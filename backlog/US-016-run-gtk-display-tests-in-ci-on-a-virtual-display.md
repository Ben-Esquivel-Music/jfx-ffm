# US-016 — Run the GTK display tests in CI on a virtual display

**Status:** 🚧 In progress (2026-10-20, uncommitted: implemented and verified in WSL, see "Implementation"; awaiting the first CI runs, which the user pushes - not Done before them) · **Found:** 2026-09-27, US-008 (F3: the GTK display-test failures depended on the window manager, and CI had never run those tests)

## Story
As a maintainer of the GTK glass (`glass/gtk` and its FFM bindings),
I want CI to run the GTK display tests on a private virtual display, once without a window manager and once with
one,
so that every push that CI runs for checks the GTK windowing code, not only a push by someone who happens to have a
display, and a result that depends on the window manager shows up in CI instead of on a developer's desktop.

## Problem
### CI runs no GTK display test
- `.github/workflows/submit.yml` has two Linux jobs: `linux_x64_build` on `ubuntu-24.04` (`:62-162`) and
  `linux_aarch64_build` on `ubuntu-24.04-arm` (`:164-264`). Each one:
  - installs the build packages (`:91-93`, `:193-195`), which include no X server and no window manager;
  - gives ALSA a null device, downloads JDK 26.0.2 and prints its environment;
  - runs `mvn -B -ntp -fae install "-Djfx.web.skipFfmTests=false"` (`:133`, `:235`);
  - checks that `libavplugin.so` was built.
- Neither job sets `DISPLAY`, and the runner has none. The `env | sort` of the "Setup environment" step lists no
  `DISPLAY` in either Linux job of run 36332738634 (commit 6ff961e5a0, 2026-09-27).
- `GtkGlassChildJvm.requireDisplay` (`GtkGlassChildJvm.java:106-111`) therefore skips every GTK display class at
  class level. In both Linux jobs of that run, the Maven log lists 32 classes of `test.com.sun.glass.ui.gtk`:
  - 27 report `Tests run: 0`;
  - the five that need no display run 31 tests: `GtkGlassNativeHeadlessTest` 5, `GtkLibraryQueryHeadlessTest` 8,
    `GtkPeerRegistryTest` 5, `GtkScreencastHeadlessTest` 8, `GtkTraceRulesTest` 5.
- Those five check the bindings, two GLib layouts, the library query table, the peer registry, the screen capture
  codec and the trace rules. None of them opens a GTK window, dispatches an event, or goes through the screen,
  clipboard, dialog or robot code of the GTK glass, and the build step skips the system tests (`:128-129`).
  `GtkGlassNativeHeadlessTest` says why it exists: so that "every CI job, where the display tests of this package
  skip" still links the GTK glass facade (`GtkGlassNativeHeadlessTest.java:47-49`).
- `submit.yml` uploads no artifact. `build-webkit.yml` does, with `actions/upload-artifact` pinned at
  `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` (v7.0.1, `build-webkit.yml:664`).
- The Linux jobs took 6 min 44 s (x64) and 7 min 37 s (aarch64) of Maven time in that run.

### The results depend on the window manager
- In US-008 (F3), three size-hint tests of `GtkWindowNativeTest` failed under openbox and under Weston (WSLg), and
  passed without a window manager. Glass subtracts the window manager's `_NET_FRAME_EXTENTS` (openbox: 1, 1, 20, 5)
  before it writes `WM_NORMAL_HINTS`; the test had assumed no window manager. Both outcomes are needed to check such
  a test.
- The trace goldens exist only for a display without a window manager (`GtkTraceGolden.java:175-185`, `:239-241`),
  and so do the expectations that `GtkGlassChildJvm.CAPTURE_ENVIRONMENT` binds to `env.windowManager=unknown`
  (`GtkGlassChildJvm.java:71-83`; for example `GtkRobotNativeTest.java:109-110`, `:136`). Under openbox they skip.
- So one CI run cannot cover both. It takes two: one without a window manager and one under openbox.

