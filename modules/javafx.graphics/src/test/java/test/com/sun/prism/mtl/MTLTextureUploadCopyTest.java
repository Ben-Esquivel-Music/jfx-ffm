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

package test.com.sun.prism.mtl;

import com.sun.prism.PixelFormat;
import com.sun.prism.mtl.MTLNativeShim;
import com.sun.prism.mtl.MTLNativeShim.TextureSourceRows;
import com.sun.prism.mtl.MTLNativeShim.TextureUpdateCopy;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Metal facade copies of a heap array for {@code mtl_texture_update}, and the arguments it passes with the
 * copy. {@code copyPixelDataToRingBuffer} (MetalTexture.m) must read from the copy the bytes it read from the array
 * the JNI pinned, and zeros where those leave the array; the copy holds no more than those rows. Pure Java: no library
 * is loaded, so this runs on every platform.
 */
public class MTLTextureUploadCopyTest {

    private final MTLNativeShim.Scratch scratch = new MTLNativeShim.Scratch();

    @AfterEach
    void closeScratch() {
        scratch.close();
    }

    @Test
    public void theTexelSizeIsThatOfTheMetalFormatTheTextureWasCreatedWith() {
        for (PixelFormat format : PixelFormat.values()) {
            int expected = switch (format) {
                case BYTE_ALPHA -> 1;
                case FLOAT_XYZW -> 16;
                default -> 4;
            };
            assertEquals(expected, MTLNativeShim.textureTexelBytes(format), format.name());
        }
    }

    @Test
    public void aFullUploadCopiesTheArrayAndKeepsTheCallersArguments() {
        assertEquals(new TextureSourceRows(0, 13 * 9 * 4, false, 13 * 9 * 4),
                MTLNativeShim.textureSourceRows(PixelFormat.INT_ARGB_PRE, 0, 0, 13, 9, 13 * 4));
        TextureUpdateCopy call = assertTheCReadsWhatItReadOfTheArray(pattern(13 * 9 * 4),
                PixelFormat.INT_ARGB_PRE, 0, 0, 13, 9, 13 * 4);
        assertEquals(0, call.baseOffset());
        assertEquals(13 * 4, call.scanBytes());
    }

    @Test
    public void aRegionWithGapsBetweenItsRowsCopiesOnlyTheRowsPacked() {
        assertEquals(new TextureSourceRows(2 * 64 + 3 * 4, 2 * 64 + 3 * 4 + 6 * 64 + 9 * 4, true, 7 * 9 * 4),
                MTLNativeShim.textureSourceRows(PixelFormat.INT_ARGB_PRE, 3, 2, 9, 7, 64));
        TextureUpdateCopy call = assertTheCReadsWhatItReadOfTheArray(pattern(11 * 64), PixelFormat.INT_ARGB_PRE,
                3, 2, 9, 7, 64);
        assertEquals(new TextureUpdateCopy(call.copy(), 0, 0, 0, 9 * 4), call);
        assertTheCReadsWhatItReadOfTheArray(pattern(9 * 13), PixelFormat.BYTE_ALPHA, 2, 1, 11, 6, 13);
        assertTheCReadsWhatItReadOfTheArray(pattern(10 * 144), PixelFormat.FLOAT_XYZW, 2, 3, 6, 5, 144);
    }

    /** A SwingNode repaint uploads a narrow strip or a caret out of its whole raster. */
    @Test
    public void aNarrowRectangleOfALargeArrayCopiesOnlyItsRows() {
        int stride = 2560 * 4;
        byte[] raster = pattern(stride * 1440);
        TextureSourceRows strip = MTLNativeShim.textureSourceRows(PixelFormat.INT_ARGB_PRE, 2540, 0, 20, 1440, stride);
        assertEquals(1439L * stride + 2540 * 4 + 20 * 4, strip.end());
        assertEquals(1440 * 20 * 4, strip.byteSize());
        assertTrue(strip.byteSize() <= MTLNativeShim.Scratch.retainedBytes());
        assertTheCReadsWhatItReadOfTheArray(raster, PixelFormat.INT_ARGB_PRE, 2540, 0, 20, 1440, stride);
        assertTheCReadsWhatItReadOfTheArray(raster, PixelFormat.INT_ARGB_PRE, 100, 700, 2, 20, stride);
    }

    /**
     * {@code MTLTexture} converts a {@code BYTE_RGB} or {@code BYTE_GRAY} region into an array of exactly
     * {@code w * h * 4} bytes but keeps the caller's origin, so the C reads {@code srcy * scan + srcx * 4} bytes past
     * the array (L20). Those bytes are zeros, also when an earlier call left the reused block dirty.
     */
    @Test
    public void aConvertedSubImageReadsZerosPastItsRegionSizedArray() {
        int w = 5;
        int h = 4;
        long start = 2 * 20 + 3 * 4;
        assertEquals(new TextureSourceRows(start, start + 3 * 20 + 5 * 4, false, w * h * 4),
                MTLNativeShim.textureSourceRows(PixelFormat.BYTE_RGB, 3, 2, w, h, w * 4));
        byte[] converted = pattern(w * h * 4);
        TextureUpdateCopy call = assertTheCReadsWhatItReadOfTheArray(converted, PixelFormat.BYTE_RGB, 3, 2, w, h,
                w * 4);
        assertEquals(new TextureUpdateCopy(call.copy(), -start, 3, 2, w * 4), call);
        assertTheCReadsWhatItReadOfTheArray(converted, PixelFormat.BYTE_GRAY, 3, 2, w, h, w * 4);
    }

