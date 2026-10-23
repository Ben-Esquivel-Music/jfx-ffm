# US-065 — Keep decorated GTK stage bounds right across full screen, maximize and the window manager's placement

**Status:** 📋 Ready (filed 2026-10-20 from US-017, decision D4; measured before and after US-017's fix, which left
`TITLED` windows unchanged) · **Found:** 2026-10-20, US-017 survey

## Story
As a JavaFX app developer whose app shows a decorated stage (`DECORATED`, `UNIFIED`, `UTILITY`) on Linux,
I want the stage's position and size to stay those of its window frame when it goes full screen, is maximized and
restored, and when it is first shown,
so that a full-screen stage is as large as the screen, a restored stage comes back where and as large as it was, and
a stage shown at (200, 200) is at (200, 200).

## Problem
All in `modules/javafx.graphics/src/main/native-glass/gtk/glass_window.cpp`, on the `TITLED` path, which US-017 left
as it was (its decision D2). Line numbers are those after US-017's fix.
- `update_frame_extents` (`:848-895`) ignores a `_NET_FRAME_EXTENTS` report that is all zero (`:859`). When the
  extents change, it stores them, writes the cache and calls `set_bounds` again with the stored position and size
  (`:891`).
- `process_configure` (`:1018`) takes a `TITLED` window's position from `gdk_window_get_root_origin` (`:1053`), which
  asks the X server at that moment.

Five faults come from these two. Measured in US-017 (2026-10-20, WSL, GTK 3.24.52; a private Xvfb 21.1.22 with
openbox 3.6.1 or i3 4.25.1, and WSLg `:0` with "Weston WM"); "after the fix" means with US-017's fix.

### 1. Full screen keeps the old extents
In full screen every WM reports 0, 0, 0, 0, which glass ignores, so the stage reports the screen plus the extents it
had before:

| WM | extents before | in full screen: client | glass `Window` |
| --- | --- | --- | --- |
| openbox | 1,1,20,5 | 0,0 1920x1080 | 0,0 1922x1105 |
| i3 | 2,2,18,2 | 0,0 1920x1080 | 0,0 1924x1100 |
| Weston | 38,38,59,38 | 0,0 1920x1080 | 0,0 1996x1177 |

