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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A reparenting window manager of the smallest kind, which a scenario runs on an X server that has none, on a
 * connection of its own, and drives step by step, so that it can hold a window's frame where a real window manager
 * holds it only for a moment. It is a stand-in for openbox's order of work when it manages a window: it answers
 * {@code _NET_REQUEST_FRAME_EXTENTS} by writing the extents on the window that is not mapped yet, applies the
 * window's {@code ConfigureRequest}s, and on its {@code MapRequest} creates a frame at a provisional position,
 * reparents the window into it at (left, top) and writes the extents; only when the scenario says so does it put
 * the frame where the window asked to be (NorthWest gravity, ICCCM 4.1.2.3), or where the scenario says, and map
 * frame and window.
 * <ul>
 * <li>It selects {@code SubstructureRedirect} on the root window, as every window manager does, and publishes itself
 * as EWMH asks ({@code _NET_SUPPORTING_WM_CHECK}, {@code _NET_WM_NAME}, {@code _NET_SUPPORTED} with
 * {@code _NET_FRAME_EXTENTS} and {@code _NET_REQUEST_FRAME_EXTENTS}). Started before the toolkit, GDK sees it as
 * the window manager and takes a window's frame origin from {@code _NET_FRAME_EXTENTS}, as under openbox.</li>
 * <li>A {@code ConfigureRequest} is applied as asked and recorded ({@link #requests}), which tells a test what glass
 * asked the window manager for: a window not framed yet is configured as it asked; a framed one has its size given to
 * it and to the frame, and a position moves the frame, or, before the frame is placed, becomes where it will be
 * placed.</li>
 * <li>It sends a window no synthetic {@code ConfigureNotify} when it places the frame, wherever it places it.
 * openbox sends none when the window's root position is the one the last real {@code ConfigureNotify} gave; when it
 * places the frame elsewhere it sends one ({@code client_configure}, called from {@code client_apply_startup_state},
 * sends it when the root position moved; client.c of danakj/openbox 3a2fbc6518), but before it maps the window, so
 * glass handles it while the window is not mapped and keeps the position the window asked for. Either way, what glass
 * learnt from a configure it handled before the frame was placed stays until it reads the position again at the
 * window's map, and sending no {@code ConfigureNotify} at all plays the case that only that read can learn.</li>
 * </ul>
 * Nothing runs by itself: the scenario's own thread calls {@link #awaitMapRequest}, {@link #serviceUntilQuiet} and
 * {@link #place}, and a request waits in the X server until then. Its X errors go to the process's Xlib error
 * handler, which before the toolkit starts is Xlib's own and ends the process - selecting {@code SubstructureRedirect}
 * where another window manager runs is one - so a scenario starts it only where {@link #runningWindowManager} finds
 * none. LP64 only, like the rest of the GTK glass binding. Scenarios only; not a production class.
 */
public final class GtkScriptedWindowManager implements AutoCloseable {

    /** The {@code value_mask} bits of a {@code ConfigureRequest} ({@code X.h}). */
    public static final int CW_X = 1;
    public static final int CW_Y = 1 << 1;
    public static final int CW_WIDTH = 1 << 2;
    public static final int CW_HEIGHT = 1 << 3;
    public static final int CW_BORDER_WIDTH = 1 << 4;

    /** Event types of {@code X.h}. */
    private static final int DESTROY_NOTIFY = 17;
    private static final int MAP_REQUEST = 20;
    private static final int CONFIGURE_REQUEST = 23;
    private static final int CLIENT_MESSAGE = 33;

    /** {@code SubstructureNotifyMask} and {@code SubstructureRedirectMask} of {@code X.h}. */
    private static final long SUBSTRUCTURE_MASKS = (1L << 19) | (1L << 20);

    /** {@code XA_ATOM}, {@code XA_CARDINAL} and {@code XA_WINDOW} of {@code Xatom.h}. */
    private static final long XA_ATOM = 4;
    private static final long XA_CARDINAL = 6;
    private static final long XA_WINDOW = 33;

    /** {@code PropModeReplace} of {@code X.h}. */
    private static final int PROP_MODE_REPLACE = 0;

    /** {@code sizeof(XEvent)} on LP64: the union is padded to 24 {@code long}s. */
    private static final long X_EVENT_SIZE = 192;

    /*
     * Offsets in an XEvent on LP64. Every event: int type @0, unsigned long serial @8, Bool send_event @16,
     * Display *display @24. XMapRequestEvent and XConfigureRequestEvent: Window parent @32, Window window @40;
     * XConfigureRequestEvent then int x @48, y @52, width @56, height @60, border_width @64, Window above @72,
     * int detail @80, unsigned long value_mask @88. XClientMessageEvent: Window window @32, Atom message_type @40.
     * XDestroyWindowEvent: Window event @32, Window window @40.
     */
    private static final long WINDOW_OFFSET = 40;
    private static final long X_OFFSET = 48;
    private static final long Y_OFFSET = 52;
    private static final long WIDTH_OFFSET = 56;
    private static final long HEIGHT_OFFSET = 60;
    private static final long BORDER_WIDTH_OFFSET = 64;
    private static final long VALUE_MASK_OFFSET = 88;
    private static final long CLIENT_MESSAGE_WINDOW_OFFSET = 32;
    private static final long MESSAGE_TYPE_OFFSET = 40;

    /** {@code XWindowChanges} on LP64: int x, y, width, height, border_width, Window sibling @24, int stack_mode. */
    private static final long WINDOW_CHANGES_SIZE = 40;

    /** How many rounds {@link #serviceUntilQuiet} takes at most. */
    private static final int MAX_ROUNDS = 100;

    /** One {@code ConfigureRequest} of a window, as the window manager received it. */
    public record Request(long window, int mask, int x, int y, int width, int height, boolean framed) {

        /**
         * The fields it asks for, {@code x=.. y=.. width=.. height=..} or its {@code value_mask} where it asks for none
         * of them (a restack), and whether the window had a frame.
         */
        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            if ((mask & CW_X) != 0) {
                parts.add("x=" + x);
            }
            if ((mask & CW_Y) != 0) {
                parts.add("y=" + y);
            }
            if ((mask & CW_WIDTH) != 0) {
                parts.add("width=" + width);
            }
            if ((mask & CW_HEIGHT) != 0) {
                parts.add("height=" + height);
            }
            if (parts.isEmpty()) {
                parts.add("value_mask=0x" + Integer.toHexString(mask));
            }
            return String.join(" ", parts) + (framed ? " (framed)" : " (not framed)");
        }
    }

    /** A window the window manager has framed. */
    private static final class Frame {
        final long frame;
        final int left;
        final int right;
        final int top;
        final int bottom;
        int x;
        int y;
        int width;
        int height;
        boolean placed;

        Frame(long frame, int[] extents, int x, int y, int width, int height) {
            this.frame = frame;
            this.left = extents[0];
            this.right = extents[1];
            this.top = extents[2];
            this.bottom = extents[3];
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private final Arena arena = Arena.ofConfined();
    private final MemorySegment display;
    private final long root;
    private final long checkWindow;
    private final MemorySegment event;
    private final Map<Long, Frame> frames = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private final long requestFrameExtents;
    private final long frameExtentsAtom;
    private int[] nextExtents = new int[4];
    private int provisionalX;
    private int provisionalY;
    private long lastMapped;

    private GtkScriptedWindowManager(MemorySegment display, String name) throws Throwable {
        this.display = display;
        this.root = (long) X.DEFAULT_ROOT_WINDOW.invokeExact(display);
        this.event = arena.allocate(X_EVENT_SIZE, 8);
        this.requestFrameExtents = atom("_NET_REQUEST_FRAME_EXTENTS");
        this.frameExtentsAtom = atom("_NET_FRAME_EXTENTS");
        int selected = (int) X.SELECT_INPUT.invokeExact(display, root, SUBSTRUCTURE_MASKS);
        this.checkWindow = (long) X.CREATE_SIMPLE_WINDOW.invokeExact(display, root, -10, -10, 1, 1, 0, 0L, 0L);
        MemorySegment check = arena.allocateFrom(JAVA_LONG, checkWindow);
        long supportingCheck = atom("_NET_SUPPORTING_WM_CHECK");
        changeProperty(root, supportingCheck, XA_WINDOW, check, 1);
        changeProperty(checkWindow, supportingCheck, XA_WINDOW, check, 1);
        // UTF-8 without the terminating NUL that allocateFrom adds
        MemorySegment text = arena.allocateFrom(name);
        int status = (int) X.CHANGE_PROPERTY.invokeExact(display, checkWindow, atom("_NET_WM_NAME"),
                atom("UTF8_STRING"), 8, PROP_MODE_REPLACE, text, (int) text.byteSize() - 1);
        long[] supported = {atom("_NET_SUPPORTED"), supportingCheck, atom("_NET_WM_NAME"), frameExtentsAtom,
            requestFrameExtents};
        changeProperty(root, atom("_NET_SUPPORTED"), XA_ATOM, arena.allocateFrom(JAVA_LONG, supported),
                supported.length);
        sync();
    }

    /**
     * Starts the window manager on a connection of its own to the X server of {@code DISPLAY}, published under
     * {@code name}. Called where {@link #runningWindowManager} answered {@code null}: another window manager holds
     * {@code SubstructureRedirect} on the root window, and asking for it as well is an X error.
     */
    public static GtkScriptedWindowManager start(String name) {
        try {
            MemorySegment display = (MemorySegment) X.OPEN_DISPLAY.invokeExact(MemorySegment.NULL);
            if (display.address() == 0) {
                throw new IllegalStateException("XOpenDisplay(NULL) failed: no X server at DISPLAY");
            }
            return new GtkScriptedWindowManager(display, name);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * The window an EWMH window manager names in the root window's {@code _NET_SUPPORTING_WM_CHECK} on the X server of
     * {@code DISPLAY}, as {@code 0x...}, or {@code null} where there is none: read on a connection of its own, closed
     * again.
     */
    @SuppressWarnings("restricted")
    public static String runningWindowManager() {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment display = (MemorySegment) X.OPEN_DISPLAY.invokeExact(MemorySegment.NULL);
            if (display.address() == 0) {
                throw new IllegalStateException("XOpenDisplay(NULL) failed: no X server at DISPLAY");
            }
            try {
                long root = (long) X.DEFAULT_ROOT_WINDOW.invokeExact(display);
                long property = (long) X.INTERN_ATOM.invokeExact(display,
                        local.allocateFrom("_NET_SUPPORTING_WM_CHECK"), 0);
                MemorySegment type = local.allocate(JAVA_LONG);
                MemorySegment format = local.allocate(JAVA_INT);
                MemorySegment count = local.allocate(JAVA_LONG);
                MemorySegment after = local.allocate(JAVA_LONG);
                MemorySegment data = local.allocate(ADDRESS);
                int status = (int) X.GET_WINDOW_PROPERTY.invokeExact(display, root, property, 0L, 1L, 0, XA_WINDOW,
                        type, format, count, after, data);
                MemorySegment items = data.get(ADDRESS, 0);
                String answer = null;
                if (status == 0 && items.address() != 0 && count.get(JAVA_LONG, 0) > 0) {
                    answer = "0x" + Long.toHexString(items.reinterpret(JAVA_LONG.byteSize()).get(JAVA_LONG, 0));
                }
                if (items.address() != 0) {
                    int freed = (int) X.FREE.invokeExact(items);
                }
                return answer;
            } finally {
                int closed = (int) X.CLOSE_DISPLAY.invokeExact(display);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * The frame extents (left, right, top, bottom) of the next window the window manager frames, which it also
     * writes in answer to that window's {@code _NET_REQUEST_FRAME_EXTENTS}, and where its frame is created before it
     * is placed.
     */
    public void frameNext(int left, int right, int top, int bottom, int x, int y) {
        nextExtents = new int[] {left, right, top, bottom};
        provisionalX = x;
        provisionalY = y;
    }

    /**
     * Handles every request until one window's {@code MapRequest} has been handled - the window framed at the
     * provisional position, reparented and given its extents, with the X server done with all of it - or
     * {@code timeoutMillis} has passed. Answers that window, or 0.
     */
    public long awaitMapRequest(long timeoutMillis) throws InterruptedException {
        long mappedBefore = lastMapped;
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (lastMapped == mappedBefore) {
            if (drain() == 0) {
                if (System.nanoTime() > deadline) {
                    return 0;
                }
                Thread.sleep(5);
            }
        }
        sync();
        return lastMapped;
    }

    /**
     * Handles requests until there are none left: each round handles what is pending, waits for the X server and
     * runs {@code roundTrip} - which is to let the client handle every event so far, and send what that made it ask
     * for - and the window manager is done after two rounds in a row with nothing to handle.
     */
    public void serviceUntilQuiet(Runnable roundTrip) {
        int quiet = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int handled = drain();
            sync();
            roundTrip.run();
            handled += drain();
            quiet = handled == 0 ? quiet + 1 : 0;
            if (quiet >= 2) {
                return;
            }
        }
        throw new IllegalStateException("requests kept coming for " + MAX_ROUNDS + " rounds: " + requests);
    }

    /**
     * Puts the frame of {@code window} where the window asked to be - its position when it asked to be mapped, or
     * the last position it asked for since - and maps frame and window, the window last; then waits for the X
     * server. No synthetic {@code ConfigureNotify} follows.
     */
    public void place(long window) {
        Frame f = frame(window);
        place(window, f.x, f.y);
    }

    /**
     * Puts the frame of {@code window} at {@code x, y}, wherever the window asked to be, as a window manager does that
     * places a window by a policy of its own or moves it on screen before it maps it, and maps frame and window, the
     * window last; then waits for the X server. No synthetic {@code ConfigureNotify} follows, so the window learns
     * where it is only by reading its position.
     */
    public void place(long window, int x, int y) {
        Frame f = frame(window);
        f.x = x;
        f.y = y;
        try {
            int moved = (int) X.MOVE_WINDOW.invokeExact(display, f.frame, f.x, f.y);
            f.placed = true;
            int frameMapped = (int) X.MAP_WINDOW.invokeExact(display, f.frame);
            int windowMapped = (int) X.MAP_WINDOW.invokeExact(display, window);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        sync();
    }

    /** Every {@code ConfigureRequest} received so far, in order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    private Frame frame(long window) {
        Frame f = frames.get(window);
        if (f == null) {
            throw new IllegalStateException("window 0x" + Long.toHexString(window) + " has no frame");
        }
        return f;
    }

    /**
     * Withdraws the window manager: deletes {@code _NET_SUPPORTING_WM_CHECK} and {@code _NET_SUPPORTED} from the root
     * window and closes its connection, which destroys its frames; the windows in them go back to the root window
     * (the save-set, {@code XAddToSaveSet}).
     */
    @Override
    public void close() {
        try {
            int deleted = (int) X.DELETE_PROPERTY.invokeExact(display, root, atom("_NET_SUPPORTING_WM_CHECK"));
            deleted = (int) X.DELETE_PROPERTY.invokeExact(display, root, atom("_NET_SUPPORTED"));
            sync();
            int closed = (int) X.CLOSE_DISPLAY.invokeExact(display);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            arena.close();
        }
    }

    /** Handles every event pending on the connection; answers how many asked the window manager to act. */
    private int drain() {
        int handled = 0;
        try {
            while ((int) X.PENDING.invokeExact(display) > 0) {
                int status = (int) X.NEXT_EVENT.invokeExact(display, event);
                if (handle()) {
                    handled++;
                }
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return handled;
    }

    private boolean handle() throws Throwable {
        switch (event.get(JAVA_INT, 0)) {
            case CONFIGURE_REQUEST -> configure();
            case MAP_REQUEST -> manage(event.get(JAVA_LONG, WINDOW_OFFSET));
            case CLIENT_MESSAGE -> {
                if (event.get(JAVA_LONG, MESSAGE_TYPE_OFFSET) != requestFrameExtents) {
                    return false;
                }
                writeExtents(event.get(JAVA_LONG, CLIENT_MESSAGE_WINDOW_OFFSET), nextExtents);
            }
            case DESTROY_NOTIFY -> {
                Frame f = frames.remove(event.get(JAVA_LONG, WINDOW_OFFSET));
                if (f == null) {
                    return false;
                }
                int destroyed = (int) X.DESTROY_WINDOW.invokeExact(display, f.frame);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A {@code ConfigureRequest}: recorded, then applied as asked. */
    private void configure() throws Throwable {
        long window = event.get(JAVA_LONG, WINDOW_OFFSET);
        int mask = (int) event.get(JAVA_LONG, VALUE_MASK_OFFSET);
        int x = event.get(JAVA_INT, X_OFFSET);
        int y = event.get(JAVA_INT, Y_OFFSET);
        int width = event.get(JAVA_INT, WIDTH_OFFSET);
        int height = event.get(JAVA_INT, HEIGHT_OFFSET);
        Frame f = frames.get(window);
        requests.add(new Request(window, mask, x, y, width, height, f != null));
        if (f == null) {
            MemorySegment changes = arena.allocate(WINDOW_CHANGES_SIZE, 8);
            changes.set(JAVA_INT, 0, x);
            changes.set(JAVA_INT, 4, y);
            changes.set(JAVA_INT, 8, width);
            changes.set(JAVA_INT, 12, height);
            changes.set(JAVA_INT, 16, event.get(JAVA_INT, BORDER_WIDTH_OFFSET));
            int status = (int) X.CONFIGURE_WINDOW.invokeExact(display, window,
                    mask & (CW_X | CW_Y | CW_WIDTH | CW_HEIGHT | CW_BORDER_WIDTH), changes);
            return;
        }
        if ((mask & (CW_X | CW_Y)) != 0) {
            f.x = (mask & CW_X) != 0 ? x : f.x;
            f.y = (mask & CW_Y) != 0 ? y : f.y;
            if (f.placed) {
                int moved = (int) X.MOVE_WINDOW.invokeExact(display, f.frame, f.x, f.y);
            }
        }
        if ((mask & (CW_WIDTH | CW_HEIGHT)) != 0) {
            f.width = (mask & CW_WIDTH) != 0 ? width : f.width;
            f.height = (mask & CW_HEIGHT) != 0 ? height : f.height;
            int resized = (int) X.RESIZE_WINDOW.invokeExact(display, window, f.width, f.height);
            resized = (int) X.RESIZE_WINDOW.invokeExact(display, f.frame, f.width + f.left + f.right,
                    f.height + f.top + f.bottom);
        }
    }

    /**
     * A {@code MapRequest}: a frame around the window at the provisional position, the window reparented into it at
     * (left, top) and kept in the save-set, the extents written on it; neither is mapped yet.
     */
    private void manage(long window) throws Throwable {
        if (frames.containsKey(window)) {
            return;
        }
        int[] extents = nextExtents;
        int[] geometry = geometry(window);
        long frame = (long) X.CREATE_SIMPLE_WINDOW.invokeExact(display, root, provisionalX, provisionalY,
                geometry[2] + extents[0] + extents[1], geometry[3] + extents[2] + extents[3], 0, 0L, 0L);
        int selected = (int) X.SELECT_INPUT.invokeExact(display, frame, SUBSTRUCTURE_MASKS);
        int saved = (int) X.ADD_TO_SAVE_SET.invokeExact(display, window);
        int reparented = (int) X.REPARENT_WINDOW.invokeExact(display, window, frame, extents[0], extents[2]);
        writeExtents(window, extents);
        frames.put(window, new Frame(frame, extents, geometry[0], geometry[1], geometry[2], geometry[3]));
        lastMapped = window;
    }

    private void writeExtents(long window, int[] extents) throws Throwable {
        MemorySegment data = arena.allocateFrom(JAVA_LONG, extents[0], extents[1], extents[2], extents[3]);
        changeProperty(window, frameExtentsAtom, XA_CARDINAL, data, 4);
    }

    /** {@code XGetGeometry}: {@code {x, y, width, height}} of {@code window} in its parent. */
    private int[] geometry(long window) throws Throwable {
        MemorySegment rootReturn = arena.allocate(JAVA_LONG);
        MemorySegment values = arena.allocate(JAVA_INT, 6);
        int ok = (int) X.GET_GEOMETRY.invokeExact(display, window, rootReturn, values,
                values.asSlice(4), values.asSlice(8), values.asSlice(12), values.asSlice(16), values.asSlice(20));
        if (ok == 0) {
            throw new IllegalStateException("XGetGeometry failed for window 0x" + Long.toHexString(window));
        }
        return new int[] {values.getAtIndex(JAVA_INT, 0), values.getAtIndex(JAVA_INT, 1),
            values.getAtIndex(JAVA_INT, 2), values.getAtIndex(JAVA_INT, 3)};
    }

    private void changeProperty(long window, long property, long type, MemorySegment data, int count)
            throws Throwable {
        int status = (int) X.CHANGE_PROPERTY.invokeExact(display, window, property, type, 32, PROP_MODE_REPLACE,
                data, count);
    }

    private long atom(String name) {
        try {
            return (long) X.INTERN_ATOM.invokeExact(display, arena.allocateFrom(name), 0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void sync() {
        try {
            int synced = (int) X.SYNC.invokeExact(display, 0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The Xlib functions the window manager calls; bound on first use. */
    private static final class X {

        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup X11 = library("libX11.so.6");

        /** {@code Display *XOpenDisplay(const char *)}. */
        static final MethodHandle OPEN_DISPLAY = bind("XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CLOSE_DISPLAY = bind("XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /** {@code Window XDefaultRootWindow(Display *)}. */
        static final MethodHandle DEFAULT_ROOT_WINDOW = bind("XDefaultRootWindow",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        /** {@code int XSelectInput(Display *, Window, long event_mask)}. */
        static final MethodHandle SELECT_INPUT = bind("XSelectInput",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
        /** {@code Atom XInternAtom(Display *, const char *, Bool only_if_exists)}. */
        static final MethodHandle INTERN_ATOM = bind("XInternAtom",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
        /**
         * {@code Window XCreateSimpleWindow(Display *, Window parent, int x, int y, unsigned int width,
         * unsigned int height, unsigned int border_width, unsigned long border, unsigned long background)}.
         */
        static final MethodHandle CREATE_SIMPLE_WINDOW = bind("XCreateSimpleWindow",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_LONG, JAVA_LONG));
        static final MethodHandle DESTROY_WINDOW = bind("XDestroyWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        /** {@code int XReparentWindow(Display *, Window, Window parent, int x, int y)}. */
        static final MethodHandle REPARENT_WINDOW = bind("XReparentWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));
        static final MethodHandle ADD_TO_SAVE_SET = bind("XAddToSaveSet",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        static final MethodHandle MAP_WINDOW = bind("XMapWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        /** {@code int XMoveWindow(Display *, Window, int x, int y)}. */
        static final MethodHandle MOVE_WINDOW = bind("XMoveWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT));
        /** {@code int XResizeWindow(Display *, Window, unsigned int width, unsigned int height)}. */
        static final MethodHandle RESIZE_WINDOW = bind("XResizeWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT));
        /** {@code int XConfigureWindow(Display *, Window, unsigned int value_mask, XWindowChanges *)}. */
        static final MethodHandle CONFIGURE_WINDOW = bind("XConfigureWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        /**
         * {@code int XChangeProperty(Display *, Window, Atom property, Atom type, int format, int mode,
         * const unsigned char *data, int nelements)}.
         */
        static final MethodHandle CHANGE_PROPERTY = bind("XChangeProperty",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT,
                        ADDRESS, JAVA_INT));
        static final MethodHandle DELETE_PROPERTY = bind("XDeleteProperty",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
        /**
         * {@code int XGetWindowProperty(Display *, Window, Atom property, long offset, long length, Bool delete,
         * Atom req_type, Atom *type, int *format, unsigned long *nitems, unsigned long *bytes_after,
         * unsigned char **prop)}.
         */
        static final MethodHandle GET_WINDOW_PROPERTY = bind("XGetWindowProperty",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT,
                        JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        /**
         * {@code Status XGetGeometry(Display *, Drawable, Window *root, int *x, int *y, unsigned int *width,
         * unsigned int *height, unsigned int *border_width, unsigned int *depth)}.
         */
        static final MethodHandle GET_GEOMETRY = bind("XGetGeometry",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                        ADDRESS, ADDRESS));
        static final MethodHandle FREE = bind("XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle PENDING = bind("XPending", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /** {@code int XNextEvent(Display *, XEvent *)}. */
        static final MethodHandle NEXT_EVENT = bind("XNextEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle SYNC = bind("XSync", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

        @SuppressWarnings("restricted")
        private static SymbolLookup library(String name) {
            return SymbolLookup.libraryLookup(name, Arena.global());
        }

        @SuppressWarnings("restricted")
        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(X11.find(name)
                    .orElseThrow(() -> new UnsatisfiedLinkError("libX11.so.6 does not export " + name)), descriptor);
        }

        private X() {
        }
    }
}
