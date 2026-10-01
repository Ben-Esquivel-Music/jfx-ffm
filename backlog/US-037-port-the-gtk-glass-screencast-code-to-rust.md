# US-037 — Port the GTK Glass screencast code to Rust behind the `screencast_api.h` ABI

**Status:** 🔶 Blocked (drafted 2026-09-30 from a read-only survey. Read: `screencast_api.h`, excerpts of
`screencast_pipewire.c` and sweeps of `screencast_portal.c`, counted with `wc -l` and `grep -c`. NOT checked: the
`screencast_portal.c` logic beyond counts, `screencast_api.c`, the Java `ScreencastHelper`, the contents of the
screencast tests, and each crate's licence and `build.rs`. Nothing built) · **Epic:** Rust port of the remaining native
code (goal 3) · **Blocked by:** US-027, US-038,
US-042

## Story
As a platform maintainer,
I want the xdg-desktop-portal and PipeWire code of `libglassgtk3.so` in Rust, exporting the same 11 `sc_*` symbols,
so that the one part of GTK Glass that runs its own thread and reads frames another process describes gets
compiler-checked cross-thread state and bounds-checked frame access, with no change to `ScreencastHelper` or
`GtkRobot`.

## Why Rust fits here
- **Must stay native (R1):** the code is OS-CALL in three ways.
  - The stream callbacks run on PipeWire's own thread-loop thread (`screencast_pipewire.c:621`; `onStreamProcess` at
    `:268`; `onCoreError` at `:601-604`).
  - The portal protocol runs nested `gtk_main` loops on the application thread (`screencast_api.h:83-94`).
  - Format negotiation uses SPA's `static inline` POD API.

  The FFM migration moved every marshal-only GTK native to Java (`GtkGlassNative.java:80-87`) and kept this C unchanged
  (`screencast_api.h:39-46`).
- **Owned code (R2):** this is OpenJFX code (Oracle copyright, `screencast_portal.c:2`). The vendored `libpipewire/`
  headers are not ported; they stay, for the SPA shim.
- **Buildable and testable here (R3):** not yet. Today WSL has no PipeWire, no portal and no D-Bus daemon (probed
  2026-09-30), so only the "no PipeWire" branch runs. R3 is met once US-038 lands.
- **Benefit (R4):** these 3,051 lines hold the cross-thread state of Linux Glass and its only foreign-process buffers:
  - an unlocked predicate read against the PipeWire thread (`screencast_pipewire.c:710-720` and `:889-897`, against
    `:384-389`);
  - a teardown on Java's `Timer` thread (`screencast_api.h:88-90`, `screencast_pipewire.c:134`);
  - frames wrapped from a compositor-supplied stride and size with no bound check (`:328-336`), and read after their
    buffer goes back to PipeWire (`:381-387`);
  - 35 lines of manual free/unref in `screencast_portal.c`.

  The C fix story repairs the first and third items *before* the port. The port's own gain is that the fixed defects
  cannot silently come back:
  - the loop-thread state is reachable only through a guard obtained from `pw_thread_loop_lock`;
  - a frame is a `&[u8]` borrowed from the dequeued buffer: the part of its `maxsize` bytes that US-042's checks
    select, so it cannot be read past its chunk, or after the buffer goes back to PipeWire;
  - GLib objects are owned by the `glib`/`gio` smart pointers.
- **Binding (R5):**
  - `gio` and `glib`, optionally `gdk-pixbuf` (gtk-rs-core, maintained, MIT *(unverified)*), plus `libc` (the MIT
    option *(unverified)*).
  - Hand-declared in a `sys` module:
    - the 25 PipeWire functions the C resolves (`screencast_pipewire.c:727-766`), resolved with `dlopen`/`dlsym` as
      today;
    - `gtk_main`, `gtk_main_quit`, `gdk_keyval_to_lower`, `gdk_x11_get_default_xdisplay` and `XKeycodeToKeysym`.
  - Rejected:
    - pipewire-rs: it links at build time and needs libclang (the Rust crate rules), and it breaks the optional load
      (`screencast_api.h:199-201`);
    - ashpd: it needs an async runtime and moves portal replies off the application thread.
