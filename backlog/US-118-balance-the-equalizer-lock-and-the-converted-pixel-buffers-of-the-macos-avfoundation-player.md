# US-118 — Balance the equalizer lock and the converted pixel buffers of the macOS AVFoundation player

**Status:** 📋 Ready (filed 2026-11-15 from US-040) · **Found:** 2026-11-15: items 1 to 3 while US-115 was fact-checked,
re-read for this story that day; 4 to 7 while it was checked. Nothing on macOS was compiled or run: every item below is
from reading. · **Related:** US-115 owns the throwing allocations and the exceptions that leave `extern "C"` under
`platform/osx`; this story owns the lock and reference balance and the band state. US-040, from which both were filed.

## Story
As a JavaFX app developer who uses the audio equalizer or video on macOS,
I want the AVFoundation equalizer and frame conversion to leave each lock and pixel buffer as they found it,
so that changing a band cannot hang the app or stall the audio, and a disposed converted frame frees its buffer.

## Problem
Paths are relative to `modules/javafx.media/src/main/native/jfxmedia`. A bare line number is in
`platform/osx/avf/AVFAudioEqualizer.cpp` in items 1, 2 and 4 to 7, and in `platform/osx/CVVideoFrame.mm` in item 3.
US-040 leaves `platform/osx` unchanged; `ffi/jfxmedia_api.cpp`, `jfxmedia_api.h` and the Java files, named alone
(`AudioEqualizer.java` is `javafx.scene.media`'s), are cited as in US-040's working tree (uncommitted, on `29f69dc205`).
`GstAudioEqualizer.cpp` is in `platform/gstreamer`. Each item was read, not compiled or run.

1. **Moving a band locks `mBandLock` twice.** The mutex has default attributes (`pthread_mutex_init`, :267), so
   its type is `PTHREAD_MUTEX_DEFAULT`. `jfxm_eq_band_set_center_frequency` (`ffi/jfxmedia_api.cpp:1177-1181`)
   calls `SetCenterFrequency` (:68-72), the only caller of `MoveBand`; it passes the band's own frequency (:69), the
   key it sits under (:45, :307, :339-340, :70). `MoveBand` locks (:333) and, when the frequency changes, calls
   `RemoveBand(newFrequency)` (:335-338), which locks the same mutex on the same thread (:319). POSIX leaves a
   re-lock of a `PTHREAD_MUTEX_DEFAULT` mutex by its owner undefined and specifies a deadlock for
   `PTHREAD_MUTEX_NORMAL`; how macOS's default mutex behaves on such a re-lock was not checked. If the call returns,
   `lockBands` ignores its result (`AVFAudioEqualizer.h:154-156`): `RemoveBand`'s unlock (:325 or :328) can then
   release the lock while `MoveBand` still edits the map (:339-342) that the audio tap walks under it (:406-413, :450),
   and `MoveBand`'s unlock (:344) can unlock a mutex it no longer holds, also undefined by POSIX.
2. **A throw in a band function leaves `mBandLock` held.** `lockBands` and `unlockBands` are a plain
   `pthread_mutex_lock` and `pthread_mutex_unlock` (`AVFAudioEqualizer.h:154-160`), and nothing else takes or
   releases `mBandLock`. `AddBand` (:304-316) locks at :305 and unlocks at :314. Between them the map
   insert of :306, the plain `new AVFEqualizerBand` (:307), whose constructor can call `SetChannelCount` (:51), and
   `ResetBandParameters` (:312), which calls it on every band (:373), can throw: `SetChannelCount` allocates with a
   plain `new[]` (:86). `RemoveBand` (:318-330) and `MoveBand` (:332-345) unlock by hand too, around a map
   `operator[]` that inserts an absent key (:320, :336, :339) and `ResetBandParameters` (:324, :342). An exception
   leaves with the lock held and nothing releases it; if the process outlives the exception (US-115), each later
   call of the three on another thread, and each `ProcessBufferLists` of the audio tap (:406, from
   `platform/osx/avf/AVFAudioProcessor.mm:229`), waits on it, and one on the same thread re-locks it as in item 1.
3. **`ConvertToFormat` keeps the buffer it creates.** For a `YCbCr_422` frame converted to `BGRA_PRE` (:183),
   `ConvertToFormat` (:181-208), which `jfxm_frame_convert` calls (`ffi/jfxmedia_api.cpp:1103-1110`), creates
   `destPixelBuffer` (`CVPixelBufferCreate`, :185) and locks its base address with flags 0 (:187). On success it
   returns `new CVVideoFrame(destPixelBuffer, ...)` (:201): the constructor locks the buffer again, read-only
   (`PrepareChunky`, :105, :127), and retains it (:110); the frame's `Dispose`, run by its destructor (:113-116),
   unlocks it read-only and releases it, once each (:171-179). Nothing undoes the lock of :187, the only lock in the
   file taken with flags 0 (its only unlock passes `kCVPixelBufferLock_ReadOnly`, :174), or releases the creator's
   reference (Core Foundation's Create Rule, not checked for this story), so a disposed frame leaves its buffer
   locked and referenced. Every failure path keeps it too:
   - the lock of :187 fails: `NULL` (:207), with the creator's reference;
   - the conversion fails (:192): `NULL`, with that reference and the lock of :187;
   - the plain `new` of :201 or the constructor throws (:53, :68, :75, or :129, :138 in `PrepareChunky`): the
     exception leaves with both, and at :138 with the constructor's read-only lock of :127 as well; a constructor
     that throws runs no destructor, and `Dispose` acts only once :109 has run (:173).
4. **Band parameters change outside `mBandLock`.** `jfxm_eq_band_set_*` (`ffi/jfxmedia_api.cpp:1177-1205`) run
   `SetCenterFrequency` (:68-72), `SetBandwidth` and `SetGain` (`AVFAudioEqualizer.h:74-86`) on the Java caller's
   thread (`EqualizerBand.java:118-124`, `:169-175`, `:221-226`, `JfxMediaNative.java:2298-2360`); they recalculate
   unlocked (:70-71 after `MoveBand`'s unlock), writing `mBypass` (:195, :205) and five coefficients one by one, then
   dividing them in place (:145-189), while `ApplyFilter` reads (:225, :230, :247) and recalculates (:227) them under
   the lock. The tap can thus filter with a set partly old, partly new or undivided; to what effect was not checked.
   `SetEnabled` (:296-298) writes `mEnabled` unlocked, on the Java caller's thread too, while `RunFilter` reads it under
   the lock (:422), and `GetNumBands` (:300-302) reads the map's size unlocked while the band functions change it.
5. **The tap's prepare callback resets the bands outside `mBandLock`.** `PrepareAudioTap`, MediaToolbox's callback
   (`AVFAudioProcessor.mm:97`), sets the rate and channel count and runs `ResetBandParameters` unlocked (`:193-195`
   there): it erases map entries (:353), replaces histories (:373, :79-87), recalculates (:372, :374) and clears the
   filter buffers (:379-385), all of which the band functions change, and the process callback reads, under the lock
   (:312, :324, :342; :406-462). Nothing keeps it apart from a Java thread in those functions: both can then walk and
   erase one map and `delete[]` one history (:75-82; reasoned). Neither callback's thread, nor any overlap, was checked.
6. **Removing a frequency that holds no band leaves a NULL entry.** `RemoveBand`'s `operator[]` (:320) inserts the key,
   and `false` returns with it in place (:328-329); `GetNumBands` (:300-302), which Java's `addBand` checks
   (`NativeAudioEqualizer.java:69-72`), counts it until a `ResetBandParameters` erases it (:351-353). Java removes any
   frequency above 0 (`:80-83` there); GStreamer's `RemoveBand` inserts nothing (`GstAudioEqualizer.cpp:165`).
7. **Moving a band onto another band's frequency deletes that band.** If item 1's re-lock returns, `RemoveBand`
   (:338) deletes the band there (:322-323) and the moved band takes its key (:339-340); `GetNumBands` drops by one.
   Java keeps the deleted band's handle: nothing keeps two bands off one frequency (`EqualizerBand.java:118-124`,
   `AudioEqualizer.java:221`) or clears a handle before the player's equalizer goes (`AudioEqualizer.java:127-133`),
   and `jfxm_eq_band_*` use it unchecked (`ffi/jfxmedia_api.cpp:1171-1205`): its next set writes to a deleted object.
   On GStreamer the same calls delete nothing; a move only sets the band's `freq` (`GstAudioEqualizer.cpp:62-65`).

## Requirement
1. **No re-lock.** Moving a band's centre frequency never locks a mutex its own thread holds and never unlocks one
   it does not hold.
2. **Every exit releases the lock.** No path out of `AddBand`, `RemoveBand` or `MoveBand`, a thrown exception
   included, leaves `mBandLock` held.
3. **Balanced buffers.** Every pixel buffer `ConvertToFormat` creates is unlocked and released exactly as often as
   it is locked and retained, each unlock with the flags of the lock it undoes: on success once the frame is
   disposed, and on every failure, a thrown exception included.
4. **Band state changes under the tap's lock.** The band state `ProcessBufferLists` reads (the map, each band's
   state, the filter buffers, the rate and channel count, and the enabled flag) changes only under `mBandLock`, on every
   thread, and `GetNumBands` reads the map under it (items 4, 5).
5. **The map holds only bands.** No call leaves a map entry without a band: removing a frequency that holds none
   returns `false` and leaves `jfxm_eq_get_num_bands` as it was, as on GStreamer.
6. **A move deletes no band.** A move onto another band's frequency does what the same calls do on GStreamer
   (item 7): no band is deleted, the band count stays, and the other band's handle stays valid with its own values.

## Constraints
- **Output unchanged:** the same samples through the same bands, and converted frames of the same size and bytes.
- **The audio tap waits on nothing new:** no lock and no call that `ProcessAudioTap` does not make today.
- **ABI unchanged.** `JFXM_ABI_VERSION` stays `4u` (`jfxmedia_api.h:96`); no export is added, removed or renamed.
- **Only `platform/osx` and tests change**, in each file's media style; LF, no tabs, second copyright year 2026.

## Acceptance criteria
On the macOS host (the Mac mini); CI's macOS runners have no audio output (US-115's notes). So that a hang or a
crash fails a test and not the run, each observation runs in a child JVM, as in `MacFontReleaseTest`
(`modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/MacFontReleaseTest.java:42-46`).
1. **Moving a band returns.** A test moves the centre frequency of each band of an AVFoundation player's equalizer,
   before playback and while it plays a small, documented, license-clean fixture that AVFoundation decodes (US-115
   needs one too). Every call returns within a bound written in the test, and the band then answers its new
   frequency. Before the change the test fails; the results record how.
