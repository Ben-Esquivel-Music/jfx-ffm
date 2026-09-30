# US-035 — Port jfxmedia's frame, conversion, spectrum, equalizer and logger code to Rust behind the jfxm_* ABI

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read `jfxmedia_api.h`, `FFM-ABI-CONTRACT.md`
§0–1, the frame/spectrum/equalizer/logger sources and `native/{win,linux,mac}.cmake`; counted lines with `wc -l` and
refcount sites with `git grep -c`; NOT checked: current gstreamer-rs/glib releases, their minimum GStreamer/GLib
versions and dependency licences, which `gst_*`/`g_*` symbols they reference versus the ordinal-only `.def` exports,
macOS; nothing built) · **Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:**
US-027

## Story
As a platform maintainer,
I want the video-frame, colour-conversion, audio-spectrum, equalizer and logger code of `libjfxmedia` implemented in
Rust, exporting the same 25 `jfxm_*` symbols under the same ABI version,
so that the most refcount-dense code of the library and the per-pixel walks over decoder-produced planes become
RAII-managed and bounds-checked, with no Java change.

## Why Rust fits here
- **Must stay native (R1):** `gstreamer-lite.dll`/`glib-lite.dll` export by ordinal only, so "every GStreamer
  interaction stays inside `jfxmedia`" and "No `WRAPPER` verdicts exist in this module"
  (`modules/javafx.media/FFM-ABI-CONTRACT.md:23-27`). `Utils/ColorConverter.c` is recorded `PURE-HOT`,
  `PARITY: unknown`, stays native (decision 4, `:59-64`). Frames and spectrum run on GStreamer threads
  (`jfxmedia_api.h:286-331`).
- **Owned code (R2):** OpenJFX's own C/C++. No vendored file is rewritten.
- **Buildable and testable here (R3):** built for Windows (`native/win.cmake:797-844`) and Linux
  (`native/linux.cmake:421-466`). The media tests run on Windows, and on soundless Linux once an ALSA null PCM is
  configured, as CI does (`.github/workflows/submit.yml:98-103`).
- **Benefit (R4):**
  - `platform/gstreamer/GstVideoFrame.cpp` has 46 manual ref/unref/map sites, the most of any file. Sample,
    buffer and mapping guards make every release structural.
  - `ColorConverter.c` walks planes whose strides and offsets come from caps of decoder output
    (`GstVideoFrame.cpp:246-258`). Today the callers validate (`CalcSize`/`CalcPlanePointer`, overflow-checked
    allocation `:400-414`) and the converter trusts raw pointers. Validated slices bounds-check each access by
    construction.
  - The spectrum band pair is hand-refcounted, and its release upcall runs on "whichever thread happens to drop the
    last reference" (`jfxmedia_api.h:480`). A swap-versus-AddRef race was already fixed once
    (`GstAudioSpectrum.cpp:101-112`). `Arc` plus `Drop` is exactly that contract.
  - The logger singleton is one of the documented `std::bad_alloc`-escapes-`extern "C"` sites
    (`jfxmedia_api.h:34-39`).
- **Binding (R5):** `gstreamer`, `gstreamer-app`, `glib` and their `-sys` crates (GStreamer project, gtk-rs-core;
  `MIT OR Apache-2.0` → MIT, and MIT). gstreamer-lite is GStreamer 1.28.3
  (`gstreamer-lite/gstreamer/gst/gstversion.h:57,63`) with GLib 2.84.3 on Windows and the system GLib on Linux
  (`native/linux.cmake:466`). Slices 1–2 need no GStreamer crate at all.
- **Why not Java:** Java cannot bind `gst_*`/`g_*` on Windows (ordinal-only imports), and frames, spectrum and
  equalizer are GStreamer objects touched on foreign threads. `ColorConverter.c` alone would be as provable in Java
  (exact byte goldens), so there Java loses only on hot-path cost: it converts every frame the Prism pipeline cannot
  take as YCbCr, per pixel. That cost is recorded (`PURE-HOT`) but not measured. Slice 2 records C and Rust
  throughput.

