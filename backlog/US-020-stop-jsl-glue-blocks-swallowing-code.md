# US-020 — Stop a JSL glue block swallowing the code after it

**Status:** 🔷 Implemented (2026-11-14; filed 2026-09-27); the Windows and WSL gates and filing the upstream issue
are [owner-run](#owner-run-and-pending) · **Found:** 2026-09-27, US-008 (filing US-019: two of its parse cases had two
glue blocks; one lost the function between them with no error, the other failed with `Unknown variable x`)

## Story
As a developer who edits a Decora or Prism `.jsl` shader, or who reviews such an edit,
I want each `<< ... >>` glue block to end at its own `>>`, and a glue block in a Prism shader, where no output can
hold it, to be an error,
so that neither a second glue block nor a later `>>`, even one in a JSL comment, can absorb the declarations after the
block, which then vanish from every shader and reach the generated Java peers as text, with an error, if any, that
points somewhere else, and so that Java text in a Prism shader does not vanish without a word.

## Problem
Paths are relative to `modules/javafx.graphics` unless they start with `backlog/` or `buildtools/`. A bare line number
(`:NN`) refers to the file named in the same sentence, else to the one named in the lead-in of its list.

### What a glue block is
- The lexer rules are `LEFT_FRENCH : '<<'` and `RIGHT_FRENCH : '>>'`
  (`src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4:383-384`) and `GLUE_BLOCK : LEFT_FRENCH .* RIGHT_FRENCH ;`
  (`:487-489`). The parser rule `glue_block` (`:354-356`) is one alternative of `external_declaration` (`:341-345`),
  which `translation_unit` repeats (`:337-339`): the grammar allows any number of glue blocks, anywhere between
  declarations.
- The visitor cuts off the delimiters (`src/jslc/java/com/sun/scenario/effect/compiler/tree/JSLVisitor.java:738-740`).
- The text is Java. It is pasted verbatim into the class body of the two generated Java peers:
  - `PrismBackend.visitGlueBlock` (`src/jslc/java/com/sun/scenario/effect/compiler/backend/prism/PrismBackend.java`
    `:149-151`) and `JSWTreeScanner.visitGlueBlock`
    (`src/jslc/java/com/sun/scenario/effect/compiler/backend/sw/java/JSWTreeScanner.java:195-197`, into
    `JSWBackend.java:291-294` in the same directory) append each block to a buffer;
  - the templates emit that buffer inside the class
    (`src/jslc/resources/com/sun/scenario/effect/compiler/backend/prism/PrismGlue.stg:60` and
    `src/jslc/resources/com/sun/scenario/effect/compiler/backend/sw/java/JSWGlue.stg:63`). Two blocks are
    concatenated in order.
- It holds the accessors the generated code calls. For every `param` that is not a sampler, the upload code of the
  Prism peer calls `<accessor>()` (`PrismBackend.java:89-108`; samplers take `:83-88`, without one), for example
  `getThreshold()`, which the glue block of `src/main/jsl-decora/Brightpass.jsl:26-30` defines.
- The shader backends ignore it: `TreeScanner.visitGlueBlock` is empty
  (`src/jslc/java/com/sun/scenario/effect/compiler/tree/TreeScanner.java:113-114`), and `SLBackend`, the base of the
  HLSL, GLSL and Metal backends, does not override it. The Prism generator writes shaders and `_Loader` classes only,
  and its loader backend ignores glue blocks too (see "A glue block in a Prism shader vanishes" below).
- 13 of the 32 Decora `.jsl` files have one glue block each, from a `<<` alone on line 26 to a `>>` alone on its line.
  Each of the 13 has one `<<` and one `>>` in all. No `src/main/jsl-prism` file and no jslc test has a glue block,
  and no jslc test covers one.

