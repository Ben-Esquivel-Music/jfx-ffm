# US-105 — Play MIDI files and KAR karaoke

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey) · **Epic:** Media formats ·
**Blocked by:** US-100 (playback of media decoded in Java) · **Blocks:** none

## Story
As a JavaFX application developer, including the developer of a karaoke application,
I want `.mid`, `.midi` and `.kar` files to play through `javafx.media`, with a KAR file's lyrics reaching my
application on time,
so that MIDI music and KAR karaoke play the same on every desktop OS without a synthesizer or lyric parser of my own.

## Problem
`impl/` is `modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/` and `scene/` is
`modules/javafx.media/src/main/java/javafx/scene/media/`. `jdk/` is `java.desktop/com/sun/media/sound/` in the
`lib/src.zip` of OpenJDK 27.0.1. `jkaraoke/` is the owner's jKaraoke (CC0) at commit `18f6f58cbb`.
- **Not recognised.** The extension map has no `.mid`, `.midi` or `.kar` (`impl/MediaUtils.java:190-227`). `MThd`
  matches no signature, so the check throws "Unrecognized file signature!" (`impl/MediaUtils.java:179`), which `Media`
  and `AudioClip` report as `MEDIA_UNSUPPORTED` (`scene/Media.java:409-410`, `scene/AudioClip.java:90-91`). The Java
  platform plays nothing (`impl/platform/java/JavaPlatform.java:68-76`).
- **The JDK has MIDI.** `java.desktop`, which holds `javax.sound.midi` and a software synthesizer, is always in the
  module graph: javafx.base and javafx.graphics require it (`modules/javafx.base/src/main/java/module-info.java:34`,
  `modules/javafx.graphics/src/main/java/module-info.java:43`), though javafx.media does not
  (`modules/javafx.media/src/main/java/module-info.java:35-36`). It is part of the platform, not a library the fork
  ships.
- **The sound depends on the host.** The JDK synthesizer's default soundbank is the largest `.sf2` or `.dls` in the
  JDK's `lib/audio`, else a `default.sf2` from the Linux soundfont directories, else Windows' `gm.dls`, else
  `~/.gervill/soundbank-emg.sf2`, else a soundbank it generates and saves there (`jdk/SoftSynthesizer.java:640-810`).
  The same file therefore sounds different from host to host, and a first use can write into the user's home
  directory.
- **The JDK synthesizer is not bit-reproducible across targets.** Its filters, oscillators, resamplers and effects
  compute with `Math.pow`, `Math.sin`, `Math.cos` and `Math.exp` (JDK 27 `java.desktop`,
  `com/sun/media/sound/SoftFilter.java:306-310`, `SoftLowFrequencyOscillator.java:95-114`, and the resamplers,
  `SoftVoice`, `SoftChannel`, `SoftReverb` and `SoftChorus` likewise), which are intrinsics that may differ in the
  last unit between x64 and aarch64 and between OS builds. The fork controls none of that code, so a rendering made
  with it cannot promise the identical PCM criterion 4 requires.
- **KAR.** A KAR (Soft Karaoke) file is a Standard MIDI File whose lyrics sit in text meta events. A leading `\`
  clears the screen and a leading `/` starts a new line, and `@` events carry the header: `@K` file type, `@V`
  version, `@I` information, `@L` language, `@T` title and artist
  (https://www.mixagesoftware.com/en/midikit/help/HTML/karaoke_formats.html). Other karaoke MIDI files use the Lyric
  meta event of Standard MIDI Files (same source). Text events declare no encoding (knowledge); the MIDI
  Association's language extension defines only Latin-1 and Shift-JIS and is little used
  (https://midi.org/community/midi-specifications/how-many-midi-karaoke-formats-exist).
- **No timed-text API.** The only timed events that carry text in the public API are markers, a map from unique names
  to times (`scene/Media.java:332`), checked by a 40 ms poll (`impl/NativeMediaPlayer.java:1584-1591`, `:1646`).
  Repeated lyrics, such as the same syllable twice, cannot both be markers (inferred).
- **The owner's karaoke app.** jKaraoke's only karaoke format is CD+G paired with MP3
  (`jkaraoke/docs/features.html:146-152`); nothing in it handles MIDI or KAR (grep).
- **WebView.** WebView hands WebKit `MediaManager.getSupportedContentTypes()`
  (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/prism/PrismGraphicsManager.java:155`), and WebKit answers
  `canPlayType` from the container type alone
  (`modules/javafx.web/src/main/native/Source/WebCore/platform/graphics/java/MediaPlayerPrivateJava.cpp:225-228`).

## Constraints
- **Owner rulings** (`backlog/README.md`, "Media formats"). Third-party Java code inside the jars is
  permissively licensed (MIT, BSD, zlib, CC0, BSL-1.0 or similar); otherwise the code is written in-house from the
  specification. A soundbank the fork ships comes under a licence of that list too. Native code keeps port rule P8
  (`backlog/README.md`, "Port rules", `.claude/skills/jfx-rust-port/SKILL.md:186-193`). Every notice ships through
  US-075.
- **One audio output.** MIDI audio leaves through the one audio output that Java-decoded media shares with US-092; it
  opens no audio device of its own.
- **No side effects.** Playing MIDI writes no file.
- **Behaviour neutrality.** Every type that plays today keeps its back end and its output
  (`.claude/skills/jni-to-ffm-migration/SKILL.md:91`).
