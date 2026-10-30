/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package test.com.sun.glass.ui.gtk;

import com.sun.glass.ui.gtk.GtkGlassShim;
import com.sun.glass.ui.gtk.GtkScriptedWindowManager;
import com.sun.glass.ui.gtk.GtkTraceWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.glass.ui.gtk.GtkBoundsSteps.Reading;
import test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.Effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.parseRect;
import static test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.rect;

/**
 * Where a glass window is, and where glass asks the window manager to put it: {@code set_bounds},
 * {@code update_frame_extents}, {@code process_configure} and {@code process_map} of {@code WindowContextTop} in
 * {@code glass_window.cpp}, in child JVMs on the display of {@code DISPLAY}, which must run no window manager.
 * <ul>
 * <li>A position glass sends the window manager is one the application asked for, never one glass read back from X:
 * a change of the frame extents asks only for the size, so the window stays where X has it, even where glass had
 * read another position before; and a {@code set_bounds} that sets one coordinate leaves the other where X has it,
 * even when glass has not yet heard where that is. Without a window manager the X server applies every request at
 * once, so the window's position in X is what glass asked for; the test moves the window itself in the same task as
 * the application's request, before glass can handle the move.</li>
 * <li>A reparenting window manager can make glass handle a configure while its frame is not placed yet, and send no
 * configure after it places it (openbox does both). Glass keeps the position it asked for until the window is
 * mapped, which the window manager does once it has placed the frame, and reads the position then: a {@code TITLED}
 * window and an undecorated one are reported at the position they asked for until then, and then where the window
 * manager put them, which can be elsewhere. A scripted window manager ({@link GtkScriptedWindowManager}) plays that
 * order, holding the frame at 0, 0 until glass has handled the configure, and placing it away from where the window
 * asked to be. The {@code TITLED} window measured is the second of its process: the first starts without extents,
 * so GTK sends the size for the window manager's extents after the map, and that resize's configure would give glass
 * the placed position whether or not it reads it at the map; the second starts from the cached extents and asks for
 * nothing after the map. The first is shown to fill the cache, and its readings are recorded, not asserted.</li>
 * <li>A window disabled when it is shown, as a stage is that an application-modal stage shown in the same task
 * disables, gets what the window manager does to it all the same; only GTK does not see its map and property
 * events. Glass takes its map, so a window manager's move or the user's drag is reported, and its frame extents, so
 * that a window shown centred is centred with its frame while it is disabled and stays where X has it when its
 * extents change after it was enabled and moved.</li>
 * <li>A window shown with gravity, as a stage {@code centerOnScreen} places, is moved by its share of the frame when
 * its first extents of a frame come, after the map included (Weston writes them only then), so that it is centred
 * with its frame; after them it stays where X has it when its extents change again (openbox's Un/Decorate, a theme
 * or border width change), also when those first extents were the cached ones and changed nothing. All-zero extents
 * before them describe no frame (Mutter answers {@code _NET_REQUEST_FRAME_EXTENTS} with them and writes its frame's
 * later): a window that starts without extents is centred with the ones that follow, and one that starts from the
 * cached extents keeps the place and the size it asked for.</li>
 * </ul>
 * Each scenario runs in a child of its own: glass caches a {@code TITLED} window's extents per process.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(300)
public class GtkWindowPlacementTest {

    /** Where the windows are shown, and their sizes: a {@code TITLED} window size, an undecorated client size. */
    static final int X = 200;
    static final int Y = 200;
    static final int WIDTH = 400;
    static final int HEIGHT = 300;
    static final int UNDECORATED_X = 100;
    static final int UNDECORATED_Y = 100;
    static final int UNDECORATED_WIDTH = 320;
    static final int UNDECORATED_HEIGHT = 240;

    /** Where the test moves the window behind glass's back, before and with the extents change. */
    static final int[] FIRST_MOVE = {50, 60};
    static final int[] SECOND_MOVE = {300, 250};

    /** The extents the extents change brings: openbox's for a {@code TITLED} window. */
    static final int[] CHANGED_EXTENTS = {1, 1, 20, 5};

    /** The coordinates one-axis {@code setBounds} calls set, and where the test moves the other one first. */
    static final int ONE_AXIS_X = 150;
    static final int ONE_AXIS_Y = 120;
    static final int MOVED_Y = 400;
    static final int MOVED_X = 300;

    /** The scripted window manager's name, its extents for a {@code TITLED} window, and its provisional frame. */
    static final String WINDOW_MANAGER = "glass test window manager";
    static final int[] SCRIPTED_EXTENTS = {1, 1, 20, 5};
    static final int PROVISIONAL_X = 0;
    static final int PROVISIONAL_Y = 0;

    /** Where the scripted window manager places the frames, away from where the windows asked to be. */
    static final int[] TITLED_PLACED = {260, 170};
    static final int[] UNDECORATED_PLACED = {140, 130};

    /** How long the scripted window manager waits for a window's {@code MapRequest}, and holds the FX thread. */
    static final long MAP_REQUEST_MILLIS = 10_000;

    /** The gravity {@code javafx.stage.Window.centerOnScreen} places a stage with. */
    static final float GRAVITY_X = 1.0f / 2;
    static final float GRAVITY_Y = 1.0f / 3;

    /** The content size of the windows shown centred. */
    static final int CENTRED_WIDTH = 360;
    static final int CENTRED_HEIGHT = 240;

    /** Where the test moves the second centred window behind glass's back. */
    static final int[] DRAGGED = {700, 500};

    @BeforeAll
    static void requireDisplay() {
        GtkGlassChildJvm.requireDisplay();
    }

    // ---------------------------------------------------------------------------------------------
    // No position read back
    // ---------------------------------------------------------------------------------------------

    /**
     * The window was moved behind glass's back, glass read where to, then the extents changed and the window was
     * moved again before glass could handle either: glass asks only for the size of the new extents, so the window
     * ends where it was moved last, not where glass had read it.
     */
    @Test
    public void anExtentsChangeKeepsTheWindowWhereXHasIt() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "extentsChangeScenario", false, false);
        Reading moved = GtkBoundsSteps.reading(run, "moved");
        Reading changed = GtkBoundsSteps.reading(run, "changed");
        int[] first = parseRect(moved.glass());
        assertEquals("Window at " + FIRST_MOVE[0] + "," + FIRST_MOVE[1], "Window at " + first[0] + "," + first[1],
                moved.describe("before the extents change glass has read the position the test moved the window to"
                        + " (" + FIRST_MOVE[0] + "," + FIRST_MOVE[1] + ")"));
        int[] e = CHANGED_EXTENTS;
        String client = rect(SECOND_MOVE[0], SECOND_MOVE[1], WIDTH - e[0] - e[1], HEIGHT - e[2] - e[3]);
        String window = rect(SECOND_MOVE[0], SECOND_MOVE[1], WIDTH, HEIGHT);
        assertEquals("client " + client + ", Window " + window,
                "client " + changed.client() + ", Window " + changed.glass(),
                changed.describe("the extents change asks for the size only: the window stays at "
                        + SECOND_MOVE[0] + "," + SECOND_MOVE[1] + ", where X has it, and not at " + FIRST_MOVE[0]
                        + "," + FIRST_MOVE[1] + ", where glass had read it, with a client of the " + WIDTH + "x"
                        + HEIGHT + " window less the new extents"));
    }

    /**
     * {@code setBounds} with x alone, right after the test moved the window to another y, which glass has not
     * handled yet: the window gets the x and keeps the y where X has it.
     */
    @Test
    public void settingXAloneKeepsTheYWhereXHasIt() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "oneAxisScenario", false, false);
        Reading reading = GtkBoundsSteps.reading(run, "xOnly");
        oneAxis(reading, ONE_AXIS_X, MOVED_Y, "x alone, " + ONE_AXIS_X + ", right after the test moved the window to"
                + " y " + MOVED_Y);
    }

    /**
     * {@code setBounds} with y alone, right after the test moved the window to another x, which glass has not
     * handled yet: the window gets the y and keeps the x where X has it.
     */
    @Test
    public void settingYAloneKeepsTheXWhereXHasIt() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "oneAxisScenario", false, false);
        Reading reading = GtkBoundsSteps.reading(run, "yOnly");
        oneAxis(reading, MOVED_X, ONE_AXIS_Y, "y alone, " + ONE_AXIS_Y + ", right after the test moved the window to"
                + " x " + MOVED_X);
    }

    private static void oneAxis(Reading reading, int x, int y, String what) {
        int[] client = parseRect(reading.client());
        int[] glass = parseRect(reading.glass());
        assertEquals("client at " + x + "," + y + ", Window at " + x + "," + y,
                "client at " + client[0] + "," + client[1] + ", Window at " + glass[0] + "," + glass[1],
                reading.describe("setBounds with " + what + ": one coordinate set leaves the other where X has the"
                        + " window, not where glass last read it"));
    }

    // ---------------------------------------------------------------------------------------------
    // The position once the window manager has placed the window
    // ---------------------------------------------------------------------------------------------

    /**
     * A {@code TITLED} window, which starts from the extents cached by the window before it and asks the window
     * manager for nothing after the map, whose configure glass handled while the window manager's frame was still at
     * its provisional 0, 0, and whose frame the window manager placed away from where it asked to be and mapped,
     * without a configure: reported where the window manager placed the frame, with its {@code View} at the client's
     * offset in it.
     */
    @Test
    public void aTitledWindowIsReportedWhereTheWindowManagerPlacedIt() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "scriptedWindowManagerScenario", false, true);
        Reading placed = GtkBoundsSteps.reading(run, "titled.placed");
        int[] e = SCRIPTED_EXTENTS;
        String frame = rect(TITLED_PLACED[0], TITLED_PLACED[1], WIDTH, HEIGHT);
        assertEquals("X frame " + frame + ", Window " + frame + ", View " + e[0] + "," + e[2],
                "X frame " + placed.frame() + ", Window " + placed.glass() + ", View " + placed.view(),
                placed.describe("the window asked to be at " + X + "," + Y + "; the window manager placed the frame"
                        + " at " + TITLED_PLACED[0] + "," + TITLED_PLACED[1] + " and mapped the window, with no"
                        + " configure after it, so glass learns the position only by reading it at the map;"
                        + " before the map glass reported " + GtkBoundsSteps.value(run, "titled.unplaced")
                        + "; MapRequest: " + GtkBoundsSteps.value(run, "titled.mapRequest") + "; requests: "
                        + GtkBoundsSteps.value(run, "titled.requests")));
    }

    /**
     * An undecorated window whose configure glass handled while the window manager's frame was still at its
     * provisional 0, 0, and which the window manager placed away from where it asked to be and mapped, without a
     * configure: reported where the window manager placed it.
     */
    @Test
    public void anUndecoratedWindowIsReportedWhereTheWindowManagerPlacedIt() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "scriptedWindowManagerScenario", false, true);
        Reading placed = GtkBoundsSteps.reading(run, "untitled.placed");
        String client = rect(UNDECORATED_PLACED[0], UNDECORATED_PLACED[1], UNDECORATED_WIDTH, UNDECORATED_HEIGHT);
        assertEquals("client " + client + ", Window " + client + ", View 0,0",
                "client " + placed.client() + ", Window " + placed.glass() + ", View " + placed.view(),
                placed.describe("the window asked to be at " + UNDECORATED_X + "," + UNDECORATED_Y + "; the window"
                        + " manager placed it at " + UNDECORATED_PLACED[0] + "," + UNDECORATED_PLACED[1] + " and"
                        + " mapped it, with no configure after it, so glass learns the position only by reading it"
                        + " at the map; before the map glass reported " + GtkBoundsSteps.value(run,
                        "untitled.unplaced") + "; MapRequest: " + GtkBoundsSteps.value(run, "untitled.mapRequest")
                        + "; requests: " + GtkBoundsSteps.value(run, "untitled.requests")));
    }

    /**
     * The {@code TITLED} window above, before the window manager placed its frame: glass handled a configure while
     * the frame was at its provisional 0, 0, and reports the position the window asked for, not that one.
     */
    @Test
    public void aTitledWindowKeepsThePositionAskedForUntilTheWindowManagerPlacesIt() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "scriptedWindowManagerScenario", false, true);
        int[] e = SCRIPTED_EXTENTS;
        unplaced(run, "titled", "Window " + rect(X, Y, WIDTH, HEIGHT) + ", View " + e[0] + "," + e[2]);
    }

    /**
     * The undecorated window above, before the window manager placed it: glass handled a configure while the frame
     * was at its provisional 0, 0, and reports the position the window asked for, not that one.
     */
    @Test
    public void anUndecoratedWindowKeepsThePositionAskedForUntilTheWindowManagerPlacesIt() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "scriptedWindowManagerScenario", false, true);
        unplaced(run, "untitled", "Window " + rect(UNDECORATED_X, UNDECORATED_Y, UNDECORATED_WIDTH,
                UNDECORATED_HEIGHT) + ", View 0,0");
    }

    private static void unplaced(GtkGlassChildJvm.Run run, String name, String expected) {
        assertEquals(expected, GtkBoundsSteps.value(run, name + ".unplaced"), "what glass reported before the window"
                + " manager placed the frame, which it held at " + PROVISIONAL_X + "," + PROVISIONAL_Y + " while glass"
                + " handled the configure of the window's request: the position asked for, until the map; held: "
                + GtkBoundsSteps.value(run, name + ".held") + "; MapRequest: "
                + GtkBoundsSteps.value(run, name + ".mapRequest") + "; requests: "
                + GtkBoundsSteps.value(run, name + ".requests") + "; after the map: Window "
                + GtkBoundsSteps.value(run, name + ".placed.glass"));
    }

    // ---------------------------------------------------------------------------------------------
    // A window disabled at its first map
    // ---------------------------------------------------------------------------------------------

    /**
     * A window disabled before it was shown, so that its map event does not reach GTK, then moved behind glass's
     * back: reported where X has it.
     */
    @Test
    public void aWindowDisabledAtItsFirstMapIsReportedWhereXMovesIt() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "disabledAtFirstMapScenario", false, false);
        Reading moved = GtkBoundsSteps.reading(run, "moved");
        int[] client = parseRect(moved.client());
        int[] glass = parseRect(moved.glass());
        assertEquals("disabled true, client at " + FIRST_MOVE[0] + "," + FIRST_MOVE[1] + ", Window at "
                        + FIRST_MOVE[0] + "," + FIRST_MOVE[1],
                "disabled " + GtkBoundsSteps.value(run, "disabled") + ", client at " + client[0] + "," + client[1]
                        + ", Window at " + glass[0] + "," + glass[1],
                moved.describe("the window, shown disabled at " + X + "," + Y + ", is reported where the test moved"
                        + " it; what glass called while it showed the window: "
                        + GtkBoundsSteps.value(run, "shown.trace")));
    }

    /**
     * The first {@code TITLED} window of its process, shown centred with gravity and disabled in the same task, given
     * its first extents of a frame while it is disabled, then enabled, moved behind glass's back and given other
     * extents: it stays where X has it.
     */
    @Test
    public void aCentredWindowDisabledAtItsShowStaysWhereXMovedItWhenItsExtentsChange() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "disabledCentredScenario", false, false);
        Reading framed = GtkBoundsSteps.reading(run, "framed");
        Reading moved = GtkBoundsSteps.reading(run, "moved");
        Reading changed = GtkBoundsSteps.reading(run, "changed");
        int[] first = parseRect(moved.glass());
        assertEquals("disabled when framed true, Window at " + DRAGGED[0] + "," + DRAGGED[1],
                "disabled when framed " + GtkBoundsSteps.value(run, "framed.disabled") + ", Window at " + first[0]
                        + "," + first[1],
                moved.describe("the window was disabled when its first extents came, and once enabled glass has read"
                        + " the position the test moved it to (" + DRAGGED[0] + "," + DRAGGED[1] + ")"));
        // Only the position: the first extents' set_bounds gave the window a window size, so the client takes the
        // change in size (as update_frame_extents does at 64d56e2b9c)
        int[] client = parseRect(changed.client());
        int[] glass = parseRect(changed.glass());
        assertEquals("client at " + DRAGGED[0] + "," + DRAGGED[1] + ", Window at " + DRAGGED[0] + "," + DRAGGED[1],
                "client at " + client[0] + "," + client[1] + ", Window at " + glass[0] + "," + glass[1],
                changed.describe("glass took the extents that came while the window was disabled ("
                        + framed.describe("-") + "), its first extents of a frame, which ended the gravity it was"
                        + " shown with; so the change asks for no move and the window stays where X has it"));
    }

    // ---------------------------------------------------------------------------------------------
    // Gravity
    // ---------------------------------------------------------------------------------------------

    /**
     * The first {@code TITLED} window of its process, shown centred with gravity, whose first extents come after
     * the map: moved by the gravity's share of them, so that it is centred with its frame.
     */
    @Test
    public void aCentredWindowIsCentredWithTheExtentsThatComeAfterItsMap() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "centredScenario", false, false);
        Reading framed = GtkBoundsSteps.reading(run, "first.framed");
        int[] asked = centred(parseRect(GtkBoundsSteps.value(run, "screen")));
        int[] e = GtkFrameExtentsStandIn.STAND_IN;
        int x = (int) (asked[0] - GRAVITY_X * (e[0] + e[1]));
        int y = (int) (asked[1] - GRAVITY_Y * (e[2] + e[3]));
        String client = rect(x, y, CENTRED_WIDTH, CENTRED_HEIGHT);
        String window = rect(x, y, CENTRED_WIDTH + e[0] + e[1], CENTRED_HEIGHT + e[2] + e[3]);
        assertEquals("client " + client + ", Window " + window,
                "client " + framed.client() + ", Window " + framed.glass(),
                framed.describe("shown at " + asked[0] + "," + asked[1] + " with gravity " + GRAVITY_X + ", "
                        + GRAVITY_Y + ", then mapped, then given the extents " + e[0] + "," + e[1] + "," + e[2] + ","
                        + e[3] + ": moved by the gravity's share of them, to " + x + "," + y + ", centred with its"
                        + " frame"));
    }

    /**
     * A second {@code TITLED} window, shown centred with gravity, whose first extents are the cached ones and change
     * nothing, then moved behind glass's back, then given other extents: it stays where X has it.
     */
    @Test
    public void aCentredWindowStaysWhereXMovedItWhenItsExtentsChange() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "centredScenario", false, false);
        Reading moved = GtkBoundsSteps.reading(run, "second.moved");
        Reading changed = GtkBoundsSteps.reading(run, "second.changed");
        int[] first = parseRect(moved.glass());
        assertEquals("Window at " + DRAGGED[0] + "," + DRAGGED[1], "Window at " + first[0] + "," + first[1],
                moved.describe("before the extents change glass has read the position the test moved the window to"
                        + " (" + DRAGGED[0] + "," + DRAGGED[1] + ")"));
        int[] e = CHANGED_EXTENTS;
        String client = rect(DRAGGED[0], DRAGGED[1], CENTRED_WIDTH, CENTRED_HEIGHT);
        String window = rect(DRAGGED[0], DRAGGED[1], CENTRED_WIDTH + e[0] + e[1], CENTRED_HEIGHT + e[2] + e[3]);
        assertEquals("client " + client + ", Window " + window,
                "client " + changed.client() + ", Window " + changed.glass(),
                changed.describe("the gravity the window was shown with counted only up to its first extents of a"
                        + " frame, the cached ones (" + GtkBoundsSteps.reading(run, "second.framed").describe("-")
                        + "), so the change asks for the size only and the window stays where X has it"));
    }

    /**
     * The first {@code TITLED} window of its process, shown centred with gravity, whose first report is all zero, as
     * Mutter's answer to {@code _NET_REQUEST_FRAME_EXTENTS} is, and whose extents of a frame come after it: moved by
     * the gravity's share of them, so that it is centred with its frame.
     */
    @Test
    public void aCentredWindowWhoseFirstReportIsAllZeroIsCentredWithTheExtentsThatFollow() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "zeroFirstScenario", false, false);
        Reading framed = framedAfterZero(run, "first");
        int[] asked = centred(parseRect(GtkBoundsSteps.value(run, "screen")));
        int[] e = GtkFrameExtentsStandIn.STAND_IN;
        int x = (int) (asked[0] - GRAVITY_X * (e[0] + e[1]));
        int y = (int) (asked[1] - GRAVITY_Y * (e[2] + e[3]));
        String client = rect(x, y, CENTRED_WIDTH, CENTRED_HEIGHT);
        String window = rect(x, y, CENTRED_WIDTH + e[0] + e[1], CENTRED_HEIGHT + e[2] + e[3]);
        assertEquals("client " + client + ", Window " + window,
                "client " + framed.client() + ", Window " + framed.glass(),
                framed.describe("shown at " + asked[0] + "," + asked[1] + " with gravity " + GRAVITY_X + ", "
                        + GRAVITY_Y + " and no cached extents; the all-zero first report described no frame and kept"
                        + " the gravity; then given the extents " + e[0] + "," + e[1] + "," + e[2] + "," + e[3]
                        + ": moved by the gravity's share of them, to " + x + "," + y + ", centred with its frame"));
    }

    /**
     * A second {@code TITLED} window, which starts from the extents cached by the first, shown centred with gravity,
     * whose first report is all zero and whose extents of a frame, the cached ones, come after it: it keeps the place
     * and the content size it asked for.
     */
    @Test
    public void aCentredWindowFromTheCachedExtentsWhoseFirstReportIsAllZeroKeepsThePlaceAndSizeAskedFor() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "zeroFirstScenario", false, false);
        Reading framed = framedAfterZero(run, "second");
        int[] asked = centred(parseRect(GtkBoundsSteps.value(run, "screen")));
        int[] e = GtkFrameExtentsStandIn.STAND_IN;
        String client = rect(asked[0], asked[1], CENTRED_WIDTH, CENTRED_HEIGHT);
        String window = rect(asked[0], asked[1], CENTRED_WIDTH + e[0] + e[1], CENTRED_HEIGHT + e[2] + e[3]);
        assertEquals("client " + client + ", Window " + window,
                "client " + framed.client() + ", Window " + framed.glass(),
                framed.describe("shown at " + asked[0] + "," + asked[1] + " with gravity " + GRAVITY_X + ", "
                        + GRAVITY_Y + " and a content of " + CENTRED_WIDTH + "x" + CENTRED_HEIGHT + ", starting from"
                        + " the cached extents " + e[0] + "," + e[1] + "," + e[2] + "," + e[3] + "; the all-zero first"
                        + " report described no frame and asked for nothing, and the extents that followed were the"
                        + " cached ones: the window keeps the place and the content size it asked for"));
    }

    /**
     * The reading of the window {@code name} of the zero-first scenario once its extents of a frame have come; fails
     * unless its first report, as glass handled it, was all zero.
     */
    private static Reading framedAfterZero(GtkGlassChildJvm.Run run, String name) {
        Reading zero = GtkBoundsSteps.reading(run, name + ".zero");
        assertEquals("_NET_FRAME_EXTENTS 0,0,0,0", "_NET_FRAME_EXTENTS " + zero.values().get("extents"),
                zero.describe("the window's first report all zero, handled by glass before its extents of a frame"));
        return GtkBoundsSteps.reading(run, name + ".framed");
    }

    /**
     * Where {@code javafx.stage.Window.centerOnScreen} puts a stage whose window is the content size of the centred
     * windows, as {@code Window.setBounds} rounds it, on the screen {@code x, y, width, height}.
     */
    static int[] centred(int[] screen) {
        return new int[] {screen[0] + Math.round((screen[2] - CENTRED_WIDTH) * GRAVITY_X),
            screen[1] + Math.round((screen[3] - CENTRED_HEIGHT) * GRAVITY_Y)};
    }

    // ---------------------------------------------------------------------------------------------
    // The scenarios (child JVMs)
    // ---------------------------------------------------------------------------------------------

    /**
     * A {@code TITLED} window under the stand-in extents, moved by the test to {@link #FIRST_MOVE}; then, in one FX
     * task, new extents and a move to {@link #SECOND_MOVE}.
     */
    static void extentsChangeScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        GtkEventTrace t = new GtkEventTrace();
        GtkTraceWindow window = GtkBoundsSteps.show(t, "w1", "v1", GtkBoundsSteps.TITLED, X, Y, WIDTH, HEIGHT, -1, -1);
        try {
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            t.settle();
            GtkFrameExtentsStandIn.provide(out, "w1", xid, false);
            GtkBoundsSteps.step(t, out, "normal", window, Effect.NONE, () -> {
            });
            GtkBoundsSteps.step(t, out, "moved", window, Effect.CLIENT, () ->
                    GtkGlassShim.moveWindow(xid, FIRST_MOVE[0], FIRST_MOVE[1]));
            GtkBoundsSteps.step(t, out, "changed", window, Effect.CLIENT, () -> {
                int[] e = CHANGED_EXTENTS;
                GtkGlassShim.setNetFrameExtents(xid, e[0], e[1], e[2], e[3]);
                GtkGlassShim.moveWindow(xid, SECOND_MOVE[0], SECOND_MOVE[1]);
            });
        } finally {
            GtkBoundsSteps.close(List.of(window));
            t.settle();
        }
    }

    /**
     * A {@code TITLED} window; in one FX task the test moves it to another y and the application sets x alone, then
     * in another the test moves it to another x and the application sets y alone.
     */
    static void oneAxisScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        GtkEventTrace t = new GtkEventTrace();
        GtkTraceWindow window = GtkBoundsSteps.show(t, "w1", "v1", GtkBoundsSteps.TITLED, X, Y, WIDTH, HEIGHT, -1, -1);
        try {
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            t.settle();
            GtkFrameExtentsStandIn.provide(out, "w1", xid, false);
            GtkBoundsSteps.step(t, out, "normal", window, Effect.NONE, () -> {
            });
            int[] normal = parseRect(out.get("normal.client"));
            // Window.PeerBoundsConfigurator passes the coordinate it does not set as 0
            GtkBoundsSteps.step(t, out, "xOnly", window, Effect.CLIENT, () -> {
                GtkGlassShim.moveWindow(xid, normal[0], MOVED_Y);
                window.setBounds(ONE_AXIS_X, 0, true, false, -1, -1, -1, -1, 0, 0);
            });
            GtkBoundsSteps.step(t, out, "yOnly", window, Effect.CLIENT, () -> {
                GtkGlassShim.moveWindow(xid, MOVED_X, MOVED_Y);
                window.setBounds(0, ONE_AXIS_Y, false, true, -1, -1, -1, -1, 0, 0);
            });
        } finally {
            GtkBoundsSteps.close(List.of(window));
            t.settle();
        }
    }

    /**
     * A {@code TITLED} window disabled and shown in one FX task, so that it is disabled at its first map, then moved
     * by the test.
     */
    static void disabledAtFirstMapScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        GtkEventTrace t = new GtkEventTrace();
        GtkTraceWindow window = GtkBoundsSteps.show(t, "w1", "v1", GtkBoundsSteps.TITLED, w -> {
            w.setBounds(X, Y, true, true, WIDTH, HEIGHT, -1, -1, 0, 0);
            w.setEnabled(false);
        });
        try {
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            t.settle();
            out.put("shown.trace", String.join(" | ", t.lines()));
            out.put("disabled", Boolean.toString(!GtkGlassChild.onFx(window::isEnabled)));
            GtkBoundsSteps.step(t, out, "normal", window, Effect.NONE, () -> {
            });
            GtkBoundsSteps.step(t, out, "moved", window, Effect.CLIENT, () ->
                    GtkGlassShim.moveWindow(xid, FIRST_MOVE[0], FIRST_MOVE[1]));
        } finally {
            GtkBoundsSteps.close(List.of(window));
            t.settle();
        }
    }

    /**
     * The first {@code TITLED} window of the process, shown centred with gravity and disabled in one FX task, given
     * the stand-in extents while it is disabled, then enabled, moved by the test to {@link #DRAGGED} and given other
     * extents.
     */
    static void disabledCentredScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        int[] asked = centred(parseRect(out.get("screen")));
        int[] e = GtkFrameExtentsStandIn.STAND_IN;
        GtkEventTrace t = new GtkEventTrace();
        GtkTraceWindow window = GtkBoundsSteps.show(t, "w1", "v1", GtkBoundsSteps.TITLED, w -> {
            centredAt(asked).run(w);
            w.setEnabled(false);
        });
        try {
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            t.settle();
            GtkBoundsSteps.step(t, out, "framed", window, Effect.EXTENTS, () ->
                    GtkGlassShim.setNetFrameExtents(xid, e[0], e[1], e[2], e[3]));
            out.put("framed.disabled", Boolean.toString(!GtkGlassChild.onFx(window::isEnabled)));
            GtkBoundsSteps.step(t, out, "enabled", window, Effect.NONE, () -> window.setEnabled(true));
            GtkBoundsSteps.step(t, out, "moved", window, Effect.CLIENT, () ->
                    GtkGlassShim.moveWindow(xid, DRAGGED[0], DRAGGED[1]));
            GtkBoundsSteps.step(t, out, "changed", window, Effect.EXTENTS, () -> {
                int[] c = CHANGED_EXTENTS;
                GtkGlassShim.setNetFrameExtents(xid, c[0], c[1], c[2], c[3]);
            });
        } finally {
            GtkBoundsSteps.close(List.of(window));
            t.settle();
        }
    }

    /**
     * Two {@code TITLED} windows shown centred with gravity, as {@code centerOnScreen} shows a stage, each given its
     * extents by the test after its map, as Weston does: the first of the process ({@code first}), which starts
     * without extents, and a second one, which starts from those cached since ({@code second}) and is then moved by
     * the test and given other extents.
     */
    static void centredScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        int[] asked = centred(parseRect(out.get("screen")));
        int[] e = GtkFrameExtentsStandIn.STAND_IN;
        GtkEventTrace t = new GtkEventTrace();
        List<GtkTraceWindow> windows = new ArrayList<>();
        try {
            GtkTraceWindow first = showCentred(t, "w1", "v1", asked);
            windows.add(first);
            long firstXid = GtkGlassChild.onFx(first::getNativeWindow);
            t.settle();
            GtkBoundsSteps.step(t, out, "first.framed", first, Effect.EXTENTS, () ->
                    GtkGlassShim.setNetFrameExtents(firstXid, e[0], e[1], e[2], e[3]));
            GtkTraceWindow second = showCentred(t, "w2", "v2", asked);
            windows.add(second);
            long secondXid = GtkGlassChild.onFx(second::getNativeWindow);
            t.settle();
            GtkBoundsSteps.step(t, out, "second.framed", second, Effect.EXTENTS, () ->
                    GtkGlassShim.setNetFrameExtents(secondXid, e[0], e[1], e[2], e[3]));
            GtkBoundsSteps.step(t, out, "second.moved", second, Effect.CLIENT, () ->
                    GtkGlassShim.moveWindow(secondXid, DRAGGED[0], DRAGGED[1]));
            GtkBoundsSteps.step(t, out, "second.changed", second, Effect.EXTENTS, () -> {
                int[] c = CHANGED_EXTENTS;
                GtkGlassShim.setNetFrameExtents(secondXid, c[0], c[1], c[2], c[3]);
            });
        } finally {
            GtkBoundsSteps.close(windows);
            t.settle();
        }
    }

    /** A {@code TITLED} window of the centred windows' content size at {@code at}, with the centring gravity. */
    private static GtkTraceWindow showCentred(GtkEventTrace t, String name, String viewName, int[] at)
            throws Exception {
        return GtkBoundsSteps.show(t, name, viewName, GtkBoundsSteps.TITLED, centredAt(at));
    }

    /** The bounds of a window of the centred windows' content size at {@code at}, with the centring gravity. */
    private static GtkBoundsSteps.SetUp centredAt(int[] at) {
        return window -> window.setBounds(at[0], at[1], true, true, -1, -1, CENTRED_WIDTH, CENTRED_HEIGHT, GRAVITY_X,
                GRAVITY_Y);
    }

    /**
     * Two {@code TITLED} windows shown centred with gravity in Mutter's order: each is given 0, 0, 0, 0 as its
     * extents in the FX task that shows it, as Mutter answers the {@code _NET_REQUEST_FRAME_EXTENTS} glass sends when
     * the window is realized, and the stand-in extents once glass has handled those. The first of the process
     * ({@code first}) starts without extents, the second ({@code second}) from those cached since.
     */
    static void zeroFirstScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        int[] asked = centred(parseRect(out.get("screen")));
        GtkEventTrace t = new GtkEventTrace();
        List<GtkTraceWindow> windows = new ArrayList<>();
        try {
            showZeroFirst(t, out, windows, "first", asked);
            showZeroFirst(t, out, windows, "second", asked);
        } finally {
            GtkBoundsSteps.close(windows);
            t.settle();
        }
    }

    /**
     * Shows a centred window named {@code name} at {@code at}, added to {@code windows}, with all-zero extents
     * written in the FX task that shows it; records the settled reading once glass has handled them under
     * {@code <name>.zero}, then writes the stand-in extents and records under {@code <name>.framed}.
     */
    private static void showZeroFirst(GtkEventTrace t, Map<String, String> out, List<GtkTraceWindow> windows,
                                      String name, int[] at) throws Exception {
        GtkTraceWindow window = GtkBoundsSteps.show(t, name, name + ".view", GtkBoundsSteps.TITLED, centredAt(at),
                shown -> GtkGlassShim.setNetFrameExtents(shown.getNativeWindow(), 0, 0, 0, 0));
        windows.add(window);
        long xid = GtkGlassChild.onFx(window::getNativeWindow);
        t.settle();
        GtkBoundsSteps.step(t, out, name + ".zero", window, Effect.NONE, () -> {
        });
        int[] e = GtkFrameExtentsStandIn.STAND_IN;
        GtkBoundsSteps.step(t, out, name + ".framed", window, Effect.EXTENTS, () ->
                GtkGlassShim.setNetFrameExtents(xid, e[0], e[1], e[2], e[3]));
    }

    /**
     * The scripted window manager, started before the toolkit so that GDK knows it from the start, frames two
     * {@code TITLED} windows, the first of the process ({@code first}) and one that starts from the extents cached
     * since ({@code titled}), then an undecorated one; it places the first where it asked to be and the other two
     * elsewhere. Starts its own toolkit; records only the display's own window manager under
     * {@code env.windowManager}, and skips where there is one.
     */
    static void scriptedWindowManagerScenario(Map<String, String> out) throws Exception {
        String running = GtkScriptedWindowManager.runningWindowManager();
        out.put("env.windowManager", running == null ? GtkBoundsSteps.NO_WINDOW_MANAGER
                : "the window manager of _NET_SUPPORTING_WM_CHECK " + running);
        if (running != null) {
            out.put(GtkBoundsSteps.SKIPPED, "this display runs a window manager");
            return;
        }
        try (GtkScriptedWindowManager wm = GtkScriptedWindowManager.start(WINDOW_MANAGER)) {
            GtkGlassChild.startToolkit();
            out.put("scripted.windowManager", GtkGlassChild.onFx(GtkGlassShim::windowManagerName));
            out.put("gdk.frameFromExtents", Boolean.toString(GtkGlassChild.onFx(() ->
                    GtkGlassShim.supportsNetWmHint("_NET_FRAME_EXTENTS"))));
            GtkEventTrace t = new GtkEventTrace();
            int[] e = SCRIPTED_EXTENTS;
            placeByScript(t, out, wm, "first", GtkBoundsSteps.TITLED, e, new int[] {X, Y, WIDTH, HEIGHT, -1, -1},
                    null);
            placeByScript(t, out, wm, "titled", GtkBoundsSteps.TITLED, e, new int[] {X, Y, WIDTH, HEIGHT, -1, -1},
                    TITLED_PLACED);
            placeByScript(t, out, wm, "untitled", GtkBoundsSteps.UNTITLED, new int[4], new int[] {UNDECORATED_X,
                UNDECORATED_Y, -1, -1, UNDECORATED_WIDTH, UNDECORATED_HEIGHT}, UNDECORATED_PLACED);
        }
    }

    /**
     * Shows a window of {@code style} with {@code bounds} ({@code x, y, width, height, contentWidth,
     * contentHeight}) under the scripted window manager. The FX task that shows it holds the FX thread, as a busy
     * application does, until the window manager has framed the window at its provisional position; then glass
     * handles what is queued - the configure the window manager made when it applied glass's request - while the
     * frame is still there, and the window manager handles what that makes glass ask for. Only then does it place the
     * frame, at {@code placeAt} or, where that is {@code null}, where the window asked to be, and map the window.
     * Records under {@code <name>.unplaced} what glass reported before the frame was placed, the settled reading after
     * under {@code <name>.placed}, and the {@code ConfigureRequest}s under {@code <name>.requests}.
     */
    private static void placeByScript(GtkEventTrace t, Map<String, String> out, GtkScriptedWindowManager wm,
                                      String name, int style, int[] extents, int[] bounds, int[] placeAt)
            throws Exception {
        wm.frameNext(extents[0], extents[1], extents[2], extents[3], PROVISIONAL_X, PROVISIONAL_Y);
        int requestsBefore = wm.requests().size();
        CountDownLatch framed = new CountDownLatch(1);
        CompletableFuture<GtkTraceWindow> shown = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                GtkTraceWindow window = t.window(name, null, style, name + ".view", new GtkEventTrace.ViewHooks());
                window.setResizable(true);
                window.setBounds(bounds[0], bounds[1], true, true, bounds[2], bounds[3], bounds[4], bounds[5], 0, 0);
                window.setVisible(true);
                GtkGlassShim.syncGdkDisplay();
                out.put(name + ".held", Boolean.toString(framed.await(MAP_REQUEST_MILLIS, TimeUnit.MILLISECONDS)));
                shown.complete(window);
            } catch (Throwable failure) {
                shown.completeExceptionally(failure);
            }
        });
        long client = wm.awaitMapRequest(MAP_REQUEST_MILLIS);
        framed.countDown();
        GtkTraceWindow window = shown.get(60, TimeUnit.SECONDS);
        try {
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            out.put(name + ".mapRequest", client == xid ? "the window's" : "0x" + Long.toHexString(client)
                    + " for the window 0x" + Long.toHexString(xid));
            Runnable roundTrip = () -> {
                try {
                    GtkGlassChild.onFx(() -> {
                        GtkGlassShim.syncGdkDisplay();
                        return null;
                    });
                    GtkGlassChild.onFx(() -> null);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            };
            wm.serviceUntilQuiet(roundTrip);
            out.put(name + ".unplaced", GtkGlassChild.onFx(() -> "Window " + rect(window.getX(), window.getY(),
                    window.getWidth(), window.getHeight()) + ", View " + window.getView().getX() + ","
                    + window.getView().getY()));
            if (client == xid && placeAt == null) {
                wm.place(client);
            } else if (client == xid) {
                wm.place(client, placeAt[0], placeAt[1]);
            }
            wm.serviceUntilQuiet(roundTrip);
            GtkBoundsSteps.step(t, out, name + ".placed", window, Effect.NONE, () -> {
            });
            List<String> requests = new ArrayList<>();
            for (GtkScriptedWindowManager.Request request : wm.requests().subList(requestsBefore,
                    wm.requests().size())) {
                requests.add(request.toString());
            }
            out.put(name + ".requests", String.join("; ", requests));
        } finally {
            GtkBoundsSteps.close(List.of(window));
            wm.serviceUntilQuiet(() -> {
                try {
                    t.settle();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
    }
}
