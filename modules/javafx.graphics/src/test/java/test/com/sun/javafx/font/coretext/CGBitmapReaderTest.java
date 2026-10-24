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

package test.com.sun.javafx.font.coretext;

import com.sun.javafx.font.coretext.CoreTextPureShim;
import com.sun.javafx.font.coretext.CoreTextPureShim.Bitmap;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request guards, source checks and copy loops of {@code CGBitmapReader} over heap segments, on every OS: each
 * rule of the C it replaces, {@code CGBitmapContextGetData} and {@code CGImageContextGetData} in {@code coretext.c}.
 * Every guard answers false, so the order the C checked them in is not observable here. The reads of real contexts
 * are checked on macOS by {@code MacFontOSTest}.
 */
public class CGBitmapReaderTest {

    private static final MemorySegment CONTEXT = MemorySegment.ofAddress(0x1000);

    @Test
    void requestsWithoutPixelsAreRefused() {
        int[][] sizes = {{0, 1}, {1, 0}, {-1, 1}, {1, -1}, {Integer.MIN_VALUE, 1}, {1, Integer.MIN_VALUE}};
        for (int[] size : sizes) {
            for (int bpp : new int[] {8, 24}) {
                assertFalse(CoreTextPureShim.isMaskRequest(CONTEXT, size[0], size[1], bpp), size[0] + "x" + size[1]);
            }
            assertFalse(CoreTextPureShim.isBgraRequest(CONTEXT, size[0], size[1], 32), size[0] + "x" + size[1]);
        }
        assertTrue(CoreTextPureShim.isMaskRequest(CONTEXT, 1, 1, 8));
        assertTrue(CoreTextPureShim.isMaskRequest(CONTEXT, 1, 1, 24));
        assertTrue(CoreTextPureShim.isBgraRequest(CONTEXT, 1, 1, 32));
    }

    @Test
    void eachReaderAcceptsOnlyItsOwnBitsPerPixel() {
        for (int bpp : new int[] {0, 1, 7, 9, 16, 23, 25, 32, -8, -24, Integer.MIN_VALUE}) {
            assertFalse(CoreTextPureShim.isMaskRequest(CONTEXT, 4, 4, bpp), "mask bpp " + bpp);
        }
        for (int bpp : new int[] {0, 8, 24, 31, 33, 64, -32, Integer.MIN_VALUE}) {
            assertFalse(CoreTextPureShim.isBgraRequest(CONTEXT, 4, 4, bpp), "BGRA bpp " + bpp);
        }
    }

    @Test
    void aNullContextIsRefused() {
        assertFalse(CoreTextPureShim.isMaskRequest(MemorySegment.NULL, 1, 1, 8));
        assertFalse(CoreTextPureShim.isMaskRequest(MemorySegment.NULL, 1, 1, 24));
        assertFalse(CoreTextPureShim.isBgraRequest(MemorySegment.NULL, 1, 1, 32));
    }

    /**
     * floor((2^31 - 1) / n) is 715,827,882 for n = 3, 536,870,911 for 4, and 2,147,483, 715,827 and 536,870 for 1000,
     * 3000 and 4000.
     */
    @Test
    void theResultMustFitAnIntForEveryStepSize() {
        assertTrue(CoreTextPureShim.fitsAnArray(Integer.MAX_VALUE, 1, 1));
        assertFalse(CoreTextPureShim.fitsAnArray(Integer.MAX_VALUE, 2, 1));
        assertTrue(CoreTextPureShim.fitsAnArray(1000, 2_147_483, 1));
        assertFalse(CoreTextPureShim.fitsAnArray(1000, 2_147_484, 1));

        assertTrue(CoreTextPureShim.fitsAnArray(715_827_882, 1, 3));
        assertFalse(CoreTextPureShim.fitsAnArray(715_827_883, 1, 3));
        assertTrue(CoreTextPureShim.fitsAnArray(1000, 715_827, 3));
        assertFalse(CoreTextPureShim.fitsAnArray(1000, 715_828, 3));

        assertTrue(CoreTextPureShim.fitsAnArray(536_870_911, 1, 4));
        assertFalse(CoreTextPureShim.fitsAnArray(536_870_912, 1, 4));
        assertTrue(CoreTextPureShim.fitsAnArray(1000, 536_870, 4));
        assertFalse(CoreTextPureShim.fitsAnArray(1000, 536_871, 4));
    }

