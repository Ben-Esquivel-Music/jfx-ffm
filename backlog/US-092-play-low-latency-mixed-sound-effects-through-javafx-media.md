# US-092 — Play low-latency mixed sound effects through javafx.media

**Status:** 📋 Ready (filed 2026-11-07 from the game-toolkit design review) · **Epic:** Media formats (game-audio
requirements) · **Blocked by:** none · **Blocks:** none

## Story
As a game developer, or the developer of any application that plays interface sounds,
I want short sounds to start at once, mix with many others and be controlled one voice at a time,
so that a game's sound keeps up with its frames without an audio stack of its own.

## Problem
Paths that start with `jfxmediaimpl/` or `jfxmedia/` are under
`modules/javafx.media/src/main/java/com/sun/media/`, and `AudioClip.java` is the public class in
`modules/javafx.media/src/main/java/javafx/scene/media/`. A bare `:N` is a line of
`jfxmediaimpl/NativeMediaAudioClipPlayer.java`.
- **The promise.** `AudioClip` plays "with minimal latency", several times at once (`AudioClip.java:40-46`). The
  internal interface promises decoding at load time and playback instances that "can be controlled individually"
  (`jfxmedia/AudioClip.java:36-46`).
- **A pipeline per play.** A clip keeps the file's bytes as read, not decoded audio
  (`jfxmediaimpl/NativeMediaAudioClip.java:59-61`, `jfxmedia/locator/Locator.java:266-291`). Each play creates a
  player (`:172`) and queues it to one scheduler thread (`:80-97`), which builds a full media player for it through
  `MediaManager.getPlayer` (`:318-322`), so every play decodes the file again (inferred).
- **Dropped plays.** A play with the same source URI and settings as one still waiting in that queue is dropped
  (`:170-182`, `:440-456`, `:533-540`).
- **16 voices.** At most 16 voices play at once (`:65`). A new voice evicts one of lower or equal priority, or is
  refused (`:185-215`).
- **Loops.** A loop restarts with `seek(0)` when the player reports the end (`:399-412`), so it is not gapless
  (inferred).
- **No PCM.** Creating a clip from raw samples throws `UnsupportedOperationException`
  (`jfxmediaimpl/NativeMediaAudioClip.java:76-78`), and so do segments, resampling, appending and flattening
  (`:81-103` of that file).
- **No voice of its own.** A public clip is made from a URL string only (`AudioClip.java:79`), and every `play`
  returns nothing (`:429`, `:440`, `:463` of that file), so a voice cannot be addressed. `stop()` stops "all playback
  of this AudioClip" (`AudioClip.java:479-481`): every voice of any clip from the same URI (`:117-135`). Its caller
  waits up to five seconds for the scheduler thread (`:218-235`).
- **Sequencing.** US-032 to US-036 port fxplugins and jfxmedia code to Rust, and US-040 fixes the jfxmedia pipeline
  (`backlog/README.md`, rows US-032 to US-036 and US-040).

## Constraints
- **Part of the media refactor** the README plans ("Short Term Project Goals": low-latency, mixed sound effects for
  games). It is sequenced with US-032 to US-036 and US-040 so that no Rust port lands on code this refactor removes.
- **Existing API.** The `javafx.scene.media` API keeps working, `AudioClip` included.
- **New API** goes in `javafx.media` only, and this story lists it when it lands.
- **Clip-only applications** do not pay the start-up cost of the video pipeline.
- **Native code** keeps the fork's FFM rules: plain C ABIs bound through FFM, no JNI
  (`.claude/skills/jni-to-ffm-migration/SKILL.md:148`, §2). Native access stays as javafx.media has it today
  (`.claude/skills/openjfx-conventions/SKILL.md:157-160`). Third-party code comes only under a GPLv2-compatible
  licence, with its notice in `modules/javafx.media/src/main/legal/`.
- **Java.** Java 27 final features only (no preview, no incubator such as the Vector API, no `var`). Production Java
  files are at most 900 lines (`wc -l`); tests and shims are exempt. Comments only for a non-obvious "why".
  GPLv2+CPE headers.
- **Tests.** Measurements on real output devices and listening checks are run by the owner. Agents never run
  `USE_ROBOT` or `tests/system` runs.

## Out of scope
- **Later, from the owner's list:** positional 3D audio, and playback scheduled at a set time. Each needs a story of
  its own, and nothing this story adds may rule them out.
- An audio module of the game toolkit's own.
- New media formats.

## Acceptance criteria
1. **Baseline first.** The time from a play call to audible output with today's `AudioClip` is measured on Windows,
   macOS and Linux by a recorded method, with the host, OS, output device and buffer settings of each run. The
   latency target of criterion 2 is set from it and recorded here before the new path is judged.
2. **Low-latency one-shots.** Starting a one-shot sound builds no playback pipeline, and its measured latency meets
   the recorded target on each platform.
3. **Many voices, a defined limit.** Many voices play mixed at once. The voice limit, and which voice is refused or
   replaced when it is reached, are documented and deterministic: the same calls give the same outcome.
4. **PCM clips.** A clip can be made from decoded PCM in memory. The sample formats, channel counts and rates it
   accepts are documented.
5. **One voice at a time.** Each voice has its own volume, pan and pitch/rate. One voice can be stopped without
   stopping any other, including other voices of the same clip.
6. **Gapless loops.** A looping voice repeats with no gap: after the clip's last sample comes its first, shown by a
   test.
7. **Never blocks.** Play calls, and the calls that stop or change one voice, never block and work from any thread,
   including a game's tick thread. A test shows them returning while audio output is stalled.
8. **Silent sink.** With no output device, or headless, voices play into a silent sink: the API behaves as it does
   with a device, and voices start, run for their length and finish.
9. **Clip-only start-up.** A test shows that an application that only plays clips never starts the video pipeline.
10. **Nothing else broken.** The media module's tests pass with the counts of a run before the change, plus the new
    tests. Each public type and member added to `javafx.media` is listed here with its `@since`, and its javadoc
    passes doclint. All five CI legs are green.

## Definition of Done
- All acceptance criteria met, with the evidence (logs, counts, goldens, tables) kept with the change.
- An independent review by `ffm-reviewer` signed off.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without explicit approval from
  the repository owner.**

## Notes
- Owner ruling, 2026-11-07: game audio is not a game-toolkit workstream. The game's audio needs are requirements on
  the planned javafx.media refactor, and this story holds them.
- Related: US-082 (its samples play sound with `AudioClip`, latency documented, until this story lands); US-096
  (today one failed `AudioClip` play stops every later clip, which criterion 8's silent sink also depends on); US-100
  (media decoded in Java shares this story's one audio output).
