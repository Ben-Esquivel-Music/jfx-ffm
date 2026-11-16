# US-040 — Fix lock, allocation and leak defects in the jfxmedia GStreamer pipeline

**Status:** ✅ Done (2026-11-15, uncommitted, PR pending; filed 2026-09-30 from the Rust-port survey of `jfxmedia`;
see "Done" below; follow-ups US-114 to US-120) · **Found:** 2026-09-30, Rust-port survey (media) ·
**Related:** US-035 and US-036 reproduce today's behaviour until this lands; if it lands first, their goldens encode
the fixed behaviour.

## Story
As a JavaFX app developer playing media,
I want the GStreamer playback core to share its stall and EOS state under one lock, to size the spectrum from the
message it receives, to never throw inside a GLib callback, and to release its source element on every failure path,
so that HLS stalls, spectrum band changes and failed media creation cannot race, lose bands, unwind through GLib or
leak.

## Problem
Paths are relative to `modules/javafx.media/src/main/native/jfxmedia/platform/gstreamer`.
1. **Mixed lock discipline.**
   - `m_bLastProgressValueEOS` is written by the bus handler without `m_StallLock`
     (`GstAudioPlaybackPipeline.cpp:1473`).
   - It is read and written under `m_StallLock` elsewhere (`:2049-2056`, `:2125-2131`).
   - It is read after `m_StateLock->Exit()` with no lock at all (`:1624-1631`).
   - `m_bHLSPBFull` follows the same pattern (`:1489`, `:1494`, `:2113-2116`, `:1628`).
   - A data race on a plain `gboolean` is undefined behaviour in C++.
2. **Spectrum lists indexed by the Java-set band count.** The bus handler indexes the magnitude and phase lists of
   the `spectrum` message by the band count Java last set (`:1512-1526`). If the count grows while a message is in
   flight, the handler reads past the message's lists: a GLib critical, and zero bands delivered.
3. **A throwing allocation inside a GLib callback.** `new float[]` runs inside the GLib bus handler (`:1516-1517`).
   A `std::bad_alloc` there would unwind through GLib frames. The header's list of `bad_alloc` escapes
   (`jfxmedia_api.h:34-39`) does not mention it.
4. **The source element leaks on three failure returns.** A comment in `CGstPipelineFactory::CreateSourceElement`
   (`GstPipelineFactory.cpp:284-291`) records that the three failure returns after it unref nothing:
   - `:297` (`gst_bin_new` failed) abandons `javaSource`;
   - `:305` (no buffer element) abandons `javaSource` and the empty bin made at `:295`;
   - `:310` (link failed) abandons the bin, which took `javaSource` and the buffer element at `:307`.

   What each return abandons is still floating, so nothing else will release it: it leaks. The comment also records
   why an unref is now safe: `InitGstMedia` registers an owner slot on each stream-callback adapter across
   `CMediaManager::CreatePlayer` (`modules/javafx.media/src/main/native/jfxmedia/ffi/jfxmedia_api.cpp:308-310`).
   An adapter that the unref frees, through the `close-connection` closure notify, NULLs the local that points at
   it (`:301-303`), so `InitGstMedia`'s cleanup does not free it a second time.

## Proposed fix
1. Choose the owning lock for each field, `m_StallLock` presumably, and take it on every access. Record the chosen
   lock next to each field.
2. Bound the loop by `gst_value_list_get_size` of the message's lists. Deliver the bands the message actually
   carries.
3. Use `new (std::nothrow)`, or a buffer sized once per band-count change, and update the header's note.
4. On each of the three returns, release what it abandons with `gst_object_ref_sink()` and then `gst_object_unref()`,
   because each is still floating: `javaSource` at `:297`; `javaSource` and the empty bin at `:305`; the bin at
   `:310`, which then releases the two elements it holds. A plain unref would finalize a floating object, which a
   debug GLib reports as a critical
   (`modules/javafx.media/src/main/native/gstreamer/3rd_party/glib/gobject/gobject.c:1814-1829`). Read
   `InitGstMedia`'s adapter cleanup first (`jfxmedia_api.cpp:332-374`), as the comment asks.

## Acceptance criteria
- **Part 1:** the PR lists every access of both fields with the lock held there. A ThreadSanitizer run of the media
  tests in WSL is attempted and its result recorded.
- **Part 2:** a test raises the band count while audio plays with the spectrum on. It receives full band arrays with
  no GLib critical on stderr.
- **Part 3:** no throwing `new` remains inside a GLib or GStreamer callback in `jfxmedia` (`git grep` in the PR).
- **Part 4:** a fault-injected failure of each of the three returns finalizes `javaSource`, and at `:305` and `:310`
  the bin, checked with `g_object_weak_ref`. Each release sinks the floating reference before it unrefs.
  `disposingAMediaClosesTheConnectionTheStreamCallbacksNeverClosed` still passes.
- The 33 module tests pass on Windows and WSL, and the export list and `JFXM_ABI_VERSION` are unchanged.

## Definition of Done
Merged and verified on Windows and WSL Ubuntu. `backlog/README.md` is updated. An upstream issue is drafted for
parts 1-3; part 4 was probably present before the fork's FFM port, and the PR checks.

## Done (2026-11-15, uncommitted)

Paths are relative to `modules/javafx.media`; native paths to `src/main/native`, and a file name alone is in
`src/main/native/jfxmedia/platform/gstreamer`, except `MediaPlayer.java`, which is in
`src/main/java/javafx/scene/media`. Line numbers are of the working tree on the base commit `29f69dc205` unless they
are marked as the base's.

### What changed

**Part 1, the stall flags** (`GstAudioPlaybackPipeline.cpp`, `GstAudioPlaybackPipeline.h`).
- Every access of `m_bLastProgressValueEOS` and `m_bHLSPBFull` outside the constructor holds `m_StallLock`, and
  `.h:173` and `.h:210` say so next to the fields. `m_StallLock` is never taken while `m_StateLock` is held.
