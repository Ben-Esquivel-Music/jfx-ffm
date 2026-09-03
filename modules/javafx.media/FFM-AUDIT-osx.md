# JNI audit — slice `osx` (macOS platform: OSXMediaPlayer/OSXPlatform, jfxmedia_avf)

Scope: `modules/javafx.media/src/main/native/jfxmedia/platform/osx/**` (3,765 lines), the macOS-only Utils they use (`Utils/JObjectPeers.m` 214, `Utils/JavaUtils.m` 56, `Utils/MTObjectProxy.m` 258 + headers), `jfxmedia/projects/mac/Makefile`, and the Java classes `com.sun.media.jfxmediaimpl.platform.osx.{OSXPlatform, OSXMedia, OSXMediaPlayer}`. Notes persisted at `<scratchpad>/phase1/osx-notes.md`. This platform cannot be compiled or run on this machine (Windows); everything below is source evidence plus the Windows prebuilt `jfxmedia.dll` for the shared core.

## 1. Verdict

**Migrate — the library is an OS engine integration and stays native — but delete its JNI glue outright rather than porting it.** `libjfxmedia_avf.dylib` is AVFoundation/CoreVideo/MediaToolbox integration whose callbacks arrive on OS-owned threads (AVFoundation KVO threads, the main dispatch queue, the CVDisplayLink thread, the MTAudioProcessingTap real-time audio thread, and a resource-loader dispatch queue). Triage of the 3,765 lines in scope: **OS-CALL 6 units** (`OSXPlatform.osxPlatformInit` + `OSXMediaPlayer.+initPlayerPlatform`, `CVVideoFrame`, `AVFMediaPlayer`, `AVFAudioProcessor`, `AVFAudioSpectrumUnit`, all with named framework/GStreamer symbols), **PURE-HOT 2** (`AVFAudioEqualizer`, `AVFSoundLevelUnit` — vDSP kernels that execute *inside* the MTAudioProcessingTap process callback on the real-time audio thread, where a Java upcall is not permissible; parity would be `tolerance` (unmeasured) and `exact` respectively but is moot), **PURE 1** (`MTObjectProxy` — dead code, no call sites), **JNI-GLUE 3 files** (the JNI half and peer-map plumbing of `OSXMediaPlayer.mm`, `JObjectPeers.m`, `JavaUtils.m`), **WRAPPER 0**. No function in scope is a wrapper that Java could bind directly: every entry point goes through Objective-C messaging into AVFoundation, which has no C-callable symbol for Java to bind. Result: the 23 JNI exports become the *same* `jfxm_player_*` C ABI the GStreamer backend gets (one Java facade, two backends; the OSX-only deltas are mute and platform init), and roughly 1,100 lines of Objective-C JNI glue (`JObjectPeers`, `JavaUtils`, `MTObjectProxy`, the JNI section of `OSXMediaPlayer.mm`) are deleted, replaced by ~120 lines of C entry points over `OSXPlayerProtocol`.

## 2. Summary counts

| Item | Count | Evidence |
|---|---|---|
| Java `native` methods in scope | 23 (OSXMediaPlayer 22, OSXPlatform 1) | `OSXMediaPlayer.java:173-197`, `OSXPlatform.java:141` |
| Java-side orphan | 1 — `osxNeedsLocator()Z` declared, present in the generated header (`target/gensrc/headers/com_sun_media_jfxmediaimpl_platform_osx_OSXMediaPlayer.h:203`), **no implementation anywhere**, never called | grep `osxNeedsLocator` over `src/main` → only the declaration |
| `JNIEXPORT` in scope | 23 (OSXMediaPlayer.mm 22, OSXPlatform.mm 1); all matched to Java; no C-side orphans | `OSXMediaPlayer.mm:293-721`, `OSXPlatform.mm:35` |
| `JNI_OnLoad` | 0 in slice | grep |
| Cached `jmethodID`/`jfieldID`/`jclass` | 0 in slice (all live in the shared core: `jni/JavaPlayerEventDispatcher.cpp`, `Locator/Locator.cpp`, `jni/Logger.cpp`) | grep |
| Upcalls from AVF code | 8 dispatcher methods at 9 sites in `AVFMediaPlayer.mm` (+ 5 callers of `setPlayerState`), Logger upcalls at 6 sites in `AVFMediaPlayer.mm` and 6+3 in `OSXMediaPlayer.mm`/`OSXPlatform.mm`, 6 stream-callback methods in the resource-loader delegate, 1 JNI array write (`SetFloatArrayRegion`) from the RT audio thread via `CJavaBandsHolder` | table §6 |
| `NewGlobalRef`/`DeleteGlobalRef` | 1 matched pair in slice (`OSXMediaPlayer.mm:122` / `:172`) | plus the dispatcher's and input-stream callbacks' refs (core slice) |
| `Get*ArrayCritical` / `Get*ArrayElements` / `GetDirectBufferAddress` | 0 | grep |
| Strings | `GetStringChars` UTF-16 (`JavaUtils.m:34-38`, no copy-back), `GetStringUTFChars` ×2 modified UTF-8 (`OSXMediaPlayer.mm:349-350`, released `:367-368`) | |
| `GetJavaVM` / `AttachCurrentThread` | `GetJavaVM` at `OSXMediaPlayer.mm:114`, `JObjectPeers.m:119,132,153,177,191`; 1 attach site `JavaUtils.m:50` (`AttachCurrentThreadAsDaemon`) used from `OSXMediaPlayer.mm:165` with `DetachCurrentThread :175` | |
| `ThrowNew` / `ExceptionCheck` | 6 `ThrowJavaException` sites (`OSXMediaPlayer.mm:310,318,335,344,361,374`, all in `osxCreatePlayer`); `ExceptionCheck/Clear :115-117` | `ThrowJavaException` itself does `FindClass` by name (`jni/JniUtils.cpp:44`, core) |
| `FindClass` | 0 direct in slice | |
| Tests exercising these classes | 0 (no `modules/javafx.media/src/test`; `tests/system` references only `new MediaView()` in `NodeInitializationStressTest.java`) | grep over `modules/*/src/test`, `tests/system/src` |

## 3. Java classes and C files

### Java classes

| Class | natives | Notes |
|---|---|---|
| `com.sun.media.jfxmediaimpl.platform.osx.OSXMediaPlayer` (`OSXMediaPlayer.java`) | 22 instance | extends `NativeMediaPlayer`; **no `long ptr` field** — the native peer is found by `jobject` identity (`JObjectPeers`). Ctor: `init()`, `osxCreatePlayer(locator, contentType, contentLength)` (`:49`), `createNativeAudioEqualizer(osxGetAudioEqualizerRef())` (`:51`), `createNativeAudioSpectrum(osxGetAudioSpectrumRef())` (`:52`) — i.e. it **reuses the shared `NativeAudioEqualizer`/`NativeAudioSpectrum`/`NativeEqualizerBand` and `NativeVideoBuffer`** unchanged, passing raw `CAudioEqualizer*`/`CAudioSpectrum*` pointers. `playerGetDuration` maps `-1.0` → `Double.POSITIVE_INFINITY` (`:151-156`). `playerInit()` empty; `getMediaPlayerOverlay()` null. No out-param arrays (values returned directly; errors only via `MediaException` from `osxCreatePlayer`). |
| `…osx.OSXPlatform` (`OSXPlatform.java`) | 1 static | `OSXPlatformInitializer` static block: `NativeLibLoader.loadLibrary("jfxmedia_avf")` (`:73`), ULE → platform absent. `loadPlatform()` → `osxPlatformInit()` (`:103`). Content types mp3/mpeg/x-m4a/mp4/x-m4v/HLS (`:45-53`). Selected by `PlatformManager.java:98-103` **after** `GSTPlatform` as a fallback on macOS (HLS `.m3u8` is diverted from GST by `GSTPlatform.java:143`). `NativeMediaManager.java:131` also lists `jfxmedia_avf` as a declared dependency of `jfxmedia` on macOS. |
| `…osx.OSXMedia` (`OSXMedia.java`) | 0 | `dispose()` empty. |
| Build: `modules/javafx.media/pom.xml:96,118` exclude `com/sun/media/jfxmediaimpl/platform/osx/**` from Windows/Linux jars; nothing builds iOS. | | |

### C/ObjC files

