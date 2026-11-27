# US-115 — Fix the audio spectrum and equalizer defects left by the review of US-040

## In short
- **Problem:** Four groups of defects in the audio spectrum and the equalizer, all found by reading the code during
  US-040.
  - Part 1 (macOS): after the number of spectrum bands changes during playback the listener gets empty or misplaced
    bands; the "phase" values are not phases; and memory is allocated on the audio thread in a way that can throw.
  - Part 2 (all platforms): more than 1024 spectrum bands is accepted, and then the listener gets no events at all.
  - Part 3 (macOS): changing an equalizer band's centre frequency takes the same lock twice on one thread, which can
    hang the application; other places leave the lock unbalanced; a converted video frame does not free its pixel
    buffer.
  - Part 4 (all platforms): after an application removes an equalizer band, changing that band calls into freed
    memory.
- **Seen failing?** No. Every item was read from the code. Nothing was run, and nothing on macOS was compiled for it.
- **Goal:** The spectrum delivers real data for every band count it accepts, and changing or removing an equalizer
  band can neither hang, crash nor touch another band.
- **Done when:** Tests in child JVMs (so that a hang or a crash fails the test and not the run) change the band
  count, exceed 1024 bands, move a band and change a removed band, and pass. Parts 1 and 3 run on the Mac, part 2 on
  Windows and WSL, part 4 on all three.
- **Order:** Can start now. Start with the hang of part 3 on the Mac mini: if it is real, it is urgent. An item that
  cannot be made to fail is dropped with one line. Merged on 2026-11-27 from US-115, US-117, US-118 and US-120; each
  part keeps its own text below.

**Status:** 📋 Ready (merged on 2026-11-27 from US-115, US-117, US-118, US-120 at the owner's request; no text of the parts
was changed, only their headings moved one level down)

## Part 1 — Fix the band count, allocation and phases of the macOS AVFoundation spectrum

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, US-040's independent review; the three
sites re-read for this story the same day. Nothing on macOS was compiled or run for US-040 or for this story.

### Story
As a JavaFX app developer who shows an audio spectrum on macOS,
I want the spectrum of media that AVFoundation plays to carry the bands each message computed and their real phases,
so that a band-count change invents no bands, phases are not magnitudes, and no allocation on the audio thread throws.

### Problem
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

### Requirement
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

### Constraints
- **The audio thread waits on nothing new:** no lock and no call into Java that it does not make today; the holder is
  still released outside the lock (`:149-153`).
- **Steady state unchanged:** with a constant count the same magnitudes arrive at the same times, the event timestamp
  still left to Java (`:202-207`).
- **One lock order:** `spectrum->lock` and `mBandLock` are never taken in opposite orders; today the tap thread takes
  each while it holds the other (item 4).
- **Nothing else moves:** only `jfxmedia/platform/osx`, code that only macOS compiles, the note at the top of
  `jfxmedia/jfxmedia_api.h` and tests change; `JFXM_ABI_VERSION` stays `4u` (`jfxmedia/jfxmedia_api.h:96`), no export
  is added. Media C++ style, LF, no tabs, second copyright year 2026.

### Acceptance criteria
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

### Out of scope
The spectrum of WAV and AIFF on macOS, which GStreamer computes (US-040), apart from requirement 5; the rest of
`platform/osx`, apart from its plain `new` and the exceptions that leave `extern "C"` through it (requirement 3).

### Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 to 3.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

### Notes
- Named by US-040's independent review and again by its confirming review; filing approved by the owner on
  2026-11-15. `:199` and `:255-256` come from upstream `c9519b6ea2` (2020, 8232589), before the fork point.