- The unlocked read the story names (`SetPlayerState`, the base's `:1624-1631`) could not simply take the lock where
  it was, because it ran with `m_StateLock` held after all. Its only caller with `Stalled` is `UpdatePlayerState`,
  which holds `m_StateLock` across the call, and `CJfxCriticalSection` is recursive (a Win32 `CRITICAL_SECTION`;
  `PTHREAD_MUTEX_RECURSIVE` elsewhere, `jfxmedia/Utils/posix/posix_critical_section.cpp:41`), so the `Exit` before
  the read released nothing. Taking `m_StallLock` there would take the two locks in the opposite order to
  `HLSBufferResume`, which holds `m_StallLock` while `IsPlayerState` takes `m_StateLock`.
- So `UpdatePlayerState` reads both flags under `m_StallLock` (`:1712-1714`) before it takes `m_StateLock` (`:1719`)
  and passes the result to `SetPlayerState` as a new third parameter, `bool bResumeIfStalled` (`.h:123`, no
  default), which `:1667` tests. The three other callers (`:558`, `:1172`, `:1288`) pass `false`, and none of them
  passes `Stalled`.
- It is a discipline fix. Every access outside the constructor runs on the GLib main-loop thread: in `BusCallback`,
  the bus watch on the media manager's main context (`:148-154`), or in a function only `BusCallback` calls. The
  implementer and the independent review both traced this, and no race was reproduced. The resume decision reads the
  same values as before.

Every access in the tree (`git grep -n -E "m_bLastProgressValueEOS|m_bHLSPBFull"`, all in
`GstAudioPlaybackPipeline.cpp`; `BusCallback` also holds `m_DisposeLock`, `:1114` to `:1602`):

| Line | Function | Field | Access | Lock held |
| --- | --- | --- | --- | --- |
| :72 | constructor | `m_bHLSPBFull` | write | none, the object is not shared yet |
| :89 | constructor | `m_bLastProgressValueEOS` | write | none, the object is not shared yet |
| :1178 | `BusCallback`, EOS | `m_bLastProgressValueEOS` | write | `m_StallLock` (:1177-1179) |
| :1442 | `BusCallback`, state changed | `m_bLastProgressValueEOS` | read | `m_StallLock` (:1441-1443) |
| :1483 | `BusCallback`, buffering | `m_bLastProgressValueEOS` | write | `m_StallLock` (:1482-1484) |
| :1500 | `BusCallback`, HLS full | `m_bHLSPBFull` | write | `m_StallLock` (:1499-1501) |
| :1507 | `BusCallback`, HLS not full | `m_bHLSPBFull` | write | `m_StallLock` (:1506-1508) |
| :1713 | `UpdatePlayerState` | both | read | `m_StallLock` (:1712-1714), before `m_StateLock` (:1719) |
| :2103 | `BufferUnderrun` | `m_bLastProgressValueEOS` | read | `m_StallLock` (:2097-2106) |
| :2140 | `UpdateBufferPosition` | `m_bLastProgressValueEOS` | read | `m_StallLock` (:2138-2141) |
| :2166 | `HLSBufferStall` | both | read | `m_StallLock` (:2164-2167) |
| :2180 | `HLSBufferResume` | `m_bLastProgressValueEOS` | write | `m_StallLock` (:2178-2182) |
| :2181 | `HLSBufferResume` | `m_bLastProgressValueEOS` | read | `m_StallLock`; `IsPlayerState` takes `m_StateLock` |

**Part 2, the spectrum band count** (`GstAudioPlaybackPipeline.cpp:1527-1570`, `GstAudioSpectrum.cpp`,
`GstAudioSpectrum.h`).
- The bus handler takes the band count from the message: both the `magnitude` and the `phase` value non-NULL, then
  the smaller of their `gst_value_list_get_size` (`:1529-1537`). No list is read past its own size.
- `CGstAudioSpectrum` records `m_HolderBands`, the count the installed holder was made for, and `SetBands` swaps it
  with the holder in one `m_BandsLock` hold (`GstAudioSpectrum.cpp:111-115`). The new `UpdateBandsIfCurrent`
  (`:129-150`) reads holder and count and takes its reference in one hold (`:133-136`), writes only when the
  message's count is the holder's, and says whether the caller may send the event: also `true` with no holder
  installed, as before. The virtual `UpdateBands` delegates to it.
- A message computed for another count than the installed arrays' is dropped whole: nothing is written and no event
  is sent (`GstAudioPlaybackPipeline.cpp:1566-1570`). Band i of an N-band message covers another frequency range than
  band i of an M-band array, and `CFfiBandsHolder::UpdateBands` refuses a foreign size anyway.
- The Windows gstreamer-lite now exports `gst_value_list_get_size` (ordinal 156, the last line of
  `src/main/native/gstreamer/projects/win/gstreamer-lite.def`); Linux and macOS have no export list. There is no
  `GST_VALUE_HOLDS_LIST` check: its type symbol is not exported, and `gst_value_list_get_size` checks the type itself.
- What the old code did: after a raise it read past the end of the message's lists. On Linux, with the system GLib,
  every such lookup prints a GLib critical, which reaches stderr as a media-log line
  `Error (<time>): gst_value_list_get_value: assertion 'index < VALUE_LIST_SIZE (value)' failed`. On Windows the
  bundled GLib is built with `GSTREAMER_LITE`, under which `g_return_val_if_fail` returns without logging
  (`src/main/native/gstreamer/3rd_party/glib/glib/gmessages.h:697-727`): the lookup returns NULL silently and the
  band is delivered as 0.0. macOS builds the same GLib (not run there). After a lowering it delivered the first bands
  of a message computed for more bands.
- Residue, not fixed, and upstream has it too: the event does not say which arrays it belongs to, and Java reads the
  arrays of the current count, so one delivery per band-count change can find the new arrays unwritten (still the
  seed values) or, during their first write, partly written.

**Part 3, no throwing `new` in a callback.** 9 of the 14 plain `new` expressions outside `platform/osx` are now
`new (nothrow)`, each with a NULL check:

| Site | Function, where it runs | On NULL |
| --- | --- | --- |
| `GstAudioPlaybackPipeline.cpp:1543-1544` | `BusCallback`, main loop | both freed, error line, message dropped |
| `GstAudioPlaybackPipeline.cpp:1953` | `SendTrackEvent`, audio pad probes | error line (`:1963`), no track event |
| `GstAVPlaybackPipeline.cpp:898` | `VideoDecoderSrcProbe`, pad probe | error line (`:908`), no track event |
| `GstAVPlaybackPipeline.cpp:349` | `OnAppSinkHaveFrame`, appsink | as a failed `Init`: sample unreffed, `GST_FLOW_OK` |
| `GstAVPlaybackPipeline.cpp:425` | `OnAppSinkPreroll`, appsink | the same |
| `GstPipelineFactory.cpp:770` | `CreateAudioPipeline`, creation | `ERROR_MEMORY_ALLOCATION` |
| `GstPipelineFactory.cpp:885` | `CreateAVPipeline`, creation | `ERROR_MEMORY_ALLOCATION` |
| `jfxmedia/Utils/Logger.cpp:174` | `CLogger::CreateInstance` | `ERROR_MEMORY_ALLOCATION` |

- The check after the `new` in `CreateAudioPipeline` tested `ppPipeline` instead of `*ppPipeline`, so it could never
  fire; it now tests `*ppPipeline` (`GstPipelineFactory.cpp:771`).
- The 5 left run in no callback: `CJfxCriticalSection::Create` (`jfxmedia/Utils/posix/posix_critical_section.cpp:61`,
  `jfxmedia/Utils/win32/WinCriticalSection.cpp:41`; from the pipeline constructor, its `Init` and a static
  initialiser of `Logger.cpp`) and `CGstVideoFrame::ConvertToFormat` (`GstVideoFrame.cpp:493`, `:596`, `:710`;
  reached only from `jfxm_frame_convert`). The sweep, from the repository root:
  ```
  git grep -n -E '\bnew\b' -- modules/javafx.media/src/main/native/jfxmedia \
      ':!modules/javafx.media/src/main/native/jfxmedia/platform/osx' | grep -v -E 'new ?\(nothrow\)' \
      | grep -v -E ':[0-9]+:\s*(//|/\*|\* )'
  ```
  prints those 5 and lines that hold no `new` expression: the disabled macros of `jfxmedia/Common/VSMemory.h`,
  `#include <new>`, the signal names `"new-sample"` and `"new-preroll"`, and two end-of-line comments.
- `platform/osx` is outside the sweep: only macOS compiles it, and nothing on macOS was compiled or run. Its spectrum
  callback still allocates with a plain `new float[]` (`jfxmedia/platform/osx/avf/AVFAudioSpectrumUnit.cpp:255-256`,
  library `jfxmedia_avf`), and the audio tap's prepare path and init callback allocate with a plain `new` too
  (`AVFAudioEqualizer.cpp:86`, `AVFAudioProcessor.mm:152` there): US-115, which also owns the 8 other plain `new` of
  `platform/osx`.
- `new (nothrow)` covers the allocation, not a throwing constructor, `std::string` work or a container insert. The
  `bad_alloc` note at the top of `jfxmedia/jfxmedia_api.h` (`:34-52`) now lists every remaining escape outside
  `platform/osx`, among them the `std::string` work of the track events on a streaming thread and, on Windows, the
  container insert in `CGstAVPlaybackPipeline::LoadDecoder`. `FFM-ABI-CONTRACT.md:68-70` points at it instead of
  keeping its own list.

**Part 4, the failure returns of `CGstPipelineFactory::CreateSourceElement`** (`GstPipelineFactory.cpp`).
- `gst_bin_new` failed (`:296-302`): `javaSource` is sunk and unreffed (`:299-300`).
- No buffer element (`:312-319`): `javaSource` and the empty bin are each sunk and unreffed (`:314-317`).
- The link failed (`:323-329`): the bin is sunk and unreffed (`:326-327`). Since `gst_bin_add_many` (`:321`) it owns
  `javaSource` and the buffer element and releases them with itself; neither is unreffed directly.
- Each release is `g_object_ref_sink`, then `gst_object_unref`, because each object is still floating.
  `gst_object_ref_sink` is not exported by the Windows gstreamer-lite and only adds a NULL check (`:298`).
- Each stream-callbacks adapter is freed exactly once. Finalizing `javaSource` destroys its signal handlers, so the
  `close-connection` closure dies and its notify, `SourceCallbacksDestroyed`, deletes the adapter. The adapter's
  destructor NULLs `InitGstMedia`'s local through the owner slot (`jfxmedia/ffi/jfxmedia_api.cpp`), so
  `InitGstMedia`'s own delete is a no-op, and both callers of `CreateSourceElement` return the error at once. The
  ownership comments in `GstPipelineFactory.cpp`, `GstPipelineFactory.h`, `jfxmedia/ffi/jfxmedia_api.cpp` and
  `jfxmedia/ffi/FfiStreamCallbacks.h` now say so.
- Not covered here: when the HLS audio source's `CreateSourceElement` fails, the main source made before it is
  abandoned unfinalized (`InitGstMedia` frees its adapter, once), and every later failure return of
  `jfxm_media_create` abandons what it built: US-114.

**Added beyond the story, each with its reason.**
- **The hooks build `jfxmedia_test`.** Part 4's criterion, a fault-injected failure checked with `g_object_weak_ref`,
  cannot be met from outside the library, and Java cannot tell a stale drop of Part 2 from a missing message. So, in
  the shape US-068 gave `glass_test`:
  - `native/CMakeLists.txt:65-92`: the option `JFXM_TEST_HOOKS` (default OFF) and the cache path
    `JFXM_TEST_BIN_DIR`. Configure stops on macOS, with the path empty, or when it resolves to `BIN_DIR`.
  - `native/win.cmake:863-872`, `native/linux.cmake:477-489`: the target `jfxmedia_test`, built from `jfxmedia`'s
    own arguments (`JFXMEDIA_LIBRARY_ARGS`) plus `ffi/jfxmedia_test_hooks.cpp` and `JFXM_TEST_HOOKS=1`, output name
    `jfxmedia`, written to `JFXM_TEST_BIN_DIR` through a new `OUTPUT_DIR` keyword of `add_media_library`; on Linux
    its RUNPATH is `$ORIGIN:$ORIGIN/../bin`. `jfxmedia` depends on `jfxmedia_test`, so a build of the shipped target
    alone also rebuilds the hooks build and cannot leave an older one for the tests; with the option on, a compile
    error in the hooks build stops the shipped build too. `native/mac.cmake` is unchanged.
  - `pom.xml`: the `native-win` and `native-linux` profiles always pass `-DJFXM_TEST_HOOKS=ON` and
    `-DJFXM_TEST_BIN_DIR=${project.build.directory}/native/test-bin`. Surefire passes that directory as
    `-Djfx.media.nativeTestBinDir` and keeps it off `java.library.path`: only the child JVMs of the hook tests load
    the hooks build. It never ships: the SDK copies `target/native/bin` only. Cost: `jfxmedia` is compiled twice.
  - `ffi/jfxmedia_test_hooks.h` and `ffi/jfxmedia_test_hooks.cpp` (new): three exports, `jfxm_test_reset`,
    `jfxm_test_arm_source_fault` and `jfxm_test_counter`, declared only when `JFXM_TEST_HOOKS` is 1; three fault
    points, which fail `gst_bin_new`, the buffer element or the link in `CreateSourceElement`; 12 counters (source and
    bin watched, finalized and finalized while floating; adapters constructed and destroyed; faults fired; spectrum
    messages dropped as stale, events sent, band sets written), kept with GLib atomics and `g_object_weak_ref`.
  - `ffi/JfxmTestHooks.h` (new): the seams production code calls (`GstPipelineFactory.cpp:234`, `:295`, `:303`,
    `:305`, `:323`; `jfxmedia/ffi/FfiStreamCallbacks.cpp:49`, `:60`; `GstAudioPlaybackPipeline.cpp:1568`, `:1572`;
    `jfxmedia/ffi/FfiBandsHolder.cpp:75`). In the shipped build they are empty `static inline` functions, so no
    build of the shipped library exports one: a Linux Debug build exported 8 weak seam symbols with plain `inline`, and
    0 with `static inline`. The Linux hooks library exports the 8 seams (default visibility), the Windows one does not.
  - The Windows `glib-lite.def` exports `g_object_weak_ref` (ordinal 552) for the hooks build, so the shipped
    `glib-lite.dll` has one more export.
- **The vendored spectrum element**
  (`src/main/native/gstreamer/gstreamer-lite/gst-plugins-good/gst/spectrum/gstspectrum.c`): the same user action as
  Part 2, which the new test performs hundreds of times a run.
  - `gst_spectrum_transform_ip` read `bands`, `nfft` and `output_channels` before it took `spectrum->lock`, while
    the property setter changes them and frees the channel data under that lock. A band-count change landing in
    between made the loops index the new allocation with the old count: a heap overflow after a lowering. The three
    reads now sit under the lock (`:925-927`, the lock at `:919`) in a new `#ifdef GSTREAMER_LITE` block; the
    upstream `#else` is untouched. Found while building the Part 2 test, after one unexplained abort (exit 134) in 52
    direct Linux runs of its probe; that the abort was this race is not established.
  - `gst_spectrum_start` (`:414-425`) and `gst_spectrum_stop` (`:435-442`) reset the channel data under the lock
    `#if defined (GSTREAMER_LITE) && !defined (OSX)`, and the `#else` keeps the upstream statement. The setter frees
    the same data under that lock, so a reset outside it could free it twice: a state change to NULL on the bus
    thread's error path (`GstAudioPlaybackPipeline.cpp:1286`) together with a band-count change. Not on macOS:
    `AVFAudioSpectrumUnit` changes the element's state while it holds its band lock, and takes that lock again in
    the message callback that `transform_ip` calls under `spectrum->lock`. Only `native/mac.cmake` defines `OSX`;
    `gcc -E -P` of the start and stop span gives the base's text with `-DGSTREAMER_LITE -DOSX` and with no define,
    and differs by the four lock lines with `-DGSTREAMER_LITE` alone. That unit serialises its own property sets and
    state changes with its band lock, so on macOS a property set cannot meet a stop of its element; a reset against
    a `transform_ip` on the audio tap stays unprotected (older than this story, not verified), and macOS's GStreamer
    pipeline keeps the upstream reset: US-115.
  - Left as upstream has it, and named in the upstream draft only: `gst_spectrum_setup` picks the sample reader from
    `multi-channel` once, a property jfxmedia never sets, and the setter writes `threshold`, `post-messages` and the
    two message flags without the lock (values only, no memory or size effect).
- **An error log line for a track that cannot be allocated** (`GstAudioPlaybackPipeline.cpp:1963`,
  `GstAVPlaybackPipeline.cpp:908`), so that a dropped track event is not silent (the independent review).
- **Documentation made true.**
  - `jfxmedia/jfxmedia_api.h`, the comment of `jfxm_spectrum_set_bands`: with the GStreamer pipeline a count above
    1024 installs the pair while the element keeps its count, so no event fires until a count the element accepts
    (`:524-529`); a count of 1 is accepted by the call but not usable with the GStreamer pipeline, whose transform
    length `2 * count - 2` is then 0 and which divides by it (`:529-531`; JavaFX never passes it, as
    `NativeAudioSpectrum.setBandCount` acts above 1 and `MediaPlayer` clamps to 2); and a new caller obligation:
    calls on one spectrum must not overlap (`:533-536`), because `SetBands` sets the element's count
    (`GstAudioSpectrum.cpp:105`) outside the hold in which it installs the pair (`:111-115`). `MediaPlayer` makes the
    calls one at a time under its `disposeLock` (`MediaPlayer.java:1822-1824`, `:2170-2173`). `JFXM_ABI_VERSION`
    stays `4u` (`:96`).
  - `FFM-ABI-CONTRACT.md`: the `bad_alloc` paragraph (`:68-70`), the release thread (`:697`), the copy of the
    header comment (`:708-733`), section 11 (`:762-786`: the writer holds no lock while it writes, `MediaPlayer`'s
    `disposeLock` orders the calls, the obligation and what depends on it) and three bullets of section 14.1
    (`:994-1027`: a NULL holder skips the write and the event still fires; the lines of the swap; the GStreamer path
    no longer fires `audio_spectrum` for a band count its element refuses). It no longer calls `CGstAudioSpectrum`
    lock-free.
  - `FFM-ABI-CONTRACT.md` section 14.2 (the vendored patch manifest) now lists `gstspectrum.c` and both `.def` files.
  - `FFM-STATUS.md:326-331` cites `UpdateBandsIfCurrent` and its current lines.
  - `src/main/java/com/sun/media/jfxmediaimpl/NativeAudioSpectrum.java`: the `Bands` Javadoc only (the writer holds
    no lock while it writes; C may release a pair on the GStreamer main-loop thread); 215 lines.
  - `jfxmedia/ffi/FfiBandsHolder.cpp:48` names the main-loop thread too, and the comment on the logger lock
    (`jfxmedia/Utils/Logger.cpp:44-48`) says that `CJfxCriticalSection::Create` throws rather than leaving it NULL.

### Behaviour changes a user can see
- A band count above 1024, which `MediaPlayer.setAudioSpectrumNumBands` passes on (it clamps only from below,
  `MediaPlayer.java:282`, `:1823`, `:2173`), now gets no spectrum events at all until a count the element accepts.
  Before, the listener was called every interval with the seeded values (magnitudes at
  `NativeAudioSpectrum.DEFAULT_THRESHOLD`, phases 0). Neither gave correct data. Both halves are read from the code
  (the fork at `29f69dc205`, the JNI code at `939aa61ead`), not run. US-117 bounds the count: a count above 1024 is
  to behave as 1024.
- After a band-count change, messages computed for the old count are no longer delivered. Before, after a raise they
  arrived with the bands past the message's end at 0.0 (with GLib criticals on Linux), and after a lowering as the
  first bands of a spectrum of more bands.
- Under memory exhaustion a spectrum message, a track event or a video frame is dropped, the first two with an error
  log line, where a `std::bad_alloc` used to unwind through GLib.
- With a constant band count every message is written and sent as before, and the stall and resume decisions are
  unchanged. Builds: `jfxmedia` is compiled twice on Windows and Linux, the shipped `glib-lite.dll` and
  `gstreamer-lite.dll` have one more export each, and a tree whose natives were built before the option fails the 6
  hook tests under `-DskipNative=true`, with a message that asks for a rebuild.

### Tests
8 new tests in 3 classes, in `src/test/java/test/com/sun/media/jfxmediaimpl`. Each runs a child JVM whose
`java.library.path` names only the directories it needs (for the hooks build its own directory, then
`target/native/bin`, which holds the gstreamer-lite and, on Windows, the glib-lite it loads), and asserts which file
`NativeLibLoader` opened, so no test can pass against the wrong library. In the final Windows gate every fault child
reported `target/native/test-bin/jfxmedia.dll`.
- `JfxmTestHooksExportTest` (2): `theShippedLibraryExportsNoTestHook` (the library in `target/native/bin` resolves
  none of the three `jfxm_test_*` names) and `theHooksBuildExportsEveryTestHook` (the hooks build resolves all three).
- `SourceElementReleaseTest` (4), against the hooks build, with a WAV served from a loopback HTTP server so that the
  progress-buffer path is taken: `aFailedBinCreationReleasesTheSourceElement`,
  `aMissingBufferElementReleasesTheSourceElementAndTheEmptyBin` and `aFailedLinkReleasesTheSourceElementAndTheBin`
  arm one fault each and assert the exact error, that the `javasource` and, where there is one, the bin are finalized
  exactly once and not while floating, that as many adapters are destroyed as were made, and that the callback
  registry balances; `withNoFaultTheSameMediaIsCreatedAndItsDisposeReleasesTheElements` is the control. The progress
  buffer is not watched, and an element released once too often is still finalized once (the class comment).
- `SpectrumBandChangeTest` (2) plays a generated tone in a child JVM and lowers and raises the band count for 400 ms
  a round, 5 rounds at least, ending on a raise. Each round must end with a delivery of full arrays of the new count
  whose strongest band is the tone's; no delivery may hold a magnitude of 0 dB or more, which is what the silent
  read past the end writes on Windows; and no GLib critical line may appear, which only Linux prints.
  `theShippedLibraryDeliversFullBandsWithoutACriticalWhenTheCountChanges` runs the shipped library, and
  `theHooksBuildDropsStaleMessagesAndSendsNoEventWithoutAWrite` the hooks build, which must also count at least 2
  stale drops and no more events than band sets written. As the bus thread nearly always wins the race where the
  audio output is paced, the probe holds it off with full collections while a lowering waits for the element's
  lock, and the child pins a stop-the-world `System.gc()`. Without an audio output both skip after checking the
  no-device path.

Helpers without tests: `ChildJvm`, `MediaTestHooks`, `JfxmTestHooksNative`, `JfxmediaLibraryProbe`,
`SourceElementReleaseProbe`, `SpectrumBandChangeProbe`. `NativeMediaManagerDegradationTest` calls
`ChildJvm.childClassPath()` instead of its own copy (+1 / -30). No fixture file is committed: `SineWav` writes the
tone to a temporary file and the fault probe serves `TinyWav`. `src/test/addExports` is unchanged.

**Mutants, first series** (on Windows, the first two and two of the release mutants also on Linux; before the
`gstspectrum.c` changes):

| Mutant | Result |
| --- | --- |
| band count from `GetBands()` again | 40 of 40 fail on each system: Windows on the 0 dB check, Linux on the critical |
| no `break` after the stale drop | the hooks test fails 20 of 20 on each system; the shipped test cannot see it |
| every message current | the hooks test fails: the stale case is not reached |
| the holder's count never set | both spectrum tests fail: no round gets its delivery |
| one sink removed, at each of the 4 releases | the matching release test fails: finalized while floating |
| one release removed, at each of the 4 | the matching release test fails: finalized 0 times |
| owner slots not registered | the 3 fault tests fail: heap corruption in the child (0xC0000374) |
| export test pointed at the hooks build | the export test fails |

**Mutants, last series** (Linux, after the `gstspectrum.c` changes; a temporary variant of `SpectrumBandChangeTest`
that repeats both methods 20 times, not in the tree). The control was 10 batches, 400 child runs (200 shipped, 200
hooks): 0 failures, errors, skips or aborts, no `hs_err` file, 0 critical lines, 0 deliveries with a band no message
carries, every child at least 5 rounds; the hooks runs dropped 11,825 to 22,987 stale messages each, and events
never exceeded band sets written.

| Mutant (20 shipped and 20 hooks runs unless stated) | Result |
| --- | --- |
| band count from `GetBands()` again (`GstAudioPlaybackPipeline.cpp:1536`) | caught 40 of 40, by the critical |
| no `break` after the stale drop (`:1569`) | the hooks test catches it 20 of 20; the shipped one passes |
| `bool current = true;` in `UpdateBandsIfCurrent` (`GstAudioSpectrum.cpp:145`) | the hooks test catches it 20 of 20 |
| the three `gstspectrum.c` reads moved back before the lock | survives 40 of 40 |
| `UpdateBandsIfCurrent` compares with `GetBands()`, not the holder's count | survives 40 of 40 |
| an extra unref of `javaSource` on the link-failure path | `SourceElementReleaseTest` passes 3 runs of 3 |
| an extra ref of the buffer element on that path | `SourceElementReleaseTest` passes (1 run) |

In plain words: no test pins the read before the lock in `gstspectrum.c`, the narrow window between the element's
property set and the holder swap that comparing with the holder's count closes, or a release made once too often
(same counters, no crash); and the progress buffer is not watched, so a leak of it passes. Not run, because no test
provokes either race: removing the `m_StallLock` hold in `UpdatePlayerState`, and removing the start and stop lock.
The four mutated native files and both test classes have not changed since the series; later changes touched
comments, Markdown and the interrupt path of `ChildJvm` only.

### Acceptance criteria
- **Part 1** (every access listed with its lock; a ThreadSanitizer run in WSL attempted and recorded): met. The table
  above lists every access, for the PR description to carry; the attempt is under "Reviews and analysis".
- **Part 2** (a test raises the band count while audio plays with the spectrum on, and receives full band arrays with
  no GLib critical on stderr): met by `SpectrumBandChangeTest` on Windows and Linux, with three differences from the
  wording. The critical exists only on Linux, so the test also asserts that no delivery holds 0 dB or more, which is
  what Windows silently delivers. On Linux the critical reaches stderr as a media-log `Error (` line, which the test
  matches beside GLib's own `-CRITICAL **` form. And "full band arrays" is asserted once per round, for a delivery of
  the new count, not for every delivery (the residue above).
- **Part 3** (no throwing `new` inside a GLib or GStreamer callback in `jfxmedia`, `git grep`): met for the code the
  sweep reads, `jfxmedia` without `platform/osx`: 5 plain `new` left, none in a callback. `platform/osx`, which only
  macOS compiles and whose callback sites are in `jfxmedia_avf`, is US-115. A `std::bad_alloc` can still leave the
  `std::string` work of a track event on a streaming thread; the header lists it.
- **Part 4** (a fault-injected failure of each return finalizes `javaSource`, and the bin at the second and third,
  checked with `g_object_weak_ref`; each release sinks first;
  `disposingAMediaClosesTheConnectionTheStreamCallbacksNeverClosed` still passes): met through the hooks build,
  which the criterion needs: nothing outside the library can fail those three calls or watch the elements.
  `SourceElementReleaseTest` checks each return, and `JfxMediaNativeTest` (23 of 23, that test among them) passed in
  both final gates.
- **"The 33 module tests pass on Windows and WSL, and the export list and `JFXM_ABI_VERSION` are unchanged"**: the 33
  pass, now as part of 41 (8 new), with 0 failures and the same 2 macOS-only skips on both systems. The shipped
  `jfxm_*` export list is unchanged: 58 names, on Windows the baseline's list (md5
  `0235e12ad8d97b0bb02393c4914b3460`), on Linux the baseline's `jfxm_` names. `JFXM_ABI_VERSION` is `4u`. Not
  unchanged elsewhere: the whole Linux `nm -D` list of `libjfxmedia.so` has 1302 names against 1303 (C++ names only:
  `UpdateBandsIfCurrent`, the new `SetPlayerState` signature and the nothrow `operator new[]` added; the old
  signature and three `libsupc++` symbols gone), the shipped `glib-lite.dll` and `gstreamer-lite.dll` have one
  export more each (by ordinal, `NONAME` in the `.def` files), and `jfxmedia.dll` imports gstreamer-lite's ordinal
  156.

