# US-019 — Make the JSL compiler reject input after the last declaration

**Status:** 📋 Ready (filed 2026-09-27; reproduced on Windows by calling the JSL compiler directly and by running the shader generators the way the pom runs them, outside Maven; both fix options prototyped outside the repository, where every shipped shader regenerates md5-identical and the 159 jslc tests pass) · **Found:** 2026-09-27, US-008 (review of the jslc test port: the rule-level tests needed an end-of-input check, and the compiler has none)

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
  an empty program `line 1:0 mismatched input '<EOF>' expecting {…}`, an unclosed function
  `line 4:0 mismatched input '<EOF>' expecting {…}`. The `(...)+` loop needs one declaration.

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
    `token recognition error at: '@'`) and an unclosed function (`mismatched input '<EOF>'`).

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
  - `javafx-jslc` runs its 159 tests plus the new ones, with no failure.
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
