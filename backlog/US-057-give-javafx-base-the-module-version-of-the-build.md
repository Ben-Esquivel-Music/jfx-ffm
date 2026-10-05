# US-057 — Give `javafx.base` the same module version as the rest of the build

**Status:** ✅ Done (2026-10-04) · **Found:** 2026-10-02, while snapshotting a runtime for the US-011 hardware check

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

## Resolution (2026-10-04)
- **Correction to this story: it was not only `javafx.base`.** A local build before the fix gave `@28-ea` to nine of
  the ten named modules (`javafx.base`, `javafx.controls`, `javafx.fxml`, `javafx.media`, `javafx.swing`,
  `javafx.web`, `jdk.jsobject`, `jfx.incubator.input`, `jfx.incubator.richtext`). Only `javafx.graphics` carried
  `@28-internal`, and it recorded `requires javafx.base` at `28-ea`.
- **Cause (confirmed).**
  - `maven-compiler-plugin` 3.14.0 has a `moduleVersion` parameter (since 3.14.0, user property
    `maven.compiler.moduleVersion`, default `${project.version}` = `28-ea`; read from the plugin's
    `META-INF/maven/plugin.xml`). The plugin passes it to javac as its own `--module-version` after the
    `compilerArgs`, and javac keeps the last one. So every module pom's `compilerArgs` pair
    `--module-version ${jfx.release.version.short}` had no effect, and `default-compile` wrote `28-ea`.
  - `javafx.graphics` escaped only because its antrun `jsl-codegen` execution (compile phase) recompiles the whole
    module with an Ant `<javac>` that passes `--module-version ${jfx.release.version.short}` and copies the result
    over `target/classes`. It compiles against `modules/javafx.base/target/classes`, so it recorded the `28-ea` that
    `default-compile` had given `javafx.base`.
  - The Ant `<javac>` shim compiles (`compile-shims` in base, graphics, controls, fxml, swing, richtext, web) already
    passed the right version; they only feed the test runs.
- **Fix.**
  - Root `pom.xml`: `<moduleVersion>${jfx.release.version.short}</moduleVersion>` in the pluginManagement
    configuration of `maven-compiler-plugin`, with a comment giving the reason. The parameter exists only on the
    `compile` goal, so `testCompile` is unaffected.
  - The ten now-redundant `compilerArgs` `--module-version` pairs were removed from the module poms (and the
    compiler `<plugin>` blocks, or `jdk.jsobject`'s whole `<build>`, where they held nothing else). The Ant `<javac>`
    sites are kept, since the plugin parameter does not reach them.
  - `javafx.swt` builds no descriptor (an automatic module compiled by Ant), so it is out of scope.
- **Guard.**
  - New `buildtools/CheckModuleVersions.java`, a single-file program run with the JDK source launcher. It reads each
    `module-info.class` (directory or jar) with `ModuleDescriptor.read` and fails, naming the module, the field, the
    found and the expected version, when the module version or the recorded version of any `requires` of a JavaFX
    module (`javafx.*`, `jfx.*`, `jdk.jsobject`) is absent or differs from `${jfx.release.version.short}`. A missing
    descriptor fails too.
  - Root-pom profile `module-version-check`, activated in every project with a `src/main/java/module-info.java` (the
    ten modules; a new module is covered without a pom change). It runs the program at `process-classes` on
    `target/classes`, after the `javafx.graphics` overwrite, and at `verify` on the module jar. So it runs in
    `mvn -pl modules/javafx.graphics -am test` as well as in `mvn install`. Skip with
    `-Djfx.module.check.skip=true`.
- **Validation (Windows, JDK 26, Maven 3.9.14).**
  - `mvn -B -ntp clean install -DskipTests`: BUILD SUCCESS; the check ran 20 times (10 modules, classes and jar).
    Every descriptor in `modules/*/target/classes` and in `sdk/target/sdk/lib` is `@28-internal`, and so is every
    recorded JavaFX `requires` (graphics 1, controls 2, fxml 2, swing 2, media 2, input 3, richtext 4, web 5).
  - CI version: `mvn -B -ntp clean verify -DskipTests -DskipNative=true -Djfx.release.suffix=-ea -pl
    modules/javafx.base,modules/javafx.graphics,modules/javafx.controls -am`: BUILD SUCCESS, all three at `@28-ea`.
  - Negative controls: the program against `javafx.graphics` with expected `99-bogus` reports the module version and
    `requires javafx.base` and exits 1; against `javafx.swt` (no descriptor) it exits 1; and
    `mvn -B -ntp -pl modules/javafx.controls process-classes -Djfx.release.suffix=-ea -DskipNative=true` over
    up-to-date `28-internal` classes fails the build in `check-module-version` with three `ERROR:` lines.
  - `mvn -B -ntp -fae clean install` (the CI command): BUILD SUCCESS; jslc 159, base 5,510 (22 skipped), graphics
    25,905 (356 skipped), controls 9,445 (185 skipped), richtext 186, fxml 123 (3 skipped), media 33 (2 skipped),
    0 failures, 0 errors; no `hs_err_pid*`. The working tree ends on the default `-internal` build.
  - An independent review of the diff found no significant issue.
- **Seen, not changed.** `javafx.swing` and `javafx.web` compile with `-source`/`-target` instead of `--release`
  (needed for `--upgrade-module-path`), so their descriptors record JDK `requires` at `26`, the JDK running the
  build, where the other modules record `25`. Those are JDK modules, outside this story.
