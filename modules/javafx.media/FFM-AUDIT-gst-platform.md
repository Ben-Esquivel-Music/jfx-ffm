# JNI audit — slice `gst-platform` (GSTPlatform / GSTMedia / GSTMediaPlayer + `jfxmedia/platform/gstreamer/**`)

Repository: `C:\SourceCode\jfx-ffm`. All paths below are repo-relative unless absolute. Notes persisted at `C:\Users\bestq\AppData\Local\Temp\claude\C--SourceCode-jfx-ffm\16d8edcc-5244-4cc6-b7f7-21d459e4d1ba\scratchpad\phase1\gst-platform-notes.md`.

## 1. Verdict

**Migrate, and delete the dead glue while doing it — do not reimplement anything in Java.** This slice is a JNI marshalling layer (`GstMediaPlayer.cpp`, `GstMedia.cpp`, `GstPlatform.cpp`, `GstJniUtils.cpp`, plus the `jni/` classes they instantiate) sitting on top of a GStreamer engine (`GstMediaManager`, `GstPipelineFactory`, `GstAudioPlaybackPipeline`, `GstAVPlaybackPipeline`, `GstAudioEqualizer`, `GstAudioSpectrum`, `GstVideoFrame`). Triage over the 51 function groups ruled: **JNI-GLUE 12** (the 23 `JNIEXPORT` sites, the dispatcher, the warning listener, the Locator statics, `GstJniUtils`, `JniUtils`), **OS-CALL 34** (every pipeline/manager/factory method reaches `gst_*`/`g_*` — named per file in §4/§5), **PURE 4** (`GstElementContainer`, `CPipeline` base defaults, `CMedia`, `CMediaWarningDispatcher`), **PURE-HOT 1** (`ColorConvert_*` behind `GstVideoFrame::ConvertToFormat` — owned by the NativeVideoBuffer slice, `PARITY: unknown`), **WRAPPER 0**. The parity gate is moot for everything ruled here: the JNI glue becomes a flat C ABI (behaviour-neutral by construction), the four PURE units are either dead code (`CMediaWarningDispatcher`, never instantiated) or internal C++ helpers that are not a boundary, and the one PURE-HOT unit is out of slice. Three findings shape the plan: (a) **`GstJniUtils.cpp` is dead** — `GstGetEnv` has zero callers anywhere under `src/main/native`; (b) **the manager-level warning path is dead** — `CMediaWarningDispatcher` is never instantiated, so `CJavaMediaWarningListener` is never invoked; (c) **`gstreamer-lite.dll`/`glib-lite.dll` export by ordinal only (`[NONAME]`)**, so on Windows Java cannot bind `gst_*` by name — every "Java could call GStreamer directly" argument (the WRAPPER verdict) is blocked at the binary level, which is why the borderline one-liners (`SetVolume` → `g_object_set`) stay behind the C ABI.

## 2. Summary counts

| Item | Count | Where |
|---|---|---|
| Java `native` methods | 21 | `GSTMediaPlayer` 18 (all instance), `GSTMedia` 2 (instance), `GSTPlatform` 1 (static) |
| `JNIEXPORT` sites in scope | 23 textual / 22 compiled | `GstMediaPlayer.cpp` 18, `GstMedia.cpp` 2, `GstPlatform.cpp` 3 (`JNI_OnLoad_jfxmedia` under `STATIC_BUILD`, `JNI_OnLoad`, `gstInitPlatform`) |
| Orphans | 0 either direction | every Java native has a C export and vice versa; the names `gstGetStopTime/SetStopTime/GetStartTime/SetStartTime/GetPlayerState/gstInitNativeMediaManager/gstNewGSTMediaPlayer/gstGetMediaPlayer` from the task brief **do not exist** (start/stop time and state are Java-side in `NativeMediaPlayer`) |
| Upcall targets (jmethodID) | 13 dispatcher + 1 warning + 3 Locator + 1 ConnectionHolder + 1 Throwable.toString | `jni/JavaPlayerEventDispatcher.cpp:87-158`, `jni/JavaMediaWarningListener.cpp:49`, `Locator/Locator.cpp:66,94,119`, via `jni/JavaInputStreamCallbacks.cpp:132`, `jni/JniUtils.cpp:99` |
| Upcall call sites in `platform/gstreamer` | 47 | `SendPlayerMediaErrorEvent` 25, `SendPlayerHaltEvent` 7, `Warning` 6, `SendNewFrameEvent` 2, `SendFrameSizeChangedEvent`/`SendVideoTrackEvent`/`SendAudioTrackEvent`/`SendDurationUpdateEvent`/`SendAudioSpectrumEvent`/`SendPlayerStateEvent`/`SendBufferProgressEvent` 1 each; `SendMarkerEvent`/`SendSubtitleTrackEvent` 0 (dead in GStreamer) |
| Global refs | 1 per player (matched) + 1–2 per media (matched, input-stream slice) | `JavaPlayerEventDispatcher.cpp:74/176`; `JavaInputStreamCallbacks.cpp:65/288` |
| Cached IDs | 13 static `jmethodID` (dispatcher) + 3 static `jmethodID` (Locator) + 1 `jclass`-less `FindClass` per call (warning listener) | see §6 |
| Array / string accesses | 7 `Set*ArrayRegion` (write-only out-params), 2 `GetStringUTFChars`, 0 `Get*ArrayElements`, 0 `Critical`, 0 `GetDirectBufferAddress` | `GstMediaPlayer.cpp:136,300,359,393,452,511`; `GstMedia.cpp:149,58,76` |
| Thread attaches reachable from slice | 15 (1 dead) | `JniUtils.cpp:67` via `CJavaEnvironment` in 13 `Send*` + `Warning` + warning listener; `GstJniUtils.cpp:50` (dead) |
| Exceptions | 0 `ThrowNew`; 8 `ExceptionCheck/Clear`; `reportException` after every upcall | `GstMediaPlayer.cpp` ×6, `GstMedia.cpp:150`, dispatcher `Init:69` |
| `FindClass` by name | 4 | `JavaMediaWarningListener.cpp:47` (live but unreachable), dispatcher `:550,:619` (dead), `JniUtils.cpp:97` |
| `JNI_OnLoad` | 1 (+1 `STATIC_BUILD` variant) | `GstPlatform.cpp:57-64`, stores `g_pJVM` only |

## 3. Java classes and C files

### 3a. Java classes

