# US-015 — Keep the GTK display tests from changing the developer's desktop

**Status:** 📋 Ready (side effects read from the test code, from GTK 3.24's source and from runs on WSLg and on a private Xvfb with and without openbox; the maintainer's opt-in decision comes first) · **Found:** 2026-09-27, US-008 (F3 review of the GTK display tests: `GtkScreenNativeTest` and `GtkClipboardNativeTest` change the display's state, and whether the display tests should be opt-in was left open)

## Story
As a developer who runs the `javafx.graphics` module tests on a Linux desktop or in WSL,
I want a plain module run to leave my display and my account alone: no windows or file choosers on my screen, and my
clipboard, my window manager's root window properties, my file chooser settings and my recent files as they were,
so that the module tests are safe to run on the machine I work on, and the GTK display tests run where they belong:
on a private virtual display.

## Problem
The GTK glass tests in `test/com/sun/glass/ui/gtk` run each scenario in a child JVM on the X11 display that
`DISPLAY` names (`GtkGlassChildJvm.java:47-62`). The 32 test classes fall into three groups:

- **No display (5):** `GtkGlassNativeHeadlessTest`, `GtkLibraryQueryHeadlessTest`, `GtkPeerRegistryTest`,
  `GtkScreencastHeadlessTest`, `GtkTraceRulesTest`. They run everywhere, CI included.
- **Robot (6), only with `-DUSE_ROBOT=true`:** `GtkDnDTargetTest`, `GtkDnDTraceTest`, `GtkEventTraceTest`,
  `GtkMotionFloodBenchmarkTest`, `GtkNullSlotsTest`, `GtkUpcallExceptionTest`. They inject input, and the javadoc
  says such scenarios "must only run on a private display" (`GtkGlassChildJvm.java:55-56`, `requireRobot` at
  `:113-117`).
- **Display (21), whenever `DISPLAY` is set:** every other class. `requireDisplay` (`:106-111`) checks only that
  the host is Linux and that `DISPLAY` is not blank. The child gets the parent's `DISPLAY` and no
  `WAYLAND_DISPLAY` (`:239`), so GTK always talks X11.

### A default run uses whatever display the shell has
- A shell started by `wsl.exe` has WSLg's `DISPLAY=:0` (checked on 2026-09-27, WSL 2.7.14 with WSLg 1.0.73.2:
  `wsl.exe -e sh -c 'echo $DISPLAY'` prints `:0`). The WSLg README says WSLg preconfigures `DISPLAY`,
  `WAYLAND_DISPLAY` and `PULSE_SERVER` in the user distro.
