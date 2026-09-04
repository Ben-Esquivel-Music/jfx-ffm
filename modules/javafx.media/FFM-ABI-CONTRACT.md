# javafx.media JNI -> FFM: the ABI contract

Status: authoritative design contract for the `javafx.media` JNI removal on branch `ffm/media`.
Every agent working on this migration follows it exactly. Derived from the `jni-to-ffm-migration`,
`jfx-media-native`, `jfx-ffm-testing` and `openjfx-conventions` skills plus the five-slice
`jni-auditor` inventory recorded in `FFM-AUDIT-*.md` (same directory).

The `javafx.web` contract (`modules/javafx.web/FFM-ABI-CONTRACT.md`) is the precedent; this one is
deliberately smaller because the media surface is ~120 functions, not ~2000, and every one of them
is hand-designed.

## 0. Measured starting point

| Surface | Java `native` | `JNIEXPORT` | Native LOC in scope |
|---|---:|---:|---:|
| `jfxmedia/jni/**` (dispatcher, input-stream callbacks, bands holder, logger, video buffer, equalizer, spectrum, band) | 33 (Logger 2, NativeVideoBuffer 13, NativeAudioEqualizer 5, NativeEqualizerBand 6, NativeAudioSpectrum 7) | 35 (2 in the dead `NativeVideoConverter.cpp`) | 2 906 |
| `jfxmedia/platform/gstreamer` (`GstMediaPlayer.cpp`, `GstMedia.cpp`, `GstPlatform.cpp`, `GstJniUtils`) | 21 (GSTMediaPlayer 18, GSTMedia 2, GSTPlatform 1) | 23 | 873 + 92 |
| `jfxmedia/platform/osx` + `Utils/{JObjectPeers,JavaUtils,MTObjectProxy}.m` | 23 (OSXMediaPlayer 22, OSXPlatform 1) | 23 | ~1 150 JNI/ObjC glue of 3 765 |
| `jfxmedia/platform/ios/**` + `NativeAudioClip` | 40 (IOSMediaPlayer 25, IOSMedia 2, IOSPlatform 1, NativeAudioClip 12) | 100 (`.h`+`.m` duplicates) | 5 217 |
| `gstreamer/plugins`, `gstreamer-lite`, `3rd_party` | 0 | 0 | JNI-free, untouched |
| **Total** | **117** | **181** | |

Prebuilt JNI-era `jfxmedia.dll` (`../caches/sdk/bin`) exports `JNI_OnLoad` + 54 `Java_*` and imports
`gstreamer-lite.dll` (80 symbols) and `glib-lite.dll` (46 symbols) **by ordinal only** (`NONAME`
`.def` exports). Consequence: Java can never bind `gst_*`/`g_*` directly on Windows, so every
GStreamer interaction stays inside `jfxmedia` behind the `jfxm_*` ABI. No `WRAPPER` verdicts exist
in this module.

Baseline: `modules/javafx.media` has no `src/test`; `mvn -pl modules/javafx.media install` compiles
Java only. No media playback test exists anywhere in the fork. The migration therefore lands with
the first media test tree (`FFM-TEST-PLAN.md`).

## 1. Decisions

1. **One flat C ABI, one Java facade.** `jfxmedia_api.h` (at
   `src/main/native/jfxmedia/jfxmedia_api.h`) declares every exported `jfxm_*` function and every
   callback table. `com.sun.media.jfxmediaimpl.JfxMediaNative` is the only class in the module that
   uses restricted `java.lang.foreign` methods (`nativeLinker`, `loaderLookup`, `downcallHandle`,
   `upcallStub`, `reinterpret`). Callers (`GSTMediaPlayer`, `GSTMedia`, `GSTPlatform`,
   `OSXMediaPlayer`, `OSXMedia`, `OSXPlatform`, `NativeVideoBuffer`, `NativeAudioEqualizer`,
   `NativeEqualizerBand`, `NativeAudioSpectrum`, `Logger`) call static methods on the facade and
   keep their public behaviour unchanged.
2. **One handle type for both desktop backends.** The GStreamer backend (Windows, Linux, macOS)
   and the AVFoundation backend (macOS only) share the `jfxm_media` handle and the whole
   `jfxm_player_*` surface. Backend-specific behaviour is kept where it is today (e.g. mute is
   Java-side on GST and native on AVF; audio sync delay is applied on GST and stored-but-ignored on
   AVF). The symbol set exported by `jfxmedia` is **identical on every platform**: functions that
   only make sense on macOS are exported everywhere and return `ERROR_NOT_IMPLEMENTED` (2561)
   elsewhere, so the symbol-resolution test is platform-independent.
3. **The iOS platform is deleted, not migrated.** Nothing in this fork builds, packages or tests
   iOS (`pom.xml` excludes `platform/ios/**` from every jar; no makefile compiles
   `jfxmedia/platform/ios`; `PlatformUtil.isIOS()` is false on every supported JDK). `NativeAudioClip`
   is implemented only by that iOS code, so on every desktop platform `AudioClipProvider` already
   fell back to `NativeMediaAudioClip` after catching `UnsatisfiedLinkError`; deleting
   `NativeAudioClip` and the `useNative` branch was behaviour-neutral (only a DEBUG log line
   disappeared). `AudioClipProvider` itself has since gone the same way - see section 12. This
   is a decision recorded here because it removes 5 217 lines of ObjC and 40 of the 117 natives
   without a compiler for them existing anywhere in the project.
4. **Behaviour-neutral migration first; deletions and reimplementations separate.** The
   migration produces the same events, same error codes, same threads, same ownership. Dead code
   (listed in section 11) is deleted in its own change. No C is reimplemented in Java in the
   migration: the audit found no `PURE` function whose parity could be proven and that is not
   already just a data carrier. `Utils/ColorConverter.c` (`PURE-HOT`, `PARITY: unknown`) stays
   native.
