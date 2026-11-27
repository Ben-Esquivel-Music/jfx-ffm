# US-019 — Make the JSL compiler reject input after the last declaration

## In short
- **Problem:** The shader compiler ignored everything after the last declaration of a file. A stray `}` made it write
  shaders without a `main` function and report success.
- **Goal:** Text after the last declaration is a syntax error, and nothing is written.
- **State:** Merged (PR #62, 2026-11-14).
- **Left to do (owner):** file the upstream issue (draft outside the repository). The Windows and WSL gates this
  story asks for have since been run on later trees that contain this change (US-023 and US-024, 0 failures on both
  systems).

**Status:** 🔶 Merged, not Done (PR #62, 2026-11-14). What is left is under "In short".
The status as written before the merge:
🔷 Implemented (2026-11-14; filed 2026-09-27); the Windows and WSL gates and filing the upstream issue
are [owner-run](#owner-run-and-pending) · **Found:** 2026-09-27, US-008 (review of the jslc test port:
the rule-level tests needed an end-of-input check, and the compiler has none)

## Story
As a developer who edits a `.jsl` shader, or who reviews such an edit,
I want the JSL compiler to fail with a syntax error on anything after the last complete declaration,
so that a stray brace or leftover text cannot silently drop functions, `main` included, from the generated HLSL, GLSL
and Metal shaders and Java peers while the generator exits 0.

## Problem
Problem and Proposed fix are the record of 2026-09-27: they describe the compiler before this story's change, and
[Implementation](#implementation-2026-11-14-macos) describes the change. Line numbers in Problem are those of that
date. Later commits moved some of them: US-018 moved `JSLC.java`'s, and `getParserInfo(InputStream)` is at `:103-108`
in the baseline `d803860b7e`.
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
- **Trailing input is rejected** (reworded 2026-11-14 after the code review; it asked for the full message). A new
  jslc test in `src/test/jslc/com/sun/scenario/effect/compiler` (the surefire include, `buildtools/jslc/pom.xml:118`,
  picks it up) calls `JSLC.getParserInfo(String)`, the production entry point, and asserts a
  `ParseCancellationException` whose message starts with the position and the cause,
  `line L:C extraneous input '}' expecting {<EOF>, `, for at least:
  - text after the last declaration;
  - a stray `}` between two declarations, which drops `main` at the baseline;
  - a stray `}` followed by a function that calls an undefined function.

  The rest of ANTLR's expected-token list is not pinned, because it changes with the token vocabulary.
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
- **Comments end where they should** (added 2026-11-14 after the code review).
  - A `//` comment ends at a line feed, a carriage return and line feed, a lone carriage return or the end of input,
    and its line end stays whitespace.
  - An unclosed `/*` is a syntax error at its opener, with the message `line L:C unterminated comment`.
  - jslc tests pin both. Each test of a behaviour the baseline did not have fails when that behaviour is removed
    (mutant proof, as above).
- **A rejected program writes nothing** (added 2026-11-14 after the code review). A jslc test compiles a program with
  trailing input through `JSLC.compile`, with every output type and every output out of date, and asserts the syntax
  error and that no file was written. A positive control compiles the valid program the same way and finds a file for
  every output type.
- **End to end.** The Decora generator on a copy of `Brightpass.jsl` with a stray `}` before `void main()` exits
  non-zero with the pinned message and writes no output for it. At the baseline (`d803860b7e`) it exits 0 and writes
  7 files.
- **Generated files unchanged.** Regenerate Decora and Prism with `-d3d -es2 -mtl` before and after the fix. All 1,059
  files are identical by name and md5. Run `CompileBoxBlur`, `CompileGaussian` and `CompileZoomRadialBlur` and parse
  `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl` after the fix too; they must still compile, although the
  build does not use them. US-021 (`backlog/US-021-remove-unused-decora-generator-inputs.md`) proposes deleting them;
  if it lands first, this part of the criterion goes.
- **Build gate,** with the two commands the US-008 gate ran, the Windows one with `-am` (added 2026-11-14 after the
  code review: without it the build takes `javafx-base` from the local repository):
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test -Djfx.parity.require=true`.
    With `-am`, the tests of `javafx.base` run too.
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
- All acceptance criteria met, with the per-class lists and `target/gensrc` md5 lists of both gate runs kept with
  the change (amended 2026-11-14 after the code review: the gate logs stay with the clones and are not kept).
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
- Neither option catches the code a glue block swallows: `GLUE_BLOCK` is greedy (`JSL.g4:487-489`), so a block runs
  to the last `>>` of the program, a second block's closer or a `>>` in a later JSL comment, and the code in between is
  lost. With a second block the parser still reaches EOF, and neither option reports anything. With a `>>` in a
  comment, either option reports an error, but at the token after that `>>`, inside the comment, not at the block.
  That is filed as US-020 (`backlog/US-020-stop-jsl-glue-blocks-swallowing-code.md`).
- `JSLC.compile` parses a shader only when one of its outputs is out of date (`JSLC.java:199-200` and the same test
  for the other outputs), or for Metal when US-018's check of the `.metal` file asks for it (`:222-224`). An output
  is out of date when it is not newer than the `.jsl` text (`:262-264`); the time of the JSL compiler does not count.
  A build without `clean` therefore reports trailing input, or an unclosed comment, when it regenerates the edited
  shader, as it does for every other syntax error. A shader that already had one is not parsed again by the new
  compiler until it is edited or the build is clean: the outputs the old compiler wrote stay. This is code-review
  finding 7. It is not changed here, because
  [US-024](US-024-regenerate-shaders-when-the-jsl-tools-change.md) covers it for every compiler change and names this
  story.
- The probe drivers, the instrumented compiler and the mutant runs were not kept in the repository. The Problem,
  Measured and Implementation sections describe each run fully enough to repeat it.

## Implementation (2026-11-14, macOS)
The change is described against `d803860b7e`, the baseline ("before"). Everything was built and run on one arm64 Mac:
macOS 27.2.1 (build 26B131), Apache Maven 3.10.0 and Oracle Corporation's OpenJDK 27.0.1.0.2 (build
27.0.1.0.2+1-2), as `sw_vers`, `mvn -version` and `java -version` report on that host. The gate logs print only that
the enforcer's `RequireJavaVersion` and `RequireMavenVersion` rules passed, and AppleClang 21.0.0.21000334 for the
native build. This host has no Windows and no Linux, so those gates are [owner-run](#owner-run-and-pending). Paths
are relative to `modules/javafx.graphics`, as in Problem.

A code review of the first version of the change returned 11 findings. This section describes the reworked change;
[Code review and rework](#code-review-and-rework-2026-11-14) gives each finding's disposition and the re-reviews.

Kept with the change: the macOS gate's per-class list, `backlog/US-019-macos-per-class.tsv`, and its
`target/gensrc` md5 list, `backlog/US-019-macos-gensrc.md5`. The probes, the mutant runs and the generator runs ran in
temporary scratch directories; they and their logs are not kept ([Notes](#notes)). The probes call
`JSLC.getParserInfo` on each case and print the declarations and the token where the parser stopped, or the error.

### Choice: option (a)
EOF in the grammar.
- It is the standard ANTLR idiom for a start rule.
- Every caller of `translation_unit()` gets the check, not only `JSLC.getParserInfo`.
- ANTLR writes the message, through `ThrowingErrorListener`, in the form of every other syntax error.
- `JSLC` and `JSLVisitor` do not change: the visitor reads `ctx.external_declaration()` only. `ThrowingErrorListener`
  changes for one message, the unterminated comment below; every other message is ANTLR's, as before.
- Option (b) covers only `getParserInfo` and needs a message of its own.
- The cost: the grammar differs from upstream's until upstream takes the same change, which the draft upstream issue
  proposes ([Upstream check](#upstream-check-2026-11-14)).

Two lexer changes go with it, because with EOF required a comment that does not lex as one is an error:
- **The standard line-comment rule.** `LINE_COMMENT : '//' ~('\n'|'\r')* -> channel(HIDDEN) ;` stops before the line
  end and leaves it to `WS` (`JSL.g4:470-471`), which hides `\r` and `\n`. So every line end ends a `//` comment: LF,
  CRLF, a lone CR, CR CR LF and the end of input. The rule of [Proposed fix](#proposed-fix), which the first version
  used, took the line end into the token and knew only LF, CRLF and the end of input. With EOF required it rejected
  a `//` comment in a file with CR-only line ends, one ended by CR CR LF, and a last line `// c` ended by a lone CR,
  with `extraneous input '/'` (code-review findings 1 and 2). The baseline accepted all three only by dropping text:
  the comment, and in the CR-only file everything after it, `main` included.
- **An unclosed comment is a token of its own.** `COMMENT` needs its `*/`, so an unclosed `/*` was lexed as code (`/`,
  `*` and so on). With EOF required, the first version reported it as `extraneous input '/'`, at a later token, or as
  a token recognition error at a character inside it, with the comment's text read as code (finding 3).
  `UNTERMINATED_COMMENT` matches a `/*` that no `*/` follows, up to the end of input. It is on the default channel
  and no parser rule uses it, so the parser reports it where it stands, at the opener. ANTLR's message would quote the
  whole rest of the file, so `ThrowingErrorListener` replaces it with `line L:C unterminated comment`.

### Change
Relative to `d803860b7e`, eight files, all under `src/jslc` and `src/test/jslc`:
- `src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4`:
  - `translation_unit : (e=external_declaration)+ EOF ;` (`:337-339`);
  - `UNTERMINATED_COMMENT : '/*' (~'*' | '*'+ ~('*'|'/'))* '*'* EOF ;` right after `COMMENT`, with a two-line comment
    that says why (`:477-481`);
  - `LINE_COMMENT : '//' ~('\n'|'\r')* -> channel(HIDDEN) ;` (`:483-485`);
  - the header years become `2008, 2026`. `GLUE_BLOCK` moves from `:481-483` to `:487-489`.
- `src/jslc/java/com/sun/scenario/effect/compiler/ThrowingErrorListener.java`: when the offending symbol is a token of
  type `JSLLexer.UNTERMINATED_COMMENT`, the message is `line L:C unterminated comment` (`:41-49`); every other message
  is unchanged. The header years become `2019, 2026`.
- `src/test/jslc/com/sun/scenario/effect/compiler/ProgramBase.java`, new, the base of the two program-level test
  classes. The valid program is `param float x;` and `void main() { color = float4(x); }`, one per line.
  `syntaxErrorIn` asserts a `ParseCancellationException` from `JSLC.getParserInfo(String)` and returns its message;
  `assertStartsWith` shows the whole message when it fails; `declarationsOf` asserts that the parse stopped at the end
  of input and lists the declarations by kind, qualifier, type and name (`VarDecl param float x`, `FuncDef void main`).
- `src/test/jslc/com/sun/scenario/effect/compiler/TranslationUnitTest.java`, new, 7 tests:
  - `textAfterTheLastDeclarationIsASyntaxError` (the valid program, then `} ) 42 garbage + + ;`),
    `aStrayBraceBetweenTwoDeclarationsIsASyntaxError` (a `}` line between the two declarations) and
    `aStrayBraceBeforeAFunctionThatCallsAnUndefinedFunctionIsReportedAtTheBrace` (the valid program, `}` and
    `void other() { undefinedCall(); }`) assert that the message starts with `line 3:0`, `line 2:0` and `line 3:0`
    followed by `extraneous input '}' expecting {<EOF>, `;
  - `aValidProgramKeepsAllItsDeclarations` and `aLineCommentWithoutANewlineCanEndTheProgram` (the valid program, then
    `// trailing comment` with no newline) assert both declarations;
  - `compilingAProgramWithAStrayBraceWritesNoFile` runs `JSLC.compile` with all five output types (`OUT_D3D`,
    `OUT_ES2`, `OUT_MTL`, `OUT_JAVA`, `OUT_PRISM`), the output and Metal header directories in a JUnit `@TempDir` and
    a source time of `Long.MAX_VALUE`, so that every output is out of date, on the program with the brace between the
    declarations. It asserts the `line 2:0` prefix and that no regular file exists under the temporary directory;
  - `compilingTheValidProgramWritesAFileForEveryOutputType`, its positive control, finds exactly `JSWProbePeer.java`,
    `PPSProbePeer.java`, `Probe.frag`, `Probe.hlsl` and `Probe.metal` there.
- `src/test/jslc/com/sun/scenario/effect/compiler/ProgramCommentTest.java`, new, 17 tests through
  `JSLC.getParserInfo`:
  - a `//` comment ended by LF, CRLF, a lone CR or CR CR LF can end the program, one test per line end; a lone CR ends
    one inside the program (`param float4 k;`, CR, `// note`, CR, `void main() { color = k; }`) and at its start
    (`// header`, CR, then the valid program); CRLF line ends with a comment between the declarations;
  - a program that is only `// note` gives a message starting with `line 1:7 mismatched input '<EOF>' expecting {`,
    and one that is only `/* note */` one starting with `line 1:10 mismatched input '<EOF>' expecting {`;
  - closed comments are ignored: two on one line, a multi-line one, `/**/`, `/***/`, `/* a **/`,
    `/* see // and >> here */` and one inside an expression;
  - 7 unclosed-comment tests, each with the exact message: after the last declaration (`line 3:0 unterminated
    comment`), between the declarations (`line 2:0 …`), first in the file with an `@` inside (`line 1:0 …`), in a
    function body after `float4(x)` with a `"` inside (`line 2:32 …`), `/*/` (`line 3:0 …`), with `//` and `>>`
    inside (`line 3:0 …`), and after closed comments (`line 4:0 …`).
- `src/test/jslc/com/sun/scenario/effect/compiler/lexer/CommentTest.java`, 7 tests (3 before): `/**/`; stars before
  the closer (`/***/`, `/* ignored **/`); the first `*/` ends a comment (`/* a */ /* b` starts with the `COMMENT`
  `/* a */`); an unclosed comment is not a `COMMENT`. The header years become `2008, 2026`.
- `src/test/jslc/com/sun/scenario/effect/compiler/lexer/LineCommentTest.java`, 5 tests (2 before). `comment`, whose
  token held the line feed, becomes three tests, one per line end (LF, CRLF, a lone CR), each asserting the token
  `// ignored` without its line end; `commentEndingAtEndOfInput` asserts `// ignored` at the end of input. The header
  years become `2008, 2026`.
- `src/test/jslc/com/sun/scenario/effect/compiler/lexer/UnterminatedCommentTest.java`, new, 8 tests: the token runs
  to the end of input across lines; `/*` alone, `/*/` and `/* unterminated **` are one; characters that cannot be
  lexed (`/* @ " # %`) and `//`, `<<` and `>>` are inside it; a closed comment and plain text are not one.

`JSLC`, `JSLVisitor`, `ParserBase`, the poms, the `.jsl` files and the generators do not change.
`ParserBase.assertAllInputConsumed` stays ([Notes](#notes)).

### Behaviour changes besides trailing input
Measured with the probes on the baseline's jslc jar and the final tree's, by the implementation and by the
re-reviews. The tests of [Change](#change) pin the first three.
- **Every line end ends a `//` comment,** and the comment token no longer holds its line end: `// ignored` followed
  by LF, CRLF, a lone CR or CR CR LF lexes as `// ignored`, then whitespace. Token positions after a comment do not
  change. Against the baseline:
  - a last line `// c` ended by a lone CR or by CR CR LF, and a last line `// trailing comment` without a newline:
    the baseline accepted them only by dropping them as trailing input (the parser stopped at `/`, 3:0); now the
    parse reaches the end of input;
  - a `//` comment in a file with CR-only line ends, `param float4 k;`, CR, `// note`, CR and
    `void main() { color = k; }`: the baseline lost `main` (1 declaration, stops at `/`, 1:16; ANTLR counts lines by
    `\n` only); now both declarations;
  - `// header`, a lone CR and the valid program: the baseline rejected it with
    `line 1:0 mismatched input '/' expecting {'const', …}`; now it is accepted.
- **A file that is only a comment** is rejected before and after: the `(...)+` loop needs a declaration. `// note`
  without a newline moves from `line 1:0 mismatched input '/' expecting {'const', …}` to
  `line 1:7 mismatched input '<EOF>' expecting {'const', …}` (`// x` alone: `line 1:4`), because the comment is now
  one hidden token. `/* note */` gives `line 1:10 mismatched input '<EOF>' …` before and after.
- **An unclosed `/*` is reported at its opener** as `line L:C unterminated comment`, wherever it stands, unless an
  earlier error is reported first: an `@` before it still gives `line 1:15 token recognition error at: '@'`, as it did
  before. The baseline lexed the comment as code:

  | Unclosed comment | Baseline | Final |
  | --- | --- | --- |
  | After the valid program: `/* unclosed comment`, `/*/`, `/* see // and >> here` or `/* a **` | 2 declarations, stops at `/` (3:0): the comment is dropped | `line 3:0 unterminated comment` |
  | `/* TODO` between the two declarations | 1 declaration, stops at `/` (2:0): `main` is dropped | `line 2:0 unterminated comment` |
  | An unclosed comment on line 4, after closed comments | 2 declarations, stops at `/` (4:0) | `line 4:0 unterminated comment` |
  | `/* @todo` first in the file | `line 1:0 mismatched input '/' expecting {'const', …}` | `line 1:0 unterminated comment` |
  | `void main() { color = float4(x) /* "q` | `line 2:35 token recognition error at: '"'` | `line 2:32 unterminated comment` |
  | `param float x` without `;`, then `/* x` | `line 2:0 mismatched input '/' expecting {',', ';'}` | `line 2:0 unterminated comment` |
  | After a `+` in `main` | `line 2:34 mismatched input '/' expecting {'+', …}` | `line 2:34 unterminated comment` |
  | In a parameter list, `float f(float a, /* b` | `line 1:17 mismatched input '/' expecting {'void', TYPE}` | `line 1:17 unterminated comment` |
  | `param /* x` | `line 1:6 no viable alternative at input 'param /'` | `line 1:6 unterminated comment` |

  The first version gave `extraneous input '/' expecting {<EOF>, …}` for the first three rows and the baseline's
  messages for the others.
- **An unclosed `/*` inside a closed glue block is still accepted** (3 declarations, stops at EOF, before and after):
  the glue block is one token of Java text from `<<` to `>>`. Glue-block lexing is US-020's
  ([Notes](#notes)).
- **Nothing else changes.** No program that the baseline accepted without dropping text is rejected or parses
  differently: 18 such rows of the first re-review's probe and 49 of the third's. The 23 cases of the story's own
  probe are under Parse results in [Tests](#tests) (22 rows; `// x` and `// note` share one). All 62 shipped `.jsl`
  files give the same results with the baseline's jar and the final tree's:
  - parsed alone, in the first re-review: 53 accepted, and the 9 `%` templates rejected;
  - compiled with all five output types, in the fourth: 52 compile, the 9 templates fail with the same
    `token recognition error at: '%'` and `PaintTextureYV12.jsl` with the same `Must use zero, one, or two samplers`,
    and the 264 generated files are identical.

### Acceptance criteria and evidence
| Criterion | Evidence | State |
| --- | --- | --- |
| Trailing input is rejected | The three trailing-input tests of `TranslationUnitTest` assert the prefixes `line 3:0`, `line 2:0` and `line 3:0 extraneous input '}' expecting {<EOF>, ` ([Tests](#tests)) | Met |
| Mutant proof | P5: a valid program, `@` in place of the stray `}`, and an unclosed function with an empty body; each of the 3 trailing-input tests fails under each, and the repository version passes ([Tests](#tests)). The first re-review's P5 also used an unclosed function with a statement in its body (`extraneous input '<EOF>'`): 4 failures. The re-reviews' mutants with their own inputs ([Code review and rework](#code-review-and-rework-2026-11-14)) | Met |
| Valid input is accepted | `aValidProgramKeepsAllItsDeclarations` and `aLineCommentWithoutANewlineCanEndTheProgram`. The second fails with the baseline's grammar (P1, at the end-of-input check of `declarationsOf`), and with the EOF change and the baseline's `LINE_COMMENT` (the first re-review's P3b) | Met |
| Comments end where they should (added) | `ProgramCommentTest`, `LineCommentTest`, `UnterminatedCommentTest` and `CommentTest` ([Change](#change)). The tests of what changed fail under P1, P3, P4a, P4b or P7, and the closed-comment tests under the third re-review's `'/*' .*? EOF` and `**/`-crossing variants ([Tests](#tests), [Code review and rework](#code-review-and-rework-2026-11-14)); the LF and CRLF tests guard what the baseline already accepted | Met |
| A rejected program writes nothing (added) | `compilingAProgramWithAStrayBraceWritesNoFile` and its positive control `compilingTheValidProgramWritesAFileForEveryOutputType`. The first fails with the valid program in place of the rejected one and with a file written before the parse, into the output or the Metal header directory; the second with the extra file in the Metal header directory ([Tests](#tests)) | Met |
| End to end | `Brightpass.jsl` with a stray `}` on line 35: exit 1, `line 35:0 extraneous input '}' expecting {<EOF>, …}`, no file written; the baseline exits 0 with 7 files (HLSL 2 lines, GLSL 16, Metal 13, JSW peer 154 against 218) ([Generator runs](#generator-runs)) | Met |
| Generated files unchanged | Outside Maven with `-d3d -es2 -mtl`, all 1,059 files identical by name and md5; `CompileBoxBlur`, `CompileGaussian` and `CompileZoomRadialBlur` exit 0 and their 141 files are unchanged; `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl` parse ([Generator runs](#generator-runs)). The 62 `.jsl` files give the same results ([Behaviour changes](#behaviour-changes-besides-trailing-input)). M2 on macOS ([Final gates](#final-gates-2026-11-14-macos)) | Met |
| Build gate | macOS: M1 and M2 ([Final gates](#final-gates-2026-11-14-macos)), extra evidence and not the criterion's commands. Windows (with `-am`) and WSL: [owner-run](#owner-run-and-pending) | Passed on macOS (extra evidence); Windows and WSL owner-run |
| Upstream | [Upstream check](#upstream-check-2026-11-14); the draft issue, kept outside the repository | Checked and drafted; filing pending |

### Tests
- **jslc.** `mvn -B -ntp -pl buildtools/jslc package` on the final tree runs 235 tests in 28 classes, with 0
  failures, 0 errors and 0 skipped, in the main tree after the last test change and again in the fourth re-review's
  clone; M1 gives the same. The baseline runs 196 in 25 (M1 and the generator runs). Per class (M1) the two are equal
  except `CommentTest` (3 to 7), `LineCommentTest` (2 to 5) and the new `ProgramCommentTest` (17),
  `TranslationUnitTest` (7) and `UnterminatedCommentTest` (8). `ProgramBase` is abstract and has no tests.
- **What is pinned.** The trailing-input tests and the compile test pin a prefix,
  `line L:C extraneous input '}' expecting {<EOF>, `, and so do the two comment-only tests
  (`line 1:7 mismatched input '<EOF>' expecting {` and `line 1:10 …`): the rest of the expected-token list changes
  with the token vocabulary (finding 4). The 7 unclosed-comment tests pin the whole message, `line L:C unterminated
  comment`. The positive tests pin the declarations and that the parse reached the end of input.
- **Mutant proofs,** each in a scratch clone with the eight files, run with `mvn -B -ntp -pl buildtools/jslc package`
  (the whole jslc suite) unless a filter is named. The repository version passes every test: 232 / 0 / 0 / 0
  before and after P2, P4, P5 and P7, and 235 / 0 / 0 / 0 before P1 and P3. P1 to P5 and P7 are the
  implementation's last runs: P1 and P3 on the final suite of 235 tests, P2, P4, P5 and P7 on the suite of 232,
  before one test was split into one per line end. The third re-review repeated P1 to P4 on 235 tests, with its own
  stubs and variants, and got the same failures. All of them ran before the compile test lost its last check, the
  working directory one ([Code review and rework](#code-review-and-rework-2026-11-14)); the fourth re-review ran P2,
  P3 and P4b again on the final tree with the same results, and the final tree passed 235 / 0 / 0 / 0 before and
  after its mutants.
  - **P1, the baseline's grammar** (with a `UNTERMINATED_COMMENT` rule that never matches, so the listener and the
    tests compile): 235 run, 27 failures and 1 error. The 3 trailing-input tests (`nothing was thrown`) and the
    compile test (an `IOException` instead); the 6 program tests whose `//` comment ends at the end of input or at a
    lone CR, or is the whole program (`parse stopped before the end of input`, or the baseline's message); the 7
    unclosed-comment tests; and 11 lexer tests (`CommentTest` 1, `LineCommentTest` 4, `UnterminatedCommentTest` 6).
    The LF and CRLF program tests pass: the baseline accepts those.
  - **P2, `translation_unit` without `EOF`:** 9 failures. The 3 trailing-input tests and the 5 unclosed-comment tests
    whose comment comes after or between declarations get `nothing was thrown`, the compile test an `IOException`.
  - **P3, the first version's `LINE_COMMENT`,** `'//' ~('\n'|'\r')* ('\r'? '\n' | EOF)`: 235 run, 3 failures and 4
    errors. The 4 program tests with a lone CR or CR CR LF error with
    `line 3:0 extraneous input '/' expecting {<EOF>, …}`, `line 1:16 …` or `line 1:0 mismatched input '/' …`, and
    the 3 token-text tests of `LineCommentTest` fail.
  - **P4a, no `UNTERMINATED_COMMENT` rule:** 14 failures, the 7 unclosed-comment tests (for example
    `expected: <line 3:0 unterminated comment> but was: <line 3:0 extraneous input '/' …>`) and the 7 lexer tests of
    unclosed comments. **P4b, no message translation in the listener:** the 7 unclosed-comment tests fail, their
    messages quoting the rest of the input.
  - **P5, other inputs in the 3 trailing-input tests and the compile test,** run with a filter on
    `TranslationUnitTest` (7 tests): 4 failures each, for a valid program (`nothing was thrown`), for `@` in place of
    the `}` (`… but was <line L:C token recognition error at: '@'>`) and for an unclosed function with an empty body
    (`… but was <line N:0 mismatched input '<EOF>' …>`).
  - **P7, `(external_declaration)* EOF`:** the two comment-only tests fail (`nothing was thrown`).
  - **The compile test,** final form, with `-Dtest='**/compiler/TranslationUnitTest'` (7 tests). The valid program in
    the negative test: 1 failure, `Expected org.antlr.v4.runtime.misc.ParseCancellationException to be thrown, but
    nothing was thrown.` A write of the D3D output at the top of `JSLC.compile`, before the parse: 1 failure,
    `expected: <[]> but was: <[Probe.hlsl]>`. A write into the Metal header directory there: 2 failures,
    `expected: <[]> but was: <[Stray.h]>` and the positive control, which lists the exact set of files.
- **Parse results,** by the story's probe, for the cases of [Measured](#measured-2026-09-27-windows-10-jdk-26) and the
  line-comment cases (`…` stands for the rest of the message):

  | Program | Baseline | Final |
  | --- | --- | --- |
  | The valid program | 2 declarations, stops at EOF | The same |
  | `} ) 42 garbage + + ;` after it | 2 declarations, stops at `}` (3:0) | `line 3:0 extraneous input '}' expecting {<EOF>, …}` |
  | `}` and `void other() { undefinedCall(); }` after it | 2 declarations, stops at `}` (3:0) | `line 3:0 extraneous input '}' …` |
  | `}` between `param` and `main` | 1 declaration, stops at `}` (2:0) | `line 2:0 extraneous input '}' …` |
  | `;` after it | 2 declarations, stops at `;` (3:0) | `line 3:0 extraneous input ';' …` |
  | `} @ #` after it | 2 declarations, stops at `}` (3:0) | `line 3:0 extraneous input '}' …` |
  | `// trailing comment` after it, no final newline | 2 declarations, stops at `/` (3:0) | 2 declarations, stops at EOF |
  | The same comment with a final newline | 2 declarations, stops at EOF | The same |
  | An unclosed `/*` comment after it | 2 declarations, stops at `/` (3:0) | `line 3:0 unterminated comment` |
  | `@` after it | `line 3:0 token recognition error at: '@'` | The same |
  | `undefinedCall();` inside `main` | `Unknown function undefinedCall()` | The same |
  | An empty program | `line 1:0 mismatched input '<EOF>' expecting {'const', …}` | The same |
  | `}` before the first declaration | `line 1:0 extraneous input '}' expecting {'const', …}` | The same |
  | Unclosed function, empty body | `line 3:0 mismatched input '<EOF>' …` | The same |
  | Unclosed function, a statement in the body | `line 4:0 extraneous input '<EOF>' …` | The same |
  | The valid program, then `void unclosed() {` | `line 4:0 mismatched input '<EOF>' …` | The same |
  | Only `// x`, or only `// note`, no newline | `line 1:0 mismatched input '/' expecting {'const', …}` | `line 1:4`, or `line 1:7`, `mismatched input '<EOF>' expecting {'const', …}` |
  | The valid program, then `// c` and a lone CR | 2 declarations, stops at `/` (3:0) | 2 declarations, stops at EOF |
  | `// header`, a lone CR, then the valid program | `line 1:0 mismatched input '/' expecting {'const', …}` | 2 declarations, stops at EOF |
  | `param float4 k;`, CR, `// note`, CR, `void main() { color = k; }` | 1 declaration, stops at `/` (1:16) | 2 declarations, stops at EOF |
  | `@` inside `main` | `line 2:33 token recognition error at: '@'` | The same |
  | `void main() { color = ; }` | `line 1:22 mismatched input ';' expecting {'+', …}` | The same |

  "The same" is byte-identical output.

### Generator runs
Run on 2026-11-14 on the final tree.
- **Setup.** Two clones: one of the baseline, and one with the change's eight files, each `cmp`-identical to the
  main tree's. The baseline clone was built with `mvn -B -ntp -pl modules/javafx.graphics -am test-compile
  -DskipNative=true`. Each side's jslc jar came from `mvn -B -ntp -pl buildtools/jslc package` in its clone: 196
  tests in 25 classes before, 235 in 28 after, no failure. Between the two sides, the only jslc main sources that
  differ are `JSL.g4` and `ThrowingErrorListener.java`; the templates and the `.jsl` inputs are identical.
- **Run as the pom runs them,** outside Maven, with the generators of the baseline clone's
  `target/jsl-compilers/{decora,prism}`. Classpath: graphics `target/classes`, base `target/classes`, the jslc jar,
  `org.antlr:antlr4:4.7.2` and its 6 dependencies as in `maven.compile.classpath`, `src/jslc/resources` and the
  generator classes, plus the Prism input directory for the Prism generator. The Decora JVM also gets the pom's
  `--module-path`, `--add-modules=javafx.graphics` and three `--add-exports`. The inputs are copies of
  `src/main/jsl-decora` and `src/main/jsl-prism`. The two sides differ only in the jslc jar. Every JVM ran in an empty
  working directory of its own.
- **Setup check.** The baseline clone's own Maven `target/gensrc` (797 files, macOS's `-mtl -es2`) is md5-identical to
  the same paths of the baseline regeneration below, and its md5 list is identical to M2's.
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

  | Case | Baseline | Final |
  | --- | --- | --- |
  | `Brightpass.jsl`, `}` inserted as line 35, before `void main()` | Exit 0, 7 files. `.hlsl` 13 lines become 2, `.frag` 24 become 16, `.metal` 25 become 13 (no fragment function; the helper ends in `return outFragColor;`), `JSWBrightpassPeer.java` 218 become 154; the new `.hlsl` and `.frag` have no `main`. `PPSBrightpassPeer.java` and both headers are unchanged | Exit 1, `line 35:0 extraneous input '}' expecting {<EOF>, 'const', 'param', 'lowp', 'mediump', 'highp', 'void', TYPE, GLUE_BLOCK}`, no file written |
  | The same input through `GenAllDecoraShaders` | Exit 0, 210 files (208 and 2 headers); exactly 4 differ from the unmodified regeneration's: `Brightpass.frag`, `.hlsl`, `.metal` and `JSWBrightpassPeer.java` | Exit 1, an `InvocationTargetException` caused by the same `line 35:0` message. It wrote the 5 outputs of `ColorAdjust`, each md5-identical to the regeneration's, and no header |
  | `ColorAdjust.jsl`, `}` inserted as line 87, after `rgb_to_hsb` | Exit 0, 7 files; `hsb_to_rgb` is in none of them. The `.hlsl` (117 lines become 37), `.frag` (128 become 51), `.metal` (131 become 49) and JSW peer (390 become 171) differ from the unmodified run's; the PPS peer and both headers do not | Exit 1, `line 87:0 extraneous input '}' …`, no file written |
  | `Brightpass.jsl`, `} ) 42 garbage + + ;` as line 43 | Exit 0, the 7 files byte-identical to those of the unmodified shader | Exit 1, `line 43:0 extraneous input '}' …`, no file written |
  | `PaintImagePattern.jsl` (Prism), `}` as line 43 | The 30 JVMs exit 0. 88 of the 848 files differ from the unmodified regeneration: the `.hlsl`, `.frag`, `.metal` and `_Loader.java` of the 22 `<mask>_ImagePattern` shaders, with and without `_AlphaTest`. All 44 of their `.hlsl` and `.frag` files lack `main`. In the 22 loaders the pixel coordinate flag goes from `true` to `false`, and the texture coordinate index goes from `1` to `-1` in 18 and from `0` to `-1` in the 2 `Texture_ImagePattern` loaders; the 2 `Solid_ImagePattern` loaders already had `-1` | The first JVM (`MaskAlphaOne.jsl`) exits 1 with `line 44:0 extraneous input '}' …`, thrown in `compilePatternPaint` for `Solid_ImagePattern`. It wrote the 4 files of `Solid_Color`, each md5-identical to the regeneration's, and no header |
  | `Brightpass.jsl`, `/* unclosed comment` as line 43 | Exit 0, the 7 files byte-identical to those of the unmodified shader: the comment is dropped | Exit 1, `line 43:0 unterminated comment`, no file written |
  | `Brightpass.jsl` with CR-only line ends | Exit 0, 7 files. The shaders and headers are byte-identical to the unmodified run's; both peers put the glue block's method on one line | The same: identical to the baseline's 7 files |
  | `Brightpass.jsl` with CRLF line ends | Exit 0, the 7 files byte-identical to those of the unmodified shader | The same |

  The peers' one-line method with CR-only line ends is the same before and after, so it does not come from this
  change.

### Final gates (2026-11-14, macOS)
Both runs were on the host above, in fresh clones, one after the other: before at `d803860b7e`, after at the baseline
plus the change's eight files, each `cmp`-identical to the main tree's (`git diff --stat d803860b7e` in that clone
lists only them, besides `backlog/`). Their md5 (`md5sum`) in the after clone:

| File | md5 |
| --- | --- |
| `src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4` | `6e7b1d33392dc77945578ee6f3c3fb47` |
| `src/jslc/java/com/sun/scenario/effect/compiler/ThrowingErrorListener.java` | `67c35e9f92ed408d1170a583fe322b5e` |
| `src/test/jslc/com/sun/scenario/effect/compiler/TranslationUnitTest.java` | `6bfd784cc94f41e28b82098ad9b143c0` |
| `src/test/jslc/com/sun/scenario/effect/compiler/ProgramBase.java` | `4dd2f31da8ec08e66720f7398c9d955a` |
| `src/test/jslc/com/sun/scenario/effect/compiler/ProgramCommentTest.java` | `f2236d59594abedbb86a50e7bb54ab5b` |
| `src/test/jslc/com/sun/scenario/effect/compiler/lexer/CommentTest.java` | `790e1c2f0943d064d2e4080b5231f128` |
| `src/test/jslc/com/sun/scenario/effect/compiler/lexer/LineCommentTest.java` | `079aacbb01c6865c43e2fa9cec81f50a` |
| `src/test/jslc/com/sun/scenario/effect/compiler/lexer/UnterminatedCommentTest.java` | `02a91602f2afdeb7b7b042751a2e7f03` |

They are extra evidence: the criterion's gates are Windows and WSL. Counts are run / failures / errors / skipped.

| Gate | Command | Result |
| --- | --- | --- |
| M1 module gate | `mvn -B -ntp -l <log> -Dmaven.test.failure.ignore=true -pl buildtools/jslc,modules/javafx.graphics -am clean test`, before and after | Both exit 0 with BUILD SUCCESS, and `jsl-codegen`, `cmake-configure` and `cmake-build` ran. jslc 196 / 0 / 0 / 0 in 25 classes before and 235 / 0 / 0 / 0 in 28 after; base 5,510 / 0 / 0 / 22 in 290 classes and graphics 26,696 / 0 / 0 / 858 in 629 in both. Of the 944 per-class lines before and 947 after, `diff` shows only `ProgramCommentTest 17 0 0 0`, `TranslationUnitTest 7 0 0 0` and `lexer.UnterminatedCommentTest 8 0 0 0` added, `lexer.CommentTest` going from `3 0 0 0` to `7 0 0 0` and `lexer.LineCommentTest` from `2 0 0 0` to `5 0 0 0` (per class). No class has a failure or an error. No `hs_err_pid*` file in either clone |
| M2 generated files | M1's `target/gensrc` | 797 files (`jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3), identical by name and md5 before and after (md5 list, the after run's; the before run's is `cmp`-identical). `DecoraShaderCommon.h` lists 50 shaders and `PrismShaderCommon.h` 212 |

The md5 list is the output of the command the gates document, `find . -type f | LC_ALL=C sort | xargs md5sum`, with
two spaces between hash and path. The first version's list had one (finding 10); its hashes and paths were the same.

M2's count differs from the US-008 gate's (Windows: 108, 424, 46 and 0 in `jsl-decora`, `jsl-prism`, `headers` and
`mtl-headers`) and from US-018's macOS F2 (817), for two reasons:
- macOS generates `-mtl -es2` (profile `jsl-backends-mac`, `pom.xml:641-652`), Windows `-d3d` (`jsl-backends-win`,
  `:609-620`) and Linux `-es2` (the default, `:48`). So `jsl-decora` holds a `.metal` and a `.frag` file for each of
  the 50 Decora shaders besides the 58 Java peers (158), and `jsl-prism` three files for each of the 212 Prism shaders
  (636).
- `9b02d6a602` (#57) removed the second-pass `javac -h`, so `target/gensrc/headers` no longer exists. US-018's F2 had
  20 files there: 817 is 797 and 20.

The jslc baseline is 196 tests in 25 classes.

### Independent review of the first version (2026-11-14)
This review is of the first version of the change, before the code review and the rework. Where it says "the
baseline", it compared with `d803860b7e`.
- **Verdict:** pass with nits; no blocker and no should-fix.
- **Mutant proofs with its own inputs** (Definition of Done). Each of the 3 negative tests fails with:
  - (a) another valid program, `const float k = 2.0;`, `float twice(float v) { return v * k; }` and
    `void main() { color = float4(twice(0.25)); }`;
  - (b) the lexer errors `%`, `#` and `"` in place of the stray `}`, each at the same line and column with
    `token recognition error`;
  - (c) other syntax errors (a missing `;`).
- **Each half of the grammar change alone.** G1, the baseline's `translation_unit` with the first version's
  `LINE_COMMENT`: the 3 negative tests fail and the rest pass. G2, the EOF change with the baseline's `LINE_COMMENT`:
  `commentEndingAtEndOfInput` fails and `aLineCommentWithoutANewlineCanEndTheProgram` errors with
  `line 3:0 extraneous input '/'`. G3, the baseline's grammar: 4 failures.
- **jslc per class** equal to the baseline's except `LineCommentTest`, 2 tests before and 3 after, and the new
  `TranslationUnitTest` with 5.
- **Probes** gave the first version's behaviour changes; its nit was the lone-CR case, which the code review raised
  again as findings 1 and 2. It repeated the end-to-end cases with identical results, and the graphics `test-compile`
  `target/gensrc` (797 files) was identical by name and md5.
- Its logs were temporary and are not kept.

### Code review and rework (2026-11-14)
A code review of the first version returned 11 findings:

| # | Finding | Disposition | Evidence |
| --- | --- | --- | --- |
| 1, 2 | `LINE_COMMENT` ended with `('\r'? '\n' \| EOF)`, so with EOF required a `//` comment in a file with lone-CR line ends, one ended by CR CR LF, or a last line `// c` ended by a CR was rejected with `extraneous input '/'`; the baseline accepted the last by dropping it | Fixed: `LINE_COMMENT` is the standard rule and leaves the line end to `WS` | [Choice](#choice-option-a), [Behaviour changes](#behaviour-changes-besides-trailing-input), P1 and P3 in [Tests](#tests) |
| 3 | An unclosed `/*` gave ANTLR's `extraneous input '/'`, or an error at a later token, with the comment's text lexed as code | Fixed: the lexer token `UNTERMINATED_COMMENT`, reported as `line L:C unterminated comment` at the opener | [Choice](#choice-option-a), [Behaviour changes](#behaviour-changes-besides-trailing-input), P4a and P4b |
| 4 | `TranslationUnitTest` compared ANTLR's whole expected-token list, so a change of the token vocabulary (US-020 will rework `GLUE_BLOCK`) breaks its three negative tests | Fixed: the tests assert the prefix `line L:C extraneous input '}' expecting {<EOF>, `; the criterion is reworded | [Acceptance criteria](#acceptance-criteria), [Tests](#tests) |
| 5 | No test covered the behaviour changes the story lists: a comment-only file, an unclosed comment, CR line ends | Fixed: `ProgramCommentTest`, `UnterminatedCommentTest`, and more `CommentTest` and `LineCommentTest` tests; a criterion added | [Change](#change), [Tests](#tests) |
| 6 | Nothing automated checked that a rejected program makes `JSLC.compile` fail and write no file | Fixed: `compilingAProgramWithAStrayBraceWritesNoFile` and its positive control; a criterion added | [Change](#change), [Tests](#tests) |
| 7 | Outputs the old compiler generated survive a build without `clean`, because the up-to-date check ignores the jslc jar | Not changed here: it is how the incremental build works for every compiler change, and US-024 covers it | [Notes](#notes) |
| 8 | The Windows gate commands left out `-am`, so in a fresh clone the build cannot resolve `javafx-base`, or uses a stale copy from the local repository | Fixed: `-am` in the gate commands and in the criterion's Windows command | [Acceptance criteria](#acceptance-criteria), [Windows gate](#windows-gate) |
| 9 | The upstream draft that the Definition of Done needs, and the probe and review logs, were only in the session's temporary scratch directory, which macOS clears | No evidence directory in the repository: the per-class and md5 lists are kept in `backlog/`, the probe and mutant logs are temporary and not kept, and the draft is kept outside the repository until it is filed. The [Definition of Done](#definition-of-done)'s first item is amended to match: the gate logs stay with the clones | [Implementation](#implementation-2026-11-14-macos), [Upstream check](#upstream-check-2026-11-14) |
| 10 | `US-019-macos-gensrc.md5` had one space between hash and path, not the two of the documented `md5sum` command | Fixed: the list is regenerated with the documented command | [Final gates](#final-gates-2026-11-14-macos) |
| 11 | `backlog/README.md`, the story and the per-class list's header said that the change was not committed yet and that the owner would commit it, but it was committed | Fixed: no wording about commit state, which goes stale; `d803860b7e` is called the baseline, not HEAD | The status line, the README row, the header of `US-019-macos-per-class.tsv` |

The rework was re-reviewed four times. Each re-review was a second, independent instance of the repository's Java
agent (`.claude/agents/java-27.agent.md`), because the repository has no reviewer agent for jslc; each ran its own
probes and mutants in its own clone:
- **Round 1: changes required.** One should-fix: `ProgramBase.declarationsOf` did not assert that the parse reached
  the end of input, so the end-of-input comment tests passed under the baseline's grammar, which drops such a comment
  as trailing input. One nit: two lexer tests asserted the token type before its text, so a failure printed bare type
  numbers. Its own evidence: a probe of 57 cases and 8 large inputs (up to 2 million characters or 100,000 comments,
  each under 5 s) on the baseline, the first version and the rework; the 18 rows the baseline accepted in full are
  unchanged; the 62 `.jsl` files give identical results; P1 to P6 with its own variants (P3b, the EOF change with the
  baseline's `LINE_COMMENT`, fails `aLineCommentWithoutANewlineCanEndTheProgram`); the graphics `target/gensrc` (797
  files) identical. The fix: `declarationsOf` asserts the end of input, and the lexer tests check the text first.
- **Round 2: pass, one nit.** One test fed four line ends through one `assertAll`, and a failure did not say which. Its
  own evidence: a probe of 176 rows, in which no program that the baseline parsed to the end of input changed; 11
  mutants of the rules and the listener, other inputs for P5 and three mutants of the compile test; an unclosed
  comment of 3.2 MB reported in 45 ms. The fix: one test per line end (232 tests then, 235 after).
- **Round 3: pass, no finding.** Its own evidence: a probe of 158 rows, in which the 49 programs the baseline accepted
  in full are unchanged; 14 grammar and listener mutants, among them an `UNTERMINATED_COMMENT` that crosses `**/`
  (caught by `CommentTest.starsBeforeTheCloserBelongToTheComment`); P5 with `#`, `)`, other unclosed declarations
  and the brace hidden in a comment (4 failures each); the graphics `target/gensrc` identical.
- **Then the compile test was simplified.** Its last check, that nothing in the JVM's working directory changed, was
  removed. With the output and Metal header directories set, every file `JSLC.compile` can write lands in the
  temporary directory (`JSLC.write`, `writeIfChanged` and `MetalHeaders.write`, traced), so the no-file check
  covers it, while the walk depended on where the JVM was started. Its mutants are the compile-test ones in
  [Tests](#tests).
- **Round 4: pass, one nit.** The no-file check lists regular files only, so a run that only creates an empty
  directory would pass; no code path creates a directory without writing a file in it, so it stays. Its own
  evidence: its own trace of every file write in `src/jslc/java`; the three compile-test mutants with its own writes
  (`Probe.frag`, `MTLShaderCommon.h`); P2 (4 of 7 `TranslationUnitTest` and 5 of 17 `ProgramCommentTest` tests
  fail), the listener without its translation (7 failures) and the first version's `LINE_COMMENT` (3 failures and 4
  errors) on the full suite; and the 62 `.jsl` files compiled with the baseline's jar and the final one
  ([Behaviour changes](#behaviour-changes-besides-trailing-input)).

The Definition of Done's "An independent review repeated the mutant proofs with its own inputs" is met for the final
tree by round 4, and by round 3 for the tree before the compile test's simplification, which changed that test
only.

### Upstream check (2026-11-14)
- **JBS.** The five searches of [Upstream too](#upstream-too) give the same counts as on 2026-09-27: 17, 2, 0, 1
  (JDK-8281422) and 0. No issue reports this. JDK-8226635 is still Open.
- **openjdk/jfx master.** At `d0e72f9d86` (2026-11-13), `JSL.g4` and `ThrowingErrorListener.java` are byte-identical
  to the baseline's, and `getParserInfo` is the same (`JSLC.java:110-115` there).
- **Draft.** The upstream issue is drafted with the `Brightpass.jsl` repro, the outputs without `main` and the final
  change: `EOF` in `translation_unit`, the standard `LINE_COMMENT` rule, the `UNTERMINATED_COMMENT` token with its
  `unterminated comment` message, and the tests and what they pin. It says that upstream runs no jslc test
  (JDK-8226635), so the fix's tests need that port too. The draft is kept outside the repository until it is filed
  (`backlog/README.md`).

### Owner-run and pending
Replace `<repo-url>` with the repository's remote and `<commit>` with the owner's US-019 commit. Before is
`d803860b7e`. If `<commit>` is only on the PR branch, fetch it first with
`git -C jfx-after fetch -q origin pull/<PR>/head`. In PowerShell, quote every `-D` argument; under Git Bash, Maven
3.10's `bin/mvn` converts the paths. Each gate keeps its logs, its per-class lists (class, run, failures, errors,
skipped, separated by tabs) and its `target/gensrc` md5 lists as `us019-<os>-<before|after>…` in the directory that
holds the clones. The check commands ran as written in bash on the macOS host, with `windows` replaced by `macos`, on
M1's clones and logs, and printed the per-class differences and jslc totals expected below, with macOS's skip counts
and `gensrc` directories.

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
mvn -B -ntp -l ..\us019-windows-before.log -pl $pl -am clean test "-Djfx.parity.require=true"
"before exit $LASTEXITCODE"; cd ..
cd jfx-after
mvn -B -ntp -l ..\us019-windows-after.log -pl $pl -am clean test "-Djfx.parity.require=true"
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
- from `diff`, no other lines than these (with tabs between the fields), besides the hunk lines:
  - `> com.sun.scenario.effect.compiler.ProgramCommentTest 17 0 0 0`
  - `> com.sun.scenario.effect.compiler.TranslationUnitTest 7 0 0 0`
  - `< com.sun.scenario.effect.compiler.lexer.CommentTest 3 0 0 0`
  - `> com.sun.scenario.effect.compiler.lexer.CommentTest 7 0 0 0`
  - `< com.sun.scenario.effect.compiler.lexer.LineCommentTest 2 0 0 0`
  - `> com.sun.scenario.effect.compiler.lexer.LineCommentTest 5 0 0 0`
  - `> com.sun.scenario.effect.compiler.lexer.UnterminatedCommentTest 8 0 0 0`
- jslc `Tests run: 196, Failures: 0, Errors: 0, Skipped: 3` before and
  `Tests run: 235, Failures: 0, Errors: 0, Skipped: 3` after (the three `link/..` tests of `MetalHeadersTest` are
  disabled on Windows); the same javafx.base line in both runs and the same javafx.graphics line in both runs, each
  with `Failures: 0, Errors: 0`;
- `gensrc identical by name and md5`, with `108 jsl-decora` and `424 jsl-prism` and no other directory: Windows
  generates `-d3d` (`pom.xml:609-620`), so 50 `.hlsl` files and the 58 Java peers in `jsl-decora` and 212 `.hlsl`
  files and 212 loaders in `jsl-prism`; without `-mtl` the generators write no Metal header, and `headers` is gone
  since `9b02d6a602`;
- nothing from `find`.

Both runs use `-am`, as the WSL gate does (code-review finding 8). Without it, Maven takes
`org.openjfx:javafx-base:28-ea` and its `tests` jar from the local repository: in a fresh clone with nothing installed
the build stops with `Could not resolve dependencies`, and after an earlier `mvn install` it builds against whatever
was installed last. With `-am`, javafx.base is built in the same reactor and its tests run too, which is why the
checks print a javafx.base line.

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
the Windows gate shows, with two differences:
- jslc `Tests run: 196, Failures: 0, Errors: 0, Skipped: 0` before and
  `Tests run: 235, Failures: 0, Errors: 0, Skipped: 0` after: Linux runs the three `link/..` tests;
- `108 jsl-decora` and `424 jsl-prism` from `-es2` (`pom.xml:48`): 50 `.frag` files and the 58 Java peers, and 212
  `.frag` files and 212 loaders.

The javafx.base line is expected to be `Tests run: 5510, Failures: 0, Errors: 0, Skipped: 22` in both runs of both
gates: US-018's CI measured 5,510 / 0 / 0 / 22 on Windows x64 and on Linux
([US-018, Before the merge](US-018-fix-jslc-metal-header-path-and-static-state.md#before-the-merge-2026-11-07)).

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
