# US-039 — Fix four Glass Windows C++ defects before its Rust port

**Status:** 📋 Ready (filed 2026-09-30 from the Rust-port survey of `native-glass/win`). Each site was confirmed by
reading the source. Parts 3 and 4 are already recorded in the ABI header as "separate change" follow-ups, with no
backlog story until now. Part 2 was redesigned on 2026-10-01 after PR review: re-read
`PlatformSupport.cpp:36-47,80-143,287-296`, `PlatformSupport.h:84-85`, `RoActivationSupport.cpp:114-131`,
`GlassApplication.cpp:112-132`, `glass_win_api.cpp:610-626`, `glass_win_api.h:199-201,488-570`, the Java chain from
`WinGlassNative.onPreferencesChanged` to `PlatformImpl.updatePreferences`, and the SDK's WRL `event.h` and
`implements.h` (10.0.26100.0). The agility premise of the two Java comments on the sinks was corrected the same
day. The thread on which the OS invokes the delegates was not checked. Nothing was built or run. · **Found:**
2026-09-30, Rust-port survey (Glass Windows) ·
**Blocks:** US-029 slice 3 (part 2), US-030 slices 4-5 (part 3), US-031 slices 3-4 (parts 1 and 4)

## Story
As a JavaFX app developer on Windows,
I want the Glass toolkit window, the WinRT preference sinks, the UI Automation text-range exports and the IME
composition path to be free of races, dangling objects, leaks and exceptions unwinding through `user32`,
so that a late `invoke_later`, a settings change during or after shutdown, a screen reader or an out-of-memory
IME composition cannot crash the JVM.