5. **Error codes, not exceptions, cross the boundary** — exactly as today. `jfxmedia_errors.h`
   keeps being generated from `MediaError.java` by `src/tools/java/headergen/HeaderGen.java`, now
   run by Maven (see `FFM-BUILD-PLAN.md`), so the two sides cannot drift.
6. **Native code never holds a Java reference.** Every former `jobject` global ref becomes a
   Java-assigned `int64_t` registry id passed as `void* user`; every former `jmethodID` becomes a
   slot in a callback table of function pointers.

## 2. Type mapping (mandatory)

| JNI | C ABI | Java FFM layout |
|---|---|---|
| `jlong` peer / pointer | `void*` (opaque handle) | `ADDRESS` |
| `jlong` value (track id, byte position, delay) | `int64_t` | `JAVA_LONG` |
| `jint` | `int32_t` | `JAVA_INT` |
| `jboolean` | `int32_t` (0/1, never `int8_t`) | `JAVA_INT` |
| `jfloat` / `jdouble` | `float` / `double` | `JAVA_FLOAT` / `JAVA_DOUBLE` |
| `JNIEnv*`, `jclass`, `jobject` receiver | dropped | - |
| `jstring` into C | `const char*` UTF-8, NUL-terminated; `NULL` = Java `null` | `ADDRESS` (`arena.allocateFrom(String)`) |
| string out of C (callback payload) | `const char*` UTF-8, NUL-terminated, valid **only for the duration of the call** | `ADDRESS`; facade copies with `getString(0)` on a `reinterpret`ed segment before returning |
| `T[] out` one-element out-param | `T* out` | `ADDRESS` to a confined-arena scalar (pattern P4) |
| `jobject` returned by C (`NativeEqualizerBand`) | never; C returns the `void*` handle and Java constructs the object | `ADDRESS` |
| `java.nio.ByteBuffer` returned by C (`NewDirectByteBuffer`) | pointer + size in a struct (`JfxmFrameInfo`) | `MemorySegment.ofAddress(p).reinterpret(size).asByteBuffer()` in the facade |
| `float[]` written by C (`SetFloatArrayRegion`) | `float*` into memory **allocated by Java** in a shared arena | `ADDRESS` |

Strings: the JNI code used `GetStringUTFChars`/`NewStringUTF` (modified UTF-8). Every string that
crosses this boundary is a URI, a MIME type, a GStreamer track name/language, or a log/warning
message; none can carry an embedded NUL and non-BMP characters were already mangled by the old
path, so standard UTF-8 is a benign change. `JfxMediaNative` never introduces modified UTF-8.

Booleans returned by C are `int32_t`; Java compares `!= 0`. Never declare a `JAVA_BOOLEAN` layout.

## 3. Handles, ownership, registry

| Handle (`void*`) | Backing object | Created by | Owned / freed by |
|---|---|---|---|
| `jfxm_media` | `JfxmMedia { int32_t backend; CMedia* gst; void* osx_player; }` (C struct in `jfxmedia_api.cpp`) | `jfxm_media_create` | `jfxm_media_dispose` (GST: `delete CMedia` -> `~CPipeline` deletes the dispatcher; AVF: `[player dispose]`, delete dispatcher, release) |
| equalizer | `CAudioEqualizer*` (pipeline-owned) | `jfxm_player_get_audio_equalizer` | the media; valid until `jfxm_media_dispose` |
| spectrum | `CAudioSpectrum*` (pipeline-owned) | `jfxm_player_get_audio_spectrum` | the media; valid until `jfxm_media_dispose` |
| band | `CEqualizerBand*` | `jfxm_eq_add_band` | the equalizer (`jfxm_eq_remove_band` or media dispose) |
| frame | `CVideoFrame*` (`CGstVideoFrame` / `CVVideoFrame`) | delivered through `new_frame`, or returned by `jfxm_frame_convert` | Java, exactly as today: `NativeVideoBuffer.releaseFrame()` -> `jfxm_frame_dispose` when the hold count reaches 0 |

Java-side registry (`JfxMediaNative`): `ConcurrentHashMap<Long, Object>` with ids from an
`AtomicLong` starting at 1. `0` is never a valid id. `register(Object)` returns the id;
`unregister(long)` removes it; upcall targets look the object up and **ignore** the call when the
id is unknown (a late callback after dispose must be harmless, never a crash). One entry per
`GSTMediaPlayer`/`OSXMediaPlayer` (player callbacks) and one per `ConnectionHolder` (stream
callbacks). A leak test asserts the map is empty after create/dispose loops.

## 4. Upcall arenas and thread contract

* **Log sink**: one stub, installed once per process, `Arena.global()`.
* **Player callbacks**: 13 stubs per player in one `Arena.ofShared()` owned by the Java player
  peer; created before `jfxm_player_init`, closed **after** `jfxm_media_dispose` has returned.
* **Stream callbacks**: 9 stubs per `ConnectionHolder` in one `Arena.ofShared()` owned by the Java
  media peer; closed after `jfxm_media_dispose` has returned (GST tears the pipeline down there,
  which joins the streaming threads; AVF cancels the resource loader and takes the player lock).
* **Spectrum band memory**: two `float` arrays allocated by Java in an `Arena.ofShared()`, handed
  to C by pointer together with a `JfxmReleaseFn` upcall stub and an id. C owns each pair until it
  calls that stub - see section 11: the holder is reference counted, so a pair outlives the
  `setBandCount` that replaced it for as long as a spectrum thread is still writing through it.
  Java frees the pair's memory **in the release callback and nowhere else**;
  freeing on the next `setBandCount`, or assuming dispose is the only release point, is a
  use-after-free. Shape that works: one `Arena.ofShared()` per pair, closed by that pair's release
  callback, and a separate longer-lived `Arena.ofShared()` per `NativeAudioSpectrum` holding the
  release stub - a callback must never close the arena its own upcall stub lives in. The callback
  runs on a native thread, so it takes no locks Java holds across a downcall and catches
  `Throwable` like every other upcall target.

