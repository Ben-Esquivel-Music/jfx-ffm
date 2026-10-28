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

package com.sun.glass.ui.win;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static com.sun.glass.ui.win.WinCallSupport.fillCallbackTable;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;
import static com.sun.glass.ui.win.WinCallSupport.requireOk;
import static com.sun.glass.ui.win.WinCallSupport.stubsFor;
import static com.sun.glass.ui.win.WinCallSupport.utf16;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bounded;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The two {@link WinGlassNative} callback tables of {@link WinView}: {@code GwinViewCallbacks} and
 * {@code GwinGestureCallbacks}, their descriptors, their install and their fifteen upcall targets.
 * <p>
 * The 26 {@code Call*Method} sites of {@code GlassView.cpp}, {@code ViewContainer.cpp} and
 * {@code FullScreenWindow.cpp} - every mouse move, key, wheel notch, resize, repaint, IME step, touch
 * point and gesture delta - became the fifteen slots of {@code GwinViewCallbacks} and
 * {@code GwinGestureCallbacks}, keyed by an {@code int64_t} view id that {@code WinView} assigns and
 * resolves through its own registry, so that {@code glass.dll} holds no Java reference on the view
 * path. The fifteen targets ({@link #onNotifyMouse} and its siblings) do only marshalling; the dispatch is in
 * {@code WinView}, because {@code View.notify*} are {@code protected} members of another package that only a
 * {@code WinView}-typed reference can reach, and because the overrides {@code WinView} has for
 * {@code notifyResize} and {@code notifyMenu} must be the ones that run. See {@link #installViewCallbacks} for
 * why this table pair, unlike three others, has no {@code void* user}.
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code Utils.cpp}) refer to those files at commit
 * {@code 8492cb03b0} ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-glass/win/<file>}).
 */
final class WinViewCallbacks {