The Rust ports reproduce the C++'s behaviour. Fixing these first means the ports carry fixed behaviour, not these
defects. The parts can merge separately, except that part 2 posts through part 1's atomic HWND and so merges after
part 1.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/native-glass/win`.
1. **The toolkit HWND is read racily.**
   - `GlassApplication::pInstance` is written on the toolkit thread (`GlassApplication.cpp:80`, `:90`) and deleted
     after `WM_NCDESTROY` (`BaseWnd.cpp:166-168`).
   - Other threads read it without synchronisation: through `GetToolkitHWND()`, which tests it and then dereferences
     it (`GlassApplication.h:78`), and through `ExecAction` (`GlassApplication.cpp:180-183`).
   - `gwin_invoke_later` may be called from any thread (`glass_win_api.h:162-166`).
   - The comment at `glass_win_api.cpp:611-617` describes this as safe, which is inaccurate.
2. **The WinRT preference sinks outlive their object, and unregistering them alone would not help.**
   - Both event delegates capture a raw `this` (`PlatformSupport.cpp:90`, `:115`) and call Java on whatever thread
     the OS invokes them on (`glass_win_api.h:508-512`).
   - That thread may be the toolkit thread. WRL `Callback<>` delegates are `RuntimeClassFlags<Delegate>` classes
     (`winrt/wrl/event.h:348`, `:376`), and `Delegate` is `ClassicCom` (`winrt/wrl/implements.h:73`), which adds no
     free-threaded marshaler. They are not agile, so an OS event source may marshal them into the toolkit thread's
     STA or call them on a thread of its own. Both Java comments on the sinks say so since 2026-10-01
     (`WinPreferences.java:92-97`, `WinGlassNative.java:3402-3406`).
   - A sink on another thread reaches `pInstance` through the queries: `WinPreferences.collect` calls
     `gwin_prefs_query_*`, which read it through `GetPlatformSupport()` (`GlassApplication.h:97-99`), although the
     header says to call them on the toolkit thread (`glass_win_api.h:565-567`).
   - Their `EventRegistrationToken`s are discarded and nothing unregisters them (`PlatformSupport.cpp:87`, `:112`,
     `:126-130`; `glass_win_api.h:514-521`). An event after `~PlatformSupport` calls a destroyed object.
   - `remove_*` does not wait for an invocation already in flight. An event source takes a reference to its
     delegate list under a lock and invokes outside it, as the SDK's WRL `EventSource` does (`winrt/wrl/event.h`,
     `DoInvoke` and `Remove`). So a delegate can still run after `remove_*` returns, into a destroyed
     `PlatformSupport` or a freed Java stub.
   - `updatePreferences` loads the callback slot twice, to test it and to call it (`PlatformSupport.cpp:142-143`).
     That is harmless while Java never clears the table, and a call through NULL once it does.
   - The raw `IActivationFactory*` and the raw `INetworkInformationStatics*` queried from it (`PlatformSupport.h:85`,
     `PlatformSupport.cpp:110`) are never released (`PlatformSupport.cpp:101-110`). `~PlatformSupport` resets only
     `settings` before `uninitializeRoActivationSupport()` (`:126-130`), which calls `RoUninitialize` and frees
     combase (`RoActivationSupport.cpp:114-131`).
   - This is also why the Java `GwinPrefsCallbacks` stub has to live in `Arena.global()` (`glass_win_api.h:514-521`,
     `WinGlassNative.java:3365-3377`).
3. **The UIA text-range exports crash on NULL and leak a BSTR.**
   - `gwin_a11y_text_range_destroy(NULL)` dereferences its argument (`glass_win_api.h:2898-2901`).
   - `gwin_a11y_raise_property_changed` leaks the BSTR of a `VT_BSTR` value, because neither VARIANT is cleared
     (`:2913-2916`).
4. **`bad_alloc` unwinds through `user32`.** `GetClauseInfo`/`GetAttributeInfo` rethrow `std::bad_alloc` through
   `WmImeComposition` into `user32` frames (`glass_win_api.h:851-857`), which the header records as undefined
   behaviour.

## Proposed fix
1. Publish the toolkit HWND through a `std::atomic<HWND>`, set at `WM_CREATE` and cleared at `WM_NCDESTROY`. Make
   `gwin_invoke_later`, `ExecAction` and part 2's sinks load it once and never touch `pInstance` off the toolkit
   thread. Correct the comment.
2. Make the sinks post to the toolkit window instead of calling Java, and release the WinRT objects before
   `RoUninitialize`.
   - **Sinks only post.** Each delegate captures nothing that teardown frees, only its `PT_*` bits. It posts a
     private message carrying them to the toolkit window through part 1's atomic HWND, the way `gwin_invoke_later`
     posts `WM_DO_ACTION_LATER` (`glass_win_api.cpp:610`, `:626`). It posts nothing once that HWND is cleared at
     `WM_NCDESTROY`, and it returns `S_OK`. That is safe on any thread, so it no longer matters whether the OS
     marshals a delegate into the toolkit thread's STA or calls it on a thread of its own.
   - **The toolkit `WndProc` handles the message** through the path of the four existing arms
     (`GlassApplication.cpp:112-132`): `updatePreferences`, which now loads the slot once. So
     `preferences_changed` and the two queries run only on the toolkit thread. That matches the queries' THREAD
     rule (`glass_win_api.h:565-567`) and keeps the setter's "No lock, by design" (`:539-542`) true. The same
     change updates the comments that still allow a second thread: the sinks' THREAD note
     (`glass_win_api.h:508-512`), the `WinPreferences` "Threading." paragraph (`WinPreferences.java:90-100`) and the
     `WinGlassNative` "Thread." paragraph (`WinGlassNative.java:3400-3408`). After the fix, "the upcall stub attaches
     such a thread" and "written for that second thread" no longer hold.
   - **Teardown.** `~PlatformSupport` removes both registrations with the kept tokens. It then releases `settings`,
     `networkInformation` and the factory explicitly in the destructor body, before
     `uninitializeRoActivationSupport()`; member `ComPtr`s would otherwise be released after it. A delegate that
     the OS still invokes finds the HWND cleared and posts nothing, or posts to a destroyed window, where
     `PostMessage` fails as it does for a late `gwin_invoke_later`.
   - **Rundown.** Only the toolkit window's `WndProc` calls the stub, and `gwin_run_loop` returns only after that
     window is destroyed, so no upcall starts after it returns. Java then clears the table with
     `gwin_prefs_set_callbacks(NULL, NULL)` and closes the stub's arena, an application-scoped `Arena.ofShared()`
     instead of `Arena.global()`, on the toolkit thread where `WinGlassNative.runLoop` returns
     (`WinApplication.java:260`). That clear comes after the toolkit thread's last read, so the setter needs no
     lock. Embedded mode never calls `gwin_run_loop` (`WinApplication.java:249-255`) and keeps `Arena.global()`.
     The header states this in place of today's "no point" text (`glass_win_api.h:514-520`, `:529-546`).
   - **Behaviour.** A sink's notification now reaches Java asynchronously, on the toolkit thread, which is the FX
     thread here. `PlatformImpl.updatePreferences` runs the listeners inline there (`PlatformImpl.java:948-951`),
     as it does for the four arms today. Only the timing changes, and the PR records it.
   - **Considered and rejected:** keeping the upcall on the sink's thread behind an SRWLOCK rundown. The delegates
     are not agile and may run on the toolkit thread, where a listener that ends the toolkit (`Platform.exit()`)
     would make teardown wait for a lock its own thread holds. The queries are toolkit-thread-only as well.
3. Return early on NULL. Call `VariantClear` on both VARIANTs after raising the event.
4. Catch `std::bad_alloc` inside the IME helpers and take the existing failure path, as the header describes. No
   exception may leave the window procedure.

## Acceptance criteria
- **Part 1:** a test hammers `gwin_invoke_later` from a second thread across toolkit shutdown, in repeated child
  JVMs, and records no access violation. The PR states the run count.
- **Part 2:** a shim-only test hook invokes both registered delegates the way an event source does, through
  references taken at registration. With it:
  - a second thread invokes them in a loop across teardown, in repeated child JVMs (the PR states the run count),
    with no access violation;
  - the recording table logs the thread of every `preferences_changed`: always the toolkit thread, and none after
    `gwin_run_loop` returns. A mutant that calls `updatePreferences` from the sink fails this;
  - a delegate invoked after `WM_NCDESTROY` posts nothing (the hook counts the posts);
  - a delegate invoked on the toolkit thread, whose listener calls `Platform.exit()`, lets the JVM exit within a
    timeout;
  - the WinRT objects and the factory are released before `RoUninitialize`, observed by a shim hook or a reference
    count check;
  - the PR records, from one manual run (toggle "Automatically hide scroll bars", drop the network), the thread on
    which the OS invoked each delegate.
- **Part 3:** `gwin_a11y_text_range_destroy(NULL)` returns without a crash. A `VT_BSTR` property-change loop leaks
  no BSTR (a `SysAllocString`/`SysFreeString` balance through a test hook).
- **Part 4:** a fault-injected `bad_alloc` during composition leaves the IME path through its failure branch, and no
  C++ exception reaches `user32`.
- `WinApplicationNativeTest`, `WinPreferencesNativeTest`, `WinAccessibilityNativeTest` and `WinViewNativeTest` are
  unchanged and pass.
- No export that `WinGlassNative` binds is added or removed, and no prototype, layout or enum value changes. So
  `GLASS_WIN_ABI_VERSION` stays under the header's rule (`glass_win_api.h:199-201`), and shim-only test hooks
  never bump it. Part 2 only narrows the header's THREAD text (the sinks post, and `preferences_changed` runs on
  the toolkit thread) and states the rundown at `GwinPrefsCallbacks`; the Java arena change lands with the C.

## Definition of Done
Merged and verified on Windows. `backlog/README.md` is updated. For each part, the PR checks whether upstream
openjdk/jfx shares the defect, and drafts an upstream issue where it does.
