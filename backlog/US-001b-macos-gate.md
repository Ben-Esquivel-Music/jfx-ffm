# US-001b — macOS gate (AC 6)

Host: Mac mini, macOS 27.0.1 (26A434) arm64, 12 CPUs, Oracle OpenJDK 27.0.1 (`openjdk version "27.0.1.0.2"
2026-10-21`, build 27.0.1.0.2+1-2), Apache Maven 3.10.0, Xcode 27.1 (27A9275) with AppleClang 21.0.0.21000334,
CMake 4.4.4.

| Run | Tree | Time (EST) |
| --- | --- | --- |
| Before | `master` at `1a6788cc4d` | 2026-11-03, 16:24:27 to 16:26:01 (Maven total 01:33 min) |
| After, workstream A | the US-001b working tree on `1a6788cc4d`: `prism_mtl` on FFM, Glass mac still JNI, before the round-1 review fixes | 2026-11-03, about 20:01:51 to 20:03:07 (Maven total 01:16 min) |
| After, workstream B | the US-001b working tree on `1a6788cc4d`: `prism_mtl` and Glass mac on FFM, before the key-chars fix of the PR 48 review | 2026-11-05, 01:40:37 to 01:41:57 (Maven total 01:19 min) |
| After the key-chars fix | workstream B's tree plus the key-chars fix, before three comment-only test edits | 2026-11-05, 17:19:23 to 17:20:43 (Maven total 01:19 min) |
| After the key-chars fix (final) | the final US-001b working tree: that tree plus the three comment edits | 2026-11-05, 17:49:04 to 17:50:23 (Maven total 01:19 min) |

