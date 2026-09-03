# JNI audit — slice `plugins-libs` (GStreamer plugins, gstreamer-lite, glib-lite, 3rd_party, and the javasource interface)

Repo: `C:\SourceCode\jfx-ffm`, module `modules/javafx.media`. All paths below are relative to `modules/javafx.media/src/main/native/` unless stated. Scratch evidence (dumpbin dumps, dead-code script, ordinal maps, notes): `C:\Users\bestq\AppData\Local\Temp\claude\C--SourceCode-jfx-ffm\16d8edcc-5244-4cc6-b7f7-21d459e4d1ba\scratchpad\phase1\`.

## 1. Verdict

**Split, and most of the slice needs no work at all.** `gstreamer/plugins/**`, `gstreamer/gstreamer-lite/**` and `gstreamer/3rd_party/**` contain **zero JNI tokens** (grep for `jni.h|JNIEnv|JNIEXPORT|jobject|JavaVM|jclass|jmethodID|jfieldID|JNI_OnLoad` returns nothing in any of the three trees, nor anywhere under `gstreamer/`). They are already JVM-agnostic engine code: every plugin file reaches `gst_*`/`g_*` (gstreamer-lite/glib-lite), and the platform wrappers reach COM/DirectShow (`CoCreateInstance` x11, `CLSIDFromString` x6), Media Foundation (`MFStartup`, `MFTEnumEx`, `MFCreateSample`, `IMFTransform`), libav (`avcodec_send_packet`, `avformat_open_input`, `dlsym("sws_getContext")`) or file I/O (`CreateFileA`/`ReadFile`/`WriteFile` on Windows, `open`/`read`/`write`/`lseek`/`unlink` on POSIX). Verdict counts for the plugin/lite side: **~38 plugin source files, all `OS-CALL` (engine)**, 0 `WRAPPER`, 0 `PURE`, 0 `PURE-HOT`; no C ABI is designed for them and no FFM facade should be written — Java never calls them, only `jfxmedia` does (through 80 `gst_*` + 46 `g_*` imports, evidence in section 4). The only JNI in scope is the **jfxmedia side of the javasource interface**: `jni/JavaInputStreamCallbacks.cpp` (10 functions), the three static helpers in `Locator/Locator.cpp`, and `InitMedia` + the 2 `JNIEXPORT`s in `platform/gstreamer/GstMedia.cpp` — **15 `JNI-GLUE` functions** replaced by one callback table (`JfxmStreamCallbacks`, 9 slots) and two exports (`jfxm_media_create`, `jfxm_media_dispose`). Of the `PURE` candidates asked about in Q5: `Track/AudioTrack/VideoTrack/SubtitleTrack.cpp` and `MediaWarningDispatcher.cpp` are `PURE`, `PARITY: exact` (field pass-through; Java counterparts `com.sun.media.jfxmedia.track.*` and `MediaUtils.nativeWarning` exist) and become scalar callback payloads; `LowLevelPerf.cpp` is dead-by-config (`ENABLE_LOWLEVELPERF 0`); `Locator.cpp` is 3 JNI-GLUE helpers around a 5-field struct; `ColorConverter.c` is `PURE-HOT` with `PARITY: unknown` (SSE2 path on x86 vs a partially unimplemented generic-C path on macOS arm64) and **stays native**. The dead-code sweep found **77 unreferenced source files** (28 iOS files incl. a stale `jfxmedia_errors.h`, `NativeVideoConverter.cpp` whose Java class does not exist, `AutoLock.h`, 40 gstreamer-lite files, 10 3rd_party files) plus the unwired `vs_project/`, `xcode_project/`, `headergen` and `def-*.pl` build inputs.

## 2. Summary counts (this slice)

| Item | Count | Where |
|---|---|---|
| Java `native` methods in scope | 2 | `GSTMedia.gstInitNativeMedia`, `GSTMedia.gstDispose` (`platform/gstreamer/GSTMedia.java:92,96`) |
| `JNIEXPORT` in scope | 2 live + 2 orphan (dead file) | `GstMedia.cpp:181,197`; `jni/com_sun_media_jfxmediaimpl_NativeVideoConverter.cpp:322,358` (no Java class, not compiled) |
| Non-JNI exports | 1 | `fxplugins.c:55` `gst_plugin_desc` (data; loaded by gstreamer-lite) |
| `JNIEXPORT` in plugins/lite/3rd_party | **0** | grep proof, section 3 |
| Upcalls (`Call*Method`) | 12 | 8 in `JavaInputStreamCallbacks.cpp`, 3 in `Locator.cpp`, 1 in `JniUtils.cpp:reportException` (`Throwable.toString`) |
| Field reads | 1 | `GetObjectField(ConnectionHolder.buffer)` + `GetDirectBufferAddress` (`JavaInputStreamCallbacks.cpp:211-212`) |
| Cached IDs | 1 `jfieldID` + 8 `jmethodID` static (`:34-42`), 3 function-static `jmethodID` in `Locator.cpp:61,89,116` | |
| `FindClass` by name | 1 (`com/sun/media/jfxmedia/locator/ConnectionHolder`, `:79`) + `java/lang/Throwable` in `JniUtils.cpp` | |
| Global refs | 1 `NewGlobalRef` (`:65`) / 1 `DeleteGlobalRef` (`:288`) — **unmatched** on the `Init` failure paths `GstMedia.cpp:95-101,129-135` | |
| Critical arrays | 0 | |
| Other array/string access | `GetStringUTFChars` x2 (`GstMedia.cpp:58,76`, modified UTF-8), `SetLongArrayRegion` x1 (`:149`), `GetDirectBufferAddress` x1 | |
| Thread attaches | 9 `CJavaEnvironment(m_jvm)` constructions (one per method, `:63,148,167,187,206,223,241,259,277,295`), `GetJavaVM` at `:56`; attach = `AttachCurrentThreadAsDaemon` + `DetachCurrentThread` per call (`JniUtils.cpp`) | |
| Exceptions thrown from C | 0 `ThrowNew` in slice | |
| Exceptions checked/cleared | every upcall (`clearException`/`reportException`), `GstMedia.cpp:150` | |
| `JNI_OnLoad` in slice | 0 (lives in `JniUtils.cpp`/platform, other slice) | |
| Tests touching this surface | 0 | section 11 |

## 3. Java classes and C files

### Java side

| Class | native methods | Role in this slice |
|---|---|---|
| `com.sun.media.jfxmediaimpl.platform.gstreamer.GSTMedia` | `int gstInitNativeMedia(Locator, String contentType, long sizeHint, long[] mediaHandle)` (instance), `void gstDispose(long)` (instance) | The downcall that creates the stream callbacks; symbols `Java_com_sun_media_jfxmediaimpl_platform_gstreamer_GSTMedia_gstInitNativeMedia` / `_gstDispose`, both exported by `jfxmedia.dll` (dumpbin ordinals 54, 53) |
| `com.sun.media.jfxmedia.locator.Locator` | none | Upcall targets `getStringLocation()` (`:572`), `createConnectionHolder()` (`:595`), `getAudioStreamConnectionHolder(ConnectionHolder)` (`:630`) |
| `com.sun.media.jfxmedia.locator.ConnectionHolder` (+ `FileConnectionHolder`, `URIConnectionHolder`, `MemoryConnectionHolder`, `HLSConnectionHolder`) | none | Upcall targets `needBuffer()`, `readNextBlock()` (`:78`), `readBlock(long,int)` (`:103`), `isSeekable()`, `isRandomAccess()`, `seek(long)` (`:132`), `closeConnection()` (`:138`), `property(int,int)` (`:156`); field `buffer` = `ByteBuffer.allocateDirect(4096)` (`:51`), replaced by a larger direct buffer in `FileConnectionHolder.readBlock` (`:206-210`) and by a *slice* in `MemoryConnectionHolder` (`:397,435`) — identity changes, C re-reads the field on every copy |
| `HLSConnectionHolder` | none | property codes `HLS_PROP_GET_DURATION=1, GET_HLS_MODE=2, GET_MIMETYPE=3, LOAD_SEGMENT=4, SEGMENT_START_TIME=5, HAS_AUDIO_EXT_STREAM=6`, `HLS_VALUE_MIMETYPE_{MP2T=1,MP3=2,FMP4=3,AAC=4}`, `HLS_VALUE_FLOAT_MULTIPLIER=1000` (`:76-87`); duplicated as `#define`s in `javasource.c:41-44`, `GstPipelineFactory.cpp:44-45`, `GstMedia.cpp:44` |

### C side — jfxmedia interface files

| File | Lines | JNI? | Content |
|---|---|---|---|
| `jni/JavaInputStreamCallbacks.cpp` / `.h` | 310 / 66 | yes | `CJavaInputStreamCallbacks : CStreamCallbacks`, 10 methods, all JNI |
| `Locator/LocatorStream.h` / `.cpp` | 99 / 37 | no | `CStreamCallbacks` pure-virtual interface (`:32-77`), `CLocatorStream` holding main + audio callbacks |
| `Locator/Locator.cpp` / `.h` | 138 / ~70 | yes (`Locator.h` includes `jni/JniUtils.h`) | `CLocator` (type, contentType, location, sizeHint) + 3 static JNI helpers `:55-136` |
| `platform/gstreamer/GstMedia.cpp` | 216 | yes | `InitMedia` (`:54-172`) builds callbacks, 2 `JNIEXPORT`s |
| `platform/gstreamer/GstPipelineFactory.cpp` (`:80-116, 229-343`) | — | no | `CreatePipeline` property probes, `CreateSourceElement`, 6 signal trampolines |
| `jni/JniUtils.cpp` / `.h` (other slice, used here) | ~130 / ~75 | yes | `CJavaEnvironment` attach/detach, `reportException` |

### C side — plugin files (all JNI-free; compiled per `gstreamer/projects/{win,linux,mac}/fxplugins/Makefile` and `linux/avplugin/Makefile`)

| File | Lines | Functions (grep of definitions) | Platforms |
|---|---|---|---|
| `plugins/fxplugins.c` | 71 | `fxplugins_init`, `gst_plugin_desc` | win, linux, mac |
| `plugins/fxplugins_common.h` | 67 | enums `JFX_CODEC_ID_*`, `JFX_GST_ERROR*`, `FX_EVENT_RANGE_READY` | header |
| `plugins/javasource/javasource.c` / `.h` | 953 / 54 | 26 (`java_source_get_type`, `_class_init`, `_init`, `_set/get_property`, `_finalize`, `_activatemode`, `_perform_seek`, `_event`, `_loop`, `_query`, `_getrange`, `_change_state`, `_plugin_init`, …) | all |
| `plugins/javasource/marshal.c` / `.h` / `marshal.in` / `genmarshal.sh` | 246 / 52 / 14 / 4 | 5 generated `source_marshal_*` closures (`glib-genmarshal --prefix=source_marshal`) | all |
| `plugins/progressbuffer/progressbuffer.c` / `.h` | 1166 / 52 | 48 | all |
| `plugins/progressbuffer/hlsprogressbuffer.c` / `.h` | 633 / 54 | 27 | all |
| `plugins/progressbuffer/cache.h` | 63 | API `create_cache`, `cache_write_buffer`, `cache_read_buffer(_from_position)`, `cache_set_{read,write}_position`, `cache_has_enough_data` | header |
| `plugins/progressbuffer/win32/filecache.c` | 193 | 8 | win |
| `plugins/progressbuffer/posix/filecache.c` | 196 | 8 | linux, mac |
| `plugins/dshowwrapper/dshowwrapper.cpp` / `.h` | 3341 / 174 | 62 | win |
| `plugins/dshowwrapper/Sink.cpp` / `.h`, `Src.cpp` / `.h`, `Allocator.cpp` / `.h` | 745/147, 326/100, 219/82 | 26, 18, 11 | win (+ `3rd_party/baseclasses` static lib, `Makefile.BaseClasses`) |
| `plugins/mfwrapper/mfwrapper.cpp` / `.h`, `mfgstbuffer.cpp` / `.h` | 2033/122, 303/92 | 55, 11 | win |
| `plugins/av/{fxavcodecplugin,avelement,decoder,audiodecoder,videodecoder,mpegtsdemuxer}.c` + `.h`, `avdefines.h` | 59, 101, 228, 904, 969, 1320 (+ headers 91/73/91/107/53/70) | 2, 8, 14, 22, 29, 50 | linux only (`avplugin` -> `libavplugin*.so`) |

## 4. External dependencies

### Build side (Makefiles; nothing in Maven builds these — `modules/javafx.media/pom.xml:40-44` says so explicitly)

| Library | Makefile | Sources | Link inputs | JNI includes |
|---|---|---|---|---|
| `jfxmedia.dll` | `jfxmedia/projects/win/Makefile` | 41 `.cpp` + `Utils/ColorConverter.c` (`:126-168`) | `gstreamer-lite.lib glib-lite.lib Winmm.lib kernel32 user32 comdlg32 advapi32` (`:89-95`) | `-I$(JAVA_HOME)/include`, `include/win32`, `-I$(GENERATED_HEADERS_DIR)` (`:54-62`); flags `-DJFXMEDIA_JNI_EXPORTS -DGSTREAMER_LITE -DGST_DISABLE_LOADSAVE -DGST_REMOVE_DEPRECATED -DG_DISABLE_DEPRECATED -EHsc -fp:precise` |
| `libjfxmedia.so` | `jfxmedia/projects/linux/Makefile` | same list minus `LowLevelPerf.cpp`, plus `Utils/posix/posix_critical_section.cpp` (`:112-150`) | `-lgstreamer-lite` + **system** `pkg-config --libs glib-2.0 gobject-2.0 gmodule-2.0 gthread-2.0` (`:81,92`) — glib-lite is *not* built on Linux | `-I$(JAVA_HOME)/include`, `include/linux` |
| `libjfxmedia.dylib` + `libjfxmedia_avf.dylib` | `jfxmedia/projects/mac/Makefile` | jfxmedia list + `Utils/{JObjectPeers,JavaUtils,MTObjectProxy}.m`, `platform/osx/{OSXPlatform,OSXMediaPlayer,CVVideoFrame}.mm`; AVF: `platform/osx/avf/*` (`:112-158, 221-227`) | `-lgstreamer-lite -lglib-lite -lobjc -framework Cocoa -framework CoreVideo`; AVF adds `AVFoundation CoreMedia Accelerate AudioUnit MediaToolbox` | `-I$(JAVA_HOME)/include`, `include/darwin` |
| `fxplugins.dll` | `gstreamer/projects/win/fxplugins/Makefile` | `javasource/{javasource,marshal}.c`, `progressbuffer/{progressbuffer,win32/filecache,hlsprogressbuffer}.c`, `fxplugins.c`, `dshowwrapper/*.cpp`, `mfwrapper/*.cpp` (`:51-63`) | `glib-lite gstreamer-lite winmm strmiids kernel32 user32 shell32 advapi32 ole32 oleaut32 Mfplat mfuuid` + `baseclasses.lib` (`:18-30`) | **none**; `-DENABLE_PULL_MODE=1 -DENABLE_SOURCE_SEEKING=1 -DGST_DISABLE_GST_DEBUG -DG_DISABLE_ASSERT -DINITGUID` |
| `libfxplugins.so` / `.dylib` | `linux/fxplugins/Makefile:66-71`, `mac/fxplugins/Makefile:57-62` | `fxplugins.c`, `progressbuffer/{progressbuffer,hlsprogressbuffer,posix/filecache}.c`, `javasource/{javasource,marshal}.c` | linux: `-lgstreamer-lite` + system glib/gobject; mac: `-lgstreamer-lite -lglib-lite` | none |
| `libavplugin*.so` | `linux/avplugin/Makefile:74-79` | `av/*.c` | `-lgstreamer-lite -lavcodec -lavformat` (`:63-66`); `libswscale` loaded at runtime via `dlopen("libswscale.so")`/`dlsym("sws_getContext")` (`videodecoder.c:455-467`) | none |
| `gstreamer-lite`, `glib-lite`, `libffi`, `baseclasses` | `gstreamer/projects/{win,linux,mac}/…` | upstream subsets | — | none |

### Export mechanism of the lite libraries

* **Windows:** `gstreamer-lite.dll` exports **155** symbols, all `NONAME` by ordinal, from `gstreamer/projects/win/gstreamer-lite.def` (linked with `-def:` at `win/gstreamer-lite/Makefile:34`); `glib-lite.dll` exports **551**, likewise from `3rd_party/glib/build/win32/vs100/glib-lite.def` (`win/glib-lite/Makefile:36`). Under MSVC with `GSTREAMER_LITE`, `GST_API` expands to nothing (`gstreamer-lite/gstreamer/gst/gstconfig.h:41-50`), so nothing is `dllexport`ed from source — the `.def` is the only export list, regenerated by `src/tools/native/def-gstlite.pl` / `def-glib.pl` (not wired into any pom). **Consequence for FFM: `SymbolLookup` cannot find `gst_*`/`g_*` by name on Windows; Java must never bind gstreamer-lite/glib-lite directly.** The "WRAPPER -> Java binds the OS symbol" route is closed for gst on Windows, which independently justifies keeping all GStreamer access inside `jfxmedia`.
* **Linux/macOS:** no `-fvisibility`, version script or exported-symbols list in any Makefile (grep of `visibility|version-script|exported_symbols` in the seven Makefiles finds only `-Wl,--gc-sections`/`-install_name`); `GST_API` = `GST_EXPORT` = `__attribute__((visibility("default")))` under `__GNUC__`, so every non-static symbol is exported.

### Prebuilt binaries (`C:\SourceCode\caches\sdk\bin`, dumpbin 14.44)

**`jfxmedia.dll` imports** — `gstreamer-lite.dll` (80 ordinals), `glib-lite.dll` (46 ordinals), `KERNEL32.dll` (22), `MSVCP140.dll` (1: `std::_Xlength_error`), `VCRUNTIME140.dll` (12: `memcpy memmove memset memcmp strstr _purecall __std_terminate _CxxThrowException __C_specific_handler …`), `VCRUNTIME140_1.dll` (`__CxxFrameHandler4`), `api-ms-win-crt-{runtime,stdio,heap}` (`malloc free _callnewh __stdio_common_vsprintf` + init). **No `jvm.dll` import** (JNI is reached through the `JNIEnv` function table, never by import). KERNEL32 named imports: `InitializeSListHead GetSystemTimeAsFileTime GetCurrentThreadId QueryPerformanceCounter IsDebuggerPresent IsProcessorFeaturePresent TerminateProcess GetCurrentProcess UnhandledExceptionFilter RtlVirtualUnwind RtlLookupFunctionEntry RtlCaptureContext EnterCriticalSection LeaveCriticalSection DeleteCriticalSection CloseHandle GetEnvironmentVariableA CreateFileA WriteFile SetUnhandledExceptionFilter GetCurrentProcessId InitializeCriticalSection` — attributable to the CRT security cookie/SEH set, `Utils/win32/WinCriticalSection.cpp`, and `Utils/win32/WinExceptionHandler.cpp:45,64,101,116` (crash-log file). Of the linked `Winmm/user32/comdlg32/advapi32`, none is actually imported.

Ordinals mapped back through the `.def` files:

* gstreamer-lite (80): `gst_app_sink_get_type gst_app_sink_pull_preroll gst_app_sink_pull_sample gst_bin_add gst_bin_add_many gst_bin_get_type gst_bin_iterate_elements gst_bin_new gst_bin_recalculate_latency gst_bin_remove gst_buffer_get_size gst_buffer_map gst_buffer_new_wrapped_full gst_buffer_unmap gst_bus_create_watch gst_bus_post gst_caps_get_size gst_caps_get_structure gst_caps_new_simple gst_child_proxy_get_child_by_index gst_child_proxy_get_type gst_element_add_pad gst_element_factory_make gst_element_get_factory gst_element_get_state gst_element_get_static_pad gst_element_get_type gst_element_link gst_element_link_many gst_element_post_message gst_element_provide_clock gst_element_query_duration gst_element_query_position gst_element_seek gst_element_set_state gst_element_sync_state_with_parent gst_ghost_pad_new gst_init_check gst_iterator_free gst_iterator_next gst_iterator_resync gst_message_get_structure gst_message_new_application gst_message_new_error gst_message_parse_error gst_message_parse_info gst_message_parse_state_changed gst_message_parse_warning gst_mini_object_copy gst_mini_object_ref gst_mini_object_unref gst_object_get_type gst_object_ref gst_object_unref gst_pad_add_probe gst_pad_get_current_caps gst_pad_link gst_pad_remove_probe gst_pad_set_active gst_pipeline_get_bus gst_pipeline_get_type gst_pipeline_new gst_pipeline_set_clock gst_resource_error_quark gst_sample_get_buffer gst_sample_get_caps gst_sample_new gst_segtrap_set_enabled gst_stream_error_quark gst_structure_get_boolean gst_structure_get_clock_time gst_structure_get_fraction gst_structure_get_int gst_structure_get_name gst_structure_get_string gst_structure_get_value gst_structure_has_name gst_structure_new_empty gst_structure_set gst_value_list_get_value`
* glib-lite (46): `g_ascii_strcasecmp g_atomic_int_add g_atomic_int_dec_and_test g_atomic_int_set g_atomic_pointer_get g_atomic_pointer_set g_cond_clear g_cond_init g_cond_signal g_cond_wait g_error_free g_error_new g_free g_get_current_time g_log_set_default_handler g_main_context_new g_main_context_unref g_main_loop_new g_main_loop_quit g_main_loop_run g_main_loop_unref g_mutex_clear g_mutex_init g_mutex_lock g_mutex_unlock g_object_get g_object_set g_object_unref g_print g_signal_connect_data g_signal_handlers_disconnect_matched g_source_attach g_source_destroy g_source_set_callback g_source_unref g_str_has_prefix g_thread_new g_try_malloc g_type_check_instance_cast g_type_check_instance_is_a g_value_get_boolean g_value_get_float g_value_get_int64 g_value_get_object g_value_reset g_value_unset`

**`jfxmedia.dll` exports** — 55: `JNI_OnLoad` + 54 `Java_*`: `Logger` (2), `NativeAudioEqualizer` (5), `NativeAudioSpectrum` (7), `NativeEqualizerBand` (6), `NativeVideoBuffer` (13), `GSTMediaPlayer` (18), `GSTMedia` (2), `GSTPlatform` (1). Nothing else. Notable absences: no `NativeAudioClip_*` and no `NativeVideoConverter_*` exports (the former has no desktop C implementation — other slice; the latter's C file is dead, below).

**`fxplugins.dll`** — 1 export `gst_plugin_desc`; imports glib-lite, gstreamer-lite (99 ordinals), KERNEL32 (files/threads/events, `LoadLibraryA`/`GetProcAddress`), USER32 (DirectShow message pump: `PeekMessageA DispatchMessageA MsgWaitForMultipleObjects PostThreadMessageA RegisterWindowMessageA GetQueueStatus`), ole32 (`CoCreateInstance CoInitialize(Ex) CoUninitialize CLSIDFromString CoTaskMemAlloc/Free CoFreeUnusedLibraries`), WINMM (`timeSetEvent timeKillEvent timeBeginPeriod timeEndPeriod`), Media Foundation (`MFStartup MFShutdown MFTEnumEx MFCreateMediaType MFCreateSample MFCreateMemoryBuffer`). **`gstreamer-lite.dll`** imports glib-lite, WS2_32, KERNEL32, USER32, ole32, DSOUND (directsoundsink) + CRT. **`glib-lite.dll`** imports CRT, WS2_32, KERNEL32, USER32, SHELL32, ADVAPI32, ole32. None imports `jvm.dll`.

## 5. Triage table

Evidence = named external symbol(s). Verdicts for plugin files are per file (function counts given); the per-function verdict is uniform within each file because every function either calls into gstreamer-lite/glib-lite or the named OS API, or is a static helper of one that does.

| Function / file | Verdict | Evidence | Parity | Replacement |
|---|---|---|---|---|
| `javasource.c` — 26 fns (`java_source_loop:532`, `_getrange:809`, `_perform_seek:418`, `_query:726`, `_change_state:874`, `_class_init:176`, `_init:289`, `_set/get_property:314,349`, `_finalize:362`, `_activatemode:378`, `_event:511`, `_get_type:132`, `_plugin_init:945`, …) | OS-CALL (engine) | `gst_pad_start_task`, `gst_pad_push`, `gst_pad_push_event`, `gst_buffer_new_allocate`, `gst_buffer_map`, `g_signal_emit`, `gst_event_new_segment`, `g_type_register_static_simple`, `gst_element_register` | n/a | none; stays as is. Its signal contract is the FFM callback table (section 8) |
| `marshal.c` — 5 generated closures | OS-CALL (glib closure ABI) | `g_value_get_*`/`GClosure` marshaller; generated from `marshal.in` by `genmarshal.sh` | n/a | none (regenerable) |
| `fxplugins.c` `fxplugins_init`, `gst_plugin_desc` | OS-CALL | `gst_element_register` via sub-inits; `GstPluginDesc` consumed by `gst_plugin_load_file` | n/a | none |
| `progressbuffer.c` — 48 fns | OS-CALL | `gst_pad_push_event` x7, `gst_pad_start_task` x2, `gst_element_post_message` x2, `gst_pad_pull_range`, `g_thread_new`; cache via `filecache.c` | n/a | none |
| `hlsprogressbuffer.c` — 27 fns | OS-CALL | `gst_pad_push_event` x5, `gst_element_post_message` x4, `gst_pad_start_task` x3 | n/a | none |
| `win32/filecache.c` — 8 fns | OS-CALL | `CreateFile` x2, `ReadFile` x2, `WriteFile`, `SetFilePointer`, `GetTempPath`, `GetTempFileName`, `CloseHandle` x2 | n/a | none |
| `posix/filecache.c` — 8 fns | OS-CALL | `open`, `read` x2, `write`, `lseek`, `close`, `unlink` | n/a | none |
| `dshowwrapper.cpp` — 62 fns | OS-CALL | `CoCreateInstance` x11, `CLSIDFromString` x6, `CoInitialize`/`CoUninitialize` x3 each, `IPin`/`IBaseFilter`/`IMediaControl` COM calls, `WaitForSingleObject`; headers `Bdaiface.h Dvdmedia.h Ks.h Codecapi.h dmodshow.h Dmoreg.h Wmcodecdsp.h` | n/a | none |
| `Sink.cpp` (26), `Src.cpp` (18), `Allocator.cpp` (11) | OS-CALL | DirectShow base classes: `IMediaSample`, `IMemAllocator`, `IPin` | n/a | none |
| `mfwrapper.cpp` — 55 fns | OS-CALL | `MFStartup`, `MFShutdown`, `MFTEnumEx`, `MFCreateMediaType` x4, `MFCreateSample` x2, `MFCreateMemoryBuffer`, `IMFTransform` x10, `CoInitializeEx`, `CoCreateInstance` | n/a | none |
| `mfgstbuffer.cpp` — 11 fns | OS-CALL | implements the `IMFMediaBuffer` COM vtable over a `GstBuffer` | n/a | none |
| `av/decoder.c` (14), `audiodecoder.c` (22), `videodecoder.c` (29), `mpegtsdemuxer.c` (50), `avelement.c` (8), `fxavcodecplugin.c` (2) | OS-CALL (codec) | `avcodec_find_decoder`, `avcodec_open`, `avcodec_send_packet`, `avcodec_receive_frame`, `av_frame_alloc`, `avformat_open_input`, `av_read_frame`, `av_log_set_callback`, `dlopen("libswscale.so")`+`dlsym("sws_getContext")` (`videodecoder.c:455-467`) | n/a | none |
| `gstreamer-lite/**`, `3rd_party/{glib,libffi,baseclasses}` | OS-CALL (engine) | the engine itself; imports WS2_32/KERNEL32/USER32/ole32/DSOUND | n/a | none — do not touch except the Windows `.def` when jfxmedia needs a new symbol |
| `CJavaInputStreamCallbacks::Init` (`:51`) | JNI-GLUE | `GetJavaVM`, `NewGlobalRef`, `FindClass`, `GetFieldID`, `GetMethodID` x8 | n/a | deleted; table copied by value in `jfxm_media_create` |
| `::NeedBuffer` (`:145`), `::IsSeekable` (`:221`), `::IsRandomAccess` (`:239`) | JNI-GLUE | `CallBooleanMethod` | n/a | table slots `need_buffer`, `is_seekable`, `is_random_access` |
| `::ReadNextBlock` (`:164`), `::ReadBlock` (`:184`) | JNI-GLUE | `CallIntMethod`; exception -> `-2` | n/a | slots `read_next_block`, `read_block`; upcall stub catches `Throwable` and returns `-2` |
| `::CopyBlock` (`:204`) | JNI-GLUE | `GetObjectField`, `GetDirectBufferAddress`, `memcpy` | n/a | slot `copy_block(user, dst, size)`; Java does `MemorySegment.copy` from `holder.buffer` into `dst.reinterpret(size)` |
| `::Seek` (`:257`), `::Property` (`:293`), `::CloseConnection` (`:275`) | JNI-GLUE | `CallLongMethod`, `CallIntMethod`, `CallVoidMethod`, `DeleteGlobalRef` | n/a | slots `seek`, `property`, `close_connection` |
| `CLocator::LocatorGetStringLocation` (`Locator.cpp:55`), `::CreateConnectionHolder` (`:83`), `::GetAudioStreamConnectionHolder` (`:110`) | JNI-GLUE | `GetMethodID`, `CallObjectMethod` | n/a | deleted; Java calls `Locator.getStringLocation()/createConnectionHolder()/getAudioStreamConnectionHolder()` itself before the downcall |
| `CLocator` ctors/`GetType`/`GetSizeHint`/`GetContentType`/`GetLocation`; `CLocatorStream::CLocatorStream` (`LocatorStream.cpp:30`) | PURE | none (std::string copies; `LOGGER_LOGMSG`) | exact (data carrier) | shrink to a plain struct `{content_type, location, size_hint, cb, audio_cb}`; stays C only because `GstPipelineFactory` consumes it |
| `InitMedia` (`GstMedia.cpp:54`), `Java_…GSTMedia_gstInitNativeMedia` (`:181`), `_gstDispose` (`:197`) | JNI-GLUE | `GetStringUTFChars` x2, `SetLongArrayRegion`, `ExceptionCheck`; then `CMediaManager::CreatePlayer` | n/a | `jfxm_media_create` / `jfxm_media_dispose` |
| `CGstPipelineFactory::CreateSourceElement` (`:229`) | OS-CALL | `gst_element_factory_make("javasource")`, `g_signal_connect_data` x6, `g_object_set`, `gst_bin_new`, `gst_bin_add_many`, `gst_element_link` | n/a | stays; now connects the trampolines to a table-backed `CStreamCallbacks` |
| `CGstPipelineFactory::Source{ReadNextBlock,ReadBlock,CopyBlock,SeekData,Property,CloseConnection}` (`:307-343`) | JNI-GLUE (dispatch shim, no JNIEnv) | none in body; `g_signal_handlers_disconnect_by_func` + `delete callbacks` in `SourceCloseConnection` | n/a | keep (35 lines) as the GSignal -> table dispatch; Java-side direct `g_signal_connect` of upcall stubs is possible but would expose GObject to Java and is closed on Windows by the ordinal exports |
| `CTrack`, `CAudioTrack`, `CVideoTrack`, `CSubtitleTrack` (`PipelineManagement/*.cpp`, 259 lines + headers) | PURE | none (ctors/getters; includes `Track.h`, `VSMemory.h`, `stdint.h`) | **exact** — the observable output is the Java `AudioTrack`/`VideoTrack`/`SubtitleTrack` field values built in `JavaPlayerEventDispatcher.cpp:342+` | scalar payloads on the `PlayerCallbacks` track slots; Java constructs `com.sun.media.jfxmedia.track.*` |
| `CMediaWarningDispatcher::Warning` (`Utils/MediaWarningDispatcher.cpp:38`) | PURE | none; forwards to `CMediaManager::m_pWarningListener` -> `MediaUtils.nativeWarning(ILjava/lang/String;)V` | **exact** (code + string pass-through) | one manager-level callback slot `warning(user, code, msg)` |
| `CLowLevelPerf` (`Utils/LowLevelPerf.cpp`, 301 lines) | dead-by-config | whole body under `#if ENABLE_LOWLEVELPERF` (`:28-301`), flag is `0` (`Common/ProductFlags.h:57`); not in the Linux Makefile at all | n/a | delete |
| `ColorConvert_YCbCr420p_to_{ARGB32,ARGB32_no_alpha,BGRA32,BGRA32_no_alpha}`, `ColorConvert_YCbCr422p_to_{ARGB32,BGRA32}_no_alpha` (`Utils/ColorConverter.c`, 2686 lines; SSE2 path `:384-2355`, generic-C path `:2355-2595`) | PURE-HOT | `emmintrin.h` intrinsics on x86 (`ENABLE_SIMD_SSE2=1` unless `TARGET_OS_MAC_ARM64`, `:30-38`); no external symbol | **unknown** — integer fixed-point (x8192 constants, `color_tClip` table) so a byte-exact test against the platform's own C is feasible, but the generic-C ARGB32 variants `return 1; // NOTE: Not implemented` (`:2358-2389`) and SSE2-vs-C agreement has never been measured | **stays native**; experiment: run both paths over the same YV12 frames and diff |
| `com_sun_media_jfxmediaimpl_NativeVideoConverter.cpp` (386 lines, 2 `JNIEXPORT`s) | dead | not in any Makefile; includes non-existent `com_sun_media_jfxmediaimpl_NativeVideoConverter.h` (no such Java class exists in `src/main/java`) | n/a | delete |
| `jfxmedia/platform/ios/**` (28 files), `Utils/AutoLock.h` | dead | no Makefile, no includer (see section 9) | n/a | delete |

## 6. Upcall table

| Java target | C site | Calling thread | Blocking? | Payload / return |
|---|---|---|---|---|
| `ConnectionHolder.needBuffer()Z` | `JavaInputStreamCallbacks.cpp:154` via `GstPipelineFactory.cpp:273` | Java thread (inside `gstInitNativeMedia`), once per pipeline | no | bool -> selects progressbuffer/hlsprogressbuffer wrapping and `stop-on-pause=FALSE` |
| `ConnectionHolder.isSeekable()Z` | `:230` via `GstPipelineFactory.cpp:268` | Java thread, once | no | bool -> `is-seekable` property |
| `ConnectionHolder.isRandomAccess()Z` | `:248` via `GstPipelineFactory.cpp:243` | Java thread, once | no | bool -> pull vs push scheduling, whether `read-block` is connected |
| `ConnectionHolder.readNextBlock()I` | `:173` <- `SourceReadNextBlock` <- `g_signal_emit` in `java_source_loop` (`javasource.c:630`) | **javasource src-pad GstTask streaming thread** (push mode) | **yes** (channel/network read) | `>0` bytes staged in `holder.buffer`; `-1` EOS (`EOS_CODE`); `-2` on Java exception (`OTHER_ERROR_CODE` -> `GST_FLOW_FLUSHING`) |
| `ConnectionHolder.readBlock(JI)I` | `:193` <- `SourceReadBlock` <- `java_source_getrange` (`javasource.c:838`) | **downstream element's streaming thread** (pull mode: qtdemux/wavparse task), <= 65536 bytes per call | **yes** | bytes staged; `-1` EOS; `0` treated as EOS (`:856-863`) |
| field `ConnectionHolder.buffer` + `GetDirectBufferAddress` + `memcpy` | `:211-214` <- `SourceCopyBlock` <- `javasource.c:644,841` | same thread as the preceding read | no | copies `size` bytes into the mapped `GstBuffer` memory C passes in |
| `ConnectionHolder.seek(J)J` | `:266` <- `SourceSeekData` <- `java_source_perform_seek` (`javasource.c:461`) | thread that delivers the SEEK event: Java thread (`gstSeek` -> `gst_element_seek`) or a demuxer streaming thread issuing an upstream byte seek; STREAM_LOCK held except in HLS-live | **yes** (HTTP Range GET, `URIConnectionHolder.seek`) | new position or `-1`; HLS returns seconds x1000 |
| `ConnectionHolder.property(II)I` | `:302` <- `SourceProperty` (`javasource.c:570-575, 750`) and direct calls `GstMedia.cpp:115` (prop 6), `GstPipelineFactory.cpp:94,96,114` (props 2,3) | Java thread (props 2,3,6 at creation); javasource task thread (4 `LOAD_SEGMENT`, 5 `SEGMENT_START_TIME`); any query thread (1 `GET_DURATION`) | **yes** for `LOAD_SEGMENT` (HLS segment download) | int |
| `ConnectionHolder.closeConnection()V` | `:283` <- `SourceCloseConnection` <- `java_source_change_state` READY->NULL (`javasource.c:931`) **while `element->lock` is held** | thread driving the state change (Java thread via `gstStop`/`gstFinish`/`gstDispose`) | no | after it returns C disconnects all six handlers and `delete callbacks` (`GstPipelineFactory.cpp:336-342`) |
| `Locator.getStringLocation()Ljava/lang/String;` | `Locator.cpp:55-79` from `GstMedia.cpp:59` | Java thread | no | string -> `CLocator.m_location` |
| `Locator.createConnectionHolder()Lcom/sun/media/jfxmedia/locator/ConnectionHolder;` | `Locator.cpp:83-107` from `GstMedia.cpp:91` | Java thread | yes (opens file/URL) | object -> global ref |
| `Locator.getAudioStreamConnectionHolder(ConnectionHolder)` | `Locator.cpp:110-136` from `GstMedia.cpp:121`, only if `property(6,0)!=0` | Java thread | yes | object -> second callbacks |
| `Throwable.toString()` | `JniUtils.cpp` `reportException` | whichever thread detected the exception | no | logged via `LOGGER_ERRORMSG` |

## 7. Proposed C ABI header (survivors only)

Nothing in `plugins/`, `gstreamer-lite/` or `3rd_party/` gets an ABI — Java does not call them. The only additions belong in `jfxmedia_api.h` (naming per `jfx-media-native`: `jfxm_*`):

```c
#include <stdint.h>

/* Return codes are jfxmedia_errors.h values (ERROR_NONE == 0). */

