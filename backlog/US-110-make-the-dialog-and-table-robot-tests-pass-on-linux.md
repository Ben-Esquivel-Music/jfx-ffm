# US-110 — Make the dialog and table robot tests pass on Linux

**Status:** ✅ Done (2026-11-12, uncommitted, PR pending; filed 2026-11-10 from the WSL robot run of master) ·
**Priority:** Low (test failures on a platform CI does not run the robot suite on; whether the product misbehaves is
not known) · **Estimate:** M · **Found:** 2026-11-10, a full `tests/system` run
(`-DFULL_TEST=true -DUSE_ROBOT=true`) of master `4799088609` in WSL (Ubuntu 26.04, JDK 27, rootless Xvfb
1920x1080x24 with openbox, Prism SW); 1 of 1 runs

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

## Done (2026-11-12, uncommitted)

### What changed
- **`glass_window.cpp`** (Glass GTK, +13 / −3), two product fixes:
  - F1, `WindowContextTop::process_configure`: the size to come back to (`geometry.final_width/height`) is taken
    from a configure only after the window's first `GDK_MAP` (`&& mapped`). That is the rule of JDK-8232811 (upstream
    `1952606a7b`, `map_received`), which JDK-8260528 (`0c03a41165`) dropped. Fixes `DialogWithOwnerSizingTest`.
  - F2, `WindowContextTop::update_frame_extents`: the `set_bounds` that follows an extents change passes the window
    width and height only for an axis the application sized as a window (`BOUNDSTYPE_WINDOW`), -1 otherwise. A
    content-sized window (`sizeToScene`) keeps its client size and the frame takes the change; a window-sized one
    keeps its window size, as before. Fixes `DialogRepeatedShowHideTest`.
- **`TableViewResizeColumnToFitContentTest`, `TreeTableViewResizeColumnToFitContentTest`**: the double click on the
  column border is `Util.runAndWait(() -> robot.mouseMove(...))` and `Util.doubleClick(robot)` (two clicks 50 ms
  apart) instead of press, release, press, release in one `runLater`, with a comment citing JDK-8322019. Both
  assertions are unchanged.
- **`GtkWindowPlacementTest`** (javafx.graphics GTK display test): the comment above the position-only assertion of
  `aCentredWindowDisabledAtItsShowStaysWhereXMovedItWhenItsExtentsChange` said the client takes the change in size,
  which F2 made untrue. Comment only.

### Criterion 1: upstream
Searched 2026-11-12: openjdk/jfx `master` at `c6413874cc` (2026-11-10) and JBS. The four test files are
byte-identical to upstream `master`, and no upstream commit after the fork's last sync (`ca9b07aeae`, 2026-08-26)
touched them.
- `DialogRepeatedShowHideTest`: JDK-8375347 "Intermittent failure in DialogRepeatedShowHideTest on Linux" is open
  (jfx25), but its failure is the dialog-shown timeout, seen mostly on Wayland; JDK-8385956 (`b1eccb6ed0`, jfx27)
  added the `assumeTrue(!Util.isOnWayland())`. JDK-8306373 (Windows, fractional scale) was closed as Cannot
  Reproduce. Nothing upstream records the 304-vs-300 failure on X11. The test was written for JDK-8193502 (jfx13).
- `DialogWithOwnerSizingTest`: no JBS issue names it. It was written for JDK-8232811 "Dialog's preferred size no
  longer accommodates multi-line strings" (`1952606a7b`, jfx14, Linux): an owned dialog sized for one line, which is
  this failure's symptom.
- `TableViewResizeColumnToFitContentTest`: JDK-8322019 "Intermittent failure in
  TableViewResizeColumnToFitContentTest on Linux" is open (jfx22, labels testbug and javafx-ci-failure), with the
  same assertion at line 90 and no root cause.
- `TreeTableViewResizeColumnToFitContentTest`: JDK-8393549 "[TestBug] Tree/TableViewResizeColumnToFitContentTest
  fails on linux" (2026-10-05, Ubuntu 24.04, line 91) was closed as a duplicate of JDK-8322019.
- Upstream's open GTK sizing rework, JDK-8354943 (openjdk/jfx#2139), touches none of the four tests.

### Criterion 2: where each failure comes from
WSL x64 (Ubuntu 26.04, GTK 3.24.52, Openbox 3.6.1), a rootless Xvfb 1920x1080x24 with openbox or with no window
manager, and a client kept connected for the whole run so that the server never resets. Each entry is one Maven run
of the four classes with the story's `-Dtest`, natives built from source, 2026-11-12. master `9b02d6a602` ran on
Temurin 27+35 and Maven 3.10.0, the JNI tree `033187ad90` on OpenJDK 25.0.4.1 and Maven 3.9.12. For the code under
test `033187ad90` is upstream's `ca9b07aeae` (the two differ only in `MenuBarSkin`), so a failure there is
upstream's.

