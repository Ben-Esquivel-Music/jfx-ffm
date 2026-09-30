# US-027 — Add a Rust toolchain to the native build and CI

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey of both native CMake projects, both module poms,
`sdk/pom.xml`, `submit.yml`, `build-webkit.yml`, `.jcheck/conf` and the seven C ABI headers; not checked: which runner
images ship rustup, anything on macOS; nothing built, because no Rust toolchain is installed on the development
machine) · **Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:** none · **Blocks:** every Rust
port story

## Story
As a platform maintainer,
I want the Maven/CMake native build to compile a Rust crate into an existing JavaFX native library, export its C ABI
symbols, and test, lint and licence-check it, locally and in CI on Windows and Linux,
so that each port story can move one function group from C to Rust behind the unchanged `*_api.h` header without
re-solving the build.

## Why this story exists
The tree has no Rust: `git ls-files '*.rs' '*Cargo.toml'` lists 0 files. Every port story assumes a `staticlib`
linked into the existing CMake target that exports exactly the header's symbols. Today there is no cargo step, no way
to export non-C symbols from a static archive, and no licence gate for crates. This story delivers that capability
and proves it by moving one real export into Rust, not by adding a hello-world crate.

## Current state
- **Build wiring.** Profile `native-win` in `modules/javafx.graphics/pom.xml:688-754` is active unless
  `skipNative=true` (`:695-698`). It runs `exec-maven-plugin` `cmake-configure` and `cmake-build --config ${CONF}
  --parallel` in `process-classes` (`:707-748`). The `native-linux` (`:757`) and `native-mac` (`:819`) profiles work
  the same way, and the media pom mirrors all three (`:210-217`, `:274`, `:335`). Libraries go to `target/native/bin`
  (`:724`), and `sdk/pom.xml:204-207` ships only its `*.dll,*.so,*.dylib`. Graphics uses `add_jfx_library`, which
  globs `SOURCE_DIRS` (`win.cmake:113-167`, `linux.cmake:102-130`). Media uses `add_media_library`, which takes
  explicit `SOURCES` (media `win.cmake:151-202`, `linux.cmake:130-148`).
- **Settings a staticlib must match.**
  - MSVC runtime: `/MD` in Release, `/MDd` in Debug (graphics `CMakeLists.txt:31-32`, media `:41-42`).
  - None of `/GL`, LTCG, `-flto`, IPO, `--no-undefined` or a version script appears in either `native/` tree (swept).
  - Windows links with `/opt:REF` (`win.cmake:75`; media adds `/opt:icf`, `:77`), writes a `/map` (`:165`) and
    writes no PDB in Release (`:76-77`).
  - Linux compiles and links with `-fPIC`, `-static-libgcc -static-libstdc++`, `-z,relro`, `--gc-sections` and
    `LINKER_LANGUAGE CXX` (`linux.cmake:66-89,119`; media `:88-106,137`). Only `iio` uses `-fvisibility=hidden`
    (`:242`). Release has no `-g` (`:76-77`) and the `.so` stays unstripped (`:33-35`).
- **Exports.**
  - Each ABI header defines `<LIB>_EXPORT` as `__declspec(dllexport)` or `visibility("default")` inside
    `extern "C"` (e.g. `native-iio/iio_api.h:114-122`). Only `glib-lite` and `gstreamer-lite` use `.def` files
    (media `win.cmake:420,681`).
  - In all seven headers each export is one line that starts with the macro and names the function (iio 9, prism_sw
    22, prism_d3d 59, prism_es2 77, glass_win 106, glass_gtk 64, jfxmedia 58; no data exports), so the export list
    can be parsed from the header.
  - Each header has an ABI-version function that the facade binds first (`iio_api.h:221`, `prism_sw_api.h:130`,
    `prism_d3d_api.h:160`, `prism_es2_api.h:167`, `glass_win_api.h:232`, `glass_gtk_api.h:155`,
    `jfxmedia_api.h:152`; tested e.g. in `JPEGNativeTest.java:94,106-108`).
  - Export dumps exist only as CI steps (`build-webkit.yml:533-549`; `submit.yml:162,264,331,398`); `buildtools/`,
    `tests/` and `modules/*/src/test` have none. A post-link `cmake -P` assertion is already a precedent
    (`check-one-dllmain.cmake`, run from media `win.cmake:696`).
