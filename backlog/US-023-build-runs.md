# US-023 — Build runs on Windows and in WSL

Evidence for [US-023](US-023-run-the-prism-shader-generator-once.md): the Prism generator step of the old pom
(`f5e137e15e`) and of the new one, through Maven on Windows and in WSL and outside Maven on Windows, with the start
counts, the files written and rewritten, the failure case, the mutants and the step times. The lists kept with it:
per class on [Windows](US-023-windows-per-class.tsv) and in [WSL](US-023-wsl-per-class.tsv), `target/gensrc` on
[Windows](US-023-windows-gensrc.md5) and in [WSL](US-023-wsl-gensrc.md5), and the
[regeneration](US-023-regeneration.md5).

## Hosts and trees
- **Windows:** Windows 10 Pro (10.0.19045), Temurin 27+35, Apache Maven 3.10.0, Git Bash; the build generates `-d3d`.
  Every run used the main tree: the old pom at a clean `f5e137e15e`, the new pom with the change in the working tree.
  Until the rework that was the pom with its first comment line (md5 `de8f96babaf139251861ec32b1420094`), from then
  on the final pom (md5 `63615e343f3d41ff3d6560e875a6d414`); the two differ in line 178, the comment, only.
- **WSL:** Ubuntu, Temurin 27+35, Apache Maven 3.10.0, `DISPLAY` and `WAYLAND_DISPLAY` unset; the build generates
  `-es2`. Every run used fresh clones of the main repository detached at `f5e137e15e`. A new-pom clone has the main
  tree's pom copied in, checked by its md5.
- All runs on 2026-11-17. Times of day are America/New_York. Other builds ran on the machine during some runs; the
  tables say where.

## Commands
- **Debug build,** the amended command of the "One JVM" criterion:
  `mvn -B -ntp -X -l <log> -pl buildtools/jslc,modules/javafx.graphics -am clean test-compile -DskipNative=true`.
  With `clean compile` in place of `clean test-compile -DskipNative=true` it fails on Windows before any mojo of
  `javafx-graphics` runs ([US-127](US-127-make-a-build-to-the-compile-phase-pass-from-a-fresh-clone.md)).
- **Timed build:** `mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am [clean] test-compile
  -DskipNative=true`, with `MAVEN_OPTS` set to the three properties
  `-Dorg.slf4j.simpleLogger.showDateTime=true`, `-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS` and
  `-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugins.antrun=debug`. The antrun logger at debug makes Ant log at its
  debug level, as `-X` does, while Maven stays at INFO.
- **Starts,** counted by a perl pass over the log. A block opens at a line that holds `Executing '` and
  `with arguments:` and closes at `The ' characters around the executable and arguments are`; it is a Prism start
  when its quoted arguments hold `-pkg` followed by `com/sun/prism`. At its debug level Ant logs every start twice, once
  from the task (`[apply] Executing` or `[java] Executing`) and once from `Execute:Java13CommandLauncher`, so one start
  is two blocks.
- **Snapshot** of `target/gensrc`: `find . -type f -printf '%p\t%T@\n' | LC_ALL=C sort`, joined on the path with each
  file's md5. Two snapshots are compared for files with a new time, files with a new md5, files added and files
  removed.
- **md5 list:** `(cd target/gensrc && find . -type f | LC_ALL=C sort | xargs md5sum)`. The lists kept in `backlog/` are
  in the two-space form; Git Bash's `md5sum` writes ` *` before the name.
- **Step time,** in a timed build: from the time stamp of the last line of the Prism generator's `<javac>` (in a build
  without `clean`, `[javac] …\jsl-prism\PrismLoaderGlue.stg skipped - don't know how to handle it`) to the `[mkdir]`
  line of `target/mods` (`Skipping … because it already exists`). For the old pom it includes the `<pathconvert>`, 3 to
  10 ms.

## Starts
| Build | Host | Old pom | New pom |
| --- | --- | --- | --- |
| Debug build, clean | Windows | 28 (56 blocks) | 1 (2 blocks) |
| Debug build, clean | WSL | 28 (56 blocks) | 1 (2 blocks) |
| Timed build, clean, the review's | WSL | 28 (56 blocks) | 1 (2 blocks) |
| Timed build, clean, with `-Djfx.jsl.backends="-d3d -es2 -mtl"`, the review's | WSL | 28 (56 blocks) | 1 (2 blocks) |
| Each timed build without `clean` that passed | Windows, WSL | 28 | 1 |
| Each build that failed in the generator | WSL | 1 | 1 |