    @Test
    void theRequestGuardsApplyTheIntLimitOfTheirStepSize() {
        assertTrue(CoreTextPureShim.isMaskRequest(CONTEXT, 1000, 2_147_483, 8));
        assertFalse(CoreTextPureShim.isMaskRequest(CONTEXT, 1000, 2_147_484, 8));
        assertTrue(CoreTextPureShim.isMaskRequest(CONTEXT, 715_827_882, 1, 24));
        assertFalse(CoreTextPureShim.isMaskRequest(CONTEXT, 715_827_883, 1, 24));
        assertTrue(CoreTextPureShim.isMaskRequest(CONTEXT, 1000, 715_827, 24));
        assertFalse(CoreTextPureShim.isMaskRequest(CONTEXT, 1000, 715_828, 24));
        assertTrue(CoreTextPureShim.isBgraRequest(CONTEXT, 536_870_911, 1, 32));
        assertFalse(CoreTextPureShim.isBgraRequest(CONTEXT, 536_870_912, 1, 32));
        assertTrue(CoreTextPureShim.isBgraRequest(CONTEXT, 1000, 536_870, 32));
        assertFalse(CoreTextPureShim.isBgraRequest(CONTEXT, 1000, 536_871, 32));
    }

    @Test
    void aSourceWithoutEnoughPixelsGivesNull() {
        MemorySegment pixels = MemorySegment.ofArray(new byte[16]);
        assertSourceRefused(new Bitmap(MemorySegment.NULL, 4, 4, 4, 8), 4, 4, "NULL pixels");
        assertSourceRefused(new Bitmap(pixels, 3, 4, 4, 8), 4, 4, "too narrow");
        assertSourceRefused(new Bitmap(pixels, 4, 3, 4, 8), 4, 4, "too short");
        assertSourceRefused(new Bitmap(pixels, 4, 4, 0, 8), 4, 4, "no bytes per row");
        assertTrue(CoreTextPureShim.bitmapCovers(new Bitmap(pixels, 4, 4, 4, 8), 4, 4));
        assertTrue(CoreTextPureShim.bitmapCovers(new Bitmap(MemorySegment.ofArray(new byte[1]), 1, 1, 1, 8), 1, 1),
                   "a heap segment at offset 0 is not NULL");
    }

    /**
     * {@code (srcHeight - dstHeight) * bytesPerRow} must not wrap a {@code size_t}: 2^64 - 1 divided by 2^62 is 3,
     * and divided by 3 is 0x5555555555555555.
     */
    @Test
    void theOffsetOfTheBottomRowsMustNotWrap() {
        MemorySegment pixels = MemorySegment.ofArray(new byte[1]);
        assertTrue(CoreTextPureShim.bitmapCovers(new Bitmap(pixels, 1, 4, 1L << 62, 8), 1, 1));
        assertSourceRefused(new Bitmap(pixels, 1, 5, 1L << 62, 8), 1, 1, "2^62 x 4");
        assertTrue(CoreTextPureShim.bitmapCovers(new Bitmap(pixels, 1, 0x5555555555555556L, 3, 8), 1, 1));
        assertSourceRefused(new Bitmap(pixels, 1, 0x5555555555555557L, 3, 8), 1, 1, "3 x 0x5555555555555556");
    }

    @Test
    void greyBytesAreInvertedAsSignedChars() {
        byte[] grey = bytes(0x00, 0x7F, 0x80, 0xFF, 0x01, 0xFE);
        Bitmap source = new Bitmap(MemorySegment.ofArray(grey), 6, 1, 6, 8);
        assertArrayEquals(bytes(0xFF, 0x80, 0x7F, 0x00, 0xFE, 0x01),
                          CoreTextPureShim.invertBottomRows(source, 6, 1, 1));
    }

    @Test
    void aGreyMaskOfABgraSourceInvertsTheBlueByte() {
        byte[] bgra = bytes(0x10, 0x20, 0x30, 0x40, 0x80, 0x7F, 0xFF, 0x00);
        Bitmap source = new Bitmap(MemorySegment.ofArray(bgra), 2, 1, 8, 32);
        assertArrayEquals(bytes(0xEF, 0x7F), CoreTextPureShim.invertBottomRows(source, 2, 1, 1));
    }

