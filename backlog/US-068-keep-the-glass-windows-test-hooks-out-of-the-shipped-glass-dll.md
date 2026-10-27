# US-068 — Keep the Glass Windows test hooks out of the shipped glass.dll

**Status:** 📋 Ready (filed 2026-10-26 from the /code-review of the US-039 branch) · **Found:** 2026-10-26, review
of the US-039 branch · **Blocked by:** none · **Blocks:** none. It should precede US-031 slice 3, so that the Rust
port does not re-export the hooks

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
  unchanged.
- The test build passes every `Win*` test class unchanged, except that `WinGlassNativeTest`'s `BOUND_SYMBOLS` loses
  the three facade-bound names, and `WinToolkitShutdownHammerTest` is clean.
- `WinGlassNative` initializes against the release glass.dll.

## Definition of Done
Merged and verified on Windows. `backlog/README.md` is updated.

## Notes
- Evidence: the review of the US-039 branch (2026-10-26), read from the diff. One check: the `GLASS_WIN_EXPORT`
  declarations of `glass_win_api.h` at commit `0ef4947515` (123 names, 26 `gwin_test_*`), the same count as the
  export list of a Release glass.dll built from that commit. Nothing else was built or run.
