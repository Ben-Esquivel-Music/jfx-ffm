# US-100 — Let media decoded in Java play through javafx.media

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey) · **Epic:** Media formats ·
**Blocked by:** US-096 (a failed playback must report one clear error before more formats can fail) ·
**Blocks:** US-101, US-102, US-103, US-104, US-105

## Story
As the developer of a JavaFX application that plays media,
I want media that javafx.media demuxes and decodes in Java to play through `Media`, `MediaPlayer`, `MediaView` and
`AudioClip` exactly as native media does,
so that new formats can be added without a new native decoder on every platform.

## Problem
Paths that start with `jfxmediaimpl/` or `jfxmedia/` are under `modules/javafx.media/src/main/java/com/sun/media/`,
`javafx/scene/media/` is under `modules/javafx.media/src/main/java/`, and `prism/` is
`modules/javafx.graphics/src/main/java/com/sun/prism/`.
- **Nothing plays in Java today.** `JavaPlatform` creates neither media nor a player
  (`jfxmediaimpl/platform/java/JavaPlatform.java:68-76`); it only parses the ID3 tags of MP3 (`:57-66`). The only
  players are `GSTMediaPlayer` and `OSXMediaPlayer` (grep for `extends NativeMediaPlayer`).
- **Java is asked first.** Platforms are tried in the order Java, GStreamer, then AVFoundation on macOS, and the first
  that claims the content type and returns a player wins (`jfxmediaimpl/platform/PlatformManager.java:82-110`,
  `:226-240`). A type the Java side claims therefore leaves its current back end on every OS (inferred).
- **AudioClip follows.** Every `AudioClip` play gets its player from the same `MediaManager.getPlayer`
  (`jfxmediaimpl/NativeMediaAudioClipPlayer.java:318-319`).
- **No PCM into GStreamer.** `appsrc` is compiled out of gstreamer-lite
  (`modules/javafx.media/src/main/native/gstreamer/gstreamer-lite/gst-plugins-base/gst/app/gstapp.c:32-34`), and the
  pipeline factory builds pipelines only for the content types it knows
  (`modules/javafx.media/src/main/native/jfxmedia/platform/gstreamer/GstPipelineFactory.cpp:148-203`).
- **Video frames.** Frames reach `MediaView` as `NewFrameEvent`s, whose constructor is public
  (`jfxmedia/events/NewFrameEvent.java:43`); `NativeVideoBuffer` is final and package-private
  (`jfxmediaimpl/NativeVideoBuffer.java:37`). `MediaView` takes `BGRA_PRE`, `YCbCr_420p` and `YCbCr_422` frames and
  rejects `ARGB` (`modules/javafx.media/src/main/java/com/sun/javafx/media/PrismMediaFrameHandler.java:203-217`).
  Metal, the macOS default (`prism/impl/PrismSettings.java:208`), shows only YCbCr frames: a 4:2:0 frame gets a
  texture per plane (`prism/mtl/MTLResourceFactory.java:217-232`, `prism/MultiTexture.java:200-225`), and any other
  frame updates a texture that takes only `BYTE_APPLE_422` (4:2:2) and throws for anything else
  (`prism/mtl/MTLTexture.java:228-231`). A `BGRA_PRE` frame becomes `INT_ARGB_PRE`
  (`PrismMediaFrameHandler.java:204-205`) and so fails on Metal (inferred).
- **Spectrum and equalizer.** The scene layer uses the player's audio spectrum with no null check
  (`javafx/scene/media/MediaPlayer.java:1823-1839`); a missing equalizer is tolerated
  (`javafx/scene/media/AudioEqualizer.java:121-134`).
- **Without the natives.** When the media natives fail to load and no error listener is registered,
  `MediaUtils.nativeError` throws (`jfxmediaimpl/MediaUtils.java:339-357`), and a test asserts that creating a player
  then fails with `ERROR_MANAGER_ENGINEINIT_FAIL`
  (`modules/javafx.media/src/test/java/test/com/sun/media/jfxmediaimpl/NativeMediaManagerDegradationTest.java:163-164`).
- **Modules.** javafx.media requires javafx.base and javafx.graphics
  (`modules/javafx.media/src/main/java/module-info.java:35-36`), and both require `java.desktop`
  (`modules/javafx.base/src/main/java/module-info.java:34`,
  `modules/javafx.graphics/src/main/java/module-info.java:43`), so `java.desktop` is already in the module graph.
