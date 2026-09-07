# javafx.media JNI → FFM: old → new entry-point coverage map

Branch `ffm/media`. Merge base `939aa61eadfb859f8de1c6690d3f5e9da4cef758` ("master" below).

This file exists to close one named gap in the branch review. The security sweep compared every
**deleted line** against its replacement and found no removed guard — but it conceded that the
result is near-vacuous at this consolidation ratio:

> A guard whose *containing function ceased to exist* is not diff-visible as a removal. Detecting
> that class of loss requires an old-entry-point → new-entry-point coverage map. Nobody built one.
> The security result should be read as *"nothing was removed from surviving code paths"*, which is
> weaker than *"no capability lost a guard"*.

This is that map. Every entry point that existed on master is listed once, given exactly one
disposition, and — the point of the exercise — its **validation** is recorded and traced to where it
lives now, or declared lost.

It is source evidence only. Nothing here has played a media file; see §5.

---

## 1. Summary

### 1.1 The old surface, measured

| Measure at `939aa61ea` | Count | How |
|---|---:|---|
| `JNIEXPORT` grep hits under `modules/javafx.media` | 180 | `git grep -n JNIEXPORT 939aa61ea -- modules/javafx.media` |
| — of those, declarations in iOS `.h` files | 49 | `platform/ios/jni/*.h` re-declare their `.m` definitions |
| **`JNIEXPORT` function definitions** | **131** | 180 − 49 |
| **Java `native` method declarations** | **117** | matches `FFM-STATUS.md` §1 and the fork-point inventory |
| `JNI_OnLoad` definitions | 3 | `GstPlatform.cpp:57`, `GstPlatform.cpp:59` (`STATIC_BUILD` variant), `IOSPlatform.m:45` |
| `RegisterNatives` call sites | **0** | none in the module; every binding was by symbol name |
| `_initIDs` methods | **0** | the module never used the `_initIDs` idiom; IDs were cached lazily in C |

The two counts reconcile exactly. Of the 131 C definitions, 125 implement a Java `native` declaration
(116 of the 117 declarations, plus 9 *second* implementations of the same declaration in the iOS tree
— `Logger` ×2 and `NativeAudioSpectrum` ×7 exist once for the desktop and once for iOS) and 6 have no
Java declaration at all. One Java declaration has no implementation anywhere.

**Distinct old entry points = 123**: the 117 Java `native` declarations plus the 6 C-only exports
(`NativeVideoConverter` ×2, `JNI_OnLoad`/`JNI_OnLoad_jfxmedia` in `GstPlatform.cpp` ×2,
`JNI_OnLoad_jfxmedia` in `IOSPlatform.m`, and `NativeMediaManager_nativeCanPlayContentType` in
`IOSPlatform.m`). That is the row count of §2.

### 1.2 The new surface, measured

| Measure in the working tree | Count |
|---|---:|
| `jfxm_*` exports declared in `jfxmedia_api.h` | **58** |
| `jfxm_*` functions defined in `ffi/jfxmedia_api.cpp` | **58** |
| `jfxm_*` symbols bound in `JfxMediaNative.java` | **58** |
| `JfxmPlayerCallbacks` slots | 13 |
| `JfxmStreamCallbacks` slots | 9 |
| `JfxmLogFn` / `JfxmReleaseFn` singleton callbacks | 2 |
| Java upcall targets in `JfxMediaNative` | 24 (13 + 9 + `onLog` + `onRelease`) |

`JFXM_ABI_VERSION` is 4. The count moved from 56 to 58 during this analysis: another agent landed
`jfxm_offsetof_player_callbacks` and `jfxm_offsetof_stream_callbacks` (the fix for review finding
**S1**, the callback-slot-order blind spot) while it was in progress. Both are declared, defined and
bound; they are treated as new and in scope.

### 1.3 Dispositions

| Disposition | Count | Meaning |
|---|---:|---|
| **COVERED** | **76** | a named new `jfxm_*` export or callback slot does its job |
| **REPLACED-IN-JAVA** | **0** | (as whole entry points — see the note below) |
| **DELETED** | **47** | the capability itself is gone |
| **UNACCOUNTED** | **0** | — |
| **Total** | **123** | |

Every one of the 76 desktop entry points that had an implementation is COVERED. No *whole* entry
point moved into Java, so the REPLACED-IN-JAVA column is zero at entry-point granularity — but four
COVERED rows had **part** of their body move to Java, and those halves are named in the tables:
`nativeAddBand`'s peer construction, `nativeGetPlaneStrides`' array building and range gate,
`gstInitNativeMedia`'s `Locator` resolution, and `osxCreatePlayer`'s `jar:`/`jrt:` scheme test. Three
non-entry-point JNI helpers (`CLocator::LocatorGetStringLocation`, `::CreateConnectionHolder`,
`::GetAudioStreamConnectionHolder`) *are* REPLACED-IN-JAVA outright; they are in §3.

The 47 DELETED break down as 40 iOS-platform Java natives, 1 Java-side orphan (`osxNeedsLocator`),
2 dead `NativeVideoConverter` exports, 3 `JNI_OnLoad` variants (obsolete under FFM), and 1 C-side
orphan (`nativeCanPlayContentType`). Each is argued in §4.

### 1.4 Guard survival — the findings

Across the 76 COVERED entry points, **no validation guard was lost**. Every NULL check, range check,
state check and error-return convention traced to a named site on the new path; six were
strengthened. The two findings below are not lost *guards* — they are lost **error reports** on
initialisation paths, and both are argued down to informational.

