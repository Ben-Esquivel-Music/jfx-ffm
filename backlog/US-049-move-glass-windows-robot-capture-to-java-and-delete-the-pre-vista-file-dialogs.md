# US-049 — Move Glass Windows robot capture to Java and delete the pre-Vista file dialogs

**Status:** 🔶 Needs a maintainer ruling on part 1 (filed 2026-09-30 from the Rust-port survey of `native-glass/win`;
nothing built or run). Part 1 contradicts the ABI header, which labels robot capture "OS-CALL, stays native"
(`glass_win_api.h:262`). Part 2 is ready once the folder-dialog dispatch is traced. · **Epic:** Less native code
(goal 1), routed here by the Rust-port survey · **Should precede:** US-031 (part 1 only: US-031 waits for the ruling,
and for the move if the ruling is yes; if it is no, US-031 ports `gwin_robot_capture`)

## Story
As a platform maintainer,
I want the Windows robot screen capture done by Java through FFM, and the file-dialog code that cannot run on
Windows 10 or later deleted,
so that the Rust port of the Glass toolkit core (US-031) has one export and 115 lines less to port, and the C++ that
US-031 leaves in glass.dll has 384 dead lines less.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/native-glass/win`.
1. **`gwin_robot_capture` is a stateless GDI sequence plus a pixel swizzle** (`glass_win_api.h:262-280`).
   - That is the shape of code Java already binds directly for the cursor (`glass_win_api.h:40-44`).
   - A Java version can match the C exactly: the same GDI calls, then an integer swizzle.
   - The header's "stays native" label predates the survey's per-function reading.
2. **`CommonDialogs_Standard.cpp/.h` (384 lines) is dead on Windows 10 and later.**
   - `IS_WINVISTA` is `IS_WINVER_ATLEAST(6, 0)` (`Utils.h:55`), so the `GetOpenFileName` branch
     (`CommonDialogs.cpp:76-82`) never runs.
   - The folder-dialog dispatch was not traced.

## Proposed fix
1. Capture a pixel golden from `gwin_robot_capture` over a fixed on-screen scene (an undecorated stage with known
   content). Bind the same GDI sequence from Java in `WinRobot`, and compare in one run while the C export still
   exists. Then delete the export under the header's bump rules (`GLASS_WIN_ABI_VERSION` 6 → 7).
2. Confirm that the folder dialog never reaches `CommonDialogs_Standard`. Then delete the two files, their dispatch
   branch and their CMake lines.

## Acceptance criteria
- **Part 1:**
  - the Java capture equals the C capture pixel for pixel on the golden scene, at 100% and 150% scaling;
  - the robot tests that use screen capture pass on Windows;
  - `WinGlassNativeTest`'s capture tests (`:643-713`), which bind `glass!gwin_robot_capture` (`:99`), move to the Java
    capture or go with the export, and the PR states the new count;
  - `gwin_robot_capture` is gone from `dumpbin /exports`, and the ABI version is bumped.
- **Part 2:**
  - the files are deleted;
  - `WinCommonDialogsNativeTest` passes;
  - opening a file, saving a file and choosing a folder all work in a manual check recorded in the PR.

## Definition of Done
Merged and verified on Windows. `backlog/README.md` is updated. If part 1 lands first, the export total that US-029,
US-030 and US-031 cite drops from 106 to 105, and the `gwin_abi_version()` 6 they cite becomes 7.
