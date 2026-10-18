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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The null arguments that get past the checks of {@link PiscesRenderer}: the alpha map of
 * {@code emitAndClearAlphaRow} and the texture transform of {@code setTexture} and {@code drawImage}. Each throws
 * {@link NullPointerException} before the compositor reads or writes anything, as the FFM path to the C of
 * {@code 42a5072596} did: it wrapped the alpha map with {@code MemorySegment.ofArray}, which throws an exception
 * with no message, inside the clip and outside it ({@code PiscesNative.java:501-503} at {@code 42a5072596}), and
 * copied the transform with {@code Transform6.fill}, whose helpful message names that call
 * ({@code PiscesRenderer.java:102-103} at {@code 42a5072596}).
 */
public class PiscesNullArgumentTest {

    private static final int SIZE = 8;
    private static final int BACKGROUND = 0xFF102030;

    /** The start of the helpful message; the rest names the variable, which depends on the debug information. */
    private static final String NULL_TRANSFORM = "Cannot invoke \"com.sun.pisces.Transform6.fill(int[])\"";

    private int[] pixels;
    private PiscesRenderer renderer;

    @BeforeEach
    void createRenderer() {
        pixels = filled(SIZE * SIZE, BACKGROUND);
        renderer = new PiscesRenderer(new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, SIZE, SIZE));
        renderer.setColor(250, 120, 10, 255);
    }

    @ParameterizedTest(name = "row {0}")
    @ValueSource(ints = {1, 20})
    public void aNullAlphaMapThrowsANullPointerExceptionWithoutAMessage(int y) {
        int[] deltas = {200, 0, 0, -200, 0, 0, 0, 0};
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> renderer.emitAndClearAlphaRow(null, deltas, y, 0, 3, 0));
        assertNull(e.getMessage());
        assertArrayEquals(new int[] {200, 0, 0, -200, 0, 0, 0, 0}, deltas, "the deltas are not consumed");
        assertArrayEquals(filled(SIZE * SIZE, BACKGROUND), pixels, "the pixels are untouched");
    }

    @Test
    public void aNullTextureTransformOfSetTextureThrowsTheNullPointerExceptionOfTransform6Fill() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> renderer.setTexture(RendererBase.TYPE_INT_ARGB_PRE, new int[16], 4, 4, 4, null,
                        RendererBase.WRAP_REPEAT, true, true));
        assertHelpfulMessageStartsWith(NULL_TRANSFORM, e);
        assertArrayEquals(filled(SIZE * SIZE, BACKGROUND), pixels, "the pixels are untouched");
    }

    @Test
    public void aNullTextureTransformOfDrawImageThrowsTheNullPointerExceptionOfTransform6Fill() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> renderer.drawImage(RendererBase.TYPE_INT_ARGB_PRE, RendererBase.IMAGE_MODE_NORMAL,
                        filled(16, 0xFF00FF00), 4, 4, 0, 4, null, RendererBase.WRAP_CLAMP_TO_EDGE, false,
                        0, 0, 4 << 16, 4 << 16, 0, 0, 0, 0, 0, 0, 3, 3, true));
        assertHelpfulMessageStartsWith(NULL_TRANSFORM, e);
        assertArrayEquals(filled(SIZE * SIZE, BACKGROUND), pixels, "the pixels are untouched");
    }

    private static void assertHelpfulMessageStartsWith(String prefix, NullPointerException e) {
        String message = e.getMessage();
        assertTrue(message != null && message.startsWith(prefix), "message: " + message);
    }

    private static int[] filled(int length, int value) {
        int[] array = new int[length];
        Arrays.fill(array, value);
        return array;
    }
}