After the fix: the same on Weston (probe) and under i3 (JDK-8348095's scenario, a full-screen stage at 0,0 1924x1100).

### 2. A change of extents while maximized resizes the maximized window (Weston)
Weston reports 6, 6, 27, 6 for a maximized window (border and title bar, no shadow margin). `update_frame_extents`
then calls `set_bounds` with the stored 400x300, so a `TITLED` window asked for at 400x300 ends maximized as a 400x300
frame at 0,0 (client 388x267) while `_NET_WM_STATE` still says `MAXIMIZED`. Glass's events: MAXIMIZE 1984x1104, then
400x300, 1920x1040, 400x300. The same after the fix. (Undecorated windows had it too; US-017 fixed that.)

### 3. The restore from maximized lands wrong
- openbox: a `TITLED` window at 200,200 400x300 ends at frame 0,0 1920x1080 (client 1,20 1918x1055) after
  `maximize(false)`. openbox first restores 200,200 400x300; 24 ms later a request from the client moves and sizes it
  again (glass's instrumented event log).
- Weston: the window comes back 64x64 smaller (twice the 32 px shadow margin) and 32 px up and left: client
  324x203 becomes 260x139, frame 168,168 becomes 136,136. The same after the fix. Why Weston restores it smaller was
  not established; the source of this Weston build was not read.

### 4. The startup placement race
`process_configure` can run while a reparenting WM has not yet placed its new frame, and then stores a provisional
frame origin. `update_frame_extents` then feeds that position back into `gtk_window_move`, so the window really moves
there.
- **openbox**, `TITLED` asked for at 200,200: openbox answers glass's `_NET_REQUEST_FRAME_EXTENTS` by briefly
  managing the unmapped window, so 1,1,20,5 are already on the client; glass handles the first configure while the
  client sits at 0,0 in an unplaced frame and stores 0,0 less (1, 20) = -1,-20; `update_frame_extents` calls
  `set_bounds(-1, -20, ...)`, and openbox puts the frame there. Result: client 0,0, glass -1,-20 400x300.
  - Before the fix: 3 of 4 runs with one window per freshly started openbox (survey); 0 of 4 later without load and
    1 of 6 under load (8 busy loops at nice 10, openbox at nice 19).
  - After the fix: 0 of 8 in those runs, but `GtkFrameExtentsNativeTest`'s `TITLED` window showed it in that class's
    run under openbox with the fix: shown at -1,-20 with its client at 0,0. So it still happens.
- **Weston**, `TITLED` asked for at 200,200: the frame ends at 136,136 instead of 168,168, so the visible window is at
  168,168 instead of 200,200. Glass's events: MOVE 168,168 at 166 ms, RESIZE 168,168 476x397 at 248 ms, MOVE 136,136 at
  265 ms. Quiet probe without load: 0 of 6; instrumented probe: 2 of 2; quiet under load (4 to 7 on 8 CPUs): 6 of 6,
  3 before and 3 after the fix, with identical values.
- **Undecorated windows** can still be hit after US-017 (its note d, openbox): their position is now `event->x`,
  `event->y`, and for a real ConfigureNotify that is GDK's own `XTranslateCoordinates`, a live read too, only taken
  earlier, when GDK reads the event. 3 of 10 runs before US-017 and 0 of 12 after, under the load above: narrowed, not
  removed by construction. Two candidate fixes would cover them together with `TITLED`: re-read the client's root
  origin at `GDK_MAP`, since openbox places its frame before it maps it; or order the configure events against
  ReparentNotify as AWT's `XDecoratedPeer` does (it keeps the reparent's serial, skips the ConfigureNotify that carries
  it and every one before the window is visible, and takes a synthetic one's position as it is).

### 5. On Weston a decorated stage reports its position 32 px from where it was put
US-017 decided (D3) to keep Weston's 32 px shadow margin in a decorated stage's bounds. Weston places the visible
window, not the frame with its margin, at the position `gtk_window_move` asks for, while glass reports the frame's
corner. Measured after the fix in `GtkFrameExtentsNativeTest`: `setPosition(150, 120)` puts the client at 156,147,
and glass reports 118,88 (the client less 38, 59), 32 px up and left of what was asked. A `setX(getX())` round trip
would then move the window by 32 px; that round trip itself was not measured.

## Fix options
1. **openjdk/jfx#2139's approach** (JDK-8354943; open, head `28ba295764`; read, not built). It keeps
   `gdk_window_get_root_origin` for a `TITLED` window's position, and:
   - `update_window_size` adds the extents only when the window is `TITLED` and its view is below the top
     (`view_position.y > 0`), so a full-screen window reports its client size although stale extents are kept
     (item 1);
   - `update_frame_extents` defers a change of extents while the window is maximized or full screen
     (`needs_to_update_frame_extents`) and applies it on the restore (items 2, 3);
   - `28ba295764` ("Fix JDK-8365201") targets a restore from maximized that exceeds the screen (item 3, reported on
     Muffin);
   - `process_configure` records a position only after the first map, and `move_resize` before the map (item 4,
     perhaps in part).
   Taking it means waiting for #2139 to land upstream and merging it, or backporting these parts onto the fork's
   `WindowContextTop`.
2. **Fork-local changes,** each with its own test:
   - accept an all-zero report for a `TITLED` window (EWMH's value in full screen) without writing it to the cache;
   - do not call `set_bounds` from `update_frame_extents` while the window is maximized or full screen;
   - do not feed the stored position back into `gtk_window_move` when only the extents changed: keep the position
     the application asked for, or take the frame position from the configure event less the extents instead of a
     live root-origin read;
   - item 5: decide whether a decorated stage on Weston may report a position other than the one it was put at, or
     whether `set_bounds` should add Weston's margin back (which D3 rejected as theme sniffing).

## Acceptance criteria
- **Decide** between merging or backporting #2139's parts and a fork-local fix, and record why. Keep D2's contract
  for `TITLED` (the stage is the WM's frame as the extents describe it) unless the decision changes it on purpose.
- **Measure each item before and after,** in the style of US-017's survey: openbox and i3 on a private Xvfb, Weston on
  WSLg `:0` (at most one short non-robot test class per Maven run there).
- **Item 1:** a full-screen `TITLED` stage reports 0,0 1920x1080 under openbox, i3 and Weston, and its old size when
  it leaves full screen.
- **Item 2:** a maximized `TITLED` stage on Weston stays at the maximized size when Weston changes the extents.
- **Item 3:** after `maximize(false)` a `TITLED` stage is back at the frame rectangle it had before, under openbox and
  on Weston.
- **Item 4:** a `TITLED` stage asked for at 200,200 is reported and placed with its frame at 200,200 under openbox in
  at least 10 runs with one window per fresh openbox under the load above, and on Weston.
- **Item 5:** decided and recorded; if it changes, measured on Weston.
- **Tests:** a child-JVM test (a new class, or more `TITLED` cases in `GtkFrameExtentsNativeTest`) for each item that
  can run on a private Xvfb, with a mutant proof; `GtkWindowNativeTest` and US-017's two classes stay green.
- **Gates** as in US-017: every `test/com/sun/glass/ui/gtk/*Test` class passes on a private Xvfb with no WM and with
  openbox, each with its counts from before apart from new tests; the JDK-8329821 and JDK-8348095 scenarios still
  pass under i3.

## Notes
- Evidence: US-017's "Resolution" (survey tables, "The openbox rows, explained", "All-zero reports and state
  changes", gates) and the session evidence it describes.
- Undecorated windows are in this story only through item 4. US-017 gave them zero extents and the configure event's
  position, which narrowed the openbox row of its note (d) (0 of 12 runs under load after the fix, against 3 of 10
  before) without removing it by construction.
- Related: US-017 (D2 left `TITLED` as it was; D3 keeps Weston's shadow margin); JDK-8354943 and openjdk/jfx#2139;
  JDK-8365201.
