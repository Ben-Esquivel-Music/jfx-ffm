# US-022 — Report JSL errors at the source file and line

**Status:** 📋 Ready (filed 2026-09-27; measured on Windows by running the shader generators the way the pom runs them, outside Maven, with errors inserted into 9 `.jsl` files; a line-marker lexer rule prototyped outside the repository, where every shipped shader regenerates md5-identical and the 159 jslc tests pass; the 7 `InternalError` sites a `.jsl` file can reach, each reached with a small change to a copy of `Brightpass.jsl`; the source file left open measured on Windows and on Linux (WSL)) · **Found:** 2026-09-27, US-008 (filing US-019: with its end-of-input check prototyped, a stray `}` on line 43 of `PaintImagePattern.jsl` was reported as `line 44:0`, with no file name)

## Story
As a developer who edits a `.jsl` shader,
I want every error of the JSL compiler to name the `.jsl` file and the line and column in it, and to read as a
mistake in the file rather than as a fault of the compiler,
so that I can find the mistake without knowing how the generators assemble programs from several files and from text
of their own.

## Problem
Paths are relative to `modules/javafx.graphics` unless they start with `backlog/`. A bare line number (`:NN`) refers
to the file named in the same sentence, else to the one named in the lead-in of its list.

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
- The four Decora drivers the build does not call (`CompileBoxBlur`, `CompileGaussian`, `CompileZoomRadialBlur` and
  `CompileExternal`) compile single files, three of them with placeholders replaced.

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
  Today that `}` is accepted without an error (US-019).

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
  `JSWCallScanner.visitCallExpr(JSWCallScanner.java:149)`, called from `JSLC.compile` (`JSLC.java:230`).
  - Before it, 5 of the 7 files were written: the `.hlsl`, `.frag` and `.metal` and both Metal headers. They are
    right: identical to those of a run with shader outputs only, which exits 0, with the nested call in all three
    (`sign(halve(luminance))`; in Metal `sign(Brightpass_halve(sampler0, uniforms, luminance))`). Only the Java
    software peer (`-java`) cannot express it. The Prism peer, which comes after it (`JSLC.java:226-244`), is not
    written.
  - `InternalError` is the `java.lang.Error` documented for an unexpected internal error of the JVM. This one has no
    location, and its "not yet" has been there since the compiler was open-sourced (`d0a313da5d`, 2012).
- 7 of the 12 `InternalError` sites (above) can be reached from a `.jsl` file. Each was reached with a small change to
  a copy of `Brightpass.jsl`, run as above: exit 1 after writing the same 5 kinds of file, each set identical to that
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

  The generators have one more, `src/main/jsl-decora/CompilePhong.java:104`, the `default` of a switch over the
  three light types it iterates (`:82`).
- The build passes `-java` for 8 of the 10 Decora shaders (`src/main/jsl-decora/GenAllDecoraShaders.java:52`,
  `:64-71`), so an edit to any of them can meet these limits. The programs compiled without `-java` (the Prism
  generator's and `LinearConvolve*`) never reach these sites.
- The 5 files written before the error do not hide it. Measured with one output directory reused without `clean`, as
  `target/gensrc` is, after a successful build of the original file:
  - the edited file: exit 1, the 3 shaders and 2 headers rewritten, the 2 peers of the earlier build kept;
  - again, unchanged: exit 1, no file rewritten. The shaders are newer than the source, but the Java peer is older
    (or missing, after a failed clean build, also measured), so it is out of date (`JSLC.java:249-251`), the program
    is parsed and the backend fails again;
  - fixed by an edit (`float h = halve(luminance);` and `sign(h)`): exit 0, all 7 files rewritten;
  - only a fix that leaves the file older than the outputs goes unnoticed: the original restored with `cp -p` gives
    exit 0 with no file rewritten, so the shaders of the rejected program, which call `halve`, stay beside the peers
    of the earlier build. The up-to-date check treats any file restored with an older time that way, with or without
    an error.

### The single-file entry point can leave the file open
- `JSLC.compile(JSLCInfo, File)` (`JSLC.java:140-144`) opens a `FileInputStream` and passes it on. The stream is read,
  and closed by `CharStreams.fromStream` (it closes the channel in a try-with-resources, ANTLR 4.7.2 runtime), only
  when an output is out of date and the program is parsed (`JSLC.java:193-244`). When every output is up to date, it
  is not closed until a garbage collection finds it (its cleaner closes it) or the JVM exits.
- Callers: the Decora `CompileJSL` (`src/main/jsl-decora/CompileJSL.java:75`), for 6 of the build's programs;
  `CompileExternal.java:44` in the same directory and `JSLC.main` (`JSLC.java:434`), which the build does not run.
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
- Not run for these two additions: a fixed compiler (nothing was prototyped), Maven, macOS.

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
    `buildtools/jslc/pom.xml:118`, picks it up) asserts the full message, file, line and column included, for:
    - with every option, one named piece with an error on a line other than its first;
    - with (a) or (b), a program of two named pieces with an error in the second, on a line whose number in the
      program differs from its number in the piece.

    With (c), the generators parse the pieces, and the ten generator cases below cover them.
  - The ten generator cases are rerun as the pom runs the generators, and their output is kept with the change.
