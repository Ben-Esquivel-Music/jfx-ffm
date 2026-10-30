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

import com.sun.glass.ui.Screen;
import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;
import com.sun.glass.ui.gtk.GtkGlassShim;
import com.sun.glass.ui.gtk.GtkTraceWindow;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.Action;
import test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.Effect;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.parseRect;
import static test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.rect;

/**
 * The steps that {@link GtkTitledStateBoundsTest} and {@link GtkWindowPlacementTest} take with a glass window, and
 * their readings. It is not a test class.
 * <ul>
 * <li>Child side: a window shown as Quantum shows a stage ({@link #show}), and steps ({@link #step}): an action on
 * the FX thread - a request of the application, what a window manager does, or both - and the reading taken once it
 * has shown in X and settled ({@link GtkFrameExtentsStandIn#settled}), with what glass's window and view recorded
 * from the action on ({@code <step>.trace}, {@link GtkEventTrace}).</li>
 * <li>Parent side: one child per scenario, launched once and only where the display has the window manager the
 * scenario is for ({@link #run}), and its readings ({@link #reading}).</li>
 * </ul>
 * A scenario that stands in for a window manager writes its properties and moves the window itself, so it needs a
 * display without one; a scenario of a real window manager's behaviour needs one that lets a client be maximized,
 * taken full screen and moved, such as openbox. A display of the wrong kind skips the test; the first child tells
 * which kind the display is, so a later test of the wrong kind skips without a child.
 */
final class GtkBoundsSteps {

    /** The style mask {@code WindowStage} builds for {@code StageStyle.DECORATED}. */
    static final int TITLED = Window.TITLED | Window.CLOSABLE | Window.MINIMIZABLE | Window.MAXIMIZABLE;

    /** The style mask {@code WindowStage} builds for {@code StageStyle.UNDECORATED}. */
    static final int UNTITLED = Window.UNTITLED | Window.CLOSABLE;

    /** {@code env.windowManager} without a window manager ({@link GtkGlassChild#recordEnvironment}). */
    static final String NO_WINDOW_MANAGER = "unknown";

    /** {@code skipped}: why a child did not run its scenario. */
    static final String SKIPPED = "skipped";

    /** The children launched so far, by test class and scenario. */
    private static final Map<String, GtkGlassChildJvm.Run> RUNS = new HashMap<>();

    /** {@code env.windowManager} as the first child recorded it; {@code null} until a child has run. */
    private static String windowManager;

    private GtkBoundsSteps() {
    }

    // ---------------------------------------------------------------------------------------------
    // Child side
    // ---------------------------------------------------------------------------------------------

    /**
     * Records the environment, and answers whether the display runs a window manager as {@code needed} says; where
     * it does not, records why under {@link #SKIPPED}.
     */
    static boolean requireWindowManager(Map<String, String> out, boolean needed) throws Exception {
        GtkGlassChild.recordEnvironment(out);
        String found = out.get("env.windowManager");
        boolean runs = !NO_WINDOW_MANAGER.equals(found);
        if (runs != needed) {
            out.put(SKIPPED, needed ? "no window manager runs on this display"
                    : "this display runs a window manager, " + found);
            return false;
        }
        out.put("screen", GtkGlassChild.onFx(() -> {
            Screen screen = Screen.getMainScreen();
            return rect(screen.getPlatformX(), screen.getPlatformY(), screen.getPlatformWidth(),
                    screen.getPlatformHeight());
        }));
        out.put("gdk.frameFromExtents", Boolean.toString(GtkGlassChild.onFx(() ->
                GtkGlassShim.supportsNetWmHint("_NET_FRAME_EXTENTS"))));
        return true;
    }

    /**
     * A window of {@code style} named {@code name} with a view named {@code viewName}, recorded into {@code t},
     * resizable, its bounds set ({@code Window.setBounds(x, y, true, true, width, height, contentWidth,
     * contentHeight, 0, 0)}) and shown, as Quantum shows a stage.
     */
    static GtkTraceWindow show(GtkEventTrace t, String name, String viewName, int style, int x, int y, int width,
                               int height, int contentWidth, int contentHeight) throws Exception {
        return show(t, name, viewName, style, window ->
                window.setBounds(x, y, true, true, width, height, contentWidth, contentHeight, 0, 0));
    }

