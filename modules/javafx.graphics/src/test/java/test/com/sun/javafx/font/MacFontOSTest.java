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

package test.com.sun.javafx.font;

import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.coretext.CFNative;
import com.sun.javafx.font.coretext.CGNativeShim;
import com.sun.javafx.font.coretext.OSShim;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the {@code OS} bodies that hold on every macOS build, so that they run on CI as well as on the
 * machine the goldens were captured on.
 */
@EnabledOnOs(OS.MAC)
public class MacFontOSTest {

    private static final double[] IDENTITY = {1, 0, 0, 1, 0, 0};
    private static final double[] FLIP = {1, 0, 0, -1, 0, 0};
    private static final double[] ROT30 = {0.8660254037844387, 0.5, -0.5, 0.8660254037844387, 3.25, -1.5};
    private static final int LOCA = 0x6c6f6361;
    private static final short SHORT_LOCA = 0;
    private static final short LONG_LOCA = 1;

    /** Row r holds 0x10 * r + c in column c, except row 0; columns 5 to 7 are row padding. */
    private static final byte[] GREY = bytes(
            0x00, 0x7F, 0x80, 0xFF, 0x01, 0xEE, 0xEE, 0xEE,
            0x10, 0x11, 0x12, 0x13, 0x14, 0xEE, 0xEE, 0xEE,
            0x20, 0x21, 0x22, 0x23, 0x24, 0xEE, 0xEE, 0xEE,
            0x30, 0x31, 0x32, 0x33, 0x34, 0xEE, 0xEE, 0xEE);

    /** Byte k of pixel p of row r holds 0x40 * r + 0x10 * p + k, except pixel 0 of row 0; 0xEE is row padding. */
    private static final byte[] BGRA = bytes(
            0x00, 0x7F, 0x80, 0xFF, 0x10, 0x11, 0x12, 0x13, 0x20, 0x21, 0x22, 0x23, 0xEE, 0xEE, 0xEE, 0xEE,
            0x40, 0x41, 0x42, 0x43, 0x50, 0x51, 0x52, 0x53, 0x60, 0x61, 0x62, 0x63, 0xEE, 0xEE, 0xEE, 0xEE,
            0x80, 0x81, 0x82, 0x83, 0x90, 0x91, 0x92, 0x93, 0xA0, 0xA1, 0xA2, 0xA3, 0xEE, 0xEE, 0xEE, 0xEE);

    /**
     * For a null matrix {@code CTFontCreateCopyWithAttributes} copies with {@code &CGAffineTransformIdentity}, not
     * NULL, as the C did: the copy of a transformed font comes back untransformed; NULL would keep the source font's
     * matrix.
     */
    @Test
    void aNullMatrixCopyOfATransformedFontHasTheIdentityMatrix() {
        long name = OSShim.CFStringCreate("Helvetica");
        for (double[] matrix : new double[][] {ROT30, FLIP}) {
            long source = OSShim.CTFontCreateWithName(name, 12, matrix);
            assertFalse(Arrays.equals(IDENTITY, MacFontOracleShim.fontMatrix(source)), "the source is transformed");
            long copy = OSShim.CTFontCreateCopyWithAttributes(source, 12, null, 0);
            assertArrayEquals(IDENTITY, MacFontOracleShim.fontMatrix(copy), "copy of " + Arrays.toString(matrix));
            OSShim.CFRelease(copy);
            OSShim.CFRelease(source);
        }
        OSShim.CFRelease(name);
    }

    /**
     * {@code CTFontGetBoundingRectForGlyphUsingTables} reads the {@code loca} and {@code glyf} tables of the CTFont.
     * Ahem's {@code loca} is short: glyph 0 has its header at offset 0, glyph 1 is empty, and the long format reads
     * entries 0 and 1 as one offset.
     */
    @Test
    void glyphBoundsComeFromTheGlyfHeaderTheLocaTableNames() {
        withRepoFont("ahem", font -> {
            assertBounds(new int[] {125, 0, 875, 800}, font, 0, SHORT_LOCA);
            assertNoBounds(font, 1, SHORT_LOCA);
            assertBounds(new int[] {0, -200, 1000, 800}, font, 4, SHORT_LOCA);
            assertBounds(new int[] {4385, 32002, -4355, -28927}, font, 0, LONG_LOCA);
            assertNoBounds(font, 1, LONG_LOCA);
        });
    }

    @Test
    void aFontWithoutALocaTableHasNoGlyphBounds() {
        withRepoFont("fontwithfeaturesotf", font -> {
            assertNull(MacFontOracleShim.fontTable(font, LOCA), "a CFF font");
            for (int glyph : new int[] {0, 1, 4}) {
                assertNoBounds(font, glyph, SHORT_LOCA);
                assertNoBounds(font, glyph, LONG_LOCA);
            }
        });
    }