### A glue block runs to the last `>>` of the program
- ANTLR 4 matches `.*` in a lexer rule greedily, and the lexer takes the longest match: `GLUE_BLOCK` runs from the
  first `<<` to the last `>>` of the program. ANTLR warns about the rule in every build, in both US-008 gate logs
  (WSL; the Windows log has `\` separators): `[WARNING] warning(131): com/sun/scenario/effect/compiler/JSL.g4:482:19:
  greedy block ()* contains wildcard; the non-greedy syntax ()*? may be preferred` (`JSL.g4:488:19` since US-019
  added `UNTERMINATED_COMMENT` above the rule).
- That last `>>` can be the closer of a second glue block, or any `>>` after the block, even in a JSL `//` or `/* */`
  comment: the lexer is still inside the `GLUE_BLOCK` token that started at `<<`. JSL has no `>>` operator.
- Parse results with `JSLC.getParserInfo(String)`, the production entry point. Each glue block holds one line of
  Java (`int a;`, `int b;`):
  - block, `param float x;`, block, `void main() { color = float4(x); }`: `java.lang.RuntimeException: Unknown
    variable x`. The `param` is inside the one glue token.
  - block, `float helper() { return 1.0; }`, block, the same `param` and `main`: no error, 3 declarations (a glue
    block, `x`, `main`). `helper` is gone, and the glue text is `int a;`, `>>`, the `helper` line, `<<`, `int b;`.
  - two adjacent blocks: one glue block, whose text contains `>>` and `<<`.
  - one block, the same `param`, `// x >> 1 would halve it`, the same `main`: no error, 1 declaration. The glue text
    is `int a;`, `>>`, the `param` line, `// x`, and the parser stops at the `1` after it (`5:8`), so `x` and `main`
    are gone. The comment written as `/* */`, or placed inside `main`, has the same effect.
- US-019's fix options do not change the two-block results (measured with its prototypes): the parser does reach EOF.
  They turn the comment case into an error at the token after the `>>`, inside the comment: `line 5:8 extraneous
  input '1' expecting {<EOF>, 'const', ...}` with its option (a).

### What it does to a shader
The Decora generator of the build, `CompileJSL` (`src/main/jsl-decora`), was run on copies of `Brightpass.jsl` with the
output types `GenAllDecoraShaders` passes for it (`-java -hw`) and `-d3d -es2 -mtl`. The two generated peers were
compiled with `javac` against a `javafx.graphics` build (Measured has the setup). In the first three cases a second
glue block holds a Java method, `getHalfThreshold()`; in the first two, a JSL function `halve` comes before it:
- **The function and the second block after the first block** (after line 31; `main` does not call the function):
  exit 0, 7 files.
  - The HLSL, GLSL and Metal outputs are byte-identical to those of the unmodified shader: the function is in none
    of them.
  - Both peers contain the function, as JSL text between a `>>` line and a `<<` line. `javac` fails on each with 2
    errors: `JSWBrightpassPeer.java:61: error: illegal start of type` at the `>>` and `:68` at the `<<`
    (`PPSBrightpassPeer.java:59` and `:66`).
- **The same, with `main` calling the function** (`luminance = halve(luminance);`): exit 1,
  `Unknown function halve(float)`, for a function the file defines.
- **The second block between the two `param` declarations** (after line 32): exit 1, `Unknown variable baseImg`.
- **No second block, a `>>` in a comment of `main`** (`    // luminance >> 1 would halve it` as line 40): exit 0,
  7 files (2026-09-27; the End to end criterion gives the result since US-018 and US-019). The `.hlsl` is empty, the
  `.frag` is the 14-line GLSL preamble alone, and the `.metal` has 8 lines, without the shader function. `javac` fails
  on the peers with 16 errors (`JSWBrightpassPeer.java:61`, `illegal start of type`, first) and 10
  (`PPSBrightpassPeer.java:59`).
- With the rule of option (a) or of option (b) below, all four exit 0 with 7 files and both peers compile. The
  function is in the `.hlsl`, `.frag` and `.metal` outputs of the first two, whose peers contain both blocks, and
  the 7 files of the fourth are identical to those of the unmodified shader.
- In the build, the second-pass `javac` of the module (`pom.xml:221-243`) compiles the generated peers. Every Decora
  shader the build compiles gets a Prism peer (`-hw`, `src/main/jsl-decora/GenAllDecoraShaders.java:63-74`), so a
  shader with a `>>` after its glue block (a second block's closer, or one in a comment) cannot pass the build today.
  It fails with one of the errors above, and none of them points at that `>>`.

### A glue block in a Prism shader vanishes
- The Prism generator (`src/main/jsl-prism/CompileJSL.java`) writes, for each of its 212 programs, one shader per
  backend and a `_Loader` class (`:417-430`). Its loader backend, `PrismLoaderBackend` (`:511-562`), is a
  `TreeScanner` that does not override `visitGlueBlock`, and the loader template
  (`src/main/jsl-prism/PrismLoaderGlue.stg`) has no slot for glue text. No output of the Prism generator can hold a
  glue block.
- Measured with the Prism generator run as the pom runs it (30 JVMs, `-d3d -es2 -mtl`), on copies of the Prism
  inputs with:
  - a glue block of five lines in the form of `Brightpass.jsl:26-30` (a Java method), after line 25 of
    `PaintImagePattern.jsl`, a paint of 22 programs, or after line 25 of `MaskFillPgram.jsl`, a mask;
  - `<< this is not Java at all >>` after line 25 of `PaintImagePattern.jsl`.

  In each case all 30 JVMs exit 0 and print nothing, and the 850 files (848 `jsl-prism`, 2 `mtl-headers`) are
  identical by name and md5 to those of the unmodified inputs. The parser does read the block: the program of
  `Solid_ImagePattern` parses to a glue block followed by the 7 declarations of the unmodified program (5 `param`s,
  `paint` and `main`).
- Nothing later in the build reads it: the shaders go to `fxc` or the Metal compiler, and the loaders to `javac`.
- A Decora program compiled with shader outputs only drops its glue block too. That is by design, since only the
  peers use the block:
  - the peers call the accessors it defines (above), and a shader cannot hold Java;
  - measured on `Brightpass.jsl`: `CompileJSL -d3d -es2 -mtl Brightpass` exits 0 with 5 files, the three shaders and
    both Metal headers, identical to those of the run with `-java -hw`. Without its glue block the 5 files are
    unchanged, and both peers fail `javac` with `cannot find symbol` at the accessor call
    (`PPSBrightpassPeer.java:78`, `JSWBrightpassPeer.java:105`);
  - the build compiles every Decora shader with `-hw` (`src/main/jsl-decora/GenAllDecoraShaders.java:52-55`,
    `:63-74`), and each of the 10 it compiles has a glue block, so every Decora block reaches a Prism peer. A
    shader-only run by hand writes no peer, so none of its outputs needs the block.

### A regression of the ANTLR 4 upgrade, upstream too
- The rule has read `LEFT_FRENCH .* RIGHT_FRENCH` since the compiler was open-sourced (`d0a313da5d`, 2012). In an
  ANTLR 3 lexer rule, `.*` is not greedy.
  - Measured: the grammar of `52adea7c36^` (`JSL.g:662-663` there), built with `antlr-complete` 3.5.2, the version
    the build used then (`build.gradle:1921` at `52adea7c36^`), lexes each block as a token of its own. Its parser
    returns 4 declarations for the first parse case above (block, `x`, block, `main`) and 5 for the second (block,
    `helper`, block, `x`, `main`).
  - The same lexer ends a block at the first `>>` of its Java text, for example in
    `java.util.List<java.util.List<String>>`, in `v >> 1` or in a `//` comment. The parser then stops there and drops
    the rest of the program without an error (the gap US-019 describes).
- `52adea7c36` (8218170, the ANTLR 4.7.2 upgrade, 2019) converted the grammar. It turned the explicit
  `options {greedy=false;}` of `COMMENT` (`JSL.g:654-656` at `52adea7c36^`) into `(.)*?` (`JSL.g4:473-475`), but kept
  the text of `GLUE_BLOCK`, whose `.*` became greedy. Neither `8889330cc3` (8221269) nor `976a763852` (8240499), the
  later commits to the grammar, changed the rule, and the fork has not changed it.
- No shipped shader has had two glue blocks or any other `>>`. The only commit whose diff adds or removes a `.jsl` line
  containing `<<` or `>>` is `b4d6d9772f` (2013), which adds 13 lines of each, one block in each of the 13 files.
- `openjdk/jfx` master (`d46e6092ae`, checked 2026-09-27) has the same `JSL.g4`, identical to this tree's, and the same
  `JSLVisitor.java`, `PrismBackend.java` and `JSWBackend.java`. Its Prism `CompileJSL.java`, `TreeScanner.java` and
  `SLBackend.java` are identical to this tree's too, so a glue block in a Prism shader vanishes there as well.
- JBS (searched 2026-09-27, project JDK): no issue reports it. Searched: text `glue` and `JSL`, component `javafx`
  (0 issues); the phrase `glue block`, component `javafx` (0); text `GLUE_BLOCK` (9, none about JSL); text `greedy`,
  component `javafx` (6, none about JSL); text `glue`, `prism` and `jsl`, component `javafx` (0). JDK-8218764, "Port
  JSLC from antlr3 to antlr4", is closed as a duplicate of JDK-8218170.

### Shipped shaders do not depend on the greedy rule
- The 13 glue blocks contain no `>>` besides their closer, and each closer is alone on its line. No shipped `.jsl`
  file and no generator text has any other `>>`.
- With the rule of option (a), the Decora and Prism generators, run as the pom runs them with `-d3d -es2 -mtl`,
  write all 1,059 files (208 `jsl-decora`, 848 `jsl-prism`, 3 `mtl-headers`) identical by md5 to the current
  compiler's. Those contain the 108 `jsl-decora` and 424 `jsl-prism` files of the US-008 Windows gate build,
  identical by md5. The same holds for option (b), and for each option combined with US-019's option (a).
- The three drivers the build does not call, `CompileBoxBlur`, `CompileGaussian` (`GaussianBlur`) and
  `CompileZoomRadialBlur`, read the other 3 glue blocks. With (a), their 137 files are identical too.
- The 159 jslc tests pass with (a), with (b), and with each plus US-019's option (a).

### Measured (2026-09-27, Windows 10, JDK 26)
- The JSL compiler was compiled from a copy of `src/jslc` identical to the working tree, with the parser generated by
  ANTLR 4.7.2 (`-visitor`). The generators were compiled and run as in the Measured section of US-019, the way the
  pom runs them (`pom.xml:156-216`).
- The peers were compiled with `javac --patch-module javafx.graphics=<generated sources>` against the `javafx.base`
  and `javafx.graphics` classes of the US-008 Windows gate build.
- The ANTLR 3 probe: the grammar of `52adea7c36^` and its `model` and `tree` classes, with lexer and parser generated
  by `org.antlr:antlr-complete:3.5.2` (from Maven Central, checked against its SHA-1).
- Compiler variants: the current rule, options (a) and (b), and each option with US-019's option (a). The comment
  cases were also run with each US-019 prototype alone.
- Parse cases besides those above: two adjacent blocks; `>>` inside the Java text (in a generic type, a shift, a `//`
  comment, and at the start of a line); a block on one line; the generic-type case and the case with a function
  between two blocks, with CRLF line ends; an unclosed `<<`, which gives `line 1:0 extraneous input '<<' expecting
  {...}` with every rule.
- End to end, the four `Brightpass.jsl` cases with the current rule and options (a) and (b), and a fifth with
  `/* threshold >> 1 is half of it */` after line 33, which behaves as the fourth.
- The Prism glue cases and the Decora runs with and without the `Brightpass.jsl` glue block used the classes of the
  US-008 Windows gate build (`buildtools/jslc/target/classes`, `target/jsl-compilers/{decora,prism}`), run as the pom
  runs them. With them, the `-d3d` regeneration of the unmodified Prism inputs is identical by name and md5 to the
  gate's `target/gensrc/jsl-prism` (424 files). The parse of `Solid_ImagePattern` rebuilt its text as the Prism
  generator does (`src/main/jsl-prism/CompileJSL.java:392-405`) and called `JSLC.getParserInfo(String)`.

## Proposed fix
The evidence decides one question: a second glue block is legal. The grammar repeats `external_declaration`, both peer
backends concatenate blocks, and the compiler accepted two blocks until 2019. So the rule must end each block at its
own closer. Two options for the closer; the choice is part of the story.
- **(a) Non-greedy:** `GLUE_BLOCK : LEFT_FRENCH .*? RIGHT_FRENCH ;`.
  - It restores the behaviour before `52adea7c36` and removes `warning(131)`.
  - A `>>` in the Java text ends the block early, as it did before 2019: a generic type such as `List<List<String>>`
    (to be written `> >`), a shift `>>` or `>>>`, or `>>` in a comment or string. Today such a block works.
  - Without US-019, the rest of the program is then dropped without an error: a block holding
    `private java.util.List<java.util.List<String>> l;` gives 1 declaration, and the `param` and `main` after it are
    gone. With US-019's option (a), it is a syntax error at the token after the early `>>`:
    `line 2:47 extraneous input 'l' expecting {<EOF>, 'const', ...}`. So (a) lands with or after US-019, and the
    comment of the rule says that glue text cannot contain `>>`.
- **(b) A closer at the start of a line:** `GLUE_BLOCK : LEFT_FRENCH .*? '\n' [ \t]* RIGHT_FRENCH ;`.
  - The block ends at the first line that starts with `>>` after blanks, as all 13 shipped blocks do. A `>>` inside
    a line of Java stays in the block (generic type, shift and comment measured). It removes `warning(131)` too.
  - A block on one line, `<< int a; >>`, accepted today and by (a), becomes a syntax error:
    `line 1:0 extraneous input '<<' expecting {...}`.
  - A Java line that starts with `>>` (a wrapped shift) still ends the block early, and as under (a), the rest of the
    program is then dropped without an error: a block holding `private int h(int v) { return v` and `    >> 1; }`
    gives 1 declaration. With US-019's option (a), it is a syntax error:
    `line 3:7 extraneous input '1' expecting {<EOF>, 'const', ...}`. So (b) also lands with or after US-019.
  - CRLF line ends work: `.*?` takes the `\r` before the `\n`.
- **Not proposed: rejecting a second block.** A check in the visitor could reject it, with (a) or (b). It narrows the
  language against the evidence above, and it is not needed once each block ends at its own closer.
- With either option, `JSL.g4` differs from upstream's until upstream takes the change.
- **A glue block in a Prism program is an error,** which names the program and, with US-022, the file and line. Two
  places; the choice is part of the story. Neither was prototyped.
  - **(c) In the Prism generator:** `PrismLoaderBackend` overrides `visitGlueBlock` to throw. A few lines in
    `src/main/jsl-prism/CompileJSL.java`, run whenever a program's `_Loader` is written, which is whenever its
    shaders are (the same source time, `CompileJSL.java:422-425`). The shaders of that program are written by then,
    and the next build without `clean` fails the same way, since the loader is still out of date. Only an end-to-end
    run tests it: the jslc tests do not reach `src/main/jsl-prism`.
  - **(d) In the compiler:** an option of `JSLCInfo`, set by the Prism generator, makes `JSLC.compile` reject a glue
    block once the program is parsed, before its first output is written. A jslc test can pin the message through
    `JSLC.compile(JSLCInfo, String, long)`, as `SymbolTest.compile` does
    (`src/test/jslc/com/sun/scenario/effect/compiler/SymbolTest.java:46-53`). Cost: one more public field in the
    compiler's options.
  - Not proposed: rejecting a glue block whenever no peer is written. It would fail a Decora shader-only run, which
    needs no glue (above).

## Acceptance criteria
- **Each block ends at its own closer.** A new jslc test in `src/test/jslc/com/sun/scenario/effect/compiler` (the
  surefire include, `buildtools/jslc/pom.xml:118`, picks it up) calls `JSLC.getParserInfo(String)` and asserts the
  declarations, in order, with the text of each glue block, for:
  - block, `param`, block, `main` (today `Unknown variable x`);
  - block, function, block, `param`, `main` (today 3 declarations, without the function);
  - two adjacent blocks (today one block);
  - one block, `param`, a `//` comment containing `>>`, `main` (1 declaration, without an error, before US-019; with
    US-019, `line 5:8 extraneous input '1'`, inside the comment).
- **The edge of the chosen rule is pinned.** For (a): a block with `>>` inside a Java line fails with a message that
  starts with `line L:C` and the cause (this needs US-019). For (b): the same block parses as one declaration with its
  full text, a block on one line fails with such a message, and so does a block with a Java line that starts with
  `>>` (this needs US-019). The rest of ANTLR's expected-token list is not pinned, because it changes with the token
  vocabulary, which this story changes (US-019's code review, finding 4).
- **Mutant proof,** as for every jslc negative test (`backlog/US-008-remove-dead-jslc-me-backend-and-simd.md:159-161`).
  In scratch copies:
  - each test of the first criterion fails with the current rule (`.*`) in place of the new one;
  - each edge test fails with the rule of the other option in place of the chosen one, except the wrapped-shift test
    of (b): (a) rejects that block with the same message, so it must fail with the current rule instead;
  - each negative test fails with a valid program in place of the invalid one, and with an input that fails for
    another reason (an unclosed `<<`: `line 1:0 extraneous input '<<' ...`).

  The repository version passes.
- **End to end.** The Decora generator on a copy of `Brightpass.jsl` with the function and the second glue block after
  line 31 exits 0. The function is in the `.hlsl`, `.frag` and `.metal` outputs, and both peers contain both blocks
  and compile. Today the function is in none of the shaders, and each peer has 2 `javac` errors. A copy with
  `    // luminance >> 1 would halve it` as line 40 writes the 7 files of the unmodified shader. On 2026-09-27 its
  `.hlsl` was empty and the peers had 16 and 10 `javac` errors. At `d803860b7e` (after US-018) the generator exits 1
  with `Cannot read Brightpass_Uniforms from the Metal shader ...` after writing the `.hlsl`, `.frag` and `.metal`.
  With US-019 it exits 1 with `line 40:20 extraneous input '1' ...` and writes nothing.
- **Glue in a Prism program is an error.** The Prism generator, run as the pom runs it with `-d3d -es2 -mtl`, exits
  1 on the copy with the five-line glue block after line 25 of `PaintImagePattern.jsl`. The message says that a
  Prism program cannot have a glue block and names `Solid_ImagePattern`, the first program with that paint; the copy
  with the block in `MaskFillPgram.jsl` names `FillPgram_Color`. Today both exit 0 with the 850 files of the
  unmodified inputs. With (d), a jslc test also pins the full message.
  - A Decora shader-only run is unchanged: `CompileJSL -d3d -es2 -mtl Brightpass` exits 0 with the 5 files of today.
  - Mutant proof, in scratch copies: with the check removed, both runs exit 0 and fail this criterion; the unmodified
    Prism inputs exit 0 with the 850 files of today; `    float v = undefinedFunc(1.0);` after line 34 of
    `PaintImagePattern.jsl` fails with `Unknown function undefinedFunc(float)`, not the pinned message; and a check
    that rejects glue whenever no peer is written fails the Decora run. With (d), the jslc test fails with the check
    removed, and with a valid program or the `Unknown function` input in place of its program.
- **No ANTLR warning.** The build log has no `warning(131)`; both US-008 gate logs have it today.
- **Generated files unchanged.** Regenerate Decora and Prism with `-d3d -es2 -mtl` before and after the fix: all 1,059
  files are identical by name and md5. Run `CompileBoxBlur`, `CompileGaussian GaussianBlur` and
  `CompileZoomRadialBlur` before and after: their 137 files are identical too.
- **Build gate,** with the two commands the US-008 gate ran:
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`.
    The Linux font goldens were captured on another machine (see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` is identical by name and md5 before and after. In the
    US-008 gate these held 108, 424, 46 and 0 files on each system, so the Metal output is covered only by the
    `-d3d -es2 -mtl` regeneration above.
  - `javafx.graphics` has the same test counts per class as before.
  - `javafx-jslc` runs its 196 tests plus the new ones, with no failure.
- **Upstream.** Repeat the JBS search when the story is picked up. Draft an upstream issue with the two-block and
  comment repros, the ANTLR 3 comparison, the Prism glue repro and the chosen options. The draft says that upstream
  runs no jslc test (JDK-8226635), so the fix's test needs that port too.

## Definition of Done
- All acceptance criteria met, with the per-class lists and `target/gensrc` md5 lists of both gate runs kept with
  the change (amended 2026-11-14, as US-019's was after its code review: the gate logs stay with the clones and are
  not kept).
- The chosen options and the reasons recorded in this file: (a) or (b), with the order with US-019, and (c) or (d).
- An independent review repeated the mutant proofs with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- The upstream issue draft kept outside the repository until it is filed.

## Notes
- Filed rather than fixed in US-008: US-008 removes dead code without changing behaviour, and this fix changes which
  programs the compiler accepts, so it needs its own change and its own shader md5 gate.
- The grammar changes of US-019 and this story are independent, their effects are not: under either option, a block
  that ends early is an error only with US-019. Both in one change need one md5 gate; both combinations with US-019's
  option (a) were measured (1,059 files identical, 159 tests pass).
- US-019 alone turns the comment case into an error, but one that points into the comment, at the token after its
  `>>`, and it does not catch the two-block cases.
- The Prism check, (c) or (d), does not depend on the rule change or on US-019; it can land before or after them.
- The probe drivers, the ANTLR 3 build, the variant compilers, the mutant runs and the generator runs were not kept in
  the repository. The Problem, Measured and Implementation sections describe each run fully enough to repeat it.

## Implementation (2026-11-14, macOS)
The change is described against `9186c05be8`, the baseline ("before"), which already holds US-019. Everything was
built and run on one arm64 Mac: macOS 27.2.1 (build 26B131), Apache Maven 3.10.0 and Oracle Corporation's OpenJDK
27.0.1.0.2 (build 27.0.1.0.2+1-2). This host has no Windows and no Linux, so those gates are
[owner-run](#owner-run-and-pending). Paths are relative to `modules/javafx.graphics`, as in Problem.

The implementation's own probes found inputs that still parse without an error under option (a); a first rework pinned
them with two tests. An independent review then returned one must-fix and five other findings. A second rework fixed
the must-fix and took two of the others, and a confirming review passed. This section describes the final change;
[Independent review](#independent-review-2026-11-14) and
[Reworks and confirming review](#reworks-and-confirming-review-2026-11-14) give the findings and their dispositions.

Kept with the change: the macOS gates' per-class list, `backlog/US-020-macos-per-class.tsv`, and their
`target/gensrc` md5 list, `backlog/US-020-macos-gensrc.md5`. The probes, the mutant runs and the generator runs ran in
temporary scratch directories; they and their logs are not kept ([Notes](#notes)). The cases D0 to D5 and P0 to P6
and the mutants M1, M2, M3 and F are those of [Generator runs](#generator-runs).

### Choice: options (a) and (d)
**(a), the non-greedy rule, not (b).**
- It is a one-token change, `.*` to `.*?`, in the idiom of `COMMENT` (`JSL.g4:473-475`), which `52adea7c36`
  converted from ANTLR 3's `options {greedy=false;}` to `(.)*?`.
- It restores the behaviour of 2012 to 2019, so upstream can take it as the fix of a regression, not as a language
  change.
- It removes `warning(131)`.
- It keeps every layout legal that was legal: a block on one line, an empty block (`<<>>`), CR-only line ends and a
  closer after code on its line. (b) rejects each of them ([Behaviour changes](#behaviour-changes)).
- US-019 is in the baseline, so the story's order, (a) with or after US-019, holds: an early `>>` is normally a syntax
  error at the token after it.
- (b) was measured too. Under (b), a block on one line followed by a later block swallows the code between them. The
  implementation's probe `<< int a; >>`, `param float x;`, a block with `int b;` on a line of its own, and
  `void main() { color = float4(x); }` gives `java.lang.RuntimeException: Unknown variable x` under (b), and 4
  declarations (block, `x`, block, `main`) under (a).
- The independent review's verdict: "Sane: (a) is still the better of the two, narrowly".

**The known limit of (a), accepted and not fixed.** An early `>>` still parses, with another meaning, when a `<<`, or
a `/*` that a later `*/` closes, follows it with only blanks, comments or valid JSL between:
- `String s = ">><<";` in a block gives two blocks, `String s = "` and `";`. The peers concatenate the blocks' text, so
  they would get `String s = "";`.
- A block whose Java line is `// x >> /* shift` ends at that `>>`. The `/*` then opens a JSL comment that hides the
  block's real closer and every declaration up to the next `*/`, here `param float y;`, without an error.

`aCloserAndAnOpenerInAJavaStringSplitTheBlockInTwo` and `aCommentOpenerAfterAnEarlyEndHidesTheCodeUpToTheNextCommentEnd`
pin both. No shipped glue block has a `>>` other than its closer.

**(d), the check in the compiler, not (c).**
- `JSLC.compile` parses lazily, just before the first out-of-date output, so the check rejects a program before its
  first output is written. (c) would run only after the program's shaders are written.
- jslc tests pin the exact message through `JSLC.compile(JSLCInfo, String, long)`; (c) could be tested only end to
  end.
- The generator's fallback parse for the `_Loader` class goes through the same check.
- The cost: a public field, `JSLCInfo.prismProgram`, and a public method, `JSLC.getParserInfo(JSLCInfo, String)`,
  public because its caller, the Prism `CompileJSL`, is in the default package.

**The limits of (d).**
- The two edits in `src/main/jsl-prism/CompileJSL.java`, the flag (`:475`) and the fallback parse (`:427`), are outside
  the jslc tests. Only the end-to-end runs guard them (mutants M2 and F).
- The error stops the generator's JVM, but programs compiled earlier in the same JVM keep their files (4 in P1, 64 in
  P2), and no Metal header is written.
- The generator ignores `-name`, so the error appears in the first of the 30 JVMs, the one for `MaskAlphaOne.jsl`,
  whichever file holds the block, and that JVM's argument does not name the file. That is
  [US-023](US-023-run-the-prism-shader-generator-once.md)'s subject.
- The generator reads `MaskSolid.jsl`, `MaskAlphaOne.jsl` and `PaintColor.jsl` but puts their text into no program
  ("can omit trivial" `mask()` and `paint()` calls, `src/main/jsl-prism/CompileJSL.java:362-378`), so a glue block in
  one of them is never parsed and is not detected (P6). Filed as
  [US-113](US-113-make-an-error-in-any-prism-generator-input-fail-the-build.md).

### Change
Relative to `9186c05be8`, eight files:
- `src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4`, `:487-491` (`GLUE_BLOCK` was at `:487-489`):
  ```
  // The Java text of a glue block ends at its first ">>", so the text cannot contain ">>": a nested generic type is
  // written "> >".
  GLUE_BLOCK
      : LEFT_FRENCH .*? RIGHT_FRENCH
      ;
  ```
  The header already reads `2008, 2026`.
- `src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java`, 492 lines:
  - `getParserInfo(JSLCInfo, String)`, public (`:111-113`), and `getParserInfo(JSLCInfo, InputStream)`, private
    (`:115-121`), which parses as `getParserInfo(InputStream)` does and then checks:
    ```java
    ParserInfo pinfo = getParserInfo(stream);
    if (jslcinfo.prismProgram && pinfo.program.getDecls().stream().anyMatch(GlueBlock.class::isInstance)) {
        throw new RuntimeException("A Prism program cannot have a glue block: " + jslcinfo.shaderName);
    }
    return pinfo;
    ```
    `RuntimeException` is what the compiler throws for its other semantic errors (`Unknown variable`,
    `Unknown function`);
  - the five lazy parses of `compile` call it: D3D `:213`, ES2 `:223`, MTL `:237`, JAVA `:255` and PRISM `:265`;
  - the field (`:314-319`):
    ```java
    /**
     * Set by the Prism generator ({@code src/main/jsl-prism}): no output of its programs can hold a glue block's
     * Java text, so a glue block is an error. A Decora program with a Prism peer ({@code OUT_PRISM}) leaves it
     * unset.
     */
    public boolean prismProgram;
    ```
  - the import of `com.sun.scenario.effect.compiler.tree.GlueBlock`.

  `getParserInfo(String)` and `getParserInfo(InputStream)` (`:100-109`) do not change and do not check. The header
  already reads `2008, 2026`.
- `src/main/jsl-prism/CompileJSL.java`, 566 lines: `jslcinfo.prismProgram = true;` in `main` (`:475`), before
  `parseAllArgs`, and the `_Loader` fallback parse of `compileShader` becomes `JSLC.getParserInfo(jslcinfo, source)`
  (`:427`). The header already reads `2009, 2026`.
- `src/test/jslc/com/sun/scenario/effect/compiler/ProgramBase.java`, 114 lines:
  - `describe` gives a glue block as `GlueBlock ` followed by its text (`:110`);
  - `declarationsOf(JSLC.ParserInfo)` (`:87-93`), to which `declarationsOf(String)` delegates, lists the declarations
    of a parse made through `getParserInfo(JSLCInfo, String)`;
  - moved here from `TranslationUnitTest`: `regularFileNamesUnder` (`:95-102`). Moved here from `TranslationUnitTest`
    and `PrismProgramTest`, the fixture both used (review finding 3): `EVERY_OUTPUT_TYPE` (`:55-56`,
    `OUT_D3D | OUT_ES2 | OUT_MTL | OUT_JAVA | OUT_PRISM`), `PROBE_FILES_FOR_EVERY_OUTPUT_TYPE` (`:58-59`, the five
    files of a run with every output type), `OUT_OF_DATE` (`:61`, `Long.MAX_VALUE`, so every output is out of date)
    and `probeRun(Path, int)` (`:63-70`), the `JSLCInfo` of a shader `Probe` whose output and Metal header
    directories are under the given directory.
- `src/test/jslc/com/sun/scenario/effect/compiler/TranslationUnitTest.java`, 97 lines: its copies of that fixture and
  three imports are gone, and its two compile tests use `probeRun(tmp, EVERY_OUTPUT_TYPE)` and
  `PROBE_FILES_FOR_EVERY_OUTPUT_TYPE`. Its 7 tests and their assertions are those of `9186c05be8` (confirming review).
- New, in `src/test/jslc/com/sun/scenario/effect/compiler`: `ProgramGlueBlockTest.java` (20 tests),
  `lexer/GlueBlockTest.java` (8) and `PrismProgramTest.java` (13), described in [Tests](#tests). Their headers read
  `2026`.

The poms, the `.jsl` files, the templates, `JSLVisitor`, the backends and the Decora generators do not change.
`PrismProgramTest`'s `@ParameterizedTest` needs `junit-jupiter-params`, which the `junit-jupiter` dependency of
`buildtools/jslc/pom.xml` already brings. In the eight files there are no tabs, trailing blanks, CR bytes, wildcard
imports or `var`, each ends in one newline, and no line is over 120 columns except `JSL.g4:308` (129), which is the
same at the baseline (second rework and confirming review).

### Behaviour changes
Measured with the tests, whose results under the baseline's rule are those of mutant G0 in [Tests](#tests), and with
the probes of the implementation and of the independent review, which parse each input with `JSLC.getParserInfo`
using the baseline's jslc jar and the change's. `…` stands for the rest of ANTLR's message.

- **Each block ends at its first `>>`.** The cases of the first acceptance criterion, with one line of Java in each
  block (`int a;`, `int b;`):

  | Program | Baseline | Final |
  | --- | --- | --- |
  | Block, `param float x;`, block, `main` | `java.lang.RuntimeException: Unknown variable x` | 4 declarations: block, `x`, block, `main` |
  | Block, `float helper() { return 1.0; }`, block, `param`, `main` | 3 declarations; the block's text is `int a;`, `>>`, the `helper` line, `<<`, `int b;` | 5 declarations: block, `helper`, block, `x`, `main` |
  | Two adjacent blocks, then `param` and `main` | One block, whose text holds `>>` and `<<` | Two blocks |
  | Block, `param`, `// x >> 1 would halve it`, `main` | `line 5:8 extraneous input '1' expecting {<EOF>, …}`, inside the comment | 3 declarations |
  | The same comment as `/* x >> 1 */`, or inside `main` | `line 5:8 …`, `line 6:12 …` | 3 declarations |

  With CRLF line ends the function between two blocks is kept too. In the review's probes, a function between two
  blocks in a file with CR-only line ends, which the baseline dropped without an error (3 declarations), is kept (5),
  and a `>>` in a JSL comment after the last block, or in a `/* */` comment in `main`, which the baseline rejected
  (`line 6:15 extraneous input …`), is accepted.
- **A `>>` inside a block's Java text ends the block.** With US-019, what follows it is then a syntax error at the
  token after that `>>`. The baseline accepted each of these as one block (the first four are tests, the others the
  review's probes):

  | Java text in the block | Final |
  | --- | --- |
  | `private java.util.List<java.util.List<String>> l;` | `line 2:47 extraneous input 'l' expecting {<EOF>, …}` |
  | `private int h(int v) { return v >> 1; }` | `line 2:35 extraneous input '1' …` |
  | `// shifts a >> 1`, then `int a;` | `line 2:15 extraneous input '1' …` |
  | `// halves >>`, then `float y;`, which is valid JSL | `line 4:0 extraneous input '>>' …`: the block's real closer is a stray token |
  | `private java.util.Map<String, java.util.List<Float>> m;`, CRLF line ends | `line 2:53 extraneous input 'm' …` |
  | `private String arrow = ">>";` | `line 2:26 token recognition error at: '"'` |
  | `private int h(int v) { v >>= 2; return v; }` | `line 2:27 extraneous input '=' …` |
  | `/** Returns {@code bits >> 4}. */` | `line 2:27 extraneous input '4' …` |
  | `/* v >> 1 */` | `line 2:8 extraneous input '1' …` |
  | A shift wrapped onto a line that starts with `>> 1;` | `line 4:11 extraneous input '1' …`, as under (b) |
  | A closer line `>>>` | `line 3:2 extraneous input '>' …`; the baseline's block text ended in a stray `>` |

  `java.util.List<java.util.List<String> > l;` is one block before and after.
- **Where an early `>>` still parses,** the limit in [Choice](#choice-options-a-and-d): `String s = ">><<";` gives two
  blocks (baseline: one); `// x >> /* shift` hides `param float y;` (baseline: 4 declarations, `y` among them; final:
  3); and in the review's probes `// mask >> <<` in a Java comment gives two blocks and loses the comment's text.
  Among the review's edge inputs, only variants of these three parse, without an error, to something else.
- **Layouts that stay legal,** the same before and after in the review's probes: an empty block, a block on one line
  (also last in the file, without a newline), CR-only line ends, a closer after a tab or after code on its line,
  `List<Float> >`, `1 << 3 | 1 << 4` and non-ASCII text in a block, and a block of 10.8 MB (parsed in 58 ms; 61 ms
  before). Under (b) the empty block, the blocks on one line, the CR-only file and the closer after code are syntax
  errors (`line 1:0 mismatched input '<<' …` and similar). A `<<` or `>>` in JSL code, outside a block, is an error
  under every rule: no parser rule uses `LEFT_FRENCH` or `RIGHT_FRENCH`.
- **A glue block in a Prism program is an error.** With `JSLCInfo.prismProgram` set, `JSLC.compile` and
  `JSLC.getParserInfo(JSLCInfo, String)` throw `java.lang.RuntimeException: A Prism program cannot have a glue block:
  <shaderName>` at the program's first parse, before any of its outputs is written. Only the Prism generator sets the
  field. `JSLC.main` and the Decora generators leave it `false`, so a Decora program compiles as before, with or
  without peers.
- **Shipped inputs are unchanged.** The 62 shipped `.jsl` files, each parsed alone with `JSLC.getParserInfo`, give
  byte-identical output with the baseline's jar and the change's: 53 parse, and the 9 `%` templates fail with the same
  token recognition error. Their tokens are identical too, and each of the 13 glue blocks is one token at 26:0 with
  the same text (implementation). The review's token dump of the 62 files (14,530 lines, 13 `GLUE_BLOCK` tokens) is
  byte-identical with the baseline's jar, the change's, an option (b) jar and the change with the greedy rule.

### Acceptance criteria and evidence
| Criterion | Evidence | State |
| --- | --- | --- |
| Each block ends at its own closer | `aParamBetweenTwoGlueBlocksIsADeclarationOfItsOwn` (4 declarations), `aFunctionBetweenTwoGlueBlocksIsKept` and its CRLF variant (5), `adjacentGlueBlocksAreTwoBlocks` and `adjacentGlueBlocksOnOneLineAreTwoBlocks` (two blocks) and the three tests of a `>>` in a JSL comment (3 declarations), each with the blocks' text ([Tests](#tests)) | Met |
| The edge of the chosen rule is pinned | For (a): `aNestedGenericTypeEndsTheBlockAtItsClosingBrackets`, `aShiftInTheJavaTextEndsTheBlock`, `aShiftInAJavaCommentEndsTheBlock` and `theCloserAfterAnEarlyEndIsAStrayCloser` pin `line L:C extraneous input 'X' expecting {<EOF>, `. Added: the two tests of the limit pin what still parses | Met |
| Mutant proof | The current rule (G0): the 8 tests of the first criterion and its variants and the 4 edge tests fail; the first rework's greedy mutant also fails the 2 tests of the limit. The rule of (b) (Gb): the 4 edge tests fail, and the first rework's (b) mutant the 2 tests of the limit. A valid program (N1): the 6 negative tests of `ProgramGlueBlockTest` fail. Another failure, an unclosed `<<` (N2): the 4 edge tests fail; the 2 unclosed-`<<` tests, whose input that is, fail under the review's other inputs. The repository version passes ([Tests](#tests), [Independent review](#independent-review-2026-11-14)) | Met |
| End to end | D1: exit 0, `halve` in the `.hlsl`, `.frag` and `.metal`, both blocks in both peers, which compile; before, `halve` in no shader and 2 `javac` errors per peer. D4: the 7 files of D0; before, exit 1 with `line 40:20 extraneous input '1' …` and no file ([Generator runs](#generator-runs)) | Met |
| Glue in a Prism program is an error | P1: exit 1, `A Prism program cannot have a glue block: Solid_ImagePattern`; P2: `… FillPgram_Color`; before, both exit 0 with the 850 files of P0. D5 unchanged (5 files). Mutants: M1 (check removed) gives P1 and P2 exit 0 with 850 files; P0 exits 0 with the 850 files; P4 fails with `Unknown function undefinedFunc(float)`, not the pinned message; M3 (rejecting glue whenever no peer is written) fails D5. With (d), `PrismProgramTest` pins the whole message and fails with the check removed (7 failures), with a valid program (5) and with the `undefinedFunc` input (5) ([Tests](#tests)) | Met. Not detected: a glue block in `MaskSolid.jsl`, `MaskAlphaOne.jsl` or `PaintColor.jsl` (P6, US-113) |
| No ANTLR warning | The macOS after gate's log has no `warning(131)`; the before log has one, `JSL.g4:488:19`. No jslc build of the change has a `warning(` line ([Final gates](#final-gates-2026-11-14-macos)) | Met |
| Generated files unchanged | Outside Maven with `-d3d -es2 -mtl`, all 1,059 files identical by name and md5; the three blur drivers' 141 files (137 distinct paths) identical by md5 ([Generator runs](#generator-runs)); the macOS gates' `target/gensrc` (797 files) identical | Met |
| Build gate | macOS, the WSL command with `-Dmaven.test.failure.ignore=true`, before and after ([Final gates](#final-gates-2026-11-14-macos)), extra evidence. jslc: 196 tests in the criterion (159 when the story was filed; US-018 added 37), 235 at the baseline (US-019 added 39), 276 after, the 235 and the 41 new ones, no failure. Windows (with `-am`) and WSL: [owner-run](#owner-run-and-pending) | Passed on macOS (extra evidence); Windows and WSL owner-run |
| Upstream | [Upstream check](#upstream-check-2026-11-14); the draft issue, kept outside the repository | Checked and drafted; filing pending |

The Definition of Done's other items: the options and reasons are in [Choice](#choice-options-a-and-d), and the
independent review repeated the mutant proofs with its own inputs
([Independent review](#independent-review-2026-11-14)).

### Tests
- **jslc.** `mvn -B -ntp -pl buildtools/jslc package` on the final tree runs 276 tests in 31 classes, with 0
  failures, 0 errors and 0 skipped: in the main tree after the second rework, in that rework's mutant clone and in the
  confirming review's clone. The macOS after gate gives the same, and the baseline runs 235 in 28. Per class the two
  are equal except the three new classes: `PrismProgramTest` 13, `ProgramGlueBlockTest` 20 and
  `lexer.GlueBlockTest` 8. No build log of the change has a `warning(` line.
- **`ProgramGlueBlockTest`,** 20 tests through `JSLC.getParserInfo(String)`. The positive tests assert the
  declarations in order, each block with its text, and that the parse reached the end of input (`declarationsOf`):
  - the first criterion: `aParamBetweenTwoGlueBlocksIsADeclarationOfItsOwn`, `aFunctionBetweenTwoGlueBlocksIsKept`
    and `aFunctionBetweenTwoGlueBlocksWithWindowsLineEndsIsKept`, `adjacentGlueBlocksAreTwoBlocks` and
    `adjacentGlueBlocksOnOneLineAreTwoBlocks`, and a `>>` in a `//` comment after the block, in a `/* */` comment
    after it and in a `//` comment inside `main` (`aShiftInALineCommentAfterTheGlueBlockIsPartOfTheComment`,
    `aShiftInABlockCommentAfterTheGlueBlockIsPartOfTheComment`, `aShiftInALineCommentInsideMainIsPartOfTheComment`);
  - what does not change: a block on one line, `1 << 2` in the Java text, CRLF line ends kept in the block's text and
    an unclosed `/*` in the Java text;
  - the edge of (a), each asserting a prefix: `aNestedGenericTypeEndsTheBlockAtItsClosingBrackets` (`line 2:47
    extraneous input 'l' expecting {<EOF>, `), `aShiftInTheJavaTextEndsTheBlock` (`line 2:35 … '1'`),
    `aShiftInAJavaCommentEndsTheBlock` (`line 2:15 … '1'`) and `theCloserAfterAnEarlyEndIsAStrayCloser`
    (`line 4:0 … '>>'`);
  - the limit of (a): `aCloserAndAnOpenerInAJavaStringSplitTheBlockInTwo` and
    `aCommentOpenerAfterAnEarlyEndHidesTheCodeUpToTheNextCommentEnd` assert the declarations of
    [Behaviour changes](#behaviour-changes);
  - an unclosed `<<` at the start of the program (`line 1:0 extraneous input '<<' expecting {`) and after its last
    declaration (`line 3:0 extraneous input '<<' expecting {<EOF>, `).
- **`lexer.GlueBlockTest`,** 8 tests of the token: a block on one line, on several lines, and empty; it ends at the
  first closer (`<< a >> b >>` gives `<< a >>`); a `<<` inside does not nest (`<< a << b >> c >>` gives
  `<< a << b >>`); `/*` and `//` inside are part of it; an unclosed `<<` and plain text are not one.
- **`PrismProgramTest`,** 13 tests through `JSLC.compile(JSLCInfo, String, long)`, with `OUT_OF_DATE` and the
  directories of `probeRun` in a JUnit `@TempDir`, or through `JSLC.getParserInfo(JSLCInfo, String)`:
  - `compilingAPrismProgramWithAGlueBlockIsAnErrorThatWritesNoFile`, a `@ParameterizedTest` over `OUT_D3D`, `OUT_ES2`,
    `OUT_MTL`, `OUT_JAVA` and `OUT_PRISM`, reported as `[1]` to `[5]` in that order. Each case asks for one output
    type, so the program's only parse is at that type's site (`:213`, `:223`, `:237`, `:255`, `:265`). It asserts
    `assertThrowsExactly(RuntimeException.class, …)`, the whole message `A Prism program cannot have a glue block:
    Probe` and that no regular file exists under the temporary directory;
  - `compilingAPrismProgramWithAGlueBlockAfterMainIsAnError`, with the three hardware shaders, the same assertions;
  - `parsingAPrismProgramWithAGlueBlockIsAnError`, the same message from `getParserInfo(JSLCInfo, String)`;
  - the positive controls: a Prism program without a glue block writes `Probe.frag`, `Probe.hlsl` and `Probe.metal`,
    and parses to the valid program's declarations; a Decora program with a glue block writes its three shaders for a
    shader-only run, parses to the block and the two declarations, writes the five files for every output type with
    the block's Java text in both peers, and with two blocks puts both in both peers, in order.
- **What is pinned.** The edge tests and the unclosed-`<<` tests pin a prefix: the rest of ANTLR's expected-token list
  changes with the token vocabulary (US-019's code-review finding 4). The Prism message is pinned whole, and the class
  exactly, so a backend failure or another exception cannot pass.
- **Mutant proofs,** each in a scratch clone of the baseline with the change's files, run with
  `mvn -B -ntp -pl buildtools/jslc package` (the whole jslc suite). The unmutated tree passed every test before each
  set, and after the implementation's: 270 / 0 / 0 / 0 for the implementation's, which ran before the two tests of
  the limit and the per-type test existed, 272 for the first rework's and 276 for the second rework's.

  | Mutant | Result |
  | --- | --- |
  | G0, the baseline's rule `.*` (implementation) | 10 failures, 5 errors. `aParamBetweenTwoGlueBlocksIsADeclarationOfItsOwn` errors with `Unknown variable x`; the two function-between-blocks tests and the two adjacent-block tests fail; the three comment tests error with `line 5:8`, `5:8` and `6:12 extraneous input '1'`; the 4 edge tests fail with `nothing was thrown`; `GlueBlockTest.glueBlockEndsAtTheFirstCloser` and `openerInsideTheTextDoesNotNest` fail; `compilingADecoraProgramWithTwoGlueBlocksPutsBothInBothPeersInOrder` errors with `Cannot read Probe_Uniforms from the Metal shader` |
  | Gb, the rule of (b), `LEFT_FRENCH .*? '\n' [ \t]* RIGHT_FRENCH` (implementation) | 9 failures, 2 errors: the 4 edge tests (`nothing was thrown`); `aGlueBlockOnOneLineIsABlock` and `adjacentGlueBlocksOnOneLineAreTwoBlocks` error with `line 1:0 extraneous input '<<'`; 5 lexer tests (one line, empty, first closer, no nesting, comment openers) |
  | N1, a valid program in the 6 negative tests of `ProgramGlueBlockTest` | 6 failures, `nothing was thrown` |
  | N2, an unclosed `<<` in the 4 edge tests | 4 failures, each `but was <line 1:0 extraneous input '<<' …>` |
  | C1, the check removed | 3 failures, the three Prism negative tests of the 270-test tree, `nothing was thrown` |
  | C2, a valid program, then the `undefinedFunc` program, in the compile test | 1 failure each: `nothing was thrown`, then `expected: <A Prism program cannot have a glue block: Probe> but was: <Unknown function undefinedFunc(float)>` |
  | C3, glue rejected whenever no peer is requested, in place of `prismProgram` | 2 errors: `compilingADecoraProgramWithAGlueBlockForShadersOnlyWritesItsShaders` and `parsingADecoraProgramWithAGlueBlockGivesItsDeclarations` |
  | C4, the five compile sites unchecked | 2 failures, the two Prism compile tests; the parse test calls the checked parse directly |
  | C5, a D3D write before the parse | 3 failures, the two Prism compile tests and `TranslationUnitTest.compilingAProgramWithAStrayBraceWritesNoFile`, each `expected: <[]> but was: <[Probe.hlsl]>` |
  | The greedy rule (first rework, 272 tests) | 12 failures, 5 errors: G0's 15 and the 2 tests of the limit. The string test gets one block, `String s = ">><<";`; the comment test gets 4 declarations, `param float y` among them |
  | The rule of (b) (first rework) | 11 failures, 2 errors: Gb's 11 and the 2 tests of the limit, with the same results as under the greedy rule |
  | One site at a time back to `getParserInfo(stream)` (second rework, 276 tests) | D3D: 2 failures, `[1]` and `compilingAPrismProgramWithAGlueBlockAfterMainIsAnError`. ES2, MTL, JAVA, PRISM: 1 failure each, `[2]`, `[3]`, `[4]`, `[5]`. All `nothing was thrown` |
  | The check removed (`:117-119` deleted, second rework) | 7 failures: `[1]` to `[5]`, the after-`main` test and the parse test |
  | `VALID_PROGRAM` in place of the parameterized test's program (second rework) | 5 failures, `[1]` to `[5]`, `nothing was thrown` |
  | `VALID_PROGRAM` and `void other() { undefinedFunc(1.0); }` there (second rework) | 5 failures, `[1]` to `[5]`, each `expected: <A Prism program cannot have a glue block: Probe> but was: <Unknown function undefinedFunc(float)>` |

  The reviews' own mutants are in [Independent review](#independent-review-2026-11-14) and
  [Reworks and confirming review](#reworks-and-confirming-review-2026-11-14).

### Generator runs
Run on 2026-11-14 on the tree before both reworks. Those changed only tests and the javadoc of `prismProgram`: the four
classes compiled from the final `JSLC.java` (`JSLC`, `JSLC$JSLCInfo`, `JSLC$OutInfo`, `JSLC$ParserInfo`) are
`cmp`-identical to those the runs used, and `JSL.g4` and the Prism `CompileJSL.java` are the final ones (second
rework, confirming review).
- **Setup.** Two sides. Before: the macOS before gate's clone of `9186c05be8`, built by its `clean test` (graphics and
  base `target/classes`, `target/jsl-compilers/{decora,prism}`, `src/jslc/resources`), and the jslc jar of
  `mvn -B -ntp -pl buildtools/jslc package` in another clone of the baseline (235 tests, no failure). After: a clone
  of the baseline with the eight files, built with `mvn -B -ntp -pl modules/javafx.graphics -am test-compile
  -DskipNative=true` and `mvn -B -ntp -pl buildtools/jslc package` (270 tests then, no failure). Between the two
  jars only `JSLC.class`, `JSLC$JSLCInfo`, `JSLC$OutInfo`, `JSLC$ParserInfo` and `JSLLexer.class` differ; between the
  generators only the Prism `CompileJSL.class` (and `PrismLoaderBackend.class`, in its bytes only: its `javap -c -p`
  output is the same). The final Prism `CompileJSL.java`, compiled as the pom's `javac` does, gives classes
  byte-identical to the after side's.
- **Run as the pom runs them,** outside Maven. Classpath: graphics `target/classes`, base `target/classes`, the side's
  jslc jar, `org.antlr:antlr4:4.7.2` and its 6 dependencies from `~/.m2`, `src/jslc/resources` and the generator
  classes, plus the Prism input directory for the Prism generator. The Decora JVM also gets the pom's
  `--module-path`, `--add-modules=javafx.graphics` and three `--add-exports`. The inputs are copies of the baseline's
  `src/main/jsl-decora` and `src/main/jsl-prism`, whose `.jsl` files are the final tree's. The Prism generator runs
  once per `.jsl` file, 30 JVMs, in the order of Ant's `DirectoryScanner` (byte order, `MaskAlphaOne.jsl` first), and
  a case stops at the first JVM that exits non-zero, as `failonerror` does. Every JVM ran in an empty working
  directory of its own, which stayed empty.
- **Setup check.** The before side's regeneration below, without its 262 `.hlsl` files (50 Decora, 212 Prism), is
  `cmp`-identical as an md5 list (797 files) to the before gate's `target/gensrc`.
- **Regeneration with `-d3d -es2 -mtl`.** `GenAllDecoraShaders` in one JVM (210 files: 208 and 2 headers) and the 30
  Prism JVMs, into an empty output root per side. All 31 JVMs exit 0 on both sides. Each side writes 1,059 files
  (`jsl-decora` 208, `jsl-prism` 848, `mtl-headers` 3), identical by name and md5.
- **The three drivers the build does not call.** `CompileBoxBlur`, `CompileGaussian GaussianBlur` and
  `CompileZoomRadialBlur`, with `-d3d -es2 -mtl -java -hw` and an output root each, exit 0 on both sides and write
  43, 43 and 55 files, the 141 identical by md5. The story's 137 counts paths: all three write
  `mtl-headers/DecoraShaderCommon.h` and `mtl-headers/FragmentShaderCommon.h`, so in one shared output root the 141
  files are 137 distinct paths. The whole glue text of each of the three `.jsl` files is one run of lines in both of
  its peers, at the same place before and after.
- **End to end, Decora:** `Brightpass.jsl`, the Decora `CompileJSL` with `-d3d -es2 -mtl -java -hw`. The peers were
  compiled with `javac -d <dir> --release 27 -encoding UTF-8 -implicit:none -Xmaxerrs 1000 --module-path <graphics
  classes>:<base classes> --patch-module javafx.graphics=<output>/jsl-decora <peer>`, one `javac` per peer, against
  the side's classes.

  | Case | Before (`9186c05be8`) | After |
  | --- | --- | --- |
  | D0, unmodified | Exit 0, 7 files: `.hlsl`, `.frag`, `.metal`, both peers, `DecoraShaderCommon.h` and `FragmentShaderCommon.h`. Both peers compile | The same 7 files, identical by name and md5. Both peers compile |
  | D1, the function `halve` and a second glue block holding `getHalfThreshold()` after line 31; `main` does not call `halve` | Exit 0, 7 files. The shaders and headers are md5-identical to D0's: `halve` is in none of them. Each peer holds the JSL text between a `>>` line and a `<<` line, and `javac` fails on each with 2 errors: `PPSBrightpassPeer.java:59: error: illegal start of type` and `:66`, `JSWBrightpassPeer.java:61` and `:68` | Exit 0, 7 files. `halve` is in the `.hlsl` (lines 1-3), the `.frag` (16-18) and the `.metal` (`Brightpass_halve`, 12-15); the headers are D0's. Both peers hold `getThreshold()` and `getHalfThreshold()`, neither holds `halve`, and both compile |
  | D2, D1 with `luminance = halve(luminance);` in `main` | Exit 1, `java.lang.RuntimeException: Unknown function halve(float)`, no file | Exit 0, 7 files. Each shader defines and calls `halve`, the JSW peer has the call in Java, and both peers compile |
  | D3, the second block between the two `param`s (after line 32) | Exit 1, `java.lang.RuntimeException: Unknown variable baseImg`, no file | Exit 0, 7 files: the shaders and headers are md5-identical to D0's and the peers to D1's. Both compile |
  | D4, `    // luminance >> 1 would halve it` as line 40 | Exit 1, `org.antlr.v4.runtime.misc.ParseCancellationException: line 40:20 extraneous input '1' expecting {<EOF>, 'const', 'param', 'lowp', 'mediump', 'highp', 'void', TYPE, GLUE_BLOCK}`, no file | Exit 0, the 7 files of D0, byte-identical. Both peers compile |
  | D5, `CompileJSL -d3d -es2 -mtl Brightpass`, unmodified | Exit 0, 5 files: the three shaders and the two headers | The same 5 files, md5-identical to those of the same name in D0 |

  D1 before gives the `javac` lines the story measured on 2026-09-27, and D4 before the result the story expected
  with US-019.
- **End to end, Prism,** with `-d3d -es2 -mtl` and the 30 JVMs:

  | Case | Before (`9186c05be8`) | After |
  | --- | --- | --- |
  | P0, unmodified inputs | The 30 JVMs exit 0 and print nothing. 850 files (848 `jsl-prism`, 2 `mtl-headers`) | The same, identical by name and md5 |
  | P1, the five-line block of `Brightpass.jsl:26-30` after line 25 of `PaintImagePattern.jsl` | 30 exit 0, nothing printed, 850 files identical to P0's | JVM 1 (`MaskAlphaOne.jsl`) exits 1, output below; JVMs 2 to 30 do not run. It wrote the 4 files of `Solid_Color` (`.hlsl`, `.frag`, `.metal`, `_Loader.java`), compiled before, each md5-identical to P0's; no `Solid_ImagePattern` file and no Metal header |
  | P2, the same block after line 25 of `MaskFillPgram.jsl` | 30 exit 0, 850 files identical to P0's | JVM 1 exits 1 with `java.lang.RuntimeException: A Prism program cannot have a glue block: FillPgram_Color`, thrown in `compileColorPaint` (`CompileJSL.java:249`, from `main`, `:484`). It wrote the 64 files of the 16 `Solid_*` and `Texture_*` programs compiled before, each md5-identical to P0's; no `FillPgram` file and no header |
  | P3, `<< this is not Java at all >>` after line 25 of `PaintImagePattern.jsl` | 30 exit 0, 850 files identical to P0's | The log and the file list of P1, byte-identical |
  | P4, `    float v = undefinedFunc(1.0);` after line 34 of `PaintImagePattern.jsl` | JVM 1 exits 1 with `java.lang.RuntimeException: Unknown function undefinedFunc(float)`, through `compilePatternPaint` (`Solid_ImagePattern`); the 4 `Solid_Color` files, no header | The same message, with no mention of glue; the same 4 files, no header |
  | P5, the fallback parse: P0's output with `Solid_ImagePattern_Loader.java` deleted, P1's block in `PaintImagePattern.jsl` with the file's old time (older than the outputs), and JVM 1 run again | Exit 0, nothing printed. It writes `Solid_ImagePattern_Loader.java`, the only file newer than a marker, and the 850 files are P0's | Exit 1 with the `Solid_ImagePattern` message, from `JSLC.getParserInfo(JSLC.java:118)`, `:112` and `CompileJSL.compileShader(CompileJSL.java:427)`, with no `JSLC.compile` frame: the fallback parse. No file is newer than the marker; the loader is not written |
  | P6, P1's block after line 25 of `MaskSolid.jsl`, of `MaskAlphaOne.jsl` or of `PaintColor.jsl` | 30 exit 0, 850 files identical to P0's | The same, in all three |

  The whole output of P1 after the change (each `at` line starts with a tab in the log):
  ```
  Exception in thread "main" java.lang.RuntimeException: A Prism program cannot have a glue block: Solid_ImagePattern
      at com.sun.scenario.effect.compiler.JSLC.getParserInfo(JSLC.java:118)
      at com.sun.scenario.effect.compiler.JSLC.compile(JSLC.java:213)
      at com.sun.scenario.effect.compiler.JSLC.compile(JSLC.java:193)
      at com.sun.scenario.effect.compiler.JSLC.compile(JSLC.java:153)
      at CompileJSL.compileShader(CompileJSL.java:423)
      at CompileJSL.compileShader(CompileJSL.java:415)
      at CompileJSL.compilePatternPaint(CompileJSL.java:296)
      at CompileJSL.main(CompileJSL.java:485)
  ```
  `JSLC.java:213` is the D3D site, the first lazy parse, so no output of `Solid_ImagePattern` is written.
  - **P5 from the code.** `compileShader` (`src/main/jsl-prism/CompileJSL.java:418-431`) calls `JSLC.compile`, which
    returns no parse when every shader is up to date. For the `.metal` output it still calls
    `MetalHeaders.addUnlessRenamed`, which reads the file and parses only if a shader looks renamed, which
    `Solid_ImagePattern`'s does not. The out-of-date loader then goes through the fallback parse at `:427`.
  - **P6 from the code.** `getFullSource` leaves out the mask text for `Solid` and `AlphaOne` (`:362-371`, "can omit
    trivial mask() call") and the paint text for `Color` (`:375-378`, "can omit trivial paint() call"). The
    generator still reads each file and its time, but the text goes into none of its programs: `MaskSolid.jsl` is
    the mask of 28 programs, `MaskAlphaOne.jsl` that of 8 and `PaintColor.jsl` the paint of 28. A glue block there
    is never parsed, before or after the change
    ([US-113](US-113-make-an-error-in-any-prism-generator-input-fail-the-build.md)).
  - **`-name`.** JVM 1 alone writes all 850 files of P0, on both sides, and JVM 2 then writes none: the generator
    composes all 212 programs in every JVM.
- **Mutants, end to end,** each on the after side with one change:

  | Mutant | Built as | Result |
  | --- | --- | --- |
  | M1, the `throw` removed from `JSLC.getParserInfo(JSLCInfo, InputStream)` | A clone with the eight files, `mvn -B -ntp -pl buildtools/jslc package -DskipTests`; only `JSLC.class` differs in code from the change's jar | P1 and P2: 30 exit 0, 850 files identical to P0's |
  | M2, `jslcinfo.prismProgram = true;` removed from the Prism `main` | The Prism generator compiled as the pom does, run with the change's jar | P1 and P2: 30 exit 0, 850 files identical to P0's |
  | M3, `(jslcinfo.outTypes & OUT_ALL_PEERS) == 0` in place of `jslcinfo.prismProgram`: glue rejected whenever no peer is written | As M1 | D5: exit 1, `java.lang.RuntimeException: A Prism program cannot have a glue block: Brightpass`, no file, so D5 fails. D0 with `-java -hw`: exit 0, its 7 files. P1: as the change |
  | F, `JSLC.getParserInfo(source)` in place of `JSLC.getParserInfo(jslcinfo, source)` at `CompileJSL.java:427` | As M2 | P5: exit 0, nothing printed, and `Solid_ImagePattern_Loader.java` is written again, md5-identical to P0's |

  After the mutants, the unmutated after side gave P1's log and file list again, byte-identical.

### Final gates (2026-11-14, macOS)
Both runs were on the host above, in fresh clones: before at `9186c05be8` (started 20:21 UTC), after at the baseline
plus the change's eight files (21:24 UTC), each `cmp`-identical to the main tree's (`git status --short` in that clone
lists only them). The after run started after the second rework and the confirming review, so it ran the final tree.
The eight files' md5 (`md5sum`):

| File | md5 |
| --- | --- |
| `src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4` | `4e6fc66b521ecba66e246e3513ee2fd0` |
| `src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java` | `40e28bce146b5475d5df18c8ff0703a5` |
| `src/main/jsl-prism/CompileJSL.java` | `5e30594600974f06154f3bdb32353532` |
| `src/test/jslc/com/sun/scenario/effect/compiler/ProgramBase.java` | `f4b308507bd64ea65cbf3acc890833a8` |
| `src/test/jslc/com/sun/scenario/effect/compiler/TranslationUnitTest.java` | `0bd817179046d918376682fb20d9d283` |
| `src/test/jslc/com/sun/scenario/effect/compiler/PrismProgramTest.java` | `abf04b94bed72f4ed30684ad29305d39` |
| `src/test/jslc/com/sun/scenario/effect/compiler/ProgramGlueBlockTest.java` | `8b8c7107cfe6b7c8cea7fa51634a7260` |
| `src/test/jslc/com/sun/scenario/effect/compiler/lexer/GlueBlockTest.java` | `6e3fc6c59b6a6b1c6ed5660e15629836` |

They are extra evidence: the criterion's gates are Windows and WSL. Counts are run / failures / errors / skipped.

| Gate | Command | Result |
| --- | --- | --- |
| Module gate | `mvn -B -ntp -l <log> -Dmaven.test.failure.ignore=true -pl buildtools/jslc,modules/javafx.graphics -am clean test`, before and after | Both exit 0 with BUILD SUCCESS (72 s and 71 s), and `jsl-codegen`, `cmake-configure` and `cmake-build` ran. jslc 235 / 0 / 0 / 0 in 28 classes before and 276 / 0 / 0 / 0 in 31 after; base 5,510 / 0 / 0 / 22 in 290 classes and graphics 26,696 / 0 / 0 / 858 in 629 in both. Of the 947 per-class lines before and 950 after, `diff` shows only `PrismProgramTest 13 0 0 0`, `ProgramGlueBlockTest 20 0 0 0` and `lexer.GlueBlockTest 8 0 0 0` added ([per class](US-020-macos-per-class.tsv)). No class has a failure or an error. No `hs_err_pid*` file in either clone |
| Generated files | The module gate's `target/gensrc` | 797 files (`jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3), identical by name and md5 before and after ([md5 list](US-020-macos-gensrc.md5), the after run's) and to `US-019-macos-gensrc.md5` |
| ANTLR warning | `grep -c 'warning(131)'` on the module gate's logs | 1 before, `[WARNING] warning(131): com/sun/scenario/effect/compiler/JSL.g4:488:19: greedy block ()* contains wildcard; the non-greedy syntax ()*? may be preferred`, and 0 after. The plugin's echo of it (`JSL.g4 [488:19]: greedy block …`) is gone too; the other `[WARNING]` lines that are not test totals are the same in both logs, except for the clone path in one `javac` note |

The check commands of [Windows gate](#windows-gate), run in bash on this host with `windows` replaced by `macos`, on
the module gate's clones and logs, print the per-class difference, totals, `warning(131)` counts and `gensrc` result
above, and their per-class and md5 lists are `cmp`-identical to the gate's. `merge` of
[After the gates](#after-the-gates) gives the body of `US-020-macos-per-class.tsv`. Both clones hold the same 27 empty
`hs_err` directories, which child-JVM tests create. The counts of `target/gensrc` differ from the US-008 gate's for
the reasons US-019's [Final gates](US-019-reject-trailing-input-in-the-jsl-compiler.md#final-gates-2026-11-14-macos)
gives.

### Independent review (2026-11-14)
Of the change after the first rework (272 tests), in its own clone, where the unmutated build ran 272 / 0 / 0 / 0 in
31 classes with no `warning(` line.
- **Verdict:** one must-fix. "Everything else in the acceptance criteria I reviewed holds, and option (a) is still the
  better choice, narrowly."
- **Findings:**

  | # | Finding | Disposition |
  | --- | --- | --- |
  | 1, must-fix | The jslc tests reached the Prism check only through the D3D site (`:213`): both compile tests used `OUT_HW_SHADERS`. With the ES2 (`:223`), MTL (`:237`), JAVA (`:255`) or PRISM (`:265`) site reverted to `getParserInfo(stream)`, all 272 tests passed. ES2 and MTL are where a Prism program is first parsed in the Linux build (`-es2`, `pom.xml:48`) and the macOS builds (`-mtl -es2` or `-mtl`). End to end with its own block in `PaintImagePattern.jsl` and one generator JVM: the ES2-site mutant exits 0 with 424 files (`-es2`) and 638 (`-mtl -es2`), the MTL-site mutant with 426 (`-mtl`); the change exits 1 with the `Solid_ImagePattern` message under each | Fixed by the second rework: the compile test runs once per output type |
  | 2 | With `prismProgram` set and `shaderName` unset, the message ends in `null` | Not changed: no production path sets `prismProgram` without `shaderName`. The Prism generator sets the name before both parses (`CompileJSL.java:422`), and `JSLC.main` never sets the flag |
  | 3 | `TranslationUnitTest` and `PrismProgramTest` duplicated `EVERY_OUTPUT_TYPE` (spelled differently, equal in value), `OUT_OF_DATE`, the `JSLCInfo` factory and the five-file list | Fixed: moved to `ProgramBase` ([Change](#change)) |
  | 4 | The grammar comment offers `> >` for generics only; a shift has no such spelling, and breaking the rule is not always an error | Not changed: every statement in the comment is true, and the limit is recorded in [Choice](#choice-options-a-and-d) and pinned by two tests |
  | 5 | The `prismProgram` javadoc could be misread: "its outputs" came right after "not for a Decora program …" | Fixed: the review's wording ([Change](#change)) |
  | 6 | For information: the two edits in `src/main/jsl-prism` are outside the jslc suite. Measured: with `getParserInfo(source)` at `:427`, the fallback exits 0 and writes the loader; without the flag, the glue copy exits 0 with 850 files | Recorded as a limit of (d) ([Choice](#choice-options-a-and-d)); M2 and F guard them |

- **Its mutants,** on 272 tests: the greedy rule, 12 failures and 5 errors (17 tests, among them all of the first
  criterion), with `warning(131)` back at `JSL.g4:490:19`; the rule of (b), 11 failures and 2 errors (13 tests, among
  them the 4 edge tests); the check removed, 3; all five sites unchecked, 2; the D3D site only, 2; each of the four
  other sites, 0 (finding 1); the check after the first D3D write, 2 (`expected: <[]> but was: <[Probe.hlsl]>`); the
  check for Decora too, 4 errors (the 4 Decora tests); the 9 negative tests with a valid program, 9 failures; and
  with another failure (an unclosed `<<`, a stray `}`, `undefinedFunc(1.0)`), 9 failures, the Prism ones with
  `but was: <Unknown function undefinedFunc(float)>`. A five-case test like the one the second rework added passed on
  the change (277 tests) and failed on each single-site mutant (1 failure each, 3 for D3D).
- **Its own probes:** the edge inputs of [Behaviour changes](#behaviour-changes), parsed with the baseline's jar, the
  change's and an option (b) jar; the token dump of the 62 shipped files; the Prism regeneration with
  `-d3d -es2 -mtl` (850 files, identical by name and md5); the Decora shader-only run (5 files, md5-identical); and
  the block in `MaskFillPgram.jsl` (exit 1, `FillPgram_Color`).
- **Its option verdict:** (a)'s cost is that glue Java cannot contain `>>`, and each realistic `>>` it tried is an
  error at the line of the `>>`; (b)'s cost is a layout rule that rejects layouts every rule since 2012 accepted.
  "Prefer (b) only if you expect glue authors to need shifts or nested generics; no shipped glue block has needed
  them in 13 years."
- **Stale text** it listed: the `backlog/README.md` row and this story's status (both updated here); the jslc test
  counts in US-021, US-022 and US-023 and US-022's note on US-020's option (b) (updated); and the glue-block text of
  US-008 and US-019, which records what those stories found then and is left as written.
- **Not checked by it:** the Windows and WSL gates, the full Decora regeneration and the blur drivers, the Brightpass
  cases with `javac`, the pom's 30-JVM loop itself, the JBS search and the upstream draft.

### Reworks and confirming review (2026-11-14)
- **First rework, before the review.** The implementation's probes found that under (a) an early `>>` followed by a
  `<<`, or by a `/*` that a later JSL `*/` closes, parses without an error ([Choice](#choice-options-a-and-d)). The
  rework measured the declarations of the two cases with the change's jar and added the two tests that pin them. The
  main tree then ran 272 / 0 / 0 / 0 in 31 classes, with no `warning(131)`. Both tests fail under the greedy rule and
  under (b)'s, each in its own clone ([Tests](#tests)).
- **Second rework, after the review.** Three changes: the compile test parameterized over the five output types
  (finding 1), the fixture moved to `ProgramBase` (finding 3) and the javadoc reworded (finding 5). It kept the
  five-type spelling of `EVERY_OUTPUT_TYPE`, equal in value to `OUT_HW_SHADERS | OUT_ALL_PEERS` (31), because it
  names the types whose files `PROBE_FILES_FOR_EVERY_OUTPUT_TYPE` lists, and because `OUT_ALL_PEERS` means peers
  only. The main tree ran 276 / 0 / 0 / 0 in 31 classes, with no `warning(131)` and no `[WARN` line; the four `JSLC`
  classes are `cmp`-identical to the implementation's. Its mutants are in [Tests](#tests).
- **Confirming review: pass.** "The rework is exactly the three requested items, every mutant I ran is killed, and
  nothing breaks a stated rule."
  - Its clone ran 276 / 0 / 0 / 0 in 31 classes before its mutants and after restoring `JSLC.java`.
  - Its mutants, one per build: each single site reverted kills exactly its own case (D3D also the after-`main`
    test), all five sites reverted give 6 failures, the `throw` at `:118` removed gives 7 (the five cases, the
    after-`main` test and the parse test), and `jslcinfo.prismProgram && ` dropped from `:117` gives 4 errors, the
    four Decora tests, each with `A Prism program cannot have a glue block: Probe`.
  - It traced why each case's only parse is at its own site: the five output bits are distinct, `OUT_OF_DATE` makes
    every output out of date, each case gets a fresh `@TempDir`, and the MTL branch writes nothing before `:237`. The
    message is built only at `:118`.
  - It checked each statement of the javadoc against the code: the only production assignment is
    `CompileJSL.java:475`; the build passes the Prism generator only `-es2`, `-d3d`, `-mtl` or two of them; the HLSL,
    ES2 and Metal backends ignore glue, and the `_Loader` template has no slot for it; `GenAllDecoraShaders` and
    `JSLC.main` never set the field, and the Prism peers of Decora programs do carry the glue text.
  - It found `TranslationUnitTest`'s 7 tests and 9 assertions the same as at `9186c05be8`, the last now comparing
    with `PROBE_FILES_FOR_EVERY_OUTPUT_TYPE`, whose value is the same list.
  - Its observations, none needing a change: a failing case is named by its index, `[1]` to `[5]`, not by its output
    type; `MetalHeadersTest` and `SymbolTest`, which do not extend `ProgramBase`, keep their own `Long.MAX_VALUE`;
    the pinned message's `Probe` ties it to `probeRun`; and `CompileJSL` would still accept `-java` or `-all`, under
    which the check still makes any glue block an error.

### Upstream check (2026-11-14)
- **JBS,** project JDK. The five searches of
  [A regression of the ANTLR 4 upgrade, upstream too](#a-regression-of-the-antlr-4-upgrade-upstream-too) give the
  same counts as on 2026-09-27: `glue` and `JSL` 0, `glue block` 0, `GLUE_BLOCK` 9 (none in javafx or about JSL),
  `greedy` 6 (none about JSL), and `glue`, `prism` and `jsl` 0. No issue reports this. JDK-8226635 is still Open;
  JDK-8218170 is Resolved (Fixed, jfx13); JDK-8218764 is Closed as its duplicate.
- **openjdk/jfx master** at `d0e72f9d86` (2026-11-13):
  - `GLUE_BLOCK` is still `LEFT_FRENCH .* RIGHT_FRENCH` (`JSL.g4:481-483` there), and `translation_unit` has no `EOF`;
  - its Prism `CompileJSL.java` still drops a glue block: `PrismLoaderBackend` has no `visitGlueBlock`, and the
    fallback parse is `JSLC.getParserInfo(source)` (`:426`);
  - its `JSLC.compile` has seven lazy-parse sites, the fork's five and those of the SSE and ME peers the fork removed
    (`:212`, `:222`, `:232`, `:245`, `:261`, `:287`, `:307`), and its SSE peer pastes glue text too;
  - `JSLVisitor`, `TreeScanner`, `PrismBackend`, `JSWBackend`, `JSWTreeScanner`, `SLBackend` and every template have
    the baseline's blobs, and 61 of the 62 `.jsl` files are identical (`ColorAdjust.jsl` has the fork's
    divide-by-zero fix, without `<<` or `>>`);
  - the ANTLR 4.7.2 tool prints `warning(131): JSL.g4:482:19: greedy block ()* contains wildcard …` on its grammar,
    and nothing with `.*?`.
- **Master's parse path with (a) alone.** A compiler with master's parse path (the fork's `src/jslc` at
  `d803860b7e`) and only `.*?` gives the fixed results for the two-block and comment cases, but after an early `>>`
  it drops the rest of the program without an error (`List<List<String>>` in a block: 1 declaration, the parser
  stops at 2:47). With US-019 as well, the fork reports `line 2:47 extraneous input 'l' …`.
- **Open pull requests.** #2346 (8394770, Direct3D 12 pipeline) changes `JSLC.java` and the Prism `CompileJSL.java`
  and adds a D3D12 lazy-parse site, which (d) would have to cover too. #2284 (8090503) adds one line to the Prism
  `CompileJSL.java` and a `.jsl` file without `<<` or `>>`. No open pull request changes `JSL.g4`, and a search for
  `glue` finds none.
- **Draft.** The upstream issue is drafted with the two-block and comment repros, the ANTLR 3 comparison, the Prism
  glue repro, the chosen options (a) and (d) with their limits, and the tests and what they pin. It says that
  upstream runs no jslc test (JDK-8226635), so the fix's tests need that port too, and that they build on the fork's
  `ProgramBase` and `JSLCInfo.metalHeaderDir`. It refers to US-019's upstream issue, the end-of-input fix, which (a)
  needs. The draft is kept outside the repository until it is filed (`backlog/README.md`).

### Owner-run and pending
Replace `<repo-url>` with the repository's remote and `<commit>` with the owner's US-020 commit. Before is
`9186c05be8`. If `<commit>` is only on the PR branch, fetch it first with
`git -C jfx-after fetch -q origin pull/<PR>/head`. In PowerShell, quote every `-D` argument; under Git Bash, Maven
3.10's `bin/mvn` converts the paths. Each gate keeps its logs, its per-class lists (class, run, failures, errors,
skipped, separated by tabs) and its `target/gensrc` md5 lists as `us020-<os>-<before|after>…` in the directory that
holds the clones. The check commands ran as written in bash on the macOS host, with `windows` replaced by `macos`, on
the macOS gates' clones and logs, and printed the per-class differences, jslc totals and `warning(131)` counts
expected below, with macOS's skip counts and `gensrc` directories.

#### Windows gate
Two sibling checkouts, in Git Bash:
```bash
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach 9186c05be8
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <commit>
```
Maven from PowerShell, in the directory that holds both, so that MSVC builds the natives:
```powershell
$pl = "buildtools/jslc,modules/javafx.graphics"
cd jfx-before
mvn -B -ntp -l ..\us020-windows-before.log -pl $pl -am clean test "-Djfx.parity.require=true"
"before exit $LASTEXITCODE"; cd ..
cd jfx-after
mvn -B -ntp -l ..\us020-windows-after.log -pl $pl -am clean test "-Djfx.parity.require=true"
"after exit $LASTEXITCODE"; cd ..
```
The checks, in Git Bash in the same directory:
```bash
classes() {
  tr -d '\r' < "$1" | awk '/Tests run: .* -- in / { c = $NF; sub(/.*Tests run: /, ""); split($0, n, /[^0-9]+/)
    print c "\t" n[1] "\t" n[2] "\t" n[3] "\t" n[4] }' | LC_ALL=C sort
}
for t in before after; do
  classes us020-windows-$t.log > us020-windows-$t-per-class.tsv
  (cd jfx-$t/modules/javafx.graphics/target/gensrc && find . -type f | LC_ALL=C sort | xargs md5sum) \
    > us020-windows-$t-gensrc.md5
done
diff us020-windows-before-per-class.tsv us020-windows-after-per-class.tsv
for t in before after; do
  echo "$t:"
  tr -d '\r' < us020-windows-$t.log \
    | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|BUILD (SUCCESS|FAILURE)'
  echo "warning(131): $(grep -c 'warning(131)' us020-windows-$t.log)"
done
cmp us020-windows-before-gensrc.md5 us020-windows-after-gensrc.md5 && echo "gensrc identical by name and md5"
cut -d/ -f2 us020-windows-after-gensrc.md5 | sort | uniq -c
for t in before after; do
  find jfx-$t/modules/javafx.base jfx-$t/modules/javafx.graphics jfx-$t/buildtools/jslc -name 'hs_err_pid*' -type f
done
```
It must show:
- `before exit 0` and `after exit 0`, and `BUILD SUCCESS` in both logs;
- from `diff`, no other lines than these (with tabs between the fields), besides the hunk lines:
  - `> com.sun.scenario.effect.compiler.PrismProgramTest 13 0 0 0`
  - `> com.sun.scenario.effect.compiler.ProgramGlueBlockTest 20 0 0 0`
  - `> com.sun.scenario.effect.compiler.lexer.GlueBlockTest 8 0 0 0`
- jslc `Tests run: 235, Failures: 0, Errors: 0, Skipped: 3` before and
  `Tests run: 276, Failures: 0, Errors: 0, Skipped: 3` after (the three `link/..` tests of `MetalHeadersTest` are
  disabled on Windows); the same javafx.base line in both runs and the same javafx.graphics line in both runs, each
  with `Failures: 0, Errors: 0`;
- `warning(131): 1` before and `warning(131): 0` after;
- `gensrc identical by name and md5`, with `108 jsl-decora` and `424 jsl-prism` and no other directory, for the
  reasons US-019's [Windows gate](US-019-reject-trailing-input-in-the-jsl-compiler.md#windows-gate) gives;
- nothing from `find`.

Both runs use `-am`, which the criterion's Windows command lacks, as US-019's gates do (its code-review finding 8):
without it Maven takes `org.openjfx:javafx-base:28-ea` and its `tests` jar from the local repository, and in a fresh
clone with nothing installed the build stops with `Could not resolve dependencies`. With `-am`, javafx.base is built
in the same reactor and its tests run too, which is why the checks print a javafx.base line.

#### WSL gate
In WSL (Ubuntu), with JDK 27 and Maven 3.10.0 on `PATH`, from fresh clones, with `-am`, no display and no
`-Djfx.parity.require=true` (the Linux font goldens were captured on another machine):
```bash
cd ~
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach 9186c05be8
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <commit>
for t in before after; do
  (cd ~/jfx-$t && env -u DISPLAY -u WAYLAND_DISPLAY \
    mvn -B -ntp -l ../us020-wsl-$t.log -pl buildtools/jslc,modules/javafx.graphics -am clean test; echo "$t exit $?")
done
```
The checks are the Windows ones, run in `~` with `windows` replaced by `wsl` in the file names. They must show what
the Windows gate shows, with two differences:
- jslc `Tests run: 235, Failures: 0, Errors: 0, Skipped: 0` before and
  `Tests run: 276, Failures: 0, Errors: 0, Skipped: 0` after: Linux runs the three `link/..` tests;
- `108 jsl-decora` and `424 jsl-prism` from `-es2` (`pom.xml:48`), as in US-019's
  [WSL gate](US-019-reject-trailing-input-in-the-jsl-compiler.md#wsl-gate).

The javafx.base line is expected to be `Tests run: 5510, Failures: 0, Errors: 0, Skipped: 22` in both runs of both
gates: US-018's CI measured 5,510 / 0 / 0 / 22 on Windows x64 and on Linux
([US-018, Before the merge](US-018-fix-jslc-metal-header-path-and-static-state.md#before-the-merge-2026-11-07)).

`LinuxFontConfigGoldenTest`, `LinuxFontProcessGoldenTest`, `LinuxFreetypeGoldenTest` and `LinuxPangoGoldenTest` each
skip one test in both runs (US-008).

#### After the gates
The rest of the [Definition of Done](#definition-of-done):
- Keep each gate's lists with the change, as `US-020-macos-per-class.tsv` and `US-020-macos-gensrc.md5` are kept. In
  the directory that holds the clones, for `windows` and then `wsl`, with `REPO` the checkout of the commit:
  ```bash
  merge() {
    awk -F'\t' 'function cell() { return $2 ", Failures: " $3 ", Errors: " $4 ", Skipped: " $5 }
      FNR == NR { b[$1] = cell(); all[$1] = 1; next } { a[$1] = cell(); all[$1] = 1 }
      END { for (c in all) print c "\t" ((c in b) ? b[c] : "-") "\t" ((c in a) ? a[c] : "-") }' "$1" "$2" |
      LC_ALL=C sort
  }
  o=windows
  { echo "# US-020: per-class results of <the gate's mvn command>, <host>, <date> (columns: class, before, after;" \
      "before = 9186c05be8, after = <commit>; '-' = no such class)"
    merge us020-$o-before-per-class.tsv us020-$o-after-per-class.tsv; } > "$REPO/backlog/US-020-$o-per-class.tsv"
  cp us020-$o-after-gensrc.md5 "$REPO/backlog/US-020-$o-gensrc.md5"
  ```
  `merge` gives the macOS file's body from the macOS gates' lists. The after md5 list is kept, since the gate requires
  it to equal the before list.
- Fill in the results of both gates here and in `backlog/README.md`.
- File the upstream issue from its draft, `US-020-upstream-draft.md`, kept in `_claude/` next to the repository. It
  refers to US-019's upstream issue: file that one first, or insert its id once both are filed.
- Set the status to ✅ Done with the date and the PR, and move the row to the done table of `backlog/README.md`.
