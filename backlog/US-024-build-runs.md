# US-024 — Build runs on Windows and in WSL

Evidence for [US-024](US-024-regenerate-shaders-when-the-jsl-tools-change.md): the two generator steps without
`-force` at `38911806e8` and with the change, through Maven on Windows and in WSL and outside Maven on Windows; for each
run the edit, the files with a new time or a new md5, the comparison with a clean build and the same run before the
change; the failure cases, the mutants and the step times. The lists kept with it: per class on
[Windows](US-024-windows-per-class.tsv) and in [WSL](US-024-wsl-per-class.tsv), and the `target/gensrc` modification
times on [Windows](US-024-windows-gensrc-times.tsv) and in [WSL](US-024-wsl-gensrc-times.tsv).

## Hosts and trees
- **Windows:** Windows 10 Pro (10.0.19045), Temurin 27+35, Apache Maven 3.10.0 (platform encoding UTF-8), Git Bash;
  the build generates `-d3d`. The baseline gate and its timed builds used the main tree at a clean `38911806e8`; every
  later run the main tree with the change. The production files of the change kept their bytes from the
  implementation on: `pom.xml` `2e028c2169b64b5b3ea623ed27ad8bb9`, `JSLC.java` `5b4565c8ba79176bb1c269ecaf262547`,
  the Prism `CompileJSL.java` `df3177fb5cffa78f679e52cfa1c43920`, `MetalHeadersTest.java`
  `9cf81da6795ff00c4356dbc8b4b364ca`. `ForcedRunTest.java` was `ffb79dbc625ba5f7cfc69970b06f39b1` until the rework of
  2026-11-21 and is `977f03adb719fd4c55a2083fb23c67a8` since.
- **WSL:** Ubuntu, Temurin 27+35 (`~/jdk27`), Apache Maven 3.10.0 (`~/maven310`), `DISPLAY` and `WAYLAND_DISPLAY`
  unset; the build generates `-es2`. Every run used fresh clones of the main repository detached at `38911806e8`. An
  after clone has the five files of the change copied in from the main tree, each checked with `cmp`; the clones of
  2026-11-17 and 2026-11-18 hold the first `ForcedRunTest.java`, those of 2026-11-21 the reworked one.
- **Dates.** 2026-11-17: the baseline gates and their timed builds (21:49 to 22:11), the implementation (to 22:42),
  the Windows end-to-end runs (22:56 to 23:50). From 23:01 on 2026-11-17 to 01:30 on 2026-11-18: the WSL runs of the
  acceptance criteria, the extra cases and the review, beside each other. 2026-11-21: the last criteria case (09:48 to
  10:07), the rework (09:41 to 09:54), the confirming review (09:59 to 10:12), the final gates and the cost runs (10:21
  to 11:21). Times of day are America/New_York, taken from each log's own time stamps or `Finished at` line.

