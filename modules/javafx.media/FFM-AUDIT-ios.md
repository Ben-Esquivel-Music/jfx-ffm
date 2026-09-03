# JNI audit — slice `ios` (iOS platform + NativeAudioClip: delete-or-migrate decision)

These are the persisted working notes of the `jni-auditor` run for this slice (the run was cut off by a
session usage limit before it could format a final report; the notes are complete through the
recommendation). Line numbers refer to the tree at the start of branch `ffm/media`. The decision
(delete, not migrate) is recorded in `FFM-ABI-CONTRACT.md` section 1.

# ios slice notes (jni-auditor) — branch ffm/media

## Section 1: Java natives (28 iOS + 12 NativeAudioClip = 40)

Java files:
- modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/platform/ios/IOSPlatform.java (128 lines)
- modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/platform/ios/IOSMedia.java (82 lines)
- modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/platform/ios/IOSMediaPlayer.java (463 lines)
- modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeAudioClip.java (176 lines)
- modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/AudioClipProvider.java (85)
- modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/NativeMediaAudioClip.java (136)
- modules/javafx.media/src/main/java/com/sun/media/jfxmediaimpl/platform/PlatformManager.java (245)

### IOSPlatform (1 native, static)
- IOSPlatform.java:126  private static native void iosPlatformInit()  -> Java_com_sun_media_jfxmediaimpl_platform_ios_IOSPlatform_iosPlatformInit
  - loadPlatform() (l.82-97): returns false if !PlatformUtil.isIOS(); calls iosPlatformInit(), catches UnsatisfiedLinkError -> false
  - NO static{} NativeLibLoader block; relies on NativeMediaManager having loaded jfxmedia.

### IOSMedia (2 natives, instance)
- IOSMedia.java:75  private native int iosInitNativeMedia(Locator, String contentType, long sizeHint, long[] nativeMediaHandle) -> Java_..._IOSMedia_iosInitNativeMedia ; out-param long[1] handle
- IOSMedia.java:80  private native void iosDispose(long refNativeMedia) -> Java_..._IOSMedia_iosDispose
- handle field: `private long refNativeMedia` (l.39); getNativeMediaRef() l.63

### IOSMediaPlayer (25 natives, all instance; handle passed as long refNativeMedia)
IOSMediaPlayer.java:233-262:
- iosInitPlayer(long) -> int
- iosGetAudioSyncDelay(long, long[] syncDelay) -> int   [out long[1]]
- iosSetAudioSyncDelay(long, long delay) -> int
- iosPlay(long) -> int
- iosPause(long) -> int
- iosStop(long) -> int
- iosGetRate(long, float[] rate) -> int   [out float[1]]
- iosSetRate(long, float) -> int
- iosGetPresentationTime(long, double[] time) -> int [out double[1]]
- iosGetVolume(long, float[] volume) -> int [out]
- iosSetVolume(long, float) -> int
- iosGetBalance(long, float[] balance) -> int [out]
- iosSetBalance(long, float) -> int
- iosGetDuration(long, double[] duration) -> int [out; -1.0 => POSITIVE_INFINITY in Java l.204]
- iosSeek(long, double streamTime) -> int
- iosDispose(long) -> void
- iosFinish(long) -> int
- iosSetOverlayX(long, double) -> int
- iosSetOverlayY(long, double) -> int
- iosSetOverlayVisible(long, boolean) -> int
- iosSetOverlayWidth(long, double) -> int
- iosSetOverlayHeight(long, double) -> int
- iosSetOverlayPreserveRatio(long, boolean) -> int
- iosSetOverlayOpacity(long, double) -> int
- iosSetOverlayTransform(long, 12 doubles) -> int
JNI symbols: Java_com_sun_media_jfxmediaimpl_platform_ios_IOSMediaPlayer_<name>
Note: mute is implemented purely in Java (l.146-159); NullAudioEQ/NullAudioSpectrum/NullEQBand are pure-Java stubs (l.264-411).

### NativeAudioClip (12 natives; 5 static + 7 instance)
NativeAudioClip.java:41-45 (static): nacInit()->boolean, nacLoad(Locator)->long, nacCreate(byte[],int,int,int,int,int)->long, nacUnload(long)->void, nacStopAll()->void
NativeAudioClip.java:83-89 (instance): nacCreateSegment(long,double,double)->NativeAudioClip, nacCreateSegment(long,int,int)->NativeAudioClip [OVERLOADED: JNI long-name mangling required], nacResample(long,int,int,int)->NativeAudioClip, nacAppend(long,long)->NativeAudioClip, nacIsPlaying(long)->boolean, nacPlay(long,double,double,double,double,int,int)->void, nacStop(long)->void
JNI symbols: Java_com_sun_media_jfxmediaimpl_NativeAudioClip_<name>; the two nacCreateSegment overloads need __JDD / __JII suffixes.
handle field: `private long nativeHandle` (l.37); disposer via MediaDisposer.addResourceDisposer -> nacUnload.
NO static{} loadLibrary block.

