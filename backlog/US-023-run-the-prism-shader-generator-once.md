# US-023 — Run the Prism shader generator once per build, not once per `.jsl` file

**Status:** 🔷 Implemented (2026-11-17; filed 2026-09-27): option (a), one forked `<java>` step runs the Prism
generator once per build in place of 28 JVMs, and writes, rewrites and fails as the loop did, on Windows and in WSL;
the commit and the PR, filing the upstream issue and an optional macOS build are
[owner-run](#owner-run-and-pending) · **Found:** 2026-09-27, US-008 (filing US-021: its generator runs, one JVM per
Prism `.jsl` file as the pom runs them, showed the first JVM writing all 848 Prism files in 4.3 s and the other 29
writing nothing in 0.34 to 1.1 s each)

## Story
As a developer who builds `javafx.graphics`, with or without `clean`,
I want the Prism shader generator to run once per build,
so that the build does not start 27 more JVMs that compose all 212 Prism shaders again and write nothing, which,
measured with 29 of them before US-021, cost about 10 s of every Windows build of the module, clean or not, and 5 s of
a clean Linux build.

## Problem
Paths are relative to `modules/javafx.graphics`. `CompileJSL.java` is the Prism generator,
`src/main/jsl-prism/CompileJSL.java`; `JSLC.java` is `src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java`. A
bare line number (`:NN`) refers to the file named in the same sentence, else to the one named in the lead-in of its
list.

### The pom starts one JVM per `.jsl` file, in every build
- `<apply executable="${java.home}/bin/java" failonerror="true" parallel="false">` (`pom.xml:200-216`) runs
  `java -cp … CompileJSL -i src/main/jsl-prism -o target/gensrc/jsl-prism -t -pkg com/sun/prism <backends> -name
  <file>` once for each file of the fileset `**/*.jsl` (`:213-215`), one after the other: 28 JVMs since US-021
  deleted 2 of the 30 files.
- The `<apply>` has no mapper and no target file, so Ant compares nothing and runs all of them in every build. On a
  complete output tree Ant logged 30 starts and `Applied …\java to 30 files and 0 directories.` (measured before
  US-021, with 30 files). The antrun execution has no up-to-date check of its own either.
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
meanwhile; ranges are over 2 to 6 runs (Measured has the setup). Everything in this section was measured before
US-021, with 30 `.jsl` files and 30 JVMs; since US-021 the loop starts 28, the first and 27 that write nothing.
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
  `Texture_*` shaders that come before `FillPgram_Color` (66 files with all three backends, 32 with `-d3d`; amended
  2026-11-17: 64 with all three backends now, the four files of each of the 16, since US-018's fix writes no Prism
  Metal header before the failure).
  `failonerror` stops the `<apply>` there (`apply returned: 1`): no other JVM starts (measured, Ant verbose log).
  Since US-022 (amended 2026-11-17) the message is
  `MaskFillPgram.jsl:109:4: in program FillPgram_Color: missing ';' at 'return'`: the mask's text starts the program,
  so the line is the file's, as US-022's case S08 measured for an error on line 101 of this file (this case was not
  rerun).
- One `<java>` step prints the same message, writes the same files and stops with `Java returned: 1`.
- Built again unfixed, both fail the same way and rewrite nothing. Fixed and built without `clean`, both complete;
  the `-d3d` output then equals a clean build's by name and md5. With `-mtl`, `PrismShaderCommon.h` listed 196 of the
  212 shaders in both before US-018's fix (below).
- The message named no file before US-022; since US-022 it names `MaskFillPgram.jsl` and the program (amended
  2026-11-17). The loop adds a misleading file: the failing JVM was started with `-name …/MaskAlphaOne.jsl`, visible
  in a verbose log only.

### Logs
- At the default level neither generator step logs anything. The gate's Maven log goes from
  `[javac] Compiling 1 source file to …\jsl-compilers\prism` straight to the second-pass `javac`.
- The 30 starts and the `Applied … to 30 files` line (with 30 files, before US-021) are Ant verbose messages.
  maven-antrun-plugin 3.1.0 passes them on only with `-X`, where it sets Ant's level to debug (read from its
  `AntRunMojo`, not run). At that level Ant logs every start twice, `[apply] Executing '…java' with arguments:` and
  `Execute:Java13CommandLauncher: Executing …` (measured with Ant: 60 such lines for the step with 30 files, 2 for one
  `<java>`).

### The Metal headers of US-018 across the loop
- Before US-018's fix, `MSLBackend` kept the header directory and the Objective-C header in static fields
  (`src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/MSLBackend.java:58`, `:60`, `:64`), so each JVM had its
  own, and only a JVM that compiled a Metal shader wrote the headers (`:427-429`).
- Clean build: the first JVM wrote complete headers (`PrismShaderCommon.h` with 212 entries) and the others none.
  After the two edits above both variants left 16 and 66 entries, US-018's truncation; with the future time stamp
  every JVM rewrote the header with the same 28. So the loop neither caused nor hid US-018.
