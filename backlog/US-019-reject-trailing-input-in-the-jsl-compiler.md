# US-019 — Make the JSL compiler reject input after the last declaration

**Status:** 🔷 Implemented (2026-11-14; filed 2026-09-27), uncommitted; the Windows and WSL gates and filing the
upstream issue are [owner-run](#owner-run-and-pending) · **Found:** 2026-09-27, US-008 (review of the jslc test port:
the rule-level tests needed an end-of-input check, and the compiler has none)

## Story
As a developer who edits a `.jsl` shader, or who reviews such an edit,
I want the JSL compiler to fail with a syntax error on anything after the last complete declaration,
so that a stray brace or leftover text cannot silently drop functions, `main` included, from the generated HLSL, GLSL
and Metal shaders and Java peers while the generator exits 0.

## Problem
Paths are relative to `modules/javafx.graphics` unless they start with `backlog/` or `buildtools/`. A bare line number
(`:NN`) refers to the file named in the same sentence, else to the one named in the lead-in of its list.

### The parser stops, without an error, at the first token that cannot start a declaration
- `JSLC.getParserInfo(InputStream)` (`src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java:99-104`) calls
  `parser.translation_unit()` and visits the result (`:102`). Nothing looks at the token where the parser stopped.
- The rule is `translation_unit : (e=external_declaration)+ ;`
  (`src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4:337-339`). No rule of the grammar names `EOF`.
- ANTLR's `(...)+` loop ends, without reporting anything, at the first token that cannot start an
  `external_declaration` (`JSL.g4:341-345`). `ThrowingErrorListener` throws only for errors that the lexer or the
  parser reports (`src/jslc/java/com/sun/scenario/effect/compiler/ThrowingErrorListener.java:41`), and here none is
  reported.
- The lexer runs only as far as the parser looks. A lexer error in the first trailing token is still reported
  (`@` there gives `line 3:0 token recognition error at: '@'`), but `} @ #` after the last declaration passes: the
  parser stops at the `}`, and the `@` is never lexed.
- `JSLVisitor.visitTranslation_unit` builds the program from the declarations the parser matched
  (`src/jslc/java/com/sun/scenario/effect/compiler/tree/JSLVisitor.java:654-660`). Everything from the stopping token
  on is gone, and so are its errors: `}` followed by `void other() { undefinedCall(); }` compiles, while the same call
  inside `main` fails with "Unknown function undefinedCall()".
- `getParserInfo` is the only caller of `translation_unit()`, in the compiler and in the tests. `JSLC.compile` calls
  it for the first output that is out of date (`JSLC.java:196`, `:206`, `:216`, `:229`, `:239`). The Prism loader
  step calls it when no shader output needed a parse (`src/main/jsl-prism/CompileJSL.java:426`).
- The tests have a guard, production has none. `ParserBase.assertAllInputConsumed`
  (`src/test/jslc/com/sun/scenario/effect/compiler/parser/ParserBase.java:52-62`) fails a test when the rule it
  called stopped before the end of the input. The 13 parser-test helpers use it because a rule called directly has no
  EOF after it (`backlog/US-008-remove-dead-jslc-me-backend-and-simd.md:150-152`).

### What a stray brace does to a shader
The Decora cases below were run with the generator class the build uses for these shaders, `CompileJSL`
(`src/main/jsl-decora`), with the output types `GenAllDecoraShaders` passes for them (`-java -hw`), all three shader
backends (`-d3d -es2 -mtl`) and output under `…/gensrc/jsl-decora` (Measured has the setup). Each writes 7 files:
`.hlsl`, `.frag`, `.metal`, the Java software peer `JSW<name>Peer.java`, the Prism peer `PPS<name>Peer.java` and the
two Metal headers.
- **A stray `}` before `void main()` in `Brightpass.jsl`** (`src/main/jsl-decora/Brightpass.jsl:35`): exit 0, 7 files.
  - HLSL: 2 lines, the two uniforms, no `main`.
  - GLSL: 16 lines, no `main`.
  - Metal: 13 lines, no `[[fragment]]` function. The texture-sampling helper runs into the tail of the missing
    fragment function and ends in `return outFragColor;`, a variable nothing declares.
  - `JSWBrightpassPeer.java` loses the per-pixel body of `main` (218 lines become 154) but still reads `color_x`,
    `color_y`, `color_z` and `color_w`.
  - `PPSBrightpassPeer.java` and both headers are unchanged.
- **A stray `}` between two functions of `ColorAdjust.jsl`**, after `rgb_to_hsb` (`src/main/jsl-decora/ColorAdjust.jsl`
  `:52-86`): exit 0. `hsb_to_rgb` (`:88-133`) and `main` (`:136-180`) are missing from every output; the HLSL keeps
  the uniforms and `rgb_to_hsb`.
- **`} ) 42 garbage + + ;` after the last `}` of `Brightpass.jsl`:** exit 0, and all 7 files are byte-identical to
  those of the unmodified shader. The text is ignored.
- **A stray `}` at the end of `PaintImagePattern.jsl`** (`src/main/jsl-prism`), with the Prism generator: exit 0. The
  generator appends `main` after the mask and the paint (`src/main/jsl-prism/CompileJSL.java:392-405`), so `main` is
  dropped from all 22 `<mask>_ImagePattern` shaders (11 masks, with and without `_AlphaTest`): 22 of the 212 shaders
  have no `main` in any backend, so 66 of the 636 `.hlsl`, `.frag` and `.metal` files have no `main` or fragment
  function. Their 22 `_Loader.java` files change too: the highest texture coordinate index passed to `createShader`
  becomes `-1` (from `1`, or `0` for `Texture` and `-1` for `Solid`), and pixel coordinates are no longer marked as
  used (`true` becomes `false`).

### What catches it later, and what does not
- **Java software peers.** The second-pass `javac` of the module (`pom.xml:221-243`) compiles the generated peers.
  Compiled against a `javafx.graphics` build, `JSWBrightpassPeer.java` from the first case fails with 4 errors,
  `variable color_w might not have been initialized` and the same for `color_x`, `color_y` and `color_z`: in generated
  code, not at the `.jsl` line. The `ColorAdjust` case fails the same way.
  - Only the 8 Decora entries that get a generated software peer (`-java`,
    `src/main/jsl-decora/GenAllDecoraShaders.java:64-71`) have this net.
  - The `LinearConvolve` shaders (`-hw` only) and all Prism shaders do not: `PPSBrightpassPeer.java` from the first
    case and all 22 changed Prism loaders compile.