### Gates
The final gate, 2026-11-15, times EST, on the base commit `29f69dc205` with the story's 39 changed and new paths (24
modified, 15 new, the three follow-up stories among them); Temurin 27+35 and Maven 3.10.0 on both systems. From the
repository root:
```
mvn -B -ntp -pl modules/javafx.media -am test -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=test/com/sun/media/**"
```

| | Windows 10 Pro (10.0.19045) | WSL Ubuntu |
| --- | --- | --- |
| Tree | this working tree, natives built incrementally | a fresh clone with the story applied, built from scratch |
| Time | 13:39:25-13:44:16, Maven 04:49 min | 13:50:42-13:54:06, Maven 03:20 min |
| Result | exit 0, BUILD SUCCESS: 41 run, 0 failures, 0 errors, 2 skipped | the same |
| Shipped `jfxmedia` | 58 exports, the baseline's list | 58 `jfxm_` names, the baseline's; 1302 names in all |
| Hooks build | 61 exports: the 58 and the 3 `jfxm_test_*` | 61 `jfxm_` names; 1313 in all, the 8 seams among them |
| `hs_err_pid*` | none under `modules/javafx.media` | none in the clone |
| Warnings | MSVC: 6 per build (recompiled units), none new | gcc: 4 per `jfxmedia` build, 2 in `gstspectrum.c` |

