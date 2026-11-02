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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Two monitors side by side on an X server that has one, such as Xvfb: the screen's one RandR monitor is split into
 * a left and a right half by two RandR 1.5 monitors of a client ({@code XRRSetMonitor}), made on a connection of its
 * own before the toolkit starts, so that GDK, and glass with it, sees two monitors. {@link #close} deletes them,
 * which gives the screen its own monitor back.
 * <p>
 * <b>That GDK sees the right half at all rests on how two libraries happen to be written, not on anything they
 * document.</b> Xvfb has one RandR output, and a RandR monitor that takes an output takes it from every other
 * monitor, so only one half can have it. GDK 3.24.40 and later take {@code outputs[0]} of every monitor without
 * looking at its {@code noutput}, leave out a monitor whose output they cannot read, and match each RandR monitor to
 * its GDK monitor by name, so that two monitors with the same output stay two ({@code find_monitor_by_name} in
 * {@code init_randr15} of {@code gdkscreen-x11.c}). {@code XRRGetMonitors} of libXrandr returns one allocation, the
 * monitors and then the outputs of all of them, so {@code outputs[0]} of a monitor without an output is the first
 * output of the monitor after it. The server lists a client's monitors in the order they were made, so the right
 * half is made first, without an output, and the left half after it, with the output: GDK has the right half as
 * monitor 0 and the left half as monitor 1. Made the other way round, {@code outputs[0]} of the right half lies past
 * the end of the allocation, and GDK sees the left half alone. Earlier 3.24 releases match by output instead
 * ({@code find_monitor_by_output}), so the left half takes over the right half's GDK monitor and GDK sees one
 * monitor. Where GDK or libXrandr does this differently, GDK sees other monitors; a scenario then checks what GDK
 * sees and skips, so it can never fail for that reason.
 * <ul>
 * <li>Only a screen with exactly one RandR output and that output's own monitor, as Xvfb has, is split, and only
 * where the server then lists the right half before the left one ({@link Split#refused} says why not); before it
 * looks, the monitors of its own names that a run which ended without {@link #close} left are deleted. Every request
 * is one the server accepts - deleting a monitor of a name no monitor has is a {@code BadValue} - because an X error
 * on this connection goes to the process's Xlib error handler, which before the toolkit starts is Xlib's own and ends
 * the process.</li>
 * <li>The connection is opened while a client that stays is connected (the scripted window manager) and closed
 * before that one goes, so it is never the server's last client: an Xvfb that resets when its last client goes (its
 * default) deletes the monitors then, and can close a connection opened in the same wake-up.</li>
 * </ul>
 * LP64 only, like the rest of the GTK glass binding. Scenarios only; not a production class.
 */
public final class GtkSplitScreen implements AutoCloseable {

    /** The names of the two monitors: atoms, which must not be the name of an output. */
    public static final String LEFT_NAME = "glass-test-left-half";
    public static final String RIGHT_NAME = "glass-test-right-half";

    /**
     * What {@link #splitIntoHalves} did: the split it made, or, where it made none, why. Exactly one of the two is
     * {@code null}.
     */
    public record Split(GtkSplitScreen screen, String refused) {
    }

    /** One RandR monitor as {@code XRRGetMonitors} lists it. */
    private record Monitor(long name, boolean automatic, int x, int y, int width, int height, int widthMm,
                           int heightMm, int outputs) {

        @Override
        public String toString() {
            return width + "x" + height + "+" + x + "+" + y + " (" + widthMm + "x" + heightMm + " mm, " + outputs
                    + " outputs" + (automatic ? ", automatic" : "") + ")";
        }
    }

    /** {@code XRRMonitorInfo} of {@code Xrandr.h} on LP64. */
    private static final StructLayout MONITOR_INFO = MemoryLayout.structLayout(
            JAVA_LONG.withName("name"),
            JAVA_INT.withName("primary"),
            JAVA_INT.withName("automatic"),
            JAVA_INT.withName("noutput"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("mwidth"),
            JAVA_INT.withName("mheight"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("outputs"));

    /** {@code XRRScreenResources} of {@code Xrandr.h} on LP64. */
    private static final StructLayout SCREEN_RESOURCES = MemoryLayout.structLayout(
            JAVA_LONG.withName("timestamp"),
            JAVA_LONG.withName("configTimestamp"),
            JAVA_INT.withName("ncrtc"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("crtcs"),
            JAVA_INT.withName("noutput"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("outputs"),
            JAVA_INT.withName("nmode"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("modes"));

    private final MemorySegment display;
    private final long root;
    private final long leftName;
    private final long rightName;
    private final int[] left;
    private final int[] right;
    private final String monitors;

    private GtkSplitScreen(MemorySegment display, long root, long leftName, long rightName, int[] left, int[] right,
                           String monitors) {
        this.display = display;
        this.root = root;
        this.leftName = leftName;
        this.rightName = rightName;
        this.left = left;
        this.right = right;
        this.monitors = monitors;
    }

    /**
     * Splits the screen of the X server of {@code DISPLAY} into a left and a right half, the left one the width's
     * lower half, each with its share of the physical width; or, where the screen has another number of RandR
     * outputs or monitors, the server no RandR 1.5, or the server lists the halves the other way round, leaves the
     * screen as it was and says why.
     */
    public static Split splitIntoHalves() {
        try {
            MemorySegment display = (MemorySegment) X.OPEN_DISPLAY.invokeExact(MemorySegment.NULL);
            if (display.address() == 0) {
                throw new IllegalStateException("XOpenDisplay(NULL) failed: no X server at DISPLAY");
            }
            GtkSplitScreen split = null;
            try (Arena local = Arena.ofConfined()) {
                long root = (long) X.DEFAULT_ROOT_WINDOW.invokeExact(display);
                MemorySegment major = local.allocate(JAVA_INT);
                MemorySegment minor = local.allocate(JAVA_INT);
                int queried = (int) X.RR_QUERY_VERSION.invokeExact(display, major, minor);
                if (queried == 0 || major.get(JAVA_INT, 0) < 1
                        || (major.get(JAVA_INT, 0) == 1 && minor.get(JAVA_INT, 0) < 5)) {
                    return new Split(null, "the X server has no RandR 1.5 (" + (queried == 0 ? "no RandR"
                            : "RandR " + major.get(JAVA_INT, 0) + "." + minor.get(JAVA_INT, 0)) + "), which has the"
                            + " monitors of a client");
                }
                long leftName = atom(display, local, LEFT_NAME);
                long rightName = atom(display, local, RIGHT_NAME);
                deleteListed(display, root, leftName, rightName);
                List<Monitor> before = monitors(display, root);
                long[] outputs = outputs(display, root);
                if (outputs.length != 1 || before.size() != 1 || !before.getFirst().automatic()) {
                    return new Split(null, "the screen has " + outputs.length + " RandR outputs and the monitors "
                            + before + "; only one output with its own monitor, as Xvfb has, is split");
                }
                Monitor whole = before.getFirst();
                int half = whole.width() / 2;
                int[] left = {whole.x(), whole.y(), half, whole.height()};
                int[] right = {whole.x() + half, whole.y(), whole.width() - half, whole.height()};
                setMonitor(display, root, rightName, right, whole.widthMm() - whole.widthMm() / 2, whole.heightMm(),
                        0);
                setMonitor(display, root, leftName, left, whole.widthMm() / 2, whole.heightMm(), outputs[0]);
                List<Monitor> after = monitors(display, root);
                if (after.size() != 2 || after.get(0).name() != rightName || after.get(1).name() != leftName) {
                    deleteListed(display, root, leftName, rightName);
                    return new Split(null, "the server lists the monitors " + after + " after the split of " + whole
                            + ", not the right half and then the left half, so GDK would not see the right half");
                }
                split = new GtkSplitScreen(display, root, leftName, rightName, left, right, after.toString());
                return new Split(split, null);
            } finally {
                if (split == null) {
                    int closed = (int) X.CLOSE_DISPLAY.invokeExact(display);
                }
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The left half: {@code x, y, width, height}. */
    public int[] left() {
        return left.clone();
    }

    /** The right half: {@code x, y, width, height}. */
    public int[] right() {
        return right.clone();
    }

    /** The server's monitors after the split, in its order, as {@code XRRGetMonitors} listed them. */
    public String monitors() {
        return monitors;
    }

    /** Deletes the two monitors, which gives the screen its own monitor back, and closes the connection. */
    @Override
    public void close() {
        try {
            deleteListed(display, root, leftName, rightName);
            int closed = (int) X.CLOSE_DISPLAY.invokeExact(display);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Deletes those of the monitors {@code names} that the server lists, and waits for it. */
    private static void deleteListed(MemorySegment display, long root, long... names) throws Throwable {
        for (Monitor monitor : monitors(display, root)) {
            for (long name : names) {
                if (monitor.name() == name) {
                    X.RR_DELETE_MONITOR.invokeExact(display, root, name);
                }
            }
        }
        int synced = (int) X.SYNC.invokeExact(display, 0);
    }

    /** The active monitors of the screen of {@code root}, in the server's order. */
    @SuppressWarnings("restricted")
    private static List<Monitor> monitors(MemorySegment display, long root) throws Throwable {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment count = local.allocate(JAVA_INT);
            MemorySegment list = (MemorySegment) X.RR_GET_MONITORS.invokeExact(display, root, 1, count);
            List<Monitor> monitors = new ArrayList<>();
            if (list.address() == 0) {
                return monitors;
            }
            try {
                long size = MONITOR_INFO.byteSize();
                MemorySegment all = list.reinterpret(count.get(JAVA_INT, 0) * size);
                for (long offset = 0; offset < all.byteSize(); offset += size) {
                    MemorySegment m = all.asSlice(offset, size);
                    monitors.add(new Monitor(m.get(JAVA_LONG, offset("name")),
                            m.get(JAVA_INT, offset("automatic")) != 0,
                            m.get(JAVA_INT, offset("x")), m.get(JAVA_INT, offset("y")),
                            m.get(JAVA_INT, offset("width")), m.get(JAVA_INT, offset("height")),
                            m.get(JAVA_INT, offset("mwidth")), m.get(JAVA_INT, offset("mheight")),
                            m.get(JAVA_INT, offset("noutput"))));
                }
            } finally {
                X.RR_FREE_MONITORS.invokeExact(list);
            }
            return monitors;
        }
    }

    /** The RandR outputs of the screen of {@code root}. */
    @SuppressWarnings("restricted")
    private static long[] outputs(MemorySegment display, long root) throws Throwable {
        MemorySegment resources = (MemorySegment) X.RR_GET_SCREEN_RESOURCES_CURRENT.invokeExact(display, root);
        if (resources.address() == 0) {
            return new long[0];
        }
        try {
            MemorySegment r = resources.reinterpret(SCREEN_RESOURCES.byteSize());
            int count = r.get(JAVA_INT, SCREEN_RESOURCES.byteOffset(PathElement.groupElement("noutput")));
            MemorySegment outputs = r.get(ADDRESS, SCREEN_RESOURCES.byteOffset(PathElement.groupElement("outputs")));
            return outputs.reinterpret(count * JAVA_LONG.byteSize()).toArray(JAVA_LONG);
        } finally {
            X.RR_FREE_SCREEN_RESOURCES.invokeExact(resources);
        }
    }

    /**
     * Makes the monitor {@code name} at {@code geometry} ({@code x, y, width, height}) with the physical size
     * {@code widthMm} x {@code heightMm}, not primary, with the output {@code output}, or with none where it is 0.
     */
    private static void setMonitor(MemorySegment display, long root, long name, int[] geometry, int widthMm,
                                   int heightMm, long output) throws Throwable {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment info = local.allocate(MONITOR_INFO);
            info.set(JAVA_LONG, offset("name"), name);
            info.set(JAVA_INT, offset("noutput"), output == 0 ? 0 : 1);
            info.set(JAVA_INT, offset("x"), geometry[0]);
            info.set(JAVA_INT, offset("y"), geometry[1]);
            info.set(JAVA_INT, offset("width"), geometry[2]);
            info.set(JAVA_INT, offset("height"), geometry[3]);
            info.set(JAVA_INT, offset("mwidth"), widthMm);
            info.set(JAVA_INT, offset("mheight"), heightMm);
            info.set(ADDRESS, offset("outputs"), output == 0 ? MemorySegment.NULL
                    : local.allocateFrom(JAVA_LONG, output));
            // Xlib copies the monitor into its request buffer, so the segment may go before the request is sent
            X.RR_SET_MONITOR.invokeExact(display, root, info);
            int synced = (int) X.SYNC.invokeExact(display, 0);
        }
    }

    private static long offset(String field) {
        return MONITOR_INFO.byteOffset(PathElement.groupElement(field));
    }

    private static long atom(MemorySegment display, Arena arena, String name) throws Throwable {
        return (long) X.INTERN_ATOM.invokeExact(display, arena.allocateFrom(name), 0);
    }

    /** The Xlib and libXrandr functions it calls; bound on first use. */
    private static final class X {

        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup X11 = library("libX11.so.6");
        private static final SymbolLookup XRANDR = library("libXrandr.so.2");

        /** {@code Display *XOpenDisplay(const char *)}. */
        static final MethodHandle OPEN_DISPLAY = bind(X11, "XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CLOSE_DISPLAY = bind(X11, "XCloseDisplay",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /** {@code Window XDefaultRootWindow(Display *)}. */
        static final MethodHandle DEFAULT_ROOT_WINDOW = bind(X11, "XDefaultRootWindow",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        /** {@code Atom XInternAtom(Display *, const char *, Bool only_if_exists)}. */
        static final MethodHandle INTERN_ATOM = bind(X11, "XInternAtom",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
        static final MethodHandle SYNC = bind(X11, "XSync", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        /** {@code Status XRRQueryVersion(Display *, int *major, int *minor)}. */
        static final MethodHandle RR_QUERY_VERSION = bind(XRANDR, "XRRQueryVersion",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        /** {@code XRRScreenResources *XRRGetScreenResourcesCurrent(Display *, Window)}. */
        static final MethodHandle RR_GET_SCREEN_RESOURCES_CURRENT = bind(XRANDR, "XRRGetScreenResourcesCurrent",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle RR_FREE_SCREEN_RESOURCES = bind(XRANDR, "XRRFreeScreenResources",
                FunctionDescriptor.ofVoid(ADDRESS));
        /** {@code XRRMonitorInfo *XRRGetMonitors(Display *, Window, Bool get_active, int *nmonitors)}. */
        static final MethodHandle RR_GET_MONITORS = bind(XRANDR, "XRRGetMonitors",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        static final MethodHandle RR_FREE_MONITORS = bind(XRANDR, "XRRFreeMonitors",
                FunctionDescriptor.ofVoid(ADDRESS));
        /** {@code void XRRSetMonitor(Display *, Window, XRRMonitorInfo *)}. */
        static final MethodHandle RR_SET_MONITOR = bind(XRANDR, "XRRSetMonitor",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        /** {@code void XRRDeleteMonitor(Display *, Window, Atom name)}. */
        static final MethodHandle RR_DELETE_MONITOR = bind(XRANDR, "XRRDeleteMonitor",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG));

        @SuppressWarnings("restricted")
        private static SymbolLookup library(String name) {
            return SymbolLookup.libraryLookup(name, Arena.global());
        }

        @SuppressWarnings("restricted")
        private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(library.find(name)
                    .orElseThrow(() -> new UnsatisfiedLinkError("no " + name + " to bind")), descriptor);
        }

        private X() {
        }
    }
}
