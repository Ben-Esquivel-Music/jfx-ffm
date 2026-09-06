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
   run by Maven (see `FFM-BUILD-PLAN.md`), so the two sides cannot drift. No exception is thrown
   across the boundary by design, with one gap under memory exhaustion: `new (nothrow)` covers the
   allocation itself but not a throwing constructor or a throwing assignment, so `std::bad_alloc`
   can still escape `extern "C"` — at the two `CLocatorStream` constructions in `ffi/jfxmedia_api.cpp`
   and `platform/osx/OSXMediaPlayer.mm`, at the `location`/`content_type` `std::string` assignments
   in `jfxm_media_create`'s AVF branch, and at the unchecked plain `new CLogger` in `jni/Logger.cpp`.
   That is inherited from the pre-FFM code, not introduced by this ABI.
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
| frame | `CVideoFrame*` (`CGstVideoFrame` / `CVVideoFrame`) | delivered through `new_frame`, or returned by `jfxm_frame_convert` | Java, exactly as today: `NativeVideoBuffer.releaseFrame()` -> `jfxm_frame_dispose` when the hold count reaches 0. One exception: a **NULL `new_frame` slot** transfers nothing, so the dispatcher disposes the frame itself (section 10) |

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
| `close_connection` | thread driving READY->NULL, **under the element lock**; the adapter is freed by `CGstPipelineFactory::SourceCallbacksDestroyed`, either from the disconnect at the end of `SourceCloseConnection` after a real transition or from `g_signal_handlers_destroy` when `g_object_unref` finalizes an element that never left `GST_STATE_NULL` | dispose caller, under the player lock |
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
#define JFXM_ABI_VERSION 4u
JFXM_EXPORT uint32_t jfxm_abi_version(void);
JFXM_EXPORT int32_t  jfxm_sizeof_player_callbacks(void);
JFXM_EXPORT int32_t  jfxm_sizeof_stream_callbacks(void);
JFXM_EXPORT int32_t  jfxm_sizeof_frame_info(void);
JFXM_EXPORT int32_t  jfxm_offsetof_frame_info(int32_t field);        /* field index per section 8; -1 if out of range */
JFXM_EXPORT int32_t  jfxm_offsetof_player_callbacks(int32_t field); /* JFXM_PLAYER_CALLBACKS_* index; -1 if bad */
JFXM_EXPORT int32_t  jfxm_offsetof_stream_callbacks(int32_t field); /* JFXM_STREAM_CALLBACKS_* index; -1 if bad */
/* Drift guards: the Java constant this library copies, by index; -1 if out of range. */
JFXM_EXPORT int32_t  jfxm_event_player_state(int32_t pipeline_state);
JFXM_EXPORT int32_t  jfxm_audio_track_channel(int32_t channel);
JFXM_EXPORT int32_t  jfxm_log_level(int32_t level);
```

`JfxMediaNative` checks `jfxm_abi_version()` right after the library loads and throws an
`UnsatisfiedLinkError` naming expected and actual versions. The binding test compares the three
`StructLayout.byteSize()` values and every `JfxmFrameInfo` field offset with the C side.

### 5.1 Why the two callback tables need `offsetof` and not just `sizeof`

`jfxm_sizeof_player_callbacks` / `_stream_callbacks` cannot detect the failure they look like they
guard against, and this is worth stating explicitly because it was a real review finding (**S1**):

* **Every slot in both tables is a function pointer.** Any permutation of the slots therefore
  produces the **same `byteSize`**. A size check is *structurally incapable* of detecting a
  reordering.
* **The Java layouts are generated from the Java slot arrays.** `PLAYER_CALLBACKS` and
  `STREAM_CALLBACKS` are built by `tableLayout(PLAYER_SLOTS)` / `tableLayout(STREAM_SLOTS)`, and
  `invokeSlot` then reads slots by name out of that same Java-derived layout. The two sides agreed
  because they were derived from one source, not because anything compared them.

A divergence - a slot inserted in the middle of the C struct, or two reordered - would leave both
sides self-consistent, keep every existing test green, and show up only at runtime as calls landing
on the **wrong function pointer**: video frames delivered to the error handler, or a `size_t` read as
a callback address. Detecting that needs actual playback, which this fork has only ever done by hand.

`jfxm_offsetof_player_callbacks` and `jfxm_offsetof_stream_callbacks` close it. Each takes an index
from the matching enum in `jfxmedia_api.h` - `JFXM_PLAYER_CALLBACKS_MEDIA_ERROR = 0` through
`_WARNING = 12` (`JFXM_PLAYER_CALLBACKS_FIELD_COUNT = 13`), `JFXM_STREAM_CALLBACKS_NEED_BUFFER = 0`
through `_CLOSE_CONNECTION = 8` (`JFXM_STREAM_CALLBACKS_FIELD_COUNT = 9`) - resolves it to a **named**
struct member and returns the C compiler's own `offsetof`, or `-1` for anything out of range. They are
pure, callable from any thread and hold no state; `jfxm_offsetof_frame_info` was the model.
`JfxMediaNativeTest.callbackTableSlotOffsetsMatchTheCompiledStructs` asserts, per index,
`LAYOUT.byteOffset(groupElement(name(i))) == offsetofXxx(i)`, plus `-1` at `-1` and at the slot count.
It deliberately does **not** hard-code `i * 8` as the expectation - that would be the
by-construction trap all over again.

**The invariant a future change must keep, and it is append-only:** append the new slot to the end of
the C struct, append its enumerator immediately before `*_FIELD_COUNT`, bump the count, append the
matching entry to the end of the Java `PLAYER_SLOTS` / `STREAM_SLOTS` array. Never insert into the
middle, and never renumber.

**The residual, recorded so the test is not read as stronger than it is:** C exports an *index*, not a
slot *name*. Reordering the Java `PLAYER_SLOTS` array moves both sides of every comparison together,
so it stays green. What the assertion does pin is that **C's index enum agrees with C's struct
declaration order** - the "move a member, forget to renumber the enum" edit that silently redirects
every upcall while `sizeof` stays 104 / 72. Closing the Java half would need C to export the slot
*names*.

### 5.2 Version history, and when a bump is the right instrument

1 was the initial ABI; 2 added `jfxm_audio_track_channel` and `jfxm_log_level`. Adding a symbol bumps
the version even though it takes nothing away, because `JfxMediaNative` binds every handle eagerly -
a library built before those two fails on symbol resolution and reports a missing symbol instead of
the version mismatch this guard exists to produce.

3 changed `JfxmStreamCallbacks::copy_block` from `void` to `int32_t` (section 9) and made
`jfxm_log_init` report success when logging is compiled out (section 6). Neither touches a symbol
name, an exported signature or `sizeof(JfxmStreamCallbacks)`, so symbol resolution and all three
layout checks still pass against a version-2 library - the *only* thing that separates the two is
`jfxm_abi_version`. A mismatched pair either has Java return a value C discards, or has C read a
return value Java never wrote and fail every block on a garbage byte count; the guard exists
precisely for a drift a size check cannot see.

4 added `jfxm_offsetof_player_callbacks` and `jfxm_offsetof_stream_callbacks` (section 5.1). Nothing
existing changed shape, and the bump is under the **revision-2** rule rather than the revision-3 one:
eager binding means an older library answers a new symbol with "missing native symbol:
jfxm_offsetof_player_callbacks" instead of the one-sentence version mismatch. The mixed pair that
actually occurs here is a Java side *newer* than the library it finds - `-DskipNative=true` reusing
`target/native/bin`, or a stale `jfxmedia` in `../caches/sdk/bin` shadowing a fresh one, both of which
this fork has hit - and `checkAbiVersion()` runs in its own static block before any other symbol
binds, so the bump turns that into one sentence naming both versions.

**Not every departure is a bump, and the distinction matters.** Read the revision-3 note precisely: it
bumped because a mismatched pair would **silently misbehave**, with nothing else to tell the two sides
apart. A behaviour change that *cannot* silently misbehave does not need the version constant, and
spending it anyway trains people to ignore it. The worked example is the departure recorded in section
14.1 for a second `jfxm_player_init` on one media handle: the affected path has no in-tree caller, and
both mix directions fail safely - an older Java against a newer library receives an error code it
already handles, and a newer Java against an older library gets the pre-existing leak. That one is
written into section 14.1 and **`JFXM_ABI_VERSION` stays at 4**. The rule, for the next person facing
this call: **the guard exists to convert a silent corruption into a clean diagnostic, not to count
edits.** Bump when a mismatched pair would misbehave without saying so; otherwise write it in 14.1.

*One note for anyone reading this against the branch review.* The review reports a stale ABI
**function count** in this document, alongside the genuinely stale ones in `FFM-STATUS.md`. There was
never one: this document has never quoted how many `jfxm_*` functions exist, and its only other
number of that shape - the 54 `Java_*` exports of the prebuilt JNI-era `jfxmedia.dll` in section 0 -
is about master and is correct. What *was* stale here is the `#define JFXM_ABI_VERSION` mirrored at
the top of this section, which said `3u`; it now says `4u` and matches `jfxmedia_api.h`. Do not go
looking for a function count to fix.

