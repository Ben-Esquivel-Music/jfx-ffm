# US-053 — Fix two Glass Windows window-procedure hazards: a late `RemoveProp`, and unvalidated action pointers

**Status:** 📋 Ready (filed 2026-10-01 from an independent review of the US-039 part 1 redesign; read:
`BaseWnd.cpp:30-190`, `GlassApplication.cpp:44-92` and `:178-194`, `glass_win_api.cpp:596-631`,
`glass_win_api.h:716-731`, `git blame` of `BaseWnd.cpp:163-170` and `GlassApplication.cpp:69-78`, and three Microsoft
pages as served on 2026-10-01, "About Window Properties", `SetProp` and `SendMessage`; not read: the procedures of
`GlassWindow`, `FullScreenWindow` and `ViewContainer`; not checked: every point marked so below; nothing built or
run) · **Found:** 2026-10-01, review of US-039 part 1 ·
**Blocked by:** US-039 part 1 for part 2 (it reuses that part's guarded post, list lock and test hook); part 1 is
independent · **Blocks:** none. It should precede US-031 slice 3, which otherwise reproduces both hazards in the
first Rust window class and its toolkit `WndProc`

## Story
As a JavaFX app developer on Windows,
I want Glass to remove its window property while the handle is still its own, and to run only the actions it
queued itself,
so that closing a window cannot strip the property of another window, and a stray `WM_USER` message to the
toolkit window cannot make the JVM call through a foreign pointer.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/native-glass/win`.
1. **`RemoveProp` runs on a dead HWND value when a window is destroyed inside one of its own messages.**
   - `BaseWnd::StaticWindowProc` removes the property and deletes the object only when the outermost message on the
     window returns (`BaseWnd.cpp:166-168`, `:176-190`).
   - When `DestroyWindow` runs inside a message of the same window, `WM_NCDESTROY` returns first and the system
     frees the window. The outer message then returns and calls `::RemoveProp(hWnd, ...)` with a handle value that
     is dead or, because HWND values are recycled (`glass_win_api.h:643-644`), names another window.
   - If that window carries a `BaseWndProp` property, as another Glass window of this JVM does, it loses
     it: `FromHandle` then answers NULL, its messages go to `DefWindowProc`, and its object is never deleted
     (`BaseWnd.cpp:75-78`, `:161-172`).
   - Documented on the `SetProp` page: "Before a window is destroyed (that is, before it returns from processing
     the WM_NCDESTROY message), an application must remove all entries it has added to the property list."
   - Not checked: whether the outer frames use the dead `m_hWnd` in other calls after the nested `WM_NCDESTROY`.
   - Upstream shares this. The `RemoveProp` and `delete` pair predates the repository's history (`git blame` ends
     at `51be56ef5b`, 2013-02-21), and the deferral to the outermost message came with JDK-8322215 (commit
     `c1c52e5a42`, 2024-01-22).
2. **The toolkit window dereferences the `WPARAM` of any `WM_DO_ACTION` or `WM_DO_ACTION_LATER` it receives.**
   - Both arms cast `WPARAM` to `Action*` and call `Do()`, and the second deletes it (`GlassApplication.cpp:69-78`).
     Nothing checks that Glass sent the message.
   - The toolkit window is a top-level window, not a message-only one (`GlassApplication.cpp:54`, parent NULL). The
     numbers are `WM_USER+1` and `WM_USER+2` (`GlassApplication.h:68-69`), a range every window class defines for
     itself.
   - Documented on the `SendMessage` page: a send to `HWND_BROADCAST` reaches "all top-level windows in the system,
     including disabled or invisible unowned windows", and a process may send to processes "of lesser or equal
     integrity level". So one process of the same user that broadcasts a message with one of these numbers makes
     the JVM call through a pointer it never created.
   - US-039 part 1's third private message does not have this hazard: its arm looks the `WPARAM` up in the pending
     list and ignores a value it does not find.
   - Upstream shares this: the arms predate the repository's history (`git blame` ends at `51be56ef5b`,
     2013-02-21).

## Proposed fix
1. Remove the property when `WM_NCDESTROY` returns, while the handle is still the window's own. Keep the `delete`
   where it is, at the outermost message. No frame needs the property after that: each holds `pThis` already
   (`BaseWnd.cpp:153-165`).
2. Run only actions that Glass queued, as US-039 part 1's third message already does.
   - `WM_DO_ACTION` is sent only by the toolkit thread's own `ExecAction` once US-039 part 1 has landed. `ExecAction`
     notes the action in a variable that only the toolkit thread touches, saved and restored around the
     `SendMessage` so that nested calls work. The arm runs the action only if `WPARAM` equals it.
   - `WM_DO_ACTION_LATER`: the guarded post of US-039 part 1 links the action into a list of posted actions, under
     that part's list lock and inside the shared section, and unlinks it if `PostMessage` fails. The arm unlinks
     the action and runs it only if it finds it.
   - The teardown rundown deletes the actions still listed, which are those posted but never dispatched. Today they
     leak. The Java ids the header records as leaked with them (`glass_win_api.h:728-729`) still leak. The rundown
     detaches the list under the lock and deletes outside it: `delete` on an `Action` is a virtual call, and the
     list lock is never held across a call out of the library.
   - Each action is deleted once. If US-039 part 1's fallback for messages left in the queue is ever needed, it
     deletes only the actions it unlinks from this list.
   - No new lock: the list uses the lock of US-039 part 1's pending list.

## Acceptance criteria
- **Part 1:** a shim-only hook counts the `RemoveProp` calls that `StaticWindowProc` makes in a frame other than the
  window's `WM_NCDESTROY` frame. A window is closed from inside one of its own messages (a callback slot that calls
  `gwin_window_close`), and the count stays 0. A mutant that removes the property at the deferred `delete` fails
  this.
- **Part 2:** US-039 part 1's hook that sends a private message with a chosen `WPARAM` also sends `WM_DO_ACTION` and
  `WM_DO_ACTION_LATER`. In a child JVM, each of them sent with a `WPARAM` that Glass never queued is ignored. A
  mutant whose arm dereferences the value crashes that JVM.
- After `gwin_terminate_loop`, the counters of US-039 part 1 show that every action allocated was freed, including
  one posted and never dispatched. A mutant without the delete in the rundown fails this.
- US-039 part 1's tests, `WinApplicationNativeTest`, `WinWindowNativeTest` and `WinViewNativeTest` are unchanged
  and pass.
- No export that `WinGlassNative` binds is added or removed, and no prototype, layout or enum value changes, so
  `GLASS_WIN_ABI_VERSION` stays under the header's rule (`glass_win_api.h:199-201`).

## Definition of Done
Merged and verified on Windows. `backlog/README.md` is updated. The PR drafts an upstream issue for each part,
after checking upstream's current `BaseWnd.cpp` and `GlassApplication.cpp`.