| # | Finding | Severity | Argument |
|---|---|---|---|
| **F1** | `MediaError.ERROR_MANAGER_LOGGER_INIT` is no longer raised. `NativeMediaManager`'s constructor called `MediaUtils.error(null, ERROR_MANAGER_LOGGER_INIT, …)` when `Logger.initNative()` returned false; it now only logs at `Logger.ERROR`. | **INFO** | The old raise site could not deliver. That constructor runs inside `NativeMediaManagerInitializer`'s class initializer on *both* sides of the diff, and `MediaUtils.error` (a) calls `NativeMediaManager.getDefaultInstance()`, which is still null during that `<clinit>`, and (b) throws `MediaException` when the listener list is empty, which it provably is that early. The old code therefore turned a logger-allocation failure into an `ExceptionInInitializerError`, not into a listener callback. No listener ever saw this code. The deviation is documented at the site. |
| **F2** | A library-load failure is no longer reported through `MediaUtils.error(null, ERROR_MANAGER_ENGINEINIT_FAIL, …)` from `NativeMediaManager`'s constructor; it is logged there instead. | **INFO** | The listener-visible signal survives, relocated: `GSTPlatform.loadPlatform()` catches `UnsatisfiedLinkError` from `jfxm_platform_init` and posts the same `ERROR_MANAGER_ENGINEINIT_FAIL` through `MediaUtils.nativeError`, and it runs late enough (from `initNativeLayer()`, on the first `getPlayer`/`getMedia`) for a listener to exist. Only the early, undeliverable throw is gone — and the constructor now also catches `UnsatisfiedLinkError`, which `catch (Exception)` did not, so the degradation path is *wider* than master's. |

Two related observations that are **not** findings, recorded so they are not rediscovered as ones:

* **O1 — a pre-existing weakness faithfully preserved.** `jni/NativeEqualizerBand.cpp` never
  NULL-checked its band handle, and `jfxm_eq_band_*` deliberately does not either (the code says so).
  That is behaviour-neutral, which is what a migration commit owes; it is also an unguarded
  dereference on both sides of the diff, and the obvious candidate for a follow-up commit.
* **O2 — `MediaUtils.nativeWarning` is now unreferenced.** It was already unreachable on master
  (§3), so nothing changed; it is dead code either way.

---

## 2. Coverage tables

Line numbers on the left are at the merge base and are stable. Line numbers on the right are in the
working tree at the time of writing and may drift — the symbol names are the durable reference. Two
of the files cited on the right (`ffi/jfxmedia_api.cpp`, `JfxMediaNative.java`) had uncommitted edits
from other agents while this was written.

Every table has the same shape: **old entry point → disposition → new home → the validation it did →
whether that validation survives.**

### 2.1 `jni/NativeVideoBuffer.cpp` — 13 exports → 4 (+ Java)

Ten getters and a plane-buffer factory collapse into one struct fill: `jfxm_frame_get_info` writes a
`JfxmFrameInfo` (`sizeof` 120, checked by `jfxm_sizeof_frame_info` and field-by-field by
`jfxm_offsetof_frame_info`), and `NativeVideoBuffer` reads it once per frame.

| Old (`939aa61ea`) | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `nativeDisposeBuffer` :38 | COVERED | `jfxm_frame_dispose` | NULL handle → no-op | **SURVIVES** — `jfxm_frame_dispose`: `if (pFrame) delete pFrame;` |
| `nativeGetTimestamp` :52 | COVERED | `jfxm_frame_get_info` → `FrameInfo.timestamp()` | NULL frame → `0.0` | **SURVIVES** — `jfxm_frame_get_info` returns `ERROR_FUNCTION_PARAM_NULL` for a NULL frame *or* a NULL out pointer (stronger than master, which had no out pointer to check); `NativeVideoBuffer.getTimestamp` keeps the `0 != nativePeer` gate and the `0.0` default |
| `nativeGetBufferForPlane` :69 | COVERED | `jfxm_frame_get_info` (`plane_data`/`plane_size`) + `JfxMediaNative.planeBuffer` | NULL frame → NULL; `ExceptionCheck`/`ExceptionClear` after `NewDirectByteBuffer` → NULL; plane index bounded inside `CVideoFrame::GetDataForPlane` (`planeIndex < MAX_PLANE_COUNT`, `VideoFrame.cpp:105`) | **SURVIVES** — `planeBuffer` re-checks `plane >= 0 && plane < MAX_PLANES && plane < planeData().length && planeData()[plane] != 0`, and `CVideoFrame`'s own bound is untouched. `ExceptionCheck` is N/A (no JNI call left to fail); the empty-buffer-never-null contract of `NewDirectByteBuffer(NULL, 0)` is reproduced and documented |
| `nativeGetWidth` :91, `nativeGetHeight` :106, `nativeGetEncodedWidth` :121, `nativeGetEncodedHeight` :136, `nativeGetFormat` :151, `nativeHasAlpha` :167, `nativeGetPlaneCount` :182 | COVERED (7) | `jfxm_frame_get_info` → the matching `FrameInfo` component | NULL frame → `0` / `JNI_FALSE` | **SURVIVES** — same two-sided gate as `nativeGetTimestamp`; each Java accessor keeps its master default |
| `nativeGetPlaneStrides` :197 | COVERED | `jfxm_frame_get_info` (`strides[4]`) + `NativeVideoBuffer.getPlaneStrides` | NULL frame → NULL; **plane-count sanity `count > 4 \|\| count < 1` → NULL**; `NewIntArray` NULL → NULL; `new jint[count]` NULL → NULL; `ExceptionCheck` → NULL | **MOVED (to Java)** — the sanity gate is `NativeVideoBuffer.getPlaneStrides`: `if (planeCount < 1 \|\| planeCount > 4) return null;`, with a comment naming the JNI behaviour it reproduces. The C loop is additionally clamped (`ii < uPlaneCount && ii < 4`), and `CVideoFrame::GetPlaneCount` already clamps to `MAX_PLANE_COUNT`. The three allocation-failure guards are N/A: Java allocates the `int[]`, and an OOM there throws rather than returning NULL |
| `nativeConvertToFormat` :240 | COVERED | `jfxm_frame_convert` | NULL frame → `0` | **SURVIVES** — `if (pFrame) … return NULL;` |
| `nativeSetDirty` :255 | COVERED | `jfxm_frame_set_dirty` | NULL frame → no-op | **SURVIVES** — `if (pFrame) pFrame->SetFrameDirty(true);` |

