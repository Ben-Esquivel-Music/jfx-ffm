# US-128 — Make a build without `clean` do only what changed

## In short
- **Problem:** A build without `clean` gets two things wrong.
  - Part 1: with nothing changed it compiles the Java sources of `javafx-base` and `javafx-graphics` again, every
    time. That costs minutes on every such build.
  - Part 2: after a source file is deleted or renamed it keeps the old `.class` files, so it tests and packages
    classes that no longer have a source.
- **Seen failing?** Yes, both measured: part 1 in 19 timed builds on Windows and WSL; part 2 when US-021's files were
  deleted and their classes were still in the output.
- **Goal:** A build without `clean` compiles nothing when nothing changed, compiles only what an edit needs, and holds
  no class of a deleted source.
- **Done when:** Three builds in a row without changes report no compile step on Windows and WSL; a build after one
  edit is still correct; and after sources are deleted, their classes are in no output directory or jar.
- **Order:** Can start now. Part 1 saves time on every build. Merged on 2026-11-27 from US-128 and US-121; each part
  keeps its own text below.

**Status:** 📋 Ready (merged on 2026-11-27 from US-128, US-121 at the owner's request; no text of the parts
was changed, only their headings moved one level down)

## Part 1 — Make a build without changes compile nothing again

**Status:** 📋 Ready (filed 2026-11-21 from US-024) · **Found:** 2026-11-17 to 2026-11-18, US-024's independent
review (its line outside the story: the second-pass `javac` compiles 1,868 sources in every build without `clean`);
the other steps read from the logs of US-024's timed builds, one pom search for this story, nothing built for it

### Story
As a developer who builds `javafx-base` and `javafx-graphics` again without `clean`,
I want a build in which nothing changed to compile no source and rewrite no output,
so that such a build does not spend minutes compiling what the build before it compiled, and a build after an edit
compiles what the edit needs.

### Problem
Paths are relative to the repository root. Line numbers are those of the tree with US-024's change.

