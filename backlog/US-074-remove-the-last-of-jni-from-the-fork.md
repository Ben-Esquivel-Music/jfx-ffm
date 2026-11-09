# US-074 — Remove the last of JNI from the fork

**Status:** 📋 Ready (filed 2026-11-05 from a JNI audit of `aacabbced7`) · **Found:** 2026-11-05, read-only audit
of the tree · **Blocked by:** none · **Blocks:** none

## Story
As the maintainer of the fork,
I want no JNI left in its code, its build or its tests,
so that the epic's goal holds for the whole tree and any JNI that comes back fails the build.

## Problem
No shipped code uses JNI. No compiled class declares a `native` method, and no library the fork builds defines
`Java_*` or `JNI_OnLoad` or calls the JNI API. This was checked with `nm` on the macOS libraries and the prebuilt
`libjfxwebkit.dylib`, and by reading the CMake files and sources for Windows and Linux. What is left:

1. **Windows glass.dll compiles against `jni.h`.** `native/win.cmake:214` gives `glass` the `JNI` flag (in
   `GLASS_LIBRARY_ARGS`, which the test build `glass_test` shares). That flag puts `${JDK_HOME}/include`,
   `include/win32` and `HEADERS_DIR` on its include path (`win.cmake:171-174`).
   11 files of `native-glass/win` include 10 `javac -h` headers, for example `com_sun_glass_events_KeyEvent.h`, and
   use only their `#define` constants. Each of those headers begins `#include <jni.h>`. GTK and mac glass include the
   same header names from JNI-free copies in `native-glass/{gtk,mac}/constants`.
2. **The build still generates JNI headers.** `modules/javafx.graphics/pom.xml:218` runs `javac -h` for the
   `@Native` constants of javafx.graphics (322 in 17 files). `native/CMakeLists.txt:59` stops the configure step
   on every platform unless `HEADERS_DIR` is set. The Linux and mac profiles pass `JDK_HOME` and `HEADERS_DIR`
   (`pom.xml:779-781` and `841-843`), but nothing reads them. javafx.media (21 in 4 files) and javafx.web
   (277 in 17 files) carry `@Native` constants with no `javac -h` behind them.
3. **Every native library is loaded by the JDK's JNI library loader.** `NativeLibLoader` calls `System.load` at
   lines 147, 192 and 402 and `System.loadLibrary` at 159. It loads glass, prism, iio, media and WebKit, and
   `DumpRenderTree.java:208` calls `System.loadLibrary("DumpRenderTreeJava")`. 18 facades and shims then find their
   symbols with `SymbolLookup.loaderLookup()`. That loader runs any `JNI_OnLoad` a library exports, so a JNI-era
   jfxwebkit's `JNI_OnLoad` runs before `WebKitNative.checkAbiVersion` rejects the library.
4. **Native declarations that nothing builds.** `TextFieldSkinIos`, `TextAreaSkinIos`, `TextFieldSkinAndroid` and
   `TextAreaSkinAndroid` (`modules/javafx.controls/src/{ios,android}`) declare 8 `native` methods. No pom compiles
   those source sets; only the Gradle build did, and it went in `01f190af72`. The iOS native side went in
   `26ce75d02f`.
5. **A JNI generator that nothing runs.** `WebCore/bindings/scripts/CodeGeneratorJava.pm` (from line 968) emits
   `JNIEXPORT` `Java_com_sun_webkit_dom_*` C++ and `native` Java. No `GENERATE_BINDINGS` call selects it. The
   `verify-no-jni.pl` checks of `buildtools/ffm-web` and `buildtools/ffm-media` pass at HEAD, because they strip
   string literals before matching. No build or CI step runs them.
6. **A test calls JNI.** `JniStringOracleShim` (javafx.graphics shims) finds `JNI_GetCreatedJavaVMs` in libjvm and
   calls `GetEnv` and `JNIEnv` function-table slots through FFM. `JniStringOracleTest` uses it as the reference for
   `JniStringCodec`'s modified UTF-8 conversions.
