# US-027 — Add a Rust toolchain to the native build and CI

**Status:** 🚧 Implemented, not merged (2026-11-16): uncommitted on `7a66c85e39`; the final gate passed on Windows 10
and WSL Ubuntu (see "Implementation" below). Open: the commit, the PR with the dumps, a green `submit.yml` run of the
five build jobs, and the owner's confirmation of two accepted deviations (Windows dependents, Linux CET marking).
Drafted 2026-09-30 from a read-only survey of both native CMake projects, both module poms, `sdk/pom.xml`,
`submit.yml`, `build-webkit.yml`, `.jcheck/conf` and the seven C ABI headers · **Epic:** Rust port of the remaining
native code (goal 3) · **Blocked by:** none · **Blocks:** every Rust port story

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
    (`:234`). Release has no `-g` (`:76-77`) and the `.so` stays unstripped (`:33-35`).
- **Exports.**
  - Each ABI header defines `<LIB>_EXPORT` as `__declspec(dllexport)` or `visibility("default")` inside
    `extern "C"` (e.g. `native-iio/iio_api.h:114-122`). Only `glib-lite` and `gstreamer-lite` use `.def` files
    (media `win.cmake:420,681`).
  - In all seven headers (six since US-046 deleted `prism_sw_api.h`) each export is one line that starts with the
    macro and names the function (iio 9, prism_sw 22, prism_d3d 59, prism_es2 77, glass_win 106, glass_gtk 64,
    jfxmedia 58; no data exports), so the export list can be parsed from the header.
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

## Implementation (2026-11-16, uncommitted)

The work sits uncommitted on `7a66c85e39`: the change that was gated is 11 modified files and 7 new entries (listed
below); this write-up adds changes to `backlog/README.md`, this story, US-028, US-032 and US-034, and the new files
`backlog/US-027-linux-hidden-symbols.txt` and US-121. "Build tree" means a module's `target/native/cmake`; the media one
is `modules/javafx.media/target/native/cmake`. Slices 1 to 3 of the story were built together and gated together;
slice 1 was not gated on its own.

### What changed

**Toolchain pins and workspace** (repository root, new).
- `rust-toolchain.toml`: `channel = "1.100.0"`, `components = ["clippy", "rustfmt"]`, `profile = "minimal"`.
- `Cargo.toml`: a virtual workspace, `resolver = "3"`, members `modules/*/src/main/native-rust/*` and
  `buildtools/rust/jfx-rust-support`. `[workspace.package]`: edition 2024, `rust-version = "1.100"`,
  `license = "GPL-2.0-only WITH Classpath-exception-2.0"`, `publish = false`. Workspace lints: rust
  `unsafe_op_in_unsafe_fn = "deny"`, clippy `undocumented_unsafe_blocks = "warn"` (an error under `-D warnings`).
  `[profile.release]`: `opt-level = 3`, `debug = false`, `lto = true`, `codegen-units = 1`; `[profile.dev]`:
  `debug = true`. `panic` is not set, so it is Cargo's `unwind`.
- `Cargo.lock`: written by cargo (format 4), the two own crates only. `rustfmt.toml`: `max_width = 120`,
  `newline_style = "Unix"`, `hard_tabs = false`.
- No crate is vendored: nothing third-party is linked, so there is no `buildtools/rust/vendor/`, no
  `.cargo/config.toml` and no `rust-crates.md` yet. The first story that links a third-party crate adds them.

**The support crate and its panic guard** (`buildtools/rust/jfx-rust-support/`, rlib only, no dependency, no
`unsafe`).
- `Poison` (an `AtomicBool`, `const fn new()`, derives `Debug` and `Default`, which clippy's `new_without_default`
  requires), `guard(failure, body)` for an export without a handle and `guard_handle(&poison, failure, body)` for one
  that runs on a handle. Both take `R: Copy`: the value crosses the C ABI, and a `Copy` type has no `Drop` that could
  panic outside the guard.
- The body runs under `catch_unwind`. A panic returns `failure`; `guard_handle` also poisons the handle, and a call on
  a poisoned handle returns `failure` without running its body. A panic payload, and the body that a poisoned call
  does not run, are dropped under `catch_unwind` too, so a `Drop` that panics cannot leave the guard.
- The first guarded call installs a panic hook (`std::sync::Once`) that writes exactly one line to stderr,
  `jfx-rust: panic at <file>:<line>:<column>: <message>`; line breaks in the message are written as `\n` and `\r`,
  and a payload that is not a string prints `Box<dyn Any>`, as std's own hook does. No hook is installed on a
  panicking thread (`set_hook` would panic there); a later call installs it. The hook belongs to the library's own
  copy of std.

**The jfxmedia crate and the proof slice** (`modules/javafx.media/src/main/native-rust/jfxmedia/`).
- `crate-type = ["staticlib", "rlib"]`, depending on `jfx-rust-support`. `src/lib.rs` holds
  `pub const JFXM_ABI_VERSION: u32 = 4;`, `src/ffi.rs` the export `#[unsafe(no_mangle)] pub extern "C" fn
  jfxm_abi_version() -> u32`, which returns the constant through `jfx_rust_support::guard(0, ...)`. 0 is the failure
  value: no ABI version is 0, so Java's version check reports a mismatch.
- `abi_check.cpp`, `cbindgen-functions.toml` and `cbindgen-types.toml`: the ABI check (below).
- `ffi/jfxmedia_api.cpp`: the C definition of `jfxm_abi_version` is under `#ifndef JFX_RUST_JFXMEDIA_ABI_VERSION`.
  Nothing else in the C/C++ changed; `jfxmedia_api.h` is unchanged (`JFXM_ABI_VERSION` is `4u`).
- `native/win.cmake` and `native/linux.cmake` register the slice `JFXMEDIA_ABI_VERSION` (`EXPORTS jfxm_abi_version`)
  twice: for `jfxmedia` with `HEADERS jfxmedia_api.h` (58 exports) and for the hooks build `jfxmedia_test` with
  `jfxmedia_api.h` and `ffi/jfxmedia_test_hooks.h` (61). `native/mac.cmake` registers no slice and is unchanged.
- A crate's directory name is its package name: the helper passes the directory name to `cargo -p` and expects the
  archive cargo names after it (`jfxmedia.lib`, `libjfxmedia.a`; `-` becomes `_`).

