# US-039 — Fix four Glass Windows C++ defects before its Rust port

**Status:** 📋 Ready (filed 2026-09-30 from the Rust-port survey of `native-glass/win`). Each site was confirmed by
reading the source. Parts 3 and 4 are already recorded in the ABI header as "separate change" follow-ups, with no
backlog story until now. Part 2 was redesigned on 2026-10-01 after PR review: re-read
`PlatformSupport.cpp:36-47,80-143,287-296`, `PlatformSupport.h:84-85`, `RoActivationSupport.cpp:114-131`,
`GlassApplication.cpp:112-132`, `glass_win_api.cpp:610-626`, `glass_win_api.h:199-201,488-570`, the Java chain from
`WinGlassNative.onPreferencesChanged` to `PlatformImpl.updatePreferences`, and the SDK's WRL `event.h` and
`implements.h` (10.0.26100.0). The agility premise of the two Java comments on the sinks was corrected the same
day. The thread on which the OS invokes the delegates was not checked. Part 1 was redesigned on 2026-10-01 after PR
review, because publishing the HWND alone leaves a recycled handle reachable: re-read `GlassApplication.cpp:66-92`
and `:178-217`, `GlassApplication.h:38-104`, `BaseWnd.cpp:30-72` and `:151-190`, `glass_win_api.cpp:396-438` and
`:534-631`, `glass_win_api.h:157-166`, `:199-250` and `:573-731`, `WinGlassNative.java:3576-3712`,
`WinApplication.java:185-196`, `:245-304` and `:510-535`, `Application.java:382-399` and `:455-481`,
`InvokeLaterDispatcher.java:96-151`, and Microsoft's reference pages for `DestroyWindow`, `WM_NCDESTROY`,
`SendMessage`, `SendNotifyMessage` and "About Messages and Message Queues" as served on 2026-10-01. A review of that
redesign the same day added `GlassApplication.cpp:44-57`, `KeyTable.cpp:380-390`, `glass_win_api.h:336-349`,
`QuantumToolkit.java:326-386` and the `InSendMessageEx` and `GetQueueStatus` pages. The Java callers of the 43
marshalled exports were not audited one by one. Nothing was built or run. · **Found:**
2026-09-30, Rust-port survey (Glass Windows) ·
**Blocks:** US-029 slice 3 (part 2), US-030 slices 4-5 (part 3), US-031 slices 3-4 (parts 1 and 4), US-052 part 1
and US-053 part 2 (part 1)

## Story
As a JavaFX app developer on Windows,
I want the Glass toolkit window, the WinRT preference sinks, the UI Automation text-range exports and the IME
composition path to be free of races, dangling objects, leaks and exceptions unwinding through `user32`,
so that a late `invoke_later`, a settings change during or after shutdown, a screen reader or an out-of-memory
IME composition cannot crash the JVM.

