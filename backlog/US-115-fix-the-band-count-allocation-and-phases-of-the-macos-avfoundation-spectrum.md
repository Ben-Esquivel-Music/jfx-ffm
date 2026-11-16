# US-115 — Fix the band count, allocation and phases of the macOS AVFoundation spectrum

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, US-040's independent review; the three
sites re-read for this story the same day. Nothing on macOS was compiled or run for US-040 or for this story.

## Story
As a JavaFX app developer who shows an audio spectrum on macOS,
I want the spectrum of media that AVFoundation plays to carry the bands each message computed and their real phases,
so that a band-count change invents no bands, phases are not magnitudes, and no allocation on the audio thread throws.

## Problem
Native paths are relative to `modules/javafx.media/src/main/native`, Java paths to
`modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl`. A line number without a file is in
`jfxmedia/platform/osx/avf/AVFAudioSpectrumUnit.cpp`, which US-040 leaves unchanged and only `jfxmedia_avf` compiles
(`modules/javafx.media/native/mac.cmake:686-692`). `gstspectrum.c` is cited as in the US-040 working tree, in
`gstreamer/gstreamer-lite/gst-plugins-good/gst/spectrum`. On macOS AVFoundation plays MP3, M4A, MP4 and HLS
(`platform/osx/OSXPlatform.java:46-54`), GStreamer only WAV and AIFF (`platform/gstreamer/GSTPlatform.java:62-65`).

`PostMessageCallback` (`:230-274`) handles each message of the vendored `spectrum` element on the audio tap thread:
`ProcessAudioTap` calls `ProcessBufferLists` (`jfxmedia/platform/osx/avf/AVFAudioProcessor.mm:214`, `:236` there),
which runs the element (`:126`), which calls back (`gstspectrum.c:1045-1046`).
1. **The band count comes from `GetBands()`.** `:252` takes the count Java last set (`:157`, read unlocked at
   `:164-166`), and `:258-263` index the message's magnitude and phase lists with it. `SetBands` changes it at once;
   the element gets it only when the next `ProcessBufferLists` rebuilds it (`:92-94`, `:296`). A change that lands
   while the element runs makes `:252` read a message computed for the old count with the new one:
   - after a raise, each lookup past a list's end returns NULL (`gstreamer/gstreamer-lite/gstreamer/gst/gstvalue.c`,
     `:991-997` there) and `g_value_get_float` 0, silently, as the bundled GLib is built with `GSTREAMER_LITE`
     (`gstreamer/3rd_party/glib/glib/gmessages.h:697-727`): those bands arrive as 0 dB;
   - after a lowering, the first bands of a message for more bands arrive as a spectrum of fewer, other bands.

   Reasoned, not run: a count above the element's maximum of 1024 (`gstspectrum.c:72`, `:177-181`) makes the raise
   case permanent. GLib refuses it (`gstreamer/3rd_party/glib/gobject/gobject.c:2207-2217`), the element keeps its
   default 128 (`gstspectrum.c:84`), and Java accepts any count from 2 up (`NativeAudioSpectrum.java:107`, `:127`).
2. **A throwing allocation in the audio tap's callbacks.** `:255-256` use plain `new float[]`; a `std::bad_alloc` there
   unwinds through the element's C frames and `ProcessAudioTap` into MediaToolbox. `SetChannelCount` does the same
   with `new AVFEQBandHistory[]` (`jfxmedia/platform/osx/avf/AVFAudioEqualizer.cpp:86`), and the tap's prepare callback
   reaches it: `PrepareAudioTap` (`jfxmedia/platform/osx/avf/AVFAudioProcessor.mm:166`) stores the channel count
   (`:194` there) and calls `ResetBandParameters` (`:195`), which calls `SetChannelCount` on every band
   (`AVFAudioEqualizer.cpp:373`), so a `std::bad_alloc` there unwinds into MediaToolbox from the prepare callback. The
   equalizer's band calls reach it too, on the caller's thread (`AVFAudioEqualizer.cpp:51`, `:312`, `:324`, `:342`).
   The tap's init callback, `InitAudioTap` (`AVFAudioProcessor.mm:148`, installed at `:95` there), makes the tap's
   context with a plain `new AVFTapContext` (`AVFAudioProcessor.mm:152`), so a `std::bad_alloc` there unwinds into
   MediaToolbox from the init callback; the prepare and process callbacks use that context without a NULL check
   (`AVFAudioProcessor.mm:187`, `:228`). Beyond the tap's callbacks, `platform/osx` holds 8 more plain `new`, listed
   here and not examined: `CVVideoFrame::ConvertToFormat` (`jfxmedia/platform/osx/CVVideoFrame.mm:201`),
   `AVFAudioEqualizer::AddBand` (`AVFAudioEqualizer.cpp:307`), `-[AVFAudioProcessor init]`
   (`AVFAudioProcessor.mm:53-55`) and the track objects of `-[AVFMediaPlayer extractTrackInfo]`
   (`jfxmedia/platform/osx/avf/AVFMediaPlayer.mm:1100`, `:1158`, `:1173`); where each runs, and which frames lie
   between it and a handler, is not established. Two of them lie on paths that throw elsewhere too, read and not
   examined on macOS, with no handler between the throw and `extern "C"`: `jfxm_eq_add_band`
   (`jfxmedia/ffi/jfxmedia_api.cpp:1151-1159`) calls `AddBand`, whose map insert `mEQBands[frequency]`
   (`AVFAudioEqualizer.cpp:306`) can throw `std::bad_alloc`, and `jfxm_frame_convert` (`jfxmedia_api.cpp:1103-1110`)
   calls `ConvertToFormat`, whose `CVVideoFrame` constructor throws `const char *` (`CVVideoFrame.mm:53`, `:68`, `:75`,
   and `:129-160` in the `PrepareChunky` or `PreparePlanar` it calls). US-040's sweep left out `platform/osx`, and the
   `bad_alloc` list of `jfxmedia/jfxmedia_api.h:34-52` (US-040 working tree) names none of these sites or paths: for
   those two functions it names only `CGstAudioEqualizer::AddBand` and `CGstVideoFrame::ConvertToFormat`.
