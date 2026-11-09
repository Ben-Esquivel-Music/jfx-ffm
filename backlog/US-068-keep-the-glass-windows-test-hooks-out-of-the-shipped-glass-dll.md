# US-068 — Keep the Glass Windows test hooks out of the shipped glass.dll

**Status:** ✅ Done (2026-11-08, uncommitted, PR pending; filed 2026-10-26 from the /code-review of the US-039
branch; option 1, a test build of glass.dll behind the CMake option `GLASS_WIN_TEST_HOOKS`, `GLASS_WIN_ABI_VERSION`
7, see "Done" below) · **Found:** 2026-10-26, review of the US-039 branch · **Blocked by:** none · **Blocks:** none.
It should precede US-031 slice 3, so that the Rust port does not re-export the hooks

## Story
As the maintainer of Glass on Windows,
I want the test hooks, and the probes that serve them, built only into a test build of glass.dll,
so that the release library exports no `gwin_test_*` name and carries no probe on its production paths.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/native-glass/win`.
- 26 of the 123 exports of the release glass.dll are `gwin_test_*` hooks, 17 of them added by US-039 (all declared
  in `glass_win_api.h`; 15 defined in `glass_win_test.cpp`, 2 in `PlatformSupport.cpp`). Among them: a decoy-window
  factory that holds up to 5000 windows (`gwin_test_decoy_*`), a sender of `WM_DO_ACTION_NOTIFY` with any `WPARAM`
  (`gwin_test_send_action_notify`) and a drainer of the calling thread's private posted messages
  (`gwin_test_peek_private_messages`).
- The probes are compiled into production paths (`glass_win_test.h`), among them: the park point inside the shared
  section of `GlassApplication::PostToToolkit`, which holds that section while armed; `GwinTestImeAllocPoint` and
  `GwinImmGetCompositionString` in `GlassInputTextInfo`; the recording of the WinRT sink delegates; the counters.
- Unarmed, each probe costs one volatile or thread-local load. The real cost is what ships: entry points and probes
  that only the tests use, in every release.

## Proposed fix
The implementer picks one:
- compile every `gwin_test_*` definition (`glass_win_test.cpp` and the hooks in `glass_win_api.cpp`,
  `PlatformSupport.cpp`, `GlassAccessible.cpp`, `GlassTextRangeProvider.cpp`) and the probe bodies only into a test
  build of glass.dll, behind a CMake option that the Maven test phase uses (for example `-DGLASS_WIN_TEST_HOOKS=ON`
  in `native/win.cmake`), so that the release build has no `gwin_test_*` export and its probes are inline no-ops;
- or move the hooks into a separate test DLL.

Either way `WinGlassNativeShim` keeps binding the hooks by name, and fails loudly, not skips, when they are missing
in a test run. Three of them, `gwin_test_fire_callback`, `gwin_test_fire_clipboard_callback` and
`gwin_test_fire_dnd_callback`, are bound eagerly by `WinGlassNative` (its `GWIN_TEST_FIRE_*CALLBACK` fields), so
their bindings move to `WinGlassNativeShim`; otherwise `WinGlassNative` fails to initialize against the release
glass.dll.

Constraints:
- US-052 part 1 and US-053 reuse these hooks.
- `GLASS_WIN_ABI_VERSION` follows the header's rule: shim-only hooks never move it; dropping the three facade-bound
  hooks bumps it together with `WinGlassNative.ABI_VERSION`.
- The release export list loses exactly the `gwin_test_*` names.

## Acceptance criteria
- The release glass.dll exports no `gwin_test_*` name (`dumpbin -EXPORTS`); its other 97 export names are
  unchanged. ✅ Met: 97 names, none `gwin_test_*`, equal to `8daf41d78a`'s 123 minus its 26 hooks.
- The test build passes every `Win*` test class unchanged, except that `WinGlassNativeTest`'s `BOUND_SYMBOLS` loses
  the three facade-bound names, and `WinToolkitShutdownHammerTest` is clean. ✅ Met, with one forced deviation (the
  ABI literal 6 → 7 in `WinGlassNativeTest`): the 24 classes keep their counts; the hammer is clean in 10 of 10 runs.
- `WinGlassNative` initializes against the release glass.dll. ✅ Met: `WinReleaseGlassLibraryTest`'s release-only
  child (ABI 7 = 7), and the toolkit starts on it in the `tests/system` Win* classes and the robot suite.

## Definition of Done
Merged and verified on Windows. `backlog/README.md` is updated. Verified on Windows and the README updated on
2026-11-08 (see "Done"); the merge is the owner's.

## Notes
- Evidence: the review of the US-039 branch (2026-10-26), read from the diff. One check: the `GLASS_WIN_EXPORT`
  declarations of `glass_win_api.h` at commit `0ef4947515` (123 names, 26 `gwin_test_*`), the same count as the
  export list of a Release glass.dll built from that commit. Nothing else was built or run.

## Done (2026-11-08, uncommitted)

### What changed
- **Native** (`native/win.cmake`, `native-glass/win`). The `glass` target stays the release glass.dll in
  `target/native/bin`: no `gwin_test_*` definition is compiled into it, and the probes of `glass_win_test.h` are empty
  inlines (`GwinImmGetCompositionString` forwards to `ImmGetCompositionStringW`). The CMake option
  `GLASS_WIN_TEST_HOOKS` (BOOL, default OFF) adds the target `glass_test`: the same arguments (`GLASS_LIBRARY_ARGS`)
  plus the compile definition `GLASS_WIN_TEST_HOOKS`, `OUTPUT_NAME glass`, written to `GLASS_TEST_BIN_DIR`; configure
  fails when that is `BIN_DIR`. The hook declarations of `glass_win_api.h` sit under `#ifdef GLASS_WIN_TEST_HOOKS`, so
  production code cannot call one; the enums `GwinTestCounter`, `GwinTestEvent` and `GwinTestDelegate` stay visible,
  because production probe call sites pass their constants. `GLASS_WIN_ABI_VERSION` 6 → 7, with its history entry;
  both builds answer 7.