| Class | Tests (skipped), both systems | Baseline |
| --- | --- | --- |
| `HLSConnectionHolderTest` | 2 | 2 |
| `JfxMediaNativeTest` | 23 | 23 |
| `JfxmTestHooksExportTest` | 2 | new |
| `MediaPlaybackTest` | 2 | 2 |
| `NativeMediaManagerDegradationTest` | 3 | 3 |
| `NativeVideoBufferOwnershipTest` | 1 | 1 |
| `OSXLocatorStreamOwnershipTest` | 2 (2, macOS only) | 2 (2) |
| `SourceElementReleaseTest` | 4 | new |
| `SpectrumBandChangeTest` | 2 | new |
| Total | 41 (2) | 33 (2) |

- Checksums: the 39 paths matched the recorded pre-gate state before and after each gate (39 of 39 in this tree; 36
  of 36 under `modules/` and 3 of 3 under `backlog/` in the clone), and the base commit stayed `29f69dc205`.
  `modules/javafx.media/pom.xml` md5 `859e7cd7b51888f47160b30552a1cf8b`. On Windows `target/native/bin` holds the
  baseline's 8 file names. After the gates only Markdown changed (this story, `backlog/README.md`, US-114, US-115,
  US-117, US-118, US-119, US-120; in `FFM-ABI-CONTRACT.md` section 14.2 this story's manifest entry and the seven-file
  manifest's corrected figures and licence sentence; in `FFM-STATUS.md` section 1b the same figures and the sentences
  around them), and no build or test reads any of it: outside Markdown, this module's `FFM-ABI-CONTRACT.md` and
  `FFM-STATUS.md` are named in comments only.