- So a plain module run from a WSL shell runs the 21 display classes on `:0`, and their windows and dialogs appear
  on the Windows desktop. US-008's first Linux gate did exactly that on 2026-09-27 (table below): 20 display classes
  ran their tests on `:0`, and `GtkWindowStateTraceTest` started its window-manager probe there before it skipped.
  `GtkUploadBenchmarkTest` alone took 18.2 s (20.3 s in a later run with US-008's fix).
- The same holds in any terminal of a Linux desktop session that exports `DISPLAY`.
- CI has no `DISPLAY` (see US-016), so all 21 display classes skip there.

### What each display class shows or changes
State that belongs to the user, not to the test:
- `GtkScreenNativeTest`: the root window's `_NET_WORKAREA` and `_NET_CURRENT_DESKTOP` (next section).
- `GtkClipboardNativeTest`: the `CLIPBOARD` selection (the section after it).
- `GtkCommonDialogsNativeTest`: from GTK 3.24's source (not run), every file chooser saves its
  `org.gtk.Settings.FileChooser` keys through GSettings when it closes: location mode, hidden files, sort column and
  order, sidebar width (`settings_save` in `gtkfilechooserwidget.c`), and the dialog's size and position
  (`save_dialog_geometry` in `gtkfilechooserdialog.c`). The two dialogs it accepts also add
  `/tmp/jfx-chosen-file.txt` and the two temporary files to the account's recently-used list
  (`add_selection_to_recent_list`, which calls `gtk_recent_manager_add_item`). Both live in the account, not on the
  display, so a private Xvfb does not contain them.

Windows and dialogs:
- `GtkUploadBenchmarkTest`: an undecorated 1920x1080 window at (0, 0) (`GtkUploadBenchmarkTest.java:199-209`),
  repainted with full frames for the whole class: 11.6 to 13.4 s on a private Xvfb, 18.2 s and 20.3 s on WSLg.
- `GtkWindowNativeTest`: a titled 400x300 window at (200, 200) whose title and size limits change (`:212-217`).
- `GtkUseCurrentLibraryTest`: a 300x200 window at (100, 100), then moved and resized (`:175-180`).
- `GtkPixelsCursorNativeTest`: a 60x40 stage with an icon (`:312-317`).
- `GtkRobotNativeTest`: an undecorated 60x40 stage at (100, 100) for about a second (`:193-200`). It also reads
  screen pixels around (110, 110) and the Caps Lock and Num Lock states.
- `GtkCommonDialogsNativeTest`: five GTK file chooser dialogs, on `/tmp` and on a temporary directory that it
  deletes afterwards (`:226-300`).
- `GtkWindowStateTraceTest`: three windows (`:212-222`), but only on a display without a window manager
  (`GtkTraceGolden.requireNoWindowManager`, `GtkTraceGolden.java:175-185`).

Other:
- `GtkApplicationQueriesNativeTest`: `showDocument` of two URIs with the unregistered scheme `jfxnosuchscheme:`
  (`:69-71`, `:164-169`), which it expects `gtk_show_uri` to refuse. Its GtkSettings changes stay in its own
  process (`g_object_set` on `gtk_settings_get_default()`, `GtkGlassShim.java:341-349`) and are put back.
- `GtkRobotParityTest` and `GtkScreencastDebugTest` start the screen capture helper with the D-Bus remote-desktop
  and screencast methods (`GtkRobotParityTest.java:75-78`, `GtkScreencastDebugTest.java:104-105`). What that does on
  a desktop with PipeWire and an xdg-desktop-portal is not established; the javadoc of
  `GtkScreencastDebugTest` says the machine it was captured on has neither (`:71-72`).
- `GtkScreencastNativeTest` points `user.home` into `target`, so it never touches the account's restore tokens
  (`:58-59`, `:112`).
- The other eight connect to the display and show nothing:
  - `GtkCallbackTableTest` creates and closes windows that it never shows; only its robot test (`:687-697`) shows
    one;
  - `GtkPreferencesNativeTest` changes GtkSettings in its own process and puts them back;
  - `GtkGlassNativeBindingTest`, `GtkGlassNativeLoadFailureTest`, `GtkInvokeLaterNativeTest`,
    `GtkLibraryQueryTest`, `GtkTimerNativeTest` and `GtkViewLifetimeTest`.

### `GtkScreenNativeTest` overwrites, then deletes, the window manager's root properties
- `screensScenario` (`GtkScreenNativeTest.java:241-287`) writes `_NET_CURRENT_DESKTOP` = 0 and `_NET_WORKAREA` = two
  rectangles (`:257-258`), then `_NET_CURRENT_DESKTOP` = 1 and 7 (`:273`, `:275`). Its `finally` deletes both
  (`:279-285`). It never reads what was there before. `GtkGlassShim.setRootCardinals` is an `XChangeProperty` in
  replace mode, or an `XDeleteProperty`, on the root window (`GtkGlassShim.java:899-922`).
- The class javadoc gives the reason: it "sets and removes the two root window properties itself, which no window
  manager is there to own" (`:58-59`). That is true on a bare Xvfb. Under a window manager it is not: the EWMH
  specification (Root Window Properties) says the window manager MUST set and update `_NET_CURRENT_DESKTOP`, and MUST
  set `_NET_WORKAREA` for each desktop.
- So under a window manager, the class replaces the window manager's current desktop and work area with its own
  values while it runs, and deletes them at the end. When, or whether, the window manager writes them again is not
  established.
- Other clients see each change. Glass itself selects property events on the root window
  (`GlassApplication.cpp:132`) and re-reads its screens on a `PropertyNotify` of either atom (`:298-303`), so every
  JavaFX application on that display gets the test's work areas and then the whole screen.
- `GtkEventTraceTest` sets and deletes `_NET_WORKAREA` the same way (`:413-415`). It runs only with
  `-DUSE_ROBOT=true`, but under any window manager. `GtkUpcallExceptionTest` does it too (`:253-256`), but only on a
  display without a window manager (`:100-101`).

**The no-window-manager oracles.**
- `withoutWorkareaTheVisibleBoundsAreTheWholeScreen` (`GtkScreenNativeTest.java:117-124`) expects the visible
  bounds read before the first write to be the whole screen.
- `removingTheWorkareaRestoresTheWholeScreen` (`:144-151`) expects the bounds after the deletion to be the whole
  screen and equal to that first read.
- Both hold only on a display that has no `_NET_WORKAREA`, or one that equals the whole screen.
- They passed under openbox by that coincidence. In the openbox run below, the child recorded
  `screens.0.visible=0,0,1920,1080` before the writes, `restored.0.visible=0,0,1920,1080` after the deletion and
  `env.windowManager=Openbox`. No panel or dock reserved space. The run did not record whether openbox had published
  a work area at all.
- On a desktop whose work area leaves out a panel, both tests would fail, after the class had already deleted the
  properties. This follows from the code; it was not run.

### `GtkClipboardNativeTest` takes the clipboard
- It runs two children on `DISPLAY` at the same time, then a third (`GtkClipboardNativeTest.java:129-140`). Through
  `GtkSystemClipboard.pushToSystem` they put on `CLIPBOARD`:
  - a short text with non-ASCII characters (`:90-91`), an HTML snippet, the URI `https://example.invalid/a%20b`,
    two file names under `/tmp`, 16 bytes of a custom MIME type and a 3x2 image (`content()`, `:334-349`);
  - then a URI list with a web URI and a file URI (`:399-403`);
  - and last an empty map (`:495`), which takes ownership with no targets.
- It saves nothing first and restores nothing: whatever the user had copied before the class ran is not put back.
- On WSLg: the WSLg README describes "cut/paste across Windows and Linux applications" and "clipboard integration
  for copy/paste" in its RDP backend. `GtkClipboardNativeTest` has already run on `:0` once, in US-008's first Linux
  gate (13/13 on 2026-09-27). Nobody looked at whether that changed the Windows clipboard, and the class was
  deliberately not run there again to find out.

### Checked, not a defect: `capture.offScreen` of `GtkRobotNativeTest`
US-008's F3 investigation suspected that this expectation pins undefined pixels. It does not.
- `screenCaptureLeavesTheArrayUntouchedWhenItReturnsEarly` asserts opaque black for a 2x2 capture at (-100, -100)
  (`GtkRobotNativeTest.java:128`, `:217`). The gate is only `env.gtk` and `env.depth` (`:125`), so it runs under
  any window manager or compositor, wherever GTK is 3.24.52 and the depth is 24.
- Glass hands the rectangle unchanged to `gdk_pixbuf_get_from_window` of the root window, then calls
  `gdk_pixbuf_add_alpha` with `substitute_color` FALSE (`GtkGlassNative.java:1230-1237`).
- GTK 3.24's `gdk_pixbuf_get_from_window` (`gdk/gdkpixbuf-drawable.c`, gtk-3-24 branch) creates a new cairo image
  surface of the requested size. It paints the root window's surface into it with `CAIRO_OPERATOR_SOURCE`, offset
  by (-src_x, -src_y), here (100, 100).
- At depth 24 the root window's surface has no alpha channel, so the new surface is `CAIRO_FORMAT_RGB24` and
  `gdk_pixbuf_get_from_surface` returns a pixbuf without alpha. That is why the expectation is gated on `env.depth`:
  with an alpha-carrying root the copy would be `CAIRO_FORMAT_ARGB32`, and `gdk_pixbuf_add_alpha` would copy an alpha
  of 0 instead of setting 255.
- cairo documents that a new image surface starts at 0, and that outside a surface pattern the pixels are fully
  transparent (`CAIRO_EXTEND_NONE`, the default for surface patterns). The 2x2 rectangle lies wholly outside the
  root window, so no pixel of it comes from the X server, and its RGB is 0.
- gdk-pixbuf documents that `gdk_pixbuf_add_alpha` sets the new alpha channel to 255 when the pixbuf had none.
- So the black follows from the documented behaviour of GDK, cairo and gdk-pixbuf, not from what an X server or a
  fallback pixmap holds. The capture of on-screen pixels, `screenCaptureReadsARGBFromTheRootWindow`, is already
  limited to a bare display (`:109-110`).

### Measured (2026-09-27, WSL Ubuntu 26.04, JDK 25)
All on commit e003c1f200, with US-008's change where the table says so. "Xvfb" is a private Xvfb 21.1.22 at
1920x1080x24; GTK is 3.24.52. The Xvfb rows and the last row ran
`mvn -B -ntp -o -pl modules/javafx.graphics -am test -DskipNative=true -Dsurefire.failIfNoSpecifiedTests=false`
with `-Dtest` selecting the classes. The two whole-module rows are US-008's Linux gate,
`mvn -B -ntp -pl buildtools/jslc,modules/javafx.graphics -am clean test` from a `wsl.exe` shell: first with WSLg's
`DISPLAY=:0`, then, for its acceptance runs, with `DISPLAY` unset.

| display | classes | result |
| --- | --- | --- |
| Xvfb + openbox | all 32 | 160 tests, 3 failures (the `GtkWindowNativeTest` hints that US-008 fixed), 7 skipped |
| Xvfb, no window manager | `GtkWindowNativeTest`, `GtkUploadBenchmarkTest`, with US-008 | 6/6 and 5/5 |
| Xvfb + openbox | the same two, with US-008 | 6/6 and 5/5 |
| WSLg `:0` (Weston), whole module | all 32 | 160 tests, 6 failures (fixed by US-008), 7 skipped |
| no `DISPLAY`, whole module, with US-008 | all 32 | 27 classes `Tests run: 0`; the 5 headless ones 31 tests, as in CI |
| WSLg `:0` | `GtkUploadBenchmarkTest`, with US-008's fix before review | 5/5 in 20.3 s |

- In the openbox run of all 32 classes, 25 classes ran tests. `GtkScreenNativeTest` passed 9/9 and
  `GtkClipboardNativeTest` 13/13. The six robot classes and `GtkWindowStateTraceTest` (a window manager was present)
  ran none.
- In the WSLg run of the whole module, 25 classes ran tests too, `GtkScreenNativeTest` 9/9,
  `GtkClipboardNativeTest` 13/13 and `GtkCommonDialogsNativeTest` 9/9 among them. The 6 failures were 3 in
  `GtkWindowNativeTest` and 3 in `GtkUploadBenchmarkTest`, the ones US-008's F3 then explained.
- The last row used US-008's fix before its review. The review then changed only comments and moved the error-trap
  pop of `GtkGlassShim.windowPixel` into a `finally`.

### The decision
**Recommended: opt-in.**
- The display classes run only with a flag that says the display is private and may be changed: a new system
  property in the style of `USE_ROBOT` (the Gradle-era flags are in `pom.xml:142-148`), checked in `requireDisplay`.
- A default module run then never touches the developer's display, whatever the shell exports.
- The results stop depending on whether the shell happens to have a `DISPLAY`.
- Coverage moves to the runs that pass the flag on a private virtual display. CI (US-016) passes it on a private
  Xvfb, with and without a window manager, on every push that `submit.yml` runs for (any branch except `master`,
  `main`, `jfx<N>` and `WIP*`). The Linux gate that US-008 ran in WSL passes it on a private rootless Xvfb, like the
  one US-008's F3 runs used, instead of unsetting `DISPLAY`. Any developer can do the same on a private Xvfb.
- Cost: the incidental runs on real window managers stop, and they are what found US-008's F3 failures, on WSLg.
  There is one more flag to remember. An opt-in test rots unless a gate runs it; US-016 is that gate.
- A variant: reuse `-DUSE_ROBOT=true` as the flag. It already means "private display" for the input tests
  (`GtkGlassChildJvm.java:55-56`), but it would mix "may show windows" with "may inject input".

**Alternative: keep the display classes on whenever `DISPLAY` is set.**
- Make only the classes with side effects opt-in: `GtkScreenNativeTest`, `GtkClipboardNativeTest`,
  `GtkCommonDialogsNativeTest` and `GtkUploadBenchmarkTest`.
- This keeps the incidental coverage on desktops for the others. Windows still appear on WSLg and on desktops
  during a plain run.

Either way, `GtkScreenNativeTest` must put back what it changes: a developer can still pass the flag on a desktop.

## Acceptance criteria
- **Decision.** The maintainer chooses opt-in (recommended) or the alternative, and the choice is recorded in this
  story. `GtkGlassChildJvm`'s javadoc says how to run the display classes: the flag if there is one, a private X
  server (Xvfb) with `WAYLAND_DISPLAY` unset, and openbox for window-manager coverage.
- **Root properties.** No test deletes or overwrites a root window property that it did not create, unless it
  restores it exactly.
  - `GtkScreenNativeTest` and `GtkEventTraceTest` read the type, format and data of `_NET_WORKAREA` and
    `_NET_CURRENT_DESKTOP` first, or note that they are absent. Their `finally` puts exactly that back.
  - Checked under a window manager that publishes both properties on a private Xvfb (`xprop -root _NET_WORKAREA
    _NET_CURRENT_DESKTOP` shows both before the class): the `xprop` output before and after the class is identical.
  - Without a window manager, restoring means deleting, so the no-window-manager trace goldens do not change.
    **Never regenerate a golden.**
- **Work-area oracles.** `withoutWorkareaTheVisibleBoundsAreTheWholeScreen` and
  `removingTheWorkareaRestoresTheWholeScreen` derive their expectation from the saved properties, as
  `workareaOfTheCurrentDesktopBecomesTheVisibleBounds` already does for the written ones.
  - They pass under a window manager whose work area is smaller than the screen, for example with a dock window
    that reserves a strut on the private Xvfb (`xprop -root _NET_WORKAREA` shows the smaller area before the class).
  - Each of them fails on a scratch mutant that ignores the saved state (reverted afterwards).
- **Clipboard.** `GtkClipboardNativeTest` runs only when the run says the display is private (the flag), whatever is
  decided for the other classes. It cannot hand the user's earlier content back without a process that outlives
  it. If the maintainer wants the WSLg bridge settled, it is settled from WSLg's documentation or source, not by
  running the test on `:0`.
- **Screen capture helper.** It is established whether `GtkRobotParityTest`'s remote-desktop scenario and
  `GtkScreencastDebugTest` open an xdg-desktop-portal session (a permission prompt) on a desktop with PipeWire and
  a portal. If they do, they become opt-in like the clipboard test.
- **File chooser state.** `GtkCommonDialogsNativeTest` leaves the account's file chooser settings and recently-used
  list as they were. For example, its child runs with its own `XDG_DATA_HOME` and `XDG_CONFIG_HOME` under `target`
  and with `GSETTINGS_BACKEND=memory`, as `GtkScreencastNativeTest` gives its children a `user.home` under `target`.
  `GtkGlassChildJvm.start` changes only `WAYLAND_DISPLAY` and `DISPLAY` in a child's environment today (`:236-242`).
  - Checked on a private Xvfb, in an account whose GSettings backend keeps what is written (dconf or keyfile):
    `~/.local/share/recently-used.xbel` and `gsettings list-recursively org.gtk.Settings.FileChooser` are the same
    before and after the class.
  - The same check on the unchanged class records what it wrote, which settles the claim above that was read from
    GTK's source.
- **Default run (if opt-in is chosen).** With `DISPLAY` naming a private Xvfb and no flag, every class but the five
  headless ones reports `Tests run: 0`, as it does with no `DISPLAY`. In a clean tree, `target/gtk-glass-child` then
  holds only the three directories of the headless classes' children: `GtkGlassNativeHeadlessTest.headlessScenario`,
  `GtkLibraryQueryHeadlessTest.tableScenario` and `GtkLibraryQueryHeadlessTest.useCurrentScenario`. This is the code
  path of a WSL shell with `:0`, checked without touching the user's desktop.
- **Coverage kept.** With the flag, on a private Xvfb:
  - with no window manager and with openbox, each display class runs as many tests as the unchanged tests ran on
    the same display (a baseline run made first), with no failure;
  - every skipped test has its reason in its surefire `<skipped>` element;
  - every class that reports `Tests run: 0` is listed in this story with its reason, because surefire records none
    for a class-level skip: the robot classes without `-DUSE_ROBOT`, and `GtkWindowStateTraceTest` under openbox.
- **Headless.** With no `DISPLAY`, as in CI, nothing changes: 27 classes report `Tests run: 0`, and the 5 headless
  classes run 31 tests.
- **`capture.offScreen`.** No behaviour change. The comment at `GtkRobotNativeTest.java:126-127` gives the reason
  recorded above (an RGB24 copy at depth 24, cairo's `CAIRO_EXTEND_NONE` and a zeroed image surface), so that the
  expectation is not reopened.

## Notes
- The GTK display classes are this fork's own. They came in with 26ce75d02f (PR #12, the JNI-to-FFM migration of
  the GTK glass), and their javadocs pin the behaviour of commit 033187ad90. There is nothing to upstream.
- None of the checks above needs WSLg's `:0`. US-008's first Linux gate ran all 32 GTK classes there before
  `DISPLAY` was unset, `GtkClipboardNativeTest` and `GtkScreenNativeTest` included. After that, US-008 ran at most
  one class per Maven run on `:0`. No robot test has run there: without `-DUSE_ROBOT` the robot classes skip.
- Related: US-016 runs these classes in CI and passes this story's flag.