## Current state
- Owned lines (`wc -l`): `jni/Logger.{cpp,h}` 332; `Utils/ColorConverter.{c,h}` 2,794; `ffi/FfiBandsHolder.*` 140;
  `platform/gstreamer/GstAudioSpectrum.*` 226, `GstAudioEqualizer.*` 260, `GstVideoFrame.*` 800;
  `PipelineManagement/VideoFrame.*` 326, `AudioSpectrum.h` 77, `AudioEqualizer.h` 60, `NullAudioSpectrum.h` 93,
  `NullAudioEqualizer.h` 116. Total **5,224**.
- Exports (25 of 58): `jfxm_log_{init,set_level,level}`, 7 `jfxm_spectrum_*`, 11 `jfxm_eq_*`,
  `jfxm_frame_{get_info,convert,set_dirty,dispose}`. All are implemented today in `ffi/jfxmedia_api.cpp` (1,317
  lines, one TU for all 58). `JFXM_ABI_VERSION 4u` (`jfxmedia_api.h:83`).
- Seams to the C++ that stays for now:
  - the `LOGGER_*` macros (`jni/Logger.h`);
  - the six `ColorConvert_*` calls in `GstVideoFrame.cpp:431-568`;
  - `UpdateBands` from the bus handler (`GstAudioPlaybackPipeline.cpp:1526`);
  - frames created in the appsink `new-sample` callback (`GstAVPlaybackPipeline.cpp:120`) and handed to `new_frame`.
- Memory: converted frames are GLib-allocated and freed by GStreamer (`g_try_malloc` +
  `gst_buffer_new_wrapped_full`, `GstVideoFrame.cpp:50-73`).
- Tests today: `JfxMediaNativeTest` has 24 tests: symbols, ABI version, struct/slot offsets, mapping exports, log
  sink, null handles, spectrum holder release, frame planes, dispose leak checks. There are also
  `NativeVideoBufferOwnershipTest` (1) and `MediaPlaybackTest` (2). Judging by test names, none pins converted
  pixels, equalizer values or spectrum magnitudes.

## Approach
- One crate for all of jfxmedia (location per US-027), built as a `staticlib` into the existing
  `jfxmedia` CMake target on Windows and Linux. A per-slice CMake option selects C++ or Rust. It defaults to C++ on
  macOS, which keeps compiling these files (no macOS host).
- Rust and the remaining C++ talk through internal `extern "C"` functions declared in a private header. These are
  never exported: the export list is generated from `jfxmedia_api.h` alone.
- Every export and every GLib/GStreamer callback entry is guarded with `catch_unwind`. Allocation keeps GLib's
  allocator wherever GStreamer frees the memory.
- End state on Windows/Linux: the 25 exports are in Rust, and the files above are out of `win.cmake`/`linux.cmake`.
  `mac.cmake` is unchanged.

### Slices
0. **Goldens and split:**
   - Split `ffi/jfxmedia_api.cpp` by export group into separate TUs: no behaviour change, identical export list.
   - Capture from the C build, recording commit and platform:
     - **Conversion:** a test-only CMake target runs the six C entry points over 1×1, 2×2, 3×3, 17×9, stride >
       width, with and without an alpha plane, Y/U/V at 0 and 255, and seeded random planes. It writes raw outputs
       that are committed.
     - **Spectrum:** `SineWav` at fixed bands; the (timestamp, duration, magnitudes, phases) sequence as float bits,
       plus a band-count change mid-play.
     - **Equalizer:** add/remove/get/set round trips.
     - **Frames:** `JfxmFrameInfo` fields, plane hashes and `jfxm_frame_convert` output for a small H.264 MP4 added to
       the test resources, generated by a documented command from a synthetic source.
     - **Logger:** sink install/replace/detach and level filtering.
1. **Logger** (`jni/Logger.*`; 3 exports). The C++ `LOGGER_*` macros call into Rust. Gate:
   `logSinkDeliversNativeMessages`, `logLevelMappingMatchesTheLoggerConstants` and the logger goldens.