| Class | File | Natives | Static init / library load | Handle fields |
|---|---|---|---|---|
| `com.sun.media.jfxmediaimpl.platform.gstreamer.GSTMediaPlayer` (final, extends `NativeMediaPlayer`) | `modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/platform/gstreamer/GSTMediaPlayer.java:282-299` | 18 instance | none | uses `gstMedia.getNativeMediaRef()` (`long` = `CMedia*`) on every call; `audioEqualizer`/`audioSpectrum` built from `long` returned by `gstGetAudioEqualizer/Spectrum` (`:58-59`) |
| `…gstreamer.GSTMedia` (final, extends `NativeMedia`) | `GSTMedia.java:92-96` | 2 instance | none | `protected long refNativeMedia` `:47` = `CMedia*`; `dispose()` `:79` synchronized, zeroes after `gstDispose` |
| `…gstreamer.GSTPlatform` (public final, extends `Platform`) | `GSTPlatform.java:193` | 1 static | none here — libraries are loaded by `NativeMediaManager` ctor `NativeMediaManager.java:103-141`: `glib-lite` (win/mac) → `gstreamer-lite` (non-linux) → `jfxmedia` with dependency list; then `Logger.initNative()` `:144` | — |
| `…platform.PlatformManager` | `PlatformManager.java:90-96` | 0 | reflectively `GSTPlatform.getPlatformInstance()`; `loadPlatforms()` `:142` → `GSTPlatform.loadPlatform()` `:80` → `gstInitPlatform()` (UnsatisfiedLinkError → `ERROR_MANAGER_ENGINEINIT_FAIL`, non-zero → `MediaUtils.nativeError`, always returns true) | — |
| `…jfxmediaimpl.NativeMediaPlayer` (abstract base) | `NativeMediaPlayer.java` | 0 | `init()` `:164` starts `EventQueueThread` (daemon, `:381-439`); `dispose()` `:1322`: terminate loop → `playerDispose()` `:1342` → `media.dispose()` `:1346` (this is where `gstDispose` runs) | declares the 13 upcall targets `:1440-1564` (all just `eventLoop.postEvent`, except `sendPlayerHaltEvent` also logs and `sendNewFrameEvent` first calls `NativeVideoBuffer.createVideoBuffer(nativeRef)`); `@Native eventPlayer* = 100..107` `:74-81` |

**Q1 — the 18 `GSTMediaPlayer` natives** (all `(JNIEnv*, jobject, jlong ref_media, …)`; every one does `jlong_to_ptr` → `CMedia*` → `ERROR_MEDIA_NULL`, `GetPipeline()` → `ERROR_PIPELINE_NULL`, then forwards):

| Java (`GSTMediaPlayer.java`) | JNI symbol suffix / C line | Out-param | Return path | Forwards to |
|---|---|---|---|---|
| `int gstInitPlayer(long)` `:282` | `_gstInitPlayer` `GstMediaPlayer.cpp:54` | — | `new CJavaPlayerEventDispatcher` (`ERROR_MEMORY_ALLOCATION`), `Init(env,obj,pMedia)`, `SetEventDispatcher`, `return pPipeline->Init()` | `CGstAudioPlaybackPipeline::Init` `:117` / `CGstAVPlaybackPipeline::Init` `:87` |
| `long gstGetAudioEqualizer(long)` `:283` | `:86` | — | returns `CAudioEqualizer*` as `jlong`, 0 if media null (**no pipeline-null check**) | `CPipeline::GetAudioEqualizer` → `CGstAudioPlaybackPipeline::GetAudioEqualizer` `:991` |
| `long gstGetAudioSpectrum(long)` `:284` | `:102` | — | same shape | `GetAudioSpectrum` `:996` |
| `int gstGetAudioSyncDelay(long,long[])` `:285` | `:118` | `long[1]` via `SetLongArrayRegion` `:136`; `ExceptionCheck` → `ERROR_JNI_UNEXPECTED` | rc of `GetAudioSyncDelay(long*)` | `:978` (`g_object_get(AUDIO_SINK,"ts-offset")`, ns→ms, **cast to C `long` = 32-bit on Windows**) |
| `int gstSetAudioSyncDelay(long,long)` `:286` | `:152` | — | rc; `(long)audio_sync_delay` truncates on LLP64 | `:961` (`g_object_set "ts-offset"`) |
| `int gstPlay/gstPause/gstStop/gstFinish(long)` `:287-290` | `:177/:204/:230/:256` | — | rc | `Play :463 / Pause :549 / Stop :499 / Finish :532` (`gst_element_set_state`, `SetPlayerState`) |
| `int gstGetRate(long,float[])` `:291` | `:282` | `float[1]` `SetFloatArrayRegion :300` | rc | `GetRate :864` (returns cached `m_fRate`) |
| `int gstSetRate(long,float)` `:292` | `:316` | — | rc | `SetRate :787` (`gst_element_seek` with rate) |
| `int gstGetPresentationTime(long,double[])` `:293` | `:341` | `double[1]` `SetDoubleArrayRegion :359` | rc | `GetStreamTime :730` (`gst_element_query_position`, clamps to duration) |
| `int gstGetVolume(long,float[])` `:294` | `:375` | `float[1]` `:393` | rc | `GetVolume :899` (`g_object_get(AUDIO_VOLUME,"volume")`) |
| `int gstSetVolume(long,float)` `:295` | `:409` | — | rc | `SetVolume :877` (clamp 0..1, `g_object_set`) |
| `int gstGetBalance(long,float[])` `:296` | `:434` | `float[1]` `:452` | rc | `GetBalance :940` (`"panorama"`) |
| `int gstSetBalance(long,float)` `:297` | `:468` | — | rc | `SetBalance :919` (clamp -1..1) |
| `int gstGetDuration(long,double[])` `:298` | `:493` | `double[1]` `:511`; **-1.0 = unknown, Java maps to `+Infinity`** (`GSTMediaPlayer.java:255`) | rc (`ERROR_GSTREAMER_PIPELINE_QUERY_LENGTH` when query fails) | `GetDuration :703` (`gst_element_query_duration`) |
| `int gstSeek(long,double)` `:299` | `:527` | — | rc | `Seek :660` (`SeekPipeline :603` → `gst_element_seek`) |

Java-side: mute is pure Java (`GSTMediaPlayer.java:172-228`), `throwMediaErrorException` maps rc → `MediaError` (`:82`).

### 3b. C files in scope

