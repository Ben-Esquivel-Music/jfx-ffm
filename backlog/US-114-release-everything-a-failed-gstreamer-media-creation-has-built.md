# US-114 — Release everything a failed GStreamer media creation has built

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, US-040's independent review; every
site below re-read for this story in the US-040 working tree, nothing built or run

## Story
As a JavaFX app developer whose media fails to open,
I want a failed media creation on the GStreamer platform to release everything it built,
so that a failed attempt leaks nothing and no GStreamer element outlives the call that made it.

## Problem
Paths are relative to `modules/javafx.media/src/main/native/jfxmedia`; files in `platform/gstreamer` are named alone, a
bare line number is in `GstPipelineFactory.cpp`, and files US-040 adds or changes are cited as in its working tree
(uncommitted, on `29f69dc205`).

US-040 releases what `CreateSourceElement` made at its own three failure returns (`:288-329`); no later failure return
of `jfxm_media_create` releases a GStreamer object. What was built is floating or held only by a floating pipeline,
and the stack `GstElementContainer` listing it only clears its map (`GstElementContainer.cpp:36-44`), so it leaks
(`ffi/jfxmedia_api.cpp:337-351` records it). A source is the `javasource` element or the bin holding it and a buffer.
- `CreatePlayerPipeline`: `:119-120` (the HLS audio source failed) abandons the main source; `:131-132` every source.
- `CreatePipeline`: `:156-157`, `:194-195` (no appsink), `:204` and, passing on their callee's failure, `:173-174`,
  `:179-180` and `:185-186` abandon the sources; `:165-166` and `:199-200` also the appsink of `:155` or `:193` unless
  a bin took it. `CreateHLSPipeline` fails before building anything at `:637`, `:645`, `:674` (Windows), `:690`,
  `:705`, `:733` (Linux).
- `CreateAudioPipeline`: `:751-752` abandons the source; `:753-754` the floating pipeline of `:750`; `:761-762`,
  `:765-766` and `:771-772` that pipeline with the source in it, and the audio bin while the pipeline does not hold it.
- `CreateAVPipeline`: `:810-811` abandons the sources; the 12 returns from `:815-816` to `:886-887` the floating
  pipeline of `:809` with what it holds, and the sources, the demuxers of `:814` and `:835` and the audio and video
  bins that are not in it.
- `CreateAudioBin`: its 16 (Windows) or 19 (Linux) returns from `:911` on abandon the floating bin of `:900` with its
  children; `:961` and `:965` also whichever of the equalizer and spectrum elements was made and not yet added,
  `:1001` the pad reference taken at `:996`.
- `CreateVideoBin`: `:1048`, `:1081`, `:1090`, `:1096` and `:1101` abandon the floating bin of `:1041` with its
  children, and the decoder, the queue and the appsink while they are not in it (`:1079`, `:1085`).
- `AttachToSource`: `:528-529`, `:533-537` and `:539-543` keep the reference on the `hlsprogressbuffer` taken at `:519`
  or `:523` (`:1166`), which only success drops (`:547`). With a progress buffer it adds no element: the buffer's pad,
  after creation, does (`:486-502`, `:448`).
- A failed `gst_bin_add` (`:505`, `:753`, `:821`, `:830`, `:868`, `:912`, `:920`, `:941`, `:951`, `:974`, `:986`)
  leaves no element of its own floating. On a name clash the vendored GStreamer has sunk and released the element it
  was given, and an element that already has a parent stays its parent's (`gstbin.c:1385-1400`, in
  `modules/javafx.media/src/main/native/gstreamer/gstreamer-lite/gstreamer/gst`). The return after it abandons only
  what the function already held.
- `MediaManagement/MediaManager.cpp:140-145` allocates a `CPipelineOptions` (its one caller passes none:
  `ffi/jfxmedia_api.cpp:311`), and `MediaManager.cpp:164` returns without deleting it whenever `CreatePlayerPipeline`
  fails (`MediaManager.cpp:148`); only a pipeline object deletes it (`PipelineManagement/Pipeline.cpp:54-55`).
- When `new CMedia` fails (`MediaManager.cpp:154-160`), `delete pPipeline` frees the options but not the GStreamer
  pipeline: `~CPipeline`'s `Dispose()` (`Pipeline.cpp:57`) is the empty base one (`Pipeline.cpp:99-101`), and neither
  `~CGstAudioPlaybackPipeline` (`GstAudioPlaybackPipeline.cpp:102-110`) nor `~CGstAVPlaybackPipeline`
  (`GstAVPlaybackPipeline.cpp:73-79`) calls the `Dispose` that unrefs it (`GstAudioPlaybackPipeline.cpp:439`).

After a successful creation the media's dispose releases the pipeline (`GstAudioPlaybackPipeline.cpp:439`) and, for an
audio-video pipeline, the audio and video bins while `m_bHasAudio` or `m_bHasVideo` is false, by a plain unref
(`GstAVPlaybackPipeline.cpp:164-168`); a bin no pad has added is still floating there. A stream that needs a buffer and
is not HLS gets a `progressbuffer` (`:292`, `:310`), and with it `AttachToSource` adds no element: the buffer's pad,
once added, does (`:486-502`, `:448`). A media disposed before that leaves the audio bin `CreateAudioPipeline` attaches
at `:764`, or the demuxer `CreateAVPipeline` makes at `:814` and attaches at `:823`, unreleased: neither is in the
pipeline, and neither `CGstAudioPlaybackPipeline::Dispose` (`GstAudioPlaybackPipeline.cpp:386-456`) nor
`CGstAVPlaybackPipeline::Dispose` (`GstAVPlaybackPipeline.cpp:143-169`) releases it. Found while this story was filed;
reasoned from the code, not run.