2. **A throw releases the lock.** With an allocation failure provoked in turn at each throwing call of Problem 2, a
   test asserts that afterwards an `AddBand` from another thread returns and spectrum events of a playing player
   keep arriving (the tap runs the spectrum after the equalizer: `AVFAudioProcessor.mm:228-240`), within a bound
   written in the test. How the failure is provoked within the constraints is recorded here; US-040's hooks build
   refuses macOS (`modules/javafx.media/native/CMakeLists.txt:75-77`). Before the change the test fails; the
   results record how, an exception that ends the child JVM included (US-115). An independent review lists every
   exit of the three functions with the state of the lock there.
3. **Converted buffers are freed.** A test converts frames an AVFoundation player delivers as `YCbCr_422` to
   `BGRA_PRE` (`jfxm_frame_convert`) in rounds and disposes each result; native memory does not grow with the
   conversions, measured as `MacFontReleaseTest` does (a retain count that rises with the calls, or heap blocks per
   call from `malloc_zone_statistics`: `src/shims/java/com/sun/javafx/font/MacFontProbeShim.java:148` of
   javafx.graphics), against a bound written in the test, with the figures before and after recorded here. Before
   the change the test fails. Which frames AVFoundation delivers as `YCbCr_422` was not checked; the results say
   how the test obtains them. Each failure path of Problem 3 that can be reached is provoked once and measured the
   same way, the results naming those reached (`CVVideoFrame.mm:53` is not if `k32BGRAPixelFormat`, :186, is
   `'BGRA'`, accepted at :33; not checked); an independent review counts the buffer's locks, unlocks, retains and
   releases on every path, reached or not, and finds them equal.
