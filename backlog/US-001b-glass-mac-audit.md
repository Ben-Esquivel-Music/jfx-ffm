# US-001b — `glass/mac` JNI audit: `native-glass/mac` + `com.sun.glass.ui.mac` (workstream B)

Five `jni-auditor` inventories for [US-001b](US-001b-migrate-glass-mac-and-prism-mtl-jni-to-ffm-on-macos.md), merged
and re-verified against `master` `1a6788cc4d` on 2026-11-03 (Mac mini, macOS 27.0.1 arm64). The groups are g0 shared
infrastructure, g1 application/timer/robot/cursor/pixels/gestures, g2 window/view, g3 pasteboard/clipboard/menus/
dialogs/NSURL, and g4 accessibility. The sibling audit for workstream A is
[US-001b-prism-mtl-audit.md](US-001b-prism-mtl-audit.md).

Nothing was built, run or modified for this document. The binary evidence is the baseline JNI build of the same
commit, the "Before" run recorded in [US-001b-macos-gate.md](US-001b-macos-gate.md): its
`modules/javafx.graphics/target/native/bin/libglass.dylib` (arm64) and `target/gensrc/headers/javafx.graphics/`.
That build lived in a session clone that is gone; a build of `1a6788cc4d` reproduces it, and the gate file records
its `Java_` export counts.

## Summary

- **Counts (re-verified, §R1).** 155 Java `native` declarations in 15 classes (38 static, 117 instance, 46 of them
  overrides). 155 `JNIEXPORT` in 15 `.m` files, equal to the 155 `_Java_` symbols of the baseline `libglass.dylib`,
  plus `JNI_OnLoad`. 127 `Call*Method` lines in 20 files; 125 of them compile. Leaving out the two `reportException`
  lines inside the check macros and expanding two `GlassViewDelegate.m` macros gives 131 direct upcall sites. On top
  of those: 16 `NewObject` constructor upcalls, 26 `ClassForName`
  (`Class.forName`) sites and 284 `GLASS_CHECK_EXCEPTION*` sites that may upcall `Application.reportException`.
  100 files, 19,728 lines.
- **Verdicts of the 155 exports.** 103 OS-CALL, 33 WRAPPER, 19 PURE, 0 PURE-HOT.
  - WRAPPER: 23 have `PARITY: exact` and Java binds the OS symbol directly (8 MacRobot, the video refresh period,
    the data directory, 2 `FormatEncoder`, 3 `MacFileNSURL`, 7 MacAccessible, plus the dead `getString`, kept only
    as a test oracle). 9 are `PARITY: unknown` and wait for an experiment (6 MacTimer, the RTL query, 2 bookmark
    calls). 1 fails the gate and stays native (`NSAccessibilityUnignoredChildren`).
  - PURE: 10 are JNI bookkeeping (9 `_initIDs` and `MacMenuDelegate._setCallback`). 7 are exact deletions (Timer
    min/max period, Robot `_init`/`_destroy`, `_getMultiClickMax{X,Y}_impl` but for a race not reproduced, g2 §6
    (m), and `_setParent`). 2 keep a C one-liner because native code owns their state (`_isTriggerReactivation`,
    and the nested-loop flag half of `_leaveNestedEventLoopImpl`, whose return value moves to Java).
- **Decision.** Migrate `libglass`; do not delete it. `GlassGestureSupport.m` goes. `GlassRobot.m` goes; four small
  exports stay (g1 §10). `GlassTimer.m` stays as the `gmac_timer_*` shim until the owner's timer experiment (PB4).
  The facade is `MacGlassNative` plus `Mac*Calls` and `Mac*Callbacks` aggregate classes, each at most 900 lines.
- **Callback tables.** 7 are proposed: App (21 slots), Window (10), View (15), Gesture (5), Menu (5), Dialog (2) and
  Accessible (16), 74 slots in all. A 1-slot Timer table is added only if Java cannot own the timer. All stubs live
  in `Arena.global()` and are installed once. Peers are keyed by Java-assigned `int64_t` ids, which leave the
  registry only in the dealloc-time `*_dispose` / `peer_released` slot.
- **Threading.** The AppKit main thread is the FX thread, and it runs nearly every export and every slot. The
  exceptions:
  - the render thread runs `MacView._begin`, `_end` and `_getNativeFrameBuffer`, and an autorelease pool spans
    `_begin` to `_end`;
  - the launcher thread runs `_initIDs`, `_initDelegate` and `_runLoop`;
  - any thread may call `_invokeAndWait`, `_submitForLaterInvocation`, `_openURI`, `_getKeyCodeForChar` and the
    `FormatEncoder` natives;
  - the "Glass Timer Thread" pthread and the CVDisplayLink thread run the timer ticks;
  - `peer_released` runs from `dealloc` on whichever thread drops the last reference.

  A C flag, set at `GlassApplication.m:562` and cleared at `:758`, replaces `jEnv != NULL`. `critical(true)` is
  allowed only on the ABI-version, `sizeof` and flag probes.
- **AC 9 plan.** Add `native-glass/mac/constants/`: 15 hand-kept headers with `javac -h` names that hold the 210
  live constants. `MacConstantsHeaderTest` checks them (the GTK precedent), and they go on `glass`'s
  `INCLUDE_DIRS`. Add a `JNI` opt-in to mac.cmake's `add_jfx_library`: `glass` and `prismMTL` keep it during the
  transition, and `iio` and `prismES2` lose the JDK directories at once. The 14 prototype-only header imports leave
  with their JNI bodies. The constants must land first, because `MacGestureSupport.h` and `MacPasteboard.h` stop
  being generated when those classes lose their last native.
- **Top risks.**
  1. `GlassThreadDataKey` must exist before any export that enters an autorelease pool, through `gmac_init`, which
     shares one `pthread_once` with `JNI_OnLoad`.
  2. The JNI bootstrap (`jApplicationClass`, `glassClassLoader`, the main-thread attach) serves all 155 exports, so
     it stays until the last slice. `FindClass` reached inside an FFM downcall resolves against the wrong loader.
  3. No `critical(true)` on a blocking or re-entrant downcall: the run loop, nested loops, dialogs, menus,
     `invokeAndWait`, the pixel uploads.
  4. Exception timing changes at the unchecked upcall sites and at the throw-and-continue main-thread assert.
  5. Registry lifetime: register an id before the create downcall, remove it only at dealloc.
  6. `NewStringUTF`'s modified-UTF-8 decoding must be reproduced with `JniStringCodec.fromNewStringUtf`.
  7. MacTimer's thread identity (names, attach and detach, idle ticks) has unknown parity.
- **Conflicts (§R3).** The auditors proposed incompatible versions of six shared ABI pieces:
  - the export macro and ABI-version type;
  - `GmacScreenInfo` (88 or 56 bytes);
  - the App table (21 or 10 slots);
  - the preferences block;
  - the attached-flag probe;
  - the constants approach.

  §R3 gives a resolution for each, following the GTK and Windows precedents. The first slice settles them.
- **Gaps (§R4).**
  - No `JNIEXPORT` is unclaimed, and every live `Call*Method` is claimed.
  - The only unclaimed JNI text is the commented-out `Sys_out_sprintf` (`GlassWindow.m:55-76`), which the AC 1
    grep will still hit.
  - Double claims:
    - `MacGestureSupport._initIDs` (g1 and g2);
    - the 21 `GlassApplication.m`/`GlassKey.m` exports and 35 upcall lines (g0 and g1);
    - the 20 device-details `Map` reads (g0 and g2).

## Contents

- [Group g0: shared infrastructure](#group-g0-shared-infrastructure)
- [Group g1: Application, Timer, Robot, Cursor, Pixels, GestureSupport](#group-g1-application-timer-robot-cursor-pixels-gesturesupport)
- [Group g2: Window, View (+ GestureSupport)](#group-g2-window-view--gesturesupport)
- [Group g3: pasteboard, clipboard, menus, dialogs, MacFileNSURL](#group-g3-pasteboard-clipboard-menus-dialogs-macfilensurl)
- [Group g4: accessibility](#group-g4-accessibility)
- [Reconciliation](#reconciliation)
- [Proposed slice order](#proposed-slice-order)
- [Owner-run items](#owner-run-items)

The group sections keep each auditor's tables, counts, verdicts, evidence and proposed C ABI as written. Each
starts with a short list of the scribe's corrections, which link to §R2. Where the groups disagree, §R3 governs.

## Group g0: shared infrastructure

**Scribe's corrections** (details in §R2):

- §3 row `staticScreen_getVideoRefreshPeriod` says "any thread". It is the FX thread: `Screen.getVideoRefreshPeriod`
  calls `Application.checkEventThread()` first (`Screen.java:50-52`). g1 has it right.
- §3 row `_getKeyCodeForChar` says "FX/main". Nothing checks the thread (`Application.getKeyCodeForChar`,
  `Application.java:745-746`); in practice it is the FX thread. g1 has it right.
- §2 `GetPrimitiveArrayCritical` is 9 calls, not 11: two hits are comments (`GlassAccessible.m:235,314`).
  `NewStringUTF` is 26 live calls, not 27: `GlassWindow.m:72` is commented out.
- §2 and §11: there are 210 live constants, not 215, and `KeyEvent.h` supplies 133, not 138. Five `KeyEvent` names
  appear only in `//` lines (`GlassKey.m:156-167`).
- §2: the 2,122 JNI type tokens were not reproduced. The figure depends on the regex: §R1 gets 2,128 including
  `JNIEXPORT`/`JNICALL`/`JNI_*`.
- "There is no built binary": the baseline build of `1a6788cc4d` has one (the header). §4's owner item (`nm -u -m`,
  `otool -L`) is done in §R1.
- Where this ABI differs from g1's (`GmacScreenInfo`, `GmacAppCallbacks`, preferences, error codes, init), §R3
  decides.
- §7 and its rules name `gmac_main_java_attached()`. §R3 item 3 replaces it with `gmac_main_thread_detached()`, so
  `pthread_main_np` stays in C (§10).

All paths are relative to `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/`, and `mac/`
is short for `src/main/native-glass/mac/`. The tree is `1a6788cc4d`, clean. `native-glass/mac` has not changed since
the fork point `868c4801ec`: 100 files, 19,728 lines, which matches the story.

**There is no built binary.** `modules/javafx.graphics/target` does not exist, so `nm`/`otool` could not be run, and
the generated headers under `target/gensrc/headers` were not available either. All import evidence below comes from
reading the sources and `native/mac.cmake`. Constant values come from the Java sources.

### 1. Verdict

**Migrate this part; do not delete it.** Most of the shared code is JNI glue, and the migration removes it outright:
- `jVM`/`jEnv`
- 80 cached class/method/field IDs in `GlassStatics`, 16 more in `GlassHelper`
- the `GLASS_CHECK_EXCEPTION*`, `GET_MAIN_JENV*` and `GLASS_ASSERT_MAIN_JAVA_THREAD` macros
- `ClassForName`
- the `java.util.Map` building in `PlatformSupport`
- `JNI_OnLoad`

What is left is Objective-C or AppKit work, which the story requires to stay in C.

Of the 35 functions triaged that do real work:
- **30 are OS-CALL.** 4 of these are dead code.
- **3 are WRAPPER:**
  - `getTimeMicroseconds` (only C calls it)
  - `staticScreen_getVideoRefreshPeriod` over CoreVideo's `CVDisplayLinkGetActualOutputVideoRefreshPeriod`, which
    can go only once Java owns the display link (a MacTimer decision)
  - `pthread_main_np` inside `GLASS_ASSERT_MAIN_JAVA_THREAD` (§R3 item 3 keeps it in C, inside
    `gmac_main_thread_detached`; §10)
- **2 are PURE.** Both have only native callers, so they stay.
- **0 are PURE-HOT.**

Parity outcomes:
- `PARITY: exact` for the two deletable WRAPPERs, and for one optional PURE arithmetic fragment in the screen code.
- `PARITY: unprovable` for replacing `performSelectorOnMainThread` with a `CFRunLoopSource`, and for
  `object_getClassName` (a KVO isa-swizzle makes it differ).
- `PARITY: unknown` for `_isKeyLocked` through `CGEventSourceFlagsState`, `sysdir_*`, the `sysctl` OS-version check,
  and the JNI calls that run while an exception is pending.

This infrastructure has to be built first (the ABI, the callback table, `gmac_init`) and deleted last (it serves every
group).

### 2. Summary counts

| Item | Whole `native-glass/mac` | Shared-infra scope | How counted |
|---|---:|---:|---|
| Java `native` declarations (`com.sun.glass.ui.mac`) | 155 in 15 classes | 2 in `GlassKey.m` + 19 `MacApplication` in `GlassApplication.m` (11 of them threading/infra) | `grep -nE '^\s*[a-z@A-Z ]*\bnative\b' src/main/java/com/sun/glass/ui/mac/*.java` |
| `JNIEXPORT` | 155 in 15 files | 2 (`GlassKey.m`); `GlassApplication.m` 19 | `grep -c JNIEXPORT mac/*.m mac/a11y/*.m` |
| `JNI_OnLoad` | 1 (`GlassApplication.m:86/88`, `JNI_OnLoad_glass` under `STATIC_BUILD`, never built) | 1 | `grep -rn JNI_OnLoad` |
| `Call*Method` lines | 127 in 20 files | 55 (23 become callback-slot dials, 32 go away) | `grep -rnE '(->\|\.)Call(Static\|Nonvirtual)?[A-Za-z]+Method[AV]?\b'` |
| `NewObject` / `GetStaticObjectField` | 16 / – | 6 / 1 (all go away) | `grep -rnE '\->NewObject(A\|V)?\('` |
| `NewGlobalRef` / `DeleteGlobalRef` lines | 37 / 15 | 14 / 4 (20 refs counting the 6 `INIT_CLASS` expansions); 14 live for the whole process and are never freed | per-file grep |
| `NewWeakGlobalRef` | 0 | 0 | |
| `Get*ArrayElements` / `GetPrimitiveArrayCritical` / `GetDirectBufferAddress` / `NewDirectByteBuffer` | 4 / 11 / 3 / 0 | 0 / 0 / 0 / 0 | |
| `GetStringChars` / `GetStringCritical` / `NewStringUTF` / `NewString` | 6 / 1 / 27 / 3 | 1 (`GlassHelper.m:341`) / 1 (`GlassAccessible.m:266`, shared helper) / 17 / 0 | |
| `ThrowNew` | 2 | 2: `GlassMacros.h:187` (inside `GLASS_ASSERT_MAIN_JAVA_THREAD`, 102 use sites in 10 files) and `GlassApplication.m:956` | |
| `ExceptionCheck` / `ExceptionDescribe` / `ExceptionOccurred` | 109 / 4 / 2 | 17 / 4 (`GlassApplication.m:235,242,249,844`) / 2 (the macros) | |
| `AttachCurrentThread[AsDaemon]` / `DetachCurrentThread` / `GetJavaVM` / `GetEnv` | 3 / 2 / 0 / 0 | `GlassApplication.m:562` (main thread); `GlassTimer.m:54,131` / `:101,144` (timer threads) | |
| Cached ID globals | `GlassStatics.h`: 13 `jclass`, 39 `jmethodID`, 2 `jfieldID`, `javaIDs` 26 = 80; `GlassHelper.m` 16 statics | all of them | `grep -c '^extern jclass'` etc. |
| Macro uses outside `GlassMacros.h` | `GET_MAIN_JENV` 84 (12 files), `_NOWARN` 6 (5), `GLASS_CHECK_EXCEPTION` 274 tokens (23), `_RETURN` 3, `_NONNULL_` 7, `GLASS_ASSERT_MAIN_JAVA_THREAD` 102 (10), `GLASS_POOL_ENTER`/`EXIT` 97/97 (10), `PUSH`/`POP` 1/1 (`GlassView.m:470,496`), `MAIN_JVM` 9 | | `grep -oE '\b<MACRO>\b' \| wc -l` |
| `javac -h` headers included | 29 distinct / 60 `#import` lines in 23 files; 15 of them supply **215** constants, 14 supply prototypes only | | §11 |
| `<jni.h>` includers / JNI type tokens | 20 files (19 headers + `GlassStatics.m`) / 2,122 tokens (`jint`, …, `jlong_to_ptr`); `jlong_to_ptr`/`ptr_to_jlong` 85 uses in 16 files | | regex in §11 |
| SIMD / intrinsics | 0 | 0 | `grep -rnE 'emmintrin\|arm_neon\|__asm'` |

### 3. Java classes and C files

**Natives whose C is shared infrastructure or the threading backbone.** The other 8 `MacApplication` natives are
listed by name only: `_isTriggerReactivation` (`GlassApplication.m:1233`), `_hide` (:1245), `_hideOtherApplications`
(:1256), `_unhideAllApplications` (:1267), `_getDataDirectory` (:1278), `_getMacKey` (:1302),
`_getApplicationClassName` (:1315), `_openURI` (:1340).

| Java (`MacApplication.java:line`) | Signature | C (`file:line`) | Thread called on |
|---|---|---|---|
| :45 | `private static native void _initIDs(boolean disableSyncRendering)` (in `<clinit>` :46-51) | `GlassApplication.m:928` | the thread that first touches `MacApplication` (the launcher thread) |
| :110 | `native long _initDelegate(ClassLoader, Runnable launchable, boolean isTaskbarApplication)` | :1009 | launcher thread |
| :112 | `native void _runLoop(long delegate)` | :1027 | launcher thread (the main thread under `-XstartOnFirstThread`) |
| :164 | `native private void _finishTerminating(long)` | :1071 | FX thread (= AppKit main) |
| :198 | `native private Object _enterNestedEventLoopImpl()` | :1112 | FX/main |
| :212 | `native private void _leaveNestedEventLoopImpl(Object)` | :1134 | FX/main |
| :331 | `@Override native protected double staticScreen_getVideoRefreshPeriod()` | :1189 | any thread |
| :332 | `@Override native protected Screen[] staticScreen_getScreens()` | :1211 | FX/main (`Screen.initScreens`, `Application.java:151`) |
| :372 | `@Override native protected void _invokeAndWait(Runnable)` | :1170 | any thread except the FX thread (`Application.invokeAndWait` runs inline on the FX thread) |
| :374 | `private native void _submitForLaterInvocation(Runnable)` | :1152 | the `InvokeLaterDispatcher` thread, or the caller when `javafx.embed.isEventThread` is set |
| :430 | `@Override protected native int _getKeyCodeForChar(char, int hint)` | `GlassKey.m:631` | FX/main |
| :433 | `@Override protected native int _isKeyLocked(int)` | `GlassKey.m:644` | FX/main |
| :437 | `private native Map<String,Object> _getPlatformPreferences(long)` | :1327 | FX/main |

The other 14 classes (`MacWindow` 30, `MacView` 20, `MacAccessible` 14, `MacPasteboard` 13, `MacRobot` 12,
`MacMenuDelegate` 11, `MacTimer` 8, `MacFileNSURL` 6, `MacCursor` 6, `MacCommonDialogs` 4, `MacSystemClipboard` 3,
`MacPixels` 3, `MacMenuBarDelegate` 3, `MacGestureSupport` 1) belong to the other groups. Each has a
`static { _initIDs(); }` and relies on `MacApplication.<clinit>` having loaded the library first. `MacView`,
`MacTimer` and `MacPixels` also read values from native in `<clinit>`.

**C files in scope**

| File | Lines | JNI in it | Role |
|---|---:|---|---|
| `common.h` | 30 | `#include <jni.h>`, `jlong_to_ptr`/`ptr_to_jlong` | included by 27 files |
| `GlassMacros.h`/`.m` | 265/67 | the exception, `JENV` and assert macros; `MAIN_JVM` | autorelease pools (thread-specific data), `GlassLog` |
| `GlassStatics.h`/`.m` | 157/140 | `jVM`, `jEnv`, 80 ID globals, `initJavaIDs{List,ArrayList,File}` (:105-139, used by `GlassDialogs`/`GlassTimer`) | `GlassThreadDataKey`, `GlassDisplayLink` |
| `GlassHelper.h`/`.m` | 56/352 | `SetGlassClassLoader` :34, `ClassForName` :47, `ApplicationClass` + 12 notify-method getters :94-304, `nsStringWithJavaString` :336 | `InvokeSelectorIfAvailable` :308 |
| `ProcessInfo.h`/`.m` | 32/211 | none | `getTimeMicroseconds` used by `GlassTimer.m:60,73`; the rest is dead |
| `GlassKey.h`/`.m` | 40/660 | 2 `JNIEXPORT`, `GetJavaKeyChars(JNIEnv*,…)` :442 | key tables and the TIS layout queries |
| `GlassScreen.h`/`.m` | 41/240 | `createJavaScreen` :54, `createJavaScreens` :122, `GlassScreenDidChangeScreenParameters` :159 | `GetScreenScaleFactor` :45, `maxScreenDimensions` :43 (read by `GlassWindow+Java.m:363-368`) |
| `PlatformSupport.h`/`.m` | 53/376 | `initIDs` :74, `put*` :308-374, `updatePreferences` :273 | KVO, notifications, `nw_path_monitor` |
| `GlassApplication.m` (threading part) | 1,384 | `JNI_OnLoad` :85-96, `GlassRunnable` :100-163, `runLoop:` :556-784, nested loops :796-859 | AppKit delegate |
| `GlassViewMTL.m`, `GlassView3D.m`, `GlassViewCGL.m` | 157/782/238 | reads from the `Map` of device details (coupling, §6.3) | view and layer creation |
| `GlassLayer*`, `GlassMTLOffscreen`, `GlassMTLFrameBufferObject`, `GlassCGL*`, `GlassOffscreen` | ≈1,800 | only `- (jlong)fbo` (`GlassOffscreen.h:39`, `GlassMTLOffscreen.m:88`, `GlassCGLOffscreen.m:97`) and `jlong_to_ptr` (`GlassLayerMTL.m:55`) | CAMetalLayer / CAOpenGLLayer subclasses |

### 4. External dependencies

- **Link libraries** (`native/mac.cmake:110-119`, `glass` block at :159-167): `-framework AppKit ApplicationServices
  Carbon OpenGL QuartzCore Security Network Metal` plus `objc`. Compile options: `-std=c99` (C/ObjC), `-O3 -DNDEBUG`,
  `-DGL_SILENCE_DEPRECATION -DMACOS_MIN_VERSION_MAJOR/MINOR -Werror`. Because of `-DNDEBUG`, **every
  `assert(pthread_main_np()==1)` is compiled out of release builds.**
- **C-callable OS symbols reached from in-scope sources** (from the sources, not from `nm`):
  - libSystem: `pthread_main_np`, `pthread_key_create`, `pthread_[gs]etspecific`, `pthread_mutex_*`, `gettimeofday`,
    `task_info`, `_dyld_image_count/_get_image_name`, `backtrace*`, `popen`,
    `dispatch_async/get_main_queue/semaphore_*/time/retain/release`
  - CoreGraphics: `CGDisplayScreenSize`, `CGDisplayBounds`, `CGGetActiveDisplayList`, `CGDisplayHide/ShowCursor`,
    `CGColorSpaceCreateWithName`
  - CoreVideo: `CVDisplayLinkGetActualOutputVideoRefreshPeriod`
  - HIToolbox/Carbon: `TISCopyCurrentKeyboardLayoutInputSource`, `TISGetInputSourceProperty`, `UCKeyTranslate`,
    `LMGetKbdType`, `GetCurrentProcess`, `TransformProcessType`
  - CoreFoundation: `CFDataGetBytePtr`, `CFRelease`, `CFNotificationCenterGetDistributedCenter/AddObserver`
  - Network: `nw_path_monitor_create/set_update_handler/set_queue/start/cancel`, `nw_path_is_constrained/expensive`.
    The update handler takes a block, so this cannot be expressed through FFM.
  - AppKit/Foundation C functions: `NSBitsPerPixelFromDepth`, `NSSearchPathForDirectoriesInDomains`,
    `NSStringFromClass`, the `NSAppKitVersionNumber` data symbol
  - Metal: `MTLCreateSystemDefaultDevice`
- Everything else is Objective-C message sends. `Security` is imported (`GlassApplication.m:40`) but no `Sec*` call
  exists.
- **No symbol dependency between Glass and Prism:** neither `native-glass/mac` nor `native-prism-mtl` includes the
  other's headers (grep). The two are coupled only through pointers carried in Java `long`s (§6.3).
- **Owner/baseline item:** capture `nm -u -m target/native/bin/libglass.dylib` and `otool -L` from the JNI build.
  That turns this section from reading into evidence.

### 5. Triage

These are the functions that do real work. JNI glue is listed after the table.

| # | Function (`file:line`) | Verdict | Evidence | Parity gate | Replaced by |
|---|---|---|---|---|---|
| 1 | `GLASS_POOL_ENTER/EXIT/PUSH/POP` (`GlassMacros.h:192-234`) | OS-CALL | `NSAutoreleasePool` (ObjC), `pthread_getspecific/setspecific`, `calloc` | – | stays; it needs `GlassThreadDataKey` from `gmac_init` |
| 2 | `GlassLog`/`GLASS_LOG` (:73-150) | OS-CALL, debug only | `NSLog`, `pthread_mutex_lock`, `vsnprintf` | – | stays (internal) |
| 3 | `GLASS_CALLSTACK` (:236-247) | OS-CALL | `backtrace`, `backtrace_symbols` | – | stays; reused for the "Java detached" warning |
| 4 | `InvokeSelectorIfAvailable` (`GlassHelper.m:308`) | OS-CALL | `NSInvocation` (ObjC); called only from C (`GlassApplication.m:712`, `GlassCursor.m:59`) | – | stays |
| 5 | `getTimeMicroseconds` (`ProcessInfo.m:185`) | WRAPPER (internal) | `gettimeofday`; only caller is the C timer thread loop | – | stays while `GlassTimer`'s loop is C |
| 6 | `printLoadedLibraries` (`ProcessInfo.m:69`) | OS-CALL, **dead** | `_dyld_*`; its only caller is under `VERBOSE_LOAD` (`GlassApplication.m:729-745`, never defined) | – | delete |
| 7 | `printLoadedFiles` (:141) + `_runCommand`/`_getCommandPath`/… | OS-CALL, **dead** | `popen` of `lsof`; same reason | – | delete |
| 8 | `getRam` (:197) | OS-CALL, **dead** | `task_info`; no caller (grep) | – | delete |
| 9 | `gKeyMap`, `macKeyCodeIsLayoutSensitive`, `getJavaCodeForASCII`, `isLetterOrDigit`, `GetJavaMouseModifiers` (`GlassKey.m:45-305,393`) | PURE | none, tables and switches only | n/a: no Java boundary; called by the C event path | stays |
| 10 | `queryKeyboard`, `getJavaCodeForMacKey[AndModifiers]` (:263-363) | OS-CALL | `TISCopyCurrentKeyboardLayoutInputSource`, `TISGetInputSourceProperty`, `UCKeyTranslate`, `LMGetKbdType` | – | stays (internal) |
| 11 | `GetJavaKeyModifiers`/`GetJavaModifiers` (:365,414) | OS-CALL | `[NSEvent modifierFlags]`, `[NSEvent pressedMouseButtons]` | – | stays |
| 12 | `GetJavaKeyChars` (:442) | OS-CALL (+ JNI) | `[event characters]`, `getCharacters:range:` | – | `uint16_t` out-buffer helper for the view's key slot |
| 13 | `GetMacKey`, `GetJavaKeyCodeFor` (:474,419) | OS-CALL | table plus #10 | – | internal; `gmac_key_java_to_mac` |
| 14 | `GetStringForJavaKey` (:516) | OS-CALL | builds an `NSString`; called by `GlassMenu.m` | – | stays |
| 15 | `GetStringForMacKey` (:605) | OS-CALL | TIS | – | stays |
| 16 | `_getKeyCodeForChar` (:631) | OS-CALL | `keyCodeForCharMap` (an `NSMutableDictionary` filled by `registerKeyEvent`, `GlassApplication.m:868`) + TIS | – | `gmac_key_code_for_char` |
| 17 | `_isKeyLocked` (:644) | OS-CALL | `[NSEvent modifierFlags]` (class message) | a WRAPPER over `CGEventSourceFlagsState` is possible but `PARITY: unknown` (§10) | **Superseded by g1 D7 (g1 §9):** Java's switch plus `gmac_current_modifier_flags`. Proposed here: `gmac_key_is_locked` |
| 18 | `GetScreenScaleFactor` (`GlassScreen.m:45`) | OS-CALL | `backingScaleFactor` | – | stays |
| 19 | `createJavaScreen` (:54) | OS-CALL + PURE fragment | `NSScreen deviceDescription/frame/visibleFrame/depth`, `NSBitsPerPixelFromDepth`, `CGDisplayScreenSize`, `CGDisplayBounds`, `retain` | the arithmetic fragment (:66-73, 95-114) is `PARITY: exact` (by reading, §9) | `gmac_screen_fill(NSScreen*, GmacScreenInfo*)`; Java builds `Screen` |
| 20 | `createJavaScreens` (:122) | OS-CALL | `[NSScreen screens]`; side effect on `maxScreenDimensions` | – | `gmac_screens_query` |
| 21 | `NSScreen(FullscreenAdditions)` (:171-237) | OS-CALL, **dead** | `CGGetActiveDisplayList`, `CGDisplayHideCursor`, …; no caller (grep for both selectors) | – | delete |
| 22 | `PlatformSupport initWithEnv:`, `stopEventProcessing`, `observeValueForKeyPath:` (:95-151) | OS-CALL | KVO, `NSNotificationCenter`, `nw_path_monitor_*` with a block | – | stays, minus `env`/`application` |
| 23 | `collectPreferences` + `queryNSColors` (:153-267) | OS-CALL | 44 `NSColor`s + `alternatingContentBackgroundColors`, `NSAppearance`, `NSWorkspace`, `NSScroller` | – | fills a `GmacPreferences` struct; Java builds the `HashMap` |
| 24 | `JNI_OnLoad` (`GlassApplication.m:85-96`) | OS-CALL remnant | `pthread_key_create` | – | `gmac_init()` |
| 25 | `GlassRunnable` (:100-163) | OS-CALL | `performSelectorOnMainThread:withObject:waitUntilDone:modes:`, `NSMutableArray` | – | holds an `int64_t` id instead of a `jobject` |
| 26 | `runLoop:` + `NSApplicationDelegate` methods (:219-784) | OS-CALL | `[NSApp run]`, `TransformProcessType`, `setDelegate:`, `activateWithOptions:`, … | – | `gmac_app_run_loop`; upcalls become slots |
| 27 | `enterNestedEventLoopWithEnv` (:796) | OS-CALL | `NSRunLoop runMode:beforeDate:`, `nextEventMatchingMask:`, `sendEvent:` | – | `gmac_app_enter_nested_loop`; the return value moves to Java |
| 28 | `leaveNestedEventLoopWithEnv` (:826) | PURE | none (sets a static flag) | n/a: the flag belongs to #27's loop | `gmac_app_leave_nested_loop` (one line) |
| 29 | `registerKeyEvent`/`getKeyCodeForChar`/`inputDidChangeCallback` (:861-909) | OS-CALL | `CFNotificationCenterAddObserver`, `NSMutableDictionary`, TIS | – | stays (internal) |
| 30 | `_submitForLaterInvocation`/`_invokeAndWait` (:1152,1170) | OS-CALL | `performSelectorOnMainThread` in 4 modes, including `AWTRunLoopMode` | a `CFRunLoopSource` (C-callable) rewrite would be `PARITY: unprovable` (§10) | `gmac_app_invoke_later/_and_wait` |
| 31 | `staticScreen_getVideoRefreshPeriod` (:1189) | **WRAPPER** | `CVDisplayLinkGetActualOutputVideoRefreshPeriod` on `GlassDisplayLink` (created in `GlassTimer.m:444-469`) | `PARITY: exact` | Java binds CoreVideo once Java owns the `CVDisplayLinkRef` (MacTimer group); until then a 6-line export |
| 32 | `_initIDs` OS check (:934-959) | OS-CALL | `[[NSProcessInfo processInfo] operatingSystemVersion]` | a `sysctl` alternative is `PARITY: unknown` | `gmac_app_init` returns a status; Java throws `RuntimeException("Unsupported macOS version")` |
| 33 | `GlassView3D initWithFrame` (:82-168), `GlassViewMTL/CGL _initialize3dWithJproperties` (:39 / :90) | OS-CALL | `NSView`, `CALayer`, `[NSOpenGLContext CGLContextObj]`, CGL | – | take `const GmacViewCaps*` (§6.3) |
| 34 | `GlassLayer`, `GlassLayerMTL`, `GlassMTLOffscreen`, `GlassMTLFrameBufferObject`, `GlassLayerCGL`, `GlassCGLOffscreen`, `GlassCGLFrameBufferObject`, `GlassOffscreen` | OS-CALL | `CAMetalLayer`/`CAOpenGLLayer` subclasses, `MTLCreateSystemDefaultDevice`, blit encoders, GL C API | – | stay; `jlong` becomes `int64_t` |
| 35 | `pthread_main_np()` in `GLASS_ASSERT_MAIN_JAVA_THREAD` (`GlassMacros.h:183`) | **WRAPPER** | libSystem `pthread_main_np` | condition `PARITY: exact`; the throw-vs-report outcome per site is `PARITY: unknown` (§10) | **Superseded by §R3 item 3:** stays in C inside `gmac_main_thread_detached()` (§10). Proposed here: Java binds `pthread_main_np` and reads `gmac_main_java_attached()` |

Totals: **OS-CALL 30** (4 dead), **WRAPPER 3**, **PURE 2**, **PURE-HOT 0**.

**JNI glue the migration deletes (not triaged):**
- `common.h:26-29`
- `GlassMacros.h`: `MAIN_JVM` :47, the `GLASS_CHECK_EXCEPTION*` family :153-179, `GLASS_ASSERT_MAIN_JAVA_THREAD`
  :182-189, `GET_MAIN_JENV*` :252-263
- `GlassStatics.*`: everything except `GlassThreadDataKey` and `GlassDisplayLink`
- `GlassHelper.m`: :33-304 and :336-350. `nsStringWithJavaString` becomes a UTF-16 helper.
- `PlatformSupport`: `initIDs` and `put*`
- `GlassScreenDidChangeScreenParameters` (becomes a slot dial)
- `enter/leaveFullScreenExitingLoop` (:834-859, become slot dials)
- `_initIDs` ID caching (:963-1001)
- `GlassApplication.h:51-54` (`jApplication`, `jLaunchable`, `jTaskBarApp`; `jshareContextPtr` is unused)

### 6. Upcalls and threading

#### 6.1 Upcall table, in scope

The "exception today" column uses three modes:
- **REPORT**: `GLASS_CHECK_EXCEPTION` calls `Application.reportException(t)`; a second throw from it is swallowed.
- **DESCRIBE**: `ExceptionDescribe` + `ExceptionClear`, which prints and does not report.
- **NONE**: the exception is left pending.

| C site | Java target | Thread | How it gets to that thread today | Exception today | Becomes |
|---|---|---|---|---|---|
| `GlassApplication.m:129` | `Runnable.run()` (GlassRunnable) | AppKit main | `performSelectorOnMainThread:@selector(maybeRun\|run)` in Default, ModalPanel, EventTracking and `AWTRunLoopMode` (:620-625, 158, 1161, 1179); deferred while `isFullScreenExitingLoop` (:142-162) | REPORT :130 | `run_runnable(id)` |
| :246 | launchable `Runnable.run()` | main, in `applicationWillFinishLaunching:`; embedded mode calls it directly or via `performSelectorOnMainThread` (:762-770) | – | DESCRIBE :247-251, **and on a throw the screen-parameters observer (:254-257) is never registered** | `run_launchable()` |
| :287, :304, :326, :339, :359, :372, :391, :404, :417, :430, :536 | `Application.notify{Will,Did}FinishLaunching / {Will,Did}BecomeActive / {Will,Did}ResignActive / {Will,Did}Hide / {Will,Did}Unhide / WillQuit()` | main (`NSApplicationDelegate`) | – | REPORT, after `[pool drain]` | 11 slots |
| :461 | `notifyOpenFiles(String[])` | main | – | REPORT :468; the C replies `Success`, or `Failure` if `NewObjectArray` failed | `notify_open_files` |
| :589 | `MacApplication.setEventThread()` | main, the first Java call after the attach at :562 | – | REPORT :592 | `set_event_thread` |
| :608 | `MacApplication.setName(String)` | main | – | REPORT :610 | `set_name` |
| :755 | `notifyApplicationDidTerminate()` | main, after `[NSApp run]` returns; `jEnv = NULL` at :758 | – | REPORT :756 | `notify_did_terminate` |
| :841 | `Application.enterNestedEventLoop()` (static) | main (fullscreen-exit from `GlassWindow`) | – | DESCRIBE :843-846 | `enter_nested_event_loop` |
| :857 | `Application.leaveNestedEventLoop(null)` (static) | main | – | **NONE** | `leave_nested_event_loop` |
| `GlassScreen.m:167` | `Screen.notifySettingsChanged()` (static) | main (`NSApplicationDidChangeScreenParametersNotification`, guarded by `env != NULL` at `GlassApplication.m:212-216`) | – | REPORT :168 | `notify_screen_settings_changed` |
| `PlatformSupport.m:295` | `MacApplication.notifyPreferencesChanged(Map)` | main: KVO on `NSApp.effectiveAppearance`, `NSNotificationCenter`, `NSWorkspace` center, and `nw_path_monitor` on `dispatch_get_main_queue()` (:136) | – | REPORT :299 | `notify_preferences_changed(prefs*)` |
| `GlassMacros.h:158,170` | `Application.reportException(Throwable)` | any thread | – | the second `ExceptionClear` swallows | absorbed into the Java upcall wrappers |
| `GlassTimer.m:67` / `:136` | `Runnable.run()` (timer) | "Glass Timer Thread" pthread (`SCHED_FIFO`) / CoreVideo display-link thread, attached with the name `"PulseTimer-CVDisplayLink thread"` (:112) | – | REPORT :70 / **NONE** | timer table (MacTimer group) |
| Marshalling only, these go away | `Class.forName` `GlassHelper.m:86`; `Screen.<init>` `GlassScreen.m:90`; `HashMap.<init>`, `Object.equals`, `Collections.unmodifiableMap`, `Map.put`×4, `Color.rgb`×2, `Boolean.TRUE/FALSE` (`PlatformSupport.m:154-372`); `Map.get`, `Long.longValue`, `Integer.<init>`, `Integer.intValue`, `Boolean.booleanValue` (`GlassView3D.m:91-130`, `GlassViewMTL.m:49-88`, `GlassViewCGL.m:98-168`); `Boolean.getBoolean` (`GlassApplication.m:737`, dead) | main | – | REPORT | Java reads or builds the value itself |

#### 6.2 Threading model, as it is today

1. **Startup.** The launcher thread runs `MacApplication.runLoop` (`MacApplication.java:115-139`): it starts a
   keep-alive thread, calls `_initDelegate`, then `_runLoop`.
   - `_runLoop` performs `runLoop:` on the main thread with `waitUntilDone:NO` and **spins with `usleep(10000)`**
     until `started == YES` (`GlassApplication.m:1047-1058`). `started` is set in `applicationWillFinishLaunching:`
     (:228).
   - Under `-XstartOnFirstThread`, `runLoop:` runs inline and never returns.
2. **The main thread joins the JVM.** `runLoop:` attaches it (`AttachCurrentThreadAsDaemon(jVM,&jEnv,NULL)`, :562),
   caches the env in the global `jEnv`, makes it the FX event thread (`setEventThread`), and enters `[NSApp run]`.
   - Embedded mode (AWT/SWT owns `NSApp`) skips `[NSApp run]` and **never clears `jEnv`**.
3. **`jEnv` is the only notion of "Java is there".**
   - `GET_MAIN_JENV` (84 sites) asserts main (debug builds only), prints a call stack if `jEnv == NULL`, then **uses
     the NULL env**.
   - `_NOWARN` (6 sites) and the direct reads (`GlassApplication.m:126,212`, `GlassWindow+Overrides.m:48,148`) guard
     with `env != NULL`.
   - `GLASS_ASSERT_MAIN_JAVA_THREAD` throws `RuntimeException("Main Java thread is detached.")` when
     `!pthread_main_np() && jEnv == NULL`, **and does not return**.
4. **invokeLater:** `InvokeLaterDispatcher` thread → `_submitForLaterInvocation` → `NewGlobalRef` → `maybeRun` on
   main (async). **invokeAndWait** waits with `waitUntilDone:YES` and is never deferred. Both silently drop the
   runnable when `jEnv == NULL` (:1158, :1176).
5. **Nested loops:** these are FX-thread only. The C spins `runMode:NSDefaultRunLoopMode beforeDate:+10ms` plus
   `nextEventMatchingMask:` until `leave` clears the flag. One static global-ref slot carries the return value
   (:815-831).
6. **Render thread.** MacView's `_begin`/`_end`/`_getNativeFrameBuffer`/`_uploadPixels*` run on the QuantumRenderer
   thread. They:
   - use the thread-specific pools, including one `GLASS_POOL_PUSH` in `_begin` popped in `_end`
     (`GlassView.m:470,496`), which keeps a pool open across two calls;
   - push `setNeedsDisplay` to main with `performSelectorOnMainThread` (`GlassMTLOffscreen.m:124-134`,
     `GlassCGLOffscreen.m:138-146`).
7. **Other foreign threads:** the timer pthread, the display-link thread, and Metal completion handlers
   (`GlassLayerMTL.m:140`, no Java involved).

**Under FFM:**
- Upcall stubs attach a native thread on its first upcall as a daemon with a default name. For the main thread that
  first upcall is `set_event_thread`, at the same moment the JNI attached. The thread is never detached until it
  exits; JNI never detached the main thread either.
- Differences:
  - The timer threads lose their JNI attach names. That is visible in thread dumps and uncaught-exception output.
  - Local references the JNI created on the attached main thread outside any native frame (`PlatformSupport put*`
    never calls `DeleteLocalRef`) leaked for the life of the process. Upcall frames free them. That is a fix by
    construction; it changes no output. This is by reading of the JNI spec.
- The "attached" state must become a C flag `g_mainJavaAttached`, set and cleared at exactly :562 and :758, so the
  dropping and throwing behaviour above stays the same.

#### 6.3 Glass↔Prism Metal coupling, and the CGL equivalent

| Value | Producer | Glass consumer | Type / ownership |
|---|---|---|---|
| `"mtlCommandQueue"` | `MTLPipeline.getResourceFactory` puts `MTLContext.getMetalCommandQueue()` (`MTLPipeline.java:96-98`, `MetalContext.m:1492-1496`) into the pipeline's device map. `QuantumRenderer.java:93-103` hands that **same `HashMap` object** to `Application.setDeviceDetails` | `GlassView3D.m:87-107` (MTL vs CGL decision), `GlassViewMTL.m:64-81`, then `GlassLayer.m:46-54`, then `GlassLayerMTL.m:55` `_blitCommandQueue` | `id<MTLCommandQueue>` as a `Long`. **Borrowed, not retained**; owned by `MetalContext` (created at `MetalContext.m:71`) |
| `"isVsyncEnabled"` | `MTLPipeline.java:70` (`Long` 1/0) | `GlassViewMTL.m:83-91` (default YES), then `displaySyncEnabled` | `Long` |
| `"useMTLInGlassForSW"` | `SWPipeline.java:75-77` (`Boolean`) | `GlassView3D.m:125-132` | `Boolean` |
| `View.Capability.kHiDPIAwareKey` (Integer 5) | `QuantumRenderer.java:97` (`Boolean`) | `GlassView3D.m:116-124`, `GlassViewCGL.m:162-170` | `Boolean` |
| `k3dDepthKey` (Integer 4) | nobody puts it (grep) | `GlassViewMTL.m:49-57` (read but unused), `GlassViewCGL.m:98-106` | `Integer` |
| `"contextPtr"` / `"shareContextPtr"` | `MacGLFactory.java:68` / `:132` | `GlassViewCGL.m:132-145` / `:112-124`, via `[ctx CGLContextObj]` | `NSOpenGLContext*`, borrowed from prism_es2 |
| native frame buffer | `GlassView.m:326-345` returns `[[layer getPainterOffscreen] fbo]`: an `id<MTLTexture>` (`GlassMTLOffscreen.m:88-92`) or a **GL FBO name** (`GlassCGLOffscreen.m:97-100`) | Prism: `PresentableState.java:286`, then `MTLSwapChain.java:143`, then `nCreateRT2` (`MetalRTTexture.m:102-103`, `__bridge`, not retained) | Owned by `GlassMTLFrameBufferObject`; replaced on resize under an `NSLock` (`:37-77`); both libraries use the default device (`MTLCreateSystemDefaultDevice`, Glass `:60`, `GlassLayerMTL.m:47`; Prism `MetalContext.m:50`) |
| `disableSyncRendering` | `MacApplication` `<clinit>` → `_initIDs` | `GlassCGLFrameBufferObject.m:269` (`glFinish` unless disabled) | flag |

**FFM:** `MacView._create` reads these 7 entries in Java, on the same thread at the same moment as the C does today,
and passes a `GmacViewCaps`. Nothing crosses between the two libraries except opaque `void*`/`int64_t` values, held in
Java as `MemorySegment.ofAddress` in the facades.

#### 6.4 Library loading and static-init order (Java)

1. On the QuantumRenderer thread: `GraphicsPipeline.createPipeline()` tries `"mtl","es2","sw"`
   (`PrismSettings.java:208`). `MTLPipeline.<clinit>` calls `NativeLibLoader.loadLibrary("prism_mtl")`
   (`MTLPipeline.java:43-53`), then `nSupportsMTL`. Then `Application.setDeviceDetails`, before Glass starts
   (`QuantumToolkit.java:236`).
2. `Application.run` → `PlatformFactory` → `MacPlatformFactory.createApplication` (`MacPlatformFactory.java:35`) →
   `MacApplication.<clinit>`: `Application.loadNativeLibrary()` ("glass", `synchronized`, `loaded` flag,
   `Application.java:105-117`). That runs **`JNI_OnLoad`** (`pthread_key_create`), then `_initIDs`, which includes
   `[PlatformSupport initIDs:]` (`FindClass` of `javafx/scene/paint/Color`, among others).
3. No other mac class loads `glass` itself.

**FFM:**
- `MacGlassNative.<clinit>` must, in this order:
  1. `Application.loadNativeLibrary()` (idempotent; this is what makes `loaderLookup()` see the library, as
     `WinGlassNative.java:97-100` does);
  2. check `gmac_abi_version`;
  3. call `gmac_init()` (the former `JNI_OnLoad`) **before any function that enters `GLASS_POOL_ENTER`**;
  4. install the app table in `Arena.global()` before `gmac_app_create_delegate`.
- `glass` and `prism_mtl` share one class loader's lookup, so their prefixes must not collide: use `gmac_` and
  `mtl_`.

### 7. Proposed C ABI header (shared part)

```c
/* src/main/native-glass/mac/glass_mac_api.h (+ glass_mac_api.m); picked up by file(GLOB), no CMake edit */
#include <stdint.h>
#define GMAC_EXPORT __attribute__((visibility("default")))
#ifdef __cplusplus
extern "C" {
#endif
#define GLASS_MAC_ABI_VERSION 1
GMAC_EXPORT int32_t gmac_abi_version(void);

/* Former JNI_OnLoad (GlassApplication.m:85-96): pthread_once(pthread_key_create(&GlassThreadDataKey)).
 * MUST precede every gmac_* that runs GLASS_POOL_ENTER. Idempotent; safe beside a still-present JNI_OnLoad. */
GMAC_EXPORT int32_t gmac_init(void);
GMAC_EXPORT void    gmac_free(void* block);               /* every C-allocated out-block below */
GMAC_EXPORT int32_t gmac_main_java_attached(void);        /* the former `jEnv != NULL` (set :562, cleared :758) */

#define GMAC_OK                     0
#define GMAC_ERR_NO_DATA          (-1)  /* the JNI returned null */
#define GMAC_ERR_UNSUPPORTED_OS   (-2)  /* Java throws RuntimeException("Unsupported macOS version") */
#define GMAC_ERR_NOT_ATTACHED     (-3)  /* on main, jEnv==NULL: request silently dropped */
#define GMAC_ERR_DETACHED_OFF_MAIN (-4) /* GLASS_ASSERT_MAIN_JAVA_THREAD fired: Java throws or reports
                                           RuntimeException("Main Java thread is detached.") per site */
#define GMAC_UPCALL_OK    0
#define GMAC_UPCALL_THREW 1

/* ---- application / run loop (all called by MacApplication; thread in §3) ---- */
GMAC_EXPORT int32_t gmac_app_init(int32_t disable_sync_rendering);          /* _initIDs */
GMAC_EXPORT void*   gmac_app_create_delegate(int32_t is_taskbar_app,
                                             int32_t has_launchable);       /* _initDelegate; +1, released by finish */
GMAC_EXPORT void    gmac_app_run_loop(void* delegate);                      /* blocks; NEVER critical */
GMAC_EXPORT void    gmac_app_finish_terminating(void* delegate);
GMAC_EXPORT void    gmac_app_enter_nested_loop(void);                       /* returns after leave; NEVER critical */
GMAC_EXPORT void    gmac_app_leave_nested_loop(void);
GMAC_EXPORT int32_t gmac_app_invoke_later(int64_t runnable_id);             /* OK | NOT_ATTACHED | DETACHED_OFF_MAIN */
GMAC_EXPORT int32_t gmac_app_invoke_and_wait(int64_t runnable_id);          /* blocks until run; NEVER critical */
GMAC_EXPORT double  gmac_screen_video_refresh_period(void);                 /* only until Java owns the CVDisplayLink */

/* ---- screens ---- */
typedef struct GmacScreenInfo {      /* sizeof 88, align 8 - the 20 arguments of Screen.<init>, in order */
    void*   screen;                  /*  0: NSScreen*, retained by C per query (GlassScreen.m:86 leak kept) */
    int32_t depth;                   /*  8: NSBitsPerPixelFromDepth([screen depth]) */
    int32_t x, y, width, height;     /* 12..24: frame, y flipped against screens[0] */
    int32_t platform_x, platform_y, platform_width, platform_height;  /* 28..40: same values */
    int32_t visible_x, visible_y, visible_width, visible_height;      /* 44..56 */
    int32_t resolution_x, resolution_y;                               /* 60, 64 */
    float   platform_scale_x, platform_scale_y;                       /* 68, 72: 1.0f */
    float   output_scale_x, output_scale_y;                           /* 76, 80: (float)GetScreenScaleFactor */
} GmacScreenInfo;
GMAC_EXPORT int32_t gmac_sizeof_screen_info(void);
GMAC_EXPORT int32_t gmac_screens_query(GmacScreenInfo** out, int32_t* out_count); /* malloc'd; updates maxScreenDimensions */

/* ---- preferences ---- */
typedef struct GmacColor { int32_t r, g, b, reserved; double a; } GmacColor;   /* 24; r=(int)(c*255.0f) as in C today */
#define GMAC_PREFS_COLOR_COUNT 44  /* PlatformSupport.m:195-266 order: 0 labelColor .. 18 headerTextColor,
    [alternatingContentBackgroundColors], 19 controlAccentColor .. 26 alternateSelectedControlTextColor,
    [currentControlTint], 27 windowBackgroundColor .. 43 systemYellowColor; Java inserts in this order */
#define GMAC_TINT_NONE (-1)        /* key omitted */
#define GMAC_TINT_DEFAULT 0
#define GMAC_TINT_GRAPHITE 1
#define GMAC_TINT_BLUE 2
#define GMAC_TINT_CLEAR 3
typedef struct GmacPreferences {   /* sizeof 1080, align 8 */
    GmacColor colors[GMAC_PREFS_COLOR_COUNT];           /*    0 */
    int32_t control_tint, scroller_overlay;             /* 1056, 1060 */
    int32_t reduce_motion, reduce_transparency;         /* 1064, 1068 */
    int32_t path_constrained, path_expensive;           /* 1072, 1076 */
} GmacPreferences;
GMAC_EXPORT int32_t gmac_sizeof_preferences(void);
GMAC_EXPORT int32_t gmac_prefs_query(void* delegate, GmacPreferences* out,
                                     GmacColor** out_alt, int32_t* out_alt_count);  /* NO_DATA = Java null */

/* ---- keys ---- */
GMAC_EXPORT int32_t gmac_key_code_for_char(uint16_t c);            /* hint ignored, as the JNI did */
/* Superseded by g1 D7: Java's switch plus gmac_current_modifier_flags; the header has no gmac_key_is_locked. */
GMAC_EXPORT int32_t gmac_key_is_locked(int32_t java_key_code);     /* KeyEvent.KEY_LOCK_* */
GMAC_EXPORT int32_t gmac_key_java_to_mac(int32_t java_key_code);   /* _getMacKey: 0 when not found, kept */

/* ---- Glass <-> Prism coupling, consumed by the MacView group's gmac_view_create ---- */
typedef struct GmacViewCaps {      /* sizeof 40, align 8; Java reads the device-details Map */
    void*   mtl_command_queue;     /*  0: id<MTLCommandQueue>, borrowed from prism_mtl; NULL = absent */
    void*   share_context;         /*  8: NSOpenGLContext*, borrowed from prism_es2 */
    void*   context;               /* 16 */
    int32_t depth_bits;            /* 24: 0 = absent */
    int32_t hidpi_aware;           /* 28 */
    int32_t use_mtl_for_sw;        /* 32 */
    int32_t vsync_enabled;         /* 36: 1 when absent (C default YES), else (value == 1L) */
} GmacViewCaps;
GMAC_EXPORT int32_t gmac_sizeof_view_caps(void);
/* Note: gmac_view_native_frame_buffer must return int64_t, not void*: on CGL it is a GLuint FBO name. */

/* ---- shim-only test hooks (do not move GLASS_MAC_ABI_VERSION) ---- */
GMAC_EXPORT int32_t gmac_test_fire_app_callback(int32_t slot);     /* fixed argument pattern per slot */
GMAC_EXPORT int32_t gmac_test_pool_depth(void);                    /* GlassThreadData.counter of this thread */
#ifdef __cplusplus
}
#endif
```

**Rules for this ABI:**
- No `BOOL` at the boundary. ObjC `BOOL` is `bool` on arm64 and `signed char` on x86_64, so use `int32_t` and
  compare `!= 0`.
- **Strings into C:** `const uint16_t*` + `int32_t len`, with NULL standing for Java `null`. There are two helpers,
  which keep the two existing semantics apart: OrEmpty (as `nsStringWithJavaString`, `GlassHelper.m:336`:
  `null`/`""` become `@""`) and OrNil (as `jStringToNSString`, `GlassAccessible.m:262`: `null` becomes `nil`).
- **Strings out of C:** the `[NSString UTF8String]` bytes + length, NULL meaning `null`. Java decodes them with
  `JniStringCodec.fromNewStringUtf`, which reproduces what `NewStringUTF` did (GTK precedent); never use a charset
  decoder. This matters for `openFiles` names outside the BMP.
- **critical(true)** is allowed only on `gmac_abi_version`, `gmac_sizeof_*` and `gmac_main_java_attached`.

### 8. Callback tables

```c
typedef struct GmacAppCallbacks {   /* 21 pointers = 168 bytes; every slot runs on the AppKit main thread */
    int32_t (*run_runnable)(int64_t runnable_id);           /* REPORT. Java removes the registry entry and runs it */
    int32_t (*run_launchable)(void);                        /* DESCRIBE; THREW => C skips the screen observer (:254) */
    int32_t (*set_event_thread)(void);                      /* REPORT; the first upcall, which attaches the thread */
    int32_t (*set_name)(const char* utf8, int32_t len);     /* REPORT */
    int32_t (*notify_will_finish_launching)(void);          /* REPORT; the 11 notify_* replace GlassHelper's getters */
    int32_t (*notify_did_finish_launching)(void);
    int32_t (*notify_will_become_active)(void);
    int32_t (*notify_did_become_active)(void);
    int32_t (*notify_will_resign_active)(void);
    int32_t (*notify_did_resign_active)(void);
    int32_t (*notify_will_hide)(void);
    int32_t (*notify_did_hide)(void);
    int32_t (*notify_will_unhide)(void);
    int32_t (*notify_did_unhide)(void);
    int32_t (*notify_will_quit)(void);
    int32_t (*notify_open_files)(const char* const* utf8, const int32_t* lens, int32_t count); /* REPORT */
    int32_t (*notify_did_terminate)(void);                  /* REPORT; C clears the attached flag after it returns */
    int32_t (*enter_nested_event_loop)(void);               /* DESCRIBE (Application.enterNestedEventLoop) */
    int32_t (*leave_nested_event_loop)(void);               /* NONE today; the wrapper reports (documented deviation) */
    int32_t (*notify_screen_settings_changed)(void);        /* REPORT (Screen.notifySettingsChanged) */
    int32_t (*notify_preferences_changed)(const GmacPreferences* p,
                                          const GmacColor* alt, int32_t alt_count); /* REPORT, per JNI step */
} GmacAppCallbacks;
GMAC_EXPORT int32_t gmac_sizeof_app_callbacks(void);
GMAC_EXPORT int32_t gmac_app_set_callbacks(const GmacAppCallbacks* t); /* copied by value; NULL clears */
```

**How the table behaves:**
- **NULL slot:** no call, and the C carries on as after a normal return (GTK rule).
- **Identity:** the app slots need no id (one application per process). Runnables are Java-assigned ids in a
  `ConcurrentHashMap` with a `NULL_RUNNABLE` sentinel (the `GtkMainLoopCalls` precedent). When `invoke_*` returns
  `NOT_ATTACHED` or `DETACHED_OFF_MAIN`, Java removes the entry, because the JNI never created the global ref in that
  case.
- **Exceptions:**
  - Every wrapper catches `Throwable`; an escaping one kills the JVM.
  - REPORT: `try { Application.reportException(t); } catch (Throwable ignored) {}`, as the second `ExceptionClear`
    did.
  - DESCRIBE: reproduce HotSpot's `jni_ExceptionDescribe` (by reading, unverified). It writes
    `Exception in thread "<name>" ` to fd 2 and then calls `t.printStackTrace()`.
  - `notify_preferences_changed`: Java builds the map in C order; `Color.rgb` throwing `IllegalArgumentException` is
    reported and that key skipped (`GLASS_CHECK_EXCEPTION_RETURN`, `PlatformSupport.m:344,366`). It keeps
    `currentPreferences` as a Java field that starts `null`, and calls `equals`, then `unmodifiableMap`, then notify.
- **Arena:** `Arena.global()`, created once. This is the GTK precedent (`GtkCallbackTables.java:284`). AppKit, KVO
  and `nw_path` can deliver events up to process exit, so no shorter arena provably outlives them (AC 5).
- **The other groups' tables** follow the same pattern; only their names and threads are given here:
  `GmacWindowCallbacks` (10 `jWindow*` IDs), `GmacViewCallbacks` (15 `jView*`), `GmacGestureCallbacks` (8),
  `GmacMenuCallbacks`, `GmacDialogCallbacks`, `GmacAccessibleCallbacks` all run on main.
  `GmacTimerCallbacks.tick(timer_id)` runs on the timer pthread or the display-link thread, so the wrapper should
  restore the thread name. `notify_move_to_another_screen` should take a `const GmacScreenInfo*`.

### 9. Deletion candidates (only those that clear the gate)

| Candidate | Parity | Lines of C | Copies | Java counterpart in tree | Parity test |
|---|---|---:|---:|---|---|
| **Kept native, not deleted (PB4, g1 §10):** `gmac_screen_video_refresh_period` stays permanently. Proposed here: `staticScreen_getVideoRefreshPeriod` → Java binds CoreVideo `CVDisplayLinkGetActualOutputVideoRefreshPeriod` | exact: same call, `*1000.0` in double | 17 (`GlassApplication.m:1188-1204`) | 1 (mac only) | none | while the C exists, assert `==` between the C export and the Java bind on the same link. **Depends on** MacTimer moving `CVDisplayLinkCreate…`/`Start`/`Stop` to Java (all C-callable) |
| **Superseded by §R3 item 3, not deleted:** the condition stays in C inside `gmac_main_thread_detached()` (§10). Proposed here: `pthread_main_np` half of `GLASS_ASSERT_MAIN_JAVA_THREAD` → Java binds libSystem `pthread_main_np` | exact for the condition; the per-site outcome is in §10 | 8 (`GlassMacros.h:182-189`), 102 use sites | 1 | none | pin the condition on the main and a non-main thread; thrown vs reported per site |
| Screen arithmetic fragment (`GlassScreen.m:66-73,95-114`): DPI from mm, y-flip | exact by reading: double `+ - * /` with no `a*b+c` to contract, no libm (only `floor` on `NSAppKitVersionNumber`); **unverified** | ≈20 | 1 | none | golden of `Screen.getScreens()` fields from the JNI build (owner-run). Optional, low value; I recommend keeping it in C for the migration |
| **Dead code** (no verdict needed, removal changes no behaviour) | – | `NSScreen(FullscreenAdditions)` 67; `printLoadedLibraries`/`printLoadedFiles`/`getRam` + helpers ≈170; `VERBOSE_LOAD` block 17; `JNI_OnLoad_glass` `STATIC_BUILD` arm; `GlassApplication.h:54` `jshareContextPtr` | 1 | – | build only |

### 10. Kept native despite being pure, or parity not settled

- **Key tables (#9) and the nested-loop flag (#28):** PURE, but their only callers are the C `NSEvent` path and the C
  run loop. There is no boundary to delete; they stay.
- **`_isKeyLocked` through `CGEventSourceFlagsState(kCGEventSourceStateCombinedSessionState) &
  kCGEventFlagMaskAlphaShift`:** `PARITY: unknown`. Experiment (owner-run, needs a keyboard or the robot): toggle
  Caps Lock and compare against `[NSEvent modifierFlags]` across focus and Secure Input states.
- **`invoke_later`/`invoke_and_wait` through a C-callable `CFRunLoopSource`:** `PARITY: unprovable`. Its FIFO
  ordering relative to the other `performSelectorOnMainThread` traffic (`GlassWindow.m:969,1040,1367`,
  `GlassDialogs.m:455,533,600`, the offscreen flushes) cannot be compared. Stays.
- **`_getApplicationClassName` through libobjc `object_getClassName(NSApp)`:** not equivalent. `PlatformSupport`
  observes `NSApp` with KVO before the launchable runs (`GlassApplication.m:227` before :246), so the runtime class is
  `NSKVONotifying_…`, while `[NSApp class]` hides that. This breaks `MacApplication.java:524`. Stays.
- **`_getDataDirectory` through `sysdir_*`, and the `_initIDs` version check through `sysctl kern.osproductversion`:**
  `PARITY: unknown` (tilde expansion and sandbox containers; the 10.16 compatibility mapping). Stay.
- **The `pthread_main_np` half of `GLASS_ASSERT_MAIN_JAVA_THREAD` (§5 #35, formerly a §9 candidate):** kept in C by
  §R3 item 3. `gmac_main_thread_detached()` (`glass_mac_api.m:84-87`) evaluates `pthread_main_np() == 0` and the
  attached flag in one downcall, which is the macro's exact condition (`GlassMacros.h:183`). A separate Java bind of
  `pthread_main_np` would still need the flag from C, so it would remove no C. No follow-up.
- **`gmac_free` (`glass_mac_api.m:74-77`):** a one-line wrapper over `free()`, kept by §R3 item 10. Every block that C
  allocates for the caller is freed through it, which keeps one allocator rule across the four groups' ABIs. A Java
  bind of libSystem `free` would behave the same (macOS has one malloc heap) but would split that rule. No follow-up.
- **`GLASS_ASSERT_MAIN_JAVA_THREAD` outcome:** `PARITY: unknown`. Where a `GLASS_CHECK_EXCEPTION` follows on the same
  path (for example `_getDataDirectory` :1292, `_openURI` :1381) the `RuntimeException` was *reported* and the call
  returned normally; otherwise it was *thrown* (`_submitForLaterInvocation`, `_invokeAndWait`). In between, the JNI
  calls ran with the exception pending, which the spec leaves undefined. Experiment: a child JVM calls each such
  native from a non-main thread before `Application.run`, on the JNI build and on the FFM build, and compares what is
  thrown or reported. This launches no window.

### 11. Build/test touchpoints, risks, recommendation

#### AC 9: headers and include directories

- **Today:** `mac.cmake:149-152` gives *every* target (`glass`, `prismES2`, `prismMTL`, `iio`)
  `${JDK_HOME}/include[/darwin]` and `${HEADERS_DIR}`.
- **Precedents:**
  - Windows adds a `JNI` option (`win.cmake:113-115,153-159`) and only `glass` passes it.
  - Linux (US-001a) gives no target the JDK directories, and adds `native-glass/gtk/constants/`: 11 hand-kept
    `javac -h`-named headers, checked by `GtkConstantsHeaderTest` through the ClassFile API.
- **For mac:**
  - Add the `JNI` option to mac's `add_jfx_library` (`glass` and `prismMTL` say `JNI` during the transition; `iio`
    and `prismES2` are already JNI-free and simply lose the directories).
  - Add `native-glass/mac/constants/` and pass it as `glass`'s `INCLUDE_DIRS` (that keyword already exists,
    `mac.cmake:125`).
  - Remove `<jni.h>` from the 20 includers and respell the 2,122 JNI type tokens as `<stdint.h>` types.
  - Prove it with a build that uses `-DHEADERS_DIR=/nonexistent -DJDK_HOME=/nonexistent`, as US-001a did.
  - `CMakeLists.txt:59-60` still requires `HEADERS_DIR` (for Windows); leave it.

**The 15 headers whose constants the mac C uses (215 constants), and the files that include them:**

| Header | Why `javac -h` emits it | Constants used | Includers (`file:line`) |
|---|---|---:|---|
| `com_sun_glass_events_KeyEvent.h` | 172 `@Native` | 138 (`GlassKey.m` 134, `GlassViewDelegate.m` 17, `GlassMenu.m` 9, `GlassApplication.m` 1) | `GlassApplication.m:28`, `GlassKey.m:27`, `GlassMenu.m:27`, `GlassView3D.m:28`, `GlassViewDelegate.m:29` |
| `…events_MouseEvent.h` | `@Native` | 13 | `GlassView3D.m:29`, `GlassViewDelegate.m:28` |
| `…events_DndEvent.h` | `@Native` | 4 | `GlassView3D.m:27`, `GlassViewDelegate.m:30` |
| `…events_SwipeGesture.h` | `@Native` | 4 | `GlassViewDelegate.m:31` |
| `…events_ViewEvent.h` | `@Native` | 4 (used only in `GlassViewDelegate.m`) | `GlassView.m:31`, `GlassViewDelegate.m:27`, `GlassWindow.m:27`, `GlassWindow+Java.m:27`, `GlassWindow+Overrides.m:27` |
| `…events_WindowEvent.h` | `@Native` | 6 | `GlassWindow.m:28`, `+Java.m:28`, `+Overrides.m:28` |
| `com_sun_glass_ui_Clipboard.h` | `@Native` | 6 | `GlassDragSource.m:27`, `GlassPasteboard.m:28`, `GlassView.m:30`, `GlassViewDelegate.m:32` |
| `…ui_Cursor.h` | `@Native` | 1 (`CURSOR_MAX`) | `GlassCursor.m:27` |
| `…ui_Pixels_Format.h` | `@Native` | 1 (`BYTE_BGRA_PRE`) | `GlassPixels.m:27` |
| `…ui_View_Capability.h` | `@Native` | 2 (`k3dDepthKeyValue`, `kHiDPIAwareKeyValue`) | `GlassView.m:29`, `GlassView3D.m:30`, `GlassViewCGL.m:27`, `GlassViewMTL.m:26`. **None since `GmacViewCaps`**: the slice-6 cleanup deleted `constants/com_sun_glass_ui_View_Capability.h`, and 13 constants headers remain (`Pixels_Format.h` went in slice 2) |
| `…ui_Window.h` | `@Native` | 10 | `GlassWindow.m:29`, `+Java.m:29`, `+Overrides.m:29` |
| `…ui_Window_Level.h` | `@Native` | 2 | `GlassWindow.m:30`, `+Java.m:30`, `+Overrides.m:30` |
| `…ui_mac_MacGestureSupport.h` | **only because the class still has 1 native (`_initIDs`)**; fields are `private static final` (`MacGestureSupport.java:38-44`) | 6 | `GlassGestureSupport.m:27`, `GlassView3D.m:31`, `GlassViewDelegate.m:33` |
| `…ui_mac_MacPasteboard.h` | **only because of its 13 natives**; fields are not `@Native` (`MacPasteboard.java:36-40`). **Not since slice 7:** the class has no natives, so `javac -h` no longer writes it and the `constants/` copy serves it | 4; 2 since slice 7 (`General`, `DragAndDrop`) | `GlassPasteboard.m:27`, still imported |
| `…ui_mac_MacVariant.h` | 19 `@Native`, no natives | 14 | `GlassAccessible.m:29`, `a11y/AccessibleBase.m:30`, `a11y/JFXStaticTextAccessibility.m:30` |

The 14 headers that supply prototypes only (their `#import` lines go with the JNI bodies):
- `…mac_MacAccessible.h` (`GlassAccessible.m:28`, `a11y/AccessibleBase.m:29`, `a11y/JFXStaticTextAccessibility.m:29`)
- `…mac_MacApplication.h` (`GlassApplication.m:27`, `GlassKey.m:28`)
- `…MacCommonDialogs.h` (`GlassDialogs.m:27`)
- `…MacCursor.h` (`GlassCursor.m:28`)
- `…MacMenuBarDelegate.h` / `…MacMenuDelegate.h` (`GlassMenu.m:28,29`)
- `…MacPixels.h` (`GlassPixels.m:28`)
- `…MacRobot.h` (`GlassRobot.m:27`)
- `…MacSystemClipboard_FormatEncoder.h` (`GlassSystemClipboard.m:28`)
- `…MacTimer.h` (`GlassTimer.m:27`)
- `…ui_Timer.h` (`GlassTimer.m:29`)
- `…ui_View.h` (`GlassView.m:27`)
- `…MacView.h` (`GlassView.m:28`)
- `…MacWindow.h` (`GlassWindow.m:31`, `+Java.m:31`, `+Overrides.m:31`)

Grep used: `cat mac/*.[mh] mac/a11y/*.[mh] | grep -vE '^\s*#\s*import|^\s*\*|Class:' | grep -oE
'\bcom_sun_glass_[A-Za-z0-9_]+' | sort -u`.

**Landing-order trap:** the `MacGestureSupport` and `MacPasteboard` headers disappear the moment those classes lose
their last `native`. Their hand-kept copies must already be in `constants/`, or `GlassViewDelegate.m` and
`GlassPasteboard.m` stop compiling. Windows hit the same thing (s3-51).

#### Tests

- **Existing:**
  - The module has no glass/mac tests.
  - `tests/system`: `test/com/sun/glass/ui/mac/MacPasteboardTest` (with
    `src/shims/java/com/sun/glass/ui/mac/MacPasteboardShim.java`), `ExceptionHandlerTest` and
    `DefaultExceptionHandlerTest` (they exercise the REPORT path), and 19 robot test files with mac conditions.
  - **All of these are owner-run:** they launch the toolkit, which activates the app.
- **New tests an agent can run** (no `NSApp`, no window):
  - `MacGlassNativeTest`: symbols, ABI version, `sizeof` of the 5 structs against the `MemoryLayout`s
    (168/88/40/24/1080), `gmac_test_fire_app_callback` for all 21 slots and each exception mode,
    `gmac_test_pool_depth`.
  - `MacConstantsHeaderTest`: pure Java, runs on every OS, same design as the GTK one.
  - Pure-Java tests of the preferences-map assembly and the runnable registry.
- **Owner-run:**
  - the JNI baseline (module tests + `tests/system` + `nm -u -m`);
  - goldens of `getPlatformPreferences()` and `Screen.getScreens()` captured from the JNI build;
  - stderr capture at the DESCRIBE sites;
  - the §10 experiments that need a keyboard;
  - the robot suites.

#### Risks

1. **`gmac_init` ordering.** If any pool-using function runs before `pthread_key_create`, it reads thread-specific
   key 0. By reading of Darwin's TSD layout (not verified here), that slot holds the thread's own `pthread_t`, so
   `GLASS_POOL_ENTER` would write into libpthread memory. The static initialiser must call `gmac_init` before
   anything else.
2. **`critical(true)` on blocking calls.** On `gmac_app_invoke_and_wait`, `run_loop` or `enter_nested_loop` it would
   stall safepoints while the main thread needs Java: a deadlock.
3. **The keep-both period.**
   - JNI reached from inside an FFM downcall resolves `FindClass` against the wrong loader; this is the Windows s3-61
     finding, for `com.sun.glass.ui.Application` on JDK 26. Sites here: `GlassScreen.m:128` (Screen),
     `PlatformSupport.m:91` (`javafx/scene/paint/Color`), `GlassAccessible.m:389,470`.
   - The C must also keep `jEnv` and `g_mainJavaAttached` in step.
4. **Behaviour after termination.** Former `GET_MAIN_JENV` sites run with `jEnv == NULL` and dereference NULL today.
   With FFM the stub would run Java instead. The C must test the attached flag at every former JENV site: skip where
   the JNI checked `env != NULL`, and print `GLASS_CALLSTACK` and skip where it crashed. That last case is a
   documented deviation from a SIGSEGV.
5. **Thread identity.** Daemon status and thread names (the `"PulseTimer-CVDisplayLink thread"` attach name) change
   with FFM's auto-attach. Uncaught-exception output on the timer threads shows this.
6. **Threading asserts in release.** All are compiled out by `-DNDEBUG`. Making AC 5's assertions active in release
   builds would be new behaviour, so keep them debug-only, or Java-side and log-only.
7. **Borrowed pointers across libraries.** Prism's `MTLCommandQueue` (`GlassLayerMTL.m:55`) and Glass's `MTLTexture`
   (`MetalRTTexture.m:102`) are not retained by the other side. Pipeline dispose before views close leaves a dangling
   queue (carried).
8. **ObjC exceptions** unwinding through a downcall or upcall stub crash the process, as they do through JNI today
   (`_openURI` with a `nil` URL, below).
9. **AC 9 side effect.** `iio` and `prismES2` lose the JDK include directories on mac. They are JNI-free on Linux and
   Windows; macOS CI, or this host, is the compile check.

#### Latent bugs to carry verbatim (do not fix in the migration)

- `_openURI` returns `-1` inside `GLASS_POOL_ENTER` without `EXIT` (`GlassApplication.m:1353-1355`), so the pool and
  the thread-specific counter leak. A `null` uri gives a `nil` URL, then `@[nil]`, then `NSInvalidArgumentException`.
- `openFiles` `return`s without `[pool drain]` and without `replyToOpenOrPrint:` (:446-448).
- `leaveNestedEventLoopWithEnv` overwrites the previous global ref (:829).
- `GlassRunnable run` with `jEnv == NULL` leaks its ref (:127-135).
- `_getMacKey` ignores `GetMacKey`'s `NO` and returns 0, which is `kVK_ANSI_A` (:1305-1307).
- `GetJavaKeyChars` uses a `jchar jc[16]` stack buffer for a `[chars length]` of any size (`GlassKey.m:463-469`).
- `[screen retain]` on every enumeration (`GlassScreen.m:86`), deliberate.
- `currentPreferences` is never released (`PlatformSupport.m:288`).
- `GlassMTLOffscreen dealloc` calls `[super dealloc]` only when `_fbo != nil` (:64-71).
- `MTLCreateSystemDefaultDevice`/`newCommandQueue`/`CGColorSpaceCreateWithName` are +1 and never released
  (`GlassLayerMTL.m:47,57,64`, `GlassMTLFrameBufferObject.m:60`).
- `nextDrawableCount` is a non-atomic static shared across layers and threads (`GlassLayerMTL.m:89-142`).
- `CVOutputCallback` leaves the exception pending (`GlassTimer.m:136`).
- `GLASS_ASSERT_MAIN_JAVA_THREAD` continues after `ThrowNew`.
- `_getDataDirectory` passes `jEnv` rather than `env` (:1288); this disappears with FFM.
- `nsStringWithJavaString` does not check `GetStringChars` for NULL (`GlassHelper.m:343-345`), so a C-heap
  allocation failure on a non-empty string reaches `initWithCharacters:NULL length:size`. The slice-0 wrapper passes
  the NULL on to `GlassNSStringOrEmpty`, which keeps that. The FFM path cannot reach it: Java allocates the chars
  before the downcall. The helper is deleted in slice 9's cleanup with its last JNI caller, so no path reaches this
  any more.
- `pthread_key_create(&GlassThreadDataKey, NULL)` registers no destructor (`gmac_init`, formerly `JNI_OnLoad`), so
  every thread that ever ran `GLASS_POOL_ENTER` keeps its `calloc`'d `GlassThreadData` after it exits.

#### Recommendation (order of work; the owner decides the commit structure)

0. **Owner:** the JNI baseline on this host (module tests, `tests/system`, `nm -u -m libglass.dylib`, the preferences
   and screen goldens).
1. `constants/` (15 headers) + `MacConstantsHeaderTest`, and the `JNI` opt-in in `mac.cmake`. The library should be
   identical apart from include paths. Run the module tests.
2. `glass_mac_api.h/.m` beside the JNI: `gmac_init`, the attached flag, `GmacAppCallbacks` with keep-both dials (a
   site dials its slot when the table is installed, otherwise takes the JNI path, as Windows did), and the screen,
   preferences and key exports.
3. `MacGlassNative` (≤900 lines; the story calls it `MacNative`, precedent says `*GlassNative`) plus
   `Mac{Application,Screen,Preference,Key,MainLoop}Calls` aggregates, then flip the 13 `MacApplication` natives
   listed in §3. After each step: `mvn -pl modules/javafx.graphics test -Dtest='*MacGlass*'`, then the full module
   suite. The owner runs `tests/system` (`ExceptionHandlerTest`, `DefaultExceptionHandlerTest`, `MacPasteboardTest`,
   robot stage/scene).
4. Only after every other group has flipped: delete the JNI glue (§5 list), `JNI_OnLoad` and `<jni.h>`; respell the
   type tokens; drop `JNI` from `glass`; run the AC 9 proof build.
5. Keep these as separate, reviewable kinds of change, not part of the transport migration: the dead-code removal
   (§9), and the WRAPPER deletions (the video refresh period together with MacTimer; `pthread_main_np`, which §R3
   item 3 has since kept in C, §10).

**Relevant files (absolute):**
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassMacros.h`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassStatics.h`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassApplication.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/PlatformSupport.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassScreen.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassKey.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassViewMTL.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/native/mac.cmake`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/glass/ui/mac/MacApplication.java`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/prism/mtl/MTLPipeline.java`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/gtk/constants/`

## Group g1: Application, Timer, Robot, Cursor, Pixels, GestureSupport

**Scribe's corrections** (details in §R2):

- "No `target/`, no `libglass.dylib`, no generated headers": the baseline build of `1a6788cc4d` has both (§R1). The
  `javac -h` readings in §11 hold against the baseline headers: `Cursor.h`, `Pixels_Format.h`, `Timer.h` and
  `KeyEvent.h` come from `@Native` fields, and `MacGestureSupport.h`/`MacPasteboard.h` only from the natives.
- §4 "CoreVideo (re-exported by QuartzCore)": the baseline links CoreVideo with its own load command, and the 8
  `CVDisplayLink*` imports resolve there (`nm -m`). A Java bind should look up CoreVideo itself.
- §10 item 4: the baseline `libglass.dylib` already imports `_objc_autoreleasePoolPush`/`Pop` from libobjc. Clang
  lowers the two `@autoreleasepool` blocks (`GlassMTLOffscreen.m:150`, `GlassLayerMTL.m:115`) to them. So libobjc
  exports them, even though no public SDK header declares them.
- §2 "13 are `@Override native protected`": 11 have `@Override` on the same line. For `_getKeyCodeForChar` and
  `_isKeyLocked`, `@Override` sits on the line before (`MacApplication.java:429,432`). The count of 13 overrides is
  right.
- `MacGestureSupport._initIDs` is also claimed by g2 (§R4). Both groups schedule it with MacView.
- Several pieces of this ABI conflict with g0 and g2, and §R3 decides each:
  - `GmacScreenInfo` is 56 bytes here, 88 there;
  - `GmacAppCallbacks` has 10 slots with `void* user` here, 21 slots without it in g0;
  - the `GmacPrefs` entry list against g0's fixed struct;
  - `JFX_EXPORT` and a `uint32_t` `gmac_abi_version`;
  - `GMAC_ERR_UNSUPPORTED_OS (-1)`;
  - `pthread_key_create` folded into `gmac_app_init`.

**Paths.** `N/` = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/`,
`J/` = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/glass/ui/mac/`,
`B/` = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/native/`.

**File abbreviations** (all under `N/`):
- GA = `GlassApplication.m`, GT = `GlassTimer.m`, GR = `GlassRobot.m`, GC = `GlassCursor.m`
- GP = `GlassPixels.m`, GGS = `GlassGestureSupport.m`, GK = `GlassKey.m`, GS = `GlassScreen.m`
- PS = `PlatformSupport.m`, GH = `GlassHelper.m`, GSt = `GlassStatics.m`, GM = `GlassMacros.h`

**Evidence limits.**
- This tree has no `target/` directory, so there is no `libglass.dylib` to run `nm -u` on and no generated
  `target/gensrc/headers` to inspect.
- Every imported symbol named below was read from source.
- Claims about which constants the generated headers contain are based on how `javac -h` works, not on reading the
  headers. Check both after `process-classes`.
- Nothing was built or run.

### 1. Verdict

**Split the library, and delete the leaf classes rather than giving them a facade.** In scope are 51 `JNIEXPORT`s:
**27 OS-CALL, 16 WRAPPER, 8 PURE, 0 PURE-HOT**.

- **PURE (8).** All have `PARITY: exact`.
  - Six are deletion candidates: Timer min/max period, `MacPixels._initIDs`, `MacGestureSupport._initIDs`, Robot
    `_init`/`_destroy`, and the return-value half of `_leaveNestedEventLoopImpl`.
  - Two stay as one-line accessors because native code owns the state they touch (`_isTriggerReactivation`, the
    nested-loop flag).
- **WRAPPER, parity exact by construction (10).**
  - The 8 Robot entries: Java calls the same CoreGraphics / HIServices / CF calls with the same arguments, so they
    are exact by construction (PB2 (i)); the owner-run `RobotTest` and the CGEventTap/capture A/B confirm it.
  - `staticScreen_getVideoRefreshPeriod` and `_getDataDirectory`.
- **WRAPPER, `PARITY: unknown` (6).** All six are MacTimer entries. What can't be proven equal: thread names, JVM
  attach and detach lifetime, display-link ticks while no vsync timer runs, and the autorelease pool.

What that means per file:
- **`GlassRobot.m` gets no FFM facade.** Java binds CoreGraphics, HIServices and CF directly, as WinRobot already
  does on Windows. The file is deleted, and four small exports survive (§10): `gmac_mouse_location_flipped` and the
  `gmac_pool_push`/`gmac_pool_pop` pair in `glass_mac_api.m`, and `gmac_key_find_mac` (`GetMacKey` with its found
  flag) in `GlassKey.m`.
- **`GlassTimer.m` stays as the `gmac_timer_*` shim (PB4).** Java binding CoreVideo and libSystem directly, as
  WinTimer does on Windows, is the owner's follow-up after the timer experiment (§10).
- **`GlassPixels.m` shrinks to `getImage`.**
- **`GlassGestureSupport.m` goes away together with MacView's gesture callbacks.**
- **MacApplication and MacCursor are genuinely native** (AppKit and NSCursor message sends). They get `gmac_*` exports
  and one 10-slot callback table.

**The JNI bootstrap must stay until the last glass/mac class is migrated.** This means `JNI_OnLoad`, `jVM`, the
main-thread `AttachCurrentThreadAsDaemon` in `runLoop:`, `jApplicationClass` plus the `reportException` method ID,
and `glassClassLoader`. All 155 glass/mac `JNIEXPORT`s depend on them through `GLASS_CHECK_EXCEPTION`,
`GET_MAIN_JENV` and `ClassForName`.

### 2. Summary counts (scope: 6 classes, 12 C files)

| Item | Count | How counted (run in `N/`, scope file list S = GA GT GR GC GP GGS GK GS PS GH GSt GM) |
|---|---:|---|
| Java `native` declarations | **51** (MacApplication 21, MacTimer 8, MacRobot 12, MacCursor 6, MacPixels 3, MacGestureSupport 1). 11 static, 40 instance. 13 are `@Override native protected`, plus 5 `native protected` in MacRobot that override nothing | `grep -nE '\bnative\b' J/<Class>.java` |
| `JNIEXPORT` in scope | **51** (GA 19, GK 2, GT 8, GR 12, GC 6, GP 3, GGS 1) of 155 in glass/mac. **0 orphans** in either direction | `grep -c JNIEXPORT <file>`; `grep -rc JNIEXPORT .` |
| `JNI_OnLoad` | 1 (GA:86/88, `STATIC_BUILD` twin `JNI_OnLoad_glass`) | `grep -rn JNI_OnLoad .` |
| `Call*Method` sites | **38** textual, 37 compiled (GA:737 is under `#if VERBOSE_LOAD`). GA 20, GT 2, GR 2, GC 1, GS 1, PS 9, GH 1, GM 2 (the `reportException` macro bodies). Also 3 `NewObject` (GC:282, GS:90, PS:154) and 1 `GetStaticObjectField` (PS:312) | `grep -nE '(->\|\.)Call(Static\|Nonvirtual)?[A-Za-z]*Method'`, `grep -n ')->NewObject('` |
| `Runnable.run` upcalls | 4 (GA:129, GA:246, GT:67, GT:136) | as above |
| `Get*MethodID` / `GetStaticFieldID` | 43 textual, 47 counting PS macro expansions (§3c) | `grep -nE 'Get(Static)?(Method\|Field)ID'` |
| `FindClass` / `ClassForName` | 9 FindClass textual (GM:185 expands 27×, PS:32 6×); 8 ClassForName | `grep -nE 'FindClass\|ClassForName:'` |
| `NewGlobalRef` / `DeleteGlobalRef` / `NewWeakGlobalRef` | 14 textual (19 with PS:37 ×6) / 4 / 0 | `grep -nE 'NewGlobalRef\|DeleteGlobalRef\|NewWeakGlobalRef'` |
| Pinned or copied arrays | 1 `GetPrimitiveArrayCritical` (GP:100, `JNI_ABORT`), 1 `GetIntArrayElements` (GR:494, mode 0), 1 `GetDirectBufferAddress` (GP:104), 2 `GetDirectBufferCapacity`, 2 `GetArrayLength`, 1 `NewIntArray`, 3 `NewObjectArray`. 0 `NewDirectByteBuffer` | `grep -nE 'Get[A-Za-z]*ArrayElements\|ArrayCritical\|GetDirectBuffer\|NewDirectByteBuffer\|New[A-Za-z]*Array\('` |
| Strings | 11 `NewStringUTF` (GA 5, PS 5, GH 1); 1 `GetStringChars` (GH:341); 1 `GetStringCritical` on the `_openURI` path (GlassAccessible.m:266 via `jStringToNSString`) | `grep -nE 'NewStringUTF\|GetString(UTF)?Chars\|GetStringCritical'` |
| Exceptions | `ThrowNew` 2 textual: GA:956, and GM:187 in `GLASS_ASSERT_MAIN_JAVA_THREAD`, which expands 27× (GA 4, GT 7, GR 10, GC 5, GP 1). 4 `ExceptionDescribe`+`Clear` (GA:235, 242, 249, 844). 102 `GLASS_CHECK_EXCEPTION*` uses (GA 28, GT 8, GC 5, GP 1, GGS 8, GK 1, GS 7, PS 22, GH 16, GSt 6) | `grep -cE 'GLASS_CHECK_EXCEPTION\|GLASS_CHECK_NONNULL'` |
| Attach / detach | 3 `AttachCurrentThreadAsDaemon` (GA:562, GT:54, GT:131), 2 `DetachCurrentThread` (GT:101, GT:144), 0 `GetJavaVM`. These are all of glass/mac's attach sites | `grep -rnE 'GetJavaVM\|AttachCurrentThread\|DetachCurrentThread' .` |
| PURE-HOT signals | none | `grep -nE 'emmintrin\|immintrin\|arm_neon\|__asm\|simd'` |

### 3. Java classes and C files

#### 3a. Native declarations, their C bodies, and calling threads

FX thread = AppKit main thread on macOS (set by `setEventThread`, GA:589). "Launcher" = the thread that calls
`Application.run`.

| Class.method (JNI descriptor) | Kind | C body | Java caller thread |
|---|---|---|---|
| MacApplication.`_initIDs` (Z)V | static | GA:928 | class-init thread (launcher) |
| `_getMacKey` (I)I | static | GA:1302 → GK:474 | main (MacAccessible J/MacAccessible.java:1500) |
| `_openURI` (Ljava/lang/String;)I | static | GA:1340 | any (`showDocument` has no thread check); blocks ≤1 s |
| `_initDelegate` (Ljava/lang/ClassLoader;Ljava/lang/Runnable;Z)J | inst | GA:1009 | launcher |
| `_runLoop` (J)V | inst | GA:1027 | launcher. Blocks: polls `started` every 10 ms, or runs `[NSApp run]` inline under `-XstartOnFirstThread` |
| `_finishTerminating` (J)V | inst | GA:1071 | FX |
| `_enterNestedEventLoopImpl` ()Ljava/lang/Object; | inst | GA:1112 → GA:796 | FX; blocks, re-entrant |
| `_leaveNestedEventLoopImpl` (Ljava/lang/Object;)V | inst | GA:1134 → GA:826 | FX |
| `_hide` / `_hideOtherApplications` / `_unhideAllApplications` ()V | inst | GA:1245 / 1256 / 1267 | FX (Apple menu action) |
| `staticScreen_getVideoRefreshPeriod` ()D (@Override) | inst | GA:1188 | FX (`Screen.getVideoRefreshPeriod` checks the event thread) |
| `staticScreen_getScreens` ()[Lcom/sun/glass/ui/Screen; (@Override) | inst | GA:1211 → GS:122 | FX/main |
| `_invokeAndWait` (Ljava/lang/Runnable;)V (@Override) | inst | GA:1170 | any thread other than FX; blocks |
| `_submitForLaterInvocation` (Ljava/lang/Runnable;)V | inst | GA:1152 | InvokeLaterDispatcher thread, or the caller when `javafx.embed.isEventThread` |
| `_isTriggerReactivation` ()Z | inst | GA:1233 | main (inside the launchable) |
| `_getDataDirectory` ()Ljava/lang/String; | inst | GA:1278 | FX (`checkEventThread`) |
| `_getKeyCodeForChar` (CI)I (@Override) | inst | GK:631 → GA:901 | any (no thread check) |
| `_isKeyLocked` (I)I (@Override) | inst | GK:644 | FX |
| `_getApplicationClassName` ()Ljava/lang/String; | inst | GA:1315 | main (inside the launchable) |
| `_getPlatformPreferences` (J)Ljava/util/Map; | inst | GA:1327 → PS:153 | FX |
| MacTimer.`_getMinPeriod` / `_getMaxPeriod` ()I | static | GT:392 / 415 | class-init (FX) |
| `_start` (Ljava/lang/Runnable;)J (@Override) | inst | GT:253 (`…__1start__Ljava_lang_Runnable_2`) | FX |
| `_start` (Ljava/lang/Runnable;I)J (@Override) | inst | GT:229 (`…_2I`) | FX |
| `_stop` (J)V (@Override) | inst | GT:276 | FX (at exit) |
| `_pause` (J)V (@Override) | inst (C declares `jclass`) | GT:307 | timer thread (`postPulse` → `pauseTimer`) |
| `_resume` (J)V (@Override) | inst (C declares `jclass`) | GT:352 | thread that calls `PulseTask.set(true)` (FX or render) |
| `_initIDs` ()V | static | GT:438 | class-init (FX) |
| MacRobot.`_init` ()J, `_destroy` (J)V | inst | GR:208, 221 | FX |
| `_keyPress` / `_keyRelease` (I)V | inst, protected | GR:234, 252 | FX |
| `_mouseMove` (JFF)V | inst | GR:270 | FX |
| `_mousePress` / `_mouseRelease` (JI)V | inst | GR:335, 354 | FX |
| `_mouseWheel` (I)V | inst, protected | GR:373 | FX |
| `_getMouseX` / `_getMouseY` (J)F | inst | GR:289, 312 | FX |
| `_getPixelColor` (DD)I | inst, protected | GR:393 | FX |
| `_getScreenCapture` (IIIIZ)Lcom/sun/glass/ui/Pixels; | inst, protected | GR:455 | FX |
| MacCursor.`_initIDs` ()V | static | GC:75 | class-init |
| `_createCursor` (IILcom/sun/glass/ui/Pixels;)J (@Override) | inst (C declares `jclass`) | GC:141 | FX |
| `_set` (I)V, `_setCustom` (J)V | inst (C declares `jclass`) | GC:170, 192 | FX |
| `_setVisible` (Z)V, `_getBestSize` (II)Lcom/sun/glass/ui/Size; | static | GC:212, 238 | FX |
| MacPixels.`_initIDs` ()I | static | GP:44 | class-init |
| `_attachInt` (JIILjava/nio/IntBuffer;[II)V, `_attachByte` (JIILjava/nio/ByteBuffer;[BI)V (@Override) | inst | GP:123, 162 | FX (through `Pixels.attachData`, called by GC:151, GlassMenu.m:279, GlassPasteboard.m:270) |
| MacGestureSupport.`_initIDs` ()V | static | GGS:46 | class-init, triggered by `ClassForName(…, initialize=true)` in GlassViewDelegate.m:619 |

**Library loading.** `MacApplication`'s static block calls `Application.loadNativeLibrary()`
(J/MacApplication.java:46-51). The other classes' `static { _initIDs(); }` blocks assume the library is already loaded.

**`long` handles.**
- `MacApplication.delegateHandle` holds a `GlassApplication*`.
- `MacRobot.ptr` holds a `GlassRobot*`.
- `Timer.ptr` holds a `GlassTimer*`.
- `Cursor.ptr` holds an `NSCursor*`.
- `Screen.nativePtr` holds an `NSScreen*`.

#### 3b. C files

| File (lines) | `JNIEXPORT` | Generated headers included → constants used | How it marshals across threads today |
|---|---:|---|---|
| GA (1,384) + `GlassApplication.h` (79) | 19 | `com_sun_glass_ui_mac_MacApplication.h` → none; `com_sun_glass_events_KeyEvent.h` → `VK_UNDEFINED` (GA:905) | `performSelectorOnMainThread` at GA:1050 (`runLoop:`), :1161 (`maybeRun`, no wait), :1179 (`run`, wait), :158 (deferred runnables), :769 (embedded case), all with the `runLoopModes` list (:620) including `AWTRunLoopMode`. `dispatch_async(main)` at :313. NSNotificationCenter observer at :254. Distributed CFNotificationCenter at :875 |
| GK (660; only :631-660 in scope) | 2 | `com_sun_glass_events_KeyEvent.h` → `VK_*`, `KEY_LOCK_*`; `…MacApplication.h` → none | none |
| GT (471) + `GlassTimer.h` (50) | 8 | `com_sun_glass_ui_mac_MacTimer.h` → none; `com_sun_glass_ui_Timer.h` → none (`UNSET_PERIOD`/`SET_PERIOD` unused) | own pthread (SCHED_FIFO); CVDisplayLink thread |
| GR (544) | 12 | `com_sun_glass_ui_mac_MacRobot.h` → none | none (called on FX thread) |
| GC (288) | 6 | `com_sun_glass_ui_Cursor.h` → `CURSOR_MAX` (only in `assert`s at :131/:177, compiled out with `-DNDEBUG`); `…MacCursor.h` → none | none |
| GP (190) | 3 | `com_sun_glass_ui_Pixels_Format.h` → `BYTE_BGRA_PRE`; `…MacPixels.h` → none | none |
| GGS (122) | 1 | `com_sun_glass_ui_mac_MacGestureSupport.h` → none here. **GlassViewDelegate.m uses its `GESTURE_ROTATE`/`SWIPE`/`MAGNIFY` and `SCROLL_SRC_WHEEL`/`INERTIA`/`GESTURE` (9 uses); GlassView3D.m:31 imports it** | — |
| Helpers: GS (240), PS (376), GH (352), GSt, GM, `ProcessInfo.m` | 0 | — | PS: KVO, NSNotificationCenter, `nw_path_monitor` on the main queue (PS:136) |

#### 3c. JNI inventory details

**Cached IDs**

| Owner | IDs |
|---|---|
| MacApplication.`_initIDs` (GA:963-998) | `jApplicationClass` (global ref to MacApplication.class); `Application.createPixels`, `getScaleFactor`, `reportException`, `enterNestedEventLoop`, `leaveNestedEventLoop` (static); `MacApplication.notifyApplicationDidTerminate`, `notifyPreferencesChanged`; `jRunnableRun` (GT uses it) |
| `[PlatformSupport initIDs:]` (PS:74), called from there | `Map`, `HashMap`, `Boolean`, `Collections`, `Object`, `javafx/scene/paint/Color` classes, plus their methods and fields |
| GH:116-302 (lazy statics) | twelve `Application.notify*` method IDs (one each), `Class.forName` |
| GC:86 | `jSizeInit` |
| GP:51 | `jPixelsAttachData`, also used by GlassMenu.m:279 and GlassPasteboard.m:270 |
| GGS:54-117 | 8 static method IDs. Five are used by GlassViewDelegate.m:624/779/787/795/826. **`notifyBegin`/`Next`/`EndTouchEvent` are never used** |
| GS:60, :163 | `Screen.<init>`, looked up on every call and never cached; `notifySettingsChanged` |
| GT:442 | `initJavaIDsList`, redundant (GlassDialogs.m:396 initialises it again) |

**Global references**

| Reference | Created | Deleted / status |
|---|---|---|
| `jApplication` | GA:182 | never deleted; pins MacApplication for the process |
| `jLaunchable` | GA:185 | never deleted |
| `jApplicationClass` | GA:963 | process lifetime |
| `nestedLoopReturnValue` | GA:829 | GA:817; leaked when overwritten |
| GlassRunnable `jRunnable` | GA:1160, :1178 | GA:132, only if `jEnv != NULL` |
| display-link timer `_runnable` | GT:175 | **never deleted** |
| period-timer `_runnable` | GT:210 | GT:91 |
| `jScreenClass` | GS:130 | process lifetime |
| six PlatformSupport classes | PS:37 | process lifetime |
| `currentPreferences` | PS:288 | PS:285; never released at dealloc |
| `glassClassLoader` | GH:36 | process lifetime |
| `classCls` | GH:59 | process lifetime |
| `_ApplicationClass` | GH:103 | process lifetime |

**Arrays and buffers**
- GR:494 `GetIntArrayElements(…, 0)`, released at :517 with mode 0 (copy back): `CGBitmapContextCreate` draws into the
  copy.
- GP:100 `GetPrimitiveArrayCritical`, released at :112 with `JNI_ABORT`. The data is only read, and is copied by
  `CFDataCreate` (GP:69).
- GP:104 `GetDirectBufferAddress` gives the base address, ignoring the buffer's position. GP:146/181
  `GetDirectBufferCapacity` gives capacity in elements, ignoring the limit.

**Strings**
- All outbound strings are `NewStringUTF([ns UTF8String])`: standard UTF-8 decoded as modified UTF-8. Sites: GA:457
  (open-files names), :604 (application name), :1288 (data directory; note it passes `jEnv`, not `env`), :1319 (class
  name), PS keys (ASCII).
- Inbound: `_openURI` reads UTF-16 through `GetStringCritical`.

**Exceptions**
- `GLASS_CHECK_EXCEPTION` (GM:154-164) routes a pending exception to the static `Application.reportException(t)`,
  which calls the uncaught-exception handler. It then clears any exception that call raises.
- `GLASS_ASSERT_MAIN_JAVA_THREAD` (GM:182-189) throws `RuntimeException("Main Java thread is detached.")` when called
  off the main thread after `jEnv` became NULL, **and then lets the rest of the function run**.
- GA:954-956 throws `RuntimeException("Unsupported macOS version")`.

### 4. External dependencies

**Link libraries** (B/mac.cmake:159-167): `JFX_FRAMEWORK_LINK_LIBS` (B/mac.cmake:110-119) = AppKit,
ApplicationServices, Carbon, OpenGL, QuartzCore, Security, Network, Metal, objc.
- Compile options: `-std=c99`, `-O3 -DNDEBUG` (release), `-DGL_SILENCE_DEPRECATION`, `-DMACOS_MIN_VERSION_*`,
  `-Werror`.
- Deployment target 11.0 (`B/CMakeLists.txt:36`).
- `add_jfx_library` (B/mac.cmake:149-152) adds `${JDK_HOME}/include`, `include/darwin` and `${HEADERS_DIR}` to every
  target.

**Symbols the scope reaches, by framework** (from source; to confirm after a build:
`nm -u -arch arm64 modules/javafx.graphics/target/native/bin/libglass.dylib`):
- **AppKit / Foundation (Objective-C):** NSApp `hide:`/`hideOtherApplications:`/`unhideAllApplications:`/`stop:`/
  `postEvent:`/`run`/`nextEventMatchingMask:`/`sendEvent:`/`setDelegate:`/`effectiveAppearance`, NSRunLoop, NSCursor,
  NSImage, NSScreen, NSEvent `+mouseLocation`/`+modifierFlags`, NSWorkspace, NSColor, NSScroller, NSAppearance,
  NSMenu, NSRunningApplication, NSProcessInfo, NSBundle, NSFileManager, NSDictionary, NSURL, NSAutoreleasePool. The
  Foundation **C** functions `NSSearchPathForDirectoriesInDomains`, `NSStringFromClass`, `NSBitsPerPixelFromDepth`.
- **CoreGraphics (C):** `CGEventCreateMouseEvent`, `CGEventCreateScrollWheelEvent` (variadic), `CGEventPost`,
  `CGWarpMouseCursorPosition`, `CGWindowListCreateImage`, `CGImageGet{Width,Height,DataProvider,ColorSpace}`,
  `CGImageCreate`, `CGImageRelease`, `CGDataProviderCopyData`, `CGDataProviderCreateWithCFData`,
  `CGDataProviderRelease`, `CGColorCreate`, `CGColorCreateCopyByMatchingToColorSpace`, `CGColorGetComponents`,
  `CGColorRelease`, `CGColorSpaceCreateWithName` + `kCGColorSpaceSRGB`, `CGColorSpaceRelease`,
  `CGBitmapContextCreate`, `CGContextDrawImage`, `CGContextFlush`, `CGContextRelease`, `CGDisplayScreenSize`,
  `CGDisplayBounds`, `CGMainDisplayID` (behind the `kCGDirectMainDisplay` macro).
- **CoreVideo (re-exported by QuartzCore):** `CVDisplayLinkCreateWithActiveCGDisplays`,
  `CVDisplayLinkSetCurrentCGDisplay`, `CVDisplayLinkSetOutputCallback`, `CVDisplayLinkStart`, `CVDisplayLinkStop`,
  `CVDisplayLinkIsRunning`, `CVDisplayLinkRelease`, `CVDisplayLinkGetActualOutputVideoRefreshPeriod`.
- **CoreFoundation:** `CFRelease`, `CFDataCreate`, `CFDataGetBytePtr`, `CFNotificationCenterGetDistributedCenter`,
  `CFNotificationCenterAddObserver`.
- **HIServices:** `AXUIElementCreateSystemWide`, `AXUIElementPostKeyboardEvent`.
- **Carbon / HIToolbox / CoreServices:** `TISCopyCurrentKeyboardLayoutInputSource`, `TISGetInputSourceProperty`,
  `kTISPropertyUnicodeKeyLayoutData`, `kTISNotifySelectedKeyboardInputSourceChanged`, `UCKeyTranslate`,
  `LMGetKbdType`, `GetCurrentProcess`, `TransformProcessType`.
- **Network:** `nw_path_monitor_*`, `nw_path_is_constrained`, `nw_path_is_expensive`.
- **libdispatch:** `dispatch_semaphore_*`, `dispatch_async`, `dispatch_get_main_queue`.
- **libSystem:** `pthread_key_create`/`getspecific`/`setspecific`, `pthread_attr_*`, `sched_get_priority_max`,
  `pthread_create`, `pthread_detach`, `pthread_setname_np`, `pthread_main_np`, `gettimeofday`, `usleep`,
  `sched_yield`, `getenv`, `round`, `floor`.
- **Security:** imported at GA:40 but nothing from it is used in scope.

### 5. Triage (51 exports plus the helpers they drive)

Under "Parity", "n/a" means the function is not a deletion candidate.

#### MacApplication (17 OS-CALL, 2 WRAPPER, 2 PURE)

| C function | Verdict | Evidence | Parity | Replacement |
|---|---|---|---|---|
| `_initIDs` GA:928 | OS-CALL | `-[NSProcessInfo operatingSystemVersion]`; the rest is JNI ID caching | n/a | `gmac_app_init` (also takes over `pthread_key_create` from JNI_OnLoad GA:91) |
| `_getMacKey` GA:1302 → `GetMacKey` GK:474 | OS-CALL | `TISCopyCurrentKeyboardLayoutInputSource`, `UCKeyTranslate`, `LMGetKbdType`; `gKeyMap` is shared with the native key-event path | n/a | `gmac_key_java_to_mac` |
| `_openURI` GA:1340 | OS-CALL | `NSWorkspace URLForApplicationToOpenURL:` and `openURLs:withApplicationAtURL:configuration:completionHandler:`, `dispatch_semaphore_wait` (1 s) | n/a | `gmac_app_open_uri` |
| `_initDelegate` GA:1009 | OS-CALL | `[GlassApplication alloc]`, global refs, class loader | n/a | `gmac_app_create` / `gmac_app_set_callbacks` |
| `_runLoop` GA:1027 + `runLoop:` GA:556 | OS-CALL | `performSelectorOnMainThread`, `[NSApp run]`, `TransformProcessType`, NSImage, NSMenu | n/a | `gmac_app_run_loop` |
| `_finishTerminating` GA:1071 | OS-CALL | `[NSApp stop:/hide:/postEvent:]` | n/a | `gmac_app_finish_terminating` |
| `_enterNestedEventLoopImpl` GA:1112/796 | OS-CALL | NSRunLoop `runMode:beforeDate:`, `nextEventMatchingMask:`, `sendEvent:` | n/a | `gmac_app_enter_nested_loop` |
| `_leaveNestedEventLoopImpl` GA:1134/826 | **PURE** | none: writes two statics | **exact** | Java keeps the return value (a null `retValue` keeps the previous value; a second non-null replaces it). C keeps only the flag setter `gmac_app_leave_nested_loop` |
| `_hide` / `_hideOther…` / `_unhideAll…` | OS-CALL | one Objective-C send each | n/a | 3 one-line shims |
| `staticScreen_getVideoRefreshPeriod` GA:1188 | **WRAPPER** | `CVDisplayLinkGetActualOutputVideoRefreshPeriod` | **exact** (same call, `*1000.0`) | Java, together with the timer's display link (§9) |
| `staticScreen_getScreens` GA:1211 → GS:54/122 | OS-CALL | NSScreen frame, visibleFrame, depth, deviceDescription, backingScaleFactor; `CGDisplayScreenSize`, `CGDisplayBounds`; also writes `maxScreenDimensions` (read by GlassWindow+Java.m:363) | n/a | `gmac_app_screens` → `GmacScreenInfo[]` |
| `_invokeAndWait` GA:1170 | OS-CALL | `performSelectorOnMainThread:…waitUntilDone:YES modes:` | n/a | `gmac_app_invoke_and_wait(id)` |
| `_submitForLaterInvocation` GA:1152 | OS-CALL | same send with `waitUntilDone:NO`; GlassRunnable `maybeRun` deferral (GA:142) | n/a | `gmac_app_invoke_later(id)` |
| `_isTriggerReactivation` GA:1233 | **PURE** | none: returns a static | exact | kept as a getter: native delegate code (GA:344/377) writes and reads the state |
| `_getDataDirectory` GA:1278 | **WRAPPER** | `NSSearchPathForDirectoriesInDomains` (Foundation C) returns an NSArray that is toll-free bridged to CFArrayRef; only `count`/`lastObject`/`UTF8String` are sent to it | **exact** for valid Unicode, decoding with `JniStringCodec.fromNewStringUtf` | Java: that call + `CFArrayGetCount`/`GetValueAtIndex` + `CFStringGetCString(UTF8)` |
| `_getKeyCodeForChar` GK:631 → GA:901 | OS-CALL | NSMutableDictionary `keyCodeForCharMap` (filled by GlassView3D), TIS | n/a | `gmac_key_code_for_char` |
| `_isKeyLocked` GK:644 | OS-CALL | `+[NSEvent modifierFlags]`; the switch is PURE | switch: exact | Java switch + `gmac_current_modifier_flags` |
| `_getApplicationClassName` GA:1315 | OS-CALL | `+[NSApplicationFX sharedApplication]` (creates NSApp if missing), `NSStringFromClass` | n/a | `gmac_app_class_name` |
| `_getPlatformPreferences` GA:1327 → PS:153 | OS-CALL | NSColor ×44 plus `alternatingContentBackgroundColors`, NSWorkspace, NSScroller, NSAppearance. The 7 map-building upcalls become Java | map: exact | `gmac_app_collect_preferences` → `GmacPrefs` |

#### MacTimer (6 WRAPPER, 2 PURE) — GlassTimer.m kept as the `gmac_timer_*` shim (PB4); only min/max became Java constants

| C function | Verdict | Evidence | Parity | Replacement |
|---|---|---|---|---|
| `_getMinPeriod` / `_getMaxPeriod` GT:392/415 | **PURE** | none: constants 0 and 1000000 | **exact** | Java constants |
| `_initIDs` GT:438 | **WRAPPER** | `CVDisplayLinkCreateWithActiveCGDisplays`, `CGMainDisplayID`, `CVDisplayLinkSetCurrentCGDisplay`, `CVDisplayLinkSetOutputCallback(…, NULL ctx)`, `CVDisplayLinkStart` | **unknown** (see §10) | `gmac_timer_init` (shim, §10); owner follow-up: Java over CoreVideo |
| `_start(R)` GT:253 → GT:162 | **WRAPPER** | `CVDisplayLinkSetOutputCallback(link, cb, ctx)` | **unknown** | `gmac_timer_start_vsync` (shim, §10); owner follow-up: Java, ctx = registry id |
| `_start(R, int)` GT:229 → GT:181 + thread body GT:47 | **WRAPPER** | `pthread_attr_init`/`setinheritsched`/`setschedpolicy(SCHED_FIFO)`/`setschedparam`, `sched_get_priority_max`, `pthread_create`, `pthread_detach`; body: `pthread_setname_np`, `gettimeofday`, `usleep`, `sched_yield`, NSAutoreleasePool around each run | **unknown** | `gmac_timer_start_period` (shim, §10); owner follow-up: a Java upcall stub as the start routine + those libSystem calls + a pool push/pop |
| `_stop` / `_pause` / `_resume` GT:276/307/352 | **WRAPPER** | `CVDisplayLinkIsRunning`, `Stop`, `Start`, `Release`, or flag writes | **unknown** | `gmac_timer_stop` / `_pause` / `_resume` (shim, §10); owner follow-up: Java |
| Display-link callback `CVOutputCallback` GT:114 | WRAPPER (foreign-thread OS callback, as WinTimer's) | CVDisplayLink thread; `Runnable.run` | **unknown** | stays in GlassTimer.m and dials `GmacTimerCallbacks.tick` (§10); owner follow-up: a process-wide Java upcall stub |

#### MacRobot (8 WRAPPER, 2 PURE, 2 OS-CALL)

| C function | Verdict | Evidence | Parity | Replacement |
|---|---|---|---|---|
| `_init` / `_destroy` GR:208/221 | **PURE** | none: an Objective-C object holding one `UInt32 mouseButtons` | **exact** (state machine) | Java field |
| `_keyPress` / `_keyRelease` → GR:108 | **WRAPPER** | `AXUIElementCreateSystemWide`, `AXUIElementPostKeyboardEvent`, `CFRelease`, plus `GetMacKey` | exact by construction | Java + `gmac_key_find_mac`, which replaces `gmac_key_java_to_mac` here: it keeps `GetMacKey`'s found flag (-1 for no key), where `gmac_key_java_to_mac` answers 0, which is also `kVK_ANSI_A` |
| `_mouseMove` → GR:147 | **WRAPPER** | `CGEventCreateMouseEvent`, `CGEventPost`, `CGWarpMouseCursorPosition`, `CFRelease` | exact by construction | Java |
| `_mousePress` / `_mouseRelease` → GR:187/194/81 | **WRAPPER** | `CGEventCreateMouseEvent`, `CGEventPost`, `CFRelease`, plus the location shim | exact by construction | Java + `gmac_mouse_location_flipped` |
| `_getMouseX` / `_getMouseY` → GR:178 | OS-CALL | `+[NSEvent mouseLocation]`, `[[NSScreen screens][0] frame]` | n/a | `gmac_mouse_location_flipped` |
| `_mouseWheel` GR:373 | **WRAPPER** | `CGEventCreateScrollWheelEvent` (variadic, no variadic arguments passed), `CGEventPost`, `CFRelease` | exact by construction | Java, `firstVariadicArg(4)` |
| `_getPixelColor` GR:393 | **WRAPPER** (a 14-call sequence; borderline against "one or two calls") | `CGWindowListCreateImage`, `CGImageGetDataProvider`, `CGDataProviderCopyData`, `CFDataGetBytePtr`, `CGImageGetColorSpace`, `CGColorCreate`, `CGColorSpaceCreateWithName`, `CGColorCreateCopyByMatchingToColorSpace`, `CGColorGetComponents`, `round`, `CGColorSpaceRelease`, `CGColorRelease`, `CFRelease`, `CGImageRelease` | exact by construction, **binding libm `round`** (not `Math.round`). The arm64 binary calls no `round`: clang compiled each `(jint)(round(…))` to one `fcvtas` (4 in `_getPixelColor` of the 1a6788cc4d `libglass.dylib`; nearest with ties away from zero, saturating, NaN to 0), which Java's `(int) round(v)` matches. On x86_64 (the macos_x64 build; the same expression compiled with libglass's `-std=c99 -O3 -mmacosx-version-min=11.0 -arch x86_64`) clang compiles `(jint)(round(x))` to `trunc(x + copysign(0.49999999999999994, x))` (`addsd`, `roundsd $11`), LLVM's lowering of `round` and equal to it for every double, then `cvttsd2si`, which answers 0x80000000 where Java's `(int)` saturates. After `& 0xFF` the bytes differ (0x00 against 0xFF) only for `round(c * 255) >= 2^31` or `c = +Inf`, which a component matched to sRGB from an 8-bit capture does not reach; NaN, -Inf and anything below -2^31 give 0 in both. No code change | Java |
| `_getScreenCapture` GR:455 | **WRAPPER** (sequence) | `CGWindowListCreateImage`, `CGImageGetWidth`/`Height`, `CGColorSpaceCreateWithName`, `CGBitmapContextCreate` (info 0x2002), `CGContextDrawImage`, `CGContextFlush`, `CGContextRelease`, `CGImageRelease`. The 2 upcalls become plain Java | exact by construction | Java |
| `DumpImage` GR:45 | PURE, dead (only called from a comment) | — | — | delete |

#### MacCursor (6 OS-CALL)

| C function | Verdict | Evidence | Replacement |
|---|---|---|---|
| `_initIDs` GC:75 | OS-CALL | NSCursor class cursors, NSImage `initByReferencingFile:`, NSDictionary, NSInvocation (GH:310) | `gmac_cursor_init` |
| `_createCursor` GC:141 | OS-CALL | `-[NSCursor initWithImage:hotSpot:]`; the `attachData` upcall disappears | `gmac_cursor_create` |
| `_set`, `_setCustom` GC:170/192 | OS-CALL | `-[NSCursor set]` | `gmac_cursor_set` / `gmac_cursor_set_custom` |
| `_setVisible` GC:212 | OS-CALL | `+[NSCursor hide/unhide]` | `gmac_cursor_set_visible` |
| `_getBestSize` GC:238 | OS-CALL | `[[NSCursor arrowCursor] image].size` | `gmac_cursor_best_size` |

#### MacPixels (2 OS-CALL, 1 PURE)

| C function | Verdict | Evidence | Parity | Replacement |
|---|---|---|---|---|
| `_initIDs` GP:44 | **PURE** | none: returns `BYTE_BGRA_PRE` (1) | **exact** | Java constant (with the landing-order caveat in §11) |
| `_attachInt` / `_attachByte` GP:123/162 → `attachCommon` GP:91 → `getImage` GP:59 | OS-CALL with a PURE prefix | `getImage` calls `CFDataCreate`, `CGDataProviderCreateWithCFData`, `CGImageCreate`, then `-[NSImage initWithCGImage:size:]`. The bounds checks are PURE | checks: **exact** | Java checks + `gmac_image_create` |

#### MacGestureSupport (1 PURE)

| C function | Verdict | Evidence | Parity | Replacement |
|---|---|---|---|---|
| `_initIDs` GGS:46 | **PURE** | none: JNI ID caching only | exact (no output) | deleted when MacView's gesture upcalls move to a callback table |

#### Helpers

| Helper | Verdict | Notes |
|---|---|---|
| Application delegate methods GA:207-552, GlassRunnable GA:121-162, fullscreen loop GA:834-859 | OS-CALL | AppKit delegate protocol; their upcalls become table slots |
| PlatformSupport PS:95-142 | OS-CALL | KVO, NSNotificationCenter, `nw_path_monitor` (needs blocks) |
| `putColor`/`putColors` truncation PS:336-366 | PURE | stays in C, see §10 |
| `JNI_OnLoad` GA:86 | JNI plumbing | `pthread_key_create` moves to `gmac_app_init` |

### 6. Upcalls

Exception sinks today:
- **report**: `GLASS_CHECK_EXCEPTION` → `Application.reportException(t)`, then clear.
- **describe**: `ExceptionDescribe` (prints `Exception in thread "<name>"` and the stack trace) then clear; this bypasses
  the uncaught-exception handler.
- **none**: the exception is left pending.

All threads are the AppKit main thread unless stated.

| Java target | C site | Thread | Sink today | FFM replacement |
|---|---|---|---|---|
| `Runnable.run` (GlassRunnable) | GA:129 | main (posted from GA:1161, 1179, 158) | report (GA:130) | slot `run_runnable(id)`; skipped when the main loop is not alive (today `jEnv==NULL`, GA:127) |
| `Runnable.run` (launchable, looked up at GA:232/239) | GA:246 | main | describe (GA:247-251); on throw the screen-change observer is **not** registered | slot `run_launchable` → returns 0/1 |
| `Application.notifyWillFinishLaunching` | GA:287 | main | report (GA:290) | `notify_app_event(0)` |
| `notifyDidFinishLaunching` / `WillBecomeActive` / `DidBecomeActive`* / `WillResignActive` / `DidResignActive`* / `WillHide` / `DidHide` / `WillUnhide` / `DidUnhide` | GA:304, 326, 339, 359, 372, 391, 404, 417, 430 | main | report | events 1-9 (* = MacApplication overrides) |
| `notifyOpenFiles(String[])` | GA:461 | main | report; reply is sent afterwards | `notify_open_files` |
| `notifyWillQuit` | GA:536 | main | report; always returns `NSTerminateCancel` | event 10 |
| `MacApplication.setEventThread()` (private) | GA:589 | main (`runLoop:`) | report (GA:592) | `set_event_thread` |
| `Application.setName(String)` | GA:608 | main | report (GA:610) | `set_name` |
| `Boolean.getBoolean` | GA:737 | — | compiled out (`VERBOSE_LOAD`) | delete |
| `MacApplication.notifyApplicationDidTerminate()` (private) | GA:755 | main, after `[NSApp run]` | report (GA:756) | event 11 |
| static `Application.enterNestedEventLoop()` (package-private) | GA:841 | main (from GlassWindow.m:915, GlassViewDelegate.m:1273/1286) | describe (GA:843) | `enter_nested_loop` |
| static `Application.leaveNestedEventLoop(null)` | GA:857 | main (from GlassWindow+Overrides.m:260/291) | **none** | `leave_nested_loop`; Java must report (see Risks) |
| `Runnable.run` (period timer) | GT:67 | "Glass Timer Thread" pthread, SCHED_FIFO max−1 | report (GT:70) | slot `tick(timer_id)` of `GmacTimerCallbacks` (PB4 fallback; GlassTimer.m kept, §10); report, inside the tick's pool (§6 (i)) |
| `Runnable.run` (vsync timer) | GT:136 | CVDisplayLink thread, attached as "PulseTimer-CVDisplayLink thread" | **none** | slot `tick(timer_id)` of `GmacTimerCallbacks` (PB4 fallback; GlassTimer.m kept, §10); none: held until a tick leaves the timer paused, then the thread's uncaught-exception handler (§6 (j)) |
| static `Application.getScaleFactor` / `createPixels` (package-private) | GR:523, 529 | FX | propagates to the caller (returns NULL with the exception pending) | plain Java |
| `Pixels.attachData(long)` (private) | GC:151 | FX | report (GC:160) | gone |
| static `Screen.notifySettingsChanged` | GS:167 | main (observer at GA:254) | report | `screens_changed` |
| `Object.equals`, `Collections.unmodifiableMap`, `notifyPreferencesChanged(Map)` | PS:279, 290, 295 | main (KVO, notifications, `nw_path` on the main queue) | report | `preferences_changed(prefs)`; Java holds `currentPreferences` |
| `Map.put` ×4, static `Color.rgb(IIID)` ×2 | PS:315/329/346/372, 338/360 | main / FX | report; on a `Color.rgb` failure the key is skipped (`_RETURN`) | Java built from `GmacPrefs` |
| static `Class.forName` | GH:86 | any | report | gone |
| static `Application.reportException` | GM:158, 170 | wherever the macro expands | swallowed | `catch (Throwable)` in every upcall target |
| `NewObject`: `Size.<init>`, `Screen.<init>(J+15I+4F)`, `HashMap.<init>`; `GetStaticObjectField` `Boolean.TRUE/FALSE` | GC:282, GS:90, PS:154, PS:312 | FX/main | — | out-params, `GmacScreenInfo`, Java |

**Rule for every upcall target:** catch `Throwable` and apply the same sink as today. For "report", call
`Application.reportException(t)` and swallow anything it throws. For "describe", print `Exception in thread "<name>" `
followed by `t.printStackTrace()`. Then return the slot's default. Nothing may escape.

**Recorded divergences (PB7).** Slot numbers are positions in the 21-slot `GmacAppCallbacks` of `glass_mac_api.h`
(§R3 item 6), not in the §8 sketch.
- (a) REPORT slots 4-15 now report inside the delegate's autorelease pool, before `[pool drain]`; the JNI reported
  after it.
- (b) Slot 18 (`leaveNestedEventLoop`) reports at once, where the JNI left the exception pending.
- (c) `_getDataDirectory` off the main thread, reachable only with `glass.disableThreadChecks`:
  - while detached, the JNI crashed in `NewStringUTF` on a NULL `jEnv` after throwing; FFM reports and answers the
    directory;
  - while attached, the JNI ran `NewStringUTF` through the main thread's `jEnv` on the calling thread (latent bug
    13), which JNI does not allow. `gmac_app_data_directory` uses no `JNIEnv`, so FFM answers the directory and,
    like the JNI, reports nothing.
- (d) An App site while Java is detached from the main thread. That is only after `[NSApp run]` returned and
  `runLoop:` cleared `jEnv`, so no case is reachable in practice. At 1a6788cc4d each site printed `GET_MAIN_JENV`'s
  `GLASS_CALLSTACK` text and then crashed on the NULL env.
  - From slice 1 until slice 11 part 3 the table path printed the same text and then dialled the slot. Slots 1 and
    4-15 ran Java, then crashed in the closing `GLASS_CHECK_EXCEPTION(NULL)` after `[pool drain]`. Slots 17
    (`+enterFullScreenExitingLoop`) and 18 (`+leaveFullScreenExitingLoopIfNeeded`) had no check after the dial: they
    ran `Application.enterNestedEventLoop()` (a whole nested loop) or `leaveNestedEventLoop(null)` and carried on,
    with no crash after.
  - Slice 11 part 3 brought slots 1, 4-15, 17 and 18 to the window rule of g2 §6 (b) (PB14), and this is the
    divergence to accept (owner-run item 10). `GLASS_WARN_IF_MAIN_JAVA_DETACHED` (`GlassMacros.h:209-214`) prints the
    same text where `GET_MAIN_JENV` printed it, and `attachedAppCallbacks()` (`GlassApplication.m:156-160`) then
    answers NULL, so the slot is skipped and no Java runs on a detached main thread. The crash after the print became
    print-and-skip, and the code after the slot, which the crash never reached, now runs:
    - `applicationWillFinishLaunching:` (slots 1 and 4, one print) makes the `PlatformSupport` and sets `started`, as
      the JNI did before its crash, but runs no launchable and does not observe screen parameter changes;
    - slots 5-14 skip only the slot, and what follows their pool runs: the activation check of
      `applicationDidFinishLaunching:`, the reactivation checks of `applicationDidBecomeActive:` and
      `applicationDidResignActive:`, and the `NSTerminateCancel` of `applicationShouldTerminate:`;
    - slot 15 replies `NSApplicationDelegateReplySuccess`;
    - slot 17 sets and clears `isFullScreenExitingLoop` without a nested loop, then reschedules the deferred
      runnables; slot 18 does nothing.
  - The detached test is the attached flag, which `runLoop:` sets just before slot 2 and clears once `[NSApp run]`
    has returned (`GlassApplication.m:571`, :732), where the JNI attached the thread and cleared `jEnv`. The other App
    slots keep their rule (`glass_mac_api.h:207-212`): slots 0 and 19 are skipped silently while the flag is clear,
    as the JNI's `jEnv != NULL` tests skipped them; slot 20 is dialled whether or not it is set, as the JNI made its
    call; slots 2, 3 and 16 run inside `runLoop:` while it is set.
  - Supporting evidence (a headless probe, not checked in, under PB3): with `NSApp` nil and the flag cleared through
    `gmac_test_set_main_java_attached`, the delegate methods of slots 6, 8, 10-15, 17 and 18 each printed the message
    and dialled nothing; with the flag set, each dialled its slot.
- (e) From slice 1 until slice 11 part 3, the three hide exports (`gmac_app_hide`,
  `gmac_app_hide_other_applications`, `gmac_app_unhide_all_applications`) ended with `GlassReportPendingException()`
  (GLASS_CHECK_EXCEPTION on the calling thread's env). It was the static `reportPendingException()` of
  GlassApplication.m, hidden from slice 5 on (`GLASS_INTERNAL`) so that the window exports could share it. Their JNI
  natives had no check. So an exception left pending by a still-JNI upcall that `[NSApp hide:]`,
  `hideOtherApplications:` or `unhideAllApplications:` ran synchronously (the window upcalls of g2 §6) was thrown out
  of the native into the Apple-menu `action()` (MacApplication.java:219-241).
  `GlassMenu.m:175` (g3 L20) then left it pending again, and it was reported at the next `GLASS_CHECK_EXCEPTION` on
  the main thread, or dropped by the next `Call*Method`. FFM reported it through the same
  `Application.reportException` before the export returned: thrown, then reported later or dropped, became reported
  at once. Without the check it would have surfaced in Java at an arbitrary point after the downcall, possibly inside
  `MacApplicationCalls.hide()`'s try, wrapped by `unexpected(t)`.
  - Part 3 deleted the helper and every call to it with the last JNI site (PB15). No upcall can leave an exception
    pending now, so the exports end without a check, as their natives did (`GlassApplication.m:1063-1076`), and the
    divergence is gone.
- (f) Slice 2, an unavoidable transport consequence. In the JNI, `MacCursor._createCursor`'s GLASS_POOL enclosed the
  `Pixels.attachData` upcall. So what NSImage construction in `getImage` autoreleases (4 objects per image on this
  host) drained when `_createCursor` ended, after the NSCursor was made. `gmac_image_create` now opens its own
  GLASS_POOL:
  - On every still-JNI path (GlassMenu.m `_setPixels:`, GlassPasteboard.m `putItemsFromArray`) that pool only nests.
    Both callers hold a GLASS_POOL that encloses the `attachData` upcall into Java, and so `gmac_image_create`.
  - On the FFM path it drains when `gmac_image_create` returns, before `gmac_cursor_create`. This was verified with a
    pool depth of -1 → 0 on a fresh thread and 0 MISSING POOLS.
  - The same applies when slices 7 and 9 flip the menu and pasteboard sites.
- (g) Slice 2, the detached reports of the cursor calls (see the note after the latent bugs).
  - `_getBestSize`: the JNI reported inside its GLASS_POOL, at the `GLASS_CHECK_EXCEPTION` of
    `+[GlassHelper ClassForName:]` (GH:88, or GH:53/71 on the process's first `ClassForName`), before the `Size` was
    made. FFM reports after `gmac_cursor_best_size`'s pool drained, and Java then builds the `Size`.
  - `attachData` on the cursor path: the JNI's `attachCommon` reported at GP:115, inside `_createCursor`'s pool. FFM
    reports after `gmac_image_create`'s pool drained (f). `_createCursor`'s own report comes after its pool in both
    builds (GC:160, and after `gmac_cursor_create`).
- (h) An error raised while Java builds a result after the downcall now propagates to the caller. In practice this is
  only an `OutOfMemoryError`, raised while Java copies a returned block or builds a string, an array or a map. The
  sites below propagate it as itself: Java has no report there, where the JNI's `GLASS_CHECK_EXCEPTION` reported the
  failed allocation and answered null (or the partial result or crash noted per site), and only the downcall sits
  inside the linkage catch (PB22). Line numbers are at 1a6788cc4d.
  - `MacScreenCalls.screens` (the `Screen[]` and each `Screen` of `toScreens`). `staticScreen_getScreens` answered
    null when `NewObjectArray` failed (GS:133-137), or a partial array, with a null element for each `Screen` whose
    `NewObject` failed (GS:90-116, 151-152).
  - `MacApplicationCalls.dataDirectory` (the string `newStringUtf` decodes). `_getDataDirectory` answered null from
    `NewStringUTF` (GA:1288-1292), so `MacApplication.getDataDirectory` fell back to `Application.getDataDirectory`.
  - `MacPreferenceCalls.platformPreferences` (the `HashMap` of `toMap`). `_getPlatformPreferences` answered null when
    the `HashMap` failed (PS:154-158). A failed entry is still reported and left out, as at PS:315-372.
  - `MacDialogCalls.bookmark` (`_getBookmark`). `NewByteArray` (`GlassDialogs.m:307`) was reported at :318, and the
    JNI answered null.
  - `MacDialogCalls.path`, which `toFile` uses for every chooser URL and for `createFromBookmark`. A failed
    `NewString` (`GlassDialogs.m:194`) was reported at the next `GLASS_CHECK_EXCEPTION` (GH:88, in the `ClassForName`
    at :199). The `File` or `MacFileNSURL` constructor (:212, :222) then threw a `NullPointerException` for the NULL
    path, which was reported too (:215, :225), and the element, or `_createFromBookmark`'s answer, was null.
  - The chooser results, which have no JNI counterpart. Only `gmac_dialog_open` answers a block, which
    `fileOpenChooser` copies; `fileSaveChooser` and `folderChooser` build a one-URL array from the `out_url` of
    `gmac_dialog_save` and `gmac_dialog_folder` (`MacDialogCalls.urls`). Each then builds its `Chosen`.
  - `MacPasteboardCalls.answer` (the block copy) and the decoding after it, for `_getName`, `_getUTFs`,
    `_getItemAsRawImage`, `_getItemStringForUTF` and `_getItemBytesForUTF`:
    - `_getName`, `_getItemAsRawImage` and `_getItemBytesForUTF`: the failed `NewStringUTF` (`GlassPasteboard.m:487`)
      or `NewByteArray` (:154 in `ByteArrayFromPixels`, :193 in `ByteArrayFromNSData`) was reported (:490, :155,
      :194), and the JNI answered null;
    - `_getUTFs` (:517-549) and `_getItemStringForUTF` (:698, :705) made each string with `createUTF` (:357-368). A
      failed `NewObject` (:367) was reported at :368; a failed `NewStringUTF` (:365) at :366, and then the
      `NullPointerException` the `String` constructor threw for the NULL charset at :368. Either gave a null string
      or array element;
    - the other failed allocations there were reported and then crashed the VM on the NULL array: `createUTF`'s
      `NewByteArray` (:362, reported at :363) in its `SetByteArrayRegion` (:364), as in L25, and `_getUTFs`'
      `NewObjectArray` (:518, :529, reported at :519, :530) in the `SetObjectArrayElement` on it (:542, :537).
  - `MacClipboardCalls.mimeToUti`, `utiToMime` and `fileReferencePath`. A failed `NewStringUTF`
    (`GlassSystemClipboard.m:48`, :71, :99) was reported at :51, :74 and :102, and the JNI answered null.
  - Unchanged: `_getApplicationClassName` had no check (GA:1319), so its error was thrown in both builds.
  - Unchanged, so not a divergence: the `MacAccessibilityCalls` sites, `actionDescription`, `roleDescription`,
    `unignoredChildren`, `idToMacVariant`, `initEnum` and `enumPointer`, and `glassAccessibleToMacAccessible`. No JNI
    body reported or cleared an exception (`GlassAccessible.m:381-425`, :528-729; `_initEnum`'s `ExceptionCheck`s
    returned with it still pending), so the error propagated in both builds.
- (i) Slice 3, the period timer's "Glass Timer Thread".
  - Report: the `tick` target reports a tick's throw inside the tick's autorelease pool, before `[pool drain]`. The
    JNI reported at GT:70, after the pool had drained at GT:69.
  - Attach: the JNI attached the thread when it started (GT:54), as an unnamed daemon ("Thread-N"). The upcall stub
    attaches it at its first tick, also as an unnamed daemon, so it takes its number later, and the number can
    differ. A thread that has not ticked yet (paused from the start, or before its first period ends) is not a Java
    thread in a thread dump, and a thread that never ticks is never attached.
  - Detach: the JNI detached the thread at the end of `_GlassTimerTask`, after its loop had seen the stop and the
    `GlassTimer` was released (GT:89-101). FFM detaches it when the OS thread exits, after `_GlassTimerTask` has
    returned, so `Thread.exit` runs then and not inside the start routine.
- (j) Slice 3, the display link's thread.
  - Attach and name: the JNI attached the thread at every tick, with "PulseTimer-CVDisplayLink thread" in its
    `JavaVMAttachArgs` (GT:112, :131). While the thread was attached, that attach did nothing. The upcall stub
    attaches each OS thread once, at its first tick, as an unnamed daemon "Thread-N". Before it runs the runnable,
    the `tick` target names the thread "PulseTimer-CVDisplayLink thread" where the JNI's attach named it: at the
    first tick on the thread and at the first tick after a tick that left the timer paused. So each display-link OS
    thread uses up one "Thread-N" number, which the JNI's named attach did not, and every later unnamed thread gets
    a number one higher, the period timer's included. A probe on this JDK shows it: after `Thread-0`, a named JNI
    attach took no number, an unnamed one took `Thread-1` and the implicit attach `Thread-2`. Until the naming early
    in that first tick, a thread dump shows "Thread-N".
  - Detach: never at a pause. After every tick that left the timer paused, the JNI detached the thread (GT:139-149),
    which ran `Thread.exit`, and attached a fresh `java.lang.Thread` at the next tick. FFM detaches only when the OS
    thread exits. While the OS thread lives, its `Thread` object, thread id and thread locals persist across a pause
    and resume, and `Thread.exit` does not run at the pause. A name the runnable gave its thread is kept until the
    next tick after a pause, as with the JNI.
  - Not known on this host: whether CoreVideo keeps the OS thread across `CVDisplayLinkStop` and
    `CVDisplayLinkStart`. Agents may not run a display link (PB3), and the JNI's comments (GT:125-129, :141-142)
    assume a new thread per start. If the thread ends at the stop, FFM detaches it as it exits, and the next start's
    thread is attached as a new "Thread-N" and renamed at its first tick. The owner's thread dumps (§10 experiment)
    settle this.
  - Uncaught-exception dispatch: the `tick` target keeps what the JNI left pending in a thread local of
    `MacTimerCalls`. A tick that throws replaces it, and a tick that does not throw keeps it, as HotSpot's
    `WeakPreserveExceptionMark` did (the "Not latent bugs" note after the Risks). After the runnable, once the start
    export has returned, the target asks `gmac_timer_paused` at every tick, as the JNI read `_paused` at every tick.
    When it answers 1, the target clears the thread local, so the next tick names the thread again, and when an
    exception is kept it calls the thread's `getUncaughtExceptionHandler().uncaughtException(thread, t)`. That is
    what `Thread.dispatchUncaughtException` does inside HotSpot's detach. So the handler gets the same exception
    after the same tick, on the same OS thread, still inside the output callback's autorelease pool: the JNI's
    `DetachCurrentThread` also ran before `[pool release]` (GT:144, :155). When the handler throws, the target
    writes `JavaThread::exit`'s line to fd 2 (`\nException: <class> thrown from the UncaughtExceptionHandler in
    thread "<name>"\n`, both names in modified UTF-8) and drops the throw, as HotSpot did (checked with a JNI probe
    on this JDK). What follows the handler changes: the thread stays attached and no `Thread.exit` runs. Unchanged:
    - A tick before the start export returned does not ask `gmac_timer_paused`. Nothing can have paused the timer
      then (`Timer` holds its lock across `_start`), so the JNI's read of `_paused` answered NO there too.
    - An exception still kept when the OS thread ends reaches no handler. The JNI detached only after a tick that
      left the timer paused.
- (k) Slice 3, the timer registry. Some runnables are now released earlier or later than the JNI released them:
  - A tick that a period timer of a nonzero period begins after its stop returned runs nothing:
    `MacTimerCalls.stop` drops the id once `gmac_timer_stop` has returned. The JNI released the runnable only when
    the thread ended, so such a tick still ran it. Either outcome can come from the race between the stop and the
    loop's `_running` check.
  - The runnable of a vsync timer whose `CVDisplayLinkSetOutputCallback` failed stays registered for the life of the
    process. The JNI took no global reference for it.
  - The runnables of vsync timers and 0 ms period timers are never released, as with the JNI (latent bugs 5 and 19).
- (l) Slice 3, MacTimer's initialization no longer runs `initJavaIDsList`. `GlassDialogs.m:396` still runs it before
  the only use, so a failing `List.add` lookup is reported there rather than at MacTimer's initialization. Since
  slice 8 no native code looks up `List.add`: `initJavaIDsList` is deleted, and Java builds the chosen-files list.
- (m) Slice 3, a failure in a step of the `tick` target itself: the registry lookup, the naming, the thread local or
  `gmac_timer_paused`, which a vsync tick asks at every tick once its start has returned. In practice this is only
  an `OutOfMemoryError`, or the `UnsatisfiedLinkError` of a `gmac_timer_paused` the library lacks (PB13). The JNI
  did these steps in C, where they could not raise. For both kinds of timer the target reports the failure through
  `Application.reportException` on the timer's thread and answers THREW. On a vsync tick, what the runnable threw
  stays held.
- (n) Slice 4, the calls after `MacRobot.destroy` (latent bug 4). The JNI's `_destroy` released the `GlassRobot` but
  `MacRobot` kept `ptr`. So a later `_mouseMove`, `_mousePress`, `_mouseRelease`, `_getMouseX` or `_getMouseY`
  messaged a released object, and a second `destroy` released it again: undefined, and likely a crash. `destroy` now
  keeps the `MacRobotCalls.RobotState`, so those calls go on with the robot's last button state, and a second
  `destroy` does nothing.
- (o) Slice 11, the main thread's attach, once Java calls `gmac_app_create_delegate`:
  - Slot 2's FFM upcall (`set_event_thread`) attaches the main thread to Java, in place of `runLoop:`'s
    `AttachCurrentThreadAsDaemon` (GA:562). So the attach moves past the `pool2` allocation (GA:565) and the rename of
    the `NSThread` to "Main Cocoa (UI) Thread" (GA:567-570). Neither touches Java, so the Java thread (a daemon
    "Thread-N") and its number are unchanged, and the attached flag is still set at the old attach point.
  - A failed attach: the JNI logged "ERROR: Glass could not attach to VM" with `NSLog` (GA:778) and skipped the run
    loop, so the launcher polled `started` forever (GA:1053). It is now fatal inside HotSpot's upcall stub, by a
    reading of `UpcallLinker` that was not run here. The case is unreachable in practice and unverified.
  - From part 1 until part 3, `runLoop:` fetched `jEnv` after that dial with an `AttachCurrentThreadAsDaemon` that
    attached nothing, for the sites that were still JNI. Part 3 deleted `jEnv`, that attach and the "could not
    attach" branch. `runLoop:` now sets the attached flag just before slot 2 (`GlassApplication.m:571`) and clears it
    once `[NSApp run]` has returned (:732).
- (p) Slice 11 part 2, where the `_initIDs` native became a call of `gmac_app_init`: `MacApplication`'s static
  initializer no longer initializes `javafx.scene.paint.Color` and its superclass `Paint`.
  - At 1a6788cc4d it ran the JNI `_initIDs` (MacApplication.java:50), whose `[PlatformSupport initIDs:env]` (GA:1001)
    did a `FindClass` of `Color` (PS:91, through `INIT_CLASS` at PS:32). HotSpot's `FindClass` initializes the class
    it finds, superclass first, so both were initialized inside `MacApplication`'s initialization, on its thread
    (supporting evidence: a JNI probe on this JDK, not checked in, ran both static initializers inside `FindClass`).
  - `MacApplicationCalls.init` now calls only `gmac_app_init`, which touches no Java class. So `Color` and `Paint` are
    initialized at their first use, at the latest when `MacPreferenceCalls.color` calls `Color.rgb` while it builds
    the first preferences map on the main thread (`getPlatformPreferences` from the launchable's `runToolkit`, or the
    `notify_preferences_changed` slot).
  - Needs no acceptance: their static initializers only build constants (`Color`'s named colours) and set Toolkit's
    paint accessor (`Paint`), and Toolkit is initialized before Glass starts.

### 7. Proposed C ABI (what stays native)

New files `N/glass_mac_api.h` and `N/glass_mac_api.m`. The CMake glob picks them up with no CMake edit. Prefix
`gmac_`, by analogy with `gwin_` and `ggtk_`.

```c
#include <stdint.h>
#define GMAC_ABI_VERSION 1u
#define GMAC_OK 0
#define GMAC_ERR_UNSUPPORTED_OS (-1)   /* GA:956 -> Java throws RuntimeException("Unsupported macOS version") */

typedef struct GmacAppCallbacks GmacAppCallbacks;   /* §8 */
typedef struct GmacPrefs GmacPrefs;                 /* §8 */
typedef struct GmacScreenInfo {      /* 56 bytes, align 8 */
    void*   screen;                  /*  0 NSScreen*, +1 retained and never released (GS:80-87, deliberate) */
    int32_t depth;                   /*  8 NSBitsPerPixelFromDepth */
    int32_t x, y, width, height;     /* 12..24 frame, y flipped against screens[0]; Java passes these twice (platform = logical, GS:93-101) */
    int32_t visible_x, visible_y, visible_width, visible_height;  /* 28..40 */
    int32_t resolution_x, resolution_y;                           /* 44, 48 (GS:62-70 math, 25.4f literal) */
    float   scale;                   /* 52 backingScaleFactor; Java passes 1.0f,1.0f,scale,scale */
} GmacScreenInfo;

JFX_EXPORT uint32_t gmac_abi_version(void);
JFX_EXPORT int32_t  gmac_sizeof_app_callbacks(void);  /* 80 */
JFX_EXPORT int32_t  gmac_sizeof_screen_info(void);    /* 56 */
JFX_EXPORT int32_t  gmac_sizeof_pref(void);           /* 48 */
JFX_EXPORT int32_t  gmac_sizeof_color(void);          /* 24 */
JFX_EXPORT void     gmac_free(void* block);           /* every C-allocated out block */
/* 1 if GLASS_ASSERT_MAIN_JAVA_THREAD would throw now: pthread_main_np()==0 && main loop not alive.
   Java checks it on entry, runs the call anyway, then throws RuntimeException("Main Java thread is detached."). */
JFX_EXPORT int32_t  gmac_main_thread_detached(void);

/* MacApplication */
JFX_EXPORT int32_t gmac_app_init(int32_t disable_sync_rendering /* !=0 */);   /* GA:928 + GA:91 pthread_key_create */
JFX_EXPORT void*   gmac_app_create(int32_t taskbar_application /* ==1, as ==JNI_TRUE at GA:640 */,
                                   const GmacAppCallbacks* cb, void* user);   /* end state; copies *cb */
JFX_EXPORT void    gmac_app_set_callbacks(void* app, const GmacAppCallbacks* cb, void* user); /* coexistence with JNI: sites call the table when installed, JNI otherwise */
JFX_EXPORT void    gmac_app_run_loop(void* app);                 /* blocks */
JFX_EXPORT void    gmac_app_finish_terminating(void* app);       /* releases app */
JFX_EXPORT void    gmac_app_enter_nested_loop(void);             /* blocks, re-entrant */
JFX_EXPORT void    gmac_app_leave_nested_loop(void);             /* flag only */
JFX_EXPORT void    gmac_app_invoke_later(int64_t runnable_id);
JFX_EXPORT void    gmac_app_invoke_and_wait(int64_t runnable_id);/* blocks */
JFX_EXPORT int32_t gmac_app_is_trigger_reactivation(void);
JFX_EXPORT void    gmac_app_hide(void);
JFX_EXPORT void    gmac_app_hide_other_applications(void);
JFX_EXPORT void    gmac_app_unhide_all_applications(void);
JFX_EXPORT char*   gmac_app_class_name(void);                    /* malloc'd -[NSString UTF8String] bytes; gmac_free */
JFX_EXPORT void    gmac_app_open_uri(const uint16_t* uri /* NULL = Java null */, int32_t length,
                                     int32_t* out_result);       /* OSStatus or -1; blocks <=1 s */
JFX_EXPORT int32_t gmac_app_screens(GmacScreenInfo** out_block, int32_t* out_count); /* malloc'd; gmac_free */
JFX_EXPORT GmacPrefs* gmac_app_collect_preferences(void* app);  /* NULL when app or PlatformSupport is nil; gmac_free */
JFX_EXPORT int32_t gmac_key_java_to_mac(int32_t java_key_code); /* macCode & 0xFFFF, 0 if not found (GA:1305-1307) */
JFX_EXPORT int32_t gmac_key_code_for_char(uint16_t c);
JFX_EXPORT uint64_t gmac_current_modifier_flags(void);          /* +[NSEvent modifierFlags] */

/* MacCursor / MacPixels / MacRobot */
JFX_EXPORT void  gmac_cursor_init(void);
JFX_EXPORT void* gmac_cursor_create(void* nsimage, int32_t hot_x, int32_t hot_y); /* releases nsimage (GC:156); NSCursor +1, never released */
JFX_EXPORT void  gmac_cursor_set(int32_t type);
JFX_EXPORT void  gmac_cursor_set_custom(void* nscursor);
JFX_EXPORT void  gmac_cursor_set_visible(int32_t visible /* ==1 */);
JFX_EXPORT void  gmac_cursor_best_size(int32_t w, int32_t h, int32_t* out_w, int32_t* out_h);
JFX_EXPORT void* gmac_image_create(const uint8_t* bgra_pre /* offset already applied */,
                                   int32_t width, int32_t height); /* GP:59; +1 NSImage or NULL; copies the bytes */
JFX_EXPORT void  gmac_mouse_location_flipped(double* out_x, double* out_y);       /* GR:178 */
/* _keyPress/_keyRelease (GR:111), in place of gmac_key_java_to_mac: macCode, or -1 if not found (0 is kVK_ANSI_A) */
JFX_EXPORT int32_t gmac_key_find_mac(int32_t java_key_code);
```

**Ownership and lifetime**
- `app` is retained (+1) by `gmac_app_create` and released by `gmac_app_finish_terminating`.
- Runnable ids are entries in a Java registry. Java removes the entry when the runnable runs. When the main loop is
  not alive the runnable is not run, so its entry leaks, exactly as the global ref leaks today.
- UTF-16 `uri` and pixel buffers are only borrowed for the call; C copies them (`stringWithCharacters`,
  `CFDataCreate`).
- Heap arrays passed to `gmac_image_create` may use `critical(true)`: the pin lasts as long as the current
  `GetPrimitiveArrayCritical` section.
- **Use `MemorySegment.ofBuffer(buf.duplicate().clear())` for direct buffers, not `ofBuffer(buf)`.** The JNI used the
  buffer's base address and capacity and ignored position and limit.

**Strings**
- C to Java is always UTF-8 from `UTF8String`, decoded with `JniStringCodec.fromNewStringUtf` to stay byte-for-byte
  equal to `NewStringUTF`.
- Java to C is UTF-16 (`const uint16_t*` plus length).

**Downcall rules**
- No `critical(true)` on any `gmac_app_*`. `run_loop`, `enter_nested_loop`, `invoke_and_wait` and `open_uri` block
  and/or call back into Java.
- C bools are `int32_t` in this ABI: Objective-C `BOOL` is `bool` on arm64 and `signed char` on x86_64.

### 8. Callback tables

```c
enum { GMAC_APP_WILL_FINISH_LAUNCHING, GMAC_APP_DID_FINISH_LAUNCHING, GMAC_APP_WILL_BECOME_ACTIVE,
       GMAC_APP_DID_BECOME_ACTIVE, GMAC_APP_WILL_RESIGN_ACTIVE, GMAC_APP_DID_RESIGN_ACTIVE,
       GMAC_APP_WILL_HIDE, GMAC_APP_DID_HIDE, GMAC_APP_WILL_UNHIDE, GMAC_APP_DID_UNHIDE,
       GMAC_APP_WILL_QUIT, GMAC_APP_DID_TERMINATE };            /* 0..11 */

struct GmacAppCallbacks {       /* 80 bytes = 10 pointers; every slot on the AppKit main thread; a NULL slot is a no-op */
    void    (*set_event_thread)(void* user);                                          /*  0 GA:589  report   */
    void    (*set_name)(void* user, const char* utf8);                                /*  8 GA:608  report; bytes valid for the call */
    int32_t (*run_launchable)(void* user);                                            /* 16 GA:246  describe; 0 ok, 1 threw */
    void    (*notify_app_event)(void* user, int32_t event);                           /* 24 GA:287..536, :755 report */
    int32_t (*notify_open_files)(void* user, const char* const* utf8, int32_t count); /* 32 GA:461  report; 0 success, 1 failure reply */
    void    (*run_runnable)(void* user, int64_t runnable_id);                         /* 40 GA:129  report   */
    void    (*enter_nested_loop)(void* user);                                         /* 48 GA:841  describe */
    void    (*leave_nested_loop)(void* user);                                         /* 56 GA:857  (none today) */
    void    (*screens_changed)(void* user);                                           /* 64 GS:167  report   */
    void    (*preferences_changed)(void* user, const GmacPrefs* prefs);               /* 72 PS:273  report; prefs valid for the call */
};

typedef struct GmacColor { int32_t r, g, b, reserved; double a; } GmacColor;  /* 24: (int)(c*255.0f) as PS:340; a=(double)alpha; nil colour -> 0s */
enum { GMAC_PREF_COLOR, GMAC_PREF_COLOR_ARRAY, GMAC_PREF_STRING, GMAC_PREF_BOOLEAN };
typedef struct GmacPref {        /* 48 bytes, align 8; entries in PlatformSupport insertion order */
    const char* key;             /*  0 static ASCII, process lifetime */
    int32_t     kind;            /*  8 */
    int32_t     count;           /* 12 COLOR_ARRAY length | BOOLEAN 0/1 */
    GmacColor   color;           /* 16 COLOR */
    const void* value;           /* 40 STRING: const char* (NULL = Java null) | COLOR_ARRAY: const GmacColor* */
} GmacPref;
struct GmacPrefs { int32_t count; int32_t reserved; const GmacPref* entries; };  /* 16 + entries in the same block */
```

**Preferences map rules (Java side)**
- Today's map has 51 entries: 44 colours, 1 colour array, the control tint when it matches, 2 accessibility booleans,
  the scroller style, and 2 network-path booleans. This matches `getPlatformKeys` (J/MacApplication.java:459-513).
- Insert into the `HashMap` in C order, so iteration order is unchanged when keys share a bucket.
- When `Color.rgb` throws `IllegalArgumentException`: report it and skip that key. For the colour array, skip the
  whole key.

**Stub arena for the app table.** Use one shared arena owned by `MacApplication` and never close it. Glass mac is one
per process (`Application.run` throws if called twice), and slots can still fire after `finishTerminating`
(`DID_TERMINATE` at GA:755, deferred runnables). Document this as a deliberate exception to the per-object arena rule.

**Timer table: only if the owner rejects deleting the timer.**
`typedef struct { void (*tick)(void* user, int64_t timer_id); } GmacTimerCallbacks;` (8 bytes). It would be called on
the CVDisplayLink thread or the "Glass Timer Thread", with the report sink on the period thread and no sink on the
CVDisplayLink thread. The stub must live in a shared arena that lasts the process.

### 9. Deletion candidates (cleared the gate or exact by construction)

There is one copy of each, glass/mac only. Estimated total ≈1,380 lines.

| Candidate | Lines | Parity | Java counterpart in tree | Parity test |
|---|---:|---|---|---|
| **D1 MacTimer**: GT + GlassTimer.h, `getTimeMicroseconds` (ProcessInfo.m:185-195), GA:1183-1204, the `GlassDisplayLink` global, GlassScreen.m:30 import. **Not deleted in this story (PB4):** slice 3 takes the fallback, and GA:1183-1204 is now `gmac_screen_video_refresh_period`, kept native permanently (§10). The Java replacement is an owner follow-up after the timer experiment | ≈556 | min/max **exact**; the rest **unknown** (§10) | pattern: `WinGlassNative` winmm timer with one process-wide stub, `WinTimerJitterTest` | min/max constants; vsync tick and pause/resume sequence; thread names; autorelease growth (owner-run) |
| **D2 MacRobot** (all of `GlassRobot.m`; `getMousePosFlipped`, the pool pair and `GetMacKey` stay as exports, §10) | ≈534 of 544 | **exact by construction**; `_init`/`_destroy` state exact | pattern: WinRobot (Java builds `INPUT`); `CGNative` binds some CG symbols but is package-private in `coretext` | headless: build every `CGEvent` without posting and assert type, location, `kCGMouseEventButtonNumber`, scroll delta. Owner-run: CGEventTap recording and screen-capture A/B against a JNI golden |
| **D3 MacPixels** `_initIDs` + bounds checks + `attachCommon` | ≈115 of 190 | **exact** | — | table of (w, h, offset, length, array / direct buffer with position≠0) cases: NSImage produced or not, A/B against JNI while both exist |
| **D4 GlassGestureSupport.m** | 122 | exact (no output) | — | none needed; scheduled with MacView |
| **D5 `_getDataDirectory`**. **Not deleted (PB2):** kept native as `gmac_app_data_directory` (§10); the Java replacement is an owner follow-up after the owner's A/B | 23 | ruled **exact** (bytes through `JniStringCodec`), but not provable under PB2 (§10) | `JniStringCodec`, CF binding pattern from `CFNative` | A/B on the host; child-JVM probe with a non-BMP `HOME` (inconclusive if NSSearchPath ignores `HOME`; say so) |
| **D6 nested-loop return value** (GA:55, 815-819, 828-830) | ≈12 | **exact** | — | unit: leave(X), leave(null) → X; leave(X), leave(Y) → Y; cleared after return |
| **D7 `_isKeyLocked` switch** | ≈15 | **exact** | — | `VK_CAPS_LOCK` → flag `0x10000`; any other code → `KEY_LOCK_UNKNOWN` with no native call |

### 10. Kept native despite being pure, or parity unknown

- **`GlassTimer.m`, kept as the `gmac_timer_*` shim (PB4, `PARITY: unknown`).** The owner-run timer experiment is
  not available, so slice 3 took the fallback. The shim keeps these in C:
  - the "Glass Timer Thread" pthread (`SCHED_FIFO`, second-highest priority) and its period loop;
  - the display link's output callback, and the NULL-context start in `gmac_timer_init`;
  - the autorelease pool around each tick.

  It exports `gmac_timer_init`, `_start_period`, `_start_vsync`, `_stop`, `_pause`, `_resume` and `_paused`. It dials
  the one-slot `GmacTimerCallbacks`, whose `tick` target in `MacTimerCalls` holds the runnables by id (§6 (i)-(l)).
  `getTimeMicroseconds` (ProcessInfo.m), the `GlassDisplayLink` global and `gmac_screen_video_refresh_period` stay
  with it.

  Not kept: under PB2, `_getMinPeriod` and `_getMaxPeriod` moved to the Java constants `MacTimerCalls.MIN_PERIOD`
  (0) and `MAX_PERIOD` (1000000 ms), with no `gmac_*` export (§9 D1). They are exact by construction: the JNI
  bodies (GT:392-431) returned those constants from a `GLASS_POOL` that autoreleased nothing, and Java keeps their
  detached report.

  Follow-up for the owner, after the timer experiment: the Java replacement, in which Java binds CoreVideo and
  libSystem's pthread calls (§9 D1, slice 3 "If it passes"). These differences decide it:
  1. The JNI attached the CVDisplayLink thread as "PulseTimer-CVDisplayLink thread" (GT:112). An FFM implicit attach
     names it "Thread-N", so Java renames it, as the shim's `tick` target already does (§6 (j)).
  2. The JNI detached on pause (GT:144). FFM detaches only when the thread exits, which the shim already carries
     (§6 (j)).
  3. `gmac_timer_init` starts the link with a NULL context (GT:459-464), so idle ticks never touch the JVM. A Java
     stub would attach the thread and make 60-120 empty upcalls per second even in full-speed or period mode.
  4. The pool around `Runnable.run` (GT:65-69, GT:119-155) is required because `_submitForLaterInvocation`
     autoreleases on that thread. From Java that means `objc_autoreleasePoolPush/Pop`: exported by libobjc but not
     declared in a public SDK header. The alternative is a two-line Objective-C shim.

  Experiment: run the JNI build (1a6788cc4d) and the Java build side by side with `jcmd Thread.print` after start,
  pause and resume; tick-interval histograms; and `leaks`/RSS over 10⁵ pulses. The thread dumps also show whether
  CoreVideo keeps the display link's OS thread across a pause and resume (§6 (j)).
- **`gKeyMap` / `GetMacKey`.** Pure data plus TIS calls, but the same table drives native key events (GK:419, 442). A
  Java copy would be a second table.
- **`_isTriggerReactivation` and the nested-loop flag.** Pure, but the state belongs to native delegate and loop
  code.
- **Objective-C calls with C-callable alternatives (`PARITY: unknown`):**

  | Today | Possible C-callable replacement | Problem or experiment |
  |---|---|---|
  | `+[NSEvent modifierFlags]` | `CGEventSourceFlagsState(kCGEventSourceStateCombinedSessionState)` | toggle Caps Lock and compare |
  | `getMousePosFlipped` | `CGEventGetLocation(CGEventCreate(NULL))` | sample both on multiple monitors and at fractional points |
  | `NSCursor hide/unhide` | `CGDisplayHideCursor` / `CGDisplayShowCursor` | the counters may differ |
  | `+sharedApplication` class name | `NSApp` data symbol + `object_getClassName` | fails the gate: `+sharedApplication` creates NSApp when it is nil |

- **Screen-geometry and colour truncation arithmetic** (GS:62-120, PS:336-366). Pure, but it works on values that
  only Objective-C can supply, and keeping it in C makes it exact by construction. Moving it removes no OS call.
- **`_getDataDirectory`, kept as `gmac_app_data_directory`** (§9 D5). PB2 by construction fails: `-count`,
  `-lastObject` and `-UTF8String` have no identical C functions (`CFArrayGetCount`/`CFArrayGetValueAtIndex` and
  `CFStringGetCString` are different calls), and the `GLASS_POOL` around the autoreleased `NSArray` would need
  libobjc's undeclared `objc_autoreleasePoolPush`/`Pop`. A headless A/B would need a JNI-side golden captured first.
  Follow-up: Java replacement after the owner's A/B.
- **`staticScreen_getVideoRefreshPeriod`, kept permanently as `gmac_screen_video_refresh_period`** (PB4).
- **`gmac_pool_push` / `gmac_pool_pop`** (slice 4): `GLASS_POOL_PUSH` and `GLASS_POOL_POP`, that is libglass's own
  per-thread `GLASS_POOL` depth counter and its `NSAutoreleasePool`. Java's robot bodies need them to be exact: each
  body opens and drains the pool at the same depth as the JNI's `GLASS_POOL_ENTER`/`EXIT`, and carries latent bug 1
  by not popping where the JNI returned inside the pool. libobjc's `objc_autoreleasePoolPush`/`Pop` would not keep
  the counter.
- **`gmac_key_find_mac`** (slice 4): `GetMacKey` with its found flag, which `_keyPress`/`_keyRelease` test before
  posting (GR:111). `gmac_key_java_to_mac` loses the flag, because the 0 it answers for no key is also `kVK_ANSI_A`.
  It reads the same `gKeyMap` as native key events (the `gKeyMap` bullet above).

### 11. Build and test touchpoints

**Build**
- `B/mac.cmake:159-167` (the `glass` block) and `:130-154` (`add_jfx_library` gives JDK includes and `HEADERS_DIR` to
  every target; AC 9 needs a JNI opt-in like `win.cmake`'s).
- **Generated-header traps:**
  - Making `MacGestureSupport._initIDs` non-native stops `com_sun_glass_ui_mac_MacGestureSupport.h` from being
    generated. GlassViewDelegate.m (9 uses) and GlassView3D.m:31 then fail to compile, unless `@Native` is added to
    the 6 constants in the same change.
  - The `#import`s of `…MacTimer.h`, `…MacRobot.h`, `…MacCursor.h`, `…MacPixels.h`, `…MacApplication.h` (GA:27,
    GK:27) must go in the same change that removes each class's last native.
  - `Cursor.h`, `Pixels_Format.h`, `Timer.h` and `KeyEvent.h` persist because those classes have `@Native` fields.

**Existing tests**
- None in `modules/*/src/test` touch the in-scope classes. `MacPasteboardShim` and
  `tests/system/.../MacPasteboardTest.java` exist but are out of scope.
- Behaviour coverage is all system or robot tests: `test/robot/javafx/scene/RobotTest.java`, `KeyLockedTest`,
  `ShortcutKeyboardTest`, `test/javafx/stage/NestedEventLoopTest`, `NestedEventLoopPlatformExitTest`, `ScreenTest`,
  `MultipleScreensTest`, `test/javafx/scene/ImageCursorGetBestSizeTest`, `test/javafx/application/PlatformTest`,
  `RobotTestBase`/`VisualTestBase`. **All of these are owner-run.**

**Test hooks to add**
- `gmac_abi_version` and the `gmac_sizeof_*` functions, with Java layout tests (80/56/48/24/16).
- `gmac_test_fire_app_callback(slot)` with fixed patterns, after the Windows precedent:
  - `set_name("A\xF0\x9F\x98\x80B")` to pin `NewStringUTF` decoding.
  - Open-files with a NULL entry.
  - A synthetic `GmacPrefs` containing r=256, to exercise the `Color.rgb` failure path.
- Child-JVM probes with `-XstartOnFirstThread`. There `pthread_main_np()==1`, so the assert cannot throw, and TIS
  calls are on the main thread. No `[NSApp run]` and no window. Use them for the Pixels table, all VK codes through
  `gmac_key_java_to_mac`, the data-directory bytes, and the timer constants. **Verify first that nothing in those
  paths activates the app.**
- A test-compiled C probe pinning the SDK constants Java will hard-code, as the Monocle `egl_vendor_stub.c` precedent
  does: `sizeof(pthread_attr_t)` 64, `sched_param` 8, `SCHED_FIFO` 4, `PTHREAD_EXPLICIT_SCHED` 2, the `kCGEvent*`
  values, `kCGHIDEventTap` 0, bitmap info 0x2002, `NSApplicationSupportDirectory` 14, `NSUserDomainMask` 1.

### Risks

1. **The JNI bootstrap is shared by all 155 exports.**
   - Removing `JNI_OnLoad` leaves `GlassThreadDataKey` at 0, and `pthread_getspecific(0)` reads someone else's key.
   - Removing `MacApplication._initIDs` leaves `jApplicationClass` NULL, and every `GLASS_CHECK_EXCEPTION` that sees
     an exception then crashes.
   - Removing the class loader from `_initDelegate` breaks every `ClassForName`.
   - Removing the attach in `runLoop:` leaves `jEnv` NULL for every `GET_MAIN_JENV`.

   Keep `_initIDs` and the class loader as JNI until the end (Windows kept `InitExceptionReporting` in a surviving
   JNI native for the same reason).
2. **`FindClass` reached from inside an FFM downcall resolves against the wrong class loader** (Windows s3-61).
   Non-boot lookups at risk: GS:128 (`com/sun/glass/ui/Screen`) and PS:32 (`javafx/scene/paint/Color`, safe only
   while it runs inside the JNI `_initIDs`).
3. **Landing order for `MacPixels._initIDs`.** It is the only place `jPixelsAttachData` is set, and the still-JNI
   GlassMenu.m:279 and GlassPasteboard.m:270 use it. Cache it lazily there (`GetObjectClass`) before the Java change,
   as Windows slice 3 did.
4. **`GLASS_ASSERT_MAIN_JAVA_THREAD` leaves a pending exception and continues.** Inside an FFM downcall a pending JNI
   exception is undefined behaviour, so it must become `gmac_main_thread_detached` plus a Java throw after the call.
   The "main loop alive" flag replaces `jEnv != NULL`.
5. **Display-link ownership couples two classes.** `staticScreen_getVideoRefreshPeriod` returns 0 until MacTimer's
   class init has created *and started* the link, and Quantum relies on that ordering (`QuantumToolkit.java:359` then
   `:392`). Move both into Java together, and do not force MacTimer initialisation from `getVideoRefreshPeriod`.
6. **Java access to upcall targets.** Several are private or package-private: `setEventThread`,
   `notifyApplicationDidTerminate`, `Pixels.attachData`, `Application.enterNestedEventLoop`/`leaveNestedEventLoop`/
   `createPixels`/`getScaleFactor`. The stubs need targets inside MacApplication, or `MethodHandles.privateLookupIn`
   within the module.
7. **Exception-sink differences that cannot be fully reproduced.** GA:857 and GT:136 leave exceptions pending today:
   they are cleared by the next JNI call or dispatched on detach. FFM must pick one behaviour (report is proposed) and
   document it. Quantum's pulse runnable catches `Throwable` itself (`QuantumToolkit.java:352-358`), so GT:136 is moot
   in production.
8. **FFM details.**
   - `CGEventCreateScrollWheelEvent` is variadic: use `firstVariadicArg(4)` with zero variadic arguments, and verify
     `Linker` accepts an index equal to the argument count.
   - `CGPoint` and `CGRect` are passed by value.
   - `Boolean` and `bool` parameters (`AXUIElementPostKeyboardEvent`, `CVDisplayLinkIsRunning`, the `NSSearchPath…`
     `BOOL`) take `JAVA_BOOLEAN`.
   - `CGWindowListCreateImage` is obsoleted in the macOS 15 SDK; bind it lazily.
   - CVDisplayLink is deprecated in macOS 15.
9. **Coexistence.** During the transition, upcalls must not run Java on the main thread before the JNI attach in
   `runLoop:`. The `GmacAppCallbacks` stubs must outlive termination. The facade size budget (≤900 lines) probably
   needs `MacGlassNative` plus `Mac*Calls` aggregate classes.
10. **Pending JNI exceptions at an FFM downcall's return.** HotSpot does not throw a JNI exception left pending inside
    an FFM downcall when the downcall returns. It surfaces later, at an arbitrary instruction; the s1r2
    pending-exception probes show this. Rule for slices 5, 6 and 9, and for any export added before slice 11 retires
    the unchecked JNI upcall sites: every gmac_* export that runs AppKit synchronously on the main thread, and can
    therefore reach such a site, ends with `GlassReportPendingException()` (`GlassApplication.m`; the static
    `reportPendingException()` until slice 5 shared it, §6 (e)) after its pool drains. Where its JNI native
    ended with `GLASS_CHECK_EXCEPTION(env)`, that is the same line. Where the native had no check, record
    'thrown -> reported' as a divergence. The helper takes the calling thread's env through `GetEnv`, which is what
    the JNI's `env` parameter was. It does not use `jEnv`, which `runLoop:` clears once `[NSApp run]` returns. Slice 1
    applies the rule to `gmac_app_run_loop`, `gmac_app_finish_terminating` and `gmac_app_enter_nested_loop` (the JNI
    tails at 1a6788cc4d GlassApplication.m:1063, :1104, :1124) and to the three hide exports (§6 (e)). Two exports
    need nothing, by construction. `gmac_app_leave_nested_loop`: its JNI tail check only covered the `NewGlobalRef`
    that D6 moved to Java, and it runs no AppKit. `gmac_app_open_uri`: its JNI tail check only covered the
    detached-thread assertion, which Java now reports or throws on `out_completed`, and its semaphore wait does not
    process events. Slice 11 part 3 retired the last JNI upcall site and deleted the helper with every call to it
    (Slice 11, "As built, part 3").

**Latent bugs to carry over verbatim (do not fix while migrating)**
1. Early returns inside `GLASS_POOL_ENTER` leave the pool counter raised for good, so later pools never drain: GA:1355
   (`_openURI` → -1), GR:482/485/521/526/533 (which also leak `screenImage`). GC:280 is gone since slice 2, as a
   consequence of the transport rather than a fix. Its only trigger was a failing `ClassForName` of `Size`, for
   example a `ClassNotFoundException`. Java now builds the `Size`, so no lookup can fail, and `gmac_cursor_best_size`
   has no early return. GR:521 is unreachable since slice 4 for the same reason: its only trigger was a failing
   `ClassForName` of `Application`, and `MacRobotCalls.screenCapture` calls `Application.getScaleFactor` and
   `createPixels` directly. Its returns at GR:482, :485, :526 and :533 skip `gmac_pool_pop` and `CGImageRelease`, as
   the JNI's did.
2. GR:427 calls `CFRelease(data)` when `data` is NULL. GR:436 dereferences a possibly NULL corrected colour.
3. GR:171 passes `index` (highest set bit + 1) as the `CGMouseButton`, so other-button drags report the wrong button.
4. `MacRobot.destroy` does not reset `ptr` → use-after-free (GR:221). Since slice 4 the robot's state is a Java
   object (`MacRobotCalls.RobotState`) that `destroy` keeps, so the calls after it go on with that state, where the
   JNI's messaged a released object.
5. GT:288-290 releases `GlassDisplayLink` without setting it to NULL (only reachable after toolkit exit).
   Display-link timers and their global refs are never freed (GT:162-178).
6. Timer start races: the thread can start before `_runnable` is set, and its `_running=YES` can overwrite a racing
   stop (GT:58/198-210). GT:212 leaks the object when `pthread_create` fails.
7. GA:445-448 returns without draining the pool and without `replyToOpenOrPrint`.
8. GA:755 reads `self->jApplication` after `_finishTerminating` released the delegate, on the `-XstartOnFirstThread`
   path. JNI fallback only: the slice-1 table path in `runLoop:` dials `notify_did_terminate` without touching
   `self`.
9. GA:829: leaving the nested loop twice leaks the first global ref.
10. PS never releases `currentPreferences`; `int count = [colors count]` truncates (PS:354).
11. `_getKeyCodeForChar` reads an NSMutableDictionary off the main thread without locking.
12. `_submitForLaterInvocation` runs with no autorelease pool on the calling thread.
13. GA:1288 passes the main thread's `jEnv`. Gone since slice 1, as a consequence of the transport rather than a
    fix: `_getDataDirectory` has no JNI body left, and `gmac_app_data_directory` uses no `JNIEnv` (§6 divergence
    (c)).
14. `_openURI(null)` puts nil into an NSArray literal: an Objective-C exception that crashes through the native frames.
15. Debug-only `assert`s (`GET_MAIN_JENV`, GC:131/177) are compiled out in release.
16. The three touch-event method IDs in MacGestureSupport are dead (GGS:96-120).
17. The preferences change check never sees an unchanged map: the map holds a `Color[]`
    (`alternatingContentBackgroundColors`), which `HashMap.equals` compares by identity, so `updatePreferences`
    notifies on every KVO, notification and path-monitor callback. Slot 20 keeps the same `equals`
    (`MacApplicationNativeTest.aMapHoldingAColourArrayNeverEqualsTheKeptOne`).
18. `gmac_cursor_init` (GC:89-131 at 1a6788cc4d, the body of `MacCursor._initIDs`) never releases the two
    `initByReferencingFile:` images or the four `[[NSCursor alloc] initWithImage:hotSpot:]` resize cursors, which the
    cache retains a second time: a one-off leak per process. The cache is built with the nil-terminated
    `arrayWithObjects:`, so an entry that came out nil would end the array there, and `gmac_cursor_set` would then
    raise `NSRangeException` for that type and every later one. On this host the two HIServices cursor directories
    exist and the cache is complete.
19. `_stop`, `_pause` and `_resume` tell the vsync timer by `_period == 0` (GT:285, :316, :361 at 1a6788cc4d), but
    a period of 0 ms is legal: `getMinPeriod` answers 0, and Quantum starts the timer with `PULSE_INTERVAL`, which is
    `1000 / javafx.animation.pulse` and so 0 above 1000, when the display link reports no refresh period
    (`QuantumToolkit.java:214`, `:401`). Such a period timer is taken for the vsync one. Stopping it stops and releases
    the display link instead of ending its thread, which then ticks for good, and pausing or resuming it stops or
    starts the link instead. `gmac_timer_stop`, `gmac_timer_pause` and `gmac_timer_resume` keep the test.
20. The robot never checks what it creates for NULL (GR:100-102, :116-119, :171-174, :381-383 at 1a6788cc4d). The
    results of `CGEventCreateMouseEvent`, `AXUIElementCreateSystemWide` and `CGEventCreateScrollWheelEvent` go
    straight to `CGEventPost` or `AXUIElementPostKeyboardEvent` and then to `CFRelease`, so a failed creation crashes
    in `CFRelease(NULL)`.
21. `getMousePosFlipped` takes `[[NSScreen screens] objectAtIndex:0]` (GR:181 at 1a6788cc4d). With no screen,
    `_getMouseX`, `_getMouseY`, `_mousePress` and `_mouseRelease` raise an `NSRangeException` that nothing catches.
    `gmac_mouse_location_flipped` keeps it.
22. `_getPixelColor` reads the first four bytes of the capture as one host-order pixel with alpha in the top byte
    (GR:415-423 at 1a6788cc4d), whatever bits per pixel and bitmap info `CGWindowListCreateImage` gave the image. The
    only code that printed them, `DumpImage`, is called only from a comment (GR:408).

**Not latent bugs: the detached cursor calls.** After the assertion at GC:147 and GC:245 (1a6788cc4d), the JNI went on
making JNI calls with its exception pending. The JNI specification allows only the exception and release functions
then, but HotSpot runs the call: `JNI_ENTRY`'s `WeakPreserveExceptionMark` sets the pending exception aside and
restores it afterwards, unless the call left a new one. The slice-2 review's pending-exception probe shows this on
JDK 27.0.1. With a `RuntimeException` pending, `GetMethodID` answers an ID, `CallVoidMethod` runs a private
superclass method and `CallStaticVoidMethod` runs. `NewStringUTF` and `Class.forName` answer non-NULL, and the
original exception is still pending afterwards. So, off the main thread while it was detached:
- `_createCursor` ran `attachData` (GC:151). `attachCommon` reported its own detached error (GP:94, :115), the cursor
  was made from the image, and `_createCursor` reported its detached error at GC:160. A throw from `attachData`
  replaced that error. `MacCursorCalls.createCursor` does the same.
- `_getBestSize` reported at GH:88 and returned the `Size`. There was no NSLog, and the pool was balanced.
  `MacCursorCalls.bestSize` reports after `gmac_cursor_best_size` and returns the `Size` (§6 (g)).

### Recommendation

This order differs from the story's (MacApplication first). It is a default, not a mandate. The owner decides commit
structure.

1. **Do not build an FFM facade for `GlassTimer.m` or `GlassRobot.m`.** Java binds CoreVideo, libSystem, CoreGraphics,
   HIServices and CF directly. Robot's parity is exact by construction. Timer's is unknown until the §10 experiment is
   run, and the 2-line pool shim versus libobjc is the owner's choice. If the timer experiment fails, fall back to the
   one-slot `GmacTimerCallbacks`.
2. **C-only preparation, coexisting with the JNI.**
   - Add `glass_mac_api.{h,m}` alongside the JNI code.
   - Cache `jPixelsAttachData` lazily in GlassMenu.m and GlassPasteboard.m.
   - Add `@Native` to the six MacGestureSupport constants (Java agent only).
3. **Then, one class at a time.** Run `mvn -pl modules/javafx.graphics test` after each step; robot and
   `tests/system` runs are owner-run.
   1. MacPixels + MacCursor (`gmac_image_create`, `gmac_cursor_*`).
   2. MacRobot (Java plus four small exports, §10).
   3. MacTimer together with `staticScreen_getVideoRefreshPeriod`.
   4. MacApplication: `gmac_app_*`, `GmacAppCallbacks`, Java preferences and screen builders, keeping the `_initIDs`
      and class-loader bootstrap natives as JNI.
   5. MacGestureSupport, together with MacView's callback table.
4. **Last:** delete the bootstrap (`JNI_OnLoad`, `runLoop:` attach, `jApplicationClass`, `glassClassLoader`) when no
   other glass/mac class has natives left.
5. **Keep the two kinds of work reviewable apart:** the migrations (behaviour-neutral) and the Java reimplementations
   (D1, D2, D3, D5, D6, D7, each carrying its parity evidence).

**Owner-run items**
- The robot suite and `tests/system` on the JNI baseline and after each step.
- CGEventTap and screen-capture goldens; these need Accessibility and Screen Recording permission prompts.
- Pulse-timer thread dumps and jitter measurements.
- A preferences map and screen list golden captured with the toolkit running.

## Group g2: Window, View (+ GestureSupport)

**Scribe's corrections** (details in §R2):

- §4 "Imported symbols: not measured. No binary exists": the baseline build of `1a6788cc4d` has one (the header).
  §R1 records its `otool -L` and `nm -u` summary.
- §2: the "53 textual" `Call*Method` sites rightly exclude `GlassWindow.m:74`, which sits in the commented-out
  `Sys_out_sprintf` (§R4). The counts leave out the 4 `Integer.<init>` `NewObject` sites that build the
  `View.Capability` keys (`GlassView3D.m:116`, `GlassViewCGL.m:98,162`, `GlassViewMTL.m:49`). They go with
  `GmacViewCaps`, and g0 lists them.
- `GmacViewCaps` uses `int64_t` members here and `void*` in g0, with the same 40-byte layout. The constants plan
  (`gmac_constant(i)`) differs from g0's `constants/` directory. §R3 decides both.
- `MacGestureSupport._initIDs` is also claimed by g1 (§R4).

The audit ran at HEAD `1a6788cc4d` and is read-only. Paths in the tables are relative to these roots:
- **N** = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/`
- **J** = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/glass/ui/`
- **CM** = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/native/mac.cmake`

`GlassGestureSupport.m` / `MacGestureSupport` is included. Its `_initIDs` is the only owner of the gesture
`jmethodID`s that `GlassViewDelegate.m` calls, so it cannot be migrated separately from MacView.

### 1. Verdict

**Migrate this slice; do not delete it. Give it a plain C ABI and delete 7 of its 51 exports.**
- **44 of 51 `JNIEXPORT`s are `OS-CALL`.** Each one sends an Objective-C message (`-[NSWindow delegate]` sits on every
  window path). The story forbids calling `objc_msgSend` from Java, so none of them can become a `WRAPPER`.
- **1 is `WRAPPER`:** `_isRightToLeftLayoutDirection`. CoreFoundation has an equivalent for every step. Its parity is
  unknown until an A/B test runs.
- **3 are `PURE`:** `_getMultiClickMaxX_impl`, `_getMultiClickMaxY_impl` and `_setParent`. They can be deleted.
  `_setParent` has `PARITY: exact`. So do the maxima, except that their assertion could fire if the main thread
  detached between those calls in `MacView`'s initializer, after `_getMultiClickTime_impl`'s found it attached: a
  race not reproduced (§6 (m), PB7).
- **3 are JNI-only plumbing:** the three `_initIDs`. They are replaced by installing the callback tables.
- **The ~309 Objective-C methods and 8 C helpers in scope are `OS-CALL`.** They are AppKit, QuartzCore, CGL or Metal
  subclasses and delegates that the OS calls. The exceptions are 3 JNI-only helpers and one pointer cast.
- **No `PURE-HOT`:** there is no SIMD in scope.

### 2. Summary counts

How I counted: I listed the 35 scope files in a scratch list (`scope.txt`) and ran each grep over that list with
`grep -vE '^\S+:\s*//'` to drop commented lines. The greps were:
- `'(->|\.)Call(Static|Nonvirtual)?[A-Za-z]*Method'`
- `JNIEXPORT`
- `NewGlobalRef|DeleteGlobalRef`
- `GetMethodID`, `GetStaticMethodID`, `GetFieldID`, `FindClass`
- `GetPrimitiveArrayCritical|Get…ArrayElements|GetDirectBufferAddress`
- `NewString|NewStringUTF|NewObject|NewCharArray`
- `ExceptionCheck`, `GLASS_CHECK_EXCEPTION\(`, `GLASS_ASSERT_MAIN_JAVA_THREAD`, `GET_MAIN_JENV`
- `AttachCurrentThread|GetJavaVM|JNI_OnLoad`
- `^#import "com_sun_`

Java natives: `grep -nE '\bnative\b'` over `MacWindow.java`, `MacView.java` and `MacGestureSupport.java`.

| Item | Count |
|---|---|
| Java `native` declarations | **51**: MacWindow 30 (20 `@Override native protected`, 1 `native protected` `_setBounds2`, 9 `private`, 1 of them static), MacView 20 (13 `@Override native protected`, 4 `private static`, 3 `private`), MacGestureSupport 1 (static) |
| `JNIEXPORT` | **51**: GlassWindow.m 30, GlassView.m 20, GlassGestureSupport.m 1. No orphans in either direction. `JNI_OnLoad` is not in scope (it is GlassApplication.m:86-96) |
| `Call*Method` sites | **53 textual / 61 expanded.** The `SEND_KEY_EVENT` macro (ViewDelegate.m:664) expands 4×; `SEND_MODIFIER_KEY_EVENT_WITH_TYPE` (:725) expands 6×. Of the 61: **41 event upcalls** (window 10, view 31) and **20 creation-time `Map` reads** (GlassView3D.m 6, GlassViewCGL.m 8, GlassViewMTL.m 6). The whole library has 127, so this slice holds 53 of them |
| Hidden upcalls reached from scope | `GLASS_CHECK_EXCEPTION` 95 sites (each calls `Application.reportException` when an exception is pending); `GlassHelper ClassForName` 7 sites, 3 of them on every scroll-wheel or gesture event (`Class.forName`); `createJavaScreen` 1 (`NewObject Screen`); `GetJavaKeyChars` 1; `enterFullScreenExitingLoop` 3 and `leaveFullScreenExitingLoopIfNeeded` 2 (the nested event loop) |
| Distinct live upcall targets | **28**: Window 9, View 14, MacGestureSupport 5 |
| Cached IDs that are never called (dead) | 5: `jWindowNotifyDelegatePtr` (GlassWindow.m:732), `jViewNotifyInputMethod` (GlassView.m:128), `notifyBegin/Next/EndTouchEvent` (GlassGestureSupport.m:96-121) |
| `GetMethodID` / `GetStaticMethodID` / `GetFieldID` / `FindClass` | 30 / 8 / 2 (looked up on every call, not cached: GlassWindow.m:99, GlassView.m:302) / 4 |
| `NewGlobalRef` / `DeleteGlobalRef` | 13 / 4. Two per-object pins, paired: `jWindow` GlassWindow.m:622 with Overrides.m:51 in dealloc; `jView` ViewDelegate.m:149 with :199 in dealloc. Two transient refs paired inside `_create` (GlassView.m:284/288 with :310/312). 9 process-lifetime class refs, never deleted; `jMenuBarDelegateClass` (GlassWindow.m:655) is never used. Unmatched: `jWindowRef` leaks if window creation fails; both per-object refs are skipped when `jEnv == NULL` |
| Critical / elements / direct buffer | `GetPrimitiveArrayCritical` 2 (GlassView.m:615, 654; released with `JNI_ABORT`; **the critical region is held across a blocking GPU wait**). `GetDoubleArrayElements` 1 (ViewDelegate.m:1213, mode 0). `GetDirectBufferAddress` 2 (GlassView.m:579, GlassWindow.m:1472) |
| Strings | `GetStringChars` 1, through `GlassHelper nsStringWithJavaString` (UTF-16) for `_setTitle`. `NewString` 2 (IME text, UTF-16, ViewDelegate.m:1162/1167). `NewStringUTF` 6 (ASCII `Map` keys only). **No dependence on modified UTF-8** |
| Exceptions | `ThrowNew`: 0 written in scope, but 47 come in through the `GLASS_ASSERT_MAIN_JAVA_THREAD` macro. `ExceptionCheck` 32 (in the `_initIDs`). `GLASS_CHECK_EXCEPTION` 95 |
| Attach / `GetJavaVM` | 0 in scope. The main thread is attached at GlassApplication.m:562 |
| Generated-header `#import`s | 35 lines, 14 distinct headers, 9 files (table in §11) |
| Lines | 35 files, 8,394 lines (`wc -l`) |

### 3. Java classes and C files

**Threads.** "FX" below means the JavaFX application thread, which on macOS *is* the AppKit main thread:
`setEventThread` runs from `runLoop:` on the main thread (GlassApplication.m:575-587). Java's
`Application.checkEventThread()` guarantees that thread for every Window and View public method except `View.lock`,
`View.unlock` and `View.getNativeFrameBuffer` (J/View.java:752-780, marked "render thread"), unless
`glass.disableThreadChecks` turns the check off (§6 (l)).

| # | Java (J/mac/…) | Declaration | C impl (N) | Thread |
|---|---|---|---|---|
| W0 | MacWindow.java:43 | `private native static void _initIDs()` | GlassWindow.m:639 | class init |
| W1 | :57 | `@Override native protected long _createWindow(long ownerPtr,long screenPtr,int mask)` | :741 → :612 → :454 | FX |
| W2 | :58 | `boolean _close(long)` (C declares `jclass cls`, but it is an instance method) | :1015 | FX |
| W3 | :59 | `boolean _setView(long, View)` | :895 | FX |
| W4 | :60 | `boolean _setMenubar(long,long)` | :984 | FX |
| W5 | :61 | `boolean _minimize(long,boolean)` | :1409 | FX |
| W6 | :62 | `boolean _maximize(long,boolean,boolean)` | :1138 | FX |
| W7 | :80 | `native protected void _setBounds2(long,int,int,boolean,boolean,int,int,int,int,float,float)` | :1204 | FX |
| W8 | :81 | `boolean _setVisible(long,boolean)` | :1317 | FX |
| W9 | :82 | `boolean _setResizable(long,boolean)` | :1294 | FX |
| W10 | :84 | `native private boolean _requestFocus(long)` | :1056 | FX |
| W11 | :93 | `void _setFocusable(long,boolean)` | :791 | FX |
| W12 | :94 | `boolean _setTitle(long,String)` | :1382 | FX |
| W13 | :95 | `void _setLevel(long,int)` | :754 | FX |
| W14 | :96 | `void _setAlpha(long,float)` | :851 | FX |
| W15 | :97 | `boolean _setBackground(long,float,float,float)` | :872 | FX |
| W16 | :98 | `void _setEnabled(long,boolean)` | :812 | FX |
| W17 | :99 | `boolean _setMinimumSize(long,int,int)` | :1234 | FX |
| W18 | :100 | `boolean _setMaximumSize(long,int,int)` | :1263 | FX |
| W19 | :115 | `private native void _setIcon(long,Object iconBuffer,int,int)` | :1456 | FX |
| W20 | :122 | `private native void _setDarkFrame(long,boolean)` | :1502 | FX |
| W21 | :124 | `void _toFront(long)` (the only export with no main-thread assert) | :1525 | FX |
| W22 | :125 | `void _toBack(long)` | :1546 | FX |
| W23 | :127 | `boolean _grabFocus(long)` | :1089 | FX |
| W24 | :128 | `void _ungrabFocus(long)` | :1116 | FX |
| W25 | :174 | `private native void _performWindowDrag(long)` | :1567 | FX |
| W26 | :176 | `private native void _performTitleBarDoubleClickAction(long)` | :1588 | FX |
| W27 | :178 | `private native boolean _isRightToLeftLayoutDirection()` | :1674 | FX |
| W28 | :180 | `private native void _setWindowButtonStyle(long,int,boolean)` | :1615 | FX |
| W29 | :182 | `private native void _setWindowButtonAppearance(long,boolean)` | :1645 | FX |
| V0 | MacView.java:44 | `private native static void _initIDs()` | GlassView.m:59 | class init |
| V1 | :56 | `private native static long _getMultiClickTime_impl()` | :224 | MacView `<clinit>` thread |
| V2 | :57 | `private native static int _getMultiClickMaxX_impl()` | :240 | same |
| V3 | :58 | `private native static int _getMultiClickMaxY_impl()` | :257 | same |
| V4 | :72 | `@Override native protected long _getNativeFrameBuffer(long)` | :326 | **render** |
| V5 | :73 | `long _create(Map caps)` | :274 | FX |
| V6 | :74 | `int _getX(long)` | :353 | FX |
| V7 | :75 | `int _getY(long)` | :384 | FX |
| V8 | :76 | `void _setParent(long,long)` | :417 | FX |
| V9 | :77 | `boolean _close(long)` | :434 | FX |
| V10 | :78 | `void _scheduleRepaint(long)` | :504 | FX |
| V11 | :79 | `void _begin(long)` (`GLASS_POOL_PUSH`) | :462 | **render** |
| V12 | :80 | `void _end(long)` (`GLASS_POOL_POP`) | :483 | **render** |
| V13 | :81 | `boolean _enterFullscreen(long,boolean,boolean,boolean)` | :525 | FX (runs a nested loop) |
| V14 | :82 | `void _exitFullscreen(long,boolean)` | :548 | FX (runs a nested loop) |
| V15 | :83 | `void _enableInputMethodEvents(long,boolean)` | :675 | FX |
| V16 | :84 | `void _finishInputMethodComposition(long)` | :696 | FX |
| V17 | :106 | `private native void _uploadPixelsDirect(long,Buffer,int,int,float,float)` | :569 | FX, between lock and unlock |
| V18 | :107 | `…_uploadPixelsByteArray(long,byte[],int off,int,int,float,float)` | :593 | same |
| V19 | :108 | `…_uploadPixelsIntArray(long,int[],int off,int,int,float,float)` | :632 | same |
| G0 | MacGestureSupport.java:33 | `private native static void _initIDs()` | GlassGestureSupport.m:46 | class init (lazy: first `Class.forName` from a wheel or gesture event) |

`long ptr` handles:
- `Window.ptr` is the `NSWindow*`; its delegate is the `GlassWindow`.
- `View.ptr` is the `GlassView3D*`. `View.getRawHandle()` (View.java:1176) already exists for unchecked access.
- `_getNativeFrameBuffer` returns either a GL FBO name or an `id<MTLTexture>`.

**How calls are moved to the main thread today.** Nothing uses `dispatch_async`.
- `performSelectorOnMainThread` is used 5 times:
  - GlassWindow.m:969 `setContentView:` (wait=YES)
  - GlassWindow.m:1040 `close` (wait=NO, deliberately deferred: JDK-8095359)
  - GlassWindow.m:1367 `orderFrontRegardless` (wait=YES)
  - CGLOffscreen.m:141 and MTLOffscreen.m:129 `setNeedsDisplay` (render thread to main, wait=NO)
- `GET_MAIN_JENV` only `assert`s that it is on the main thread. Release builds are compiled with `-DNDEBUG`
  (CM:103-104), so the assert disappears.
- `GLASS_ASSERT_MAIN_JAVA_THREAD` (GlassMacros.h) throws `RuntimeException("Main Java thread is detached.")` when not
  on the main thread and `jEnv == NULL`, **and then keeps running the body**.

| C file (N) | Lines | Role | JNI content |
|---|---|---|---|
| GlassWindow.m | 1692 | NSWindow/NSPanel subclasses (`GLASS_NS_WINDOW_IMPLEMENTATION` :111-209), GlassWindow, 30 exports | 30 `JNIEXPORT`, 5 global refs, 10 `GetMethodID`, 3 `ClassForName`, 103 j-type lines |
| GlassWindow+Java.m/.h | 393/54 | upcall helpers, grab, bounds, coordinate flip | 5 upcalls (all unchecked), j-types in method signatures |
| GlassWindow+Overrides.m/.h | 323/34 | NSWindowDelegate | 5 upcalls (unchecked), `DeleteGlobalRef` in dealloc |
| GlassView.m/.h | 710/53 | 20 exports, GlassView protocol | 20 `JNIEXPORT`, 20 `GetMethodID`, critical arrays, `SetLongField` |
| GlassView3D.m/.h | 782/58 | NSView, NSTextInputClient, drag destination, accessibility forwarding | 6 `Map` reads |
| GlassViewDelegate.m/.h | 1306/115 | event-to-Java translation | 23 textual / 31 expanded upcalls, `jView` global ref |
| GlassViewCGL.m/.h, GlassViewMTL.m/.h | 238/52, 157/41 | pixel format/context, layer setup | 8 and 6 `Map` reads |
| GlassHostView, GlassLayer*, GlassOffscreen, GlassCGL/MTLOffscreen, GlassCGL/MTLFrameBufferObject (.m/.h) | 95…343 | CALayer/CGL/Metal surfaces | no JNI calls; `jlong` return types (`-fbo`), `jlong_to_ptr` in GlassLayerMTL.m:55 |
| GlassGestureSupport.m | 122 | only `MacGestureSupport._initIDs` | 8 `GetStaticMethodID` |

### 4. External dependencies

**Linked.** The `glass` target is defined at CM:159-167. It links `JFX_FRAMEWORK_LINK_LIBS` (CM:109-118): AppKit,
ApplicationServices, Carbon, OpenGL, QuartzCore, Security, Network, Metal and `objc`. Compile options are `-std=c99`
for C and ObjC, `-O3 -DNDEBUG` in release, `-DGL_SILENCE_DEPRECATION`, `-DMACOS_MIN_VERSION_*` and `-Werror`. Every
target gets the JDK include dirs and `HEADERS_DIR` unconditionally (CM:150-153). The code uses manual retain/release
(no ARC).

**Imported symbols: not measured.** No binary exists: `modules/javafx.graphics/target` is absent at HEAD, and there is
no `libglass.dylib` under `../caches/sdk` or `~/.m2`. `nm -u target/native/bin/libglass.dylib` has to wait for a
build. From the source, the scope reaches:
- **AppKit / Foundation (ObjC):** NSWindow, NSPanel, NSView, NSEvent, NSScreen, NSApp, NSColor, NSImage, NSAppearance,
  NSTrackingArea, NSTextInputContext, NSDragging*, NSLocale, NSUserDefaults, NSString/NSData, NSAutoreleasePool,
  NSThread. Also the plain C function `NSBeep` and many data symbols (NSPasteboardType*, NSAppearanceName*,
  NSAccessibility*, NSDefaultRunLoopMode, `kUTTypeURL`/`kUTTypeFileURL`).
- **CoreGraphics (C):** `CGEventGetLocation`, `CGColorSpaceCreateWithName` (plus `CFDataCreate`, `CGImageCreate` in
  `getImage`, GlassPixels.m:59).
- **CGL/OpenGL (C):** `CGLChoosePixelFormat`, `CGLCreateContext`, `CGLRetain/ReleaseContext`, `CGLLock/UnlockContext`,
  `CGLGet/SetCurrentContext`, `CGLRetainPixelFormat`, `CGLGetPixelFormat`, about 40 `gl*` calls (immediate mode, EXT
  FBO, `glBlitFramebuffer`, `glFinish`) and `gluCheckExtension`.
- **Metal / QuartzCore:** `MTLCreateSystemDefaultDevice`, MTL command queue, buffer and texture objects, CALayer /
  CAOpenGLLayer / CAMetalLayer.
- **Carbon (C, deprecated):** `GetCurrentProcess` and `GetProcessInformation` (GlassWindow.m:1357-1361).
- **libSystem:** `pthread_main_np`, `pthread_get/setspecific`, `calloc`, `malloc`, `free`, `memset`, `backtrace`.

Nothing here is free of external dependencies, so no whole-library deletion is possible.

### 5. Triage table

#### Exports

| Function (Java name) | Verdict | Evidence (external symbol) | Parity gate | Replacement |
|---|---|---|---|---|
| W0, V0, G0 `_initIDs` | JNI-only | none; only `GetMethodID`, `GetStaticMethodID`, `FindClass`, `NewGlobalRef`, `ClassForName` | n/a | the `gmac_*_set_callbacks` tables; GlassGestureSupport.m (122 lines) goes entirely. W0: done in slice 5. V0, G0: done in slice 6, `gmac_view_install_callbacks` and `gmac_gesture_install_callbacks` |
| W1 `_createWindow` | OS-CALL | `-[NSWindow initWithContentRect:styleMask:backing:defer:screen:]`, `setCollectionBehavior:`, `setAppearance:`, `+[NSAppearance appearanceNamed:]` | — | `gmac_window_create` |
| W2 `_close` | OS-CALL | `performSelectorOnMainThread:@selector(close)` | — | `gmac_window_close` |
| W3 `_setView` | OS-CALL | `setContentView:`, `makeFirstResponder:`, `toggleFullScreen:`, nested loop; `getMacView` (GetFieldID/GetLongField `View.ptr`) is JNI-only | — | Java calls `gmac_window_leave_native_full_screen`, then reads `MacView.nativeHandle()`, then calls `gmac_window_set_view` (§6 (c)) |
| W4 `_setMenubar` | OS-CALL | `-[NSApplication setMainMenu:]`, `-[NSMenu update]` | — | `gmac_window_set_menubar` |
| W5 `_minimize` | OS-CALL | `miniaturize:`, `deminiaturize:`, `setStyleMask:` | — | `gmac_window_minimize` |
| W6 `_maximize` | OS-CALL | `zoom:`, `setFrame:display:animate:YES`, `-[NSScreen visibleFrame]` | — | `gmac_window_maximize` |
| W7 `_setBounds2` | OS-CALL | `setFrame:display:animate:`, `+frameRectForContentRect:styleMask:` | — | `gmac_window_set_bounds` |
| W8 `_setVisible` | OS-CALL | `makeKeyAndOrderFront:`, `orderOut:`, `GetCurrentProcess`, `GetProcessInformation` | — | `gmac_window_set_visible` |
| W9 `_setResizable` | OS-CALL | `setStyleMask:`, `setShowsResizeIndicator:`, `-[NSButton setEnabled:]` | — | `gmac_window_set_resizable` |
| W10 `_requestFocus` | OS-CALL | `makeMainWindow`, `makeKeyAndOrderFront:`, `orderFrontRegardless`, `isKeyWindow` | — | `gmac_window_request_focus` |
| W11 `_setFocusable` | OS-CALL | `-[NSWindow delegate]` plus a delegate ivar that AppKit reads in `-canBecomeKeyWindow` | — | `gmac_window_set_focusable` |
| W12 `_setTitle` | OS-CALL | `-[NSString initWithCharacters:length:]`, `setTitle:` | — | `gmac_window_set_title` (UTF-16) |
| W13 `_setLevel` | OS-CALL | `setLevel:`, `level` | — | `gmac_window_set_level` |
| W14 `_setAlpha` | OS-CALL | `setAlphaValue:` | — | `gmac_window_set_alpha` |
| W15 `_setBackground` | OS-CALL | `+[NSColor colorWithSRGBRed:…]`, `setBackgroundColor:` | — | `gmac_window_set_background` |
| W16 `_setEnabled` | OS-CALL | `setStyleMask:`, `standardWindowButton:` | — | `gmac_window_set_enabled` |
| W17, W18 `_setMinimumSize`, `_setMaximumSize` | OS-CALL | `setMinSize:`/`setContentMinSize:` and the max equivalents | — | `gmac_window_set_{minimum,maximum}_size` |
| W19 `_setIcon` | OS-CALL | `CFDataCreate`, `CGImageCreate`, `-[NSImage initWithCGImage:size:]`, `setRepresentedURL:`, `-[NSButton setImage:]` | — | `gmac_window_set_icon` |
| W20 `_setDarkFrame` | OS-CALL | `setAppearance:` | — | `gmac_window_set_dark_frame` |
| W21, W22 `_toFront`, `_toBack` | OS-CALL | `orderFrontRegardless`, `orderBack:` | — | `gmac_window_to_front` / `to_back` |
| W23, W24 `_grabFocus`, `_ungrabFocus` | OS-CALL | GlassWindow ObjC state plus an upcall | — | `gmac_window_grab_focus` / `ungrab_focus` |
| W25 `_performWindowDrag` | OS-CALL | `performWindowDragWithEvent:`, `-[NSApplication currentEvent]` | — | `gmac_window_perform_drag` |
| W26 `_performTitleBarDoubleClickAction` | OS-CALL | `-[NSUserDefaults stringForKey:]`, `performMiniaturize:`, `performZoom:` | — | `gmac_window_perform_title_bar_double_click_action` |
| **W27 `_isRightToLeftLayoutDirection`** | **WRAPPER** | `+[NSLocale preferredLanguages]`, `localeWithLocaleIdentifier:`, `objectForKey:NSLocaleLanguageCode`, `characterDirectionForLanguage:`. Each has a toll-free-bridged CF equivalent: `CFLocaleCopyPreferredLanguages`, `CFArrayGetValueAtIndex`, `CFLocaleCreate`, `CFLocaleGetValue(kCFLocaleLanguageCode)`, `CFLocaleGetLanguageCharacterDirection` (returns CFIndex; RTL = 2), `CFRelease` | **exact**, by construction (disassembly) and by a headless A/B (§10) | Java binds CoreFoundation directly (`MacWindowCalls`); no export |
| W28, W29 `_setWindowButtonStyle`, `_setWindowButtonAppearance` | OS-CALL | `setToolbarStyle:`, `-[NSButton setHidden:/setAppearance:]` | — | `gmac_window_set_button_style` / `_appearance` |
| V1 `_getMultiClickTime_impl` | OS-CALL | `+[NSEvent doubleClickInterval]`. The C alternative `GetDblTime()` returns 1/60 s ticks, so it is not equivalent | — | `gmac_view_multi_click_time_ms`. Done in slice 6 |
| **V2, V3 `_getMultiClickMax{X,Y}_impl`** | **PURE** | none: the body is `return 3`; the macro reaches only libSystem `pthread_main_np` | **exact**, but for a race not reproduced (§6 (m), PB7) | Java literal `3`. Done in slice 6: no export |
| V4 `_getNativeFrameBuffer` | OS-CALL | GlassLayer/Offscreen, i.e. a GL FBO name or `-[GlassMTLFrameBufferObject texture]` | — | `gmac_view_get_native_frame_buffer`. Done in slice 6 |
| V5 `_create` | OS-CALL | GlassHostView/GlassView3D `initWithFrame:`, `CGLChoosePixelFormat`, `CGLCreateContext` or `MTLCreateSystemDefaultDevice`, `addSubview:`. The `Map` reads, `SetLongField` and the 2 transient global refs are JNI-only | — | `gmac_view_create(view_id, const GmacViewCaps*)`. Done in slice 6 |
| V6, V7 `_getX`, `_getY` | OS-CALL | `-[NSView window]`, `frame`, `contentRectForFrameRect:` | — | `gmac_view_get_x` / `get_y`. Done in slice 6 |
| **V8 `_setParent`** | **PURE** | none: the body is only `GLASS_ASSERT_MAIN_JAVA_THREAD` plus a TODO | **exact** | a Java override that keeps only the assertion's detached throw (PB18, §7). Done in slice 6: no export |
| V9 `_close` | OS-CALL | `removeFromSuperview`, `release` | — | `gmac_view_close`. Done in slice 6 |
| V10 `_scheduleRepaint` | OS-CALL | `setNeedsDisplay:` | — | `gmac_view_schedule_repaint`. Done in slice 6 |
| V11, V12 `_begin`, `_end` | OS-CALL | `CGLLockContext`, `CGLSetCurrentContext`, `glBindFramebufferEXT` or MTL `newTextureWithDescriptor:`; flush `glBlitFramebuffer`; `performSelectorOnMainThread:setNeedsDisplay` | — | `gmac_view_begin` / `end`. Done in slice 6 |
| V13, V14 `_enterFullscreen`, `_exitFullscreen` | OS-CALL | `toggleFullScreen:` plus the nested event loop | — | `gmac_view_enter/exit_fullscreen`. Done in slice 6 |
| V15, V16 IME | OS-CALL | `-[NSTextInputContext discardMarkedText]`, `unmarkText` | — | `gmac_view_enable_input_method_events` / `finish_input_method_composition`. Done in slice 6 |
| V17–V19 uploads | OS-CALL | CGL: `glTexImage2D`, `glTexSubImage2D`, `glFinish`; MTL: `newBufferWithBytes:`, blit encoder, `waitUntilCompleted` | — | `gmac_view_upload_pixels_{direct,bytes,ints}`. Done in slice 6 |

#### Internal C helpers (8)

| Helper | Verdict |
|---|---|
| `getGlassWindow` (GlassWindow.m:87) | OS-CALL (`-[NSWindow delegate]`) |
| `getMacView` (:95) | JNI-only → delete |
| `getGlassView` (GlassView.m:45) | PURE cast; becomes a plain cast |
| `_createWindowCommonDo` (:454) | OS-CALL |
| `_createWindowCommon` (:612) | JNI-only; the global ref becomes `window_id` |
| `isInertialScroll` (ViewDelegate.m:81) | OS-CALL (`-[NSEvent momentumPhase]`) |
| `getSwipeDirFromEvent` (:121) | OS-CALL (`deltaX`, `deltaY`) |
| `convertNSStringToJString` (:1148) | OS-CALL (`-[NSString dataUsingEncoding:]`); now produces a UTF-16 buffer for the slot. Slice 6 made it `GlassDialInputMethod`; the JNI function was deleted in the slice-6 cleanup |

#### Objective-C methods

About 309 method definitions (`grep -cE '^[-+] ?\('`) across 16 classes and categories. All are OS-CALL: AppKit,
QuartzCore, CGL or Metal instantiate them or call them through the ObjC runtime.

### 6. Upcall table

All 41 event upcalls fire on the **AppKit main thread**, which is the FX thread. Many of them re-enter: they fire
inside our own downcalls (`_setView`, `_maximize`, `_setBounds2`, `_setVisible`, `_close`) and inside nested loops.

- "chk" means a `GLASS_CHECK_EXCEPTION` follows immediately: the exception is reported and execution continues. No
  site in scope *branches* on an exception.
- "**unchk**" means the exception is left pending. It is reported by the next check, often the enclosing export's
  tail. A later JNI upcall does not drop it: HotSpot sets it aside for that call and restores it unless the call
  raised a new one, which then replaces it (PB11, checked on this host; divergences (a) and (e) below).

#### Window upcalls

All receivers are `self->jWindow`, i.e. the MacWindow.

| # | Site | Target (JNI signature) | Arguments exactly as passed | Trigger | Exc. |
|---|---|---|---|---|---|
| WU1 | +Java.m:68 | `notifyMoveToAnotherScreen(Lcom/sun/glass/ui/Screen;)V` | `createJavaScreen(env,newScreen)` (GlassScreen.m:54, 1 `long` + 15 `int` + 4 `float`, `[screen retain]` leaked on purpose) | from WU3/WU4 when `[nsWindow screen]` changes | unchk |
| WU2 | +Java.m:75 | `notifyScaleChanged(FFFF)V` | `1.0f, 1.0f, newScale, newScale`. **`newScale` is a CGFloat (double) passed through varargs; the JVM narrows it to float** | `windowDidChangeBackingProperties:` | unchk |
| WU3 | +Java.m:86 | MacWindow `notifyMove(IIZ)V` | `(int)frame.origin.x, (int)frame.origin.y, [nsWindow isZoomed]` (BOOL) | `windowDidMove:`, `windowDidResize:`, `_maximize`, `_setBounds:` | unchk |
| WU4 | +Java.m:104 | MacWindow `notifyResize(III)V` | `type` (rewritten to MINIMIZE or MAXIMIZE when miniaturized or zoomed), `(int)w`, `(int)h` | `windowDidResize/Miniaturize/Deminiaturize:`, `_maximize`, `_setBounds:` | unchk |
| WU5 | +Java.m:172 | `notifyFocusUngrab()V` | — | `_ungrabFocus` (from close, title-bar mouse-down in `sendEvent:`, `_resetGrab` including GlassMenu.m:191, `windowDidResignKey:`, hide, the export) | unchk |
| WU6 | Overrides.m:82 | `notifyFocusDisabled()V` | after `NSBeep()` | `windowDidBecomeKey:` when disabled | unchk |
| WU7 | Overrides.m:86 | `notifyFocus(I)V` | `FOCUS_GAINED` (542) | `windowDidBecomeKey:` | unchk |
| WU8 | Overrides.m:119 | `notifyFocus(I)V` | `FOCUS_LOST` (541); skipped if `env == NULL` | `windowDidResignKey:` | unchk |
| WU9 | Overrides.m:150 | `notifyDestroy()V` | skipped if `jEnv == NULL` | `windowWillClose:` (also fires for child windows closed in a loop) | unchk |
| WU10 | Overrides.m:303 | `notifyClose()V` | only if `isEnabled`; skipped if `env == NULL` | `windowShouldClose:` | unchk |
| (WD) | Overrides.m:51 | `DeleteGlobalRef(jWindow)`, then `GLASS_CHECK_EXCEPTION` | — | GlassWindow `dealloc`, after the NSWindow deallocates | — |

#### View upcalls

All receivers are `self->jView`, i.e. the MacView. "Gesture" rows are `CallStaticVoidMethod` on MacGestureSupport,
with the View passed as the first argument after a `ClassForName` lookup on every event.

| # | Site (ViewDelegate.m) | Target | Arguments exactly as passed | Trigger | Exc. |
|---|---|---|---|---|---|
| VU1, VU2 | :229, :238 | `notifyView(I)V` | ADD 411 / REMOVE 412 | `viewDidMoveToWindow` (fires inside `setContentView:` and close) | chk :240 |
| VU3 | :255 | `notifyResize(II)V` (MacView override scales) | `(int)newSize.width, (int)newSize.height` | `setFrameSize:` | chk |
| VU4 | :271 | same | `(int)frameRect.size.*` | `setFrame:` | chk |
| VU5 | :297 | `notifyRepaint(IIII)V` | `(jint)` frame x, y, w, h | `drawRect:` | chk |
| VU6 | :317 | `notifyMenu(IIIIZ)V` | view x, y, `CGEventGetLocation` x, y; `isKeyboardTrigger = JNI_FALSE` always | after a popup-trigger DOWN | chk |
| **VU7 HOT** | :623 | gesture `scrollGesturePerformed(Lcom/sun/glass/ui/View;IIIIIIFF)V` | `jView`, modifiers, sender (50, 51 or 52), `(jint)` vx, vy, bx, by, `rotationX`, `rotationY`. **These two are jdouble (scrollingDelta·0.1 or delta) and the JVM narrows them to float** | `scrollWheel:` | chk :635 |
| **VU8 HOT** | :631 | `notifyMouse(IIIIIIIZZ)V` (MacView override scales) | type, button, `(jint)` vx, vy, bx, by, modifiers, `isPopupTrigger`, `isSynthesized = JNI_FALSE` always. A synthesized ENTER may run first (recursion at :517) | mouse, rightMouse, otherMouse, enter/exit, `resetMouseTracking`, `synthesizeMouseUp` | chk :635 |
| **VU9–VU12 HOT** | :693, :699, :707, :714 (`SEND_KEY_EVENT`) | `notifyKeyAndReturnConsumed(II[CI)Z` | type (RELEASE / PRESS / TYPED / synthesized RELEASE when ⌘), keyCode (**0 for TYPED**), `jKeyChars` (**the same `char[]` object for all calls of one event**, from `GetJavaKeyChars` or a 1-unit `textChar`), modifiers. Only the PRESS result is used | keyUp, `handleKeyDown` (keyDown / `performKeyEquivalent:`) | chk each |
| VU13–VU16 | :746–:749 | same | PRESS or RELEASE with `VK_SHIFT/CONTROL/ALT/COMMAND`, one shared `char[0]`; result discarded | `flagsChanged:` | chk each |
| VU17, VU18 | :753, :754 | same | `VK_CAPS_LOCK` PRESS, then RELEASE | `flagsChanged:` | **unchk** (checked only at :758) |
| VU19 | :778 | gesture `rotateGesturePerformed(LView;IIIIIF)V` | view, mods, 4 ints, `(jfloat)[ev rotation]` | `rotateWithEvent:` | chk :803 |
| VU20 | :786 | gesture `swipeGesturePerformed(LView;IIIIII)V` | view, mods, dir (1–4), 4 ints | `swipeWithEvent:` | chk |
| VU21 | :794 | gesture `magnifyGesturePerformed(LView;IIIIIF)V` | view, mods, 4 ints, `(jfloat)[ev magnification]` | `magnifyWithEvent:` | chk |
| VU22 | :825 | gesture `gestureFinished(LView;IIIII)V` | view, mods, 4 ints | phase ended or cancelled | chk :832 |
| VU23–VU25 | :931, :935, :939 | `notifyDrag{Enter,Over,Drop}(IIIII)I` | x, y, xAbs, yAbs, recommended action. Result goes to `[GlassDragSource setMask:]` (0 after a throw) | `draggingEntered/Updated:`, `performDragOperation:` | chk :952 (after `setMask`) |
| VU26 | :943 | `notifyDragLeave()V` | — | `draggingExited:` | chk :952 |
| VU27 | :1129 | `notifyDragEnd(I)V` | performed action | drag source `draggingEnded:` (GlassDraggingSource.m:75) | chk |
| VU28 | :1184 | MacView `notifyInputMethodMac(Ljava/lang/String;IIIII)V` | jStr (UTF-16 via `NewString`; **no call if NULL**), attr (1 or 4), length, cursor, `selectionRange.location`, `.length`. **The last two are NSUInteger in varargs, read as jint (low 32 bits): NSNotFound arrives as −1** | `insertText:`, `setMarkedText:`, `unmarkText` (also from V16). Off the main thread the call is dropped | chk |
| VU29 | :1204 | `notifyInputMethodCandidatePosRequest(I)[D` | pos = 0; result used only when length == 2 | `firstRectForCharacterRange:`. Off the main thread the call is dropped | chk |
| VU30 | :1238 | `notifyView(I)V` | FULLSCREEN_ENTER 431 / EXIT 432 | `windowDid{Enter,Exit}FullScreen:`, non-native exit | chk |
| VU31 | :1301 | `getAccessible()J` (package-private; `View.getAccessibleForNative()` exists) | returns a `GlassAccessible*` | every `accessibility*` override in GlassView3D.m:710-770 (VoiceOver, high rate) | chk |
| (VD) | :199 | `DeleteGlobalRef(jView)` if `env != NULL` | — | GlassViewDelegate `dealloc` | — |

**Creation-time `Map` reads (CU1–CU20).** These are `CallObjectMethod(Map.get)` and `Long.longValue` /
`Boolean.booleanValue` / `Integer.intValue` calls:
- GlassView3D.m:91, 97, 118, 122, 126, 130
- GlassViewCGL.m:100, 104, 113, 117, 133, 137, 164, 168
- GlassViewMTL.m:51, 55, 65, 71, 84, 88

They run inside V5 on the FX thread, each followed by a check. They become the `GmacViewCaps` scalars listed in §7.

**Recorded divergences (PB7), slice 5 as built.** Line numbers are at 1a6788cc4d.
- (a) WU1–WU10 are reported at once. The JNI left what they threw pending, and the next `GLASS_CHECK_EXCEPTION` on
  the main thread reported it, often the tail of the export that ran them. Each slot target now reports it through
  `Application.reportException` before it answers `GMAC_UPCALL_THREW` (Risk 3).
  - The count can change as well as the time. Under the JNI, a later site that threw before that check replaced the
    pending exception (PB11), so only the last one was reported. Now each slot that throws is reported, in the order
    the slots ran. Example: `_maximize` dials slot 0 (`notifyMove`), then slot 1 (`notifyResize`), with no check
    between them (`GlassWindow.m:1189-1190`). When both throw, the JNI reported only `notifyResize`'s exception, at
    the export's tail (:1194); Java reports `notifyMove`'s, then `notifyResize`'s.
- (b) The `GET_MAIN_JENV` sites, WU1–WU7 (`GlassWindow+Java.m:67, 74, 84, 96, 171`, `GlassWindow+Overrides.m:78`),
  while Java is detached from the main thread: `GET_MAIN_JENV` printed its `GLASS_CALLSTACK` ("Java has been detached
  already, but someone is still trying to use it at …" and a backtrace), and the JNI then crashed on the NULL
  `JNIEnv`. The C still prints it, then skips the slot. WU8–WU10 and `notify_dispose` (`GET_MAIN_JENV_NOWARN` and raw
  `jEnv`) skip silently, as the JNI did. Slice 11 part 3 brought slice 1's App sites (slots 1, 4-15, 17 and 18, g1 §6
  (d)) to this rule (PB14). It also replaced `GET_MAIN_JENV` with `GLASS_WARN_IF_MAIN_JAVA_DETACHED`, which prints
  the same text, and the silent sites' `jEnv` tests with `GlassIsMainJavaAttached()`.
- (c) Java calls `gmac_window_leave_native_full_screen`, then reads `MacView.nativeHandle()`, then calls
  `gmac_window_set_view`. So the handle is read after the full-screen exit loop, as the JNI read `View.ptr` through
  `getMacView` after `enterFullScreenExitingLoop` (`GlassWindow.m:919`). A view closed in the loop arrives as NULL
  (dummy content view), with no use-after-free. What remains of the divergence:
  - The two exports drain separate autorelease pools, where the JNI had one. So anything autoreleased by the
    full-screen exit is drained before the rest of `_setView` runs.
  - Until slice 11 part 3 the leave export ended with `GlassReportPendingException()`, which found nothing pending,
    because the JNI's `enterFullScreenExitingLoop` had already run ExceptionDescribe+Clear (`GlassApplication.m:843-846`
    at 1a6788cc4d). Part 3 deleted it with the last JNI site.
- (d) The detached reports of `_createWindow` and `_setView` come after the export's autorelease pool. The JNI reported
  inside it: at `_createWindowCommonDo`'s `GLASS_CHECK_EXCEPTION` (`GlassWindow.m:607`), before `_createWindowCommon`'s
  `GLASS_POOL_EXIT` (:625), and at `getMacView`'s (:100) inside `_setView`'s pool. Java evaluates
  `gmac_main_thread_detached` before the downcall and reports after it returns.
- (e) FFM upcall stubs clear a pending JNI exception. A probe on this JDK showed it (supporting evidence, not checked
  in): a native leaves a `RuntimeException` pending and then calls an FFM upcall stub, and after the slot returns
  nothing is pending, whether the slot threw and caught or not. Under JNI a `Call*Method` kept it unless the call
  raised a new one (PB11). It was transitional (PB15): only a still-JNI site could leave an exception pending, and
  slice 11 part 3 deleted the last of them with every helper below. Until part 3:
  - The window slots preserved it. Slots 0, 1 and 3-8 were dialled through `GlassDialSlotKeepingPending`, which took
    the exception off the thread before the slot and put it back after, unless the slot answered
    `GMAC_UPCALL_THREW`, just as a new exception replaced it under JNI. There was no separate take or restore
    function. `MacWindowNativeTest` checked it through the shim-only hook
    `gmac_test_fire_window_callback_with_pending`; part 3 deleted the hook and its three tests with the helper.
  - Slots 2 and 9 reported first instead. `GlassReportPendingException()` ran before
    `notify_move_to_another_screen`, where `createJavaScreen`'s `GLASS_CHECK_EXCEPTION` ran (`GlassScreen.m:63`/`:116`),
    and `GLASS_CHECK_EXCEPTION` ran before `notify_dispose`, as dealloc's followed a `DeleteGlobalRef` that runs no
    Java. So WU1 and dispose were exact.
  - The App slots of slice 1 (g1 §6) did not preserve it. An exception that a still-JNI site left pending on the main
    thread was dropped when an App slot ran; the JNI kept it for the next `GLASS_CHECK_EXCEPTION`. At 1a6788cc4d
    `enterFullScreenExitingLoop` wrote an exception pending before slot 17 to stderr and cleared it
    (ExceptionDescribe), and the FFM slot-17 stub dropped it silently.

  Since part 3 nothing can be pending when a slot is dialled. Every window slot is dialled directly, nothing is
  reported before slots 2 and 9, and the App-slot drop and the slot-17 ExceptionDescribe drop are gone.

**Recorded divergences (PB7), slice 6 as built (C side).** Line numbers are at 1a6788cc4d. The letters continue
slice 5's.
- (f) The Caps Lock pair VU17/VU18 (`GlassViewDelegate.m:753-754`) is reported at once: the `notify_key` target
  reports what it throws through `Application.reportException` before it returns. The JNI left it pending for the
  check after the pair (:758). When both threw, RELEASE's exception replaced PRESS's (PB11), so the JNI reported only
  RELEASE's; now PRESS's is reported, then RELEASE's.
- (g) The view and gesture slots dropped an exception that a still-JNI upcall left pending on the main thread (PB15).
  They are dialled directly, not through `GlassDialSlotKeepingPending` as window slots 0, 1 and 3-8 were until slice
  11 part 3 (§6 (e)). Under JNI the exception survived the upcall (PB11), and the site's next `GLASS_CHECK_EXCEPTION`
  reported it, or the new one that replaced it when the upcall threw. It was transitional: only a still-JNI site
  could leave one pending, so the drop went with the last of them in slice 11 part 3.
- (h) A detached IME event (VU28, `notifyInputMethod:`) prints `GLASS_CALLSTACK` once, at the method's
  `GET_MAIN_JENV` (`GLASS_WARN_IF_MAIN_JAVA_DETACHED` since slice 11 part 3), then skips the slot (PB14). The JNI
  printed it twice, there (:1180) and at `convertNSStringToJString`'s `GET_MAIN_JENV` (:1150), before it crashed on
  the NULL `JNIEnv`.
- (i) `char[]` identity is lost after a nested key event. The JNI passed every dial of one `sendJavaKeyEvent` or
  `sendJavaModifierKeyEvent` the same `jcharArray`, even when a nested event dialled the slot between two of them.
  Now `same_chars_as_previous` is 1 only when the immediately preceding `notify_key` dial was the same event's. After
  a nested event's dial it is 0, and Java builds a new `char[]` with the same units (§8 "Key array identity").
- (j) The transitional JNI `_create` read each device-details key once, into a `GmacViewCaps`
  (`readCapabilities`, `GlassView.m`), before the host view was made, and passed it to the `createView` it shared
  with `gmac_view_create`. The JNI read the keys after it made the `GlassHostView` (`GlassView.m:292`), inside the
  GlassView3D initializer and then GlassViewMTL's or GlassViewCGL's `_initialize3dWithJproperties:`, and it re-read
  `mtlCommandQueue` on the Metal path (`GlassViewMTL.m:64-71`) and `kHiDPIAware` on the OpenGL path
  (`GlassViewCGL.m:162-168`). Not observable. The transitional `readCapabilities` path is now gone: the slice-6
  cleanup deleted it with the JNI `_create`.
- (k) `GET_MAIN_JENV` is removed from the GlassView3D initializer (`GlassView3D.m:86`) and from the
  `_initialize3dWithJproperties:` of GlassViewCGL (`GlassViewCGL.m:93`) and GlassViewMTL (`GlassViewMTL.m:42`), now
  `_initialize3dWithCaps:`. Its detached case, the `GLASS_CALLSTACK` print, cannot happen on the event thread, where
  `View`'s constructor runs `_create` after `Application.checkEventThread()` (`View.java:438-439`). Only
  `glass.disableThreadChecks` lets `_create` run elsewhere; that case is in (l).

**Recorded divergences (PB7), slice 6 as built (Java side).** Line numbers are at 1a6788cc4d unless marked current.
- (l) The view calls run off the main thread while it is detached: only the JNI's crashes remain. With
  `-Dglass.disableThreadChecks=true`, `Application.checkEventThread()` checks nothing (`Application.java:437`), so
  any `View` method can run on any thread. When one runs while the main thread is detached (before `runLoop:`
  attaches it, or after `[NSApp run]` has returned), `GLASS_ASSERT_MAIN_JAVA_THREAD` left
  `RuntimeException("Main Java thread is detached.")` pending and the JNI body went on (PB11). Under PB18, decided
  after slices 6 and 7, `MacViewCalls` reproduces that outcome for every view call whose native asserted, as
  `MacWindowCalls` does for the window calls (§7 "Detached-thread check"). So these calls match the JNI again:
  - `_getX`, `_getY`, `_close`, `_scheduleRepaint`, `_enterFullscreen`, `_exitFullscreen`,
    `_enableInputMethodEvents` and `_finishInputMethodComposition` (assertions at `GlassView.m:361`, `:392`, `:440`,
    `:510`, `:531`, `:554`, `:681`, `:702`) report it once after the downcall, where their closing
    `GLASS_CHECK_EXCEPTION` did (`:374`, `:407`, `:452`, `:517`, `:538`, `:561`, `:688`, `:709`). The bodies that
    crashed before that check are below.
  - The uploads, `_uploadPixelsDirect`, `_uploadPixelsByteArray` and `_uploadPixelsIntArray` (assertions at `:576`,
    `:607`, `:646`), had no closing check. Past the checks before their assertion, they throw it after the downcall,
    also where the length check after it (`:609-612`, `:648-651`) makes the export return. Their only caller,
    `View.uploadPixels`, calls `lock()` first (`View.java:793`), whose `_begin` throws on a detached main thread. So
    an upload meets the condition only when the main thread detaches between that `_begin` and the upload.
  - `_setParent`, whose body after `if (!jPtr) return;` (`:423`) is the assertion alone (`:425`), throws it for every
    nonzero `ptr`, parent 0 included (`MacViewCalls.setParent`). `View.setWindow` (`View.java:502-508`) again leaves
    after `this.window` is set and before `isValid` is, and the throw cuts its callers short, as under JNI:
    - `Window.setView` at the old view's `setWindow(null)` (`Window.java:460`), before `_setView` (`:469`), or at the
      new view's (`:471`), before `_updateViewSize` (`:475`);
    - `Window.close` before `view.close()` and the window's `_close` (`:404-409`);
    - `View.close`, through `host.setView(null)`, before `isValid = false` and its `_close` (`View.java:526-529`).

  What remains are the bodies that crashed on the NULL `JNIEnv`. They now have a defined outcome:
  - `_close` of a view in a window. Its `removeFromSuperview` dials VU2, whose `GET_MAIN_JENV` printed
    `GLASS_CALLSTACK` (`GlassViewDelegate.m:216`) before the call used the NULL `jEnv` (`:238`). The FFM site prints
    the same text and skips the slot (PB14), the export returns, and Java reports once (`GlassView.m:452`). The
    dealloc skips `notify_dispose` silently, so the view's id stays in the registry, where the JNI's dealloc skipped
    its `DeleteGlobalRef` (`GlassViewDelegate.m:195-200`).
  - `_enterFullscreen`, and `_exitFullscreen` of a view in native full screen. After `toggleFullScreen:`,
    `enterFullScreenExitingLoop`'s `GET_MAIN_JENV` printed `GLASS_CALLSTACK` and the JNI crashed at
    `GlassApplication.m:841`. Now the App slot `enter_nested_event_loop` prints and is skipped too (g1 §6 (d); until
    slice 11 part 3 it was dialled after the print), and so are the FULLSCREEN_ENTER and FULLSCREEN_EXIT view slots.
    Java then reports once (`GlassView.m:538`, `:561`).
  - `_exitFullscreen` of any other view. `sendJavaFullScreenEvent:NO` printed `GLASS_CALLSTACK`
    (`GlassViewDelegate.m:1237`) and the JNI crashed at `:1238`. Now FULLSCREEN_EXIT is skipped after the print, and
    Java reports once (`GlassView.m:561`).
  - `_create` (assertion at `:281`). The GlassView3D initializer's `GET_MAIN_JENV` printed `GLASS_CALLSTACK`
    (`GlassView3D.m:86`), and the NULL `JNIEnv` was used at the first `Map` read (`:90`) or, with no map, by the
    delegate's `NewGlobalRef` (`GlassViewDelegate.m:149`) at the latest. Now the delegate's
    `GLASS_WARN_IF_MAIN_JAVA_DETACHED` (current `GlassViewDelegate.m:222`; `GET_MAIN_JENV` until slice 11 part 3)
    prints the same text once and the view is made, with no report: Java evaluates nothing around
    `gmac_view_create`, because the JNI never reached a closing check. A crash became a normal result, as
    `_getDataDirectory`'s did in g1 §6 (c), but without its report.

  The window exports never had this divergence. `MacWindowCalls` evaluates `gmac_main_thread_detached` for each of
  the 28 whose native asserted (all but `_toFront`), on whatever thread calls it, and reports after the downcall,
  where each closing `GLASS_CHECK_EXCEPTION` did (current `MacWindowCalls.java:157-164`, `:373-386`; the timing is
  in (d)).
- (m) The multi-click maxima's assertion, a race not reproduced. `MacView`'s initializer called
  `_getMultiClickTime_impl`, then `_getMultiClickMaxX_impl` and `_getMultiClickMaxY_impl` (`MacView.java:47-49`).
  Each opened with `GLASS_ASSERT_MAIN_JAVA_THREAD` (`GlassView.m:229`, `:245`, `:262`), and none had a closing check.
  A main thread already detached at the first call makes `_getMultiClickTime_impl` throw in both builds (§7
  "Detached-thread check"). Under JNI, if `MacView` was initialized off the main thread and the main thread detached
  between those calls, after `_getMultiClickTime_impl`'s assertion found it attached, the `ThrowNew` of MaxX's or
  MaxY's surfaced as `ExceptionInInitializerError`. Now the maxima are the constant 3 (current
  `MacViewCalls.MULTI_CLICK_MAX`), so `<clinit>` succeeds. The window has no fixed width in either build; it depends
  on scheduling.

### 7. Proposed C ABI header

These files are new: `native-glass/mac/glass_mac_api.h` and `glass_mac_api.m`. CMake's `file(GLOB)` picks them up
after a configure run. Conventions follow the GTK precedent: `int64_t` peer ids, an `int32_t` status from every slot,
tables installed once, and no `void* user`.

```c
#include <stdint.h>
#define GMAC_EXPORT __attribute__((visibility("default")))
#define GLASS_MAC_ABI_VERSION 1            /* = MacGlassNative.ABI_VERSION */
typedef void* gmac_window_t;                /* NSWindow* (GlassWindow_Normal/_Panel) == Window.ptr */
typedef void* gmac_view_t;                  /* GlassView3D* == View.ptr */
#define GMAC_UPCALL_OK 0
#define GMAC_UPCALL_THREW 1

/* Constants now taken from javac -h headers, stated here and pinned by a Java test
 * (AC 9 removes HEADERS_DIR; MacGestureSupport.h disappears with its last native). */
#define GMAC_WINDOW_TITLED 1      /* TRANSPARENT 2, EXTENDED 4, UTILITY 8, POPUP 16, CLOSABLE 32,
                                     MINIMIZABLE 64, MAXIMIZABLE 128, UNIFIED 512, DARK_FRAME 2048 */
#define GMAC_LEVEL_FLOATING 2     /* TOPMOST 3 */
/* WindowEvent RESIZE 511, MINIMIZE 531, MAXIMIZE 532, RESTORE 533, FOCUS_LOST 541, FOCUS_GAINED 542;
 * ViewEvent ADD 411, REMOVE 412, FULLSCREEN_ENTER 431, FULLSCREEN_EXIT 432;
 * DndEvent ENTER 611, UPDATE 612, PERFORM 613, EXIT 614; SwipeGesture DIR_UP 1 .. DIR_RIGHT 4;
 * Clipboard ACTION_NONE 0, COPY 1, MOVE 2, REFERENCE 0x40000000;
 * MacGestureSupport GESTURE_ROTATE 100, MAGNIFY 101, SWIPE 102, SCROLL_SRC_WHEEL 50, GESTURE 51, INERTIA 52;
 * plus the 13 MouseEvent and 17 KeyEvent names listed in §11. */
GMAC_EXPORT int32_t gmac_constant_count(void);
GMAC_EXPORT int32_t gmac_constant(int32_t index);      /* -1 out of range (es2_gl_enum pattern) */

GMAC_EXPORT int32_t gmac_abi_version(void);
GMAC_EXPORT int32_t gmac_main_thread_detached(void);   /* !pthread_main_np() && run loop not attached
                                                          (the jEnv==NULL condition; flag owned by the
                                                          MacApplication slice) */
/* ---- Window: all FX/main thread; every function is a no-op returning 0 for w == NULL, as `if (!jPtr)` was ---- */
GMAC_EXPORT gmac_window_t gmac_window_create(gmac_window_t owner, void* ns_screen, int32_t style_mask, int64_t window_id);
GMAC_EXPORT int32_t gmac_window_close(gmac_window_t w);                       /* 1; close is deferred */
GMAC_EXPORT int32_t gmac_window_set_view(gmac_window_t w, gmac_view_t v);     /* v NULL = Java null */
GMAC_EXPORT int32_t gmac_window_set_menubar(gmac_window_t w, void* glass_menubar);
GMAC_EXPORT int32_t gmac_window_minimize(gmac_window_t w, int32_t minimize);
GMAC_EXPORT int32_t gmac_window_maximize(gmac_window_t w, int32_t maximize, int32_t is_zoomed);
GMAC_EXPORT void    gmac_window_set_bounds(gmac_window_t w, int32_t x, int32_t y, int32_t x_set, int32_t y_set,
                                           int32_t width, int32_t height, int32_t cw, int32_t ch,
                                           float x_gravity, float y_gravity);  /* gravities ignored, as today */
GMAC_EXPORT int32_t gmac_window_set_visible(gmac_window_t w, int32_t visible); /* returns isVisible */
GMAC_EXPORT int32_t gmac_window_set_resizable(gmac_window_t w, int32_t resizable);
GMAC_EXPORT int32_t gmac_window_request_focus(gmac_window_t w);                /* returns isKeyWindow */
GMAC_EXPORT void    gmac_window_set_focusable(gmac_window_t w, int32_t focusable);   /* == 1 */
GMAC_EXPORT int32_t gmac_window_set_title(gmac_window_t w, const uint16_t* utf16, int32_t length); /* NULL == "" */
GMAC_EXPORT void    gmac_window_set_level(gmac_window_t w, int32_t level);
GMAC_EXPORT void    gmac_window_set_alpha(gmac_window_t w, float alpha);
GMAC_EXPORT int32_t gmac_window_set_background(gmac_window_t w, float r, float g, float b);
GMAC_EXPORT void    gmac_window_set_enabled(gmac_window_t w, int32_t enabled);
GMAC_EXPORT int32_t gmac_window_set_minimum_size(gmac_window_t w, int32_t width, int32_t height);
GMAC_EXPORT int32_t gmac_window_set_maximum_size(gmac_window_t w, int32_t width, int32_t height); /* -1 = FLT_MAX */
GMAC_EXPORT void    gmac_window_set_icon(gmac_window_t w, const uint8_t* bgra_pre, int32_t width, int32_t height);
                    /* NULL clears; borrowed for the call (CFDataCreate copies); passes (h, w) swapped, as today */
GMAC_EXPORT void    gmac_window_set_dark_frame(gmac_window_t w, int32_t dark);
GMAC_EXPORT void    gmac_window_to_front(gmac_window_t w);
GMAC_EXPORT void    gmac_window_to_back(gmac_window_t w);
GMAC_EXPORT int32_t gmac_window_grab_focus(gmac_window_t w);
GMAC_EXPORT void    gmac_window_ungrab_focus(gmac_window_t w);
GMAC_EXPORT void    gmac_window_perform_drag(gmac_window_t w);
GMAC_EXPORT void    gmac_window_perform_title_bar_double_click_action(gmac_window_t w);
GMAC_EXPORT void    gmac_window_set_button_style(gmac_window_t w, int32_t toolbar_style, int32_t buttons_visible);
GMAC_EXPORT void    gmac_window_set_button_appearance(gmac_window_t w, int32_t dark);

/* ---- View ---- */
typedef struct GmacViewCaps {         /* Java resolves Application.getDeviceDetails(); 40 bytes, align 8 */
    int64_t mtl_command_queue;        /*  0 "mtlCommandQueue" (Long), 0 if absent */
    int64_t share_context;            /*  8 "shareContextPtr" (Long, NSOpenGLContext*), 0 if absent */
    int64_t context;                  /* 16 "contextPtr" (Long, NSOpenGLContext*), 0 if absent */
    int32_t depth_bits;               /* 24 View.Capability.k3dDepthKey (Integer), 0 if absent */
    int32_t hidpi_aware;              /* 28 kHiDPIAwareKey (Boolean), 0 if absent */
    int32_t mtl_for_sw;               /* 32 "useMTLInGlassForSW" (Boolean), 0 if absent */
    int32_t vsync_enabled;            /* 36 "isVsyncEnabled" (Long) == 1; **1 if absent** */
} GmacViewCaps;                       /* caps == NULL <=> Map null: every default above */
GMAC_EXPORT gmac_view_t gmac_view_create(int64_t view_id, const GmacViewCaps* caps);   /* FX */
GMAC_EXPORT int64_t gmac_view_get_native_frame_buffer(gmac_view_t v); /* RENDER thread: GLuint FBO or id<MTLTexture> */
GMAC_EXPORT int32_t gmac_view_get_x(gmac_view_t v);
GMAC_EXPORT int32_t gmac_view_get_y(gmac_view_t v);
GMAC_EXPORT int32_t gmac_view_close(gmac_view_t v);
GMAC_EXPORT void    gmac_view_begin(gmac_view_t v);                    /* RENDER thread, pushes the per-thread pool */
GMAC_EXPORT void    gmac_view_end(gmac_view_t v);                      /* RENDER thread, pops it */
GMAC_EXPORT void    gmac_view_schedule_repaint(gmac_view_t v);
GMAC_EXPORT int32_t gmac_view_enter_fullscreen(gmac_view_t v, int32_t animate, int32_t keep_ratio, int32_t hide_cursor);
GMAC_EXPORT void    gmac_view_exit_fullscreen(gmac_view_t v, int32_t animate);
GMAC_EXPORT void    gmac_view_upload_pixels_direct(gmac_view_t v, const void* pixels, int32_t width, int32_t height,
                                                   float sx, float sy);          /* NULL returns; no size check (as today) */
GMAC_EXPORT void    gmac_view_upload_pixels_bytes(gmac_view_t v, const uint8_t* array, int32_t array_length,
                                                  int32_t offset, int32_t width, int32_t height, float sx, float sy);
GMAC_EXPORT void    gmac_view_upload_pixels_ints(gmac_view_t v, const uint32_t* array, int32_t array_length,
                                                 int32_t offset, int32_t width, int32_t height, float sx, float sy);
                    /* bytes/ints keep the JNI's offset, overflow and length guards verbatim, in C */
GMAC_EXPORT void    gmac_view_enable_input_method_events(gmac_view_t v, int32_t enable);   /* == 1 */
GMAC_EXPORT void    gmac_view_finish_input_method_composition(gmac_view_t v);
GMAC_EXPORT int64_t gmac_view_multi_click_time_ms(void);   /* (int64)(doubleClickInterval * 1000.0f) */

/* ---- tables, probes, test hooks ---- */
GMAC_EXPORT int32_t gmac_window_set_callbacks(const struct GmacWindowCallbacks* cb);  /* copied by value; NULL clears */
GMAC_EXPORT int32_t gmac_view_set_callbacks(const struct GmacViewCallbacks* cb);
GMAC_EXPORT int32_t gmac_gesture_set_callbacks(const struct GmacGestureCallbacks* cb);
GMAC_EXPORT int32_t gmac_sizeof_window_callbacks(void);   /* 80 */
GMAC_EXPORT int32_t gmac_sizeof_view_callbacks(void);     /* 120 */
GMAC_EXPORT int32_t gmac_sizeof_gesture_callbacks(void);  /* 40 */
GMAC_EXPORT int32_t gmac_sizeof_screen_info(void);        /* 88 */
GMAC_EXPORT int32_t gmac_sizeof_view_caps(void);          /* 40 */
GMAC_EXPORT int64_t gmac_test_fire_window_callback(int32_t slot, int64_t window_id);
GMAC_EXPORT int64_t gmac_test_fire_view_callback(int32_t slot, int64_t view_id);
GMAC_EXPORT int64_t gmac_test_fire_gesture_callback(int32_t slot, int64_t view_id);
GMAC_EXPORT int32_t gmac_test_rtl_objc(void);   /* the current NSLocale body, JNI-free: oracle for the W27 A/B */
/* Only if the A/B fails: GMAC_EXPORT int32_t gmac_is_right_to_left_layout_direction(void); */
```

**Totals.** 29 window functions, 16 view functions and 16 plumbing/test functions: 61 exports. `_initIDs` ×3,
`getMultiClickMax{X,Y}`, `_setParent` and `_isRightToLeftLayoutDirection` get no export.

**As built (slice 5).** The window part of `glass_mac_api.h` exports:
- 29 `gmac_window_*` functions: `create`, `close`, `leave_native_full_screen`, `set_view`, `set_menubar`,
  `minimize`, `maximize`, `set_bounds`, `set_visible`, `set_resizable`, `request_focus`, `set_focusable`,
  `set_title`, `set_level`, `set_alpha`, `set_background`, `set_enabled`, `set_minimum_size`, `set_maximum_size`,
  `set_icon`, `set_dark_frame`, `to_front`, `to_back`, `grab_focus`, `ungrab_focus`, `perform_drag`,
  `perform_title_bar_double_click_action`, `set_button_style` and `set_button_appearance`. The first build had 28;
  review round 1 split `leave_native_full_screen` out of `_setView` (§6 (c));
- `gmac_sizeof_window_callbacks` (80);
- `gmac_window_install_callbacks`, in place of the sketch's `gmac_window_set_callbacks`;
- three shim-only hooks:
  - `gmac_test_fire_window_callback`;
  - `gmac_test_fire_window_callback_with_pending`, which dialled a slot with an exception pending (§6 (e)). Slice 11
    part 3 deleted it with `GlassDialSlotKeepingPending`;
  - `gmac_test_nslocale_language_direction`, in place of the sketch's `gmac_test_rtl_objc`. It runs the former W27
    body and answers its `NSLocaleLanguageDirection`, not the boolean, as the oracle of the CF chain. It stays in
    the shipping libglass on purpose (PB19, §10).

W27 has no export, and the fallback `gmac_is_right_to_left_layout_direction` was not added: Java binds CF (§10).

The cleanup landed in the same slice. Deleted: the 30 MacWindow `Java_*` bodies, `_createWindowCommon`, `getMacView`,
the `jWindow` ivar (the window is keyed by `windowId`), `createJavaScreen`/`newJavaScreen`, `jWindowClass`,
`jMenuBarDelegateClass` and the 10 `jWindowNotify*` IDs. `_createWindowCommonDo` stays as the static `createWindow`.
The window upcall sites' JNI branches went too (PB16): only the deleted `_createWindow` set `jWindow`, so nothing
could reach them. libglass now exports 77 `_Java_` symbols, plus `JNI_OnLoad`.

**As built (slice 6), C side.** The view and gesture part of `glass_mac_api.h` exports:
- 16 `gmac_view_*` functions, the sketch's 16 under its names: `create`, `get_native_frame_buffer`, `get_x`, `get_y`,
  `close`, `begin`, `end`, `schedule_repaint`, `enter_fullscreen`, `exit_fullscreen`, `upload_pixels_direct`,
  `upload_pixels_bytes`, `upload_pixels_ints`, `enable_input_method_events`, `finish_input_method_composition` and
  `multi_click_time_ms`. They take the `GlassView3D*` as `void *view`; there is no `gmac_view_t`.
  `gmac_view_multi_click_time_ms` is kept as an OS-CALL: `+[NSEvent doubleClickInterval]` has no C stand-in with
  parity (§10). It runs on any thread, without a pool;
- `GmacViewCaps` with `void*` members for the three pointers, as R3 item 8 resolved (PB1), in place of the sketch's
  `int64_t`. It is 40 bytes: `mtl_command_queue` 0, `share_context` 8, `context` 16, `depth_bits` 24, `hidpi_aware`
  28, `mtl_for_sw` 32 and `vsync_enabled` 36. The defaults are NULL and 0, but 1 for `vsync_enabled`, and a NULL caps
  stands for every default;
- the 15-slot `GmacViewCallbacks` (120 bytes) and the 5-slot `GmacGestureCallbacks` (40 bytes), in the slot order of
  the §8 sketch;
- 6 plumbing exports: `gmac_sizeof_view_caps` (40), `gmac_sizeof_view_callbacks` (120),
  `gmac_sizeof_gesture_callbacks` (40), `gmac_offsetof_view_caps`, `gmac_view_install_callbacks` and
  `gmac_gesture_install_callbacks`. The installers keep slice 5's `*_install_callbacks` naming, in place of the
  sketch's `gmac_view_set_callbacks` and `gmac_gesture_set_callbacks`;
- five shim-only hooks:
  - `gmac_test_fire_view_callback`, which, unlike the sketch, takes an `out` buffer for the slots' out-parameters;
  - `gmac_test_fire_gesture_callback`;
  - `gmac_test_fire_key_event`, which dials `notify_key` as one key event through the code the views use, so it sets
    `same_chars_as_previous`; a slot may call it again, as a nested event would dial the slot (§8);
  - `gmac_test_fire_input_method`, which dials `notify_input_method` through the code the views use;
  - `gmac_test_default_view_caps`, the `GmacViewCaps` that `gmac_view_create` uses for a NULL caps.

`_getMultiClickMax{X,Y}_impl`, `_setParent` and the V0 and G0 `_initIDs` get no export (§9). Until the cleanup, both
paths stayed (PB6): every view and gesture site dialled its slot when its table was installed and otherwise took its
JNI path, and the JNI `_create` shared `createView` with `gmac_view_create` (§6 (j)). The Java flip of MacView and
MacGestureSupport came next, then the cleanup.

The cleanup landed after the Java flip, in the same slice. Deleted:
- GlassGestureSupport.m (122 lines);
- in GlassView.m: the 20 MacView `Java_*` bodies, the MacView `_initIDs` among them; `readCapabilities`;
  `capabilityForName`, `capabilityForKey`, `capabilityPointer` and `capabilityBoolean`; and the `View_Capability`
  import;
- in GlassViewDelegate.m: every JNI `else` branch, with `SEND_KEY_EVENT`, `SEND_MODIFIER_KEY_EVENT`,
  `SEND_MODIFIER_KEY_EVENT_WITH_TYPE`, `convertNSStringToJString`, the `jView` ivar and `-jView`. Only the JNI
  `_create` set `jView`, so nothing could reach them (PB16);
- the `withJview:` parameter of GlassView.h, GlassView3D.h/.m, GlassViewDelegate.h/.m and `createView`;
- `GetJavaKeyChars` (GlassKey.h/.m). The hidden `GetJavaKeyCharsUtf16` stays;
- `javaIDs.GestureSupport`, `jViewClass` and the 15 `jView*` method IDs, `jIntegerClass`, `jLongClass`,
  `jIntegerInitMethod`, `jIntegerValueMethod`, `jLongValueMethod`, `jMapGetMethod` and `jBooleanValueMethod`.
  `jMapClass` and `jBooleanClass` stay.

Kept: at the view sites, `GET_MAIN_JENV` still prints `GLASS_CALLSTACK` and then skips the slot (PB14), and `dealloc`
uses `GET_MAIN_JENV_NOWARN`. (Slice 11 part 3 replaced the first with `GLASS_WARN_IF_MAIN_JAVA_DETACHED` and the
second with its main-thread `assert`.) libglass now exports 56 `_Java_` symbols, plus `JNI_OnLoad` and 122
`gmac_*`; the `gmac_*` set is unchanged.

**Detached-thread check.** `GLASS_ASSERT_MAIN_JAVA_THREAD` can only fire off the main thread. The view calls made
there by design are `_begin`, `_end` and `_getNativeFrameBuffer` (render thread) and `_getMultiClickTime_impl`
(whichever thread initializes MacView); the two multi-click maxima ran in `MacView`'s initializer with the time
(`MacView.java:47-49`). The other 13 view entry points are reached through `Application.checkEventThread()`, and
only `glass.disableThreadChecks`, which turns that check off (`Application.java:437`), lets them run elsewhere. PB18,
decided after slices 6 and 7, makes Java reproduce the detached outcome of every view call whose native asserted,
also where only that property reaches it, as `MacWindowCalls` does. For any view but NULL, and for
`_getMultiClickTime_impl`, which takes none, Java evaluates `gmac_main_thread_detached()` before the downcall, makes
the downcall all the same, and after it:
- throws `RuntimeException("Main Java thread is detached.")` where the native had no closing check: `_begin`, `_end`,
  `_getMultiClickTime_impl`, and each upload that got past the checks its native made before the assertion
  (`MacViewCalls.arrayUploadAsserts`);
- reports the exception once through `Application.reportException` where the native closed with
  `GLASS_CHECK_EXCEPTION`: `_getNativeFrameBuffer`, `_getX`, `_getY`, `_close`, `_scheduleRepaint`,
  `_enterFullscreen`, `_exitFullscreen`, `_enableInputMethodEvents` and `_finishInputMethodComposition`.

`_setParent` has no export: for any view but NULL, Java throws the exception itself (`MacViewCalls.setParent`), as
the native, which was the assertion alone, did on return. Java evaluates nothing around `_create`, whose native
crashed on the NULL `JNIEnv` before its closing check, nor for `_getMultiClickMax{X,Y}_impl`, now the constant 3
(§9). Their assertion could fire only if the main thread detached between those calls in `MacView`'s initializer,
after `_getMultiClickTime_impl`'s found it attached, a race not reproduced (§6 (m)). On the event thread
`pthread_main_np()==1`, so the condition is false there and nothing is reported or thrown. `MacViewNativeTest`
pins each frame detached, attached and for the NULL view. What still differs from the JNI while the main thread is
detached is in §6 (l) and (m).

The 28 window exports whose natives asserted (all but `_toFront`) evaluate it as well, on any thread, and report
after the downcall (slice 5, §6 (d)).

### 8. Callback tables

```c
typedef struct GmacScreenInfo {      /* createJavaScreen's 20 values; 88 bytes, align 8 */
    void*   ns_screen;               /*  0 NSScreen* (the C retains it: the deliberate leak, kept) */
    int32_t depth, x, y, width, height;                                 /*  8..24 */
    int32_t platform_x, platform_y, platform_width, platform_height;    /* 28..40 */
    int32_t visible_x, visible_y, visible_width, visible_height;        /* 44..56 */
    int32_t resolution_x, resolution_y;                                 /* 60, 64 */
    float   platform_scale_x, platform_scale_y, output_scale_x, output_scale_y; /* 68..80 (1,1,s,s) */
} GmacScreenInfo;                    /* shared with the MacApplication screen enumeration */

typedef struct GmacWindowCallbacks { /* 10 pointers, 80 bytes; all main thread */
    int32_t (*notify_move)(int64_t window_id, int32_t x, int32_t y, int32_t is_zoomed);          /* WU3 */
    int32_t (*notify_resize)(int64_t window_id, int32_t type, int32_t width, int32_t height);     /* WU4 */
    int32_t (*notify_move_to_another_screen)(int64_t window_id, const GmacScreenInfo* screen);   /* WU1 */
    int32_t (*notify_scale_changed)(int64_t window_id, float psx, float psy, float osx, float osy); /* WU2: C does (float)newScale */
    int32_t (*notify_close)(int64_t window_id);                                                 /* WU10 */
    int32_t (*notify_focus)(int64_t window_id, int32_t event);                                  /* WU7, WU8 */
    int32_t (*notify_focus_ungrab)(int64_t window_id);                                          /* WU5 */
    int32_t (*notify_focus_disabled)(int64_t window_id);                                        /* WU6 */
    int32_t (*notify_destroy)(int64_t window_id);                                               /* WU9 */
    int32_t (*notify_dispose)(int64_t window_id);  /* where Overrides.m:51 DeleteGlobalRef ran: the only place
                                                      Java may drop the window registry entry */
} GmacWindowCallbacks;

typedef struct GmacViewCallbacks {   /* 15 pointers, 120 bytes; all main thread */
    int32_t (*notify_view)(int64_t view_id, int32_t type);                                       /* VU1, VU2, VU30 */
    int32_t (*notify_resize)(int64_t view_id, int32_t width, int32_t height);                   /* VU3, VU4 */
    int32_t (*notify_repaint)(int64_t view_id, int32_t x, int32_t y, int32_t w, int32_t h);     /* VU5 */
    int32_t (*notify_menu)(int64_t view_id, int32_t x, int32_t y, int32_t x_abs, int32_t y_abs,
                           int32_t is_keyboard_trigger);                                         /* VU6 */
    int32_t (*notify_mouse)(int64_t view_id, int32_t type, int32_t button, int32_t x, int32_t y,
                            int32_t x_abs, int32_t y_abs, int32_t modifiers,
                            int32_t is_popup_trigger, int32_t is_synthesized);                  /* VU8 HOT */
    int32_t (*notify_key)(int64_t view_id, int32_t type, int32_t key_code, const uint16_t* chars,
                          int32_t char_count, int32_t modifiers, int32_t same_chars_as_previous,
                          int32_t* out_consumed);       /* VU9-VU18; chars NULL only where NewCharArray failed */
    int32_t (*notify_input_method)(int64_t view_id, const uint16_t* text, int32_t text_len, int32_t attr,
                                   int32_t length, int32_t cursor, int32_t sel_start, int32_t sel_length);
                                   /* VU28: sel_* = (int32_t)NSUInteger, so NSNotFound arrives as -1 */
    int32_t (*notify_input_method_candidate_pos_request)(int64_t view_id, int32_t pos,
                                                         double* out_xy, int32_t* out_valid);   /* VU29 */
    int32_t (*notify_drag_enter)(int64_t view_id, int32_t x, int32_t y, int32_t x_abs, int32_t y_abs,
                                 int32_t recommended_action, int32_t* out_action);              /* VU23 */
    int32_t (*notify_drag_over)(/* same as notify_drag_enter */ int64_t, int32_t, int32_t, int32_t, int32_t,
                                int32_t, int32_t*);                                            /* VU24 */
    int32_t (*notify_drag_drop)(int64_t, int32_t, int32_t, int32_t, int32_t, int32_t, int32_t*); /* VU25 */
    int32_t (*notify_drag_leave)(int64_t view_id);                                              /* VU26 */
    int32_t (*notify_drag_end)(int64_t view_id, int32_t performed_action);                      /* VU27 */
    int32_t (*get_accessible)(int64_t view_id, void** out_accessible);                          /* VU31 */
    int32_t (*notify_dispose)(int64_t view_id);    /* where ViewDelegate.m:199 DeleteGlobalRef ran */
} GmacViewCallbacks;

typedef struct GmacGestureCallbacks { /* 5 pointers, 40 bytes; targets are MacGestureSupport statics */
    int32_t (*rotate_gesture_performed)(int64_t view_id, int32_t mods, int32_t x, int32_t y,
                                        int32_t x_abs, int32_t y_abs, float rotation);           /* VU19 */
    int32_t (*scroll_gesture_performed)(int64_t view_id, int32_t mods, int32_t sender, int32_t x, int32_t y,
                                        int32_t x_abs, int32_t y_abs, float dx, float dy);       /* VU7 HOT: C does (float)rotationX/Y */
    int32_t (*swipe_gesture_performed)(int64_t view_id, int32_t mods, int32_t dir, int32_t x, int32_t y,
                                       int32_t x_abs, int32_t y_abs);                            /* VU20 */
    int32_t (*magnify_gesture_performed)(int64_t view_id, int32_t mods, int32_t x, int32_t y,
                                         int32_t x_abs, int32_t y_abs, float scale);             /* VU21 */
    int32_t (*gesture_finished)(int64_t view_id, int32_t mods, int32_t x, int32_t y,
                                int32_t x_abs, int32_t y_abs);                                   /* VU22 */
} GmacGestureCallbacks;               /* the touch slots are omitted: no site ever called them */
```

Contract the header must state:

- **Exceptions.** A slot catches every `Throwable` and reports it exactly as `GLASS_CHECK_EXCEPTION` did:
  `Application.reportException(t)`, swallowing any second throw. It then returns `GMAC_UPCALL_THREW`. An exception
  escaping an upcall stub terminates the JVM, so this is mandatory.
- **Out-parameters after a throw.** Value slots write out-parameters only on a normal return. The C pre-initializes
  them to the values HotSpot returned after a throw: `*out_consumed = 0`, `*out_action = 0` (ACTION_NONE),
  `*out_valid = 0`, `*out_accessible = NULL`. These are also what a NULL slot leaves.
- **The window C reads no status.** Until slice 11 part 3 it branched on it in one place: `GlassDialSlotKeepingPending`
  restored a still-JNI pending exception unless the slot answered `GMAC_UPCALL_THREW`.
- **Sites that change behaviour.** WU1–WU10, VU17 and VU18 were unchecked. They are now reported immediately; see
  Risk 3. Slice 5 built this for WU1–WU10 (§6 divergence (a)).
- **A pending JNI exception across a slot.** An FFM upcall stub clears it. Until slice 11 part 3, window slots 0, 1
  and 3-8 took it off the thread before the slot and restored it unless the slot threw, and slots 2 and 9 reported
  what was pending before the slot instead (§6 divergence (e)). Part 3 deleted both with the last JNI site, the only
  thing that could leave one pending.
- **Identity.** `window_id` and `view_id` are Java-assigned registry keys passed to `gmac_window_create` /
  `gmac_view_create`. Java must register `id → peer` **before** the create downcall, because WU3 and WU4 can fire
  inside `_initWithContentRect:` → `_setFlipFrame:` before `Window.ptr` is assigned. Entries are removed **only** in
  `notify_dispose`, with one exception: `MacViewCalls.create` also removes the view id when `gmac_view_create`
  answers NULL or its downcall throws (allocating the caps, or PB13's missing symbol). No `GlassViewDelegate` was
  made then: GlassView3D.m:92-108 makes it only after `[super initWithFrame:]` answered non-nil. So no slot and no
  `notify_dispose` can come for that id. Upcalls after `notifyDestroy` and before dealloc (for example a FOCUS_LOST)
  are still delivered today.
- **Ids the registry does not hold.** As built for windows (slice 5), such an id runs nothing
  (`MacWindowCallbacks.java:59`), and the GTK rule (id 0 reports a `NullPointerException`, as a NULL JNI receiver
  did) is not used. Ids start at 1 (`MacWindowCallbacks.java:106`), and id 0 cannot reach a slot:
  `_initWithContentRect:` stores `windowId` (`GlassWindow+Java.m:162`) where the JNI stored `jWindow`, before any
  delegate notification can fire. A view slot that could fire before its id is stored would need the
  `NullPointerException` report to stay neutral.
- **Gating on the run loop.** Every slot call is gated on the run-loop-attached flag (today's `jEnv != NULL`):
  - At the `GET_MAIN_JENV_NOWARN` and raw-`jEnv` sites (WU8, WU9, WU10, both disposes), skip silently when the loop
    is not attached.
  - At the `GET_MAIN_JENV` sites, today's code prints `GLASS_CALLSTACK` and then dereferences NULL. That crash cannot
    be carried over sensibly. Slice 5 built "print, then skip" for WU1–WU7, recorded as a divergence (§6 (b)).
- **Lifetime.** All stubs are created once in `Arena.global()`, following the GtkGlassNative.java:337 precedent:
  AppKit can call any slot at any time for the life of the process. Every pointer handed to a slot
  (`GmacScreenInfo`, `chars`, `text`, `out_*`) is C stack or C-owned and borrowed for that call only.
  `out_accessible` is a `GlassAccessible*` owned by MacAccessible; the C does not retain it.
- **Strings.** UTF-16 code units in both directions, lone surrogates preserved (`new String(char[])` matches
  `NewString`; `toCharArray()` matches `GetStringChars`). No modified UTF-8 anywhere.
- **Key array identity.** The JNI passed every dial of one `sendJavaKeyEvent` or `sendJavaModifierKeyEvent` the
  same `jcharArray`. `same_chars_as_previous` is 1 only when the immediately preceding `notify_key` dial was the same
  event's; Java then passes the `char[]` it passed that dial. It is 0 for an event's first dial and for a dial that
  follows a nested event's dial: Java then builds a new `char[]` with the same units, so identity is lost after a
  nested event (§6 (i)). This replaces GTK's address match; it is needed because `GetJavaKeyChars` uses a stack
  buffer whose address repeats across events. Slice 6 built it (`GlassDialKeySlot`; test hook
  `gmac_test_fire_key_event`).

### 9. Deletion candidates (cleared the parity gate)

| Function | Parity | C lines | Copies | Java counterpart | Parity test |
|---|---|---|---|---|---|
| `_getMultiClickMaxX_impl`, `_getMultiClickMaxY_impl` (GlassView.m:235-267) | **exact**, but for a race not reproduced (§6 (m), PB7). The value is the literal 3. `_getMultiClickTime_impl` has the same macro, no trailing check, and runs first in `<clinit>` (MacView.java:47-49), so a main thread already detached there makes it throw first. The maxima's assertion could fire only if the main thread detached between those calls, after `_getMultiClickTime_impl`'s found it attached | ~33 | 1 (macOS) | the literal `3`. Done in slice 6: no export | `MacView.getMultiClickMax{X,Y}_impl() == 3`, plus a structural test that neither method is `native` |
| `_setParent` (GlassView.m:412-427) | **exact** (PB18). No-op body after the assertion. Its only caller, `View.setWindow`, runs `Application.checkEventThread()` first (View.java:503), so the macro can fire only when `glass.disableThreadChecks` lets it run off the main thread while that thread is detached; Java then throws the detached exception for any view but NULL, as the native did on return (§7 "Detached-thread check") | ~16 | 1 | `MacViewCalls.setParent(ptr)`, which `MacView._setParent` calls with the view alone. Done in slice 6: no export | `MacViewNativeTest`: `aDetachedSetParentThrowsForEveryViewButTheNullOneAndAnAttachedOneDoesNot`, `setParentHandsItsFrameTheViewAlone` (bytecode) and `neitherClassDeclaresANativeMethod` |

These are exact replacements, so they can land with the migration. JNI-only deletions that need no gate: the three
`_initIDs` (GlassWindow.m:634-734, done in slice 5; GlassView.m:54-217; and all of GlassGestureSupport.m),
`getMacView` (GlassWindow.m:95-107, done in slice 5), the `Map`-read blocks, the `SetLongField` in `_create`, and the
5 dead IDs (`jWindowNotifyDelegatePtr` done in slice 5). Together that is roughly 560 lines of JNI, counted from the
cited ranges; some of it is replaced by ABI glue. The view and gesture ones are done in slice 6: the tables and
`gmac_view_create` replace them, and the slice-6 cleanup deleted the JNI `_create` with `readCapabilities`, the one
function the `Map` reads had become (§6 (j)).

### 10. Kept native despite being pure

- **W27 `_isRightToLeftLayoutDirection` (WRAPPER), `PARITY: exact`. No longer kept native (slice 5).** Java binds
  CoreFoundation (`MacWindowCalls.preferredLanguageDirection`), with no export. The shim-only
  `gmac_test_nslocale_language_direction` keeps the former body as the oracle. It stays in the shipping libglass on
  purpose (PB19), a deliberate exception and not a follow-up: the JNI body is gone and no golden of it exists, so the
  in-process A/B against the oracle is the only parity check that still runs, here and on other machines (x64 CI,
  other macOS releases). Evidence, on macOS 27.0.1 arm64:
  - **By construction (disassembly).** Both preferred-language calls reach
    `LocaleCache.preferredLanguages(forCurrentUser:false)`. `-[_NSPlaceholderLocale initWithLocaleIdentifier:]`
    tail-calls `CFLocaleCreate`. `CFLocaleGetValue` is `-objectForKey:` with the identical key.
    `CFLocaleGetLanguageCharacterDirection` sends `+[NSLocale characterDirectionForLanguage:]`.
  - **In the module tests** (PB2 (ii), PB3/PB12), the checked-in A/B.
    `MacWindowNativeTest.theCoreFoundationChainAnswersWhatTheNSLocaleBodyAnsweredForEachPreferredLanguage` runs the
    CF chain, the oracle and `_isRightToLeftLayoutDirection` in 19 child JVMs that load `libglass`, one per
    `-AppleLanguages` list (17 single languages and 2 two-language lists), each asserting `NSApp` nil before its
    first call and after its last.
  - **Headless probe programs** (supporting evidence, not checked in; Foundation and CoreFoundation only). They ran
    the NSLocale body and the CF chain in one process, under the same `-AppleLanguages` argument:
    - 56/56 cases are SAME: the default list, 52 single languages and 3 two-language lists;
    - the empty list `()` traps the same way in both chains, with exit 133;
    - all 8 steps of a run-time change are SAME: the start, the argument domain replaced in process five times, then
      removed, then `NSCurrentLocaleDidChangeNotification` posted.

  The three edge cases the audit listed are settled:
  - **An empty language list.** It traps (Swift's "Array index out of range", exit 133) in `objectAtIndex:0` and
    `CFArrayGetValueAtIndex` alike, so Java calls the chain unguarded, as the JNI body was.
  - **A nil language code.** It goes to the same `+characterDirectionForLanguage:` through
    `CFLocaleGetLanguageCharacterDirection`.
  - **`-AppleLanguages` in argv.** Both list calls reach the same `LocaleCache` function, and the A/B passed the
    argument and changed the argument domain at run time.
- **Pure arithmetic inside OS-CALL methods.** Examples: the style-mask bit logic (GlassWindow.m:460-519), the
  `_constrainFrame` clamp, the y-flip in `_flipFrame`/`_setFlipFrame`, the mouse type/button and modifier mapping
  (ViewDelegate.m:332-559), the drag-image scaling. None of these is a separable function. Moving any of them to Java
  would add a crossing per event on hot paths, so they are not candidates.
- **`GetDblTime()` as a stand-in for `+[NSEvent doubleClickInterval]`: `PARITY: unprovable`.** It has 1/60 s tick
  granularity, so V1 stays an ObjC export.

### 11. Build/test touchpoints, risks, recommendation

#### Generated headers

These must be restated in `glass_mac_api.h`; §7 pins them.

| Header | Included by | Constants used |
|---|---|---|
| `com_sun_glass_ui_Window.h` | GlassWindow.m:29 (+Java, +Overrides: unused) | TITLED, CLOSABLE, MINIMIZABLE, MAXIMIZABLE, TRANSPARENT, UTILITY, POPUP, UNIFIED, EXTENDED, DARK_FRAME (:460-469). TITLED, TRANSPARENT and EXTENDED are not `@Native` but are still emitted (that is my reading of javac -h) |
| `com_sun_glass_ui_Window_Level.h` | GlassWindow*.m | FLOATING, TOPMOST (:767, :770) |
| `com_sun_glass_events_WindowEvent.h` | GlassWindow*.m | RESTORE, MAXIMIZE, MINIMIZE, RESIZE, FOCUS_GAINED, FOCUS_LOST |
| `com_sun_glass_events_ViewEvent.h` | GlassWindow*.m and GlassView.m (unused), ViewDelegate.m | ADD, REMOVE, FULLSCREEN_ENTER, FULLSCREEN_EXIT |
| `com_sun_glass_events_MouseEvent.h` | ViewDelegate.m (GlassView3D.m: unused) | DOWN, UP, DRAG, MOVE, ENTER, EXIT, WHEEL, BUTTON_{NONE, LEFT, RIGHT, OTHER, BACK, FORWARD} |
| `com_sun_glass_events_KeyEvent.h` | ViewDelegate.m (GlassView3D.m: unused) | PRESS, RELEASE, TYPED, VK_{PAGE_UP, DOWN, SHIFT, CONTROL, ALT, COMMAND, CAPS_LOCK}, MODIFIER_{COMMAND, CONTROL, BUTTON_PRIMARY, SECONDARY, MIDDLE, BACK, FORWARD} |
| `com_sun_glass_events_DndEvent.h` | GlassView3D.m, ViewDelegate.m | ENTER, UPDATE, PERFORM, EXIT |
| `com_sun_glass_events_SwipeGesture.h` | ViewDelegate.m | DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT |
| `com_sun_glass_ui_Clipboard.h` | ViewDelegate.m (GlassView.m: unused) | ACTION_NONE, COPY, MOVE, REFERENCE |
| `com_sun_glass_ui_View_Capability.h` | GlassView3D.m, CGL, MTL | k3dDepthKeyValue 4, kHiDPIAwareKeyValue 5. These go away once `GmacViewCaps` exists. **Gone in slice 6:** nothing includes the header since `GmacViewCaps`, so the cleanup deleted `constants/com_sun_glass_ui_View_Capability.h`; 13 constants headers remain |
| `com_sun_glass_ui_mac_MacGestureSupport.h` | GestureSupport.m, ViewDelegate.m (GlassView3D.m: unused) | GESTURE_{ROTATE, SWIPE, MAGNIFY}, SCROLL_SRC_{WHEEL, INERTIA, GESTURE}. **This header stops being generated when `_initIDs` goes, which breaks the build** (the same failure Windows hit in s3-51) |
| `com_sun_glass_ui_View.h`, `…_mac_MacWindow.h`, `…_mac_MacView.h` | GlassView.m, GlassWindow*.m | prototypes only, or nothing |

#### Build

- **AC 9** needs an opt-in like `win.cmake`'s `JNI` flag; today CM:150-153 gives every target the JDK includes and
  `HEADERS_DIR`.
- **`-Werror`** is on, so removing JNI will surface unused-variable errors.
- **Layout pins:** C99 mode still accepts `_Static_assert` as a clang extension without `-pedantic`.
- **JNI-type residue:** 284 j-type lines remain across 20 files. The non-JNI files among them are GlassOffscreen.h/.m
  (`-(jlong)fbo`), CGL/MTLOffscreen, GlassView3D.h, GlassViewCGL/MTL.h/.m and GlassWindow+Java.h. `jni.h` is imported
  by 6 scope headers plus `common.h`.

#### Tests

- **Module tests:** none cover `com.sun.glass.ui.mac` today (`grep` over `modules/*/src/test` finds 0). The only shim
  is `MacPasteboardShim`. New headless tests are needed: symbols, `gmac_sizeof_*`, `gmac_constant` pins against the
  `@Native` fields, fire hooks with a distinct argument per slot, the `Map` → `GmacViewCaps` default table, and
  registry release.
- **Owner-run (agents must not run these; this Mac is not ready for robot runs):** `tests/system`
  `test/robot/javafx/stage/**` and `scene/**`; `test/javafx/stage/*` (FullScreenExitTest, MultipleScreensTest,
  ScreenTest, NestedEventLoopTest); `test/com/sun/glass/ui/ExceptionHandlerTest`; MacPasteboardTest.
- **Manual (owner):** IME (Japanese/Chinese, Keyman), trackpad rotate/magnify/swipe/inertial scroll, DnD in and out,
  ⌘-key RELEASE synthesis, Caps Lock, full screen, VoiceOver (AC 8, the `get_accessible` path), and each pipeline
  under `-Dprism.order=mtl|es2|sw`, since `GmacViewCaps` takes a different branch per pipeline.

#### Risks

1. **Registry lifetime.** Ids must live until `notify_dispose` (dealloc), not until `notifyDestroy` or `View.close`.
2. **Register before create.** Upcalls can fire during `gmac_window_create`, from the `MacWindow._createWindow`
   override while the superclass constructor is still running.
3. **Exception timing.** WU1–WU10 and VU17/VU18 left exceptions pending. They were reported at the next check or the
   enclosing export's tail. A later JNI upcall kept them: HotSpot sets a pending exception aside for each JNI call
   and restores it unless the call raised a new one (PB11). An FFM upcall stub instead clears it, whether the slot
   threw or not (the slice-5 probe, §6 (e)). So the window C takes it off the thread around slots 0, 1 and 3-8 and
   restores it unless the slot threw, and reports it before slots 2 and 9. FFM reports each slot's own exception
   immediately. This is the one divergence an upcall stub forces; list
   these sites in the header.
4. **`GLASS_ASSERT_MAIN_JAVA_THREAD` throws and continues** (47 sites). The exact Java mapping is in §7.
5. **Render-thread entries.** `_begin`, `_end` and `_getNativeFrameBuffer` must stay on the render thread, with no
   marshalling. `GLASS_POOL_PUSH` and `POP` span two downcalls on one thread's `GlassThreadDataKey` pool. That key is
   created in `JNI_OnLoad` (GlassApplication.m:91), which belongs to another slice and must move into an init export
   first.
6. **Uploads block.** JNI held a critical region across `waitUntilCompleted` (GlassMTLOffscreen.m:176) and `glFinish`
   (GlassCGLOffscreen.m:231). `Linker.Option.critical(true)` would reproduce that exactly, but PB5 excludes it for an
   export that waits. Slice 6 copies: `MacViewCalls.uploadPixelsBytes` and `uploadPixelsInts` copy the whole array
   into `Arena.ofConfined()` for the call, which costs w·h·4 bytes per upload (every frame on the SW pipeline). The
   arena's lifetime is enough, because nothing keeps the pointer after the call: `newBufferWithBytes:`
   (GlassMTLOffscreen.m:158), `glTexImage2D` and `glTexSubImage2D` (GlassCGLOffscreen.m:177, :186) copy the pixels.
   No measurement was taken. The copy stands under PB5 (a manager decision after slices 6 and 7), and measuring
   it on `-Dprism.order=sw` is an owner follow-up (slice 6). A direct buffer passes
   `MemorySegment.ofBuffer(buf.duplicate().clear())`, its base address whatever its position, as
   `GetDirectBufferAddress` gave it; a buffer that is not direct crosses as NULL, as `GetDirectBufferAddress`
   answered.
7. **Exact narrowing on hot paths.** Doubles become floats in VU7 and WU2; NSUInteger becomes int32 in VU28
   (NSNotFound → −1). The C must do each conversion once, explicitly.
8. **Nested run loops and re-entrancy inside downcalls.** `_setView`, `_enterFullscreen` and `_exitFullscreen` call
   `enterFullScreenExitingLoop`, which runs `Application.enterNestedEventLoop` (another slice). `_maximize` uses
   animated `setFrame:`. `performSelectorOnMainThread…waitUntilDone:YES` runs on the main thread. No window or view
   downcall may be `critical`.
9. **Class-init timing of MacGestureSupport.** Today it initializes lazily through
   `Class.forName(…, true, glassClassLoader)` on the first wheel event. Create the stubs with `findStatic`, which does
   not initialize the class, so the timing stays the same.
10. **`Map` value types.** JNI `CallLongMethod` on a non-Long was undefined behaviour; Java will throw
    `ClassCastException` instead. The `kHiDPIAware` and `mtlCommandQueue` keys are each read twice.
11. **ObjC exceptions** such as `[[NSScreen screens] objectAtIndex:0]` with no screens. They crash through FFM frames
    just as they crash through JNI frames today.
12. **Cross-slice dependencies (MacApplication):** the `jEnv` flag, the thread-data key, `createJavaScreen`, the
    nested-loop upcalls, `glassClassLoader`, and attaching the main thread for the first FFM upcall once `runLoop:` no
    longer attaches it through JNI. Plus the `GlassMenubar*` (MacMenuBarDelegate) and `GlassAccessible*`
    (MacAccessible) handles.

#### Latent bugs to carry verbatim (do not fix)

- `_setIcon` swaps width and height in its `getImage` call (GlassWindow.m:1471-1473).
- A nil window in `_createWindowCommonDo` dereferences NULL (:529 at 1a6788cc4d). `createWindow` still carries it.
  The global ref it leaked (:622) went with the JNI path in slice 5: `gmac_window_create` keys the window by
  `window_id` and makes no global ref.
- `_setMenubar` dereferences `menubar->menu` without a NULL check (:1000).
- `_uploadPixelsDirect` has no capacity check (GlassView.m:569-586).
- `GetJavaKeyChars` overflows its `jchar jc[16]` stack buffer (GlassKey.m:458-459).
- `convertNSStringToJString`, attributed branch: copies `length` units from data that may be shorter
  (ViewDelegate.m:1158-1162).
- Local-ref leaks on the attached main thread (reading-based): `ClassForName` results on every wheel or gesture event,
  the IME `jStr`, and `theArray` when n ≠ 2. The `createJavaScreen` result leaked too until slice 5. That leak is
  gone as a consequence of the transport, not as a fix: WU1 passes a `GmacScreenInfo` on the C stack and Java
  builds the `Screen`, so no JNI local is made.
- Object leaks: `[NSToolbar new]` (GlassWindow.m:534); sublayers never released (GlassLayer.m:55/66);
  `CGColorSpaceCreateWithName` (GlassLayerMTL.m:62, GlassLayerCGL.m:59); `MTLCreateSystemDefaultDevice`
  (GlassLayerMTL.m:46, and on every texture recreation in GlassMTLFrameBufferObject.m:51); the NSLock is never
  released.
- `GlassMTLOffscreen dealloc` calls `[super dealloc]` only when `_fbo != nil` (:57-64).
- Re-`init` of the live `nsAttrBuffer` (GlassView3D.m:413, 547, 620, 643).
- `GlassCGLOffscreen dealloc` reads the unretained `glassView`.
- `_toFront` lacks the main-thread assert.
- `isVsyncEnabled` defaults to YES when absent.
- The pointless paired global refs in `_create`.

#### Test hooks wanted

- `gmac_test_fire_*_callback`: a distinct value per argument (`1001, 1002…`, `1.5f…`, fake pointer), with the pattern
  documented per slot. This is the only automated check of the 30 descriptors that needs no window.
- `gmac_sizeof_*`.
- `gmac_constant(i)`.
- `gmac_test_nslocale_language_direction`, built in slice 5 in place of the sketch's `gmac_test_rtl_objc` (§7
  "As built"), and `gmac_test_fire_window_callback_with_pending` (built in slice 5, deleted in slice 11 part 3, §6
  (e)).
- `MacGlassNative.registrySize()`.
- `gmac_main_thread_detached` exercised from a non-main test thread before the loop exists.

#### Recommendation

**Migrate with an FFM facade. Do not delete the library.** Order the work by class: MacApplication's prerequisites
(its slice) → MacWindow → MacView together with MacGestureSupport.

1. Add `glass_mac_api.{h,m}` alongside the JNI: constants, size probes, the 3 tables, installers and fire hooks. Make
   every one of the 41 upcall sites keep both paths: call through the table if one is installed, otherwise use JNI
   (the Windows slices 7–9 precedent). The window sites' JNI branches went in slice 5's cleanup (PB16, §7 "As
   built").
2. Add `MacGlassNative` plus the headless tests. Keep each production Java file at or under 900 lines by splitting
   into aggregate classes such as `MacWindowCalls`, `MacViewCalls` and `MacCallbackTargets`.
3. Flip MacWindow: the window table and 29 exports.
4. Flip MacView and MacGestureSupport: the view and gesture tables, `GmacViewCaps`, uploads, and the 3 exact PURE
   deletions.
5. Delete the JNI bodies, the `_initIDs`, the dead IDs, GlassGestureSupport.m and the generated-header includes.
   Restate the constants and add the CMake `JNI` opt-in.
6. Run the W27 A/B, then bind CF from Java.

After each step, run `mvn -pl modules/javafx.graphics test` (headless; narrow with `-Dtest='*MacGlassNative*'`). The
owner runs the robot and manual lists above, because agents cannot open windows here.

## Group g3: pasteboard, clipboard, menus, dialogs, MacFileNSURL

**Scribe's corrections** (details in §R2):

- "No built binary exists": the baseline build of `1a6788cc4d` has one (the header). The §4 owner step is done. The
  baseline imports `UTTypeCreatePreferredIdentifierForTag`, `UTTypeCopyPreferredTagWithClass`, `kUTTagClassMIMEType`,
  `kUTTypeURL` and `kUTTypeFileURL` from CoreServices, and `NSLog` from Foundation. It imports no `CFURL*` symbol,
  because the code uses `NSURL` methods; the CF names in §4 are candidate binds.
- §2 "The skill's grep misses `native private …`" is wrong. The skill's regex allows zero leading modifiers, so it
  does match `native private …`. What it misses are the 44 declarations with `@Override` on the same line, and none
  of those is in this scope.
- §7: `JFX_EXPORT`, the `uint32_t` `gmac_abi_version`, and the hand-copied `GMAC_*` constants with their
  `gmac_test_constant` probe all differ from g0/g2. Under the `constants/` plan the constant copies and their probe
  are not needed (§R3).
- §11 "Delete the 5 exact WRAPPERs in their own commit": the owner commits the story as one commit, so read this as
  an order of work, not a commit plan.

Read-only audit. No files were changed, and no Maven, CMake or test runs were made.

**Paths.** `N/` = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/`
and `J/` = `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/glass/ui/mac/`.

**No built binary exists.** `modules/javafx.graphics/target` is absent, so no `nm -u` or `otool -L` was possible.
External symbols below come from the sources, checked against the Xcode 27.1 SDK `.tbd` export lists.

### 1. Verdict

**Split. Most of this C stays native, and some of it gets deleted.**

- **28 of the 40 exports are `OS-CALL`.** They are AppKit Objective-C (`NSPasteboard`, `NSPasteboardItem`, `NSImage`,
  `NSMenu`/`NSMenuItem`, `NSOpenPanel`/`NSSavePanel`, `NSURL`), and the story forbids `objc_msgSend` from Java. These
  get a flat `gmac_*` ABI.
- **5 are `PURE` JNI bookkeeping and go away with the migration itself.** These are the four `_initIDs` and
  `MacMenuDelegate._setCallback`, whose body is only global-ref replacement.
- **7 are `WRAPPER`s, where Java can bind a plain C OS symbol directly:**
  - `FormatEncoder._convertMIMEtoUTI` / `_convertUTItoMIME` sit over CoreServices
    `UTTypeCreatePreferredIdentifierForTag` / `UTTypeCopyPreferredTagWithClass`. **PARITY: exact.**
  - `MacFileNSURL._dispose` / `_start…` / `_stop…` map to the CoreFoundation twins `CFRelease` /
    `CFURLStart|StopAccessingSecurityScopedResource`. **PARITY: exact**, ruled by reading; the A/B test proves it.
  - `MacFileNSURL._getBookmark` / `_createFromBookmark` map to `CFURLCreateBookmarkData` /
    `CFURLCreateByResolvingBookmarkData`. **PARITY: unknown**, so they are not deletion candidates until the experiment
    in §10 runs.
- **0 are `PURE-HOT`.**
- **Upcalls shrink a lot.** 17 distinct Java targets (21 sites) become 7 callback slots in two tables (5 menu, 2
  dialog). Every other upcall becomes Java code running before or after the downcall.
- **No function in scope ends `PARITY: unprovable`.** Three CF substitutions are `PARITY: unknown` (§10).

### 2. Summary counts

| Item | Count | Where |
|---|---:|---|
| Java `native` declarations | **40** | MacPasteboard 13, MacSystemClipboard 3 (2 in nested `FormatEncoder`), MacMenuDelegate 11, MacMenuBarDelegate 3, MacCommonDialogs 4, MacFileNSURL 6. MacDnDClipboard 0, MacClipboardDelegate 0. `@Override native protected`: 0 in scope. |
| `_initIDs` | 4 | MacPasteboard, MacMenuDelegate, MacCommonDialogs, MacFileNSURL; each is called from a `static {}` block. No class in scope loads a library. |
| `JNIEXPORT` | **40** | GlassPasteboard.m 13, GlassSystemClipboard.m 3, GlassMenu.m 14, GlassDialogs.m 10. GlassDragSource.m and GlassDraggingSource.m have 0. Orphans: 0 in either direction (names matched 1:1). |
| `JNI_OnLoad` in scope | 0 | The library's is `N/GlassApplication.m:86-95`. It runs `pthread_key_create(&GlassThreadDataKey)`, which every `GLASS_POOL_ENTER` here needs. |
| Upcall sites, direct | **21** | 13 `Call*Method` + 8 `NewObject`, 17 distinct Java targets (§6). |
| Upcall sites, indirect | 11 + 73 | 11 `[GlassHelper ClassForName:]` (each a `Class.forName` upcall at `N/GlassHelper.m:86`). 73 `GLASS_CHECK_EXCEPTION`, each a conditional `Application.reportException` (`N/GlassMacros.h:157-167`). |
| Java field reads | 6 | `GetObjectField` ×2 (`MacMenuDelegate.menu`), `GetIntField` ×2, `GetFloatField` ×2 (Pixels width/height/scalex/scaley). |
| `Get*ID` sites | 18 | GlassPasteboard 1 (not cached, :359), GlassMenu 9 (:411-432, static), GlassDialogs 8 (:244, :382, :389-393, :404-413, `javaIDs`). Plus 3 through `initJavaIDs{List,ArrayList,File}` (`N/GlassStatics.m:105-139`). Shared caches used: `jPixelsAttachData` (set at `N/GlassPixels.m:51` by `MacPixels._initIDs`), `jApplicationClass` and `javaIDs.Application.reportException` (`N/GlassApplication.m:963,973`). |
| Global refs | 6 `NewGlobalRef` / 5 `DeleteGlobalRef` | GlassMenu.m:91, 116, 119, 277, 669 / 153, 158, 300, 665. GlassDialogs.m:93 / 112. 0 weak refs. |
| Critical arrays | 2 | `GetPrimitiveArrayCritical` at GlassDialogs.m:309 (written via memcpy, released with mode 0) and :340 (read-only, mode 0). |
| Array elements | 2 | `GetByteArrayElements` at GlassPasteboard.m:282 and :298, both released with mode 0 (copy-back, nothing written). |
| Direct buffers | 0 | `GetDirectBufferAddress` / `NewDirectByteBuffer`: none. |
| Arrays created | 6 + 11 | `NewByteArray` 4 (GlassPasteboard.m:154, 193, 362; GlassDialogs.m:307), `NewObjectArray` 2 (:518, :529); `SetByteArrayRegion` 11. |
| Strings in | 5 + 17 | `GetStringChars`/`ReleaseStringChars` 5 pairs (GlassPasteboard.m:254, 328, 453, 680, 729) plus 17 `nsStringWithJavaString` (`GetStringChars` at `N/GlassHelper.m:336-350`; null gives `@""`). `GetStringUTFChars`: 0. |
| Strings out | 6 | `NewStringUTF` 5 (GlassPasteboard.m:365, 487; GlassSystemClipboard.m:48, 71, 99), `NewString` 1 (GlassDialogs.m:194, UTF-16). |
| Exceptions | 0 / 28 / 15 / 73 | `ThrowNew`: 0 literal, 28 via `GLASS_ASSERT_MAIN_JAVA_THREAD` expansions (`N/GlassMacros.h:184-191`; Pasteboard 12, Menu 13, Dialogs 3). `ExceptionCheck` 15 (GlassMenu 8, GlassDialogs 7). `GLASS_CHECK_EXCEPTION` 73 (37 / 3 / 13 / 20). `FindClass` 6 literal. |
| Thread attach | 0 | `AttachCurrentThread`, `DetachCurrentThread`, `GetJavaVM`: none in scope. `GET_MAIN_JENV` 9 + `_NOWARN` 1 use the main thread's cached `jEnv`, attached at `N/GlassApplication.m:562` and cleared at :758. |
| Main-thread marshalling | 3 | `performSelectorOnMainThread … waitUntilDone:YES` at GlassDialogs.m:455, 533, 600. These run inline because the caller is already the main thread. `dispatch_*`: 0. |
| Generated-header `#import`s | 8 | GlassPasteboard.m:27 (`com_sun_glass_ui_mac_MacPasteboard.h`), :28 (`com_sun_glass_ui_Clipboard.h`). GlassSystemClipboard.m:28 (`..._MacSystemClipboard_FormatEncoder.h`). GlassMenu.m:27 (`com_sun_glass_events_KeyEvent.h`), :28, :29 (MenuBarDelegate/MenuDelegate). GlassDialogs.m:27 (`MacCommonDialogs.h`). GlassDragSource.m:27 (`Clipboard.h`). |
| Generated constants used | 19 distinct | `MacPasteboard_{General,DragAndDrop,UtfIndex,ObjectIndex}`, `Clipboard_ACTION_{NONE,COPY,MOVE,COPY_OR_MOVE,REFERENCE,ANY}`, `KeyEvent_MODIFIER_{SHIFT,FUNCTION,CONTROL,OPTION,COMMAND}`, `KeyEvent_VK_{F1,F12,F13,F24}`. |
| Direct `jni.h` | 3 | GlassMenu.h:27, GlassDialogs.h:27, GlassDragSource.h:27, plus `common.h`, `GlassMacros.h`, `GlassStatics.h` in every .m. |
| C lines in scope | 2,907 | 11 files (`wc -l`). |

**How counted** (from `N/`, run with `bash -c` because zsh does not word-split):
- `grep -c JNIEXPORT <6 .m>`
- `grep -nE "(->|\.)(Call(Static|Nonvirtual)?[A-Za-z]*Method[AV]?|NewObject[AV]?)\("`
- `grep -nE "Get(Static)?(Method|Field)ID"`
- `grep -nE "New(Weak)?GlobalRef|Delete(Weak)?GlobalRef"`
- one `grep -cE` per idiom for the rest
- Java side: `grep -cE "^\s*[a-z ]*\bnative\b[^;]*\("` per class. The skill's grep misses `native private …`.

### 3. Java classes and C files

**Natives.** All are `private` unless noted; S = static, I = instance. "C" is the implementing function.

| # | Java (`J/`) | Signature | S/I | C (`N/`) |
|---|---|---|---|---|
| 1 | MacPasteboard.java:31 | `void _initIDs()` | S | GlassPasteboard.m:379 |
| 2 | :68 | `long _createSystemPasteboard(int type)` | I | :406 |
| 3 | :75 | `long _createUserPasteboard(String name)` | I | :441 |
| 4 | :89 | `String _getName(long ptr)` | I | :475 |
| 5 | :98 | `String[][] _getUTFs(long ptr)` | I | :500 |
| 6 | :106 | `byte[] _getItemAsRawImage(long ptr, int index)` | I | :559 |
| 7 | :113 | `String _getItemStringForUTF(long ptr, int index, String utf)` | I | :666 |
| 8 | :120 | `byte[] _getItemBytesForUTF(long ptr, int index, String utf)` | I | :715 |
| 9 | :141 | `long _putItemsFromArray(long ptr, Object[] items, int supportedActions)` | I | :759 |
| 10 | :170 | `long _clear(long ptr)` | I | :829 |
| 11 | :177 | `long _getSeed(long ptr)` | I | :853 |
| 12 | :184 | `int _getAllowedOperation(long ptr)` | I | :897 |
| 13 | :190 | `void _release(long ptr)` | I | :877 |
| 14 | MacSystemClipboard.java:393 (`FormatEncoder`) | `String _convertMIMEtoUTI(String mime)` | S | GlassSystemClipboard.m:36 (`…_00024FormatEncoder__1convertMIMEtoUTI`) |
| 15 | :394 (`FormatEncoder`) | `String _convertUTItoMIME(String uti)` | S | :61 |
| 16 | :448 | `String _convertFileReferencePath(String path)` | S | :84 |
| 17 | MacMenuDelegate.java:35 | `void _initIDs()` | S | GlassMenu.m:400 |
| 18 | :52 | `long _createMenu(String title, boolean enabled)` | I (`this` becomes jDelegate) | :440 |
| 19 | :58 | `long _createMenuItem(String title, char shortcut, int modifiers, Pixels icon, boolean enabled, boolean checked, Callback callback)` | I | :468 |
| 20 | :70 | `void _insert(long menuPtr, long submenuPtr, int pos)` | I | :499 |
| 21 | :85 | `void _remove(long menuPtr, long submenuPtr, int pos)` | I | :532 |
| 22 | :104 | `void _setTitle(long menuPtr, String title)` | I | :569 |
| 23 | :111 | `void _setShortcut(long menuPtr, char shortcut, int modifiers)` | I | :595 |
| 24 | :118 | `void _setPixels(long menuPtr, Pixels pixels)` | I | :681 |
| 25 | :125 | `void _setEnabled(long menuPtr, boolean enabled)` | I | :615 |
| 26 | :132 | `void _setChecked(long menuPtr, boolean checked)` | I | :635 |
| 27 | :138 | `void _setCallback(long menuPtr, Callback callback)` | I | :655 |
| 28 | MacMenuBarDelegate.java:35 | `long _createMenuBar()` | I | GlassMenu.m:317 |
| 29 | :41 | `void _insert(long menubarPtr, long menuPtr, int pos)` | I | :341 |
| 30 | :48 | `void _remove(long menubarPtr, long menuPtr, int pos)` | I | :374 |
| 31 | MacCommonDialogs.java:39 | `void _initIDs()` | S | GlassDialogs.m:379 |
| 32 | :44 | `FileChooserResult _showFileOpenChooser(long owner, String folder, String title, boolean multipleMode, ExtensionFilter[] f, int defaultFilterIndex)` | S | :421 |
| 33 | :46 | `FileChooserResult _showFileSaveChooser(long owner, String folder, String filename, String title, ExtensionFilter[] f, int defaultFilterIndex)` | S | :497 |
| 34 | :49 | `File _showFolderChooser(long owner, String folder, String title)` | S | :570 |
| 35 | MacFileNSURL.java:41 | `void _initIDs()` | S | GlassDialogs.m:241 |
| 36 | :66 | `void _dispose(long ptr)` | I | :252 |
| 37 | :80 | `boolean _startAccessingSecurityScopedResource(long ptr)` | I | :264 |
| 38 | :95 | `void _stopAccessingSecurityScopedResource(long ptr)` | I | :276 |
| 39 | :108 | `byte[] _getBookmark(long ptr, long baseDocumentPtr)` | I | :288 |
| 40 | :122 | `MacFileNSURL _createFromBookmark(byte[] data, long baseDocumentPtr)` | S | :328 |

**`long` handle fields.**

| Field | Holds | Retain state |
|---|---|---|
| `MacPasteboard.ptr` (:62) | `NSPasteboard*` | system: +0; user: +1 |
| `MacMenuDelegate.ptr` (:41) | `GlassMenu*` | +1 |
| `MacMenuBarDelegate.ptr` (:33) | `GlassMenubar*` | +1, also consumed by `MacWindow._setMenubar` (`N/GlassWindow.m:994`) |
| `MacFileNSURL.ptr` (:49) | `NSURL*` | +1 |

**Entry threads.** Everything runs on the FX thread, which on macOS is the AppKit main thread:
- The C asserts this through `GLASS_ASSERT_MAIN_JAVA_THREAD` in 28 exports.
- `MacFileNSURL` calls `Application.checkEventThread()` on the Java side.
- `CommonDialogs.showFileChooser` calls `checkEventThread`.
- `FormatEncoder` has no assert. Its callers are `synchronized static`, so Java may call it from any thread, and
  UTType is thread-safe.

**C files.**

| File | Lines | JNI role | Non-JNI internals |
|---|---:|---|---|
| GlassPasteboard.m | 923 | 13 exports | `validate` :55, `copyToDragPasteboardIfNeeded` :87 (called by `N/GlassViewDelegate.m:930`), `DumpPasteboard` :104 (dead; only commented call at :511), `ByteArrayFromPixels` :145, `ByteArrayFromNSData` :187, `SetNSPasteboardItemValueForUtf` :206, `NSPasteboardItemFromArray` :310, `createUTF` :355 |
| GlassPasteboard.h | 34 | none | MIME macros, `copyToDragPasteboardIfNeeded` prototype |
| GlassSystemClipboard.m | 105 | 3 exports | — |
| GlassMenu.m | 694 | 14 exports, 4 upcall methods | `GlassMenubar` init/dealloc, `GlassMenu` init×2, dealloc, `action:`, `menuWillOpen:`, `menuDidClose:`, `validateMenuItem:`, `_setShortcut`, `_setChecked`, `_setPixels` |
| GlassMenu.h | 64 | `jobject`/`jstring`/`jboolean` ivars and params; `jni.h` | `jMenu` ivar :41 is unused |
| GlassDialogs.m | 613 | 10 exports | `DialogDispatcher` (:43-186: `runModally`, `exitModalWithEnv:`, `applyExtensions:`, `extensionFilterChanged:`), `convertNSURLtoFile` :188 |
| GlassDialogs.h | 27 | `jni.h` only | — |
| GlassDragSource.m / .h | 265 / 54 | no JNI calls; `jint` types plus `Clipboard.h` constants | 12 class methods with static `gDelegate`/`gMask`/`supportedActions`; callers are GlassPasteboard and GlassViewDelegate |
| GlassDraggingSource.m / .h | 83 / 45 | no JNI (includes only) | 6 `NSDraggingSource` methods |

### 4. External dependencies

**Link line.** `add_jfx_library(glass …)` is at `modules/javafx.graphics/native/mac.cmake:159-167`.
- `COMPILE_OPTIONS`: `${JFX_CC_OPTIONS}` (`-std=c99` for C/ObjC; `-O3 -DNDEBUG` in release, `-DDEBUG` in Debug;
  :103-106), `-DGL_SILENCE_DEPRECATION`, `-DMACOS_MIN_VERSION_MAJOR/MINOR`, `-Werror`.
- `LINK_LIBS`: `${JFX_FRAMEWORK_LINK_LIBS}` (:110-119) = AppKit, ApplicationServices, Carbon, OpenGL, QuartzCore,
  Security, Network, Metal, objc.
- Include dirs: JDK `include` + `include/darwin` + `HEADERS_DIR` are added for every target (:149-152).

**Imported symbols**, from source. Each C symbol was confirmed in the SDK `.tbd` files listed.
- **Objective-C runtime / AppKit / Foundation, in every OS-CALL:**
  - `objc_msgSend`, class refs, `NSApp`
  - data symbols `NSGeneralPboard`, `NSDragPboard`,
    `NSPasteboardType{String,PDF,TIFF,PNG,RTF,RTFD,HTML,TabularText,MultipleTextSelection,FindPanelSearchOptions}`
  - `NSLog`, `NSStringFromPoint`, `NSDefaultMallocZone`
- **CoreServices / LaunchServices** (`CoreServices.tbd`, re-exported through ApplicationServices/Carbon):
  `UTTypeCreatePreferredIdentifierForTag`, `UTTypeCopyPreferredTagWithClass`, `kUTTagClassMIMEType`, `kUTTypeURL`,
  `kUTTypeFileURL`.
- **CoreGraphics** (GlassPasteboard.m:627-648): `CGImageGetWidth`, `CGImageGetHeight`, `CGColorSpaceCreateWithName`,
  `kCGColorSpaceSRGB`, `CGBitmapContextCreate`, `CGContextSetBlendMode`, `CGContextDrawImage`, `CGContextFlush`,
  `CGColorSpaceRelease`.
- **libSystem:** `pthread_main_np`, `pthread_getspecific`, `pthread_setspecific`, `calloc`, `malloc`, `free`,
  `memcpy`, `assert` (compiled out under `NDEBUG`).
- **Candidate Java binds, confirmed exported:**
  - `CoreFoundation.tbd`: `CFRelease`, `CFURLStartAccessingSecurityScopedResource`,
    `CFURLStopAccessingSecurityScopedResource`, `CFURLCreateBookmarkData`, `CFURLCreateByResolvingBookmarkData`,
    `CFURLCreateWithString`, `CFURLCreateFilePathURL`, `CFURLCopyFileSystemPath`, `CFDataCreate`,
    `CFStringGetCString`, `CFStringGetMaximumSizeForEncoding`, `CFGetRetainCount`
  - `Foundation.tbd`: `NSLog`
  - `libsystem_pthread.tbd`: `pthread_main_np`
- **Bookmark constants are equal on both sides:** `kCFURLBookmarkCreationWithSecurityScope` =
  `NSURLBookmarkCreationWithSecurityScope` = 1<<11, `…ResolutionWithoutUI(Mask)` = 1<<8,
  `…ResolutionWithSecurityScope` = 1<<10 (CFURL.h:1224-1236, NSURL.h:758-781).
- **Owner/build step once a binary exists:**
  `nm -u modules/javafx.graphics/target/native/bin/libglass.dylib | grep -E 'UTType|kUTTag|CFURL|NSLog'` and
  `otool -L` on the same file.

### 5. Triage

**Exports.**

| C function | Verdict | Evidence | Parity gate | Replacement |
|---|---|---|---|---|
| Pasteboard `_initIDs` :379 | PURE (body is commented NSLogs only) | none | exact (no output) | deleted with its static block |
| `_createSystemPasteboard` :406 | OS-CALL | `+[NSPasteboard pasteboardWithName:]`, `NSGeneralPboard`, `NSDragPboard` | — | `gmac_pasteboard_create_system` |
| `_createUserPasteboard` :441 | OS-CALL | `pasteboardWithName:`, `retain` | — | `gmac_pasteboard_create_user` |
| `_getName` :475 | OS-CALL | `-name`, `-UTF8String` | — | `gmac_pasteboard_get_name`; Java decodes with `JniStringCodec.fromNewStringUtf` |
| `_getUTFs` :500 | OS-CALL | `-pasteboardItems`, `-types`, `-lengthOfBytesUsingEncoding:`, `-UTF8String` | — | `gmac_pasteboard_get_utis` |
| `_getItemAsRawImage` :559 | OS-CALL | `-dataForType:`, `NSImage initWithData:`/`initByReferencingURL:`, `+[NSURL URLWithString:]`, `CGImageForProposedRect:…`, the CG calls of §4 | — | `gmac_pasteboard_get_item_as_raw_image` |
| `_getItemStringForUTF` :666 | OS-CALL | `-stringForType:` | — | `gmac_pasteboard_get_item_string` |
| `_getItemBytesForUTF` :715 | OS-CALL | `-dataForType:` | — | `gmac_pasteboard_get_item_bytes` |
| `_putItemsFromArray` :759 | OS-CALL | `-clearContents`, `-setString:forType:`, `-setData:forType:`, `-TIFFRepresentation`, `NSStringFromPoint`, `NSDraggingItem`, `+[GlassDragSource flushWithMask:…]` → `-[NSView beginDraggingSessionWithItems:…]` (`N/GlassViewDelegate.m:1082`), `-writeObjects:` | — | `gmac_pasteboard_put_items` |
| `_clear` :829 | OS-CALL | `-clearContents` | — | `gmac_pasteboard_clear` |
| `_getSeed` :853 | OS-CALL | `-changeCount` | — | `gmac_pasteboard_get_seed` |
| `_release` :877 | OS-CALL | `-releaseGlobally` | — | `gmac_pasteboard_release` |
| `_getAllowedOperation` :897 | OS-CALL | `pasteboardWithName:NSDragPboard`, `GlassDragSource` static state shared with GlassViewDelegate | — | `gmac_pasteboard_get_allowed_operation` |
| `FormatEncoder._convertMIMEtoUTI` GlassSystemClipboard.m:36 | **WRAPPER** | `UTTypeCreatePreferredIdentifierForTag(kUTTagClassMIMEType, mime, "public.mime-type")`; the NSString steps are toll-free CF equivalents | **exact**: output is always ASCII (declared UTIs or `dyn.`) | Java: `CFStringCreateWithCharacters` (null → `""`), the UTType call, `CFStringGetCString(UTF-8)` → `JniStringCodec`, `CFRelease` |
| `FormatEncoder._convertUTItoMIME` :61 | **WRAPPER** | `UTTypeCopyPreferredTagWithClass(uti, kUTTagClassMIMEType)` | **exact** for well-formed payloads; the golden must include a `dyn.` UTI whose payload is ill-formed UTF-16 (outcome not predictable by reading) | as above; NULL → `null` (as `NewStringUTF(NULL)`) |
| `_convertFileReferencePath` :84 | OS-CALL | `+[NSURL URLWithString:]`, `-filePathURL`, `-path` | CF substitution is `unknown` (§10) | `gmac_clipboard_file_reference_path` (UTF-8 out, `JniStringCodec`) |
| MenuBar `_createMenuBar` GlassMenu.m:317 | OS-CALL | `GlassMenubar` → `[[NSMenu allocWithZone:NSDefaultMallocZone()] initWithTitle:]` | — | `gmac_menubar_create` |
| MenuBar `_insert` :341 | OS-CALL | `insertItem:atIndex:`, `setSubmenu:forItem:`, `setAutoenablesItems:`, SPI `setAppleMenu:`, `[[NSApp mainMenu] update]` | — | `gmac_menubar_insert` |
| MenuBar `_remove` :374 | OS-CALL | `indexOfItem:`, `removeItem:`, `[glassmenu release]`, `update` | — | `gmac_menubar_remove` |
| MenuDelegate `_initIDs` :400 | PURE (ID caching) | none | exact | deleted |
| `_createMenu` :440 | OS-CALL | `NSMenuItem`/`NSMenu` init, `setDelegate:` | — | `gmac_menu_create` |
| `_createMenuItem` :468 | OS-CALL | `NSMenuItem initWithTitle:action:keyEquivalent:`, `setState:`, `setImage:`, `GetStringForJavaKey` (`N/GlassKey.m:516`) | — | `gmac_menu_item_create` |
| `_insert` :499 | OS-CALL | `insertItem:atIndex:`, `separatorItem`, `addItem:` | — | `gmac_menu_insert` |
| `_remove` :532 | OS-CALL | `removeItem:`, `removeItemAtIndex:`, `update` | — | `gmac_menu_remove` |
| `_setTitle` :569 | OS-CALL | `[NSApp mainMenu] indexOfItem:`, `setTitle:` | — | `gmac_menu_set_title` |
| `_setShortcut` :595 | OS-CALL | `setKeyEquivalent:`, `setKeyEquivalentModifierMask:` | — | `gmac_menu_set_shortcut` |
| `_setPixels` :681 | OS-CALL | `NSImage setSize:`, `NSMenuItem setImage:` | — | `gmac_menu_set_image` |
| `_setEnabled` :615 | OS-CALL | `setEnabled:` | — | `gmac_menu_set_enabled` |
| `_setChecked` :635 | OS-CALL | `setState:` | — | `gmac_menu_set_checked` |
| `_setCallback` :655 | PURE (Delete/NewGlobalRef only) | none | exact except L8 (crash on `ptr == 0`) | Java registry update; no export |
| CommonDialogs `_initIDs` :379 | PURE (ID caching) | none | exact | deleted |
| `_showFileOpenChooser` :421 | OS-CALL | `NSOpenPanel` setters, `beginSheetModalForWindow:`/`beginWithCompletionHandler:`, `URLs`, `NSPopUpButton`, `setAllowedFileTypes:` | — | `gmac_dialog_open` |
| `_showFileSaveChooser` :497 | OS-CALL | `NSSavePanel` (same set) | — | `gmac_dialog_save` |
| `_showFolderChooser` :570 | OS-CALL | `NSOpenPanel` | — | `gmac_dialog_folder` |
| FileNSURL `_initIDs` :241 | PURE | none | exact | deleted |
| `_dispose` :252 | **WRAPPER** (substitution) | `[url release]` ≡ `CFRelease` on a toll-free-bridged NSURL | **exact** by reading; test asserts `CFGetRetainCount` deltas | Java `CFRelease` |
| `_startAccessing…` :264 | **WRAPPER** (substitution) | `CFURLStartAccessingSecurityScopedResource` | **exact**: boolean A/B on the host; sandbox case is manual | Java bind (`JAVA_BOOLEAN` return) |
| `_stopAccessing…` :276 | **WRAPPER** (substitution) | `CFURLStopAccessingSecurityScopedResource` | **exact** (void; sandbox case manual) | Java bind |
| `_getBookmark` :288 | WRAPPER (substitution) | `CFURLCreateBookmarkData`, `NSLog` | **unknown** (§10) | stays as `gmac_url_get_bookmark` until settled |
| `_createFromBookmark` :328 | WRAPPER (substitution) | `CFDataCreate`, `CFURLCreateByResolvingBookmarkData`, `NSLog` | **unknown** (§10) | stays as `gmac_url_create_from_bookmark` until settled |

Totals: OS-CALL 28, WRAPPER 7, PURE 5, PURE-HOT 0.

**Internal (non-exported) C.**

| Function | Verdict | Disposition |
|---|---|---|
| `validate` (Pasteboard :55) | PURE | Keep: its only caller stays C. Parity would be exact, but moving it adds a crossing. |
| `GlassDragSource` mask mappers, setters, getters | PURE (`flushWithMask` is OS-CALL) | Keep: callers are C only (GlassViewDelegate, GlassPasteboard). Swap `jint` for `int32_t` and replace the `Clipboard.h` constants with `GMAC_ACTION_*`. |
| `ByteArrayFromPixels` | PURE | Becomes the C block builder; keeps both size bounds (L6). |
| `ByteArrayFromNSData`, `createUTF` | marshalling | Become malloc'd blocks. |
| `convertNSURLtoFile` :188 | marshalling | Moves to Java; the path comes from `gmac_url_copy_path`. |
| `copyToDragPasteboardIfNeeded`, `SetNSPasteboardItemValueForUtf`, `NSPasteboardItemFromArray`, `DialogDispatcher`, `GlassMenu`/`GlassMenubar`, `GlassDraggingSource` | OS-CALL | Stay, with JNI types removed. |
| `DumpPasteboard` | dead | Carry. |

### 6. Upcalls

Every upcall below runs on the AppKit main thread. Synchronous ones run inside the JNI downcall.

| # | Java target | C site (`N/`) | Thread / trigger | Today's exception handling | FFM replacement |
|---|---|---|---|---|---|
| 1 | `Pixels.attachData(long)` (private) | GlassPasteboard.m:270; GlassMenu.m:279 | sync, inside `_putItemsFromArray` / `_createMenuItem` / `_setPixels` | :307 checks; menu path reported by the export's trailing check (:485, :693) | Java calls it *before* the downcall through `privateLookupIn` (precedent `GtkPixelsCalls.PIXELS_ATTACH_DATA`) with an 8-byte slot; the +1 `NSImage*` goes in. Works while MacPixels is still JNI. |
| 2 | `new String(byte[],"UTF-8")` | GlassPasteboard.m:367 (via :538, :698) | sync | :368 | Java `new String(b, UTF_8)` on the block |
| 3 | `MenuItem.Callback.action()` | GlassMenu.m:175 | async: AppKit menu or key-equivalent dispatch in the NSApp loop | **none**, left pending (L20) | slot `action` |
| 4 | `Menu.notifyMenuOpening()` (protected) via `GetObjectField(menu)` | :198-199 | `menuWillOpen:`, after `[GlassWindow _hasGrab]` / `_resetGrab` (window table) | none | slot `menu_opening` |
| 5 | `Menu.notifyMenuClosed()` (protected) | :208-209 | `menuDidClose:` | none | slot `menu_closed` |
| 6 | `MenuItem.Callback.validate()` | :222 | async in tracking **and synchronously inside downcalls** via `[[NSApp mainMenu] update]` (:363, :389, :558) | none; NPE on a NULL `jCallback` (L7) | slot `validate` |
| 7 | `new EventLoop()` | GlassDialogs.m:88 (+ `ClassForName` :84) | sync | :91 returns with the exception still pending | Java builds it before the downcall; `loop_id` = 0 if it threw |
| 8 | `EventLoop.enter()` | :109 | sync; **blocks in a nested loop** | :110 | slot `event_loop_enter` |
| 9 | `EventLoop.leave(null)` | :75 (blocks :98, :104) | panel completion handler inside the nested loop | :76 | slot `event_loop_leave` |
| 10 | `ExtensionFilter.getDescription()` | :133 | sync, before the panel | :148 (after the loop) | Java pre-marshal, same order |
| 11 | `ExtensionFilter.extensionsToArray()` (private) | :136 | sync | :148 | Java via `privateLookupIn` (precedent `GtkDialogCalls.EXTENSIONS_TO_ARRAY`) |
| 12 | `MacCommonDialogs.isFileNSURLEnabled()` | :203 | sync, per URL, after the panel closes | :205 | Java, per URL, after the downcall |
| 13 | `new MacFileNSURL(String,long)` (private) | :212 (+ `ClassForName` :210) | sync | :215 | Java (needs access to the private constructor) |
| 14 | `new File(String)` | :222 | sync | :225 | Java |
| 15 | `new ArrayList()` | :460, :538 | sync | :461, :539 | Java |
| 16 | `List.add` | :468, :542 | sync | :469, :543 | Java |
| 17 | `new FileChooserResult(List, ExtensionFilter)` | :484, :557 | sync | :487, :560 | Java |
| — | `Class.forName` | 11 `ClassForName` sites | sync | GlassHelper.m:88 | direct Java references |
| — | `Application.reportException` | 73 `GLASS_CHECK_EXCEPTION` | sync | — | facade reports at every place a JNI step became Java (Risk R1) |

**How the C reaches Java today.** It uses the global main-thread `jEnv` through `GET_MAIN_JENV`, with no thread hop.
Callbacks are skipped once `jEnv == NULL` (after `[NSApp run]` returns, `N/GlassApplication.m:758`). The dialogs'
`performSelectorOnMainThread:waitUntilDone:YES` runs inline. `GlassDragSource flushWithMask:` drops the call with an
NSLog when it is not on the main thread (GlassDragSource.m:61-78).

**Recorded divergences (PB7), slice 8 as built.** Line numbers are at 1a6788cc4d unless marked current. Two more,
(o) and (p), were recorded after slice 9's and follow its letters.
- (a) A detached main thread leaves the panel unbegun. The three dialog natives ran `runModally`
  (`GlassDialogs.m:79-113`) on the main thread with `jEnv` NULL: `GET_MAIN_JENV` (:81) printed its `GLASS_CALLSTACK`
  message, and `ClassForName` (:84) then crashed on the NULL `JNIEnv`. The export prints the same message (current
  `GlassDialogs.m:111`) and returns without beginning the panel (current :117-120), so the dialog answers as
  cancelled (PB14). Java's report of the assertion is latent bug L33.
- (b) A late `leave` after `enter()` threw is a no-op (latent bug L14 made defined). Under JNI, an `enter()` that
  threw at once returned (:109), :110 reported it and :112 deleted the global reference, so the panel's completion
  handler later called `leave` through the deleted reference (:75). Now `MacDialogCalls` unregisters the loop id
  once the downcall returns, and the `event_loop_leave` target in `MacDialogCallbacks` does nothing for an id the
  registry no longer holds. `MacDialogNativeTest.aLoopIdTheRegistryDoesNotHoldRunsNothing`.
- (c) The filter upcalls run before the panel is configured (Risk R6). The JNI called `getDescription()` and
  `extensionsToArray()` in `applyExtensions:withDefaultIndex:withEnv:` (:133, :136), after it had made and configured
  the panel (:431-447 open, :507-525 save) and inside its pool. `MacDialogCalls.fileFilters` calls them in the same
  order before the downcall, so they run before the panel exists, and so does the report at the filter check (:148;
  L32, L33). The folder chooser has no filters.
- (d) URLs are handed out +1 and converted after the export's pool drained. The JNI converted each chosen URL inside
  its `GLASS_POOL` (:468, :542, :604; `_createFromBookmark` :361), and `convertNSURLtoFile` retained only a URL that
  became a `MacFileNSURL` (:208). Now `gmac_dialog_open`, `gmac_dialog_save`, `gmac_dialog_folder` and
  `gmac_url_create_from_bookmark` retain each URL they answer (current `GlassDialogs.m:181-196`, :405, :447, :277),
  drain their pool and return. `MacDialogCalls.toFile` then converts it: a `MacFileNSURL` keeps the reference, and a
  `File` releases it through `objc_release`. Each URL ends with the retain count the JNI left it (L29 included); what
  moves is the drain, so whatever the export's pool held is released before the conversion's upcalls
  (`isFileNSURLEnabled()`, the constructors) run.
  `MacDialogNativeTest.aUrlBecomesAFileOfItsPathOnceItsReferenceIsReleased`,
  `aUrlBecomesAFileNSURLThatKeepsItsReferenceUntilDisposed`.
- (e) The dialog slots dropped a JNI exception that a still-JNI upcall left pending on the main thread (PB15). They
  are dialled directly (current `GlassDialogs.m:95`, :127), not through `GlassDialSlotKeepingPending` as window slots
  0, 1 and 3-8 were until slice 11 part 3 (g2 §6 (e)). Under JNI the exception survived the `enter()` and `leave`
  calls (PB11), and :110's or :76's `GLASS_CHECK_EXCEPTION` reported it, or the new one that replaced it when the
  upcall threw. Until part 3, an exception still pending once the panel was done was reported, as the JNI's checks
  after the panel did, by the export's `GlassReportPendingException()` before it returned. It was transitional: only
  a still-JNI site could leave one pending, so the drop and that report went with the last of them in slice 11 part
  3.
- (f) The `EventLoop` is made on the calling thread before the downcall. The JNI made it in `runModally` (:84-93),
  which `performSelectorOnMainThread:…waitUntilDone:YES` (:455, :533, :600) ran on the main thread, after the panel
  was configured and the filters read. `MacDialogCalls.run` calls `new EventLoop()` on the thread that called the
  dialog, after the filters and before the downcall, and registers its id for the slots; the export's `runModally`
  still runs on the main thread. On the event thread, which is the main thread, only the order changes: the loop
  exists before the panel. The thread differs only when `glass.disableThreadChecks` lets a dialog run off it. What
  `new EventLoop()` throws is reported after the panel, at the JNI's first check after it (:459, :537, :610), and
  loop id 0 leaves the panel unbegun, as the JNI's return at :91 did.
  `MacDialogNativeTest.offTheEventThreadTheFiltersAndTheEventLoopEachReportOnce`.

**Recorded divergences (PB7), slice 9 as built.** Line numbers are at 1a6788cc4d. The letters continue slice 8's.
- (g) The menu upcalls report what they throw at once (Risk R5, latent bug L20). The JNI did not check for an
  exception after `action()` (`GlassMenu.m:175`), `validate()` (:222), `notifyMenuOpening()` (:199) or
  `notifyMenuClosed()` (:209), and left it pending for the next `GLASS_CHECK_EXCEPTION` on the main thread. For a
  `validate()` that `[[NSApp mainMenu] update]` ran, that check was the closing one of `_insert` or `_remove` (:366,
  :392, :561). Each `MacMenuCallbacks` target now reports it through `Application.reportException` before it answers
  `GMAC_UPCALL_THREW`. This includes the `NullPointerException` of `validate()` on an item without a callback (L7)
  and of the menu slots on a delegate without a menu.
  - The count can change as well as the time. Under JNI, a later upcall that threw before that check replaced the
    pending exception (PB11), so only the last one was reported. Now each one is reported, in order.
  - `MacMenuNativeTest.whatASlotThrowsIsReportedAtOnceAndAnsweredThrew`,
    `actionWithoutACallbackMakesNoCallAndValidateReportsTheNullReceiver`,
    `theMenuSlotsOfAnItemsDelegateReportTheNullReceiver`.
- (h) On a detached main thread, choosing an item without a callback prints the `GLASS_CALLSTACK` message, where the
  JNI printed nothing. The JNI's `action:` tested `jCallback` first (:170) and reached `GET_MAIN_JENV` (:172) only
  for an item with a callback. Only Java knows the callback now, so `action:` prints the message for every item and
  skips slot `action`. No Java runs in either case. `jEnv` is NULL only once `[NSApp run]` has returned (PB14), so
  only then is this reachable. The comment on slot `action` in `glass_mac_api.h` records it.
- (i) `Pixels.attachData` runs before the item is made, on the calling thread and outside the export's autorelease
  pool (Risk R6).
  - The JNI ran it in `-[GlassMenu _setPixels:]` (:279), inside the native's `GLASS_POOL` (:475-484 for
    `_createMenuItem`, :687-692 for `_setPixels`). For `_createMenuItem` that was after the `NSMenuItem` had its
    title, key equivalent, enabled and checked state (:127-135).
  - `MacMenuCalls.createMenuItem` and `setPixels` now call it before the downcall and pass what it wrote in a
    `GmacMenuImage`, which the export consumes. What it throws is still reported after the export has run, where
    the JNI's closing check (:485, :693) reported it. The export takes whatever image was written, NULL when none
    was.
  - `MacMenuNativeTest.setPixelsAttachesTheIconBeforeItsDowncall`,
    `whatAttachDataThrowsIsReportedAfterTheDowncallWhichGetsNoImage`.
- (j) The natives that crashed on a detached main thread now have a defined outcome (latent bug L34).
  - At 1a6788cc4d, each printed `GET_MAIN_JENV`'s message and then crashed on the NULL `JNIEnv`:
    - `_createMenu` and `_createMenuItem` printed it in the initializer (:90, :115) and crashed in `NewGlobalRef`
      (:91, :116);
    - `_setPixels` with a `Pixels` printed it (:274) and crashed in `NewGlobalRef` (:277);
    - `_setCallback` printed it (:664) and crashed in `DeleteGlobalRef` (:665).
  - The exports print the same message where `GET_MAIN_JENV` printed it, in both initializers and in
    `-[GlassMenu _setImage:]`, and carry on: the menu or item is made and the image is set. Where the assertion
    fired, Java reports `RuntimeException("Main Java thread is detached.")` once after the downcall (PB18), or what
    `attachData` threw in its place (PB11). With an icon, `attachData` reaches `MacPixels`, which reports the
    assertion of its own first.
  - `_setCallback` runs only Java now: it prints no message, updates the registry and reports once.
  - `MacMenuNativeTest.aDetachedCreateMakesItsMenuThenReportsOnce`,
    `aDetachedItemWithAThrowingIconReportsOnlyWhatAttachDataThrew`,
    `aDetachedItemWithAnIconReportsInAttachDataAndAfterTheDowncall`,
    `aDetachedSetPixelsReportsWhatAttachDataThrewInsteadOfTheAssertion`,
    `aDetachedSetCallbackUpdatesTheRegistryThenReportsOnce`.
- (k) The menu slots dropped a JNI exception that a still-JNI upcall left pending on the main thread (PB15). They are
  dialled directly, not through `GlassDialSlotKeepingPending` as window slots 0, 1 and 3-8 were until slice 11 part
  3 (g2 §6 (e)). Under JNI the exception survived the menu upcall (PB11), and the next `GLASS_CHECK_EXCEPTION`
  reported it, or the new one that replaced it when the upcall threw. Until part 3, an exception still pending when
  an export was done was reported by the export's `GlassReportPendingException()` before it returned, as the closing
  check did. It was transitional: only a still-JNI site could leave one pending, so the drop and that report went
  with the last of them in slice 11 part 3.
- (l) The detached message prints new source locations. `GLASS_CALLSTACK` prints `__FUNCTION__`, `__FILE__` and
  `__LINE__` (`GlassMacros.h:236-240`), then a backtrace.
  - Every `GET_MAIN_JENV` in `GlassMenu.m` prints a new line number. Slice 11 part 3 moved them again when it
    replaced the macro with `GLASS_WARN_IF_MAIN_JAVA_DETACHED`.
  - Three print new names: `-[GlassMenu initWithJavajdelegate:jtitle:jenabled:]` and its item variant are now
    `initMenuWithId:title:enabled:` and `initItemWithId:title:shortcut:modifiers:icon:enabled:checked:`, and
    `_setPixels:` is now `_setImage:`.
  - The backtrace shows the `gmac_*` export where it showed the `Java_*` function. No test reads the message.
- (m) `_createMenu` and `_createMenuItem` no longer convert the title a second time. After their closing check
  (:454, :485) the JNI converted it again (:456, :487) for a `LOG` that is compiled out unless `VERBOSE` is defined
  (:39-43). Nothing used the result.
- (n) `MenuItem.setCallback` on a delegate whose `ptr` is 0 is defined (latent bug L35, which is L8 at
  `_setCallback`). The JNI dereferenced the NULL `GlassMenu` (:663-665) and crashed. `MacMenuCalls.setCallback`
  changes nothing for a `ptr` of 0, and never files an id that the registry no longer holds.
  `MacMenuNativeTest.setCallbackOnTheNullMenuChangesNothing`, `setCallbackDoesNotRefileADisposedId`.

**Recorded divergences (PB7), slice 8, recorded after slice 9's.** Line numbers are at 1a6788cc4d unless marked
current. Both are outcomes that Java defines where the JNI answered a mistyped value or crashed.
- (o) `MacFileNSURL.createFromBookmark` and `createFromDocumentScopedBookmark` answer null when
  `glass.macosx.enableFileNSURL` turns false during the call (latent bug L31, which is L16 as Java defines it). The
  factories check the property (`MacFileNSURL.java:137`, :174; current :127, :164), and the JNI's
  `_createFromBookmark` converted the resolved URL with `convertNSURLtoFile` (`GlassDialogs.m:361`), which read it
  again (:203). When it had turned false in between, the JNI answered a plain `File` from a native declared to answer
  a `MacFileNSURL`, so the caller held a `File` typed as a `MacFileNSURL`. `MacDialogCalls.fromBookmark` answers null
  there: `toFile` releases the URL, as for any `File`, and the `File` is dropped. Null is what the factories already
  answer for a bookmark that does not resolve. `MacDialogNativeTest.aResolvedBookmarkThatConvertsToAPlainFileIsNull`.
- (p) A null filter, or a filter whose `extensionsToArray()` throws, no longer crashes the VM (latent bug L32, which
  is L17 as Java defines it). The JNI's `applyExtensions:` called `getDescription()` on a null filter
  (`GlassDialogs.m:133`), and `CallObjectMethod` crashed on the NULL receiver; after a throwing `extensionsToArray()`
  (:136) it crashed in `GetArrayLength(NULL)` (:137). `MacDialogCalls.fileFilters` gives a null filter an empty title
  and no file types, and a filter whose `extensionsToArray()` threw gets no file types. What they threw replaces the
  pending exception (PB11) and is reported once at the filter check (§6 (c)). Neither case is reachable from
  `CommonDialogs.showFileChooser` (L32). `MacDialogNativeTest.aNullFilterIsAnEmptyTitleWithoutFileTypes`,
  `aThrowingUpcallReplacesThePendingExceptionAndLeavesItsStringsOut`.

### 7. Proposed C ABI header (excerpt of `glass_mac_api.h`)

```c
#include <stdint.h>
/* Threads: FX thread == AppKit main thread for every export and slot. NO export may be bound critical(true):
 * menu exports re-enter Java (validate), dialogs run a nested loop, pasteboard calls block on pboard IPC.
 * Strings in: (const uint16_t*, int32_t len) UTF-16, not NUL-terminated; NULL = Java null (C maps it as the JNI did).
 * Strings out: malloc'd block + int32_t* out_len, freed once with gmac_free; NULL = Java null.
 * Booleans: int32_t 0/1, converted (BOOL)(v != 0). Handles: void* = the ObjC object the JNI cast to jlong. */
JFX_EXPORT uint32_t gmac_abi_version(void);
JFX_EXPORT void     gmac_free(void *block);
/* Dependency (MacApplication scope): GlassThreadDataKey creation (JNI_OnLoad today) and the "main Java thread
 * attached" flag that replaces jEnv != NULL must exist before any export below runs. */

#define GMAC_PASTEBOARD_GENERAL 1        /* MacPasteboard.General */
#define GMAC_PASTEBOARD_DRAG_AND_DROP 2  /* MacPasteboard.DragAndDrop */
#define GMAC_ACTION_NONE 0x0
#define GMAC_ACTION_COPY 0x1
#define GMAC_ACTION_MOVE 0x2
#define GMAC_ACTION_COPY_OR_MOVE 0x3
#define GMAC_ACTION_REFERENCE 0x40000000
#define GMAC_ACTION_ANY 0x4FFFFFFF
#define GMAC_KEY_MODIFIER_SHIFT 0x01
#define GMAC_KEY_MODIFIER_FUNCTION 0x02
#define GMAC_KEY_MODIFIER_CONTROL 0x04
#define GMAC_KEY_MODIFIER_OPTION 0x08
#define GMAC_KEY_MODIFIER_COMMAND 0x10
#define GMAC_VK_F1 0x70
#define GMAC_VK_F12 0x7B
#define GMAC_VK_F13 0xF000
#define GMAC_VK_F24 0xF00B

/* --- MacPasteboard (GlassPasteboard.m) --- */
#define GMAC_REP_BAD    -1  /* representation array length != 2: C logs the :347 message with data_len */
#define GMAC_REP_NULL    0
#define GMAC_REP_STRING  1  /* data = const uint16_t*, data_len = code units */
#define GMAC_REP_BYTES   2  /* data = const uint8_t*,  data_len = bytes; offset UTI reads 8 (L1): Java pads to >= 8 */
#define GMAC_REP_IMAGE   3  /* data = NSImage* (+1) from Pixels.attachData, may be NULL; ALWAYS consumed */
typedef struct GmacPasteboardRep {   /* 32 bytes, align 8 (LP64: arm64 and x86_64) */
    const uint16_t *uti;    /* +0  item[UtfIndex]; len <= 0 -> nil, as GetStringLength == 0 did */
    int32_t uti_len;        /* +8  */
    int32_t kind;           /* +12 GMAC_REP_*; C branches on the UTI exactly as :206; on kind mismatch (UB in JNI) skips */
    const void *data;       /* +16 */
    int32_t data_len;       /* +24 */
    int32_t reserved;       /* +28 */
} GmacPasteboardRep;
typedef struct GmacPasteboardItem {  /* 16 bytes, align 8 */
    const GmacPasteboardRep *reps;   /* +0 NULL = null item, skipped (:781) */
    int32_t rep_count;               /* +8 */
    int32_t reserved;                /* +12 */
} GmacPasteboardItem;
JFX_EXPORT void    *gmac_pasteboard_create_system(int32_t type);                    /* +0, never released */
JFX_EXPORT void    *gmac_pasteboard_create_user(const uint16_t *name, int32_t len); /* +1 (L3) */
JFX_EXPORT uint8_t *gmac_pasteboard_get_name(void *pb, int32_t *out_len);           /* [name UTF8String] incl. NUL */
/* Block: int32 n_items; per item int32 n_types (-1 = null String[]); per type int32 n_bytes + createUTF bytes.
 * Native-endian, unaligned. NULL = Java null (no items). */
JFX_EXPORT uint8_t *gmac_pasteboard_get_utis(void *pb, int32_t *out_len);
JFX_EXPORT uint8_t *gmac_pasteboard_get_item_as_raw_image(void *pb, int32_t index, int32_t *out_len); /* old byte[] verbatim */
JFX_EXPORT uint8_t *gmac_pasteboard_get_item_string(void *pb, int32_t index, const uint16_t *uti, int32_t uti_len, int32_t *out_len);
JFX_EXPORT uint8_t *gmac_pasteboard_get_item_bytes(void *pb, int32_t index, const uint16_t *uti, int32_t uti_len, int32_t *out_len); /* non-NULL + 0 = byte[0] */
JFX_EXPORT int64_t  gmac_pasteboard_put_items(void *pb, const GmacPasteboardItem *items, int32_t item_count, int32_t supported_actions);
JFX_EXPORT int64_t  gmac_pasteboard_clear(void *pb);
JFX_EXPORT int64_t  gmac_pasteboard_get_seed(void *pb);
JFX_EXPORT void     gmac_pasteboard_release(void *pb);
JFX_EXPORT int32_t  gmac_pasteboard_get_allowed_operation(void *pb);
JFX_EXPORT uint8_t *gmac_clipboard_file_reference_path(const uint16_t *path, int32_t len, int32_t *out_len); /* NULL = UTF8String NULL */

/* --- Menus (GlassMenu.m); menu_id is Java-assigned per native GlassMenu --- */
typedef struct GmacMenuImage {   /* 24 bytes, align 8 */
    void   *image;               /* +0  NSImage* +1 consumed; NULL = attachData gave none -> no change (:280) */
    int32_t width, height;       /* +8, +12 Pixels.getWidthUnsafe/getHeightUnsafe */
    float   scale_x, scale_y;    /* +16, +20 getScaleXUnsafe/getScaleYUnsafe */
} GmacMenuImage;
JFX_EXPORT void *gmac_menubar_create(void);                                        /* +1, never released (L9) */
JFX_EXPORT void  gmac_menubar_insert(void *menubar, void *menu, int32_t pos);      /* may dial validate */
JFX_EXPORT void  gmac_menubar_remove(void *menubar, void *menu, int32_t pos);      /* may dial validate, menu_disposed */
JFX_EXPORT void *gmac_menu_create(int64_t menu_id, const uint16_t *title, int32_t title_len, int32_t enabled);
JFX_EXPORT void *gmac_menu_item_create(int64_t menu_id, const uint16_t *title, int32_t title_len, uint16_t shortcut,
                                       int32_t modifiers, const GmacMenuImage *icon /* NULL = no Pixels */,
                                       int32_t enabled, int32_t checked);
JFX_EXPORT void  gmac_menu_insert(void *menu, void *submenu /* NULL = separator */, int32_t pos);
JFX_EXPORT void  gmac_menu_remove(void *menu, void *submenu /* NULL = removeItemAtIndex: */, int32_t pos);
JFX_EXPORT void  gmac_menu_set_title(void *menu, const uint16_t *title, int32_t title_len);
JFX_EXPORT void  gmac_menu_set_shortcut(void *menu, uint16_t shortcut, int32_t modifiers);
JFX_EXPORT void  gmac_menu_set_image(void *menu, const GmacMenuImage *icon /* NULL = setImage:nil */);
JFX_EXPORT void  gmac_menu_set_enabled(void *menu, int32_t enabled);
JFX_EXPORT void  gmac_menu_set_checked(void *menu, int32_t checked);

/* --- Dialogs (GlassDialogs.m) --- */
typedef struct GmacFileFilter {          /* 32 bytes, align 8; C copies everything into NSStrings before the loop */
    const uint16_t        *description;     /* +0 NULL -> @"" */
    int32_t                description_len; /* +8 */
    int32_t                extension_count; /* +12 */
    const uint16_t *const *extensions;      /* +16 */
    const int32_t         *extension_lens;  /* +24 */
} GmacFileFilter;
/* Return 1 iff button == NSModalResponseOK. *out_urls: malloc'd array of NSURL*, each +1 for transfer (Java wraps
 * it in MacFileNSURL or CFReleases it); NULL/0 otherwise. *out_selected: popup indexOfSelectedItem (NSInteger),
 * written when filter_count > 0. loop_id 0: the panel is configured but never begun (as :91). */
JFX_EXPORT int32_t gmac_dialog_open(void *owner, const uint16_t *folder, int32_t folder_len,
        const uint16_t *title, int32_t title_len, int32_t multiple,
        const GmacFileFilter *filters, int32_t filter_count /* -1 = null */, int32_t default_index,
        int64_t loop_id, void ***out_urls, int32_t *out_url_count, int64_t *out_selected);
JFX_EXPORT int32_t gmac_dialog_save(void *owner, const uint16_t *folder, int32_t folder_len,
        const uint16_t *filename, int32_t filename_len, const uint16_t *title, int32_t title_len,
        const GmacFileFilter *filters, int32_t filter_count, int32_t default_index,
        int64_t loop_id, void **out_url, int64_t *out_selected);
JFX_EXPORT int32_t gmac_dialog_folder(void *owner, const uint16_t *folder, int32_t folder_len,
        const uint16_t *title, int32_t title_len, int64_t loop_id, void **out_url);
JFX_EXPORT uint16_t *gmac_url_copy_path(void *nsurl, int32_t *out_len); /* [[url path] dataUsingEncoding:UTF16LE], as :193-194 */
/* Only while §10 leaves the CF substitution unproven: */
JFX_EXPORT uint8_t *gmac_url_get_bookmark(void *url, void *base_url, int32_t *out_len);            /* NULL = error, NSLog'd */
JFX_EXPORT void    *gmac_url_create_from_bookmark(const uint8_t *data, int32_t len, void *base_url); /* +1 or NULL; data non-NULL even for len 0 */

/* --- Test hooks (bound by a shim only) --- */
JFX_EXPORT int32_t  gmac_sizeof_pasteboard_rep(void);  /* 32 */
JFX_EXPORT int32_t  gmac_sizeof_pasteboard_item(void); /* 16 */
JFX_EXPORT int32_t  gmac_sizeof_menu_image(void);      /* 24 */
JFX_EXPORT int32_t  gmac_sizeof_file_filter(void);     /* 32 */
JFX_EXPORT int64_t  gmac_test_fire_menu_callback(int32_t slot, int64_t menu_id);
JFX_EXPORT int64_t  gmac_test_fire_dialog_callback(int32_t slot, int64_t loop_id);
JFX_EXPORT uint8_t *gmac_test_utis_block(int32_t *out_len);  /* null inner, "", BMP, U+1F600, lone-surrogate NSString -> 0 bytes */
JFX_EXPORT uint8_t *gmac_test_raw_image_block(int32_t w, int32_t h, int32_t *out_len); /* header + both bounds (L6) */
JFX_EXPORT int32_t  gmac_test_constant(int32_t index); /* the 19 hand-copied constants in header order, -1 past the end */
```

**Ownership.**
- System pasteboard: +0, owned by AppKit.
- User pasteboard: +1 and never balanced (L3).
- `GlassMenubar`: +1, never released.
- `GlassMenu`: +1, released only inside `*_remove` when it is found there; that release fires `menu_disposed`
  synchronously.
- `NSImage` in: +1, consumed by the export it is passed to.
- `NSURL` out: +1 transfer.
- `MacFileNSURL.ptr`: +1 until `dispose()`.
- Every out-block: freed once with `gmac_free`.
- In-strings, reps, items and filters: from a per-call confined arena. C copies them, and the arena stays open across
  the dialog's nested loop.
- No struct crosses by value.

**As built (slice 7).** The pasteboard and clipboard part of `glass_mac_api.h` exports:
- 12 `gmac_pasteboard_*` functions: `create_system`, `create_user`, `get_name`, `get_utis`, `get_item_as_raw_image`,
  `get_item_string`, `get_item_bytes`, `put_items`, `clear`, `get_seed`, `release` and `get_allowed_operation`;
- `gmac_clipboard_file_reference_path`, the kept `_convertFileReferencePath` (§10);
- `GmacPasteboardRep`, 32 bytes, and `GmacPasteboardItem`, 16 bytes, at the sketch's offsets: `uti` 0, `uti_length`
  8, `kind` 12, `data` 16, `data_length` 24 and `reserved` 28; `reps` 0, `rep_count` 8 and `reserved` 12. `kind` is
  one of `GMAC_PASTEBOARD_REP_BAD` (-1), `_NONE` (0, a value of no other kind, in place of the sketch's
  `GMAC_REP_NULL`), `_STRING` (1), `_BYTES` (2) and `_IMAGE` (3). A null item has `rep_count`
  `GMAC_PASTEBOARD_ITEM_NULL` (-1), in place of the sketch's NULL `reps`. `gmac_abi_constant` pins the six values;
- 4 probes: `gmac_sizeof_pasteboard_rep` (32), `gmac_sizeof_pasteboard_item` (16), `gmac_offsetof_pasteboard_rep`
  and `gmac_offsetof_pasteboard_item`;
- six shim-only hooks:
  - `gmac_test_clipboard_mime_to_uti` and `gmac_test_clipboard_uti_to_mime`, which run the former FormatEncoder
    bodies as the oracle of the UTType and CoreFoundation calls that replace them. This follows the slice-5 W27
    precedent, `gmac_test_nslocale_language_direction` (g2 §7 "As built"). Both stay in the shipping libglass on
    purpose (PB19, §10);
  - `gmac_test_pasteboard_utis_block`, in place of the sketch's `gmac_test_utis_block`;
  - `gmac_test_pasteboard_raw_image` and `gmac_test_pasteboard_raw_image_bounds`, in place of the sketch's
    `gmac_test_raw_image_block`: the block made from given TIFF, PNG or PDF data, and the two size bounds (L6);
  - `gmac_test_pasteboard_describe_items`, which runs the part of `put_items` that makes the `NSPasteboardItem`s and
    describes each setter call.

Unlike the sketch, the exports that answer a string or a block return an `int32_t` status, `GMAC_OK`, or
`GMAC_ERR_NO_DATA` where the JNI answered null. They hand the block out through `out_*` pointers, and it is freed
with `gmac_free`. Every block has a NUL past its length, so an empty one is not NULL. `get_name` and
`gmac_clipboard_file_reference_path` follow the strings-out convention. `get_utis` and `get_item_string` answer
`createUTF`'s bytes, which Java decodes as UTF-8 (R4). In the `get_utis` block, an item without UTIs, the JNI's null
`String[]`, counts 0 and not the sketch's -1.

Not built: the sketch's 19 hand-copied `GMAC_*` constants and `gmac_test_constant`. Under R3 item 9 the C keeps its
`com_sun_glass_*` spellings, and the hand-kept `com_sun_glass_ui_mac_MacPasteboard.h` and
`com_sun_glass_ui_Clipboard.h` in `constants/` serve them once `javac -h` stops writing them.

`GlassDragSource.h` no longer imports `<jni.h>`. It includes `<stdint.h>`, and its 10 class methods with a mask or an
action, and the two statics and two locals of GlassDragSource.m, take `int32_t` where they took `jint`.

The cleanup landed after the Java flip, in the same slice. Deleted, from the working tree as the native step had
left it:
- in GlassPasteboard.m, the whole JNI section: all 13 `Java_com_sun_glass_ui_mac_MacPasteboard_*` bodies,
  `_initIDs` among them, and their helpers, `createUTF` (`GlassPasteboard.m:355` at 1a6788cc4d) and
  `ByteArrayFromBlock`, `readInt32` and `copyStringChars`, which the native step had written for the transitional
  JNI bodies. `GlassPixelsAttachData` stays for GlassMenu.m (slice 9's cleanup deleted it and `jPixelsAttachData`
  with `attachJavaIcon`, their last caller; see Slice 9);
- in GlassSystemClipboard.m, the three `Java_*` bodies and the `GlassHelper.h` import. `UTIForMIME` and
  `MIMEForUTI` stay only as the bodies of `gmac_test_clipboard_mime_to_uti` and `gmac_test_clipboard_uti_to_mime`;
- in `constants/com_sun_glass_ui_mac_MacPasteboard.h`, `UtfIndex` and `ObjectIndex`, which no C reads. The header
  now holds only `General` and `DragAndDrop`, which `gmac_pasteboard_create_system` reads.

Against 1a6788cc4d, GlassPasteboard.m goes from 923 to 958 lines: the 12 exports and their C helpers stand where the
13 JNI bodies and their JNI helpers (`ByteArrayFromPixels`, `ByteArrayFromNSData`, `SetNSPasteboardItemValueForUtf`,
`NSPasteboardItemFromArray` and `createUTF`) were. GlassSystemClipboard.m goes from 105 to 96. In the pre-cleanup
working tree the two files had 1,375 and 168 lines.

libglass now exports 40 `_Java_` symbols, plus `JNI_OnLoad` and 145 `gmac_*`, the set `glass_mac_api.h` declares.

**Detached-thread check.** Each of the 12 MacPasteboard natives other than `_initIDs` opened with
`GLASS_ASSERT_MAIN_JAVA_THREAD`, ran its body all the same, reported the `RuntimeException` at the first
`GLASS_CHECK_EXCEPTION` it reached, and then answered as usual (L18, R1). So each `gmac_pasteboard_*` call is REPORT
once, then a normal result. Java evaluates `gmac_main_thread_detached()` before the downcall and reports through
`Application.reportException` after it. In `putItems` the report comes before the first `Pixels.attachData`, where
the JNI's first check came. One case differs: `_getItemBytesForUTF`, on data whose length is negative as a `jsize`,
reports only the `NegativeArraySizeException`, which replaced the pending exception (PB11, L25).
`_convertFileReferencePath` and the two FormatEncoder natives asserted no thread, so Java evaluates nothing for them.

**FormatEncoder A/B** (PB2 (ii)). The checked-in evidence is `MacClipboardNativeTest`:
- `theConversionsAndTheFileReferencePathAreThoseOfTheJniBodies` compares the Java conversions with the golden
  `src/test/resources/test/com/sun/glass/ui/mac/mac-clipboard-golden-1a6788cc4d.txt`. The JNI natives wrote it in
  the surefire JVM before the flip, with the bodies of 1a6788cc4d and their Objective-C factored out unchanged: 30
  inputs, each through `_convertMIMEtoUTI`, through `_convertUTItoMIME` and through both in turn, plus 12 paths
  through `_convertFileReferencePath`, 102 values in all. The golden describes macOS 27.0.1 aarch64; elsewhere the
  test is skipped, or fails under `-Djfx.parity.require=true`;
- `theConversionsAreThoseOfTheirObjectiveCBodies` compares the same 30 inputs, in the same JVM and on any Mac, with
  the oracle hooks `gmac_test_clipboard_mime_to_uti` and `gmac_test_clipboard_uti_to_mime`, which run the JNI
  bodies' Objective-C (PB19, §10). `aMimeTypeGoesToTheIdentifierOfTheObjectiveCBodyAndBackToItsMimeType` and
  `aDeclaredUtiHasTheMimeTypeOfTheObjectiveCBody` do the same for four MIME types, one of them mixed-case, and three
  declared UTIs.

The inputs include null, the empty string, U+00E9, U+1F600, an embedded NUL, lone and swapped surrogates, declared
UTIs and malformed `dyn.` UTIs. Supporting evidence, not checked in: before the flip, a probe program using
Foundation and CoreServices only ran the JNI bodies' `NSString` chain and the CoreFoundation chain that Java binds in
one process on macOS 27.0.1 arm64, and all 28 of its cases (10 MIME types to UTIs, 18 UTIs to MIME types) gave
identical bytes.

### 8. Callback tables

```c
typedef struct GmacMenuCallbacks {      /* 40 bytes; installed once; stubs process-lifetime (Arena.global, GTK precedent) */
    void (*action)(int64_t menu_id);        /* :175  Callback.action(); Java returns if the entry's callback is null */
    void (*validate)(int64_t menu_id);      /* :222  Callback.validate(); null callback -> NPE, reported (HotSpot NPE today) */
    void (*menu_opening)(int64_t menu_id);  /* :199  entry.delegate.menu.notifyMenuOpening() */
    void (*menu_closed)(int64_t menu_id);   /* :209  ...notifyMenuClosed() */
    void (*menu_disposed)(int64_t menu_id); /* :151-160 dealloc, exactly where DeleteGlobalRef ran; the ONLY registry removal */
} GmacMenuCallbacks;
typedef struct GmacDialogCallbacks {    /* 16 bytes */
    void (*event_loop_enter)(int64_t loop_id); /* :109 blocks; a throw is reported and C continues (as :110) */
    void (*event_loop_leave)(int64_t loop_id); /* :75 completion handler; unknown id -> no-op (L14) */
} GmacDialogCallbacks;
JFX_EXPORT int32_t gmac_sizeof_menu_callbacks(void);    /* 40 */
JFX_EXPORT void    gmac_menu_set_callbacks(const GmacMenuCallbacks *cb);
JFX_EXPORT int32_t gmac_sizeof_dialog_callbacks(void);  /* 16 */
JFX_EXPORT void    gmac_dialog_set_callbacks(const GmacDialogCallbacks *cb);
```

**As built (slices 8 and 9):** see Slice 8, Slice 9 and `glass_mac_api.h`.

- **Firing rule.** A site fires only when its slot is non-NULL **and** the main Java thread is attached. That is the
  replacement for `env != NULL`. NULL slots are no-ops.
- **Upcall targets.** Every target catches `Throwable` and calls `Application.reportException`, following the
  `GtkCallbackTargets` convention.
- **Registry.** It holds **strong** references `{MacMenuDelegate, Callback}` per `menu_id`, matching the global-ref
  pin. A menu that is never removed stays pinned, as it does today.
- **`_setCallback`.** It only updates the registry entry.

**As built (slice 7).** MacPasteboard and MacSystemClipboard have no callback table. Their two upcalls, §6 rows 1 and
2 at the pasteboard sites, became Java around the downcall: `Pixels.attachData` runs before
`gmac_pasteboard_put_items`, and Java decodes the pasteboard strings. The two tables above are for slices 8 and 9.

### 9. Deletion candidates (gate passed)

| Candidate | Parity | C lines removed | Platform copies | Java counterpart in tree | What the parity test asserts |
|---|---|---:|---:|---|---|
| `FormatEncoder._convertMIMEtoUTI` + `_convertUTItoMIME` (GlassSystemClipboard.m:31-77) | exact (ruled by reading) | 47 | 1 (mac) | none for UTType. CF toolkit in `com.sun.javafx.font.coretext.CFNative` (package-private) and `com.sun.javafx.font.JniStringCodec` (public) reproduce `NewStringUTF`. | String equality against a golden captured from the JNI build over a MIME/UTI corpus: declared types, `dyn.` round trips, empty, U+00E9, U+1F600, U+0000, and a payload with a lone surrogate. Also null for NULL. The odd conformance argument `"public.mime-type"` (L19) is carried. |
| `MacFileNSURL._dispose` (:247-257). **Deleted in favour of Java `objc_release`** (slice 8, PB2 (i)), not the `CFRelease` §5 proposed | **exact by construction**: the JNI body is a tail call of `objc_release` with the same pointer, `mov x0, x2; b _objc_release` in libglass on arm64 and `jmpq *_objc_release` on x86_64. The disassembly is of the 1a6788cc4d body, which the slice-8 cleanup deleted, so building 1a6788cc4d re-checks it | 11 | 1 | `MacDialogCalls.release`, a libobjc `objc_release` bind with no export | `MacDialogNativeTest.releaseDropsTheRetainCountOfTheUrlByOne`: the Java call drops the URL's `CFGetRetainCount` from 2 to 1, and is called on 0, which `objc_release` ignores as `[nil release]` did |
| `_startAccessing…` / `_stopAccessing…` (:259-281). **Not deleted (PB2):** kept native as `gmac_url_start_accessing_security_scoped_resource` / `gmac_url_stop_accessing_security_scoped_resource` (§10) | exact (by reading; sandbox manual), but PB2 (i) fails (§10) | 23 | 1 | none | boolean equality, JNI vs CF, on temp-file URLs and on a URL resolved from a security-scoped bookmark |
| 4 × `_initIDs` + `_setCallback` (JNI bookkeeping) | exact | 26 + 39 + 43 + 10 + 25 = 143 | 1 | — | removed by the migration itself; no new behaviour. `MacPasteboard._initIDs` done in slice 7 |

Run the A/B while the JNI still exists, through a shim, as a headless module test. The run loads libglass and calls
the JNI natives and the CF binds side by side; it creates no `NSApp` window.

### 10. Kept native despite being pure, or until parity is known

| Item | Status | Reason, and the experiment that settles it |
|---|---|---|
| `_startAccessing…` / `_stopAccessing…`, kept as `gmac_url_start_accessing_security_scoped_resource` / `gmac_url_stop_accessing_security_scoped_resource` (slice 8) | **Kept native (PB2).** Follow-up: Java replacement over CFURL after the owner's A/B, the sandbox included | The C sends `startAccessingSecurityScopedResource` / `stopAccessingSecurityScopedResource` through `objc_msgSend`, which Java may not call, and the CFURL twins `CFURLStartAccessingSecurityScopedResource` / `CFURLStopAccessingSecurityScopedResource` are different functions, so PB2 (i) fails. The headless check, `MacDialogNativeTest.securityScopeAccessAnswersAsCoreFoundationOnAFileUrl`, compares the export with the CF twin on a plain file URL only; the security-scoped case needs a sandboxed app (Owner-run item 8). |
| `_getBookmark` → `CFURLCreateBookmarkData`, kept as `gmac_url_get_bookmark` (slice 8) | **PARITY: unknown.** Kept native (PB2). Follow-up: Java replacement after the owner's sandbox check | Bookmark blobs may embed per-call data. First call the JNI version twice on one URL; if the bytes match, compare JNI vs CF bytes; if not, compare resolution (path via `gmac_url_copy_path`, isStale, error presence, NSLog text). The owner's sandbox check (Owner-run item 8) settles it. |
| `_createFromBookmark` → `CFURLCreateByResolvingBookmarkData`, kept as `gmac_url_create_from_bookmark` (slice 8) | **PARITY: unknown.** Kept native (PB2). Follow-up: Java replacement after the owner's sandbox check | Resolve the same blobs (valid, stale with the file moved, garbage, empty) both ways and compare path, `isStale` (`Boolean` → `JAVA_BOOLEAN`), error presence, and NSLog lines (`NSLog` is bindable with `firstVariadicArg(1)`). The owner's sandbox check (Owner-run item 8) settles it. |
| `_convertFileReferencePath` (OS-CALL) → `CFURLCreateWithString` + `CFURLCreateFilePathURL` + `CFURLCopyFileSystemPath` | **PARITY: unknown, divergence likely** | `NSURL.h:72` (SDK): "For apps linked on or after iOS 17 and aligned OS versions, NSURL parsing … automatically percent- and IDNA-encodes invalid characters". The behaviour depends on the SDK the `java` launcher links. Run a corpus (spaces, `%`, non-ASCII, `file:///.file/id=` references, invalid strings) through both. Stays native. |
| `validate`, `GlassDragSource` mappers, `ByteArrayFromPixels` (`copyRawImageBlock` since slice 7) | PURE, exact possible | Kept because their only callers are C. |
| `UTIForMIME` and `MIMEForUTI`, the former FormatEncoder bodies, behind the oracle hooks `gmac_test_clipboard_mime_to_uti` and `gmac_test_clipboard_uti_to_mime` | **Kept on purpose (PB19).** A deliberate exception, not a follow-up | Java's UTType and CoreFoundation binds replace them (§9), but they stay in the shipping libglass as the oracle. The JNI golden (§7 "FormatEncoder A/B") is gated on macOS 27.0.1 aarch64 and can no longer be re-captured from this tree, because the JNI natives are gone. So the in-process A/B, `MacClipboardNativeTest.theConversionsAreThoseOfTheirObjectiveCBodies`, is the only parity check on other machines (x64 CI, other macOS releases). The slice-5 W27 oracle, `gmac_test_nslocale_language_direction`, stays for the same reason (g2 §10). |

### 11. Build/test touchpoints, risks, recommendation

**Build.**
- AC 9: the 8 generated-header imports and 19 constants need the hand-copied `GMAC_*` constants. `N/GlassKey.m` still
  uses `KeyEvent.h`, which is out of scope.
- `jni.h` leaves GlassMenu.h, GlassDialogs.h and GlassDragSource.h. GlassMenu.h is also imported by
  `N/GlassWindow.h:29`.
- The target compiles with `-Werror`. Removed includes must not leave undeclared `jint`/`jboolean`.

**Existing tests.**
- None in `modules/*/src/test` touches these natives.
- `tests/system/src/test/java/test/com/sun/glass/ui/mac/MacPasteboardTest.java` uses
  `J/../../../../../shims/java/com/sun/glass/ui/mac/MacPasteboardShim.java` (`pushToSystem`/`popFromSystem` on the
  DnD clipboard; shim API unchanged).
- Others: `test.javafx.scene.input.ClipboardTest`, `test.com.sun.javafx.tk.quantum.SystemMenuBarTest`, robot
  `SystemMenuBar{HelpMenu,Enable,ClickEvents}Test`, `MenuDoubleShortcutTest`.
- Manual: `tests/manual/{systemmenu,dnd,clipboard}`, monkey `FileChooserPage`.
- **All of these are owner-run.** They start Glass and activate the app.

**Headless tests to add.**
- Layouts against the `gmac_sizeof_*` exports.
- Slot descriptors through `gmac_test_fire_*`.
- Block parsers from `gmac_test_utis_block` / `gmac_test_raw_image_block`.
- Constant pins through `gmac_test_constant`.
- The §9/§10 A/B shims.
- A round trip on a uniquely named user pasteboard is probably safe. Have the owner verify the first run: reading the
  General or Drag pasteboard may raise macOS's paste-privacy prompt.

**Risks.**

- **R1 — no native in scope throws to its caller.**
  - Every pending exception is caught by a trailing `GLASS_CHECK_EXCEPTION`. That covers OOM in `NewByteArray`,
    throws from upcalls, and the AIOOBE at GlassDialogs.m:475/548.
  - This includes the assert macro's `RuntimeException("Main Java thread is detached.")`: the body continues after
    `ThrowNew`, and the exception is *reported* after the work (L18). The Java replacement must do the same: report
    after the downcall when `pthread_main_np() == 0` and the thread is detached.
  - The only exception that propagates is `NoSuchMethodError` from `_initIDs`.
  - So every Java step that replaces a JNI step must catch, report and return the JNI default (null / 0 / empty
    list).
- **R2 — re-entrancy.**
  - `validate` fires inside `gmac_menubar_insert/remove` and `gmac_menu_remove`.
  - Dialog exports run a nested loop that makes further downcalls and upcalls.
  - None of these may be `critical`.
- **R3 — `GlassThreadDataKey`.** It is created in `JNI_OnLoad` (`N/GlassApplication.m:91`). `pthread_getspecific` on a
  key that was never created reads another key's slot, so the key must be created before any `gmac_*` call.
- **R4 — strings.**
  - `createUTF` returns UTF-8. A lone surrogate yields `""` because `lengthOfBytesUsingEncoding` returns 0. Java must
    decode with `new String(b, UTF_8)`, not `CFStringGetCharacters`.
  - The 5 `NewStringUTF` sites feed standard UTF-8 to a modified-UTF-8 decoder. Supplementary characters in
    pasteboard names, MIME types and **dropped file paths** are mangled today: `F0 9F 98 80` becomes U+00F0, U+009F,
    and trailing characters are dropped (L22). Carry this with `JniStringCodec`.
- **R5 — exception reporting moves.** The menu upcalls left exceptions pending (L20); they were reported later, or
  surfaced at `N/GlassApplication.m:744` after `[NSApp run]` returned. FFM reports them immediately. This is
  unavoidable and follows the GTK convention.
- **R6 — order of Java side effects.** `attachData`, filter marshalling and `new EventLoop()` move before the
  downcall, keeping their relative order. JNI ran `attachData` after `clearContents`. This is unobservable, apart from
  when a throw gets reported.
- **R7 — ObjC exceptions** (`objectAtIndex:` after the pasteboard changes, L5; `removeItemAtIndex:`) unwind through
  FFM frames exactly as they unwind through JNI frames today. This is undefined behaviour; carry it.
- **R8 — Java access.** `Pixels.attachData`, `ExtensionFilter.extensionsToArray`, `Menu.notifyMenuOpening/Closed`
  (protected) and the private `MacFileNSURL(String,long)` were reachable only because JNI ignores access. Use
  `privateLookupIn` (GTK precedent) or have the Java agent add an accessor.
- **R9 — cross-scope dependencies.**
  - `[GlassWindow _resetGrab]` reaches the window table.
  - The menubar handle is consumed by `MacWindow._setMenubar`.
  - The attached flag and the pthread key belong to MacApplication.
  - `jPixelsAttachData` stays live for `N/GlassCursor.m:151`. Slice 2 moved that call to Java, and slice 9's cleanup
    deleted `jPixelsAttachData` and `GlassPixelsAttachData` with `attachJavaIcon`, their last caller (see Slice 9).
- **R10 — CF toolkit visibility.** The toolkit in `CFNative` is package-private. Glass needs its own linkage or a
  shared one; Java agent's call. Respect the 900-line file budget with `Mac*Calls` aggregates.
- **R11 — booleans.** `(BOOL)jboolean`: BOOL is `bool` on arm64. Use `int32_t` 0/1 in the ABI.
- **R12 — 32-bit.** `jlong_to_ptr` is `intptr_t` and is fine on LP64. The `(jsize)` truncations of `NSUInteger`
  lengths over 2³¹ are carried.

**Latent bugs to carry verbatim (`N/`).**
- **L1.** GlassPasteboard.m:284: `sizeof(array)==sizeof(jint)*2` compares pointer size, so it is always true and 8
  bytes are read whatever the length.
- **L2.** :641: the `CGBitmapContextCreate` context is never released.
- **L3.** :461: the user pasteboard `retain` is never balanced; `releaseGlobally` does not release the object.
- **L4.** :213-217: PDF is treated as a string type, and an `else` is missing before the RTF test.
- **L5.** :574/692/741: `objectAtIndex:` throws on a stale index.
- **L6.** :150 vs :632-633: two different size bounds.
- **L7.** GlassMenu.m:222: validate on a NULL `jCallback` produces a pending NPE.
- **L8.** :665, :349-353, :383-388: NULL dereference when the pointer is 0.
- **L9.** :145-166 and :75-79: `menu` is never released; `GlassMenubar` leaks.
- **L10.** GlassMenu.h:41: the `jMenu` ivar is unused.
- **L11.** An NSMenu that outlives its GlassMenu keeps a dangling delegate.
- **L12.** GlassDialogs.m:150: `m_filters` is not retained.
- **L13.** :483 and :556: `return NULL` inside `GLASS_POOL_ENTER` skips `GLASS_POOL_EXIT`, so the pool counter never
  returns to zero.
- **L14.** The completion block can run after the global ref is deleted at :112 if `enter()` threw.
- **L15.** :475/548: AIOOBE on popup index -1; it is reported and the filter becomes null.
- **L16.** :361: `_createFromBookmark` can return a plain `File` from a native declared to return `MacFileNSURL`.
- **L17.** :137: `GetArrayLength(NULL)` crashes if `extensionsToArray` throws.
- **L18.** The assert macro reports the exception instead of throwing it.
- **L19.** GlassSystemClipboard.m:46: conformance to `"public.mime-type"` always yields `dyn.*`.
- **L20.** Menu upcalls are never exception-checked.
- **L21.** Local references accumulate in `_getUTFs`; this goes away with JNI.
- **L22.** Modified-UTF-8 mangling at the `NewStringUTF` sites.
- **L23.** `ByteArrayFromPixels` takes bytes [3..0] of a `size_t`, which is correct on little-endian only.
- **L24** (slice 7). L1's 8-byte read, by array length, from HotSpot's `jni.cpp` (not run): `GetByteArrayElements`
  answers a C-heap copy of exactly the array, or for an empty array a reserved read-only page, so an empty drag image
  offset read zeros and gave `{0, 0}`, and one of 1 to 7 bytes read garbage past the copy.
  `gmac_pasteboard_put_items` keeps the 8-byte read, and Java pads a shorter `byte[]` with zeros: the empty case is
  unchanged, and the 1-to-7-byte case reads zeros where the JNI read garbage.
- **L25** (slice 7). `createUTF` (`_getUTFs`, `_getItemStringForUTF`) on a string whose UTF-8 length is negative as a
  `jsize`, over 2 GiB: `NewByteArray` threw, `GLASS_CHECK_EXCEPTION` reported the `NegativeArraySizeException`, and
  `SetByteArrayRegion` on the NULL array then crashed the VM. The C cannot carry a VM crash; `gmac_pasteboard_get_utis`
  and `gmac_pasteboard_get_item_string` answer `GMAC_ERR_NO_DATA` (null) there. `_getItemBytesForUTF` did not crash
  (its `javaArray != NULL` check), so `gmac_pasteboard_get_item_bytes` hands Java the negative length to report.
- **L26** (slice 7). `_putItemsFromArray` read each value as its UTI said, whatever its Java type: a null value, a
  Java null UTI or a value of the wrong type crashed in `GetStringChars`/`GetByteArrayElements`/`CallVoidMethod` or
  read an object's header as data. None is reachable from `MacSystemClipboard`: its values are never null, a `Pixels`
  goes only under the raw and drag image UTIs, and a custom MIME's UTI is always `dyn.*` (L19), never a string UTI.
  `GmacPasteboardRep` tags each value with its Java kind, and the C sets nothing for a mismatch, releasing an image it
  was given.
- **L27** (slice 7). `_putItemsFromArray` on an item that is not an `Object[]`, a representation that is null or not
  an `Object[]`, or a UTI that is not a `String`: the JNI crashed in `GetArrayLength` or read the object as an array
  or a string. `MacPasteboardCalls.putItems` throws the `NullPointerException` or `ClassCastException` of its cast out
  of `putItemsFromArray`, before the downcall, and the +1 `NSImage`s that `Pixels.attachData` made for the earlier
  representations of that call are never released. None is reachable from `MacSystemClipboard`: `hashMapToArray`
  builds only null or `{String, value}` arrays.
- **L28** (slice 8). GlassDialogs.m:302 and :358 test the `NSError` out-parameter, not the result. An error written
  on success makes `_getBookmark` and `_createFromBookmark` log it and answer null. A nil result without an error
  makes `_getBookmark` answer an empty `byte[]` and `_createFromBookmark` a file of `""` (`convertNSURLtoFile(nil)`),
  a `MacFileNSURL` with `ptr` 0 when FileNSURL is enabled. `gmac_url_get_bookmark` and `gmac_url_create_from_bookmark`
  test the same out-parameter, and the latter answers `GMAC_OK` with a NULL URL, whose `gmac_url_copy_path` is `""`.
- **L29** (slice 8). `convertNSURLtoFile` (GlassDialogs.m:208-215) retains the URL before the `MacFileNSURL` exists.
  When `ClassForName` fails (:211) or the constructor throws (reported at :215), the reference is never released. The
  dialog exports and `gmac_url_create_from_bookmark` hand Java the URL retained; Java keeps that reference unreleased
  where the JNI leaked it.
- **L30** (slice 8). `applyExtensions` keeps only `[extension pathExtension]` (GlassDialogs.m:143), and
  `extensionFilterChanged:` takes `"*"` for every file type (:171). So `"*.*"` becomes `"*"` and allows every type,
  but `"*"` becomes `""`, which becomes an allowed type of its own. The dialog exports keep both steps
  (`gmac_test_dialog_describe_filters` shows the extensions they keep).
- **L31** (slice 8). L16 as Java defines it. The JNI answered a plain `File` from `_createFromBookmark` when
  `glass.macosx.enableFileNSURL` turned false between `createFromBookmark`'s check and `convertNSURLtoFile`'s, and the
  caller then held a `File` typed as a `MacFileNSURL`. `MacDialogCalls.fromBookmark` answers null there: the URL is
  released and the `File` dropped, as the conversion of any `File` releases it. Null is what `createFromBookmark`
  already answers for a bookmark that does not resolve.
  `MacDialogNativeTest.aResolvedBookmarkThatConvertsToAPlainFileIsNull`.
- **L32** (slice 8). L17 as Java defines it, together with a null element of the `ExtensionFilter[]`, on which the
  JNI's `CallObjectMethod` crashed. `MacDialogCalls.fileFilters` gives such a filter no file types, and a NULL title,
  which the export takes as `""`, where `getDescription()` threw. What it threw replaces the pending exception and
  is reported once after the loop, where the JNI's `GLASS_CHECK_EXCEPTION` ran (PB11). Neither is reachable from
  `CommonDialogs.showFileChooser`: it checks the event thread before `ExtensionFilter`'s methods do, and
  `QuantumToolkit` makes each element with `new ExtensionFilter`.
  `MacDialogNativeTest.aThrowingUpcallReplacesThePendingExceptionAndLeavesItsStringsOut`,
  `aNullFilterIsAnEmptyTitleWithoutFileTypes`.
- **L33** (slice 8, PB7/PB18). On a detached main thread the three dialog natives ran `runModally` on the main thread
  with `jEnv` NULL: `GET_MAIN_JENV` printed its `GLASS_CALLSTACK` message, and `ClassForName` then crashed on the NULL
  `JNIEnv`. The export prints the same message and does not begin the panel, so the dialog answers as cancelled: an
  empty list and the filter the popup selected for open and save, null for the folder chooser. Java reports
  `RuntimeException("Main Java thread is detached.")` once, where the PB11 trace of the JNI at 1a6788cc4d puts it.
  For open and save, the exception is pending during the filter upcalls, a throwing upcall replaces it, and it is
  reported at the filter check when there is a filter, else after the panel, before the `ArrayList`. For the folder
  chooser it is reported after the panel, followed by what `new EventLoop()` threw, if it threw.
  `MacDialogNativeTest.aDetachedChooserReportsAtTheFilterCheckWhenThereIsAFilter`,
  `aDetachedChooserWithoutAFilterReportsAfterThePanel`,
  `aDetachedChooserWithoutAFilterReportsTheAssertionBeforeTheEventLoop`,
  `aDetachedFolderChooserReportsAfterThePanelThenWhatTheEventLoopThrew`.
- **L34** (slice 9). The GlassMenu code made its JNI calls on `GET_MAIN_JENV`'s `jEnv`, the main thread's
  `JNIEnv`, whatever thread called the native: both initializers' `NewGlobalRef` (GlassMenu.m:90-91, :115-119 at
  1a6788cc4d), `_setPixels:`'s `NewGlobalRef`, `Pixels.attachData` and four field reads (:274-300) and
  `_setCallback`'s `DeleteGlobalRef`/`NewGlobalRef` (:664-669). Off the main thread, which only
  `glass.disableThreadChecks` allows, that is another thread's `JNIEnv`; with Java detached it is NULL, and each
  printed its `GLASS_CALLSTACK` message and crashed. The `gmac_menu_*` exports make no JNI call: they print the
  message where `GET_MAIN_JENV` printed it (both initializers, and `-[GlassMenu _setImage:]` for an icon or none) and
  carry on. `Pixels.attachData` runs before the export, on the calling thread, and `_setCallback` becomes an update
  of Java's registry.
- **L35** (slice 9). L8 at `_setCallback` (GlassMenu.m:655-673 at 1a6788cc4d): the JNI replaced the `jCallback` of
  the `GlassMenu` at `jMenuPtr` (:665), so `MenuItem.setCallback` on a delegate whose `ptr` was 0, never created or
  set to 0 by a remove, crashed the VM on the NULL `GlassMenu`. `_setCallback` has no export now:
  `MacMenuCalls.setCallback` updates the registry entry of the delegate's id only for a `ptr` that is not 0, and for
  0 changes nothing, so the slots keep reaching the callback the entry had. It never files an id the registry no
  longer holds. `MacMenuNativeTest.setCallbackOnTheNullMenuChangesNothing`, `setCallbackDoesNotRefileADisposedId`.

**Recommendation.**
- **No whole-library deletion.** Migrate the 28 OS-CALL exports behind the `gmac_*` ABI, keeping JNI and FFM side by
  side as on Windows.
- **Delete the 5 bookkeeping entries** in the migration commit.
- **Delete the 5 exact WRAPPERs** in their own commit with goldens.
- **Run the §10 A/B before writing any `gmac_url_*` bookmark export.** If it comes out exact, those exports never
  exist.
- **Order inside the scope**, after MacApplication provides the pthread key and attached flag, per the story's order:
  1. `FormatEncoder` WRAPPER commit; `_convertFileReferencePath` migration.
  2. `MacFileNSURL`.
  3. `MacCommonDialogs` (needs `gmac_url_copy_path` and the dialog table).
  4. `MacPasteboard`.
  5. `MacMenuDelegate` together with `MacMenuBarDelegate` (they share GlassMenu.m).
- **After each step:** `mvn -pl modules/javafx.graphics test -Dtest='*Mac*'` (agent-runnable, headless).
- **Owner-run after each step:** `MacPasteboardTest`, `ClipboardTest`, `SystemMenuBarTest`, the robot system-menu
  tests and the manual systemmenu/dnd/clipboard/FileChooser checks, compared against the JNI baseline from the story's
  Definition of Ready. Sandbox bookmark access is a manual check.

## Group g4: accessibility

**Scribe's corrections** (details in §R2):

- "30 files": the scope has 32. `a11y/` holds 30 files (`AccessibleBase.h/.m` plus 14 `JFX*Accessibility` classes),
  and `GlassAccessible.h/.m` makes 32, 2,701 lines. §3b's "13 × `a11y/JFX*Accessibility.h`" is 14: the table lists 14
  `.m` files, and each has a header.
- §7: `JFX_EXPORT` gives way to `GMAC_EXPORT` (§R3). Under the `constants/` plan the `GMAC_VARIANT_*` enum is
  optional: the hand-kept `com_sun_glass_ui_mac_MacVariant.h` keeps today's spellings. Dropping `@Native` from
  `MacVariant` is not needed for the transport change.
- §7 "Java binds libSystem `malloc`/`free`" differs from the `gmac_free` that g0, g1 and g3 export. macOS has one
  heap, so both are correct; §R3 picks one rule.
- This group is the only one that used the baseline binary. Its `nm` evidence agrees with §R1.

Repo: `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm`, HEAD `1a6788cc4d`. Paths below are relative to
`modules/javafx.graphics/src/main/` unless they start with `modules/` or `backlog/`. This tree has no `target/`
directory. The binary evidence comes from the baseline JNI build of the same HEAD, the "Before" run of
[US-001b-macos-gate.md](US-001b-macos-gate.md): its `modules/javafx.graphics/target/native/bin/libglass.dylib`,
inspected with `nm -m`, `nm -gU`, `nm -u` and `otool -L`. That build lived in a session clone that is gone; a build
of `1a6788cc4d` reproduces it. No test, Maven or CMake run was made. Every parity call below comes from reading the
code, not from comparing outputs.

### 1. Verdict

**Migrate this slice. Do not delete it.** Of the 153 functions in scope, the verdicts are **143 OS-CALL, 9 WRAPPER,
1 PURE, 0 PURE-HOT**:
- **Why most of it stays native.** The bulk is the NSAccessibility protocol itself: 19 `GlassAccessible` methods, 27
  `AccessibleBase` methods, 89 methods in the `JFX*Accessibility` classes, plus 4 C helpers. AppKit calls these as
  Objective-C methods, so they must stay Objective-C.
- **The 14 JNI exports split three ways:**
  - **9 WRAPPER.** Seven clear the gate and are replaced by Java binding AppKit or CoreFoundation C symbols directly.
    All seven are rated `PARITY: exact` from reading the code, and each names the A/B test that has to prove it
    before the JNI body goes: `_initEnum`, `isEqualToString`, `NSAccessibilityPostNotification`,
    `NSAccessibilityUnignoredAncestor`, `NSAccessibilityRoleDescription`, `NSAccessibilityActionDescription`,
    `_destroyAccessiblePeer`. The eighth, `getString`, is dead (no Java caller); it serves as the test oracle and is
    then deleted. The ninth, `NSAccessibilityUnignoredChildren`, **fails the gate**: on a reachable crash path it
    would crash differently. It stays native.
  - **1 PURE.** `_initIDs` is JNI plumbing only. It disappears and the callback-table install replaces it.
  - **4 OS-CALL, kept.** `_createAccessiblePeer`, `_invalidateParent`, `idToMacVariant` and
    `GlassAccessibleToMacAccessible` all send Objective-C messages, which the story forbids doing from Java.
- **What the C ABI needs:** 8 exports and 1 callback table with 16 slots. The table replaces the 15 cached
  `jmethodID`s and the 2 `DeleteGlobalRef` sites in `dealloc`.

### 2. Summary counts

How I counted: the `grep` commands below, run in `native-glass/mac` and `java/com/sun/glass/ui/mac`, checked against
`nm -gU libglass.dylib`.

| Item | Count | Where / how |
|---|---:|---|
| Java `native` declarations | **14** (11 static, 3 instance, 0 `@Override native protected`) | Using the skill's regex `grep -cE '^\s*(public\|private\|protected\|static\|final\|synchronized\|\s)*\s*native\s'` gives MacAccessible 14, MacVariant 0. The "20" comes from `grep -cE '\bnative\b'`, which also hits 6 comment lines: `MacAccessible.java:174,644,647,650,1290,1730`. The story's 14 is correct. |
| `JNIEXPORT` | **14** | `GlassAccessible.m` 10 and `a11y/AccessibleBase.m` 4. `nm -gU` shows 14 `_Java_com_sun_glass_ui_mac_MacAccessible_*`. No orphans in either direction. One dead export: `getString` has no caller. |
| `JNI_OnLoad` | 0 in scope | Glass's own is at `GlassApplication.m:86-96`. |
| `Call*Method` sites | **22** | `GlassAccessible.m` 17: 15 instance upcalls, plus `CallStaticObjectMethod` `values()` at `:397` and `CallObjectMethod` `toString()` at `:408`. `AccessibleBase.m` 5. |
| Hidden upcalls | 22 | Each `GLASS_CHECK_EXCEPTION` expands to `CallStaticVoidMethod(Application.reportException)` (`GlassMacros.h:153-162`). `GlassAccessible.m` has 16, `AccessibleBase.m` 6. |
| ID lookups | 36 | 17 `GetMethodID`, 1 `GetStaticMethodID`, 18 `GetFieldID`. |
| Cached JNI globals | 38 | 15 method IDs, `jVariantInit`, 17 field IDs (`jVariantStringArray` is never read), 2 class global refs, 3 blocks. All are defined in headers and land as exported `(__DATA,__common)` symbols in `libglass.dylib`. |
| `NewGlobalRef` / `DeleteGlobalRef` / `NewWeakGlobalRef` | 4 / 2 / 0 | Per-peer pairs: `GlassAccessible.m:38`↔`:47` and `AccessibleBase.m:90`↔`:100`, both in `dealloc` and skipped when `jEnv==NULL`. Never deleted: class refs `:436` and `:472` (process lifetime). |
| `GetPrimitiveArrayCritical` | 4 | `GlassAccessible.m:248` (read), `:568`, `:584`, `:645` (write). All release mode 0; no `JNI_ABORT`. `:651` releases even when `data==NULL`. |
| `GetStringCritical` / `GetStringLength` | 1 / 1 | `:266` / `:264` (UTF-16). |
| `NewStringUTF` | 4 | `:557`, `:665`, `:703`, `:716`, each fed by `-[NSString UTF8String]`. |
| `GetStringUTFChars`, `Get*ArrayElements`, `GetDirectBufferAddress`, `NewDirectByteBuffer` | 0 | |
| Other JNI calls | — | `NewObject` 1 (`:531`); `New{Long,Int}Array` 3; `Get*Field` 26; `Set*Field` 18; `GetObjectArrayElement` 3; `GetArrayLength` 3; `FindClass` 2 (`:389` by computed name, `:470`). |
| `ThrowNew` / `ExceptionCheck` | 0 / 43 | 42 in `GlassAccessible.m`, 1 at `AccessibleBase.m:243`. |
| `GetJavaVM` / `AttachCurrentThread` / `DetachCurrentThread` | 0 | Code uses the global `jEnv`, attached at `GlassApplication.m:562` and set to NULL at `:758`. `GET_MAIN_JENV` appears 20 times, `GET_MAIN_JENV_NOWARN` 2. |
| Generated JNI headers | 2 | `com_sun_glass_ui_mac_MacAccessible.h` (prototypes only) and `com_sun_glass_ui_mac_MacVariant.h` (19 `@Native` constants). Included at `GlassAccessible.m:28-29`, `AccessibleBase.m:29-30` and `JFXStaticTextAccessibility.m:29-30`; the last two use nothing from them. `GlassAccessible.m` uses 14 distinct constants: `NSArray_id`, `NSArray_int`, `NSAttributedString`, `NSDate`, `NSDictionary`, `NSNumber_Boolean`, `NSNumber_Int`, `NSNumber_Double`, `NSString`, `NSValue_point`, `NSValue_size`, `NSValue_rectangle`, `NSValue_range`, `NSObject`. Unused: `NSArray_NSString`, `NSArray_range`, `NSData`, `NSNumber_Float`, `NSURL`. |
| Files with direct JNI | 4 | `GlassAccessible.h/.m`, `AccessibleBase.h/.m`. Every `JFX*.h` imports `AccessibleBase.h`, so all of them get `<jni.h>` transitively. `GlassViewDelegate.h:30` and `GlassApplication.m:32` import `GlassAccessible.h`. |

### 3. Java classes

| Class | Native (decl line) | JNI descriptor | Kind | JNI symbol / C impl | Java callers |
|---|---|---|---|---|---|
| MacAccessible | `_initIDs` :60 | `()V` | static | `…MacAccessible__1initIDs` `GlassAccessible.m:432` | `<clinit>` :63 |
| | `_initEnum` :61 | `(Ljava/lang/String;)Z` | static | `__1initEnum` `GlassAccessible.m:381` | :64-84 (7 enums, prints "Fail linking X") |
| | `_createAccessiblePeer` :648 | `(Ljava/lang/String;)J` | instance | `__1createAccessiblePeer` `AccessibleBase.m:298` | `getNativeAccessible` :834 (lazy) |
| | `_destroyAccessiblePeer` :651 | `(J)V` | instance | `__1destroyAccessiblePeer` `AccessibleBase.m:316` | `dispose` :681 |
| | `_invalidateParent` :654 | `(J)V` | instance | `__1invalidateParent` `AccessibleBase.m:342` (parameter is `long`, not `jlong`) | `sendNotification(PARENT)` :814 |
| | `getString` :656 | `(J)Ljava/lang/String;` | static | `_getString` `GlassAccessible.m:661` | **none (dead)** |
| | `isEqualToString` :657 | `(JJ)Z` | static | `GlassAccessible.m:673` | `MacAttribute.getAttribute` :190, `MacAction.getAction` :562 |
| | `NSAccessibilityUnignoredAncestor` :658 | `(J)J` | static | `AccessibleBase.m:330` | :1420, :1978 |
| | `NSAccessibilityUnignoredChildren` :659 | `([J)[J` | static | `GlassAccessible.m:635` | :861, :1156, :1388 |
| | `NSAccessibilityPostNotification` :660 | `(JJ)V` | static | `GlassAccessible.m:686` | :679, :707, :744, :768, :792, :793, :824 |
| | `NSAccessibilityActionDescription` :661 | `(J)Ljava/lang/String;` | static | `GlassAccessible.m:711` | :1908 |
| | `NSAccessibilityRoleDescription` :662 | `(JJ)Ljava/lang/String;` | static | `GlassAccessible.m:697` | :1346 |
| | `idToMacVariant` :663 | `(JI)Lcom/sun/glass/ui/mac/MacVariant;` | static | `GlassAccessible.m:528` | :1648, :1655, :1672, :1695, :1716, :1770 |
| | `GlassAccessibleToMacAccessible` :664 | `(J)Lcom/sun/glass/ui/mac/MacAccessible;` | static | `GlassAccessible.m:724` | :1677, :1700 |
| MacVariant | none | — | — | 19 `@Native` constants (:35-53); 17 fields read and written by C through cached field IDs | factories :75-171 |

- **`long ptr` fields.** `MacAccessible.peer` :645 holds the +1 retained peer. Each of the 7 enums has `ptr` (:171,
  :461, :516, :553, :585, :592, :606), set natively and holding NSString pointers.
- **Library loading.** There is no `loadLibrary` in either class; libglass is loaded by `MacApplication`. Peers are
  created from `MacApplication.createAccessible` at `MacApplication.java:346`.
- **Upcall targets** (all private instance methods in MacAccessible): `accessibilityAttributeNames` :998,
  `ArrayAttributeCount` :1045, `ArrayAttributeValues` :1101, `IsAttributeSettable` :1161, `AttributeValue` :1189,
  `SetValue` :1642, `IndexOfChild` :1729 (constant `-1`), `ParameterizedAttributeNames` :1734,
  `AttributeValueForParameter` :1764, `ActionNames` :1885, `ActionDescription` :1907, `PerformAction` :1911,
  `FocusedUIElement` :1938, `IsIgnored` :1948, `HitTest` :1969. Twelve of the 15 call
  `Application.checkEventThread()`. The three that do not are :1729, :1907 and :1911.

### 3b. C files

| File | Lines | JNI | Role / triage |
|---|---:|---|---|
| `native-glass/mac/GlassAccessible.h` | 89 | `<jni.h>` :27. Defines (not declares) the 38 globals, :40-88. | `GlassAccessible : NSObject` with a `jobject jAccessible` ivar. |
| `native-glass/mac/GlassAccessible.m` | 730 | 10 exports, 17 Call sites, all criticals and `NewStringUTF`s | Legacy informal-protocol peer: 19 methods (15 upcall), plus `jArrayToNSArray`, `jStringToNSString` (also used by `GlassApplication.m:1348` `_openURI`) and `variantToID`. |
| `a11y/AccessibleBase.h` | 53 | `<jni.h>` :27, `jobject` ivar :34, duplicate global :53 | `AccessibleBase : NSAccessibilityElement` (new-style API). |
| `a11y/AccessibleBase.m` | 349 | 4 exports, 5 Call sites | 27 methods, roles→class map. |
| `a11y/JFXButtonAccessibility.m` | 66 | none | OS-CALL; `accessibilityPerformPress` → `AXPress`. |
| `a11y/JFXCheckboxAccessibility.m` | 39 | none | OS-CALL. |
| `a11y/JFXGroupAccessibility.m` | 50 | none | OS-CALL. Not in `rolesMap`; used only as the superclass of ProgressIndicator. |
| `a11y/JFXImageAccessibility.m` | 50 | none | OS-CALL. |
| `a11y/JFXLinkAccessibility.m` | 51 | none | OS-CALL; legacy `accessibilityAttributeValue:` → `requestNodeAttribute:`. |
| `a11y/JFXMenuBarAccessibility.m` | 42 | none | OS-CALL, no upcall. |
| `a11y/JFXMenuItemAccessibility.m` | 73 | none | OS-CALL; label/title → `AXTitle`, `AXChildren`, `AXMenuItemMarkChar`. |
| `a11y/JFXNavigableTextAccessibility.m` | 223 | none | OS-CALL; 22 methods upcall through helpers; `composedCharacterRangeForIndex` (Foundation grapheme ranges). |
| `a11y/JFXProgressIndicatorAccessibility.m` | 69 | none | OS-CALL; rewraps `AXValue` as `numberWithDouble:`. |
| `a11y/JFXRadiobuttonAccessibility.m` | 39 | none | OS-CALL. |
| `a11y/JFXSliderAccessibility.m` | 73 | none | OS-CALL; `AXIncrement` / `AXDecrement`. |
| `a11y/JFXStaticTextAccessibility.m` | 55 | dead includes :27-31 (generated headers, `GlassAccessible.h`) | OS-CALL. |
| `a11y/JFXStepperAccessibility.m` | 78 | none | OS-CALL; `AXIncrement` / `AXDecrement`. |
| `a11y/JFXTabGroupAccessibility.m` | 56 | `GlassAccessible.h` → `jni.h` | OS-CALL; `accessibilityTabs` → `AXTabs`. |
| 13 × `a11y/JFX*Accessibility.h` | 34-50 each | `jni.h` via `AccessibleBase.h` | Interfaces only. |

Coupled code outside the scope, no JNI change needed here: `GlassView3D.m:710-769` forwards 5 legacy methods to the
root peer that `GlassViewDelegate.m:1298-1304` returns. That pointer comes from the `jViewGetAccessible` upcall, which
belongs to the MacView slice. `GlassWindow.m:239-284` overrides `accessibilityIsIgnored` /
`accessibilityAttributeValue` with no JNI.

### 4. External dependencies

- **Build.** `add_jfx_library(glass …)` is at `modules/javafx.graphics/native/mac.cmake:159-167`.
  - `SOURCE_DIRS`: `native-glass/mac` and `native-glass/mac/a11y`.
  - `COMPILE_OPTIONS`: `JFX_CC_OPTIONS` (:103-106: `-std=c99` for C and ObjC, `-O3 -DNDEBUG`),
    `-DGL_SILENCE_DEPRECATION`, `-DMACOS_MIN_VERSION_*`, `-Werror`.
  - `LINK_LIBS`: `JFX_FRAMEWORK_LINK_LIBS` (:110-119): AppKit, ApplicationServices, Carbon, OpenGL, QuartzCore,
    Security, Network, Metal, objc.
  - Every target gets the JDK include dirs and `HEADERS_DIR` from `:149-151`.
- **Imports used by this scope** (`nm -m … | grep undefined`):
  - AppKit: `_NSAccessibilityPostNotification`, `_NSAccessibilityUnignoredAncestor`,
    `_NSAccessibilityUnignoredChildren`, `_NSAccessibilityRoleDescription`, `_NSAccessibilityActionDescription`. Data
    symbols used directly by the JFX classes:
    `_NSAccessibility{Button,CheckBox,Group,Image,Incrementor,Link,MenuBar,MenuItem,Menu,ProgressIndicator,RadioButton,Slider,StaticText,TabGroup,TextArea,TextField}Role`,
    `_NSAccessibilitySecureTextFieldSubrole`, `_NSAccessibility{Children,Role,RoleDescription}Attribute`.
  - CoreFoundation: `_CFBundleGetBundleWithIdentifier`, `_CFBundleGetDataPointerForName`, `_CFRelease`.
  - libobjc: `_objc_msgSend`, `_objc_release`, `_objc_autorelease`.
  - Objective-C classes: `NSAccessibilityElement`, `NSArray`, `NSMutableArray`, `NSDictionary`,
    `NSMutableDictionary`, `NSNumber`, `NSValue`, `NSDate`, `NSString`, `NSMutableAttributedString`, `NSObject`.
- **SDK check.** All 123 `NS*` names among the 134 enum constants (MacAttribute 61, MacRole 32, MacSubrole 5,
  MacAction 9, MacNotification 14, MacOrientation 3, MacText 10) are exported data symbols in the SDK's `AppKit.tbd`;
  none is missing. The other 11 are `AX*` custom names. All C functions proposed for direct binding exist in
  `AppKit.tbd` and `CoreFoundation.tbd`.

### 5. Triage table

ObjC classes and methods are grouped in the last rows.

| C function | Verdict | Evidence | Parity gate | What replaces it |
|---|---|---|---|---|
| `_initEnum` `GlassAccessible.m:381-425` | WRAPPER | `CFBundleGetBundleWithIdentifier("com.apple.Cocoa")` and `CFBundleGetDataPointerForName` (CoreFoundation). For `AX*` names: `-[NSString initWithString:]` (+1, leaked on purpose). | **exact**, read. Same CF calls give the same pointers. `AX*` strings are equal by value; their identity is not stable across runs even today, and they are compared by value (`isEqualToString`, `GlassWindow.m:263`). Test: A/B of every enum `ptr` against the JNI result while the JNI exists. | Java iterates `values()` (`toString()` equals `name()`; no enum overrides it) and keeps the "starts with AX" rule. `NS*` names: bind the two CF calls with a temporary `CFStringCreateWithCharacters` + `CFRelease`. `AX*` names: `CFStringCreateWithCharacters` (+1, never released). Stop at the first NULL and return `false`, leaving later ptrs 0, exactly as `:421`. |
| `isEqualToString` `:673` | WRAPPER | `-isEqualToString:` (objc). C-callable equivalent: `CFEqual` (CoreFoundation). | **exact**, read. Literal comparison either way. Java must guard `ptr==0` on both sides, because messaging nil returns NO while `CFEqual(NULL)` halts. Test: A/B over a corpus (constants, `CFStringCreateCopy`, short tagged strings, NFC/NFD pairs, mutable strings). | Java binds `CFEqual(ADDRESS,ADDRESS) -> JAVA_BOOLEAN`, following the Apple `Boolean` rule. |
| `NSAccessibilityPostNotification` `:686` | WRAPPER | AppKit `NSAccessibilityPostNotification` | **exact**: same symbol, same arguments, same thread (FX = main), same autorelease pool. | Direct AppKit bind. |
| `NSAccessibilityUnignoredAncestor` `AccessibleBase.m:330` | WRAPPER | AppKit `NSAccessibilityUnignoredAncestor` | **exact**: no marshalling. | Direct AppKit bind; returns a borrowed id. |
| `NSAccessibilityRoleDescription` `:697` | WRAPPER | AppKit `NSAccessibilityRoleDescription`, then `-UTF8String`, then `NewStringUTF` | **exact**, read, over a finite domain (MacRole×MacSubrole). Conversion must be NSString → UTF-8 → `JniStringCodec.fromNewStringUtf`, which reproduces HotSpot's mangling of 4-byte sequences. Test: A/B every pair, plus a string corpus (supplementary, U+0000, lone surrogate) against the dead `getString` JNI as oracle. | Bind AppKit + `CFStringGetLength`, `CFStringGetMaximumSizeForEncoding`, `CFStringGetCString(UTF8=0x08000100) -> JAVA_BOOLEAN`, then `JniStringCodec`. NULL → null. |
| `NSAccessibilityActionDescription` `:711` | WRAPPER | AppKit `NSAccessibilityActionDescription`, then as above | **exact**, read; finite domain is the 9 MacActions plus whatever names AppKit passes. Same tests. | Same pattern. |
| `_destroyAccessiblePeer` `AccessibleBase.m:316` | WRAPPER | `-release`. The `respondsToSelector:` guard is always YES. C-callable equivalent: `CFRelease`, which dispatches `-release` for ObjC objects. | **exact**, read. Java already guards `peer != 0` (:677). `dealloc` and its callback fire identically. | Java calls the public `CFNative.CFRelease`, or its own bind. |
| `getString` `:661` | WRAPPER (dead) | `-UTF8String` + `NewStringUTF`; no Java caller | Deleting unreachable code is neutral. | Keep it during the migration as the `NewStringUTF` oracle for the string A/B, then delete. |
| `NSAccessibilityUnignoredChildren` `:635` | WRAPPER | AppKit `NSAccessibilityUnignoredChildren`; array build via `-[NSMutableArray addObject:]`. C equivalents: `CFArrayCreate`, `CFArrayGetCount`, `CFArrayGetValues`. | **Fails.** A 0 id is reachable (`NSAccessibilityTabsAttribute` with a null tab, :1386) and is fatal both ways, but differently: an NSInvalidArgumentException unwinding through Java frames versus a `CFRetain(NULL)` HALT. NSArray subclass and identity otherwise match (read). | **Stays native**: `gmac_a11y_unignored_children` (§10). |
| `_initIDs` `:432-521` | PURE (JNI plumbing) | Only JVM calls: `GetMethodID`, `GetFieldID`, `FindClass`, `NewGlobalRef`. Also creates blocks `jLongToID` (a cast) and `jIntToNSNumber` (`+numberWithInt:`). | n/a: nothing to reimplement. | `gmac_a11y_set_callbacks` from `MacAccessible.<clinit>`; the blocks become static C functions. |
| `idToMacVariant` `:528` | OS-CALL | `-boolValue`, `-doubleValue`, `-intValue`, `-UTF8String`, `-count`, `-objectAtIndex:`, `-pointValue`, `-sizeValue`, `-rectValue`, `-rangeValue` on arbitrary ids (objc_msgSend) | — | `gmac_a11y_id_to_variant`. |
| `GlassAccessibleToMacAccessible` `:724` | OS-CALL | `-getJAccessible` on an id that VoiceOver supplied. A foreign object gives an unrecognized selector, which is fatal. | — | `gmac_a11y_peer_id` plus a Java registry lookup. A pure-Java map keyed by address would make foreign or freed ids non-fatal, which is a behaviour change, so it is not used. |
| `_createAccessiblePeer` `AccessibleBase.m:298` | OS-CALL | `NSClassFromString`, `-alloc/-init`, `NSMutableDictionary` (`rolesMap`), `-copy` | — | `gmac_a11y_create_peer`. |
| `_invalidateParent` `AccessibleBase.m:342` | OS-CALL | `-isKindOfClass:`, `-clearParent` | — | `gmac_a11y_invalidate_parent`. |
| `jArrayToNSArray` `:227`, `jStringToNSString` `:262`, `variantToID` `:278` | OS-CALL | `NSMutableArray`, `+numberWithInt:`, `+stringWithCharacters:length:`, `NSDictionary`, `NSMutableAttributedString`, `NSValue`, `NSDate`, `NSNumber` | — | Kept. Each reads a C array or struct instead of JNI fields; same bodies. |
| 19 GlassAccessible methods, 27 AccessibleBase methods, 89 JFX methods, `composedCharacterRangeForIndex` | OS-CALL | AppKit calls these as NSAccessibility overrides; `-rangeOfComposedCharacterSequenceAtIndex:` | — | Kept. Each upcall site dials the callback table (§8). |

### 6. Upcall table

There is no explicit marshalling in scope: no `performSelectorOnMainThread`, no `dispatch_*` (confirmed by `grep`).
Every site uses `GET_MAIN_JENV`. Its `assert(pthread_main_np())` is compiled out by `-DNDEBUG`, and it returns the
detached default when `jEnv==NULL`.

| Java target | C sites | Thread |
|---|---|---|
| `accessibilityAttributeNames` | `GlassAccessible.m:66` | AppKit main (= FX thread) |
| `accessibilityAttributeValue` | `GlassAccessible.m:76` (`CallLongMethod` on a method that returns an object); `AccessibleBase.m:131` (`requestNodeAttribute:`, which serves every AccessibleBase/JFX getter) | main |
| `accessibilityIsAttributeSettable` | `:86`; `AccessibleBase.m:161` | main |
| `accessibilitySetValue` | `:95`; `AccessibleBase.m:175` | main |
| `accessibilityIndexOfChild` | `:104` | main |
| `accessibilityArrayAttributeCount` | `:119` | main |
| `accessibilityArrayAttributeValues` | `:130` (`(jint)` truncates `NSUInteger`) | main |
| `accessibilityParameterizedAttributeNames` | `:143` | main |
| `accessibilityAttributeValueForParameter` | `:153` (`CallLongMethod`); `AccessibleBase.m:147` | main |
| `accessibilityActionNames` | `:165` | main |
| `accessibilityActionDescription` | `:175` (`CallLongMethod` returning a String) | main |
| `accessibilityPerformAction` | `:184`; `AccessibleBase.m:242` (`ExceptionCheck` → `NO`) | main |
| `accessibilityIsIgnored` | `:195` | main |
| `accessibilityHitTest(FF)` | `:205` (varargs `CGFloat` read as `jfloat`) | main |
| `accessibilityFocusedUIElement` | `:215` | main |
| `MacAccessible$X.values()` / `toString()` | `:397` / `:408` | FX thread, class init. Both disappear. |
| `Application.reportException` | 22 `GLASS_CHECK_EXCEPTION` sites | same thread as the failing call |
| `DeleteGlobalRef` (not an upcall, but becomes one) | `GlassAccessible.m:47`, `AccessibleBase.m:100` (`dealloc`) | **Whichever thread drops the last reference.** This is the only possible off-main entry. |

**Upcalls by protocol method.**
- `GlassAccessible`: all 15 legacy methods (:61-218).
- `AccessibleBase` (through the helpers): `accessibilityValue`, `MinValue`, `MaxValue`, `Label`, `Parent` (cached),
  `Title`, `TitleUIElement`, `PlaceholderValue`, `Children`, `RoleDescription`, `Frame` (2 calls),
  `isAccessibilityFocused`, `setAccessibilityFocused:`, plus `performAccessibleAction:`.
- Subclasses: JFXButton `PerformPress`; JFXLink `accessibilityAttributeValue:`; JFXMenuItem `Label` / `Title`;
  JFXNavigableText 22 methods; JFXProgressIndicator `Value`; JFXSlider and JFXStepper `Increment` / `Decrement`;
  JFXTabGroup `Tabs`.

**How `NSAccessibilityPostNotification` is reached.** Seven Java sites (listed in §3), all behind `checkEventThread`.
Element: the peer, a child or container peer, or `View.getNativeView()`. Notification: an enum `ptr`. The JNI forwards
verbatim (`GlassAccessible.m:689`).

**Downcalls nested inside upcalls**, all on main: `isEqualToString`, `idToMacVariant`,
`GlassAccessibleToMacAccessible`, `NSAccessibilityUnignoredChildren` / `Ancestor`, `RoleDescription` /
`ActionDescription`, the lazy `_createAccessiblePeer`, and `MacApplication._getMacKey` (:1500).
`UnignoredChildren` / `Ancestor` and `PostNotification` can call back into Java. None of these downcalls may be
`critical(true)`.

**Recorded divergences (PB7), slice 10 as built (C side).** Line numbers are at 1a6788cc4d unless marked current.
- (a) Slot 15 `peer_released` reports what is pending only on the main thread (formerly latent bug 17). Both
  `dealloc`s used the main thread's `JNIEnv` on whichever thread released the peer last (`GlassAccessible.m:45-48`,
  `AccessibleBase.m:98-101`): `GET_MAIN_JENV_NOWARN` read `jEnv`, and `DeleteGlobalRef` and `GLASS_CHECK_EXCEPTION`
  ran on it, which JNI does not allow off its own thread.
  - `GlassDialPeerReleased` (current `GlassAccessible.m:212-218`) dials slot 15 on the releasing thread. From slice
    10 until slice 11 part 3 it first called `GlassReportPendingException()`, only when `pthread_main_np()` answered
    nonzero, the one thread where that check was defined, so a peer released last on another thread reported
    nothing. Part 3 deleted that report with the last JNI site (PB15): nothing can be pending now, on any thread.
  - The slot is skipped while Java is detached from the main thread (`GlassIsMainJavaAttached()`), as the JNI's
    `env != NULL` test skipped `DeleteGlobalRef`. The id then stays in the registry, as the global reference stayed
    (latent bug 13).
  - The comment on slot 15 in `glass_mac_api.h` records the skip.
- (b) The a11y slots did not keep a JNI exception that a still-JNI upcall left pending on the main thread (PB15).
  Slots 0-14 are dialled directly, not through `GlassDialSlotKeepingPending` as window slots 0, 1 and 3-8 were until
  slice 11 part 3 (g2 §6 (e)), and the FFM upcall stub cleared that exception. Under JNI it survived the upcall
  (PB11), and the `GLASS_CHECK_EXCEPTION` after each of the 20 sites reported it, or the new one that replaced it
  when the upcall threw. At `AccessibleBase`'s `-performAccessibleAction:` (`AccessibleBase.m:243`) the JNI's
  `ExceptionCheck` saw that exception too and answered NO, where the slot answered YES unless the action threw.
  Slot 15 on the main thread reported it before it dialled ((a)). It was transitional: only a still-JNI site could
  leave one pending, so the drop went with the last of them in slice 11 part 3, and part 3 also removed its note
  from the comment above `GmacAccessibleCallbacks` in `glass_mac_api.h`. With nothing left pending,
  `-performAccessibleAction:` (current `AccessibleBase.m:226-232`) answers NO when the action throws and YES when it
  does not, as the JNI did.

### 7. Proposed C ABI header

This is for the native survivors only. It is an excerpt of the shared `native-glass/mac/glass_mac_api.h`. The prefix
`gmac_` mirrors `gwin_` / `ggtk_`; the first mac slice fixes the final header name, prefix and
`GLASS_MAC_ABI_VERSION`.

```c
#include <stdint.h>
#define JFX_EXPORT __attribute__((visibility("default")))

/* = MacVariant.type; MacVariant drops @Native once a Java test pins these values */
enum { GMAC_VARIANT_NULL = 0, GMAC_VARIANT_NSARRAY_ID = 1, GMAC_VARIANT_NSARRAY_NSSTRING = 2,
       GMAC_VARIANT_NSARRAY_INT = 3, GMAC_VARIANT_NSARRAY_RANGE = 4, GMAC_VARIANT_NSATTRIBUTEDSTRING = 5,
       GMAC_VARIANT_NSDATA = 6, GMAC_VARIANT_NSDATE = 7, GMAC_VARIANT_NSDICTIONARY = 8,
       GMAC_VARIANT_NSNUMBER_BOOLEAN = 9, GMAC_VARIANT_NSNUMBER_INT = 10, GMAC_VARIANT_NSNUMBER_FLOAT = 11,
       GMAC_VARIANT_NSNUMBER_DOUBLE = 12, GMAC_VARIANT_NSSTRING = 13, GMAC_VARIANT_NSURL = 14,
       GMAC_VARIANT_NSVALUE_POINT = 15, GMAC_VARIANT_NSVALUE_SIZE = 16, GMAC_VARIANT_NSVALUE_RECTANGLE = 17,
       GMAC_VARIANT_NSVALUE_RANGE = 18, GMAC_VARIANT_NSOBJECT = 19 };

/* MacVariant minus the unused stringArray. LP64 (arm64, x86_64): sizeof 120, alignof 8.
 * Counts/lengths of -1 mean "Java null"; the receiver pre-fills them with -1 and everything else with 0.
 * Blocks come from libSystem malloc, written by the producer and freed with free() by the consumer. */
typedef struct GmacVariant {
    int32_t   type;                 /*   0 */
    int32_t   int1;                 /*   4 */
    int32_t   int2;                 /*   8 */
    int32_t   location;             /*  12  attributed-string style range */
    int32_t   length;               /*  16 */
    float     float1, float2, float3, float4;  /* 20 24 28 32 */
    int32_t   long_array_count;     /*  36 */
    int64_t   long1;                /*  40  id for NSObject, epoch seconds for NSDate */
    double    double1;              /*  48 */
    void*     key;                  /*  56  NSString*, borrowed */
    void**    long_array;           /*  64  ids, borrowed objects */
    int32_t*  int_array;            /*  72 */
    int32_t   int_array_count;      /*  80 */
    int32_t   chars_length;         /*  84  UTF-16 units (Java -> C) */
    uint16_t* chars;                /*  88  not NUL-terminated (Java -> C) */
    char*     utf8;                 /*  96  copy of -UTF8String, NUL-terminated (C -> Java) */
    struct GmacVariant* variant_array; /* 104 NSDictionary values / attributed-string styles */
    int32_t   variant_array_count;  /* 112 */
    int32_t   reserved;             /* 116 */
} GmacVariant;

JFX_EXPORT int32_t gmac_a11y_sizeof_callbacks(void);                  /* 128 */
JFX_EXPORT int32_t gmac_a11y_sizeof_variant(void);                    /* 120 */
/* main thread, once, from MacAccessible.<clinit>; the table is copied; a NULL slot answers its default */
JFX_EXPORT int32_t gmac_a11y_set_callbacks(const struct GmacAccessibleCallbacks* callbacks);
/* role: UTF-16, borrowed; returns +1 (alloc/init), owned by MacAccessible.peer, released with CFRelease;
   peer_id: Java-assigned, a new one per call (getNativeAccessible may run again after dispose) */
JFX_EXPORT void*   gmac_a11y_create_peer(const uint16_t* role, int32_t role_length, int64_t peer_id);
JFX_EXPORT void    gmac_a11y_invalidate_parent(void* peer);
JFX_EXPORT int64_t gmac_a11y_peer_id(void* object);                   /* 0 for NULL; foreign id is fatal, as today */
/* object borrowed; Java's out (confined arena); C mallocs long_array/int_array/utf8; returns 1, or 0 where NewObject failed */
JFX_EXPORT int32_t gmac_a11y_id_to_variant(void* object, int32_t type, GmacVariant* out);
/* ids borrowed (copied into an NSMutableArray); *out_ids malloc'd (borrowed objects); returns count */
JFX_EXPORT int32_t gmac_a11y_unignored_children(void* const* ids, int32_t count, void*** out_ids);

/* test hooks (shim-only, like gwin_test_*) */
JFX_EXPORT int64_t gmac_a11y_test_fire_callback(int32_t slot, int64_t peer_id);       /* fixed, distinct args per slot */
JFX_EXPORT int32_t gmac_a11y_test_describe_variant(const GmacVariant* in, char** out_utf8); /* class + -description (+objCType) of variantToID(in) */
JFX_EXPORT int32_t gmac_a11y_offsetof_variant(int32_t field_index);
```

**What Java binds directly** (no C written for it):
- **AppKit** (`/System/Library/Frameworks/AppKit.framework/AppKit`, through `libraryLookup` as in US-003):
  - `NSAccessibilityPostNotification(ADDRESS, ADDRESS)`
  - `NSAccessibilityUnignoredAncestor(ADDRESS) -> ADDRESS`
  - `NSAccessibilityRoleDescription(ADDRESS, ADDRESS) -> ADDRESS`
  - `NSAccessibilityActionDescription(ADDRESS) -> ADDRESS`
- **CoreFoundation:** `CFEqual -> JAVA_BOOLEAN`, `CFRelease`, `CFBundleGetBundleWithIdentifier`,
  `CFBundleGetDataPointerForName`, `CFStringCreateWithCharacters(ADDRESS, ADDRESS, JAVA_LONG)`,
  `CFStringGetLength -> JAVA_LONG`, `CFStringGetMaximumSizeForEncoding(JAVA_LONG, JAVA_INT)`,
  `CFStringGetCString(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT) -> JAVA_BOOLEAN`. `CFNative.CFRelease` is already public;
  the others are package-private in `CFNative`, so either the glass facade binds its own or the migrator widens them.
- **libSystem:** `malloc` and `free` through `Linker.nativeLinker().defaultLookup()`. macOS has one heap, so no
  `gmac_alloc` is needed. Windows needed `gwin_alloc` because of its CRT heaps.

**String encodings.**
- Java → C is UTF-16 with a length. This matches `GetStringCritical` + `stringWithCharacters:length:` exactly.
- C → Java is the `-UTF8String` / `CFStringGetCString` bytes decoded with `JniStringCodec.fromNewStringUtf`. This
  reproduces `NewStringUTF`, including its mangling of supplementary characters and truncation at NUL.

**As built (slice 10), C side.** The MacAccessible part of `glass_mac_api.h` exports 9 production functions and 5
shim-only hooks, where the sketch had 8 and 3. `GLASS_MAC_ABI_VERSION` stays 1. libglass exports 191 `gmac_*`, the
header's 191, 32 of them `gmac_test_*`, beside 16 `_Java_` (MacAccessible 14, MacApplication 2) and `JNI_OnLoad`.
- **`GmacVariant`, 120 bytes, alignment 8.** It has the sketch's members at the sketch's offsets, except offset 116:
  it holds `utf8_length` where the sketch had `reserved`, so a string to Java follows the strings-out convention
  (`utf8` and its length). `_Static_assert`s pin the size and every offset (`glass_mac_api.m:76`, `:205-215`).
  - `type` takes the `com_sun_glass_ui_mac_MacVariant_*` constants of the hand-kept
    `constants/com_sun_glass_ui_mac_MacVariant.h`, with 0 for a null variant. There is no `GMAC_VARIANT_*` enum
    (R3 item 9, PB10).
  - A null array is a NULL block with a count of -1. A null string from Java is NULL `chars`, not a length of -1.
- **4 OS-CALL exports.** `gmac_a11y_create_peer`, `gmac_a11y_invalidate_parent` and `gmac_a11y_peer_id` have the
  sketch's signatures. `gmac_a11y_id_to_variant` differs in what it answers:
  - it answers `GMAC_OK`, or `GMAC_ERR_NO_DATA` where the JNI's `NewStringUTF`, `NewLongArray` or `NewIntArray`
    threw `OutOfMemoryError`. The sketch answered 1, or 0 for a failed `NewObject`; Java makes the `MacVariant`
    after the call (PB9);
  - the blocks it writes are freed with `gmac_free` (R3 item 10).
- **`gmac_a11y_unignored_children(ids, count, out_ids, out_count)`**, the WRAPPER that fails the gate (§10). Where
  the sketch answered the count, it writes the count to `*out_count` and answers a status. `GMAC_ERR_NO_DATA` stands
  for a count below 0 (the JNI's null `long[]`) or a failed allocation. Its block is freed with `gmac_free`.
- **4 plumbing exports, named as in the other slices.** The sketch had `gmac_a11y_sizeof_*`,
  `gmac_a11y_set_callbacks` and the test hook `gmac_a11y_offsetof_variant`. As built:
  - `gmac_sizeof_variant` (120) and `gmac_sizeof_accessible_callbacks` (128);
  - `gmac_offsetof_variant`, now a production probe that answers -1 for a member the struct does not have;
  - `gmac_a11y_install_callbacks`, which copies the table, uninstalls it for NULL and always answers `GMAC_OK`.
- **5 shim-only hooks:**
  - `gmac_test_fire_accessible_callback(slot, peer_id, out)`, in place of the sketch's `gmac_a11y_test_fire_callback`.
    It dials one slot with fixed ids that are not objects (`ATTRIBUTE` is `0x0102030405060701`; `VALUE`, `CHILD`,
    `PARAMETER` and `ACTION` end in 2-5 instead), points the out-parameters into the caller's 120-byte buffer and
    answers the slot's status;
  - `gmac_test_a11y_variant_to_object(variant, out_object)`, in place of the sketch's
    `gmac_a11y_test_describe_variant`. It answers what the peers make of a variant, retained, and the test describes
    that object through Foundation;
  - 3 oracle hooks the sketch did not have: `gmac_test_a11y_utf8_string`, `gmac_test_a11y_is_equal_to_string` and
    `gmac_test_a11y_enum_pointer`. They run the C of the former `getString`, `isEqualToString` and `_initEnum`
    inside a `GLASS_POOL_ENTER`/`EXIT` pair. They take over from the JNI `getString` as the oracle, so `getString`
    goes with the other JNI bodies. They stay in the shipping libglass on purpose (PB19, §10).

### 8. Callback table

```c
typedef struct GmacAccessibleCallbacks {               /* 16 x 8 = 128 bytes; no void* user, peer_id is the key */
    int32_t (*attribute_names)(int64_t peer_id, void*** out_ids);                         /* -> count, -1 = null */
    int32_t (*attribute_value)(int64_t peer_id, void* attribute, GmacVariant* out);       /* -> 1 variant, 0 null */
    int32_t (*is_attribute_settable)(int64_t peer_id, void* attribute);
    void    (*set_value)(int64_t peer_id, void* value, void* attribute);
    int64_t (*index_of_child)(int64_t peer_id, void* child);
    int32_t (*array_attribute_count)(int64_t peer_id, void* attribute);
    int32_t (*array_attribute_values)(int64_t peer_id, void* attribute, int32_t index, int32_t max_count, void*** out_ids);
    int32_t (*parameterized_attribute_names)(int64_t peer_id, void*** out_ids);
    int32_t (*attribute_value_for_parameter)(int64_t peer_id, void* attribute, void* parameter, GmacVariant* out);
    int32_t (*action_names)(int64_t peer_id, void*** out_ids);
    int32_t (*action_description)(int64_t peer_id, void* action, uint16_t** out_chars);  /* -> UTF-16 length, -1 null */
    int32_t (*perform_action)(int64_t peer_id, void* action);                             /* -> 1 ok, 0 Java threw */
    int32_t (*is_ignored)(int64_t peer_id);
    void*   (*hit_test)(int64_t peer_id, float x, float y);                               /* C passes (float)point.x/.y */
    void*   (*focused_ui_element)(int64_t peer_id);
    void    (*peer_released)(int64_t peer_id);   /* fired where DeleteGlobalRef was (dealloc): ANY thread */
} GmacAccessibleCallbacks;
```

**Defaults.** On `Throwable` the stub must catch, call `Application.reportException(t)`, swallow anything the
reporting throws (the `WinCallSupport.java:157-164` precedent), and return what the JNI left after
`GLASS_CHECK_EXCEPTION`:

| Slot | Return on Throwable | Effect |
|---|---|---|
| names ×3, `action_description` | -1 | nil |
| `attribute_value*` | 0 | nil |
| `settable`, `is_ignored` | 0 | NO |
| `index_of_child` | 0 | returns 0, not super |
| `array_attribute_count` | 0 | returns 0, not super |
| `array_attribute_values` | -1 | super |
| `perform_action` | 0 | NO |
| `hit_test`, `focused` | NULL | nil |

The detached branches (`jEnv==NULL`) stay in C verbatim and differ from these:
- `array_attribute_count` returns `(NSUInteger)-1`.
- `array_attribute_values` returns nil without calling super.
- `peer_released` is skipped, matching the leaked global ref.

That flag has to be whatever the MacApplication slice uses to replace `jEnv`.

**Ownership.**
- The `attribute`, `action`, `value`, `parameter` and `child` ids are borrowed for the duration of the upcall and must
  not be stored.
- `out` and the out-pointers are C stack memory. Blocks Java writes into them pass to C, which frees them.
- Returned ids are borrowed, exactly as the JNI did.

**Arenas and registry.**
- The 16 stubs are process-wide with static targets and live in `Arena.global()` (precedent `WinCallSupport.java:214`).
- Per-call scratch uses `Arena.ofConfined()`.
- Registry: `ConcurrentHashMap<Long, MacAccessible>` with one id per `create_peer`. Publish the entry before the
  create. Remove it **only** in `peer_released`, as `WinAccessible.dispatchDisposed` (`WinAccessible.java:2042-2057`)
  does, with one exception. When `gmac_a11y_create_peer` answers NULL (`-alloc` failed or `-init` answered nil),
  `MacAccessibilityCalls.createAccessiblePeer` removes the id right after the call through
  `MacAccessibleCallbacks.forget`. No peer exists, so no `peer_released` can come, and the JNI took no global
  reference on that path. This follows Reconciliation R3 item 4 (Java has to drop a registry entry when the C silently
  dropped the request). Evidence: the checked-in test
  `MacAccessibleNativeTest.aPeerTheExportCouldNotMakeLeavesNoIdBehind`.
- `peer_released` may run on a foreign thread, so its target may touch only the map.

**As built (slice 10), C side.** `GmacAccessibleCallbacks` has the sketch's 16 slots in the sketch's order, 16
pointers and so 128 bytes (`glass_mac_api.m:77`). It differs from the sketch in four ways:
- **Status returns.** Every slot answers an `int32_t`, `GMAC_UPCALL_OK` or `GMAC_UPCALL_THREW` (R3 item 6). What it
  answers goes to out-parameters, in place of the sketch's counts, booleans, `int64_t`, `void*` and `void` returns:
  - `*out_ids` and `*out_count` for the name lists and `array_attribute_values`;
  - `*out_settable`, `*out_index`, `*out_count`, `*out_ignored` and `*out_element`;
  - `*out_chars` and `*out_length` for `action_description`, where the sketch answered the UTF-16 length or -1.
- **The defaults live in the C, not in the stub.** The C first sets each out-parameter to what the JNI read after
  an exception, which is also what it reads for a NULL slot. A slot writes them only when it returns normally. The C
  reads the status only for slot 11: `AccessibleBase`'s `-performAccessibleAction:` answers NO for
  `GMAC_UPCALL_THREW`.
- **Blocks.** A slot's blocks (ids, UTF-16 units, variant blocks) come from libSystem `malloc`, and the C frees them
  with `free()` (R3 item 10).
- **Detached main thread.** Slots 0-14 keep the detached print and answer ahead of the dial (PB14; `GET_MAIN_JENV`
  until slice 11 part 3, then `GLASS_WARN_IF_MAIN_JAVA_DETACHED` and `GlassIsMainJavaAttached()`), so the sketch's
  detached branches stay as they were. Slot 15 is skipped while `GlassIsMainJavaAttached()` answers NO: the flag
  slice 0 set and cleared where `jEnv` was set and cleared, and the only detached test since part 3 deleted `jEnv`.
  Until part 3, on the main thread it first reported what was pending (§6 (a)).

**Keep-both (PB6), closed by the slice-10 cleanup.** The bullets record the native step as built. The cleanup after
the Java flip ended the window: it deleted the JNI bodies, the `jAccessible` ivars and every JNI fallback below
(Slice 10, "Cleanup after the Java flip").
- **The peers.** `GlassAccessible` and `AccessibleBase` carry an `int64_t peerId` beside `jAccessible`. It is set by
  `initWithPeerId:`, which replaces `initWithEnv:accessible:`. The JNI `_createAccessiblePeer` calls
  `gmac_a11y_create_peer` with id 0, then gives the peer a new global reference through `setJAccessible:`.
- **The 20 upcall sites.** There are 15 in `GlassAccessible` and 5 in `AccessibleBase` (`requestNodeAttribute:`,
  `requestNodeAttribute:forParameter:`, `isNodeAttributeSettable:`, `setNodeAttribute:forAttribute:` and
  `performAccessibleAction:`). Each dials the table when `GlassAccessibleCallbacks()` answers one, and otherwise
  takes the JNI path. The five slots both classes dial go through the shared `GlassDial*` helpers of
  `GlassAccessible.h`.
- **Both `dealloc`s.** Each dials slot 15 through `GlassDialPeerReleased` when the table is installed. Otherwise it
  runs the JNI `DeleteGlobalRef` and its check.
- **The JNI bodies.** All 14 stay for the cleanup after the Java flip. `_createAccessiblePeer`,
  `_invalidateParent`, `idToMacVariant` and `NSAccessibilityUnignoredChildren` call their exports. `_initEnum` shares
  `enumConstantPointer` with `gmac_test_a11y_enum_pointer`.
- **Object construction.** `variantToObject`, `arrayOfElements` and `arrayOfVariants` build the objects from a
  `GmacVariant` or a C array, as `variantToID` and `jArrayToNSArray` build them from `MacVariant` fields and Java
  arrays. `jStringToNSString` now converts through `GlassNSStringOrNil`.

### 9. Deletion candidates (cleared the gate)

The last two columns are as built (slice 10). The tests are in `MacAccessibleNativeTest`, and "the golden" is its
`theNativesAreThoseOfTheJniBodies` against `mac-a11y-golden-1a6788cc4d.txt`. The `gmac_test_a11y_*` oracle hooks
are those of §10.

| JNI function | C lines removed | Copies | Java counterpart, as built | Parity evidence, as built |
|---|---:|---:|---|---|
| `_initEnum` | 50 (`GlassAccessible.m:376-425`) | 1 (macOS only) | `MacAccessibilityCalls.initEnum`: CoreFoundation's `CFBundleGetBundleWithIdentifier`, `CFBundleGetDataPointerForName` and `CFStringCreateWithCharacters` | PB2 (ii). The string of each of the 134 `ptr`s against the golden. Against `gmac_test_a11y_enum_pointer`: `NS*` by pointer equality, `AX*` by `-isEqualToString:` and content (`everyEnumPointerIsThatOfTheObjectiveCBody`, `aNameIsLookedUpOrMadeAsTheObjectiveCBodyDid`). The stop at the first name without a pointer: `initEnumStopsAtTheFirstNameWithoutAPointer`. |
| `isEqualToString` | 12 (:668-679) | 1 | `MacAccessibilityCalls.isEqualToString`: `CFEqual`, false for a NULL side | PB2 (ii). The 13 × 13 pairs of the equality corpus, NULL on either side included, against the golden and `gmac_test_a11y_is_equal_to_string` (`isEqualToStringIsThatOfItsObjectiveCBody`). |
| `NSAccessibilityPostNotification` | 10 (:681-690) | 1 | `MacAccessibilityCalls.postNotification`: an AppKit bind | PB2 (i), disassembly at 1a6788cc4d (below). Symbol: `theAppKitFunctionsNoTestMayCallAreAppKitExports`. No test calls it (PB3); VoiceOver (AC 8). |
| `NSAccessibilityUnignoredAncestor` | 11 (`AccessibleBase.m:325-335`) | 1 | `MacAccessibilityCalls.unignoredAncestor`: an AppKit bind | PB2 (i), disassembly at 1a6788cc4d (below). Symbol: `theAppKitFunctionsNoTestMayCallAreAppKitExports`. No test calls it (PB3); VoiceOver (AC 8). |
| `NSAccessibilityRoleDescription` | 13 (:692-704) | 1 | `MacAccessibilityCalls.roleDescription`: an AppKit bind, read by `MacClipboardCalls.utf8String` (`CFStringGetCString`, then `JniStringCodec`) | PB2 (ii). The 32 `MacRole`s × the 5 `MacSubrole`s and none, against the golden and against the AppKit function read through `gmac_test_a11y_utf8_string` (`theDescriptionsAreThoseTheirObjectiveCBodiesRead`). The conversion over the 13 strings of the UTF-8 corpus against `gmac_test_a11y_utf8_string` (`theUtf8ExtractionIsThatOfItsObjectiveCBody`). |
| `NSAccessibilityActionDescription` | 12 (:706-717) | 1 | `MacAccessibilityCalls.actionDescription`, as the role description | PB2 (ii). The 9 `MacAction`s and 5 other names, checked as the role descriptions are (`theDescriptionsAreThoseTheirObjectiveCBodiesRead`). |
| `_destroyAccessiblePeer` | 13 (`AccessibleBase.m:311-323`) | 1 | `MacAccessibilityCalls.destroyAccessiblePeer`: a libobjc `objc_release` bind, as slice 8's `MacFileNSURL._dispose`; not the `CFNative.CFRelease` §5 proposed | PB2 (i), disassembly at 1a6788cc4d (below). `destroyAccessiblePeerReleasesThePeerOnce`: a stand-in `CFString`'s retain count drops by one. |
| `getString` (dead) | 11 (:656-666) | 1 | — | It served as the oracle and then went. Its answers are the golden's `case.utf8.*` keys and the strings of its `case.enum.*` keys; `gmac_test_a11y_utf8_string` keeps its C. |

Total: 132 lines of C. In addition, the migration itself removes `_initIDs` (95 lines, replaced by a set-callbacks
function of about 10 lines) and the 38 header globals (`GlassAccessible.h:37-88`, `AccessibleBase.h:53`).

**PB2 evidence (slice 10, native step).** The disassembly is `otool -tV` of the JNI bodies in libglass on macOS
27.0.1 arm64, taken before the slice-10 cleanup deleted them. The seven bodies other than `_initEnum` were unchanged
since 1a6788cc4d. `_initEnum`'s 1a6788cc4d body makes the calls named below too (read), and its
`enumConstantPointer` survives for the oracle hook. With the bodies gone, the disassembly can be re-checked only on
a build of 1a6788cc4d (PB20). `NewStringUTF` is the function at offset 0x538 of the `JNIEnv` table. Line numbers
are at 1a6788cc4d.
- **Exact by construction (PB2 (i)):**
  - `NSAccessibilityPostNotification` (`GlassAccessible.m:686`) is `mov x1, x3; mov x0, x2;
    b _NSAccessibilityPostNotification`: a tail call of the AppKit function with the element and the notification;
  - `NSAccessibilityUnignoredAncestor` (`AccessibleBase.m:330`) is `mov x0, x2; b _NSAccessibilityUnignoredAncestor`:
    a tail call with the peer;
  - `_destroyAccessiblePeer` (`AccessibleBase.m:316`) calls `objc_opt_respondsToSelector(peer, @selector(release))`
    and, when that answers YES, tail-calls `objc_release(peer)`.
    - The guard answers YES for every peer `gmac_a11y_create_peer` makes, because both classes descend from
      `NSObject` (latent bug 10). It answers NO for 0, which `objc_release` ignores too.
    - So a libobjc `objc_release` of the peer makes the same call for every peer. Slice 8 bound the same function
      for `MacFileNSURL._dispose` (g3 §9). `CFRelease`, which §5 proposed, is a different function.
- **A headless A/B needed (PB2 (ii)).** Each runs against an oracle hook that keeps its C (PB19, §10):
  - `isEqualToString` (`GlassAccessible.m:673`) is `objc_msgSend$isEqualToString:` with the two strings. Java may
    not send it, and `CFEqual` is a different function. Oracle: `gmac_test_a11y_is_equal_to_string`;
  - `NSAccessibilityRoleDescription` (`:697`) and `NSAccessibilityActionDescription` (`:711`) call the AppKit
    function. For a non-NULL answer they then call `objc_msgSend$UTF8String` and tail-call `NewStringUTF`. The dead
    `getString` (`:661`) is those last two steps alone. Java binds the AppKit functions themselves, so only the
    conversion needs the A/B: Java's `CFStringGetCString` bytes take the place of `-UTF8String`. Oracle:
    `gmac_test_a11y_utf8_string`;
  - `_initEnum` (`:381`) calls `CFBundleGetBundleWithIdentifier`, and for an `NS*` name `CFBundleGetDataPointerForName`,
    which are C functions. An `AX*` name, though, is `objc_alloc` plus `objc_msgSend$initWithString:`, and the prefix
    test is `-rangeOfString:`. Oracle for both kinds: `gmac_test_a11y_enum_pointer`, which runs the body's own
    `enumConstantPointer`.
- **The JNI golden** of `getString`, `isEqualToString`, both descriptions and the `_initEnum` pointers is
  `mac-a11y-golden-1a6788cc4d.txt`, read by `MacAccessibleNativeTest`. It was captured before the flip and is gated
  on macOS 27.0.1 aarch64 with the preferred languages `en-US` (its `machine.*` keys).
  - It was captured in the slice-10 native-step tree on top of 1a6788cc4d, not at that commit. There `getString`,
    `isEqualToString` and both descriptions had the bodies of 1a6788cc4d. `_initEnum` went through the extracted
    `enumConstantPointer`, and `jStringToNSString`, which made its names, through `GlassNSStringOrNil` (§8).
  - So its `capture.nativeSource.*` keys hash that tree's GlassAccessible.m and AccessibleBase.m, not 1a6788cc4d's.

### 10. Kept native despite being pure or a wrapper

- **`NSAccessibilityUnignoredChildren`, kept as `gmac_a11y_unignored_children`** (WRAPPER, fails the gate). **Kept
  native (PB2).** Follow-up: Java replacement after the owner's A/B. A CF-built array gives the same result for
  non-zero ids (read), but the 0-id path is reachable and dies differently (`-addObject:nil` exception versus
  `CFRetain(NULL)` HALT). Two things would let it go: an owner who accepts the different crash text, and an A/B on
  stub peers that toggle `is_ignored`, comparing id sequences during the keep-both window.
- **The 4 OS-CALL exports** (§5). Each sends Objective-C messages, which Java may not. They are not deletion
  candidates, so there is no follow-up:
  - `_createAccessiblePeer`, kept as `gmac_a11y_create_peer`: `NSClassFromString`, `-alloc`, `-initWithPeerId:`,
    `rolesMap`'s `NSMutableDictionary` and `-copy`;
  - `_invalidateParent`, kept as `gmac_a11y_invalidate_parent`: `-isKindOfClass:` and `-clearParent`;
  - `idToMacVariant`, kept as `gmac_a11y_id_to_variant`: `-boolValue`, `-doubleValue`, `-intValue`, `-UTF8String`,
    `-count`, `-objectAtIndex:` and the `NSValue` getters, all on arbitrary ids;
  - `GlassAccessibleToMacAccessible`, kept as `gmac_a11y_peer_id`: `-getPeerId` on an id that VoiceOver supplied.
    A foreign object raises an `NSException` for the unrecognized selector, as `-getJAccessible` did.
- **The oracle hooks `gmac_test_a11y_utf8_string`, `gmac_test_a11y_is_equal_to_string` and
  `gmac_test_a11y_enum_pointer`. Kept on purpose (PB19).** A deliberate exception, not a follow-up.
  - They keep the C of `getString`, `isEqualToString` and `_initEnum`: `-UTF8String`, `-isEqualToString:`, and the
    `enumConstantPointer` that `_initEnum` shares.
  - Java's AppKit, CoreFoundation and libobjc binds replace those bodies (§9), and the JNI natives go with the
    cleanup. The JNI golden, `mac-a11y-golden-1a6788cc4d.txt`, is gated on macOS 27.0.1 aarch64 with `en-US` and can
    no longer be re-captured from this tree after that.
  - So the in-process A/B against these hooks is the only parity check on other machines (x64 CI, other macOS
    releases), as with g2's `gmac_test_nslocale_language_direction` and g3's clipboard hooks.
- **`+getComponentAccessibilityClass:` / `rolesMap`** (`AccessibleBase.m:37-84`). It is a pure 25-entry lookup, but
  `NSClassFromString` consumes it inside C, so moving it would delete nothing. Its `IMAGE` key matches no
  `AccessibleRole`.
- **`composedCharacterRangeForIndex`** (`JFXNavigableTextAccessibility.m:27-39`). It is an OS-CALL. If anyone proposes
  replacing it with `BreakIterator`, rule it `PARITY: unprovable`: Foundation's grapheme rules are the spec.

### 11. Build and test touchpoints

**Build.**
- AC 9 needs a `JNI` opt-in in `mac.cmake:130-154`, as in `win.cmake:105-155`.
- Includes to drop: `GlassAccessible.h` must lose `<jni.h>` and the globals; it is pulled into `GlassViewDelegate.h`
  and `GlassApplication.m`. Also drop the generated-header includes from 3 `.m` files. **Done** in the slice-10
  cleanup: GlassAccessible.h and AccessibleBase.h no longer include `<jni.h>` or define the globals, and the 3 `.m`
  files no longer import `com_sun_glass_ui_mac_MacAccessible.h`. Their `com_sun_glass_ui_mac_MacVariant.h` import
  stays, served by `constants/` (R3 item 9).
- `jStringToNSString` also serves `GlassApplication.m:1348` (`_openURI`), so it must already be migrated in the
  MacApplication slice.
- `-std=c99 -Werror`: check that `_Static_assert(offsetof…)` compiles warning-free.
- The header globals compile only because Apple clang defaults to `-fcommon`. Verified: a scratch `clang -c` emits
  `(common)` with clang-2100.3.34.2, and libglass exports all 38 as `(__DATA,__common)`.

**Existing tests.**
- Nothing under `src/test` or `tests/system` references `MacAccessible` or `MacVariant`, and no Mac accessibility
  class appears in `backlog/US-001b-macos-before-per-class.tsv`.
- `javafx.controls` tests such as `QueryAccessibleAttributeTest` cover only the scene-graph side.
- Manual: `tests/manual/a11y/AccessibilityNotificationTest(App).java`,
  `tests/manual/monkey/…/AccessibilityPropertyViewer.java`.

**New tests wanted** (pattern: `test.com.sun.glass.ui.win.WinAccessibility*NativeTest`):
- symbol resolution;
- `sizeof` / `offsetof` for both structs;
- `gmac_a11y_test_fire_callback` for all 16 slots, including a throwing target, in a child JVM;
- variant describe goldens;
- the A/Bs in §9;
- a registry leak loop.

Whether `gmac_a11y_create_peer` or the AppKit binds touch `NSApp` or the window server is **unverified**. Run them in
a child JVM and have the owner confirm on the first run that no Dock icon appears and focus does not change.

**As built (slice 10).** `MacAccessibleNativeTest` runs in the surefire JVM and starts no child JVM. Each slot runs on
a fresh platform thread that stands for the main thread. It asserts `NSApp` nil before its first call into libglass
and after its last. In place of the list above:
- symbols: `MacGlassNativeTest.everyExportResolvesInTheLoadedLibrary` for the `gmac_a11y_*` exports, and
  `theAppKitFunctionsNoTestMayCallAreAppKitExports` for the two AppKit functions no test may call;
- layouts: `theVariantIsWhereTheCompilerPutIt` and `theTableIsTheSixteenSlotsOfTheHeaderInItsOrder`;
- the 16 slots through `gmac_test_fire_accessible_callback`:
  `everySlotReachesTheReceiverOfItsIdAndWritesWhatItAnswered`, `aNullAnswerLeavesTheOutParametersAsTheCSetThem`,
  `whatASlotThrowsIsReportedAtOnceAndLeavesItsOutParameters` and `withoutATableOrItsSlotNothingIsDialled`;
- the variants, without describe goldens: what a slot hands the C (`aVariantCrossesWithEveryFieldAndBlock`), and a
  round trip through `gmac_test_a11y_variant_to_object` and `gmac_a11y_id_to_variant`
  (`thePeersMakeTheObjectOfEachKindOfVariant`, `idToMacVariantReadsWhatTheJniReadOfEachType` and the date,
  dictionary, attributed-string and null cases);
- the registry, without a leak loop: `idsAreNonzeroDistinctAndKeptUntilReleased`,
  `peerReleasedForgetsOnlyItsIdOnAnyThreadAndNoSlotReachesItAfterwards`, `anIdTheRegistryNeverHeldRunsNothing`,
  `createAccessiblePeerFilesANewIdBeforeTheExportAndTheIdNamesItsAccessible` and
  `aPeerTheExportCouldNotMakeLeavesNoIdBehind`;
- the A/Bs: the tests named in the §9 table.

No test makes a peer (PB3): a stand-in takes the place of `gmac_a11y_create_peer`. The only AppKit functions called are
`NSAccessibilityRoleDescription` and `NSAccessibilityActionDescription`. So the question above bears on no agent test:
a real peer is made only while the toolkit runs, as in the VoiceOver session (AC 8). The owner's watch for a Dock icon
or a focus change is on the surefire run of `MacAccessibleNativeTest` (slice 10 "Owner-run").

**Owner-run.**
- VoiceOver before and after (AC 8), plus `tests/manual/a11y`.
- Recommended: an AX-tree dump harness, before and after. A client process using ApplicationServices
  `AXUIElementCreateApplication`, `AXUIElementCopyAttributeNames`, `AXUIElementCopyAttributeValue`,
  `AXUIElementCopyParameterizedAttributeValue`, `AXUIElementCopyActionNames` and `AXObserverAddNotification` dumps a
  fixed scene. It needs a window and Accessibility permission. It is the only automated end-to-end check of the
  variant path.

### 12. Risks, and latent bugs to carry verbatim

**Risks.**
- **Modified UTF-8.** Four `NewStringUTF` sites; use `JniStringCodec` for every C → Java string. There is no
  `GetStringUTFChars` and no reliance on `JNI_ABORT`.
- **Global refs pin peers.** The per-peer global ref keeps a disposed `MacAccessible` reachable until `dealloc`. Today
  an upcall on such a peer throws an NPE that gets reported (`eventHandler==null`). If registry entries were dropped at
  `dispose`, that reporting would silently change.
- **Thread.** Only `peer_released` is off-main. No slot blocks, but every downcall can re-enter Java, so none may be
  `critical(true)`.
- **No struct-by-value at the boundary.**
- **LP64 only.** `jlong_to_ptr` is `(intptr_t)`; `NSUInteger`→`jint` truncation at `:130` and `:622-623`.
- **Upcall cost.** `MacAttribute.getAttribute` can make up to 61 `CFEqual` downcalls per lookup. Keep the identity
  fast path.
- **Facade size.** Production facade parts must stay at 900 lines or fewer; split like `WinAccessibilityCalls` (665)
  and `WinAccessibleCallbacks` (725).

**Latent bugs to carry verbatim:**
1. `CallLongMethod` is used for methods that return objects (`GlassAccessible.m:76`, `:153`, `:175`;
   `AccessibleBase.m:131`, `:147`). It works only because HotSpot keeps the local ref in the `jvalue`. The FFM slots
   remove this quirk with no behaviour change.
2. `ReleasePrimitiveArrayCritical` is called with NULL (`:651`).
3. The detached path of `ArrayAttributeCount` returns `NSUIntegerMax` (`:118`); the detached path of
   `ArrayAttributeValues` returns nil without calling super (`:129`).
4. An exception in `IndexOfChild` or `ArrayAttributeCount` returns 0, not super.
5. `NSRange` int→`NSUInteger` sign extension (`:352-354`, `:327`).
6. A nil element makes `jArrayToNSArray` throw (`:252`). Reachable through null tabs, or through a 0 `ptr` after a
   failed `_initEnum`.
7. `_initEnum` leaves later `ptr`s at 0 after the first failure, and leaks the `AX*` strings on purpose (`:416`).
8. `AccessibleBase` caches `parent` unretained (`:205`) and leaks `jRole` (`:119`; not released in `dealloc`).
9. `_invalidateParent` takes `long` (`:343`).
10. `respondsToSelector:@selector(release)` is always YES (`:320`).
11. In `variantToID`, the attributed string leaks on the early `ExceptionCheck` return (`:320`).
12. `GET_MAIN_JENV`'s main-thread assert is compiled out in release builds.
13. `dealloc` skips `DeleteGlobalRef` after detach.
14. `jVariantStringArray` is cached but never read.
15. `MacAccessible.isDisposed()` recreates a peer after `dispose` (`Accessible.java:76-78` plus
    `MacAccessible.java:829-841`). This is why ids must be per-create.
16. `NSAccessibilityRoleDescription(macRole.ptr …)` throws an NPE when a role has no MacRole (:1346).
17. Moved to §6 divergence (a) (PB7): not carried. `dealloc`'s report of what is pending now runs only on the main
    thread.
18. `AccessibleBase`'s initializer sets `self->parent` after its nil check (`AccessibleBase.m:89-92`), so it would
    write through nil if `-[NSAccessibilityElement init]` answered nil. Carried in `initWithPeerId:`.

### 13. Recommendation

Migrate this slice last, as the story orders it.

1. **C first, additive and keep-both.** Add the 8 `gmac_a11y_*` exports and both structs. Give `GlassAccessible` and
   `AccessibleBase` an `int64_t peerId` beside `jAccessible`. Make each of the 20 upcall sites and `dealloc` dial the
   table when it is installed and fall back to JNI otherwise. Factor `variantToID`, `jArrayToNSArray` and
   `jStringToNSString` so each Objective-C construction exists only once.
2. **Facade and tests.** Bind the exports, the 4 AppKit symbols, the CF calls and `malloc`/`free`. Write the binding
   tests, and the A/Bs of §9 while the JNI still exists. The JNI `getString` is the oracle.
3. **Flip `MacAccessible`.** Install the registry and the callback table in `<clinit>`. Replace the 7 WRAPPER natives
   with direct binds and `dispose` with `CFRelease`. Run `mvn -pl modules/javafx.graphics test`.
4. **Sweep.**
   - Delete the 14 JNI bodies, `_initIDs` and the 38 globals.
   - Remove `<jni.h>` and the generated-header includes from the 4 direct-JNI files and
     `JFXStaticTextAccessibility.m`.
   - Drop `@Native` from `MacVariant` once a test pins its constants.
   - Then the `JNI` opt-in in `mac.cmake`.
5. **Owner-run.** VoiceOver (AC 8), `tests/manual/a11y`, and the AX-tree dump comparison.

The WRAPPER binds are behaviour-neutral, as in Windows slices 2, 3 and 5. No Java reimplementation of PURE logic is
part of this slice.

## Reconciliation

Every grep below was re-run for this document from `modules/javafx.graphics/src/main`. `N` stands for
`native-glass/mac` (including `a11y/`), `J` for `java/com/sun/glass/ui/mac`, and `D` for the baseline
`libglass.dylib` named at the top.

### R1. Headline counts

| # | Count | Grep | Result | Story | Auditors |
|---|---|---|---|---|---|
| 1 | Size | `find N -type f \| wc -l`; `find N -type f -print0 \| xargs -0 cat \| wc -l` | 100 files (70 top level, 30 in `a11y/`), 19,728 lines | agrees | g0 agrees |
| 2 | `JNIEXPORT` | `cat N/*.[mhc] N/a11y/*.[mh] \| grep -c JNIEXPORT`; per file `grep -c JNIEXPORT N/*.m N/a11y/*.m` | **155** in 15 `.m` files, none in a header or a comment: GlassWindow 30, GlassView 20, GlassApplication 19, GlassMenu 14, GlassPasteboard 13, GlassRobot 12, GlassAccessible 10, GlassDialogs 10, GlassTimer 8, GlassCursor 6, AccessibleBase 4, GlassPixels 3, GlassSystemClipboard 3, GlassKey 2, GlassGestureSupport 1 | agrees | g1 51 + g2 51 + g3 40 + g4 14 = 156, which is 155 plus one double claim (§R4). g0's 21 overlap g1 |
| 3 | Exports in the binary | `nm -gU D \| grep -c ' T _Java_'`, then per class by symbol name | **155** `_Java_` symbols plus `_JNI_OnLoad`. MacWindow 30, MacApplication 21, MacView 20, MacAccessible 14, MacPasteboard 13, MacRobot 12, MacMenuDelegate 11, MacTimer 8, MacCursor 6, MacFileNSURL 6, MacCommonDialogs 4, MacMenuBarDelegate 3, MacPixels 3, MacSystemClipboard 3 (1 + 2 in `FormatEncoder`), MacGestureSupport 1 | the per-class list agrees | g4 agrees |
| 4 | Java `native` declarations | `grep -cE '^\s*(@Override\s+)?((public\|private\|protected\|static\|final\|synchronized)\s+)*native\s+((public\|private\|protected\|static\|final\|synchronized)\s+)*[A-Za-z_][A-Za-z0-9_<>,\[\] ]*\s+[A-Za-z_][A-Za-z0-9_]*\s*\(' J/*.java` | **155** in 15 classes, per class as in the story. 38 static, 117 instance. 46 override a superclass method: 44 have `@Override` on the same line, and 2 have it on the line before (`MacApplication.java:429,432`) | agrees | g1 + g2 + g3 + g4 = 156 − 1 (`MacGestureSupport` twice). The skill's regex (`jni-to-ffm-migration` §1) returns **111**, because it misses exactly the 44 same-line `@Override native` lines |
| 5 | `Call*Method` lines | `grep -cE '(->\|\.)Call(Static\|Nonvirtual)?[A-Za-z]+Method[AV]?\b' N/*.[mh] N/a11y/*.[mh]` | **127** lines in 20 files: GlassViewDelegate 23, GlassApplication 20, GlassAccessible 17, PlatformSupport 9, GlassViewCGL 8, GlassDialogs 7, GlassView3D 6, GlassViewMTL 6, GlassMenu 5, GlassWindow+Java 5, GlassWindow+Overrides 5, AccessibleBase 5, GlassMacros.h 2, GlassRobot 2, GlassTimer 2, GlassCursor 1, GlassHelper 1, GlassPasteboard 1, GlassScreen 1, GlassWindow 1 | agrees as a raw grep, but see the next rows | g1 38 + g2 53 + g3 13 + g4 22 = 126 live lines, plus the commented one. g0's 55 overlap g1 (35) and g2 (20) |
| 5a | … of which compiled | the same grep, then `#if` and `//` inspection | **125**. `GlassWindow.m:74` sits in the commented-out `Sys_out_sprintf`, and `GlassApplication.m:737` sits under `#if defined(VERBOSE_LOAD)`, which is never defined | — | g1 counts :737 as compiled out; g2 drops :74 |
| 5b | … as call sites after macro expansion | `grep -n 'define SEND_\|SEND_KEY_EVENT(\|SEND_MODIFIER_KEY_EVENT' N/GlassViewDelegate.m` | **131** direct upcall sites: 125 − 2 macro bodies in `GlassMacros.h` (:158, :170, the `reportException` call) − 2 macro bodies in `GlassViewDelegate.m` (:664 `SEND_KEY_EVENT`, used 4×; :725 `SEND_MODIFIER_KEY_EVENT_WITH_TYPE`, used 6×) + 10 expansions | — | g2 gets 61 for its files: 53 + 8 |
| 6 | Upcalls outside that grep | `grep -nE '(->\|\.)NewObject[AV]?\('`; `grep -nE 'ClassForName:'`; macro counts as in row 9 | 16 `NewObject` constructor upcalls (GlassDialogs 7, GlassViewCGL 2, and 1 each in GlassAccessible, GlassCursor, GlassPasteboard, GlassScreen, GlassView3D, GlassViewMTL, PlatformSupport). 26 `ClassForName` call sites, each a `Class.forName` upcall at `GlassHelper.m:86`. 284 `GLASS_CHECK_EXCEPTION*` sites and 102 `GLASS_ASSERT_MAIN_JAVA_THREAD` sites behind the `reportException` upcall | not counted. AC 4's "128" is this 127 plus prism_mtl's 1 | `NewObject`: g1 3 + g3 8 + g4 1 + g0 4 (the `Integer.<init>` keys in g2's files) = 16. `ClassForName`: g1 8 + g2 7 + g3 11 = 26 |
| 7 | Files with JNI | `grep -l JNIEnv N/*.[mhc] N/a11y/*.[mh] \| wc -l` | **32** name `JNIEnv` (21 `.m` + 11 `.h`). 41 name `JNIEnv` or `jni.h`, which is the AC 1 target set. 25 make a JNI call (`(*env)->`, `(*jEnv)->`). 20 include `<jni.h>`. 54 hold any JNI type token | 32 matches only the first reading | g0 agrees on the 20 `<jni.h>` includers |
| 8 | JNI idioms, library-wide, comment lines excluded | one `grep -oE "(->\|\.)\s*<idiom>\("` per idiom | `GetPrimitiveArrayCritical` 9, `Get*ArrayElements` 4, `GetDirectBufferAddress` 3, `NewDirectByteBuffer` 0, `GetStringChars` 6, `GetStringCritical` 1, `NewStringUTF` 26, `NewString` 3, `GetStringUTFChars` 0, `ThrowNew` 2, `ExceptionCheck` 109, `ExceptionDescribe` 4, `ExceptionOccurred` 2, `AttachCurrentThread[AsDaemon]` 3, `DetachCurrentThread` 2, `GetJavaVM`/`GetEnv` 0, `GetMethodID` 83, `GetStaticMethodID` 19, `GetFieldID` 25, `GetStaticFieldID` 1, `NewGlobalRef` 37, `DeleteGlobalRef` 15, `NewWeakGlobalRef` 0 | — | g0's criticals 11 and `NewStringUTF` 27 include comments (§R2). Per group: criticals 1 + 2 + 2 + 4 = 9; `NewStringUTF` 11 + 6 + 5 + 4 = 26; global refs 14/4 + 13/4 + 6/5 + 4/2 = 37/15 |
| 9 | Macro uses outside `GlassMacros.h`, comments excluded | `grep -oE '\b<MACRO>\b'` per file | `GLASS_CHECK_EXCEPTION` 274 in 23 files, plus 10 `_RETURN`/`_NONNULL_…_RETURN` in `PlatformSupport.m`. `GLASS_ASSERT_MAIN_JAVA_THREAD` 102 in 10 files. `GET_MAIN_JENV` 84 in 12 files. `_NOWARN` 6 in 5 files | — | g0 agrees. Asserts: g1 27 + g2 47 + g3 28 = 102. Check sites: g1 102 + g2 95 + g3 73 + g4 22 = 292, which is 284 plus the 8 in `GlassGestureSupport.m`, counted by both g1 and g2 |
| 10 | Generated-header imports | `grep -hE '^\s*#\s*(import\|include)\s*"com_sun_' N/*.[mh] N/a11y/*.[mh]` | 60 lines in 23 files, 29 distinct headers | — | g0 agrees |
| 10a | Live constants from them | `cat N/*.[mh] N/a11y/*.[mh] \| grep -vE '^\s*#\s*(import\|include)\|^\s*\*\|^\s*//\|Class:\|#pragma' \| grep -oE '\bcom_sun_glass_[A-Za-z0-9_]+' \| sort -u` | **210** in 15 headers: KeyEvent 133, MacVariant 14, MouseEvent 13, Window 10, WindowEvent 6, Clipboard 6, MacGestureSupport 6, DndEvent 4, SwipeGesture 4, ViewEvent 4, MacPasteboard 4, View_Capability 2, Window_Level 2, Cursor 1, Pixels_Format 1. All 210 are `#define`d in the baseline's `target/gensrc/headers/javafx.graphics/` (36 `com_sun_glass*` headers) | — | g0's 215 and its KeyEvent 138 include 5 names that occur only in `//` lines (`GlassKey.m:156-167`: `VK_ALPHANUMERIC`, `VK_KATAKANA`, `VK_F14`, `VK_F15`, `VK_F16`). g2's reading holds: the baseline `com_sun_glass_ui_Window.h` defines `UNTITLED 0L`, `TITLED 1L`, `TRANSPARENT 2L`, `EXTENDED 4L`, none of which is `@Native` (`Window.java:126-129`) |
| 11 | JNI type tokens | `grep -oE '\b(j(int\|long\|…\|weak)\|JNIEnv\|JavaVM\|JNIEXPORT\|JNICALL\|JNI_[A-Z_0-9]+\|jlong_to_ptr\|ptr_to_jlong)\b'` | 2,128 with the JNI macros; 1,830 for the `j*` types alone. `jlong_to_ptr`/`ptr_to_jlong`: 87 tokens in 17 files, 2 of them the definitions in `common.h` | — | g0's 2,122 was not reproduced (its regex is not given); the order of magnitude agrees. g0's "85 uses in 16 files" agrees |
| 12 | The baseline binary | `otool -L D`; `nm -u D \| wc -l`; `nm -m D \| grep undefined` grouped by `(from …)`; `nm -gU D \| grep -c ' T '` | arm64. 16 dependencies besides itself: AppKit, ApplicationServices, Carbon, OpenGL, QuartzCore, Security, Network, Metal, libobjc, libc++, libSystem, CoreFoundation, CoreGraphics, CoreServices, CoreVideo, Foundation. 356 imports: AppKit 92, libSystem 78, OpenGL 44, CoreGraphics 33, CoreFoundation 24, Foundation 23, libobjc 22, CoreVideo 8, QuartzCore 7, Network 7, CoreServices 6, Carbon 5, ApplicationServices 5, Metal 2, Security 0. 183 exported functions: there is no `-fvisibility=hidden`, so internal helpers (`_GetMacKey`, `_createJavaScreen`, `_jStringToNSString`, `_getRam`, `_CVOutputCallback`, …) are exported as well | — | g0, g1, g2 and g3 said no binary exists; g4 used it. Spot checks agree with the claimed imports: `CGWindowListCreateImage`, the 8 `CVDisplayLink*`, `AXUIElementPostKeyboardEvent`, the `UTType*`/`kUTTag*`/`kUTType*` names, the 5 `NSAccessibility*` functions, `CFBundleGet*`, `NSSearchPathForDirectoriesInDomains`, `nw_path_monitor_create` and `objc_autoreleasePoolPush` are imported. `CFURLCreateBookmarkData`, `CGEventSourceFlagsState` and `GetDblTime` are not, as the auditors say (they are proposed or rejected alternatives) |

### R2. Where the story or an auditor is wrong

**Story.**

- **"Files with JNI: 32".** This holds only as "files that name `JNIEnv`". AC 1 forbids `<jni.h>` and `JNIEnv*` too,
  so its real target set is the 41 files that name either (row 7). The j-type respelling touches 54.
- **"127 `Call*Method` sites".** This is right as a raw grep, but only 125 compile (rows 5 and 5a). AC 4's "all 128
  upcall sites" must cover more than this list:
  - the 16 `NewObject` constructor upcalls;
  - the 26 `ClassForName` lookups;
  - the 8 extra expansions of the two `GlassViewDelegate.m` macros;
  - the `reportException` upcall behind 284 check sites and 102 asserts (row 6).

  The callback tables in the group sections already cover all of these.
- **"`MacNative` facade".** The precedents (`GtkGlassNative`, `WinGlassNative`) give `MacGlassNative`, as g0 says.
- **The note that "US-001 counted 67 … missed the `@Override native protected` declarations".** The house skill's
  own inventory regex still misses them: it returns 111 for `com.sun.glass.ui.mac`, not 155 (row 4). An inventory
  based on that skill command undercounts by 44.
- The story's 155 natives, 155 `JNIEXPORT` and per-class counts all agree.

**g0 (infrastructure).**

- §3: `staticScreen_getVideoRefreshPeriod` runs on the FX thread, not "any thread": `Screen.getVideoRefreshPeriod`
  calls `Application.checkEventThread()` (`Screen.java:50-52`).
- §3: `_getKeyCodeForChar` is called with no thread check (`Application.getKeyCodeForChar`,
  `Application.java:745-746`), not "FX/main".
- §2: `GetPrimitiveArrayCritical` 11 → 9 (`GlassAccessible.m:235` and `:314` are comments). `NewStringUTF` 27 → 26
  (`GlassWindow.m:72` is commented out).
- §2 and §11: 215 constants → 210, and KeyEvent 138 → 133 (row 10a).
- §2: the 2,122 JNI type tokens were not reproduced (row 11).
- "There is no built binary": the baseline build exists (row 12).

**g1 (application, timer, robot, cursor, pixels, gestures).**

- "No `target/`, no generated headers": both exist in the baseline. The `javac -h` claims hold against its headers.
- §4 "CoreVideo (re-exported by QuartzCore)": `libglass` loads CoreVideo directly, and the `CVDisplayLink*` imports
  resolve there.
- §10 item 4: `objc_autoreleasePoolPush/Pop` are already imported by the baseline. Clang lowers
  `GlassMTLOffscreen.m:150` and `GlassLayerMTL.m:115` (`@autoreleasepool`) to them.
- §2 "13 are `@Override native protected`": 11 are, and 2 have `@Override` on the preceding line. The count of 13
  overrides is right.

**g2 (window, view).**

- "No binary exists": the baseline build exists.
- §2 leaves out the 4 `Integer.<init>` `NewObject` sites in its own files (`GlassView3D.m:116`,
  `GlassViewCGL.m:98,162`, `GlassViewMTL.m:49`). g0 lists them, and `GmacViewCaps` removes them.

**g3 (pasteboard, clipboard, menus, dialogs, NSURL).**

- "No built binary exists": the baseline build exists.
- §2 "The skill's grep misses `native private …`": wrong. The skill's regex allows zero leading modifiers and matches
  `native private`. It misses the 44 same-line `@Override native` declarations, none of them in g3's scope.

**g4 (accessibility).**

- "30 files": 32 (`GlassAccessible.h/.m` plus 30 under `a11y/`), 2,701 lines.
- §3b "13 × `a11y/JFX*Accessibility.h`": 14.

### R3. Conflicting proposals and their resolution

The five ABI sketches overlap, because every group needs the shared pieces. The table below states each conflict and
the scribe's resolution. Where it says "decision", the auditors' evidence does not settle the point, and the slice
that first needs it decides.

| # | Item | Proposals | Resolution | Why |
|---|---|---|---|---|
| 1 | Header, export macro, ABI version | g0, g2: `glass_mac_api.h`, `GMAC_EXPORT`, `#define GLASS_MAC_ABI_VERSION 1`, `int32_t gmac_abi_version(void)`. g1: `GMAC_ABI_VERSION 1u`, `JFX_EXPORT`, `uint32_t`. g3, g4: `JFX_EXPORT`, `uint32_t` | g0/g2 | Matches `glass_gtk_api.h:137-155` (`GGTK_EXPORT`, `GLASS_GTK_ABI_VERSION 1`, `int32_t ggtk_abi_version(void)`) and the `int ABI_VERSION` constant of `GtkGlassNative.java:105` |
| 2 | Library init | g0: a separate, idempotent `gmac_init()` (`pthread_once`), called first by `MacGlassNative.<clinit>`. g1: `pthread_key_create` folded into `gmac_app_init` | g0. During the keep-both period, `JNI_OnLoad` calls the same once-routine | A second `pthread_key_create` would replace `GlassThreadDataKey` and orphan the pool data threads already hold under the first key. Every slice needs the key, not only MacApplication |
| 3 | The "Java attached" probe | g0: `gmac_main_java_attached()`, with Java binding `pthread_main_np`. g1, g2: `gmac_main_thread_detached()` = `!pthread_main_np() && !attached`. g3 needs the same condition | One export, `gmac_main_thread_detached()`, which Java evaluates before the downcall. The macro's outcome per site stays as it is: the exception is reported if a `GLASS_CHECK_EXCEPTION` later runs on the same C path, and thrown after the call otherwise (g0 §10, g2 §7, g3 R1). The flag behind it is g0's, set at `GlassApplication.m:562` and cleared at `:758`. `_getScreenCapture` (slice 4) is REPORT, then a normal result, not a throw: after the draw, its `ClassForName` of `Application` reports the pending exception at `GlassHelper.m:88`, and the capture returns its `Pixels`. Only its returns before that point leave the exception pending and throw it | It is the macro's exact condition in one downcall, and two groups chose it. g0's separate `pthread_main_np` bind (its WRAPPER #35) then has nothing left to do, and the behaviour is equal |
| 4 | Status codes | g0: `GMAC_OK 0`, `NO_DATA -1`, `UNSUPPORTED_OS -2`, `NOT_ATTACHED -3`, `DETACHED_OFF_MAIN -4`. g1: `UNSUPPORTED_OS -1`, with `invoke_*` returning `void` | g0's set, and `gmac_app_invoke_later`/`_and_wait` return a status | It is the superset. Java has to drop a registry entry when the C silently dropped the request (the JNI never made the global ref) |
| 5 | `GmacScreenInfo` | g0, g2: 88 bytes, one field per `Screen.<init>` argument. g1: 56 bytes, from which Java rebuilds the repeated platform fields and the 1.0 scales | 88 bytes. Name field 0 once (`screen` or `ns_screen`) | It is 1:1 with the constructor, so Java holds no rule about which arguments repeat. The same struct serves `notify_move_to_another_screen` (WU1) |
| 6 | `GmacAppCallbacks` | g0: 21 slots (168 B), one per Java target, `int32_t` status, no `void* user`. g1: 10 slots (80 B), `void* user`, mostly `void` returns, and one `notify_app_event(int32_t)` for the 12 lifecycle notifications | g0's conventions: no `void* user`, `int32_t` status. The scribe leans to g0's 21-slot shape. g1's coalesced event slot fits the same conventions and is behaviour-equal, and the layout test pins whichever lands | g2, g3, g4 and the GTK/Windows tables all use these conventions. One Java target per slot matches every other table proposed here |
| 7 | Preferences | g0: a fixed `GmacPreferences` (1,080 B: 44 colours by index plus 6 ints) and a separate alternating-colours array; the key names and order live in Java. g1: a list of tagged `GmacPref` entries (48 B each) with C-owned keys in insertion order | Decision for slice 1. The scribe leans to g1's list | g1 leaves the 51 key spellings, their order and the conditional entries (the control tint) in C, where the JNI has them, so Java needs no second key table beside `getPlatformKeys`. g0 is simpler to pin. Either way Java inserts into the `HashMap` in today's order and skips a key when `Color.rgb` throws |
| 8 | `GmacViewCaps` | g0: three `void*` and `use_mtl_for_sw`. g2: three `int64_t` and `mtl_for_sw`. Both are 40 bytes with the same offsets and defaults (vsync 1 when absent) | Either; choose once in slice 6. The scribe leans to `void*`, because the three members are object pointers | The layout is identical, so only the spelling differs |
| 9 | Constants (AC 9) | g0: `native-glass/mac/constants/` with hand-kept, `javac -h`-named headers and `MacConstantsHeaderTest` (the GTK precedent). g2: the constants restated as `GMAC_*` in `glass_mac_api.h`, with a `gmac_constant(i)` probe. g3: 19 hand-copied `GMAC_*` with `gmac_test_constant`. g4: a `GMAC_VARIANT_*` enum, and `@Native` dropped from `MacVariant`. g1: `@Native` added to the 6 `MacGestureSupport` constants | g0 | The C keeps its `com_sun_glass_*` spellings and its 60 import lines, so no use of a constant changes. The test reads the class files on any OS. The landing trap closes before the first native flips. No probe export and no `@Native` edit is needed: once `javac -h` no longer serves glass, `@Native` is inert. Only the 15 headers with live constants are copied; the 14 prototype-only imports go with their JNI bodies |
| 10 | Who frees blocks | g0, g1, g3: `gmac_free` for every block C allocates. g4: Java binds libSystem `malloc`/`free`, and the consumer frees | `gmac_free` for blocks C allocates. For the blocks Java hands to C to free (g4's `out_ids`, `out_chars`, variant arrays), bind libSystem `malloc` | macOS has one malloc heap, so both are correct. One rule keeps the four groups' ABIs uniform |
| 11 | `critical(true)` | g0: only on the ABI, `sizeof` and flag probes. g1: also on `gmac_image_create` for heap arrays. g2: leaves the pixel uploads, which hold a critical region across a GPU wait today, to a measurement. g3, g4: none | g0's rule as the default. `gmac_image_create` qualifies if wanted: short, no block, no upcall. The uploads copy into a confined arena (PB5, slice 6): both wait for the GPU, so `critical` is excluded. No measurement was taken; the owner measures the copy on the SW pipeline as a follow-up (g2 Risk 6) | These are the house rules |
| 12 | MacTimer | g1: delete `GlassTimer.m` (Java binds CoreVideo and libSystem's pthread) if the §10 experiment passes, else a 1-slot `GmacTimerCallbacks`. g0 assumed the table | g1's order: the experiment first, the table as the fallback. Either way `staticScreen_getVideoRefreshPeriod` moves with the display link | g0 §9 and g1 Risk 5 agree on the coupling |
| 13 | Commit structure | g0 step 5, g1 recommendation 5 and g3 ("their own commit") want deletions apart from the migration | The owner commits the story as one commit. The slices below are an order of work, and a class's WRAPPER/PURE deletions belong to its slice | This is the owner's standing rule |

### R4. Gaps and double claims

**Unclaimed (gaps).**

- **`JNIEXPORT`:** none. g1 to g4 together cover all 155.
- **Upcall sites:** none of the 126 live `Call*Method` lines is unclaimed: each line number maps to a row of a group's
  upcall table. The only unclaimed line is `GlassWindow.m:74`, inside the commented-out `Sys_out_sprintf`
  (`GlassWindow.m:55-76`).
  - That block also holds `JNIEnv`, `FindClass`, `GetStaticFieldID`, `GetStaticObjectField` and `NewStringUTF`
    text, and no group lists it.
  - It compiles to nothing. But the AC 1 grep for `JNIEnv`, and the skill's final
    `grep -rn 'jni.h\|JNIEnv\|\bnative\b'`, will hit it, so slice 11 deletes it.
- **Files:** every one of the 100 files is claimed. Eight are claimed only by g0: `common.h`, `GlassHelper.h`,
  `GlassKey.h`, `GlassMacros.m`, `GlassScreen.h`, `GlassStatics.h`, `PlatformSupport.h` and `ProcessInfo.h`. They
  leave JNI in slice 11.
- **`NewObject`:** g2 does not count the 4 `Integer.<init>` sites in its files. g0 does, so they are covered.

**Claimed twice.**

| Item | Claimed by | Resolution |
|---|---|---|
| `MacGestureSupport._initIDs` (`GlassGestureSupport.m:46`) | g1 (PURE, D4) and g2 (G0, JNI-only) | Both delete the file together with MacView's gesture table. Slice 6 owns it |
| `GlassApplication.m` (19 exports) and `GlassKey.m` (2) | g0 as infrastructure, g1 as owner | Same verdicts, different ABI sketches (§R3 items 1-7). Slice 1 owns them |
| 35 upcall lines: `GlassApplication.m` 20, `PlatformSupport.m` 9, `GlassMacros.h` 2, `GlassTimer.m` 2, `GlassScreen.m` 1, `GlassHelper.m` 1 | g0 and g1 | Same targets and exception sinks in both; only the slot shape differs (§R3 item 6) |
| 20 device-details `Map` reads (`GlassView3D.m` 6, `GlassViewCGL.m` 8, `GlassViewMTL.m` 6) and the 4 `Integer.<init>` keys | g0 and g2 | Both replace them with `GmacViewCaps` (§R3 item 8). Slice 6 owns them |
| `createJavaScreen` (`GlassScreen.m:54`) | g0, g1 (screen list) and g2 (WU1) | One `GmacScreenInfo` (§R3 item 5): slice 1 builds it, slice 5 reuses it |

Helpers that cross group lines, and the slice that first needs each:
- `Pixels.attachData` / `jPixelsAttachData`: set by g1's `MacPixels._initIDs`, used by `GlassCursor.m:151` (g1) and by
  `GlassMenu.m:279` and `GlassPasteboard.m:270` (g3). Needed first in slice 2.
- `jStringToNSString` (`GlassAccessible.m:262`, g4) also serves `_openURI` (g1). It moves in slice 0.
- `GetJavaKeyChars` (`GlassKey.m:442`, g0) feeds the view's key slot (g2). Slice 6.
- `[GlassWindow _resetGrab]` (g2's file) is called from `GlassMenu.m:191` (g3). Slice 5 then slice 9.
- `GlassDraggingSource.m:75` (g3's file) fires VU27 (g2). Slice 6 then slice 7.

## Proposed slice order

The order follows the story: MacApplication, then MacWindow, then MacView, then MacPasteboard/MacSystemClipboard,
then MacMenuDelegate, and MacAccessible last. The small classes sit where their dependencies allow. A slice is a unit
of work and review inside the story's single commit.

Until slice 11 removes the JNI, every slice keeps both paths: an upcall site dials its slot when the table is
installed, and otherwise takes the JNI path, as on Windows. A JNI branch whose only entry was a deleted `Java_*`
native is dead, and goes in that slice's cleanup instead (PB16). So `GlassTimer.m`'s JNI tick branches went in slice
3, and the window upcall sites' JNI branches in slice 5, not in slice 11.

Two rules hold for every aggregate:
- An error Java raises while it builds a result after a downcall, in practice only an `OutOfMemoryError`, reaches
  the caller as itself. Only the downcall sits inside a linkage catch such as `unexpected()` (PB22). g1 §6 (h)
  records the divergence and lists the sites of slices 1, 7, 8 and 10.
- Each struct with `gmac_sizeof_*` and `gmac_offsetof_*` probes is checked once at run time with
  `MacAppCallbacks.requireLayout`, before it first crosses the boundary (PB23): `GmacScreenInfo`, `GmacColor`
  and `GmacPref` in `MacAppCallbacks.checkLayouts`, `GmacViewCaps` in `MacViewCallbacks.install`, `GmacMenuImage` in
  `MacMenuCallbacks.install`, `GmacVariant` in `MacAccessibleCallbacks.install`, `GmacPasteboardRep` and
  `GmacPasteboardItem` in `MacPasteboardCalls`' static initializer, and `GmacFileFilter` in `MacDialogCalls`' static
  initializer (Slices 7 and 8 say why). The 8 callback tables (App, Timer, Window, View, Gesture, Dialog, Menu,
  Accessible) keep their size check at install.

After each slice:
- the agent runs `mvn -pl modules/javafx.graphics test`, first narrowed with `-Dtest='*Mac*'`;
- the owner runs `tests/system` and the manual checks listed for the slice. Agents run no robot or `tests/system`
  tests and launch no toolkit.

Production Java files stay at 900 lines or fewer (`wc -l`). `MacAccessible.java` already has 1,981 lines, a
pre-existing class like `WinAccessible.java` (2,445). It must not grow: its natives become calls into the aggregate
classes.

| Slice | Java natives flipped | C files | Callback table (slots) | Facade / aggregate classes | Needs |
|---|---|---|---|---|---|
| 0 Infrastructure | none | new `glass_mac_api.h/.m`; new `constants/` (15 headers); `GlassApplication.m` (`JNI_OnLoad` calls the shared once-routine; attached flag at :562/:758); the string helpers of `GlassHelper.m:336` and `GlassAccessible.m:262` move to the shared file; `native/mac.cmake` | — | `MacGlassNative`; tests `MacConstantsHeaderTest`, `MacGlassNativeTest` | — |
| 1 MacApplication | 19 of 21: all but `_initIDs` and `_initDelegate`, which finish in slice 11 | `GlassApplication.m/.h`, `GlassKey.m`, `GlassScreen.m/.h`, `PlatformSupport.m/.h`, `GlassHelper.m` | App (21) | `MacApplicationCalls`, `MacMainLoopCalls`, `MacScreenCalls`, `MacPreferenceCalls`, `MacKeyCalls`, `MacAppCallbacks` | 0 |
| 2 MacPixels + MacCursor | 3 + 6 | `GlassPixels.m`, `GlassCursor.m`; lazy `jPixelsAttachData` lookups in `GlassMenu.m:279` and `GlassPasteboard.m:270` | — | `MacCursorCalls` (including `gmac_image_create`) | 0 |
| 3 MacTimer | 8 | `GlassTimer.m/.h`, reduced to the plain-C shim: PB4 takes the fallback. `ProcessInfo.m` `getTimeMicroseconds` and the `GlassDisplayLink` global stay, and `GlassApplication.m:1183-1204` is already `gmac_screen_video_refresh_period`, kept native permanently (PB4, g1 §10). Deleting them for a Java timer is an owner follow-up after the timer experiment | Timer (1) | `MacTimerCalls` | 1 |
| 4 MacRobot | 12 | `GlassRobot.m` deleted; `gmac_mouse_location_flipped` and `gmac_pool_push`/`gmac_pool_pop` in `glass_mac_api.m`, `gmac_key_find_mac` in `GlassKey.m`; the `createPixels`/`getScaleFactor` ids out of `GlassApplication.m` `_initIDs` and `GlassStatics.h` | — | `MacRobotCalls` | 1 (`MacGlassNative`, `gmac_main_thread_detached`) |
| 5 MacWindow | 30 | `GlassWindow.m/.h`, `GlassWindow+Java.m/.h`, `GlassWindow+Overrides.m/.h` | Window (10) | `MacWindowCalls`, `MacWindowCallbacks` | 1 |
| 6 MacView + MacGestureSupport | 20 + 1 | `GlassView.m/.h`, `GlassView3D.m/.h`, `GlassViewDelegate.m/.h`, `GlassViewCGL.m/.h`, `GlassViewMTL.m/.h`, `GlassGestureSupport.m` (deleted), `GlassKey.m` `GetJavaKeyChars`; `jlong` → `int64_t` in `GlassOffscreen*`, `GlassCGL*`, `GlassMTL*`, `GlassLayer*` | View (15), Gesture (5) | `MacViewCalls`, `MacViewCallbacks` (plus `MacGestureCallbacks` if the budget needs it) | 1, 5 |
| 7 MacSystemClipboard + MacPasteboard | 3 + 13 | `GlassSystemClipboard.m`, `GlassPasteboard.m/.h`, `GlassDragSource.m/.h`, `GlassDraggingSource.m/.h` | — | `MacClipboardCalls`, `MacPasteboardCalls` | 2, 6 |
| 8 MacFileNSURL + MacCommonDialogs | 6 + 4 | `GlassDialogs.m/.h`, `GlassStatics.m` `initJavaIDs*` | Dialog (2) | `MacDialogCalls` | 1 |
| 9 MacMenuDelegate + MacMenuBarDelegate | 11 + 3 | `GlassMenu.m/.h` | Menu (5) | `MacMenuCalls`, `MacMenuCallbacks` | 2, 5 |
| 10 MacAccessible | 14 | `GlassAccessible.h/.m`, the 30 files of `a11y/` | Accessible (16) | `MacAccessibilityCalls`, `MacAccessibleCallbacks` | 1, 6 |
| 11 Sweep | 2 (`MacApplication._initIDs`, `_initDelegate`) | `GlassMacros.h/.m`, `GlassStatics.h/.m`, `GlassHelper.h/.m`, `common.h`, the 20 `<jni.h>` includers, the j-type respelling, the 14 prototype-only imports, `Sys_out_sprintf`; `native/mac.cmake` (`glass` drops `JNI`) | — | — | all |

The flipped natives add up to 19 + 9 + 8 + 12 + 30 + 21 + 16 + 10 + 14 + 14 + 2 = 155.

### Slice 0: infrastructure

- Settle §R3 items 1-4 and 9-11.
- `glass_mac_api.h/.m`:
  - `gmac_abi_version`;
  - `gmac_init`: `pthread_once` around `pthread_key_create(&GlassThreadDataKey)`, also called from `JNI_OnLoad`;
  - `gmac_free`;
  - `gmac_main_thread_detached`, over a C flag set at `GlassApplication.m:562` and cleared at `:758`;
  - the status codes, the `sizeof` probes and the shim-only test hooks.
- Put both UTF-16-to-`NSString` helpers in the shared file: OrEmpty (from `nsStringWithJavaString`) and OrNil (from
  `jStringToNSString`). Then `_openURI` in slice 1 does not depend on slice 10.
- `constants/`: the 15 headers holding the 210 live constants, plus `MacConstantsHeaderTest`, a copy of
  `GtkConstantsHeaderTest`. `glass` gets `INCLUDE_DIRS …/native-glass/mac/constants`.
  - While `HEADERS_DIR` stays on the include path, it is searched first (`mac.cmake:149-152`), so the generated
    header wins.
  - The hand-kept copy takes over only when `javac` stops emitting a header (`MacGestureSupport.h` in slice 6,
    `MacPasteboard.h` in slice 7, all in slice 11).
  - Confirmed for slice 6, after a clean build: `javac -h` no longer writes `MacGestureSupport.h` or `MacView.h`, so
    GlassView3D.m and GlassViewDelegate.m compile against the `constants/` copy of `MacGestureSupport.h`.
  - Until then the test, not the build, proves the copies.
- `mac.cmake`: add a `JNI` option to `add_jfx_library`. `glass` and `prismMTL` pass it; `iio` and `prismES2` lose the
  JDK directories, and this host's compile is the check. The option is shared with workstream A, and whichever lands
  first adds it.
- `MacGlassNative.<clinit>` order (g0 §6.4):
  1. `Application.loadNativeLibrary()`;
  2. the ABI check;
  3. `gmac_init`.

  Nothing else runs at this stage.
- Agent tests:
  - `MacConstantsHeaderTest`, on any OS;
  - `MacGlassNativeTest`: symbols, the ABI version, `sizeof`, and the detached probe from a non-main thread.

### Slice 1: MacApplication

- **Flip 19 natives:**
  - `_runLoop`, `_finishTerminating`, `_enterNestedEventLoopImpl` and `_leaveNestedEventLoopImpl` (D6: the return
    value moves to Java);
  - `_hide`, `_hideOtherApplications` and `_unhideAllApplications`;
  - `staticScreen_getVideoRefreshPeriod`, through `gmac_screen_video_refresh_period`, kept native permanently (PB4,
    g1 §10);
  - `staticScreen_getScreens`, `_invokeAndWait`, `_submitForLaterInvocation` and `_isTriggerReactivation`;
  - `_getDataDirectory`, through `gmac_app_data_directory`, kept native (PB2, g1 §10). D5's Java replacement is an
    owner follow-up after the owner's A/B;
  - `_getKeyCodeForChar`;
  - `_isKeyLocked`: D7's Java switch plus `gmac_current_modifier_flags`;
  - `_getApplicationClassName`, `_getPlatformPreferences`, `_getMacKey` and `_openURI`.
- **Keep the bootstrap as JNI until slice 11** (g1 Risk 1; Windows kept `InitExceptionReporting` the same way):
  - `_initIDs`: `jApplicationClass` and the `reportException` ID serve every remaining `GLASS_CHECK_EXCEPTION`, and
    the PlatformSupport class refs live here too.
  - The class-loader hand-off of `_initDelegate`: `glassClassLoader` serves every remaining `ClassForName`.

  The main-thread JNI attach in `runLoop:` also stays. The JNI fallbacks need `jEnv`, and the first FFM upcall then
  finds the thread already attached.
- **One JNI fallback is not self-sufficient after slice 1:** `GlassScreenDidChangeScreenParameters` (slot 19's
  fallback) reads `jScreenClass`. `createJavaScreens` set it before the screen-parameters observer was registered,
  and it is gone, because `staticScreen_getScreens` is flipped. After slice 1 only `MacWindow._initIDs`
  (`GlassWindow.m:667`) set it, and since slice 5 nothing does (Slice 5 below). With no App table installed, a
  screen change would call `GetStaticMethodID` on a NULL class. That is unreachable in production: `MacApplication`
  installs the table before `_initDelegate`.
- **The App table** is installed before the delegate is created. `GlassScreen.m:167`, the `PlatformSupport.m:295`
  chain and 19 `GlassApplication.m` sites dial it (the compiled-out `VERBOSE_LOAD` site is not dialled).
  - Runnables use a registry with a `NULL_RUNNABLE` sentinel (the `GtkMainLoopCalls` precedent).
  - `GmacScreenInfo` is the 88-byte form.
  - Preferences follow §R3 item 7.
- **Trap:** `FindClass` inside an FFM downcall (`GlassScreen.m:128` `Screen`, `PlatformSupport.m:91` `Color`) uses
  the wrong loader. It goes away once Java builds `Screen` and the map; until then, keep those paths out of FFM-called
  code.
- **Owner-run:**
  - `ExceptionHandlerTest`, `DefaultExceptionHandlerTest`, `NestedEventLoopTest`, `NestedEventLoopPlatformExitTest`,
    `ScreenTest`, `MultipleScreensTest`, `PlatformTest`, `KeyLockedTest`;
  - goldens of `getPlatformPreferences()` and `Screen.getScreens()` from the JNI build;
  - stderr at the 4 DESCRIBE sites (`GlassApplication.m:235, 242, 249, 844`).

### Slice 2: MacPixels and MacCursor

- **MacPixels:**
  - `_initIDs` becomes the Java constant 1 (`BYTE_BGRA_PRE`).
  - `_attachInt` and `_attachByte` become Java bounds checks plus `gmac_image_create`, writing the +1 `NSImage*`
    into the 8-byte slot that `attachData` receives. The C callers that are still JNI keep passing their stack slot.
  - Direct buffers go through `MemorySegment.ofBuffer(buf.duplicate().clear())`: the base address and capacity, as
    the JNI used them.
- **Before the flip,** `GlassMenu.m:279` and `GlassPasteboard.m:270` look up `Pixels.attachData` lazily (g1 Risk 3).
  `GlassCursor.m:151` flips in this slice.
- **MacCursor:** 6 `gmac_cursor_*` exports. Java builds the `Size`.
- **Owner-run:** `ImageCursorGetBestSizeTest`.

### Slice 3: MacTimer

- **Decision (PB4):** the owner-run experiment is not available, so this slice takes the "If it fails" branch.
  `GlassTimer.m` stays as the plain-C shim, and `gmac_screen_video_refresh_period` stays native permanently (g1
  §10). The "If it passes" branch is the owner's follow-up after the experiment, not work for this story.
- **Gate:** the g1 §10 experiment, owner-run: thread dumps after start, pause and resume; tick histograms; leaks
  over 10⁵ pulses.
- **If it passes:**
  - Java binds CoreVideo (looked up directly, §R2) and libSystem's pthread calls.
  - `GlassTimer.m/.h`, `getTimeMicroseconds`, `GlassDisplayLink` and the refresh-period export are deleted.
  - `staticScreen_getVideoRefreshPeriod` becomes the CoreVideo bind on the Java-owned link (g0 §9, g1 D1).
  - The autorelease pool comes from libobjc's `objc_autoreleasePoolPush/Pop` (already imported) or a two-line shim;
    the owner chooses.
- **If it fails:** `gmac_timer_*` exports plus the 1-slot `GmacTimerCallbacks`, whose wrapper restores the thread
  name.
- **Keep Quantum's ordering:** it reads `getVideoRefreshPeriod` after MacTimer's initialisation
  (`QuantumToolkit.java:359`, `:392`).
- **Owner-run:** pulse-timer jitter and `PlatformTest`.

### Slice 4: MacRobot

- **D2:** Java binds CoreGraphics, ApplicationServices and CF.
  - `CGEventCreateScrollWheelEvent` is variadic: bind it with `firstVariadicArg(4)`.
  - `CGWindowListCreateImage` is bound lazily.
  - Rounding uses libm `round`, not `Math.round`. On x86_64 the JNI narrowed with `cvttsd2si` (0x80000000 out of
    range), Java saturates: the `_getPixelColor` bytes differ only for `round(c * 255) >= 2^31` or `c = +Inf`, which
    an 8-bit capture matched to sRGB does not reach (g1 §5 MacRobot).
- **The slice's C ABI** (`glass_mac_api.h`): 4 exports and 2 test hooks.
  - `gmac_pool_push` and `gmac_pool_pop`: the `GLASS_POOL_ENTER`/`EXIT` of each robot body (g1 §10).
  - `gmac_key_find_mac`: `GetMacKey` with its found flag, for `_keyPress`/`_keyRelease`, in place of
    `gmac_key_java_to_mac` (g1 §10).
  - `gmac_mouse_location_flipped`: `getMousePosFlipped`, moved to `glass_mac_api.m`.
  - Shim-only test hooks: `gmac_test_sdk_constant` (the CoreGraphics and HIServices values and the type sizes that
    Java hard-codes) and `gmac_test_color_space_srgb_name` (`kCGColorSpaceSRGB`).
- **Agent tests:** `CGEvent`s built without posting, and the SDK-constant probe.
- **Owner-run:** `RobotTest` and the robot suites, which need the Accessibility and Screen Recording permissions; the
  CGEventTap and screen-capture A/B against JNI goldens. This Mac is not ready for robot runs.

### Slice 5: MacWindow

- **The ABI, as built:** 29 `gmac_window_*` functions, plus `gmac_window_install_callbacks` and
  `gmac_sizeof_window_callbacks` (g2 §7 "As built"). The first build had 28; `gmac_window_leave_native_full_screen`
  was split out of `_setView` in review round 1 (g2 §6 (c)). W27 `_isRightToLeftLayoutDirection` is a Java CF bind
  with no export: its headless A/B matched the `NSLocale` body (g2 §10), so the fallback export was not added.
- **The Window table** has 10 slots. Java registers the id before `gmac_window_create` and removes it only in
  `notify_dispose`.
- **`jScreenClass`:** nothing sets it any more. Its last writer, `MacWindow._initIDs` (`GlassWindow.m:667` at
  1a6788cc4d), is deleted, and so is WU1's `createJavaScreen`, which read it. Its only reader left is the slice-1 JNI
  fallback of `GlassScreenDidChangeScreenParameters` (`GlassScreen.m:161-165`), which runs only while no App table is
  installed. That fallback is unreachable: `MacAppCallbacks.install` runs before `_initDelegate` creates the
  delegate that observes screen changes (`MacApplication.java:135-136`). Slice 11 deletes the global
  (`GlassStatics.m:42`, `GlassStatics.h:40`) together with that fallback.
- **From slice 1** it uses the nested-loop slots (the full-screen exiting loop), `GmacScreenInfo` (WU1) and the
  attached-flag gating.
- **Handles:**
  - `_setView`: Java calls `gmac_window_leave_native_full_screen`, then reads `MacView.nativeHandle()`, then calls
    `gmac_window_set_view` (g2 §6 (c));
  - `_setMenubar` passes `MacMenuBarDelegate.ptr`, a raw pointer whether or not slice 9 has landed.
- **Recorded divergences:** g2 §6 (a)-(e). The main one: the unchecked WU1-WU10 are now reported immediately.
- **Owner-run:** the robot stage tests, `FullScreenExitTest`, `MultipleScreensTest` and `ScreenTest`.

### Slice 6: MacView and MacGestureSupport

- **The ABI:** 16 view exports. `_initIDs` goes, and `_getMultiClickMax{X,Y}_impl` and `_setParent` are deleted
  (exact; `_setParent` keeps the assertion's detached throw in Java, PB18). The View table has 15 slots and the
  Gesture table 5.
- **`GmacViewCaps`** is built in Java from the device-details map. That removes the 20 `Map` reads and the 4
  `Integer.<init>` keys.
- **`GlassGestureSupport.m`** is deleted; its header copy has been in `constants/` since slice 0. The stubs use
  `findStatic`, so `MacGestureSupport`'s class-init timing stays the same.
- **`constants/com_sun_glass_ui_View_Capability.h`** is deleted by the cleanup, a manager decision after slices 6
  and 7: nothing includes it since `GmacViewCaps`, as slice 2 deleted `Pixels_Format.h` once nothing included it.
  `MacConstantsHeaderTest` has one dynamic test per header, so it went from 15 tests to 14; none was dropped or
  disabled.
- **The render thread:** `_begin`, `_end` and `_getNativeFrameBuffer` stay there, and the pool still spans `_begin`
  to `_end`.
- **A detached main thread (PB18, decided after slices 6 and 7):** every view call whose native asserted reproduces
  its detached outcome in Java, as the window calls do. `_begin`, `_end`, `_getMultiClickTime_impl`, `_setParent`
  and the uploads that got past their pre-assertion checks throw; `_getNativeFrameBuffer` and the eight calls with a
  closing check report once (g2 §7 "Detached-thread check").
- **The registry:** `notify_dispose` removes the id of a view that was made. `MacViewCalls.create` also removes the
  id when `gmac_view_create` answers NULL or its downcall throws, because no `GlassViewDelegate` was made
  (GlassView3D.m:92-108), so no slot and no `notify_dispose` can come for it (g2 §8 Identity).
- **The uploads:** copy, per PB5. The byte and int arrays are copied whole into an `Arena.ofConfined()` for the
  call, and a direct buffer passes `MemorySegment.ofBuffer(buf.duplicate().clear())`. `critical(true)` is excluded
  because both paths wait for the GPU (GlassMTLOffscreen.m:176, GlassCGLOffscreen.m:231). No measurement was taken
  (g2 Risk 6). The copy stands under PB5, a manager decision after slices 6 and 7; measuring its cost on
  `-Dprism.order=sw` is an owner follow-up.
- **The key slot** carries `same_chars_as_previous`. `GetJavaKeyChars` becomes a UTF-16 out-buffer helper.
- **Recorded divergences:** g2 §6 (f)-(m). (l) is the view calls that crashed on a detached main thread and now have
  a defined outcome (`_close` of a view in a window, `_enterFullscreen`, `_exitFullscreen`, `_create`), reachable
  only with `glass.disableThreadChecks`. (m) is the multi-click maxima's race, which is not reproduced.
- **Owner-run:**
  - the robot scene tests;
  - manual IME, trackpad, DnD, ⌘-key, Caps Lock and full-screen checks;
  - every pipeline (`-Dprism.order=mtl|es2|sw`);
  - a follow-up: measuring the per-frame upload copy on `-Dprism.order=sw`.

  The Metal golden (AC 7) belongs to workstream A, but this path feeds it.

### Slice 7: MacSystemClipboard and MacPasteboard

- **`FormatEncoder`:** both WRAPPERs become Java UTType/CF binds, proven exact by a golden corpus.
  `_convertFileReferencePath` stays native as `gmac_clipboard_file_reference_path` (g3 §10).
- **MacPasteboard:** 12 `gmac_pasteboard_*` exports, and `_initIDs` goes. Java calls `Pixels.attachData` before the
  downcall (slice 2). `GlassDragSource` keeps its C state shared with `GlassViewDelegate`, with `jint` respelled as
  `int32_t`.
- **`MacPasteboard.h`** stops being generated here, and its copy is already in `constants/`.
- **As built** (g3 §7 and §8 "As built"):
  - the ABI: 12 `gmac_pasteboard_*` and `gmac_clipboard_file_reference_path`; `GmacPasteboardRep` (32 bytes) and
    `GmacPasteboardItem` (16 bytes), with the `GMAC_PASTEBOARD_REP_*` kinds and `GMAC_PASTEBOARD_ITEM_NULL`; 4
    probes; and 6 shim-only hooks, two of them the FormatEncoder oracles (the slice-5 W27 precedent), which stay in
    the shipping libglass on purpose (PB19, g3 §10);
  - no callback table. The sketch's 19 hand-copied `GMAC_*` constants and `gmac_test_constant` were not built (R3
    item 9);
  - the layout check (PB23): with no table to install, `MacPasteboardCalls`' static initializer checks
    `GmacPasteboardRep` and `GmacPasteboardItem` against their probes with `MacAppCallbacks.requireLayout`, before
    `putItems` first hands them to C;
  - `GlassDragSource.h` includes `<stdint.h>` in place of `<jni.h>`, and GlassDragSource takes `int32_t` where it
    took `jint`;
  - a detached main thread: each `gmac_pasteboard_*` call is REPORT once, then a normal result. In `putItems` the
    report comes before the first `Pixels.attachData`;
  - the FormatEncoder A/B (g3 §7): `MacClipboardNativeTest` matches the JNI golden
    `src/test/resources/test/com/sun/glass/ui/mac/mac-clipboard-golden-1a6788cc4d.txt` (102 values, macOS 27.0.1
    aarch64) and, on any Mac, the two oracle hooks; a probe program's 28/28 identical cases are supporting
    evidence;
  - the cleanup after the Java flip: the 16 JNI bodies (13 MacPasteboard, 3 MacSystemClipboard) and
    GlassPasteboard.m's JNI helpers are deleted, libglass's `_Java_` exports go from 56 to 40, and
    `constants/com_sun_glass_ui_mac_MacPasteboard.h` is down to 2 macros (`General`, `DragAndDrop`).
- **Owner-run:**
  - `MacPasteboardTest`, `ClipboardTest`, and the manual dnd and clipboard checks;
  - the first headless round trip on a user pasteboard, watched for the paste-privacy prompt.

### Slice 8: MacFileNSURL and MacCommonDialogs

- **MacFileNSURL:**
  - `_dispose`, `_start…` and `_stop…` become CF binds (exact).
  - The bookmark pair follows its §10 A/B: CF binds if exact, `gmac_url_*` otherwise.
  - Both `_initIDs` go.
- **MacCommonDialogs:**
  - `gmac_dialog_{open,save,folder}` and `gmac_url_copy_path`, with the Dialog table for `EventLoop` enter and
    leave.
  - Java marshals the filters first, and builds `FileChooserResult`, `MacFileNSURL` and `File` after the downcall.
  - The confined arena stays open across the nested loop.
- **As built** (g3 §6, §9, §10):
  - the ABI: 5 `gmac_url_*` (`start_accessing_security_scoped_resource`, `stop_accessing_security_scoped_resource`,
    `get_bookmark`, `create_from_bookmark`, `copy_path`), 3 `gmac_dialog_*` (`open`, `save`, `folder`) and
    `gmac_dialog_install_callbacks`; `GmacFileFilter` (32 bytes) and the 2-slot `GmacDialogCallbacks` (16 bytes),
    whose `event_loop_enter` and `event_loop_leave` take the Java-assigned `loop_id` and answer an `int32_t` status
    that the C does not read; 3 probes (`gmac_sizeof_file_filter`, `gmac_offsetof_file_filter`,
    `gmac_sizeof_dialog_callbacks`); and 2 shim-only hooks, `gmac_test_fire_dialog_callback` and
    `gmac_test_dialog_describe_filters`. libglass exports 159 `gmac_*`, the header's 159, 25 of them `gmac_test_*`;
  - the layout check (PB23): `GmacFileFilter` is checked against its probes with `MacAppCallbacks.requireLayout` in
    `MacDialogCalls`' static initializer, not in `MacDialogCallbacks.install`. `MacCommonDialogs.<clinit>` runs
    `install`, and `MacFileNSURL.createFromBookmark` and `createFromDocumentScopedBookmark` reach it through
    `isFileNSURLEnabled`. While `glass.macosx.enableFileNSURL` is unset they throw without using `MacDialogCalls`,
    so a check in `install` would initialize `MacDialogCalls` on that path;
  - `_dispose` has no export: Java calls `objc_release` (g3 §9). The security-scope pair and the bookmark pair stay
    native (g3 §10);
  - the dialog exports do not evaluate `GLASS_ASSERT_MAIN_JAVA_THREAD`: Java evaluates `gmac_main_thread_detached`
    first and reports (PB18, L33). Each export reports what a still-JNI upcall left pending before it returns, as the
    closing `GLASS_CHECK_EXCEPTION` did;
  - the loop id: Java registers the `EventLoop` it made under a nonzero id before the downcall, passes 0 when
    `new EventLoop()` threw, which leaves the panel unbegun as the JNI's return did, and unregisters it once the
    downcall returns;
  - every URL a dialog export or `gmac_url_create_from_bookmark` answers is retained for the caller, and
    `MacDialogCalls.toFile` keeps it in a `MacFileNSURL` or releases it for a `File`;
  - latent bugs L28-L33 were added to g3's list (§11);
  - the cleanup after the Java flip deletes:
    - the 10 JNI bodies (6 MacFileNSURL, 4 MacCommonDialogs);
    - GlassDialogs.m's JNI helpers `convertNSURLtoFile`, `copyJavaString`, `copyJavaFilters` and `freeFilters`;
    - DialogDispatcher's `exitModalWithEnv:result:`, its `jobject eventLoop` ivar and the JNI `EventLoop` path of
      `runModally`, which only the deleted JNI natives reached (PB16);
    - the `javaIDs` groups `EventLoop`, `List`, `ArrayList`, `MacFileNSURL`, `MacCommonDialogs`, `File`,
      `ExtensionFilter` and `FileChooserResult`, and `initJavaIDsList`, `initJavaIDsArrayList` and
      `initJavaIDsFile` (GlassStatics.h/.m).

    `GlassDialogs.h` drops `<jni.h>`, and GlassDialogs.m imports no generated header:
    `com_sun_glass_ui_mac_MacCommonDialogs.h` is no longer produced. libglass's `_Java_` exports go from 40 to 30,
    and GlassDialogs.m is 500 lines (613 at 1a6788cc4d). Without an installed `GmacDialogCallbacks` table the dialog
    exports leave the panel unbegun and answer as cancelled. Production never gets there, because MacCommonDialogs'
    static initializer installs the table.
- **Recorded divergences:** g3 §6 (a)-(f), (o) and (p). (o) is L31: `createFromBookmark` answers null on the
  `enableFileNSURL` race, where the JNI answered a plain `File` typed as a `MacFileNSURL`. (p) is L32: a null filter
  or a throwing `extensionsToArray()` gives a filter without file types, and the null filter an empty title, where
  the JNI crashed.
- **Owner-run:** the monkey `FileChooserPage`, and the sandbox bookmark check.

### Slice 9: MacMenuDelegate and MacMenuBarDelegate

- **The ABI:** 12 exports (3 menubar, 9 menu). `_setCallback` becomes a registry update, and `_initIDs` goes.
- **The Menu table** has 5 slots. `menu_disposed` is the only place an id leaves the registry.
- **Re-entrancy:** `validate` re-enters Java inside `gmac_menubar_insert`/`_remove` and `gmac_menu_remove`, so none
  of them may be `critical`.
- **As built** (g3 §6, §11):
  - the ABI: 3 `gmac_menubar_*` (`create`, `insert`, `remove`), 9 `gmac_menu_*` (`create`, `item_create`, `insert`,
    `remove`, `set_title`, `set_shortcut`, `set_image`, `set_enabled`, `set_checked`) and
    `gmac_menu_install_callbacks`. Also `GmacMenuImage` (24 bytes): the +1 `NSImage*` that `Pixels.attachData`
    wrote, then the `Pixels`' width, height, `scalex` and `scaley`; the export it is passed to consumes the image.
    Then 3 probes (`gmac_sizeof_menu_image`, `gmac_sizeof_menu_callbacks`, `gmac_offsetof_menu_image`), the slice's
    only critical binds, and 2 shim-only hooks, `gmac_test_fire_menu_callback` and `gmac_test_menu_key_equivalent`.
    Titles cross as UTF-16 units and a length. `GLASS_MAC_ABI_VERSION` stays 1. libglass exports 177 `gmac_*`, the
    header's 177, 27 of them `gmac_test_*`;
  - the table: the 5-slot `GmacMenuCallbacks` (40 bytes). Each slot is `int32_t (*)(int64_t menu_id)`: `action`,
    `validate`, `menu_opening`, `menu_closed` and `menu_disposed`. `MacMenuCallbacks.install` installs it from
    MacMenuDelegate's static initializer;
    - Java registers each delegate under a nonzero id before the create export, together with the item's callback,
      as strongly as the JNI's global references held them (L9);
    - `menu_disposed`, dialled from `-[GlassMenu dealloc]` inside the two remove exports, is the only slot that
      forgets an id. A create export that made no `GlassMenu` forgets it too;
    - `validate` also runs inside `gmac_menubar_insert`, `gmac_menubar_remove` and `gmac_menu_remove`;
    - a slot is dialled only while Java is attached to the main thread. A detached site prints `GET_MAIN_JENV`'s
      message and skips the slot (PB14). Every target reports what it throws (g3 §6 (g));
  - `_setCallback` has no export: `MacMenuCalls.setCallback` updates the registry (L35). `_initIDs` goes. Java
    reaches the protected `Menu.notifyMenuOpening` and `notifyMenuClosed` through `privateLookupIn` (g3 Risk R8),
    and calls `Pixels.attachData` through `MacCursorCalls.attachData` before the downcall (g3 §6 (i));
  - the exports do not evaluate `GLASS_ASSERT_MAIN_JAVA_THREAD`. Java evaluates `gmac_main_thread_detached` first,
    makes the downcall and then reports once, or reports what `attachData` threw in its place (PB18, g3 §6 (j)).
    Each export reports what a still-JNI upcall left pending before it returns, as the closing
    `GLASS_CHECK_EXCEPTION` did;
  - latent bugs L34 and L35 were added to g3's list (§11);
  - the cleanup after the Java flip deletes:
    - the 14 JNI bodies (3 MacMenuBarDelegate, 11 MacMenuDelegate), `_initIDs` and `_setCallback` among them;
    - GlassMenu.m's JNI helpers `JavaChars`, `getJavaChars`, `releaseJavaChars` and `attachJavaIcon`;
    - the 9 cached method and field IDs;
    - the JNI branches of `action:`, `menuWillOpen:`, `menuDidClose:`, `validateMenuItem:` and `dealloc` (PB16);
    - the `jDelegate` and `jCallback` ivars and `<jni.h>` in GlassMenu.h, and GlassMenu.m's imports of
      `com_sun_glass_ui_mac_MacMenuBarDelegate.h` and `com_sun_glass_ui_mac_MacMenuDelegate.h`;
    - beyond the planned list, the `jMenu` ivar, declared but unused since 1a6788cc4d, and GlassMenu.m's
      `GlassHelper.h` import, which only `_initIDs`' `ClassForName` used;
    - also beyond it, `GlassPixelsAttachData` (GlassPixels.m) and the `jPixelsAttachData` global
      (GlassStatics.h/.m). `attachJavaIcon` was their last caller, so this supersedes the statement in §7's
      "As built (slice 7)" that `GlassPixelsAttachData` stays for GlassMenu.m.
    - `+[GlassHelper nsStringWithJavaString:withEnv:]` (GlassHelper.h/.m), and the `glass_mac_internal.h` import
      GlassHelper.m took for it. Its callers at 1a6788cc4d were GlassWindow.m, GlassSystemClipboard.m,
      GlassDialogs.m and GlassMenu.m. Slices 5, 7 and 8 kept it for GlassMenu.m, and slice 9's wrappers then
      switched to `JavaChars`, which left it no caller. GlassHelper.m goes from 350 to 336 lines (352 at
      1a6788cc4d, +1/-17) and GlassHelper.h from 56 to 54 (+1/-3). The rest of GlassHelper stays for slice 11.

    Without an installed `GmacMenuCallbacks` table no menu slot runs and `validateMenuItem:` answers YES. Production
    never gets there, because MacMenuDelegate's static initializer installs the table before any `GlassMenu` exists.
    `GET_MAIN_JENV` stays at every site (PB14), and `GET_MAIN_JENV_NOWARN` stays in `dealloc` for its debug-build
    assert. libglass's `_Java_` exports go from 30 to 16 (MacAccessible 14, MacApplication 2), beside `JNI_OnLoad`
    and 177 `gmac_*`, the header's 177. GlassMenu.m goes from 891 to 537 lines (694 at 1a6788cc4d, so +197/-354
    against it) and GlassMenu.h from 70 to 64 (64 at 1a6788cc4d, +11/-11). GlassPixels.m loses 11 lines,
    GlassStatics.h 6 and GlassStatics.m 2.
- **Recorded divergences:** g3 §6 (g)-(n).
- **Owner-run:** `SystemMenuBarTest`, the robot `SystemMenuBar*` tests, `MenuDoubleShortcutTest`, and the manual
  systemmenu check.

### Slice 10: MacAccessible

- **The 7 parity-exact WRAPPERs** become Java AppKit/CF binds after their A/Bs. The dead `getString` stays as the
  oracle and then goes.
- **`NSAccessibilityUnignoredChildren`** stays native as `gmac_a11y_unignored_children`.
- **The other natives:**
  - `_createAccessiblePeer`, `_invalidateParent`, `idToMacVariant` and `GlassAccessibleToMacAccessible` become
    `gmac_a11y_*`;
  - `_initIDs` becomes the table install.
- **The Accessible table** has 16 slots, and `peer_released` runs on any thread.
- **Registry ids are per create,** because `isDisposed()` recreates a peer.
- **As built, native step** (g4 §7, §8 "As built", §9 "PB2 evidence", §10):
  - the ABI:
    - `GmacVariant` (120 bytes) and the 16-slot `GmacAccessibleCallbacks` (128 bytes);
    - 9 production exports: `gmac_a11y_create_peer`, `gmac_a11y_invalidate_parent`, `gmac_a11y_peer_id`,
      `gmac_a11y_id_to_variant`, `gmac_a11y_unignored_children`, `gmac_a11y_install_callbacks`,
      `gmac_sizeof_variant`, `gmac_sizeof_accessible_callbacks` and `gmac_offsetof_variant`;
    - 5 shim-only hooks: `gmac_test_fire_accessible_callback`, `gmac_test_a11y_variant_to_object` and the PB19
      oracle hooks `gmac_test_a11y_utf8_string`, `gmac_test_a11y_is_equal_to_string` and
      `gmac_test_a11y_enum_pointer`;
    - `GLASS_MAC_ABI_VERSION` stays 1. libglass exports 191 `gmac_*`, the header's 191, 32 of them `gmac_test_*`;
  - keep-both (PB6) at all 20 upcall sites and in both `dealloc`s: each dials the table when it is installed and
    otherwise takes the JNI path. The 14 JNI bodies stay for the cleanup after the Java flip;
  - the differences from the sketch:
    - offset 116 of `GmacVariant` is `utf8_length`;
    - every slot answers a status and writes out-parameters, and the C holds the defaults;
    - `gmac_a11y_id_to_variant` and `gmac_a11y_unignored_children` answer a status and free with `gmac_free`;
    - the plumbing takes the other slices' names;
    - the three oracle hooks take over from the JNI `getString` as the oracle;
  - kept native: `gmac_a11y_unignored_children`, which fails the gate (follow-up after the owner's A/B), and the 4
    OS-CALL exports;
  - PB2:
    - `NSAccessibilityPostNotification`, `NSAccessibilityUnignoredAncestor` and `_destroyAccessiblePeer` are exact
      by construction (disassembly);
    - `isEqualToString`, the two descriptions with `getString`, and `_initEnum`'s `AX*` names need the A/B against
      the oracle hooks;
  - GlassAccessible.m goes from 730 to 1,167 lines (+524/-87) and AccessibleBase.m from 349 to 396 (+60/-13).
    GlassAccessible.h goes from 89 to 112 (+25/-2) and AccessibleBase.h from 53 to 56 (+4/-1).
- **Cleanup after the Java flip** (g4 §8 "Keep-both (PB6)", §11). It deletes:
  - the 14 JNI bodies (GlassAccessible.m 10, AccessibleBase.m 4), `getString` among them once it had served as the
    oracle;
  - the JNI helpers `jArrayToNSArray`, `jStringToNSString` and `variantToID`;
  - the 38 header globals: the 15 `jAccessibility*` method IDs, `jAccessibleClass`, `jVariantClass`, `jVariantInit`,
    the 17 `jVariant*` field IDs and the 3 `jMapper` blocks (`jLongToID`, `jIntToNSNumber`, `jVariantToID`);
  - the JNI fallbacks of all 20 upcall sites and of both `dealloc`s, dead per PB16: `jAccessible` was set only by
    the deleted JNI `_createAccessiblePeer`;
  - the `jAccessible` ivars and `setJAccessible:`/`getJAccessible` on both peers;
  - `<jni.h>` in GlassAccessible.h and AccessibleBase.h, and the `com_sun_glass_ui_mac_MacAccessible.h` import in
    GlassAccessible.m, AccessibleBase.m and JFXStaticTextAccessibility.m;
  - `jlong_to_ptr` and `ptr_to_jlong` in common.h. Their last callers were the deleted bodies (GlassAccessible.m 8
    uses and AccessibleBase.m 5 at 1a6788cc4d; no other file used them once slice 10 began). common.h keeps only
    `#include <jni.h>`, which slice 11 drops.

  Without an installed table every site answers as while Java is detached, the slice-9 rule, written in
  GlassAccessible.h. Production never gets there, because MacAccessible installs the table before any peer exists.
  `GET_MAIN_JENV` stays at every slot, and `GET_MAIN_JENV_NOWARN` in both `dealloc`s (PB14). libglass now exports 2
  `_Java_` (MacApplication), `JNI_OnLoad` and 191 `gmac_*`, the header's 191: 55 exported names fewer (the 14
  `_Java_` and 41 JNI globals and helpers), none added. GlassAccessible.m goes from 1,167 to 609 lines (730 at
  1a6788cc4d, so +396/-517 against it), GlassAccessible.h from 112 to 55 (89, +23/-57), AccessibleBase.m from 396
  to 293 (349, +28/-84), AccessibleBase.h from 56 to 50 (53, +3/-6), JFXStaticTextAccessibility.m from 55 to 54
  (+1/-2) and common.h from 30 to 27 (+1/-4 against 1a6788cc4d; copyright now 2011, 2026).
- **Recorded divergences:** g4 §6 (a) and (b). (a) is formerly latent bug 17; (b) is the PB15 drop.
- **Owner-run:**
  - VoiceOver (AC 8), `tests/manual/a11y`, and the AX-tree dump;
  - in the module run, confirm that no Dock icon appears and focus does not change while `MacAccessibleNativeTest`
    runs. It loads libglass in the surefire JVM, makes no peer and starts no child JVM (g4 §11 "As built").

### Slice 11: sweep

- **Flip the bootstrap:**
  - `_initIDs` becomes `gmac_app_init`, which returns a status, and Java throws "Unsupported macOS version";
  - `_initDelegate` becomes `gmac_app_create_delegate`.
- **As built, native step:** `gmac_app_init` and `gmac_app_create_delegate` (`glass_mac_api.h:759`, :773), and the
  shim-only hook `gmac_test_inspect_and_release_app_delegate`. Recorded divergence: g1 §6 (o).
  - It also removed GlassApplication.m:27's `#import "com_sun_glass_ui_mac_MacApplication.h"`. Once Java deleted
    MacApplication's last natives, `javac -h` stopped writing that header, so a clean build failed there with "file
    not found"; the incremental build had passed only on a stale copy. The two dead `Java_*` bodies compile without
    the prototypes, since glass builds with `-Werror` but not `-Wmissing-prototypes`.
- **As built, part 2 (Java):** MacApplication's last two natives are downcalls, so no class of `com/sun/glass/ui/mac`
  declares a `native` method. `test.com.sun.glass.ui.mac.MacGlassNativeTest.noClassOfThePackageDeclaresANativeMethod`
  reads the package's compiled classes, nested and anonymous ones included, and finds none.
  - `_initIDs`: the static initializer calls `MacApplicationCalls.init` (`MacApplication.java:49`), where it called
    the JNI `_initIDs` (`MacApplication.java:50` at 1a6788cc4d). `init` calls `gmac_app_init`
    (`MacApplicationCalls.java:160-166`) and throws "Unsupported macOS version" when it answers
    `GMAC_ERR_UNSUPPORTED_OS` (:76-80). Recorded divergence: g1 §6 (p), which needs no acceptance.
  - `_initDelegate`: `runLoop` calls `MacMainLoopCalls.createDelegate` (`MacApplication.java:132`), where it called
    the JNI `_initDelegate` (:137 at 1a6788cc4d). `createDelegate` calls `gmac_app_create_delegate`
    (`MacMainLoopCalls.java:75-84`) with two flags, the taskbar flag and whether there is a launchable, each as 1 or
    0; the JNI took the class loader, the launchable and the taskbar flag. The launchable stays in Java, in the
    receiver that `MacAppCallbacks.install` (`MacApplication.java:131`) installs just before, and slot 1
    (`run_launchable`) runs it.
- **Delete the JNI:**
  - `JNI_OnLoad`, the JNI attach in `runLoop:`, `jVM`/`jEnv` and the ID globals, among them `jScreenClass`, which
    nothing sets since slice 5, with its only reader, the `GlassScreenDidChangeScreenParameters` fallback;
  - `ClassForName`, the `GLASS_CHECK_EXCEPTION*`, `GET_MAIN_JENV*` and `GLASS_ASSERT_MAIN_JAVA_THREAD` macros, and
    `PlatformSupport initIDs`;
  - the commented `Sys_out_sprintf`;
  - `<jni.h>` in its 13 remaining includers (20 at 1a6788cc4d): common.h, GlassHelper.h, GlassHostView.h,
    GlassKey.h, GlassMacros.h, GlassScreen.h, GlassStatics.h, GlassStatics.m, GlassView.h, GlassViewDelegate.h,
    GlassWindow+Java.h, GlassWindow+Overrides.h and PlatformSupport.h;
  - the 1 import left of g0 §11's 14 prototype-only headers: `com_sun_glass_ui_View.h` (GlassView.m:27; `javac -h`
    still writes it for View's `@Native` constants, none of which the mac C uses). The native step removed
    `com_sun_glass_ui_mac_MacApplication.h`'s, and the other 12 headers' imports went with their slices' JNI bodies;
  - then respell the j-types, and drop `JNI` from `glass`.
- **As built, part 3 (native):** `native-glass/mac` has no JNI left, and libglass exports no `_Java_` symbol and no
  `JNI_OnLoad`; its 193 `gmac_*` exports (32 of them `gmac_test_*`) are the declarations of `glass_mac_api.h`.
  - Deleted, as planned above: `JNI_OnLoad` and its `STATIC_BUILD` twin `JNI_OnLoad_glass`; the last two `Java_*`
    bodies (`MacApplication._initIDs`, `_initDelegate`) with `-takeJavaApplication:…` and the `createdByJni`,
    `jApplication` and `jLaunchable` ivars; every JNI fallback of the App sites; `runLoop:`'s attach, its "could not
    attach" branch and its `VERBOSE_LOAD` block, which used `jEnv`; `jVM`, `jEnv`, `javaIDs` and the other ID
    globals, `jScreenClass` and the `GlassScreenDidChangeScreenParameters` fallback among them; PlatformSupport's
    `+initIDs:`, its JNI preference path and its `env` and `application` ivars; `ClassForName`,
    `SetGlassClassLoader`, `ApplicationClass` and the 12 `Application…Method` getters of GlassHelper, which keeps only
    `InvokeSelectorIfAvailable:`; the `GLASS_CHECK_EXCEPTION*`, `GET_MAIN_JENV*`, `GLASS_ASSERT_MAIN_JAVA_THREAD` and
    `MAIN_JVM` macros; the commented `Sys_out_sprintf`; `<jni.h>` in its 13 includers and the
    `com_sun_glass_ui_View.h` import. The j-types are respelled as `int32_t`, `uint8_t` and the like.
  - Deleted with the last JNI site (PB15): `GlassDialSlotKeepingPending`, through which window slots 0, 1 and 3-8
    were dialled, and `GlassReportPendingException` with its 62 calls, which were the closing ones of the App,
    window, view, menu and dialog exports, the one before window slot 2 and the one in `GlassDialPeerReleased`; also
    the `GLASS_CHECK_EXCEPTION` before `notify_dispose`. There was no separate take or restore function. The
    shim-only hook `gmac_test_fire_window_callback_with_pending` and the three `MacWindowNativeTest` tests that used
    it went with them. This closes the divergences that existed only while a JNI site could leave an exception
    pending: g1 §6 (e), g2 §6 (e) and (g), g3 §6 (e) and (k), g4 §6 (b) and the report in g4 §6 (a).
  - Added: `GLASS_WARN_IF_MAIN_JAVA_DETACHED` (`GlassMacros.h:209-214`), which replaces `GET_MAIN_JENV` at its 66
    remaining sites. It prints the same `GLASS_CALLSTACK` text when the attached flag is clear. The flag is set just
    before slot 2 and cleared once `[NSApp run]` has returned (`GlassApplication.m:571`, :732), where the JNI attached
    the thread and cleared `jEnv`, and every `jEnv`/`env` NULL test now reads `GlassIsMainJavaAttached()`. The 6
    `GET_MAIN_JENV_NOWARN` sites keep only its `assert(pthread_main_np() == 1)`. `attachedAppCallbacks()`
    (`GlassApplication.m:156-160`) answers the App table only while the flag is set.
  - Recorded divergence (PB7, owner-run item 10): g1 §6 (d). On a detached main thread, App slots 1, 4-15, 17 and 18
    now print the message and are skipped (PB14). The JNI crashed on the NULL env after the print; from slice 1 until
    part 3, slots 1 and 4-15 ran Java and then crashed, and slots 17 and 18 ran Java. Slots 0 and 19 are still
    skipped silently while detached, slot 20 is still dialled either way, and slots 2, 3 and 16 run only while
    attached (`glass_mac_api.h:207-212`). Reachable only after `[NSApp run]` has returned.
  - Needs no acceptance: the detached message prints new line numbers at every site, as in g3 §6 (l).
- **As built, part 4 (CMake):** `mac.cmake`'s `add_jfx_library` has no `JNI` keyword: `cmake_parse_arguments` at
  `mac.cmake:133` takes only `OUTPUT_NAME`, `SOURCE_DIRS`, `INCLUDE_DIRS`, `COMPILE_OPTIONS` and `LINK_LIBS`, and a
  single `target_include_directories` of `SOURCE_DIRS` and `INCLUDE_DIRS` at `:151` is the whole include path.
  `glass` (`mac.cmake:166-175`) keeps `INCLUDE_DIRS` `native-glass/mac/constants` (`:169`), where the `javac -h`-named
  constant imports resolve and which `test.com.sun.glass.ui.mac.MacConstantsHeaderTest` checks against the Java
  classes.
  - Evidence: the generated compile flags of `glass`, `prismES2`, `prismMTL` and `iio` contain no JDK `include`,
    `include/darwin` or `HEADERS_DIR`. Supporting evidence from scratch builds: a fresh configure and build with
    `HEADERS_DIR` an empty directory and `JDK_HOME` a directory without `include/` builds all 4 dylibs and the
    metallib, with export names identical to the Maven build (the Proof's AC 9 build below); libglass built with the
    pre-edit `mac.cmake` (generated headers first on the include path) and with the new one differs only in
    `__FILE__` path literals, `LC_UUID` and the ad-hoc signature, so `constants/` compiles to the same code; no new
    warnings: the only 4 are prism_es2's baseline deprecations.
  - `CMakeLists.txt:59-60` (`HEADERS_DIR` required) and the graphics pom's `-h` stay for Windows glass.
  - With this, `glass` and `prismMTL` both meet AC 9 on macOS.
- **The first FFM upcall** now attaches the main thread (`set_event_thread`), at the point the JNI attach used to
  (g0 §6.2).
- **Proof:**
  - `grep -rnE 'JNIEXPORT|jni\.h|JNIEnv' native-glass/mac` comes back empty (AC 1);
  - the `native` grep of §R1 row 4 over `com/sun/glass/ui/mac` gives 0 (AC 2);
  - the AC 9 build passes with `HEADERS_DIR` pointed at an empty directory and a `JDK_HOME` without `include/` (an
    empty `HEADERS_DIR` value is fatal at `CMakeLists.txt:59-60`).
- **Optional, the owner's call:**
  - g0 §9's dead code: `NSScreen(FullscreenAdditions)`, `printLoadedLibraries`/`printLoadedFiles`/`getRam` and
    `jshareContextPtr`;
  - g3's `DumpPasteboard`.
  - No longer on the list: g0 §9's `VERBOSE_LOAD` and `JNI_OnLoad_glass`, which part 3 deleted because AC 1 required
    it (`JNI_OnLoad_glass` was a `JNICALL` entry point, and the `VERBOSE_LOAD` block called Java through `jEnv`), and
    g1's `DumpImage`, which went with `GlassRobot.m` in slice 4. The `VERBOSE_LOAD` block was the only caller of
    ProcessInfo.m's `printLoadedLibraries` and `printLoadedFiles`, so they have had no caller since part 3.
- **Then** the `ffm-reviewer` sign-off (AC 10) and the owner's gate (AC 6): module tests and `tests/system` against
  the "Before" row of [US-001b-macos-gate.md](US-001b-macos-gate.md).

## Owner-run items

Agents never run these: no `tests/system`, no `-DUSE_ROBOT`/`-DFULL_TEST`, nothing that starts the toolkit or
activates the app. The checklist that combines them with every other owner item of the story, both workstreams, is
"Owner-run, deferred" in [US-001b-macos-gate.md](US-001b-macos-gate.md#owner-run-deferred).

1. **Definition of Ready:** `tests/system`, including the robot suites, on the JNI baseline. The module "before" run
   exists in [US-001b-macos-gate.md](US-001b-macos-gate.md): javafx-graphics 26,238 run, 1 failure, 824 skipped; the
   failure comes from the clone's path, not from the code.
2. **Goldens from the JNI build with the toolkit running:** `getPlatformPreferences()`, `Screen.getScreens()`, and
   stderr at the 4 DESCRIBE sites.
3. **The timer experiment** (slice 3).
4. **Robot work:** the Accessibility and Screen Recording permissions, and the CGEventTap and screen-capture goldens
   (slice 4).
5. **First runs of any child JVM that loads `libglass`:** the `-XstartOnFirstThread` probes and the pasteboard round
   trip. Confirm that no Dock icon appears, focus does not change and no privacy prompt opens before agents rely on
   them. The RTL A/B with `-AppleLanguages` is no longer one: agents ran it headless under PB3/PB12, in the 19 child
   JVMs of `MacWindowNativeTest`, which load `libglass` and assert `NSApp` nil before their first call and after
   their last, and before that in probe programs (Foundation and CoreFoundation only) (g2 §10). It stays a watch
   item: the owner's module run starts those 19 children too. Nor is a11y peer creation one: no a11y test makes a peer
   or starts a child JVM, so slice 10's watch is on the surefire run of `MacAccessibleNativeTest` (slice 10
   "Owner-run").
6. **The per-slice `tests/system` and manual lists** above.
7. **VoiceOver (AC 8).**
8. **The sandbox bookmark check** (slice 8). No checked-in test runs the real `gmac_url_get_bookmark` and
   `gmac_url_create_from_bookmark`: they always make and resolve security-scoped bookmarks
   (`NSURLBookmarkCreationWithSecurityScope`, `NSURLBookmarkResolutionWithSecurityScope`; current
   `GlassDialogs.m:225`, :267), whose XPC traffic is a first run for the owner to watch. `MacDialogNativeTest` puts
   stand-ins where they would be.
9. **Accepting slice 9's divergences (PB7)**, g3 §6:
   - (g) what the menu upcalls throw is reported at once, where the JNI left it pending (Risk R5, L20);
   - (h) on a detached main thread, choosing an item without a callback prints the `GLASS_CALLSTACK` message, where
     the JNI printed nothing. Reachable only after `[NSApp run]` has returned;
   - (i) `Pixels.attachData` runs before the item is made, on the calling thread and outside the export's autorelease
     pool (Risk R6); what it throws is still reported after the export has run;
   - (j) the detached-main-thread crashes of `_createMenu`, `_createMenuItem`, `_setPixels` with a `Pixels`, and
     `_setCallback` have a defined outcome; `_setCallback` prints no message;
   - (n) `MenuItem.setCallback` on a delegate whose `ptr` is 0 changes nothing, where the JNI crashed on the NULL
     `GlassMenu` (L35).

   (k), (l) and (m) need no acceptance: (k) was transitional and went with the last JNI site in slice 11 part 3
   (PB15), (l) moves only the source locations the detached message prints, and (m) drops a conversion whose only use
   was a `LOG` compiled out unless `VERBOSE` is defined.
10. **Accepting slice 11's divergences (PB7)**, g1 §6:
    - (d) on a detached main thread, App slots 1, 4-15, 17 and 18 print the `GLASS_CALLSTACK` message and are
      skipped, where the JNI crashed on the NULL env after the same message (part 3, PB14). The code after each slot
      now runs. Reachable only after `[NSApp run]` has returned;
    - (o) slot 2's upcall attaches the main thread to Java in place of `runLoop:`'s `AttachCurrentThreadAsDaemon`,
      and a failed attach is fatal inside HotSpot's upcall stub, where the JNI logged it and skipped the run loop.

    The divergences part 3 closed need no acceptance: they existed only while a JNI site could leave an exception
    pending (g1 §6 (e), g2 §6 (e) and (g), g3 §6 (e) and (k), g4 §6 (b) and the report in g4 §6 (a)). Nor do the
    new line numbers the detached message prints, as slice 9's (l), or g1 §6 (p): `Color` and `Paint` are
    initialized at their first use, no longer inside `MacApplication`'s initialization (part 2).

## Files most relevant to the migrator

- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassApplication.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassMacros.h`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassStatics.h`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassViewDelegate.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassWindow.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/mac/GlassAccessible.m`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/native/mac.cmake`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/gtk/glass_gtk_api.h`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/native-glass/gtk/constants/`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/test/java/test/com/sun/glass/ui/gtk/GtkConstantsHeaderTest.java`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/glass/ui/gtk/GtkGlassNative.java`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/glass/ui/win/WinGlassNative.java`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/javafx/font/coretext/CFNative.java`
- `/Users/benesquivelmusic/Documents/SourceCode/jfx-ffm/modules/javafx.graphics/src/main/java/com/sun/javafx/font/JniStringCodec.java`