- Windows recompiled 27 `jfxmedia` translation units, 13 for the shipped build and 14 for the hooks build, and both
  DLLs are newer than every changed native source; `gstspectrum.c` was not recompiled in that run (its object comes
  from an earlier gate of the same source, with 0 warnings). The MSVC warnings are `C4996` (5) and `C4244` (1); a
  full build, which compiles every unit, shows 8 per target, `C4101` and `C4267` in unchanged sources besides. The
  4 gcc warnings were all there at the base, whose fifth, a `-Wsign-compare` in the old spectrum loop, is gone, and
  the two `-Wsign-compare` in `gstspectrum.c` are the base's.
- Cost of the hooks build, Linux, one sample in two private trees (`cmake --build --target jfxmedia --parallel 4`):
  26 to 53 compile steps for `jfxmedia` (+27), 28.57 s to 29.82 s, a difference inside the noise. Windows was not
  measured on its own.
- Baseline, the same command at `29f69dc205` on 2026-11-14: Windows 18:56-19:00 (Maven 03:58 min) and WSL
  19:08-19:13 (Maven 04:22 min), both exit 0, 33 run, 0 failures, 0 errors, 2 skipped, in 6 classes; the same 58
  `jfxm_*` exports, `JFXM_ABI_VERSION` `4u`.

