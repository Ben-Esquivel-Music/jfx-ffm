# US-120 — Give each equalizer band its own native band, and none once it is removed

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15, while US-118 was fact-checked; read for
this story that day, nothing compiled or run · **Related:** US-118 owns the macOS native equalizer's lock, map and
move; this story owns the lifetime of the handle Java holds. US-040, from which both were filed.

## Story
As a JavaFX app developer who adds, moves and removes the bands of an `AudioEqualizer`,
I want each `EqualizerBand` to act on a native band of its own, and on none once I have removed it,
so that changing a band can neither reach a native band that is gone nor remove another band's.

## Problem
Java paths are under `modules/javafx.media/src/main/java` (`AudioEqualizer` and `EqualizerBand` in `javafx.scene.media`,
the others in `com.sun.media.jfxmediaimpl`), native ones under `modules/javafx.media/src/main/native/jfxmedia`, with
`Gst*` in `platform/gstreamer` and `AVF*` in `platform/osx/avf`. Of these US-040 changes only `ffi/jfxmedia_api.cpp`
and `jfxmedia_api.h`, cited as in its working tree (on `29f69dc205`). Each item was read, not compiled or run.
1. **The handle is a raw pointer that nothing checks.** Java keeps the `CEqualizerBand*` that `jfxm_eq_add_band`
   returns as a `long` (`ffi/jfxmedia_api.cpp:1151-1159`, `JfxMediaNative.java:2254-2261`,
   `NativeEqualizerBand.java:32`) and passes it back to each set (`JfxMediaNative.java:745-747`, `:2298-2360`);
   `jfxm_eq_band_*` make a virtual call through it with no check (`ffi/jfxmedia_api.cpp:1171-1205`,
   `PipelineManagement/AudioEqualizer.h:36-41`). On GStreamer it points at a value of the equalizer's
   `std::map<double, CGstEqualizerBand>` (`GstAudioEqualizer.h:76`, `GstAudioEqualizer.cpp:155-157`), on
   AVFoundation at a band made with `new` (`AVFAudioEqualizer.cpp:307`).
2. **A removed band keeps its handle.** `AudioEqualizer.Bands.onProposedChange` removes the native band of each band
   leaving the list by its centre frequency, ignores the result and clears no handle (`AudioEqualizer.java:211-218`);
   only the player's equalizer going clears handles (`:127-133`). The setters call through any handle that is not
   null (`EqualizerBand.java:118-124`, `:169-175`, `:221-226`). `RemoveBand` erases the map element (GStreamer,
   `GstAudioEqualizer.cpp:163-175`) or deletes the band (AVFoundation, `AVFAudioEqualizer.cpp:318-330`), so a set
   after `getBands().remove(band)` calls a member of a destroyed object: undefined behaviour, effect not checked.
3. **A moved band is removed under a key it does not have (GStreamer).** `SetCenterFrequency` sets only the band's
   `freq` (`GstAudioEqualizer.cpp:62-65`), and the map key stays the frequency it was added with, while Java removes
   by the current one (`AudioEqualizer.java:216`). `RemoveBand`'s `find` (`GstAudioEqualizer.cpp:165`) then misses
   the band. With no band at the new frequency it returns false, which Java ignores: the native band stays in the map
   and the element, and the removed band's sets still reach it. With a band there, that band is erased instead, and
   its `EqualizerBand`, still in the list, holds a handle to an erased element. AVFoundation re-keys a moved band
   (`AVFAudioEqualizer.cpp:332-345`), so there the removal finds it, if the re-lock of US-118 item 1 returns.
4. **Two bands on one frequency.** Nothing in Java keeps two bands of the list off one frequency
   (`AudioEqualizer.java:220-226`). On GStreamer `AddBand` returns NULL for a frequency in use
   (`GstAudioEqualizer.cpp:149-161`), Java's `addBand` returns null for it (`NativeAudioEqualizer.java:76-77`) and
   `setJfxBand(null)` stores the null (`EqualizerBand.java:84-88`): nothing throws or logs, though the comment at
   `AudioEqualizer.java:147-148` says it would throw an NPE. The second band's sets reach nothing, and its removal
   erases the first band's native band, whose `EqualizerBand` stays in the list with a handle to it. On AVFoundation
   `AddBand` sets the existing band's bandwidth and gain to the new values and returns that band
   (`AVFAudioEqualizer.cpp:308-315`): both `EqualizerBand`s hold one handle, the first one's native values are the
   second one's, and removing either deletes the band the other holds.