- **CI.** `submit.yml` runs `mvn -B -ntp -fae install` on `ubuntu-24.04` (`:64`), `ubuntu-24.04-arm` (`:166`),
  `macos-15-intel` (`:268`), `macos-15` (`:335`) and `windows-2022` (`:402`) with preinstalled toolchains
  (`:30-31`). Actions are pinned by SHA with a version comment (`:73`). The Windows steps run in PowerShell with no
  vcvars (`:415-446`). `build-webkit.yml` runs no Maven.
- **Conventions.** `.jcheck/conf:41` whitespace-checks C, C++, Java and more, but not `.rs` or `.toml`. `:33` makes
  `executable` an error and `:34` makes `binary` a warning. `.gitattributes` is `* -text`. Third-party notices live in
  `modules/*/src/main/legal/*.md`, including one for the statically linked GCC runtime (`graphics/.../legal/gcc.md`).

## Approach
1. **Staticlib in the existing target.** Each shared library has one crate, `crate-type = ["staticlib", "rlib"]`, in
   `modules/<module>/src/main/native-rust/<crate>/` (outside every `SOURCE_DIRS` glob). A library never links two
   staticlibs, since each carries std. Rejected: a `cdylib`, which cannot be part C and part Rust without a second DLL.
2. **Cargo from CMake: a custom target and an IMPORTED STATIC library, not Corrosion.**
   - `buildtools/rust/jfx_rust.cmake` is included by both `native/CMakeLists.txt`. Media already uses graphics'
     `version.rc` (`CMakeLists.txt:78`), so sharing across modules has a precedent.
   - It provides `jfx_add_rust_slice(<target> CRATE <dir> HEADER <h> EXPORTS <sym>...)`. That call adds an
     always-run `cargo build --locked --offline -p <crate>` step. `CONF` picks the release or dev profile through
     generator expressions, which the tree's CMake 3.20 floor allows (`CMakeLists.txt:29`).
   - `CARGO_TARGET_DIR` is `<cmake tree>/cargo`: ignored by git (`.gitignore:8`), removed by `mvn clean`, never in
     `BIN_DIR`.
   - At configure time the helper builds an empty staticlib with `rustc --print native-static-libs` and links the
     printed list. On Linux it drops `-lgcc_s`, because `-static-libgcc` already supplies the unwinder.
   - Corrosion was rejected: it is third-party CMake that must be vendored or fetched over the network, and it would
     add cross-compile machinery that native-host builds do not need.
3. **Exports.**
   - **Windows:** one `/EXPORT:<sym>` per Rust symbol. rustc does not dllexport a staticlib's `#[no_mangle]`
     symbols, and since no C code calls them, `/EXPORT:` is also what makes link.exe pull them out of the archive.
     The C symbols keep `__declspec(dllexport)`. A `.def` file was rejected because it would duplicate the C export
     list.
   - **Linux:** one `-Wl,--undefined=<sym>` per Rust symbol, plus a version script generated from the header,
     `{ global: <all header exports>; local: *; };`. The script also hides Rust std. It is applied whenever the
     target has a slice, with Rust on or off, so both builds export the same list.
   - **Checks and scope:** a Rust export that the header does not declare fails configure. macOS is out of scope:
     the helper errors on `APPLE` unless that slice is off there (Risk 2).
4. **C/Rust switch.**
   - A root pom property `JFX_RUST` (default `true`) is passed as `-DJFX_RUST=${JFX_RUST}` by every
     `cmake-configure`, like `INCLUDE_ES2`.
   - With it on, the helper defines `JFX_RUST_<SLICE>=1` and the C definition is compiled out by an `#ifndef`.
   - Cargo is looked for only when a target has a slice and `JFX_RUST` is on. If cargo is missing, or rustc is not
     the pinned version (a distro cargo ignores `rust-toolchain.toml`), configure fails. The error names the
     library, the pinned version, rustup and `-DJFX_RUST=false`.
   - Once a slice's C is deleted, `-DJFX_RUST=false` is a configure error for that library.
