# US-057 — Give `javafx.base` the same module version as the rest of the build

**Status:** 📋 Ready (filed 2026-10-02 from the HEAD runtime snapshot of US-011; observed with `javap -v` on
the `module-info.class` of a local build of 900c40e41a; read: `pom.xml:32`, `:63-79`,
`modules/javafx.base/pom.xml:121-129`, `modules/javafx.graphics/pom.xml:104-113`; the cause is not confirmed and other
modules were not checked) · **Found:** 2026-10-02, while snapshotting a runtime for the US-011 hardware check

## Story
As a JavaFX developer who builds this fork or links its modules into an image,
I want every JavaFX module of one build to carry the same module version,
so that `ModuleDescriptor.version()`, `jlink` listings and the recorded `requires` versions agree with each other
and with the version the build states.

## Problem
A local Maven build (`mvn -pl modules/javafx.graphics -am test`, 2026-10-02) produces:
- `module javafx.base@28-ea`;
- `module javafx.graphics@28-internal`, whose descriptor records `requires javafx.base` at `28-ea`.

Both module poms ask for the same version. Each passes `--module-version ${jfx.release.version.short}` to the
compiler (`modules/javafx.base/pom.xml:126-127`, `modules/javafx.graphics/pom.xml:109-110`). The root pom sets that
to `28-internal` for a local build, "matching the Gradle defaults" (`pom.xml:71-78`). `28-ea` is the project version
(`pom.xml:32`). So in `javafx.base` something passes the project version and wins.

A likely cause, not yet confirmed: `maven-compiler-plugin` passes its own `--module-version` from its `moduleVersion`
parameter, which defaults to `${project.version}`. The descriptor of `javafx.graphics` may come from a later `javac`
run of that module's build that passes `28-internal` itself (its pom has two more `--module-version` sites,
`:231` and `:289`).

### Who is affected
- Anyone who reads module versions: `java --list-modules` and `jlink` images built from these modules, and tools
  that compare the `requires` versions.
- Pixels, behaviour and tests are not affected.

## Acceptance criteria
- Every JavaFX module descriptor of a local build carries `${jfx.release.version.short}`, and so does every recorded
  `requires` version of a JavaFX module. A CI build carries the CI version, which `jfx.release.suffix` sets.
- A check guards it: a test or a build step that reads the descriptors of the built modules and fails on a mismatch.
- The cause is stated: which compiler invocation sets which version.

## Notes
- Release and publishing were left outside the Maven port's "complete" bar, so the maintainer may schedule this with
  the publishing work.