| File | Lines | Library | Role | JNI content |
|---|---|---|---|---|
| `platform/osx/OSXMediaPlayer.mm` | 721 | `libjfxmedia.dylib` (Makefile:156) | ObjC wrapper `OSXMediaPlayer : NSObject<OSXPlayerProtocol>` holding `jobject javaPlayer`, `JavaVM*`, `CJavaPlayerEventDispatcher*`, and the real `id<OSXPlayerProtocol> player`; forwards every property/method (`:183-282`). `+initPlayerPlatform` (`:69-95`) probes `objc_getClass("AVFMediaPlayer")`. JNI section `:285-721`. | 22 `JNIEXPORT`; `GetJavaVM`, `NewGlobalRef`/`DeleteGlobalRef`, `GetStringUTFChars`×2, 6 `ThrowJavaException`, `NSAutoreleasePool` per call, peer lookup per call |
| `platform/osx/OSXMediaPlayer.h` | 49 | — | imports `<jni.h>`, `Utils/MTObjectProxy.h` (unused), `JavaPlayerEventDispatcher.h` | ivars `jobject`, `JavaVM*` |
| `platform/osx/OSXPlatform.mm` | 60 | `libjfxmedia.dylib` (Makefile:155) | `osxPlatformInit`: mutates `[NSBundle mainBundle].infoDictionary` (ATS `NSAllowsArbitraryLoads`/`ForMedia`, JDK-8202393 workaround, `:39-56`), then `[OSXMediaPlayer initPlayerPlatform]` | 1 `JNIEXPORT` |
| `platform/osx/OSXPlayerProtocol.h` | 70 | header | The backend interface (`audioSyncDelay`, `duration`, `rate`, `currentTime`, `mute`, `volume`, `balance`, `audioEqualizer`, `audioSpectrum`, `initWithURL:eventHandler:locatorStream:`, `play/pause/stop/finish/dispose`) + `kPlayerState_*` constants | includes `jni/JavaPlayerEventDispatcher.h` (→ `jni.h`) |
| `platform/osx/CVVideoFrame.mm/.h` | 208+49 | `libjfxmedia.dylib` (Makefile:157) | `CVVideoFrame : CVideoFrame` over a `CVPixelBufferRef` | none |
| `platform/osx/avf/AVFMediaPlayer.mm/.h` | 914+94 | `libjfxmedia_avf.dylib` (Makefile:236) | The AVFoundation player | none (only the opaque `CJavaPlayerEventDispatcher*` and `CLocatorStream*`) |
| `platform/osx/avf/AVFAudioProcessor.mm/.h` | 248+71 | avf | `AVMutableAudioMix` + `MTAudioProcessingTap` driving EQ → spectrum → level in place | none |
| `platform/osx/avf/AVFAudioEqualizer.cpp/.h` | 464+165 | avf | biquad EQ (`vDSP_deq22D`) | none |
| `platform/osx/avf/AVFAudioSpectrumUnit.cpp/.h` | 326+125 | avf | drives a standalone gstreamer-lite `spectrum` element | none |
| `platform/osx/avf/AVFSoundLevelUnit.cpp/.h` | 133+68 | avf | volume/balance (`vDSP_vsmul`) | none |
| `Utils/JObjectPeers.m/.h` | 214+53 | `libjfxmedia.dylib` (Makefile:152) | `jobject` ↔ ObjC peer list, `IsSameObject` linear scan under `@synchronized` | pure JNI |
| `Utils/JavaUtils.m/.h` | 56+44 | `libjfxmedia.dylib` (Makefile:153) | `NSStringFromJavaString` (UTF-16), `GetJavaEnvironment` (attach) | pure JNI |
| `Utils/MTObjectProxy.m/.h` | 258+49 | `libjfxmedia.dylib` (Makefile:154) | `NSProxy` forwarding invocations to the main thread | none — **dead**: only reference is the `#import` in `OSXMediaPlayer.h:27`; no `objectProxyWithTarget:` call site anywhere in `src/main` |

**Does OSX share the core `jni/` glue or duplicate it?** It shares it. `CJavaPlayerEventDispatcher` is instantiated in `osxCreatePlayer` (`OSXMediaPlayer.mm:303-304`) and handed to `AVFMediaPlayer`, which calls its `Send*Event` methods directly (`AVFMediaPlayer.mm:315,384,619,664,674,734,736,742`). Frames reach Java through the shared `NativeVideoBuffer.cpp` (`sendNewFrameEvent(long)` → `NativeVideoBuffer.createVideoBuffer`, `NativeMediaPlayer.java:1496-1500`) as a `CVideoFrame*`. Equalizer/spectrum go through the shared `NativeAudioEqualizer.cpp`/`NativeAudioSpectrum.cpp`/`NativeEqualizerBand.cpp` over the `CAudioEqualizer`/`CAudioSpectrum`/`CEqualizerBand` virtual interfaces (`PipelineManagement/AudioEqualizer.h:36-57`, `AudioSpectrum.h:57-67`), with `CJavaBandsHolder` (`jni/JavaBandsHolder.cpp`) as the band sink. Stream reads for `jar:`/`jrt:` go through the shared `CJavaInputStreamCallbacks` (`OSXMediaPlayer.mm:329-366`). The only ObjC-specific JNI is the peer map + string/attach helpers, and the 22 exports themselves.

## 4. External dependencies

From `jfxmedia/projects/mac/Makefile` (the media natives are not built by Maven; `modules/javafx.media/native/CMakeLists.txt` exists but is **untracked** (`git status` → `??`) and references `mac.cmake`/`win.cmake`/`linux.cmake` that do not exist yet — the Makefile is the only build evidence):

| Library | Sources (this slice) | Link flags | Compile flags |
|---|---|---|---|
| `libjfxmedia.dylib` (`-install_name @rpath/libjfxmedia.dylib`, Makefile:106) | `Utils/ColorConverter.c`, `Utils/JObjectPeers.m`, `Utils/JavaUtils.m`, `Utils/MTObjectProxy.m`, `platform/osx/OSXPlatform.mm`, `platform/osx/OSXMediaPlayer.mm`, `platform/osx/CVVideoFrame.mm` (Makefile:151-157) plus all core `jni/*`, `PipelineManagement/*`, `platform/gstreamer/*` | `-lobjc -framework Cocoa -framework CoreVideo -lgstreamer-lite -lglib-lite` (Makefile:77-80,105-108) | `-DJFXMEDIA_JNI_EXPORTS -DGSTREAMER_LITE -DTARGET_OS_MAC=1`, `-msse2` on x86_64 (Makefile:55-69,85-93), `-I$(JAVA_HOME)/include{,/darwin}` (Makefile:71-75) |
| `libjfxmedia_avf.dylib` (`-install_name @rpath/libjfxmedia_avf.dylib`, Makefile:210) | `avf/AVFMediaPlayer.mm`, `avf/AVFAudioProcessor.mm`, `avf/AVFAudioEqualizer.cpp`, `avf/AVFAudioSpectrumUnit.cpp`, `avf/AVFSoundLevelUnit.cpp` (Makefile:235-240) | base + `-framework AVFoundation -framework CoreMedia -framework Accelerate -framework AudioUnit -framework MediaToolbox -lgstreamer-lite -lglib-lite` and **`-ljfxmedia`** (Makefile:209-217, 270); depends on `$(TARGET)` being built first (Makefile:268) | `-fobjc-arc -DOSX -DGSTREAMER_LITE -DCA_*` (Makefile:219-233); include `gst-plugins-good/gst/spectrum` (Makefile:256) |
| Makefile also asserts neither dylib links QTKit/QuickTime (Makefile:171-174). | | | |

Imported symbols named in the sources (no mac binary is available here; a mac build must confirm with `nm -u` / `otool -L`):

