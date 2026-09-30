# US-018 — Stop the jslc Metal backend depending on `jsl-` in the output path and on static state

**Status:** 📋 Ready (reproduced on Windows by calling the JSL compiler directly and by running the shader generators the way the pom runs them, outside Maven; no Maven build with `-mtl` and no macOS build run) · **Found:** 2026-09-27, US-008 (review of the jslc test port: `SymbolTest` would have passed for any program that parses, because the Metal backend threw)

## Story
As a maintainer who builds `javafx.graphics` on macOS, or who runs the JSL compiler from a test or a tool,
I want the Metal backend to write its headers to a directory the caller names, listing every shader of the run,
so that the checkout path, a build without `clean` or an earlier compile in the same JVM cannot leave the Metal
headers missing, incomplete or in the wrong directory, and so that the jslc tests can cover Metal output.

## Problem
Paths are relative to `modules/javafx.graphics` unless they start with `backlog/`; `build.gradle` is the
repository root's. The class is `src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/MSLBackend.java`. A bare
line number (`:NN`) refers to the repository file named in the same sentence, else to the one named in the lead-in of
its list, else to `MSLBackend.java`.

`JSLC.compile` creates one `MSLBackend` per shader and passes it the canonical path of the `.metal` output
(`JSLC.java:213-224`). Besides the `.metal` file, the backend writes three headers:
- `FragmentShaderCommon.h`, the `VS_OUTPUT` struct (`writeFragmentShaderHeader`, `:476-505`). Every generated
  `.metal` file includes it (`getHeader`, `:405`).
- `DecoraShaderCommon.h` or `PrismShaderCommon.h`, an Objective-C header. It holds each shader's uniform struct,
  argument IDs and `get<shader>_Uniform_VarID_Dict()`, and one `getDECORADict` or `getPRISMDict` that maps a shader
  name to its dictionary (`updateCommonHeaders`, `:311-399`). `src/main/native-prism-mtl/MetalShader.m` imports both
  headers (`:26-27`) and looks every fragment shader up with them (`:64-67`).

### The header directory is cut out of the output path
`setShaderNameAndHeaderPath` (`:464-474`), with `MTL_HEADERS_DIR = "/mtl-headers/"` (`:79`):
```java
if (headerFilesDir == null) {
    headerFilesDir = genMetalShaderPath.substring(0, genMetalShaderPath.indexOf("jsl-"));
    headerFilesDir += MTL_HEADERS_DIR;
    writeFragmentShaderHeader();
}
isPrismShader = genMetalShaderPath.contains("jsl-prism");
```
- **No `jsl-` in the path.** `indexOf` returns -1 and `substring` throws `StringIndexOutOfBoundsException`. The
  `.metal` file and every output `JSLC.compile` would write after it (the Java peers) are not written.
- **An earlier `jsl-` in the path.** The first match wins. Output under `<dir>/jsl-work/gensrc/jsl-decora` gets the
  header directory `<dir>/` + `/mtl-headers/`. Both header writers catch the `IOException`, print it and go on
  (`:500-504`, `:395-398`), so the process exits 0. A build whose checkout path contains `jsl-` above
  `target/gensrc` is in the same position.
- **Decora or Prism** is chosen by the path too: `isPrismShader` picks the header file name and its `DECORA` or
  `PRISM` names (`:312`, `:528-529`).

### The state is static, so the first compile in a JVM decides
- `headerFilesDir` (`:58`) is set only while it is `null`. The first Metal compile in a JVM fixes the header
  directory for every later one, and only that compile writes `FragmentShaderCommon.h`.
- `objCHeader` (`:60`) is a static `StringBuilder`. It collects the structs and dictionary functions of every shader
  compiled in the JVM. Its include guard and `VS_INPUT` typedef come from the first shader (`:329-346`).
- `shaderFunctionNameList` (`:64`) collects every shader name, and the `get…Dict` function lists them all
  (`:381-392`).
- `objCHeaderFileName` (`:61`) and `texSamplerMap` (`:85`) are static too, but are reset for each compile
  (`:528-529`, `:512`).
- After every shader, `getShader` rewrites the whole header file from `objCHeader` (`:427-429`, `:314`, `:379-394`).

So `DecoraShaderCommon.h` and `PrismShaderCommon.h` list the shaders this JVM compiled, not the shaders of the
build.

