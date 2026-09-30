# US-039 — Fix four Glass Windows C++ defects before its Rust port

**Status:** 📋 Ready (filed 2026-09-30 from the Rust-port survey of `native-glass/win`). Each site was confirmed by
reading the source. Parts 3 and 4 are already recorded in the ABI header as "separate change" follow-ups, with no
backlog story until now. Nothing was built or run. · **Found:** 2026-09-30, Rust-port survey (Glass Windows) ·
**Blocks:** US-029 slice 3 (part 2), US-030 slices 4-5 (part 3), US-031 slices 3-4 (parts 1 and 4)

## Story
As a JavaFX app developer on Windows,
I want the Glass toolkit window, the WinRT preference sinks, the UI Automation text-range exports and the IME
composition path to be free of races, dangling objects, leaks and exceptions unwinding through `user32`,
so that a late `invoke_later`, a settings change after shutdown, a screen reader or an out-of-memory IME
composition cannot crash the JVM.

The Rust ports reproduce the C++'s behaviour. Fixing these first means the ports carry fixed behaviour, not these
defects. The four parts can merge separately.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/native-glass/win`.
1. **The toolkit HWND is read racily.**
   - `GlassApplication::pInstance` is written on the toolkit thread (`GlassApplication.cpp:80`, `:90`) and deleted
     after `WM_NCDESTROY` (`BaseWnd.cpp:166-168`).
   - Other threads read it without synchronisation: through `GetToolkitHWND()`, which tests it and then dereferences
     it (`GlassApplication.h:78`), and through `ExecAction` (`GlassApplication.cpp:180-183`).
   - `gwin_invoke_later` may be called from any thread (`glass_win_api.h:162-166`).
   - The comment at `glass_win_api.cpp:611-617` describes this as safe, which is inaccurate.
2. **The WinRT preference sinks outlive their object.**
   - Both event delegates capture a raw `this` (`PlatformSupport.cpp:90`, `:115`) and run on WinRT threads
     (`glass_win_api.h:508-512`).
   - Their `EventRegistrationToken`s are discarded and nothing unregisters them (`PlatformSupport.cpp:87`, `:112`,
     `:126-130`; `glass_win_api.h:514-521`). An event after `~PlatformSupport` calls a destroyed object.
   - The raw `IActivationFactory*` is never released (`PlatformSupport.cpp:101-110`).
   - This is also why the Java `GwinPrefsCallbacks` stub has to live in `Arena.global()` (`glass_win_api.h:514-521`).
3. **The UIA text-range exports crash on NULL and leak a BSTR.**
   - `gwin_a11y_text_range_destroy(NULL)` dereferences its argument (`glass_win_api.h:2898-2901`).
   - `gwin_a11y_raise_property_changed` leaks the BSTR of a `VT_BSTR` value, because neither VARIANT is cleared
     (`:2913-2916`).
4. **`bad_alloc` unwinds through `user32`.** `GetClauseInfo`/`GetAttributeInfo` rethrow `std::bad_alloc` through
   `WmImeComposition` into `user32` frames (`glass_win_api.h:851-857`), which the header records as undefined
   behaviour.

## Proposed fix
1. Publish the toolkit HWND through a `std::atomic<HWND>`, set at `WM_CREATE` and cleared at `WM_NCDESTROY`. Make
   `gwin_invoke_later` and `ExecAction` load it once and never touch `pInstance` off the toolkit thread. Correct the
   comment.
2. Keep both tokens, call `remove_*` in `~PlatformSupport`, and release the factory. Then scope the Java stub to the
   application's lifetime instead of `Arena.global()`, tested with the same trace.
3. Return early on NULL. Call `VariantClear` on both VARIANTs after raising the event.
4. Catch `std::bad_alloc` inside the IME helpers and take the existing failure path, as the header describes. No
   exception may leave the window procedure.

## Acceptance criteria
- **Part 1:** a test hammers `gwin_invoke_later` from a second thread across toolkit shutdown, in repeated child
  JVMs, and records no access violation. The PR states the run count.
- **Part 2:** a recording trace shows no `preferences_changed` after the application is disposed, and the factory's
  reference count returns to its starting value.
- **Part 3:** `gwin_a11y_text_range_destroy(NULL)` returns without a crash. A `VT_BSTR` property-change loop leaks
  no BSTR (a `SysAllocString`/`SysFreeString` balance through a test hook).
- **Part 4:** a fault-injected `bad_alloc` during composition leaves the IME path through its failure branch, and no
  C++ exception reaches `user32`.
- `WinApplicationNativeTest`, `WinPreferencesNativeTest`, `WinAccessibilityNativeTest` and `WinViewNativeTest` are
  unchanged and pass.
- The export list is unchanged. `GLASS_WIN_ABI_VERSION` changes only if the Java stub change in part 2 needs it,
  under the header's bump rules.

## Definition of Done
Merged and verified on Windows. `backlog/README.md` is updated. For each part, the PR checks whether upstream
openjdk/jfx shares the defect, and drafts an upstream issue where it does.
