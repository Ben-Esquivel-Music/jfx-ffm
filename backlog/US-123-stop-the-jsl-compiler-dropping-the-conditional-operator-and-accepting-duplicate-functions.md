# US-123 — Stop the JSL compiler dropping the conditional operator and accepting duplicate functions

**Status:** 📋 Ready (filed 2026-11-16 from US-022) ·
**Found:** 2026-11-16, the design review of US-022, measured again for this story the same day; T2 and D2 found while
measuring; E1 and E2 found by US-022's confirming review on 2026-11-17

## Story
As a developer who edits a `.jsl` shader,
I want the JSL compiler to compile a program as it is written, or else to reject it with an error at the construct it
cannot compile,
so that a conditional expression or a second definition of a function never gives shaders and peers that compute
something else, fail in a shader compiler later, or fail with an error about something the program does not say.

## Problem
Paths are relative to `modules/javafx.graphics`. The compiler's files are under
`src/jslc/java/com/sun/scenario/effect/compiler`, its grammar is
`src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4`. Line numbers are those of `726d7f74a5`.

### What US-022 changes and what it does not
This story was filed from US-022 on 2026-11-16. US-022 gives every error that the compiler raises for its input a file,
line and column. Of the inputs below, only T3 and the control D0 raise an error; the others compile without one.
US-022 leaves these defects out of its scope (its record,
[Not fixed here](US-022-report-jsl-errors-at-the-source-file-line.md#not-fixed-here)), so after US-022 each input
behaves exactly as before it, except that the errors of T3 and D0 gain a location, with the same text. Measured by
US-022's final macOS gate on 2026-11-17, through `JSLC.compile(JSLCInfo, String, long)`, whose messages name no file:
T3 gives `JSLSourceException: line 4:10 Initializer result type must match that of variable`, at the declared
variable `t`, and D0 `JSLSourceException: line 5:0 Function 'halve' already declared`, at the start of the second
definition; as before, neither writes a file.

### The inputs
Each input is this program with `<functions>` and `<body>` replaced by the lines given, except E1 and E2, whose
program their section gives. Without functions the `<functions>` line is left out. Each body line is indented by 4
spaces.
```
param sampler img;
<functions>
void main() {
    float4 val = sample(img, pos0);
    <body>
}
```
Unless said otherwise, each was compiled three times, each time with a new `JSLCInfo` and an empty output directory:
with the shaders only (`-d3d -es2 -mtl`), with the Java software peer only (`-java`), and with the output types the
build gives 8 of the 10 Decora shaders (`-java -hw -d3d -es2 -mtl`).

### The conditional operator is dropped (measured 2026-11-16)
- The grammar accepts `c ? a : b` (`JSL.g4:155-162`, under the comment `// TODO: handle ternary`). The visitor
  returns the expression of the condition alone and never reads the two branches (`tree/JSLVisitor.java:392-395`).
- **T1, `bool` branches.** Body `bool r = true ? false : true;`, `color = val;`. No error with any output type. The
  `.frag`, `.hlsl` and `.metal` have `bool r = true;`, and the program says `false`. The Java peer has the same line,
  which `javac` rejects for another reason (US-122, N1). The Metal compiler accepts the `.metal`.
- **T2, a `float` condition** (found while measuring). Body `float t = val.r ? 1.0 : 0.0;`, `color = val * t;`. No
  error with any output type. The 3 shaders have `float t = val.r;`, and the Java peer `float t = val_x;`, which
  `javac` compiles. The Metal compiler accepts the `.metal`.
- **T3, a comparison as the condition.** Body `float t = (val.r > 0.5) ? 1.0 : 0.0;`, `color = val * t;`.
  `java.lang.RuntimeException: Initializer result type must match that of variable` at `tree/TreeMaker.java:248`, with
  every output type, before any file is written: the `bool` condition is taken as the value of a `float`.
- No shipped `.jsl` file has `?:` in JSL code. The only `?` in the 57 files of `src/main/jsl-decora` and
  `src/main/jsl-prism` are in the Java of the glue block of `src/main/jsl-decora/DisplacementMap.jsl` (`:36` and
  `:45`, in the block of `:26-65`) and in a comment (`src/main/jsl-prism/MaskDrawEllipse.jsl:43`).

### A second definition of a function is accepted (measured 2026-11-16)
- `SymbolTable.declareFunction` rejects a function equal to one already declared (`model/SymbolTable.java:112-116`,
  `:156-158`). Two functions are equal when their names, return types and parameters are (`model/Function.java:62-80`),
  and two parameters when their names and types are (`model/Param.java:54-64`). A call is resolved by the function's
  name and parameter types alone, to the first match in a `HashSet` (`model/SymbolTable.java:40`, `:164-188`).
- **D0, the control.** Functions `float halve(float v) {`, `    return v * 0.5;`, `}`, `float halve(float v) {`,
  `    return v * 0.25;`, `}`; body `color = val * halve(val.r);`. `java.lang.RuntimeException: Function 'halve' already
  declared` at `model/SymbolTable.java:115`, with every output type, before any file is written.
- **D1, another parameter name.** D0 with the second function `float halve(float w) {`, `    return w * 0.25;`, `}`.
  No error with any output type.
  - The `.frag` (lines 17 and 20), `.hlsl` (2 and 5) and `.metal` (11 and 15) define `halve` twice and call
    `halve(val.r)`. The Metal compiler rejects the `.metal`: `redefinition of '<shader>_halve'`.
  - The Java peer binds the argument to `v_tmp` and inlines the body of the second function,
    `halve_res = w_tmp * 0.25f;`, so `javac` fails with `cannot find symbol`.
- **D2, another return type** (found while measuring). Functions `float halve(float v) {`, `    return v * 0.5;`, `}`,
  `float2 halve(float v) {`, `    return float2(v * 0.25, v);`, `}`; body `float h = halve(val.r);`, `color = val * h;`.
  Run three times with the shaders only and once with the Java peer only: no error.
  - The 3 shaders define both functions (`float halve(float v)` and `vec2 halve(float v)` in the `.frag`), the same in
    the three runs (the `.hlsl` md5-identical). The Metal compiler rejects the `.metal`: `functions that differ only in
    their return type cannot be overloaded`.
  - The Java peer declares `float halve_res;` and assigns `halve_res_x` and `halve_res_y` from the body of the
    `float2` function: `javac` fails with `cannot find symbol`.
- No program of the 57 shipped `.jsl` files has two functions of one name. The only name defined in two files,
  `rrmask` (`src/main/jsl-prism/MaskDrawRoundRect.jsl:34`, `src/main/jsl-prism/MaskDrawSemiRoundRect.jsl:36`), is in
  two masks, and a Prism program has one mask.
- Functions of one name with different parameter types are overloads, which the shaders keep and the Metal compiler
  accepts (US-122, O1).

### A further declarator skips the declarator checks (measured 2026-11-17)
- One declaration can declare several variables (`float a, b;`). The checks of a declarator's array size
  (`Array size must be an integer`, `Array size must be a constant integer`) and of a `const` declarator
  (`Constant value must be initialized`, `Constant type must match that of initializer`) run for the first
  declarator only (`tree/JSLVisitor.java:530-574`). `visitDeclaration` declares each further one with the
  four-argument `SymbolTable.declareVariable` (`tree/JSLVisitor.java:709-721`, `model/SymbolTable.java:59-63`), which
  passes no array size (-1) and no constant value, so none of these checks runs for it (read from the code).
- **E1, an array size that is not an integer.** Statement `    float g1, g2[1.5];` in `main`. No error. On a first
  declarator the same size fails: `param float4 w[1.5];` gives `Array size must be an integer`.
- **E2, a `const` without an initializer.** Statement `    const float c1 = 1.0, c2;` in `main`. No error. On a first
  declarator it fails: `    const float k;` gives `Constant value must be initialized`.
- Measured by US-022's confirming review with a driver of its own that called `JSLC.compile(JSLCInfo, String, long)`
  with the HLSL output only (`-d3d`) and the source time `Long.MAX_VALUE`, on the program `param float x;`,
  `param int count;`, `void main() {`, the statement, `    color = float4(x);`, `}`: both inputs compile, with the
  compiler of `726d7f74a5` and with US-022's. The two failing controls are rows of US-022's
  `SourceLocationTest.anErrorOfTheInputIsReportedAtItsConstruct`, run on US-022's compiler. Not run: the other output
  types, the Java peer and the Metal compiler.

### Tests
No jslc test compiles a conditional expression or two functions of one name (`src/test/jslc` searched for `?`,
`ternary`, `conditional_expression`, `already declared` and `overload`).

### How it was measured
- macOS 27.2.1 (26B131) arm64, JDK 27.0.1.0.2, outside Maven, on 2026-11-16. The JSL compiler classes of a clone of
  `726d7f74a5` built by US-022's module gate (`buildtools/jslc/target/classes`), with `src/jslc/resources` and, from
  the local Maven repository, antlr4 and antlr4-runtime 4.7.2, antlr-runtime 3.5.2, ST4 4.1,
  org.abego.treelayout.core 1.0.3, javax.json 1.0.4 and icu4j 61.1.
- A driver called `JSLC.compile(JSLCInfo, String, long)` with the source time `Long.MAX_VALUE` (every output out of
  date), a new `JSLCInfo` per compile with `trimToOutDir`, and printed the exception, its first frame in the compiler
  and the files written.
- The Java peers were also generated with the shader name `Brightpass`, so that they import an effect class that
  exists, and compiled alone with `javac` of the same JDK against the `javafx.graphics` and `javafx.base` classes of
  that clone. The Metal compiler was run by hand (`xcrun -sdk macosx metal -c`, Apple metal 32023.921, with the
  `FragmentShaderCommon.h` that the baseline build generated).
- Not run: Windows, Linux, Maven, the generators, an HLSL or GLSL compiler, and a generated peer at run time.

### Upstream too
`openjdk/jfx` master at `f1bb639dab` (2026-11-16) has the same `JSLVisitor.java`, `SymbolTable.java`, `Function.java`
and `Param.java`: their git blob ids equal the fork's at `726d7f74a5` (read through the GitHub API). Its `JSL.g4`
differs, since US-019 and US-020 changed the fork's, but has the same `ternary_part` and `conditional_expression` rules
at `:155-162`. Upstream was not built or run, and JBS was not searched.

## Requirement
1. **No conditional expression is dropped.** A program with a conditional expression `c ? a : b` either compiles into
   outputs that evaluate it as written, or fails with an error of the input, in the form US-022 gives every error of
   the input (file, line, column and program), at that expression.
2. **A second definition is an error.** A function with the name and parameter types of a function declared before
   it is an error of the input, whatever its parameter names and its return type, as D0 is today.
3. **Every declarator is checked.** A further declarator of a declaration is held to the array-size and `const` rules
   of the first: E1 and E2 fail with an error of the input at the further declarator's identifier, in the form US-022
   gives every error of the input.

## Constraints
- **Overloads stay.** Two functions of one name with different parameter types stay a valid program: US-122's O1
  compiles with the shaders only and writes the same `.hlsl`, `.frag` and `.metal` as today, byte for byte.
- **Generated files unchanged.** The regeneration with `-d3d -es2 -mtl` outside Maven, as US-022 ran it
  (`backlog/US-022-generator-cases.md`), writes the same 1,059 files, identical by name and md5
  (`backlog/US-021-regeneration.md5`).
- **Existing tests.** The jslc tests that exist when the story is picked up pass unchanged.
- **Code rules.** Java 27 final features only, no `var`; a production file stays at most 900 lines (`wc -l`); lines
  at most 120 columns, one statement per line; comments only for a non-obvious "why".

## Acceptance criteria
1. **The conditional operator.** A jslc test compiles T1, T2 and T3 with every output type. None gives today's
   result (`bool r = true;`, `float t = val.r;`, `Initializer result type must match that of variable`). Each either
   fails with an error of the input whose full message the test pins (the file, line and column of the conditional
   expression, worked out by hand from the input, the program and the text), or writes outputs in which the test
   shows the expression evaluated as written (T1's `r` is `false`; T3's `t` is `1.0` where `val.r > 0.5` and `0.0`
   elsewhere), with a `.metal` that the Metal compiler accepts.
2. **Second definitions.** A jslc test compiles D1 and D2 with every output type. Each fails before any file is
   written, with an error of the input whose full message the test pins: the file, line and column of the second
   definition, worked out by hand, the program, and a text that says the function is already declared. D0 fails as
   US-022 leaves it.
3. **Overloads.** US-122's O1, compiled with the shaders only, writes the same 3 files before and after, md5-identical.
4. **Mutant proof.** With the change undone in a scratch copy, the tests of criteria 1, 2 and 6 fail. The repository
   version passes.
5. **The constraints hold.** The regeneration is md5-identical before and after.
   `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test` passes, with
   `target/gensrc/{jsl-decora,jsl-prism,mtl-headers}` identical by name and md5 before and after and the same
   test counts per class as before plus the new tests. The platforms it ran on are recorded here.
6. **Further declarators.** A jslc test compiles E1 and E2 with every output type. Each fails before any file is
   written, with an error of the input whose full message the test pins: the file, line and column of the further
   declarator's identifier (`g2`, `c2`), worked out by hand, the program and the text. Today both compile.

## Out of scope
- Which file, line and column an error names: US-022.
- What the Java software peer does with an overload, with `bool` and with the other inputs of US-122: US-122.

## Definition of Done
- All acceptance criteria met, with the logs and md5 lists of every run kept with the change and the results stated
  in this story.
- An independent review repeated criteria 1, 2 and 6 with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

## Notes
- Found by the design review of US-022 (2026-11-16): T1, T3 and D1. US-022 put them out of its scope and filed this
  story. T2 and D2 were found while measuring for this story. E1 and E2 were found by US-022's confirming review
  (2026-11-17), on the compiler of `726d7f74a5` and on US-022's, and added to this story then.
- The driver and the edited inputs were not kept in the repository. The inputs above describe each case fully enough
  to rebuild it.
