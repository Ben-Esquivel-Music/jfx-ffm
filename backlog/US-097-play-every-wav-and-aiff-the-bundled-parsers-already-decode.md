# US-097 — Play every WAV and AIFF the bundled parsers already decode

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey) · **Epic:** Media formats ·
**Blocked by:** none · **Blocks:** US-098 (it adds encodings to the forms this story makes playable)

## Story
As an application developer whose users bring WAV and AIFF files from many tools,
I want every PCM and float WAV and AIFF to play, whatever header form, size format or chunk order the tool chose,
so that a 24-bit WAV, a broadcast WAV, an RF64 recording or an AIFC file plays like the files that play today.

## Problem
Paths starting `ji/` are under `modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/`, `jm/` under
`modules/javafx.media/src/main/java/com/sun/media/jfxmedia/`, `mt/` under
`modules/javafx.media/src/test/java/test/com/sun/media/jfxmediaimpl/`, `N/` under
`modules/javafx.media/src/main/native/`, and `G/` under `N/gstreamer/gstreamer-lite/`.
- **WAV is always checked against its first 22 bytes.** A source typed `audio/x-wav` is typed again from its file
  signature (`jm/locator/Locator.java:419-423`), and the sniffer reads 22 bytes (`ji/MediaUtils.java:46`).
- **With `fmt ` first, only plain PCM and float pass.** Format tag 1 or 3 gives `audio/x-wav`; any other tag throws
  "Compressed WAVE is not supported!" (`ji/MediaUtils.java:114-118`). That includes `WAVE_FORMAT_EXTENSIBLE` (0xFFFE),
  whose sub-format GUID sits at byte 44, past the 22 bytes read (knowledge: the `WAVEFORMATEXTENSIBLE` layout). ffmpeg
  writes EXTENSIBLE for integer samples wider than 16 bits, so its 24-bit WAVs are refused today (knowledge).
- **Another chunk first skips the check.** A RIFF/WAVE file with `JUNK`, `bext` or any other chunk before `fmt ` is
  typed `audio/x-wav` and its format is left to the native parser (`ji/MediaUtils.java:119-128`). EXTENSIBLE then
  plays, and a compressed encoding fails later, in the pipeline (inferred).
- **RF64 and BW64 are refused.** Their magic matches no signature, so they throw "Unrecognized file signature!", even
  when named `.wav` (`ji/MediaUtils.java:178-179`).
- **The native WAV parser.** wavparse accepts `audio/x-wav` and `audio/x-rf64`
  (`G/gst-plugins-good/gst/wavparse/gstwavparse.c:118`) and reads the `ds64` chunk (`:1115-1142`). The RIFF reader
  accepts the `RIFF`, `AVF0` and `RF64` tags, not `BW64` (`G/gst-plugins-base/gst-libs/gst/riff/riff-read.c:241-244`).
  `riff-media.c` maps PCM to U8, S16LE, S24LE or S32LE by block align
  (`G/gst-plugins-base/gst-libs/gst/riff/riff-media.c:1243-1303`), IEEE float to F32LE or F64LE (`:1327-1362`), and
  the EXTENSIBLE PCM and float sub-formats to the same raw audio (`:1620-1709`). It carries no `GSTREAMER_LITE`
  change (grep).
- **AIFF needs `COMM` first, and AIFC has no name.** A `.aif` or `.aiff` name gives `audio/x-aiff` with no byte check
  (`ji/MediaUtils.java:201-203`, `jm/locator/Locator.java:424-437`). By bytes, only `FORM....AIFF` with `COMM` at
  offset 12 is recognised (`ji/MediaUtils.java:129-141`), so a `FORM....AIFC` file, or an AIFF whose first chunk is
  not `COMM`, gets "Unrecognized file signature!". `.aifc` is not in the extension map (`ji/MediaUtils.java:190-227`).
- **The native AIFF parser.** aiffparse accepts `FORM` with `AIFF` or `AIFC`
  (`G/gst-plugins-bad/gst/aiff/aiffparse.c:233-241`) and decodes the uncompressed AIFC types `NONE`, `sowt`,
  `fl32`/`FL32` and `fl64` (`:735-760`); any other compression fails with "Unsupported compression in AIFC"
  (`:777-784`). It outputs S8, S16, S24 and S32 in either byte order, F32BE and F64BE (`:97-103`).
- **Channels.** On macOS and Linux the chain passes through audiopanorama, which takes one or two channels
  (`G/gst-plugins-good/gst/audiofx/audiopanorama.c:87-94`,
  `N/jfxmedia/platform/gstreamer/GstPipelineFactory.cpp:948-959`).
- **Nothing is measured.** The only WAV the tests play is 16-bit mono at 8 kHz (`mt/SineWav.java:72-79`,
  `mt/MediaPlaybackTest.java:175-177`); the other, 8-bit mono, is never played (`mt/TinyWav.java:36-40`). No test
  uses AIFF. `TinyWav` documents the 22-byte rule (`mt/TinyWav.java:42-48`), and no test checks the sniffer's
  rejection messages (grep of `modules/javafx.media/src/test`).
