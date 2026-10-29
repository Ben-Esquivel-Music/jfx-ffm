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

package com.sun.glass.ui.gtk;

import com.sun.glass.ui.Screen;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_X11;
import static com.sun.glass.ui.gtk.GtkGlassNative.X11;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.GDK_DISPLAY_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.DEFAULT_DPI;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_SCREEN_GET_DEFAULT;
import static com.sun.glass.ui.gtk.GtkRobotCalls.GDK_X11_DISPLAY_GET_XDISPLAY;
import static com.sun.glass.ui.gtk.GtkRobotCalls.X_INTERN_ATOM;
import static com.sun.glass.ui.gtk.GtkRobotCalls.getUIScale;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The screen list of the Linux glass: {@code GtkApplication.staticScreen_getScreens} ({@code GlassApplication.cpp}
 * at commit {@code 033187ad90} -> {@code rebuild_screens}, {@code createJavaScreen}, {@code get_screen_workarea},
 * {@code get_current_desktop} of {@code glass_screen.cpp}), and the {@code Screen} that
 * {@code notify_move_to_another_screen} hands its window ({@link GtkCallbackTargets}).
 */
final class GtkScreenCalls {

    /** {@code GdkRectangle}: {@code gint x, y, width, height}. */
    private static final long GDK_RECTANGLE_SIZE = 16;

    /** {@code XA_CARDINAL} of {@code Xatom.h}. */
    private static final long XA_CARDINAL = 6;

    /** {@code AnyPropertyType} of {@code X.h}; also {@code None} and {@code Success}. */
    private static final long X_ANY_PROPERTY_TYPE = 0;

