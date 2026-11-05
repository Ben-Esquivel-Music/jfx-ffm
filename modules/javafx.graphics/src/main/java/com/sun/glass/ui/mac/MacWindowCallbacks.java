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

package com.sun.glass.ui.mac;

import com.sun.glass.ui.Screen;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_OK;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_THREW;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_WINDOW_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_WINDOW_INSTALL_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.LIBRARY_NAME;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code GmacWindowCallbacks}: the upcalls of {@code MacWindow}'s native side, on the AppKit main thread, for the
 * windows {@code gmac_window_create} made. A window crosses as the id {@link #register} gave it, which names its
 * {@link Receiver}, as the JNI's global reference named the {@code MacWindow}, until {@code notify_dispose}, after
 * which no slot runs for the id. An id the registry does not hold runs nothing.
 * <p>
 * No Java exception may escape a slot. The JNI left what slots 0-8 threw pending for a later
 * {@code GLASS_CHECK_EXCEPTION}; their targets report it at once through {@code Application.reportException}. Slot 9
 * stands where the JNI deleted its global reference and then ran {@code GLASS_CHECK_EXCEPTION}, and reports the same
 * way.
 * <p>
 * A slot answers {@code GMAC_UPCALL_THREW} when it raised, and reported, a new exception, else
 * {@code GMAC_UPCALL_OK}. The C that dials the slots ignores the answer, and neither takes, restores nor reports a
 * pending exception around any slot.
 */
final class MacWindowCallbacks {

    /** The slots in table order: the index of a name is its slot number. */
    static final List<String> SLOT_NAMES = List.of("notify_move", "notify_resize", "notify_move_to_another_screen",
            "notify_scale_changed", "notify_close", "notify_focus", "notify_focus_ungrab", "notify_focus_disabled",
            "notify_destroy", "notify_dispose");

    static final StructLayout GMAC_WINDOW_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            SLOT_NAMES.stream().map(ADDRESS::withName).toArray(MemoryLayout[]::new));

    /** Where the slots of one window go: the {@code MacWindow} methods the JNI called on it, in slot order. */
    interface Receiver {

        void notifyMove(int x, int y, boolean isZoomed);

        void notifyResize(int type, int width, int height);

        void notifyMoveToAnotherScreen(Screen screen);

        void notifyScaleChanged(float platformScaleX, float platformScaleY, float outputScaleX, float outputScaleY);

        void notifyClose();

        void notifyFocus(int event);

        void notifyFocusUngrab();

        void notifyFocusDisabled();

        void notifyDestroy();
    }

    private static final Map<Long, Receiver> WINDOWS = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_WINDOW_ID = new AtomicLong(1);

    private static final MemorySegment[] STUBS = createStubs();

    private MacWindowCallbacks() {
    }

    /**
     * Once, from {@code MacWindow}'s class initialization, before the first {@code gmac_window_create}. The library
     * copies the table, so its memory may go once the call returns.
     */
    static void install() {
        int size = sizeOfTable();
        if (size != GMAC_WINDOW_CALLBACKS_LAYOUT.byteSize()) {
            throw new UnsatisfiedLinkError(LIBRARY_NAME + " layout mismatch: sizeof GmacWindowCallbacks is " + size
                    + ", the layout has " + GMAC_WINDOW_CALLBACKS_LAYOUT.byteSize() + " bytes");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GMAC_WINDOW_CALLBACKS_LAYOUT);
            for (int slot = 0; slot < STUBS.length; slot++) {
                table.setAtIndex(ADDRESS, slot, STUBS[slot]);
            }
            int status = (int) GMAC_WINDOW_INSTALL_CALLBACKS.invokeExact(table);
            if (status != GMAC_OK) {
                throw new IllegalStateException("gmac_window_install_callbacks answered " + status);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The upcall stub of a slot. */
    static MemorySegment stub(int slot) {
        return STUBS[slot];
    }

    /** A new nonzero id naming {@code window}: before {@code gmac_window_create}, which dials slots for it. */
    static long register(Receiver window) {
        long windowId = NEXT_WINDOW_ID.getAndIncrement();
        WINDOWS.put(windowId, window);
        return windowId;
    }

    static boolean isRegistered(long windowId) {
        return WINDOWS.containsKey(windowId);
    }

    private static int notifyMove(long windowId, int x, int y, int isZoomed) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyMove(x, y, isZoomed != 0);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyResize(long windowId, int type, int width, int height) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyResize(type, width, height);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyMoveToAnotherScreen(long windowId, MemorySegment info) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyMoveToAnotherScreen(screenOrNull(info));
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** The JNI reported a {@code Screen} its constructor could not build, and passed null. */
    private static Screen screenOrNull(MemorySegment info) {
        try {
            return MacScreenCalls.toScreen(bounded(info, MacScreenCalls.GMAC_SCREEN_INFO_LAYOUT.byteSize()));
        } catch (Throwable t) {
            reportException(t);
            return null;
        }
    }

    private static int notifyScaleChanged(long windowId, float platformScaleX, float platformScaleY,
                                          float outputScaleX, float outputScaleY) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyScaleChanged(platformScaleX, platformScaleY, outputScaleX, outputScaleY);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyClose(long windowId) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyClose();
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyFocus(long windowId, int event) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyFocus(event);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyFocusUngrab(long windowId) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyFocusUngrab();
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyFocusDisabled(long windowId) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyFocusDisabled();
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyDestroy(long windowId) {
        try {
            Receiver window = WINDOWS.get(windowId);
            if (window != null) {
                window.notifyDestroy();
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** The only place the id leaves the registry: the window's {@code GlassWindow} has been deallocated. */
    private static int notifyDispose(long windowId) {
        try {
            WINDOWS.remove(windowId);
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int sizeOfTable() {
        try {
            return (int) GMAC_SIZEOF_WINDOW_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment[] createStubs() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FunctionDescriptor idOnly = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
        try {
            return new MemorySegment[] {
                stub(lookup, "notifyMove", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT)),
                stub(lookup, "notifyResize", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT)),
                stub(lookup, "notifyMoveToAnotherScreen", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                stub(lookup, "notifyScaleChanged",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT)),
                stub(lookup, "notifyClose", idOnly),
                stub(lookup, "notifyFocus", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT)),
                stub(lookup, "notifyFocusUngrab", idOnly),
                stub(lookup, "notifyFocusDisabled", idOnly),
                stub(lookup, "notifyDestroy", idOnly),
                stub(lookup, "notifyDispose", idOnly)
            };
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment stub(MethodHandles.Lookup lookup, String name, FunctionDescriptor descriptor)
            throws ReflectiveOperationException {
        MethodHandle target = lookup.findStatic(MacWindowCallbacks.class, name, descriptor.toMethodType());
        return upcallStub(target, descriptor);
    }
}