- No test reaches this code today: `SpectrumBandChangeTest` plays a WAV, which goes to GStreamer on macOS. CI's macOS
  runners have no audio output (US-040's confirming review), so criterion 1 runs on the macOS host only.
- `AVFAudioEqualizer.cpp:86` was found while this story was filed, `AVFAudioProcessor.mm:152` when it was checked,
  and item 4 came from US-040's own lock change in `gstspectrum.c`; all three were added on 2026-11-15. The 8 other
  plain `new` of item 2 were seen while US-040's record was written, and added, unexamined, the same day; its two
  `extern "C"` paths were seen while US-040's record was fact-checked, 2026-11-15.

## Part 2 — Keep the audio spectrum band count within what the spectrum can compute (was US-117)

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, US-040's record ("Behaviour changes a
user can see"); the sites re-read for this story the same day, nothing built or run · **Related:** US-040, US-115

### Story
As a JavaFX app developer who shows an audio spectrum,
I want every band count that `MediaPlayer` accepts to bring spectrum events with real data,
so that a large count cannot silence my `AudioSpectrumListener` and no count reaches a spectrum that cannot compute it.

### Problem
`MediaPlayer.java` is `modules/javafx.media/src/main/java/javafx/scene/media/MediaPlayer.java` (US-040 leaves it
unchanged), `NativeAudioSpectrum.java` is in `modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl`, native
paths are relative to `modules/javafx.media/src/main/native/jfxmedia`, and `gstspectrum.c` is in
`modules/javafx.media/src/main/native/gstreamer/gstreamer-lite/gst-plugins-good/gst/spectrum`. Files US-040 changes
are cited as in its working tree (uncommitted, on `29f69dc205`).

- **`MediaPlayer` bounds the count from below only.** `AUDIOSPECTRUM_NUMBANDS_MIN` is 2 (`MediaPlayer.java:282`),
  and both calls that pass the property on clamp it to `[2, Integer.MAX_VALUE]` (`:1823`, `:2173`); the property
  keeps the value set. Its Javadoc says "minimum is 2" (`:2137-2145`) and "must be &ge; 2" (`:2150`), and names no
  maximum. `NativeAudioSpectrum.setBandCount` takes any count above 1 (`NativeAudioSpectrum.java:107`), records the
  new pair as the one it reports (`:115-116`) and hands it to C (`:121`).
- **C has no upper bound.** `jfxm_spectrum_set_bands` refuses only a count of 0 or less or a NULL buffer: it logs a
  warning, runs `release` once, before it returns, and installs nothing (`ffi/jfxmedia_api.cpp:1243-1249`). Any
  other count gets a holder and, on a non-NULL spectrum, `SetBands` (`:1251-1270`). Both comments say no upper bound
  is checked (`:1241-1242`, `jfxmedia_api.h:516-522`).
- **The element accepts 0 to 1024 bands.** `CGstAudioSpectrum::SetBands` sets the element's "bands" property
  (`platform/gstreamer/GstAudioSpectrum.cpp:105`), then installs the pair and its count (`:111-115`). The property
  accepts 0 to `MAX_BANDS`, 1024 (`gstspectrum.c:72`, `:177-181`); for any other value `g_object_set` keeps the old
  one (`jfxmedia_api.h:524-526`; GLib not re-read here).
- **With GStreamer, above 1024 the listener gets nothing.** The pair for the refused count is installed while the
  element keeps computing for its old count. Since US-040 the bus handler drops each message computed for another
  count than the installed pair's, with no write and no event
  (`platform/gstreamer/GstAudioPlaybackPipeline.cpp:1560-1570`). So the `AudioSpectrumListener` gets no event until
  an accepted count is set (`jfxmedia_api.h:526-529`, `modules/javafx.media/FFM-ABI-CONTRACT.md:1013-1027`). Before
  US-040 it was called every interval with the seeded values: magnitudes at `NativeAudioSpectrum.DEFAULT_THRESHOLD`,
  phases 0. Neither is real data. Both halves are read from the code, as US-040 records, not run.
- **A count of 1 divides by zero.** `jfxm_spectrum_set_bands` accepts it, and so does the property. The element's
  transform length `nfft = 2 * bands - 2` is then 0 (`gstspectrum.c:241`, `:907`, `:927`), and the element takes
  remainders by it (`:462` and each sample reader to `:611`, `:796`, `:977`, `:997`, `:1008`). JavaFX never passes 1:
  `NativeAudioSpectrum` acts only above 1, and `MediaPlayer` clamps to 2. Read, not run.
- **The same count on macOS.** The AVFoundation unit runs the same element (US-115 `:19-21`), built into
  `gstreamer-lite` with `GSTREAMER_LITE` there too (`modules/javafx.media/native/mac.cmake:548`, `:592`). US-115
  item 1 (`:31-33`) describes a count above 1024 there, and its criterion 1 (`:117-118`) expects no event for it.

### Requirement
1. **Every accepted count delivers.** No band count that `setAudioSpectrumNumBands` accepts leaves the listener
   without events: a count above the largest the spectrum computes, 1024, behaves as 1024, as a count below 2
   behaves as 2 today. The property's Javadoc states the range.
2. **C installs nothing it cannot compute.** `jfxm_spectrum_set_bands` installs nothing for a count the platform's
   spectrum cannot compute: below 2, or above its maximum of 1024. The pair and count in effect stay in effect, and
   the handed-over memory is released exactly once, before the call returns, as for a count of 0 today. The function
   stays `void`.
3. **The texts say what the code then does:** the comment of `jfxm_spectrum_set_bands` (`jfxmedia_api.h:501-537`),
   its copy in `FFM-ABI-CONTRACT.md` section 11 (`:703-733`), the section 14.1 bullet (`:1013-1027`) and the comment
   at `ffi/jfxmedia_api.cpp:1229-1242`.

### Constraints
- **Counts 2 to 1024 unchanged:** the same pair is installed, the same messages are written, the same events fire.
- **ABI unchanged:** `JFXM_ABI_VERSION` stays `4u` (`jfxmedia_api.h:96`); the shipped `jfxmedia` exports the same
  `jfxm_*` names (58, as US-040 records).
- **A declared departure:** this is a deliberate difference from upstream OpenJFX, recorded in
  `FFM-ABI-CONTRACT.md` section 14.1 with its observable delta.
- **macOS:** the AVFoundation unit (`platform/osx`) is US-115's and does not change here. US-115's criterion 1, "A
  count above 1024 gets no event", expects what the GStreamer path does today (`jfxmedia_api.h:526-529`), not what
  macOS does today (US-115 item 1); whichever of the two stories lands second adjusts it.
- **Who changes what:** only the Java 27 agent changes Java. Media C++ style, LF, no tabs, second copyright year 2026.

### Acceptance criteria
1. **Above 1024 through `MediaPlayer`.** A test in the manner of `SpectrumBandChangeTest` (US-040: a generated tone
   played in a child JVM) sets a count above 1024 through `MediaPlayer.setAudioSpectrumNumBands` during playback. Its
   `AudioSpectrumListener` receives deliveries of 1024 real bands whose strongest band is the tone's. The probe of
   `SpectrumBandChangeTest` calls the internal `AudioSpectrum.setBandCount` (`SpectrumBandChangeProbe.java:242`,
   `:246`), which bypasses the `MediaPlayer` bound.
2. **Refused counts.** A test against US-040's hooks build (`jfxmedia_test`) or through the facade calls
   `jfxm_spectrum_set_bands` on a playing media's spectrum with a count of 1 and with one above 1024. Nothing is
   installed: the pair in effect before the call is still written and its events still fire, and the refused pair is
   never written. Each refused pair's `release` runs exactly once, before the call returns.
3. **Mutant proof.** With each change undone in a scratch copy (the `MediaPlayer` bound, the refusal below 2, the
   refusal above 1024), its test fails. The repository version passes.
4. **Gate.** `mvn -pl modules/javafx.media -am test` passes on Windows and in WSL Linux, per-class counts as before
   plus the new tests; `SpectrumBandChangeTest` passes unchanged. Platforms and counts recorded here. CI is green.

### Out of scope
The AVFoundation unit (US-115); the element's own range in `gstspectrum.c`; overlapping `set_bands` calls on one
spectrum, which the caller must not make (`jfxmedia_api.h:533-536`); the one delivery per count change that can read
unwritten arrays (US-040, Part 2). No public API member is added: the range is stated in the Javadoc.

### Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 to 3 and
  read the texts of requirement 3 against the code.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

### Notes
- The owner approved filing this story on 2026-11-15.

## Part 3 — Balance the equalizer lock and the converted pixel buffers of the macOS AVFoundation player (was US-118)

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15: items 1 to 3 while US-115 was fact-checked,
re-read for this story that day; 4 to 7 while it was checked. Nothing on macOS was compiled or run: every item below is
from reading. · **Related:** US-115 owns the throwing allocations and the exceptions that leave `extern "C"` under
`platform/osx`; this story owns the lock and reference balance and the band state. US-040, from which both were filed.

### Story
As a JavaFX app developer who uses the audio equalizer or video on macOS,
I want the AVFoundation equalizer and frame conversion to leave each lock and pixel buffer as they found it,
so that changing a band cannot hang the app or stall the audio, and a disposed converted frame frees its buffer.

### Problem
Paths are relative to `modules/javafx.media/src/main/native/jfxmedia`. A bare line number is in
`platform/osx/avf/AVFAudioEqualizer.cpp` in items 1, 2 and 4 to 7, and in `platform/osx/CVVideoFrame.mm` in item 3.
US-040 leaves `platform/osx` unchanged; `ffi/jfxmedia_api.cpp`, `jfxmedia_api.h` and the Java files, named alone
(`AudioEqualizer.java` is `javafx.scene.media`'s), are cited as in US-040's working tree (uncommitted, on `29f69dc205`).
`GstAudioEqualizer.cpp` is in `platform/gstreamer`. Each item was read, not compiled or run.

1. **Moving a band locks `mBandLock` twice.** The mutex has default attributes (`pthread_mutex_init`, :267), so
   its type is `PTHREAD_MUTEX_DEFAULT`. `jfxm_eq_band_set_center_frequency` (`ffi/jfxmedia_api.cpp:1177-1181`)
   calls `SetCenterFrequency` (:68-72), the only caller of `MoveBand`; it passes the band's own frequency (:69), the
   key it sits under (:45, :307, :339-340, :70). `MoveBand` locks (:333) and, when the frequency changes, calls
   `RemoveBand(newFrequency)` (:335-338), which locks the same mutex on the same thread (:319). POSIX leaves a
   re-lock of a `PTHREAD_MUTEX_DEFAULT` mutex by its owner undefined and specifies a deadlock for
   `PTHREAD_MUTEX_NORMAL`; how macOS's default mutex behaves on such a re-lock was not checked. If the call returns,
   `lockBands` ignores its result (`AVFAudioEqualizer.h:154-156`): `RemoveBand`'s unlock (:325 or :328) can then
   release the lock while `MoveBand` still edits the map (:339-342) that the audio tap walks under it (:406-413, :450),
   and `MoveBand`'s unlock (:344) can unlock a mutex it no longer holds, also undefined by POSIX.
2. **A throw in a band function leaves `mBandLock` held.** `lockBands` and `unlockBands` are a plain
   `pthread_mutex_lock` and `pthread_mutex_unlock` (`AVFAudioEqualizer.h:154-160`), and nothing else takes or
   releases `mBandLock`. `AddBand` (:304-316) locks at :305 and unlocks at :314. Between them the map
   insert of :306, the plain `new AVFEqualizerBand` (:307), whose constructor can call `SetChannelCount` (:51), and
   `ResetBandParameters` (:312), which calls it on every band (:373), can throw: `SetChannelCount` allocates with a
   plain `new[]` (:86). `RemoveBand` (:318-330) and `MoveBand` (:332-345) unlock by hand too, around a map
   `operator[]` that inserts an absent key (:320, :336, :339) and `ResetBandParameters` (:324, :342). An exception
   leaves with the lock held and nothing releases it; if the process outlives the exception (US-115), each later
   call of the three on another thread, and each `ProcessBufferLists` of the audio tap (:406, from
   `platform/osx/avf/AVFAudioProcessor.mm:229`), waits on it, and one on the same thread re-locks it as in item 1.
3. **`ConvertToFormat` keeps the buffer it creates.** For a `YCbCr_422` frame converted to `BGRA_PRE` (:183),
   `ConvertToFormat` (:181-208), which `jfxm_frame_convert` calls (`ffi/jfxmedia_api.cpp:1103-1110`), creates
   `destPixelBuffer` (`CVPixelBufferCreate`, :185) and locks its base address with flags 0 (:187). On success it
   returns `new CVVideoFrame(destPixelBuffer, ...)` (:201): the constructor locks the buffer again, read-only
   (`PrepareChunky`, :105, :127), and retains it (:110); the frame's `Dispose`, run by its destructor (:113-116),
   unlocks it read-only and releases it, once each (:171-179). Nothing undoes the lock of :187, the only lock in the
   file taken with flags 0 (its only unlock passes `kCVPixelBufferLock_ReadOnly`, :174), or releases the creator's
   reference (Core Foundation's Create Rule, not checked for this story), so a disposed frame leaves its buffer
   locked and referenced. Every failure path keeps it too:
   - the lock of :187 fails: `NULL` (:207), with the creator's reference;
   - the conversion fails (:192): `NULL`, with that reference and the lock of :187;
   - the plain `new` of :201 or the constructor throws (:53, :68, :75, or :129, :138 in `PrepareChunky`): the
     exception leaves with both, and at :138 with the constructor's read-only lock of :127 as well; a constructor
     that throws runs no destructor, and `Dispose` acts only once :109 has run (:173).
4. **Band parameters change outside `mBandLock`.** `jfxm_eq_band_set_*` (`ffi/jfxmedia_api.cpp:1177-1205`) run
   `SetCenterFrequency` (:68-72), `SetBandwidth` and `SetGain` (`AVFAudioEqualizer.h:74-86`) on the Java caller's
   thread (`EqualizerBand.java:118-124`, `:169-175`, `:221-226`, `JfxMediaNative.java:2298-2360`); they recalculate
   unlocked (:70-71 after `MoveBand`'s unlock), writing `mBypass` (:195, :205) and five coefficients one by one, then
   dividing them in place (:145-189), while `ApplyFilter` reads (:225, :230, :247) and recalculates (:227) them under
   the lock. The tap can thus filter with a set partly old, partly new or undivided; to what effect was not checked.
   `SetEnabled` (:296-298) writes `mEnabled` unlocked, on the Java caller's thread too, while `RunFilter` reads it under
   the lock (:422), and `GetNumBands` (:300-302) reads the map's size unlocked while the band functions change it.
5. **The tap's prepare callback resets the bands outside `mBandLock`.** `PrepareAudioTap`, MediaToolbox's callback
   (`AVFAudioProcessor.mm:97`), sets the rate and channel count and runs `ResetBandParameters` unlocked (`:193-195`
   there): it erases map entries (:353), replaces histories (:373, :79-87), recalculates (:372, :374) and clears the
   filter buffers (:379-385), all of which the band functions change, and the process callback reads, under the lock
   (:312, :324, :342; :406-462). Nothing keeps it apart from a Java thread in those functions: both can then walk and
   erase one map and `delete[]` one history (:75-82; reasoned). Neither callback's thread, nor any overlap, was checked.
6. **Removing a frequency that holds no band leaves a NULL entry.** `RemoveBand`'s `operator[]` (:320) inserts the key,
   and `false` returns with it in place (:328-329); `GetNumBands` (:300-302), which Java's `addBand` checks
   (`NativeAudioEqualizer.java:69-72`), counts it until a `ResetBandParameters` erases it (:351-353). Java removes any
   frequency above 0 (`:80-83` there); GStreamer's `RemoveBand` inserts nothing (`GstAudioEqualizer.cpp:165`).
7. **Moving a band onto another band's frequency deletes that band.** If item 1's re-lock returns, `RemoveBand`
   (:338) deletes the band there (:322-323) and the moved band takes its key (:339-340); `GetNumBands` drops by one.
   Java keeps the deleted band's handle: nothing keeps two bands off one frequency (`EqualizerBand.java:118-124`,
   `AudioEqualizer.java:221`) or clears a handle before the player's equalizer goes (`AudioEqualizer.java:127-133`),
   and `jfxm_eq_band_*` use it unchecked (`ffi/jfxmedia_api.cpp:1171-1205`): its next set writes to a deleted object.
   On GStreamer the same calls delete nothing; a move only sets the band's `freq` (`GstAudioEqualizer.cpp:62-65`).

### Requirement
1. **No re-lock.** Moving a band's centre frequency never locks a mutex its own thread holds and never unlocks one
   it does not hold.
2. **Every exit releases the lock.** No path out of `AddBand`, `RemoveBand` or `MoveBand`, a thrown exception
   included, leaves `mBandLock` held.
3. **Balanced buffers.** Every pixel buffer `ConvertToFormat` creates is unlocked and released exactly as often as
   it is locked and retained, each unlock with the flags of the lock it undoes: on success once the frame is
   disposed, and on every failure, a thrown exception included.
4. **Band state changes under the tap's lock.** The band state `ProcessBufferLists` reads (the map, each band's
   state, the filter buffers, the rate and channel count, and the enabled flag) changes only under `mBandLock`, on every
   thread, and `GetNumBands` reads the map under it (items 4, 5).
5. **The map holds only bands.** No call leaves a map entry without a band: removing a frequency that holds none
   returns `false` and leaves `jfxm_eq_get_num_bands` as it was, as on GStreamer.
6. **A move deletes no band.** A move onto another band's frequency does what the same calls do on GStreamer
   (item 7): no band is deleted, the band count stays, and the other band's handle stays valid with its own values.

### Constraints
- **Output unchanged:** the same samples through the same bands, and converted frames of the same size and bytes.
- **The audio tap waits on nothing new:** no lock and no call that `ProcessAudioTap` does not make today.
- **ABI unchanged.** `JFXM_ABI_VERSION` stays `4u` (`jfxmedia_api.h:96`); no export is added, removed or renamed.
- **Only `platform/osx` and tests change**, in each file's media style; LF, no tabs, second copyright year 2026.

### Acceptance criteria
On the macOS host (the Mac mini); CI's macOS runners have no audio output (US-115's notes). So that a hang or a
crash fails a test and not the run, each observation runs in a child JVM, as in `MacFontReleaseTest`
(`modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontReleaseTest.java:42-46`).
1. **Moving a band returns.** A test moves the centre frequency of each band of an AVFoundation player's equalizer,
   before playback and while it plays a small, documented, license-clean fixture that AVFoundation decodes (US-115
   needs one too). Every call returns within a bound written in the test, and the band then answers its new
   frequency. Before the change the test fails; the results record how.
2. **A throw releases the lock.** With an allocation failure provoked in turn at each throwing call of Problem 2, a
   test asserts that afterwards an `AddBand` from another thread returns and spectrum events of a playing player
   keep arriving (the tap runs the spectrum after the equalizer: `AVFAudioProcessor.mm:228-240`), within a bound
   written in the test. How the failure is provoked within the constraints is recorded here; US-040's hooks build
   refuses macOS (`modules/javafx.media/native/CMakeLists.txt:75-77`). Before the change the test fails; the
   results record how, an exception that ends the child JVM included (US-115). An independent review lists every
   exit of the three functions with the state of the lock there.
3. **Converted buffers are freed.** A test converts frames an AVFoundation player delivers as `YCbCr_422` to
   `BGRA_PRE` (`jfxm_frame_convert`) in rounds and disposes each result; native memory does not grow with the
   conversions, measured as `MacFontReleaseTest` does (a retain count that rises with the calls, or heap blocks per
   call from `malloc_zone_statistics`: `src/shims/java/com/sun/javafx/font/MacFontProbeShim.java:148` of
   javafx.graphics), against a bound written in the test, with the figures before and after recorded here. Before
   the change the test fails. Which frames AVFoundation delivers as `YCbCr_422` was not checked; the results say
   how the test obtains them. Each failure path of Problem 3 that can be reached is provoked once and measured the
   same way, the results naming those reached (`CVVideoFrame.mm:53` is not if `k32BGRAPixelFormat`, :186, is
   `'BGRA'`, accepted at :33; not checked); an independent review counts the buffer's locks, unlocks, retains and
   releases on every path, reached or not, and finds them equal.
4. **Band state is written under the lock.** An independent review lists every reader and writer of the state of
   requirement 4, with its thread as far as the code shows it and whether `mBandLock` is held there, and finds
   requirement 4 met. No test provokes these races, which were never reproduced, so this criterion has no mutant.
5. **An absent frequency.** A test removes a frequency that holds no band from an AVFoundation player's equalizer
   (`OSXMediaPlayer.java:138`) and asserts `false` and an unchanged band count; before the change it rises by one.
6. **A move onto an occupied frequency.** A test moves a band of that equalizer onto another band's frequency, then
   sets and reads back the other band's bandwidth and gain through its handle and asserts an unchanged band count.
   Before the change the test fails; the results record how.
7. **Mutant proof.** Criteria 1 to 3, 5 and 6 each fail with their change undone in a scratch copy (the re-lock;
   the release on one exit of each band function; the unlock and release on each reached path of `ConvertToFormat`; the
   NULL entry; the deleted band); the repository passes.
8. **Gate.** `mvn -pl modules/javafx.media -am test` passes on the macOS host before and after, per-class counts as
   before plus the new tests, which run against the shipped media libraries; no run leaves an `hs_err_pid*` file.
   CI's two macOS jobs are green.

### Out of scope
Whether the allocations under `mBandLock` and in `ConvertToFormat` can throw, and the exceptions that leave
`extern "C"` under `platform/osx`: US-115 owns both; this story owns the lock and reference balance on every path
out. The spectrum unit's own `mBandLock` (US-115), the GStreamer equalizer and video frames. The lifetime of the
handle Java holds for a removed band, and two Java bands on one frequency, are US-120's.

### Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 to 3, 5, 6.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

### Notes
- The owner approved filing this story on 2026-11-15.

## Part 4 — Give each equalizer band its own native band, and none once it is removed (was US-120)

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, while US-118 was fact-checked; read for
this story that day, nothing compiled or run · **Related:** US-118 owns the macOS native equalizer's lock, map and
move; this story owns the lifetime of the handle Java holds. US-040, from which both were filed.

### Story
As a JavaFX app developer who adds, moves and removes the bands of an `AudioEqualizer`,
I want each `EqualizerBand` to act on a native band of its own, and on none once I have removed it,
so that changing a band can neither reach a native band that is gone nor remove another band's.

### Problem
Java paths are under `modules/javafx.media/src/main/java` (`AudioEqualizer` and `EqualizerBand` in `javafx.scene.media`,
the others in `com.sun.media.jfxmediaimpl`), native ones under `modules/javafx.media/src/main/native/jfxmedia`, with
`Gst*` in `platform/gstreamer` and `AVF*` in `platform/osx/avf`. Of these US-040 changes only `ffi/jfxmedia_api.cpp`
and `jfxmedia_api.h`, cited as in its working tree (on `29f69dc205`). Each item was read, not compiled or run.
1. **The handle is a raw pointer that nothing checks.** Java keeps the `CEqualizerBand*` that `jfxm_eq_add_band`
   returns as a `long` (`ffi/jfxmedia_api.cpp:1151-1159`, `JfxMediaNative.java:2254-2261`,
   `NativeEqualizerBand.java:32`) and passes it back to each set (`JfxMediaNative.java:745-747`, `:2298-2360`);
   `jfxm_eq_band_*` make a virtual call through it with no check (`ffi/jfxmedia_api.cpp:1171-1205`,
   `PipelineManagement/AudioEqualizer.h:36-41`). On GStreamer it points at a value of the equalizer's
   `std::map<double, CGstEqualizerBand>` (`GstAudioEqualizer.h:76`, `GstAudioEqualizer.cpp:155-157`), on
   AVFoundation at a band made with `new` (`AVFAudioEqualizer.cpp:307`).
2. **A removed band keeps its handle.** `AudioEqualizer.Bands.onProposedChange` removes the native band of each band
   leaving the list by its centre frequency, ignores the result and clears no handle (`AudioEqualizer.java:211-218`);
   only the player's equalizer going clears handles (`:127-133`). The setters call through any handle that is not
   null (`EqualizerBand.java:118-124`, `:169-175`, `:221-226`). `RemoveBand` erases the map element (GStreamer,
   `GstAudioEqualizer.cpp:163-175`) or deletes the band (AVFoundation, `AVFAudioEqualizer.cpp:318-330`), so a set
   after `getBands().remove(band)` calls a member of a destroyed object: undefined behaviour, effect not checked.
3. **A moved band is removed under a key it does not have (GStreamer).** `SetCenterFrequency` sets only the band's
   `freq` (`GstAudioEqualizer.cpp:62-65`), and the map key stays the frequency it was added with, while Java removes
   by the current one (`AudioEqualizer.java:216`). `RemoveBand`'s `find` (`GstAudioEqualizer.cpp:165`) then misses
   the band. With no band at the new frequency it returns false, which Java ignores: the native band stays in the map
   and the element, and the removed band's sets still reach it. With a band there, that band is erased instead, and
   its `EqualizerBand`, still in the list, holds a handle to an erased element. AVFoundation re-keys a moved band
   (`AVFAudioEqualizer.cpp:332-345`), so there the removal finds it, if the re-lock of US-118 item 1 returns.
4. **Two bands on one frequency.** Nothing in Java keeps two bands of the list off one frequency
   (`AudioEqualizer.java:220-226`). On GStreamer `AddBand` returns NULL for a frequency in use
   (`GstAudioEqualizer.cpp:149-161`), Java's `addBand` returns null for it (`NativeAudioEqualizer.java:76-77`) and
   `setJfxBand(null)` stores the null (`EqualizerBand.java:84-88`): nothing throws or logs, though the comment at
   `AudioEqualizer.java:147-148` says it would throw an NPE. The second band's sets reach nothing, and its removal
   erases the first band's native band, whose `EqualizerBand` stays in the list with a handle to it. On AVFoundation
   `AddBand` sets the existing band's bandwidth and gain to the new values and returns that band
   (`AVFAudioEqualizer.cpp:308-315`): both `EqualizerBand`s hold one handle, the first one's native values are the
   second one's, and removing either deletes the band the other holds.
5. **Not a regression of the FFM port.** `AudioEqualizer.java` and `EqualizerBand.java` are upstream's (`git diff`
   from `868c4801ec` to `29f69dc205` is empty on them). At `939aa61ea` the equalizers were today's but for a copyright
   year and an include, and the JNI code reached the same object the same way: a `jlong` cast back and called through
   unchecked (`jni/NativeAudioEqualizer.cpp:57-91`, `jni/NativeEqualizerBand.cpp:35-77` there).