The Rust ports reproduce the C++'s behaviour. Fixing these first means the ports carry fixed behaviour, not these
defects. The parts can merge separately, except that part 2 posts through part 1's guarded post and so merges after
part 1.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/native-glass/win`.
1. **The toolkit HWND is read racily, and a handle read before teardown can name another window.**
   - `GlassApplication::pInstance` is written on the toolkit thread (`GlassApplication.cpp:80`, `:90`) and deleted
     after `WM_NCDESTROY` (`BaseWnd.cpp:166-168`).
   - Five accessors read it without synchronisation:
     - `GetToolkitHWND()` and `GetMainThreadId()`, which test it and then dereference it (`GlassApplication.h:78`,
       `:101-104`);
     - `GetInstance()` (`GlassApplication.h:79`);
     - `GetPlatformSupport()` (`GlassApplication.h:97-99`);
     - `ExecAction` (`GlassApplication.cpp:180-183`).
   - Four callers reach them off the toolkit thread:
     - `gwin_invoke_later`, from any thread (`glass_win_api.h:162-166`, `glass_win_api.cpp:617`);
     - `gwin_invoke_and_wait`, which tests `GetInstance()` and then calls `ExecAction` (`glass_win_api.cpp:598`,
       `:606`). `Application.invokeAndWait` sends every caller but the toolkit thread to it
       (`Application.java:460-468`, `WinApplication.java:516-518`). The tree has two such call sites,
       `J2DPrinterJob.java:142` and `:964`. `QuantumToolkit.java:382` is not one: it runs in `runToolkit()` on the
       toolkit thread (`:333-334`) and is short-circuited (`Application.java:464-465`);
     - `gwin_key_code_for_char`, which calls `GetMainThreadId()` on its caller's thread (`KeyTable.cpp:388`). The
       header says that caller is not thread-checked (`glass_win_api.h:345`);
     - the two preference queries, which call `GetPlatformSupport()` (`PlatformSupport.cpp:305`, `:322`) and which
       a WinRT sink reaches on its own thread (item 2).
   - Reading the HWND once does not make that safe. HWND values are recycled (`glass_win_api.h:643-644`). A caller
     can load the handle, `WM_NCDESTROY` can run, and the value can name a new window before `PostMessage`
     (`glass_win_api.cpp:617-626`) or `SendMessage` (`GlassApplication.cpp:183`) uses it. The call then succeeds
     against that window, which can belong to another process.
   - Both messages carry a pointer in `WPARAM` and are numbered `WM_USER+1` and `WM_USER+2` (`GlassApplication.h:68-69`,
     `GlassApplication.cpp:69-78`). That range belongs to each window class ("available for message identifiers
     for private window classes", Microsoft's "About Messages and Message Queues"). So the wrong window receives a
     message it reads its own way, on top of a runnable that never runs and a leaked action.
   - Three comments describe the single read or the re-check as sufficient: `glass_win_api.cpp:602-604` ("that
     benign race is pre-existing"), `:611-617` and `glass_win_api.h:723-729`.
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
1. Publish the toolkit window behind a guard that is held across each post, and give the synchronous call from
   another thread a rundown. Once part 2 has landed too, no thread but the toolkit thread reads `pInstance`.
   - **Publication.** A static atomic HWND and a static atomic thread id, both stored at `WM_CREATE` with no lock
     held, and one static `SRWLOCK`. The `WM_NCDESTROY` arm (`GlassApplication.cpp:88-92`) takes the lock
     exclusively, clears both and releases it. `pInstance` stays as the toolkit thread's own copy. Teardown is
     `DestroyWindow` on that thread (`glass_win_api.cpp:578-584`), the only teardown the library has. A toolkit
     thread that ends without it never reaches the arm, so nothing is cleared: that gap is US-052 part 1,
     which also moves the two `WM_CREATE` stores inside the exclusive guard, makes the clear conditional on what
     is published, and detaches the pending list inside the exclusive section, before the rundown.
   - **Readers.** Each off-thread reader of the Problem list changes as follows:
     - `gwin_invoke_later` and `ExecAction` use the guarded paths below and no longer call `GetToolkitHWND()`;
     - `gwin_invoke_and_wait` drops its `GetInstance()` test (`glass_win_api.cpp:598`). The guarded load in
       `ExecAction` decides, and the export answers what `ExecAction` reports;
     - `GetMainThreadId()` returns the published thread id, one atomic load;
     - `GetPlatformSupport()` is unchanged. A sink thread still reaches it through the queries until part 2 lands,
       and part 1 merges first.
   - **Posts.** One function takes the lock shared, loads the HWND, calls `PostMessage` and releases the lock. When
     the HWND is NULL it posts nothing and reports that. `gwin_invoke_later`, `ExecActionLater`
     (`GlassApplication.cpp:187-194`, whose one call site is `BaseWnd.cpp:70`) and part 2's sinks post through it
     and through nothing else. A post now reaches the toolkit window or is refused with `GWIN_ERR_NO_TOOLKIT`.
     Nothing relies on `PostMessage` failing for a dead handle.
   - **Why clearing in `WM_NCDESTROY` is early enough.** Documented: `DestroyWindow` sends `WM_NCDESTROY` to the
     window, which receives it through its window procedure, and only the creating thread can destroy a window.
     Inferred, not documented in those words: the handle still names this window while its procedure handles that
     message, so the value cannot go to another window before the arm returns. The arm returns only after the
     exclusive section. Every guarded call that loaded the HWND has finished by then, and every later one loads
     NULL.
   - **No self-deadlock.** The shared section holds the load and one `PostMessage`, or one `SendNotifyMessage` to a
     window of another thread, and is never nested. Documented: a thread handles sent messages only in message
     retrieval code (the `SendMessage` page). Inferred: neither call is such code, and neither runs user code on
     its caller. So a thread inside the section cannot reach `WM_NCDESTROY` there. That covers a part-2 sink and
     `~BaseWnd` on the toolkit thread.
   - **Synchronous calls on the toolkit thread.** `ExecAction` compares `GetCurrentThreadId()` with the published
     id. On the toolkit thread nothing races, because that thread is the only writer. It reads `pInstance` and
     calls `SendMessage` as today, which calls the `WndProc` inline and keeps the message count of
     `BaseWnd.cpp:176-190`. It takes no guard: an action may end the toolkit, and `WM_NCDESTROY` would wait for a
     lock its own thread holds. This is the path of the 43 `PERFORM` sites (`glass_win_api.cpp` 33,
     `GlassClipboard.cpp` 10), whose exports the header assigns to the toolkit thread (`glass_win_api.h:157-166`).
   - **Synchronous calls from another thread** (`gwin_invoke_and_wait`, which the two `J2DPrinterJob` sites reach,
     and any marshalled export called off its thread). A guard held across the cross-thread `SendMessage` would
     deadlock: a toolkit thread blocked on the exclusive side handles no sent message, and the runnable itself may
     end the toolkit. So the caller queues the message under the guard and waits outside it:
     - it builds a record on its stack: the action, a state and an event;
     - in one shared section it loads the HWND, links the record into a pending list and calls `SendNotifyMessage`
       with a third private message. Documented: from another thread that call "passes the message to the window
       procedure and returns immediately". Inferred, and pinned by the ordering test below: the toolkit thread
       handles that message where it handles today's `SendMessage`, ahead of posted messages;
     - when the HWND is NULL, or `SendNotifyMessage` answers 0, it unlinks the record under the list lock, still
       inside the section, and reports that nothing ran (`GWIN_ERR_NO_TOOLKIT`);
     - otherwise it waits for the event outside the guard, and keeps handling its own incoming sent messages
       while it waits, as `SendMessage` does (documented).

     The toolkit `WndProc` arm looks the `WPARAM` up in the list and ignores a value it does not find, without
     dereferencing it. It unlinks the record, runs `Do()` with no lock held, marks it done and sets the event. The
     `WM_NCDESTROY` arm, after the clear, unlinks every record still pending, marks it as not run and sets its
     event. Neither arm touches a record after `SetEvent`, because the caller's stack frame may be gone by then. A
     running record is not in the list, so its caller waits for `Do()` to return, as it does today. The list has its
     own small lock, which is never held across a call out of the library.
   - **Behaviour.** What runs does not change, and the order is meant not to (inferred, pinned by the ordering
     test). Six things do change, and the PR records them:
     - a post or send that lost the race with teardown is refused, not attempted;
     - `gwin_invoke_and_wait` answers `GWIN_ERR_NO_TOOLKIT` where it answers `GWIN_OK` today, when the window is
       destroyed before the runnable starts. Today the export answers `GWIN_OK` after every `ExecAction`, which
       discards the result of `SendMessage` (`glass_win_api.cpp:606-607`, `GlassApplication.cpp:183`). Java ignores
       the status (`WinGlassNative.java:3692-3699`);
     - teardown waits for a guarded call in flight, which is one `PostMessage` or one `SendNotifyMessage`;
     - the caller's wait is the library's, not `user32`'s;
     - during a call from another thread, `InSendMessageEx` on the toolkit thread answers `ISMEX_NOTIFY` where it
       answers `ISMEX_SEND` today (documented). Nothing in `native-glass/win` calls it (`git grep`). From the
       reviewer's memory, not checked: COM refuses an outgoing call from an STA that is handling an `ISMEX_SEND`
       message (`RPC_E_CANTCALLOUT_ININPUTSYNCCALL`). A marshalled action that calls OLE
       (`GlassClipboard.cpp:1441-1463`) could then behave differently when its export is called off its thread. The
       two in-tree callers run `stage.setEnabled` and a layout pass, not COM;
     - a toolkit thread that ends with a record pending leaves the caller waiting, because nothing runs the
       rundown. Today `SendMessage` is said to return when the receiving thread ends (from memory, not run). A call
       made after the thread ended is expected to be refused, as long as the dead HWND was not reused. US-052
       settles part of that. Documented: a terminated thread's windows are freed ("Terminating a Thread").
       Measured on US-052's probe: the window procedure gets no `WM_NCDESTROY`, and `PostMessage` to the dead
       handle fails with error 1400. Not measured: what `SendNotifyMessage` and `SendMessage` answer for it. The
       gap is the one US-052 part 1 closes, and its thread-exit rundown releases these callers. Part 1 ships with
       this hang until US-052 part 1 lands, so US-052 follows part 1 directly. Waiting on the toolkit thread as
       well was rejected: it needs a thread handle per call and a second way out for a record that is already
       running, for a case that exists only inside that gap.
   - **Comments.** The same change corrects the text that calls the race benign or the single read enough, or that
     names `SendMessage` for the cross-thread path: `glass_win_api.cpp:417-421`, `:602-604`, `:611-617`;
     `glass_win_api.h:162` ("There are no locks"), `:592-596`, `:676-683`, `:704-711`, `:723-729`;
     `WinGlassNative.java:154`, `:3635-3649`; `WinApplication.java:510-515`.
   - **Not verified.** Whether a message posted before the clear can stay in the toolkit thread's queue once the
     window is gone. The current `DestroyWindow` page does not say that the queue is flushed. The acceptance
     criteria observe it. If one stays, the `WM_NCDESTROY` arm removes them from the queue after the exclusive
     section and deletes the `WM_DO_ACTION_LATER` actions, and this story is updated first. Once US-053 part 2 has
     landed, it deletes only the actions it unlinks from that story's list.
   - **Considered and rejected:**
     - a shared guard across the cross-thread `SendMessage`: the two deadlocks above;
     - an exclusive acquire that keeps handling sent messages: it runs runnables inside `WM_NCDESTROY`, and it never
       ends when the sender whose runnable ended the toolkit still holds the shared side;
     - `SendMessageTimeout` under the guard: the deadlock becomes a stall, and in the second case the sender
       returns while its stack action is still running;
     - post and wait, in C or through `InvokeLaterDispatcher.invokeAndWait` as GTK does (`GtkApplication.java:351`):
       a posted message waits its turn in the queue, where a sent one is handled first and inside the toolkit
       thread's own `SendMessage` waits. That reorders `invokeAndWait` against pending `invokeLater` runnables. It
       needs the same rundown, too (`InvokeLaterDispatcher.java:141-151` waits without one). It is the only
       materially simpler design that is correct. The cross-thread path has two in-tree call sites
       (`J2DPrinterJob.java:142`, `:964`), so a maintainer may rule for post and wait and accept the reorder as a
       recorded change. This story keeps the order, under the fork's behaviour-neutrality rule;
     - a generation or ownership check alone, such as a per-instance cookie in `LPARAM` that the toolkit `WndProc`
       validates. A recycled Glass window would reject the message, but foreign code never runs the check and gets
       a `WM_USER` message it defines itself. A `RegisterWindowMessage` number would be ignored there by convention
       only: the post would still report `GWIN_OK` and leak its action, and a send could block on a foreign thread.
       It is not added on top either. With the guard no message leaves for any window but the toolkit window, and a
       cookie would be a second mechanism for the Rust port to carry;
     - rarity. USER handle values are said to carry a reuse count in their upper 16 bits, so that one value comes
       back only after its slot was reused 65,536 times (from memory of Raymond Chen's "The Old New Thing"; not an
       API contract, not checked). That would make the race rare. It is not the argument here: the ABI header
       treats recycling as real, and the fix does not depend on the count.
2. Make the sinks post to the toolkit window instead of calling Java, and release the WinRT objects before
   `RoUninitialize`.
   - **Sinks only post.** Each delegate captures nothing that teardown frees, only its `PT_*` bits. It posts a
     private message carrying them to the toolkit window through part 1's guarded post, the one `gwin_invoke_later`
     uses for `WM_DO_ACTION_LATER` (`glass_win_api.cpp:610`, `:626`). The post is refused once the HWND is cleared
     at `WM_NCDESTROY`, and the delegate returns `S_OK`. That is safe on any thread, so it no longer matters whether
     the OS marshals a delegate into the toolkit thread's STA or calls it on a thread of its own. The guard spans
     the `PostMessage` and nothing else. No lock is held across a call into Java.
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
     the OS still invokes either posts while the toolkit window still exists or finds the HWND cleared and posts
     nothing. Part 1's guard leaves no third case, so no post leaves for a destroyed or recycled window.
   - **Rundown.** Only the toolkit window's `WndProc` calls the stub, on the toolkit thread, and that thread
     retrieves no message after `gwin_run_loop` returns: it goes on to end (`WinApplication.java:256-261`). So no
     upcall starts after the return. That holds even when the pump ends with the window alive, the case of US-052
     part 1 (`glass_win_api.cpp:560`). Java then clears the table with
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
     would make teardown wait for a lock its own thread holds. The queries are toolkit-thread-only as well. Part 1's
     guard is not that lock: it is held across one `PostMessage`, which runs no listener and no window procedure, so
     its holder cannot end the toolkit while holding it.
3. Return early on NULL. Call `VariantClear` on both VARIANTs after raising the event.
4. Catch `std::bad_alloc` inside the IME helpers and take the existing failure path, as the header describes. No
   exception may leave the window procedure.

## Acceptance criteria
- **Part 1:** the tests use these shim-only hooks, which do nothing in production unless a test arms them:
  - a gate that parks a caller inside the shared section, between the load and the `PostMessage`, and signals that
    it is parked. It parks on a kernel wait and does nothing there that can need the loader lock: US-052 part 1
    takes the exclusive side under that lock;
  - counters: the `PostMessage` and `SendNotifyMessage` calls, those of them whose target was not the published
    HWND at the time of the call ("stale"), and the actions allocated and freed;
  - a send of the third private message to the toolkit window with a `WPARAM` the caller chooses;
  - a peek of the calling thread's queue with no window filter, which answers the HWND of every private message
    it finds;
  - decoy windows on a hook-owned thread that pumps, whose procedure records every private message it receives;
  - a query, for the calling thread, of whether a sent message is pending: `GetQueueStatus(QS_SENDMESSAGE)`.
    Documented: that flag means "A message sent by another thread or application is in the queue". Inferred: the
    call dispatches nothing, and a `SendNotifyMessage` sets the flag as a `SendMessage` does.

  With them:
  - a parked `gwin_invoke_later` holds teardown. `gwin_terminate_loop` on the toolkit thread does not return within
    a bounded wait while the poster is parked, and returns once it is released. The stale count is 0. A mutant that
    posts without the shared section, which is the atomic HWND alone, fails both checks;
  - after the clear, every `gwin_invoke_later` answers `GWIN_ERR_NO_TOOLKIT`, calls `PostMessage` zero times and
    frees its action. That includes one issued by the runnable that called `gwin_terminate_loop`, after that call
    returned. A mutant that clears in `~GlassApplication`, not in the `WM_NCDESTROY` arm, fails this;
  - order, captured on the C++ before the change and unchanged after it: while the toolkit thread is busy in a
    runnable, thread A calls `gwin_invoke_later(L)` and then thread B calls `gwin_invoke_and_wait(W)`; W runs before
    L. The busy runnable returns only after A's call has returned and the pending-send query, polled inside that
    runnable, answers yes; no counter can signal W on the unchanged C++, where B blocks in `SendMessage`. A mutant
    that posts W and waits fails this, by timing out in that poll or by running L first;
  - a `gwin_invoke_and_wait` called on the toolkit thread, through the shim, whose runnable calls
    `gwin_terminate_loop`, returns. A mutant that takes the guard on that path, or always takes the cross-thread
    path, fails this by timing out;
  - a `gwin_invoke_and_wait` from a second thread, queued while the toolkit thread runs a runnable that then calls
    `gwin_terminate_loop`, returns within a timeout and answers `GWIN_ERR_NO_TOOLKIT` exactly when its runnable did
    not run. Not running is the expected outcome, and the PR records which one occurred, because whether
    `DestroyWindow` first hands a queued sent message to the window was not checked. A mutant without the rundown
    in the `WM_NCDESTROY` arm fails the expected outcome by timing out;
  - a `gwin_invoke_and_wait` from a second thread whose runnable calls `gwin_terminate_loop` answers `GWIN_OK` after
    the runnable, within a timeout. A mutant that holds the shared side across the wait fails this by timing out;
  - the third private message sent with a `WPARAM` that is in no list is ignored, in a child JVM. A mutant that
    dereferences the value crashes that JVM;
  - handle reuse. No documented API forces a HWND value to be reused, so the regular test pins the stale count,
    which is the stronger property: it fails on every call to a HWND that is no longer published, reused or not.
    For the first mutant, the PR records one run in which decoy windows are created and destroyed until one takes
    the parked HWND value; the released poster's `WM_DO_ACTION_LATER` then arrives at that decoy. The cap on that
    loop exceeds the 65,536 reuses of one slot cited above, or the run could not succeed. If the cap is reached
    without reuse, the PR says so;
  - an observation for the "Not verified" item, with no mutant: after `gwin_terminate_loop` returns, the peek runs
    on the toolkit thread and the PR records whether it found a private message carrying the destroyed HWND,
    whether or not the parked post succeeded;
  - a statistical line beside these: a test hammers `gwin_invoke_later`, `gwin_invoke_and_wait` and
    `gwin_key_code_for_char` from other threads across toolkit shutdown, in repeated child JVMs. It records no
    access violation and a stale count of 0. It runs no decoy windows: under the fix nothing can reach one, so
    they would add almost no detection power. The PR states the run count;
  - the PR lists the readers of `pInstance` that remain. None of them is reachable off the toolkit thread, except
    `GetPlatformSupport()` through the two queries until part 2 lands.
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
  never bump it. Part 1 keeps the prototype and the two statuses of `gwin_invoke_and_wait` and `gwin_invoke_later`
  (`glass_win_api.h:703-731`) and corrects the header's threading text, which says "There are no locks" (`:162`).
  Part 2 only narrows the header's THREAD text (the sinks post, and `preferences_changed` runs on the toolkit
  thread) and states the rundown at `GwinPrefsCallbacks`; the Java arena change lands with the C.

## Definition of Done
Merged and verified on Windows. `backlog/README.md` is updated. For each part, the PR checks whether upstream
openjdk/jfx shares the defect, and drafts an upstream issue where it does.