**Behaviour note (not a guard).** The JNI accessors queried C on every call; the FFM ones read one
`FrameInfo` snapshot taken when the buffer is created. Nothing mutates a `CVideoFrame`'s geometry
after it is dispatched (`SetFrameDirty` does not, and `ConvertToFormat` returns a *new* frame), so the
snapshot is equivalent — but it is a real structural difference and is recorded here rather than left
to be found later.

### 2.2 `jni/NativeAudioEqualizer.cpp` — 5 exports → 5

| Old | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `nativeGetEnabled` :34 | COVERED | `jfxm_eq_get_enabled` | NULL eq → `JNI_FALSE` | **SURVIVES** — `(NULL != pEqualizer && pEqualizer->IsEnabled()) ? 1 : 0` |
| `nativeSetEnabled` :42 | COVERED | `jfxm_eq_set_enabled` | NULL eq → no-op | **SURVIVES** |
| `nativeGetNumBands` :50 | COVERED | `jfxm_eq_get_num_bands` | NULL eq → `0` | **SURVIVES** |
| `nativeAddBand` :57 | COVERED (C half) | `jfxm_eq_add_band`; the `FindClass`/`GetMethodID`/`NewObject` half is **REPLACED-IN-JAVA** in `NativeAudioEqualizer.addBand` (`bandRef != 0 ? new NativeEqualizerBand(bandRef) : null`) | NULL eq → NULL; NULL band → NULL; `reportException() \|\| bandClass == NULL` → NULL; `reportException() \|\| mid == NULL` → NULL; `reportException()` after `NewObject` | **SURVIVES** — the two handle checks are `if (NULL != pEqualizer)` and the `bandRef != 0` test in Java. The three JNI reflection guards are N/A: there is no `FindClass`, no cached `jmethodID` and no `NewObject` left to fail |
| `nativeRemoveBand` :93 | COVERED | `jfxm_eq_remove_band` | NULL eq → `JNI_FALSE` | **SURVIVES** |

The Java-side gates on this class are untouched: `NativeAudioEqualizer.addBand` still refuses on
`getNumBands() >= MAX_NUM_BANDS && gain in [MIN_GAIN, MAX_GAIN]` and `removeBand` still requires
`centerFrequency > 0` — preserved verbatim, including the upstream `&&`/`||` oddity in the former,
which a migration commit is right not to "fix".

### 2.3 `jni/NativeEqualizerBand.cpp` — 6 exports → 6

| Old | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `nativeGetCenterFrequency` :35, `nativeSetCenterFrequency` :42, `nativeGetBandwidth` :50, `nativeSetBandwidth` :57, `nativeGetGain` :65, `nativeSetGain` :72 | COVERED (6) | `jfxm_eq_band_get/set_center_frequency`, `…_bandwidth`, `…_gain` | **none** — the band pointer is dereferenced unchecked in all six | **PRESERVED (as-is)** — the new code is identically unchecked, with a comment recording that master was too (*"the band is never NULL-checked there"*). Behaviour-neutral; see observation **O1** |

`NativeEqualizerBand.setGain`'s `gain >= MIN_GAIN && gain <= MAX_GAIN` gate survives verbatim in Java.

### 2.4 `jni/NativeAudioSpectrum.cpp` — 7 exports → 7

| Old | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `nativeGetEnabled` :36, `nativeSetEnabled` :43, `nativeGetInterval` :71, `nativeSetInterval` :78, `nativeGetThreshold` :87, `nativeSetThreshold` :94 | COVERED (6) | `jfxm_spectrum_get/set_enabled`, `…_interval`, `…_threshold` | NULL spectrum → default / no-op | **SURVIVES** — each new function repeats the `NULL != pSpectrum` test with master's default |
| `nativeSetBands` :52 | COVERED | `jfxm_spectrum_set_bands` | holder allocation failure → return; `pHolder->Init()` failure → delete and skip; `pSpectrum != NULL && pHolder != NULL` before `SetBands` | **SURVIVES and STRENGTHENED** — the allocation and NULL-spectrum guards are kept, and the new code *adds* `count <= 0 \|\| NULL == magnitudes \|\| NULL == phases` → log + release + return. Master passed a negative count straight to `g_object_set(…, "bands", count, …)` on `gstspectrum`, whose property is unsigned, and built a holder from NULL arrays that could never copy |

`NativeAudioSpectrum`'s Java gates (`bands > 1`, `interval * ONE_SECOND >= 1`, `threshold <= 0`, and
`mag == null || mag.length < size` in the copy-out) all survive; the last moved into the private
`copyOut` helper. One deliberate, documented deviation: a rejected `setBandCount` no longer clears the
installed band arrays, because C may still be writing through them.

### 2.5 `jni/com_sun_media_jfxmedia_logging_Logger.cpp` — 2 exports → 2

| Old | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `Logger_nativeInit` :45 | COVERED | `jfxm_log_init` | `ENABLE_LOGGING == 0` → `JNI_TRUE`; else `pLogger != NULL && pLogger->init(env, cls)` | **SURVIVES** — `#if ENABLE_LOGGING … #else return 1;` with the "compiled-out is healthy" rule spelled out in `jfxmedia_api.h` (this is the ABI-3 change); the singleton NULL test lives in `CLogger::initSink` |
| `Logger_nativeSetNativeLevel` :62 | COVERED | `jfxm_log_set_level` | `ENABLE_LOGGING` gate; `pLogger != NULL` | **SURVIVES** — both, verbatim |

