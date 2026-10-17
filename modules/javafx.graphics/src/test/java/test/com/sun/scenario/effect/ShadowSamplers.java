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

package test.com.sun.scenario.effect;

import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolveShadowPeer;

/**
 * The sampler the Java Gaussian shadow loop {@code JSWLinearConvolveShadowPeer.filterVector} had before JDK-8090445
 * was fixed, which the Decora golden recorded, as a test-only peer ({@link PreFixNearestShadowPeer}), and the backends
 * that render with it ({@link DecoraBackend#withPeer}). The peer is a subclass of the production peer that replaces
 * only that loop: {@code filter} and {@code filterHV} stay production's.
 */
final class ShadowSamplers {

    /** The peer key of the Gaussian shadow peers. */
    static final String PEER = "LinearConvolveShadow";

    private ShadowSamplers() {
    }

    /** A fresh copy of {@code backend} whose Gaussian shadow loop is the frozen pre-fix nearest-texel loop. */
    static DecoraBackend preFixNearest(DecoraBackend backend) {
        return backend.withPeer("pre-fix nearest shadow sampler", PEER, PreFixNearestShadowPeer.class);
    }

    /**
     * {@code JSWLinearConvolveShadowPeer} with the {@code filterVector} of commit 4f71e99535, which read the alpha of
     * the texel under each sample, {@code (int) sampx} and {@code (int) sampy}. It is unchanged but for the shadow
     * colour, which it reads from the render state as the production peer's private {@code getShadowColor} does.
     */
    public static final class PreFixNearestShadowPeer extends JSWLinearConvolveShadowPeer {

        public PreFixNearestShadowPeer(FilterContext fctx, Renderer r, String uniqueName) {
            super(fctx, r, uniqueName);
        }

        @Override
        protected void filterVector(int dstPixels[], int dstw, int dsth, int dstscan,
                                    int srcPixels[], int srcw, int srch, int srcscan,
                                    float weights[], int count,
                                    float srcx0, float srcy0,
                                    float offsetx, float offsety,
                                    float deltax, float deltay,
                                    float dxcol, float dycol, float dxrow, float dyrow)
        {
            float shadowColor[] = getRenderState().getPassShadowColorComponents();

            int dstrow = 0;
            // srcxy0 point at UL corner, shift them to center of 1st dest pixel:
            srcx0 += (dxrow + dxcol) * 0.5f;
            srcy0 += (dyrow + dycol) * 0.5f;
            for (int dy = 0; dy < dsth; dy++) {
                float srcx = srcx0;
                float srcy = srcy0;
                for (int dx = 0; dx < dstw; dx++) {
                    float sum = 0.0f;
                    float sampx = srcx + offsetx;
                    float sampy = srcy + offsety;
                    for (int i = 0; i < count; ++i) {
                        if (sampx >= 0 && sampy >= 0) {
                            int ix = (int) sampx;
                            int iy = (int) sampy;
                            if (ix < srcw && iy < srch) {
                                int argb = srcPixels[iy * srcscan + ix];
                                sum += (argb >>> 24) * weights[i];
                            }
                        }
                        sampx += deltax;
                        sampy += deltay;
                    }
                    sum = (sum < 0f) ? 0f : ((sum > 255f) ? 255f : sum);
                    dstPixels[dstrow + dx] = ((int) (shadowColor[0] * sum) << 16) |
                                             ((int) (shadowColor[1] * sum) <<  8) |
                                             ((int) (shadowColor[2] * sum)      ) |
                                             ((int) (shadowColor[3] * sum) << 24);
                    srcx += dxcol;
                    srcy += dycol;
                }
                srcx0 += dxrow;
                srcy0 += dyrow;
                dstrow += dstscan;
            }
        }
    }
}
