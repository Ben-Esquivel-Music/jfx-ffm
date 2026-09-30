# US-008 — Remove the dead jslc ME backend and `AccelType.SIMD`

**Status:** ✅ Done (2026-09-27, PR #19); extended the same day with three findings of the first pass (F1–F3); each later finding fixed here or filed as US-015 to US-024 · **Found:** 2026-09-13, footprint audit for the `decora_sse` deletion · **Deferred from:** the `decora_sse` deletion

## Story
As a maintainer of the fork,
I want the unused JSL compiler backend and the leftover SIMD accel type gone,
so that the codebase stops carrying a JNI code generator and names that no longer mean anything.

## Problem
- **ME backend:** the jslc ME backend is under `modules/javafx.graphics/src/jslc/java/com/sun/scenario/effect/compiler/backend/sw/me/`. That is 4 files and about 1,634 lines, plus about 184 lines of `.stg` templates under the matching `resources` path. `MENativeGlue.stg` still emits JNI C. Nothing invokes it: no build step passes `-me`, and the `OUT_ME_JAVA` / `OUT_ME_NATIVE` flags have no caller. Upstream carries the same dead code.
- **SIMD accel type:** `AccelType.SIMD` (`Effect.java` around `:526-528`) and the `case SIMD:` in `BoxRenderState` (around `:302`) are unreachable. Nothing reports SIMD since `decora_sse` was deleted.

## Acceptance criteria
- Delete the ME backend (`.java` and `.stg`), remove the `OUT_ME*` constants, the `-me` option and its usage text from `JSLC.java`, and drop `AccelType.SIMD` and its `case`.
- `mvn -pl buildtools/jslc,modules/javafx.graphics clean test`: same test counts as before, and the generated `target/gensrc/jsl-decora` is name-and-md5 identical before and after.
- A `git grep` for `OUT_ME`, `MEBackend`, `backend.sw.me` and `SIMD` finds nothing outside history comments.
- The Linux (WSL) graphics build is green.

## Notes
- Pure cleanup, with no rendering change. Keep `OUT_*` bit values stable if any external tool passes numeric masks; none is known.

## Extension (2026-09-27): the three findings of the first pass
The first pass (Resolution below) found three more problems of the same kind: dead or unverified code next to the
code it removed. The story was extended to cover them (resolved at the end of this file).

### Problem
- **F1 — `AccelType.FIXED`:** no code has ever produced it. `git log -S 'AccelType.FIXED'` finds no commit in the
  whole history, no `case` uses it, and its javadoc ("accelerated using native fixed-point arithmetic") describes
  nothing that exists. It is dead for the same reasons as `SIMD`.
- **F2 — the jslc tests do not run:** `modules/javafx.graphics/src/test/jslc` (25 files, about 2,100 lines of lexer,
  parser and symbol tests) is in no pom, so `javafx-jslc` runs 0 tests. It does not compile either: 34 errors, from a
  half-finished upstream move to ANTLR 4 and JUnit 5 (ANTLR 3 `org.antlr.runtime`, JUnit 4 `@Before`/`Assert`, missing
  imports) and `JSLC.OUT_ALL`, which upstream removed in `682ee49241`. The JSL compiler that generates every Decora
  and Prism shader therefore has no test.
- **F3 — GTK display tests fail under WSLg and open windows on the desktop:** the javafx.graphics module suite runs
  GTK Glass tests in child JVMs whenever `DISPLAY` is set. WSLg exports `DISPLAY=:0`, so a plain module run in WSL puts
  windows on the Windows desktop. There, 6 assertions fail on unmodified e003c1f200:
  - `GtkWindowNativeTest`: `theMinimumSizeReachesTheWindowManager`, `theMaximumSizeReachesTheWindowManager` and
    `theSystemMinimumSizeRaisesTheMinimum` (for example `max=724,503` where `800,600` was set);
  - `GtkUploadBenchmarkTest`: `everyKindPaintsItsFrame`, `aFrameOutsideItsArrayPaintsNothing` and
    `aFrameAtAnOffsetIsPaintedFromThatOffset`.

  CI has no display, so it never runs them.

### Acceptance criteria (extension)
- **F1:** `AccelType.FIXED` and its javadoc are removed. Nothing else changes, generated sources and test counts stay
  identical, and `git grep -n 'AccelType.FIXED\|CPU/Fixed'` finds nothing.
- **F2:** the jslc tests compile against the current ANTLR 4 compiler and JUnit 5, and `mvn -pl buildtools/jslc test`
  runs them in the normal build. The tests change and the JSL compiler does not: generated sources stay identical by
  name and md5. A test whose expectation contradicts what the compiler does today is reported and decided on, never
  silently deleted.
- **F3:** the root cause of each of the 6 failures is established from evidence and is either a product bug (fixed,
  with a test) or an environment assumption in the test (the test made correct for that environment, or skipped with a
  stated reason). The fix is checked on WSLg, where the failures occur, and on a private rootless Xvfb, and the
  headless CI-like run is unchanged.
- The original acceptance gate still holds on Windows and Linux: generated sources identical, and the only test-count
  differences are the new jslc tests plus any F3 test that now passes or skips instead of failing.

## Resolution (2026-09-27, PR #19)
- **Change:** 9 files, +3 / −1,867 lines.
  - The ME backend is deleted: `MEBackend`, `MECallScanner`, `MEFuncImpls` and `METreeScanner` (1,634 lines), and
    `MEJavaGlue.stg` and `MENativeGlue.stg` (184 lines; the second emitted `JNIEXPORT`/`JNIEnv` C). Both `sw/me`
    directories are gone.
  - `JSLC.java` lost the `MEBackend` import, `OUT_ME_JAVA`, `OUT_ME_NATIVE` and `OUT_ME`, their two `DEFAULT_INFO_MAP`
    entries, the `OUT_ME` block in `compile`, the `-me` option, `-me` in the usage line and the two `impl/sw/me` lines
    of the output-layout javadoc.
  - `Effect.AccelType.SIMD` and the `case SIMD:` in `BoxRenderState.getPassPeer` are gone.
- **Why it is neutral:**
  - The remaining `OUT_*` values are unchanged: `javap` of the old and new `JSLC` gives `OUT_NONE` 0, `OUT_D3D` 1,
    `OUT_ES2` 2, `OUT_MTL` 4, `OUT_JAVA` 8, `OUT_PRISM` 16, `OUT_SW_PEERS` 8, `OUT_HW_PEERS` 16, `OUT_ALL_PEERS` 24 and
    `OUT_HW_SHADERS` 7. Bits 5 to 8 are now unused.
  - The only producer `SIMD` ever had was `SSERendererDelegate`, deleted with `decora_sse` in `26ce75d02f`. No code uses
    `AccelType.values()`, `ordinal()`, `valueOf`, `EnumMap` or `EnumSet`, and the only `switch` over it is
    `getPassPeer`, where `NONE` takes the same path as before. `AccelType` is now `INTRINSIC`, `NONE`, `FIXED`,
    `OPENGL`, `DIRECT3D`, `METAL` (`FIXED` went too in the extension, below).
  - The ME backend loaded its templates through its own `STGroupFile`. `JSLC.group`, the `STGroupDir` over
    `compiler/backend`, is never asked for an ME template.
- **Acceptance gate:** `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test`, run on commit
  e003c1f200 and again with the change:
  - **Windows (JDK 26):** with `-Djfx.parity.require=true`. `javafx-jslc` runs 0 tests both times. `javafx.graphics`
    runs 25,678 tests with 0 failures, 0 errors and 356 skipped both times, and each of the 562 test classes has the
    same counts.
  - **Linux (WSL Ubuntu, JDK 25):** fresh clones with `-am`, run with no display as CI does. BUILD SUCCESS both times.
    Run without `-Djfx.parity.require=true`: the Linux font goldens were captured on another machine, so
    `LinuxFontConfigGoldenTest`, `LinuxFontProcessGoldenTest`, `LinuxFreetypeGoldenTest` and `LinuxPangoGoldenTest`
    skip one test each, and the property would make those skips failures. `javafx.base` runs 5,510 tests
    (22 skipped). `javafx.graphics` runs 25,492 tests with 0 failures, 0 errors and 500 skipped. Each of the 852 test
    classes has the same counts.
  - **Generated files:** on both platforms `target/gensrc/jsl-decora` is identical by name and md5 (108 files), and so
    are `jsl-prism` (424 files) and `headers` (46 files). The `DecoraJavaGoldenTest` output is byte-identical. The only
    difference in `buildtools/jslc/target/classes` is the 22 removed `backend/sw/me` entries.
  - **Review:** it regenerated the Decora and Prism shaders for all three hardware backends (`-d3d -es2 -mtl`) with the
    old and the new JSL compiler. The 210 Decora files and 850 Prism files are identical.
- **Grep:** `git grep -n -E 'OUT_ME|MEBackend|backend\.sw\.me|SIMD'` has no hit in any `.java`, `.jsl`, `.stg`, `.g4`,
  pom, CMake or workflow file. The remaining hits are unrelated to this code:
  - the Khronos `GLX_GPU_NUM_SIMD_AMD` / `WGL_GPU_NUM_SIMD_AMD` defines in the ES2 `GL/` headers;
  - WebKit/JavaScriptCore and GStreamer third-party code;
  - media `ColorConverter.c` (`ENABLE_SIMD_SSE2`) and a comment in `AVFAudioEqualizer.cpp`;
  - the `FFM-AUDIT-*.md` files, and this story.
- **Observations of the first pass** (all three were then taken into the story; see the resolution of the extension):
  - `AccelType.FIXED` has no producer either, now or anywhere in the history (`git log -S 'AccelType.FIXED'` finds no
    commit). Removing it would be neutral for the same reasons as `SIMD`.
  - `src/test/jslc` (25 files) is in no pom and does not compile, before or after this change. Both give the same
    34 errors: ANTLR 3 and JUnit 4 APIs, and `JSLC.OUT_ALL`, which upstream removed in `682ee49241`. None of them names
    an ME or SIMD symbol.
  - **Linux gate:** WSLg exports `DISPLAY=:0`. With it set, the GTK Glass native tests in the module suite
    (`GtkWindowNativeTest`, `GtkUploadBenchmarkTest`) open windows on the Windows desktop, and 6 window-manager
    assertions fail on e003c1f200 already. The gate therefore unsets `DISPLAY` and `WAYLAND_DISPLAY`, like CI, which
    has no display.

## Resolution of the extension (2026-09-27, PR #19)
- **F1 — `AccelType.FIXED`:** removed with its javadoc (`Effect.java`). `AccelType` is now `INTRINSIC`, `NONE`,
  `OPENGL`, `DIRECT3D`, `METAL`. `git grep` for `AccelType.FIXED`, `\bFIXED\b` and `CPU/Fixed` over `modules/`, `apps/`
  and `tests/` finds only unrelated constants: `TabPane.TabDragPolicy.FIXED`, the `ButtonBarSkin` spacer, ICU and WTF
  double-conversion, libxml/libxslt `#FIXED`, WebCore `GraphicsContextGL::FIXED`. The story's grep finds only this
  story.
- **F2 — the jslc tests run:**
  - `buildtools/jslc/pom.xml` (+26 lines, CRLF kept) takes its test sources from `src/test/jslc`, adds
    `junit-jupiter` and `junit-platform-launcher` at the versions the root pom manages, and passes the usual JUnit
    timeouts. It overrides the surefire includes with `com/sun/scenario/effect/compiler/**/*.java`: the root's
    `test/**/*.java` matches none of these tests, so surefire would otherwise run 0.
  - The 34 compile errors (19 missing symbols, 14 unreported `IOException`s, 1 ANTLR 3 package) are fixed in 10 of the
    25 files without changing what the tests check: ANTLR 4 `CharStreams.fromString` (the counterpart of ANTLR 3's
    `ANTLRStringStream`) in `LexerBase` and `ParserBase`, JUnit 5 `@BeforeEach`, `Assertions.fail` and
    `AssertionFailedError`, missing imports, and `FullySpecifiedTypeExpr` for the ANTLR 3 return type. `SymbolTest`
    uses `OUT_ALL_PEERS | OUT_HW_SHADERS`, which is what `682ee49241` made of `OUT_ALL` (`OUT_ALL` stays out of
    `JSLC`), and a JUnit `@TempDir` instead of leaving a file in the system temp directory on every run.
  - Six tests could never fail, and now can:
    - `FieldSelectTest` `notAFieldSelection2`, `tooManyVals` and `mixedVals`: upstream's helper called `fail`
      whenever an error was expected, whatever the input. The helper now asserts that the whole input was one field
      selection, and the 12 positive tests pass through it.
    - `SymbolTest.specialVarUsedOutsideOfMain`: with the Metal backend in the mask, every program that parses throws
      a `StringIndexOutOfBoundsException` at `MSLBackend`'s `jsl-` path check (US-018), so
      `assertThrows(RuntimeException.class)` alone proved nothing. The test now also checks the message, "Unknown
      variable pos0".
    - `IntegerTest.badDigits` was a bare `recognize("H128376")`: the ANTLR 4 upgrade (`52adea7c36`) dropped its
      expected exception. It now asserts that `H128376` is not lexed as an integer (it is the identifier `H128376`).
    - `PrimaryExprTest.bracketted`, a positive test that runs in four classes through inheritance, parsed `(5)` and
      asserted nothing. It now checks for a `ParenExpr` around the literal 5.

    With a valid input in place of the invalid one, each of the five negatives fails. `bracketted` fails for other
    inputs and for a `JSLVisitor` mutant that drops the `ParenExpr`.
  - Every other negative now fails only for the reason its name states, not merely with some exception:
    - the three read-only assignments in `AssignmentExprTest`, which accepted any `RuntimeException`, check
      `TreeMaker`'s "Left-hand side of assignment expression cannot be const variable";
    - the parser negatives check the parser's `line 1:0 mismatched input '<token>' expecting ` or
      `extraneous input '<token>' expecting ` when the rule rejects the first token, or
      `trailing input '<token>' at <line>:<column>` when it stops early;
    - that last message comes from `ParserBase.assertAllInputConsumed`. A rule called directly has no EOF after it,
      so it stops without an error at the first token that cannot extend a complete match. All 13 parser-test
      helpers now check that the whole input was consumed, so no positive test passes on a prefix either;
    - a lexer error (`token recognition error`) matches none of these. The `EqualityExprTest`, `RelationalExprTest`
      and `UnaryExprTest` negatives, which failed in the lexer, now use inputs that lex (`foo && 3`, `foo == 3`, `*`
      before a primary expression). The `MultExprTest` and `AddExprTest` negatives, which passed on a
      `ClassCastException`, now fail on the end-of-input check;
    - the lexer negatives check the text and type of the token they get. `IdentifierTest.notAnId2` checks only the
      exception, which can only mean that no token matches at 1:0.
  - Every changed test was proven with scratch mutants: a valid input of the named kind makes it fail, and so does,
    for each pinned negative, an input that fails for another reason (a lexer error, another compiler error). The
    repository version passes. An independent review repeated the proofs with its own inputs.
  - No test contradicted the compiler. Under `src/jslc`, the only change beyond the first pass is a comment, `JSLC`'s
    output-layout javadoc (below), and the shaders regenerated with the new classes are identical.
  - `javafx-jslc` now runs 159 tests in 22 classes (`mvn -pl buildtools/jslc test`, about 15 s). They are part of
    `mvn install`, so CI runs them on all three platforms. Because the module now has tests, a `-Dtest` run that
    includes `buildtools/jslc` needs `-Dsurefire.failIfNoSpecifiedTests=false`, as `javafx.base` under `-am` already
    did. Surefire 3.5.4 ignores a plain `-DfailIfNoSpecifiedTests=false`.
- **F3 — the GTK display tests:** all six failures were assumptions in the tests. Glass behaves exactly like the JNI
  glass of 8492cb03b0, which was run on openbox and on WSLg to check.
  - `GtkWindowNativeTest` (3): Java's minimum and maximum sizes include the window frame. Before GTK writes the
    client-size `WM_NORMAL_HINTS`, `WindowContextTop::update_window_constraints` subtracts the `_NET_FRAME_EXTENTS`
    the window manager reports, and keeps a minimum of at least 1. The test expected the Java sizes verbatim, which
    holds only without a window manager. Weston (WSLg) reports 38,38,59,38, its 32 px shadow plus the border or title
    bar: 800 − 76 = 724 and 600 − 97 = 503. Under openbox on a private Xvfb (1,1,20,5) the test failed the same way.
    It now records the window's extents with each set of hints, re-reading both until two reads agree (Weston can set
    the extents seconds after the map), and expects the requested size less those extents. With no window manager
    that gives the old literal values.
  - `GtkUploadBenchmarkTest` (3): the read-back used the robot, which captures the root window. On WSLg's rootless
    Xwayland an `XGetImage` of the root always fails (BadMatch), and the capture returns an undefined fallback pixmap:
    `20a040` was the first colour read back, repeated for every later read. The test now reads the window's own pixels
    (`GtkGlassShim.windowPixel`, an `XGetImage` of the window inside a GDK error trap), and waits up to 20 s for the
    window to be viewable before the first frame. Capturing the root window stays covered by `GtkRobotNativeTest`,
    which is limited to bare displays.
  - The change is in `GtkGlassShim` (`netFrameExtents`, `windowPixel`, five libX11 and GDK downcalls),
    `GtkWindowNativeTest` and `GtkUploadBenchmarkTest`; no product code changed.
  - Verified:
    - WSLg `:0`: 6/6 and 5/5, one class per Maven run.
    - A private Xvfb with no window manager and with openbox: all pass. With openbox the unchanged tests fail 3.
    - With no `DISPLAY`, both classes skip, as before.
    - The WSLg runs used the fix before review. Review then changed `windowPixel` only by a javadoc line and a
      `try`/`finally` around `XGetImage`, and the final code passes again on the private Xvfb, with and without
      openbox.
