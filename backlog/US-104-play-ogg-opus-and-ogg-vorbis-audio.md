# US-104 — Play Ogg Opus and Ogg Vorbis audio

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey) · **Epic:** Media formats ·
**Blocked by:** US-100 (playback of media decoded in Java) · **Blocks:** none

## Story
As a JavaFX application developer,
I want Ogg Opus and Ogg Vorbis files to play through `Media`, `MediaPlayer` and `AudioClip` on every desktop OS,
so that an application plays the open audio formats games and web content use without a decoder of its own.

## Problem
`impl/` is `modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/`, `scene/` is
`modules/javafx.media/src/main/java/javafx/scene/media/` and `lite/` is
`modules/javafx.media/src/main/native/gstreamer/gstreamer-lite/`.
- **Not recognised.** The extension map has no `.ogg`, `.oga` or `.opus` (`impl/MediaUtils.java:190-227`). The `OggS`
  capture pattern matches no signature, so the check throws "Unrecognized file signature!"
  (`impl/MediaUtils.java:179`), which `Media` and `AudioClip` report as `MEDIA_UNSUPPORTED`
  (`scene/Media.java:409-410`, `scene/AudioClip.java:90-91`). The packet that names the codec starts after a 27-byte
  page header and its segment table (RFC 3533 §6, https://www.rfc-editor.org/rfc/rfc3533), past the 22 bytes the
  check reads (`impl/MediaUtils.java:46`) (inferred).
- **No decoder.** gstreamer-lite registers no Ogg, Opus or Vorbis element
  (`lite/projects/plugins/gstplugins-lite.c:34-67`); besides the audio sinks, its plugin sources are app,
  audioconvert, typefind and volume (`lite/gst-plugins-base/gst/`), audiofx, audioparsers, equalizer, isomp4,
  spectrum and wavparse (`lite/gst-plugins-good/gst/`) and aiff (`lite/gst-plugins-bad/gst/`). The Java platform
  plays nothing (`impl/platform/java/JavaPlatform.java:68-76`).
- **Ogg.** Every page carries a CRC, and the capture pattern lets a reader resynchronise after damage (RFC 3533 §6).
  Logical streams are chained one after another or grouped side by side (§4).
- **Opus is not bit-exact.** A conformant decoder ends every packet in the reference decoder's final range coder state
  and keeps its output within the `opus_compare` thresholds (RFC 6716 §6 and §6.1,
  https://www.rfc-editor.org/rfc/rfc6716). In Ogg, granule positions count 48 kHz samples (RFC 7845 §4,
  https://www.rfc-editor.org/rfc/rfc7845); the header's pre-skip is decoded and discarded (§4.2); the last page's
  granule position trims the end (§4.4); a seek decodes and discards at least 80 ms first (§4.6); players SHOULD apply
  the header's output gain (§5.1); tags use the Vorbis comment layout (§5.2); mapping family 0 carries 1 or 2
  channels and family 1 carries 1 to 8 in Vorbis order, and family 255 has no defined channel meaning (§5.1.1).
- **Vorbis is not bit-exact either.** The Vorbis I specification asks for a decoder "entirely mathematically
  equivalent" to it, not a literal implementation (§1.3.2, https://xiph.org/vorbis/doc/Vorbis_I_spec.html), and
  allows fixed-point arithmetic of a stated minimum range (§4.3.6), so correct decoders may differ in rounding
  (inferred).
- **Licences.** No maintained pure-Java Vorbis decoder under a standard permissive licence is known (knowledge): JOrbis
  is LGPL-2.1 and its README was last modified in 2001 (https://github.com/ymnk/jorbis), and J-Ogg carries a custom
  licence that requires a credit notice (https://raw.githubusercontent.com/stephengold/j-ogg-all/master/LICENSE).
- **Patents.** Opus has royalty-free IETF grants (https://datatracker.ietf.org/ipr/1524/); since 2023 a pool claims
  royalties on hardware (https://ipwatchdog.com/press/vectis-ip-launches-patent-pool-opus-codec/). No pool is known for
  Vorbis (knowledge).
- **WebView.** WebView hands WebKit `MediaManager.getSupportedContentTypes()`
  (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/prism/PrismGraphicsManager.java:155`), and WebKit answers
  `canPlayType` from the container type alone
  (`modules/javafx.web/src/main/native/Source/WebCore/platform/graphics/java/MediaPlayerPrivateJava.cpp:225-228`).

## Constraints
- **Owner rulings** (`backlog/README.md`, "Media formats"). Third-party Java code inside the jars is
  permissively licensed (MIT, BSD, zlib, CC0, BSL-1.0 or similar); otherwise the code is written in-house from the
  specification. Native code keeps port rule P8 (`backlog/README.md`, "Port rules",
  `.claude/skills/jfx-rust-port/SKILL.md:186-193`). Every notice ships through US-075. Audio decoded in Java uses the
  one audio output it shares with US-092.
- **Behaviour neutrality.** Every type that plays today keeps its back end and its output
  (`.claude/skills/jni-to-ffm-migration/SKILL.md:91`).
- **Sequencing.** A change to gstreamer-lite or `GstPipelineFactory.cpp` lands after US-040. US-036's slice-0
  "unsupported content" scenario (`backlog/US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md:99`) uses
  content that stays unsupported after this story, so not Ogg.
- **Bounds first.** Every tolerance against a reference decoder is recorded in this story before the first
  comparison runs, never chosen afterwards.
- **Java.** Java 27 final features only (no preview, no incubator such as the Vector API, no `var`). Production Java
  files are at most 900 lines (`wc -l`); tests and shims are exempt. Comments only for a non-obvious "why". GPLv2+CPE
  headers; ported permissive code keeps its notice in the second header block
  (`.claude/skills/openjfx-conventions/SKILL.md:86-89`).
- **Tests.** Test media is generated by a command recorded next to it, never downloaded (`backlog/README.md`, "Port
  rules"). Reference outputs are captured as the house does (`.claude/skills/jfx-ffm-testing/SKILL.md:268-285`). Agents
  never
  run `USE_ROBOT` or `tests/system`; listening checks, real-device and latency measurements are the owner's.

## Out of scope
- Ogg video (Theora), Speex, and Ogg files that group several logical streams; these fail with US-096's error.
- FLAC in Ogg (US-103 plays it once this story's Ogg reading exists), and Opus or Vorbis in WebM and Matroska (US-107).
- Writing Ogg, Opus or Vorbis.
- Playing these formats when the media natives fail to load (US-100 leaves that out of scope).

## Acceptance criteria
1. **Plays everywhere.** `.opus`, `.ogg` and `.oga` files carrying Opus or Vorbis play through `Media`, `MediaPlayer`
   and `AudioClip` on Windows, macOS and Linux, recognised by name (any case), by an HTTP `audio/ogg` header and by
   their bytes. Playback keeps the `MediaPlayer` contract of the formats that play today: status transitions and
   events, seek, rate, cycle count, start and stop time, markers, volume, balance, mute, equalizer and audio spectrum.
2. **Opus output.** Over the corpus, every packet ends in the reference decoder's (libopus) final range coder state,
   `opus_compare` passes, and the output stays within the bound against libopus recorded here.
3. **Vorbis output.** Over the corpus, the output stays within the bound against the reference decoder (libvorbis)
   recorded here.
4. **Same everywhere.** Audio decoded in Java is identical on the five CI targets.
5. **Ogg Opus rules.** Pre-skip and end trimming are applied, so the decoded length equals the length the granule
   positions give, and the output gain is applied. Mapping families 0 and 1 play; family 255 and the reserved
   families fail with US-096's error naming the family.
6. **Channels.** How 1 to 8 channels reach the output, including the coefficients of each downmix to fewer channels,
   is documented in the public format table and checked by tests.
7. **Sample-accurate seek.** After `seek(t)` the first sample played is the one at media time t by the granule
   positions, and the samples that follow equal those of an uninterrupted decode within the bounds of criteria 2
   and 3. The same holds where playback restarts at a start time or a cycle repeat. `Media.getDuration()` is exact to
   the sample.
8. **Metadata.** Vorbis comments, from Vorbis and from Opus tags, reach `Media.getMetadata()` under the keys the public
   metadata table documents, with the same mapping FLAC uses (US-103); the mapping is documented in that table.
9. **Chains and damage.** A chained file plays every link in order, including links that change codec, channel count
   or rate; each link's tags reach the metadata when it starts, and the duration is the sum of the links. A page whose
   CRC fails is dropped, playback resumes at the next good page and media time follows the granule positions. A file
   with no playable stream fails with US-096's error naming the cause. A seeded mutation run over the corpus, with its
   seed and count recorded, never hangs or crashes.
10. **Documented and visible.** The public container and encoding tables list Ogg, Opus and Vorbis. WebView's
    `canPlayType` answers for the content types this story adds change from empty to "maybe"; the change is checked
    and recorded.
11. **Nothing else changes.** The media module's tests pass with the counts of a run before the change, plus the new
    tests. No public API is added. All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `media-format-reviewer` signed off, and by `ffm-reviewer` if native or FFM code changed.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Owner ruling, 2026-11-07: Ogg Opus and Ogg Vorbis are in scope now.
- Related: US-107 (Opus and Vorbis audio inside WebM and Matroska); US-103 (FLAC in Ogg uses this story's Ogg
  reading); US-100 lands with its first format, which may be this one.