* Foundation/ObjC runtime: `objc_getClass` (`OSXMediaPlayer.mm:83`, `AVFMediaPlayer.mm:122`), `conformsToProtocol:`, `NSBundle mainBundle`/`infoDictionary` (`OSXPlatform.mm:39-46`), `NSURL initWithString:`, `NSNotificationCenter addObserverForName:` (`AVFMediaPlayer.mm:182`), KVO `addObserver:forKeyPath:` (`:243`), `dispatch_queue_create`/`dispatch_async(main queue)` (`:139,154,252,322`), `getenv` (`OSXMediaPlayer.mm:74`).
* AVFoundation: `AVPlayer playerWithURL:` (`:142`), `AVPlayerItemVideoOutput initWithPixelBufferAttributes:` (`:255,284`), `addOutput:/removeOutput:`, `AVAssetResourceLoader setDelegate:queue:` (`:157`), `AVMutableAudioMix`/`AVMutableAudioMixInputParameters` (`AVFAudioProcessor.mm:85-88`), `seekToTime:`, `cancelPendingPrerolls`, track/format inspection (`AVFMediaPlayer.mm:531-663`).
* CoreMedia: `CMTimeGetSeconds`, `CMTimeMakeWithSeconds`, `CMFormatDescriptionGetMediaSubType` (`:553`), `CMAudioFormatDescriptionGetStreamBasicDescription`/`GetChannelLayout` (`:639-641`), `CMClockMakeHostTimeFromSystemUnits` (`:882`); CoreAudio `AudioChannelLayoutTag_GetNumberOfChannels` (`:652`).
* CoreVideo: `CVDisplayLinkCreateWithActiveCGDisplays`/`SetOutputCallback`/`Start`/`Stop`/`Release` (`:298-301,510-511,690`), `CVGetCurrentHostTime`, `CVPixelBufferGetPixelFormatType`/`GetWidth`/`GetHeight`/`GetExtendedPixels`/`IsPlanar`/`LockBaseAddress`/`UnlockBaseAddress`/`GetBaseAddress[OfPlane]`/`GetBytesPerRow[OfPlane]`/`GetPlaneCount`/`GetHeightOfPlane`/`Retain`/`Release`/`Create` (`CVVideoFrame.mm:51-186`), `CVBufferRelease`.
* MediaToolbox: `MTAudioProcessingTapCreate` (`AVFAudioProcessor.mm:102`), `MTAudioProcessingTapGetStorage` (`:160,169,220`), `MTAudioProcessingTapGetSourceAudio` (`:221`).
* Accelerate/vDSP: `vDSP_vsdivD`, `vDSP_deq22D`, `vDSP_vspdp`, `vDSP_vdpsp` (`AVFAudioEqualizer.cpp:153,171,189,247,447,462`), `vDSP_vclr`, `vDSP_vadd`, `vDSP_vsdiv` (`AVFAudioSpectrumUnit.cpp:94-103`), `vDSP_vsadd`, `vDSP_vclr`, `vDSP_vsmul` (`AVFSoundLevelUnit.cpp:115-131`); libm `cos/tan/sqrt/pow` (`AVFAudioEqualizer.cpp:139-208`).
* gstreamer-lite/glib-lite (from `libjfxmedia_avf`): `gst_init_check` (`AVFAudioSpectrumUnit.cpp:51`), `gst_element_factory_make("spectrum")` (`:273`), `g_object_set` (`:284-294`), `gst_audio_info_*` (`:302-307`), `gst_element_set_state` (`:310,320`), `gst_buffer_new_wrapped_full` (`:109`), `gst_buffer_unref`, `gst_message_get_structure`/`gst_structure_*`/`gst_value_list_get_value`/`g_value_get_float` (`:234-263`), and the **JavaFX-patched, macOS-only** entry points `gst_spectrum_setup_api`, `gst_spectrum_transform_ip_api` plus the `GstSpectrum.user_data`/`post_message_callback` fields (`gstspectrum.h:43-50,94-99,109-115`, guarded by `GSTREAMER_LITE && OSX`; `gstreamer/projects/mac/gstreamer-lite/Makefile:54` passes `-DOSX` so both dylibs see the same struct layout).
* `libjfxmedia.dylib` symbols used by `libjfxmedia_avf.dylib`: `CJavaPlayerEventDispatcher::Send*Event`, `CVideoFrame`/`CVVideoFrame`, `CTrack`/`CAudioTrack`/`CVideoTrack`/`CSubtitleTrack` ctors, `CLocatorStream`/`CStreamCallbacks`, `CLogger`, `CAudioEqualizer`/`CAudioSpectrum`/`CEqualizerBand`/`CBandsHolder::AddRef/ReleaseRef`, `ColorConvert_YCbCr422p_to_BGRA32_no_alpha` (`Utils/ColorConverter.h:94`).
* Windows prebuilt for the shared core (`C:\SourceCode\caches\sdk\bin\jfxmedia.dll`, `dumpbin -exports/-imports`): 54 `Java_*` exports (Logger 2, NativeAudioEqualizer 5, NativeAudioSpectrum 7, NativeEqualizerBand 6, NativeVideoBuffer 13, GSTMedia 2, GSTMediaPlayer 18, GSTPlatform 1) + `JNI_OnLoad`; imports `gstreamer-lite.dll`, `glib-lite.dll`, `KERNEL32`, MSVC CRT. Zero `osx` symbols, as expected — there is no macOS binary on this machine.

**How `jfxmedia_avf` is loaded (Q4).** Java `dlopen`s it: `NativeLibLoader.loadLibrary("jfxmedia_avf")` (`OSXPlatform.java:73`; also as a declared dependency in `NativeMediaManager.java:131`), after `glib-lite`, `gstreamer-lite`, `jfxmedia`. Loading registers the ObjC class `AVFMediaPlayer` with the runtime; `libjfxmedia.dylib` never links or `dlsym`s the avf library — it finds the class by **name** with `objc_getClass("AVFMediaPlayer")`, checks `conformsToProtocol:@protocol(OSXPlayerProtocol)` and `+playerAvailable` (which itself probes `objc_getClass("AVPlayerItemVideoOutput")`, `AVFMediaPlayer.mm:119-124`), then instantiates it via `[[gMediaPlayerClass alloc] initWithURL:eventHandler:locatorStream:]` (`OSXMediaPlayer.mm:83-87,130`). The dependency direction is the reverse of what one might expect: `libjfxmedia_avf.dylib` links `-ljfxmedia` (Makefile:270) to reach the dispatcher, frame, track, locator, logger and equalizer/spectrum base classes; `libjfxmedia.dylib` exports nothing specifically *for* avf beyond those C++ classes (no `.def`/export list; default visibility). An env var `JFXMEDIA_AVF` not equal to `yes` disables the platform (`OSXMediaPlayer.mm:74-77`).

## 5. Triage table