### 5.3 `fxplugins` is version-locked to `jfxmedia`

The version-3 `copy-block` contract spans two shared libraries and `jfxm_abi_version` guards only one
of them: the signal's `G_TYPE_INT` return type and its `source_marshal_INT__POINTER_INT` marshaller
live in **fxplugins** (`gstreamer/plugins/javasource/javasource.c:256`, `marshal.c:171`,
`marshal.in`), while the handler `CGstPipelineFactory::SourceCopyBlock` and `jfxm_abi_version` itself
live in **jfxmedia**. A mismatched pair passes every check in this section and then fails badly:

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

`libjfxmedia_avf.dylib` is locked to `libjfxmedia.dylib` the same way, for a reason **no version
number can guard at all** - see the cross-dylib vtable packaging invariant in section 14.3.

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
 * After it returns no callback of any table fires again; Java then unregisters and closes arenas.
 * How far that promise actually reaches, per backend and per path, is in section 7.1. */
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

Failure of `jfxm_player_init` leaves the media handle alive: the Java caller must call `jfxm_media_dispose` itself before propagating the error when it created the media in the same constructor (`OSXMediaPlayer`); `GSTMediaPlayer` leaves that to `GSTMedia.dispose()` exactly as today. On AVF no callback of the table fires after a failed init — `jfxm_avf_player_init` deletes the dispatcher on every failure path. On GST that is not true: the GST backend has by then installed a by-value copy of the table on the pipeline, and `CGstAudioPlaybackPipeline::Init` attaches the bus watch and starts the main loop before the `gst_element_set_state(PIPELINE, GST_STATE_PAUSED)` call that can fail, so a sink error there — the ordinary "audio device cannot be opened" case on a headless machine — still reaches `SendPlayerMediaErrorEvent` through `BusCallback` on the media-manager main-loop thread, a slot of that same table, after `jfxm_player_init` has already returned failure. That is exactly why the function pointers and user values must stay valid until `jfxm_media_dispose` has returned — the same lifetime rule `jfxm_media_create` states for the stream tables. `jfxm_player_init` may be called at most once per media handle, and **both backends now enforce that the same way**: a second call returns `ERROR_MEDIA_CREATION` and leaves the first dispatcher in place (section 14.1). AVF has always done so; the GST backend used to replace the dispatcher, leak the old one and return `CPipeline::Init()`'s result. `NativeMediaPlayer.dispose()` already calls `playerDispose()` and then `media.dispose()` under one lock, so `OSXMedia.dispose()` is where the AVF teardown now happens (previously `osxDispose` in `playerDispose()`); the only difference is that the Java-side field clearing of `playerDispose()` runs a few lines earlier.