- **Generator text.** An error in the generator's own text names the generator and the program, for example after
  breaking `declPosVar` in a scratch copy of `CompilePhong`.
- **The source file is closed on every path.** A jslc test calls `JSLC.compile(JSLCInfo, File)` 20 times with every
  output up to date and 20 times with one out of date, each time on a fresh copy of a `.jsl` file, and checks after
  each call that the copy is closed: on Windows by deleting it, on Linux by finding no link to it in `/proc/self/fd`.
  Elsewhere it is skipped with an assumption. Today the calls with every output up to date fail it on both systems.
  - It is not portable: deleting an open file succeeds on Linux (measured above), so the Windows check would pass
    there today, and macOS has neither check. CI runs the jslc tests on macOS too (US-008); this one is skipped
    there.
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
    the node's for a backend error such as `unroll`;
  - if it is a test of a Java-peer limit, with the `InternalError` of its site restored, and with the check moved out
    of the Java backend into the visitor (its shader-only compile then fails).

  The repository version passes.
- **Messages without a file.** The 9 parser tests that pin `line 1:0 ...` pass unchanged, and the 4 pinned semantic
  messages pass as decided under Part 2.
- **Generated files unchanged.** Regenerate Decora and Prism with `-d3d -es2 -mtl` before and after the fix. All 1,059
  files are identical by name and md5.
- **Build gate,** with the two commands the US-008 gate ran:
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`.
    The Linux font goldens were captured on another machine (see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` is identical by name and md5 before and after. In the
    US-008 gate these held 108, 424, 46 and 0 files on each system, so the Metal output is covered only by the
    `-d3d -es2 -mtl` regeneration above.
  - `javafx.graphics` has the same test counts per class as before.
  - `javafx-jslc` runs its 159 tests plus the new ones, with no failure.
- **Upstream.** Repeat the JBS search when the story is picked up. Draft an upstream issue with the measured cases
  and the chosen option. The draft says that upstream runs no jslc test (JDK-8226635), so the fix's test needs that
  port too.

## Definition of Done
- All acceptance criteria met, with the logs and md5 lists of both gate runs kept with the change.
- The option chosen for Part 1, (a), (b) or (c), and the reason recorded in this file.
- An independent review repeated the mutant proofs with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- The upstream issue draft kept outside the repository until it is filed.

## Notes
- Filed rather than fixed in US-008: US-008 removes dead code without changing behaviour, and this fix changes the
  compiler's error messages and the way the generators build programs.
- US-019 (trailing input) and US-020 (either option: a glue block that ends early; under its option (b) also a block
  on one line) add syntax errors, and US-020's check of glue in a Prism program adds an error of its own. All report
  the line of the assembled program, or none, until this story is done.
- The first error stops the generator and the build. For `Blend.jsl` and `PhongLighting.jsl` the offset therefore
  depends on the program compiled first, and an error in a file used by many programs is reported for the first of
  them only.
- The probe drivers, the edited inputs and the variant compilers were not kept in the repository. The Problem and
  Measured sections describe each repro fully enough to rebuild it.
