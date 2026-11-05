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
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_GESTURE_INSTALL_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OFFSETOF_VIEW_CAPS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_GESTURE_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_VIEW_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_VIEW_CAPS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_VIEW_INSTALL_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code GmacViewCallbacks} and {@code GmacGestureCallbacks}: the upcalls of {@code MacView}'s native side, on the
 * AppKit main thread, for the views {@code gmac_view_create} made. A view crosses as the id {@link #register} gave it,
 * which names its {@link Receiver}, as the JNI's global reference named the {@code MacView}, until
 * {@code notify_dispose}, after which no slot runs for the id. An id the registry does not hold runs nothing and
 * writes no out-parameter.
 * <p>
 * No Java exception may escape a slot. The JNI checked for one after each call but the Caps Lock pair of
 * {@code notify_key}, which it left pending for the check after the pair; each target reports what it throws at once
 * through {@code Application.reportException} and answers {@code GMAC_UPCALL_THREW}, which the C does not read. A slot
 * writes its out-parameters only when its call returned normally: the C has set them to what the JNI read after an
 * exception.
 */
final class MacViewCallbacks {

    /** The slots of {@code GmacViewCallbacks} in table order: the index of a name is its slot number. */
    static final List<String> VIEW_SLOT_NAMES = List.of("notify_view", "notify_resize", "notify_repaint",
            "notify_menu", "notify_mouse", "notify_key", "notify_input_method",
            "notify_input_method_candidate_pos_request", "notify_drag_enter", "notify_drag_over", "notify_drag_drop",
            "notify_drag_leave", "notify_drag_end", "get_accessible", "notify_dispose");

    /** The slots of {@code GmacGestureCallbacks} in table order. */
    static final List<String> GESTURE_SLOT_NAMES = List.of("rotate_gesture_performed", "scroll_gesture_performed",
            "swipe_gesture_performed", "magnify_gesture_performed", "gesture_finished");

    static final StructLayout GMAC_VIEW_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            VIEW_SLOT_NAMES.stream().map(ADDRESS::withName).toArray(MemoryLayout[]::new));

    static final StructLayout GMAC_GESTURE_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            GESTURE_SLOT_NAMES.stream().map(ADDRESS::withName).toArray(MemoryLayout[]::new));

    /**
     * Where the slots of one view go: the {@code MacView} methods the JNI called on it, in slot order, then the static
     * methods of {@code MacGestureSupport} it called with the view, without the view.
     */
    interface Receiver {

        void notifyView(int type);

        void notifyResize(int width, int height);

        void notifyRepaint(int x, int y, int width, int height);

        void notifyMenu(int x, int y, int xAbs, int yAbs, boolean isKeyboardTrigger);

        void notifyMouse(int type, int button, int x, int y, int xAbs, int yAbs, int modifiers,
                         boolean isPopupTrigger, boolean isSynthesized);

        boolean notifyKeyAndReturnConsumed(int type, int keyCode, char[] keyChars, int modifiers);

        void notifyInputMethodMac(String text, int attr, int length, int cursor, int selStart, int selLength);

        double[] notifyInputMethodCandidatePosRequest(int pos);

        int notifyDragEnter(int x, int y, int xAbs, int yAbs, int recommendedAction);

        int notifyDragOver(int x, int y, int xAbs, int yAbs, int recommendedAction);

        int notifyDragDrop(int x, int y, int xAbs, int yAbs, int recommendedAction);

        void notifyDragLeave();

        void notifyDragEnd(int performedAction);

        long getAccessible();

        void rotateGesturePerformed(int modifiers, int x, int y, int xAbs, int yAbs, float rotation)
                throws Throwable;

        void scrollGesturePerformed(int modifiers, int sender, int x, int y, int xAbs, int yAbs, float dx, float dy)
                throws Throwable;

        void swipeGesturePerformed(int modifiers, int dir, int x, int y, int xAbs, int yAbs) throws Throwable;

        void magnifyGesturePerformed(int modifiers, int x, int y, int xAbs, int yAbs, float scale) throws Throwable;

        void gestureFinished(int modifiers, int x, int y, int xAbs, int yAbs) throws Throwable;
    }

    private static final Map<Long, Receiver> VIEWS = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_VIEW_ID = new AtomicLong(1);

    /* Main thread only, as notify_key is: the char[] its last dial passed. */
    private static char[] lastKeyChars;

    private static final MemorySegment[] VIEW_STUBS = createViewStubs();

    private static final MemorySegment[] GESTURE_STUBS = createGestureStubs();

    private MacViewCallbacks() {
    }

    /**
     * Once, from {@code MacView}'s class initialization, before the first {@code gmac_view_create}: checks the
     * layouts of the tables and of {@code GmacViewCaps}, then installs both tables. The library copies them, so
     * their memory may go once the calls return.
     */
    static void install() {
        MacAppCallbacks.requireSize("GmacViewCallbacks", GMAC_VIEW_CALLBACKS_LAYOUT, probe(GMAC_SIZEOF_VIEW_CALLBACKS));
        MacAppCallbacks.requireSize("GmacGestureCallbacks", GMAC_GESTURE_CALLBACKS_LAYOUT,
                probe(GMAC_SIZEOF_GESTURE_CALLBACKS));
        MacAppCallbacks.requireLayout("GmacViewCaps", MacViewCalls.GMAC_VIEW_CAPS_LAYOUT, GMAC_SIZEOF_VIEW_CAPS,
                GMAC_OFFSETOF_VIEW_CAPS);
        installTable("gmac_view_install_callbacks", GMAC_VIEW_INSTALL_CALLBACKS, GMAC_VIEW_CALLBACKS_LAYOUT,
                VIEW_STUBS);
        installTable("gmac_gesture_install_callbacks", GMAC_GESTURE_INSTALL_CALLBACKS,
                GMAC_GESTURE_CALLBACKS_LAYOUT, GESTURE_STUBS);
    }

    /** The upcall stub of a slot of {@code GmacViewCallbacks}. */
    static MemorySegment viewStub(int slot) {
        return VIEW_STUBS[slot];
    }

    /** The upcall stub of a slot of {@code GmacGestureCallbacks}. */
    static MemorySegment gestureStub(int slot) {
        return GESTURE_STUBS[slot];
    }

    /** A new nonzero id naming {@code view}: before {@code gmac_view_create}, which dials slots for it. */
    static long register(Receiver view) {
        long viewId = NEXT_VIEW_ID.getAndIncrement();
        VIEWS.put(viewId, view);
        return viewId;
    }

    /** Removes the id of a view {@code gmac_view_create} did not make, which no {@code notify_dispose} will remove. */
    static void unregister(long viewId) {
        VIEWS.remove(viewId);
    }

    static boolean isRegistered(long viewId) {
        return VIEWS.containsKey(viewId);
    }

    private static int notifyView(long viewId, int type) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.notifyView(type);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyResize(long viewId, int width, int height) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.notifyResize(width, height);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyRepaint(long viewId, int x, int y, int width, int height) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.notifyRepaint(x, y, width, height);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyMenu(long viewId, int x, int y, int xAbs, int yAbs, int isKeyboardTrigger) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.notifyMenu(x, y, xAbs, yAbs, isKeyboardTrigger != 0);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyMouse(long viewId, int type, int button, int x, int y, int xAbs, int yAbs,
                                   int modifiers, int isPopupTrigger, int isSynthesized) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.notifyMouse(type, button, x, y, xAbs, yAbs, modifiers, isPopupTrigger != 0, isSynthesized != 0);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /**
     * The JNI passed every dial of one key event the same {@code char[]}, so a dial that the C marks
     * {@code same_chars_as_previous} gets the array of the dial before it.
     */
    private static int notifyKey(long viewId, int type, int keyCode, MemorySegment chars, int charCount,
                                 int modifiers, int sameCharsAsPrevious, MemorySegment outConsumed) {
        try {
            char[] keyChars = sameCharsAsPrevious != 0
                    ? lastKeyChars
                    : bounded(chars, JAVA_CHAR.byteSize() * charCount).toArray(JAVA_CHAR);
            lastKeyChars = keyChars;
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                boolean consumed = view.notifyKeyAndReturnConsumed(type, keyCode, keyChars, modifiers);
                bounded(outConsumed, JAVA_INT.byteSize()).set(JAVA_INT, 0, consumed ? 1 : 0);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyInputMethod(long viewId, MemorySegment text, int textLength, int attr, int length,
                                         int cursor, int selStart, int selLength) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                String string = textLength == 0
                        ? ""
                        : new String(bounded(text, JAVA_CHAR.byteSize() * textLength).toArray(JAVA_CHAR));
                view.notifyInputMethodMac(string, attr, length, cursor, selStart, selLength);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** The JNI used what Java answered only when it was an array of 2. */
    private static int notifyInputMethodCandidatePosRequest(long viewId, int pos, MemorySegment outXy,
                                                            MemorySegment outValid) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                double[] xy = view.notifyInputMethodCandidatePosRequest(pos);
                if (xy != null && xy.length == 2) {
                    MemorySegment.copy(xy, 0, bounded(outXy, JAVA_DOUBLE.byteSize() * 2), JAVA_DOUBLE, 0, 2);
                    bounded(outValid, JAVA_INT.byteSize()).set(JAVA_INT, 0, 1);
                }
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyDragEnter(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction,
                                       MemorySegment outAction) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                int action = view.notifyDragEnter(x, y, xAbs, yAbs, recommendedAction);
                bounded(outAction, JAVA_INT.byteSize()).set(JAVA_INT, 0, action);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyDragOver(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction,
                                      MemorySegment outAction) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                int action = view.notifyDragOver(x, y, xAbs, yAbs, recommendedAction);
                bounded(outAction, JAVA_INT.byteSize()).set(JAVA_INT, 0, action);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyDragDrop(long viewId, int x, int y, int xAbs, int yAbs, int recommendedAction,
                                      MemorySegment outAction) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                int action = view.notifyDragDrop(x, y, xAbs, yAbs, recommendedAction);
                bounded(outAction, JAVA_INT.byteSize()).set(JAVA_INT, 0, action);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyDragLeave(long viewId) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.notifyDragLeave();
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int notifyDragEnd(long viewId, int performedAction) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.notifyDragEnd(performedAction);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** The {@code GlassAccessible*} belongs to {@code MacAccessible}; the C does not retain it. */
    private static int getAccessible(long viewId, MemorySegment outAccessible) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                long accessible = view.getAccessible();
                bounded(outAccessible, ADDRESS.byteSize()).set(ADDRESS, 0, MemorySegment.ofAddress(accessible));
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** Where the id of a view that was made leaves the registry: its {@code GlassViewDelegate} has been deallocated. */
    private static int notifyDispose(long viewId) {
        try {
            VIEWS.remove(viewId);
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int rotateGesturePerformed(long viewId, int modifiers, int x, int y, int xAbs, int yAbs,
                                              float rotation) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.rotateGesturePerformed(modifiers, x, y, xAbs, yAbs, rotation);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int scrollGesturePerformed(long viewId, int modifiers, int sender, int x, int y, int xAbs,
                                              int yAbs, float dx, float dy) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.scrollGesturePerformed(modifiers, sender, x, y, xAbs, yAbs, dx, dy);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int swipeGesturePerformed(long viewId, int modifiers, int dir, int x, int y, int xAbs,
                                             int yAbs) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.swipeGesturePerformed(modifiers, dir, x, y, xAbs, yAbs);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int magnifyGesturePerformed(long viewId, int modifiers, int x, int y, int xAbs, int yAbs,
                                               float scale) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.magnifyGesturePerformed(modifiers, x, y, xAbs, yAbs, scale);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static int gestureFinished(long viewId, int modifiers, int x, int y, int xAbs, int yAbs) {
        try {
            Receiver view = VIEWS.get(viewId);
            if (view != null) {
                view.gestureFinished(modifiers, x, y, xAbs, yAbs);
            }
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    private static void installTable(String installer, MethodHandle install, StructLayout layout,
                                     MemorySegment[] stubs) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(layout);
            for (int slot = 0; slot < stubs.length; slot++) {
                table.setAtIndex(ADDRESS, slot, stubs[slot]);
            }
            int status = (int) install.invokeExact(table);
            if (status != GMAC_OK) {
                throw new IllegalStateException(installer + " answered " + status);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int probe(MethodHandle sizeOf) {
        try {
            return (int) sizeOf.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment[] createViewStubs() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        FunctionDescriptor idOnly = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
        FunctionDescriptor drag = FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                JAVA_INT, ADDRESS);
        try {
            return new MemorySegment[] {
                stub(lookup, "notifyView", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT)),
                stub(lookup, "notifyResize", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT)),
                stub(lookup, "notifyRepaint",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)),
                stub(lookup, "notifyMenu",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)),
                stub(lookup, "notifyMouse", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)),
                stub(lookup, "notifyKey", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS,
                        JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)),
                stub(lookup, "notifyInputMethod", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)),
                stub(lookup, "notifyInputMethodCandidatePosRequest",
                        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS)),
                stub(lookup, "notifyDragEnter", drag),
                stub(lookup, "notifyDragOver", drag),
                stub(lookup, "notifyDragDrop", drag),
                stub(lookup, "notifyDragLeave", idOnly),
                stub(lookup, "notifyDragEnd", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT)),
                stub(lookup, "getAccessible", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                stub(lookup, "notifyDispose", idOnly)
            };
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment[] createGestureStubs() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            return new MemorySegment[] {
                stub(lookup, "rotateGesturePerformed", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT)),
                stub(lookup, "scrollGesturePerformed", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT)),
                stub(lookup, "swipeGesturePerformed", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)),
                stub(lookup, "magnifyGesturePerformed", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT)),
                stub(lookup, "gestureFinished", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, JAVA_INT))
            };
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment stub(MethodHandles.Lookup lookup, String name, FunctionDescriptor descriptor)
            throws ReflectiveOperationException {
        MethodHandle target = lookup.findStatic(MacViewCallbacks.class, name, descriptor.toMethodType());
        return upcallStub(target, descriptor);
    }
}
