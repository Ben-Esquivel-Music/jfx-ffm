# US-074 — Remove the last of JNI from the fork

**Status:** ✅ Done (2026-11-11, uncommitted on the local branch `US-074-remove-the-last-of-jni-from-the-fork`; PR
and CI pending; filed 2026-11-05 from a JNI audit of `aacabbced7`) · **Found:** 2026-11-05, read-only audit of the
tree · **Blocked by:** none · **Blocks:** none

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

## Resolution (2026-11-11, uncommitted)

Done and uncommitted on the local branch `US-074-remove-the-last-of-jni-from-the-fork`, off `0fd9cb0ef5`: 172 files
changed (+1,484 / −5,569; 16 deleted, 26 renamed) and 18 new files, not counting `backlog/`. The owner commits,
pushes and merges; the PR and CI are pending.

### What changed
1. **Windows glass.dll and `jni.h`.** `modules/javafx.graphics/src/main/native-glass/win/constants/` holds 10
   JNI-free headers named like the javac `-h` headers that 11 glass sources include
   (`com_sun_glass_events_{KeyEvent,MouseEvent,TouchEvent,ViewEvent,WindowEvent}.h`,
   `com_sun_glass_ui_{Clipboard,CommonDialogs_Type,Window,Window_Level}.h`, `com_sun_glass_ui_win_WinWindow.h`). They
   hold every one of the 257 macros javac wrote at `0fd9cb0ef5`, with javac's names, values, order and spelling (256
   `<n>L`, one long `-9223372036854775808i64`), and no `jni.h`, prototype or `extern "C"`. `native/win.cmake` lost
   the `JNI` keyword, its `JDK_HOME`/`HEADERS_DIR` include block and the comments on it; `add_jfx_library` takes
   `INCLUDE_DIRS`, and `glass` and `glass_test` pass the constants directory. `native/CMakeLists.txt` declares
   neither variable and requires only `GENSRC_DIR` and `BIN_DIR`. Comments fixed in `linux.cmake`, `mac.cmake`,
   `glass_win_api.cpp`, `glass_win_api.h` and the root `README.md`. New
   `test.com.sun.glass.ui.win.WinConstantsHeaderTest` (11 tests): `GtkConstantsHeaderTest`'s rules plus javac's
   Windows spelling of a long; 7 mutants each fail one test.
2. **`javac -h` and `@Native`.** `modules/javafx.graphics/pom.xml` lost the `-h` pair of the `jsl-codegen` javac,
   the `gensrc/headers` mkdir and `-DJDK_HOME`/`-DHEADERS_DIR` in `native-win`, `native-linux` and `native-mac`.
   `@Native` and its import left 38 files: 623 annotations (graphics 322 in 17 files, media 21 in 4, web 277 in 17,
   and 3 in commented-out code). The annotation has source retention, and no tool read it: `gen-wkj-constants.pl`
   reads every static final integral field, `HeaderGen` reads `MediaError.values()`. Media's own `HEADERS_DIR`, the
   headergen `jfxmedia_errors.h`, is not JNI and stays.
3. **Library loading.** `com.sun.glass.utils.NativeLibLoader` calls no `System.load*`. Every step opens a library
   with `SymbolLookup.libraryLookup(Path, Arena.global())` after the native-access check that `System.load` made
   first; on macOS a path that does not exist is handed to `dlopen` as a string, as `System.load` hands it there.
   Steps, order, locations, verbose strings and not-found texts are those of `0fd9cb0ef5`; the last step searches
   what `System.loadLibrary` searched (`sun.boot.library.path`, then `java.library.path`, the JDK's path rules, the
   macOS `.jnilib` alternative). New API: `libraryLookup(name)`, `loadedLibrariesLookup()` (every library loaded, in
   load order) and `loadLibraryFromSystemPaths(name)`. 32 `SymbolLookup.loaderLookup()` calls now use
   `loadedLibrariesLookup()` (graphics main 9, shims 12, tests 5, media 1, web 5), and `DumpRenderTree`'s
   `System.loadLibrary` became `loadLibraryFromSystemPaths`. New `NativeLibLoaderTest` (11 tests, one macOS-only)
   with `NativeLibLoaderChild` and `NativeLibLoaderShim`; graphics `src/test/addExports` adds `com.sun.glass.utils`.