    /**
     * {@code GwinViewCallbacks} ({@code glass_win_api.h}): the ten former {@code javaIDs.View} method ids of
     * {@code GlassView.cpp}'s {@code _initIDs}, in declaration order; {@code gwin_sizeof_view_callbacks} pins
     * the size.
     */
    static final StructLayout GWIN_VIEW_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("notify_view"),
            ADDRESS.withName("notify_resize"),
            ADDRESS.withName("notify_repaint"),
            ADDRESS.withName("notify_menu"),
            ADDRESS.withName("notify_key"),
            ADDRESS.withName("notify_mouse"),
            ADDRESS.withName("notify_scroll"),
            ADDRESS.withName("notify_input_method"),
            ADDRESS.withName("notify_ime_candidate_pos_request"),
            ADDRESS.withName("get_accessible"));

    /**
     * {@code GwinGestureCallbacks} ({@code glass_win_api.h}): the five former {@code javaIDs.Gestures}
     * static method ids of {@code ViewContainer.cpp}, in declaration order.
     */
    static final StructLayout GWIN_GESTURE_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("gesture_performed"),
            ADDRESS.withName("inertia_gesture_finished"),
            ADDRESS.withName("notify_begin_touch_event"),
            ADDRESS.withName("notify_next_touch_event"),
            ADDRESS.withName("notify_end_touch_event"));

    private static final MethodHandle GWIN_SIZEOF_VIEW_CALLBACKS =
            bindGlass("gwin_sizeof_view_callbacks", FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle GWIN_SIZEOF_GESTURE_CALLBACKS =
            bindGlass("gwin_sizeof_gesture_callbacks", FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle GWIN_VIEW_SET_CALLBACKS = bindGlass("gwin_view_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle GWIN_GESTURE_SET_CALLBACKS = bindGlass("gwin_gesture_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * {@code gwin_test_fire_callback} returns {@code int64_t}, not {@code int32_t}, so that slot 9's
     * {@code get_accessible} value round-trips untruncated; binding it as {@code JAVA_INT} would read half a
     * return register and never notice.
     */
    private static final MethodHandle GWIN_TEST_FIRE_CALLBACK = bindGlass("gwin_test_fire_callback",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG));

    private WinViewCallbacks() {
    }

    /*
     * The fifteen upcall descriptors are package-private, not private, on purpose: WinGlassNativeShim
     * builds its recording stubs from these same objects, so the arity test in WinViewNativeTest proves
     * THESE descriptors against gwin_test_fire_callback's patterns and not a copy of them. The hot
     * slots - notify_mouse (per WM_MOUSEMOVE), notify_next_touch_event (per touch point per WM_TOUCH),
     * gesture_performed (per manipulation delta) and the per-frame notify_view / notify_resize /
     * notify_repaint / notify_scroll - carry primitives only, so no MemorySegment can be materialised
     * per event; the four that carry a pointer are per keystroke (notify_key) or cold (the IME pair,
     * and get_accessible's return).
     */
    static final FunctionDescriptor NOTIFY_VIEW_FD = FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT);
    static final FunctionDescriptor NOTIFY_RESIZE_FD = FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_REPAINT_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_MENU_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_KEY_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_MOUSE_FD = FunctionDescriptor.ofVoid(JAVA_LONG,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_SCROLL_FD = FunctionDescriptor.ofVoid(JAVA_LONG,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE);
    static final FunctionDescriptor NOTIFY_INPUT_METHOD_FD = FunctionDescriptor.ofVoid(JAVA_LONG,
            ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_IME_CANDIDATE_POS_REQUEST_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS);
    static final FunctionDescriptor GET_ACCESSIBLE_FD = FunctionDescriptor.of(JAVA_LONG, JAVA_LONG);
    /**
     * SEVEN {@code int32_t} then SEVEN {@code float}, counted against
     * {@code ViewContainer::NotifyGesturePerformed}: one integer too many shifts every float by an
     * 8-byte stack slot on x64 and a pinch delivers the bit pattern of {@code y_abs} as
     * {@code delta_x}, with no crash and no exception. {@code gwin_test_fire_callback(100, ...)} and
     * {@code WinViewNativeTest}'s recording table exist for exactly this descriptor.
     */
    static final FunctionDescriptor GESTURE_PERFORMED_FD = FunctionDescriptor.ofVoid(JAVA_LONG,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
            JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    static final FunctionDescriptor INERTIA_GESTURE_FINISHED_FD = FunctionDescriptor.ofVoid(JAVA_LONG);
    static final FunctionDescriptor NOTIFY_BEGIN_TOUCH_EVENT_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_NEXT_TOUCH_EVENT_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor NOTIFY_END_TOUCH_EVENT_FD = FunctionDescriptor.ofVoid(JAVA_LONG);

    /**
     * The slot names of {@link #GWIN_VIEW_CALLBACKS_LAYOUT} in declaration order, which is the order
     * the installer writes them, the order of {@link #VIEW_SLOT_TARGETS}, and the numbering
     * {@code gwin_test_fire_callback} uses (0..9).
     */
    static final List<String> VIEW_SLOT_NAMES = List.of("notify_view", "notify_resize", "notify_repaint",
            "notify_menu", "notify_key", "notify_mouse", "notify_scroll", "notify_input_method",
            "notify_ime_candidate_pos_request", "get_accessible");

    /** The {@link #GWIN_GESTURE_CALLBACKS_LAYOUT} slot names; {@code gwin_test_fire_callback} numbers them 100..104. */
    static final List<String> GESTURE_SLOT_NAMES = List.of("gesture_performed", "inertia_gesture_finished",
            "notify_begin_touch_event", "notify_next_touch_event", "notify_end_touch_event");

    /** The target methods of this class, parallel to {@link #VIEW_SLOT_NAMES}. */
    private static final String[] VIEW_SLOT_TARGETS = {"onNotifyView", "onNotifyResize", "onNotifyRepaint",
            "onNotifyMenu", "onNotifyKey", "onNotifyMouse", "onNotifyScroll", "onNotifyInputMethod",
            "onNotifyImeCandidatePosRequest", "onGetAccessible"};
    private static final FunctionDescriptor[] VIEW_SLOT_DESCRIPTORS = {NOTIFY_VIEW_FD, NOTIFY_RESIZE_FD,
            NOTIFY_REPAINT_FD, NOTIFY_MENU_FD, NOTIFY_KEY_FD, NOTIFY_MOUSE_FD, NOTIFY_SCROLL_FD,
            NOTIFY_INPUT_METHOD_FD, NOTIFY_IME_CANDIDATE_POS_REQUEST_FD, GET_ACCESSIBLE_FD};

    /** The target methods of this class, parallel to {@link #GESTURE_SLOT_NAMES}. */
    private static final String[] GESTURE_SLOT_TARGETS = {"onGesturePerformed", "onInertiaGestureFinished",
            "onNotifyBeginTouchEvent", "onNotifyNextTouchEvent", "onNotifyEndTouchEvent"};
    private static final FunctionDescriptor[] GESTURE_SLOT_DESCRIPTORS = {GESTURE_PERFORMED_FD,
            INERTIA_GESTURE_FINISHED_FD, NOTIFY_BEGIN_TOUCH_EVENT_FD, NOTIFY_NEXT_TOUCH_EVENT_FD,
            NOTIFY_END_TOUCH_EVENT_FD};

    private static boolean viewCallbacksInstalled;

    /**
     * The ten and the five stubs, created once by {@link #installViewCallbacks} in
     * {@link Arena#global()} and kept so that {@link #reinstallViewCallbacks} can write the same
     * addresses again. Never replaced: a stub the library has been given must outlive the process.
     */
    private static MemorySegment[] viewCallbackStubs;
    private static MemorySegment[] gestureCallbackStubs;

    /** {@code sizeof(GwinViewCallbacks)} as the C compiler saw it; {@link #GWIN_VIEW_CALLBACKS_LAYOUT} must agree. */
    static int sizeOfViewCallbacks() {
        try {
            return (int) GWIN_SIZEOF_VIEW_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code sizeof(GwinGestureCallbacks)}; {@link #GWIN_GESTURE_CALLBACKS_LAYOUT} must agree. */
    static int sizeOfGestureCallbacks() {
        try {
            return (int) GWIN_SIZEOF_GESTURE_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Hands {@code glass.dll} the two tables that replaced the 26 {@code Call*Method} sites of
     * {@code GlassView.cpp}, {@code ViewContainer.cpp} and {@code FullScreenWindow.cpp}. The install
     * was the flip: before it every one of those sites took its JNI path. Those paths are deleted, so
     * a site that finds no table installed now delivers nothing ({@code ViewCallbacks()} /
     * {@code GestureCallbacks()} answer {@code NULL}), and the install is what makes view events
     * arrive at all.
     * <p>
     * <b>Call this exactly once, from {@code WinApplication}'s static initializer</b>, beside the
     * preferences and application installs and for the same reason: the launcher thread, after the
     * library is loaded and before {@code Application.run()} creates the toolkit window, so the tables
     * exist before the first {@code WndProc} message and are never written from a second thread. A
     * lazy install - from {@code WinView}'s initializer, say - would leave a window in which a touch
     * event arrives before the table exists. What this does <em>not</em> do is initialise
     * {@code WinGestureSupport}: its static initializer must run on the toolkit thread
     * ({@code TouchInputSupport}'s constructor checks it), which the launcher thread is not, so
     * {@code WinApplication.runLoop} does that on the thread it creates.
     * <p>
     * <b>No {@code void* user}, unlike the other three tables.</b> The identity here is per view, not
     * per table - every slot's first parameter is the {@code int64_t} id {@code WinView} registered
     * itself under - so a table-level context would always be {@code NULL} (the three existing
     * installers are in fact passed {@code NULL}), and an {@code ADDRESS} parameter on an upcall stub
     * materialises a {@code MemorySegment} per invocation, which the per-mouse-move and per-touch-point
     * slots cannot afford. A parameter that is always {@code NULL} is worse than no parameter.
     * <p>
     * A slot left {@code NULL} would be replaced by an internal no-op in C; none is, all fifteen are
     * written. The tables are copied by the library, so the confined arena that holds them is closed
     * here; the stubs are in {@link Arena#global()}, because there is no point at which the library
     * can promise no further {@code WndProc} message.
     */
    static synchronized void installViewCallbacks() {
        if (viewCallbacksInstalled) {
            return;
        }
        viewCallbackStubs = stubsFor(WinViewCallbacks.class, VIEW_SLOT_TARGETS, VIEW_SLOT_DESCRIPTORS);
        gestureCallbackStubs = stubsFor(WinViewCallbacks.class, GESTURE_SLOT_TARGETS, GESTURE_SLOT_DESCRIPTORS);
        writeViewTables();
        viewCallbacksInstalled = true;
    }

    static synchronized boolean viewCallbacksInstalled() {
        return viewCallbacksInstalled;
    }

    /**
     * Writes the production tables again, with the stubs {@link #installViewCallbacks} created. The
     * one caller is {@code WinGlassNativeShim}, after it has installed a recording table to drive
     * {@code gwin_test_fire_callback} through the facade's descriptors: {@code gwin_view_set_callbacks}
     * copies by value and the last call wins, so this is the restore. Installs first if nothing has
     * been installed yet; never creates a second set of stubs.
     */
    static synchronized void reinstallViewCallbacks() {
        if (!viewCallbacksInstalled) {
            installViewCallbacks();
            return;
        }
        writeViewTables();
    }

    /** The fifteen installed stubs, view slots then gesture slots, for the test that reads them back. */
    static synchronized List<MemorySegment> installedViewCallbackStubs() {
        List<MemorySegment> stubs = new ArrayList<>(15);
        if (viewCallbacksInstalled) {
            Collections.addAll(stubs, viewCallbackStubs);
            Collections.addAll(stubs, gestureCallbackStubs);
        }
        return Collections.unmodifiableList(stubs);
    }

    private static void writeViewTables() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment viewTable = arena.allocate(GWIN_VIEW_CALLBACKS_LAYOUT);
            fillCallbackTable(viewTable, GWIN_VIEW_CALLBACKS_LAYOUT, VIEW_SLOT_NAMES, viewCallbackStubs);
            requireOk((int) GWIN_VIEW_SET_CALLBACKS.invokeExact(viewTable), "gwin_view_set_callbacks");
            MemorySegment gestureTable = arena.allocate(GWIN_GESTURE_CALLBACKS_LAYOUT);
            fillCallbackTable(gestureTable, GWIN_GESTURE_CALLBACKS_LAYOUT, GESTURE_SLOT_NAMES,
                    gestureCallbackStubs);
            requireOk((int) GWIN_GESTURE_SET_CALLBACKS.invokeExact(gestureTable), "gwin_gesture_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_test_fire_callback}: drives slot {@code slot} of the installed table with the
     * header's fixed pattern and returns what the slot returned - 0 for a void slot,
     * {@link WinCallSupport#GWIN_ERR_INVALID_ARG} for an unknown slot or an uninstalled table. A test hook, and the
     * only automated check of the fifteen descriptors: the {@code sizeof} probes cannot see a
     * parameter list, a table stores only pointers, and neither touch hardware nor an IME exists in a
     * build.
     */
    static long testFireCallback(int slot, long viewId) {
        try {
            return (long) GWIN_TEST_FIRE_CALLBACK.invokeExact(slot, viewId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /*
     * The fifteen targets. Each does the marshalling its slot needs and nothing else - the registry
     * lookup and the virtual call are WinView's - and each catches Throwable, because letting anything
     * out of an upcall stub terminates the JVM. Reporting through reportUpcallFailure is
     * CheckAndClearException (the former Utils.cpp:50-71): report on this thread, swallow, carry on. Thread:
     * always the JavaFX application thread, inside DispatchMessage or inside a gwin_view_* downcall
     * that ExecAction marshalled there; re-entrant, and nested in the downcall for set_parent,
     * enter_fullscreen and exit_fullscreen. Nothing here takes a lock (R8: View.uploadPixels already
     * holds View.lock() around gwin_view_upload_pixels, and a notify_view can arrive inside it).
     * Booleans arrive as int32_t 0 / 1 and are tested with != 0, never == 1. Package-private so that
     * WinGlassNativeShim can drive every arm without a table.
     */

    /** {@code notify_view}: {@code View.notifyView(int)} with a {@code ViewEvent} constant. Per frame during a move. */
    static void onNotifyView(long viewId, int type) {
        try {
            WinView.dispatchView(viewId, type);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_resize}: {@code View.notifyResize(int,int)}, through {@code WinView}'s override. */
    static void onNotifyResize(long viewId, int width, int height) {
        try {
            WinView.dispatchResize(viewId, width, height);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_repaint}: {@code View.notifyRepaint(int,int,int,int)}, the {@code GetUpdateRect} rect. */
    static void onNotifyRepaint(long viewId, int x, int y, int width, int height) {
        try {
            WinView.dispatchRepaint(viewId, x, y, width, height);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_menu}: {@code View.notifyMenu(int,int,int,int,boolean)}, through {@code WinView}'s override. */
    static void onNotifyMenu(long viewId, int x, int y, int xAbs, int yAbs, int isKeyboardTrigger) {
        try {
            WinView.dispatchMenu(viewId, x, y, xAbs, yAbs, isKeyboardTrigger != 0);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code notify_key}: {@code View.notifyKey(int,int,char[],int)}. {@code keyChars} points at
     * {@code keyCharCount} UTF-16 code units borrowed for the call; a count of 0 yields an empty
     * array, never {@code null}, because the JNI passed an allocated empty {@code jcharArray}
     * ({@code ViewContainer.cpp}, the {@code NewCharArray(keyCharCount)} before the PRESS / RELEASE
     * sites). One {@code char[]} per keystroke, as the JNI allocated.
     */
    static void onNotifyKey(long viewId, int type, int keyCode, MemorySegment keyChars, int keyCharCount,
                            int modifiers) {
        try {
            WinView.dispatchKey(viewId, type, keyCode, utf16(keyChars, keyCharCount), modifiers);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code notify_mouse}: {@code View.notifyMouse(int,int,int,int,int,int,int,boolean,boolean)}.
     * <b>Hot</b> - one per {@code WM_MOUSEMOVE} that moved, up to the mouse report rate - and so
     * scalars only: nothing here allocates, boxes or builds varargs. The one allocation that can
     * happen is the {@code Long} the registry lookup boxes, which {@code Long.valueOf} caches for the
     * first 127 ids; see {@code WinView.dispatchMouse}.
     */
    static void onNotifyMouse(long viewId, int type, int button, int x, int y, int xAbs, int yAbs,
                              int modifiers, int isPopupTrigger, int isSynthesized) {
        try {
            WinView.dispatchMouse(viewId, type, button, x, y, xAbs, yAbs, modifiers, isPopupTrigger != 0,
                    isSynthesized != 0);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_scroll}: {@code View.notifyScroll(...)}; per wheel notch, scalars only. */
    static void onNotifyScroll(long viewId, int x, int y, int xAbs, int yAbs, double deltaX, double deltaY,
                               int modifiers, int lines, int chars, int defaultLines, int defaultChars,
                               double xMultiplier, double yMultiplier) {
        try {
            WinView.dispatchScroll(viewId, x, y, xAbs, yAbs, deltaX, deltaY, modifiers, lines, chars,
                    defaultLines, defaultChars, xMultiplier, yMultiplier);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code notify_input_method}: {@code View.notifyInputMethod(String,int[],int[],byte[],int,int,int)}.
     * All four buffers are borrowed for the call and copied here. {@code text} is UTF-16, not
     * NUL-terminated, {@code textLen} code units long; {@code NULL} is the
     * {@code WM_IME_ENDCOMPOSITION} reset and also a failed result / composition join, and becomes
     * {@code null}. <b>Null-ness comes from the pointer, never from the count</b>: the C passes
     * {@code clauseBoundary} only when the JNI would have built the {@code jintArray}, and
     * {@code attrBoundary} / {@code attrValue} both or neither, but an individual {@code NewIntArray}
     * could fail and leave one of a pair {@code null}, so each pointer is tested on its own and the
     * count of a {@code NULL} buffer is ignored. {@code clauseBoundary} holds {@code clauseCount + 1}
     * ints, {@code attrBoundary} {@code attrCount + 1}, {@code attrValue} {@code attrCount} bytes
     * ({@code ViewContainer.cpp}'s {@code NewIntArray(cClause+1)}, {@code NewIntArray(cAttrBlock+1)},
     * {@code NewByteArray(cAttrBlock)}). Per composition step; allocates what the JNI allocated.
     */
    static void onNotifyInputMethod(long viewId, MemorySegment text, int textLen, MemorySegment clauseBoundary,
                                    int clauseCount, MemorySegment attrBoundary, MemorySegment attrValue,
                                    int attrCount, int committedTextLength, int caretPos, int visiblePos) {
        try {
            String composed = text.equals(MemorySegment.NULL) ? null : new String(utf16(text, textLen));
            int[] clauses = clauseBoundary.equals(MemorySegment.NULL) ? null : int32s(clauseBoundary, clauseCount + 1);
            int[] attrBounds = attrBoundary.equals(MemorySegment.NULL) ? null : int32s(attrBoundary, attrCount + 1);
            byte[] attrs = attrValue.equals(MemorySegment.NULL) ? null : bytes(attrValue, attrCount);
            WinView.dispatchInputMethod(viewId, composed, clauses, attrBounds, attrs, committedTextLength,
                    caretPos, visiblePos);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code notify_ime_candidate_pos_request}: {@code View.notifyInputMethodCandidatePosRequest(int)}.
     * <b>Query.</b> Writes {@code outXy[0] = x}, {@code outXy[1] = y} and returns 1, or touches
     * nothing and returns 0 - and then {@code GetCandidatePos} keeps its own {@code (0, 0)}. Both
     * elements are read before either is written, so a short array writes neither. On a throw the
     * segment is untouched and the answer is 0; the JNI had no such arm at all - {@code CallObjectMethod}
     * yields {@code NULL} with an exception pending and {@code GetDoubleArrayElements(NULL, NULL)}
     * followed with no null check ({@code ViewContainer.cpp GetCandidatePos}), reachable only because
     * {@code View.java} substitutes {@code {0, 0}} - so 0-and-untouched is the header's contract, not a
     * reproduction. Once per {@code IMN_OPENCANDIDATE} / {@code IMN_CHANGECANDIDATE}; cold.
     */
    static int onNotifyImeCandidatePosRequest(long viewId, int offset, MemorySegment outXy) {
        try {
            double[] pos = WinView.dispatchInputMethodCandidatePosRequest(viewId, offset);
            if (pos == null) {
                return 0;
            }
            double x = pos[0];
            double y = pos[1];
            MemorySegment out = bounded(outXy, 2 * JAVA_DOUBLE.byteSize());
            out.setAtIndex(JAVA_DOUBLE, 0, x);
            out.setAtIndex(JAVA_DOUBLE, 1, y);
            return 1;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return 0;
        }
    }

    /**
     * {@code get_accessible}: {@code View.getAccessible()}, the {@code IRawElementProviderSimple*} the
     * Java {@code Accessible} owns, or 0. <b>The return is load-bearing</b>: for
     * {@code UiaRootObjectId} a non-zero value goes to {@code UiaReturnRawElementProvider} and its
     * {@code LRESULT} is the {@code WM_GETOBJECT} reply ({@code GlassWindow.cpp}'s
     * {@code if (lr) return lr;}); 0 falls through to {@code DefWindowProc}, and a wrong 0 silently
     * disables accessibility for the window. A throw answers 0, which is what the JNI answered:
     * {@code CallLongMethod} returns 0 with an exception pending and {@code CheckAndClearException}
     * then reported it ({@code ViewContainer.cpp}, the {@code pProvider} read of {@code WM_GETOBJECT}).
     * Two sites, cold.
     */
    static long onGetAccessible(long viewId) {
        try {
            return WinView.dispatchGetAccessible(viewId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return 0L;
        }
    }

    /**
     * {@code gesture_performed}: <b>hot</b> - one per manipulation delta - and scalars only. The view is the
     * registry's answer, {@code null} for id 0 (a container with no {@code GlassView} attached, which
     * {@code HandleViewTouchEvent} produces) and for an id that is gone; the JNI passed a {@code null}
     * {@code View} in the first case too.
     */
    static void onGesturePerformed(long viewId, int modifiers, int isDirect, int isInertia, int x, int y,
                                   int xAbs, int yAbs, float deltaX, float deltaY, float totalDeltaX,
                                   float totalDeltaY, float totalScale, float totalExpansion,
                                   float totalRotation) {
        try {
            WinGestureSupport.gesturePerformed(WinView.viewFor(viewId), modifiers, isDirect != 0,
                    isInertia != 0, x, y, xAbs, yAbs, deltaX, deltaY, totalDeltaX, totalDeltaY, totalScale,
                    totalExpansion, totalRotation);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code inertia_gesture_finished}: per timer tick. */
    static void onInertiaGestureFinished(long viewId) {
        try {
            WinGestureSupport.inertiaGestureFinished(WinView.viewFor(viewId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    static void onNotifyBeginTouchEvent(long viewId, int modifiers, int isDirect, int touchEventCount) {
        try {
            WinGestureSupport.notifyBeginTouchEvent(WinView.viewFor(viewId), modifiers, isDirect != 0,
                    touchEventCount);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code notify_next_touch_event}: <b>hot</b> - once per touch point per {@code WM_TOUCH} - and scalars
     * only; {@code id} is the {@code TOUCHINPUT.dwID} widened to 64 bits.
     */
    static void onNotifyNextTouchEvent(long viewId, int state, long id, int x, int y, int xAbs, int yAbs) {
        try {
            WinGestureSupport.notifyNextTouchEvent(WinView.viewFor(viewId), state, id, x, y, xAbs, yAbs);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    static void onNotifyEndTouchEvent(long viewId) {
        try {
            WinGestureSupport.notifyEndTouchEvent(WinView.viewFor(viewId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    private static int[] int32s(MemorySegment pointer, int count) {
        return bounded(pointer, count * JAVA_INT.byteSize()).toArray(JAVA_INT);
    }

    private static byte[] bytes(MemorySegment pointer, int count) {
        return bounded(pointer, count).toArray(JAVA_BYTE);
    }
}