### AudioClipProvider (desktop fallback) — quote
AudioClipProvider.java:49-60:
    private AudioClipProvider() {
        useNative = false;
        try {
            useNative = NativeAudioClip.init();
        } catch (UnsatisfiedLinkError ule) {
            Logger.logMsg(Logger.DEBUG, "JavaFX AudioClip native methods not linked, using NativeMedia implementation");
        } catch (Exception t) { ... }
    }
load()/create()/stopAllClips() (l.62-83): if (useNative) NativeAudioClip.* else NativeMediaAudioClip.*
NativeAudioClip.init() (l.47-49) = `return nacInit();` -> static native -> UnsatisfiedLinkError when no Java_com_sun_media_jfxmediaimpl_NativeAudioClip_nacInit symbol in any loaded library.

### PlatformManager iOS selection — quote
PlatformManager.java:90: `if (!PlatformUtil.isIOS() && isPlatformEnabled("GSTPlatform"))` -> GSTPlatform via reflection
PlatformManager.java:107-113: `if (PlatformUtil.isIOS() && isPlatformEnabled("IOSPlatform"))` -> Class.forName("com.sun.media.jfxmediaimpl.platform.ios.IOSPlatform") reflectively (getPlatformInstance l.125-140 catches Exception -> null)
=> iOS platform is loaded ONLY by reflection, ONLY when PlatformUtil.isIOS().

## Section 2: C/ObjC side (platform/ios: 28 files, 5217 lines total)

