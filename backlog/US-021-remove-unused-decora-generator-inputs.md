# US-021 — Remove the unused Decora and Prism generator inputs

**Status:** 📋 Ready (filed 2026-09-27; the deletion was prototyped on copies of the generator inputs outside the repository, where the generators, compiled and run as the pom does, write the same 1,059 files with every JVM exiting 0; Maven, Linux and macOS not run) · **Found:** 2026-09-27, US-008 (filing US-019: replacing each `.jsl` file in turn with text that cannot lex showed that the build never parses `BoxBlur.jsl`, `GaussianBlur.jsl`, `ZoomRadialBlur.jsl`, `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl`, and reading the Decora drivers showed that four of them are never called)

## Story
As a developer who changes the JSL compiler or the shader build,
I want the Decora drivers and the `.jsl` files that no build step uses removed,
so that every file in `src/main/jsl-decora` and `src/main/jsl-prism` feeds a shipped shader or peer, and a compiler
change is checked against the shaders that ship, not also against dead inputs whose output no longer compiles.

## Problem
Paths are relative to `modules/javafx.graphics` unless they start with `backlog/`. A bare line number (`:NN`) refers to
the file named in the same sentence, else to the one named in the lead-in of its list.

### Four Decora drivers that no build step calls
- The build runs one Decora entry point, `GenAllDecoraShaders` (`pom.xml:156-179`). By reflection it calls
  `CompileJSL` (6 shaders), `CompileBlend`, `CompilePhong` and `CompileLinearConvolve` (2 shaders)
  (`src/main/jsl-decora/GenAllDecoraShaders.java:63-74`, `:89-91`), and no other driver.
- `CompileBoxBlur`, `CompileGaussian`, `CompileZoomRadialBlur` and `CompileExternal` are named nowhere but in their own
  `public class` line and in `backlog/`: `git grep` over the tree (sources, poms, cmake, `.github`, docs, tests,
  untracked files included) finds nothing else. The fork has had no `build.gradle` since `01f190af72`.
- The build still compiles them: the generator `javac` takes every `.java` in `src/main/jsl-decora` (`pom.xml:142-154`,
  source path at `:145`). The US-008 Windows gate's `target/jsl-compilers/decora` holds their 4 classes among its 11.
- What each would write, run directly with the shared options `GenAllDecoraShaders` passes, `-d3d -es2 -mtl` and
  `-java -hw`, and the shader name for the two drivers that take one (`CompileGaussian`, `CompileExternal`):
  - `CompileBoxBlur`: `BoxBlur_10` to `BoxBlur_130` in 13 sizes for each backend (39 shaders), `PPSBoxBlurPeer.java`,
    `JSWBoxBlurPeer.java` and the two Metal headers: 43 files.
  - `CompileGaussian GaussianBlur`: the same for `GaussianBlur`, 43 files. Its other two names, `MotionBlur` and
    `Shadow`, have no `.jsl`: `Input file not found: MotionBlur.jsl`, exit 1.
  - `CompileZoomRadialBlur`: `ZoomRadialBlur_4` to `ZoomRadialBlur_68` in 17 sizes (51 shaders), the two peers and the
    headers: 55 files.
  - `CompileExternal`: compiles each named `.jsl` as `CompileJSL` does. The two `main` methods differ only in the usage
    text, `"<filename>"` for `"<jslfile>+"` (`CompileExternal.java:36-46`, usage at `:37`; `CompileJSL.java:67-77`,
    usage at `:68`). For `Brightpass` both write the same 7 files, and the 5 shaders and peers among them are
    identical by md5 to the build's.
- `GenAllDecoraShaders` appends the shader name to every call (`GenAllDecoraShaders.java:86-88`). `CompileBoxBlur` and
  `CompileZoomRadialBlur` take none: they parse with `parseAllArgs` (`CompileBoxBlur.java:78`,
  `CompileZoomRadialBlur.java:76`), which prints `unrecognized argument: BoxBlur` (or `ZoomRadialBlur`) and calls
  `System.exit(1)` (`src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java:300-311`). Called so, each writes no
  file (measured).
