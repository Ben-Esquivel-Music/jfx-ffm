# US-073 — Make the system menu bar click test pass with a non-auto-hide context menu

**Status:** ✅ Done (2026-11-26, uncommitted, PR pending; port of openjdk/jfx `81cc4f0b50`, JDK-8393642, which takes
the `cancelTrackingWithoutAnimation` call out of Glass mac's `menuWillOpen:` and brings upstream's test; filed
2026-11-05 from the US-001b robot run) · **Priority:** Low (pre-existing: the JNI build
at `1a6788cc4d` fails the same way; seen only in this robot test so far) · **Estimate:** S ·
**Found:** 2026-11-05, the owner's `-DFULL_TEST=true -DUSE_ROBOT=true` run of `tests/system` on the US-001b tree
(macOS 27.0.1, 26A434)

## Story
As the maintainer reading a macOS robot run,
I want a context menu with auto-hide off to show after a right-click on macOS, and
`SystemMenuBarClickEventsTest.testSystemMenuBarProcessesClickEvent` to pass when it does and fail when it does not,
so that the class checks the system menu bar on every run instead of being a known failure.

## Problem
`tests/system/src/test/java/test/robot/javafx/scene/SystemMenuBarClickEventsTest.java` came from upstream
e84b145731 (8372398: [macOS] Shown ContextMenu does not close when macOS system menu is clicked), the only commit
that touches it in this tree. `testSystemMenuBarProcessesClickEvent` fails at line 136:

```
Context menu should be showing after right-click ==> expected: <true> but was: <false>
```

Up to their identical right-click, the class's two methods differ only in the context menu's auto-hide.
`testSystemMenuBarConsumesClickEvent` sets it on (line 91), and its check at line 102 passes.
`testSystemMenuBarProcessesClickEvent` sets it off (line 125) and fails at line 136. Line 136 asserts as soon as
`Util.runAndWait` returns (`Util.java:143-166` polls the right-click runnable at 0 ms, then every 100 ms), with no
wait for the menu. Whether the menu shows late or never is not known. The method also fails when run alone.

Runs on the US-001b gate host (macOS 27.0.1, 26A434), 2026-11-05, times EST:

| Time | Build | Run | `testSystemMenuBarProcessesClickEvent` | `testSystemMenuBarConsumesClickEvent` |
| --- | --- | --- | --- | --- |
| 08:25-09:07 | FFM (US-001b tree) | owner, all of `tests/system`: 1,099 tests, 2 failures | failed at :136, 0.219 s | passed |
| 11:17 | FFM | owner, the method alone | failed at :136, 0.212 s | not run |
| 11:53 | FFM | owner, the class, with `SliderTooltipNPETest` and `TooltipTest` | failed at :136, 0.220 s | passed |
| ended 11:56:47 | JNI (clone detached at `1a6788cc4d`) | agent at the owner's request, the class | failed at :136, 0.214 s | passed |
| ended 11:57:45 | JNI | the same | failed at :136, 0.216 s | passed |

The clone's `libglass.dylib` exports 155 `_Java_` symbols, and surefire's `java.library.path` was the clone's own
`native/bin`. Its runs came from a session where `CGPreflightPostEventAccess` and `AXIsProcessTrusted` were both
true, and used, from the clone's root:

```
mvn -B -ntp -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='test/robot/javafx/scene/SystemMenuBarClickEventsTest'
```

The failure, its line and its duration are the same in both builds (FFM 3 of 3, JNI 2 of 2), so it is not a
US-001b regression. A reading of the right-click path in both trees found no FFM-vs-JNI behaviour difference. Upstream
disabled the sibling `SystemMenuBarHelpMenuTest` in e40a73e689 (8381578: Skip failing SystemMenuBarHelpMenuTest,
`@Disabled("JDK-8381443")`).

## Acceptance criteria
1. **Upstream first.** Before any change, search openjdk/jfx (its `master` and JBS) for a fix, a skip or an open
   bug for this test, and record what was found here, with the IDs.
2. **Cause recorded.** Find out whether, on macOS, a context menu with auto-hide off shows after the robot's
   right-click but only after line 136 has checked, or does not show at all, and whether the cause is in the test
   or in JavaFX. Record both here, with the evidence.