| File | Lines | JNIEXPORT | Role | JNI usage | Externals (source grep) |
|---|---|---|---|---|---|
| `…/platform/gstreamer/GstMediaPlayer.cpp` | 550 | 18 | player downcalls | `Set{Long,Float,Double}ArrayRegion`, `ExceptionCheck/Clear` | none (forwards to `CPipeline`) |
| `GstMedia.cpp` | 215 | 2 (+`InitMedia` static `:54`) | media create/dispose | `GetStringUTFChars` ×2, `SetLongArrayRegion`, upcalls via `CLocator` statics and `CJavaInputStreamCallbacks::Property` | none directly; `CMediaManager::CreatePlayer` |
| `GstPlatform.cpp` | 108 | 3 | `JNI_OnLoad` (`g_pJVM`), `gstInitPlatform` | `new CJavaMediaWarningListener(env)` | none directly; `CMediaManager::GetInstance` |
| `GstJniUtils.cpp/.h` | 59+33 | 0 | `GstGetEnv`, `DetachThread` — **dead, zero callers** | `GetEnv`, `AttachCurrentThreadAsDaemon`, `DetachCurrentThread` | `g_private_get`, `g_private_set` |
| `GstMediaManager.cpp/.h` | 284+74 | 0 | GStreamer init, MainLoop thread, glib log hook | none (`LOGGER_LOGMSG` → logger slice) | `gst_init_check`, `gst_segtrap_set_enabled`, `g_thread_new`, `g_main_context_new`, `g_main_loop_new/run/quit/unref`, `g_log_set_default_handler`, `g_mutex_*`, `g_cond_*` |
| `GstPipelineFactory.cpp/.h` | 1101+93 | 0 | builds pipelines, wires `javasource` signals to `CStreamCallbacks` | none | `gst_element_factory_make`, `gst_pipeline_new`, `gst_bin_new/add/add_many`, `gst_element_link(_many)`, `g_signal_connect`, `g_signal_handlers_disconnect_by_func`, `g_object_set`, `gst_ghost_pad_new`, `gst_app_sink_set_caps`, `gst_bus_post`, `gst_message_new_error` |
| `GstAudioPlaybackPipeline.cpp/.h` | 2149+214 | 0 | audio pipeline, bus watch, state machine, probes | none (`m_pEventDispatcher->Send*`) | `gst_bus_create_watch`, `g_source_attach/set_callback/destroy`, `gst_element_set_state/seek/query_duration/query_position/get_state`, `gst_pad_add_probe/remove_probe`, `g_object_set/get`, `gst_message_parse_*`, `gst_pipeline_set_clock` |
| `GstAVPlaybackPipeline.cpp/.h` | 934+88 | 0 | video: appsink, demuxer pads, decoder probe | none | `gst_app_sink_pull_sample/pull_preroll`, `g_signal_connect`, `gst_pad_add_probe`, `gst_element_post_message`, `gst_message_new_application`, `gst_sample_*`, `gst_structure_*` |
| `GstAudioEqualizer.cpp/.h` | 176+85 | 0 | equalizer bands | none (`#include <jni/JniUtils.h>` unused) | `g_object_set/get`, `gst_child_proxy_get_child_by_index`, `gst_object_ref/unref` |
| `GstAudioSpectrum.cpp/.h` | 132+56 | 0 | spectrum element + `CBandsHolder` refcount | none (`#include <jni/JavaBandsHolder.h>`) | `g_object_set/get`, `g_atomic_*`, `gst_object_ref/unref` |
| `GstVideoFrame.cpp/.h` | 728+77 | 0 | frame wrapper + colour conversion | none | `gst_buffer_map/unmap`, `gst_buffer_new_wrapped_full`, `gst_sample_new/ref/unref`, `g_try_malloc`, `ColorConvert_YCbCr420p_to_*`, `ColorConvert_YCbCr422p_to_*` |
| `GstElementContainer.cpp/.h` | 56+76 | 0 | `std::map<ElementRole,GstElement*>` | none | none |
| Glue instantiated by this slice: `jni/JavaPlayerEventDispatcher.cpp/.h` 639+95, `jni/JavaMediaWarningListener.cpp/.h` 65+52, `jni/JniUtils.cpp/.h` 145+77, `Locator/Locator.cpp:54-133`, `Utils/MediaWarningDispatcher.cpp/.h` | | | see §6 | |

## 4. External dependencies

**Build (no CMake; Maven compiles Java only, `modules/javafx.media/pom.xml:69-70` emits JNI headers with `-h`).** Makefiles in the tree, not invoked by Maven:
- Windows `modules/javafx.media/src/main/native/jfxmedia/projects/win/Makefile`: `LIBS :89-95` = `gstreamer-lite.lib glib-lite.lib Winmm.lib kernel32.lib user32.lib comdlg32.lib advapi32.lib`; `CFLAGS :73-87` `-DJFXMEDIA_JNI_EXPORTS -DGSTREAMER_LITE -DTARGET_OS_WIN32=1`; `JNI_INCLUDES :54-55`, generated headers `:62`; sources `:126-166` (all 12 `platform/gstreamer/*.cpp`, `GstJniUtils.cpp :157`).
- Linux `projects/linux/Makefile`: `LDFLAGS :92` `-lgstreamer-lite` + `pkg-config --libs glib-2.0 gobject-2.0 gmodule-2.0 gthread-2.0` (system glib); JNI includes `:84-85`; `GstJniUtils.cpp :145`.
- macOS `projects/mac/Makefile`: `LDFLAGS :77-80` `-lobjc -framework Cocoa -framework CoreVideo`; `JFXMEDIA_LDFLAGS :105-108` `-lgstreamer-lite -lglib-lite`; `GstJniUtils.cpp :144`; separate `jfxmedia_avf` target `:209-270`.
- Generated headers included: `com_sun_media_jfxmediaimpl_platform_gstreamer_{GSTMediaPlayer,GSTMedia,GSTPlatform}.h`, `com_sun_media_jfxmedia_control_VideoFormat_FormatTypes.h` (`GstMedia.cpp:27`, unused there), `com_sun_media_jfxmediaimpl_NativeMediaPlayer.h` + `com_sun_media_jfxmedia_track_AudioTrack.h` (dispatcher), `jfxmedia_errors.h` (HeaderGen from `MediaError.java`; live copy `modules/javafx.media/target/gensrc/headers/jfxmedia_errors.h`, stale copy under `platform/ios`).

**Prebuilt binary `C:\SourceCode\caches\sdk\bin\jfxmedia.dll` (144 896 bytes).** `dumpbin -exports`: 55 symbols = `JNI_OnLoad` + 54 `Java_*` (21 of them in this slice); no non-JNI export. `dumpbin -dependents`: `gstreamer-lite.dll glib-lite.dll KERNEL32.dll MSVCP140.dll VCRUNTIME140.dll VCRUNTIME140_1.dll api-ms-win-crt-{runtime,stdio,heap}-l1-1-0.dll` — a real JNI-era build (not a stub); `Winmm/user32/comdlg32/advapi32/ole32` are **not** imported (so no `CoInitialize`, no timeBeginPeriod). `dumpbin -imports`: KERNEL32 = `CloseHandle CreateFileA DeleteCriticalSection EnterCriticalSection GetCurrentProcess GetCurrentProcessId GetCurrentThreadId GetEnvironmentVariableA GetSystemTimeAsFileTime InitializeCriticalSection InitializeSListHead IsDebuggerPresent IsProcessorFeaturePresent LeaveCriticalSection QueryPerformanceCounter RtlCaptureContext RtlLookupFunctionEntry RtlVirtualUnwind SetUnhandledExceptionFilter TerminateProcess UnhandledExceptionFilter WriteFile`; **`gstreamer-lite.dll`: 80 imports and `glib-lite.dll`: 46 imports, all by ordinal** — `dumpbin -exports gstreamer-lite.dll` = 155 functions / 0 names (`[NONAME]`), `glib-lite.dll` = 551 / 0 names. Consequence: symbol names are unrecoverable from the binaries (named evidence above is from source), and `SymbolLookup.libraryLookup("gstreamer-lite")` cannot resolve `gst_*` by name on Windows.

