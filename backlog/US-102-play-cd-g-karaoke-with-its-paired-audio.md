# US-102 — Play CD+G karaoke with its paired audio

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey; pairing revised 2026-11-07 to an explicit URI
pair) · **Epic:** Media formats ·
**Blocked by:** US-096 (clear errors for the new failure cases), US-100 (frames decoded in Java reach `MediaView`) ·
**Blocks:** none

## Story
As the developer of a karaoke application,
I want to create a `Media` from the URI of a `.cdg` graphics file and the URI of its audio, named explicitly, and have
it play the audio with the CD+G graphics in a `MediaView`, in step with the audio,
so that karaoke plays through the JavaFX media API without a decoder, a clock and a viewer of my own, and without the
media module guessing which files belong together.

## Problem
Paths that start with `jfxmediaimpl/` are under `modules/javafx.media/src/main/java/com/sun/media/`,
`javafx/scene/media/` is under `modules/javafx.media/src/main/java/`, and `prism/` is
`modules/javafx.graphics/src/main/java/com/sun/prism/`. `jkaraoke/` is the owner's jKaraoke
(https://github.com/Ben-Esquivel-Music/jKaraoke) at commit `18f6f58cbb`, licensed CC0 (`jkaraoke/LICENSE:1-3`);
`CdgImageReader.java` and `CdgImageViewer.java` are in `jkaraoke/application/src/main/java/karaoke/media/`.
"CD+G Revealed" is https://jbum.com/cdg_revealed.html.
- **One source string.** `Media` has one constructor, taking one source URI string
  (`javafx/scene/media/Media.java:376`), and `getSource()` returns it (`:433`). Nothing in the API lets an
  application name a second file that belongs to the same media, so an audio file and a graphics file can only be
  paired by convention: a scan of the directory or archive beside one of them.
- **No CD+G type.** The extension map does not know `cdg` (`jfxmediaimpl/MediaUtils.java:190-227`), so a `.cdg`
  falls to the byte sniffer, which throws "Unrecognized file signature!" (`:179`), and `Media` turns that into
  `MEDIA_UNSUPPORTED` (`javafx/scene/media/Media.java:409-410`). Extensions are already compared in lower case
  (`MediaUtils.java:198`). `jar:` URIs are accepted and checked against their inner scheme
  (`jfxmedia/locator/Locator.java:215-223`), so an entry of a `.zip` is addressable as
  `jar:file:/path/song.zip!/song.cdg`.
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
  (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/prism/PrismGraphicsManager.java:154-155`), so any type
  this story put there would be advertised to WebView (inferred), which can hand over only one URI.

## Constraints
- **The application names the pair** (owner ruling, 2026-11-07, `backlog/README.md`, "Media formats"). A CD+G
  `Media` is created from two URI strings, `videoUri` for the `.cdg` and `audioUri` for the audio, carried by one
  public record type. The media module never scans a directory or an archive for a companion: a `.cdg` given alone as
  a plain source string is not CD+G media. An MP3+G `.zip` plays through two `jar:` URIs into it; the module does not
  open a `.zip` by itself.
- **Opening the audio alone behaves exactly as today** (owner ruling, `backlog/README.md`, "Media formats").
- **The audio plays as it does alone.** The paired audio plays through the back end and decoder that play it on its
  own, with the same output (for MP3, the fill-gaps ruling, `backlog/README.md`, "Media formats").
- **The CD+G implementation sits on a separate audio-and-video input facade** (owner direction, 2026-11-07). The part of
  the module that takes the pair, keeps the audio and the separately-sourced video stream in step and presents one
  `Media` is a facade that knows nothing of CD+G; the CD+G decoder sits behind it as one video-stream kind. No platform
  claims a CD+G content type (criterion 13). A later format whose graphics come as a separate file reuses the facade's
  pairing, clock and error handling and adds only its decoder. The names of the record and its components, and of any
  other public API this adds, are listed here with `@since` when the story lands (javafx.media may add public API only
  where its story names it, `README.md:20-21`). How the facade and the player layer behind it are shaped in Java is the
  implementer's.
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
  in-house from the specification. Native code keeps port rule P8 (`backlog/README.md`, "Port rules"). Every notice
  ships through US-075.
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
- Finding a companion for a `.cdg`, a `.zip` or an audio file by scanning a directory or an archive, over any
  scheme. The application names both URIs.
- A `.cdg` or a `.zip` given as the one source string of `new Media(String)`. Each keeps failing with
  `MEDIA_UNSUPPORTED`, as today, and the public format table says so (criterion 13).
- `AudioClip` with the pair, a `.cdg` or a `.zip`. It takes one source string and keeps failing with
  `MEDIA_UNSUPPORTED`, as today (`modules/javafx.media/src/main/java/javafx/scene/media/AudioClip.java:91`).
- A second shipped video-stream kind behind the facade. This story lands the facade with CD+G only; the stub of
  criterion 12 is test code.

## Acceptance criteria
1. **Creating paired media.** A `Media` is created from a public record holding a `videoUri` string and an `audioUri`
   string; `new Media(String)` is unchanged. The record rejects a null or blank component at construction and is
   readable back from the `Media`. `videoUri` names a `.cdg` over any scheme `Media` accepts, extensions matching in
   any case (`.CDG`); `audioUri` names anything the media module plays as audio over any scheme `Media` accepts.
2. **MP3+G archives.** The pair works with two `jar:` URIs into one `.zip` (`jar:file:/path/song.zip!/song.cdg` and
   `jar:file:/path/song.zip!/song.mp3`), with bounded entry reads and nothing extracted beside the user's files.
3. **The audio alone is unchanged.** Opening the audio URI on its own gives the same content type, platform,
   pipeline, tracks, metadata, events and output as before, and no graphics.
4. **Clear failures.** A `MediaException` whose type and message name the cause, and which of the two inputs, is
   raised for: a `videoUri` that is not a `.cdg`; a `.cdg` too short to hold one whole packet; an `audioUri` the
   module cannot play; and a URI that cannot be opened. Each arrives where US-096 criterion 4 says: thrown from the
   `Media` constructor when neither URI's locator blocks, set on `Media.error` when either does, and a cause found at
   player creation arrives exactly where it arrives for the audio alone. Every `.cdg` fault is reported before the
   player reaches `READY`. A read error on the audio during playback reaches `MediaPlayer.onError` with a type and
   message that name it and the input. Each cause has one type, as US-096 requires, and the type of each is listed
   here when the story lands.
5. **What `Media` reports.** Width 300 and height 216; the audio's duration; the audio's metadata, entries that
   arrive late included; a video track and the audio's track; `getSource()` equal to `videoUri`, and the record
   equal to the one given.
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
   and every `MediaPlayer` event fires as it does for the audio alone, once each, apart from the events of the added
   video track. A test runs one script against the audio alone and against the paired media and compares the event
   logs.
10. **FX thread.** A test shows that no scene-graph object changes off the FX thread while paired media plays, seeks
    and stops.
11. **Same on five targets.** Decoded frames are identical on Windows x64, macOS x64 and aarch64, and Linux x64 and
    aarch64, shown by hashes of a generated corpus compared against a committed golden on each leg.
12. **The facade stands apart from CD+G.** A test creates paired media through the facade with a stub video-stream
    kind in place of CD+G and shows the clock, seek, error and event behaviour of criteria 4, 7, 9 and 10 hold
    without the CD+G decoder.
13. **Documented and advertised.** The public format tables (`javafx/scene/media/package-info.java:43-109`) list CD+G
    with the pair it needs and the MP3+G `jar:` form, and state that a `.cdg` or a `.zip` given alone does not play.
    This story adds nothing to `MediaManager.getSupportedContentTypes()`, so US-100 criterion 7 has nothing to list
    and WebView, which can pass only one URI, is not offered CD+G. A test checks that `getSupportedContentTypes()` is
    unchanged by this story; that `<video>` of a `.cdg` in WebView still fails is an owner-run check with the
    javafx.web tests enabled (`-Djfx.web.skipTests=false`), since CI does not run them.
14. **Nothing else broken.** The media module's tests pass with the counts of a run before the change, plus the new
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
  opening the `.mp3` alone behaves as today.
- Owner ruling, 2026-11-07 (revised the same day): MP3+G is opened from an explicit `videoUri` and `audioUri` pair,
  not by scanning beside a `.cdg` or inside a `.zip`. The earlier form, `new Media(".../song.cdg")` finding its audio
  by base name, is withdrawn: it needed a directory scan, worked only for `file:` and `jar:`, and had to fail on
  "several companions". A `.zip` is reached through two `jar:` URIs, which `Media` already accepts.
- Owner direction, 2026-11-07: the CD+G implementation sits on a more abstract separate audio-and-video input pair
  facade; that is the facade constraint and criterion 12. A sealed interface was floated for it and is not a
  requirement; the Java shape is the implementer's.
- Related: US-101 (MP3+G on Linux without ffmpeg plays once it lands); US-105 (KAR, the other karaoke format).