- With US-018's fix, the run of each Prism JVM lists all 212 shaders: it reads every shader's entry, compiled or up to
  date, from the uniform struct in its `.metal` file and writes a header only when its bytes change, so the later JVMs
  no longer overwrite the headers with fewer entries. A rerun with nothing changed takes 12.74 ms more per generator
  JVM than before US-018 (on macOS, the median of 9 repetitions: 394.9 ms over the 31 JVMs). A run that compiles all 212
  shaders, as `-force` makes it, takes 78.6 s through the loop and 2.8 s in one JVM.

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
  - This is the step as measured, before US-018. Since US-018 the `<apply>` also passes `-mtlheaders
    ${jfx.graphics.gensrc}/mtl-headers`, and the one step must keep it (One step, below): without it the Prism headers
    go to `target/gensrc/jsl-prism/mtl-headers`, and `native/mac.cmake` looks for them only in
    `target/gensrc/mtl-headers` (`:197`, `:240`).
  - The `<pathconvert>` (`:192-199`) can go: only the `<apply>` uses `jfx.jslc.prism.classpath` (`git grep`). The
    emulation kept it and passed its value as the class path, which is the same path.
  - `fork="true"` as today: the generator calls `System.exit(1)` for a missing input or a bad argument
    (`JSLC.java:300-304`), which must not reach the Maven JVM.
  - No Java change: without `-name`, `shaderName` is null until `CompileJSL.java:421` sets it (measured: exit 0, the
    same files).
  - Cost: about 25 pom lines replaced by 17, or 27 by 19 with `-mtlheaders`. Saves the 27 later JVMs (29 when
    measured, before US-021: about 10 s of every Windows build of the module, clean or not, and 5 s of a clean Linux
    build).
- **(b) `<apply parallel="true">`.** Fails today (above). It would need `CompileJSL` to accept file names and ignore
  them, for the same one JVM as (a).
- **(c) (a) with a step-level up-to-date check,** an Ant `<uptodate>` as in the 2013 Ant build. It would save the
  rest of a no-change build, about 0.3 to 0.6 s. The check must list every input of every output: the 28 `.jsl` files,
  the generator classes, `PrismLoaderGlue.stg`, and the JSL compiler's classes and templates on
  `maven.compile.classpath`. A missing one skips a needed run. It would not fix JDK-8090470, since the generator's own
  check still skips every output after a compiler change. Upstream closed the Decora version of this as Won't Fix
  (JDK-8093735). Not recommended.
- **(d) Run the Prism generator in the Decora JVM,** from `GenAllDecoraShaders`. Both generators are a
  default-package `CompileJSL` (`src/main/jsl-decora/CompileJSL.java:40`, `src/main/jsl-prism/CompileJSL.java:85`),
  so they cannot share a class path without a rename. In one JVM, US-018's static state put Decora entries into
  `PrismShaderCommon.h` before its fix (measured in US-018). It would save one more JVM start. Rejected.

**Recommendation: (a).** In every case measured it writes the same files, rewrites the same files and fails the same
way as the loop, and it removes 27 JVMs that do nothing (29 before US-021), with a pom change only.

**Upstream.** Replacing the loop with one `javaexec` without `-name` in `generatePrismShaders` would save the same 29
JVMs in upstream clean builds and after any `.jsl` change; no-op builds are skipped there already. Whether to propose
it is part of the story.

## Acceptance criteria
- **One step.** `pom.xml` runs the Prism `CompileJSL` in one `<java fork="true" failonerror="true">` with every
  generator argument of today's `<apply>`, US-018's `-mtlheaders ${jfx.graphics.gensrc}/mtl-headers` included, except
  `-name` and the file. `git grep -n -e '<apply' -e '<srcfile' -e '"-name"' --
  modules/javafx.graphics/pom.xml` finds nothing. The same search on the pom at `7a66c85e39` finds `:186`, `:201`
  and `:202`, so an empty result cannot come from a wrong path or pattern.
- **One JVM.** In the log of a clean
  `mvn -B -ntp -X -pl buildtools/jslc,modules/javafx.graphics -am clean test-compile -DskipNative=true`, the Prism
  step starts `CompileJSL` once: every start is logged with its arguments, `'-pkg'` followed by `'com/sun/prism'`
  (amended 2026-11-17: the command was `mvn -B -ntp -X -pl buildtools/jslc,modules/javafx.graphics clean compile`,
  which cannot resolve the `tests` jar of `javafx-base` for `javafx-graphics` at the compile phase, with or without
  `-am`, unless the local repository holds one, US-127; instead of `-X`, the antrun plugin's logger at debug,
  `-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugins.antrun=debug` in `MAVEN_OPTS`, logs the same starts). Mutant
  proof: the same count with the current pom must give 28 starts (30 before US-021).
- **Same files, clean.** The generators, run as the old and the new pom run them with `-d3d -es2 -mtl`, write 1,059
  files identical by name and md5, and with `-d3d` 532 files, identical to the old pom's.
