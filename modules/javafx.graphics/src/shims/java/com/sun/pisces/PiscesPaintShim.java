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

package com.sun.pisces;

/**
 * Test access to the paint generators of the Pisces compositor ({@link PiscesPaint}) on renderer states that the
 * public API reaches only with gibibytes of heap.
 */
public final class PiscesPaintShim {

    private PiscesPaintShim() {
    }

    /**
     * Runs {@link PiscesPaint#genTexturePaintMultiply} for {@code height} rows of the touched columns
     * {@code [minTouched, maxTouched]}, with a linear gradient as the previous paint and {@code paint} as the paint
     * buffer, on the renderer state of a 1 x 1 surface that is otherwise as {@code renderer_create} leaves it.
     * Only a row too wide for the multiply is meaningful here: a narrower one generates the gradient into
     * {@code paint} and samples a texture this state does not have.
     */
    public static void genTexturePaintMultiplyAfterALinearGradient(int[] paint, int minTouched, int maxTouched,
            int height) {
        PiscesRendererState rdr = new PiscesRendererState(
                PiscesSurfaceState.create(RendererBase.TYPE_INT_ARGB_PRE, 1, 1));
        rdr.paintMode = PiscesRendererState.PAINT_TEXTURE8888_MULTIPLY;
        rdr.prevPaintMode = PiscesRendererState.PAINT_LINEAR_GRADIENT;
        rdr.minTouched = minTouched;
        rdr.maxTouched = maxTouched;
        rdr.alphaWidth = maxTouched - minTouched + 1;
        rdr.paint = paint;
        rdr.paintLength = paint.length;
        PiscesPaint.genTexturePaintMultiply(rdr, height);
    }
}
