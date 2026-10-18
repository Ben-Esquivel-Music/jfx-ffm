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

package test.com.sun.pisces;

import com.sun.pisces.GradientColorMap;
import com.sun.pisces.JavaSurface;
import com.sun.pisces.PiscesRenderer;
import com.sun.pisces.RendererBase;
import com.sun.pisces.Transform6;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An {@code emitAndClearAlphaRow} whose delta range ends exactly at the end of the delta array. The range check of
 * {@link PiscesRenderer#emitAndClearAlphaRow(byte[], int[], int, int, int, int, int)} is off by one: it lets
 * {@code xOff + (xTo - xFrom) == alphaDeltas.length} through, although the row has {@code xTo - xFrom + 1} pixels.
 * The C of {@code 42a5072596} then read and zeroed one int past the caller's array, so no golden holds this
 * outcome. The Java compositor gives every pixel that has a delta the value it gets from a long enough array,
 * zeroing the deltas as it consumes them, and throws {@link ArrayIndexOutOfBoundsException} at the pixel whose
 * delta would lie past the end.
 * <p>
 * The oracle for the pixels before the failure is the in-range twin of each case, rendered here: the same call
 * with a delta array one int longer, whose extra delta is 0. The corpus of {@link PiscesCorpusGoldenTest} ties
 * such rows to the C. Every pixel from the failing one on keeps the background. Fixing the range check changes
 * this outcome, and this test with it.
 */
public class PiscesDeltaRangeAtArrayEndTest {

    private static final int WIDTH = 7;
    private static final int BACKGROUND = 0xFF102030;

    private static final int SRC = RendererBase.COMPOSITE_SRC;
    private static final int SRC_OVER = RendererBase.COMPOSITE_SRC_OVER;

    /**
     * One row: {@code x} 0 to {@code xTo} of row 0 under {@code rule}, painted with opaque red or, with
     * {@code gradient}, a linear gradient; {@code deltas.length - xOff} of its pixels have a delta.
     */
    record Row(String name, int rule, boolean gradient, int[] deltas, int xTo, int xOff) {

        int pixelsWithADelta() {
            return deltas.length - xOff;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Row> rangesEndingAtTheArrayEnd() {
        return Stream.of(
                new Row("SRC_OVER colour, coverage to the end", SRC_OVER, false, new int[] {200, 0, 0, 0}, 4, 0),
                new Row("SRC_OVER colour, coverage back to 0", SRC_OVER, false, new int[] {200, 0, -200, 0}, 4, 0),
                new Row("SRC_OVER colour, solid run", SRC_OVER, false, new int[] {255, 0, 0, 0, 0, 0}, 6, 0),
                new Row("SRC_OVER colour, xOff 2", SRC_OVER, false, new int[] {0, 0, 128, 0, 0}, 3, 2),
                new Row("SRC colour", SRC, false, new int[] {200, 0, 0, 0}, 4, 0),
                new Row("SRC_OVER gradient", SRC_OVER, true, new int[] {200, 0, 0, 0}, 4, 0),
                new Row("SRC_OVER gradient, full coverage", SRC_OVER, true, new int[] {255, 0, 0, 0}, 4, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rangesEndingAtTheArrayEnd")
    public void compositesThePixelsThatHaveADeltaThenThrows(Row row) {
        int[] twinPixels = filled(WIDTH, BACKGROUND);
        int[] twinDeltas = Arrays.copyOf(row.deltas(), row.deltas().length + 1);
        renderer(twinPixels, row).emitAndClearAlphaRow(identityMap(), twinDeltas, 0, 0, row.xTo(), row.xOff(), 0);
        assertNotEquals(BACKGROUND, twinPixels[0], "the in-range twin composites its first pixel");

        int[] pixels = filled(WIDTH, BACKGROUND);
        int[] deltas = row.deltas().clone();
        PiscesRenderer pr = renderer(pixels, row);
        ArrayIndexOutOfBoundsException e = assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> pr.emitAndClearAlphaRow(identityMap(), deltas, 0, 0, row.xTo(), row.xOff(), 0));
        assertEquals("Index " + deltas.length + " out of bounds for length " + deltas.length, e.getMessage());

        int[] expected = filled(WIDTH, BACKGROUND);
        System.arraycopy(twinPixels, 0, expected, 0, row.pixelsWithADelta());
        assertEquals(hex(expected), hex(pixels),
                "the twin's pixels before pixel " + row.pixelsWithADelta() + ", the background from there on");
        assertArrayEquals(new int[deltas.length], deltas, "every delta is consumed and zeroed");
    }

    private static PiscesRenderer renderer(int[] pixels, Row row) {
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, WIDTH, 1));
        pr.setColor(255, 0, 0, 255);
        pr.setCompositeRule(row.rule());
        if (row.gradient()) {
            pr.setLinearGradient(0, 0, WIDTH << 16, 0, new int[] {0, 1 << 16}, new int[] {0xFF0000FF, 0x80FF0000},
                    GradientColorMap.CYCLE_NONE, new Transform6(1 << 16, 0, 0, 1 << 16, 0, 0));
        }
        return pr;
    }

    private static byte[] identityMap() {
        byte[] map = new byte[256];
        for (int i = 0; i < map.length; i++) {
            map[i] = (byte) i;
        }
        return map;
    }

    private static int[] filled(int length, int value) {
        int[] array = new int[length];
        Arrays.fill(array, value);
        return array;
    }

    private static String hex(int[] pixels) {
        return Arrays.stream(pixels).mapToObj(pixel -> String.format("%08X", pixel)).toList().toString();
    }
}
