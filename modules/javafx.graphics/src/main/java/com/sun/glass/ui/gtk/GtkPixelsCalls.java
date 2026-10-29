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

import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Size;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK_PIXBUF;
import static com.sun.glass.ui.gtk.GtkGlassNative.GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.GOBJECT;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK_PIXBUF;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GLIB;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GOBJECT;
import static com.sun.glass.ui.gtk.GtkGlassNative.address;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.bounded;
import static com.sun.glass.ui.gtk.GtkGlassNative.reportException;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The pixels and cursors of the Linux glass: {@code GtkPixels._attachInt} and {@code _attachByte}
 * ({@code GlassPixels.cpp} at commit {@code 033187ad90}), {@code GtkCursor._createCursor} and {@code _getBestSize}
 * ({@code GlassCursor.cpp}). The other aggregates use its {@code g_malloc}, {@code gdk_display_get_default} and
 * {@code g_object_unref} handles, its pixel conversion and {@link #PIXELS_ATTACH_DATA}: they are declared here
 * because this section binds them first ({@link GtkGlassNative#link}).
 */
final class GtkPixelsCalls {

    /** {@code GDK_COLORSPACE_RGB} of {@code gdk-pixbuf-core.h}. */
    static final int GDK_COLORSPACE_RGB = 0;

    private static final ValueLayout.OfInt RGBA_BYTES = JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** {@code gpointer g_malloc(gsize n_bytes)}. */
    static final MethodHandle G_MALLOC = bind(GLIB, LIB_GLIB, "g_malloc",
            FunctionDescriptor.of(ADDRESS, JAVA_LONG));

    /**
     * {@code void g_free(gpointer mem)}, passed as the {@code GdkPixbufDestroyNotify} of every pixbuf an attach
     * creates. The C passed {@code my_free(guchar *pixels, gpointer data)}, whose whole body was
     * {@code g_free(pixels)}; {@code g_free} ignores the second argument the notify receives.
     */
    private static final MemorySegment G_FREE = address(GLIB, LIB_GLIB, "g_free");

    /**
     * {@code GdkPixbuf *gdk_pixbuf_new_from_data(const guchar *data, GdkColorspace colorspace, gboolean has_alpha,
     * int bits_per_sample, int width, int height, int rowstride, GdkPixbufDestroyNotify destroy_fn,
     * gpointer destroy_fn_data)}.
     */
    private static final MethodHandle GDK_PIXBUF_NEW_FROM_DATA = bind(GDK_PIXBUF, LIB_GDK_PIXBUF,
            "gdk_pixbuf_new_from_data", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    /** {@code GdkDisplay *gdk_display_get_default(void)}. */
    static final MethodHandle GDK_DISPLAY_GET_DEFAULT = bind(GDK, LIB_GDK, "gdk_display_get_default",
            FunctionDescriptor.of(ADDRESS));

    /** {@code GdkCursor *gdk_cursor_new_from_pixbuf(GdkDisplay *display, GdkPixbuf *pixbuf, gint x, gint y)}. */
    private static final MethodHandle GDK_CURSOR_NEW_FROM_PIXBUF = bind(GDK, LIB_GDK, "gdk_cursor_new_from_pixbuf",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    /** {@code void g_object_unref(gpointer object)}. */
    static final MethodHandle G_OBJECT_UNREF = bind(GOBJECT, LIB_GOBJECT, "g_object_unref",
            FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code guint gdk_display_get_default_cursor_size(GdkDisplay *display)}. */
    private static final MethodHandle GDK_DISPLAY_GET_DEFAULT_CURSOR_SIZE = bind(GDK, LIB_GDK,
            "gdk_display_get_default_cursor_size", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * The private {@code Pixels.attachData(long)}, which the C reached through the cached {@code jPixelsAttachData}
     * of {@code glass_general.cpp}; JNI ignores access, a private lookup in this module is the Java equivalent.
     */
    static final MethodHandle PIXELS_ATTACH_DATA = pixelsAttachData();

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkPixels__1attachInt} ({@code GlassPixels.cpp}), its guards in the C's order.
     * A heap buffer passed without its array has the capacity -1 {@code GetDirectBufferCapacity} gave it and fails
     * the bounds check. The C held the array with {@code GetPrimitiveArrayCritical} while it copied; here it is read
     * in place.
     */
    static void pixelsAttachInt(long ptr, int w, int h, IntBuffer ints, int[] array, int offset) {
        if (ptr == 0) {
            return;
        }
        if (array == null && ints == null) {
            return;
        }
        if (offset < 0) {
            return;
        }
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w > (((Integer.MAX_VALUE - offset) / 4) / h)) {
            return;
        }
        int numElem = array == null ? directBufferCapacity(ints) : array.length;
        if ((w * h + offset) > numElem) {
            return;
        }
        MemorySegment data = array == null ? directBufferBase(ints) : MemorySegment.ofArray(array);
        attachPixbuf(ptr, data, 4L * offset, w, h);
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkPixels__1attachByte} ({@code GlassPixels.cpp}): {@link #pixelsAttachInt}
     * over bytes. The pixels are still read as native-order {@code int}s, which assumes the little-endian layout of
     * {@code BYTE_BGRA_PRE}.
     */
    static void pixelsAttachByte(long ptr, int w, int h, ByteBuffer bytes, byte[] array, int offset) {
        if (ptr == 0) {
            return;
        }
        if (array == null && bytes == null) {
            return;
        }
        if (offset < 0) {
            return;
        }
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w > (((Integer.MAX_VALUE - offset) / 4) / h)) {
            return;
        }
        int numElem = array == null ? directBufferCapacity(bytes) : array.length;
        if ((w * h * 4 + offset) > numElem) {
            return;
        }
        MemorySegment data = array == null ? directBufferBase(bytes) : MemorySegment.ofArray(array);
        attachPixbuf(ptr, data, offset, w, h);
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkCursor__1createCursor} ({@code GlassCursor.cpp}). The exception of
     * {@code attachData} is reported as {@code EXCEPTION_OCCURED} reported it, and the pixbuf is unreffed in every
     * case: on that path {@code g_object_unref(NULL)} and GLib's critical warning, as before. A {@code null}
     * {@code pixels} is reported as the {@code NullPointerException} without a message that {@code CallVoidMethod}
     * on {@code NULL} left.
     *
     * @return the {@code GdkCursor *}, or 0
     */
    static long cursorCreate(int x, int y, Pixels pixels) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment slot = call.allocate(ADDRESS);
            boolean threw = false;
            try {
                if (pixels == null) {
                    throw new NullPointerException();
                }
                PIXELS_ATTACH_DATA.invokeExact(pixels, slot.address());
            } catch (Throwable t) {
                reportException(t);
                threw = true;
            }
            MemorySegment pixbuf = slot.get(ADDRESS, 0);
            MemorySegment cursor = MemorySegment.NULL;
            if (!threw) {
                MemorySegment display = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
                cursor = (MemorySegment) GDK_CURSOR_NEW_FROM_PIXBUF.invokeExact(display, pixbuf, x, y);
            }
            G_OBJECT_UNREF.invokeExact(pixbuf);
            return cursor.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkCursor__1getBestSize} ({@code GlassCursor.cpp}): the default cursor size,
     * whatever was asked for. A throwing {@code Size} constructor was reported ({@code EXCEPTION_OCCURED}) and
     * answered {@code null}.
     */
    static Size cursorBestSize(int width, int height) {
        int size;
        try {
            MemorySegment display = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
            size = (int) GDK_DISPLAY_GET_DEFAULT_CURSOR_SIZE.invokeExact(display);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        try {
            return new Size(size, size);
        } catch (Throwable t) {
            reportException(t);
            return null;
        }
    }

    /** The shared tail of both attaches; {@code ptr} is a {@code GdkPixbuf **}. */
    private static void attachPixbuf(long ptr, MemorySegment data, long offset, int w, int h) {
        MemorySegment rgba = convertBgraToRgba(data, offset, w * 4, h);
        if (rgba.address() != 0) {
            try {
                MemorySegment pixbuf = (MemorySegment) GDK_PIXBUF_NEW_FROM_DATA.invokeExact(rgba, GDK_COLORSPACE_RGB,
                        1, 8, w, h, w * 4, G_FREE, MemorySegment.NULL);
                bounded(MemorySegment.ofAddress(ptr), ADDRESS.byteSize()).set(ADDRESS, 0, pixbuf);
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    /**
     * {@code convert_BGRA_to_RGBA} ({@code glass_general.cpp}): a {@code g_malloc}ed buffer, or {@code NULL}. Each
     * native-order {@code int} of {@code pixels} becomes the bytes R = {@code >>16}, G = {@code >>8}, B = {@code >>0},
     * A = {@code >>24}, written as one little-endian {@code int}, which puts exactly those bytes at exactly those
     * addresses on any byte order, in one access instead of four.
     */
    static MemorySegment convertBgraToRgba(MemorySegment pixels, long offset, int stride, int height) {
        if (stride <= 0 || height <= 0 || (height > Integer.MAX_VALUE / stride)) {
            return MemorySegment.NULL;
        }
        int size = height * stride;
        MemorySegment out;
        try {
            out = (MemorySegment) G_MALLOC.invokeExact((long) size);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (out.address() == 0) {
            return MemorySegment.NULL;
        }
        out = bounded(out, size);
        for (int i = 0; i < size; i += 4) {
            int pixel = pixels.get(JAVA_INT_UNALIGNED, offset + i);
            out.set(RGBA_BYTES, i, (pixel & 0xFF00FF00) | ((pixel >> 16) & 0xFF) | ((pixel & 0xFF) << 16));
        }
        return out;
    }

    /** {@code GetDirectBufferCapacity}: the capacity of a direct buffer, -1 for any other. */
    private static int directBufferCapacity(Buffer buffer) {
        return buffer.isDirect() ? buffer.capacity() : -1;
    }

    /** {@code GetDirectBufferAddress}: the whole buffer from its base address, whatever its position and limit. */
    static MemorySegment directBufferBase(Buffer buffer) {
        return MemorySegment.ofBuffer(buffer.duplicate().clear());
    }

    private static MethodHandle pixelsAttachData() {
        try {
            return MethodHandles.privateLookupIn(Pixels.class, MethodHandles.lookup()).findVirtual(Pixels.class,
                    "attachData", MethodType.methodType(void.class, long.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private GtkPixelsCalls() {
    }
}