### Measured in WSL (2026-09-27, Ubuntu 26.04, JDK 25, commit e003c1f200)
A private Xvfb 21.1.22 at 1920x1080x24 with GTK 3.24.52, and
`mvn -B -ntp -o -pl modules/javafx.graphics -am test -DskipNative=true -Dsurefire.failIfNoSpecifiedTests=false`
with `-Dtest` selecting the classes.

| display | classes | result |
| --- | --- | --- |
| Xvfb + openbox | all 32 | 160 tests, 3 failures (fixed by US-008), 7 skipped; Maven 3 min 07 s, classes 93 s |
| Xvfb, no window manager | `GtkWindowNativeTest`, `GtkUploadBenchmarkTest`, with US-008 | 6/6 and 5/5 |
| Xvfb + openbox | the same two, with US-008 | 6/6 and 5/5 |

- In the openbox run of all 32 classes, 25 classes ran tests. The six robot classes (no `-DUSE_ROBOT`) and
  `GtkWindowStateTraceTest` (window manager present) ran none.
- US-008 made no run of all 32 classes without a window manager. The first CI run gives one.
- The WSL machine of these runs has no `sudo`, so its Xvfb was unpacked from the Ubuntu packages and patched to
  find `xkbcomp`. CI needs no such workaround: the runner images already have the stock Xvfb (below), and CI already
  installs packages with `sudo apt-get` (`submit.yml:80`, `:91`), so it can install the stock `openbox` package.

### What the CI environment differs in
- `CAPTURE_ENVIRONMENT` is a 1920x1080 screen at depth 24 and 96 dpi, with GTK 3.24.52 (`GtkGlassChildJvm.java:76-83`).
  `GtkUploadBenchmarkTest`'s default frame is 1920x1080 too (`GtkUploadBenchmarkTest.java:119-120`). The Xvfb has to
  be started with that geometry and depth.
- Both Linux jobs install `libgtk-3-dev` 3.24.41-4ubuntu1.3 (logs of run 36332738634), not 3.24.52. Expectations
  gated on `env.gtk` therefore skip in CI, for example `GtkRobotNativeTest.java:125` and
  `GtkPixelsCursorNativeTest.java:235`. With `-Djfx.parity.require=true` those skips would become failures
  (`GtkGlassChildJvm.java:119-124`).
- Both runner images of that run (ubuntu24/20260920.314 and ubuntu24-arm64/20260920.129) already have Xvfb 21.1.12
  (`xvfb` 2:21.1.12-1ubuntu1.6 in the image readme), not the 21.1.22 of the capture; neither has openbox.

## Depends on US-015
- If US-015 makes the display classes opt-in (its recommendation), this step passes that flag. Without it every
  display class skips and the step proves nothing. The step then lands with or after US-015's flag.
- If US-015 keeps them on whenever `DISPLAY` is set, the step needs only `DISPLAY`.
- `GtkScreenNativeTest` changing the root properties is harmless on a private display, so this story does not wait
  for that part of US-015.

## Acceptance criteria
- **Two runs on a private Xvfb.** After the `install` step of `linux_x64_build`, a step (or a job) runs the classes
  of `test/com/sun/glass/ui/gtk` on a private Xvfb at 1920x1080x24 twice: without a window manager and under
  openbox. `DISPLAY` names the Xvfb, `WAYLAND_DISPLAY` is unset, and US-015's flag is passed if there is one.
  - A command like the one used in WSL is enough. It drops `-o` and `-am`, because the `install` step has already
    built and installed the modules. This form has not been run yet; for example:

    ```
    mvn -B -ntp -pl modules/javafx.graphics test -DskipNative=true \
        -Dtest='test/com/sun/glass/ui/gtk/*Test' -Dsurefire.failIfNoSpecifiedTests=false
    ```
  - `xvfb` is already on both runner images (checked for run 36332738634); `openbox` is installed from the Ubuntu
    archive with `sudo apt-get`. The installed versions are recorded.
- **Each run proves its setup.** Children that call `GtkGlassChild.recordEnvironment` record their environment in
  `target/gtk-glass-child/*/values.txt` (`GtkGlassChild.java:152-166`). The step fails unless at least one child
  of each run recorded it, every recorded `env.screen` is `1920x1080` and every `env.depth` is `24`, and the openbox
  run recorded `env.windowManager=Openbox` and the other run `unknown`.
  - A window manager that died silently would otherwise turn the openbox run into a second run without one.
  - `xvfb-run`'s default screen, `-screen 0 1280x1024x24` in Debian's script, would otherwise go unnoticed.