- **Windows native build.** `fxc` compiles every generated `.hlsl` (`native/win.cmake:268-283`) and rejects a shader
  with no `main`: `error X3501: 'main': entrypoint not found`. It accepts the text left after the last `}`, since that
  never reaches the output.
- **Linux (ES2).** The `.frag` files are copied into the module unchecked (`pom.xml:264-267`) by `copy-shaders`
  (`generate-test-sources`, `:253-254`), after the second-pass `javac` of `jsl-codegen` (`compile`, `:125-126`).
  - A shader without `main` that has no generated software peer (every Prism shader, `LinearConvolve*`) is built
    and packaged. It can fail only at run time, when the ES2 pipeline compiles and links it (not tested).
  - A Decora shader with a generated software peer, such as the `Brightpass.jsl` case, stops the build at that
    `javac` on every platform, before any `.frag` is packaged.
- **macOS (Metal).** The native build compiles every generated `.metal` file (`native/mac.cmake:245-253`). The helper
  above uses an undeclared variable, so that compile should fail; not run, for lack of a macOS host.

### Shipped shaders stop at EOF today
- 62 `.jsl` files are in the tree: 32 in `src/main/jsl-decora` and 30 in `src/main/jsl-prism`. Each ends with a `}`
  line and a newline.
- **What the build parses.** An instrumented copy of the compiler logged, for each parse, the token where the parser
  stopped. Run as the pom runs the generators, with `-d3d -es2 -mtl`, it parsed 264 programs (52 Decora, 212 Prism).
  All 264 stop at EOF.
- **What the build does not parse.** Replacing each file in turn with text that cannot lex shows that the build parses
  the text of 29 Decora files and 25 Prism files. The other 8:
  - `BoxBlur.jsl`, `GaussianBlur.jsl` and `ZoomRadialBlur.jsl` are read only by `CompileBoxBlur`, `CompileGaussian`
    and `CompileZoomRadialBlur`, which `GenAllDecoraShaders` does not call (`GenAllDecoraShaders.java:63-74`). Run
    directly, the three drivers parse 49 programs, all stopping at EOF.
  - `MaskSolid.jsl`, `MaskAlphaOne.jsl` and `PaintColor.jsl` are read, but the generator omits the trivial `mask()` or
    `paint()` call instead of compiling their text (`src/main/jsl-prism/CompileJSL.java:361-377`).
    `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl` are never read. Parsed alone, all five stop at EOF.

So no shipped shader has trailing input, and neither fix option changes a generated file (see the prototypes below).

### Upstream too
- The compiler has never checked for EOF. From its open-sourcing in `d0a313da5d` (2012) to `52adea7c36^`, the ANTLR 3
  grammar has `translation_unit returns [ProgramUnit prog]` with `(e=external_declaration ...)+` and no EOF
  (`JSL.g:555-561` at `52adea7c36^`), and its `JSLC.getParserInfo` also just called `parser.translation_unit()`.
  `52adea7c36` (8218170, the ANTLR 4.7.2 upgrade) kept the rule, and `8889330cc3` (8221269) moved its actions to
  `JSLVisitor`. Neither the fork's commits after `ca9b07aeae`, the last upstream commit in this history, nor the
  US-008 change touch the rule or `getParserInfo`.
- `openjdk/jfx` master (`d46e6092ae`, checked 2026-09-27) has the same `JSL.g4`, identical to this tree's, and the
  same `getParserInfo` (`JSLC.java:110-115` there).
- JBS (searched 2026-09-27, project JDK, component `javafx`): no issue reports it. Searched: summary `JSL` (17
  issues), summary `jslc` (2), text `JSL` and `EOF` (0), text `JSL` and `trailing` (1, JDK-8281422, whitespace
  checks), text `JSLC` and `silently` (0). JDK-8226635, "Get JSL tests working again (after Jigsaw modularization)",
  has been open since 2019: upstream does not run the jslc tests.

### Measured (2026-09-27, Windows 10, JDK 26)
**Setup.**
- The JSL compiler was compiled from `src/jslc/java`, with the parser generated by ANTLR 4.7.2 (`-visitor`) from
  `src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4` and the templates from `src/jslc/resources`. The
  generators were compiled from copies of `src/main/jsl-decora` (against a `javafx.graphics` build) and
  `src/main/jsl-prism`, and run as the pom runs them (`pom.xml:156-216`).
- Check of the setup: the regeneration with `-d3d -es2 -mtl` contains every file of `target/gensrc/jsl-decora` (108)
  and `target/gensrc/jsl-prism` (424) of the US-008 Windows gate build, identical by md5. It matches the WSL gate's
  files after removing carriage returns: the generated Java files have CRLF line ends on Windows.
- All parse cases and the four generator cases were repeated with the jslc classes of that gate build, with the same
  results and byte-identical outputs.

**Parse results** (`JSLC.getParserInfo` on each program; `param float x; void main() { color = float4(x); }` is the
valid program, with trailing input added):
- valid program: both declarations, stops at EOF;
- `} ) 42 garbage + + ;` after it: both declarations, stops at `}` (3:0);
- `}` and `void other() { undefinedCall(); }` after it: both declarations, stops at `}` (3:0);
- `}` between the `param` and `main`: 1 declaration, stops at `}` (2:0);
- `;` after it: stops at `;` (3:0);
- `// trailing comment` after it, with no final newline: stops at `/` (3:0). `LINE_COMMENT` needs a newline
  (`JSL.g4:477-479`), so the comment lexes as `/`, `/`, `trailing`, `comment`, and is dropped as trailing input;
- the same comment with a final newline: stops at EOF;
- an unclosed `/*` comment after it: stops at `/`. `COMMENT` needs its `*/` (`JSL.g4:473-475`), so the text lexes as
  `/`, `*` and so on, and is dropped as trailing input;
- errors reported today: a stray `}` before the first declaration gives
  `line 1:0 extraneous input '}' expecting {'const', 'param', 'lowp', 'mediump', 'highp', 'void', TYPE, GLUE_BLOCK}`,
  and an empty program `line 1:0 mismatched input '<EOF>' expecting {…}`. The `(...)+` loop needs one declaration.
  The message for an unclosed function depends on its body (re-measured 2026-11-14 on macOS, the same before and
  after the fix): `param float x;` and `void main() {` on two lines give
  `line 3:0 mismatched input '<EOF>' expecting {…}`, and the same with `color = float4(x);` on a third line gives
  `line 4:0 extraneous input '<EOF>' expecting {…}`. ANTLR reports `mismatched` when the body's `(statement)*` loop
  has not started, and `extraneous` after a statement.