`CLogger`'s level filter (`level >= m_currentLevel`) is unchanged; `jni/Logger.cpp` survived the
migration JNI-free and gained a lock around the sink pointer.

### 2.6 `jni/com_sun_media_jfxmediaimpl_NativeVideoConverter.cpp` — 2 exports → 0

| Old | Disposition | Guard | Verdict |
|---|---|---|---|
| `nativeConvert__L…VideoDataBuffer_2L…Format_2` :322 | **DELETED** | 10 cached-ID lookups, several NULL checks | **N/A — the capability is gone.** Verified: (a) no `NativeVideoConverter` class exists anywhere under `src/main/java` at the merge base, so neither export could ever be bound; (b) the file appears in none of the three `jfxmedia/projects/*/Makefile` source lists, so it was never compiled; (c) it does not compile — unbalanced braces at :87-96 and a missing semicolon at :287 |
| `nativeConvert__L…VideoDataBuffer_2L…VideoDataBuffer_2` :358 | **DELETED** | as above | as above |

### 2.7 `platform/gstreamer/GstMedia.cpp` — 2 exports → 2

| Old | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `GSTMedia_gstInitNativeMedia` :181 (body in `InitMedia` :50) | COVERED | `jfxm_media_create(JFXM_BACKEND_GST, …)`; the three `CLocator` JNI calls are **REPLACED-IN-JAVA** in `GSTMedia.createNativeMedia` | `CMediaManager::GetInstance` != `ERROR_NONE` → return it; `pjContent == NULL` → `ERROR_MEMORY_ALLOCATION`; `jLocation == NULL` → same; `pjLocation == NULL` → same; `pManager == NULL` → `ERROR_MANAGER_NULL`; `callbacks == NULL \|\| jConnectionHolder == NULL` → `ERROR_MEMORY_ALLOCATION`; `callbacks->Init()` false → `ERROR_MEDIA_CREATION`; `locator == NULL` → `ERROR_MEMORY_ALLOCATION`; the audio-stream branch repeats all four; `CMedia::IsValid(pMedia)` false → `ERROR_MEDIA_INVALID`; `ExceptionCheck` after `SetLongArrayRegion` | **SURVIVES** — the manager, content-type, location and manager-NULL pre-conditions are the same four tests in the same order in `InitGstMedia`, with the same codes and a comment saying which `GetStringUTFChars` failure each stands in for; `NULL == cb` reproduces the null-connection-holder rejection; `CMedia::IsValid` is unchanged; the audio-stream branch is `NULL != audio_cb`. **MOVED** for the Java half: the null-string and null-holder tests are in `GSTMedia.createNativeMedia` (`contentType == null \|\| location == null` → `ERROR_MEMORY_ALLOCATION`; `holder == null` → same). `ExceptionCheck` is N/A (`out_media` is a plain pointer, checked for NULL instead). Master's `HLS_PROP_HAS_AUDIO_EXT_STREAM` probe swallowed a Java exception via `clearException()`; that swallow is reproduced deliberately in Java, with the reasoning written out |
| `GSTMedia_gstDispose` :197 | COVERED | `jfxm_media_dispose` | NULL media → no-op | **SURVIVES** — `if (NULL == pHandle) return;` |

One deliberate, documented departure in the safe direction: a failing `createNativeMedia` now closes
the connection holders it opened. Master left them open until the holder was collected.

### 2.8 `platform/gstreamer/GstMediaPlayer.cpp` — 18 exports → 18

All eighteen share one guard pattern: `pMedia == NULL` → `ERROR_MEDIA_NULL`, then
`pMedia->GetPipeline() == NULL` → `ERROR_PIPELINE_NULL`. That pair is factored into one function on
the new side, `GetGstPipeline` in `ffi/jfxmedia_api.cpp`, whose comment reads *"The NULL checks of
every GstMediaPlayer.cpp export, in their order."* The `void*` handle is additionally re-checked at
the ABI boundary by `PlayerOps`, which answers `ERROR_MEDIA_NULL` for a NULL handle.

| Old | Disposition | New home | Guard delta | Verdict |
|---|---|---|---|---|
| `gstInitPlayer` :54 | COVERED | `jfxm_player_init` → `GstPlayerInit` | + `new(nothrow)` dispatcher NULL → `ERROR_MEMORY_ALLOCATION` | **SURVIVES** — all three |
| `gstGetAudioEqualizer` :86 | COVERED | `jfxm_player_get_audio_equalizer` → `GstPlayerGetAudioEqualizer` | master checked `pMedia` but **not** `GetPipeline()`, and dereferenced it | **SURVIVES and STRENGTHENED** — the new code adds the pipeline NULL check master lacked |
| `gstGetAudioSpectrum` :102 | COVERED | `jfxm_player_get_audio_spectrum` → `GstPlayerGetAudioSpectrum` | as above | **SURVIVES and STRENGTHENED** — same |
| `gstGetAudioSyncDelay` :118 | COVERED | `jfxm_player_get_audio_sync_delay` | + error-code passthrough from `GetAudioSyncDelay`; + `ExceptionCheck` after `SetLongArrayRegion` → `ERROR_JNI_UNEXPECTED` | **SURVIVES** — the pair and the passthrough. The `ExceptionCheck` is N/A (no JNI array write); its replacement is `if (NULL != out_millis)`, a guard master did not need because JNI handed it an object |
| `gstSetAudioSyncDelay` :152 | COVERED | `jfxm_player_set_audio_sync_delay` | pair only | **SURVIVES** |
| `gstPlay` :177, `gstPause` :204, `gstStop` :230, `gstFinish` :256 | COVERED (4) | `jfxm_player_play/pause/stop/finish` | pair only | **SURVIVES** |
| `gstGetRate` :282, `gstGetPresentationTime` :341, `gstGetVolume` :375, `gstGetBalance` :434, `gstGetDuration` :493 | COVERED (5) | `jfxm_player_get_rate` / `…_get_presentation_time` / `…_get_volume` / `…_get_balance` / `…_get_duration` | pair + error passthrough + `ExceptionCheck` after the array write | **SURVIVES** — pair and passthrough kept; the array `ExceptionCheck` is replaced by a NULL out-pointer test on each |
| `gstSetRate` :316, `gstSetVolume` :409, `gstSetBalance` :468, `gstSeek` :527 | COVERED (4) | `jfxm_player_set_rate/set_volume/set_balance/seek` | pair only | **SURVIVES** |