    /**
     * What is done with a window in the FX task that shows it: the application's calls before the show (its bounds,
     * and anything else), or what a window manager does as soon as the window exists; FX thread.
     */
    @FunctionalInterface
    interface SetUp {
        void run(GtkTraceWindow window) throws Exception;
    }

    /**
     * A window of {@code style} named {@code name} with a view named {@code viewName}, recorded into {@code t},
     * resizable, set up by {@code setUp} and shown, in one task on the FX thread.
     */
    static GtkTraceWindow show(GtkEventTrace t, String name, String viewName, int style, SetUp setUp)
            throws Exception {
        return show(t, name, viewName, style, setUp, window -> {
        });
    }

    /**
     * A window of {@code style} named {@code name} with a view named {@code viewName}, recorded into {@code t},
     * resizable, set up by {@code setUp}, shown, and then given {@code shown}, in one task on the FX thread, so that
     * glass handles no event of the show before {@code shown} has run.
     */
    static GtkTraceWindow show(GtkEventTrace t, String name, String viewName, int style, SetUp setUp, SetUp shown)
            throws Exception {
        return GtkGlassChild.onFx(() -> {
            GtkTraceWindow window = t.window(name, null, style, viewName, new GtkEventTrace.ViewHooks());
            window.setResizable(true);
            setUp.run(window);
            window.setVisible(true);
            shown.run(window);
            return window;
        });
    }