JNIEXPORT sites (grep -c): .h = 49, .m = 51 (51 includes JNI_OnLoad_jfxmedia and one commented-out nativeCanPlayContentType) => 100 sites in scope.
Per file (.h/.m): NativeAudioClip 12/12; NativeAudioSpectrum 7/7 (the .m holds PROTOTYPES ONLY, no bodies); IOSMedia 2/2; IOSMediaPlayer 25/25; IOSPlatform 1/3; Logger 2/2.
All jni/*.h begin with "DO NOT EDIT THIS FILE - it is machine generated" = javah duplicates of what target/gensrc/headers now produces; symbol sets identical (diffed).

Definitions (file:line -> what it does):
IOSPlatform.m:45  JNI_OnLoad_jfxmedia -> stores global JavaVM *javavm (l.40)
IOSPlatform.m:112 iosPlatformInit -> initAudioSession() l.65: AudioSessionInitialize, AudioSessionGetProperty(kAudioSessionProperty_OtherAudioIsPlaying), [AVAudioSession setCategory:error:]; error value discarded (TODO JDK-8096014)
IOSMedia.m:42  iosInitNativeMedia -> GetMethodID(Locator.getStringLocation)+CallObjectMethod+GetStringUTFChars -> [[Media alloc] initMedia:] -> SetLongArrayRegion; always ERROR_NONE
IOSMedia.m:89  iosDispose -> [media dispose] (Media.m:333 dispose body is EMPTY, TODO)
IOSMediaPlayer.m:43  iosInitPlayer -> [MediaPlayer initPlayerWithMedia:javaEnvironment:javaPlayer:result:] (allocs EventDispatcher, NewGlobalRef(player))
IOSMediaPlayer.m:81  iosGetAudioSyncDelay -> STUB ERROR_NONE, out-array never written
IOSMediaPlayer.m:92  iosSetAudioSyncDelay -> STUB ERROR_NONE
IOSMediaPlayer.m:103/132/161 iosPlay/iosPause/iosStop -> [player play/pause/stop]
IOSMediaPlayer.m:190 iosGetRate -> [player getRate:] + SetFloatArrayRegion
IOSMediaPlayer.m:223 iosSetRate -> [player setRate:]
IOSMediaPlayer.m:252 iosGetPresentationTime -> [player getCurrentTime:] + SetDoubleArrayRegion
IOSMediaPlayer.m:285 iosGetVolume -> [player getVolume:] (returns cached playbackVolume, no OS query) + SetFloatArrayRegion
IOSMediaPlayer.m:318 iosSetVolume -> [player setVolume:] (AVMutableAudioMix per track; HLS path uses MPMusicPlayerController iPodMusicPlayer global volume)
IOSMediaPlayer.m:347 iosGetBalance -> STUB ERROR_NONE, out-array never written
IOSMediaPlayer.m:358 iosSetBalance -> STUB ERROR_NONE
IOSMediaPlayer.m:369 iosGetDuration -> [media duration] + SetDoubleArrayRegion (-1 = unknown)
IOSMediaPlayer.m:394 iosSeek -> [player seek:] (AVPlayer seekToTime:... completionHandler block)
IOSMediaPlayer.m:423 iosDispose -> [player dispose]; [player release]
IOSMediaPlayer.m:442 iosFinish -> [player finish]
IOSMediaPlayer.m:472,501,530,559,588,617,646,675 iosSetOverlay{X,Y,Visible,Width,Height,PreserveRatio,Opacity,Transform} -> [player overlaySet*] (AVPlayerLayer + CATransaction on UIApplication keyWindow root view)
NativeAudioClip.m:41  nacInit -> returns true unconditionally
NativeAudioClip.m:52  nacLoad -> Locator.getStringLocation upcall + GetStringUTFChars -> [AudioClip load:] (NSData dataWithContentsOfFile/URL, AVAudioPlayer initWithData)
NativeAudioClip.m:90  nacPlay -> setPan/setVolume/setRate/setLoopCount/play on AVAudioPlayer; balance and priority args IGNORED
NativeAudioClip.m:117 nacIsPlaying; :140 nacStop; :162 nacStopAll ([AudioClip stopAll] over static players array); :177 nacUnload
NativeAudioClip.m:208 nacCreate -> STUB 0 (UNSUPPORTED) => Java would throw MediaException("Cannot create audio clip")
NativeAudioClip.m:221/234/247/260 nacCreateSegment__JDD/__JII, nacResample, nacAppend -> STUB NULL
Logger.m:39 Logger_nativeInit -> [ErrorHandler initHandler]; Logger.m:51 nativeSetNativeLevel -> [ErrorHandler setLevel:]  (DUPLICATE of desktop exports in jfxmedia/jni/com_sun_media_jfxmedia_logging_Logger.cpp)
NativeAudioSpectrum.m: 7 prototypes, no definitions (would be unresolved if referenced)

Cached IDs / globals:
IOSPlatform.m:40 JavaVM *javavm
ErrorHandler.m:39-41 static jclass javaLoggerClass (NewWeakGlobalRef, never deleted), static jmethodID logMsg1Method, logMsg2Method
EventDispatcher.h:61-71 per-instance jmethodID x11; only 8 used (State, MediaError, FrameSizeChanged, AudioTrack, VideoTrack, BufferProgress, DurationUpdate, plus State again for EOM); NewFrame, Marker, AudioSpectrum looked up but never called; Halt has a sender but no caller
EventDispatcher.m:445,457,469,481,494,536,570-571 static jmethodID for Boolean/Integer/Long/Double/GregorianCalendar/Duration/HashMap ctors + HashMap.put — DEAD (sendMetadataEvent commented out)

Global refs:
EventDispatcher.m:117 NewGlobalRef(playerInstance) / :136 DeleteGlobalRef in -dispose. BUT nothing calls [eventDispatcher dispose]: MediaPlayer.dispose (MediaPlayer.m:150-166) only removes KVO observers; dealloc (l.137) releases the dispatcher object without calling dispose => global ref on IOSMediaPlayer LEAKS (pins the Java peer).
ErrorHandler.m:61 NewWeakGlobalRef(Logger class), process lifetime.

Upcalls (site -> Java target -> thread):
EventDispatcher.m:150 sendPlayerStateEvent(ID)V <- iosPlay/Pause/Stop (Java thread); KVO observeValueForKeyPath + AVPlayerItemDidPlayToEndTime notification (AVFoundation / main-queue thread); seek completion block (AVFoundation queue) via [self play]
EventDispatcher.m:167 sendPlayerMediaErrorEvent(I)V <- KVO error context; Media.reportError from loadValuesAsynchronouslyForKeys completion; iosInitPlayer (Java thread)
EventDispatcher.m:184 sendPlayerHaltEvent(Ljava/lang/String;D)V <- NO CALLER; and passes NSString* where jstring expected (latent bug)
EventDispatcher.m:202 sendFrameSizeChangedEvent(II)V <- KVO presentationSize (HLS only)
EventDispatcher.m:221 sendPlayerStateEvent(eventPlayerFinished) <- playerItemDidReachEnd notification; iosFinish (Java thread)
EventDispatcher.m:241 sendBufferProgressEvent(DJJJ)V <- KVO loadedTimeRanges
EventDispatcher.m:294 sendAudioTrack(ZJLjava/lang/String;ILjava/lang/String;IIF)V <- initializePlayerItemWithAsset: Java thread (iosInitPlayer) or asset-loading completion queue (Media.m:130); channels=2, mask=0, rate=44100 hard-coded
EventDispatcher.m:349 sendVideoTrack(ZJLjava/lang/String;IIIFZ)V <- same
EventDispatcher.m:396 sendDurationUpdateEvent(D)V <- Media.updateDuration on asset-loading completion queue
EventDispatcher.m:629 HashMap.put <- DEAD
ErrorHandler.m:84  static Logger.logMsg(ILjava/lang/String;)V <- every log site, any thread
ErrorHandler.m:106 static Logger.logMsg(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V <- NO CALLER
IOSMedia.m:61 and NativeAudioClip.m:66 Locator.getStringLocation()Ljava/lang/String; <- Java thread inside the downcall
All targets verified to exist in NativeMediaPlayer.java (l.1457-1562) / Logger.java (l.189,215) / Locator.java:572 with matching signatures.

Thread attach: JniUtils.c:32-46 media_getEnv (GetEnv || AttachCurrentThreadAsDaemon) and :48 detachThread (DetachCurrentThread) — attach+detach on EVERY upcall.
Strings/arrays: GetStringUTFChars x2 (URLs); NewStringUTF x9; Set{Long,Float,Double}ArrayRegion x5; no *Critical, no Get*ArrayElements, no DirectBuffer; no ThrowNew/ExceptionCheck anywhere; FindClass x5.
32-bit bug: JniUtils.h:38-39 jlong_to_ptr/ptr_to_jlong cast through (int) — pointer truncation on arm64.
Uninitialised return: MediaPlayer.m:802,813,841,869,899,924,960 `jint result;` returned unset when state == INITIAL (overlaySetX/Y/Width/Height/PreserveRatio/Opacity/Transform) => garbage error code to Java.
Frameworks used (#import): Foundation, AVFoundation, AudioToolbox (AudioSession* API deprecated iOS 7), MediaPlayer (MPMusicPlayerController, iOS-only), UIKit (UIApplication keyWindow, deprecated iOS 13). Non-ARC (manual retain/release). NSDayCalendarUnit (deprecated). No SIMD.

## Section 3: Build side / evidence that nothing builds or ships iOS
- jfxmedia/projects/win/Makefile CPP_SOURCES l.126-160: platform/gstreamer/* only; C_SOURCES Utils/ColorConverter.c. No platform/ios.
- jfxmedia/projects/linux/Makefile l.112-150: same. No platform/ios.
- jfxmedia/projects/mac/Makefile l.113-157: platform/gstreamer/* + Utils/{JObjectPeers,JavaUtils,MTObjectProxy}.m + platform/osx/{OSXPlatform,OSXMediaPlayer,CVVideoFrame}.mm; AVF_SOURCES l.235-240 platform/osx/avf/* -> libjfxmedia_avf.dylib. No platform/ios.
- grep platform/ios|NativeAudioClip over every Makefile*, .pbxproj, .vcxproj, .filters, .sln, .sh under native/: NO MATCH.
- xcode_project/JFXMedia.xcodeproj: a macOS Xcode project (frameworks Cocoa, AppKit, Foundation, AVFoundation, QTKit, CoreVideo, MediaToolbox, AudioUnit, CoreMIDI, Accelerate; JFXMedia-Prefix.pch imports <Cocoa/Cocoa.h>); file refs = jfxmedia core + jni + platform/osx + platform/osx/avf + gstreamer-lite/glib folders. Zero platform/ios refs. build_prereqs.sh shells out to GRADLE (:media:generateHeaders :media:buildMacPlugins :media:generateMediaErrorHeader) — dead in the Maven fork; java_home.sh looks for JDK 1.8. Last commit 8287822 (2022). Nothing in the repo references xcode_project.
- vs_project/FXMedia.sln: VS2017 (PlatformToolset v141, Win32) IDE solution for glib/gstreamer/plugins/jfxmedia (jfxmedia.vcxproj: 43 ClCompile entries, hard-coded include C:\cygwin\home\kirill\work\jdk\jdk1.8.0_40\include). Developer convenience only (JDK-8222780 in doc-files/release-notes-13.md); not referenced by any pom/cmake/CI. Last commit 8379561.
- modules/javafx.media/pom.xml: <exclude>com/sun/media/jfxmediaimpl/platform/ios/**</exclude> at l.97, l.119, l.140 (every jar config); platform/osx/** excluded at l.96, l.118 on non-mac.
- .github/workflows: no ios reference. No CMake for media.
- PlatformUtil.java:61 IOS = os.name startsWith("iOS"); :121 isIOS(). Always false on Windows/Linux/macOS JDKs.
- PlatformManager.java:90 GSTPlatform only if !isIOS(); :107-113 IOSPlatform only if isIOS(), loaded reflectively by name.
- Prebuilt C:\SourceCode\caches\sdk\bin\jfxmedia.dll (144,896 B, dumpbin -exports): 54 Java_ exports = NativeAudioEqualizer 5, NativeAudioSpectrum 7, NativeEqualizerBand 6, NativeVideoBuffer 13, logging_Logger 2, GSTMedia 2, GSTMediaPlayer 18, GSTPlatform 1. ZERO NativeAudioClip_*, ZERO platform_ios_*. glib-lite/gstreamer-lite/fxplugins export no Java_ symbols. jfxmedia.dll imports: gstreamer-lite.dll, glib-lite.dll, KERNEL32.dll, MSVCP140.dll, VCRUNTIME140(.1).dll, api-ms-win-crt-{runtime,stdio,heap}.
- target/gensrc/headers DOES contain com_sun_media_jfxmediaimpl_NativeAudioClip.h and the three platform_ios_*.h (javac -h runs over all sources; only jar packaging excludes ios). Generated IOSMediaPlayer.h carries the 8 eventPlayer* constants (@Native in NativeMediaPlayer.java:74-81) — same as the in-tree copy.
- jfxmedia_errors.h: the only copy in the source tree is the stale platform/ios one (96 ERROR_ tokens); MediaError.java has 102 entries; missing from stale copy: ERROR_BASE_OSX, ERROR_OSX_INIT, ERROR_JNI_UNEXPECTED, ERROR_MEDIA_H265_FORMAT_UNSUPPORTED, ERROR_INVALID_LIBSWSCALE, ERROR_MISSING_LIBSWSCALE. HeaderGen.java writes to arg-supplied path (l.57-65); nothing in Maven invokes it.
- git log platform/ios: f06b15b6e6 (2024 copyright), 03eb8b11af (bug-id remap), e1cb1911f0 8240694 (2020), fa88095b57 8187637 (2017), c420248b9b (2016 dir rename). No functional iOS work since 2017.
- Tests: zero references to IOSPlatform/IOSMedia/IOSMediaPlayer/NativeAudioClip/AudioClipProvider/NativeMediaAudioClip in modules/*/src/test or tests/system; tests/manual/monkey AudioClipPage uses only public javafx.scene.media.AudioClip. modules/javafx.media has no src/test.
- Docs naming IOSPlatform: .github/agents/media-format-engineer.agent.md:18, .github/skills/jfx-media-native/SKILL.md:30, .agents/skills/jfx-media-native/SKILL.md:29 (tracked status checked separately), plus gitignored .claude/skills/jfx-media-native/SKILL.md.

