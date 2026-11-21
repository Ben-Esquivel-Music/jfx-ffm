# US-128 — Make a build without changes compile nothing again

**Status:** 📋 Ready (filed 2026-11-21 from US-024) · **Found:** 2026-11-17 to 2026-11-18, US-024's independent
review (its line outside the story: the second-pass `javac` compiles 1,868 sources in every build without `clean`);
the other steps read from the logs of US-024's timed builds, one pom search for this story, nothing built for it

## Story
As a developer who builds `javafx-base` and `javafx-graphics` again without `clean`,
I want a build in which nothing changed to compile no source and rewrite no output,
so that such a build does not spend minutes compiling what the build before it compiled, and a build after an edit
compiles what the edit needs.

## Problem
Paths are relative to the repository root. Line numbers are those of the tree with US-024's change.

### Measured
Timed builds without `clean` and without changes, each after a build of the same tree, with the command and the
`MAVEN_OPTS` of [US-023-build-runs.md](US-023-build-runs.md#commands):
`mvn -B -ntp -l <log> -pl buildtools/jslc,modules/javafx.graphics -am test-compile -DskipNative=true`. Temurin 27+35
and Maven 3.10.0; on Windows 3 builds at `38911806e8` (2026-11-17) and 5 with US-024's change (2026-11-21), in the
main tree; in WSL 3 builds at `38911806e8` (2026-11-17), then 4 at `38911806e8` and 4 with the change, alternating
(2026-11-21), in fresh clones ([US-024's record](US-024-build-runs.md#step-times-and-cost)). None of them wrote a file
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

### Read
- `modules/javafx.base/pom.xml`: the `process-version-info` execution (`:81`) copies `src/main/version-info` (`:95`)
  into the module's `target/gensrc`; `compile-shims` (`:126`).
- `modules/javafx.graphics/pom.xml`, the `jsl-codegen` execution (`:114`): the second pass is an Ant `<javac>` with
  `--module-source-path` into `target/mods` (`:205-225`), and a `<copy>` puts `target/mods/javafx.graphics` over
  `target/classes` (`:226-228`). `compile-shims` (`:254`) is an Ant `<javac>` into `target/shims` (`:262-293`) and
  copies. The `compile` and `testCompile` steps are the compiler plugin's.
- Why each step compiles again was not investigated, and no step was run on its own.

## Requirement
A build without `clean` of `-pl buildtools/jslc,modules/javafx.graphics -am` to `test-compile`, after a build of the
same tree in which no source, resource or build file changed, compiles no Java source and rewrites no file of
`target/classes`, `target/mods`, `target/shims`, `target/test-classes` and `target/gensrc` of `javafx-base` and
`javafx-graphics`. A build without `clean` after a change compiles what the change needs, and its outputs equal those
of a clean build of the same tree.

## Constraints
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

## Acceptance criteria
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
   [`US-024-windows-per-class.tsv`](US-024-windows-per-class.tsv) and
   [`US-024-wsl-per-class.tsv`](US-024-wsl-per-class.tsv), and `target/gensrc` identical by name and md5 to
   `US-023-windows-gensrc.md5` and `US-023-wsl-gensrc.md5`. `submit.yml` is green.
5. **Time recorded:** the Maven totals of criterion 1's builds, before and after, on both hosts.

## Out of scope
- The time of the generator steps, which compile every program in every build by US-024's choice.
- The classes of a deleted source that a build without `clean` keeps today: US-121.
- A build that stops at the compile phase from a fresh clone: US-127.
- Modules other than `javafx-base` and `javafx-graphics`, and the native build, which these measurements skipped.

## Definition of Done
- All acceptance criteria met, with the logs and snapshots kept with the change and the results stated here.
- An independent review repeated criteria 1 and 2.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Found by US-024's independent review of 2026-11-17 to 2026-11-18, in its WSL builds without `clean`; the other
  steps are in the lists of what ran in US-024's final timed builds on Windows, which equal those of the baseline's.
- US-121 is about outputs that a build without `clean` keeps, this story about outputs that it makes again; neither
  fix may undo the other.
- US-024's two Windows builds with natives of 2026-11-17 without changes spent 8.2 and 6.7 s in CMake and compiled no
  shader object ([US-024's record](US-024-build-runs.md#the-windows-builds-with-natives)).