## 5. Triage table

| Function(s) | File:line | Verdict | Evidence (named symbol) | Parity gate | Replacement |
|---|---|---|---|---|---|
| `Java_…GSTMediaPlayer_gstInitPlayer` | `GstMediaPlayer.cpp:54` | JNI-GLUE | none in body; `NewGlobalRef`/`GetMethodID` via dispatcher; forwards to `CGstAudioPlaybackPipeline::Init` (`gst_bus_create_watch`, `g_source_attach`, `gst_element_set_state`) | n/a | `jfxm_player_init(media, cb, user)` |
| `…gstGetAudioEqualizer`, `…gstGetAudioSpectrum` | `:86`, `:102` | JNI-GLUE | none | n/a | `jfxm_player_get_audio_equalizer/spectrum` (raw pointer, lifetime = media) |
| `…gstGetAudioSyncDelay`, `…gstSetAudioSyncDelay` | `:118`, `:152` | JNI-GLUE | `SetLongArrayRegion`; target `g_object_get/set("ts-offset")` | n/a | `jfxm_player_get/set_audio_sync_delay(int64_t)` |
| `…gstPlay/gstPause/gstStop/gstFinish` | `:177/:204/:230/:256` | JNI-GLUE | target `gst_element_set_state` | n/a | `jfxm_player_play/pause/stop/finish` |
| `…gstGetRate/gstSetRate` | `:282/:316` | JNI-GLUE | `SetFloatArrayRegion`; target `gst_element_seek` | n/a | `jfxm_player_get/set_rate` |
| `…gstGetPresentationTime` | `:341` | JNI-GLUE | `SetDoubleArrayRegion`; target `gst_element_query_position` | n/a | `jfxm_player_get_presentation_time` |
| `…gstGetVolume/gstSetVolume/gstGetBalance/gstSetBalance` | `:375/:409/:434/:468` | JNI-GLUE | target `g_object_get/set("volume"/"panorama")` | n/a | `jfxm_player_get/set_volume`, `…balance` |
| `…gstGetDuration`, `…gstSeek` | `:493`, `:527` | JNI-GLUE | target `gst_element_query_duration`, `gst_element_seek` | n/a | `jfxm_player_get_duration`, `jfxm_player_seek` |
| `InitMedia` (static), `Java_…GSTMedia_gstInitNativeMedia` | `GstMedia.cpp:54`, `:181` | JNI-GLUE | `GetStringUTFChars` ×2, 4 synchronous upcalls; target `CMediaManager::CreatePlayer` → `gst_element_factory_make("javasource")` | n/a | `jfxm_media_create(location, contentType, sizeHint, streamCb, streamUser, audioCb, audioUser, &out)`; Java pre-computes `getStringLocation()`, `createConnectionHolder()`, `getAudioStreamConnectionHolder()` and `property(6)` |
| `Java_…GSTMedia_gstDispose` | `:197` | JNI-GLUE | `delete CMedia` → `CPipeline::Dispose` (`gst_element_set_state(NULL)`, `g_source_destroy`) | n/a | `jfxm_media_dispose` |
| `JNI_OnLoad` / `JNI_OnLoad_jfxmedia` | `GstPlatform.cpp:57-64` | JNI-GLUE | none (stores `g_pJVM`) | n/a | delete |
| `Java_…GSTPlatform_gstInitPlatform` | `:73` | JNI-GLUE | wraps `CMediaManager::GetInstance` → `CGstMediaManager::Init` (`gst_segtrap_set_enabled`, `gst_init_check`, `g_thread_new`, `g_main_context_new`, `g_main_loop_new`, `g_log_set_default_handler`) + `new CJavaMediaWarningListener` | n/a | `jfxm_platform_init(warningCb, user)` keeping the init in C |
| `GstGetEnv`, `DetachThread` | `GstJniUtils.cpp:41`, `:32` | JNI-GLUE (dead) | `g_private_get/set` + `JavaVM` vtable; **0 callers** (`grep -rn GstGetEnv src/main/native`) | n/a | delete file |
| `CJavaPlayerEventDispatcher::Init/Dispose/Send*(11 live)/Warning` | `jni/JavaPlayerEventDispatcher.cpp:64-537` | JNI-GLUE | `GetJavaVM`, `NewGlobalRef`, 13 `GetMethodID`, `CallVoidMethod`, `NewStringUTF`, attach/detach per call via `JniUtils.cpp:67/137` | n/a | `CFfiPlayerEventDispatcher` holding `JfxmPlayerCallbacks` + `void* user` |
| `CJavaPlayerEventDispatcher::CreateObject/CreateBoolean/CreateInteger/CreateLong/CreateDouble/CreateDuration` | `:542-639` | JNI-GLUE (dead) | `FindClass("java/lang/*", "javafx/util/Duration")`, `NewObjectA`; no callers | n/a | delete |
| `CJavaMediaWarningListener::Warning` | `jni/JavaMediaWarningListener.cpp:42` | JNI-GLUE (unreachable) | `FindClass("com/sun/media/jfxmediaimpl/MediaUtils")` + `CallStaticVoidMethod` per call; only caller `CMediaWarningDispatcher::Warning`, and `CMediaWarningDispatcher` is never instantiated (tree-wide grep) | n/a | delete (optionally a `warning` slot in `jfxm_platform_init`) |
| `CMediaWarningDispatcher::Warning` | `Utils/MediaWarningDispatcher.cpp:38` | PURE (dead) | none; never instantiated | n/a (dead) | delete |
| `CLocator::LocatorGetStringLocation/CreateConnectionHolder/GetAudioStreamConnectionHolder` | `Locator/Locator.cpp:54/:83/:108` | JNI-GLUE | `GetMethodID` + `CallObjectMethod` on the Java `Locator` on the caller thread | n/a | replace-in-java: `GSTMedia.init()` computes the values and passes them down |
| `CJavaEnvironment`, `GetJavaEnvironment`, `ThrowJavaException` | `jni/JniUtils.cpp:34-145` | JNI-GLUE | `AttachCurrentThreadAsDaemon`, `DetachCurrentThread`, `ThrowNew` (unused here) | n/a | delete once the last slice is JNI-free |
| `CGstMediaManager::Init/run_loop/StartMainLoop/GlibLogFunc/~` | `GstMediaManager.cpp:117/219/201/259/57` | OS-CALL | `gst_init_check`, `gst_segtrap_set_enabled`, `g_thread_new`, `g_main_loop_run`, `g_log_set_default_handler` | n/a | keep; called from `jfxm_platform_init` |
| `CMediaManager::GetInstance/CreateInstance/CreatePlayer/SetWarningListener` | `MediaManagement/MediaManager.cpp:63/75/121/109` | OS-CALL | `SetExceptionHandler()` (`SetUnhandledExceptionFilter`, Win32), constructs `CGstMediaManager`, `CPipelineFactory::CreatePlayerPipeline` | n/a | keep |
| `CGstPipelineFactory::CreatePlayerPipeline/CreatePipeline/Create{Wav,Aiff,HLS,Audio,AV}Pipeline/CreateAudioBin/CreateVideoBin/AttachToSource/CreateAudioSinkElement/CreateElement` | `GstPipelineFactory.cpp:66-1065` | OS-CALL | `gst_element_factory_make`, `gst_pipeline_new`, `gst_bin_add_many`, `gst_element_link`, `gst_ghost_pad_new` | n/a | keep |
| `CGstPipelineFactory::CreateSourceElement` + `SourceReadNextBlock/ReadBlock/CopyBlock/SeekData/Property/CloseConnection` | `:229-343` | OS-CALL | `g_signal_connect` on `javasource`, `g_object_set`; `delete callbacks :342` on close-connection | n/a | keep; the `CStreamCallbacks` impl becomes the input-stream slice's FFI table (needs a release slot) |
| `CGstAudioPlaybackPipeline::Init/PostBuildInit/Dispose` | `GstAudioPlaybackPipeline.cpp:117/216/386` | OS-CALL | `gst_bus_create_watch`, `g_source_attach`, `gst_pad_add_probe`, `gst_element_set_state`, `g_source_destroy` | n/a | keep |
| `…::Play/Pause/InternalPause/Stop/Finish/Seek/SeekPipeline/SetRate` | `:463/549/578/499/532/660/603/787` | OS-CALL | `gst_element_set_state`, `gst_element_seek`, `gst_element_get_state` | n/a | keep |
| `…::GetDuration/GetStreamTime` | `:703/:730` | OS-CALL | `gst_element_query_duration/position` | n/a | keep |
| `…::SetVolume/GetVolume/SetBalance/GetBalance/SetAudioSyncDelay/GetAudioSyncDelay` | `:877-989` | OS-CALL (borderline WRAPPER) | one `g_object_set/get` each, plus `IsPlayerState(Error)` gate and clamping; element handles private to the C++ pipeline; `g_object_set` is variadic and `glib-lite.dll` exports NONAME on Windows → Java cannot bind it by name | n/a | keep behind `jfxm_player_*` |
| `…::GetRate` | `:864` | PURE (trivial) | none (returns `m_fRate`) | n/a | keep (member of an OS-CALL class, not a boundary) |
| `…::BusCallback/BusCallbackDestroyNotify/SetPlayerState/UpdatePlayerState/SendTrackEvent/AudioSinkPadProbe/AudioSourcePadProbe/OnParserSrcPadAdded/BufferUnderrun/UpdateBufferPosition/HLSBufferStall/HLSBufferResume/IsCodecSupported/CheckCodecSupport/LoadDecoder` | `:1116-2148, :271, :1001-1115` | OS-CALL | GStreamer/glib callbacks on glib threads; `gst_message_parse_*`, `gst_pad_get_current_caps`, `gst_structure_get_*` | n/a | keep |
| `CGstAVPlaybackPipeline::*` (Init, PostBuildInit, Dispose, OnAppSinkHaveFrame, OnAppSinkPreroll, OnAppSinkVideoFrameDiscont, on_pad_added, no_more_pads, CheckQueueSize, queue_overrun/underrun, VideoDecoderSrcProbe, IsCodecSupported, CheckCodecSupport, LoadDecoder, SetEncodedVideoFrameRate) | `GstAVPlaybackPipeline.cpp:87-933` | OS-CALL | `gst_app_sink_pull_sample/pull_preroll`, `g_signal_connect`, `gst_pad_add_probe`, `gst_element_post_message` | n/a | keep |
| `CGstAudioEqualizer`, `CGstEqualizerBand` | `GstAudioEqualizer.cpp` | OS-CALL | `g_object_set/get`, `gst_child_proxy_get_child_by_index` | n/a | keep (equalizer slice defines its ABI) |
| `CGstAudioSpectrum`, `CBandsHolder::InitRef/AddRef/ReleaseRef` | `GstAudioSpectrum.cpp` | OS-CALL / PURE refcount | `g_object_set/get`, `g_atomic_*` | n/a | keep (spectrum slice) |
| `CGstVideoFrame::Init/Dispose/SetFrameCaps/ConvertToFormat/ConvertSwapRGB/ConvertFromYCbCr420p/ConvertFromYCbCr422` | `GstVideoFrame.cpp` | OS-CALL wrapping PURE-HOT | `gst_buffer_map/unmap`, `gst_sample_new`, `g_try_malloc`; `ColorConvert_*` in `Utils/ColorConverter.c` | **PARITY: unknown** for `ColorConvert_*` (experiment: byte-compare converted planes for a corpus of YCbCr420p/422 frames against a Java port) | keep; NativeVideoBuffer slice rules it |
| `GstElementContainer` | `GstElementContainer.cpp` | PURE | none (`std::map`) | n/a (internal helper, no observable output) | keep |
| `CPipeline` base defaults, `CMedia` | `PipelineManagement/Pipeline.cpp`, `MediaManagement/Media.cpp` | PURE | none | n/a | keep (`CMedia*` is the handle type) |