### How the build runs it
The `jsl-codegen` execution of `pom.xml` (`:118-249`):
- **Only macOS generates Metal.** `-mtl` comes from the profiles `jsl-backends-mac` (`-mtl -es2`, `:655-669`) and
  `jsl-backends-mac-noes2` (`-mtl`, `:673-687`). Windows gets `-d3d` or `-d3d -es2` (`:623-654`); Linux and any
  other system get `-es2` (`:48`).
- **Decora: one forked JVM.** `<java classname="GenAllDecoraShaders" fork="true">` (`pom.xml:156-179`) calls each
  compiler's `main` in that JVM (`src/main/jsl-decora/GenAllDecoraShaders.java:84-92`), with
  `-o target/gensrc/jsl-decora -t`.
- **Prism: one JVM per `.jsl` file.** `<apply parallel="false">` (`:200-216`) starts `CompileJSL` once for each of
  the 30 files in `src/main/jsl-prism`, with `-o target/gensrc/jsl-prism -t`. `CompileJSL.main` ignores the file
  name and compiles all 212 combinations every time (`src/main/jsl-prism/CompileJSL.java:467-508`).
- **Only out-of-date outputs are written.** An output is regenerated only when its source time is at least its own
  (`JSLC.outOfDate`, `JSLC.java:249-251`). A Decora shader's source time is the time of the `.jsl` file it is
  generated from (`JSLC.java:140-144`); for a blend mode it is the newer of `Blend.jsl` and its `Blend_<MODE>.jsl`
  (`src/main/jsl-decora/CompileBlend.java:46-54`). A Prism shader's is the newer of its mask and paint files
  (`src/main/jsl-prism/CompileJSL.java:406-408`). The
  execution itself has no up-to-date check, so a build without `clean` runs both generators over the existing
  `target/gensrc`.
- **Headers.** The pom creates `target/gensrc/mtl-headers` (`:137-140`). The macOS native build compiles each
  generated `.metal` file, and `prism_mtl`, with that directory on the include path (`native/mac.cmake:195-200`,
  `:245-253`).

### What that means
- **Windows and Linux builds:** not affected; `MSLBackend` never runs.
- **Clean macOS build: correct.** Each JVM writes to one output root. The first `jsl-` in
  `…/target/gensrc/jsl-decora/…` and `…/target/gensrc/jsl-prism/…` is that root, so the header directory is
  `target/gensrc/mtl-headers`. The Decora JVM compiles all 50 Decora shaders and the first Prism JVM all 212 Prism
  shaders, so both headers are complete. The other 29 Prism JVMs find every output up to date and write nothing.
  This holds only while no directory above `target/gensrc` contains `jsl-`.
- **macOS checkout under a path that contains `jsl-`:** the headers go to another directory, or nowhere, and
  `target/gensrc/mtl-headers` stays empty. The generators exit 0, so the build goes on to the native step.
- **macOS build without `clean` after a `.jsl` edit:** the JVM recompiles only the out-of-date shaders and rewrites
  the header from those alone. After an edit of `ColorAdjust.jsl`, `DecoraShaderCommon.h` lists 1 of the 50 Decora
  shaders. After an edit of `PaintColor.jsl`, `PrismShaderCommon.h` lists 28 of the 212 Prism shaders. This was
  reproduced by running the generators the pom's way outside Maven (see Measured), not by a Maven build or on macOS.
  For every other shader, `getDECORADict` and `getPRISMDict` return `nil` (the generated `return nil;`,
  `MSLBackend.java:390`), and that is what `MetalShader.m:64-67` gets if it is compiled against these headers. What
  the Metal pipeline then does was not tested.
- **The static header directory** is latent in the build, because no build JVM writes to two output roots. It
  affects any caller that compiles into two roots in one JVM: tests that share a surefire JVM, or a tool.