**The CMake helper** `buildtools/rust/jfx_rust.cmake` (new, 789 lines), included by
`modules/javafx.graphics/native/CMakeLists.txt` and `modules/javafx.media/native/CMakeLists.txt` before the platform
file. The graphics tree registers no slice.
- Signature:
  ```
  jfx_add_rust_slice(<target>
      CRATE <crate dir>                 # modules/<module>/src/main/native-rust/<crate>
      SLICE <NAME>                      # JFX_RUST_<NAME>=1 is defined for <target> when JFX_RUST is on
      HEADERS <h>...                    # their <LIB>_EXPORT lines are the target's whole export list
      EXPORTS <sym>...                  # the symbols this slice implements in Rust
      [ABI_CHECK_INCLUDE_DIRS <dir>...] # what the header(s) need to compile in the ABI check
      [C_DELETED])                      # the slice's C is gone: JFX_RUST=OFF is then a configure error
  ```
- The cache option `JFX_RUST` (BOOL, default ON). The export list is every name declared on a header line that
  starts with an export macro; an `EXPORTS` name that no header declares is a configure error, and the headers (and,
  with `JFX_RUST` on, `rust-toolchain.toml`) are `CMAKE_CONFIGURE_DEPENDS`, so a header change configures again.
- **Linux exports, Rust on or off:** a version script generated into `<build tree>/jfx-rust/<target>.version`,
  `{ global: <the header names>; local: *; };` (anonymous: no symbol version is attached), linked with
  `--version-script`. With `JFX_RUST` off the C is built, no Rust tool is looked up, and the configure prints
  `slice JFXMEDIA_ABI_VERSION built from C (JFX_RUST=OFF), 58 exports` (61 for `jfxmedia_test`).
- **With `JFX_RUST` on:** the toolchain is looked up once per tree (below), `rustc --version`, run in the repository
  root, must give the channel parsed from `rust-toolchain.toml`, and `rustc --crate-type staticlib --print
  native-static-libs` on an empty file gives the platform libraries std needs. Windows links `kernel32.lib
  ntdll.lib userenv.lib ws2_32.lib dbghelp.lib synchronization.lib` and drops `/defaultlib:msvcrt` (the CRT stays
  the C build's choice); Linux links `-lutil -lrt -lpthread -lm -ldl -lc` and drops `-lgcc_s` (`-static-libgcc`
  supplies the unwinder). The lists are from the configure lines of the final gate.
- **The cargo step:** an always-run custom target `jfx_rust_<crate>_cargo` runs, in the repository root,
  `cargo rustc --locked --offline -p <crate> --lib --profile <dev|release> --crate-type staticlib` with
  `CARGO_TARGET_DIR=<build tree>/cargo`; the profile is `dev` for the `Debug` configuration (a generator expression
  on Visual Studio, `CMAKE_BUILD_TYPE` on Makefiles). Its archive is an IMPORTED STATIC library, created once per
  crate, so `jfxmedia` and `jfxmedia_test` share one cargo step and one archive. The target gets
  `JFX_RUST_<SLICE>=1`.
- **Exports:** Windows adds `/EXPORT:<sym>` per Rust symbol, which also pulls the member out of the archive (no C
  code calls it); the C exports keep `__declspec(dllexport)`. Linux adds `--require-defined=<sym>`, which pulls the
  member in and fails the link when nothing defines the name, and `--strip-debug` in every configuration but
  `Debug`.
- **The origin check:** both linkers take a C definition left in an object file over the archive member without a
  word. A `POST_BUILD` step of each target with an enabled slice reads the linker map (on Windows the target's own
  `/map:`; on Linux an added `-Map` with `--cref`) and fails the build unless every `EXPORTS` symbol is defined by a
  member of the crate's archive. Its verdict, success or failure text, is written to
  `<build tree>/jfx-rust/logs/<target>-origin.log`, for example
  `jfx_rust: jfxmedia: jfxm_abi_version is defined by
  jfxmedia:jfxmedia-b64b3012e4277a02.jfxmedia.302d1e7085676a31-cgu.0.rcgu.o` (Windows, final gate). A failure
  names the symbol, the target and the object that defines it instead, and tells to compile the C out with
  `#ifndef JFX_RUST_<SLICE>`.
- **Logs:** every cargo call (build, test, fmt, clippy) runs through the helper's script mode, which unsets
  `CARGO_BUILD_TARGET`, `RUSTFLAGS`, `CARGO_ENCODED_RUSTFLAGS`, `CARGO_BUILD_RUSTFLAGS` and `CARGO_BUILD_TARGET_DIR`,
  sets `RUSTUP_AUTO_INSTALL=0`, and writes stdout and stderr into one log file, `<build tree>/jfx-rust/logs/<step>.log`
  (`jfxmedia-build-release.log`, `jfxmedia-build-dev.log`, `test.log`, `fmt.log`, `clippy.log`). The log is printed
  when cargo ends: to stdout after a success, to stderr after a failure, followed by a short error naming the command.
  The configure probes and the licence script also run with `RUSTUP_AUTO_INSTALL=0`, so a missing pinned toolchain
  fails with the helper's message and nothing is downloaded.
- **Tool lookup** (configure: cargo and rustc; the lint: cbindgen; the licence script: cargo, for a hand run): `PATH`
  first, then `$CARGO_HOME/bin` when `CARGO_HOME` is set and not empty, else the home `.cargo/bin` (`%USERPROFILE%` on
  Windows), never both. A rustup proxy runs its tool with `$CARGO_HOME/bin` first on `PATH`, so a proxy found outside
  that directory fails only at build time (`could not execute process rustc -vV`, seen in WSL).
- **Targets `jfx_rust_test` and `jfx_rust_lint`,** defined in every tree after all slices are registered
  (`cmake_language(DEFER)`). `jfx_rust_test` runs `cargo test --locked --offline -p <each linked crate>
  -p jfx-rust-support`. `jfx_rust_lint` runs `cargo fmt --check` and `cargo clippy --locked --offline --all-targets
  ... -- -D warnings` for the same crates, the ABI check of each library crate, and the licence check. With no
  enabled slice both only print `<target>: no Rust slice is enabled in this tree` and need no Rust tool.
