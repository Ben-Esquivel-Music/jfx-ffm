# US-096 — Tell applications why media won't play, and keep AudioClip working after a failed play

**Status:** 📋 Ready (filed 2026-11-07 from the media-format survey) · **Epic:** Media formats ·
**Blocked by:** none · **Blocks:** US-100, US-101, US-102

## Story
As an application developer whose users open media their machine cannot play,
I want one error that says why, arriving where the API documents it, and no failed `AudioClip` play to silence
other sounds,
so that I can tell users what is missing and the application keeps its sounds.

## Problem
Paths starting `sm/` are under `modules/javafx.media/src/main/java/javafx/scene/media/`, `ji/` under
`modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/`, `jm/` under
`modules/javafx.media/src/main/java/com/sun/media/jfxmedia/`, `mt/` under
`modules/javafx.media/src/test/java/test/com/sun/media/jfxmediaimpl/`, and `N/` under
`modules/javafx.media/src/main/native/`. Nothing below was run; the chains were traced in the code.
- **Two errors for one cause.** MP3 on Linux without a usable ffmpeg: `new Media` succeeds, because GStreamer claims
  `audio/mpeg` from a fixed list (`ji/platform/gstreamer/GSTPlatform.java:47-57`). Creating the player builds the
  pipeline, and the missing `avaudiodecoder` returns `ERROR_MEDIA_AUDIO_FORMAT_UNSUPPORTED`
  (`N/jfxmedia/platform/gstreamer/GstPipelineFactory.cpp:585-586`, `:917-919`). `GSTMedia` posts it
  (`ji/platform/gstreamer/GSTMedia.java:85-90`) to the listener the `MediaPlayer` registered
  (`sm/MediaPlayer.java:409-410`), which sets `Media.error` to `MEDIA_UNSUPPORTED` later, through
  `Platform.runLater` (`sm/MediaPlayer.java:1736-1746`). Meanwhile `GSTPlatform` swallows the failure and returns
  null (`ji/platform/gstreamer/GSTPlatform.java:128-138`), `NativeMediaManager` throws "Could not create player!"
  with no error code (`ji/NativeMediaManager.java:294-297`), and the `MediaPlayer` constructor rethrows it as
  `MediaException.Type.UNKNOWN` (`sm/MediaPlayer.java:423-424`, `sm/MediaException.java:132-147`).
- **macOS reports the same gap at once.** When `jfxmedia_avf` fails to load, no platform claims MP3
  (`ji/platform/osx/OSXPlatform.java:83-91`), so `new Media` throws `MEDIA_UNSUPPORTED`, "media type not supported"
  (`jm/locator/Locator.java:473-474`, `sm/Media.java:409-410`).
- **No audio output device.** The sink fails to open while the player is created, `GSTPlatform` returns null, and
  the `MediaPlayer` constructor throws the same `UNKNOWN` "Could not create player!" (`mt/AudioOutput.java:48-54`).
  The Windows and macOS CI runners have no device (`mt/AudioOutput.java:65-68`).
- **Messages name an error code, not a cause.** The posted message is the `MediaError` description prefixed with the
  posting object (`ji/MediaUtils.java:350`, `sm/MediaException.java:154-161`), and `description()` falls back to the
  constant's name (`jm/MediaError.java:194-207`), which is what it returns today, because the bundle lookup at
  `jm/MediaError.java:173` finds nothing (`backlog/US-074-remove-the-last-of-jni-from-the-fork.md:64-65`).
- **Errors reach every player.** `MediaUtils.nativeError` hands each error to every registered listener
  (`ji/MediaUtils.java:344-354`). Every `MediaPlayer` registers one for its lifetime (`sm/MediaPlayer.java:409-410`,
  `jm/MediaManager.java:129-134`), and its handler does not look at the source (`sm/MediaPlayer.java:2621-2627`). So
  one failed player creation sets the error of every live `MediaPlayer` that has none yet, and of its `Media`
  (inferred).
- **A fallback is not silent.** `PlatformManager` moves to the next platform that claims the type when one returns
  null (`ji/platform/PlatformManager.java:226-240`), but the failed platform has already posted its error, so a later
  platform that succeeds leaves that error set (inferred). No two platforms claim the same type today
  (`ji/platform/gstreamer/GSTPlatform.java:47-65`, `ji/platform/osx/OSXPlatform.java:46-54`); `JavaPlatform`
  claims none, as it keeps the empty default (`ji/platform/Platform.java:78-80`,
  `ji/platform/java/JavaPlatform.java:51-76`). US-101 adds the first fallback.
- **One failed clip play stops every later clip.** One scheduler thread runs every `AudioClip` play
  (`ji/NativeMediaAudioClipPlayer.java:80-97`). It takes a voice slot (`:111`, `:185-215`), then calls `play()` with
  no catch (`:99-116`). `play()` creates a player through `MediaManager.getPlayer` (`:312-321`), and `MediaException`
  is a `RuntimeException` (`jm/MediaException.java:31`), so one failure ends the thread for the life of the JVM and
  keeps its slot. Plays queued after it never run. The clip's `isPlaying()` stays true, because its play count drops
  only when a play finishes (`ji/NativeMediaAudioClip.java:106-108`, `:121-125`, `:138-140`), and every `stop()` then
  waits five seconds for the dead thread (`ji/NativeMediaAudioClipPlayer.java:218-235`). MP3 on Linux without a
  usable ffmpeg and a machine with no audio output device both trigger it.
