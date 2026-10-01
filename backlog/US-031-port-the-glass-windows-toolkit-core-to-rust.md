# US-031 — Port the Glass Windows toolkit core (loop, WndProcs, IME, key tables) to Rust behind the glass_win_api ABI

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read `glass_win_api.h:1-262,573-757,807-870,
1182-1216`, `BaseWnd.cpp:140-219`, `GlassApplication.cpp:70-96`, `glass_win_api.cpp:596-622` and class declarations by
`grep`; the bodies of `ViewContainer.cpp`, `GlassWindow.cpp`, `FullScreenWindow.cpp` and `GlassInputTextInfo.cpp` were
NOT read; the robot tests' platform gating was not checked; the `gwin_robot_capture` fallback was added in the PR #21
review from `glass_win_api.h:252-295`, `glass_win_api.cpp:302-335,470-491` and `WinGlassNativeTest.java:99,643-713`
(`CaptureScreen` past `:335` not read); nothing built) · **Epic:** Rust port of the remaining
native code (goal 3) · **Blocked by:** US-027, US-030 (the COM servers the WndProcs hand
out move first), US-039 part 1, US-039 part 4 (C++ fixes), US-049 part 1 (the ruling on robot capture, and the
move to Java if the ruling is yes; if it is no, this story ports `gwin_robot_capture` as a 66th export)

## Story
As a platform maintainer,
I want the Glass toolkit core on Windows to be Rust exporting the same 65 symbols (66 if the ruling on US-049 part 1
keeps robot capture native): the message loop and toolkit window, `BaseWnd` and its window classes, the window and
view WndProcs with their input translation (keys, mouse, IMM32, touch), fullscreen, the screen and menu upcalls, and
the key tables,
so that per-HWND object lifetime, the toolkit window's cross-thread state and the exception paths the ABI header
records as undefined behaviour live in checked code, and glass.dll's remaining C++ shrinks to the common dialogs.

## Why Rust fits here
- **Must stay native (R1):** the per-function triage of the FFM migration left only OS-CALL or native-state entry
  points here and sent the WRAPPERs to Java (`glass_win_api.h:78-83,92-97,121-124`).
- **Owned code (R2):** OpenJFX code.
- **Buildable and testable here (R3):** Windows 10 + VS2022; 131 module `@Test` methods, 11 in `tests/system`, and
  the local robot suite.
- **Benefit (R4):**
  - **Cross-thread state.** `GlassApplication::pInstance` is written on the toolkit thread
    (`GlassApplication.cpp:80,90`) and deleted after `WM_NCDESTROY` (`BaseWnd.cpp:166-168`). It is read without
    synchronisation from any thread through `GetToolkitHWND()`, which tests and then dereferences it
    (`GlassApplication.h:78`), and through `ExecAction` (`GlassApplication.cpp:180-183`). Once the C++ fix publishes
    the HWND atomically, Rust keeps it an atomic by type; a raw shared pointer would not be `Sync`.
  - **Per-HWND lifetime.** `this` lives in a window property and is deleted at a re-entrancy count of zero after
    `WM_NCDESTROY` (`BaseWnd.cpp:154-172,176-190`). `gwin_view_close` leaves `view` dangling
    (`glass_win_api.h:1095`), and the animated fullscreen exit reaches a stale peer through "a pre-existing defect
    the JNI crashed on" (`glass_win_api.h:836-837`).
  - **Exceptions.** `bad_alloc` is rethrown through `WmImeComposition` into user32 frames, recorded as undefined
    behaviour (`glass_win_api.h:851-857`). The C++ fix goes first; Rust has no unwinding path there at all.
  - `ManipulationEventSink` counts its own references (`ManipulationEvents.h:32`, `OleUtils.h:199`).
- **Binding (R5):** `windows` 0.62.2 (MIT taken). Features `Win32_UI_WindowsAndMessaging`, `Win32_UI_Input_Ime`,
  `Win32_UI_Input_KeyboardAndMouse`, `Win32_UI_Input_Touch`, `Win32_Graphics_Gdi`, `Win32_Graphics_Dwm`,
  `Win32_UI_HiDpi` and `Win32_System_Ole` are present (docs.rs, 2026-09-30).
