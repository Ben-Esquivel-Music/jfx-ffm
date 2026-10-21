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
 * The frame extents that {@link GtkFrameExtentsNativeTest} and {@link GtkUndecoratedStageBoundsTest} measure glass
 * under, and the readings they take. It is not a test class.
 * <p>
 * Weston, the window manager of WSLg, publishes {@code _NET_FRAME_EXTENTS} of 38, 38, 59, 38 (left, right, top,
 * bottom) for every window it frames, an undecorated one included. openbox, and i3 in its default layout, publish
 * 0, 0, 0, 0 for an undecorated window (i3 publishes its border for a window in a tabbed or stacked container with
 * siblings), and without a window manager there is no property.
 * So where the window manager has not published non-zero extents of its own within {@link #WINDOW_MANAGER_MILLIS},
 * the test writes Weston's values on the window itself ({@link #STAND_IN}, {@link GtkGlassShim#setNetFrameExtents}),
 * and glass handles the {@code PropertyNotify} as it would the window manager's. A window manager that writes the
 * property again - openbox writes 0, 0, 0, 0 whenever it resizes an undecorated window - gets it written back
 * before each reading is taken ({@link #settled}).
 */
final class GtkFrameExtentsStandIn {

    /** The extents written for a window that has none: Weston's for a window that is not maximized. */
    static final int[] STAND_IN = {38, 38, 59, 38};

    /** How long a window manager is given to publish non-zero extents of its own before the stand-in is written. */
    static final long WINDOW_MANAGER_MILLIS = 3000;

    /** How many rounds {@link #settled} waits for two readings in a row to agree. */
    static final int SETTLE_ROUNDS = 20;

    private GtkFrameExtentsStandIn() {
    }

    /**
     * The frame extents the window {@code xid} is to be measured under: waits up to {@link #WINDOW_MANAGER_MILLIS}
     * for non-zero extents of a window manager (not at all when GDK sees none), and writes {@link #STAND_IN} when
     * none came. Answers {@code true} when the stand-in is in use; records under {@code <prefix>.extentsSource}
     * where the extents come from.
     */
    static boolean provide(Map<String, String> out, String prefix, long xid) throws Exception {
        String windowManager = GtkGlassChild.onFx(GtkGlassShim::windowManagerName);
        String extents = GtkGlassChild.onFx(() -> GtkGlassShim.netFrameExtents(xid));
        if (!windowManager.equals("unknown")) {
            long deadline = System.nanoTime() + WINDOW_MANAGER_MILLIS * 1_000_000L;
            while (!nonZero(extents) && System.nanoTime() < deadline) {
                Thread.sleep(50);
                extents = GtkGlassChild.onFx(() -> GtkGlassShim.netFrameExtents(xid));
            }
        }
        if (nonZero(extents)) {
            out.put(prefix + ".extentsSource", "the window manager (" + windowManager + ")");
            return false;
        }
        String why = windowManager.equals("unknown") ? "no window manager runs"
                : "the window manager, " + windowManager + ", had published " + extents + " after "
                        + WINDOW_MANAGER_MILLIS + " ms";
        out.put(prefix + ".extentsSource", "the test's stand-in (" + why + ")");
        write(xid);
        return true;
    }

    /**
     * Takes readings of the window {@code xid} until two in a row agree, at most {@link #SETTLE_ROUNDS}, letting
     * glass handle every event first ({@link GtkEventTrace#settle}). Each reading is one task on the FX thread: what
     * {@code glass} reads, the client's root position and size ({@code client}, {@code x,y wxh}), its
     * {@code _NET_FRAME_EXTENTS} ({@code extents}) and its {@code WM_NORMAL_HINTS} ({@code hints}). With
     * {@code standIn}, a reading whose extents are not {@link #STAND_IN} is dropped and the stand-in written again.
     * Records the last reading under {@code <prefix>.<key>}, with {@code <prefix>.settled} the round it settled in
     * or {@code no}, and {@code <prefix>.rewrites} the number of times the stand-in was written again.
     */
    static void settled(GtkEventTrace t, Map<String, String> out, String prefix, long xid, boolean standIn,
                        Callable<Map<String, String>> glass) throws Exception {
        Map<String, String> last = null;
        Map<String, String> previous = null;
        int rewrites = 0;
        String settled = "no";
        for (int round = 1; round <= SETTLE_ROUNDS; round++) {
            t.settle();
            Map<String, String> now = GtkGlassChild.onFx(() -> {
                Map<String, String> values = new LinkedHashMap<>(glass.call());
                int[] position = GtkGlassShim.rootPosition(xid);
                int[] size = GtkGlassShim.windowSize(xid);
                values.put("client", rect(position[0], position[1], size[0], size[1]));
                values.put("extents", GtkGlassShim.netFrameExtents(xid));
                values.put("hints", GtkGlassShim.wmNormalHints(xid));
                return values;
            });
            last = now;
            if (standIn && !now.get("extents").equals(join(STAND_IN))) {
                write(xid);
                rewrites++;
                previous = null;
                continue;
            }
            if (now.equals(previous)) {
                settled = Integer.toString(round);
                break;
            }
            previous = now;
        }
        for (Map.Entry<String, String> entry : last.entrySet()) {
            out.put(prefix + "." + entry.getKey(), entry.getValue());
        }
        out.put(prefix + ".settled", settled);
        out.put(prefix + ".rewrites", Integer.toString(rewrites));
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
