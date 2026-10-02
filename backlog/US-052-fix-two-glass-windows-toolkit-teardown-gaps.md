# US-052 — Fix two Glass Windows toolkit teardown gaps: a toolkit thread that ends undestroyed, and leaked classes

**Status:** 📋 Ready (filed 2026-10-01; found while redesigning US-039 part 1 after PR review; revised the same day
after a review of this story, and again after the PR review of that revision, which replaced part 1's
fiber-local-storage callback, and once more after a review of that change; read: `GlassApplication.cpp:44-135`,
`:178-194` and `:245-249`, `GlassApplication.h:78-104`, `BaseWnd.cpp:30-190`, `glass_win_api.cpp:518-584` and
`:596-631`, `glass_win_api.h:199-201`, `:592-598`, `:636-650` and `:653-685`, `PlatformSupport.cpp:65-144`,
`RoActivationSupport.cpp:65-112`, `OleUtils.h:150-164`, `WinApplication.java:185-196` and `:245-304`,
`Application.java:382-399`, `PlatformImpl.java:566-572`, `QuantumToolkit.java:862-882`, one `git grep` for
`PostQuitMessage` and `WM_QUIT` under `modules`, and these Microsoft pages as served on 2026-10-01: "Window
Features" ("Window Destruction"), `GetCurrentThreadId`, "Terminating a Thread", "DllMain entry point",
"Dynamic-Link Library Best Practices", `ExitProcess`, `ExitThread`, `DisableThreadLibraryCalls`, `DeleteFiber`,
`ConvertFiberToThread`, `PFLS_CALLBACK_FUNCTION`, "Error at thread exit if FLS callback isn't freed"
(KB 2754614), "PE Format", "Thread Handles and Identifiers" and `RegisterWaitForSingleObject`; not read: SWT's
side of the embedded mode; not checked: every point marked so below; built and run: one standalone probe, on
Windows 10.0.19045.6466 x64 with glass.dll's compiler and linker flags; nothing built or run against glass.dll
itself) ·
**Evidence:** `US-052-thread-end-probe-results.md` (this folder), with the probe's sources beside it. The scenario
numbers S1 to S12 below are its sections · **Found:** 2026-10-01, redesign of US-039 part 1 ·
**Blocked by:** US-039 part 1 (part 1 extends its publication and reuses its test hooks; part 2 is independent) ·
**Blocks:** none. Part 1 should follow US-039 part 1 directly, because it ends a hang that part ships with, and the
story should precede US-031 slice 3, which otherwise reproduces both gaps in Rust

