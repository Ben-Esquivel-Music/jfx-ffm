# US-108 — Make the JFXPanel scaled test pass on Windows

**Status:** ✅ Done (2026-11-14, uncommitted, PR pending; filed 2026-11-08 from the US-068 robot run) ·
**Priority:** Low (pre-existing: master `8daf41d78a` fails the same way; seen only in this robot test so far) ·
**Estimate:** S ·
**Found:** 2026-11-08, US-068's `-DFULL_TEST=true -DUSE_ROBOT=true` run of the `tests/system` robot suite on the
US-068 tree (Windows 10 Pro x64 10.0.19045, Temurin 27+35, Maven 3.10.0)

## Story
As the maintainer reading a Windows robot run,
I want `JFXPanelScaledTest.testScale` to check the JFXPanel back buffer at 125 % on every run, passing when the
buffer has the scaled size and no skew and failing when it does not,
so that the class guards the JFXPanel HiDPI buffer instead of being a known error.

## Problem
`tests/system/src/test/java/test/robot/javafx/embed/swing/JFXPanelScaledTest.java` (Oracle header, 2020, 2024) runs
on Windows only (line 66). `setupOnce` sets a 125 % scale (`sun.java2d.uiScale`, `glass.win.uiScale`, lines 68-71),
shows a `JFrame` holding a `JFXPanel`, and waits for a 500 ms Swing `Timer` that resizes the panel to 201 × 201, then
to 101 × 101, then counts the latch down (lines 122-143). `testScale` reads the Swing-side back buffer on the JUnit
thread (`JFXPanelShim.getPixelsIm`, line 91) and fails at its first use, line 92, so the size check (127 =
`ceil(101 × 1.25)`) and the skew check never run:

```
java.lang.NullPointerException: Cannot invoke "java.awt.image.BufferedImage.getWidth()" because "<local1>" is null
    at test.robot.javafx.embed.swing.JFXPanelScaledTest.testScale(JFXPanelScaledTest.java:92)
```

Runs on the US-068 host, 2026-11-08, times EST, all with `-DskipNative=true` on natives already built:

| Time | Tree and glass.dll | Run | `testScale` |
| --- | --- | --- | --- |
| 19:19-20:00 | US-068 tree, release glass.dll | the robot suite without monocle and web: 91 classes, 491 tests, 0 failures, 1 error (this one), 42 skipped | NPE at :92, 0.031 s |
| 20:01-20:06 | US-068 tree, release glass.dll | the class alone | NPE at :92, 0.033 s |
| 20:07-20:16 | a clean checkout of master `8daf41d78a`, its glass.dll (with the test hooks) | the class alone | NPE at :92, 0.029 s |

The runs of the class alone used, from the tree's root:

```
mvn -B -ntp -pl tests/system -am test -DskipNative=true -DFULL_TEST=true -DUSE_ROBOT=true \
    -Dsurefire.failIfNoSpecifiedTests=false -Dtest='test/robot/javafx/embed/swing/JFXPanelScaledTest'
```

The error, its line and its time are the same on master and on the US-068 tree (3 of 3 runs), so it is not a US-068
regression. The host's display scaling was not recorded; the test sets 125 % itself. CI never runs this test:
`tests/system` skips its tests unless `-DFULL_TEST=true` (`jfx.systemtests.skip`, `tests/system/pom.xml`), no
workflow passes it, and CI's only `-DUSE_ROBOT=true` run is the GTK display tests of `javafx.graphics`.

**Likely cause (a hypothesis, not verified).** The shim returns `JFXPanel.pixelsIm` (`test_getPixelsIm`,
`JFXPanel.java:1062-1064`). `createResizePixelBuffer` leaves it null while `fx_scenePeer` is null or the panel has no
size (:791-794), and `paintComponent` returns before creating it while `fx_scenePeer` or `fx_stage` is null
(:866-874). The test reads it off the EDT without waiting for a paint (the latch counts down 500 ms after the last
resize), so either the embedded scene was not attached in those 1.5 s or no paint created the buffer. Which one, and
why it fails every time on this host, is not known.

## Acceptance criteria
1. **Upstream first.** Before any change, search openjdk/jfx (its `master` and JBS) for a fix, a skip or an open
   bug for this test, and record what was found here, with the IDs.
2. **Cause recorded.** Find out why `pixelsIm` is still null when `testScale` reads it on Windows, whether the cause
   is in the test or in JavaFX, and whether an upstream OpenJFX build fails the same way on the same host. Record all
   three here, with the evidence.
3. **Passes reliably.** Afterwards `testScale` passes on Windows in 20 consecutive runs of the class alone and in a
   run of the robot suite.
4. **A wrong buffer still fails.** The size and skew checks are not removed, skipped or weakened: a scratch mutant
   that sizes the back buffer with `Math.round` instead of `Math.ceil` (the case the 101 base size exists for, line
   59) fails the test.
5. **No new failures.** A product change also leaves the `javafx.swing` module tests and the other robot classes of
   `tests/system` with no new failure against a run before it.

