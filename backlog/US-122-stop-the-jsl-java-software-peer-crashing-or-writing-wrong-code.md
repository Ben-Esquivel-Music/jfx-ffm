# US-122 — Make the shader compiler compile correctly, or reject, the constructs it mishandles today

## In short
- **Problem:** The shader compiler (jslc) mishandles some constructs without saying so.
  - Part 1: for certain constructs, the Java version it writes of some Decora effects crashes the compiler with a
    Java exception, or is Java that computes something else or does not compile.
  - Part 2: it accepts `c ? a : b` and silently keeps only `c`, and it accepts two functions with the same name.
  - Part 3: it writes Metal shader files that Apple's Metal compiler then rejects, so the error shows up late, only
    in a macOS build, at a line of a generated file.
- **Seen failing?** Yes, on test inputs written to provoke each case. No shipped shader contains any of these
  constructs, so nothing a user runs is wrong today.
- **Goal:** For every input, the compiler either writes correct output for every backend or stops with an error at the
  file, line and column.
- **Done when:** Tests compile every recorded input of the three parts, and none ends in a Java exception, in wrong
  output or in a shader that the Metal compiler rejects. The files generated from the shipped shaders stay identical.
- **Order:** Can start now. It matters only to someone who edits a shader. Part 3 needs the Mac. Merged on 2026-11-27
  from US-122, US-123 and US-124; each part keeps its own text below.

**Status:** 📋 Ready (merged on 2026-11-27 from US-122, US-123, US-124 at the owner's request; no text of the parts
was changed, only their headings moved one level down)

## Part 1 — Stop the Java software peer of the JSL compiler crashing or writing wrong code

**Status:** 📋 Ready (filed 2026-11-16 from US-022) ·
**Found:** 2026-11-16, the design review of US-022, measured again for this story the same day; W3, N1 and N2 found
while measuring

### Story
As a developer who edits one of the Decora `.jsl` shaders that the build also compiles to a Java software peer,
I want the JSL compiler to write a peer that computes what the program says, or else to stop with an error that names
the file, line and column of what the peer cannot express,
so that an edit that the shader backends compile neither crashes the compiler with a JVM exception nor builds a peer
that computes something else or does not compile.

### Problem
Paths are relative to `modules/javafx.graphics`. The compiler's files are under
`src/jslc/java/com/sun/scenario/effect/compiler`, those of its Java backend under `backend/sw/java` there. Line numbers
are those of `726d7f74a5`.

#### The Java software peer
- The build compiles 8 of the 10 Decora shaders with `-java` (`src/main/jsl-decora/GenAllDecoraShaders.java:56`,
  `:67-75`): `ColorAdjust`, `Brightpass`, `SepiaTone`, `PerspectiveTransform`, `DisplacementMap`, `InvertMask`, `Blend`
  (19 programs) and `PhongLighting` (3). `JSLC.compile` writes a program's HLSL, GLSL and Metal shaders first and its
  Java peer after them (`JSLC.java:209-260`). The pom's second-pass `javac` compiles the peers with the module
  (`pom.xml:203-225`; amended 2026-11-21 for US-024's change, `:199-221` with US-023's and `:206-228` at
  `726d7f74a5`). The Prism programs and `LinearConvolve*` are compiled without `-java`.
- The Java backend writes Java statement by statement (`JSWTreeScanner`) and inlines each call where it is used
  (`JSWCallScanner.visitCallExpr`, `JSWCallScanner.java:146-250`): a core function from the table of `JSWFuncImpls`
  (`JSWFuncImpls.java:52-133`), a user function by scanning its body again (`JSWCallScanner.java:242-245`). The code
  that computes a call's result, its preamble, is written only before an expression statement and before a
  declaration (`JSWTreeScanner.java:142`, `:273`).