- **macOS:** `jfx_add_rust_slice` on an Apple host is a configure error while `JFX_RUST` is on (it names US-028 and
  `-DJFX_RUST=false`); with it off it only applies `C_DELETED` and returns. `mac.cmake` registers no slice, so a
  macOS build with the default `JFX_RUST=true` builds the C and needs no Rust tool.
- Everything generated goes to `<build tree>/jfx-rust` and `<build tree>/cargo`; nothing to the source tree or to
  `BIN_DIR`.

**The ABI check**, part of `jfx_rust_lint`.
- cbindgen is looked up when the lint runs; one that is missing or whose `--version` is not `cbindgen 0.29.4` fails
  the lint with a message naming `cargo install --locked cbindgen@0.29.4`.
- It runs twice on the crate's root source file `src/lib.rs` into `<build tree>/jfx-rust/<crate>/abi-check/`:
  `cbindgen-functions.toml` writes `jfxmedia_rs_functions.h` (the `extern "C"` functions under the header's type
  names), `cbindgen-types.toml` writes `jfxmedia_rs_types.h` (constants and `repr(C)` types prefixed `rs_`; a
  `pub const` comes out as `constexpr static const uint32_t rs_JFXM_ABI_VERSION = 4;`). Any cbindgen line that
  starts with `ERROR` fails the lint, since cbindgen skips an item it cannot translate and still exits 0.
- **Completeness:** the lint fails unless the functions cbindgen emitted are exactly the union of the `EXPORTS` of
  the crate's slices. A missing name is an export cbindgen cannot see (one made by a macro or a re-export); an extra
  one is a `#[no_mangle]` function that no slice exports. Today: `the jfxmedia crate declares exactly the 1 EXPORTS
  symbol(s) of its slices`.
- `abi_check.cpp` includes `jfxmedia_api.h` and both generated headers and is compiled, never linked, as C++14
  (an OBJECT library, `/W4 /WX` or `-Wall -Wextra -Werror`). In C++ a second `extern "C"` declaration with other
  parameters or another return type is an error on MSVC and GCC; MSVC's C mode would only warn (C4028). Two
  `static_assert`s: `rs_JFXM_ABI_VERSION == JFXM_ABI_VERSION`, and that the constant's type is `const uint32_t`.
- For a later slice that moves a struct: `sizeof` and `offsetof` asserts alone pass a field whose type changes to
  another of the same size, so add a `std::is_same<decltype(...)>` assert per scalar, pointer and array field.

**The licence gate** `buildtools/rust/check-crate-licences.pl` (new, 658 lines; perl with core `JSON::PP` only).
- Reads `cargo metadata --locked --offline --format-version 1` in the repository root (`--cargo <cargo>` names
  cargo, and the lint passes the one configure found; `--metadata <file>` reads saved JSON). Shipped crates are the
  workspace members with a `staticlib` target; for each it follows the normal dependencies of the resolve graph.
- A workspace member must carry exactly `GPL-2.0-only WITH Classpath-exception-2.0`. A third-party crate's SPDX
  expression must allow MIT, BSD-2-Clause, BSD-3-Clause, ISC, Zlib or Unicode-3.0 (for an `OR` the option taken is
  printed); MPL-2.0 and LGPL-2.1 pass only with a `Maintainer sign-off: <name> <version>` line for that exact version
  in the module's `src/main/legal/rust-crates.md`; anything else fails. A linked third-party crate needs a heading
  `## <name> <version>` there. A module with a shipped crate needs `src/main/legal/rust-std.md`, whose first `## `
  heading must end with the channel `rust-toolchain.toml` pins. Proc macros and build dependencies are listed, not
  gated.
- Exit 0 with a table, 1 with one line per violation, 2 when the metadata cannot be read or checked (no cargo,
  `cargo metadata` failing or not giving JSON, no `resolve` graph, an edge without `dep_kinds`), on a usage error, or
  when `rust-toolchain.toml` has no channel. `--self-test` runs 54 canned cases, each rule
  passing and failing. Today: `check-crate-licences: OK, 2 linked crate(s), 0 build-time crate(s)`.

**The legal notice** `modules/javafx.media/src/main/legal/rust-std.md` (new, 300 lines): the Rust standard library of
1.100.0, linked into `jfxmedia` on Windows and Linux, used under MIT, with the notices of the toolchain's own
`share/doc/rust/COPYRIGHT-library.html`, `COPYRIGHT.html` and `licenses/`: the MIT and Unicode-3.0 texts;
compiler-builtins (Linux) with its own expression `Apache-2.0 WITH LLVM-exception AND (Apache-2.0 OR MIT)`; and the
crates std links, rustc-demangle 0.1.28 (both systems) and addr2line 0.27.1, adler2, gimli 0.34.0, memchr 2.8.3,
miniz_oxide 0.9.1, object 0.39.1 (Linux). `buildtools/CheckLegalNotices.java` ships it in the javafx.media jar
(`META-INF/legal/rust-std.md`) and the SDK (`legal/javafx.media/rust-std.md`) with no build change.

**The poms.**
- Root `pom.xml`: the property `JFX_RUST` = `true`, next to `INCLUDE_ES2`.
- `modules/javafx.graphics/pom.xml` and `modules/javafx.media/pom.xml`: `-DJFX_RUST=${JFX_RUST}` in all six
  `cmake-configure` executions, and two `exec` executions in each of the six native profiles: `rust-test` (phase
  `test`, `cmake --build <build tree> [--config ${CONF}] --target jfx_rust_test`, `<skip>${skipTests}</skip>`) and
  `rust-lint` (phase `verify`, `--target jfx_rust_lint`, not skipped by `-DskipTests`). Windows passes
  `--config ${CONF}`, Linux and macOS do not. No pom defines `skipTests`; exec-maven-plugin 3.5.1's `skip` defaults
  to false, and runs with it unset, `-DskipTests`, `-DskipTests=true` and `-DskipTests=false` behaved as expected
  (Windows). In the `test` phase surefire runs first, then `rust-test`, so a failing Java test stops the build
  before `cargo test` unless `-fae` is given. `-DskipNative=true` switches the profiles, and with them every Rust
  step, off. `modules/javafx.web/pom.xml` and `sdk/pom.xml` are unchanged.

