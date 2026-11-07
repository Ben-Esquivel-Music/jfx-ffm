# US-099 — Keep MP3, AAC and H.264 playing on Linux whatever ffmpeg is installed

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey) · **Epic:** Media formats ·
**Blocked by:** none · **Blocks:** none

## Story
As a developer shipping a JavaFX application to Linux users,
I want MP3, AAC and H.264 to play with whichever supported ffmpeg the user's distribution installs,
so that media does not stop playing because the user's ffmpeg differs from the build machine's.

## Problem
Paths starting `N/` are under `modules/javafx.media/src/main/native/`, `G/` under `N/gstreamer/gstreamer-lite/`,
and `AV/` under `N/gstreamer/plugins/av/`; `linux.cmake` is `modules/javafx.media/native/linux.cmake`. Nothing was
run: the failure below is traced in the code, and this story reproduces it first.
- **Linux decodes them through ffmpeg.** MP3 needs `avaudiodecoder`, and MP4 needs `avaudiodecoder` and
  `avvideodecoder` (`N/jfxmedia/platform/gstreamer/GstPipelineFactory.cpp:561-563`, `:585-586`). Both come from
  avplugin, which links the system ffmpeg's libavcodec and libavformat (`linux.cmake:389-410`).
- **One plugin, built for the build machine's ffmpeg.** The build makes one unversioned `libavplugin.so` against
  what pkg-config finds (`linux.cmake:389-410`), and skips it when libavcodec, libavformat or the libswscale
  headers are missing (`:69-72`, `:411-415`). The makefile it replaced could also build against a bundled
  `LIBAV_DIR`; only the system case was ported (`:40-43`). The SDK ships every library the media build produced
  (`sdk/pom.xml:203-208`).
- **The loader expects one plugin per major.** At run time gstreamer-lite opens `libavcodec.so.62` down to `.57`, then
  `libavcodec-ffmpeg.so.56`, then libav's 56 and 54 (`G/gstreamer/gst/gstregistry.c:145-149`, `:1487-1540`). With
  one found, it loads `libavplugin.so` if that file exists, and otherwise `libavplugin-<major>.so` or
  `libavplugin-ffmpeg-<major>.so` (`:1546-1554`); with none found, it loads no plugin (`:1564-1567`). `JfxMediaNative`
  lists the versioned names among the libraries to extract
  (`modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/JfxMediaNative.java:514-526`). Upstream OpenJFX
  ships one avplugin per libavcodec major (knowledge), and the Rust survey kept avplugin native as "a binding per
  libavcodec major" (`backlog/README.md`, avplugin verdict).
- **So another major stops playback (inferred).** On a host whose libavcodec major differs from the build machine's,
  the registry finds the host's libavcodec and loads the unversioned plugin, which needs a libavcodec the host lacks
  and fails to load. `avaudiodecoder` is then missing, and player creation fails with
  `ERROR_MEDIA_AUDIO_FORMAT_UNSUPPORTED` (`GstPipelineFactory.cpp:917-919`). A host with only libavcodec 63 or later
  loads no plugin at all, whatever the build used (`gstregistry.c:149`).
- **The plugin source.** It adapts to the libavcodec version at compile time (`AV/avdefines.h:31-67`), but it calls
  `av_init_packet` (`AV/audiodecoder.c:704`, `AV/videodecoder.c:757`), which `linux.cmake:51-57` records as surviving
  only up to libavcodec 62.
- **aarch64 takes its own path.** On Linux other than x86_64, the registry first preloads the plugin on a thread and
  skips it if that fails (`gstregistry.c:1555-1563`).
- **CI.** Both Linux legs build on Ubuntu 24.04, x64 and aarch64 (`.github/workflows/submit.yml:70`, `:213`), with
  the distribution's ffmpeg development packages (`:99`, `:242`), and check that `libavplugin.so` was built and does
  not link libswscale (`:182-209`, `:325-352`). Ubuntu 24.04 ships FFmpeg 6.1, libavcodec 60 (knowledge).