- **Acceptance gate:** same command and baselines as the first pass.
  - Windows: `javafx.graphics` 25,678 tests, 0 failures, 0 errors, 356 skipped, and each of the 562 classes as before.
    `javafx-jslc` went from 0 to 159 tests (22 new classes, no failure).
  - Linux, with no display: `javafx.base` 5,510 (22 skipped) and `javafx.graphics` 25,492 with 0 failures, 0 errors
    and 500 skipped, each of the 852 classes as before. `javafx-jslc` 159.
  - On both, `jsl-decora` (108 files), `jsl-prism` (424) and `headers` (46) are identical by name and md5, and the
    `DecoraJavaGoldenTest` output is byte-identical.
- **Review:** an independent review of the extension found one must-fix and four nits, and all five were fixed:
  - the must-fix: `SymbolTest` passed whatever the program (above);
  - unused imports in two import blocks the change edits;
  - the `windowPixel` javadoc (without a compositor, off-screen pixels are refused too);
  - the GDK error-trap pop moved into a `finally`;
  - the `-Dtest` note (above, under F2).

  It confirmed the rest:
  - F1 has no producer or consumer in any form.
  - No assertion was weakened and no `@Test` removed: 118 annotations, 159 runs with inheritance.
  - The pom wiring is correct.
  - The FFM descriptors match the Xlib and GDK prototypes.
  - The size oracle is exactly `update_window_constraints`, and catches a glass that stops subtracting the extents,
    subtracts them twice or swaps the axes.