3. **Passes reliably.** *Amended by the owner on 2026-11-26, when choosing to port upstream as it is.* Afterwards
   `testSystemMenuBarProcessesClickEventAndKeepsNonAutoHidePopup` passes on macOS in 20 consecutive runs of the
   method alone and in 20 consecutive runs of its class, in which
   `testSystemMenuBarProcessesClickEventAndClosesAutoHidePopup` passes too.
   As filed: ~~Afterwards `testSystemMenuBarProcessesClickEvent` passes on macOS in 20 consecutive runs of the method
   alone and in 20 consecutive runs of its class, in which `testSystemMenuBarConsumesClickEvent` passes too.~~
4. **A missing menu still fails.** The test still fails when no context menu shows after the right-click, shown by a
   scratch mutant in which the menu does not show. The check is not removed or skipped.
5. **JDK-8372398's behaviour.** *Amended by the owner on 2026-11-26, when choosing to port upstream as it is.* The
   behaviour is upstream's after JDK-8393642, on every macOS version (the ported change has no version check): a click
   on the system menu bar closes a showing auto-hide context menu and opens the system menu; with a non-auto-hide
   context menu showing, the system menu opens and the context menu stays showing. The half given up, "without
   opening the system menu", is tracked upstream as JDK-8394185.
   As filed: ~~A click on the system menu bar closes a showing auto-hide context menu without opening the system
   menu, and opens the system menu while a non-auto-hide context menu stays showing. The class's other assertions
   keep their meaning.~~
6. **No new failures.** A product change also leaves the `javafx.graphics` module tests and the other robot classes
   of `tests/system` with no new failure against a run before it.
7. **Robot runs.** Every robot run for this story is run by the owner, or by an agent the owner explicitly
   authorized, from a session whose input-event posting is confirmed (`CGPreflightPostEventAccess` and
   `AXIsProcessTrusted` true). A run from a session without it is not evidence.

## Verification
On macOS, from the repository root, under criterion 7:
```
# The class (criterion 3: 20 runs)
mvn -B -ntp -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='test/robot/javafx/scene/SystemMenuBarClickEventsTest'
# The method alone (criterion 3: 20 runs)
mvn -B -ntp -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='SystemMenuBarClickEventsTest#testSystemMenuBarProcessesClickEventAndKeepsNonAutoHidePopup'
```
The method-alone command names the ported test's method: the old name, `testSystemMenuBarProcessesClickEvent`, no
longer exists, and with `-Dsurefire.failIfNoSpecifiedTests=false` it would run 0 tests and exit green.

Measured before the change on 2026-11-26 (macOS 27.2.1, `fe5a0072df`): the class failed at line 136 in 1 of 1 run,
while `testSystemMenuBarConsumesClickEvent` passed; the method alone, under its old name, passed in 3 of 3 runs.

## Results
The changes are in the working tree, uncommitted, for the owner to commit. Every run of 2026-11-26 below was on the
owner's Mac, macOS 27.2.1 (26B131), arm64, OpenJDK 27.0.1, times EST; the upstream facts, the run of 2026-11-05 and
the baselines of 2026-11-25 are attributed where they appear. The detail is in
[US-073-macos-evidence.md](US-073-macos-evidence.md).

