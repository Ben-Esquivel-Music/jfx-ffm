# US-108 — Make the JFXPanel scaled test pass on Windows

**Status:** 📋 Ready (filed 2026-11-08 from the US-068 robot run) · **Priority:** Low (pre-existing: master
`8daf41d78a` fails the same way; seen only in this robot test so far) · **Estimate:** S ·
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