4. **iOS and Android skins.** `modules/javafx.controls/src/ios/**` and `src/android/**` deleted: 4 Java files with
   the 8 `native` methods and 4 css files. No pom, build file or source names them.
5. **JNI generator and tools.** `WebCore/bindings/scripts/CodeGeneratorJava.pm` deleted: every `GENERATE_BINDINGS`
   passes `GENERATOR JS` or `DumpRenderTree`, and `SCRIPTS_BINDINGS` lists only `CodeGeneratorJS.pm`.
   `buildtools/ffm-web/{extract-jni.pl,dom-cpp-to-ffm.pl,verify-no-jni.pl}` and
   `buildtools/ffm-media/verify-no-jni.pl` deleted; nothing ran them. Kept because they regenerate checked-in
   files: `dom-java-to-ffm.pl` (the 103 facades, byte-identical, without `@Native`), `spec-to-header.pl`
   (`webkit_java_api_dom.h`, run by the wkjstub build), `dom-abi.tsv` and `gen-wkj-constants.pl`. The FFM docs that
   list a deleted tool say US-074 removed it, and the provenance comments that named `dom-cpp-to-ffm.pl` were
   updated (`spec-to-header.pl` and the header it regenerates, `gen-wkjstub.pl`, the wkjstub seed header).
6. **JNI in a test.** `JniStringOracleShim`, `JniStringOracleChild` and `JniStringOracleTest` deleted after their
   answers were captured into `jni-string-codec-golden-0fd9cb0ef5.txt`: 1,928 rows, the 8 measured inputs and the
   1,280 decode and 640 encode cases of the oracle's own seeded corpora, taken on `0fd9cb0ef5` through the oracle
   shim (0 mismatches with the codec; a rerun was byte-identical). New `JniStringCodecGoldenTest` (4 tests) checks
   the corpora, the measured rows, the decoder and the encoder against it; a decoder mutant and an encoder mutant
   each fail their test (291 of 1,288 and 389 of 640 rows differ).
7. **Names and comments.** Renamed:

| Old | New |
| --- | --- |
| `Source/WebCore/bridge/jni/` (and `jsc/`) | `Source/WebCore/bridge/java/` (and `jsc/`) |
| `bridge/jni/JNIUtility.{h,cpp}` | `bridge/java/JavaBridgeUtility.{h,cpp}` |
| `bridge/jni/jsc/JNIUtilityPrivate.{h,cpp}` | `bridge/java/jsc/JavaBridgeUtilityJSC.{h,cpp}` |
| `bridge/jni/JobjectWrapper.{h,cpp}`, class `JSC::Bindings::JobjectWrapper` | `bridge/java/JavaObjectWrapper.{h,cpp}`, `JavaObjectWrapper` |
| `WebCore::Java_Object_to_JSValue` | `WebCore::javaObjectToJSValue` |
| `JSC::Bindings::convertValueToJValue` | `convertValueToJavaValue` |
| `JNI_EXPAND(n)` (`RenderThemeJava.cpp`) | `RENDER_THEME_CONSTANT(n)` |
| `JNI_EXPAND_MEDIA(n)` (`RenderThemeJava.cpp`) | `RENDER_MEDIA_CONTROLS_CONSTANT(n)` |
| `WebPage::jobjectFromPage(Page*)` | `WebPage::handleFromPage(Page*)` |
| `jfxmedia/jni/Logger.{h,cpp}` | `jfxmedia/Utils/Logger.{h,cpp}` |
| `#include <jni/Logger.h>` (11 files) | `#include <Utils/Logger.h>` |
| `"${JFXMEDIA_SRC}/jni"` include directory (media `win.cmake`, `mac.cmake`) | removed (only `Logger.cpp` included `Logger.h`, from its own directory) |
| include guard `Jfxmedia_jni_MediaTypes_h` | `_MEDIA_TYPES_H_` |

   The `bridge` move also changes its include directory, `SourcesJava.txt` and `PRIVATE_FRAMEWORK_HEADERS`. Comments
   corrected: `WTF/wtf/PlatformJava.cmake`, `WebPageNative.java`, `MediaError.java` (the names `ERROR_BASE_JNI` and
   `ERROR_JNI_*` unchanged), `VideoFormat.java`, `win.cmake`, and the present-tense JNI claims in the media
   `native/CMakeLists.txt`, `webkit_java_api_page.h`, `webkit_java_api_bridge.h` and the FFM docs.
   `legal/webkit.md` names `bridge/java`.