- The old pom's debug log on Windows has, at line 37866,
  `[apply] Applied C:\Program Files\OpenJDK\jdk-27+35\bin\java to 28 files and 0 directories.`; the new step logs no
  such line.
- The 28 `-name` values of the old pom, in start order, which is the `LC_ALL=C` order of the names, are the 28 `.jsl`
  files of `src/main/jsl-prism`, on Windows and in WSL: `MaskAlphaOne`, `MaskAlphaTexture`,
  `MaskAlphaTextureDifference`, `MaskDrawCircle`, `MaskDrawEllipse`, `MaskDrawPgram`, `MaskDrawRoundRect`,
  `MaskDrawSemiRoundRect`, `MaskFillCircle`, `MaskFillEllipse`, `MaskFillPgram`, `MaskFillRoundRect`, `MaskSolid`,
  `MaskTexture`, `PaintAlphaTextureImagePattern`, `PaintAlphaTextureLinearGradient`,
  `PaintAlphaTextureRadialGradient`, `PaintColor`, `PaintImagePattern`, `PaintLinearGradient`, `PaintMaskTextureRGB`,
  `PaintMaskTextureSuper`, `PaintMultiGradient`, `PaintRadialGradient`, `PaintTextureFirstPassLCD`, `PaintTextureRGB`,
  `PaintTextureSecondPassLCD` and `PaintTextureYV12`, each `.jsl`.
- The new pom's one start, as `Execute:Java13CommandLauncher` logs it in the Windows debug build (line 33635), with
  the class path shortened:
  ```
  [DEBUG] Execute:Java13CommandLauncher: Executing 'C:\Program Files\OpenJDK\jdk-27+35\bin\java.exe' with arguments:
  '-classpath'
  '<the 13 elements below, separated by ;>'
  'CompileJSL'
  '-i'
  'C:\SourceCode\jfx-ffm\modules\javafx.graphics/src/main/jsl-prism'
  '-o'
  'C:\SourceCode\jfx-ffm\modules\javafx.graphics\target/gensrc/jsl-prism'
  '-t'
  '-mtlheaders'
  'C:\SourceCode\jfx-ffm\modules\javafx.graphics\target/gensrc/mtl-headers'
  '-pkg'
  'com/sun/prism'
  '-d3d'
  ```
- The old pom's first start, in its Windows debug build (line 36821):
  ```
  [DEBUG] Execute:Java13CommandLauncher: Executing 'C:\Program Files\OpenJDK\jdk-27+35\bin\java' with arguments:
  '-cp'
  '<the 13 elements below, separated by ;>'
  'CompileJSL'
  '-i'
  'C:\SourceCode\jfx-ffm\modules\javafx.graphics/src/main/jsl-prism'
  '-o'
  'C:\SourceCode\jfx-ffm\modules\javafx.graphics\target/gensrc/jsl-prism'
  '-t'
  '-mtlheaders'
  'C:\SourceCode\jfx-ffm\modules\javafx.graphics\target/gensrc/mtl-headers'
  '-pkg'
  'com/sun/prism'
  '-d3d'
  '-name'
  'C:\SourceCode\jfx-ffm\modules\javafx.graphics\src\main\jsl-prism\MaskAlphaOne.jsl'
  ```
- The class path, the same string in both (`cmp`): `modules/javafx.graphics/target/classes`,
  `modules/javafx.base/target/classes`, `buildtools/jslc/target/classes`, `antlr4-4.7.2.jar`,
  `antlr4-runtime-4.7.2.jar`, `antlr-runtime-3.5.2.jar`, `ST4-4.1.jar`, `org.abego.treelayout.core-1.0.3.jar`,
  `javax.json-1.0.4.jar`, `icu4j-61.1.jar` (the seven jars from the local repository),
  `modules/javafx.graphics/src/jslc/resources`, `modules/javafx.graphics/target/jsl-compilers/prism` and
  `modules/javafx.graphics/src/main/jsl-prism`.