typedef struct JfxmStreamCallbacks {
    /* Called once at pipeline construction, on the thread calling jfxm_media_create. */
    int32_t (*need_buffer)(void* user);            /* 1 => wrap javasource in (hls)progressbuffer */
    int32_t (*is_seekable)(void* user);
    int32_t (*is_random_access)(void* user);       /* 1 => pull mode, read_block will be used */
    /* Streaming-thread calls; MAY BLOCK; never call these under Linker.Option.critical. */
    int32_t (*read_next_block)(void* user);        /* >0 bytes staged; -1 EOS; -2 error/exception */
    int32_t (*read_block)(void* user, int64_t position, int32_t size);  /* pull mode; size <= 65536 */
    void    (*copy_block)(void* user, void* dst, int32_t size);         /* copy the staged bytes into dst[0..size) */
    int64_t (*seek)(void* user, int64_t position); /* new position or -1; HLS: seconds*1000 */
    int32_t (*property)(void* user, int32_t prop, int32_t value);       /* HLS_PROP_* (1..6) */
    void    (*close_connection)(void* user);       /* last call; emitted under the element lock on READY->NULL */
} JfxmStreamCallbacks;

JFX_EXPORT int32_t jfxm_sizeof_stream_callbacks(void);   /* layout drift test */

/* Replaces Java_..._GSTMedia_gstInitNativeMedia. Java resolves the Locator upcalls itself first:
 * location = locator.getStringLocation(); holder = locator.createConnectionHolder();
 * audio = holder.property(HLS_PROP_HAS_AUDIO_EXT_STREAM, 0) != 0 ? locator.getAudioStreamConnectionHolder(holder) : null.
 * The tables are copied by value; the function pointers and `user` must stay valid until
 * close_connection has run AND jfxm_media_dispose has returned. */
