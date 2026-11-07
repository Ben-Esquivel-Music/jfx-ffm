# US-107 — Play Matroska and WebM video

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey) · **Epic:** Media formats ·
**Blocked by:** US-040 (native pipeline changes wait for its fixes) · **Blocks:** none

## Story
As a JavaFX application developer,
I want WebM and Matroska files with VP8 or VP9 video and Opus or Vorbis audio to play in a `MediaView` on every
desktop OS,
so that an application plays the royalty-free video the web uses without a player of its own.

## Problem
`impl/` is `modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/`, `jfxmedia/` is
`modules/javafx.media/src/main/java/com/sun/media/jfxmedia/`, `scene/` is
`modules/javafx.media/src/main/java/javafx/scene/media/`, `native/` is `modules/javafx.media/src/main/native/` and
`prism/` is `modules/javafx.graphics/src/main/java/com/sun/prism/`.
- **Not recognised.** The extension map has no `.webm` or `.mkv` (`impl/MediaUtils.java:190-227`). The EBML header
  matches no signature, so the check throws "Unrecognized file signature!" (`impl/MediaUtils.java:179`), which `Media`
  reports as `MEDIA_UNSUPPORTED` (`scene/Media.java:409-410`).
- **No demuxer or decoder.** gstreamer-lite registers no Matroska, VP8 or VP9 element
  (`native/gstreamer/gstreamer-lite/projects/plugins/gstplugins-lite.c:34-67`). Video is decoded only by platform
  decoders: dshowwrapper and mfwrapper on Windows (`modules/javafx.media/native/win.cmake:750-764`), avplugin on Linux
  for H.264 and H.265 only (`native/gstreamer/plugins/av/videodecoder.c:207-225`), and AVFoundation on macOS.
- **macOS.** GStreamer claims only AIFF and WAV there
  (`impl/platform/gstreamer/GSTPlatform.java:62-65`, `:110-116`) and refuses MP4
  (`native/jfxmedia/platform/gstreamer/GstPipelineFactory.cpp:559-560`); AVFoundation plays the rest
  (`impl/platform/osx/OSXPlatform.java:46-54`).
- **Frames.** `MediaView` takes `BGRA_PRE`, `YCbCr_420p` and `YCbCr_422` frames and rejects `ARGB`
  (`modules/javafx.media/src/main/java/com/sun/javafx/media/PrismMediaFrameHandler.java:203-217`). Metal, the macOS
  default (`prism/impl/PrismSettings.java:207-208`), builds 4:2:0 frames from their planes
  (`prism/mtl/MTLResourceFactory.java:200-267`) and updates a single texture only from 4:2:2
  (`prism/mtl/MTLTexture.java:228-232`).
- **Track encodings.** The track encoding enum has no VP8, VP9, Opus or Vorbis (`jfxmedia/track/Track.java:36-53`), its
  C++ twin keeps the same ordinals (`native/jfxmedia/PipelineManagement/Track.h:43-59`), and its name reaches
  `Track.getMetadata()` as "encoding" (`scene/Media.java:475`).
