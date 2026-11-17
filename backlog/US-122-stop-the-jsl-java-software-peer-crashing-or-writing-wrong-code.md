# US-122 — Stop the Java software peer of the JSL compiler crashing or writing wrong code

**Status:** 📋 Ready (filed 2026-11-16 from US-022) ·
**Found:** 2026-11-16, the design review of US-022, measured again for this story the same day; W3, N1 and N2 found
while measuring

## Story
As a developer who edits one of the Decora `.jsl` shaders that the build also compiles to a Java software peer,
I want the JSL compiler to write a peer that computes what the program says, or else to stop with an error that names
the file, line and column of what the peer cannot express,
so that an edit that the shader backends compile neither crashes the compiler with a JVM exception nor builds a peer
that computes something else or does not compile.

## Problem
Paths are relative to `modules/javafx.graphics`. The compiler's files are under
`src/jslc/java/com/sun/scenario/effect/compiler`, those of its Java backend under `backend/sw/java` there. Line numbers
are those of `726d7f74a5`.

### The Java software peer
- The build compiles 8 of the 10 Decora shaders with `-java` (`src/main/jsl-decora/GenAllDecoraShaders.java:56`,
  `:67-75`): `ColorAdjust`, `Brightpass`, `SepiaTone`, `PerspectiveTransform`, `DisplacementMap`, `InvertMask`, `Blend`
  (19 programs) and `PhongLighting` (3). `JSLC.compile` writes a program's HLSL, GLSL and Metal shaders first and its
  Java peer after them (`JSLC.java:209-260`). The pom's second-pass `javac` compiles the peers with the module
  (`pom.xml:206-228`). The Prism programs and `LinearConvolve*` are compiled without `-java`.
- The Java backend writes Java statement by statement (`JSWTreeScanner`) and inlines each call where it is used
  (`JSWCallScanner.visitCallExpr`, `JSWCallScanner.java:146-250`): a core function from the table of `JSWFuncImpls`
  (`JSWFuncImpls.java:52-133`), a user function by scanning its body again (`JSWCallScanner.java:242-245`). The code
  that computes a call's result, its preamble, is written only before an expression statement and before a
  declaration (`JSWTreeScanner.java:142`, `:273`).

### What US-022 changes and what it does not
This story was filed from US-022 on 2026-11-16. US-022 gives every error that the compiler raises for its input a file,
line and column, among them the 7 `InternalError`s of the Java backend that an input can reach. The inputs below raise
no such error: each crashes the compiler with a JVM exception, or compiles without an error. US-022 leaves them out of
its scope (its record,
[Not fixed here](US-022-report-jsl-errors-at-the-source-file-line.md#not-fixed-here)), so after US-022 each behaves
exactly as before it: the same exception without a location, or the same files. The one exception is O2, below.

### The inputs
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

### The compiler crashes (measured 2026-11-16)
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

### The peer is wrong or does not compile (measured 2026-11-16)
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

### The shader outputs
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

### The shipped shaders
None of the inputs above is in a shipped file. Of the 57 `.jsl` files of `src/main/jsl-decora` and
`src/main/jsl-prism`, none has `;;`, `any`, `ddx`, `ddy`, `bool` or `sample((`, or a field selected from a vector
constructor; `length` is only in a comment (`src/main/jsl-prism/MaskDrawEllipse.jsl:44`); and no program has two
functions of one name. In the 8 shaders compiled with `-java`, outside glue blocks, no `if` condition and no `return`
calls a function (a vector constructor such as the `float3(h, s, b)` of `ColorAdjust.jsl:85` is not a call), and
there is no `while`; `Blend_ADD.jsl:73-76` calls `max` before its `return`, as above.

### How it was measured
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

### Upstream too
`openjdk/jfx` master at `f1bb639dab` (2026-11-16) has the same `JSWTreeScanner.java`, `JSWCallScanner.java`,
`JSWBackend.java`, `JSWFuncImpls.java` and `CoreSymbols.java`: their git blob ids equal the fork's at `726d7f74a5`
(read through the GitHub API). Upstream was not built or run, and JBS was not searched.

## Requirement
1. **No crash.** Compiling with `-java` a program that the JSL compiler accepts never ends in an exception or error of
   the JVM thrown by the Java backend: no `NullPointerException`, `ClassCastException`, index exception,
   `StackOverflowError` or `InternalError`.
2. **A right peer or an error of the input.** For each such program the compiler either writes a Java peer that
   `javac` compiles and that computes what the program says, or stops with an error of the input, in the form US-022
   gives every error of the input (file, line, column and program), that names the construct the Java software peer
   cannot express and says that the peer (`-java`) does not support it.
3. **An error names code the program runs.** An error of the Java peer names a construct in the code that the
   program's `main` reaches, not one in a function it does not call.

## Constraints
- **Generated files unchanged.** The regeneration with `-d3d -es2 -mtl` outside Maven, as US-022 ran it
  (`backlog/US-022-generator-cases.md`), writes the same 1,059 files, identical by name and md5
  (`backlog/US-021-regeneration.md5`).
- **Shaders unchanged.** For each input above, the shader-only compile writes the same `.hlsl`, `.frag` and `.metal`
  as today, byte for byte, and a program that compiles with the shaders only today still does.
- **Existing tests.** The jslc tests that exist when the story is picked up pass unchanged.
- **Code rules.** Java 27 final features only, no `var`; a production file stays at most 900 lines (`wc -l`); lines
  at most 120 columns, one statement per line; comments only for a non-obvious "why".

## Acceptance criteria
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

## Out of scope
- The 7 `InternalError` sites of US-022's story and the location of every error the compiler raises: US-022.
- The conditional operator `?:` and a second function with the name and parameter types of an earlier one: US-123.
- The Metal compiler's rejection of the `.metal` of C3, C5, O2 and US-022's case J07, which the shader backends
  write: [US-124](US-124-stop-the-jsl-compiler-writing-metal-shaders-that-the-metal-compiler-rejects.md).

## Definition of Done
- All acceptance criteria met, with the logs and md5 lists of every run kept with the change and the results stated
  in this story.
- An independent review repeated criteria 1 to 3 with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

## Notes
- Found by the design review of US-022 (2026-11-16): C1 to C6, O1, O2, W1, W2, W4 and W5. US-022 put them out of its
  scope and filed this story. W3, N1 and N2 were found while measuring for this story.
- `Blend_ADD.jsl:73-74` calls W4 "a JSL parsing limitation". The parser accepts the `return`; the Java backend writes a
  peer that does not compile.
- The driver and the edited inputs were not kept in the repository. The inputs above describe each case fully enough
  to rebuild it.
