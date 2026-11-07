# US-018 — Stop the jslc Metal backend depending on `jsl-` in the output path and on static state

**Status:** 🔷 Implemented (2026-11-05) in `a8d6688172` (PR 50) and `ba2b68e991` (the fix for the first PR 50 review round), with the fix for the second review round uncommitted · **Found:** 2026-09-27, US-008 (review of the jslc test port: `SymbolTest` would have passed for any program that parses, because the Metal backend threw)

## Story
As a maintainer who builds `javafx.graphics` on macOS, or who runs the JSL compiler from a test or a tool,
I want the Metal backend to write its headers to a directory the caller names, listing every shader of the run,
so that the checkout path, a build without `clean` or an earlier compile in the same JVM cannot leave the Metal
headers missing, incomplete or in the wrong directory, and so that the jslc tests can cover Metal output.

## Problem
Paths are relative to `modules/javafx.graphics` unless they start with `backlog/`; `build.gradle` is the
repository root's. The class is `src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/MSLBackend.java`. A bare
line number (`:NN`) refers to the repository file named in the same sentence, else to the one named in the lead-in of
its list, else to `MSLBackend.java`. This section describes the tree before the fix, with the line numbers of
`b5b00d4416`, the commit that filed the story, unless a sentence names another commit;
[Implementation](#implementation-2026-11-05-macos) records the fix.

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
  reproduced by running the generators the pom's way outside Maven (see Measured), and on 2026-11-05 by a Maven build
  on macOS. For every other shader, `getDECORADict` and `getPRISMDict` return `nil` (the generated `return nil;`,
  `MSLBackend.java:390`), and that is what `MetalShader.m:64-67` gets if it is compiled against these headers. In that
  macOS build the 233 shaders missing from the headers got no uniform table, and 5 `MTL*` tests failed (see
  [Final gates](#final-gates-2026-11-05-macos)).
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
  in this history, nor the fork's before this story. It is not a fork regression.
- `openjdk/jfx` master has the same static fields and the same `setShaderNameAndHeaderPath` (checked 2026-09-27).
- Upstream's Gradle build, the root `build.gradle` at `ca9b07aeae`, ran the generators in the same JVMs: it created
  `gensrc/mtl-headers` (`:1702`), ran `GenAllDecoraShaders` in one `javaexec` (`:2485-2486`) and ran `CompileJSL` in
  one `javaexec` per Prism `.jsl` file (`:2589-2593`), with `-mtl` on macOS. Unlike the Maven execution, its task had
  an up-to-date check on the source directory `src/main/jsl-<name>` and the output directory `gensrc/jsl-<name>`
  (`build.gradle:1708-1709`), so it reran only after a change there, such as a `.jsl` edit. Upstream truncates the
  headers too: `ca9b07aeae`'s generators, run as its `build.gradle` runs them on macOS, leave 1 of 50 Decora and 28 of
  212 Prism shaders in the headers after `ColorAdjust.jsl` and `PaintColor.jsl` change (2026-11-05, without Gradle;
  see [Upstream check](#upstream-check-2026-11-05-macos)). The draft upstream report is kept outside the repository
  until it is filed.

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
  - The headers are byte-identical to those of a clean regeneration. Before the fix, after `ColorAdjust.jsl` and
    `PaintColor.jsl`, they list 1 of 50 and 28 of 212 shaders.
  - As before the fix, a rerun with nothing changed rewrites no file, and the outputs of unchanged shaders are not
    rewritten.
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

## Definition of Done
- All acceptance criteria met, the Windows and WSL gates included.
- The upstream report filed; until then its draft is kept outside the repository.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

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
- `prism_mtl` itself (`MetalShader.m`) belongs to US-001b, the macOS half of US-001. This story changes only the
  build-time compiler and its shader generators, the pom and the jslc tests, and needs a macOS host only for the final
  native check.

## Implementation (2026-11-05, macOS)
The story's change is commit `a8d6688172` on the PR 50 branch, made on `0281b2fde8`. The fix for the first PR 50
review round is commit `ba2b68e991`; the fix for the second round is uncommitted at the time of writing. This section
describes the final state, `ba2b68e991` with the round-2 fix. A result dated 2026-11-05 was taken when the story was
written, one dated 2026-11-06 on the content of `ba2b68e991` before it was committed, and one dated 2026-11-07 on the
round-2 fix. The last change of that fix removed a set of uniform names from `MSLBackend`, whose `add` always returned
true, since the JSL symbol table rejects a second declaration of a name. Of the results dated 2026-11-07, F1, F2, the
jslc suite in default and random method order and the clean generator run outside Maven were taken on the final
state, after that removal. The others were taken just before it, and those of the fix itself on what this story calls
the round-2 tree. The removal cannot change their outputs: F1's per-class lines, generated files and natives and that
clean run are the same as the round-2 tree's. Below, HEAD is `0281b2fde8`, as when the story was written. Everything
was built and tested on one arm64 Mac with Apache Maven 3.10.0 and Oracle JDK 27.0.1: on macOS 27.0.1 for the results
dated 2026-11-05 and 2026-11-06, and on macOS 27.2 (build 26B123) for those dated 2026-11-07 (see
[F3](#final-gates-2026-11-05-macos)). This host has no Windows and no Linux, so those gates are
[owner-run](#owner-run-and-pending). They expect the final state, so their `<US-018 commit>` must hold the round-2 fix:
`ba2b68e991` alone runs 190 jslc tests, with `MetalHeadersTest` at 27, and `a8d6688172` 182 and 19, where the gates
expect 196 and 33. Until a commit with the round-2 fix is pushed, F8.1 to F8.3 run on the patch that the owner-run
recipe makes from the working tree.

### Change
Relative to `0281b2fde8`:
- `JSLCInfo` has a Metal header directory (`metalHeaderDir`, option `-mtlheaders <dir>`; by default `mtl-headers` in
  `outDir`, or in the working directory without one), a kind (`metalHeaderKind`, `DECORA` unless `-mtlprism`; the
  Prism `CompileJSL` sets `PRISM` in code) and the run's headers (`metalHeaders`). Both generator invocations of the
  pom pass `-mtlheaders ${jfx.graphics.gensrc}/mtl-headers`, and its `<mkdir>` of that directory is gone. The headers
  go to the directory the file system resolves the path to, as every other output does: the path is not normalized,
  and two paths that resolve to one directory name the same header directory in one run.
- The new `backend/hw/MetalHeaders.java` holds the headers of one generator run. `JSLC.compile` adds every Metal
  shader of the run to it, compiled or up to date. A shader's Objective-C header entry (the argument buffer ID enum,
  the uniform struct with Objective-C types and the uniform dictionary function) comes from the
  `typedef struct <Shader>_Uniforms { … } <Shader>_Uniforms;` block that `MSLBackend` writes into the `.metal`: from
  the text just written for a compiled shader, and from the `.metal` file for an up-to-date one. So the program of an
  up-to-date shader is not parsed, and the header matches the `.metal` files the Metal library is built from,
  whichever run or output root wrote the header before. The exception is an up-to-date `.metal` whose struct may hold
  a member that an earlier compiler renamed: that shader is compiled again (review round 2 below).
  `GenAllDecoraShaders` shares one run among the drivers it calls.
- An up-to-date `.metal` is read only when it starts exactly as `MSLBackend` writes it: the line
  `#include "FragmentShaderCommon.h"`, an empty line and `typedef struct <Shader>_Uniforms {`. The struct runs to the
  first `} <Shader>_Uniforms;` line. A struct member is read only in the form `MSLBackend` writes: four spaces, an
  optional precision, a type, the uniform's JSL identifier (`$` included), an optional array size and `;`. Any other
  start, a cut struct or a member in any other form, as a hand edit can give, fails the run with `Cannot read
  <Shader>_Uniforms from the Metal shader <path>`. The 262 shaders of the build all pass this check.
- `MetalHeaders.write()` runs once at the end of a run: in `JSLC.main`, `GenAllDecoraShaders`, the Prism `CompileJSL`
  and the `main` of every Decora driver, so a driver run by hand writes its headers too. It creates the directory and
  writes `FragmentShaderCommon.h` and the Objective-C header, which it assembles from every shader's entry on every
  run, each only when its bytes differ, so an unchanged header keeps its time. A directory that cannot be created or a
  header that cannot be written throws an `IOException` that names the path. A run without Metal output writes
  nothing, so a build without `-mtl` (Windows, Linux) no longer has an empty `target/gensrc/mtl-headers`.
- `MSLBackend` writes only the `.metal` file and keeps no per-compile or per-run data in static fields. Its static
  fields are `MAIN` and four immutable maps, all `static final`. Its `float2`, `float3` and `float4` rewrites match the
  type with the space after it, and it renames a helper function or `sampleTex` only where it is called or defined, so
  every struct member keeps its uniform's JSL name, the name the Java peer sets. Every uniform the program declares
  is in the struct.
- Every changed production file is under 900 lines; the largest, `src/main/jsl-prism/CompileJSL.java`, has 565.
  `MetalHeaders.java` has 403, `MSLBackend.java` 356 and `JSLC.java` 473.

**The PR 50 review, round 1,** relative to `a8d6688172` (commit `ba2b68e991`):
- **The review comment** (Copilot, on `MetalHeaders.java:139`, `listsThisRun`): compile `Foo` with other uniforms
  into roots A and B that share a header directory, then rerun A with its `.metal` up to date. The story commit did
  not assemble a header again when no shader was compiled and the file started with the kind's prologue and ended with
  the run's `get<KIND>Dict`. So A kept B's uniform IDs and dictionary, and every run succeeded. Now `write()` always
  assembles the header from A's `.metal` files, and the rerun writes A's header.
  `aHeaderThatListsTheSameShadersWithTheirUniformsInAnotherOrderIsWrittenAgain` covers it with the two uniforms of one
  shader in swapped order, so that both headers have the same length and only their bytes differ. The same case run
  through `JSLC.main` and through `GenAllDecoraShaders` restores A's header and rewrites none of A's outputs
  ([Generator runs](#generator-runs)).
- **Removed:** that names check, the flag "a shader of the run was compiled", the lazy entries (`addCompiled`,
  `addUpToDate`), `JSLC`'s `Source` class and `MSLBackend.getObjCHeaderEntry`. `JSLC.compile` parses its program as
  `0281b2fde8` does, at the first output that is out of date.
- **Closed:** [risk R1](#risk-r1-closed-a-stale-entry-after-an-interrupted-run).
- **From the review rounds** of the fix ([Mutation check](#tests)): the strict member parser and its `$`; the header
  directory resolved by the file system, where `normalize()` had dropped a `link/..` as text; the anchored type
  rewrites and the call-only renames of `MSLBackend`; and the duplicate-shader message "was added twice in one run".
- **Cost:** a rerun with nothing changed reads and parses every `.metal` file of the run, 212 in each Prism JVM, and
  takes 7.94 ms more per JVM than `a8d6688172` ([Generator runs](#generator-runs)).
- **Three contrived programs** that no build shader resembles get a different `.metal` than `0281b2fde8` writes: a
  uniform named like a helper function, a uniform named `sampleTex` beside a sampler, and uniforms whose names start
  with `float2`, `float3` or `float4` keep their JSL names in the struct, where `0281b2fde8` renamed them. Their header
  IDs and dictionary keys are the JSL names on all three trees; in the header only the typedef members differ, which
  no native code uses. The 1,059 files the build generates are unchanged. That holds for a clean run: over an
  up-to-date `.metal` that `0281b2fde8` or `a8d6688172` wrote for such a program, `ba2b68e991` keys the renamed member
  (C1 of round 2).

**The PR 50 review, round 2,** relative to `ba2b68e991` (Copilot, 2026-11-07; uncommitted):
- **C2, a commented-out struct** (on `MetalHeaders.java:148-149`). `ba2b68e991` looked for
  `typedef struct <Shader>_Uniforms {` anywhere in an up-to-date `.metal`. With the old struct in a block comment
  before the active one, it read the commented layout, so a reordering of the active uniforms left stale argument IDs
  in the header, and the run succeeded. Now `MetalHeaders` reads a `.metal` only when it starts with the generated
  include line, empty line and typedef line, and takes the struct to the first end line ([Change](#change) above).
  Any other start fails with the `IOException` that names the file, since no compiler version writes one.
  `MSLBackend.getHeader` builds those lines with `MetalHeaders.metalShaderStart` and `uniformStructEnd`, so the writer
  and the reader share one definition. The review's case through `GenAllDecoraShaders` is in
  [Generator runs](#generator-runs). Tests:
  `anUpToDateMetalShaderWithItsOldUniformStructCommentedOutBeforeItFailsNamingIt` (the review's case, and the same
  behind a start exactly as long as the generated one, so that only the check of the start rejects it),
  `aMetalShaderWhoseUniformStructCannotBeReadFailsNamingIt` with seven more starts, and
  `anUpToDateMetalShaderWithItsOldUniformStructCommentedOutAfterItGetsTheEntryOfItsStruct`.
- **C1, members an earlier compiler renamed** (on `JSLC.java:226`). `JSLC.compile` handed every up-to-date `.metal`
  to `MetalHeaders` as it was. `0281b2fde8` and `a8d6688172` renamed some struct members: they put `vector_` before a
  name that starts with `float2`, `float3` or `float4`, put `<Shader>_` before each word of the struct that names a
  function the program defines (a name, a part of a name that a `$` separates, `main`, `UNUSED` or a type such as
  `texture2d`), and turned `sampleTex` into `<Shader>_SampleTexture`. Over such an output, without `clean`,
  `ba2b68e991` keyed the renamed member, for example `level$Dollar_gain` for the uniform `level$gain`, while the Java
  peer sets the JSL name. `MTLShader.setConstant` passes `uniformNameIdMap.get(name)` as an `int`
  (`src/main/java/com/sun/prism/mtl/MTLShader.java:144`), so that setter throws a `NullPointerException`.
  - Now an up-to-date `.metal` whose struct may hold such a member is not read as it is: a member's name starts with
    `vector_float2`, `vector_float3` or `vector_float4`, or the member text contains `<Shader>_`. `JSLC` compiles that
    shader's program again and takes the entry from the result.
  - In this path the `.metal` is written only when its bytes change, so a current output whose JSL names only look
    renamed keeps its bytes and time. `-force` and out-of-date outputs are still written unconditionally, and a
    compiled shader's entry comes from the text just written, even when the test holds.
  - The test is false for all 262 shaders of the build, so a rerun with nothing changed still parses no program.
  - Tests: `upToDateMetalShadersWithUniformsThatAnEarlierCompilerRenamedGetTheOutputsOfACleanRun` lays down 9 `.metal`
    files captured from `0281b2fde8` (`a8d6688172` writes the same bytes), at least one per kind of rename, as
    up-to-date outputs, and gets a clean run's `.metal` files and headers.
    `aRerunWithNothingChangedKeepsMetalShadersWhoseJslNamesLookRenamedAndParsesNoOtherProgram` and
    `aRunWithForceWritesEveryOutputAgainThoughNothingIsOutOfDate` pin the write rules.
- **A dropped uniform,** found while tracing C1. `MSLBackend` left a uniform out of the struct when its name occurred
  anywhere in the struct text before it, types included: `at` after any `float` member, `g` after
  `texture2d<float> img`. A uniform that the shader body never uses passes the Metal compiler, so `ba2b68e991`'s header
  had no key for it, and the peer's setter threw the same `NullPointerException`. `0281b2fde8`, whose header came from
  the program, failed to compile `MetalShader.m` for `g` and bound `at` to the slot of another member. Now the
  substring test is gone and every declared uniform is appended to the struct. No uniform can occur twice: the JSL
  symbol table rejects a second declaration of a name. Test:
  `uniformsWhoseNamesOccurInEarlierMemberLinesAreAllInTheStructAndTheHeader`.
- **Removed:** `MetalHeaders.add(String, File)`. **Added:** `MetalHeaders.add(String, File, String)` for a compiled
  shader; `addUnlessRenamed(String, File)` for an up-to-date one, which returns false when the struct may hold a
  renamed member; the package-private `metalShaderStart` and `uniformStructEnd`; and `JSLC`'s private
  `writeIfChanged`. `FRAGMENT_SHADER_HEADER_FILE_NAME` is private now.
- **Cost:** none measured. A rerun with nothing changed takes 1449.4 ms of main time on the round-2 tree and
  1497.1 ms on `ba2b68e991` ([Generator runs](#generator-runs)). The up-to-date path reads each `.metal` file once, as
  before, and a compiled shader's file is no longer read back.
- **Accepted:**
  - A legacy `.metal` whose struct passes the test can differ from a current one in its body, for example where a
    local variable is named like a helper function. Its struct, and so its header entry, is right, and the Metal
    library is built from that same file.
  - A legacy `.metal` that lost a uniform to the old substring test keeps it out after an upgrade without `clean`, if
    its struct passes the test. From `param float opacity; param float at;`, `0281b2fde8` writes the struct
    `float opacity; texture2d<float> UNUSED;` and a header with `At_at_ID = 1`, the slot of `UNUSED`. The final state
    keeps that `.metal` and writes only the keys `opacity` and `UNUSED`, so setting `at` throws. A clean build or an
    edit of the `.jsl` file gives the full struct. Such a struct that fails the test is compiled again and repaired:
    the legacy shader `Dropped` of the C1 test, with `float2x`, `float2y` and `at`.
  - `0281b2fde8` and `a8d6688172` also renamed a word of the include or typedef line (for a helper function named `h`,
    `include`, `typedef`, `struct` or `FragmentShaderCommon`) or a precision (for one named `LOWP`, `MEDIUMP` or
    `HIGHP`). The Metal compiler rejects every such file, so it exists only after a failed build. Such a file fails
    naming it, as any other start does; deleting it, or a clean build, ends that.

### Acceptance criteria and evidence
| Criterion | Evidence | State |
| --- | --- | --- |
| Header directory per compile | The criterion's `git grep` finds nothing, and neither does `grep -rn` with the same patterns over `src/jslc`, which reads untracked files too. `MSLBackendTest` pins the static fields, `JSLCInfoTest` the options, and the three `link/..` tests the directory the file system resolves | Met |
| A path without `jsl-` works | `aPathWithoutJslGetsTheMetalShaderThePeersAndTheHeaders` ([Tests](#tests)) | Met |
| No carry-over between compiles | `twoOutputRootsInOneJvmGetTheirOwnCompleteHeaders`, `aPrismRunAfterADecoraRunGetsAPrismHeaderWithOnlyItsShader`, `anEarlierJslInThePathDoesNotMoveTheHeaders` | Met |
| Write errors fail | `aHeaderDirectoryThatCannotBeCreatedFailsNamingIt`, `aHeaderThatCannotBeWrittenFailsNamingIt`; both generators exit 1 ([Generator runs](#generator-runs)) | Met |
| Incremental runs keep complete headers | `aSecondRunWithOneShaderOutOfDateKeepsTheHeadersOfBoth` and its `jsl-decora` twin; for the entries, `aShaderRecompiledWithOtherUniformsGetsItsNewEntryInTheHeader`, `aShaderWrittenByARunThatStoppedBeforeItsHeadersGetsItsEntryInTheNextRun` and the review comment's `aHeaderThatListsTheSameShadersWithTheirUniformsInAnotherOrderIsWrittenAgain`; for outputs an earlier compiler wrote, `upToDateMetalShadersWithUniformsThatAnEarlierCompilerRenamedGetTheOutputsOfACleanRun` (review round 2); the generators outside Maven, including the upgrade without `clean` over the outputs of `0281b2fde8` and `a8d6688172`; F5 (2026-11-07) and F7 | Met on macOS; the Windows run (F8.1) is owner-run |
| Generated files unchanged | The generators outside Maven, on 2026-11-07 on the final state (the clean run), the round-2 tree, `ba2b68e991`, `a8d6688172` and `0281b2fde8`, and Maven on 2026-11-06 on the content of `ba2b68e991`, both with `-d3d -es2 -mtl` ([Generator runs](#generator-runs)); F2 and F4 (2026-11-07); the goldens of `aDecoraRunWritesTheHeadersOfTheCompilerBeforeThisStory` and `aShaderWithHelperFunctionsGetsTheMetalShaderOfTheCompilerBeforeThisStory`, captured from `0281b2fde8`'s compiler and unchanged by review round 2 | Met |
| Build gate | F1 on macOS (2026-11-07); Windows (F8.2) and WSL (F8.3) | Met on macOS; Windows and WSL owner-run |
| macOS | F4 and F5 (2026-11-07), and F6 (2026-11-06) | Met |
| Upstream | [Upstream check](#upstream-check-2026-11-05-macos); the draft report, kept outside the repository | Checked and drafted; filing pending |

### Tests
- **jslc.** `mvn -B -ntp -pl buildtools/jslc test` runs 196 tests with 0 failures, 0 errors and 0 skipped, in the
  default method order (F1) and in a random one (`MethodOrderer$Random`, seed 20261107), with the same counts per
  class (2026-11-07, on the final state). The random run's report lists the orderer among its system properties, and its
  `MetalHeadersTest` methods ran in another order than in F1. The 22 existing classes keep their 159 tests, and
  `SymbolTest` keeps its message check. New: `MetalHeadersTest` 33, `JSLCInfoTest` 3 and
  `backend.hw.MSLBackendTest` 1. `ba2b68e991` runs 190, with `MetalHeadersTest` at 27, and the story commit runs 182,
  with 19. The three `link/..` tests are `@DisabledOnOs(WINDOWS)`, because Windows drops `link\..` from a path as
  text, so on Windows jslc is expected to run 196 with 3 skipped ([F8.2](#f82-windows-gate)).
- **The tests of review round 2.** Of `ba2b68e991`'s 27 `MetalHeadersTest` methods, 26 are unchanged and 1 is
  extended, and 6 methods are new:
  - New for C1: `upToDateMetalShadersWithUniformsThatAnEarlierCompilerRenamedGetTheOutputsOfACleanRun`. Its 9 legacy
    shaders are `Scale`, `Glow` and `Tint` (`vector_` before a `float2`, `float3` or `float4` name; `Tint` declares
    `opacity` first, so that looking at the first member alone misses it), `Helper` (a uniform named like a helper
    function), `Parts` (`$`-separated parts, `main` and `UNUSED`), `Dollar` (only the part after a `$`), `Sampled`
    (`sampleTex`), `Typed` (a helper named `texture2d`) and `Dropped`. `Dropped`'s `.metal` from `0281b2fde8` lost `at`
    and gained two `vector_`, so it is exactly as long as a clean run's and differs only in its bytes. For every shader
    the test asserts the clean run's `.metal`, the header IDs, struct members, `ids[]` and keys by JSL name, and both
    headers byte-identical to a clean run's. A further rerun with a program that is not JSL rewrites nothing.
  - New for C1's write rules: `aRerunWithNothingChangedKeepsMetalShadersWhoseJslNamesLookRenamedAndParsesNoOtherProgram`
    (current outputs whose JSL names match the test keep their bytes and times, and `Near`, with names such as
    `vector_floatx`, `my_vector_float2` and `Nearby`, parses no program) and
    `aRunWithForceWritesEveryOutputAgainThoughNothingIsOutOfDate`.
  - New for C2: `anUpToDateMetalShaderWithItsOldUniformStructCommentedOutBeforeItFailsNamingIt` and
    `anUpToDateMetalShaderWithItsOldUniformStructCommentedOutAfterItGetsTheEntryOfItsStruct`.
  - New for the dropped uniform: `uniformsWhoseNamesOccurInEarlierMemberLinesAreAllInTheStructAndTheHeader`, with
    `at` inside the type `float`, `g` inside the name `img`, `pacity` inside `opacity` and `exture` inside `texture2d`.
  - Extended: `aMetalShaderWhoseUniformStructCannotBeReadFailsNamingIt` gained seven starts: a line comment before the
    struct, an `#if 0` copy of the struct before it, no empty line, an extra empty line, a line comment before the
    include, another shader's struct name and the end marker at the end of a member line.
- **The tests of review round 1.** Of the story commit's 19 `MetalHeadersTest` methods, 16 are unchanged, 1 is renamed
  and 2 changed, and 8 methods are new:
  - New: `aHeaderThatListsTheSameShadersWithTheirUniformsInAnotherOrderIsWrittenAgain` (the review comment),
    `aShaderWrittenByARunThatStoppedBeforeItsHeadersGetsItsEntryInTheNextRun` (R1) and
    `aMetalShaderWhoseUniformStructCannotBeReadFailsNamingIt`. The last writes a cut struct, a struct without its
    typedef line, an end marker before the typedef and 17 member lines in other forms over an up-to-date `.metal`,
    and each must fail with the `IOException` that names the file.
  - New: the three `link/..` tests, `theHeadersGoWhereTheFileSystemResolvesAHeaderDirectoryWithDotDotAfterALink`,
    `withoutAHeaderDirectoryTheHeadersFollowAnOutputDirectoryWithDotDotAfterALink` and
    `aRunTellsHeaderDirectoriesApartAsTheFileSystemResolvesThem`.
  - New: `aUniformNamedLikeAHelperFunctionATypeOrSampleTexKeepsItsJslNameInTheHeader` and
    `aShaderWithHelperFunctionsGetsTheMetalShaderOfTheCompilerBeforeThisStory`, a golden of the `.metal` that
    `0281b2fde8` wrote for helper functions that call each other and sample, as the build's mask and paint shaders do.
  - Renamed, with the same body: `aRunWithNothingOutOfDateParsesAndRewritesNothing` is now
    `aRunWithNothingOutOfDateParsesNoProgramAndRewritesNothing`, since such a run reads every `.metal` struct.
  - Changed: `aDecoraRunWritesTheHeadersOfTheCompilerBeforeThisStory` compiles 7 programs instead of 3, adding
    `IntAndFloat4`, `Precisions`, `Pairs` and `IdentifierCharacters`. Between them they have every kind of uniform the
    build's shaders declare, the precisions, two uniforms of each type the header spells differently from the `.metal`,
    and a name (`$zZ_aA09`) with the first and last character of each range a JSL identifier may have. Its golden was
    captured from `0281b2fde8`'s compiler. `aShaderRecompiledWithOtherUniformsGetsItsNewEntryInTheHeader` moved its
    clean run into a helper.
- **Fail-before, story.** Variants of the story's new tests ran on `0281b2fde8` on 2026-11-05, one Maven/surefire JVM
  per method. They use a local `Kind` enum, create the header directory as `0281b2fde8`'s pom did and do not call
  `write()`. 15 of the 18 methods that `0281b2fde8` can run fail; the 5 methods that exercise only the new API cannot
  run there.

  | Test | Acceptance criterion | On `0281b2fde8` |
  | --- | --- | --- |
  | `aPathWithoutJslGetsTheMetalShaderThePeersAndTheHeaders` | a path without `jsl-` works | `StringIndexOutOfBoundsException: Range [0, -1) out of bounds for length 159` |
  | `withoutAHeaderDirectoryTheHeadersGoToMtlHeadersInTheOutputDirectory` | default directory in `outDir` | the same exception |
  | `twoOutputRootsInOneJvmGetTheirOwnCompleteHeaders` | no carry-over: two roots | `expected: <[ProbeA]> but was: <[ProbeA, ProbeB]>` |
  | `aPrismRunAfterADecoraRunGetsAPrismHeaderWithOnlyItsShader` | no carry-over: Decora then Prism | `expected: <#ifndef PRISM_SHADER_COMMON_H> but was: <#ifndef DECORA_SHADER_COMMON_H>` |
  | `anEarlierJslInThePathDoesNotMoveTheHeaders` | no carry-over: `jsl-work` | `missing <tmp>/jsl-work/gensrc/mtl-headers/FragmentShaderCommon.h` |
  | `aHeaderDirectoryThatCannotBeCreatedFailsNamingIt`, `aHeaderThatCannotBeWrittenFailsNamingIt` | write errors fail | `Expected java.io.IOException to be thrown, but nothing was thrown.` |
  | `aSecondRunWithOneShaderOutOfDateKeepsTheHeadersOfBoth` | incremental runs: the jslc test | `StringIndexOutOfBoundsException` |
  | `aSecondRunUnderJslDecoraWithOneShaderOutOfDateKeepsTheHeadersOfBoth` | the same under `jsl-decora`, failing on the headers | `expected: <[ProbeA, ProbeB]> but was: <[ProbeA, ProbeB, ProbeB]>` |
  | `aHeaderWhoseContentIsUnchangedKeepsItsTimeStamp` | a header is written only when its content changes | the header's time moved |
  | `aShaderRecompiledWithOtherUniformsGetsItsNewEntryInTheHeader` | a recompiled shader's new uniforms reach the header | `NoSuchFileException: <tmp>/clean/gensrc/mtl-headers/DecoraShaderCommon.h` (the static directory sent the second root's header to the first) |
  | `missingHeadersAreWrittenAgainWithoutRewritingTheShaders` | complete headers with up-to-date shaders | `NoSuchFileException: <tmp>/gensrc/mtl-headers/FragmentShaderCommon.h` |
  | `aHeaderThatListsOtherShadersIsWrittenAgain` | truncated or foreign headers are repaired | `NoSuchFileException: <tmp>/other/gensrc/mtl-headers/DecoraShaderCommon.h` |
  | `JSLCInfoTest.theMetalHeaderOptionsSetTheDirectoryAndThePrismKind` | directory and kind from `JSLCInfo` | `expected: <4> but was: <0>` |
  | `MSLBackendTest.keepsNoCompileOrRunStateInStaticFields` | no static per-compile or per-run state | `headerFilesDir is not final` |

  Three guards pass on `0281b2fde8` as well: a rerun with nothing out of date parses no program and writes nothing, a
  run without Metal output writes no headers, and the header bytes equal a golden captured from `0281b2fde8`'s
  compiler.
- **Fail-before, review round 2** (2026-11-07). The final `MetalHeadersTest`, copied into a clone of `ba2b68e991`,
  compiles there and gives 33 / 4 / 0 / 0.

  | Test | What it pins | On `ba2b68e991` |
  | --- | --- | --- |
  | `upToDateMetalShadersWithUniformsThatAnEarlierCompilerRenamedGetTheOutputsOfACleanRun` | C1 | `:928 Scale ==> expected: <the clean run's .metal>`: the legacy `.metal` is kept |
  | `anUpToDateMetalShaderWithItsOldUniformStructCommentedOutBeforeItFailsNamingIt` | C2 | `:542`, no `IOException`: the commented struct is read |
  | `aMetalShaderWhoseUniformStructCannotBeReadFailsNamingIt` | other starts fail | `:516`, no `IOException` for the first new start, a line comment before the struct |
  | `uniformsWhoseNamesOccurInEarlierMemberLinesAreAllInTheStructAndTheHeader` | the dropped uniform | `:1006`: the struct is `float opacity; texture2d<float> img;`, without `at`, `g`, `pacity` and `exture` |

  The 26 unchanged methods pass, and three new ones pass as guards: the struct with the old one commented out after it
  (`ba2b68e991` also ends the struct at the first end line), the rerun of outputs whose names look renamed
  (`ba2b68e991` never rewrites an up-to-date output) and the `-force` run. Through `JSLC.main`, `ba2b68e991` keeps
  every legacy `.metal` it finds up to date ([Generator runs](#generator-runs)).
- **Fail-before, review round 1** (2026-11-06). The final `MetalHeadersTest` of that round, copied into a clone of
  `a8d6688172`, compiles there, since it uses no API the fix added. The class gives 27 / 6 / 1 / 0, and each method
  alone the same result; the 16 unchanged methods pass. `0281b2fde8` cannot compile it, so its variants ran through
  that commit's command-line drivers, one JVM per run, with the header directory created first.

  | Test | What it pins | On `a8d6688172` | On `0281b2fde8` |
  | --- | --- | --- | --- |
  | `aHeaderThatListsTheSameShadersWithTheirUniformsInAnotherOrderIsWrittenAgain` | the review comment: same shader names, other uniforms | `:538 array contents differ at index [406], expected: <104> but was: <115>`: the `h` of A's `hue` against the `s` of B's `saturation`, so B's header was kept | B's entry kept (through `JSLC.main`) |
  | `aShaderWrittenByARunThatStoppedBeforeItsHeadersGetsItsEntryInTheNextRun` | R1 | `:465`: the header keeps ProbeA's old struct `id<MTLTexture> baseImg; packed_float2 offset;` | cannot run: no `write()` step |
  | `aMetalShaderWhoseUniformStructCannotBeReadFailsNamingIt` | an unreadable struct fails naming the `.metal` | `:373 Expected java.io.IOException to be thrown, but nothing was thrown.` | cannot run: no `.metal` is read |
  | `theHeadersGoWhereTheFileSystemResolvesAHeaderDirectoryWithDotDotAfterALink`, `withoutAHeaderDirectoryTheHeadersFollowAnOutputDirectoryWithDotDotAfterALink` | `link/..` resolved by the file system | `:311` and `:324`: `missing <tmp>/x/y/gensrc/mtl-headers/FragmentShaderCommon.h` and `missing <tmp>/x/y/out/mtl-headers/FragmentShaderCommon.h` | passes (through `JSLC.main`): the headers go where the file system resolves the path |
  | `aRunTellsHeaderDirectoriesApartAsTheFileSystemResolvesThem` | two paths to one directory are one directory | `IllegalStateException: This run writes the DecoraShaderCommon.h in <tmp>/mtl-headers, not the DecoraShaderCommon.h in <tmp>/x/y/mtl-headers` | cannot run: no header directory option |
  | `aUniformNamedLikeAHelperFunctionATypeOrSampleTexKeepsItsJslNameInTheHeader` | uniforms keep their JSL names | `:660`: the typedef members of `NamesThatStartWithAType` are `packed_float4Tint`, `packed_float2Scale` and `vector_float3Glow`; its IDs and keys pass | the same as `a8d6688172` |

  Four pass on `a8d6688172` as guards: the renamed `aRunWithNothingOutOfDateParsesNoProgramAndRewritesNothing`, the
  two changed tests and `aShaderWithHelperFunctionsGetsTheMetalShaderOfTheCompilerBeforeThisStory`. On `0281b2fde8`
  the variants of the two goldens give byte-identical files, as on the other two trees, and the variant of the renamed
  test, a rerun over an up-to-date root whose program is not JSL, exits 0 and moves no file time. The dictionary keys
  that `aUniformNamedLike…` guards went wrong only in an intermediate state of round 1's fix (its internal round 4
  below), so on `a8d6688172` it catches only the typedef members.
- **Mutation check.** The story's review ran two rounds of mutants of `MetalHeaders`, `JSLC` and `MSLBackend` (55,
  then 28). The gap that mattered then, a uniform edit kept out of the header by the names check, is gone with that
  check, and `aShaderRecompiledWithOtherUniformsGetsItsNewEntryInTheHeader` still pins it. The fix for PR review
  round 1 had five internal review rounds, each with a mutation reviewer, and each confirmed gap got a test:
  - **Round 1.** M06, the byte compare reduced to a length compare, passed the review comment's test, whose two
    headers differed in length; the test now swaps two uniforms, so both headers have the same length. Hand-edited
    member lines that the Metal compiler still accepts (a space before `;`, two declarators on one line,
    `[[id(2)]]`, a tab indent) gave a wrong entry or an exception without the path. They led to the strict member
    parser and the test's list of member lines in other forms.
  - **Round 2.** `replaceFirst` in the three type mappings (A11 to A13), the edges of the identifier character
    ranges, a three-space indent and `<>` in a name (B01, B22, B24 to B27, B29), and a struct without its typedef line
    or with its end before it (A01, A02) survived. The golden gained `Pairs` and `IdentifierCharacters`, the list two
    lines and the unreadable-struct test two shapes.
  - **Round 3.** `isWord` not checking a name's first character (I03) survived; `float *offset;` and
    `float &offset;` kill it. The `link/..` fix was checked with three mutants (N1, N3, N5), which its tests kill.
  - **Round 4** found by review that `MSLBackend` renamed some struct members: it renamed a helper function or
    `sampleTex` everywhere in the shader, struct included, and rewrote a name that starts with `float2`, `float3` or
    `float4` together with the type. The fix keyed those uniforms by the renamed member, a name the Java peer never
    sets, so `MTLShader` would throw a `NullPointerException` setting one. The anchored rewrites and call-only renames
    came with `aUniformNamedLike…`, which fails under each mutant that undoes one of them.
  - **Round 5.** H1, H5, H6 and H7 of those renames passed all 189 tests. H1, the helper rename without its leading
    `\b`, silently changes 48 of the build's 212 Prism `.metal` files; H5 and H6 give shaders the Metal compiler
    rejects; H7, the new lookahead made unmatchable, leaves every helper unprefixed and changes 208 of them.
    `aShaderWithHelperFunctionsGetsTheMetalShaderOfTheCompilerBeforeThisStory` kills them and H8 to H10.
  - **Survivors, accepted.** Equivalent ones, for example: a precision matched without its trailing space, the
    explicit `typeEnd < 0` check removed, the `[` searched from the start of the line, `put` instead of `putIfAbsent`
    before the throw, `compile` returning null (the Prism `CompileJSL` then parses again) and the textual fast path of
    the same-directory check dropped (only cost). Two that the story accepted stay: `FragmentShaderCommon.h` written
    only when missing, and the directory-creation `IOException` swallowed, whose code the fix does not change and
    which was not run again. The third, `-force` no longer recompiling the Metal output, is killed since PR review
    round 2. A miscount of array sizes of 2 to 9 digits passes jslc; `MTLShaderTableGoldenTest`, which pins the
    argument buffer IDs of all 262 shaders, catches it on macOS.
  - **PR review round 2.** Its implementation ran 21 mutants (M01 to M21). Its review reasoned about N02 and N06, and
    the fix pass after the review ran 20 (N01 to N20, partly reruns of the first set). Each gap got a test:
    - `-force` dropped from the Metal decision (M13): `aRunWithForceWritesEveryOutputAgainThoughNothingIsOutOfDate`.
    - The start check removed while the members are still cut at the generated start's length (N01): the C2 test's
      start exactly as long as the generated one.
    - The renamed-member test looking only at the first member (N02, N03): `Tint` declares `opacity` first.
    - The struct end searched with `lastIndexOf` (N04): the test with the old struct commented out after the active
      one.
    - `writeIfChanged` comparing sizes instead of bytes (N05): `Dropped`, whose legacy `.metal` is as long as a clean
      run's.
    - The `<Shader>_` clause anchored at a space or a token start (N06, N07): `Dollar`, renamed only after its `$`.
    - The `vector_` clause widened to `vector_float` or to a match anywhere in the name (N08, N09): `Near` in the
      rerun test.
    - **Survivors, accepted as equivalent:** the end searched one character later (M15, N11: the two differ only for
      an empty struct, which both reject), `writeIfChanged` encoding UTF-8 instead of the default charset (M16, N12:
      the same on JDK 18 and later unless `-Dfile.encoding=COMPAT` is set, and the generated text is ASCII) and the
      recheck path reading its entry back from the file (M19, N13: after `writeIfChanged` the file holds exactly the
      compiled text).
    - **No longer applicable:** M18 and N10 made an exact-name check of `MSLBackend` unconditional and survived. That
      check is gone, since the symbol table rejects a second declaration (the dropped uniform above).

### Generator runs
- **The pom's way, outside Maven** (`GenAllDecoraShaders` in one JVM, `CompileJSL` once per Prism `.jsl` file,
  `-d3d -es2 -mtl`, copies of the `.jsl` directories as input): on 2026-11-06 for the content of `ba2b68e991`,
  `a8d6688172` and `0281b2fde8`, and on 2026-11-07 for the round-2 tree, `ba2b68e991`, `a8d6688172` and `0281b2fde8`,
  each with the compiler and generators built from a clone of that tree, and on the final state only the clean run,
  with the compiler from the main tree's jslc build, its jar rebuilt at about 12:01 on 2026-11-07:
  - **Clean:** 1,059 files (`jsl-decora` 208, `jsl-prism` 848, `mtl-headers` 3), identical by name and md5 on all
    three trees, and on 2026-11-07 on all four and on the final state. The headers list 50 of 50 and 212 of 212
    shaders. All 262 `.metal` files start with the generated include, empty and typedef lines, and the round-2 test for
    a renamed member is false on all 1,368 of their member lines.
  - **Rerun with nothing changed:** 0 of the 1,059 file times move, on all three trees; on 2026-11-07 on the round-2
    tree, `ba2b68e991` and `0281b2fde8`, each tree staying md5-identical to the clean one.
  - **After touching `ColorAdjust.jsl` and `PaintColor.jsl`:** `ba2b68e991` and `a8d6688172` rewrite 117 files (5
    Decora, 112 Prism, no header), and on 2026-11-07 the round-2 tree and `ba2b68e991` do the same. The headers keep
    their times to the nanosecond, and the tree is identical by md5 to the clean one. `0281b2fde8` rewrites 120, and
    its headers list 1 of 50 and 28 of 212, on both dates.
  - **Repair:** with `0281b2fde8`'s truncated headers (1 and 28) dated 2001, a run of `ba2b68e991` with nothing
    changed rewrites only `DecoraShaderCommon.h` and `PrismShaderCommon.h`; with the three headers cut to 1,000,
    200,000 and 100 bytes, it rewrites those three. The tree then equals the clean one, and the next rerun rewrites
    nothing. `a8d6688172` does the same, and `0281b2fde8` repairs nothing. On 2026-11-07 the round-2 tree, run over
    `0281b2fde8`'s output after the touch above, whose headers list 1 and 28, rewrites exactly `DecoraShaderCommon.h`
    and `PrismShaderCommon.h`; the tree then equals the clean one, and the next rerun rewrites nothing.
  - **Upgrade without `clean`** (2026-11-07): over the clean outputs of `0281b2fde8` and of `a8d6688172`, the round-2
    tree rewrites no file, and both trees equal its clean run by name and md5.
    - No compiler renamed a member of the build's shaders, so the legacy case ran through `JSLC.main -mtl`, one JVM
      per program, on two programs of the C1 test: `Tint` (`opacity`, then `float4Tint`) and `Dollar` (`level$gain`
      beside a helper function `gain`). `0281b2fde8` writes `vector_float4 vector_float4Tint;` and
      `float level$Dollar_gain;`, and `a8d6688172` the same bytes.
    - Over either output, the round-2 tree rewrites both `.metal` files and the header, and the tree then equals a
      clean run's; a further rerun, over `a8d6688172`'s output, rewrites neither `.metal`. `ba2b68e991` keeps both
      legacy files and writes the keys `level$Dollar_gain`, `opacity` and `UNUSED` for `Dollar`.
    - From `param float opacity; param float at;`, `0281b2fde8` writes a struct without `at` that passes the test. The
      round-2 tree keeps that `.metal` and writes only the keys `opacity` and `UNUSED`, where its clean run writes
      `float at;` (round 2, Accepted).
  - **The review comment's case:** roots `gensrc/jsl-decora-A` and `gensrc/jsl-decora-B` share `gensrc/mtl-headers`,
    B's `ColorAdjust.jsl` declares `saturation` before `hue`, and `GenAllDecoraShaders` runs for A, B and A again. On
    `ba2b68e991` the header after the rerun of A equals the clean Decora header, with `ColorAdjust_hue_ID = 1,
    ColorAdjust_saturation_ID = 2`, and none of A's 208 outputs is rewritten. `a8d6688172` and `0281b2fde8` keep B's
    header, `saturation_ID = 1, hue_ID = 2`, while A's `ColorAdjust.metal` has `hue` first. Through `JSLC.main`, with
    `Foo` compiled into the two roots, the result is the same.
  - **Exit codes:** with `gensrc/mtl-headers` a regular file, `GenAllDecoraShaders` and the first Prism `CompileJSL`
    of `ba2b68e991` exit 1 with `Cannot create the Metal header directory <path>`. With `DecoraShaderCommon.h` and
    `PrismShaderCommon.h` directories, or `FragmentShaderCommon.h` a directory, both exit 1 with `Cannot write the
    Metal header <path>`, on a fresh output and over an up-to-date one. `a8d6688172` does the same. `0281b2fde8` exits
    0 in every case: it prints the exceptions on a fresh output and is silent over an up-to-date one. An up-to-date
    `ColorAdjust.metal` cut to 60 bytes makes `GenAllDecoraShaders` exit 1 with `Cannot read ColorAdjust_Uniforms from
    the Metal shader <path>` and leaves the header as it was (measured on round 1's fix before its internal review
    rounds).
  - **C2, the review's case** (2026-11-07): an up-to-date `ColorAdjust.metal` gets a copy of its struct, with `hue`
    and `baseImg` swapped, in a block comment after the include line and its empty line, before the active struct.
    The round-2 tree's `GenAllDecoraShaders` exits 1 with `Cannot read ColorAdjust_Uniforms from the Metal shader
    <path>`, and `DecoraShaderCommon.h` keeps its md5 and time. `ba2b68e991` exits 0 and writes
    `ColorAdjust_hue_ID = 0, ColorAdjust_baseImg_ID = 1`, against the clean `ColorAdjust_baseImg_ID = 0,
    ColorAdjust_hue_ID = 1`.
  - **Time:** for a rerun with nothing changed, the main time summed over the 31 JVMs, as the median of 9 interleaved
    repetitions, is 1224.6 ms on `0281b2fde8`, 1373.5 ms on `a8d6688172` and 1619.6 ms on the content of
    `ba2b68e991` (2026-11-06). That is 394.9 ms more than `0281b2fde8`, 12.74 ms per JVM: 148.8 ms (4.80 ms per JVM)
    from the story commit and 246.1 ms (7.94 ms per JVM) from round 1's fix. That share is mostly reading and parsing
    every `.metal` of the run: in a Prism JVM, profiled before round 1's internal review rounds, adding the 212 shaders
    took 9.1 to 10.1 ms, 3.7 to 4.7 ms of it reading the files. Computing every entry from the programs instead was
    measured at about 198 ms more per JVM than `a8d6688172`.
  - **Time, review round 2** (2026-11-07): each JVM's main time is taken from its uptime stamps, from the main class
    load (`-XX:LogClassLoadingCauseFor`) to the first `-Xlog:gc+exit` line. The median of 9 interleaved repetitions,
    the order alternating, is 1449.4 ms on the round-2 tree and 1497.1 ms on `ba2b68e991`; the repetitions span 1426.5
    to 1560.8 ms and 1469.8 to 1507.0 ms, and the median wall times are 2147.0 and 2178.4 ms. No file was rewritten.
    These figures were taken on another macOS version than those of 2026-11-06 and compare only with each other.
- **Maven with all three hardware backends** (2026-11-06, on the content of `ba2b68e991`): `mvn -B -ntp -pl
  modules/javafx.graphics -am clean test-compile "-Djfx.jsl.backends=-d3d -es2 -mtl" -DskipNative=true`.
  `target/gensrc` holds the same 1,079 files as `a8d6688172`'s, identical by name and md5, including the 20 `javac -h`
  headers, and its 1,059 shader files equal the clean run outside Maven. On 2026-11-05 the story's tree held the same
  1,079 files as `0281b2fde8`'s. The three headers are in `target/gensrc/mtl-headers` under the same names.
- **Two of the four drivers the build does not run** (2026-11-06, on the content of `ba2b68e991`): `CompileBoxBlur
  -mtl -hw` and `CompileExternal -mtl SepiaTone`, each with its own `-mtlheaders`, exit 0 and write headers that list
  their 13 and 1 shaders, md5-identical to `a8d6688172`'s. Without a backend option, `CompileExternal SepiaTone`
  writes no Metal shader and so no header.
  `CompileGaussian` and `CompileZoomRadialBlur` got the same `write()` call but were not run.

### Final gates (2026-11-05, macOS)
Rerun on 2026-11-07 on macOS 27.2. F1 ran in the main tree on the final state, and F2 is taken from its output. F3,
F4 and F5 ran on the round-2 tree ([Implementation](#implementation-2026-11-05-macos)): F3 in the main tree, F4 and F5
in a clone of `ba2b68e991` that holds the round-2 fix as it was then and whose path has no `jsl-`. F1 and F2 had run
on the round-2 tree too, with the same per-class lines and generated files. That F1's natives were overwritten by the
final state's F1 before they were hashed; F2's equal those of the round-2 tree's F4 build and of `ba2b68e991`. Each of
F1 to F5 also ran on a clone of `ba2b68e991` without the round-2 fix, with the same results apart from the jslc
counts. F6 and F7 carry their result of 2026-11-06, on macOS 27.0.1, from the content of `ba2b68e991` in a clone of
`a8d6688172`, which also ran on `a8d6688172` alone with the same results: F6's oracle can no longer run on this host
(see F3), and F7's generators take the up-to-date path of F5 and of the runs outside Maven, where the round-2 fix
changes no output. "Before the story" is the same steps on `0281b2fde8`, run on this host on 2026-11-05. Counts are
run / failures / errors / skipped.

| Gate | Command | Result |
| --- | --- | --- |
| F1 module gate | `mvn -B -ntp -Dmaven.test.failure.ignore=true -pl buildtools/jslc,modules/javafx.graphics -am clean test` | BUILD SUCCESS. jslc 196 / 0 / 0 / 0 in 25 classes, base 5,510 / 0 / 0 / 22, graphics 26,636 / 0 / 0 / 837. The 937 per-class lines (jslc 25, base 290, graphics 622) equal the round-2 tree's in every class, and `ba2b68e991`'s, run the same day, except `MetalHeadersTest`, 27 there. On 2026-11-06 graphics skipped 824 and the per-class lines equalled `a8d6688172`'s except `MetalHeadersTest`; on 2026-11-05 the story's tree had those of `0281b2fde8` in every old class. The 13 more skips are on both trees and come from the host: since the update to macOS 27.2 (build 26B123, with another GPU driver and Metal version), `ParityGate` takes the goldens of 10 classes captured on macOS 27.0.1 as no oracle, so `MTLGoldenRenderTest` skips 4 tests, `MTLShaderTableGoldenTest` 1, and 8 classes 1 each: the 5 Mac font golden classes, `MacFontProcessGoldenTest` among them, and `MacAccessibleNativeTest`, `MacClipboardNativeTest` and `MacPixelsNativeTest`. `SymbolTest` keeps its message check. No `hs_err_pid*` file, and no Metal header message in the log |
| F2 generated files | F1's `target/gensrc` and natives | 817 files (`jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3, `headers` 20), identical by name and md5 to the round-2 tree's and `ba2b68e991`'s, as round 1's were to `a8d6688172`'s on 2026-11-06 and the story's tree to `0281b2fde8`'s on 2026-11-05. `DecoraShaderCommon.h` lists 50 of 50 shaders and `PrismShaderCommon.h` 212 of 212. `libprism_mtl.dylib` (272,192 bytes), `jfxshaders.metallib` (1,621,807 bytes) and `MetalShader.m.o` (267,256 bytes) are md5-identical to the round-2 tree's and `ba2b68e991`'s |
| F3 Metal goldens | `mvn -B -ntp -pl modules/javafx.graphics -am test '-Dtest=MTL*' -Dsurefire.failIfNoSpecifiedTests=false -Djfx.parity.require=true -DskipNative=true` | 55 / 5 / 0 / 0 in 7 classes, equal per class to `ba2b68e991`'s. The 5 failures, 4 in `MTLGoldenRenderTest` and 1 in `MTLShaderTableGoldenTest`, are `ParityGate` refusing the oracle on this host: `-Djfx.parity.require=true says this machine owns the oracle … the Metal golden describes another machine, macOS build, GPU driver, shader library or JDK`, with the macOS build, GPU driver and Metal version differing from the golden's. No comparison ran. On 2026-11-06, on macOS 27.0.1 and the content of `ba2b68e991`, F3 gave 55 / 0 / 0 / 0, equal per class to `a8d6688172`'s. The round-2 fix cannot change its outcome on any host: F2's generated files and native libraries are md5-identical to `ba2b68e991`'s. Its `jsl-codegen` rerun over the output of F1's run on the round-2 tree rewrote no `jsl-decora`, `jsl-prism` or `mtl-headers` file, only the 20 `javac -h` headers |
| F4 clean native build | `mvn -B -ntp -pl modules/javafx.graphics -am clean test-compile` | `target/gensrc` as in F2. `libprism_mtl.dylib`, `jfxshaders.metallib` and `MetalShader.m.o` (267,256 bytes) are md5-identical to `ba2b68e991`'s |
| F5 no `clean` after a `.jsl` edit | `touch` of `ColorAdjust.jsl` and `PaintColor.jsl`, then F4's command without `clean` | `target/gensrc` md5-identical to F4's. 108 files are rewritten (4 Decora, 84 Prism, the 20 `javac -h` headers) and no Metal header: the three keep F4's times to the nanosecond and list 50 and 212. CMake regenerates only the 29 changed `.air` files and relinks `jfxshaders.metallib` (same md5); `MetalShader.m` is not recompiled and `libprism_mtl.dylib` not relinked. `ba2b68e991` gives the same, as `a8d6688172` did on 2026-11-06. Before the story: 111 files rewritten, headers listing 1 and 28, `MetalShader.m` recompiled and `libprism_mtl.dylib` relinked at 163,056 bytes |
| F6 Metal goldens after F5 | F3's command | 2026-11-06: 55 / 0 / 0 / 0; the shader-table test creates 262 shaders (212 Prism, 50 Decora), none with an empty uniform table, and its `shader.*` keys equal the golden's. Before the story: 5 failures (4 in `MTLGoldenRenderTest`, 1 in `MTLShaderTableGoldenTest`); the 233 shaders the headers no longer list have no uniform table, and the first texture binding on one of them throws a `NullPointerException` in `MTLShader.setTexture` (`MTLShader.java:113`) |
| F7 `-d3d -mtl` without `clean` | F4's command with `"-Djfx.jsl.backends=-d3d -mtl" -DskipNative=true`, F5's `touch`, then the same without `clean` (the stand-in for F8.1) | 2026-11-06: clean, 817 files, each md5-equal to its file in the `-d3d -es2 -mtl` build of that state, and headers listing 50 and 212. Without `clean`: the headers byte-identical with unchanged times, the whole `target/gensrc` md5-identical, 108 files rewritten (4 Decora, 84 Prism, the 20 `javac -h` headers) and no Metal header. `a8d6688172` gives the same. Before the story the same Maven steps leave 1 and 28 and rewrite all three headers |

### Upstream check (2026-11-05, macOS)
- **Setup.** `git archive ca9b07aeae` of `modules/javafx.graphics/src/{jslc,main/jsl-decora,main/jsl-prism}`. The
  parser was generated by the ANTLR 4.7.2 Tool with `build.gradle`'s arguments (`-package
  com.sun.scenario.effect.compiler -visitor`), and the compiler and both generator sets were compiled with JDK 27. The
  Decora drivers were compiled against the fork's `javafx.graphics` classes, with `build.gradle`'s three
  `--add-exports`; the only JavaFX classes they use, `Blend`, `Light` and `LinearConvolveRenderState`, are identical
  at `ca9b07aeae` and HEAD.
- **Run** as `build.gradle` runs it on macOS without `IS_INCLUDE_ES2`: `gensrc/mtl-headers` created first (`:1702`);
  `GenAllDecoraShaders` in one JVM with
  `-i … -o gensrc/jsl-decora -t -pkg com/sun/scenario/effect -mtl -all GenAllDecoraShaders` (`:2485-2508`);
  `CompileJSL` once per Prism `.jsl` file, 30 JVMs, with
  `-i … -o gensrc/jsl-prism -t -pkg com/sun/prism -mtl -name <file>` (`:2589-2608`). A second run from a fresh archive
  gave the same files, the same rewritten files and the same header bytes.
- **Clean:** `jsl-decora` holds 164 files (50 `.metal`, 86 `.java`, 28 `.cc`: upstream still generates the SSE peers),
  `jsl-prism` 424 (212 `.metal`, 212 `.java`) and `mtl-headers` 3. The three headers are byte-identical to HEAD's,
  and so are the 262 `.metal` files except `ColorAdjust.metal`, which differs by the fork's US-005 fix.
- **Rerun with nothing changed:** 0 of the 591 file times move.
- **After touching `ColorAdjust.jsl` and `PaintColor.jsl`:** 64 files are rewritten: 5 Decora (`ColorAdjust.metal` and
  the `JSW`, `PPS` and two `SSE` peer files), 56 Prism (the 28 `<mask>_Color` and `<mask>_Color_AlphaTest` shaders ×
  `.metal`/`.java`) and the 3 headers. `DecoraShaderCommon.h` lists 1 of 50 shaders (`ColorAdjust`), and
  `PrismShaderCommon.h` 28 of 212. These truncated headers are byte-identical to those HEAD's Maven build leaves
  after the same `touch` (F5, before the story).
- **With `-es2` too.** `build.gradle` sets `INCLUDE_ES2` to true outside Windows (`:581`), so a default macOS build
  passes `-mtl -es2`. Run that way, with `// edit` appended to the two `.jsl` copies instead of a `touch`, the headers
  list 50 and 212 clean and 1 and 28 after the edit, and every generator exits 0.
- **The Measured cases** reproduce on upstream's compiler: a path without `jsl-` throws the
  `StringIndexOutOfBoundsException` at `MSLBackend.java:468`, output under `<dir>/jsl-work/gensrc/jsl-decora` sends the
  headers to `<dir>/mtl-headers` and exits 0, and two roots or Decora then Prism in one JVM carry entries over. With
  the generators' output root below a `jsl-build` directory, every JVM exits 0 after 264 `FileNotFoundException`s,
  and `gensrc/mtl-headers` stays empty.
- **master.** At `0165faf6df` (2026-11-04, fetched 2026-11-06), `MSLBackend.java`, `JSLC.java` and `MTLShader.java`
  are byte-identical to `ca9b07aeae`'s.
- **In a Gradle build** the touch stands for an edit. Gradle fingerprints the files of `src/main/jsl-<name>` by
  content, so a bare `touch` would not rerun the task, but an edit does. Once the task reruns, the compiler's own time
  check recompiles only the edited shaders, as here. The task's `doFirst` only creates `gensrc/mtl-headers`, and
  Gradle's user guide describes removing old outputs only in special cases (stale outputs after a Gradle version
  change, for source-set output), not before an ordinary task reruns. Gradle itself was not run.

### Risk R1, closed: a stale entry after an interrupted run
- **Trigger.** A run writes a `.metal` file whose uniforms changed and stops before `write()`: the JVM is killed, or a
  header write fails and the header becomes writable again later. The next run finds every output up to date.
- **At the story commit.** An Objective-C header that survived intact still started with the kind's prologue and
  ended with the run's `get<KIND>Dict`, so the names check kept it with the shader's old struct, IDs and dictionary,
  and `MetalShader.m` was built against uniforms that did not match the `.metal` file. The story accepted this, with a
  `clean` build, `-force` or a `touch` of a `.jsl` file as the remedy.
- **Closed by the fix for review round 1.** Every run reads each shader's entry from its current `.metal`, so the
  next run writes the header that matches it, and no remedy is needed.
  `aShaderWrittenByARunThatStoppedBeforeItsHeadersGetsItsEntryInTheNextRun` covers it; on `a8d6688172` it fails at
  `:465`, with ProbeA's old struct `id<MTLTexture> baseImg; packed_float2 offset;` in the header.
- **With the Decora generator** (measured on round 1's fix before its internal review rounds): with `hue` and
  `saturation` swapped in `ColorAdjust.jsl` and `DecoraShaderCommon.h` read-only, the run exits 1 with `Cannot write
  the Metal header …/DecoraShaderCommon.h` after writing the new `ColorAdjust.metal`. Once the header is writable, a
  run with nothing out of date rewrites only `DecoraShaderCommon.h`, which then has `ColorAdjust_saturation_ID = 1`
  as the `.metal` does. `a8d6688172` rewrites nothing and keeps the stale `ColorAdjust_hue_ID = 1`.

### Owner-run and pending
Replace `<repo-url>` with the repository's remote. `<US-018 commit>` is a commit that holds the round-2 fix, which
`ba2b68e991`, the head of the PR 50 branch, does not ([Implementation](#implementation-2026-11-05-macos)). A clone of
`<repo-url>` has such a commit only once the owner commits the round-2 fix and pushes it (a branch is enough). Until
then, run the gates on a patch from `0281b2fde8` to the working tree, which carries `a8d6688172`, `ba2b68e991` and the
uncommitted round-2 fix. The patch is made in a scratch clone, so that it also takes any untracked file. At the root of
the main repository on the macOS host (its index is not touched):
```bash
R="$PWD"; D="$(mktemp -d)"
git clone -q "$R" "$D/us018" && git -C "$D/us018" checkout -q --detach 0281b2fde8
{ git diff -z --name-only 0281b2fde8; git ls-files -z -o --exclude-standard; } | xargs -0 tar -cf - | tar -xf - -C "$D/us018"
git -C "$D/us018" add -A && git -C "$D/us018" diff --cached --binary 0281b2fde8 > "$D/us018.patch"
git -C "$D/us018" status --short | grep '^A'; echo "patch: $D/us018.patch"
```
The `A` lines must name `MetalHeaders.java`, `JSLCInfoTest.java`, `MetalHeadersTest.java` and `MSLBackendTest.java`.
Copy the patch to the Windows host, into the directory that holds `jfx-before` and `jfx-after`, and to the WSL home
directory. In F8.2 and F8.3, replace the `jfx-after` clone line with:
```bash
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach 0281b2fde8 && git -C jfx-after apply "$PWD/us018.patch"
```
In PowerShell, quote every `-D` argument; under Git Bash, Maven 3.10's `bin/mvn` converts the paths.

#### F8.1 Windows: build without `clean` with `"-Djfx.jsl.backends=-d3d -mtl"`
In Git Bash, at the root of `jfx-after` (F8.2), before or after its gate, which starts with `clean`. The block touches
two `.jsl` files, so run it in a clone. `-DskipNative=true`: only the generators matter, and no Windows target
compiles `.metal` files. The block ran as written in bash on the macOS host: it passes on the change and catches HEAD.
```bash
W="${W:-$(mktemp -d)}"; G=modules/javafx.graphics/target/gensrc
mvn -B -ntp -l "$W/clean.log" -pl modules/javafx.graphics -am clean test-compile "-Djfx.jsl.backends=-d3d -mtl" -DskipNative=true; echo "clean build exit $?"
cp -Rp "$G/mtl-headers" "$W/mtl-headers-clean"
(cd "$G" && find . -type f | LC_ALL=C sort | xargs md5sum) > "$W/gensrc-clean.md5"
touch "$W/marker"; sleep 1
touch modules/javafx.graphics/src/main/jsl-decora/ColorAdjust.jsl modules/javafx.graphics/src/main/jsl-prism/PaintColor.jsl
mvn -B -ntp -l "$W/no-clean.log" -pl modules/javafx.graphics -am test-compile "-Djfx.jsl.backends=-d3d -mtl" -DskipNative=true; echo "no-clean build exit $?"
(cd "$G" && find . -type f | LC_ALL=C sort | xargs md5sum) > "$W/gensrc-no-clean.md5"
cmp "$W/gensrc-clean.md5" "$W/gensrc-no-clean.md5" && echo "gensrc: same $(wc -l < "$W/gensrc-clean.md5") files with the same md5 as the clean build"
for h in DecoraShaderCommon.h PrismShaderCommon.h FragmentShaderCommon.h; do cmp "$W/mtl-headers-clean/$h" "$G/mtl-headers/$h" && echo "$h: byte-identical to the clean build"; done
echo "mtl-headers rewritten by the no-clean build:"; find "$G/mtl-headers" -type f -newer "$W/marker"; echo "(end)"
echo "files rewritten by the no-clean build, per directory:"; (cd "$G" && find . -type f -newer "$W/marker" | cut -d/ -f2 | sort | uniq -c)
grep -c '_Uniform_VarID_Dict() {' "$G/mtl-headers/DecoraShaderCommon.h" "$G/mtl-headers/PrismShaderCommon.h"
grep -c 'isEqualToString:@"' "$G/mtl-headers/DecoraShaderCommon.h" "$G/mtl-headers/PrismShaderCommon.h"
head -n 1 "$G/mtl-headers/DecoraShaderCommon.h" "$G/mtl-headers/PrismShaderCommon.h"
echo "logs, manifests and the clean headers are in $W"
```
It must print:
- `clean build exit 0`, `no-clean build exit 0` and `gensrc: same 817 files with the same md5 as the clean build`;
- three `…: byte-identical to the clean build` lines, and nothing between `mtl-headers rewritten by the no-clean
  build:` and `(end)`;
- the per-directory counts `20 headers`, `4 jsl-decora` and `84 jsl-prism`, with no `mtl-headers` row;
- `…DecoraShaderCommon.h:50` and `…PrismShaderCommon.h:212`, twice each, and the first lines
  `#ifndef DECORA_SHADER_COMMON_H` and `#ifndef PRISM_SHADER_COMMON_H`.

On HEAD it prints `differ` for the manifest and the Decora and Prism headers, all three headers as rewritten, a
`3 mtl-headers` row, and the counts 1 and 28.

#### F8.2 Windows gate
Two sibling checkouts, in Git Bash:
```bash
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach 0281b2fde8
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <US-018 commit>
```
Maven from PowerShell, in the directory that holds both, so that MSVC builds the natives:
```powershell
cd jfx-before; mvn -B -ntp -l ..\us018-windows-before.log -pl buildtools/jslc,modules/javafx.graphics clean test "-Djfx.parity.require=true"; "before exit $LASTEXITCODE"; cd ..
cd jfx-after;  mvn -B -ntp -l ..\us018-windows-after.log  -pl buildtools/jslc,modules/javafx.graphics clean test "-Djfx.parity.require=true"; "after exit $LASTEXITCODE"; cd ..
```
The checks, in Git Bash in the same directory:
```bash
for t in before after; do (cd jfx-$t/modules/javafx.graphics/target/gensrc && find . -type f | LC_ALL=C sort | xargs md5sum) > us018-windows-$t-gensrc.md5; done
classes() { tr -d '\r' < "$1" | sed -nE 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+), Time elapsed: .* -- in ([^ ]+)$/\5 \1 \2 \3 \4/p' | LC_ALL=C sort; }
diff <(classes us018-windows-before.log) <(classes us018-windows-after.log)
for t in before after; do echo "$t:"; tr -d '\r' < us018-windows-$t.log | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|BUILD (SUCCESS|FAILURE)'; done
cmp us018-windows-before-gensrc.md5 us018-windows-after-gensrc.md5 && echo "gensrc identical by name and md5 ($(wc -l < us018-windows-after-gensrc.md5) files)"
find jfx-before jfx-after -maxdepth 3 -name 'hs_err_pid*'; find jfx-before/modules/javafx.graphics/target jfx-after/modules/javafx.graphics/target jfx-before/buildtools/jslc/target jfx-after/buildtools/jslc/target -name 'hs_err_pid*'
```
It must show:
- `before exit 0` and `after exit 0`, and `BUILD SUCCESS` in both logs;
- from `diff`, no `<` line and exactly three `>` lines: `com.sun.scenario.effect.compiler.JSLCInfoTest 3 0 0 0`,
  `com.sun.scenario.effect.compiler.MetalHeadersTest 33 0 0 3` (the three `link/..` tests are disabled on Windows)
  and `com.sun.scenario.effect.compiler.backend.hw.MSLBackendTest 1 0 0 0`;
- jslc `Tests run: 159` before and `Tests run: 196, Failures: 0, Errors: 0, Skipped: 3` after, and the same
  javafx.graphics line in both, with `Failures: 0, Errors: 0`;
- `gensrc identical by name and md5`: Windows generates no `.metal` and no Metal header, and the empty
  `target/gensrc/mtl-headers` that HEAD's pom creates is not in a file manifest;
- nothing from either `find`.

Without `-am`, as the criterion gives the command, Maven takes `org.openjfx:javafx-base:28-ea` and its `tests` jar
from the local repository: that needs an earlier `mvn install`, and with nothing installed the build stops with
`Could not resolve dependencies`. Then add `-am` after the `-pl` value in both runs; javafx.base's 5,510 tests run
too, and the jslc and javafx.graphics counts do not change.

#### F8.3 WSL gate
In WSL (Ubuntu), with JDK 27 and Maven 3.10.0 on `PATH`, from fresh clones, with `-am`, no display and no
`-Djfx.parity.require=true` (the Linux font goldens were captured on another machine):
```bash
cd ~
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach 0281b2fde8
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <US-018 commit>
for t in before after; do
  (cd ~/jfx-$t && env -u DISPLAY -u WAYLAND_DISPLAY mvn -B -ntp -l ../us018-wsl-$t.log -pl buildtools/jslc,modules/javafx.graphics -am clean test; echo "$t exit $?")
  (cd ~/jfx-$t/modules/javafx.graphics/target/gensrc && find . -type f | LC_ALL=C sort | xargs md5sum) > ~/us018-wsl-$t-gensrc.md5
done
classes() { tr -d '\r' < "$1" | sed -nE 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+), Time elapsed: .* -- in ([^ ]+)$/\5 \1 \2 \3 \4/p' | LC_ALL=C sort; }
diff <(classes ~/us018-wsl-before.log) <(classes ~/us018-wsl-after.log)
for t in before after; do echo "$t:"; grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|BUILD (SUCCESS|FAILURE)' ~/us018-wsl-$t.log; done
cmp ~/us018-wsl-before-gensrc.md5 ~/us018-wsl-after-gensrc.md5 && echo "gensrc identical by name and md5 ($(wc -l < ~/us018-wsl-after-gensrc.md5) files)"
find ~/jfx-before ~/jfx-after -maxdepth 3 -name 'hs_err_pid*'; find ~/jfx-before/modules/javafx.graphics/target ~/jfx-after/modules/javafx.graphics/target -name 'hs_err_pid*'
```
It must show what F8.2 shows, with Linux generating `-es2` only and running the three `link/..` tests:
`com.sun.scenario.effect.compiler.MetalHeadersTest 33 0 0 0` and jslc `Tests run: 196, Failures: 0, Errors: 0,
Skipped: 0` after. It must also show javafx.base `Tests run: 5510, Failures: 0, Errors: 0, Skipped: 22` in both runs.
`LinuxFontConfigGoldenTest`, `LinuxFontProcessGoldenTest`, `LinuxFreetypeGoldenTest` and `LinuxPangoGoldenTest` each
skip one test in both runs (US-008).

#### After the gates
The rest of the [Definition of Done](#definition-of-done):
- File the upstream report. Its draft is kept outside the repository until it is filed (`backlog/README.md`).
- Commit the round-2 fix, if the gates ran on the patch. Set the status to ✅ Done with the date and the PR, and move
  the row to the done table of `backlog/README.md`.
