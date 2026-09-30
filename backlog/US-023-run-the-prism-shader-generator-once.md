# US-023 — Run the Prism shader generator once per build, not once per `.jsl` file

**Status:** 📋 Ready (filed 2026-09-27; the pom's generator steps were run outside Maven with Ant 1.10.12, the version maven-antrun-plugin 3.1.0 uses, today's `<apply>` against one `<java>` step, on Windows: the same files and the same failures in every case measured; Maven, Linux and macOS not run, apart from reading the file times of the US-008 gate builds) · **Found:** 2026-09-27, US-008 (filing US-021: its generator runs, one JVM per Prism `.jsl` file as the pom runs them, showed the first JVM writing all 848 Prism files in 4.3 s and the other 29 writing nothing in 0.34 to 1.1 s each)

## Story
As a developer who builds `javafx.graphics`, with or without `clean`,
I want the Prism shader generator to run once per build,
so that the build does not start 29 more JVMs that compose all 212 Prism shaders again and write nothing, which
costs about 10 s of every Windows build of the module, clean or not, and 5 s of a clean Linux build.

## Problem
Paths are relative to `modules/javafx.graphics`. `CompileJSL.java` is the Prism generator,
`src/main/jsl-prism/CompileJSL.java`; `JSLC.java` is `src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java`. A
bare line number (`:NN`) refers to the file named in the same sentence, else to the one named in the lead-in of its
list.

### The pom starts one JVM per `.jsl` file, in every build
- `<apply executable="${java.home}/bin/java" failonerror="true" parallel="false">` (`pom.xml:200-216`) runs
  `java -cp … CompileJSL -i src/main/jsl-prism -o target/gensrc/jsl-prism -t -pkg com/sun/prism <backends> -name
  <file>` once for each file of the fileset `**/*.jsl` (`:213-215`), one after the other: 30 JVMs.
- The `<apply>` has no mapper and no target file, so Ant compares nothing and runs all 30 in every build. On a
  complete output tree Ant logs 30 starts and `Applied …\java to 30 files and 0 directories.` (measured). The antrun
  execution has no up-to-date check of its own either.
- The backends are `jfx.jsl.backends`: `-es2` by default (Linux, `:48`), `-d3d` on Windows (`:635`), `-mtl -es2` on
  macOS (`:667`), and `-d3d -es2` or `-mtl` with `INCLUDE_ES2` (`:652`, `:685`).

### The generator ignores the file name and does all the work in every JVM
- `parseAllArgs` (`CompileJSL.java:473`) stores `-name` in `jslcinfo.shaderName` (`JSLC.java:358-359`), and
  `compileShader` overwrites it before every compile (`CompileJSL.java:421`). Nothing reads it in between.
- `main` composes all 212 programs (`CompileJSL.java:476-507`): 11 masks with the color, image-pattern and 6 gradient
  paints, 3 alpha masks with the color, image-pattern and 2 gradient paints, and 6 texture shaders, each without and
  with `_AlphaTest`.
- It writes each backend output and each `_Loader.java` only when it is out of date (`JSLC.java:193-244`,
  `CompileJSL.java:424-429`); the pom passes no `-force` (`JSLC.java:330-331`).
- No version of the Prism generator has read `-name`. From its import in `839a0d8349` (2013) to its last change,
  `f0312b0e3d` (2025), `compileShader` sets `shaderName` before every compile.

### What "out of date" compares
- `JSLC.outOfDate(outFile, sourceTime)` is `sourceTime >= outFile.lastModified()` (`JSLC.java:249-251`). A missing
  output has time 0.
- A Prism program's source time is the newer of its mask and paint files (`CompileJSL.java:406-408`); for a gradient,
  the paint's is the newer of `PaintMultiGradient.jsl` and its own file (`:259-265`). The times of `MaskSolid.jsl`,
  `MaskAlphaOne.jsl` and `PaintColor.jsl` count although their text is left out (`:361-377`).
- Nothing else counts. After the first JVM of a build has written an output, every later JVM finds it newer than its
  sources and skips it.