## Story
As a JavaFX app developer on Windows, embedding JavaFX or running it more than once in a process,
I want the Glass toolkit to notice that its thread ended without `gwin_terminate_loop`, and to unregister its
window classes when the toolkit ends,
so that a late `invoke_later` or `invokeAndWait` is refused instead of reaching a dead or recycled window, and no
window class stays registered for a toolkit that is gone.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/native-glass/win`.
1. **A toolkit thread that ends without `DestroyWindow` leaves the toolkit published for ever.**
   - Only the `WM_NCDESTROY` arm clears `pInstance` (`GlassApplication.cpp:88-92`), and only `gwin_terminate_loop`
     destroys the toolkit window (`glass_win_api.cpp:578-584`). US-039 part 1 keeps both: its publication (HWND and
     thread id) is cleared in that arm and nowhere else.
   - Two paths end the thread with the window alive:
     - SWT-embedded mode creates the toolkit window on the embedder's thread and never calls `gwin_run_loop`
       (`WinApplication.java:245-255`, `glass_win_api.h:597-598`). Nothing in the library runs when that thread
       ends. Whether an embedder ends it while JavaFX is still in use was not checked;
     - the pump leaves its loop when `GetMessage` answers 0 or -1 while the instance is alive
       (`glass_win_api.cpp:560`), so a `WM_QUIT` on that thread's queue makes `gwin_run_loop` return and the
       `WindowsNativeRunloopThread` end (`WinApplication.java:256-261`). This trigger needs foreign code: nothing in
       `native-glass/win` or `com/sun/glass/ui/win` posts `WM_QUIT`, and the only `PostQuitMessage` under `modules`
       is WebKit's `RunLoopWin.cpp:102` (`git grep`). Whether that file is built here, or runs on the toolkit
       thread, was not checked.
   - Documented: an application must destroy the windows it creates, with `DestroyWindow` ("Window Features",
     "Window Destruction"), and when a thread terminates, "Any resources owned by the thread, such as windows and
     hooks, are freed" ("Terminating a Thread"). Measured: the window of a thread that ends is invalid once the
     thread handle is signaled, `PostMessage` to it then fails with error 1400, and its window procedure receives
     neither `WM_DESTROY` nor `WM_NCDESTROY` (S1, S10). So the `WM_NCDESTROY` arm does not run on these paths.
   - Today: `pInstance` keeps pointing at an object that is never deleted, and its HWND is dead or recycled
     (`glass_win_api.h:643-644`). `gwin_invoke_later` and `ExecAction` post and send to that value
     (`glass_win_api.cpp:617-626`, `GlassApplication.cpp:180-183`).
   - After US-039 part 1: the guard does not help, because the HWND it guards is never cleared. A guarded call goes
     to the stale value. A `gwin_invoke_and_wait` that was queued before the thread ended waits for a rundown that
     never comes; US-039 part 1 records that hang and leaves it to this story.
   - The published thread id goes stale as well. Documented: "Until the thread terminates, the thread identifier
     uniquely identifies the thread throughout the system" (`GetCurrentThreadId`), so a later thread can receive
     the same id. It then takes the toolkit-thread branch of US-039 part 1's `ExecAction`: it reads `pInstance` and
     calls `SendMessage` with no guard. US-039 part 1's argument that a published id names a live thread rests on
     the clear in `WM_NCDESTROY`.
2. **Window classes stay registered when the toolkit ends.**
   - Each `BaseWnd` registers its own class, `GlassWndClass-<suffix>-<counter>` (`BaseWnd.cpp:86-106`).
   - `~BaseWnd` cannot unregister it, because it runs inside `WM_NCDESTROY` while the window still exists
     (`BaseWnd.cpp:53-55`). It posts the `UnregisterClass` through `ExecActionLater` (`BaseWnd.cpp:56-70`).
   - For the toolkit window that post goes to the window being destroyed. `pInstance` is already NULL, so
     `ExecActionLater` deletes the action and returns (`GlassApplication.cpp:187-192`). Its class is never
     unregistered.
   - For a window that is open at exit, the action is posted but never dispatched. `Application.terminate` closes
     the windows and then ends the toolkit with no pump in between (`Application.java:382-399`), and the pump stops
     once the instance is gone and does not drain the queue (`glass_win_api.h:660-663`).
   - The counter keeps the names unique, so a new toolkit in the same process does not collide. The cost is one
     leaked class per toolkit and per window open at exit, each with a procedure that points into glass.dll.

## Proposed fix
1. Clear the publication on the toolkit thread when that thread ends, whether or not `WM_NCDESTROY` arrives.
   - **One teardown function**, safe to call twice, replaces US-039 part 1's teardown step. Inside the exclusive
     guard it compares, and on a match it clears the HWND and the thread id and detaches the pending list under
     the list lock: guard, then list lock, the order in which a caller links or unlinks a record inside the
     shared section. After releasing the guard it marks each detached record as not run and sets its event. A
     rundown of the shared list after the release could refuse a record linked for a toolkit published since.
   - **Two callers, two compares.** The `WM_NCDESTROY` arm compares the published HWND with its own window.
     glass.dll gets a `DllMain` that returns TRUE at once for every Reason but `DLL_THREAD_DETACH`. For that one
     it loads the published thread id once and returns unless it equals `GetCurrentThreadId()`: the notification
     comes for every thread that ends in the process, so the common path takes no lock. On a match it calls the
     function, which compares the thread id again under the guard, against what is published at that moment. So
     a thread that ends after its toolkit was torn down or replaced leaves the other toolkit published, and so
     does the `WM_NCDESTROY` of a window that is no longer the published one, also when one thread created
     both. Nothing is stored per thread or per fiber at `WM_CREATE` beyond the publication.
   - **Publication under the guard.** `WM_CREATE` stores the HWND and then the thread id inside the exclusive
     guard, where US-039 part 1 stores them with no lock. A second `gwin_app_create` is allowed ("A second call
     leaks the first instance", `glass_win_api.h:646-647`), so a thread whose toolkit was never torn down can
     end while another thread publishes. With unguarded stores it can find its own id under the guard after the
     new HWND was stored, clear both, and leave HWND NULL beside the new thread's id: every post refused for a
     live toolkit. With guarded stores either the clear or the publication comes first, and in the second case
     the compare fails. The acquire is uncontended, once per toolkit, and cannot deadlock on itself, by US-039
     part 1's argument: a thread inside the shared section is in one `PostMessage` or one `SendNotifyMessage` to
     another thread's window and cannot reach `WM_CREATE` there, the toolkit thread's own synchronous path takes
     no guard, and an exclusive holder calls nothing outside the library.
   - **Thread exit only.** Documented ("DllMain entry point"): `DLL_THREAD_DETACH` means "A thread is exiting
     cleanly", "The call is made in the context of the exiting thread", and it also comes for a thread that "was
     already running when a call to the LoadLibrary function was made". It does not come for `TerminateThread`,
     nor per thread when the process ends or the DLL is unloaded: "The DLL is only sent a DLL_PROCESS_DETACH
     notification". Measured with a `DllMain` in the probe DLL: it fires on the exiting thread at each kind of
     thread exit that was run (return, `ExitThread`, and `DeleteFiber` of the running fiber, which is documented
     to end the thread), also for a thread that predates the load, and at nothing else: not at
     `ConvertFiberToThread`, the review's example (8 of 8 runs), at `DeleteFiber` of a sibling fiber, at process
     exit, at `FreeLibrary` or at `TerminateThread` (S11's table).
   - **Timing.** Measured: the notification runs while the window still passes `IsWindow` and is owned by the
     exiting thread (S11). So the HWND still names the toolkit window during the clear, as US-039 part 1 requires
     of the `WM_NCDESTROY` arm. That order is not documented.
   - **Loader lock.** Documented: "Threads in DllMain hold the loader lock" ("DllMain entry point"). Measured: held
     inside the notification (S11). The arm departs from Microsoft's advice in one respect. "Dynamic-Link Library
     Best Practices" lists "Synchronize with other threads. This can cause a deadlock." among the things never to
     do in `DllMain`, and "DllMain entry point" says entry points "should not attempt to communicate with other
     threads or processes". The arm waits for a guard that other threads hold, and it sets events. It is argued
     safe under the rule "Dynamic-Link Library Best Practices" gives for a private lock, "The loader lock must be
     at the bottom of this hierarchy": no holder of the guard or of the list lock can need the loader lock.
     - On this path the function calls nothing in `user32`, loads no library, runs no code from outside the
       library and waits only for the guard and the list lock. It clears the two atomics and detaches the
       pending list, and after the release it marks the detached records and sets their events.
     - The rest of teardown stays in the `WM_NCDESTROY` arm, after the function returns: US-039 part 1's fallback
       that removes private messages left in the queue (`user32`), and US-053 part 2's delete of posted actions
       that were never dispatched (a virtual destructor). On the `DllMain` path that list is only detached, and
       its actions leak as they do today.
     - The holders: an exclusive holder stores or clears the two atomics and detaches the list, a holder of the
       list lock does list operations only, and a shared holder is inside one `PostMessage` or one
       `SendNotifyMessage` to a window of another thread. Measured: neither call waited for the loader lock, and
       an exiting thread that waited for a parked shared holder finished its exit (S8a-d, S9a-c, S11; one build).
       Not measured: `SendMessage` under the shared side, which US-039 part 1 never does.
   - **Process exit.** Documented (`ExitProcess`): the other threads end "without receiving a DLL_THREAD_DETACH
     notification". Measured: no `DLL_THREAD_DETACH` for any thread, only `DLL_PROCESS_DETACH` on the thread that
     exits the process (S4a-c, S11), which `DllMain` ignores. So nothing of this story runs at process exit: the
     exclusive acquire is never attempted there, and a guard left held by a thread the exit killed cannot make
     the exit wait. Nothing is lost, because no other thread survives to post. `FreeLibrary` is the same
     (documented; S5a), and `DllMain` belongs to the image, so there is nothing to unregister.
   - **Not covered:**
     - `TerminateThread` on the toolkit thread. It sends no notification (documented, "DllMain entry point"; S7),
       so the publication stays;
     - a shared holder that never releases the guard, for example after `TerminateThread` inside the shared
       section. The arm then waits for ever while it holds the loader lock. Documented (`ExitThread`): "Only one
       thread in a process can be in a DLL initialization or detach routine at a time", and "ExitProcess does
       not return until no threads are in their DLL initialization or detach routines". So every later thread
       start, thread exit and `ExitProcess` hangs (not measured); the process-exit paragraph covers only holders
       that the exit itself killed. Today such a process leaks the publication; with this story it can hang;
     - a toolkit that a second `gwin_app_create` replaced without a teardown. Records still pending for it are
       not run down when its thread ends, because the compare fails. Their callers wait until the replacing
       toolkit's rundown, which detaches the whole list.
   - **The comment at `GlassApplication.cpp:245-249`** ("do not re-add a DllMain") is rewritten by the PR that
     implements this, and `DllMain` goes where it stands: its reason, "nothing here consumes thread attach/detach
     notifications", stops being true. Its remark on cost holds. Documented: `DisableThreadLibraryCalls` "does
     not perform any optimizations if static Thread Local Storage (TLS) is enabled". Measured: the probe DLLs
     have static TLS, and their thread notifications kept arriving after that call returned TRUE (S12). An
     earlier build of this fork's glass.dll has a TLS directory, with no callbacks (`dumpbin /TLS`; the HEAD
     build was not checked). So glass.dll is already notified for every thread, and the new arm adds one compare.
   - **Considered and rejected:**
     - the fiber-local-storage (FLS) callback of this story's previous revision: documented to run "on fiber
       deletion, thread exit, and when an FLS index is freed" (`PFLS_CALLBACK_FUNCTION`), and measured to run at
       `DeleteFiber` with thread and window alive (S3a, S3b), at process exit (S4a-c), and, after a `FreeLibrary`,
       as a crash at the next thread exit (S5a; KB 2754614). `ConvertFiberToThread` did not run it (S2a, S2b)
       and is not documented to: the review's finding holds through `DeleteFiber`;
     - a guard inside that callback: none is documented, and what told the causes apart in the probe,
       `FlsGetValue` against the argument (S1, S3, S4, S5b), is an observation, no contract;
     - a raw image TLS callback (`.CRT$XL*`): the same as `DllMain` wherever both ran (S11), but its thread, its
       lock and its pre-existing threads are documented only for `DllMain`, and "PE Format" omits the idiom;
     - a C++ `thread_local` destructor: it cannot see the Reason, and it also runs at process exit and at
       `FreeLibrary`, under the loader lock (S4, S5);
     - a registered wait on a duplicated thread handle: its callback runs on a pool thread after the window is
       gone (S6), so the publication is stale in between, and an open handle is not documented to reserve the
       thread id ("Thread Handles and Identifiers");
     - destroying the window in `gwin_run_loop` when its pump ends with the instance alive: the notification
       covers that path too, and the path needs foreign code.
   - The `GlassApplication` object and what it owns are not torn down on either path. They leak as they do today,
     on a thread that is ending.
   - **Not measured.** The measurements are from the probe, a DLL built with glass.dll's flags; the acceptance
     tests repeat the decisive scenarios on glass.dll. Not run:
     - glass.dll itself; a JVM and its exit paths, `System.exit` on the toolkit thread and `DestroyJavaVM` among
       them; other Windows versions and architectures than 10.0.19045.6466 x64; thread-id reuse;
     - a thread that ends inside a COM apartment. The probe thread had none. The toolkit thread ends inside the
       STA that `RoActivationSupport.cpp:104` entered, and on the `WM_QUIT` path after the `OleUninitialize` of
       `gwin_run_loop` (`glass_win_api.cpp:552`, `OleUtils.h:160-163`). A hypothesis from review, not verified:
       combase's own thread detach runs before glass.dll's and dispatches a queued sent message to the toolkit
       window, which is still valid then. The queued `gwin_invoke_and_wait` test records it.
2. Unregister the classes on the toolkit thread, in `gwin_terminate_loop`.
   - `~BaseWnd` records its atom in a static list that only the toolkit thread touches, and posts as today. The
     posted action unregisters the class and removes the atom from the list.
   - `gwin_terminate_loop` reads the toolkit window's atom and zeroes `m_wndClassAtom` before `DestroyWindow`. When
     no toolkit-window message is on the stack, the object is deleted inside that call (`BaseWnd.cpp:166-168`), so
     reading it afterwards would be a use after free. A normal exit runs inside a toolkit-window message and
     defers the delete (`PlatformImpl.java:570`, `QuantumToolkit.java:864-881`), but the order must hold for both.
     With the atom zeroed, `~BaseWnd` posts nothing for it (`BaseWnd.cpp:51`).
   - After `DestroyWindow` returns, `gwin_terminate_loop` unregisters that atom and every atom left in the list.
     Those windows are dead by then. No new thread and no lock.
   - Not covered, and not checked whether either occurs: a Glass window destroyed after `gwin_terminate_loop` has
     returned, and a window closed inside one of its own messages with `gwin_terminate_loop` on that stack, whose
     `~BaseWnd` records the atom after the drain. Their atoms stay in the list.

## Acceptance criteria
- **Part 1**, in child JVMs, with US-039 part 1's shim-only hooks (the `PostMessage` and `SendNotifyMessage`
  counters with their stale count, and the gate that parks a poster) and five more: one reads the published HWND
  and thread id; one captures the calling thread's handle (`DuplicateHandle`, run on the thread before it ends)
  and waits on a captured handle; one posts `WM_QUIT` to the thread whose id is published (`PostThreadMessage`);
  one is a gate between the two guarded `WM_CREATE` stores; one runs the fiber sequence below. A Java thread's
  `join` returns before its native thread has ended (from memory, not checked), so every check that follows a
  thread's end first waits on that handle, within a timeout. The three post-end checks are: `gwin_invoke_later`
  and `gwin_invoke_and_wait` from another thread answer `GWIN_ERR_NO_TOOLKIT`, the counters do not move, and the
  published thread id reads 0.
  - a thread creates the toolkit as the embedded mode does, with `gwin_app_create` and no `gwin_run_loop`, and ends
    without `gwin_terminate_loop`. The three post-end checks hold. A mutant whose `DllMain` does not act on
    `DLL_THREAD_DETACH` fails this;
  - a `gwin_invoke_and_wait` queued before that thread ends returns within a timeout, answers
    `GWIN_ERR_NO_TOOLKIT`, and its runnable did not run. The same mutant fails this by timing out. A mutant that
    sets the event without marking the record as not run fails the answer, and one that runs `Do()` in `DllMain`
    fails the last check. One exception is recorded, not asserted away: the test logs whether the runnable ran
    (the COM-apartment item under "Not measured"). If it did, the PR stops and this story is updated first;
  - the `WM_QUIT` hook makes `gwin_run_loop` return, and once that thread has ended the three post-end checks
    hold. The first mutant fails this;
  - a thread whose toolkit ended through `gwin_terminate_loop` ends while a second thread's toolkit is published.
    The published HWND and thread id stay, and `gwin_invoke_later` still answers `GWIN_OK`. A mutant with both
    compares removed, the lock-free one in `DllMain` and the one under the guard, fails this;
  - a publication against a thread end. Thread A's toolkit was never torn down. Thread B creates a toolkit and
    parks at the `WM_CREATE` gate: inside the exclusive guard, its HWND stored, its thread id not yet. A ends,
    and its handle stays unsignaled while B is parked. After the release, B's HWND and thread id are the
    published ones and `gwin_invoke_later` answers `GWIN_OK`. A mutant whose stores are not under the guard
    fails this, because A clears the HWND under B. So does a mutant that drops the compare under the guard.
    While B is parked, A sits in `DllMain` under the loader lock: B, the releasing thread and every downcall
    handle they need exist before A starts to end, and the release starts no thread and loads no library;
  - by review, with no test: that the pending list is detached inside the exclusive section, and the HWND
    compare of the `WM_NCDESTROY` arm, whose trigger is a foreign `DestroyWindow`. A gate inside `DllMain`
    would pin more, but it would park a thread under the loader lock while another thread's `gwin_app_create`
    loads a library and starts WinRT activation (`RoActivationSupport.cpp:87`, `:104`);
  - fiber deletion. Java must not run on a fiber stack, so the hook runs the sequence on a native thread it
    creates: the thread converts itself to a fiber, creates a second fiber and switches to it; that fiber calls
    `gwin_app_create` and switches back; the first fiber calls `DeleteFiber` on the second, then
    `ConvertFiberToThread`. After each of those two calls the published HWND and thread id are unchanged and
    `gwin_invoke_later` answers `GWIN_OK`, as the probe's `DllMain` saw nothing at either call (S3a, S2a). When
    the thread then ends, the published thread id reads 0. A mutant that takes its notification from an FLS
    callback armed at `WM_CREATE` fails the check after `DeleteFiber` (S3a). Read: `gwin_app_create` makes no
    upcall on the calling thread (`glass_win_api.h:647`, `glass_win_api.cpp:534-548`,
    `GlassApplication.cpp:48-55` and `:79-82`, `BaseWnd.cpp:80-125`, `PlatformSupport.cpp:65-124`,
    `RoActivationSupport.cpp:65-112`); the arms and WinRT sinks that call Java need a message or an event, and
    the hook's thread retrieves no message. Not checked: WinRT activation on a fiber stack (`RoInitialize`), and
    that `CreateWindowEx` sends nothing that reaches such an arm. The PR records whether an upcall ran on the
    hook's thread; if one did, the sequence runs with the callback tables cleared, which the library tolerates
    (`glass_win_api.cpp:518-528`, `PlatformSupport.cpp:142-143`, `GlassApplication.cpp:103-104`);
  - thread end under contention. The toolkit thread ends while the gate holds a poster parked in the shared
    section. Its handle is not signaled within a bounded wait while the poster is parked, and is signaled once
    the poster is released. The poster's post is queued to the still-valid window or refused, never sent to a
    dead one: the stale count is 0. A mutant that clears without the exclusive guard fails both checks. During
    that wait the ending thread holds the loader lock: the thread that releases the poster and every downcall
    handle it needs exist before the toolkit thread starts to end, and nothing in that window starts a thread
    (no `assertTimeoutPreemptively`) or loads a library;
  - process exit. No test pins "nothing runs at process exit": on the probe a blocked exclusive acquire in a
    callback at process exit ended the process at once with its normal exit code (S4e; measured for the FLS
    callback only, not documented), so a mutant that also acts on `DLL_PROCESS_DETACH` cannot be expected to
    time out. The property holds by construction, through the Reason test, and by review. One smoke check, which
    no mutant proves: a child JVM that calls `System.exit` on the toolkit thread, with a poster parked in the
    shared section, exits within the timeout with the requested status;
  - thread-id reuse cannot be forced through a documented API, so the test pins the cleared id and the PR says so;
  - the PR records what these tests show on glass.dll for the probe's scenarios S1, S2, S3, S4 and S9, with
    every difference from the probe, and the `dumpbin /TLS` output of the glass.dll it built.
- **Part 2:** a shim-only hook answers whether a class name recorded by `BaseWnd` is still registered
  (`GetClassInfoEx`). A run opens one window, closes it and calls `gwin_terminate_loop` with no pump in between, as
  `Application.terminate` does. Afterwards neither that window's class nor the toolkit window's class is
  registered. A mutant that leaves the unregistering to the posted action alone fails both checks.
- US-039 part 1's teardown tests, `WinApplicationNativeTest` and `WinApplicationStartupTest` are unchanged and
  pass.
- No export that `WinGlassNative` binds is added or removed, and no prototype, layout or enum value changes, so
  `GLASS_WIN_ABI_VERSION` stays under the header's rule (`glass_win_api.h:199-201`).

## Definition of Done
Merged and verified on Windows. `backlog/README.md` is updated. For each part, the PR checks whether upstream
openjdk/jfx shares the defect, and drafts an upstream issue where it does.
