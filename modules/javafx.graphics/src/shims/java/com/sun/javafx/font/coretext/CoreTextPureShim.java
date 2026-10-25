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

import com.sun.javafx.geom.Path2D;
import java.lang.foreign.MemorySegment;

/**
 * Test access to the package-private parts of the CoreText layer that compute over memory only. None of them
 * initialises a facade, so the tests run on every OS.
 */
public final class CoreTextPureShim {

    public static final long NO_GLYF_HEADER = GlyfTableBounds.NO_HEADER;

    private CoreTextPureShim() {
    }

    public static long glyfHeaderOffset(MemorySegment loca, short glyph, short format) {
        return GlyfTableBounds.headerOffset(loca, glyph, format);
    }

    public static boolean glyfBoundingBox(MemorySegment glyf, long headerOffset, int[] bb) {
        return GlyfTableBounds.boundingBox(glyf, headerOffset, bb);
    }

    public static boolean isMaskRequest(MemorySegment context, int dstWidth, int dstHeight, int bpp) {
        return CGBitmapReader.isMaskRequest(context, dstWidth, dstHeight, bpp);
    }

    public static boolean isBgraRequest(MemorySegment context, int dstWidth, int dstHeight, int bpp) {
        return CGBitmapReader.isBgraRequest(context, dstWidth, dstHeight, bpp);
    }

    public static boolean fitsAnArray(int dstWidth, int dstHeight, int dstStep) {
        return CGBitmapReader.fitsAnArray(dstWidth, dstHeight, dstStep);
    }

    public static boolean bitmapCovers(Bitmap source, int dstWidth, int dstHeight) {
        return CGBitmapReader.covers(source.toReaderBitmap(), dstWidth, dstHeight);
    }

    public static long bottomRowsOffset(Bitmap source, int dstHeight) {
        return CGBitmapReader.bottomRowsOffset(source.toReaderBitmap(), dstHeight);
    }

    public static byte[] invertBottomRows(Bitmap source, int dstWidth, int dstHeight, int dstStep) {
        return CGBitmapReader.invertBottomRows(source.toReaderBitmap(), dstWidth, dstHeight, dstStep);
    }

    public static byte[] copyBottomRows(Bitmap source, int dstWidth, int dstHeight) {
        return CGBitmapReader.copyBottomRows(source.toReaderBitmap(), dstWidth, dstHeight);
    }

    /** What a bitmap context reports about its pixels, as {@code CGBitmapReader} receives it. */
    public record Bitmap(MemorySegment pixels, long width, long height, long bytesPerRow, long bitsPerPixel) {

        private CGBitmapReader.Bitmap toReaderBitmap() {
            return new CGBitmapReader.Bitmap(pixels, width, height, bytesPerRow, bitsPerPixel);
        }
    }

    /** A {@link CGPathSink} fed directly, without {@code CGPathApply}. */
    public static final class PathSink {

        private final CGPathSink sink = new CGPathSink();

        public void moveTo(double x, double y) {
            sink.moveTo(x, y);
        }

        public void lineTo(double x, double y) {
            sink.lineTo(x, y);
        }

        public void quadTo(double cx, double cy, double x, double y) {
            sink.quadTo(cx, cy, x, y);
        }

        public void curveTo(double cx1, double cy1, double cx2, double cy2, double x, double y) {
            sink.curveTo(cx1, cy1, cx2, cy2, x, y);
        }

        public void closePath() {
            sink.closePath();
        }

        public void unknownElement(int type) {
            sink.unknownElement(type);
        }

        public void failed(Throwable t) {
            sink.failed(t);
        }

        public Path2D toPath2D() {
            return sink.toPath2D();
        }
    }
}
