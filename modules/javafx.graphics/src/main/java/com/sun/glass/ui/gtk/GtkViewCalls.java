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

import com.sun.glass.ui.gtk.GtkCallbackTables.Glass;
import com.sun.glass.ui.gtk.GtkGlassNative.UploadStaging;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.nio.Buffer;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkPixelsCalls.directBufferBase;

/**
 * The natives of {@code GtkView} at commit {@code 033187ad90} ({@code GlassView.cpp}). Each replaces the
 * {@code Java_com_sun_glass_ui_gtk_GtkView_*} function it names, whose body is now the {@code ggtk_view_*} function of
 * {@code glass_gtk_api.h} (THE NATIVES) the method calls ({@link GtkCallbackTables.Glass}); what the JNI function did
 * with its Java arguments and result is done here, in its order, around the downcall, on the thread that calls it.
 * {@code _getNativeView} and {@code _scheduleRepaint} answer 0 or do nothing in {@code GtkView} itself, as their bodies
 * did.
 * <p>
 * No downcall of the binding is {@link Linker.Option#critical critical}: the sources run Java, most of the
 * {@code ggtk_*} functions and the main-loop calls block and re-enter Java through the binding, and the frame uploads
 * of {@code GtkView._uploadPixelsIntArray} and {@code _uploadPixelsByteArray}, which cannot call Java, paint a whole
 * frame to the X server - far longer than a critical downcall may keep its thread from a safepoint.
 */
final class GtkViewCalls {