`GSTMediaPlayer` keeps `throwMediaErrorException` on non-zero returns; `OSXMediaPlayer` keeps
today's convention (throw only on create/init, ignore return codes of the other calls, return the
same defaults as before when a call fails). The `jlong` <-> `long` truncation of
`gstSetAudioSyncDelay` on Windows (C `long` is 32-bit) is preserved on the C side by casting
exactly as the pipeline code does; the ABI type is `int64_t` because that is what `jlong` was.

### 7.1 How far the `jfxm_media_dispose` guarantee actually reaches

`jfxm_media_dispose`'s header comment says *"after it returns no callback of any table fires again;
Java then unregisters and closes arenas."* Java relies on it literally: `OSXMediaPlayer` and
`GSTMediaPlayer` close the `Arena.ofShared()` that owns all 13 upcall trampolines the moment dispose
returns, which **unmaps** them. A callback arriving afterwards is not a stale-pointer read that
usually gets away with it, as it was under JNI; it is a jump to an unmapped page. Under JNI the same
race existed and a stale `CJavaPlayerEventDispatcher*` frequently still held a usable `JavaVM*`, so
it often "worked". The migration did not create that race - it removed the luck that was hiding it.

**The rule the AVF fence follows, and it is the whole design in one line:** hold the monitor across
the `eventHandler->Send*` calls, and **never across an AVFoundation call**. It is written into
`-observeValueForKeyPath:ofObject:change:context:` in those words, with the reason named -
`-resourceLoader:shouldWaitForLoadingOfRequestedResource:` takes this same monitor on the loader
queue, so any AVFoundation call under the monitor that can trigger a synchronous load is a lock-order
inversion - and `-extractTrackInfo` and `-createVideoOutput` back-reference it. Everything
AVFoundation-side is therefore outside the monitor: the change dictionary, `_player.error`,
`CMTimeGetSeconds(...duration)`, every `AVAssetTrack` read, and the audio-mixer wiring.

| Path | State |
|---|---|
| GStreamer, all slots | **Honoured.** `jfxm_media_dispose` drives the pipeline to `GST_STATE_NULL` and tears down the bus watch before returning; no GStreamer thread can be inside a slot after that. |
| AVF KVO (`-observeValueForKeyPath:`), duration and tracks | **Fenced.** An advisory unlocked early-out, then the monitor **only around the sends**. `-removeObserver:forKeyPath:` does not drain a notification already being delivered on another thread (a documented Apple hazard), so the monitor is what closes it, not the removal. No longer deadlock-prone, because no AVFoundation call is made under it. |
| AVF track extraction (`-extractTrackInfo`) | **Fenced**, by the same shape: unlocked early-out, monitor around the sends only, `AVAssetTrack` reads outside it. |
| AVF `AVPlayerItemDidPlayToEndTimeNotification` main-queue block | **Fenced**, via `-setPlayerState:`, which is the funnel every state event in this player passes through from all four calling threads - the Java caller, the KVO thread, the main queue and the disposing thread. Its whole body is under the monitor behind an `isDisposed` test, which it can afford because it is nothing but a send. This is the path that previously had no fence at all: `-removeObserver:` does not cancel a block already enqueued on the main queue, and `blockSelf` is only `__weak`, so it can still be non-nil while an autorelease pool holds the player. `-dispose` calls `-setPlayerState:` itself, from inside the monitor and **before** setting `isDisposed`, so the HALTED event still goes out exactly as before - `@synchronized` is recursive per thread. |
| AVF video output (`-createVideoOutput`) | **Fenced** by an `isDisposed` test inside its pre-existing monitor - and it is **the one place left where the monitor is held across AVFoundation calls** (`addOutput:`, the display link). See the constraint below. |
| AVF audio spectrum (`-sendSpectrumEventDuration:timestamp:`) | **Honoured, and permanently unfenced by necessity.** `SetBands(0, NULL)` blocks until an in-flight `UpdateBands` upcall returns, after which `mBands` and `mSpectrumCallbackProc` are NULL - so it is genuinely fenced by the thing that already had to block. Adding the monitor here deadlocks; see below. |
| AVF display link (`displayLinkCallback` -> `-sendPixelBuffer:frameTime:hostTime:`) | **Not honoured - HIGH-M1 is OPEN on this path.** An unlocked `isDisposed` test only. See below. |

**`-createVideoOutput` is a known, accepted exception to the rule.** It predates this fork and was not
narrowed with the rest. It survives the rule for a specific reason worth keeping written down rather
than leaving in a comment: none of the calls it makes under the monitor reads an `AVAssetTrack`
property, which is the call class with a documented synchronous-load fallback, and whatever loading
`addOutput:` goes on to cause happens on AVFoundation's own threads and is not waited for here.
Narrowing it anyway is a reasonable follow-up, and **it is the second place to look if a `jar:`/`jrt:`
source ever hangs at load** - the first being the resource-loader inversion the rule exists to
prevent.

**The two things `-dispose` waits on, which is why the fence is shaped the way it is.** `-dispose`
holds the monitor from first line to last, and inside it waits on another thread twice:

* `SetBands(0, NULL)` waits on the **real-time audio thread**: `AVFAudioSpectrumUnit::UpdateBands`
  holds `mBandLock` across `mSpectrumCallbackProc`, and `SetBands` takes that same lock. So the RT
  audio thread **must never take this monitor** - and does not, because
  `-sendSpectrumEventDuration:timestamp:` is deliberately left unfenced. **Fencing it deadlocks
  immediately**: dispose waiting on the band lock, RT thread waiting on the monitor. That is written
  into the code comment as well, so that nobody "finishes the job" by adding it.
* `CVDisplayLinkStop` may or may not wait on the display-link thread. If it does, giving
  `-sendPixelBuffer:` this monitor is a guaranteed hang on the thread running `MediaPlayer.dispose()`
  whenever a frame is in flight - i.e. routinely. Trading an unproven crash for a probable hang is
  the wrong way round, which is why the guard there is a plain flag test.

**HIGH-M1 is closed on the KVO, track and main-queue paths and OPEN on the display-link path. Do not
record it as fixed.** An adversarial review of the fix established that the display-link path stays
open *whichever way* the `CVDisplayLinkStop` question resolves, which is why the flag test is a
mitigation and not a fix:

