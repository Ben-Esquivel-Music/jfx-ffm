# US-133 — Balance each toolkit thread's RoInitialize in the handle that made it

## In short
- **Closed without change on 2026-11-27 (owner's decision).**
  Nobody has seen it fail and no known path reaches it. It needs two toolkits alive at once, which nothing is known to
  create, or a bug in our own code first. The C++ before the Rust port had the same flaw.
- **Problem:** Glass on Windows starts the Windows Runtime on its thread and stops it at shutdown, but tracks that
  with one flag for the whole process. If two toolkits ever existed at once, one could stop the runtime on the
  other's thread, or leave its own running.
- **Seen failing?** No. Read from the code. Nobody has shown that two toolkits can exist at once, and the other
  path needs a bug in our own code first. The C++ before the Rust port had the same flaw.
- **Goal:** Each start of the Windows Runtime is undone exactly once, on the same thread, by the toolkit that made it.
- **Done when:** A test fails on today's code with a message that names the imbalance, and passes after the change.

**Status:** ⛔ Closed without change (2026-11-27, owner's decision; the reason is under "In short").
The status as written before:
📋 Ready (filed 2026-11-27 from US-029) · **Found:** the code review of `7c38bdc73a` (US-029's port),
2026-11-27, its finding F2; from the code; nothing built or run for this story

## Story
As a maintainer of the Glass Windows toolkit,
I want the `RoUninitialize` of a toolkit's preferences handle to undo exactly the `RoInitialize` that handle made, on
the thread it made it on,
so that no toolkit uninitialises the Windows Runtime of a thread it did not initialise and none leaves its own thread
initialised.

## Problem
The glass-win crate (`modules/javafx.graphics/src/main/native-rust/glass-win/src/`) ties `RoUninitialize` to one
process-wide flag, not to the handle whose creation called `RoInitialize`.

### The code (line numbers of the tree US-029 left: `fcfc41cf48`, which is `7c38bdc73a` with its review fixes)
- `prefs.rs:55-59`: `static RO_INITIALIZED: AtomicBool`, "Whether a successful `RoInitialize` is in force: the
  process-wide flag of `RoActivationSupport.cpp` at `5a709c189d`".
- `prefs.rs:119-131`, `Preferences::create`: calls `sys::ro_initialize_single_threaded()`
  (`RoInitialize(RO_INIT_SINGLETHREADED)` on the calling thread, `S_OK` and `S_FALSE` count as success,
  `sys.rs:81-86`) only while `RO_INITIALIZED` is false, and sets the flag on success. It activates `UISettings` and
  `NetworkInformation` whenever the flag is set, also when this create called no `RoInitialize`.
- `prefs.rs:268-272`, `Drop for Preferences`: calls `sys::ro_uninitialize()` (`RoUninitialize` on the dropping thread)
  whenever the flag is set, whoever set it, then clears the flag. Nothing records which handle or thread made the
  `RoInitialize`. The `// SAFETY:` comment of `sys::ro_uninitialize` (`sys.rs:90-91`) says "the caller pairs this with
  its successful RoInitialize on the same thread"; `Drop` does not check that.
- `ffi.rs:205-214`, `glass_prefs_destroy`: a poisoned handle is left alone, so its `Drop` never runs and the flag stays
  set (US-029 deviation 10).
- A handle belongs to one `GlassApplication`: `PreferencesHandle` calls `glass_prefs_create` in its constructor and
  `glass_prefs_destroy` in its destructor (`GlassApplication.h:89-90`); `gwin_app_create` constructs the object
  (`glass_win_api.cpp:549`), on "the thread that will call gwin_run_loop", and "A second call leaks the first
  instance, as the JNI did" (`glass_win_api.h:720-722`). `GlassApplication.cpp:224-226` names the case of
  `gwin_app_create` "called again" while the earlier instance's toolkit window has not yet had its `WM_NCDESTROY`.

### Inherited from the C++
At `5a709c189d` the same flag was `bool initialized` in `RoActivationSupport.cpp:42`: `tryInitializeRoActivationSupport`
returned at once when it was set (`:68-70`) and set it after a successful `RoInitialize` (`:105-110`);
`uninitializeRoActivationSupport` called `RoUninitialize` when it was set and cleared it (`:115-122`). They ran from
`PlatformSupport::InitializeWinRT` (`PlatformSupport.cpp:130-136`) and from the end of `~PlatformSupport`
(`:194-222`). US-029 reproduced it, as a port does (`jfx-rust-port` §2.9, §6); its code review found it.

### Where a wrong handle or thread balances, or none does (read from the code; no failing scenario was run)
1. **Two handles alive at once.** Handle A is created on thread TA: the flag is false, `RoInitialize` on TA succeeds,
   the flag is set. A second `gwin_app_create` on thread TB before A is destroyed creates handle B: the flag is set,
   so B calls no `RoInitialize` on TB and calls the activations there. If B is destroyed first, on TB, its `Drop` calls
   `RoUninitialize` on TB, which no handle initialised, and clears the flag; A's later `Drop` on TA finds the flag
   clear, so TA's `RoInitialize` is never balanced. If the first instance is leaked, as the header says a second call
   does, A is never destroyed and the same holds. With TB the same thread as TA, B's `Drop` balances A's
   `RoInitialize` while A still holds its WinRT objects. Whether Java ever makes a second `gwin_app_create` while the
   first instance lives was not checked.
2. **A poisoned handle.** A panic the crate catches while a query reads the live handle poisons it
   (`ffi.rs:182-191`); its destroy then leaves the flag set and TA's `RoInitialize` unbalanced. A later toolkit on
   thread TB calls no `RoInitialize`, and its `Drop` calls `RoUninitialize` on TB. Reachable only after a crate bug.

`WinPreferenceToolkitLifecycleTest` creates its second toolkit "after the first one was torn down" (its class comment,
`WinPreferenceToolkitLifecycleTest.java:56`), so it never has two handles alive at once; other tests were not
searched for such a case.

## Requirement
1. Every successful `RoInitialize` (`S_OK` or `S_FALSE`) that `glass_prefs_create` makes on a thread is balanced by
   exactly one `RoUninitialize` on that thread, made by the teardown of the handle whose creation made it.
2. No handle calls `RoUninitialize` on a thread for which it made no successful `RoInitialize`.
3. A poisoned handle's teardown still does not run (US-029 deviation 10); no other handle uninitialises for it.

## Constraints
- `GLASS_WIN_ABI_VERSION` stays 7; the declarations of `glass_win_api.h` and the sorted export lists of both glass.dll
  builds (release 97, test build 123) are unchanged.
- Windows only.
- The query golden `win-prefs-query-golden-5a709c189d.txt` is unchanged and passes in verify mode on its capture
  machine.
- The existing apartment tests, `WinPreferenceToolkitLifecycleTest` and `WinPreferenceActivationFailureTest`, and the
  other preference tests (`WinPreferencesNativeTest`, `WinPreferenceSinksNativeTest`,
  `WinPreferenceDispatchTraceTest`) keep passing with their counts; no existing assertion is removed or weakened.
- The proof uses test code, the shim and test-build hooks only: a hook exists only in the test build of glass.dll
  (target `glass_test`, the crate's feature `test-hooks`), as US-068 requires.
- No robot test and nothing under `tests/system`.

## Acceptance criteria
On Windows, with the commands, counts and failure texts recorded here:
1. **The imbalance fails a test.** A Windows module test fails on the code US-029 left with a message naming the
   imbalance (the thread whose `RoInitialize` stays unbalanced, or the `RoUninitialize` made by a handle that did not
   initialise that thread), and passes after the change. A mutant that restores the process-wide flag, applied to the
   changed tree and reverted after, fails it with that message; the unmutated tree passes (the negative control).
2. **Stable.** The new test passes 10 runs in a row.
3. **Nothing else changes.** The Windows module gate, `mvn -B -ntp -pl
   buildtools/jslc,modules/javafx.base,modules/javafx.graphics,modules/javafx.media clean verify
   -Djfx.parity.require=true`, gives US-029's final per-class counts plus the tests this story adds; both glass.dll
   export lists are `cmp`-identical; the golden in verify mode passes; `submit.yml` is green.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**
