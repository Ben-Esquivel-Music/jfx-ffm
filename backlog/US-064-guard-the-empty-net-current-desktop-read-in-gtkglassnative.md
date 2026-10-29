# US-064 — Guard the empty `_NET_CURRENT_DESKTOP` read in `GtkGlassNative.currentDesktop`

**Status:** ✅ Done (2026-10-29, uncommitted; the `nitems > 0` guard in `GtkScreenCalls.currentDesktop` and
`GtkScreenNativeTest.anEmptyCurrentDesktopIsDesktopZero`, with the mutant proof on a private Xvfb with and without
openbox, see "Done" below) · **Found:** 2026-10-19, US-015 review

## Story
As the maintainer of the GTK glass screen code,
I want `GtkGlassNative.currentDesktop` to read a `_NET_CURRENT_DESKTOP` item only when the property has one,
so that a well-typed but empty property never makes it read past what Xlib allocated.

## Problem
`GtkGlassNative.currentDesktop` (`modules/javafx.graphics/src/main/java/com/sun/glass/ui/gtk/GtkGlassNative.java`,
about lines 2053-2074) calls `XGetWindowProperty` for `_NET_CURRENT_DESKTOP` with `XA_CARDINAL` and, when
`type == XA_CARDINAL && format == 32`, reads `reinterpretLongs(data, 1).get(JAVA_LONG, 0)` without checking that
`nitems > 0`. For a property of type `CARDINAL`, format 32 and zero items, Xlib returns a non-null 1-byte allocation
(the trailing NUL it always adds), so the read takes 8 bytes from a 1-byte block.
- Since US-071 the method is `GtkScreenCalls.currentDesktop` (`GtkScreenCalls.java`, lines 246-269), unchanged.
- Inherited from the C: `get_current_desktop` of `glass_screen.cpp` at commit `033187ad90` did `ret = data[0]` under
  the same two conditions and no `num` check (confirmed in git history, `git show 033187ad90:...glass_screen.cpp`).
- Pathological: no window manager publishes an empty `_NET_CURRENT_DESKTOP` (EWMH defines it as one cardinal). It
  is a read of uninitialized heap, not a crash, in practice.

## Proposed fix
- One guard in `currentDesktop`: the branch also requires `num.get(JAVA_LONG, 0) > 0`; otherwise the desktop is 0,
  as for a missing property.