* **If `CVDisplayLinkStop` does join** an executing callback, then the guard in
  `-sendPixelBuffer:` is dead code - the race it tests for cannot happen - and the path is safe for
  a reason that has nothing to do with the guard.
* **If it does not join**, the guard is insufficient. `displayLinkCallback` dereferences the
  unretained `(__bridge void*)self` in **five places outside** the guarded send, and calls
  `CVDisplayLinkStop` on an already-released reference. Testing `isDisposed` inside
  `-sendPixelBuffer:` narrows the upcall window to a few instructions; it does nothing about the
  use-after-free of `self` on the way there.

**What settles the display-link case:** someone on a Mac establishing whether `CVDisplayLinkStop`
joins. If it joins, nothing further is needed on this path. If it does not, the fix is larger than a
flag: the callback has to stop touching an unretained `self` at all, and the fence has to be a lock
`-dispose` can release *before* calling `CVDisplayLinkStop` (or a trylock the display-link thread can
fail), never this object's monitor, which it holds throughout.

**`isDisposed` is `_Atomic(BOOL)`**, not `volatile BOOL`. The generated code is the same; what changes
is that the plain concurrent read/write pair it replaced was formally undefined behaviour, which
matters here because the display-link guard reads it with no lock at all. **Flagging it as the single
highest uncompiled risk token in this diff:** only clang compiling Objective-C++ will ever see that
declaration, and nothing on the machine this was written on can. If macOS CI rejects `_Atomic(BOOL)`
in an ObjC++ header, `volatile BOOL` is the fallback - it is what was there before, and it loses only
the formal guarantee, not the behaviour.

**What has and has not been verified for this table.** The AVF backend **is compiled and linked in
CI on both macOS architectures on every push** - `macos_x64_build` and `macos_aarch64_build`, a full
`mvn install` with no `-DskipNative`, `Built target jfxmediaAvf` in the logs - and because
`add_media_library` uses `SHARED` with the default `-undefined error`, macOS is the one platform
where a dangling symbol is fatal at link, so those green links are real symbol-level evidence
(`FFM-STATUS.md` section 4). What that does **not** establish is any of the behaviour in this table.
**No macOS runtime behaviour has been observed at all**, no macOS playback has ever run, and the
dispose-fencing changes described here are **uncommitted**, so they have not been in any CI run
either. There is no Objective-C toolchain on the machine this was written on, so every row above is
reasoning from source.

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
 * handling it had for a JNI exception). Strings are UTF-8 and valid only during the call.
 * A NULL new_frame slot is the one exception to "NULL is treated as delivered": with no target to
 * take ownership, the dispatcher disposes the frame itself, so it is freed exactly once either
 * way. A slot that returns 0 is not that case and does not free the frame: C cannot tell whether
 * the target had already taken it, already disposed of it, or never touched it. */
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

Everything above is behaviour-neutral **with one exception stated in place**: section 9's
`copy_block` change from a `void` slot to an `int32_t` one. That is a departure, section 9 is honest
about it, and it is listed again at the end of this section so that a reader of the register does not
have to have read section 9 to know about it.

This section is the register. Its purpose is that **every departure is declared, not that there are
none** - the project's rule is that a migration commit is observably equivalent, and the owner's
position on breaking that rule is that a change which genuinely fixes a bug is welcome, "in the
spirit of making it better". What is not acceptable is silence. Nothing below has been reverted or
split out; what has changed is that it is written down, with a rationale and with the **observable
delta** a user or a caller could notice. Three registers exist and they do not overlap: this one for
behaviour departures in first-party code, section 14.2 for the vendored `gstreamer-lite` patches,
section 14.3 for cross-language invariants the code depends on and no compiler enforces.
`FFM-STATUS.md` section 4a is the fourth: pre-existing defects deliberately left alone.