- **Why not Java:** the COM-vtable objection does not apply to a `WNDPROC`, so this rests on three things:
  - volume: a Java WndProc turns every message into an upcall, including ones that never reach Java today, and the
    header already treats upcall cost as material on hot slots (`glass_win_api.h:830-832,995`). This is **not
    measured**;
  - the native COM servers are handed out from these WndProcs;
  - behind the unchanged ABI, each slice is A/B-tested against the C with identical Java tests.

## Current state
- Files (`wc -l`): `GlassApplication` 387, `BaseWnd` 320, `GlassWindow` 1,919, `FullScreenWindow` 634,
  `ViewContainer` 1,806, `GlassView` 408, `ManipulationEvents` 245, `GlassInputTextInfo` 475, `KeyTable` 453,
  `GlassScreen` 123, `GlassMenu` 111, `Pixels` 194, `Utils` 333, `common` 95, `GlassStringBlock.h` 80, and the export
  shims in `glass_win_api.cpp` 1,723. Total 9,306 lines.
- 65 exports: `gwin_abi_version`, application 9, key 3, menu 2, view + gesture 17, window 30, screen 3. They are 65
  of the 106 in `glass_win_api.h`: US-029 ports 5, US-030 ports 32, and the 3 file-dialog exports stay C++.
- `gwin_robot_capture` (`glass_win_api.h:294`) is the last one. If the ruling on US-049 part 1 is yes, it moves to
  Java before this story starts. If the ruling is no, it is this story's 66th export, and its 115 lines
  (`glass_win_api.cpp:302-394,470-491`, counted in the 1,723 above) join slice 2.
- 6 tables with 30 slots: `GwinAppCallbacks` 1, `GwinMenuCallbacks` 1, `GwinViewCallbacks` 10, `GwinGestureCallbacks`
  5, `GwinWindowCallbacks` 12, `GwinScreenCallbacks` 1. All run on the toolkit thread
  (`glass_win_api.h:592-602,755-757,839-848,1216`). `gwin_run_loop` defines that thread, and `gwin_invoke_later`
  may be called from any thread (`glass_win_api.h:162-166`).
- Hierarchy: `GlassWindow` and `FullScreenWindow` are `BaseWnd` + `ViewContainer` (`GlassWindow.h:37`,
  `FullScreenWindow.h:36`); `GlassApplication : protected BaseWnd` (`GlassApplication.h:71`).
- The IME is IMM32 (five `Imm*` calls), with no TSF.
- Tests (`@Test` methods; a plain `grep '@Test'` also matches `@TestMethodOrder`):
  - module: `WinApplicationNativeTest` 19, `WinViewNativeTest` 28, `WinWindowNativeTest` 32, `WinGlassNativeTest` 27,
    `WinScreenNativeTest` 7, `WinScreenParityTest` 3, `WinScreenLayoutParityTest` 5, `WinMenuNativeTest` 9,
    `WinDowncallExceptionReportingTest` 1;
  - `tests/system`: `WinApplicationStartupTest` 6, `WinScreenScaledParityTest` 2, `WinScreenStartupParityTest` 3;
  - robot, by name: `KeyLockedTest`, `ShortcutKeyboardTest`, `KeyEventClosesStageTest`, `MenuDoubleShortcutTest`.

  No test drives IME composition.

## Approach
- ABI unchanged; same crate, modules `app`, `wnd`, `window`, `view`, `input` (keys, IME, touch), `screen`, `menu`.
- During the transition, Rust window classes use their own window property. `BaseWnd::FromHandle` then answers NULL
  for a Rust-owned HWND, and no C++ ever casts a Rust object.
- The `BaseWnd` + `ViewContainer` multiple inheritance fixes the order: helpers first, then the toolkit window, then
  the input handlers behind the still-C++ WndProcs, then the window classes themselves.
- Re-entrancy is reproduced, not redesigned. No borrow of per-HWND state may be held across a call that can re-enter
  the WndProc (`SendMessage`, `DestroyWindow`, `DefWindowProc`, or a modal loop such as menus, sizing or
  `DoDragDrop`). The message-count lifetime of `BaseWnd.cpp:176-190` is kept.

