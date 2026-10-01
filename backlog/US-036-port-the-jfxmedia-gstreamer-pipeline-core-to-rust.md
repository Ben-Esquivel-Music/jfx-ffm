# US-036 — Port the jfxmedia GStreamer pipeline core to Rust behind the jfxm_* ABI

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read the bus-watch, dispose, stall/resume,
javasource-wiring and dispatcher code and `jfxmedia_api.h`; counted lines and refcount/lock sites with `wc -l`/
`git grep -c`; NOT checked: which threads call `UpdatePlayerState`, the lock state of every cross-thread field,
current gstreamer-rs/glib APIs and the licences of their dependency trees, macOS; nothing built; 2026-10-01: the
macOS deletion became slice 7, after US-028, as US-028 expects) · **Epic:** Rust port of the remaining native code
(goal 3) · **Blocked by:** US-027, US-035 (jfxmedia video-frame, colour-conversion, spectrum, equalizer and logger
port); slice 7 also by US-028

## Story
As a JavaFX app developer,
I want the GStreamer playback core of `libjfxmedia` in Rust behind the unchanged `jfxm_*` ABI: the media manager and
its main loop, the pipeline factory, the audio and audio-video pipelines, the stream-callback adapter, the event
dispatcher and the remaining 33 exports,
so that pipeline teardown, bus-watch lifetime and cross-thread player state are safe by construction, with the same
events, error codes and threads as today.

## Why Rust fits here
- **Must stay native (R1):** there are no nameable `gst_*`/`g_*` symbols on Windows, and the contract records no
  `WRAPPER` (`modules/javafx.media/FFM-ABI-CONTRACT.md:23-27`). The code is GObject signal handlers, a GLib bus watch
  and pad/appsink callbacks on GStreamer threads (`platform/gstreamer/GstAVPlaybackPipeline.cpp:88-121`,
  `GstPipelineFactory.cpp:236-260`). It also reads macro-only struct fields (`GST_BUFFER_TIMESTAMP`,
  `GstAVPlaybackPipeline.cpp:408`).
- **Owned code (R2):** OpenJFX's own C++. The fxplugins signal contract (javasource `read-next-block`/`copy-block`/
  `seek-data`/`read-block`/`property`/`close-connection`; progressbuffer `pad-added`, `GstPipelineFactory.cpp:476-478`)
  is kept byte for byte.
- **Buildable and testable here (R3):** as for the story it depends on. Playback on WSL needs the ALSA null PCM of
  `.github/workflows/submit.yml:98-103`.