**CI** (`.github/workflows/submit.yml`; it stays CRLF).
- A step "Set up the Rust toolchain" before the build in `linux_x64_build`, `linux_aarch64_build` (bash) and
  `windows_x64_build` (PowerShell, placed before "Setup environment", whose saved `Path` the build step restores).
  When `rustup` is missing it downloads `rustup-init` the way the boot JDK is fetched (`wget` on Linux, `curl -fL` on
  Windows) and installs with `--profile minimal --default-toolchain none`; then, in the repository root,
  `rustup toolchain install`, `cargo install --locked cbindgen@0.29.4` and `rustup show active-toolchain`. The cargo
  bin directory goes to `GITHUB_PATH`. PowerShell checks `$LASTEXITCODE` after each native command.
- A step "Verify the exports of jfxmedia, which links Rust" after the build in the same three jobs
  (`if: ${{ !cancelled() }}`): it fails when the library was not built; Linux compares the `JFXM_EXPORT` names of
  `jfxmedia_api.h` with `nm -D --defined-only` of `libjfxmedia.so` for exact equality, prints `readelf -d` `NEEDED`,
  and fails when `ldd -r` reports an undefined symbol or a missing library; Windows finds `dumpbin` through
  `vswhere`, prints `/DEPENDENTS`, and fails on a header name the DLL does not export and on an export the header
  does not declare. The header comment of the workflow names both steps. The macOS jobs, `no_jni_check` and
  `build-webkit.yml` are unchanged.

**Other files.** `.jcheck/conf`: the whitespace `files=` expression is now
`^(?!buildtools/rust/vendor/)(?:<the 17 old alternatives>|.*\.rs$)`, so `.rs` files are checked and nothing under a
future vendor directory is, whatever its type (checked with `java.util.regex`, `matches()` and `find()`, on 27 sample
paths). `README.md`, under "Building": one paragraph on what is built in Rust, the pin, the one-time setup, what the
`test` and `verify` phases run, `-DJFX_RUST=false`, `-DskipNative=true` and that macOS builds no Rust yet.

**Deviations from the story's text**, each checked against the code.
- **The helper's signature** has `SLICE` and a plural `HEADERS`, `ABI_CHECK_INCLUDE_DIRS` and `C_DELETED`, where the
  story has `HEADER`: `JFX_RUST_<SLICE>` needs a name, the hooks build `jfxmedia_test` (added by US-040, after this
  story was written) exports the names of two headers, and the header of a later slice may need include paths.
- **`--require-defined=<sym>` instead of `--undefined=<sym>` on Linux:** with `--undefined` an `EXPORTS` name that
  nothing defines links silently; with `--require-defined` the link fails, as it does on Windows.
- **The build step is `cargo rustc ... --crate-type staticlib`, not `cargo build`:** with
  `crate-type = ["staticlib", "rlib"]` in one rustc call cargo passes no `-C lto`, so the profile's `lto = true`
  never applied. Measured: release archive 13,114,348 to 4,007,116 bytes on Windows, 23,669,154 to 8,105,518 on
  Linux; `jfxmedia.dll` 244,736 to 229,888 bytes.
- **`--strip-debug` on Linux outside `Debug`** (not in the story): the prebuilt std objects carry `.debug_*` sections
  (`libjfxmedia.so` grew from 557,472 to 4,705,776 bytes with 6 of them), cargo's `strip` setting does not reach a
  staticlib, and the C Release build has none. The symbol table stays.
- **No `/NODEFAULTLIB` in a Windows Debug link** (Risk 1): the archive's objects carry no `/DEFAULTLIB` directive, and
  the helper drops `/defaultlib:msvcrt` from rustc's list, so a Debug link names no `msvcrt.lib` and gives no
  LNK4098 (0 in the final Debug gate).
- **cbindgen runs on `src/lib.rs`, not on the crate directory:** in directory mode cbindgen 0.29.4 runs
  `cargo metadata` itself, without `--offline` and `--locked`.
- **perl on Windows:** with no perl on `PATH` the helper takes the perl in `usr/bin` of the Git for Windows that the
  `git` on `PATH` belongs to, so a build from PowerShell or an IDE finds one; with none at all the lint fails, naming
  both remedies.
- **The gates ran `mvn verify`, not `mvn install`:** `install` writes to the local repository, which this machine's
  rules allow only when the owner asks; the lint is bound to `verify`, which `install` includes.
- **A failing cargo step prints its log to stderr,** followed by the error: printed to stdout, its first lines came
  out interleaved character by character with the error (US-121).
- **cargo's live progress is lost:** each step's output appears when cargo ends. Follow a running step with
  `tail -f <build tree>/jfx-rust/logs/<step>.log`.
- **Added, not in the story:** the origin check and its log `<build tree>/jfx-rust/logs/<target>-origin.log`; the
  completeness check of the ABI check and its failure on cbindgen `ERROR` lines; `RUSTUP_AUTO_INSTALL=0` and the five
  unset cargo variables; the tool lookup rule (never both directories, for the reason above); the extra
  `static_assert` on the constant's type; the licence script's exact-version sign-off, its exit 2 and its check of
  the `rust-std.md` heading; the guard's `R: Copy` and the panic-safe drops.
- **Not taken from the story:** vendoring and `.cargo/config.toml` (nothing to vendor); `rust-crates.md` (nothing
  third-party linked).

### Behaviour changes a user can see

For someone who builds the fork:
- A default build of javafx.media on Windows or Linux now needs Rust 1.100.0 (rustup, then `rustup toolchain install`
  in the repository root), or `-DJFX_RUST=false`, which builds the C and needs no Rust tool. Without either, the
  media `cmake-configure` fails with (final gate, Linux; paths replaced by `<repo>` and `<CARGO_HOME>`):
  ```
  jfxmedia: its Rust slices need the Rust toolchain 1.100.0 pinned in
  <repo>/rust-toolchain.toml, and no cargo or no rustc
  is on PATH or in <CARGO_HOME>/bin.

  Install rustup from https://rustup.rs and run "rustup toolchain install" in
  <repo>, or configure with -DJFX_RUST=false to build
  the C implementation of jfxmedia instead.
  ```
  javafx.graphics has no slice and builds without Rust either way.