### Requirement
1. **A removed band acts on nothing.** Once an `EqualizerBand` has left its `AudioEqualizer`'s list, no set of its
   gain, bandwidth or centre frequency reaches a native band, on either backend (items 2, 3).
2. **No call reaches a band that is gone.** No call on an `EqualizerBand` in the list reaches a native band that no
   longer exists, on either backend (items 3, 4).
3. **A native band of its own, or none.** Each `EqualizerBand` in the list has a native band of its own or none,
   never one it shares with another `EqualizerBand`, and adding a band changes no other band's native band (item 4).
4. **Removal removes its own band only.** Removing an `EqualizerBand` removes its own native band, if it has one, and
   no other, also when its centre frequency changed after it was added (items 3, 4).

### Constraints
- **Public API unchanged:** no new public member, none removed; the Javadoc may state the behaviour.
- **Bands that stay behave as today:** a band in the list whose centre frequency no other band of the list shares
  gets the same native values, the same calls and the same sound.
- **ABI:** `JFXM_ABI_VERSION` stays `4u` (`jfxmedia_api.h:96`) and the 58 shipped `jfxm_*` exports stay, unless the
  requirement cannot be met without changing them. Then the version follows `FFM-ABI-CONTRACT.md` section 5.2
  (`:267-300`: an added symbol bumps it, as does a change a mismatched pair would not report; any other change is
  written in section 14.1), and the texts on the handle (`jfxmedia_api.h:475-485`, sections 3 and 11) follow the code.