2. **Colour conversion** (`Utils/ColorConverter.*`). Rust provides the six `ColorConvert_*` names internally with
   the prototypes of `ColorConverter.h`, so `GstVideoFrame.cpp` is unchanged. Gate: the conversion goldens, exact.
   Proposed throughput bound on a 1920×1080 frame: within 5 % of the C (the maintainer sets the bound); numbers go
   in the PR.
3. **Spectrum** (`FfiBandsHolder`, `GstAudioSpectrum`, `NullAudioSpectrum.h`; 7 exports).
   - The holder becomes an `Arc`; the release upcall fires from `Drop`.
   - The slice also lands the GStreamer link wiring:
     - system-deps overrides so the `-sys` build scripts accept gstreamer-lite;
     - append-only additions to `gstreamer-lite.def`/`glib-lite.def` for any newly referenced symbol;
     - an undefined-symbol audit (`dumpbin /symbols`, `nm -u`) in CI.
   - Gate: `aNullSpectrumStillHandsTheBandsBackExactlyOnce` and the spectrum goldens, including the zero bands of the
     band-count change until that behaviour is fixed separately.
4. **Equalizer** (`GstAudioEqualizer`, `NullAudioEqualizer.h`; 11 exports). Gate: the equalizer goldens.
5. **Video frames** (`GstVideoFrame`, `VideoFrame`; 4 exports).
   - The C++ appsink callback creates the frame through an internal Rust function and passes the opaque pointer on.
   - `#[repr(C)]` asserts for `JfxmFrameInfo`.
   - Gate: the frame goldens, `NativeVideoBufferOwnershipTest`, `aPlaneTheFrameDoesNotHaveReadsAsAnEmptyBuffer` and
     `frameInfoFieldOffsetsMatchTheCompiledStruct`.

Each slice removes its C/C++ from the Windows/Linux builds, in its own commit, once it is accepted on both.

## Acceptance criteria
- The export list is identical (`dumpbin /exports jfxmedia.dll`, `nm -D --defined-only libjfxmedia.so`), and
  `jfxm_abi_version()` = 4.
- The 34 tests of `modules/javafx.media/src/test` are unchanged and pass on Windows and on WSL (ALSA null PCM).
- The conversion, spectrum, equalizer and logger goldens match exactly. The frame goldens match exactly on each
  platform they were captured on.
- The slice files are absent from `native/win.cmake` and `native/linux.cmake`; `native/mac.cmake` is unchanged.
- The staticlib references no `gst_*`/`g_*` symbol that gstreamer-lite/glib-lite do not export. `.def` changes are
  append-only.
- `unsafe` appears only in the boundary modules, each block with a `// SAFETY:` comment. clippy and rustfmt are
  clean.

## Definition of Done
- The PRs are merged, one slice per PR.
- Each slice is verified on Windows (VS2022) and on WSL Ubuntu, and its C/C++ is removed from the Windows/Linux
  builds.
- The Rust table in `backlog/README.md` is updated; the macOS row stays BLOCKED.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | The crates reference symbols missing from the ordinal-only `.def`s or from `libgstreamer-lite.so` | Undefined-symbol audit in slice 3; append-only `.def` entries; release LTO so that only reachable wrapper code is linked |
| 2 | `GST_DISABLE_GST_DEBUG` (`native/linux.cmake:459`) versus gstreamer-rs logging | Log only through the jfxmedia logger, as the C does; the audit catches any `gst_debug_*` reference |
| 3 | Conversion slower than the C | Throughput gate in slice 2; the C++ option stays selectable until it passes |
| 4 | macOS keeps the C++ of these files, so a fix lands twice until macOS can build the crate | Goldens are platform-neutral data; one PR updates both sides and the goldens |
| 5 | H.264 on Linux decodes through avplugin, which is built only when the ffmpeg dev packages exist (`submit.yml:82`) | Capture frame goldens on Windows (mfwrapper) and on Linux where avplugin is built; record the platform per golden |