## Section 4: Desktop AudioClip trace (question 3)
javafx.scene.media.AudioClip(String) l.80-83 -> com.sun.media.jfxmedia.AudioClip.load(URI) l.134-136 -> AudioClipProvider.getProvider().load(source)
AudioClipProvider() l.49-60: useNative=false; try { useNative = NativeAudioClip.init(); } catch (UnsatisfiedLinkError) { DEBUG log "JavaFX AudioClip native methods not linked, using NativeMedia implementation" }
NativeAudioClip.init() l.47-49 -> nacInit() (static native, l.41). No static{} block, no loadLibrary; class init only builds clipDisposer (l.39).
JVM links Java_com_sun_media_jfxmediaimpl_NativeAudioClip_nacInit lazily at first call: searched in every library loaded via the module loader = glib-lite, gstreamer-lite, jfxmedia (+fxplugins, jfxmedia_avf on mac): none exports it (dumpbin above; mac/linux Makefiles never compile NativeAudioClip.m) => UnsatisfiedLinkError => caught => useNative=false.
Then load()/create()/stopAllClips() l.62-83 take the NativeMediaAudioClip branch: NativeMediaAudioClip.load -> Locator + cacheMedia; play -> NativeMediaAudioClipPlayer.playClip -> MediaManager.getPlayer (l.319) = the regular GST/AVF player.
Observable difference after deleting NativeAudioClip + the useNative branch: only the DEBUG-level log line disappears (and one UnsatisfiedLinkError construction at first use). No public API behaviour changes: create() still throws UnsupportedOperationException from NativeMediaAudioClip.create (l.70-72) as today.

