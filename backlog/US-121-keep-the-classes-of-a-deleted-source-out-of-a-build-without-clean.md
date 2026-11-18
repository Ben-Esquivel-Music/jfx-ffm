# US-121 — Keep the classes of a deleted source out of a build without `clean`

**Status:** 📋 Ready (filed 2026-11-15 from US-021) · **Found:** 2026-11-15, US-021's independent review (finding 5),
in a clone of `7a66c85e39` built on macOS; the pom re-read for this story the same day, nothing built or run for it

## Story
As a developer who deletes or renames a source file of `javafx.graphics` or of its shader generators, and builds
without `clean`,
I want the build's outputs to hold no class of the deleted source,
so that the build compiles, tests and packages the classes the sources have, and none that only an earlier build made.

## Problem
Paths are relative to `modules/javafx.graphics`. `pom.xml` is its pom, cited with US-023's change (amended
2026-11-17; as filed it was cited at `7a66c85e39`, where the lines cited here after `:178` are 7 higher).

### Measured
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

### Read
- The second-pass `javac` writes `target/mods` (`pom.xml:199-221`), and a `<copy>` puts `target/mods/javafx.graphics`
  over `target/classes` (`:222-224`). Nothing removes a file from `target/mods`, so a class whose source is gone stays
  there, and the copy puts it back into `target/classes` (the review's reading).
- The generator `javac`s write `target/jsl-compilers/decora` and `target/jsl-compilers/prism` (`:126-138`,
  `:167-176`), `compile-shims` writes `target/shims` (`:258`), and the `testResources` (`:86-98`) copy the shim
  directories into `target/test-classes`. The pom has no `<delete>` step.
- Nothing in the `jsl-codegen` execution (`:108-227`) removes a file from `target/gensrc`: it makes directories, runs
  the generator `javac`s, the generators and the second pass, and copies (read, not measured).
- Other modules' builds stage the graphics shims too: in the main checkout, built before US-021's deletion, the two
  `ZoomRadialBlur` classes are also in the `target/test-classes` of `javafx.controls` and `javafx.fxml` (the review)
  and of `jfx.incubator.input` and `jfx.incubator.richtext` (a `find` on 2026-11-15).

### What it means
After a source file is deleted, a build without `clean` compiles, tests and can package classes that the sources no
longer have, and a test can pass only because such a class is still there. US-019's, US-020's and US-021's gates
build with `clean` in fresh clones and are unaffected; gates elsewhere in the backlog that build without `clean`, such
as US-059's and US-066's, were not checked. Not measured: Windows and Linux, and the jar and SDK of such a build.

## Requirement
After a source file of the module, or of a shader generator, is deleted, a build without `clean` leaves no class of it
in the module's outputs or in the generator class directories.

## Constraints
- A clean build writes the same files as today, identical by name and md5, in `target/classes`, `target/mods`,
  `target/shims`, `target/test-classes`, `target/jsl-compilers` and `target/gensrc`.
- `javafx.graphics` has the same test counts per class as before, and `javafx-jslc` runs its tests with no failure.
- The gates' commands of the open stories still work as written.

## Acceptance criteria
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

## Definition of Done
- All acceptance criteria met, with the logs and file lists kept with the change and the results stated here.
- An independent review repeated criteria 1 and 2.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.

## Notes
- Found by US-021's independent review (finding 5). US-021's owner-run gates build from `clean` for this reason.
- Related to [US-024](US-024-regenerate-shaders-when-the-jsl-tools-change.md), which is about regenerating the shaders
  and peers when the JSL compiler or a generator changes, not about removing the classes of a deleted source.
- Outside this story: the peers and shaders generated into `target/gensrc` from a deleted `.jsl` file, and the
  classes compiled from them. That case was not measured, because none of the `.jsl` files US-021 deleted generated a
  file.
