# US-071 — Split `GtkGlassNative` into aggregate classes of at most 900 lines

**Status:** ✅ Done (2026-10-28; filed 2026-10-27, owner request; US-066 listed it as out of scope), pushed as
`6839f9b650`, PR #44 (open). Actions run 40875501864 is green in all five jobs, the GTK display step of both Linux
jobs included (AC 6, third bullet). `GtkGlassNative` stays the entry point
and aggregates fifteen package-private binding classes and the library query's holder: 17 files, the largest 744
lines (`GtkGlassNative`; the largest aggregate is `GtkCallbackTables`, 653). The split is behaviour-neutral: in WSL,
the module run with `DISPLAY` unset (911 test classes) and the GTK display suite with no window manager and under
openbox give the same counts per class as `14d7e2a18d`, with no crash file, and `ffm-reviewer` signed off (AC 7).
Evidence: [US-071-linux-gate.md](US-071-linux-gate.md),
[US-071-linux-headless-per-class.tsv](US-071-linux-headless-per-class.tsv) and
[US-071-linux-display-per-class.tsv](US-071-linux-display-per-class.tsv) · **Found:** 2026-10-23, the US-066
survey · The story states the requirement; the implementer designs the split, as for US-066

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

## Resolution
Built on `14d7e2a18d`, where `GtkGlassNative.java` is the same as at `e9d25742bc`.
- **The 17 files** (`wc -l`, 7,199 in total): `GtkGlassNative` 744, `GtkCallbackTables` 653, `GtkRobotCalls` 641,
  `GtkCallbackTargets` 634, `GtkClipboardCalls` 571, `GtkKeyCalls` 494, `GtkClipboardReadCalls` 391,
  `GtkWindowCalls` 369, `GtkPreferenceCalls` 358, `GtkDialogCalls` 350, `GtkApplicationCalls` 331, `GtkDndCalls`
  296, `GtkPixelsCalls` 293, `GtkLibraryLoader` 293, `GtkScreenCalls` 278, `GtkMainLoopCalls` 265, `GtkViewCalls`
  238. Every peer still calls `GtkGlassNative`, which delegates; `SetView` and `UploadStaging` stay nested in it, and
  `PlatformSupport` moved into `GtkPreferenceCalls`.
- **One initialization point.** The facade's initializer only opens the libraries. `link()` initializes the fifteen
  binding classes, in a fixed order, with `MethodHandles.Lookup.ensureInitialized`, and each class binds its
  handles in its initializer. So every system symbol still resolves inside `link()`, and a missing one is still an
  `UnsatisfiedLinkError` out of the `GtkApplication` constructor that names the library and the symbol (checked
  with a renamed `XTestFakeKeyEvent`). `link()`'s order is also the census order.
- **The two holders.** `Glass`, now nested in `GtkCallbackTables`, still binds the `ggtk_*` exports when
  `installCallbacks` first runs and checks `ggtk_abi_version` before anything else. The library query's body is
  `GtkLibraryLoader`, which opens libc and `libX11.so.6` only and reaches no class `link()` lists. The nested
  `GtkGlassNative.Loader` stays the peers' entry point (`QUERY_*`, `queryLibrary`) and holds the query's linker,
  census and two restricted calls. Its initializer reaches only JDK classes.
- **Restricted calls stay in `GtkGlassNative.java`.** The aggregates reach them through the facade's `bind`,
  `address`, `optional`, `upcallStub` (still `Arena.global()`) and `bounded` (`reinterpret`). There is no `critical`
  downcall, and `UploadStaging` is unchanged.
- **Accepted, behaviour-neutral differences:**
  - The four `static synchronized` install methods (`installCallbacks`, `callbacksInstalled`, `reinstallCallbacks`,
    `installedCallbackStubs`) moved together to `GtkCallbackTables` and now lock that class instead of
    `GtkGlassNative`. They share only `callbackStubs`, and nothing else synchronizes on either class.
  - A missing system symbol now fails the initializer of the aggregate that binds it, inside
    `GtkGlassNative.link()`, with the same `UnsatisfiedLinkError` out of the `GtkApplication` constructor.
    Afterwards that aggregate's methods throw `NoClassDefFoundError` instead of the whole facade's, which nothing can
    observe because the toolkit never starts.
- **Shim and tests.** `GtkGlassShim` reaches an aggregate by reflection only after `link()`, so no test changes the
  census order. `boundSymbols()` and `boundAddress()` link first, and `loaderBoundSymbols()` initializes
  `GtkLibraryLoader` first: at `14d7e2a18d`, reading either census bound what it read. Its direct references now
  name `GtkLibraryLoader`, `GtkCallbackTables`, `GtkCallbackTargets` and `GtkViewCalls`. `GtkCallbackTableTest`'s
  upcall-target classes are the four aggregates that own stubs. Comments in three more tests now name the binding
  instead of the facade. `SYMBOLS` and every test method are unchanged.
- **Comments elsewhere.** Four peers (`GtkApplication`, `GtkCommonDialogs`, `GtkSystemClipboard`, `GtkPixels`),
  `glass_gtk_api.h`, `GlassApplication.cpp`, `glass_screen.cpp` and `linux.cmake` now name the class that owns what
  they describe (comments only).
- **Neutrality**, from the sources and the bytecode:
  - The census has 142 entries, no entry twice, equal to `SYMBOLS` in the original order. `g_free`, bound at two
    sites as at `14d7e2a18d` (the `GtkPixelsCalls` address and the `GtkRobotCalls` handle), keeps its first position.
  - The class-initialization graph from `javap` is acyclic (24 classes).
  - No restricted call or `@SuppressWarnings("restricted")` is outside `GtkGlassNative.java`.
  - Every one of the original's 930 facts is still present: descriptors, census calls, constants, layouts, arenas,
    `reinterpret` sizes, upcall stubs, and the `synchronized` and `catch (Throwable` sites. The 8 added facts are
    the `link()` list, the four `QUERY_*` re-exports, `bounded`, and the `Loader`'s two restricted calls.
  - Token counts differ only for those: 24 `reinterpret` calls became `bounded`, which leaves 9 of the 21
    `@SuppressWarnings("restricted")`.
  - 88 members went from private to package-private, and none narrowed.
- **Acceptance criteria.**
  - AC 1: the line check prints nothing.
  - AC 2: entry point, package-private, no `*Native` name.
  - AC 3: the guarantees above, with the census order unchanged.
  - AC 4: no descriptor, layout, export, arena, ownership or threading change; the `ggtk` ABI version is unchanged.
  - AC 5: comment pass done and reviewed.
  - AC 6: bullets 1 and 2 met ([gate](US-071-linux-gate.md)); bullet 3 met: `linux_x64_build` and
    `linux_aarch64_build` are green in Actions run 40875501864, GTK display step included (the gate's "CI" section).
  - AC 7: `ffm-reviewer` signed off.
- **Skill.** `jni-to-ffm-migration` §2 rule 2 now names `GtkGlassNative` as the third worked example, and adds its
  shape: when the binding must resolve at one startup point, the facade's initializer opens only the libraries, and
  an explicit `link()` initializes the binding classes in a fixed order after it, which also fixes the symbol list's
  order.

## Notes
- US-064 fixes `currentDesktop`, which this split moved to `GtkScreenCalls`; its story points there.
- Related: US-066 (the pattern and the rule text) and US-001a (it produced `GtkGlassNative`).
