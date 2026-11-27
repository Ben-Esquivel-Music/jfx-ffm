# US-050 — Retire dshowwrapper by decoding through Media Foundation

## In short
- **Problem:** On Windows, H.264, AAC and MP3 are decoded through DirectShow by a plugin (5,134 lines) that also needs
  37,810 lines of bundled Microsoft sample code. It is too large to port to Rust.
- **Seen failing?** No. No defect. This would be the largest native deletion in `javafx.media`.
- **Goal:** Decode through Media Foundation, as H.265 already does, and delete the plugin and the sample code.
- **Done when:** H.264 frames are identical to today's. AAC and MP3 come from a different decoder, so their sound can
  only be close, not identical.
- **Order:** Needs the owner's ruling first, because it changes behaviour: is "close" good enough for AAC and MP3? If
  not, the story is closed and the plugin stays.

**Status:** 🔶 Needs a maintainer ruling (filed 2026-09-30 from the Rust-port survey of the media plugins; nothing
built or run; 2026-10-01: dshowwrapper's `CoInitialize(NULL)` calls were re-read with their `CoUninitialize`
calls). This is a behaviour change. H.264 can be exact, but AAC and MP3 would be decoded by a different
decoder, so their parity is `tolerance` or `unprovable`. Behaviour-neutrality outranks the deletion unless the
maintainer accepts a bound. · **Epic:** Less native code (goal 1), routed here by the Rust-port survey · **Best
after:** US-034, whose Rust Media Foundation element is the natural host

## Story
As a platform maintainer,
I want Windows media decoding to go through Media Foundation decoder MFTs instead of the DirectShow graph of
`dshowwrapper`,
so that `dshowwrapper` (5,134 lines) and the vendored DirectShow `baseclasses` (37,810 lines) are deleted. That is
the largest native deletion available in `javafx.media`.

## Problem: why this story, and not a Rust port
- `dshowwrapper` builds a private DirectShow graph from its own filters on the SDK `baseclasses`
  (`gstreamer/plugins/dshowwrapper/dshowwrapper.cpp:1086`; `Src.h:55`, `:80`; `Sink.h:77`, `:101`; `Allocator.h:40`,
  `:59`).
- The `windows` crate has the DirectShow interfaces but not the baseclasses. The files that define the classes it
  uses total 13,160 `.cpp` and 4,467 `.h` lines, 2.6 times the plugin itself. So the survey's verdict on a port is
  KEEP.
- Retiring the plugin removes both it and the baseclasses.
- It also removes dshowwrapper's transient STA joins: `CoInitialize(NULL)` at `dshowwrapper.cpp:1144`, `:1325` and
  `:2743`, each undone before its function returns (`:1236-1237`, `:1386-1387`, `:2764-2765`).

## Approach
1. **Inventory.** List the caps `dshowwrapper` accepts, the pipelines that select it (`GstPipelineFactory`,
   `GstAVPlaybackPipeline`), and the Windows 10 in-box MFT for each format. H.264, AAC and MP3 are believed in-box,
   except on "N" editions (not checked).
2. **Goldens from the DirectShow path.** H.264 frame hashes, and AAC/MP3 PCM for a small committed corpus, generated
   by a recorded command.
3. **Maintainer ruling.**
   - H.264 must be exact: decoding is normative.
   - For AAC and MP3, the maintainer either accepts a written tolerance bound (e.g. per-sample error and SNR) before
     any code changes, or rules the story unprovable, which closes it as KEEP.
4. **Media Foundation elements for the formats**, in the Rust `mfwrapper` crate of US-034 or its C++ predecessor.
5. **Switch the pipelines, then delete** `dshowwrapper`, `baseclasses` and their CMake lines.

## Acceptance criteria
- H.264 frame hashes are identical to the DirectShow goldens.
- AAC/MP3 PCM is within the pre-agreed bound, or the story is closed as KEEP.
- The module and media tests pass on Windows.
- No DirectShow code or `baseclasses` file remains.

## Definition of Done
Merged and verified on Windows. The accepted bound is recorded in the tests. `backlog/README.md` is updated.
