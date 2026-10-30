# US-065 — Keep decorated GTK stage bounds right across full screen, maximize and the window manager's placement

**Status:** ✅ Done (2026-10-30, uncommitted. Fix option 2, a fork-local fix that re-implements openjdk/jfx#2139's
ideas rather than merging it: a `TITLED` stage is reported with the frame extents in force, asks for nothing while the
window manager has it maximized or full screen, never sends a position read back from X and takes its startup
position only after its first map, so items 1-4 are met on every window manager measured, and item 5 stays as US-017's
D3 decided; see "Resolution" below) · **Found:** 2026-10-20, US-017 survey

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

## Fix options (decided 2026-10-30: option 2, with option 1's ideas re-implemented in the fork; see "Resolution")
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

## Resolution (2026-10-30, uncommitted)

All in WSL (Ubuntu 26.04, JDK 27 Temurin 27+35, Maven 3.10.0, GTK 3.24.52). "Before" is master `64d56e2b9c`, which
has US-017's fix; "after" is the uncommitted fix on it. The decisions were taken by the manager as acting maintainer,
from the survey, a reading of #2139, AWT (JDK 27+35), GTK 3.24 and the WMs' sources, and three review rounds. The raw
evidence stays outside the repository, in the session scratchpad: the probe's tables, glass's event logs, the `xprop`
dumps and frame pixels, the gate reports and Maven logs, and the mutant patches with their runs.

### Decision
**Fix option 2: a fork-local fix that re-implements openjdk/jfx#2139's ideas in the fork's `WindowContextTop`, rather
than merging or backporting #2139.** Why:
- #2139 (JDK-8354943) is still open and still moving: head `128e1babb71f` (2026-10-26, 51 commits), three commits
  after the `28ba295764` that US-017 read, none of them about geometry. Waiting to merge it has no date.
- Each of its 11 glass files that exist in the fork was rewritten by the fork's FFM port. A backport would hand-port
  about 1,500 native lines and an init ABI change for its logging, far more than these five items need.
- By reading (it was not built), it fixes neither item 4 on Weston, since its `update_frame_extents` still moves the
  window to a read-back position after the map, nor item 5. Its deferral of an extents change while maximized depends
  on the event order: it caches the maximized frame's extents before it defers them, can load them back as the
  restored window's, and reports a stale size while maximized.
- The fix takes three of its ideas, and its comments cite JDK-8354943 there: no geometry request while the window is
  not floating, no position from a configure before the first map, and a gravity move by the change only. Its
  full-screen rule (add the extents only while the view is below the top) is replaced by the extents in force, which
  is D2 itself and does not depend on where the frame's title bar is.
- `28ba295764` ("Fix JDK-8365201") is not taken. JDK-8365201 is a stage set maximized before its first show (Mint,
  Muffin, a saved state restored at startup), and the commit defers that state to an idle after the first map; the fix
  options above misread it as a restore from maximized. The fork applies such a state through GTK's
  `maximize_initially` at the map. That cell was measured before and after only to show there is no regression.

D2 is kept: a `TITLED` stage is the WM's frame as `_NET_FRAME_EXTENTS` describes it, now with the extents **in force**
(the property's current value, all zero included) for the reported size. The scene stays at (left, top), and the size
limits are still the sizes asked less the floating frame's extents (`GtkWindowNativeTest` pins them).

### The fix
`glass_window.cpp`, `glass_window.h` and `GlassApplication.cpp` in `native-glass/gtk`, +323/-54 lines; no Java change
outside the tests, and no ABI change (`process_map` is internal). `WindowContextTop` keeps two sets of extents:
`frame_extents`, the extents in force, and `geometry.extents`, the floating frame's, which size requests,
`WM_NORMAL_HINTS` and the per-process cache use. The fix follows eight rules:
1. **No position read back from X is ever sent to the WM.** Glass sends only positions the application asked for
   (`requested_x`, `requested_y`, kept per axis by `set_bounds`). When a floating window's extents change,
   `update_frame_extents` asks for the size only, and a size request keeps the frame's corner (NorthWest gravity,
   ICCCM 4.1.2.3). A `set_bounds` that sets one coordinate of a window on screen sends a ConfigureRequest with that
   coordinate only (`move_window`: `XConfigureWindow` with `CWX` or `CWY`), so the X server and an ICCCM WM leave the
   other where the window is. The one exception: a window GTK has hidden again keeps `gtk_window_move` with the stored
   position, since GTK needs a whole position for the next show.
