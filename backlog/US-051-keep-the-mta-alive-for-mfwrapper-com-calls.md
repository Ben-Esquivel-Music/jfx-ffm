# US-051 — Keep the MTA alive for mfwrapper's COM calls on GStreamer threads

**Status:** 📋 Ready (filed 2026-10-01 from a review of US-034; read: `mfwrapper.cpp`'s instance init and dispose,
where it creates the decoder MFT and the colour converter and their callers, jfxmedia's creation of the element,
and every `CoInitialize`, `CoInitializeEx`, `OleInitialize` and `CoIncrementMTAUsage` call in the media natives and
Glass Windows (one `git grep`, then the context of each hit); checked after review: every jfxmedia AV pipeline has a
DirectSound sink before the element exists, and javasource pushes a flushing seek's `FLUSH_STOP` on the seeking
thread (Problem 2 and 4); not checked: whether Media Foundation's own threads hold the MTA after `MFStartup`, how
qtdemux and the queue pass that flush on, the Java side of `seek`, the COM facts below (Microsoft's documentation,
from knowledge), and whether any test reaches the element; nothing built or run) · **Found:** 2026-10-01, review of
US-034 · **Blocked by:** none · **Blocks:** the mfwrapper Rust port (US-034)

## Story
As a JavaFX app developer playing H.265 MP4 on Windows,
I want the Media Foundation decoder element to keep the multithreaded apartment (MTA) alive for as long as it
makes COM calls,
so that creating and driving its decoder and colour converter does not depend on another thread happening to hold
the MTA.

## Problem
Paths relative to `modules/javafx.media/src/main/native/` unless they start with `modules/`.
1. **The element leaves the MTA at once.** `gst_mfwrapper_init` (`gstreamer/plugins/mfwrapper/mfwrapper.cpp:186`)
   joins the MTA (`:211`), starts Media Foundation (`:214`) and calls `CoUninitialize` (`:216-217`) before it
   returns. No other line of the element initialises COM.
2. **Its COM calls come later, mostly on GStreamer threads that hold no apartment.**
   - The decoder MFT is enumerated and activated (`MFTEnumEx` `:1737`, `ActivateObject` `:1750`) from the
     `is-supported` getter, which jfxmedia reads in the demuxer's `pad-added` handler right after creating the
     element (`jfxmedia/platform/gstreamer/GstAVPlaybackPipeline.cpp:579-583`, `:203-204`).
   - `FLUSH_STOP` creates it again (`mfwrapper.cpp:1580`, `:1519`) on the thread that delivers the flush. jfxmedia's
     seek is always flushing and runs synchronously on its caller (`CGstAudioPlaybackPipeline::Seek`,
     `jfxmedia/platform/gstreamer/GstAudioPlaybackPipeline.cpp:655`, `:675`, then `:606-609`, `:627`, `:635`). In push
     mode javasource answers it in `java_source_perform_seek` (`gstreamer/plugins/javasource/javasource.c:418`, from
     `:519-521`) and pushes the `FLUSH_STOP` itself (`:500`); progressbuffer pushes one too (`progressbuffer.c:780`,
     caller not traced). So a seek's reload can run on the seeking thread, which may be in an STA: on Windows the
     JavaFX Application Thread is in Glass's STA
     (`modules/javafx.graphics/src/main/native-glass/win/glass_win_api.cpp:550-552`).
   - `mfwrapper_chain` (`:1446`) runs on the task of the video `queue` in front of the element
     (`GstAVPlaybackPipeline.cpp:289`). It calls `ProcessInput` and `ProcessOutput` (`mfwrapper.cpp:620`, `:1392`,
     `:1257-1267`) and, on a stream change, can create the colour converter with `CoCreateInstance` (`:872`, via
     `:1402`).
3. **The GStreamer threads are in the MTA only implicitly.** A thread that has not initialised COM runs in the process's
   implicit MTA while some other thread holds the MTA. With no MTA in the process, `CoCreateInstance` fails with
   `CO_E_NOTINITIALIZED`.
