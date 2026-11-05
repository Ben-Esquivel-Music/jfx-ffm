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

import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Size;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_CURSOR_BEST_SIZE;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_CURSOR_CREATE;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_CURSOR_INIT;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_CURSOR_SET;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_CURSOR_SET_CUSTOM;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_CURSOR_SET_VISIBLE;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_IMAGE_CREATE;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The natives of {@code MacPixels} and {@code MacCursor}: the bounds checks of the JNI {@code _attachInt} and
 * {@code _attachByte}, in their order, in front of {@code gmac_image_create}, and the {@code gmac_cursor_*} calls.
 * <p>
 * Each call evaluates {@code gmac_main_thread_detached} where its JNI body ran {@code GLASS_ASSERT_MAIN_JAVA_THREAD},
 * and reports the error where the {@code GLASS_CHECK_EXCEPTION} that followed on that body's C path did; none throws
 * it. None of these calls may be critical: {@code gmac_image_create} allocates by the caller's size, and the cursor
 * calls run AppKit.
 */
final class MacCursorCalls {

    /** {@code gmac_cursor_create}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface CursorFactory {

        long create(MemorySegment image, int x, int y);
    }

    /** {@code gmac_cursor_best_size}, so that a test can stand where AppKit would. */
    @FunctionalInterface
    interface BestSizeQuery {

        void query(int width, int height, MemorySegment outWidth, MemorySegment outHeight);
    }

    private static final MethodHandle PIXELS_ATTACH_DATA = pixelsAttachData();

    private MacCursorCalls() {
    }

    /**
     * {@code MacPixels._attachInt}: writes to the slot {@code ptr} a +1 {@code NSImage*} of the
     * {@code width * height} ints from {@code offset}, or NULL; leaves the slot alone where the JNI returned early.
     */
    static void attachInt(long ptr, int width, int height, IntBuffer ints, int[] array, int offset) {
        if (acceptsInts(ptr, width, height, ints, array, offset)) {
            attach(ptr, width, height, array != null ? MemorySegment.ofArray(array) : null, ints,
                    Integer.BYTES * (long) offset);
        }
    }

    /** {@code MacPixels._attachByte}, as {@link #attachInt}, with {@code offset} counting bytes. */
    static void attachByte(long ptr, int width, int height, ByteBuffer bytes, byte[] array, int offset) {
        if (acceptsBytes(ptr, width, height, bytes, array, offset)) {
            attach(ptr, width, height, array != null ? MemorySegment.ofArray(array) : null, bytes, offset);
        }
    }