- **Green, with tests.** Both runs pass. Every display class that has tests for that setup reports a non-zero count.
  - Every skipped test is listed with the reason from its surefire `<skipped>` element, for example the
    expectations bound to GTK 3.24.52, which skip on 3.24.41.
  - Every class that reports `Tests run: 0` is listed with its reason, which surefire does not record for a
    class-level skip: the robot classes without `-DUSE_ROBOT`, and under openbox the classes that require no window
    manager (`GtkTraceGolden.requireNoWindowManager`; `GtkWindowStateTraceTest` in the WSL runs).
  - The per-class counts of the first green runs are recorded in this story.
- **Runtime.** The runtime of each run is reported in this story from the first green runs. For reference, the
  openbox run above took 3 min 07 s in WSL, of which about 93 s were spent in the GTK classes.
- **Failure artifacts.** When a run fails, CI uploads the `javafx.graphics` surefire reports and
  `modules/javafx.graphics/target/gtk-glass-child`, which holds each child's stdout, stderr, recorded values and
  fatal error logs (`GtkGlassChildJvm.java:196-216`). It uses `actions/upload-artifact` pinned by commit SHA, as
  `build-webkit.yml` does.
- **It can fail.** The failure path is checked once: with one display assertion inverted, the step fails and
  uploads the artifacts. The inversion is then reverted.
- **Headless run unchanged.** The `mvn -B -ntp -fae install` step stays as it is (`:133`, `:235`), with no
  `DISPLAY`: 27 GTK classes report `Tests run: 0` and the five headless ones 31 tests.
- **Decisions recorded in this story:**
  - whether `linux_aarch64_build` runs the display tests too (coverage of the aarch64 GTK glass against its runtime);
  - whether the CI runs pass `-DUSE_ROBOT=true`. The Xvfb is private, so the six robot classes may run there; if
    they do, record their counts too;
  - whether `-Djfx.parity.require=true` is passed, given that CI's GTK is not the captured 3.24.52.

## Notes
- `submit.yml` runs on every push to a branch other than `master`, `main`, `jfx<N>` and `WIP*`, and on manual
  dispatch (`:46-56`), so the new step runs wherever the two Linux jobs run, and not for a push to `master`.