5. **Not a regression of the FFM port.** `AudioEqualizer.java` and `EqualizerBand.java` are upstream's (`git diff`
   from `868c4801ec` to `29f69dc205` is empty on them). At `939aa61ea` the equalizers were today's but for a copyright
   year and an include, and the JNI code reached the same object the same way: a `jlong` cast back and called through
   unchecked (`jni/NativeAudioEqualizer.cpp:57-91`, `jni/NativeEqualizerBand.cpp:35-77` there).

## Requirement
1. **A removed band acts on nothing.** Once an `EqualizerBand` has left its `AudioEqualizer`'s list, no set of its
   gain, bandwidth or centre frequency reaches a native band, on either backend (items 2, 3).
2. **No call reaches a band that is gone.** No call on an `EqualizerBand` in the list reaches a native band that no
   longer exists, on either backend (items 3, 4).
3. **A native band of its own, or none.** Each `EqualizerBand` in the list has a native band of its own or none,
   never one it shares with another `EqualizerBand`, and adding a band changes no other band's native band (item 4).
4. **Removal removes its own band only.** Removing an `EqualizerBand` removes its own native band, if it has one, and
   no other, also when its centre frequency changed after it was added (items 3, 4).

## Constraints
- **Public API unchanged:** no new public member, none removed; the Javadoc may state the behaviour.
- **Bands that stay behave as today:** a band in the list whose centre frequency no other band of the list shares
  gets the same native values, the same calls and the same sound.
- **ABI:** `JFXM_ABI_VERSION` stays `4u` (`jfxmedia_api.h:96`) and the 58 shipped `jfxm_*` exports stay, unless the
  requirement cannot be met without changing them. Then the version follows `FFM-ABI-CONTRACT.md` section 5.2
  (`:267-300`: an added symbol bumps it, as does a change a mismatched pair would not report; any other change is
  written in section 14.1), and the texts on the handle (`jfxmedia_api.h:475-485`, sections 3 and 11) follow the code.
- **A declared departure:** each observable difference from upstream OpenJFX is recorded in `FFM-ABI-CONTRACT.md`
  section 14.1 with its observable delta.
- **Seams:** new watches of the hooks build are seams in `ffi/JfxmTestHooks.h`, empty in the shipped build.
- **Who changes what:** only the Java 27 agent changes Java. Media C++ style, LF, no tabs, second copyright year 2026.

## Acceptance criteria
Each test makes its player in a child JVM (`ChildJvm`) as `SpectrumBandChangeTest` does, so a crash fails the test and
not the run, and like it is skipped by `AudioOutput.reportNoPlayer` without an audio output. GStreamer is verified on
Windows and in WSL Linux, AVFoundation on the macOS host, which US-040's hooks build (`jfxmedia_test`) refuses
(`modules/javafx.media/native/CMakeLists.txt:74-77`).
1. **A removed band.** A test removes a band from `getAudioEqualizer().getBands()`, then sets its gain, bandwidth and
   centre frequency. The child ends normally, the bands left read back their own values natively, and no set reached
   a native band: on GStreamer the hooks build counts `jfxm_eq_band_*` calls on a band that no longer exists and the
   test requires none; on macOS the results record how such a call is made observable. Before the change it fails.
   Windows, WSL Linux and the macOS host.
2. **A moved band (GStreamer).** A test changes a band's centre frequency, then removes the band: the native band
   count (`jfxm_eq_get_num_bands`) drops by one; with another band at the new frequency, that band keeps its native
   band and values, and no call reaches a band that is gone. Before the change it fails. Windows and WSL Linux.
3. **Two bands on one frequency.** A test adds a second band on a listed band's frequency with another bandwidth and
   gain, sets each one's gain and removes the second: the first reads back its own values natively throughout, and
   the native band count ends where it was before the second was added. A second test removes the first band, then
   sets the second's gain, bandwidth and frequency. In both, no call reaches a band that is gone. Before the change
   the first fails on both backends, the second on AVFoundation. GStreamer on Windows and WSL Linux; AVFoundation
   on the macOS host only.
4. **Mutant proof.** With each part of the change undone in a scratch copy, one at a time, a test of the requirement
   it serves fails where that requirement's problem occurs; the repository version passes. The results list the parts.
5. **Gate.** `mvn -pl modules/javafx.media -am test` passes on Windows and in WSL Linux, per-class counts as before
   plus the new tests, `SpectrumBandChangeTest` and `MediaPlaybackTest` unchanged, and the AVFoundation halves on the
   macOS host, with no `hs_err_pid*` under `modules/javafx.media/target`; platforms and counts recorded. CI is green.

## Out of scope
The macOS native equalizer's lock, map and move, a move onto another band's frequency included: US-118 (items 1, 2
and 4 to 7). The equalizer's audio behaviour: its filters and what the bands do to the sound.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 to 4.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- The owner approved filing this story on 2026-11-15.