## Verification
On Windows, from the repository root (add `-DskipNative=true` when the natives are already built):
```
# The class (criterion 3: 20 runs)
mvn -B -ntp -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='test/robot/javafx/embed/swing/JFXPanelScaledTest'
```
Before the change it fails at line 92, as in the table above.

## Out of scope
- Running the robot suite in CI.
- The other `javafx.embed.swing` robot classes, which passed or skipped in the same run.

## Definition of Done
- Criteria 1 and 2 are recorded here, and criteria 3 to 5 are met, with the before and after results stated in this
  story.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-068; its gate (`tests/system` on the release glass.dll) records this error as pre-existing.
- Line numbers are of `8daf41d78a`; US-068 changes neither the test, `JFXPanelShim` nor `JFXPanel`.

## Done (2026-11-14, uncommitted)

### What changed
`JFXPanelScaledTest` only (+21 / −6); no product code. The test now waits for the embedded scene before it measures
the buffer, as upstream's fix for the same error in the sibling class did (JDK-8324239, `876d5356bd`,
`JFXPanelHiDPITest`):
- **`MyApp`** attaches the scene with `Util.runAndWait(() -> initFX(jfxPanel))` instead of `Platform.runLater`, so
  the embedded scene exists before the 500 ms `Timer` starts resizing the panel. Every resize then goes through
  `createResizePixelBuffer` with a scene, and the buffer is always resized from 201 to 101, the case the class guards
  (JDK-8220484).
- **`setupOnce`** builds `MyApp` with `SwingUtilities.invokeAndWait` instead of `invokeLater`, so if the scene cannot
  be attached, `setupOnce` fails with the cause instead of timing out on the latch.
- **`testScale`** reads the back buffer on the EDT (`SwingUtilities.invokeAndWait`), where `JFXPanel` creates and
  paints it, copies it there (`copyData`) and checks the copy on the test thread. An `assertNotNull` with a message
  replaces the NPE. The two size assertions, the skew loop and the 101 base size are unchanged.
- The unused `Platform` import is removed, and the header's second year is now 2026.

### Criterion 1: upstream
Searched 2026-11-13: openjdk/jfx `master` at `d0e72f9d86` (2026-11-13) and JBS (anonymous REST search).
- `JFXPanelScaledTest.java`, `JFXPanel.java` and `JFXPanelShim.java` are byte-identical to upstream `master` (blobs
  `0ae541f845`, `ac4c3801a9`, `559591e733`). The test's upstream history is `3cc29e38c5` (JDK-8220484, 2020, the
  slanted-image fix it guards), `c08a6b7458` (JDK-8328745) and `21601f8ff1` (JDK-8339510, JUnit 5); nothing since
  2024-09-24. No skip, no `@Disabled`.