4. **Nothing in the element holds the MTA; today another element does.**
   - Every jfxmedia AV pipeline creates a DirectSound sink before the element exists: `CreateAVPipeline`
     (`jfxmedia/platform/gstreamer/GstPipelineFactory.cpp:773`) always calls `CreateAudioBin` (`:827-828`), which
     creates the audio sink (`:943`), on Windows `directsoundsink` (`:400`). The sink's device notifier joins the MTA
     on its own thread (`gstreamer/gstreamer-lite/gst-plugins-good/sys/directsound/gstdirectsoundnotify.cpp:243`)
     from the sink's instance init to its finalize (`gstdirectsoundsink.c:329`, `:193`, same directory).
   - So today's pipelines are covered by coincidence: the element relies on another element's private thread and
     declares nothing. The real exposure is a pipeline without that sink, such as US-034's trace driver
     (`qtdemux ! mfwrapper` into a recording sink, US-034 slice 1), which has no MTA holder unless Media
     Foundation's own threads hold one (not checked).
   - The only other in-tree MTA holders are the DirectShow baseclasses' worker threads
     (`gstreamer/3rd_party/baseclasses/wxutil.cpp:247`, where `COINIT_DISABLE_OLE1DDE` with no apartment flag means
     the MTA), which run only inside a dshowwrapper graph. Glass joins an STA (`OleInitialize`,
     `modules/javafx.graphics/src/main/native-glass/win/OleUtils.h:153`), and dshowwrapper's own `CoInitialize(NULL)`
     calls are each undone before their function returns.

## Proposed fix
1. Hold an MTA usage reference for the element's lifetime: `CoIncrementMTAUsage` in `gst_mfwrapper_init`, with the
   cookie kept in the instance, and `CoDecrementMTAUsage` as the last COM call of `gst_mfwrapper_dispose`, after
   every MF object is released and after `MFShutdown` (`mfwrapper.cpp:259-284`). Both calls exist since Windows 8,
   and the fork targets Windows 10. The increment does not put the calling thread in an apartment, so the
   `RPC_E_CHANGED_MODE` case of `:211` does not affect it.
2. Keep the `CoInitializeEx`/`CoUninitialize` pair around `MFStartup` (`:209-217`) as it is.
3. Make dispose safe to run twice, as GObject allows: clear the cookie after the decrement, and reset
   `hr_mfstartup` after `MFShutdown`, which `:283-284` does not do today.

The fix does not change the apartment of a reload on the seeking thread (Problem 2): there the MFT is enumerated and
activated in whatever apartment that thread is in, an STA on the JavaFX Application Thread. That is fine only if the
transform is free-threaded, which is expected but not checked; the apartment log below records that reload.

## Acceptance criteria
- `gst_mfwrapper_init` takes the MTA usage reference and `gst_mfwrapper_dispose` drops it after `MFShutdown`. Both
  are guarded, and a second dispose calls neither `MFShutdown` nor `CoDecrementMTAUsage`.
- A temporary log line records `CoGetApartmentType` and the thread at `ActivateObject` (`:1750`, at creation and at
  the seek's reload), at the first `ProcessInput` (`:620`) and at `CoCreateInstance` (`:872`) where it is reached.
- A run that can fail on the unfixed C: a scratch change, never committed, makes `CreateAudioSinkElement`
  (`GstPipelineFactory.cpp:397-400`) return `fakesink`, so no DirectSound sink holds the MTA. It runs with the log on
  the unfixed and on the fixed C, and the PR shows `CO_E_NOTINITIALIZED` (or the log's answer) before and success
  after. If the unfixed run still shows the implicit MTA, Media Foundation's own threads hold it; the PR says so and
  Problem 3-4 are corrected.
- A new module smoke test plays a tiny H.265 MP4 to EOS, seeks once and disposes the player. It cannot fail on the
  unfixed C, because the pipeline's DirectSound sink holds the MTA. The clip has a few frames and a size that
  exercises cropping, and is generated in WSL by a command recorded next to it (`backlog/README.md`, "Port rules");
  US-034's slice 1 reuses the clip and the test. The test needs an HEVC decoder MFT: without one the player reports
  `ERROR_MEDIA_H265_FORMAT_UNSUPPORTED` and the test is skipped with that reason. A failed activation reports the
  same error (`GstAVPlaybackPipeline.cpp:203-212`), so `-Djfx.media.requireHevc=true` turns the skip into a failure,
  and the PR names the host and MFT of the run that passed with it. No test reaches the element today.
- The module suite passes unchanged on Windows. WSL confirms that the Linux `fxplugins` still builds (the element
  is Windows-only).

## Definition of Done
Merged; verified on Windows on a host with an HEVC decoder MFT, named in the PR; no Rust involved;
`backlog/README.md` updated. US-034's goldens are captured after this lands. An upstream issue is drafted if
upstream's `mfwrapper.cpp` has the same pair; the PR checks.