- **Maven** (`modules/javafx.graphics/pom.xml`). The `native-win` profile configures with `-DGLASS_WIN_TEST_HOOKS=ON
  -DGLASS_TEST_BIN_DIR=${project.build.directory}/native/test-bin`, and the graphics surefire passes
  `java.library.path` = `target/native/test-bin`, then `target/native/bin`. Only the javafx.graphics tests load the
  test build; every other module, `tests/system` and the SDK stay on `target/native/bin`.
- **Java**. `WinViewCallbacks` and `WinClipboardCalls` no longer bind `gwin_test_fire_callback`,
  `gwin_test_fire_clipboard_callback` and `gwin_test_fire_dnd_callback`; `WinGlassNativeShim` binds them lazily by name
  (`FireHooks`) and, when they are missing, fails with an `AssertionError` that names the symbol and asks for the test
  build in `target/native/test-bin` first on `java.library.path`. `WinGlassNative.ABI_VERSION` 6 → 7;
  `WinGlassNativeTest.BOUND_SYMBOLS` loses the three names. The new `WinReleaseGlassLibraryTest` (4 tests) guards the
  release DLL: a child JVM whose `java.library.path` is only `target/native/bin` initializes `WinGlassNative` and
  resolves none of the 26 hooks the header declares, and the test fails, never skips, when that glass.dll is missing;
  in the test JVM all 26 resolve in the test build.

### Decisions and the deviation
- A test build of glass.dll, not a separate hook DLL: a separate DLL would leave the probes on the production paths and
  would need glass internals exported.
- No OBJECT library shared by the two targets: it would need a list of the 9 sources whose object code depends on the
  define, which could go stale; each target globs `native-glass/win`, so a new source lands in both. Cost: the clean
  Maven native build (`ALL_BUILD`) takes +7.7 s wall-clock (+12 %, 62.5 s → 70.3 s).
- The three fire hooks moved to the shim's `FireHooks` holder, bound like the other shim-only hooks, so the facade
  binds no hook and initializes on the release build.
- Deviation from AC 2: `WinGlassNativeTest.abiVersionIsTheOneTheFacadeWasWrittenFor` asserts 7 instead of 6, forced by
  the ABI bump this story requires.

### Measured (2026-11-08, Windows 10 Pro x64 10.0.19045, Temurin 27+35, Maven 3.10.0, MSVC 14.44)
Before: master `8daf41d78a` in a separate worktree. After: this working tree on it, natives built from source.

| gate | before | after |
| --- | --- | --- |
| `dumpbin -EXPORTS glass.dll` | 123, 26 of them `gwin_test_*` | release (`target/native/bin`) 97, no `gwin_test_*`, = before minus the 26; test build (`target/native/test-bin`) 123, = before |
| `test.com.sun.glass.ui.win` (run / failures / errors / skipped) | 24 classes, 299 / 0 / 0 / 3 | 25 classes, 303 / 0 / 0 / 3; the only differing row is `WinReleaseGlassLibraryTest` 4 / 0 / 0 / 0 |
| full module run | 937 classes; jslc 196 / 0 / 0 / 3, base 5,510 / 0 / 0 / 22, graphics 26,624 / 0 / 0 / 816 | 938 classes; graphics 26,628 / 0 / 0 / 816, the rest equal; the only differing row is the new class |
| `WinToolkitShutdownHammerTest` | 1 / 0 / 0 / 0 | clean in 10 of 10 separate runs (0.6 to 1.3 million calls each) |
| CI replay `mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false"` | | exit 0, 15 of 15 modules SUCCESS, 0 failures, 0 errors, no CMake warning |
| `tests/system` Win* classes, `-DFULL_TEST=true`, on the release DLL | | 5 classes, 27 / 0 / 0 / 0; `WinToolkitProbe` loaded `target/native/bin/glass.dll` (ABI 7 = 7) and started `WinApplication` |
| robot suite, `-DFULL_TEST=true -DUSE_ROBOT=true`, on the release DLL | | 91 classes, 491 / 0 / 1 / 42; the error, `JFXPanelScaledTest.testScale` (NPE at :92), fails the same way on `8daf41d78a` (filed as US-108) |

- Mutant proof of `WinReleaseGlassLibraryTest`: pointed at a copy of the test build it fails, naming all 26 hooks;
  pointed at a directory with no glass.dll, 2 failures and 0 skipped.
- Loud failure: with only `target/native/bin` on the path, `WinViewNativeTest` has 18 failures and
  `WinImeCompositionFaultNativeTest` 15 errors, 0 skipped.
- No new crash file in any run. The robot suite ran with `-Dtest` (`test/robot/**` without monocle and web), because
  the root pom's `<includes>` overrides `-Dsurefire.includes`.