| Test | master, openbox 1/2/3 | master, no WM 1/2/3 | `033187ad90`, openbox 1/2/3 |
| --- | --- | --- | --- |
| `DialogRepeatedShowHideTest` | F F F (304.0 vs 300.0) | P P P | F F F (304.0 vs 300.0) |
| `DialogWithOwnerSizingTest` | F F F (79.0 vs 175.0) | P P P | P P P |
| `TableViewResizeColumnToFitContentTest` | F P P | P P P | P P P |
| `TreeTableViewResizeColumnToFitContentTest` | F P P | P P P | F F F |

Nothing fails without a window manager. The table pair is a race, so runs of one class at a time in a fresh JVM on
the same display, without Maven, show its rates better than three Maven runs:
`TreeTableViewResizeColumnToFitContentTest` failed 3 of 3 Maven runs at `033187ad90`, but passed 7 of 8 such runs
there.

| Double click under openbox, runs passed of 8 | master, TableView | master, TreeTableView | `033187ad90`, TableView | `033187ad90`, TreeTableView |
| --- | --- | --- | --- | --- |
| as the tests did it: press, release, press, release, no gap | 2 | 1 | 4 | 7 |
| two clicks 50 ms apart (`Util.doubleClick`) | 8 | 8 | 8 | 8 |

**Table pair: a wrong test assumption, inherited from upstream (JDK-8322019).**
- The click point is right, so the story's first hypothesis is wrong. The stage is `UNDECORATED`, so the scene is
  at 0, 0 of the window (`_NET_FRAME_EXTENTS` 0, 0, 0, 0), and the robot lands at 1034, 339, inside the
  column-border drag rectangle (1033 to 1037, 328 to 354).
- In every failing run JavaFX gets one press, one release and one click (`clickCount` 1) on that rectangle; the
  second press and release never arrive.
- Openbox's default click-to-focus binding (a left press in a client: Focus, Raise) is a synchronous passive button
  grab on every client window: the first press freezes the pointer until openbox has run its actions and replayed it,
  and a press the robot sends before that is lost to the client. With 50 or 200 ms between the clicks inside the same
  `runLater` (the FX thread blocked, so only openbox can run) TableView passed 8 of 8, and under an openbox without
  that binding it passed 8 of 8 with the clicks as the test sent them.

**`DialogWithOwnerSizingTest`: a fork regression from `f8c745cf2c` (US-065).**
- The owned dialog is shown at 360x79 (its preferred size before CSS) and resized to 360x175 right after its show
  (the `sizeToScene` of `Dialog.show`). The unowned dialog, application-modal by default, is shown next and disables
  it.
- Openbox then sends the owned window a synthetic configure of the size it was mapped with (360x79, before its map),
  and `process_configure` takes 79 as the size to come back to, on both trees.
- `f8c745cf2c` made `process_events` (`GlassApplication.cpp`) give a disabled window's map and property events to
  glass. So the owned dialog's `_NET_FRAME_EXTENTS` now reach `update_frame_extents`, which asks for 360x79 again
  and sets the non-resizable window's minimum and maximum size hints to it: the pending 360x175 is lost for good. At
  `033187ad90` that property event was dropped for a disabled window, and the window manager granted 360x175.
- Proof in a scratch clone of master: as is, 0 of 5 runs passed; without the property-event branch `f8c745cf2c`
  added, 5 of 5; with F1, 5 of 5. US-001a's full `tests/system` run (before `f8c745cf2c`) passed the test; this
  story's run of `4799088609` (after it) failed it.

**`DialogRepeatedShowHideTest`: inherited from upstream, exposed by openbox.**
- The dialog is 300x304 already after its first show; the test only asserts after the second.
- Openbox answers `_NET_REQUEST_FRAME_EXTENTS` with 1, 1, 20, 5: the frame of a resizable window, with its 4-pixel
  resize handle. `update_frame_extents` passed `set_bounds` both the window size (302x325) and the content size, and
  `set_bounds` takes the window size, so the content-sized dialog became window-sized. When openbox manages the
  fixed-size window it drops the handle (1, 1, 20, 1), and glass kept the 325-pixel window and made the client 304
  pixels tall.
- `033187ad90` does the same: its `update_frame_extents` also passes both sizes.

The story's other hypotheses: frame extents do explain both dialog failures, in two different ways; the table pair
is upstream's; nothing fails without a window manager.

### Criterion 3: fix or document
**Table pair, fixed in the test.** The double click is two clicks 50 ms apart, within GTK's 400 ms multi-click time,
so a click-to-focus grab can no longer swallow the second click; what the tests check is unchanged. A clone of
master `9b02d6a602` with only the two test files changed, 2026-11-12:

| Test | Maven, openbox 1/2/3 | Maven, no WM 1/2/3 | 8 runs, openbox | Mutant, openbox |
| --- | --- | --- | --- | --- |
| `TableViewResizeColumnToFitContentTest` | P P P | P P P | 8 of 8 passed | 4 of 4 failed |
| `TreeTableViewResizeColumnToFitContentTest` | P P P | P P P | 8 of 8 passed | 4 of 4 failed |

The mutant makes a double click on the border resize nothing (`NestedTableColumnHeader.rectMousePressed`:
`getClickCount() == 2` changed to `== 3`). Both fixed tests failed it with "resizeColumnToFitContent failed" in one
Maven run and in three single-class runs.

