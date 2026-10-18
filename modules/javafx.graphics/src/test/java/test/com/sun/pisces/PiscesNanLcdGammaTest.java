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

import com.sun.pisces.JavaSurface;
import com.sun.pisces.PiscesRenderer;
import com.sun.pisces.RendererBase;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An LCD mask over a pixel that is not white after {@code setLCDGammaCorrection(Float.NaN)}, which passes the
 * {@code gamma <= 0} check of {@link PiscesRenderer#setLCDGammaCorrection(float)}. A NaN gamma sets every entry of
 * both LCD gamma tables but the last to {@code 0x80000000}, the x86-64 conversion of NaN to {@code jint}
 * ({@code pow(1, NaN)} is 1, so a white colour still converts to 255), and the blend of a grey destination channel
 * then computes a negative table index. The C of {@code 42a5072596} read the tables out of bounds there and
 * crashed the process, so no golden holds this outcome. The Java compositor throws
 * {@link ArrayIndexOutOfBoundsException} at the first such pixel and leaves it and every later pixel as they were:
 * at index -32768 under zero coverage and at index -32640 under coverage 128. Zero coverage does not take the
 * zero-coverage table of the blit here, which holds the previous gamma's values and is used only while every
 * inverse gamma entry lies in [0, 255].
 * <p>
 * The gamma tables are process-global, so every test sets a valid gamma first and restores gamma 1 at the end.
 */
public class PiscesNanLcdGammaTest {

    private static final int GREY = 0xFF808080;

    @Test
    public void zeroCoverageThrowsAtIndexMinus32768AndLeavesThePixel() {
        assertNanGammaFails(new byte[] {0, 0, 0}, "Index -32768 out of bounds for length 256");
    }

    @Test
    public void coverage128ThrowsAtIndexMinus32640AndLeavesThePixel() {
        assertNanGammaFails(new byte[] {(byte) 128, (byte) 128, (byte) 128},
                "Index -32640 out of bounds for length 256");
    }

    /** Zero coverage, then coverage 128: the row fails at its first pixel and leaves both. */
    @Test
    public void aRowThrowsAtItsFirstPixelAndLeavesEveryPixel() {
        assertNanGammaFails(new byte[] {0, 0, 0, (byte) 128, (byte) 128, (byte) 128},
                "Index -32768 out of bounds for length 256");
    }

    /** Fills {@code mask} (three subpixels per pixel, one row) over grey pixels with white after a NaN gamma. */
    private static void assertNanGammaFails(byte[] mask, String message) {
        int width = mask.length / 3;
        int[] pixels = filled(width, GREY);
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, width, 1));
        try {
            pr.setColor(255, 255, 255, 255);
            pr.setLCDGammaCorrection(2.0f);
            pr.setLCDGammaCorrection(Float.NaN);
            ArrayIndexOutOfBoundsException e = assertThrows(ArrayIndexOutOfBoundsException.class,
                    () -> pr.fillLCDAlphaMask(mask, 0, 0, mask.length, 1, 0, mask.length));
            assertEquals(message, e.getMessage());
            assertArrayEquals(filled(width, GREY), pixels, "the pixels are untouched");
        } finally {
            pr.setLCDGammaCorrection(1f);
        }
    }

    private static int[] filled(int length, int value) {
        int[] array = new int[length];
        Arrays.fill(array, value);
        return array;
    }
}