Each one is intentional:

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
  * a NULL `new_frame` slot deletes the frame instead of leaking it (section 10);
    `CJavaPlayerEventDispatcher::SendNewFrameEvent` had no table, and its equivalent no-target
    paths - a NULL `pEnv` from a detached VM, a cleared `m_PlayerInstance` global ref - returned
    false and leaked `pVideoFrame`. `JfxMediaNative` fills all 13 player slots, so this is a
    guarantee for a future or third-party filler of the table, not a live leak that stopped;
  * the AVF `!player` path releases `eventHandler`, `locatorStream`, `callbacks` and `mediaURL`,
    which `osxCreatePlayer` leaked;
  * `AVFMediaPlayer -dispose` deletes the `CFfiStreamCallbacks` adapter after `CloseConnection` and
    then the `CLocatorStream` that holds it, and `-[AVFMediaPlayer initWithURL:...]` deletes the
    same pair on the two paths where it returns nil. The JNI code closed the connection and dropped
    the pointer, so every `jar:`/`jrt:` AVF player leaked both allocations for the life of the JVM.
    Ownership passes to that initializer the moment it is entered, and `jfxm_avf_player_init`'s own
    cleanup runs only when no `AVFMediaPlayer` was allocated at all, so the pair is freed exactly
    once. The delete cannot race an `AVAssetResourceLoader` callback, because the resource-loader
    delegate body and `-dispose` are both wholly inside `@synchronized(self)`;
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
* **Connection holders are closed by Java, on both backends, on every path.** An earlier revision of
  this register said *"`GSTMedia` needs none of this: its pipeline teardown drives READY->NULL, which
  fires `close_connection` on every path."* **That is false**, `GSTMedia.dispose()` does exactly the
  thing the sentence says it does not need, and the code is right while the doc was wrong. Recorded
  properly:
  * `OSXMedia.dispose()` closes the `jar:`/`jrt:` `ConnectionHolder` once `jfxm_media_dispose` has
    returned. On AVF the `close_connection` upcall fires only from `AVFMediaPlayer`'s own dispose, so
    any failure after `jfxm_media_create` had already succeeded - a failing `jfxm_player_init`, say -
    left the stream open for the life of the JVM: `ConnectionHolder` has no finalizer and no
    `Cleaner`, and `CallbackTable.unregister()` only drops the registry entry. The JNI code was worse
    still, leaking the global ref permanently.
  * `GSTMedia.dispose()` does the same for `streamConnection` and `audioStreamConnection`, and needs
    to. `close_connection` reaches the holders from `CGstPipelineFactory::SourceCloseConnection`,
    i.e. from the pipeline's `READY -> NULL` transition - so a pipeline that **never left
    `GST_STATE_NULL`** never closes them at all. A media whose `jfxm_player_init` failed, or one that
    `MediaManager.getMedia()` created and nothing ever played, is exactly that case, and its
    connection stayed open for the life of the JVM.
  * `GSTMedia.createNativeMedia` closes the holders it created on **every** failing return (the
    `finally` block, which also calls `releaseCallbacks()` and clears both fields).
    `OSXMedia.initNativeMedia` does the same for its single holder. On the success path C closes them;
    but a failing `jfxm_media_create` never received the tables, so nothing else ever would. The JNI
    code left the connection open until the holder was collected.
  * **Observable delta, and it is the reason this belongs in a register rather than a changelog:**
    there is now **synchronous close I/O on the disposing thread, under the media monitor** - and for
    an HLS source that can be **network I/O**. `GSTMedia.dispose()` is `synchronized` and
    `NativeMediaPlayer.dispose()` calls it under its own lock, so a slow close is a slow
    `MediaPlayer.dispose()`. The JNI build had the same shape wherever the transition did happen
    (`gstDispose` drove `READY -> NULL` from inside the same `synchronized dispose()`, and
    `close_connection` ran the same close on that thread); the close that is genuinely new is the one
    for a pipeline that never reached `READY`, where the holder has nothing open but the connection
    the `Locator` made.
  * Closing twice is safe and is expected on the normal path. The base holder and its File, URI and
    Memory subclasses close idempotently, and `HLSConnectionHolder.closeConnection` gained an
    `AtomicBoolean` compare-and-set plus null guards on `currentPlaylist` and `playlistLoader` - see
    the HLS entry below. Nothing tracks which of the two callers closed a given holder, because
    neither can see the other's: the upcall reaches the holder through the registry, not through the
    media.
  * `GSTMedia.createNativeMedia` and `OSXMedia.initNativeMedia` cite this bullet from their javadoc.
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
* **`CGstAudioSpectrum::UpdateBands` no longer dereferences a NULL holder.** Master called
  `holder->UpdateBands(...)` on the result of `CBandsHolder::AddRef(m_pHolder)`, which returns `NULL`
  when no holder has been installed. `GstAudioSpectrum.cpp:129-130` now returns instead. Observable
  delta: a spectrum that produces data before `setBandCount` has installed a pair - or again after a
  `SetBands` given none - drops the delivery rather than crashing the process. Unreachable through
  `NativeAudioSpectrum` today, which is why it sat in `FFM-STATUS.md` section 4a for a while; it is a
  fix, and section 4a no longer lists it as unfixed.
* **`CGstAudioSpectrum::SetBands` swaps the holder under `m_BandsLock`.** Master did a bare
  `g_atomic_pointer_get` followed by a `g_atomic_pointer_set`, which cannot make the read and the
  `AddRef` in `UpdateBands` one step. `GstAudioSpectrum.cpp:101-119` now takes the lock across the
  swap, and `UpdateBands` takes the same lock across its read-and-retain (`:124-126`). Both release
  the superseded holder **outside** the lock, deliberately: dropping the last reference runs
  `~CFfiBandsHolder`, which calls back into Java, and a spectrum thread blocked on `m_BandsLock` must
  not be left waiting on a native-to-Java transition. Observable delta: two threads calling
  `setBandCount()` on one spectrum can no longer both release the same superseded holder - a double
  free, plus a `release` upcall on freed memory - while one of the two new holders is never released.
  Serialised by `NativeAudioSpectrum` today, so unreachable, but the ABI's release-exactly-once
  promise no longer rests on that assumption.
* **A second `jfxm_player_init` on the same GST media handle is rejected.** It returns
  `ERROR_MEDIA_CREATION` (0x0102), leaves the first dispatcher in place and frees the one the call
  asked for. Master replaced `m_pEventDispatcher` without deleting the old one
  (`CPipeline::SetEventDispatcher`), leaking it and - under FFM - a copy of 13 upcall stub addresses
  with it, and returned `CPipeline::Init()`'s result. **The backends now agree**: AVF's
  `jfxm_avf_player_init` has always returned `ERROR_MEDIA_CREATION` for a second call, so this removes
  a divergence rather than creating one, and section 7's text has been corrected to match.
  * *Why reject rather than delete the old dispatcher.* By the time a second init is possible the bus
    watch is running, so a GStreamer thread can be inside a `Send*Event` on the first dispatcher.
    Deleting it would convert a leak into a use-after-free on a foreign thread - a strictly worse
    trade.
  * *Observable delta:* none in-tree. No caller makes the second call; `NativeMediaPlayer` creates one
    player per media.
  * *Why this is **not** an ABI version bump.* See section 5.2. A mismatched pair cannot silently
    misbehave here: an older Java against a newer library receives an error code it already handles,
    and a newer Java against an older library gets the pre-existing leak. `JFXM_ABI_VERSION` stays
    at 4.
  * *Two corrections to the review record, verified against the merge base.* The reviewer speculated
    that the JNI-era orphaned dispatcher was lighter than the FFM one; it was not - the JNI orphan
    held **global refs**, pinning the Java player graph. And the FFM orphan holds stub addresses **by
    value**, which does not by itself keep the `Arena.ofShared()` open: Java closes that arena
    explicitly. So "strictly worse under FFM" is wrong in both halves. The JNI-parity claim itself was
    reported unverified and is now confirmed: `939aa61ea` `GstMediaPlayer.cpp:54-79` shows
    `gstInitPlayer` unconditionally `new`ing a dispatcher, and `939aa61ea` `Pipeline.cpp:63-66` is the
    same bare assignment. Not drift.