    /** {@code MacCursor}'s class initialization. The JNI {@code _initIDs} had no detached assertion. */
    static void initCursors() {
        try {
            GMAC_CURSOR_INIT.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static long createCursor(int x, int y, Pixels pixels) {
        return createCursor(x, y, pixels, MacCursorCalls::cursorCreate);
    }

    /**
     * {@code MacCursor._createCursor}: {@code Pixels.attachData} writes the image of {@code pixels} into a slot, and
     * {@code factory} takes it; the exception the JNI left pending is reported after the cursor exists.
     * <p>
     * While detached, the JNI's assertion left its exception pending. HotSpot's {@code JNI_ENTRY} set it aside for the
     * {@code attachData} call and restored it afterwards unless {@code attachData} threw, whose exception then took
     * its place. So {@code attachData} ran, detached in turn, the cursor was made from the image it left, and the
     * closing {@code GLASS_CHECK_EXCEPTION} reported the one exception left pending.
     */
    static long createCursor(int x, int y, Pixels pixels, CursorFactory factory) {
        Throwable pending = mainThreadDetached() ? mainThreadDetachedError() : null;
        MemorySegment image;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(ADDRESS);
            try {
                attachData(pixels, slot);
            } catch (Throwable t) {
                pending = t;
            }
            image = slot.get(ADDRESS, 0);
        }
        long cursor = factory.create(image, x, y);
        if (pending != null) {
            reportException(pending);
        }
        return cursor;
    }

    /** {@code Pixels.attachData(slot)}, the private method the JNI called: the image of {@code pixels}, or NULL. */
    static void attachData(Pixels pixels, MemorySegment slot) throws Throwable {
        PIXELS_ATTACH_DATA.invokeExact(pixels, slot.address());
    }

    /** {@code MacCursor._set}. */
    static void setCursor(int type) {
        boolean detached = mainThreadDetached();
        try {
            GMAC_CURSOR_SET.invokeExact(type);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
    }

    /** {@code MacCursor._setCustom}: {@code ptr} is a cursor {@link #createCursor} answered; 0 does nothing. */
    static void setCustomCursor(long ptr) {
        boolean detached = mainThreadDetached();
        try {
            GMAC_CURSOR_SET_CUSTOM.invokeExact(MemorySegment.ofAddress(ptr));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
    }

    /** {@code MacCursor._setVisible}: AppKit counts hides and unhides, so the caller balances them. */
    static void setCursorVisible(boolean visible) {
        boolean detached = mainThreadDetached();
        try {
            GMAC_CURSOR_SET_VISIBLE.invokeExact(visible ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
    }

    static Size bestSize(int width, int height) {
        return bestSize(width, height, MacCursorCalls::queryBestSize);
    }

    /**
     * {@code MacCursor._getBestSize}, the {@code Size} of what {@code query} wrote. While detached, the JNI's
     * {@code ClassForName} of {@code Size} found the class with the assertion's exception pending, and its
     * {@code GLASS_CHECK_EXCEPTION} reported the exception before the {@code Size} was made and returned.
     */
    static Size bestSize(int width, int height, BestSizeQuery query) {
        boolean detached = mainThreadDetached();
        int bestWidth;
        int bestHeight;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outWidth = arena.allocate(JAVA_INT);
            MemorySegment outHeight = arena.allocate(JAVA_INT);
            query.query(width, height, outWidth, outHeight);
            bestWidth = outWidth.get(JAVA_INT, 0);
            bestHeight = outHeight.get(JAVA_INT, 0);
        }
        reportIfDetached(detached);
        return new Size(bestWidth, bestHeight);
    }

    /** The JNI's checks, in its order; {@code 4 * offset} and the division by {@code height} only once they hold. */
    private static boolean acceptsInts(long ptr, int width, int height, IntBuffer ints, int[] array, int offset) {
        if (ptr == 0L || (array == null && ints == null) || offset < 0 || width <= 0 || height <= 0) {
            return false;
        }
        if (offset > Integer.MAX_VALUE / 4 || width > ((Integer.MAX_VALUE - 4 * offset) / 4) / height) {
            return false;
        }
        int elements = array != null ? array.length : directCapacity(ints);
        return width * height + offset <= elements;
    }

    /** The JNI's checks, in its order. Unlike {@link #acceptsInts} they have no quarter-range offset check. */
    private static boolean acceptsBytes(long ptr, int width, int height, ByteBuffer bytes, byte[] array, int offset) {
        if (ptr == 0L || (array == null && bytes == null) || offset < 0 || width <= 0 || height <= 0) {
            return false;
        }
        if (width > ((Integer.MAX_VALUE - offset) / 4) / height) {
            return false;
        }
        int elements = array != null ? array.length : directCapacity(bytes);
        return 4 * width * height + offset <= elements;
    }

    /** {@code GetDirectBufferCapacity}: -1 for a buffer that is not direct. */
    private static int directCapacity(Buffer buffer) {
        return buffer.isDirect() ? buffer.capacity() : -1;
    }

    /** The rest of the JNI's {@code attachCommon}, whose closing {@code GLASS_CHECK_EXCEPTION} reported. */
    private static void attach(long ptr, int width, int height, MemorySegment array, Buffer buffer, long byteOffset) {
        boolean detached = mainThreadDetached();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = pixels(arena, array, buffer, byteOffset, 4L * width * height);
            GMAC_IMAGE_CREATE.invokeExact(pixels, width, height, MemorySegment.ofAddress(ptr));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        reportIfDetached(detached);
    }

    /**
     * The pixels the JNI read. Of a heap array, a copy for the call of what it read under
     * {@code GetPrimitiveArrayCritical}. Of a direct buffer, its own memory from its base address, whatever its
     * position and limit, or NULL, whatever the offset, where {@code GetDirectBufferAddress} answered NULL.
     */
    private static MemorySegment pixels(Arena arena, MemorySegment array, Buffer buffer, long byteOffset,
                                        long byteSize) {
        if (array != null) {
            return arena.allocate(byteSize).copyFrom(array.asSlice(byteOffset, byteSize));
        }
        MemorySegment memory = MemorySegment.ofBuffer(buffer.duplicate().clear());
        return memory.address() == 0 ? MemorySegment.NULL : memory.asSlice(byteOffset);
    }

    private static long cursorCreate(MemorySegment image, int x, int y) {
        try {
            return ((MemorySegment) GMAC_CURSOR_CREATE.invokeExact(image, x, y)).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void queryBestSize(int width, int height, MemorySegment outWidth, MemorySegment outHeight) {
        try {
            GMAC_CURSOR_BEST_SIZE.invokeExact(width, height, outWidth, outHeight);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void reportIfDetached(boolean detached) {
        if (detached) {
            reportException(mainThreadDetachedError());
        }
    }

    /** {@code Pixels.attachData} is private: the JNI called it by name (g1 Risk 6). */
    private static MethodHandle pixelsAttachData() {
        try {
            return MethodHandles.privateLookupIn(Pixels.class, MethodHandles.lookup()).findVirtual(Pixels.class,
                    "attachData", MethodType.methodType(void.class, long.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
