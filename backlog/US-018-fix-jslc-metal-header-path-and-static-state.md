# US-018 — Stop the jslc Metal backend depending on `jsl-` in the output path and on static state

**Status:** 🔷 Implemented (2026-11-05), uncommitted · **Found:** 2026-09-27, US-008 (review of the jslc test port: `SymbolTest` would have passed for any program that parses, because the Metal backend threw)

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
Made on `0281b2fde8` (HEAD below) and uncommitted at the time of writing. It was built and tested on macOS 27.0.1
(arm64) with Apache Maven 3.10.0 and Oracle JDK 27.0.1. This host has no Windows and no Linux, so those gates are
[owner-run](#owner-run-and-pending).

### Change
- `JSLCInfo` has a Metal header directory (`metalHeaderDir`, option `-mtlheaders <dir>`; by default `mtl-headers` in
  `outDir`, or in the working directory without one), a kind (`metalHeaderKind`, `DECORA` unless `-mtlprism`; the
  Prism `CompileJSL` sets `PRISM` in code) and the run's headers (`metalHeaders`). Both generator invocations of the
  pom pass `-mtlheaders ${jfx.graphics.gensrc}/mtl-headers`, and its `<mkdir>` of that directory is gone.
- The new `backend/hw/MetalHeaders.java` holds the headers of one generator run. `JSLC.compile` adds every Metal
  shader of the run to it: a compiled shader with its entry, an up-to-date one with an entry that is computed only if
  the header has to be assembled. `GenAllDecoraShaders` shares one run among the drivers it calls.
- `MetalHeaders.write()` runs once at the end of a run: in `JSLC.main`, `GenAllDecoraShaders`, the Prism `CompileJSL`
  and the `main` of every Decora driver, so a driver run by hand writes its headers too. It creates the directory and
  writes `FragmentShaderCommon.h` and the Objective-C header only when their bytes differ. The Objective-C header is
  assembled again when a shader of the run was compiled, or when the file does not start with the kind's prologue and
  end with the run's `get<KIND>Dict`. A directory that cannot be created or a header that cannot be written throws an
  `IOException` that names the path. A run without Metal output writes nothing, so a build without `-mtl` (Windows,
  Linux) no longer has an empty `target/gensrc/mtl-headers`.
- `MSLBackend` keeps no per-compile or per-run data in static fields. Its static fields are `MAIN` and four immutable
  maps, all `static final`.
- Every changed production file is under 900 lines; the largest, `src/main/jsl-prism/CompileJSL.java`, has 565.

### Acceptance criteria and evidence
| Criterion | Evidence | State |
| --- | --- | --- |
| Header directory per compile | The criterion's `git grep` finds nothing, and neither does `grep -rn` with the same patterns over `src/jslc`, which reads untracked files too. `MSLBackendTest` pins the static fields, `JSLCInfoTest` the options | Met |
| A path without `jsl-` works | `aPathWithoutJslGetsTheMetalShaderThePeersAndTheHeaders` ([Tests](#tests)) | Met |
| No carry-over between compiles | `twoOutputRootsInOneJvmGetTheirOwnCompleteHeaders`, `aPrismRunAfterADecoraRunGetsAPrismHeaderWithOnlyItsShader`, `anEarlierJslInThePathDoesNotMoveTheHeaders` | Met |
| Write errors fail | `aHeaderDirectoryThatCannotBeCreatedFailsNamingIt`, `aHeaderThatCannotBeWrittenFailsNamingIt`; both generators exit 1 ([Generator runs](#generator-runs)) | Met |
| Incremental runs keep complete headers | `aSecondRunWithOneShaderOutOfDateKeepsTheHeadersOfBoth` and its `jsl-decora` twin; the generators outside Maven; F5 and F7 | Met on macOS; the Windows run (F8.1) is owner-run |
| Generated files unchanged | The generators outside Maven and Maven, both with `-d3d -es2 -mtl` ([Generator runs](#generator-runs)); F2 and F4 | Met |
| Build gate | F1 on macOS; Windows (F8.2) and WSL (F8.3) | Met on macOS; Windows and WSL owner-run |
| macOS | F4, F5 and F6 | Met |
| Upstream | [Upstream check](#upstream-check-2026-11-05-macos); the draft report, kept outside the repository | Checked and drafted; filing pending |

### Tests
- **jslc.** `mvn -B -ntp -pl buildtools/jslc test` runs 182 tests with 0 failures, 0 errors and 0 skipped, in the
  default method order and in a random one (`MethodOrderer$Random`), with the same counts per class. The 22 existing
  classes keep their 159 tests, and `SymbolTest` keeps its message check. New: `MetalHeadersTest` 19, `JSLCInfoTest`
  3 and `backend.hw.MSLBackendTest` 1.
- **Fail-before.** Variants of the new tests ran on HEAD, one Maven/surefire JVM per method. They use a local `Kind`
  enum, create the header directory as HEAD's pom did and do not call `write()`. 15 of the 18 methods that HEAD can
  run fail; the 5 methods that exercise only the new API cannot run there.

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

  Three guards pass on HEAD as well: a rerun with nothing out of date parses and writes nothing, a run without Metal
  output writes no headers, and the header bytes equal a golden captured from HEAD's compiler.
- **Mutation check.** The review ran two rounds of mutants of `MetalHeaders`, `JSLC` and `MSLBackend` (55, then 28).
  One gap mattered: without the rule "a compiled shader makes `write()` assemble the header again", a uniform edit
  keeps the old struct, IDs and dictionary in the header, because the shader names do not change.
  `aShaderRecompiledWithOtherUniformsGetsItsNewEntryInTheHeader` covers it. Its second run compiles the edited shader
  before an up-to-date one, as `GenAllDecoraShaders` compiles `ColorAdjust` before 49 other shaders, and it fails
  under the three mutants of that rule: the flag "a shader of the run was compiled" never set, the flag ignored by
  `write()`, and the flag assigned per add instead of accumulated. Four mutants survive and were not taken up as
  findings: the directory-creation `IOException` swallowed, the write-if-changed check comparing lengths only,
  `FragmentShaderCommon.h` written only when missing, and `-force` no longer recompiling the Metal output.

### Generator runs
- **The pom's way, outside Maven** (`GenAllDecoraShaders` in one JVM, `CompileJSL` once per Prism `.jsl` file,
  `-d3d -es2 -mtl`, copies of the `.jsl` directories as input):
  - **Clean:** 1,059 files (`jsl-decora` 208, `jsl-prism` 848, `mtl-headers` 3), identical by name and md5 to HEAD's.
    The headers list 50 of 50 and 212 of 212 shaders.
  - **Rerun with nothing changed:** 0 of the 1,059 file times move.
  - **After touching `ColorAdjust.jsl` and `PaintColor.jsl`:** 117 files are rewritten (5 Decora, 112 Prism, no
    header), and the tree is identical by md5 to the clean one. HEAD rewrites 120, and its headers list 1 of 50 and
    28 of 212.
  - **Repair:** over a time-preserving copy of HEAD's truncated tree, a run with nothing changed rewrites only
    `DecoraShaderCommon.h` and `PrismShaderCommon.h`, and the tree then equals the clean one. The next rerun rewrites
    nothing.
  - **Exit codes:** with `gensrc/mtl-headers` a regular file, `GenAllDecoraShaders` and the first Prism `CompileJSL`
    exit 1 with `Cannot create the Metal header directory <path>`. With `DecoraShaderCommon.h` and
    `PrismShaderCommon.h` directories, both exit 1 with `Cannot write the Metal header <path>`, on a fresh output and
    over an up-to-date one. HEAD exits 0 in the two fresh-output cases, after printing the exceptions; it was not run
    over an up-to-date output, where it compiles nothing and so writes no header.
  - **Time:** a rerun with nothing changed takes about 0.15 s more over the 31 JVMs, about 5 ms per JVM.
- **Maven with all three hardware backends:** `mvn -B -ntp -pl modules/javafx.graphics -am clean test-compile
  "-Djfx.jsl.backends=-d3d -es2 -mtl" -DskipNative=true`. `target/gensrc` holds the same 1,079 files as HEAD's,
  identical by name and md5, including the 20 `javac -h` headers. The three headers are in
  `target/gensrc/mtl-headers` under the same names.
- **Two of the four drivers the build does not run:** `CompileBoxBlur -mtl -hw` and `CompileExternal SepiaTone`, each
  with its own `-mtlheaders`, exit 0 and write headers that list their 13 and 1 shaders. `CompileGaussian` and
  `CompileZoomRadialBlur` got the same `write()` call but were not run.

### Final gates (2026-11-05, macOS)
F1 to F3 ran in the main tree, and F4 to F7 in a clone of HEAD that holds the change and whose path has no `jsl-`.
"Before the fix" is the same steps on HEAD, on this host. Counts are run / failures / errors / skipped.

| Gate | Command | Result |
| --- | --- | --- |
| F1 module gate | `mvn -B -ntp -Dmaven.test.failure.ignore=true -pl buildtools/jslc,modules/javafx.graphics -am clean test` | BUILD SUCCESS. jslc 182 / 0 / 0 / 0 in 25 classes, base 5,510 / 0 / 0 / 22, graphics 26,636 / 0 / 0 / 824. Each of the 22 old jslc, 283 base and 622 graphics classes has the run and skipped counts of the run before the fix, and `SymbolTest` keeps its message check. `MacFontProcessGoldenTest` passes (before the fix it had 1 failure, because that clone was under `/tmp`; see `backlog/US-001b-macos-gate.md:56-68`). No `hs_err_pid*` file, and no Metal header error in the log |
| F2 generated files | F1's `target/gensrc` and natives | 817 files (`jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3, `headers` 20), identical by name and md5 to before the fix. `DecoraShaderCommon.h` lists 50 of 50 shaders and `PrismShaderCommon.h` 212 of 212, and all three headers are byte-identical. `libprism_mtl.dylib` (272,192 bytes) and `jfxshaders.metallib` (1,621,807 bytes) are md5-identical to before the fix; `libglass.dylib` differs only by the checkout path it embeds |
| F3 Metal goldens | `mvn -B -ntp -pl modules/javafx.graphics -am test '-Dtest=MTL*' -Dsurefire.failIfNoSpecifiedTests=false -Djfx.parity.require=true -DskipNative=true` | 55 / 0 / 0 / 0 in 7 classes, equal per class to before the fix. Its `jsl-codegen` rerun over F1's output rewrote no `jsl-decora`, `jsl-prism` or `mtl-headers` file |
| F4 clean native build | `mvn -B -ntp -pl modules/javafx.graphics -am clean test-compile` | `target/gensrc` as in F2. `libprism_mtl.dylib`, `jfxshaders.metallib` and `MetalShader.m.o` are md5-identical to before the fix |
| F5 no `clean` after a `.jsl` edit | `touch` of `ColorAdjust.jsl` and `PaintColor.jsl`, then F4's command without `clean` | `target/gensrc` md5-identical to F4's. 108 files are rewritten (4 Decora, 84 Prism, the 20 `javac -h` headers) and no Metal header: the three keep F4's times to the nanosecond and list 50 and 212. CMake rebuilds only the 29 changed `.air` files and relinks `jfxshaders.metallib` (same md5); `MetalShader.m` is not recompiled and `libprism_mtl.dylib` not relinked. Before the fix: 111 files rewritten, headers listing 1 and 28, `MetalShader.m` recompiled and `libprism_mtl.dylib` relinked at 163,056 bytes |
| F6 Metal goldens after F5 | F3's command | 55 / 0 / 0 / 0; the shader-table test sees 262 shaders, none with an empty uniform table. Before the fix: 5 failures (4 in `MTLGoldenRenderTest`, 1 in `MTLShaderTableGoldenTest`); the 233 shaders the headers no longer list have no uniform table, and the first texture binding on one of them throws a `NullPointerException` in `MTLShader.setTexture` (`MTLShader.java:113`) |
| F7 `-d3d -mtl` without `clean` | F4's command with `"-Djfx.jsl.backends=-d3d -mtl" -DskipNative=true`, F5's `touch`, then the same without `clean` (the stand-in for F8.1) | Clean: 817 files, each md5-equal to its file in HEAD's `-d3d -es2 -mtl` build, and headers listing 50 and 212. Without `clean`: the headers byte-identical with unchanged times, the whole `target/gensrc` md5-identical, 108 files rewritten and none of them a header. Before the fix the same Maven steps leave 1 and 28 and rewrite all three headers |

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
  after the same `touch` (F5, before the fix).
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

### Accepted risk R1: a stale entry after an interrupted run
- **Trigger.** A run writes a `.metal` file whose uniforms changed and stops before `write()`: the JVM is killed, or a
  header write fails and the header becomes writable again later. The next run finds every output up to date.
- **Effect.** An Objective-C header that survived intact still starts with the kind's prologue and ends with the
  run's `get<KIND>Dict`, so the names check keeps it with the shader's old struct, IDs and dictionary, and
  `MetalShader.m` is built against uniforms that do not match the `.metal` file.
- **Why accepted.** It needs an interrupted or failed run, which is rare and visible. A time rule, assembling the
  header whenever a `.metal` file is newer than it, would close the gap, but it would lose the kept header times (so
  `MetalShader.m` would be recompiled after every `.jsl` edit) and the repair of truncated or foreign headers.
- **Remedy.** A `clean` build, `-force`, or a `touch` of any `.jsl` file of that generator: a compiled shader makes
  the run assemble the header again from every shader's current program.

### Owner-run and pending
Replace `<repo-url>` with the repository's remote. `<US-018 commit>` is the story's commit; a clone of `<repo-url>`
has it only once it is pushed (a branch is enough). Until then, run the gates on a patch of the working tree. `git
diff` leaves out untracked files, `MetalHeaders.java` and the three new test classes among them, so the patch is made
in a scratch clone. At the root of the main repository on the macOS host (its index is not touched):
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
  `com.sun.scenario.effect.compiler.MetalHeadersTest 19 0 0 0` and
  `com.sun.scenario.effect.compiler.backend.hw.MSLBackendTest 1 0 0 0`;
- jslc `Tests run: 159` before and `Tests run: 182, Failures: 0, Errors: 0, Skipped: 0` after, and the same
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
It must show what F8.2 shows, with Linux generating `-es2` only, and also javafx.base `Tests run: 5510, Failures: 0,
Errors: 0, Skipped: 22` in both runs. `LinuxFontConfigGoldenTest`, `LinuxFontProcessGoldenTest`,
`LinuxFreetypeGoldenTest` and `LinuxPangoGoldenTest` each skip one test in both runs (US-008).

#### After the gates
The rest of the [Definition of Done](#definition-of-done):
- File the upstream report. Its draft is kept outside the repository until it is filed (`backlog/README.md`).
- Commit, if the gates ran on the patch. Set the status to ✅ Done with the date and the PR, and move the row to the
  done table of `backlog/README.md`.