- Half of the generated peers do not compile. Compiled against the gate's `javafx.graphics` classes:
  - `PPSBoxBlurPeer.java`, 3 errors. The glue calls `getEffect().getRadius()` (`src/main/jsl-decora/BoxBlur.jsl:34`,
    `:38`), and `com.sun.scenario.effect.BoxBlur` has horizontal and vertical sizes and passes, no radius. The Prism
    backend calls `getOffsetsArrayLength()` for the array parameter
    (`src/jslc/java/com/sun/scenario/effect/compiler/backend/prism/PrismBackend.java:95`), which the glue lacks.
  - `JSWBoxBlurPeer.java`, 2 errors (`getRadius()`). It also has the name of the module's hand-written
    `src/main/java/com/sun/scenario/effect/impl/sw/java/JSWBoxBlurPeer.java`, which says it "was originally generated
    by JSLC and then hand edited for performance" (`:27-28`).
  - `PPSGaussianBlurPeer.java`, 1 error: `getKvalsArrayLength()`.
  - `JSWGaussianBlurPeer.java`, `PPSZoomRadialBlurPeer.java` and `JSWZoomRadialBlurPeer.java` compile.
  - Both causes are in the first open-source versions. `BoxBlur.java` has no `getRadius()` in the Decora import
    `b4d6d9772f` (2013), and the Prism backend already calls `…ArrayLength()` in `d0a313da5d` (2012).

### Three Decora shaders only those drivers read
- `BoxBlur.jsl`, `GaussianBlur.jsl` and `ZoomRadialBlur.jsl` are read by `CompileBoxBlur`, `CompileGaussian` and
  `CompileZoomRadialBlur` through `getJSLFile()`. The four drivers the build calls read the names `GenAllDecoraShaders`
  passes and, for `Blend`, the 19 `Blend_<mode>.jsl` files. Without the three files the generators still exit 0 and
  write the same files (Measured).
- They are 3 of the 13 `.jsl` files with a glue block (`<<` … `>>`).

### Two Prism shaders nothing reads
- The Prism generator composes its 212 shaders from masks and paints it names in code
  (`src/main/jsl-prism/CompileJSL.java:467-508`). The texture paints it reads are `PaintTextureRGB`,
  `PaintTextureYV12`, `PaintTextureFirstPassLCD`, `PaintTextureSecondPassLCD`, `PaintMaskTextureRGB` and
  `PaintMaskTextureSuper` (`:500-506`). No version of the generator since its import (`839a0d8349`, 2013) mentions
  YUV.
- The build still starts a Prism generator JVM for each of them. The `<apply>` runs `CompileJSL` once per `.jsl` file in
  `src/main/jsl-prism` (`pom.xml:200-216`, fileset at `:215`): 30 JVMs. `CompileJSL` ignores `-name`
  (`CompileJSL.java:421` overwrites the shader name), composes all 212 shaders on every run and, without `-force` (the
  pom passes none), writes only what is out of date (the loaders at `CompileJSL.java:425`, the backends at
  `src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java:193-244`).
  - One JVM with `-name …/PaintTextureYUV422.jsl` and `-d3d` writes the same 424 files as the gate.
  - The two YUV runs took 338 and 345 ms in the full regeneration. Run again on its complete output, they changed
    none of its 1,059 files.
- The runtime has no use for such shaders:
  - The texture shaders it looks up are `Solid_TextureRGB`, `Mask_TextureRGB`, `Solid_TextureYV12`, the two LCD shaders
    and `Mask_TextureSuper` (`src/main/java/com/sun/prism/impl/ps/BaseShaderContext.java:135-141`).
  - There is no YUV 4:4:4 pixel format. The media formats are `MULTI_YCbCr_420` and `BYTE_APPLE_422`
    (`src/main/java/com/sun/prism/PixelFormat.java:46-48`).
  - `BYTE_APPLE_422` (4:2:2) becomes an RGB texture: on ES2 through `GL_APPLE_ycbcr_422`
    (`src/main/java/com/sun/prism/es2/ES2Texture.java:403-409`), on Metal through the hand-written compute kernel
    `uyvy422_to_rgba` (`src/main/native-prism-mtl/MetalTexture.m:243-244`). It is drawn with `TEXTURE_RGB`
    (`BaseShaderContext.java:591`).
