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

import com.sun.glass.ui.Pixels;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bindUser32;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} downcalls of {@link WinView}: the {@code gwin_view_*} exports of
 * {@code GlassView.cpp} and the multi-click metrics.
 * <p>
 * <b>The view peer</b> (ABI 4), {@code GlassView.cpp}, and the {@code WndProc} body that feeds it,
 * {@code ViewContainer.cpp}. Eleven of {@code WinView}'s eighteen natives were {@code OS-CALL} or
 * native-state bodies and became the {@code gwin_view_*} exports ({@link #viewClose} through
 * {@link #viewFinishImeComposition}); the three multi-click getters were {@code WRAPPER}s over
 * {@code user32!GetDoubleClickTime} and {@code GetSystemMetrics(SM_CXDOUBLECLK / SM_CYDOUBLECLK)} and
 * are bound directly; {@code _create} became {@link #viewCreate}, so {@code WinView} declares no native any
 * more. The other direction - the view and gesture callback tables - is {@link WinViewCallbacks}.
 */
final class WinViewCalls {

    /* The double-click rectangle (_getMultiClickMaxX/Y); both pinned by static_assert in glass_win_api.cpp. */
    static final int SM_CXDOUBLECLK = 36;
    static final int SM_CYDOUBLECLK = 37;

    /*
     * The view section of glass_win_api.h, in the header's order. None of these is critical:
     * eight of them block in SendMessage(WM_DO_ACTION) and three of those upcall from inside the
     * downcall (set_parent, enter_fullscreen, exit_fullscreen); the three that do not marshal take no
     * Java array. See viewUploadPixels for the one that carries a buffer, and why it is copied.
     */

    private static final MethodHandle GWIN_VIEW_CREATE = bindGlass("gwin_view_create",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG));

    private static final MethodHandle GWIN_VIEW_CLOSE = bindGlass("gwin_view_close",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle GWIN_VIEW_GET_NATIVE_VIEW = bindGlass("gwin_view_get_native_view",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_VIEW_SET_PARENT = bindGlass("gwin_view_set_parent",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    private static final MethodHandle GWIN_VIEW_GET_X = bindGlass("gwin_view_get_x",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle GWIN_VIEW_GET_Y = bindGlass("gwin_view_get_y",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle GWIN_VIEW_SCHEDULE_REPAINT = bindGlass("gwin_view_schedule_repaint",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle GWIN_VIEW_UPLOAD_PIXELS = bindGlass("gwin_view_upload_pixels",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    private static final MethodHandle GWIN_VIEW_ENTER_FULLSCREEN = bindGlass("gwin_view_enter_fullscreen",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));

    private static final MethodHandle GWIN_VIEW_EXIT_FULLSCREEN = bindGlass("gwin_view_exit_fullscreen",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_VIEW_ENABLE_IME = bindGlass("gwin_view_enable_ime",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private static final MethodHandle GWIN_VIEW_FINISH_IME_COMPOSITION =
            bindGlass("gwin_view_finish_ime_composition", FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle GET_DOUBLE_CLICK_TIME = bindUser32("GetDoubleClickTime",
            FunctionDescriptor.of(JAVA_INT));

    private WinViewCalls() {
    }

    /**
     * {@code gwin_view_create}: {@code new GlassView(viewId)}, all that the former JNI {@code WinView._create} did.
     * No OS call and no thread marshal, so it works without a toolkit. The {@code viewId} is what every
     * view, gesture and drop-target slot delivers; the object is released by {@link #viewClose}.
     *
     * @return the {@code gwin_view_t} as a {@code long}; 0 only when the allocation threw
     */
    static long viewCreate(long viewId) {
        try {
            MemorySegment view = (MemorySegment) GWIN_VIEW_CREATE.invokeExact(viewId);
            return view.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_view_close}: {@code GlassView::Close} plus {@code delete}, marshalled to the toolkit
     * thread as {@code ENTER_MAIN_THREAD_AND_RETURN} did. <b>Blocks and upcalls</b> - closing the
     * {@code FullScreenWindow} can fire {@code notify_view} on the way out, which is why
     * {@code WinView._close} removes its registry entry only after this returns. {@code view} is
     * dangling afterwards and must never be passed to a {@code gwin_view_*} function again;
     * {@code View.close()} nulls its handle.
     *
     * @return true; false only when the C caught a C++ exception
     */
    static boolean viewClose(long view) {
        try {
            return (int) GWIN_VIEW_CLOSE.invokeExact(MemorySegment.ofAddress(view)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_view_get_native_view}: the host HWND as a {@code long}, 0 when detached. No thread marshal. */
    static long viewGetNativeView(long view) {
        try {
            MemorySegment hwnd = (MemorySegment) GWIN_VIEW_GET_NATIVE_VIEW.invokeExact(MemorySegment.ofAddress(view));
            return hwnd.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_view_set_parent}: {@code GlassView::SetHostHwnd} on the toolkit thread, which fires
     * {@code notify_view(REMOVE)} then {@code notify_view(ADD)} from inside this call when the host
     * changes. <b>Blocks and upcalls.</b>
     */
    static void viewSetParent(long view, long parentHwnd) {
        try {
            GWIN_VIEW_SET_PARENT.invokeExact(MemorySegment.ofAddress(view), MemorySegment.ofAddress(parentHwnd));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_view_get_x}: the host window's left inset, 0 without a host. Blocks. */
    static int viewGetX(long view) {
        try {
            return (int) GWIN_VIEW_GET_X.invokeExact(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_view_get_y}: the host window's top inset, 0 without a host. Blocks. */
    static int viewGetY(long view) {
        try {
            return (int) GWIN_VIEW_GET_Y.invokeExact(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_view_schedule_repaint}: {@code InvalidateRect(host, NULL, FALSE)}. Blocks. */
    static void viewScheduleRepaint(long view) {
        try {
            GWIN_VIEW_SCHEDULE_REPAINT.invokeExact(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_view_upload_pixels} with the {@code Pixels} resolved here instead of by the
     * {@code Pixels.attachData} upcall the JNI made from inside its {@code SendMessage}
     * ({@code GlassView.cpp _uploadPixels}, {@code Pixels.cpp}). {@link #pixelBits} reproduces what
     * {@code WinPixels._attachInt / _attachByte} handed the C: the base address of a direct buffer,
     * the array of a heap buffer from its {@code arrayOffset()}, or {@code NULL}. The buffer crosses
     * as a borrowed pointer the C never retains, from a confined arena closed on return; a copy rather
     * than a pinned array, because the call blocks in {@code SendMessage} and the C released with
     * {@code JNI_ABORT}, i.e. read it only. The JNI checked {@code IsWindow(host)} before resolving
     * the {@code Pixels} and this resolves first; the export checks again, so a stale host costs one
     * wasted copy and nothing else. Blocks.
     */
    static void viewUploadPixels(long view, Pixels pixels) {
        int width = pixels.getWidth();
        int height = pixels.getHeight();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bits = pixelBits(arena, pixels.getBuffer(), width, height);
            GWIN_VIEW_UPLOAD_PIXELS.invokeExact(MemorySegment.ofAddress(view), width, height, bits);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The {@code bits} pointer {@code gwin_view_upload_pixels} is given for a {@code Pixels} buffer.
     * <ul>
     * <li>Direct: the buffer's base address. {@code duplicate().clear()} is load-bearing -
     * {@link MemorySegment#ofBuffer} honours position and limit, {@code GetDirectBufferAddress}
     * ignored both, and {@code Pixels.getBuffer()} hands out "the original pixels buffer,
     * unmodified", shared with other callers, so it is not rewound in place.</li>
     * <li>Heap with an accessible array: {@code width * height * 4} bytes copied from
     * {@code array()} at {@code arrayOffset()} into {@code arena}, which is what
     * {@code Pixels.attachData} passed ({@code Pixels.java}: {@code array()} and
     * {@code arrayOffset()}, not the position). An array shorter than that is copied as far as it
     * goes and the rest stays zero, where the C read past the end of the Java array.</li>
     * <li>Anything else - a read-only heap buffer, whose {@code array()} threw
     * {@code ReadOnlyBufferException} inside {@code attachData}, which {@code CheckAndClearException}
     * swallowed so that {@code GetBits()} answered {@code NULL}: {@link MemorySegment#NULL}, which the
     * export accepts and hands to {@code SetDIBitsToDevice} unchanged.</li>
     * </ul>
     */
    static MemorySegment pixelBits(Arena arena, Buffer buffer, int width, int height) {
        if (buffer.isDirect()) {
            return MemorySegment.ofBuffer(buffer.duplicate().clear());
        }
        if (!buffer.hasArray()) {
            return MemorySegment.NULL;
        }
        long byteCount = (long) width * height * 4L;
        MemorySegment copy = arena.allocate(byteCount, JAVA_INT.byteAlignment());
        if (buffer instanceof IntBuffer ints) {
            int available = ints.array().length - ints.arrayOffset();
            int count = (int) Math.min(available, byteCount / JAVA_INT.byteSize());
            MemorySegment.copy(ints.array(), ints.arrayOffset(), copy, JAVA_INT, 0L, count);
        } else if (buffer instanceof ByteBuffer bytes) {
            int available = bytes.array().length - bytes.arrayOffset();
            int count = (int) Math.min(available, byteCount);
            MemorySegment.copy(bytes.array(), bytes.arrayOffset(), copy, JAVA_BYTE, 0L, count);
        } else {
            // Pixels only ever holds an IntBuffer or a ByteBuffer (Pixels.java's two constructors).
            return MemorySegment.NULL;
        }
        return copy;
    }

    /**
     * {@code gwin_view_enter_fullscreen}: fires {@code notify_view(FULLSCREEN_ENTER)} on success from
     * inside the call. {@code hideCursor} is passed and ignored, as the JNI ignored it. <b>Blocks and
     * upcalls.</b>
     */
    static boolean viewEnterFullscreen(long view, boolean animate, boolean keepRatio, boolean hideCursor) {
        try {
            return (int) GWIN_VIEW_ENTER_FULLSCREEN.invokeExact(MemorySegment.ofAddress(view), animate ? 1 : 0,
                    keepRatio ? 1 : 0, hideCursor ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_view_exit_fullscreen}: fires {@code notify_view(FULLSCREEN_EXIT)} from inside the
     * call; with {@code animate} the REMOVE / ADD / resize of a {@code FullScreenWindow} host arrive
     * later from a {@code WM_TIMER}, possibly after the view is closed, which is why a registry miss
     * is silent. <b>Blocks and upcalls.</b>
     */
    static void viewExitFullscreen(long view, boolean animate) {
        try {
            GWIN_VIEW_EXIT_FULLSCREEN.invokeExact(MemorySegment.ofAddress(view), animate ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code gwin_view_enable_ime}: the flag the {@code WndProc} IME path tests, native because it
     * decides a {@code WndProc} return. The C keeps {@code jbool_to_bool}'s rule - only 1 enables - so
     * {@code true} crosses as exactly 1. No thread marshal.
     */
    static void viewEnableIme(long view, boolean enable) {
        try {
            GWIN_VIEW_ENABLE_IME.invokeExact(MemorySegment.ofAddress(view), enable ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code gwin_view_finish_ime_composition}: {@code ImmNotifyIME(NI_COMPOSITIONSTR, CPS_COMPLETE)}. No marshal. */
    static void viewFinishImeComposition(long view) {
        try {
            GWIN_VIEW_FINISH_IME_COMPOSITION.invokeExact(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code GetDoubleClickTime()}, the whole of {@code _getMultiClickTime_impl}, widened as the JNI's
     * {@code (jlong)} cast widened an unsigned {@code UINT}. The two multi-click extents are
     * {@link WinRobotCalls#getSystemMetrics} with {@link #SM_CXDOUBLECLK} / {@link #SM_CYDOUBLECLK}.
     */
    static long getDoubleClickTime() {
        try {
            return Integer.toUnsignedLong((int) GET_DOUBLE_CLICK_TIME.invokeExact());
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