- **jslc tests:** no jslc test compiles Metal output. A test could do so only by writing under a `jsl-` directory
  with no `jsl-` above it, and only the first such compile in the surefire JVM would set the header directory.
  `SymbolTest` compiles with `OUT_ALL_PEERS | OUT_HW_SHADERS` into a JUnit `@TempDir`
  (`src/test/jslc/com/sun/scenario/effect/compiler/SymbolTest.java:40-52`). For any program that parses,
  `JSLC.compile` writes the D3D and ES2 files and then throws the `StringIndexOutOfBoundsException`, so
  `assertThrows(RuntimeException.class)` alone passed whatever the program was.
  - The test's invalid input fails earlier, while the tree is built: `TreeMaker.variable` throws "Unknown variable
    pos0" (`src/jslc/java/com/sun/scenario/effect/compiler/tree/TreeMaker.java:108`, called from
    `src/jslc/java/com/sun/scenario/effect/compiler/tree/JSLVisitor.java:190`). It is reached through
    `getParserInfo` (`JSLC.java:196`), before any backend runs.
  - US-008 therefore pinned that message (`SymbolTest.java:69`;
    `backlog/US-008-remove-dead-jslc-me-backend-and-simd.md:133-136`).

### Upstream too
- `MSLBackend.java` has one commit, `f0312b0e3d` (2025-08-11, "8271024: Implement macOS Metal Rendering Pipeline").
  No later commit touched it: neither the upstream commits up to `ca9b07aeae` (2026-08-26), the last upstream commit
  in this history, nor the fork's. It is not a fork regression.
- `openjdk/jfx` master has the same static fields and the same `setShaderNameAndHeaderPath` (checked 2026-09-27).
- Upstream's Gradle build, the root `build.gradle` at `ca9b07aeae`, ran the generators in the same JVMs: it created
  `gensrc/mtl-headers` (`:1702`), ran `GenAllDecoraShaders` in one `javaexec` (`:2485-2486`) and ran `CompileJSL` in
  one `javaexec` per Prism `.jsl` file (`:2589-2593`), with `-mtl` on macOS. Unlike the Maven execution, its task had
  an up-to-date check on the source directory `src/main/jsl-<name>` and the output directory `gensrc/jsl-<name>`
  (`build.gradle:1708-1709`), so it reran only after a change there, such as a `.jsl` edit. Whether an upstream
  macOS build without `clean` truncates the headers too was not checked.

### Measured (2026-09-27, Windows 10, JDK 26)
**Setup.**
- The JSL compiler was compiled from `src/jslc/java`, with the parser generated by ANTLR 4.7.2 from
  `src/jslc/antlr/com/sun/scenario/effect/compiler/JSL.g4` and the templates from `src/jslc/resources`.
- The generators were compiled from copies of `src/main/jsl-decora` (against a `javafx.graphics` build) and
  `src/main/jsl-prism`.
- They were run as the pom runs them: `GenAllDecoraShaders` in one JVM and `CompileJSL` once per Prism `.jsl` file.
  Each got `-i <copy> -o <root>/target/gensrc/jsl-decora` (or `jsl-prism`) `-t -pkg <pkg> -d3d -es2 -mtl`, and
  `<root>/target/gensrc/mtl-headers` was created first.

**Generator runs.**
- **Clean:** `jsl-decora` holds 208 files (50 `.metal`), `jsl-prism` 848 (212 `.metal`) and `mtl-headers` 3.
  `DecoraShaderCommon.h` defines 50 `…_Uniform_VarID_Dict()` functions and `PrismShaderCommon.h` 212. All 262
  `.metal` files include `FragmentShaderCommon.h`.
- **Again, nothing changed:** no file is rewritten, and all 1,059 files are identical by md5.
- **Again, after touching the copies of `ColorAdjust.jsl` and `PaintColor.jsl`:** 5 Decora files, 112 Prism files
  and the 3 headers are rewritten. `DecoraShaderCommon.h` now lists 1 shader (`ColorAdjust`). `PrismShaderCommon.h`
  lists 28: the 14 `<mask>_Color` shaders, with and without `_AlphaTest`.

**Direct compiles.** A small driver calls `JSLC.compile(jslcinfo, source, Long.MAX_VALUE)` with
`outTypes = OUT_MTL` and `trimToOutDir = true`, on this program:
```
param sampler baseImg;
param float2 offset;
void main() {
    float val = sample(baseImg, pos0 - offset).a;
    color = float4(1.0 - val);
}
```
Each case runs in a new JVM:
- **`outDir = <tmp>/gensrc/out`:** `StringIndexOutOfBoundsException: Range [0, -1) out of bounds for length
  <path length>`, at `MSLBackend.setShaderNameAndHeaderPath(MSLBackend.java:468)`.