## Requirement
1. **Nothing built survives a failed creation.** When `jfxm_media_create` fails on the GStreamer platform after a
   `CreateSourceElement` succeeded, a failed `new CMedia` included, every GStreamer object made on the way is
   finalized exactly once, before the call returns, and none while floating; every extra reference taken is dropped.
2. **The options are freed.** Each `CPipelineOptions` that `CMediaManager::CreatePlayer` allocates is deleted exactly
   once, whatever the outcome (`:73-90` and US-040's three returns included): by the pipeline object that took it, or
   by `CreatePlayer` when none did.
3. **Dispose releases what no pad added.** When a media `jfxm_media_create` made on the GStreamer platform is
   disposed, every element its creation made that the pipeline does not hold (a demuxer or bin no pad has added yet)
   is finalized exactly once, none while floating.

## Constraints
- **Same errors, same pipelines.** Each failure return reports today's MediaError; a successful creation is as today.
- **One owner per adapter.** On a failed creation each `CFfiStreamCallbacks` adapter is freed once, before
  `jfxm_media_create` returns: by the `close-connection` closure notify when its `javasource` is finalized
  (`:241-260`), else by `InitGstMedia` (`ffi/jfxmedia_api.cpp:301-309`, `:358-359` there); Java frees its upcall stubs
  right after, and `close_connection` is not invoked (`ffi/jfxmedia_api.cpp:353-357`). The comments at `:241-244` and
  `ffi/jfxmedia_api.cpp:337-351`, which describe the abandonment, say what the code does afterwards.
- **No release after GStreamer's own.** An element a failed `gst_bin_add` has released is not released again, and a
  fault that replaces a `gst_bin_add` releases the element as a name clash does.
- **Not while a pad can still add it.** `OnBufferPadAdded` holds its element without a reference (`:498`) and checks
  no dispose flag (`:430-481`): no element is released while that handler can still run with it.
- **ABI unchanged.** `JFXM_ABI_VERSION` stays `4u` (`jfxmedia_api.h:96`); the shipped `jfxmedia` exports the same 58
  `jfxm_*` names. A GLib or GStreamer function that the change or its hooks call and that the Windows `glib-lite.def`
  or `gstreamer-lite.def` does not export is added there (`gst_object_ref_sink` is not exported: `:298`). New fault
  points and watches are seams in `ffi/JfxmTestHooks.h`, empty `static inline` functions in the shipped build.
- **Code rules.** Each file's media C++ style, LF, no tabs; second copyright year 2026; comments only for a "why".

## Acceptance criteria
1. **Each return releases.** With US-040's hooks build (target `jfxmedia_test`, in `target/native/test-bin`;
   `jfxm_test_reset`, `jfxm_test_arm_source_fault` and `jfxm_test_counter`, bound by `JfxmTestHooksNative` in a
   child JVM), one test per return listed that its platform compiles arms a fault there, like
   `SourceElementReleaseTest`. It asserts today's error; every object the path made finalized exactly once, none while
   floating; as many adapters and options objects destroyed as constructed; `JfxMediaNative.registrySize()` back to
   its start. Today the hooks fault only three calls in `CreateSourceElement` and watch only `javasource` elements and
   source bins (`ffi/jfxmedia_test_hooks.h:45-59`).
2. **US-040's returns free the options.** The three faults of `SourceElementReleaseTest` also show the options freed.
3. **Dispose releases.** With the same build, one test per pipeline (audio, audio-video) disposes a media whose source
   has a `progressbuffer` before the buffer's pad is added, and asserts every element the creation made finalized
   exactly once, none while floating.
4. **Mutant proof.** With the release removed in a scratch copy at one return of each function listed, and on the
   options and `CMedia` paths, that return's test fails; with a dispose release of requirement 3 removed, criterion 3's
   test fails. The repository version passes.
5. **Gate.** `mvn -pl modules/javafx.media -am test` passes on Windows and in WSL Linux, per-class counts as before
   plus the new tests; `SourceElementReleaseTest` and
   `JfxMediaNativeTest.disposingAMediaClosesTheConnectionTheStreamCallbacksNeverClosed` pass unchanged; platforms and
   counts recorded here. CI is green (macOS compiles the shared code but cannot build the hooks library:
   `modules/javafx.media/native/CMakeLists.txt:75-77`).

## Out of scope
Failures after `jfxm_media_create` returned, which the media's dispose path releases apart from requirement 3; the
AVFoundation backend.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1, 3 and 4.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- `MediaManager.cpp:121-165` (`0e436f6474`, 2013) and `:117-120` (`b86e6771b9`, 2024) predate the fork point
  `868c4801ec`; no other line was blamed. The owner approved filing this story on 2026-11-15.
- Reachable with no fault (reasoned, not run): an MP3 on Linux without `avplugin` fails at `:937-939`, whose
  `avaudiodecoder` is `avplugin`'s. Java offers MP3 to GStreamer on Linux either way (the `CONTENT_TYPES` of
  `GSTPlatform.java`). `avplugin` is built only where pkg-config finds the ffmpeg libraries
  (`modules/javafx.media/native/linux.cmake:395`): the WSL of US-040 built none, and the later Linux AV returns need it.