    /** {@code Java_com_sun_glass_ui_gtk_GtkView_enableInputMethodEventsImpl}: the IME of the view's window. */
    static void viewEnableInputMethodEvents(long view, boolean enable) {
        try {
            Glass.VIEW_ENABLE_INPUT_METHOD_EVENTS.invokeExact(MemorySegment.ofAddress(view), enable ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkView__1create} ({@code GlassView.cpp}): a new {@code GlassView} whose
     * slots receive {@code viewId}; the JNI ignored the caps map. No upcall.
     *
     * @return the {@code GlassView *}, never 0
     */
    static long viewCreate(long viewId) {
        try {
            return ((MemorySegment) Glass.VIEW_CREATE.invokeExact(viewId)).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkView__1getX}: the content's x in its window, 0 without one. */
    static int viewX(long view) {
        try {
            return (int) Glass.VIEW_GET_X.invokeExact(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkView__1getY}: the content's y in its window, 0 without one. */
    static int viewY(long view) {
        try {
            return (int) Glass.VIEW_GET_Y.invokeExact(MemorySegment.ofAddress(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkView__1setParent}: the view's window, then {@code notify_view} with
     * {@code REMOVE} or {@code ADD} for the view's own id - the JNI called {@code notifyView} on the {@code View}
     * the native ran for, which is that id's peer.
     */
    static void viewSetParent(long view, long parent) {
        try {
            Glass.VIEW_SET_PARENT.invokeExact(MemorySegment.ofAddress(view), MemorySegment.ofAddress(parent));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkView__1close}: deletes the {@code GlassView}; {@code true}. */
    static boolean viewClose(long view) {
        try {
            return (int) Glass.VIEW_CLOSE.invokeExact(MemorySegment.ofAddress(view)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkView__1uploadPixelsDirect} ({@code GlassView.cpp}): nothing for a view of
     * 0 or a {@code null} buffer, the JNI's first two returns; otherwise the paint from the buffer's base address -
     * what {@code GetDirectBufferAddress} gave, its position ignored, {@code NULL} for a buffer that is not direct.
     */
    static void viewUploadPixelsDirect(long view, Buffer pixels, int width, int height) {
        if (view == 0 || pixels == null) {
            return;
        }
        MemorySegment data = pixels.isDirect() ? directBufferBase(pixels) : MemorySegment.NULL;
        try {
            Glass.VIEW_UPLOAD_PIXELS_DIRECT.invokeExact(MemorySegment.ofAddress(view), data, width, height);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkView__1uploadPixelsIntArray} ({@code GlassView.cpp}): nothing for a view of
     * 0 or a {@code null} array, the JNI's first two returns; otherwise {@code ggtk_view_upload_pixels_int}, which
     * checks the frame's bounds and paints it ({@code WindowContextBase::paint}: {@code gdk_window_begin_paint_region},
     * cairo, {@code gdk_window_end_paint}).
     * <p>
     * The JNI painted from the array it held with {@code GetPrimitiveArrayCritical}, its thread in native, where the
     * VM does not wait for it: a safepoint requested meanwhile went ahead (G1 pins the array's region). The paint
     * writes a whole frame to the X server, and a {@link Linker.Option#critical critical} downcall with heap access,
     * which would hand the C the array in place, keeps its thread in Java for as long as the call runs - every
     * safepoint of the VM would wait for the paint. So the frame is copied instead: the {@code width * height} ints
     * at {@code offset}, the part the C paints, go into {@code staging}, a native block of the view, and the C gets
     * that block as an array of exactly the frame's length at offset 0, through an ordinary downcall. Its checks
     * then pass exactly when they passed on the array itself: the copy is made only for a frame inside the array
     * ({@link #frameLength}, the C's own test), and a frame outside it is neither copied nor handed over, where the
     * C returned without painting.
     */
    static void viewUploadPixelsInt(long view, UploadStaging staging, int[] pixels, int offset, int width,
                                    int height) {
        if (view == 0 || pixels == null) {
            return;
        }
        int length = frameLength(pixels.length, offset, width, height, 1);
        if (length < 0) {
            return;
        }
        synchronized (staging) {
            MemorySegment frame = staging.copyIn(MemorySegment.ofArray(pixels), (long) offset * Integer.BYTES,
                    (long) length * Integer.BYTES);
            try {
                Glass.VIEW_UPLOAD_PIXELS_INT.invokeExact(MemorySegment.ofAddress(view), frame, length, 0, width,
                        height);
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkView__1uploadPixelsByteArray} ({@code GlassView.cpp}):
     * {@link #viewUploadPixelsInt} over bytes - the {@code 4 * width * height} bytes at {@code offset} - through
     * {@code ggtk_view_upload_pixels_byte}, copied into {@code staging} for the same reason.
     */
    static void viewUploadPixelsByte(long view, UploadStaging staging, byte[] pixels, int offset, int width,
                                     int height) {
        if (view == 0 || pixels == null) {
            return;
        }
        int length = frameLength(pixels.length, offset, width, height, 4);
        if (length < 0) {
            return;
        }
        synchronized (staging) {
            MemorySegment frame = staging.copyIn(MemorySegment.ofArray(pixels), offset, length);
            try {
                Glass.VIEW_UPLOAD_PIXELS_BYTE.invokeExact(MemorySegment.ofAddress(view), frame, length, 0, width,
                        height);
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    /**
     * The number of array elements of a frame of {@code width * height} pixels of {@code unit} elements each, at
     * {@code offset} in an array of {@code length}; -1 where {@code ggtk_view_upload_pixels_int} ({@code unit} 1) or
     * {@code _byte} ({@code unit} 4) refuses the frame: a negative offset, a width or height that is not positive,
     * or {@code unit * width * height + offset} beyond the array - including where that sum overflows an
     * {@code int}, which the C tests first ({@code width > ((INT_MAX - offset) / unit) / height}) and which exceeds
     * any array length.
     */
    static int frameLength(int length, int offset, int width, int height, int unit) {
        if (offset < 0 || width <= 0 || height <= 0) {
            return -1;
        }
        long pixels = (long) width * height;
        if (pixels > ((long) length - offset) / unit) {
            return -1;
        }
        return (int) (pixels * unit);
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkView__1enterFullscreen}: {@code gtk_window_fullscreen} and
     * {@code notify_view(FULLSCREEN_ENTER)}; {@code false} when that threw, as {@code CHECK_JNI_EXCEPTION_RET}
     * answered.
     */
    static boolean viewEnterFullscreen(long view, boolean animate, boolean keepRatio, boolean hideCursor) {
        try {
            return (int) Glass.VIEW_ENTER_FULLSCREEN.invokeExact(MemorySegment.ofAddress(view), animate ? 1 : 0,
                    keepRatio ? 1 : 0, hideCursor ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkView__1exitFullscreen}: out of full screen, {@code FULLSCREEN_EXIT}. */
    static void viewExitFullscreen(long view, boolean animate) {
        try {
            Glass.VIEW_EXIT_FULLSCREEN.invokeExact(MemorySegment.ofAddress(view), animate ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private GtkViewCalls() {
    }
}