- **Same files, without `clean`.** After a clean build, `touch src/main/jsl-prism/MaskFillPgram.jsl` and build again
  without `clean`. Exactly the 16 `FillPgram_*` backend files and their 16 loaders under `target/gensrc/jsl-prism` get
  a new modification time (32 files on Windows with `-d3d` and on Linux with `-es2`), with the old pom and the new one
  alike, and no file changes content. Mutant proof: with `-force` added to the Prism step's arguments the check must
  fail (amended 2026-11-17: filed as "all 424 files rewritten"; `-force` rewrites every backend file but no loader that
  is up to date, since `CompileJSL.compileShader` writes a loader only when it is out of date and never reads the flag,
  `CompileJSL.java:420`. In WSL, after the touch, 228 files: the 212 `.frag` files and the 16 `FillPgram_*` loaders. On
  Windows, in the next build, with `-force` passed through `-Djfx.jsl.backends` and so reaching the Decora step too,
  320: the 212 `.hlsl` files and the 108 files of `jsl-decora`).
- **Same failure.** With the `;` at the end of `MaskFillPgram.jsl:108` removed in a scratch copy of the tree, a clean
  build fails in `jsl-codegen` with `MaskFillPgram.jsl:109:4: in program FillPgram_Color: missing ';' at 'return'`
  after writing the same 32 files as the old pom (amended 2026-11-17: the message was `line 109:4 missing ';' at
  'return'` before US-022). With the `;` restored, the next build without `clean` passes and
  `target/gensrc/jsl-prism` equals a clean build's by name and md5.
- **Time recorded** (not gated): for the clean gate builds before and after, the time from the newest file under
  `target/gensrc/jsl-prism` to the creation of `target/mods`, and the time of the Prism step in a build without
  `clean` and without changes, on Windows and on Linux.
