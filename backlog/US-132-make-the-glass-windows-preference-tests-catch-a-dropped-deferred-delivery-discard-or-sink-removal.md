# US-132 — Make the Glass Windows preference tests catch a dropped deferred delivery, discard or sink removal

**Status:** 📋 Ready (filed 2026-11-26 from US-029) · **Found:** the end-to-end mutant run of US-029's port and its
triage, 2026-11-26; from their reports; nothing built or run for this story · **Before:** US-031 slice 3, which moves
six of the seven sites below

## Story
As a maintainer who changes or ports the Glass Windows toolkit core,
I want a Windows module test to fail when a delivery of the preference types a sink kept, a discard of them, or a sink
removal at teardown is dropped,
so that no change to the code that calls them can lose one without a failing test.

## Problem
Since US-039 part 2 a WinRT preference sink only posts its `PT_*` types to the toolkit window. When that post is
refused with the window published, the sink keeps the types in one atomic word and posts a wake; the toolkit thread
delivers what was kept at several places, and drops it at two. US-029 moved the word, the sinks and their teardown into
the glass-win crate (`modules/javafx.graphics/src/main/native-rust/glass-win/src/prefs.rs`) and left the call sites in
the C++, one call for one call.

### Measured (US-029's reports; nothing new run)
- **Seven mutants fail no test.** On Windows 10.0.19045.6466, on 2026-11-26, on US-029's port as first gated
  (uncommitted on `5a709c189d`, before its rework round added tests), the mutants were built and run against the
  javafx.graphics Windows preference tests (`WinPreferencesNativeTest` 28, `WinPreferenceSinksNativeTest` 10 with 1
  opt-in, `WinPreferenceDispatchTraceTest` 8 at the time, `WinPreferenceActivationFailureTest` 5,
  `WinPreferenceQueryGoldenTest` 1 in verify mode, `WinGlassNativeTest` 27), and reverted; the crate's unit tests were
  not run against them. Not each ran alone: X2, X6 and X9 each ran in one build beside another mutant that a test
  caught, and X16 together with X17. No test caught these seven (line numbers of the tree US-029 left; X6 was at
  `:569` when it ran):

  | Mutant | Site | Change |
  | --- | --- | --- |
  | X2 | `glass-win/src/prefs.rs:245` and `:251`, `Drop for Preferences` | both `remove_*` calls deleted |
  | X6 | `GlassApplication.cpp:572`, `EnterNestedEventLoop` | `glass_prefs_run_deferred()` removed |
  | X9 | `GlassApplication.cpp:295`, the wake arm (`wParam` 0) of `WM_PREFERENCES_CHANGED` | `glass_prefs_run_deferred()` removed |
  | X18 | `glass_win_api.cpp:576`, the pump of `gwin_run_loop` | `glass_prefs_run_deferred()` removed |
  | X19 | `GlassApplication.cpp:205`, `WM_DO_ACTION_LATER` | `glass_prefs_run_deferred()` removed |
  | X16 | `GlassApplication.cpp:229`, `WM_CREATE` | `glass_prefs_discard_deferred()` removed |
  | X17 | `GlassApplication.cpp:250`, `WM_NCDESTROY` | `glass_prefs_discard_deferred()` removed |

- **Why.** `glass_prefs_run_deferred()` is called at five places in the C++, these four and the `WM_TIMER` arm
  (`GlassApplication.cpp:315`), and each delivery path is masked by the others. X16 and X17 need a second toolkit in
  one process, which no test created when they ran; `WinPreferenceToolkitLifecycleTest`, which US-029 added later,
  creates one, and none of the seven was run against it. For X2, the teardown events 1 and 2 of the test hooks are
  logged whether or not the removal ran (`prefs.rs:247`, `:253`), and no test observes a sink still registered after
  teardown.
- **The C++ of `5a709c189d`** would leave the same mutants uncaught: reasoned from the unchanged tests and the
  one-to-one call replacement, not run.

## Requirement
1. On Windows, the javafx.graphics module tests fail when any one of these is removed, each alone:
   - the delivery of kept preference types after a dispatch in `gwin_run_loop`'s pump, in `EnterNestedEventLoop`, in
     the `WM_DO_ACTION_LATER` arm and in the wake arm of `WM_PREFERENCES_CHANGED`;
   - the discard of kept types in the `WM_CREATE` arm and in the `WM_NCDESTROY` arm;
   - the removal of either sink registration at teardown (`AutoHideScrollBarsChanged`, `NetworkStatusChanged`).
2. Each failure message names what is missing: the delivery path, the discard or the removal.

## Constraints
- Test code, the shim and test hooks only: what the release glass.dll does and exports does not change. A hook a test
  needs exists only in the test build of glass.dll (target `glass_test`, the crate's feature `test-hooks`), as US-068
  requires; the release glass.dll keeps its 97 exports and `GLASS_WIN_ABI_VERSION` stays 7.
- No existing assertion is removed or weakened; the existing preference tests keep their counts.
- The new tests run in the default module suite on Windows, whatever the machine's theme, accent colour and network.
- No robot test and nothing under `tests/system`.

## Acceptance criteria
On Windows, with the commands, counts and failure texts recorded here:
1. **Each removal fails a test.** Each of the seven mutants above, applied alone to the tree the story starts from and
   reverted after, fails at least one test of the module with a message that names what is missing; the unmutated
   tree passes (the negative control).
2. **Stable.** The new tests pass 10 runs in a row on the unmutated tree.
3. **Nothing else changes.** The Windows module gate, `mvn -B -ntp -pl
   buildtools/jslc,modules/javafx.base,modules/javafx.graphics,modules/javafx.media clean verify
   -Djfx.parity.require=true`, gives US-029's final per-class counts plus the tests this story adds; the release
   glass.dll's sorted export list is unchanged; `submit.yml` is green.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**
