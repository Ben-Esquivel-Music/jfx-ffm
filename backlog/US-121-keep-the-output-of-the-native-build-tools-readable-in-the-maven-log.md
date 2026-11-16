# US-121 — Keep the output of the native build tools readable in the Maven log

**Status:** 📋 Ready (filed 2026-11-16 from US-027) · **Found:** US-027's native builds on Windows 10 and WSL Ubuntu;
quoted from its final gate of 2026-11-16, whose logs were re-read for this story; nothing built or run for it

## Story
As a developer whose native build fails,
I want the messages of cmake, cargo, the compiler and the linker to reach the Maven log as they wrote them,
so that I can read why the build failed without re-running the tool by hand.

## Problem
The native libraries are built by native tools that Maven starts through `exec-maven-plugin` (version 3.5.1, pinned
in the root `pom.xml`). In the Maven log a tool's standard output and standard error come out interleaved character
by character when both streams are written at about the same time. US-027's CMake helper says why, in its header
comment: exec-maven-plugin copies a child's two streams into the Maven log concurrently (not checked in the plugin's
source).

### Measured (US-027's final gate, 2026-11-16, logs of `mvn -B -ntp ...` with stdout and stderr to one file)
- **Windows, a configure that fails** (no Rust toolchain, `mvn -B -ntp -pl modules/javafx.media -am clean
  test-compile`): the helper's error, written to stderr, is mixed with cmake's own stdout line
  `-- Configuring incomplete, errors occurred!`. Three of the lines as the log has them:
  ```
    jfxmedia: its Rust slices need the-- Configurin Rust tooglchain 1.100.0 pinned in
    C:/SourceCode/jfx-ffm/rust-toolch ain.toml, aind no cargon or no rustcc is on
    PoATH or impln
  ```
  The next two lines mix the configure output's log path into the path the message names; the message's last
  paragraph is legible.
- **Linux, the same configure:** the message body is legible, but the header line before it,
  `CMake Error at <repository>/buildtools/rust/jfx_rust.cmake:369 (message):`, is mixed character by character with
  cmake's `-- Configuring incomplete, errors occurred!`; only its tail is whole:
  ```
  uildtools/rust/jfx_rust.cmake:369 (message):
  ```
- **Linux, a successful build:** the post-link lines of US-027's origin check (`jfx_rust: <target>: jfxm_abi_version
  is defined by ...`), printed by a `cmake -P` step between linker and compiler output, have 0 literal matches in the
  Maven logs of a Debug test run and of a full `verify`. In the Windows `verify` log the same lines are legible.
- **Earlier, during US-027's work:** the `test result:` lines of `cargo test` were unreadable in a Linux Maven log
  until US-027 merged cargo's two streams into one log file per step; a failing cargo step whose log was printed to
  stdout came out interleaved with the error that followed on stderr.

### What US-027 does about it today
US-027 works around the problem for its own steps only: every cargo call writes stdout and stderr into one file,
`<build tree>/jfx-rust/logs/<step>.log`, printed when cargo ends (to stdout after a success, to stderr after a
failure), and the origin check also writes `<build tree>/jfx-rust/logs/<target>-origin.log`. The price is that cargo's
progress appears only when the step ends. Nothing is done for cmake's own messages, the compiler or the linker.

### The executions concerned
Every `exec-maven-plugin` execution that runs a native build tool:
- `modules/javafx.graphics/pom.xml` and `modules/javafx.media/pom.xml`, in each of the profiles `native-win`,
  `native-linux` and `native-mac`: `cmake-configure`, `cmake-build`, `rust-test` and `rust-lint` (24 executions);
- `modules/javafx.web/pom.xml`, in the profiles `web-stub-native-win` and `web-stub-native-unix`:
  `wkjstub-cmake-configure` and `wkjstub-cmake-build` (the test stub library).

## Requirement
1. **Readable output.** The text that a native build tool started by one of these executions writes, on either
   stream, reaches the Maven log with no line mixed into another: each line of the tool's output appears whole.
2. **Every execution.** It holds for all the executions listed above, on Windows, Linux and macOS, for a success and
   for a failure.

## Constraints
- A failing tool still fails the build, at the same execution and with a non-zero Maven exit.
- The order of the lines within one stream is kept.
- A developer still sees progress while a long native build runs; output that appears only when a tool ends is
  acceptable only for steps as short as US-027's cargo steps are today, and US-027's merged per-step cargo logs stay
  or are replaced by something at least as readable (complete, ordered, in a file a developer can follow while the
  step runs).
- No compiler or linker warning is suppressed, and no tool's output is dropped.
- CI (`submit.yml`) and the `-DskipNative=true` behaviour are unchanged.

## Acceptance criteria
On Windows 10 and WSL Ubuntu, before and after the change, with the commands and the quoted lines recorded here:
1. **A failing configure.** With no Rust toolchain reachable, `mvn -B -ntp -pl modules/javafx.media -am clean
   test-compile` fails at `cmake-configure`, and the log holds the line `CMake Error at
   <repository>/buildtools/rust/jfx_rust.cmake:<line> (message):` and the helper's message as cmake printed them,
   each line whole (before the change: the garbled lines above, or others like them).
2. **A failing compile.** A syntax error put into one C++ source of `jfxmedia` makes the build fail, and the
   compiler's first error line appears whole in the log, with its file and line.
3. **A failing cargo step.** A syntax error in a Rust source of the `jfxmedia` crate makes the build fail, and
   rustc's `error:` line and its `-->` location appear whole.
4. **A successful build.** In a full `mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false"` on Linux, the
   origin lines of both `jfxmedia` targets appear whole (literal `grep` matches, before the change 0).
5. **Progress.** During a full build a developer sees compile progress in the log before the native step ends;
   recorded with timestamps of a few log lines.
6. **Nothing else changes.** The module totals and per-class test counts of the full build equal those before the
   change, and a failing tool still gives a non-zero Maven exit.
7. **CI.** `submit.yml` is green on all six jobs (`No JNI` and the five builds).

## Out of scope
The output of Java tools (`javac`, surefire, antrun steps such as the shader generators) and of `build-webkit.yml`.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Found while US-027's poms and native build were wired, and seen again in its final gate runs; it is the one story
  filed from US-027.