## Section 5: Triage verdicts (1.1) + parity
Rule: whole slice is unreachable on every platform this fork builds (PlatformUtil.isIOS() false; no library exports the symbols) => deletion is behaviour-neutral by construction, PARITY n/a (dead code), no Java reimplementation needed. Verdicts recorded for the record / contingency only.
JNI-GLUE (replaced by C ABI + callback tables if ever kept): JNI_OnLoad_jfxmedia; JniUtils.c media_getEnv/detachThread; all 25 Java_..IOSMediaPlayer_* dispatchers; 2 IOSMedia_*; IOSPlatform_iosPlatformInit dispatcher; 12 NativeAudioClip_* dispatchers; Logger.m 2 (duplicate of desktop); EventDispatcher.m entire (initMethodIDs, initWithJavaEnv, dispose, 8 send*, createObjectOfClass/createBoolean/Integer/Long/Double/Date/Duration/createMetadataMap); ErrorHandler initHandler/logMsg x2; NativeAudioSpectrum.m prototypes (orphan).
OS-CALL (evidence): MediaPlayer.m initializePlayerWithURL/initializePlayerItemWithAsset/play/pause/stop/seek/setRate/setVolume/getCurrentTime/finish/dispose/registerPlayerItemKVO/observeValueForKeyPath/overlay* -> AVPlayer, AVPlayerItem, AVPlayerLayer, AVMutableAudioMix, MPMusicPlayerController, CATransaction, UIApplication; Media.m initMedia/loadMetadataAsync/updateTracks/updateDuration/updateMetadata -> AVURLAsset, loadValuesAsynchronouslyForKeys, AVAssetTrack; AudioClip.m load/play/stop/unload/isPlaying/set* -> NSData, AVAudioPlayer; IOSPlatform.m initAudioSession -> AudioSessionInitialize/AudioSessionGetProperty/AVAudioSession setCategory (WRAPPER-shaped: 3 OS calls + no logic, but ObjC messaging => direct FFM binding would go through objc_msgSend; ruled OS-CALL).
PURE (parity exact, trivially): iosGetAudioSyncDelay/iosSetAudioSyncDelay/iosGetBalance/iosSetBalance (constant ERROR_NONE); nacInit (true); nacCreate/nacCreateSegment x2/nacResample/nacAppend (0/NULL); MediaPlayer getVolume (returns cached field); MediaPlayer getRate/getError trivial wrappers; ErrorHandler.mapAVErrorToFXError (switch on NSError code -> MediaError, PURE, PARITY exact given the raw code); MediaUtils.m urlFromString/resolveFileUrl/bundleUrlFromJarUrl (string manipulation + [NSBundle mainBundle] resourcePath; PURE, PARITY exact); debug.h NSLog macro.
No PURE-HOT. No SIMD.
Counts (functions, not JNIEXPORT sites): JNI-GLUE ~62 (51 exports + JniUtils 2 + EventDispatcher 17 + ErrorHandler 3 - overlaps), OS-CALL ~45 ObjC methods across MediaPlayer.m/Media.m/AudioClip.m/IOSPlatform.m, PURE ~14, WRAPPER 0 (ObjC), PURE-HOT 0.

