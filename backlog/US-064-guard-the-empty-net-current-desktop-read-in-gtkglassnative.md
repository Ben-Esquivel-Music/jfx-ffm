# US-064 — Guard the empty `_NET_CURRENT_DESKTOP` read in `GtkGlassNative.currentDesktop`

**Status:** 📋 Ready (from the US-015 review's reading of the code; no further investigation) ·
**Found:** 2026-10-19, US-015 review

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
