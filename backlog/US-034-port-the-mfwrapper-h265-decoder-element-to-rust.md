# US-034 — Port the mfwrapper H.265 decoder element to Rust inside fxplugins

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read: `mfwrapper.cpp` and `mfgstbuffer.cpp`
excerpts (COM object, decoder enumeration, the HVCC parser, COM/MF start-up), the H.265 pipeline selection; not
checked: whether this host has an HEVC decoder MFT, the exact `windows` crate coverage, the colour-converter
media types and the pad caps in detail; nothing built; 2026-10-01: the threads were re-read from where the
element, the decoder MFT and the colour converter are created and from their callers, and the COM initialisation
in the media natives and Glass Windows was listed with `git grep`; after review, a seek's `FLUSH_STOP` was traced
to javasource) · **Epic:** Rust port of the remaining native code (goal 3) · **Blocked by:** US-027, US-032
(javasource port: crate, link recipe, trace driver), US-051 (the MTA held for its COM calls)

## Story
As a JavaFX app developer playing H.265 MP4 on Windows,
I want the Media Foundation decoder element implemented in Rust with the `windows` crate,
so that COM reference counting, the GstBuffer-backed `IMFMediaBuffer` and the parsing of untrusted `hvcC` bytes are
memory-safe, with no change to decoded frames.

## Why Rust fits here
Paths as in the javasource port story.
- **Must stay native (R1):**
  - It is a `GstElement` subclass (`gstreamer/plugins/mfwrapper/mfwrapper.cpp:121`) that drives Media Foundation
    transforms, mostly on GStreamer's threads (`MFTEnumEx` `:1737`, `ProcessInput` `:620`; see "Threads" below).
  - It starts MF when the element is created (`:214`) and shuts it down in dispose (`:283-284`).
  - OS-CALL throughout.
- **Owned code (R2):** OpenJFX code.
- **Buildable and testable here (R3):**
  - Windows only (`native/win.cmake:763-764`), selected for `video/x-h265`
    (`jfxmedia/platform/gstreamer/GstAVPlaybackPipeline.cpp:274-277`).
  - Enumeration takes only synchronous local MFTs (`mfwrapper.cpp:1737-1738`). Tests therefore need an HEVC
    decoder MFT on the machine; on Windows 10 that is usually the HEVC Video Extensions package. Not checked
    here; see risk 1.
  - No test covers the element today (`FFM-STATUS.md:264-268`).
- **Benefit (R4):**
  - **COM lifetime.** `CMFGSTBuffer` implements `IMFMediaBuffer` by hand.
    - Its refcount starts at 0 and is managed with `InterlockedIncrement`/`Decrement` and `delete this`
      (`mfgstbuffer.cpp:30`, `:162-195`).
    - A Lock/Unlock count holds a GstBuffer map (`:104-160`), and the destructor releases the map and the ref
      (`:56-63`).
    - The MFT may keep the sample after `ProcessInput` returns.

    `#[implement(IMFMediaBuffer)]` generates the refcount and `QueryInterface`, and the buffer's lifetime becomes
    the object's.
  - **Untrusted input.** `mfwrapper_get_hevc_config` (`mfwrapper.cpp:1763`) parses the container's `hvcC` bytes
    into Annex-B (`:1788-1826`), with hand-written size checks before each copy.
  - **Raw COM pointers.** `IMF*` pointers are released by hand through `SafeRelease` (`:110-114`, `:259-271`,
    `:625-626`).
- **Binding (R5):**
  - `windows` crate (Microsoft; `MIT OR Apache-2.0`, MIT taken, from knowledge).
  - `Win32::Media::MediaFoundation` for `IMFTransform`, `IMFSample`, `IMFMediaBuffer`, `IMFMediaType`,
    `IMFActivate`, `MFTEnumEx`, `MFCreateSample`, `MFCreateMediaType`, `MFCreateMemoryBuffer`, `MFStartup`,
    `MFShutdown`.
  - `Win32::System::Com` for `CoInitializeEx`, `CoUninitialize`, `CoCreateInstance` (the colour converter,
    `:872`) and US-051's `CoIncrementMTAUsage`/`CoDecrementMTAUsage`.
  - `MFSetAttributeSize`, `MFSetAttributeRatio` and `MFGetAttribute*` are `mfapi.h` inline helpers, so the crate
    reimplements them (two `UINT32` packed in a `UINT64`).
  - The element side is as in the javasource port.
