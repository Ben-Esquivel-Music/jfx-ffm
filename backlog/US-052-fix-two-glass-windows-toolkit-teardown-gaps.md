# US-052 — Fix two Glass Windows toolkit teardown gaps: a toolkit thread that ends undestroyed, and leaked classes

**Status:** 📋 Ready (filed 2026-10-01; found while redesigning US-039 part 1 after PR review; revised the same day
after a review of this story; read: `GlassApplication.cpp:44-92`, `:178-194` and `:245-249`,
`GlassApplication.h:78-104`, `BaseWnd.cpp:30-190`, `glass_win_api.cpp:534-584`, `glass_win_api.h:592-598` and
`:653-685`, `WinApplication.java:185-196` and `:245-304`, `Application.java:382-399`, one `git grep` for
`PostQuitMessage` and `WM_QUIT` under `modules`, and three Microsoft pages as served on 2026-10-01: "Window
Features" ("Window Destruction"), `GetCurrentThreadId` and `PFLS_CALLBACK_FUNCTION`; not read: SWT's side of the
embedded mode; not checked: every point marked so below; nothing built or run) · **Found:** 2026-10-01, redesign
of US-039 part 1 ·
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
     "Window Destruction"). That section does not say what the system does with a thread's windows when the
     thread ends. From memory, not checked: the system destroys them. Not checked: whether the window procedure
     then receives `WM_NCDESTROY`. This story assumes it may not.
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
   - Move US-039 part 1's teardown step (exclusive guard, clear the HWND and the thread id, run down the pending
     list) into one function that is safe to call twice. The `WM_NCDESTROY` arm calls it.
   - At `WM_CREATE` the toolkit thread stores a non-NULL value in a fiber-local-storage slot whose callback calls
     the same function. Documented: "If the FLS slot is in use, FlsCallback is called on fiber deletion, thread
     exit, and when an FLS index is freed" (`PFLS_CALLBACK_FUNCTION`). One callback covers both paths of the
     Problem list.
   - The callback acts only when the published thread id equals `GetCurrentThreadId()`, compared inside the
     exclusive section. The slot value is never cleared, so the callback also fires when a thread ends after its
     toolkit was torn down normally, and another thread's toolkit may be published by then. Comparing with what is
     published at that moment also covers a first toolkit that was replaced without being torn down, which
     clearing the slot at teardown would not.
   - Not verified, and recorded by the PR:
     - that the callback runs on the exiting thread before the system frees the window;
     - whether it runs under the loader lock. The function takes the guard exclusively, and a shared holder is
       inside one `PostMessage` or `SendNotifyMessage`, which is assumed not to need that lock;
     - whether it also runs at process exit. A thread that was terminated inside the shared section would then
       make the exclusive acquire wait for ever, so in that case the callback must not block.
   - No `DllMain` is added: the tree removed it on purpose (`GlassApplication.cpp:245-249`).
   - `gwin_run_loop` does not destroy the window when its pump ends with the instance alive. That was considered
     and left out: the callback covers that path too, and the path needs foreign code. The `GlassApplication`
     object and what it owns are not torn down on either path. They leak as they do today, on a thread that is
     ending.
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
- **Part 1**, with US-039 part 1's shim-only hooks (the `PostMessage` and `SendNotifyMessage` counters) and one more
  that reads the published thread id, in child JVMs:
  - a thread creates the toolkit as the embedded mode does, with `gwin_app_create` and no `gwin_run_loop`, and ends
    without `gwin_terminate_loop`. Afterwards `gwin_invoke_later` and `gwin_invoke_and_wait` from another thread
    answer `GWIN_ERR_NO_TOOLKIT`, the counters stay at zero and the published thread id reads 0. A mutant without
    the callback fails this;
  - a `gwin_invoke_and_wait` queued before that thread ends returns within a timeout. The same mutant fails this by
    timing out;
  - a `WM_QUIT` posted to the toolkit thread makes `gwin_run_loop` return. Once that thread has ended, the same
    three checks hold. The same mutant fails this;
  - a thread whose toolkit ended through `gwin_terminate_loop` ends while a second thread's toolkit is published.
    The published HWND and thread id stay, and `gwin_invoke_later` still answers `GWIN_OK`. A mutant whose callback
    clears without comparing the thread id fails this;
  - thread-id reuse cannot be forced through a documented API, so the test pins the cleared id and the PR says so;
  - the PR records what it found for the three "not verified" points.
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