### Acceptance criteria
1. ✅ No JDK include directory, no `jni.h`, no `javac -h`, and the graphics build configures without `JDK_HOME` or
   `HEADERS_DIR`. Windows, a private build with `JAVA_HOME` unset: no vcxproj names a JDK or `gensrc\headers`
   directory, MSBuild's `CL.read` logs of every compile of every target (glass 25, glass_test 25, prismD3D 15,
   prismES2 9, iio 42) read no JDK or `jni*.h` file, and `/showIncludes` of the 11 glass sources reaches `jni.h` in
   11 of 11 before and 0 after. Linux (WSL): the dependency files of all 73 objects name no JDK or `jni` header. A
   Maven build of the main tree from scratch (generated headers deleted first) configured with neither variable and
   created no headers directory. Media's CMake never took a JDK; WebKit's has no `find_package(JNI)` or JDK include.
   macOS: `mac.cmake` changed only in comments and never read either variable; CI builds it.
2. ✅ glass.dll exports the same 97 names, and its test build the same 123, with the same ordinal, hint and RVA as
   the build of `0fd9cb0ef5` (prism_d3d 59 and javafx_iio 9 too). `WinConstantsHeaderTest` passes 11 of 11.
3. ✅ No `System.load*` or `Runtime.load*` call in the tree (`CheckNoJni` rule R-LOAD); `NativeLibLoader` keeps its
   verbose string "System.loadLibrary(<name>) succeeded". A test DLL that exports `JNI_OnLoad`, which writes a marker
   file: loaded through this `NativeLibLoader` its function answers 42 and no marker is written; through
   `System.load` or the `NativeLibLoader` of `0fd9cb0ef5` the marker is written.
4. ✅ No `.java` file declares a `native` method or uses `@Native` (R-NATIVE, also on Java that generators emit);
   `CodeGeneratorJava.pm`, `extract-jni.pl` and `dom-cpp-to-ffm.pl` are deleted.
5. ✅ No test or shim calls a JNI function (R-JNI). `JniStringCodecGoldenTest` checks every input
   `JniStringOracleTest` covered.
6. ✅ The names and comments of item 7 are renamed or corrected. Runtime-visible strings are unchanged, except the
   loader's deviation (a) below, which AC 3 forces, and three Debug-only `LOG_ERROR` strings (see Notes).
7. ✅ `buildtools/CheckNoJni.java` (see "The no-JNI check") fails on each listed form, and its `--self-test` shows it
   on a planted instance of each, with commented-out controls that must pass.