- cargo's output reaches the Maven log only when each step ends; the whole of it is in
  `<build tree>/jfx-rust/logs/`. In the Linux Maven log the helper's messages and a cargo failure can still come out
  garbled (US-121); the log files are the reliable place.
- `verify` (and so `install`) runs the lint: it needs cbindgen 0.29.4 and perl (Git for Windows ships one). The `test`
  phase runs `cargo test`; `-DskipTests` skips it, `-Dmaven.test.skip=true` does not. `-DskipNative=true` runs no
  CMake, no cargo and no Rust test or lint.

For someone who ships the libraries:
- **Windows, Rust on:** `jfxmedia.dll` exports the same 58 names (the hooks build the same 61), and depends on the
  baseline's ten DLLs plus `ntdll.dll` (`NtWriteFile`, `RtlNtStatusToDosError`: std's stderr write) and
  `api-ms-win-core-synch-l1-2-0.dll` (`WaitOnAddress`, `WakeByAddressSingle`, `WakeByAddressAll`: std's `Once`,
  `Mutex` and `RwLock`). It grows from 132,096 to 229,888 bytes (hooks build 132,608 to 230,912). With Rust off it
  is the baseline's DLL in exports, dependents and size.
- **Linux, Rust on or off:** the version script now keeps every name but the 58 header exports local, so
  `libjfxmedia.so` no longer exports 1,244 names it exported at `7a66c85e39` (below). Size: 557,472 bytes at
  `7a66c85e39`, 299,024 with Rust off, 636,096 with Rust on (hooks build 558,624, 304,208, 637,184). `NEEDED` is
  unchanged.
- **Linux, Rust on:** the library loses the `.note.gnu.property` marking `x86 feature: IBT, SHSTK` that the C build
  has (the prebuilt std objects are not built with control-flow protection).