- **Why not Java:**
  - A Java port would run the stream callbacks as upcalls on PipeWire's thread. The JVM attaches that thread
    (`screencast_api.h:54-61`, about 1.5 ms on the first call), and Java can then stop at a safepoint while PipeWire's
    loop lock is held. PipeWire calls its callbacks under that lock (background, not verified).
  - SPA's inline API has no symbol for `SymbolLookup`. Java would have to re-implement the SPA POD wire format, which is
    vendored code (R2), or keep the same C shim and add Java layouts for the PipeWire and SPA structs on top of it.

## Current state
All C paths are under `modules/javafx.graphics/src/main/native-glass/gtk/`.
- **Files (3,051 lines, `wc -l`):** `screencast_api.c` 77, `screencast_api.h` 303, `screencast_pipewire.c` 1,138,
  `screencast_pipewire.h` 110, `screencast_portal.c` 1,322 and `screencast_portal.h` 101. They are linked into
  `glassgtk3` (`modules/javafx.graphics/native/linux.cmake:148-156`).
- **ABI:** `GLASS_SCREENCAST_ABI_VERSION` 1 (`screencast_api.h:134`); 11 `SC_EXPORT`s (`:136-297`); one callback
  table, `ScTokenCallbacks`, with the single slot `store_token` (`:184-187`).
- **Internal links that become C↔Rust links during the transition:**
  - `screencast_pipewire.c` calls into the portal code (`portalScreenCastCleanup`, `:160`);
  - the remote-desktop flag that `sc_load_pipewire` records also steers `glass_key.cpp` (`screencast_api.h:199`; the
    symbol was not checked).
- **Threads:**
  - the application thread runs `sc_get_rgb_pixels`, `sc_remote_desktop_*` and the nested `gtk_main`, and dials
    `store_token` (`screencast_api.h:83-94`);
  - Java's `Timer` thread runs `sc_close_session` (`:88-90`);
  - PipeWire's loop thread runs the stream and core callbacks (`screencast_pipewire.c:621`).
- **Tests today** (names from `git grep`; contents not read):
  - under `modules/javafx.graphics/src/test/java/test/com/sun/glass/ui/gtk/`: `GtkScreencastNativeTest`,
    `GtkScreencastHeadlessTest`, `GtkScreencastDebugTest`, `GtkCallbackTableTest` and `GtkRobotParityTest`.
    `GtkScreencastDebugTest` checks the golden `gtk-screencast-debug-golden.txt` and normalises `__LINE__`
    (`GtkScreencastDebugTest.java:67-68`);
  - `tests/system/src/test/java/test/robot/javafx/embed/swing/LinuxScreencastHangCrashTest.java`.

## Approach
- **The ABI is unchanged:** the same 11 symbols, ABI version, slot and threads. The header's comments are the spec.
- **The crate:** `glass_gtk_screencast`, placed and wired as the toolchain story defines. It builds as a `staticlib`
  linked into `glassgtk3`, and a CMake option per slice selects C or Rust. Internal C↔Rust symbols of the transition
  stay local (version script); only the 11 `sc_*` symbols are ABI.
- **What stays the same:**
  - GdkPixbuf still does the scaling and cropping, so pixel parity comes from running the same library code.
  - The nested `gtk_main` loops stay.
  - The debug lines keep their text, including the C function names. `GtkScreencastDebugTest` masks only the line
    number.
- **Panic guards:** every export, and every `extern "C"` callback that PipeWire or GLib calls, runs under the crate's
  `catch_unwind` guard. The guard returns what the C returns on its early-exit path.
- **What remains at the end:** one C file, a shim that makes `spa_pod_builder_add_object`, `spa_format_parse` and
  `spa_format_video_raw_parse` out-of-line, plus the vendored `libpipewire/` headers it compiles against.

