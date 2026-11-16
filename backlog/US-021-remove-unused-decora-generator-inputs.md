# US-021 — Remove the unused Decora and Prism generator inputs

**Status:** 🔷 Implemented (2026-11-15; filed 2026-09-27); the Windows and WSL gates and filing the upstream issues
are [owner-run](#owner-run-and-pending) · **Found:** 2026-09-27, US-008 (filing US-019: replacing each `.jsl` file
in turn with text that cannot lex showed that the build never parses `BoxBlur.jsl`, `GaussianBlur.jsl`,
`ZoomRadialBlur.jsl`, `PaintTextureYUV422.jsl` and `PaintTextureYUV444.jsl`, and reading the Decora drivers showed
that four of them are never called)

## Story
As a developer who changes the JSL compiler or the shader build,
I want the Decora drivers and the `.jsl` files that no build step uses removed,
so that every file in `src/main/jsl-decora` and `src/main/jsl-prism` feeds a shipped shader or peer, and a compiler
change is checked against the shaders that ship, not also against dead inputs whose output no longer compiles.

## Problem
This section, "Measured" and "Proposed fix" describe the tree of 2026-09-27 and are kept as filed. Line numbers, and
the statements listed in [Drift since filing](#drift-since-filing), have changed since (2026-11-15).

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
- **Build gate,** with the two commands the US-008 gate ran, the Windows one with `-am` (amended 2026-11-15, as
  US-019's and US-020's owner-run gates have it: without it the build takes `javafx-base` from the local repository):
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`
    (the Linux font goldens were captured on another machine; see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,mtl-headers}` is identical by name and md5 before and after. On each system
    `jsl-decora` holds 108 files and `jsl-prism` 424, and there is no Metal header, so the Metal output is covered only
    by the `-d3d -es2 -mtl` regeneration above (amended 2026-11-15: `headers` and its 46 files are dropped, because
    `9b02d6a602`, #57, removed the second-pass `javac -h` and the directory no longer exists).
  - `target/classes` has the same file names before and after. With (B1) it has exactly two fewer,
    `com/sun/scenario/effect/ZoomRadialBlur.class` and `com/sun/scenario/effect/impl/state/ZoomRadialBlurState.class`.
  - `javafx.graphics` has the same test counts per class as before, and `javafx-jslc` runs its 276 tests with no
    failure.
- **With (B1), no compiled reference.** After the gate, a byte search for `ZoomRadialBlur` in
  `modules/javafx.graphics/target/{classes,mods,shims,test-classes}` finds nothing. The same search in the build
  before the change must find 8 files, `ZoomRadialBlur.class` and `ZoomRadialBlurState.class` in each of `classes`,
  `mods/javafx.graphics` (the second-pass `javac` output, `pom.xml:208-228`; amended 2026-11-15, was `:220-246`),
  `shims/javafx.graphics` and `test-classes`.
- **Upstream.** Repeat the JBS search when the story is picked up, and decide whether to propose the same deletion
  upstream. A draft stays outside the repository.

## Definition of Done
- All acceptance criteria met, with each gate's per-class list, `target/gensrc` md5 list and `target/classes` file
  list, and the regeneration's md5 list, kept with the change (amended 2026-11-15, as US-019's and US-020's were: the
  gate logs stay with the clones and are not kept; the kept files are listed in
  [Implementation](#implementation-2026-11-15-macos)).
- The chosen scope, (A) with (B1), (A) with (B2) or (C), and the reason recorded in this file. With (B2), the runtime
  `ZoomRadialBlur` filed as its own story.
- Any open story that uses the deleted files updated (amended 2026-11-15 after the independent review, finding 4).
  US-019 and US-020, named here when the story was filed, are 🔷 Implemented, and their records and pending gates pin
  their own commits, so they stay as written. The open stories that describe the deleted files are updated instead:
  US-022, US-023 and US-113.
- An independent review repeated the regeneration and the missing-input check.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

## Notes
- Filed rather than fixed in US-008: US-008 removes dead code from the JSL compiler without changing what it accepts
  or writes. This story changes the build's inputs, and with (B1) the module's classes, so it needs its own change and
  its own gate.
- No driver can be added back to `GenAllDecoraShaders` as it is (amended 2026-11-15 after the independent review,
  finding 3). Since `acf30cfe29` (#50, 2026-11-07), `GenAllDecoraShaders` looks up `compile(String[], MetalHeaders)`
  reflectively (`GenAllDecoraShaders.java:94-96`), which none of the 4 deleted drivers has: each would fail with
  `NoSuchMethodException` before any argument is parsed. The rest of this paragraph is what was true when the story
  was filed, when `GenAllDecoraShaders` called each driver's `main`. `CompileBoxBlur` and `CompileZoomRadialBlur`
  reject the shader name it appends (measured: exit 1, no file), and in the Decora JVM that `System.exit(1)` would
  fail the build (`failonerror`, `pom.xml:156` then, `:140` at `7a66c85e39`). `CompileGaussian` takes the name, but
  its `PPSGaussianBlurPeer.java` would break the second-pass `javac` with the compile error above, and so would a
  `CompileBoxBlur` called without the name, with its peers' errors and a second `JSWBoxBlurPeer` next to the
  hand-written one (inferred, not run). Called without the name, `CompileZoomRadialBlur` writes peers that compile.
- The scratch copies, the generator runs and the peer lookup probe were not kept in the repository. The Measured and
  runtime sections describe them fully enough to rebuild.

## Implementation (2026-11-15, macOS)
The change is described against `7a66c85e39`, the baseline ("before"). Everything was built and run on one arm64 Mac:
macOS 27.2.1 (build 26B131), Apache Maven 3.10.0 and Oracle Corporation's OpenJDK 27.0.1.0.2 (build
27.0.1.0.2+1-2), as `sw_vers`, `mvn -version` and `java -version` report on that host; the module gate's native build
used cmake 4.4.4. This host has no Windows and no Linux, so those gates are [owner-run](#owner-run-and-pending). Paths
are relative to `modules/javafx.graphics`, as in Problem.

The deletion was made by the repository's Java agent. A gate, a regeneration outside Maven, an independent review and
an upstream check followed, the first three in clones of their own. The review's two must-fix items were in this
file's text, which still described the tree of 2026-09-27; they are fixed here
([Independent review](#independent-review-2026-11-15)).

Kept with the change:
- [`US-021-macos-per-class.tsv`](US-021-macos-per-class.tsv), the module gate's per-class list, before and after;
- [`US-021-macos-gensrc.md5`](US-021-macos-gensrc.md5), the after run's `target/gensrc` md5 list (the before run's is
  `cmp`-identical);
- [`US-021-macos-classes.txt`](US-021-macos-classes.txt), the after run's `target/classes` file list, 3,902 names (the
  before run's is this list plus `./com/sun/scenario/effect/ZoomRadialBlur.class` and
  `./com/sun/scenario/effect/impl/state/ZoomRadialBlurState.class`);
- [`US-021-regeneration.md5`](US-021-regeneration.md5), the after side's 1,059 files of the regeneration (the before
  side's is `cmp`-identical).

The scratch clones, the gate and build logs, the probes and the JFR recordings are not kept. The sections below
describe each run fully enough to repeat it.

### Choice: (A) with (B1)
- No build step calls the 4 drivers or reads the 5 shaders, measured again on 2026-11-15, a JFR file-read recording
  included ([Generator runs](#generator-runs)).
- Without the 11 files, the generated files, the kept generator classes and every remaining class of the module are
  byte-identical ([Final gates](#final-gates-2026-11-15-macos)).
- The runtime `ZoomRadialBlur` cannot work: no peer for it exists, and the only thing that could generate one is
  `CompileZoomRadialBlur`, which (A) deletes. The review's probe with the real `PSWRenderer` shows that constructing
  one succeeds and that `getAccelType` and filtering throw ([Independent review](#independent-review-2026-11-15)).
- (B2) would keep two classes that throw on every use; (C) would keep compiling 4 unused classes and starting 2 JVMs
  that do nothing.

What is lost, and what replaces it (review check b):
- `CompileExternal`, a tool to compile named `.jsl` files by hand. The Decora `CompileJSL` main takes the same
  `<jslfile>+` arguments: run with `-d3d -es2 -mtl -java -hw Brightpass`, both write the same 7 files, identical by
  md5, and the 5 shaders and peers among them equal the build's. `JSLC.main`
  (`src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java:473`) compiles one stand-alone `.jsl` file by path.
- The three blur drivers and their `.jsl` files stay in git history. 3 of the 6 peers they generate did not compile
  ([Four Decora drivers that no build step calls](#four-decora-drivers-that-no-build-step-calls)).
- Nothing kept depends on what is deleted: the deleted drivers called the kept `CompileJSL.readFile`, and nothing
  called into them.

### Change
Relative to `7a66c85e39`, 11 files are deleted; nothing is edited and nothing is added. Lines by `wc -l` at the
baseline:

| File | Lines |
| --- | --- |
| `src/main/jsl-decora/CompileBoxBlur.java` | 82 |
| `src/main/jsl-decora/CompileExternal.java` | 48 |
| `src/main/jsl-decora/CompileGaussian.java` | 96 |
| `src/main/jsl-decora/CompileZoomRadialBlur.java` | 80 |
| `src/main/jsl-decora/BoxBlur.jsl` | 94 |
| `src/main/jsl-decora/GaussianBlur.jsl` | 98 |
| `src/main/jsl-decora/ZoomRadialBlur.jsl` | 81 |
| `src/main/jsl-prism/PaintTextureYUV422.jsl` | 45 |
| `src/main/jsl-prism/PaintTextureYUV444.jsl` | 43 |
| (A), 9 files | 667 |
| `src/main/java/com/sun/scenario/effect/ZoomRadialBlur.java` | 229 |
| `src/main/java/com/sun/scenario/effect/impl/state/ZoomRadialBlurState.java` | 85 |
| (B1), 2 files | 314 |

981 lines in all (667 + 314); `git diff --shortstat` prints `11 files changed, 981 deletions(-)`.
- Left in `src/main/jsl-decora`: 5 `.java` files (`GenAllDecoraShaders`, `CompileJSL`, `CompileBlend`, `CompilePhong`,
  `CompileLinearConvolve`) and 29 `.jsl` files. Left in `src/main/jsl-prism`: 1 `.java` file (`CompileJSL`), 28 `.jsl`
  files and `PrismLoaderGlue.stg`. No directory became empty: `com/sun/scenario/effect` keeps 38 `.java` files and
  `impl/state` 13.
- The `.jsl` files each kept generator can open, read from the code by the implementation and by the regeneration:
  - Decora, 29: the 6 names `GenAllDecoraShaders` passes to `CompileJSL` (`ColorAdjust`, `Brightpass`, `SepiaTone`,
    `PerspectiveTransform`, `DisplacementMap`, `InvertMask`), `Blend` and `Blend_<mode>` for the 19 `Blend.Mode`
    values (`CompileBlend`), `PhongLighting` (`CompilePhong`), `LinearConvolve` and `LinearConvolveShadow`
    (`CompileLinearConvolve`).
  - Prism, 28: `Mask` with the 11 `MaskType` and the 3 `AlphaMaskType` names (14), and `Paint` with `Color`,
    `ImagePattern`, `MultiGradient`, `LinearGradient`, `RadialGradient`, `AlphaTextureImagePattern`,
    `AlphaTextureLinearGradient`, `AlphaTextureRadialGradient`, `TextureRGB`, `MaskTextureRGB`, `MaskTextureSuper`,
    `TextureYV12`, `TextureFirstPassLCD` and `TextureSecondPassLCD` (14).
- These are exactly the files left, so a generator the build runs reads every remaining file, which is the story's
  goal. Three of the Prism files, `MaskSolid.jsl`, `MaskAlphaOne.jsl` and `PaintColor.jsl`, are read but their text
  goes into no program, only their time
  ([US-113](US-113-make-an-error-in-any-prism-generator-input-fail-the-build.md)).

### Drift since filing
What the filed text says against what is true at `7a66c85e39`:

| Filed text | Says | At `7a66c85e39` |
| --- | --- | --- |
| Proposed fix (A); the `backlog/README.md` row | 9 files, 663 lines | 667. `acf30cfe29` (#50, US-018, 2026-11-07) added `jslcinfo.metalHeaders.write();` to each of the 4 drivers and set their copyright year to 2026 (+2/-1 lines each). Upstream's 4 drivers are 81, 95, 79 and 47 lines, so the 9 files are 663 lines there. The 2 runtime files are 314 lines in both |
| Upstream too | All 9 files identical to upstream's (same blob ids) | True for the 5 `.jsl` files and the 2 runtime files. The 4 drivers differ by the copyright year and the line above ([Upstream check](#upstream-check-2026-11-15)) |
| When and why: content unchanged since 2013 | The 7 Decora files unchanged since `293263d095` | The 4 drivers were edited in `acf30cfe29`; the 3 Decora `.jsl` files are unchanged |
| Upstream too: `GenAllDecoraShaders` | Rewritten in `26ce75d02f` | Last changed in `acf30cfe29`, which made it look up `compile(String[], MetalHeaders)` instead of `main(String[])` ([Notes](#notes)) |
| Four Decora drivers: `CompileExternal` | Its `main` and the Decora `CompileJSL` `main` differ only in the usage text | Since `acf30cfe29` the Decora `CompileJSL` has a `main` (`:68-72`) that delegates to `compile(String[], MetalHeaders)` (`:74-85`, usage `"<jslfile>+"` at `:75`); `CompileExternal.main` is `:36-47`, usage `:37`. For `Brightpass` both still write the same 7 files, identical by md5 (review check b) |
| Measured | Windows 10, JDK 26, `--release 25` | The build compiles with `--release 27` (`jfx.jdk.target.version`) on JDK 27; the runs of 2026-11-15 are on macOS with JDK 27.0.1.0.2 |
| Measured; Acceptance criteria, Build gate | The second-pass `javac` writes 46 `javac -h` headers into `target/gensrc/headers` | `9b02d6a602` (#57, 2026-11-12) removed the `-h`: `target/gensrc` holds only `jsl-decora`, `jsl-prism` and, with `-mtl`, `mtl-headers`. The criterion is amended |
| What the runtime would look up: the byte search | The name is found only in the two classes, in the compiled classes of this checkout's `target` directories | Copies of the same two classes are also in `modules/javafx.controls/target/test-classes` and `modules/javafx.fxml/target/test-classes`, staged there from the graphics shims (review finding 6). A `find` over the checkout's `target` directories on 2026-11-15 also lists them in `modules/jfx.incubator.input` and `modules/jfx.incubator.richtext`. The criterion's search, limited to the four graphics output directories, is unaffected: 8 files before, 0 after |
| Measured: JVMs | The first Prism JVM writes every Prism file and header | In the 2026-11-15 regeneration it writes 849 files: the 848 `jsl-prism` files and `PrismShaderCommon.h`. `FragmentShaderCommon.h` keeps the Decora JVM's file time, because its bytes are the same and the generator rewrites a header only when its bytes change |
| Measured: JVMs; Problem, Two Prism shaders | The first Prism JVM 4.3 s, each later one 0.34 to 1.1 s (median 0.35 s), the two YUV runs 338 and 345 ms | 521 ms, 76 to 138 ms (median 85 ms) and 82 and 79 ms on this host ([Generator runs](#generator-runs)): another machine, not a contradiction |
| Status, as filed | Maven, Linux and macOS not run | Maven was run on macOS ([Final gates](#final-gates-2026-11-15-macos)); Linux and Windows are owner-run |

Line references, as `git show 7a66c85e39:<file>` shows them. Where the regeneration report and the review give
different ranges for the same reference (the second pass, the name appended, the Prism `main` and texture paints,
`JSLC.java`, `BoxRenderState.java`, `MetalTexture.m`, `CompileExternal.java`), the table gives what the file shows.

| Reference | Filed | At `7a66c85e39` |
| --- | --- | --- |
| `pom.xml`, the Decora generator `javac` | `:142-154`, source path `:145` | `:126-138`, `:129` |
| `pom.xml`, the `GenAllDecoraShaders` `<java>` | `:156-179`, `failonerror` `:156` | `:140-165`, `:140` |
| `pom.xml`, the Prism `<apply>` | `:200-216`, fileset `:215` | `:186-204`, `:203` |
| `pom.xml`, the generator steps of Measured | `:156-216` | `:126-204` |
| `pom.xml`, the second pass | `:220-246` | comment `:206`, `<javac>` into `target/mods` `:208-228`, `<copy>` into `target/classes` `:229-231` |
| `GenAllDecoraShaders.java`, the shader table | `:63-74` | `:67-78` |
| `GenAllDecoraShaders.java`, the name appended | `:86-88` | `:91-93` |
| `GenAllDecoraShaders.java`, the reflection | `:89-91` | `:94-96` |
| Decora `CompileJSL.java`, `main` and usage | `:67-77`, `:68` | `main` `:68-72`, `compile` `:74-85`, usage `:75` |
| `CompileExternal.java`, `main` | `:36-46` | `:36-47` (usage `:37`) |
| `JSLC.java`, `error` and `parseAllArgs` | `:300-311` | `:344-348`, `:350-355` |
| `JSLC.java`, the backends | `:193-244` | `compile` `:196-273`, the five output blocks `:209-270` |
| Prism `CompileJSL.java`, `main` | `:467-508` | `:468-512` |
| Prism `CompileJSL.java`, the texture paints | `:500-506` | `:504-509` |
| Prism `CompileJSL.java`, the name overwritten | `:421` | `:422`; the loaders still at `:425` |
| `BoxRenderState.java` | `:291-313` | `getPassPeer` `:312-333`, `"BoxBlur"` `:323` |
| `MetalTexture.m`, `uyvy422_to_rgba` | `:243-244` | `:244-245` |

Still true: `CompileBoxBlur.java:78`, `CompileZoomRadialBlur.java:76`, `BoxBlur.jsl:34` and `:38`,
`PrismBackend.java:95`, `JSWBoxBlurPeer.java:27-28`, `PaintTextureYUV422.jsl:32`, `module-info.java:156-161`,
`BaseShaderContext.java:135-141` and `:591`, `PixelFormat.java:46-48`, `ES2Texture.java:403-409`,
`Renderer.java:263-281`, `ZoomRadialBlur.java:146-149` and `CoreEffect.java:64-67` (regeneration report); the
`CoreEffect`, `Renderer`, `PSWRenderer` and `PPSRenderer` references, the 19 and 15 generator classes and jslc's 276
tests (review).

### Acceptance criteria and evidence
| Criterion | Evidence | State |
| --- | --- | --- |
| Files deleted | The working tree lacks the 11 files of [Change](#change), and no other file outside `backlog/` changes: `git status --short` lists exactly 11 ` D` lines. `git ls-files` listed all 11 before the deletion; `git ls-files --deleted` lists the 11 now. The index is unchanged, so `git ls-files` itself drops them with the owner's commit | Met in the working tree; `git ls-files` with the commit |
| No reference left | The 10 searches find nothing after the deletion; before it, the 4 drivers' `public class` lines and `ZoomRadialBlur` in exactly 4 files, so the patterns and paths are right ([Reference searches](#reference-searches)) | Met |
| No build step reads them | With `-d3d -es2 -mtl`, 1,059 files before and after, identical by name and md5, from 31 and 29 JVMs that all exit 0 ([md5 list](US-021-regeneration.md5)). A copy without `SepiaTone.jsl` and `PaintTextureYV12.jsl` exits 1 with `Input file not found` in the Decora JVM and in all 27 Prism JVMs; the review's five single-file cases fail too. JFR: the Decora JVM reads exactly the 29 remaining Decora `.jsl` files and the first Prism JVM the 28 Prism ones ([Generator runs](#generator-runs)) | Met |
| Generator classes | `target/jsl-compilers/decora` 11 classes before, 7 after, the 4 drivers' classes the difference; the 7 and the 8 of `prism` byte-identical to before, in the regeneration's compiles, the implementation's build, the module gate and the review's build | Met |
| Build gate | macOS, the WSL command with `-Dmaven.test.failure.ignore=true`, before and after: no per-class difference, `target/gensrc` (797 files) and `target/classes` as required ([Final gates](#final-gates-2026-11-15-macos)), extra evidence. jslc 276 tests, no failure. Windows (with `-am`) and WSL: [owner-run](#owner-run-and-pending) | Passed on macOS (extra evidence); Windows and WSL owner-run |
| With (B1), no compiled reference | `command grep -rlF ZoomRadialBlur` over `target/{classes,mods,shims,test-classes}`: 8 files before, none after, in the module gate and in the implementation's build ([Final gates](#final-gates-2026-11-15-macos)) | Met on macOS; Windows and WSL owner-run |
| Upstream | [Upstream check](#upstream-check-2026-11-15): the JBS search repeated; the recommendation is to propose the deletion as two issues, drafted outside the repository | Checked and drafted; filing pending |

The Definition of Done's other items: the scope and the reason are in [Choice](#choice-a-with-b1); the open stories
that describe the deleted files, US-022, US-023 and US-113, are updated; the independent review repeated the
regeneration and the missing-input check with its own clones and inputs
([Independent review](#independent-review-2026-11-15)); the status is ✅ Done only after the owner's commit, the PR and
the two gates ([After the gates](#after-the-gates)).

### Reference searches
- **The searches.** `git grep --untracked -n -F <pattern> -- ':!backlog'` in the main tree, before and after the
  deletion, for each of the 10 patterns: `CompileBoxBlur`, `CompileGaussian`, `CompileZoomRadialBlur`,
  `CompileExternal`, `BoxBlur.jsl`, `GaussianBlur.jsl`, `ZoomRadialBlur.jsl`, `PaintTextureYUV422`,
  `PaintTextureYUV444` and `ZoomRadialBlur`.
- **Before.** `CompileBoxBlur`, `CompileGaussian` and `CompileZoomRadialBlur` match only their own `public class`
  line (`:35` in each file), and `CompileExternal` only `CompileExternal.java:34`. `ZoomRadialBlur` matches exactly 4
  files: `ZoomRadialBlur.java`, `ZoomRadialBlurState.java`, `CompileZoomRadialBlur.java` and `ZoomRadialBlur.jsl`.
  The 5 shader-name patterns match nothing, as the criterion expects; `git ls-files` lists the 11 paths.
- **After.** Each of the 10 searches exits 1 with no file.
- **Wider searches, before.** `git grep -i -F zoomradial` finds the same 4 files. `git grep -i -E 'yuv ?4(22|44)'`
  finds only the unrelated Metal 4:2:2 texture upload (`MTLNative`, `MTLTexture`, `MTLTextureCalls`,
  `native-prism-mtl`, `MTLNativeTest`, a Metal golden), GStreamer and WebKit's DRM formats. `command grep -rlF` over
  the ignored paths outside `target/` (`.claude`, `modules/javafx.base/build`, `tests/system/build` and two
  `.DS_Store` files) finds none of the 10 patterns. `.idea` is tracked, so `git grep` covers it; there are no
  submodules.
- **Names built at run time,** read. `GenAllDecoraShaders` loads only the 4 drivers of its `SHADERS` table with
  `Class.forName`. Peers are looked up as `JSW<key>Peer` (`JSWRendererDelegate:44`), `PPS<key>Peer`
  (`PPSRenderer:316`) and `Pr<key>Peer`; `RendererFactory` loads renderers only. `EffectInputTest.java:91` builds
  `com.sun.scenario.effect.<name>` from a fixed list of 13 names without `ZoomRadialBlur`. `module-info.java` exports
  packages, both of which keep other classes, and no `addExports` argfile, `META-INF/services` entry or shim names the
  two classes. No test or build step counts the files of the two input directories: the pom takes the directory and a
  `**/*.jsl` fileset, and `mac.cmake:233` and `win.cmake:311` glob the generated shaders.
- **The review's repeat.** In the main tree none of the 10 patterns is found outside `backlog/`. On its pristine clone
  of the baseline the same searches find the 4 `public class` files and `ZoomRadialBlur` in exactly 4 files.
  `.classpath`, the cmake files, the jslc tests, `apps/`, `tests/` and `.github` have no reference.

### Generator runs
Run on 2026-11-15, outside Maven, on the two clones of the implementation's build
([Final gates](#final-gates-2026-11-15-macos)): before at `7a66c85e39`, after with the 11 files deleted. Each side used
its own clone's classes, inputs and output root. Timings are this host's wall times (zsh `$EPOCHREALTIME` around
each `java`), taken while the module gate was running on the host.
- **Setup,** as the pom runs the generators. The classpath is `maven.compile.classpath`, read from the poms: graphics
  `target/classes`, base `target/classes`, jslc `target/classes`, then antlr4 4.7.2, antlr4-runtime 4.7.2,
  antlr-runtime 3.5.2, ST4 4.1, org.abego.treelayout.core 1.0.3, javax.json 1.0.4 and icu4j 61.1 from `~/.m2` (the
  optional `stringtemplate` and `javax.json-api` and the test-scope javafx-base test jar left out).
  `jfx.graphics.modulepath` is graphics `target/classes` and base `target/classes`. With `C` the side's clone and `W`
  its work root:
  ```
  CP=$C/modules/javafx.graphics/target/classes:$C/modules/javafx.base/target/classes:$C/buildtools/jslc/target/classes:<the 7 jars>
  M=$C/modules/javafx.graphics   MP=$M/target/classes:$C/modules/javafx.base/target/classes

  javac -d $W/compilers/decora -classpath $CP -sourcepath $M/src/main/jsl-decora -encoding UTF-8 -g --release 27 -implicit:none --module-path $MP --add-modules=javafx.graphics --add-exports=javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.scenario.effect.light=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.scenario.effect.impl.state=ALL-UNNAMED $M/src/main/jsl-decora/*.java
  javac -d $W/compilers/prism -classpath $CP -sourcepath $M/src/main/jsl-prism -encoding UTF-8 -g --release 27 -implicit:none --module-path $MP --add-modules=javafx.graphics $M/src/main/jsl-prism/*.java
  ```
  The Ant `javac` maps `srcdir` to `-sourcepath`, `debug="true"` to `-g` and `release` to `--release`.
- **The two command lines,** each in an empty working directory of its own (`$W/wd/00`, `$W/wd/NN`), which stayed
  empty. The Decora generator, one JVM:
  ```
  java --module-path=$MP --add-modules=javafx.graphics --add-exports=javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.scenario.effect.light=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.scenario.effect.impl.state=ALL-UNNAMED -classpath $CP:$M/src/jslc/resources:$W/compilers/decora GenAllDecoraShaders -i $M/src/main/jsl-decora -o $W/out/jsl-decora -t -mtlheaders $W/out/mtl-headers -pkg com/sun/scenario/effect -d3d -es2 -mtl -all GenAllDecoraShaders
  ```
  The Prism generator, one JVM per `.jsl` file, here the first:
  ```
  java -cp $CP:$M/src/jslc/resources:$W/compilers/prism:$M/src/main/jsl-prism CompileJSL -i $M/src/main/jsl-prism -o $W/out/jsl-prism -t -mtlheaders $W/out/mtl-headers -pkg com/sun/prism -d3d -es2 -mtl -name $M/src/main/jsl-prism/MaskAlphaOne.jsl
  ```
- **Order.** The Prism files run in the order of Ant 1.10.12's `DirectoryScanner` for `**/*.jsl`, the Ant of
  maven-antrun-plugin 3.1.0, taken with a probe: `LC_ALL=C sort` order on both sides, 30 files before and 28 after,
  `MaskAlphaOne.jsl` first.
- **Regeneration with `-d3d -es2 -mtl`.** Before: 31 JVMs (1 Decora, 30 Prism); after: 29 (1 and 28). Every JVM exits
  0 and prints nothing. Each side writes 1,059 files (`jsl-decora` 208, `jsl-prism` 848, `mtl-headers` 3), and the two
  md5 lists (`find . -type f | LC_ALL=C sort | xargs md5sum`) are `cmp`-identical
  ([md5 list](US-021-regeneration.md5)). Three setup checks, on both sides:
  - (a) the list without its `.hlsl` files has 797 lines and is `cmp`-identical to
    [`US-020-macos-gensrc.md5`](US-020-macos-gensrc.md5) and to the same list made from the clone's own `target/gensrc`;
  - (b) there are 262 `.hlsl` files, 50 Decora and 212 Prism;
  - (c) `DecoraShaderCommon.h` holds 50 `_Uniforms` structs and 50 `_Uniform_VarID_Dict` functions and
    `PrismShaderCommon.h` 212 of each, matching the names of the generated `.metal` files exactly.
- **Generator classes.** 11 Decora and 8 Prism classes before, 19; 7 and 8 after, 15. Each is `cmp`-identical to
  Maven's in its clone's `target/jsl-compilers` (11 of 11 and 8 of 8 before, 7 of 7 and 8 of 8 after), and the 15
  after-side classes to their before-side counterparts. Only on the before side: `CompileBoxBlur.class`,
  `CompileExternal.class`, `CompileGaussian.class` and `CompileZoomRadialBlur.class`.
- **Which JVM wrote what,** from a snapshot of every output's nanosecond file time, size and name after each JVM, on
  the before side. The Decora JVM writes 210 files: the 208 and `DecoraShaderCommon.h` and `FragmentShaderCommon.h`.
  The first Prism JVM writes 849: the 848 `jsl-prism` files and `PrismShaderCommon.h`. JVMs 02 to 30 write, rewrite
  or add nothing; the after side behaves the same way. The snapshots after JVMs 27, 28 and 29 are byte-identical, so
  the JVMs of `PaintTextureYUV422.jsl` (28) and `PaintTextureYUV444.jsl` (29) change nothing.
- **Times.** Before: Decora 334 ms, the first Prism JVM 521 ms, the other 29 Prism JVMs 76 / 85 / 138 ms (minimum /
  median / maximum), 3,388 ms in all. After: 358 ms, 541 ms, and 76 / 87 / 99 ms for the other 27, 3,257 ms in all.
  The two YUV JVMs took 82 and 79 ms.
- **JFR file reads.** One extra Decora JVM and one extra first Prism JVM per side, into fresh output roots, recorded
  `jdk.FileRead` with `enabled=true`, `threshold=0ms` and `throttle=off`. A first try with the default throttle of
  100 events per second lost events and is discarded. On both sides the Decora JVM read exactly the 29 Decora `.jsl`
  files of the after side and the Prism JVM exactly the 28 Prism files; nothing named `BoxBlur`, `GaussianBlur`,
  `ZoomRadialBlur` or `YUV` was read, and the output of the recorded runs equals the regeneration's md5 list on each
  side. Two more before-side JVMs, started with `-name …/PaintTextureYUV422.jsl` and
  `-name …/PaintTextureYUV444.jsl`, each read the same 28 files and no YUV file.
- **Missing input.** Scratch copies of the after side's inputs with files removed, run into fresh output roots:

  | Removed | Run by | Decora JVM | Prism JVMs | Written before the failure |
  | --- | --- | --- | --- | --- |
  | `SepiaTone.jsl` and `PaintTextureYV12.jsl` | Regeneration | Exit 1, `Input file not found: SepiaTone.jsl`, then the usage text | All 27 exit 1, `Input file not found: PaintTextureYV12.jsl`; the 27 logs are byte-identical | Decora: 10 files, the `.hlsl`, `.frag`, `.metal` and both peers of `ColorAdjust` and `Brightpass`, no header. Prism JVM 01: 412 files, the `.hlsl`, `.frag`, `.metal` and `_Loader.java` of 103 shaders, every one without `_AlphaTest` up to and including `Mask_TextureSuper` and no `Solid_TextureYV12`, no header; JVMs 02 to 27 nothing |
  | `SepiaTone.jsl` | Review | Exit 1, `Input file not found: SepiaTone.jsl` | The 28 exit 0 | Not measured |
  | `Blend_SOFT_LIGHT.jsl` | Review | Exit 1, `Input file not found: Blend_SOFT_LIGHT.jsl` | The 28 exit 0 | Not measured |
  | `PaintTextureYV12.jsl` | Review | Exit 0 | All 27 exit 1, `Input file not found` | Not measured |
  | `MaskDrawSemiRoundRect.jsl` | Review | Exit 0 | All 27 exit 1, `Input file not found` | Not measured |
  | `PaintMultiGradient.jsl`, read through `readShaderFile` | Review | Exit 0 | All 27 exit 1, `Input file not found` | Not measured |

  A build without `clean` catches a missing file too: in the review, a run on an up-to-date output root still exits 1
  without `PaintTextureYV12.jsl`, and the same run with complete inputs exits 0 and touches no file.
- **The `-name` file is never opened.** The review ran the Prism generator with
  `-name /nonexistent/PaintTextureYUV422.jsl`: exit 0, nothing written. That is why the two YUV files were
  invisible to the build.

### Final gates (2026-11-15, macOS)
Both runs were on the host above, in fresh clones of the repository detached at `7a66c85e39`: `jfx-before`, and
`jfx-after` with the 11 files deleted by one `rm` of literal paths, whose `git status --short` is byte-identical to
the main tree's. They ran one after the other, starting at 00:49:11 and 00:50:55 UTC on 2026-11-16, the evening of
2026-11-15 on the host (America/New_York). They are extra evidence: the criterion's gates are Windows and WSL. Counts
are run / failures / errors / skipped.

| Gate | Command | Result |
| --- | --- | --- |
| Module gate | `mvn -B -ntp -l <log> -Dmaven.test.failure.ignore=true -pl buildtools/jslc,modules/javafx.graphics -am clean test`, before and after | Both exit 0 with BUILD SUCCESS (Maven total time 01:24 min and 01:15 min), and `jsl-codegen`, `cmake-configure` and `cmake-build` ran once in each. jslc 276 / 0 / 0 / 0 in 31 classes, base 5,510 / 0 / 0 / 22 in 290 and graphics 26,696 / 0 / 0 / 858 in 629, in both. 950 per-class lines on each side, and `diff` shows no difference ([per class](US-021-macos-per-class.tsv)). No class has a failure or an error. The after list is `cmp`-identical to the after column of [`US-020-macos-per-class.tsv`](US-020-macos-per-class.tsv). No `hs_err_pid*` file in either clone |
| Generated files | The module gate's `target/gensrc` | 797 files (`jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3) and no `headers` directory, identical by name and md5 before and after ([md5 list](US-021-macos-gensrc.md5), the after run's) and to `US-020-macos-gensrc.md5` |
| Generator classes | `md5sum` of `target/jsl-compilers/{decora,prism}` | `decora` 11 classes before and 7 after; the 4 missing are `CompileBoxBlur`, `CompileExternal`, `CompileGaussian` and `CompileZoomRadialBlur`, and nothing is only in the after run. The 7 common ones (`CompileBlend`, `CompileJSL`, `CompileLinearConvolve`, `CompilePhong$1`, `CompilePhong`, `GenAllDecoraShaders$Shader`, `GenAllDecoraShaders`) are `cmp`-identical. `prism` 8 and 8, identical |
| `target/classes` | `find . -type f` and `md5sum` | 3,904 files before and 3,902 after; `diff` of the file lists shows exactly `./com/sun/scenario/effect/ZoomRadialBlur.class` and `./com/sun/scenario/effect/impl/state/ZoomRadialBlurState.class` removed ([file list](US-021-macos-classes.txt), the after run's), and `diff` of the md5 lists only the same two lines, so none of the 3,902 common files differs |
| Byte search | `command grep -rlF ZoomRadialBlur` in `target/{classes,mods,shims,test-classes}` | 8 files before, `ZoomRadialBlur.class` and `ZoomRadialBlurState.class` in each of `classes`, `mods/javafx.graphics`, `shims/javafx.graphics` and `test-classes`; none after |
| Shader resources | `target/classes` and `target/gensrc` | 277 `*.frag` files and 1 `*.metallib` in `target/classes` in both; no file named `BoxBlur_*`, `GaussianBlur_*`, `ZoomRadialBlur_*`, `*TextureYUV422*` or `*TextureYUV444*` in either clone |

An earlier, separate check, by the implementation in two other clones (before at `7a66c85e39`, after with the 11
files deleted): `mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am test-compile -DskipNative=true`.
- Both BUILD SUCCESS, Maven total time 34.927 s and 27.163 s, with 12 `[WARNING]` lines (`javac` notes) and no
  `[ERROR]` in each.
- The Decora generator `javac` compiles 9 sources before and 5 after, the Prism one 1 in both, the second pass 1,890
  and 1,888, `compile-shims` 2,023 and 2,021, the first pass 1,620 and 1,618. A normalised `diff` of the two logs
  shows only these counts and the copy counts 3,540 to 3,538 and 4,190 to 4,188.
- `target/jsl-compilers` 11 and 7 Decora classes and 8 and 8 Prism, the common ones identical; `target/gensrc` 797
  files (158, 636, 3), identical by name and md5, and no `headers` directory; `target/classes` 3,903 and 3,901 files,
  the two `ZoomRadialBlur` classes the only difference and every other file md5-identical; the byte search 8 files and
  none.

The check commands of [Windows gate](#windows-gate) ran as written in bash on this host, with `windows` replaced by
`macos`, on the module gate's clones and logs, with their output files written to a scratch directory. They print
`per-class lists identical`, the totals, the `gensrc` result and directory counts, the two-line `target/classes`
difference, `0` files of the 3,902 common ones differing by md5, the four-class `jsl-compilers/decora` difference
and the 8 and 0 files of the byte search above, and `find` prints nothing. Their per-class lists, `gensrc` md5 lists,
`target/classes` file and md5 lists and `jsl-compilers` md5 lists are `cmp`-identical to the gate's. The
shader-object lines print `shader-obj identical by name and md5` and `0`: macOS has no `.obj` file (its shader
resource is `jfxshaders.metallib`). `merge` and the `cp` lines of [After the gates](#after-the-gates), with
`o=macos`, give the body of `US-021-macos-per-class.tsv`, `US-021-macos-gensrc.md5` and `US-021-macos-classes.txt`,
byte for byte. Both clones hold 27 empty `hs_err`
directories, which child-JVM tests create. The counts of `target/gensrc` differ from the US-008 gate's for the reasons
US-019's [Final gates](US-019-reject-trailing-input-in-the-jsl-compiler.md#final-gates-2026-11-14-macos) gives.

The change is not rendering-affecting: no generated file and no remaining class changes by a byte. No
`FULL_TEST`/`USE_ROBOT` run is called for, and the PR's `submit.yml` run is to cover Linux and Windows (review
finding 7).

### Independent review (2026-11-15)
- **Verdict:** "Pass with must-fix items. The 11 deletions are complete, minimal and behaviour-neutral." On the scope:
  "(A)+(B1): sane. `ZoomRadialBlur` is dead and unreachable: any filter through it throws, and I confirmed that with
  the real `PSWRenderer`."
- **Findings:**

  | # | Finding | Disposition |
  | --- | --- | --- |
  | 1, must-fix | The story file was unchanged: the Definition of Done's scope, open-story and status items were not done, and several of its statements were no longer true | Fixed by this write-up; the statements are in [Drift since filing](#drift-since-filing) |
  | 2, must-fix | The Build gate criterion and Measured expect `target/gensrc/headers` with 46 files, which has not existed since `9b02d6a602` (#57); a gate that compares it fails falsely or compares two missing directories | Fixed by this write-up: the criterion is amended, and the owner-run checks do not compare `headers` |
  | 3, should-fix | Notes gave the wrong reason why no driver can be added back: since `acf30cfe29` (#50), `GenAllDecoraShaders` looks up `compile(String[], MetalHeaders)` (`GenAllDecoraShaders.java:95`), which `javap` shows none of the 4 deleted drivers has | Fixed: [Notes](#notes) amended |
  | 4, should-fix | The Definition of Done item on US-019 and US-020 is overtaken: both are 🔷 Implemented, and their pending gates pin their own commits | Fixed: the item is amended, US-019 and US-020 stay as written, and US-022, US-023 and US-113 are updated |
  | 5 | Measured: a build without `clean` keeps the deleted classes. In a third clone built at the baseline, the 11 files deleted and the same `test-compile` run again, the two `ZoomRadialBlur` classes stayed in `target/{classes,mods,shims,test-classes}` and `target/jsl-compilers/decora` still held 11 classes | Filed as [US-121](US-121-keep-the-classes-of-a-deleted-source-out-of-a-build-without-clean.md). The owner-run gates start from `clean` |
  | 6 | The story's byte search says the name is found only in the two classes; copies also sit in the `target/test-classes` of `javafx.controls` and `javafx.fxml`, staged from the graphics shims | Recorded in [Drift since filing](#drift-since-filing); the criterion's search is unaffected |
  | 7 | The diff touches no pom or cmake file, but changes the shipped `javafx.graphics` jar on every platform; it is not rendering-affecting | Stated in [Final gates](#final-gates-2026-11-15-macos): no `FULL_TEST`/`USE_ROBOT` run is called for, and the PR's `submit.yml` run is to cover Linux and Windows |
  | 8 | The skill texts under `.claude/` are git-ignored and outside the commit; their `javac -h` and `target/gensrc/headers` sentences have been stale since #57, not because of this change | Not changed here; reported to the owner |

- **What it repeated,** in its own clones (`git clone --shared` of the repository at `7a66c85e39`, the 11 files
  deleted in the after clone by literal path, its `git status --short` equal to the main tree's), each built with
  `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am test-compile -DskipNative=true` (exit 0, about 31 s),
  each side running its own clone's classes into empty output roots. Its commands are those of
  [Generator runs](#generator-runs), run from the module directory with the clone's own `target/jsl-compilers` as
  the generator classes and, one deviation it states, the module path after the second pass rather than pass one,
  which the generators use only for effect classes such as `Blend.Mode`. Its results: 31 and 29 JVMs, all exit 0,
  empty logs; 1,059 files on each side, identical by name and md5, none named `BoxBlur`, `GaussianBlur`,
  `ZoomRadialBlur` or `YUV`; the Maven builds' own `target/gensrc` (`-mtl -es2`, 797 files) identical; the
  missing-input cases of the table above;
  `target/jsl-compilers/decora` 11 to 7, `prism` 8 to 8, identical. Compared afterwards, its regeneration md5 lists,
  its `jsl-compilers` lists and its `gensrc` md5 set equal the regeneration's, the implementation's and the gate's.
- **Its probe,** `ZrbProbe`, against the before clone's classes, with the real `PSWRenderer.createJSWInstance((Screen)
  null)` registered for a `FilterContext` through `Renderer.rendererMap`, so that the production `CoreEffect` path
  picks it up. Its output (the renderer's hash elided):
  ```
  renderer: com.sun.scenario.effect.impl.prism.sw.PSWRenderer accel=CPU/Java
  control BoxBlur peer: com.sun.scenario.effect.impl.sw.java.JSWBoxBlurPeer
  Error: CPU/Java peer not found for: ZoomRadialBlur due to error: com.sun.scenario.effect.impl.sw.java.JSWZoomRadialBlurPeer
  getPeerInstance(ZoomRadialBlur, 8): java.lang.RuntimeException: Could not create peer  ZoomRadialBlur for renderer com.sun.scenario.effect.impl.prism.sw.PSWRenderer@…
  constructed: radius=5
  Error: CPU/Java peer not found for: ZoomRadialBlur due to error: com.sun.scenario.effect.impl.sw.java.JSWZoomRadialBlurPeer
  getAccelType: java.lang.RuntimeException: Could not create peer  ZoomRadialBlur for renderer com.sun.scenario.effect.impl.prism.sw.PSWRenderer@…
  Error: CPU/Java peer not found for: ZoomRadialBlur due to error: com.sun.scenario.effect.impl.sw.java.JSWZoomRadialBlurPeer
  filterImageDatas: java.lang.RuntimeException: Could not create peer  ZoomRadialBlur for renderer com.sun.scenario.effect.impl.prism.sw.PSWRenderer@…
  filter: java.lang.IllegalArgumentException: Identity source must be PrImage
  ```
  The failure is not cached, so every call looks the peer up again and prints again. The last line comes from the
  probe's own input, an `Identity` over an image that is not a `PrImage`, which `PSWRenderer.java:295` rejects; it
  says nothing about the peer, and the review's report leaves it out. `PPSRenderer` fails the same way on a GPU: read,
  not run. The built class lists hold no `JSW`, `PPS` or `Pr` `ZoomRadialBlurPeer`.
- **Check a, completeness:** the reference searches of [Reference searches](#reference-searches), and the names built
  at run time: the `SHADERS` table, the three peer lookups, `RendererFactory`, and the stock Prism shader names of
  `BaseShaderContext` and the mask enums, without YUV.
- **Check b, the drivers:** what is lost and what replaces it, in [Choice](#choice-a-with-b1).
- **Check c, the shaders:** file reads do not depend on the backend set, because `getJSLFile` runs before any backend
  dispatch and the Decora set is fixed. The runtime asks for no YUV shader; 4:2:2 media go through
  `GL_APPLE_ycbcr_422` or the `uyvy422_to_rgba` kernel; `BoxRenderState` asks for `BoxBlur` only when the renderer's
  acceleration is `NONE`, which the hand-written `JSWBoxBlurPeer` serves.
- **Check d, the runtime `ZoomRadialBlur`:** the probe above. The package is exported only to `javafx.web`, whose
  `WCGraphicsPrismContext` and `WCBufferedContext` use `com.sun.scenario.effect` but never `ZoomRadialBlur`. There is
  no public `javafx.scene.effect` counterpart, no CSS or FXML path, no `Serializable` and no reflective lookup of
  effect classes, and no test, Swing or `apps/` code uses it. An application could reach it only with
  `--add-exports`, and then only its non-rendering methods work.
- **Check e, Windows and Linux:** `win.cmake:311-326` globs the generated `.hlsl` files for `fxc`, `mac.cmake:233-254`
  the `.metal` files, Linux reads no generated source, and the `.frag` files are copied by glob. No list names a
  shader, so nothing changes while the generated files are identical. Its checks for the owner-run gates (`clean`,
  the Windows `.obj` names and md5, `gensrc` 108 and 424, `headers` not compared) are in
  [Owner-run and pending](#owner-run-and-pending). It also asked the gates to look at the Linux `-es2` `gensrc` and
  the `.frag` resources in `target/classes`, which the WSL gate's `gensrc` md5 list and `target/classes` file list
  cover (with the md5 comparison of `target/classes` as information), and at `javafx.graphics.jar` lacking exactly
  the two classes, which is not covered: `clean test` builds no jar.
- **Check g, conventions:** finding 7; a deletion needs no copyright edit.
- **What it reports about itself.** The per-backend-set regenerations (`-d3d`, `-d3d -es2`, `-es2`, `-mtl`) were
  blocked by the shell's safety check and dropped. Their coverage is the code reading of check c, the combined
  `-d3d -es2 -mtl` run and the macOS `-mtl -es2` build; the owner-run gates build `-d3d` and `-es2`. Its first
  regeneration recorded the Prism exit codes wrongly (zsh's `$?` was reset by a `$(basename …)` in the same
  `echo`); it redid the whole regeneration with the exit codes captured correctly, and the outputs were identical to
  the first run's.
- **Not checked by it:** Windows (`-d3d`, `fxc`), Linux (`-es2`), the CMake shader compiles and the jar or SDK
  assembly; the module test suite and the robot tests; JBS and upstream (no network); `PPSRenderer` on a GPU.

### Upstream check (2026-11-15)
- **JBS,** project JDK, through the anonymous REST API. The seven searches of [Upstream too](#upstream-too) give the
  same counts as on 2026-09-27: `CompileBoxBlur`, `CompileGaussian`, `CompileExternal` and `GenAllDecoraShaders` 0;
  `ZoomRadialBlur` 1 (JDK-8105703); `PaintTextureYUV422` 1 (JDK-8091282); `YUV444` in component `javafx` 0. Four more
  searches give 0 each: `PaintTextureYUV444`, `YUV422` in `javafx`, `PaintTexture` in `javafx`, and `ZoomRadial` (JBS
  matches whole tokens). `YUV` in `javafx` gives 4 issues: JDK-8091933 (Open), JDK-8178356 (Closed as its duplicate),
  JDK-8301671 ("Implement support for BYTE_APPLE_422 format image", Fixed 2023-05-16, the ES2 and Metal conversion
  path with no Prism YUV shader) and JDK-8342690 (D3D12 texture implementation, Delivered).
- **The five issues the story names:** JDK-8105703 Resolved, Fixed (fx1.1, 2009-01-10); JDK-8099811 Resolved, Fixed
  (fx1.2, 2009-05-01); JDK-8099221 Resolved, Fixed (fx1.3, 2009-09-04); JDK-8091282 Open (created 2012-12-28, last
  updated 2018); JDK-8091933 Open (created 2012-09-04, last updated 2018). No issue proposes deleting the files or
  reviving the effects or the YUV shaders. Related: JDK-8349668 (Open, 2025-02-07, "Prism: Improve texture formats
  used when creating MediaFrame-based Texture") would rewrite "Media-related JSL shaders" for NV12-style textures on
  D3D12; it is about the 4:2:0 YV12 path and names neither YUV file. JDK-8357625 ("Build should generate platform
  specific shaders", Fixed in jfx27, 2026-02-20) is why upstream passes the backends per platform. JDK-8226635 (jslc
  tests not run) is still Open.
- **openjdk/jfx master** at `d0e72f9d86` (2026-11-13), the head of the US-020 check:
  - all 11 files exist. The 5 `.jsl` files and the 2 runtime files have the fork's blob ids; the 4 drivers differ only
    by the copyright year (2013 there, 2026 here) and the fork's `jslcinfo.metalHeaders.write();` line from
    `acf30cfe29`;
  - `GenAllDecoraShaders.java:34-45` lists the same 10 shaders, names none of the 4 drivers, reflects at `:51-53`, and
    was last changed by `682ee49241` (JDK-8357625);
  - `build.gradle`: `compileCompilers` at `:1716-1735` compiles the whole directory (`source` at `:1724`),
    `generateShaders` at `:1737-1752` (`inputs.dir` at `:1747`); Decora at `:2526-2553`, whose only generator is
    `GenAllDecoraShaders` (`:2527`); Prism at `:2628-2653`, a `fileTree` of `**/*.jsl` (`:2629-2631`) with
    `mainClass = "CompileJSL"` (`:2635`) and `-name` (`:2650`). The file names none of the 4 drivers, `ZoomRadial` or
    `YUV`;
  - its `jsl-decora` holds 32 `.jsl` and 9 `.java` files and its `jsl-prism` 30 `.jsl`, 1 `.java` and 1 `.stg`, so its
    Prism step runs 30 JVMs, 2 of them for the YUV files. Its Prism `CompileJSL.java` mentions only `YV12` (a comment
    at `:83`, `compileSolidTexture(jslcinfo, "YV12", alphaTest)` at `:504`).
- **Code search** (`gh search code`, an index search): `ZoomRadialBlur` finds exactly the 4 paths of
  [Reference searches](#reference-searches); `CompileBoxBlur`, `CompileGaussian` and `CompileExternal` each only their
  own file; `PaintTextureYUV422` and `PaintTextureYUV444` nothing; `YUV422` Metal and ES2 media code and WebCore only.
  `module-info.java:156-161` still exports the three `com.sun.scenario.effect` packages only to `javafx.web`. The
  history commits the story cites exist upstream, and two of its facts were checked there: `CompileExternal` at
  `decora-runtime/build.xml:46` and the commented-out `CompileZoomRadialBlur` at `:120` at `b4d6d9772f`, and the same
  10 Decora shaders at `build.gradle:143-152` at `420f2d72f2`.
- **Open pull requests,** 52, each one's file list read: none touches `jsl-decora`, any of the 11 files,
  `GenAllDecoraShaders` or a Decora driver, and none adds or uses a YUV 4:2:2 or 4:4:4 shader or `ZoomRadialBlur`.
  Three touch `jsl-prism`: #2346 (8394770, Direct3D 12 pipeline) adds `-d3d12` to both generators' arguments in
  `build.gradle` and one line to the Prism `CompileJSL.java`, so on Windows each Prism JVM would also write D3D12
  output; #2284 (8090503) adds one line to the Prism `CompileJSL.java` and a new `.jsl` file; #2329 (8087965) edits
  three round-rectangle `.jsl` files. None edits a line this change deletes, and the deletion needs no `build.gradle`
  edit.
- **Recommendation:** propose the deletion upstream as two JBS issues, the 9 generator inputs first and the runtime
  `ZoomRadialBlur` classes second, the second referring to the first; filing is the owner's. The first is build-only,
  with no API and no module class changed; the second removes two classes from the product jar, internal ones that
  throw on every use, and splitting lets a reviewer decline it without blocking the first. Against it: low value and
  reviewer time; upstream reviewers will want Gradle output compared before and after, and the fork's evidence is
  Maven and generator runs; `CompileExternal` may still be used by hand; JDK-8091282 and JDK-8091933 would be left
  with nothing behind them, and a reviewer may want the YUV stubs kept for JDK-8091933; #2346 is a large open pull
  request on the same generator arguments; that no CSR is needed for internal classes is a reading, not confirmed.
- **Draft.** Both issues are drafted in one file, `US-021-upstream-draft.md`, kept in `_claude/` next to the
  repository until they are filed. Upstream references are to `d0e72f9d86` with today's `build.gradle` line numbers;
  the fork's measurements are marked as the fork's, Maven-driven and not Gradle.
- **Not verified:** no Gradle build was run, and the two Gradle task names in the draft,
  `:graphics:generateDecoraShaders` and `:graphics:generatePrismShaders`, are inferred from `addJSL`
  (`build.gradle:1737`) and marked in the draft for the owner to run first; the code search is an index search, and
  upstream was not cloned for a `git grep`; the patch scan of large pull requests saw only files for which GitHub
  returns a patch (the file-name scan is complete); closed and merged pull requests since 2026-09-27 were not scanned,
  only the commit history of the four paths.

### Owner-run and pending
Replace `<repo-url>` with the repository's remote and `<commit>` with the owner's US-021 commit. Before is
`7a66c85e39`. If `<commit>` is only on the PR branch, fetch it first with
`git -C jfx-after fetch -q origin pull/<PR>/head`. In PowerShell, quote every `-D` argument; under Git Bash, Maven
3.10's `bin/mvn` converts the paths. Each gate keeps its logs, its per-class lists (class, run, failures, errors,
skipped, separated by tabs), its `target/gensrc` md5 lists, its `target/classes` file lists and its
`target/jsl-compilers` md5 lists as `us021-<os>-<before|after>…` in the directory that holds the clones. The check
commands ran as written in bash on the macOS host, with `windows` replaced by `macos`, on the macOS gate's clones and
logs ([Final gates](#final-gates-2026-11-15-macos)).

Both gates build from `clean` in fresh clones. A build without `clean` keeps the classes of the deleted files in
`target/{classes,mods,shims,test-classes}` and the 4 drivers' classes in `target/jsl-compilers/decora` (review
finding 5, [US-121](US-121-keep-the-classes-of-a-deleted-source-out-of-a-build-without-clean.md)), so the byte search
and the two class `diff`s below would fail. The main checkout's own `modules/javafx.graphics/target` still holds those
classes from earlier builds, so a build there needs `clean` too.

#### Windows gate
Two sibling checkouts, in Git Bash:
```bash
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach 7a66c85e39
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <commit>
```
Maven from PowerShell, in the directory that holds both, so that MSVC builds the natives:
```powershell
$pl = "buildtools/jslc,modules/javafx.graphics"
cd jfx-before
mvn -B -ntp -l ..\us021-windows-before.log -pl $pl -am clean test "-Djfx.parity.require=true"
"before exit $LASTEXITCODE"; cd ..
cd jfx-after
mvn -B -ntp -l ..\us021-windows-after.log -pl $pl -am clean test "-Djfx.parity.require=true"
"after exit $LASTEXITCODE"; cd ..
```
The checks, in Git Bash in the same directory. The byte search uses `command grep`, so that a shell function wrapping
`grep` that skips binary files cannot answer nothing:
```bash
classes() {
  tr -d '\r' < "$1" | awk '/Tests run: .* -- in / { c = $NF; sub(/.*Tests run: /, ""); split($0, n, /[^0-9]+/)
    print c "\t" n[1] "\t" n[2] "\t" n[3] "\t" n[4] }' | LC_ALL=C sort
}
for t in before after; do
  classes us021-windows-$t.log > us021-windows-$t-per-class.tsv
  g=jfx-$t/modules/javafx.graphics/target
  (cd $g/gensrc && find . -type f | LC_ALL=C sort | xargs md5sum) > us021-windows-$t-gensrc.md5
  (cd $g/classes && find . -type f | LC_ALL=C sort) > us021-windows-$t-classes.txt
  (cd $g/classes && find . -type f | LC_ALL=C sort | xargs md5sum) > us021-windows-$t-classes.md5
  for c in decora prism; do
    (cd $g/jsl-compilers/$c && find . -type f | LC_ALL=C sort | xargs md5sum) > us021-windows-$t-jslcompilers-$c.md5
  done
done
diff us021-windows-before-per-class.tsv us021-windows-after-per-class.tsv && echo "per-class lists identical"
for t in before after; do
  echo "$t:"
  tr -d '\r' < us021-windows-$t.log \
    | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|BUILD (SUCCESS|FAILURE)'
done
cmp us021-windows-before-gensrc.md5 us021-windows-after-gensrc.md5 && echo "gensrc identical by name and md5"
cut -d/ -f2 us021-windows-after-gensrc.md5 | sort | uniq -c
diff us021-windows-before-classes.txt us021-windows-after-classes.txt
awk 'FNR == NR { b[$2] = $1; next } ($2 in b) { n++; if (b[$2] != $1) d++ }
  END { print "target/classes, information:", n + 0, "files in both,", d + 0, "differ by md5" }' \
  us021-windows-before-classes.md5 us021-windows-after-classes.md5
for c in decora prism; do
  echo "jsl-compilers/$c:"
  diff us021-windows-before-jslcompilers-$c.md5 us021-windows-after-jslcompilers-$c.md5
done
for t in before after; do
  echo "$t, ZoomRadialBlur:"
  (cd jfx-$t/modules/javafx.graphics/target && command grep -rlF ZoomRadialBlur classes mods shims test-classes)
done
for t in before after; do
  find jfx-$t/modules/javafx.base jfx-$t/modules/javafx.graphics jfx-$t/buildtools/jslc -name 'hs_err_pid*' -type f
done
```
And, on Windows only, the compiled shaders:
```bash
for t in before after; do
  (cd jfx-$t/modules/javafx.graphics/target/native/shader-obj && find . -name '*.obj' -type f | LC_ALL=C sort \
    | xargs -r md5sum) > us021-windows-$t-shader-obj.md5
done
cmp us021-windows-before-shader-obj.md5 us021-windows-after-shader-obj.md5 && echo "shader-obj identical by name and md5"
wc -l < us021-windows-after-shader-obj.md5
```
It must show:
- `before exit 0` and `after exit 0`, and `BUILD SUCCESS` in both logs;
- `per-class lists identical`, and no other output from that `diff`;
- jslc `Tests run: 276, Failures: 0, Errors: 0, Skipped: 3` in both runs (the three `link/..` tests of
  `MetalHeadersTest` are disabled on Windows); the same javafx.base line in both runs and the same javafx.graphics
  line in both runs, each with `Failures: 0, Errors: 0`;
- `gensrc identical by name and md5`, with `108 jsl-decora` and `424 jsl-prism` and no other directory, for the
  reasons US-019's [Windows gate](US-019-reject-trailing-input-in-the-jsl-compiler.md#windows-gate) gives;
- from the `target/classes` `diff`, besides the hunk lines, only `< ./com/sun/scenario/effect/ZoomRadialBlur.class`
  and `< ./com/sun/scenario/effect/impl/state/ZoomRadialBlurState.class`;
- as information (the criterion requires the names), `target/classes, information: <n> files in both, 0 differ by
  md5`, with `<n>` the after run's number of files (3,902 on macOS); a difference is to be investigated, not read as a
  failed criterion;
- under `jsl-compilers/decora:`, besides the hunk lines, only the four `<` lines of `./CompileBoxBlur.class`,
  `./CompileExternal.class`, `./CompileGaussian.class` and `./CompileZoomRadialBlur.class` (11 classes before, 7
  after), and nothing under `jsl-compilers/prism:` (8 and 8);
- after `before, ZoomRadialBlur:` the 8 files `ZoomRadialBlur.class` and `ZoomRadialBlurState.class` in each of
  `classes`, `mods/javafx.graphics`, `shims/javafx.graphics` and `test-classes`, and nothing after
  `after, ZoomRadialBlur:`;
- nothing from `find`;
- `shader-obj identical by name and md5`, and `262`: with `-d3d` the build generates 262 `.hlsl` files, 50 Decora
  and 212 Prism, and the `shaders` target of `native/win.cmake` (`:310-326` at `7a66c85e39`) compiles each with `fxc`
  into one `.obj`. That `fxc` output is byte-identical between two checkouts has not been verified on this host, so if
  the `cmp` fails, check that first (for example by comparing two builds of the before clone) before reading it as a
  regression.

Both runs use `-am`, which the criterion's Windows command lacked when the story was filed, as US-019's and US-020's
gates do: without it Maven takes `org.openjfx:javafx-base:28-ea` and its `tests` jar from the local repository, and in
a fresh clone with nothing installed the build stops with `Could not resolve dependencies`. With `-am`, javafx.base is
built in the same reactor and its tests run too, which is why the checks print a javafx.base line.

#### WSL gate
In WSL (Ubuntu), with JDK 27 and Maven 3.10.0 on `PATH`, from fresh clones, with `-am`, no display and no
`-Djfx.parity.require=true` (the Linux font goldens were captured on another machine):
```bash
cd ~
git clone <repo-url> jfx-before && git -C jfx-before checkout --detach 7a66c85e39
git clone <repo-url> jfx-after && git -C jfx-after checkout --detach <commit>
for t in before after; do
  (cd ~/jfx-$t && env -u DISPLAY -u WAYLAND_DISPLAY \
    mvn -B -ntp -l ../us021-wsl-$t.log -pl buildtools/jslc,modules/javafx.graphics -am clean test; echo "$t exit $?")
done
```
The checks are the Windows ones without the compiled-shader lines, run in `~` with `windows` replaced by `wsl` in the
file names. They must show what the Windows gate shows, with two differences:
- jslc `Tests run: 276, Failures: 0, Errors: 0, Skipped: 0` in both runs: Linux runs the three `link/..` tests;
- `108 jsl-decora` and `424 jsl-prism` from `-es2` (`pom.xml:48`), as in US-019's
  [WSL gate](US-019-reject-trailing-input-in-the-jsl-compiler.md#wsl-gate).

The javafx.base line is expected to be `Tests run: 5510, Failures: 0, Errors: 0, Skipped: 22` in both runs of both
gates: US-018's CI measured 5,510 / 0 / 0 / 22 on Windows x64 and on Linux
([US-018, Before the merge](US-018-fix-jslc-metal-header-path-and-static-state.md#before-the-merge-2026-11-07)).

`LinuxFontConfigGoldenTest`, `LinuxFontProcessGoldenTest`, `LinuxFreetypeGoldenTest` and `LinuxPangoGoldenTest` each
skip one test in both runs (US-008).

#### After the gates
The rest of the [Definition of Done](#definition-of-done):
- Keep each gate's lists with the change, as the macOS ones are kept. In the directory that holds the clones, for
  `windows` and then `wsl`, with `REPO` the checkout of the commit:
  ```bash
  merge() {
    awk -F'\t' 'function cell() { return $2 ", Failures: " $3 ", Errors: " $4 ", Skipped: " $5 }
      FNR == NR { b[$1] = cell(); all[$1] = 1; next } { a[$1] = cell(); all[$1] = 1 }
      END { for (c in all) print c "\t" ((c in b) ? b[c] : "-") "\t" ((c in a) ? a[c] : "-") }' "$1" "$2" |
      LC_ALL=C sort
  }
  o=windows
  { echo "# US-021: per-class results of <the gate's mvn command>, <host>, <date> (columns: class, before, after;" \
      "before = 7a66c85e39, after = <commit>; '-' = no such class)"
    merge us021-$o-before-per-class.tsv us021-$o-after-per-class.tsv; } > "$REPO/backlog/US-021-$o-per-class.tsv"
  cp us021-$o-after-gensrc.md5 "$REPO/backlog/US-021-$o-gensrc.md5"
  cp us021-$o-after-classes.txt "$REPO/backlog/US-021-$o-classes.txt"
  ```
  The after lists are kept: the gate requires the md5 lists to be equal, and the before file list is the after list
  plus the two `ZoomRadialBlur` classes.
- Fill in the results of both gates here and in `backlog/README.md`.
- File the two upstream issues from the draft, `US-021-upstream-draft.md`, kept in `_claude/` next to the repository:
  the generator inputs first, then the runtime `ZoomRadialBlur` classes, which refer to the first. The draft asks for
  its two Gradle task names to be run before filing.
- Set the status to ✅ Done with the date and the PR, and move the row to the done table of `backlog/README.md`.