#### What US-022 changes and what it does not
This story was filed from US-022 on 2026-11-16. US-022 gives every error that the compiler raises for its input a file,
line and column, among them the 7 `InternalError`s of the Java backend that an input can reach. The inputs below raise
no such error: each crashes the compiler with a JVM exception, or compiles without an error. US-022 leaves them out of
its scope (its record,
[Not fixed here](US-022-report-jsl-errors-at-the-source-file-line.md#not-fixed-here)), so after US-022 each behaves
exactly as before it: the same exception without a location, or the same files. The one exception is O2, below.

#### The inputs
Each input is this program with `<functions>` and `<body>` replaced by the lines given. Without functions the
`<functions>` line is left out. Each body line is indented by 4 spaces.
```
param sampler img;
<functions>
void main() {
    float4 val = sample(img, pos0);
    <body>
}
```
Each was compiled three times, each time with a new `JSLCInfo` and an empty output directory: with the shaders only
(`-d3d -es2 -mtl`), with the Java peer only (`-java`), and with the output types of the 8 shaders
(`-java -hw -d3d -es2 -mtl`). With the shaders only, every input below compiles and writes the `.hlsl`, `.frag` and
`.metal`.

#### The compiler crashes (measured 2026-11-16)
With `-java` the compile throws. With `-java -hw -d3d -es2 -mtl` it throws the same exception after writing the 3
shaders and before any peer.
- **C1, an empty statement.** Body `color = val;;`; also a line holding only `;` before `color = val;`.
  `java.lang.NullPointerException: Cannot invoke "com.sun.scenario.effect.compiler.tree.Expr.getResultType()" because
  "<local2>" is null` at `JSWTreeScanner.java:144`: the empty statement has no expression.
- **C2, `length` and `any`.** Body `float l = length(val.xy);`, `color = val * l;`; and body
  `bool2 b = bool2(true, false);`, `bool r = any(b);`, `color = val;`. Both give `java.lang.NullPointerException:
  Cannot invoke "com.sun.scenario.effect.compiler.tree.FuncDef.getStmt()" because the return value of
  "com.sun.scenario.effect.compiler.backend.sw.java.JSWBackend.getFuncDef(String)" is null` at
  `JSWCallScanner.java:244`. Both are core functions (`model/CoreSymbols.java:83-86`, `:144-145`) that `JSWFuncImpls`
  does not implement, so the call is taken for a call of a user function, and there is none.
- **C3, a sampler in parentheses.** `sample((img), pos0)` in place of `sample(img, pos0)`, body `color = val;`.
  `java.lang.ClassCastException: class com.sun.scenario.effect.compiler.tree.ParenExpr cannot be cast to class
  com.sun.scenario.effect.compiler.tree.VariableExpr` at `JSWFuncImpls.java:202`, which casts the first argument of
  `sample` to a variable.
- **C4, a field of a vector constructor.** Body `float x = float2(1.0, 2.0).y;`, `color = val * x;`.
  `java.lang.IndexOutOfBoundsException: Index 3 out of bounds for length 2` at `JSWTreeScanner.java:323`. The backend
  takes the constructor's argument at the index of the last vector component it wrote (3, from the `float4 val` line),
  not at the selected field.
- **C5, a vector argument of a vector constructor.** Body `float4 c = float4(1.0, 2.0, 3.0, val.xy);`, `color = c;`.
  `java.lang.StringIndexOutOfBoundsException: Index 3 out of bounds for length 2` at `JSWTreeScanner.java:165`.
- **C6, recursion.** Functions `float f(float x) {`, `    float t = f(x);`, `    return t;`, `}`; body
  `float y = f(val.r);`, `color = val * y;`. `java.lang.StackOverflowError`: the stack repeats
  `JSWCallScanner.visitCallExpr` (`JSWCallScanner.java:244`, which scans the body of `f` again for the call inside it)
  and `JSWTreeScanner.outputPreambles` (`JSWTreeScanner.java:335-336`).
- **Through the generator.** In a copy of `src/main/jsl-decora` whose `Brightpass.jsl:40` ends in `;;`, the Decora
  `CompileJSL` with `-java -hw -d3d -es2 -mtl` and `Brightpass`, as `GenAllDecoraShaders` runs it, exits 1 with C1's
  exception after writing the 3 shaders. Each is md5-identical to the shader of the same run with the shaders only,
  which exits 0 and also writes the 2 Metal headers.

#### The peer is wrong or does not compile (measured 2026-11-16)
Each input compiles with every output type without an error and writes all its files. The peers were also generated
with the shader name `Brightpass`, so that they import an effect class that exists, and each was compiled with `javac`
against the `javafx.graphics` and `javafx.base` classes of `726d7f74a5`. A control with body
`float luminance = val.r;`, `float a = abs(luminance);`, `if (a > 0.5) luminance = 0.0;`, `color = val * luminance;`
gives a peer that compiles.
- **W1, a call in an `if` condition.** Body `float luminance = val.r;`,
  `if (abs(luminance) > 0.5) luminance = 0.0;`, `color = val * luminance;`. The peer has
  `if (abs_res > 0.5f)luminance = 0.0f;` and no declaration of `abs_res`: `javac` fails with `cannot find symbol`.
- **W2, the same after an earlier call of the function.** Body `float a = abs(val.g);`, `float luminance = val.r;`,
  `if (abs(luminance) > 0.5) luminance = 0.0;`, `color = val * luminance * a;`. The same `if`, but `abs_res` is now
  declared and holds `abs(val.g)`, computed for `a`. The peer compiles and tests the wrong value.
- **W3, a call in a `while` condition** (found while measuring). Body `float luminance = val.r;`,
  `while (abs(luminance) > 0.5) luminance = luminance * 0.5;`, `color = val * luminance;`. The peer has
  `while (abs_res > 0.5f)luminance = luminance * 0.5f;`: `cannot find symbol`.
- **W4, a call in a `return`.** Functions `float f(float x) {`, `    return abs(x);`, `}`; body `float y = f(val.r);`,
  `color = val * y;`. The peer has `f_res = abs_res;`: `cannot find symbol`.
  - The shipped `Blend_ADD.jsl` works around it: `:73-76` compute the `max(...)` into a variable before the `return`
    "to circumvent a JSL parsing limitation". With the workaround undone in a copy (line 76 is
    `    return bot + top - max(bot*top.a + top*bot.a - top.a*bot.a, 0.0);` and line 75 is gone), `CompileBlend` with
    `-java -hw -d3d -es2 -mtl` exits 0, prints nothing and writes 97 files. Its `JSWBlend_ADDPeer.java` fails `javac`
    with 4 errors, `cannot find symbol` for `max_res_x` to `max_res_w` (lines 215-218). The peer of the unedited file
    compiles.
- **W1 to W4.** The preamble is written only for an expression statement and a declaration (above), not for the
  condition of an `if` (`JSWTreeScanner.java:246-257`) or a `while` (`:326-332`), nor for a `return` (`:219-244`).
  The backend declares a call's result variable once per program (`JSWBackend.java:283-289`), so in W2 the condition
  reads the value of the earlier call.
- **W5, a field of a vector constructor, first.** Main's body is only `float x = float2(1.0, 2.0).y;`,
  `color = sample(img, pos0) * x;`. The peer has `float x = 1.0f;`; the program's value is 2.0. The index of C4 is 0
  here (`JSWTreeScanner.java:319-324`). The peer compiles.
- **O1, an overloaded function.** Functions `float g(float x) {`, `    return x * 0.5;`, `}`, `float g(float2 x) {`,
  `    return 2.0;`, `}`; body `float y = g(val.r);`, `color = val * y;`. The call is of `g(float)`, but the peer has
  `g_res = 2.0f;`, the body of `g(float2)`, and compiles. The shaders define both functions and call `g(val.r)`.
- **O2, the same with an error in the other overload.** O1 with the body of `g(float2)` replaced by
  `    if (x == x) return 1.0;`, `    return 0.0;`. `java.lang.InternalError: TBD` at `JSWTreeScanner.java:314`, raised
  for the `x` of `g(float2)`. This is one of the 7 sites that US-022 turns into an error of the input at its
  construct. Measured by US-022's final macOS gate on 2026-11-17, through `JSLC.compile(JSLCInfo, String, long)`,
  whose messages name no file: with `-java`, `JSLSourceException: line 6:8 the Java software peer (-java) does not
  support vector 'x' here: it cannot tell which component to use`, at the first `x` of `if (x == x)` in `g(float2)`,
  a function the program does not call; with the output types of the 8 shaders the same, after the 3 shaders are
  written; with the shaders only, no error.
- **O1 and O2.** The backend keeps user functions by name (`JSWBackend.java:274-281`; stored by
  `JSWTreeScanner.java:184-192`, read by `JSWCallScanner.java:244`), so a call inlines the body of the last function of
  that name.
- **N1, a `bool` variable** (found while measuring). Body `bool r = false;`, `color = val;`. The peer has
  `bool r = false;`, since `JSWTreeScanner.java:291` writes the JSL type's name (`:279` for vector types), and Java
  has no `bool`: `cannot find symbol`.
- **N2, `ddx`** (found while measuring). Body `float d = ddx(val.r);`, `color = val * d;`. The peer has
  `ddx_res = <ddx() not implemented for sw backends>;` (`JSWFuncImpls.java:128-129`): `javac` fails with
  `> or ',' expected`. `ddy` has the same kind of text at `:131-132` (read, not run).
- **Through the generator.** In a copy of `src/main/jsl-decora` with `    if (abs(luminance) > 0.5) luminance = 0.0;`
  after `Brightpass.jsl:40` (W1), the Decora `CompileJSL` with `-java -hw -d3d -es2 -mtl` and `Brightpass` exits 0,
  prints nothing and writes 7 files. Its `JSWBrightpassPeer.java` fails `javac` with `cannot find symbol` at
  `abs_res` (line 182), so the build would fail in the second-pass `javac`, on a generated file (read, not run in
  Maven).

#### The shader outputs
The Metal compiler, run by hand (`xcrun -sdk macosx metal -c`, Apple metal 32023.921, with the
`FragmentShaderCommon.h` that the baseline build generated), accepts the `.metal` of C1, C2, C4, C6, W1 to W5, O1, N1
and N2. It rejects three:
- C3: `no matching function for call to '<shader>_SampleTexture'`; the Metal backend passes `(uniforms.img)` and no
  sampler.
- C5: `no matching constructor for initialization of 'float4'`; the four arguments have five components.
- O2: `value of type 'bool2' (vector of 2 'bool' values) is not contextually convertible to 'bool'`, for
  `if (x == x)`.

It also rejects, in the same way, the `.metal` of the input of US-022's Java-peer case J07,
`if (val == val) luminance = 0.0;` on the vector `val`, which compiles with the shaders only: `value of type 'bool4'
(vector of 4 'bool' values) is not contextually convertible to 'bool'` (measured 2026-11-16).

The macOS build compiles the generated `.metal` files into `jfxshaders.metallib` (`native/mac.cmake:211-212`). No HLSL
or GLSL compiler was run. These rejections belong to
[US-124](US-124-stop-the-jsl-compiler-writing-metal-shaders-that-the-metal-compiler-rejects.md), not to this story.

#### The shipped shaders
None of the inputs above is in a shipped file. Of the 57 `.jsl` files of `src/main/jsl-decora` and
`src/main/jsl-prism`, none has `;;`, `any`, `ddx`, `ddy`, `bool` or `sample((`, or a field selected from a vector
constructor; `length` is only in a comment (`src/main/jsl-prism/MaskDrawEllipse.jsl:44`); and no program has two
functions of one name. In the 8 shaders compiled with `-java`, outside glue blocks, no `if` condition and no `return`
calls a function (a vector constructor such as the `float3(h, s, b)` of `ColorAdjust.jsl:85` is not a call), and
there is no `while`; `Blend_ADD.jsl:73-76` calls `max` before its `return`, as above.

#### How it was measured
- macOS 27.2.1 (26B131) arm64, JDK 27.0.1.0.2, outside Maven, on 2026-11-16. The JSL compiler classes of a clone of
  `726d7f74a5` built by US-022's module gate (`buildtools/jslc/target/classes`), with `src/jslc/resources` and, from
  the local Maven repository, antlr4 and antlr4-runtime 4.7.2, antlr-runtime 3.5.2, ST4 4.1,
  org.abego.treelayout.core 1.0.3, javax.json 1.0.4 and icu4j 61.1.
- A driver called `JSLC.compile(JSLCInfo, String, long)` with the source time `Long.MAX_VALUE` (every output out of
  date), a new `JSLCInfo` per compile with `trimToOutDir`, and printed the exception, its first frame in the compiler
  and the files written.
- The generator runs used the generator classes (`target/jsl-compilers/decora`) and the `javafx.graphics` and
  `javafx.base` classes of that clone, with the arguments `GenAllDecoraShaders` passes for the shader plus
  `-d3d -es2 -mtl` and `-mtlheaders`, as US-022 ran its generator cases (`backlog/US-022-generator-cases.md`), each
  with its own output directory and an empty working directory, which stayed empty.
- `javac` of the same JDK compiled each peer alone, with those `javafx.graphics` and `javafx.base` classes on the class
  path.
- Not run: Windows, Linux, Maven, an HLSL or GLSL compiler, and a generated peer at run time.

#### Upstream too
`openjdk/jfx` master at `f1bb639dab` (2026-11-16) has the same `JSWTreeScanner.java`, `JSWCallScanner.java`,
`JSWBackend.java`, `JSWFuncImpls.java` and `CoreSymbols.java`: their git blob ids equal the fork's at `726d7f74a5`
(read through the GitHub API). Upstream was not built or run, and JBS was not searched.

### Requirement
1. **No crash.** Compiling with `-java` a program that the JSL compiler accepts never ends in an exception or error of
   the JVM thrown by the Java backend: no `NullPointerException`, `ClassCastException`, index exception,
   `StackOverflowError` or `InternalError`.
2. **A right peer or an error of the input.** For each such program the compiler either writes a Java peer that
   `javac` compiles and that computes what the program says, or stops with an error of the input, in the form US-022
   gives every error of the input (file, line, column and program), that names the construct the Java software peer
   cannot express and says that the peer (`-java`) does not support it.
3. **An error names code the program runs.** An error of the Java peer names a construct in the code that the
   program's `main` reaches, not one in a function it does not call.

### Constraints
- **Generated files unchanged.** The regeneration with `-d3d -es2 -mtl` outside Maven, as US-022 ran it
  (`backlog/US-022-generator-cases.md`), writes the same 1,059 files, identical by name and md5
  (`backlog/US-021-regeneration.md5`).
- **Shaders unchanged.** For each input above, the shader-only compile writes the same `.hlsl`, `.frag` and `.metal`
  as today, byte for byte, and a program that compiles with the shaders only today still does.
- **Existing tests.** The jslc tests that exist when the story is picked up pass unchanged.
- **Code rules.** Java 27 final features only, no `var`; a production file stays at most 900 lines (`wc -l`); lines
  at most 120 columns, one statement per line; comments only for a non-obvious "why".

### Acceptance criteria
1. **No JVM exception.** A jslc test compiles each of C1 to C6 (both inputs of C1 and of C2), W1 to W5, O1, O2, N1
   and N2 with every output type, and asserts that each compile ends normally or with an error of the input. At
   `726d7f74a5` nine end in an exception of the JVM (C1 to C6 and O2); after US-022, eight (C1 to C6).
2. **The errors are pinned.** For each input that fails, the test pins the full message: the file, line and column of
   the construct, worked out by hand from the input, the program, and a text that names the construct and says that
   the Java software peer (`-java`) does not support it. The same program with the shaders only compiles and writes
   the 3 shaders, md5-identical to those of the failing compile (without `-mtl` for an input that US-124 rejects with
   `-mtl`).
3. **The peers that are written are right.** For each input that compiles with `-java`, the peer compiles with
   `javac` against the module's classes, as measured above, and a test shows that it computes what the program says,
   at least: W2 tests `abs(luminance)`, W5's `x` is 2.0, and O1's `y` is `val.r * 0.5`.
4. **Through the generator.** The three generator cases above (`;;` in `Brightpass.jsl`, the `if` in
   `Brightpass.jsl`, the workaround undone in `Blend_ADD.jsl`), rerun as the pom runs the generators, each exit
   non-zero with an error of the input that names the file, line and column, or write peers that the module's build
   compiles.
5. **Mutant proof.** With the change undone in a scratch copy, the tests of criteria 1 to 3 fail. The repository
   version passes.
6. **The constraints hold.** The regeneration and the shader outputs are md5-identical before and after.
   `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test` passes, with
   `target/gensrc/{jsl-decora,jsl-prism,mtl-headers}` identical by name and md5 before and after and the same
   test counts per class as before plus the new tests. The platforms it ran on are recorded here.

### Out of scope
- The 7 `InternalError` sites of US-022's story and the location of every error the compiler raises: US-022.
- The conditional operator `?:` and a second function with the name and parameter types of an earlier one: US-123.
- The Metal compiler's rejection of the `.metal` of C3, C5, O2 and US-022's case J07, which the shader backends
  write: [US-124](US-124-stop-the-jsl-compiler-writing-metal-shaders-that-the-metal-compiler-rejects.md).

### Definition of Done
- All acceptance criteria met, with the logs and md5 lists of every run kept with the change and the results stated
  in this story.
- An independent review repeated criteria 1 to 3 with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

### Notes
- Found by the design review of US-022 (2026-11-16): C1 to C6, O1, O2, W1, W2, W4 and W5. US-022 put them out of its
  scope and filed this story. W3, N1 and N2 were found while measuring for this story.
- `Blend_ADD.jsl:73-74` calls W4 "a JSL parsing limitation". The parser accepts the `return`; the Java backend writes a
  peer that does not compile.
- The driver and the edited inputs were not kept in the repository. The inputs above describe each case fully enough
  to rebuild it.

## Part 2 — Stop the JSL compiler dropping the conditional operator and accepting duplicate functions (was US-123)

**Status:** 📋 Ready (filed 2026-11-16 from US-022) ·
**Found:** 2026-11-16, the design review of US-022, measured again for this story the same day; T2 and D2 found while
measuring; E1 and E2 found by US-022's confirming review on 2026-11-17

### Story
As a developer who edits a `.jsl` shader,
I want the JSL compiler to compile a program as it is written, or else to reject it with an error at the construct it
cannot compile,
so that a conditional expression or a second definition of a function never gives shaders and peers that compute
something else, fail in a shader compiler later, or fail with an error about something the program does not say.

### Problem
Paths are relative to `modules/javafx.graphics`. The compiler's files are under
`src/jslc/java/com/sun/scenario/effect/compiler`, its grammar is
`src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4`. Line numbers are those of `726d7f74a5`.

#### What US-022 changes and what it does not
This story was filed from US-022 on 2026-11-16. US-022 gives every error that the compiler raises for its input a file,
line and column. Of the inputs below, only T3 and the control D0 raise an error; the others compile without one.
US-022 leaves these defects out of its scope (its record,
[Not fixed here](US-022-report-jsl-errors-at-the-source-file-line.md#not-fixed-here)), so after US-022 each input
behaves exactly as before it, except that the errors of T3 and D0 gain a location, with the same text. Measured by
US-022's final macOS gate on 2026-11-17, through `JSLC.compile(JSLCInfo, String, long)`, whose messages name no file:
T3 gives `JSLSourceException: line 4:10 Initializer result type must match that of variable`, at the declared
variable `t`, and D0 `JSLSourceException: line 5:0 Function 'halve' already declared`, at the start of the second
definition; as before, neither writes a file.

#### The inputs
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

#### The conditional operator is dropped (measured 2026-11-16)
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

#### A second definition of a function is accepted (measured 2026-11-16)
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

#### A further declarator skips the declarator checks (measured 2026-11-17)
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

#### Tests
No jslc test compiles a conditional expression or two functions of one name (`src/test/jslc` searched for `?`,
`ternary`, `conditional_expression`, `already declared` and `overload`).

#### How it was measured
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

#### Upstream too
`openjdk/jfx` master at `f1bb639dab` (2026-11-16) has the same `JSLVisitor.java`, `SymbolTable.java`, `Function.java`
and `Param.java`: their git blob ids equal the fork's at `726d7f74a5` (read through the GitHub API). Its `JSL.g4`
differs, since US-019 and US-020 changed the fork's, but has the same `ternary_part` and `conditional_expression` rules
at `:155-162`. Upstream was not built or run, and JBS was not searched.

### Requirement
1. **No conditional expression is dropped.** A program with a conditional expression `c ? a : b` either compiles into
   outputs that evaluate it as written, or fails with an error of the input, in the form US-022 gives every error of
   the input (file, line, column and program), at that expression.
2. **A second definition is an error.** A function with the name and parameter types of a function declared before
   it is an error of the input, whatever its parameter names and its return type, as D0 is today.
3. **Every declarator is checked.** A further declarator of a declaration is held to the array-size and `const` rules
   of the first: E1 and E2 fail with an error of the input at the further declarator's identifier, in the form US-022
   gives every error of the input.

### Constraints
- **Overloads stay.** Two functions of one name with different parameter types stay a valid program: US-122's O1
  compiles with the shaders only and writes the same `.hlsl`, `.frag` and `.metal` as today, byte for byte.
- **Generated files unchanged.** The regeneration with `-d3d -es2 -mtl` outside Maven, as US-022 ran it
  (`backlog/US-022-generator-cases.md`), writes the same 1,059 files, identical by name and md5
  (`backlog/US-021-regeneration.md5`).
- **Existing tests.** The jslc tests that exist when the story is picked up pass unchanged.
- **Code rules.** Java 27 final features only, no `var`; a production file stays at most 900 lines (`wc -l`); lines
  at most 120 columns, one statement per line; comments only for a non-obvious "why".

### Acceptance criteria
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

### Out of scope
- Which file, line and column an error names: US-022.
- What the Java software peer does with an overload, with `bool` and with the other inputs of US-122: US-122.

### Definition of Done
- All acceptance criteria met, with the logs and md5 lists of every run kept with the change and the results stated
  in this story.
- An independent review repeated criteria 1, 2 and 6 with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

### Notes
- Found by the design review of US-022 (2026-11-16): T1, T3 and D1. US-022 put them out of its scope and filed this
  story. T2 and D2 were found while measuring for this story. E1 and E2 were found by US-022's confirming review
  (2026-11-17), on the compiler of `726d7f74a5` and on US-022's, and added to this story then.
- The driver and the edited inputs were not kept in the repository. The inputs above describe each case fully enough
  to rebuild it.

## Part 3 — Stop the JSL compiler writing Metal shaders that the Metal compiler rejects (was US-124)

**Status:** 📋 Ready (filed 2026-11-17 from US-022) ·
**Found:** 2026-11-16, while US-122 was measured (its C3, C5 and O2, and the line of US-022's Java-peer case J07);
measured again for this story on 2026-11-17, when J06 was found

### Story
As a developer who edits a `.jsl` shader,
I want the JSL compiler to write a Metal shader that the Metal compiler compiles, or else to stop with an error that
names the file, line and column of what the Metal shader cannot express,
so that a program that the JSL compiler accepts does not fail later, only in the macOS build, at a line of a generated
file.

### Problem
Paths are relative to `modules/javafx.graphics`. The compiler's files are under
`src/jslc/java/com/sun/scenario/effect/compiler`. Line numbers are those of `726d7f74a5`.

#### What the build does with a `.metal` file
- The `jsl-codegen` execution of `pom.xml` (`:114`, phase `compile`) runs the Decora and Prism generators with
  `${jfx.jsl.backends}`: `-mtl -es2` on macOS (`:649`), `-mtl` there with `-DINCLUDE_ES2=false` (`:667`), `-d3d` or
  `-d3d -es2` on Windows (`:617`, `:634`) and `-es2` elsewhere (`:48`). Only a macOS build writes `.metal` files.
  (Amended 2026-11-17: these four lines are those of US-023's change, 7 lower than at `726d7f74a5`. Amended
  2026-11-21: they are now those of US-024's change, 4 higher than with US-023's.)
- On macOS, unless `-DskipNative=true`, the `native-mac` profile runs `cmake-configure` and `cmake-build` at
  `process-classes` (`pom.xml:801-859`). `native/mac.cmake:233-241` gives every generated `.metal` of `jsl-decora`
  and `jsl-prism` to `add_msl_shader` (`:220-231`), which compiles it with
  `metal -Wdeprecated -I <gensrc>/mtl-headers -std=macos-metal2.4 -c` (`:227`, `:239`; `macos-metal2.4` is
  `jfx.msl.version`, root `pom.xml:111`). The `shaders` target, which `cmake --build` builds by default, links the
  results into `jfxshaders.metallib` (`:248-254`).
- So a `.metal` that the Metal compiler rejects fails `cmake --build`, and with it the build, at `process-classes`.
  The message names a line of the generated file under `target/gensrc`, not the `.jsl` file. A Windows or Linux build
  writes no `.metal`, so the Metal compiler never sees the program there. Read from the build files, not run in
  Maven.
- The generators compile 8 of the 10 Decora shaders also with `-java`. For them the Java software peer stops the
  generator first, after the shaders are written: C3 and C5 below crash it (US-122), and O2, J06 and J07 are its
  errors of the input since US-022 (`US-022-generator-cases.md`, J06 and J07). The Prism programs and
  `LinearConvolve*` are compiled without `-java` (US-122), so for them the generator exits 0 and the Metal compiler
  is the first to fail (read, not run through a generator).
- The 262 `.metal` files generated from the shipped `.jsl` files (50 Decora, 212 Prism; `US-022-macos-gensrc.md5`)
  compile: US-022's macOS module gate ran `cmake-build` and passed
  ([its record](US-022-report-jsl-errors-at-the-source-file-line.md#final-gates-2026-11-17-macos)).

#### The inputs
Each input is US-122's program with `<functions>` and `<body>` replaced by the lines given. Without functions the
`<functions>` line is left out. Each body line is indented by 4 spaces.
```
param sampler img;
<functions>
void main() {
    float4 val = sample(img, pos0);
    <body>
}
```
Each was compiled with the shaders only (`-d3d -es2 -mtl`) and compiles without an error: the JSL compiler writes the
`.hlsl`, `.frag` and `.metal`. Below, `<shader>` stands for the shader's name, and a position is the line and column
of the `.metal` file.

#### The Metal compiler rejects the shader (measured 2026-11-16 and 2026-11-17)
- **C3, a sampler in parentheses** (US-122's C3). `sample((img), pos0)` in place of `sample(img, pos0)`, body
  `color = val;`. The `.metal` has, at line 17,
  `float4 val = <shader>_SampleTexture((uniforms.img), in.texCoord0);`. The Metal compiler: `17:14: error: no
  matching function for call to '<shader>_SampleTexture'`, with the note `candidate function not viable: requires 3
  arguments, but 2 were provided` at the function's definition (7:8). The backend writes `sample` as a call of
  `<shader>_SampleTexture` and then inserts each texture's sampler by a text replacement that matches only
  `<shader>_SampleTexture(uniforms.<texture>` (`backend/hw/MSLBackend.java:342-346`), so `((uniforms.img)` gets no
  sampler.
- **C5, a vector argument of a vector constructor** (US-122's C5). Body
  `float4 c = float4(1.0, 2.0, 3.0, val.xy);`, `color = c;`. The `.metal` has the same text at line 18. The Metal
  compiler: `18:12: error: no matching constructor for initialization of 'float4' (vector of 4 'float' values)`, with
  the note `candidate constructor float4(float, float, float, float)`. `TreeMaker.vectorCtor` checks the number of
  arguments against the size of the type (`tree/TreeMaker.java:126-130`) and the base type of each argument
  (`:131-136`), not the number of components, so four arguments with five components pass.
- **O2, a vector `==` in an `if`, in a function that `main` does not call** (US-122's O2). Functions
  `float g(float x) {`, `    return x * 0.5;`, `}`, `float g(float2 x) {`, `    if (x == x) return 1.0;`,
  `    return 0.0;`, `}`; body `float y = g(val.r);`, `color = val * y;`. The `.metal` defines both functions and has,
  at line 17, `if (x == x)return 1.0;`. The Metal compiler: `17:5: error: value of type 'bool2' (vector of 2 'bool'
  values) is not contextually convertible to 'bool'`.
- **J07, a vector `==` in an `if`** (the line of US-022's Java-peer case J07). Body `float luminance = val.r;`,
  `if (val == val) luminance = 0.0;`, `color = val * luminance;`. The `.metal` has, at line 19,
  `if (val == val)luminance = 0.0;`. The Metal compiler: `19:5: error: value of type 'bool4' (vector of 4 'bool'
  values) is not contextually convertible to 'bool'`.
- **J06, the same with a call** (the line of US-022's Java-peer case J06; found while measuring). Body
  `float luminance = val.r;`, `if (abs(val) == val) luminance = 0.0;`, `color = val * luminance;`. The `.metal` has,
  at line 19, `if (abs(val) == val)luminance = 0.0;`. The Metal compiler: `19:5:` with J07's message.
- **O2, J07 and J06.** The front end gives every relational operator, `==` included, the type `bool`, also for vector
  operands (`tree/BinaryExpr.java:46-49`, under `// TODO: what about bool vector ops?`), so the `if` is accepted. The
  `.metal` keeps the condition as written, and in Metal `==` of two vectors is a vector of `bool`, as the messages
  say.

#### The HLSL and GLSL outputs
The `.hlsl` and `.frag` of the same programs have the same constructs: `tex2D((img), pos0)` and
`texture2D((img), texCoord0)` (C3), `float4(1.0, 2.0, 3.0, val.xy)` and `vec4(1.0, 2.0, 3.0, val.xy)` (C5), and the
lines `if (x == x)return 1.0;`, `if (val == val)luminance = 0.0;` and `if (abs(val) == val)luminance = 0.0;` (O2,
J07, J06). Nobody compiled them: neither with `fxc`, which the Windows build runs on every generated `.hlsl`
(`native/win.cmake:315-320`), nor with a GLSL compiler; the build compiles no `.frag`.

#### What US-022 changes and what it does not
US-022 gives every error that the compiler raises for its input a file, line and column. With the shaders only, none
of these inputs raises one, before or after US-022, so each still writes a `.metal` that the Metal compiler rejects.
- O2: US-022's final macOS gate (2026-11-17) compiled it with the shaders only on US-022's compiler without an error
  (US-122, O2), and its written files are md5-identical on both compilers (US-022's record,
  [Not fixed here](US-022-report-jsl-errors-at-the-source-file-line.md#not-fixed-here)). Its `.metal` from US-022's
  compiler has the md5 of the one compiled for this story.
- J06 and J07: US-022's `JavaPeerLimitTest` asserts that the shader-only compile of their lines, in a program of its
  own, writes `Probe.frag`, `Probe.hlsl` and `Probe.metal` (rows `a vector-valued call in a condition` and `a vector
  in a condition` of `aJavaPeerLimitIsAnErrorAtItsConstructAfterTheShadersOfAShaderOnlyCompileAreWritten`). That
  `Probe.metal` was not given to the Metal compiler. US-022's record names the Metal rejection of J07's `.metal`
  ([Known effects](US-022-report-jsl-errors-at-the-source-file-line.md#known-effects)).
- C3, C5, J06 and J07 were not compiled with US-022's compiler for this story.

#### Tests
No jslc test runs the Metal compiler (`src/test/jslc` searched for `xcrun`, `metal` as a command and `metallib`). No
jslc test compiles `sample((`, a vector constructor with a vector argument, or a vector `==` in an `if`, other than
the two `JavaPeerLimitTest` rows above.

#### How it was measured
- macOS 27.2.1 (26B131) arm64, outside Maven. Apple metal 32023.921 (`metalfe-32023.921.6`), through `xcrun`.
- 2026-11-16: JSL compiler classes built from `726d7f74a5` (`buildtools/jslc/target/classes` of a clone), JDK
  27.0.1.0.2. Drivers, for US-122 (C3, C5, O2) and for US-022's design review (J06, J07), called
  `JSLC.compile(JSLCInfo, String, long)` with the source time `Long.MAX_VALUE`, a new `JSLCInfo` per compile with
  `trimToOutDir`, and the output types `-d3d -es2 -mtl`, for each of the five inputs. For US-122, the Metal compiler
  was run by hand on the `.metal` of C3, C5, O2 and J07: `xcrun -sdk macosx metal -c`, with `-I` the directory of the
  `FragmentShaderCommon.h` that the build generates.
- 2026-11-17, for this story: the five `.metal` files of 2026-11-16, copied unchanged. Each was compiled twice: as on
  2026-11-16, and as the build runs the compiler (`-Wdeprecated -I <header directory> -std=macos-metal2.4 -c`). The
  header has the md5 that `US-022-macos-gensrc.md5` lists (`71534089ae138305abef49d129e11082`). All 10 runs exit 1
  and write no `.air`. Both ways, each message is the one quoted above, and for C3, C5, O2 and J07 the same as on
  2026-11-16 but for the file's path. J06's `.metal` was given to the Metal compiler for the first time.
- Not run: Windows, Linux, Maven, the generators, `fxc`, a GLSL compiler, `metallib`, and a shader at run time.

#### Upstream too
`openjdk/jfx` master at `f1bb639dab` (2026-11-16) has the same `tree/BinaryExpr.java` and `tree/TreeMaker.java`:
their git blob ids equal the fork's at `726d7f74a5` (read through the GitHub API on 2026-11-17). Its
`backend/hw/MSLBackend.java` differs from the fork's but inserts the sampler by the same text replacement (`:451`).
Upstream was not built or run, and JBS was not searched.

### Requirement
1. **The Metal shader compiles.** For every program that the JSL compiler accepts with `-mtl`, the `.metal` that it
   writes compiles with the Metal compiler as the macOS build runs it (`native/mac.cmake:227`, `:239`).
2. **Or an error of the input.** A program with a construct that the Metal shader cannot express is an error of the
   input, raised by the JSL compiler in the form US-022 gives every error of the input (file, line, column and
   program), at that construct, not a `.metal` that fails later.

### Constraints
- **Generated files unchanged.** The regeneration with `-d3d -es2 -mtl` outside Maven, as US-022 ran it
  (`backlog/US-022-generator-cases.md`), writes the same 1,059 files, identical by name and md5
  (`backlog/US-021-regeneration.md5`).
- **HLSL and GLSL unchanged.** For each input above that a compile without `-mtl` (`-d3d -es2`) still accepts, that
  compile writes the same `.hlsl` and `.frag` as today, byte for byte.
- **Existing tests.** The jslc tests that exist when the story is picked up pass unchanged, except those that compile
  C3, C5, O2, J06 or J07 with `-mtl` (the two `JavaPeerLimitTest` rows above, and US-122's if it is done first), which
  change only as far as this story's errors of the input or `.metal` text require.
- **Code rules.** Java 27 final features only, no `var`; a production file stays at most 900 lines (`wc -l`); lines
  at most 120 columns, one statement per line; comments only for a non-obvious "why".

### Acceptance criteria
1. **The five inputs.** A jslc test compiles C3, C5, O2, J06 and J07 with `-mtl` and with the shaders only
   (`-d3d -es2 -mtl`). Each either fails with an error of the input whose full message the test pins (the file, line
   and column of the construct, worked out by hand from the input, the program, and a text that names the
   construct), or writes a `.metal` whose line with the construct the test pins. Today the shader-only compile of each
   writes the line quoted above, which the Metal compiler rejects.
2. **The Metal compiler accepts what is written.** Each `.metal` that the compiles of criterion 1 write compiles with
   the Metal compiler as `native/mac.cmake` runs it (`-Wdeprecated`, `-I` the generated Metal headers,
   `-std=macos-metal2.4`), on macOS. The commands and their output are kept with the change.
3. **Mutant proof.** With the change undone in a scratch copy, the tests of criterion 1 fail. The repository version
   passes.
4. **The constraints hold.** The regeneration is md5-identical before and after, and so are the `.hlsl` and `.frag`
   of each of the five inputs that a `-d3d -es2` compile still accepts.
   `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test` passes on macOS, where it compiles every
   generated `.metal`, with `target/gensrc/{jsl-decora,jsl-prism,mtl-headers}` identical by name and md5 before and
   after and the same test counts per class as before plus the new tests. The platforms it ran on are recorded here.

### Out of scope
- What the Java software peer does with these inputs: US-122 (C3, C5) and US-022 (O2, J06, J07).
- A second function with the name and parameter types of an earlier one, whose `.metal` the Metal compiler also
  rejects (US-123's D1 and D2): US-123.
- Which file, line and column an error names: US-022.
- Whether `fxc` or a GLSL compiler accepts the `.hlsl` and `.frag` of these programs, which was not measured.

### Definition of Done
- All acceptance criteria met, with the logs and md5 lists of every run kept with the change and the results stated
  in this story.
- An independent review repeated criteria 1 and 2 with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

### Notes
- C3, C5, O2 and J07 were found on 2026-11-16, when the Metal compiler was run on the shaders of US-122's inputs and
  of US-022's case J07. US-122 records them and leaves them out of its scope. J06 was found while measuring for this
  story.
- The driver and the inputs were not kept in the repository. The inputs above describe each case fully enough to
  rebuild it.
- US-024 (amended 2026-11-21): another case of a `.metal` that the backend writes wrong. `MSLBackend.getVar` writes a
  variable as `VAR_MAP.getOrDefault(v, v)` (`backend/hw/MSLBackend.java:182-184`), and `VAR_MAP` is a fixed list of
  names (`:82-154`; lines of `38911806e8`, where the file is as with US-024's change). A parameter whose name is not in
  it is written without the `uniforms.` prefix, which is not valid MSL: in a probe of US-024's test rework, a program
  with `param float2 origin;` gave `in.texCoord0 - origin`. Read in the source by US-024's confirming review, seen in
  that probe's output; not compiled with the Metal compiler.