**`DialogWithOwnerSizingTest`, fixed in the product (F1).** A configure before the first map is the window
manager's echo of the size the window was mapped with, not a size to come back to.

**`DialogRepeatedShowHideTest`, fixed in the product (F2)** rather than skipped by an assumption, so that it keeps
running under openbox: a content size keeps its type across an extents change.

The two fixes, in a clone of master `9b02d6a602` with this change, 2026-11-12:

| Variant, Maven runs | `DialogRepeatedShowHideTest` | `DialogWithOwnerSizingTest` |
| --- | --- | --- |
| F1 and F2, openbox 1/2/3 | P P P | P P P |
| F1 and F2, no WM 1/2/3 | P P P | P P P |
| F1 alone (F2 reverted), openbox 1/2/3 | F F F (304.0 vs 300.0) | P P P |
| F2 alone (F1 reverted), openbox 1/2/3 | P P P | F F F (79.0 vs 175.0) |

- GTK display tests (`bash .github/scripts/run-gtk-display-tests.sh -am`), master and this change on the same
  display, the same day: without a window manager 40 classes, 274 run, 0 failures, 0 errors, 4 skipped on both;
  under openbox 40 classes, 247 run, 0 failures, 0 errors, 27 skipped (5 classes run no test) on both. Every method
  has the same status on both, in both passes.
- The exports of `libglassgtk3.so` are unchanged (`nm -D`: 4,455 defined symbols, 64 `ggtk_*`, the same lists).
- The `GtkWindowPlacementTest` scenario behind the reworded comment shows F2 at work without a window manager: after
  its extents change, the content-sized window's client is 434x312 in a 436x337 window on master, and stays 360x240
  in a 362x265 window with this change.
- No GTK display test pins F1 or F2: the display gate is the same with and without them. Only
  `DialogWithOwnerSizingTest` and `DialogRepeatedShowHideTest` do, and they run only in a `FULL_TEST` + `USE_ROBOT`
  run on Linux under a window manager such as openbox. CI does not run `tests/system`.

### Criterion 4: no new failures
Two full `tests/system` runs (`-DFULL_TEST=true -DUSE_ROBOT=true`) with the driver under "Verification", on
2026-11-12: before, master `9b02d6a602` (10:30 to 11:19); after, the same commit with this change, the uncommitted
tree applied to the fresh clone as one patch (sha256 `77b9e4648ee49b49f5a8da063f0449ca9732d67db05159563e1646efa362b325`,
13:34 to 14:25). Both: WSL x64, Temurin 27+35, Maven 3.10.0, natives built from source, Xvfb `:98` with openbox,
which was up with unchanged root window properties from start to end; no `hs_err` file.

| Run | Tests run | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| before | 1,092 | 18 | 11 | 164 |
| after | 1,092 | 14 | 11 | 164 |

Compared per test case (all 1,092), exactly four changed, each from a failure to a pass:
`DialogRepeatedShowHideTest.dialogSizeOnReShownTest`, `DialogWithOwnerSizingTest.dialogWithOwnerSizingTest`,
`TableViewResizeColumnToFitContentTest.resizeColumnToFitContentTest` and
`TreeTableViewResizeColumnToFitContentTest.resizeColumnToFitContentTest`. The 25 failures and errors left are the
WebKit set of both runs, which fail for want of a Linux `jfxwebkit`: `NullCCLTest` (2),
`SceneGraphHTMLEditorTest`, `LocalStorageAccessTest`, `MainThreadTest`, `JFXPanelNPETest`, `CSSRoundingTest`,
`CanvasTest`, `ColorChooserTest`, `HTMLEditorTest` (5), `SVGTest`, `StraightLineTest`, `TextureMapperTest`,
`WebIObserverTest`, `WebPageTest`, `ModuleLauncherTest.testModuleJSCallback*` (5) and `JSCallbackMemoryTest`.
The after run's `-am` build test-compiled `javafx.graphics`, so the reworded `GtkWindowPlacementTest` compiles.

### Acceptance criteria
1. ✅ Upstream: the table pair is JDK-8322019 (open; JDK-8393549 its duplicate); `DialogRepeatedShowHideTest` has
   JDK-8375347 (open, a different, Wayland-heavy failure) and its Wayland skip JDK-8385956, but nothing on 304 vs
   300; `DialogWithOwnerSizingTest` has no issue of its own and was written for JDK-8232811.
2. ✅ Three runs of each case. The table pair is a wrong test assumption inherited from upstream,
   `DialogWithOwnerSizingTest` a fork regression from `f8c745cf2c`, and `DialogRepeatedShowHideTest` inherited from
   upstream and exposed by openbox; nothing fails without a window manager.
3. ✅ The table pair is fixed in the test (mutant-proved). Both dialog failures are fixed in the product (F1, F2), each
   proved by reverting it alone; the GTK display gate is unchanged per method.
4. ✅ The full robot run under openbox has no failure outside the WebKit set; exactly the four story tests changed,
   from failing to passing.