- So the two differ in `-classpath` for `-cp`, in the `-name` pair and in the executable as logged, `java.exe`
  against `java` of the same JDK. In WSL the two blocks differ in `-classpath` and the `-name` pair only: the
  executable is `…/jdk27/bin/java` in both, and the class path, the same 13 elements, is identical once each clone's
  directory is replaced by one placeholder. The rework's build logged a command line `cmp`-identical to the one above.

## Same files, clean
- **Windows, debug builds:** old and new each leave 532 files in `target/gensrc` (`jsl-decora` 108, `jsl-prism` 424, no
  `mtl-headers`), their md5 lists `cmp`-identical to each other and to the Windows baseline gate's. The generator
  classes, `target/jsl-compilers/decora` (7) and `prism` (8), are identical by md5.
- **WSL, debug builds:** the same, 532 files (108, 424) in both clones, `cmp`-identical to each other and to the WSL
  baseline gate's list, and the 7 and 8 generator classes identical.
- **WSL, three backends, the review's timed clean builds** with `-Djfx.jsl.backends="-d3d -es2 -mtl"`: 1,059 files
  from each pom (`jsl-decora` 208, `jsl-prism` 848, `mtl-headers` 3), the lists `cmp`-identical to each other and to
  `US-022-regeneration.md5`; the new pom's is kept as [`US-023-regeneration.md5`](US-023-regeneration.md5).
  `PrismShaderCommon.h` has 212 entries in both.