`jfxm_player_get_mute` / `jfxm_player_set_mute` are new exports with no GStreamer counterpart on
master; the GST backend answers `ERROR_NOT_IMPLEMENTED`, which is what `GSTMediaPlayer.java` already
assumed. They exist for the AVF backend (§2.10).

### 2.9 `platform/gstreamer/GstPlatform.cpp` — 3 exports → 1

| Old | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `JNI_OnLoad_jfxmedia` :57 (`STATIC_BUILD`) | **DELETED** | — | none; stored `g_pJVM` and returned `JNI_VERSION_1_2` | **N/A — obsolete.** Its only product was the `JavaVM*` used by `CJavaEnvironment` to attach threads. FFM upcall stubs attach themselves, so there is nothing to cache. Its one consumer, `GstJniUtils.cpp::GstGetEnv`, had **zero** call sites at the merge base (grep finds only its own declaration and definition), and that file is deleted too |
| `JNI_OnLoad` :59 | **DELETED** | — | as above | as above |
| `GSTPlatform_gstInitPlatform` :73 | COVERED | `jfxm_platform_init` | `GetInstance` != `ERROR_NONE` → return it; `pManager == NULL` → `ERROR_MANAGER_NULL`; `new(nothrow) CJavaMediaWarningListener` NULL → `ERROR_MEMORY_ALLOCATION` | **SURVIVES** for the two manager guards. The third guarded an allocation that no longer happens: the warning listener it created is **DELETED** — see §4.5, where the deletion is argued and the `ERROR_MANAGER_ENGINEINIT_FAIL` relocation is traced |

### 2.10 `platform/osx/OSXMediaPlayer.mm` — 21 exports → 21

Twenty of the twenty-one share one guard: `[OSXMediaPlayer peerForPlayer:andEnv:]` returns nil for an
unknown Java player, and each export tests it and returns a default. That `JObjectPeers` map is a
`jobject`-keyed peer registry — precisely the machinery FFM replaces with a Java-side id registry, so
the guard's new form is the `JfxmMedia` handle plus `AvfResolvePlayer`, which answers
`ERROR_MEDIA_NULL` and leaves `player` nil.

| Old | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `osxCreatePlayer` :293 | COVERED | `jfxm_media_create(JFXM_BACKEND_AVF, …)` + `jfxm_player_init` → `jfxm_avf_player_init`; the `jar:`/`jrt:` scheme test is **REPLACED-IN-JAVA** in `OSXMedia.initNativeMedia` | `sourceURIString` nil → `ThrowJavaException(MediaException)`; `mediaURL` nil → same; `callbacks == NULL \|\| jConnectionHolder == NULL` → same; `callbacks->Init()` false → same; `pjContent == NULL \|\| pjSourceURI == NULL` → same; `player` nil → same; **`[mediaURL scheme]` is `jar`/`jrt` ⇒ use a Locator stream** | **SURVIVES, relocated.** Each `ThrowJavaException` becomes an error code that Java turns back into the same exception: `OSXMediaPlayer.java` throws `new MediaException("OSXMediaPlayer: unable to create player", null, error)`. The null-location and null-content-type tests are in `OSXMedia.initNativeMedia` (`location == null` → `ERROR_MEMORY_ALLOCATION`; `contentType == null` on the jar/jrt path → same) and are re-checked in C in `jfxm_media_create`'s AVF branch, with comments naming the master message each stands for. The `NSURL` failure becomes `ERROR_FACTORY_INVALID_URI`. **MOVED** for the scheme test: `OSXMedia.initNativeMedia` — `"jar".equalsIgnoreCase(scheme) \|\| "jrt".equalsIgnoreCase(scheme)`. The code documents why the C-side repeat was dropped: two URL parsers asking the same question can only disagree |
| `osxGetAudioEqualizerRef` :389, `osxGetAudioSpectrumRef` :406 | COVERED (2) | `jfxm_player_get_audio_equalizer` / `…_spectrum` → `jfxm_avf_player_get_audio_equalizer` / `…_spectrum` | nil peer → `0` | **SURVIVES** — `AvfResolvePlayer` + `if (player)`, default NULL |
| `osxGetAudioSyncDelay` :423, `osxSetAudioSyncDelay` :441 | COVERED (2) | `jfxm_player_get/set_audio_sync_delay` | nil peer → `0` / no-op | **SURVIVES**, plus a NULL out-pointer test the JNI form did not need |
| `osxPlay` :457, `osxStop` :473, `osxPause` :489, `osxFinish` :505 | COVERED (4) | `jfxm_player_play/stop/pause/finish` | nil peer → no-op | **SURVIVES** |
| `osxGetRate` :521, `osxSetRate` :539, `osxGetPresentationTime` :555, `osxGetMute` :573, `osxSetMute` :591, `osxGetVolume` :607, `osxSetVolume` :625, `osxGetBalance` :641, `osxSetBalance` :659, `osxGetDuration` :675, `osxSeek` :693 | COVERED (11) | `jfxm_player_get/set_rate`, `…_presentation_time`, `…_mute`, `…_volume`, `…_balance`, `…_duration`, `…_seek` | nil peer → master's default | **SURVIVES** — same pattern throughout |
| `osxDispose` :709 | COVERED | `jfxm_media_dispose` → `jfxm_avf_player_dispose` | nil peer → no-op; `[OSXMediaPlayer removePlayerPeers:]` | **SURVIVES** — the handle NULL test replaces the peer test; the peer-map removal is the registry `unregister()` on the Java side |

