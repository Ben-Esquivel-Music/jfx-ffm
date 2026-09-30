# US-016 — Run the GTK display tests in CI on a virtual display

**Status:** 📋 Ready (the gap read from `submit.yml` and from the Linux job logs of Actions run 36332738634; the private-Xvfb runs proven in WSL; the flag it passes depends on US-015) · **Found:** 2026-09-27, US-008 (F3: the GTK display-test failures depended on the window manager, and CI had never run those tests)

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
