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

import com.sun.glass.events.WindowEvent;
import com.sun.glass.ui.gtk.GtkGlassShim;
import com.sun.glass.ui.gtk.GtkTraceWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Which rectangle a {@code TITLED} glass window reports, and what it asks the window manager for, while the window
 * manager takes it full screen, maximizes and restores it, or drops its frame: {@code update_frame_extents},
 * {@code report_frame_in_force} and {@code process_configure} of {@code WindowContextTop} in {@code glass_window.cpp},
 * in child JVMs on the display of {@code DISPLAY}.
 * <ul>
 * <li>The window is the frame that the {@code _NET_FRAME_EXTENTS} in force describe around its client: all zero in
 * full screen (EWMH), so a full-screen window is its client, as large as the screen, and the maximized frame's while
 * maximized; it is its old frame again when it leaves full screen.</li>
 * <li>While the window manager has the window maximized or full screen, a change of the extents makes glass ask for
 * nothing - Weston obeys a resize of a maximized window - whether the window manager writes the extents after
 * {@code _NET_WM_STATE}, as Weston and openbox do, or before it; and the extents of that frame are neither stored
 * for the window's requests nor cached for the next window, so a restore that brings the floating frame's extents
 * back is no change, and the window stays where and as large as the window manager restores it.</li>
 * <li>An all-zero report while the window floats is a window manager that dropped the frame: the window is its client
 * from then on, for its requests too, but the next window does not start from it.</li>
 * </ul>
 * Each case runs twice where it can: without a window manager, with the test standing in for one - it writes
 * {@code _NET_WM_STATE} and {@code _NET_FRAME_EXTENTS} on the window and moves and sizes it, in the order Weston
 * does, so that the X server, which applies every request glass makes, shows what glass asked for - and with a
 * window manager that lets a client be maximized, taken full screen and moved, such as openbox, where the window
 * manager does it all. Without a window manager GDK takes a window's frame from the window tree, which is the client
 * itself, so there the window's position is its client's. A tiling window manager is not one this class is meant
 * for: i3 keeps a window in its tile and maximized, so the maximize and restore never take effect.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(300)
public class GtkTitledStateBoundsTest {

    /** Where the window is shown, and its window size. */
    static final int X = 200;
    static final int Y = 200;
    static final int WIDTH = 400;
    static final int HEIGHT = 300;

    /**
     * Weston's maximized frame on WSLg, which the stand-in replays: the work area 0,0 1920x1040 with its extents
     * 6, 6, 27, 6 (border and title bar, no shadow margin), so a client of 6,27 1908x1007.
     */
    static final int[] MAXIMIZED_EXTENTS = {6, 6, 27, 6};
    static final int[] MAXIMIZED_CLIENT = {6, 27, 1908, 1007};

    /** Where the second window of the all-zero scenario is shown. */
    static final int SECOND_X = 650;
    static final int SECOND_Y = 300;

    @BeforeAll
    static void requireDisplay() {
        GtkGlassChildJvm.requireDisplay();
    }

    // ---------------------------------------------------------------------------------------------
    // Full screen
    // ---------------------------------------------------------------------------------------------

