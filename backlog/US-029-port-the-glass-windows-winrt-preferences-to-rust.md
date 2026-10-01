# US-029 — Port the Glass Windows WinRT preferences (PlatformSupport) to Rust behind the glass_win_api ABI

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read `glass_win_api.h:1-262,488-523,641-649` and
`PlatformSupport.cpp:80-140`, counted with `wc -l`/`grep -c`; `RoActivationSupport.cpp` and the two query bodies were
NOT read; nothing built. 2026-10-01, after PR review: the sink text follows US-039 part 2's design, in which the
sinks only post; re-read `PlatformSupport.cpp:36-47` and `:138-144`, and `GlassApplication.h:97-99` and `:128`) ·
**Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:** US-027, US-039 part 2 (a C++ fix)

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
    atomic HWND, so `preferences_changed` runs only on the toolkit thread. Teardown removes both registrations and
    releases the WinRT objects and the factory before `RoUninitialize`. Removing the registrations alone would not
    be enough, because `remove_*` does not wait for an invocation that has already started.
  - The port keeps that design, and Rust makes the unsafe variant hard to write. The delegate closures capture only
    their `PT_*` bits and post through the atomic HWND, so a late invocation reaches nothing `Drop` frees. `Drop`
    removes both registrations, then releases the objects before `RoUninitialize`. There is no lock, so port rule
    P6 holds trivially. A closure holding a raw `this` would need an `unsafe impl Send`, which review rejects.
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
- Five exports under `GLASS_WIN_ABI_VERSION 6`: `gwin_sizeof_ui_settings`, `gwin_sizeof_network_info`,
  `gwin_prefs_set_callbacks`, `gwin_prefs_query_ui_settings`, `gwin_prefs_query_network`.
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
- No thread, lock or marshalling (port rule P6, "No new concurrency", `backlog/README.md:120`). The delegates keep
  running on whatever thread the OS picks and only post, as the C does after US-039 part 2.

### Slices
1. **Goldens from the C**, before any Rust:
   - a recording `GwinPrefsCallbacks`;
   - both query structs captured byte for byte;
   - the four WndProc arms fired with `SendMessage` to the toolkit window, recording `types` per message.

   Record the commit, the Windows build and the theme settings.
2. **Queries**: `gwin_sizeof_*`, `gwin_prefs_query_ui_settings` and `gwin_prefs_query_network`. Gate: slice-1
   structs exact; `WinPreferencesNativeTest` and `WinPreferencesParityTest` green.
3. **Activation, sinks, installer**: `gwin_prefs_set_callbacks`, the activation called from `gwin_app_create`, the two
   sinks, the settings-change seam, and US-039 part 2's shim-only hook that invokes the registered delegates (a
   glass.dll export, which the crate takes over). Delete `RoActivationSupport.cpp/.h`. Gate: slice-1 traces, a sink
   event from a second thread that reaches `preferences_changed` on the toolkit thread with the same `types`, and
   US-039 part 2's teardown tests run against the Rust slice.
4. **Delete** `PlatformSupport.cpp/.h` and the slice's CMake option, in their own commit.

## Acceptance criteria
- `dumpbin /exports glass.dll` is identical before and after, and `gwin_abi_version()` is unchanged by this story.
  At commit `200192cfd8` that is 106 exports and ABI 6. US-039's shim-only test hooks add exports and US-049 part 1
  removes one and bumps to ABI 7; both move the starting point, not this story.
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
| 1 | After US-039 part 2 the stub is called only on the toolkit thread; a Rust lock, thread or extra marshal would change timing. | No lock or thread (port rule P6); the delegates only post; the trace records the thread of every `preferences_changed`. |
| 2 | `RoInitialize` must precede `gwin_run_loop`'s `OleInitialize` in the same apartment (`glass_win_api.h:646,655`). | Keep the call order; the slice-3 trace spans `gwin_app_create` → `gwin_run_loop`. |
| 3 | Theme-dependent goldens differ between machines. | Store the settings with the golden and compare the Rust build on the same machine. |
| 4 | The `windows` crate may cache activation factories for the process (from memory, not checked), so the factory would not be released before `RoUninitialize`. | Read the crate's generated factory accessor in slice 3. If it caches, activate through `RoGetActivationFactory` directly, as the C does. |