- **WebView.** WebView advertises `MediaManager.getSupportedContentTypes()`
  (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/prism/PrismGraphicsManager.java:154-155`), so each type the
  Java side claims changes WebView's `canPlayType` answer (inferred).

## Constraints
- **What plays today keeps playing as today.** Every content type that plays today keeps its back end and its output
  on every OS. Behaviour-neutrality outranks less native code (`.claude/skills/jni-to-ffm-migration/SKILL.md:91-107`).
- **One audio output** (owner ruling, `backlog/README.md`, "Media formats"). Audio decoded in Java and US-092's mixed
  voices leave through one audio output path; there is only one. Whichever of the two stories lands first creates it,
  and
  the other uses it.
- **API.** This story adds no public API and changes nothing outside javafx.media.
- **Licences** (owner ruling, `backlog/README.md`, "Media formats"). Third-party Java code inside the jars is MIT, BSD,
  zlib, CC0, BSL-1.0 or similarly permissive; no LGPL and no Apache-2.0 Java code. Where no permissive library exists,
  the
  code is written in-house from the specification. Native code keeps port rule P8 (`backlog/README.md`, "Port rules").
  Every notice ships through US-075.
- **Native code.** Any native change keeps the fork's FFM rules: plain C ABIs bound through FFM, no JNI. If it
  touches gstreamer-lite or `GstPipelineFactory.cpp`, it lands after US-040. Either way, the content of US-036's
  slice-0 "unsupported content" scenario (`backlog/US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md:99`)
  stays unsupported.
- **Java.** Java 27 final features only (no preview, no incubator such as the Vector API, no `var`). Production Java
  files this story adds are at most 900 lines (`wc -l`), and files already over that, such as
  `javafx/scene/media/MediaPlayer.java` (2,863) and `jfxmediaimpl/NativeMediaPlayer.java` (1,721), do not grow;
  tests and shims are exempt. Comments only for a non-obvious "why". GPLv2+CPE headers; ported permissive code also
  keeps its notice in the second header block (`.claude/skills/openjfx-conventions/SKILL.md:86-89`).
- **Tests.** Test media is generated by a command recorded next to it; nothing downloaded is committed
  (`backlog/README.md`, "Port rules"). Reference outputs are captured as the house captures goldens
  (`.claude/skills/jfx-ffm-testing/SKILL.md:268-286`). Agents never run `USE_ROBOT` or `tests/system` runs; robot and
  visual tests, listening checks and real-device measurements are the owner's.

## Out of scope
- Playing Java-decoded media when the media natives fail to load. It would change what
  `NativeMediaManagerDegradationTest` asserts; a later story may take it.
- Moving a content type that plays today to Java.
- The formats themselves, apart from the first one (criterion 8): US-101 to US-105.

## Acceptance criteria
1. **The contract of native media.** Media demuxed and decoded in Java plays through `Media`, `MediaPlayer`,
   `MediaView` and `AudioClip` with the contract native media has: the status transitions
   (`javafx/scene/media/MediaPlayer.java:159-187`) and events, seek, rate, cycle count, start and stop time, markers,
   metadata, volume, balance, mute, equalizer, audio spectrum, video frames, and errors as US-096 defines them, a
   missing audio device included. One contract suite runs the same scenarios against a generated WAV played natively
   and against Java-decoded media, and both pass.
2. **Equalizer and spectrum act on the sound.** For Java-decoded audio, raising one equalizer band raises the output
   level around that band's centre frequency, and a generated sine puts the spectrum's peak in the band that holds its
   frequency. Tests show both.
3. **Frames on every pipeline.** Java-decoded frames show in `MediaView` on the D3D, ES2, Metal and SW pipelines, and
   `fitWidth`, `fitHeight`, `preserveRatio`, `smooth` and `viewport` behave as for native video. Owner-run robot tests
   check each pipeline.
4. **Existing types unchanged.** For every content type that plays today, a test shows on each CI leg that the
   platform that played it before still plays it.
5. **Same on five targets.** Decoded PCM, and decoded frames where there is video, are identical on Windows x64,
   macOS x64 and aarch64, and Linux x64 and aarch64, shown by hashes compared against a committed golden on each leg.
6. **One audio output.** The module has one audio output path. Java-decoded audio uses it, and so do US-092's voices
   once that story lands.
7. **Modules and WebView.** Every `requires` the change adds to javafx.media's `module-info.java` is listed here when
   it lands and names a module already in the graph of javafx.base and javafx.graphics. Each content type the Java
   side adds is in `MediaManager.getSupportedContentTypes()`, and a test checks WebView's `canPlayType` answer for it.
8. **Proven by a format.** The path lands with a first Java-decoded format: one of US-101 to US-105, delivered with
   this story and meeting that story's criteria.
9. **Nothing else broken.** The media module's tests pass with the counts of a run before the change, plus the new
   tests, and all five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `media-format-reviewer` signed off, and by `ffm-reviewer` if native or FFM code changed.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
- The owner runs the robot tests.

## Notes
- The owner's goal, 2026-11-07: "enable more media types to be supported in this codebase. Right now, the media module
  is extremely limited in what media types it supports, both native (.wav) and pure java (.cdg + .mp3)."
- Related: US-092 (the one audio output path; the two stories settle it together, `backlog/README.md`, "Media formats",
  Order). US-101 to US-105 add their formats on this path.