    /** Full screen with the stand-in: the window is its client, as large as the screen, with its View at 0, 0. */
    @Test
    public void aFullScreenWindowIsTheScreenWithoutAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "fullScreenStandInScenario", false, false);
        aFullScreenWindowIsTheScreen(run);
    }

    /** Full screen under the window manager: the window is its client, as large as the screen. */
    @Test
    public void aFullScreenWindowIsTheScreenUnderAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "fullScreenScenario", true, false);
        aFullScreenWindowIsTheScreen(run);
    }

    /** Leaving full screen with the stand-in, which puts the client back: the window is its frame from before. */
    @Test
    public void aWindowLeavingFullScreenIsItsFrameFromBeforeWithoutAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "fullScreenStandInScenario", false, false);
        aWindowLeavingFullScreenIsItsFrameFromBefore(run);
    }

    /** Leaving full screen under the window manager: the window is its frame from before. */
    @Test
    public void aWindowLeavingFullScreenIsItsFrameFromBeforeUnderAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "fullScreenScenario", true, false);
        aWindowLeavingFullScreenIsItsFrameFromBefore(run);
    }

    private static void aFullScreenWindowIsTheScreen(GtkGlassChildJvm.Run run) {
        Reading full = GtkBoundsSteps.reading(run, "fullScreen");
        String screen = GtkBoundsSteps.value(run, "screen");
        assertEquals("_NET_FRAME_EXTENTS 0,0,0,0, client " + screen + ", Window " + screen + ", View 0,0",
                "_NET_FRAME_EXTENTS " + full.values().get("extents") + ", client " + full.client() + ", Window "
                        + full.glass() + ", View " + full.view(),
                full.describe("in full screen the extents in force are all zero (EWMH), so the Window is its"
                        + " client, as large as the screen " + screen + ", not the client plus the extents it had"
                        + " before"));
    }

    private static void aWindowLeavingFullScreenIsItsFrameFromBefore(GtkGlassChildJvm.Run run) {
        Reading normal = GtkBoundsSteps.reading(run, "normal");
        Reading restored = GtkBoundsSteps.reading(run, "restored");
        assertEquals("client " + normal.client() + ", Window " + normal.glass() + ", View " + normal.view(),
                "client " + restored.client() + ", Window " + restored.glass() + ", View " + restored.view(),
                restored.describe("the client and the Window of before full screen: " + normal.describe("-")));
    }

    // ---------------------------------------------------------------------------------------------
    // Maximize and restore
    // ---------------------------------------------------------------------------------------------

    /**
     * Maximized with the stand-in in Weston's order - the state, the client sized to the work area, then the
     * maximized frame's extents: glass asks for nothing, so the client keeps the size the window manager gave it, and
     * the window is the maximized frame those extents describe.
     */
    @Test
    public void anExtentsChangeWhileMaximizedAsksForNothingWithoutAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "maximizeStandInScenario", false, false);
        aMaximizedWindowAskedForNothing(GtkBoundsSteps.reading(run, "maximized"), "the state came first");
    }

    /**
     * Maximized with the stand-in writing the maximized frame's extents before the state, both before glass handles
     * either: GDK still says the window floats when glass handles the extents, and the state glass reads from X then
     * says maximized, so glass asks for nothing, as above.
     */
    @Test
    public void anExtentsChangeBeforeTheStateWhileMaximizedAsksForNothingWithoutAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "maximizeExtentsFirstStandInScenario", false, false);
        aMaximizedWindowAskedForNothing(GtkBoundsSteps.reading(run, "maximized"), "the extents came before the"
                + " state, so GDK still said floating when glass handled them");
    }

    private static void aMaximizedWindowAskedForNothing(Reading maximized, String order) {
        String client = rect(MAXIMIZED_CLIENT[0], MAXIMIZED_CLIENT[1], MAXIMIZED_CLIENT[2], MAXIMIZED_CLIENT[3]);
        String frame = rect(MAXIMIZED_CLIENT[0], MAXIMIZED_CLIENT[1],
                MAXIMIZED_CLIENT[2] + MAXIMIZED_EXTENTS[0] + MAXIMIZED_EXTENTS[1],
                MAXIMIZED_CLIENT[3] + MAXIMIZED_EXTENTS[2] + MAXIMIZED_EXTENTS[3]);
        assertEquals("client " + client + ", Window " + frame + ", maximized true, no resize request",
                "client " + maximized.client() + ", Window " + maximized.glass() + ", maximized "
                        + maximized.maximized() + ", " + resizeRequests(maximized),
                maximized.describe("glass asks for no size while the window manager has the window maximized (" + order
                        + "): the client stays " + client + ", and the Window is it plus the maximized frame's"
                        + " extents (without a window manager GDK's frame is the client, so the Window is at its"
                        + " position)"));
    }

    /**
     * Maximized under the window manager: glass asks for no size, so no notification of a window that is not
     * maximized reaches the {@code Window}, and the window is the maximized frame the extents describe.
     */
    @Test
    public void anExtentsChangeWhileMaximizedAsksForNothingUnderAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "maximizeScenario", true, false);
        Reading maximized = GtkBoundsSteps.reading(run, "maximized");
        String frame = titledFrame(run, maximized);
        assertEquals("Window " + frame + ", maximized true, no resize request",
                "Window " + maximized.glass() + ", maximized " + maximized.maximized() + ", "
                        + resizeRequests(maximized),
                maximized.describe("glass asks for no size while the window manager has the window maximized"
                        + " (a request is notified as RESIZE " + WindowEvent.RESIZE + " with the floating size), and"
                        + " the Window is the frame the extents in force describe"));
    }

    /**
     * Restored with the stand-in in Weston's order - the state, the client back where and as large as it was, then
     * the floating frame's extents: glass asks for nothing, and the window is its frame from before.
     */
    @Test
    public void aRestoreKeepsTheFrameTheWindowManagerRestoresWithoutAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "maximizeStandInScenario", false, false);
        aRestoredWindowIsItsFrameFromBefore(run);
    }

    /** Restored under the window manager: the window and the window manager's frame are where and as before. */
    @Test
    public void aRestoreKeepsTheFrameTheWindowManagerRestoresUnderAWindowManager() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "maximizeScenario", true, false);
        aRestoredWindowIsItsFrameFromBefore(run);
    }

    private static void aRestoredWindowIsItsFrameFromBefore(GtkGlassChildJvm.Run run) {
        Reading normal = GtkBoundsSteps.reading(run, "normal");
        Reading restored = GtkBoundsSteps.reading(run, "restored");
        assertEquals("client " + normal.client() + ", X frame " + normal.frame() + ", Window " + normal.glass()
                        + ", maximized false",
                "client " + restored.client() + ", X frame " + restored.frame() + ", Window " + restored.glass()
                        + ", maximized " + restored.maximized(),
                restored.describe("the client, the X frame and the Window of before maximize(true): "
                        + normal.describe("-")));
    }

    // ---------------------------------------------------------------------------------------------
    // All zero while floating
    // ---------------------------------------------------------------------------------------------

    /**
     * An all-zero report while the window floats is a window manager that dropped the frame: the window is its client
     * from then on, so it keeps its window size with a client that large, and an explicit size is the client's.
     */
    @Test
    public void anAllZeroReportWhileFloatingIsTheFrame() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "allZeroScenario", false, false);
        Reading normal = GtkBoundsSteps.reading(run, "normal");
        Reading zero = GtkBoundsSteps.reading(run, "zero");
        Reading sized = GtkBoundsSteps.reading(run, "sized");
        int[] client = parseRect(normal.client());
        String asked = rect(client[0], client[1], WIDTH, HEIGHT);
        String resized = rect(client[0], client[1], GtkFrameExtentsNativeTest.SIZE_WIDTH,
                GtkFrameExtentsNativeTest.SIZE_HEIGHT);
        assertEquals("all zero: client " + asked + ", Window " + asked + "; setSize: client " + resized + ", Window "
                        + resized,
                "all zero: client " + zero.client() + ", Window " + zero.glass() + "; setSize: client "
                        + sized.client() + ", Window " + sized.glass(),
                zero.describe("with no frame the " + WIDTH + "x" + HEIGHT + " window is a client of that size, and"
                        + " Window.setSize(" + GtkFrameExtentsNativeTest.SIZE_WIDTH + ", "
                        + GtkFrameExtentsNativeTest.SIZE_HEIGHT + ") then sizes the client to it; after setSize: "
                        + sized.describe("-")));
    }

    /**
     * The all-zero report is not cached: a {@code TITLED} window shown after it starts from the extents of the frame
     * before, so its client is its window size less them.
     */
    @Test
    public void anAllZeroReportWhileFloatingIsNotCached() {
        GtkGlassChildJvm.Run run = GtkBoundsSteps.run(getClass(), "allZeroScenario", false, false);
        Reading second = GtkBoundsSteps.reading(run, "second");
        int[] e = GtkFrameExtentsStandIn.STAND_IN;
        int[] client = parseRect(second.client());
        assertEquals((WIDTH - e[0] - e[1]) + "x" + (HEIGHT - e[2] - e[3]), client[2] + "x" + client[3],
                second.describe("the second window's client is its " + WIDTH + "x" + HEIGHT + " less the extents"
                        + " cached before the all-zero report, " + e[0] + "," + e[1] + "," + e[2] + "," + e[3]));
    }

    // ---------------------------------------------------------------------------------------------
    // Readings
    // ---------------------------------------------------------------------------------------------

    /**
     * The calls of {@code notifyResize} with {@code RESIZE} in the trace of {@code reading}: what a size request of
     * glass notifies ({@code notify_window_resize}). While the window is maximized every other notification is a
     * {@code MAXIMIZE}.
     */
    private static String resizeRequests(Reading reading) {
        List<String> found = new ArrayList<>();
        for (String line : reading.trace()) {
            if (line.contains(".notifyResize " + WindowEvent.RESIZE + " ")) {
                found.add(line);
            }
        }
        return found.isEmpty() ? "no resize request" : "resize requests " + found;
    }

    /**
     * The frame of a {@code TITLED} window as GDK has it: where GDK takes it from the extents, the client plus them;
     * elsewhere the client's top-level ancestor.
     */
    private static String titledFrame(GtkGlassChildJvm.Run run, Reading reading) {
        return Boolean.parseBoolean(GtkBoundsSteps.value(run, "gdk.frameFromExtents")) ? reading.clientPlusExtents()
                : reading.frame();
    }

    // ---------------------------------------------------------------------------------------------
    // The scenarios (child JVMs)
    // ---------------------------------------------------------------------------------------------

    /** Full screen and back, the test standing in for the window manager; no window manager. */
    static void fullScreenStandInScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        int[] screen = parseRect(out.get("screen"));
        fullScreen(out, xid -> {
            GtkGlassShim.setNetWmState(xid, "_NET_WM_STATE_FULLSCREEN");
            GtkGlassShim.setNetFrameExtents(xid, 0, 0, 0, 0);
            GtkGlassShim.moveResizeWindow(xid, screen[0], screen[1], screen[2], screen[3]);
        }, (xid, client) -> {
            int[] e = GtkFrameExtentsStandIn.STAND_IN;
            GtkGlassShim.setNetWmState(xid);
            GtkGlassShim.setNetFrameExtents(xid, e[0], e[1], e[2], e[3]);
            GtkGlassShim.moveResizeWindow(xid, client[0], client[1], client[2], client[3]);
        });
    }

    /** Full screen and back under the window manager. */
    static void fullScreenScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, true)) {
            return;
        }
        fullScreen(out, xid -> {
        }, (xid, client) -> {
        });
    }

    /** A window manager's answer to a request of the application, which a stand-in writes; FX thread. */
    @FunctionalInterface
    private interface Answer {
        void run(long xid) throws Exception;
    }

    /** A window manager's answer to a request, given the client rectangle of the window before the request. */
    @FunctionalInterface
    private interface Restore {
        void run(long xid, int[] client) throws Exception;
    }

    private static void fullScreen(Map<String, String> out, Answer enter, Restore exit) throws Exception {
        GtkEventTrace t = new GtkEventTrace();
        GtkTraceWindow window = GtkBoundsSteps.show(t, "w1", "v1", GtkBoundsSteps.TITLED, X, Y, WIDTH, HEIGHT, -1, -1);
        try {
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            t.settle();
            GtkFrameExtentsStandIn.provide(out, "w1", xid, false);
            GtkBoundsSteps.step(t, out, "normal", window, Effect.NONE, () -> {
            });
            int[] client = parseRect(out.get("normal.client"));
            GtkBoundsSteps.step(t, out, "fullScreen", window, Effect.CLIENT, () -> {
                window.getView().enterFullscreen(false, false, false);
                enter.run(xid);
            });
            GtkBoundsSteps.step(t, out, "restored", window, Effect.CLIENT, () -> {
                window.getView().exitFullscreen(false);
                exit.run(xid, client);
            });
        } finally {
            GtkBoundsSteps.close(List.of(window));
            t.settle();
        }
    }

    /**
     * {@code maximize(true)} and {@code maximize(false)}, the test standing in for the window manager in Weston's
     * order; no window manager.
     */
    static void maximizeStandInScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        maximize(out, xid -> {
            GtkGlassShim.setNetWmState(xid, "_NET_WM_STATE_MAXIMIZED_VERT", "_NET_WM_STATE_MAXIMIZED_HORZ");
            GtkGlassShim.moveResizeWindow(xid, MAXIMIZED_CLIENT[0], MAXIMIZED_CLIENT[1], MAXIMIZED_CLIENT[2],
                    MAXIMIZED_CLIENT[3]);
            GtkGlassShim.setNetFrameExtents(xid, MAXIMIZED_EXTENTS[0], MAXIMIZED_EXTENTS[1], MAXIMIZED_EXTENTS[2],
                    MAXIMIZED_EXTENTS[3]);
        }, GtkTitledStateBoundsTest::westonRestore);
    }

    /** Weston's restore: the state removed, the client back where and as large as it was, the floating extents. */
    private static void westonRestore(long xid, int[] client) {
        int[] e = GtkFrameExtentsStandIn.STAND_IN;
        GtkGlassShim.setNetWmState(xid);
        GtkGlassShim.moveResizeWindow(xid, client[0], client[1], client[2], client[3]);
        GtkGlassShim.setNetFrameExtents(xid, e[0], e[1], e[2], e[3]);
    }

    /**
     * {@code maximize(true)} with the stand-in writing the maximized frame's extents first, then the state and the
     * client's work-area rectangle, in the same task; {@code maximize(false)} in Weston's order; no window manager.
     */
    static void maximizeExtentsFirstStandInScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        maximize(out, xid -> {
            GtkGlassShim.setNetFrameExtents(xid, MAXIMIZED_EXTENTS[0], MAXIMIZED_EXTENTS[1], MAXIMIZED_EXTENTS[2],
                    MAXIMIZED_EXTENTS[3]);
            GtkGlassShim.setNetWmState(xid, "_NET_WM_STATE_MAXIMIZED_VERT", "_NET_WM_STATE_MAXIMIZED_HORZ");
            GtkGlassShim.moveResizeWindow(xid, MAXIMIZED_CLIENT[0], MAXIMIZED_CLIENT[1], MAXIMIZED_CLIENT[2],
                    MAXIMIZED_CLIENT[3]);
        }, GtkTitledStateBoundsTest::westonRestore);
    }

    /** {@code maximize(true)} and {@code maximize(false)} under the window manager. */
    static void maximizeScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, true)) {
            return;
        }
        maximize(out, xid -> {
        }, (xid, client) -> {
        });
    }

    private static void maximize(Map<String, String> out, Answer maximize, Restore restore) throws Exception {
        GtkEventTrace t = new GtkEventTrace();
        GtkTraceWindow window = GtkBoundsSteps.show(t, "w1", "v1", GtkBoundsSteps.TITLED, X, Y, WIDTH, HEIGHT, -1, -1);
        try {
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            t.settle();
            GtkFrameExtentsStandIn.provide(out, "w1", xid, false);
            GtkBoundsSteps.step(t, out, "normal", window, Effect.NONE, () -> {
            });
            int[] client = parseRect(out.get("normal.client"));
            GtkBoundsSteps.step(t, out, "maximized", window, Effect.CLIENT, () -> {
                window.maximize(true);
                maximize.run(xid);
            });
            GtkBoundsSteps.step(t, out, "restored", window, Effect.CLIENT, () -> {
                window.maximize(false);
                restore.run(xid, client);
            });
        } finally {
            GtkBoundsSteps.close(List.of(window));
            t.settle();
        }
    }

    /**
     * An all-zero report while floating, then an explicit size, then a second {@code TITLED} window that shows what
     * the cache holds; no window manager.
     */
    static void allZeroScenario(Map<String, String> out) throws Exception {
        if (!GtkBoundsSteps.requireWindowManager(out, false)) {
            return;
        }
        GtkEventTrace t = new GtkEventTrace();
        List<GtkTraceWindow> windows = new ArrayList<>();
        try {
            GtkTraceWindow window = GtkBoundsSteps.show(t, "w1", "v1", GtkBoundsSteps.TITLED, X, Y, WIDTH, HEIGHT, -1,
                    -1);
            windows.add(window);
            long xid = GtkGlassChild.onFx(window::getNativeWindow);
            t.settle();
            GtkFrameExtentsStandIn.provide(out, "w1", xid, false);
            GtkBoundsSteps.step(t, out, "normal", window, Effect.NONE, () -> {
            });
            GtkBoundsSteps.step(t, out, "zero", window, Effect.EXTENTS, () ->
                    GtkGlassShim.setNetFrameExtents(xid, 0, 0, 0, 0));
            GtkBoundsSteps.step(t, out, "sized", window, Effect.CLIENT, () ->
                    window.setSize(GtkFrameExtentsNativeTest.SIZE_WIDTH, GtkFrameExtentsNativeTest.SIZE_HEIGHT));
            GtkTraceWindow second = GtkBoundsSteps.show(t, "w2", "v2", GtkBoundsSteps.TITLED, SECOND_X, SECOND_Y,
                    WIDTH, HEIGHT, -1, -1);
            windows.add(second);
            GtkBoundsSteps.step(t, out, "second", second, Effect.NONE, () -> {
            });
        } finally {
            GtkBoundsSteps.close(windows);
            t.settle();
        }
    }
}
