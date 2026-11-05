# US-073 — Make the system menu bar click test pass with a non-auto-hide context menu

**Status:** 📋 Ready (filed 2026-11-05 from the US-001b robot run) · **Priority:** Low (pre-existing: the JNI build
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
3. **Passes reliably.** Afterwards `testSystemMenuBarProcessesClickEvent` passes on macOS in 20 consecutive runs of
   the method alone and in 20 consecutive runs of its class, in which `testSystemMenuBarConsumesClickEvent` passes
   too.
4. **A missing menu still fails.** The test still fails when no context menu shows after the right-click, shown by a
   scratch mutant in which the menu does not show. The check is not removed or skipped.
5. **JDK-8372398's behaviour.** A click on the system menu bar closes a showing auto-hide context menu without
   opening the system menu, and opens the system menu while a non-auto-hide context menu stays showing. The class's
   other assertions keep their meaning.
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
    -Dtest='SystemMenuBarClickEventsTest#testSystemMenuBarProcessesClickEvent'
```
Before the change, both fail at line 136, as in the table above.

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
  failure as pre-existing.
- Line numbers are of `1a6788cc4d`; US-001b changes neither the test nor `Util.java`.