### 2.11 `platform/osx/OSXPlatform.mm` — 1 export → 1

| Old | Disposition | New home | Guard in the old code | Verdict |
|---|---|---|---|---|
| `OSXPlatform_osxPlatformInit` :35 | COVERED | `jfxm_osx_platform_init` | `mainBundle` nil → warn; `infoDictionary` nil → warn; not an `NSMutableDictionary` → warn (the JDK-8202393 ATS workaround, best-effort by design) | **SURVIVES** — `jfxmedia_api.h` records the same three-step best-effort contract, and the non-Apple build returns 0 |

### 2.12 `platform/ios/**` — 51 export definitions → 0

The whole iOS tree is **DELETED**: 40 distinct entry points (`NativeAudioClip` 12, `IOSMedia` 2,
`IOSMediaPlayer` 25, `IOSPlatform.iosPlatformInit` 1), 9 duplicate implementations of desktop entry
points (`Logger` 2, `NativeAudioSpectrum` 7), and 2 C-side-only exports (`JNI_OnLoad_jfxmedia`,
`NativeMediaManager_nativeCanPlayContentType`). Their guards go with them and none is listed as lost;
§4.1 argues why that is legitimate rather than asserting it.

---

## 3. The JNI machinery that was not an entry point

The map would be dishonest if it dropped the plumbing, since that is where most of the cached-ID and
global-ref risk lived. There are **no** `RegisterNatives` calls and **no** `_initIDs` methods in this
module on either side of the diff; IDs were cached lazily inside the C classes instead.

| Master machinery | Disposition | Where it went | Guard trace |
|---|---|---|---|
| `CJavaPlayerEventDispatcher` — `GetJavaVM`, `NewGlobalRef(player)` :74 / `DeleteGlobalRef` :176, 13 cached `jmethodID`s, 15 `Send*` upcalls | COVERED | `JfxmPlayerCallbacks` (13 slots) + `ffi/FfiPlayerEventDispatcher.cpp`; the global ref becomes a Java-assigned `int64_t` registry id passed as `void* user` | **SURVIVES.** `Warning` still drops a NULL message; `SendPlayerHaltEvent` still reports failure for one; `SendPlayerStateEvent` still maps the eight `CPipeline` states and still `return false`s on an unknown one (`FfiMapPipelineStateToJavaEvent`, shared with the exported `jfxm_event_player_state` so the two cannot drift); `SendAudioTrackEvent`'s seven-bit channel-mask remap is copied bit for bit. `reportException()` is replaced by `catch (Throwable)` in all 13 Java targets, each returning master's failure value. `SendNewFrameEvent` gains an explicit frame-ownership rule for the NULL-slot case, argued in the code |
| `CJavaInputStreamCallbacks` — `GetJavaVM`, `NewGlobalRef(holder)` :65 / `DeleteGlobalRef` :288, 1 cached `jfieldID` + 8 cached `jmethodID`s, `FindClass` on the **abstract base** `ConnectionHolder` (JDK-8093889) | COVERED | `JfxmStreamCallbacks` (9 slots) + `ffi/FfiStreamCallbacks.cpp`; `ConnectionHolderBridge` gives the facade package access to the same base-class methods | **SURVIVES and STRENGTHENED.** Return conventions are preserved exactly (`-2` from `read_next_block`/`read_block` on a throw, `-1` from `seek`, `0` from the boolean/int slots) and are now stated in the header. The post-close behaviour master got from a nulled global ref is `m_bClosed`. **`copy_block` is strictly safer than master**: master did `GetObjectField` → `GetDirectBufferAddress` → **unchecked `memcpy(destination, data, size)`**, with no NULL test on the address and no bound on the source; the FFM slot bounds the copy by the staged buffer, zero-fills the remainder, logs the shortfall and — since ABI 3 — *returns* it, so `javasource` can fail the read instead of parsing uninitialised memory. The `FindClass`-on-the-abstract-base guard is N/A: Java calls its own methods, so there is no virtual-dispatch crash to prevent |
| `CJavaBandsHolder` — `GetJavaVM`, `NewGlobalRef` ×2 :63-64 / `DeleteGlobalRef` ×2, `SetFloatArrayRegion` from the spectrum thread | COVERED | `ffi/FfiBandsHolder.cpp` writing Java-owned off-heap segments, with a `JfxmReleaseFn` that runs exactly once per `jfxm_spectrum_set_bands` call | **SURVIVES.** Master's `m_Bands != size` mismatch test becomes the `count`/NULL pre-condition on the entry point plus the holder's own both-sides-non-NULL test. The release-exactly-once rule is new, and is what lets Java know when C is done with a pair |
| `CLogger::init` — `GetJavaVM`, `NewWeakGlobalRef(class)` :129 (**never deleted** on master), 2 cached static `jmethodID`s | COVERED | one `JfxmLogFn` upcall stub installed once by `jfxm_log_init`, allocated in `Arena.global()` | **SURVIVES.** The level filter is unchanged. The never-deleted weak global ref is simply gone — one leak fewer, not a guard |
| `CLocator::LocatorGetStringLocation` :54, `::CreateConnectionHolder` :83, `::GetAudioStreamConnectionHolder` :108 — 3 cached `jmethodID`s, `CallObjectMethod` on the Java caller thread | **REPLACED-IN-JAVA** | `GSTMedia.createNativeMedia` and `OSXMedia.initNativeMedia` call `locator.getStringLocation()`, `locator.createConnectionHolder()` and `locator.getAudioStreamConnectionHolder(holder)` directly — same thread, same order, which `jfxmedia_api.h` pins down as part of the `jfxm_media_create` contract | **SURVIVES** — each null result maps to the `ERROR_MEMORY_ALLOCATION` master returned for the corresponding JNI failure |
| `CJavaMediaWarningListener` + `CMediaWarningDispatcher` — `FindClass` + `GetStaticMethodID` on **every** call | **DELETED** | — | **N/A — the path was doubly dead on master**, verified two independent ways: (1) `CMediaWarningDispatcher::Warning` has **no callers** anywhere in the tree (grep finds only the definition and the `friend` declaration in `MediaManager.h:46`); (2) even if called, it looked up `MediaUtils.nativeWarning` with the descriptor `(ILjava/lang/String;)V`, while `MediaUtils.java:321` declares `nativeWarning(Object, int, String)` — i.e. `(Ljava/lang/Object;ILjava/lang/String;)V` — so `GetStaticMethodID` throws `NoSuchMethodError`, `clearException()` returns true, and the call is skipped. `MediaUtils.nativeWarning` survives in the tree and is now unreferenced (observation **O2**). Note this is the *global* warning listener; the *per-player* warning path is a live `JfxmPlayerCallbacks` slot |
| `JniUtils.cpp` — `ThrowJavaException` :34, `GetJavaEnvironment` :60, `CJavaEnvironment` attach/detach :117-145, `reportException` :91 | COVERED | error codes across the ABI + `catch (Throwable)` in all 24 Java upcall targets; no attach/detach, because upcall stubs attach themselves | **SURVIVES** — the six `ThrowJavaException` sites (all in `osxCreatePlayer`) are traced in §2.10; `reportException`'s "an exception means failure" convention is the `int32_t` return of every player slot |
| `Utils/JObjectPeers.m`, `Utils/JavaUtils.m` | COVERED | the `JfxMediaNative` registry (`register`/`unregister`/`lookup`, keyed by a Java-assigned id) and `std::string` | **SURVIVES** — an unknown id resolves to `null` and every upcall target returns master's default, exactly as a nil peer did |
| `Utils/MTObjectProxy.m` | **DELETED** | — | **N/A — dead.** Verified: `objectProxyWithTarget:` has **zero** call sites; the class is only `#import`ed by `OSXMediaPlayer.h` and never instantiated. Recorded because the `core-jni` audit had provisionally called it `OS-CALL`; it is not reached |