### Reviews and analysis
- **Independent review** of the native change (`ffm-reviewer`, read-only): mergeable, nothing that had to be fixed,
  and all five claims of the change hold. It found that the Part 1 race never existed (every access on the main
  loop), which Part 1 above states; an ownership comment that was still false, empty seams exported by a Linux Debug
  build and a silent drop of a track that cannot be allocated, all three fixed; and the always-on hooks build,
  accepted as for `glass_test`. Declined, with reasons: re-sorting the two `.def` files (appending keeps every ordinal
  and shows the next free one on the last line), and relabelling the stale count for a message without lists (the
  constructor turns both lists on).
- **Confirming review**, three `ffm-reviewer` lenses (native, Java tests, build and evidence): all three pass, nothing
  that had to be fixed. Its optional findings went into one more change, or into statements in this section: the
  band-set counter is bumped where the holder copies (`jfxmedia/ffi/FfiBandsHolder.cpp:75`), so that "events no more
  than written" also sees an event for a message the holder refused; the start and stop reset under the lock; the
  CMake dependency of `jfxmedia` on `jfxmedia_test`; the stop-the-world collector flags of the spectrum child; the
  renamed link-failure test and its class comment; the interrupt path and report decoding of `ChildJvm` and an
  IPv6-safe URI in the fault probe; the stale texts of the contract and the comments; the refused-count text.
  Declined, with reasons: a hook counter for drops of shorter messages and a pinned serial collector (the collector
  would change the measured timing, and the mutant that takes the band count from `GetBands()` again already fails
  on both systems under the default one), and a proxy guard for the fault probe's loopback request (the JDK exempts
  loopback by default).