3. **The magnitudes are passed as phases.** `UpdateBands` passes `magnitudes` for both arrays (`:199`) and never reads
   its `phases` parameter (`:190`), so Java's phases are a copy of the magnitudes.
4. **The element's start and stop reset outside its lock on macOS.** `gst_spectrum_start` and `gst_spectrum_stop` reset
   the channel data under `spectrum->lock` only `#if defined (GSTREAMER_LITE) && !defined (OSX)`
   (`gstspectrum.c:414-425`, `:435-442`); on macOS the reset runs outside the lock, as upstream has it.
   `gst_spectrum_set_property` frees the same data under the lock when the band count, interval or channel mode changes
   (`gstspectrum.c:336-368`), and `gst_spectrum_transform_ip` uses it under the lock (`gstspectrum.c:919`, `:1077`).
   US-040 left macOS out because this unit holds `mBandLock` across `g_object_set` and `gst_element_set_state`
   (`:279-321`, `:325-334`) and takes it again in `UpdateBands` (`:192`), which the element's message callback
   `PostMessageCallback` calls (`:264`) while `transform_ip` holds `spectrum->lock` (`gstspectrum.c:1045-1046`):
   locking start and stop there would take the two locks in opposite orders. `SetupSpectralProcessor` takes
   `spectrum->lock` under `mBandLock` today: `gst_spectrum_setup_api` (`:314`) locks it (`gstspectrum.c:622`). For
   this unit's element `mBandLock` already serialises the property sets and the state changes, so a property set
   cannot meet a stop there; the pair left unprotected is a start or stop reset against a `transform_ip` on the audio
   tap thread. Whether the two can overlap was not established: the unit starts and stops its element from
   `ProcessBufferLists`, on the tap thread (`:93`, `:277`), and in its destructor (`:60`), which cannot run while a tap
   context holds its reference (`jfxmedia/platform/osx/avf/AVFAudioProcessor.mm:136`, deleted at `:162`). macOS's
   GStreamer pipeline (WAV, AIFF) keeps the upstream reset as well, so the pair US-040 closed on Windows and Linux, a
   start or stop reset against a band-count change, is still open there. Older than US-040; reasoned from the code,
   not reproduced; macOS was not compiled.

US-040 fixes 1 and 2 for GStreamer (`jfxmedia/platform/gstreamer/GstAudioPlaybackPipeline.cpp:1527-1570` in its
working tree): the count is the message's, a message for another count than the installed arrays' writes nothing and
sends no event, and a failed `new (nothrow)` drops the message with an error log line.

## Requirement
1. **The message's own count.** The handler takes the band count from the message's lists and never looks up an index
   at or past a list's size.
2. **A stale message is dropped.** A message whose count is not that of the arrays installed when it is handled writes
   nothing and sends no event, as on GStreamer after US-040.
3. **No throwing allocation in the handler, in `SetChannelCount` or in `InitAudioTap`.** A failed allocation in the
   handler drops the message with an error log line; one in `SetChannelCount` logs an error line and leaves that band
   without a history, so that `ApplyFilter` skips it (`AVFAudioEqualizer.cpp:240`); one in `InitAudioTap` logs an
   error line and leaves the tap without a context, which no later callback of the tap then reads. Every other plain
   `new` under `jfxmedia/platform/osx` is either non-throwing with a failure path, or the results here show where it
   runs and that no C or Objective-C frame (AVFoundation, MediaToolbox, CoreVideo, GLib, the spectrum element) lies
   between it and a C++ handler. On macOS no C++ exception leaves `extern "C"` from `jfxm_eq_add_band` or
   `jfxm_frame_convert`, or, where one still can under memory exhaustion, the note at `jfxmedia/jfxmedia_api.h:34-52`
   names it; after this story that note names every escape out of `extern "C"` left under `jfxmedia/platform/osx`.
