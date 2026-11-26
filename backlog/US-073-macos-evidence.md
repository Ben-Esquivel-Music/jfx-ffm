# US-073 — Evidence: the system menu bar click test on macOS 27

## Host and tree

Every run of 2026-11-26 recorded here was on the owner's Mac: macOS 27.2.1 (26B131), arm64, OpenJDK 27.0.1
(`java.version` 27.0.1.0.2), primary screen 1920x1080 at scale 1. The baselines of 2026-11-25 under "The full run"
are attributed where they appear. Times are EST. The tree is `fe5a0072df`: unchanged for the diagnostic and the
real test before the change, and with the three changed files of the story as committed in `7e136e7ae5`, before
the story's T6 gate, for the mutant, the 20 + 20 runs and the full run. Line numbers of the original test (102,
111-112, 136) are of its file before the change, which is the same at `1a6788cc4d` and `fe5a0072df`; line 115 is of
the ported test in that tree, upstream's file at `81cc4f0b50` (line 114 with the gate). The raw logs and reports
are not kept in the repository: they were in a temporary session scratch directory, and the figures here are
transcribed from them.

## The diagnostic (criterion 2)

Seven scratch test classes in `test.robot.javafx.scene.us073`, one JVM each, ran 2026-11-26 05:53-05:54 on the
unchanged tree (7 tests, 0 failures; BUILD SUCCESS in 1 min 42 s, finished 05:54:45); they are deleted. Each
rebuilt the original test's application and replayed the original calls with the original pacing, with listeners
recording every mouse event, `CONTEXT_MENU_REQUESTED`, show and hide (with their callers) and focus change.
"Click record" is the moment the robot had posted the right-click. "At-once read" is the read the original test
makes at line 102 or 136 right after `Util.runAndWait` returns.

| Variant | Startup latch | Situation | Right press reached the scene | `ContextMenu.show()` reached | `isShowing()` true | At-once read |
| --- | --- | --- | --- | --- | --- | --- |
| AloneFalseA | original | autoHide=false, first click after launch | +5.8 ms | +15.4 ms | +15.9 ms | true at +94.9 ms |
| AloneFalseB | original | the same | +3.2 ms | +12.6 ms | +13.0 ms | true at +93.8 ms |
| AloneFalseC | original | the same | +5.2 ms | +16.5 ms | +17.4 ms | true at +97.4 ms |
| AloneFalseFocusWait | wait for stage focus | the same | +5.4 ms | +15.6 ms | +16.1 ms | true at +97.3 ms |
| AloneFalseSettled | original, then 3 s | the same | +8.9 ms | +27.8 ms | +28.6 ms | true at +81.2 ms |
| AloneTrueThenFalse, 1st click | original | autoHide=true, first click | +5.6 ms | +15.1 ms | +15.6 ms | true at +96.2 ms |
| AloneTrueThenFalse, 2nd click | original | autoHide=false, after the first menu was hidden by `hide()`, no menu-bar click in between | +2.2 ms | +3.0 ms | +3.4 ms | true at +100.5 ms |
| ClassOrder, 1st click | original | body of `testSystemMenuBarConsumesClickEvent` (autoHide=true) | +5.7 ms | +15.2 ms | +15.7 ms | true at +96.6 ms |
| ClassOrder, 2nd click | original | body of `testSystemMenuBarProcessesClickEvent` up to line 136, right after the first body | never | never | never | **false at +103.4 ms** |

### The class order, in detail

Times from the class's initialisation:

- +491 ms: the menu-bar click at (140, 10) is posted while the auto-hide context menu shows and its owner holds the
  grab.
- +530 ms: the context menu hides (`ON_HIDING`, `WINDOW_HIDING`, `onAutoHide`), 38 ms after the click. The system
  menu's `showing` property never changes (no `ON_SHOWING`): the click is consumed, as JDK-8372398 intended.
- +701 ms: the reads of lines 111-112, `menu.isShowing()` false and `contextMenu.isShowing()` false (the first
  method passes).
- +808 ms: the second method's right-click is posted, with the stage focused and the cursor at the label's centre
  (960, 404). From here to the end of the run at +12,902 ms not one mouse event (and no `CONTEXT_MENU_REQUESTED`)
  reaches either scene: no `MOUSE_PRESSED`, no `CONTEXT_MENU_REQUESTED`, no `MOUSE_MOVED` or `MOUSE_ENTERED`.
