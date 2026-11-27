# US-127 — Make a build to the compile phase pass from a fresh clone

## In short
- **Problem:** `mvn ... clean compile` fails in a fresh clone. Maven wants the test jar of `javafx-base`, which a
  build that stops at `compile` never makes. Stopping at `test-compile` or later works.
- **Seen failing?** Yes, measured on Windows.
- **Goal:** A build to the `compile` phase passes from a fresh clone, without anything installed in `~/.m2`.
- **Done when:** The command passes on Windows and WSL in a fresh clone with no `org.openjfx` artifacts in the local
  repository.
- **Order:** Can start now. Developer convenience; the workaround is to build to `test-compile`.

**Status:** 📋 Ready (filed 2026-11-17 from US-023) · **Found:** 2026-11-17, while implementing US-023: the command of
its "One JVM" criterion; quoted from that story's logs and reports, one pom read for this story, nothing built for it

## Story
As a developer who builds this fork with Maven,
I want a build that stops at the compile phase to pass from a fresh clone,
so that `compile` works without this project's artifacts installed in the local repository, and never builds against
stale ones.

## Problem
### Measured (Windows 10, Temurin 27+35, Maven 3.10.0, 2026-11-17, main tree at `f5e137e15e`)
`mvn -B -ntp -X -pl buildtools/jslc,modules/javafx.graphics -am clean compile` exits 1. The reactor summary shows
`openjfx`, `javafx-jslc` and `javafx-base` SUCCESS and `javafx-graphics` FAILURE in 0.503 s, before any mojo of that
module ran (lines 7156, 7158 and 7163 to 7165 of its log):
```
[ERROR] javafx-graphics .................................... FAILURE [  0.503 s]
[ERROR] BUILD FAILURE
[ERROR] Failed to execute goal on project javafx-graphics: Could not resolve dependencies for project org.openjfx:javafx-graphics:jar:28-ea
[ERROR] dependency: org.openjfx:javafx-base:jar:tests:28-ea (test)
[ERROR]     Could not find artifact org.openjfx:javafx-base:jar:tests:28-ea in central (https://repo.maven.apache.org/maven2): The following artifacts could not be resolved: org.openjfx:javafx-base:jar:tests:28-ea (absent): Could not find artifact org.openjfx:javafx-base:jar:tests:28-ea in central (https://repo.maven.apache.org/maven2)
```
The log's tab at the start of the last line is shown as spaces. The run left a tracking file,
`~/.m2/repository/org/openjfx/javafx-base/28-ea/javafx-base-28-ea-tests.jar.lastUpdated`. The same command run again
with the US-023 change gave the same failure.

### What passes
- `test-compile` and every later phase with the same reactor: `-am clean test-compile -DskipNative=true` built all four
  modules on Windows and in WSL in every US-023 run without an injected fault, with and without `-X`, and the gates'
  `-am clean test` passed.
- In WSL, `mvn -B -ntp -X -pl buildtools/jslc,modules/javafx.graphics clean compile`, without `-am`, passed, but only
  because the local repository there holds a `javafx-base` 28-ea jar and its `tests` jar of 2026-08-31: the debug log
  resolves both from `~/.m2/repository/org/openjfx/javafx-base/28-ea/`, and the compile class path of
  `javafx-graphics` held that jar.
  The local repository on the Windows host holds only `.lastUpdated` markers there.

### Read
- `javafx-graphics` declares the `javafx-base` test jar as a test dependency (`modules/javafx.graphics/pom.xml:64-70`,
  `<type>test-jar</type>`, `<scope>test</scope>`).
- The whole reactor (`mvn compile` without `-pl`) was not run.

## Requirement
A build of the reactor, or of `-pl buildtools/jslc,modules/javafx.graphics -am`, that stops at the compile phase
passes from a fresh clone whose local repository holds none of this project's artifacts.

## Constraints
- The `test` phase and every later one keep working as today, with the same tests run.
- No artifact of this project is taken from the local repository while the reactor builds that artifact.
- Nothing is installed into `~/.m2` by the fix or by the build it makes pass.

## Acceptance criteria
1. **Compile from a fresh clone.** In a fresh clone, with `~/.m2/repository/org/openjfx` absent or holding only
   `.lastUpdated` markers, `mvn -B -ntp -X -pl buildtools/jslc,modules/javafx.graphics -am clean compile` passes on
   Windows and in WSL, and its debug log resolves no `org.openjfx` artifact from the local repository. Platforms and
   logs recorded here.
2. **Mutant proof.** With the fix undone in a scratch clone, the command of criterion 1 fails with the message above.
3. **Nothing else changes.** The module gates of US-023, `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am
   clean test` on Windows (with `-Djfx.parity.require=true`) and in WSL, give per-class lists identical to
   `US-023-windows-per-class.tsv` and `US-023-wsl-per-class.tsv`, and `target/gensrc` identical by name and md5 to
   `US-023-windows-gensrc.md5` and `US-023-wsl-gensrc.md5`. `submit.yml` is green. (Amended 2026-11-21: since US-024,
   which adds the jslc class `ForcedRunTest`, the per-class lists are to equal the "after" columns of
   `US-024-windows-per-class.tsv` and `US-024-wsl-per-class.tsv`; the `target/gensrc` lists are unchanged.)

## Definition of Done
- All acceptance criteria met, logs kept, results stated here.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- US-023's "One JVM" criterion is amended to `-am clean test-compile -DskipNative=true` for this reason; see its
  [Drift since filing](US-023-run-the-prism-shader-generator-once.md#drift-since-filing).