1. **Upstream first** (searched 2026-11-25). Upstream tracks and fixes this failure as JDK-8393642 ("[macOS]
   Application hangs when a system menu bar is clicked while a ContextMenu is open, starting macOS 27"),
   Resolved/Fixed in jfx28, whose description quotes this method, this message and line 136 on macOS 27. The fix
   is commit `81cc4f0b50` (PR openjdk/jfx#2338, 2026-10-09): it removes `[_menu cancelTrackingWithoutAnimation]`
   and the early `return` from `-[GlassMenu menuWillOpen:]`, keeps `[GlassWindow _resetGrab]`, and rewrites the
   test. It is backported as JDK-8394346 (jfx27.0.2, jfx27u `8ce7272036`). JDK-8394185 (Open, no fix) tracks
   restoring "a second click should be needed to open the system menu when auto-hide popups are opened".
   JDK-8372398 is the change that added the test and the `cancelTrackingWithoutAnimation` call. Upstream has no
   skip, `@Disabled` or problem-list entry for this test; the sibling skip of `SystemMenuBarHelpMenuTest`
   (JDK-8381578 for JDK-8381443) has a different cause. Before this story the fork had neither half of
   `81cc4f0b50`.
2. **Cause: never shown, not late, in the class run.** A scratch diagnostic of seven classes (2026-11-26
   05:53-05:54, on the unchanged tree, since deleted) replayed the test's calls with its pacing and recorded every
   mouse event, `CONTEXT_MENU_REQUESTED`, show and hide, and focus change. In the class order, the first method's
   menu-bar click hid its auto-hide menu 38 ms later without opening the system menu, as JDK-8372398 intended. From
   the second method's right-click on, not one mouse event (and no `CONTEXT_MENU_REQUESTED`) reached either scene
   until the run ended 12.1 s later, through three more right-clicks, mouse moves and a primary click, while FX
   runnables kept running and the stage stayed focused and showing; an Escape was also posted, but key events were
   not recorded. Line 136's read was `false` 103.4 ms after the click. This is the hang JDK-8393642 records:
   `menuWillOpen:` calls `[_menu cancelTrackingWithoutAnimation]` when a window holds the grab, and from macOS 27,
   according to JDK-8393642, that leaves AppKit in its menu tracking loop. The cause is in JavaFX's native Glass, the
   call JDK-8372398 added, meeting an AppKit change in macOS 27; it is not in the test. It does not depend on
   `autoHide=false`: a non-auto-hide menu shown after an auto-hide one, with no menu-bar click between them,
   showed 3.4 ms after the click, and the failure follows the menu-bar click.

   The Problem section's sentence "The method also fails when run alone" does not hold on 2026-11-26. The story's
   one recorded failure of the method alone (2026-11-05 11:17, macOS 27.0.1, 0.212 s) did not reproduce on
   2026-11-26 (macOS 27.2.1): 3 of 3 runs of the real test and 5 of 5 diagnostic variants of the method alone
   showed the menu (in the diagnostic variants 13.0 to 28.6 ms after the click). Its cause that day is not
   established. The start-up timing is measured as a candidate only: in all seven variants the stage is not focused
   at `onShown` and becomes focused 10.6 to 15.7 ms later; in the six with the original latch, it counts down 9.7 to
   13.7 ms after that, so the original test started about 10 ms after the stage got focus, by the accident of one
   `runLater`. Upstream's test, now ported, waits for stage focus before it starts.
3. **Passes reliably** (amended criterion), 2026-11-26 06:14-06:46 on the changed tree. The method alone:
   20 of 20 runs passed, each 1 / 0 / 0 / 0 (tests / failures / errors / skipped), method time 0.524 to 0.538 s.
   The class: 20 of 20 runs passed, each 2 / 0 / 0 / 0; `...AndClosesAutoHidePopup` (runs first) 0.527 to
   0.540 s, `...AndKeepsNonAutoHidePopup` 0.514 to 0.528 s, so no run came near the test's 5 s latch.
4. **A missing menu still fails**, 2026-11-26 06:12-06:13. The scratch mutant `SystemMenuBarClickEventsMutantTest`
   (since deleted), a copy of the ported test that consumes `CONTEXT_MENU_REQUESTED` on the label so that the menu
   is never shown, failed 2 of 2 tests, both at the mutant's `runTest` line 117 (line 115 of the ported test) with
   "Context menu should be showing after right-click ==> expected: <true> but was: <false>", in 0.216 s and
   0.212 s. The ported test asserts the right-click check unconditionally in both methods; nothing is skipped.
5. **JDK-8372398's behaviour** (amended criterion). Before the change, on the unchanged tree, in the diagnostic:
   with an auto-hide context menu (class order), the menu-bar click hid the menu 38 ms later and the system menu
   did not open, after which no mouse event arrived; with a non-auto-hide context menu (the five method-alone
   variants), the system menu opened 54.7 to 70.2 ms after the click and the context menu stayed showing until the
   diagnostic hid it, 2.92 to 2.93 s later. After the change: `...AndClosesAutoHidePopup` asserts, after the
   menu-bar click, that the system menu is showing (a latch on its `onShown`, then `menu.isShowing()`) and the
   context menu is not; it passed in 20 of 20 class runs. `...AndKeepsNonAutoHidePopup` asserts that the system
   menu is showing and the context menu still is; it passed in 20 of 20 runs alone and 20 of 20 class runs. Given
   up against JDK-8372398: a menu-bar click that dismisses an auto-hide context menu no longer leaves the system
   menu closed. The ported change has no macOS version check, so this is given up on every macOS version,
   including those where the hang does not occur (upstream's reviewer: the issue "does not reproduce on macOS
   26.7"); this story ran on macOS 27.2.1 only. Upstream tracks restoring it as JDK-8394185 (Open, no fix); no fork
   story is filed for it.
6. **No new failures.** One run, 2026-11-26 06:47:02-07:28:16, of
   `mvn -B -ntp -fae -pl tests/system -am test -DFULL_TEST=true -DUSE_ROBOT=true`: BUILD SUCCESS in 41 min 14 s,
   0 failures and 0 errors in every module. `tests/system` 1,099 / 0 / 0 / 60 against 1,099 / 1 / 0 / 60 in the
   owner's run of 2026-11-25 14:56-15:54 (`c5448d8440`); 268 classes in both with the same names, and the same
   counts in every class but this one (2 / 1 / 0 / 0 to 2 / 0 / 0 / 0;
   [per class](US-073-macos-per-class.tsv)). javafx.graphics 26,889 / 0 / 0 / 859, and 0 failures and 0 errors in
   its 607 reports both before (2026-11-25, `3f19f2c154`) and after. No `hs_err_pid*` file.
7. **Robot runs.** The owner authorized robot runs from the session for this story on 2026-11-26 and granted it
   event posting; `CGPreflightPostEventAccess` and `AXIsProcessTrusted` were both true at 05:52:53 and again before
   each batch. Every robot run above (the diagnostic, the real test before the change, the mutant, the 20 + 20 runs
   and the full run) was launched from that session after such a check. No subagent ran a robot test or anything
   under `tests/system`.

## Out of scope
- `SystemMenuBarHelpMenuTest` (disabled upstream for JDK-8381443) and the other system menu bar tests.
- `SliderTooltipNPETest`'s timeout in the same full run, which the US-001b gate records as intermittent.

## Definition of Done
- Criteria 1 and 2 are recorded here, and criteria 3 to 7 are met, with the before and after results stated in this
  story.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-001b; its gate ([US-001b-macos-gate.md](US-001b-macos-gate.md), "Menus (slice 9)") records this
  failure of 2026-11-05 as pre-existing, and this story's fix.
- Line numbers in Problem, Acceptance criteria, Verification and Results items 1 and 2 are of the test as it was at
  `1a6788cc4d` (upstream e84b145731's file, unchanged up to `fe5a0072df`); US-001b changes neither the test nor
  `Util.java`. The line numbers of Results item 4 are of the ported test, upstream's file at `81cc4f0b50`.
- Changed files (working tree, uncommitted):
  - `modules/javafx.graphics/src/main/native-glass/mac/GlassMenu.m`: the grab block of `menuWillOpen:` is upstream's
    after `81cc4f0b50`, byte for byte; the fork's FFM `menu_opening` callback code after it is unchanged and now
    also runs when a grab existed, as upstream's does. The rebuilt `libglass.dylib` has 195 `gmac_*` exports, no
    `Java_*`, and no `cancelTrackingWithoutAnimation` selector string.
  - `modules/javafx.graphics/src/main/native-glass/mac/glass_mac_api.h` (fork-only): the comments of
    `GmacMenuCallbacks` slots 2 and 3 state the new behaviour (comment only).
  - `tests/system/src/test/java/test/robot/javafx/scene/SystemMenuBarClickEventsTest.java`: byte-identical to
    upstream's file at `81cc4f0b50`.
- The Objective-C and the header were changed by the `native-build-engineer` agent, the test by the `java-27` agent.
  Two `ffm-reviewer` reviews (port fidelity; the changed path's behaviour in the FFM layer) found the stale header
  comment, fixed in the same change, and nothing else in the code.
