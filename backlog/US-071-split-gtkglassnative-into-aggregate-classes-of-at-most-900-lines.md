# US-071 — Split `GtkGlassNative` into aggregate classes of at most 900 lines

**Status:** 📋 Ready (filed 2026-10-27, owner request; US-066 listed it as out of scope) · **Found:** 2026-10-23,
the US-066 survey · The story states the requirement; the implementer designs the split, as for US-066

## Story
As a maintainer of the Glass GTK binding,
I want `GtkGlassNative` and every class it aggregates to be at most 900 lines (`wc -l`),
so that the binding is reviewable.

## Problem
Paths are relative to `modules/javafx.graphics/src/main/java/com/sun/glass/ui/gtk`. A bare `:N` is a line of
`GtkGlassNative.java` at `e9d25742bc`.
- `GtkGlassNative.java` is 6,019 lines, with 205 `static final MethodHandle`s and four nested classes:
  `PlatformSupport` (`:2166`), `Glass` (`:3756`), `UploadStaging` (`:4713`) and `Loader` (`:5638`).
- Cutting at the existing banners is not enough. Two sections are over 900 lines on their own: robot and key lock
  (`:641-1667`, about 1,030 lines) and the natives of the event code (`:4120-5614`, about 1,500 lines).
- Today the single class guarantees the following, and any split must keep them:
  - **One initialization point.** Every system library and symbol outside the two holders below is resolved when
    `link()` (`:181`) initializes the class. `link()` is called from the `GtkApplication` constructor, after
    `_queryLibrary` and the `glassgtk3` load and before `_initGTK` (javadoc `:98-104`, `:172-180`). A missing
    library or symbol must still fail the toolkit startup there with an `UnsatisfiedLinkError` that names it. It
    must never fail first, unreported, on the invoke-later thread or in the pulse timer. So an aggregate that binds
    a system library is initialized by `link()`: neither earlier nor lazily later.
  - **The two holders stay independent.** `Glass` binds the `ggtk_*` exports of `libglassgtk3.so` and checks
    `ggtk_abi_version` before anything else. The rest of the class can initialize without `glassgtk3`, as the
    headless tests do. `Loader` reads no field and calls no method of the enclosing class, and has its own linker,
    lookups and symbol census (`:5677`). A machine with no display therefore fails with the no-display
    `UnsupportedOperationException`, not with a GTK load error.
  - **No class-init cycle.** A cycle reached from two threads can deadlock on class-init locks. On one thread it
    can expose fields that are still null to a stub target.
  - **One symbol census.** `BOUND` (`:153`) records `library!symbol` in binding order.
    `GtkGlassNativeBindingTest`, `GtkGlassNativeHeadlessTest` and `GtkLibraryQueryTest` read it through
    `GtkGlassShim.boundSymbols()`.
  - **Upcalls.** The stubs are allocated once in `Arena.global()`, and the four callback tables
    (`CALLBACK_TABLES`, `:3740`) are installed once by `installCallbacks` (`:4000`). No upcall target lets a
    `Throwable` out (`reportException`, `:5903`).
  - **No `critical` downcall.** The frame uploads keep `UploadStaging` (javadoc `:126-131`).
  - **Restricted calls stay in `GtkGlassNative`**, as its javadoc (`:86-87`) and `jni-to-ffm-migration` §2 rule 2
    require. Handles stay `static final`.

## Acceptance criteria
1. **900 lines.** `GtkGlassNative.java` and each class it aggregates are at most 900 lines by `wc -l`, licence and
   javadoc included. Tests and shims are exempt (`GtkGlassShim` is 3,071).
2. **Aggregation shape.** `GtkGlassNative` stays the entry point that the peers call. The aggregate classes are
   package-private in `com.sun.glass.ui.gtk`, and none is named `*Native`.
3. **The guarantees under "Problem" hold.** `boundSymbols()` returns the same entries, none of them bound twice.
   If their order changes, the tests that pin it change in the same commit, and the reason is recorded here.
4. **Behaviour-neutral.** No descriptor, layout, `ggtk_*` export, arena, ownership or threading change. The
   `ggtk` ABI version is unchanged.
5. **No cramming, no restating comments**, as in US-066 AC 5.
6. **Tests.** Before and after:
   - the module run in WSL with `DISPLAY` unset has no failure or error that the run before the change does not
     have. This runs the headless GTK classes.
   - `.github/scripts/run-gtk-display-tests.sh` in WSL on a private Xvfb gives the same per-class counts for the
     36 test classes of `test.com.sun.glass.ui.gtk`, both with no window manager and under openbox. Count them
     from the Maven log.
   - `linux_x64_build` and `linux_aarch64_build` of `.github/workflows/submit.yml` are green, including their GTK
     display step.
7. **Review.** `ffm-reviewer` signs off.

## Verification
```
G=modules/javafx.graphics/src/main/java/com/sun/glass/ui/gtk
# Prints nothing
wc -l $G/*.java | awk '$2 != "total" && $1 > 900'
```
The WSL setup (rootless Xvfb, openbox started with `setsid`, `WAYLAND_DISPLAY` unset) is the one that US-015 and
US-016 used. The robot classes never run on WSLg's `:0`.

## Out of scope
- `WinGlassNative` (US-070) and the other facades that US-066 lists.
- The C behind `ggtk_*`, and the Rust port of its screencast code (US-037).
- Behaviour fixes found during the split, for example US-064 (`currentDesktop`). Each one gets its own story.

## Definition of Done
- All acceptance criteria are met, and the before and after per-class counts are kept with the change.
- The status is set to ✅ Done with the date and the PR, and the row moves to the done table of `backlog/README.md`.
- The changes stay in the working tree or a review branch. **No commits, merges or PRs without explicit approval
  from the repository owner.**

## Notes
- US-064 edits `GtkGlassNative.currentDesktop`. Whichever lands second rebases; the split is the cheaper one to
  redo.
- Related: US-066 (the pattern and the rule text) and US-001a (it produced `GtkGlassNative`).