## Section 6: Recommendation = DELETE (Java + ObjC), not migrate
Delete: modules/javafx.media/src/main/native/jfxmedia/platform/ios/** (28 files, 5217 lines); java platform/ios/{IOSPlatform,IOSMedia,IOSMediaPlayer}.java (673 lines); NativeAudioClip.java (176); simplify AudioClipProvider (drop useNative + NativeAudioClip.init try/catch, l.40,49-60,63-65,72-74,79-81) or delete it and call NativeMediaAudioClip directly from jfxmedia/AudioClip.java l.135,160,167; PlatformManager.java l.90 (drop !isIOS()), l.107-113 (drop block) and PlatformUtil import if unused; pom.xml l.97,119,140 excludes; NativeMediaManager.java l.109 (!isIOS() on gstreamer-lite load) — verify by grep; generated headers vanish automatically. Keep xcode_project? It is macOS-only, gradle-driven, unreferenced: recommend delete in a separate housekeeping commit (not iOS). vs_project: same, separate decision (JDK-8222780 history).
Risk DELETE: loses a 2013-era iOS reference impl (non-ARC, deprecated AudioSession/MPMusicPlayerController/keyWindow APIs, 32-bit jlong_to_ptr, uninitialised returns, leaked global ref); recoverable from git (commit fa88095b57 tree). No supported iOS JDK/FFM runtime exists in this fork.
Risk MIGRATE blind: ~40 downcalls + 8-entry callback table + player struct written for a toolchain and runtime nobody here has (no iOS SDK, no iOS JDK 25 with FFM), cannot compile, cannot test, must decide whether to preserve known bugs; the 100 JNIEXPORT sites stay in the JNI inventory until then; JavaFX has no iOS Glass build either (jfx-graphics-native: ios glass not built).

## Section 7: Extra iOS touchpoints outside the slice (leave for a follow-up, not the deletion commit)
- javafx/scene/media/MediaView.java: l.183 mediaPlayerOverlay field, l.269 updateMediaPlayerOverlay, l.290 "End of iOS specific stuff", isIOS() branches at l.491,592,642,701,760 (route property invalidations to the overlay instead of NodeHelper.markDirty), l.1032 jfxPlayer.getMediaPlayerOverlay(). MediaPlayerOverlay interface (com.sun.media.jfxmedia.control, exported to javafx.web) is implemented ONLY by IOSMediaPlayer.MediaPlayerOverlayImpl; GSTMediaPlayer.java:77 and OSXMediaPlayer.java:70 return null. After deleting IOSMediaPlayer, the overlay path in MediaView is dead but harmless (guarded by isIOS()).
- locator/Locator.java:119 comment, :231 `if (PlatformUtil.isIOS() && protocol.equals("ipod-library")) isIpod = true;` — dead after deletion, harmless.
- NativeMediaManager.java:109 `!PlatformUtil.isIOS()` guard on gstreamer-lite load — dead, harmless.
- Docs naming IOSPlatform are all gitignored (.agents/**, .github/agents/**, .github/skills/**, .claude/**); WEBKIT-MEDIA-STUBS.md does not mention iOS. No tracked doc needs editing.
- MediaDisposer has other users (LocatorCache, NativeMediaManager, NativeVideoBuffer) — stays.

## Section 8: Resumed run 2026-09-02 — verification pass (all Section 1-7 claims re-checked against source)
- Notes file found intact; results/ dir holds gst-platform, osx, plugins-libs outputs only => ios report still owed.
- Re-verified by reading: AudioClipProvider.java:49-83, NativeAudioClip.java:35-94, PlatformManager.java:83-140, IOSMediaPlayer.java:233-262, IOSPlatform.java:82-126, IOSMedia.java:39-80, IOSPlatform.m:36-60/100-140, NativeMediaManager.java:106-137, jfxmedia/AudioClip.java:134-168, NativeMediaAudioClip.java:62-126, PlatformUtil.java:61/121.
- JNIEXPORT inventory (grep -rn JNIEXPORT platform/ios): 100 sites = 49 in jni/*.h + 51 in jni/*.m. .m breakdown: NativeAudioClip 12, NativeAudioSpectrum 7 (prototypes only, no bodies), IOSMedia 2, IOSMediaPlayer 25, IOSPlatform 3 (JNI_OnLoad_jfxmedia l.45, iosPlatformInit l.112, commented-out NativeMediaManager_nativeCanPlayContentType l.127), Logger 2.
- Line numbers of every .m definition confirmed as in Section 2. .h prototype lines: NativeAudioClip.h 36,44,52,60,68,76,84,92,100,108,116,124; NativeAudioSpectrum.h 15,23,31,39,47,55,63; IOSMedia.h 15,23; IOSMediaPlayer.h 35..227 step 8; IOSPlatform.h 15; Logger.h 25,33.
- Repo-wide tracked references outside slice-owned files (git grep): pom.xml:97,119,140 excludes; jfxmedia/AudioClip.java:28,135,160,167 (AudioClipProvider); doc-files/release-notes-13.md:36 (vs_project, JDK-8222780). All other hits are WebKit's own Source/WebCore/platform/ios (unrelated). ZERO hits in any src/test or tests/system. ZERO tracked files under .agents/.github/agents/.github/skills/.claude (git ls-files count 0) => no tracked doc names IOSPlatform.
- Makefiles: grep -i ios over all media Makefile* matches only 'audio*' substrings; no platform/ios source anywhere. xcode_project/vs_project: zero refs to platform/ios, NativeAudioClip, IOSMedia. .github/workflows: no 'ios'. Prebuilt jfxmedia.dll: 54 Java_ exports, 0 matching NativeAudioClip|platform_ios.
- module-info.java: exports com.sun.media.jfxmedia.control (MediaPlayerOverlay lives there) to javafx.web etc.; no ios-specific package export (platform.ios is not exported; it is loaded reflectively).
- git log platform/ios (both trees): f06b15b6e6, 03eb8b11af, 411c1b113b, e7974bc846 (8308028 os.name->PlatformUtil), 48f6f5ba5b, 7cb408bdbf (8297412 warnings) — housekeeping only.

### Upcall call-site map (C site -> dispatcher -> Java target -> thread)
- MediaPlayer.m:414 handleStatusChange (KVO AVPlayerItem.status, AVFoundation/main-queue thread) -> EventDispatcher.m:150 sendPlayerStateEvent(eventPlayerReady)
- MediaPlayer.m:456,473 handleBufferChange (KVO playbackBufferEmpty / playbackLikelyToKeepUp) -> :150 sendPlayerStateEvent(Stalled/Playing)
- MediaPlayer.m:541 play / :560 pause / :577 stop (Java thread inside iosPlay/iosPause/iosStop; play ALSO from seek completion block MediaPlayer.m:672-680 on AVFoundation queue when state==EOM) -> :150 sendPlayerStateEvent
- MediaPlayer.m:95 sendErrorToJava (<- :504 KVO ItemErrorContext; <- :696 notifyError <- Media.m:143 reportError <- loadValuesAsynchronouslyForKeys completion Media.m:254-265) and MediaPlayer.m:350 initPlayerWithMedia (Java thread inside iosInitPlayer) -> EventDispatcher.m:167 sendPlayerMediaErrorEvent(I)V
- MediaPlayer.m:517 observeValueForKeyPath ItemPresentationSizeContext (HLS only, KVO thread) -> :202 sendFrameSizeChangedEvent(II)V
- MediaPlayer.m:182 finish (Java thread inside iosFinish) and :597 playerItemDidReachEnd (NSNotificationCenter AVPlayerItemDidPlayToEndTimeNotification, main thread) -> :221 sendEndOfMediaEvent => sendPlayerStateEvent(eventPlayerFinished)
- MediaPlayer.m:439 handleBufferChange (KVO loadedTimeRanges) -> :241 sendBufferProgressEvent(DJJJ)V
- MediaPlayer.m:108 / :125 sendTrackInfoToJava (<- initializePlayerItemWithAsset: Java thread via initPlayerWithMedia :338, or Media.m:130 updateTracks on asset-loading completion queue) -> :294 sendAudioTrack / :349 sendVideoTrack (channels=2, mask=0, rate=44100 hard-coded; encoding NONE)
- MediaPlayer.m:690 notifyDurationChanged (<- Media handleDurationStatusChange, asset-loading completion queue) -> :396 sendDurationUpdateEvent(D)V
- EventDispatcher.m:184 sendPlayerHaltEvent: NO caller. :379 (sendStopReachedEvent) and :654 (sendMetadataEvent) are inside /* */ comment blocks; :629 HashMap.put reachable only from the commented sendMetadataEvent => dead.
- ErrorHandler.m:84 Logger.logMsg(I,String) <- every [ErrorHandler logMsg:] / logError: site (Java thread, KVO thread, completion queues); :106 4-arg logMsg NO caller.
- IOSMedia.m:61, NativeAudioClip.m:66 Locator.getStringLocation() synchronous on the Java caller thread.
- Global-ref leak confirmed: MediaPlayer.m:137-146 dealloc -> [self dispose] (l.149-166 only removes KVO/notification observers) then [eventDispatcher release]; EventDispatcher dispose (l.131-140, DeleteGlobalRef) is never invoked => IOSMediaPlayer pinned forever.
- Uninitialised `jint result` confirmed at MediaPlayer.m:798-810 (overlaySetX/Y pattern repeats for the other overlay setters).
- 32-bit truncation confirmed: JniUtils.h:38-39 `#define jlong_to_ptr(a) ((void*)(int)(a))`, `ptr_to_jlong(a) ((jlong)(int)(a))`.