Every upcall target catches `Throwable`, logs through `com.sun.media.jfxmedia.logging.Logger`, and
returns the documented default. An escaping exception terminates the JVM.

Calling threads (from the audit; none of them is the FX thread, and no target may block except the
stream callbacks, which are allowed to block on Java I/O):

| Table / slot | GStreamer backend thread | AVFoundation backend thread |
|---|---|---|
| `JfxmPlayerCallbacks.media_error`, `halt`, `warning` | GLib MainLoop thread (bus watch), demuxer/parser/decoder streaming threads, or the Java caller thread (`pause`) | KVO thread, main dispatch queue |
| `state` | MainLoop thread, or synchronously on the Java caller thread inside `jfxm_player_pause` | Java caller thread (play/pause/stop/finish), main queue (end of media), KVO thread |
| `new_frame`, `frame_size` | appsink streaming thread | CVDisplayLink thread |
| `audio_track`, `video_track`, `subtitle_track` | decoder/parser streaming threads (subtitle: never) | KVO thread |
| `duration_update`, `buffer_progress`, `marker` | MainLoop thread (marker: never) | KVO thread (buffer/marker: never) |
| `audio_spectrum` | MainLoop thread | **MTAudioProcessingTap real-time audio thread**, band lock held: the Java target only enqueues |
| `JfxmStreamCallbacks.need_buffer`, `is_seekable`, `is_random_access`, `property(2,3,6)` | Java caller thread inside `jfxm_media_create` | Java caller thread inside `jfxm_player_init` |
| `read_next_block`, `read_block`, `copy_block`, `seek`, `property(1,4,5)` | `javasource` task thread (push) or the pulling element's streaming thread; **may block** | `playerLoaderQueue` serial dispatch queue under the player lock; **may block** |
| `close_connection` | thread driving READY->NULL, **under the element lock**; C deletes the adapter right after | dispose caller, under the player lock |
| `JfxmLogFn` | any of the above | any of the above |
| `JfxmReleaseFn` (band pair) | the thread that dropped the holder's last reference: MainLoop / spectrum thread, or the app thread inside `jfxm_spectrum_set_bands` / dispose | the audio tap **with the band lock held**, or the app thread inside `jfxm_spectrum_set_bands` / dispose |

Never use `Linker.Option.critical` for any function in this ABI: every downcall takes pipeline or
ObjC locks, and `gst_element_set_state` can block on preroll.

## 5. ABI version guard and layout checks

```c
#define JFXM_ABI_VERSION 2u
JFXM_EXPORT uint32_t jfxm_abi_version(void);
JFXM_EXPORT int32_t  jfxm_sizeof_player_callbacks(void);
JFXM_EXPORT int32_t  jfxm_sizeof_stream_callbacks(void);
JFXM_EXPORT int32_t  jfxm_sizeof_frame_info(void);
JFXM_EXPORT int32_t  jfxm_offsetof_frame_info(int32_t field);   /* field index per section 8; -1 if out of range */
/* Drift guards: the Java constant this library copies, by index; -1 if out of range. */
JFXM_EXPORT int32_t  jfxm_event_player_state(int32_t pipeline_state);
JFXM_EXPORT int32_t  jfxm_audio_track_channel(int32_t channel);
JFXM_EXPORT int32_t  jfxm_log_level(int32_t level);
```

`JfxMediaNative` checks `jfxm_abi_version()` right after the library loads and throws an
`UnsatisfiedLinkError` naming expected and actual versions. The binding test compares the three
`StructLayout.byteSize()` values and every `JfxmFrameInfo` field offset with the C side.

Version history: 1 was the initial ABI; 2 added `jfxm_audio_track_channel` and `jfxm_log_level`.
Adding a symbol bumps the version even though it takes nothing away, because `JfxMediaNative` binds
every handle eagerly - a library built before those two fails on symbol resolution and reports a
missing symbol instead of the version mismatch this guard exists to produce.

The three drift guards exist because the generated JNI headers that used to keep the C copies of
`NativeMediaPlayer.eventPlayer*`, `AudioTrack.*` and `Logger.*` in step with Java are gone. Each
returns the library's own named constant, so the binding test fails if either side is renumbered.

## 6. Library loading and initialisation

`NativeMediaManager` keeps loading `glib-lite` (Windows/macOS), `gstreamer-lite` (non-Linux) and
`jfxmedia` in that order through `NativeLibLoader`, and `OSXPlatform` keeps loading
`jfxmedia_avf`. That sequence moves into `JfxMediaNative.loadLibraries()` (idempotent,
`NativeLibLoader` is synchronized and remembers loaded libraries) which both `NativeMediaManager`
and the facade's own static initializer call, so a binding test can touch the facade without
constructing the manager. The facade binds symbols with `SymbolLookup.loaderLookup()` and throws
`UnsatisfiedLinkError("missing native symbol: <name>")` for a missing one, at class-init time,
like JNI's lazy link failure but earlier and with a name.