2. **No request while the WM says the window is not floating.** `update_frame_extents` takes the window for maximized
   or full screen if GDK's window state says so (`wm_floating`, in event order, so a maximize the WM starts by itself
   counts) or a live read of `_NET_WM_STATE` does (`net_wm_state_floating`, one round trip per extents change, for a
   WM that writes the extents before the state). It then reports the window and asks for nothing.
3. **A non-floating window's extents are never stored or cached.** While the window is not floating,
   `update_frame_extents` returns before it touches `geometry.extents` or the cache. A change is measured against
   `geometry.extents`, so a restore that brings the floating extents back is no change and runs no `set_bounds`.
   `process_configure` takes the size to come back to only from a configure of a window that glass and GDK both have
   floating.
4. **The size is reported with the extents in force.** `process_configure` and `report_frame_in_force` report the
   client plus `frame_extents`: in full screen (all zero, EWMH) the window is its client, and while maximized it is the
   maximized frame.
5. **The startup position is taken only after the first map.** `process_configure` keeps the requested position until
   the window's first `GDK_MAP` (`mapped`), as #2139 does, and `process_map` reads it live then (for a `TITLED` window
   GDK's root origin and origin, for an undecorated one `gdk_window_get_origin`; not for a `POPUP`). It notifies a
   move only if the position changed, so the trace goldens without a WM keep their events. openbox places its frame
   before it maps the client (`client_manage` in its client.c runs `place_client` and `client_apply_startup_state`
   before `client_show`).
6. **The gravity is honoured only up to the window's first floating frame.** A window shown with gravity
   (`centerOnScreen`) is moved at its first floating extents with a frame, from the requested position, by its share
   of the change of the window size (the extents' change for a content-sized stage, 0 for a window-sized one; master
   moved the read-back position by its share of the whole extents). That report ends the gravity
   (`floating_frame_seen`), also when it is a cache hit that changes nothing. A later change (openbox's Un/Decorate, a
   theme or border width) then asks for the size only, and a user's drag stays. The gravity does not end at the
   not-floating return (a stage shown maximized has its first floating frame at the restore) nor at the map (Weston
   writes the extents only after it).
