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

package com.sun.javafx.font.coretext;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

/**
 * Copies the bottom {@code dstHeight} rows of a bitmap context into a new array, for the glyph masks. Every request
 * the context cannot serve gives null. The pixels are viewed through a segment bounded to the bitmap, so a request
 * that would read past it (a grey context read as 24 or 32 bits per pixel) throws {@link IndexOutOfBoundsException}.
 */
final class CGBitmapReader {

    private static final long SIZE_MAX = -1L;
    private static final int BGRA_STEP = 4;
    private static final ValueLayout.OfInt BGRA_PIXEL = JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** What the context reports about its pixels. */
    record Bitmap(MemorySegment pixels, long width, long height, long bytesPerRow, long bitsPerPixel) {
    }

    private CGBitmapReader() {
    }

    /** Grey (8) or RGB (24) bytes, each the inverse of the source byte; RGB from BGRA. */
    static byte[] invertedMask(MemorySegment context, int dstWidth, int dstHeight, int bpp) {
        if (!isMaskRequest(context, dstWidth, dstHeight, bpp)) {
            return null;
        }
        return invertBottomRows(readBitmap(context), dstWidth, dstHeight, bpp / 8);
    }

    /** BGRA bytes copied as they are; {@code bpp} must be 32. */
    static byte[] bgra(MemorySegment context, int dstWidth, int dstHeight, int bpp) {
        if (!isBgraRequest(context, dstWidth, dstHeight, bpp)) {
            return null;
        }
        return copyBottomRows(readBitmap(context), dstWidth, dstHeight);
    }

    /** A NULL context is refused before the array-size checks. */
    static boolean isMaskRequest(MemorySegment context, int dstWidth, int dstHeight, int bpp) {
        if (dstWidth <= 0 || dstHeight <= 0 || (bpp != 8 && bpp != 24) || MemorySegment.NULL.equals(context)) {
            return false;
        }
        return fitsAnArray(dstWidth, dstHeight, bpp / 8);
    }

    /** A NULL context is refused after the array-size checks. */
    static boolean isBgraRequest(MemorySegment context, int dstWidth, int dstHeight, int bpp) {
        if (dstWidth <= 0 || dstHeight <= 0 || bpp != 32) {
            return false;
        }
        return fitsAnArray(dstWidth, dstHeight, bpp / 8) && !MemorySegment.NULL.equals(context);
    }

    /** Whether {@code dstWidth * dstHeight * dstStep}, all of them positive, fits in an {@code int}. */
    static boolean fitsAnArray(int dstWidth, int dstHeight, int dstStep) {
        return dstWidth <= Integer.MAX_VALUE / dstStep && dstHeight <= Integer.MAX_VALUE / (dstWidth * dstStep);
    }

    /**
     * The C read the data pointer before the sizes; the sizes come first here because they bound the view of the
     * pixels.
     */
    private static Bitmap readBitmap(MemorySegment context) {
        long width = CGNative.CGBitmapContextGetWidth(context);
        long height = CGNative.CGBitmapContextGetHeight(context);
        long bytesPerRow = CGNative.CGBitmapContextGetBytesPerRow(context);
        long bitsPerPixel = CGNative.CGBitmapContextGetBitsPerPixel(context);
        MemorySegment pixels = CGNative.CGBitmapContextGetData(context, height * bytesPerRow);
        return new Bitmap(pixels, width, height, bytesPerRow, bitsPerPixel);
    }

    /** Whether the bitmap has pixels and at least {@code dstWidth} by {@code dstHeight} of them. */
    static boolean covers(Bitmap source, int dstWidth, int dstHeight) {
        return !MemorySegment.NULL.equals(source.pixels()) && source.width() >= dstWidth
                && source.height() >= dstHeight && source.bytesPerRow() != 0
                && Long.compareUnsigned(source.height() - dstHeight,
                                        Long.divideUnsigned(SIZE_MAX, source.bytesPerRow())) <= 0;
    }

    static long bottomRowsOffset(Bitmap source, int dstHeight) {
        return (source.height() - dstHeight) * source.bytesPerRow();
    }

    /** {@code dstStep} 1 inverts the first byte of each source pixel; 3 inverts its first three in reverse order. */
    static byte[] invertBottomRows(Bitmap source, int dstWidth, int dstHeight, int dstStep) {
        if (!covers(source, dstWidth, dstHeight)) {
            return null;
        }
        long srcStep = source.bitsPerPixel() / 8;
        byte[] data = new byte[dstWidth * dstHeight * dstStep];
        long srcOffset = bottomRowsOffset(source, dstHeight);
        for (int y = 0; y < dstHeight; y++) {
            int dstOffset = y * dstWidth * dstStep;
            if (srcStep == 1 && dstStep == 1) {
                invertGreyRow(source.pixels(), srcOffset, data, dstOffset, dstWidth);
            } else if (srcStep == BGRA_STEP && dstStep == 1) {
                invertBlueRow(source.pixels(), srcOffset, data, dstOffset, dstWidth);
            } else if (srcStep == BGRA_STEP && dstStep == 3 && holdsWholePixels(source.pixels(), srcOffset, dstWidth)) {
                invertBgraRowToRgb(source.pixels(), srcOffset, data, dstOffset, dstWidth);
            } else {
                invertRow(source.pixels(), srcOffset, srcStep, data, dstOffset, dstWidth, dstStep);
            }
            srcOffset += source.bytesPerRow();
        }
        return data;
    }

