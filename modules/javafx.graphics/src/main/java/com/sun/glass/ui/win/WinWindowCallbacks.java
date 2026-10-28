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
import java.util.List;
import static com.sun.glass.ui.win.WinCallSupport.fillCallbackTable;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;
import static com.sun.glass.ui.win.WinCallSupport.requireOk;
import static com.sun.glass.ui.win.WinCallSupport.stubsFor;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bounded;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} callback table of {@link WinWindow}: {@code GwinWindowCallbacks}, its descriptors,
 * its install and its twelve upcall targets.
 * <p>
 * Every slot is keyed by an {@code int64_t} window id {@code WinWindow} assigns and resolves through its own
 * registry, so that {@code glass.dll} holds no Java reference on the window path. The downcalls are
 * {@link WinWindowCalls}.
 */
final class WinWindowCallbacks {

    /**
     * {@code GwinWindowCallbacks} ({@code glass_win_api.h}): the six file-static {@code jmethodID}s
     * of {@code GlassWindow.cpp} plus {@code javaIDs.Window} (5) and {@code javaIDs.WinWindow} (1),
     * minus the never-called {@code midNotifyMoveToAnotherScreen}, plus {@code notify_dispose} in
     * place of the {@code DeleteGlobalRef} of {@code ~GlassWindow}.
     */
    static final StructLayout GWIN_WINDOW_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("notify_close"),
            ADDRESS.withName("notify_destroy"),
            ADDRESS.withName("notify_moving"),
            ADDRESS.withName("notify_move"),
            ADDRESS.withName("notify_resize"),
            ADDRESS.withName("notify_scale_changed"),
            ADDRESS.withName("notify_focus"),
            ADDRESS.withName("notify_focus_disabled"),
            ADDRESS.withName("notify_focus_ungrab"),
            ADDRESS.withName("notify_delegate_ptr"),
            ADDRESS.withName("non_client_hit_test"),
            ADDRESS.withName("notify_dispose"));

    private static final MethodHandle GWIN_SIZEOF_WINDOW_CALLBACKS =
            bindGlass("gwin_sizeof_window_callbacks", FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle GWIN_WINDOW_SET_CALLBACKS = bindGlass("gwin_window_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private WinWindowCallbacks() {
    }

    /*
     * The first JAVA_LONG of each upcall descriptor is the window id. Package-private
     * for the reason the view section's are: the shim reads them, and any table a test installs must
     * be built from THESE objects. gwin_test_fire_window_callback - added after the ABI-4 bump as a
     * sibling of gwin_test_fire_callback - fires each slot with the header's fixed pattern; the shim
     * binds it, not this class (the product never calls it), and WinWindowNativeTest proves every
     * descriptor through it, argument by argument, against a recording table built from these objects.
     *
     * Hot: notify_moving (per WM_WINDOWPOSCHANGING during a drag - 13 scalars and one bounded
     * out-pointer, nothing allocated on the "no override" path) and non_client_hit_test (per mouse
     * move over an EXTENDED window's frame - scalars only). Warm: notify_move, notify_resize,
     * notify_focus. Cold: the rest.
     */
    static final FunctionDescriptor WINDOW_NOTIFY_CLOSE_FD = FunctionDescriptor.ofVoid(JAVA_LONG);
    static final FunctionDescriptor WINDOW_NOTIFY_DESTROY_FD = FunctionDescriptor.ofVoid(JAVA_LONG);
    /**
     * Four {@code int32_t}, two {@code float}, seven {@code int32_t}, then the out-pointer - counted
     * against {@code GlassWindow::HandleWindowPosChangingEvent}'s dial and against
     * {@code WinWindow.notifyMoving(IIIIFFIIIIIII)}. One integer out of place would hand the anchor
     * the bit pattern of a float, with no crash and no exception.
     */
    static final FunctionDescriptor WINDOW_NOTIFY_MOVING_FD = FunctionDescriptor.of(JAVA_INT, JAVA_LONG,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, JAVA_INT, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    static final FunctionDescriptor WINDOW_NOTIFY_MOVE_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor WINDOW_NOTIFY_RESIZE_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor WINDOW_NOTIFY_SCALE_CHANGED_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    static final FunctionDescriptor WINDOW_NOTIFY_FOCUS_FD = FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT);
    static final FunctionDescriptor WINDOW_NOTIFY_FOCUS_DISABLED_FD = FunctionDescriptor.ofVoid(JAVA_LONG);
    static final FunctionDescriptor WINDOW_NOTIFY_FOCUS_UNGRAB_FD = FunctionDescriptor.ofVoid(JAVA_LONG);
    static final FunctionDescriptor WINDOW_NOTIFY_DELEGATE_PTR_FD =
            FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS);
    static final FunctionDescriptor WINDOW_NON_CLIENT_HIT_TEST_FD =
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT);
    static final FunctionDescriptor WINDOW_NOTIFY_DISPOSE_FD = FunctionDescriptor.ofVoid(JAVA_LONG);

    /** In the declaration order of {@link #GWIN_WINDOW_CALLBACKS_LAYOUT}, parallel to {@link #WINDOW_SLOT_TARGETS}. */
    static final List<String> WINDOW_SLOT_NAMES = List.of("notify_close", "notify_destroy", "notify_moving",
            "notify_move", "notify_resize", "notify_scale_changed", "notify_focus", "notify_focus_disabled",
            "notify_focus_ungrab", "notify_delegate_ptr", "non_client_hit_test", "notify_dispose");

    private static final String[] WINDOW_SLOT_TARGETS = {"onWindowClose", "onWindowDestroy", "onWindowMoving",
            "onWindowMove", "onWindowResize", "onWindowScaleChanged", "onWindowFocus", "onWindowFocusDisabled",
            "onWindowFocusUngrab", "onWindowDelegatePtr", "onWindowNonClientHitTest", "onWindowDispose"};
    private static final FunctionDescriptor[] WINDOW_SLOT_DESCRIPTORS = {WINDOW_NOTIFY_CLOSE_FD,
            WINDOW_NOTIFY_DESTROY_FD, WINDOW_NOTIFY_MOVING_FD, WINDOW_NOTIFY_MOVE_FD, WINDOW_NOTIFY_RESIZE_FD,
            WINDOW_NOTIFY_SCALE_CHANGED_FD, WINDOW_NOTIFY_FOCUS_FD, WINDOW_NOTIFY_FOCUS_DISABLED_FD,
            WINDOW_NOTIFY_FOCUS_UNGRAB_FD, WINDOW_NOTIFY_DELEGATE_PTR_FD, WINDOW_NON_CLIENT_HIT_TEST_FD,
            WINDOW_NOTIFY_DISPOSE_FD};

    /**
     * winuser.h {@code HTNOWHERE}: what {@code non_client_hit_test} answers on a Java throw, because
     * HotSpot's {@code CallIntMethod} returned 0 with an exception pending and the shipped result of
     * that was "not HTCAPTION, not HTUNSPECIFIED, answer HTNOWHERE" ({@code HandleNCHitTestEvent}).
     * {@code HTCLIENT} (1) would be an improvement, hence not neutral. Pinned by {@code static_assert}
     * in {@code glass_win_api.cpp}.
     */
    static final int HTNOWHERE = 0;

    private static boolean windowCallbacksInstalled;

    /**
     * The twelve stubs, created once by {@link #installWindowCallbacks} in {@link Arena#global()} and
     * kept so that {@link #reinstallWindowCallbacks} can write the same addresses again. Never
     * replaced: a stub the library has been given must outlive the process.
     */
    private static MemorySegment[] windowCallbackStubs;

    /**
     * {@code sizeof(GwinWindowCallbacks)} as the C compiler saw it; {@link #GWIN_WINDOW_CALLBACKS_LAYOUT}
     * must agree.
     */
    static int sizeOfWindowCallbacks() {
        try {
            return (int) GWIN_SIZEOF_WINDOW_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Hands {@code glass.dll} the table that replaced the eleven {@code Call*Method} sites of
     * {@code GlassWindow.cpp} and the {@code DeleteGlobalRef} of {@code ~GlassWindow}. The install was
     * the flip: before it every site took its JNI path. Those paths are deleted, so a site that finds
     * no table installed now delivers nothing ({@code WindowCallbacks()} answers {@code NULL}). The
     * flip travelled in the same change set as {@code WinWindow}'s forwarders: a table with no
     * registry behind it drops every window event, and a JNI-created window under an installed table
     * would have delivered id 0 to a registry that knows no 0.
     * <p>
     * <b>Call this exactly once, from {@code WinApplication}'s static initializer</b>, beside
     * {@link WinViewCallbacks#installViewCallbacks} and for the same reason: the launcher thread, after the library
     * is loaded and before {@code Application.run()} creates the toolkit window, so the table exists
     * before the first {@code WndProc} message and is never written from a second thread. No
     * {@code void* user}, as for the view tables: the identity is per window, the {@code int64_t} id
     * {@code WinWindow} registered itself under before it called {@code gwin_window_create}.
     * <p>
     * A slot left {@code NULL} would be replaced by an internal no-op in C - and for
     * {@code non_client_hit_test} that no-op answers {@code HTNOWHERE}, which silently turns an
     * EXTENDED window's caption off - so all twelve are written. The table is copied by the library;
     * the confined arena that holds it is closed here, the stubs live in {@link Arena#global()}.
     */
    static synchronized void installWindowCallbacks() {
        if (windowCallbacksInstalled) {
            return;
        }
        windowCallbackStubs = stubsFor(WinWindowCallbacks.class, WINDOW_SLOT_TARGETS, WINDOW_SLOT_DESCRIPTORS);
        writeWindowTable();
        windowCallbacksInstalled = true;
    }

    static synchronized boolean windowCallbacksInstalled() {
        return windowCallbacksInstalled;
    }

    /**
     * Writes the production table again with the stubs {@link #installWindowCallbacks} created;
     * {@code gwin_window_set_callbacks} copies by value and the last call wins, so this restores
     * after a test has installed a table of its own. Installs first if nothing has been installed
     * yet; never creates a second set of stubs.
     */
    static synchronized void reinstallWindowCallbacks() {
        if (!windowCallbacksInstalled) {
            installWindowCallbacks();
            return;
        }
        writeWindowTable();
    }

    /** The twelve installed stubs in slot order, for the test that reads them back; empty before the install. */
    static synchronized List<MemorySegment> installedWindowCallbackStubs() {
        if (!windowCallbacksInstalled) {
            return List.of();
        }
        return List.of(windowCallbackStubs);
    }

    private static void writeWindowTable() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GWIN_WINDOW_CALLBACKS_LAYOUT);
            fillCallbackTable(table, GWIN_WINDOW_CALLBACKS_LAYOUT, WINDOW_SLOT_NAMES, windowCallbackStubs);
            requireOk((int) GWIN_WINDOW_SET_CALLBACKS.invokeExact(table), "gwin_window_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /*
     * The twelve targets. As for the view section: the marshalling is here, the registry lookup and
     * the protected Window.notify* call are WinWindow's (they are protected members of another
     * package, reachable only through a WinWindow body), and each catches Throwable, because letting
     * anything out of an upcall stub terminates the JVM. Thread: always the JavaFX application thread,
     * inside DispatchMessage, inside the WH_CBT hook, or inside a gwin_window_* downcall that
     * ExecAction marshalled there; re-entrant; possibly on ANOTHER window's WndProc frame
     * (FullScreenWindow reaches the owner's HandleActivateEvent / HandleCloseEvent / SetDelegateWindow,
     * and SetDelegateWindow's UngrabFocus can fire notify_focus_ungrab on a third window) - the id is
     * always the right one. Nothing here takes a lock. An id the registry does not know is a stale
     * peer or a JNI-created window (id 0): the slot default, silently, no report. Package-private so
     * that WinGlassNativeShim can drive every arm without a table.
     */

    /**
     * {@code notify_close}: {@code Window.notifyClose()}; WM_CLOSE, and the FullScreenWindow's
     * WM_CLOSE on the owner.
     */
    static void onWindowClose(long windowId) {
        try {
            WinWindow.dispatchClose(windowId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_destroy}: {@code Window.notifyDestroy()}, which zeroes {@code Window.ptr}; WM_DESTROY. */
    static void onWindowDestroy(long windowId) {
        try {
            WinWindow.dispatchDestroy(windowId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code notify_moving}: {@code WinWindow.notifyMoving(...)} from WM_WINDOWPOSCHANGING.
     * <b>Query, hot.</b> A non-null answer is written to {@code out_bounds[0..3]} as x, y, w, h and
     * 1 is returned; {@code null}, a throw - and an answer that is not exactly four ints, the JNI's
     * "bad array length" arm minus its {@code stderr} line, unreachable from {@code WinWindow} itself
     * - leave the buffer untouched and answer 0, which is what a null return, a thrown exception and
     * a failed {@code GetIntArrayRegion} all produced. {@code fxX} / {@code fxY} arrive as typed
     * {@code 0.0f}: the JNI passed two {@code int} literals where {@code CallObjectMethod}'s varargs
     * wanted doubles, and the values are dead on this path because {@code resize_mode} is never
     * {@code RESIZE_TO_FX_ORIGIN} from the WndProc.
     */
    static int onWindowMoving(long windowId, int x, int y, int w, int h, float fxX, float fxY, int anchorX,
                              int anchorY, int resizeMode, int insetLeft, int insetTop, int insetRight,
                              int insetBottom, MemorySegment outBounds) {
        try {
            int[] bounds = WinWindow.dispatchMoving(windowId, x, y, w, h, fxX, fxY, anchorX, anchorY, resizeMode,
                    insetLeft, insetTop, insetRight, insetBottom);
            if (bounds == null || bounds.length != 4) {
                return 0;
            }
            writeBounds(outBounds, bounds);
            return 1;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return 0;
        }
    }

    /**
     * {@code notify_move}: {@code Window.notifyMove(int,int)}; WM_MOVE, WM_DISPLAYCHANGE,
     * WM_SETTINGCHANGE and WM_SHOWWINDOW.
     */
    static void onWindowMove(long windowId, int x, int y) {
        try {
            WinWindow.dispatchMove(windowId, x, y);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_resize}: {@code WinWindow.notifyResize(int,int,int)} - the override, not {@code Window}'s. */
    static void onWindowResize(long windowId, int type, int width, int height) {
        try {
            WinWindow.dispatchResize(windowId, type, width, height);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code notify_scale_changed}: {@code Window.notifyScaleChanged(float x4)}; WM_DPICHANGED, all
     * four the same value.
     */
    static void onWindowScaleChanged(long windowId, float platformX, float platformY, float outputX, float outputY) {
        try {
            WinWindow.dispatchScaleChanged(windowId, platformX, platformY, outputX, outputY);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_focus}: {@code Window.notifyFocus(int)} with FOCUS_GAINED / FOCUS_LOST; WM_ACTIVATE. */
    static void onWindowFocus(long windowId, int event) {
        try {
            WinWindow.dispatchFocus(windowId, event);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_focus_disabled}: {@code Window.notifyFocusDisabled()}; WM_MOUSEACTIVATE and the CBT hook. */
    static void onWindowFocusDisabled(long windowId) {
        try {
            WinWindow.dispatchFocusDisabled(windowId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code notify_focus_ungrab}: {@code Window.notifyFocusUngrab()}; {@code GlassWindow::UngrabFocus}. */
    static void onWindowFocusUngrab(long windowId) {
        try {
            WinWindow.dispatchFocusUngrab(windowId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code notify_delegate_ptr}: {@code Window.notifyDelegatePtr(long)} with the FullScreenWindow's
     * HWND, or {@code NULL} on detach. The pointer is a USER handle value, never dereferenced by
     * either side, and crosses as its address.
     */
    static void onWindowDelegatePtr(long windowId, MemorySegment delegateHwnd) {
        try {
            WinWindow.dispatchDelegatePtr(windowId, delegateHwnd.address());
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * {@code non_client_hit_test}: {@code WinWindow.nonClientHitTest(int,int)} for EXTENDED windows.
     * <b>Query, hot.</b> The return is load-bearing - an HT* constant or {@code HTUNSPECIFIED}, which
     * the C post-processes into the WndProc's WM_NCHITTEST answer. A throw, and an unknown id, answer
     * {@link #HTNOWHERE}.
     */
    static int onWindowNonClientHitTest(long windowId, int x, int y) {
        try {
            return WinWindow.dispatchNonClientHitTest(windowId, x, y);
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return HTNOWHERE;
        }
    }

    /**
     * {@code notify_dispose}: {@code ~GlassWindow}, at the point {@code DeleteGlobalRef} was. Drops
     * the registry entry and does <em>nothing else</em>: it fires after {@code RemoveProp}, so
     * {@code FromHandle} is already {@code NULL} inside it and any {@code gwin_window_*} call from
     * here would dereference NULL in a C++ destructor. It must not call
     * {@code Window.notifyDestroy}, which is the separate slot fired earlier from WM_DESTROY.
     */
    static void onWindowDispose(long windowId) {
        try {
            WinWindow.dispose(windowId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /**
     * The buffer is the caller's. The copy writes all four ints or - if the segment could not be bounded -
     * none.
     */
    private static void writeBounds(MemorySegment outBounds, int[] bounds) {
        MemorySegment out = bounded(outBounds, 4 * JAVA_INT.byteSize());
        MemorySegment.copy(bounds, 0, out, JAVA_INT, 0L, 4);
    }
}
