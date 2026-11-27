# US-029 — Port the Glass Windows WinRT preferences (PlatformSupport) to Rust behind the glass_win_api ABI

## In short
- **Problem:** The Windows code that reads system settings (colours, network state) through the Windows Runtime was
  981 lines of C++ with hand-written COM. It is the first real piece of Glass to move to Rust.
- **Goal:** The same five exports in Rust, the C++ deleted, the same answers.
- **State:** Merged (PR #76, 2026-11-27).
- **Left to do (owner):** the `tests/system` preference tests and the manual sink check (commands under "Left for the
  owner" below); accept the side effects, the main ones being 118 MB of vendored Rust crates in the repository and
  four more system DLLs that `glass.dll` loads. Follow-up: US-132 (test gaps).
  US-133 was closed without change.

**Status:** 🔶 Merged, not Done (PR #76, 2026-11-27). What is left is under "In short".
The status as written before the merge:
🔷 Implemented, not merged (2026-11-26): committed as `7c38bdc73a` on the branch
`port-the-glass-windows-winrt-preferences-to-rust`; the fixes of its code review of 2026-11-27 are committed as
`fcfc41cf48` (PR #76, open). The five preferences exports and the two preference test hooks of `glass_win_api.h` are
Rust, in the new `glass-win` crate linked into both builds of glass.dll; `PlatformSupport.cpp/.h` and
`RoActivationSupport.cpp/.h` are deleted. Both builds export exactly the names of `5a709c189d` (97 and 123), and
`gwin_abi_version()` is still 7. The final Windows gate passes with the baseline's per-class counts plus four new test
classes (graphics 26,898 tests, 0 failures, 0 errors, 906 skipped), before and after the review fixes. Open: a
green `submit.yml` run (macOS included), the owner-only
`tests/system` runs and the owner's acceptance of the deviations (see "Implementation" below). Drafted 2026-09-30
from a read-only survey (read `glass_win_api.h:1-262,488-523,641-649` and `PlatformSupport.cpp:80-140`, counted
with `wc -l`/`grep -c`; `RoActivationSupport.cpp` and the two query bodies were NOT read; nothing built. 2026-10-01,
after PR review: the sink text follows US-039 part 2's design, in which the sinks only post; re-read
`PlatformSupport.cpp:36-47` and `:138-144`, and `GlassApplication.h:97-99` and `:128`; the same day the post became
US-039 part 1's guarded post, after that part's redesign) · **Epic:** Rust port of the remaining native code
(goal 3) · **Blocked by:** US-027, US-039 part 2 (a C++ fix; landed in PR #37)

## Story
As a platform maintainer,
I want the WinRT half of the Windows platform preferences (`PlatformSupport.cpp`, `RoActivationSupport.cpp`) to be
Rust that exports the same five symbols,
so that WinRT activation, the two event sinks and their lifetime come from the `windows` crate's
generated projections instead of hand-written HSTRING/activation code.

## Why Rust fits here
- **Must stay native (R1):** Java-side COM was considered for exactly this code and rejected: hard-coded IIDs,
  vtable slot indices and a fabricated vtable for the two event sinks, none testable here (`glass_win_api.h:54-56`).
- **Owned code (R2):** OpenJFX code; nothing vendored in `native-glass/win` (file headers not audited).
- **Buildable and testable here (R3):** Windows 10 + VS2022; `WinPreferencesNativeTest` (28 `@Test` methods) runs in the
  module suite and `WinPreferencesParityTest` (7) in `tests/system`.
- **Benefit (R4):**
  - The two sinks capture a raw `this` (`PlatformSupport.cpp:90,115`) and call Java on whatever thread the OS
    invokes them on (`glass_win_api.h:508-512`), which may be the toolkit thread. Their tokens are discarded and
    nothing unregisters them (`glass_win_api.h:514-521`, `PlatformSupport.cpp:87,112,126-130`), so a sink firing
    after `~PlatformSupport` calls a destroyed object.
  - US-039 part 2 fixes this in the C++ first, and this story is blocked by it. After that fix, a sink captures
    nothing that teardown frees and only posts its `PT_*` bits to the toolkit window through US-039 part 1's
    guarded post, so `preferences_changed` runs only on the toolkit thread. That post holds a shared guard across
    the HWND load and the `PostMessage`, and `WM_NCDESTROY` clears the HWND under the exclusive side, so a late
    post is refused and never reaches a recycled HWND. Teardown removes both registrations and releases the WinRT
    objects and the factory before `RoUninitialize`. Removing the registrations alone would not be enough, because
    `remove_*` does not wait for an invocation that has already started.
  - The port keeps that design, and Rust makes the unsafe variant hard to write. The delegate closures capture only
    their `PT_*` bits and call the toolkit core's guarded post through the internal seam. They never hold the
    HWND, so a late invocation reaches nothing `Drop` frees. `Drop` removes both registrations, then releases the
    objects before `RoUninitialize`. This crate module adds no lock. The guard belongs to the toolkit core, which
    is C++ until US-031, and it spans one `PostMessage`, never a call into Java, so port rule P6 holds. A closure
    holding a raw `this` would need an `unsafe impl Send`, which review rejects.
  - The `IActivationFactory*` and the `INetworkInformationStatics*` queried from it are raw pointers that are never
    released (`PlatformSupport.cpp:101-110`, `PlatformSupport.h:85`). In Rust every projected interface releases
    on `Drop`.
  - `RoActivationSupport.cpp/.h` (334 lines of HSTRING and `RO_CHECKED` plumbing) needs no Rust counterpart.
- **Binding (R5):** `windows` 0.62.2 (microsoft/windows-rs, `MIT OR Apache-2.0`, MIT taken). Features
  `UI_ViewManagement`, `Networking_Connectivity`, `Foundation` and `Win32_System_WinRT` are present (docs.rs,
  2026-09-30). The item placement is known by name only.
- **Why not Java:** the rejection above is recorded in the ABI header itself. Rust meets its objection, because the
  crate generates IIDs and vtables from Microsoft's metadata and nothing is hard-coded.

## Current state
- `PlatformSupport.cpp/.h` 421 + `RoActivationSupport.cpp/.h` 334 = 755 lines (`wc -l`).
- Five exports under `GLASS_WIN_ABI_VERSION 7` (6 before US-068): `gwin_sizeof_ui_settings`,
  `gwin_sizeof_network_info`, `gwin_prefs_set_callbacks`, `gwin_prefs_query_ui_settings`, `gwin_prefs_query_network`.
- One table, `GwinPrefsCallbacks`, with one slot `preferences_changed(void* user, int32_t types)`. Its Java stub lives
  in `Arena.global()` because no call guarantees that native code can no longer reach it: the sinks are never
  unregistered, and clearing the table does not wait for a sink already running (`glass_win_api.h:514-520`).
  US-039 part 2 makes the toolkit window the only caller, so the stub moves to an application-scoped arena closed
  after `gwin_run_loop` returns. Embedded mode never calls `gwin_run_loop` and keeps `Arena.global()`.
- Activation runs inside `gwin_app_create`, on the thread that will call `gwin_run_loop`, before it:
  `RoInitialize(RO_INIT_SINGLETHREADED)`, `UISettings`, `NetworkInformation` and both sinks (`glass_win_api.h:641-649`).
  The header says the queries run on the toolkit thread and block across apartments (`glass_win_api.h:565-567`);
  today a sink on another thread calls them too, which US-039 part 2 ends.
- `PlatformSupport` is a member of `GlassApplication` (`GlassApplication.h:128`). The toolkit WndProc's
  `WM_SETTINGCHANGE`, `WM_THEMECHANGED`, `WM_SYSCOLORCHANGE` and `WM_DWMCOLORIZATIONCOLORCHANGED` arms call it.

## Approach
- ABI unchanged. One crate for all of glass.dll, in the US-027 layout
  (`modules/javafx.graphics/src/main/native-rust/glass-win/`). This story adds its `prefs` module; the staticlib
  links into the `glass` target.
- The `m_platformSupport` member becomes an opaque handle created and dropped through an internal, non-exported
  seam. The four WndProc arms and `gwin_app_create` reach the Rust side through that seam until the core story
  moves them.
- The structs behind the two `gwin_sizeof_*` probes become `#[repr(C)]` with `const` size and offset assertions.
- No thread, lock or marshalling of its own (port rule P6, "No new concurrency", in the "Rust port" section of
  `backlog/README.md`). The delegates keep running on whatever thread the OS picks and only post, through the
  toolkit core's guarded post, as the C does after US-039 part 2.

### Slices
1. **Goldens from the C**, before any Rust:
   - a recording `GwinPrefsCallbacks`;
   - both query structs captured byte for byte;
   - the four WndProc arms fired with `SendMessage` to the toolkit window, recording `types` per message.

   Record the commit, the Windows build and the theme settings.
2. **Queries**: `gwin_sizeof_*`, `gwin_prefs_query_ui_settings` and `gwin_prefs_query_network`. Gate: slice-1
   structs exact; `WinPreferencesNativeTest` and `WinPreferencesParityTest` green.
3. **Activation, sinks, installer**: `gwin_prefs_set_callbacks`, the activation called from `gwin_app_create`, the two
   sinks, the settings-change seam, and US-039 part 2's shim-only hook that invokes the registered delegates (since
   US-068 an export of the test build of glass.dll only; the crate takes it over and keeps it out of the release
   build). Delete `RoActivationSupport.cpp/.h`. Gate: slice-1 traces, a sink
   event from a second thread that reaches `preferences_changed` on the toolkit thread with the same `types`, and
   US-039 part 2's teardown tests run against the Rust slice.
4. **Delete** `PlatformSupport.cpp/.h` and the slice's CMake option, in their own commit.

## Acceptance criteria
- `dumpbin /exports glass.dll` is identical before and after, and `gwin_abi_version()` is unchanged by this story.
  At commit `200192cfd8` that is 106 exports and ABI 6. US-039's shim-only test hooks add exports, US-068 takes every
  `gwin_test_*` hook out of the release glass.dll and bumps to ABI 7 (both builds: release 97 exports, test build
  123), and US-049 part 1 removes one and bumps to ABI 8; all move the starting point, not this story.
- `WinPreferencesNativeTest` (28) and `WinPreferencesParityTest` (7) are unchanged and pass; slice-1 goldens exact.
- US-039 part 2's teardown tests (delegates invoked from a second thread across teardown, `preferences_changed` only
  on the toolkit thread, `Platform.exit()` from a listener, the release before `RoUninitialize`) pass unchanged
  against the Rust slice, with the same child-JVM run count.
- `PlatformSupport.cpp/.h` and `RoActivationSupport.cpp/.h` are deleted.
- Every export and both delegate bodies are guarded with `catch_unwind` and answer the C++ fallback value.
- `unsafe` appears only in the boundary module; clippy and rustfmt are clean.

## Definition of Done
Merged PR. Verified on Windows (module suite plus the `tests/system` Windows preference tests). The C is deleted.
The WSL Linux build still configures (glass/win is not built there). `backlog/README.md` is updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | After US-039 part 2 the stub is called only on the toolkit thread; a Rust lock, thread or extra marshal would change timing. | No lock or thread of its own (port rule P6); the delegates only post, through US-039 part 1's guarded post; the trace records the thread of every `preferences_changed`. |
| 2 | `RoInitialize` must precede `gwin_run_loop`'s `OleInitialize` in the same apartment (`glass_win_api.h:646,655`). | Keep the call order; the slice-3 trace spans `gwin_app_create` → `gwin_run_loop`. |
| 3 | Theme-dependent goldens differ between machines. | Store the settings with the golden and compare the Rust build on the same machine. |
| 4 | The `windows` crate may cache activation factories for the process (from memory, not checked), so the factory would not be released before `RoUninitialize`. | Read the crate's generated factory accessor in slice 3. If it caches, activate through `RoGetActivationFactory` directly, as the C does. |

## Implementation (2026-11-26, committed as 7c38bdc73a; code review of 2026-11-27 committed as fcfc41cf48)

The port is committed as `7c38bdc73a` ("winrt prefs ported to Rust", 2026-11-27) on the branch
`port-the-glass-windows-winrt-preferences-to-rust`; its parent is `5a709c189d`. The fixes of the
code review of 2026-11-27 are committed on it as `fcfc41cf48` ("Code review feedback addressed.", 2026-11-27; PR #76,
open): 12 modified code files, 5 modified backlog files and the new US-133 (see "Code review of 7c38bdc73a
(2026-11-27)"). Before the first commit the work was uncommitted on `master` at `5a709c189d`: 18 modified
files, 4 deleted files and 12 new entries, one of them `buildtools/rust/vendor/` with 1,068 files
(`git status --short` before the first write-up: 34 lines). That write-up, part of `7c38bdc73a`, changed this story,
`backlog/README.md`, US-030, US-031, US-052 and `README.md`, added US-132, and corrected the skills `jfx-native-build`
and `jfx-rust-port`, which are git-ignored (`.gitignore:80`), so no commit carries them. The story's four slices were
built and gated as one change. "The C++" is the code of `5a709c189d`; "the baseline" is the gate run on it before any
change. The four deleted files had 981 lines there (`PlatformSupport.cpp` 522, `PlatformSupport.h` 123,
`RoActivationSupport.cpp` 256, `RoActivationSupport.h` 80), not the 755 of "Current state", which was counted before
US-039 and US-068 changed them.

### What changed

**Deleted** (`modules/javafx.graphics/src/main/native-glass/win/`): `PlatformSupport.cpp`, `PlatformSupport.h`,
`RoActivationSupport.cpp`, `RoActivationSupport.h`. `git grep` finds no include of them and no use of their symbols;
what still names them is comments: in the changed files they cite them as former code, and the stale ones in test
files kept unchanged are listed under "Follow-up".

**The crate** `modules/javafx.graphics/src/main/native-rust/glass-win/` (new). `src/lib.rs` starts with
`#![cfg(windows)]`: on Linux and macOS the crate is an empty library, and nothing links it there.
- `Cargo.toml` (49 lines): `crate-type = ["staticlib", "rlib"]`, `jfx-rust-support`, the feature `test-hooks`, and
  under `[target.'cfg(windows)'.dependencies]` `windows = { version = "=0.62.2", features = ["UI_ViewManagement",
  "Networking_Connectivity", "Win32_System_WinRT"] }`.
- `src/lib.rs` (105): the 21 `int32_t` constants of `glass_win_api.h` the crate uses, `GWIN_UI_COLOR_COUNT`, and the
  `#[repr(C)]` structs `GwinUiSettings`, `GwinNetworkInfo` and `GwinPrefsCallbacks`.
- `src/ffi.rs` (765; 732 in `7c38bdc73a`): the exports, the seam functions the crate defines, the declarations of
  the C++ ones it calls, the two test hooks (feature `test-hooks`), and doubles of the C++ seam for the unit tests.
- `src/prefs.rs` (871; 855 in `7c38bdc73a`): the ported logic, safe Rust: `Preferences` (activation in `create`, the
  teardown in `Drop`, `collect_ui_settings`, `collect_network_info`), the kept word `KeptTypes`,
  `post_preferences_changed`, `run_deferred`, `update_preferences`, `setting_changed_types` and the
  `RoInitialize failed: ` line.
- `src/sys.rs` (280; 281 in `7c38bdc73a`): the raw OS and vtable calls: `RoInitialize`, `RoUninitialize`,
  `RoActivateInstance`, `RoGetActivationFactory`, the `add_*`, `remove_*` and getter calls on the WinRT interfaces, and
  `FormatMessageW`, `WideCharToMultiByte` and `LocalFree`, declared with `windows::core::link!` (no further crate
  feature).
- `abi_check.cpp` (146), `cbindgen-functions.toml` (40) and `cbindgen-types.toml` (39). The check includes
  `glass_win_api.h`, the seam header and both cbindgen outputs: a prototype that differs between the C++ and the crate
  is a compile error in both directions; `static_assert`s pin the 21 constants by value and type, `GWIN_UI_COLOR_COUNT`,
  and `sizeof`, `alignof`, every `offsetof` and every field's type of the three structs. cbindgen's `[defines]` maps
  `feature = test-hooks` to `GLASS_WIN_TEST_HOOKS`.
- 35 unit tests (24 in `prefs.rs`, 11 in `ffi.rs`), the same in both feature sets; 32 (23 and 9) in `7c38bdc73a`.

**The seam** `native-glass/win/glass_win_prefs.h` (new, 136 lines; 137 in `7c38bdc73a`): internal to glass.dll, C
types only, no export macro.
- The crate defines, the C++ calls: `void* glass_prefs_create(void)`, `void glass_prefs_destroy(void* handle)`,
  `int32_t glass_prefs_update(int32_t types);`,
  `int32_t glass_prefs_setting_changed(uintptr_t wparam, intptr_t lparam);` (`glass_win_prefs.h:87` and `:95`; in
  `7c38bdc73a` both took a leading `void* handle`, which they only checked for NULL and poison),
  `void glass_prefs_run_deferred(void)`, `void glass_prefs_discard_deferred(void)`.
- The C++ defines, `noexcept` and calling nothing that throws; the crate calls: `int32_t glass_prefs_post(int32_t
  types, int32_t* published)`, `void glass_prefs_arm_timer(void)`, `void* glass_prefs_live_handle(void)`,
  `void glass_prefs_stderr(const char* text)`, and under `GLASS_WIN_TEST_HOOKS` the probes
  `glass_prefs_test_record_delegate_call`, `glass_prefs_test_log_event` and `glass_prefs_test_count` (each
  `(int32_t)`).
- A NULL handle is legal everywhere and means "no WinRT objects". The header says what each function answers for a
  poisoned handle: the queries answer for it what they answer without WinRT objects, `glass_prefs_destroy` leaves it
  alone, and the delivery of preference types reads no handle, so neither NULL nor a poisoned handle stops it.

**The C++ that stays** (`native-glass/win/`).
- `GlassApplication.h` (195 to 214 lines): includes `glass_win_api.h` and `glass_win_prefs.h` instead of
  `PlatformSupport.h`. `m_platformSupport` keeps its position, now a `PreferencesHandle`: a holder that cannot be
  copied, whose constructor calls `glass_prefs_create` and whose destructor calls `glass_prefs_destroy`.
  `GetPlatformSupport()` is `GetPreferencesHandle()`, with the same rule: the handle while `pInstance` is set, else
  NULL.
- `GlassApplication.cpp` (573 to 608): the WndProc arms (`WM_DO_ACTION_LATER`, `WM_CREATE`, `WM_NCDESTROY`,
  `WM_SETTINGCHANGE`, `WM_PREFERENCES_CHANGED`, `WM_TIMER`, `WM_THEMECHANGED`, `WM_SYSCOLORCHANGE`,
  `WM_DWMCOLORIZATIONCOLORCHANGED`) and `EnterNestedEventLoop` call the seam; `PlatformSupport::PT_ALL` is
  `GWIN_PT_ALL`. It defines the four C++ seam functions: `glass_prefs_post` wraps `GlassApplication::PostToToolkit`,
  `glass_prefs_stderr` is `fputs(text, stderr)`.
- `glass_win_api.cpp` (1,740 to 1,735): `gwin_run_loop`'s pump calls `glass_prefs_run_deferred()`; the C++ of the two
  `gwin_sizeof_*` probes is deleted.
- `glass_win_test.cpp` (745 to 770): the three probes, `noexcept` wrappers of `GwinTestRecordDelegateCall`,
  `GwinTestLogEvent` and `GwinTestCount`.
- `glass_win_api.h` (3,264 to 3,275) and `GlassMenu.cpp`: comments only. Without its comments `glass_win_api.h` is
  identical to `5a709c189d`: no declaration, type, constant or enumerator changed, `GLASS_WIN_ABI_VERSION` is 7. The
  new comments say that `gwin_prefs_set_callbacks` and `gwin_test_prefs_capture` answer `GWIN_ERR_INVALID_ARG` only
  after a panic the crate caught (a bug). For the two queries (`glass_win_api.h:623-626`, since the code review of
  2026-11-27): a panic caught while a query reads the live toolkit's preferences handle poisons that handle, and that
  query and every later one on the handle answer as a toolkit without WinRT objects does, a zeroed struct and
  `GWIN_OK`, until the toolkit window is destroyed; a panic caught anywhere else in the call answers
  `GWIN_ERR_INVALID_ARG` with nothing written.

**The build.**
- `modules/javafx.graphics/native/win.cmake` (+40): the slice `GLASS_WIN_PREFERENCES` of the crate, registered for
  `glass` and for `glass_test`, both `C_DELETED`, with `HEADERS glass_win_api.h glass_win_prefs.h`. `glass`: 5
  `EXPORTS` (`gwin_sizeof_ui_settings`, `gwin_sizeof_network_info`, `gwin_prefs_set_callbacks`,
  `gwin_prefs_query_ui_settings`, `gwin_prefs_query_network`), 6 `INTERNAL` (the six crate-defined seam functions), 4
  `IMPORTS` (the four C++ ones). `glass_test`: the same plus `gwin_test_prefs_capture` and `gwin_test_prefs_invoke`
  (7 `EXPORTS`), the three probes (7 `IMPORTS`), `FEATURES test-hooks` and `ABI_CHECK_DEFINITIONS
  GLASS_WIN_TEST_HOOKS=1`. `native/CMakeLists.txt`: one comment.
- `buildtools/rust/jfx_rust.cmake` (1,422 to 1,961 lines; 652 added, 113 removed): the four keywords below, and:
  with the toolchain missing, configure no longer offers `-DJFX_RUST=false` for a `C_DELETED` slice; Linux links with
  `--require-defined=` per `IMPORTS` symbol; a leading `-D` in `ABI_CHECK_DEFINITIONS` is accepted; a symbol under two
  keywords across the slices of one target, and two feature sets whose cargo directories or target names would
  coincide, are configure errors; cbindgen's `Missing [defines] entry` warning fails the lint; the status line reads
  `... in Rust; <n> export lines in its headers`.
- `buildtools/rust/cargo-config.toml` (+10): `[source.crates-io] replace-with = "vendored-sources"` and
  `[source.vendored-sources] directory = "rust/vendor"`. Cargo resolves the relative path against the parent of the
  file's directory, so it is `buildtools/rust/vendor` for every cargo call of the build, which all get this file with
  `--config`. There is still no `.cargo/config.toml`.
- `buildtools/rust/vendor/` (new): `cargo vendor` output, committed whole and untrimmed, since cargo checks every file
  against its crate's `.cargo-checksum.json`. 15 crates, 1,068 files, 118,053,047 bytes: linked, `windows` 0.62.2,
  `windows-core` 0.62.2, `windows-result` 0.4.1, `windows-strings` 0.5.1, `windows-link` 0.2.1, `windows-collections`
  0.3.2, `windows-future` 0.3.2, `windows-numerics` 0.3.1, `windows-threading` 0.2.1; at build time only,
  `windows-implement` 0.60.2, `windows-interface` 0.59.3, `proc-macro2` 1.0.107, `quote` 1.0.47, `syn` 2.0.119,
  `unicode-ident` 1.0.27. All are `MIT OR Apache-2.0` (taken under MIT), `unicode-ident` `(MIT OR Apache-2.0) AND
  Unicode-3.0`. No feature switches one of them off. Only `proc-macro2` and `quote` have a `build.rs`, which runs
  `$RUSTC --version`, no network.
- `.gitignore` (+5/-1): `!buildtools/rust/vendor/**` as the last rule (the line before it gets its final newline). The
  older rules would ignore 20 vendored files (15 `Cargo.toml.orig`, 5 under directories named `Debug`);
  `git status --short --ignored -- buildtools/rust/vendor` now lists no ignored path.
- `Cargo.lock` (+153 lines, written by cargo).
- `buildtools/rust/check-crate-licences.pl` (+4) and `.github/workflows/submit.yml` (3 added, 2 removed): comments
  only. The licence script's usage text says that a hand run offline needs `--config buildtools/rust/cargo-config.toml`;
  `submit.yml` without its comment lines is identical to `5a709c189d` (CRLF kept) and still checks the exports of
  jfxmedia only.
- `modules/javafx.graphics/src/main/legal/rust-std.md` (117 lines, first heading `## The Rust Standard Library
  1.100.0`, after javafx.media's) and `rust-crates.md` (278 lines: a `## <name> <version>` section with the crate's
  `license-mit` text for each of the nine linked `windows*` crates). The final gate's jar check: "6 legal notice(s)
  of ...javafx.graphics\src\main\legal are in ...javafx-graphics-28-ea.jar".

**Java.** No pom and no production behaviour changed.
- `WinApplication.java`, `WinPreferenceCalls.java`, `WinPreferences.java`: comments only (2, 13 and 18 lines
  replaced; 731, 517 and 421 lines as before), where a comment named a deleted file or C++ symbol as the place where
  something lives today. The 68 compiled classes of `com/sun/glass/ui/win` are md5-identical to the baseline's.
- `WinGlassNativeShim.java` (+323/-4 against `5a709c189d`, 5,809 lines; +325/-4 and 5,811 in `7c38bdc73a`): what
  the new tests bind (`SendMessageW`, the raw queries over a fill byte, `CoGetApartmentType`, `CoInitializeEx` and
  `CoUninitialize`, the system message of an `HRESULT`, a callback table that answers 0 or 1, a table with a NULL
  slot) with one import, and comment fixes in three places of the existing code (4 lines).
- The new tests and their helpers are under "Tests". No existing test file and nothing under `tests/system` changed.

### Decisions and their reasons
- **One change, no C/Rust switch left.** The four C++ files are deleted and the slice is `C_DELETED`. The owner
  commits a story as one commit, and a C path refactored behind a seam adapter would no longer be the C that shipped.
  The A/B baseline is therefore the C++ exactly as shipped at `5a709c189d`: its goldens, traces, export lists and DLLs
  were captured before any native change.
- **`windows` 0.62.2, vendored** (the story's R5), with the three features that compile the port; the closure is
  vendored whole because the build is `--offline --locked`, cargo checks each file, and none of its 15 crates can be
  switched off.
- **One crate, three modules:** `prefs` holds the logic in safe Rust; `unsafe` only in `ffi` (the C ABI and the seam)
  and `sys` (raw OS and vtable calls that the crate has no safe binding for).
- **The seam** is one internal header with C types only, in both directions. The crate's seam functions are
  `INTERNAL` (linked, checked, never exported); the C++ ones are `IMPORTS`. The ABI check includes the header. No C++
  exception reaches Rust (the C++ seam functions are `noexcept` and call nothing that throws), and no panic reaches the
  C++ (every seam function the crate defines runs in the guard).
- **Test hooks behind the cargo feature `test-hooks`**, linked only into `glass_test` from its own archive, so the
  release glass.dll has no hook code and no probe call (US-068's rule). The release archive and the release
  `glass.map` hold 0 `gwin_test_` and 0 `glass_prefs_test_` names; `dumpbin /SYMBOLS` of the release archive shows the
  5 exports and the 6 `INTERNAL` functions, and the 4 imports as undefined; the test-hooks archive adds the two hooks
  and the three probes, undefined.
- **Delegates** from the crate's constructors (`TypedEventHandler::new`, `NetworkStatusChangedEventHandler::new`),
  closures that capture only their `PT_*` bits and do what `PostPreferencesChanged` did. The generated `Invoke` has no
  `catch_unwind`, so each closure body is one guarded call that answers `Ok(())` in every case. The closures never
  touch their sender or arguments. The test hooks keep their delegates in two `AtomicPtr` slots, written with
  `into_raw` and released with `from_raw`, with the C's exchange rules (kept only while capture is on and only after a
  successful `add_*`, dropped when capture goes off). No `unsafe impl Send` or `Sync`. Why not WRL-like delegates: a
  non-agile delegate would need a hand-written vtable in `unsafe` Rust, which the story's R4 argues against.
- **Activation and teardown as `PlatformSupport::InitializeWinRT` and `~PlatformSupport` did.**
  `RoInitialize(RO_INIT_SINGLETHREADED)` (`S_FALSE` counts as success), then `RoActivateInstance` of
  `Windows.UI.ViewManagement.UISettings`, kept as the `IInspectable` it answers, and
  `RoGetActivationFactory::<IActivationFactory>` of `Windows.Networking.Connectivity.NetworkInformation`, then one
  `cast` to `INetworkInformationStatics`. Story risk 4 is real: the crate's `UISettings::new()` and every
  `NetworkInformation` static keep an agile factory in a process-lifetime static that is never released (and call
  `CoIncrementMTAUsage` and try registration-free activation), so the port never uses them, nor
  `windows::core::factory`, nor a class method that queries again. The `add_*`, `remove_*` and getter calls are raw
  vtable calls on the crate's own interface pointers, with one `QueryInterface` where the C++ had one. Partial states
  are the C++'s: an activation failure of one half leaves the other half working; a failed `add_*` leaves that sink
  unregistered. Teardown removes both registrations with the kept tokens, releases `IUISettings5`, the `UISettings`
  object, the statics and the factory, then calls `RoUninitialize` only if `RoInitialize` succeeded, with the same six
  test events in the same order.
- **Failure values under the guard**, where the C++ had no `catch` on these paths: `gwin_sizeof_*` 0 (no struct has
  size 0, so Java reports a layout mismatch); `gwin_prefs_set_callbacks` and the two test hooks
  `GWIN_ERR_INVALID_ARG`, the only failure value they document; the queries, for a panic while they read the live
  handle, every byte zero and `GWIN_OK` (the answer of the C++'s failure path without WinRT objects, `available == 0`;
  the failure value of `guard_handle` in `query_live`), and `GWIN_ERR_INVALID_ARG` for a panic anywhere else in the
  call; `glass_prefs_create` NULL; `glass_prefs_update` and `glass_prefs_setting_changed` 0, so the message goes to
  `DefWindowProc` as when no table is installed; both delegate bodies `S_OK`. The handle embeds a `Poison`; functions
  without a handle use `guard`. The query answer for a poisoned handle is that of the code review of 2026-11-27; in
  `7c38bdc73a` it was `GWIN_ERR_INVALID_ARG`.
- **State.** The kept word `s_deferredTypes` is one atomic with the C's three operations (OR, acquire load,
  exchange). The callback slot and its user pointer, which the C read and wrote without a lock by design, are atomics,
  each loaded once per call as the C loaded the slot once. Orderings: the `RoInitialize` flag Relaxed, the slot and
  user Release/Acquire, the kept word SeqCst/Acquire. No lock, thread or channel (P6).
- **The queries keep the C's rules.** `out == NULL` is `GWIN_ERR_INVALID_ARG`; no live handle zero-fills and answers
  `GWIN_OK`; the colours come in the nine-entry order of `colorTypes` as `(A << 24) | (R << 16) | (G << 8) | B`, then
  the advanced-effects and the auto-hide blocks, each with its own `QueryInterface`; a failed `QueryInterface` ends with
  that block and every later one invalid, a failed getter leaves its zero value. The two `bool` getters read into a
  `u8` and publish `value != 0`. `GetInternetConnectionProfile` is a raw call, so "success, no profile" is visible:
  `available = 1`, `GWIN_NET_COST_UNKNOWN`. The cost goes through the C's explicit switch.
- **The `RoInitialize failed: ` line** is built as `_com_error::ErrorMessage` and `RoException` built it:
  `FormatMessageW(ALLOCATE_BUFFER | FROM_SYSTEM | IGNORE_INSERTS, NULL, hr, 0x0400, ...)`, one trailing line break
  removed, the fallbacks `IDispatch error #%d` (0x80040200 to 0x8004FFFF) and `Unknown error 0x%0lX`, `LocalFree`,
  `WideCharToMultiByte(CP_ACP, ...)`, the prefix, no newline. A C++ seam function writes it with `fputs(text, stderr)`,
  the CRT stream the C's `fprintf` used; Rust's `std::io::stderr` is not used. An empty system text prints the bare
  prefix, as the C++ did.
- **Ownership and order in `GlassApplication`.** `m_platformSupport` stays a member at the same position, so creation
  still happens in the constructor's member initialisation and destruction after `~GlassApplication`'s body and before
  `~BaseWnd`. Which handle is live stays a C++ rule. Create answers a handle whenever no panic occurred, also when
  `RoInitialize` or an activation failed. `glass_prefs_update` and `glass_prefs_setting_changed` need no WinRT object
  and, since the code review of 2026-11-27, take no handle.
- **Tests.** The dispatch trace is permanent, always on and machine-independent, its expected values in the test
  source. The query golden is opt-in, a test resource with the capture commit in its name and `machine.*` keys,
  compared only on the same machine (story risk 3). The tests written first were proven against the C++ by temporary
  C++ mutants; those added in the rework pass against the C++'s test glass.dll and were proven by mutants of the port.
- **Upstream.** The plan had no upstream issue, as the port was to fix no defect upstream has (US-039 carried those).
  That was withdrawn once defects 1 to 5 were found in upstream's code too; see "Follow-up".

### The helper's new keywords
```
jfx_add_rust_slice(<target> CRATE <dir> SLICE <NAME> HEADERS <h>... EXPORTS <sym>...
    [INTERNAL <sym>...]               # functions the crate defines for the target's own C/C++, not exported
    [IMPORTS <sym>...]                # functions the target's C/C++ defines for the crate to call
    [FEATURES <feature>...]           # the cargo features of the archive this target links
    [ABI_CHECK_DEFINITIONS <def>...]  # compile definitions of the ABI check of that feature set
    [ABI_CHECK_INCLUDE_DIRS <dir>...] [C_DELETED])
```
- `INTERNAL`: a plain declaration (no export macro) in one of the `HEADERS`, else a configure error. Linked with
  `/INCLUDE:` on Windows (pulled in and kept through `/OPT:REF`, not exported), `-u` on macOS, `--require-defined=` on
  Linux (kept local by the version script). The post-link origin check covers it as it covers `EXPORTS`.
- `IMPORTS`: a declaration of either kind in one of the `HEADERS`, else a configure error; Linux adds
  `--require-defined=` for each, so a symbol the C/C++ does not define fails the link there as on Windows and macOS.
  Rust declares them in `unsafe extern "C"` blocks; rustc's "never used" warning fails clippy `-D warnings`, so each is
  called in every feature set that declares it.
- A symbol under two keywords, in one slice or in two slices of a target, is a configure error.
- `FEATURES`: the target links an archive built with `--features <crate>/<feature>` in its own cargo directory,
  `<build tree>/cargo-with-<the sorted features joined by +>`, through its own IMPORTED library and cargo target
  (`jfx_rust_glass_win_with_test_hooks`, `jfx_rust_glass_win_with_test_hooks_cargo`); both cargo steps always run, so a
  source change rebuilds both archives. `jfx_rust_test`, clippy and the ABI check run once per feature set.
- The completeness check evaluates cbindgen's `#if defined(<X>)` blocks with `<X>` defined exactly when
  `ABI_CHECK_DEFINITIONS` defines it, and wants exactly the `EXPORTS`, `INTERNAL` and `IMPORTS` of that feature set
  (the `IMPORTS` as `extern` declarations).
- The status line, final gate: `-- glass (glass): slice GLASS_WIN_PREFERENCES from the glass-win crate,
  gwin_sizeof_ui_settings, ..., gwin_prefs_query_network in Rust; 123 export lines in its headers; internal
  glass_prefs_create, ...; imports glass_prefs_post, ...`. The 123 are lines of `glass_win_api.h`, the test hooks
  under `#ifdef GLASS_WIN_TEST_HOOKS` included; on Windows the helper uses them only to check that each `EXPORTS`
  symbol is declared.

### Deviations for the owner to accept
Each was argued; none is silent.
1. **118 MB of vendored source:** 118,053,047 bytes in 1,068 files under `buildtools/rust/vendor/`, because the
   build is `--offline --locked` and cargo checks every vendored file.
2. **New load-time dependents of glass.dll:** `api-ms-win-core-winrt-l1-1-0.dll` (the four `Ro*` functions),
   `combase.dll` (`CoCreateFreeThreadedMarshaler`, the delegates' `IMarshal` answer), `ntdll.dll` and
   `api-ms-win-core-synch-l1-2-0.dll` (Rust std, as jfxmedia.dll has since US-027), and second, lower-case descriptors
   `kernel32.dll` and `oleaut32.dll`. The C++ loaded combase.dll at run time and degraded without it; that loader and
   its four stderr texts (`GetProcAddress: %s not loaded`, `WinRT: Failed to fetch system directory`, `WinRT: Failed
   to form path to combase.dll` and the module-not-found message) are gone, so glass.dll no longer loads where these
   DLLs are missing. JDK 27 runs on Windows 10 and later, where both exist. Nothing was removed from the dependents,
   and the delay-load list is unchanged. The code review of `7c38bdc73a` (F3) raised the two WinRT entries and changed
   nothing: `dumpbin /DEPENDENTS` of the baseline gate's release glass.dll (the C++ of `5a709c189d`) lists
   `ole32.dll` among its load-time dependencies, and on this Windows 10 `dumpbin /DEPENDENTS` of
   `C:\Windows\System32\ole32.dll` lists `combase.dll` among ole32.dll's dependencies and
   `api-ms-win-core-winrt-l1-1-0.dll` among its delay-load dependencies.
   On this Windows 10 (10.0.19045), loading `api-ms-win-core-winrt-l1-1-0.dll` and loading `combase.dll` return the
   same module handle (`NativeLibrary.Load` in PowerShell 7, 2026-11-27), and the four functions glass.dll imports
   from the API set, `RoInitialize`, `RoUninitialize`, `RoActivateInstance` and `RoGetActivationFactory`, are found
   through that handle. Here the API set is therefore `combase.dll`, which the C++ glass.dll already had in its
   process through `ole32.dll`.
3. **Existing C++ imports bind elsewhere:** `CoTaskMemAlloc` and `CoTaskMemFree` now bind to `combase.dll` instead of
   `ole32.dll`; the five `OLEAUT32.dll` ordinal imports (#2, #4, #6, #7, #150) are named imports from `oleaut32.dll`;
   14 `KERNEL32.dll` functions moved to the lower-case `kernel32.dll` descriptor. `GetSystemDirectoryW`,
   `LoadLibraryW`, `strcpy_s` and `strlen` are no longer imported.
4. **The delegates are agile** (they answer `IAgileObject` and a free-threaded `IMarshal`; WRL's `Callback<>` is
   ClassicCom without `FtmBase`, so the C++'s were not). The OS may now invoke a sink directly on its own thread where
   it marshalled into the toolkit STA before. `preferences_changed` still runs only on the toolkit thread: the sink
   only posts, as it has since US-039 part 2. The port review rated this risky and the owner's call. Which thread the
   OS really uses was not observed (below).
5. **The delegates answer more interfaces to `QueryInterface`:** the delegate IID, `IUnknown`, `IAgileObject` and
   `IMarshal`.
6. **The thread's COM error object is cleared** after a failed `cast` or `Ro*` call: the crate's `Error` calls
   `GetErrorInfo`; the C++ never did.
7. **`fputs` instead of `fprintf(stderr, text)`,** and the message is copied before its buffer is freed (C++ defects
   1 and 2 below). On the captured failure the bytes are equal.
8. **`-DJFX_RUST=false` is a configure error for javafx.graphics on Windows:** `glass: the C implementation of its
   slice GLASS_WIN_PREFERENCES is deleted, so it cannot be built with -DJFX_RUST=false`. The switch covers the whole
   tree, so it also ends that A/B path for any later glass slice. Linux and macOS register no glass slice.
9. **Out of memory aborts the process** where the C++ answered an error: building a delegate (WRL's `Callback`
   answered NULL), the `Box` of the handle, the `format!` of the fallback text and the crate's marshaler assert. In
   the C++ `gwin_app_create`'s `catch (...)` (`glass_win_api.cpp:554`; `:557` in the tree) turned a `bad_alloc`
   into a NULL toolkit.
10. **Differences on paths that only a Rust bug or a COM contract violation reaches:** a query on a poisoned handle
    writes a zeroed struct and answers `GWIN_OK`, as a toolkit without WinRT objects does (in `7c38bdc73a` it answered
    `GWIN_ERR_INVALID_ARG` and wrote nothing, on which Java's `requireOk` throws, `WinPreferenceCalls.java:385` and
    `:394`, and every later notification of that toolkit was dropped); `glass_prefs_update` and
    `glass_prefs_setting_changed` read no handle and deliver whatever the state of the handle, under `guard`, so a
    panic there poisons nothing (a listener that calls `Platform.exit()` can destroy the handle inside the upcall);
    `glass_prefs_run_deferred` delivers when the live handle is not NULL, poisoned or not, and drops the taken types
    when it is NULL; `glass_prefs_destroy` leaves a poisoned handle alone (no remove, release or `RoUninitialize`: it
    leaks, and `RO_INITIALIZED` stays set: US-133); a panic inside create runs `Drop` on the partly built object
    before create answers NULL; a failed `QueryInterface` or `GetConnectionCost` that still writes a pointer leaks it,
    where the C++'s `ComPtr` released it; `gwin_test_prefs_invoke` calls the crate's `Invoke`, which would call
    `GetErrorInfo` on a failure `HRESULT` (unreachable).

### Defects of the deleted C++ and what the port does
| # | Site at `5a709c189d` | Defect | The port | Where behaviour now differs |
| --- | --- | --- | --- | --- |
| 1 | `RoActivationSupport.cpp:221-232` | The system text is read after the temporary `_com_error` freed it (use after free) | Copies the text before `LocalFree` | Where the freed buffer was reused before the read; on every captured input the bytes are equal |
| 2 | `RoActivationSupport.cpp:107` | The system text is the `fprintf` format string | `fputs` | A system text holding `%` |
| 3 | `RoActivationSupport.cpp:228` | `new char[]` can throw `std::bad_alloc` through the `extern "C"` path | Fallible allocation; nothing printed; create still answers a handle | Out of memory at that allocation |
| 4 | `RoActivationSupport.cpp:232` | The second `WideCharToMultiByte` is unchecked | Prints nothing when it fails | That conversion failing |
| 5 | `PlatformSupport.cpp:428-432` | `GetConnectionCost` succeeding with a NULL cost is dereferenced | `available = 0`, as for a failed `GetConnectionCost` | Such an answer |
| 6 | the activation and query paths | A `QueryInterface`, `RoActivateInstance` or `RoGetActivationFactory` that succeeds with NULL is dereferenced | An error: that block or half ends like a failed call | Such an answer |
| 7 | `PlatformSupport.cpp:139-140` | The `IInspectable` from `RoActivateInstance` is stored as an `IUISettings*` | Keeps the `IInspectable` | None on the captured paths |
| 8 | the queries, `gwin_prefs_set_callbacks`, `onSettingChanged` | A misaligned `out`, `cb` or `lParam` is accessed as aligned | `read_unaligned` / `write_unaligned` | A misaligned pointer |

Sites 1, 5, 6 and 8 are undefined behaviour in the C++; the port does the defined thing above, and the goldens and
traces, all captured on paths without these inputs, match exactly.

### Behaviour changes a user can see
For someone who builds the fork:
- A Windows build of javafx.graphics now needs Rust 1.100.0 (rustup, then `rustup toolchain install` in the
  repository root), and its `verify` phase cbindgen 0.29.4 and perl, as javafx.media has since US-027. Without the
  toolchain configure stops with `glass: its Rust slices need the Rust toolchain 1.100.0 pinned in
  <repo>/rust-toolchain.toml, and <what is wrong>.`, followed by `Install rustup from https://rustup.rs and run
  "rustup toolchain install" in <repo>. glass has no C implementation of its slice GLASS_WIN_PREFERENCES to fall back
  to (C_DELETED), so the toolchain is required.` (the helper's text). `-DJFX_RUST=false` fails configure for it
  (deviation 8). Linux and macOS builds of javafx.graphics register no slice and need no Rust tool.
- On Windows the graphics `test` phase runs `cargo test` (glass-win 35 tests without features and 35 with
  `test-hooks`, jfx-rust-support 9, 2, 1, 1), and `verify` runs fmt, clippy `-D warnings` and the ABI check for both
  feature sets and the licence check (`check-crate-licences: OK, 13 linked crate(s), 6 build-time crate(s)`).
- Every cargo call of the build takes the third-party crates from `buildtools/rust/vendor/`. A cargo run by hand
  needs `--config buildtools/rust/cargo-config.toml` for them: with an empty `CARGO_HOME`,
  `cargo metadata --locked --offline` exits 101 without it ("no matching package named `windows` found") and 0 with it
  (18 packages, 15 from the vendored tree).

For someone who ships the libraries:
- Both builds of glass.dll export the same names (97 release, 123 test). Release glass.dll grows from 241,664 to
  345,600 bytes, the test build from 262,144 to 366,592 (350,208 and 371,200 in `7c38bdc73a`). The dependents are
  deviation 2. The javafx.graphics jar and the SDK carry `rust-std.md` and `rust-crates.md`.

For someone who runs JavaFX on Windows:
- The preference values and their delivery are unchanged on the captured scenarios: the query structs equal the
  golden byte for byte, and the dispatch, sink and teardown traces equal the C++'s.
- A sink may run on the OS's own thread (deviation 4); `preferences_changed` still only on the toolkit thread.
- A panic in the crate (a bug) writes one `jfx-rust: panic at <file>:<line>:<column>: <message>` line to stderr and
  gives the failure values above, where the C++ had no `catch`.

### Tests
**Unchanged and passing** (files unchanged; final gate): `WinPreferencesNativeTest` 28 / 0 / 0 / 0,
`WinPreferenceSinksNativeTest` 10 / 0 / 0 / 1 (`manualSinkRun` runs only with `-Dwin.glass.manualSinkSeconds=<n>`),
`WinGlassNativeTest` 27 / 0 / 0 / 0 (run / failures / errors / skipped). In `tests/system`, for the owner:
`WinPreferencesParityTest` (7 `@Test`) and `WinApplicationStartupTest`.

**New** (`modules/javafx.graphics/src/test/java/test/com/sun/glass/ui/win/`, Windows only):

| Class | Tests | What it pins |
| --- | --- | --- |
| `WinPreferenceDispatchTraceTest` | 9 | Always on, the expected values in its source. 31 messages sent on the toolkit thread to its own window, with the table answering 0 and then 1: the exact calls, types, thread, user pointer and the send result 0, over every `onSettingChanged` case, the five theme arms, the `PT_ALL` mask and the wake of `WM_PREFERENCES_CHANGED`. Both sinks (auto-hide 4, network 8), invoked from a second thread and from the toolkit thread, deliver on the toolkit thread and never inside the sink. The COM apartment: MAINSTA after create, in the loop and after `DestroyWindow`. Teardown events exactly 1, 2, 3, 4, 5, 6. The test hooks answer -1 for an unknown delegate and once capture is off. The queries: NULL gives -1, no toolkit zero-fills over a 0xA5 prefill, the toolkit thread gets every byte written. A live query's flags (`available`, `colors_valid`, `advanced_effects_valid`, `auto_hide_valid` 1; network `available` 0 or 1, cost 0 to 3, unknown when not available). A cleared table and a table with a NULL slot get no call |
| `WinPreferenceActivationFailureTest` | 5 | The toolkit thread put into the MTA before the toolkit is created, so `RoInitialize` fails with `RPC_E_CHANGED_MODE`: stderr exactly `RoInitialize failed: ` and that system text, no line break (a Debug glass.dll, told by its `VCRUNTIME140D.dll` import, adds exactly `GlassApplication: created.` and CRLF); both queries zero and `GWIN_OK`; no delegate kept; no teardown event; every preference message calls `preferences_changed` as before; the thread stays in the MTA |
| `WinPreferenceToolkitLifecycleTest` | 6 | The toolkit thread already in an STA (`RoInitialize` answers `S_FALSE`, the SWT-embedded path): nothing on stderr, both sinks registered and delivering 4 and 8 on the toolkit thread, events 1 to 6, live queries carry values, the thread stays in its STA after the toolkit's `RoUninitialize` and `OleUninitialize`. A second toolkit in one process, on a new thread and on the same thread after its loop returned: WinRT is initialised again, its own registrations deliver (the first toolkit's kept delegates are dropped first), its queries carry values, events 1 to 6 each |
| `WinPreferenceQueryGoldenTest` | 1, skipped by default | With `-Dwin.glass.prefsGolden=verify` (or `capture`): both query structs and their statuses byte for byte against `win-prefs-query-golden-5a709c189d.txt` (39 lines, sha256 `bf29c9f0...`), captured from the C++ on Windows 10.0.19045.6466 (light theme, accent `0xff0078d7`, network unrestricted) and compared only where its `machine.*` keys match |

Helpers: `WinSteppedToolkit` (134 lines; one toolkit at a time) and `WinAppCreateStandardError` (204 lines); 136 and
205 in `7c38bdc73a`.

**Crate unit tests** (`glass-win`, run by `jfx_rust_test` in the graphics `test` phase): 35 in each feature set (24 in
`prefs.rs`, 11 in `ffi.rs`); 32 in `7c38bdc73a`. The code review of 2026-11-27 added
`ffi::tests::a_collect_that_panics_answers_none_and_poisons_the_live_handle`,
`ffi::tests::a_query_on_a_poisoned_live_handle_writes_zeros_and_answers_ok` and
`prefs::tests::run_deferred_delivers_also_when_a_panic_poisoned_the_live_handle`, and rewrote
`the_seam_answers_0_for_a_null_handle_and_destroy_ignores_it` as
`the_seam_delivers_without_any_handle_and_destroy_ignores_null`; `set_callbacks_...` and `setting_changed_...` call the
new signatures. A test that installs the process-wide callback table holds `doubles::lock_table()`, a `TableLock`
whose `Drop` clears the table with the lock still held (`gwin_prefs_set_callbacks(NULL, NULL)`, status ignored, no
assertion), so a failing test leaves no table installed for the next one.

**Proven against the C++ of `5a709c189d`** (Windows 10.0.19045.6466). The dispatch trace (then 8 tests), the
activation-failure test and the golden were written and passed on the unchanged tree before any native change: 13
temporary C++ mutants each failed a test with a message naming the difference and were reverted; 10 runs in a row
passed; verify mode passed 9 times, and a golden with one byte flipped failed. The tests added later (the live-query
test, the lifecycle class, the stderr rule by build kind) passed against the baseline's test glass.dll (sha256
`63f928cd...`, selected with `-Djfx.headless.args=-Djava.library.path=<its directory>`): activation failure
5 / 0 / 0 / 0, dispatch trace 9 / 0 / 0 / 0, lifecycle 6 / 0 / 0 / 0. On the port, the child values of the dispatch
trace, both no-slot traces and the activation failure equal those captured from the C++ key for key, with stderr
byte-identical.

**Proven against the port:**
- The 13 mutants of the C++ run, mirrored into the port: each caught by the same test.
- 19 more mutants: 10 caught by Java tests, 1 only by a crate unit test (X7, `Variable` and `Fixed` swapped in the
  cost mapping: this machine's network is unrestricted), 8 by nothing. Of the 8, X14 (`RO_INITIALIZED.store(false)`
  removed, `prefs.rs:272`) is caught since `WinPreferenceToolkitLifecycleTest` exists: five checks of each
  second-toolkit test fail, among them `expected: <1,2,3,4,5,6> but was: <6>`. The other 7, caught by nothing in that
  run and not run again against the tests added later, are filed as US-132: X2 (both `remove_*` calls deleted,
  `prefs.rs:245` and `:251`), X6, X9, X18 and X19 (`glass_prefs_run_deferred()` removed from `EnterNestedEventLoop`,
  the `WM_PREFERENCES_CHANGED` wake arm, `gwin_run_loop`'s pump and `WM_DO_ACTION_LATER`), X16 and X17
  (`glass_prefs_discard_deferred()` removed from `WM_CREATE` and `WM_NCDESTROY`). X2, X6, X9 and X14 each ran beside a
  mutant that a test caught, X16 together with X17.
- For the tests added in the rework, one mutant each: only `S_OK` counted as success in `sys.rs` fails the STA
  scenario; X14 above; the live query answering the default struct fails the live-query test; an extra
  `fputs("\n", stderr)` in `glass_prefs_stderr` fails the activation-failure test, on Release and on Debug.
- The port's own: the ABI check fails for a changed export, seam function, import and constant; a C++ definition put
  back fails the link with LNK2005 (with LTO and one codegen unit the crate is one object, so the second definition
  clashes as soon as anything pulls that object); a temporary panic in an export, a seam function and a delegate
  closure leaves the JVM running, gives the failure value and writes one `jfx-rust: panic at` line each; sink bits, the
  C++ `& GWIN_PT_ALL`, the teardown order and an added newline each fail a test. The Rust `& 15` in `KeptTypes::take`
  is caught only by the crate's unit tests.
- In `7c38bdc73a`, superseded by P1 and P2 of the code review below: a temporary panic in `Preferences::create`
  after both activations: `gwin_app_create` still answers a toolkit window, the unwind runs the full teardown (events
  1 to 6), the handle is NULL, so the queries answer 0 and zeros and no message or sink reaches
  `preferences_changed`; the child exits 0 with one panic line and no `hs_err`. At the top of `collect_ui_settings`:
  that query and every later one on the handle, network too, answer -1 and write nothing; after teardown 0 and zeros;
  nothing is delivered through `glass_prefs_update`; teardown logs no event and the thread stays in its STA; both
  children exit 0 with one panic line each.
- The fallback texts: a C++ harness printing `_com_error(hr).ErrorMessage()` (MSVC 14.44, `/DUNICODE /D_UNICODE`) and
  the Rust pipeline agree code unit for code unit for 0x80040201, 0x8004FFFF, 0x80040200, 0x80050000, 0x00040201,
  0x80010106, 0x80042000, 0x8004F000, 0x8004FE00 and 0x80040203.
- The build: raw-dylib imports in a staticlib linked by `link.exe`; an `INTERNAL` symbol also defined in C++;
  `INTERNAL` and `IMPORTS` symbols no header declares; the completeness check both ways; an `IMPORTS` prototype that
  differs (C2733); the isolation of the two feature sets; `jfx-rust-port` §13.1 and §13.2 for the probes; the licence
  gate; then the toolchain message for a slice with and without its C; Linux `--require-defined=` for `IMPORTS`
  (WSL: `required symbol 'jfxm_proof_import' not defined`); the `-D` prefix; two keywords across slices; colliding
  feature sets; a missing `[defines]` entry. Each failed as intended and was reverted.
- Not attempted: the order of the two stores in `gwin_prefs_set_callbacks` (not observable on one thread), a delegate
  kept although `add_*` failed and `available` on a failed profile query (both need fault injection), and a seam
  function without its guard.

### Acceptance criteria
- **`dumpbin /exports` identical, `gwin_abi_version()` unchanged:** met. The sorted name lists of release glass.dll
  (97) and the test build (123) are `cmp`-identical to the baseline's, with dumpbin's `number of functions` equal to
  its `number of names`; also in the Debug build. `GLASS_WIN_ABI_VERSION` is 7 in `glass_win_api.h` and
  `WinGlassNative.ABI_VERSION` 7, both unchanged.
- **`WinPreferencesNativeTest` (28) and `WinPreferencesParityTest` (7) unchanged and passing; slice-1 goldens exact:**
  `WinPreferencesNativeTest` met (28 / 0 / 0 / 0, file unchanged). `WinPreferencesParityTest` is unchanged and not run:
  it is in `tests/system`, which only the owner runs. The goldens are exact: the dispatch trace in every run, and the
  query golden in verify mode (1 / 0 / 0 / 0 after the final gate, its sha256 the same before and after).
- **US-039 part 2's teardown tests pass unchanged against the Rust slice, with the same child-JVM run count:** met on
  Windows. `WinPreferenceSinksNativeTest` (across teardown, only on the toolkit thread, `Platform.exit()` from a
  listener, the release before `RoUninitialize`) is unchanged and passes 10 / 0 / 0 / 1; an unchanged file starts the
  same child JVMs (not counted separately).
- **The four files deleted:** met.
- **Every export and both delegate bodies guarded, answering the C++ fallback:** met by reading and by the panic
  proofs above; the seam functions the crate defines are guarded too. Where the C++ had no `catch` the values are
  those under "Decisions".
- **`unsafe` only in the boundary module; clippy and rustfmt clean:** met. 40 `unsafe` blocks, each with a
  `// SAFETY:` comment: 19 in `sys.rs`, 21 in `ffi.rs` (13 in both feature sets, 6 only with `test-hooks`, 2 in the
  unit tests' doubles); 0 in `prefs.rs` and `lib.rs`. Besides them `ffi.rs` has the two `unsafe extern "C"` blocks of
  the imports. No `unsafe impl`, no `unsafe fn`, no `#[global_allocator]`. fmt and clippy `-D warnings` pass for both
  feature sets in the final gate.

### Gates
**Baseline** at `5a709c189d` and **final gate** on the finished tree, Windows 10 10.0.19045.6466 (Maven 3.10.0,
Temurin 27+35, cargo and rustc 1.100.0, CMake 3.24.2, MSVC 14.44.35207), both
`mvn -B -ntp -pl buildtools/jslc,modules/javafx.base,modules/javafx.graphics,modules/javafx.media clean verify
-Djfx.parity.require=true`. Baseline: exit 0, 701 s, `git status --short` empty before and after. Final: alone on
the machine after the last source change, exit 0, 592 s, HEAD unchanged.

| Module | Baseline | Final | Classes (baseline / final) |
| --- | --- | --- | --- |
| jslc | 437 / 0 / 0 / 3 | 437 / 0 / 0 / 3 | 36 / 36 |
| base | 5510 / 0 / 0 / 22 | 5510 / 0 / 0 / 22 | 290 / 290 |
| graphics | 26877 / 0 / 0 / 905 | 26898 / 0 / 0 / 906 | 640 / 644 |
| media | 41 / 0 / 0 / 2 | 41 / 0 / 0 / 2 | 9 / 9 |

- Per class, jslc, base and media are identical to the baseline; in graphics the only difference is the four new
  classes: `WinPreferenceActivationFailureTest` 5 / 0 / 0 / 0, `WinPreferenceDispatchTraceTest` 9 / 0 / 0 / 0,
  `WinPreferenceQueryGoldenTest` 1 / 0 / 0 / 1, `WinPreferenceToolkitLifecycleTest` 6 / 0 / 0 / 0 (21 tests, 1
  skipped).
- The Rust steps in the final gate's log: `jfx_rust: rustc 1.100.0 (0ef1bc386 2026-11-09)`; the origin checks name a
  `glass_win` archive member for each of the 11 symbols of `glass` and the 13 of `glass_test`; `jfx_rust_test` as
  above; `jfx_rust_lint`: fmt, clippy for both feature sets, "the glass-win crate declares exactly the 15 symbol(s) of
  its slices: 5 EXPORTS, 6 INTERNAL, 4 IMPORTS; 5 more inside #if blocks that are false for this feature set", "the
  glass-win crate with the features test-hooks declares exactly the 20 symbol(s) of its slices: 7 EXPORTS, 6 INTERNAL,
  7 IMPORTS", the licence self-test (68 cases), `check-crate-licences: OK, 13 linked crate(s), 6 build-time crate(s)`
  (glass-win and jfx-rust-support for javafx.graphics, the nine `windows*` crates, jfx-rust-support and jfxmedia for
  javafx.media; build time: proc-macro2, quote, syn, unicode-ident, windows-implement, windows-interface). javafx.media:
  `jfxm_abi_version in Rust; 58 export lines in its headers` and "the jfxmedia crate declares exactly the 1 EXPORTS
  symbol(s) of its slices".
- Exports: release glass.dll 97, test build 123, jfxmedia.dll 58, each sorted list `cmp`-identical to the baseline's.
  Dependents of both glass.dll builds: deviation 2, nothing removed, delay-load list unchanged. jfxmedia.dll: exports,
  dependents and imports dumps identical to the baseline's, 229,888 bytes as before (not byte-identical: the linker's
  output is not reproducible).
- md5 lists: the 532 generated sources and the 68 compiled classes of `com/sun/glass/ui/win`, both `cmp`-identical to
  the baseline's.
- The golden afterwards: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.base,modules/javafx.graphics test
  -Dtest=WinPreferenceQueryGoldenTest -Dsurefire.failIfNoSpecifiedTests=false -Dwin.glass.prefsGolden=verify`,
  1 / 0 / 0 / 0.
- No `hs_err_pid*` in the four module directories. The sha256 manifest of every modified or new file outside the
  vendored tree, an aggregate hash of the vendored tree, and `git status --short` (34 lines) were the same before and
  after the gate.
- Earlier full runs of the same command, superseded: with the build infrastructure and only the two probes in Rust,
  and after the port (each exit 0, graphics 26891 / 0 / 0 / 906 in 643 classes); before the fix round (exit 0, 661 s,
  graphics 26898 / 0 / 0 / 906 in 644).

**WSL Ubuntu**, on the final tree (after the fix round and this write-up), in the clone `~/us029-final`. Its
`git diff HEAD --binary` is `cmp`-identical to the Windows tree's (28 files), and so is the sorted `sha256  path` list
of its untracked files (1,087 files, 1,068 of them vendored), also after the builds; `buildtools/rust/jfx_rust.cmake`
has the same sha256 on both sides.
- `clean verify -DskipTests` of the four modules: exit 0. The graphics tree prints "jfx_rust_lint: no Rust slice is
  enabled in this tree"; media configures with `jfxm_abi_version in Rust; 58 export lines in its headers` (61 for
  `jfxmedia_test`), and its lint passes ("the jfxmedia crate declares exactly the 1 EXPORTS symbol(s) of its slices",
  the licence self-test's 68 cases, `check-crate-licences: OK, 13 linked crate(s), 6 build-time crate(s)`).
- `mvn -pl modules/javafx.media -am verify`: exit 0; media 41 / 0 / 0 / 2, and on Linux jslc 437 / 0 / 0 / 0, base
  5510 / 0 / 0 / 22, graphics 26642 / 0 / 0 / 1043. An earlier Linux run of this story, before the rework of the tests,
  gave graphics 26635 / 0 / 0 / 1036: the 7 more tests, all skipped on Linux, are the 6 of
  `WinPreferenceToolkitLifecycleTest` and the ninth of `WinPreferenceDispatchTraceTest`.
- `libjfxmedia.so`: "58 names in the header, 58 exported", diff empty; `ldd -r` reports no undefined symbol and no
  missing library.

**Debug** (`-DCONF=Debug`, Windows): both dev archives build, the origin checks list 11 and 13 symbols, `jfx_rust_test`
passes 32 / 32 in both feature sets, the lint passes, and the Debug DLLs export the 97 / 123 names of the baseline.
The first test run there failed `roInitializeFailingWritesItsSystemMessageToStandardErrorWithNoLineBreak`
(5 / 1 / 0 / 0): a Debug glass.dll writes `GlassApplication: created.` after the `RoInitialize` line (`STRACE` at
`GlassApplication.cpp:233`, which `OleUtils.h:47-48` sends to stderr only with `DEBUG` or `_DEBUG`; the same line at
`5a709c189d`). The test now expects it by build kind; a clean Debug build then gave 5 / 0 / 0 / 0, 9 / 0 / 0 / 0 and
6 / 0 / 0 / 0, and Release was restored (350,208 / 371,200 bytes, 97 / 123 exports). The Debug build was made in the
rework round, before the fix round, and the WSL runs above on the tree committed as `7c38bdc73a`; neither was
repeated after the code review of 2026-11-27.

**Gate after the code review of 2026-11-27**, Windows 10 only, alone on the machine after the last code change and
after the confirming review, the same command as the final gate: exit 0, 594 s wall. Totals identical to the final
gate's (jslc 437 / 0 / 0 / 3, base 5510 / 0 / 0 / 22, graphics 26898 / 0 / 0 / 906, media 41 / 0 / 0 / 2), and the
four per-class tables (36, 290, 644 and 9 classes) `cmp`-identical to its. The preference classes:
`WinPreferencesNativeTest` 28 / 0 / 0 / 0, `WinPreferenceSinksNativeTest` 10 / 0 / 0 / 1,
`WinGlassNativeTest` 27 / 0 / 0 / 0, `WinPreferenceDispatchTraceTest` 9 / 0 / 0 / 0,
`WinPreferenceActivationFailureTest` 5 / 0 / 0 / 0, `WinPreferenceToolkitLifecycleTest` 6 / 0 / 0 / 0,
`WinPreferenceQueryGoldenTest` 1 / 0 / 0 / 1. glass-win `cargo test` 35 passed in each feature set, jfx-rust-support
9, 2, 1, 1; the lint's 15 and 20 symbols as above;
`check-crate-licences: OK, 13 linked crate(s), 6 build-time crate(s)`; no `[ERROR]` line. Release glass.dll 97 and
test build 123 exports, both sorted lists `cmp`-identical to the final gate's; 345,600 and 366,592 bytes. The md5
lists of the 68 classes of `com/sun/glass/ui/win` and of the 532 generated sources `cmp`-identical. The golden in
verify mode afterwards: 1 / 0 / 0 / 0. No `hs_err_pid*` in the four module directories. The checked-out commit
(`7c38bdc73a`) and the sha256 of the 12 modified files the same before and after. Logs, outside the repository: the
session scratch directory `cr029\gate\` (`gate.log`, `golden-verify.log`, `totals.txt`, `per-class-*.tsv`).

### Reviews and analysis
- **Three independent reviews** of the port as first gated:
  - **Port and tests:** "I found no wrong behaviour on any reachable path and no unsoundness." Must-fix: only the
    missing WSL run after the port. Should-fix: the headers did not say what a poisoned handle answers, the
    deviation list lacked deviations 3, 5 and the whole of 9, the Java comment pass was not done, and no test pinned
    `RoInitialize` answering `S_FALSE`. Notes: one seam comment, the scope of poisoning, and that the module suite
    loads only the test build of glass.dll. The decisions were judged right, the agile delegates risky and the
    owner's call, the test plan right but for the `S_FALSE` gap.
  - **Build side:** pass, 4 should-fix, 10 notes. Checked: every vendored file against its `.cargo-checksum.json`,
    each crate's checksum and version against `Cargo.lock`, the licence texts, the source replacement, javafx.media's
    unchanged path through every changed helper function on Windows, Linux and macOS, `INTERNAL` on Linux and macOS,
    the two feature sets, `abi_check.cpp`; the checksums and the two archives' symbols measured, the rest by reading.
  - **End to end with mutants:** "I found no defect in the port code itself"; must-fix for the test gaps above.
- **Taken** (one rework round): the WSL run; the header comments on a poisoned handle; the seam comment; the proofs
  of a panic in create, a panic in a query and the fallback texts; the Java comment pass; the `S_FALSE` and
  second-toolkit tests; the live-query assertion; the toolchain message for a `C_DELETED` slice; Linux
  `--require-defined=` for `IMPORTS`; the `-D` prefix, the two-keyword check per target, the feature-set collision,
  the `Missing [defines] entry` failure and the status-line wording; the licence script's usage text and the
  `submit.yml` comments; the Debug build, the rebuild of both archives after a touch, `cargo metadata` offline with and
  without `--config`; and, in this write-up, `README.md`, the deviation list and the skills.
- **Declined, with the reason:**
  - A CI export check of glass.dll: a seam function listed under `EXPORTS` is already a configure error (the seam
    header has no export line), a test hook listed for the release target fails the base-set completeness check, the
    header's export list did not change, and the local gate compares both export lists with the baseline.
  - A declaration under an `#if` that is false for a feature set is never prototype-compared: `EXPORTS` had the same
    gap at `5a709c189d`; here every seam declaration is unconditional except the three probes, which are compared in
    the test-hooks set; closing it needs a generated header that every crate's `abi_check.cpp` includes, javafx.media's
    too.
  - Narrower poisoning: consistent with P4, reachable only through a Rust bug, and documented in the headers.
  - The `S_FALSE` scenario on a worktree build of `5a709c189d`: the baseline's test glass.dll is that build and served
    instead.
  - No action: the module suite loads only the test build (the owner's `tests/system` run loads the release build);
    two binary files in the vendored tree (`unicode-ident/tests/fst/*.fst`; jcheck only warns); the `windows*`
    checksums are verified only by the Windows job.
- **Confirming reviews:** build side pass, nothing at must-fix or should-fix, five notes; port side pass, with two
  should-fix comment findings (two Java comments naming deleted C++ in the present tense; two helper contracts untrue
  once a test creates a second toolkit) and four notes (an `ffi.rs` doc line; the second toolkit's sink assertions
  could pass through the first toolkit's kept delegate; byte equalities between two child JVMs; the optional comment
  on poisoning).
- **Fix round:** comment edits (`ffi.rs` doc comments, `glass_win_api.h` on `gwin_test_prefs_capture`, the helper's
  status-line comment, the Java comments and helper contracts) and test changes (kept delegates dropped before
  each second toolkit; the cross-child byte equalities removed; one test renamed); no production code line changed.
  Two of the rework's mutants were run again: X14 fails five checks per second-toolkit test, only-`S_OK` still fails
  the STA scenario; against the baseline's test glass.dll 5 / 0 / 0 / 0, 9 / 0 / 0 / 0, 6 / 0 / 0 / 0.
- **Not taken from the confirming reviews:** the macOS messages of the helper still offer `-DJFX_RUST=false` without
  checking `C_DELETED` (no macOS slice is `C_DELETED` today); the toolchain check runs once per tree, so its first
  slice decides the remedy (no tree mixes a slice with its C and a `C_DELETED` one today); the optional comment on
  poisoning.

### Code review of 7c38bdc73a (2026-11-27)
A code review of the commit reported five findings on the crate and its tests. Each was decided as below; the fixes
are committed as `fcfc41cf48`.

- **F1**, after a caught panic the toolkit stops delivering every preference change: fixed (D1, below). The C++
  delivered without its object's state: `PlatformSupport::updatePreferences` and `onSettingChanged` were `const`
  members that read only the static callback table, and `RunDeferredPreferences` counted, then delivered whenever a
  `PlatformSupport` existed (`PlatformSupport.cpp:277-326` at `5a709c189d`). In `7c38bdc73a` a NULL or poisoned
  handle made `glass_prefs_update`, `glass_prefs_setting_changed` and `run_deferred` deliver nothing.
- **F2**, `RoUninitialize` follows the process-wide `RO_INITIALIZED` flag, not the handle that initialised: no change
  here, filed as US-133. The flag is the C++'s (`RoActivationSupport.cpp:42` at `5a709c189d`) and the port reproduces
  it (`jfx-rust-port` §2.9, §6 "port and fix").
- **F3**, glass.dll needs `combase.dll` and `api-ms-win-core-winrt-l1-1-0.dll` at load time: no change. It is
  deviation 2, with the `ole32.dll` evidence and the API-set probe added there.
- **F4**, a failing Rust unit test leaves the process-wide callback table installed for the next test that makes an
  update: fixed (D4, below).
- **F5**, new comments only restate the code: 14 deleted (4 Rust, 10 Java), 5 kept.
  `WinGlassNativeShim.comApartment()`'s comment names the OS call, whose name differs from the method's; the
  `void CoUninitialize(void)` comment on its binding stays because every binding of the shim carries its C prototype
  (already at `5a709c189d`); the section dividers `ffi.rs:99` and `:146` are like the file's other three;
  `glass_win_api.h:3140` is one cell of an enumerator column to which lines 3141 and 3142 refer back.

**D1.** The delivery of preference types no longer depends on the handle, and a query on a poisoned handle answers
what a toolkit without WinRT objects answers. Making delivery independent of the handle alone would not have been
enough: Java's upcall `WinPreferenceCalls.onPreferencesChanged` runs `WinPreferences.update(types)`, whose
`collect(types)` calls `gwin_prefs_query_ui_settings` and `gwin_prefs_query_network` for the `GWIN_PT_UI_SETTINGS`
and `GWIN_PT_NETWORK_INFORMATION` bits through `requireOk`, which throws on the `GWIN_ERR_INVALID_ARG` a poisoned
handle answered; every `types` value the window procedure sends except 2 carries one of those bits
(`GlassApplication.cpp:276, 298, 322`). **D4.** `doubles::lock_table()` answers a `TableLock` (under "Tests").

**What changed, by file** (12 modified, nothing new or deleted; `git diff --stat`: 133 insertions, 96 deletions):
- `glass_win_prefs.h`: `glass_prefs_update` and `glass_prefs_setting_changed` without their `void* handle`; the file
  comment and the comments of those two and of `glass_prefs_run_deferred` follow the code.
- `GlassApplication.cpp:276, 298, 322`: the three calls drop `m_platformSupport.Get(), `.
- `glass_win_api.h:623-626`: the query contract, comment lines only; `GLASS_WIN_ABI_VERSION` 7.
- `ffi.rs`: `query_live` answers `T` with `none` as the failure value of `guard_handle`, so both queries write zeros
  and answer `GWIN_OK` for a poisoned live handle; `out == NULL` and the outer guard still answer
  `GWIN_ERR_INVALID_ARG`. `glass_prefs_update` and `glass_prefs_setting_changed` call `prefs::update_preferences` in
  `guard(0, ...)`; `update_on` is deleted, `usable` stays for `glass_prefs_destroy`. The test doubles gain `TableLock`
  and `poisoned()`; two tests added, three rewritten; two comments deleted.
- `prefs.rs`: `run_deferred` counts `GWIN_TEST_COUNTER_PREFS_DEFERRED_RUN` and calls `update_preferences(types)` when
  the live handle is not NULL, poisoned or not, as `RunDeferredPreferences` did; one test added; one comment deleted.
- `sys.rs`: one comment deleted.
- `WinGlassNativeShim.java` (2), `WinAppCreateStandardError.java` (1), `WinPreferenceActivationFailureTest.java` (2),
  `WinPreferenceDispatchTraceTest.java` (2), `WinPreferenceToolkitLifecycleTest.java` (1), `WinSteppedToolkit.java`
  (2): one-line Javadoc comments deleted, nothing added.
- Unchanged: every export's name and prototype, the slice lists of `win.cmake` (the lint still names 15 and 20
  symbols), `RO_INITIALIZED`, the sinks, the kept word, `glass_prefs_create`, `glass_prefs_destroy`; 40 `unsafe`
  blocks (21 in `ffi.rs`, 19 in `sys.rs`), each with its `// SAFETY:` comment.

**Proofs.** Every mutant and temporary panic was reverted, with the sha256 lists of the changed files the same before
and after.
- The implementer's: **M1** (`query_live` back to `Option<T>` with `let Some(..) else` in the exports): 33 passed, 2
  failed, `a_query_on_a_poisoned_live_handle_writes_zeros_and_answers_ok` with "left: -1 right: 0"; after the collect
  test was changed to compare the answer directly, the mutant no longer builds the test crate
  (`error[E0308]: mismatched types` at `ffi.rs:672:79` and `ffi.rs:679:17`). **M2** (`run_deferred` skips a
  poisoned live handle): `run_deferred_delivers_also_when_a_panic_poisoned_the_live_handle` fails,
  "left: [LiveHandle, Count(11)] right: [LiveHandle, Count(11), Changed(4)]". **M3** (`--test-threads=1`, a temporary
  failing assertion in `set_callbacks_...`): with `TableLock` only that test fails; with its clearing `Drop` removed
  `update_calls_the_installed_slot_once_and_answers_whether_it_reported_a_change` fails too, at `prefs.rs:858`,
  "left: [Changed(4)] right: []". **M4** (`void* handle` back in the header's `glass_prefs_update` only): the lint
  fails in both feature sets,
  `error C2733: 'glass_prefs_update': you cannot overload a function with 'extern "C"' linkage`.
- End to end, a temporary `panic!` in the test build of glass.dll, `WinPreferenceDispatchTraceTest`: **P1**, at the
  top of `Preferences::collect_ui_settings`: 9 tests, 3 failures, each the poisoning's expected effect (the live query
  carries zeros, "GwinUiSettings.available is 0, not 1, ..."; no teardown event, as the poisoned handle is left alone;
  the apartment after the loop, as `RoUninitialize` never ran). In the child both live queries answer status 0 with
  every byte zero, the four sink deliveries, which the implementer places after the poisoning query, reach
  `preferences_changed` on the toolkit thread (`sink.*.delivered=true`, `thread=toolkit`), stderr holds exactly one
  `jfx-rust: panic at ...prefs.rs:180:9: P1: ...` line, and the child exits 0 without `hs_err`. **P2**, in
  `Preferences::create` after both activations: 9 tests, 2 failures (the live query carries zeros;
  `apartment.afterCreate` is `CO_E_NOTINITIALIZED`, as the unwind ran the teardown); every preference message reaches
  `preferences_changed` with a NULL handle, the sink and teardown tests pass (events 1 to 6), the queries answer
  status 0 with zeros, one panic line per child, exit 0, no `hs_err`.
- The confirming review's, each killed: **R1** and **R2** (a query answers `GWIN_ERR_INVALID_ARG` on a poisoned live
  handle) at `ffi.rs:686` and `:689`, "left: -1 right: 0"; **R3** (`glass_prefs_update` answers 0 for a poisoned
  live handle) at `ffi.rs:703` and `:723`; **R4** (the same in `glass_prefs_setting_changed`) at `:703` and `:737`;
  **R5** (`query_live` with `guard` instead of `guard_handle`, so nothing is poisoned) at `ffi.rs:670`; **R6**
  (`run_deferred` neither counts nor delivers for a poisoned handle) at `prefs.rs:815`; **R7** (`TableLock::drop`
  clears only when the thread is not panicking, `--test-threads=1`) survived with its injected failure in
  `ffi::tests::the_seam_...`, because later tests overwrite and clear the table first, and **R7b**, the same mutant
  with the injection at `prefs.rs:795`, was killed at `prefs.rs:858`, "left: [Changed(4)] right: []"; with `TableLock`
  intact the same injection fails only the injected test. Negative control: the panicking `collect` of
  `a_collect_that_panics_answers_none_and_poisons_the_live_handle` replaced by a plain one fails it at `ffi.rs:668`.
- **Review verdict:** pass, no must-fix and no optional finding; 35 / 35 in both feature sets after the last restore,
  fmt and clippy `-D warnings` clean in both, exports 97 / 123 `cmp`-identical to the final gate's.
- **Gate:** "Gate after the code review of 2026-11-27" under "Gates".

Logs, outside the repository, in the session scratch directory `cr029\`: `porter\` (M1 to M4, P1, P2), `review\`
(R1 to R7b, the negative control, `ole32-dependents.txt`, `glass-dependents.txt`), `gate\`; the baseline glass.dll's
dependents are in the scratch directory of the port's own gates, `us029\baseline\glass-release-dependents.txt`.

### Not verified, not done
- **macOS:** nothing built or run. The helper's Darwin code changed for the new keywords (the ld64 origin check loops
  over `EXPORTS` and `INTERNAL`, `jfx_rust.cmake:592`; the `macos-<version>` directory below a feature set's cargo
  directory, `:1156`; `-u,_<sym>` for `INTERNAL` too, `:1674`; `-DJFX_RUST_INTERNAL=` passed to the check, `:1717`),
  and javafx.media's macOS slice runs through it. CI only.
- **CI:** no run with this change. Linux aarch64 not built.
- **Linux `--require-defined=` for `IMPORTS`** was proven only with a proof symbol: no Linux registration lists
  `IMPORTS`.
- **`tests/system` and robot tests:** none (the owner runs them). Only those load the release glass.dll.
- **The OS's real thread for the agile delegates** was not observed; the owner's `manualSinkRun` command below, with
  a settings change during the run, shows it.
- **Fault-only paths:** combase load failures, `RO_CHECKED` failures and a sink firing during `gwin_app_create` (no
  fault injection); the C++'s "answer nonzero returns 0, else `DefWindowProc`" is not observable (`DefWindowProc`
  answers 0 for these messages); `WM_THEMECHANGED` after a poisoning query was not sent.
- **A Debug build of the C++** was not run; the Debug stderr rule is proven on the port's Debug build only.
- **`mvn install`:** not run; every gate ran `verify`.
- **The code review of 2026-11-27 ran on Windows only:** no WSL run (the changed production code is Windows-only: the
  `glass-win` crate is `#![cfg(windows)]` and the C++ is glass win; the Java changes are ten deleted comment lines),
  and no Debug build. Nobody ran a robot or `tests/system` test in that round; the owner's commands below name no
  symbol the round changed.

### Follow-up
- **US-132** (filed): the seven mutants above that no test caught, at sites the port touched but US-039 designed.
- **US-133** (filed 2026-11-27, finding F2 of the code review): every successful `RoInitialize` of a toolkit thread
  balanced by exactly one `RoUninitialize` on that thread, by the handle that made it; inherited from the C++.
- **Upstream, not filed:** defects 1 to 5 are in upstream's code too: at openjdk/jfx `ca9b07aeae` (8390652, the newest
  upstream commit in this history) `RoActivationSupport.cpp` is as `76d5e1ae1c` (8343398) left it and
  `PlatformSupport.cpp` as `436f3ffe3d` (8365763) left it, with the same lines; defects 1 to 4 date from 8301302
  (`170a4c1545`), defect 5 from 8343398. Current upstream master was not fetched. A draft of the upstream issue, with
  the quoted lines, the `comdef.h` lines of the use after free, the reachable path and the smallest C++ fix for each,
  was handed to the owner, who files it; it is kept outside the repository.
- **Stale comments in files the acceptance criteria keep unchanged:** `WinExternalPumpToolkit.java:37`,
  `WinPreferenceSinksNativeTest.java:60`, `:83`, `:87` and `WinPreferencesNativeTest.java:524` (`GetPlatformSupport()`,
  now `GetPreferencesHandle()`) in the module tests; `WinApplicationStartupTest.java:127` (an assertion message says
  "PlatformSupport's") and `WinPreferencesParityTest.java:63` (history) in `tests/system`.

### Left for the owner
- **Done 2026-11-27, the commit and the PR:** the port is `7c38bdc73a`; the code review's fixes (12 code files) with
  this write-up's backlog changes (this story, `backlog/README.md`, US-030, US-031, US-052 and the new US-133) are
  `fcfc41cf48`; both are in PR #76 (open).
- **A green `submit.yml` run** in all jobs, macOS included: the helper's Darwin code changed and could not be built
  here. To look at: the Windows job builds glass.dll from the vendored crates offline and runs the graphics Rust test
  and lint; the Linux jobs' media lint and `cargo metadata` pass through the source replacement; the jfxmedia export
  checks still print `58 names in the header, 58 exported` on Linux and Windows; the macOS jobs build their media
  slice.
- **The owner-only runs**, the `tests/system` preference tests (the only run that loads the release glass.dll):
  ```
  mvn -pl tests/system -am test -DFULL_TEST=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest='test/com/sun/glass/ui/win/WinPreferencesParityTest,test/com/sun/glass/ui/win/WinApplicationStartupTest'
  ```
  and, with the auto-hide scroll-bar setting toggled or a network change during the run, the evidence for the agile
  delegates' thread:
  ```
  mvn -pl modules/javafx.graphics test -DskipNative=true -Dtest=WinPreferenceSinksNativeTest#manualSinkRun -Dwin.glass.manualSinkSeconds=60
  ```
  The golden check, on its capture machine only:
  ```
  mvn -B -ntp -pl buildtools/jslc,modules/javafx.base,modules/javafx.graphics test -Dtest=WinPreferenceQueryGoldenTest -Dsurefire.failIfNoSpecifiedTests=false -Dwin.glass.prefsGolden=verify
  ```
- **The deviations** 1 to 10 above, accepted or rejected; deviation 10 changed with the code review of 2026-11-27.
- **The stale test comments** above, in a later change; and filing the upstream issue for defects 1 to 5 from the
  draft handed to the owner.
- **Clean-up outside the repository:** the WSL clones `~/us029-a2` and `~/us029-final` (1.3 GB each); the std
  components of the two macOS targets, which `rust-toolchain.toml` names and which rustup installed into WSL's
  `~/.rustup` on a `cargo --version` without `RUSTUP_TOOLCHAIN` (and into the Windows `~/.rustup` on a plain cargo
  call in the baseline run); and `target/.rustc_info.json`, which `cargo vendor` wrote into the repository root's
  ignored `target/`.
- After the merge: the CI record here and in `backlog/README.md`, the Done status, and the row moved to the done table.

### Definition of Done
- Merged PR: open, for the owner.
- Verified on Windows, the module suite: yes (final gate, and the gate after the code review of 2026-11-27); the
  `tests/system` Windows preference tests: open, for the owner.
- The C is deleted: yes.
- The WSL Linux build still configures: yes, on the tree of `7c38bdc73a`, and javafx.media builds and passes there;
  not repeated after the code review, whose changes are Windows-only.
- `backlog/README.md` is updated: yes (this row, the "Rust port" table, "Order", the rows of US-030 and US-031, and
  US-132's row; after the code review the rows of US-029, US-030 and US-133, the "Rust port" table and "Order").