    @Test
    void bgraBecomesInvertedRgb() {
        byte[] bgra = bytes(0x10, 0x20, 0x30, 0x40, 0x80, 0x7F, 0xFF, 0x00);
        Bitmap source = new Bitmap(MemorySegment.ofArray(bgra), 2, 1, 8, 32);
        assertArrayEquals(bytes(0xCF, 0xDF, 0xEF, 0x00, 0x80, 0x7F),
                          CoreTextPureShim.invertBottomRows(source, 2, 1, 3));
    }

    @Test
    void bgraIsCopiedAsItIs() {
        byte[] bgra = bytes(0x10, 0x20, 0x30, 0x40, 0x80, 0x7F, 0xFF, 0x00);
        Bitmap source = new Bitmap(MemorySegment.ofArray(bgra), 2, 1, 8, 32);
        assertArrayEquals(bgra, CoreTextPureShim.copyBottomRows(source, 2, 1));
    }

    /** Row r, column c of the grey source holds 0x10 * r + c; columns 3 and 4 are row padding. */
    @Test
    void aGreyMaskReadsTheBottomRowsOfAPaddedSource() {
        byte[] grey = bytes(0x00, 0x01, 0x02, 0xEE, 0xEE,
                            0x10, 0x11, 0x12, 0xEE, 0xEE,
                            0x20, 0x21, 0x22, 0xEE, 0xEE,
                            0x30, 0x31, 0x32, 0xEE, 0xEE);
        Bitmap source = new Bitmap(MemorySegment.ofArray(grey), 3, 4, 5, 8);
        assertEquals(10, CoreTextPureShim.bottomRowsOffset(source, 2));
        assertArrayEquals(bytes(0xDF, 0xDE, 0xCF, 0xCE), CoreTextPureShim.invertBottomRows(source, 2, 2, 1));
        assertArrayEquals(bytes(0xEF, 0xEE, 0xED, 0xDF, 0xDE, 0xDD, 0xCF, 0xCE, 0xCD),
                          CoreTextPureShim.invertBottomRows(source, 3, 3, 1));
    }

    /** Pixel p of row r of the BGRA source holds 0x40 * r + 0x10 * p + k in byte k; 0xEE is row padding. */
    @Test
    void bgraReadersReadTheBottomRowsOfAPaddedSource() {
        byte[] bgra = bytes(0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x12, 0x13, 0xEE, 0xEE, 0xEE, 0xEE,
                            0x40, 0x41, 0x42, 0x43, 0x50, 0x51, 0x52, 0x53, 0xEE, 0xEE, 0xEE, 0xEE,
                            0x80, 0x81, 0x82, 0x83, 0x90, 0x91, 0x92, 0x93, 0xEE, 0xEE, 0xEE, 0xEE);
        Bitmap source = new Bitmap(MemorySegment.ofArray(bgra), 2, 3, 12, 32);
        assertEquals(12, CoreTextPureShim.bottomRowsOffset(source, 2));
        assertArrayEquals(bytes(0x40, 0x41, 0x42, 0x43, 0x80, 0x81, 0x82, 0x83),
                          CoreTextPureShim.copyBottomRows(source, 1, 2));
        assertArrayEquals(bytes(0xBD, 0xBE, 0xBF, 0x7D, 0x7E, 0x7F),
                          CoreTextPureShim.invertBottomRows(source, 1, 2, 3));
        assertArrayEquals(bytes(0x7F, 0x6F), CoreTextPureShim.invertBottomRows(source, 2, 1, 1));
    }

    /** The BGRA copy steps by the source's bytes per pixel: over a grey source each pixel starts one byte on. */
    @Test
    void aBgraReadOfAGreySourceStepsOneBytePerPixel() {
        Bitmap grey = new Bitmap(MemorySegment.ofArray(bytes(0, 1, 2, 3, 4, 5, 6, 7)), 8, 1, 8, 8);
        assertArrayEquals(bytes(0, 1, 2, 3, 1, 2, 3, 4), CoreTextPureShim.copyBottomRows(grey, 2, 1));
    }

