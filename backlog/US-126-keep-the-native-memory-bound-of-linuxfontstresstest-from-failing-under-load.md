# US-126 — Keep the native-memory bound of LinuxFontStressTest from failing under load

**Status:** 📋 Ready (filed 2026-11-17 from US-027) · **Found:** the WSL run of US-027's review-fix final gate,
2026-11-16; quoted from its logs and reports; nothing built or run for this story

## Story
As a developer who runs the javafx.graphics tests on Linux,
I want `LinuxFontStressTest` to fail only when native memory is retained per call,
so that a full gate on a busy machine does not stop at a test of unchanged code.

## Problem
`test.com.sun.javafx.font.LinuxFontStressTest` (Linux only) runs its layout, outline and shape loops in child JVMs
with a fixed, pre-touched 192 MB Java heap and the native heap trimmed every second (`MEMORY_JVM_OPTIONS`). Each child
reads the C heap in use (`mallinfo2`) and the resident set (`/proc/self/statm`) after a warm-up and at the end.
`assertNativeMemoryBounded` fails when the C heap grew by `MALLOC_GROWTH_BOUND` (2,097,152 bytes) or more, and records
the resident set without bounding it. The bound was measured on the JNI build of `7b43255b30`; its Javadoc notes that
the outline child takes its first reading without a settle pause.

### Measured (logs on file; nothing new run)
- **US-027's review-fix final gate, WSL step 2** (`mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false"
  -DJFX_RUST=false` in a clone of `924047c8b0` with the review fixes, 2026-11-16):
  `concurrentOutlinesMatchSingleThreadedOutlines` failed with `the C heap in use grew by 2380704 bytes over 80000
  outlines (bound 2097152): native memory is retained per call; the resident set changed by -978944 bytes over 80000
  outlines (recorded, not bounded)`, and `-fae` skipped every module after javafx.graphics. While the graphics tests
  ran, another project's Maven build and its test JVM ran on the Windows host, which had 3,712 MB of free memory.
- The class then passed alone in the same clone and environment (6/0/0/0, `mem.malloc.growth=-2149680`), in the same
  gate's Rust-on run and second Rust-off attempt, in US-027's earlier gate, and in the baseline on `7a66c85e39`.
- US-074 records the same bound failing on unchanged master code in a local WSL baseline run (its "load-sensitive
  graphics tests").
- javafx.graphics has no Rust slice and the run had `-DJFX_RUST=false`: US-027 did not cause it.

## Requirement
1. On unchanged code, the native-memory check of `LinuxFontStressTest` does not fail when the machine is under load
   (other processes taking memory and CPU while it runs).
2. It still fails when native memory is retained per call by the bindings its children exercise.

## Constraints
- Test-only: no production code changes.
- Linux only, like the test (`@EnabledOnOs(OS.LINUX)`).
- No weaker leak detection: a native leak injected per call into any child whose C heap is checked (layouts, outlines,
  shape) still fails its test. `LinuxFreetypeNativeTest` and `LinuxPangoNativeTest` keep their own checks.
- The resident set stays recorded in the test output.

## Acceptance criteria
On WSL Ubuntu, with the commands, the load and the counts recorded here:
1. **Under load.** The class, run repeatedly while another build or a memory-heavy process keeps free memory low,
   fails before the change (runs and failures counted) and never after it, with the same load and number of runs.
2. **A leak is still caught.** A mutant that retains native memory on every call of one child's measured loop, of a
   size the present bound catches, fails the test after the change; the restored code passes.
3. **Nothing else changes.** A full `mvn -B -ntp -fae clean verify "-Djfx.web.skipFfmTests=false"` on WSL gives the
   baseline's module totals and per-class counts, and `submit.yml` is green.

## Definition of Done
- All acceptance criteria met, logs kept, results stated here.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**