## Commands and methods
Reused from [US-023-build-runs.md](US-023-build-runs.md#commands): the **timed build**
(`mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am [clean] test-compile -DskipNative=true` with
the three `MAVEN_OPTS` properties that put the antrun logger at debug and stamp every line with the time), the
**start counter**, the **snapshot** of `target/gensrc` (path, `find -printf '%T@'` time, md5) and the **md5 list**.
The Windows builds with natives are the timed build without `-DskipNative=true`.

New here:
- **Comparison of two snapshots:** the files with a new time (times compared as strings, to the last digit), with a new
  md5, added and removed, written "new time / new md5 / added / removed". "= clean" means that the md5 list is
  `cmp`-identical to that of a clean build of the same tree. The comparisons of the final runs, the criteria and the
  extra cases were checked on a snapshot against itself (0 / 0 / 0 / 0) and on a planted copy with one time and one
  md5 changed, one line removed and one added (1 / 1 / 1 / 1; the extra cases' copy had two times changed, 2 / 1 / 1 /
  1); the implementation's on a copy with one time and one md5 changed (1 / 1). The Windows end-to-end runs and the
  review recorded no such check.
- **Kinds of generated file:** `.hlsl`, `.frag`, `.metal`, loader (`*_Loader.java` under `jsl-prism`), JSW peer
  (`JSW*Peer.java`), PPS peer (`PPS*Peer.java`) and Metal header. A complete `-es2` or `-d3d` tree has 532 files: 262
  `.frag` or `.hlsl` (50 Decora, 212 Prism), 212 loaders, 28 JSW and 30 PPS peers. With `-d3d -es2 -mtl` it has 1,059:
  262 each of `.hlsl`, `.frag` and `.metal`, the 212 loaders, the 58 peers and 3 headers.
- **Step time,** in a timed build: from the time stamp of the step's `[java] Executing` line to the first time-stamped
  line of the next task, for the Decora step `fileset: Setup scanner in dir …jsl-prism with`, for the Prism step the
  `[mkdir]` line of `target/mods`. US-023's Prism step time starts at the `PrismLoaderGlue.stg skipped` line before
  it and gives 3 to 9 ms more on the baseline's builds (297, 491 and 482 against 294, 482 and 475 ms). The parsers of
  the final runs and of the Windows end-to-end runs were checked on the baseline logs and gave their numbers exactly.
- **Forced and unforced runs outside Maven, same session** (Windows, 2026-11-21): the two generator command lines
  copied byte for byte from the `Execute:Java13CommandLauncher` blocks of the first timed build after the final gate;
  `-o` and `-mtlheaders` pointed into a scratch root; `-force` dropped for an unforced run; each JVM started by perl's
  `system(LIST)` without a shell, from `modules/javafx.graphics` (the pom's `dir="${project.basedir}"`), and timed with
  `Time::HiRes`. One forced run of each generator into an empty root, then 8 pairs on that complete tree, each pair in
  the order Decora unforced, Decora forced, Prism unforced, Prism forced; the first pair is a warm-up.
- **The "before" variant outside Maven** (Windows, 2026-11-17): the baseline's command lines, without `-force`, with
  `38911806e8`'s `JSLC.java` and Prism `CompileJSL.java` (`git show`) compiled into scratch directories put first on the
  class path (`javac -d <dir> --release 27 -encoding UTF-8 -g -implicit:none`). `javap` showed the scratch `JSLC`
  without `writeOutput` and the scratch `CompileJSL` reading no `force`; `-Xlog:class+load` showed both loaded from the
  scratch directories. The "after" variant: the main tree's classes with the change's command lines. All with
  `MSYS_NO_PATHCONV=1`, from an empty working directory that stayed empty.
- **Edits** in clones or scratch copies with `sed -i` or `perl -i -pe '… if $. == N'`; in the extra cases each undone
  with `git checkout --` and the source time put back with `touch -d '<the time before>'`; copies of trees with
  `cp -rp`, checked equal by name, time and md5.
- **`fxc` runs:** the `Generating …shader-obj/….obj` lines of a log; **`.obj` copies:** its
  `[copy] Copying …shader-obj….obj to` lines.

## Baseline
`38911806e8`, 2026-11-17. The gates: see the story's
[Final gates](US-024-regenerate-shaders-when-the-jsl-tools-change.md#final-gates-2026-11-21). Then three timed builds
without `clean` on each host; each gave 0 / 0 / 0 / 0 against the snapshot before it, and the four snapshots of each
host are identical.

| Build | Windows: exit, Decora, Prism, Maven total | WSL: exit, Decora, Prism, Maven total |
| --- | --- | --- |
| 1 | 0, 582 ms, 294 ms, 03:11 min | 0, 0.389 s, 0.193 s, 02:49 min |
| 2 | 0, 896 ms, 482 ms, 03:37 min | 0, 0.436 s, 0.249 s, 02:50 min |
| 3 | 0, 935 ms, 475 ms, 03:40 min | 0, 0.360 s, 0.263 s, 02:08 min |

- Every log has 1 Decora and 1 Prism start (2 blocks each) and no `-force`. The logged command lines of the after
  builds differ from these by one `'-force'` line in each step.
- Load: on Windows no other `java` or `mvn` process at the start of the gate; in WSL another project's build ended
  before the gate started; neither host was checked during the timed builds.

## Nothing changed
Builds without `clean` and without a change, each compared with the snapshot before it:

| Date | Host | Tree | Builds | Result |
| --- | --- | --- | --- | --- |
| 2026-11-17 | Windows | after, the implementation's builds after its clean build | 2 | 0 / 0 / 0 / 0 each |
| 2026-11-17 | Windows | after, with natives ([below](#the-windows-builds-with-natives)) | 2 | 0 / 0 / 0 / 0 each; no `.obj` with a new time |
| 2026-11-17 and 2026-11-18 | WSL | after, the criteria clone: 3 builds, and a fourth whose edit diff was empty | 4 | 0 / 0 / 0 / 0 each; each md5 list `cmp`-identical to `US-023-wsl-gensrc.md5` |
| 2026-11-17 | WSL | before, the criteria clone | 1 | 0 / 0 / 0 / 0 |
| 2026-11-17 | WSL | after, the review's clone | 2 | 0 new times, 0 new md5s, the same 532 names |
| 2026-11-18 | WSL | after, the extra clone with `-Djfx.jsl.backends="-d3d -es2 -mtl"` | 2 | 0 / 0 / 0 / 0 each, the header times unchanged |
| 2026-11-21 | Windows | after, the main tree after the final gate | 5 | 0 / 0 / 0 / 0 each; `snap1` to `snap5` `cmp`-identical to `snap0`, which is kept as [the Windows list](US-024-windows-gensrc-times.tsv) |
| 2026-11-21 | WSL | before (a clone of `38911806e8`) and after (the final gate's clone), alternating | 4 and 4 | 0 / 0 / 0 / 0 each; the after clone's snapshots `cmp`-identical to the one before its first build, kept as [the WSL list](US-024-wsl-gensrc-times.tsv) |
| 2026-11-17 | Windows and WSL | before, the baseline | 3 and 3 | 0 / 0 / 0 / 0 each |

In all: 21 builds after the change and 11 before, none writing a file.

## The Windows builds with natives
2026-11-17, 22:56 to 23:28, in the main tree with the change, one after the other, the timed build without
`-DskipNative=true`. `target/native/shader-obj` holds 262 `.obj` files (212 Prism, 50 Decora). Each log shows both
steps with `-force`. Times are the step times in ms, CMake's in s.

| Build | Case | Exit | `target/gensrc` | `.obj` with a new time | `fxc` runs | `.obj` copied | Decora | Prism | CMake | Maven total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | after the implementation's clean build, which left an empty `shader-obj` | 0 | 0 / 0 / 0 / 0 | 262 | 262 | 262 | 2,152 | 2,793 | 115.0 | 07:01 |
| 2 | nothing changed | 0 | 0 / 0 / 0 / 0 | 0 | 0 | 0 | 3,606 | 5,757 | 8.2 | 05:09 |
| 3 | nothing changed | 0 | 0 / 0 / 0 / 0 | 0 | 0 | 0 | 3,556 | 3,980 | 6.7 | 04:58 |
| 4 | `touch src/main/jsl-prism/MaskFillPgram.jsl` (time 15:04:55.695 to 23:17:11.751, md5 `51c98460…` unchanged) | 0 | 0 / 0 / 0 / 0 | 0 | 0 | 0 | 4,300 | 4,756 | 6.7 | 05:11 |
| 5 | positive control: `touch` of `target/gensrc/jsl-prism/com/sun/prism/d3d/hlsl/FillPgram_Color.hlsl` | 0 | the touch 1 new time; the build 0 / 0 / 0 / 0 | 1, `FillPgram_Color.obj`, md5 unchanged | 1 | 1 | 4,407 | 4,021 | 9.4 | 05:28 |

- After build 5 the touched `.hlsl` still had its touched time, 23:23:01.469173900: the forced generator did not
  rewrite it, and `fxc` and `copy-shaders` acted on exactly that file.
- At the end `target/gensrc` held 532 files, its md5 list `cmp`-identical to `US-023-windows-gensrc.md5`.
- `fxc` at the baseline was not measured.
- Load: builds of this story ran in WSL the whole time, two or three Maven JVMs, at a load average of 11.1 to 12.6 on
  8 CPUs from 23:11 on, as the run's report gives them (the samples were not saved); build 1's load was not sampled.

## A compiler or template change
**Through Maven in WSL clones** (`-es2`), 2026-11-17 23:38 to 2026-11-18 00:49: a clean build, the edit, a build
without `clean`, then the edit undone and a build without `clean`; the same in a before clone where marked. Each
"after = clean" compares with a clean build of the edited tree in the same clone, and the before and after clean
builds of the edited tree are `cmp`-identical.
- `src/jslc/resources/com/sun/scenario/effect/compiler/backend/prism/PrismGlue.stg:32`:
  ` * This file was generated by JSLC -- DO NOT EDIT MANUALLY!` to
  ` * This file was generated by JSLC (template probe) -- DO NOT EDIT MANUALLY!`.
- `src/jslc/resources/com/sun/scenario/effect/compiler/backend/sw/java/JSWGlue.stg:33`: the same text change.
- `src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/ES2Backend.java:119`: `sb.append("#endif\n");` to
  `sb.append("#endif\n\n");`.

| Edit | After: edit, no `clean` | After = clean | After: undone, no `clean` | Before: edit, no `clean` | Before against its clean build |
| --- | --- | --- | --- | --- | --- |
| `PrismGlue.stg:32` | 30 new times and 30 new md5s, the 30 PPS peers | yes | the 30 again, md5 list = `US-023-wsl-gensrc.md5` | 0 / 0 / 0 / 0 | 30 differ, the PPS peers |
| `JSWGlue.stg:33` | 28, the 28 JSW peers | yes | the 28 again, = `US-023-wsl-gensrc.md5` | not run | – |
| `ES2Backend.java:119` | 262, every `.frag` (50 Decora, 212 Prism) | yes | the 262 again, = `US-023-wsl-gensrc.md5` | 0 / 0 / 0 / 0 | 262 differ |

In each after run the files with a new time are those with a new md5.

**Outside Maven on Windows,** 2026-11-17, three backends, on a `cp -rp` copy of a complete clean tree
([setup](#outside-maven-on-windows)):

| Edit | After | After = clean | Before | Before against the clean run |
| --- | --- | --- | --- | --- |
| `PrismGlue.stg:32`, `… generated by JSLC from a changed template …`, in a scratch copy of `src/jslc/resources` put first on the class path | 30 new times and md5s, all PPS peers | yes | 0 | 30 of 1,059 differ |
| `src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/HLSLBackend.java:118`, `"out float4 color : COLOR0"` to `"out float4 color  : COLOR0"`, compiled into scratch classes put first | 262, every `.hlsl` (50 Decora, 212 Prism) | yes; the clean run differs from the unedited tree in 262 | 0 | 262 differ |

The marker text of the template edit is in 30 files of both clean runs and in no file of the unedited trees, so the
scratch template was the one read.

**The review's edit,** in its own WSL clones: [below](#the-reviews-own-runs-and-mutants).

## The generator's alpha test
`src/main/jsl-prism/CompileJSL.java:395`, `mainTemplate += "    if (color.a == 0.0) discard;\n";` with `== 0.0`
changed to `<= 0.0`.

| Run | Backends | After: rewritten | After = clean build of the changed generator | Before: rewritten | Before against its clean build |
| --- | --- | --- | --- | --- | --- |
| Through Maven, WSL clones, 2026-11-18 00:49 to 01:20 | `-es2` | 106 new times and md5s, all `.frag`, all `_AlphaTest` | yes | 0 | 106 differ, the before and after clean builds identical |
| Through Maven, WSL after clone | `-d3d -es2 -mtl` | 318: 106 each of `.frag`, `.hlsl` and `.metal`; no header | yes, the 3 headers identical too | not run | – |
| Outside Maven, Windows, 2026-11-17 | `-d3d -es2 -mtl` | 318, the same kinds; `PrismShaderCommon.h` kept its time and its md5 `2d8c5c140fe22da6baac33e38d53abeb` | yes; that clean run differs from the unchanged tree in 318 | 0 | 318 differ |
| Outside Maven, Windows, the implementation, 2026-11-17 | `-d3d` | 106, all `_AlphaTest.hlsl` | yes; that clean run differs from the unchanged tree in 106 | not run | – |

The three-backend clean build in the WSL after clone, before the edit, was `cmp`-identical to
`US-023-regeneration.md5`. Outside Maven the changed generator was a scratch copy compiled into scratch classes put
first on the class path.

## The loader template
`src/main/jsl-prism/PrismLoaderGlue.stg:30`.

| Run | Edit | After | After = clean | Before |
| --- | --- | --- | --- | --- |
| Outside Maven, Windows, the implementation, 2026-11-17, `-d3d`, on a copy of a complete forced tree, with a `cp -rp` copy of `src/main/jsl-prism` as `-i` and as the last class-path element; the 28 `.jsl` times older than every output | `generated by JSLC --` to `generated by JSLC from a changed template --` (the scratch copy's line 30) | 212 new times and md5s, all loaders, no other file | yes: equal to a forced run of the same inputs into an empty directory, which differs from the unchanged tree in 212 | the `38911806e8` `CompileJSL.java` compiled first on the class path: 0, and the tree differs from that clean run in 212 loaders |
| Through Maven, WSL extra clones, 2026-11-17, `-es2` | `generated by JSLC --` to `generated by JSLC from PrismLoaderGlue.stg --` | 212 loaders | yes | 0; 212 differ |
| The same, edit undone | – | the 212 again | = the clean build before the edit | not run |
| Through Maven, WSL extra after clone, 2026-11-18, `-d3d -es2 -mtl` | the same | 212 loaders; no `.hlsl`, `.frag`, `.metal`, header or peer | = a clean three-backend build of the edited tree | not run |

The 212 loaders of the three-backend run are md5-identical to those of the one-backend clean build of the edited tree.

## Restored `.jsl` files
**Through Maven in WSL clones,** `-es2`. After clone 2026-11-18 01:22 to 01:30; its clean build of the restored tree,
cut off at 01:30:37 that day, repeated on 2026-11-21 09:48 to 09:53; before clone 2026-11-21 09:55 to 10:07.
- Edits: `src/main/jsl-decora/ColorAdjust.jsl:144`, `src.rgb = ((src.rgb - 0.5) * contrast) + 0.5;` to
  `… + 0.25;`, and `src/main/jsl-prism/MaskFillPgram.jsl:108`, `cov = min(cov, tcc * 2.0);` to `… * 3.0;`. Both files
  were saved with `cp -p` first and put back with `cp -p`; in the before clone the same diff was applied with
  `git apply --unidiff-zero`, and its `git diff -U0` is `cmp`-identical to the after clone's.
- The 18 files: `jsl-decora/…/es2/glsl/ColorAdjust.frag`, `jsl-decora/…/sw/java/JSWColorAdjustPeer.java`, and the 16
  `jsl-prism/com/sun/prism/es2/glsl/FillPgram_{Color,ImagePattern,LinearGradient_{PAD,REFLECT,REPEAT},
  RadialGradient_{PAD,REFLECT,REPEAT}}[_AlphaTest].frag`.

| Step | After | Before |
| --- | --- | --- |
| Edited, no `clean` | 18 new times and md5s | 35 new times (the 18, `PPSColorAdjustPeer.java` and the 16 `FillPgram_*` loaders, rewritten with the same bytes), 18 new md5s; the md5 list `cmp`-identical to the after clone's |
| Restored with `cp -p`, no `clean` | the same 18 again; the md5 list `cmp`-identical to `US-023-wsl-gensrc.md5` and to the clean build of the restored tree | 0 / 0 / 0 / 0; the md5 list differs from the clean build's and the kept list in the 18 |
| Clean build afterwards | = `US-023-wsl-gensrc.md5` | = `US-023-wsl-gensrc.md5` |

The two `.jsl` files had their old times after the restore in both clones: `MaskFillPgram.jsl` and `ColorAdjust.jsl`
at 2026-11-17 22:58:16.592826358 and 22:58:16.586108758 in the after clone, at 22:56:50.635235677 and
22:56:50.633218777 in the before clone.

**Outside Maven on Windows,** 2026-11-17, three backends, the Decora generator only, on a copy of a complete clean
tree: `src/main/jsl-decora/ColorAdjust.jsl:139`, `src.a > 0.0` to `src.a > 0.001`, in a `cp -rp` copy of
`src/main/jsl-decora`, then the file put back with `cp -p` from the main tree.

| Step | After | Before |
| --- | --- | --- |
| Edited | 4 new times and md5s: `ColorAdjust.hlsl`, `.frag`, `.metal` and `JSWColorAdjustPeer.java`; `PPSColorAdjustPeer.java` has unchanged bytes and was not written | 5 new times (the 4 and `PPSColorAdjustPeer.java` with the same bytes), 4 new md5s |
| Restored with `cp -p` | the same 4 again; the end tree equals the clean run's by name and md5 | 0 / 0 / 0 / 0; the end tree differs from the clean run's in the 4 |

## A touch, truncated outputs and a source time in the future
Through Maven in WSL extra clones (`-es2`), 2026-11-17 23:49 to 2026-11-18 00:48. In these cases "= clean" for the
after clone compares with that clone's clean build of the same sources at the start of the series, not with a new
clean build; the clone held only the five files of the change (`git status`, md5 list checked).

| Case | After | Before |
| --- | --- | --- |
| `touch src/main/jsl-prism/MaskFillPgram.jsl` (md5 `51c98460b44aea5b156f2b7a7cb851d6` unchanged) | 0 / 0 / 0 / 0, = clean | 32 new times (16 `FillPgram_*` `.frag`, 16 loaders), 0 new md5s |
| `truncate -s 0`, then `touch`, of `jsl-prism/com/sun/prism/es2/glsl/Solid_Color.frag` and `jsl-decora/com/sun/scenario/effect/impl/prism/ps/PPSBlend_ADDPeer.java` under `target/gensrc`, newer than every source | 2 new times and md5s, the two files, = clean | 0 / 0 / 0 / 0, both stay 0 bytes, exit 0 |
| `touch -d '+1 day' src/main/jsl-prism/PaintColor.jsl`, two builds | 0 / 0 / 0 / 0 in both, = clean | 56 new times in each build, the same 56 (28 `.frag`, 28 loaders), 0 new md5s |

- The truncated case before the change, BUILD SUCCESS: the second-pass `javac` compiles the empty
  `PPSBlend_ADDPeer.java` without an error, and the class of an earlier build stays,
  `target/mods/…/PPSBlend_ADDPeer.class` and `target/classes/…` at 2,252 bytes from 00:12:42 and 00:12:45 (as the
  run's report gives them), with `[copy] …PPSBlend_ADDPeer.class omitted as … is up to date` in the log; the empty
  `Solid_Color.frag` is copied into `target/classes` and `target/shims/javafx.graphics`, 0 bytes in both. In the
  after clone only `target/gensrc` was checked.
- On Windows the same touch, with natives: [build 4](#the-windows-builds-with-natives).

## An error in a source with an old time
Through Maven in WSL extra clones (`-es2`), 2026-11-17 23:59 to 2026-11-18 00:23.
- Edit: `sed -i '108s/;$//' src/main/jsl-prism/MaskFillPgram.jsl`, `    cov = min(cov, tcc * 2.0);` to
  `    cov = min(cov, tcc * 2.0)`. For the old time, then `touch -d '2001-01-01 00:00:00'` (`stat` shows
  `2000-12-31 19:00:00 -0500`), older than every output.

| Case | After | Before |
| --- | --- | --- |
| The error and the old time, no `clean` | exit 1; 0 / 0 / 0 / 0 | exit 0; 0 / 0 / 0 / 0: the broken source passes |
| The error with the edit's own time, no `clean`, twice | exit 1 both times; 0 / 0 / 0 / 0 | not run |
| The same, file restored, no `clean` | exit 0; 0 / 0 / 0 / 0, = clean | not run |
| The error, clean build | exit 1 after 140 files (108 `jsl-decora`; 32 `jsl-prism`, the `.frag` and loader of the 16 `Solid_*` and `Texture_*` programs), each md5-identical to the file of that name of a clean build | not run |
| The same, file restored, no `clean` | exit 0; 392 added (196 `.frag`, 196 loaders), none rewritten, = clean | not run |

The message, the same in the four failing after builds (the clone's directory shown as `<clone>`, the tab before
`at` as spaces):
```
[INFO]      [java] Exception in thread "main" org.antlr.v4.runtime.misc.ParseCancellationException: MaskFillPgram.jsl:109:4: in program FillPgram_Color: missing ';' at 'return'
[INFO]      [java]     at CompileJSL.main(CompileJSL.java:453)
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-antrun-plugin:3.1.0:run (jsl-codegen) on project javafx-graphics: An Ant BuildException has occured: Java returned: 1
[ERROR] around Ant part ...<java fork="true" classname="CompileJSL" failonerror="true" dir="<clone>/modules/javafx.graphics">... @ 52:133 in <clone>/modules/javafx.graphics/target/antrun/build-main.xml
```
US-023 recorded `@ 51:125`; line 52 of that `build-main.xml` is the `<java fork="true" classname="CompileJSL"` element.
The exception line and the frame `CompileJSL.java:453` are US-023's. The clean-build case is US-023's "Same failure"
case, unchanged.

## Outside Maven on Windows
2026-11-17, in the main tree with the change: the implementation's runs (to 22:42), and the end-to-end runs after the
builds with natives (to 23:50). The two generators, as the logged command lines start them:
```
java "--module-path=$DMP" --add-modules=javafx.graphics <the three --add-exports> -classpath "<prefix>$DCP"
    GenAllDecoraShaders -i <jsl-decora> -o <O>/jsl-decora -t -mtlheaders <O>/mtl-headers
    -pkg com/sun/scenario/effect [-force] <backends> -all GenAllDecoraShaders
java -classpath "<prefix>$PCP" CompileJSL -i <jsl-prism> -o <O>/jsl-prism -t -mtlheaders <O>/mtl-headers
    -pkg com/sun/prism [-force] <backends>
```
`DMP`, `DCP` and `PCP` are the module path and the class paths of the logged command lines; `<prefix>` is empty or the
scratch classes of a case. Every run exited 0 and printed nothing, and the md5s of the 4,817 class-path and input
files were the same before and after.

| Case | After | Before |
| --- | --- | --- |
| Clean, `-d3d` | 532 files (108, 424), = `US-023-windows-gensrc.md5` | the same |
| Clean, `-d3d -es2 -mtl` | 1,059 (208, 848, 3), `cmp`-identical to the before run's | 1,059 |

Against `US-023-regeneration.md5` both three-backend lists differ in 270 md5s, all `.java` (58 Decora, 212 Prism):
every line of those files ends in CRLF, no other file holds a CR, and with the CRs removed the lists are
`cmp`-identical to it, as US-023 recorded. The other cases are in the sections above:
[templates and classes](#a-compiler-or-template-change), [the alpha test](#the-generators-alpha-test),
[the loader](#the-loader-template) and [a restored file](#restored-jsl-files).

## The review's own runs and mutants
WSL clones of its own, `-es2`, the timed build; each build after `free -g` (at least 7 GB available) while two other
agents' builds of this story ran in WSL. All exit 0.

In the order run, 2026-11-17 23:07 to 2026-11-18 00:21:

| Build | Tree | What | Result | Maven total |
| --- | --- | --- | --- | --- |
| 1 | after | clean | 532 files, = `US-023-wsl-gensrc.md5` | 06:38 |
| 2, 3 | after | no `clean`, nothing changed | 0 new times, 0 new md5s, the same names, each | 06:21, 05:54 |
| 4 | after | no `clean`, after the two edits below | 368 new times = 368 new md5s: `jsl-decora` 2 (`PhongLighting_POINT.frag`, `PhongLighting_SPOT.frag`), `jsl-prism` 366 (154 `.frag`, 212 loaders) | 04:24 |
| 5 | after | clean, edited | build 4 = build 5 by name and md5 | 05:00 |
| 6 | before | clean | = build 1 | 05:02 |
| 7 | before | no `clean`, the same edits | 0 new times, 0 new md5s | 04:25 |
| 8 | before | clean, edited | build 7 differs from it in 368 files; it equals build 5 | 04:39 |
| 9 | after, the pom without the Prism step's `-force` | no `clean`, the edits undone | 366 `jsl-prism` files keep the edited build's content and differ from build 1 | 04:59 |
| 10 | after, the pom without the Decora step's `-force` | no `clean`, the `ES2Backend.java` edit again | `PhongLighting_POINT.frag` and `PhongLighting_SPOT.frag` keep build 1's md5, not build 5's; the forced Prism step rewrote the 212 loaders that build 9 left stale | 05:16 |

- The edits: `src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/ES2Backend.java`,
  `sb.append("uniform vec4 jsl_pixCoordOffset;\n");` to `sb.append("uniform highp vec4 jsl_pixCoordOffset;\n");`, and
  `src/main/jsl-prism/PrismLoaderGlue.stg:30`, `DO NOT EDIT MANUALLY!` to `DO NOT EDIT BY HAND!`.
- Its step times: [below](#step-times-and-cost).

Its unit-test mutants, one line each in `JSLC.java`, on the full `buildtools/jslc` module with its own 23 tests added
(458). The tests of `ForcedRunTest` are named here by what they check: the rewrite test, the keeps-the-time test,
the missing-output test, the failure test, the up-to-date and the out-of-date test without `force`, and, after the
rework, the charset test.

| Mutant | Result | Killed by |
| --- | --- | --- |
| `jslcinfo.force \|\|` removed from one of the five decisions (`:231`, `:241`, `:254`, `:273`, `:283`), each in turn | killed, 7 / 7 / 6 / 7 / 7 failures | the rewrite and the failure tests, and its own |
| the forced write made unconditional at one of the five sites (`:235`, `:245`, `:261`, `:277`, `:287`), each in turn | killed, 3 / 3 / 3 / 4 / 3 | the keeps-the-time test and the reworked `MetalHeadersTest` test, and its own |
| `getBytes(ISO_8859_1)` in the comparison (`:325`) | passes every test of the change | only its own non-ASCII glue-block test: optional finding 2 |
| `getBytes(UTF_16)` | killed, 9 | the keeps-the-time test ×5, `MetalHeadersTest` ×2, its own |
| the comparison by byte length, and by string length (`:326`) | pass `ForcedRunTest` | only `MetalHeadersTest.upToDateMetalShadersWithUniformsThatAnEarlierCompilerRenamedGetTheOutputsOfACleanRun` and its own same-length inputs: optional finding 1 |
| `!outFile.isFile() \|\|` dropped | killed, 5 errors and 5 failures | the missing-output test, and its own |
| `writeOutput` always compares | killed, 7 | the out-of-date test without `force` ×5, `MetalHeadersTest.aHeaderWhoseContentIsUnchangedKeepsItsTimeStamp`, `SourceFileClosedTest` |
| `writeOutput` always writes | killed, 8 | the keeps-the-time test ×5, the reworked `MetalHeadersTest` test, its own |
| `38911806e8`'s `JSLC.java` with the new tests | 8 failures | the same as the row above |

Negative controls, `-Dtest=ForcedRunTest`: the forced call given a different syntax error, and the error program made
valid; each fails 5 of 5, as it must.

## The unit tests' mutants and negative controls
`mvn -B -ntp -l <log> -pl buildtools/jslc test`, one mutant at a time on the module's full suite, the file restored and
its md5 checked after each. The tests are named as above.

**The implementation,** Windows, 2026-11-17 22:18 to 22:27: 435 / 0 / 0 / 3 before and after the round (405 and the
first `ForcedRunTest`'s 30), 15 mutants, all killed:

| Mutant, one line of `JSLC.java` | Fails |
| --- | --- |
| `write(…)` for `writeOutput`, at one of `:235`, `:245`, `:261`, `:277`, `:287` in turn | the keeps-the-time test of that type and the reworked `MetalHeadersTest` test (`expected: <2001-09-09T01:46:40Z> but was: <2026-11-18T03:19:06.4170364Z>`) |
| `if (!outFile.exists()) writeOutput(…)`, at one of the same five in turn | the rewrite test and the out-of-date test without `force` of that type (`array lengths differ, expected: <327> but was: <329>` for D3D); for D3D also `SourceFileClosedTest`, for Metal two `MetalHeadersTest` tests |
| `jslcinfo.force \|\|` removed at `:231`, and at `:254` | the failure test and the rewrite test of D3D, and of Metal (`Expected …ParseCancellationException to be thrown, but nothing was thrown.`) |
| a missing file treated as unchanged (`outFile.isFile() && …`, `:326`) | the missing-output test ×5, errors (`NoSuchFile`) |
| the missing-file check removed (`:326` as at `38911806e8`) | the same, errors |
| `writeOutput` always compares (`:305`, `if (true)`) | the out-of-date test without `force` ×5, `MetalHeadersTest.aHeaderWhoseContentIsUnchangedKeepsItsTimeStamp`, `SourceFileClosedTest` |

**The rework,** Windows, 2026-11-21 09:41 to 09:54: 435 / 0 / 0 / 3 before it, 437 / 0 / 0 / 3 after it and after the
last mutant. Probes first, with `jshell` on the jslc classes: the default charset UTF-8; the Java peer 6,574 bytes and
6,573 characters and the Prism peer 2,855 and 2,854 with the glue block, one `C3 A9` pair each; the three shaders no
byte at or above `0x80`. Output lengths of the renames, old against new:

| Rename | D3D | ES2 | Metal | Java peer | Prism peer |
| --- | --- | --- | --- | --- | --- |
| `offset` to `shift` | 329 / 327 | 463 / 461 | 662 / 651 | 6,556 / 6,548 | 2,837 / 2,831 |
| `offset` to `origin` | 329 / 329 | 463 / 463 | 662 / 653 | 6,556 / 6,556 | 2,837 / 2,837 |
| `baseImg` to `origImg` | the same lengths for all five, all bytes different | | | | |

| Mutant | Failures | Killed by `ForcedRunTest` | Also |
| --- | --- | --- | --- |
| the comparison by size, `Files.size(outFile.toPath()) != bytes.length` (`:326`) | 6 | the rewrite test ×5 (`:103 array contents differ at index [10], expected: <111> but was: <98>`, `o` of `origImg` against `b` of `baseImg`) | the older `MetalHeadersTest` rename test |
| the comparison by string length, `Files.readString(…, Charset.defaultCharset()).length() != str.length()` | 6 | the same | the same |
| `getBytes(StandardCharsets.ISO_8859_1)` (`:325`) | 2 | the charset test ×2 (`:130 expected: <2001-09-09T01:46:40Z> but was: <2026-11-21T14:50:37.1336814Z>`) | none |
| `jslcinfo.force \|\|` removed at `:231` | 2 | the rewrite and the failure test of D3D | none |
| `write(hlslBackend.getShader(), outFile)` at `:235` | 2 | the keeps-the-time test of D3D | the reworked `MetalHeadersTest` test |

Negative controls, `-Dtest=ForcedRunTest`, `JSLC.java` unmutated unless said: with the accented letter replaced by
`cafe` and the precondition kept, the charset test fails 2 of 2 on its precondition (`the glue block's accented letter
did not reach the output as more than one byte`); with `cafe`, the precondition removed and the `ISO_8859_1` mutant
applied, 32 / 0 / 0 / 0, so the kill rests on the letter.

**The confirming review,** WSL, 2026-11-21 09:59 to 10:12, in a clone with the five final files: 437 / 0 / 0 / 0,
`ForcedRunTest` 32 / 0 / 0 / 0, `MetalHeadersTest` 33 / 0 / 0 / 0. 17 mutants, all killed: the two length comparisons
(6 failures each, by the rewrite test and the older `MetalHeadersTest` test), `ISO_8859_1` (2, the charset test only),
`UTF_16` (9), `force ||` removed at one of the five decisions in turn (2 each, the rewrite and the failure test of that
type), the unconditional write at one of the five sites in turn (2, 2, 2, 3 and 3: the keeps-the-time test, the
reworked `MetalHeadersTest` test and, for the two peers, the charset test), the missing-file check dropped (5 errors),
`writeOutput` always comparing (7) and always writing (8). Negative controls: the rework's two, and the old,
length-changing rename with the length assertion (`:211`) removed and the size mutant applied: 32 / 0 / 0 / 0, so that
mutant's kill rests on the equal lengths. Run with `-Djava.io.tmpdir=<scratch>` and
`-Djunit.jupiter.tempdir.cleanup.mode.default=NEVER`, the 32 test directories were kept: in the five of the up-to-date
test without `force`, the original output and the clean output of the renamed program have equal sizes and different
md5s (D3D 329, ES2 463, Metal 662, Java peer 6,379, Prism peer 2,755 bytes), and the two charset directories hold
`C3 A9` with the aged time.

## Step times and cost
**Baseline,** 2026-11-17: [above](#baseline).

**The implementation,** Windows, 2026-11-17, `-DskipNative=true`, no other `java` process (`tasklist`):

| Build | Decora | Prism | Maven total |
| --- | --- | --- | --- |
| clean | 1,634 ms | 2,572 ms (to `[mkdir] Created dir`) | 03:37 |
| no `clean`, 1 | 1,545 ms | 2,190 ms | 02:28 |
| no `clean`, 2 | 1,479 ms | 2,258 ms | 02:32 |

**The Windows builds with natives,** 2026-11-17, under load: [above](#the-windows-builds-with-natives). Outside Maven
the same evening, interleaved on complete `-d3d` trees, nothing rewritten, WSL load 12.1 to 12.6 (as the run's report
gives it), `date +%s%N` around each JVM:

| Run | After, Decora (`-force`) | Before, Decora | After, Prism (`-force`) | Before, Prism |
| --- | --- | --- | --- | --- |
| 1 | 3,970 ms | 1,327 ms | 5,107 ms | 735 ms |
| 2 | 3,302 ms | 1,263 ms | 5,088 ms | 628 ms |
| 3 | 4,509 ms | 1,442 ms | 5,771 ms | 652 ms |

**WSL, 2026-11-17 and 2026-11-18,** while the review's, the criteria's and the extra cases' builds ran beside each
other (load average about 6 to 12), not a cost record. The criteria clone's builds without changes:

| Build | Decora | Prism | Maven total | Load, from the notes before the build |
| --- | --- | --- | --- | --- |
| after, 1 | 6.271 s | 7.562 s | 06:39 | builds in two other clones |
| after, 2 | 3.919 s | 8.265 s | 05:49 | 9.22 |
| after, 3 | 2.880 s | 3.799 s | 04:37 | 8.61, 12.02 at the end |
| after, 4 (empty edit) | 1.801 s | 3.231 s | 03:22 | 5.93 |
| before, 1 | 1.976 s | 0.759 s | 05:00 | 9.74, 11.85 at the end |

The review's builds, Decora (to the Prism generator's `javac` line) and Prism step, by its build numbers above: the
before clone's build 7, which wrote nothing, 0.56 and 0.25 s; the after clone's builds without changes, 3.89 and
5.11 s, 5.63 and 5.23 s; the clean builds 1, 5, 6 and 8, 3.07 and 4.14 s, 2.01 and 3.10 s, 2.76 and 4.63 s, 1.29 and
1.69 s. The extra clones' Prism step, by US-023's method, in the builds of their cases: after 6.047, 3.806, 5.080,
5.594, 3.223, 2.412, 3.153, 3.592, 1.943 and 2.772 s; before 0.665, 1.574, 0.389, 0.350, 1.261 and 1.184 s.

The criteria clones' builds of the restored `.jsl` files, Decora and Prism step: after, the restored tree, 18 files
written, 1.549 and 1.687 s, 03:02 (2026-11-18); after, clean, 1.691 and 3.241 s, 04:53 (2026-11-21); before, the
restored tree, nothing written, 0.328 and 0.164 s, 02:07 (2026-11-21, while the confirming review's builds ran).

**Windows, the final timed builds,** 2026-11-21 10:33 to 10:50, in the main tree after the final gate, no other `java`
process before each (16,416 to 16,650 MB free):

| Build | Decora | Prism | Maven total |
| --- | --- | --- | --- |
| 1 | 1,982 ms | 2,669 ms | 03:17 |
| 2 | 2,003 ms | 2,674 ms | 03:12 |
| 3 | 1,458 ms | 2,199 ms | 02:37 |
| 4 | 1,540 ms | 2,301 ms | 02:42 |
| 5 | 1,577 ms | 2,173 ms | 02:44 |
| Median | 1,577 ms | 2,301 ms | |
| Baseline median (2026-11-17) | 896 ms | 475 ms | |

The two series are of different sessions. In all eight builds the same steps compile and copy the same numbers of
files (`javafx-base` 314 and 255 sources, `javafx-graphics` 1,618, 1,868 into `target/mods`, 3,538 files copied, 2,001
shims and 766 tests), the time stamps and totals left out
([US-128](US-128-make-a-build-without-changes-compile-nothing-again.md)).

**Windows, outside Maven, same session,** 2026-11-21 10:52 to 10:53: one forced run of each generator into an empty
root, Decora 1,533 ms and Prism 2,524 ms, its md5 list `cmp`-identical to `US-023-windows-gensrc.md5` (108 and 424
files); then 8 pairs on that tree, all 32 runs exit 0 and print nothing, the snapshot after pair 8 identical to the one
before pair 1:

| Pair | Decora, unforced | Decora, forced | Prism, unforced | Prism, forced |
| --- | --- | --- | --- | --- |
| 1, warm-up | 616.5 ms | 1,561.1 ms | 303.9 ms | 2,057.2 ms |
| 2 | 602.3 ms | 1,406.5 ms | 279.5 ms | 2,069.9 ms |
| 3 | 539.5 ms | 1,446.4 ms | 283.1 ms | 2,096.4 ms |
| 4 | 564.1 ms | 1,476.0 ms | 284.8 ms | 2,212.8 ms |
| 5 | 554.6 ms | 1,428.8 ms | 295.8 ms | 2,040.5 ms |
| 6 | 560.6 ms | 1,500.9 ms | 287.9 ms | 2,052.4 ms |
| 7 | 575.4 ms | 1,449.0 ms | 279.9 ms | 2,026.6 ms |
| 8 | 523.4 ms | 1,490.0 ms | 297.3 ms | 2,033.2 ms |
| Median of 2 to 8 | 560.6 ms | 1,449.0 ms | 284.8 ms | 2,052.4 ms |

Forced minus unforced, as the difference of the medians: Decora +888.4 ms, Prism +1,767.6 ms, together about +2.66 s
per build; as the median of the paired differences, +906.9 and +1,764.5 ms.

**WSL, the final cost builds,** 2026-11-21 11:06 to 11:21, timed builds without `clean`, the before clone (a clone of
`38911806e8`, its md5 list `cmp`-identical to `US-023-wsl-gensrc.md5`) and the after clone (the final gate's)
alternating, one build at a time; each 0 / 0 / 0 / 0, 1 start of each step, `-force` in both after steps and in no
before step:

| Build | 1-minute load before it | Decora | Prism | Maven total |
| --- | --- | --- | --- | --- |
| before 1, warm-up | 2.27 | 0.447 s | 0.183 s | 1:55 |
| after 1, warm-up | 2.99 | 1.384 s | 1.793 s | 1:50 |
| before 2 | 3.53 | 0.308 s | 0.149 s | 1:44 |
| after 2 | 3.91 | 1.132 s | 1.757 s | 1:48 |
| before 3 | 4.12 | 0.357 s | 0.173 s | 1:45 |
| after 3 | 3.59 | 1.079 s | 1.611 s | 1:47 |
| before 4 | 3.96 | 0.263 s | 0.167 s | 2:01 |
| after 4 | 4.02 | 1.090 s | 1.718 s | 1:50 |

Builds 2 to 4, medians: Decora 0.308 against 1.090 s (+0.782 s), Prism 0.167 against 1.718 s (+1.551 s), both steps
0.457 against 2.808 s (+2.351 s; by pair +2.432, +2.160 and +2.378 s).