```c
/* Replaces JNI_OnLoad + Java_..._GSTPlatform_gstInitPlatform. Idempotent because the media manager
 * is a singleton: a second call hands back the same manager and returns ERROR_NONE again without
 * re-running gst_init_check. Nothing is memoised in C, so a failure is retried by a later call.
 * Return = MediaError code. */
JFXM_EXPORT int32_t jfxm_platform_init(void);

/* Replaces Java_..._OSXPlatform_osxPlatformInit. macOS: applies the ATS info-dictionary workaround,
 * honours JFXMEDIA_AVF, probes objc_getClass("AVFMediaPlayer") and returns 1 if the AVF backend is
 * usable, 0 otherwise. Other platforms: returns 0 and does nothing. Exported everywhere. */
JFXM_EXPORT int32_t jfxm_osx_platform_init(void);

/* Replaces Java_..._logging_Logger_nativeInit / nativeSetNativeLevel. The single log sink is
 * installed once; a second call replaces the pointer (tests). level uses the Java Logger constants
 * (ERROR 4, WARNING 3, INFO 2, DEBUG 1, OFF Integer.MAX_VALUE). The message pointer is valid only
 * during the call. Returns 1 when logging is compiled in (ENABLE_LOGGING), else 0. */
typedef void (*JfxmLogFn)(void* user, int32_t level, const char* message);
JFXM_EXPORT int32_t jfxm_log_init(JfxmLogFn fn, void* user);
JFXM_EXPORT void    jfxm_log_set_level(int32_t level);
```

The manager-level warning path (`CMediaWarningDispatcher` -> `CJavaMediaWarningListener` ->
`MediaUtils.nativeWarning`) is unreachable today (the dispatcher is never instantiated) and is
deleted rather than given a slot; `MediaUtils.nativeWarning` stays as the Java-side sink used by
`NativeMediaPlayer.sendWarning` callers.

## 7. Media and player

```c
enum { JFXM_BACKEND_GST = 0, JFXM_BACKEND_AVF = 1 };

typedef struct JfxmStreamCallbacks JfxmStreamCallbacks;   /* section 9 */
typedef struct JfxmPlayerCallbacks JfxmPlayerCallbacks;   /* section 10 */

/* Replaces Java_..._GSTMedia_gstInitNativeMedia (+ the three CLocator JNI statics) and the media
 * half of Java_..._OSXMediaPlayer_osxCreatePlayer. Java resolves the Locator calls itself, on the
 * same thread and in the same order as the C did:
 *   location = locator.getStringLocation();
 *   holder   = locator.createConnectionHolder();               (GST always; AVF only for jar:/jrt:)
 *   audio    = holder.property(HLS_PROP_HAS_AUDIO_EXT_STREAM, 0) != 0
 *              ? locator.getAudioStreamConnectionHolder(holder) : null;   (GST only)
 * Tables are copied by value; the function pointers and user values must stay valid until
 * close_connection has run AND jfxm_media_dispose has returned. cb may be NULL (AVF file/http).
 * On any non-zero return *out_media is NULL and nothing is retained by C. */
JFXM_EXPORT int32_t jfxm_media_create(int32_t backend,
                                      const char* content_type, const char* location, int64_t size_hint,
                                      const JfxmStreamCallbacks* cb, void* user,
                                      const JfxmStreamCallbacks* audio_cb, void* audio_user,
                                      void** out_media);

/* Replaces Java_..._GSTMedia_gstDispose and the teardown half of Java_..._OSXMediaPlayer_osxDispose.
 * After it returns no callback of any table fires again; Java then unregisters and closes arenas. */
JFXM_EXPORT void    jfxm_media_dispose(void* media);

/* Replaces Java_..._GSTMediaPlayer_gstInitPlayer and the player half of osxCreatePlayer. Creates the
 * backend event dispatcher over a by-value copy of *cb and initialises the pipeline / AVPlayer.
 * Return = MediaError code (AVF: the six former ThrowJavaException sites map to their codes). */
JFXM_EXPORT int32_t jfxm_player_init(void* media, const JfxmPlayerCallbacks* cb, void* user);

/* The 18 GSTMediaPlayer / 20 OSXMediaPlayer forwarding natives. media = jfxm_media handle.
 * Return = MediaError code (ERROR_MEDIA_NULL, ERROR_PIPELINE_NULL preserved); out-params are
 * written only on ERROR_NONE. */
JFXM_EXPORT void*   jfxm_player_get_audio_equalizer(void* media);     /* NULL if media is NULL */
JFXM_EXPORT void*   jfxm_player_get_audio_spectrum(void* media);
JFXM_EXPORT int32_t jfxm_player_get_audio_sync_delay(void* media, int64_t* out_millis);
JFXM_EXPORT int32_t jfxm_player_set_audio_sync_delay(void* media, int64_t millis);   /* AVF: stored, never applied */
JFXM_EXPORT int32_t jfxm_player_play(void* media);
JFXM_EXPORT int32_t jfxm_player_pause(void* media);
JFXM_EXPORT int32_t jfxm_player_stop(void* media);
JFXM_EXPORT int32_t jfxm_player_finish(void* media);
JFXM_EXPORT int32_t jfxm_player_get_rate(void* media, float* out_rate);
JFXM_EXPORT int32_t jfxm_player_set_rate(void* media, float rate);
JFXM_EXPORT int32_t jfxm_player_get_presentation_time(void* media, double* out_seconds);
JFXM_EXPORT int32_t jfxm_player_get_volume(void* media, float* out_volume);
JFXM_EXPORT int32_t jfxm_player_set_volume(void* media, float volume);
JFXM_EXPORT int32_t jfxm_player_get_balance(void* media, float* out_balance);
JFXM_EXPORT int32_t jfxm_player_set_balance(void* media, float balance);
JFXM_EXPORT int32_t jfxm_player_get_duration(void* media, double* out_seconds);  /* -1.0 = unknown; Java maps to +Infinity as today */
JFXM_EXPORT int32_t jfxm_player_seek(void* media, double seconds);
/* AVF only; the GST backend returns ERROR_NOT_IMPLEMENTED and GSTMediaPlayer never calls them (mute stays Java-side there). */
JFXM_EXPORT int32_t jfxm_player_get_mute(void* media, int32_t* out_mute);
JFXM_EXPORT int32_t jfxm_player_set_mute(void* media, int32_t mute);
```