    static byte[] copyBottomRows(Bitmap source, int dstWidth, int dstHeight) {
        if (!covers(source, dstWidth, dstHeight)) {
            return null;
        }
        long srcStep = source.bitsPerPixel() / 8;
        byte[] data = new byte[dstWidth * dstHeight * BGRA_STEP];
        long srcOffset = bottomRowsOffset(source, dstHeight);
        for (int y = 0; y < dstHeight; y++) {
            int dstOffset = y * dstWidth * BGRA_STEP;
            // One copy checks the whole row before it reads, so it is used only where no read is out of bounds and
            // the row's byte count fits an int.
            if (srcStep == BGRA_STEP && dstWidth <= Integer.MAX_VALUE / BGRA_STEP
                    && holdsWholePixels(source.pixels(), srcOffset, dstWidth)) {
                MemorySegment.copy(source.pixels(), JAVA_BYTE, srcOffset, data, dstOffset, dstWidth * BGRA_STEP);
            } else {
                copyRow(source.pixels(), srcOffset, srcStep, data, dstOffset, dstWidth);
            }
            srcOffset += source.bytesPerRow();
        }
        return data;
    }

    // Rows of the grey and BGRA step sizes, the ones CTGlyph reads, are read in bulk or at constant strides, which
    // lets the JIT take the bounds checks out of the loops. Each one fails exactly when the general loops do, with
    // the same exception unless the pixels' arena is closed.

    private static void invertGreyRow(MemorySegment pixels, long srcOffset, byte[] data, int dstOffset, int width) {
        for (int x = 0; x < width; x++) {
            data[dstOffset + x] = inverse(pixels.get(JAVA_BYTE, srcOffset + x));
        }
    }

    private static void invertBlueRow(MemorySegment pixels, long srcOffset, byte[] data, int dstOffset, int width) {
        for (int x = 0; x < width; x++) {
            data[dstOffset + x] = inverse(pixels.get(JAVA_BYTE, srcOffset + BGRA_STEP * (long) x));
        }
    }

    /**
     * Whether the row has pixels and all four bytes of each lie inside the bitmap, so that no read of it is out of
     * bounds. The paths that read whole pixels or the whole row at once are taken only then.
     */
    private static boolean holdsWholePixels(MemorySegment pixels, long srcOffset, int width) {
        return width > 0 && srcOffset >= 0 && pixels.byteSize() - srcOffset >= BGRA_STEP * (long) width;
    }

    private static void invertBgraRowToRgb(MemorySegment pixels, long srcOffset, byte[] data, int dstOffset,
                                           int width) {
        for (int x = 0; x < width; x++) {
            int pixel = pixels.get(BGRA_PIXEL, srcOffset + BGRA_STEP * (long) x);
            int dx = dstOffset + 3 * x;
            data[dx] = inverse((byte) (pixel >> 16));
            data[dx + 1] = inverse((byte) (pixel >> 8));
            data[dx + 2] = inverse((byte) pixel);
        }
    }

    private static void invertRow(MemorySegment pixels, long srcOffset, long srcStep, byte[] data, int dstOffset,
                                  int width, int dstStep) {
        long sx = srcOffset;
        for (int x = 0; x < width; x++, dstOffset += dstStep, sx += srcStep) {
            if (dstStep == 1) {
                data[dstOffset] = inverse(pixels.get(JAVA_BYTE, sx));
            } else {
                data[dstOffset] = inverse(pixels.get(JAVA_BYTE, sx + 2));
                data[dstOffset + 1] = inverse(pixels.get(JAVA_BYTE, sx + 1));
                data[dstOffset + 2] = inverse(pixels.get(JAVA_BYTE, sx));
            }
        }
    }

    private static void copyRow(MemorySegment pixels, long srcOffset, long srcStep, byte[] data, int dstOffset,
                                int width) {
        long sx = srcOffset;
        for (int x = 0; x < width; x++, dstOffset += BGRA_STEP, sx += srcStep) {
            MemorySegment.copy(pixels, JAVA_BYTE, sx, data, dstOffset, BGRA_STEP);
        }
    }

    // C signed-char arithmetic, 0xFF - b, truncated back to a byte.
    private static byte inverse(byte b) {
        return (byte) (0xFF - b);
    }
}
