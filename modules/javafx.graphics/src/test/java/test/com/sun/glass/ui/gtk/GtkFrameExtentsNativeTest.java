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

import com.sun.glass.ui.Application;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;
import com.sun.glass.ui.gtk.GtkGlassShim;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.Action;
import test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.Effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.parseRect;
import static test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.rect;

/**
 * Which rectangle a glass window reports, and which size limits it hands the window manager, while a window manager
 * publishes {@code _NET_FRAME_EXTENTS} for it: {@code WindowContextTop::update_frame_extents} and
 * {@code process_configure} of {@code glass_window.cpp}, in a child JVM on the display of {@code DISPLAY}.
 * <ul>
 * <li>An undecorated window - {@code UNTITLED}, {@code TRANSPARENT}, {@code EXTENDED} and a {@code POPUP} - has no
 * frame, whatever extents a window manager publishes for it (Weston publishes a decorated frame's): its
 * {@code Window} x, y, width and height are its client's root position and size, its {@code View} sits at 0, 0, an
 * explicit window size is the client size, and the minimum and maximum sizes reach {@code WM_NORMAL_HINTS} as
 * asked.</li>
 * <li>A {@code TITLED} window, the control, is the frame the extents describe: its {@code Window} size is the client
 * size plus the extents, an explicit window size is that frame size, and the hints are the sizes asked less the
 * extents, as {@link GtkWindowNativeTest} pins. Its position is GDK's frame origin. Where GDK takes the frame from the
 * extents (below), the {@code Window} is at the client's position less (left, top) and the {@code View} at (left,
 * top). Elsewhere GDK takes the client's top-level ancestor as the frame - the window manager's frame under a
 * reparenting window manager, the client itself without one ({@link GtkGlassShim#topLevelGeometry}) - and the
 * {@code Window} is at that ancestor's position, with the {@code View} at the client's offset from it.</li>
 * </ul>
 * The extents are the window manager's where it publishes non-zero ones (Weston, and openbox for {@code TITLED}),
 * otherwise Weston's 38, 38, 59, 38 written on the window by the test ({@link GtkFrameExtentsStandIn}). The
 * {@code TITLED} control proves that those reached glass: it applies them in every build.
 * <p>
 * What the stand-in covers and what not: glass's own use of the extents - the size it reports, the size it asks
 * GTK for, the hints - whatever runs on the display. The position is GDK's: {@code gdk_window_get_root_origin}, the
 * frame origin that {@code process_configure} reads for a {@code TITLED} window, comes from
 * {@code _NET_FRAME_EXTENTS} only when a window manager lists that atom in {@code _NET_SUPPORTED}
 * ({@link GtkGlassShim#supportsNetWmHint}). Elsewhere GDK ignores the property and takes the frame from the window
 * tree, which without a window manager is the client itself, so the position half - the {@code Window}'s x and y and
 * the {@code View}'s offset - is covered only under a window manager that supports the atom, such as openbox, and on
 * WSLg's Weston.
 * <p>
 * The child also records each window as shown, but every assertion is on readings taken after an explicit move
 * ({@code Window.setPosition}) of the window the window manager has placed, once that move has shown in X
 * ({@link GtkFrameExtentsStandIn#settled}): a reparenting window manager (openbox) can leave the position glass reads
 * at map time behind the client's, a race of its own that this class does not measure.
 * <p>
 * The class is meant for a display without a window manager, with openbox, or WSLg's Weston: window managers that let
 * a client move and size its window. A tiling window manager is not one. i3 keeps every window it manages in its tile
 * and marks it maximized, so glass writes no size limits for it, and the move, the size and the limits of every window
 * but the override-redirect {@code POPUP} never take effect there; those tests fail on that.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(300)
public class GtkFrameExtentsNativeTest {

    /** The windows, in the order the child shows them: {@code TITLED} first, before another could write the cache. */
    enum Kind {
        TITLED(Window.TITLED | Window.CLOSABLE | Window.MINIMIZABLE | Window.MAXIMIZABLE, 200, 200, 400, 300, -1, -1),
        UNTITLED(Window.UNTITLED | Window.CLOSABLE, 100, 100, -1, -1, 320, 240),
        TRANSPARENT(Window.TRANSPARENT | Window.CLOSABLE, 100, 100, -1, -1, 320, 240),
        EXTENDED(Window.EXTENDED | Window.CLOSABLE | Window.MINIMIZABLE | Window.MAXIMIZABLE, 100, 100, -1, -1,
                320, 240),
        POPUP(Window.POPUP, 300, 300, -1, -1, 200, 150);

        /** The style masks {@code WindowStage} builds for each {@code StageStyle}, and a bare popup. */
        final int style;
        /** The first {@code setBounds}: a window size for {@code TITLED}, a client size for the others. */
        final int x;
        final int y;
        final int width;
        final int height;
        final int contentWidth;
        final int contentHeight;

        Kind(int style, int x, int y, int width, int height, int contentWidth, int contentHeight) {
            this.style = style;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
        }
    }

    /** Where every window is moved before it is measured. */
    static final int MOVE_X = 150;
    static final int MOVE_Y = 120;

    /** The explicit window size every window is given. */
    static final int SIZE_WIDTH = 500;
    static final int SIZE_HEIGHT = 400;

    /** The minimum and maximum sizes asked for, as in {@link GtkWindowNativeTest}. */
    static final int MIN_WIDTH = 123;
    static final int MIN_HEIGHT = 45;
    static final int MAX_WIDTH = 800;
    static final int MAX_HEIGHT = 600;

    private static GtkGlassChildJvm.Run run;

    @BeforeAll
    @Timeout(GtkGlassChildJvm.BEFORE_ALL_SECONDS)
    static void runScenario() {
        GtkGlassChildJvm.requireDisplay();
        run = GtkGlassChildJvm.run(GtkFrameExtentsNativeTest.class, "framesScenario", List.of());
    }

    // ---------------------------------------------------------------------------------------------
    // Undecorated windows
    // ---------------------------------------------------------------------------------------------

    /** An undecorated window is its client, wherever the extents say the frame is, and its scene is at 0, 0. */
    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UNTITLED", "TRANSPARENT", "EXTENDED", "POPUP"})
    public void anUndecoratedWindowIsItsClient(Kind kind) {
        Reading moved = reading(kind, "moved");
        assertEquals(moved.client() + " View 0,0", moved.glass() + " View " + moved.view(),
                moved.describe("the Window is the client and the View is at 0,0 (an undecorated window has no"
                        + " frame)"));
    }

    /** An explicit window size of an undecorated window is its client size. */
    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UNTITLED", "TRANSPARENT", "EXTENDED", "POPUP"})
    public void anUndecoratedWindowSizeIsItsClientSize(Kind kind) {
        Reading sized = reading(kind, "sized");
        int[] client = parseRect(sized.client());
        String asked = rect(client[0], client[1], SIZE_WIDTH, SIZE_HEIGHT);
        assertEquals("client " + asked + ", Window " + asked, "client " + sized.client() + ", Window "
                + sized.glass(), sized.describe("Window.setSize(" + SIZE_WIDTH + ", " + SIZE_HEIGHT + ") makes"
                + " a client of that size, which the Window reports"));
    }

    /** The minimum and maximum sizes of an undecorated window reach the window manager as asked. */
    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UNTITLED", "TRANSPARENT", "EXTENDED"})
    public void undecoratedSizeLimitsReachTheWindowManagerAsAsked(Kind kind) {
        Reading limits = reading(kind, "limits");
        assertEquals("min=" + MIN_WIDTH + "," + MIN_HEIGHT + " max=" + MAX_WIDTH + "," + MAX_HEIGHT, limits.hints(),
                limits.describe("WM_NORMAL_HINTS carry the minimum " + MIN_WIDTH + "x" + MIN_HEIGHT + " and the"
                        + " maximum " + MAX_WIDTH + "x" + MAX_HEIGHT + " unchanged"));
    }

    // ---------------------------------------------------------------------------------------------
    // The TITLED control
    // ---------------------------------------------------------------------------------------------

    /**
     * A {@code TITLED} window is the frame the extents describe around its client: the client size plus the
     * extents, at GDK's frame origin - where GDK takes the frame from the extents the client's position less (left,
     * top), elsewhere the position of the client's top-level ancestor - with the {@code View} at the client's offset
     * from it.
     */
    @Test
    public void theTitledControlIsTheFrameTheExtentsDescribe() {
        Reading moved = reading(Kind.TITLED, "moved");
        int[] e = moved.extents();
        int[] client = parseRect(moved.client());
        int[] topLevel = parseRect(moved.frame());
        boolean gdkFrame = run.values().get("gdk.frameFromExtents").equals("true");
        int x = gdkFrame ? client[0] - e[0] : topLevel[0];
        int y = gdkFrame ? client[1] - e[2] : topLevel[1];
        String frame = rect(x, y, client[2] + e[0] + e[1], client[3] + e[2] + e[3]);
        assertEquals(frame + " View " + (client[0] - x) + "," + (client[1] - y),
                moved.glass() + " View " + moved.view(),
                moved.describe("the Window is the client plus the extents, " + (gdkFrame
                        ? "at the client's position less (left, top), and the View is at (left, top)"
                        : "at the position of the client's top-level ancestor (X frame), and the View at the client's"
                                + " offset from it: without a window manager that lists _NET_FRAME_EXTENTS in"
                                + " _NET_SUPPORTED, GDK takes that ancestor as the frame")));
    }

    /** An explicit window size of a {@code TITLED} window is its frame size: the client is that less the extents. */
    @Test
    public void anExplicitTitledSizeIsTheFrameSize() {
        Reading sized = reading(Kind.TITLED, "sized");
        int[] e = sized.extents();
        int[] client = parseRect(sized.client());
        int[] glass = parseRect(sized.glass());
        assertEquals("client " + (SIZE_WIDTH - e[0] - e[1]) + "x" + (SIZE_HEIGHT - e[2] - e[3]) + ", Window "
                        + SIZE_WIDTH + "x" + SIZE_HEIGHT,
                "client " + client[2] + "x" + client[3] + ", Window " + glass[2] + "x" + glass[3],
                sized.describe("Window.setSize(" + SIZE_WIDTH + ", " + SIZE_HEIGHT + ") sizes the frame: the"
                        + " client is that less the extents"));
    }

    /** The size limits of a {@code TITLED} window reach the window manager less the extents, a minimum at least 1. */
    @Test
    public void titledSizeLimitsAreLessTheExtents() {
        Reading limits = reading(Kind.TITLED, "limits");
        int[] e = limits.extents();
        String expected = "min=" + Math.max(1, MIN_WIDTH - e[0] - e[1]) + "," + Math.max(1, MIN_HEIGHT - e[2] - e[3])
                + " max=" + (MAX_WIDTH - e[0] - e[1]) + "," + (MAX_HEIGHT - e[2] - e[3]);
        assertEquals(expected, limits.hints(), limits.describe("WM_NORMAL_HINTS carry the minimum " + MIN_WIDTH + "x"
                + MIN_HEIGHT + " and the maximum " + MAX_WIDTH + "x" + MAX_HEIGHT + " less the extents"));
    }

    // ---------------------------------------------------------------------------------------------
    // Readings
    // ---------------------------------------------------------------------------------------------

    /** One settled reading of the child ({@link GtkFrameExtentsStandIn#settled}). */
    record Reading(Kind kind, String step, Map<String, String> values) {

        String glass() {
            return values.get("glass");
        }

        String view() {
            return values.get("view");
        }

        String client() {
            return values.get("client");
        }

        /** The client's top-level ancestor ({@link GtkGlassShim#topLevelGeometry}). */
        String frame() {
            return values.get("frame");
        }

        String hints() {
            return values.get("hints");
        }

        int[] extents() {
            return GtkFrameExtentsStandIn.extents(values.get("extents"));
        }

        /**
         * The kind, the extents in force and where they come from, both rectangles, the hints, how the reading
         * settled and what is expected.
         */
        String describe(String expectation) {
            return kind + " window, step '" + step + "', _NET_FRAME_EXTENTS " + values.get("extents") + " from "
                    + values.get("extentsSource") + ": glass Window " + glass() + ", View " + view() + "; client "
                    + client() + ", X frame " + frame() + "; WM_NORMAL_HINTS " + hints() + "; the step's action: "
                    + values.get("effect") + "; settled: " + values.get("settled") + ", stand-in rewrites: "
                    + values.get("rewrites") + ". Expected: " + expectation;
        }
    }

    /**
     * The settled reading of {@code step} of the {@code kind} window; fails unless the step's action showed in X, the
     * readings settled and non-zero frame extents were in force, without which it would prove nothing.
     */
    private static Reading reading(Kind kind, String step) {
        String prefix = kind + "." + step + ".";
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("glass", "view", "client", "frame", "extents", "hints", "settled", "effect",
                "rewrites")) {
            String value = run.values().get(prefix + key);
            if (value == null) {
                throw new AssertionError("the child recorded no " + prefix + key + ": " + run.describe());
            }
            values.put(key, value);
        }
        values.put("extentsSource", run.values().get(kind + ".extentsSource"));
        Reading reading = new Reading(kind, step, values);
        assertNotEquals(GtkFrameExtentsStandIn.NEVER_TOOK_EFFECT, values.get("settled"), reading.describe("the step's"
                + " action to show in X within " + GtkFrameExtentsStandIn.EFFECT_MILLIS + " ms; it never took"
                + " effect"));
        assertNotEquals(GtkFrameExtentsStandIn.NOT_SETTLED, values.get("settled"), reading.describe("a reading that"
                + " settled within " + GtkFrameExtentsStandIn.SETTLE_ROUNDS + " rounds after the step's action"
                + " showed in X"));
        assertTrue(GtkFrameExtentsStandIn.nonZero(values.get("extents")), reading.describe("non-zero frame"
                + " extents in force, from the window manager or the stand-in"));
        return reading;
    }

    // ---------------------------------------------------------------------------------------------
    // The scenario (child JVM)
    // ---------------------------------------------------------------------------------------------

    /** Runs in {@link GtkGlassChild}. */
    static void framesScenario(Map<String, String> out) throws Exception {
        GtkGlassChild.recordEnvironment(out);
        out.put("gdk.frameFromExtents", Boolean.toString(GtkGlassChild.onFx(() ->
                GtkGlassShim.supportsNetWmHint("_NET_FRAME_EXTENTS"))));
        GtkEventTrace t = new GtkEventTrace();
        for (Kind kind : Kind.values()) {
            measure(t, out, kind);
        }
    }

    /**
     * Shows the {@code kind} window with a {@code View}, provides its frame extents, and records a settled reading
     * after it is shown, after a move, after an explicit window size and, for a resizable window, after the size
     * limits. Each request differs from the window's state before it - every {@code Kind} starts elsewhere, smaller
     * and without size limits - so that {@link GtkFrameExtentsStandIn#settled} can tell when it took effect. Closes
     * every window it made, whatever fails.
     */
    private static void measure(GtkEventTrace t, Map<String, String> out, Kind kind) throws Exception {
        boolean popup = kind == Kind.POPUP;
        List<Window> made = new ArrayList<>();
        try {
            Window owner = popup ? show(made, null, Kind.UNTITLED, false) : null;
            Window window = show(made, owner, kind, !popup);
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            t.settle();
            Measured m = new Measured(t, out, kind, window, xid,
                    GtkFrameExtentsStandIn.provide(out, kind.name(), xid, popup));
            m.step("shown", Effect.NONE, () -> {
            });
            m.step("moved", Effect.CLIENT, () -> window.setPosition(MOVE_X, MOVE_Y));
            m.step("sized", Effect.CLIENT, () -> window.setSize(SIZE_WIDTH, SIZE_HEIGHT));
            if (!popup) {
                m.step("limits", Effect.HINTS, () -> {
                    window.setMinimumSize(MIN_WIDTH, MIN_HEIGHT);
                    window.setMaximumSize(MAX_WIDTH, MAX_HEIGHT);
                });
            }
        } finally {
            close(made);
            t.settle();
        }
    }

    /**
     * A window of {@code kind} with a {@code View}, bounds set and shown, as Quantum shows a stage, added to
     * {@code made} as soon as it exists; FX thread.
     */
    private static Window show(List<Window> made, Window owner, Kind kind, boolean resizable) throws Exception {
        return GtkGlassChild.onFx(() -> {
            Window w = Application.GetApplication().createWindow(owner, Screen.getMainScreen(), kind.style);
            made.add(w);
            View v = Application.GetApplication().createView();
            v.setEventHandler(new View.EventHandler());
            w.setView(v);
            w.setResizable(resizable);
            w.setBounds(kind.x, kind.y, true, true, kind.width, kind.height, kind.contentWidth, kind.contentHeight,
                    0, 0);
            w.setVisible(true);
            return w;
        });
    }

    /** Closes {@code windows}, the last made first, each whatever closing another threw; FX thread. */
    private static void close(List<Window> windows) throws Exception {
        GtkGlassChild.onFx(() -> {
            RuntimeException failure = null;
            for (Window w : windows.reversed()) {
                try {
                    w.close();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            return null;
        });
    }

    /** The {@code kind} window being measured, and where its readings go. */
    private record Measured(GtkEventTrace t, Map<String, String> out, Kind kind, Window window, long xid,
                            boolean standIn) {

        /** Runs {@code action} on the FX thread and records the settled reading under {@code <kind>.<name>}. */
        void step(String name, Effect effect, Action action) throws Exception {
            GtkFrameExtentsStandIn.settled(t, out, kind + "." + name, xid, standIn, effect, action, () -> {
                Map<String, String> values = new LinkedHashMap<>();
                values.put("glass", rect(window.getX(), window.getY(), window.getWidth(), window.getHeight()));
                values.put("view", window.getView().getX() + "," + window.getView().getY());
                return values;
            });
        }
    }
}