7. **An all-zero report before the first floating frame is ignored; after it, it is stored but not cached.** Mutter
   answers `_NET_REQUEST_FRAME_EXTENTS` with 0,0,0,0 and writes its frame's extents later. Before the first floating
   frame such a report is not stored, cached, reported or asked for, and the gravity stays armed, as master ignored
   every all-zero report. After it, an all-zero floating report is a WM that dropped the frame (openbox's
   Un/Decorate, i3's `border none`): stored as the floating extents, so that requests, hints and reports agree, but not
   cached for the next window. All zero while not floating (full screen) falls under rules 2-4.
8. **A disabled window gets its first map and its property notifications.** `process_events` in
   `GlassApplication.cpp` drops a disabled window's events after the `isEnabled` upcall, and a stage shown in the same
   FX task as an application-modal stage is disabled. It now hands such a window's first `GDK_MAP` to `process_map`
   and its `GDK_PROPERTY_NOTIFY` to `process_property_notify` (the WM's `_NET_FRAME_EXTENTS`, and `_NET_WM_STATE` for
   Compiz), each under its own `EventsCounterHelper`; GTK still gets neither. Without it such a window kept its
   requested position for good, and never took its extents or ended its gravity.

Item 1 is rule 4, item 2 rules 2 and 3, item 3 rule 3, and item 4 rules 1 and 5, undecorated windows included
(US-017's note d). Rule 6's end at the first floating frame, the first half of rule 7 and the property notifications
of rule 8 came from the review. The Linux build (`glassgtk3` with `-Werror -Wall -Wextra`) gives 0 warnings.

### How it was measured
- **Probe** (throwaway, not in the repository, in the style of US-017's): glass windows made in a `GtkGlassChild`
  child JVM with `Application.createWindow`, each with a `View`: a `TITLED` window of 400x300 at 200,200 (styles as
  `WindowStage` builds them; `UNIFIED` and `UTILITY` as extra cells) and an `UNTITLED` 320x240 client at 100,100.
  Phases in one JVM: normal, full screen and back (`View.enterFullscreen`, `exitFullscreen`), maximized and restored,
  `setBounds(150, 120)`, a round trip `setBounds(getX(), getY())`, one-axis `setBounds`. Each value was read once it
  had stayed the same for 1 s. The quiet mode makes no X request in event handlers or in the first 1.5 s after an
  action; the instrumented mode logs the X and GDK state at every glass event.
- **Displays:** a private rootless Xvfb 21.1.22 at 1920x1080x24 with no WM, with openbox 3.6.1 and with i3 4.25.1
  (default configurations); WSLg `:0` with Xwayland 24.1.12 and "Weston WM" (WSLg 1.0.79, weston-mirror
  `a7e35fdab455`), `WAYLAND_DISPLAY` unset. Item 4 under openbox ran one window per freshly started Xvfb and openbox.
  "Under load" is 8 busy loops at nice 10 with openbox at nice 19 (on Weston 6 loops per run, or 7 kept running from a
  load average of 5); a run without load started only once the 1-minute load average was below 1.0.
- On Weston the WM frame includes the 32 px shadow margin. The **visible frame** is its opaque part, read from the
  frame's pixels; it is where Weston puts a window asked for at a position (US-017's D3).
- "After" is the final library. The fix as it stood before the review gave the same settled reading in all 106 probe
  runs: the review changed only paths the probe does not take (a gravity, a disabled window, an all-zero first report).

### Item 1: full screen
`TITLED`, glass `Window` in full screen. Under each WM, client and `View` were 0,0 1920x1080 and the extents 0,0,0,0
in every run, before and after.

| WM | runs before / after | before | after | after `exitFullscreen`, after |
| --- | --- | --- | --- | --- |
| openbox | 3 / 3 | 0,0 **1922x1105** | 0,0 1920x1080 | 200,200 400x300 |
| i3 | 3 / 3 | 0,0 **1924x1100** | 0,0 1920x1080 | tiled 0,0 1920x1080, `View` 2,18 |
| Weston | 3 / 3 | 0,0 **1996x1177** | 0,0 1920x1080 | 168,168 400x300, visible frame 200,200 |
| none | 3 / 3 | 200,200 400x300 (nothing changes) | the same | the same |

Every after value held in 3 of 3 runs. Leaving full screen gave the normal values before the fix too. `UNIFIED` under
openbox: 1922x1105 before, 1920x1080 after (one run each); openbox does not take a `UTILITY` window full screen.

### Item 2: an extents change while maximized
`TITLED`, glass `Window` (client) after `maximize(true)`:

| WM | runs before / after | before | after |
| --- | --- | --- | --- |
| Weston | 4 / 4 | 0,0 **400x300** (6,27 **388x267**), still `MAXIMIZED` | 0,0 1920x1040 (6,27 1908x1007) |
| openbox | 3 / 3 | 0,0 1920x1080 (0,19 1920x1061) | the same |
| i3 | 3 / 3 | tiled, no change | the same |

- Before, glass reported a resize with `maximized=false` in the middle of the maximize on Weston (3 of 3) and in
  openbox's instrumented run, where openbox kept the window maximized and so hid it. After, in none (Weston 0 of 4,
  openbox 0 of 3).
- On Weston, with a second window shown while the first stays maximized, the first is 0,0 1920x1040 (before 0,0
  400x300).

### Item 3: the restore from maximized
`TITLED` after `maximize(true)` and `maximize(false)`; WM frame (client):

| WM | runs before / after | before | after (= glass `Window`) |
| --- | --- | --- | --- |
| openbox | 3 / 3 | **0,0 1920x1080** (1,20 1918x1055) | 200,200 400x300 (201,220 398x275) |
| Weston | 4 / 4 | **136,136 336x236** (174,195 **260x139**), visible frame 168,168 | 168,168 400x300 (206,227 324x203), visible frame 200,200 336x236 |
| i3, none | 3 / 3 each | no change | no change |

- Before, the instrumented Weston run, whose normal phase had raced (item 4), ended at 104,104 336x236.
- Under openbox, `UTILITY`, `UNIFIED` and a stage maximized before its first show: 0,0 1920x1080 before, 200,200
  400x300 after (one run each).
- **Why, now established from glass's event logs.** On Weston, at the maximize glass stored and cached 6,6,27,6 and
  resized the maximized window (item 2). Weston's restore configure (client 324x203) was taken as the size to come back
  to with those extents, 336x236. Weston's 38,38,59,38 then ran `set_bounds` with that size and the read-back 168,168:
  the client shrank to 260x139, and Weston put the visible frame at 168,168, so the frame landed at 136,136. Weston
  restored the window right; glass resized and moved it. Under openbox, after the RESTORE glass handled a configure
  that still carried the maximized client (1920x1061) with 0,0,19,0 stored, and took 1920x1080 as the size to come
  back to. openbox's 1,1,20,5 then ran `set_bounds(200, 200, 1920x1080)`, and openbox pushed the oversized frame back
  onto the screen, to 0,0 (`client_find_onscreen`). Glass never asked for 0,0. With rule 3 the restore report is no
  change, and openbox's own restore configure gives the size to come back to a few ms later.

### Item 4: the startup placement race
"Off" = settled away from the D2/D3 place: openbox `TITLED` frame and glass 200,200 400x300 (client 201,220);
`UNTITLED` client and glass 100,100 320x240; Weston `TITLED` frame and glass 168,168 400x300, client 206,227, visible
frame 200,200.

| WM | window | condition | runs before / after | off before | off after |
| --- | --- | --- | --- | --- | --- |
| openbox | `TITLED` | under load | 12 / 12 | **9** | 0 |
| openbox | `UNTITLED` 320x240 | under load | 12 / 12 | **1** | 0 |
| openbox | `TITLED` | no load | 5 / 5 | **5** | 0 |
| openbox | `TITLED` | no load, no `xev` on a second connection | 5 / 5 | **4** | 0 |
| openbox | `TITLED` | a second window, after the first was maximized | 1 / 1 | **1** (-1,-20) | 0 |
| openbox | `TITLED` | every normal reading | 36 / 31 | **24** | 0 |
| Weston | `TITLED` | instrumented, no load | 7 / 7 | **7** | 0 |
| Weston | `TITLED` | quiet, under load | 14 / 14 | **2** | 0 |
| Weston | `TITLED` | quiet, no load | 3 / 3 | 0 | 0 |
| Weston | `TITLED` | every normal reading | 35 / 37 | **10** | 0 |
| Weston | `UNTITLED` 320x240 | quiet and instrumented | 2 / 2 | 0 | 0 |

- Before, openbox ended at -1,-20 (client 0,0), 199,180 or 0,0, and Weston at 136,136 (visible frame 168,168). Even a
  transient wrong position is gone: a glass move or resize elsewhere than asked came in 19 of 34 fresh openbox runs
  before and in none after; on Weston in 31 of 40 runs before and in 1 of 42 after (an undecorated client in Weston's
  still unplaced frame, for about 10 ms).
- **On Weston**, "placed with its frame at 200,200" is read as the visible frame at 200,200 (D3): glass and Weston's
  frame with its margin are then at 168,168, and the client at 206,227.
- **Why.** The race of US-017's "The openbox rows, explained": glass handled a configure while openbox's frame was not
  placed yet, stored that position, and `update_frame_extents` sent it back with `gtk_window_move`. On Weston a
  configure stored 168,168, the frame with its margin; the extents report moved the window there, and Weston put the
  visible frame at 168,168, so the frame landed at 136,136. Rule 1 removes the move on every WM, and rule 5 keeps glass
  from taking the provisional position.

### Item 5: Weston's 32 px margin (D3 kept)
D3 stays, for the application's own round trips. Weston includes its 32 px shadow margin in `_NET_FRAME_EXTENTS` but
puts the visible frame at the position asked for, so a decorated stage reports its position 32 px up and left of
where it was put. The inconsistency is Weston's: Mutter's extents and placement agree, and a native GTK 3 application
(`gtk_window_get_position` against `gtk_window_move`), AWT and #2139 drift the same way. Every correction needs
Weston's theme constant or a guess; learning the offset from a move would take a WM's clamping or snapping for one.

| step | asked | glass / client / visible frame, before = after (3 of 3 runs each) |
| --- | --- | --- |
| placed | 200,200 400x300 | 168,168 / 206,227 / 200,200 336x236 |
| `setBounds(150, 120)` | 150,120 | **118,88** / 156,147 / 150,120 |
| round trip `setBounds(getX(), getY())` | 118,88 | **86,56** / 124,115 / 118,88 |

A plain `stage.setX(v)` reaches glass as a `set_bounds` with only x set. Before the fix glass filled the other axis
with its read-back position; now it sends the set coordinate only (rule 1). Weston fills a coordinate a
ConfigureRequest leaves out from its own record of the frame, margin included (`weston_wm_handle_configure_request`,
xwayland/window-manager.c:956-963 of weston-mirror `a7e35fdab455`), while it places the visible frame at the
coordinates it gets. So on Weston each one-axis call still moves the other axis 32 px, exactly as before. **Accepted,
next to D3, not filed:** the request is right by protocol (ICCCM 4.1.5) and keeps a user's drag of the other axis on
every other WM.

| WM | step | glass / visible frame, before = after |
| --- | --- | --- |
| Weston (1 run before, 3 after) | placed | 168,168 / 200,200 |
| | x only, 150 | 118,**136** / 150,**168** |
| | y only, 120 | **86**,88 / **118**,120 |
| | x only, 150, three more times | y 56, 24, -8 / visible y 88, 56, 24 |
| openbox, none (1 run each) | placed, x150, y120, x150 three times | 200,200 / 150,200 / 150,120 / 150,120: no drift |

### Notes (accepted, not filed)
- **Transients.** When the WM changes the state, glass can handle the client's configure before the new extents'
  `PropertyNotify`, and reports the new client with the old extents in force: entering full screen openbox
  1922x1105, i3 1924x1100, Weston 1996x1177; maximizing openbox 1922x1086, Weston 1984x1104; leaving full screen or
  maximize, the client with the full-screen or maximized extents, and under openbox a configure of the maximized window
  handled after the RESTORE (0,0 1920x1080, then 200,200 1922x1086). Each settles within 2-37 ms (once 33 ms under
  openbox, whose own restore configure came 32 ms after the RESTORE). No request follows from any of them.
- **A size or position the application sets on a maximized stage** is still sent, and Weston obeys a resize of a
  maximized window. #2139's "`set_bounds` only reports while not floating" was not taken: it would also drop a
  request made in the same pulse as `maximize(false)`, before the WM has answered.
- **Weston's unplaced frame.** Just after the map Weston's frame can still be at -32768,-32768, and glass reports
  that: in 29 of 40 runs after (30 of 38 before), for 3-19 ms, once 736 ms in the first Weston JVM after a pause.
  Every run then settled where asked.
- **`UTILITY` on Weston** is never placed: its frame stayed at -32768,-32768 in all 5 runs, 2 before the fix and 3
  after. Weston's behaviour for a utility window without an owner.
- **A stage maximized before its first show, on Weston,** restores as a 400x300 frame at 0,0 (visible frame 32,32),
  not at the asked 200,200: Weston mapped it maximized and never placed it floating, and glass sends no position after
  the map. Before the fix it restored to -32,-32 412x333, neither the asked size nor place. One run each.
- **The first window of its type, shown maximized and dragged out of maximize by the user,** can be moved by its
  gravity at the restore, whose report is its first floating frame. Master moves it there too (from the read-back
  position). Telling the user's restore from the WM's would need Weston's placement offset.
- **A WM that never decorates a window** leaves a window that started from the cached extents with them in force, in
  full screen too, as on master: its all-zero reports are ignored (rule 7).
- **A WM that writes only all-zero extents until a user action adds a frame** (an openbox per-application
  `<decor>no</decor>`, then the user's Decorate) moves a centred window back near its startup position when the frame
  comes; master shifted it a few pixels. No WM's default configuration does this (read in the openbox, Weston, Mutter
  and i3 sources).
- **Mutter was not run** (no GNOME host): its all-zero first answer was read in its source
  (`process_request_frame_extents`, src/x11/events.c) and is played by the zero-first tests. The `_NET_WM_STATE` path
  a disabled window now takes under Compiz (`work_around_compiz_state`) was not run either. KWin and xfwm4 were not
  run.
- **openbox's race was more frequent before the fix than the story said** (14 of 15 runs without load, against 0 of 4
  before US-017's fix). An A/B of US-017's merge `28b02ef511` against `64d56e2b9c` with the same probe, 5 runs each
  without load, gave 5 of 5 on both: the difference comes from the probe's timing and the host, not from a regression
  between them.

### Tests
In `modules/javafx.graphics`; Linux only, and skipped without `DISPLAY` and `-DPRIVATE_DISPLAY=true` like every GTK
display class. Two new classes, 24 tests: `GtkTitledStateBoundsTest` (11) and `GtkWindowPlacementTest` (13). Each
scenario runs in a child JVM of its own, since glass caches a `TITLED` window's extents per process. A test of the
wrong kind for the display skips per method (its first child tells whether a WM runs), so the gate script's list of
classes that run no test under openbox is unchanged. Expected values are those of a 1920x1080 screen. A tiling WM is
not one the classes are meant for (their javadocs); they were not run under i3 or on WSLg `:0`, where the probe
measured.
- **Stand-in.** Without a WM the test stands in for one: it writes `_NET_WM_STATE` and `_NET_FRAME_EXTENTS` on the
  window and moves and sizes the client, in Weston's order (the state, the client, then the extents: floating
  38,38,59,38; maximized 6,6,27,6 with the client at 6,27 1908x1007) or Mutter's (all zero first). The X server applies
  every request glass makes, so the client's geometry shows what glass asked for.
