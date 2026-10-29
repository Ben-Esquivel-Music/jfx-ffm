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

Two runs of `.github/workflows/submit.yml`, each green in all five jobs. The `linux_x64_build` and
`linux_aarch64_build` jobs run the same display step after their install step.

| Run | Actions run | Commit | Linux x64 job | Linux aarch64 job |
| --- | --- | --- | --- | --- |
| Before | 40718289172 | `df067e6d23`, the head of PR #43 | 122921873348 | 122921872981 |
| After | 40875501864 | `6839f9b650`, the head of PR #44 | 123453152778 | 123453152667 |

PR #43 was squash-merged as `14d7e2a18d`, and no run exists on the merge itself. Its head `df067e6d23` has the same
tree as `14d7e2a18d` (`d1fd3c825c`), the before tree of the WSL runs, so its run is the before run. Both runs used the
same environment: Temurin JDK 27+35; Maven 3.10.0 on x64 (image `ubuntu-24.04` 20261025.353.1) and 3.9.12 on aarch64
(`ubuntu-24.04-arm` 20261025.165.1); `libgtk-3-dev` and `libgtk-3-0t64` 3.24.41-4ubuntu1.3, `xvfb`
2:21.1.12-1ubuntu1.9 and openbox 3.6.1-12build5. The display step's test JVMs recorded GTK 3.24.41 and the X server
`The X.Org Foundation 12101011`. In each of the four jobs every step succeeded except "Upload the GTK display test
reports", which runs on failure only and was skipped. No log contains `hs_err_pid` or `A fatal error has been
detected`.

Counts from the Maven logs (run / failures / errors / skipped). The `test.com.sun.glass.ui.gtk` row is the 36 GTK
classes in the build step, part of `javafx-graphics`; the display rows are the display script's two passes over them.

| Tests | x64, before | x64, after | aarch64, before | aarch64, after |
| --- | --- | --- | --- | --- |
| `javafx-jslc` | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 |
| `javafx-base` | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 |
| `javafx-graphics` | 26,005 / 0 / 0 / 606 | 26,005 / 0 / 0 / 606 | 26,005 / 0 / 0 / 606 | 26,005 / 0 / 0 / 606 |
| `javafx-controls` | 9,445 / 0 / 0 / 185 | 9,445 / 0 / 0 / 185 | 9,445 / 0 / 0 / 185 | 9,445 / 0 / 0 / 185 |
| `jfx-incubator-richtext` | 186 / 0 / 0 / 0 | 186 / 0 / 0 / 0 | 186 / 0 / 0 / 0 | 186 / 0 / 0 / 0 |
| `javafx-fxml` | 123 / 0 / 0 / 3 | 123 / 0 / 0 / 3 | 123 / 0 / 0 / 3 | 123 / 0 / 0 / 3 |
| `javafx-media` | 33 / 0 / 0 / 2 | 33 / 0 / 0 / 2 | 33 / 0 / 0 / 2 | 33 / 0 / 0 / 2 |
| `javafx-web` (`ffm-binding-test`) | 194 / 0 / 0 / 0 | 194 / 0 / 0 / 0 | 194 / 0 / 0 / 0 | 194 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.gtk`, build step | 43 / 0 / 0 / 0 | 43 / 0 / 0 / 0 | 43 / 0 / 0 / 0 | 43 / 0 / 0 / 0 |
| Display, no window manager | 227 / 0 / 0 / 8 | 227 / 0 / 0 / 8 | 227 / 0 / 0 / 8 | 227 / 0 / 0 / 8 |
| Display, openbox | 200 / 0 / 0 / 5 | 200 / 0 / 0 / 5 | 200 / 0 / 0 / 5 | 200 / 0 / 0 / 5 |

`jfx-incubator-input` and `javafx-swing` print no results; `javafx-swt`, the default tests of `javafx-web` and
`javafx-system-tests` are skipped; `jdk-jsobject` has no tests. Every display pass printed `passed`, Maven exit code
0, with no class at 0 tests with no window manager and 5 classes at 0 tests under openbox.

Before and after, in both jobs, the per-class lists are identical: the build step's 1,204 test classes, the 36
headless GTK classes among them, and each display pass's 36 classes, with no window manager and under openbox. The
x64 and aarch64 jobs give the same lists as each other.

Against the WSL tables above, the totals of `javafx-jslc`, `javafx-base` and `javafx-graphics`, the 43 headless GTK
tests and the 911-class list of [US-071-linux-headless-per-class.tsv](US-071-linux-headless-per-class.tsv) are the
same in CI. CI also runs `javafx-controls`, `jfx-incubator-richtext`, `javafx-fxml`, `javafx-media` and `javafx-web`,
which the WSL install step does not build. The display passes run the same number of tests per class as in WSL, with
no failure or error, but CI skips more:

- No window manager: 8 skipped, not 0, in six classes: `GtkDnDTraceTest` 3, and `GtkEventTraceTest`,
  `GtkPixelsCursorNativeTest`, `GtkRobotNativeTest`, `GtkUpcallExceptionTest` and `GtkWindowStateTraceTest` 1 each.
- Openbox: 5 skipped, not 3: `GtkPixelsCursorNativeTest` 1, not 0, and `GtkRobotNativeTest` 3, not 2. The five
  classes that run no test under openbox are the same.

The display script prints the reason for each skip. With no window manager, the six trace tests
(`GtkDnDTraceTest`'s three and the `traceMatchesTheJniGolden` of `GtkEventTraceTest`, `GtkUpcallExceptionTest` and
`GtkWindowStateTraceTest`) report goldens captured with GTK 3.24.52 and X server 12101022, and
`GtkPixelsCursorNativeTest.attachDataStillServesTheRemainingJniWindowIcon` and
`GtkRobotNativeTest.screenCaptureLeavesTheArrayUntouchedWhenItReturnsEarly` expectations captured with GTK 3.24.52,
where the runners have GTK 3.24.41 and X server 12101011; 3.24.52 is the GTK of the WSL host above. Under openbox,
those two tests skip for the same reason, `GtkEventTraceTest.traceMatchesTheJniGolden` because its golden is for a
display with no window manager, and `GtkRobotNativeTest.keyLockStatesOfAFreshServer` and
`.screenCaptureReadsARGBFromTheRootWindow` because they were captured with no window manager. This record does not
name the three tests that WSL skipped under openbox.