    /** {@code gint gdk_screen_get_n_monitors(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_GET_N_MONITORS = bind(GDK, LIB_GDK, "gdk_screen_get_n_monitors",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code void gdk_screen_get_monitor_geometry(GdkScreen *screen, gint monitor_num, GdkRectangle *dest)}. */
    private static final MethodHandle GDK_SCREEN_GET_MONITOR_GEOMETRY = bind(GDK, LIB_GDK,
            "gdk_screen_get_monitor_geometry", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));

    /** {@code GdkVisual *gdk_screen_get_system_visual(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_GET_SYSTEM_VISUAL = bind(GDK, LIB_GDK,
            "gdk_screen_get_system_visual", FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code gint gdk_visual_get_depth(GdkVisual *visual)}, which the {@code glass_gdk_visual_get_depth} of
     * {@code glass_general.cpp} wrapped at commit {@code 033187ad90}.
     */
    private static final MethodHandle GDK_VISUAL_GET_DEPTH = bind(GDK, LIB_GDK, "gdk_visual_get_depth",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * {@code gboolean gdk_rectangle_intersect(const GdkRectangle *src1, const GdkRectangle *src2,
     * GdkRectangle *dest)}.
     */
    private static final MethodHandle GDK_RECTANGLE_INTERSECT = bind(GDK, LIB_GDK, "gdk_rectangle_intersect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** {@code gint gdk_screen_get_width(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_GET_WIDTH = bind(GDK, LIB_GDK, "gdk_screen_get_width",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code gint gdk_screen_get_height(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_GET_HEIGHT = bind(GDK, LIB_GDK, "gdk_screen_get_height",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code gint gdk_screen_get_width_mm(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_GET_WIDTH_MM = bind(GDK, LIB_GDK, "gdk_screen_get_width_mm",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code gint gdk_screen_get_height_mm(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_GET_HEIGHT_MM = bind(GDK, LIB_GDK, "gdk_screen_get_height_mm",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code gint gdk_screen_get_monitor_width_mm(GdkScreen *screen, gint monitor_num)}. */
    private static final MethodHandle GDK_SCREEN_GET_MONITOR_WIDTH_MM = bind(GDK, LIB_GDK,
            "gdk_screen_get_monitor_width_mm", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** {@code gint gdk_screen_get_monitor_height_mm(GdkScreen *screen, gint monitor_num)}. */
    private static final MethodHandle GDK_SCREEN_GET_MONITOR_HEIGHT_MM = bind(GDK, LIB_GDK,
            "gdk_screen_get_monitor_height_mm", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /** {@code GdkWindow *gdk_screen_get_root_window(GdkScreen *screen)}. */
    private static final MethodHandle GDK_SCREEN_GET_ROOT_WINDOW = bind(GDK, LIB_GDK, "gdk_screen_get_root_window",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code Window gdk_x11_window_get_xid(GdkWindow *window)}, which the {@code GDK_WINDOW_XID} macro calls. */
    private static final MethodHandle GDK_X11_WINDOW_GET_XID = bind(GDK, LIB_GDK, "gdk_x11_window_get_xid",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code int XGetWindowProperty(Display *, Window, Atom property, long offset, long length, Bool delete,
     * Atom req_type, Atom *type, int *format, unsigned long *nitems, unsigned long *after, unsigned char **prop)}.
     */
    private static final MethodHandle X_GET_WINDOW_PROPERTY = bind(X11, LIB_X11, "XGetWindowProperty",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT,
                    JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code int XFree(void *data)}. */
    private static final MethodHandle X_FREE = bind(X11, LIB_X11, "XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code rebuild_screens} ({@code glass_screen.cpp}). */
    static Screen[] screens() {
        try {
            MemorySegment screen = (MemorySegment) GDK_SCREEN_GET_DEFAULT.invokeExact();
            int monitors = (int) GDK_SCREEN_GET_N_MONITORS.invokeExact(screen);
            Screen[] result = new Screen[monitors];
            for (int i = 0; i < monitors; i++) {
                result[i] = createScreen(screen, i);
            }
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code createJavaScreen} ({@code glass_screen.cpp}); the {@code (int)} casts truncate towards zero as the C's
     * {@code jint} conversions did.
     */
    static Screen createScreen(MemorySegment screen, int monitor) throws Throwable {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment workArea = screenWorkarea(call, screen);
            MemorySegment geometry = call.allocate(GDK_RECTANGLE_SIZE);
            GDK_SCREEN_GET_MONITOR_GEOMETRY.invokeExact(screen, monitor, geometry);
            MemorySegment visual = (MemorySegment) GDK_SCREEN_GET_SYSTEM_VISUAL.invokeExact(screen);
            MemorySegment working = call.allocate(GDK_RECTANGLE_SIZE);
            // the C ignored the return too: an empty intersection leaves dest zeroed and is used as it is
            int ignored = (int) GDK_RECTANGLE_INTERSECT.invokeExact(workArea, geometry, working);

            float uiScale = getUIScale(screen);

            int gx = geometry.get(JAVA_INT, 0);
            int gy = geometry.get(JAVA_INT, 4);
            int gw = geometry.get(JAVA_INT, 8);
            int gh = geometry.get(JAVA_INT, 12);
            int mx = (int) (gx / uiScale);
            int my = (int) (gy / uiScale);
            int mw = (int) (gw / uiScale);
            int mh = (int) (gh / uiScale);
            int wx = (int) (working.get(JAVA_INT, 0) / uiScale);
            int wy = (int) (working.get(JAVA_INT, 4) / uiScale);
            int ww = (int) (working.get(JAVA_INT, 8) / uiScale);
            int wh = (int) (working.get(JAVA_INT, 12) / uiScale);

            int mmW = (int) GDK_SCREEN_GET_MONITOR_WIDTH_MM.invokeExact(screen, monitor);
            int mmH = (int) GDK_SCREEN_GET_MONITOR_HEIGHT_MM.invokeExact(screen, monitor);
            if (mmW <= 0 || mmH <= 0) {
                if ((int) GDK_SCREEN_GET_N_MONITORS.invokeExact(screen) == 1) {
                    mmW = (int) GDK_SCREEN_GET_WIDTH_MM.invokeExact(screen);
                    mmH = (int) GDK_SCREEN_GET_HEIGHT_MM.invokeExact(screen);
                }
            }
            int dpiX;
            int dpiY;
            if (mmW <= 0 || mmH <= 0) {
                dpiX = DEFAULT_DPI;
                dpiY = DEFAULT_DPI;
            } else {
                dpiX = (mw * 254) / (mmW * 10);
                dpiY = (mh * 254) / (mmH * 10);
            }
            int depth = visual.address() == 0 ? 0 : (int) GDK_VISUAL_GET_DEPTH.invokeExact(visual);
            return new Screen(monitor, depth, mx, my, mw, mh, gx, gy, gw, gh, wx, wy, ww, wh, dpiX, dpiY,
                    uiScale, uiScale, uiScale, uiScale);
        }
    }

    /** {@code get_screen_workarea} ({@code glass_screen.cpp}): a {@code GdkRectangle} allocated in {@code call}. */
    private static MemorySegment screenWorkarea(Arena call, MemorySegment screen) throws Throwable {
        MemorySegment display = (MemorySegment) GDK_X11_DISPLAY_GET_XDISPLAY.invokeExact(
                (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact());
        MemorySegment rectangle = call.allocate(GDK_RECTANGLE_SIZE);
        rectangle.set(JAVA_INT, 0, 0);
        rectangle.set(JAVA_INT, 4, 0);
        rectangle.set(JAVA_INT, 8, (int) GDK_SCREEN_GET_WIDTH.invokeExact(screen));
        rectangle.set(JAVA_INT, 12, (int) GDK_SCREEN_GET_HEIGHT.invokeExact(screen));

        long workareaAtom = (long) X_INTERN_ATOM.invokeExact(display, call.allocateFrom("_NET_WORKAREA"), 1);
        if (workareaAtom == X_ANY_PROPERTY_TYPE) {
            return rectangle;
        }
        MemorySegment root = (MemorySegment) GDK_SCREEN_GET_ROOT_WINDOW.invokeExact(screen);
        long xid = (long) GDK_X11_WINDOW_GET_XID.invokeExact(root);
        MemorySegment type = call.allocate(JAVA_LONG);
        MemorySegment format = call.allocate(JAVA_INT);
        MemorySegment num = call.allocate(JAVA_LONG);
        MemorySegment left = call.allocate(JAVA_LONG);
        MemorySegment dataOut = call.allocate(ADDRESS);
        int result = (int) X_GET_WINDOW_PROPERTY.invokeExact(display, xid, workareaAtom, 0L, Long.MAX_VALUE, 0,
                X_ANY_PROPERTY_TYPE, type, format, num, left, dataOut);
        MemorySegment data = dataOut.get(ADDRESS, 0);
        if (result == 0 && data.address() != 0) {
            if (type.get(JAVA_LONG, 0) != X_ANY_PROPERTY_TYPE && format.get(JAVA_INT, 0) == 32) {
                long count = num.get(JAVA_LONG, 0);
                int currentDesktop = currentDesktop(call, display, screen);
                if (Integer.toUnsignedLong(currentDesktop) < Long.divideUnsigned(count, 4)) {
                    long base = Integer.toUnsignedLong(currentDesktop) * 4;
                    MemorySegment values = reinterpretLongs(data, base + 4);
                    rectangle.set(JAVA_INT, 0, (int) values.getAtIndex(JAVA_LONG, base));
                    rectangle.set(JAVA_INT, 4, (int) values.getAtIndex(JAVA_LONG, base + 1));
                    rectangle.set(JAVA_INT, 8, (int) values.getAtIndex(JAVA_LONG, base + 2));
                    rectangle.set(JAVA_INT, 12, (int) values.getAtIndex(JAVA_LONG, base + 3));
                }
            }
            int freed = (int) X_FREE.invokeExact(data);
        }
        return rectangle;
    }

    /** {@code get_current_desktop} ({@code glass_screen.cpp}). */
    private static int currentDesktop(Arena call, MemorySegment display, MemorySegment screen) throws Throwable {
        long atom = (long) X_INTERN_ATOM.invokeExact(display, call.allocateFrom("_NET_CURRENT_DESKTOP"), 1);
        if (atom == X_ANY_PROPERTY_TYPE) {
            return 0;
        }
        MemorySegment root = (MemorySegment) GDK_SCREEN_GET_ROOT_WINDOW.invokeExact(screen);
        long xid = (long) GDK_X11_WINDOW_GET_XID.invokeExact(root);
        MemorySegment type = call.allocate(JAVA_LONG);
        MemorySegment format = call.allocate(JAVA_INT);
        MemorySegment num = call.allocate(JAVA_LONG);
        MemorySegment left = call.allocate(JAVA_LONG);
        MemorySegment dataOut = call.allocate(ADDRESS);
        int result = (int) X_GET_WINDOW_PROPERTY.invokeExact(display, xid, atom, 0L, Long.MAX_VALUE, 0,
                XA_CARDINAL, type, format, num, left, dataOut);
        MemorySegment data = dataOut.get(ADDRESS, 0);
        int desktop = 0;
        if (result == 0 && data.address() != 0) {
            if (type.get(JAVA_LONG, 0) == XA_CARDINAL && format.get(JAVA_INT, 0) == 32) {
                desktop = (int) reinterpretLongs(data, 1).get(JAVA_LONG, 0);
            }
            int freed = (int) X_FREE.invokeExact(data);
        }
        return desktop;
    }

    /** {@code count} {@code unsigned long}s of an X property, which {@code XGetWindowProperty} returned unsized. */
    private static MemorySegment reinterpretLongs(MemorySegment data, long count) {
        return bounded(data, count * JAVA_LONG.byteSize());
    }

    private GtkScreenCalls() {
    }
}