    /** {@code CGBitmapContextGetData} at 8 bits per pixel inverts each byte of the bottom rows of a grey context. */
    @Test
    void aGreyMaskInvertsTheBottomRowsOfAGreyContext() {
        withGreyContext(GREY, 5, 8, context -> {
            assertArrayEquals(bytes(0xFF, 0x80, 0x7F, 0x00, 0xFE,
                                    0xEF, 0xEE, 0xED, 0xEC, 0xEB,
                                    0xDF, 0xDE, 0xDD, 0xDC, 0xDB,
                                    0xCF, 0xCE, 0xCD, 0xCC, 0xCB),
                              OSShim.CGBitmapContextGetData(context, 5, 4, 8));
            assertArrayEquals(bytes(0xDF, 0xDE, 0xDD, 0xCF, 0xCE, 0xCD),
                              OSShim.CGBitmapContextGetData(context, 3, 2, 8));
        });
    }

    /**
     * {@code CGBitmapContextGetData} at 24 bits per pixel turns each BGRA pixel into the inverse of its R, G and B;
     * at 8, of its B.
     */
    @Test
    void anRgbMaskInvertsTheBottomRowsOfABgraContext() {
        withBgraContext(BGRA, 3, 16, context -> {
            assertArrayEquals(bytes(0x7F, 0x80, 0xFF, 0xED, 0xEE, 0xEF, 0xDD, 0xDE, 0xDF,
                                    0xBD, 0xBE, 0xBF, 0xAD, 0xAE, 0xAF, 0x9D, 0x9E, 0x9F,
                                    0x7D, 0x7E, 0x7F, 0x6D, 0x6E, 0x6F, 0x5D, 0x5E, 0x5F),
                              OSShim.CGBitmapContextGetData(context, 3, 3, 24));
            assertArrayEquals(bytes(0xBD, 0xBE, 0xBF, 0xAD, 0xAE, 0xAF, 0x7D, 0x7E, 0x7F, 0x6D, 0x6E, 0x6F),
                              OSShim.CGBitmapContextGetData(context, 2, 2, 24));
            assertArrayEquals(bytes(0x7F, 0x6F, 0x5F), OSShim.CGBitmapContextGetData(context, 3, 1, 8));
        });
    }

    /** {@code CGImageContextGetData} copies the BGRA bytes of the bottom rows as they are, without the row padding. */
    @Test
    void aBgraReadCopiesTheBottomRowsOfABgraContext() {
        withBgraContext(BGRA, 3, 16, context -> {
            assertArrayEquals(bytes(0x00, 0x7F, 0x80, 0xFF, 0x10, 0x11, 0x12, 0x13, 0x20, 0x21, 0x22, 0x23,
                                    0x40, 0x41, 0x42, 0x43, 0x50, 0x51, 0x52, 0x53, 0x60, 0x61, 0x62, 0x63,
                                    0x80, 0x81, 0x82, 0x83, 0x90, 0x91, 0x92, 0x93, 0xA0, 0xA1, 0xA2, 0xA3),
                              OSShim.CGImageContextGetData(context, 3, 3, 32));
            assertArrayEquals(bytes(0x80, 0x81, 0x82, 0x83, 0x90, 0x91, 0x92, 0x93),
                              OSShim.CGImageContextGetData(context, 2, 1, 32));
        });
    }

    /**
     * {@code CGImageContextGetData} steps by the context's bytes per pixel: on a grey context each 4-byte pixel
     * starts one byte on.
     */
    @Test
    void aBgraReadOfAGreyContextStepsOneBytePerPixel() {
        withGreyContext(GREY, 5, 8, context -> {
            assertArrayEquals(bytes(0x20, 0x21, 0x22, 0x23, 0x21, 0x22, 0x23, 0x24, 0x22, 0x23, 0x24, 0xEE,
                                    0x30, 0x31, 0x32, 0x33, 0x31, 0x32, 0x33, 0x34, 0x32, 0x33, 0x34, 0xEE),
                              OSShim.CGImageContextGetData(context, 3, 2, 32));
        });
    }

    /**
     * The C read up to 3 bytes past the bitmap here. The pixels are viewed through a segment of height * bytesPerRow
     * bytes, so a read past them throws instead.
     */
    @Test
    void aGreyContextReadPastItsLastByteThrows() {
        withGreyContext(bytes(0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x12, 0x13), 4, 4, context -> {
            assertThrows(IndexOutOfBoundsException.class, () -> OSShim.CGBitmapContextGetData(context, 4, 2, 24));
            assertThrows(IndexOutOfBoundsException.class, () -> OSShim.CGBitmapContextGetData(context, 3, 1, 24));
            assertThrows(IndexOutOfBoundsException.class, () -> OSShim.CGImageContextGetData(context, 2, 1, 32));
        });
    }