- A child-JVM test in `GtkScreenNativeTest` that sets a zero-item `CARDINAL` `_NET_CURRENT_DESKTOP` through the shim
  (`GtkGlassShim.restoreRootProperty` with a `RootProperty(XA_CARDINAL, 32, new long[0])` does exactly that; save the
  original with `readRootProperty` first and put it back in a `finally`, as US-015's scenario does) and expects
  desktop 0: with US-015's `_NET_WORKAREA` of two rectangles in place, the visible bounds are `WORKAREA_0`.
- Mutant proof: the test must fail with the guard removed (on a private Xvfb, `-DPRIVATE_DISPLAY=true`), and pass
  with it. Record both runs in this story.

## Acceptance criteria
- `currentDesktop` answers 0 for a zero-item `CARDINAL` `_NET_CURRENT_DESKTOP` and reads no byte of the data.
- The new test passes with the guard and fails without it (mutant proof recorded); every other
  `GtkScreenNativeTest` test is unchanged; the property is put back exactly (`xprop -root` before == after).
- No golden changes.

## Notes
- Evidence: the code reading in the US-015 review (2026-10-19); the C in git history as cited above. Nothing was run.
- Related: US-015 (the shim's `readRootProperty` / `restoreRootProperty` this test needs; its `savedWorkarea()`
  oracle already treats a zero-item saved desktop as 0, which is the behaviour this story makes the production
  code match).

## Done (2026-10-29, uncommitted)

### What changed
- `GtkScreenCalls.currentDesktop` (`GtkGlassNative.currentDesktop` before US-071): the `CARDINAL`/32 branch also
  requires `num.get(JAVA_LONG, 0) > 0`, so a property with no item is desktop 0, as a missing one is, and the `&&`
  stops before any byte of the data is read. The method's javadoc says so and cites the C of `033187ad90`. This is
  the only production change.
- `GtkScreenNativeTest.screensScenario`: after the out-of-range step (desktop 7, the whole screen), with the
  scenario's two-rectangle `_NET_WORKAREA` still in place, the child writes `RootProperty(XA_CARDINAL, 32,
  new long[0])` through `GtkGlassShim.restoreRootProperty` (`XChangeProperty` in replace mode with no element),
  records what `readRootProperty` reads back as `emptyDesktop.property`, and records the screens as
  `emptyDesktop`. The new test `anEmptyCurrentDesktopIsDesktopZero` pins the read-back to `type=6 format=32 items=`
  (present, `CARDINAL`, format 32, no item: a write that deleted the property would read `absent` and reach desktop
  0 through the missing-property path, without the guard) and expects the visible bounds of `WORKAREA_0`
  intersected with the monitor. The `finally` puts the saved property back as before. The class has 11 tests (was
  10); the child JVM, its options and its environment are unchanged.

### Measured (2026-10-29, WSL Ubuntu 26.04: glibc 2.43, libX11 1.8.13, GTK 3.24.52, rootless Xvfb 21.1.22 at 1920x1080x24; JDK 27 Temurin 27+35, Maven 3.10.0)
Fresh clones of master `2fd2bc900f`: unchanged ("master"), with this change applied by patch ("patched"), or with
the patch minus the guard ("mutant"). Each run started its own private Xvfb (`:99` or `:98`, never `:0`) and ran
`mvn -B -ntp -pl modules/javafx.graphics test -DskipNative=true -Dtest=test/com/sun/glass/ui/gtk/GtkScreenNativeTest
-Dsurefire.failIfNoSpecifiedTests=false -DPRIVATE_DISPLAY=true` on a session bus of its own without `DISPLAY`. In
every run `xprop -root _NET_WORKAREA _NET_CURRENT_DESKTOP` was byte-identical before and after (without a window
manager both absent; under openbox its `_NET_WORKAREA = 0, 0, 1920, 1080` four times and `_NET_CURRENT_DESKTOP =
0`), and no run left an `hs_err` file. Outcomes per test are from the surefire XML. Scripts and logs: the session
scratchpad `us064/`.

| run | tree | window manager | `GtkScreenNativeTest` | notes |
| --- | --- | --- | --- | --- |
| before (x2) | master | none; openbox | 10/10 in each | |
| after (x2) | patched | none | 11/11 in each | the ten as on master; `emptyDesktop.property=type=6 format=32 items=`, `emptyDesktop.0.visible=13,29,1600,800` |
| after | patched | openbox | 11/11 | the same |
| mutant (x5) | mutant | none | 10 pass, 1 fail in each | `anEmptyCurrentDesktopIsDesktopZero`: `expected: <13,29,1600,800> but was: <0,0,1920,1080>`; the read-back still `type=6 format=32 items=` |
| mutant (x3) | mutant | openbox | 10 pass, 1 fail in each | the same |
| mutant, diagnostic | mutant plus a print of what the read took (clone only) | none; openbox | 40 children in each, every one failing the test | see below |
| Windows | patched | - | - | `mvn -B -ntp -pl modules/javafx.graphics -am test-compile -DskipNative=true`: BUILD SUCCESS (the class is `@EnabledOnOs(LINUX)`) |

`git status` after the runs: the two Java files, this story and `backlog/README.md`; nothing under
`src/test/resources`.

### Does the mutant fail every time?
Without the guard the read takes 8 bytes from Xlib's 1-byte block, a 0x20-byte glibc chunk. The `(int)` keeps bytes
0-3 and byte 0 is Xlib's NUL, so the mutant's desktop is a multiple of 256, never 1, and it is 0 (the guard's
answer, a surviving mutant) only when bytes 1-3 are 0. Any other value is past the two rectangles, so the visible
bounds are the whole screen and the test fails.
- Measured: 8 of 8 plain mutant runs failed (table above). A diagnostic in the mutant clone printed the 8 bytes that
  the step's own read took and the desktop made of them, and ran the child 40 times per window manager in one
  surefire run: 80 of 80 children failed the test, and none read desktop 0 (80 distinct values, the smallest
  22,519,040 unsigned).
- Why: each of the 80 values is glibc's safe-linked free-list pointer, `(address >> 12) ^ next`, with `next` within
  386,560 bytes of the chunk. So the chunk was recycled from the thread's tcache or a fastbin, and that pointer is
  never 0, even at the end of a list (glibc 2.32 and later; CI's ubuntu-24.04 has 2.39, not measured there). That
  fits the code path: 13 confined-arena allocations of at most 24 bytes, the same chunk size, precede Xlib's in
  every `createScreen`, and the screen reads before the step freed theirs. Only a chunk cut from never-used memory
  could read 0.
- So the test design stays as it is: no second child and no allocator setting. glibc's perturb tunable would not
  help anyway, since it fills only the requested byte, which Xlib overwrites with the NUL.