**The names the Linux version script hides**, all 1,244 in `backlog/US-027-linux-hidden-symbols.txt` (one per line;
the baseline's 1,302 dynamic names less the 58 header exports). Classified by prefix:
- 1,212 Itanium-mangled C++ names: 988 `_ZN` (nested names), 94 `_ZT` (46 `_ZTV` vtables, 24 `_ZTI` typeinfo, 24
  `_ZTS` typeinfo names), 69 `_ZGTt` (transaction-safe clones, all of `std::` exception members), 43 `_ZSt`
  (42 functions in `std::` and the object `std::nothrow`), 10 `_Z` followed by the length of a global-namespace
  name, and 8 global `operator new` and `operator delete` forms (`_Znwm`, `_Znam`, `_ZdlPv`, `_ZdaPv` and their
  sized and nothrow variants);
- 18 C++ runtime entry points: the 16 `__cxa_*` names, `__gxx_personality_v0` and `__gcclibcxx_demangle_callback`;
- 14 plain C names: the six `ColorConvert_YCbCr42*` functions, `color_tBU`, `color_tClip`, `color_tGU`, `color_tGV`,
  `color_tRV`, `color_tYY`, `inline_load_si128` and `inline_loadu_si128`.
None of the six other libraries of the Linux build (`libglassgtk3`, `libjavafx_iio`, `libprism_es2`,
`libprism_es2_monocle`, `libfxplugins`, `libgstreamer-lite`) imports one of them (`nm -D --undefined-only`, final
gate), and `git grep` finds no `dlsym` or `g_module_symbol` user of `libjfxmedia`. Java binds only header names. The
hooks build hides 1,252 (measured before the review rounds, not again in the final gate).

### Tests

**cargo tests** (`jfx_rust_test`, the same on both systems, from the final gate's logs):

| Test binary | Tests |
| --- | --- |
| `jfx-rust-support` unit tests | 9 |
| `tests/first_call_while_unwinding.rs` | 2 |
| `tests/guard_handle_stderr_line.rs` | 1 |
| `tests/stderr_line.rs` | 1 |
| `jfxmedia` unit tests | 2 |
| doc-tests of the two crates | 0, 0 |

The support crate's unit tests cover the failure value, the poison latch, a body that returns, a payload whose
`Drop` panics, an unrun body whose capture panics on drop, and the line for a `&str`, a `String` and another payload
and for a message with line breaks. The three integration files run the test executable again as a child process and
count its stderr lines: one caught panic writes exactly one line, through `guard` and through `guard_handle` alone,
and a first guarded call made while a panic unwinds returns its value and a later call installs the hook. The
`jfxmedia` tests check that the export returns `JFXM_ABI_VERSION` and that the constant is 4.

**The licence script's self-test:** 54 cases, all passing with Git for Windows' perl 5.38.2 and WSL's perl 5.40.1
(the fix round's runs; the gate runs the real check, not the self-test).

**Mutant proofs** (each mutant in a scratch copy or private build tree, restored afterwards):
- **ABI check**, each compiled with MSVC and with GCC:
  - a parameter `_extra: u32` the header does not have: MSVC `error C2733: 'jfxm_abi_version': you cannot overload a
    function with 'extern "C"' linkage`; GCC `error: conflicting declaration of C function 'uint32_t
    jfxm_abi_version(uint32_t)'`;
  - return type `i32`: MSVC `error C2371` and `error C2556: 'int32_t jfxm_abi_version(void)': overloaded function
    differs only by return type from 'uint32_t jfxm_abi_version(void)'`; GCC `error: conflicting declaration of C
    function 'int32_t jfxm_abi_version()'` (repeated by the confirming review);
  - the constant 5: `static_assert failed: 'jfxmedia crate: JFXM_ABI_VERSION differs from jfxmedia_api.h'` (MSVC
    C2338, GCC "static assertion failed");
  - the constant typed `i32`: the type assert fails on both;
  - the unchanged crate compiles clean on both.
- **End to end, the constant 5.** WSL, through Maven: `JfxMediaNativeTest` fails with `jfxmedia ABI version
  mismatch: expected 4, found 5`, the lint fails on the `static_assert`, and the same sources with `JFX_RUST` off
  pass 41/0/0/2. Windows, in a private tree: the built DLL returns 5 (4 with `JFX_RUST` off), the lint fails with
  C2338 and `cargo test` fails `the_abi_version_is_4`.
- **Completeness check** (through the real helper): an extra `#[unsafe(no_mangle)] pub extern "C" fn` fails ("no
  slice lists it in EXPORTS"); `jfxm_abi_version` made by `macro_rules!` fails ("cbindgen emitted no declaration of
  it"; also through the real `jfx_rust_lint` target in the confirming review); a `[u8; 4]` parameter and, in the
  confirming review, a tuple parameter make cbindgen print `ERROR: ...`, which fails the lint.
- **Origin check, MSVC map** (Windows): the C definition left in (the `#ifndef` removed, or a misspelt guard macro)
  fails the build with `jfxm_abi_version is defined by jfxmedia_api.obj, not by ...` (or `lib.obj` in a small
  project), in Release and Debug, and again on a rebuild; a missing map fails with `no linker map ...`. **GNU map**
  (WSL): the same C mutant fails naming `jfxmedia_api.cpp.o`, for `jfxmedia_test` and for `jfxmedia`, and again on a
  rebuild; a missing map and a map without the cross reference table (`--cref` removed) fail too. Each failure text
  is also in the origin log, which then holds no success line. Ninja and Unix Makefiles both behave so.
- **`--require-defined`** (WSL): a name added to the header and to `EXPORTS` but defined nowhere fails the link with
  `required symbol 'jfxm_proof_missing' not defined`; switched back to `--undefined=` the link succeeds and only the
  origin check catches it.
- **Tool lookup** at its three sites, each mutant restoring "both directories", with `CARGO_HOME` set to an empty
  directory and no cargo on `PATH`, on Windows and WSL: the configure mutant configures (with the home `.cargo/bin`)
  where the fixed helper fails with its message; the cbindgen mutant writes the headers where the fixed action fails
  with `no cbindgen is on PATH or in <empty>/bin`; the licence-script mutant finds the home cargo where the fixed
  script exits 2. The script's self-test does not catch its mutant; only that hand run does.
- **The guard** (cargo tests; on both systems unless noted): `guard` rethrowing, no latch, a latch never checked,
  poisoning on success, failure returned on success, each missing payload branch, no escaping of line breaks, the
  default hook left in place, the line printed twice, the hook writing nothing (the negative control), `install_hook`
  called on a panicking thread (the child dies: `cannot modify the panic hook from a panicking thread`), the payload
  dropped outside `catch_unwind`, `install_hook` removed from `guard_handle`, and the plain `return failure;` on the
  poisoned path: each fails a named test. After the last guard rework, the three mutants before the poisoned-path one
  were re-run on Windows only. A test that passes a `String` as `R` does not compile (`String: Copy` not satisfied;
  Windows).
- **Other:** a toolchain pinned to 1.99.0 fails configure with the helper's message and downloads nothing; the
  developer variables `CARGO_BUILD_TARGET` and `RUSTFLAGS` do not reach the archive (with the unset removed, cargo
  wrote to `cargo/x86_64-pc-windows-msvc/` and a stale archive was linked); a header export added without a manual
  configure re-runs it and the new name is exported.

**Java tests:** no Java source or test changed. `JfxMediaNativeTest` runs 23 tests, 0 failures, in every gate step that
runs it; javafx.media runs 41, 0 failures, 0 errors, 2 skipped, in 9 classes, as at `7a66c85e39`.

### Acceptance criteria
- **Without Rust before slice 2; the message after it; `-DJFX_RUST=false` builds the C:** the second and
  third parts are met on Windows and WSL, with `verify`, not `install`: with no Rust tool reachable the media
  `cmake-configure` fails with the message above (step 1 of the final gate, both systems), and `-DJFX_RUST=false`
  builds the C path with every module's per-class counts equal to the baseline (step 2). The first part was not
  tested as worded, because slice 1 was never gated alone. By proxy, the javafx.graphics tree, which has the helper
  but no slice, configured and built in both no-Rust runs.
- **`-DskipNative=true` runs no CMake, no cargo and no Rust test or lint:** met on Windows and WSL (step 5: 0
  executions of `cmake-configure`, `cmake-build`, `rust-test`, `rust-lint`; the libraries unchanged).
- **Windows: `dumpbin /EXPORTS` names identical:** met (58 and 61). **`/DEPENDENTS` identical:** deviation, accepted
  by the manager, for the owner to confirm: two more system DLLs (above). Why it cannot hold: the story requires the
  guard, std's panic machinery takes these imports whatever the guard does (the hook sits behind an `RwLock`), every
  later slice that uses std's synchronisation or I/O takes them too, and both DLLs are part of every Windows the JDK
  supports (`KERNEL32.dll` itself imports `ntdll.dll`).
- **Linux: every header export and no `_ZN` or `_R` symbol:** met (exactly the 58; 61 for the hooks build).
  **The hidden C symbols listed:** met here and in `backlog/US-027-linux-hidden-symbols.txt`; the PR is to point at
  it. **`NEEDED` identical, `ldd -r` without a new undefined symbol:** met (0 undefined, 0 not found). Beyond the
  criterion: the lost CET marking is a deviation, accepted by the manager, for the owner to confirm (stable Rust
  cannot rebuild std with control-flow protection; no `-z cet-report` was added, as it would warn on every link).
- **`JfxMediaNativeTest` unchanged and passing:** met on Windows and WSL.
- **fmt, clippy, `cargo test`, the ABI check and the licence check pass inside the build; the PR shows a
  parameter-type mutant failing:** met inside `mvn verify` on Windows and WSL (step 4); the mutant is shown above
  (under Tests); showing it in the PR is open until the PR exists.
- **A `-DCONF=Debug` Windows build links without LNK4098, and its tests pass:** met (step 3: 0 LNK4098, media
  41/0/0/2 in the 9 baseline classes, the cargo tests pass; the same on Linux).
- **All five CI jobs green, the macOS jobs without Rust, `build-webkit.yml` untouched:** `build-webkit.yml` untouched
  is met (`git status` and `git diff --stat` empty). The rest is open: it needs the owner's push.

### Gates

**Baseline** at `7a66c85e39`, before any change, `mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false"`,
exit 0, BUILD SUCCESS in 15 modules on both systems, 1,246 test classes each:

| Module | Windows | WSL Ubuntu |
| --- | --- | --- |
| jslc | 276 / 0 / 0 / 3 | 276 / 0 / 0 / 0 |
| base | 5510 / 0 / 0 / 22 | 5510 / 0 / 0 / 22 |
| graphics | 26684 / 0 / 0 / 850 | 26428 / 0 / 0 / 967 |
| controls | 9445 / 0 / 0 / 185 | 9445 / 0 / 0 / 185 |
| richtext | 186 / 0 / 0 / 0 | 186 / 0 / 0 / 0 |
| fxml | 123 / 0 / 0 / 3 | 123 / 0 / 0 / 3 |
| media | 41 / 0 / 0 / 2 | 41 / 0 / 0 / 2 |
| web | 194 / 0 / 0 / 0 | 194 / 0 / 0 / 0 |

(run / failures / errors / skipped). Baseline libraries: `jfxmedia.dll` 132,096 bytes, 58 exports, ten dependents
(hooks build 132,608, 61); `libjfxmedia.so` 557,472 bytes, 1,302 dynamic names (58 `jfxm_`, 988 `_ZN`, 0 `_R`;
hooks build 1,313), `NEEDED` `libgstreamer-lite.so libgobject-2.0.so.0 libglib-2.0.so.0 libc.so.6
ld-linux-x86-64.so.2`, `ldd -r` clean.

**The final gate, 2026-11-16,** after the last code change. Windows ran in this working tree, WSL in a fresh clone of
`7a66c85e39` with the story's changes applied; the 29 changed or new files matched their recorded checksums before
and after on both. "No Rust" means no `.cargo` entry on `PATH` and `CARGO_HOME` set to an empty directory, with
`command -v cargo rustc cbindgen` finding nothing. Tools: Temurin 27+35, Maven 3.10.0, cargo and rustc 1.100.0,
cbindgen 0.29.4 on both; Windows 10 Pro with CMake 3.24.2, MSVC 14.44.35207 (VS 2022) and perl 5.38.2; WSL Ubuntu
with CMake 4.2.3, gcc 15.2.0, glibc 2.43 and perl 5.40.1.

| Step | Command (from the repository root) | Windows | WSL Ubuntu |
| --- | --- | --- | --- |
| 0 | checks before the runs | on `7a66c85e39`, 11 modified + 7 new entries, checksums match, the count extraction reproduces the baseline's files | the same in the clone |
| 1 | no Rust: `mvn -B -ntp -pl modules/javafx.media -am clean test-compile` | exit 1 at the media `cmake-configure` with the helper's message, no cargo or rustc run, javafx.graphics built | the same |
| 2 | no Rust: `mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false" -DJFX_RUST=false` | BUILD SUCCESS 15/15 (16:00 min); totals and 1,246 per-class rows equal the baseline; "built from C" for both targets; no origin log; all 9 DLL rows equal the baseline | BUILD SUCCESS 15/15; totals and per-class rows equal the baseline; `libjfxmedia.so` 299,024 bytes (hooks 304,208), the 58 / 61 header names, `NEEDED` equal, `ldd -r` clean, IBT/SHSTK note present |
| 3 | Rust on: `mvn -B -ntp -pl modules/javafx.media -am clean test -DCONF=Debug -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=test/com/sun/media/**"` | BUILD SUCCESS (8:21 min); media 41/0/0/2, the 9 classes equal the baseline; cargo tests 9, 2, 1, 1, 2, 0, 0; `dev` profile; 0 LNK4098; both origin logs name a `jfxmedia` archive member | BUILD SUCCESS; media 41/0/0/2; the same cargo tests; `dev` profile; both origin logs name `cargo/debug/libjfxmedia.a(...)` |
| 4 | Rust on: `mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false"` | BUILD SUCCESS 15/15 (20:50 min); totals and per-class rows equal the baseline and step 2; the cargo tests; lint: completeness line, `abi_check.cpp` compiled, licence OK; `release` profile; origin logs; 58 / 61 exports equal the baseline; dependents the ten plus `ntdll.dll` and `api-ms-win-core-synch-l1-2-0.dll`, nothing else; 229,888 / 230,912 bytes; the other 7 DLL rows equal the baseline; `rust-std.md` byte-identical in the jar and the SDK; `CheckNoJni` exit 0; no `hs_err_pid` file | BUILD SUCCESS 15/15 (15:58 min); totals and per-class rows equal the baseline and step 2; the cargo tests and the lint as on Windows; 636,096 / 637,184 bytes; exactly the 58 / 61 header names, 0 `_ZN`, 0 `_R`; `NEEDED` equal; `ldd -r` clean; 0 `.debug_*` sections; no IBT/SHSTK note; 1,244 hidden names, imported by none; `rust-std.md` in the jar and the SDK; no `hs_err_pid` file |
| 5 | Rust on: `mvn -B -ntp -pl modules/javafx.media -am verify -DskipNative=true -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=test/com/sun/media/**"` | BUILD SUCCESS (4:25 min); media 41/0/0/2; 0 executions of the four exec steps; 0 lines naming cargo, rustc or cmake; the 18 native files unchanged in size and time | BUILD SUCCESS; media 41/0/0/2; 0 executions; the 8 libraries unchanged |

- Another project's Maven build ran on the Windows machine during parts of steps 3 and 4; both still met every value.
- The first run of this gate found one defect: the helper looked for cargo in both `$CARGO_HOME/bin` and the home
  `.cargo/bin`, so with `CARGO_HOME` pointing elsewhere it took the home rustup proxy, which on Linux then failed at
  build time instead of at configure. One fix round applied the lookup rule at its three sites and added the origin
  log (the origin lines were illegible in the Linux Maven log); a confirming review passed it with its own mutants,
  and the whole gate ran again with the results above.

### Reviews and analysis
- **Three independent reviews** of the first implementation: the crates, the ABI check, the licence gate and the notice;
  the build wiring; the CI workflow. Taken from the first: real LTO, the guard's hook not installed on a panicking
  thread with its tests, the tests of a panicking payload `Drop` and of the hook installed through `guard_handle` alone,
  the licence script's exit 2 and exact-version sign-off, the compiler-builtins entry of `rust-std.md` with its own
  expression and the heading check, the vendor exclusion for every file type, and the `is_same` note for struct slices.
  From the second: `RUSTUP_AUTO_INSTALL=0`, the headers as configure dependencies, the unset cargo variables, and the
  lost CET marking, recorded as a deviation. Found by both: the completeness check, the origin check and
  `--require-defined`. From the third: exact equality in both export checks, the "was not built" message, `ldd -r` as an
  assertion, `curl -fL`, no added line over 120 columns but URLs, the updated workflow header and one comment removed.
- **Guard rework:** while closing the reviewers' guard findings, two more places where a panic could leave the guard
  were found (dropping the unused failure value, and dropping the closure a poisoned call does not run): `R: Copy`
  closes the first, a drop under `catch_unwind` the second.
- **Confirming review** of the reworked tree: pass, nothing to fix; every ruling repeated with its own mutants.
- **Fix round and its confirming review** (after the first gate run): pass, nothing to fix, with its own mutants per
  lookup site and per map format.
- **Declined, with reasons:**
  - `-Dmaven.test.skip=true` does not skip `cargo test`: the story prescribes `<skip>${skipTests}</skip>`, and the
    Rust tests take seconds.
  - A textual scan that every `extern "C"` body starts with the guard: brittle against macros and formatting; the
    guard stays a rule (P4) that the reviewer checks.
  - A check that `nm` prints no `@@` version suffix in CI: the version script is anonymous by design.
  - A checksum for the downloaded `rustup-init`: the boot JDK in the same workflow is fetched the same way; not this
    story.
  - `guard_handle`'s poisoned path drops the unrun closure before the hook is installed, so a capture whose `Drop`
    panics prints std's multi-line message instead of the one line, but only when no guarded call has installed the
    hook yet. The panic is still caught (a unit test shows it); cosmetic, needs two rare conditions at once.
  - A self-test case that catches the "both directories" mutant of the licence script's own cargo lookup: the lint
    always passes cargo to the script, its lookup serves a hand run only, and three self-test cases pin the rule.
  - The licence check's header says "1 shipped crate(s)" and its summary "2 linked crate(s)": both are the script's
    terms (a shipped crate is a staticlib crate, the linked crates are its closure).

### Not verified, not done
- **macOS:** nothing was built or run. By reading the code: `mac.cmake` registers no slice, and the macOS profiles pass
  `-DJFX_RUST=${JFX_RUST}` (default `true`), so the helper is never asked for a slice there and `rust-test` and
  `rust-lint` print that no slice is enabled. US-028 has the facts this story learned.
- **Linux aarch64:** not built.
- **CI:** the five jobs never ran with these changes. The workflow changes were reviewed, and each check body was run by
  hand (Linux in bash in WSL, Windows in pwsh) against good and bad inputs, but no runner executed them. By the runner
  images' published software lists (read 2026-11-16: `ubuntu-24.04` image 20261111.370.1, `windows-2022` image
  20261112.366.7), both have rustup 1.29.3, CMake 3.31.6 and a perl (5.38.2 on Linux, 5.32.1 on Windows; the licence
  script ran only on 5.38.2 and 5.40.1, and uses nothing but `strict`, `warnings` and core modules), and the Windows
  image already has cbindgen 0.29.4. The list of the `ubuntu-24.04-arm` image names Cargo and Rust without versions and
  does not name rustup, so whether it has rustup is still not known; the fallback download covers its absence and never
  ran. The `ldd -r` assertion was never seen failing on the real library.
- **CMake versions:** only 3.24.2 (Windows) and 4.2.3 (WSL) ran. The helper uses nothing newer than the trees'
  minimum 3.20 by reading, which was not tested.
- **Licences:** the expression of compiler-builtins on aarch64 was not checked. The toolchain's COPYRIGHT files do
  not list the versions of addr2line (0.27.1), memchr (2.8.3) and miniz_oxide (0.9.1) that std links; their notice
  text in `rust-std.md` comes from the nearest listed version, under headings that name the linked one.
- **The licence script's `--self-test`** runs nowhere automatically: neither `jfx_rust_lint` nor `submit.yml` calls
  it (`CheckNoJni`'s self-test does run, in `no_jni_check`). It is a developer check, run by hand when the script
  changes; its 54 cases pass on Windows and WSL, with `CARGO_HOME` set and unset. The story does not ask for it in CI.
- **Garbled output:** on Linux the helper's messages and a cargo failure can come out garbled in the Maven log (the
  final gate's Linux origin lines had 0 literal matches there); the logs under `jfx-rust/logs` are the reliable
  place. US-121.
- **`mvn install`** was not run; every gate ran `verify`.
- **Robot and `tests/system` runs:** none; no test there is touched by this story.
- **Stale archive:** cargo decides by modification time, so a crate source restored with an older time (`cp -p`,
  `tar`) leaves the old archive linked (seen once in WSL); a normal edit or checkout is not affected.

### Follow-up
- **US-121** (filed): the output of the native build tools reaches the Maven log with stdout and stderr interleaved
  character by character, which makes error messages unreadable, worse on Linux than on Windows.
- **US-028** records the macOS linking facts below its story text.
- **Not filed, a question for the owner:** every Windows Maven run relinks every native DLL, with or without Rust
  (`glib-lite`, `gstreamer-lite`, `fxplugins`, `glass`, `prism_d3d`, `javafx_iio` relinked on a second identical
  run, while the Rust part did nothing). That is the Windows build's existing behaviour, outside this story.
- **Not filed, a second question for the owner:** whether the CI Rust setup step should also run
  `perl buildtools/rust/check-crate-licences.pl --self-test` (one line per job; see "Not verified, not done").

### Definition of Done
- Verified on Windows 10 + VS 2022 and on WSL Ubuntu, a clean build with Rust on and with Rust off: yes, with
  `mvn verify` instead of `mvn install` (the final gate).
- `backlog/README.md` updated and US-028 given the macOS facts: yes.
- Left for the owner: the commit; the push; a green `submit.yml` run of the five build jobs (macOS without Rust); the
  PR with the dumps (Windows exports and dependents, Linux `nm -D`, `NEEDED`, `ldd -r` and notes, the hidden names,
  the parameter-type mutant); the PR number and the CI records in this story and in `backlog/README.md`; the Done
  status after CI; and the confirmation of the two deviations (the Windows dependents, the Linux CET marking).