- **New API** goes in javafx.media only (`README.md:20-21`), and this story lists it when it lands.
- **Sequencing.** A change to gstreamer-lite or `GstPipelineFactory.cpp` lands after US-040. US-036's slice-0
  "unsupported content" scenario (`backlog/US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md:99`) uses
  content that stays unsupported after this story, so not MIDI.
- **Bounds first.** Every tolerance against a reference rendering is recorded in this story before the first
  comparison runs, never chosen afterwards; none applies between CI targets.
- **Java.** Java 27 final features only (no preview, no incubator such as the Vector API, no `var`). Production Java
  files are at most 900 lines (`wc -l`); tests and shims are exempt. Comments only for a non-obvious "why". GPLv2+CPE
  headers; ported permissive code keeps its notice in the second header block
  (`.claude/skills/openjfx-conventions/SKILL.md:86-89`).
- **Tests.** Test media is generated by a command recorded next to it, never downloaded (`backlog/README.md`, "Port
  rules"). Reference outputs are captured as the house does (`.claude/skills/jfx-ffm-testing/SKILL.md:268-285`). Agents
  never
  run `USE_ROBOT` or `tests/system`; listening checks, real-device and latency measurements are the owner's.

## Out of scope
- Standard MIDI File format 2 (independent patterns with no shared timeline).
- MIDI input and recording, and output to MIDI hardware or to the operating system's synthesizers.
- Playing MIDI when the media natives fail to load (US-100 leaves that out of scope).

## Acceptance criteria
1. **Plays everywhere.** Standard MIDI Files of format 0 and 1 named `.mid`, `.midi` or `.kar` (any case), or
   recognised by their bytes, play through `Media`, `MediaPlayer` and `AudioClip` on Windows, macOS and Linux.
   Playback keeps the `MediaPlayer` contract of the formats that play today: status transitions and events, seek,
   rate, cycle count, start and stop time, markers, volume, balance, mute, equalizer and audio spectrum.
2. **A stated soundbank.** The soundbank is named in the public format documentation, covers every General MIDI
   Level 1 program and the percussion key map, and is the same on every host: a test shows the output does not change
   when other soundbanks sit where the JDK looks for one.
3. **Exact timing.** Every note sounds at the media time its ticks give under the file's division and tempo changes,
   exact to the output sample, and every text event carries that media time. `Media.getDuration()` is the time of the
   last event.
4. **Same output everywhere.** For a corpus covering tempo and time-signature changes, SMPTE division, running status,
   system-exclusive messages, all 16 channels with percussion on channel 10, program changes, controllers and pitch
   bend, the rendered PCM is identical on the five CI targets, shown as US-100 criterion 5 shows it: an exact hash
   match against one committed golden on every leg. The rendering's sample rate, channel count and sample format are
   fixed, stated beside the soundbank in the public format documentation, and independent of the host's output
   device. No tolerance applies between targets. A tolerance may apply only to a comparison against a rendering by a
   synthesizer other than this story's, named in this story with its bound recorded before that comparison runs,
   and such a comparison never stands in for the cross-target identity check.
5. **Seek chases state.** After a seek, each channel's program, controllers and pitch bend equal those of an
   uninterrupted play to that point.
6. **Lyrics reach the application.** KAR text events and Standard MIDI File Lyric events reach the application through
   public API this story lists, each with its media time and its kind: syllable, new line (`/`), new screen (`\`) or
   header (`@`). Once the media is ready the application can read every such event with its time, so a karaoke screen
   can show lines ahead. During playback each event is reported once, in order, on the FX thread, at most 40 ms after
   its media time; a seek reports none of the events it jumps over.
7. **Text and metadata.** Event text that is valid UTF-8 is read as UTF-8; other text is read in an encoding the
   application names, or as windows-1252 when it names none. The KAR header fields and the title, copyright and track
   name events reach `Media.getMetadata()` under keys documented in the public metadata table.
8. **Damaged input.** A truncated or corrupt file fails with US-096's error naming the cause, or plays its events up
   to the damage and ends; it never hangs or crashes. A seeded mutation run over the corpus, with its seed and count
   recorded, meets the same rule.
9. **Documented and visible.** The public container and encoding tables list MIDI and KAR. WebView's `canPlayType`
   answers for the content types this story adds change from empty to "maybe"; the change is checked and recorded.
10. **Nothing else changes.** The media module's tests pass with the counts of a run before the change, plus the new
    tests. Each public type and member added to javafx.media is listed here with its `@since`, and its javadoc
    passes doclint. All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `media-format-reviewer` signed off, and by `ffm-reviewer` if native or FFM code changed.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Owner ruling, 2026-11-07: MIDI and KAR are in scope now.
- Decision needed before this story starts (raised 2026-11-07 by `media-format-reviewer`): criterion 4 requires
  identical PCM on the five targets, and the JDK synthesizer cannot promise it (Problem). Either the rendering uses
  a synthesizer whose arithmetic is the same on every target, which the fork then ships or writes under the licence
  rule, or the owner grants US-105 an exception to US-100 criterion 5 with per-target goldens and a cross-target
  bound recorded before comparison. The story keeps the identity requirement until the owner rules.
- Related: US-102 (CD+G karaoke, the other karaoke format); US-106 (timed text from subtitle files); US-100 lands with
  its first format, which may be this one.