- **Review of the last changes**, with a second reviewer told to refute each finding that had to be fixed: two false
  sentences in shipped text, both confirmed and corrected, and no behaviour defect. The contract said that
  `NativeAudioSpectrum` serialises `set_bands` calls; it takes no lock, and `MediaPlayer` makes the calls one at a
  time under its `disposeLock`. The `Bands` Javadoc and `jfxmedia/ffi/FfiBandsHolder.cpp:48` named a GStreamer
  streaming thread for the release; the band write runs in the bus handler, on the main-loop thread. Also taken:
  the NULL-holder bullet now says the write is skipped and the event still fires, `ChildJvm` waits a bounded time
  for a child it killed, and a contract bullet records the refused-count change.
- **ThreadSanitizer attempt** (WSL: Ubuntu 26.04, gcc 15.2, TSan v3; its summary checked against the logs by a
  second reviewer). `jfxmedia`, gstreamer-lite and `fxplugins` were instrumented and the whole media gate ran under
  TSan: 41 run, 0 failures, 2 skipped, exit 0; 1,137 reports in 37 files, all data races. None is at a line that
  touches either stall flag, and none names `HLSBufferStall`, `HLSBufferResume`, `BufferUnderrun`,
  `UpdatePlayerState` or `SetPlayerState`; whether those paths ran was not measured. The 79 reports in the story's
  spectrum code pair accesses under, or ordered by, GLib `GMutex` locks that TSan cannot see (`m_BandsLock`,
  `spectrum->lock`, the bus queue lock), so TSan neither confirms nor rules them out. With the base's
  `gstspectrum.c` it reported the read of `bands` before the lock once (the base's `:880` against `set_property` at
  `:350`); with the fix that pair appeared only with the read inside the lock, which by itself proves nothing.
  Sanitizer flags on the CMake command line have no effect (`native/linux.cmake:78-85` resets them), so they went
  in as compiler arguments in a second build tree, and no repository file changed. It ran on an earlier state of
  this change, before the start and stop lock and the counter move.