5. **Toolchain and reproducibility.**
   - A root `rust-toolchain.toml` pins `channel = "1.NN.0"`, the current stable when this is implemented (never
     `stable`), plus `components = ["clippy", "rustfmt"]` and `profile = "minimal"`.
   - A root virtual workspace (`Cargo.toml`, edition 2024, members `modules/*/src/main/native-rust/*`) has one
     committed `Cargo.lock`. Every cargo run uses `--locked --offline`.
   - Crates are vendored with `cargo vendor` into `buildtools/rust/vendor/` and wired in through `.cargo/config.toml`,
     starting with the first port story that needs one. The proof slice needs none.
   - **Profiles.** `panic` keeps Cargo's default, `"unwind"`, because every Rust candidate in the survey is called
     back by foreign code (COM, WNDPROCs, GLib, GStreamer, PipeWire) or converts C++ exceptions at its ABI.
     - Release uses `opt-level = 3`; a package may override it, e.g. `"s"` to mirror media's `-Os`,
       `linux.cmake:92`.
     - Release also uses `debug = false` like the C Release build, `lto = true` and `codegen-units = 1`.
     - Dev uses `debug = true`, so the Debug DLL's PDB covers the Rust code.
   - **The support crate.** A workspace crate, `jfx-rust-support`, is linked into each library's staticlib as an
     rlib. It provides the one guard every export and every foreign-called entry point uses (port rule P4).
     - The guard runs the body under `catch_unwind` and returns the caller-supplied C failure value.
     - It poisons the handle the panic ran on, so every later call on that handle returns the failure value.
     - It writes one line to stderr.
6. **ABI conformance at compile time.**
   - The lint step compiles `abi_check.cpp` against the hand-written header plus two cbindgen outputs. cbindgen is
     a pinned build-time tool and is not shipped.
   - A functions-only cbindgen run uses the header's type names, so every Rust prototype must match the header's.
   - A types-and-constants run uses an `rs_` prefix and is checked with `static_assert` on the ABI version, `sizeof`
     and each `offsetof`. The slice that moves a struct writes that struct's asserts.
   - The file is compiled as C++, where a mismatched `extern "C"` redeclaration is an error on both MSVC (C2733) and
     GCC; MSVC's C mode only warns (C4028). The Java `StructLayout` and binding tests stay the runtime contract.
7. **Tests and lint in Maven.** Two new executions in each module's three native profiles, so `-DskipNative=true`
   skips them. In the `test` phase, `cmake --build <tree> [--config ${CONF}] --target jfx_rust_test` runs
   `cargo test`, with `<skip>${skipTests}</skip>` (the first `<skip>` in these poms). In `verify`, `jfx_rust_lint` runs
   `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, the ABI check and the licence check. Both do
   nothing on a platform with no enabled slice, and CI's `mvn install` runs both.
   - Test-only native programs, such as the GStreamer trace drivers of US-032 and US-035, are CMake targets of the
     same tree. They are kept out of `BIN_DIR` and the SDK, and are built and run by the same `test` execution.
8. **Conventions.** `.rs` files start with the C sources' GPLv2 + Classpath block comment, and `.toml` files use the
   `#` form. `Cargo.lock` and vendored crates stay as generated or as upstream ships them. `rustfmt.toml` sets
   `max_width = 120`, `newline_style = "Unix"` (the tree is `* -text`) and `hard_tabs = false`. `.*\.rs$` joins
   `.jcheck/conf:41`, with the vendor directory excluded.
9. **Licence gate.** `buildtools/rust/check-crate-licences.pl` uses perl with core `JSON::PP`, since there is no
   python or node here. It reads `cargo metadata --locked --offline --format-version 1` and follows the normal
   (linked) dependencies of each shipped crate.
   - Each SPDX expression must allow MIT, BSD-2-Clause, BSD-3-Clause, ISC, Zlib or Unicode-3.0. An `OR` with one of
     them passes, and the notice names the option taken.
   - Apache-2.0-only, GPLv3, LGPLv3, AGPL and unknown licences fail. MPL-2.0 and LGPL-2.1 also fail, unless
     `rust-crates.md` records the maintainer's sign-off for that crate and version.
   - Proc macros and build dependencies are listed but not gated.
   - A linked crate missing (name and version) from `modules/<module>/src/main/legal/rust-crates.md` also fails.
   - Rust std is not in `cargo metadata`, so each module that ships Rust adds `legal/rust-std.md` by hand, modelled on
     `gcc.md`.
   - Rejected: `cargo-deny`. It is one more tool to install, and it cannot be vendored into an offline build.