---

## 4. Deletions, verified

The review cleared five deletions as legitimate. Each is re-verified here from the merge base rather
than repeated, and **two more were found** that its list did not name (§4.5, §4.6). All seven hold.

### 4.1 The iOS platform — 40 entry points, 51 export definitions

* **Never built here.** `platform/ios/**` appears in **none** of
  `jfxmedia/projects/{win,linux,mac}/Makefile` (checked directly: the only `ios` substring matches in
  all three are `AudioSpectrum` and `GstAudioSpectrum`), and the merge base has no CMake media build
  at all.
* **Never reachable here.** `PlatformManager` gates it on `PlatformUtil.isIOS()` (`:107-109`), and
  gates `GSTPlatform` on `!PlatformUtil.isIOS()`.
* **`MediaPlayerOverlay` is iOS-only.** The interface had exactly one implementation,
  `IOSMediaPlayer.MediaPlayerOverlayImpl`; `GSTMediaPlayer.getMediaPlayerOverlay()` and
  `OSXMediaPlayer.getMediaPlayerOverlay()` both `return null; // Not needed`. The 171 lines removed
  from the **public** class `javafx.scene.media.MediaView` are all either the overlay field and its
  seven `updateOverlay*` helpers (each already `if (mediaPlayerOverlay != null)`) or the
  `if (PlatformUtil.isIOS()) … else …` branches in five property `invalidated()` bodies, whose `else`
  arm is what survives. The removed `doTransformsChanged` accessor override had the body
  `if (mediaPlayerOverlay != null) updateOverlayTransform();`. On every platform this fork builds, all
  of it was unreachable. `MediaPlayerOverlay` lives in `com.sun.media.jfxmedia.control` — internal,
  not public API — so no CSR question arises.
* **`nativeCanPlayContentType`** (`IOSPlatform.m:127`) exported
  `Java_com_sun_media_jfxmediaimpl_NativeMediaManager_nativeCanPlayContentType`, but
  `NativeMediaManager.java` declares **no** `native` method at the merge base. A C-side orphan.

### 4.2 `NativeVideoConverter` — 2 entry points

Verified in §2.6: no Java class, in no Makefile, and does not compile.

### 4.3 `LowLevelPerf` — support code, no entry point

`Common/ProductFlags.h:57` sets `ENABLE_LOWLEVELPERF 0` at the merge base **and at HEAD**, so every
`LOWLEVELPERF_*` macro expanded to nothing. It was compiled on Windows and macOS only, and never on
Linux. Deleting `Utils/LowLevelPerf.{cpp,h}` was safe because the macro call sites went with the
functions that contained them. The two surviving `#if ENABLE_LOWLEVELPERF` blocks
(`GstAudioPlaybackPipeline.h`, `GstMediaManager.cpp` ×2) are byte-identical to master and reference
GLib, not the deleted header, so flipping the flag back on still compiles.

### 4.4 `WinThread` / `CThread`, `WinDllMain`, `AutoLock`, `Thread.h` — support code, no entry points

* `CThread` is named in exactly two files at the merge base, `Utils/Thread.h` and
  `Utils/win32/WinThread.cpp` — its own declaration and its own definition. It was compiled into
  `jfxmedia.dll` (the Windows Makefile lists it) and never used; the shipped DLL imports no
  `_beginthreadex`.
* `WinDllMain.cpp`'s `DllMain(DWORD, LPVOID)` has the **wrong signature** (the real one takes
  `HINSTANCE, DWORD, LPVOID`), and both handlers it dispatches to are empty: `OnDllLoad` is
  `return TRUE;`, `OnDllUnload` is `{}`. Removing it removes nothing.