- **`SymbolTest`'s settings** (`OUT_ALL_PEERS | OUT_HW_SHADERS`, no `trimToOutDir`, a temp directory): `Foo.hlsl`
  and `Foo.frag` are written, then the same exception is thrown. There is no `.metal` file and no Java peer.
- **`outDir = <tmp>/jsl-work/gensrc/jsl-decora`, with `<tmp>/jsl-work/gensrc/mtl-headers` present:** a
  `FileNotFoundException` for `<tmp>/mtl-headers/FragmentShaderCommon.h` and one for
  `<tmp>/mtl-headers/DecoraShaderCommon.h` are printed. The `.metal` file is written, the process exits 0, and
  `jsl-work/gensrc/mtl-headers` stays empty.
- **Two roots in one JVM,** `<tmp>/A/gensrc/jsl-decora` (`ProbeA`) then `<tmp>/B/gensrc/jsl-decora` (`ProbeB`):
  A's `DecoraShaderCommon.h` defines both shaders and its `getDECORADict` lists both. `B/gensrc/mtl-headers` stays
  empty.
- **Decora then Prism in one JVM,** `<tmp>/C/gensrc/jsl-decora` (`ProbeD`) then `<tmp>/C/gensrc/jsl-prism`
  (`ProbeP`): `PrismShaderCommon.h` opens with `#ifndef DECORA_SHADER_COMMON_H` and defines `DECORA_VS_INPUT`. Its
  `getPRISMDict` lists `ProbeD` as well as `ProbeP`.

