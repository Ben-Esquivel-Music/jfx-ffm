# US-022 — Report JSL errors at the source file and line

**Status:** 🔷 Implemented (2026-11-16, final macOS gate 2026-11-17; filed 2026-09-27); the Windows and WSL
gates, the Windows and Linux mutant proof of the file-closed test and filing the upstream issue are
[owner-run](#owner-run-and-pending) · **Found:** 2026-09-27, US-008 (filing US-019: with its end-of-input check
prototyped, a stray `}` on line 43 of `PaintImagePattern.jsl` was reported as `line 44:0`, with no file name)

## Story
As a developer who edits a `.jsl` shader,
I want every error of the JSL compiler to name the `.jsl` file and the line and column in it, and to read as a
mistake in the file rather than as a fault of the compiler,
so that I can find the mistake without knowing how the generators assemble programs from several files and from text
of their own.

## Problem
Paths are relative to `modules/javafx.graphics` unless they start with `backlog/`. A bare line number (`:NN`) refers
to the file named in the same sentence, else to the one named in the lead-in of its list. Line numbers are those of
`b5b00d4416`, the commit that filed the story, except in [A limit of the Java peer is an `InternalError`, thrown after
the shaders are written](#a-limit-of-the-java-peer-is-an-internalerror-thrown-after-the-shaders-are-written), which
uses the tree of US-018 at `ba2b68e991`, the fix for the first PR 50 review round.

This section, "Measured" and "Proposed fix" are kept as filed. Line numbers, and the statements listed in
[Drift since filing](#drift-since-filing), have changed since (2026-11-17).

### Most programs are assembled
The build runs the Decora and Prism generators (`pom.xml:156-216`). Every program goes through `JSLC.compile`, which
parses the text it is given (`src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java:131-247`). The build parses 264
programs, 52 Decora and 212 Prism (US-019 counted them). For line N of a `.jsl` file, the line in an error message is:
- **Decora `CompileJSL`** (`src/main/jsl-decora/CompileJSL.java:73-76`): 6 programs (`ColorAdjust`, `Brightpass`,
  `SepiaTone`, `PerspectiveTransform`, `DisplacementMap`, `InvertMask`), each a file as it is. Line N.
- **`CompileLinearConvolve`** (`src/main/jsl-decora/CompileLinearConvolve.java:57-75`): 24 programs, 12 from each of
  `LinearConvolve.jsl` and `LinearConvolveShadow.jsl` (11 unrolled sizes and one for the Prism peer), with each `%d`
  replaced by a number (`:60`, `:74`). Line N; the column shifts after a placeholder (`LinearConvolve.jsl:68`, `:75`).
- **`CompileBlend`** (`src/main/jsl-decora/CompileBlend.java:44-57`): 19 programs, `String.format` of `Blend.jsl` with
  one `Blend_<MODE>.jsl` in place of the `%s` on `Blend.jsl:72` (`CompileBlend.java:53`).
  - Line N of a mode file: N + 71.
  - Line N of `Blend.jsl`, from line 73 on: N plus the line count of the mode file (29 to 89). The first program
    compiled, and so the one that reports an error in `Blend.jsl`, uses `Blend_SRC_OVER.jsl`, which has 29 lines.
- **`CompilePhong`** (`src/main/jsl-decora/CompilePhong.java:39-65`, `:106-110`): 3 programs, one per light type. Two
  to four lines of the generator's own text replace the `%s` on `PhongLighting.jsl:122`, and two to six lines the
  `%s` on `:138`.
  - Lines 123 to 137: N + 1, and from 139 on: N + 2, for `DISTANT`, the light type compiled first. The other types add
    more lines.
  - The generator's own text has no file line at all.
- **Prism `CompileJSL`** (`src/main/jsl-prism/CompileJSL.java:353-409`): 212 programs, each the mask file, a newline,
  the paint text, a newline and a `main` the generator writes (`:392-405`). The `Solid` and `AlphaOne` masks and the
  `Color` paint are left out (`:361-377`). The text of a gradient paint is `PaintMultiGradient.jsl`, a newline and
  `PaintLinearGradient.jsl` or `PaintRadialGradient.jsl` (`:259-264`).
  - Line N of a mask file: N, since the mask comes first.
  - Line N of a paint file: N + 1. The first program compiled with each paint has no mask text; with a mask of M
    lines it would be N + M + 1.
  - Line N of `PaintLinearGradient.jsl` or `PaintRadialGradient.jsl`: N + 81 in the first program compiled with it
    (`PaintMultiGradient.jsl` has 79 lines).
  - One file is part of many programs: `PaintImagePattern.jsl` of 22, `PaintMultiGradient.jsl` of 132.
- The four Decora drivers the build did not call (`CompileBoxBlur`, `CompileGaussian`, `CompileZoomRadialBlur` and
  `CompileExternal`) compiled single files, three of them with placeholders replaced. US-021 deleted them.

### What a developer sees for a syntax error
- The generator's JVM ends with `Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException:
  line L:C <ANTLR message>` and a stack trace, and exits 1.
  - `ThrowingErrorListener` builds the message from the line and column alone
    (`src/jslc/java/com/sun/scenario/effect/compiler/ThrowingErrorListener.java:41`).
  - `JSLC.parse` reads the text with `CharStreams.fromStream` (`JSLC.java:152-169`, `:156`) and so gives it no source
    name, even when the text is a file (`JSLC.java:140-144`).
  - Through `GenAllDecoraShaders`, which calls each Decora generator by reflection
    (`src/main/jsl-decora/GenAllDecoraShaders.java:89-91`), it is a `java.lang.reflect.InvocationTargetException`
    with that exception as its cause.
- Nothing names the file, the program or the output being written. The last frames of the trace name the method of
  the generator, for example `CompileBlend.main(CompileBlend.java:56)` or
  `CompileJSL.compilePatternPaint(CompileJSL.java:295)`.
- Measured, with a line `    )` inserted into a function body (line numbers of the edited file; each case exits 1
  with `extraneous input ')' expecting {...}`):
  - `Brightpass.jsl:39`: `line 39:4`;
  - `LinearConvolve.jsl:75`: `line 75:4`;
  - `Blend_ADD.jsl:76`: `line 147:4`, and `Blend.jsl:77`: `line 106:4`;
  - `PhongLighting.jsl:127`: `line 128:4`, and `PhongLighting.jsl:142`: `line 144:4`;
  - `PaintImagePattern.jsl:35`: `line 36:4`, `MaskFillPgram.jsl:101`: `line 101:4`, `PaintMultiGradient.jsl:47`:
    `line 48:4`, and `PaintLinearGradient.jsl:44`: `line 125:4`.

  Seven of the ten are off, by 1 to 81 lines. The other three are right because their text starts the program.
- With US-019's option (a) prototyped, a `}` appended to `PaintImagePattern.jsl` (its line 43) gives `line 44:0`.
  Before US-019 that `}` was accepted without an error; US-019's change rejects it at the same `line 44:0`.

### Semantic errors carry no location at all
- Measured, with a line inserted into a function body, or an operator changed:
  - `    float u = undefinedVar;` at `Brightpass.jsl:39` and at `Blend_ADD.jsl:76`:
    `java.lang.RuntimeException: Unknown variable undefinedVar`;
  - `    float v = undefinedFunc(1.0);` at `PaintImagePattern.jsl:35`: `Unknown function undefinedFunc(float)`;
  - `i <= count` in the `unroll` loop of `LinearConvolve.jsl:75`:
    `Condition must be '<' in order to unroll 'for' loop (for now)`, from the HLSL backend.

  None has a line, a file or a program.
- The compiler has 58 `throw new RuntimeException` sites in 8 files and 12 `throw new InternalError` sites in 6
  files, and none adds a location. Several report mistakes in a `.jsl` file. Under
  `src/jslc/java/com/sun/scenario/effect/compiler`:
  - `tree/TreeMaker.java:108` (`Unknown variable`) and `:179` (`Unknown function`);
  - `model/SymbolTable.java:81` (`already declared`);
  - the six `unroll` restrictions of `backend/hw/SLBackend.java:216-249`;
  - the sampler limit of `backend/prism/PrismBackend.java:122`;
  - `backend/sw/java/JSWCallScanner.java:149` (`Nested function calls not yet supported`, an `InternalError`; see the
    next section).
- The visitor has the parse context wherever it calls `TreeMaker` or `SymbolTable` (`ctx.getStart()` gives line and
  column). The tree it builds keeps no position, so the backends, which scan the tree, have none to report.

### A limit of the Java peer is an `InternalError`, thrown after the shaders are written
- Measured on a copy of `Brightpass.jsl` with the function `float halve(float v)`, which returns `v * 0.5`, before
  `main`, and `sign(halve(luminance))` in place of `sign(luminance)` (line 46 of the edited file). The Decora
  `CompileJSL`, with the output types `GenAllDecoraShaders` passes (`-java -hw`) and `-d3d -es2 -mtl`, exits 1 with
  `Exception in thread "main" java.lang.InternalError: Nested function calls not yet supported` at
  `JSWCallScanner.visitCallExpr(JSWCallScanner.java:149)`, called from `JSLC.compile` (`JSLC.java:233`).
  - Before it, 3 of the 7 files were written: the `.hlsl`, `.frag` and `.metal`. They are right: identical to those
    of a run with shader outputs only, which exits 0, with the nested call in all three (`sign(halve(luminance))`; in
    Metal `sign(Brightpass_halve(sampler0, uniforms, luminance))`). Only the Java software peer (`-java`) cannot
    express it. The Prism peer, which comes after it (`JSLC.java:229-247`), is not written, and neither are the two
    Metal headers, which the generator writes only after the compile returns
    (`src/main/jsl-decora/CompileJSL.java:70-71`).
  - The file counts in this section are from a rerun on 2026-11-06, on macOS with JDK 27.0.1, with the compiler of
    US-018 at `ba2b68e991` (the fix for the first PR 50 review round) and the Decora `CompileJSL` given
    `-mtlheaders` as the pom gives it. Before US-018 the compiler wrote both Metal headers during the compile, so the
    run of 2026-09-27 wrote them before the error too.
  - `InternalError` is the `java.lang.Error` documented for an unexpected internal error of the JVM. This one has no
    location, and its "not yet" has been there since the compiler was open-sourced (`d0a313da5d`, 2012).
- 7 of the 12 `InternalError` sites (above) can be reached from a `.jsl` file. Each was reached with a small change to
  a copy of `Brightpass.jsl`, run as above: exit 1 after writing the same 3 shaders, each set identical to the shaders
  of a run with shader outputs only, which exits 0. Under
  `src/jslc/java/com/sun/scenario/effect/compiler/backend/sw/java`:
  - `JSWCallScanner.java:149`: the case above, and `sign(abs(luminance))`, a built-in function inside another;
  - `JSWTreeScanner.java:75` and `JSWCallScanner.java:265`, `Array access only supports variable expr/index (for
    now)`: `param float4 weights[2];` with `float w = weights[0].x;`, and with `float w = abs(weights[0].x);`;
  - `JSWTreeScanner.java:223`, `Empty return not yet implemented`: `if (luminance < 0.0) return;`;
  - `JSWTreeScanner.java:102`, `TBD`: `if (abs(val) == val) luminance = 0.0;`, a vector-valued call in a condition;
  - `JSWTreeScanner.java:314`, `TBD`: `if (val == val) luminance = 0.0;`, a vector in a condition;
  - `JSWCallScanner.java:357`, `TBD`: `luminance = abs((val.x + val).y);`.
- The other 5 check the compiler's own state, not the input (read, not measured). Under the same root:
  - `backend/hw/HLSLBackend.java:164`: the `default` of a switch over the 4 types whose base type is `INT`
    (`model/Type.java:38-41`), the only ones that reach it (`HLSLBackend.java:131`);
  - `backend/sw/java/JSWBackend.java:270`: `getFieldIndex` gets a character of a field selection, which the lexer
    limits to `rgba` or `xyzw` (`src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4:428-446`;
    `tree/TreeMaker.java:148` drops the dot), or the scanners' initial `'x'`;
  - `backend/sw/java/JSWFuncImpls.java:140` and `model/CoreSymbols.java:175` and `:181`: checks of the compiler's
    tables of built-in functions, in static initialisers (`JSWFuncImpls.java:52`, `CoreSymbols.java:60`) that run
    the same way for every input.

  The generators have one more, `src/main/jsl-decora/CompilePhong.java:112`, the `default` of a switch over the
  three light types it iterates (`:90`).
- The build passes `-java` for 8 of the 10 Decora shaders (`src/main/jsl-decora/GenAllDecoraShaders.java:56`,
  `:68-75`), so an edit to any of them can meet these limits. The programs compiled without `-java` (the Prism
  generator's and `LinearConvolve*`) never reach these sites.
- The 3 shaders written before the error do not hide it. Measured with one output directory reused without `clean`,
  as `target/gensrc` is, after a successful build of the original file:
  - the edited file: exit 1, the 3 shaders rewritten, the 2 headers and the 2 peers of the earlier build kept;
  - again, unchanged: exit 1, no file rewritten. The shaders are newer than the source, but the Java peer is older
    (or missing, after a failed clean build, also measured), so it is out of date (`JSLC.java:252-254`), the program
    is parsed and the backend fails again;
  - fixed by an edit (`float h = halve(luminance);` and `sign(h)`): exit 0, the 3 shaders and the 2 peers rewritten;
    the 2 headers keep their bytes and times, since the uniforms did not change;
  - only a fix that leaves the file older than the outputs goes unnoticed: the original restored with `cp -p` gives
    exit 0 with no file rewritten, so the shaders of the rejected program, which call `halve`, stay beside the peers
    of the earlier build. The up-to-date check treats any file restored with an older time that way, with or without
    an error.

### The single-file entry point can leave the file open
- `JSLC.compile(JSLCInfo, File)` (`JSLC.java:140-144`) opens a `FileInputStream` and passes it on. The stream is read,
  and closed by `CharStreams.fromStream` (it closes the channel in a try-with-resources, ANTLR 4.7.2 runtime), only
  when an output is out of date and the program is parsed (`JSLC.java:193-244`). When every output is up to date, it
  is not closed until a garbage collection finds it (its cleaner closes it) or the JVM exits.
- Callers (lines at `7a66c85e39`): the Decora `CompileJSL` (`src/main/jsl-decora/CompileJSL.java:83`), for 6 of the
  build's programs, and `JSLC.main` (`JSLC.java:473`, its call at `:489`), which the build does not run.
  `CompileExternal.java:44` in the same directory, which the build did not run either, was deleted by US-021.
  The other generators read their files with the `readFile` of either `CompileJSL`, which closes them
  (`src/main/jsl-decora/CompileJSL.java:42-65`, `src/main/jsl-prism/CompileJSL.java:432-455`).
- Measured with a fresh copy of `Brightpass.jsl` for each of 1,000 calls with its `.hlsl` output up to date and
  1,000 with it out of date, each call followed at once by the check:
  - Windows: up to date, `Files.delete` of the copy failed after all 1,000 calls (`The process cannot access the
    file because it is being used by another process`); out of date, after none. After `System.gc()` and a
    1-second pause, all 1,000 open copies could be deleted.
  - Linux (WSL, JDK 26.0.2, files on the Linux file system): up to date, `/proc/self/fd` still had a link to the copy
    after 996 and 998 of 1,000 calls in two runs (a garbage collection between the call and the check closed the
    others); out of date, after none. `Files.delete` succeeded while the copy was open.
- The effect is small: a build without `clean` keeps up to 6 `.jsl` files open until the generator's JVM exits, and
  on Windows they cannot be deleted meanwhile. Part 1 rewrites this method anyway.

### Tests that pin today's messages
- 9 parser tests pin a `line 1:0 ...` prefix, for example
  `src/test/jslc/com/sun/scenario/effect/compiler/parser/AssignmentExprTest.java:129`.
- 4 assertions pin a semantic message exactly: `src/test/jslc/com/sun/scenario/effect/compiler/SymbolTest.java:69`
  (`Unknown variable pos0`) and `AssignmentExprTest.java:72`, `:115` and `:121`.

### Upstream too
- `openjdk/jfx` master (`d46e6092ae`, checked 2026-09-27) has the same `ThrowingErrorListener.java`, `TreeMaker.java`,
  `JSLVisitor.java`, both `CompileJSL.java`, `CompileBlend.java`, `CompilePhong.java` and
  `CompileLinearConvolve.java`, identical to this tree's. Its `JSLC.parse` also calls `CharStreams.fromStream(stream)`
  (`JSLC.java:172` there). Its `JSLC.java` and `GenAllDecoraShaders.java` differ from this tree's only where the fork
  removed backends and rewrote the shader list.
- The `InternalError` sites and the open file are upstream too: `JSWCallScanner.java`, `JSWTreeScanner.java`,
  `JSWBackend.java`, `JSWFuncImpls.java`, `CoreSymbols.java` and `HLSLBackend.java` are identical there, and its
  `JSLC.compile(JSLCInfo, File)` is the same (`JSLC.java:156-160` there).
- JBS (searched 2026-09-27, project JDK, component `javafx`): no issue reports it. Searched: text `JSL` and the
  phrase `line number` (0 issues), `JSL` and `Unknown variable` (0), `JSL` and `Unknown function` (0), `JSL` and
  `location` (2, neither about errors), `ParseCancellationException` (0), `decora` and `syntax error` (0), `JSLC` and
  `error` (2, neither about error locations), `InternalError` and `JSL` (2, JDK-8114221 and JDK-8117916, neither
  about the compiler), `JSL` and `nested` (1, JDK-8088486, about HLSL compiler warnings). Without the component: the
  phrase `Nested function calls` (1, JDK-8297106, about JNI) and `JSLC` and `FileInputStream` (0).

### Measured (2026-09-27, Windows 10, JDK 26)
- The JSL compiler was compiled from a copy of `src/jslc` identical to the working tree, with the parser generated by
  ANTLR 4.7.2 (`-visitor`). The generators were compiled as in the Measured section of US-019.
- Each case copied the generator inputs, changed one line of one file and ran one generator: `CompileJSL`,
  `CompileLinearConvolve`, `CompileBlend` or `CompilePhong` with the arguments `GenAllDecoraShaders` passes and
  `-d3d -es2 -mtl`, or the Prism `CompileJSL` with `-d3d -es2 -mtl` once, as the first of the pom's 30 runs, which
  compiles all 212 programs. The `Brightpass.jsl` syntax case was also run through `GenAllDecoraShaders`.
- The source-name and line-marker probes below used the same compiler, with the lexer and parser called directly.
- The `InternalError` cases and the open-file probe used the classes of the US-008 Windows gate build
  (`buildtools/jslc/target/classes`, `target/jsl-compilers/decora`). The `InternalError` cases ran the Decora
  `CompileJSL` as the pom runs it, with the gate's `javafx.base` and `javafx.graphics` classes on the module path.
  The open-file probe called `JSLC.compile(JSLCInfo, File)` directly, on Windows and on Linux (WSL, no display).
- On 2026-11-06 the `InternalError` cases and the reused-output sequence were rerun on macOS (JDK 27.0.1) with the
  compiler of US-018 at `ba2b68e991` (the fix for the first PR 50 review round), the Decora `CompileJSL` given
  `-mtlheaders`; the file counts of that section are from the rerun.
- Not run for these two additions: a fixed compiler (nothing was prototyped), Maven; macOS only in the rerun above.

## Proposed fix
Two parts, both about where an error points.

### Part 1: a syntax error names the file and the line in it
- **Single files: a source name.** `CharStreams.fromString(text, name)` and `CharStreams.fromChannel(..., name, ...)`
  carry a source name, and an error listener reads it from `recognizer.getInputStream().getSourceName()`, for lexer
  and parser errors alike. Measured with ANTLR 4.7.2: `Brightpass.jsl:4:4: extraneous input ')' expecting {...}` and
  `Brightpass.jsl:2:0: token recognition error at: '@'`. `JSLC.compile(JSLCInfo, File)` and the formatted single
  files would pass the name. Every option below needs this too.
- **The single file is closed on every path.** The rewritten `JSLC.compile(JSLCInfo, File)` reads the file in full
  and closes it before the up-to-date checks, for example with `Files.readString`, then passes the text and the name.
- **Assembled programs,** three options; the choice is part of the story:
  - **(a) A source map.** The generators build each program with a helper that records, for each piece, its file (or
    the generator, for its own text) and the program line where it starts. `JSLC` takes the map with the text, and a
    listener created for each parse turns `line L:C` into the file and its line.
    - The program text stays byte-identical, so no output can change.
    - Cost: the generators assemble with `String.format` and `+` today (`CompileBlend.java:53`,
      `CompilePhong.java:108`, Prism `CompileJSL.java:263-264` and `:402-405`) and would split at the placeholders; a
      new `JSLC.compile` overload, also used by the Prism loader step (`CompileJSL.java:426`);
      `ThrowingErrorListener` is no longer a shared instance (`ThrowingErrorListener.java:35`, `JSLC.java:161`,
      `:167`).
  - **(b) Line markers.** A hidden lexer token `#line N "file"` sets the line, and the generators put one before each
    piece.
    - Prototyped without the file name, as the rule
      `LINE_MARK : '#line' [ \t]+ [0-9]+ [ \t]* '\r'? '\n' { setLine(...); } -> channel(HIDDEN) ;`. With ANTLR 4.7.2 a
      syntax error on the line after `#line 40` is reported at `line 40:4`, and a lexer error after `#line 7` at
      `line 7:0`. `Brightpass.jsl` with two markers writes 7 files identical to those of the unmarked shader. With the
      rule, all 1,059 files regenerate md5-identical, and the 159 jslc tests pass. Without it, `#` is a lexer error.
    - Cost: a grammar change, which upstream does not have. The program text changes: hidden tokens reach no
      output, which the md5 gate must show. The file name must travel with each token, since the parser looks ahead
      and the lexer can be past the next marker when an error is reported: a token factory, or a lookup from the
      token's start index to the marker before it.
  - **(c) Parse each piece alone first.** The generator parses each file, with one-line values in place of its
    placeholders, under its own name before it compiles the program, so a syntax error names the file and its line.
    - Cost: each piece must be a translation unit on its own. The mask, paint and mode files are; `Blend.jsl` and
      `PhongLighting.jsl` are once their placeholders are replaced. Two parses per program. The generator's own text
      and errors that exist only in the assembled program are not covered. Not prototyped.
    - It checks syntax only. The errors of Part 2 are raised when the assembled program is compiled, so for a piece
      after the first they keep the program's line, unless each piece also passes the semantic checks alone, with
      placeholder values that declare what it uses from other pieces (`Blend.jsl:78` calls the mode file's
      `blend_%s`).