- **Why not Java:** as R1. The decoded frames also stay native all the way to the video sink.

## Current state
- **Files.** `gstreamer/plugins/mfwrapper/`: `mfwrapper.cpp` 2,033, `mfwrapper.h` 122, `mfgstbuffer.cpp` 303,
  `mfgstbuffer.h` 92 lines. Total 2,550.
- **Contract.**
  - Factory `mfwrapper`, rank 512 (`mfwrapper.cpp:2032`), registered by `mfwrapper_init`, which `fxplugins.c:48`
    calls on Windows.
  - Pad templates and caps; not enumerated in this survey.
  - The colour-converter output formats (NV12, P010 for 10-bit, and others; `pColorConvert[]`, `:267-271`).
  - The errors it posts.
- **Threads.** When the element makes its COM calls, it holds no apartment on the calling thread.
  - **Creation.** jfxmedia creates the element in the demuxer's `pad-added` handler: `on_pad_added` calls
    `LoadDecoder` (`jfxmedia/platform/gstreamer/GstAVPlaybackPipeline.cpp:579`), which makes it at `:284`. So
    `gst_mfwrapper_init` (`mfwrapper.cpp:186`) runs on the thread that emits `pad-added`, the demuxer's streaming
    thread. It joins the MTA (`:211`), starts MF (`:214`) and leaves the MTA again (`:216-217`, when `:211`
    succeeded). Only the MF start-up outlives the call: `gst_mfwrapper_dispose` (`:248`) shuts MF down when the
    start-up returned `S_OK` (`:283-284`).
  - **Decoder MFT.** `mfwrapper_load_decoder_media_types` (`:1721`) enumerates and activates it (`MFTEnumEx`
    `:1737`, `ActivateObject` `:1750`). It first runs from the `is-supported` getter (`:308-309`, `:329`, `:1718`),
    which jfxmedia reads right after creating the element, on the same thread (`GstAVPlaybackPipeline.cpp:583`,
    `:203-204`). `FLUSH_STOP` creates it again (`mfwrapper.cpp:1580`, `:1519`) on the thread that delivers the
    flush. A seek is flushing and synchronous on its caller (`GstAudioPlaybackPipeline.cpp:606-609`, `:675`), and in
    push mode javasource pushes that `FLUSH_STOP` on the same thread (`javasource.c:500`). So the reload can run on
    the seeking thread, which may be in an STA (US-051, Problem 2).
  - **Streaming.** `mfwrapper_chain` (`:1446`) runs on the task of the `queue` linked in front of the element
    (`GstAVPlaybackPipeline.cpp:289`), not on the creating thread. It calls the decoder's `ProcessInput`
    (`mfwrapper.cpp:620`, via `:1458`) and `ProcessOutput` (`:1392`, via `:1461`). On a stream change it sets the
    output type again, which can create the colour converter (`:1398-1402`, `CoCreateInstance` at `:872`); the
    converter's `ProcessOutput`/`ProcessInput` run at `:1257-1267` (via `:1413`). Caps (`:1659`) and the EOS
    drain (`:1632`) arrive through the same queue.
  - So the calls on GStreamer threads rely on the process's implicit MTA, which exists only while another thread
    holds the MTA: today the pipeline's DirectSound sink does, by coincidence. A reload on the seeking thread runs in
    that thread's apartment instead. US-051 makes the element hold the MTA itself.
- **Tests.** None today. US-051 adds the first, an H.265 playback smoke test, with its clip.