## Proposed fix
Two options; the choice is part of the story.
- **(a) EOF in the grammar:** `translation_unit : (e=external_declaration)+ EOF ;`.
  - The generated context gains an `EOF()` accessor. `visitTranslation_unit` iterates `ctx.external_declaration()`
    only (`JSLVisitor.java:656`), so the visitor needs no change.
  - The message comes from ANTLR through `ThrowingErrorListener`, for example
    `line 35:0 extraneous input '}' expecting {<EOF>, 'const', 'param', 'lowp', 'mediump', 'highp', 'void', TYPE,
    GLUE_BLOCK}`. It has the form of every other syntax error.
  - Every caller of `translation_unit()` gets the check. The grammar then differs from upstream's until upstream
    takes the change.
- **(b) A check in `JSLC.getParserInfo`:** after `translation_unit()`, if `parser.getCurrentToken()` is not EOF, throw
  a `ParseCancellationException` with `ThrowingErrorListener`'s `line L:C <message>` form, for example
  `line 35:0 extraneous input '}' after the last declaration`.
  - No grammar change, and the message text is ours to choose.
  - A caller that runs `translation_unit()` itself would not be covered; there is none today.
- **Both options: let a line comment end at EOF.** With either change alone, a file whose last line is a `//` comment
  without a newline, accepted today, fails with `line 3:0 extraneous input '/' …`. The lexer rule
  `LINE_COMMENT : '//' ~('\n'|'\r')* ('\r'? '\n' | EOF) -> channel(HIDDEN) ;` fixes that. With ANTLR 4.7.2 it lexes
  `// x` at EOF as one hidden `LINE_COMMENT`, and `// ignored` with its newline stays one token with the newline, as
  `LineCommentTest.comment` expects.
- **What users see.** Today every syntax error is an `org.antlr.v4.runtime.misc.ParseCancellationException` with the
  message `line L:C <text>` (`ThrowingErrorListener.java:41`). `GenAllDecoraShaders` calls each compiler by
  reflection (`GenAllDecoraShaders.java:89-91`), so the Decora JVM reports an `InvocationTargetException` caused by
  it; the Prism generator throws it directly. Either JVM exits 1, and `failonerror="true"` stops the build
  (`pom.xml:156`, `:200`). Both options keep this, so trailing input fails like any other syntax error.
- **Line numbers of composed programs.** The Prism, `Blend` and `PhongLighting` generators compile programs they
  assemble from several files, or from a file and text of their own. The line in the message is that of the assembled
  program, for trailing input as for any other syntax error: a stray `}` on line 43 of `PaintImagePattern.jsl` is
  reported as `line 44:0` for `Solid_ImagePattern`, whose program starts with an empty line where the mask would be.
  That is filed as US-022 (`backlog/US-022-report-jsl-errors-at-the-source-file-line.md`).
- **Prototypes, outside the repository:** (a), (b), and (a) with the `LINE_COMMENT` change, each built from the
  working tree with ANTLR 4.7.2.
  - Each regenerates all 1,059 files (208 `jsl-decora`, 848 `jsl-prism`, 3 `mtl-headers`) with `-d3d -es2 -mtl`,
    identical by md5 to the current compiler's.
  - The 159 jslc tests pass with each.
  - Each rejects the trailing-input cases above and accepts the valid program and the comment with a final newline.
    The comment without a final newline is accepted only with the `LINE_COMMENT` change. An unclosed `/*` at the end
    fails with every variant (`extraneous input '/'`), as it should: the comment is not finished.
  - Run with the Decora generator, the `Brightpass.jsl` case fails with `line 35:0 …` and writes nothing; through
    `GenAllDecoraShaders` it exits 1 after the 5 outputs of `ColorAdjust`. The `ColorAdjust.jsl` case fails at
    `line 87:0` and the garbage case at `line 43:0`.

## Acceptance criteria
- **Trailing input is rejected.** A new jslc test in `src/test/jslc/com/sun/scenario/effect/compiler` (the surefire
  include, `buildtools/jslc/pom.xml:118`, picks it up) calls `JSLC.getParserInfo(String)`, the production entry
  point, and asserts a `ParseCancellationException` with the full message, `line L:C` included, for at least:
  - text after the last declaration;
  - a stray `}` between two declarations, which drops `main` today;
  - a stray `}` followed by a function that calls an undefined function.
- **Mutant proof,** as for every jslc negative test (`backlog/US-008-remove-dead-jslc-me-backend-and-simd.md:159-161`).
  In scratch copies, each negative test must fail:
  - with a valid program in place of the invalid one;
  - with an input that fails for another reason: a lexer error (`@` as the first trailing token gives
    `token recognition error at: '@'`) and an unclosed function (`mismatched input '<EOF>'` with an empty body,
    `extraneous input '<EOF>'` after a statement).

  The repository version passes.
- **Valid input is accepted.** A test parses a valid program and asserts all its declarations, and another parses a
  program whose last line is a `//` comment without a newline. The second fails with the grammar or `getParserInfo`
  change alone.
- **End to end.** The Decora generator on a copy of `Brightpass.jsl` with a stray `}` before `void main()` exits
  non-zero with the pinned message and writes no output for it. Today it exits 0 and writes 7 files.
- **Generated files unchanged.** Regenerate Decora and Prism with `-d3d -es2 -mtl` before and after the fix. All 1,059
  files are identical by name and md5. Run `CompileBoxBlur`, `CompileGaussian` and `CompileZoomRadialBlur` and parse
  `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl` after the fix too; they must still compile, although the
  build does not use them. US-021 (`backlog/US-021-remove-unused-decora-generator-inputs.md`) proposes deleting them;
  if it lands first, this part of the criterion goes.