- With any option, the message also names the program being compiled (`Solid_ImagePattern`, `Blend_ADD`,
  `PhongLighting_POINT`), since one file is part of up to 132 programs.

### Part 2: a semantic error carries a location
- In `JSLVisitor`, every error raised while a context is visited gets that context's start line and column, then the
  file of Part 1. This covers the errors of `TreeMaker`, `SymbolTable` and the visitor.
- The errors of the backends need a position in the tree: the node factories of `TreeMaker` store the line and column
  the visitor passes, at least for statements (`for`, `return`) and for the calls, array accesses and variable
  references the Java backend rejects (below).
- The 7 `InternalError`s a `.jsl` file can reach become errors of the input, of the same kind as the others of this
  part: the location of the construct, and a message that names it and the output that cannot express it, for
  example `Brightpass.jsl:46:23: nested function calls are not supported by the Java software peer (-java)`. They stay
  in the Java backend, since the shader backends compile these programs. The 5 unreachable sites stay
  `InternalError`s or become `AssertionError`s.
- Messages of inputs without a file: either the 4 pinned semantic messages change to the new form, or a program
  without a source name keeps today's text. Either way, the choice is recorded here.

## Acceptance criteria
- **Syntax errors name the file and its line.** For the ten inserted errors above, the generator's message names the
  file, its line and column and the program being compiled: `Brightpass.jsl:39:4`, `LinearConvolve.jsl:75:4`,
  `Blend_ADD.jsl:76:4`, `Blend.jsl:77:4`, `PhongLighting.jsl:127:4`, `PhongLighting.jsl:142:4`,
  `PaintImagePattern.jsl:35:4`, `MaskFillPgram.jsl:101:4`, `PaintMultiGradient.jsl:47:4` and
  `PaintLinearGradient.jsl:44:4`. Today they give lines 39, 75, 147, 106, 128, 144, 36, 101, 48 and 125 with no name.
  - A new jslc test in `src/test/jslc/com/sun/scenario/effect/compiler` (the surefire include,
    `buildtools/jslc/pom.xml:118`, picks it up) asserts the start of the message, file, line, column and cause
    included; ANTLR's expected-token list after the cause is not pinned, because it changes with the token vocabulary
    (US-019's code review, finding 4). It covers:
    - with every option, one named piece with an error on a line other than its first;
    - with (a) or (b), a program of two named pieces with an error in the second, on a line whose number in the
      program differs from its number in the piece.

    With (c), the generators parse the pieces, and the ten generator cases below cover them.
  - The ten generator cases are rerun as the pom runs the generators, and their output is kept with the change.
  - Amended 2026-11-17, as the gate logs were: each case's exit code, first exception lines and number of files
    written are kept in `US-022-generator-cases.md`; the whole output stays with the clones.
- **Generator text.** An error in the generator's own text names the generator and the program, for example after
  breaking `declPosVar` in a scratch copy of `CompilePhong`.
  - Amended 2026-11-17, after the independent review: met by an offending token inside the generator's text, case G02
    of [Generator runs](#generator-runs) (`flaot3` in `declPosVar` gives `<CompilePhong declPosVar>:2:6: in program
    PhongLighting_POINT:`). An error that the parser detects at the first token after a piece of text names the piece
    that holds that token, the accepted limitation of
    [the chosen option](#choice-option-a-a-source-map-by-character-offset): case G01, which drops the last `;` of
    `declPosVar`, names `PhongLighting.jsl:124:0`, the `void` after it.
- **The source file is closed on every path.** A jslc test calls `JSLC.compile(JSLCInfo, File)` 20 times with every
  output up to date and 20 times with one out of date, each time on a fresh copy of a `.jsl` file, and checks after
  each call that the copy is closed: on Windows by deleting it, on Linux by finding no link to it in `/proc/self/fd`.
  Elsewhere it is skipped with an assumption. Today the calls with every output up to date fail it on both systems.
  - It is not portable: deleting an open file succeeds on Linux (measured above), so the Windows check would pass
    there today, and macOS has neither check. CI runs the jslc tests on macOS too (US-008); this one is skipped
    there.
  - Amended 2026-11-17: the test has a macOS check as well, through the inodes of the open files that `/dev/fd`
    lists, so it runs on macOS too, and a third test covers the path where the compile throws. The Windows and Linux
    checks are written but were not run on the macOS host ([Tests](#tests),
    [Owner-run and pending](#owner-run-and-pending)).
  - 20 calls each, because a garbage collection between a call and its check closes the file: on Linux it did so
    after 2 to 4 of 1,000 calls.
  - Mutant proof: on Windows and on Linux, the test fails with today's `JSLC.compile(JSLCInfo, File)` in place of the
    new one. The calls with an output out of date pass on both versions.
- **Semantic errors carry a location.** The `Unknown variable`, `Unknown function` and `unroll` cases above report the
  file, line and column of the offending token or statement, and a jslc test pins each of the three messages in full.
  With (a) or (b), at least one of these tests has its error in a later piece.
- **Limits of the Java peer are errors of the input.** For the 8 inputs that reach the 7 `InternalError` sites
  above, the generator exits 1 with an error of the input, not an `InternalError`, that names the file, line and
  column of the construct and says that the Java software peer does not support it. A jslc test pins each full
  message and compiles each program with shader outputs only, which succeeds. Today each is an `InternalError`
  without a location.
- **Mutant proof,** as for every jslc negative test (`backlog/US-008-remove-dead-jslc-me-backend-and-simd.md:159-161`).
  In scratch copies, each new location test must fail:
  - with a valid program in place of the invalid one;
  - with an input that fails for another reason: a lexer error (`@`, `token recognition error at: '@'`) for a
    syntax test, another semantic error for a semantic test (`Unknown function` where `Unknown variable` is pinned);
  - with the error moved one line down in its piece;
  - if its error is in a later piece, with the mapping replaced by the identity, so that the program line is
    reported;
  - if it is a semantic test, with the position dropped where the error is raised: the context's in `JSLVisitor`,
    the node's for a backend error such as `unroll` (amended 2026-11-17: in `JSLVisitor` this mutant is equivalent
    for some tests, see [Mutant proofs](#mutant-proofs));
  - if it is a test of a Java-peer limit, with the `InternalError` of its site restored, and with the check moved out
    of the Java backend into the visitor (its shader-only compile then fails).

  The repository version passes.
- **Messages without a file.** The 9 parser tests that pin `line 1:0 ...` pass unchanged, and the 4 pinned semantic
  messages pass as decided under Part 2 (amended 2026-11-17: decided in [Message forms](#message-forms), a message
  without a file is always located, so the 4 assertions change, and so do 3 of `PrismProgramTest`, which US-020 added
  after this count was made).
- **Generated files unchanged.** Regenerate Decora and Prism with `-d3d -es2 -mtl` before and after the fix. All 1,059
  files are identical by name and md5.
- **Build gate,** with the two commands the US-008 gate ran, the Windows one with `-am` (amended 2026-11-17, as
  US-019's to US-021's owner-run gates have it: without it the build takes `javafx-base` from the local repository):
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`.
    The Linux font goldens were captured on another machine (see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,mtl-headers}` is identical by name and md5 before and after. On each system
    `jsl-decora` holds 108 files and `jsl-prism` 424, and there is no Metal header, so the Metal output is covered only
    by the `-d3d -es2 -mtl` regeneration above (amended 2026-11-17, as US-021's criterion was: `headers` and its 46
    files are dropped, because `9b02d6a602`, #57, removed the second-pass `javac -h` and the directory no longer
    exists).
  - `javafx.graphics` has the same test counts per class as before.
  - `javafx-jslc` runs its 276 tests plus the new ones, with no failure.
- **Upstream.** Repeat the JBS search when the story is picked up. Draft an upstream issue with the measured cases
  and the chosen option. The draft says that upstream runs no jslc test (JDK-8226635), so the fix's test needs that
  port too.

## Definition of Done
- All acceptance criteria met, with each gate's per-class list and `target/gensrc` md5 list kept with the change
  (amended 2026-11-17, as US-019's to US-021's were: the gate logs stay with the clones and are not kept; the kept
  files are listed in [Implementation](#implementation-2026-11-16-macos)).
- The option chosen for Part 1, (a), (b) or (c), and the reason recorded in this file.
- An independent review repeated the mutant proofs with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- The upstream issue draft kept outside the repository until it is filed.

## Notes
- Filed rather than fixed in US-008: US-008 removes dead code without changing behaviour, and this fix changes the
  compiler's error messages and the way the generators build programs.
- US-019 (trailing input) and US-020 (a glue block that ends early) add syntax errors, and US-020's check of glue in
  a Prism program adds an error of its own. All report the line of the assembled program, or none, until this story
  is done.
- The first error stops the generator and the build. For `Blend.jsl` and `PhongLighting.jsl` the offset therefore
  depends on the program compiled first, and an error in a file used by many programs is reported for the first of
  them only.
- The probe drivers, the edited inputs and the variant compilers were not kept in the repository. The Problem and
  Measured sections describe each repro fully enough to rebuild it.

## Implementation (2026-11-16, macOS)
The change is described against `726d7f74a5`, the baseline ("before"). Everything was built and run on one arm64 Mac:
macOS 27.2.1 (build 26B131), Apache Maven 3.10.0 and Oracle Corporation's OpenJDK 27.0.1.0.2 (build
27.0.1.0.2+1-2). This host has no Windows and no Linux, so those gates are [owner-run](#owner-run-and-pending). Paths
are relative to `modules/javafx.graphics`, as in Problem. Line numbers are those of the tree with the change unless a
commit is named.

A design of the change was reviewed before the implementation; what that review found and this story does not fix
went to US-122 and US-123 ([Not fixed here](#not-fixed-here)). The implementation and four new jslc test classes were
followed by an independent review in six lenses, each serious finding checked by a skeptic, then a rework, a
confirming review, three test pins and the final macOS gate. This section describes the final change;
[Independent review](#independent-review-2026-11-16) and
[Rework and confirming review](#rework-and-confirming-review-2026-11-16-and-2026-11-17) give the findings and what was
done with them.

Kept with the change:
- [`US-022-generator-cases.md`](US-022-generator-cases.md): the generator cases before and after, the regeneration
  with `-d3d -es2 -mtl` outside Maven and the macOS module gate, how each was run, and the md5 of the 31 Java files of
  the change;
- [`US-022-macos-per-class.tsv`](US-022-macos-per-class.tsv), the module gate's per-class list, before and after;
- [`US-022-macos-gensrc.md5`](US-022-macos-gensrc.md5), the after run's `target/gensrc` md5 list (the before run's is
  `cmp`-identical);
- [`US-022-regeneration.md5`](US-022-regeneration.md5), the after side's 1,059 files of the regeneration (the before
  side's is `cmp`-identical).

The scratch clones, the gate and build logs, the drivers, the mutant runs and the whole output of each generator case
are not kept. `US-022-generator-cases.md` keeps each case's exit code, first exception lines and number of files
written; it and the sections below describe each run fully enough to repeat it.

### Choice: option (a), a source map by character offset
The generators build each program from named pieces of text: files, and text that a generator writes itself. The
compiler turns a line and column of the program into a character offset, finds the piece that holds it and gives the
line and column in that piece, so the position is exact also after a placeholder in the middle of a line.
- **Why (a).** It needs no grammar change. The program text stays byte-identical, so no output can change: the
  confirming review dumped the 264 program texts of the regeneration on both sides, identical, and the 1,059
  generated files are md5-identical. Line and column are exact everywhere, and the same lookup serves Part 2.
- **Against (b), line markers.** Seven placeholders sit in the middle of a line (`Blend.jsl:78`,
  `LinearConvolve.jsl:68` and `:75`, `LinearConvolveShadow.jsl:71` and `:78`, `PaintLinearGradient.jsl:46`,
  `PaintRadialGradient.jsl:65`), so every column after them would stay wrong, and `PhongLighting.jsl:138` takes text
  of several lines in the middle of a line. (b) also changes `JSL.g4` and the program text, so the hidden tokens would
  have to be proven to reach no output.
- **Against (c), a parse of each piece alone.** It covers neither the generators' own text nor Part 2, whose errors
  are raised in the assembled program, and it costs a second parse per program.
- **The accepted limitation.** An error that the parser detects at the first token *after* a piece, such as a missing
  `;` or `}` at its end, names the piece that holds that token, as C compilers do across `#include`. Case G01 drops
  the last `;` of `<CompilePhong declPosVar>` and names `PhongLighting.jsl:124:0`, the `void` that follows; the
  review's run without the last `;` of `funcPosVar` names `<CompilePhong funcRgbConst>:1:0`. On a prototype made
  before the implementation, `MaskFillPgram.jsl` without its last `}` gave `<Prism CompileJSL main>:3:9` and
  `Blend_ADD.jsl` without its last `}` gave `Blend.jsl:74:9`. No hint is added.

### Message forms
- **A named source,** that is every program a generator compiles and every file that `JSLC.compile(JSLCInfo, File)`
  reads: `<piece>:<line>:<column>: in program <program>: <detail>`.
  - `<piece>` is a file's name (`Blend_ADD.jsl`) or, for text that a generator writes itself, a name in angle
    brackets that starts with the generator's (`<CompilePhong declPosVar>`, `<Prism CompileJSL main>`).
  - `<program>` is the `JSLCInfo`'s `peerName`, else its `shaderName` (`Blend_ADD`, `Solid_ImagePattern`,
    `PhongLighting_DISTANT`). With neither set, `in program <program>: ` is left out.
  - The line is 1-based and the column 0-based, as ANTLR counts them and as the criterion's `Brightpass.jsl:39:4`
    fixes them: only `\n` ends a line, a column is a code point, and `\r`, a tab or a form feed is one column.
  - `<detail>` is the text of before: ANTLR's message for a syntax error and the compiler's text for the others,
    except the new texts of [Behaviour changes](#behaviour-changes).
- **No source name,** that is `JSLC.compile(JSLCInfo, String, long)`, `getParserInfo(String)`,
  `getParserInfo(JSLCInfo, String)` and `parse(String)`, which the baseline's jslc tests use:
  `line <line>:<column> <detail>`, a syntax error's message of before byte for byte. It never names the program.
- **A message without a file is always located,** the choice that Part 2 left open, so that an error of the input
  carries its position whether its program has a name or not. A semantic error of a program without a source name,
  which had no position, is now `line L:C <text of before>`. The 9 parser tests that pin `line 1:0 …` pass unchanged.
  These assertions changed (lines at `726d7f74a5`):
  - `SymbolTest.java:69`: `Unknown variable pos0` became `line 3:11 Unknown variable pos0`;
  - `parser/AssignmentExprTest.java`: the constant at `:53`, which `:72`, `:115` and `:121` compare with, became
    `line 1:0 Left-hand side of assignment expression cannot be const variable`;
  - `PrismProgramTest.java`, which US-020 added after the story counted 4 such assertions: its three
    `assertThrowsExactly(RuntimeException.class, …)` (`:71`, `:89`, `:124`) expect a `JSLSourceException`, and its
    constant at `:59`, `A Prism program cannot have a glue block: Probe`, became
    `line 1:0 A Prism program cannot have a glue block` for `:74` (run once per output type, 5 times) and `:127`, and
    `line 3:0 A Prism program cannot have a glue block` for `:92`.

  So the unnamed glue-block message no longer names the shader. The named form names the program in its prefix
  (`Helper.jsl:4:4: in program Probe_Helper: A Prism program cannot have a glue block`).
- **Exception types.**
  - A syntax error stays an `org.antlr.v4.runtime.misc.ParseCancellationException`.
  - Every other error of the input is the new `com.sun.scenario.effect.compiler.JSLSourceException`, a
    `RuntimeException`: the errors that the visitor, `TreeMaker`, `SymbolTable` and the tree nodes raise, whose
    `RuntimeException` is kept as the cause; the errors of the backends; the Prism glue-block check; and the 7
    reachable limits of the Java peer.
  - A mistake in a generator's template that `JSLSource.format` finds is an `IllegalArgumentException` whose message
    starts with the template's piece and the line and column of its `%`, or that names the piece for a wrong number of
    arguments. `format` on an unnamed or assembled source is an `IllegalStateException`.
  - Nothing suppresses the class or the stack trace in the generator's output. M01 to M03 also print a `Caused by:`
    line with the original `RuntimeException` of `TreeMaker`.

### Change
Relative to `726d7f74a5`, 24 Java files are edited and 7 added, 3 of them production code and 4 test classes. Line
counts are `wc -l`; no production file is over 900 lines.

New, in `src/jslc/java/com/sun/scenario/effect/compiler`:
- `JSLSource.java` (262 lines), public and final: a program's text and, for each position in it, the named piece it
  comes from. It is public because the generators are in the unnamed package.
  - `unnamed(String)`, `named(String name, String text)`, `file(File)` and `text()`.
  - `format(Object...)`: the receiver, one named piece, is a template whose only conversions are `%s` and `%d`. `%s`
    takes a `String`, inserted as it is, or a named `JSLSource`, single or assembled, inserted with its own map; `%d`
    takes an `Integer`, inserted as `Integer.toString(value)`. The text equals
    `String.format(Locale.ROOT, template, args)` with each `JSLSource` argument replaced by its `text()`. A position
    inside an inserted string or number maps to its placeholder, an empty argument owns no position, and the end of
    the text maps to the end of the template.
  - `describe(line, column, programName, detail)`, package-private, the one place that formats a located message. It
    turns the line and column into a character offset of the program, finds the piece that holds it and counts that
    piece's line and column by the same rules.
- `JSLSourceException.java` (70 lines), public and final: a `RuntimeException` with a line, a column and a detail.
  Its message is `line L:C detail` until `JSLC` locates it with the program's `JSLSource` and name (`locate`,
  package-private), which keeps the original stack trace. Besides a constructor that takes a line and a column, with
  or without a cause, it has one that takes a `SourcePosition`, for the 16 throws at a node's position.
