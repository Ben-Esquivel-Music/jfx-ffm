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

package com.sun.prism.mtl;

import com.sun.prism.PixelFormat;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandle;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import static com.sun.prism.mtl.MTLNative.bind;
import static com.sun.prism.mtl.MTLNative.directBase;
import static com.sun.prism.mtl.MTLNative.flag;
import static com.sun.prism.mtl.MTLNative.seg;
import static com.sun.prism.mtl.MTLNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The {@code mtl_texture_*} and {@code mtl_rtt_*} functions: the natives of {@code MTLTexture}, {@code MTLRTTexture}
 * and {@code MTLResourceFactory.nCreateTexture}, whose JNI bodies were in {@code MetalTexture.m},
 * {@code MetalRTTexture.m} and {@code MetalContext.m}.
 */
final class MTLTextureCalls {

    private static final MethodHandle MTL_TEXTURE_CREATE = bind("mtl_texture_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_TEXTURE_UPDATE = bind("mtl_texture_update",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT));
    private static final MethodHandle MTL_TEXTURE_UPDATE_YUV422 = bind("mtl_texture_update_yuv422",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT));
    private static final MethodHandle MTL_RTT_CREATE = bind("mtl_rtt_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_RTT_WRAP = bind("mtl_rtt_wrap",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_RTT_INIT = bind("mtl_rtt_init",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle MTL_RTT_READ_PIXELS = bind("mtl_rtt_read_pixels",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle MTL_RTT_READ_PIXELS_CONTIGUOUS = bind("mtl_rtt_read_pixels_contiguous",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    private MTLTextureCalls() {
    }