- **A declared departure:** each observable difference from upstream OpenJFX is recorded in `FFM-ABI-CONTRACT.md`
  section 14.1 with its observable delta.
- **Seams:** new watches of the hooks build are seams in `ffi/JfxmTestHooks.h`, empty in the shipped build.
- **Who changes what:** only the Java 27 agent changes Java. Media C++ style, LF, no tabs, second copyright year 2026.

### Acceptance criteria
Each test makes its player in a child JVM (`ChildJvm`) as `SpectrumBandChangeTest` does, so a crash fails the test and
not the run, and like it is skipped by `AudioOutput.reportNoPlayer` without an audio output. GStreamer is verified on
Windows and in WSL Linux, AVFoundation on the macOS host, which US-040's hooks build (`jfxmedia_test`) refuses
(`modules/javafx.media/native/CMakeLists.txt:74-77`).
1. **A removed band.** A test removes a band from `getAudioEqualizer().getBands()`, then sets its gain, bandwidth and
   centre frequency. The child ends normally, the bands left read back their own values natively, and no set reached
   a native band: on GStreamer the hooks build counts `jfxm_eq_band_*` calls on a band that no longer exists and the
   test requires none; on macOS the results record how such a call is made observable. Before the change it fails.
   Windows, WSL Linux and the macOS host.
