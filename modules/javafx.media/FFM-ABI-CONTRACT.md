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
   uses restricted `java.lang.foreign` methods (`nativeLinker`, `loaderLookup`, `libraryLookup`,
   `downcallHandle`, `upcallStub`, `reinterpret`). Callers (`GSTMediaPlayer`, `GSTMedia`, `GSTPlatform`,
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
| `T[] out` one-element out-param | `T* out` | `ADDRESS` to the calling thread's shared scratch cell (see below) |
| `jobject` returned by C (`NativeEqualizerBand`) | never; C returns the `void*` handle and Java constructs the object | `ADDRESS` |
| `java.nio.ByteBuffer` returned by C (`NewDirectByteBuffer`) | pointer + size in a struct (`JfxmFrameInfo`) | `MemorySegment.ofAddress(p).reinterpret(size).asByteBuffer()` in the facade |
| `float[]` written by C (`SetFloatArrayRegion`) | `float*` into memory **allocated by Java** in a shared arena | `ADDRESS` |

Strings: the JNI code used `GetStringUTFChars`/`NewStringUTF` (modified UTF-8). Every string that
crosses this boundary is a URI, a MIME type, a GStreamer track name/language, or a log/warning
message; none can carry an embedded NUL and non-BMP characters were already mangled by the old
path, so standard UTF-8 is a benign change. `JfxMediaNative` never introduces modified UTF-8.

Booleans returned by C are `int32_t`; Java compares `!= 0`. Never declare a `JAVA_BOOLEAN` layout.

Out-parameters, and who owns the memory they point at: the eight entry points that take one
(`jfxm_player_get_audio_sync_delay`, `_get_rate`, `_get_presentation_time`, `_get_volume`,
`_get_balance`, `_get_duration`, `_get_mute`, and `jfxm_frame_get_info`) are called through wrappers
that pass **one per-thread scratch segment, allocated once per thread out of a shared
`Arena.ofAuto()` and never freed** (`JfxMediaNative.SCRATCH` / `scratch()`), sized and aligned for
the largest of them, `JfxmFrameInfo`. That is no longer the confined-arena-per-call shape of pattern
P4. Ownership is Java's for the life of the thread; C may write the cell only for the duration of
the call, must not retain the pointer, and **must write `*out` after any upcall it makes** - the
requirement stated in section 4, which is exactly what makes one cell per thread safe under the
re-entrancy `jfxm_player_get_duration` really has. `jfxm_media_create` keeps an `Arena.ofConfined()`
per call and is right to: it allocates variable-length strings from the same arena, and it genuinely
upcalls on the caller's thread (`need_buffer`, `is_seekable`, `is_random_access`, `property(2,3)`).

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
* **Spectrum band memory**: two `float` arrays allocated by Java in an `Arena.ofAuto()`, handed to
  C by pointer together with a `JfxmReleaseFn` upcall stub and an id. C owns each pair until it
  calls that stub - see section 11: the holder is reference counted, so a pair outlives the
  `setBandCount` that replaced it for as long as a spectrum thread is still writing through it.
  That callback, and nothing else, is permission to reuse the pair; reusing it on the next
  `setBandCount`, or assuming dispose is the only release point, is a use-after-free.
  The shape used is an **automatic** arena per pair that is never closed at all: the registry
  entry for the handover holds both segments, so the memory stays reachable until C runs the
  release, and becomes collectable the moment it does. Closing an arena is the one thing this
  callback must not do - it can run on a GStreamer spectrum thread, where closing a shared arena
  is a thread handshake, i.e. precisely the blocking the callback is forbidden. The release stub
  is a single process-wide one in `Arena.global()`, so no callback can close the arena its own
  stub lives in. The callback runs on a native thread, so it takes no locks Java holds across a
  downcall and catches `Throwable` like every other upcall target.

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
| `JfxmStreamCallbacks.need_buffer`, `is_seekable`, `is_random_access` | Java caller thread inside `jfxm_media_create` | Java caller thread inside `jfxm_player_init` |
| `read_next_block`, `read_block`, `copy_block`, `seek` | `javasource` task thread (push) or the pulling element's streaming thread; **may block** | `playerLoaderQueue` serial dispatch queue under the player lock; **may block** |
| `property` | `property(2,3)`: Java caller thread inside `jfxm_media_create` (`GstPipelineFactory.cpp:91,93,111`). `property(4,5)`: `javasource` task thread (`java_source_loop`, `javasource.c:571,574`). **`property(1)`: whichever thread runs a `GST_QUERY_DURATION` - including the Java thread that called `jfxm_player_get_duration`, nested inside that downcall** (note below). **May block** | never invoked: the only callers of `CStreamCallbacks::Property` are `GstPipelineFactory` and the `javasource` `property` signal, both GStreamer-only. (`property(6)` is not an upcall on either backend: Java asks the holder itself before `jfxm_media_create`, section 7.) |
| `close_connection` | thread driving READY->NULL, **under the element lock**; C deletes the adapter right after | dispose caller, under the player lock |
| `JfxmLogFn` | any of the above | any of the above |
| `JfxmReleaseFn` (band pair) | the thread that dropped the holder's last reference: MainLoop / spectrum thread, or the app thread inside `jfxm_spectrum_set_bands` / dispose | the app thread inside `jfxm_spectrum_set_bands` / dispose, or the thread that tears the audio tap down. **Not** the audio tap itself: `AVFAudioSpectrumUnit::UpdateBands` takes the band lock rather than a reference, so it can never drop the last one, and `SetBands` releases outside that lock |

`property(1)` (`HLS_PROP_GET_DURATION == 1`, `HLSConnectionHolder.java:77`) is the one slot in this
table that can run **on a thread that is inside a downcall of this ABI**, nested in it. Traced in
the source: `jfxm_player_get_duration` -> `GstPlayerGetDuration` (`ffi/jfxmedia_api.cpp:626`) ->
`CGstAudioPlaybackPipeline::GetDuration` (`GstAudioPlaybackPipeline.cpp:698`) ->
`gst_element_query_duration(m_Elements[PIPELINE], ...)`, a synchronous query answered on the calling
thread -> the `javasource` src pad's query function `java_source_query` (`javasource.c:738`,
installed by `gst_pad_set_query_function` at `javasource.c:298`) -> for `MODE_HLS`
`g_signal_emit(..., signals[SIGNAL_PROPERTY], 0, HLS_PROP_GET_DURATION, 0, &duration)`
(`javasource.c:762`) -> `CGstPipelineFactory::SourceProperty` (`GstPipelineFactory.cpp:318`) ->
`CFfiStreamCallbacks::Property` (`FfiStreamCallbacks.cpp:122`) -> the Java `property` target. Any
other thread that queries duration - a demuxer's streaming thread, the MainLoop handling a
`duration-changed` message - reaches the same slot the same way; the caller's thread is simply one
more of them.