- **Documentation and WebView.** The public format table lists AIFF (`.aif`, `.aiff`) and WAV (`.wav`) with PCM
  (`modules/javafx.media/src/main/java/javafx/scene/media/package-info.java:83-109`). WebView offers as playable the
  types `MediaManager.getSupportedContentTypes()` lists
  (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/prism/PrismGraphicsManager.java:153-155`), and its
  `canPlayType` follows that list
  (`modules/javafx.web/src/main/native/Source/WebCore/platform/graphics/java/MediaPlayerPrivateJava.cpp:225-228`).

## Constraints
- **Exact where it plays today.** Every WAV and AIFF that plays today keeps its content type, platform, pipeline and
  decoded output, bit for bit.
- **Native changes.** If the change touches gstreamer-lite or `GstPipelineFactory.cpp`, it lands after US-040 and
  keeps US-036's slice-0 scenarios valid; the content of its "unsupported content" scenario stays unsupported
  (`backlog/US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md:96-102`). gstreamer-lite's Windows `.def`
  only grows by appending (`backlog/README.md`, US-032 row).
- **Licences.** Third-party Java code shipped in a jar is under a permissive licence (MIT, BSD, zlib, CC0, BSL-1.0 or
  similar); otherwise the code is written in-house from the specification. Native code keeps the GPLv2-compatible
  rule (`.claude/skills/jfx-rust-port/SKILL.md:186-191`; port rule P8, `backlog/README.md`, "Port rules"). Every notice
  ships through US-075.
- **Java.** Java 27 final features only (no preview, no incubator, no `var`). Production Java files are at most 900
  lines (`wc -l`); tests and shims are exempt. Comments only for a non-obvious "why". GPLv2+CPE headers.
- **Fixtures** are generated by the tests or by a command recorded next to them; nothing is downloaded
  (`backlog/README.md`, "Port rules").
- **Tests.** Agents never run `USE_ROBOT` or `tests/system` runs. Robot tests, listening checks and runs on real
  output devices are the owner's.

## Out of scope
- Compressed encodings: A-law, µ-law, IMA ADPCM and MS ADPCM are US-098; MP3 inside WAV and the rest stay unsupported.
- Other PCM containers, such as AU, CAF and Wave64.

## Acceptance criteria
1. **Baseline first.** Before any change, generated files are played on Windows, macOS and Linux through
   `MediaPlayer` and `AudioClip`, and the outcome of each is recorded here per OS and API. The set: WAV PCM at 8,
   16, 24 and 32 bits and IEEE float at 32 and 64 bits; one, two and six channels; each with a plain format tag and as
   EXTENSIBLE; with `fmt ` first and after a `JUNK` and a `bext` chunk; as RIFF, RF64 and BW64. AIFF at 8, 16, 24 and
   32 bits, and AIFC `NONE`, `sowt`, `fl32` and `fl64`, each named `.aif`, named `.aifc` and with no extension, and
   with `COMM` first and after another chunk.
2. **Every form plays.** After the change, every one- and two-channel file of that set plays to its end on all three
   OSes, through both APIs, with the right duration.
3. **Six channels.** On each OS where a six-channel file with a plain tag and `fmt ` first played in the baseline,
   every six-channel form plays. On any other OS they fail as criterion 6 says, and the message names the channel
   count.
4. **Same samples in any form.** A file in a form that did not play before decodes, sample for sample, to what the
   same audio decodes to in a form that played: plain tag, `fmt ` first, RIFF; AIFF for AIFC.
5. **Unchanged where it played.** Every file that played in the baseline keeps its content type and decodes bit for bit
   as before, shown against a capture made before the change (`.claude/skills/jfx-ffm-testing/SKILL.md:268-286`,
   pattern 7).
6. **Undecodable encodings fail early.** A WAV or AIFC whose encoding nothing in the fork decodes fails where a
   compressed WAV with `fmt ` first fails today, wherever its `fmt ` or `COMM` chunk sits: when the `Media` or
   `AudioClip` is constructed, with `MEDIA_UNSUPPORTED` and a message naming the encoding (US-096's rules once it has
   landed). A header that cannot be parsed fails with a `MediaException` naming the fault; nothing hangs or crashes.
7. **Same on every target.** Anything decoded in Java gives identical output on the five CI targets.
8. **Documented, and WebView.** The public format table lists AIFC with `.aifc`, and RF64 and BW64 with WAV.
   `TinyWav`'s javadoc states the rule the detection follows. Each content type added to
   `MediaManager.getSupportedContentTypes()` is listed here, and a test checks WebView's `canPlayType` for it;
   `audio/x-wav` and `audio/x-aiff` answer as before.
9. **Tests.** A detection test covers every form of criterion 1 and every rejection message. The playback tests run
   wherever the module's tests find an audio output (`mt/AudioOutput.java:65-68`).
10. **Nothing else broken.** The media module's tests pass with the counts of a run before the change, plus the new
    tests. All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `media-format-reviewer` signed off, and by `ffm-reviewer` too if native code changed.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- The owner, 2026-11-07: "Right now, the media module is extremely limited in what media types it supports, both
  native (.wav) and pure java (.cdg + .mp3)."
- Related: US-096 (the error a refused encoding gives); US-098 (A-law, µ-law and ADPCM in the forms this story
  opens); US-036 (its slice-0 WAV scenario).
