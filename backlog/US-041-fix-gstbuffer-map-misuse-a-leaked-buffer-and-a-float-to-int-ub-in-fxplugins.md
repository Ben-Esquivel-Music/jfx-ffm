# US-041 — Fix GstBuffer map misuse, a leaked sample buffer and a float-to-int UB in fxplugins

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; the four sites below were read, and
`GetGstBuffer`'s ownership was checked at its one implementation; not checked: whether any site is reached by
today's tests, or the `bandwidth`/`prebuffer-time` property ranges; nothing built) · **Found:** 2026-09-30, Rust-port
survey (media plugins) · **Blocked by:** none · **Blocks:** the javasource and progressbuffer Rust ports
(US-032, US-033)

## Story
As a platform maintainer,
I want fxplugins to write only through buffers it mapped for writing, to release every buffer it allocates, and
to convert doubles to integers only within range,
so that the elements follow the GStreamer buffer contract and have no UB, and the Rust ports stay faithful
without copying the violations.

## Findings
Paths relative to `modules/javafx.media/src/main/native/gstreamer/plugins/`.
1. **javasource pull path writes through a read map.** `javasource/javasource.c:856` allocates the buffer, `:862`
   maps it `GST_MAP_READ`, and `:883` passes `info.data + read` to the `copy-block` signal, whose handler writes
   the stream bytes. The push path maps `GST_MAP_WRITE` (`:647`). It works today only because the default
   system-memory allocator returns the same pointer for both modes.
2. **dshowwrapper masks bits inside the caps' `codec_data`.** `dshowwrapper/dshowwrapper.cpp:2097` maps it
   `GST_MAP_READ`. `:2100` passes the data to `dshowwrapper_get_avc_config`, which writes `lengthSizeMinusOne` and
   `spsCount` back into it (`:1255-1256`). That clears reserved bits in a buffer every holder of the caps shares.
3. **The DirectShow sink leaks its output buffer on two failure paths.** `dshowwrapper/Sink.cpp:456` gets a new
   buffer from `dshowwrapper_get_gst_buffer_sink` (`dshowwrapper.cpp:603-616`, caller owns it). The returns at
   `Sink.cpp:461-462` (`GetPointer` failed) and `:464-465` (`gst_buffer_map` failed) drop it without
   `gst_buffer_unref`.
4. **progressbuffer converts an unbounded double to `gint64`.** `progressbuffer/progressbuffer.c:1081` computes
   `end_position + (gint64)(bandwidth * prebuffer_time)`. That is UB when the product is outside the `gint64`
   range or NaN, and the addition can overflow.

## Approach
1. Map `GST_MAP_WRITE` at `javasource.c:862`.
2. `dshowwrapper_get_avc_config` takes `const void*`, masks on a local copy of the 6-byte header, and stores
   `lengthSizeMinusOne` as today.
3. `gst_buffer_unref(pBuffer)` before both failure returns.
4. Convert with a range check and add with saturation. Unchanged for every in-range value.

## Acceptance criteria
- `MediaPlaybackTest`, `JfxMediaNativeTest` and the rest of the module suite pass unchanged on Windows and WSL.
  The PR states whether the suite reaches the javasource pull path (a temporary log line is enough).
- `dshowwrapper_get_avc_config`'s input parameter is `const`, so a write through it no longer compiles.
- Both `Sink.cpp` failure paths unref the buffer. The PR records a one-off fault-injected run for each, because
  neither DirectShow failure is reachable without injection.
- `progressbuffer.c:1081` has no conversion or addition that can overflow. The progressbuffer Rust port's trace
  corpus later adds an extreme-`bandwidth` case against the fixed C.

## Definition of Done
Merged; verified on Windows (all four) and on WSL (1 and 4); no Rust involved; `backlog/README.md` updated.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | Something downstream relied on the masked `codec_data` bytes | The masked values reach the decoder through `decoder->lengthSizeMinusOne` and the generated header, as today; only the shared buffer stops changing |
| 2 | Clamping changes `range_stop` for a value that was in range | The fix is a no-op for in-range products; the change is reviewed against `:1079-1088` |

