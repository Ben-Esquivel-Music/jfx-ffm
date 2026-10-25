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
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code loca} and {@code glyf} parse of {@code GlyfTableBounds} over heap segments, on every OS: each edge case
 * of the C it replaces, {@code CTFontGetBoundingRectForGlyphUsingTables} in {@code coretext.c}. Its table copies
 * and releases are checked on macOS: the results by {@code MacFontOSTest}, the releases by {@code MacFontStressTest}.
 */
public class GlyfTableBoundsTest {

    private static final long NONE = CoreTextPureShim.NO_GLYF_HEADER;
    private static final short SHORT_FORMAT = 0;
    private static final short LONG_FORMAT = 1;

    @Test
    void shortEntriesAreUnsignedHalfOffsets() {
        MemorySegment loca = shortLoca(0, 6, 20, 20);
        assertEquals(0, headerOffset(loca, 0, SHORT_FORMAT));
        assertEquals(12, headerOffset(loca, 1, SHORT_FORMAT));
        assertEquals(NONE, headerOffset(loca, 2, SHORT_FORMAT), "an empty glyph");
        assertEquals(0xFFFE, headerOffset(shortLoca(0x7FFF, 0xFFFF), 0, SHORT_FORMAT));
    }

    @Test
    void longEntriesAreOffsets() {
        MemorySegment loca = longLoca(0, 12, 40);
        assertEquals(0, headerOffset(loca, 0, LONG_FORMAT));
        assertEquals(12, headerOffset(loca, 1, LONG_FORMAT));
    }

    @Test
    void everyNonZeroFormatReadsTheLongTable() {
        MemorySegment loca = longLoca(12, 40);
        assertEquals(0, headerOffset(loca, 0, SHORT_FORMAT), "as short entries: 0, 12, 0, 40");
        for (short format : new short[] {1, 2, -1, Short.MIN_VALUE, Short.MAX_VALUE}) {
            assertEquals(12, headerOffset(loca, 0, format), "format " + format);
        }
    }

    @Test
    void aGlyphIsReadOnlyWhenTheTableHoldsTheEntryAfterIt() {
        MemorySegment longLoca = longLoca(0, 12, 40);
        assertEquals(12, headerOffset(longLoca, 1, LONG_FORMAT));
        assertEquals(NONE, headerOffset(longLoca, 2, LONG_FORMAT));
        MemorySegment longCut = longLoca.asSlice(0, longLoca.byteSize() - 1);
        assertEquals(0, headerOffset(longCut, 0, LONG_FORMAT));
        assertEquals(NONE, headerOffset(longCut, 1, LONG_FORMAT), "a partial last entry is not read");

        MemorySegment shortLoca = shortLoca(0, 6, 20);
        assertEquals(12, headerOffset(shortLoca, 1, SHORT_FORMAT));
        assertEquals(NONE, headerOffset(shortLoca, 2, SHORT_FORMAT));
        MemorySegment shortCut = shortLoca.asSlice(0, shortLoca.byteSize() - 1);
        assertEquals(0, headerOffset(shortCut, 0, SHORT_FORMAT));
        assertEquals(NONE, headerOffset(shortCut, 1, SHORT_FORMAT), "a partial last entry is not read");

        MemorySegment empty = MemorySegment.ofArray(new byte[0]);
        assertEquals(NONE, headerOffset(empty, 0, SHORT_FORMAT));
        assertEquals(NONE, headerOffset(empty, 0, LONG_FORMAT));
    }

    @Test
    void aHeaderNeedsTenBytesBeforeTheNextEntry() {
        assertEquals(NONE, headerOffset(longLoca(100, 109), 0, LONG_FORMAT));
        assertEquals(100, headerOffset(longLoca(100, 110), 0, LONG_FORMAT));
        assertEquals(NONE, headerOffset(longLoca(100, 100), 0, LONG_FORMAT));
        assertEquals(NONE, headerOffset(longLoca(110, 100), 0, LONG_FORMAT));
        assertEquals(NONE, headerOffset(shortLoca(50, 54), 0, SHORT_FORMAT));
        assertEquals(100, headerOffset(shortLoca(50, 55), 0, SHORT_FORMAT));
        assertEquals(NONE, headerOffset(shortLoca(50, 50), 0, SHORT_FORMAT));
        assertEquals(NONE, headerOffset(shortLoca(55, 50), 0, SHORT_FORMAT));
    }

    @Test
    void longOffsetsAreUnsigned32BitValues() {
        assertEquals(0x8000_0000L, headerOffset(longLoca(0x8000_0000L, 0x8000_000AL), 0, LONG_FORMAT));
        assertEquals(0x7FFF_FFF0L, headerOffset(longLoca(0x7FFF_FFF0L, 0x8000_0000L), 0, LONG_FORMAT));
        assertEquals(NONE, headerOffset(longLoca(0x8000_0000L, 0x7FFF_FFFFL), 0, LONG_FORMAT));
        assertEquals(0xFFFF_FFF5L, headerOffset(longLoca(0xFFFF_FFF5L, 0xFFFF_FFFFL), 0, LONG_FORMAT));
        assertEquals(NONE, headerOffset(longLoca(0xFFFF_FFF6L, 0xFFFF_FFFFL), 0, LONG_FORMAT));

        MemorySegment glyf = glyf(64, 0, 1, 2, 3, 4);
        for (long offset : new long[] {0x8000_0000L, 0xFFFF_FFF5L}) {
            int[] bb = sentinel();
            assertFalse(CoreTextPureShim.glyfBoundingBox(glyf, offset, bb), "offset " + offset);
            assertArrayEquals(sentinel(), bb);
        }
    }