- **Clip errors that arrive later are only logged** (`ji/NativeMediaAudioClipPlayer.java:427-433`).
- **Documented contracts.** `Media` throws for problems it can detect at once, such as an unsupported format, when
  the protocol does not block, and sets `error` otherwise (`sm/Media.java:336-350`). `MediaPlayer` throws for errors
  in its constructor and sets `error` for later ones (`sm/MediaPlayer.java:381-387`). `AudioClip` throws from its
  constructor (`sm/AudioClip.java:77`), and its plays are "fire and forget" (`sm/AudioClip.java:43-45`).
  `MEDIA_UNSUPPORTED` means "this media type is not supported by this platform" (`sm/MediaException.java:67-70`).

## Constraints
- **Playback that works stays as it is.** Media that plays today keeps its platform, pipeline and output. Only
  failures change.
- **No new public API.** No public type or member is added or removed. The types, messages and arrival points of
  errors change as listed here, with the javadoc that documents them; the README allows logic changes inside
  javafx.media (`README.md:20-21`).
- **Native changes.** If the change touches gstreamer-lite or `GstPipelineFactory.cpp`, it lands after US-040 and
  keeps US-036's slice-0 scenarios valid, among them "unsupported content" and "PAUSED failing without an audio
  device" (`backlog/US-036-port-the-jfxmedia-gstreamer-pipeline-core-to-rust.md:96-102`). Native code keeps the
  fork's FFM rules (`.claude/skills/jni-to-ffm-migration/SKILL.md:148`, §2).
- **Java.** Java 27 final features only (no preview, no incubator, no `var`). Production Java files are at most 900
  lines (`wc -l`); files already over, such as `sm/MediaPlayer.java` (2,863) and `ji/NativeMediaPlayer.java`
  (1,721), do not grow. Tests and shims are exempt. Comments only for a non-obvious "why". GPLv2+CPE headers.
- **Tests.** Agents never run `USE_ROBOT` or `tests/system` runs. Robot tests and runs on real output devices are the
  owner's.

## Out of scope
- New formats and decoders (US-097 to US-107). MP3 without ffmpeg is US-101; ffmpeg majors are US-099.
- A silent sink for clips when there is no output device (US-092, criterion 8).
- Causes other than an unrecognised format, a missing decoder and a missing audio output device keep today's types
  and messages; criterion 5 applies to them too.

## Acceptance criteria
1. **Reproduced first.** Tests show, on the tree before the change, three defects: `Media` and `MediaPlayer`
   reporting different types for one cause; one failed `AudioClip` play stopping every later play; and one media's
   error set on another live player. Each test fails there and passes after. The story records the host or CI leg and
   the cause each test used.
2. **One cause, one type.** An unrecognised format, or one no platform on this machine can decode, gives
   `MEDIA_UNSUPPORTED`. No audio output device gives `PLAYBACK_ERROR`, so the `Media` keeps a null error. No cause in
   this list gives `UNKNOWN`. The type is the same on Windows, macOS and Linux.
3. **A message that says why.** The message names the cause in words: the format, the decoder or library that is
   missing (on Linux, ffmpeg's libavcodec), or the missing audio output device.
4. **Where each error arrives.**
   - `Media`: a cause the content type decides on its own (an unrecognised format, or no decoder for it on this
     machine) is thrown from the constructor when the locator does not block and set on `error` when it does (it
     blocks for http and https, `jm/locator/Locator.java:231-234`), as `sm/Media.java:344-350` documents. A cause
     found later is set on `error` by the player that found it.
   - `MediaPlayer`: a cause found while the constructor runs is thrown from it; a later one is set on `error`. A
     cause of type `MEDIA_CORRUPTED`, `MEDIA_UNSUPPORTED`, `MEDIA_INACCESSIBLE` or `MEDIA_UNAVAILABLE` is also set on
     its `Media`'s `error`, with the same type and message, as `sm/MediaPlayer.java:1740-1745` does today.
   - `AudioClip`: a cause the content type decides on its own is thrown from the constructor. A play that fails later
     throws nothing to its caller and is logged with its cause.
5. **Only the media that failed.** An error is thrown to, or set on, only the `Media`, `MediaPlayer` or `AudioClip`
   whose media caused it. Every other live player and media keeps the error it had.
6. **A fallback that succeeds is silent.** When one platform cannot open a media and a later one plays it, nothing is
   thrown and no `error` property is set. A test shows it with a platform that fails ahead of one that succeeds.
7. **AudioClip survives a failed play,** whatever the cause. Later plays of that clip and of other clips are
   attempted and play where they can. The active voice count returns to what it was before the failed play. The
   clip's `isPlaying()` is false once none of its plays is running, and `stop()` returns as promptly as when no play
   has failed.
8. **WebView.** WebView offers as playable the types `MediaManager.getSupportedContentTypes()` lists
   (`modules/javafx.web/src/main/java/com/sun/javafx/webkit/prism/PrismGraphicsManager.java:153-155`), and its
   `canPlayType` follows that list
   (`modules/javafx.web/src/main/native/Source/WebCore/platform/graphics/java/MediaPlayerPrivateJava.cpp:225-228`).
   If the list changes on any machine, each change is recorded here and a test checks `canPlayType` for it.
9. **Documented.** The javadoc of `Media`, `MediaPlayer`, `AudioClip` and `MediaException` says where each error
   arrives and which type each cause gives, and passes doclint.
10. **Nothing else broken.** The media module's tests pass with the counts of a run before the change, plus the new
    tests. All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `media-format-reviewer` signed off, and by `ffm-reviewer` too if native code changed.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- First in the epic's order, because every new format makes these failures more common (`backlog/README.md`, "Media
  formats", Order).
- Related: US-092. It has no criterion for a play that fails. Its criterion 8 (a silent sink with no output device)
  removes the no-device cause for clips once it lands; every other cause stays with this story, and US-092's clip
  path keeps criterion 7 here. US-099 reports a missing or unsupported ffmpeg with this story's error. US-101's MP3
  fallback relies on criterion 6.
