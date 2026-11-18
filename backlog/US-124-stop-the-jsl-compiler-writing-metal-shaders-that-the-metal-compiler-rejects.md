# US-124 — Stop the JSL compiler writing Metal shaders that the Metal compiler rejects

**Status:** 📋 Ready (filed 2026-11-17 from US-022) ·
**Found:** 2026-11-16, while US-122 was measured (its C3, C5 and O2, and the line of US-022's Java-peer case J07);
measured again for this story on 2026-11-17, when J06 was found

## Story
As a developer who edits a `.jsl` shader,
I want the JSL compiler to write a Metal shader that the Metal compiler compiles, or else to stop with an error that
names the file, line and column of what the Metal shader cannot express,
so that a program that the JSL compiler accepts does not fail later, only in the macOS build, at a line of a generated
file.

## Problem
Paths are relative to `modules/javafx.graphics`. The compiler's files are under
`src/jslc/java/com/sun/scenario/effect/compiler`. Line numbers are those of `726d7f74a5`.

### What the build does with a `.metal` file
- The `jsl-codegen` execution of `pom.xml` (`:114`, phase `compile`) runs the Decora and Prism generators with
  `${jfx.jsl.backends}`: `-mtl -es2` on macOS (`:645`), `-mtl` there with `-DINCLUDE_ES2=false` (`:663`), `-d3d` or
  `-d3d -es2` on Windows (`:613`, `:630`) and `-es2` elsewhere (`:48`). Only a macOS build writes `.metal` files.
  (Amended 2026-11-17: these four lines are those of US-023's change, 7 lower than at `726d7f74a5`.)
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

### The inputs
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

### The Metal compiler rejects the shader (measured 2026-11-16 and 2026-11-17)
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

### The HLSL and GLSL outputs
The `.hlsl` and `.frag` of the same programs have the same constructs: `tex2D((img), pos0)` and
`texture2D((img), texCoord0)` (C3), `float4(1.0, 2.0, 3.0, val.xy)` and `vec4(1.0, 2.0, 3.0, val.xy)` (C5), and the
lines `if (x == x)return 1.0;`, `if (val == val)luminance = 0.0;` and `if (abs(val) == val)luminance = 0.0;` (O2,
J07, J06). Nobody compiled them: neither with `fxc`, which the Windows build runs on every generated `.hlsl`
(`native/win.cmake:315-320`), nor with a GLSL compiler; the build compiles no `.frag`.

### What US-022 changes and what it does not
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

### Tests
No jslc test runs the Metal compiler (`src/test/jslc` searched for `xcrun`, `metal` as a command and `metallib`). No
jslc test compiles `sample((`, a vector constructor with a vector argument, or a vector `==` in an `if`, other than
the two `JavaPeerLimitTest` rows above.

### How it was measured
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

### Upstream too
`openjdk/jfx` master at `f1bb639dab` (2026-11-16) has the same `tree/BinaryExpr.java` and `tree/TreeMaker.java`:
their git blob ids equal the fork's at `726d7f74a5` (read through the GitHub API on 2026-11-17). Its
`backend/hw/MSLBackend.java` differs from the fork's but inserts the sampler by the same text replacement (`:451`).
Upstream was not built or run, and JBS was not searched.

## Requirement
1. **The Metal shader compiles.** For every program that the JSL compiler accepts with `-mtl`, the `.metal` that it
   writes compiles with the Metal compiler as the macOS build runs it (`native/mac.cmake:227`, `:239`).
2. **Or an error of the input.** A program with a construct that the Metal shader cannot express is an error of the
   input, raised by the JSL compiler in the form US-022 gives every error of the input (file, line, column and
   program), at that construct, not a `.metal` that fails later.

## Constraints
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

## Acceptance criteria
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

## Out of scope
- What the Java software peer does with these inputs: US-122 (C3, C5) and US-022 (O2, J06, J07).
- A second function with the name and parameter types of an earlier one, whose `.metal` the Metal compiler also
  rejects (US-123's D1 and D2): US-123.
- Which file, line and column an error names: US-022.
- Whether `fxc` or a GLSL compiler accepts the `.hlsl` and `.frag` of these programs, which was not measured.

## Definition of Done
- All acceptance criteria met, with the logs and md5 lists of every run kept with the change and the results stated
  in this story.
- An independent review repeated criteria 1 and 2 with its own inputs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

## Notes
- C3, C5, O2 and J07 were found on 2026-11-16, when the Metal compiler was run on the shaders of US-122's inputs and
  of US-022's case J07. US-122 records them and leaves them out of its scope. J06 was found while measuring for this
  story.
- The driver and the inputs were not kept in the repository. The inputs above describe each case fully enough to
  rebuild it.