- **Scripted WM** (`GtkScriptedWindowManager`, new in the shims): a reparenting WM of the smallest kind, on its own X
  connection to an Xvfb with no WM, driven step by step by the scenario. It selects SubstructureRedirect on the root,
  publishes `_NET_SUPPORTING_WM_CHECK` and `_NET_SUPPORTED`, answers `_NET_REQUEST_FRAME_EXTENTS` (1,1,20,5 for
  `TITLED`), applies and records each ConfigureRequest, frames a window at 0,0 on its MapRequest and holds it there
  until glass has handled the configure, then places the frame, maps it and sends no synthetic ConfigureNotify: the
  case that only the read at the map can learn. (openbox sends one before the map when it places a window elsewhere;
  glass keeps the asked position until the map then too.)
- `GtkTitledStateBoundsTest`, items 1-3, each case with the stand-in and under openbox where it can:
  - **full screen** (`aFullScreenWindowIsTheScreen…`, 2): extents 0,0,0,0, `Window` = client = 0,0 1920x1080, `View`
    0,0;
  - **leaving full screen** (`aWindowLeavingFullScreenIsItsFrameFromBefore…`, 2): client, `Window` and `View` as
    before full screen;
  - **maximize** (`anExtentsChangeWhileMaximizedAsksForNothing…`, 2, and
    `anExtentsChangeBeforeTheStateWhileMaximizedAsksForNothingWithoutAWindowManager`, which writes the extents before
    the state): the client stays 6,27 1908x1007, `Window` 6,27 1920x1040, maximized, with no resize notification of a
    floating size; under openbox `Window` 0,0 1920x1080 and no such notification either;
  - **restore** (`aRestoreKeepsTheFrameTheWindowManagerRestores…`, 2): client, X frame (`topLevelGeometry`) and
    `Window` as before the maximize;
  - **all zero** (`anAllZeroReportWhileFloatingIsTheFrame`, `anAllZeroReportWhileFloatingIsNotCached`): after
    38,38,59,38 and then 0,0,0,0 while floating, the window keeps 400x300 with its client filling it, and
    `setSize(500, 400)` gives a 500x400 client and `Window`; a second window starts from the cached 38,38,59,38, not
    from the zeros.