- **Build gate,** with the two commands the US-008 gate ran:
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`.
    The Linux font goldens were captured on another machine, so `LinuxFontConfigGoldenTest`,
    `LinuxFontProcessGoldenTest`, `LinuxFreetypeGoldenTest` and `LinuxPangoGoldenTest` skip one test each there, and
    the property turns those skips into failures (`src/test/java/test/com/sun/javafx/font/FontGoldens.java:167-192`,
    `src/test/java/test/com/sun/javafx/test/ParityGate.java:103-115`).
  - `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` is identical by name and md5 before and after. In the
    US-008 gate these held 108, 424, 46 and 0 files on each system.
  - `javafx.graphics` has the same test counts per class as before.
  - `javafx-jslc` runs its 196 tests plus the new ones, with no failure.
- **Upstream.** Repeat the JBS search when the story is picked up. Draft an upstream issue with the `Brightpass.jsl`
  repro, the outputs without `main` and the chosen fix. The draft says that upstream runs no jslc test
  (JDK-8226635), so the fix's test needs that port too.

## Definition of Done
- All acceptance criteria met, with the logs and md5 lists of both gate runs kept with the change.
- The chosen option, (a) or (b), and the reason recorded in this file.
- An independent review repeated the mutant proofs with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- The upstream issue draft kept outside the repository until it is filed.

## Notes
- Filed rather than fixed in US-008 (`backlog/US-008-remove-dead-jslc-me-backend-and-simd.md:244-247`): US-008 removes
  dead code without changing behaviour, and this fix changes which programs the compiler accepts, so it needs its own
  change and its own shader md5 gate.
- `ParserBase.assertAllInputConsumed` stays after the fix. The parser tests call inner rules, which have no EOF after
  them under either option.
- Neither option catches the code a glue block swallows: `GLUE_BLOCK` is greedy (`JSL.g4:481-483`), so a block runs
  to the last `>>` of the program, a second block's closer or a `>>` in a later JSL comment, and the code in between is
  lost. With a second block the parser still reaches EOF, and neither option reports anything. With a `>>` in a
  comment, either option reports an error, but at the token after that `>>`, inside the comment, not at the block.
  That is filed as US-020 (`backlog/US-020-stop-jsl-glue-blocks-swallowing-code.md`).
- `JSLC.compile` parses a shader only when one of its outputs is out of date (`JSLC.java:195-196`, `:249-251`). A
  build without `clean` therefore reports trailing input when it regenerates the edited shader, as it does for every
  other syntax error.
- The probe drivers and the instrumented compiler were not kept in the repository. The Problem and Measured sections
  describe each repro fully enough to rebuild it.

## Implementation (2026-11-14, macOS)
The change is uncommitted in the main tree, on `d803860b7e` (HEAD below). Everything was built and run on one arm64
Mac: macOS 27.2.1 (build 26B131), Apache Maven 3.10.0 and Oracle Corporation's OpenJDK 27.0.1.0.2 (build
27.0.1.0.2+1-2), as `sw_vers`, `mvn -version` and `java -version` report on that host. The gate logs print only that
the enforcer's `RequireJavaVersion` and `RequireMavenVersion` rules passed, and AppleClang 21.0.0.21000334 for the
native build. This host has no Windows and no Linux, so those gates are [owner-run](#owner-run-and-pending). Paths
are relative to `modules/javafx.graphics`, as in Problem.

The probe, the mutants, the generator runs and their logs are in the session scratch directory, which is temporary:
under `/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/`, the directory
`9cef09f8-b393-43e9-8574-939b14cb68c5/scratchpad/us019-impl/`. The probe is `probe/ParseProbe.java` there: it calls
`JSLC.getParserInfo` on each case and prints the declarations and the token where the parser stopped, or the error.

### Choice: option (a)
EOF in the grammar, with the `LINE_COMMENT` change that both options need.
- It is the standard ANTLR idiom for a start rule.
- Every caller of `translation_unit()` gets the check, not only `JSLC.getParserInfo`.
- ANTLR writes the message, through `ThrowingErrorListener`, in the form of every other syntax error.
- `JSLC` and `JSLVisitor` do not change: the visitor reads `ctx.external_declaration()` only.
- Option (b) covers only `getParserInfo` and needs a message of its own.
- The cost: the grammar differs from upstream's until upstream takes the same change, which the draft upstream issue
  proposes ([Upstream check](#upstream-check-2026-11-14)).

### Change
Relative to `d803860b7e`, three files:
- `src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4`: `translation_unit : (e=external_declaration)+ EOF ;`
  (`:337-339`) and `LINE_COMMENT : '//' ~('\n'|'\r')* ('\r'? '\n' | EOF) -> channel(HIDDEN) ;` (`:477-479`). The
  header years become `2008, 2026`. Nothing else changes.
- `src/test/jslc/com/sun/scenario/effect/compiler/TranslationUnitTest.java`, new (`Copyright (c) 2026`, 104 lines).
  Its 5 tests call `JSLC.getParserInfo(String)`. The valid program is `param float x;` and
  `void main() { color = float4(x); }`, one per line.
  - `textAfterTheLastDeclarationIsASyntaxError`: the valid program, then `} ) 42 garbage + + ;`.
  - `aStrayBraceBetweenTwoDeclarationsIsASyntaxError`: a `}` line between the two declarations.
  - `aStrayBraceBeforeAFunctionThatCallsAnUndefinedFunctionIsReportedAtTheBrace`: the valid program, `}` and
    `void other() { undefinedCall(); }`.
  - These three assert a `ParseCancellationException` and its full message ([Tests](#tests)).
  - `aValidProgramKeepsAllItsDeclarations` and `aLineCommentWithoutANewlineCanEndTheProgram` (the valid program, then
    `// trailing comment` with no newline) assert the declarations by kind, qualifier, type and name:
    `VarDecl param float x` and `FuncDef void main`.
- `src/test/jslc/com/sun/scenario/effect/compiler/lexer/LineCommentTest.java`: `commentEndingAtEndOfInput`,
  `assertRecognized("// ignored")`. The header years become `2008, 2026`.