Failure of `jfxm_player_init` leaves the media handle alive (C retains nothing of the callback table): the Java caller must call `jfxm_media_dispose` itself before propagating the error when it created the media in the same constructor (`OSXMediaPlayer`); `GSTMediaPlayer` leaves that to `GSTMedia.dispose()` exactly as today. `NativeMediaPlayer.dispose()` already calls `playerDispose()` and then `media.dispose()` under one lock, so `OSXMedia.dispose()` is where the AVF teardown now happens (previously `osxDispose` in `playerDispose()`); the only difference is that the Java-side field clearing of `playerDispose()` runs a few lines earlier.

`GSTMediaPlayer` keeps `throwMediaErrorException` on non-zero returns; `OSXMediaPlayer` keeps
today's convention (throw only on create/init, ignore return codes of the other calls, return the
same defaults as before when a call fails). The `jlong` <-> `long` truncation of
`gstSetAudioSyncDelay` on Windows (C `long` is 32-bit) is preserved on the C side by casting
exactly as the pipeline code does; the ABI type is `int64_t` because that is what `jlong` was.

## 8. Video frames (replaces the 13 `NativeVideoBuffer` natives)

```c
typedef struct JfxmFrameInfo {          /* field index for jfxm_offsetof_frame_info */
    double  timestamp;                  /* 0  GetTime() */
    int32_t width;                      /* 1 */
    int32_t height;                     /* 2 */
    int32_t encoded_width;              /* 3 */
    int32_t encoded_height;             /* 4 */
    int32_t format;                     /* 5  CVideoFrame::FrameType == VideoFormat.FormatTypes: ARGB 1, BGRA_PRE 2, YCbCr_420p 100, YCbCr_422 101 */
    int32_t has_alpha;                  /* 6 */
    int32_t plane_count;                /* 7  1..4 */
    int32_t reserved;                   /* 8  keeps the pointers 8-aligned; always 0 */
    int32_t strides[4];                 /* 9  GetStrideForPlane(i), 0 beyond plane_count */
    int64_t plane_size[4];              /* 10 GetSizeForPlane(i) */
    void*   plane_data[4];              /* 11 GetDataForPlane(i); memory owned by the frame */
} JfxmFrameInfo;                        /* sizeof == 120 on every LP64/LLP64 platform */

JFXM_EXPORT int32_t jfxm_frame_get_info(void* frame, JfxmFrameInfo* out);   /* ERROR_NONE, or ERROR_FUNCTION_PARAM_NULL */
JFXM_EXPORT void*   jfxm_frame_convert(void* frame, int32_t format);         /* new frame or NULL, exactly nativeConvertToFormat */
JFXM_EXPORT void    jfxm_frame_set_dirty(void* frame);                       /* nativeSetDirty */
JFXM_EXPORT void    jfxm_frame_dispose(void* frame);                         /* nativeDisposeBuffer: delete */
```

