# US-110 — Make the dialog and table robot tests pass on Linux

**Status:** 📋 Ready (filed 2026-11-10 from the WSL robot run of master) · **Priority:** Low (test failures on a
platform CI does not run the robot suite on; whether the product misbehaves is not known) · **Estimate:** M ·
**Found:** 2026-11-10, a full `tests/system` run (`-DFULL_TEST=true -DUSE_ROBOT=true`) of master `4799088609` in
WSL (Ubuntu 26.04, JDK 27, rootless Xvfb 1920x1080x24 with openbox, Prism SW); 1 of 1 runs

## Story
As a maintainer of the Linux (GTK) port,
I want the Linux robot run to fail only for reasons we know about,
so that a new failure in it shows a real regression instead of being lost among old ones.

## Problem
Four robot tests that do not need WebKit fail on Linux. The other 25 failures and errors of the same run are all
WebKit tests, failing because the Maven build has no Linux jfxwebkit.

| Test | Failure |
|---|---|
| `test.robot.javafx.scene.dialog.DialogRepeatedShowHideTest.dialogSizeOnReShownTest` | line 73: the dialog pane height is 304.0 after the second show, expected 300.0 (tolerance 0) |
| `test.robot.javafx.scene.dialog.DialogWithOwnerSizingTest.dialogWithOwnerSizingTest` | line 69: the owned dialog's pane height is 79.0, the reference dialog's is 175.0 (tolerance 2.0); the width check on line 68 passed |
| `test.robot.javafx.scene.tableview.TableViewResizeColumnToFitContentTest.resizeColumnToFitContentTest` | line 90: "resizeColumnToFitContent failed", column 1's width did not change after the double click |
| `test.robot.javafx.scene.treetableview.TreeTableViewResizeColumnToFitContentTest.resizeColumnToFitContentTest` | line 91: same |

Nothing has been investigated yet. Three hypotheses, none verified:
- The table tests compute the click point from `scene.getWindow().getX()/getY()` plus the table's layout position,
  leaving out the scene's offset inside the window. Under a window manager that decorates the window, that offset
  is the frame and title bar, so the double click may land above the column header.
- The two dialog tests may depend on when the window manager applies the frame extents, which Glass GTK subtracts
  from the window size (see US-017 and US-065).
- Some of the four may also fail upstream on GTK, or fail without a window manager.

The robot baseline from 2026-09-16 (commit `033187ad90`, JDK 25, openbox) had about three failures that were not
WebKit, but its list of tests was not kept. So it is not known whether any of the four is newer than that.

## Acceptance criteria
1. **Upstream first.** Search openjdk/jfx (its `master` and JBS) for each of the four tests on Linux, and record
   what was found here, with the IDs.
2. **Where each failure comes from.** Run the four classes in the same WSL setup, three times each:
   - on master, with openbox and with no window manager;
   - on the JNI tree at `033187ad90` (or an upstream openjdk/jfx build), with openbox.

   Record the results here. Each test is then classed as a fork regression, inherited from upstream, or an
   environment artefact. Give the evidence for each class.
3. **Fix or document.** A fork regression is fixed in the product, with the test as its proof. A wrong test
   assumption is fixed in the test, keeping what the test checks. A failure that is upstream's or comes from the
   environment gets an assumption naming the reason (and the JBS ID, if there is one), not a silent skip.
4. **No new failures.** A full `FULL_TEST` + `USE_ROBOT` run with openbox ends with no failures outside the WebKit
   set. The per-test before and after results are recorded here.

## Verification
In WSL, run `dbus-run-session -- ~/robot/robotrun.sh <branch>`. It does a fresh clone, starts a private Xvfb `:98`
with openbox, and runs `mvn -pl tests/system -am test -DskipTests=true -DFULL_TEST=true -DUSE_ROBOT=true`. Logs go
to `~/robot/logs`.

To run only the four classes, add
`-Dtest='DialogRepeatedShowHideTest,DialogWithOwnerSizingTest,*ResizeColumnToFitContentTest' -Dsurefire.failIfNoSpecifiedTests=false`.
Never run the robot suite on WSLg's `:0`.

## Out of scope
- The 25 WebKit failures (no Linux jfxwebkit in the Maven build).
- The Monocle robot tests (`UNSTABLE_TEST`), which this run did not include.
- Putting `tests/system` into CI.

## Definition of Done
- Criteria 1 to 4 are met, with the results stated in this story.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-108 (the Windows robot failure in the JFXPanel scaled test), US-017 and US-065 (GTK frame extents and
  window bounds).
- Run totals: 1,092 tests, 18 failures, 11 errors, 164 skipped, no `hs_err` logs, 50 minutes including the native
  build; openbox and the root window properties were unchanged from start to end.
- Line numbers are of `4799088609`.