- `PaintTextureYUV422.jsl:32` keeps a TODO for JDK-8091282 (open since 2012) in a shader no build compiles.

### What the runtime would look up
Decora finds a peer by the key an effect registers: the class `JSW<key>Peer` in software
(`src/main/java/com/sun/scenario/effect/impl/prism/sw/PSWRenderer.java:248-265`), or `PPS<key>Peer` with the shader
`<key>_<n>` on a GPU (`src/main/java/com/sun/scenario/effect/impl/prism/ps/PPSRenderer.java:306-327`). Both return null
when the class is missing, and `Renderer.getPeerInstance` then throws
(`src/main/java/com/sun/scenario/effect/impl/Renderer.java:263-281`, `:275`).
- `BoxBlur`: requested only in software (`src/main/java/com/sun/scenario/effect/impl/state/BoxRenderState.java:291-313`)
  and served by the hand-written `JSWBoxBlurPeer`. GPUs use `LinearConvolve`.
- `GaussianBlur`: no effect registers it. The Gaussian blur, motion blur and shadows use `LinearConvolve` and
  `LinearConvolveShadow` (`src/main/java/com/sun/scenario/effect/impl/state/LinearConvolveRenderState.java:255-265`).
- `ZoomRadialBlur`: registered by `com.sun.scenario.effect.ZoomRadialBlur`
  (`src/main/java/com/sun/scenario/effect/ZoomRadialBlur.java:146-149`), and no peer of that key exists.
  - Nothing in the tree creates one. Its package is exported only to `javafx.web` (`src/main/java/module-info.java`
    `:156-161`), which does not use it. Outside `src/main/jsl-decora` and `backlog/`, `git grep` finds the name only
    in its own file and in `ZoomRadialBlurState.java`. A byte search of the compiled classes of the 11 modules in this
    checkout's `target` directories finds it only in those two classes.
  - A probe asked production's `Renderer.getPeerInstance` for the key of `new ZoomRadialBlur(5)` (`ZoomRadialBlur`,
    count 8) through a renderer that creates peers as `PSWRenderer` does. It printed
    `Error: CPU/Java peer not found for: ZoomRadialBlur due to error: ` followed by
    `com.sun.scenario.effect.impl.sw.java.JSWZoomRadialBlurPeer`, and got
    `java.lang.RuntimeException: Could not create peer  ZoomRadialBlur for renderer …`. `PPSRenderer` fails the same
    way on a GPU (read, not run). The same probe got the hand-written `JSWBoxBlurPeer` for `BoxBlur`.
  - So every filter through a `ZoomRadialBlur`, and its `getAccelType`, throws today: both look the peer up
    (`src/main/java/com/sun/scenario/effect/CoreEffect.java:64-67`, `:100-107`, `:110-116`). Constructing one does
    not; the probe did. Only `CompileZoomRadialBlur` could make its peers, and those compile (above) when it is called
    without a shader name, but no build runs it.
- Shader resources: the gate's module has none named `BoxBlur_*`, `GaussianBlur_*`, `ZoomRadialBlur_*`,
  `Solid_TextureYUV422*` or `Solid_TextureYUV444*`. The probe found `LinearConvolve_8.obj` and `Solid_TextureYV12.obj`
  as controls. The Windows gate has no generated `.frag` files (its 15 are the hand-written 3D shaders of
  `src/main/resources/com/sun/prism/es2/glsl`), so ES2 names were checked in the generated sources only.

### When and why they became unused
- JDK-8105703 (JavaFX 1.1, resolved 2009-01-10), "Reduce download size by removing BoxBlur and ZoomRadialBlur from
  Decora builds": both "were never exposed at the FX level".
- JDK-8099811 (1.2, 2009) then changed the BoxBlur API from a radius to horizontal and vertical sizes and passes, gave
  it "a generalized linear convolution shader" on the GPU and hand-tuned Java and SSE loops in software. That is why
  the glue of `BoxBlur.jsl` calls a `getRadius()` that no longer exists; the module's `JSWBoxBlurPeer` is such a
  hand-edited peer.