7. **Names and comments that claim JNI where there is none.**
   - Names: `jfxmedia/jni/`, which holds the pure C++ `CLogger`, and its include paths; the media include guard
     `Jfxmedia_jni_MediaTypes_h`; `WebCore/bridge/jni/` with `JNIUtility*`, `JobjectWrapper`,
     `Java_Object_to_JSValue` and `convertValueToJValue`; `JNI_EXPAND` and `JNI_EXPAND_MEDIA`
     (`RenderThemeJava.cpp:63`); `WebPage::jobjectFromPage`.
   - Comments that say JNI remains: `WTF/wtf/PlatformJava.cmake:7-9`, `WebPageNative.java:67`, `MediaError.java:45`
     and `VideoFormat.java:74`. `win.cmake:105-106` stays true only until item 1 is fixed.

## Constraints
- The change is behaviour-neutral. No library's exports change, no value compiled into the C changes, and no
  runtime-visible string changes.
- The constants that Windows glass compiles in keep their values. A test checks them against the Java classes, as
  `GtkConstantsHeaderTest` and `MacConstantsHeaderTest` do for GTK and mac.
- Library loading keeps its search order and locations, its `UnsatisfiedLinkError` on failure, the symbol
  visibility between the fork's libraries, and their lifetime (loaded until the JVM exits). `WebKitNative` and
  `JfxMediaNative` still reject a JNI-era library with the same error.
- `JniStringCodec` stays proven equal to HotSpot's string conversions for every input the oracle covers today.

## Out of scope
- JNI inside the JDK and SWT: javafx.swing's `jdk.swing.interop`, printing through `java.awt.print`
  (`J2DPrinterJob`), WebKit's `Toolkit.beep()`, and javafx.swt's reflection into SWT.
- `MediaError`'s `ERROR_BASE_JNI` and `ERROR_JNI_*` names and the `MediaErrors*.properties` keys that match them.
  They are runtime-visible: `MediaError.description()` returns the constant's name, because its
  `ResourceBundle.getBundle("MediaErrors")` finds no bundle. The same holds for the "NewObjectArray failed" message
  of `GlassApplication.m:468`.
- `JniStringCodec`'s name (it does reproduce JNI's conversions) and constant names in javac's spelling, such as
  `com_sun_glass_events_KeyEvent_PRESS` and `wkj_constants.h`. They name Java fields, not JNI.
- Comments that record what the JNI code did, and test code that reads JNI-era builds or guards against `native`
  methods: the golden capture paths of `MTLGoldens`, `MacFontGoldens` and `LinuxFontGoldens`, and the `jni*`
  helpers of the glass shims.

## Acceptance criteria
1. No native target on any platform has a JDK include directory on its include path or reaches `jni.h` or
   `jni_md.h`. No build step runs `javac -h`. The graphics native build configures without `JDK_HOME` or
   `HEADERS_DIR`.
2. glass.dll's `dumpbin -EXPORTS` list is the same before and after, and the Windows constants test passes.
3. No fork code calls `System.load`, `System.loadLibrary` or `Runtime.load*`. No library's `JNI_OnLoad` runs when
   the fork loads it.
4. No `.java` file in the tree declares a `native` method or uses `java.lang.annotation.Native`, and no file in the
   tree generates JNI code.
5. No test or shim calls a JNI function. `JniStringCodec`'s tests still prove its parity for every input
   `JniStringOracleTest` covers today.
6. The names and comments of item 7 are gone or corrected, and no runtime-visible string changes.
7. A check that the build or CI runs fails when any of these comes back: a `native` method or `@Native`; a
   `Java_*` or `JNI_OnLoad` export; a JNI type or call outside comments, including inside string literals of
   generators; an include of `jni.h`; a JDK include directory; `javac -h`; or `System.load*`. It covers every
   platform's sources from any host, and it is shown to fail on a planted instance of each.
8. The module tests pass on Windows, Linux and macOS with the counts of the "Before" run plus the new tests. If the
   WebKit sources change, the WebKit build of `.github/workflows/build-webkit.yml` passes on all three platforms.

## Definition of Done
Merged and verified on Windows, Linux and macOS. The owner runs the `tests/system` robot comparison.
`backlog/README.md` is updated.

## Notes
- Evidence: a read-only audit of `aacabbced7` on 2026-11-05. Four area audits (Java, graphics, media, web) were
  each checked by an adversarial verifier, and a completeness pass followed. Only the macOS binaries were inspected;
  Windows and Linux were read, not built.