## Section 9: Final verdict (ready for StructuredOutput)
VERDICT: DELETE the whole slice (Java + ObjC). Grounds: unreachable on every platform this fork builds (PlatformUtil.isIOS() false on win/linux/mac; IOSPlatform loaded only by reflection behind isIOS(); NativeAudioClip.nacInit has no implementation in any built/prebuilt library so AudioClipProvider always falls back to NativeMediaAudioClip). Deletion is behaviour-neutral by construction: the only observable delta on desktop is one DEBUG log line at AudioClipProvider.java:56 and the UnsatisfiedLinkError it swallows.
Triage counts (functions): JNI-GLUE 62 (42 Java_ dispatchers with bodies + JNI_OnLoad + 2 JniUtils + 17 EventDispatcher + 3 ErrorHandler - 3 overlap), OS-CALL ~45 ObjC methods, PURE 14 (9 constant-return stubs + getVolume/getRate + mapAVErrorToFXError + MediaUtils x3 -ish), PURE-HOT 0, WRAPPER 0. All PURE: PARITY exact (trivial) but moot — dead code.
ABI: none proposed (nothing survives). Contingency sketch (only if DELETE rejected): jfxm_ios_platform_init(); jfxm_ios_media_create(const char* url, void** out); jfxm_ios_media_dispose(void*); jfxm_ios_player_create(void* media, const JfxmPlayerCallbacks*, void* user, void** out); play/pause/stop/finish/seek/get_rate/set_rate/get_time/get_volume/set_volume/get_duration/dispose; overlay_set_{x,y,visible,width,height,preserve_ratio,opacity,transform}; callback table {state(user,int,double), error(user,int), frame_size(user,int,int), buffer_progress(user,double,int64,int64,int64), audio_track(user,int,int64,const char*,int,const char*,int,int,float), video_track(user,int,int64,const char*,int,int,int,float,int), duration(user,double)} + jfxm_log(level,const char*). NativeAudioClip would NOT be migrated even then: desktop never used it and iOS nacCreate/segment/resample/append are stubs.
Deletion touch list (one mechanical commit): native/jfxmedia/platform/ios/** (28 files, 5217 lines); java platform/ios/{IOSPlatform,IOSMedia,IOSMediaPlayer}.java (673); NativeAudioClip.java (176); AudioClipProvider.java drop useNative + init try/catch + 3 branches (or delete class and call NativeMediaAudioClip from jfxmedia/AudioClip.java:135,160,167); PlatformManager.java:90 drop `!PlatformUtil.isIOS() &&`, :107-113 drop block; pom.xml:97,119,140 drop excludes. Follow-up (separate, API-affecting): MediaPlayerOverlay interface + MediaView isIOS branches (l.183,269,491,592,642,701,760,1032) + Locator.java:231 + NativeMediaManager.java:109. Housekeeping (separate): xcode_project, vs_project (neither builds iOS; neither referenced by pom/CMake/CI).
Tests after deletion: mvn -pl modules/javafx.media install (compiles, 0 tests); mvn -pl tests/system test -DFULL_TEST=true -DUSE_ROBOT=true -Dtest=NodeInitializationStressTest (MediaView ctor); manual tests/manual monkey AudioClipPage (AudioClip playback via NativeMediaAudioClip) with prebuilt jfxmedia.dll in caches/sdk/bin.