- JDK-8099221 (1.3, 2009) moved `GaussianBlur`, `Shadow` and last `MotionBlur` to the `LinearConvolve` shaders, and
  "the last of the effect-specific gaussian code was purged". `CompileGaussian` and `GaussianBlur.jsl` stayed.
- In the Decora import `b4d6d9772f` (2013-02-07), the Ant build called neither `CompileBoxBlur` nor `CompileGaussian`
  and had `CompileZoomRadialBlur` commented out (`decora-runtime/build.xml:107-124`, `:120` at that commit).
  `CompileExternal` compiled two test shaders, `testjsl/Test1.jsl` and `Test2.jsl`, in the Ant `test` target (`:45-58`,
  `:93-103`, `:135-144`).
- `b56a9aa393` (2013-06-27, "remove obsolete test files") deleted those test shaders and the two calls. The Ant files
  were deleted in `093cfcc948` (2013-10-16).
- The Gradle build never called the 4 drivers. The first `build.gradle` (`420f2d72f2`, 2013-02-01) already compiled
  the same 10 Decora shaders as `GenAllDecoraShaders` does now (`build.gradle:143-152` at that commit). No version of
  a `.gradle` file or of `GenAllDecoraShaders.java` names any of the 4 drivers (`git log -S`; the same search without
  the path filter finds the drivers' own commits).
- Content unchanged since, apart from the moves `bc5885c8a0` and `c420248b9b`: the 7 Decora files since `293263d095`
  (2013-02-18, copyright header), `PaintTextureYUV444.jsl` since its import `839a0d8349` (2013-02-25).
  `PaintTextureYUV422.jsl`, imported there too, had two mechanical edits in 2024 (`03eb8b11af`, bug-ID mapping;
  `f06b15b6e6`, copyright year).

### Upstream too
- `openjdk/jfx` master (`d46e6092ae`, checked 2026-09-27) has all 9 files, identical to this tree's (same git blob
  ids), and the runtime `ZoomRadialBlur.java` and `ZoomRadialBlurState.java`, identical as well.
- Its `GenAllDecoraShaders` compiles the same 10 shaders (`GenAllDecoraShaders.java:34-45` there). Its `build.gradle`
  runs only `GenAllDecoraShaders` for Decora (`:2519-2521`) and `CompileJSL` once per Prism `.jsl` (`:2621-2645`).
- This tree's `GenAllDecoraShaders.java` differs from upstream's (rewritten in `26ce75d02f`) but calls the same
  compiles.
- JBS (searched 2026-09-27, project JDK, text): `CompileBoxBlur`, `CompileGaussian`, `CompileExternal` and
  `GenAllDecoraShaders` 0 issues; `ZoomRadialBlur` 1 (JDK-8105703); `PaintTextureYUV422` 1 (JDK-8091282, Open);
  `YUV444` in component `javafx` 0. No issue proposes deleting them.

### Measured (2026-09-27, Windows 10, JDK 26)
**Setup.** Two copies of `src/main/jsl-decora` and `src/main/jsl-prism`: the full set, and the reduced set without the
9 files. From each, the generators were compiled as the pom compiles them (`--release 25`, `-implicit:none`, the gate's
`javafx.graphics` and `javafx.base` classes on the module path) and run as the pom runs them (`pom.xml:156-216`), with
the gate's jslc classes.
- **Generator classes.** The full set gives 19 classes, byte-identical to the gate's `target/jsl-compilers`. The
  reduced set gives 15, each byte-identical to its full counterpart; the 4 driver classes are the difference.
- **All backends.** Both sets, run with `-d3d -es2 -mtl`, write 1,059 files (208 `jsl-decora`, 848 `jsl-prism`, 3
  `mtl-headers`), identical by name and md5, and every JVM exits 0. The files are also identical to US-019's
  regeneration.
- **Windows gate backends.** The reduced set with `-d3d` writes 532 files, identical by name and md5 to the gate's
  `target/gensrc/{jsl-decora,jsl-prism,mtl-headers}` (108, 424, 0).