## 6. Upcall table (Java method ← C site ← thread)

| Java target (`NativeMediaPlayer` unless noted) | C site | Calling thread(s) | Payload |
|---|---|---|---|
| `sendPlayerMediaErrorEvent(I)V` | `JavaPlayerEventDispatcher.cpp:214` ← 25 sites (`GstAVPlaybackPipeline.cpp:238,367,441,494,638,648,915`; `GstAudioPlaybackPipeline.cpp:295,316,334,360,1090,1157,1206,1229,1245,1252,1266,1273,1298,1307,1546,1623,1931,2090`) | MainLoop GThread (bus watch), appsink streaming thread, demuxer/parser/decoder streaming threads, Java caller thread (via `SetPlayerState` from `Pause :560`) | `int` |
| `sendPlayerHaltEvent(Ljava/lang/String;D)V` | `:235` ← `AV:636,646` (`on_pad_added`), `Audio:293,314,332,358` (`OnParserSrcPadAdded`), `Audio:1305` (`BusCallback` ERROR) | demuxer/parser streaming thread; MainLoop thread | `NewStringUTF(message)`, `double` |
| `sendPlayerStateEvent(ID)V` | `:291` ← `Audio:1621` (`SetPlayerState`; callers `:560` Pause, `:1178` EOS, `:1290` ERROR, `:1865` `UpdatePlayerState` ← STATE_CHANGED) | MainLoop thread; Java caller thread (Pause) | state mapped `CPipeline::PlayerState` 0..7 → 100..107 |
| `sendNewFrameEvent(J)V` | `:312` ← `AV:365` (`OnAppSinkHaveFrame`, `new-sample`), `AV:439` (`OnAppSinkPreroll`, `new-preroll`) | appsink streaming thread | `jlong CVideoFrame*`; Java builds `NativeVideoBuffer` |
| `sendFrameSizeChangedEvent(II)V` | `:332` ← `AV:491` (`OnAppSinkVideoFrameDiscont`) | appsink streaming thread | 2 × `int` |
| `sendAudioTrack(ZJLjava/lang/String;ILjava/lang/String;IIF)V` | `:375` ← `Audio:1929` (`SendTrackEvent` ← `AudioSinkPadProbe :1983`, `AudioSourcePadProbe :2034`) | audio decoder/parser streaming thread | 2 × `NewStringUTF`, channel-mask bits remapped to `AudioTrack.*` constants |
| `sendVideoTrack(ZJLjava/lang/String;IIIFZ)V` | `:410` ← `AV:913` (`VideoDecoderSrcProbe`) | video decoder streaming thread | `NewStringUTF(name)`, scalars |
| `sendSubtitleTrack(ZJLjava/lang/String;ILjava/lang/String;)V` | `:440` | **no GStreamer caller** (only `platform/osx/avf/AVFMediaPlayer.mm:674`) | — |
| `sendMarkerEvent(Ljava/lang/String;D)V` | `:468` | **no caller in tree** | — |
| `sendBufferProgressEvent(DJJJ)V` | `:490` ← `Audio:2087` (`UpdateBufferPosition`) | MainLoop thread | 4 scalars |
| `sendDurationUpdateEvent(D)V` | `:508` ← `Audio:1155` (`BusCallback` DURATION_CHANGED) | MainLoop thread | `double` |
| `sendAudioSpectrumEvent(DDZ)V` | `:528` ← `Audio:1541` (`BusCallback` ELEMENT "spectrum") | MainLoop thread | 3 scalars |
| `sendWarning(ILjava/lang/String;)V` | `:194` ← `AV:377,449,474,479`, `Audio:1331,1354` | appsink streaming thread; MainLoop thread | `NewStringUTF` |
| `MediaUtils.nativeWarning(ILjava/lang/String;)V` (static) | `JavaMediaWarningListener.cpp:56` ← `CMediaWarningDispatcher::Warning` | **unreachable** (dispatcher never instantiated) | `FindClass` + `NewStringUTF` per call |
| `Locator.getStringLocation()`, `Locator.createConnectionHolder()`, `Locator.getAudioStreamConnectionHolder(ConnectionHolder)` | `Locator.cpp:74,101,127` ← `GstMedia.cpp:59,91,121` | Java caller thread, synchronously inside `gstInitNativeMedia` | `jstring`, `jobject` |
| `ConnectionHolder.property(II)I` | `JavaInputStreamCallbacks.cpp` (`Property`) ← `GstMedia.cpp:115` (prop 6) and `GstPipelineFactory.cpp:94,96,114` | Java caller thread (during create) and `javasource` task thread later | ints |
| `Throwable.toString()` | `JniUtils.cpp:101` (`reportException`) | any thread that upcalled | — |

