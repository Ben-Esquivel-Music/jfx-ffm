# US-071 — Linux gate in WSL (AC 6)

Host: WSL on Windows 10 Pro x64 (10.0.19045), Temurin JDK 27+35 (`openjdk version "27" 2026-09-15`), Apache Maven
3.10.0, `libgtk-3-0t64` 3.24.52-0ubuntu1, `libglib2.0-0t64` 2.88.0-1ubuntu0.1. Both runs used the same JDK, Maven
and libraries.

| Run | Tree | Time (EDT) |
| --- | --- | --- |
| Before | `master` at `14d7e2a18d` | 2026-10-28, 12:11 to 12:34 |
| After | `14d7e2a18d` plus the US-071 change (30 changed paths: the 17 files, four peers, the shim, four tests, and the comments of three C files and `linux.cmake`) | 2026-10-28, 15:35 to 15:54 |

Each run starts from a fresh clone of the repository at `14d7e2a18d`. For the after run the change is applied as
`git diff 14d7e2a18d --binary` plus the new files. Maven uses a private local repository, so `~/.m2` is not touched.
`DISPLAY`, `WAYLAND_DISPLAY` and `DBUS_SESSION_BUS_ADDRESS` are unset. The two steps, from the clone's root:

```
mvn -B -ntp -Dmaven.test.failure.ignore=true install -pl modules/javafx.graphics -am
bash .github/scripts/run-gtk-display-tests.sh
```

The install step is the module run of AC 6's first bullet. It also builds the natives the second step needs. The
second step is the CI step of the Linux jobs, run unchanged: it runs the 36 `test.com.sun.glass.ui.gtk` classes on
a private, rootless Xvfb, first with no window manager and then under openbox, with Maven inside a
`dbus-run-session` started without `DISPLAY`. This is the WSL setup US-015 and US-016 used; WSLg's `:0` is never
used. Counts per class come from the Maven logs. After each run, a `find` for `hs_err_pid*.log` newer than the start
of the run printed nothing, and no Xvfb or openbox process was left running.

## Module run with `DISPLAY` unset (run / failures / errors / skipped)

| Module | Before | After |
| --- | --- | --- |
| `javafx-jslc` | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 |
| `javafx-base` | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 |
| `javafx-graphics` | 26,005 / 0 / 0 / 606 | 26,005 / 0 / 0 / 606 |

Both builds succeeded. The 36 GTK classes ran 43 tests in each, with no display. The sorted per-class lists of the
two runs (911 test classes) are identical: `diff` prints nothing, so before == after. The list is
[US-071-linux-headless-per-class.tsv](US-071-linux-headless-per-class.tsv).

## GTK display suite (run / failures / errors / skipped)

The script exited 0 for each tree. The per-class lists of the after tree are identical to those of the before tree,
with no window manager and under openbox; they are
[US-071-linux-display-per-class.tsv](US-071-linux-display-per-class.tsv).

| Class | No window manager, before | No window manager, after | Openbox, before | Openbox, after |
| --- | --- | --- | --- | --- |
| `GtkApplicationQueriesNativeTest` | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 |
| `GtkCallbackTableTest` | 17 / 0 / 0 / 0 | 17 / 0 / 0 / 0 | 17 / 0 / 0 / 0 | 17 / 0 / 0 / 0 |
| `GtkClipboardNativeTest` | 13 / 0 / 0 / 0 | 13 / 0 / 0 / 0 | 13 / 0 / 0 / 0 | 13 / 0 / 0 / 0 |
| `GtkCommonDialogsNativeTest` | 9 / 0 / 0 / 0 | 9 / 0 / 0 / 0 | 9 / 0 / 0 / 0 | 9 / 0 / 0 / 0 |
| `GtkConstantsHeaderTest` | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 |
| `GtkDnDReentrantDropTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `GtkDnDTargetTest` | 8 / 0 / 0 / 0 | 8 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `GtkDnDTraceTest` | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `GtkEventTraceTest` | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 1 | 4 / 0 / 0 / 1 |
| `GtkFrameExtentsNativeTest` | 14 / 0 / 0 / 0 | 14 / 0 / 0 / 0 | 14 / 0 / 0 / 0 | 14 / 0 / 0 / 0 |
| `GtkGlassNativeBindingTest` | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 |
| `GtkGlassNativeHeadlessTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `GtkGlassNativeLoadFailureTest` | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 |
| `GtkInvokeLaterNativeTest` | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 |
| `GtkLibraryQueryHeadlessTest` | 8 / 0 / 0 / 0 | 8 / 0 / 0 / 0 | 8 / 0 / 0 / 0 | 8 / 0 / 0 / 0 |
| `GtkLibraryQueryTest` | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 |
| `GtkMotionFloodBenchmarkTest` | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 |
| `GtkNullSlotsTest` | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `GtkPeerRegistryTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `GtkPixelsCursorNativeTest` | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 |
| `GtkPreferencesNativeTest` | 10 / 0 / 0 / 0 | 10 / 0 / 0 / 0 | 10 / 0 / 0 / 0 | 10 / 0 / 0 / 0 |
| `GtkRobotNativeTest` | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 2 | 7 / 0 / 0 / 2 |
| `GtkRobotParityTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `GtkScreenNativeTest` | 10 / 0 / 0 / 0 | 10 / 0 / 0 / 0 | 10 / 0 / 0 / 0 | 10 / 0 / 0 / 0 |
| `GtkScreencastDebugTest` | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 |
| `GtkScreencastHeadlessTest` | 8 / 0 / 0 / 0 | 8 / 0 / 0 / 0 | 8 / 0 / 0 / 0 | 8 / 0 / 0 / 0 |
| `GtkScreencastNativeTest` | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 |
| `GtkTimerNativeTest` | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 |
| `GtkTraceRulesTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `GtkUndecoratedStageBoundsTest` | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 | 2 / 0 / 0 / 0 |
| `GtkUpcallExceptionTest` | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `GtkUploadBenchmarkTest` | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| `GtkUseCurrentLibraryTest` | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 |
| `GtkViewLifetimeTest` | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 |
| `GtkWindowNativeTest` | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 |
| `GtkWindowStateTraceTest` | 6 / 0 / 0 / 0 | 6 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| **Total** | **227 / 0 / 0 / 0** | **227 / 0 / 0 / 0** | **200 / 0 / 0 / 3** | **200 / 0 / 0 / 3** |

An earlier after run, on the tree before the implementation review's fixes (2026-10-28, 14:41 to 15:11), gave the
same three lists. After the final run the source changed only by one javadoc sentence of `GtkClipboardReadCalls`
(comments only: the file without its comments is identical), besides these records.

## CI

The `linux_x64_build` and `linux_aarch64_build` jobs of `.github/workflows/submit.yml`, which run the same display
step after their install step, run after the owner pushes; their runs and results are added here and in the story
then.