- The generator, the JSL compiler and their templates do not count either, with one JVM or thirty: a changed generator
  run on a complete output tree rewrote none of the 1,059 files, where a clean run with it changes 318 (measured; its
  alpha test was changed from `== 0.0` to `<= 0.0`). JDK-8090470, "force running of CompileJSL if compiler sources
  were modified", has been open upstream since 2012.

### What the loop costs
Times of the Ant runs are wall times, about 0.44 s of Ant's own start included, on a machine that ran other work
meanwhile; ranges are over 2 to 6 runs (Measured has the setup).
- **Clean build.** The first JVM writes every Prism output and the Metal headers; the other 29 write nothing.
  - US-008's gate builds (Maven, read from file times): on Windows (JDK 26, `-d3d`) the last Prism output is written
    at 20:39:18.02 and `target/mods`, the next task (`pom.xml:220`), is created at 20:39:30.04: 12.0 s for the 29
    later JVMs, after about 2.9 s for the first. On Linux (WSL, JDK 25, `-es2`): 4.9 s after about 1.5 s.
  - US-021's generator runs and their check: 12.3 s and 9.4 s for the 29, a median of 0.35 and 0.32 s each.
  - Ant: the Prism step takes 10.6 to 13.3 s with `-d3d` and 12.3 to 13.6 s with `-d3d -es2 -mtl`; one JVM takes
    2.3 to 2.9 s and 3.3 to 3.5 s.
- **Build without `clean`, nothing changed.** 30 JVMs, no file written: 9.5 to 11.0 s. One JVM: 0.8 to 1.1 s.
- **Build without `clean` after one edit** (all three backends). The first JVM regenerates what the edit affects, the
  other 29 nothing.
  - `MaskFillPgram.jsl`: the 16 `FillPgram_*` shaders, 64 files rewritten (their `.hlsl`, `.frag`, `.metal` and
    loaders) plus 2 Metal headers: 10.3 s; one JVM 1.8 s.
  - `PaintLinearGradient.jsl`: 66 shaders, 264 files plus 2 headers: 11.1 s; one JVM 1.8 s.
- **A source time in the future** (clock skew; set here with `touch -d '+1 day'` on `PaintColor.jsl`). The check stays
  true, so each of the 30 JVMs rewrites the 112 files of the 28 `<mask>_Color` shaders and the 2 headers: 26.9 to
  31.5 s, and every later build does it again until the clock passes the time stamp. One JVM: 1.4 to 2.2 s.

### A failure is the same with one JVM
- With the `;` at the end of `MaskFillPgram.jsl:108` removed, the first JVM, the one named after `MaskAlphaOne.jsl`,
  fails with `ParseCancellationException: line 109:4 missing ';' at 'return'` after writing the 16 `Solid_*` and
  `Texture_*` shaders that come before `FillPgram_Color` (66 files with all three backends, 32 with `-d3d`).
  `failonerror` stops the `<apply>` there (`apply returned: 1`): no other JVM starts (measured, Ant verbose log).
- One `<java>` step prints the same message, writes the same files and stops with `Java returned: 1`.
- Built again unfixed, both fail the same way and rewrite nothing. Fixed and built without `clean`, both complete;
  the `-d3d` output then equals a clean build's by name and md5. With `-mtl`, `PrismShaderCommon.h` lists 196 of the
  212 shaders in both (US-018, below).
- The message names no file (US-022). The loop adds a misleading one: the failing JVM was started with
  `-name …/MaskAlphaOne.jsl`, visible in a verbose log only.

### Logs
- At the default level neither generator step logs anything. The gate's Maven log goes from
  `[javac] Compiling 1 source file to …\jsl-compilers\prism` straight to the second-pass `javac`.