#### Measured
Timed builds without `clean` and without changes, each after a build of the same tree, with the command and the
`MAVEN_OPTS` of US-023-build-runs.md:
`mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am test-compile -DskipNative=true`. Temurin 27+35
and Maven 3.10.0; on Windows 3 builds at `38911806e8` (2026-11-17) and 5 with US-024's change (2026-11-21), in the
main tree; in WSL 3 builds at `38911806e8` (2026-11-17), then 4 at `38911806e8` and 4 with the change, alternating
(2026-11-21), in fresh clones (US-024's record). None of them wrote a file
of `target/gensrc` (US-024). All 19 logs report the same steps with the same counts; the times are those of the
builds of 2026-11-21, from the time stamp of the line that starts the step to the first line of the next mojo (for
the second pass, to its `<copy>` line):

| Module | Step | The log says | Windows (5 builds) | WSL (8 builds) |
| --- | --- | --- | --- | --- |
| `javafx-jslc` | `compile`, `testCompile` | `Nothing to compile - all classes are up to date.` | – | – |
| `javafx-base` | `compile` | `Recompiling the module because of changed source code.`, `Compiling 314 source files` | 7.6 to 10.9 s | 6.5 to 7.2 s |
| `javafx-base` | `compile-shims` | `[javac] Compiling 1 source file to …/target/shims/javafx.base` | – | – |
| `javafx-base` | `testCompile` | `Recompiling the module because of changed dependency.`, `Compiling 255 source files` | 13.1 to 16.3 s | 10.6 to 11.4 s |
| `javafx-graphics` | `compile` | `Recompiling the module because of changed dependency.`, `Compiling 1618 source files` | 24.5 to 33.5 s | 17.7 to 19.2 s |
| `javafx-graphics` | `jsl-codegen`, the second pass | `[javac] Compiling 1868 source files to …/target/mods` | 23.8 to 32.5 s | 19.4 to 20.3 s |
| `javafx-graphics` | `jsl-codegen`, its `<copy>` | `[copy] Copying 3538 files to …/target/classes` | 4.2 to 7.0 s | 0.5 to 0.7 s |
| `javafx-graphics` | `compile-shims` | `[javac] Compiling 2001 source files to …/target/shims`, then its copies | 26.4 to 31.2 s | 19.5 to 26.0 s |
| `javafx-graphics` | `testCompile` | `Recompiling the module because of changed dependency.`, `Compiling 766 source files` | 26.3 to 34.9 s, to the end of the build | 20.5 to 29.3 s |

- Together these steps took 131 to 162 s of the Windows builds, whose Maven totals were 02:37 to 03:17 min, and 97 to
  113 s of the WSL builds, 1:44 to 2:01 min. The baseline's builds of 2026-11-17 took 03:11 to 03:40 min on Windows
  and 02:08 to 02:50 min in WSL.
- Before each `javafx-base` compile, `process-version-info` logs `Copying 1 resource from src/main/version-info to
  target/gensrc/java/com/sun/javafx/runtime`; the `resources` steps log `Copying 2 resources` (`javafx-jslc`) and
  `Copying 81 resources` (`javafx-graphics`), the `testResources` steps `Copying 696 resources` (`javafx-base`) and
  `Copying 696`, `4188` and `257 resources` (`javafx-graphics`). Whether these copies rewrite their files was not
  measured.
- The same in every build: US-024's change neither causes nor changes it.

#### Read
- `modules/javafx.base/pom.xml`: the `process-version-info` execution (`:81`) copies `src/main/version-info` (`:95`)
  into the module's `target/gensrc`; `compile-shims` (`:126`).
- `modules/javafx.graphics/pom.xml`, the `jsl-codegen` execution (`:114`): the second pass is an Ant `<javac>` with
  `--module-source-path` into `target/mods` (`:205-225`), and a `<copy>` puts `target/mods/javafx.graphics` over
  `target/classes` (`:226-228`). `compile-shims` (`:254`) is an Ant `<javac>` into `target/shims` (`:262-293`) and
  copies. The `compile` and `testCompile` steps are the compiler plugin's.
- Why each step compiles again was not investigated, and no step was run on its own.

### Requirement
A build without `clean` of `-pl buildtools/jslc,modules/javafx.graphics -am` to `test-compile`, after a build of the
same tree in which no source, resource or build file changed, compiles no Java source and rewrites no file of
`target/classes`, `target/mods`, `target/shims`, `target/test-classes` and `target/gensrc` of `javafx-base` and
`javafx-graphics`. A build without `clean` after a change compiles what the change needs, and its outputs equal those
of a clean build of the same tree.

### Constraints
- **Clean builds unchanged.** A clean build writes the same files as today, identical by name and md5, in
  `target/classes`, `target/mods`, `target/shims`, `target/test-classes`, `target/jsl-compilers` and `target/gensrc`
  of both modules.
- **Nothing stale.** No output of a build without `clean` is kept that a clean build of the same tree does not make:
  the classes of a deleted source must not come back (US-121), and a shader or peer that US-024's forced generators
  rewrite reaches `target/classes`.
- **The generated files and the gates stay as they are.** The generator steps run as US-024 left them, every program
  compiled in every build and an output written only when its bytes change; a clean build's `target/gensrc` is
  identical to `US-023-windows-gensrc.md5` and `US-023-wsl-gensrc.md5`; the gates' commands are those of US-024 and
  give its per-class lists.
- **Code rules.** The poms keep their line ends (the graphics pom is CRLF throughout); new lines at most 120 columns,
  no tab.

### Acceptance criteria
1. **Nothing compiled, nothing rewritten.** In a fresh clone, after a clean build with the command of "Measured", three
   builds without `clean` and without changes, on Windows and in WSL. No log line of `javafx-base` or
   `javafx-graphics` reports a compile (`Compiling N source file` from the compiler plugin or from `[javac]`), and a
   snapshot (path, modification time, md5) of `target/classes`, `target/mods`, `target/shims`, `target/test-classes`
   and `target/gensrc` of both modules is the same before and after each build. Mutant proof: the same on the commit
   before the change gives the compile lines of "Measured".
2. **A change is built.** After the builds of criterion 1, each of these edits and a build without `clean`: a comment
   changed in a `.java` file of `javafx-graphics`; the same in `javafx-base`; a generator or `.jsl` edit that changes
   generated files (US-024's alpha-test edit of `modules/javafx.graphics/src/main/jsl-prism/CompileJSL.java:395`, or
   its edit of `ColorAdjust.jsl:144`). The five directories of criterion 1 then equal those of a clean build of the
   edited tree by name and md5.
3. **Nothing comes back.** US-121's case, US-021's 11 files restored, built, deleted and built again without `clean`:
   the change leaves no class of them that the commit before it does not also leave; if US-121 is done, its criteria
   still pass.
4. **The gates.** `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test` on Windows (with
   `-Djfx.parity.require=true`) and in WSL give per-class lists equal to the "after" columns of
   `US-024-windows-per-class.tsv` and
   `US-024-wsl-per-class.tsv`, and `target/gensrc` identical by name and md5 to
   `US-023-windows-gensrc.md5` and `US-023-wsl-gensrc.md5`. `submit.yml` is green.
5. **Time recorded:** the Maven totals of criterion 1's builds, before and after, on both hosts.

### Out of scope
- The time of the generator steps, which compile every program in every build by US-024's choice.
- The classes of a deleted source that a build without `clean` keeps today: US-121.
- A build that stops at the compile phase from a fresh clone: US-127.
- Modules other than `javafx-base` and `javafx-graphics`, and the native build, which these measurements skipped.

### Definition of Done
- All acceptance criteria met, with the logs and snapshots kept with the change and the results stated here.
- An independent review repeated criteria 1 and 2.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

### Notes
- Found by US-024's independent review of 2026-11-17 to 2026-11-18, in its WSL builds without `clean`; the other
  steps are in the lists of what ran in US-024's final timed builds on Windows, which equal those of the baseline's.
- US-121 is about outputs that a build without `clean` keeps, this story about outputs that it makes again; neither
  fix may undo the other.
- US-024's two Windows builds with natives of 2026-11-17 without changes spent 8.2 and 6.7 s in CMake and compiled no
  shader object (US-024's record).

## Part 2 — Keep the classes of a deleted source out of a build without `clean` (was US-121)

**Status:** 📋 Ready (filed 2026-11-15 from US-021) · **Found:** 2026-11-15, US-021's independent review (finding 5),
in a clone of `7a66c85e39` built on macOS; the pom re-read for this story the same day, nothing built or run for it

### Story
As a developer who deletes or renames a source file of `javafx.graphics` or of its shader generators, and builds
without `clean`,
I want the build's outputs to hold no class of the deleted source,
so that the build compiles, tests and packages the classes the sources have, and none that only an earlier build made.

### Problem
Paths are relative to `modules/javafx.graphics`. `pom.xml` is its pom, cited with US-024's change (amended
2026-11-21; with US-023's change alone, the lines cited here from `:164` to `:198` are 3 lower and those from `:200`
on 4 lower. Amended 2026-11-17: as filed it was cited at `7a66c85e39`, where the lines that are after `:178` with
US-023's change are 7 higher).

#### Measured
US-021's independent review (2026-11-15; macOS 27.2.1 arm64, Maven 3.10.0, JDK 27.0.1.0.2) built a third clone at
`7a66c85e39` with `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am test-compile -DskipNative=true`,
deleted US-021's 11 files and ran the same `test-compile` again. Both builds end in BUILD SUCCESS. After the second:
- `com/sun/scenario/effect/ZoomRadialBlur.class` and `com/sun/scenario/effect/impl/state/ZoomRadialBlurState.class`,
  whose sources are deleted, are still in each of `target/classes`, `target/mods/javafx.graphics`,
  `target/shims/javafx.graphics` and `target/test-classes`;
- `target/jsl-compilers/decora` still holds 11 classes, those of the 4 deleted drivers among them, where a clean build
  of the same tree holds 7.

The second build's log shows the second-pass `javac` compiling 1,868 sources into `target/mods` and the `<copy>`
copying 3,540 files into `target/classes`. A clean build of the tree without the 11 files copies 3,538 (US-021's
implementation build).

#### Read
- The second-pass `javac` writes `target/mods` (`pom.xml:203-225`), and a `<copy>` puts `target/mods/javafx.graphics`
  over `target/classes` (`:226-228`). Nothing removes a file from `target/mods`, so a class whose source is gone stays
  there, and the copy puts it back into `target/classes` (the review's reading).
- The generator `javac`s write `target/jsl-compilers/decora` and `target/jsl-compilers/prism` (`:126-138`,
  `:170-179`), `compile-shims` writes `target/shims` (`:262`), and the `testResources` (`:86-98`) copy the shim
  directories into `target/test-classes`. The pom has no `<delete>` step.
- Nothing in the `jsl-codegen` execution (`:108-231`) removes a file from `target/gensrc`: it makes directories, runs
  the generator `javac`s, the generators and the second pass, and copies (read, not measured).
- Other modules' builds stage the graphics shims too: in the main checkout, built before US-021's deletion, the two
  `ZoomRadialBlur` classes are also in the `target/test-classes` of `javafx.controls` and `javafx.fxml` (the review)
  and of `jfx.incubator.input` and `jfx.incubator.richtext` (a `find` on 2026-11-15).

#### What it means
After a source file is deleted, a build without `clean` compiles, tests and can package classes that the sources no
longer have, and a test can pass only because such a class is still there. US-019's, US-020's and US-021's gates
build with `clean` in fresh clones and are unaffected; gates elsewhere in the backlog that build without `clean`, such
as US-059's and US-066's, were not checked. Not measured: Windows and Linux, and the jar and SDK of such a build.

### Requirement
After a source file of the module, or of a shader generator, is deleted, a build without `clean` leaves no class of it
in the module's outputs or in the generator class directories.

### Constraints
- A clean build writes the same files as today, identical by name and md5, in `target/classes`, `target/mods`,
  `target/shims`, `target/test-classes`, `target/jsl-compilers` and `target/gensrc`.
- `javafx.graphics` has the same test counts per class as before, and `javafx-jslc` runs its tests with no failure.
- The gates' commands of the open stories still work as written.

### Acceptance criteria
1. **No class of a deleted source.** In a clone of the change's commit, restore US-021's 11 files from `7a66c85e39`
   (`git checkout 7a66c85e39 -- <the 11 paths>`), build with the review's `test-compile` command, delete the 11 files
   again and build again without `clean`. `ZoomRadialBlur` is in no file of `target/classes`, `target/mods`,
   `target/shims` and `target/test-classes` (`command grep -rlF ZoomRadialBlur`, which also searches binary files),
   and `target/jsl-compilers/decora` holds the 7 classes of a clean build. Each of these directories then has the file
   list of a clean build of the same tree.
2. **The check can fail.** The same steps in a clone of the commit before the change give the measurement of
   "Measured": 8 class files and 11 generator classes.
3. **The constraints hold,** on the platforms the change is built on, which are recorded here; the CI run of the PR
   covers the others.

### Definition of Done
- All acceptance criteria met, with the logs and file lists kept with the change and the results stated here.
- An independent review repeated criteria 1 and 2.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

### Notes
- Found by US-021's independent review (finding 5). US-021's owner-run gates build from `clean` for this reason.
- Related to [US-024](US-024-regenerate-shaders-when-the-jsl-tools-change.md), which is about regenerating the shaders
  and peers when the JSL compiler or a generator changes, not about removing the classes of a deleted source.
- Outside this story: the peers and shaders generated into `target/gensrc` from a deleted `.jsl` file, and the
  classes compiled from them. That case was not measured, because none of the `.jsl` files US-021 deleted generated a
  file.
- US-024 (amended 2026-11-21): the build now forces both generators, and a forced run rewrites what the generators
  produce and deletes nothing. So the outputs of a deleted `.jsl` file, or of a generator change that drops or renames
  an output, still stay in `target/gensrc` until `clean`, and the second-pass `javac` still compiles them (US-024,
  [Known limits](US-024-regenerate-shaders-when-the-jsl-tools-change.md#known-limits); read from the code by its
  review, not measured).