* **The AVF resource-loader read loop was rewritten** (`AVFMediaPlayer.mm`, the
  `-resourceLoader:shouldWaitForLoadingOfRequestedResource:` fill loop). Master read the block count
  into an **`unsigned int`**, so `-1` (EOS) and `-2` (error) became values near `UINT_MAX`, passed the
  `blockSize <= 0` test, and fell through into copying data the stream had never staged. The branch
  keeps the count `int`; breaks on negative and distinguishes EOS from error; breaks on `0`; clamps
  against the loop's remaining `requestedLength` rather than `dataRequest.requestedLength`; treats a
  short `CopyBlock` as a failure; and answers a failed read with `finishLoadingWithError:`
  (`ERROR_LOCATOR_CONNECTION_LOST`) instead of `finishLoading`. Every one of those is a fix.
  * *Observable delta, and it is a real one:* a `jar:`/`jrt:` media whose Java read throws mid-playback
    previously got garbage bytes handed to the demuxer and usually played on or glitched. It now fails
    the loading request, which fails the `AVPlayerItem` and surfaces as `kPlayerState_HALTED` - **a
    `MediaException` where master gave a glitch.** That is a better failure, and it is still a
    different one.
  * *Blast radius is small.* `onCopyBlock` stages `min(bufferCapacity, size)` and every in-tree
    `ConnectionHolder` guarantees capacity at least the requested `readSize`, so the short-copy branch
    is effectively unreachable; the change that can actually be observed is confined to the `-2` and
    EOS paths.
* **`HLSConnectionHolder.closeConnection` is idempotent.** It gained an `AtomicBoolean`
  compare-and-set plus null guards on `currentPlaylist` and `playlistLoader`. This is a genuine
  double-close/NPE fix, and it is *made necessary* by the close paths above: the Java-side close and
  the `close_connection` upcall can now both reach the same holder, from different threads, and
  neither can see the other's. Observable delta: a second close is a no-op instead of an NPE.
* **`ConnectionHolderBridge` is new API surface, and it is reachable from `javafx.web`.** The
  `JfxmStreamCallbacks` upcall targets in `JfxMediaNative` need `needBuffer`, `isSeekable`,
  `isRandomAccess`, `readBlock` and `property` on a `ConnectionHolder`. `JavaInputStreamCallbacks.cpp`
  reached them through `CallBooleanMethod`/`CallIntMethod`, which performs no access check; ordinary
  Java code does, so the five members are forwarded through a public bridge class rather than being
  widened on `ConnectionHolder` itself. That keeps `ConnectionHolder`'s own API untouched but does
  **not** confine the widening to the package: `module-info.java` carries
  `exports com.sun.media.jfxmedia.locator to javafx.web`, so the bridge is reachable from
  `javafx.web`, on a holder a live pipeline owns. Accepted rather than designed around - `javafx.web`
  is first-party, is built from this repository, and uses exactly one type of this package
  (`Locator`, in `com.sun.javafx.webkit.prism.WCMediaPlayerImpl`); containing it would mean moving
  `ConnectionHolder` and `HLSConnectionHolder` out of an exported package, which
  `Locator.createConnectionHolder()` and `Locator.getAudioStreamConnectionHolder(ConnectionHolder)`
  would then name in signatures `javafx.web` cannot resolve. It is real API-surface growth introduced
  *for* the migration, and it is recorded here for that reason.
* **A player built over an already-disposed source media reports `ERROR_MEDIA_INVALID` (260).**
  `NativeMedia.runAfterDispose` now returns a boolean saying whether the action was registered for
  later or run inline because the media was already disposed; `GSTMediaPlayer` and `OSXMediaPlayer`
  skip `playerInit` in the second case and fall into the failure path they already had, which
  disposes and throws the proper `MediaException`. Previously they went on to call `playerInit` with a
  closed arena and got an `IllegalStateException`. The check is race-free because
  `runAfterDispose` is `synchronized` on the same monitor `dispose()` holds - a caller-side
  `isDisposed()` pre-check would have had a real window. Observable delta: none in-tree, since no
  caller passes a disposed media; this is hardening, recorded because that is what this register is
  for.
* **`JfxMediaNative.onRelease` no longer unregisters before it knows what it has.** Master's FFM code
  looked the id up, unregistered it unconditionally, and then switched on the type. It now switches on
  the lookup and unregisters only inside the `BandHandover` and `Runnable` arms; an unrecognised type
  logs at `Logger.ERROR` and **keeps** the entry, because silently dropping it is the failure the
  finding was about. `case null` stays silent - a double release is harmless, as it is for every other
  slot.
* **`copy_block` returns the number of bytes copied** (section 9, ABI revision 3), where the JNI slot
  was `void` and `memcpy`'d `size` bytes unconditionally. The Java target bounds the copy by what is
  staged, zero-fills the remainder, logs at ERROR and returns the count. This is listed here as well as
  in section 9 so that this register is complete. **It is not a parity defect**: the old behaviour on
  the input where the two differ was a heap over-read, i.e. undefined, and the short path is
  unreachable through GStreamer anyway, because `javasource.c` allocates exactly the length
  `read_next_block` returned.
* **An AVF player state change can now wait on a resource-loader read.** The dispose fence puts the
  monitor around `-setPlayerState:`'s send, and
  `-resourceLoader:shouldWaitForLoadingOfRequestedResource:` takes the same monitor on the loader
  queue, so a state event can block behind an in-flight read. The wait is bounded by a local
  `jar:`/`jrt:` read; the resource loader is **never installed at all for `file:` or `http:`
  sources**, so those paths cannot see it; and it is the same wait `-dispose` already took before
  this change. Recorded because it is a real, if small, new coupling between the state machine and
  I/O, and because section 7.1's rule - never hold the monitor across an AVFoundation call - exists
  precisely to keep it from becoming a lock-order inversion.
