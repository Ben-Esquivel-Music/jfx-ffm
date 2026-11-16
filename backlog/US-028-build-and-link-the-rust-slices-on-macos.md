# US-028 — Build and link the Rust slices on macOS

**Status:** 🔶 Deferred (filed 2026-09-30). The trigger is the first library also built on macOS whose C is otherwise
ready to be deleted (`jfxmedia`, US-035/US-036, or `fxplugins`, US-032/US-033). CI builds and tests
macOS (`macos-15-intel`, `macos-15` in `.github/workflows/submit.yml`), and a macOS host has built and tested the
module since 2026-10-24 (US-003).
Nothing was built. · **Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:** US-027

## Story
As a platform maintainer,
I want the Rust slices of the libraries that are also built on macOS to build, link and pass in both macOS CI jobs,
so that their C can be deleted everywhere instead of being kept for macOS alone.

## Problem
- US-027 builds Rust on Windows and Linux only. On `APPLE` its CMake helper refuses any slice registered while
  `JFX_RUST` is on, so `mac.cmake` registers none (see "macOS linking facts from US-027" below).
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
  `-Wl,-u,_<sym>` per Rust export, in the place of US-027's Windows/Linux mechanism (`/EXPORT:`; `--require-defined`
  and a version script). Rust std stays unexported.
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

## macOS linking facts from US-027 (2026-11-16)
US-027 is implemented, uncommitted on `7a66c85e39` at the time of writing; its "Implementation" section has the
detail. Each fact is marked: measured on Windows, measured on Linux (WSL Ubuntu), or read in the code.

What the build does on macOS today:
- `buildtools/rust/jfx_rust.cmake` stops the configure when `jfx_add_rust_slice` is called on an Apple host while
  `JFX_RUST` is on; the message names US-028 and `-DJFX_RUST=false`. With `JFX_RUST` off it only rejects a slice
  marked `C_DELETED` and returns, so no version script, export option or origin check is added (read in the code).
- Nothing switches the slice off in the pom: the `native-mac` profiles pass `-DJFX_RUST=${JFX_RUST}` (default
  `true`) like the other profiles. The macOS build needs no Rust because `native/mac.cmake` registers no slice and
  is unchanged; its `jfx_rust_test` and `jfx_rust_lint` targets only print that no slice is enabled, and no Rust tool
  is looked up (read in the code). The macOS CI jobs got no Rust setup step.

What a macOS slice will meet, from the Windows and Linux work:
- **LTO needs `cargo rustc ... --crate-type staticlib`.** With `crate-type = ["staticlib", "rlib"]` built in one rustc
  call, cargo passes no `-C lto`; building only the staticlib applies the release profile's `lto = true` (archive
  13.1 MB to 4.0 MB measured on Windows, 23.7 MB to 8.1 MB measured on Linux).
- **std's prebuilt objects carry debug information.** On Linux they added 6 `.debug_*` sections to a Release
  library that has none in C (557,472 to 4,705,776 bytes); cargo's `strip` setting does not reach a staticlib, so the
  helper links with `--strip-debug` outside `Debug` (measured on Linux). Not looked at on Windows, where the Release
  link writes no PDB.
- **The platform libraries** come from `rustc --print native-static-libs` on an empty staticlib; the helper drops
  `-lgcc_s` on Linux and `/defaultlib:msvcrt` on Windows (read in the code; lists measured on both).
- **Exports.** `/EXPORT:` on Windows pulls the Rust member out of the archive (measured on Windows). On Linux
  `--undefined=<sym>` links silently when nothing defines the name; `--require-defined=<sym>` fails the link
  (measured on Linux).
- **A C definition wins silently.** Both linkers take a C definition left in an object file over the archive member
  (measured on Windows and Linux). The helper's post-link origin check therefore reads the linker map and fails unless
  each Rust export comes from the crate's archive. It knows two map formats, MSVC's `/map` and GNU ld's `-Map` with
  `--cref`, and not ld64's (read in the code).
- **New system dependencies.** With std linked, `jfxmedia.dll` gained `ntdll.dll` and
  `api-ms-win-core-synch-l1-2-0.dll` (std's stderr write and its `Once`, `Mutex` and `RwLock`), measured on Windows;
  `libjfxmedia.so` kept its `NEEDED` list but lost the `.note.gnu.property` marking `x86 feature: IBT, SHSTK`,
  measured on Linux. Both were accepted as deviations, for the owner to confirm. So the macOS libraries need the same
  before/after dump: `otool -L` and the load commands (`otool -l`), not only `nm -gU`.
- **The exported symbol list hides C++ names.** The Linux version script, applied with Rust on and off, hides 1,244
  names that `libjfxmedia.so` exported by default visibility (`backlog/US-027-linux-hidden-symbols.txt`), measured on
  Linux; an `-exported_symbols_list` on macOS will hide that library's own set, which the PR lists, as
  US-027's acceptance criteria require for Linux.
- **Tool lookup.** cargo, rustc and cbindgen are looked up on `PATH`, then in `$CARGO_HOME/bin` when `CARGO_HOME` is
  set and not empty, else in the home `.cargo/bin`, never both: a rustup proxy runs its tool with `$CARGO_HOME/bin`
  first on `PATH`, so one found outside that directory fails at build time (`could not execute process rustc -vV`,
  measured on Linux).
- **No download.** Every rustc and cargo call runs with `RUSTUP_AUTO_INSTALL=0`, so a missing pinned toolchain fails
  with the helper's message (measured on Windows).
- **Stale archive.** cargo's freshness check uses file times: a crate source restored with an older time (`tar`,
  `cp -p`) left the old archive linked (measured on Linux).
- **Output.** Every cargo step writes one merged log, `<build tree>/jfx-rust/logs/<step>.log`, because a tool's two
  streams come out interleaved in the Maven log (US-121; measured on Windows and Linux).

Not known for macOS:
- what `rustc --print native-static-libs` prints for `x86_64-apple-darwin` and `aarch64-apple-darwin`;
- whether ld64's `-u` fails when nothing defines the symbol, and how ld64 resolves a C definition against an archive
  member;
- the format of ld64's `-map` output, which the origin check would have to read;
- whether std's objects bring debug information or other content into a macOS dylib, and what `otool -L` and the
  load commands show before and after;
- whether the macOS runner images have rustup;
- the licence expressions of the std components and crates linked on the Darwin targets, which `rust-std.md` would
  have to list.