8. Windows ✅; Linux, macOS and `build-webkit.yml` ⏳ pending CI after the owner's push.
   - Windows 10 x64, `mvn -B -ntp -fae clean verify -Djfx.web.skipFfmTests=false` (15 modules, what CI runs),
     natives built from source. Before, `0fd9cb0ef5`: 1,233 classes, 42,333 run, 1,048 skipped, 0 failures, 0
     errors. After: 1,235 classes, 42,355 run, 1,049 skipped, 0 failures, 0 errors, no `hs_err_pid`. The per-class
     difference is exactly −`JniStringOracleTest` 4, +`JniStringCodecGoldenTest` 4, +`WinConstantsHeaderTest` 11 and
     +`NativeLibLoaderTest` 11 with 1 skipped (macOS-only). Per class:
     [US-074-windows-per-class.tsv](US-074-windows-per-class.tsv). The run took the tree as of the first review's
     fixes; the sign-off review's later fixes changed only `NativeLibLoaderTest`'s child-JVM handling, comments, an
     unused import and `CheckNoJni`, and the targeted rerun of the affected classes was green with the same counts.
   - CI before run: 43114478515 (PR #56 head `abfb42b5f1`, whose tree is `0fd9cb0ef5`'s).
   - The local WSL before-and-after runs and the local WebKit export comparison were stopped by Claude Code's
     low-memory reaper, and the owner chose not to restart them; Linux, macOS and the WebKit build are left to CI.
   - The local WSL baseline run 1 failed two load-sensitive graphics tests on unchanged master code
     (`LinuxFontStressTest`'s native-memory bound, `JpegStreamCallbackTest`'s 20 s timeout).

### Constraints
- **Behaviour-neutral, exports unchanged.** Graphics, Windows: the same sources built at the same paths with
  `-Brepro`, once with the CMake files of `0fd9cb0ef5` (JDK includes and the generated headers) and once with these,
  give byte-identical glass.dll, test glass.dll, prism_d3d.dll, javafx_iio.dll and prism_es2.dll. Their objects are
  byte-identical except five glass sources, whose sections differ only in order (same sections, bytes, fix-ups and
  symbols). Graphics, Linux: `libglassgtk3.so`, `libjavafx_iio.so`, `libprism_es2.so` and `libprism_es2_monocle.so`
  byte-identical before and after (`nm -D` 4,455 / 9 / 95 / 92 symbols). Media: Windows `dumpbin /EXPORTS`
  identical with RVAs for jfxmedia (58), fxplugins (1), glib-lite and gstreamer-lite (by ordinal), and the DLLs
  differ only in 6 time-stamp bytes; Linux `nm -D` identical (jfxmedia 1,303, gstreamer-lite 3,232, fxplugins 21)
  with nothing changed in `.text`, `.rodata` or `.data`. WebKit: its after build and export comparison are pending
  (`build-webkit.yml`).
- **No compiled value changes.** The constants headers equal javac's `#define`s; `wkj_constants.h` values are
  unchanged (see Notes).
- **Windows glass constants keep their values,** checked by `WinConstantsHeaderTest` against the Java fields, as
  `GtkConstantsHeaderTest` and `MacConstantsHeaderTest` do.
- **Library loading** keeps its search order and locations (`NativeLibLoaderTest`), its `UnsatisfiedLinkError`
  texts (except deviation a), and the lifetime: `Arena.global()` is never closed. Symbol visibility: no name is
  exported by two of the fork's libraries (glass 97, javafx_iio 9, prism_d3d 59, fxplugins 1, jfxmedia 58, the
  cached jfxwebkit 15,249), so the load-order lookup finds what the loader lookup found. A JNI-era stand-in for
  jfxwebkit and jfxmedia is rejected by `WebKitNative` and `JfxMediaNative` with the same text before and after,
  and its `JNI_OnLoad` runs only before.
- **`JniStringCodec` parity** for every input the oracle covered: the golden test above.

### Accepted deviations of the library loader
- **a) A refused existing library loses the OS reason.** The text is now "`<path>`: the operating system could not
  load this library (-Xlog:os=info shows why)", with the `IllegalArgumentException` as cause. Forced by AC 3:
  `SymbolLookup.libraryLookup`, the JDK's only loader that runs no `JNI_OnLoad`, reports no reason, and rebuilding
  `os::dll_load`'s text is not feasible. Not-found texts are unchanged.
- **b) The library-path properties are read at search time,** not as the JVM-startup values `System.loadLibrary`
  used, which `javafx.graphics` cannot reach. Forced by AC 3; differs only when an app changes them at run time.
- **c) A custom `ClassLoader.findLibrary` is not consulted.** It is protected and `java.lang` is not open to
  `javafx.graphics`; the JDK's built-in loaders return null from it, so every standard launch behaves the same.
  Forced by AC 3, accepted.
- **d) The JNI-only "already loaded in another classloader" error is gone.** It guards JNI bindings, which no
  library has any more; loading is more permissive. Accepted.