### Slices
1. **Goldens from the C**:
   - recording tables for all six, over scripted scenarios:
     - `SendInput` keys: dead keys, numpad, AltGr, lock keys;
     - mouse and wheel;
     - resize, move, minimize, maximize;
     - fullscreen, including the animated exit;
     - `WM_COMMAND` and `WM_DISPLAYCHANGE`;
     - `invoke_later` from another thread;
   - the full domains of `gwin_key_java_to_windows`/`gwin_key_windows_to_java`, and `gwin_key_code_for_char` over
     the BMP for the installed layouts;
   - IMM32 composition with an installed Microsoft IME.

   Record the commit, the Windows build, the layouts and the IME.
2. **Helpers**: `KeyTable` and the three key exports, `GlassScreen`, `GlassMenu`, `Pixels` and `GlassStringBlock`,
   called by the C++ WndProcs through internal seams. Gate: key sweep exact, `WinGlassNativeTest`, `WinScreen*`,
   `WinMenuNativeTest`, and the robot key tests.
   If US-049 part 1 is ruled no, `gwin_robot_capture` is ported here too; it is a GDI sequence that no WndProc
   calls. Its gate adds a pixel golden of the C capture over a fixed scene and the four capture tests of
   `WinGlassNativeTest` (`:643-713`).
3. **Toolkit window**: `GlassApplication` and the loop/invoke exports, on a Rust window class, including the
   clipboard-viewer arms the COM story left behind a seam. Gate: `WinApplicationNativeTest`,
   `WinDowncallExceptionReportingTest`, `WinApplicationStartupTest`, invoke traces.
4. **Input handlers**: `ViewContainer`'s key, mouse, IME and touch handlers, `GlassInputTextInfo` and
   `ManipulationEventSink`, as Rust over Rust-owned per-view state and dispatched from the C++ WndProcs. Gate:
   slice-1 traces exact, `WinViewNativeTest`.
5. **Window classes**: `GlassWindow`, `FullScreenWindow`, `BackgroundWindow`, `GlassView`, `BaseWnd`, and the view
   and window exports. Gate: `WinWindowNativeTest`, `WinViewNativeTest`, the traces, and the robot suite on Windows.
6. **Delete** each slice's C++ in its own commit, and `glass_win_api.cpp` once it is empty.

## Acceptance criteria
- `dumpbin /exports glass.dll` is identical before and after, and `gwin_abi_version()` is unchanged: 105 exports and
  ABI 7 once US-049 part 1 has landed, or 106 exports and ABI 6 if it was ruled out.
- The 131 + 11 `@Test` methods above pass unchanged, and the slice-1 traces are exact on the capture machine, as is the
  slice-2 robot pixel golden if US-049 part 1 is ruled no.
  If US-049 part 1 has landed, recount `WinGlassNativeTest` first: its capture tests (`:643-713`) change with
  the export.
- The Windows robot suite (`USE_ROBOT`) gives the same result as the C baseline run on the same machine.
- The listed C++ files are deleted; glass.dll's C++ is reduced to `CommonDialogs*` and what it includes.
- Every export and every `extern "system"` entry (WndProcs, COM methods, timer/hook procs) is guarded and answers
  the C++ fallback.
- `unsafe` appears only in the boundary modules; clippy and rustfmt are clean.

## Definition of Done
Merged PR. Verified on Windows: the module suite, the `tests/system` Win tests and the robot suite, run locally.
The C++ is deleted. The WSL Linux build still configures. `backlog/README.md` is updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Re-entrancy: user32 re-enters the WndProc inside `SendMessage`, `DestroyWindow` and modal loops, and a Rust `&mut` held across one is UB. | Interior mutability with no borrow held across OS calls, and a debug assertion counting re-entry depth. |
| 2 | Slice 5 is about 5,000 lines behind one hierarchy. | Split it by class (`BackgroundWindow` → `FullScreenWindow` → `GlassWindow` + `GlassView`), each behind its own CMake option. |
| 3 | The IME golden needs an installed IME. | Install a Microsoft Japanese IME on the capture machine and record it with the golden. |
| 4 | Behaviour before `WM_CREATE`: the C++ ignores `WM_NCCREATE`/`WM_GETMINMAXINFO` (`BaseWnd.cpp:172`). | Attach at `WM_CREATE`, exactly as the C++ does. |
| 5 | Upcall order and timing in the invoke and fullscreen-animation paths. | Traces record order and thread; no new thread, timer or lock. |

