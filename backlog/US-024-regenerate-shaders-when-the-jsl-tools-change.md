# US-024 — Regenerate the shaders when the JSL compiler or a generator changes

## In short
- **Problem:** After a change to the shader compiler or to a generator, a build without `clean` did not regenerate
  the shaders, so it shipped shaders made by the old tool.
- **Goal:** Every build regenerates, and writes a file only when its content changes.
- **State:** Merged (PR #69, 2026-11-21). It costs about 2.5 seconds per build.
- **Left to do (owner):** post the comment on JDK-8090470 (draft outside the repository).

**Status:** 🔶 Merged, not Done (PR #69, 2026-11-21). What is left is under "In short".
The status as written before the merge:
🔷 Implemented (2026-11-21; filed 2026-09-28): option (c), refined: the build passes `-force` to both
generator steps, so every program is compiled in every build, and a forced run writes an output only when its bytes
differ from the file's or the file is missing; on Windows and in WSL a build without `clean` now regenerates after a
change to the compiler, a generator or a `.jsl` file, a restored one included, writes nothing when nothing changed,
and costs about 2.4 to 2.7 s more per build; the commit and the PR, the upstream comment and an optional macOS build
are [owner-run](#owner-run-and-pending) · **Found:** 2026-09-27, US-008 (filing US-023: a changed Prism generator,
run on a complete output tree, rewrote none of the 1,059 files, where a clean run with it changes 318; filing US-022:
a `.jsl` file restored with its older time was not compiled again)

## Story
As a developer who changes the JSL compiler, a shader generator or a `.jsl` file, and builds without `clean`,
I want every generated shader and peer that the change affects to be written again,
so that the module never packages shaders made by an older compiler or from an older source, and a before/after
comparison of the generated files compares what the new code writes.

## Problem
Paths are relative to `modules/javafx.graphics`. `JSLC.java` is
`src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java`. Line numbers are those of the tree with US-022 (amended
2026-11-17; as filed they were those of `b5b00d4416`).
- `JSLC.compile` writes each output only when `jslcinfo.force` is set or `JSLC.outOfDate(outFile, sourceTime)` holds
  (`JSLC.java:229-289`), and `outOfDate` is `sourceTime >= outFile.lastModified()` (`:294-296`). The pom passes no
  `-force` (US-023).
- The source time is the time of the `.jsl` text only: the file's for the Decora single files (`JSLC.java:170-176`,
  the time at `:174`), the newer of the mask and paint files for a Prism program (US-023,
  `src/main/jsl-prism/CompileJSL.java:401-402`).
  The JSL compiler (`buildtools/jslc`, its classes and `.stg` templates), the generators (`target/jsl-compilers`)
  and their text count for nothing.
- Measured (US-023): the Prism generator with its alpha test changed from `== 0.0` to `<= 0.0`, run on a complete
  output tree, rewrote none of the 1,059 files; a clean run with it changes 318 of them.
- Measured (US-022): a `.jsl` file put back with its older modification time (`cp -p`) after a build of an edited
  version gives exit 0 and rewrites nothing, so the outputs of the edited version stay.
- Every gate in this backlog builds with `clean`, so none is affected. A build without `clean` after a change to the
  compiler or a generator packages the old outputs, and US-019, US-020 and US-022 all change the compiler.
- Upstream too: JDK-8090470, "force running of CompileJSL if compiler sources were modified", has been open since
  2012. Upstream Gradle skips its task while nothing under `src/main/jsl-prism` or `gensrc/jsl-prism` changed (US-023).

## Proposed fix
Options; the choice is part of the story. None was prototyped.
- **(a) Count the tools in the source time.** Each generator takes, as the lowest source time of every program, the
  newest modification time of the JSL compiler's classes and templates and of its own classes. Small, and it keeps
  the per-output check; it still misses a source restored with an older time.
- **(b) A stamp.** The build keeps, in `target/gensrc`, a digest of the compiler, the generators and every `.jsl`
  input, and runs the generators with `-force` when the digest differs. It also catches restored files. Cost: a digest
  of every input in every build.
- **(c) Always `-force`.** Simplest. Every build rewrites every output that `JSLC.compile` writes, so every later step
  that compares times (the second-pass `javac`, the native shader compiles) repeats its work. Not measured. (Amended
  2026-11-17: filed as "every output". The Prism `_Loader.java` files are not rewritten: `CompileJSL.compileShader`
  writes a loader only when it is out of date and never reads `-force` (`src/main/jsl-prism/CompileJSL.java:420`).
  Measured in US-023: with `-force`, the Prism step rewrote all 212 backend files of each backend and only the loaders
  that were out of date, and the Decora step every one of its 108 files with `-d3d`.) (Amended 2026-11-21: that was
  the code before this story's change, which makes the loader read `-force` at the same line and a forced run write
  only the outputs whose bytes change; see [Change](#change).)
- **(d) Keep it and document it:** a change to the compiler or a generator needs `clean`.

## Acceptance criteria
- **A compiler change regenerates.** After a clean build, change a backend template of the JSL compiler in a scratch
  copy of the tree so that its output changes, and build without `clean`: every output the change affects is rewritten,
  and `target/gensrc` equals a clean build's by name and md5. Mutant proof: with today's check the same comparison
  fails (no file rewritten).
- **A generator change regenerates.** The same with the Prism generator's alpha test changed from `== 0.0` to
  `<= 0.0` (318 files differ from the unchanged generator's output, measured in US-023).
- **Nothing changed, nothing written.** A build without `clean` and without changes rewrites no generated file (the
  modification-time lists before and after are equal), unless (c) is chosen; then its cost on Windows and Linux is
  recorded here.
- **With (b), a restored file regenerates:** a `.jsl` file put back with `cp -p` after a build of an edited version is
  compiled again, and the outputs equal a clean build's.
- **Build gate,** with the two commands the US-008 gate ran (amended 2026-11-21: run with `-am` on Windows too, as
  US-023's gate was amended, since without it the build takes `javafx-base` from the local repository; see
  [Drift since filing](#drift-since-filing)):
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`
    (the Linux font goldens were captured on another machine; see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` is identical by name and md5 before and after, and
    `javafx.graphics` has the same test counts per class. (Amended 2026-11-21: `headers` no longer exists since
    `9b02d6a602`, #57, and `mtl-headers` is written only with `-mtl`, so the gates compare `jsl-decora` and
    `jsl-prism`.)

## Definition of Done
- All acceptance criteria met, with the logs, md5 lists and modification-time lists kept with the change.
- The chosen option and the reason recorded in this file.
- An independent review repeated the compiler-change and nothing-changed runs.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Whether to comment on JDK-8090470 upstream decided; a draft stays outside the repository.

## Notes
- Filed rather than fixed in US-008: it changes how the build decides what to regenerate, so it needs its own change
  and its own gate.
- Related to US-023, which makes the Prism step one JVM: with (b) or (c), that one JVM is also the only place the
  `-force` decision has to be made for Prism.
- Not independently fact-checked: the two measurements above were made by the agents that wrote US-022 and US-023.
  (Amended 2026-11-21: both were measured again on `38911806e8`, in this story's runs of the code before the change:
  the alpha-test change rewrote none of the 1,059 files outside Maven on Windows, where a clean run with it differs in
  318, and a `.jsl` file restored with `cp -p` was not compiled again, through Maven in WSL and outside Maven on
  Windows; see [Acceptance criteria and evidence](#acceptance-criteria-and-evidence).)

## Implementation (2026-11-17 to 2026-11-21, Windows and WSL)
The change is described against `38911806e8`, the baseline ("before"). The runs were made on two hosts: Windows 10 Pro
(10.0.19045) with Temurin 27+35 and Apache Maven 3.10.0, where the build generates `-d3d`, and WSL Ubuntu with Temurin
27+35 and Apache Maven 3.10.0, `DISPLAY` and `WAYLAND_DISPLAY` unset, where it generates `-es2`. On Windows the
baseline gate and its timed builds used the main tree at a clean `38911806e8`, and every later run the main tree with
the change. In WSL every run used fresh clones of `38911806e8`; an after clone has the five files of the change copied
in from the main tree, each checked with `cmp`. macOS was not run. Paths are relative to `modules/javafx.graphics`, as
in Problem. Line numbers of `pom.xml`, `JSLC.java` and `CompileJSL.java` are those of the tree with the change unless
a commit is named. Times of day are America/New_York.

The work went in this order. On 2026-11-17: the baseline gates on Windows and in WSL, each followed by three timed
builds without `clean`; the change, its unit tests and mutants, and the Prism loader site proven outside Maven. From
the evening of 2026-11-17 to 01:30 on 2026-11-18, beside each other: the end-to-end runs on Windows, through Maven and
outside Maven; extra cases in WSL clones; the acceptance criteria, before against after, in WSL clones; an independent
review with its own runs and mutants; and the upstream check. On 2026-11-21: the last case of the acceptance criteria,
whose clean build had been cut off at 01:30 on 2026-11-18; the rework of `ForcedRunTest.java` after the review; a
confirming review; and the final gates on Windows and in WSL with the cost measurements. This section describes the
final change; [Independent review](#independent-review-2026-11-17-to-2026-11-18) and
[Rework and confirming review](#rework-and-confirming-review-2026-11-21) give the findings and what was done with them.

Kept with the change:
- `US-024-build-runs.md`: how each build and generator run was made, what it edited, the files
  it wrote or rewrote by kind, its comparison with a clean build and with the same run before the change, the failure
  texts, the mutants and the step times;
- `US-024-windows-per-class.tsv` and
  `US-024-wsl-per-class.tsv`, the per-class lists of the baseline and final gates;
- `US-024-windows-gensrc-times.tsv` and
  `US-024-wsl-gensrc-times.tsv`, the modification-time lists of the Definition of Done:
  path, time and md5 of the 532 files of `target/gensrc` after each final gate, before the builds without `clean`
  that followed it; the snapshots after each of those builds, five on Windows and four in WSL, are `cmp`-identical to
  them.

No new md5 list is kept. The `target/gensrc` md5 lists of the baseline and final gates are `cmp`-identical to
`US-023-windows-gensrc.md5` and `US-023-wsl-gensrc.md5`, and the
lists of the two clean builds with `-Djfx.jsl.backends="-d3d -es2 -mtl"` in WSL clones with the change are
`cmp`-identical to `US-023-regeneration.md5`.

Not kept: the gate and build logs, the other snapshots, the WSL clones, the mutant copies, the scratch classes and
outputs of the runs outside Maven, and the review's scratch tests. They are in the temporary scratch directory
`C:/Users/bestq/AppData/Local/Temp/claude/50b9cf433e233190/c5f3c13d-ed95-405e-ae5e-b39ea033161b/scratchpad/us024`
and, for the clones, under `~/jfx-us024` in WSL, until those are cleaned. `US-024-build-runs.md` keeps each run's
command, exit code, edit, file counts and comparisons, and the excerpts it relies on; it and the sections below
describe each run fully enough to repeat it.

### Choice: option (c), refined
- **What was built.** The build always passes `-force` to both generator steps, and a forced run writes an output only
  when its bytes differ from the file's or the file is missing. Every program is compiled in every build; only what
  changed is written. The time check stays for runs without `-force`.
- **Why.** Every output is then a function of what the build runs now, the compiler's classes and templates, the
  generator classes, the `.jsl` text, the arguments and the JDK, with no list of inputs that can miss one. It
  regenerates after a compiler change, a generator change, a `.jsl` file restored with an older time (`cp -p`), a
  truncated output, and with a source time in the future ([Behaviour changes](#behaviour-changes)).
- **Why only the changed bytes.** An output with unchanged bytes keeps its file and its modification time, so the steps
  after it that compare times (`fxc` through the CMake `DEPENDS` of `native/win.cmake:311-326`, the `copy-shaders`
  `<copy>`, the Metal compile of `native/mac.cmake:220-240`) repeat their work only for outputs that changed. That
  removes the cost the story names against (c) as filed, where every output is rewritten and every later step repeats.
  What remains is the generator time, every program compiled in every build, which [Cost](#cost) records.
- **Against (a), the tools in the source time.** It depends on the file times of class files and jars: a jar from the
  local repository has its install time, and a recompile of an unchanged compiler gives new times, which would rewrite
  all 532 files and repeat every `fxc` compile. It still misses a restored source.
- **Against (b), a stamp.** Its input list is open-ended: the JSL compiler and its jars, both generators,
  `PrismLoaderGlue.stg`, the `.jsl` files, the arguments, and the `javafx.graphics` classes that the Decora generators
  read (`Blend.Mode`, `LinearConvolveRenderState`, the light classes). A missing input skips a needed run, which is the
  defect of this story. It also needs a stamp file and a digest step in every build.
- **Against (d), documentation.** It leaves the defect, and `clean` costs a full build of the module (the clean gates of
  this story took 6:29 to 11:01 min).
- **The time check stays** for runs without `-force`: the tool's command line keeps its behaviour, and so does
  US-018's up-to-date path for Metal shaders (`MetalHeaders.addUnlessRenamed`) with its tests; removing it is outside
  this story. Without `-force` an out-of-date output is still written even with unchanged bytes, so that its new time
  makes it up to date.

### Change
In the `jsl-codegen` execution of `pom.xml`, both generator steps get `<arg value="-force"/>`: the
`GenAllDecoraShaders` step at `:163`, after a two-line comment (`:161-162`), and the Prism `CompileJSL` step at `:199`.
The Decora step now reads:
```xml
<arg value="com/sun/scenario/effect"/>
<!-- Here and in the Prism run: file times miss a changed compiler or generator,
     and a forced run writes only the outputs whose bytes change -->
<arg value="-force"/>
<arg line="${jfx.jsl.backends}"/>
```
- **Position.** In both steps `-force` follows `-pkg <package>` and comes before `<arg line="${jfx.jsl.backends}"/>`.
  In the Decora step it must come before the placeholders `-all GenAllDecoraShaders` (`:166-167`), which
  `GenAllDecoraShaders` checks to be the last two arguments (the review). Logged, the arguments end
  `-pkg com/sun/scenario/effect -force -d3d -all GenAllDecoraShaders` and `-pkg com/sun/prism -force -d3d` on Windows,
  and the same with `-es2` in WSL; each command line differs from the baseline's by the one `'-force'` line.
- **The comment** says once, for both steps, why the build forces them and why that rewrites little.
- **Format.** 4 lines added. All 964 lines of the pom end in CRLF, as its 960 did; the new lines are 116, 104, 57 and
  57 columns, and the 18 lines over 120 columns are older lines, the same 18 as at `38911806e8`.

`JSLC.java`:
- The five output sites of `JSLC.compile` call `writeOutput(jslcinfo, …, outFile)` where they called `write(…)`
  (`:235`, `:245`, `:261`, `:277`, `:287`). Their decisions are unchanged, `jslcinfo.force || outOfDate(…)` (`:231`,
  `:241`, `:254`, `:273`, `:283`). The Metal site calls it in its `compileMetal` branch; the branch for an up-to-date
  shader whose names only look renamed keeps the private `writeIfChanged` (`:265`).
- `public static void writeOutput(JSLCInfo jslcinfo, String str, File outFile)` (`:298-310`) is the one rule: with
  `jslcinfo.force` it calls `writeIfChanged`, otherwise `write`. Its Javadoc gives the reason for both branches.
- `writeIfChanged` (`:323-329`) now also writes when the file is missing (`!outFile.isFile() ||`, `:326`). It compares
  the bytes of `str.getBytes(Charset.defaultCharset())`, the charset of `write`'s `FileWriter` (`:324-325`), and it
  writes a changed output with `write` itself, so a forced write has the bytes of an unforced one.
- One line of Javadoc on the `force` field (`:333`). `write` is unchanged and public. 511 lines before, 526 now.

The Prism generator, `src/main/jsl-prism/CompileJSL.java`, is the sixth site. Its loader is compiled when
`jslcinfo.force || JSLC.outOfDate(outFile, sourcetime)` (`:420`, where it read the time only) and written with
`JSLC.writeOutput(jslcinfo, …)` (`:423`, where it called `JSLC.write`); 535 lines before and after. The Decora
generators write only through `JSLC.compile`, and `MetalHeaders` already compared before writing
(`src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/MetalHeaders.java:167-168`, `:351-357`); neither changes.

Tests, in `src/test/jslc/com/sun/scenario/effect/compiler`, run by `buildtools/jslc`:
- `MetalHeadersTest.aRunWithForceWritesEveryOutputAgainThoughNothingIsOutOfDate` pinned the old meaning of `force`. It
  is now `aRunWithForceWritesNoOutputOrHeaderWhoseBytesAreUnchangedThoughEverySourceIsNewer` (`:623-638`): a forced
  run of two probe programs whose sources are newer than their outputs leaves every file of the test directory, 10
  outputs and 2 headers, with its time and content. With up-to-date times it would pass even if `force` were ignored
  (the review). 1,785 lines before, 1,780 now.
- `ForcedRunTest` is new, 220 lines: 7 parameterized tests over the five output types, D3D, ES2, Metal, Java peer and
  Prism peer, 32 cases in all.
  - A forced run rewrites an output that is up to date by time when its program changed (5). The change renames the
    sampler `baseImg` to `origImg`; a precondition shows that it changes the bytes but not the length.
  - A forced run keeps the time and the bytes of an output whose bytes are unchanged though its program is newer (5).
  - A forced run compares in the charset it writes with, so an unchanged output with an accented letter from a glue
    block keeps its time (2: the two peers, which alone copy a glue block). A precondition shows that the letter
    reached the file as more than one byte.
  - A forced run writes a missing output (5).
  - A forced run fails on a syntax error in a program whose output is up to date, with the message of an unforced
    run, and leaves the output as it was (5).
  - Without `force`, an output that is up to date by time keeps its bytes though its program changed (5).
  - Without `force`, an out-of-date output is written again though its bytes are unchanged (5).
- In the final gates `javafx-jslc` runs 437 tests, where it ran 405: `ForcedRunTest` 32 / 0 / 0 / 0 on both hosts,
  and `MetalHeadersTest` 33, with its 3 skipped on Windows as before.

The four Java files are LF only, with no tab, no trailing blank and no line over 120 columns (the implementation's and
the rework's checks); the production files stay under the 900-line limit, `JSLC.java` at 526 and `CompileJSL.java` at
535. `git diff --stat`: 4 files, 31 insertions, 17 deletions, and `ForcedRunTest.java` is new. No other file of the
build changes: no `.jsl`, `.stg`, grammar, CMake file, other pom or CI file.

### Behaviour changes
- **Every program is compiled in every build,** whatever the file times say. So an error in a program whose outputs
  are up to date by time fails the build. With the `;` at the end of `src/main/jsl-prism/MaskFillPgram.jsl:108`
  removed and the file's time set back to 2001, a build without `clean` exits 1 with
  `MaskFillPgram.jsl:109:4: in program FillPgram_Color: missing ';' at 'return'` and writes nothing; at `38911806e8`
  the same build exits 0 (WSL).
- **A change regenerates** what it changes, and the result equals a clean build's by name and md5: a compiler
  template, a compiler class, the generator, the loader template ([evidence](#acceptance-criteria-and-evidence)).
- **A `touch` of a `.jsl` file rewrites nothing.** After `touch src/main/jsl-prism/MaskFillPgram.jsl` no file gets a
  new time, on Windows and in WSL. At `38911806e8` the same touch gives 32 files a new time: 16 `.frag` and 16
  loaders in WSL (measured here), 16 `.hlsl` and 16 loaders on Windows (US-023's record).
- **Restored, truncated and future-dated inputs are healed.** Two `.jsl` files put back with `cp -p` after a build of an
  edited version: their 18 outputs are written back and equal a clean build's (before: nothing written, the 18 files
  of the edited version stay). Two outputs truncated to 0 bytes with a new time: both written again (before: both
  stay empty and the build passes). `PaintColor.jsl` a day in the future: nothing written in two builds (before: the
  same 56 files in each).
- **An output that a forced run cannot read** fails the build with the I/O error: the review's write-only output gave
  `AccessDeniedException` at `Files.readAllBytes`, where an unforced run of an out-of-date output writes it.
- **Unchanged:** a clean build's bytes (532 files on each host and 1,059 with three backends, identical by name and
  md5 to the kept lists); a build without `clean` and without changes writes nothing, as before; runs without
  `-force`, where an out-of-date output is written even with unchanged bytes and an up-to-date one is not compiled; the
  tool's command line; the messages and exit codes of a failure.

### Drift since filing
What the filed text says against what is true at `38911806e8`, or was measured from 2026-11-17 to 2026-11-21:

| Filed text | Says | Now |
| --- | --- | --- |
| Acceptance criteria, Build gate | the Windows command without `-am` | Without `-am` the build takes `javafx-base` from the local repository, as US-023's amended gate says (not run without it here); both gates ran with `-am`, and the Linux command has it as filed. Amended; [US-127](US-127-make-a-build-to-the-compile-phase-pass-from-a-fresh-clone.md) asks for a build to the compile phase that passes from a fresh clone |
| Acceptance criteria, Build gate | `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` | `headers` and its 46 files are gone since `9b02d6a602` (#57), as US-023's gate amendment says, and `mtl-headers` is written only with `-mtl`: each gate has `jsl-decora` 108 and `jsl-prism` 424. Amended |
| Proposed fix, (c) | every build rewrites every output, so every later step that compares times repeats its work | Built refined: a forced run writes only the outputs whose bytes change, and the Prism loader is forced too; with nothing changed nothing is written ([Choice](#choice-option-c-refined)) |
| Proposed fix, (c), amended 2026-11-17 | the loader never reads `-force` (`CompileJSL.java:420`) | It does since this change, at the same line. Amended |
| Problem | the pom passes no `-force` | It passes it to both generator steps (`pom.xml:163`, `:199`) |
| Problem, measured (US-023) | a changed generator rewrote none of the 1,059 files; a clean run changes 318 | Measured again on `38911806e8` outside Maven on Windows: 0 rewritten, 318 differ from the clean run |
| Problem, measured (US-022) | a `.jsl` file restored with `cp -p` is not compiled again | Measured again on `38911806e8`: through Maven in WSL 0 files rewritten and 18 of the edited version left; outside Maven on Windows 0 and 4 |
| Acceptance criteria, A generator change regenerates | 318 files differ | With three backends; with one backend 106, one file for each `_AlphaTest` program |
| Notes | not independently fact-checked | Measured again, as above. Amended |

Line references, as the filed text gives them (amended 2026-11-17 to the tree with US-022), at `38911806e8` and with
the change:

| Reference | Filed | At `38911806e8` | Now |
| --- | --- | --- | --- |
| `JSLC.java`, the outputs of `JSLC.compile` | `:229-289` | `:229-289` | `:229-289`; the writes call `writeOutput` |
| `JSLC.java`, `outOfDate` | `:294-296` | `:294-296` | `:294-296` |
| `JSLC.java`, a file's source time | `:170-176`, the time at `:174` | the same | the same |
| `CompileJSL.java`, the newer of the mask and paint times | `:401-402` | `:401-402` | `:401-402` |
| `CompileJSL.java`, the loader's check | `:420` | `:420`, the time only | `:420`, `force` or the time |
| `JSLC.java`, `writeIfChanged` | not cited | `:309-315` | `:323-329` |

### Acceptance criteria and evidence
"Before" is the same run on `38911806e8`: in WSL a clone of it, outside Maven on Windows its `JSLC.java` and Prism
`CompileJSL.java` compiled into scratch directories, put first on the class path, and run without `-force`.

| Criterion | Evidence | Before (mutant proof) | State |
| --- | --- | --- | --- |
| A compiler change regenerates | Through Maven in WSL clones, after a clean build, each edit and a build without `clean`: `PrismGlue.stg:32` rewrites the 30 Prism peers (`PPS*Peer.java`), `JSWGlue.stg:33` the 28 Java peers, `ES2Backend.java:119` all 262 `.frag`; each time the files with a new time are those with a new md5, and `target/gensrc` equals a clean build of the edited tree by name and md5. Outside Maven on Windows with three backends: `PrismGlue.stg` 30 files, `HLSLBackend.java:118` all 262 `.hlsl`, each equal to a clean run. The review's own edit of `ES2Backend.java` and `PrismLoaderGlue.stg`: 368 files, equal to a clean build (runs) | 0 files rewritten in each case run before; the tree then differs from a clean build in 30, 262, 30, 262 and 368 files (`JSWGlue.stg` not run before) | Met |
| A generator change regenerates | `CompileJSL.java:395`, `== 0.0` to `<= 0.0`: through Maven in WSL 106 `.frag` (every `_AlphaTest` program) with `-es2`, and 318 (106 each of `.frag`, `.hlsl` and `.metal`, no header) with `-d3d -es2 -mtl`; outside Maven on Windows with three backends 318; each equal to a clean build of the changed generator. The loader template, `PrismLoaderGlue.stg:30`: the 212 loaders, through Maven in WSL with one and three backends and outside Maven on Windows with `-d3d`, each equal to a clean build (runs) | 0 rewritten; 106 files (WSL) and 318 (Windows) differ from the clean build. The loader template: 0, and 212 differ (WSL; on Windows with `38911806e8`'s Prism generator) | Met |
| Nothing changed, nothing written | 21 builds without `clean` and without changes after the change, on Windows (9, two of them with natives) and in WSL (12, two of them with three backends): no file of `target/gensrc` got a new time or a new md5, none was added or removed (runs). Option (c) is chosen, so the cost on Windows and Linux is recorded in [Cost](#cost) | The baseline writes nothing either: 0 in each of its 11 such builds | Met; cost recorded |
| With (b), a restored file regenerates | Written for (b); (c) meets it too. Through Maven in WSL, `src/main/jsl-decora/ColorAdjust.jsl:144` and `src/main/jsl-prism/MaskFillPgram.jsl:108` edited and built without `clean` (18 files rewritten), then both put back with `cp -p` and built again: the same 18 rewritten, and `target/gensrc` equals a clean build of the restored tree. Outside Maven on Windows, `ColorAdjust.jsl:139` with three backends: 4 files and 4, the end tree equal to a clean run's (runs) | After the restore 0 rewritten, and 18 files (WSL) and 4 (Windows) of the edited version stay | Met, by (c) |
| Build gate | Both commands, with `-am` on Windows, before and after ([Final gates](#final-gates-2026-11-21)): exit 0; jslc 405 and 437 tests, base and graphics the same totals; per class only `ForcedRunTest` added; `target/gensrc`, 532 files with `jsl-decora` 108 and `jsl-prism` 424, identical by name and md5 to US-023's lists | – | Met, as amended |
| DoD: logs, md5 lists and modification-time lists kept | The kept files above. The logs are not kept, as for US-019 to US-023; the md5 lists are US-023's, `cmp`-identical | – | Met as recorded |
| DoD: the chosen option and the reason | [Choice](#choice-option-c-refined) | – | Met |
| DoD: an independent review repeated the compiler-change and nothing-changed runs | In its own WSL clones: 368 files after its edits against 0 before, and no file written in two builds without changes ([Independent review](#independent-review-2026-11-17-to-2026-11-18)) | – | Met |
| DoD: status ✅ Done with the date and the PR, row moved | After the owner's commit and PR | – | Pending, the owner's |
| DoD: JDK-8090470 decided, a draft outside the repository | [Upstream check](#upstream-check-2026-11-17): comment on it with the draft; posting is the owner's | – | Decided and drafted |

### Runs
`US-024-build-runs.md` gives the commands, edits, counts and excerpts. In short:
- **Baseline,** 2026-11-17: the two gates, then three timed builds without `clean` on each host, none writing a file.
- **Nothing changed:** the 21 builds above, and on Windows the `fxc` objects of the builds with natives.
- **Changes,** most with the same run before the change: compiler templates and a compiler class, the generator's
  alpha test with one backend and with three, the loader template, restored `.jsl` files, a touch, truncated outputs
  and a source time in the future, through Maven in WSL and, for most, outside Maven on Windows.
- **Failures:** an error in a source with an old time fails now; US-023's failure case, a clean build with the error,
  is unchanged.
- **Mutants and negative controls:** the implementation's 15 unit-test mutants, the review's 18 and its 2 pom
  mutants, the rework's 5 and the confirming review's 17, with the negative controls of the last three.
- **Times:** [Cost](#cost).

### Cost
The cost of always forcing is the generator time: both steps compile every program in every build. Through Maven a
step time is the wall time from the step's `[java] Executing` line to the first line of the next task, in timed builds
without `clean` and without changes; outside Maven it is the wall time of the generator's JVM
(methods).

| Comparison | Same session | Runs | Decora step, before and after | Prism step, before and after | Both steps, added |
| --- | --- | --- | --- | --- | --- |
| Windows, the two generators started outside Maven with the logged command lines, on a complete `-d3d` tree, 2026-11-21 10:52 to 10:53 | yes | 8 pairs of unforced and forced runs; medians of pairs 2 to 8 | 560.6 and 1,449.0 ms | 284.8 and 2,052.4 ms | +2.66 s |
| WSL, timed builds through Maven, a clone of `38911806e8` and the final gate's clone alternating, 2026-11-21 11:06 to 11:21 | yes | 4 each; medians of builds 2 to 4 | 0.308 and 1.090 s | 0.167 and 1.718 s | +2.35 s (0.457 and 2.808 s) |
| Windows, timed builds through Maven in the main tree: before 2026-11-17 21:59 to 22:10, after 2026-11-21 10:33 to 10:50 | no | 3 and 5; medians of all | 896 and 1,577 ms | 475 and 2,301 ms | +2.5 s |

- **Load.** Windows, 2026-11-21: no other `java` process before each timed build or before the runs outside Maven,
  16,416 to 16,650 MB of memory free; CPU load of other processes not measured. Windows, 2026-11-17: no other `java`
  or `mvn` process at the start of the baseline gate. WSL, 2026-11-21: no other build seen, a 1-minute load average of
  2.27 to 4.12 before the eight builds, and 9 to 10 GB free.
- **Maven totals** of the same builds: Windows 02:37 to 03:17 min after, 03:11 to 03:40 before; WSL 1:47 to 1:50 after,
  1:44 to 2:01 before. Most of such a build recompiles the modules, before and after alike
  ([US-128](US-128-make-a-build-without-changes-compile-nothing-again.md)).
- **Earlier, under load,** not a cost record: on 2026-11-17 the Windows end-to-end builds with natives ran while this
  story's builds ran in WSL (load average 11.1 to 12.6 from 23:11 on, as reported; not saved), and the WSL runs of
  2026-11-17 and 2026-11-18 ran beside each other. Their step times are in the record.
- **Unchanged outputs keep their time,** measured with natives on Windows on 2026-11-17: in the two builds without
  changes and in the build after `touch MaskFillPgram.jsl`, none of the 262 `.obj` files got a new time, `fxc` ran for
  none and `copy-shaders` copied none; CMake took 6.7 to 8.2 s in those builds. The positive control, a `touch` of the
  generated `FillPgram_Color.hlsl`, made `fxc` compile that one object and `copy-shaders` copy it, and the forced
  generator left the touched `.hlsl` as it was. `fxc` at the baseline was not measured; there the touch gives the 16
  `FillPgram_*` `.hlsl` files a new time (US-023), which the CMake rules compile again (read, not run).

### Final gates (2026-11-21)
Before on the baseline (2026-11-17) and after on the final change (2026-11-21), the commands of the "Build gate"
criterion as amended. Counts are run / failures / errors / skipped.

| Gate | Windows | WSL |
| --- | --- | --- |
| Command | `mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am clean test -Djfx.parity.require=true`, in the main tree | `mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am clean test`, in fresh clones, no display; the after clone with the five files of the change copied in, `cmp`-checked |
| Before | 2026-11-17, 21:49 to 21:59, exit 0, BUILD SUCCESS, Maven total 09:41 min | 2026-11-17, 21:55 to 22:03, exit 0, BUILD SUCCESS, 08:18 min |
| After | 2026-11-21, 10:21 to 10:32, exit 0, BUILD SUCCESS, 11:01 min | 2026-11-21, 10:57 to 11:04, exit 0, BUILD SUCCESS, 06:29 min |
| Modules, before | `javafx-jslc` 405 / 0 / 0 / 3, `javafx-base` 5,510 / 0 / 0 / 22, `javafx-graphics` 26,684 / 0 / 0 / 850 | `javafx-jslc` 405 / 0 / 0 / 0, `javafx-base` 5,510 / 0 / 0 / 22, `javafx-graphics` 26,428 / 0 / 0 / 967 |
| Modules, after | `javafx-jslc` 437 / 0 / 0 / 3, `javafx-base` and `javafx-graphics` the same | `javafx-jslc` 437 / 0 / 0 / 0, `javafx-base` and `javafx-graphics` the same |
| Per class | 954 classes before, 955 after; one class differs, `ForcedRunTest`, new, 32 / 0 / 0 / 0 (per class) | the same (per class) |
| `target/gensrc` | 532 files, `jsl-decora` 108 and `jsl-prism` 424, no `mtl-headers`; before and after `cmp`-identical to `US-023-windows-gensrc.md5` | the same counts; before and after `cmp`-identical to `US-023-wsl-gensrc.md5` |
| The five files of the change | md5s `cmp`-identical to the final list before and after the gate, and at the end | md5s equal to the final list before the gate (compared by eye), then `cmp`-identical to that copy after the gate and after the cost builds |
| Crash files | no `hs_err_pid*` under `modules/javafx.base`, `modules/javafx.graphics` or `buildtools/jslc` | none in the clone |

- On Windows jslc's 3 skipped tests are those of `MetalHeadersTest` (33 / 0 / 0 / 3), before and after.
- Load. Windows, before: no other `java` or `mvn` process at the start; the 09:41 min was not investigated. Windows,
  after: another project's Maven build (not this repository) was running at the start, with a test fork, and had
  ended by the end, so 11:01 min is no clean comparison. WSL, before: another project's build was running when the gate
  was due; the gate started after it ended. WSL, after: no Maven process at the start and no other build seen.
- The per-class lists come from each log's `Tests run: … -- in <class>` lines, merged as US-023's were. The Windows
  after list sums to its totals, 32,631 run and 875 skipped.
- No source of a shipped module changes: `JSLC.java` belongs to the build tool `javafx-jslc`, and `CompileJSL.java` is
  a generator. A clean build's generated files are md5-identical before and after, so no `FULL_TEST`/`USE_ROBOT` run
  is called for; `target/classes` was not compared. The PR's `submit.yml` run, required for a pom change, is to cover
  the other platforms.

### Independent review (2026-11-17 to 2026-11-18)
Of the change before the rework, with the first `ForcedRunTest` of 30 tests, in WSL clones of its own (`-es2`), with
the timed build, from 23:04 on 2026-11-17 to 00:21 on 2026-11-18; other builds of this story ran in WSL at the same
time. Verdict: pass, no must-fix.

What it repeated, for the Definition of Done:
- **The compiler change.** In its after clone, after a clean build, `ES2Backend.java` (`"uniform vec4
  jsl_pixCoordOffset;\n"` to `"uniform highp vec4 jsl_pixCoordOffset;\n"`) and `PrismLoaderGlue.stg:30` (`DO NOT EDIT
  MANUALLY!` to `DO NOT EDIT BY HAND!`) edited, then a build without `clean`: 368 files rewritten, `jsl-decora` 2
  (`PhongLighting_POINT.frag`, `PhongLighting_SPOT.frag`) and `jsl-prism` 366 (154 `.frag`, 212 loaders), the files
  with a new time the same as those with a new md5, and `target/gensrc` equal to a clean build of the edited tree. In
  its before clone, the same edits: 0 new times and 0 md5 changes, and the tree differs from its clean build in 368
  files; that clean build equals the after clone's.
- **Nothing changed.** Two builds without `clean` in the after clone: 0 new times, 0 md5 changes, the same 532 names.

| Finding | Raised as | Done |
| --- | --- | --- |
| `ForcedRunTest` changes the length of every output (`offset` to `shift`), so a comparison of lengths only passes all its tests: two such mutants at `JSLC.java:326` survive it and are killed only by an older `MetalHeadersTest` test of the unforced rename branch and by the review's own tests | optional 1 | Taken, in the tests-only rework: a same-length sampler rename ([Rework](#rework-and-confirming-review-2026-11-21)) |
| The charset of the comparison is pinned by no test of the change: `getBytes(ISO_8859_1)` at `JSLC.java:325` passes them all | optional 2 | Taken, in the rework: a test with a non-ASCII glue block for both peers |
| A forced run never deletes an output that the generators no longer produce, so a change that drops or renames an output still needs `clean`; options (a) to (c) share this | optional 3 | Accepted and recorded under [Known limits](#known-limits); no new story; US-121's Notes name the neighbouring case |
| A forced run reads every output, so one that cannot be read fails the build (a write-only output: `AccessDeniedException`) | optional 4 | Declined: the build creates its outputs itself, an output it cannot read is a broken build directory, and stopping with the I/O error is the right report; `clean` repairs it |
| The second-pass `javac` (`pom.xml:205-225`) compiles 1,868 sources in every build without `clean`, before and after alike | outside the story | Filed, with the other steps that repeat in such a build, as [US-128](US-128-make-a-build-without-changes-compile-nothing-again.md) |

Its design verdicts, all sane: option (c) with compare-before-write, since every run derives every output again,
also from the `javafx.graphics` classes that the Decora generators load, which (a) and (b) would have to list; always
forcing in the pom; writing only changed bytes when forced and every out-of-date output otherwise, which keeps the
later steps incremental and the old behaviour; keeping the time check; one public `writeOutput` for the six sites; the
forced loader site; the position of `-force` and the one comment; the reworked `MetalHeadersTest` test; and
`ForcedRunTest`, with the reach limits of the findings above.

What else it checked: the jslc tests in its after clone, 435 / 0 / 0 / 0; 23 tests of its own (not kept), among them a
same-length change, a non-ASCII glue block, a directory or a write-only file in an output's place, a truncated output
and an exception in the middle of a forced run, all passing on the change, the first two being what killed the
mutants of findings 1 and 2; its mutants (record); that
`-force` is read only at `pom.xml:163`, `:199` and `JSLC.java:408` (`git grep`); and that the steps that read the
generated files' times, the CMake `fxc` and Metal rules, `copy-shaders` and the second-pass and shim `javac`s, gain
from compare-before-write. Not checked by it: Windows, three backends, the alpha-test change, a touch or a `cp -p`
restore end to end, and the gates.

### Rework and confirming review (2026-11-21)
- **Rework,** of `ForcedRunTest.java` only, 192 to 220 lines, on Windows from 09:41 to 09:54, for optional findings 1
  and 2:
  - The two tests that change a program rename the sampler `baseImg` to `origImg`, and a helper asserts that the
    rename changes the bytes but not the length. The rename first tried, `offset` to `origin`, keeps the length of
    four outputs but not of the Metal shader (662 to 653 bytes), because `MSLBackend.VAR_MAP` adds `uniforms.` only to
    the names in its list; both sampler names are in it, and both have 7 letters.
  - A new test, for the Java peer and the Prism peer, compiles a program with `// café` in a glue block (written as
    `\u00e9` in the source) and asserts that a forced run keeps the output's time and bytes, after a precondition
    that the letter reached the file as more than one byte. Both peers, because `PrismBackend.visitGlueBlock` copies a
    glue block's text too.
  - jslc 437 / 0 / 0 / 3, `ForcedRunTest` 32. Each of the mutants of a length comparison (two), of the charset, of a
    decision site and of a write site is killed by `ForcedRunTest`; the charset test fails on its precondition without
    the letter and passes a charset mutant once the precondition and the letter are gone (the negative controls).
  - The production files are byte-identical to the reviewed ones: `pom.xml` `2e028c21…`, `JSLC.java` `5b4565c8…`,
    `CompileJSL.java` `df3177fb…` and `MetalHeadersTest.java` `9cf81da6…`. `ForcedRunTest.java` went from `ffb79dbc…`
    to `977f03ad…`. So every end-to-end run of the reviewed change stands for the final one.
- **Confirming review,** in a WSL clone from 09:59 to 10:12: pass, no must-fix. jslc 437 / 0 / 0 / 0; all 17 of its
  mutants killed, the two length mutants and the charset mutant by `ForcedRunTest` itself; three negative controls as
  required, among them the old, length-changing rename without the length assertion, under which a length mutant
  passes. In its kept test directories, the outputs of the two programs of the up-to-date test without `force` have
  equal sizes and different md5s for all five types. Its two optional notes: the charset test needs a default charset
  that encodes `é` in more than one byte (a note only; [Known limits](#known-limits)); and the line outside the story
  below, which it confirmed by reading the source.
- **Outside the story, found in the rework:** `MSLBackend.getVar`
  (`src/jslc/java/com/sun/scenario/effect/compiler/backend/hw/MSLBackend.java:182-184`) writes a parameter as
  `VAR_MAP.getOrDefault(v, v)`, so a name that is not in the fixed list (`:82-154`) comes out without the `uniforms.`
  prefix, which is not valid MSL: in the probe, `in.texCoord0 - origin`; not compiled with the Metal compiler. It is
  US-124's subject and is a dated note there, not a new story.

### Upstream check (2026-11-17)
- **JDK-8090470,** "decora-runtime/build.xml and prism-ps/build.xml: force running of CompileJSL if compiler sources
  were modified", read through the anonymous JBS REST API: Open, P5, fix version tbd, no assignee, created 2012-12-28,
  last updated 2018-09-05, no description, no comments, no links. Its sibling JDK-8090472 (Open) asks for the same.
  Related: JDK-8101343 (fixed for fx2.0), the origin of the time check; JDK-8093735, Closed as Won't Fix; JDK-8172236,
  Open. No other issue is about regenerating after a compiler or generator change.
- **openjdk/jfx master** at `735d02da91` (2026-11-17) has the same gaps, read, not run: `JSLC` decides by time
  (`JSLC.java:211`, `:221`, `:231`, `:244`, `:258-260`, `:284-286`, `:306`; `outOfDate` at `:317-318`) and writes
  without comparing; the Prism loader ignores `force` (`CompileJSL.java:425`); `build.gradle` passes no `-force`; and
  `generate${name}Shaders` declares only `src/main/jsl-<name>` as its input (`build.gradle:1747-1748`), so a compiler
  change leaves the task up to date. Of 50 open pull requests, #2346 (Direct3D 12) edits the same sites of `JSLC.java`
  and `build.gradle` and adds none of this.
- **Decision:** comment on JDK-8090470 with the draft, which says what was measured downstream and what upstream would
  need besides (its compiler classes as task inputs); posting is the owner's.
- **Draft:** kept outside the repository, as `upstream/US-024-upstream-draft.md` under
  `C:/Users/bestq/AppData/Local/Temp/claude/50b9cf433e233190/c5f3c13d-ed95-405e-ae5e-b39ea033161b/scratchpad/us024`.
  That is a temporary directory: the owner must copy the draft elsewhere before it is cleaned. Gradle was not run.

### Known limits
- **A forced run deletes nothing.** A change that makes a generator drop or rename an output, or a deleted `.jsl` file,
  leaves the old outputs in `target/gensrc`, and the second-pass `javac` (`pom.xml:205-225`) and `copy-shaders`
  (`:246-249`) still compile and package them; such a change still needs `clean`. Read from the code by the review,
  not measured. Options (a) to (c) share it. US-121 has the neighbouring case of a deleted source.
- **The loader site has no unit test:** `buildtools/jslc` does not compile the Prism generator. It is proven outside
  Maven on Windows, with `38911806e8`'s generator as the mutant, and through Maven in WSL with one and three backends
  and in the review's runs.
- **The pom's two `-force` arguments** are checked end to end only: without either, the review's runs left stale
  files.
- **The charset test** rests on a default charset that encodes `é` in more than one byte, UTF-8 on JDK 27 (the Windows
  host reports the platform encoding UTF-8). Under a single-byte default its precondition fails loudly.
- **Every output is read in every build,** 532 files with `-d3d` or `-es2`, and one that cannot be read fails it
  (finding 4, declined).
- **Not verified:** three backends through Maven on Windows (they ran outside Maven there); the Metal shaders with the
  Metal compiler; `fxc` at the baseline; a same-session Maven comparison on Windows (the cost outside Maven is one);
  `target/classes` after the truncated-output case (checked in `target/gensrc` only). Some WSL extra cases compare an
  after clone with the clean list of the same clone from before the case rather than a new clean build
  (record).

### Owner-run and pending
- **The commit and the PR,** with the five files of the change and the backlog files of this write-up. The PR's
  `submit.yml` run must pass, as for any pom change.
- **Posting the comment on JDK-8090470** from the draft above, once it is copied out of the temporary directory.
- **Done:** set the status to ✅ Done with the date and the PR, and move the row to the done table of
  `backlog/README.md`.
- **Optional, a macOS build,** the only place where `.metal` files are written and compiled
  (`native/mac.cmake:220-240`), in a fresh clone of the commit:
  `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`. Expected, derived from US-022's macOS
  gate (its per class and md5 list) plus this story's 32
  tests, not run: BUILD SUCCESS, `javafx-jslc` 437 / 0 / 0 / 0, `javafx-base` 5,510 / 0 / 0 / 22, `javafx-graphics`
  26,696 / 0 / 0 / 858, and `target/gensrc` (797 files: `jsl-decora` 158, `jsl-prism` 636, `mtl-headers` 3) identical
  by name and md5 to `US-022-macos-gensrc.md5`.

Not run: anything on macOS, and the Metal compiler anywhere; Gradle; the Windows gate command without `-am`; a change
that drops or renames an output; `fxc` on shaders that a change regenerated; the `JSWGlue.stg` edit and the
three-backend cases through Maven on the baseline; a three-backend build through Maven on Windows; a Windows path
with a space.