- `tree/SourcePosition.java` (32 lines): a record of a 1-based line and a 0-based column.

Changed, in the same directory:
- `JSLC.java` (511 lines).
  - New public `compile(JSLCInfo, JSLSource, long)` and `getParserInfo(JSLCInfo, JSLSource)`. The signatures that
    take a `String` stay and give an unnamed source. `compile(JSLCInfo, File)` takes `file.lastModified()`, reads the
    file with `JSLSource.file` and compiles it.
  - The text reaches the lexer through `CharStreams.fromString(source.text())`. The `InputStream` plumbing is gone,
    with `getParserInfo(InputStream)`.
  - Each parse attaches a `ThrowingErrorListener` of its own, with the source and the program name (`peerName`, else
    `shaderName`), to the lexer and to the parser.
  - Every `JSLSourceException` that leaves `compile` or `getParserInfo(JSLCInfo, …)` is located. `compile` and
    `getParserInfo` catch no other type.
  - The Prism glue-block check throws a `JSLSourceException` at the first glue block, with
    `A Prism program cannot have a glue block`, still before any output is written.
  - Unchanged: the order of the outputs, the lazy parse, what is written before an error, the up-to-date checks, the
    Metal headers and every caller's source time.
- `ThrowingErrorListener.java`: a package-private constructor that takes the source and the program name; the message
  comes from `describe`. `INSTANCE` keeps its declaration and its messages (an unnamed source), and the special case
  of an unterminated comment stays.