| Function / unit | File:lines | Verdict | Evidence (named symbols) | Parity gate | Replacement |
|---|---|---|---|---|---|
| `Java_…OSXMediaPlayer_osxCreatePlayer` | `OSXMediaPlayer.mm:293-382` | JNI-GLUE | Body: `CLocator::LocatorGetStringLocation` (upcall), `new CJavaPlayerEventDispatcher`+`Init`, `NSStringFromJavaString`, `NSURL initWithString:`, optional `CJavaInputStreamCallbacks`+`CLocator::CreateConnectionHolder`+`CLocatorStream`, `[[OSXMediaPlayer alloc] initWithURL:…]`, 6 `ThrowJavaException` | n/a | `jfxm_player_create(JFXM_BACKEND_AVF, &info, &cb, user, &out)`; Java passes `locator.getStringLocation()`/`getContentType()`/`getContentLength()` and a `JfxmStreamCallbacks` only for `jar:`/`jrt:` |
| `…_osxGetAudioEqualizerRef` / `…_osxGetAudioSpectrumRef` | `:389-416` | JNI-GLUE | peer lookup + `ptr_to_jlong(player.audioEqualizer/audioSpectrum)` (`static_cast` from `shared_ptr` payload, `AVFMediaPlayer.mm:232-240`) | n/a | `jfxm_player_get_equalizer/get_spectrum(p, void** out)` |
| `…_osxGetAudioSyncDelay` / `…_osxSetAudioSyncDelay` | `:423-450` | JNI-GLUE | forwards to `AVFAudioProcessor.audioDelay`, which is **stored and never applied** (`AVFAudioProcessor.h:69`, `.mm:59`; no other reader) | n/a | `jfxm_player_get/set_audio_sync_delay` — preserve the no-op |
| `…_osxPlay` / `…_osxPause` / `…_osxStop` / `…_osxFinish` | `:457-514` | JNI-GLUE | `[AVPlayer play/pause]`, `seekToTime:kCMTimeZero`, then `setPlayerState` upcall (`AVFMediaPlayer.mm:455-474`) | n/a | `jfxm_player_play/pause/stop/finish` |
| `…_osxGetRate` / `…_osxSetRate` | `:521-548` | JNI-GLUE | `AVPlayer.rate` | n/a | `jfxm_player_get/set_rate` |
| `…_osxGetPresentationTime` | `:555-566` | JNI-GLUE | `CMTimeGetSeconds([AVPlayer currentTime])` | n/a | `jfxm_player_get_presentation_time` |
| `…_osxGetMute` / `…_osxSetMute` | `:573-600` | JNI-GLUE | `AVPlayer.muted` | n/a | `jfxm_player_get/set_mute` (**OSX-only**; GST does mute in Java, `GSTMediaPlayer.java:168-190`) |
| `…_osxGetVolume` / `…_osxSetVolume` / `…_osxGetBalance` / `…_osxSetBalance` | `:607-668` | JNI-GLUE | `AVFAudioProcessor.volume/balance` → `AVFSoundLevelUnit::setVolume/setBalance` (clamped, `AVFSoundLevelUnit.cpp:42-62`) | n/a | `jfxm_player_get/set_volume`, `get/set_balance` |
| `…_osxGetDuration` | `:675-686` | JNI-GLUE | `AVPlayerItem.duration` when `ReadyToPlay` and not `CMTIME_IS_INDEFINITE`, else `-1.0` (`AVFMediaPlayer.mm:445-453`) | n/a | `jfxm_player_get_duration(p, double* out)`; Java keeps the `-1.0 → +Inf` mapping |
| `…_osxSeek` | `:693-702` | JNI-GLUE | `seekToTime:CMTimeMakeWithSeconds(t,1)`; auto-`play` if state was FINISHED (`AVFMediaPlayer.mm:397-403`) | n/a | `jfxm_player_seek` |
| `…_osxDispose` | `:709-721` | JNI-GLUE | `[player dispose]` (AVF teardown) + `eventHandler->Dispose()` + `DeleteGlobalRef` + peer removal | n/a | `jfxm_player_dispose` |
| `Java_…OSXPlatform_osxPlatformInit` | `OSXPlatform.mm:35-60` | OS-CALL | `[NSBundle mainBundle].infoDictionary` mutation (ATS keys), `objc_getClass`, `conformsToProtocol:`, `getenv` | n/a | `jfxm_osx_platform_init(void)` exported by `libjfxmedia.dylib`. WRAPPER was considered and rejected: the two OS interactions are ObjC messages, not C symbols Java can bind. |
| `OSXMediaPlayer` ObjC class: `+initialize`, `+peerForPlayer:`, `+setPeer:`, `+removePlayerPeers:`, `-initWithURL:javaPlayer:andEnv:…`, `-dispose`, property forwarders | `OSXMediaPlayer.mm:47-283` | JNI-GLUE | `GetJavaVM`, `NewGlobalRef`, `JObjectPeers`, `GetJavaEnvironment`+`DetachCurrentThread`; forwarders are pure message forwarding to `id<OSXPlayerProtocol>` | n/a | Collapse: `jfxm_player*` for AVF is the `AVFMediaPlayer` instance (CFBridgingRetain) plus its dispatcher; the C entry points message `id<OSXPlayerProtocol>` directly. `+initPlayerPlatform` (`:69-95`) is OS-CALL and survives inside `jfxm_osx_platform_init`. |
| `JObjectPeers` (whole file) | `Utils/JObjectPeers.m:1-214` | JNI-GLUE | `GetJavaVM`, `IsSameObject` | n/a | Delete; opaque handle returned from create |
| `NSStringFromJavaString` | `Utils/JavaUtils.m:28-42` | JNI-GLUE | `GetStringChars`/`GetStringLength`/`ReleaseStringChars` | n/a | Delete; Java passes UTF-8 `const char*` (`arena.allocateFrom(String)`), C does `[NSString stringWithUTF8String:]` |
| `GetJavaEnvironment` | `Utils/JavaUtils.m:44-56` | JNI-GLUE | `GetEnv`, `AttachCurrentThreadAsDaemon` | n/a | Delete |
| `MTObjectProxy` (whole file) | `Utils/MTObjectProxy.m:1-258` | PURE (dead) | Foundation only (`NSInvocation`, `performSelectorOnMainThread:`); **no call sites** in `src/main` (only `#import` at `OSXMediaPlayer.h:27`) | n/a (unused) | Delete |
| `CVVideoFrame::CVVideoFrame`, `PrepareChunky`, `PreparePlanar`, `Dispose`, `~CVVideoFrame` | `CVVideoFrame.mm:42-179` | OS-CALL | `CVPixelBufferGetPixelFormatType/GetWidth/GetHeight/GetExtendedPixels/IsPlanar/LockBaseAddress/GetBaseAddress[OfPlane]/GetBytesPerRow[OfPlane]/GetPlaneCount/GetHeightOfPlane/Retain/Unlock/Release` | n/a | Keep; exposed to Java only through the core `jfxm_frame_*` (NativeVideoBuffer slice) as a `CVideoFrame*` |
| `CVVideoFrame::IsFormatSupported` | `:31-40` | PURE (trivial switch on 3 FourCCs) | none | exact | Keep (4 lines inside an OS-CALL class; must match `VO_FORMATS` at `AVFMediaPlayer.mm:61-63`) |
| `CVVideoFrame::ConvertToFormat` | `:181-208` | OS-CALL + calls PURE-HOT | `CVPixelBufferCreate/LockBaseAddress` + `ColorConvert_YCbCr422p_to_BGRA32_no_alpha` (`Utils/ColorConverter.c`, core slice, `-msse2`) | ColorConverter: **unknown** — experiment: run a corpus of `2vuy` frames through the C converter and a Java port, assert byte equality of BGRA output | Keep (the converter's verdict belongs to the core slice) |
| `AVFMediaPlayer` init/dispose/KVO/notification/output creation/state/track extraction | `AVFMediaPlayer.mm:126-686` | OS-CALL | `AVPlayer playerWithURL:`, `AVPlayerItemVideoOutput`, `addObserver:forKeyPath:`, `NSNotificationCenter`, `CVDisplayLinkCreateWithActiveCGDisplays/SetOutputCallback/Stop`, `CMTimeGetSeconds`, `CMFormatDescriptionGetMediaSubType`, `CMAudioFormatDescriptionGetStreamBasicDescription`, `AudioChannelLayoutTag_GetNumberOfChannels` | n/a | Keep; `CJavaPlayerEventDispatcher*` becomes `JfxmPlayerCallbacks*` + `void* user` |
| `AVFMediaPlayer -getContentTypeFromURL:` | `:746-763` | PURE (suffix match) | none | exact | Keep in place (called inside the `AVAssetResourceLoaderDelegate`; not worth a boundary crossing). Note it ignores the `contentType` Java passed to create (`:778`). |
| `AVFMediaPlayer resourceLoader:shouldWaitForLoadingOfRequestedResource:` (+ renewal/cancel) | `:766-854` | OS-CALL | `AVAssetResourceLoadingRequest`/`respondWithData:`/`finishLoading`; calls `CStreamCallbacks::IsRandomAccess/Seek/ReadBlock/ReadNextBlock/CopyBlock` (Java `InputStream` upcalls) | n/a | Keep; stream callbacks become `JfxmStreamCallbacks` (core `CJavaInputStreamCallbacks` slice) |
| `displayLinkCallback`, `-sendPixelBuffer:…`, `-outputMediaDataWillChange:`, `-outputSequenceWasFlushed:`, `-hlsBugReset`, `-setFallbackVideoFormat` | `:688-737, 249-271, 320-337, 865-914` | OS-CALL | `itemTimeForCVTimeStamp:`, `hasNewPixelBufferForItemTime:`, `copyPixelBufferForItemTime:`, `CVGetCurrentHostTime`, `CMClockMakeHostTimeFromSystemUnits`, `CVDisplayLinkStart/Stop`, `requestNotificationOfMediaDataChangeWithAdvanceInterval:` | n/a | Keep (HLS bitrate-switch workaround `:887-899` is behaviour to preserve) |
| `SpectrumCallbackProc`, `-sendSpectrumEventDuration:timestamp:` | `:739-744, 858-863` | JNI-GLUE-adjacent (pure forwarding) | none | n/a | Becomes `cb->on_audio_spectrum(user, -1.0, duration, 1)` |
| `AVFAudioProcessor -mixer`, `-setAudioTrack:`, `-setVolume:`, `-setBalance:`; `InitAudioTap`/`FinalizeAudioTap`/`PrepareAudioTap`/`UnprepareAudioTap`/`ProcessAudioTap`; `AVFTapContext` | `AVFAudioProcessor.mm:51-248` | OS-CALL | `AVMutableAudioMix audioMix`, `audioMixInputParametersWithTrack:`, `setAudioTapProcessor:`, `MTAudioProcessingTapCreate(kMTAudioProcessingTapCreationFlag_PreEffects)`, `MTAudioProcessingTapGetStorage`, `MTAudioProcessingTapGetSourceAudio` | n/a | Keep unchanged |
| `AVFEqualizerBand` (ctor, `SetFilterType`, `SetCenterFrequency`, `SetChannelCount`, `Setup{Peak,LowShelf,HighShelf}Filter`, `RecalculateParams`, `ApplyFilter`) | `AVFAudioEqualizer.cpp:39-255` | PURE-HOT | `cos`, `tan`, `sqrt`, `pow` (libm), `vDSP_vsdivD`, `vDSP_deq22D`; formulae copied from the GStreamer equalizer (`:90-91,131-133`) | tolerance — bound **not measured**; would need a Java biquad vs `vDSP_deq22D` comparison over float32 input (double accumulation, float32 output); moot, see next column | **Stays native**: `ApplyFilter` runs inside `ProcessAudioTap` on the MTAudioProcessingTap real-time thread (`AVFAudioProcessor.mm:229`); a Java upcall there is not permissible |
| `AVFAudioEqualizer` (`AddBand`, `RemoveBand`, `MoveBand`, `ResetBandParameters`, `ProcessBufferLists`, `RunFilter`, accessors) | `:259-464` | PURE-HOT | `vDSP_vspdp`, `vDSP_vdpsp`, `pthread_mutex_*`, `calloc/free/memset` | tolerance (as above) | Stays native (RT thread); its `CAudioEqualizer` interface is reached through the shared `jfxm_eq_*` ABI (NativeAudioEqualizer slice) |
| `AVFSoundLevelUnit` (`setVolume`, `setBalance`, `CalculateChannelLevel`, `ProcessBufferLists`, `Process`) | `AVFSoundLevelUnit.cpp:29-133` | PURE-HOT | `vDSP_vsadd`, `vDSP_vclr`, `vDSP_vsmul`, `fabsf` | exact in principle (per-sample float multiply by a scalar) — moot | Stays native (RT thread) |
| `AVFAudioSpectrumUnit` (ctor, `ProcessBufferLists`, `SetBands`, `UpdateBands`, `SetupSpectralProcessor`, `ReleaseSpectralProcessor`, `PostMessageCallback`, accessors) | `AVFAudioSpectrumUnit.cpp:31-327` | OS-CALL | `gst_init_check`, `gst_element_factory_make("spectrum")`, `g_object_set`, `gst_audio_info_*`, `gst_spectrum_setup_api`, `gst_spectrum_transform_ip_api`, `gst_buffer_new_wrapped_full`, `gst_element_set_state`, `gst_structure_*`, `vDSP_vclr/vadd/vsdiv`, `CBandsHolder::AddRef/ReleaseRef` | unprovable (the GStreamer `spectrum` FFT output is the de facto spec, shared with the GST platform) | Stays native; `UpdateBands` writes into the band holder from the RT thread and fires the spectrum callback while holding `mBandLock` (`:184-202`) |

Counts: JNI-GLUE 23 exports + 4 glue units; OS-CALL 6 units; PURE-HOT 2 (RT-thread-bound); PURE 3 trivial/dead (`IsFormatSupported`, `getContentTypeFromURL`, `MTObjectProxy`); WRAPPER 0.

## 6. Upcall table

| Java target | C site | Calling thread | Payload today | Proposed callback |
|---|---|---|---|---|
| `NativeMediaPlayer.sendPlayerStateEvent(ID)V` | `AVFMediaPlayer.mm:315` (`setPlayerState`, deduplicated by `previousPlayerState`) called from `:457,462,468,473` (play/pause/stop/finish), `:498` (dispose → HALTED), `:187` (did-play-to-end block), `:367,378` (KVO status READY/HALTED) | (a) the Java thread that called play/pause/stop/finish/dispose; (b) main dispatch queue (`NSOperationQueue mainQueue`, `:184`); (c) AVFoundation KVO-notifying thread | `int state, double 0.0` | `on_player_state(user, int32_t state, double time)` |
| `sendDurationUpdateEvent(D)V` | `:384` | KVO thread | `double seconds` | `on_duration_update(user, double)` |
| `sendVideoTrack(ZJLjava/lang/String;IIIFZ)V` | `:619` via `CVideoTrack` built at `:604-611` | KVO thread (`currentItem.tracks`) | enabled, trackID, "Video Track N", encoding (H264/H265/CUSTOM from FourCC `:554-577`), width, height, frameRate, hasAlpha=false | `on_video_track(user, const JfxmVideoTrackInfo*)` |
| `sendAudioTrack(ZJLjava/lang/String;ILjava/lang/String;IIF)V` | `:664` via `CAudioTrack` `:658-663` | KVO thread | enabled, trackID, "Audio Track N", encoding (PCM/AAC/MPEG1AUDIO/MPEG1LAYER3), language ("und" default), channels (from layout tag, default 2), mask FL\|FR, sampleRate (default 44100) | `on_audio_track(user, const JfxmAudioTrackInfo*)` |
| `sendSubtitleTrack(ZJLjava/lang/String;ILjava/lang/String;)V` | `:674` via `CSubtitleTrack` `:669-673` | KVO thread | enabled, trackID, "Subtitle Track N", encoding, language | `on_subtitle_track(user, const JfxmSubtitleTrackInfo*)` |
| `sendFrameSizeChangedEvent(II)V` | `:734` | CVDisplayLink thread (`displayLinkCallback :865` → `sendPixelBuffer :699`) | width, height | `on_frame_size_changed(user, int32_t, int32_t)` |
| `sendNewFrameEvent(J)V` → `NativeVideoBuffer.createVideoBuffer` | `:736` | CVDisplayLink thread | `CVideoFrame*` (a `CVVideoFrame` holding a locked, retained `CVPixelBufferRef`) | `on_new_frame(user, jfxm_frame*)` |
| `sendAudioSpectrumEvent(DDZ)V` | `:742` ← `SpectrumCallbackProc :858` ← `AVFAudioSpectrumUnit::UpdateBands :199` ← `PostMessageCallback :256` ← `gst_spectrum_transform_ip_api :120` ← `ProcessBufferLists :65` ← `ProcessAudioTap` (`AVFAudioProcessor.mm:236`) | **MTAudioProcessingTap real-time audio thread**, with `mBandLock` held | timestamp `-1.0`, duration = `samplesPerInterval/44100` (`:195`, hard-coded 44100), `queryTimestamp=true` (Java then reads `playerGetPresentationTime()` on its event thread, `NativeMediaPlayer.java:722-728`, JDK-8240694) | `on_audio_spectrum(user, double ts, double dur, int32_t query_ts)` |
| `NativeAudioSpectrum` magnitudes/phases `float[]` (JNI array write, not a method call) | `AVFAudioSpectrumUnit.cpp:191` → `CJavaBandsHolder::UpdateBands` (`jni/JavaBandsHolder.cpp:71-86`, `SetFloatArrayRegion`); phases discarded (magnitudes passed twice) | RT audio thread | `float[bands]` ×2 | Off-heap band buffer owned by Java (NativeAudioSpectrum slice); C writes into it, no upcall |
| `Locator.getStringLocation()Ljava/lang/String;`, `Locator.createConnectionHolder()` | `OSXMediaPlayer.mm:298,330` via `CLocator::*` (`Locator/Locator.cpp:54-101`) | Java caller thread (inside the `osxCreatePlayer` downcall) | — | Removed: Java passes the strings and the stream callback table into `jfxm_player_create` |
| `ConnectionHolder.readNextBlock/readBlock/isSeekable/isRandomAccess/seek/closeConnection/property` via `CJavaInputStreamCallbacks` | `AVFMediaPlayer.mm:779-829` (`GetSizeHint`, `IsRandomAccess`, `Seek`, `ReadBlock`, `ReadNextBlock`, `CopyBlock`), `:516` (`CloseConnection` in dispose) | `playerLoaderQueue` serial dispatch queue (`:154-157`), under `@synchronized(self)` (`:769`) | position/size; up to `MAX_READ_SIZE` 1 MiB per request (`:106,797`) | `JfxmStreamCallbacks` (core slice); may block on Java I/O — never `critical` |
| `Logger.logMsg(ILjava/lang/String;)V` (static) via `CLogger::logMsg` (`jni/Logger.cpp:47-61`) | `AVFMediaPlayer.mm:253,323,365,371,714,720`; `OSXMediaPlayer.mm:309,317,334,343,360,373`; `OSXPlatform.mm:47-55` | main queue, KVO thread, CVDisplayLink thread, Java caller thread | level, message | Single process-wide `log(level, const char*)` stub (Logger slice) |

## 7. Proposed C ABI header (survivors only; shared surface with the GST backend)

```c
/* jfxmedia_api.h (excerpt for the player surface; shared by GST and AVF backends) */
#include <stdint.h>

typedef struct jfxm_player    jfxm_player;     /* opaque: AVF = CFBridgingRetain(AVFMediaPlayer) + dispatcher; GST = existing CPipeline owner */
typedef struct jfxm_equalizer jfxm_equalizer;  /* CAudioEqualizer*  (consumed by jfxm_eq_*, NativeAudioEqualizer slice) */
typedef struct jfxm_spectrum  jfxm_spectrum;   /* CAudioSpectrum*   (consumed by jfxm_spectrum_*, NativeAudioSpectrum slice) */
typedef struct jfxm_frame     jfxm_frame;      /* CVideoFrame* (CVVideoFrame on macOS; consumed by jfxm_frame_*, NativeVideoBuffer slice) */

enum { JFXM_BACKEND_GST = 0, JFXM_BACKEND_AVF = 1 };

/* PlayerState values are the existing kPlayerState_* / NativeMediaPlayer.eventPlayer* ints (UNKNOWN 0 … HALTED 7) */

typedef struct JfxmAudioTrackInfo {
    int32_t enabled; int64_t track_id; const char* name /*UTF-8*/; int32_t encoding;
    const char* language /*"und" when unknown*/; int32_t channels; int32_t channel_mask; float sample_rate;
} JfxmAudioTrackInfo;
typedef struct JfxmVideoTrackInfo {
    int32_t enabled; int64_t track_id; const char* name; int32_t encoding;
    int32_t width; int32_t height; float frame_rate; int32_t has_alpha;
} JfxmVideoTrackInfo;
typedef struct JfxmSubtitleTrackInfo {
    int32_t enabled; int64_t track_id; const char* name; int32_t encoding; const char* language;
} JfxmSubtitleTrackInfo;

/* One table per player; every slot may be NULL. Thread per slot documented in §8. */
typedef struct JfxmPlayerCallbacks {
    void (*on_player_state)(void* user, int32_t state, double present_time);
    void (*on_new_frame)(void* user, jfxm_frame* frame);          /* Java must jfxm_frame_release() */
    void (*on_frame_size_changed)(void* user, int32_t width, int32_t height);
    void (*on_audio_track)(void* user, const JfxmAudioTrackInfo* track);     /* pointer valid only during the call */
    void (*on_video_track)(void* user, const JfxmVideoTrackInfo* track);
    void (*on_subtitle_track)(void* user, const JfxmSubtitleTrackInfo* track);
    void (*on_duration_update)(void* user, double duration_seconds);
    void (*on_audio_spectrum)(void* user, double timestamp, double duration, int32_t query_timestamp);
    /* GST-only slots (AVF never calls them): */
    void (*on_marker)(void* user, const char* name, double time);
    void (*on_buffer_progress)(void* user, double clip_duration, int64_t start, int64_t stop, int64_t position);
    void (*on_stop_reached)(void* user);
    void (*on_media_error)(void* user, int32_t error_code);
    void (*on_warning)(void* user, int32_t code, const char* message);
} JfxmPlayerCallbacks;

/* Java InputStream bridge (core CJavaInputStreamCallbacks slice); AVF uses it only for jar:/jrt: sources */
typedef struct JfxmStreamCallbacks {
    int32_t (*read_next_block)(void* user);
    int32_t (*read_block)(void* user, int64_t position, int32_t size);
    void    (*copy_block)(void* user, void* dest, int32_t size);
    int32_t (*is_seekable)(void* user);
    int32_t (*is_random_access)(void* user);
    int64_t (*seek)(void* user, int64_t position);
    void    (*close_connection)(void* user);
    int32_t (*property)(void* user, int32_t prop, int32_t value);
} JfxmStreamCallbacks;

typedef struct JfxmPlayerCreateInfo {
    const char* uri;               /* UTF-8; Locator.getStringLocation() */
    const char* content_type;      /* UTF-8; Locator.getContentType() */
    int64_t     size_hint;         /* Locator.getContentLength() */
    const JfxmStreamCallbacks* stream; /* NULL => engine opens uri itself (AVF: file/http/https) */
    void*       stream_user;
} JfxmPlayerCreateInfo;

/* All functions return 0 on success or a MediaError code (jfxmedia_errors.h). The AVF backend returns
 * non-zero only from create (mapping the six ThrowJavaException sites); the other calls are no-ops on
 * a NULL/disposed player, exactly as the JNI code silently returned 0/0.0/false. */
JFX_EXPORT int32_t jfxm_player_create(int32_t backend, const JfxmPlayerCreateInfo* info,
                                      const JfxmPlayerCallbacks* cb, void* user, jfxm_player** out);
JFX_EXPORT int32_t jfxm_player_init(jfxm_player* p);               /* GST: gstInitPlayer; AVF: returns 0 */
JFX_EXPORT void    jfxm_player_dispose(jfxm_player* p);            /* AVF: [player dispose] + free; stops display link and tap callbacks before returning */
JFX_EXPORT int32_t jfxm_player_get_equalizer(jfxm_player* p, jfxm_equalizer** out);
JFX_EXPORT int32_t jfxm_player_get_spectrum(jfxm_player* p, jfxm_spectrum** out);
JFX_EXPORT int32_t jfxm_player_get_audio_sync_delay(jfxm_player* p, int64_t* out);
JFX_EXPORT int32_t jfxm_player_set_audio_sync_delay(jfxm_player* p, int64_t delay_ms);  /* AVF: stored, never applied (today's behaviour) */
JFX_EXPORT int32_t jfxm_player_play(jfxm_player* p);
JFX_EXPORT int32_t jfxm_player_pause(jfxm_player* p);
JFX_EXPORT int32_t jfxm_player_stop(jfxm_player* p);
JFX_EXPORT int32_t jfxm_player_finish(jfxm_player* p);
JFX_EXPORT int32_t jfxm_player_get_rate(jfxm_player* p, float* out);
JFX_EXPORT int32_t jfxm_player_set_rate(jfxm_player* p, float rate);
JFX_EXPORT int32_t jfxm_player_get_presentation_time(jfxm_player* p, double* out);
JFX_EXPORT int32_t jfxm_player_get_volume(jfxm_player* p, float* out);
JFX_EXPORT int32_t jfxm_player_set_volume(jfxm_player* p, float volume);
JFX_EXPORT int32_t jfxm_player_get_balance(jfxm_player* p, float* out);
JFX_EXPORT int32_t jfxm_player_set_balance(jfxm_player* p, float balance);
JFX_EXPORT int32_t jfxm_player_get_duration(jfxm_player* p, double* out);  /* AVF writes -1.0 for indefinite/not-ready */
JFX_EXPORT int32_t jfxm_player_seek(jfxm_player* p, double stream_time);
/* AVF-only (GST implements mute in Java, GSTMediaPlayer.java:168-190; GST backend returns JFXM_ERROR_NOT_SUPPORTED) */
JFX_EXPORT int32_t jfxm_player_get_mute(jfxm_player* p, int32_t* out);
JFX_EXPORT int32_t jfxm_player_set_mute(jfxm_player* p, int32_t mute);
/* Platform init, exported by libjfxmedia.dylib (not by libjfxmedia_avf): ATS info-dictionary workaround +
 * JFXMEDIA_AVF env check + objc_getClass("AVFMediaPlayer") probe. Returns 1 if the AVF backend is usable. */
JFX_EXPORT int32_t jfxm_osx_platform_init(void);
```

Where the two backends differ (the Java facade `JfxMediaNative` is one class; `OSXMediaPlayer`/`GSTMediaPlayer` keep their platform-specific behaviour):

| Concern | GST today | OSX today | Unified ABI |
|---|---|---|---|
| Creation | `GSTMedia.gstInitNativeMedia(Locator, contentType, sizeHint, long[] out)` + `GSTMediaPlayer.gstInitPlayer(ref)` | `osxCreatePlayer(Locator, contentType, sizeHint)` (throws) | `jfxm_player_create(backend, info, cb, user, &out)` + `jfxm_player_init` (no-op on AVF) |
| Error convention | `int` code + out-params, `throwMediaErrorException` in Java | direct return values; only create throws | `int32_t` code + out-params everywhere; OSX facade keeps "throw only on create" |
| Duration | code + `double[]` | `-1.0` → Java maps to `+Inf` | `get_duration(p, double*)`; mapping stays in `OSXMediaPlayer.java:151-156` |
| Mute | Java-side volume caching | native `AVPlayer.muted` | `get/set_mute` implemented only by AVF |
| Audio sync delay | applied in the GStreamer pipeline | stored, never applied | same prototype; document the no-op |
| Spectrum event | real timestamp, `queryTimestamp=false` | `-1.0`, `queryTimestamp=true` | same callback slot; Java behaviour unchanged (`NativeMediaPlayer.java:722-728`) |
| Platform init | `GSTPlatform` native | `osxPlatformInit` | `jfxm_osx_platform_init()` in addition to the GST init |
| Stream source | always via `javasource` plugin | only for `jar:`/`jrt:` (`OSXMediaPlayer.mm:327-328`) | `info->stream` NULL or not |
| `osxNeedsLocator` | — | dead declaration | dropped |

## 8. Callback tables — thread contract (goes into the header as comments)

| Slot | AVF calling thread | Constraints |
|---|---|---|
| `on_player_state` | Java caller thread (play/pause/stop/finish/dispose), main dispatch queue (did-play-to-end), AVFoundation KVO thread (status READY/HALTED) | Stub in `Arena.ofShared()`; must not throw |
| `on_duration_update`, `on_audio_track`, `on_video_track`, `on_subtitle_track` | AVFoundation KVO thread | Track structs valid only during the call; Java copies scalars/strings immediately |
| `on_frame_size_changed`, `on_new_frame` | CVDisplayLink thread (vsync) | Frame ownership: native owns, Java holds until `jfxm_frame_release`; keep today's `NativeVideoBuffer` hold semantics |
| `on_audio_spectrum` | MTAudioProcessingTap real-time audio thread, `mBandLock` held | Must be non-blocking and allocation-light; today's Java target only enqueues an event (`sendAudioSpectrumEvent` → `sendPlayerEvent`). Same JVM-attach cost as today's `AttachCurrentThreadAsDaemon` path. |
| `JfxmStreamCallbacks.*` | `playerLoaderQueue` serial dispatch queue, under `@synchronized(self)` | May block on Java I/O (never `critical`); `dispose` takes the same lock (`AVFMediaPlayer.mm:477`) so a slow read delays dispose — existing behaviour |
| Logger `log(level, msg)` | any of the above | Process-wide stub (Logger slice) |

Registry: Java assigns a `long` id per `OSXMediaPlayer`, stores `id → player` in a `ConcurrentHashMap`, passes it as `user`; `jfxm_player_dispose` returns only after the display link is stopped, the tap's spectrum callback pointer is nulled (`AVFMediaPlayer.mm:488-489`) and the AVF object released; then Java removes the registry entry and closes the stub arena (`NativeMediaPlayer.dispose()` already orders `playerDispose()` after terminating its event loop, `NativeMediaPlayer.java:1322-1342`).

## 9. Deletion candidates (clear the parity gate)

| Candidate | Verdict / parity | Lines | Per-platform copies | Existing Java counterpart | Parity test |
|---|---|---|---|---|---|
| `Utils/JObjectPeers.m` + `.h` | JNI-GLUE / n/a | 214 + 53 | 1 (macOS only; iOS has its own `platform/ios/jni` glue, not audited here) | registry `ConcurrentHashMap<Long, OSXMediaPlayer>` in the facade | none needed (no observable output); facade unit test that `create`/`dispose` round-trips a handle |
| `Utils/JavaUtils.m` + `.h` | JNI-GLUE / n/a | 56 + 44 | 1 | `Arena.allocateFrom(String)` UTF-8 | URI strings are ASCII/percent-encoded; assert `[NSString stringWithUTF8String:]` yields the same `NSURL` for the existing supported schemes (mac test) |
| `Utils/MTObjectProxy.m` + `.h` | PURE, dead / n/a (unused) | 258 + 49 | 1 | — | none (no call sites: grep `objectProxyWithTarget\|MTObjectProxy` → only its own files and the `#import` at `OSXMediaPlayer.h:27`) |
| `OSXMediaPlayer.mm` JNI section `:285-721` + peer plumbing `:43-67,104-181` and the `jobject`/`JavaVM*` ivars in `OSXMediaPlayer.h:38-39` | JNI-GLUE / n/a | ~540 of 721 (replaced by ~120 lines of `jfxm_player_*` C entry points that message `id<OSXPlayerProtocol>` directly) | 1 | `JfxMediaNative` | facade round-trip on macOS: create with a non-existent `file:` URL → HALTED state callback; create with a malformed URI → non-zero return (was `MediaException`) |
| `OSXMediaPlayer.java:197` `osxNeedsLocator` | dead declaration (no C implementation on any platform, never called) | 1 | — | — | none |

Approximate total: ~1,150 lines of Objective-C/JNI removed, ~120 added. Nothing under `avf/` and nothing in `CVVideoFrame.mm` is a deletion candidate.

## 10. Kept native despite being pure

| Unit | Verdict | Parity | Reason it stays |
|---|---|---|---|
| `AVFAudioEqualizer.cpp` / `AVFEqualizerBand` | PURE-HOT (`vDSP_deq22D`, `vDSP_vsdivD`, `vDSP_vspdp/vdpsp`, libm) | tolerance — bound **unmeasured**; would be settled by comparing float32 output of a Java biquad (double state) against `vDSP_deq22D` over a sweep of band configurations | Executes inside `ProcessAudioTap` on the MTAudioProcessingTap real-time audio thread (`AVFAudioProcessor.mm:229`). A Java upcall there would introduce JVM safepoint/allocation latency on the RT thread; the C code today never touches the JVM in that path. Also intentionally mirrors the GStreamer equalizer coefficients (`:90-91,131-133`). |
| `AVFSoundLevelUnit.cpp` | PURE-HOT (`vDSP_vsmul/vclr/vsadd`) | exact in principle (scalar float multiply per sample) | Same RT-thread reason (`AVFAudioProcessor.mm:243`) |
| `ColorConvert_YCbCr422p_to_BGRA32_no_alpha` (called from `CVVideoFrame::ConvertToFormat`, `CVVideoFrame.mm:192`) | PURE-HOT (`Utils/ColorConverter.c`, core slice; SSE2 via `-msse2`) | **unknown** — experiment: convert a corpus of `2vuy` frames with the C converter and a Java port and assert byte-equal BGRA output | Owned by the core slice; on macOS it runs on whichever thread calls `NativeVideoBuffer.nativeConvertToFormat` (Prism render thread) — no thread constraint, only the unproven parity |
| `CVVideoFrame::IsFormatSupported` (`:31-40`), `AVFMediaPlayer -getContentTypeFromURL:` (`:746-763`), `+initPlayerPlatform`'s `getenv("JFXMEDIA_AVF")` (`OSXMediaPlayer.mm:74-77`) | PURE, trivial | exact | Embedded in OS-CALL code paths; extracting them to Java would add boundary crossings for no reduction in native surface. The env check could move to `OSXPlatform.loadPlatform()` (`System.getenv`) in a later, separate commit. |
| `AVFAudioSpectrumUnit.cpp` | OS-CALL (not pure) — listed for completeness | unprovable (GStreamer `spectrum` FFT output is the de facto spec shared with the GST platform) | RT thread + GStreamer engine |

## 11. Build/test touchpoints, risks, recommendation

### Build/test touchpoints
* Nothing in this slice is compiled by Maven: `modules/javafx.media/pom.xml:40-44` states the native stack is not ported; the only mac build description is `jfxmedia/projects/mac/Makefile` (plus the `xcode_project/JFXMedia.xcodeproj`, which lists the same files). The untracked `modules/javafx.media/native/CMakeLists.txt` includes a `mac.cmake` that does not exist yet — the OSX migration cannot be verified until that file (or the Makefile run by hand) exists.
* After migration, the Makefile changes are: drop `Utils/JObjectPeers.m`, `Utils/JavaUtils.m`, `Utils/MTObjectProxy.m` from `JFXMEDIA_SOURCES` (Makefile:152-154); drop `-I$(JAVA_HOME)/include{,/darwin}` and `-I$(GENERATED_HEADERS_DIR)` (Makefile:50,71-75) once the *whole* dylib is JNI-free (the core slices gate this); `-DJFXMEDIA_JNI_EXPORTS` (Makefile:92) becomes the `JFX_EXPORT` define. Keep `-DOSX` on both `avf` (Makefile:232) and `gstreamer-lite` (`gstreamer/projects/mac/gstreamer-lite/Makefile:54`) — the `GstSpectrum` struct extension (`gstspectrum.h:94-99`) must have the same layout in both dylibs.
* Prebuilt libraries for the mac test runs go to `../caches/sdk/lib` (`WEBKIT-MEDIA-STUBS.md:41-56`: `libjfxmedia.dylib`, `libjfxmedia_avf.dylib`, `libgstreamer-lite.dylib`, `libglib-lite.dylib`, `libfxplugins.dylib`).
* Generated JNI headers `target/gensrc/headers/com_sun_media_jfxmediaimpl_platform_osx_OSXMediaPlayer.h` / `_OSXPlatform.h` disappear automatically when the `native` declarations go.
* Tests: none exist. Add, on macOS only and gated on `FULL_TEST`: (1) a hardware-free binding test — `-Djfxmedia.platforms=OSXPlatform`, create a player for a non-existent `file:` URL and assert a HALTED `PlayerStateEvent` arrives (AVPlayer status → Failed, `AVFMediaPlayer.mm:370-379`), and that a malformed URI makes `jfxm_player_create` return non-zero (was `MediaException` at `OSXMediaPlayer.mm:318`); (2) a playback test with a bundled `.mp4` from a `jar:` URL (exercises the `AVAssetResourceLoader` + stream callbacks path) reaching READY and delivering at least one `NewFrameEvent`; (3) an HLS `.m3u8` smoke test, since HLS is the reason OSXPlatform exists as GST's fallback (`GSTPlatform.java:143`, `PlatformManager.java:98-103`).
* **What a mac build must verify** (cannot be checked here): `nm -u libjfxmedia_avf.dylib` shows no `_JNI_*`/`Java_*`; `otool -L` on both dylibs lists exactly AVFoundation, CoreMedia, Accelerate, AudioUnit/AudioToolbox, MediaToolbox, CoreVideo, Cocoa, libobjc, `@rpath/libgstreamer-lite.dylib`, `@rpath/libglib-lite.dylib`, and (avf only) `@rpath/libjfxmedia.dylib`; `objc_getClass("AVFMediaPlayer")` still resolves after the rewrite (class name unchanged, ARC on, not dead-stripped); the `JFX_EXPORT` symbols are visible (`nm -gU libjfxmedia.dylib | grep jfxm_`); the spectrum callback still fires from the tap thread and `HandleAudioSpectrumEvents` still fills the timestamp; the Makefile's QTKit/QuickTime assertion (Makefile:171-174) still passes.

### Risks
* **Modified UTF-8**: only `GetStringUTFChars` on content type/URI in the `jar:`/`jrt:` branch (`OSXMediaPlayer.mm:349-350`) and UTF-16 → `NSString` for the URI (`JavaUtils.m:34-38`). No dependence on embedded NULs or supplementary-character encoding found; standard UTF-8 from `allocateFrom(String)` is safe.
* **`JNI_ABORT`**: none in slice.
* **Callbacks from non-Java threads**: five distinct native thread contexts (§8). The spectrum callback runs on the real-time audio thread while holding `mBandLock`; the FFM upcall must stay as cheap as the JNI one (enqueue only). The stream callbacks block on Java I/O inside `@synchronized(self)`, which `dispose` also takes (`AVFMediaPlayer.mm:477,769`).
* **Teardown ordering vs in-flight callbacks**: `-[OSXMediaPlayer dispose]` deletes the dispatcher (`:157-160`) right after `[player dispose]`; the display link is stopped (`AVFMediaPlayer.mm:510`) and the spectrum callback nulled (`:488-489`) before that, but whether `CVDisplayLinkStop` waits for an in-flight `displayLinkCallback` is not established from source. Under FFM: null the callback table under a lock inside `jfxm_player_dispose`, tolerate NULL slots, and close the Java stub arena only after `jfxm_player_dispose` returns.
* **Global refs pinning peers**: `javaPlayer` (`OSXMediaPlayer.mm:122`) plus the dispatcher's `m_PlayerInstance` and the input-stream callbacks' holder pin the Java player until `osxDispose`; `JObjectPeers` retains the ObjC peer so `dealloc` never runs without `removePeer`. The registry pattern preserves the lifetime but removes the pin.
* **Dangling equalizer/spectrum handles**: `osxGetAudioEqualizerRef`/`SpectrumRef` hand Java raw `CAudioEqualizer*`/`CAudioSpectrum*` extracted from `shared_ptr`s owned by `AVFAudioProcessor` (`AVFMediaPlayer.mm:232-240`); after `dispose` releases the AVF player, a late `NativeAudioEqualizer` call is a use-after-free. Existing risk; the facade should null the handles in `playerDispose`.
* **Exceptions across the boundary**: the six `ThrowJavaException` sites become return codes; the dispatcher's `reportException` disappears with it. Note the existing leak: on the first two error paths the freshly allocated `CJavaPlayerEventDispatcher` (`:303`) and the autorelease pool (`:306`) are not released — fixed for free by the rewrite.
* **Blocking**: no export blocks for long, but all take ObjC locks and drain autorelease pools — none may be `Linker.Option.critical`.
* **Struct-by-value**: `CMTime`/`CVTimeStamp` never cross the boundary; track info crosses by pointer.
* **32-bit assumptions**: `ptr_to_jlong`/`jlong_to_ptr` (`jni/JniUtils.h:33-37`) have an `(int)` branch on 32-bit; macOS is 64-bit only; replaced by plain casts.
* **Behavioural quirks to preserve, not fix, in the migration commit**: audio sync delay is a no-op on macOS; `contentType` from Java is ignored by the resource loader in favour of a URL-suffix guess (`:746-763,778`); spectrum duration uses a hard-coded 44100 Hz (`AVFAudioSpectrumUnit.cpp:195`); `previousPlayerState` deduplication; the HLS reset heuristic (`:887-899`).
* **Java side**: `OSXMediaPlayer` has no `long ptr` field today; the facade introduces one (`MemorySegment`), which is a module-private change.

### Recommendation
1. **Do not start with this slice.** Everything it calls into is shared core: the player callback table (`CJavaPlayerEventDispatcher`), `NativeVideoBuffer`/`jfxm_frame_*`, `NativeAudioEqualizer`/`NativeAudioSpectrum`/`NativeEqualizerBand` + `CJavaBandsHolder`, `CJavaInputStreamCallbacks`/`CLocator`, and `Logger`. Land those (GST-verified on Windows/Linux) first; the OSX slice then contributes only the AVF implementation of `jfxm_player_*` and `jfxm_osx_platform_init`.
2. **Migration commit (behaviour-neutral, macOS)**: add the `jfxm_player_*` AVF backend and `jfxm_osx_platform_init` in `OSXMediaPlayer.mm`/`OSXPlatform.mm` beside the JNI exports; add the OSX branch of `JfxMediaNative`; flip `OSXPlatform.loadPlatform()` and `OSXMediaPlayer` to the facade; then delete the 23 JNI exports, the `jobject`/`JavaVM*` ivars, `JObjectPeers`, `JavaUtils`, the `osxNeedsLocator` declaration, and strip `jni.h` from `OSXMediaPlayer.h`, `OSXPlayerProtocol.h` and `AVFMediaPlayer.h` (via `JavaPlayerEventDispatcher.h`). Remove the three `Utils/*.m` files from the Makefile / future `mac.cmake`. Run tests (1)–(3) above after the flip.
3. **Separate dead-code commit**: delete `Utils/MTObjectProxy.m/.h` and the import at `OSXMediaPlayer.h:27` (no behaviour, but keep the diff reviewable on its own).
4. **Not in scope of migration** (would be behaviour changes, each its own commit with tests): applying audio sync delay on macOS, honouring the Java-supplied content type in the resource loader, moving the `JFXMEDIA_AVF` env check to Java.
5. Keep `AVFAudioEqualizer`, `AVFSoundLevelUnit`, `AVFAudioSpectrumUnit`, `AVFAudioProcessor`, `AVFMediaPlayer`, `CVVideoFrame` untouched apart from the dispatcher-pointer type change; no ABI beyond `jfxm_player_*` is needed for them.