- **Findings of the extension, each fixed here or filed as a story:**
  - Fixed here, in code this story already changed:
    - The weak jslc tests (under F2 above). The extension's review found the first ones. Three more review rounds
      found the rest, among them `bracketted` and `badDigits`, which asserted nothing. The last round approved.
    - `JSLC`'s output-layout javadoc, a comment only, now matches `DEFAULT_INFO_MAP`:
      - both layouts list the Metal output (`impl/hw/mtl/msl`, and `decora-mtl/build/gensrc/` in the default one);
      - the default layout's paths lose a `../` prefix that the code never adds;
      - it says that they are under the out directory, or the working directory if there is none.
    - The `-Dtest` note is under F2 above.
    - The Linux gate's command (Acceptance gate above). This file and US-018's gate criterion said that the WSL runs
      passed `-Djfx.parity.require=true`; they never did.
  - Filed:
    - [US-015](US-015-keep-gtk-display-tests-off-the-developer-desktop.md): the GTK display tests change the
      developer's desktop. They run on whatever display `DISPLAY` names, WSLg's `:0` included, and this story's
      first Linux gate ran all 32 classes there before it unset `DISPLAY`.
      - `GtkScreenNativeTest` overwrites, then deletes, the window manager's `_NET_WORKAREA` and
        `_NET_CURRENT_DESKTOP`.
      - `GtkClipboardNativeTest` takes the clipboard.
      - `GtkCommonDialogsNativeTest` writes the account's file chooser settings and recent files.

      The opt-in decision that was open here is that story's first criterion.
    - [US-016](US-016-run-gtk-display-tests-in-ci-on-a-virtual-display.md): CI runs no GTK display test. It needs
      two runs on a private Xvfb, one with no window manager and one under openbox, because F3's failures depended on
      the window manager.
    - [US-017](US-017-keep-wm-frame-extents-out-of-undecorated-gtk-stages.md): glass applies a window manager's
      `_NET_FRAME_EXTENTS` to undecorated stages. On WSLg, Weston reports a decorated frame's extents for them, so
      such a stage's position is off by (38, 59) and its size is 76 x 97 px too large. Upstream has the same code.
    - [US-018](US-018-fix-jslc-metal-header-path-and-static-state.md): `MSLBackend` cuts its header directory out of
      the output path at the first `jsl-`, and keeps it and the Objective-C header in static fields. A path without
      `jsl-` throws, and a macOS build without `clean` after a `.jsl` edit leaves the Metal headers incomplete.
    - [US-019](US-019-reject-trailing-input-in-the-jsl-compiler.md): the JSL compiler parses `translation_unit`
      with no EOF, so it silently drops everything from the first token that cannot start a declaration: a stray
      `}` before `main` leaves the shaders without `main`, and the generator exits 0. The
      `ParserBase.assertAllInputConsumed` of F2 guards only the tests.
    - [US-020](US-020-stop-jsl-glue-blocks-swallowing-code.md): `GLUE_BLOCK` has been greedy since the ANTLR 4
      upgrade, so a glue block runs to the last `>>` of the program: a second block's closer, or a `>>` in a later
      comment. The code in between vanishes from every shader and reaches the Java peers as text.
    - [US-021](US-021-remove-unused-decora-generator-inputs.md): no build step calls the Decora drivers
      `CompileBoxBlur`, `CompileGaussian`, `CompileZoomRadialBlur` and `CompileExternal` or reads 5 of the `.jsl`
      files, and the runtime `ZoomRadialBlur` they could serve has no peer. Without the 9 files the generators write
      the same 1,059 files.
    - [US-022](US-022-report-jsl-errors-at-the-source-file-line.md): no JSL compiler error names a file. A syntax
      error gives the line of the program the generator assembled (line 76 of `Blend_ADD.jsl` is reported as
      `line 147`), and a semantic error gives no location at all.
    - [US-023](US-023-run-the-prism-shader-generator-once.md): the pom starts the Prism shader generator once per
      `.jsl` file, 30 JVMs in every build, and only the first writes anything: about 10 s of every Windows build.
    - [US-024](US-024-regenerate-shaders-when-the-jsl-tools-change.md): a build without `clean` after a change to the
      JSL compiler or a generator regenerates nothing, since only the `.jsl` source time counts.