- **The check can fail.** With `SepiaTone.jsl` and `PaintTextureYV12.jsl` also removed, the Decora JVM and all 27
  Prism JVMs exit 1 with `Input file not found: SepiaTone.jsl` and `Input file not found: PaintTextureYV12.jsl`. A
  build step that read a deleted file would fail the same way.
- **JVMs.** 31 for the full set (1 Decora, 30 Prism), 29 for the reduced set. The first Prism JVM writes every Prism
  file and header (4.3 s here); each later one took 0.34 to 1.1 s, median 0.35 s.
- **Not run:** Maven, the module's second-pass `javac`, `fxc`, the Metal compiler, Linux and macOS. The second-pass
  `javac` reads `src/main/java` and the generated sources, both unchanged by (A), so its classes and 46 headers should
  be unchanged too; the build gate below checks that.

## Proposed fix
Three options; the scope is part of the story.
- **(A) Delete the 9 files, in the style of US-008 (recommended).** 4 `.java` and 5 `.jsl` files, 663 lines. Nothing
  else changes: `GenAllDecoraShaders` does not name them; the pom takes the directory (`pom.xml:145`) and a `**/*.jsl`
  fileset (`:215`); `CompileJSL.readFile` stays, for `CompileBlend`, `CompilePhong` and `CompileLinearConvolve`.
  - Each build compiles 4 classes fewer and starts 2 Prism JVMs fewer (0.35 s each here).
  - The generated files and the module's classes and resources stay the same.
- **(B) The runtime `ZoomRadialBlur`.** After (A) nothing in the tree can generate its peers. Either:
  - **(B1) delete it too (recommended):** `src/main/java/com/sun/scenario/effect/ZoomRadialBlur.java` and
    `src/main/java/com/sun/scenario/effect/impl/state/ZoomRadialBlurState.java`, 314 lines. Nothing creates it, every
    filter through it (and `getAccelType`) throws, and its package is exported only to `javafx.web`, which does not
    use it. The module loses exactly these 2 classes; or
  - **(B2) keep it,** and file its removal as a story of its own.
- **(C) Keep the files and document them:** a comment in `GenAllDecoraShaders` on why the 4 drivers are not called, and
  one in each YUV shader.
  - The build keeps compiling 4 unused classes and starting 2 JVMs that do nothing.
  - `CompileBoxBlur` and `CompileZoomRadialBlur` reject the shader name `GenAllDecoraShaders` appends, and 3 of the 6
    peers the drivers generate do not compile, so the drivers are not a switch that could be turned back on.
  - JSL compiler work keeps special-casing them: US-019's acceptance criteria run the three drivers and parse the
    YUV shaders as extra cases, and 3 of the 13 glue-block files US-020 counts are among them.
  - Upstream's open JDK-8091933 (support for YUV pixel formats, 2012) would need new wiring in any case: no generator
    has compiled these shaders since 2013.

**What upstream merges cost.** Upstream master still has every file (B1's included). This tree has taken no upstream
commit since `ca9b07aeae` (2026-08-26) and already deletes upstream files (`build.gradle` in `01f190af72`), so a
future merge needs manual resolution anyway.
- Each deleted file adds a modify/delete conflict only when upstream edits it, resolved by keeping the deletion.
- Upstream edited none of the 9 in content since 2013, apart from the two mechanical 2024 edits of
  `PaintTextureYUV422.jsl`.
- `ZoomRadialBlur.java` last changed, apart from a copyright header (`41b26e56ea`, 2014), with an API change across
  all Decora effects (`e747f870ca`, 2014); such a change would conflict on it.

**Recommendation: (A) with (B1).** Upstream too, the Decora effects behind the three drivers have been out of the
build since 2009 and `CompileExternal` since 2013, and no open-source generator has ever compiled the YUV shaders. Half
of the drivers' generated peers do not compile. Deleting the files changes no generated file, and the one runtime class
they could serve does not work without them.

## Acceptance criteria
- **Files deleted.** The 9 files of (A), and with (B1) the 2 runtime files; no other file outside `backlog/` changes.
  `git ls-files` lists them before the change and none of them after.
- **No reference left.** Outside `backlog/`, `git grep --untracked` finds none of `CompileBoxBlur`, `CompileGaussian`,
  `CompileZoomRadialBlur`, `CompileExternal`, `BoxBlur.jsl`, `GaussianBlur.jsl`, `ZoomRadialBlur.jsl`,
  `PaintTextureYUV422` and `PaintTextureYUV444`, and with (B1) no `ZoomRadialBlur` either. The same search before the
  change must find the 4 drivers' `public class` lines and, for `ZoomRadialBlur`, 4 files (the 2 runtime files,
  `CompileZoomRadialBlur.java` and `ZoomRadialBlur.jsl`), so an empty result cannot come from a wrong path or
  pattern. The 5 shader names match nothing before the change either; `git ls-files` above covers them.
