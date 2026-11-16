# US-117 — Keep the audio spectrum band count within what the spectrum can compute

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, US-040's record ("Behaviour changes a
user can see"); the sites re-read for this story the same day, nothing built or run · **Related:** US-040, US-115

## Story
As a JavaFX app developer who shows an audio spectrum,
I want every band count that `MediaPlayer` accepts to bring spectrum events with real data,
so that a large count cannot silence my `AudioSpectrumListener` and no count reaches a spectrum that cannot compute it.

## Problem
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

## Requirement
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

## Constraints
- **Counts 2 to 1024 unchanged:** the same pair is installed, the same messages are written, the same events fire.
- **ABI unchanged:** `JFXM_ABI_VERSION` stays `4u` (`jfxmedia_api.h:96`); the shipped `jfxmedia` exports the same
  `jfxm_*` names (58, as US-040 records).
- **A declared departure:** this is a deliberate difference from upstream OpenJFX, recorded in
  `FFM-ABI-CONTRACT.md` section 14.1 with its observable delta.
- **macOS:** the AVFoundation unit (`platform/osx`) is US-115's and does not change here. US-115's criterion 1, "A
  count above 1024 gets no event", expects what the GStreamer path does today (`jfxmedia_api.h:526-529`), not what
  macOS does today (US-115 item 1); whichever of the two stories lands second adjusts it.
- **Who changes what:** only the Java 27 agent changes Java. Media C++ style, LF, no tabs, second copyright year 2026.

## Acceptance criteria
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

## Out of scope
The AVFoundation unit (US-115); the element's own range in `gstspectrum.c`; overlapping `set_bands` calls on one
spectrum, which the caller must not make (`jfxmedia_api.h:533-536`); the one delivery per count change that can read
unwritten arrays (US-040, Part 2). No public API member is added: the range is stated in the Javadoc.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 to 3 and
  read the texts of requirement 3 against the code.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- The owner approved filing this story on 2026-11-15.