`NativeVideoBuffer` calls `jfxm_frame_get_info` once per frame (in `createVideoBuffer`, on the
delivering thread, which is what the JNI getters read from anyway) and serves every getter from the
cached struct; `getBufferForPlane(i)` returns
`MemorySegment.ofAddress(plane_data[i]).reinterpret(plane_size[i]).asByteBuffer().order(nativeOrder())`
built inside the facade, which reproduces `NewDirectByteBuffer` (a view with no lifetime of its
own; the frame's hold count still governs the memory). The hold/release/dispose protocol, the
`MediaDisposer` registration and the cached BGRA_PRE conversion are unchanged. Prism
(`com.sun.javafx.media.PrismMediaFrameHandler`, `com.sun.prism.MediaFrame`) is not touched.

## 9. Stream callbacks (replaces `CJavaInputStreamCallbacks` + `CLocator` statics)

```c
/* One table per ConnectionHolder. Return conventions are exactly JavaInputStreamCallbacks.cpp's:
 * an exception in the Java target yields -2 from read_*, -1 from seek, 0 from the int/bool slots. */
typedef struct JfxmStreamCallbacks {
    int32_t (*need_buffer)(void* user);                                /* 1 => wrap in (hls)progressbuffer */
    int32_t (*is_seekable)(void* user);
    int32_t (*is_random_access)(void* user);                           /* 1 => pull mode, read_block used */
    int32_t (*read_next_block)(void* user);                            /* >0 bytes staged; -1 EOS; -2 error */
    int32_t (*read_block)(void* user, int64_t position, int32_t size); /* size <= 65536 (GST), <= 1 MiB (AVF) */
    void    (*copy_block)(void* user, void* dst, int32_t size);        /* copy the staged bytes into dst[0..size) */
    int64_t (*seek)(void* user, int64_t position);                     /* new position or -1; HLS: seconds*1000 */
    int32_t (*property)(void* user, int32_t prop, int32_t value);      /* HLSConnectionHolder.HLS_PROP_* 1..6 */
    void    (*close_connection)(void* user);                           /* last call; C deletes its adapter right after */
} JfxmStreamCallbacks;
```

`copy_block`'s Java target copies from the holder's **current** `buffer` (it is replaced by
`FileConnectionHolder.readBlock` and sliced by `MemoryConnectionHolder`, so the target reads the
field on every call, as the JNI `GetObjectField` did) with `MemorySegment.copy` into
`dst.reinterpret(size)`. The C side is a ~70-line `CFfiStreamCallbacks : CStreamCallbacks` that
stores the table copy and `user`, tolerates `NULL` slots (returning the defaults above), and is
created by `jfxm_media_create` where `CJavaInputStreamCallbacks` was. `GstPipelineFactory`'s
`Source*` trampolines and `CLocatorStream` are untouched.

## 10. Player callbacks (replaces `CJavaPlayerEventDispatcher`)

```c
/* One table per player, copied by value in jfxm_player_init. Every slot may be NULL (treated as
 * "delivered"). Return 1 = delivered, 0 = the Java target threw (the C keeps the same false-return
 * handling it had for a JNI exception). Strings are UTF-8 and valid only during the call. */
typedef struct JfxmPlayerCallbacks {
    int32_t (*media_error)(void* user, int32_t error_code);
    int32_t (*halt)(void* user, const char* message, double time);
    int32_t (*state)(void* user, int32_t state, double present_time);   /* state = NativeMediaPlayer.eventPlayer* 100..107 */
    int32_t (*new_frame)(void* user, void* frame);                       /* Java wraps it in NativeVideoBuffer.createVideoBuffer */
    int32_t (*frame_size)(void* user, int32_t width, int32_t height);
    int32_t (*audio_track)(void* user, int32_t enabled, int64_t track_id, const char* name, int32_t encoding,
                           const char* language, int32_t channels, int32_t channel_mask, float sample_rate);
    int32_t (*video_track)(void* user, int32_t enabled, int64_t track_id, const char* name, int32_t encoding,
                           int32_t width, int32_t height, float frame_rate, int32_t has_alpha);
    int32_t (*subtitle_track)(void* user, int32_t enabled, int64_t track_id, const char* name, int32_t encoding,
                              const char* language);
    int32_t (*marker)(void* user, const char* name, double time);        /* no caller today; kept for ABI completeness */
    int32_t (*buffer_progress)(void* user, double clip_duration, int64_t start, int64_t stop, int64_t position);
    int32_t (*duration_update)(void* user, double duration);
    int32_t (*audio_spectrum)(void* user, double timestamp, double duration, int32_t query_timestamp);
    int32_t (*warning)(void* user, int32_t warning_code, const char* message);
} JfxmPlayerCallbacks;
```

Payloads are the exact arguments of the existing `NativeMediaPlayer.send*` methods, which are the
JNI targets today; the Java stub targets call those same methods, so `NativeMediaPlayer` and
everything above it are untouched. The C `CFfiPlayerEventDispatcher : CPlayerEventDispatcher`
keeps the `CPipeline::PlayerState -> eventPlayer*` mapping and the audio channel-mask remap from
`JavaPlayerEventDispatcher.cpp` verbatim (both are identity maps today, kept for safety), and
drops the dead `CreateObject/CreateBoolean/CreateInteger/CreateLong/CreateDouble/CreateDuration`
family.

## 11. Equalizer, bands, spectrum

```c
/* NativeAudioEqualizer (5). eq = handle from jfxm_player_get_audio_equalizer; NULL eq => 0 / no-op, as the JNI code. */
JFXM_EXPORT int32_t jfxm_eq_get_enabled(void* eq);
JFXM_EXPORT void    jfxm_eq_set_enabled(void* eq, int32_t enabled);
JFXM_EXPORT int32_t jfxm_eq_get_num_bands(void* eq);
JFXM_EXPORT void*   jfxm_eq_add_band(void* eq, double center_frequency, double bandwidth, double gain); /* band handle or NULL; Java constructs NativeEqualizerBand */
JFXM_EXPORT int32_t jfxm_eq_remove_band(void* eq, double center_frequency);

/* NativeEqualizerBand (6). band = handle from jfxm_eq_add_band. */
JFXM_EXPORT double  jfxm_eq_band_get_center_frequency(void* band);
JFXM_EXPORT void    jfxm_eq_band_set_center_frequency(void* band, double hz);
JFXM_EXPORT double  jfxm_eq_band_get_bandwidth(void* band);
JFXM_EXPORT void    jfxm_eq_band_set_bandwidth(void* band, double hz);
JFXM_EXPORT double  jfxm_eq_band_get_gain(void* band);
JFXM_EXPORT void    jfxm_eq_band_set_gain(void* band, double db);

/* Handing memory back: C calls this once when it has dropped its last reference to a block Java
 * gave it, on whichever thread dropped that reference (GST MainLoop / spectrum thread, the AVF
 * audio tap with the band lock held, or the app thread doing set_bands / dispose). The Java target
 * must be thread-safe, must not block and must not throw. After it returns, C never touches the
 * memory again - and not before. */
typedef void (*JfxmReleaseFn)(void* user);

/* NativeAudioSpectrum (7). spectrum = handle from jfxm_player_get_audio_spectrum.
 * set_bands replaces CJavaBandsHolder: magnitudes/phases point at two Java-allocated float arrays
 * of `count` elements each; C writes them in place from the spectrum thread (GST MainLoop / AVF
 * audio tap) before firing the audio_spectrum callback.
 *
 * The pair is owned by the refcounted CFfiBandsHolder C builds around it, exactly as
 * CJavaBandsHolder held a NewGlobalRef on the two float[]s - NOT by the call. CGstAudioSpectrum is
 * lock-free, so a spectrum thread already inside UpdateBands keeps writing through the old pair
 * after a newer set_bands call has returned. `release` is the only signal that the pair is dead:
 * it runs exactly once per set_bands call (also when spectrum is NULL, and if the call fails),
 * with release_user, on the thread that dropped the last reference. `release` may be NULL, in
 * which case the memory must outlive the media. */
JFXM_EXPORT int32_t jfxm_spectrum_get_enabled(void* spectrum);
JFXM_EXPORT void    jfxm_spectrum_set_enabled(void* spectrum, int32_t enabled);
JFXM_EXPORT void    jfxm_spectrum_set_bands(void* spectrum, int32_t count,
                                            float* magnitudes, float* phases,
                                            JfxmReleaseFn release, void* release_user);
JFXM_EXPORT double  jfxm_spectrum_get_interval(void* spectrum);
JFXM_EXPORT void    jfxm_spectrum_set_interval(void* spectrum, double seconds);
JFXM_EXPORT int32_t jfxm_spectrum_get_threshold(void* spectrum);
JFXM_EXPORT void    jfxm_spectrum_set_threshold(void* spectrum, int32_t db);
```

`NativeAudioSpectrum` keeps its `float[] magnitudes/phases` API (`getMagnitudes(float[])` copies
out); it copies from the shared segments when asked, which happens on the event thread after the
`audio_spectrum` event, i.e. at the same moment the JNI region copy used to be visible. The C
`CFfiBandsHolder : CBandsHolder` (refcounted exactly like `CJavaBandsHolder`) writes with
`memcpy`; `AVFAudioSpectrumUnit` and `CGstAudioSpectrum` are unchanged.

The `release`/`release_user` pair is what makes that safe. `CGstAudioSpectrum::UpdateBands` is
lock-free - it `AddRef`s the current holder, writes, then `ReleaseRef`s it - so an application that
calls `setBandCount()` during playback with a spectrum listener attached leaves the *previous*
holder alive and writing into the *previous* Java arrays after `jfxm_spectrum_set_bands` has
returned. `CJavaBandsHolder` survived that because it held a `NewGlobalRef` on each `float[]`;
`CFfiBandsHolder` reproduces it by calling `release(release_user)` from its destructor, i.e. at the
moment the last reference goes away. Java must treat that callback, and only that callback, as
permission to free the pair (section 4). On the AVF side the destructor can run under
`AVFAudioSpectrumUnit`'s band lock, so the callback must not block.

## 12. What is deleted (in a separate change from the migration)

| Path | Reason |
|---|---|
| `jfxmedia/platform/ios/**` (28 files), `java/.../platform/ios/{IOSPlatform,IOSMedia,IOSMediaPlayer}.java`, `NativeAudioClip.java`, `AudioClipProvider` in full (with `NativeAudioClip` gone its three methods were unconditional delegations, so `com.sun.media.jfxmedia.AudioClip` calls `NativeMediaAudioClip` directly and that class is now `public` within the module), the `isIOS()` platform branch in `PlatformManager`, the `platform/ios/**` jar excludes in `pom.xml` | decision 3 |
| `jfxmedia/jni/**` entirely (after the flip): `JavaPlayerEventDispatcher`, `JavaInputStreamCallbacks`, `JavaBandsHolder`, `JavaMediaWarningListener`, `JniUtils`, `Logger.cpp` JNI half, `NativeVideoBuffer.cpp`, `NativeAudioEqualizer.cpp`, `NativeAudioSpectrum.cpp`, `NativeEqualizerBand.cpp`, `com_sun_media_jfxmedia_logging_Logger.cpp`, `com_sun_media_jfxmediaimpl_NativeVideoConverter.cpp` (never compiled, no Java class) | replaced by `jfxmedia_api.cpp`, `FfiPlayerEventDispatcher`, `FfiStreamCallbacks`, `FfiBandsHolder`; `CLogger` keeps its level filter and becomes the FFM sink caller |
| `platform/gstreamer/GstJniUtils.cpp/.h` | zero callers |
| `Utils/MediaWarningDispatcher.cpp/.h` | never instantiated |
| `Utils/LowLevelPerf.cpp/.h` + `LOWLEVELPERF_*` macro sites | `ENABLE_LOWLEVELPERF 0` everywhere |
| `Utils/AutoLock.h`, `Utils/Thread.h`, `Utils/win32/WinThread.cpp`, `Utils/win32/WinDllMain.cpp` | no includer / no user / empty `DllMain` |
| `Utils/JObjectPeers.m/.h`, `Utils/JavaUtils.m/.h`, `Utils/MTObjectProxy.m/.h` | macOS JNI glue and a dead proxy |
| `Locator/Locator.cpp` JNI statics (`LocatorGetStringLocation`, `CreateConnectionHolder`, `GetAudioStreamConnectionHolder`) and the `jni/JniUtils.h` include in `Locator.h` | Java calls those Locator methods itself |
| `OSXMediaPlayer.mm` JNI section, `jobject`/`JavaVM*` ivars, `osxNeedsLocator` declaration | replaced by the AVF branch of `jfxmedia_api` |
| `CJavaPlayerEventDispatcher::Create*` object helpers | no callers |
| `src/main/native/vs_project/**`, `src/main/native/xcode_project/**` | Gradle-era IDE projects that reference deleted files; superseded by the CMake build |
| `jfxmedia/projects/{win,linux,mac}/Makefile`, `gstreamer/projects/**/Makefile*` | superseded by `modules/javafx.media/native/*.cmake` (the `.def` files and `def-*.pl` stay) |
| `-h ${project.build.directory}/gensrc/headers` in `modules/javafx.media/pom.xml`, `${JDK_HOME}/include*` in the media CMake | no JNI headers needed once the module is JNI-free |

Left alone on purpose: `Utils/ColorConverter.c` (`PARITY: unknown`), the 50 unreferenced upstream
files under `gstreamer-lite`/`3rd_party` (upstream-tracking friction outweighs 21 k dead lines;
listed in `FFM-AUDIT-plugins-libs.md` for a later decision), `src/tools/native/def-*.pl`.

## 13. Order of work

1. Build enablement: CMake for the media natives (`FFM-BUILD-PLAN.md`), verified on Windows
   against the unmodified JNI tree (identical export set to the prebuilt DLL).
2. C side, one change: `jfxmedia_api.h/.cpp`, `FfiPlayerEventDispatcher`, `FfiStreamCallbacks`,
   `FfiBandsHolder`, the FFM log sink in `CLogger`, the AVF branch in `OSXMediaPlayer.mm` /
   `OSXPlatform.mm` (unverified here), added **beside** the JNI code; library builds and exports
   both symbol sets.
3. Java side, one change: `JfxMediaNative` + flips of the eleven caller classes + iOS/AudioClip
   deletion + test tree + pom wiring; `mvn -pl modules/javafx.media install` green.
4. Remove the JNI code, the generated-header dependency and the JDK include paths; rebuild; run
   the binding tests and the hardware-free error-path test; review (`ffm-reviewer`).
5. Dead-code deletion (section 12 rows that are not JNI), docs (`FFM-STATUS.md`).

Nothing is committed by the automation; the user commits.

## 14. Implementation notes recorded after the C side landed

* `struct JfxmMedia` lives in `ffi/JfxmMediaHandle.h` (not inside `jfxmedia_api.cpp`) because
  `OSXMediaPlayer.mm` reads and writes it; for the AVF backend it also carries the create-time inputs
  (`content_type`, `location`, `size_hint`, the optional stream table and its user) until
  `jfxm_player_init` builds the player. New C sources live in `jfxmedia/ffi/`
  (`jfxmedia_api.cpp`, `FfiPlayerEventDispatcher`, `FfiStreamCallbacks`, `FfiBandsHolder`,
  `jfxmedia_avf.h`); the public header is `jfxmedia/jfxmedia_api.h`.
* `jfxm_media_create` never calls `property(HLS_PROP_HAS_AUDIO_EXT_STREAM)` itself: it creates the
  audio-stream adapter iff `audio_cb != NULL`. The decision is Java's (section 7), made on the same
  thread and at the same point as the JNI code made it.
* `jfxm_log_init` returns 0 when `ENABLE_LOGGING` is compiled out (the JNI `nativeInit` returned
  true unconditionally); `ENABLE_LOGGING` is 1 in `Common/ProductFlags.h`, so this is unobservable.
* Every `jfxm_player_*` forwarder, including `get/set_mute`, returns `ERROR_MEDIA_NULL` (257) for a
  NULL handle before any backend check; on the GST backend `get/set_mute` then return
  `ERROR_NOT_IMPLEMENTED` (2561). A handle whose AVF player was never created returns
  `ERROR_PIPELINE_NULL` (769) from the forwarders.
* AVF error codes (the six former `ThrowJavaException` sites): missing location / callbacks /
  content type -> `ERROR_MEMORY_ALLOCATION` (2562); unparsable URI -> `ERROR_FACTORY_INVALID_URI`
  (1027); no player class -> `ERROR_MEDIA_CREATION` (258); `jfxm_player_init` on a handle that
  already has a player -> `ERROR_MEDIA_CREATION`.
* Measured on Windows x64 (MSVC 19.44, C compile of the header): `sizeof(JfxmFrameInfo) == 120`,
  `sizeof(JfxmPlayerCallbacks) == 104`, `sizeof(JfxmStreamCallbacks) == 72`; field offsets
  timestamp 0, width 8, height 12, encoded_width 16, encoded_height 20, format 24, has_alpha 28,
  plane_count 32, reserved 36, strides 40, plane_size 56, plane_data 88.
* Quirks of the JNI code that the C side mirrors on purpose: `halt` with a NULL message reports
  failure and a NULL `warning` message is dropped; after `close_connection` the stream adapter
  answers reads with -1; `jfxm_eq_band_*` do not NULL-check the band; the `InitMedia` callback
  ownership on `CreatePlayer` failure is unchanged.

### 14.1 Deliberate departures from the JNI behaviour (do not "restore parity" by undoing these)

Everything above is behaviour-neutral. These few points are not, and each one is intentional:

* `jfxm_player_get_audio_equalizer` and `jfxm_player_get_audio_spectrum` NULL-check the pipeline
  before asking it for the equalizer/spectrum; `GstMediaPlayer.cpp`'s `gstGetAudioEqualizer` /
  `gstGetAudioSpectrum` NULL-checked only the media and then called `pMedia->GetPipeline()->
  GetAudioEqualizer()`, i.e. dereferenced a NULL pipeline. Only reachable through a
  handle whose pipeline was never built, which Java does not do, so the fix is unobservable - but
  it is a fix, not a copy.
* `jfxm_frame_get_info` reports the frame's real plane count and leaves the `strides` slots it did
  not fill at zero, so an empty frame comes back as `plane_count == 0` with
  `strides = [0, 0, 0, 0]`. `nativeGetPlaneStrides` returned **`null`** for `count < 1` *and* for
  `count > 4`. **The Java side must reproduce that**: build the `int[]` from `plane_count`, and
  return `null` when `plane_count < 1 || plane_count > 4`, rather than handing out four zeros -
  callers test the array for null.
* Leaks the C side fixes relative to the JNI code (a change in the safe direction, so the
  migration is not strictly neutral):
  * `CFfiStreamCallbacks` adapter built for a NULL callback table is destroyed instead of leaked;
  * the AVF `!player` path releases `eventHandler`, `locatorStream`, `callbacks` and `mediaURL`,
    which `osxCreatePlayer` leaked;
  * `jfxm_spectrum_set_bands` with a NULL spectrum deletes the holder (and so runs `release`);
    `nativeSetBands` leaked it;
  * `OSXMediaPlayer initWithURL:` sends `[self release]` on its early-return path.
* Windows only: `/OPT:ICF` folds functions with identical bodies, and once `__APPLE__` is compiled
  out `jfxm_player_get_mute` and `jfxm_player_set_mute` have identical bodies (both just return
  `ERROR_NOT_IMPLEMENTED` after the handle check). `dumpbin /EXPORTS` therefore shows them at the
  same RVA. A symbol test must assert that each name **resolves**, never that two names resolve to
  distinct addresses.
* `jfxm_spectrum_set_bands` takes `(JfxmReleaseFn release, void* release_user)` on top of the JNI
  argument list (sections 4 and 11). That is not decoration: without it C has no way to tell Java
  that a band pair is dead, and `setBandCount()` during playback corrupts the heap.