### Not verified, not done
- **macOS**: not compiled and not run. By reading only: `native/mac.cmake` is unchanged and the configure refuses
  the hooks build there; `jfxmedia/ffi/FfiBandsHolder.cpp` now includes `ffi/JfxmTestHooks.h`, which the macOS
  `jfxmedia` target finds on its include path; the start and stop reset takes the upstream branch (checked with
  `gcc -E` only). There the hook tests skip by an assumption that says so, and the spectrum test of the shipped
  library would play its WAV through GStreamer. Open on macOS: US-115.
- **Robot runs and `tests/system`**: not run; only the owner runs them, and nothing was played by hand. The one media
  playback test there, `test.com.sun.media.jfxmediaimpl.platform.osx.AVFVideoDisposeRaceTest`, runs on macOS only
  (it assumes `PlatformUtil.isMac()`, `AVFVideoDisposeRaceTest.java:130`): it plays a generated movie through
  AVFoundation thirty times and disposes the player each time with frames in flight, so it reaches none of the
  GStreamer changes on Windows or Linux. The owner's command for a full run, in the form of US-110
  (`-DskipTests=true` skips the tests of the modules `-am` builds; `tests/system` sets its own `skipTests` from
  `jfx.systemtests.skip`, which `-DFULL_TEST=true` turns off, `tests/system/pom.xml:257`, `:320`):
  `mvn -B -ntp -pl tests/system -am test -DskipTests=true -DFULL_TEST=true -DUSE_ROBOT=true`.
- **CI**: not run, as nothing is pushed. The confirming review reads `submit.yml`'s Windows and macOS runners as
  having no audio output, so the spectrum tests would skip there and Linux would carry Part 2; no run checked that.
- **Races no test provokes**: the read before the lock in `gstspectrum.c`, a start or stop reset against a band-count
  change, the window `UpdateBandsIfCurrent` closes and overlapping `set_bands` calls (unreachable through
  `MediaPlayer`); nor does any test see a release made once too often. Each rests on the code reading above; the
  gates show that the locks break nothing, not that the races are gone.
- **Reasoned only**: the out-of-memory branches (no test fails an allocation); both halves of a count above 1024;
  the cause of the one abort seen before the `gstspectrum.c` change, which is not established (that race is a
  candidate; none in the 400 control runs after the change); the interrupt path of `ChildJvm`; the fault probe
  under `java.net.preferIPv6Addresses=true`; a Windows Debug build; the system GLib of Linux refusing an
  out-of-range property as the vendored copy does.
- **Legal notices**: `src/main/legal/gstreamer.md:17-20` and `src/main/legal/glib.md:17-20` say the modified
  sources are in openjdk/jfx, from which this fork's media sources have differed since `a544256444`, and this story
  changes `gstspectrum.c` and two `.def` files more. No notice changed (`CheckLegalNotices` compares them byte for
  byte). The owner ruled on 2026-11-15 that it becomes a story: US-119.

### Follow-up
- **US-114**: release everything a failed GStreamer media creation has built: the failure returns of
  `jfxm_media_create` after the first `CreateSourceElement`, the `CPipelineOptions` it leaks, and the elements of a
  media disposed before its buffer's pad is added; to be verified with this story's hooks build.
- **US-115**: the macOS AVFoundation spectrum: the band count from `GetBands()`, throwing allocations in the audio
  tap's callbacks and the other plain `new` of `platform/osx`, the magnitudes passed as the phases, and the start and
  stop reset outside the lock on macOS; on the macOS host.
- **US-116**: the absolute build-tree `bin` directory in the RUNPATH of the shipped `libjfxmedia.so` (CMake's build
  RPATH), there at the base too.
- **US-117**: the spectrum band count: a count above 1024 is to behave as 1024, and `jfxm_spectrum_set_bands`
  is to install nothing below 2 or above 1024; on Windows and Linux.
- **US-118**: the macOS AVFoundation player: `mBandLock` re-locked by a band move, left held by a throw, not taken for
  band-state writes; a removal's NULL entry; a move's deleted band; `ConvertToFormat`'s kept buffer; on the macOS host.
- **US-119**: the GStreamer, GLib and WebKit notices, which send the reader to openjdk/jfx for sources this fork has
  modified (see "Legal notices" above).
- **US-120**: the equalizer bands' handles: a removed band keeps its raw `CEqualizerBand*`, removal goes by the
  current frequency, and two bands on one frequency; as upstream, on Windows, WSL Linux and the macOS host.
- **Upstream**: an issue is drafted for all four parts, plus notes on the vendored spectrum element (the read before
  the lock, the reader that is not re-picked, the unlocked start and stop reset, counts above 1024 and of 1). It is
  kept outside the repository, and the owner files it. Its master line numbers are from a fetch of 2026-11-14 and
  need checking again before filing, and whether current upstream GStreamer still has the element's defects was not
  checked. Part 1 is described there as inconsistent lock discipline, not as a race. Part 4 is in openjdk/jfx master
  (`GstPipelineFactory.cpp:322-337` there) and in the fork before the FFM port (`939aa61ead`, the parent of
  `a544256444`), so it is not a regression of the port.

### Definition of Done
- Verified on Windows and WSL Ubuntu: yes, the final gate above.
- `backlog/README.md` updated: yes; the row is in the done table, and US-114 to US-120 are in the open table.
- Upstream issue drafted: yes, for parts 1 to 4 and the element's notes; filing it is the owner's.
- Part 4 before the FFM port: checked; it was there (see "Follow-up").
- Merged: no. The commit, the PR, whose description can carry the Part 1 table and the Part 3 sweep, and a green
  `submit.yml` run are the owner's.