- **Windows, outside Maven:** see [Regeneration outside Maven on Windows](#regeneration-outside-maven-on-windows).

## Same files without `clean`
- **Nothing changed.** Every timed build without `clean` and without a change, three per pom on Windows and two per
  pom in WSL, left all 532 files with their times and md5s: 0 new times, 0 md5 changes, nothing added or removed. Each
  snapshot before a build equals the one after the build before it.
- **`touch src/main/jsl-prism/MaskFillPgram.jsl`,** then a timed build without `clean`. The file keeps its content
  (md5 `51c98460b44aea5b156f2b7a7cb851d6`, git blob `07c6e08fb5d9`, the one at `f5e137e15e`). With each pom, on each
  host, exactly 32 files get a new time, all under `jsl-prism`; no md5 changes, nothing is added or removed, and no
  `jsl-decora` file is touched. The old and new sets are identical by path on Windows and in WSL. The files, under
  `target/gensrc/jsl-prism/com/sun/prism/`, for the 16 programs:

  | Program | Windows (`-d3d`) | WSL (`-es2`) | Loader, both |
  | --- | --- | --- | --- |
  | `FillPgram_Color` | `d3d/hlsl/FillPgram_Color.hlsl` | `es2/glsl/FillPgram_Color.frag` | `shader/FillPgram_Color_Loader.java` |
  | `FillPgram_Color_AlphaTest` | `d3d/hlsl/FillPgram_Color_AlphaTest.hlsl` | `es2/glsl/FillPgram_Color_AlphaTest.frag` | `shader/FillPgram_Color_AlphaTest_Loader.java` |
  | `FillPgram_ImagePattern` | `d3d/hlsl/FillPgram_ImagePattern.hlsl` | `es2/glsl/FillPgram_ImagePattern.frag` | `shader/FillPgram_ImagePattern_Loader.java` |
  | `FillPgram_ImagePattern_AlphaTest` | `d3d/hlsl/FillPgram_ImagePattern_AlphaTest.hlsl` | `es2/glsl/FillPgram_ImagePattern_AlphaTest.frag` | `shader/FillPgram_ImagePattern_AlphaTest_Loader.java` |
  | `FillPgram_LinearGradient_PAD` | `d3d/hlsl/FillPgram_LinearGradient_PAD.hlsl` | `es2/glsl/FillPgram_LinearGradient_PAD.frag` | `shader/FillPgram_LinearGradient_PAD_Loader.java` |
  | `FillPgram_LinearGradient_PAD_AlphaTest` | `d3d/hlsl/FillPgram_LinearGradient_PAD_AlphaTest.hlsl` | `es2/glsl/FillPgram_LinearGradient_PAD_AlphaTest.frag` | `shader/FillPgram_LinearGradient_PAD_AlphaTest_Loader.java` |
  | `FillPgram_LinearGradient_REFLECT` | `d3d/hlsl/FillPgram_LinearGradient_REFLECT.hlsl` | `es2/glsl/FillPgram_LinearGradient_REFLECT.frag` | `shader/FillPgram_LinearGradient_REFLECT_Loader.java` |
  | `FillPgram_LinearGradient_REFLECT_AlphaTest` | `d3d/hlsl/FillPgram_LinearGradient_REFLECT_AlphaTest.hlsl` | `es2/glsl/FillPgram_LinearGradient_REFLECT_AlphaTest.frag` | `shader/FillPgram_LinearGradient_REFLECT_AlphaTest_Loader.java` |
  | `FillPgram_LinearGradient_REPEAT` | `d3d/hlsl/FillPgram_LinearGradient_REPEAT.hlsl` | `es2/glsl/FillPgram_LinearGradient_REPEAT.frag` | `shader/FillPgram_LinearGradient_REPEAT_Loader.java` |
  | `FillPgram_LinearGradient_REPEAT_AlphaTest` | `d3d/hlsl/FillPgram_LinearGradient_REPEAT_AlphaTest.hlsl` | `es2/glsl/FillPgram_LinearGradient_REPEAT_AlphaTest.frag` | `shader/FillPgram_LinearGradient_REPEAT_AlphaTest_Loader.java` |
  | `FillPgram_RadialGradient_PAD` | `d3d/hlsl/FillPgram_RadialGradient_PAD.hlsl` | `es2/glsl/FillPgram_RadialGradient_PAD.frag` | `shader/FillPgram_RadialGradient_PAD_Loader.java` |
  | `FillPgram_RadialGradient_PAD_AlphaTest` | `d3d/hlsl/FillPgram_RadialGradient_PAD_AlphaTest.hlsl` | `es2/glsl/FillPgram_RadialGradient_PAD_AlphaTest.frag` | `shader/FillPgram_RadialGradient_PAD_AlphaTest_Loader.java` |
  | `FillPgram_RadialGradient_REFLECT` | `d3d/hlsl/FillPgram_RadialGradient_REFLECT.hlsl` | `es2/glsl/FillPgram_RadialGradient_REFLECT.frag` | `shader/FillPgram_RadialGradient_REFLECT_Loader.java` |
  | `FillPgram_RadialGradient_REFLECT_AlphaTest` | `d3d/hlsl/FillPgram_RadialGradient_REFLECT_AlphaTest.hlsl` | `es2/glsl/FillPgram_RadialGradient_REFLECT_AlphaTest.frag` | `shader/FillPgram_RadialGradient_REFLECT_AlphaTest_Loader.java` |
  | `FillPgram_RadialGradient_REPEAT` | `d3d/hlsl/FillPgram_RadialGradient_REPEAT.hlsl` | `es2/glsl/FillPgram_RadialGradient_REPEAT.frag` | `shader/FillPgram_RadialGradient_REPEAT_Loader.java` |
  | `FillPgram_RadialGradient_REPEAT_AlphaTest` | `d3d/hlsl/FillPgram_RadialGradient_REPEAT_AlphaTest.hlsl` | `es2/glsl/FillPgram_RadialGradient_REPEAT_AlphaTest.frag` | `shader/FillPgram_RadialGradient_REPEAT_AlphaTest_Loader.java` |

  On Windows the new times fall inside the one JVM's run, and with the old pom inside the first JVM's.
- **`-force`, the mutant of the criterion.** It must make the "exactly 32" check fail, and does:
  - WSL: in a new-pom clone, `<arg value="-force"/>` added after `<arg value="com/sun/prism"/>` of the step (one line,
    with `perl -i`), the touch above and a timed build without `clean`: 1 start, with `-force`; 228 files with a new
    time, the 212 `.frag` files and the 16 `FillPgram_*` loaders; 0 md5 changes. The review's mutant, the same line
    added after `<arg line="${jfx.jsl.backends}"/>`, gave the same 228.
  - Windows: the timed build after the touched one, with `"-Djfx.jsl.backends=-d3d -force"` and no further touch.
    The property reaches both steps: both Prism blocks end in `-pkg com/sun/prism -d3d -force`, both Decora blocks in
    `-d3d -force -all GenAllDecoraShaders`. 320 files with a new time: the 212 Prism `.hlsl` files, no loader, and
    the 108 files of `jsl-decora` (50 `.hlsl`, 58 Java peers); 0 md5 changes, and the md5 list after it is
    `cmp`-identical to the baseline gate's.
  - Outside Maven on Windows, `-force` on the Prism step only, from a complete output tree: 636 files rewritten with
    `-d3d -es2 -mtl` (212 each of `.hlsl`, `.frag` and `.metal`, no loader, no header) and 212 with `-d3d`.
  - No `-force` run rewrote an up-to-date loader. `CompileJSL.compileShader` writes the loader under
    `if (JSLC.outOfDate(outFile, sourcetime))` (`src/main/jsl-prism/CompileJSL.java:420`) and never reads
    `jslcinfo.force`, which `JSLC.compile` checks for each backend output.

## Failure case
**Through Maven, in WSL clones,** one per pom:
- Edit: `sed -i '108s/;$//' modules/javafx.graphics/src/main/jsl-prism/MaskFillPgram.jsl`, so that line 108,
  `    cov = min(cov, tcc * 2.0);`, becomes `    cov = min(cov, tcc * 2.0)`.
- A timed clean build exits 1 with each pom, `javafx-graphics` failing in `jsl-codegen`. One generator JVM starts with
  each; the old one carries `-name …/MaskAlphaOne.jsl`. The generator's 23 lines, the exception and its 22 frames, are
  identical after the task's prefix. The first and the last, and Maven's error lines (the clone's directory shown as
  `<clone>`):
  ```
  old: [INFO]     [apply] Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: MaskFillPgram.jsl:109:4: in program FillPgram_Color: missing ';' at 'return'
  old: [INFO]     [apply]     at CompileJSL.main(CompileJSL.java:453)
  old: [ERROR] Failed to execute goal org.apache.maven.plugins:maven-antrun-plugin:3.1.0:run (jsl-codegen) on project javafx-graphics: An Ant BuildException has occured: apply returned: 1
  old: [ERROR] around Ant part ...<apply parallel="false" failonerror="true" dir="<clone>/modules/javafx.graphics" executable="/…/jdk27/bin/java">... @ 59:148 in <clone>/modules/javafx.graphics/target/antrun/build-main.xml
  new: [INFO]      [java] Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: MaskFillPgram.jsl:109:4: in program FillPgram_Color: missing ';' at 'return'
  new: [INFO]      [java]     at CompileJSL.main(CompileJSL.java:453)
  new: [ERROR] Failed to execute goal org.apache.maven.plugins:maven-antrun-plugin:3.1.0:run (jsl-codegen) on project javafx-graphics: An Ant BuildException has occured: Java returned: 1
  new: [ERROR] around Ant part ...<java fork="true" classname="CompileJSL" failonerror="true" dir="<clone>/modules/javafx.graphics">... @ 51:125 in <clone>/modules/javafx.graphics/target/antrun/build-main.xml
  ```
  The tab before `at` is shown as spaces. Neither log has an Ant line of its own for `apply returned: 1` or
  `Java returned: 1`.
- Files at the failure: 140 in `target/gensrc` with each pom, `jsl-decora` 108 and `jsl-prism` 32: the `.frag` files of
  `Solid_Color`, `Solid_ImagePattern`, `Solid_LinearGradient_{PAD,REFLECT,REPEAT}`,
  `Solid_RadialGradient_{PAD,REFLECT,REPEAT}` and of the 8 `Texture_*` programs of the same paints, and their 16
  loaders. The 32 are identical by name and md5 between the poms and to the clean build's files of the same names.
- Built again unfixed, without `clean`: exit 1 with each, the same message, 1 start; 0 files with a new time, 0 md5
  changes (140 before and after).
- `git checkout -- modules/javafx.graphics/src/main/jsl-prism/MaskFillPgram.jsl`, then a timed build without
  `clean`: exit 0 with each; 392 files added, none of the 140 rewritten; the md5 list (532) is `cmp`-identical to the
  clean build's, to the baseline gate's and between the clones.

**Outside Maven, on Windows** ([below](#regeneration-outside-maven-on-windows)), on copies of the inputs with the same
line 108 (`perl -pe 's/;\n\z/\n/ if $. == 108'`; `diff -r` shows only that line), from an empty output tree:
- With `-d3d -es2 -mtl` and with `-d3d`: the Decora JVM exits 0, then the Prism JVM exits 1, on the old side the first
  JVM, named after `MaskAlphaOne.jsl`, and the loop stops there. Both sides print the exception line of the Maven
  builds above without Ant's prefix, `Exception in thread "main" …ParseCancellationException: MaskFillPgram.jsl:109:4:
  in program FillPgram_Color: missing ';' at 'return'`; the 23-line outputs are identical between the sides.
- The Prism JVM writes 64 files with three backends, the 16 `Solid_*` and `Texture_*` programs above in four files
  each, and no `PrismShaderCommon.h` (`mtl-headers` holds only the Decora JVM's 2 headers: 274 files in all); and 32
  with `-d3d` (140 in all). The lists are identical between the sides by name and md5, and each file equals the clean
  run's of that path.
- Run again unfixed: the same exits and output, the snapshot unchanged.
- Restored (the file copied back, with a new time): every JVM exits 0 and prints nothing; 785 files added with three
  backends and 392 with `-d3d`, none rewritten, and the end lists equal the clean runs'. `PrismShaderCommon.h` lists
  212 shaders on both sides.

## The review's own cases and mutants
In its own WSL clones, with the timed build, `-es2`; each edit undone with `git checkout --` (the moved file put
back). Counts of files are under `target/gensrc`.

| Case | Old pom | New pom |
| --- | --- | --- |
| Clean | 532 files (108, 424), 28 starts | 532 identical by name and md5, 1 start |
| `touch` of `PaintRadialGradient.jsl` and `MaskSolid.jsl`, no `clean` | 176 files with a new time, no content change | 176, the same set |
| `;` removed at the end of `PaintTextureSecondPassLCD.jsl:37`, clean | exit 1, 318 files (210 `jsl-prism`, 108 `jsl-decora`) | exit 1, the same files and md5s |
| The same, built again unfixed | exit 1, nothing written | exit 1, nothing written |
| The same, fixed, no `clean` | 214 added, 0 rewritten, equal to the clean build | 214 added, 0 rewritten, equal to the clean build |
| `PaintAlphaTextureRadialGradient.jsl` moved out of the tree, clean | exit 1, 290 files (182, 108) | exit 1, the same files and md5s |
| The same, built again | exit 1, nothing written | exit 1, nothing written |
| The file put back, no `clean` | 242 added, 0 rewritten, equal to the clean build | 242 added, 0 rewritten, equal to the clean build |
| Clean, in a directory whose path holds a space | not run | 532, equal to the clean build |

- The 176 are 88 programs, each with its `.frag` and its loader: the 66 radial-gradient programs and the 28 that take
  `MaskSolid.jsl`'s time, 6 of them in both.
- The syntax error's message, the same with both: `PaintTextureSecondPassLCD.jsl:38:4: in program
  Solid_TextureSecondPassLCD: mismatched input 'float2' expecting {',', ';'}`. The missing file's:
  `Input file not found: PaintAlphaTextureRadialGradient.jsl` and the generator's usage text. Maven then reports
  `apply returned: 1` or `Java returned: 1`.

Its mutants of the new pom, each built in its new clone:

| Mutant | Result | What catches it |
| --- | --- | --- |
| `-name` and `${project.basedir}/src/main/jsl-prism/MaskAlphaOne.jsl` added back | the same output as the change, 176 files rewritten after the two-file touch, the same set; 1 start | only the "One step" search (`"-name"` at line 197) |
| `-mtlheaders` and its value dropped | with `-d3d -es2 -mtl`, 1,060 files: `PrismShaderCommon.h` and a second `FragmentShaderCommon.h` under `jsl-prism/mtl-headers`, and no `PrismShaderCommon.h` in `mtl-headers`; the build passes. With `-es2` or `-d3d` no Metal header is written, so nothing can differ (read from the code) | the three-backend list, which differs from `US-023-regeneration.md5`, or the macOS Metal compile |
| `fork="false"` | every build fails: `java.lang.UnsupportedOperationException: Setting a Security Manager is not supported` | any build; Ant's in-process start line also matches the start counter |
| `failonerror` dropped | the syntax-error build logs `[ERROR]      [java] Java Result: 1` and ends in BUILD SUCCESS with 210 of the 424 `jsl-prism` files | "Same failure" and "Same files" |
| `src/main/jsl-prism` dropped from the class path | after a touch of `MaskSolid.jsl` the build fails with `IllegalArgumentException: URL to group file cannot be null` (`PrismLoaderGlue.stg` not found) | the without-`clean` comparison |
| `maven.compile.classpath` moved to the end of the class path | identical: 176, the same set, the same md5s | nothing; harmless |
| `-force` added | 228 files with a new time after the touch of `MaskFillPgram.jsl` | "Same files, without `clean`" |

## Regeneration outside Maven on Windows
The pom's two generator steps started by hand, in Git Bash with `MSYS_NO_PATHCONV=1`, from the main tree's inputs and
the classes of its debug build, each side into a fresh output root `<O>`, each JVM in an empty working directory of
its own, which stayed empty. `DCP` and `DMP` are the Decora step's class path and module path and `PCP` the Prism
class path above, copied from the launcher lines of the debug log.
- Decora, both sides: `java "--module-path=$DMP" --add-modules=javafx.graphics
  --add-exports=javafx.graphics/com.sun.scenario.effect=ALL-UNNAMED
  --add-exports=javafx.graphics/com.sun.scenario.effect.light=ALL-UNNAMED
  --add-exports=javafx.graphics/com.sun.scenario.effect.impl.state=ALL-UNNAMED -classpath "$DCP" GenAllDecoraShaders
  -i <src/main/jsl-decora> -o <O>/jsl-decora -t -mtlheaders <O>/mtl-headers -pkg com/sun/scenario/effect <backends>
  -all GenAllDecoraShaders`.
- Old Prism step, once per `.jsl` file in `LC_ALL=C` order, stopping at the first exit other than 0:
  `java -cp "$PCP" CompileJSL -i <src/main/jsl-prism> -o <O>/jsl-prism -t -mtlheaders <O>/mtl-headers
  -pkg com/sun/prism <backends> -name <src/main/jsl-prism>\<file>`.
- New Prism step: `java -classpath "$PCP" CompileJSL -i <src/main/jsl-prism> -o <O>/jsl-prism -t
  -mtlheaders <O>/mtl-headers -pkg com/sun/prism <backends>`.
- The md5s of all 4,491 class-path inputs and of the 64 files of `src/main/jsl-prism` and `src/main/jsl-decora` were
  the same before and after all runs.

Results:
- **`-d3d -es2 -mtl`, clean:** old 29 JVMs, new 2, all exit 0 and print nothing; 1,059 files on each side (208, 848,
  3), identical between the sides by name and md5. On the old side the Decora JVM writes 210 files (208 and its 2
  headers), the first Prism JVM 849 (848 and `PrismShaderCommon.h`) and the other 27 nothing; the snapshots after the
  first and the 28th are identical. `PrismShaderCommon.h` has 212 structs and 212 dictionary functions on both sides,
  named as the 212 `.metal` files.
- **Against `US-022-regeneration.md5`,** made on macOS: the names are the same, and 270 md5s differ, those of the 58
  Decora and 212 Prism `.java` files, which the generators write with CRLF line ends on Windows (87 of 87 lines in
  one of them; no other file holds a CR). With the CRs removed, the list is `cmp`-identical to US-022's. Both sides
  alike.
- **`-d3d`, clean:** old 29 JVMs, new 2, all exit 0; 532 files (108, 424, no `mtl-headers`), the lists `cmp`-identical
  between the sides and to the Windows baseline gate's.
- **Without `clean`,** each side on a `cp -rp` copy of its own three-backend tree and of the inputs (names, times and
  sizes checked equal):

  | Edit | Old side | New side | Old = new |
  | --- | --- | --- | --- |
  | `touch MaskFillPgram.jsl` | 64 rewritten, all by the first JVM: the 16 `FillPgram_*` programs in four files each; no header | 64 | the same list; end md5s equal to the clean run's |
  | `touch PaintLinearGradient.jsl` | 264 rewritten, all by the first JVM: 66 programs in four files each; no header | 264 | the same |
  | `touch -d '+1 day' PaintColor.jsl`, a time in the future | each of the 28 JVMs rewrites the same 112 files, the 28 `<mask>_Color` programs in four files each | 112, once | the same |

- **`<apply parallel="true">`,** one JVM with the 28 paths after one `-name`, three backends: exit 1,
  `unrecognized argument: C:\SourceCode\jfx-ffm\modules\javafx.graphics\src\main\jsl-prism\MaskAlphaTexture.jsl` and
  the usage text, no file written.
- **`-force`** on the Prism step: see [`-force`](#same-files-without-clean) above.

## Negative controls
- **The start counter** gives 28 starts (56 blocks) on each old-pom debug log, run unchanged on Windows by the
  implementation, the end-to-end run and the confirming review, and in WSL; so a count of 1 is not the counter missing
  starts. It also reproduces the old pom's step times of the probes to the millisecond.
- **The "One step" search** on the pom at `7a66c85e39` finds `:186` (`<apply`), `:201` (`"-name"`) and `:202`
  (`<srcfile/>`), so an empty result is not a wrong path or pattern.
- **The snapshot comparison,** WSL: on a copy of `jsl-prism`, `FillPgram_Color.frag` written again with the same bytes
  and a newline appended to `FillPgram_Color_Loader.java` give a new time for 2 files and a new md5 for 1, of 424.
- **The comparisons outside Maven,** Windows: the `-d3d` list against the three-backend list, a list with one hex
  digit changed, an output with 1 byte appended, a touched output (`d3d/hlsl/Texture_Color.hlsl`), the rewritten lists
  of two different edits, a failure log against an empty one, and the CRLF difference above: each is reported as a
  difference.

## Step times
Wall times of single runs. Clean gate builds, the final gates of the story: from the newest file under
`target/gensrc/jsl-prism` (each time `Solid_TextureSecondPassLCD_AlphaTest_Loader.java`) to the creation of
`target/mods`:

| Host | Before | After |
| --- | --- | --- |
| Windows (file times, 100 ns) | 8.45 s | 0.054 s |
| WSL (file time and birth time, ns) | 4.22 s | 0.009 s |

Timed builds without `clean`, the Prism step:

| Build | Windows, old | Windows, new | WSL, old | WSL, new |
| --- | --- | --- | --- | --- |
| Nothing changed, run 1 | 9.339 s | 0.375 s | 13.804 s | 0.751 s |
| Nothing changed, run 2 | 9.455 s | 0.625 s | 10.790 s | 0.273 s |
| Nothing changed, run 3 | 8.986 s | 0.920 s | not run | not run |
| After the touch of `MaskFillPgram.jsl` | 9.167 s | 2.717 s | 7.437 s | 1.000 s |
| The `-force` mutant | not run | 4.221 s | not run | 4.015 s |
| After the failure, fixed | not run | not run | 8.619 s | 3.400 s |

- Windows, old pom: the first JVM took 0.298, 0.587 and 0.326 s in the three runs without a change and the other 27 a
  median of 0.308, 0.307 and 0.318 s each; after the touch 0.979 s and 0.307 s.
- Windows, new pom: another project's Maven test run (not this repository) was running on the machine; the builds
  took 1.4 to 2.3 times as long as the old pom's, `javafx-base`, which the change does not touch, too.
- WSL: other builds ran in WSL, at a load average of about 8 on 8 CPUs.

Outside Maven on Windows, the Prism step (the host was busy):

| Run | Old: first JVM | Old: the other 27, median each | Old: all 28 | New: the one JVM |
| --- | --- | --- | --- | --- |
| Clean, `-d3d -es2 -mtl` | 10,996 ms | 923 ms | 35,899 ms | 8,526 ms |
| Clean, `-d3d` | 6,684 ms | 657 ms | 24,756 ms | 4,027 ms |
| `touch MaskFillPgram.jsl` | 2,966 ms | 913 ms | 28,285 ms | 2,460 ms |
| `touch PaintLinearGradient.jsl` | 4,804 ms | 990 ms | 32,150 ms | 4,096 ms |
| `PaintColor.jsl` a day ahead | 2,229 ms | 2,279 ms | 65,607 ms | 2,371 ms |