- **Build gate,** with the two commands the US-008 gate ran, the Windows one with `-am` (amended 2026-11-15 from
  US-021's review: without it the build takes `javafx-base` from the local repository, as US-019's and US-020's gates
  say):
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`
    (the Linux font goldens were captured on another machine; see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,mtl-headers}` is identical by name and md5 before and after. On each system
    `jsl-decora` holds 108 files and `jsl-prism` 424, and there is no Metal header, so the Metal output is covered only
    by the `-d3d -es2 -mtl` regeneration above (amended 2026-11-15 from US-021's review: `headers` and its 46 files
    are dropped, because `9b02d6a602`, #57, removed the second-pass `javac -h` and the directory no longer exists).
  - `javafx.graphics` has the same test counts per class as before, and `javafx-jslc` runs its 405 tests with no
    failure (amended 2026-11-17: 276 before US-022).
- **Upstream.** Repeat the JBS search when the story is picked up and decide whether to propose the change upstream. A
  draft stays outside the repository.

## Definition of Done
- All acceptance criteria met, with the logs, md5 lists and modification-time lists of the runs kept with the change.
- The chosen option and the reason recorded in this file.
- An independent review repeated the clean, without-`clean` and failure comparisons with its own inputs.
- Open stories that describe the loop updated. A done or implemented story's record of the loop stays as it is,
  such as US-021's ("starts 2 Prism JVMs fewer").
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

## Notes
- Filed rather than fixed in US-008: US-008 removed dead code from the JSL compiler without changing the build. This
  story changes a build step, so it needs its own change and its own gate.
- Independent of US-018 and US-021. US-018's fix was measured with the loop's 30 Prism JVMs. US-021 (implemented
  2026-11-15) deleted the two YUV shaders, so the loop starts 28 JVMs, and the one JVM of this change replaces 28.
- The Ant build file, the scratch copies and the probe runs were not kept in the repository. The Measured section
  describes them fully enough to rebuild.

## Implementation (2026-11-17, Windows and WSL)
The change is described against `f5e137e15e`, the baseline ("before"). Every run was made on 2026-11-17 on two hosts:
Windows 10 Pro (10.0.19045) with Temurin 27+35 and Apache Maven 3.10.0, where the build generates `-d3d`, and WSL
Ubuntu with Temurin 27+35 and Apache Maven 3.10.0, where it generates `-es2`. On Windows every run used the main tree,
at a clean `f5e137e15e` before the change and with the change after it; in WSL every run used fresh clones of
`f5e137e15e`, the after clones with the main tree's pom copied in and checked by its md5. macOS was not run. Paths are
relative to `modules/javafx.graphics`, as in Problem. Line numbers of `pom.xml` are those of the tree with the change
unless a commit is named; `CompileJSL.java` and `JSLC.java` are the same before and after. Times of day are
America/New_York.

The work went in this order: the baseline gates on Windows and in WSL; probes of the old pom on Windows (the start
count, builds without `clean` and the touch case); the change; then, each on its own, runs of the new pom on Windows,
the regeneration outside Maven on Windows, old against new in WSL clones, an independent review with its own inputs
and mutants, and the upstream check; the rework of the pom's comment, a confirming review, and the final gates on
Windows and in WSL. This section describes the final change; [Independent review](#independent-review-2026-11-17) and
[Rework and confirming review](#rework-and-confirming-review-2026-11-17) give the findings and what was done with them.

Kept with the change:
- [`US-023-build-runs.md`](US-023-build-runs.md): how each build and generator run was made, with its start count,
  the files it wrote or rewrote, the failure text, the mutants and the step times;
- [`US-023-windows-per-class.tsv`](US-023-windows-per-class.tsv) and
  [`US-023-wsl-per-class.tsv`](US-023-wsl-per-class.tsv), the final gates' per-class lists, before and after;
- [`US-023-windows-gensrc.md5`](US-023-windows-gensrc.md5) and [`US-023-wsl-gensrc.md5`](US-023-wsl-gensrc.md5), the
  after gates' `target/gensrc` md5 lists (the before lists are `cmp`-identical);
- [`US-023-regeneration.md5`](US-023-regeneration.md5), the 1,059 files that the new pom's clean build with
  `-Djfx.jsl.backends="-d3d -es2 -mtl"` writes in WSL (the old pom's list is `cmp`-identical, and so is
  `US-022-regeneration.md5`).

The gate and build logs, the modification-time snapshots, the WSL clones, the mutant poms and the outputs of the runs
outside Maven are not kept. `US-023-build-runs.md` keeps each run's command, exit code, start count, file counts and
lists of rewritten files, and the excerpts it relies on; it and the sections below describe each run fully enough to
repeat it.

### Choice: option (a), one forked `<java>` step
- **Why (a).** In every case measured, with Ant when the story was filed and now through Maven on Windows and in WSL,
  one run writes the same files, rewrites the same files and fails the same way as the loop.
- **Against (b), `<apply parallel="true">`.** It needs a Java change, `CompileJSL` accepting file names that it then
  ignores, for the same single JVM. Run again outside Maven: exit 1 with `unrecognized argument: …MaskAlphaTexture.jsl`
  and no file written.
- **Against (c), a step-level up-to-date check.** It adds a hand-kept list of inputs that can skip a needed run, and
  what makes the build regenerate is US-024's subject.
- **Against (d), the Prism generator in the Decora JVM.** It needs a class rename: both generators are a
  default-package `CompileJSL`.
- **The `<pathconvert>` went with the `<apply>`.** Nothing else used `jfx.jslc.prism.classpath`; `git grep` finds the
  name only in this story's text.
- **No Java change and no new test.** The step is a pom step, and no unit test can run antrun; the criteria gate it by
  a search, a log count and file comparisons. `-name` stays in the shared `JSLCInfo`, where `JSLC.main` uses it.

### Change
In the `jsl-codegen` execution of `pom.xml`, the `<pathconvert>` and the `<apply>` (`:178-204` at `f5e137e15e`) are
replaced by one comment line and one `<java>` step (`:178-197`):
```xml
<!-- One run, not one per .jsl file as upstream: every run composes every program -->
<java classname="CompileJSL" fork="true" failonerror="true"
      dir="${project.basedir}">
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
    <arg value="-mtlheaders"/>
    <arg value="${jfx.graphics.gensrc}/mtl-headers"/>
    <arg value="-pkg"/>
    <arg value="com/sun/prism"/>
    <arg line="${jfx.jsl.backends}"/>
</java>
```
- The class path has the four elements of the `<pathconvert>` in their order. The arguments are those of the `<apply>`
  but `-name` and the file, `-mtlheaders ${jfx.graphics.gensrc}/mtl-headers` included. `fork="true"`,
  `failonerror="true"` and `dir="${project.basedir}"` are as before. The start tag wraps as the `GenAllDecoraShaders`
  step's does (`:140-141`).
- Logged, the new start differs from the old pom's first one only in `-classpath` for `-cp` and in the missing
  `-name <file>`: the class path string, 13 elements, is byte-identical, and so are the other arguments and their
  order. On Windows Ant logs the executable as `…\bin\java.exe`, where the `<apply>` logged `…\bin\java` of the same
  JDK; in WSL both log the same `…/jdk27/bin/java`.
- `git diff --stat`: 1 file, 6 insertions, 13 deletions; the pom has 960 lines (967 at `f5e137e15e`). Like the rest of
  the file, all 960 lines end in CRLF; there is no tab and no trailing blank. The six added lines are at most 117
  columns; the 18 lines over 120 columns are older lines, left as they were.
- The comment says why there is one run where upstream has one per file. It was reworded after the review: the first
  version said "it takes no input file", which is not true, since the generator reads all 28.
- No other file of the build changes: no Java, `.jsl`, test, CMake file or other pom.

### Behaviour changes
- **One JVM.** A debug log shows 1 start of the Prism `CompileJSL` (2 logged blocks) where it showed 28 (56).
- **The failure line.** Maven's error says `An Ant BuildException has occured: Java returned: 1` where it said
  `apply returned: 1`, and the Ant part it quotes is the `<java>` element where it was the `<apply>`. The generator's
  output in the log is prefixed `[java]`, not `[apply]`; its text is the same.
- **No misleading file.** The failing JVM is no longer started with `-name …/MaskAlphaOne.jsl`.
- **A `jsl-prism` directory without any `.jsl` file.** The loop started no JVM and passed, with no Prism shader
  written; the one step fails with `Input file not found: MaskSolid.jsl` (read from the code by the review, not run).
- Unchanged: at the default log level neither step logs anything when it succeeds, and a missing `src/main/jsl-prism`
  fails the generators' `<javac>` before either step runs (read by the review).

### Drift since filing
What the filed text says against what is true at `f5e137e15e`, or was measured on 2026-11-17:

| Filed text | Says | Now |
| --- | --- | --- |
| Acceptance criteria, One JVM | `mvn -B -ntp -X -pl buildtools/jslc,modules/javafx.graphics clean compile` | At the compile phase `javafx-graphics` cannot resolve `org.openjfx:javafx-base:jar:tests:28-ea`, with `-am` too: on Windows `-am clean compile` exits 1 before any mojo of `javafx-graphics` runs. In WSL the command as written passed only because the local repository there holds a `javafx-base` 28-ea jar and `tests` jar of 2026-08-31. The counts were made with `-am clean test-compile -DskipNative=true`, with `-X` or with the antrun logger at debug. The criterion is amended; [US-127](US-127-make-a-build-to-the-compile-phase-pass-from-a-fresh-clone.md) asks for a build to the compile phase that passes from a fresh clone |
| Acceptance criteria, Same files without `clean` | the `-force` mutant rewrites all 424 files | 228 in WSL and 320 on Windows: `-force` never rewrites a loader that is up to date (`CompileJSL.java:420`). Amended |
| A failure is the same with one JVM | 66 files with all three backends | 64: since US-018's fix no Prism Metal header is written before the failure; 32 with `-d3d` holds. Amended |
| The pom starts one JVM per `.jsl` file | 28 JVMs since US-021 | 28 starts in every old-pom debug log, on Windows and in WSL; the 28 `-name` values are the 28 `.jsl` files in `LC_ALL=C` order, `MaskAlphaOne.jsl` first |
| Acceptance criteria, Same files, clean | 1,059 files identical by name and md5 | Through Maven in WSL, old and new pom, `cmp`-identical to `US-022-regeneration.md5`. Outside Maven on Windows, old and new are identical, but 270 generated `.java` files (58 Decora, 212 Prism) differ from `US-022-regeneration.md5`, which was made on macOS, by their line ends only: on Windows the generators write them with CRLF, and without the CRs the list is identical |

Line references, as the filed text gives them, at `f5e137e15e` and with the change:

| Reference | Filed | At `f5e137e15e` | Now |
| --- | --- | --- | --- |
| `pom.xml`, the `<pathconvert>` | `:192-199` | `:178-185` | gone |
| `pom.xml`, the `<apply>`; its fileset | `:200-216`; `:213-215` | `:186-204`; `:201-203` | the comment `:178` and the `<java>` step `:179-197` |
| `pom.xml`, `target/mods` | `:220` | `:207` | `:200` |
| `pom.xml`, `jfx.jsl.backends`: `-d3d`, `-mtl -es2`, `-d3d -es2`, `-mtl` | `:635`, `:667`, `:652`, `:685` | `:620`, `:652`, `:637`, `:670` | `:613`, `:645`, `:630`, `:663` |
| `CompileJSL.java`, `parseAllArgs`; `main` | `:473`; `:476-507` | `:445`; `:437-481` | the same |
| `CompileJSL.java`, `shaderName` set before every compile | `:421` | `:416` | the same |
| `CompileJSL.java`, the loader written when out of date | `:424-429` | `:419-424`, the check at `:420` | the same |
| `JSLC.java`, `-name` stored | `:358-359` | `:423-424` | the same |
| `native/mac.cmake`, the Metal header include | `:197`, `:240` | `:196`, `:239` | the same |

The other line references of Problem, Measured and Proposed fix were not checked again.

### Acceptance criteria and evidence
| Criterion | Evidence | State |
| --- | --- | --- |
| One step | `git grep -n -e '<apply' -e '<srcfile' -e '"-name"' -- modules/javafx.graphics/pom.xml` finds nothing in the tree with the change; on `7a66c85e39` it finds `:186`, `:201` and `:202`. The step has every argument of the `<apply>` but `-name` and the file, `-mtlheaders` included ([Change](#change)) | Met |
| One JVM | With the amended command: old pom 28 starts (56 logged blocks), new pom 1 (2 blocks), on Windows and in WSL; in WSL also 28 against 1 with `-Djfx.jsl.backends="-d3d -es2 -mtl"`. The counter gives 28 on every old-pom log, so a count of 1 is not a miss ([starts](US-023-build-runs.md#starts)) | Met, as amended |
| Same files, clean | `-d3d -es2 -mtl` through Maven in WSL: 1,059 files (`jsl-decora` 208, `jsl-prism` 848, `mtl-headers` 3) from both poms, `cmp`-identical to each other and to `US-022-regeneration.md5` ([md5 list](US-023-regeneration.md5)); outside Maven on Windows, old and new identical. `-d3d` on Windows and `-es2` in WSL: 532 files (108, 424, no Metal header) from both poms, `cmp`-identical to the baseline gates' lists | Met |
| Same files, without `clean` | After `touch src/main/jsl-prism/MaskFillPgram.jsl`: 32 files with a new time on Windows (16 `.hlsl`, 16 loaders) and in WSL (16 `.frag`, 16 loaders), the same paths with the old pom and the new one, no md5 changed ([files](US-023-build-runs.md#same-files-without-clean)). The `-force` mutant: 320 on Windows and 228 in WSL, so the check fails | Met, as amended |
| Same failure | In WSL clones through Maven: both poms exit 1 in `jsl-codegen` with `MaskFillPgram.jsl:109:4: in program FillPgram_Color: missing ';' at 'return'` after writing the same 32 files (16 `.frag`, 16 loaders), md5-identical to a clean build's; built again unfixed, exit 1 and nothing rewritten; fixed and built without `clean`, 392 files added, none rewritten, and `target/gensrc` equals a clean build's by name and md5 ([failure](US-023-build-runs.md#failure-case)). Outside Maven on Windows, the same with 32 files for `-d3d` and 64 for three backends, old and new alike | Met: through Maven in WSL, outside Maven on Windows |
| Time recorded | [Runs](#runs): the clean gates' Prism tail and the Prism step of builds without `clean` and without changes, on Windows and in WSL | Recorded |
| Build gate | Both commands before and after ([Final gates](#final-gates-2026-11-17)): exit 0, the same totals, the per-class lists and the `target/gensrc` md5 lists `cmp`-identical, 108 and 424 files and no Metal header, `javafx-jslc` 405 tests without a failure | Met |
| Upstream | [Upstream check](#upstream-check-2026-11-17): the story's JBS searches give the same counts and none is about the loop; proposing the change upstream decided; the draft is kept outside the repository | Checked and drafted; filing pending |

The Definition of Done's other items: the option and the reason are in
[Choice](#choice-option-a-one-forked-java-step); the independent review repeated the clean, without-`clean` and
failure comparisons with its own inputs ([Independent review](#independent-review-2026-11-17)); the open stories that
described the loop are updated, US-113 and US-024, and so are the `pom.xml` line numbers that US-121, US-122 and US-124
cite, while the records of US-018 and US-021 stay as they are; the logs are not kept, as for US-019 to US-022 (the kept
files are listed above); the status is ✅ Done only after the owner's commit and the PR.

### Runs
[`US-023-build-runs.md`](US-023-build-runs.md) gives the commands, counts, file lists and excerpts. In short:
- **Starts.** In the debug builds the old pom starts the Prism `CompileJSL` 28 times and the new pom once, on Windows
  and in WSL, and so with three backends; logged, the two command lines differ as [Change](#change) says.
- **Same files.** Clean: 532 files on each host and 1,059 with three backends, identical by name and md5 between the
  poms. Without `clean`: nothing rewritten when nothing changed; 32 files after the touch of `MaskFillPgram.jsl`; the
  review's touch of `PaintRadialGradient.jsl` and `MaskSolid.jsl`, 176 files; outside Maven, 64, 264 and 112 files for
  the story's two edits and its time in the future, the old pom's 28 JVMs rewriting the 112 each time.
- **Failure.** The story's case through Maven in WSL and outside Maven on Windows; the review's syntax error in
  `PaintTextureSecondPassLCD.jsl` and its missing input file through Maven in WSL: the same message, the same files
  written, the same rerun and the same fixed build with both poms.
- **Mutants and negative controls.** The `-force` mutant and the review's six pom mutants, with what catches each; the
  start counter, the "One step" search and the file comparisons, each shown to report a difference where there is one.
- **Times.** Wall times; the clean gates' times are those of the final gates:

  | Measure | Windows, before | Windows, after | WSL, before | WSL, after |
  | --- | --- | --- | --- | --- |
  | Clean gate: newest file under `target/gensrc/jsl-prism` to the creation of `target/mods` | 8.45 s | 0.054 s | 4.22 s | 0.009 s |
  | Build without `clean` and without changes: the Prism step | 9.339, 9.455 and 8.986 s | 0.375, 0.625 and 0.920 s | 13.804 and 10.790 s | 0.751 and 0.273 s |

  The Windows builds without `clean` of the new pom ran while another project's Maven test run (not this repository)
  ran on the machine, and the WSL ones beside other builds in WSL.

### Final gates (2026-11-17)
Before on the baseline and after on the final pom, the commands of the "Build gate" criterion. Counts are run /
failures / errors / skipped.

| Gate | Windows | WSL |
| --- | --- | --- |
| Command | `mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am clean test -Djfx.parity.require=true`, in the main tree | `mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am clean test`, in fresh clones, no display; the after clone with the main tree's pom, `cmp`-identical |
| Before | 14:02 to 14:09, exit 0, BUILD SUCCESS, Maven total 07:08 min | 14:12 to 14:19, exit 0, BUILD SUCCESS, 07:22 min |
| After | 16:29 to 16:37, exit 0, BUILD SUCCESS, 07:39 min | 16:40 to 16:48, exit 0, BUILD SUCCESS, 08:35 min |
| Modules, before and after | `javafx-jslc` 405 / 0 / 0 / 3, `javafx-base` 5,510 / 0 / 0 / 22, `javafx-graphics` 26,684 / 0 / 0 / 850 | `javafx-jslc` 405 / 0 / 0 / 0, `javafx-base` 5,510 / 0 / 0 / 22, `javafx-graphics` 26,428 / 0 / 0 / 967 |
| Per class | 954 classes, the lists `cmp`-identical ([per class](US-023-windows-per-class.tsv)) | 954, `cmp`-identical ([per class](US-023-wsl-per-class.tsv)) |
| `target/gensrc` | 532 files, `jsl-decora` 108 and `jsl-prism` 424, no `mtl-headers`; identical by name and md5 ([md5 list](US-023-windows-gensrc.md5)) | the same counts, identical ([md5 list](US-023-wsl-gensrc.md5)) |
| Other outputs, by md5 | `target/jsl-compilers` 7 and 8 classes, `target/classes` 3,901 files and the 262 compiled shaders (`.obj`) identical | `target/jsl-compilers` 7 and 8 classes and `target/classes` 3,901 files identical |
| Crash files | no `hs_err_pid*` newer than the start under `modules/javafx.base`, `modules/javafx.graphics` or `buildtools/jslc` | none |

- On Windows jslc's 3 skipped tests are those of `MetalHeadersTest` (33 / 0 / 0 / 3), before and after.
- During the Windows after gate another project's Maven test run (not this repository) ran on the machine, so its time
  is no clean comparison. In WSL no other build was running at the start of either gate or the end of the after gate;
  the after gate took 73 s longer, in every module (`javafx-base` 1:22 against 0:57 min, `javafx-graphics` 6:41
  against 5:59 min), which was not explained.
- The per-class lists come from each log's per-class `Tests run:` lines and are merged as US-022's were.
- No source of a shipped module changes, and every generated file and every file of `target/classes` is
  md5-identical, so no `FULL_TEST`/`USE_ROBOT` run is called for. The PR's `submit.yml` run, required for a pom
  change, is to cover the other platforms.

### Independent review (2026-11-17)
Of the change before the rework, with its first comment line, in WSL clones of its own (`-es2`), one build at a time,
with the timed build of `US-023-build-runs.md`; other agents built at the same time. Verdict: pass, no must-fix.

| Finding | Raised as | Done |
| --- | --- | --- |
| The `-force` mutant of "Same files, without `clean`" expects all 424 files rewritten; its run of the mutant rewrote 228, because `CompileJSL.compileShader` never reads the flag for a loader (`CompileJSL.java:420`). The mutant is still caught | should-fix | The criterion amended in place; measured on Windows too (320) |
| The "One JVM" command works only with a `javafx-base` 28-ea in the local repository: as written it passed in WSL through stale jars of 2026-08-31, and the Windows local repository holds only `.lastUpdated` markers there | should-fix | The criterion amended in place to the command the counts were made with; US-127 filed |
| Definition of Done items open: the status, the chosen option, the README row and US-113, which still described the 28-JVM `<apply>`; US-018 (Merged, not Done) describes the 30 JVMs | should-fix | This write-up: the status, [Choice](#choice-option-a-one-forked-java-step), the README row, US-113 and US-024; US-018's text is the record of a merged story and stays |
| The comment said "it takes no input file", which is not true: the generator reads all 28 `.jsl` files and ignores `-name` | optional | Taken: reworded and confirmed ([Rework](#rework-and-confirming-review-2026-11-17)) |
| The Metal header path is checked only by a `-mtl` run | optional | Covered by its three-backend builds through Maven: the new pom's 1,059 files equal the old pom's and US-022's list, and the mutant without `-mtlheaders` writes 1,060 with `PrismShaderCommon.h` under `jsl-prism/mtl-headers`, a list that differs from the kept one |
| `-force` does not regenerate the Prism loaders | outside the story | No new story: recorded here, and US-024's option (c), which assumed it, corrected |

Its design verdicts, all sane: option (a), since the logged command lines and the outputs match in every case it ran;
the `<pathconvert>` removed; `-mtlheaders` kept, which is required (`native/mac.cmake:196` and `:239` read
`${GENSRC_DIR}/mtl-headers`); no Java change, since `JSLCInfo` is shared and `JSLC.main` uses `-name`; no new test,
since no unit test can run antrun and the loop coming back would cost time, not correctness; the comment, whose wording
it asked to tighten; and the CRLF line ends, which the whole file has.

What it confirmed, besides its cases and mutants ([review](US-023-build-runs.md#the-reviews-own-cases-and-mutants)):
the same executable, class path and arguments but `-cp` and `-name`; the same working directory attribute and the
inherited environment; the same output on failure under another prefix; the backends of macOS and `INCLUDE_ES2`
passed by the unchanged `<arg line="${jfx.jsl.backends}"/>`; a clean build in a directory whose path holds a space
(Linux only); and that nothing in the poms, CMake files, `.github`, `buildtools` or documentation reads the removed
step (`jfx.jslc.prism`, `[apply]` and `apply returned` appear only in backlog text; `javafx.controls/pom.xml:116` has
an unrelated `<apply>` of its own). Not checked by it: Windows, macOS, the story's own `MaskFillPgram.jsl` cases (left
to the other runs), the gates and upstream.

### Rework and confirming review (2026-11-17)
- **Rework.** Line 178 only, the comment, reworded to
  `<!-- One run, not one per .jsl file as upstream: every run composes every program -->` (117 columns, CRLF). `diff`
  against the reviewed pom shows that one line; `git diff --stat` stays 6 insertions and 13 deletions; the "One step"
  search still finds nothing, and `:186`, `:201` and `:202` at `7a66c85e39`. A timed build without `clean` on Windows
  after the edit: exit 0, 1 start (2 blocks), the logged command line `cmp`-identical to the reviewed pom's, and
  `target/gensrc` unchanged (532 files, 0 new times, 0 md5 changes) and `cmp`-identical to the baseline gate's list.
- **Confirming review,** on the main tree: verdict pass, no must-fix. Every statement of the comment is true: one
  `<java>` without a fileset; upstream's last Gradle build before the Maven port (`7c77121fee`) runs one `javaexec` per
  `.jsl` file with `-name`; `CompileJSL.main` composes every program whatever its arguments. Its own start counter
  gives 1 on the rework's log and 28 on the old pom's; the command lines are `cmp`-identical; `target/gensrc` equals the
  baseline gate's. Two optional notes concern the brief's format check: the pom's longest line is 256 columns at
  `f5e137e15e` and after (18 lines over 120 in both, the same lengths), so the check's "longest" value cannot show a
  121-column line, which a count of lines over 120 does (19 under its mutant); CRLF and tab mutants were caught.
  Nothing was changed for them, since those 18 lines are not part of the change.

### Upstream check (2026-11-17)
- **JBS,** project JDK, through the anonymous REST API (`text ~`). The story's searches give the counts of 2026-09-27:
  `generatePrismShaders` 4, `CompileJSL` 3, `"jsl-prism"` 13, component `javafx` with `JSL` and `incremental` 3,
  `javaexec` and `shader` 0. Twenty-six more searches (for example `CompileJSL` with `once`, `"Prism shaders"` with
  `"build time"`, `JSL` with `"per file"`, and the `javafx` issues with `shader`, `JSL` or `shaders` created since
  2026-09-27) find nothing about the loop. JDK-8172236 is Open (its last comment, of 2026-09-02, predates the story's
  search); JDK-8093735 is still Won't Fix; JDK-8090470 is Open, unchanged since 2018. Related, not in the story:
  JDK-8357625, fixed in jfx27 by `682ee49241` (2026-02-20), set the backends inside the loop to the platform's own and
  kept the loop; JDK-8344728, "☂ Gradle build modernization", is the open umbrella for Gradle build work.
- **openjdk/jfx master** at `f1bb639dab` (2026-11-16): the loop is unchanged, 6 lines lower than filed, at
  `build.gradle:2627-2651` (`-name` at `:2649`), and the task's `inputs.dir`/`outputs.dir` are at `:1747-1748`. Master
  has 30 Prism `.jsl` files. Its Prism `CompileJSL.java` is no longer identical to the fork's, as the story said when
  filed: it is the fork's file before `acf30cfe29` (#50), which #50, #63 and #67 changed since. It still ignores
  `-name` (`JSLC.java:430-431` stores it, `CompileJSL.java:421` overwrites it). Of 51 open pull requests, #2346
  (JDK-8394770, Direct3D 12) edits the loop's backends to `-d3d -d3d12`, #2284 (JDK-8090503) adds a `.jsl` file, which
  would make the loop start 31 JVMs, and #2329 edits three Prism `.jsl` files.
- **Decision:** propose it, at low priority: an Enhancement in `javafx/build`, linked to JDK-8344728, one `javaexec`
  without `-name` in `generatePrismShaders`. Upstream skips the task in a build without changes, so it would save 29
  JVMs in clean builds and after a `.jsl` change only. Filing is the owner's.
- **Draft:** kept outside the repository, at
  `C:/Users/bestq/AppData/Local/Temp/claude/50b9cf433e233190/c7bd4e23-a56d-46c7-aab9-686107582407/scratchpad/us023/upstream/US-023-upstream-draft.md`.
  That is a temporary directory: the owner must copy the draft elsewhere, for example next to US-022's in `_claude/`,
  before it is cleaned. Its measured numbers are the story's of 2026-09-27, with Ant; Gradle was not run.

### Owner-run and pending
- **The commit and the PR,** with the pom and the backlog files of this write-up. The PR's `submit.yml` run must pass,
  as for any pom change.
- **Filing the upstream issue** from the draft above, once it is copied out of the temporary directory.
- **Done:** set the status to ✅ Done with the date and the PR, and move the row to the done table of
  `backlog/README.md`.
- **Optional, a macOS build,** the only place where the Metal headers are compiled (`native/mac.cmake:196`, `:239`),
  in a fresh clone of the commit: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`. Expected,
  as in US-022's macOS gate: BUILD SUCCESS, `javafx-jslc` 405 / 0 / 0 / 0, `javafx-base` 5,510 / 0 / 0 / 22,
  `javafx-graphics` 26,696 / 0 / 0 / 858, and `target/gensrc` (797 files: `jsl-decora` 158, `jsl-prism` 636,
  `mtl-headers` 3) identical by name and md5 to `US-022-macos-gensrc.md5`.

Not run: anything on macOS; Gradle; the "One JVM" criterion's command as filed, without `-am`, on Windows (with `-am`
it fails there, see [Drift](#drift-since-filing)); the "Same failure" case through Maven on Windows, which has no
scratch copy of the tree (it ran outside Maven there); the old pom with `-force`; a Windows path with a space.