- The 30 starts and the `Applied … to 30 files` line are Ant verbose messages. maven-antrun-plugin 3.1.0 passes them
  on only with `-X`, where it sets Ant's level to debug (read from its `AntRunMojo`, not run). At that level Ant logs
  every start twice, `[apply] Executing '…java' with arguments:` and `Execute:Java13CommandLauncher: Executing …`
  (measured with Ant: 60 such lines for today's step, 2 for one `<java>`).

### The Metal headers of US-018 across the loop
- `MSLBackend` keeps the header directory and the Objective-C header in static fields
  (`src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/MSLBackend.java:58`, `:60`, `:64`), so each JVM has its
  own, and only a JVM that compiles a Metal shader writes the headers (`:427-429`).
- Clean build: the first JVM writes complete headers (`PrismShaderCommon.h` with 212 entries) and the others none.
  After the two edits above both variants leave 16 and 66 entries, US-018's truncation; with the future time stamp
  every JVM rewrites the header with the same 28. So the loop neither causes nor hides US-018.
- It matters for US-018's fix. That story must keep "Prism's later JVMs" from overwriting the headers with fewer
  entries, and one of its ways computes the entries of every shader in each run, which the loop repeats 30 times. A
  run that compiles all 212 shaders, as `-force` makes it, takes 78.6 s through the loop and 2.8 s in one JVM.

### Why one JVM per file
- JDK-8101343 (fixed in 2011 for fx2.0) made the JSL tools write only out-of-date outputs; the first open-source JSLC
  has the check (`d0a313da5d`, 2012, `decora-compiler/src/com/sun/scenario/effect/compiler/JSLC.java:287-292`).
- `839a0d8349` (2013-02-25): the Ant build ran `CompileJSL` once, with `<java>` and no `-name`, and skipped it while
  `Solid_Color.frag` and `.hlsl` were newer than every file of `jsl` and `shadergen`
  (`prism-ps/build-common.xml:29-44`, `:50-80` there).
- `9666382d09` (2013-02-26, the first Gradle JSL tasks) made one `JavaExec` task per input file for both: each
  Decora task compiles the shader it names (`build.gradle:313-342` there), and each Prism task passes `-name $file`,
  with `inputs.file file`, to a generator that ignores it (`:381-398`).
- `9a5b9c0a54` (2013-04-15) replaced the Prism tasks with one `generatePrismShaders` task with `inputs.dir` and
  `outputs.dir` (`build.gradle:706-711` there) and kept the loop inside it (`:1008-1020`).
- The Maven port, `01f190af72` (2026-08-27), turned the loop into the `<apply>` (`pom.xml:189-205` there) without the
  task's up-to-date check, so the fork runs it in every build.

### Upstream too
- `openjdk/jfx` master (`d46e6092ae`, checked 2026-09-27) has the loop (`build.gradle:2621-2645`) and a Prism
  generator identical to this tree's.
- Gradle skips the task while nothing under `src/main/jsl-prism` or `gensrc/jsl-prism` changed
  (`build.gradle:1742-1743`). JDK-8172236's profile of a no-op build shows
  `:graphics:generatePrismShaders 0.007s UP-TO-DATE`. Upstream pays for the loop in clean builds and after a `.jsl`
  change only.
- JBS (searched 2026-09-27, project JDK): text `generatePrismShaders` (4 issues), `CompileJSL` (3), `"jsl-prism"`
  (13); component `javafx` with text `JSL` and `incremental` (3), `javaexec` and `shader` (0). None is about the
  loop. Related: JDK-8172236 (Open, 2017, incremental build performance), JDK-8093735 (no dependency check for the
  Decora `.jsl` files, closed as Won't Fix in 2014 because rebuilding them had become cheap), JDK-8090470 (above).

### Measured (2026-09-27, Windows 10, JDK 26)
**Setup.** Copies of `src/main/jsl-prism` and `src/main/jsl-decora`, and of the US-008 Windows gate's classes: the JSL
compiler (`buildtools/jslc/target/classes`), the generators (`target/jsl-compilers`) and the `javafx.base` and
`javafx.graphics` modules. The pom's `GenAllDecoraShaders` `<java>` and Prism `<apply>` were copied into an Ant build
file with only the paths changed, next to option (a)'s `<java>` below, and run with Ant 1.10.12. Outputs were compared
by name and md5, rewrites by modification time.

**The same files.**
- Clean, `-d3d -es2 -mtl`: both write 1,059 files (208 `jsl-decora`, 848 `jsl-prism`, 3 `mtl-headers`), identical by
  name and md5 to each other and to US-021's regeneration. `PrismShaderCommon.h` has 212 entries in both.
- Clean, `-d3d`: both write 532 files, identical by name and md5 to the gate's
  `target/gensrc/{jsl-decora,jsl-prism,mtl-headers}` (108, 424, 0).
- Without `clean`, after each edit above, after touching `MaskFillPgram.jsl` and `PaintLinearGradient.jsl` (`-d3d`:
  152 files rewritten, the 76 affected shaders and their loaders), with the future time stamp, and after the syntax
  error was fixed: both rewrite the same files and end with the same md5s.
- `<apply parallel="true">` is not a one-JVM variant: it starts one JVM with all 30 file names after `-name`, and
  `parseAllArgs` rejects the second (`unrecognized argument: …MaskAlphaTexture.jsl`, exit 1, no file written).

**Not run:** Maven, Linux and macOS runs of either step; `fxc` and the Metal compiler. The Linux figure above comes
from the WSL gate build's file times.

## Proposed fix
Four options; the evidence decides for (a).
- **(a) One forked `<java>` step (recommended).** Replace the `<apply>` (`pom.xml:200-216`) with:
  ```xml
  <java classname="CompileJSL" fork="true" failonerror="true" dir="${project.basedir}">
      <classpath>
          <path refid="maven.compile.classpath"/>
          <pathelement location="${project.basedir}/src/jslc/resources"/>
          <pathelement location="${project.build.directory}/jsl-compilers/prism"/>
          <pathelement location="${project.basedir}/src/main/jsl-prism"/>
      </classpath>
      <arg value="-i"/>
      <arg value="${project.basedir}/src/main/jsl-prism"/>
      <arg value="-o"/>
      <arg value="${jfx.graphics.gensrc}/jsl-prism"/>
      <arg value="-t"/>
      <arg value="-pkg"/>
      <arg value="com/sun/prism"/>
      <arg line="${jfx.jsl.backends}"/>
  </java>
  ```
  - The `<pathconvert>` (`:192-199`) can go: only the `<apply>` uses `jfx.jslc.prism.classpath` (`git grep`). The
    emulation kept it and passed its value as the class path, which is the same path.
  - `fork="true"` as today: the generator calls `System.exit(1)` for a missing input or a bad argument
    (`JSLC.java:300-304`), which must not reach the Maven JVM.
  - No Java change: without `-name`, `shaderName` is null until `CompileJSL.java:421` sets it (measured: exit 0, the
    same files).
  - Cost: about 25 pom lines replaced by 17. Saves the 29 JVMs: about 10 s of every Windows build of the module,
    clean or not, and 5 s of a clean Linux build.
- **(b) `<apply parallel="true">`.** Fails today (above). It would need `CompileJSL` to accept file names and ignore
  them, for the same one JVM as (a).
- **(c) (a) with a step-level up-to-date check,** an Ant `<uptodate>` as in the 2013 Ant build. It would save the
  rest of a no-change build, about 0.3 to 0.6 s. The check must list every input of every output: the 30 `.jsl` files,
  the generator classes, `PrismLoaderGlue.stg`, and the JSL compiler's classes and templates on
  `maven.compile.classpath`. A missing one skips a needed run. It would not fix JDK-8090470, since the generator's own
  check still skips every output after a compiler change. Upstream closed the Decora version of this as Won't Fix
  (JDK-8093735). Not recommended.
- **(d) Run the Prism generator in the Decora JVM,** from `GenAllDecoraShaders`. Both generators are a
  default-package `CompileJSL` (`src/main/jsl-decora/CompileJSL.java:40`, `src/main/jsl-prism/CompileJSL.java:85`),
  so they cannot share a class path without a rename. In one JVM, US-018's static state puts Decora entries into
  `PrismShaderCommon.h` (measured in US-018). It would save one more JVM start. Rejected.

**Recommendation: (a).** In every case measured it writes the same files, rewrites the same files and fails the same
way as the loop, and it removes 29 JVMs that do nothing, with a pom change only.

**Upstream.** Replacing the loop with one `javaexec` without `-name` in `generatePrismShaders` would save the same 29
JVMs in upstream clean builds and after any `.jsl` change; no-op builds are skipped there already. Whether to propose
it is part of the story.

## Acceptance criteria
- **One step.** `pom.xml` runs the Prism `CompileJSL` in one `<java fork="true" failonerror="true">` with today's
  arguments except `-name` and the file. `git grep -n -e '<apply' -e '<srcfile' -e '"-name"' --
  modules/javafx.graphics/pom.xml` finds nothing. The same search on the current tree finds `:200`, `:213` and `:214`,
  so an empty result cannot come from a wrong path or pattern.
- **One JVM.** In the log of a clean `mvn -B -ntp -X -pl buildtools/jslc,modules/javafx.graphics clean compile`, the
  Prism step starts `CompileJSL` once: every start is logged with its arguments, `'-pkg'` followed by
  `'com/sun/prism'`. Mutant proof: the same count with the current pom must give 30 starts.
- **Same files, clean.** The generators, run as the old and the new pom run them with `-d3d -es2 -mtl`, write 1,059
  files identical by name and md5, and with `-d3d` 532 files, identical to the old pom's.
- **Same files, without `clean`.** After a clean build, `touch src/main/jsl-prism/MaskFillPgram.jsl` and build again
  without `clean`. Exactly the 16 `FillPgram_*` backend files and their 16 loaders under `target/gensrc/jsl-prism` get
  a new modification time (32 files on Windows with `-d3d` and on Linux with `-es2`), with the old pom and the new one
  alike, and no file changes content. Mutant proof: with `-force` added to the Prism step's arguments the check must
  fail (all 424 files rewritten).
- **Same failure.** With the `;` at the end of `MaskFillPgram.jsl:108` removed in a scratch copy of the tree, a clean
  build fails in `jsl-codegen` with `line 109:4 missing ';' at 'return'` after writing the same 32 files as the old
  pom. With the `;` restored, the next build without `clean` passes and `target/gensrc/jsl-prism` equals a clean
  build's by name and md5.
- **Time recorded** (not gated): for the clean gate builds before and after, the time from the newest file under
  `target/gensrc/jsl-prism` to the creation of `target/mods`, and the time of the Prism step in a build without
  `clean` and without changes, on Windows and on Linux.
- **Build gate,** with the two commands the US-008 gate ran:
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`
    (the Linux font goldens were captured on another machine; see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` is identical by name and md5 before and after. In the
    US-008 gate these held 108, 424, 46 and 0 files on each system; `mtl-headers` is empty there, so the Metal output
    is covered only by the `-d3d -es2 -mtl` regeneration above.
  - `javafx.graphics` has the same test counts per class as before, and `javafx-jslc` runs its 159 tests with no
    failure.
- **Upstream.** Repeat the JBS search when the story is picked up and decide whether to propose the change upstream. A
  draft stays outside the repository.

## Definition of Done
- All acceptance criteria met, with the logs, md5 lists and modification-time lists of the runs kept with the change.
- The chosen option and the reason recorded in this file.
- An independent review repeated the clean, without-`clean` and failure comparisons with its own inputs.
- Open stories that describe the loop updated: US-018 ("How the build runs it" and "Prism's later JVMs" in its
  Proposed fix) and US-021 ("starts 2 Prism JVMs fewer").
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

## Notes
- Filed rather than fixed in US-008: US-008 removed dead code from the JSL compiler without changing the build. This
  story changes a build step, so it needs its own change and its own gate.
- Independent of US-018 and US-021, but it changes their numbers. US-018's fix gets one Prism JVM per build to design
  for, and US-021's deletion of the two YUV shaders then saves no JVM.
- The Ant build file, the scratch copies and the probe runs were not kept in the repository. The Measured section
  describes them fully enough to rebuild.