- **No build step reads them.** Regenerate Decora and Prism with `-d3d -es2 -mtl`, the generators run as the pom runs
  them, before and after the change. All 1,059 files are identical by name and md5, and every generator JVM exits 0.
  The check can fail: a scratch copy without an input the build needs (for example `SepiaTone.jsl`) must exit 1 with
  `Input file not found`.
- **Generator classes.** `target/jsl-compilers/decora` holds 7 classes instead of 11. They and the 8 classes of
  `target/jsl-compilers/prism` are byte-identical to before.
- **Build gate,** with the two commands the US-008 gate ran:
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`
    (the Linux font goldens were captured on another machine; see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` is identical by name and md5 before and after. In the
    US-008 gate these held 108, 424, 46 and 0 files on each system; `mtl-headers` is empty there, so the Metal output
    is covered only by the `-d3d -es2 -mtl` regeneration above.
  - `target/classes` has the same file names before and after. With (B1) it has exactly two fewer,
    `com/sun/scenario/effect/ZoomRadialBlur.class` and `com/sun/scenario/effect/impl/state/ZoomRadialBlurState.class`.
  - `javafx.graphics` has the same test counts per class as before, and `javafx-jslc` runs its 159 tests with no
    failure.
- **With (B1), no compiled reference.** After the gate, a byte search for `ZoomRadialBlur` in
  `modules/javafx.graphics/target/{classes,mods,shims,test-classes}` finds nothing. The same search in the build
  before the change must find 8 files, `ZoomRadialBlur.class` and `ZoomRadialBlurState.class` in each of `classes`,
  `mods/javafx.graphics` (the second-pass `javac` output, `pom.xml:220-246`), `shims/javafx.graphics` and
  `test-classes`.
- **Upstream.** Repeat the JBS search when the story is picked up, and decide whether to propose the same deletion
  upstream. A draft stays outside the repository.

## Definition of Done
- All acceptance criteria met, with the logs, the md5 lists and the `target/classes` file lists of both gate runs and
  of the regeneration kept with the change.
- The chosen scope, (A) with (B1), (A) with (B2) or (C), and the reason recorded in this file. With (B2), the runtime
  `ZoomRadialBlur` filed as its own story.
- Any open story that uses the deleted files updated: US-019's "Generated files unchanged" criterion runs the three
  drivers and parses the two YUV shaders, and US-020 counts 13 glue-block files, 3 of them deleted here.
- An independent review repeated the regeneration and the missing-input check.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

## Notes
- Filed rather than fixed in US-008: US-008 removes dead code from the JSL compiler without changing what it accepts
  or writes. This story changes the build's inputs, and with (B1) the module's classes, so it needs its own change and
  its own gate.
- No driver can be added back to `GenAllDecoraShaders` as it is. `CompileBoxBlur` and `CompileZoomRadialBlur` reject
  the shader name it appends (measured: exit 1, no file), and in the Decora JVM that `System.exit(1)` would fail the
  build (`failonerror`, `pom.xml:156`). `CompileGaussian` takes the name, but its `PPSGaussianBlurPeer.java` would
  break the second-pass `javac` with the compile error above, and so would a `CompileBoxBlur` called without the name,
  with its peers' errors and a second `JSWBoxBlurPeer` next to the hand-written one (inferred, not run). Called
  without the name, `CompileZoomRadialBlur` writes peers that compile.
- The scratch copies, the generator runs and the peer lookup probe were not kept in the repository. The Measured and
  runtime sections describe them fully enough to rebuild.