    /** The RGB mask never reads a pixel's fourth byte, so the last one may end past the bitmap; the BGRA copy does. */
    @Test
    void anRgbMaskReadsOnlyThreeBytesOfTheLastPixel() {
        Bitmap bgra = new Bitmap(MemorySegment.ofArray(bytes(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70)), 2, 1, 7, 32);
        assertArrayEquals(bytes(0xCF, 0xDF, 0xEF, 0x8F, 0x9F, 0xAF), CoreTextPureShim.invertBottomRows(bgra, 2, 1, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> CoreTextPureShim.copyBottomRows(bgra, 2, 1));
    }

    /** A read of no columns reads no pixel, so its bottom rows may start past the end of the bitmap. */
    @Test
    void aReadOfNoColumnsReadsNothing() {
        Bitmap bgra = new Bitmap(MemorySegment.ofArray(new byte[2]), 4, 8, 4, 32);
        assertArrayEquals(new byte[0], CoreTextPureShim.copyBottomRows(bgra, 0, 1));
        assertArrayEquals(new byte[0], CoreTextPureShim.invertBottomRows(bgra, 0, 1, 3));
    }

    /** With no columns the pixels are never touched, so even a Java null segment gives an empty array. */
    @Test
    void aReadOfNoColumnsNeverTouchesThePixels() {
        Bitmap noPixels = new Bitmap(null, 4, 1, 4, 32);
        assertArrayEquals(new byte[0], CoreTextPureShim.invertBottomRows(noPixels, 0, 1, 3));
        assertArrayEquals(new byte[0], CoreTextPureShim.copyBottomRows(noPixels, 0, 1));
    }

    /**
     * A BGRA row of 2^30 pixels is 2^32 bytes, 0 as an int, and one of 2^30 + 1 pixels is 4 bytes as an int; the
     * result array wraps the same way. Each copy still fails at the end of the bitmap, at the first and second pixel.
     */
    @Test
    void aBgraRowWhoseByteCountOverflowsAnIntFailsAtTheEndOfTheBitmap() {
        Bitmap empty = new Bitmap(MemorySegment.ofArray(new byte[0]), 1L << 30, 1, 4, 32);
        assertThrows(IndexOutOfBoundsException.class, () -> CoreTextPureShim.copyBottomRows(empty, 1 << 30, 1));
        Bitmap onePixel = new Bitmap(MemorySegment.ofArray(bytes(1, 2, 3, 4)), (1L << 30) + 1, 1, 4, 32);
        assertThrows(IndexOutOfBoundsException.class,
                     () -> CoreTextPureShim.copyBottomRows(onePixel, (1 << 30) + 1, 1));
    }

    /** Where the C read up to 3 bytes past the bitmap, the bounded view throws instead. */
    @Test
    void aGreySourceReadAsBgraFailsAtTheEndOfTheBitmap() {
        Bitmap grey = new Bitmap(MemorySegment.ofArray(bytes(0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x12, 0x13)),
                                 4, 2, 4, 8);
        assertThrows(IndexOutOfBoundsException.class, () -> CoreTextPureShim.invertBottomRows(grey, 4, 2, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> CoreTextPureShim.invertBottomRows(grey, 3, 1, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> CoreTextPureShim.copyBottomRows(grey, 4, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> CoreTextPureShim.copyBottomRows(grey, 2, 1));
        assertArrayEquals(bytes(0xED, 0xEE, 0xEF, 0xEC, 0xED, 0xEE), CoreTextPureShim.invertBottomRows(grey, 2, 1, 3),
                          "within the bitmap, each pixel reads its neighbours");
        assertArrayEquals(bytes(0x10, 0x11, 0x12, 0x13), CoreTextPureShim.copyBottomRows(grey, 1, 1));
    }

    private static void assertSourceRefused(Bitmap source, int dstWidth, int dstHeight, String what) {
        assertFalse(CoreTextPureShim.bitmapCovers(source, dstWidth, dstHeight), what);
        assertNull(CoreTextPureShim.invertBottomRows(source, dstWidth, dstHeight, 1), what);
        assertNull(CoreTextPureShim.invertBottomRows(source, dstWidth, dstHeight, 3), what);
        assertNull(CoreTextPureShim.copyBottomRows(source, dstWidth, dstHeight), what);
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
