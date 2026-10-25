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

/**
 * The bounding box a TrueType glyph declares in its {@code glyf} header, found through the {@code loca} table. Both
 * tables come from the CTFont rather than the font file, because a CTFont of a collection, variable or system font
 * has tables of its own.
 */
final class GlyfTableBounds {

    /** What {@link #headerOffset} returns when the {@code loca} entries give no header of at least 10 bytes. */
    static final long NO_HEADER = -1;

    private static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort BE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private GlyfTableBounds() {
    }

    /** {@code bb} receives xMin, yMin, xMax and yMax, and is written only when this returns {@code true}. */
    static boolean bounds(MemorySegment font, short glyph, short format, int[] bb) {
        MemorySegment loca = CTNative.CTFontCopyTable(font, CTNative.kCTFontTableLoca,
                                                      CTNative.kCTFontTableOptionNoOptions);
        if (loca.address() == 0) {
            return false;
        }
        MemorySegment entries = contents(loca);
        long offset = entries.address() == 0 ? NO_HEADER : headerOffset(entries, glyph, format);
        CFNative.CFRelease(loca);
        if (offset == NO_HEADER) {
            return false;
        }
        MemorySegment glyf = CTNative.CTFontCopyTable(font, CTNative.kCTFontTableGlyf,
                                                      CTNative.kCTFontTableOptionNoOptions);
        if (glyf.address() == 0) {
            return false;
        }
        MemorySegment glyphs = contents(glyf);
        boolean result = glyphs.address() != 0 && boundingBox(glyphs, offset, bb);
        CFNative.CFRelease(glyf);
        return result;
    }

    private static MemorySegment contents(MemorySegment data) {
        long length = CFNative.CFDataGetLength(data);
        return CFNative.CFDataGetBytePtr(data, length);
    }

    /**
     * The offset into {@code glyf} of the header of {@code glyph}, an unsigned 16-bit index, or {@link #NO_HEADER}.
     * Every non-zero {@code format} reads 32-bit entries, -1 included.
     */
    static long headerOffset(MemorySegment loca, short glyph, short format) {
        int index = glyph & 0xFFFF;
        long length = loca.byteSize();
        long offset1 = 0;
        long offset2 = 0;
        if (format != 0) {
            if (index + 1 < length / 4) {
                offset1 = Integer.toUnsignedLong(loca.get(BE_INT, 4L * index));
                offset2 = Integer.toUnsignedLong(loca.get(BE_INT, 4L * (index + 1)));
            }
        } else if (index + 1 < length / 2) {
            offset1 = Short.toUnsignedLong(loca.get(BE_SHORT, 2L * index)) << 1;
            offset2 = Short.toUnsignedLong(loca.get(BE_SHORT, 2L * (index + 1))) << 1;
        }
        if (offset2 > offset1 && offset2 - offset1 >= 10) {
            return offset1;
        }
        return NO_HEADER;
    }

    /** Reads the four signed coordinates after the contour count of the header at {@code offset}. */
    static boolean boundingBox(MemorySegment glyf, long offset, int[] bb) {
        if (offset + 10 < glyf.byteSize()) {
            for (int i = 0; i < 4; i++) {
                bb[i] = glyf.get(BE_SHORT, offset + 2 + 2L * i);
            }
            return true;
        }
        return false;
    }
}
