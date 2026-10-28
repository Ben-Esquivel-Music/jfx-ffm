# US-066 — Split `DWNative` into aggregate classes of at most 900 lines

**Status:** ✅ Done in the working tree (2026-10-27; filed 2026-10-23 from the macOS `CTNative` facade discussion of
US-003), uncommitted: the owner commits and pushes, and the PR number and the `windows_x64_build` CI result (AC 6,
third bullet) are added here then. `DWNative` stays the entry point and aggregates eleven package-private classes,
12 files with the largest at 820 lines; the split is behaviour-neutral, the 84 DirectWrite tests and the full module
run on Windows give the same counts as `e3b8b21e6d`, and `ffm-reviewer` signed off (AC 8). Evidence:
[US-066-windows-gate.md](US-066-windows-gate.md) and
[US-066-windows-full-per-class.tsv](US-066-windows-full-per-class.tsv) ·
**Found:** 2026-10-23, owner request · **Amended:** 2026-10-24, owner: the story states the requirement; the
implementer designs the split

## Story
As a maintainer of the DirectWrite font binding,
I want `DWNative` and every class it aggregates to be at most 900 lines (`wc -l`),
so that the binding is reviewable.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/java/com/sun/javafx/font/directwrite` unless they start
with `modules/`, `.claude/`, `.github/` or `backlog/`. Bare test classes are in
`modules/javafx.graphics/src/test/java/test/com/sun/javafx/font/directwrite`. A bare `:N` is a line of
`DWNative.java`.
- `DWNative.java` is 5,307 lines (`wc -l`), out of 8,413 for the whole package.
- The owner set the limit: "use java aggregation so that DWNative and aggregate classes are no more than 900 lines".
  They ruled on 2026-10-23: "Apply the 900-line limit to production code only".
- Cutting at the existing section banners is not enough: the Direct2D and WIC section alone is 922 lines.
- Today the single class guarantees six things, and any split must keep them:
  - **No initializer throws.** The first thread to touch `DWNative` may be the "Prism Font Disposer" daemon, whose
    loop does not catch `Error` (`:104-108`). `DWNativeTest.java:139-155` tests this.
  - **No class-init cycles.** A cycle reached from two threads (the disposer and the FX or render thread) can
    deadlock on class-initialization locks. On a single thread it can expose fields that are still null.
  - **What is lazy stays lazy.** Five nested holders initialize on first use: the `Sink`, `AnalysisTables` and
    `RendererTable` vtables (`:1697`, `:3191`, `:3900`), and `Ole32` (`:4663`) and `D2D1` (`:4696`), which load
    `ole32.dll` and `d2d1.dll` on the render thread.
  - **One handle cache, one symbol list.** `HANDLES` (`:336`) links each vtable shape once. `BOUND_SYMBOLS`
    (`:327`) backs `boundSymbols()`, and `DWRenderTest.java:301-311` asserts exactly 5 symbols, none bound twice.
  - **Restricted calls stay in the one `*Native` entry class.** The class javadoc says so (`:59-64`), and the rules
    require it (`.claude/skills/jni-to-ffm-migration/SKILL.md:176-184`,
    `.claude/skills/openjfx-conventions/SKILL.md:117`, `:150`).
  - **Handles stay `static final`.** C2 constant-folds `invokeExact` only through static finals.

## Acceptance criteria
1. **900 lines.** Every production file of the facade, `DWNative.java` and each aggregate class, is at most 900
   lines by `wc -l`, licence and javadoc included. Tests and shims are exempt. The line-count check under
   "Verification" prints nothing.
2. **Aggregation shape.** `DWNative` stays the entry point that production code calls. The aggregate classes are
   package-private, in `com.sun.javafx.font.directwrite`, and none is named `*Native`.
3. **The six guarantees hold** as listed under "Problem".
4. **Behaviour-neutral.** No descriptor, layout, slot, arena, ownership, reference-count or threading change.
5. **Comments and no cramming.**
   - Code carries a comment only for a non-obvious "why", never one that restates the code. The owner ruled on
     2026-10-23: "limited code commenting will help in the effort to not have thousands of lines of code. Code that
     explains itself is the best type of comment."
   - The 900 lines are never reached by putting several statements on one line, joining declarations or going over
     120 columns.
6. **Tests.**
   - The 84 DirectWrite `@Test` methods pass before and after on the same Windows host, with the same counts:
     `DWNativeTest` 21, `DWFontFaceTest` 20, `DWLayoutTest` 25 and `DWRenderTest` 14, plus
     `DirectWriteMetricsGoldenTest` 2 and `FontEnumerationGoldenTest` 2 in the parent package. All are
     `@EnabledOnOs(OS.WINDOWS)`.
   - The full module test run has no failure or error that a run before the change on the same host does not have.
   - The `windows_x64_build` job of `.github/workflows/submit.yml` is green.
7. **Skill text** (`grep -n 'US-066'` finds both edits): `.claude/skills/jni-to-ffm-migration/SKILL.md` §2 rule 2
   and the `native-font` row of `.claude/skills/jfx-graphics-native/SKILL.md` mention the aggregation and the
   900-line limit (US-066).
8. **Review.** `ffm-reviewer` signs off.

## Verification
On any host, in bash, from the repository root:
```
D=modules/javafx.graphics/src/main/java/com/sun/javafx/font/directwrite
# Nothing in the package is over 900 lines (prints nothing)
wc -l $D/*.java | awk '$2 != "total" && $1 > 900'
```

On a native Windows host with JDK 27 and Maven (PowerShell for Maven, Git Bash for `find`). WSL cannot run these
tests: it is a Linux JVM, so `@EnabledOnOs(WINDOWS)` skips them.
```
# Before and after: 84 run, 0 failures, 0 errors (the last flag only on the owner's golden machine)
mvn -pl modules/javafx.graphics -am test "-DskipNative=true" "-Dsurefire.failIfNoSpecifiedTests=false" `
    "-Dtest=DW*Test,DirectWriteMetricsGoldenTest,FontEnumerationGoldenTest" "-Djfx.parity.require=true"
# Full module tests before and after, then no crash files (prints nothing)
mvn -pl modules/javafx.graphics -am test
find modules/javafx.graphics -maxdepth 1 -name 'hs_err_pid*' -type f
find modules/javafx.graphics/target -name 'hs_err_pid*' -type f
```
A crashed fork writes its `hs_err_pid*.log` in the module directory (`.claude/skills/jfx-ffm-testing/SKILL.md:92-93`),
so the first `find` searches there.

## Out of scope
- **Other facades over 900 lines** (`wc -l`), each a possible story of its own: `WinGlassNative` 9,150,
  `GtkGlassNative` 6,019, `JfxMediaNative` 2,481, `WebPageNative` 2,301, `WebKitNative` 1,717, `ES2Native` 1,458,
  `D3DNative` 1,293, `LiveConnectNative` 1,013, the monocle `LinuxSystem` 1,132 and `X` 938, and the generated DOM
  bindings `DocumentNative` 2,638, `ElementNative` 2,352, `DOMWindowNative` 2,227 and `HTMLInputElementNative` 1,132.
- **The test side.** `DWNativeShim` (1,515) and `DWLayoutTest` (1,005) are over 900 lines but exempt, because the
  limit applies to production code only.
- **Behaviour fixes.** Any behaviour fix found during the split goes to its own story.

## Definition of Done
- All acceptance criteria met, with the Windows logs and the before/after test counts kept with the change.
- An independent review (`ffm-reviewer`) repeated the line-count check and the Windows targeted run.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- The owner, 2026-10-24: "Whoever solves it on a Windows OS is going to have to do the legwork to fix it. We're just
  documenting the requirement with that story."
- Related: US-003 (its Windows half produced `DWNative`; the limit came up in its macOS half); US-047 (it cites
  `DWNative`, `IDWriteFactory` and `JFXTextAnalysisSink` as the precedent for driving COM from Java).