`JSLC`, `JSLVisitor`, `ThrowingErrorListener`, `ParserBase`, the poms, the `.jsl` files and the generators do not
change. `ParserBase.assertAllInputConsumed` stays ([Notes](#notes)).

### Behaviour changes besides trailing input
Measured with the probe on HEAD's jslc jar and the change's:
- An unclosed `/*` at the end is now rejected. After the valid program, `/* unclosed comment` and a newline stop the
  parser at `/` (3:0) at HEAD and are dropped; the change gives
  `line 3:0 extraneous input '/' expecting {<EOF>, …}`.
- A file that is only `// note` without a newline is rejected before and after. The message moves from
  `line 1:0 mismatched input '/' expecting {'const', …}` to `line 1:7 mismatched input '<EOF>' expecting {'const', …}`:
  the comment is now one hidden token, and the `(...)+` loop finds no declaration. `// x` alone gives `line 1:4`.
- A last line `// c` ended by a lone CR (`\r` without `\n`) is now rejected. After the valid program, HEAD accepts it
  only by dropping it as trailing input (it stops at `/`, 3:0); the change gives
  `line 3:0 extraneous input '/' expecting {<EOF>, …}`.
- A lone CR never ended a `//` comment mid-file. HEAD and the change both reject `// header`, a CR and the valid
  program with `line 1:0 mismatched input '/' expecting {'const', …}`. `param float4 k;`, a CR, `// note`, a CR and
  `void main() { color = k; }` loses `main` at HEAD (1 declaration, stops at `/`, 1:16), and the change rejects it
  with `line 1:16 extraneous input '/' expecting {<EOF>, …}`. ANTLR counts lines by `\n` only, hence line 1.
- Not fixed here: CR-only line ends would be a separate lexer change, `('\r' '\n'? | '\n' | EOF)`. None of the 62
  `.jsl` files in the tree has a CR: `git ls-files '*.jsl' | xargs grep -l $'\r'` prints nothing.

### Acceptance criteria and evidence
| Criterion | Evidence | State |
| --- | --- | --- |
| Trailing input is rejected | The three negative tests of `TranslationUnitTest`, pinned at `line 3:0`, `line 2:0` and `line 3:0 extraneous input '}' expecting {<EOF>, …}` ([Tests](#tests)) | Met |
| Mutant proof | Ours: a valid program, `@` as the first trailing token and an unclosed function with an empty body; each of the 3 negative tests fails under each, and the repository version passes ([Tests](#tests)). The review's, with its own inputs ([Independent review](#independent-review-2026-11-14)) | Met |
| Valid input is accepted | `aValidProgramKeepsAllItsDeclarations` and `aLineCommentWithoutANewlineCanEndTheProgram`. With the EOF change alone the second errors with `line 3:0 extraneous input '/'`, and `LineCommentTest.commentEndingAtEndOfInput` fails ([Tests](#tests), the review's G2) | Met |
| End to end | `Brightpass.jsl` with a stray `}` on line 35: exit 1, `line 35:0 extraneous input '}' expecting {<EOF>, …}`, no output; HEAD exits 0 with 7 files (HLSL 2 lines, GLSL 16, Metal 13, JSW peer 154 against 218). `GenAllDecoraShaders` exits 1 after the 5 outputs of `ColorAdjust`. `ColorAdjust.jsl` fails at `line 87:0`, the garbage case at `line 43:0` and `PaintImagePattern.jsl` at `line 44:0` ([Generator runs](#generator-runs)) | Met |
| Generated files unchanged | Outside Maven with `-d3d -es2 -mtl`, all 1,059 files identical by name and md5; `CompileBoxBlur`, `CompileGaussian` and `CompileZoomRadialBlur` exit 0 and their 141 files are unchanged; `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl` parse ([Generator runs](#generator-runs)). The review parsed each of the 62 `.jsl` files alone, with identical results on HEAD and the change. M2 on macOS ([Final gates](#final-gates-2026-11-14-macos)) | Met |
| Build gate | macOS: M1 and M2 ([Final gates](#final-gates-2026-11-14-macos)), extra evidence and not the criterion's commands. Windows and WSL: [owner-run](#owner-run-and-pending) | Passed on macOS (extra evidence); Windows and WSL owner-run |
| Upstream | [Upstream check](#upstream-check-2026-11-14); the draft issue, kept outside the repository | Checked and drafted; filing pending |

### Tests
- **jslc.** `mvn -B -ntp -pl buildtools/jslc package` in the main tree runs 202 tests in 26 classes, with 0 failures,
  0 errors and 0 skipped. The same command in a clone of HEAD runs 196 in 25. Per class the two are equal except
  `LineCommentTest`, 2 tests before and 3 after, and the new `TranslationUnitTest` with 5.
- **Pinned messages.** `textAfterTheLastDeclarationIsASyntaxError` and
  `aStrayBraceBeforeAFunctionThatCallsAnUndefinedFunctionIsReportedAtTheBrace`:
  `line 3:0 extraneous input '}' expecting {<EOF>, 'const', 'param', 'lowp', 'mediump', 'highp', 'void', TYPE,
  GLUE_BLOCK}`. `aStrayBraceBetweenTwoDeclarationsIsASyntaxError`: the same at `line 2:0`. This is the form that
  [Proposed fix](#proposed-fix) gives for option (a).
- **Mutant proofs,** in a clone with the three files. Each mutant changes only the three negative inputs, and
  `mvn -B -ntp -pl buildtools/jslc test -Dtest=TranslationUnitTest -Dsurefire.failIfNoSpecifiedTests=false` then
  runs 5 tests with 3 failures, the two positive tests passing:
  - the valid program in place of each invalid one:
    `Expected org.antlr.v4.runtime.misc.ParseCancellationException to be thrown, but nothing was thrown.`;
  - `@` in place of the stray `}`: `… but was: <line 3:0 token recognition error at: '@'>` (`line 2:0` for the brace
    between the declarations);
  - an unclosed function with an empty body: the valid program, then `void unclosed() {` or `void other() {`, and
    `param float x;` with `void main() {` for the brace between the declarations:
    `… but was: <line 4:0 mismatched input '<EOF>' expecting {'const', …}>` (`line 3:0` for the brace between the
    declarations).
- **Each half of the grammar change alone,** in the same clone, with `-Dtest=TranslationUnitTest,LineCommentTest`:
  - HEAD's `JSL.g4`: 8 tests, 4 failures. The 3 negative tests throw nothing, and `commentEndingAtEndOfInput` fails
    with `expected: <// ignored> but was: </>`. `aLineCommentWithoutANewlineCanEndTheProgram` passes, because HEAD
    drops the comment as trailing input.
  - The EOF change without the `LINE_COMMENT` change: 1 failure and 1 error. `commentEndingAtEndOfInput` fails as
    above, and `aLineCommentWithoutANewlineCanEndTheProgram` errors with
    `line 3:0 extraneous input '/' expecting {<EOF>, …}`.
- **Parse results,** by the probe, for the cases of [Measured](#measured-2026-09-27-windows-10-jdk-26) (`…` stands
  for the rest of the message):

  | Program | HEAD | Change |
  | --- | --- | --- |
  | The valid program | 2 declarations, stops at EOF | The same |
  | `} ) 42 garbage + + ;` after it | 2 declarations, stops at `}` (3:0) | `line 3:0 extraneous input '}' expecting {<EOF>, …}` |
  | `}` and `void other() { undefinedCall(); }` after it | 2 declarations, stops at `}` (3:0) | `line 3:0 extraneous input '}' …` |
  | `}` between `param` and `main` | 1 declaration, stops at `}` (2:0) | `line 2:0 extraneous input '}' …` |
  | `;` after it | 2 declarations, stops at `;` (3:0) | `line 3:0 extraneous input ';' …` |
  | `} @ #` after it | 2 declarations, stops at `}` (3:0) | `line 3:0 extraneous input '}' …` |
  | `// trailing comment` after it, no final newline | 2 declarations, stops at `/` (3:0) | 2 declarations, stops at EOF |
  | The same comment with a final newline | 2 declarations, stops at EOF | The same |
  | An unclosed `/*` comment after it | 2 declarations, stops at `/` (3:0) | `line 3:0 extraneous input '/' …` |
  | `@` after it | `line 3:0 token recognition error at: '@'` | The same |
  | `undefinedCall();` inside `main` | `Unknown function undefinedCall()` | The same |
  | An empty program | `line 1:0 mismatched input '<EOF>' expecting {'const', …}` | The same |
  | `}` before the first declaration | `line 1:0 extraneous input '}' expecting {'const', …}` | The same |
  | Unclosed function, empty body | `line 3:0 mismatched input '<EOF>' …` | The same |
  | Unclosed function, a statement in the body | `line 4:0 extraneous input '<EOF>' …` | The same |
  | `@` inside `main` | `line 2:33 token recognition error at: '@'` | The same |
  | `void main() { color = ; }` | `line 1:22 mismatched input ';' expecting {'+', …}` | The same |

  "The same" is byte-identical output.

### Generator runs
- **Setup.** A clone of HEAD was built with
  `mvn -B -ntp -pl modules/javafx.graphics -am test-compile -DskipNative=true`. With `compile` instead, the build
  stops at `Could not resolve dependencies … org.openjfx:javafx-base:jar:tests:28-ea`: the reactor has base's `tests`
  artifact only from `test-compile` on. Each side's jslc jar came from `mvn -B -ntp -pl buildtools/jslc package`, in
  that clone and in the main tree.
- **Run as the pom runs them** (`pom.xml:140-204`), outside Maven, with the generators of the clone's
  `target/jsl-compilers/{decora,prism}`. Classpath: graphics `target/classes`, base `target/classes`, the jslc jar,
  `org.antlr:antlr4:4.7.2` and its 6 dependencies as in `maven.compile.classpath`, `src/jslc/resources` and the
  generator classes, plus the Prism input directory for the Prism generator. The Decora JVM also gets the pom's
  `--module-path`, `--add-modules=javafx.graphics` and three `--add-exports`. The inputs are copies of
  `src/main/jsl-decora` and `src/main/jsl-prism`. The two sides differ only in the jslc jar.
- **Setup check.** The clone's own Maven `target/gensrc` (797 files, macOS's `-mtl -es2`) is md5-identical to the
  same paths of the HEAD regeneration below, and to M2's.
- **Regeneration with `-d3d -es2 -mtl`.** `GenAllDecoraShaders` in one JVM and the Prism `CompileJSL` once per
  `.jsl` file, 30 JVMs, into an empty output root per side. All 31 JVMs exit 0 on both sides, and nothing is written
  to the working directory. Each side writes 1,059 files (`jsl-decora` 208, `jsl-prism` 848, `mtl-headers` 3),
  identical by name and md5.
- **Generator inputs the build does not use.** `CompileBoxBlur`, `CompileGaussian GaussianBlur` and
  `CompileZoomRadialBlur`, with `-d3d -es2 -mtl -java -hw` and an output root each, exit 0 on both sides and write
  43, 43 and 55 files, the 141 identical by md5. `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl`, parsed
  alone, give 2 declarations each (`inputTex`, `paint`) and stop at EOF on both sides.
- **End to end.** The Decora cases ran the Decora `CompileJSL` with `-java -hw` (the output types
  `GenAllDecoraShaders` gives them) and `-d3d -es2 -mtl`, the Prism case the Prism generator, stopping at the first
  JVM that fails, as `failonerror` does.

  | Case | HEAD | Change |
  | --- | --- | --- |
  | `Brightpass.jsl`, `}` inserted as line 35, before `void main()` | Exit 0, 7 files. `.hlsl` 13 lines become 2, `.frag` 24 become 16, `.metal` 25 become 13 (no fragment function; the helper ends in `return outFragColor;`), `JSWBrightpassPeer.java` 218 become 154. `PPSBrightpassPeer.java` and both headers are unchanged | Exit 1, `line 35:0 extraneous input '}' expecting {<EOF>, 'const', 'param', 'lowp', 'mediump', 'highp', 'void', TYPE, GLUE_BLOCK}`, no file written |
  | The same input through `GenAllDecoraShaders` | Exit 0, 210 files (208 and 2 headers); 4 Brightpass files differ from the unmodified run's | Exit 1, an `InvocationTargetException` caused by the same `line 35:0` message. It wrote the 5 outputs of `ColorAdjust` (`.hlsl`, `.frag`, `.metal`, `JSWColorAdjustPeer.java`, `PPSColorAdjustPeer.java`) and no header |
  | `ColorAdjust.jsl`, `}` inserted as line 87, after `rgb_to_hsb` | Exit 0, 7 files; `hsb_to_rgb` is in no output, and the `.hlsl`, `.frag`, `.metal` and the JSW peer differ from the unmodified run's | Exit 1, `line 87:0 extraneous input '}' …`, no file written |
  | `Brightpass.jsl`, `} ) 42 garbage + + ;` as line 43 | Exit 0, the 7 files byte-identical to those of the unmodified shader | Exit 1, `line 43:0 extraneous input '}' …`, no file written |
  | `PaintImagePattern.jsl` (Prism), `}` as line 43 | The 30 JVMs exit 0. 88 of the 848 files differ from the unmodified run: the `.hlsl`, `.frag` and `.metal` of the 22 `<mask>_ImagePattern` shaders, with and without `_AlphaTest`, 44 of them `.hlsl` or `.frag` files without `main`, and their 22 loaders, whose texture coordinate index becomes `-1` (from `1`, `0` for `Texture`) and pixel coordinate flag `false` | The first JVM exits 1 with `line 44:0 extraneous input '}' …`, thrown in `compilePatternPaint` for `Solid_ImagePattern`. It wrote the 4 files of `Solid_Color` |

### Final gates (2026-11-14, macOS)
Both runs were on the host above on 2026-11-14, in fresh clones: before at `d803860b7e`, after at `d803860b7e` plus
the three files, each `cmp`-identical to the main tree's (md5 `609c2952aac9409fb770b58833be61d5` for `JSL.g4`,
`9ffc222da30e859345fa2b62e9c8afc9` for `LineCommentTest.java`, `31c74b200ccd030c2d2bdbede68e4323` for
`TranslationUnitTest.java`). They are extra evidence: the criterion's gates are Windows and WSL. Counts are run /
failures / errors / skipped.

| Gate | Command | Result |
| --- | --- | --- |
| M1 module gate | `mvn -B -ntp -l <log> -Dmaven.test.failure.ignore=true -pl buildtools/jslc,modules/javafx.graphics -am clean test`, before and after | Both exit 0 with BUILD SUCCESS, and `jsl-codegen`, `cmake-configure` and `cmake-build` ran. jslc 196 / 0 / 0 / 0 in 25 classes before and 202 / 0 / 0 / 0 in 26 after; base 5,510 / 0 / 0 / 22 in 290 classes and graphics 26,696 / 0 / 0 / 858 in 629 in both. Of the 945 per-class lines after, `diff` shows only `TranslationUnitTest 5 0 0 0` added and `LineCommentTest` going from `2 0 0 0` to `3 0 0 0` ([per class](US-019-macos-per-class.tsv)). No `hs_err_pid*` file in either clone |
| M2 generated files | M1's `target/gensrc` | 797 files (`jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3), identical by name and md5 before and after ([md5 list](US-019-macos-gensrc.md5), the after run's; the before run's is `cmp`-identical). `DecoraShaderCommon.h` lists 50 shaders and `PrismShaderCommon.h` 212 |

M2's count differs from the US-008 gate's (Windows: 108, 424, 46 and 0 in `jsl-decora`, `jsl-prism`, `headers` and
`mtl-headers`) and from US-018's macOS F2 (817), for two reasons:
- macOS generates `-mtl -es2` (profile `jsl-backends-mac`, `pom.xml:641-652`), Windows `-d3d` (`jsl-backends-win`,
  `:609-620`) and Linux `-es2` (the default, `:48`). So `jsl-decora` holds a `.metal` and a `.frag` file for each of
  the 50 Decora shaders besides the 58 Java peers (158), and `jsl-prism` three files for each of the 212 Prism shaders
  (636).
- `9b02d6a602` (#57) removed the second-pass `javac -h`, so `target/gensrc/headers` no longer exists. US-018's F2 had
  20 files there: 817 is 797 and 20.

The jslc baseline is 196 tests in 25 classes.

### Independent review (2026-11-14)
- **Verdict:** pass with nits; no blocker and no should-fix.
- **Mutant proofs with its own inputs** (Definition of Done). Each of the 3 negative tests fails with:
  - (a) another valid program, `const float k = 2.0;`, `float twice(float v) { return v * k; }` and
    `void main() { color = float4(twice(0.25)); }`;
  - (b) the lexer errors `%`, `#` and `"` in place of the stray `}`, each at the same line and column with
    `token recognition error`;
  - (c) other syntax errors (a missing `;`).
- **Each half of the grammar change alone.** G1, HEAD's `translation_unit` with the new `LINE_COMMENT`: the 3
  negative tests fail and the rest pass. G2, the EOF change with HEAD's `LINE_COMMENT`: `commentEndingAtEndOfInput`
  fails and `aLineCommentWithoutANewlineCanEndTheProgram` errors with `line 3:0 extraneous input '/'`. G3, HEAD's
  grammar: 4 failures.
- **jslc per class** equal to HEAD's except `LineCommentTest`, 2 tests before and 3 after, and the new
  `TranslationUnitTest` with 5: 202 tests in 26 classes.
- **Probes** gave the behaviour changes above; its nit is the lone-CR case. It repeated the end-to-end cases with
  identical results, and the graphics `test-compile` `target/gensrc` (797 files) is identical by name and md5.
- Its logs are in the session scratch directory, which is temporary and not kept: under
  `/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/`, the directory
  `9cef09f8-b393-43e9-8574-939b14cb68c5/scratchpad/us019-review/logs/`.

### Upstream check (2026-11-14)
- **JBS.** The five searches of [Upstream too](#upstream-too) give the same counts as on 2026-09-27: 17, 2, 0, 1
  (JDK-8281422) and 0. No issue reports this. JDK-8226635 is still Open.
- **openjdk/jfx master.** At `d0e72f9d86` (2026-11-13), `JSL.g4` is byte-identical to HEAD's, and `getParserInfo` is
  the same (`JSLC.java:110-115` there).
- **Draft.** The upstream issue is drafted with the `Brightpass.jsl` repro, the outputs without `main` and option (a).
  It says that upstream runs no jslc test (JDK-8226635), so the fix's test needs that port too. It is kept outside
  the repository until it is filed: under
  `/private/tmp/claude-501/-Users-benesquivelmusic-Documents-SourceCode-jfx-ffm/`, the file
  `9cef09f8-b393-43e9-8574-939b14cb68c5/scratchpad/us019-upstream/US-019-upstream-draft.md`.

### Owner-run and pending
Replace `<repo-url>` with the repository's remote and `<commit>` with the owner's US-019 commit. Before is
`d803860b7e`. If `<commit>` is only on the PR branch, fetch it first with
`git -C jfx-after fetch -q origin pull/<PR>/head`. In PowerShell, quote every `-D` argument; under Git Bash, Maven
3.10's `bin/mvn` converts the paths. Each gate keeps its logs, its per-class lists (class, run, failures, errors,
skipped, separated by tabs) and its `target/gensrc` md5 lists as `us019-<os>-<before|after>…` in the directory that
holds the clones. The check commands ran as written in bash on the macOS host, on M1's clones and logs, and printed
what is expected below with M1's numbers.

#### Windows gate
Two sibling checkouts, in Git Bash:
```bash
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach d803860b7e
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <commit>
```
Maven from PowerShell, in the directory that holds both, so that MSVC builds the natives:
```powershell
$pl = "buildtools/jslc,modules/javafx.graphics"
cd jfx-before
mvn -B -ntp -l ..\us019-windows-before.log -pl $pl clean test "-Djfx.parity.require=true"
"before exit $LASTEXITCODE"; cd ..
cd jfx-after
mvn -B -ntp -l ..\us019-windows-after.log -pl $pl clean test "-Djfx.parity.require=true"
"after exit $LASTEXITCODE"; cd ..
```
The checks, in Git Bash in the same directory:
```bash
classes() {
  tr -d '\r' < "$1" | awk '/Tests run: .* -- in / { c = $NF; sub(/.*Tests run: /, ""); split($0, n, /[^0-9]+/)
    print c "\t" n[1] "\t" n[2] "\t" n[3] "\t" n[4] }' | LC_ALL=C sort
}
for t in before after; do
  classes us019-windows-$t.log > us019-windows-$t-per-class.tsv
  (cd jfx-$t/modules/javafx.graphics/target/gensrc && find . -type f | LC_ALL=C sort | xargs md5sum) \
    > us019-windows-$t-gensrc.md5
done
diff us019-windows-before-per-class.tsv us019-windows-after-per-class.tsv
for t in before after; do
  echo "$t:"
  tr -d '\r' < us019-windows-$t.log \
    | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|BUILD (SUCCESS|FAILURE)'
done
cmp us019-windows-before-gensrc.md5 us019-windows-after-gensrc.md5 && echo "gensrc identical by name and md5"
cut -d/ -f2 us019-windows-after-gensrc.md5 | sort | uniq -c
for t in before after; do
  find jfx-$t/modules/javafx.base jfx-$t/modules/javafx.graphics jfx-$t/buildtools/jslc -name 'hs_err_pid*' -type f
done
```
It must show:
- `before exit 0` and `after exit 0`, and `BUILD SUCCESS` in both logs;
- from `diff`, no other line than `> com.sun.scenario.effect.compiler.TranslationUnitTest 5 0 0 0`,
  `< com.sun.scenario.effect.compiler.lexer.LineCommentTest 2 0 0 0` and
  `> com.sun.scenario.effect.compiler.lexer.LineCommentTest 3 0 0 0` (with tabs between the fields), besides the
  hunk lines;
- jslc `Tests run: 196, Failures: 0, Errors: 0, Skipped: 3` before and
  `Tests run: 202, Failures: 0, Errors: 0, Skipped: 3` after (the three `link/..` tests of `MetalHeadersTest` are
  disabled on Windows), and the same javafx.graphics line in both, with `Failures: 0, Errors: 0`;
- `gensrc identical by name and md5`, with `108 jsl-decora` and `424 jsl-prism` and no other directory: Windows
  generates `-d3d` (`pom.xml:609-620`), so 50 `.hlsl` files and the 58 Java peers in `jsl-decora` and 212 `.hlsl`
  files and 212 loaders in `jsl-prism`; without `-mtl` the generators write no Metal header, and `headers` is gone
  since `9b02d6a602`;
- nothing from `find`.

Without `-am`, as the criterion gives the command, Maven takes `org.openjfx:javafx-base:28-ea` and its `tests` jar
from the local repository: that needs an earlier `mvn install`, and with nothing installed the build stops with
`Could not resolve dependencies`. Then add `-am` after the `-pl` value in both runs; javafx.base's 5,510 tests run
too, and the jslc and javafx.graphics counts do not change.

#### WSL gate
In WSL (Ubuntu), with JDK 27 and Maven 3.10.0 on `PATH`, from fresh clones, with `-am`, no display and no
`-Djfx.parity.require=true` (the Linux font goldens were captured on another machine):
```bash
cd ~
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach d803860b7e
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <commit>
for t in before after; do
  (cd ~/jfx-$t && env -u DISPLAY -u WAYLAND_DISPLAY \
    mvn -B -ntp -l ../us019-wsl-$t.log -pl buildtools/jslc,modules/javafx.graphics -am clean test; echo "$t exit $?")
done
```
The checks are the Windows ones, run in `~` with `windows` replaced by `wsl` in the file names. They must show what
the Windows gate shows, with three differences:
- jslc `Tests run: 196, Failures: 0, Errors: 0, Skipped: 0` before and
  `Tests run: 202, Failures: 0, Errors: 0, Skipped: 0` after: Linux runs the three `link/..` tests;
- javafx.base `Tests run: 5510, Failures: 0, Errors: 0, Skipped: 22` in both runs;
- `108 jsl-decora` and `424 jsl-prism` from `-es2` (`pom.xml:48`): 50 `.frag` files and the 58 Java peers, and 212
  `.frag` files and 212 loaders.

`LinuxFontConfigGoldenTest`, `LinuxFontProcessGoldenTest`, `LinuxFreetypeGoldenTest` and `LinuxPangoGoldenTest` each
skip one test in both runs (US-008).

#### After the gates
The rest of the [Definition of Done](#definition-of-done):
- Keep each gate's lists with the change, as `US-019-macos-per-class.tsv` and `US-019-macos-gensrc.md5` are kept. In
  the directory that holds the clones, for `windows` and then `wsl`, with `REPO` the checkout of the commit:
  ```bash
  merge() {
    awk -F'\t' 'function cell() { return $2 ", Failures: " $3 ", Errors: " $4 ", Skipped: " $5 }
      FNR == NR { b[$1] = cell(); all[$1] = 1; next } { a[$1] = cell(); all[$1] = 1 }
      END { for (c in all) print c "\t" ((c in b) ? b[c] : "-") "\t" ((c in a) ? a[c] : "-") }' "$1" "$2" |
      LC_ALL=C sort
  }
  o=windows
  { echo "# US-019: per-class results of <the gate's mvn command>, <host>, <date> (columns: class, before, after;" \
      "before = d803860b7e, after = <commit>; '-' = no such class)"
    merge us019-$o-before-per-class.tsv us019-$o-after-per-class.tsv; } > "$REPO/backlog/US-019-$o-per-class.tsv"
  cp us019-$o-after-gensrc.md5 "$REPO/backlog/US-019-$o-gensrc.md5"
  ```
  `merge` gives the macOS file's body from M1's lists. The after md5 list is kept, since the gate requires it to
  equal the before list.
- Fill in the results of both gates here and in `backlog/README.md`.
- File the upstream issue from its draft.
- Set the status to ✅ Done with the date and the PR, and move the row to the done table of `backlog/README.md`.