* **`AVFMediaPlayer`'s `isDisposed` is `_Atomic(BOOL)`**, where it was a plain `BOOL` and briefly a
  `volatile BOOL`. No generated code changes; what changes is that the concurrent read/write pair it
  replaced was formally undefined behaviour, which matters because the display-link guard reads it
  with no lock. It is also **the single highest uncompiled risk token in this branch** - only clang
  compiling Objective-C++ will ever see that declaration, and nothing on the machine this was
  written on can. `volatile BOOL` is the fallback if macOS CI rejects it (section 7.1).
* **Internal changes with no observable delta**, recorded only because the register should be
  complete: `CPipeline::SetEventDispatcher` now returns `bool` (one call site); `m_bClosed` is an
  `int` accessed with `g_atomic_int_get`/`g_atomic_int_set`, so there is one atomic load per
  stream-callback call where there was a plain load.

### 14.2 Patches to the vendored `gstreamer-lite` tree (the patch manifest)

These files are **third-party sources under their own licence**, and they are the one part of the
module `buildtools/ffm-media/verify-no-jni.pl` deliberately cannot see: it skips
`gstreamer/gstreamer-lite`, `gstreamer/3rd_party` and `gstreamer/plugins`, correctly, because those
trees contain no JNI. The exclusion is right and the consequence is that **no automated control in
this branch covers a change made in them**. This subsection is that control: it is the manifest of the
local delta, and a future upstream GStreamer merge should start here rather than with `git log`.

Measured at the merge base `939aa61ea`: seven files, `+500 / −71`. Per-file deltas are in
`FFM-STATUS.md` section 1b.

**`plugins/javasource/javasource.c`** (+78 / −7) and **`marshal.c` / `marshal.h` / `marshal.in`**
(+21 / −17, +7 / −7, +1 / −1)

* The `copy-block` signal is re-registered with `G_TYPE_INT` and an `INT__POINTER_INT` marshaller so
  that the Java side's byte count reaches C (section 9, ABI revision 3). Both `javasource` paths act
  on a short return: the push path (`java_source_loop`) unrefs the buffer, sets `GST_FLOW_ERROR` and
  stops the task, so the buffer is never pushed; the pull path (`java_source_getrange`) unmaps and
  unrefs and returns `GST_FLOW_ERROR`, so the pulling element reports the failure instead of receiving
  short data. Master pushed whatever was in the freshly allocated, uninitialised buffer.
* **`marshal.c` and `marshal.h` are generated**, by `glib-genmarshal` from `marshal.in`, and
  **`marshal.in` was correctly updated too** - line 11 now reads `INT:POINTER,INT`. This is the single
  highest-yield trap in the whole change and the authors did not fall into it: had the generator input
  been left at `VOID:POINTER,INT`, the next regeneration would have **silently reverted `copy_block`'s
  return value to garbage**, with the failure mode described in section 5.3 and no diagnostic
  anywhere. If you regenerate these two files, regenerate them from `marshal.in` and diff the result
  against what is committed.

**`gstreamer-lite/gst-plugins-good/sys/directsound/gstdirectsoundnotify.cpp` / `.h`** (+232 / −21,
+57 / −1) and **`gstdirectsoundsink.c`** (+104 / −17)

* **A deliberate, permanent, unbounded leak on the registered path.** `ReleaseNotificator` calls
  `Dispose()` and then does **not** free the `GSTDirectSoundNotify` - about 80 bytes of heap holding a
  valid vtable pointer, a valid `SRWLOCK` and a NULL callback pair. This is the departure with the
  largest cost in the branch, and it is recorded here in full because **the tests still cannot detect
  the leak itself**. That claim is now narrower than it was, and the difference is worth stating.
  When this entry was first written, the only test that opened an audio sink was skipped wherever
  there was no audio output - which included CI - and asserted nothing when it did run; twenty green
  tests would have been equally green at 80 MB leaked per playback. Since then
  `JfxMediaNativeTest.playerOverATinyWavFileDisposesWithoutLeaks` gained real assertions - the
  event-queue thread count back to baseline under a bounded wait, total JVM threads no more than
  baseline + 10 after 20 playbacks, and registry size back to baseline - and `MediaPlaybackTest` runs
  and asserts in full on both Linux CI jobs, which write a null `~/.asoundrc` so the sink always
  opens. So the *thread* and *registry* halves of the trade are now pinned, and the 20-playback
  thread bound is precisely the shape that would catch an unbounded thread leak here.
  **The heap allocation is still invisible to all of it**: no test measures native memory, and 80
  bytes per playback would not move any assertion that exists. Read the trade as deliberate and
  partially fenced, not as detected.
  * *Why.* MMDevAPI keeps the raw pointer it was handed at registration, takes no reference of its own,
    and reaches the object through a **virtual call** - the vtable load happens inside MMDevAPI before
    a single instruction of ours runs. No lock, flag or refcount taken on entry can come early enough
    to protect the object's own lifetime, and `UnregisterEndpointNotificationCallback` promises no
    in-flight drain, so a successful unregister does not rule the race out either. The only thing that
    makes an already-dispatched call safe is the object still being there when it lands. The trade is
    a fixed, inert allocation instead of a use-after-free, and it is the right way round.
  * *Scale, in the authors' own words at the site:* an element is created per audio pipeline, "so per
    `MediaPlayer` with audio and per `AudioClip.play()`, which makes it **proportional to playbacks
    rather than to live players**". Each element also costs a thread create, a COM create and a thread
    join. **That is what makes this MEDIUM rather than LOW:** 80 bytes is nothing, but it is unbounded
    over process lifetime and keyed to the single most repeated operation in the audio API. A game
    firing `AudioClip`s pays it per sound.
  * *The failure path is different and does free.* `InitNotificator` calls `Release()` when `Init()`
    returns false, and that is provably safe: `Init()` returns true only after
    `RegisterEndpointNotificationCallback()` succeeded, so a false return means MMDevAPI was never told
    the object exists.
  * *The amortisation that was not done:* one process-wide notificator holding a list of sinks. That
    changes who registers with MMDevAPI rather than how long the object lives, and it is separate work.