    @Test
    public void overlappingRowsCopyTheSpanTheyShare() {
        assertEquals(new TextureSourceRows(1, 6, false, 5),
                MTLNativeShim.textureSourceRows(PixelFormat.BYTE_ALPHA, 1, 0, 5, 4, 0));
        assertTheCReadsWhatItReadOfTheArray(pattern(8), PixelFormat.BYTE_ALPHA, 1, 0, 5, 4, 0);
        assertTheCReadsWhatItReadOfTheArray(pattern(64), PixelFormat.BYTE_ALPHA, 2, 3, 9, 4, 6);
    }

    @Test
    public void aNegativeStrideReadsTheRowsAboveTheFirst() {
        assertEquals(new TextureSourceRows(0, 36, false, 36),
                MTLNativeShim.textureSourceRows(PixelFormat.BYTE_ALPHA, 0, -2, 4, 3, -16));
        assertTheCReadsWhatItReadOfTheArray(pattern(48), PixelFormat.BYTE_ALPHA, 0, -2, 4, 3, -16);
    }

    /**
     * The C adds {@code srcx * 4} as an {@code unsigned int} and multiplies offsets in {@code int}: such arguments
     * reach outside the array behind the JNI as well, so they are passed unchanged with the copy's address moved
     * back, never repaired by packing.
     */
    @Test
    public void argumentsTheCDoesNotComputeExactlyAreNeverPacked() {
        assertFalse(MTLNativeShim.textureSourceRows(PixelFormat.INT_ARGB_PRE, -1, 0, 2, 2, 64).packed());
        assertFalse(MTLNativeShim.textureSourceRows(PixelFormat.INT_ARGB_PRE, 0, -1, 2, 2, 64).packed());
        assertFalse(MTLNativeShim.textureSourceRows(PixelFormat.FLOAT_XYZW, 0, 9000, 2, 2, 16384 * 16).packed());
        TextureUpdateCopy call = scratch.textureUpdateCopyInCall(pattern(128), PixelFormat.INT_ARGB_PRE, -1, 0, 2,
                2, 64);
        assertEquals(new TextureUpdateCopy(call.copy(), 4, -1, 0, 64), call);
        scratch.endCall();
    }

    @Test
    public void anEmptyRegionCopiesNothingButIsNeverNull() {
        long first = 3 * 64 + 2 * 4;
        for (int[] size : new int[][] {{0, 7}, {9, 0}, {-1, -1}}) {
            assertEquals(new TextureSourceRows(first, first, false, 0),
                    MTLNativeShim.textureSourceRows(PixelFormat.INT_ARGB_PRE, 2, 3, size[0], size[1], 64));
        }
        TextureUpdateCopy call = scratch.textureUpdateCopyInCall(pattern(64 * 8), PixelFormat.INT_ARGB_PRE, 2, 3, 0,
                7, 64);
        assertEquals(0, call.copy().byteSize());
        assertNotEquals(0L, call.copy().address());
        assertEquals(new TextureUpdateCopy(call.copy(), -first, 2, 3, 64), call);
        scratch.endCall();
    }

    /**
     * Copies as the facade does, into a reused block an earlier call filled with ones, then reads the copy the way
     * {@code copyPixelDataToRingBuffer} reads the address and arguments the facade passes. A read outside the copy
     * fails with {@link IndexOutOfBoundsException}.
     */
    private TextureUpdateCopy assertTheCReadsWhatItReadOfTheArray(byte[] array, PixelFormat textureFormat, int srcx,
                                                                  int srcy, int w, int h, int scan) {
        int[] dirty = new int[(int) (MTLNativeShim.Scratch.retainedBytes() / Integer.BYTES)];
        Arrays.fill(dirty, -1);
        scratch.copiesInCall(dirty);
        scratch.endCall();
        TextureUpdateCopy call = scratch.textureUpdateCopyInCall(array, textureFormat, srcx, srcy, w, h, scan);
        int texel = MTLNativeShim.textureTexelBytes(textureFormat);
        long jniFirst = (long) srcy * scan + (long) srcx * texel;
        long copyFirst = call.baseOffset() + (long) call.srcy() * call.scanBytes() + (long) call.srcx() * texel;
        for (int row = 0; row < h; row++) {
            for (int b = 0; b < w * texel; b++) {
                long offset = jniFirst + (long) row * scan + b;
                byte expected = offset >= 0 && offset < array.length ? array[(int) offset] : 0;
                byte actual = call.copy().get(JAVA_BYTE, copyFirst + (long) row * call.scanBytes() + b);
                if (actual != expected) {
                    assertEquals(expected, actual, "row " + row + ", byte " + b);
                }
            }
        }
        scratch.endCall();
        return call;
    }

    /** Bytes that are never 0, so that a zero read back can only come from outside the array. */
    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (1 + i % 251);
        }
        return bytes;
    }
}
