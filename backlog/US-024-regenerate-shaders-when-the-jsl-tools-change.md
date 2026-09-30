# US-024 — Regenerate the shaders when the JSL compiler or a generator changes

**Status:** 📋 Ready (filed 2026-09-28; from measurements made while filing US-022 and US-023, on Windows, with the generators run outside Maven as the pom runs them; no fix prototyped, Maven not run) · **Found:** 2026-09-27, US-008 (filing US-023: a changed Prism generator, run on a complete output tree, rewrote none of the 1,059 files, where a clean run with it changes 318; filing US-022: a `.jsl` file restored with its older time was not compiled again)

## Story
As a developer who changes the JSL compiler, a shader generator or a `.jsl` file, and builds without `clean`,
I want every generated shader and peer that the change affects to be written again,
so that the module never packages shaders made by an older compiler or from an older source, and a before/after
comparison of the generated files compares what the new code writes.

## Problem
Paths are relative to `modules/javafx.graphics`. `JSLC.java` is
`src/jslc/java/com/sun/scenario/effect/compiler/JSLC.java`.
- `JSLC.compile` writes each output only when `jslcinfo.force` is set or `JSLC.outOfDate(outFile, sourceTime)` holds
  (`JSLC.java:193-244`), and `outOfDate` is `sourceTime >= outFile.lastModified()` (`:249-251`). The pom passes no
  `-force` (US-023).
- The source time is the time of the `.jsl` text only: the file's for the Decora single files (`JSLC.java:140-144`),
  the newer of the mask and paint files for a Prism program (US-023, `src/main/jsl-prism/CompileJSL.java:406-408`).
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
- **(c) Always `-force`.** Simplest. Every build rewrites every output, so every later step that compares times (the
  second-pass `javac`, the native shader compiles) repeats its work. Not measured.
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
- **Build gate,** with the two commands the US-008 gate ran:
  - Windows: `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics clean test -Djfx.parity.require=true`.
  - Linux (WSL), from a fresh clone with no display:
    `mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test`, without `-Djfx.parity.require=true`
    (the Linux font goldens were captured on another machine; see US-019's build gate).
  - `target/gensrc/{jsl-decora,jsl-prism,headers,mtl-headers}` is identical by name and md5 before and after, and
    `javafx.graphics` has the same test counts per class.

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
