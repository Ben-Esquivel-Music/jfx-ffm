# US-020 — Stop a JSL glue block swallowing the code after it

**Status:** 📋 Ready (filed 2026-09-27; reproduced on Windows by calling the JSL compiler directly and by running the Decora generator outside Maven; two fix options prototyped outside the repository, where every shipped shader regenerates md5-identical and the 159 jslc tests pass; the ANTLR 3 compiler of `52adea7c36^` rebuilt to show the behaviour before the ANTLR 4 upgrade; a glue block added to a Prism shader, run through the Prism generator as the pom runs it, vanishes without a message) · **Found:** 2026-09-27, US-008 (filing US-019: two of its parse cases had two glue blocks; one lost the function between them with no error, the other failed with `Unknown variable x`)

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
  (`:481-483`). The parser rule `glue_block` (`:354-356`) is one alternative of `external_declaration` (`:341-345`),
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
  greedy block ()* contains wildcard; the non-greedy syntax ()*? may be preferred`.
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
  7 files. The `.hlsl` is empty, the `.frag` is the 14-line GLSL preamble alone, and the `.metal` has 8 lines,
  without the shader function. `javac` fails on the peers with 16 errors (`JSWBrightpassPeer.java:61`, `illegal
  start of type`, first) and 10 (`PPSBrightpassPeer.java:59`).
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
  - one block, `param`, a `//` comment containing `>>`, `main` (today 1 declaration, without an error).
- **The edge of the chosen rule is pinned.** For (a): a block with `>>` inside a Java line fails with the full
  message, `line L:C` included (this needs US-019). For (b): the same block parses as one declaration with its full
  text, a block on one line fails with the full message, and so does a block with a Java line that starts with `>>`
  (this needs US-019).
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
  `    // luminance >> 1 would halve it` as line 40 writes the 7 files of the unmodified shader. Today its `.hlsl` is
  empty and the peers have 16 and 10 `javac` errors.
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
  - `javafx-jslc` runs its 159 tests plus the new ones, with no failure.
- **Upstream.** Repeat the JBS search when the story is picked up. Draft an upstream issue with the two-block and
  comment repros, the ANTLR 3 comparison, the Prism glue repro and the chosen options. The draft says that upstream
  runs no jslc test (JDK-8226635), so the fix's test needs that port too.

## Definition of Done
- All acceptance criteria met, with the logs and md5 lists of both gate runs kept with the change.
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
- The probe drivers, the ANTLR 3 build and the variant compilers were not kept in the repository. The Problem and
  Measured sections describe each repro fully enough to rebuild it.
