# US-070 — Split `WinGlassNative` into aggregate classes of at most 900 lines

**Status:** ✅ Done (2026-10-28; landed 2026-10-28 as `14d7e2a18d`, PR #43; filed 2026-10-27, owner request). Actions
run 40718289172 on the PR's head `df067e6d23`, which has the tree of `14d7e2a18d`, is green in all five jobs, among
them `windows_x64_build` (AC 6, third bullet).
`WinGlassNative` stays the entry point and aggregates eighteen package-private classes, 19 files with the largest at
852 lines (`WinGlassNative`; the largest aggregate is `WinMenuCalls`, 751); the split is behaviour-neutral, the
targeted run of the 24 `test.com.sun.glass.ui.win` classes and the full module run on Windows give the same counts per
class as `e9d25742bc` with no crash file, and `ffm-reviewer` signed off (AC 7). Evidence:
[US-070-windows-gate.md](US-070-windows-gate.md) and
[US-070-windows-full-per-class.tsv](US-070-windows-full-per-class.tsv) ·
**Found:** 2026-10-23, the US-066 survey · The story states the requirement; the implementer designs the split, as
for US-066

## Story
As a maintainer of the Glass Windows binding,
I want `WinGlassNative` and every class it aggregates to be at most 900 lines (`wc -l`),
so that the binding is reviewable.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/java/com/sun/glass/ui/win`. A bare `:N` is a line of
`WinGlassNative.java` at `e9d25742bc`.
- `WinGlassNative.java` is 9,223 lines (9,150 when US-066 listed it). It holds 153 `static final MethodHandle`s
  and one `upcallStub` helper (`:2269`) with 8 callers. Its class javadoc alone is 257 lines (`:65-321`).
- Cutting at the existing banners is not enough. Two blocks are over 900 lines on their own: the constants,
  layouts, handles, holders and bind helpers (`:322-2354`, about 2,030 lines) and UI Automation (`:7547-9223`,
  about 1,680 lines). The clipboard, drag-and-drop and dialogs block (`:6642-7546`) is about 900.
- Today the single class guarantees the following, and any split must keep them:
  - **ABI guard first.** `gwin_abi_version` is bound and checked before any other `gwin_*` symbol (`:1177-1183`).
  - **Lazy holders keep their load point and blast radius.** `Winmm` (`:1936`), `Gdi32` (`:2009`), `Shlwapi`
    (`:2083`), `Shcore` (`:2122`) and `UiaCore` (`:9202`) bind on first use. The C delay-loaded `winmm` and
    `shlwapi`. A DLL that cannot be resolved costs only its own feature, never a `NoClassDefFoundError` for the
    whole of `WinGlassNative`.
  - **No class-init cycle.** Initializers run on the FX thread and on a winmm worker thread that nothing attaches
    (`onTimerFired`). A cycle reached from two threads can deadlock on class-init locks.
    The clipboard and drag-and-drop tables are installed last in the initializer (`:7536-7545`), so that no stub
    target can read a field that is not initialized yet.
  - **One symbol list.** `BOUND_SYMBOLS` (`:1146`) backs `boundSymbols()`. `WinGlassNativeTest` forces the
    holders in `@BeforeAll` and asserts the list as an exact ordered equality.
  - **Upcalls.** Every callback table keeps its stubs in `Arena.global()`, allocated once, with its install and
    reinstall paths: application, preferences, menu, view and gesture, window, screen, clipboard, drag-and-drop,
    accessibility and text range. The monitor-enumeration stub stays per call, in its own arena (`:6404`).
    No upcall target lets a `Throwable` out. The timer registry keeps its guarding: `TIMERS` (`:3846`) and
    `TIMER_LOCK` (`:3863`).
  - **Restricted calls stay in `WinGlassNative`**, as its javadoc (`:65-71`) and `jni-to-ffm-migration` §2 rule 2
    require. Handles stay `static final`.

## Acceptance criteria
1. **900 lines.** `WinGlassNative.java` and each class it aggregates are at most 900 lines by `wc -l`, licence and
   javadoc included. Tests and shims are exempt (`WinGlassNativeShim` is 5,370). `WinAccessible.java` (2,445) is
   not part of the facade and is not in scope.
2. **Aggregation shape.** `WinGlassNative` stays the entry point that the peers call. The aggregate classes are
   package-private in `com.sun.glass.ui.win`, and none is named `*Native`.
3. **The guarantees under "Problem" hold.** `boundSymbols()` returns the same symbols, none of them bound twice.
   If their order changes, `WinGlassNativeTest`'s expected list changes in the same commit, and the reason is
   recorded here.
4. **Behaviour-neutral.** No descriptor, layout, `gwin_*` export, arena, ownership or threading change.
   `GLASS_WIN_ABI_VERSION` is unchanged.
5. **No cramming, no restating comments**, as in US-066 AC 5.
6. **Tests.** On the same Windows host, before and after:
   - every class of `test.com.sun.glass.ui.win` (24 test classes) has the same per-class counts. Count them
     from the Maven log, not the surefire XML.
   - the full module run has no failure or error that the run before the change does not have, and leaves no
     `hs_err_pid*` file.
   - `windows_x64_build` of `.github/workflows/submit.yml` is green.
7. **Review.** `ffm-reviewer` signs off.

## Verification
```
W=modules/javafx.graphics/src/main/java/com/sun/glass/ui/win
# Prints only WinAccessible.java
wc -l $W/*.java | awk '$2 != "total" && $1 > 900'
```
On native Windows (WSL skips these tests), before and after:
```
mvn -pl modules/javafx.graphics -am test "-Dtest=test/com/sun/glass/ui/win/**" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl modules/javafx.graphics -am test
```

## Out of scope
- `GtkGlassNative` (US-071) and the other facades that US-066 lists.
- Moving the native `gwin_test_*` hooks out of the shipped glass.dll (US-068).
- Behaviour fixes found during the split. Each one gets its own story.

## Definition of Done
- All acceptance criteria are met, and the before and after per-class counts are kept with the change.
- The status is set to ✅ Done with the date and the PR, and the row moves to the done table of `backlog/README.md`.
- The changes stay in the working tree or a review branch. **No commits, merges or PRs without explicit approval
  from the repository owner.**

## Resolution
- **`BOUND_SYMBOLS` order (AC 3).** Aggregates bind their own handles when they initialize, so `boundSymbols()`
  lists the facade's three symbols (`gwin_abi_version`, then `gwin_clipboard_set_callbacks` and
  `gwin_dnd_set_callbacks`, which its initializer needs), then each aggregate's symbols in their original relative
  order, aggregates in the order of `WinGlassNative.ensureLoaded()`'s list, then the lazy holders as before. The order
  is deterministic because every win test class's `@BeforeAll` calls `WinGlassNatives.require()`, which runs
  `ensureLoaded()`, before anything else uses the binding. Same set, none bound twice; `WinGlassNativeTest`'s
  expected list changes with it in this change.
- **A missing eager export** now fails only the aggregate that binds it (`UnsatisfiedLinkError`, then
  `NoClassDefFoundError`), not the whole facade. No working build can observe this: every bound user32, kernel32 and
  ole32 export exists on every supported Windows; `GLASS_WIN_ABI_VERSION` fixes glass's export set, so only a broken
  glass.dll lacks one; `WinGlassNatives.require()` still fails on such a build, because `ensureLoaded()` initializes
  every aggregate; and the toolkit still fails at startup for the aggregates `WinApplication.<clinit>` installs. An
  ABI mismatch still fails every entry point as before.
- **Constants.** The 33 constants the peers name moved to the aggregate that uses them, and five peers re-qualify
  them; every method a peer calls stays a `WinGlassNative` delegation (AC 2).
- **Where the result differs from the text under "Problem"**, each behaviour-neutral:
  - The facade's initializer reaches two classes, `WinClipboardCallbacks` and `WinCallSupport`, whose own
    initializers reach nothing: the clipboard and drag tables stay installed by its last static initializer, because
    a drop target is registered as soon as a window exists (`GlassWindow::Create` -> `InitDropTarget`,
    `GlassDnD.cpp:57`) and can be dialled before any clipboard class is touched.
  - The preferences stub lives in the caller's `stubArena` (the application arena, or `Arena.global()` when
    embedded in SWT), not in `Arena.global()`, unchanged from the original.
  - Each callback table's install methods lock their own class instead of `WinGlassNative.class`; no state is shared
    between two tables, and nothing relied on serializing them.
  - `Shcore.load` reaches `SymbolLookup.libraryLookup` through the facade's `systemLibrary`, so the restricted call
    stays in `WinGlassNative`.
  - The `static_assert` messages and comments of `glass_win_api.cpp` that named moved members as `WinGlassNative.X`
    now name the owner class (strings and comments only), and `glass_win_api.h:357`'s false "FFM has no char layout"
    reason now gives the C type.

## Notes
- Other open stories edit `WinGlassNative.java`: US-049, US-052, US-053 and US-068. Whichever lands second rebases.
  The split is mechanical, so it is the cheaper one to redo.
- Related: US-066 (the pattern and the rule text), US-031 (the Rust port of the C behind `gwin_*`, which this split
  does not touch).
