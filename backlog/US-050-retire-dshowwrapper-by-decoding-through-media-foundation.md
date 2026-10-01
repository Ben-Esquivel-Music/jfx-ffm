# US-050 — Retire dshowwrapper by decoding through Media Foundation

**Status:** 🔶 Needs a maintainer ruling (filed 2026-09-30 from the Rust-port survey of the media plugins; nothing
built or run). This is a behaviour change. H.264 can be exact, but AAC and MP3 would be decoded by a different
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
- It also removes the STA `CoInitialize(NULL)` calls on GStreamer streaming threads (`dshowwrapper.cpp:1144`, `:1325`,
  `:2743`), which the Media Foundation element has to work around (US-034, risk 3).

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