- **Benefit (R4):** lifetime and thread safety, not parsing. The core never parses stream bytes: `copy_block` hands
  the javasource buffer straight to Java (`ffi/FfiStreamCallbacks.cpp:95-100`).
  - A hand-rolled teardown handshake: `sBusCallbackContent` with three flags, a lock it deletes itself, and "who
    frees" logic split between `Dispose()` and the GSource destroy notify (`GstAudioPlaybackPipeline.cpp:136-158,
    385-450, 1566-1590`). Upcalls run under `m_DisposeLock` (`:273-376`). An `Arc` shared by the watch and the
    pipeline replaces it.
  - One plain `gboolean` under different locks or none. `m_bLastProgressValueEOS` is written by the bus handler
    without `m_StallLock` (`:1473`), and is guarded by `m_StallLock` elsewhere (`:2049-2056`, `:2125-2131`). It is
    read after `m_StateLock->Exit()` with no lock (`:1624-1631`). `m_bHLSPBFull` follows the same pattern (`:1489`,
    `:1494`, `:2113-2116`, `:1628`). A data race is UB in C++. Rust forces an atomic or a single owner.
  - The stream adapter is owned by a `GClosureNotify`, and the never-played path used to leak it
    (`GstPipelineFactory.cpp:239-256`). A Rust closure owns its captured state and drops it on finalize.
  - The dispatcher install-once rule lives in a comment (`PipelineManagement/Pipeline.cpp:63-86`). `OnceLock` makes
    it the type.
  - 67 manual ref/unref/probe sites (`git grep -c`: GstAVPlaybackPipeline 28, GstAudioPlaybackPipeline 19,
    GstPipelineFactory 18, ffi 2).
  - Unwinding into C: `std::bad_alloc` can escape `extern "C"` (`jfxmedia_api.h:34-39`), and a throwing
    `new float[]` runs inside the GLib bus handler (`GstAudioPlaybackPipeline.cpp:1516-1517`), which the header does
    not list. Rust guards every export and callback entry.
- **Binding (R5):** `gstreamer`, `gstreamer-app`, `glib` (`MIT OR Apache-2.0` → MIT; MIT). The API needed is
  element/bin/pad/bus/message/query/event/caps/structure, a bus watch on a private `glib::MainContext`, raw signal
  connection with C trampolines, and appsink callbacks. The link wiring arrives with the story this one depends on.
- **Why not Java:** every entry point is a GStreamer callback on a foreign thread or needs macro-level struct
  access, and there are no nameable symbols on Windows (above).

## Current state
- Owned lines (`wc -l`), **9,544** in total:
  - `platform/gstreamer/` 5,101: GstAudioPlaybackPipeline 2,352, GstPipelineFactory 1,245, GstAVPlaybackPipeline
    1,017, GstMediaManager 355, GstElementContainer 132;
  - `PipelineManagement/` pipeline, factory, tracks, options and dispatcher interface: 1,162;
  - `MediaManagement/` 473 and `Locator/` 257;
  - `ffi/` without `FfiBandsHolder` and `jfxmedia_avf.h`: 2,001;
  - `Utils/` locks, `Singleton.h` and `WinExceptionHandler`: 432;
  - `Common/` 118.
- Exports (33): `jfxm_abi_version`, `jfxm_sizeof_*` (3), `jfxm_offsetof_*` (3), `jfxm_platform_init`,
  `jfxm_osx_platform_init`, `jfxm_media_{create,dispose}`, `jfxm_player_init` plus 19 `jfxm_player_*` calls,
  `jfxm_event_player_state`, `jfxm_audio_track_channel`. The ABI version is `4u` (`jfxmedia_api.h:83`).
- Tables: `JfxmStreamCallbacks` (9 slots, `jfxmedia_api.h:239-280`) and `JfxmPlayerCallbacks` (13 slots,
  `:286-331`), each with the per-slot threads and lifetimes the header states.
- Threads:
  - one `MainLoop` GThread and private `GMainContext` per process (`GstMediaManager.cpp:156, 225-242`), which every
    bus watch attaches to (`GstAudioPlaybackPipeline.cpp:147-153`);
  - the javasource task thread and the streaming threads;
  - Java caller threads, some of which block on preroll (`jfxmedia_api.h:395`).
- Windows: a process-wide `SetUnhandledExceptionFilter` is installed from `MediaManager.cpp:82`
  (`Utils/win32/WinExceptionHandler.cpp:108-116`).
- Tests:
  - `modules/javafx.media/src/test` has 33 tests in 6 classes (`modules/javafx.media/FFM-STATUS.md:256-261`; a plain
    `git grep -c '@Test'` also counts the `@TestMethodOrder` at `JfxMediaNativeTest.java:108`). Playback coverage is
    `MediaPlaybackTest` (2 tests; states in order, `:223`).
  - `tests/system` has one media test, and it is macOS-only (`AVFVideoDisposeRaceTest.java:130`). So no GStreamer
    system test runs under `FULL_TEST` on Windows or Linux.

## Approach
- Same crate and mixed-library model as the story it depends on. Rust and C++ meet at internal `extern "C"` seams
  until the last C++ caller moves.
- **Races are reproduced, not fixed.** A field the C shares without a common lock becomes a relaxed atomic. That
  compiles to the same plain loads and stores on x86-64 and AArch64, without the UB. A field under one lock stays
  under that lock. Unchanged: the lock set and lock order, the single `MainLoop` thread and its name, event order,
  and the preroll race. `GetDuration` still fails while `gst_element_query_duration` cannot answer
  (`GstAudioPlaybackPipeline.cpp:698-702`), and the tests keep gating on `onReady` (`MediaPlaybackTest.java:455,502`).
- The fxplugins signals keep C-signature trampolines connected with `g_signal_connect_data` from the boundary module,
  not glib-rs closure marshalling.
- The Windows exception filter is ported faithfully (`windows-sys`, same install point).

### Slices
0. **Event-trace goldens from the C build**, with the recording tables of the survey. Scenarios, on Windows and WSL:
   - WAV played to end; play/pause/seek/stop; rate, volume, balance and mute changes; spectrum on;
   - push-mode and pull-mode stream tables;
   - unsupported content;
   - PAUSED failing without an audio device, which gives the late `BusCallback` delivery (`jfxmedia_api.h:375-388`);
   - dispose while playing, and dispose of a never-played media (the `SourceCallbacksDestroyed` path);
   - the MP4 added by the story this one depends on.

   Compare the slot order and arguments exactly. Wall-clock fields and anything read before READY are checked by
   order and range only. Record commit and platform.
1. **Stream adapter and Locator** (`FfiStreamCallbacks`, `Locator/`, the javasource trampolines of
   `GstPipelineFactory.cpp:323` onward). Gate: the stream traces, `copyBlockReportsHowManyBytesItCopied`,
   `aReadAtTheEndOfTheStreamReportsEosAndNotAFailure`,
   `disposingAMediaClosesTheConnectionTheStreamCallbacksNeverClosed` and `HLSConnectionHolderTest`.
2. **Event dispatcher** (`FfiPlayerEventDispatcher`, `jfxm_event_player_state`, `jfxm_audio_track_channel`). Gate:
   the player traces, both mapping tests, `anUpcallTargetSwallowsWhatItsTargetThrows` and
   `noUpcallReachesATargetThatHasBeenUnregistered`.
3. **Media manager and main loop** (`GstMediaManager`, `MediaManagement/`, `WinExceptionHandler`, the two platform
   inits). It keeps the same `gst_init_check` and the same retry on failure (`jfxmedia_api.h:200-204`). Gate:
   `platformInitIsIdempotent`, `NativeMediaManagerDegradationTest` and the traces.
4. **Pipeline factory** (`GstPipelineFactory`, `GstElementContainer`, `PipelineFactory.*`). It builds C++ pipelines
   through an internal seam. Gate: the traces.
5. **Playback pipelines** (`GstAudioPlaybackPipeline`, `GstAVPlaybackPipeline`, `Pipeline.*`, tracks, options),
   about 4,400 lines.
   - One slice, because the AV pipeline derives from the audio pipeline.
   - Reviewed as three commits: bus handling and state machine; seek/rate/volume with HLS stall/resume; AV pads,
     queues and appsink.
   - Every racy field is listed in the PR with its accesses.
   - Gate: all traces, `MediaPlaybackTest` and both dispose-leak tests.
6. **Exports and leftovers** (`ffi/jfxmedia_api.cpp`, `JfxmMediaHandle.h`, the 33 exports). The guards return the
   C's error codes. Then `Utils/` locks, `Singleton.h` and `Common/` leave the Windows/Linux builds.
7. **Delete the C++, after US-028.** Delete the files slices 1-6 removed from the Windows/Linux builds, and their
   `native/mac.cmake` lines.

Slices 1-6 each remove their C++ from the Windows/Linux builds, in their own commit, once accepted on both. macOS
keeps compiling it until slice 7.

## Acceptance criteria
- The export list is identical and `jfxm_abi_version()` = 4. The `jfxm_sizeof_*`/`jfxm_offsetof_*` values are
  unchanged.
- The 33 module tests are unchanged and pass on Windows and on WSL (ALSA null PCM).
- The event traces match, under the comparison rule of slice 0, for every scenario on both platforms.
- The fxplugins signal names and C signatures are unchanged, and no plugin file is edited.
- No new thread, lock or `GMainContext`: one `MainLoop` thread per process.
- After slice 7, the slice files are deleted and nothing references them (`git grep`).
- The Windows/Linux builds compile no jfxmedia C/C++. `unsafe` appears only in the boundary modules. clippy and
  rustfmt are clean.

## Definition of Done
- The PRs are merged.
- The port is verified on Windows (VS2022) and on WSL Ubuntu, and the C++ is gone from the Windows/Linux builds.
- Slice 7 deletes the C++ from the tree once US-028 has landed. `backlog/README.md` is updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Event order across the main loop and streaming threads shifts subtly | Trace goldens per scenario; no new synchronisation; reads before READY excluded, as the tests already do |
| 2 | A racy field mapped to a relaxed atomic hides an ordering dependency | Each field listed with its accesses and the C's lock set; the reviewer checks the ordering |
| 3 | glib-rs marshalling differs from C trampolines for the fxplugins signals | Raw `g_signal_connect_data` with `extern "C"` trampolines in the boundary module |
| 4 | Under OOM, Rust aborts where the C++ returned `ERROR_MEMORY_ALLOCATION` from `new (nothrow)` | Keep `g_try_malloc` where the C used it; record the remaining difference (the throwing-`new` paths already terminate) |
| 5 | macOS keeps the C++ backend (`native/mac.cmake` compiles `platform/gstreamer`) | As in the story this one depends on: the C++ stays in the macOS build until US-028, which needs no macOS host because CI builds and tests macOS; slice 7 then deletes it |
| 6 | Slice 5 is large | Three commits with the traces run per commit; slices 1–4 shrink its seam first |