* `Utils/AutoLock.h` had zero references. The `CAutoLock` occurrences that remain in the tree belong
  to the vendored DirectShow `baseclasses`, which are untouched.

### 4.5 The global media-warning listener — **not in the review's list**

`JavaMediaWarningListener.{cpp,h}` and `MediaWarningDispatcher.{cpp,h}` were deleted, removing the
whole "global, non-player-specific warning" path. Verified dead two independent ways in §3. The one
live consumer of the object, `gstInitPlatform`, only ever *constructed and stored* it. Legitimate.

Related, and worth stating plainly because it is the closest thing to a real behaviour change on this
branch: the `ERROR_MANAGER_ENGINEINIT_FAIL` report that `NativeMediaManager`'s constructor used to
raise on a library-load failure is now raised from `GSTPlatform.loadPlatform()` instead — see **F2**.

### 4.6 `NativeAudioClip` / `AudioClipProvider` — **not in the review's list**

`NativeAudioClip.java` (12 natives) and `AudioClipProvider.java` were deleted, and
`com.sun.media.jfxmedia.AudioClip` now calls `NativeMediaAudioClip` directly.

Verified legitimate: `NativeAudioClip`'s only implementation was
`platform/ios/jni/com_sun_media_jfxmediaimpl_NativeAudioClip.m`, which no desktop Makefile built.
`AudioClipProvider`'s constructor therefore always took its `catch (UnsatisfiedLinkError)` branch on
every platform this fork builds, logged *"JavaFX AudioClip native methods not linked, using
NativeMedia implementation"*, set `useNative = false`, and forwarded every call to
`NativeMediaAudioClip` — which is exactly what the new code does unconditionally. The removed
`try`/`catch` was an availability probe, not a validation guard.

### 4.7 `osxNeedsLocator` — a Java-side orphan

`OSXMediaPlayer.java:197` declared `private native boolean osxNeedsLocator() throws MediaException;`
at the merge base. Grep over the whole tree finds **only that declaration**: no implementation in
`OSXMediaPlayer.mm` or anywhere else (that file's 21 exports are all accounted for in §2.10), and no
caller. Calling it would have thrown `UnsatisfiedLinkError`. Deleting it removes nothing.

---

## 5. Method, evidence, and limits

### 5.1 Method

1. The old surface was read out of git, not out of the working tree — the files are gone from HEAD.
   `git ls-tree -r 939aa61ea` enumerated `modules/javafx.media/src/main/native/jfxmedia`, and each
   file was extracted with `git show 939aa61ea:<path>` into a scratch tree so bodies could be read in
   full. `JNIEXPORT` and Java `native` declarations were enumerated with
   `git grep -n … 939aa61ea -- modules/javafx.media`, counted per file, and reconciled against each
   other (§1.1); that reconciliation is what surfaced the two orphans in §4.7 and §4.1.
2. The new surface was enumerated from `jfxmedia_api.h` (58 `JFXM_EXPORT`), `ffi/jfxmedia_api.cpp`
   (58 definitions) and `JfxMediaNative.java` (58 bound symbol names), plus the two callback structs
   and the 24 Java upcall targets.
3. For each old entry point, the **body** was read at the merge base and every validation extracted:
   NULL checks, range and array-bounds checks, state/disposed checks, `ExceptionCheck`/`Throw*` calls,
   error-code conventions and their defaults. The corresponding new function was then read in full and
   the guard traced, strengthened, moved to a named Java site, or declared lost.
4. Each deletion the review had cleared was re-derived from primary evidence — Makefile source lists,
   `ProductFlags.h`, grep for call sites, the Java class's existence — rather than accepted.

Read-only throughout: `git show` / `git ls-tree` / `git grep` / `git diff` only. Nothing was built,
committed or checked out; this file is the only thing written.

### 5.2 What this map establishes

* Every entry point that existed on master has a disposition, and none is **UNACCOUNTED**.
* Every validation those 76 surviving entry points performed has a named home on the new path. Six
  are strengthened: the unchecked `memcpy` in `CopyBlock`, the missing pipeline NULL check in
  `gstGetAudioEqualizer` and in `gstGetAudioSpectrum`, the unvalidated band count and buffers in
  `nativeSetBands`, and the NULL out-pointer tests the JNI out-param forms never needed.
* The 47 deleted entry points were all unreachable, unbuilt, uncompilable or obsolete on every
  platform this fork builds, each verified from primary evidence.

### 5.3 What it does not establish

* **It is static.** Nothing here ran. There is still no media playback test anywhere in the fork, so a
  guard that is *present* on the new path but reached under different conditions than master's would
  not show up in this analysis. This map upgrades the security result from *"nothing was removed from
  surviving code paths"* to *"no capability lost a guard, by source inspection"*. It does not reach
  *"the guards fire when they used to."*
* **macOS is source-only.** The AVF rows in §2.10 were read, not compiled or executed. That backend
  has never been built on this machine, and no test plays media on any machine.
* **Callback-slot order.** The C side now exports `jfxm_offsetof_player_callbacks` /
  `jfxm_offsetof_stream_callbacks` and the Java side binds them, which is the mechanism that closes
  review finding **S1** — but whether the resulting assertions actually run is a test question, and
  belongs to the test suite, not to this map.
* **iOS deletion is verified as unreachable *in this fork*.** Whether some downstream consumer wanted
  an iOS media backend is a product question this map cannot answer; it can only report that nothing
  in this repository built it, referenced it outside an `isIOS()` guard, or could have linked it.
* **Line numbers on the HEAD side may drift.** Two of the files cited (`ffi/jfxmedia_api.cpp`,
  `JfxMediaNative.java`) had uncommitted edits from concurrent work while this was written; the symbol
  names are the durable reference and were re-checked at the end.
