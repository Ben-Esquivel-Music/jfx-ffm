# US-033 — Port progressbuffer and hlsprogressbuffer to Rust inside fxplugins

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read: `progressbuffer.c` excerpts (pads, threads,
`getrange`, monitor), `cache.h`, both `filecache.c` excerpts, the jfxmedia message consumers; not checked:
`hlsprogressbuffer.c` beyond its type and factory, the property ranges; no in-repo test reaches either element;
nothing built) · **Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:**
US-027, US-032 (javasource port: crate, link recipe, trace driver), US-041 (fxplugins
map-misuse fix, finding 4)

## Story
As a JavaFX app developer streaming media over HTTP or HLS,
I want the progressive-download buffer elements implemented in Rust,
so that the state their three threads share and their byte-range arithmetic are checked by the compiler, with no
change in buffering, underrun or seek behaviour.

## Why Rust fits here
Paths as in the javasource port story.
- **Must stay native (R1):** two `GstElement` subclasses (`gstreamer/plugins/progressbuffer/progressbuffer.c:67`,
  `hlsprogressbuffer.c:44`) with sink and src pads and three threads:
  - the upstream chain thread;
  - the src pad task (`progressbuffer.c:435`, `:785`);
  - a range-monitor thread the element starts in pull mode (`:395`).
- **Owned code (R2):** OpenJFX code.
- **Buildable and testable here (R3):**
  - It is compiled into `fxplugins` on both OSes (`native/win.cmake:755-757`, `native/linux.cmake:366-368`).
  - It is inserted when the source needs buffering (`GstPipelineFactory.cpp:281-302`).
  - No test reaches it today (`FFM-STATUS.md:264-268`), so the trace goldens are the oracle.
- **Benefit (R4):**
  - **Shared state.** Range requests, segment, cache and flow result are shared under one `GMutex` and one
    `GCond` (`progressbuffer.c:72-73`, `:385-403`, `:859`, `:1026-1046`). In Rust the lock owns the state.
  - **Untrusted ranges.** Pull ranges are driven by offsets inside the untrusted container (`guint64` start,
    `guint` size). They are compared with `gint64` segment fields through casts (`:1064-1088`) and become cache
    file offsets (`:733-767`).
  - **Cache reads.** The cache allocates the requested size and reads into it (`win32/filecache.c:126-147`,
    `posix/filecache.c:135-156`).
  - **Effect of the port.** Every conversion becomes explicit with the same results, and unlocked access becomes
    impossible.
- **Binding (R5):**
  - As the javasource port, plus `std::fs`.
  - The Windows open flags and share modes (`win32/filecache.c:62-65`) map to
    `std::os::windows::fs::OpenOptionsExt` (`custom_flags`, `attributes`, `share_mode`).
  - The POSIX `g_mkstemp_full` + `unlink` (`posix/filecache.c:60-66`) stays a GLib call through `glib`.
- **Why not Java:** as R1. Java sees only the bus messages (`pb_buffering`, `pb_underrun`, `hls_pb_*`:
  `jfxmedia/platform/gstreamer/GstAudioPlaybackPipeline.h:51-58`, consumed at
  `GstAudioPlaybackPipeline.cpp:1451-1493`).

## Current state
- **Files.** `gstreamer/plugins/progressbuffer/`: `progressbuffer.c` 1,166, `hlsprogressbuffer.c` 633,
  `win32/filecache.c` 193, `posix/filecache.c` 196, and headers 169 lines (`cache.h`, `progressbuffer.h`,
  `hlsprogressbuffer.h`). Total 2,357.
- **Contract.**
  - **Factories.** `progressbuffer` (`progressbuffer.h:33`) and `hlsprogressbuffer` (`hlsprogressbuffer.h:33`,
    rank NONE at `hlsprogressbuffer.c:632`).
  - **Properties.** `threshold`, `bandwidth`, `prebuffer-time`, `wait-tolerance` (`progressbuffer.c:206-233`).
  - **Custom event.** `FX_EVENT_RANGE_READY` (`gstreamer/plugins/fxplugins_common.h`, pushed at
    `progressbuffer.c:1042`).
  - **Custom query.** `progressive-getrange` (`:1109-1120`).
  - **Messages.** Buffering and underrun messages (`:552`, `:836`).
  - **Upstream seeks.** Byte seeks (`:1098-1100`).
  - **Pull.** The `getrange` decision tree (`:1053-1107`).
  - **Internal cache API.** `cache.h`, 8 functions, one implementation per OS.
- **Tests.** None in the repository exercise either element.

## Approach
- **Harness.** Same crate and trace driver as the javasource port.
- **Contract unchanged.** Factory names, pad templates, properties, message names and every structure field,
  custom event and query, seek events, and thread roles.
- **Monitor thread.** It keeps its lifecycle: started on pull-mode activation, woken through the same condition,
  and joined on deactivation (`:385-403`).
- **Cache.** It keeps its C prototypes while `progressbuffer.c` is still C.

### Slices
1. **Goldens from C.** Scenarios in the trace driver, upstream being the scripted javasource in
   non-random-access mode:
   - push-mode playthrough to EOS;
   - pull reads inside the cache;
   - pull reads past the write position (FLUSHING, an underrun message and an upstream seek);
   - pull reads past the segment stop (EOS);
   - bandwidth and prebuffer edge values, including the extreme value fixed by the blocking story;
   - flush during seek;
   - the HLS element's stall, resume, full, not-full and EOS messages.

   Captured at a recorded commit on Windows and WSL.
2. **Cache in Rust.** The crate exports the `cache.h` functions per OS behind an option, and `filecache.c` leaves
   the build. Same temp-file location and delete-on-close behaviour as each C file. Gate: goldens exact.
3. **`progressbuffer` in Rust.** Gate: goldens exact, and the option off is unchanged.
4. **`hlsprogressbuffer` in Rust.** Same gate.
5. **Delete the C.** Remove both elements' `.c` files, both `filecache.c`, `cache.h` and the CMake lines. The
   headers `fxplugins.c` includes shrink to the init prototypes.

## Acceptance criteria
- `fxplugins` export lists as in the javasource port: Windows identical; Linux identical except for listed symbols
  internal to deleted C; no Rust std symbol.
- Goldens exact on Windows and WSL.
- Module Java suite unchanged and passing.
- The slice-5 files deleted.
- `unsafe` only in `ffi`.
- clippy and rustfmt clean.

## Definition of Done
PR merged after review; verified on Windows and on WSL Linux; the C deleted; `backlog/README.md` updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | No existing test reaches the elements, so the goldens are the only oracle | The corpus covers every branch of `:1064-1094` and every message; the PR shows the branch map |
| 2 | Timing-dependent paths (bandwidth estimate, `wait-tolerance`) make traces nondeterministic | The driver paces upstream deterministically and sets the properties; any field left nondeterministic is excluded and documented, never tolerated silently |
| 3 | The monitor thread's wake and exit order changes | Same condition protocol, reviewed against `:1026-1046` |

