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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The frame extents that {@link GtkFrameExtentsNativeTest}, {@link GtkUndecoratedStageBoundsTest},
 * {@link GtkTitledStateBoundsTest} and {@link GtkWindowPlacementTest} measure glass under, and the readings they take,
 * the last two through {@link GtkBoundsSteps}. It is not a test class.
 * <p>
 * Weston, the window manager of WSLg, publishes {@code _NET_FRAME_EXTENTS} of 38, 38, 59, 38 (left, right, top,
 * bottom) for every window it frames, an undecorated one included. openbox, and i3 in its default layout, publish
 * 0, 0, 0, 0 for an undecorated window (i3 publishes its border for a window in a tabbed or stacked container with
 * siblings). Without a window manager there is no property, and no window manager frames an override-redirect window
 * (a glass popup) or gives it one.
 * So where the window manager has not published non-zero extents of its own, the test writes Weston's values on the
 * window itself ({@link #STAND_IN}, {@link GtkGlassShim#setNetFrameExtents}), and glass handles the
 * {@code PropertyNotify} as it would the window manager's ({@link #provide}). A window manager that writes the
 * property again - openbox writes 0, 0, 0, 0 whenever it resizes an undecorated window - gets it written back
 * before each reading is taken ({@link #settled}).
 */
final class GtkFrameExtentsStandIn {

    /** The extents written for a window that has none: Weston's for a window that is not maximized. */
    static final int[] STAND_IN = {38, 38, 59, 38};

    /**
     * How long a window manager is given to publish {@code _NET_FRAME_EXTENTS} on a window it manages before the
     * stand-in is written: Weston can publish them a second after the window is shown.
     */
    static final long WINDOW_MANAGER_MILLIS = 3000;

    /**
     * How long {@link #settled} waits for the action of a step to show in X: many times what a window manager takes
     * to apply a move or a size, openbox at nice 19 under load included.
     */
    static final long EFFECT_MILLIS = 5000;

    /** How many rounds {@link #settled} waits, once the action has shown, for two readings in a row to agree. */
    static final int SETTLE_ROUNDS = 20;

    /** {@code <prefix>.settled} of a step whose action did not show in X within {@link #EFFECT_MILLIS}. */
    static final String NEVER_TOOK_EFFECT = "never took effect";

    /** {@code <prefix>.settled} of a step whose readings did not agree within {@link #SETTLE_ROUNDS} rounds. */
    static final String NOT_SETTLED = "no";

    /** What the action of a step changes in X, which {@link #settled} waits to see before it compares readings. */
    enum Effect {
        /** No action: the window as it is. */
        NONE(null),
        /** The client's root position or size, the {@code client} of a reading. */
        CLIENT("client"),
        /** The size limits of {@code WM_NORMAL_HINTS}, the {@code hints} of a reading. */
        HINTS("hints"),
        /** The window's {@code _NET_FRAME_EXTENTS}, the {@code extents} of a reading. */
        EXTENTS("extents");

        /** The key of the reading the action changes. */
        final String key;

        Effect(String key) {
            this.key = key;
        }
    }

    /** The action of a step, run on the FX thread. */
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    private GtkFrameExtentsStandIn() {
    }

    /**
     * The frame extents the window {@code xid} is to be measured under: the window manager's where it has published
     * non-zero ones, otherwise {@link #STAND_IN}, written at once. It waits, up to {@link #WINDOW_MANAGER_MILLIS},
     * only while a window manager runs and the window, which it manages, has no {@code _NET_FRAME_EXTENTS} yet; a
     * value already there, 0, 0, 0, 0 included, is the window manager's answer, and an {@code overrideRedirect}
     * window is not managed. Answers {@code true} when the stand-in is in use; records under
     * {@code <prefix>.extentsSource} where the extents come from.
     */
    static boolean provide(Map<String, String> out, String prefix, long xid, boolean overrideRedirect)
            throws Exception {
        String windowManager = GtkGlassChild.onFx(GtkGlassShim::windowManagerName);
        boolean managed = !windowManager.equals("unknown") && !overrideRedirect;
        String extents = GtkGlassChild.onFx(() -> GtkGlassShim.netFrameExtents(xid));
        if (managed) {
            long deadline = System.nanoTime() + WINDOW_MANAGER_MILLIS * 1_000_000L;
            while (extents.equals("none") && System.nanoTime() < deadline) {
                Thread.sleep(50);
                extents = GtkGlassChild.onFx(() -> GtkGlassShim.netFrameExtents(xid));
            }
        }
        if (nonZero(extents)) {
            out.put(prefix + ".extentsSource", "the window manager (" + windowManager + ")");
            return false;
        }
        String why;
        if (windowManager.equals("unknown")) {
            why = "no window manager runs";
        } else if (overrideRedirect) {
            why = "an override-redirect window, which " + windowManager + " does not manage";
        } else if (extents.equals("none")) {
            why = "the window manager, " + windowManager + ", had published none after " + WINDOW_MANAGER_MILLIS
                    + " ms";
        } else {
            why = "the window manager, " + windowManager + ", published " + extents;
        }
        out.put(prefix + ".extentsSource", "the test's stand-in (" + why + ")");
        write(xid);
        return true;
    }

    /**
     * Runs {@code action} on the FX thread and takes readings of the window {@code xid} until the action shows in X
     * and two readings in a row agree, letting glass handle every event before each ({@link GtkEventTrace#settle}).
     * The action has shown once the {@code effect} value of a reading differs from that value read in the same FX
     * task just before the action; that is awaited for at most {@link #EFFECT_MILLIS}, and then at most
     * {@link #SETTLE_ROUNDS} rounds for the agreement. Each reading is one task on the FX thread: what {@code glass}
     * reads, the client's root position and size ({@code client}, {@code x,y wxh}), its top-level ancestor
     * ({@code frame}, {@link GtkGlassShim#topLevelGeometry}), its {@code _NET_FRAME_EXTENTS} ({@code extents}) and its
     * {@code WM_NORMAL_HINTS} ({@code hints}). With {@code standIn}, a reading whose extents are not
     * {@link #STAND_IN} is not compared and the stand-in is written again. Records the last reading under
     * {@code <prefix>.<key>}, with {@code <prefix>.settled} the round it settled in, {@link #NOT_SETTLED} or
     * {@link #NEVER_TOOK_EFFECT}, {@code <prefix>.effect} what the action changed and when, and
     * {@code <prefix>.rewrites} the number of times the stand-in was written again.
     */
    static void settled(GtkEventTrace t, Map<String, String> out, String prefix, long xid, boolean standIn,
                        Effect effect, Action action, Callable<Map<String, String>> glass) throws Exception {
        String before = GtkGlassChild.onFx(() -> {
            String value = effect == Effect.NONE ? null : x11(xid).get(effect.key);
            action.run();
            return value;
        });
        long start = System.nanoTime();
        String shown = effect == Effect.NONE ? "none (no action)" : null;
        Map<String, String> last = null;
        Map<String, String> previous = null;
        int rewrites = 0;
        int rounds = 0;
        String settled = null;
        while (settled == null) {
            t.settle();
            Map<String, String> now = GtkGlassChild.onFx(() -> {
                Map<String, String> values = new LinkedHashMap<>(glass.call());
                values.putAll(x11(xid));
                return values;
            });
            last = now;
            boolean rewritten = standIn && !now.get("extents").equals(join(STAND_IN));
            if (rewritten) {
                write(xid);
                rewrites++;
                previous = null;
            }
            long millis = (System.nanoTime() - start) / 1_000_000L;
            if (shown == null) {
                if (now.get(effect.key).equals(before)) {
                    if (millis >= EFFECT_MILLIS) {
                        shown = effect.key + " still " + before + " after " + millis + " ms";
                        settled = NEVER_TOOK_EFFECT;
                    }
                    continue;
                }
                shown = effect.key + " " + before + " -> " + now.get(effect.key) + " after " + millis + " ms";
            }
            rounds++;
            if (!rewritten && now.equals(previous)) {
                settled = Integer.toString(rounds);
            } else if (rounds >= SETTLE_ROUNDS) {
                settled = NOT_SETTLED;
            } else if (!rewritten) {
                previous = now;
            }
        }
        for (Map.Entry<String, String> entry : last.entrySet()) {
            out.put(prefix + "." + entry.getKey(), entry.getValue());
        }
        out.put(prefix + ".settled", settled);
        out.put(prefix + ".effect", shown);
        out.put(prefix + ".rewrites", Integer.toString(rewrites));
    }

    /** What X says about the window {@code xid}: {@code client}, {@code frame}, {@code extents}, {@code hints}. */
    private static Map<String, String> x11(long xid) {
        Map<String, String> values = new LinkedHashMap<>();
        int[] position = GtkGlassShim.rootPosition(xid);
        int[] size = GtkGlassShim.windowSize(xid);
        int[] frame = GtkGlassShim.topLevelGeometry(xid);
        values.put("client", rect(position[0], position[1], size[0], size[1]));
        values.put("frame", rect(frame[0], frame[1], frame[2], frame[3]));
        values.put("extents", GtkGlassShim.netFrameExtents(xid));
        values.put("hints", GtkGlassShim.wmNormalHints(xid));
        return values;
    }

    private static void write(long xid) throws Exception {
        GtkGlassChild.onFx(() -> {
            GtkGlassShim.setNetFrameExtents(xid, STAND_IN[0], STAND_IN[1], STAND_IN[2], STAND_IN[3]);
            return null;
        });
    }

    /** {@code x,y wxh}. */
    static String rect(int x, int y, int width, int height) {
        return x + "," + y + " " + width + "x" + height;
    }

    /** The four numbers of {@link GtkGlassShim#netFrameExtents}, all 0 for {@code none}. */
    static int[] extents(String text) {
        if (text.equals("none")) {
            return new int[4];
        }
        String[] parts = text.split(",");
        int[] e = new int[4];
        for (int i = 0; i < 4; i++) {
            e[i] = Integer.parseInt(parts[i]);
        }
        return e;
    }

    /** The {@code x, y, width, height} of a {@link #rect} string. */
    static int[] parseRect(String text) {
        String[] parts = text.split("[, x]");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])};
    }

    static boolean nonZero(String extents) {
        for (int e : extents(extents)) {
            if (e != 0) {
                return true;
            }
        }
        return false;
    }

    private static String join(int[] e) {
        return e[0] + "," + e[1] + "," + e[2] + "," + e[3];
    }
}
