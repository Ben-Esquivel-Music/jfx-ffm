# US-022 — Generator cases and gates on macOS

Evidence for [US-022](US-022-report-jsl-errors-at-the-source-file-line.md): the generator error cases before and after
the change, the regeneration with `-d3d -es2 -mtl` outside Maven, and the macOS module gate. The lists kept with it:
[per class](US-022-macos-per-class.tsv), [`target/gensrc`](US-022-macos-gensrc.md5) and
[regeneration](US-022-regeneration.md5).

## Host and trees
- macOS 27.2.1 (build 26B131) arm64, Oracle OpenJDK 27.0.1.0.2 (build 27.0.1.0.2+1-2), Apache Maven 3.10.0. Times are
  America/New_York.
- Before: a fresh clone detached at `726d7f74a5`, built by its module gate on 2026-11-16 (21:38 to 21:39). Its
  regeneration and cases ran from 21:41 to 21:43. G02's before run was on 2026-11-17 at 00:08 and J01g's at 00:39,
  with the same clone's classes.
- After: a fresh clone detached at `726d7f74a5`, with the main tree's uncommitted US-022 change applied
  (`git diff 726d7f74a5` and its 9 untracked files). Its `git status --short` listed the same 34 entries as the main
  tree's then, and each of the 34 files was `cmp`-identical to the main tree's. Its module gate on 2026-11-17 (00:03 to
  00:05) built the classes of the regeneration, at 00:06, and of the cases, from 00:07 to 00:08.
- After, second clone: made the same way on 2026-11-17 at 00:36, once three test pins had been added to
  `SourceLocationTest.java`, with 13 untracked files (the 4 more are this file and its three lists). Its 38 entries
  were then `cmp`-identical to the main tree's. Among its sources it differs from the first clone only in
  `SourceLocationTest.java`, so its build's jslc classes (156) and generator classes (15) are `cmp`-identical to the
  first clone's, and the regeneration and the cases were not rerun. Module gate on 2026-11-17 from 00:37 to 00:38,
  J01g at 00:39.