4. **Band state is written under the lock.** An independent review lists every reader and writer of the state of
   requirement 4, with its thread as far as the code shows it and whether `mBandLock` is held there, and finds
   requirement 4 met. No test provokes these races, which were never reproduced, so this criterion has no mutant.
5. **An absent frequency.** A test removes a frequency that holds no band from an AVFoundation player's equalizer
   (`OSXMediaPlayer.java:138`) and asserts `false` and an unchanged band count; before the change it rises by one.
6. **A move onto an occupied frequency.** A test moves a band of that equalizer onto another band's frequency, then
   sets and reads back the other band's bandwidth and gain through its handle and asserts an unchanged band count.
   Before the change the test fails; the results record how.
7. **Mutant proof.** Criteria 1 to 3, 5 and 6 each fail with their change undone in a scratch copy (the re-lock;
   the release on one exit of each band function; the unlock and release on each reached path of `ConvertToFormat`; the
   NULL entry; the deleted band); the repository passes.
8. **Gate.** `mvn -pl modules/javafx.media -am test` passes on the macOS host before and after, per-class counts as
   before plus the new tests, which run against the shipped media libraries; no run leaves an `hs_err_pid*` file.
   CI's two macOS jobs are green.

## Out of scope
Whether the allocations under `mBandLock` and in `ConvertToFormat` can throw, and the exceptions that leave
`extern "C"` under `platform/osx`: US-115 owns both; this story owns the lock and reference balance on every path
out. The spectrum unit's own `mBandLock` (US-115), the GStreamer equalizer and video frames. The lifetime of the
handle Java holds for a removed band, and two Java bands on one frequency, are US-120's.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here; an independent review repeated criteria 1 to 3, 5, 6.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- The owner approved filing this story on 2026-11-15.
