# US-034 — Port the mfwrapper H.265 decoder element to Rust inside fxplugins

**Status:** 📋 Ready (drafted 2026-09-30 from a read-only survey; read: `mfwrapper.cpp` and `mfgstbuffer.cpp`
excerpts (COM object, decoder enumeration, the HVCC parser, COM/MF start-up), the H.265 pipeline selection; not
checked: whether this host has an HEVC decoder MFT, the exact `windows` crate coverage, the colour-converter
chain and the pad caps in detail; nothing built) · **Epic:** Rust port of the remaining native code (goal 3) ·
**Blocked by:** US-027, US-032 (javasource port: crate, link recipe, trace driver)

## Story
As a JavaFX app developer playing H.265 MP4 on Windows,
I want the Media Foundation decoder element implemented in Rust with the `windows` crate,
so that COM reference counting, the GstBuffer-backed `IMFMediaBuffer` and the parsing of untrusted `hvcC` bytes are
memory-safe, with no change to decoded frames.

## Why Rust fits here
Paths as in the javasource port story.
- **Must stay native (R1):**
  - It is a `GstElement` subclass (`gstreamer/plugins/mfwrapper/mfwrapper.cpp:121`) that drives Media Foundation
    transforms on GStreamer's streaming thread (`MFTEnumEx` `:1737`, `ProcessInput` `:620`).
  - It initialises COM (MTA) and MF on that thread (`:211-214`).
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
  - `Win32::System::Com` for `CoInitializeEx`.
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
- **Threads.** The GStreamer streaming thread runs `ProcessInput`/`ProcessOutput`, with COM MTA on it (`:211`).
- **Tests.** None.

## Approach
- **Contract unchanged.**
  - The same apartment and MF start-up per thread (`COINIT_MULTITHREADED` + `COINIT_DISABLE_OLE1DDE`,
    `MFSTARTUP_LITE`), with matching shutdown.
  - The same enumeration flags and MFT choice order.
  - The same output types and caps.
- **Crate.** Modules are added to `fxplugins-rs` under `#[cfg(windows)]`. `mfwrapper_init` is exported with the C
  prototype (`fxplugins.c:34`).
- **COM rules.** Smart pointers only. The `#[implement]` buffer keeps its GstBuffer ref and map while any MF
  reference exists, as `CMFGSTBuffer` did, and answers `QueryInterface` for the same IIDs
  (`mfgstbuffer.cpp:162-183`).

### Slices
1. **Test media and goldens.**
   - Commit two tiny H.265 MP4s, 8-bit Main and 10-bit Main10. They are generated in WSL by a command recorded
     next to them: a few frames each, with a size that exercises cropping.
   - The trace driver plays them through `qtdemux ! mfwrapper` into a recording sink. It records caps, per-frame
     PTS and duration, and a hash of each frame's bytes, plus flush and seek, EOS drain, and a corrupt `hvcC`.
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
| 3 | The streaming thread was already initialised STA (dshowwrapper calls `CoInitialize(NULL)` on its threads, `dshowwrapper.cpp:1144`), so `CoInitializeEx` MTA returns `RPC_E_CHANGED_MODE` | Keep the C's handling of that result verbatim; the trace records the `HRESULT` |
| 4 | `windows` crate API churn | Version pinned in `Cargo.lock` and vendored |