    /** {@code MTLResourceFactory.nCreateTexture}, without the hint, isRTT and samples the C ignored. */
    static long textureCreate(long ctx, int pixelFormat, int width, int height, boolean useMipmap) {
        try {
            return ((MemorySegment) MTL_TEXTURE_CREATE.invokeExact(seg(ctx), pixelFormat, width, height,
                    flag(useMipmap))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLTexture.nUpdate}, for a texture created with {@code textureFormat}. */
    static void textureUpdate(MTLScratch scratch, long tex, PixelFormat textureFormat, ByteBuffer buffer,
                              byte[] pixels, int dstx, int dsty, int srcx, int srcy, int width, int height,
                              int scanBytes) {
        update(scratch, tex, textureFormat, buffer, pixels == null ? null : MemorySegment.ofArray(pixels),
                dstx, dsty, srcx, srcy, width, height, scanBytes);
    }

    /** {@code MTLTexture.nUpdateFloat}, for a texture created with {@code textureFormat}. */
    static void textureUpdateFloat(MTLScratch scratch, long tex, PixelFormat textureFormat, FloatBuffer buffer,
                                   float[] pixels, int dstx, int dsty, int srcx, int srcy, int width, int height,
                                   int scanBytes) {
        update(scratch, tex, textureFormat, buffer, pixels == null ? null : MemorySegment.ofArray(pixels),
                dstx, dsty, srcx, srcy, width, height, scanBytes);
    }

    /** {@code MTLTexture.nUpdateInt}, for a texture created with {@code textureFormat}. */
    static void textureUpdateInt(MTLScratch scratch, long tex, PixelFormat textureFormat, IntBuffer buffer,
                                 int[] pixels, int dstx, int dsty, int srcx, int srcy, int width, int height,
                                 int scanBytes) {
        update(scratch, tex, textureFormat, buffer, pixels == null ? null : MemorySegment.ofArray(pixels),
                dstx, dsty, srcx, srcy, width, height, scanBytes);
    }

    /** {@code MTLTexture.nUpdateYUV422}; the copy is the one {@code GetByteArrayElements} made. */
    static void textureUpdateYuv422(long tex, byte[] pixels, int dstx, int dsty, int srcx, int srcy,
                                    int width, int height, int scanBytes) {
        try (Arena call = Arena.ofConfined()) {
            MTL_TEXTURE_UPDATE_YUV422.invokeExact(seg(tex), call.allocateFrom(JAVA_BYTE, pixels),
                    dstx, dsty, srcx, srcy, width, height, scanBytes);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLRTTexture.nCreateRT}, without the wrap mode the C ignored. */
    static long rttCreate(long ctx, int physicalWidth, int physicalHeight, int contentWidth, int contentHeight,
                          boolean msaa) {
        try {
            return ((MemorySegment) MTL_RTT_CREATE.invokeExact(seg(ctx), physicalWidth, physicalHeight,
                    contentWidth, contentHeight, flag(msaa))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLRTTexture.nCreateRT2}: wraps the {@code id<MTLTexture>} of a Glass view, which Glass keeps owning. */
    static long rttWrap(long ctx, long mtlTexture, int physicalWidth, int physicalHeight) {
        try {
            return ((MemorySegment) MTL_RTT_WRAP.invokeExact(seg(ctx), seg(mtlTexture), physicalWidth,
                    physicalHeight)).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code MTLRTTexture.nInitRTT}. The copy is as long as the array, as {@code GetIntArrayElements}' was: the C
     * reads physical width x height ints from it while the array is content-sized, so under
     * {@code prism.forcepow2} it reads past the copy, as it read past the JNI's.
     */
    static void rttInit(long rtt, int[] pixels) {
        try (Arena call = Arena.ofConfined()) {
            MTL_RTT_INIT.invokeExact(seg(rtt), call.allocateFrom(JAVA_INT, pixels));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLRTTexture.nReadPixels}, with the copy in and back of {@code GetIntArrayElements}. */
    static void rttReadPixels(long rtt, int[] pixels) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment copy = call.allocateFrom(JAVA_INT, pixels);
            MTL_RTT_READ_PIXELS.invokeExact(seg(rtt), copy);
            MemorySegment.copy(copy, JAVA_INT, 0, pixels, 0, pixels.length);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLRTTexture.nReadPixelsFromRTT}. A heap buffer gives the C a {@code NULL} destination, as before. */
    static void rttReadPixelsContiguous(long rtt, IntBuffer pixels) {
        try {
            MTL_RTT_READ_PIXELS_CONTIGUOUS.invokeExact(seg(rtt), directBase(pixels));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The JNI gave the C the base of the array, whatever the buffer's position and array offset, or else the base of
     * the direct buffer. A heap buffer without an accessible array gave it a {@code NULL} it dereferences, and still
     * does. Of an array, only the {@link SourceRows} the C reads are copied.
     */
    private static void update(MTLScratch scratch, long tex, PixelFormat textureFormat, Buffer buffer,
                               MemorySegment array, int dstx, int dsty, int srcx, int srcy, int width, int height,
                               int scanBytes) {
        if (array == null) {
            invokeUpdate(tex, directBase(buffer), dstx, dsty, srcx, srcy, width, height, scanBytes);
            return;
        }
        SourceRows rows = SourceRows.of(textureFormat, srcx, srcy, width, height, scanBytes);
        SegmentAllocator copies = scratch.begin(rows.byteSize());
        try {
            SourceRows.Copy copy = rows.copyOf(array, copies);
            invokeUpdate(tex, copy.pixels(), dstx, dsty, copy.srcx(), copy.srcy(), width, height, copy.scanBytes());
        } finally {
            scratch.end();
        }
    }

    private static void invokeUpdate(long tex, MemorySegment pixels, int dstx, int dsty, int srcx, int srcy,
                                     int width, int height, int scanBytes) {
        try {
            MTL_TEXTURE_UPDATE.invokeExact(seg(tex), pixels, dstx, dsty, srcx, srcy, width, height, scanBytes);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * What {@code copyPixelDataToRingBuffer} (MetalTexture.m) reads from the {@code pixels} of
     * {@code mtl_texture_update}: {@code height} rows of {@code width} texels, {@code scanBytes} apart, the first at
     * {@code srcy * scanBytes + srcx * texelBytes}. {@link #start} and {@link #end} bound them as offsets from
     * {@code pixels}; both are that first offset when {@code width} or {@code height} is not positive. Origin and
     * stride are used nowhere else in the C.
     * <p>
     * When the rows leave gaps, as a dirty rectangle or a narrow tile of a wide image does, only the rows are copied,
     * {@link #packed} one after the other, and the C gets origin 0 and a stride of one row: it reads the same bytes in
     * the same order. Otherwise {@code [start, end)} is copied, and the C gets the caller's origin and stride with an
     * address {@code start} bytes before the copy, so that its own arithmetic lands on the copy.
     * <p>
     * Negative origins and offsets past 2 GiB are never packed. The C reads before the array for a negative
     * {@code srcy}, adds a negative {@code srcx} as an {@code unsigned int}, about 4 GiB on, and overflows its
     * {@code int} arithmetic past 2 GiB. Each made the JNI read outside the array as well, so none is a parity case;
     * passing them unchanged keeps the C's arithmetic, where packing would repair them.
     * <p>
     * It uses nothing of {@link MTLTextureCalls} or {@link MTLNative}, so a test initializes it without loading
     * {@code prism_mtl}.
     */
    record SourceRows(int srcx, int srcy, int width, int height, int scanBytes, int texelBytes) {

        static SourceRows of(PixelFormat textureFormat, int srcx, int srcy, int width, int height, int scanBytes) {
            return new SourceRows(srcx, srcy, width, height, scanBytes, texelBytes(textureFormat));
        }

        /**
         * The texel size {@code getPixelSize} (MetalTexture.m) gives the {@code MTLPixelFormat} that
         * {@code mtl_texture_create} or {@code mtl_rtt_create} chose for a texture of {@code textureFormat}: A8 for
         * {@code BYTE_ALPHA}, RGBA32Float for {@code FLOAT_XYZW} and BGRA8 for every other format, which includes
         * the {@code BYTE_BGRA_PRE} of render targets and the formats that fall into the {@code default} of the C
         * switch.
         */
        static int texelBytes(PixelFormat textureFormat) {
            return switch (textureFormat) {
                case BYTE_ALPHA -> 1;
                case FLOAT_XYZW -> 16;
                default -> 4;
            };
        }

        long start() {
            return isEmpty() ? first() : first() + Math.min(lastRow(), 0);
        }

        long end() {
            return isEmpty() ? first() : first() + Math.max(lastRow(), 0) + rowBytes();
        }

        boolean packed() {
            return !isEmpty() && scanBytes > rowBytes() && srcx >= 0 && srcy >= 0 && end() <= Integer.MAX_VALUE;
        }

        long byteSize() {
            return packed() ? height * rowBytes() : end() - start();
        }

        /** The copy, with zeros for the bytes outside {@code array}, and the arguments that make the C read it. */
        Copy copyOf(MemorySegment array, SegmentAllocator allocator) {
            MemorySegment copy = allocator.allocate(byteSize());
            if (packed()) {
                long rowBytes = rowBytes();
                for (int row = 0; row < height; row++) {
                    copyZeroPadded(array, first() + row * (long) scanBytes, copy.asSlice(row * rowBytes, rowBytes));
                }
                return new Copy(copy, 0, 0, 0, Math.toIntExact(rowBytes));
            }
            copyZeroPadded(array, start(), copy);
            return new Copy(copy, -start(), srcx, srcy, scanBytes);
        }

        private boolean isEmpty() {
            return width <= 0 || height <= 0;
        }

        private long first() {
            return (long) srcy * scanBytes + (long) srcx * texelBytes;
        }

        private long lastRow() {
            return (long) (height - 1) * scanBytes;
        }

        private long rowBytes() {
            return (long) width * texelBytes;
        }

        /**
         * The bytes of {@code array} from {@code offset} into all of {@code target}, zeros where they fall outside
         * the array. A {@code BYTE_RGB} or {@code BYTE_GRAY} sub-image reads past its region-sized array (L20 of the
         * audit), where the JNI read the Java heap after the pinned array. The zeros are written explicitly because
         * the scratch hands out a block an earlier call left dirty.
         */
        private static void copyZeroPadded(MemorySegment array, long offset, MemorySegment target) {
            long size = target.byteSize();
            long from = Math.clamp(-offset, 0, size);
            long to = Math.clamp(array.byteSize() - offset, from, size);
            target.asSlice(0, from).fill((byte) 0);
            if (to > from) {
                MemorySegment.copy(array, offset + from, target, from, to - from);
            }
            target.asSlice(to).fill((byte) 0);
        }

        /** A copy, and the origin and stride the C gets with the address {@code baseOffset} bytes from the copy. */
        record Copy(MemorySegment copy, long baseOffset, int srcx, int srcy, int scanBytes) {

            MemorySegment pixels() {
                return MemorySegment.ofAddress(copy.address() + baseOffset);
            }
        }
    }
}