2. **A moved band (GStreamer).** A test changes a band's centre frequency, then removes the band: the native band
   count (`jfxm_eq_get_num_bands`) drops by one; with another band at the new frequency, that band keeps its native
   band and values, and no call reaches a band that is gone. Before the change it fails. Windows and WSL Linux.
3. **Two bands on one frequency.** A test adds a second band on a listed band's frequency with another bandwidth and
   gain, sets each one's gain and removes the second: the first reads back its own values natively throughout, and
   the native band count ends where it was before the second was added. A second test removes the first band, then
   sets the second's gain, bandwidth and frequency. In both, no call reaches a band that is gone. Before the change
   the first fails on both backends, the second on AVFoundation. GStreamer on Windows and WSL Linux; AVFoundation
   on the macOS host only.
4. **Mutant proof.** With each part of the change undone in a scratch copy, one at a time, a test of the requirement
   it serves fails where that requirement's problem occurs; the repository version passes. The results list the parts.
5. **Gate.** `mvn -pl modules/javafx.media -am test` passes on Windows and in WSL Linux, per-class counts as before
   plus the new tests, `SpectrumBandChangeTest` and `MediaPlaybackTest` unchanged, and the AVFoundation halves on the
   macOS host, with no `hs_err_pid*` under `modules/javafx.media/target`; platforms and counts recorded. CI is green.

### Out of scope
The macOS native equalizer's lock, map and move, a move onto another band's frequency included: US-118 (items 1, 2
and 4 to 7). The equalizer's audio behaviour: its filters and what the bands do to the sound.

### Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 to 4.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

### Notes
- The owner approved filing this story on 2026-11-15.