- JBS: `text ~ "JFXPanelScaledTest"` and `text ~ "JFXPanelScaled*"` found 0 issues. javafx and `text ~ "pixelsIm"`
  found 5, one of them this error in the sibling class: **JDK-8324239** "JFXPanelHiDPITest fails on Windows 11"
  (Resolved, Fixed, jfx23). Its error is `NullPointerException: Cannot invoke "java.awt.image.BufferedImage.getWidth()"
  because "pixelsIm" is null`, and `876d5356bd` (openjdk/jfx#1344, 2024-01-29) fixed it in the test:
  `invokeLater` → `invokeAndWait`, and `Platform.runLater` → `Util.runAndWait` for the scene. The fork has that fix.
  It was never applied to `JFXPanelScaledTest`, which has the same pattern.
- Open, and not this failure: JDK-8395891 (jfx28, a `BufferOverflowException` in `JFXPanel` with a screen scale above 1
  and `sun.java2d.uiScale.enabled=false`, a regression of JDK-8373688) and JDK-8372322 (make `JFXPanel` thread-safe).

### Criterion 2: why the buffer is null
**The cause is a race in the test, and upstream's build has it too. `JFXPanel` behaves as designed.**
- `pixelsIm` is made only by `createResizePixelBuffer` (on a resize) and by `paintComponent`, and both leave it null
  while `fx_scenePeer` is null. The scene peer is set on the FX thread inside `setScene` (`EmbeddedWindow.show` →
  `EmbeddedScene.setStage` → `HostContainer.setEmbeddedScene`). The test set the scene with `Platform.runLater`
  and resized on a fixed schedule: 201 at +500 ms, 101 at +1000 ms, latch at +1500 ms. If the scene peer arrived
  after the resize to 101, only a paint could make the buffer. That paint waits for the scene's first frame, which
  came after the latch, so the test read null.
- Evidence: a harness in the scratchpad replays `setupOnce` and `testScale` outside JUnit and logs the `JFXPanel`
  fields by reflection every 5 ms. In all 18 runs, one quantity decided the outcome: when the scene peer arrived
  relative to the resize to 101. Every pass had the scene peer before that resize, and every null had it after
  (+17 to +147 ms).

| Build, CPU load | Runs | `initFX` start to scene peer | Scene peer vs resize to 101 | Result |
| --- | --- | --- | --- | --- |
| upstream OpenJFX 27, idle | 4 | 397-550 ms | 454-608 ms before | 4 passed |
| fork, idle | 4 | 768-938 ms | 66-235 ms before | 4 passed |
| upstream OpenJFX 27, 8 busy loops | 3 | 598-664 ms | 4.7-5.4 s before (EDT delayed) | 3 passed |
| fork, 8 busy loops | 4 | 1,020-1,150 ms | 17-147 ms after | 4 null |
| upstream OpenJFX 27, 16 busy loops | 3 | 986-1,065 ms | run 3: 47 ms after | 2 passed, 1 null |

  Upstream is the `javafx-base`, `-graphics` and `-swing` 27 `-win` jars from Maven Central (sha1 checked), reached
  by reflection (`test_getPixelsIm` is in upstream 27's `JFXPanel`). A busy loop is `perl -e '1 while 1'`. The host
  has 4 cores and 8 threads.
- The fork attaches the scene about twice as slowly as upstream (about 900 ms against 500 ms), so it has 66-235 ms to
  spare before the resize to 101, against about 500 ms upstream. Thread dumps of a slow fork run show the renderer
  thread creating FFM downcall handles (`DowncallLinker`) during toolkit startup, and the EDT initializing
  `DragSource` (its cursor images) for `SwingDnD` right after the attach. They show class loading and
  initialization, and no deadlock.
- The real test fails the same way under Maven. On the unchanged tree (`d803860b7e`), the story's command passed with
  the machine idle (2026-11-13, 22:25-22:31). With 8 busy loops during the test JVM (22:40-22:46) it failed with the
  story's error, `NullPointerException ... at JFXPanelScaledTest.testScale(JFXPanelScaledTest.java:92)`. Why the
  runs of 2026-11-08 failed 3 of 3 is not known: their load was not recorded, and that tree (`8daf41d78a`, before
  `9b02d6a602` rewrote `NativeLibLoader`) was not timed.
- Host display scaling: 100 % (one monitor, 1920 × 1080, `AppliedDPI` 96, AWT default transform 1.0 with no
  `uiScale` properties). The 125 % comes only from the test's own properties.

### Criteria 3 and 4: results
All runs: Windows 10 Pro 19045, Temurin 27+35, Maven 3.10.0, `-DskipNative=true` on natives built from
`d803860b7e`. Unless stated, each run is the story's command from the repository root.

| Tree | Run | `testScale` |
| --- | --- | --- |
| unchanged | class alone, idle | passed (class 2.709 s) |
| unchanged | class alone, 8 busy loops during the test | NPE at :92 (class 6.804 s) |
| fixed | class alone, 8 busy loops during the test | passed (class 13.34 s) |
| fixed | class alone, 20 consecutive runs, 2026-11-14 00:23-02:44 | 20 of 20 passed (class 4.06-4.81 s) |
| fixed | robot suite (the testing skill's command), 2026-11-14 02:47-03:26 | passed; suite 91 classes, 491 tests, 0 failures, 0 errors, 42 skipped |
| fixed + mutant | class alone | failed: `expected: <127> but was: <126>` at :97, the width assertion |

- **The 20 runs** came from one loop, which held a lock, never started a run while a JVM other than its own was alive,
  and sampled the machine's JVMs every 10 s. Every run had mvn exit 0, 1 test, 0 failures, 0 errors and 0 skipped, no
  new `hs_err`, and a shim `JFXPanel` sizing with `Math.ceil`. The owner's build of another project overlapped run 2
  (it started 12 s after run 2 did). It shares no files with this tree, only the CPU, and run 2 passed. Runs 1 and 3
  to 20 shared the machine with no other JVM.
- **Invalidated runs.** An earlier series is not counted. A stopped loop kept running and a second loop started
  alongside it, so for one run each, two Maven builds wrote the same `target/` directories (2026-11-14 00:06-00:17).
  Both of those builds ended with mvn exit 1 and no test report. The cause was the test harness overlapping itself,
  not the test. The stray loop's other completed runs passed (7 of 7), and its ninth was stopped mid-build; none of
  them are counted.
- **The robot suite** (no monocle, no WebKit) ran with the same gate and sampling, and no other JVM was alive at any
  point. Against US-068's run (91 classes, 491 tests, 0 failures, 1 error, 42 skipped) exactly one class differs, class
  by class: `JFXPanelScaledTest` went from 1 error to passing. No new `hs_err`.
- **The mutant** sizes the back buffer with `Math.round` in `createResizePixelBuffer` (`newPixelW`, `newPixelH`). The
  fixed test failed it on the size check, not by a timeout or an NPE. The test output also shows the
  `BufferOverflowException` that `EmbeddedScene.getPixels` throws on the EDT when it writes into the 126 × 126 buffer.
  The mutant was then reverted, and the tree's `git diff` has the same hash before the mutant and after (`501c665d…`).

Criterion 5 does not apply: no product code changed.