- **Documentation.** README says only that avplugin is built when the ffmpeg development packages are installed
  (`README.md:71-74`).

## Constraints
- **Native build and loading only.** The change is in the native build and in how the native layer finds and loads
  avplugin. It adds no Java decoder (MP3 without a usable ffmpeg is US-101), and nothing changes on Windows or macOS.
- **Decoding stays in the user's ffmpeg.** No ffmpeg library ships in the SDK; the untrusted parsing stays inside
  the system ffmpeg (`backlog/README.md`, avplugin verdict), and AAC and H.264 stay on the system's decoders because of
  their patents (knowledge).
- **The rest builds without ffmpeg.** A build host without ffmpeg's development packages still builds every other
  media library, as today (`linux.cmake:411-415`).
- **Native changes.** If the change touches gstreamer-lite or `GstPipelineFactory.cpp`, it lands after US-040 and
  keeps US-036's slice-0 scenarios valid, with the content of its "unsupported content" scenario still unsupported
  (`backlog/US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md:96-102`). Native code keeps the fork's FFM
  rules (`.claude/skills/jni-to-ffm-migration/SKILL.md:148`, §2).
- **Licences.** Any third-party code or headers the build adds keep the GPLv2-compatible rule
  (`.claude/skills/jfx-rust-port/SKILL.md:186-191`; port rule P8, `backlog/README.md`, "Port rules"), and their notices
  ship through US-075.
- **No installs by agents.** Hosts or container images with other libavcodec majors are set up by the owner; agents
  install no software.
- **Tests.** Agents never run `USE_ROBOT` or `tests/system` runs. Robot tests, listening checks and runs on real
  output devices are the owner's.

## Out of scope
- MP3 on a Linux host with no usable ffmpeg (US-101).
- H.265 and HLS. They go through the same plugin and gain from this story, but it does not gate on them.

## Acceptance criteria
1. **Reproduced first.** Before any change, an SDK built on a host with one libavcodec major is run on a Linux host or
   container with another, and MP3, AAC and H.264 playback is tried. The story records the distributions, both
   majors, the commit, what failed and the log lines that show why. If nothing fails, the story records that, sets
   its status to ✅ Done with that evidence, and closes.
2. **Every accepted major plays.** On Linux x64 and aarch64, MP3, AAC and H.264 play through `MediaPlayer`, and MP3
   and AAC through `AudioClip`, with each libavcodec major the loader accepts. Each major on each architecture has a
   recorded run: distribution, major, commit and outcome.
3. **The list.** The loader accepts no major that does not play. It accepts every FFmpeg major it probes today, 57 to
   62 (`gstregistry.c:149`), which includes the major of the CI runner images. For the libav and
   `libavcodec-ffmpeg` majors below 57 (`:145-147`), the story records whether each stays. The accepted majors are
   listed here and in README's paragraph on avplugin.
4. **A clear failure otherwise.** With no ffmpeg, or only a major outside the list, MP3, AAC and H.264 fail with the
   error US-096 gives a missing decoder, and the message names libavcodec and the major found, if any. This criterion
   is checked once US-096 has landed.
5. **CI checks every plugin.** Each Linux leg fails when a plugin the list promises is not built, as it fails today
   when `libavplugin.so` is missing, and every plugin built reaches the SDK.
6. **Unchanged where it works.** With the build machine's own major, MP3, AAC and H.264 decode bit for bit as today,
   shown against a capture made before the change (`.claude/skills/jfx-ffm-testing/SKILL.md:268-286`, pattern 7).
7. **Nothing else broken.** The media module's tests pass with the counts of a run before the change, plus the new
   tests. All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `media-format-reviewer` and by `ffm-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Related: US-096 (the error a missing or unsupported ffmpeg gives); US-101 (MP3 where no usable ffmpeg exists,
  which this story narrows to hosts with none or an unsupported major); US-040 and US-036 (sequencing of any change to
  gstreamer-lite).