- `GtkWindowPlacementTest`, item 4 and rules 1 and 5-8, all without a WM:
  - **extents change** (`anExtentsChangeKeepsTheWindowWhereXHasIt`): the test moves the client to 50,60 and glass
    reads it; then, in one FX task, the extents change to 1,1,20,5 and the test moves the client to 300,250: client
    300,250 398x275, `Window` 300,250 400x300;
  - **one axis** (`settingXAloneKeepsTheYWhereXHasIt`, `settingYAloneKeepsTheXWhereXHasIt`): in one FX task the test
    moves the client's y to 400, then `setBounds(150, 0, true, false, ...)`: client and `Window` at 150,400; the same
    for x (300,120);
  - **unplaced** (`…KeepsThePositionAskedForUntilTheWindowManagerPlacesIt`, `TITLED` and undecorated) and **placed**
    (`aTitledWindowIsReportedWhereTheWindowManagerPlacedIt`,
    `anUndecoratedWindowIsReportedWhereTheWindowManagerPlacedIt`), with the scripted WM: while the frame is held at
    0,0 glass reports the asked 200,200 and 100,100; once the WM has placed the frames at 260,170 and 140,130 it
    reports those, `View` 1,20 and 0,0. The `TITLED` window measured is the second of its process: the first asks for
    its size after the map, and the configure of that resize would give glass the placed position whether or not it
    reads it at the map;
  - **disabled at map** (`aWindowDisabledAtItsFirstMapIsReportedWhereXMovesIt`): `setEnabled(false)` and
    `setVisible(true)` in one FX task, then the test moves the window to 50,60: glass reports 50,60;
  - **centred**, a 360x240 content asked at 780,280 with `centerOnScreen`'s gravity 0.5, 1/3: **centred after the
    map** (`aCentredWindowIsCentredWithTheExtentsThatComeAfterItsMap`), the first window, its 38,38,59,38 written after
    its map (Weston's order), is centred with its frame at 742,247 436x337; **centred, moved**
    (`aCentredWindowStaysWhereXMovedItWhenItsExtentsChange`), a second one, whose first report is the cache, moved by
    the test to 700,500, stays there when its extents change to 1,1,20,5; **centred and disabled**
    (`aCentredWindowDisabledAtItsShowStaysWhereXMovedItWhenItsExtentsChange`) does the same after it took its extents
    while disabled;
  - **zero first**, Mutter's order (0,0,0,0 in the FX task that shows the window, then 38,38,59,38): **cache miss**
    (`aCentredWindowWhoseFirstReportIsAllZeroIsCentredWithTheExtentsThatFollow`) is centred at 742,247; **cache hit**
    (`aCentredWindowFromTheCachedExtentsWhoseFirstReportIsAllZeroKeepsThePlaceAndSizeAskedFor`) keeps 780,280 360x240.
- Helpers: `GtkBoundsSteps` (new, not a test class: a window shown as Quantum shows a stage, steps of an action and a
  settled reading with glass's call trace, one child per scenario); `GtkGlassShim` gains `setNetWmState`, `moveWindow`
  and `moveResizeWindow`; `GtkFrameExtentsStandIn` gains `Effect.EXTENTS`; `GtkGlassChild.startToolkit()` is `main`'s
  toolkit start, taken out for a scenario that starts the scripted WM first. No pom change.
- Run time in the gate: 43 s without a WM (28 s and 15 s), 8 s under openbox, where most tests skip.

**Before the fix** (master's library with the new tests): 17 of 24 fail, 14 of 20 without a WM and 3 of 4 under
openbox, with the story's numbers: full screen 0,0 1996x1177 and 1922x1105; maximized client 388x267 and a 400x300
resize; restored client 260x139 and frame 0,0 1920x1080; all zero client 324x203 and `setSize(500, 400)` a 424x303
client; extents change 50,60; one axis 150,200 and 150,120 (glass's own drift of the unset axis); placed and unplaced
`Window` 0,0; centred, moved and centred and disabled 699,491. The seven that pass on master pin what master already
did: leaving full screen (2), the zeros not cached, centred after the map (master corrects from the read-back
position, which is the asked one while nothing moved the window), disabled at map (master has no gate) and zero
first (2; master ignores every all-zero report). A mutant shows that each of them can fail.

**Mutant proof.** Mutants m1-m16 each revert one piece of the fix: m1-m5 and m8-m16 were written on the native side,
m4 in two halves (m4a, m4b); m6 and m7 on the test side, for tests no other mutant reached. Each is a patch applied
only in a throwaway WSL clone that carries the fix: `glassgtk3` rebuilt, both classes run without a WM and, where a
test needs one, under openbox, then the patch reverted and the library rebuilt, its sha256 equal to the fix's every
time. Each mutant failed its tests in 3 of 3 runs on the tree of the round that brought it, and again in one run on
the final tree. The patches are not committed.

| mutant | reverts | fails (in every run) |
| --- | --- | --- |
| m1 | rule 4: reports with the stored floating extents | full screen and maximize (both orders) without a WM; full screen (1922x1105) and maximize (1922x1086) under openbox |
| m2 | rule 2: the return while not floating | full screen, maximize (both orders) and restore without a WM; leaving full screen, maximize and restore under openbox |
| m3 | rule 3: a non-floating window's extents stored and cached | restore, without a WM (client 260x139) and under openbox (0,0 1920x1080) |
| m4a | rule 1: an extents change moves the window to the read-back position | extents change (client 50,60) |
| m4b | rule 5: m9 and m10 together | placed and unplaced (`Window` 0,0) |
| m5 | rule 1: the unset axis of `set_bounds` filled from the read-back position | one axis (150,200 and 150,120) |
| m6 | rule 7: an all-zero floating report cached as well | all zero, the second window (client 400x300) |
| m7 | rule 4: a report back to the stored floating extents leaves the extents in force as they were | leaving full screen and restore, without a WM and under openbox |
| m8 | rule 6: the gravity stays armed after the first floating frame | centred, moved (817,304) |
| m9 | rule 5: no position read at the first map | placed (200,200 and 100,100) |
| m10 | rule 5: no `mapped` gate | unplaced (`Window` 0,0) |
| m11 | rule 2: no live `_NET_WM_STATE` read | maximize with the extents before the state (client 388x267) |
| m12 | rule 8: a disabled window's first map dropped | disabled at map; centred and disabled, at its precondition |
| m13 | rule 6: the gravity ends at the first map | centred after the map; zero first, cache miss (780,280) |
| m14 | rule 7: a pre-frame all-zero report stored, the gravity ended only by a non-zero one | zero first, cache hit (client 818,312 284x143) |
| m15 | rule 8: a disabled window's property notifications dropped | centred and disabled (779,271) |
| m16 | rule 7: a pre-frame all-zero report ignored but ending the gravity | zero first, cache miss (780,280) |

`anAllZeroReportWhileFloatingIsTheFrame` has no mutant of its own; it fails on master.

**Stability.** On the final tree, five runs in a row each: 20 passed and 4 skipped without a WM, 4 passed and 20
skipped under openbox, in all five (load average 1.3-2.0). The reviewer ran both classes of the first version under
8 busy loops (load average 6.6-13.1): 2 of 2 passed without a WM and under openbox. The waits are event-driven (a
reading counts once two in a row agree); there is no bare sleep.

Files: `glass_window.cpp`, `glass_window.h`, `GlassApplication.cpp`; in `src/shims`, `GtkGlassShim` (changed) and
`GtkScriptedWindowManager` (new); in `src/test`, `GtkFrameExtentsStandIn` and `GtkGlassChild` (changed),
`GtkBoundsSteps`, `GtkTitledStateBoundsTest` and `GtkWindowPlacementTest` (new).

### Gates
| gate | before the fix | after the fix |
| --- | --- | --- |
| every `test/com/sun/glass/ui/gtk/*Test` on a private Xvfb with no WM (`.github/scripts/run-gtk-display-tests.sh`, `-DPRIVATE_DISPLAY=true -DUSE_ROBOT=true`) | 36 classes, 228 tests, 0 failures, 0 skipped | 38 classes, 252 tests (+11 and +13), 0 failures, 4 skipped (the new openbox tests) |
| the same under openbox | 36 classes, 201 tests, 0 failures, 3 skipped, 5 classes `Tests run: 0` | 38 classes, 225 tests, 0 failures, 23 skipped (the same 3 and the 20 new no-WM tests), the same 5 `Tests run: 0` |
| headless (`DISPLAY` unset) | 36 classes, 43 tests | the same 43; the 2 new classes `Tests run: 0` with the other display classes |
| `GtkWindowNativeTest`, `GtkFrameExtentsNativeTest`, `GtkUndecoratedStageBoundsTest` | 6/6, 14/14, 2/2 in both runs | the same |
| the trace goldens (`GtkEventTraceTest`, `GtkWindowStateTraceTest`, `GtkUpcallExceptionTest`, `GtkDnD*`) | pass | pass; no golden changed or regenerated |
| JDK-8329821 under i3: `ContextMenu.show(anchor, Side.BOTTOM, 0, 0)` on a decorated stage | 8/8 checks, 2 runs | 8/8, 2 runs, every reading identical (the menu 108x80 at 302,242 in X and in JavaFX) |
| JDK-8348095 under i3: the same in a full-screen stage | 9/9, 2 runs; stage 0,0 **1924x1100** | 9/9, 2 runs, every menu reading identical; stage 0,0 1920x1080 |

- In both runs each of the 36 old classes has the tests, failures, errors and skips it had before.
- The gate took 934 s before and 720 s after. No run left an `hs_err` or `.dumpstream` file, and the runs left the
  natives unchanged. The reviewer ran the gate without a WM again on the final tree: 252 run, 0 failures, 4 skipped.
- The i3 scenario is US-017's, rebuilt from its surviving class file: a decorated 800x600 stage, a `ContextMenu` shown
  2.5 s after `show()` and read 1.5 s later, checked against `xwininfo` and `xprop`; a freshly started Xvfb and i3 per
  run.

### Review
Three rounds by an independent reviewer (`ffm-reviewer`), each with its own WSL clone, build and runs. Each finding of
round 1 was checked by an adversarial verification before it was fixed; rounds 2 and 3 demonstrated theirs with runs.
- **Round 1** (native and tests). (Major) A window shown centred whose first extents matched the cache kept its
  gravity armed for good, so a later floating change (openbox's Un/Decorate, a theme or border change) sent it back to
  its startup centre and undid a user's drag: fixed by rule 6. (Nit) The `process_map` comment cited openbox's
  `frame_show` for the placement; it now cites `client_manage`. (Major) Each half of the item 4 fix could be removed
  with every test green, because the scripted WM placed the frame where it was asked and the unplaced reading was not
  asserted: the WM now places it elsewhere, and the unplaced readings are asserted (m9, m10). (Minor, twice) The live
  `_NET_WM_STATE` read and the disabled window's first map had no test: one now writes the extents before the state
  (m11), one disables the window before its show (m12). A javadoc was fixed; a duplicate Xlib binding in the two shim
  classes was refuted (the shims bind per purpose, and the scripted WM must not load GTK).
- **Round 2.** (Major) Glass dropped a disabled window's property notifications, so a stage shown in the same FX task
  as an application-modal dialog never handled its first extents and kept its gravity: shown by a run, fixed by
  rule 8 (m15). (Major) On Mutter's order a cache-hit content-sized stage ended with a client smaller by the extents
  and off its place (818,312 284x143 for 780,280 360x240 asked; master right): shown with a writer of all-zero
  extents, fixed by rule 7's first half (m14, m16). (Nit) The scripted WM's javadoc about openbox's synthetic
  ConfigureNotify was corrected.
- **Round 3: no findings.** The reviewer traced every path of the gravity rule (disabled windows, Mutter's zero
  answer, a stage shown maximized, Weston's order, hide and show, `centerOnScreen()` after the show), ran the gate
  without a WM on the final tree and caught m8 and m13-m16 again.