**Requirement on this ABI: an out-param entry point must write `*out` after any upcall it makes.**
The trace above means "an out-param entry point never upcalls on the caller's thread" is *false*
here, and nothing may be built on it. What the Java side does rely on is the write ordering: the
out-param wrappers hand C one scratch cell per thread (section 2), which is safe precisely because
any upcall - and anything it does, including another out-param wrapper re-entered on the same
thread and reusing the same cell - has finished before `*out` is stored, and Java reads the cell
immediately after the downcall returns. Every out-param entry point satisfies this today: the seven
`jfxm_player_get_*` forwarders that have an out-parameter read the value first and store it into
`*out` as their last action (`ffi/jfxmedia_api.cpp`, and `platform/osx/OSXMediaPlayer.mm` for the
AVF ops), and
`jfxm_frame_get_info` fills the struct from `CVideoFrame` accessors and makes no upcall at all. A
new `jfxm_*` out-param entry point that upcalls *after* storing `*out` would break the Java side and
is forbidden by this contract.

Never use `Linker.Option.critical` for any function in this ABI: every downcall takes pipeline or
ObjC locks, and `gst_element_set_state` can block on preroll.

## 5. ABI version guard and layout checks

```c
#define JFXM_ABI_VERSION 3u
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

3 changed `JfxmStreamCallbacks::copy_block` from `void` to `int32_t` (section 9) and made
`jfxm_log_init` report success when logging is compiled out (section 6). Neither touches a symbol
name, an exported signature or `sizeof(JfxmStreamCallbacks)`, so symbol resolution and all three
layout checks still pass against a version-2 library - the *only* thing that separates the two is
`jfxm_abi_version`. A mismatched pair either has Java return a value C discards, or has C read a
return value Java never wrote and fail every block on a garbage byte count; the guard exists
precisely for a drift a size check cannot see.

**`fxplugins` is version-locked to `jfxmedia`.** The version-3 `copy-block` contract spans two
shared libraries and `jfxm_abi_version` guards only one of them: the signal's `G_TYPE_INT` return
type and its `source_marshal_INT__POINTER_INT` marshaller live in **fxplugins**
(`gstreamer/plugins/javasource/javasource.c:256`, `marshal.c:171`, `marshal.in`), while the handler
`CGstPipelineFactory::SourceCopyBlock` and `jfxm_abi_version` itself live in **jfxmedia**. A
mismatched pair passes every check in this section and then fails badly:

* fresh `fxplugins` + stale `jfxmedia`: the `INT__POINTER_INT` marshaller calls a `void`-returning
  `SourceCopyBlock` through a `gint (*)(gpointer, gpointer, gint, gpointer)` pointer and reads the
  return register as garbage, so `copied != size` on effectively every block -> `GST_FLOW_ERROR`
  from both `javasource` paths (section 9) -> no playback at all.
* stale `fxplugins` + fresh `jfxmedia`: the version-2 `VOID__POINTER_INT` marshaller discards the
  count, so the stack silently reverts to version-2 semantics - short copies unreported - with no
  diagnostic anywhere.

`modules/javafx.media/native/{win,linux,mac}.cmake` always build the two together, so this is a
mispackaging or library-shadowing hazard rather than a build hazard - and shadowing is a documented
real occurrence: `fxplugins` is loaded by name as a declared dependency of `jfxmedia`
(`JfxMediaNative.loadNativeLibraries`), `../caches/sdk/{bin,lib}` is still on `java.library.path`
through the root pom's `${jfx.native.librarypath}`, and `WEBKIT-MEDIA-STUBS.md` tells you to delete
stale `jfxmedia*`, `gstreamer-lite*`, `glib-lite*` and `fxplugins*` from there for exactly that
reason. Ship, cache, copy and delete the media libraries as one set; never mix builds.

The three drift guards exist because the generated JNI headers that used to keep the C copies of
`NativeMediaPlayer.eventPlayer*`, `AudioTrack.*` and `Logger.*` in step with Java are gone. Each
returns the library's own named constant, so the binding test fails if either side is renumbered.

## 6. Library loading and initialisation

`NativeMediaManager` keeps loading `glib-lite` (Windows/macOS), `gstreamer-lite` (non-Linux) and
`jfxmedia` in that order through `NativeLibLoader`, and `OSXPlatform` keeps loading
`jfxmedia_avf`. That sequence moves into `JfxMediaNative.loadLibraries()` (idempotent,
`NativeLibLoader` is synchronized and remembers loaded libraries) which both `NativeMediaManager`
and the facade's own static initializer call, so a binding test can touch the facade without
constructing the manager. The facade binds symbols through `SymbolLookup.loaderLookup()` when that
lookup can see the library, and falls back to
`SymbolLookup.libraryLookup(System.mapLibraryName("jfxmedia"), Arena.global())` when it cannot
(`JfxMediaNative.resolveLookup`, which probes `jfxm_abi_version` to decide); either way a missing
symbol throws `UnsatisfiedLinkError("missing native symbol: <name>")` at class-init time, like JNI's
lazy link failure but earlier and with a name. The fallback exists because the class that calls
`System.load` is `NativeLibLoader` in `javafx.graphics`, while `loaderLookup` answers only with
libraries loaded by classes of `JfxMediaNative`'s own defining loader: the same loader on every
standard launch (class path, module path, jlink image), but not necessarily in a hand-built
`ModuleLayer`, an OSGi bundle or a plugin container, where every `find` would come back empty
although `jfxmedia` had loaded perfectly well. The POSIX caveat, stated rather than hidden: a
bare-name `libraryLookup` ends in `dlopen("libjfxmedia.so")`, and although a loader answers a
directory-free name from its already-loaded list first (`LoadLibrary` by module base name, `dlopen`
by `DT_SONAME`), a name matching no loaded `DT_SONAME` sends `dlopen` on to `DT_RPATH`/`DT_RUNPATH`,
`LD_LIBRARY_PATH` and `ld.so.cache`, where it could in principle map a *second* copy of the library
beside the one already in the process. That is why `loaderLookup` stays the first thing tried, why
no directory is ever guessed here, and why the fallback is reached only once the loader lookup has
failed to find `jfxm_abi_version`.

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
 * during the call. Returns 1 on success and 0 only on a genuine failure (native allocation); a
 * build with ENABLE_LOGGING == 0 has no sink to install and so also returns 1, exactly as the JNI
 * nativeInit returned JNI_TRUE for that branch. */
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
 * Return = MediaError code (AVF: the six former ThrowJavaException sites map to their codes, plus
 * the added CLocatorStream check of section 14.1). */
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
    int32_t (*copy_block)(void* user, void* dst, int32_t size);        /* copy into dst[0..size); -> bytes copied */
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
created by `jfxm_media_create` where `CJavaInputStreamCallbacks` was. `CLocatorStream` is untouched;
`GstPipelineFactory::SourceCopyBlock` now forwards the count instead of discarding it.

`copy_block` returns the number of bytes it copied because the JNI `void` slot could not tell a
short copy from a good one: the Java target had already consumed the staged buffer, C had already
handed out a freshly allocated (uninitialised) GStreamer buffer, and the demuxer went on to parse
whatever was in it as media data. The Java target still fills the whole window - it copies what is
staged, zero-fills the remainder and logs ERROR on a shortfall - so the uninitialised-memory hazard
is closed on the Java side; the return value is what makes the failure *visible* to C. On the
success path the return equals `size` and nothing else changes.

What each native consumer does with a short return (`copied != size`), in every case the idiom the
surrounding code already uses for a failure it cannot recover from:

| Consumer | On a short return |
|---|---|
| `CFfiStreamCallbacks::CopyBlock` | returns the count up (0 for a NULL slot or a closed adapter, as before) |
| `CGstPipelineFactory::SourceCopyBlock` | returns the count as the `copy-block` signal's value |
| `javasource.c` push path (`java_source_loop`, `GST_EVENT_UNKNOWN`) | unrefs the buffer, sets `GST_FLOW_ERROR`, stops the task - the buffer is never pushed |
| `javasource.c` pull path (`java_source_getrange`) | unmaps and unrefs the buffer and returns `GST_FLOW_ERROR`, so the pulling element reports the failure instead of receiving short data |
| `AVFMediaPlayer.mm` resource-loader delegate | `break`s out of the fill loop without `respondWithData:`, exactly as a failed `ReadBlock`/`ReadNextBlock` (`blockSize <= 0`) does |

The GStreamer `copy-block` signal is registered with `G_TYPE_INT` and an `INT__POINTER_INT`
marshaller (`gstreamer/plugins/javasource/marshal.{in,c,h}`, regenerated by hand in
glib-genmarshal's own output shape); `read-next-block`, `read-block`, `seek-data` and `property`
already returned values through the same mechanism and are unchanged.

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
 * gave it, on whichever thread dropped that reference (GST MainLoop / streaming thread, the app
 * thread doing set_bands / dispose, or the thread tearing an AVF audio tap down; no C lock is held
 * across it). The Java target must be thread-safe, must not block and must not throw. After it
 * returns, C never touches the memory again - and not before. */
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
 * which case the memory must outlive the media.
 *
 * set_bands validates count > 0 and both buffers non-NULL, as every other entry point validates its
 * handle; a call that fails the check installs nothing, retires nothing and still runs `release`
 * once. No upper bound is checked: a positive count is the caller's promise about the buffers. */
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
`memcpy`.

`CAudioSpectrum::SetBands` now says who owns the reference it is handed: the caller keeps it and
drops it when the call returns, so an implementation that keeps the holder `AddRef`s it and
`ReleaseRef`s whichever holder it held before. The two implementations disagreed about that -
`CGstAudioSpectrum` consumed the caller's reference while `AVFAudioSpectrumUnit` added one of its
own - which pinned every pair on the AVF path at one reference for ever: `release` never ran, so
the facade's registry entry and the pair's arena were retained for the life of the JVM.
`CGstAudioSpectrum` gained the `AddRef` (the number of references it holds is unchanged, so the
GStreamer path behaves exactly as before), `AVFAudioSpectrumUnit`'s destructor gained the matching
`ReleaseRef` for the pair it is still holding at teardown, `CNullAudioSpectrum` conforms too, and
`jfxm_spectrum_set_bands` ends with a single `CBandsHolder::ReleaseRef` on every path.

The `release`/`release_user` pair is what makes that safe. `CGstAudioSpectrum::UpdateBands` is
lock-free - it `AddRef`s the current holder, writes, then `ReleaseRef`s it - so an application that
calls `setBandCount()` during playback with a spectrum listener attached leaves the *previous*
holder alive and writing into the *previous* Java arrays after `jfxm_spectrum_set_bands` has
returned. `CJavaBandsHolder` survived that because it held a `NewGlobalRef` on each `float[]`;
`CFfiBandsHolder` reproduces it by calling `release(release_user)` from its destructor, i.e. at the
moment the last reference goes away. Java must treat that callback, and only that callback, as
permission to free the pair (section 4). `AVFAudioSpectrumUnit::SetBands` drops the superseded
reference *after* it releases the band lock, so no destructor - and so no upcall - runs under a
lock the real-time audio tap is waiting on.

The exactly-once guarantee assumes `set_bands` calls on one spectrum are serialised, which is what
`NativeAudioSpectrum` does today. `CGstAudioSpectrum::SetBands` reads and then writes `m_pHolder`
rather than exchanging it, so two threads calling `setBandCount()` on the same spectrum at once can
both release the same superseded holder and neither release one of the new ones. That shape is
older than this ABI and is listed in `FFM-STATUS.md` section 4a; making it an exchange is the fix
if the assumption ever stops holding.

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
* The AVF backend reads through the `Locator` iff `has_stream` is set, and tests nothing else.
  `OSXMedia.initNativeMedia` makes the `jar:`/`jrt:` decision on `locator.getURI().getScheme()` and
  installs a stream table only then; `jfxm_media_create` records that in `JfxmMedia.has_stream`, and
  `jfxm_avf_player_init` builds the `CFfiStreamCallbacks` + `CLocatorStream` pair - and so installs
  the `AVAssetResourceLoader` delegate, which `AVFMediaPlayer initWithURL:` keys off the stream
  being non-NULL - exactly when it is set. It does **not** re-derive the scheme from `[mediaURL
  scheme]`: the same decision taken twice, on `java.net.URI` and on `NSURL`, could only disagree.
  A disagreement used to end `jfxm_player_init` with `ERROR_MEMORY_ALLOCATION`, which Java reports
  as `MediaException("unable to create player")`, for what was a URL-parsing difference and not a
  failed allocation.
* `jfxm_log_init` returns 1 when `ENABLE_LOGGING` is compiled out, as the JNI `nativeInit` did.
  It briefly returned 0 there, which made a healthy logging-free build report a logger-init failure;
  `ENABLE_LOGGING` is 1 in `Common/ProductFlags.h`, so no shipped build ever saw it. The two paths
  that return 0 are now one: `CLogger::initSink` failing, which happens only when the logger
  singleton cannot be allocated.
* Every `jfxm_player_*` forwarder, including `get/set_mute`, returns `ERROR_MEDIA_NULL` (257) for a
  NULL handle before any backend check; on the GST backend `get/set_mute` then return
  `ERROR_NOT_IMPLEMENTED` (2561). A handle whose AVF player was never created returns
  `ERROR_PIPELINE_NULL` (769) from the forwarders.
* Those twenty forwarders take the backend decision exactly once, in the `PlayerOps` lookup of
  `jfxmedia_api.cpp`: each backend fills one `JfxmPlayerOps` table of function pointers
  (`GST_PLAYER_OPS`, and `AVF_PLAYER_OPS` on Apple) and the exported function resolves the handle to
  a table and calls through it. A NULL handle resolves to no table, which is where the uniform
  `ERROR_MEDIA_NULL` (`NULL` for the two handle getters) comes from. Every slot is a constructor
  parameter without a default, so a new player entry point cannot be added without every backend
  filling its slot - the compile fails instead of the new function silently falling through to
  GStreamer, which is what a forgotten per-function `#ifdef __APPLE__` preamble used to do.
  `jfxm_media_create` and `jfxm_media_dispose` are deliberately not slots: they build and tear down
  the handle itself rather than forwarding to a player.
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
  * `jfxm_spectrum_set_bands` with a NULL spectrum drops the last reference to the holder (and so
    runs `release`); `nativeSetBands` leaked it;
  * `AVFAudioSpectrumUnit` releases the band holder it is still keeping when it is destroyed, and
    no longer holds a reference the caller never drops (section 11); the JNI code leaked every
    `CJavaBandsHolder` on macOS for the same reason;
  * `OSXMediaPlayer initWithURL:` sends `[self release]` on its early-return path;
  * the AVF `jar:`/`jrt:` path checks the `CLocatorStream` allocation and returns
    `ERROR_MEMORY_ALLOCATION`, deleting the `CFfiStreamCallbacks` adapter with it. `osxCreatePlayer`
    left that `new (nothrow)` unchecked, so a failure both leaked the adapter and built an `AVPlayer`
    with no resource loader for a URL AVFoundation cannot open itself - the failure surfaced later
    and elsewhere, as an asynchronous `AVPlayerItemStatusFailed`. This is the seventh return of
    `jfxm_player_init` on AVF. A *throwing* `CLocatorStream` constructor is still uncovered, exactly
    as in `InitGstMedia`.