    @Test
    void bitmapReadsAContextCannotServeGiveNull() {
        assertNull(OSShim.CGBitmapContextGetData(0, 4, 4, 8), "NULL context");
        assertNull(OSShim.CGBitmapContextGetData(0, 4, 4, 24), "NULL context");
        assertNull(OSShim.CGImageContextGetData(0, 4, 4, 32), "NULL context");
        withGreyContext(GREY, 5, 8, context -> {
            assertNull(OSShim.CGBitmapContextGetData(context, 6, 1, 8), "wider than the context");
            assertNull(OSShim.CGBitmapContextGetData(context, 1, 5, 8), "taller than the context");
            assertNull(OSShim.CGBitmapContextGetData(context, 0, 1, 8), "no columns");
            assertNull(OSShim.CGBitmapContextGetData(context, 1, 1, 16), "16 bits per pixel");
        });
        withBgraContext(BGRA, 3, 16, context -> {
            assertNull(OSShim.CGBitmapContextGetData(context, 4, 1, 24), "wider than the context");
            assertNull(OSShim.CGImageContextGetData(context, 4, 1, 32), "wider than the context");
            assertNull(OSShim.CGImageContextGetData(context, 1, 4, 32), "taller than the context");
            assertNull(OSShim.CGImageContextGetData(context, 1, 0, 32), "no rows");
            assertNull(OSShim.CGBitmapContextGetData(context, 1, 1, 32), "32 bits per pixel");
            assertNull(OSShim.CGImageContextGetData(context, 1, 1, 24), "24 bits per pixel");
        });
    }

    private static void assertBounds(int[] expected, long font, int glyph, short format) {
        int[] bb = untouched();
        assertTrue(OSShim.CTFontGetBoundingRectForGlyphUsingTables(font, (short) glyph, format, bb),
                   "glyph " + glyph + ", format " + format);
        assertArrayEquals(expected, bb, "glyph " + glyph + ", format " + format);
    }

    private static void assertNoBounds(long font, int glyph, short format) {
        int[] bb = untouched();
        assertFalse(OSShim.CTFontGetBoundingRectForGlyphUsingTables(font, (short) glyph, format, bb),
                    "glyph " + glyph + ", format " + format);
        assertArrayEquals(untouched(), bb, "glyph " + glyph + ", format " + format);
    }

    private static int[] untouched() {
        return new int[] {Integer.MIN_VALUE, -7, 7, Integer.MAX_VALUE};
    }

    /** Reached through a CGFont, as an embedded font is, so the CTFont has the tables of the file in the repo. */
    private static void withRepoFont(String key, LongConsumer test) {
        long path = OSShim.CFStringCreate(MacFontGoldens.pinned(key).toString());
        long url = OSShim.CFURLCreateWithFileSystemPath(OSShim.kCFAllocatorDefault(), path,
                                                        OSShim.constant("kCFURLPOSIXPathStyle"), false);
        long provider = OSShim.CGDataProviderCreateWithURL(url);
        long cgFont = OSShim.CGFontCreateWithDataProvider(provider);
        assertNotEquals(0, cgFont, "no CGFont for " + key);
        long font = OSShim.CTFontCreateWithGraphicsFont(cgFont, 12, null, 0);
        try {
            test.accept(font);
        } finally {
            for (long object : new long[] {font, cgFont, provider, url, path}) {
                OSShim.CFRelease(object);
            }
        }
    }

    /** An 8-bit grey context without alpha, as {@code CTGlyph} makes for grey masks, over a copy of the pixels. */
    private static void withGreyContext(byte[] pixels, int width, int bytesPerRow, LongConsumer test) {
        withBitmapContext(pixels, width, bytesPerRow, false, test);
    }

    /** A premultiplied BGRA context, as {@code CTGlyph} makes for LCD and colour masks, over a copy of the pixels. */
    private static void withBgraContext(byte[] pixels, int width, int bytesPerRow, LongConsumer test) {
        withBitmapContext(pixels, width, bytesPerRow, true, test);
    }

    private static void withBitmapContext(byte[] pixels, int width, int bytesPerRow, boolean bgra, LongConsumer test) {
        int height = pixels.length / bytesPerRow;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(JAVA_BYTE, pixels);
            MemorySegment space = bgra ? CGNativeShim.CGColorSpaceCreateDeviceRGB()
                                       : CGNativeShim.CGColorSpaceCreateDeviceGray();
            int info = bgra
                    ? OSShim.constant("kCGBitmapByteOrder32Host") | OSShim.constant("kCGImageAlphaPremultipliedFirst")
                    : OSShim.constant("kCGImageAlphaNone");
            MemorySegment context = CGNativeShim.CGBitmapContextCreate(data, width, height, 8, bytesPerRow, space,
                                                                       info);
            try {
                assertNotEquals(0, context.address(), "no bitmap context");
                long[] expected = {width, height, bytesPerRow, bgra ? 32 : 8};
                assertArrayEquals(expected, Arrays.copyOf(MacFontOracleShim.bitmapContextInfo(context.address()), 4));
                test.accept(context.address());
                assertArrayEquals(pixels, data.toArray(JAVA_BYTE), "the reads leave the pixels as they were");
            } finally {
                CGNativeShim.CGContextRelease(context);
                CFNative.CFRelease(space);
            }
        }
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