## Approach
- **Contract unchanged.**
  - The same COM and MF handling at the same points (P1, P6): the MTA joined (`COINIT_MULTITHREADED |
    COINIT_DISABLE_OLE1DDE`) around `MFStartup(MF_VERSION, MFSTARTUP_LITE)` in instance init and left again
    (`:209-217`), `MFShutdown` in dispose only after a start-up that returned `S_OK` (`:283-284`), and no apartment
    set-up on any other thread. Once US-051 has landed, that means its fixed handling, and slice 1 captures the
    goldens after it.
  - The same enumeration flags and MFT choice order.
  - The same output types and caps.
- **Crate.** Modules are added to `fxplugins-rs` under `#[cfg(windows)]`. `mfwrapper_init` is exported with the C
  prototype (`fxplugins.c:34`).
- **COM rules.** Smart pointers only. The `#[implement]` buffer keeps its GstBuffer ref and map while any MF
  reference exists, as `CMFGSTBuffer` did, and answers `QueryInterface` for the same IIDs
  (`mfgstbuffer.cpp:162-183`).

### Slices
1. **Test media and goldens.**
   - Reuse US-051's 8-bit Main clip and its test, and add a 10-bit Main10 clip generated the same way: a few frames,
     with a size that exercises cropping.
   - The trace driver plays them through `qtdemux ! mfwrapper` into a recording sink. It records caps, per-frame
     PTS and duration, and a hash of each frame's bytes, plus flush and seek, EOS drain, and a corrupt `hvcC`. One
     seek is issued from a thread in an STA, as from the JavaFX Application Thread.
   - Capture from C on Windows, recording the MFT that was found.
2. **`IMFMediaBuffer` in Rust.**
   - It replaces `CMFGSTBuffer` behind an option, through one internal factory function called where the C++
     does `new CMFGSTBuffer` (`mfwrapper.cpp:348`).
   - Gate: goldens exact, plus a test that holds the sample past the element's release.
3. **HVCC parser in Rust.**
   - Goldens come from the C function over a byte corpus. A test-only build compiles `mfwrapper_get_hevc_config`
     (non-static, `:1763`) into the driver.
   - The corpus: valid input, truncation at every field, oversized NAL lengths, empty arrays, maximum counts.
   - Gate: identical output bytes and return values.
4. **Element in Rust.**
   - Covers caps negotiation, the MFT and colour-converter setup, the input/output loop, flush, drain and
     errors.
   - Gate: goldens exact.
5. **Delete the C++.** Remove the four files and their CMake lines. `Mfplat.lib` and `mfuuid.lib` stay while
   needed.

## Acceptance criteria
- `dumpbin /exports fxplugins.dll` identical to the pre-port list.
- Goldens exact on a Windows host with an HEVC MFT. The host and MFT are named in the PR.
- The module Java suite passes.
- The four files deleted.
- No hand-written `AddRef`/`Release`.
- `unsafe` only in the `ffi` and `sys` modules, each block with a `// SAFETY:` comment.
- clippy and rustfmt clean.

## Definition of Done
PR merged after review; verified on Windows; the C++ deleted; `backlog/README.md` updated. Linux is unaffected
(the element is Windows-only); WSL confirms that the Linux `fxplugins` still builds and passes.

## Risks
| # | Risk | Mitigation |
| --- | --- | --- |
| 1 | No HEVC decoder MFT on the maintainer's machine or CI, so parity cannot run | Slice 1 records the MFT; without one the story is blocked on that capability, never passed on skipped tests |
| 2 | Frame hashes differ between runs | H.265 decoding is bit-exact by specification, so a mismatch is investigated, not tolerated |
| 3 | The creating thread is already in an STA, so `CoInitializeEx` (`:211`) returns `RPC_E_CHANGED_MODE`. No in-tree code was found that leaves a thread there: dshowwrapper undoes each of its `CoInitialize(NULL)` calls on every path out of the function (`dshowwrapper.cpp:1144`/`:1236-1237`, `:1325`/`:1386-1387`, `:2743`/`:2764-2765`) | Keep the C's handling verbatim: no `CoUninitialize`, and `MFStartup` still called (`:211-217`); the trace records the `HRESULT` |
| 4 | `windows` crate API churn | Version pinned in `Cargo.lock` and vendored |

