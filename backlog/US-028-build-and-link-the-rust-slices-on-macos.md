# US-028 — Build and link the Rust slices on macOS

## In short
- **Problem:** US-027 built Rust only on Windows and Linux. macOS still built the C version.
- **Goal:** The Rust code also builds and links on macOS, exporting exactly the names of the C build.
- **State:** Merged (PR #70, 2026-11-21).
- **Left to do (owner):** confirm the recorded side effects (the library is about 320 KB larger and has more system
  imports; CI downloads two more Rust packages). The list is under "Deviations from the story's text" in
  "Implementation" below.

**Status:** 🔶 Merged, not Done (PR #70, 2026-11-21). What is left is under "In short".
The status as written before the merge:
🔷 Implemented, not merged (2026-11-21): uncommitted on `6832730e09`. On the arm64 macOS host the
Rust-linked `libjfxmedia.dylib` exports exactly the names of its C build (501 in Release, 557 in Debug) and loads the
same libraries in the same order, and `mvn -pl modules/javafx.media -am verify` passes with Rust on, with
`-DJFX_RUST=false` and with `-DCONF=Debug`; x86_64 was built and compared there (519 and 575 names, equal), never run
(see "Implementation" below and the gate). Open: the owner's commit and PR, a green
`submit.yml` run with both macOS jobs, which have never run with Rust, and the owner's confirmation of the deviations.
Filed 2026-09-30 · **Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:** US-027 (merged as
`f5e137e15e`)

## Story
As a platform maintainer,
I want the Rust slices of the libraries that are also built on macOS to build, link and pass in both macOS CI jobs,
so that their C can be deleted everywhere instead of being kept for macOS alone.

## Problem
- Before this story, US-027 (merged 2026-11-17) built Rust on Windows and Linux only. On `APPLE` its CMake helper
  refused any slice registered while `JFX_RUST` was on, so `mac.cmake` registered none (see "macOS linking facts from
  US-027" below).
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
  and a version script). Rust std stays unexported. Not taken: an exported list breaks the link of
  `libjfxmedia_avf.dylib` (see "Deviations from the story's text" under "Implementation").
- **Libraries.** Link the list that `rustc --print native-static-libs` prints for the Darwin targets (`-lSystem`,
  `-lc`, `-lm`, and any frameworks). Not taken either (same place).
- **CI.** Add the rustup step to both macOS jobs, in their own shell.

## Acceptance criteria
- Both macOS jobs build every Rust slice that exists at the time, with `JFX_RUST` on, and pass the module tests.
- `nm -gU` lists the same exported symbols as the C build, with no Rust symbol. `otool -L` lists the same
  dependencies.
- With this landed, the Windows/Linux-only restriction is lifted from the C-deletion slices of US-032, US-033, US-035
  and US-036, and they delete their C on macOS too. At implementation this holds but for the C++ that defines a name
  that `libjfxmedia_avf.dylib` imports, or that jfxmedia's own macOS sources reference (US-035, US-036; see
  "Acceptance criteria and evidence").

## Definition of Done
The PR is merged and both macOS CI jobs are green. The PR states that no local macOS run was possible (no longer
true: a macOS host has built this project since 2026-10-24, US-003, and the arm64 runs below were local; see
"Definition of Done" under "Implementation").
`backlog/README.md` is updated.

## macOS linking facts from US-027 (2026-11-16)
US-027 is commit `924047c8b0` (PR #66), with the fixes of its code review uncommitted on top at the time of writing;
PR #66 has since been merged as `f5e137e15e` (2026-11-17), with those fixes. Its "Implementation" and "Review fixes"
sections have the detail. Each fact is marked: measured on Windows, measured on Linux (WSL Ubuntu), or read in the
code. They describe the tree of 2026-11-16; "Implementation" below has what this story measured and changed.

What the build did on macOS on 2026-11-16:
- `buildtools/rust/jfx_rust.cmake` stops the configure when `jfx_add_rust_slice` is called on an Apple host while
  `JFX_RUST` is on; the message names US-028 and `-DJFX_RUST=false`. With `JFX_RUST` off it only rejects a slice
  marked `C_DELETED` and returns, so no version script, export option or origin check is added (read in the code).
- Nothing switches the slice off in the pom: the `native-mac` profiles pass `-DJFX_RUST=${JFX_RUST}` (default
  `true`) like the other profiles. The macOS build needs no Rust because `native/mac.cmake` registers no slice and
  is unchanged; its `jfx_rust_test` and `jfx_rust_lint` targets only print that no slice is enabled, and no Rust tool
  is looked up (read in the code). The macOS CI jobs have no Rust setup step and no Rust cache step; the cache key of
  the three Rust jobs holds `runner.os` and `runner.arch` (read in the workflow).

What a macOS slice will meet, from the Windows and Linux work:
- **LTO needs `cargo rustc ... --crate-type staticlib`.** With `crate-type = ["staticlib", "rlib"]` built in one rustc
  call, cargo passes no `-C lto`; building only the staticlib applies the release profile's `lto = true` (archive
  13.1 MB to 4.0 MB measured on Windows, 23.7 MB to 8.1 MB measured on Linux).
- **std's prebuilt objects carry debug information.** On Linux they added 6 `.debug_*` sections to a Release
  library that has none in C (557,472 to 4,705,776 bytes); cargo's `strip` setting does not reach a staticlib, so the
  helper links with `--strip-debug` in `Release` only, as the other configurations are built to be debugged (measured
  on Linux). Not looked at on Windows, where the Release link writes no PDB.
- **The profiles and the archive.** The release and dev profiles are in `buildtools/rust/cargo-config.toml`, not in
  `Cargo.toml`: every stable setting but `incremental` (`[build] incremental = false` for both) and `split-debuginfo`.
  The helper passes the file with `--config` to every cargo call that compiles, then `--target <host>`, the `host:`
  line of `rustc -vV` at configure time, so the archive lies in `<build tree>/cargo/<host>/{release,debug}/` (read in
  the code; measured on Windows and Linux).
- **`split-debuginfo`.** The helper passes `packed` for a host that ends in `-windows-msvc` and `off` for every other
  host, so an `*-apple-darwin` host would get `off` (read in the code). The release profile has `debug = false`, and
  then no `-C split-debuginfo` reaches rustc (measured on Windows and Linux); the dev profile's rustc line gets it.
  Cargo's default on macOS is `unpacked` for a profile with debug information, rustc's is `packed`, and on macOS
  `off` skips the final run of `dsymutil` (cargo and rustc reference).
- **The cargo environment.** The cargo action removes every variable named `CARGO`, `RUSTC`, `RUSTDOC`, `RUSTFMT`,
  `RUSTFLAGS` or `RUSTDOCFLAGS` or starting with `CARGO_`, `__CARGO_` or `RUSTC_` (but `CARGO_HOME`,
  `CARGO_BUILD_JOBS`, `CARGO_TERM_*`), then sets `RUSTFLAGS` to one blank, `RUSTUP_TOOLCHAIN` to the pin and `RUSTC`
  to the rustc configure checked. A setting a macOS slice needs cannot reach cargo through such a variable (read in
  the code; measured on Windows and Linux).
- **The platform libraries** come from `rustc --print native-static-libs` on an empty staticlib; the helper drops
  `-lgcc_s` on Linux and `/defaultlib:msvcrt` on Windows (read in the code; lists measured on both).
- **Exports.** `/EXPORT:` on Windows pulls the Rust member out of the archive (measured on Windows). On Linux
  `--undefined=<sym>` links silently when nothing defines the name; `--require-defined=<sym>` fails the link
  (measured on Linux).
- **A C definition wins silently.** Both linkers take a C definition left in an object file over the archive member
  (measured on Windows and Linux). The helper's post-link origin check therefore reads the linker map and fails unless
  each Rust export comes from the crate's archive. It knows two map formats, MSVC's `/map` and GNU ld's `-Map` with
  `--cref`, and not ld64's; it adds the GNU options (`LINKER:-Map=<file>`, `LINKER:--cref`) on every host that is not
  Windows (read in the code).
- **New system dependencies.** With std linked, `jfxmedia.dll` gained `ntdll.dll` and
  `api-ms-win-core-synch-l1-2-0.dll` (std's stderr write and its `Once`, `Mutex` and `RwLock`), measured on Windows;
  `libjfxmedia.so` kept its `NEEDED` list but lost the `.note.gnu.property` marking `x86 feature: IBT, SHSTK`,
  measured on Linux. Both were accepted as deviations, for the owner to confirm. So the macOS libraries need the same
  before/after dump: `otool -L` and the load commands (`otool -l`), not only `nm -gU`.
- **The exported symbol list hides C++ names.** The Linux version script, applied with Rust on and off, hides 1,244
  names that `libjfxmedia.so` exported by default visibility (`backlog/US-027-linux-hidden-symbols.txt`), measured on
  Linux; an `-exported_symbols_list` on macOS will hide that library's own set, which the PR lists, as
  US-027's acceptance criteria require for Linux. (At implementation: macOS hides nothing; see "Deviations from the
  story's text".)
- **Tool lookup.** cargo, rustc and cbindgen are looked up on `PATH`, then in `$CARGO_HOME/bin` when `CARGO_HOME` is
  set and not empty, else in the home `.cargo/bin`, never both: a rustup proxy runs its tool with `$CARGO_HOME/bin`
  first on `PATH`, so one found outside that directory fails at build time (`could not execute process rustc -vV`,
  measured on Linux).
- **No download.** Every rustc and cargo call runs with `RUSTUP_AUTO_INSTALL=0`, so a missing pinned toolchain fails
  with the helper's message (measured on Windows).
- **Stale archive.** cargo's freshness check uses file times: a crate source restored with an older time (`tar`,
  `cp -p`) left the old archive linked (measured on Linux).
- **Output.** Every cargo step writes one merged log, `<build tree>/jfx-rust/logs/<step>.log`, because a tool's two
  streams come out interleaved in the Maven log (US-125; measured on Windows and Linux).

Not known for macOS on 2026-11-16 (each is answered under "Implementation" and in the gate):
- what `rustc --print native-static-libs` prints for `x86_64-apple-darwin` and `aarch64-apple-darwin`;
- whether ld64's `-u` fails when nothing defines the symbol, and how ld64 resolves a C definition against an archive
  member;
- the format of ld64's `-map` output, which the origin check would have to read;
- whether std's objects bring debug information or other content into a macOS dylib, and what `otool -L` and the
  load commands show before and after;
- whether the macOS runner images have rustup;
- the licence expressions of the std components and crates linked on the Darwin targets, which `rust-std.md` would
  have to list.

## Implementation (2026-11-21, uncommitted on 6832730e09)

The work is uncommitted on `master` at `6832730e09`: six modified files, no new one (below). This write-up adds
`US-028-macos-gate.md`, with the dumps, the negative controls and the gates, and changes this
story, the status line of US-027 and `backlog/README.md`. US-032, US-033, US-035, US-036 and the `.claude` skill and
agent files are updated in the same working tree by a separate step, not described here. "This Mac" is the project's
arm64 macOS host, which has no Rosetta: x86_64 code was built and inspected there, never run. "Build tree" means
`modules/javafx.media/target/native/cmake`, or a scratch tree configured with the arguments of the `native-mac`
profile.

### What changed

**The toolchain pin** (`rust-toolchain.toml`, 2 lines added).
- `targets = ["aarch64-apple-darwin", "x86_64-apple-darwin"]`, with a comment: a Mac builds Rust for the architecture
  CMake builds, which need not be rustc's host.
- `rustup toolchain install` in the repository root installs the std of both targets. With rustup 1.29.3 on this Mac
  it also adds a listed target that an already installed 1.100.0 lacks: with the x86_64 std removed, the command
  downloaded it again.
- The helper's own rustc and cargo calls set `RUSTUP_TOOLCHAIN`, which outranks the file, so the line changes none of
  them.

**The CMake helper** `buildtools/rust/jfx_rust.cmake` (974 to 1,422 lines; 484 added, 36 removed). The refusal of a
slice on an Apple host is gone. On macOS with `JFX_RUST` on:
- **The target triple.** `--target`, the archive directory and `split-debuginfo` follow the one architecture the tree
  builds for: the single `CMAKE_OSX_ARCHITECTURES` entry, else `CMAKE_SYSTEM_PROCESSOR`, the source `mac.cmake`'s
  `JFXM_TARGET_ARCH` reads too. `arm64` gives `aarch64-apple-darwin`, `x86_64` gives `x86_64-apple-darwin`; anything
  else, two entries included, is a configure error. Before the probe, `rustc --print target-libdir --target <triple>`
  must name a directory with a `libstd-*.rlib`, else the toolchain error names the triple. The global property
  `JFX_RUST_HOST` is now `JFX_RUST_TARGET_TRIPLE`; on Windows and Linux it still holds the host.
- **The deployment target.** When `CMAKE_OSX_DEPLOYMENT_TARGET` is set (media's `native/CMakeLists.txt` sets 11.0),
  `rustc -vV`, the std check, the probe and every cargo action run with `MACOSX_DEPLOYMENT_TARGET` set to it, over any
  inherited value, and the cargo directory is `<build tree>/cargo/macos-<value>`: cargo does not rebuild when only the
  variable changes.
- **The libraries.** `rustc --print native-static-libs` prints `-lSystem -lc -lm` for both Darwin targets. All three
  are dropped, and the configure prints `the Rust standard library links nothing that clang does not link; dropped
  from rustc's list: -lSystem -lc -lm`. Any other `-l<name>` would be linked, and a `-framework <Name>` as one link
  item; anything else is a configure error that names the item.
- **The exports.** One `LINKER:-u,_<sym>` per `EXPORTS` symbol pulls its member out of the archive; ld64 fails the
  link when nothing defines the name.
- **The unexported list.** A `PRE_LINK` step of the target (`Listing the names of the jfxmedia crate's archive that
  jfxmedia keeps local`) runs `nm --no-llvm-bc -g -U -P` on the archive of this very link and writes every name the
  archive defines, less the `EXPORTS` of all the target's slices, to `<build tree>/jfx-rust/<target>.unexported`
  (`<target>-<config>.unexported` in a multi-config tree). The link gets `LINKER:-unexported_symbols_list,<list>`.
  ld64 skips a listed name that the link does not define, so the whole archive is listed: 712 names for arm64
  Release, 2,613 for Debug, 622 for x86_64 Release, 2,539 for Debug. Apple's nm (Xcode 27.1, LLVM 21) cannot read the
  LLVM 23 bitcode that rustc 1.100.0 embeds in the compiler_builtins members and the Debug std members: without
  `--no-llvm-bc` it exits 1 and leaves their names out. The step fails when nm fails, when an `EXPORTS` symbol is
  missing from nm's output, or when a name holds `*`, `?`, `[` or `]` or starts with `#`, which ld64 would read as a
  pattern or a comment (no name does today); it then writes no list.
- **`LINKER:-S` in Release.** std's objects carry DWARF even in release, so the link adds a debug map, whose N_OSO
  names the archive member by its absolute build-tree path. `-S` removes the debug map and nothing else; the C-only
  Release link is byte-identical with it. In Debug it would remove the C code's debug map too, so Debug links
  without it.
- **The map and the origin check.** The link writes `LINKER:-map,<build tree>/jfx-rust/<target>.map`
  (`<target>-<config>.map` multi-config). The `POST_BUILD` check reads ld64's format (`# Object files:`,
  `# Sections:`, `# Symbols:`, cut at `# Dead Stripped Symbols:`), finds the archive's members by the real path of
  `<archive>(<member>)` from both link directories, and fails unless
  (a) each `EXPORTS` symbol is defined by a member of the crate's archive,
  (b) `nm -g -U -P` of the library lists each of them,
  (c) the library exports no other name that the map takes from a member of the archive, and
  (d) nothing outside the archive defines a name of the list, as ld64 would hide that definition too; every
  definition that the map gives to "linker synthesized" is left out (in the Debug maps, 6 std thread-local
  descriptors).
  The verdict goes to `<build tree>/jfx-rust/logs/<target>-origin.log`, as on the other platforms; the Maven tree's
  says that `jfxm_abi_version is defined by cargo/macos-11.0/aarch64-apple-darwin/release/libjfxmedia.a(…cgu.0.rcgu.o)`
  and that `libjfxmedia.dylib exports jfxm_abi_version, and none of the other names on the 1454 lines of the map that
  the archive defines (nm -g -U lists 501 names)`. The check takes 0.04 to 0.06 s on a Release map, 0.10 to 0.11 s on
  a Debug one.
- **One crate per target.** The list holds the names of one archive, so a second crate registered for the same target
  is a configure error. `CMAKE_NM`, which CMake finds with the compiler, must be set, else a configure error.
- **`JFX_RUST` off.** The crate, header and `EXPORTS` checks, the configure dependencies and the line `slice
  JFXMEDIA_ABI_VERSION built from C (JFX_RUST=OFF), 58 exports` run as on Linux, and the link gets no option (the
  version script stays Linux-only), so the library is the C build. The configure removes the origin log of an earlier
  Rust link, on every platform, and on macOS its map and list. `C_DELETED` is a configure error, as before.
- **Windows and Linux** are unchanged but for that removal of a stale origin log. Every other hunk is an `APPLE` or
  ld64 branch, a comment, the renamed property with the same value, or an empty variable; the `native-static-libs`
  text is still split on blanks there, and the msvc and gnu branch of the origin check is byte-identical to
  `6832730e09` (112 lines, `cmp`). Configured on this Mac with Linux and Windows simulated, the helper of
  `6832730e09` against the one before the fix round (5, 8 and 6 configurations), and that one against the final
  helper (12 pairs), gave identical configure output and generated files
  (gate). No real Windows or Linux build was made.

**The registration** (`modules/javafx.media/native/mac.cmake`, 8 lines added): `jfx_add_rust_slice(jfxmedia ...)`
after `add_media_library(jfxmedia ...)`, with the `CRATE`, `SLICE JFXMEDIA_ABI_VERSION`, `EXPORTS jfxm_abi_version`
and `HEADERS jfxmedia_api.h` of `linux.cmake`, and a two-line comment. macOS registers no `jfxmedia_test` slice: the
hooks build is Windows and Linux only (`native/CMakeLists.txt` refuses `JFXM_TEST_HOOKS` on Apple).

**CI** (`.github/workflows/submit.yml`, 979 to 1,325 lines, all CRLF; 350 added, 4 removed). In `macos_x64_build` and
`macos_aarch64_build`:
- `CBINDGEN_VERSION: "0.29.4"` in `env`, and the four steps of the Linux jobs after "Setup environment" and before the
  build: "Locate the pinned Rust toolchain and the cargo and rustup homes" (the channel cut out by parameter
  expansion, as macOS grep has no `-P`), the cache restore, "Set up the Rust toolchain" (`curl -fL` of the job's
  `rustup-init` when no rustup is found, `rustup toolchain install`, `cargo install` of cbindgen only on a version
  mismatch) and the cache save. The cache key is `rust-macOS-<X64|ARM64>-<hash of rust-toolchain.toml>-cbindgen-0.29.4`.
- "Verify the exports of jfxmedia, which links Rust", after the AVF step (`if: ${{ !cancelled() }}`). It fails when the
  library or its origin log is missing and when the library lacks a `JFXM_EXPORT` name of `jfxmedia_api.h`. It then
  builds the C library once more with `-DJFX_RUST=false` into `RUNNER_TEMP`, with the values Maven passed (read from
  the tree's `CMakeCache.txt`), and requires the same `nm -gUj` names and the same `otool -L` list, order included,
  as that C build, and no N_OSO stab in a Release build. Its comment says that a slice of jfxmedia marked `C_DELETED`
  fails this configure, so the change that marks one must give the check another baseline.
- The header comment of the workflow describes the macOS check. The Linux, Windows and `no_jni_check` jobs are
  unchanged (compared as parsed YAML with `6832730e09`).

**The legal notice** (`modules/javafx.media/src/main/legal/rust-std.md`, 8 lines): the standard library is "statically
linked into the jfxmedia library on Windows, Linux and macOS"; compiler-builtins is "Linux and macOS"; rustc-demangle
"Windows, Linux and macOS"; addr2line, gimli, memchr and object "Linux and macOS". On both Darwin targets those five
crates are inside the LTO member that a Release build links (memchr inlined on arm64), and a Debug build links
rustc-demangle, addr2line, gimli and object as members of their own, and memchr too on x86_64; compiler_builtins
members are linked by the Debug builds only. adler2 and miniz_oxide stay
"Linux": no macOS build links them. The first heading, which the licence gate reads, is unchanged.

**`README.md`** (Building): the Rust part is built on Windows, Linux and macOS, the pin includes the std of the two
macOS targets, and "macOS builds no Rust yet." is gone.

**Deviations from the story's text**, each checked against the code.
- **An unexported list, not an exported list from the header** (Approach, "Exports"). Measured by relinking
  `libjfxmedia.dylib` by hand on arm64: with an `-exported_symbols_list` of the 58 header names, the link of
  `libjfxmedia_avf.dylib` fails with 13 undefined symbols, the C++ names it imports from `libjfxmedia.dylib`. With
  those 13 added the avf link succeeds, but the library exports 71 names and hides 430 (Release) or 486 (Debug) that
  the C build exports, against acceptance criterion 2. Such a list would also differ per architecture (x86_64 exports
  18 more typeinfo names), and ld64 fails the link on a listed name that the optimiser did not emit. The unexported
  list leaves the C exports as they are.
- **None of rustc's `native-static-libs` is linked** (Approach, "Libraries"). Passing `-lSystem -lc -lm` draws `ld:
  warning: ignoring duplicate libraries: '-lSystem'` on every link (an error under `-fatal_warnings`) and moves
  `libSystem.B` before `libc++.1` in `otool -L`, against criterion 2. `libc.tbd` and `libm.tbd` are symbolic links to
  `libSystem.tbd`, which clang links anyway. Without the three, the order and the exports equal the C build's, with
  no warning.
- **The `targets` pin** is the story's, but it reaches beyond macOS: `rustup toolchain install` on Windows and Linux,
  in CI and on every developer's machine, now also downloads the std of both Darwin targets (one more std on a Mac),
  and the hash of `rust-toolchain.toml` in the cache key changes the key of all five Rust jobs once. Not measured: the
  size and time of the download.
- **The target triple follows the CMake architecture,** not rustc's host (not in the story): an arm64 Mac builds an
  x86_64 tree with `-DCMAKE_OSX_ARCHITECTURES=x86_64`, and a universal tree is refused with Rust on.
- **One crate per target** is enforced on macOS only, where the list needs it; P2 already asks for one staticlib per
  library.
- **A cargo directory per deployment target** (`cargo/macos-11.0`, not in the story): run again with only
  `MACOSX_DEPLOYMENT_TARGET=12.0` changed, cargo printed `Fresh` and kept the archive's minos 11.0. Old directories
  stay on disk.
- **`LINKER:-S` in Release** (not in the story), for the debug map above. macOS copies no DWARF into a dylib, so this
  is the counterpart of Linux's `--strip-debug`.
- **The origin check reads ld64's map** and adds checks (b) to (d), which the other platforms do not need: there
  `/EXPORT:` or the version script fixes the export list.
- **The CI check builds the C library once more** (not in the story): on macOS the library must export the names of
  its C build (501 on arm64, 519 on x86_64, in Release), not the 58 of the header, and no list of them is committed.
- **"No local macOS run was possible"** (Definition of Done) is no longer true: every run in this section was local,
  on arm64.

### Behaviour changes a user can see

For someone who builds the fork on macOS:
- A default build of javafx.media now needs Rust 1.100.0 with the std of the tree's architecture (`rustup toolchain
  install` in the repository root installs both), and `verify` needs cbindgen 0.29.4 and perl (`/usr/bin/perl`); or
  `-DJFX_RUST=false`, which builds the C and looks up no Rust tool. Without cargo or rustc the media `cmake-configure`
  stops with US-027's message (`jfxmedia: its Rust slices need the Rust toolchain 1.100.0 pinned in
  <repo>/rust-toolchain.toml, and no cargo or no rustc is on PATH or in <CARGO_HOME>/bin.`, then the remedy); that
  path is the Windows and Linux one and was not run on macOS. A toolchain without the std of the tree's target stops
  it with this text (measured in a toy project whose helper copy asks for `aarch64-apple-ios`; shown with jfxmedia's
  names):
  ```
  jfxmedia: its Rust slices need the Rust toolchain 1.100.0 pinned in
  <repo>/rust-toolchain.toml, and it has no standard library for <triple>
  (rustc --print target-libdir --target <triple>: <libdir>), one of the
  targets that rust-toolchain.toml lists for "rustup toolchain install".

  Install rustup from https://rustup.rs and run "rustup toolchain install" in
  <repo>, or configure with -DJFX_RUST=false to build the C implementation of
  jfxmedia instead.
  ```
- A universal tree is refused with Rust on (`-DCMAKE_OSX_ARCHITECTURES="arm64;x86_64"`, or `arm64e`):
  ```
  jfxmedia: its Rust slices are built for one macOS architecture, arm64 or
  x86_64, and this tree builds for arm64, x86_64 (CMAKE_OSX_ARCHITECTURES).
  Configure with -DCMAKE_OSX_ARCHITECTURES=arm64 or
  -DCMAKE_OSX_ARCHITECTURES=x86_64, or with -DJFX_RUST=false to build the C
  implementation of jfxmedia instead.
  ```
  With Rust off it configures as before. No pom passes `CMAKE_OSX_ARCHITECTURES`, so a Maven build is never universal.
- Without an nm the configure stops: `jfx_add_rust_slice(jfxmedia): CMake found no nm (CMAKE_NM), which lists the
  names of the jfxmedia crate's archive that jfxmedia keeps local. Configure with -DCMAKE_NM=<nm>, or with
  -DJFX_RUST=false to build the C implementation of jfxmedia instead.` (seen in a toy project only).
- Every build runs cargo (0.07 s when nothing changed; nothing is relinked then); a link of `jfxmedia` is preceded by
  the listing step.
- Where things are: the verdict in `<build tree>/jfx-rust/logs/jfxmedia-origin.log`, ld64's map in
  `<build tree>/jfx-rust/jfxmedia.map`, the list in `<build tree>/jfx-rust/jfxmedia.unexported`, the cargo logs in
  `<build tree>/jfx-rust/logs/` (`jfxmedia-build-release.log`, `jfxmedia-build-dev.log`, `test.log`, `fmt.log`,
  `clippy.log`, `licences.log`), the archive in `<build tree>/cargo/macos-11.0/<triple>/{release,debug}/`. With
  `-DJFX_RUST=false` the configure removes the first three.
- An x86_64 tree on an arm64 Mac without Rosetta builds and links, but its `jfx_rust_test` fails: `could not execute
  process … (never executed)`, `Bad CPU type in executable (os error 86)`. Maven never configures such a tree.

For someone who ships the libraries (macOS with Rust on; with Rust off the library is the C build in exports,
dependencies and size):
- `libjfxmedia.dylib` exports the names of its C build (501 arm64 Release, 557 arm64 Debug, 519 x86_64 Release, 575
  x86_64 Debug), none starting `__R` or `_rust_`, and `otool -L` lists the same ten entries, its install name and
  nine libraries, in the same order;
  `libjfxmedia_avf.dylib` links against it unchanged and imports the same 13 names. The other four dylibs are
  unchanged.
- **The Mach-O differences** against the C twin, for the owner to accept
  (table): 206,032 to 529,392 bytes on arm64 Release (187,536 to 528,088 on
  x86_64); the header flag `MH_HAS_TLV_DESCRIPTORS`; the sections `__DATA,__thread_vars`, `__thread_data` and
  `__thread_bss`, and `__TEXT,__eh_frame` where the C build has none (on arm64 Release it grows from 0x88 to 0x5160
  bytes); 42 more undefined imports (x86_64 43), all bound to libSystem (`_Unwind_*`, `_dyld_*`, `_tlv_*`, the malloc
  family, `open`, `read`, `write`, dispatch semaphores), none removed; 48 panic-location strings
  (`/rustc/0ef1bc…/library/…`, `/cargo/registry/…`, `buildtools/rust/jfx-rust-support/src/lib.rs`) and no absolute
  path of the build machine. `LC_BUILD_VERSION` (minos 11.0, sdk 27.2) and the kinds of load commands are equal.
- **Debug:** 182 more libSystem imports (x86_64 180), std linked without LTO, among them `_fork`, `_execvp`,
  `_posix_spawnp`, `_dlopen`, `_setuid`, `_chroot` and sockets, none of which jfxmedia calls; the debug map grows from
  29 to 90 N_OSO entries (x86_64 92), and the new ones name the members of
  `<build tree>/cargo/macos-11.0/<triple>/debug/libjfxmedia.a` by absolute path.

### Tests

- **cargo tests** (`jfx_rust_test`, arm64): 14 passed, 0 failed (the support crate's 9 unit tests, its integration
  tests 2, 1 and 1, the `jfxmedia` unit test 1), and two doc-test runs with 0 tests; the same tests as on Windows and
  Linux. In an x86_64 tree they cannot run here (above).
- **The lint** (`jfx_rust_lint`): `fmt.log` empty, clippy with `-D warnings`, cbindgen 0.29.4's two headers, `the
  jfxmedia crate declares exactly the 1 EXPORTS symbol(s)`, `abi_check.cpp` compiled, `check-crate-licences
  --self-test: all 68 cases passed` (perl 5.34.1) and `check-crate-licences: OK, 2 linked crate(s), 0 build-time
  crate(s)`.
- **Java tests:** none changed. javafx.media runs 41, 0 failures, 0 errors, 6 skipped in every gate: the 6 tests of
  the hooks build, which macOS does not have. No Maven run of the unchanged tree was made on this Mac; the run with
  `-DJFX_RUST=false` (gate step 5) is the comparison.
- **The matrix:** four pairs of a Rust and a C tree (arm64 and x86_64, Release and Debug), compared in exports,
  `otool -L`, the avf imports, the load commands, sections, imports, symbol tables and stabs; and a smoke program that
  `dlopen`s each arm64 library and calls `jfxm_abi_version` (4 from each). See the
  gate.
- **Negative controls,** each failing as intended, with the messages in the
  gate: the origin check against a C definition that wins (also in a real
  build, where make deletes the library), another archive, a Rust name exported, no list, the export hidden, no map,
  a C name hidden by the list, a hostile map; the listing step against a missing, truncated or foreign archive and a
  failing nm; `-u` without the archive; the configure against a universal tree, `arm64e`, a missing std, `C_DELETED`,
  no nm and unknown `native-static-libs` items; the toggles of `JFX_RUST` and of the deployment target; and the CI
  step's body against seven faults and a slice marked `C_DELETED`.
- **Windows and Linux,** simulated on this Mac: three comparisons of the helper of `6832730e09` with this story's
  before the fix round (5, 8 and 6 configurations), and one of the helper before the fix round with the final one (12
  pairs and 4 native macOS pairs), all identical.

### Acceptance criteria and evidence
- **Both macOS jobs build every Rust slice with `JFX_RUST` on and pass the module tests.** The one slice is
  `jfxmedia`'s `JFXMEDIA_ABI_VERSION`. Met on this Mac for arm64: `mvn -pl modules/javafx.media -am verify` builds it,
  links it and passes (media 41 / 0 / 0 / 6, the cargo tests, the lint; gate steps 4, 6 and 7). x86_64: built,
  linked and compared in scratch trees, never run. Only the two CI jobs can show the native x86_64 build and tests on
  `macos-15-intel`, and both builds with Xcode 16.4's ld and nm; they have not run.
- **`nm -gU` lists the same exported symbols as the C build, with no Rust symbol; `otool -L` the same
  dependencies.** Met on this Mac on both architectures, Release and Debug: 501, 557, 519 and 575 names, each list
  equal to its C build's, no `__R` or `_rust_` name, `otool -L` equal in order. The CI step checks the same in each
  job against a C build made there.
- **The restriction is lifted from the C-deletion slices of US-032, US-033, US-035 and US-036.** Met in the rules:
  by P2 of `backlog/README.md` a slice's C is now deleted on all three platforms once the slice is accepted on
  Windows, WSL Linux and macOS, and P9 states the macOS export rule. The mechanism is per target, and only
  `jfxmedia` has a slice to show it. One limit remains for US-035 and US-036: `libjfxmedia_avf.dylib`
  (`platform/osx/avf`, C++ and Objective-C++, which no story ports) imports 13 C++ names from `libjfxmedia.dylib`
  (`CAudioTrack`, `CVideoTrack`, `CSubtitleTrack`, `CVideoFrame`, `CVVideoFrame`, `CBandsHolder`, `CLogger`,
  `CLocator`), and jfxmedia's own macOS sources (`platform/osx/OSXPlatform.mm`, `OSXMediaPlayer.mm`,
  `CVVideoFrame.mm`, which no story ports either) reference C++ of both stories (`CLogger`, `CVideoFrame`,
  `CLocatorStream`, `CFfiStreamCallbacks`, `CFfiPlayerEventDispatcher`). C++ that defines a name that
  `libjfxmedia_avf.dylib` imports, or that those three files reference, cannot leave the macOS build while they
  reference it.

### Gates

Host and tools: macOS 27.2.1 (26B131), arm64, 12 cores, no Rosetta; Xcode 27.1 (27A9275), Command Line Tools 27.2.0,
ld-27037.1, Apple clang 21.0.0; CMake 4.4.4; rustc and cargo 1.100.0, cbindgen 0.29.4, rustup 1.29.3; OpenJDK
27.0.1.0.2, Apache Maven 3.10.0. Every step ran on 2026-11-21. Steps 1 and 3 to 7 ran after the last change to the
helper (md5 `b17e019a2f0af91230eabf87f87e2a73`), and step 3 after the last change to the workflow. In step 2 the
origin-check cases, the framework, no-nm and `JFX_RUST`-toggle cases and the `C_DELETED` case of the CI step ran with
it. The listing-step, universal-tree, `arm64e`, missing-std, deployment-target and `-u` cases, the real build in which
a C definition wins, the toy `C_DELETED` configure and the CI step's seven faults ran with earlier versions that give
the same result on those paths.

| Step | Command (from the repository root) | Result on this Mac |
| --- | --- | --- |
| 0 | checks before the runs | as listed below |
| 1 | scratch trees with Rust on: `cmake -S modules/javafx.media/native -B <tree> …`, `cmake --build` | rc 0 each |
| 2 | the negative controls of the gate | each as listed there |
| 3 | the body of both macOS export steps of `submit.yml`, under `/bin/bash -e` | rc 0 each |
| 4 | `mvn -pl modules/javafx.media -am verify` | BUILD SUCCESS, 79 s |
| 5 | `mvn -pl modules/javafx.media -am verify -DJFX_RUST=false` | BUILD SUCCESS, 78 s |
| 6 | `mvn -pl modules/javafx.media -am verify -DCONF=Debug` | BUILD SUCCESS, 87 s |
| 7 | `mvn -pl modules/javafx.media -am verify` | BUILD SUCCESS, 87 s |

- **Step 0:** the helper has 1,422 lines, LF, no tab, no trailing blank and no line over 120 columns, and its msvc
  and gnu origin branch is `cmp`-equal to `6832730e09`'s; the workflow has 1,325 lines, all CRLF, and as parsed YAML
  equals `6832730e09` outside the two macOS jobs; the 12 simulated Windows and Linux pairs are identical.
- **Step 1,** with the `native-mac` arguments: arm64 Release, arm64 Debug, and x86_64 Release
  (`-DCMAKE_OSX_ARCHITECTURES=x86_64`). `nm -gU` 501, 557 and 519 names, each list equal to the C baseline's;
  `otool -L` equal; avf imports 13, equal; N_OSO 0, 90 and 0; minos 11.0; no `__R` or `_rust_` export; 529,392,
  1,841,072 and 528,088 bytes.
- **Step 3,** against the Maven tree's Release build: `58 names in the header, 501 exported`, both diffs empty, no
  N_OSO, nothing written into the tree. It ran on the library that step 4 then found up to date.
- **Steps 4 to 7:** jslc 437 / 0 / 0 / 0, base 5,510 / 0 / 0 / 22, graphics 26,696 / 0 / 0 / 858 and media 41 / 0 /
  0 / 6 in each; in steps 4, 6 and 7 the cargo tests (14 passed) and the lint pass.
  - Step 4 did not relink `jfxmedia`: the tree was up to date.
  - Step 5 removed the origin log, map and list at configure; the library is 206,032 bytes, its 501 names and
    `otool -L` equal the baseline's, and `link.txt` has no Rust item.
  - Step 6: 1,835,744 bytes, 557 names and `otool -L` equal to the Debug baseline's, avf imports 13, N_OSO 90, check
    (d) run; the origin log names a `…/debug/libjfxmedia.a(…cgu.09.rcgu.o)` member.
  - Step 7 relinked: 529,392 bytes, 501 names equal to the baseline's, `otool -L` equal in order, minos 11.0, N_OSO
    0, avf imports 13.

The gate has the skipped tests, the lint lines and the final library.

### Reviews and analysis
- **Measurements before the design** (toy links and hand relinks on this Mac): ld64's `-u`, its precedence of a C
  object over an archive member (silent, and the archive is not loaded), its `-map` format, both export lists,
  `native-static-libs`, std's debug map and `-S`, the deployment target, `nm --no-llvm-bc`; the Java side (Java binds
  only the 58 header names, so no Java or pom change is needed); the licence gate and the std components linked on
  Darwin; the runner images. They chose the unexported list and rejected the story's exported list.
- **Review of the three build files** (`jfx_rust.cmake`, `mac.cmake`, `rust-toolchain.toml`): no blocker, no major.
  - M1, minor, confirmed in a toy project: a `-framework <Name>` that rustc prints would be split in two and fail the
    link. Fixed: on macOS a framework pair stays one item, and an unknown item fails the configure.
  - N1: the claim "commas in paths are safe" was false; a comma in the build-tree path already breaks a C-only link
    (its rpath), so nothing new breaks. No code change.
  - N2: the header comment gave the cargo directory without its condition. Fixed ("if set").
  - N3: the missing-nm error named no remedy. Fixed (`-DCMAKE_NM=<nm>` or `-DJFX_RUST=false`).
  - N4: a comment restated the code. Removed.
  - N5: old `cargo/macos-<value>` directories pile up. Accepted (disk only).
- **Independent verification** (the matrix, the Mach-O dump, the controls): criterion 2 holds on both architectures.
  - D-1, low: after a toggle to `-DJFX_RUST=false` the origin log, map and list stayed and still named the archive.
    Fixed: the configure removes them.
  - D-2, a design limit: the list hides every name the archive defines, so a C export of the same name (a builtin
    such as `___udivti3`, `_fmaximum` or `_roundeven`) would vanish from `nm -gU` on macOS only. Fixed by check (d),
    which fails such a link; its limit is under "Accepted limits".
  - D-3, cosmetic: a blank indented line in the leak message, and the `#ifndef` remedy shown for another archive.
    Fixed.
  - Surprises, recorded above or below: `cargo test` cannot run in an x86_64 tree here (S-1); cargo copies the
    archive again on each build with the same time and bytes, harmless for make (S-2); Apple's nm crashes on a
    truncated archive, which the listing step reports (S-3); the Debug build imports dead std code (S-4); Release
    links rustc-demangle, gimli and addr2line too, which `rust-std.md` now labels (S-5).
- **The fix round** made the seven fixes above, in `jfx_rust.cmake` only, and ran the final gates.
- **Review of the CI change** (`submit.yml`, `README.md`, `rust-std.md`): mergeable, one nit. The macOS check compares
  with a fresh C build, so the change that marks a jfxmedia slice `C_DELETED` makes both macOS jobs fail in this step.
  Fixed in the comment of both steps; the fix reproduced the failure with a copy of `mac.cmake` that marks the slice
  (the gate). The committed baseline that will replace the C build belongs to that
  change.

### Not verified, not done
- **x86_64 code** never ran: this Mac has no Rosetta. `cargo test`, the Java tests and the library itself run on x86_64
  only in the `macos-15-intel` job. No Intel host, where rustc's host is x86_64, was used. The x86_64 Debug pair
  was not rebuilt with the final helper.
- **The CI runners:** Xcode 16.4's ld and nm (the `-map` format, `-unexported_symbols_list`, `-S`, `nm --no-llvm-bc
  -P`, the output that the CI step parses from `nm -gUj`, `nm -a` and `otool -L`) and their linking of rustc 1.100.0's
  LLVM 23 bitcode; whether rustup is on `PATH`, and whether `rustup toolchain install` adds both targets with rustup
  1.29.0, which the `macos-15-intel` image lists (the `macos-15` image lists 1.29.3, the version measured here); the
  `curl` fallback; `cargo install` of cbindgen, which neither image lists; the cache save and restore; the time of the
  step's extra C build on 3- or 4-core runners. None of the six jobs has run with this change.
- **Real Windows and Linux builds:** only simulated on this Mac, with a Darwin rustc and Unix Makefiles.
- **A plain `cargo` or `rustc` call** in the repository root on Windows or in WSL, outside the helper: it now meets
  the two Darwin targets of the pin and may try to install them, which offline may fail.
- **Multi-config generators** (Xcode, Ninja Multi-Config): the per-configuration map, list and archive, the
  `PRE_LINK` step and the multi-config file names that the configure removes were never built. `-G Xcode` failed to
  configure here (`No CMAKE_C_COMPILER could be found`), and there is no ninja.
- **RelWithDebInfo, MinSizeRel and the root pom's DebugNative** were not built; by reading they get no `-S`, so their
  library carries the debug map with the archive's absolute path.
- **The configure without cargo or rustc** was not run on macOS (above), nor two slices or two crates on one target.
- **A Maven run of the unchanged tree** on this Mac; `mvn install` (every gate ran `verify`).
- **Robot and `tests/system` runs:** none; no test there is touched.

### Accepted limits
- **Check (d)** cannot see a C thread-local variable whose descriptor the map gives to "linker synthesized", as it
  skips those for std's. Theoretical today.
- **A C reference to a name the archive defines** (a builtin such as `_fmaximum`) would bind to the archive's member
  instead of libSystem, and no check sees it. Today the Rust builds remove no import of the C build (0 in all four
  pairs).
- **Another archive** that defines the export trips checks (a) and (d), and (d)'s "A C symbol…" text is wrong for that
  artificial case.
- **Old cargo directories** (`cargo/macos-<value>`) stay on disk after the deployment target changes.
- **Linux** keeps a stale `jfx-rust/<target>.map` after a toggle to `-DJFX_RUST=false`; only its origin log is
  removed. Older than this story.
- **A comma** in the build-tree path splits a `LINKER:` option; a C-only link with such a path already fails.
- **`jfxwebkit`** links with an `-exported_symbols_list`, which ld64 refuses together with an unexported list. It has
  no slice, and the fork does not build it.
- **The member alternation** of the origin check fails to compile above about 6,000 to 7,000 loaded archive members,
  loudly; the Debug build loads 66.

### Follow-up
- **US-035 and US-036,** whose deletion slices delete jfxmedia's C++: C++ that defines a name that
  `libjfxmedia_avf.dylib` imports (13 today, listed in the
  gate), or that jfxmedia's own macOS sources reference
  (`platform/osx/OSXPlatform.mm`, `OSXMediaPlayer.mm`, `CVVideoFrame.mm`), cannot leave the macOS build while they
  reference it, and no story ports the AVF layer or those three files. The change that marks a jfxmedia slice
  `C_DELETED` must also give the macOS CI check a baseline other than the C build, which that change can no longer
  build.
- **The compiler-builtins entry of `rust-std.md`,** older than this story and now labelled "Linux and macOS": this
  toolchain's COPYRIGHT files do not tie the expression `Apache-2.0 WITH LLVM-exception AND (Apache-2.0 OR MIT)` to
  compiler-builtins, that expression is outside the licences P8 allows, and no maintainer sign-off for it is recorded.
  The macOS Debug builds link compiler_builtins members. The expressions of addr2line 0.27.1 and memchr 2.8.3 are not
  listed in those files either (US-027 recorded the same).

### Left for the owner
- **One commit** of the six files with this write-up and the files of the separate documentation step, and the PR.
  A draft of the PR description is kept outside the repository. The `.claude` skill and agent files are git-ignored
  (`.gitignore:80`), so the commit does not carry them.
- **A green `submit.yml` run** in all six jobs. In both macOS jobs, the first with Rust: rustup found or fetched,
  `rustup toolchain install` adding the std of both Darwin targets, cbindgen built by `cargo install`, `Cache saved
  with key: rust-macOS-<X64|ARM64>-…`, the origin log line, and the check printing `58 names in the header, <n>
  exported`, with `<n>` the export count of the C build made in the job (501 and 519 with Xcode 27.1 here), with
  empty diffs and no N_OSO; the x86_64 cargo tests and media tests passing natively. In the Linux and Windows jobs:
  a new cache key and the two Darwin std packages in `rustup toolchain install`.
- **The confirmation of the deviations:** the unexported list, no `native-static-libs`, the cost of the `targets`
  pin, and the Mach-O differences of the Rust-linked library (Release and Debug).
- After the merge: the CI record here and in `backlog/README.md`, the Done status, and the row moved to the done table.

### Definition of Done
- The PR is merged and both macOS CI jobs are green: open, for the owner.
- The PR states that no local macOS run was possible: replaced, since the premise is false; the PR draft states what
  ran on this Mac (arm64 built, linked and tested; x86_64 built and compared) and what only CI shows.
- `backlog/README.md` is updated: yes (the US-027 and US-028 rows, the rows of US-032, US-033, US-035 and US-036,
  R3, P2, P9 and "Order").