JFX_EXPORT int32_t jfxm_media_create(const char* content_type_utf8, const char* location_utf8, int64_t size_hint,
                                     const JfxmStreamCallbacks* cb, void* user,
                                     const JfxmStreamCallbacks* audio_cb, void* audio_user, /* may be NULL */
                                     void** out_media);

/* Replaces Java_..._GSTMedia_gstDispose. */
JFX_EXPORT void jfxm_media_dispose(void* media);
```

Strings: `content_type`/`location` are standard UTF-8 (`arena.allocateFrom(String)`); today they go through `GetStringUTFChars` (modified UTF-8). Both are URI/MIME strings; confirm `Locator.getStringLocation()` returns an ASCII URI before ruling this a no-op (open question 6).

C-side implementation: a ~70-line `CFfiStreamCallbacks : CStreamCallbacks` that stores the table copy + `user` and forwards each virtual to the slot (tolerating `NULL` slots), created in the C ABI `jfxm_media_create` in place of `CJavaInputStreamCallbacks`. `CLocatorStream`/`GstPipelineFactory` are untouched. The `CLocator` JNI statics and `JavaInputStreamCallbacks.cpp/.h` are deleted when `GSTMedia` flips.

## 8. Callback tables

* **`JfxmStreamCallbacks`** (above) — one per `ConnectionHolder` (main and optional HLS audio stream). Java: stubs in `Arena.ofShared()` owned by the `GSTMedia` peer (calls come from GStreamer task threads); `user` = registry id in a `ConcurrentHashMap<Long, ConnectionHolder>`; close the arena and remove the entry only after `jfxm_media_dispose` returns. Upcall stubs must catch `Throwable`: `read_next_block`/`read_block` return `-2`, `seek` returns `-1`, booleans return `0`, `property` returns `0`, `close_connection` swallows — exactly the mapping in `JavaInputStreamCallbacks.cpp:174-176,194-196` and the `clearException()` defaults elsewhere.
* **Track/warning slots** (belong to the `PlayerCallbacks`/manager tables of the dispatcher slice, listed here because their C++ carriers are in Q5): `void (*on_audio_track)(void* user, int64_t id, const char* name, int32_t encoding, int32_t enabled, const char* language, int32_t channels, int32_t channel_mask, float sample_rate)`, `void (*on_video_track)(void* user, int64_t id, const char* name, int32_t encoding, int32_t enabled, int32_t width, int32_t height, float frame_rate, int32_t has_alpha)`, `void (*on_subtitle_track)(void* user, int64_t id, const char* name, int32_t encoding, int32_t enabled, const char* language)`, `void (*warning)(void* user, int32_t code, const char* msg)`.

## 9. Deletion candidates (parity gate cleared, or no gate applicable)

Sweep method: every `*.c/*.cpp/*.m/*.mm` token in every Makefile under `jfxmedia/projects/{win,linux,mac}` and `gstreamer/projects/{win,linux,mac}/**` (419 files compiled on some platform), then the `#include` closure by path-suffix match (over-approximates references, so this list is a **lower bound**); 1026 files scanned, 949 reachable, 77 dead + 3 iOS headers that suffix matching wrongly kept (their names collide with generated headers in `target/gensrc/headers`, which is what the live includers actually resolve to). Script: scratchpad `deadcode.pl`; raw lists `dead-all.txt`, `compiled-all.txt`.

| Path | Kind | Lines | Copies | Java counterpart | Parity test / risk |
|---|---|---|---|---|---|
| `jfxmedia/platform/ios/**` (28 files incl. `jfxmedia_errors.h` stale copy 143, `jni/*.h` 108) | DEAD (no Makefile; pom excludes `platform/ios/**` Java; nothing builds iOS) | 5217 | 1 | n/a | none needed; risk: none for desktop |
| `jfxmedia/jni/com_sun_media_jfxmediaimpl_NativeVideoConverter.cpp` | DEAD (not compiled; header/Java class do not exist) | 386 | 1 | none | none |
| `jfxmedia/Utils/AutoLock.h` | DEAD (no includer) | 54 | 1 | n/a | none |
| `jfxmedia/Utils/LowLevelPerf.cpp` (+ macro-only `LowLevelPerf.h`) | DEAD-by-config (`ENABLE_LOWLEVELPERF 0`) | 301 | 1 | n/a | remove the `LOWLEVELPERF_*` macro invocations or keep the header as no-ops; count them with `grep -rc 'LOWLEVELPERF_' jfxmedia` |
| `jfxmedia/jni/JavaInputStreamCallbacks.cpp/.h` | JNI-GLUE (migration) | 376 | 1 | replaced by `JfxmStreamCallbacks` stubs in `GSTMedia`/`JfxMediaNative` | binding test: Java-side round trip of each stub; playback smoke test |
| `jfxmedia/Locator/Locator.cpp:55-136` + `jni/JniUtils.h` include in `Locator.h` | JNI-GLUE (migration) | ~85 | 1 | `Locator.getStringLocation/createConnectionHolder/getAudioStreamConnectionHolder` called from Java | same |
| `jfxmedia/platform/gstreamer/GstMedia.cpp` JNI parts (`InitMedia` string/array marshalling, 2 exports) | JNI-GLUE (migration) | ~60 | 1 | `JfxMediaNative.mediaCreate/mediaDispose` | same |
| `jfxmedia/PipelineManagement/{Track,AudioTrack,VideoTrack,SubtitleTrack}.cpp/.h` | PURE, `PARITY: exact` | 259 (.cpp) + ~250 (.h, approx.) | 1 (used by gstreamer and osx/avf) | `com.sun.media.jfxmedia.track.{Track,AudioTrack,VideoTrack,SubtitleTrack,VideoResolution}` | assert the Java track objects received by `NativeMediaPlayer` have identical id/name/encoding/enabled/language/channels/mask/rate/width/height/fps/alpha before and after; lands with the `PlayerCallbacks` commit, not alone |
| `jfxmedia/Utils/MediaWarningDispatcher.cpp/.h` | PURE, `PARITY: exact` | 43 + ~45 | 1 | `MediaUtils.nativeWarning(int, String)` | assert same (code, message) arrives |
| `gstreamer/gstreamer-lite/**` 40 unreferenced files (controller lib 10, video lib 14, pbutils/gstaudiovisualizer.c, tag/gsttageditingprivate.c, typefind functions{data,riff,startwith}, isomp4/properties.c, app/gstapp-marshal.h, fft/fft.h, gst/gstquark.h (0 lines), base/*-docs.h x3) | DEAD (upstream leftovers) | 15512 | 1 | n/a | risk: upstream tracking — prune in its own commit or leave |
| `gstreamer/3rd_party/**` 10 files (`baseclasses/schedule.h`, glib `gi18n.h`, `gmirroringtable.h`, `gpathbuf.c`, `gscripttable.h`, `gobject/glib-enumtypes.c`, `build/win32/vs100/msvc_recommended_pragmas.h`, libffi `ffi_cfi.h`, `src/debug.c`, `src/x86/asmnames.h`) | DEAD | 5612 | 1 | n/a | same |
| `vs_project/**` (FXMedia.sln + 4 vcxproj/filters), `xcode_project/**` | unwired build inputs | 2720 + 1069 | — | n/a | decide with the build owner; Maven never reads them |
| `src/tools/java/headergen/HeaderGen.java`, `src/tools/native/def-{glib,gstlite}.pl` | unwired (pom.xml:40-44 comment) | 83 + (not counted) | — | n/a | `def-*.pl` still matter if the Windows `.def` files must change; `headergen` becomes obsolete once `jfxmedia_errors.h` is no longer generated from `MediaError.java` |

Not dead, keep: `plugins/javasource/marshal.in` + `genmarshal.sh` (generator inputs for `marshal.c/.h`).

## 10. Kept native despite being pure

| Function / file | Parity | Reason |
|---|---|---|
| `Utils/ColorConverter.c` (6 entry points x 2 code paths, 2686 lines) | **unknown** | Integer fixed-point so exactness is testable, but there are two de facto outputs: SSE2 (`ENABLE_SIMD_SSE2=1` on win/linux-x86/mac-x64) and generic C (mac arm64), and the generic-C ARGB32 variants are stubs (`return 1`). Experiment: convert a corpus of YV12/YUV422 frames through both paths on x86 (force the macro) and diff; then measure a Java/Vector-API port against the SSE2 path on the SW/J2D pipeline, its only consumer (`SWArgbPreTexture.java:130`, `J2DTexture.java:189`, `J2DResourceFactory.java:148`; GPU pipelines use `PaintTextureYV12/YUV422/YUV444.jsl`). |
| `CGstPipelineFactory::Source*` trampolines (`GstPipelineFactory.cpp:307-343`) | n/a | Pure but they are the GSignal-to-table dispatch running on GStreamer threads; moving them to Java means binding `g_signal_connect_data` and exposing the `GstElement*` to Java, which the Windows ordinal-only exports forbid. |
| `CLocator`/`CLocatorStream` data classes | exact | Trivially pure, but `GstPipelineFactory` consumes them in C; they shrink to a struct rather than move. |

## 11. Build/test touchpoints, risks, recommendation

### Build/test touchpoints

* Nothing in this slice is compiled by Maven; `mvn -pl modules/javafx.media install` compiles Java only and emits 19 JNI headers into `modules/javafx.media/target/gensrc/headers` (present: `..._GSTMedia.h`, `..._GSTMediaPlayer.h`, `..._GSTPlatform.h`, `..._NativeVideoBuffer.h`, `jfxmedia_errors.h`, …). Any C change here needs a hand-driven make on each OS plus a refreshed `C:\SourceCode\caches\sdk\bin\jfxmedia.dll`; `fxplugins.dll`, `gstreamer-lite.dll`, `glib-lite.dll` need no rebuild for this migration.
* `jfxmedia.dll` has no `.def`; its exports come from `JNIEXPORT` (`__declspec(dllexport)`) under `-DJFXMEDIA_JNI_EXPORTS`, so a `JFX_EXPORT` macro in `jfxmedia_api.h` is sufficient on Windows; on Linux/macOS the Makefiles set no visibility flag so exports are automatic.
* `modules/javafx.media` has no `src/test` tree; no test in `modules/*/src/test` or `tests/system/src` references `ConnectionHolder`, `Locator`, `LocatorCache`, `javasource`, `progressbuffer`, `HLSConnectionHolder`, `NativeMediaManager` or `MediaUtils`; the only media reference in `tests/system` is `new MediaView()` in `test.robot.javafx.scene.NodeInitializationStressTest`. No stubs override the natives.
* Tests to add with the migration: (1) `jfxm_sizeof_stream_callbacks()` == `JfxmStreamCallbacks.LAYOUT.byteSize()`; (2) invoke each upcall stub from Java against a `MemoryConnectionHolder` (`ConnectionHolder.createMemoryConnectionHolder`) and assert the `-1/-2/0` mappings incl. a throwing holder; (3) a `FULL_TEST` playback smoke test with a bundled WAV that asserts the `readNextBlock`/`closeConnection` sequence completes and the player reaches PAUSED/READY — the first media playback test in the fork.

### Risks

1. **Blocking upcalls from non-Java threads.** `read_next_block`, `read_block`, `seek`, `property(LOAD_SEGMENT)` block on I/O and arrive on GStreamer task threads; `Arena.ofShared()` only, never `Linker.Option.critical`.
2. **`close_connection` runs under `element->lock`** (`javasource.c:930-932`) and is immediately followed by `delete callbacks` (`GstPipelineFactory.cpp:342`); the Java handler must not re-enter jfxmedia, and Java must not touch the media after `jfxm_media_dispose`.
3. **Existing global-ref leak** on `Init` failure paths (`GstMedia.cpp:95-101,129-135`) becomes a registry-entry leak unless the Java side removes the entry on every error return of `jfxm_media_create`.
4. **`copy_block` writes into C-owned mapped memory**; the stub must `reinterpret(size)` exactly and copy from the *current* `holder.getBuffer()` (identity changes in `MemoryConnectionHolder`/`FileConnectionHolder`).
5. **Exception semantics are part of the contract** (`-2` -> `GST_FLOW_FLUSHING`; swallowed exceptions elsewhere); an escaping exception from an upcall stub terminates the JVM.
6. **Modified UTF-8 -> UTF-8** for content type and location (`GstMedia.cpp:58,76`); low risk for URI/MIME strings, verify.
7. **Windows `NONAME` ordinal exports** of gstreamer-lite/glib-lite: `SymbolLookup` finds nothing by name; do not attempt Java-direct gst binding; Linux links **system glib** (not glib-lite) so even symbol sets differ per OS.
8. **Thread-unsafe one-time init** (`static bool methodIDsInitialized`, `JavaInputStreamCallbacks.cpp:72`) and function-static `jmethodID`s in `Locator.cpp` — disappear with the glue.
9. **32-bit casts**: `JniUtils.h` `jlong_to_ptr` uses `(void*)(int)` on non-64-bit (irrelevant for supported targets); HLS property values are `int` milliseconds (overflow past ~24.8 days, pre-existing).
10. `JNI_ABORT`: none in scope. Struct-by-value returns: none.

### Recommendation

* **Plugins, gstreamer-lite, glib-lite, 3rd_party: no migration work, no ABI, no facade.** They are JNI-free engine code; leave them untouched. Only the Windows `.def` files (via `def-gstlite.pl`/`def-glib.pl`) ever need attention, when jfxmedia starts using a new `gst_*` symbol.
* **Migration commit (behaviour-neutral):** add `JfxmStreamCallbacks`, `jfxm_media_create`, `jfxm_media_dispose`, `jfxm_sizeof_stream_callbacks` and `CFfiStreamCallbacks` beside the JNI code; add `JfxMediaNative.mediaCreate/mediaDispose`; flip `GSTMedia` (2 natives, the Locator upcalls move into `GSTMedia` Java code); then delete `JavaInputStreamCallbacks.cpp/.h`, `CLocator::{LocatorGetStringLocation,CreateConnectionHolder,GetAudioStreamConnectionHolder}`, the `jni/JniUtils.h` include in `Locator.h`, and the two `JNIEXPORT`s. Run the binding tests above and the WAV smoke test after the flip. This step depends on nothing else in the media migration order and can go first or alongside `Logger`.
* **Prune commit (no behaviour change, separate):** delete `jfxmedia/platform/ios/**`, `NativeVideoConverter.cpp`, `AutoLock.h`, `LowLevelPerf.cpp` (+ macro sites); decide on `vs_project/`, `xcode_project/`, `headergen`; optionally the 50 unreferenced gstreamer-lite/3rd_party files (weigh against upstream-merge friction).
* **Java-reimplementation commits (parity-tested, separate):** track carriers -> scalar `PlayerCallbacks` payloads and `MediaWarningDispatcher` -> `warning` slot, landing with the dispatcher slice's callback-table work.
* **Keep `ColorConverter.c` native** until the SSE2-vs-generic-C experiment and a Vector-API measurement exist.