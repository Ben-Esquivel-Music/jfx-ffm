# US-102 — Play CD+G karaoke with its paired audio

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey) · **Epic:** Media formats ·
**Blocked by:** US-096 (clear errors for the new failure cases), US-100 (frames decoded in Java reach `MediaView`) ·
**Blocks:** none

## Story
As the developer of a karaoke application,
I want `new Media` on a `.cdg` file or an MP3+G `.zip` to play the song's audio with its CD+G graphics in a
`MediaView`, in step with the audio,
so that karaoke plays through the JavaFX media API without a decoder, a clock and a viewer of my own.

## Problem
Paths that start with `jfxmediaimpl/` are under `modules/javafx.media/src/main/java/com/sun/media/`,
`javafx/scene/media/` is under `modules/javafx.media/src/main/java/`, and `prism/` is
`modules/javafx.graphics/src/main/java/com/sun/prism/`. `jkaraoke/` is the owner's jKaraoke
(https://github.com/Ben-Esquivel-Music/jKaraoke) at commit `18f6f58cbb`, licensed CC0 (`jkaraoke/LICENSE:1-3`);
`CdgImageReader.java` and `CdgImageViewer.java` are in `jkaraoke/application/src/main/java/karaoke/media/`.
"CD+G Revealed" is https://jbum.com/cdg_revealed.html.
- **No CD+G type.** The extension map knows neither `cdg` nor `zip` (`jfxmediaimpl/MediaUtils.java:190-227`), so a
  `.cdg` falls to the byte sniffer, which throws "Unrecognized file signature!" (`:179`), and `Media` turns that into
  `MEDIA_UNSUPPORTED` (`javafx/scene/media/Media.java:409-410`). Extensions are already compared in lower case
  (`MediaUtils.java:198`).
- **MediaView shows its player's frames only.** They come from the renderer listener the scene player registers on
  its internal player (`javafx/scene/media/MediaPlayer.java:448`).
- **The clock is fine-grained.** `getCurrentTime()` asks the player for the presentation time on demand
  (`MediaPlayer.java:1333-1338`); only the property's change events come every 100 ms (`:2836`).
- **CD+G** (CD+G Revealed):
  - Packets of 24 bytes: command, instruction, 2 Q-parity bytes, 16 data bytes, 4 P-parity bytes. A packet carries
    CD+G when the low 6 bits of its command are 9. 4 packets a sector at 75 sectors a second make 300 packets a
    second.
  - A 300×216 raster and a 16-entry colour table drawn from 4,096 colours, 4 bits each of R, G and B.
  - Nine instructions: Memory Preset (1), Border Preset (2), Tile Block (6), Scroll Preset (20), Scroll Copy (24),
    Define Transparent Color (28), Load Color Table entries 0-7 (30) and 8-15 (31), Tile Block XOR (38).
  - 6×12 tiles at row × 12 and column × 6. Scrolls carry a horizontal offset of 0-5 and a vertical offset of 0-11
    pixels.
  - It is ambiguous about the border: it calls "the middle 294 x 204 area" visible and also gives the rectangle
    "(6,12,294,204)".
- **jKaraoke, the owner's workaround:**
  - It opens the audio and looks beside it for one `.cdg` with the same base name, case-sensitively and only for
    `file:` URIs (`jkaraoke/application/src/main/java/karaoke/controller/MediaViewFxmlController.java:94-119`). It
    has no `.zip` support (grep).
  - It draws into an `ImageView` of its own on a 50 ms timer that never reads the audio clock
    (`CdgImageReader.java:15-17`, `:50`, `:57`, `:101-147`), and changes that node from the timer thread
    (`CdgImageViewer.java:137-144`, `:229-243`).
  - Its decoder departs from the specification: tiles are drawn at an offset of (+3, +6) and tiles in row 17 or
    column 49 are dropped (`CdgImageReader.java:176-187`); Memory Preset clears only the interior
    (`CdgImageViewer.java:188-194`); scroll offsets are ignored (`CdgImageViewer.java:89-99`); a seek moves to the
    same fraction of the file and does not rebuild the screen (`CdgImageReader.java:80-92`, `:205-209`); packets are
    read without the lock that seek and close take (`CdgImageReader.java:73`, `:82`, `:221-227`), and the pause flag
    is read without it too (`:39`, `:102`).
  - It relies on the audio's equalizer, spectrum and late metadata
    (`jkaraoke/application/src/main/java/karaoke/controller/EqualizerFxmlController.java:162-164`;
    `jkaraoke/application/src/main/java/karaoke/controller/KaraokeFxmlController.java:480`, `:498-510`, `:539-541`).
- **Exact colours meet Prism.** Metal, the macOS default (`prism/impl/PrismSettings.java:208`), shows only YCbCr
  frames: 4:2:0 frames carry chroma at half width and height (`prism/mtl/MTLResourceFactory.java:217-232`), and any
  other frame updates a texture that takes only `BYTE_APPLE_422` (YCbCr 4:2:2) (`prism/mtl/MTLTexture.java:228-231`).
  A `BGRA_PRE` frame becomes `INT_ARGB_PRE`
  (`modules/javafx.media/src/main/java/com/sun/javafx/media/PrismMediaFrameHandler.java:204-205`) and so fails on
  Metal (inferred). 4:2:2 shares chroma between horizontal pixel pairs (knowledge). A palette raster cannot reach the
  screen exactly through a YCbCr format (inferred).
- **WebView.** WebView advertises `MediaManager.getSupportedContentTypes()`
  (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/prism/PrismGraphicsManager.java:154-155`), so the types
  this story adds change its `canPlayType` answer (inferred).

## Constraints
- **The karaoke file opens the song** (owner ruling, `backlog/README.md`, "Media formats"). Opening the audio file alone
  behaves exactly as today.
- **The audio plays as it does alone.** The companion audio plays through the back end and decoder that play it on
  its own, with the same output (for MP3, the fill-gaps ruling, `backlog/README.md`, "Media formats").
- **jKaraoke.** Its code may be ported (CC0). A ported file keeps its notice in the second header block
  (`.claude/skills/openjfx-conventions/SKILL.md:86-89`), the notice ships through US-075, and none of the defects
  listed under "Problem" is carried over.
- **javafx.graphics.** This story names one change it may make there, if exact colours need it: Prism showing media
  frames with every pixel's colour exact on each pipeline. The change adds no public API, renders frames in the
  formats Prism accepts today as before, and is listed here when it lands. Nothing else outside javafx.media changes
  (`README.md:20-21`).
- **Threads.** No scene-graph object changes off the FX thread.
- **Licences** (owner ruling, `backlog/README.md`, "Media formats"). Third-party Java code inside the jars is MIT, BSD,
  zlib, CC0, BSL-1.0 or similarly permissive; no LGPL and no Apache-2.0 Java code; otherwise the code is written
  in-house
  from the specification. Native code keeps port rule P8 (`backlog/README.md`, "Port rules"). Every notice ships through
  US-075.
- **Native code.** Any native change keeps the fork's FFM rules: plain C ABIs bound through FFM, no JNI. If it
  touches gstreamer-lite or `GstPipelineFactory.cpp`, it lands after US-040. Either way, the content of US-036's
  slice-0 "unsupported content" scenario (`backlog/US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md:99`)
  stays unsupported.
- **Java.** Java 27 final features only (no preview, no incubator such as the Vector API, no `var`). Production Java
  files this story adds are at most 900 lines (`wc -l`), and files already over that do not grow; tests and shims are
  exempt. Comments only for a non-obvious "why". GPLv2+CPE headers.
- **Tests.** CD+G streams and audio for tests are generated by a command recorded next to them; nothing downloaded is
  committed (`backlog/README.md`, "Port rules"). Reference rasters are captured as the house captures goldens
  (`.claude/skills/jfx-ffm-testing/SKILL.md:268-286`). Agents never run `USE_ROBOT` or `tests/system` runs; the owner
  runs the robot and visual tests.

## Out of scope
- CD+EG (extended graphics), KAR karaoke (US-105), and video karaoke beyond what plays today.
- Graphics for an audio file opened on its own.
- Finding a companion over the other schemes `Media` accepts (http, https, jrt, resource). A `.cdg` opened over them
  fails with the no-companion error.
- `AudioClip` given a `.cdg` or an MP3+G `.zip`. It keeps failing with `MEDIA_UNSUPPORTED`, as today
  (`modules/javafx.media/src/main/java/javafx/scene/media/AudioClip.java:91`).

## Acceptance criteria
1. **Opening a `.cdg`.** `new Media(uri)` succeeds for the `file:` or `jar:` URI of a `.cdg` whose directory holds
   exactly one file with the same base name that the media module can play as audio. Extensions match in any case
   (`.CDG`, `.Mp3`).
2. **Opening an MP3+G `.zip`.** `new Media(uri)` succeeds for the `file:` URI of a `.zip` that holds exactly one
   `.cdg` and exactly one playable audio entry with the same base name.
3. **The audio alone is unchanged.** Opening the companion audio on its own gives the same content type, tracks,
   metadata, events and output as before, and no graphics.
4. **Clear failures.** `new Media` throws a `MediaException` whose type and message name the cause for: no
   companion; several companions; a `.zip` with no `.cdg`, no audio entry or several of either; and a `.cdg` too short
   to hold one whole packet. A read error during playback reaches `MediaPlayer.onError` with a type and message that
   name it. Each cause has one type, as US-096 requires, and the type of each is listed here when the story lands.
5. **What `Media` reports.** Width 300 and height 216; the audio's duration; the audio's metadata, entries that
   arrive late included; a video track and the audio's track; and `getSource()` equal to the URI given.
6. **Display.** `MediaView` shows the CD+G raster and honours `fitWidth`, `fitHeight`, `preserveRatio`, `smooth` and
   `viewport` as it does for video. Shown at its own size, every pixel has exactly the colour the decoder gives it,
   with a 4-bit component c shown as the 8-bit value 17 × c, on the D3D, ES2, Metal and SW pipelines. Owner-run robot
   tests check each pipeline.
7. **In step with the audio.** At media time t the screen equals the result of applying packets [0, ⌊300·t⌋) to
   within one packet, through play, pause, stall, every rate, seek, cycle count, and start and stop times. After any
   seek the screen equals what playback from the start shows at that time, with no stale content. Tests compare the
   frames with reference rasters at many times in each case.
8. **Decoded per the specification.** All nine instructions follow CD+G Revealed: Memory Preset clears the whole
   screen, Border Preset fills the specification's border, tiles land at row × 12 and column × 6 (row 17 and column
   49 included), scroll offsets take effect, XOR tiles XOR the colour indices, and both halves of the colour table
   load. Packets that are not CD+G and a trailing partial packet are ignored. Where the specification is ambiguous
   (the border, tiles outside the raster, Memory Preset repeats, the effect of Define Transparent Color), a CD+G
   decoder independent of this implementation and of jKaraoke, chosen before comparison, decides, and each ruling is
   recorded here.
9. **The audio's behaviour.** Equalizer, spectrum, volume, balance and mute act on the audio as when it plays alone,
   and every `MediaPlayer` event fires as it does for the audio alone, once each. A test runs one script against the
   audio alone and against the CD+G media and compares the event logs.
10. **FX thread.** A test shows that no scene-graph object changes off the FX thread while CD+G media plays, seeks
    and stops.
11. **Same on five targets.** Decoded frames are identical on Windows x64, macOS x64 and aarch64, and Linux x64 and
    aarch64, shown by hashes of a generated corpus compared against a committed golden on each leg.
12. **Documented and advertised.** The public format tables (`javafx/scene/media/package-info.java:43-109`) list CD+G
    with its pairing rules. A test checks WebView's `canPlayType` answer for the content types this story adds.
13. **Nothing else broken.** The media module's tests pass with the counts of a run before the change, plus the new
    tests; if javafx.graphics changes, so do its tests; all five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `media-format-reviewer` signed off, and by `ffm-reviewer` if native or FFM code changed.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**
- The owner runs the robot tests.

## Notes
- Owner rulings, 2026-11-07: jKaraoke's CD+G code may be ported freely, but its decoder defects are not inherited;
  MP3+G plays when the `.cdg` or the `.zip` is opened, and opening the `.mp3` alone behaves as today.
- Related: US-101 (MP3+G on Linux without ffmpeg plays once it lands); US-105 (KAR, the other karaoke format).