## Proposed fix
- **Header directory from `JSLCInfo`.** Add a Metal header directory to `JSLCInfo`, with a command-line option, and
  pass `${jfx.graphics.gensrc}/mtl-headers` from both generator invocations in the pom. Without one, use
  `mtl-headers` inside `outDir`, so that a caller who names only `outDir` (a test's `@TempDir`) gets everything under
  it. Create the directory the way `JSLC.write` creates output directories (`JSLC.java:253-257`).
- **Decora or Prism from `JSLCInfo`,** for example as part of the same setting, not from `jsl-prism` in the path.
- **Decision: a path without `jsl-` works; it does not fail with a clearer message.**
  - `JSLCInfo` already describes every output location (`outDir`, `trimToOutDir`, `outNameMap`, `pkgName`). The
    `jsl-` rule is a second, hidden contract copied from the build's directory names.
  - The rule also misfires on a path with an earlier `jsl-`. A version that fails clearly would still need the
    directory from somewhere else.
  - A failure would make every Metal test write under a `jsl-` directory of its `@TempDir` and depend on the
    temporary path above it containing no `jsl-`. The new tests should need neither.
- **Per-run header state.** Replace the static fields with one object per generator run, for example held by the
  `JSLCInfo`, which collects the header entries and writes the headers. No static field of `MSLBackend` holds
  per-compile or per-run data.
- **Complete headers on incremental runs.** The headers must list every shader of the run, including those whose
  `.metal` file is up to date. Possible ways:
  - compute the header entries for every shader of the run and write the headers from all of them;
  - keep one header fragment per shader and assemble the headers from all fragments.

  Either way leaves the `.metal` files of unchanged shaders untouched, as the acceptance criteria require.
  Regenerating every Metal output of the run when one is out of date would not: one edit would recompile all 50
  Decora or all 212 Prism shaders. Prism's later JVMs must not overwrite the headers with fewer entries; writing a
  header only when its content changes also keeps its time stamp.
- **Fail on write errors.** A header that cannot be written throws, so the generator exits non-zero and the build
  stops.

## Acceptance criteria
- **Header directory per compile.** The Metal header directory and the Decora/Prism choice come from `JSLCInfo`.
  `git grep -n -e 'indexOf("jsl-")' -e '"jsl-prism"' -- modules/javafx.graphics/src/jslc` finds nothing, and
  `MSLBackend` has no static field that holds per-compile or per-run data.
- **A path without `jsl-` works** (decided above). A new jslc test compiles a valid program with
  `OUT_ALL_PEERS | OUT_HW_SHADERS` into a JUnit `@TempDir` whose path has no `jsl-`. It asserts:
  - the `.metal` file and the Java peers;
  - `FragmentShaderCommon.h` and `DecoraShaderCommon.h` in the configured header directory;
  - the shader's dictionary function and its entry in `getDECORADict`.

  It fails before the fix with the `StringIndexOutOfBoundsException`.
- **No carry-over between compiles.** Tests that each fail before the fix (see Measured) and pass after:
  - two compiles in one JVM into two output roots each get their own complete headers;
  - a Decora compile followed by a Prism compile in one JVM gives a `PrismShaderCommon.h` with the `PRISM` guard and
    `PRISM_VS_INPUT` that lists only the Prism shader;
  - output under `<dir>/jsl-work/gensrc/jsl-decora`, with the header directory set to
    `<dir>/jsl-work/gensrc/mtl-headers`, puts the headers there.
- **Write errors fail.** A header directory that cannot be created or written fails the compile with a message that
  names the path, and `GenAllDecoraShaders` and `CompileJSL` exit non-zero.
- **Incremental runs keep complete headers.** Regenerate as the pom does, with `-mtl`. Then touch one Decora and one
  Prism `.jsl` file and regenerate over the same output.
  - The headers are byte-identical to those of a clean regeneration. Today, after `ColorAdjust.jsl` and
    `PaintColor.jsl`, they list 1 of 50 and 28 of 212 shaders.
  - As today, a rerun with nothing changed rewrites no file, and the outputs of unchanged shaders are not rewritten.
  - A jslc test covers it. One run compiles two shaders into a `@TempDir`. A second run over the same output, in
    which only one shader is out of date, leaves headers that list both and are byte-identical to the first run's.
    It fails before the fix; with its output under a `jsl-decora` directory it still fails, on the headers rather
    than on the `StringIndexOutOfBoundsException`.
  - Repeat this once under Maven without `clean`: on Windows with `"-Djfx.jsl.backends=-d3d -mtl"` (only the macOS
    profiles pass `-mtl`), and on a macOS host if one is available. Compare `target/gensrc/mtl-headers` with that of
    a clean build.
- **Generated files unchanged.** Regenerate Decora and Prism with `-d3d -es2 -mtl` before and after the fix, as the
  US-008 review did.
  - Every file in `jsl-decora`, `jsl-prism` and `mtl-headers` is identical by name and md5. The regeneration under
    Measured has 208, 848 and 3 files.
  - The headers are in `target/gensrc/mtl-headers` under the same three names.
- **Build gate,** as the US-008 gate ran: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test` on
  Windows with `-Djfx.parity.require=true`, and on Linux (WSL) from a fresh clone with `-am`, no display and without
  that property (the Linux font goldens were captured on another machine, and it would turn their skips into failures).
  - `javafx.graphics` has the same test counts per class as before.
  - `javafx-jslc` runs its current tests plus the new ones, with no failure. `SymbolTest` keeps its message check.
  - `target/gensrc` is identical by name and md5, as in the US-008 gate.
- **macOS.** On a macOS host, a clean build and a build without `clean` after a `.jsl` edit both leave complete
  headers in `target/gensrc/mtl-headers`, and `prism_mtl` and `jfxshaders.metallib` build. Without a macOS host,
  record that this was not run; the md5 identity above is then the gate for the clean build.
- **Upstream.** Draft the upstream report from the Measured cases. Check whether an upstream build without `clean`
  truncates the headers too, on a macOS host or by running upstream's generators as its root `build.gradle` does,
  with `-mtl`.

## Notes
- US-008's review found this, and US-008 filed it here instead of fixing it
  (`backlog/US-008-remove-dead-jslc-me-backend-and-simd.md:241-243`): the fix changes what the compiler does, and
  US-008 changed nothing it generates. The build's paths do always contain `jsl-`, but the rule takes the first
  `jsl-` in the whole path, and the static header state also breaks builds without `clean`.
- The probe driver and the generator runs were not kept in the repository. The Measured section describes each
  repro fully enough to rebuild it.
- The US-008 review reported 210 Decora and 850 Prism files
  (`backlog/US-008-remove-dead-jslc-me-backend-and-simd.md:93-94`). Its counts include the two headers each
  generator writes (`FragmentShaderCommon.h` and that generator's `…ShaderCommon.h`). Without them, they are the 208
  and 848 files of the regeneration above, identical by md5, and the headers are identical too.
- `prism_mtl` itself (`MetalShader.m`) belongs to the macOS half of US-001. This story changes only the build-time
  compiler, the pom and the jslc tests, and needs a macOS host only for the final native check.