* `OSXMedia.dispose()` closes the `jar:`/`jrt:` `ConnectionHolder` once `jfxm_media_dispose` has
  returned. On AVF the `close_connection` upcall fires only from `AVFMediaPlayer`'s own dispose,
  so any failure after `jfxm_media_create` had already succeeded - a failing `jfxm_player_init`,
  say - left the stream open for the life of the JVM: `ConnectionHolder` has no finalizer and no
  `Cleaner`, and `CallbackTable.unregister()` only drops the registry entry. The JNI code was
  worse still, leaking the global ref permanently. `ConnectionHolder.closeConnection` is
  idempotent, so on the normal path - where the upcall already ran inside `jfxm_media_dispose` -
  closing a second time is a no-op. `GSTMedia` needs none of this: its pipeline teardown drives
  READY->NULL, which fires `close_connection` on every path.
* Windows only: `/OPT:ICF` folds functions with identical bodies, and once `__APPLE__` is compiled
  out `jfxm_player_get_mute` and `jfxm_player_set_mute` have identical bodies (both just return
  `ERROR_NOT_IMPLEMENTED` after the handle check). `dumpbin /EXPORTS` therefore shows them at the
  same RVA. A symbol test must assert that each name **resolves**, never that two names resolve to
  distinct addresses.
* `NativeAudioSpectrum.setBandCount(int)` leaves the installed pair alone when it rejects a count.
  The JNI implementation set `magnitudes` and `phases` to `EMPTY_FLOAT_ARRAY` *before* throwing
  `IllegalArgumentException`, so a rejected argument made `getBandCount()` report 0 and
  `getMagnitudes()`/`getPhases()` report an empty spectrum while C went on writing through the
  pair it still owned. Retiring a live pair is exactly what `release(Bands)` is documented not to
  do, and the throw is unreachable from public API (`MediaPlayer` clamps to
  `AUDIOSPECTRUM_NUMBANDS_MIN`), so the mutation is gone rather than reproduced.
* `jfxm_spectrum_set_bands` takes `(JfxmReleaseFn release, void* release_user)` on top of the JNI
  argument list (sections 4 and 11). That is not decoration: without it C has no way to tell Java
  that a band pair is dead, and `setBandCount()` during playback corrupts the heap.