- Related: US-015 (the flag and the side effects of these tests on a developer's display), US-008 (F3).

## Implementation (2026-10-20, uncommitted; awaiting the first CI runs)

Implemented and verified locally in WSL. **Not Done before CI**: the first CI runs, which the user pushes, are the
real test (CI has other GTK and Xvfb versions and an aarch64 job that WSL cannot run). Fill in "First CI runs"
below from their logs.

### The decisions, as taken
Taken by the manager as acting maintainer; the last three were added during the implementation, with the evidence
that forced them.
1. **Both Linux jobs run the display tests**, `linux_x64_build` and `linux_aarch64_build`. Otherwise the aarch64 FFM
   downcalls (AAPCS64) into GTK and X11 never run against a display. The cost is wall-clock time in two jobs that
   run in parallel anyway. The logic is in one script that both jobs call, not in duplicated YAML. Both runner
   images already have Xvfb; the step installs `xvfb` anyway (a no-op where it is present). Note: in run
   39477670315 the x64 runner had Maven 3.10.0 and the aarch64 runner Maven 3.9.12; the script just calls `mvn`.
2. **Both runs pass `-DUSE_ROBOT=true`.** The Xvfb is private, and the robot classes are the only ones that go
   through input dispatch. Checked for thresholds that a shared runner could miss: `GtkMotionFloodBenchmarkTest`
   and `GtkUploadBenchmarkTest` have no upper wall-clock bound. Their pass conditions are lower bounds (every round
   delivers motions or takes time; the medians are above 0). The bounds that do apply to them: a child JVM may take
   at most `GtkGlassChildJvm.CHILD_SECONDS` (180 s); `GtkUploadBenchmarkTest.VIEWABLE_MILLIS` gives the window
   manager 20 s to map the window; and `GtkMotionFloodBenchmarkTest.MAX_BYTES` (8 bytes allocated per motion, the
   idle-corrected median) is an allocation bound, not a time, but its idle correction subtracts a rate measured over
   500 ms, so a busy runner adds noise to it. In WSL the flood class took 31 s and its child well under the 180 s.
3. **`-Djfx.parity.require=true` is not passed.** CI has GTK 3.24.41 and Xvfb 21.1.12, not the captured 3.24.52 and
   21.1.22, so every expectation bound to `CAPTURE_ENVIRONMENT` skips there, and the flag would turn each skip into
   a failure.
4. **Maven runs on a session bus of its own**, from `dbus-run-session` started without `DISPLAY`. The first green
   run in WSL, with the runner's `XDG_RUNTIME_DIR` set (to a private directory with no `bus` socket in it) and no
   `DBUS_SESSION_BUS_ADDRESS`, failed 5 tests of the run without a window manager: `GtkDnDTraceTest` (3),
   `GtkUpcallExceptionTest.traceMatchesTheJniGolden` and `GtkWindowStateTraceTest.traceMatchesTheJniGolden`. Each
   trace had an extra `notifyFocus 542` and a `mouse ENTER` when its first window was shown: the pointer was already
   inside it, where the robot classes before it had left it. Cause, shown on a separate display with
   `GtkTimerNativeTest` alone: without a session bus, the first GTK child autolaunches one through X11
   (`dbus-launch`), and the `at-spi-bus-launcher` activated on it connects to the display (it sets `AT_SPI_BUS` on
   the root window). Both stay connected, so Xvfb never resets when the last child disconnects, and the pointer
   keeps its position from one child to the next. The no-window-manager goldens were captured with a reset, which
   puts the pointer back in the middle of the screen, outside every window of these scenarios. With
   `env -u DISPLAY dbus-run-session -- env DISPLAY=<private> mvn ...`, `org.a11y.Bus` is activated on the private
   bus without a display, nothing else connects to the X server, and all 194 tests pass. US-015's runs passed
   because a `wsl.exe` shell has a session bus (`DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus`) whose
   activated services do not see the private display. Whether the runner has a bus at `/run/user/1001/bus`, and
   `dbus-x11`, is unknown (its needrestart output names a systemd user manager for `runner`, so it probably has
   one), so the script makes the run independent of the image. The step's `bus socket: yes|no` line in the first CI
   run answers whether the runner has a bus. The script also fails a run if `AT_SPI_BUS` is still set on the root
   window afterwards. A private bus also keeps a developer's own session bus out of a local run.
   `dbus-run-session` is in the `dbus-daemon` package, which the runner's system bus already needs; the step
   installs it explicitly. The tests' requirement that nothing else stays connected to the display is accepted and
   documented in the run recipe of `GtkGlassChildJvm`'s javadoc, not changed in the tests.
5. **No `-dpi`, no `+extension`.** The 96 dpi of `CAPTURE_ENVIRONMENT` is GDK's resolution (`env.resolution`).
   Measured with `GtkScreenNativeTest`: it is `96.0` on an Xvfb at its default 100 dpi, at `-dpi 120` and at
   `-dpi 96`, while `xdpyinfo` follows the flag. The expectations were captured on an Xvfb with the default size,
   so the script passes no `-dpi`. `+extension GLX +extension RANDR` (US-015's driver) change nothing on Xvfb
   21.1.22: the same 23 extensions either way.
6. **One fresh Xvfb per run; openbox waited for and checked.** Each run starts its own Xvfb (`-screen 0
   1920x1080x24 -nolisten tcp`) on `:99` (`GTK_TEST_DISPLAY`) and refuses a display that already answers. openbox
   is started with `setsid`; the run starts only when the root window has both `_NET_SUPPORTING_WM_CHECK` and
   `_NET_WORKAREA` (US-015 saw a race on the first alone), and fails unless, after Maven, the openbox process is
   alive and the supporting window still says `_NET_WM_NAME = "Openbox"`.

### What changed
- `.github/scripts/run-gtk-display-tests.sh` (new; LF; invoked with `bash`, so no executable bit). Per run (`nowm`,
  then `openbox`; `GTK_TEST_RUNS` selects): start Xvfb (and openbox), then
  `mvn -B -ntp -pl modules/javafx.graphics test -DskipNative=true -Dtest='test/com/sun/glass/ui/gtk/*Test'
  -Dsurefire.failIfNoSpecifiedTests=false -DPRIVATE_DISPLAY=true -DUSE_ROBOT=true` with `DISPLAY` naming the Xvfb
  and `WAYLAND_DISPLAY` unset, on its own session bus. Then it moves the run's `target/surefire-reports` and
  `target/gtk-glass-child` to `target/gtk-display-tests/<run>/` (a copy left by an earlier build is first moved to
  `<name>.before-gtk-display-tests`, so each run's directories hold only that run), and checks:
  - **Maven's exit code**, and that Xvfb (and openbox) survived;
  - **the setup proof**: at least one `values.txt` with `env.*`; every `env.screen` is `1920x1080`, every
    `env.depth` `24`, every `env.windowManager` `unknown` (no window manager) or `Openbox`; the failing scenarios
    are named;
  - **the counts**, from the Maven log's `Tests run: ... in test.com.sun.glass.ui.gtk.<class>` lines (surefire XML
    undercounts nested classes): every `*Test.java` of the package must report a result, so a test pattern that
    stops matching a class, or a fork that dies, fails the run; a class may report `Tests run: 0` only if the run's
    list (`expected_zero_classes`) names it with its reason, and a listed class that runs tests fails too;
  - **no fatal error log** (`hs_err_pid*`, the module's and the children's);
  - **the natives**: names, sizes, times and SHA-256 of `target/native/bin/*.so` are the same after both runs as
    before, so `-DskipNative=true` kept what the install step built (4 libraries in WSL).

  It prints a report per run (`report.txt`): the runtime, the per-class counts, the `Tests run: 0` classes with
  their reasons, every skipped test with the first line of its surefire `<skipped>` element, and the `env.*` values
  the children recorded; and appends a compact version to `$GITHUB_STEP_SUMMARY`: one row per run, the installed
  versions (`dpkg-query` of xvfb, openbox, x11-utils, dbus-daemon and `libgtk-3-0*`, plus `openbox --version`),
  `env.gtk`, `env.xserver` and friends, any errors, and in two collapsed sections the per-class table and the skip
  reasons. Both runs always execute; the script exits 1 at the end if either failed, 2 if it could not start (a
  tool missing, no `libglassgtk3.so`). Machine-specific things come from the environment (`GTK_TEST_XVFB`,
  `GTK_TEST_OPENBOX`, `GTK_TEST_DISPLAY`) and extra Maven arguments from its arguments, so it ran unchanged in WSL.
- `.github/workflows/submit.yml`, in both Linux jobs, after "Build JavaFX artifacts and run headless tests" (which
  is unchanged: the diff only inserts lines):
  - "Install a virtual display and a window manager": `sudo apt-get -y install --no-install-recommends xvfb openbox
    x11-utils dbus-daemon`;
  - "Run the GTK display tests on a private Xvfb" (`id: gtk_display_tests`, `timeout-minutes: 30`,
    `working-directory: jfx`): `bash .github/scripts/run-gtk-display-tests.sh`. It runs only when the install step
    succeeded; "Verify the optional avplugin was built" still runs after it (`!cancelled()`);
  - "Upload the GTK display test reports", only when that step failed (`failure() &&
    steps.gtk_display_tests.outcome == 'failure'`): `actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a
    # v7.0.1`, the pin of `build-webkit.yml`, artifacts `gtk-display-tests-linux-x64` and
    `gtk-display-tests-linux-aarch64`, 7 days, with `target/gtk-display-tests/` (per run: report, Maven log,
    surefire reports, `gtk-glass-child`, Xvfb and openbox logs, `hs_err` files) plus `target/surefire-reports/`,
    `target/gtk-glass-child/` and the module's `hs_err_pid*.log`, which exist only if the step was stopped in the
    middle of a run. About 3.3 MB for both runs in WSL.
  - The header comment says what the Linux jobs now run. No `${{ }}` in any `run:`; `permissions` stays
    `contents: read`; the only new action is the pinned first-party upload.
- `GtkGlassChildJvm` (javadoc only): its run recipe says that nothing else may stay connected to the display, and
  that Maven runs on a private bus whose daemon has no `DISPLAY` where no session bus is reachable.
- `backlog/README.md`: the US-016 row; US-015 moved to the done table, and its story's status says it landed as
  `8c43d5ff36` (PR #34).

### Classes that report `Tests run: 0`, and why (the script's list)
Surefire records no reason for a class-level skip, so the list in `expected_zero_classes` is where it is written.

| run | classes | reason |
| --- | --- | --- |
| no window manager | none | every class runs, the six robot classes included (`-DUSE_ROBOT=true`) |
| openbox | `GtkDnDTargetTest`, `GtkDnDTraceTest`, `GtkNullSlotsTest`, `GtkUpcallExceptionTest`, `GtkWindowStateTraceTest` | `GtkTraceGolden.requireNoWindowManager`: they script window positions, stacking and focus for an X server without a window manager |

Skipped tests in WSL, with the reasons the script printed from surefire: none without a window manager; under
openbox `GtkEventTraceTest.traceMatchesTheJniGolden` ("the trace golden of gtk-event-trace is for a display without
a window manager; this one runs Openbox, ...") and `GtkRobotNativeTest.keyLockStatesOfAFreshServer` and
`.screenCaptureReadsARGBFromTheRootWindow` (`ParityGate`: "here env.windowManager=Openbox where it was captured with
unknown"). On CI more `ParityGate` skips are expected from `env.gtk=3.24.41` and the X server release.

### Measured locally (2026-10-20, WSL Ubuntu 26.04, JDK 27 Temurin 27+35, Maven 3.10.0)
**Caveat:** WSL has GTK 3.24.52 and Xvfb 21.1.22 - the capture's versions - and a rootless Xvfb unpacked into the
home directory (no `sudo`); CI has GTK 3.24.41 and Xvfb 21.1.12 from the image, and an aarch64 job. These runs
prove the script and its checks, not CI's results.

Setup: a fresh clone of `8c43d5ff36` with this change, a private Maven repository (a copy without
`org/openjfx`), then `mvn -B -ntp install -pl modules/javafx.graphics -am` with the GTK pattern and no `DISPLAY` -
CI's state before the step (natives built, artifacts installed). The script ran as in CI, with
`GTK_TEST_XVFB`/`GTK_TEST_OPENBOX` naming the rootless binaries and `-Dmaven.repo.local=<private>`, on `:99`, never
on WSLg's `:0`, in the runner's environment (`LANG=C.UTF-8`, `XDG_RUNTIME_DIR` without a bus,
`DBUS_SESSION_BUS_ADDRESS`, `DISPLAY` and `WAYLAND_DISPLAY` unset). No run left an `hs_err` file.

| run | what | result |
| --- | --- | --- |
| setup | `mvn -B -ntp install -pl modules/javafx.graphics -am`, GTK pattern, no `DISPLAY` | BUILD SUCCESS, 02:13 min; GTK: 32 classes, 27 `Tests run: 0`, 31 tests in the 5 headless classes (as in CI) |
| green 1 | the script before decision 4 (no session bus) | no window manager: 194 tests, **5 failures** (the trace goldens above), 6 min 13 s; openbox: 167 tests, 0 failures, 4 min 30 s; exit 1 |
| **green 2** | the script with decision 4 (its comments were reworded afterwards) | no window manager: 32 classes, **194 tests, 0 failures, 0 skipped**, no `Tests run: 0`, 6 min 04 s (Maven 05:56); openbox: 32 classes, **167 tests, 0 failures, 3 skipped**, 5 `Tests run: 0` (the list), 4 min 29 s (Maven 04:27); proof: 13 children `unknown`, 7 children `Openbox`, all `1920x1080` and `24`, `env.resolution` `96.0`; natives unchanged; **exit 0**, 10 min 36 s for the script |
| failure path | one display assertion inverted (`GtkApplicationQueriesNativeTest.transparentWindowsNeedACompositedScreen` asserts the opposite of its oracle: one `!`) | both runs executed and failed: `GtkApplicationQueriesNativeTest` 4 tests, 1 failure in each (5 min 44 s, 4 min 46 s); proof and counts otherwise as green 2; `::error::GTK display tests failed: nowm openbox`, exit 1; `target/gtk-display-tests/{nowm,openbox}` held the report, Maven log, 32 surefire XML files (the failure in `TEST-...GtkApplicationQueriesNativeTest.xml`), `gtk-glass-child`, the Xvfb and openbox logs: 3.3 MB. Reverted: `git diff -- modules` empty |
| proof negative: wrong geometry | Xvfb given xvfb-run's default `-screen 0 1280x1024x24`, run without a window manager only | **Maven passed** (194 tests, 0 failures, 7 skipped); the proof failed the run: `not every child recorded env.screen=1920x1080` (13 children recorded `1280x1024`); exit 1 |
| proof negative: openbox killed | openbox killed 60 s into the openbox run, which alone ran | **Maven passed** (194 tests, 0 failures, 0 skipped: a second run without a window manager); failed by the survival check, by the proof (`env.windowManager=unknown` in all 13 children) and by the count check (the 5 listed classes ran tests); exit 1 |
| headless | `DISPLAY` unset, no flags, the GTK pattern (the install step's view) | 32 classes, 27 `Tests run: 0`, 31 tests in `GtkGlassNativeHeadlessTest` 5, `GtkLibraryQueryHeadlessTest` 8, `GtkPeerRegistryTest` 5, `GtkScreencastHeadlessTest` 8, `GtkTraceRulesTest` 5; 66 s; `target/gtk-glass-child` held only the three headless scenarios |
| **final** | a fresh clone of `8c43d5ff36`, this change applied as one patch (it applied cleanly: `submit.yml` CRLF 534/534, every file byte-identical to the working tree, no line of `submit.yml` removed, the install step of both jobs parsing to the same YAML as at `8c43d5ff36`), the setup install (138 s), then the script | no window manager: 32 classes, **194 tests, 0 failures**, 5 min 48 s; openbox: 32 classes, **167 tests, 0 failures, 3 skipped**, 5 `Tests run: 0`, 4 min 30 s; natives unchanged; **exit 0**, 10 min 19 s for the script |

Cost: the two runs took 10 min 19 s to 10 min 36 s in WSL. In run 39477670315 the Linux jobs took 10:54 (x64)
and 9:44 (aarch64), and macOS x64 was the longest job at 19:09. If CI is about as fast as WSL, each Linux job
grows to about 22-25 min and becomes the longest, so a push waits about 3-6 min longer, and each push costs about
2 x 11 runner minutes more. To be replaced by the measured times below.

Per class, green 2 (tests, with failures and skips):

| class | WSL, no window manager | WSL, openbox | x64, no window manager | x64, openbox | aarch64, no window manager | aarch64, openbox |
| --- | --- | --- | --- | --- | --- | --- |
| `GtkApplicationQueriesNativeTest` | 4 | 4 | TBD | TBD | TBD | TBD |
| `GtkCallbackTableTest` | 17 | 17 | TBD | TBD | TBD | TBD |
| `GtkClipboardNativeTest` | 13 | 13 | TBD | TBD | TBD | TBD |
| `GtkCommonDialogsNativeTest` | 9 | 9 | TBD | TBD | TBD | TBD |
| `GtkDnDTargetTest` | 8 | 0 | TBD | TBD | TBD | TBD |
| `GtkDnDTraceTest` | 6 | 0 | TBD | TBD | TBD | TBD |
| `GtkEventTraceTest` | 4 | 4 (1 skipped) | TBD | TBD | TBD | TBD |
| `GtkGlassNativeBindingTest` | 3 | 3 | TBD | TBD | TBD | TBD |
| `GtkGlassNativeHeadlessTest` | 5 | 5 | TBD | TBD | TBD | TBD |
| `GtkGlassNativeLoadFailureTest` | 2 | 2 | TBD | TBD | TBD | TBD |
| `GtkInvokeLaterNativeTest` | 6 | 6 | TBD | TBD | TBD | TBD |
| `GtkLibraryQueryHeadlessTest` | 8 | 8 | TBD | TBD | TBD | TBD |
| `GtkLibraryQueryTest` | 4 | 4 | TBD | TBD | TBD | TBD |
| `GtkMotionFloodBenchmarkTest` | 2 | 2 | TBD | TBD | TBD | TBD |
| `GtkNullSlotsTest` | 4 | 0 | TBD | TBD | TBD | TBD |
| `GtkPeerRegistryTest` | 5 | 5 | TBD | TBD | TBD | TBD |
| `GtkPixelsCursorNativeTest` | 7 | 7 | TBD | TBD | TBD | TBD |
| `GtkPreferencesNativeTest` | 10 | 10 | TBD | TBD | TBD | TBD |
| `GtkRobotNativeTest` | 7 | 7 (2 skipped) | TBD | TBD | TBD | TBD |
| `GtkRobotParityTest` | 5 | 5 | TBD | TBD | TBD | TBD |
| `GtkScreenNativeTest` | 10 | 10 | TBD | TBD | TBD | TBD |
| `GtkScreencastDebugTest` | 3 | 3 | TBD | TBD | TBD | TBD |
| `GtkScreencastHeadlessTest` | 8 | 8 | TBD | TBD | TBD | TBD |
| `GtkScreencastNativeTest` | 7 | 7 | TBD | TBD | TBD | TBD |
| `GtkTimerNativeTest` | 6 | 6 | TBD | TBD | TBD | TBD |
| `GtkTraceRulesTest` | 5 | 5 | TBD | TBD | TBD | TBD |
| `GtkUpcallExceptionTest` | 3 | 0 | TBD | TBD | TBD | TBD |
| `GtkUploadBenchmarkTest` | 5 | 5 | TBD | TBD | TBD | TBD |
| `GtkUseCurrentLibraryTest` | 3 | 3 | TBD | TBD | TBD | TBD |
| `GtkViewLifetimeTest` | 3 | 3 | TBD | TBD | TBD | TBD |
| `GtkWindowNativeTest` | 6 | 6 | TBD | TBD | TBD | TBD |
| `GtkWindowStateTraceTest` | 6 | 0 | TBD | TBD | TBD | TBD |

The CI columns (TBD) are filled in from the first CI runs below.

### First CI runs (to be filled in from the CI logs)
Placeholders marked **TBD**. Source: each Linux job's step summary of "Run the GTK display tests on a private Xvfb",
or its log (`gh run view <run> --log --job <job>`, then grep
`== GTK|Setup proof|  env.|Installed:|Natives unchanged|bus socket`).

**Green run:** Actions run **TBD**, commit **TBD**.

| job | installed versions | `env.gtk` / `env.xserver` | bus socket in `XDG_RUNTIME_DIR` | no window manager: classes / tests / failures / skipped / `Tests run: 0`, time | openbox: the same, time | step time |
| --- | --- | --- | --- | --- | --- | --- |
| Linux x64 | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** |
| Linux aarch64 | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** |

- Per-class counts per job and run: the CI columns of the per-class table above (**TBD**).
- Skipped tests on CI, with their reasons (expected: `ParityGate` skips for `env.gtk=3.24.41`): **TBD**.
- Job times against run 39477670315 (x64 10:54, aarch64 9:44, macOS x64 19:09): **TBD**.

**Failure-path run:** Actions run **TBD**, on a throwaway branch with the inverted assertion of the failure-path
row above as one commit on top of the green commit; the branch is deleted afterwards.
- Expected in both Linux jobs: both runs execute, `GtkApplicationQueriesNativeTest` fails 1 of 4 in each, the step
  fails with `::error::GTK display tests failed: nowm openbox`, and "Upload the GTK display test reports" uploads
  `gtk-display-tests-linux-x64` and `gtk-display-tests-linux-aarch64`.
- Observed: **TBD** (step results, artifact names and sizes, and that the artifact holds the failing
  `TEST-test.com.sun.glass.ui.gtk.GtkApplicationQueriesNativeTest.xml`).