**Thread map (Q5).** MainLoop = `g_thread_new("MainLoop", run_loop)` `GstMediaManager.cpp:159`, private `GMainContext`, bus watch attached `GstAudioPlaybackPipeline.cpp:148-154`. Streaming threads = GStreamer task threads of `javasource`, demuxer, parser, decoders, `appsink` (signals `new-sample`/`new-preroll` are emitted on the appsink's upstream streaming thread). Java caller threads = whoever calls `MediaPlayer` API (typically FX thread), the `EventQueueThread` (`playerInit` via `onNativeInit` `NativeMediaPlayer.java:551,584`), and the `java.util.Timer` media-pulse thread (`playerFinish()` `:1680` → `gstFinish`). No upcall is FX-thread-bound today (every target just posts to `EventQueueThread`), so **every stub uses `Arena.ofShared()`** and no marshalling must be added.

## 7. Proposed C ABI header (survivors)

```c
/* jfxmedia_api.h — gst-platform slice (Windows/Linux/macOS, C ABI, no jni.h) */
#include <stdint.h>
#ifdef _WIN32
#  define JFX_EXPORT __declspec(dllexport)
#else
#  define JFX_EXPORT __attribute__((visibility("default")))
#endif

typedef struct JfxmStreamCallbacks JfxmStreamCallbacks;   /* defined by the input-stream slice */
typedef struct JfxmPlayerCallbacks JfxmPlayerCallbacks;   /* §8 */
typedef struct JfxmWarningCallbacks JfxmWarningCallbacks; /* §8 */

/* Platform: replaces JNI_OnLoad + Java_…GSTPlatform_gstInitPlatform. Idempotent; gst_init_check etc. stay in C. */
JFX_EXPORT int32_t jfxm_platform_init(const JfxmWarningCallbacks* cb, void* user);

/* Media: replaces Java_…GSTMedia_gstInitNativeMedia (+ CLocator JNI statics) / gstDispose. Strings are UTF-8, NUL-terminated. */
JFX_EXPORT int32_t jfxm_media_create(const char* location, const char* content_type, int64_t size_hint,
                                     const JfxmStreamCallbacks* stream, void* stream_user,
                                     const JfxmStreamCallbacks* audio_stream /* NULL if none */, void* audio_stream_user,
                                     void** out_media);
JFX_EXPORT void    jfxm_media_dispose(void* media);   /* after return no player callback fires; Java closes the stub arena */

/* Player: replaces the 18 Java_…GSTMediaPlayer_* exports. media = CMedia*. Return = MediaError code. */
JFX_EXPORT int32_t jfxm_player_init(void* media, const JfxmPlayerCallbacks* cb, void* user); /* C copies *cb */
JFX_EXPORT void*   jfxm_player_get_audio_equalizer(void* media);  /* CAudioEqualizer*, owned by pipeline, valid until jfxm_media_dispose */
JFX_EXPORT void*   jfxm_player_get_audio_spectrum(void* media);   /* CAudioSpectrum*, same lifetime */
JFX_EXPORT int32_t jfxm_player_get_audio_sync_delay(void* media, int64_t* out_millis);
JFX_EXPORT int32_t jfxm_player_set_audio_sync_delay(void* media, int64_t millis);
JFX_EXPORT int32_t jfxm_player_play(void* media);
JFX_EXPORT int32_t jfxm_player_pause(void* media);
JFX_EXPORT int32_t jfxm_player_stop(void* media);
JFX_EXPORT int32_t jfxm_player_finish(void* media);
JFX_EXPORT int32_t jfxm_player_get_rate(void* media, float* out_rate);
JFX_EXPORT int32_t jfxm_player_set_rate(void* media, float rate);
JFX_EXPORT int32_t jfxm_player_get_presentation_time(void* media, double* out_seconds);
JFX_EXPORT int32_t jfxm_player_get_volume(void* media, float* out_volume);
JFX_EXPORT int32_t jfxm_player_set_volume(void* media, float volume);
JFX_EXPORT int32_t jfxm_player_get_balance(void* media, float* out_balance);
JFX_EXPORT int32_t jfxm_player_set_balance(void* media, float balance);
JFX_EXPORT int32_t jfxm_player_get_duration(void* media, double* out_seconds); /* -1.0 = unknown; Java maps to +Infinity as today */
JFX_EXPORT int32_t jfxm_player_seek(void* media, double seconds);

/* Layout guard for the Java-side StructLayout test */
JFX_EXPORT int32_t jfxm_sizeof_player_callbacks(void);
```

Java facade (`com.sun.media.jfxmediaimpl.JfxMediaNative`): `static final MethodHandle` per symbol, `invokeExact`, out-params via a per-call `Arena.ofConfined()` scalar (catalog P4; none of these is hot). `GSTMedia.refNativeMedia` may stay `long` at the `NativeMedia` boundary; convert once with `MemorySegment.ofAddress`. Registry `ConcurrentHashMap<Long, GSTMediaPlayer>` keyed by a Java counter passed as `user`.

## 8. Callback tables

```c
/* One slot per former jmethodID. All slots invoked on GStreamer threads (MainLoop or streaming), never on the FX thread.
 * Return 1 = delivered, 0 = failed (keeps the existing ERROR_JNI_SEND_* fallback semantics of CPlayerEventDispatcher).
 * A NULL slot is treated as "delivered". Strings are UTF-8, valid only for the duration of the call. */
typedef struct JfxmPlayerCallbacks {
    int32_t (*media_error)(void* user, int32_t error_code);
    int32_t (*halt)(void* user, const char* message, double time);
    int32_t (*state)(void* user, int32_t state /* 100..107 = NativeMediaPlayer.eventPlayer* */, double present_time);
    int32_t (*new_frame)(void* user, void* frame /* CVideoFrame*; Java takes a hold via the NativeVideoBuffer ABI */);
    int32_t (*frame_size)(void* user, int32_t width, int32_t height);
    int32_t (*audio_track)(void* user, int32_t enabled, int64_t track_id, const char* name, int32_t encoding,
                           const char* language, int32_t channels, int32_t channel_mask /* Java AudioTrack bits */, float sample_rate);
    int32_t (*video_track)(void* user, int32_t enabled, int64_t track_id, const char* name, int32_t encoding,
                           int32_t width, int32_t height, float frame_rate, int32_t has_alpha);
    int32_t (*subtitle_track)(void* user, int32_t enabled, int64_t track_id, const char* name, int32_t encoding, const char* language); /* OSX AVF only */
    int32_t (*marker)(void* user, const char* name, double time);   /* no caller in tree; keep for OSX parity or drop */
    int32_t (*buffer_progress)(void* user, double clip_duration, int64_t start, int64_t stop, int64_t position);
    int32_t (*duration_update)(void* user, double duration);
    int32_t (*audio_spectrum)(void* user, double time, double duration, int32_t query_timestamp);
    void    (*warning)(void* user, int32_t warning_code, const char* message);
} JfxmPlayerCallbacks;

/* Manager-level warnings (CMediaManager::m_pWarningListener). Path is dead today; slot may be NULL. */
typedef struct JfxmWarningCallbacks {
    void (*warning)(void* user, int32_t warning_code, const char* message);
} JfxmWarningCallbacks;
```

Java side: 13 stubs per player in one `Arena.ofShared()` owned by `GSTMediaPlayer`; targets catch `Throwable`, log, return 0. The C `CFfiPlayerEventDispatcher` replaces `CJavaPlayerEventDispatcher` and keeps the state-code mapping (`SendPlayerStateEvent :247-278`) and the channel-mask remap (`:357-373`) unchanged.

## 9. Deletion candidates (clear the gate — dead code or JNI marshalling, nothing to compare)

| Path | Lines | Copies | Reason | Java counterpart / parity assertion |
|---|---|---|---|---|
| `…/platform/gstreamer/GstJniUtils.cpp` + `.h` | 92 | 1 source, listed in 3 makefiles + `vs_project` + `xcode_project` | `GstGetEnv` has 0 callers; `DetachThread` only reachable through it | none needed; assert the library still loads and a player plays (no code path changes) |
| `jni/JavaPlayerEventDispatcher.cpp:539-639` (`CreateObject`…`CreateDuration`) | ~100 | 1 | no callers | none |
| `jni/JavaMediaWarningListener.cpp` + `.h` | 117 | 1 | only invoked via `CMediaWarningDispatcher`, which is never instantiated | none; if the slot is wanted, `JfxmWarningCallbacks` |
| `Utils/MediaWarningDispatcher.cpp` + `.h` | ~70 | 1 | never instantiated | none |
| `GstPlatform.cpp:50-64` (`g_pJVM`, `JNI_OnLoad`) | 15 | 1 | replaced by `jfxm_platform_init` | none |
| `Locator/Locator.cpp:54-133` + `Locator.h:52-55` JNI statics | ~85 | 1 | Java (`GSTMedia.init`) calls `getStringLocation()/createConnectionHolder()/getAudioStreamConnectionHolder()` itself and passes results | replace-in-java; behaviour-neutral (same Java methods, same thread, same order) |
| JNI bodies of `GstMediaPlayer.cpp`/`GstMedia.cpp`/`GstPlatform.cpp` | 873 → ~400 | 1 | migration to `jfxm_*` (not a Java reimplementation) | binding test: create player on a non-existent `file:` locator and assert the same `MediaError` code as JNI |
| `jni/JavaPlayerEventDispatcher.cpp/.h` | 734 → ~150 | 1 | replaced by `CFfiPlayerEventDispatcher` | event-sequence test against the JNI build (READY/PLAYING/track/duration events for a WAV) |
| `jni/JniUtils.cpp/.h` | 222 | 1 | shared JNI helper; delete after the last slice | none |

## 10. Kept native despite being pure

| Unit | Verdict | Reason |
|---|---|---|
| `GstElementContainer` (`std::map` over `GstElement*`) | PURE, parity n/a | internal C++ helper used by every pipeline class; not a boundary; no Java counterpart; deleting it means refactoring the OS-CALL classes |
| `CPipeline` base defaults, `CMedia`, `CGstAudioPlaybackPipeline::GetRate`, `CBandsHolder` refcount | PURE, parity n/a | members of OS-CALL classes; `CMedia*` is the handle |
| `ColorConvert_*` behind `CGstVideoFrame::ConvertToFormat` | PURE-HOT, **PARITY: unknown** | per-pixel YCbCr→ARGB/BGRA; output is pixels consumed by Prism; settle with a byte-compare of converted frames over a corpus (owned by the NativeVideoBuffer slice — not ruled here) |

## 11. Build/test touchpoints, risks, recommendation

**Build.** Nothing in Maven compiles this C; every step needs a hand rebuild through `jfxmedia/projects/{win,linux,mac}/Makefile` (add `jfxmedia_api.c`/`FfiPlayerEventDispatcher.cpp` to `CPP_SOURCES`, drop `GstJniUtils.cpp`, `JavaMediaWarningListener.cpp`, `Utils/MediaWarningDispatcher.cpp`; keep `$(JAVA_HOME)/include` until the logger/equalizer/video-buffer slices are done). `vs_project/jfxmedia/jfxmedia.vcxproj:43,87` and `xcode_project/JFXMedia.xcodeproj/project.pbxproj:62,178-179,396-397,642` also list `GstJniUtils`. Drop `-h …/gensrc/headers` from `modules/javafx.media/pom.xml:69-70` only when the whole module is JNI-free.

**Tests.** `modules/javafx.media` has no `src/test`; no test in `modules/*/src/test` or `tests/system/src` references `GSTMediaPlayer/GSTMedia/GSTPlatform/NativeMediaManager/NativeMediaPlayer`. Only `tests/system/src/test/java/test/robot/javafx/scene/NodeInitializationStressTest.java` touches `MediaView` (no playback) and `tests/manual/media/FXMediaPlayer` is a manual app outside the reactor. Nothing stubs or overrides these natives. First tests to add (per `jfx-ffm-testing`): symbol-resolution test for every `jfxm_*`, `jfxm_sizeof_player_callbacks()` vs `StructLayout.byteSize()`, hardware-free error-path test (`jfxm_media_create` on a missing `file:` → same `MediaError` as JNI), and a `FULL_TEST` playback test (WAV/AIFF) asserting the READY→PLAYING→FINISHED event order.

**Risks.**
- Stub-arena close point: `NativeMediaPlayer.dispose()` runs `playerDispose()` (`:1342`) *before* `media.dispose()` (`:1346`); the native `CFfiPlayerEventDispatcher` is deleted inside `gstDispose` (`~CPipeline` `Pipeline.cpp:59`). The arena must be closed **after** `jfxm_media_dispose` returns — from `GSTMedia.dispose()` or a post-`super` hook — never from `playerDispose()`.
- `BusCallbackDestroyNotify` may run on the MainLoop thread after `Dispose()` (`GstAudioPlaybackPipeline.cpp:1577-1600`), but it only frees `sBusCallbackContent`; `m_bIsDisposeInProgress` (`:395`) already gates dispatch, and `gst_element_set_state(NULL)` (`:402`) joins streaming threads, so no callback fires after `jfxm_media_dispose` returns.
- Re-entrant upcall during a downcall on the same thread: `Pause()` → `SetPlayerState(Paused)` (`:560`) → `state` callback synchronously on the Java caller thread; Java targets only enqueue, so no lock ordering issue, but the stub must not be confined.
- Attach/detach per event today (`JniUtils.cpp:67/137`) disappears; FFM upcalls are cheaper, no behaviour change.
- Strings: `NewStringUTF` treats GStreamer UTF-8 track names/messages as modified UTF-8 (non-BMP would be mangled today); `arena.allocateFrom(String)`/`getString` use real UTF-8 — a benign fix, but not byte-identical for exotic input. `GetStringUTFChars` on `location`/`contentType` are ASCII/percent-encoded in practice.
- 32-bit: `jlong_to_ptr` (`JniUtils.h:32-38`) is fine on LP64/LLP64; `SetAudioSyncDelay(long)` truncates on Windows — widening to `int64_t` changes behaviour only beyond ±2^31 ms.
- `gstGetAudioEqualizer/Spectrum` hand out pipeline-owned raw pointers (`Dispose :416-426` deletes them); Java `NativeAudioEqualizer/Spectrum` must not outlive `jfxm_media_dispose` (today `playerDispose` nulls them; keep).
- `gstInitPlatform` called twice leaks the previous listener (`GstPlatform.cpp:95-99`); make `jfxm_platform_init` idempotent.
- Blocking: `jfxm_player_*` calls take pipeline locks and `gst_element_set_state` (may block on preroll) — never `Linker.Option.critical`.
- No exceptions cross the boundary today (`MediaError` codes) — keep the same codes; `ERROR_JNI_UNEXPECTED` becomes unreachable.

**Recommendation.** Migrate this slice as a thin C ABI (`jfxm_platform_*`, `jfxm_media_*`, `jfxm_player_*` + `JfxmPlayerCallbacks`) in one commit per verified rebuild, and delete the dead glue (`GstJniUtils`, `CreateObject` family, warning listener + `MediaWarningDispatcher`, Locator JNI statics) in a separate commit. Order: (1) `jfxm_platform_init` + `JfxMediaNative` skeleton and symbol test; (2) `jfxm_media_create/dispose` with Java-side Locator pre-computation (depends on the input-stream slice's `JfxmStreamCallbacks`); (3) `jfxm_player_*` + `CFfiPlayerEventDispatcher`, flip `GSTMediaPlayer` to `JfxMediaNative`; (4) delete the JNI exports and `GstJniUtils`; (5) run the error-path binding test and a `FULL_TEST` playback. Do **not** attempt to bind `gst_*` from Java on Windows: the shipped `gstreamer-lite.dll`/`glib-lite.dll` export no names.

### Answers to the slice questions
1. See §3a table — 18 natives, all instance, out-params `long[1]`/`float[1]`/`double[1]` written with `Set*ArrayRegion`, return = `MediaError` code, each forwards to the `CPipeline` virtual named in the last column.
2. `JNI_OnLoad` caches only `g_pJVM` (`GstPlatform.cpp:51,62`); `gstInitPlatform` caches nothing itself — it forces `CMediaManager::GetInstance` (→ `gst_segtrap_set_enabled(false)`, `gst_init_check`, MainLoop `GThread` + `GMainContext`/`GMainLoop`, `g_log_set_default_handler`) and installs `CJavaMediaWarningListener` (stores `JavaVM*`; `FindClass`/`GetStaticMethodID` per call; never invoked). No `CoInitialize` (`WinDllMain.cpp` empty; no `ole32` import). Survives as `jfxm_platform_init`: the GStreamer/glib init in C, the listener as an optional callback slot. `gstInitNativeMediaManager/gstNewGSTMediaPlayer/gstGetMediaPlayer` do not exist.
3. `GstJniUtils`: `GstGetEnv` (attach-as-daemon cached in a `GPrivate`) and `DetachThread` (GPrivate destructor). Nothing but JNI glue, and dead.
4. `refNativeMedia` = `CMedia*` (`GstMedia.cpp:148`); `CMedia` owns `CPipeline` (`Media.cpp:58-62`); `CPipeline` owns the dispatcher (`Pipeline.cpp:59-60`), which holds the one global ref to the `GSTMediaPlayer` (`JavaPlayerEventDispatcher.cpp:74`, released `:176`). Deletion is Java-driven: `GSTMedia.dispose()` → `gstDispose` → `delete CMedia`. The stream `ConnectionHolder` global ref lives in `CJavaInputStreamCallbacks` (`:65/:288`), deleted by `SourceCloseConnection` (`GstPipelineFactory.cpp:342`) on a GStreamer thread. `Utils/JObjectPeers.m` is macOS/AVF only and unused by the GStreamer platform.
5. See the thread map under §6.
6. See §7/§8.