- Probes: mouse moves (+4,021 ms), a right-click (+4,737 ms), another right-click (+7,660 ms), a primary click
  on the label (+9,872 ms) and a last right-click (+10,580 ms), none of which arrives; an Escape was also posted
  (+6,949 ms), but the diagnostic recorded no key events, so whether it arrived is not known.
  The at-once reads of the four right-clicks from +808 ms on are all `false` (100.0 to 103.6 ms after each click).
- FX runnables keep running throughout: every `Util.runAndWait` returns in about 100 ms, and the stage is focused
  and showing in every snapshot.

So in the class run the context menu is never shown, not shown late: after the first method's menu-bar click the
application receives no mouse events. This is the hang JDK-8393642 records: `-[GlassMenu menuWillOpen:]` calls
`[_menu cancelTrackingWithoutAnimation]` when a window holds the grab, and from macOS 27, according to JDK-8393642,
that leaves AppKit in its menu tracking loop; the diagnostic recorded no AppKit menu-tracking state. The cause is in
JavaFX (native Glass, the call JDK-8372398 added), exposed by an AppKit change in macOS 27, and not in the test. It
does not depend on `autoHide=false`: the second click of AloneTrueThenFalse shows a non-auto-hide menu; the failure
follows the menu-bar click.

### Start-up timing

The original latch is `stage.setOnShown(_ -> Platform.runLater(countDown))`. In all seven variants the stage is not
focused at `onShown`; `stage.focused` turns true 10.6 to 15.7 ms after `onShown`. In the six variants with the
original latch it counts down 9.7 to 13.7 ms after that; in AloneFalseFocusWait the latch is the focus change
itself. The original test therefore started only about 10 ms after the stage got focus, by the accident of one
`runLater`.

The story's one recorded failure of the method alone (2026-11-05 11:17, macOS 27.0.1, 0.212 s) did not reproduce on
2026-11-26 (macOS 27.2.1): 3 of 3 runs of the real test (next section) and 5 of 5 diagnostic variants of the method
alone (AloneFalseA, B, C, FocusWait and Settled) show the menu. Its cause on that day cannot be established now. A
candidate, not proven: the start-up race above, a first right-click on a stage that has no focus yet. Upstream's
test change waits for stage focus before starting, and the ported test has it.

### The non-auto-hide menu and the menu bar, before the change

In the five AloneFalse variants the system menu showed (`showing` true) 69.3, 54.7, 59.5, 70.2 and 58.6 ms after the
menu-bar click (54.7 to 70.2 ms), and the context menu stayed showing until the diagnostic's own
`contextMenu.hide()`, which reached its `ON_HIDING` 2.92 to 2.93 s after the click.

## The real test before the change

2026-11-26 05:55-05:59, tree `fe5a0072df` unchanged, after an event-posting check that answered
`CGPreflightPostEventAccess True AXIsProcessTrusted True`.

| Run | Command | Result |
| --- | --- | --- |
| method alone, 3 runs | the story's second verification command as filed (`-Dtest='SystemMenuBarClickEventsTest#testSystemMenuBarProcessesClickEvent'`) | passed 3 of 3 (1 test each; 0.536, 0.637, 0.639 s) |
| class, 1 run | the story's first verification command | `testSystemMenuBarConsumesClickEvent` passed (0.323 s); `testSystemMenuBarProcessesClickEvent` failed at line 136 in 0.22 s, "Context menu should be showing after right-click ==> expected: <true> but was: <false>" |

## The mutant (criterion 4)

2026-11-26 06:12-06:13 (finished 06:13:43, 46.5 s), on the changed tree. The scratch class
`test.robot.javafx.scene.us073.SystemMenuBarClickEventsMutantTest`, now deleted, was a copy of the ported test whose
only behavioural difference was

```
label.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, Event::consume);
```

after `label.setContextMenu(contextMenu)`, so that `Control`'s handler never shows the menu. Result: 2 tests,
2 failures. Both methods failed at the mutant's `runTest` line 117 (line 115 of the ported test) with "Context menu
should be showing after right-click ==> expected: <true> but was: <false>", in 0.216 s
(`...AndClosesAutoHidePopup`) and 0.212 s (`...AndKeepsNonAutoHidePopup`). The ported test asserts this check
unconditionally in both methods.

## The 20 + 20 runs (criterion 3)

2026-11-26 06:14-06:46, on the changed tree, 40 consecutive runs, each a separate Maven invocation:

