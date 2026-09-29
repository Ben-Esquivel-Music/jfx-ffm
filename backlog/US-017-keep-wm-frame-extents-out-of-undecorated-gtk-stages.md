# US-017 — Keep a window manager's frame extents out of undecorated GTK stage bounds

**Status:** 📋 Ready (measured on WSLg and on openbox; the cause is in `glass_window.cpp`: `update_frame_extents` for the size and the hints, and for the position glass's use of GDK's frame origin (`gdk_window_get_root_origin`, since JDK-8348095), which GDK computes from the same extents; the right behaviour is still to be decided) · **Found:** 2026-09-27, US-008 (explaining the `GtkWindowNativeTest` failures on WSLg)

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
All in `modules/javafx.graphics/src/main/native-glass/gtk/glass_window.cpp`:
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
- (c) In one run Weston did not set the extents during the whole scenario. The window stayed where Weston creates
  its frame windows, at -32768,-32768 (`weston_wm_window_create_frame`), and glass reported -32768,-32768 400x300.
  The frame window's size was read shortly after the window closed. The client sits at (38, 59) inside a frame
  76 x 97 px larger than it: a decorated frame includes the 32 px margin too.
- (d) The same in two runs. Glass applied no extents (the view is at 0,0 and the window is the client), yet it
  reports a position that is not the client's. Not explained.

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
  - i3: answers `_NET_REQUEST_FRAME_EXTENTS` with its default border and title bar whatever the window's
    decoration (`src/handlers.c`, branch `next`), which gave undecorated popups non-zero extents (JDK-8329821).
    Glass no longer asks for them there. i3 also sets the extents unasked on every window it manages, to the border
    it draws (`x_push_node`, `src/x.c`); whether that is non-zero for an undecorated stage is not known.
- **On WSLg, for such a stage:**
  - its x and y are the client's position less (38, 59), and its width and height are the client's plus 76 x 97
    (measured);
  - its scene sits at (38, 59) in the stage instead of (0, 0) (measured);
  - an explicit width or height gives a client 76 or 97 px smaller (by the code, `set_bounds`);
  - its minimum and maximum reach the WM 76 x 97 px smaller, and a minimum height of 45 becomes 1 (by the code;
    measured for a `TITLED` window).
- **Decorated stages on WSLg** include Weston's 32 px shadow margin on every side in their bounds, because Weston
  counts it in the extents. That is a separate question; see the acceptance criteria.
- **Not affected:** Windows and macOS; Linux without a WM; popups on WSLg, which Weston does not frame (by
  Weston's source, above).

## Fix options (to be decided)
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
  settle.
- The Weston source was read on branch `working` of `microsoft/weston-mirror`. The exact build in this WSLg was not
  identified, but its numbers match every measurement above. The i3 source was read on branch `next` of `i3/i3`
  (2026-09-27).