* **A failed `UnregisterEndpointNotificationCallback` no longer aborts the process.** The
  `assert(SUCCEEDED(hr))` is gone. Observable delta is confined to non-`NDEBUG` builds - release builds
  compiled the assert out and are unaffected - where a driver-level unregister failure during teardown
  now proceeds instead of aborting.
* **The untimed join is deliberate and was deliberately kept.** `Dispose()` does
  `WaitForSingleObject(m_hThread, INFINITE)` on the dispose path, which is routinely the FX Application
  Thread. A timeout is **not** an available alternative: abandoning the apartment thread means the
  unregister may never have been attempted, so MMDevAPI keeps dispatching with a pointer to a sink that
  `gst_directsound_sink_finalize` is about to free; the callback pair is never cleared, so the drain
  never happens; and the three `CloseHandle` calls run on handles the abandoned thread still owns and
  is still waiting on. It converts a bounded stall into a use-after-free, which is the wrong trade at
  any bound. The stall itself is bounded by MMDevAPI returning from the unregister - the same exposure
  as before this thread existed, when those calls ran inline on the disposing thread - plus one
  in-flight callback, which sets two flags and takes no lock.
* **`gstreamer-lite` now defines its own no-op `DllMain`.** Runtime-identical: a DLL that defines none
  gets the CRT's default, which is the same no-op. Its purpose is a **link-time assertion**. A DLL has
  exactly one `DllMain`, so any future `DllMain` compiled into `gstreamer-lite` fails the link with
  `LNK2005` naming this one, instead of shipping a deadlock. That is what turns the loader-lock
  invariant in section 14.3 from aspirational into enforceable. **The limit, stated because it is
  real:** it cannot cover a `DllMain` in another module of the process that reaches
  `gst_directsound_sink_finalize`, because the loader lock is process-wide.

### 14.3 Contract invariants the compiler cannot enforce

Each of these is a real constraint the code depends on. Each lived only in a comment, or nowhere at
all, and each has a fatal or expensive failure mode. They are not defects; they are the things a
future change must not break.

* **A video frame is freed by *Java's* `catch`, never by C.**
  `CFfiPlayerEventDispatcher::SendNewFrameEvent` deletes the frame **only** on the NULL-slot path.
  When the slot exists and returns
  0 - which means the Java target threw - C deliberately deletes nothing, because from there it cannot
  tell whether the target had already taken the frame, already disposed of it, or never touched it, and
  deleting would risk a double free. Neither `GstAVPlaybackPipeline` call site deletes it either. What
  makes that safe is `NativeVideoBuffer.createVideoBuffer`: its `catch (Throwable)` calls
  `MediaDisposer.removeResourceDisposer(nativePeer)` and then `JfxMediaNative.frameDispose(nativePeer)`
  before rethrowing. **Anyone who "simplifies" that `catch` reintroduces a leak of a `CGstVideoFrame`
  plus its `GstSample` - roughly 8 MB per 1080p BGRA frame, at frame rate.** The removal before the
  dispose matters too: `HashMap.put` inserts before it resizes, so a failure inside a resize can leave
  the entry registered, and disposing without removing it would free the frame a second time when the
  buffer is collected.
* **The DirectSound joins must never be made under the loader lock.** Both the wait in
  `GSTDirectSoundNotify::Dispose()` and the one in `Init()` wait on the apartment thread's
  `DLL_THREAD_DETACH` / `DLL_THREAD_ATTACH`. Making either under the loader lock **deadlocks**. No such
  path exists today - `ReleaseNotificator` is reached from a GObject finalize, on a pipeline teardown
  driven from Java - and it has to stay that way. Within `gstreamer-lite` this is now enforced by the
  no-op `DllMain` described in section 14.2; across the process it cannot be, because the loader lock
  is process-wide.
* **The `SCRATCH` cell rests on two invariants nothing checks.** One `Arena.ofAuto()` 120-byte
  `ThreadLocal` cell is shared by all eight out-param wrappers in `JfxMediaNative`. It requires
  **(a)** every out-param entry point writes `*out` **last**, after any upcall (sections 2 and 4 state
  this, and a `jfxm_*` out-param function that upcalls after storing `*out` is forbidden by this
  contract); and **(b)** no upcall target reaches an out-param wrapper on a thread already inside one.
  (b) is pinned by a test for all nine stream slots, and it was independently confirmed that no
  `ConnectionHolder` subclass references `JfxMediaNative`, so it holds today. Violating either
  corrupts a parameter across calls. This is a standing constraint on any *future* out-param wrapper,
  not a present defect.
* **The cross-dylib `CPlayerEventDispatcher` vtable is a packaging invariant, not a checkable one.**
  AVF's fragile ABI boundary is **not** `JfxmPlayerCallbacks` - the AVF tree never sees that struct at
  all; `jfxm_avf_player_init` hands the pointer straight to `CFfiPlayerEventDispatcher`, which copies
  it by value. The real boundary is the **vtable** of `CPlayerEventDispatcher`, which
  `libjfxmedia_avf.dylib` calls into `libjfxmedia.dylib`. `PlayerEventDispatcher.h` carries an
  append-only warning and `SendSubtitleTrackEvent` was correctly appended rather than inserted for FFM.
  But **`jfxm_abi_version` cannot see a vtable** - it is a symbol of `libjfxmedia.dylib`, and a vtable
  has no version, only an order - so a mixed-vintage dylib pair is undetectable by any check in this
  contract. What keeps the pair honest is packaging: `native/mac.cmake` builds both from these headers
  in one run and the SDK ships them together. **Never replace one of the two dylibs on its own.**
* **`NativeVideoBuffer` reads one `FrameInfo` snapshot at construction**, where the JNI implementation
  queried C on every accessor call. Equivalent - nothing mutates a `CVideoFrame`'s geometry after it
  has been dispatched - but a real structural difference, and it is what makes the ten former getters
  collapse into one `jfxm_frame_get_info`. Recorded because it was not written down anywhere: an
  accessor added later must be served from the snapshot, or the snapshot has to be refreshed
  deliberately.