- `tree/JSLVisitor.java` (742 lines before, 869 now). Each of its 44 `visitX` overrides runs its body inside one
  helper, `locating(ctx, …)`:
  - an exception whose class is exactly `RuntimeException`, as the visitor, `TreeMaker`, `SymbolTable` and the tree
    nodes throw for a mistake in the input, becomes a `JSLSourceException` at the start of the context, with the
    original as its cause;
  - a `JSLSourceException` passes through, so the innermost context wins;
  - any other type passes unchanged, so that a fault of the compiler does not read as a mistake in the file.

  The visitor passes positions to the seven `TreeMaker` factories below; the three carriers that it builds only to pass
  values get none. The rest of its diff is the re-indentation, the declarator rule and the three crash fixes of
  [Behaviour changes](#behaviour-changes).
- `tree/TreeMaker.java` and seven node classes: `VariableExpr`, `CallExpr`, `ArrayAccessExpr`, `ReturnStmt`,
  `ForStmt`, `VarDecl` and `GlueBlock` hold the `SourcePosition` of their start token, which their factory takes; a
  `VarDecl`'s is its declarator's identifier. `TreeMaker`, `SymbolTable` and the node constructors keep their texts
  and their plain `RuntimeException`.
- The backends throw a `JSLSourceException` at a node's position:
  - `backend/hw/SLBackend.java`: the six `unroll` restrictions, at the loop (the inner one of nested loops);
  - `backend/prism/PrismBackend.java`: the sampler limit, at the third `param` sampler declarator in source order. It
    now records the declarators of `param` sampler variables while it scans;
  - `backend/sw/java/JSWCallScanner.java` (3 sites) and `JSWTreeScanner.java` (4 sites, and
    `Return statement not expected`): the 7 reachable `InternalError`s, with the texts of
    [Behaviour changes](#behaviour-changes).

The generators, in `src/main/jsl-decora` and `src/main/jsl-prism`. The program text is byte-identical and every source
time is taken as before.
- Decora `CompileJSL.java`: `readFile` and `readStream` are deleted; it still calls
  `JSLC.compile(jslcinfo, jslcinfo.getJSLFile())`.
- `CompileBlend.java`: `JSLSource.file` of `Blend.jsl`, read once before the mode loop as before, then
  `main.format(JSLSource.file(funcFile), funcname)` for each mode.
- `CompileLinearConvolve.java`: `base.format(peersize/4, peersize/4)` and `base.format(peersize, 0)`.
- `CompilePhong.java`: the eight text constants are `JSLSource` pieces named `<CompilePhong declPosConst>` and so on
  (`<CompilePhong declRgbConst>` is empty), joined in pairs by a piece `<CompilePhong>` whose text is `%s\n%s`.
- Prism `CompileJSL.java`: a shader's source is a `JSLSource`. A gradient paint is the piece
  `<Prism CompileJSL gradient paint>` (`%s\n%s`) with `PaintMultiGradient.jsl` and the formatted paint file; a program
  is the template that the generator writes, `<Prism CompileJSL main>`, with the mask, the paint and their calls; an
  omitted mask or paint is an empty named piece (`<Prism CompileJSL omitted mask>`,
  `<Prism CompileJSL omitted paint>`). The loader's fallback parse is `JSLC.getParserInfo(jslcinfo, source)`.
  `readFile` and `readStream` are deleted.
- `GenAllDecoraShaders.java`: it rethrows the cause of the `InvocationTargetException` of its reflective call, an
  `Exception` or an `Error`. The wrapper has no message of its own, so the generator's exception would show only on a
  `Caused by:` line.

Tests: the assertions of [Message forms](#message-forms) in `SymbolTest.java`, `parser/AssignmentExprTest.java` and
`PrismProgramTest.java`, and four new classes ([Tests](#tests)).

Style, checked on every changed or new line by the review, the rework and the confirming review: no tab, CR,
trailing blank, `var`, preview feature or wildcard import, no line over 120 columns, every file ends in LF, and the
headers carry 2026. Lines of the baseline in changed files are left as they were, since nothing unrelated is
reformatted: `ThrowingErrorListener.java:48`, the unchanged `syntaxError` signature, is 150 columns;
`TreeMaker.java:41`, `JSWTreeScanner.java:32` and `JSWCallScanner.java:50` are wildcard imports; `PrismBackend.java`
imports `InputStreamReader` and `Reader`, and the Prism `CompileJSL.java` `JSLParser` and `java.io.Reader`, without
using them.

### Behaviour changes
Measured with the generator cases ([Generator runs](#generator-runs)), the jslc tests ([Tests](#tests)) and the
reviews' drivers.
- **Every error of the input is located** ([Message forms](#message-forms)). Case S03 gives
  `Blend_ADD.jsl:76:4: in program Blend_ADD: extraneous input ')' expecting …` where the baseline gave
  `line 147:4 extraneous input ')' expecting …`, and case M01 gives
  `Brightpass.jsl:39:14: in program Brightpass: Unknown variable undefinedVar` where the baseline gave
  `java.lang.RuntimeException: Unknown variable undefinedVar`. The token each error reports, every row pinned by a
  test:

  | Error | Reported at |
  | --- | --- |
  | A syntax error | the token the parser reports, as before |
  | `Unknown variable x` | the identifier |
  | `Unknown function f(…)`; the number of a constructor's arguments | the function's or the type's name |
  | `Expressions must have compatible result types …` | the start of the expression that combines the operands |
  | A check on one declarator: `already declared`, the array size, `const`, the initializer's type | that declarator's identifier |
  | An `unroll` restriction | the `unroll`; of nested loops, the inner one |
  | The sampler limit of the Prism peer | the third sampler declarator in source order |
  | `Return statement not expected`; a `return` without a value in the Java peer | the `return` |
  | A glue block in a Prism program | the first glue block's `<<` |
  | A limit of the Java peer | its construct: the inner call, the array access, the vector variable or the call with a vector result |
  | An integer constant too large; an array type | the literal or the `unroll`; the start of the type |

- **The 7 reachable `InternalError`s of the Java peer are errors of the input** at their construct, raised where they
  were, after the 3 shaders are written:

  | Site (lines at `726d7f74a5`) | Before | After |
  | --- | --- | --- |
  | `JSWCallScanner.java:149` | `java.lang.InternalError: Nested function calls not yet supported` | `nested function calls are not supported by the Java software peer (-java)`, at the inner call |
  | `JSWTreeScanner.java:75`, `JSWCallScanner.java:265` | `InternalError: Array access only supports variable expr/index (for now)` | `array access other than variable[variable] is not supported by the Java software peer (-java)` |
  | `JSWTreeScanner.java:223` | `InternalError: Empty return not yet implemented` | `return without a value is not supported by the Java software peer (-java)` |
  | `JSWTreeScanner.java:102` | `InternalError: TBD` | `the Java software peer (-java) does not support the vector result of abs(...) here: it cannot tell which component to use`, with the called function's name |
  | `JSWTreeScanner.java:314`, `JSWCallScanner.java:357` | `InternalError: TBD` | `the Java software peer (-java) does not support vector 'val' here: it cannot tell which component to use`, with the variable's name |

  The checks stay in the Java backend, because the shader backends compile these programs: in the visitor the same
  check would fail the shader-only compile, and in `JSLC` ahead of the outputs it would leave no shader written
  ([Mutant proofs](#mutant-proofs)).
- **Three crashes of the visitor are errors of the input:**

  | Input | Before | After |
  | --- | --- | --- |
  | `float3()`, a constructor without arguments | `NullPointerException` in `TreeMaker.vectorCtor` | `Number of constructor args must match size of float3`, at the type name |
  | `int n = 2147483648;`, `unroll (2147483648, 0)`, `unroll (4, 2147483648)` | `NumberFormatException` | `integer constant 2147483648 is too large (the largest is 2147483647)`, at the literal or the `unroll` |
  | `float[2] w;`, and `float[2]` as a parameter type, as a return type or in a constructor | `IllegalArgumentException: No enum constant …FLOAT[2]` | `array types are not supported: float[2]`, at the start of the type |

- **The declarator rule.** Every check on one declarator reports that declarator's identifier, not the start of the
  declaration: in `float a = 1.0, a = 2.0;` the second `a`. For the first declarator, the array-size checks, the two
  `const` checks, declaring the variable and the check of its initializer run under it. A further declarator gets two
  of these, declaring the variable and the check of its initializer, as before; it gets no array-size or `const`
  check, before or after the change (US-123).
- **`%d` in ASCII digits,** an intended fix. `JSLSource.format` inserts a number as `Integer.toString(value)`, ASCII
  digits in every locale, the text of `String.format(Locale.ROOT, …)`. The generators' `String.format` used the
  default locale, and where that gives other digits today's build already fails: with the baseline compiler and the
  default locale `ar-EG`, the first `LinearConvolve` program does not lex
  (`line 68:21 token recognition error at: '١'`; the program was parsed, the generator was not run under that
  locale). So every build that works today gets the same bytes. `aNumberIsInsertedInAsciiDigitsWhateverTheDefaultLocale`
  pins it under a default locale with Thai digits.
- **Decoding.** `JSLSource.file` decodes a file as UTF-8 and replaces malformed input, as the single-file path did
  through `CharStreams.fromStream`; `Files.readString` would reject it. The generators decoded their files with the
  default charset, so they differ only for non-ASCII bytes under a non-UTF-8 default charset; the 57 shipped `.jsl`
  files are ASCII. `aByteOfAFileThatIsNotUtf8IsReplacedRatherThanRejected` and
  `aFileWithAByteThatIsNotUtf8InACommentCompiles` pin it.
- **The read goes through `FileInputStream`,** in try-with-resources, not through `Files`. On the macOS host
  `Files.readAllBytes` of a directory named `Dir.jsl` gives `java.io.IOException: Is a directory`, without the path,
  where `FileInputStream`, as at the baseline, gives `java.io.FileNotFoundException: <path> (Is a directory)`. A
  missing and an unreadable file give the baseline's `FileNotFoundException` messages too, `(No such file or
  directory)` and `(Permission denied)`, measured through `JSLC.compile(JSLCInfo, File)` on both compilers; `JSLC.main`
  rejects a missing file with `Input file not found: missing.jsl` before any read, as before.
  `aDirectoryInPlaceOfAFileIsReportedWithItsPath` pins the directory case.
- **The time first, then the read, then the checks.** `JSLC.compile(JSLCInfo, File)` takes `file.lastModified()`,
  then reads the whole file and closes it, and only then runs the up-to-date checks, so the file is closed when the
  call returns or throws, whether or not an output is out of date. The time comes first so that a file rewritten
  during the read cannot look older than its outputs. A comment says so, since no test in one thread can tell the two
  orders apart. Before, the stream stayed open until a garbage collection when every output was up to date. The
  generators that read their own files keep their order, the read before the time.
- **`GenAllDecoraShaders`** prints the generator's own exception as its first line (S01g, J01g), where the baseline
  printed a `java.lang.reflect.InvocationTargetException` with it as the cause. An `Error` is rethrown too: a recursive
  function in a copy of `Brightpass.jsl` (US-122's C6) gave an `InvocationTargetException` caused by a
  `StackOverflowError` before and a `StackOverflowError` now. The exit code, 1, and the files written are the same.
- **Two public members are removed,** which nothing in the tree uses: `JSLC.getParserInfo(InputStream)` and the
  implicit public constructor of `ThrowingErrorListener`, whose only constructor is now package-private. Code that
  calls either would no longer compile; the upstream draft names both.
- **Texts kept on purpose,** although imprecise: `Return statement not expected`, the texts of the six `unroll`
  restrictions, three of which end in `(for now)`, and `Must use zero, one, or two samplers (for now)`. This story
  changes where an error points, not what it says, and its criteria pin one of these texts in full
  (`Condition must be '<' …`).
- **What does not change.** The program text and so every generated file; the order of the outputs; the lazy parse;
  what is written before an error (the 3 shaders before a Java-peer error, the files of the programs compiled before
  a failing one); the up-to-date checks; the Metal headers; the exit codes; and the text after the location, which
  in every generator case is byte-identical to the baseline's message ([Generator runs](#generator-runs)).

### Not fixed here
- Defects that the design review found and that this story leaves as they were, with no location and the same
  exception, are filed:
  - [US-122](US-122-stop-the-jsl-java-software-peer-crashing-or-writing-wrong-code.md): more crashes of the Java
    software peer for programs that the shader backends compile (an empty statement `;;`, `length` and `any`,
    `sample((img), …)`, a field of a vector constructor and a vector argument of one, a recursive function),
    overloads inlined by name, and peers that `javac` rejects or that compute something else;
  - [US-123](US-123-stop-the-jsl-compiler-dropping-the-conditional-operator-and-accepting-duplicate-functions.md):
    the conditional operator `?:`, which the visitor drops, a second function that is accepted when only its parameter
    names or its return type differ, and, found by the confirming review, a further declarator of a declaration that
    skips the array-size and `const` checks (`float g1, g2[1.5];` and `const float c1 = 1.0, c2;` compile, before and
    after the change).
- Found while US-122 was measured (J06 when US-124 was), and filed as
  [US-124](US-124-stop-the-jsl-compiler-writing-metal-shaders-that-the-metal-compiler-rejects.md): `.metal` files that
  the Metal compiler rejects although the JSL compiler, with the shader outputs only, accepts their programs without
  an error: those of US-122's C3, C5 and O2 and of the lines of Java-peer cases J06 and J07
  ([Known effects](#known-effects)).
- The review ran 26 inputs of the two stories on both compilers: the same exception class, message and written files
  as the baseline, except that the errors of the input among them are located. The final gate ran three of them
  through `JSLC.compile(JSLCInfo, String, long)`, so in the unnamed form, on both compilers:
  - US-122's O2, an overload `g(float2)` that the program does not call, with `if (x == x)` in its body, is
    `JSLSourceException: line 6:8 the Java software peer (-java) does not support vector 'x' here: it cannot tell which
    component to use`, where the baseline threw `java.lang.InternalError: TBD` at `JSWTreeScanner.java:314`: one of
    the 7 sites, reached in a function that the program does not call;
  - US-123's T3 and D0 keep their texts and gain `line 4:10` and `line 5:0`;
  - the files written are md5-identical before and after.
- Unchanged on purpose: the 5 `InternalError` sites that check the compiler's own state, not the input
  (`HLSLBackend.java:164`, `JSWBackend.java:270`, `JSWFuncImpls.java:140`, `CoreSymbols.java:175` and `:181`), and the
  `default` of `CompilePhong`'s switch over the light types (`CompilePhong.java:115`, `:112` at `726d7f74a5`).

### Drift since filing
What the filed text says against what is true at `726d7f74a5`, or was measured on 2026-11-16 and 2026-11-17:

| Filed text | Says | Now |
| --- | --- | --- |
| Measured; the cases | Windows 10, JDK 26 | The runs of 2026-11-16 and 2026-11-17 are on the macOS host with JDK 27.0.1.0.2. There, at `726d7f74a5`, the ten syntax cases, the stray `}`, the semantic cases and the 8 Java-peer inputs give the lines and messages that the story measured (the Before column of [Generator runs](#generator-runs)) |
| Semantic errors carry no location at all | 58 `throw new RuntimeException` sites in 8 files | 59 in 8 files at `b5b00d4416` (`git grep`, counted on 2026-11-16). The 12 `InternalError` sites in 6 files are right |
| Tests that pin today's messages | 4 assertions pin a semantic message exactly | US-020 (`18f5ac5bda`) added `PrismProgramTest`, whose 3 message assertions pin the glue-block message ([Message forms](#message-forms)) |
| Acceptance criteria, Build gate | `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}`: 108, 424, 46 and 0 files | `9b02d6a602` (#57) removed `headers`. The macOS module gate, which builds `-mtl -es2`, holds 158, 636, no `headers` and 3 files, before and after; Windows and Linux are to hold 108 and 424 and no Metal header. The criterion is amended |
| Acceptance criteria, Build gate | The Windows command without `-am` | Amended, as US-019's to US-021's owner-run gates have it |
| Acceptance criteria, file closed | macOS has neither check, so the test is skipped there | The test has a macOS check (amended) |

Line references, as `git show 726d7f74a5:<file>` shows them; those of the `InternalError` section are at
`ba2b68e991`:

| Reference | Filed | At `726d7f74a5` |
| --- | --- | --- |
| `pom.xml`, the generator steps | `:156-216` | `:140-204` |
| `JSLC.java`, `JSLC.compile` | `:131-247` | `:148-273` |
| `JSLC.java`, `compile(JSLCInfo, File)` | `:140-144` | `:157-161` |
| `JSLC.java`, `parse`; `CharStreams.fromStream` | `:152-169`; `:156` | `:169-186`; `:173` |
| `JSLC.java`, `ThrowingErrorListener.INSTANCE` on the lexer and the parser | `:161`, `:167` | `:178`, `:184` |
| `JSLC.java`, the backends and their lazy parses | `:193-244` | `:210-270` |
| `JSLC.java`, the Java and Prism peers; the Java backend | `:229-247`; `:233` | `:252-270`; `:256` |
| `JSLC.java`, `outOfDate` | `:252-254` | `:275-277` |
| `ThrowingErrorListener.java`, `INSTANCE`; the message | `:35`; `:41` | `:36`; `:44` |
| `GenAllDecoraShaders.java`, the reflective call | `:89-91` | `:94-96` |
| Decora `CompileJSL.java`, `readFile`; the 6 programs | `:42-65`; `:73-76` | `:43-66`; `:81-84` |
| `CompileLinearConvolve.java` | `:57-75`, `:60`, `:74` | `:58-76`, `:61`, `:75` |
| `CompileBlend.java` | `:44-57`, `:53` | `:52-65`, `:61` |
| `CompileBlend.java`; Prism `CompileJSL.java`, the stack frames of What a developer sees | `:56`; `:295` | `:64`; `:296` |
| `CompilePhong.java` | `:39-65`, `:106-110`, `:108` | `:40-66`, `:114-118`, `:116` |
| Prism `CompileJSL.java`, `getFullSource`; the `main` | `:353-409`; `:392-405` | `:354-410`; `:393-406` |
| Prism `CompileJSL.java`, the omitted mask and paint; the gradient | `:361-377`; `:259-264`, `:263-264` | `:362-378`; `:260-265`, `:264-265` |
| Prism `CompileJSL.java`, the `String.format`; the loader's parse; `readFile` | `:402-405`; `:426`; `:432-455` | `:403-406`; `:427`; `:433-456` |

Still true at `726d7f74a5`: the other references of the `InternalError` section, `JSL.g4:428-446`, and every line of
`TreeMaker.java`, `SymbolTable.java`, `SLBackend.java`, `PrismBackend.java`, the `JSW*` scanners, `HLSLBackend.java`,
`JSWBackend.java`, `JSWFuncImpls.java`, `CoreSymbols.java`, `Type.java`, the cited tests and `buildtools/jslc/pom.xml`,
files that did not change between the two commits.

### Acceptance criteria and evidence
| Criterion | Evidence | State |
| --- | --- | --- |
| Syntax errors name the file and its line | The ten cases S01 to S10 name the file, line, column and program, where the baseline gave lines 39, 75, 147, 106, 128, 144, 36, 101, 48 and 125 without a name ([Generator runs](#generator-runs)). `aSyntaxErrorInAFileNamesTheFileItsLineAndTheProgramAndWritesNoFile` pins a named piece with its error on line 4, `Piece.jsl:4:4: in program Probe: extraneous input ')' expecting `, and `aSyntaxErrorInALaterPieceNamesThatPieceAndItsLine` a second piece, `Helper.jsl:3:4`, program line 4, each up to `expecting `. The cases' exit codes, first exception lines and numbers of files written are kept in `US-022-generator-cases.md`; their whole output stays with the clones | Met, as amended |
| Generator text | G02: `<CompilePhong declPosVar>:2:6: in program PhongLighting_POINT: no viable alternative at input 'param flaot3'`, and `aSyntaxErrorInGeneratorTextNamesTheGeneratorPiece`. G01, the accepted limitation, names `PhongLighting.jsl:124:0` | Met, as amended |
| The source file is closed on every path | `SourceFileClosedTest`, 3 tests of 20 calls each, every output up to date, one out of date and a compile that throws: 3 / 0 / 0 / 0 on macOS, not skipped. With the baseline's `compile(JSLCInfo, File)` emulated, the up-to-date test fails with the copy open after 20 of 20 calls and the other two pass ([Mutant proofs](#mutant-proofs)) | Met on macOS; the Windows and Linux checks and their mutant proof [owner-run](#owner-run-and-pending) |
| Semantic errors carry a location | M01 `Brightpass.jsl:39:14`, M02 `Blend_ADD.jsl:76:14`, M03 `PaintImagePattern.jsl:35:14`, M04 `LinearConvolve.jsl:75:4`. Pinned in full: `anUnknownVariableInALaterPieceIsReportedAtTheVariableInThatPiece` (a later piece, `Helper.jsl:2:14`), `anUnknownFunctionAfterAPlaceholderIsReportedAtItsColumnInTheTemplate` (`Kernel.jsl:3:37`, program column 38) and `anUnrollConditionOtherThanLessThanIsReportedAtTheLoop` (`Kernel.jsl:6:4`, with the HLSL, GLSL and Metal outputs) | Met |
| Limits of the Java peer are errors of the input | J01 to J08 exit 1 with a `JSLSourceException` at the construct after writing the 3 shaders, and J01s to J08s, with the shader outputs only, exit 0 with the same shaders. `JavaPeerLimitTest` pins the 8 full messages, the 3 files written and the shader-only compile | Met |
| Mutant proof | Every mutant that the criterion lists fails its tests as they stood at 389 tests, also in the review's own form; the position dropped in a single `visitX` is equivalent for some tests and killed for the others ([Mutant proofs](#mutant-proofs)). The 16 tests added or changed since (rows 15 to 27, `aSemanticErrorInAFileNamesTheFileItsLineAndTheProgram` and the two glue tests) each fail under the mutant they pin, and each fails under the valid-program, other-cause and one-line-down mutants, run on them on 2026-11-17: 48 of 48; the identity map and the dropped position were not run again on them | Met |
| Messages without a file | The 9 parser tests pass unchanged; the assertions of [Message forms](#message-forms) changed as decided | Met, as amended |
| Generated files unchanged | Outside Maven with `-d3d -es2 -mtl`, 29 JVMs exit 0 before and after, and the 1,059 files are identical by name and md5 ([md5 list](US-022-regeneration.md5)) | Met |
| Build gate | macOS, with `-Dmaven.test.failure.ignore=true`, before and after ([Final gates](#final-gates-2026-11-17-macos)), extra evidence: jslc 276 and 405 tests, no failure. Windows (with `-am`) and WSL: [owner-run](#owner-run-and-pending) | Passed on macOS (extra evidence); Windows and WSL owner-run |
| Upstream | [Upstream check](#upstream-check-2026-11-16); the draft issue, kept outside the repository, says that upstream runs no jslc test (JDK-8226635) | Checked and drafted; filing pending |

The Definition of Done's other items: the option and the reasons are in
[Choice](#choice-option-a-a-source-map-by-character-offset); the independent review repeated the mutant proofs with
its own inputs ([Mutant proofs](#mutant-proofs)); the draft stays outside the repository; the status is ✅ Done only
after the owner's commit, the PR and the two gates ([After the gates](#after-the-gates)).

### Tests
`mvn -B -ntp -o -pl buildtools/jslc clean test` on the final tree (2026-11-17): 405 tests, 0 failures, 0 errors, 0
skipped, no `[ERROR]` or `[WARNING]` line; that is 276 + 129 tests in 31 + 4 classes. The 31 classes of the baseline
keep their counts, among them `PrismProgramTest` 13, `SymbolTest` 1 and `parser.AssignmentExprTest` 7. The new
classes are in `src/test/jslc/com/sun/scenario/effect/compiler` and extend `ProgramBase`; every expected line and
column was counted by hand from the input.
- **`SourceLocationTest`, 45 tests.**
  - Through `JSLC.compile(JSLCInfo, File)`: a syntax error, `Piece.jsl:4:4: in program Probe: extraneous input ')'
    expecting `, with no file written; a semantic error, `Piece.jsl:3:14: in program Probe: Unknown variable
    undefinedVar`; an error at the end of the file, `Piece.jsl:5:0: … extraneous input '<EOF>' expecting `; a
    directory named `Dir.jsl`, a `FileNotFoundException` whose message holds the directory's path; and a file with
    the byte 0xFF in a comment, which compiles.
  - In a second piece, `Helper.jsl`, which starts on line 2 of the program `Probe_Helper`: a stray `)`
    (`Helper.jsl:3:4`), a stray `}` after the last declaration (US-019, `Helper.jsl:4:0`) and a glue block that ends
    early (US-020, `Helper.jsl:7:0`), each pinned up to `expecting `; a character `@` and an unclosed comment, in full
    (`Helper.jsl:3:4: in program Probe_Helper: token recognition error at: '@'`, and `… unterminated comment`);
    `Unknown variable undefinedVar` at `Helper.jsl:2:14`; and, in a Prism program, two glue blocks, the first at
    column 4, through `compile` and through `getParserInfo`: `Helper.jsl:4:4: in program Probe_Helper: A Prism program
    cannot have a glue block`, with no file written.
  - Generator text: `<Probe declarations>:2:6: in program Probe_POINT: no viable alternative at input 'param flaot3'`.
  - After a placeholder on its line: `Kernel.jsl:3:37: in program Probe: Unknown function undefinedFunc(float)`, at
    column 38 of the program.
  - The `unroll` condition `<=`, with each of the HLSL, GLSL and Metal outputs:
    `Kernel.jsl:6:4: in program Probe: Condition must be '<' in order to unroll 'for' loop (for now)`.
  - `anErrorOfTheInputIsReportedAtItsConstruct`, 27 rows, each a full message in a piece `Probe.jsl`: 1 to 5, the
    other `unroll` restrictions (nested loops at the inner one, `7:8`, the others at `5:4`); 6, 7 and 17, the sampler
    limit at the third sampler (three on three lines, `3:14`; one list, `1:20`; two lists of two samplers among other
    `param`s, `4:15`); 8, `Return statement not expected` at the `return`; 9 and 10, `already declared` at the
    identifier (`4:10`, and `4:19` for the second declarator of one declaration); 11 to 14 and 18 to 21, the three
    crash fixes (`float3()`, constants too large in a declaration and in both `unroll` arguments, `float[2]` in a
    declaration, as a parameter type, as a return type and in a constructor); 15 and 16, `Expressions must have
    compatible result types` at the right operand (`4:18`, `4:17`); 22 to 27, the declarator checks at the identifier
    (an array size that is not an integer or not a constant integer, a `const` without an initializer or of another
    type, and an initializer of another type for the first and for a second declarator, `4:11` and `4:20`).
- **`JSLSourceTest`, 73 tests.** The text of `format` equals `String.format(Locale.ROOT, …)` with each source
  replaced by its text, and a number is inserted in ASCII digits under a default locale with Thai digits. Positions
  before, inside and after a placeholder on one line; nested pieces, a piece used twice and an assembled argument;
  the end of a text with and without a final newline, of the empty text and of a template that ends in a placeholder;
  `\r` counted as a column; a supplementary character one column, before a piece boundary in the template, in an
  argument and in a piece (a count in UTF-16 units gives another column); an empty argument, string or piece, owning
  no position. Every rejection of `format` with its exception type and, where it applies, the piece, line and column
  of the `%`. The three forms of `describe`. `file`: the piece is named after the file, and a byte that is not UTF-8
  is replaced, not rejected.
- **`JavaPeerLimitTest`, 8 tests,** the story's 8 inputs in a piece `Limit.jsl`, with the input's declaration on
  line 3, its statement on line 10 and its expression on line 11: J01 at `11:23` (`halve`), J02 at `11:23` (`abs`),
  J03 at `10:14` and J04 at `10:18` (`weights`), J05 at `10:25` (`return`), J06 at `10:8` (`abs`), J07 at `10:8`
  (the first `val`) and J08 at `10:29` (the second `val`). Each compile with every output type throws exactly a
  `JSLSourceException` with the full message and writes exactly `Probe.frag`, `Probe.hlsl` and `Probe.metal`; the same
  program with the shader outputs only, in a fresh directory, writes the same 3 files with the same text.
- **`SourceFileClosedTest`, 3 tests,** each of 20 calls of `JSLC.compile(JSLCInfo, File)` on fresh copies of a
  `.jsl` file, each call checked at once:
  - every output up to date: the outputs keep their write time, so the up-to-date path was taken;
  - the `.hlsl` out of date: it is rewritten and the `.frag` is not;
  - a program with a syntax error and the `.hlsl` out of date: the compile throws `ParseCancellationException`.

  The check depends on the system. On Windows the copy can be deleted. On Linux no link in `/proc/self/fd` points at
  it. On macOS, where neither works, no regular file open in `/dev/fd` has the copy's inode and size (the device is not
  compared, as `/dev/fd` reports devfs's), and if one has them before the call already, the check fails, since it
  cannot tell. Anywhere else the tests are skipped with an assumption. Before each test a control file is opened and
  the check must see it open, then closed. On macOS the 3 tests ran, with nothing skipped; the Windows and Linux
  checks are written but were not run here.

The four classes have no tab, CR, trailing blank or wildcard import and no line over 120 columns. The review's tests
lens ran the suite, at 389 tests then, under a default locale with Thai digits and a symlinked `java.io.tmpdir`, under
`ISO-8859-1` with a Turkish locale and in 3 random orders, and `SourceFileClosedTest` 29 times in all, without a
failure or a skip.

### Mutant proofs
Each mutant was one edit, or for the story's three test-input mutants one diff across the tests, in a scratch clone,
built and run with `mvn -B -ntp -o -pl buildtools/jslc clean test`, the whole jslc suite; the clone was restored and
compared with the repository afterwards. The unmutated tree passed every
run. Counts are those of the test classes as they stood then.

**The test authors' mutants (2026-11-16):**

| Mutant | Fails |
| --- | --- |
| The story's: a valid program in place of each invalid one | every negative test: 29 of `SourceLocationTest`'s 30 then (the other compiles a valid file), the 8 of `JavaPeerLimitTest`, the 18 rejections of `JSLSourceTest` |
| The story's: another cause (`@` for `)` and back, `Unknown function` for `Unknown variable`, another backend error or Java-peer limit) | each test so changed, on the other message: 21 and 8 |
| The story's: the error one line down in its piece | every location test, each one line lower: 29 and 8 |
| The story's: the map replaced by the identity | the later-piece tests on the line and the placeholder test on the column (`Kernel.jsl:3:38`), and 35 rows of `JSLSourceTest`; the single-piece tests pass, as they must |
| The story's position dropped where the error is raised, in all of the visitor: `locating` rethrows unchanged; the outermost context wins | the semantic location tests and `SymbolTest`, and with the first also the 3 `AssignmentExprTest` assertions: 12 and 9 |
| The story's position dropped at a node: each of the seven `TreeMaker` factories ignores it | its own tests: `ForStmt` the `unroll` tests, `VarDecl` the sampler rows, `GlueBlock` the glue tests, `ReturnStmt`, `CallExpr`, `ArrayAccessExpr` and `VariableExpr` their rows |
| The story's: the `InternalError` of each of the 7 sites restored | exactly that site's rows of `JavaPeerLimitTest` |
| The story's: a Java-peer check moved into the visitor; the Java-peer checks run in `JSLC` before any output | row 5, on the files written and, without that assertion, on the shader-only compile; all 8 rows, on the files written |
| `compile(JSLCInfo, File)` with an unnamed source or with the path as the name; `shaderName` for `peerName`; `getParserInfo(JSLCInfo, JSLSource)` left unlocated; the lexer on `INSTANCE` | the two file tests; the 9 tests of programs with a `peerName`; the `getParserInfo` glue test; the `@` test |
| `Files.readString` in `JSLSource.file`; columns in UTF-16 units; an empty argument owning a position; `%d` in the default locale | the two non-UTF-8 tests; the supplementary-character tests; the 4 empty-argument tests; the locale test |
| The baseline's `compile(JSLCInfo, File)`, emulated: the stream opened before the checks and closed only when the program is parsed | on macOS, the up-to-date file test, with the copy open after 20 of 20 calls; the out-of-date test passes, as the story expects |
| The macOS check broken: also comparing the device; always answering open | the control, so both file tests |

**The independent review's own mutants (2026-11-16).** Its mutants lens made 72 runs, every mutant its own edit and
every input its own. Killed: a valid program in place of the invalid one (37 of 37 negative tests, 18 of 18
rejections), another cause (37 of 37), the error one line down (50 of 50 location tests, each one line lower with the
same column), the identity map, the two `locating` mutants in its own form, the line-one form of each factory's
mutant and the column-zero form of six of seven, each `InternalError` restored with the new text so that only the type
changes, the nested-call check moved into the visitor, the Java backend run before the HLSL output, and more of its
own: the program name dropped, line and column swapped, 1-based columns, the root piece's name for every position, the
glue check after the backends, `%d` through `NumberFormat`, the end of the text mapped to the last argument, the
declarator's `locating` removed and the nested call reported at the outer one. Its source-map lens ran a property test,
7 seeds of 20,000 random trees of pieces: `describe` equalled a brute-force location at all 10,537,923 code-point
positions, 3,351,995 lexer tokens and 420,624 lexer errors, with 0 failures, and the harness caught the 3 mutants of
`JSLSource` given to it. `locating` that wraps every `RuntimeException` passes the suite, as expected: 55 unusual
inputs of the visitor end only in success, `JSLSourceException` or `ParseCancellationException`.

The mutants that survived, in the review and later, and what kills them now:

| Mutant | What it showed | Killed now by |
| --- | --- | --- |
| `TreeMaker.glueBlock` keeps the line and drops the column | every pinned glue block was at column 0 | the two later-piece glue tests (`4:0` for `4:4`) |
| The last glue block reported instead of the first | no Prism program of the tests had two glue blocks | the same two tests (`7:0`) |
| `compile(JSLCInfo, File)` skips the locate step, or locates with another source | no test reached a `JSLSourceException` through the `File` overload | `aSemanticErrorInAFileNamesTheFileItsLineAndTheProgram` |
| `%d` formatted with `Locale.getDefault()` | the locale test set only the format locale | `aNumberIsInsertedInAsciiDigitsWhateverTheDefaultLocale` |
| `locating` dropped in `visitMultiplicative_expression`, or in `visitAdditive_expression` | no test pinned `Expressions must have compatible result types` | rows 15 and 16 (`4:14` for `4:18`, `4:13` for `4:17`) |
| `PrismBackend` records every `param`; reports the last sampler | the sampler rows had three samplers and no other `param` | row 17 (`2:17`, `4:18`) |
| `Type.fromToken` back at the parameter type, the return type or the constructor; `Integer.valueOf` for the second `unroll` argument | the crash fixes were pinned at one site each | rows 18 to 21 |
| Each of the four array-size and `const` checks raised at the declaration's start (the rework's; two of them survived its first two rows) | each check moved under the declarator needs a pin of its own | rows 22 to 25 |
| `tm.varDecl` called after the declarator's `locating`, for the first and for a further declarator (the confirming review's) | the initializer checks were unpinned | rows 26 and 27 (`4:4` for `4:11` and for `4:20`) |
| `JSLSource.file` back on `Files.readAllBytes` (the confirming review's) | a directory reported without its path | `aDirectoryInPlaceOfAFileIsReportedWithItsPath` (`IOException` for `FileNotFoundException`) |
| The macOS observation taken after the compile | a leak would read as closed | the check fails when the copy is open before the call |
| The read before the time in `compile(JSLCInfo, File)` | the two orders differ only when the file changes during the read | not killed: no test in one thread tells them apart; a comment says why the time comes first |

The rework ran each fixed survivor and its own: a `FileInputStream` leaked in `compile(JSLCInfo, File)` fails the 3
file tests; with the observation also moved after the call, all 3 fail with `cannot tell whether the code leaves …
open: it is open already`; a stream closed only on a normal return fails only the throwing-path test. The confirming
review wrote its own forms of the rework's mutants, all killed, and found the three survivors it reported: the two
`tm.varDecl` forms and `Files.readAllBytes`; the read before the time survived it too, as expected. Its emulation of
the baseline's `compile(JSLCInfo, File)` fails the up-to-date test, open after 20 of 20 calls, and passes the other
two. The three test pins each fail only their own test under their mutant. The generators are outside the
jslc suite: the confirming review's mutant that leaves an omitted Prism mask unnamed is caught by the regeneration
only, where the first Prism JVM exits 1 with
`IllegalArgumentException: <Prism CompileJSL main>:1:0: %s takes a named JSLSource, not an unnamed one`.

**The story's three test-input mutants on the 16 later tests (2026-11-17).** The test authors and the review ran the
valid-program, other-cause and one-line-down mutants on the tests as they stood at 389 tests. The 16 tests added or
changed since then were run against the same three in a clone of the final tree: rows 15 to 27 of
`anErrorOfTheInputIsReportedAtItsConstruct`, `aSemanticErrorInAFileNamesTheFileItsLineAndTheProgram`,
`compilingAPrismProgramWithGlueBlocksInALaterPieceReportsTheFirstThereAndWritesNoFile` and
`parsingAPrismProgramWithGlueBlocksInALaterPieceReportsTheFirstThere`. Each mutant changed the inputs of the 16 tests,
never their expectations. The unmutated clone ran 405 / 0 / 0 / 0, and each mutant 405 / 16 / 0 / 0, the 16 failures
being exactly those tests:
- a valid program: each with `Expected com.sun.scenario.effect.compiler.JSLSourceException to be thrown, but nothing
  was thrown.`;
- another cause: each on another message, 13 at the pinned line and column and rows 18, 19 and 25 at another
  position; the two glue tests with both glue blocks replaced by `    float[2] g;`, which gives
  `Helper.jsl:4:4: in program Probe_Helper: array types are not supported: float[2]`;
- the error one line down: each with the same message, one line lower at the same column.

The identity map and the dropped position were not run again on these 16 tests; each of the 16 fails under the
mutant it was written to kill (the survivors table above).

**The story's "position dropped in a single `visitX`" mutant** is equivalent only where the context that raises the
error is the first child of a context that also locates, and so starts at the same token:
`anUnknownVariableInALaterPieceIsReportedAtTheVariableInThatPiece`,
`anUnknownFunctionAfterAPlaceholderIsReportedAtItsColumnInTheTemplate` and rows 11 to 14 of
`anErrorOfTheInputIsReportedAtItsConstruct`, whose errors are raised in `primary_expression`, `function_call`,
`unroll_modifier` and `fully_specified_type`. Where it is not, for a declarator and for the right operand of a binary
operator, the drop moves the error and a test kills it: row 9 (`4:10` becomes `4:4`) and rows 15 and 16 (`4:18`
becomes `4:14`, `4:17` becomes `4:13`). The mutants "`locating` rethrows unchanged" and "the outermost context wins"
cover the cases where it is equivalent.

Not run: the mutant proof of `SourceFileClosedTest` on Windows and Linux
([Owner-run and pending](#owner-run-and-pending)).

### Generator runs
Outside Maven, as the pom's `jsl-codegen` execution runs the generators, with the arguments `GenAllDecoraShaders`
passes for each shader plus `-d3d -es2 -mtl`; [`US-022-generator-cases.md`](US-022-generator-cases.md) has the class
path, the command lines, the inputs and the times. Both sides used the before clone's graphics and base classes and
`.jsl` inputs, which the change does not edit, and each side its own jslc and generator classes: before, a fresh clone
of `726d7f74a5` built by its module gate on 2026-11-16; after, a fresh clone with the change, built by its module gate
on 2026-11-17 ([Final gates](#final-gates-2026-11-17-macos)). Every JVM ran in an empty working directory of its own,
which stayed empty.

- **Regeneration with `-d3d -es2 -mtl`.** On each side 29 JVMs, `GenAllDecoraShaders` and the Prism `CompileJSL` once
  per Prism `.jsl` file, all exit 0 and print nothing, and write 1,059 files (`jsl-decora` 208, `jsl-prism` 848,
  `mtl-headers` 3), identical by name and md5 between the sides and to `US-021-regeneration.md5`
  ([md5 list](US-022-regeneration.md5)). Without its 262 `.hlsl` lines (50 Decora, 212 Prism) the list has 797 lines,
  identical to the after gate's `target/gensrc` list. The implementation, the review, the rework and the confirming
  review each ran it on their own builds, with the same result.
- **The cases.** PCE is `ParseCancellationException`, JSE `JSLSourceException`, RE `RuntimeException` and IE
  `InternalError`; ANTLR's expected-token list is cut. Exit codes and file counts are before / after:

  | Id | Edit | Before | After | Exit | Files |
  | --- | --- | --- | --- | --- | --- |
  | S01 | `Brightpass.jsl`, new line 39 `    )` | PCE `line 39:4 extraneous input ')' …` | PCE `Brightpass.jsl:39:4: in program Brightpass: extraneous input ')' …` | 1 / 1 | 0 / 0 |
  | S01g | As S01, through `GenAllDecoraShaders` | `InvocationTargetException`, caused by S01's | S01's | 1 / 1 | 5 / 5 |
  | S02 | `LinearConvolve.jsl`, new line 75 `    )` | `line 75:4` | `LinearConvolve.jsl:75:4: in program LinearConvolve_4:` | 1 / 1 | 0 / 0 |
  | S03 | `Blend_ADD.jsl`, new line 76 `    )` | `line 147:4` | `Blend_ADD.jsl:76:4: in program Blend_ADD:` | 1 / 1 | 20 / 20 |
  | S04 | `Blend.jsl`, new line 77 `    )` | `line 106:4` | `Blend.jsl:77:4: in program Blend_SRC_OVER:` | 1 / 1 | 0 / 0 |
  | S05 | `PhongLighting.jsl`, new line 127 `    )` | `line 128:4` | `PhongLighting.jsl:127:4: in program PhongLighting_DISTANT:` | 1 / 1 | 0 / 0 |
  | S06 | `PhongLighting.jsl`, new line 142 `    )` | `line 144:4` | `PhongLighting.jsl:142:4: in program PhongLighting_DISTANT:` | 1 / 1 | 0 / 0 |
  | S07 | `PaintImagePattern.jsl`, new line 35 `    )` | `line 36:4` | `PaintImagePattern.jsl:35:4: in program Solid_ImagePattern:` | 1 / 1 | 4 / 4 |
  | S08 | `MaskFillPgram.jsl`, new line 101 `    )` | `line 101:4` | `MaskFillPgram.jsl:101:4: in program FillPgram_Color:` | 1 / 1 | 64 / 64 |
  | S09 | `PaintMultiGradient.jsl`, new line 47 `    )` | `line 48:4` | `PaintMultiGradient.jsl:47:4: in program Solid_LinearGradient_PAD:` | 1 / 1 | 8 / 8 |
  | S10 | `PaintLinearGradient.jsl`, new line 44 `    )` | `line 125:4` | `PaintLinearGradient.jsl:44:4: in program Solid_LinearGradient_PAD:` | 1 / 1 | 8 / 8 |
  | S11 | `PaintImagePattern.jsl`, new last line 43 `}` | `line 44:0 extraneous input '}' …` | `PaintImagePattern.jsl:43:0: in program Solid_ImagePattern: extraneous input '}' …` | 1 / 1 | 4 / 4 |
  | M01 | `Brightpass.jsl`, new line 39 `    float u = undefinedVar;` | RE `Unknown variable undefinedVar` | JSE `Brightpass.jsl:39:14: in program Brightpass: Unknown variable undefinedVar` | 1 / 1 | 0 / 0 |
  | M02 | `Blend_ADD.jsl`, the same line as new line 76 | RE `Unknown variable undefinedVar` | JSE `Blend_ADD.jsl:76:14: in program Blend_ADD: Unknown variable undefinedVar` | 1 / 1 | 20 / 20 |
  | M03 | `PaintImagePattern.jsl`, new line 35 `    float v = undefinedFunc(1.0);` | RE `Unknown function undefinedFunc(float)` | JSE `PaintImagePattern.jsl:35:14: in program Solid_ImagePattern: Unknown function undefinedFunc(float)` | 1 / 1 | 4 / 4 |
  | M04 | `LinearConvolve.jsl:75`, `i <= count` for `i < count` | RE `Condition must be '<' in order to unroll 'for' loop (for now)` | JSE `LinearConvolve.jsl:75:4: in program LinearConvolve_4:` and the same text | 1 / 1 | 0 / 0 |
  | J01 | `Brightpass.jsl`: `float halve(float v)` inserted after line 34; line 46 `sign(halve(luminance))` | IE `Nested function calls not yet supported` | JSE `Brightpass.jsl:46:23: in program Brightpass: nested function calls are not supported by the Java software peer (-java)` | 1 / 1 | 3 / 3 |
  | J01g | As J01, through `GenAllDecoraShaders` | `InvocationTargetException`, caused by J01's | J01's | 1 / 1 | 8 / 8 |
  | J02 | line 41 `sign(abs(luminance))` | IE, as J01 | JSE `Brightpass.jsl:41:23:`, as J01 | 1 / 1 | 3 / 3 |
  | J03 | `param float4 weights[2];` and `    float w = weights[0].x;` | IE `Array access only supports variable expr/index (for now)` | JSE `Brightpass.jsl:41:14: in program Brightpass: array access other than variable[variable] is not supported by the Java software peer (-java)` | 1 / 1 | 3 / 3 |
  | J04 | as J03 with `abs(weights[0].x)` | IE, as J03 | JSE `Brightpass.jsl:41:18:`, as J03 | 1 / 1 | 3 / 3 |
  | J05 | new line 41 `    if (luminance < 0.0) return;` | IE `Empty return not yet implemented` | JSE `Brightpass.jsl:41:25: in program Brightpass: return without a value is not supported by the Java software peer (-java)` | 1 / 1 | 3 / 3 |
  | J06 | new line 41 `    if (abs(val) == val) luminance = 0.0;` | IE `TBD` | JSE `Brightpass.jsl:41:8: in program Brightpass: the Java software peer (-java) does not support the vector result of abs(...) here: it cannot tell which component to use` | 1 / 1 | 3 / 3 |
  | J07 | new line 41 `    if (val == val) luminance = 0.0;` | IE `TBD` | JSE `Brightpass.jsl:41:8: in program Brightpass: the Java software peer (-java) does not support vector 'val' here: it cannot tell which component to use` | 1 / 1 | 3 / 3 |
  | J08 | new line 41 `    luminance = abs((val.x + val).y);` | IE `TBD` | JSE `Brightpass.jsl:41:29:`, as J07 | 1 / 1 | 3 / 3 |
  | J01s to J08s | J0n's input, with the shader outputs only | nothing printed | nothing printed | 0 / 0 | 5 / 5 |
  | G01 | `CompilePhong.java`, `"param float3 lightPosition;"` without its `;` | PCE `line 126:0 mismatched input 'void' expecting {',', ';'}` | PCE `PhongLighting.jsl:124:0: in program PhongLighting_POINT: mismatched input 'void' expecting {',', ';'}` | 1 / 1 | 5 / 5 |
  | G02 | the same string with `flaot3` for `float3` | PCE `line 123:6 no viable alternative at input 'param flaot3'` | PCE `<CompilePhong declPosVar>:2:6: in program PhongLighting_POINT: no viable alternative at input 'param flaot3'` | 1 / 1 | 5 / 5 |

  - In all 35 runs the exit code is the same on both sides, and so is the list of the files written with their md5.
  - Each file a failing run writes is md5-identical to the file of that path in the regeneration, on both sides: S01g
    5 of 5, S03 20 of 20, S07 4 of 4, S08 64 of 64, S09 8 of 8, S10 8 of 8, S11 4 of 4, M02 20 of 20, M03 4 of 4, G01
    5 of 5 and G02 5 of 5. Each J0n writes `Brightpass.frag`, `.hlsl` and `.metal`, md5-identical to those of its J0ns
    run, which also writes the 2 Metal headers. J01g writes the 5 files of `ColorAdjust`, which `GenAllDecoraShaders`
    compiles first, then J01's 3 shaders, and no header or `Brightpass` peer.
  - After the location, every message is byte-identical to the baseline's: in the 13 syntax runs to the text after
    `line L:C `, ANTLR's expected-token list included, and in M01 to M04 to the whole message. M01 to M03 also print a
    `Caused by:` line with a `RuntimeException` of the same text.
  - G01 and G02 run `CompilePhong` compiled from a copy of the generator sources with one line edited, as the pom's
    Decora `javac` compiles them; the unedited copy gives classes `cmp`-identical to the gate's.
- **The review's own cases,** each run on both sides with the same exit code and written files: an extra `)` before
  the `{` of `LinearConvolve.jsl:75`, after the `%d` in the middle of that line, is reported at
  `LinearConvolve.jsl:75:46` (before, `line 75:45`); `blendx_%s` for `blend_%s` on `Blend.jsl:78` gives
  `Blend.jsl:78:12: in program Blend_SRC_OVER: Unknown function blendx_src_over(float4, float4)`; `mask` renamed in
  `MaskFillPgram.jsl` gives `<Prism CompileJSL main>:5:12: in program FillPgram_Color: Unknown function mask(float2,
  float2)`; errors in `LinearConvolveShadow.jsl`, `PaintTextureRGB.jsl`, `MaskAlphaTexture.jsl` and after the `%s` of
  `PaintRadialGradient.jsl:65` name their file and line; and a glue block reached only by the Prism loader's fallback
  parse gives `PaintImagePattern.jsl:43:0: in program Solid_ImagePattern: A Prism program cannot have a glue block`.
  A JFR file-read recording showed the same reads in the same order on both sides, and a Maven build of a clone with
  S01's edit failed in `jsl-codegen` with S01's located message and `Java returned: 1`.

### Final gates (2026-11-17, macOS)
Both sides ran `mvn -B -ntp -l <log> -Dmaven.test.failure.ignore=true -pl buildtools/jslc,modules/javafx.graphics -am
clean test` in fresh clones on the host above: before, at `726d7f74a5` on 2026-11-16 from 21:38 to 21:39; after, at
`726d7f74a5` with the main tree's change applied, `-o` added (whether the before run was offline its log does not
show). There were two after runs, each in a clone whose `git status --short` and files were `cmp`-identical to the main
tree's: on 2026-11-17 from 00:03 to 00:05, before the three test pins, with jslc 402 / 0 / 0 / 0, whose classes ran the
regeneration and the generator cases; and from 00:37 to 00:38, with the pins, which the table and the kept lists give.
Among its sources, the second clone differs from the first only in `SourceLocationTest.java`; its 156 jslc classes and
15 generator classes are `cmp`-identical to the first's, so the regeneration and the cases were not run again, and
J01g ran on it. Times are America/New_York. They are extra evidence: the criterion's gates are Windows and WSL. Counts
are run / failures / errors / skipped.

| Gate | Result |
| --- | --- |
| Module gate | Both exit 0 with BUILD SUCCESS (Maven total time 01:16 min and 01:11 min). jslc 276 / 0 / 0 / 0 in 31 classes before and 405 / 0 / 0 / 0 in 35 after, 276 + 129 tests in 31 + 4 classes; base 5,510 / 0 / 0 / 22 in 290 and graphics 26,696 / 0 / 0 / 858 in 629 in both. Of the 950 per-class lines before and 954 after, `diff` shows only `JSLSourceTest 73 0 0 0`, `JavaPeerLimitTest 8 0 0 0`, `SourceFileClosedTest 3 0 0 0` and `SourceLocationTest 45 0 0 0` added ([per class](US-022-macos-per-class.tsv)). No class has a failure or an error, and jslc skips nothing, so `SourceFileClosedTest` ran its macOS check |
| Generated files | The module gate's `target/gensrc`: 797 files (`jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3) and no `headers` directory, identical by name and md5 before and after ([md5 list](US-022-macos-gensrc.md5), the after run's) and to `US-021-macos-gensrc.md5` |
| Logs | No `[ERROR]` line and no `warning(131)` in either log, and no `warning(` line after. Of the 132 `[WARNING]` lines of each log, 120 are surefire's `Tests run:` lines of classes and modules with skipped tests (118 and 2); the other 12 are `javac` notes, identical in both once the clone path is normalised. `jsl-codegen`, `cmake-configure` and `cmake-build` ran once in each |
| Crash files | No `hs_err_pid*` file in either clone; each holds 27 `hs_err` directories without a file, which child-JVM tests create |

The per-class lists come from each log through the `classes` function of US-021's
[Windows gate](US-021-remove-unused-decora-generator-inputs.md#windows-gate), which reproduces the before list of this
host byte for byte, merged with its `merge` function. No source of a shipped module changes and every generated file
is md5-identical, so no `FULL_TEST`/`USE_ROBOT` run is called for; the PR's `submit.yml` run is to cover Linux and
Windows.

### Independent review (2026-11-16)
Of the change before the rework, when jslc ran 389 tests, in clones of the reviewers' own. Six lenses: the source map
and `JSLC`; the visitor, the tree and the backends; independent mutants; the generators and the build; the tests; and
conventions and records. Each finding raised as a blocker or a should-fix was checked by a skeptic, who reproduced it
in a clone of its own and rated it again.

| Lens | Finding | Raised as | Skeptic | Done |
| --- | --- | --- | --- | --- |
| Source map and `JSLC` | `JSLSource.file` read with `Files.readAllBytes`: a directory named `Brightpass.jsl` gave `java.io.IOException: Is a directory` without the path (baseline: `FileNotFoundException: <path> (Is a directory)`), and a missing or unreadable file changed exception type | should-fix | Real, should-fix; only the directory case is a defect | The read goes through `FileInputStream`; the three cases give the baseline's messages again; pinned by `aDirectoryInPlaceOfAFileIsReportedWithItsPath` |
| Independent mutants | `TreeMaker.glueBlock` keeping the line and dropping the column passed every test, since every pinned glue block was at column 0 | blocker | Real, should-fix: the code is right, and the test's input could not show a column | The helper's first glue block starts at column 4 and a second one follows; both later-piece glue tests pin `Helper.jsl:4:4` |
| Independent mutants | No test reached a `JSLSourceException` through `compile(JSLCInfo, File)`, the path of the Decora `CompileJSL` and of `JSLC.main`; skipping the locate step there, or locating with the path, passed every test | should-fix | Real, should-fix | `aSemanticErrorInAFileNamesTheFileItsLineAndTheProgram` |
| Independent mutants | The locale test set only the format locale, so `%d` formatted with `Locale.getDefault()` passed | should-fix | Real, a note: the code is right, and the test catches the regression that matters | Done anyway: the test sets the whole default locale and restores it |
| Independent mutants | The reason given for the story's single-`visitX` mutant being equivalent is false for the right operand of a binary operator, and no test pinned `Expressions must have compatible result types` | should-fix | Real, should-fix | Rows 15 and 16; the equivalence is recorded as in [Mutant proofs](#mutant-proofs) |
| Generators and build | The "Generator text" criterion against case G01, which names `PhongLighting.jsl:124:0` | should-fix | Real, a note: the accepted limitation, for the records | The criterion is annotated; G02 meets it |
| Conventions and records | This story's records, the evidence and the amendments of other stories were not written | should-fix | Real, a note: the planned next step | This write-up, the `US-022-*` files and the amendments of US-023, US-024 and US-113 |
| Conventions and records | US-122 and US-123 cited documents that are not in the repository and gave three positions as read, not measured | should-fix | Real, should-fix | They cite this story and `US-022-generator-cases.md`, and give the positions the final gate measured |
| Conventions and records | The Javadoc of `JSLSource.format` said it returns text equal to `String.format(Locale.ROOT, …)`, which is false for a `JSLSource` argument | should-fix | Real, should-fix | Reworded |
| Conventions and records | A comment of `JSLSource.file` narrates history | should-fix | Not a finding: it gives the reason | Not changed for it; the comment was rewritten when the read moved to `FileInputStream` |
| Conventions and records | US-122 and US-123 leave an either/or in their requirements | should-fix | Not a finding: the owner's rule leaves the approach to whoever implements a story | Left as filed |

Its notes, and what was done with them:
- the two removed public members: recorded in [Behaviour changes](#behaviour-changes) and in the upstream draft;
- the lower nesting limit of the visitor: [Known effects](#known-effects);
- `PrismBackend`'s choice of sampler declarators unpinned: row 17;
- `float[2]` pinned only in a declaration, and only the first `unroll` argument: rows 18 to 21;
- the declarator rule covered only declaring the variable and its initializer, so `w[0]` was reported at the
  identifier and `w[1.5]` at the declaration's start: every check on a declarator now reports its identifier, rows
  22 to 25;
- the last glue block reported instead of the first: the second block of the helper;
- the order of the time and the read unpinned: a comment says why the time comes first;
- the macOS check blind if the observation were taken after the call: the check fails when the copy is open already;
- the comment of `GenAllDecoraShaders` claimed that the cause always names a file and line: reworded;
- one piece name for four `main` templates: [Known effects](#known-effects);
- the exception type for an unreadable input: the baseline's again;
- no test of the path where the compile throws: the third file test;
- the Metal output of J07: [Known effects](#known-effects) and US-124;
- Javadoc gaps (the bases of line and column, the detail, the decoding, how pieces are named), the mask and paint of
  the Prism generator typed `Object`, `program` for a program's name beside `ProgramUnit`, and the positional boolean
  of `JSLSource`'s segments: fixed;
- US-122's count of 9 JVM exceptions and the code rules of US-122 and US-123: fixed in those stories;
- two unused imports of the Prism generator, lines of the baseline: left.

What it confirmed, among more: the message forms of every public entry point; that `compile` and `getParserInfo`
catch only `JSLSourceException` and that none leaves a named entry point unlocated; 108 of 108 positions counted by
hand on 54 inputs, each as a single piece and as a later piece after a placeholder in the middle of a line; 15 valid
programs whose 111 output files are md5-identical to the baseline's; the files written before each error, the same on
both compilers; a JVM after 28 failing compiles writing the same outputs as a fresh one; the regeneration and 32
generator cases; the same reads in the same order (JFR); and the rules of style on the 31 Java files. Not checked by
it: Windows and Linux, the `javafx.graphics` module tests, robot and system tests.

### Rework and confirming review (2026-11-16 and 2026-11-17)
- **Rework,** 2026-11-16, after the review. In production: the `FileInputStream` read; the Javadoc of `format`, of
  `named` and `file`, and of `JSLSourceException` (the line 1-based and the column 0-based, as ANTLR counts them, and
  the detail the message without the location); the reason in `GenAllDecoraShaders`' comment; every check on a
  declarator under the declarator; the omitted Prism mask and paint as empty named pieces; `programName` for the
  program's name everywhere; two named factories for `JSLSource`'s segments; the comment on the time in
  `compile(JSLCInfo, File)`. In the tests: the kills of the survivors table of [Mutant proofs](#mutant-proofs), the
  throwing-path file test and the macOS check that fails when it cannot tell. Results: jslc 402 / 0 / 0 / 0 in 35
  classes; the 1,059 files identical; the 32 generator cases with the implementation's exit codes, files and
  exception lines; the directory, missing and unreadable files with the baseline's `FileNotFoundException` messages.
- **Confirming review,** 2026-11-17, in a clone of its own: every item done as decided; the behaviour of the baseline
  wherever it should be, in the three I/O cases, the 264 program texts (byte-identical, dumped through a copy of
  `JSLC` on both sides), the 1,059 files of its own regeneration and the 32 generator cases and G02; jslc
  402 / 0 / 0 / 0 in 35 classes; every changed line within the rules. One should-fix: the initializer checks that
  `TreeMaker.varDecl` makes on a declarator were unpinned, so its mutants moving them to the declaration's start
  passed all 402 tests.
  Notes: no test pinned the `FileInputStream` read; a further declarator never gets the array-size or `const` checks,
  before or after (now in US-123); the `String` entry points ([Known effects](#known-effects)); and evidence files
  written into `backlog/` during its run, which are the final gate's.
- **Test pins,** 2026-11-17: rows 26 and 27 (`    float2 v = 1.0;` at `4:11`, `    float f1 = 1.0, f2 = true;` at
  `4:20`, `Initializer result type must match that of variable`) and `aDirectoryInPlaceOfAFileIsReportedWithItsPath`,
  which asserts a `FileNotFoundException` whose message holds the directory's path and nothing more, so that it can
  hold on Windows and Linux too (it ran on macOS only). Each fails only under its own mutant. jslc then ran
  405 / 0 / 0 / 0, and the final module gate was run again ([Final gates](#final-gates-2026-11-17-macos)).

### Known effects
- **The visitor accepts less nesting.** Each level of the parse tree now costs the frames of the `visitX` method, the
  helper and a lambda. Measured by the review on the macOS host with JDK 27 and the default 2 MB thread stack: the
  deepest nesting that compiles fell from 761 to 351 parentheses, from 5,148 to 2,987 nested blocks and from 4,095 to
  1,944 nested `if`s, by bisection in one warm JVM. In a fresh JVM, 300 nested parentheses compiled before and end now
  in a `StackOverflowError` in the visitor, without a location; at the baseline 900 overflowed, in ANTLR. The shipped
  `.jsl` files nest at most 3 parentheses. Not measured on Windows, on Linux or with other stack sizes.
- **The `String` entry points no longer go through bytes.** `JSLC.compile(JSLCInfo, String, long)`,
  `getParserInfo(String)` and `parse(String)` give the text to the lexer as it is, where the baseline encoded it with
  the default charset and decoded it as UTF-8. Under the build's UTF-8 default the output is the same for non-ASCII
  and lone-surrogate text in glue blocks and comments. Two differences, measured by the confirming review on the
  macOS host: a lone surrogate in code was `line 3:4 extraneous input '?' expecting {…}` and is now
  `line 3:4 token recognition error at: '\uD800'`; and under `-Dfile.encoding=ISO-8859-1` the baseline wrote a glue
  block's `café` as `caf?` and showed `'�'` in a token recognition error, where the change keeps `café` and shows
  `'é'`. No shipped input reaches either. `-Dfile.encoding=COMPAT` is UTF-8 on that host and showed nothing; Windows
  was not run.
- **The Metal compiler rejects two shader-only outputs.** The lines of Java-peer cases J07,
  `if (val == val) luminance = 0.0;`, and J06, `if (abs(val) == val) luminance = 0.0;`, compile with the shader
  outputs only, as `JavaPeerLimitTest` asserts, but the Metal compiler, run by hand (`xcrun -sdk macosx metal -c`)
  on the `.metal` of a small program with each line, written by the compiler of `726d7f74a5` (J07's on 2026-11-16,
  both on 2026-11-17), rejects them: `value of type 'bool4' (vector of 4 'bool' values) is not contextually
  convertible to 'bool'`.
  [US-124](US-124-stop-the-jsl-compiler-writing-metal-shaders-that-the-metal-compiler-rejects.md) owns these
  rejections, with those of US-122's inputs C3, C5 and O2.
- **One piece name, four templates.** `<Prism CompileJSL main>` names the text of four `main` templates, with or
  without `jsl_vertexColor` and with or without the alpha test, so one position can be different text in two programs;
  the program's name in the message tells them apart.

### Upstream check (2026-11-16)
- **JBS,** project JDK, through the anonymous REST API (`text ~` is JIRA's full-text search; the web pages could not
  be fetched from the host). The story's searches give the same counts as on 2026-09-27: `JSL` with `Unknown variable`
  0, with `Unknown function` 0, `decora` with `syntax error` 0 and `JSLC` with `error` 2; `JSL` with `location` gives 2
  (JDK-8123054, JDK-8111412) and `JSL` with `nested` 1 (JDK-8088486), none about error locations. More searches:
  - `jslc`: 7 issues (JDK-8364488, Open, "Missing @Override in JSLC"; JDK-8218764, JDK-8165809, JDK-8117916,
    JDK-8240499, JDK-8336624, JDK-8221269), none about error locations; a keyword scan of their full text and of
    JDK-8226635 found nothing about them either;
  - `ThrowingErrorListener` and `ParseCancellationException`: 0 each, with and without the component `javafx`;
  - `JSL` with `line number`, `line numbers` or `syntax error`: 1 (JDK-8336002, a Gradle log); with `file name` or
    `filename`: 6; with `error message`, `wrong line` and `stack trace`: 1, 2 and 3; none about these messages;
  - `Nested function calls`: 0 in `javafx`, 1 without the component (JDK-8297106, HotSpot, JNI); `InternalError` with
    `jslc`: 1 (JDK-8117916); `InternalError` with `JSL`: 2 (JDK-8114221, JDK-8117916);
  - `FileInputStream` with `JSLC`: 0; `JSLC` with `FileInputStream`, `not closed` or `resource leak`: 1
    (JDK-8218764, which names none of them); `JSL` with `locale`: 3, none about number formatting.

  No issue reports any of this. JDK-8226635 (the jslc tests are not run upstream) is still Open, created 2019-06-22
  and last updated 2019-08-01, without a comment or a link.
- **openjdk/jfx master** at `f1bb639dab` (2026-11-16); the three commits since `d0e72f9d86` touch none of the jslc
  files. Every defect of the story is there: no source name (`JSLC.java:172`, `CharStreams.fromStream`) and one shared
  listener on the lexer and the parser (`:177`, `:183`) that builds its message from line and column
  (`ThrowingErrorListener.java:41`); the program's line instead of the file's (`CompileBlend.java:53`,
  `CompilePhong.java:108`, `CompileLinearConvolve.java:60` and `:74`, the Prism `CompileJSL.java:263-264` and
  `:402-405`); semantic errors without a location (`TreeMaker.java:108` and `:179`, `SymbolTable.java:81`, `:85`,
  `:91` and `:115`, `SLBackend.java:216-249`, `PrismBackend.java:122`, `JSWTreeScanner.java:226`); the 7 reachable
  `InternalError`s at the lines of the 2012 import; the open file (`JSLC.java:156-160`); and `%d` in the default
  locale. `JSLVisitor`, `TreeMaker`, `SymbolTable`, `JSWCallScanner`, `JSWTreeScanner`, `JSWBackend`, `SLBackend`,
  `PrismBackend` and `HLSLBackend` are byte-identical to the fork's at `726d7f74a5`, and 56 of the fork's 57 `.jsl`
  files have master's blob ids (`ColorAdjust.jsl` differs by a fork-only change that no case uses).
- **What applies upstream.** The ten syntax cases, the semantic cases, the open file and `%d` apply as they are, since
  the files that make the messages are the same; no generator was run against master's own sources. On master a stray
  `}` after the last declaration is accepted silently (US-019's issue) and a glue block in a Prism program is dropped
  silently (US-020's), and neither the unterminated-comment message nor the glue-block check exists. Master still has
  the SSE and ME backends (`JSLC.java:33-34`, `:252-302`), whose scanners hold copies of the 7 sites (read, not
  measured), and its `-all` is `-java -sse -hw`. Master has 61 `throw new RuntimeException` sites in 10 files and 30
  `throw new InternalError` sites in 14. Upstream runs no jslc test (JDK-8226635): `build.gradle` has no
  `src/test/jslc` reference, and its `SymbolTest.java:48` uses `JSLC.OUT_ALL`, which nothing under `src/jslc`
  declares, so the fix's tests need that port too. Open pull requests that touch these files: #2346 (Direct3D 12,
  which adds a `HLSL6Backend` and an `OUT_D3D12` lazy-parse site that would need the same `JSLSource`), #2284 and
  #2329.
- **Draft.** The upstream issue is drafted with the measured cases, option (a) with its reasons and its limitation,
  the results with the fix, the two removed public members and the tests, and says that upstream runs no jslc test
  (JDK-8226635). It is kept outside the repository, `US-022-upstream-draft.md` in `_claude/`, until it is filed.
- **Not verified:** Gradle was not run (the task names `generateDecoraShaders` and `generatePrismShaders` are read
  from `build.gradle:1737`); no generator ran against master's sources; the SSE and ME scanners were read, not run;
  the Windows and WSL numbers in the draft are the story's.

### Owner-run and pending
Replace `<repo-url>` with the repository's remote and `<commit>` with the owner's US-022 commit. Before is
`726d7f74a5`. If `<commit>` is only on the PR branch, fetch it first with
`git -C jfx-after fetch -q origin pull/<PR>/head`. In PowerShell, quote every `-D` argument; under Git Bash, Maven
3.10's `bin/mvn` converts the paths. Each gate keeps its logs, its per-class lists (class, run, failures, errors,
skipped, separated by tabs) and its `target/gensrc` md5 lists as `us022-<os>-<before|after>…` in the directory that
holds the clones. The check commands ran as written in bash on the macOS host, with `windows` replaced by `macos`, on
the macOS gate's clones and logs (the shader-obj lines excepted). Both gates build from `clean` in fresh clones.

#### Windows gate
Two sibling checkouts, in Git Bash:
```bash
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach 726d7f74a5
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <commit>
```
Maven from PowerShell, in the directory that holds both, so that MSVC builds the natives:
```powershell
$pl = "buildtools/jslc,modules/javafx.graphics"
cd jfx-before
mvn -B -ntp -l ..\us022-windows-before.log -pl $pl -am clean test "-Djfx.parity.require=true"
"before exit $LASTEXITCODE"; cd ..
cd jfx-after
mvn -B -ntp -l ..\us022-windows-after.log -pl $pl -am clean test "-Djfx.parity.require=true"
"after exit $LASTEXITCODE"; cd ..
```
The checks, in Git Bash in the same directory:
```bash
classes() {
  tr -d '\r' < "$1" | awk '/Tests run: .* -- in / { c = $NF; sub(/.*Tests run: /, ""); split($0, n, /[^0-9]+/)
    print c "\t" n[1] "\t" n[2] "\t" n[3] "\t" n[4] }' | LC_ALL=C sort
}
for t in before after; do
  classes us022-windows-$t.log > us022-windows-$t-per-class.tsv
  (cd jfx-$t/modules/javafx.graphics/target/gensrc && find . -type f | LC_ALL=C sort | xargs md5sum) \
    > us022-windows-$t-gensrc.md5
done
diff us022-windows-before-per-class.tsv us022-windows-after-per-class.tsv
for t in before after; do
  echo "$t:"
  tr -d '\r' < us022-windows-$t.log \
    | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|BUILD (SUCCESS|FAILURE)'
done
cmp us022-windows-before-gensrc.md5 us022-windows-after-gensrc.md5 && echo "gensrc identical by name and md5"
cut -d/ -f2 us022-windows-after-gensrc.md5 | sort | uniq -c
for t in before after; do
  find jfx-$t/modules/javafx.base jfx-$t/modules/javafx.graphics jfx-$t/buildtools/jslc -name 'hs_err_pid*' -type f
done
```
And, on Windows only, the compiled shaders:
```bash
for t in before after; do
  (cd jfx-$t/modules/javafx.graphics/target/native/shader-obj && find . -name '*.obj' -type f | LC_ALL=C sort \
    | xargs -r md5sum) > us022-windows-$t-shader-obj.md5
done
cmp us022-windows-before-shader-obj.md5 us022-windows-after-shader-obj.md5 \
  && echo "shader-obj identical by name and md5"
wc -l < us022-windows-after-shader-obj.md5
```
It must show:
- `before exit 0` and `after exit 0`, and `BUILD SUCCESS` in both logs;
- from `diff`, besides the hunk lines, only these (with tabs between the fields):
  - `> com.sun.scenario.effect.compiler.JSLSourceTest 73 0 0 0`
  - `> com.sun.scenario.effect.compiler.JavaPeerLimitTest 8 0 0 0`
  - `> com.sun.scenario.effect.compiler.SourceFileClosedTest 3 0 0 0`, so the Windows check ran (skipped, it would
    end in `3`)
  - `> com.sun.scenario.effect.compiler.SourceLocationTest 45 0 0 0`
- jslc `Tests run: 276, Failures: 0, Errors: 0, Skipped: 3` before and
  `Tests run: 405, Failures: 0, Errors: 0, Skipped: 3` after (the three `link/..` tests of `MetalHeadersTest` are
  disabled on Windows); the same javafx.base line in both runs and the same javafx.graphics line in both runs, each
  with `Failures: 0, Errors: 0`;
- `gensrc identical by name and md5`, with `108 jsl-decora` and `424 jsl-prism` and no other directory, for the
  reasons US-019's [Windows gate](US-019-reject-trailing-input-in-the-jsl-compiler.md#windows-gate) gives;
- nothing from `find`;
- `shader-obj identical by name and md5`, and `262`: with `-d3d` the build generates 262 `.hlsl` files, 50 Decora and
  212 Prism, and the `shaders` target of `native/win.cmake` compiles each with `fxc` into one `.obj`. That `fxc`
  output is byte-identical between two checkouts has not been verified on the macOS host, so if the `cmp` fails, check
  that first (for example by comparing two builds of the before clone) before reading it as a regression.

`SourceFileClosedTest` deletes each copy on Windows to see whether it is still open. A virus scanner or the indexer
holding a fresh copy would make it fail, never pass falsely; its control in `@BeforeEach` checks that the check sees an
open file. Both runs use `-am`, as the amended criterion does: without it Maven takes `org.openjfx:javafx-base:28-ea`
and its `tests` jar from the local repository, and in a fresh clone with nothing installed the build stops with
`Could not resolve dependencies`. With `-am`, javafx.base is built in the same reactor and its tests run too, which is
why the checks print a javafx.base line.

#### WSL gate
In WSL (Ubuntu), with JDK 27 and Maven 3.10.0 on `PATH`, from fresh clones, with `-am`, no display and no
`-Djfx.parity.require=true` (the Linux font goldens were captured on another machine):
```bash
cd ~
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach 726d7f74a5
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <commit>
for t in before after; do
  (cd ~/jfx-$t && env -u DISPLAY -u WAYLAND_DISPLAY \
    mvn -B -ntp -l ../us022-wsl-$t.log -pl buildtools/jslc,modules/javafx.graphics -am clean test; echo "$t exit $?")
done
```
The checks are the Windows ones without the compiled-shader lines, run in `~` with `windows` replaced by `wsl` in the
file names. They must show what the Windows gate shows, with two differences:
- jslc `Tests run: 276, Failures: 0, Errors: 0, Skipped: 0` before and
  `Tests run: 405, Failures: 0, Errors: 0, Skipped: 0` after: Linux runs the three `link/..` tests, and
  `SourceFileClosedTest 3 0 0 0` means that its `/proc/self/fd` check ran;
- `108 jsl-decora` and `424 jsl-prism` from `-es2` (`pom.xml:48`), as in US-019's
  [WSL gate](US-019-reject-trailing-input-in-the-jsl-compiler.md#wsl-gate).

The javafx.base line is expected to be `Tests run: 5510, Failures: 0, Errors: 0, Skipped: 22` in both runs of both
gates: US-018's CI measured 5,510 / 0 / 0 / 22 on Windows x64 and on Linux
([US-018, Before the merge](US-018-fix-jslc-metal-header-path-and-static-state.md#before-the-merge-2026-11-07)).
`LinuxFontConfigGoldenTest`, `LinuxFontProcessGoldenTest`, `LinuxFreetypeGoldenTest` and `LinuxPangoGoldenTest` each
skip one test in both runs (US-008).

#### The file-closed test against the baseline's `compile`
The mutant proof of the criterion, on Windows and in WSL, in a scratch copy of the after clone, never in the gate's:
1. `mvn -B -ntp -pl buildtools/jslc clean test -Dtest=SourceFileClosedTest` must give
   `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.
2. Replace `compile(JSLCInfo, File)` in `src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java` of the copy with:
   ```java
       public static ParserInfo compile(JSLCInfo jslcinfo, File file)
           throws Exception
       {
           java.io.FileInputStream stream = new java.io.FileInputStream(file);
           long sourceTime = file.lastModified();
           ParserInfo pinfo;
           try {
               JSLSource source = JSLSource.named(file.getName(),
                       new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
               pinfo = compile(jslcinfo, source, sourceTime);
           } catch (Throwable t) {
               stream.close();
               throw t;
           }
           if (pinfo != null) {
               stream.close();
           }
           return pinfo;
       }
   ```
   Like the baseline, it opens the file once, before it takes the time, and leaves the stream open when the compile
   returns `null` because every output is up to date. It closes the stream when the compile returns a parse or throws.
   It differs from the baseline in two ways that the test does not see: it reads the text before the compile, and it
   also closes the stream when the compile throws before the parse, a path that no test reaches. The name and the
   decoding are those of `JSLSource.file`. Run the same command.
3. Expected, as on macOS (2026-11-17, with `-o` added): `Tests run: 3, Failures: 1, Errors: 0, Skipped: 0`, the one
   failure `aFileCompiledWithEveryOutputUpToDateIsClosedWhenTheCompileReturns`, with
   `calls after which the copy was still open ==> expected: <[]> but was: <[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
   13, 14, 15, 16, 17, 18, 19]>`, all 20 calls. The out-of-date test and the throwing-path test pass. Windows and
   Linux were not run. In WSL a garbage collection between a call and its check may close some (the story measured
   996 and 998 of 1,000 left open), so the list may be shorter, but not empty. On Windows JUnit's cleanup of the
   failing test's temporary directory may also report the files it cannot delete; that lands on the failing test
   only.
4. Restore `JSLC.java` and run the command again: 3 / 0 / 0 / 0.

#### After the gates
The rest of the [Definition of Done](#definition-of-done):
- Keep each gate's lists with the change, as the macOS ones are kept. In the directory that holds the clones, for
  `windows` and then `wsl`, with `REPO` the checkout of the commit:
  ```bash
  merge() {
    awk -F'\t' 'function cell() { return $2 ", Failures: " $3 ", Errors: " $4 ", Skipped: " $5 }
      FNR == NR { b[$1] = cell(); all[$1] = 1; next } { a[$1] = cell(); all[$1] = 1 }
      END { for (c in all) print c "\t" ((c in b) ? b[c] : "-") "\t" ((c in a) ? a[c] : "-") }' "$1" "$2" |
      LC_ALL=C sort
  }
  o=windows
  { echo "# US-022: per-class results of <the gate's mvn command>, <host>, <date> (columns: class, before, after;" \
      "before = 726d7f74a5, after = <commit>; '-' = no such class)"
    merge us022-$o-before-per-class.tsv us022-$o-after-per-class.tsv; } > "$REPO/backlog/US-022-$o-per-class.tsv"
  cp us022-$o-after-gensrc.md5 "$REPO/backlog/US-022-$o-gensrc.md5"
  ```
  The after md5 list is kept, since the gate requires it to equal the before list.
- Fill in the results of both gates and of the file-closed mutant proof here and in `backlog/README.md`.
- File the upstream issue from its draft, `US-022-upstream-draft.md`, kept in `_claude/` next to the repository. It
  refers to the upstream issues of US-019 and US-020: file those first, or insert their ids once all are filed.
- Set the status to ✅ Done with the date and the PR, and move the row to the done table of `backlog/README.md`.