    /**
     * Runs {@code action} on the FX thread, waits until its {@code effect} shows in X and the readings of
     * {@code window} settle ({@link GtkFrameExtentsStandIn#settled}, never writing a stand-in itself), and records
     * the last reading under {@code <step>.<key>}: glass's {@code glass} rectangle, {@code view} offset and
     * {@code maximized} state, the window manager's {@code _NET_WM_STATE} ({@code state}), the X readings, and the
     * calls glass made into the window and its view from the action until then ({@code trace}, joined with
     * {@code " | "}).
     */
    static void step(GtkEventTrace t, Map<String, String> out, String step, Window window, Effect effect,
                     Action action) throws Exception {
        int from = t.lines().size();
        long xid = GtkGlassChild.onFx(window::getNativeWindow);
        GtkFrameExtentsStandIn.settled(t, out, step, xid, false, effect, action, () -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("glass", rect(window.getX(), window.getY(), window.getWidth(), window.getHeight()));
            View view = window.getView();
            values.put("view", view.getX() + "," + view.getY());
            values.put("maximized", Boolean.toString(window.isMaximized()));
            values.put("state", GtkGlassShim.netWmState(xid));
            return values;
        });
        List<String> lines = t.lines();
        out.put(step + ".trace", String.join(" | ", lines.subList(from, lines.size())));
    }

    /** Closes {@code windows}, the last first; FX thread. */
    static void close(List<? extends Window> windows) throws Exception {
        GtkGlassChild.onFx(() -> {
            for (Window window : windows.reversed()) {
                window.close();
            }
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Parent side
    // ---------------------------------------------------------------------------------------------

    /**
     * What the child of {@code testClass#scenario} recorded, launched the first time it is asked for: with the
     * toolkit, or, for a scenario that has to set the display up first, without
     * ({@link GtkGlassChildJvm#runWithoutToolkit}). Skips the calling test unless the display runs a window manager
     * as {@code needsWindowManager} says.
     */
    static GtkGlassChildJvm.Run run(Class<?> testClass, String scenario, boolean needsWindowManager,
                                    boolean startsItsOwnToolkit) {
        GtkGlassChildJvm.requireDisplay();
        if (windowManager != null) {
            requireWindowManager(testClass, scenario, needsWindowManager, windowManager);
        }
        String key = testClass.getName() + "#" + scenario;
        GtkGlassChildJvm.Run run = RUNS.get(key);
        if (run == null) {
            run = startsItsOwnToolkit
                    ? GtkGlassChildJvm.runWithoutToolkit(testClass, scenario, List.of(), true)
                    : GtkGlassChildJvm.run(testClass, scenario, List.of());
            RUNS.put(key, run);
        }
        String recorded = run.values().get("env.windowManager");
        if (recorded == null) {
            throw new AssertionError("the child recorded no env.windowManager: " + run.describe());
        }
        windowManager = recorded;
        requireWindowManager(testClass, scenario, needsWindowManager, recorded);
        return run;
    }

    private static void requireWindowManager(Class<?> testClass, String scenario, boolean needed, String found) {
        boolean runs = !NO_WINDOW_MANAGER.equals(found);
        assumeTrue(runs == needed, testClass.getSimpleName() + "#" + scenario + (needed
                ? " needs a window manager that lets a client be maximized, taken full screen and moved (openbox,"
                        + " Weston), and this display runs none"
                : " stands in for a window manager on a display without one, and this display runs " + found));
    }

    /** One settled reading of a child. */
    record Reading(String step, Map<String, String> values) {

        String glass() {
            return values.get("glass");
        }

        String view() {
            return values.get("view");
        }

        String client() {
            return values.get("client");
        }

        /** The client's top-level ancestor ({@link GtkGlassShim#topLevelGeometry}): the WM's frame, or the client. */
        String frame() {
            return values.get("frame");
        }

        boolean maximized() {
            return Boolean.parseBoolean(values.get("maximized"));
        }

        int[] extents() {
            return GtkFrameExtentsStandIn.extents(values.get("extents"));
        }

        /** What glass called on the window and its view from the step's action until the reading settled. */
        List<String> trace() {
            String trace = values.get("trace");
            return trace.isEmpty() ? List.of() : List.of(trace.split(" \\| "));
        }

        /** The frame the extents in force describe around the client: GDK's frame where it takes it from them. */
        String clientPlusExtents() {
            int[] e = extents();
            int[] c = parseRect(client());
            return rect(c[0] - e[0], c[1] - e[2], c[2] + e[0] + e[1], c[3] + e[2] + e[3]);
        }

        /** The step, both rectangles, the X readings, how the reading settled, the trace, and the expectation. */
        String describe(String expectation) {
            return "step '" + step + "': glass Window " + glass() + ", View " + view() + ", maximized " + maximized()
                    + "; client " + client() + ", X frame " + frame() + ", _NET_FRAME_EXTENTS " + values.get("extents")
                    + ", _NET_WM_STATE " + values.get("state") + "; the step's action: " + values.get("effect")
                    + "; settled: " + values.get("settled") + "; glass's calls since the action: " + values.get("trace")
                    + ". Expected: " + expectation;
        }
    }

    /**
     * The settled reading of {@code step} of {@code run}; fails unless the step's action showed in X and the readings
     * settled.
     */
    static Reading reading(GtkGlassChildJvm.Run run, String step) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("glass", "view", "maximized", "state", "client", "frame", "extents", "hints",
                "settled", "effect", "trace")) {
            String value = run.values().get(step + "." + key);
            if (value == null) {
                throw new AssertionError("the child recorded no " + step + "." + key + ": " + run.describe());
            }
            values.put(key, value);
        }
        Reading reading = new Reading(step, values);
        assertNotEquals(GtkFrameExtentsStandIn.NEVER_TOOK_EFFECT, values.get("settled"), reading.describe("the step's"
                + " action to show in X within " + GtkFrameExtentsStandIn.EFFECT_MILLIS + " ms; it never took"
                + " effect"));
        assertNotEquals(GtkFrameExtentsStandIn.NOT_SETTLED, values.get("settled"), reading.describe("a reading that"
                + " settled within " + GtkFrameExtentsStandIn.SETTLE_ROUNDS + " rounds after the step's action"
                + " showed in X"));
        return reading;
    }

    /** The value {@code key} of {@code run}, which the child must have recorded. */
    static String value(GtkGlassChildJvm.Run run, String key) {
        String value = run.values().get(key);
        if (value == null) {
            throw new AssertionError("the child recorded no " + key + ": " + run.describe());
        }
        return value;
    }
}