4. **Phases are phases.** Java receives the message's phases.
5. **Start and stop reset safely on macOS.** No reset in `gst_spectrum_start` or `gst_spectrum_stop` runs while another
   thread frees or uses the same channel data in `gst_spectrum_set_property` or `gst_spectrum_transform_ip`, for this
   unit's element and for the element of macOS's GStreamer pipeline.

## Constraints
- **The audio thread waits on nothing new:** no lock and no call into Java that it does not make today; the holder is
  still released outside the lock (`:149-153`).
- **Steady state unchanged:** with a constant count the same magnitudes arrive at the same times, the event timestamp
  still left to Java (`:202-207`).
- **One lock order:** `spectrum->lock` and `mBandLock` are never taken in opposite orders; today the tap thread takes
  each while it holds the other (item 4).
- **Nothing else moves:** only `jfxmedia/platform/osx`, code that only macOS compiles, the note at the top of
  `jfxmedia/jfxmedia_api.h` and tests change; `JFXM_ABI_VERSION` stays `4u` (`jfxmedia/jfxmedia_api.h:96`), no export
  is added. Media C++ style, LF, no tabs, second copyright year 2026.

## Acceptance criteria
On the macOS host (the Mac mini):
1. **Band changes deliver full arrays.** A test plays a tone that AVFoundation decodes, from a small, documented,
   license-clean fixture, with the spectrum on, and lowers and raises the band count in rounds during playback, like
   `test.com.sun.media.jfxmediaimpl.SpectrumBandChangeTest`. Each round ends with a delivery of full arrays of the new
   count whose strongest band is the tone's; no delivery holds a magnitude of 0 dB or more. A count above 1024 gets
   no event.
2. **Phases.** In every delivery the phase array differs from the magnitude array, and every phase lies in [-π, π]
   (`gstspectrum.c:819`, `:840`: a mean of `atan2` values).
3. **No throwing `new`.** From the repository root this prints no line in `PostMessageCallback`, `SetChannelCount` or
   `InitAudioTap`, and for each line it does print the results here show where it runs and that no C or Objective-C
   frame lies between it and a C++ handler. Today it prints `:255`, `:256`, `AVFAudioEqualizer.cpp:86`,
   `AVFAudioProcessor.mm:152` and the 8 sites of item 2:
   ```
   git grep -nP '\bnew\s+(?!\((std::)?nothrow\))[A-Za-z_][\w:]*\s*[\[(]' -- '*jfxmedia/platform/osx/*'
   ```
   The results also list each path from an `extern "C"` function into `jfxmedia/platform/osx` that can throw, with
   its handler or its line in the note of `jfxmedia/jfxmedia_api.h`; an independent review repeats the list.
4. **Start and stop.** A test plays a WAV, which GStreamer plays, and a tone AVFoundation decodes, each with the
   spectrum on, and in rounds changes the band count and stops, restarts and disposes the player. Every round ends
   within a bound written in the test, and no run leaves an `hs_err_pid*` file. An independent review lists each place
   that takes `spectrum->lock` or `mBandLock`, with the locks held there, and finds one order. No test provokes the
   race, which was never reproduced, so this criterion has no mutant.
5. **Mutant proof.** With each of the three changes undone in a scratch copy, its criterion fails (1 for the count,
   2 for the phases, 3 for the allocation). The repository version passes.
6. **Gate.** `mvn -pl modules/javafx.media -am test` passes before and after, per-class counts as before plus the new
   tests, which run against the shipped `jfxmedia_avf` (the hooks build refuses macOS:
   `modules/javafx.media/native/CMakeLists.txt:75-77`). CI's two macOS jobs are green.

## Out of scope
The spectrum of WAV and AIFF on macOS, which GStreamer computes (US-040), apart from requirement 5; the rest of
`platform/osx`, apart from its plain `new` and the exceptions that leave `extern "C"` through it (requirement 3).

## Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 to 3.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Named by US-040's independent review and again by its confirming review; filing approved by the owner on
  2026-11-15. `:199` and `:255-256` come from upstream `c9519b6ea2` (2020, 8232589), before the fork point.
- No test reaches this code today: `SpectrumBandChangeTest` plays a WAV, which goes to GStreamer on macOS. CI's macOS
  runners have no audio output (US-040's confirming review), so criterion 1 runs on the macOS host only.
- `AVFAudioEqualizer.cpp:86` was found while this story was filed, `AVFAudioProcessor.mm:152` when it was checked,
  and item 4 came from US-040's own lock change in `gstspectrum.c`; all three were added on 2026-11-15. The 8 other
  plain `new` of item 2 were seen while US-040's record was written, and added, unexamined, the same day; its two
  `extern "C"` paths were seen while US-040's record was fact-checked, 2026-11-15.