    @Test
    void glyphFFFFIsIndex65535() {
        int[] halves = new int[65537];
        halves[65535] = 50;
        halves[65536] = 60;
        assertEquals(100, headerOffset(shortLoca(halves), 0xFFFF, SHORT_FORMAT));
        long[] offsets = new long[65537];
        offsets[65535] = 100;
        offsets[65536] = 120;
        assertEquals(100, headerOffset(longLoca(offsets), 0xFFFF, LONG_FORMAT));
        assertEquals(NONE, headerOffset(shortLoca(new int[65536]), 0xFFFF, SHORT_FORMAT));
        assertEquals(NONE, headerOffset(longLoca(new long[65536]), 0xFFFF, LONG_FORMAT));
    }

    @Test
    void theHeaderMustEndBeforeTheLastByteOfTheTable() {
        int[] bb = sentinel();
        assertFalse(CoreTextPureShim.glyfBoundingBox(glyf(30, 20, 1, 2, 3, 4), 20, bb), "offset + 10 == length");
        assertArrayEquals(sentinel(), bb);
        assertTrue(CoreTextPureShim.glyfBoundingBox(glyf(31, 20, 1, 2, 3, 4), 20, bb), "offset + 10 == length - 1");
        assertArrayEquals(new int[] {1, 2, 3, 4}, bb);
    }

    @Test
    void coordinatesAreSignedBigEndianShortsAtAnyAlignment() {
        int[] expected = {-1, Short.MIN_VALUE, Short.MAX_VALUE, 0x1234};
        MemorySegment glyf = glyf(40, 13, expected);
        int[] bb = sentinel();
        assertTrue(CoreTextPureShim.glyfBoundingBox(glyf, 13, bb));
        assertArrayEquals(expected, bb);
        int[] parsed = sentinel();
        assertTrue(parse(longLoca(13, 30), glyf, 0, LONG_FORMAT, parsed));
        assertArrayEquals(expected, parsed);
    }

    @Test
    void aFailedParseLeavesTheBoxUntouched() {
        MemorySegment glyf = glyf(64, 20, 1, 2, 3, 4);
        MemorySegment[] locas = {
            MemorySegment.ofArray(new byte[0]), longLoca(20, 29), longLoca(20, 20), longLoca(30, 20),
            longLoca(54, 64), longLoca(0x8000_0000L, 0x8000_0010L), longLoca(20)};
        for (MemorySegment loca : locas) {
            int[] bb = sentinel();
            assertFalse(parse(loca, glyf, 0, LONG_FORMAT, bb), loca.byteSize() + " bytes of loca");
            assertArrayEquals(sentinel(), bb);
        }
        int[] bb = sentinel();
        assertFalse(parse(shortLoca(10, 14), glyf, 0, SHORT_FORMAT, bb));
        assertArrayEquals(sentinel(), bb);
        assertTrue(parse(longLoca(20, 30), glyf, 0, LONG_FORMAT, bb));
        assertArrayEquals(new int[] {1, 2, 3, 4}, bb);
    }

    /** What {@code bounds} does between its two table copies: {@code glyf} is read only when loca names a header. */
    private static boolean parse(MemorySegment loca, MemorySegment glyf, int glyph, short format, int[] bb) {
        long offset = headerOffset(loca, glyph, format);
        return offset != NONE && CoreTextPureShim.glyfBoundingBox(glyf, offset, bb);
    }

    private static long headerOffset(MemorySegment loca, int glyph, short format) {
        return CoreTextPureShim.glyfHeaderOffset(loca, (short) glyph, format);
    }

    private static int[] sentinel() {
        return new int[] {Integer.MIN_VALUE, -7, 7, Integer.MAX_VALUE};
    }

    /** Big-endian 16-bit entries, each half the offset it stands for. */
    private static MemorySegment shortLoca(int... halfOffsets) {
        ByteBuffer buffer = ByteBuffer.allocate(2 * halfOffsets.length);
        for (int half : halfOffsets) {
            buffer.putShort((short) half);
        }
        return MemorySegment.ofArray(buffer.array());
    }

    /** Big-endian 32-bit entries. */
    private static MemorySegment longLoca(long... offsets) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * offsets.length);
        for (long offset : offsets) {
            buffer.putInt((int) offset);
        }
        return MemorySegment.ofArray(buffer.array());
    }

    /** {@code length} bytes holding, at {@code offset}, a header of one contour and the box {@code bb}. */
    private static MemorySegment glyf(int length, int offset, int... bb) {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.putShort(offset, (short) 1);
        for (int i = 0; i < bb.length; i++) {
            buffer.putShort(offset + 2 + 2 * i, (short) bb[i]);
        }
        return MemorySegment.ofArray(buffer.array());
    }
}