### Slices
1. **Installer, version and sizeof exports.**
   - Moves: `sc_abi_version`, `sc_sizeof_token_callbacks` and `sc_set_token_callbacks`, along with the crate skeleton,
     the guard and the version script.
   - Gate: `GtkScreencastNativeTest` and `GtkCallbackTableTest` are unchanged and green, and the export list is
     unchanged.
   - Deletes: their C. `screencast_api.c` (77 lines) is the likely home; not read.
2. **PipeWire loader.**
   - Moves: `sc_load_pipewire`, with the same `dlopen` flags, the same 25 symbols, the same answers and the same debug
     text. The dead `glib_version_2_68` extern is not carried over.
   - Gate: `gtk-screencast-debug-golden.txt` and the stub-library branch of the test bed exact; the NEEDED list from
     `readelf -d` unchanged, with no `libpipewire-0.3.so.0`.
3. **Portal and remote-desktop input.**
   - Moves, onto `gio`: `screencast_portal.c`/`.h`, `sc_init_xdg_desktop_portal`, the four `sc_remote_desktop_*`
     functions and their key mapping (`screencast_pipewire.c:1027-1138`).
   - Gate: the D-Bus message trace on the test bed's private bus and the `store_token` call trace (thread, order,
     bytes), both exact.
   - Deletes: the portal C.
4. **Streams, frames and teardown.**
   - Moves: `doLoop` and `connectStream`, the stream and core callbacks, the frame wrap/scale/crop into the caller's
     buffer, `sc_get_rgb_pixels`, and `sc_close_session` on the `Timer` thread.
   - Gate: raw-ARGB pixel goldens from the stub producer (identity, scaled, cropped and multi-screen cases), the event
     trace and the teardown trace, all exact.
   - Deletes: `screencast_pipewire.c`/`.h`, apart from the SPA shim.
5. **Clean-up.** Remove the per-slice CMake options, and update `backlog/README.md`.

## Acceptance criteria
- `nm -D --defined-only libglassgtk3.so` is identical before and after every slice, `GLASS_SCREENCAST_ABI_VERSION` stays
  1, and the NEEDED list from `readelf -d` is unchanged.
- A C translation unit that includes both `screencast_api.h` and the cbindgen header compiles.
- `const` layout asserts cover the PipeWire/SPA structs the crate reads, taken from the vendored headers.
- The Java tests listed above are unchanged and pass in WSL.
- The test bed's goldens are exact: the D-Bus trace, the `store_token` trace, the pixels and the debug text.
- The screencast C files above are deleted; only the SPA shim remains.
- `unsafe` appears only in `ffi`/`sys` (the PR reports the count), and clippy and rustfmt are clean.
- Every crate's licence is recorded in `legal/`.

## Definition of Done
The PR is merged. It is verified in WSL on the test bed and on one real Wayland session, with the manual smoke test
recorded. The Windows build is unaffected (the crate is Linux-only). CI is green, with the display tests once US-016
lands. The C is deleted and `backlog/README.md` is updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | A faithful port must keep the C's races (port rule P6) | Land US-042 in C first, so the goldens and the port both encode the fixed code |
| 2 | `gio` makes different GLib calls from the C (floating refs, proxy flags) | Judge parity at the D-Bus message level on the private bus, not on the GLib call sequence |
| 3 | Upstream OpenJFX fixes to `screencast_*.c` stop applying as patches | Watch the upstream history of these files and port each fix as its own change |
| 4 | PipeWire/SPA struct layouts change between PipeWire versions | Take the layouts from the vendored headers the C compiles against today, and assert them in the ABI check |
| 5 | Portal backends differ (GNOME, KDE, wlroots) | The mock pins how the C uses the protocol; smoke-test on at least one real backend and record which one |
| 6 | Callbacks are not exports, so a panic on PipeWire's thread would abort the process | Guard every callback; its failure path is the C's early return |

