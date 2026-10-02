# US-028 — Build and link the Rust slices on macOS

**Status:** 🔶 Deferred (filed 2026-09-30). The trigger is the first library also built on macOS whose C is otherwise
ready to be deleted (`jfxmedia`, US-035/US-036, or `fxplugins`, US-032/US-033). There is no macOS host, but CI builds
and tests macOS (`macos-15-intel`, `macos-15` in `.github/workflows/submit.yml`), so this can be verified in CI only.
Nothing was built. · **Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:** US-027

## Story
As a platform maintainer,
I want the Rust slices of the libraries that are also built on macOS to build, link and pass in both macOS CI jobs,
so that their C can be deleted everywhere instead of being kept for macOS alone.

## Problem
- US-027 builds Rust on Windows and Linux only. On `APPLE` its CMake helper refuses any slice that is not switched
  off (US-027, Approach 3).
- Every library that is also built on macOS therefore keeps its C for macOS after the Windows and Linux slices land,
  and each such file is maintained twice. `native/mac.cmake` compiles:
  - the shared `jfxmedia` core, including the GStreamer backend, which `ColorConverter` also serves through
    `CVVideoFrame.mm:192`;
  - `gstreamer-lite`;
  - `fxplugins`, except `mfwrapper`, which is Windows-only.
- Windows-only libraries (`glass`, `prism_d3d`) and Linux-only ones (`glassgtk3`) are not affected.

## Approach
- **Targets.** `x86_64-apple-darwin` (`macos-15-intel`) and `aarch64-apple-darwin` (`macos-15`). Pin both in
  `rust-toolchain.toml` `targets`, and set `MACOSX_DEPLOYMENT_TARGET` to the C build's minimum (from
  `native/mac.cmake`).
- **Exports.** Use `-Wl,-exported_symbols_list` generated from the header (Mach-O names carry a leading `_`), plus
  `-Wl,-u,_<sym>` per Rust export, mirroring the Windows/Linux mechanism of US-027 (Approach 3). Rust std stays
  unexported.
- **Libraries.** Link the list that `rustc --print native-static-libs` prints for the Darwin targets (`-lSystem`,
  `-lc`, `-lm`, and any frameworks).
- **CI.** Add the rustup step to both macOS jobs, in their own shell.

## Acceptance criteria
- Both macOS jobs build every Rust slice that exists at the time, with `JFX_RUST` on, and pass the module tests.
- `nm -gU` lists the same exported symbols as the C build, with no Rust symbol. `otool -L` lists the same
  dependencies.
- With this landed, the Windows/Linux-only restriction is lifted from the C-deletion slices of US-032, US-033, US-035
  and US-036, and they delete their C on macOS too.

## Definition of Done
The PR is merged and both macOS CI jobs are green. The PR states that no local macOS run was possible.
`backlog/README.md` is updated.