The workstream A run covers `prism_mtl` only. Workstream B repeated the after run with both libraries on FFM; see
[After run (workstream B)](#after-run-workstream-b). The PR 48 review's key-chars fix then changed Glass mac. The run
on that tree was repeated after three test comments changed, and the repeat is the story's final run; see
[After the key-chars fix (final)](#after-the-key-chars-fix-final).

The before run used a plain clone of the repository, detached at `1a6788cc4d`, in a session scratch directory under
`/private/tmp` that is gone now. Its per-class results are checked in as
[US-001b-macos-before-per-class.tsv](US-001b-macos-before-per-class.tsv). Maven used a private local repository, so
`~/.m2` was not touched. No `-DFULL_TEST` and no `-DUSE_ROBOT`, so nothing under `tests/system` ran. From the
clone's root:

```
mvn -B -ntp -Dmaven.test.failure.ignore=true -Dmaven.repo.local=<scratchpad>/m2-baseline install -pl modules/javafx.graphics -am
find modules -name 'hs_err_pid*' -type f
nm -gU modules/javafx.graphics/target/native/bin/<lib> | grep -c ' T _Java_'
```

The build succeeded. It built the JNI natives `libglass.dylib` (155 `Java_` exports, plus `JNI_OnLoad`) and
`libprism_mtl.dylib` (59), the same numbers as the story's `JNIEXPORT` counts, plus `libprism_es2.dylib` (0),
`libjavafx_iio.dylib` (0) and `jfxshaders.metallib`, all arm64.

The after run used the repository's own working tree, not a clone, with the same private local repository. Its
Maven log runs `clean` and then each module up to surefire, with no `jar` or `install` step. The reactor holds only
`openjfx`, `javafx-jslc`, `javafx-base` and `javafx-graphics`, so nothing under `tests/system` ran:

```
mvn -B -ntp -Dmaven.repo.local=<scratchpad>/m2-baseline -pl modules/javafx.graphics -am clean test
```

The after build succeeded. `libprism_mtl.dylib` has 0 `Java_` exports and 53 `mtl_*` exports (`grep -c ' T _mtl_'`),
the 53 of `prism_mtl_api.h`. `libglass.dylib` still has its 155 `Java_` exports, which workstream B removes. All
the libraries are arm64.

## Module run (run / failures / errors / skipped)

| Module | Before | After, workstream A | After, workstream B | After the key-chars fix (final) |
| --- | --- | --- | --- | --- |
| `javafx-jslc` | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 | 159 / 0 / 0 / 0 |
| `javafx-base` | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 | 5,510 / 0 / 0 / 22 |
| `javafx-graphics` | 26,238 / 1 / 0 / 824 | 26,283 / 0 / 0 / 824 | 26,627 / 0 / 0 / 824 | 26,636 / 0 / 0 / 824 |

The one failure, `test.com.sun.javafx.font.MacFontProcessGoldenTest.processStateMatchesTheJniGolden`:

```
1 difference(s) against the golden captured from the JNI build:
  proc.p4.embedded.emb.getFontData.registered: golden=6|Ahem|Ahem|${TMP}/+JXF<n>.tmp;Ahem|Ahem|${WORK}/Ahem.ttf;FontWithFeaturesOTF|FontWithFeaturesOTF|${WORK}/FontWithFeatures.otf;FontWithFeaturesTTF|FontWithFeaturesTTF|${WORK}/FontWithFeatures.ttf;WebKit WeightWatcher Bold|WebKit WeightWatcher|${WORK}/WeightWatcher.ttc;WebKit WeightWatcher Regular|WebKit WeightWatcher|${WORK}/WeightWatcher.ttc now=0|
```

This comes from where the clone is, not from the code. `MacFontProcessChild.putRegisteredTriples` keeps the
font-finder entries whose path contains the scenario directory as `/private/tmp/…`. CoreText reports a font
registered from `/private/tmp/…` as `/tmp/…`, with `/private` stripped. A standalone CoreText probe on this host
showed this. So no entry matches, while `getFontData.length` and every other key equal the golden. US-003 recorded
its macOS font gate (`Mac*Test`, this class included) with 0 failures on this host. An after run from a clone under
`/private/tmp` is expected to show the same failure. The after run, from the repository, passes it (2 / 0 / 0 / 0).

The sorted per-class list (913 test classes: 22 jslc, 290 base, 601 graphics) is
[US-001b-macos-before-per-class.tsv](US-001b-macos-before-per-class.tsv). Like US-071's, it is taken from
surefire's per-class lines in the Maven log, so the outer class of a `@Nested` group shows 0, with the tests counted
on its nested classes. Summed over the `TEST-*.xml` test cases, each module's totals equal the table above.

The after list, made the same way, has 919 classes. `diff` against the before list shows 7 classes, all expected:

| Class | Before | After, workstream A |
| --- | --- | --- |
| `test.com.sun.javafx.font.MacFontProcessGoldenTest` | 2 / 1 / 0 / 0 | 2 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLGoldenFormatTest` | — | 11 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLGoldenRenderTest` | — | 4 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLNativeTest` | — | 18 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLObjcReleaseTest` | — | 6 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLScratchTest` | — | 5 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLShaderTableGoldenTest` | — | 1 / 0 / 0 / 0 |

The six new classes are the 45 tests between 26,238 and 26,283. The other 912 classes have the same counts in both
runs, so no test was deleted or disabled.

`find modules -name 'hs_err_pid*' -type f` printed nothing, and neither did the same `find` over the whole clone. The
two `hs_err` directories that child-JVM tests create under `target/` (`mac-font-golden/hs_err`,
`mac-font-native/ThrowingSinkChild/hs_err`) hold no files. In the repository, after the after run and the later
MTL runs, which did not clean `target/`, the same `find` and a `find . -maxdepth 2` printed nothing either
(2026-11-03, 20:55:40).

## MTL runs after the round-1 review fixes

The round-1 fixes changed the Java facade, its tests and shims, and the documents, not the `prism_mtl` sources.
In the runs below CMake rebuilt only `libglass.dylib` (Glass mac, still JNI); `libprism_mtl.dylib` is still the one
the after run built at 20:02:29. On that tree, from the repository root:

```
mvn -B -ntp -Dmaven.repo.local=<scratchpad>/m2-baseline -pl modules/javafx.graphics -am test -Dtest='MTL*' \
    -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -Dmaven.repo.local=<scratchpad>/m2-baseline -pl modules/javafx.graphics -am test -Dtest='MTL*' \
    -Dsurefire.failIfNoSpecifiedTests=false -Djfx.parity.require=true
```

2026-11-03, 20:54:37 to 20:55:04 and 20:55:13 to 20:55:40: BUILD SUCCESS, 55 / 0 / 0 / 0 in 7 classes each time.
`MTLScratchTest` now has 6 tests and `MTLTextureUploadCopyTest` (9) is new; the other five classes have the counts of
the table above. Under `-Djfx.parity.require=true`, which fails a machine-gate mismatch instead of skipping, the
four `MTLGoldenRenderTest` profiles and `MTLShaderTableGoldenTest` matched the `1a6788cc4d` goldens. The
`-Xlog:jni+resolve` logs of the six child JVMs (`target/mtl-golden/*/verify/jni-resolve.log`) name no
`com.sun.prism.mtl` native method. The module run was not repeated on this tree.

## After run (workstream B)

The workstream B after run used the repository's own working tree with both libraries on FFM and `mac.cmake` without
a `JNI` keyword: the whole story but the key-chars fix, which the PR 48 review asked for later on 2026-11-05 (see
[After the key-chars fix (final)](#after-the-key-chars-fix-final)). It repeats a run of 00:30:07 to 00:31:26, after
which two Java comments changed (the Javadoc of `MacGlassNative.unexpected()` and the `GmacFileFilter` comment in
`MacDialogCalls`). Its totals, its per-class list and its export lists are those of that run. No source, CMake or pom
file changed between it and the Metal parity run below: `git status --porcelain` and the SHA-256 of `git diff` were
the same before it and after that run. The only file outside `target/` that the two runs wrote is the gitignored
`modules/javafx.base/build/module-lib/javafx.properties`, which javafx-base's `write-javafx-properties` execution
rewrites on every build. The command, the private local repository and the reactor are those of the workstream A
after run, so nothing under `tests/system` ran:

```
mvn -B -ntp -Dmaven.repo.local=<scratchpad>/m2-baseline -pl modules/javafx.graphics -am clean test
```

BUILD SUCCESS, with the totals in the table above. CMake rebuilt the four libraries from clean, all arm64. The only
native compiler warnings are the 4 `-Wdeprecated-declarations` of
`native-prism-es2/macosx/MacOSXWindowSystemInterface.m` that the before run had too.

| Library | `Java_` exports | `JNI_OnLoad` | C ABI exports |
| --- | ---: | ---: | --- |
| `libglass.dylib` | 0 | no | 193 `gmac_*`: the 193 functions `glass_mac_api.h` declares, 32 of them the shim-only `gmac_test_*` hooks |
| `libprism_mtl.dylib` | 0 | no | 53 `mtl_*`: the 53 of `prism_mtl_api.h` |
| `libprism_es2.dylib`, `libjavafx_iio.dylib` | 0 | no | not changed by this story |

```
nm -gU modules/javafx.graphics/target/native/bin/<lib> | grep -c ' T _Java_'
nm -gU modules/javafx.graphics/target/native/bin/libglass.dylib | grep -c ' T _gmac_'
nm -gU modules/javafx.graphics/target/native/bin/libglass.dylib | grep -c ' T _gmac_test_'
```

The per-class list, made as before, has 934 classes. `diff` against
[US-001b-macos-before-per-class.tsv](US-001b-macos-before-per-class.tsv) shows 22 classes, all expected:
`MacFontProcessGoldenTest` passes from the repository, as in the workstream A run, and 21 classes are new.

| Class | Before | After, workstream B |
| --- | --- | --- |
| `test.com.sun.javafx.font.MacFontProcessGoldenTest` | 2 / 1 / 0 / 0 | 2 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacAccessibleNativeTest` | — | 33 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacApplicationNativeTest` | — | 37 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacClipboardNativeTest` | — | 6 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacConstantsHeaderTest` | — | 14 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacCursorNativeTest` | — | 7 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacDialogNativeTest` | — | 32 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacGlassNativeTest` | — | 27 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacMenuNativeTest` | — | 34 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacPasteboardNativeTest` | — | 25 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacPixelsNativeTest` | — | 8 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacRobotNativeTest` | — | 20 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacTimerNativeTest` | — | 24 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacViewNativeTest` | — | 48 / 0 / 0 / 0 |
| `test.com.sun.glass.ui.mac.MacWindowNativeTest` | — | 19 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLGoldenFormatTest` | — | 11 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLGoldenRenderTest` | — | 4 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLNativeTest` | — | 18 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLObjcReleaseTest` | — | 6 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLScratchTest` | — | 6 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLShaderTableGoldenTest` | — | 1 / 0 / 0 / 0 |
| `test.com.sun.prism.mtl.MTLTextureUploadCopyTest` | — | 9 / 0 / 0 / 0 |

The 14 Glass mac classes hold 334 tests and the 7 Metal classes 55: the 389 between 26,238 and 26,627. The other
912 classes have the same counts in both runs, so no test was deleted or disabled. Summed over the `TEST-*.xml` test
cases, the totals equal the table above (32,296 run, 0 failures, 0 errors, 846 skipped). No glass class skips: the
three machine-gated Glass goldens (`mac-pixels-golden-1a6788cc4d.txt`, `mac-clipboard-golden-1a6788cc4d.txt`,
`mac-a11y-golden-1a6788cc4d.txt`) compared on this host.

`find modules -name 'hs_err_pid*' -type f` and `find . -maxdepth 2 -name 'hs_err_pid*'` printed nothing
(2026-11-05, 01:46:50); the 33 `hs_err` directories that child-JVM tests create under `target/` hold no files.

The Metal parity run followed on the same tree:

```
mvn -B -ntp -Dmaven.repo.local=<scratchpad>/m2-baseline -pl modules/javafx.graphics -am test -Dtest='MTL*' \
    -Dsurefire.failIfNoSpecifiedTests=false -Djfx.parity.require=true
```

2026-11-05, 01:42:26 to 01:42:53: BUILD SUCCESS, 55 / 0 / 0 / 0 in 7 classes, with the counts of the table above.
Under `-Djfx.parity.require=true` the four `MTLGoldenRenderTest` profiles and `MTLShaderTableGoldenTest` matched the
`1a6788cc4d` goldens.

## After the key-chars fix (final)

The PR 48 review's key-chars fix (audit g2 §6 (n)) changed `GlassKey.h`, `GlassKey.m`, `GlassViewDelegate.m`,
`glass_mac_api.h`, `glass_mac_api.m` and `glass_mac_internal.h`, added the shim-only hooks `gmac_test_key_chars_utf16`
and `gmac_test_fire_key_chars`, and extended `MacGlassNativeShim`, `MacGlassNativeTest` (its export list) and
`MacViewNativeTest`. The 17:19:23 run repeats the workstream B command on that tree, from the repository root, with
the same private local repository, so nothing under `tests/system` ran:

```
mvn -B -ntp -Dmaven.repo.local=<scratchpad>/m2-baseline -pl modules/javafx.graphics -am clean test
```

BUILD SUCCESS, with the totals in the module table above. CMake rebuilt the four libraries from clean, all arm64. The
only native compiler warnings are the same 4 `-Wdeprecated-declarations` of
`native-prism-es2/macosx/MacOSXWindowSystemInterface.m`, none in a file the fix touched. `git status --porcelain` and
the SHA-256 of `git diff` were the same before the run and after the Metal parity run below.

| Library | `Java_` exports | `JNI_OnLoad` | C ABI exports |
| --- | ---: | ---: | --- |
| `libglass.dylib` | 0 | no | 195 `gmac_*`: the 195 functions `glass_mac_api.h` declares, 34 of them the shim-only `gmac_test_*` hooks |
| `libprism_mtl.dylib` | 0 | no | 53 `mtl_*`: the 53 of `prism_mtl_api.h` |
| `libprism_es2.dylib`, `libjavafx_iio.dylib` | 0 | no | not changed by this story |

`GetJavaKeyCharsUtf16`, `GlassKeyCharsFill` and `GlassKeyCharsRelease` stay hidden: `nm -m` lists them as
non-external.

The per-class list, made as before, has 934 classes. `diff` against
[US-001b-macos-before-per-class.tsv](US-001b-macos-before-per-class.tsv) shows the 22 classes of the workstream B run
with the same counts, except `test.com.sun.glass.ui.mac.MacViewNativeTest`: 57 / 0 / 0 / 0, where workstream B had
48. Its 9 new tests are the 9 between 26,627 and 26,636. Summed over the `TEST-*.xml` test cases, the totals equal
the module table (32,305 run, 0 failures, 0 errors, 846 skipped). No glass class skips, so the three machine-gated
Glass goldens compared on this host.

`find modules -name 'hs_err_pid*' -type f` and `find . -maxdepth 2 -name 'hs_err_pid*'` printed nothing
(2026-11-05, 17:21:04); the 33 `hs_err` directories that child-JVM tests create under `target/` hold no files.

The Metal parity run followed on the same tree, with the command of the workstream B one: 2026-11-05, 17:22:00 to
17:22:28, BUILD SUCCESS, 55 / 0 / 0 / 0 in 7 classes. Under `-Djfx.parity.require=true` the four
`MTLGoldenRenderTest` profiles and `MTLShaderTableGoldenTest` matched the `1a6788cc4d` goldens.

At 17:26:02 the comments of `MacDialogNativeTest`, `MacMenuNativeTest` and `MacRobotNativeTest` took the
`GlassKey.m` and `glass_mac_api.m` line numbers the fix had moved; nothing else in them changed. Apart from those
edits, nothing outside `backlog/` changed after the Metal parity run. The same command then ran on that tree, the
final one: 2026-11-05, 17:49:04 to 17:50:23, BUILD SUCCESS. Its totals, export counts, 4 warnings and per-class list
equal those of the 17:19:23 run, and `find modules -name 'hs_err_pid*' -type f` printed nothing (17:50:30).
`git status --porcelain` and the SHA-256 of `git diff` were the same before and after it. The Metal parity run was
not repeated.

## Owner-run, deferred

Agents ran none of these. They need a display, start the Glass toolkit or activate the app (AppKit, robot,
dialogs, menus, accessibility peers, timers, pasteboards), need a sandboxed app, or need another machine. Line numbers
are at `1a6788cc4d` unless the audit says otherwise. References such as "g1 §6 (d)" are to sections of
[US-001b-glass-mac-audit.md](US-001b-glass-mac-audit.md), and the L-numbered latent bugs are g3's (§11 there),
except the `prism_mtl` item's L20, L21 and R3, which are in [US-001b-prism-mtl-audit.md](US-001b-prism-mtl-audit.md).

### `tests/system` and robot, before and after (AC 6, Definition of Ready)

Prerequisites (`jfx-ffm-testing`): a display, the Accessibility and Screen Recording permissions for the app that runs
Maven, and the sRGB IEC61966-2.1 display profile. The 2026-11-05 robot runs below were on this Mac.

- [ ] Baseline: `-DFULL_TEST=true -DUSE_ROBOT=true` on a clone of the repository detached at `1a6788cc4d`.
- [ ] The same run on the final tree, compared per class with the baseline. It is the only check of the Glass mac
  windowing, input and present paths, and of the Metal swap chain, present, `blitToScreen` and `mtl_rtt_wrap`
  (formerly `nCreateRT2`), which no golden covers. No robot run has been made on the final tree; the owner makes it,
  agents do not. The owner's full run of 2026-11-05, 08:25 to 09:07 (1,099 tests, 2 failures, 0 errors, 60 skipped),
  and the owner's run at 11:53 below were on workstream B's tree, before the PR 48 key-chars fix. That fix rewrote
  `_dialKeyEvent:` in `GlassViewDelegate.m`, through which a Glass view sends its `keyDown:` and `keyUp:` events to
  Java, robot key presses included, so neither run stands for the final tree. The full run's failures were
  `SystemMenuBarClickEventsTest.testSystemMenuBarProcessesClickEvent`, pre-existing (Menus below), and
  `SliderTooltipNPETest.testSliderTooltipNPE`, intermittent (View and gestures below). Cover in the run on the final
  tree, by area:
  - [ ] Start-up (slice 11): run an app normally and under `-XstartOnFirstThread`, each up to a normal exit, and
    compare stderr and the uncaught-exception handler output with the JNI build. The macOS version check passes,
    the Dock icon matches the taskbar flag (regular app or UI element), the launchable runs (`run_launchable` from
    `applicationWillFinishLaunching:`), and slot 2 (`set_event_thread`), the first FFM upcall and now the only
    main-thread attach, attaches the main thread as a daemon "Thread-N" where `runLoop:` attached it (g1 §6 (o)). The
    FX thread keeps its name and daemon status, no runnable is lost, and stderr shows no "Java has been detached
    already" call stack.
  - [ ] Application (slice 1): `ExceptionHandlerTest`, `DefaultExceptionHandlerTest`, `NestedEventLoopTest`,
    `NestedEventLoopPlatformExitTest`, `ScreenTest`, `MultipleScreensTest`, `PlatformTest`, `KeyLockedTest`. By hand:
    `openURI`/`showDocument`; the Apple menu's Hide, Hide Others and Show All, also with a focus or occlusion listener
    that throws (reported once through `Application.reportException`); opening files from Finder; leaving full screen
    (the nested loop); a `Stage.showAndWait()` whose `onHidden` handler throws after the close button hid the stage,
    and a menu action that closes the dialog and then throws (in both builds the exception reaches the
    uncaught-exception handler, `showAndWait` returns normally and later `Platform.runLater` calls still run).
  - [ ] Cursor and pixels (slice 2): `ImageCursorGetBestSizeTest`; the 20 standard cursors; a custom `ImageCursor` from
    int and byte `Pixels` with its hot spot; `Cursor.NONE` and visibility toggles staying balanced; `getBestSize` with
    zero and negative sizes; menu item icons; clipboard and drag images.
  - [ ] Timer (slice 3): `PlatformTest` and pulse-timer jitter (the timer experiment is under PB2 below).
  - [ ] Robot (slice 4): `RobotTest` and the robot suites: event posting, `CGWarpMouseCursorPosition`, `getMouseX`/`Y`
    through `gmac_mouse_location_flipped`, `getPixelColor`, `getScreenCapture` with and without `scaleToFit`, and
    `keyPress`/`keyRelease` through `AXUIElementPostKeyboardEvent` with `gmac_key_find_mac`'s search of the current
    keyboard layout. Each body pairs `gmac_pool_push` with `gmac_pool_pop` in a `finally` (PB21), proven by
    reading only.
  - [ ] Window (slice 5): the robot stage tests (`test/robot/javafx/stage/**`), `FullScreenExitTest`,
    `MultipleScreensTest`, `ScreenTest`. By hand: title-bar icon, dark frame, header button style and appearance,
    title-bar double-click, window drag, focus and grab with popups, minimize and maximize; `setScene`/`Window.setView`
    on a stage in native full screen, also with the new view closed during the exit loop (it must leave full screen
    and end with the 10x10 dummy content view); an `EXTENDED` stage with a `HeaderBar` under an RTL language
    (`-AppleLanguages '(ar)'`), whose header-button metrics must mirror.
  - [ ] View and gestures (slice 6): the robot scene tests (`test/robot/javafx/scene/**`). By hand: IME (marked and
    committed text, candidate window position; Japanese, Chinese, Keyman), dead keys and key repeats, trackpad
    rotate, magnify and swipe, wheel and inertial scroll, drag and drop into and out of a view (drag end), the
    ⌘-key RELEASE synthesis, Caps Lock PRESS/RELEASE, full screen enter and exit. Every pipeline:
    `-Dprism.order=mtl`, `es2` and `sw`, because `GmacViewCaps` takes a different branch for each; `_begin`, `_end`
    and `_getNativeFrameBuffer` stay on the render thread with the pool spanning `_begin` to `_end`.
    `SliderTooltipNPETest.testSliderTooltipNPE` failed once, in the owner's full run on workstream B's tree:
    "Timeout waiting for tooltip to display" at line 99 (`dragSliderAfterTooltipDisplayed`, its 5 s wait for the
    tooltip). On that tree it is intermittent, not a US-001b regression: it passed there in the owner's run at 11:53
    (1 / 0 / 0 / 0, with `TooltipTest` 8 / 0 / 0 / 0 in the same run), and a reading of its path found no FFM-vs-JNI
    difference. It was not run on the JNI build or on the final tree.
  - [ ] Pasteboard and clipboard (slice 7): `MacPasteboardTest`, `test.javafx.scene.input.ClipboardTest`, and the
    manual `tests/manual` dnd and clipboard checks: copy and paste of text, HTML, RTF, URLs, file lists, images and a
    custom MIME type; drags with a custom drag image and offset, each drag action (copy, move, link), and a drag from
    the Dock. No agent test calls a `gmac_pasteboard_*` export: watch the first general, drag and user pasteboard use
    for macOS's paste-privacy prompt.
  - [ ] Dialogs and `MacFileNSURL` (slice 8): the monkey `FileChooserPage`: open with single and multiple selection,
    save with a file name that has an extension, and folder; with no filters, with filters, and with `*` and `*.*`
    (L30); the default filter index; cancel; as a sheet of an owner window and on its own; with
    `glass.macosx.enableFileNSURL` true and false (`MacFileNSURL` returned, `dispose`, start and stop of the security
    scope).
  - [ ] The sandbox bookmark check (audit owner-run item 8): `getBookmark`/`createFromBookmark` and
    `start`/`stopAccessingSecurityScopedResource` from a sandboxed app. No checked-in test runs the real
    `gmac_url_get_bookmark` or `gmac_url_create_from_bookmark`: they always make and resolve security-scoped
    bookmarks, so this is their first real run (XPC to `ScopedBookmarksAgent`). Their PB22 change (the copy outside the
    linkage catch) was proved only by reading the code.
  - [ ] Menus (slice 9): `SystemMenuBarTest`, the robot `SystemMenuBar{HelpMenu,Enable,ClickEvents}Test`,
    `MenuDoubleShortcutTest` and the manual `tests/manual/systemmenu` check: actions, `validateMenuItem:` (also its
    re-entry inside `gmac_menubar_insert`/`_remove` and `gmac_menu_remove`), `menuWillOpen:`/`menuDidClose:` with and
    without a window grab, `dealloc` → `menu_disposed`, key equivalents and item icons.
    `SystemMenuBarClickEventsTest.testSystemMenuBarProcessesClickEvent` fails at line 136 ("Context menu should be
    showing after right-click ==> expected: <true> but was: <false>"). It is pre-existing, not a US-001b regression:
    on 2026-11-05 it failed there in 3 of 3 runs on workstream B's tree and 2 of 2 on the JNI build at `1a6788cc4d`,
    each in 0.21 to 0.22 s, and a reading of the right-click path in both trees found no FFM-vs-JNI behaviour
    difference. Filed, with the runs, as
    [US-073](US-073-make-the-system-menu-bar-click-test-pass-with-a-non-auto-hide-context-menu.md).
  - [ ] Metal (workstream A): the robot `painttest`, `scenegraph` and `test3d`; on Metal also a `SwingNode` with small
    dirty-rectangle repaints and an `ImageView` of an image wider than 16384 px (the packed and span upload copies).
- [ ] Watch items during the owner's module run (audit owner-run item 5): the 19 `MacWindowNativeTest` child JVMs and
  `MacAccessibleNativeTest` load `libglass`; no Dock icon may appear, focus may not change and no privacy prompt may
  open. `MacRobotNativeTest`'s whole-event tests take the live modifier state, so a modifier key that changes while
  they run can fail one once.

### VoiceOver (AC 8)

- [ ] VoiceOver against `MacAccessible` on `1a6788cc4d` and on the final tree: peer creation, attribute and action
  upcalls, `peer_released` from threads other than the main one; `tests/manual/a11y`
  (`AccessibilityNotificationTest`/`App`); the AX-tree dump comparison (g4 §11). It is the only run-time check of
  `NSAccessibilityPostNotification` and `NSAccessibilityUnignoredAncestor`, which are exact by disassembly only, and
  of real peer creation: no agent test makes a peer.

### Toolkit goldens from the JNI build

Capture on `1a6788cc4d` with the toolkit running, then compare with the final tree. The working tree's JNI paths are
gone, so no capture can come from it.

- [ ] `getPlatformPreferences()`: key set, values and `HashMap` iteration order. Compare `Color[]` values element by
  element: they compare by identity in `HashMap.equals` (g1 latent bug 17).
- [ ] `Screen.getScreens()`: all 20 constructor arguments of each screen.
- [ ] stderr at the 4 DESCRIBE sites (`GlassApplication.m:235, 242, 249, 844`): a throwing launchable (slot 1) and a
  throwing `enterNestedEventLoop` (slot 17). The FFM side prints `Exception in thread "<name>" ` and then
  `printStackTrace`; `MacApplicationNativeTest.theLaunchableAndTheNestedLoopDescribeWhatTheyThrow` pins its bytes.
- [ ] Robot (slice 4): a `CGEventTap` recording of the posted events and a screen-capture and pixel-colour comparison
  between the two builds. They confirm the by-construction parity of g1 §1.

### Timer experiment and the other PB2 follow-ups ("Java replacement after owner A/B")

Each stays native behind a `gmac_*` export (the "Kept native" sections, §10 of each group) until its A/B passes.

- [ ] The timer experiment (g1 §10, slice 3), the JNI build against the final tree: `jcmd Thread.print` after start,
  pause and resume (the "Glass Timer Thread" and "PulseTimer-CVDisplayLink thread" names; whether CoreVideo keeps the
  display-link OS thread across a pause, g1 §6 (j); the "Thread-N" numbering and the period thread's attach at its
  first tick, g1 §6 (i)), tick-interval histograms, and `leaks`/RSS over 10⁵ pulses. If it passes, Java binds
  CoreVideo and libSystem's pthread calls in place of the `gmac_timer_*` shim (`GlassTimer.m`, `getTimeMicroseconds`,
  `GlassDisplayLink`), and `staticScreen_getVideoRefreshPeriod` becomes a CoreVideo bind; until then
  `gmac_screen_video_refresh_period` stays native (PB4).
- [ ] `gmac_app_data_directory` (`_getDataDirectory`, D5): an A/B against a golden captured from the JNI build first
  (g1 §10).
- [ ] `gmac_mouse_location_flipped`: the candidate `CGEventGetLocation(CGEventCreate(NULL))` has unknown parity;
  sample both on several monitors and at fractional points (g1 §10).
- [ ] `gmac_clipboard_file_reference_path` (`_convertFileReferencePath`): a corpus A/B against the CFURL chain (spaces,
  `%`, non-ASCII, `file:///.file/id=` references, invalid strings); a divergence is likely, because NSURL's parsing
  depends on the SDK the launcher links (g3 §10).
- [ ] `gmac_url_start_accessing_security_scoped_resource` / `_stop_…`: CFURL binds after an A/B that includes a
  sandboxed app (g3 §10).
- [ ] `gmac_url_get_bookmark` / `gmac_url_create_from_bookmark`: `CFURLCreateBookmarkData` /
  `CFURLCreateByResolvingBookmarkData` after the sandbox check (g3 §10).
- [ ] `gmac_a11y_unignored_children` (`NSAccessibilityUnignoredChildren`): an A/B on stub peers that toggle
  `is_ignored`, and acceptance of the different crash on a 0 id (g4 §10).
- [ ] Measure the per-frame cost of the confined-arena copy in `MacViewCalls.uploadPixelsBytes`/`uploadPixelsInts`
  (`w*h*4` bytes per upload) under `-Dprism.order=sw` (g2 Risk 6). The copy stands under PB5.

### Divergences and latent bugs to accept or reject (PB7)

- [ ] g1 §6: (a) App slots 4-15 report inside the delegate's pool; (b) slot 18 reports at once; (c) a detached
  `_getDataDirectory` off the main thread reports and answers; (d) on a detached main thread App slots 1, 4-15, 17 and
  18 print the `GLASS_CALLSTACK` message and are skipped, and the code after each slot now runs (audit owner-run item
  10; only after `[NSApp run]` has returned); (f), (g) the cursor pools and detached-report timing; (h) an
  `OutOfMemoryError` while Java builds a result after the downcall reaches the caller (screens, data directory,
  preferences, bookmark data, URL paths and chooser URL arrays, pasteboard blocks, FormatEncoder and file-reference
  strings), where the JNI reported it and answered null, a partial result or, after `createUTF` and `_getUTFs`, crashed
  on a NULL array; (i)-(m) the timer threads, registry and tick failures; (n) calls after `MacRobot.destroy`; (o) slot
  2's attach, and a failed attach now fatal in HotSpot's upcall stub (read from `UpcallLinker`, not run).
- [ ] g2 §6: (a) WU1-WU10 reported at once, every throwing slot; (b) detached window sites print and skip; (c) the
  `_setView` split and its two pools; (d) detached reports after the pool; (f) the Caps Lock pair at once; (h) a
  detached IME event prints once and skips; (i) `char[]` identity after a nested key event; (j) `GmacViewCaps` reads
  the 7 device-detail keys once, in Java; (k) the initializer's `GET_MAIN_JENV`; (l) the detached view calls under
  `glass.disableThreadChecks` that crashed now have defined outcomes; (m) the multi-click maxima are the constant 3, so
  the JNI's `ExceptionInInitializerError`, which needed the main thread to detach inside `MacView`'s initializer, is
  not reproduced; (n) the key-chars stack overflow is fixed: every unit of `[event characters]` reaches Java, and if
  the heap block for more than 16 units cannot be allocated, `notify_key` gets the first 16 units, count 16, where the
  JNI dialled with a null `char[]` while an `OutOfMemoryError` was pending.
- [ ] g3 §6: (a)-(d), (f) the dialogs (a detached main thread answers as cancelled, L33; a late leave after a throwing
  `enter()` is a no-op, L14; filter upcalls before the panel; URLs converted after the pool; `new EventLoop()` on the
  calling thread); (g)-(j), (n) the menus; (o) `createFromBookmark` answers null on the `enableFileNSURL` race, L31;
  (p) a null filter or a throwing `extensionsToArray()` gives a filter without file types, L32. Slice 7: L24 (a 1-7
  byte drag-image offset reads zeros), L25 and L26 (crashes become defined), L27 (malformed `putItemsFromArray` input
  throws before the downcall and leaks that call's images), and `attachData` before `clearContents` (Risk R6).
- [ ] g4 §6 (a): `peer_released` runs on the releasing thread. With g4 latent bug 6 (an `NSException` while the C
  turns a slot's output into objects), the blocks Java handed over are not freed, where the JNI released its pinned
  array.
- [ ] `prism_mtl`: R3 and L21 (printing a `Canvas` or a WebView `RTImage` rendered on Metal copies into `NULL` and
  crashes, in both builds) and L20 (`BYTE_RGB`/`BYTE_GRAY` sub-image uploads read transparent black past the array,
  where the JNI read heap garbage).
- [ ] Which carried latent bugs get a fix story. Found during the migration: g1 17 (the preferences change check never
  matches) and g1 19 (a 0 ms period timer is taken for the vsync timer).
- [ ] PB23 placement: the `GmacFileFilter` layout check sits in `MacDialogCalls`' static initializer, not in
  `MacDialogCallbacks.install` (Slice 8 "As built" says why).
- [ ] `MacVariant.stringArray`: kept although nothing reads it, because the JNI cached a field ID for it; removing it
  is not a transport change (the "unused stringArray" comments in `glass_mac_api.h`, `MacAccessibleCallbacks` and the
  shim would change with it).
- [ ] Optional dead code, the owner's call (Slice 11): `NSScreen(FullscreenAdditions)`, `printLoadedLibraries`,
  `printLoadedFiles`, `getRam`, `jshareContextPtr`, g3's `DumpPasteboard`, and the unused `GlassAccessible.h` and
  `MacVariant.h` imports (unused at `1a6788cc4d` too).
- [ ] Line endings: `CMakeLists.txt` and `win.cmake`, `linux.cmake` and `mac.cmake` of javafx.graphics are CRLF in the
  repository (`.gitattributes` is `* -text`), while `openjfx-conventions` asks for LF. `mac.cmake` kept CRLF on every
  line; converting the four files would be a separate whole-file change.

### Test hooks in the shipping libglass

For the record, not a check: `libglass.dylib` exports 34 `gmac_test_*` hooks (`nm -gU … | grep -c ' T _gmac_test_'`;
32 at the workstream B after run, before the key-chars fix of the PR 48 review added `gmac_test_key_chars_utf16` and
`gmac_test_fire_key_chars`), following the `glass_win_api.h` and `glass_gtk_api.h` precedent: `glass_mac_api.h`
declares them shim-only, `MacGlassNative` binds none, and a change to them does not move `GLASS_MAC_ABI_VERSION`
(US-068 is the Windows story about keeping such hooks out of the release library). PB19 keeps the oracle hooks that
are the replaced C there on purpose, a deliberate exception and not a follow-up: `gmac_test_clipboard_mime_to_uti`,
`gmac_test_clipboard_uti_to_mime` and `gmac_test_nslocale_language_direction`, and under the same rule slice 10's
`gmac_test_a11y_utf8_string`, `gmac_test_a11y_is_equal_to_string` and `gmac_test_a11y_enum_pointer`. The JNI goldens
are machine-gated and can no longer be captured from this tree, so these in-process A/Bs are the only parity check on
other machines.

### Do not update this host (D3)

- [ ] Keep macOS 27.0.1 (26A434), Xcode 27.1 and JDK 27.0.1.0.2+1-2 until the owner-run checks above are done. The
  goldens compare only where their `machine.*` keys match: the Metal goldens on the macOS build, the dyld shared cache,
  the GPU and driver, the metallib and `machine.java.vm.version`; the Glass goldens on macOS 27.0.1 aarch64, the a11y
  one also on the preferred languages `en-US`. After an update they skip, or fail under `-Djfx.parity.require=true`,
  and a re-capture needs the JNI natives of `1a6788cc4d`, since this tree has none left.

### macOS x86_64 and the other platforms in CI

- [ ] A green `.github/workflows/submit.yml` run before merge (README-code-reviews asks for the GitHub Actions
  tests), with `macos_x64_build` and `macos_aarch64_build`. Only macOS 27.0.1 arm64 was built and tested here. The x64
  job checks the warning-clean libglass and libprism_mtl builds with the new `mac.cmake`, the `Mac*` and `MTL*` tests,
  and in particular: the bootstrap exports `gmac_app_init` and `gmac_app_create_delegate` and slot 2's attach point;
  `_getPixelColor`'s rounding (the JNI narrowed with `cvttsd2si`, 0x80000000 out of range, where Java saturates, so
  the bytes differ only for `round(c * 255) >= 2^31` or `c = +Inf`, which an 8-bit sRGB capture does not reach); the
  variadic `CGEventCreateScrollWheelEvent` (`%al`, set by FFM's `firstVariadicArg`); `objc_release` bound from Java
  (`prism_mtl` audit R7); and the PB19 oracle A/Bs (FormatEncoder over UTType, the RTL CoreFoundation chain, the a11y
  strings). The machine-gated goldens skip there. Windows and Linux are untouched
  (`mac.cmake` is included only under `APPLE`; `win.cmake`, `linux.cmake` and `CMakeLists.txt` are unchanged), but
  the run covers them too.
