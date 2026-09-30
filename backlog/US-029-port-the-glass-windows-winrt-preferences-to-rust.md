# US-029 — Port the Glass Windows WinRT preferences (PlatformSupport) to Rust behind the glass_win_api ABI

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read `glass_win_api.h:1-262,488-523,641-649` and
`PlatformSupport.cpp:80-140`, counted with `wc -l`/`grep -c`; `RoActivationSupport.cpp` and the two query bodies were
NOT read; nothing built) · **Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:**
US-027, US-039 part 2 (a C++ fix)

## Story
As a platform maintainer,
I want the WinRT half of the Windows platform preferences (`PlatformSupport.cpp`, `RoActivationSupport.cpp`) to be
Rust that exports the same five symbols,
so that WinRT activation, the two foreign-thread event sinks and their lifetime come from the `windows` crate's
generated projections instead of hand-written HSTRING/activation code and a raw `this`.

## Why Rust fits here
- **Must stay native (R1):** Java-side COM was considered for exactly this code and rejected: hard-coded IIDs,
  vtable slot indices and a fabricated vtable for the two event sinks, none testable here (`glass_win_api.h:54-56`).
- **Owned code (R2):** OpenJFX code; nothing vendored in `native-glass/win` (file headers not audited).
- **Buildable and testable here (R3):** Windows 10 + VS2022; `WinPreferencesNativeTest` (28 annotations) runs in the
  module suite and `WinPreferencesParityTest` (7) in `tests/system`.
- **Benefit (R4):**
  - The two sinks capture a raw `this` (`PlatformSupport.cpp:90,115`) and run on a WinRT thread
    (`glass_win_api.h:508-512`). Their tokens are discarded and nothing unregisters them (`glass_win_api.h:514-521`,
    `PlatformSupport.cpp:87,112,126-130`), so a sink firing after `~PlatformSupport` calls a destroyed object. The
    C++ fix this story is blocked by makes "unregistered on destruction" the C behaviour. In Rust the tokens
    live in the state and `Drop` removes them, and a delegate closure cannot capture a raw `this` without an
    `unsafe impl Send` that review rejects.
  - The `IActivationFactory*` is a raw pointer that is never released (`PlatformSupport.cpp:101-110`). In Rust
    every projected interface releases on `Drop`.
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
  in `Arena.global()` because nothing unregisters the sinks (`glass_win_api.h:514-521`).
- Activation runs inside `gwin_app_create`, on the thread that will call `gwin_run_loop`, before it:
  `RoInitialize(RO_INIT_SINGLETHREADED)`, `UISettings`, `NetworkInformation` and both sinks (`glass_win_api.h:641-649`).
  The queries run on the toolkit thread and block across apartments (`glass_win_api.h:565-566`).
- `PlatformSupport` is a member of `GlassApplication` (`GlassApplication.h:98`). The toolkit WndProc's
  `WM_SETTINGCHANGE`, `WM_THEMECHANGED`, `WM_SYSCOLORCHANGE` and `WM_DWMCOLORIZATIONCOLORCHANGED` arms call it.

## Approach
- ABI unchanged. One crate for all of glass.dll, in the US-027 layout
  (`modules/javafx.graphics/src/main/native-rust/glass-win/`). This story adds its `prefs` module; the staticlib
  links into the `glass` target.
- The `m_platformSupport` member becomes an opaque handle created and dropped through an internal, non-exported
  seam. The four WndProc arms and `gwin_app_create` reach the Rust side through that seam until the core story
  moves them.
- The structs behind the two `gwin_sizeof_*` probes become `#[repr(C)]` with `const` size and offset assertions.
- No new thread, lock or marshalling: the sinks keep running on whatever thread WinRT picks.

### Slices
1. **Goldens from the C**, before any Rust:
   - a recording `GwinPrefsCallbacks`;
   - both query structs captured byte for byte;
   - the four WndProc arms fired with `SendMessage` to the toolkit window, recording `types` per message.

   Record the commit, the Windows build and the theme settings.
2. **Queries**: `gwin_sizeof_*`, `gwin_prefs_query_ui_settings` and `gwin_prefs_query_network`. Gate: slice-1
   structs exact; `WinPreferencesNativeTest` and `WinPreferencesParityTest` green.
3. **Activation, sinks, installer**: `gwin_prefs_set_callbacks`, the activation called from `gwin_app_create`, the two
   sinks and the settings-change seam. Delete `RoActivationSupport.cpp/.h`. Gate: slice-1 traces, plus a sink event
   from a WinRT thread that reaches `preferences_changed` with the same `types`.
4. **Delete** `PlatformSupport.cpp/.h` and the slice's CMake option, in their own commit.

## Acceptance criteria
- `dumpbin /exports glass.dll` is identical before and after (106 exports), and `gwin_abi_version()` is still 6.
- `WinPreferencesNativeTest` (28) and `WinPreferencesParityTest` (7) are unchanged and pass; slice-1 goldens exact.
- `PlatformSupport.cpp/.h` and `RoActivationSupport.cpp/.h` are deleted.
- Every export and both delegate bodies are guarded with `catch_unwind` and answer the C++ fallback value.
- `unsafe` appears only in the boundary module; clippy and rustfmt are clean.

## Definition of Done
Merged PR. Verified on Windows (module suite plus the `tests/system` Windows preference tests). The C is deleted.
The WSL Linux build still configures (glass/win is not built there). `backlog/README.md` is updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | The Java stub is thread-safe for the threads WinRT uses today; a Rust lock or marshal would change timing. | No new locks or threads (port rule P6); the trace records the thread of each sink call. |
| 2 | `RoInitialize` must precede `gwin_run_loop`'s `OleInitialize` in the same apartment (`glass_win_api.h:646,655`). | Keep the call order; the slice-3 trace spans `gwin_app_create` → `gwin_run_loop`. |
| 3 | Theme-dependent goldens differ between machines. | Store the settings with the golden and compare the Rust build on the same machine. |