| Runs | Command | Result | Method times |
| --- | --- | --- | --- |
| 20, method alone (finished 06:15:24 to 06:30:26) | `mvn -B -ntp -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest='SystemMenuBarClickEventsTest#testSystemMenuBarProcessesClickEventAndKeepsNonAutoHidePopup'` | 20 of 20 passed, each 1 / 0 / 0 / 0 | 0.524 to 0.538 s |
| 20, class (finished 06:31:14 to 06:46:35) | the same with `-Dtest='test/robot/javafx/scene/SystemMenuBarClickEventsTest'` | 20 of 20 passed, each 2 / 0 / 0 / 0 | `...AndClosesAutoHidePopup` (runs first) 0.527 to 0.540 s; `...AndKeepsNonAutoHidePopup` 0.514 to 0.528 s |

Counts are tests / failures / errors / skipped; no report has a failure, error or skip element. No run came near the
test's 5 s latch on the system menu's `onShown`.

## The full run (criterion 6)

One command from the repository root, 2026-11-26 06:47:02-07:28:16, on the changed tree, after an event-posting
check:

```
mvn -B -ntp -fae -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true
```

It has no `-Dtest` filter, so `-am` also ran the unit tests of every upstream module (javafx.web's are skipped by
default). BUILD SUCCESS, exit 0, 41 min 14 s. Console totals (tests / failures / errors / skipped):

| Module | After (2026-11-26) |
| --- | --- |
| javafx-jslc | 437 / 0 / 0 / 0 |
| javafx-base | 5,510 / 0 / 0 / 22 |
| javafx-graphics | 26,889 / 0 / 0 / 859 |
| javafx-controls | 9,445 / 0 / 0 / 183 |
| jfx-incubator-richtext | 186 / 0 / 0 / 0 |
| javafx-fxml | 123 / 0 / 0 / 3 |
| javafx-media | 41 / 0 / 0 / 6 |
| javafx-system-tests (`tests/system`, robot included) | 1,099 / 0 / 0 / 60 |

`tests/system` before: the owner's full run of 2026-11-25 14:56-15:54 on `c5448d8440` (the US-111 branch before its
merge), 268 classes, 1,099 / 1 / 0 / 60. Per class, from the `TEST-*.xml` reports of each run: 268 classes before and
268 after (91 of them `test.robot.*`), the same class names, and the same four counts in every class except
`test.robot.javafx.scene.SystemMenuBarClickEventsTest`, 2 / 1 / 0 / 0 before and 2 / 0 / 0 / 0 after
([per class](US-073-macos-per-class.tsv)). `SliderTooltipNPETest` (intermittent per the US-001b gate) passed in both
runs; `SystemMenuBarHelpMenuTest` is skipped (2 skipped) in both.

javafx.graphics before: the reports of the run of 2026-11-25 17:46-17:48 on `3f19f2c154` (US-131's run 5,
`mvn -B -ntp -o -fae -pl modules/javafx.web -am test -Djfx.web.skipTests=false -Djfx.web.skipFfmTests=false
'-Dtest=!UserDataDirectoryTest' -Dsurefire.failIfNoSpecifiedTests=false`, whose `-am` ran the upstream modules'
tests): 607 report files, 0 failures, 0 errors, their `tests` attributes summing to 26,555 tests with 859 skipped.
These figures were read on 2026-11-26 before any change; the after run overwrote those reports. After: 607 report
files, 0 failures, 0 errors, summing to 26,140 tests with 853 skipped (console total 26,889 / 0 / 0 / 859). The test
counts of the two runs are not comparable, because `-Dtest` replaces a module's includes and excludes, so the
before run selected tests differently. What the comparison shows is 0 failures and 0 errors in javafx.graphics
before and after: no new failure. `fe5a0072df` differs from `3f19f2c154` only in four backlog files.

After the run there was no `hs_err_pid*` file under `tests/system`, `modules/javafx.graphics/target`,
`modules/javafx.controls/target` or `modules/javafx.base/target`, and `git status --short` listed exactly the three
changed files.

## Event posting (criterion 7)

The owner allowed robot runs from the session for this story on 2026-11-26. At that moment the session could not
post events (`CGPreflightPostEventAccess` false, `AXIsProcessTrusted` false; host Terminal.app), so nothing was run.
The owner then granted access, and both checks returned true at 05:52:53 and again before each batch of runs
(05:55, 06:12, 06:14, and before the full run); the checks kept with the before batch and the mutant read
`CGPreflightPostEventAccess True AXIsProcessTrusted True`. Every robot run above was launched from that session
after a true/true check: the diagnostic (05:53), the real test before the change (05:55-05:59), the mutant (06:12),
the 20 + 20 runs (06:14-06:46) and the full run (06:47-07:28). No subagent ran a robot test or anything under
`tests/system`.
