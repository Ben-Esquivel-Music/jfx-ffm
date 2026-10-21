# US-017 — Keep a window manager's frame extents out of undecorated GTK stage bounds

**Status:** ✅ Done (2026-10-20, uncommitted; the PR number is added when it lands. Fix option 1, as in openjdk/jfx#2139: undecorated windows ignore `_NET_FRAME_EXTENTS` and take their position from the configure event; `TITLED` unchanged; verified on a private Xvfb with no WM, openbox and i3, and on WSLg; the `TITLED` faults went to US-065; see "Resolution" below) · **Found:** 2026-09-27, US-008 (explaining the `GtkWindowNativeTest` failures on WSLg)

## Story
As a JavaFX app developer whose app shows an undecorated, transparent or extended stage on Linux (a splash screen,
a full-screen view, a window that draws its own title bar),
I want the stage's position, size, minimum size and maximum size to describe the window that is on the screen,
so that an undecorated 1920x1080 stage placed at (0, 0) reports (0, 0) and 1920x1080, not a larger rectangle that
includes a border, a title bar and a shadow margin the window does not have.

## Problem
Glass/gtk applies the `_NET_FRAME_EXTENTS` that a window manager (WM) reports to every window, whatever its frame
type. Weston, the WM of WSLg, reports the extents of a decorated frame, shadow margin included, for undecorated
windows too. So on WSLg an undecorated stage gets the bounds of a decorated frame that it does not have.

### How glass uses the extents
All in `modules/javafx.graphics/src/main/native-glass/gtk/glass_window.cpp`, with the line numbers it had before this
story's fix (the same at `c666773a96`):
- `process_property_notify` calls `update_frame_extents` whenever `_NET_FRAME_EXTENTS` changes on the window
  (`:970-980`). Neither function looks at `frame_type`.
- `update_frame_extents` (`:848-888`) ignores a report that is all zero (`:852`). Otherwise it stores the extents in
  `geometry.extents` and, when they changed, copies them into the static cache (`set_cached_extents`, `:864`,
  `:890-896`) and sets the bounds again (`set_bounds`, `:884`).
- Only the start depends on the frame type. The constructor makes every frame type except `TITLED` undecorated
  (`gtk_window_set_decorated(FALSE)`), and only a `TITLED` window starts from the cached extents (`:821-825`). The
  others start at zero (`WindowGeometry()` value-initialises `extents`, `glass_window.h:72-73`). `process_realize`
  asks the WM for the extents (`_NET_REQUEST_FRAME_EXTENTS`) only for a `TITLED` window (`:998-1000`).
- The frame type comes from the stage style (`WindowStage.java:155-170`, `GlassWindow.cpp:37-48`): `UNDECORATED` is
  `UNTITLED`, `TRANSPARENT` is `TRANSPARENT` and `EXTENDED` is `EXTENDED`. `DECORATED`, `UNIFIED` and `UTILITY` are
  `TITLED`. A popup is a `GTK_WINDOW_POPUP` (`:786`) and `UNTITLED` unless it is transparent.

### Where the extents reach Java
- **Size.** `process_configure` reports the client size plus the extents as the window size (`:1012-1013`,
  `:1018-1022`), and so does `notify_window_resize` (`:732-746`, `:1414-1422`). Quantum makes that the stage's width
  and height (`GlassWindowEventHandler.java:99-104`).
- **Size requests.** `set_bounds` subtracts the extents from a window width or height to get the client size
  (`:1151-1154`, `:1163-1166`). An explicit stage width or height is such a window size
  (`javafx.stage.Window.adjustSize`).
- **Minimum and maximum.** `update_window_constraints` subtracts the extents from both before GTK writes
  `WM_NORMAL_HINTS` (`:1085-1099`). Since US-008, `GtkWindowNativeTest` expects exactly that for a `TITLED` window.
- **Position.** `process_configure` takes the window position from `gdk_window_get_root_origin`, and the scene's
  offset in the window from `gdk_window_get_origin` minus that (`:1044-1056`). Quantum makes them the stage's x and y
  (`GlassWindowEventHandler.java:68-86`) and the scene's x and y (`GlassViewEventHandler.java:815-823`).
  - Glass's own extents play no part here. GTK documents `gdk_window_get_root_origin` as the top-left corner of the
    WM frame. GTK 3's X11 backend computes that frame from `_NET_FRAME_EXTENTS` when the WM lists the atom in
    `_NET_SUPPORTED` (Weston does) and the window has the property. For an override-redirect window it returns the
    window's own position (`gdk_x11_window_get_frame_extents` in `gdk/x11/gdkwindow-x11.c`, branch `gtk-3-24`).
  - The measured positions below agree with that. So a fix in `update_frame_extents` alone would not correct the
    position.

### What Weston reports
WSLg's Weston fork (`microsoft/weston-mirror`, branch `working`, read 2026-09-27) sets `_NET_FRAME_EXTENTS` in
`weston_wm_window_set_net_frame_extents` (`xwayland/window-manager.c`). It does not look at the window's decoration:
- `frame_decoration_sizes` (`shared/frame.c`) takes the title bar height for the top when the frame has a title or
  buttons, and the border width for the other sides. Unless the window is maximized, it adds the shadow margin on
  every side (its comment: "Not maximized, add shadows").
- The theme has a margin of 32, a border width of 6 and a title bar of 27 (`theme_create`, `shared/cairo-util.c`).
  The XWM always gives the frame a close button (`weston_wm_window_create_frame`).
- That makes 38, 38, 59, 38 (left, right, top, bottom) for every window the XWM frames (every managed window) that
  is neither maximized nor full screen. For a full-screen window it computes 0, 0, 0, 0.
- An override-redirect window, such as a glass popup (`GTK_WINDOW_POPUP`), gets no frame and no extents: only
  `weston_wm_handle_map_request` creates the frame, and `weston_wm_window_schedule_repaint` skips a window without
  one.
- The X frame window of an undecorated window is the client plus the margin on every side, with the client at
  (32, 32) inside it (`weston_wm_window_get_frame_size`, `weston_wm_window_get_child_position`).

### Measured (2026-09-27; WSL2 with WSLg: X server release 12401006 (Xwayland 24.1.6), "Weston WM", GTK 3.24.52)
Screen 1920x1080 at scale 1. The probe, run on JDK 25, made a glass `Window` with
`Application.createWindow(null, Screen.getMainScreen(), style)`, set its bounds and showed it. It ran in a child JVM
through `GtkGlassChild`, with `WAYLAND_DISPLAY` unset. It read the `Window`'s x, y, width and height and the
`View`'s x and y; the client's position on the root (`GtkGlassShim.rootPosition`); the X window tree (`xwininfo`);
and the window's properties (`xprop`). openbox and "no WM" ran on a private Xvfb, 1920x1080x24. The row
"Weston, FFM and JNI" comes from `GtkWindowNativeTest` as changed by US-008, which reads the extents with
`GtkGlassShim.netFrameExtents`, and from a JNI build of `8492cb03b0`, run in its own JVM (not through
`GtkGlassChild`), that created the same `TITLED` window, set a minimum of 123x45, a maximum of 800x600 and a maximum
of `Integer.MAX_VALUE`, and read the extents and hints with `xprop`.

The `UNTITLED` rows set a client size with a view: `setBounds(x, y, true, true, -1, -1, w, h, 0, 0)`. The `TITLED`
rows set a window size, `setBounds(200, 200, true, true, 400, 300, -1, -1, 0, 0)`, without a view. "Glass" is the
FFM glass of `e003c1f200` unless marked JNI (`8492cb03b0`).

| WM | window, as asked | extents (l, r, t, b) | client | X frame | glass window | view |
| --- | --- | --- | --- | --- | --- | --- |
| Weston | `UNTITLED` 1920x1080 at 0,0 | 38,38,59,38 (a) | 0,0 | -32,-32 1984x1144 | -38,-59 1996x1177 | 38,59 |
| Weston | `UNTITLED` 320x240 at 100,100 | 38,38,59,38 (a) | 100,100 | not read | 62,41 396x337 (b) | 38,59 |
| Weston, FFM and JNI | `TITLED` 400x300 at 200,200 | 38,38,59,38 | not read | not read | not read | - |
| Weston | `TITLED` 400x300 at 200,200 | not set (c) | -32730,-32709 | -32768,-32768 476x397 | (c) | - |
| openbox | `TITLED` 400x300 at 200,200 | 1,1,20,5 | 0,0 | not read | -1,-20 400x300 | - |
| openbox | `UNTITLED` 320x240 at 100,100 | not read | 100,100 | not read | 0,0 320x240 (d) | 0,0 |
| none | `TITLED` 400x300 at 200,200 | not set | 200,200 | none | 200,200 400x300 | - |
| none | `UNTITLED` 320x240 at 100,100 | not read | 100,100 | none | 100,100 320x240 | 0,0 |
| none | `UNTITLED` 1920x1080 at 0,0 | not read | 0,0 | none | 0,0 1920x1080 | 0,0 |

- (a) Not read with `xprop` for an undecorated window. Glass's numbers imply these values: an `UNTITLED` window gets
  extents only from `update_frame_extents`, its window is the client plus 76 x 97, and the view is at (38, 59).
- (b) The first four of six reads, before the extents arrived, gave 100,100 320x240 and a view at 0,0.
  **Corrected 2026-10-20:** glass's own event log shows Weston's frame-creation position until the extents arrive
  (an undecorated window reports -32774,-32795, a `TITLED` one -32768,-32768), not the client. The extents arrived
  7-141 ms after `setVisible` returned in 21 of 23 windows, and after 635 ms and 1077 ms in two `TITLED` windows.
  How the reads of 2026-09-27 were timed against the configure events is not known.
- (c) In one run Weston did not set the extents during the whole scenario. The window stayed where Weston creates
  its frame windows, at -32768,-32768 (`weston_wm_window_create_frame`), and glass reported -32768,-32768 400x300.
  The frame window's size was read shortly after the window closed. The client sits at (38, 59) inside a frame
  76 x 97 px larger than it: a decorated frame includes the 32 px margin too. **2026-10-20:** not reproduced; Weston
  set the extents on all 8 `TITLED` windows of the survey.
- (d) The same in two runs. Glass applied no extents (the view is at 0,0 and the window is the client), yet it
  reports a position that is not the client's. Not explained. **Explained 2026-10-20:** a startup race in
  `process_configure`, which read GDK's frame origin while openbox was still placing its new frame; see "The openbox
  rows" under "Resolution". It did not occur after the fix: 0 of 16 runs, against 3 of 10 before the fix under the
  same load (a small sample, Fisher p about 0.08).

On Weston, glass's rectangle for the undecorated 1920x1080 window is exactly the frame a decorated window of that
size would have: 1920 + 2 x (6 + 32) = 1996 wide, 1080 + 27 + 6 + 2 x 32 = 1177 tall, at the client position less
(38, 59).
- Weston's real frame is the client plus the 32 px margin. Glass's corner is 6 px left of it and 27 px above it,
  and glass's rectangle is 12 px wider and 33 px taller.
- The window shows only its client, at (0, 0) and 1920x1080. Against that, the stage is off by (38, 59) and
  76 x 97 px too large.
- A window asked to be at (0, 0) ends with its client at (0, 0) and then reports (-38, -59). One asked to be at
  (100, 100) reports (62, 41).

Minimum and maximum, `TITLED` 400x300 on Weston, FFM and JNI glass alike: a minimum of 123x45 reaches
`WM_NORMAL_HINTS` as 47x1, a maximum of 800x600 as 724x503, and a maximum of `Integer.MAX_VALUE` as 32691x32670. On
openbox both glasses give 121x20, 798x575 and 32765x32742. `_GTK_FRAME_EXTENTS` did not exist on either display
(`xprop`: "no such atom on any window").

### All-zero extents
- An all-zero report changes nothing: the window keeps the extents it had (`:852`).
- An undecorated window starts at zero, so a WM that only ever reports 0, 0, 0, 0 for it leaves it at zero.
- Any window that once received non-zero extents keeps them when the WM later reports 0, 0, 0, 0. That includes an
  undecorated window on WSLg. Weston computes 0, 0, 0, 0 for a full-screen window, so a window that goes full
  screen on WSLg would keep 38, 38, 59, 38 in its reported size. That follows from the code; it was not measured.
  **Measured 2026-10-20:** it does, for undecorated and `TITLED` windows alike (0,0 1996x1177 for a 1920x1080 client);
  see "All-zero reports and state changes" under "Resolution".
- A `TITLED` window can also start from stale extents: it starts from the cache (`:824`). The cache holds the
  extents that `update_frame_extents` last stored for any window, an undecorated one included: one cache for
  `NORMAL` windows and one for `UTILITY` and `POPUP` windows (`:864`, `:890-900`). If the WM then reports
  0, 0, 0, 0 for the `TITLED` window, the cached extents stay. On Weston decorated and undecorated windows get the
  same extents, so the shared cache makes no difference there.
- The code does not say why the all-zero report is ignored.

### Upstream too
- `glass_window.cpp` is byte-identical in upstream `openjdk/jfx` at `868c4801ec` and at `ca9b07aeae` (the last
  upstream commit in this fork), at `8492cb03b0` (the JNI glass), and on `openjdk/jfx` master (read 2026-09-27).
- In the functions named above, the FFM port (#12, `26ce75d02f`) changed only how the constructor receives the
  window's Java id and X visual, and how `process_configure` and `notify_window_resize` call into Java. The values
  they pass are the same.
- The JNI glass run on WSLg and on openbox gave the same extents and hints as the FFM glass (above).
- So this is not a fork regression.
- JBS has two fixed i3 reports about this code:
  - JDK-8329821 ("[Linux] When using i3 WM, menus are incorrectly sized", fixed in jfx23, `a7627fa8d4`): i3 gave
    non-zero `_NET_FRAME_EXTENTS` to undecorated windows (popups). The fix only stopped `process_realize` from
    requesting the extents for a window that is not `TITLED` (`:998-1000`). `update_frame_extents` still applies
    extents that a WM sets unasked, as Weston does.
  - JDK-8348095 ("[Linux] Menu shows up in wrong position when using i3 windows manager in full screen mode", fixed
    in jfx25, `b267340b9f`) moved the window position to `gdk_window_get_root_origin` and the view offset to
    `gdk_window_get_origin` minus that. Before it, a non-`TITLED` window took its position from
    `gdk_window_get_origin`, and the view took its offset from glass's extents.
- A JBS search on 2026-09-27 (JavaFX issues whose text has "frame extents", `_NET_FRAME_EXTENTS`, "WSLg" or
  "Weston") found no report of a WM that sets the extents on an undecorated window by itself, as Weston does.
- JDK-8354943 ("[Linux] Simplify and update glass gtk backend: window sizing, positioning, and state management
  issues", open, fix version tbd) and its pull request openjdk/jfx#2139 (open, created 2026-04-06) rework this code.
  Neither description mentions frame extents, and by their titles none of the issues linked from JDK-8354943 is
  about them.

### Who is affected
- **Linux, GTK glass on X11 or Xwayland,** stage styles `UNDECORATED`, `TRANSPARENT` and `EXTENDED`, under a WM
  that reports non-zero `_NET_FRAME_EXTENTS` for an undecorated window.
  - Measured: Weston (WSLg), with `UNTITLED` glass windows. `TRANSPARENT` and `EXTENDED` take the same code path.
  - openbox: glass applied no extents to the undecorated window (note d). GNOME, KDE and other WMs: not known.
    **2026-10-20:** openbox publishes 0, 0, 0, 0 for an undecorated window in every state (measured). By their
    sources, KWin and xfwm4 publish 0, 0, 0, 0 and Mutter publishes nothing for it (not measured; no host).
  - i3: answers `_NET_REQUEST_FRAME_EXTENTS` with its default border and title bar whatever the window's
    decoration (`src/handlers.c`, branch `next`), which gave undecorated popups non-zero extents (JDK-8329821).
    Glass no longer asks for them there. i3 also sets the extents unasked on every window it manages, to the border
    it draws (`x_push_node`, `src/x.c`); whether that is non-zero for an undecorated stage is not known.
    **2026-10-20:** 0, 0, 0, 0 in i3's default layout, which honours the "no decorations" hint; 2, 2, 0, 2 (a real
    2 px border) in a tabbed container with a sibling (both measured, i3 4.25.1).
- **On WSLg, for such a stage:**
  - its x and y are the client's position less (38, 59), and its width and height are the client's plus 76 x 97
    (measured);
  - its scene sits at (38, 59) in the stage instead of (0, 0) (measured);
  - an explicit width or height gives a client 76 or 97 px smaller (by the code, `set_bounds`; measured 2026-10-20:
    500x400 gives a 424x303 client);
  - its minimum and maximum reach the WM 76 x 97 px smaller, and a minimum height of 45 becomes 1 (by the code;
    measured for a `TITLED` window; measured 2026-10-20 for undecorated windows too: 47,1 and 724,503).
- **Decorated stages on WSLg** include Weston's 32 px shadow margin on every side in their bounds, because Weston
  counts it in the extents. That is a separate question; see the acceptance criteria.
- **Not affected:** Windows and macOS; Linux without a WM; popups on WSLg, which Weston does not frame (by
  Weston's source, above).

## Fix options (decided 2026-10-20: option 1, see "Resolution")
1. **Ignore the extents for undecorated frame types.** `update_frame_extents` returns early for `UNTITLED`,
   `TRANSPARENT` and `EXTENDED`, and does not write the cache. These windows take their position from the client
   (`gdk_window_get_origin`) with a view offset of 0, because GDK's frame origin includes the extents. This
   restores the position source that non-`TITLED` windows had before JDK-8348095. Risk: a WM that ignores the
   "no decorations" hint and draws a real frame around the window. Its extents are then true, as for a `TITLED`
   window.
2. **Trust the extents only where they match the WM's frame.** Compare the client's offset inside its parent (the
   WM's frame window) with (left, top). On Weston an undecorated client sits at (32, 32) against (38, 59): no match.
   The decorated client of note (c) sits at (38, 59): a match. This costs an X round trip per change and works only
   under a reparenting WM.

## Acceptance criteria
- **Survey first.** For each WM available, record the facts in a table in this story:
  - WMs: Weston (WSLg `:0`); openbox and no WM on a private Xvfb; i3 on a private Xvfb (JDK-8329821 records that
    it gave undecorated windows non-zero extents); GNOME Shell or KDE Plasma on X11 if a host is available. Name the
    ones that were not.
  - Windows: `UNTITLED`, `TRANSPARENT`, `EXTENDED` and `TITLED` stages and a popup, each normal, maximized and
    full screen.
  - Values: `_NET_FRAME_EXTENTS` and `_GTK_FRAME_EXTENTS` (`xprop`); the client's position and size on the root;
    the WM's frame window (the client's parent); glass's `Window` and `View` values.
  - Include an `xprop` dump of the extents Weston sets on an undecorated window; so far they are only implied
    (note a).
- **Explain the openbox rows.** The `TITLED` window asked for at 200,200 has its client at 0,0. Glass reports the
  `UNTITLED` window at 0,0 while its client is at 100,100. Fix it here if the cause is this story's; otherwise file a
  new story.
- **Confirm the position source.** On Weston, log `gdk_window_get_root_origin` beside the client's root position and
  the frame window's origin. Show that it returns the client position less the left and top extents.
- **Decide and record the behaviour for each frame type:** which rectangle is the stage's x, y, width and height,
  what the minimum and maximum sizes mean, and where the scene sits. Weigh the fix options above against a WM that
  draws a real frame around an undecorated window. Also decide whether an undecorated window may write the cache
  that later `TITLED` windows start from (`:824`, `:864`).
- **Decorated stages on WSLg.** Decide whether their bounds should include Weston's 32 px shadow margin. Fix it here
  or file a new story.
- **All-zero reports.** On WSLg, show what glass reports for a `TITLED` and an `UNTITLED` stage that enters and
  leaves full screen and the maximized state. If the old extents stay in force, fix it here or file a new story.
- **Fix** `glass_window.cpp` as decided, and the Java side only if it has to change.
- **Test.** Add a child-JVM test in the style of `GtkWindowNativeTest` (`GtkGlassChildJvm.run`, `GtkGlassChild`),
  run on a private Xvfb with a WM:
  - `UNTITLED`, `TRANSPARENT` and `EXTENDED` windows, and a `TITLED` control;
  - it asserts the decided rectangle against the client's root position and size (`GtkGlassShim.rootPosition`), and
    `WM_NORMAL_HINTS` (`GtkGlassShim.wmNormalHints`) against the minimum and maximum asked for;
  - it reads the extents with `GtkGlassShim.netFrameExtents` and waits for them to settle, as `GtkWindowNativeTest`
    does;
  - if neither i3 nor another WM on a private Xvfb reports non-zero extents for an undecorated window, the test may
    write `_NET_FRAME_EXTENTS` on the window itself to stand in for one. Say what that covers and what it does not
    (GDK's frame origin may still need a real WM);
  - it fails before the fix and passes after. Mutant proof: with the fix reverted in a scratch copy, it fails;
  - it skips without `DISPLAY`, like the other display classes.
- **Public API.** A short non-robot check, in the new class or a class of its own: an `UNDECORATED` `Stage` shown at
  (0, 0) with a 1920x1080 scene reports x, y = 0, 0, width x height = 1920x1080 and scene x, y = 0, 0, or the
  rectangle decided above. It runs on a private Xvfb with a WM (with the stand-in extents if needed) and once on
  WSLg `:0`.
- **Gates.**
  - Every `test/com/sun/glass/ui/gtk/*Test` class passes on a private Xvfb with no WM and with openbox, each class
    with the same counts as before, apart from the new test.
  - `GtkWindowNativeTest` stays green; it pins the `TITLED` hints.
  - The scenarios of JDK-8329821 (popup menu size under i3) and JDK-8348095 (menu position in a full-screen stage
    under i3) still pass: under i3 if it runs on a private Xvfb, otherwise with the test's stand-in extents.
  - On WSLg `:0`, at most one short non-robot test class per Maven run: the new class, `GtkWindowNativeTest` and
    the public-API check each get a run of their own. Never run the robot suite there. Every other test run is on a
    private Xvfb.
- **Upstream.** Repeat the JBS search (component `javafx/window-toolkit`; "frame extents", `_NET_FRAME_EXTENTS`,
  WSLg, Weston) and check whether openjdk/jfx#2139 changes this behaviour. Record both here. If there is still no
  report, draft one for the upstream register, which is kept outside the repository until filed. Cite JDK-8329821
  and JDK-8348095 in it.

## Notes
- Found in US-008 while explaining why `GtkWindowNativeTest` failed on WSLg. US-008 fixed the test's expectation for
  a `TITLED` window (hints less the extents) and changed no glass code.
- The probe programs were throwaway code and are not in the repository. The Measured section describes them well
  enough to rebuild them.
- Weston sets the extents late, sometimes seconds after the map (notes b and c). Any test has to wait for them to
  settle. **2026-10-20:** within 7-141 ms in 21 of 23 windows, at most 1077 ms; the new tests wait up to 3 s for a
  WM's own extents and then for two readings in a row to agree.
- Out of scope, reported only (decision D5): `maximize(true)` on a glass `POPUP` aborted WSLg's Weston (an assertion
  in `weston_wm_window_set_allow_commits`, `xwayland/window-manager.c:1168`; WSLGd restarted Weston). A JavaFX
  application cannot do that through public API, since a `PopupWindow` is not a `Stage`; it is a Weston defect. No
  story.
- The Weston source was read on branch `working` of `microsoft/weston-mirror`. The exact build in this WSLg was not
  identified, but its numbers match every measurement above. The i3 source was read on branch `next` of `i3/i3`
  (2026-09-27).
- Related: US-065 (the `TITLED` faults this story measured and left to a story of their own, decision D4).

## Resolution (2026-10-20, uncommitted; the PR number is added when it lands)

All on master `c666773a96` in WSL (Ubuntu 26.04, JDK 27 Temurin 27+35, Maven 3.10.0, GTK 3.24.52). The decisions
D1-D5 were taken by the manager as acting maintainer, from the survey and the source research below. The raw
evidence stays outside the repository, in the session scratchpad: the probe's per-WM tables with every column, the
`xprop` and `xwininfo` dumps of every window and state, glass's event logs, the frame pixel profiles, the gate
scripts' reports and Maven logs, and the child JVMs' `values.txt`.

### Survey (2026-10-20)
- **Probe** (throwaway, not in the repository): glass windows made in a `GtkGlassChild` child JVM with
  `Application.createWindow(owner, Screen.getMainScreen(), style)`, styles as `WindowStage` builds them, each with a
  `View`. `UNTITLED`, `TRANSPARENT` and `EXTENDED` got a client of 320x240 at 100,100 or 1920x1080 at 0,0
  (`setBounds(x, y, true, true, -1, -1, w, h, 0, 0)`), `TITLED` a window of 400x300 at 200,200, and a `POPUP` (owner
  an `UNTITLED` window) a client of 200x150 at 300,300. States in one JVM: normal, maximized, restored, full screen,
  restored. A fresh JVM per window asked for a minimum of 123x45 and a maximum of 800x600.
- **Values,** each read once it had stayed the same for at least 1 s: the client's root position and size; the WM
  frame (the client's ancestor that is a child of the root) and the client's offset in it; `_NET_FRAME_EXTENTS`,
  `_GTK_FRAME_EXTENTS`, `_NET_WM_STATE` and `WM_NORMAL_HINTS`; `gdk_window_get_root_origin` and
  `gdk_window_get_origin` of glass's own `GdkWindow`; glass's `Window` and `View` values. The tables come from the
  quiet mode, which makes no X request in event handlers and none in the first 1.5 s after `setVisible`. The
  instrumented mode also logs the X and GDK state at every glass event; its round trips change the timing.
- **Displays:** a private rootless Xvfb 21.1.22 at 1920x1080x24 with no WM, with openbox 3.6.1 and with i3 4.25.1
  (both in their default configuration; i3 tiles, `default_border normal 2`); WSLg `:0`: Xwayland 24.1.12 and
  "Weston WM" (WSLg 1.0.79, weston 9.0.0-215-ga7e35fda), with `WAYLAND_DISPLAY` unset.
- **Not available:** GNOME Shell (Mutter), KDE Plasma (KWin) and Xfce (xfwm4) on X11; there is no such host. Their
  behaviour was read from their sources instead (below).

Normal state, before the fix. "Undecorated" stands for `UNTITLED`, `TRANSPARENT` and `EXTENDED`, which gave the same
numbers in every row and every state. "Hints" is `WM_NORMAL_HINTS` for a minimum of 123x45 and a maximum of 800x600.

| WM | window, as asked | `_NET_FRAME_EXTENTS` | client on root | WM frame (client offset) | glass `Window` | `View` | hints min / max |
| --- | --- | --- | --- | --- | --- | --- | --- |
| none | undecorated 320x240 at 100,100 | none | 100,100 320x240 | none | 100,100 320x240 | 0,0 | 123,45 / 800,600 |
| none | `TITLED` 400x300 at 200,200 | none | 200,200 400x300 | none | 200,200 400x300 | 0,0 | 123,45 / 800,600 |
| openbox | undecorated 320x240 at 100,100 | 0,0,0,0 | 100,100 320x240 | 100,100 320x240 (0,0) | 100,100 320x240 (0,0 in the race, note d) | 0,0 | 123,45 / 800,600 |
| openbox | `TITLED` 400x300 at 200,200 | 1,1,20,5 | 201,220 398x275 | 200,200 400x300 (1,20) | 200,200 400x300 (-1,-20 in the race) | 1,20 | 121,20 / 798,575 |
| i3, default layout | undecorated, any | 0,0,0,0 | 0,0 1920x1080 (tiled) | 0,0 1920x1080 (0,0) | 0,0 1920x1080 | 0,0 | not written (a) |
| i3, default layout | `TITLED` | 2,2,18,2 | 2,18 1916x1060 | 0,0 1920x1080 (2,18) | 0,0 1920x1080 | 2,18 | not written (a) |
| i3, tabbed, with a sibling | `UNTITLED` 320x240 | 2,2,0,2 | 2,18 1916x1060 | 0,18 1920x1062 (2,0) | 0,18 1920x1062 | 2,0 | - |
| Weston | undecorated 320x240 at 100,100 | 38,38,59,38 | 100,100 320x240 | 68,68 384x304 (32,32) | 62,41 396x337 | 38,59 | 47,1 / 724,503 |
| Weston | undecorated 1920x1080 at 0,0 | 38,38,59,38 | 0,0 1920x1080 | -32,-32 1984x1144 (32,32) | -38,-59 1996x1177 | 38,59 | 47,1 / 724,503 (b) |
| Weston | `TITLED` 400x300 at 200,200 | 38,38,59,38 | 206,227 324x203 | 168,168 400x300 (38,59) | 168,168 400x300 | 38,59 | 47,1 / 724,503 |
| each of them | `POPUP` 200x150 at 300,300 | none | 300,300 200x150, override-redirect | none | 300,300 200x150 | 0,0 | 200,150 / 200,150 (not resizable) |

- (a) i3 tiles every managed window to 0,0 1920x1080 whatever it asked for and marks it
  `_NET_WM_STATE_MAXIMIZED_HORZ|VERT`, so glass treats it as maximized and writes no minimum or maximum.
- (b) The client shrinks to 724x503, and glass reports -38,-59 800x600.
- `_GTK_FRAME_EXTENTS`, a GTK convention a client sets for its own client-side shadow (not EWMH), never existed on
  any of these servers. `WM_NORMAL_HINTS` carry `PPosition`, never `USPosition`.
- Without a WM, maximize and full screen change nothing. openbox reports 0,0,0,0 for an undecorated window in every
  state, and glass reports its client. openbox reports 0,0,19,0 for a maximized `TITLED` window and 0,0,0,0 in full
  screen.
- The `xprop` dump of an `UNTITLED` 1920x1080 client on Weston (note a confirmed); its frame is a depth-32 window with
  the client at +32+32:
  ```
  _NET_FRAME_EXTENTS(CARDINAL) = 38, 38, 59, 38
  _MOTIF_WM_HINTS(_MOTIF_WM_HINTS) = 0x3, 0x26, 0x0, 0x0, 0x0
  _NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_NORMAL
  ```
  `_MOTIF_WM_HINTS` asks for no decorations. Weston frames the window anyway and reports a decorated frame's extents.
- What Weston draws, from the pixels of the depth-32 frame: for the `TITLED` window the opaque part is the frame inset
  by 32 px on every side, root 200,200 336x236 (border 6 + client 324 + border 6; title bar 27 + client 203 +
  border 6), exactly where the stage was asked to be. The 32 px margin is a drop shadow: 12 px of it on the left and
  top (alpha at most 15 %), 24 px on the right and bottom (alpha at most 42 %), the rest transparent. An undecorated
  window gets the same shadow and nothing else, so for it 38, 38, 59, 38 matches neither what is drawn (the client)
  nor the frame window (the client plus 32).
- From the sources (read 2026-10-20), for a window whose `_MOTIF_WM_HINTS` ask for no decorations, which GTK 3 sets on
  every non-`TITLED` glass window: Weston (WSLg's fork and upstream) reports 38,38,59,38 and draws only a shadow;
  openbox, KWin and xfwm4 report 0,0,0,0; Mutter gives the window no frame and sets no `_NET_FRAME_EXTENTS`; i3
  reports 0,0,0,0 except in a tabbed or stacked container with more than one child, where it draws a real border and
  reports it. Weston is the only one that reports extents for a frame it does not draw.

### The openbox rows, explained
Reproduced with one window per freshly started openbox and the quiet probe: `UNTITLED` gave glass 0,0 with its client
at 100,100 in 4 of 4 runs, and `TITLED` gave client 0,0 and glass -1,-20 400x300 in 3 of 4. The sequence, from
`xev -root` on a second connection and from glass's event log:
1. GTK creates the client at 0,0, asks for 100,100 (`PPosition`) and maps it. openbox, which holds
   SubstructureRedirect, applies the move for the still unmanaged window: ConfigureNotify, client at 100,100.
2. openbox creates its frame at 0,0 1x1 and reparents the client into it at offset 0,0, so the client is at 0,0 on
   the root. Only then does it move and size the frame to 100,100 320x240 and map it (`PPosition` involves no
   placement policy).
3. Glass handles the ConfigureNotify of step 1 while the server is between the reparent and the frame move.
   `process_configure` did not use the event's coordinates. It called `gdk_window_get_root_origin`, which asks the
   server now and got the client's 0,0 less (left, top). Glass stored 0,0, with the `View` at 0,0.
4. openbox moves the frame but sends the client no synthetic ConfigureNotify, because the client's root position is
   what the real one already said. Glass gets no further configure and reports 0,0 for good.

`TITLED` takes the same step 3 with openbox's 1,1,20,5 already on the client (openbox answers glass's
`_NET_REQUEST_FRAME_EXTENTS` by briefly managing the unmapped window), so the root origin is -1,-20. Then
`update_frame_extents` sees changed extents and calls `set_bounds(-1, -20, ...)`, openbox puts the frame at -1,-20,
the client lands at 0,0, and every later reading agrees: the story's row.

The cause is this story's position code, not the WM's placement and not the frame extents: a live root-origin read
in `process_configure` while a reparenting WM has not yet placed its frame, and for `TITLED` also
`update_frame_extents` feeding that position back into `gtk_window_move`. Weston showed the same race, for
undecorated windows too: a configure handled after the extents were on the window but before their PropertyNotify
stored the client less (38, 59), and `update_frame_extents` then moved the client there (62,41 instead of 100,100;
1 of 11 undecorated windows in quiet runs, 6 of 6 instrumented). The fix removes the undecorated half: their position
now comes from the event, and `update_frame_extents` no longer moves them. After the fix the openbox row did not occur
in 16 runs (3 of 10 before the fix under the same load) and no undecorated window raced on Weston. The `TITLED` half
is US-065.

### The position source, confirmed on Weston
In every row of every Weston run, `gdk_window_get_root_origin` returned the client's root position less (left, top)
of `_NET_FRAME_EXTENTS`, and glass's settled x, y were that value:

| row | client | extents left, top | root origin | WM frame origin | glass x, y (before the fix) |
| --- | --- | --- | --- | --- | --- |
| `UNTITLED` 320x240, normal | 100,100 | 38,59 | 62,41 | 68,68 | 62,41 |
| `UNTITLED` 1920x1080, normal | 0,0 | 38,59 | -38,-59 | -32,-32 | -38,-59 |
| `UNTITLED` 320x240, maximized | 0,0 | 6,27 | -6,-27 | -32,-32 | -6,-27 |
| `UNTITLED` 320x240, full screen | 0,0 | 0,0 | 0,0 | 0,0 | 0,0 |
| `TITLED`, normal | 206,227 | 38,59 | 168,168 | 168,168 | 168,168 |
| `TITLED`, maximized | 6,27 | 6,27 | 0,0 | 0,0 | 0,0 |
| `POPUP` (override-redirect) | 300,300 | none | 300,300 | none | 300,300 |

- The root origin is the WM frame's origin only where the client's offset in the frame equals (left, top): `TITLED`
  and full screen. For an undecorated window it lies 6 px left of and 27 px above Weston's frame.
- GTK 3.24's source agrees (`gdk_x11_window_get_frame_extents`, branch `gtk-3-24`). It uses `_NET_FRAME_EXTENTS`
  only when the WM lists the atom in `_NET_SUPPORTED` (Weston and openbox do, i3 does not); otherwise it walks the
  window tree to the WM's frame. An override-redirect window gets its own position.
- The configure event carries the client's root position: GDK translates a real ConfigureNotify with
  `XTranslateCoordinates` when it reads it, and a synthetic one carries the root coordinates ICCCM prescribes.

### All-zero reports and state changes (WSLg)
Weston's extents through the states (`xprop -spy`, two windows, two runs): 38,38,59,38; full screen 0,0,0,0; normal
38,38,59,38; maximized 6,6,27,6 (border and title bar, no shadow margin); normal 38,38,59,38. Glass's `Window`:

| window | normal | full screen (client 0,0 1920x1080) | maximized | restored from maximized |
| --- | --- | --- | --- | --- |
| `TITLED`, the same before and after the fix | 168,168 400x300 (c) | 0,0 **1996x1177** | frame 0,0 **400x300** (client 388x267), still `MAXIMIZED` | 136,136 336x236 (client 260x139: 64x64 smaller, 32 px up-left) |
| `UNTITLED` 320x240, before the fix | 62,41 396x337 | 0,0 **1996x1177** | client 0,0 **384x304**, glass -6,-27 396x337 | client 62,41 256x176, glass 24,-18 332x273 |
| `UNTITLED` 320x240, after the fix | 100,100 320x240 | 0,0 1920x1080 | 0,0 1920x1040 | 100,100 320x240 |

- (c) 136,136 when the startup race hits, as it did in every run under load, before and after the fix alike (US-065).
- The old extents stay in force after an all-zero report: a full-screen window reports the client plus the stale
  76 x 97. openbox (`TITLED` 1922x1105) and i3 (`TITLED` 1924x1100) do the same.
- A change of extents while maximized re-runs `set_bounds` with the stored size, so the still-maximized window is
  resized back (glass events MAXIMIZE 1984x1104, 400x300, 1920x1040, 400x300).
- Restore lands wrong: on Weston 64x64 smaller and moved; on openbox a restored `TITLED` window ends at 0,0 1920x1080
  (openbox first restores 200,200 400x300, and 24 ms later a request from the client moves and sizes it again).
- After the fix an undecorated window has none of these faults, because its extents stay zero. The `TITLED` ones
  remain; they are US-065 (D4).

### Decisions
- **D1: the undecorated frame types (`UNTITLED`, `TRANSPARENT`, `EXTENDED`, and popups) ignore
  `_NET_FRAME_EXTENTS` (fix option 1).** An undecorated stage's x, y are the client's root position, its width and
  height the client size, its scene sits at 0,0, an explicit width or height is the client size, and its minimum and
  maximum sizes are client sizes, written to `WM_NORMAL_HINTS` unchanged. It never writes the extents cache that later
  `TITLED` windows start from. Why:
  - it is what openjdk/jfx#2139 (JDK-8354943) does at head `28ba295764`, so the fork stays mergeable with upstream;
  - AWT's `XDecoratedPeer` gives undecorated frames zero insets, never reads the WM's extents for them and never
    caches them;
  - the window asked for no frame (`_MOTIF_WM_HINTS` decorations 0). openbox, xfwm4, KWin and i3's default layout
    report 0 for it and Mutter nothing; only Weston reports a decorated frame's extents, and it draws only a shadow.
  - Risk accepted: a WM that draws a real frame around an undecorated window. i3 does, in a tabbed or stacked
    container with siblings; the stage then reports its client without i3's 2 px border (measured, see "Gates"),
    as AWT and #2139 do. In a tiling layout the WM owns the geometry anyway.
  - The position comes from the configure event (`event->x`, `event->y`), as in #2139, rather than from
    `gdk_window_get_origin` as option 1 was written. Both are the client's root position; the event's value is the
    one GDK translated when it read the event.
  - Option 2 rejected: an X round trip per change, it works only under a reparenting WM, and under i3 tabbed it would
    put i3's border into an undecorated stage.
- **D2: `TITLED` (`DECORATED`, `UNIFIED`, `UTILITY`) stays as it is.** The stage is the WM's frame as
  `_NET_FRAME_EXTENTS` describes it (the client plus the extents), the scene sits at (left, top), and the minimum,
  maximum and explicit sizes are frame sizes (hints = asked less the extents, as `GtkWindowNativeTest` pins). Only
  `TITLED` windows write the extents cache. The fork keeps its own `TITLED` view offset (`gdk_window_get_origin` less
  the root origin), where #2139 uses `event->x` less the root origin.
- **D3: a decorated stage on WSLg keeps Weston's 32 px shadow margin in its bounds; documented here, no story.** Glass
  reports what the WM publishes. EWMH defines the extents as "widths of the respective borders added by the Window
  Manager" and says nothing about shadows. Glass has no portable way to tell a shadow from a border; fixing it would
  mean recognising "Weston WM" and its theme constants. AWT and #2139 behave the same, and the error is Weston's
  (Mutter publishes only the borders it draws). Measured: an opaque window at 200,200 336x236 against glass's
  168,168 400x300.
- **D4: the `TITLED` faults go to one new story, US-065.** The all-zero report ignored in full screen, a change of
  extents while maximized, the restore from maximized, the startup race, and, measured with the fix, a decorated stage
  on Weston that reports its position 32 px from where it was put (`setPosition(150, 120)` reads back 118,88). They are
  not fixed here: each is `TITLED` behaviour (D2), they share one mechanism (`update_frame_extents` and
  `process_configure` re-running `set_bounds` with a stored or live position), and #2139 reworks exactly that. The
  openbox undecorated row (note d) is not in it, because it did not recur after the fix.
- **D5: out of scope.** The Weston abort on `maximize(true)` of a glass popup (see "Notes").

### The fix
`glass_window.cpp` only, two hunks; `glass_window.h` is unchanged.
- `update_frame_extents` returns at once unless `frame_type == TITLED`: no extents stored, the cache never written, no
  `set_bounds` re-run. An undecorated window keeps the zero extents `WindowGeometry()` gives it. The constructor
  already gave only `TITLED` the cached extents, and `process_realize` already asked for extents only for `TITLED`.
- `process_configure`: `TITLED` runs the code it had (the root origin for the position, `gdk_window_get_origin` less
  it for the view), only indented. Every other frame type takes x, y from `event->x`, `event->y` and its view at 0,0.
- Nothing else had to change:
  - `process_configure` has one caller, the event loop in `GlassApplication.cpp`, and `WindowContextTop` is the only
    implementation;
  - with zero extents, every size, size request, minimum and maximum computed from them is a client size for a
    non-`TITLED` window;
  - only `TITLED` writes the cache, and only the `TITLED` constructor reads it;
  - the stored position is read by `set_bounds`, `notify_window_move` and the screen lookup, which take it as it is;
    the view offset is read only by `ggtk_view_get_x` and `ggtk_view_get_y`;
  - no Java change.
- Linux build in WSL (g++ 15.2.0; the GTK glass compiles with `-Werror`): 0 warnings in `glass_window.cpp` before and
  after, and `nm -D --defined-only` of `libglassgtk3.so` identical (4,445 lines, 64 `ggtk_*` functions). macOS and
  Windows do not build this file.
- Weston after the fix, quiet probe: undecorated windows report their clients in every state (the table above; an
  `UNTITLED` 1920x1080 window at 0,0 reports 0,0 1920x1080, `View` 0,0). `TITLED` is the same as in the survey, and the
  same as the unfixed build in an alternating A/B (3 runs each). For about 40 ms after the map an undecorated window
  reports 32,32, while Weston's new frame is still at 0,0, and then settles where it was asked; nothing feeds that
  into `gtk_window_move` any more.

### Tests
In `modules/javafx.graphics`; Linux only (`@EnabledOnOs(LINUX)`), and skipped without `DISPLAY` and
`-DPRIVATE_DISPLAY=true` like every GTK display class (`GtkGlassChildJvm.requireDisplay`).
- `GtkGlassShim` gains `setNetFrameExtents(xid, left, right, top, bottom)` (`XChangeProperty` of 32-bit `CARDINAL`s
  on the client, as a WM does, then `XSync`), `windowSize(xid)` (`XGetGeometry`: the client size) and
  `supportsNetWmHint(name)` (`gdk_x11_screen_supports_net_wm_hint`: whether the WM lists the atom in
  `_NET_SUPPORTED`, which decides whether GDK's frame origin uses the extents).
- `GtkFrameExtentsStandIn`, a helper, not a test class. It waits up to 3 s for non-zero extents of the WM's own (not
  at all without a WM), otherwise writes Weston's 38, 38, 59, 38 on the window. It then takes readings until two in a
  row agree, writing the stand-in back first if the WM overwrote it (openbox writes 0,0,0,0 when it resizes an
  undecorated window; it never did in these runs).
- `GtkFrameExtentsNativeTest`, 14 tests on one child JVM. It shows `TITLED` first, so that no other window can fill
  the cache before it. Every assertion is on readings taken after an explicit `setPosition(150, 120)` of the placed
  window, and each also requires non-zero extents in force and a settled reading. So it does not depend on the startup
  race; the readings at show time are recorded, not asserted.
  - `anUndecoratedWindowIsItsClient` (`UNTITLED`, `TRANSPARENT`, `EXTENDED`, `POPUP`): the `Window` rectangle and the
    `View` offset together equal the client rectangle and 0,0;
  - `anUndecoratedWindowSizeIsItsClientSize` (the same four): `setSize(500, 400)` gives a 500x400 client;
  - `undecoratedSizeLimitsReachTheWindowManagerAsAsked` (not the popup): hints min 123,45 max 800,600;
  - the `TITLED` control (D2), three tests: the `Window` is the client plus the extents, an explicit size is the frame
    size, and the hints are the sizes asked less the extents. Its position is the client's less (left, top) and the
    `View` at (left, top) where GDK takes the frame from the extents; otherwise the client's position and 0,0.
- `GtkUndecoratedStageBoundsTest`, 2 tests, the public-API check: an `UNDECORATED` `Stage` at 0,0 with a scene as
  large as the screen (1920x1080) reports 0,0 1920x1080 with its scene at 0,0, and its rectangle equals its client's.
- **What the stand-in covers:** everything glass does with the extents itself (the size it reports, the size it asks
  for, the hints), on any display. The position half is GDK's: without a WM, GDK ignores the property for the frame
  origin, so the position is tested only under openbox and on Weston. `GtkUndecoratedStageBoundsTest` covers the
  position only where the WM's own extents are in force when the stage's configure events arrive (Weston): the
  stand-in comes after the stage is placed, and glass no longer sets an undecorated window's bounds again, so no
  configure reads the position with it in force. Under openbox `GtkFrameExtentsNativeTest` covers the position, since
  it moves each window after the stand-in is in force. Both class javadocs say so.
- Added run time: the native class about 14 s without a WM and 26 s under openbox (the 3 s wait per window), the stage
  class 2-5 s.

Before the fix (the unfixed natives with the new classes; each setup run twice, with the same results): the native
class fails 11 of 14 and the stage class 2 of 2; the `TITLED` control passes.
- No WM: an undecorated `Window` 150,120 396x337 for a 150,120 320x240 client (the popup 276x247 for 200x150);
  `setSize(500, 400)` gives a 424x303 client; hints min 47,1 max 724,503; the stage 0,0 1996x1177 for a 0,0 1920x1080
  client.
- openbox: the same sizes and hints, and the position wrong too: `Window` 112,61 with the `View` at 38,59; the stage
  -38,-59 1996x1177 with the scene at 38,59.
- WSLg `:0`, with Weston's own extents: the stage class fails 2 of 2, stage -38,-59 1996x1177, scene 38,59, hints max
  32691,32670.

Mutant proof: a scratch copy with one hunk of the fix reverted and the natives rebuilt. Failures of the native class /
the stage class:

| mutant | no WM | openbox |
| --- | --- | --- |
| (a) the early return in `update_frame_extents` reverted | 11 of 14 / 2 of 2 | 11 of 14 / 2 of 2; repeated by the reviewer on its own clone: 11 of 14, the same `libglassgtk3.so` |
| (b) the `process_configure` position change reverted | none / none | 6 of 14 / none |

- (a) breaks the size half: `Window` 396x337 for a 320x240 client, hints 47,1 and 724,503, stage 1996x1177. The
  `TITLED` controls pass.
- (b) is caught where GDK's frame origin uses the extents: under openbox, `UNTITLED`, `TRANSPARENT` and `EXTENDED`
  report 112,61 with the `View` at 38,59 for a client at 150,120. The popup passes (override-redirect, so GDK gives its
  own position), the hints pass, and the stage class passes for the reason above.
- Rebuilt with the whole fix, the scratch copy gives the same `libglassgtk3.so` as the fixed tree.

### Gates
| gate | before the fix | after the fix |
| --- | --- | --- |
| `test/com/sun/glass/ui/gtk/*Test` on a private Xvfb with no WM (`.github/scripts/run-gtk-display-tests.sh`, `-DPRIVATE_DISPLAY=true -DUSE_ROBOT=true`) | two runs: 32 classes, 194 tests, 0 failures, 0 skipped | 34 classes, 210 tests (+14 and +2), 0 failures, 0 skipped; every other class with its counts from before |
| the same under openbox | two runs: 32 classes, 167 tests, 0 failures, 3 skipped, 5 classes `Tests run: 0` | 34 classes, 183 tests (+14 and +2), 0 failures, the same 3 skipped and 5 `Tests run: 0` |
| `GtkWindowNativeTest` (pins the `TITLED` hints) | 6/6 in all four passes | 6/6 in both passes |
| WSLg `:0`, one class per Maven run | `GtkUndecoratedStageBoundsTest` fails 2 of 2 | `GtkFrameExtentsNativeTest` 14/14, `GtkWindowNativeTest` 6/6, `GtkUndecoratedStageBoundsTest` 2/2 (stage 0,0 1920x1080 = the client, scene 0,0, hints max 32767,32767) |
| JDK-8329821 under i3: `ContextMenu.show(anchor, Side.BOTTOM, 0, 0)` on a decorated stage | passes, 2 runs | passes, 2 runs: the menu is override-redirect and 108x80 in X, in JavaFX and in its scene, with no extents |
| JDK-8348095 under i3: the same in a full-screen stage | passes, 2 runs | passes, 2 runs: the menu's content sits 1,-2 from the anchor's bottom-left, in X exactly where JavaFX computed it |
| i3 tabbed with a sibling, `UNTITLED` 320x240 | 0,18 1920x1062, `View` 2,0: i3's frame | 2,18 1916x1060, `View` 0,0: the client without i3's 2 px border (D1's accepted risk). Alone in the container both report 0,0 1920x1080 |
| the openbox race of note (d), one window per fresh openbox | 3 of 10 runs under load (8 busy loops at nice 10, openbox at nice 19), 0 of 4 without | 0 of 12 under load, 0 of 4 without |
| headless (`DISPLAY` unset) | 31 tests in the 5 headless classes | the same 31; the 2 new classes report `Tests run: 0` with the other display classes |

- The gate took 667 s and 681 s before the fix and 698 s after. No run left an `hs_err` file, and the runs left the
  natives unchanged. Every run except those on `:0` was on a private Xvfb.
- In the JDK-8348095 scenario the full-screen stage reports 0,0 1924x1100 before and after the fix: the stale
  `TITLED` extents of US-065.
- Review: the reviewer checked the C change against D1 and #2139, the shim's FFM descriptors against the C
  prototypes, the conventions of all five files and the gate evidence, and repeated mutant (a). It found two wrong
  comments, both fixed: the stage class's javadoc claimed the position half under openbox, and the stand-in's and
  the shim's javadocs said that no WM on a private Xvfb publishes extents for an undecorated window, which i3 tabbed
  contradicts. With the corrected comments, both new classes passed again with no WM and under openbox (14/14 and
  2/2 each).

### Upstream
- **JBS,** searched on 2026-10-20 through the REST API (project JDK, component javafx; subcomponent `window-toolkit`
  where named):
  - "frame extents": 4 hits (JDK-8181406, JDK-8089923, JDK-8332222, JDK-8329821); `_NET_FRAME_EXTENTS`: 4
    (JDK-8181406, JDK-8332222, JDK-8112196, JDK-8329821); "WSLg": 0; "Weston": 0; `window-toolkit` and "WSL": 2
    (JDK-8237454, JDK-8382165); `window-toolkit`, "undecorated", Linux and position, size or offset: 12; "Xwayland":
    33, robot, screen capture and test issues; `window-toolkit` issues created since 2026-09-27: 6, none related.
  - The ones that touch this code: JDK-8329821 and JDK-8348095 (above); JDK-8332222 (KDE, a maximized stage shrinks,
    fixed in jfx24) and JDK-8181406 (2017, `setHeight`), unrelated; JDK-8089923 (late extents and the position; open,
    but confirmed fixed by PR #915 in 2023); JDK-8237454 (WSL 2 with VcXsrv, a gap under a menu bar; open since 2020,
    not analysed); JDK-8365201 (restoring a maximized window exceeds the screen; open, and `28ba295764` of #2139
    claims to fix it).
  - **None reports a WM that sets non-zero `_NET_FRAME_EXTENTS` on an undecorated window by itself.**
- **openjdk/jfx#2139** (JDK-8354943): open, `rfr`, one approval (2026-09-06), 48 commits, head `28ba295764`
  (2026-10-18), last updated 2026-10-20. 29 files; `glass_window.cpp` +1116/-725, merging `WindowContextBase` and
  `WindowContextTop` into `WindowContext`. By reading (it was not built here):
  - it fixes this story's case as this fix does: `update_frame_extents` starts with
    `if (frame_type != TITLED) return;`, `load_cached_extents` is `TITLED` only, and `process_configure` gives a
    non-`TITLED` window `x = event->x; y = event->y;` with its view at 0,0;
  - for `TITLED` it also touches what US-065 is about: a full-screen window reports its client size, a change of
    extents while maximized or full screen waits for the restore, and positions from configure events are used only
    after the first map;
  - neither the PR nor JDK-8354943 mentions WSLg, Weston or undecorated windows' extents.
- **Upstream master** (`cb3335ea3a`, 2026-10-20) has had the same `glass_window.cpp` since `a3b85676cd` (2026-01-14),
  so the problem is upstream too.
- A JBS bug report is drafted, kept outside the repository until it is filed: Weston's extents on undecorated stages,
  citing JDK-8329821 and JDK-8348095 and proposing to link it to JDK-8354943 as fixed by #2139.