10. **Setup.** On Windows 10 + VS2022, run `rustup-init.exe` (default host `x86_64-pc-windows-msvc`); on WSL
    Ubuntu, run the `sh.rustup.rs` installer. Then, in the repo root, run `rustup toolchain install` and
    `cargo install --locked cbindgen@<pin>`. In CI, one step before the build in `windows_x64_build`,
    `linux_x64_build` and `linux_aarch64_build` runs the same commands plus `rustup show active-toolchain`, in the
    job's own shell. On Windows that is PowerShell, never `shell: bash`, whose Git `link.exe` shadows MSVC's
    (`build-webkit.yml:320-343`). No new action is added; if one ever is, pin it like `submit.yml:73`. The macOS jobs
    are unchanged.

### Slices
1. **Toolchain files and helper, no library changed.** Adds the workspace files, the helper, the licence script, the
   `JFX_RUST` pass-through, the test and lint executions, the jcheck line and a Rust paragraph under "Building" in
   `README.md`. Gate: `mvn install` and `-DskipNative=true` behave unchanged on a machine without Rust.
2. **Proof slice: one real export into Rust.** The library is `jfxmedia`, a Rust target on both Windows and Linux
   (US-035, US-036).
   - Its ABI-version function `jfxm_abi_version` (`jfxmedia_api.h:152`) becomes a
     `#[unsafe(no_mangle)] pub extern "C" fn` that returns a `pub const`.
   - The ABI check asserts that constant against `JFXM_ABI_VERSION`, and the C definition goes under `#ifndef`.
   - `jfxmedia` is also built on macOS, so the slice stays off there until US-028.
3. **CI.** Adds the Rust setup step to the three jobs, and the dumps below to their post-build checks, in the style of
   `submit.yml:162`.

## Acceptance criteria
- Before slice 2, `mvn install` works unchanged without Rust. After it, configure fails with the Approach 4 message,
  and `-DJFX_RUST=false` builds the C path.
- `-DskipNative=true` runs no CMake, no cargo and no Rust test or lint.
- **Windows:** `dumpbin /EXPORTS` names and `dumpbin /DEPENDENTS` are identical to the C build.
- **Linux:**
  - `nm -D --defined-only` shows every header export and no `_ZN` or `_R` Rust symbol.
  - The PR lists any C symbol the version script now hides. That is the rule for every later slice: a library with
    no header, such as `fxplugins`, keeps every symbol a consumer resolves (`gst_plugin_desc`), and internal symbols
    that were exported only through default visibility may disappear, listed in the PR.
  - `readelf -d` `NEEDED` is identical, and `ldd -r` shows no undefined symbol that the C build did not have.
- `JfxMediaNativeTest` is unchanged and passes, including the ABI-version check.
- `cargo fmt --check`, clippy with `-D warnings`, `cargo test`, the ABI check and the licence check all pass inside
  `mvn install`. The PR also shows once that a mutant with a changed parameter type fails the ABI check.
- A `-DCONF=Debug` Windows build links without LNK4098, and its tests pass.
- All five CI jobs are green, the macOS jobs build without Rust, and `build-webkit.yml` is untouched.

## Definition of Done
The PR is merged. It was verified on Windows 10 + VS2022 and on WSL Ubuntu (a clean `mvn install` with Rust on and
off) and in CI, and the dumps are pasted into it. `backlog/README.md` is updated, and US-028 records the macOS
linking facts this story learned.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Rust std uses the release CRT but C Debug builds use `/MDd` (LNK4098, two heaps) | Debug links of targets with a Rust slice get `/NODEFAULTLIB:` for the libraries LNK4098 names; an acceptance criterion covers it |
| 2 | macOS never builds Rust in this story, so C in cross-platform libraries cannot be deleted | US-028 (macOS linking with `-exported_symbols_list` and `-u`), verified in both macOS jobs |
| 3 | A runner image may lack rustup (assumed, not verified; least certain for the ARM image) | If `rustup` is missing, the CI step downloads `rustup-init` the way the boot JDK is fetched (`submit.yml:105-110`) |
| 4 | New clippy lints or rustfmt changes arrive with a toolchain update | The pin keeps them out; each bump is a separate commit |
| 5 | The version script hides C symbols that were exported by accident | The PR lists them; Java binds only symbols from the header |
| 6 | Vendored crates bring binaries or tabs | The vendor directory is excluded from the jcheck regex; binaries only raise warnings (`.jcheck/conf:34`) |
| 7 | `--parallel` builds contend for one `CARGO_TARGET_DIR` | Cargo's build lock serializes them; each library has only one small staticlib |