- **e) The JDK's native-access warning names `MemorySegment::reinterpret` instead of `System::load`,** the method of
  the check that now runs first, and `DumpRenderTree`'s check runs against `javafx.graphics` instead of
  `javafx.web`. Forced by AC 3 (the warning text is the JDK's); for `DumpRenderTree` accepted, because
  `WebKitNative`'s own restricted calls from `javafx.web` run first, so a missing `javafx.web` grant still shows
  there.

### Notes
- Accepted and documented, not stories:
  - `wkj_constants.h` is not byte-reproducible from `gen-wkj-constants.pl`: pre-existing drift, 40 FFM-era
    constants were never added and 25 origin comments were already stale at `0fd9cb0ef5`; the `@Native` import
    removal shifted the other origin comments by one or two lines. No value differs and no build regenerates the
    header, so no compiled value changes; regenerating would add 40 unused macros to a header all of WebCore
    compiles.
  - The three `LOG_ERROR` strings in `JavaBridgeUtilityJSC.cpp` carry the new function names. `LOG_ERROR` is
    compiled out of Release, and in Debug it already prints the file and function name, which the rename changes.
- Reviews. Two review workflows; everything they confirmed was fixed in-story, and no new story was filed.
  - First (6 lenses: loader, graphics native, web and media, the check, tests, acceptance criteria; 24 agents): 13
    confirmed, 5 refuted, 6 nits. Major R3-1: the Windows after run had covered 6 of the 8 modules with tests, fixed
    by the 15-module run above. Minor: LOAD-1, the native-access check now runs before any file search, with
    deny-mode tests; LOAD-3, macOS hands an absent path to `dlopen`; NB-1 and acs F5, `README.md`; WM-1, the
    bridge header's comment; F1-F5 and acs F1/F2, `CheckNoJni` gaps (`javac -h` forms, `$ENV{JAVA_HOME}` and
    `${{ env.JAVA_HOME }}`, `Runtime` loads through a variable or a static import, lexer desyncs, Java emitted by
    generators). Nits fixed: LOAD-5 (the fork's skills record `NativeLibLoader` as the one restricted-call
    exception), WM-2, F6-F8. Nit acs F6 left: the two lines over 120 columns in the generated
    `CharacterDataImpl.java` were longer before. Refuted: LOAD-2, LOAD-4, R3-2, and acs F3/F4 (pending gates).
  - Sign-off (7 agents): 4 confirmed (minor), 0 refuted, 7 nits, all fixed. CNJ-R2-1 to -3, `CheckNoJni` misses (a
    comment kept by the fail-safe, generated `native` declarations, YAML heredocs); US074-S2-1, the media
    `FFM-ABI-CONTRACT.md` §6. Nits: `NativeLibLoaderTest`'s child-JVM timeout and kill and its output encoding,
    CNJ-R2-4/5 and S2-4, an unused import, comments that still named the old source of the loader's exception.
- The no-JNI check. `buildtools/CheckNoJni.java`, a single-file JDK program (`java buildtools/CheckNoJni.java .`),
  reads every tracked and untracked (not ignored) text file, on any host, and strips comments but not strings.
  Rules: R-NATIVE (`native` methods, `@Native`), R-EXPORT (`Java_*`, `JNI_OnLoad`), R-JNI (JNI types and calls),
  R-INCLUDE (`jni.h` and the JDK headers that include it), R-JDKINC, R-JAVAC-H and R-LOAD.
  `buildtools/CheckNoJni.allowlist` (43 entries, 58 hits) covers only the Out-of-scope items, and a stale entry
  fails the check. `--self-test` plants every rule with commented-out controls: 133 of 133 cases pass. On
  `0fd9cb0ef5` it finds every item of this story (939 hits). CI runs the self-test and the scan in the new job
  `no_jni_check` of `.github/workflows/submit.yml`; the scan reads 28,090 files in about 5 s (7 s wall).
- Still to do, by the owner: commit, push and open the PR; CI on all platforms (`submit.yml`, `no_jni_check`
  included) and `build-webkit.yml` on all three; the `tests/system` robot comparison; then fill in the PR number,
  the CI runs and the Linux and macOS per-class records here and in `backlog/README.md`.