- The change edits 24 Java files and adds 7; every other file it edits or adds is under `backlog/`. None is a `.jsl`,
  `.stg`, pom or resource, or a source under `src/main/java` of `javafx.base` or `javafx.graphics`. The md5 of its 31
  Java files in the second after clone (paths under `modules/javafx.graphics`; in the first clone
  `SourceLocationTest.java` had `f4616d1c230d14d6703a36566f1f1441`, every other file the same md5):

  | md5 | File |
  | --- | --- |
  | `b4592aee70570776b204243ce43b50ca` | `src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java` |
  | `65093c281cae2e7228c5da4e0d1c26d6` | `src/jslc/java/com/sun/scenario/effect/compiler/JSLSource.java` |
  | `86262182c3fb78f865c485fa8ad0abb4` | `src/jslc/java/com/sun/scenario/effect/compiler/JSLSourceException.java` |
  | `9fbc3c500948799df23c52fe931982c3` | `src/jslc/java/com/sun/scenario/effect/compiler/ThrowingErrorListener.java` |
  | `02221354fb42035ac1efabe134b96718` | `src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/SLBackend.java` |
  | `0d37d81e29d903b9d5aef113c8fbba22` | `src/jslc/java/com/sun/scenario/effect/compiler/backend/prism/PrismBackend.java` |
  | `c717460ed3b45d49ee6b90731ae33493` | `src/jslc/java/com/sun/scenario/effect/compiler/backend/sw/java/JSWCallScanner.java` |
  | `f21b3782fd3f6d578170e0aecb70d614` | `src/jslc/java/com/sun/scenario/effect/compiler/backend/sw/java/JSWTreeScanner.java` |
  | `8f3dfe09d8a17078eab52ce5ea73f5a4` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/ArrayAccessExpr.java` |
  | `be97a6df4a0812a856315524c0b4b4da` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/CallExpr.java` |
  | `1a33707608a59da17931b8bb8c2687c5` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/ForStmt.java` |
  | `54bc73274e08dac05d62fb094b4fd49a` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/GlueBlock.java` |
  | `0537421d7ef342b036495902240c1771` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/JSLVisitor.java` |
  | `356d09cded2941d4795e146adaa4a6e9` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/ReturnStmt.java` |
  | `da7a79df07605a24097d9e64f9cb5247` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/SourcePosition.java` |
  | `4fedd540e8c107ffee745ea3be4e9b23` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/TreeMaker.java` |
  | `5cd3470e7561df22ad4f3fc9113451f4` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/VarDecl.java` |
  | `ee36bb5daac85e03c26f8eda42af5adb` | `src/jslc/java/com/sun/scenario/effect/compiler/tree/VariableExpr.java` |
  | `5a501c56d643ce8ecb538a7f22a3ab64` | `src/main/jsl-decora/CompileBlend.java` |
  | `ed73cf85bf4b05a728d86e6300eed8d2` | `src/main/jsl-decora/CompileJSL.java` |
  | `52f901950361c5888c8ad8375d3a2a11` | `src/main/jsl-decora/CompileLinearConvolve.java` |
  | `97af02a1c1d21601509f2e38828b8a35` | `src/main/jsl-decora/CompilePhong.java` |
  | `018a7f35668b21398701b4771853e3d8` | `src/main/jsl-decora/GenAllDecoraShaders.java` |
  | `b35126f0b2be822678495a11360a276c` | `src/main/jsl-prism/CompileJSL.java` |
  | `e6ff4d6c8e468454a3d113bf443df7fc` | `src/test/jslc/com/sun/scenario/effect/compiler/JSLSourceTest.java` |
  | `c69afc526ceefa232c4243a7ad90f4ab` | `src/test/jslc/com/sun/scenario/effect/compiler/JavaPeerLimitTest.java` |
  | `a952c3a2cadedc985dc9a2a3e2463a20` | `src/test/jslc/com/sun/scenario/effect/compiler/PrismProgramTest.java` |
  | `6493038f585eb4f76b1faa761b318d5f` | `src/test/jslc/com/sun/scenario/effect/compiler/SourceFileClosedTest.java` |
  | `a4883c131fbabacf3f4dd5bdae325f6e` | `src/test/jslc/com/sun/scenario/effect/compiler/SourceLocationTest.java` |
  | `e80af5327ba55020a9871e21eeb1eda7` | `src/test/jslc/com/sun/scenario/effect/compiler/SymbolTest.java` |
  | `15ea122899d86bb6e89e621d36c82f0a` | `src/test/jslc/com/sun/scenario/effect/compiler/parser/AssignmentExprTest.java` |

## Generator cases
PCE is `org.antlr.v4.runtime.misc.ParseCancellationException`, JSE
`com.sun.scenario.effect.compiler.JSLSourceException`, RE `java.lang.RuntimeException` and IE `java.lang.InternalError`.
The table cuts ANTLR's expected-token list after `expecting`; the [full first lines](#first-exception-lines-after-side)
follow it. Line numbers in "Edit" are those of the edited file. "Files" counts the files a run writes.

| Id | Edit | Before: message | After: first line | Exit before / after | Files before / after |
| --- | --- | --- | --- | --- | --- |
| S01 | `Brightpass.jsl`, new line 39 `    )` | PCE `line 39:4 extraneous input ')' expecting …` | PCE `Brightpass.jsl:39:4: in program Brightpass: extraneous input ')' expecting …` | 1 / 1 | 0 / 0 |
| S01g | As S01, through `GenAllDecoraShaders` | `java.lang.reflect.InvocationTargetException`, `Caused by:` PCE `line 39:4 extraneous input ')' expecting …` | PCE `Brightpass.jsl:39:4: in program Brightpass: extraneous input ')' expecting …` | 1 / 1 | 5 / 5 |
| S02 | `LinearConvolve.jsl`, new line 75 `    )` | PCE `line 75:4 extraneous input ')' expecting …` | PCE `LinearConvolve.jsl:75:4: in program LinearConvolve_4: extraneous input ')' expecting …` | 1 / 1 | 0 / 0 |
| S03 | `Blend_ADD.jsl`, new line 76 `    )` | PCE `line 147:4 extraneous input ')' expecting …` | PCE `Blend_ADD.jsl:76:4: in program Blend_ADD: extraneous input ')' expecting …` | 1 / 1 | 20 / 20 |
| S04 | `Blend.jsl`, new line 77 `    )` | PCE `line 106:4 extraneous input ')' expecting …` | PCE `Blend.jsl:77:4: in program Blend_SRC_OVER: extraneous input ')' expecting …` | 1 / 1 | 0 / 0 |
| S05 | `PhongLighting.jsl`, new line 127 `    )` | PCE `line 128:4 extraneous input ')' expecting …` | PCE `PhongLighting.jsl:127:4: in program PhongLighting_DISTANT: extraneous input ')' expecting …` | 1 / 1 | 0 / 0 |
| S06 | `PhongLighting.jsl`, new line 142 `    )` | PCE `line 144:4 extraneous input ')' expecting …` | PCE `PhongLighting.jsl:142:4: in program PhongLighting_DISTANT: extraneous input ')' expecting …` | 1 / 1 | 0 / 0 |
| S07 | `PaintImagePattern.jsl`, new line 35 `    )` | PCE `line 36:4 extraneous input ')' expecting …` | PCE `PaintImagePattern.jsl:35:4: in program Solid_ImagePattern: extraneous input ')' expecting …` | 1 / 1 | 4 / 4 |
| S08 | `MaskFillPgram.jsl`, new line 101 `    )` | PCE `line 101:4 extraneous input ')' expecting …` | PCE `MaskFillPgram.jsl:101:4: in program FillPgram_Color: extraneous input ')' expecting …` | 1 / 1 | 64 / 64 |
| S09 | `PaintMultiGradient.jsl`, new line 47 `    )` | PCE `line 48:4 extraneous input ')' expecting …` | PCE `PaintMultiGradient.jsl:47:4: in program Solid_LinearGradient_PAD: extraneous input ')' expecting …` | 1 / 1 | 8 / 8 |
| S10 | `PaintLinearGradient.jsl`, new line 44 `    )` | PCE `line 125:4 extraneous input ')' expecting …` | PCE `PaintLinearGradient.jsl:44:4: in program Solid_LinearGradient_PAD: extraneous input ')' expecting …` | 1 / 1 | 8 / 8 |
| S11 | `PaintImagePattern.jsl`, new last line 43 `}` | PCE `line 44:0 extraneous input '}' expecting …` | PCE `PaintImagePattern.jsl:43:0: in program Solid_ImagePattern: extraneous input '}' expecting …` | 1 / 1 | 4 / 4 |
| M01 | `Brightpass.jsl`, new line 39 `    float u = undefinedVar;` | RE `Unknown variable undefinedVar` | JSE `Brightpass.jsl:39:14: in program Brightpass: Unknown variable undefinedVar` | 1 / 1 | 0 / 0 |
| M02 | `Blend_ADD.jsl`, new line 76 `    float u = undefinedVar;` | RE `Unknown variable undefinedVar` | JSE `Blend_ADD.jsl:76:14: in program Blend_ADD: Unknown variable undefinedVar` | 1 / 1 | 20 / 20 |
| M03 | `PaintImagePattern.jsl`, new line 35 `    float v = undefinedFunc(1.0);` | RE `Unknown function undefinedFunc(float)` | JSE `PaintImagePattern.jsl:35:14: in program Solid_ImagePattern: Unknown function undefinedFunc(float)` | 1 / 1 | 4 / 4 |
| M04 | `LinearConvolve.jsl:75`, `i < count` changed to `i <= count` | RE `Condition must be '<' in order to unroll 'for' loop (for now)` | JSE `LinearConvolve.jsl:75:4: in program LinearConvolve_4: Condition must be '<' in order to unroll 'for' loop (for now)` | 1 / 1 | 0 / 0 |
| J01 | `Brightpass.jsl`: `float halve(float v)`, `{`, `    return v * 0.5;`, `}` and an empty line inserted after line 34; line 46 (was 41) `    color = val * sign(halve(luminance));` | IE `Nested function calls not yet supported` | JSE `Brightpass.jsl:46:23: in program Brightpass: nested function calls are not supported by the Java software peer (-java)` | 1 / 1; J01s 0 / 0 | 3 / 3; J01s 5 / 5 |
| J01g | As J01, through `GenAllDecoraShaders` | `java.lang.reflect.InvocationTargetException`, `Caused by:` IE `Nested function calls not yet supported` | JSE `Brightpass.jsl:46:23: in program Brightpass: nested function calls are not supported by the Java software peer (-java)` | 1 / 1 | 8 / 8 |
| J02 | `Brightpass.jsl`, line 41 changed to `    color = val * sign(abs(luminance));` | IE `Nested function calls not yet supported` | JSE `Brightpass.jsl:41:23: in program Brightpass: nested function calls are not supported by the Java software peer (-java)` | 1 / 1; J02s 0 / 0 | 3 / 3; J02s 5 / 5 |
| J03 | `Brightpass.jsl`, new line 34 `param float4 weights[2];` and new line 41 `    float w = weights[0].x;` | IE `Array access only supports variable expr/index (for now)` | JSE `Brightpass.jsl:41:14: in program Brightpass: array access other than variable[variable] is not supported by the Java software peer (-java)` | 1 / 1; J03s 0 / 0 | 3 / 3; J03s 5 / 5 |
| J04 | `Brightpass.jsl`, new line 34 `param float4 weights[2];` and new line 41 `    float w = abs(weights[0].x);` | IE `Array access only supports variable expr/index (for now)` | JSE `Brightpass.jsl:41:18: in program Brightpass: array access other than variable[variable] is not supported by the Java software peer (-java)` | 1 / 1; J04s 0 / 0 | 3 / 3; J04s 5 / 5 |
| J05 | `Brightpass.jsl`, new line 41 `    if (luminance < 0.0) return;` | IE `Empty return not yet implemented` | JSE `Brightpass.jsl:41:25: in program Brightpass: return without a value is not supported by the Java software peer (-java)` | 1 / 1; J05s 0 / 0 | 3 / 3; J05s 5 / 5 |
| J06 | `Brightpass.jsl`, new line 41 `    if (abs(val) == val) luminance = 0.0;` | IE `TBD` | JSE `Brightpass.jsl:41:8: in program Brightpass: the Java software peer (-java) does not support the vector result of abs(...) here: it cannot tell which component to use` | 1 / 1; J06s 0 / 0 | 3 / 3; J06s 5 / 5 |
| J07 | `Brightpass.jsl`, new line 41 `    if (val == val) luminance = 0.0;` | IE `TBD` | JSE `Brightpass.jsl:41:8: in program Brightpass: the Java software peer (-java) does not support vector 'val' here: it cannot tell which component to use` | 1 / 1; J07s 0 / 0 | 3 / 3; J07s 5 / 5 |
| J08 | `Brightpass.jsl`, new line 41 `    luminance = abs((val.x + val).y);` | IE `TBD` | JSE `Brightpass.jsl:41:29: in program Brightpass: the Java software peer (-java) does not support vector 'val' here: it cannot tell which component to use` | 1 / 1; J08s 0 / 0 | 3 / 3; J08s 5 / 5 |
| G01 | `CompilePhong.java` (`:48` before, `:49` after): the string `"param float3 lightPosition;"` becomes `"param float3 lightPosition"` | PCE `line 126:0 mismatched input 'void' expecting {',', ';'}` | PCE `PhongLighting.jsl:124:0: in program PhongLighting_POINT: mismatched input 'void' expecting {',', ';'}` | 1 / 1 | 5 / 5 |
| G02 | `CompilePhong.java`, the same line: the string becomes `"param flaot3 lightPosition;"` | PCE `line 123:6 no viable alternative at input 'param flaot3'` | PCE `<CompilePhong declPosVar>:2:6: in program PhongLighting_POINT: no viable alternative at input 'param flaot3'` | 1 / 1 | 5 / 5 |

- The exit codes are the same on both sides in all 35 runs. So is each run's list of written files with their md5
  (`files.md5`, byte-identical between the sides).
- Each file a failing run writes is md5-identical to the file of the same path in the regeneration, on both sides:
  S01g 5 of 5, S03 20 of 20, S07 4 of 4, S08 64 of 64, S09 8 of 8, S10 8 of 8, S11 4 of 4, M02 20 of 20, M03 4 of 4,
  G01 5 of 5 and G02 5 of 5.
- Each J0n writes `Brightpass.frag`, `Brightpass.hlsl` and `Brightpass.metal`, md5-identical to those of its J0ns run
  (3 of 3 in all 8). Each J0ns prints nothing and writes those 3 files and the 2 Metal headers.
- J01g writes, on both sides, the 5 files of `ColorAdjust`, which `GenAllDecoraShaders` compiles before `Brightpass`
  (md5-identical to S01g's and to the regeneration's: 5 of 8), and then the 3 `Brightpass` shaders, md5-identical to
  those of J01 and J01s (3 of 3). Neither side writes a Metal header or a `Brightpass` peer.
- After the location, the after side's message is byte-identical to the before side's. In the 13 syntax runs (S01 to
  S11, G01, G02) it equals the text after `line L:C `, ANTLR's expected-token list included. In M01 to M04 it equals the
  whole message.
- The ten syntax cases of the story's acceptance criteria name `Brightpass.jsl:39:4`, `LinearConvolve.jsl:75:4`,
  `Blend_ADD.jsl:76:4`, `Blend.jsl:77:4`, `PhongLighting.jsl:127:4`, `PhongLighting.jsl:142:4`,
  `PaintImagePattern.jsl:35:4`, `MaskFillPgram.jsl:101:4`, `PaintMultiGradient.jsl:47:4` and
  `PaintLinearGradient.jsl:44:4`, each with the program being compiled.
- G01's offending token is the `void` of `void main()` at `PhongLighting.jsl:124:0`, the first token after the
  generator text. G02's is `flaot3`, inside the generator text, on line 2 of `<CompilePhong declPosVar>`.
- After the change, S01g and J01g print no `InvocationTargetException`; J01g's first line is J01's. M01 to M03 also
  print `Caused by:` with a `java.lang.RuntimeException` that has the same detail.
- Every JVM ran in an empty working directory of its own, which stayed empty, and wrote into a fresh, empty output
  root.

## First exception lines, after side
The `Exception in thread` and `Caused by:` lines of each run's output. The J0ns runs print nothing.
```
S01   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: Brightpass.jsl:39:4: in program Brightpass: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S01g  Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: Brightpass.jsl:39:4: in program Brightpass: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S02   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: LinearConvolve.jsl:75:4: in program LinearConvolve_4: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S03   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: Blend_ADD.jsl:76:4: in program Blend_ADD: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S04   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: Blend.jsl:77:4: in program Blend_SRC_OVER: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S05   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: PhongLighting.jsl:127:4: in program PhongLighting_DISTANT: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S06   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: PhongLighting.jsl:142:4: in program PhongLighting_DISTANT: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S07   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: PaintImagePattern.jsl:35:4: in program Solid_ImagePattern: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S08   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: MaskFillPgram.jsl:101:4: in program FillPgram_Color: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S09   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: PaintMultiGradient.jsl:47:4: in program Solid_LinearGradient_PAD: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S10   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: PaintLinearGradient.jsl:44:4: in program Solid_LinearGradient_PAD: extraneous input ')' expecting {'const', 'param', 'lowp', 'mediump', 'highp', '+', '-', '++', '--', '(', '{', '}', '!', ';', 'if', 'while', 'do', 'for', 'unroll', 'continue', 'break', 'discard', 'return', 'void', TYPE, BOOLCONSTANT, IDENTIFIER, INTCONSTANT, FLOATCONSTANT}
S11   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: PaintImagePattern.jsl:43:0: in program Solid_ImagePattern: extraneous input '}' expecting {<EOF>, 'const', 'param', 'lowp', 'mediump', 'highp', 'void', TYPE, GLUE_BLOCK}
M01   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:39:14: in program Brightpass: Unknown variable undefinedVar
M01   Caused by: java.lang.RuntimeException: Unknown variable undefinedVar
M02   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Blend_ADD.jsl:76:14: in program Blend_ADD: Unknown variable undefinedVar
M02   Caused by: java.lang.RuntimeException: Unknown variable undefinedVar
M03   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: PaintImagePattern.jsl:35:14: in program Solid_ImagePattern: Unknown function undefinedFunc(float)
M03   Caused by: java.lang.RuntimeException: Unknown function undefinedFunc(float)
M04   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: LinearConvolve.jsl:75:4: in program LinearConvolve_4: Condition must be '<' in order to unroll 'for' loop (for now)
J01   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:46:23: in program Brightpass: nested function calls are not supported by the Java software peer (-java)
J01g  Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:46:23: in program Brightpass: nested function calls are not supported by the Java software peer (-java)
J02   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:41:23: in program Brightpass: nested function calls are not supported by the Java software peer (-java)
J03   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:41:14: in program Brightpass: array access other than variable[variable] is not supported by the Java software peer (-java)
J04   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:41:18: in program Brightpass: array access other than variable[variable] is not supported by the Java software peer (-java)
J05   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:41:25: in program Brightpass: return without a value is not supported by the Java software peer (-java)
J06   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:41:8: in program Brightpass: the Java software peer (-java) does not support the vector result of abs(...) here: it cannot tell which component to use
J07   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:41:8: in program Brightpass: the Java software peer (-java) does not support vector 'val' here: it cannot tell which component to use
J08   Exception in thread "main" com.sun.scenario.effect.compiler.JSLSourceException: Brightpass.jsl:41:29: in program Brightpass: the Java software peer (-java) does not support vector 'val' here: it cannot tell which component to use
G01   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: PhongLighting.jsl:124:0: in program PhongLighting_POINT: mismatched input 'void' expecting {',', ';'}
G02   Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: <CompilePhong declPosVar>:2:6: in program PhongLighting_POINT: no viable alternative at input 'param flaot3'
```

## How the cases were run
Outside Maven, the way the pom's `jsl-codegen` execution runs the generators. Each case uses the arguments
`GenAllDecoraShaders` passes for the shader, plus `-d3d -es2 -mtl`.
- Classpath: the `maven.compile.classpath` of the graphics pom, read from the poms. It holds graphics `target/classes`,
  base `target/classes` and jslc `target/classes`, then antlr4 4.7.2, antlr4-runtime 4.7.2, antlr-runtime 3.5.2,
  ST4 4.1, org.abego.treelayout.core 1.0.3, javax.json 1.0.4 and icu4j 61.1 from `~/.m2`. The module path is graphics
  and base `target/classes`.
- Both sides use the before clone's graphics and base classes and the before clone's `.jsl` inputs, since the change
  edits none of them. The jslc classes, `src/jslc/resources` and the generator classes
  (`target/jsl-compilers/{decora,prism}`) are each side's own, from its module gate. `C` is the before clone, `A` the
  side's clone, `K/<id>` the case's input directory and `W` the run's own directory:
  ```
  CP=$C/modules/javafx.graphics/target/classes:$C/modules/javafx.base/target/classes:$A/buildtools/jslc/target/classes:<the 7 jars>
  MP=$C/modules/javafx.graphics/target/classes:$C/modules/javafx.base/target/classes
  RES=$A/modules/javafx.graphics/src/jslc/resources
  ```
- A Decora case runs in one JVM:
  ```
  java --module-path=$MP --add-modules=javafx.graphics --add-exports=javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.scenario.effect.light=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.scenario.effect.impl.state=ALL-UNNAMED -classpath $CP:$RES:<generator classes> <generator> -i $K/<id> -o $W/out/jsl-decora -t -mtlheaders $W/out/mtl-headers -pkg com/sun/scenario/effect -d3d -es2 -mtl <arguments>
  ```

  | Ids | Generator | Arguments |
  | --- | --- | --- |
  | S01, M01, J01 to J08 | `CompileJSL` | `-java -hw Brightpass` |
  | J01s to J08s | `CompileJSL`, on the input of J0n | `Brightpass` |
  | S01g; J01g, on the input of J01 | `GenAllDecoraShaders` | `-all GenAllDecoraShaders` |
  | S02, M04 | `CompileLinearConvolve` | `-hw LinearConvolve` |
  | S03, S04, M02 | `CompileBlend` | `-java -hw Blend` |
  | S05, S06 | `CompilePhong` | `-java -hw PhongLighting` |
  | G01, G02 | `CompilePhong`, from the edited generator classes below, on unedited inputs | `-java -hw PhongLighting` |

  The generator classes are `$A/modules/javafx.graphics/target/jsl-compilers/decora`, except for G01 and G02.
- A Prism case (S07 to S11, M03) runs in one JVM, as the first of the pom's Prism runs:
  ```
  java -cp $CP:$RES:$A/modules/javafx.graphics/target/jsl-compilers/prism:$K/<id> CompileJSL -i $K/<id> -o $W/out/jsl-prism -t -mtlheaders $W/out/mtl-headers -pkg com/sun/prism -d3d -es2 -mtl -name $K/<id>/MaskAlphaOne.jsl
  ```
- Inputs: `K/<id>` is a copy of the before clone's `src/main/jsl-decora` (34 entries), or of its `src/main/jsl-prism`
  (30 entries) for S07 to S11 and M03, with the one edit of the table; `diff -r` against the clone shows only that
  edit. G01 and G02 read an unedited copy of `src/main/jsl-decora`.
- G01 and G02 generator classes: a copy of the side's `src/main/jsl-decora` with one line of `CompilePhong.java` edited.
  The line is `        "param float3 lightPosition;";` at `:48` before and `        "param float3 lightPosition;");` at
  `:49` after. Each copy is compiled as the pom's Decora `javac` compiles:
  ```
  javac -d <classes> -classpath $CP -sourcepath <copy> -encoding UTF-8 -g --release 27 -implicit:none --module-path $MP --add-modules=javafx.graphics --add-exports=javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.scenario.effect.light=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.scenario.effect.impl.state=ALL-UNNAMED <copy>/*.java
  ```
  The unedited after-side copy, compiled this way, gives 7 classes `cmp`-identical to the after gate's
  `target/jsl-compilers/decora`. Each edited copy differs from its own side's gate classes only in `CompilePhong.class`.
- The written files are listed with `find . -type f | LC_ALL=C sort | xargs md5sum` in the run's output root, and the
  exception lines with `grep -E '^(Exception in thread|Caused by:)'` on the run's combined standard output and error.

## Regeneration with `-d3d -es2 -mtl`
- Outside Maven, with the classpath and module path above. `GenAllDecoraShaders` runs in one JVM:
  `-i $C/modules/javafx.graphics/src/main/jsl-decora -o $W/out/jsl-decora -t -mtlheaders $W/out/mtl-headers
  -pkg com/sun/scenario/effect -d3d -es2 -mtl -all GenAllDecoraShaders`. Then the Prism `CompileJSL` runs once per
  `.jsl` file of the before clone's `src/main/jsl-prism`, in `LC_ALL=C sort` order (28 JVMs, `MaskAlphaOne.jsl`
  first), with that directory as `-i` and on the classpath and the file as `-name`. Every JVM runs in an empty working
  directory of its own, and all write into one fresh output root.
- After: all 29 JVMs exit 0 and print nothing, and the working directories stay empty. They write 1,059 files:
  `jsl-decora` 208, `jsl-prism` 848 and `mtl-headers` 3. The md5 list
  ([US-022-regeneration.md5](US-022-regeneration.md5)) is `cmp`-identical to the before side's and to
  [US-021-regeneration.md5](US-021-regeneration.md5).
- Without its 262 `.hlsl` lines (50 Decora, 212 Prism), the list has 797 lines and is `cmp`-identical to the after
  gate's `target/gensrc` list.
- The after gate's generator classes, used here: 7 Decora and 8 Prism classes.

## Module gate
`mvn -B -ntp -l <log> -Dmaven.test.failure.ignore=true -pl buildtools/jslc,modules/javafx.graphics -am clean test` in
the before clone and in the second after clone; the after run added `-o`. Counts are run / failures / errors /
skipped.

| | Before | After |
| --- | --- | --- |
| Exit and result | 0, BUILD SUCCESS | 0, BUILD SUCCESS |
| Maven total time; wall time | 01:16 min; 76 s | 01:11 min; 71 s |
| `javafx-jslc` | 276 / 0 / 0 / 0 in 31 classes | 405 / 0 / 0 / 0 in 35 classes: 276 + 129 tests in 31 + 4 classes |
| `javafx-base` | 5,510 / 0 / 0 / 22 in 290 classes | 5,510 / 0 / 0 / 22 in 290 classes |
| `javafx-graphics` | 26,696 / 0 / 0 / 858 in 629 classes | 26,696 / 0 / 0 / 858 in 629 classes |
| Per-class lines | 950 | 954 |

- `diff` of the per-class lists shows only 4 added lines: `JSLSourceTest` 73, `JavaPeerLimitTest` 8,
  `SourceFileClosedTest` 3 and `SourceLocationTest` 45, each with no failure, error or skip. Every other class of the
  three modules has the same counts on both sides ([per class](US-022-macos-per-class.tsv)). No class has a failure or
  an error. jslc skips nothing on either side, so `SourceFileClosedTest` ran its macOS branch.
- The per-class lists are made from each log with the `classes` function of US-021's
  [Windows gate](US-021-remove-unused-decora-generator-inputs.md#windows-gate), and merged with its `merge` function.
- Neither log has an `[ERROR]` line or a `warning(131)` line. Of the 132 `[WARNING]` lines of each log, 120 are
  surefire's `Tests run:` lines of classes and modules with skipped tests (118 and 2); the other 12 are `javac` notes,
  the same in both but for the clone's path in one of them. `jsl-codegen`, `cmake-configure` and `cmake-build` ran once
  in each.
- Neither clone has an `hs_err_pid*` file. Each holds 27 `hs_err` directories, none with a file in it.
- `target/gensrc` holds 797 files (`jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3) and no `headers` directory. The
  md5 lists are identical by name and md5 before and after ([md5 list](US-022-macos-gensrc.md5), the after run's) and
  to `US-021-macos-gensrc.md5`.
- Not run here: the Windows and WSL gates of the acceptance criteria, which the owner runs.