- **Formats.** Matroska is RFC 9559 (https://www.rfc-editor.org/rfc/rfc9559), built on EBML (RFC 8794). WebM is a
  subset whose video SHOULD be VP8 or VP9 and audio Vorbis or Opus, with DocType "webm" and `audio/webm` for audio-only
  files (https://www.webmproject.org/docs/container/). VP8 is RFC 6386 and VP9 has a bitstream specification
  (https://www.webmproject.org/vp9/); both define the decoded output exactly (knowledge).
- **Licences.** gstreamer-lite is LGPL-2.1 overall (`modules/javafx.media/src/main/legal/gstreamer.md:31-33`);
  GStreamer's Matroska plugin is LGPL too, and libvpx, the reference VP8/VP9 decoder, is BSD-3-Clause (knowledge). No
  pure-Java VP9 decoder is known; JCodec decodes VP8 key frames only (https://github.com/jcodec/jcodec).
- **Patents.** H.264 patents stay in force until late 2030
  (https://meta.wikimedia.org/wiki/Have_the_patents_for_H.264_MPEG-4_AVC_expired_yet%3F) and AAC has an active pool
  (https://via-la.com/via-licensing-alliance-welcomes-honor-as-advanced-audio-coding-patent-pool-licensee/). VP8 and
  VP9 are royalty-free under Google's patent licence (knowledge), though a pool for VP9 is disputed
  (https://streaminglearningcenter.com/blogs/sisvel-announces-patent-pools-for-vp9-and-av1.html).
- **Native rules.** gstreamer-lite on Windows exports by ordinal from a `.def` that grows append-only
  (`modules/javafx.media/native/win.cmake:681`, `backlog/README.md`, US-032 row). The SDK's natives check only counts
  that javafx.graphics and javafx.media each contributed at least one library (`sdk/pom.xml:245-270`).
- **WebView.** WebView hands WebKit `MediaManager.getSupportedContentTypes()`
  (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/prism/PrismGraphicsManager.java:155`). WebKit answers
  `canPlayType` from the container type alone, and "probably" for any `codecs` parameter of a listed container
  (`modules/javafx.web/src/main/native/Source/WebCore/platform/graphics/java/MediaPlayerPrivateJava.cpp:225-228`).

## Constraints
- **Licences.** Third-party Java code inside the jars is MIT, BSD, zlib, CC0, BSL-1.0 or similar; otherwise the code is
  written in-house from the specification (`backlog/README.md`, "Media formats"). Native code keeps port rule P8
  (`backlog/README.md`, "Port rules") and the licence rule behind it, including its maintainer sign-off for any licence
  not on the allowed list (`.claude/skills/jfx-rust-port/SKILL.md:186-193`). Every notice ships through US-075.
- **Patents.** This story adds no H.264, HEVC or AAC decoder; those stay with the platform decoders.
- **Native code** is built from source for the five CI targets. Any new boundary between Java and native code is a
  plain C ABI bound through FFM, with no JNI (`.claude/skills/jni-to-ffm-migration/SKILL.md:148-153`). Native
  access stays as javafx.media has it today (`.claude/skills/openjfx-conventions/SKILL.md:157-160`), and
  gstreamer-lite's Windows `.def` is only appended to.
- **Sequencing.** Native pipeline changes land after US-040. US-036's slice-0 "unsupported content" scenario
  (`backlog/US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md:99`) uses content that stays unsupported after
  this story, so not WebM or Matroska.
- **Scope of change.** No logic or public API of the other `javafx.*` modules changes (`README.md:20-21`). Every type
  that plays today keeps its back end and its output (`.claude/skills/jni-to-ffm-migration/SKILL.md:91`).
- **Bounds first.** Every tolerance is recorded in this story before the first comparison runs, never chosen
  afterwards.
- **Java.** Java 27 final features only (no preview, no incubator such as the Vector API, no `var`). Production Java
  files are at most 900 lines (`wc -l`); tests and shims are exempt. Comments only for a non-obvious "why". GPLv2+CPE
  headers; ported permissive code keeps its notice in the second header block
  (`.claude/skills/openjfx-conventions/SKILL.md:86-89`).
- **Tests.** Test media is generated by a command recorded next to it, never downloaded (`backlog/README.md`, "Port
  rules"). Reference outputs are captured as the house does (`.claude/skills/jfx-ffm-testing/SKILL.md:268-285`). Agents
  never
  run `USE_ROBOT` or `tests/system`; visual and listening checks, real-device and latency measurements are the owner's.

## Out of scope
- AV1, VP9 profiles 1 to 3, and H.264, HEVC, AAC or any other codec inside Matroska.
- Matroska subtitle and attachment tracks; they are ignored.
- Encrypted WebM, and Media Source Extensions in WebView.
- Writing WebM or Matroska.

## Acceptance criteria
1. **Plays everywhere.** `.webm` files with VP8 or VP9 (profile 0) video and Opus or Vorbis audio, audio-only WebM, and
   `.mkv` files with the same codecs play on Windows, macOS and Linux through `Media`, `MediaPlayer` and `MediaView`,
   and audio-only files through `AudioClip` too. They are recognised by name (any case), by HTTP content type and by
   their bytes, from every protocol other media play from (`impl/platform/gstreamer/GSTPlatform.java:70-76`,
   `impl/platform/osx/OSXPlatform.java:59-65`).
   Playback keeps the `MediaPlayer` contract of the formats that play today: status transitions and events, seek,
   rate, cycle count, start and stop time, markers, volume, balance, mute, equalizer, audio spectrum, and the video
   size and tracks `Media` reports.
2. **Exact video.** Decoded VP8 and VP9 frames equal the reference decoder's (libvpx) output exactly over a corpus
   covering key and inter frames, reference-frame updates, hidden frames, odd sizes, mid-stream resolution changes,
   tiles and every loop-filter and segmentation feature the encoder can write.
3. **Audio.** Opus and Vorbis audio meets the bounds US-104 records against the reference decoders; whichever of the
   two stories lands first records them before its first comparison. Audio decoded in Java is identical on the five
   CI targets.
4. **On every pipeline.** The frames appear in `MediaView` on D3D, ES2, Metal and SW, converted to RGB as the stream
   signals, and honour `fitWidth`, `fitHeight`, `preserveRatio`, `smooth` and `viewport`. The owner checks the
   picture on each pipeline.
5. **In sync and in real time.** Each frame is shown within one frame duration of its timestamp on the audio clock.
   1080p VP8 and VP9 at 30 frames per second play at rate 1.0 with no dropped frame on one owner machine per operating
   system, each recorded.
6. **Seek.** After `seek(t)` the frame shown is the one whose display interval holds t, with and without a Cues index,
   and audio resumes at the sample for t.
7. **Matroska.** The corpus covers SimpleBlock and BlockGroup, every lacing kind, unknown-size clusters, files with
   and without Cues, several audio tracks (the default one plays and `Media` lists all), and Opus codec delay and seek
   pre-roll.
8. **Unplayable or damaged input.** A file whose video or audio codec is outside criterion 1 fails with US-096's error
   naming the codec. A damaged cluster is skipped and playback resumes at the next key frame. A seeded mutation run
   over the corpus, with its seed and count recorded, never hangs or crashes.
9. **Natives in the SDK.** Every native library this story adds ships in the SDK on every target, and the SDK's
   natives check fails when one is missing, shown on a planted case.
10. **Documented and visible.** The public container and encoding tables list WebM and Matroska with their codecs, and
    track metadata names VP8, VP9, Opus and Vorbis, with the Java and C++ encoding ordinals still equal. WebView's
    `canPlayType` answers for the content types this story adds change from empty to "maybe" without a `codecs`
    parameter and to "probably" with one, whatever codecs it names; the answers are checked and recorded.
11. **Nothing else changes.** The media module's tests pass with the counts of a run before the change, plus the new
    tests. No public API is added. All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `media-format-reviewer` and by `ffm-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
- The owner runs the robot tests and the visual checks.

## Notes
- Owner ruling, 2026-11-07: Matroska and WebM video are in scope now.
- Related: US-104 (Opus and Vorbis decoding and their bounds); US-106 (sidecar subtitles; Matroska subtitle tracks
  stay out of scope in both).
