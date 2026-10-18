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
 * The paint generators of the Pisces compositor: {@code native-prism-sw/PiscesPaint.c} of commit
 * {@code 42a5072596}. Each generator fills {@code height} rows of {@code rdr.alphaWidth} premultiplied ARGB
 * values into the paint buffer, as its C counterpart does, in the C's integer and float arithmetic.
 * <p>
 * The texture coordinate bound helpers of the C ({@code checkBoundsRepeat}, {@code checkBoundsNoRepeat}) write
 * through pointers; here they return the new value. {@code checkBoundsRepeat} changes the 16.16 coordinate, and
 * the integer texel is then {@code (int) (coordinate >> 16)} again, which is what the C stores alongside.
 */
final class PiscesPaint {

    private static final int NO_REPEAT_NO_INTERPOLATE = 0;
    private static final int REPEAT_NO_INTERPOLATE = 1;
    private static final int NO_REPEAT_INTERPOLATE_NO_ALPHA = 2;
    private static final int NO_REPEAT_INTERPOLATE_ALPHA = 3;
    private static final int REPEAT_INTERPOLATE_NO_ALPHA = 4;
    private static final int REPEAT_INTERPOLATE_ALPHA = 5;

    private PiscesPaint() {
    }

    private static long lmod(long x, long y) {
        x = x % y;
        if (x < 0) {
            x += y;
        }
        return x;
    }

    private static int interp(int x0, int x1, int frac) {
        return ((x0 << 16) + (x1 - x0) * frac + 0x8000) >> 16;
    }

    /** {@code pad}: maps a 16.16 gradient fraction into [0, 0xffff] by the cycle method. */
    private static int pad(int ifrac, int cycleMethod) {
        switch (cycleMethod) {
            case PiscesRendererState.CYCLE_NONE:
                if (ifrac < 0) {
                    ifrac = 0;
                } else if (ifrac > 0xffff) {
                    ifrac = 0xffff;
                }
                break;
            case PiscesRendererState.CYCLE_REPEAT:
                ifrac &= 0xffff;
                break;
            case PiscesRendererState.CYCLE_REFLECT:
                if (ifrac < 0) {
                    ifrac = -ifrac;
                }
                ifrac &= 0x1ffff;
                if (ifrac > 0xffff) {
                    ifrac = 0x1ffff - ifrac;
                }
                break;
            default:
                break;
        }

        return ifrac;
    }

    /**
     * {@code genLinearGradientPaint}: {@code frac} is a float accumulated by {@code mx} per pixel, as in the C,
     * and truncated with the x86-64 conversion ({@link PiscesTransform#f2i}).
     */
    static void genLinearGradientPaint(PiscesRendererState rdr, int height) {
        int paintOffset = 0;
        int width = rdr.alphaWidth;

        int cycleMethod = rdr.gradientCycleMethod;
        float mx = rdr.lgMx;
        float my = rdr.lgMy;
        float b = rdr.lgB;

        int[] paint = rdr.paint;
        int[] colors = rdr.gradientColors;

        int y = rdr.currY;
        for (int j = 0; j < height; j++, y++) {
            int x = rdr.currX;
            int pidx = paintOffset;

            float frac = x * mx + y * my + b;
            for (int i = 0; i < width; i++, pidx++) {
                int ifrac = pad(PiscesTransform.f2i(frac), cycleMethod);
                ifrac >>= 16 - PiscesRendererState.LG_GRADIENT_MAP_SIZE;
                paint[pidx] = colors[ifrac];

                frac += mx;
            }

            paintOffset += width;
        }
    }

    /**
     * {@code genRadialGradientPaint}: the per-row set-up and the forward differences in float, in the C's order;
     * the fraction is {@code U + sqrt(V)} in double ({@code sqrt} takes and returns a double in the C) and
     * truncated with the x86-64 conversion ({@link PiscesTransform#d2i}).
     */
    static void genRadialGradientPaint(PiscesRendererState rdr, int height) {
        int cycleMethod = rdr.gradientCycleMethod;
        int width = rdr.alphaWidth;
        int paintOffset = 0;

        float a00 = rdr.rgA00;
        float a01 = rdr.rgA01;
        float a02 = rdr.rgA02;
        float a10 = rdr.rgA10;
        float a11 = rdr.rgA11;
        float a12 = rdr.rgA12;

        float a00a00 = rdr.rgA00a00;
        float a10a10 = rdr.rgA10a10;
        float a00a10 = rdr.rgA00a10;

        float cx = rdr.rgCx;
        float cy = rdr.rgCy;
        float fx = rdr.rgFx;
        float fy = rdr.rgFy;
        float rsq = rdr.rgRsq;

        int[] paint = rdr.paint;
        int[] colors = rdr.gradientColors;

        int y = rdr.currY;
        for (int j = 0; j < height; j++, y++) {
            int pidx = paintOffset;
            int x = rdr.currX;

            float txx = x * a00 + y * a01 + a02;
            float tyy = x * a10 + y * a11 + a12;

            float fxx = fx - txx;
            float fyy = fy - tyy;
            float bigA = fxx * fxx + fyy * fyy;
            float cfx = cx - fx;
            float cfy = cy - fy;
            float cfxcfx = cfx * cfx;
            float cfycfy = cfy * cfy;
            float cfxcfy = cfx * cfy;
            float bigB = (cfx * fxx + cfy * fyy);
            float bigB2 = -bigB * 2.0f;
            float bigC = cfxcfx + cfycfy - rsq;
            float bigC2 = 2.0f * bigC;
            float invC = 1.0f / bigC;
            float invCsq = invC * invC;
            float u = (-bigB * invC);
            float dU = (a00 * cfx + a10 * cfy) * invC;
            float v = ((bigB * bigB - bigA * bigC) * invCsq);
            float sube = 2.0f * a00a10 * cfxcfy;
            float dV = (sube +
                       (a00a00 * (cfxcfx - bigC) + a00 * (bigB2 * cfx + bigC2 * fxx)) +
                       (a10a10 * (cfycfy - bigC) + a10 * (bigB2 * cfy + bigC2 * fyy))) * invCsq;
            float tmp = a00a00 * cfycfy - sube + a10a10 * cfxcfx;
            float ddV = 2.0f * ((a00a00 + a10a10) * rsq - tmp) * invCsq;

            u   = (65536.0f * u); // 65536.0f to be in fixed-point level needed by "frac"
            v   = (65536.0f * 65536.0f * v); // 65536.0f * 65536.0f to stay in fixed point level after sqrt
            dU  = (65536.0f * dU);
            dV  = (65536.0f * 65536.0f * dV);
            ddV = (65536.0f * 65536.0f * ddV);
            for (int i = 0; i < width; i++, pidx++) {
                if (v < 0) {
                    v = 0;
                }

                int ifrac = PiscesTransform.d2i(u + Math.sqrt(v));

                u += dU;
                v += dV;
                dV += ddV;

                ifrac = pad(ifrac, cycleMethod);
                ifrac >>= (16 - PiscesRendererState.LG_GRADIENT_MAP_SIZE);
                paint[pidx] = colors[ifrac];
            }

            paintOffset += width;
        }
    }

    private static int interpolate2points(int p0, int p1, int frac) {
        int a0 = (p0 >> 24) & 0xff;
        int r0 = (p0 >> 16) & 0xff;
        int g0 = (p0 >> 8)  & 0xff;
        int b0 =  p0        & 0xff;

        int a1 = (p1 >> 24) & 0xff;
        int r1 = (p1 >> 16) & 0xff;
        int g1 = (p1 >> 8)  & 0xff;
        int b1 =  p1        & 0xff;

        int aa = interp(a0, a1, frac);
        int rr = interp(r0, r1, frac);
        int gg = interp(g0, g1, frac);
        int bb = interp(b0, b1, frac);

        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    /**
     * {@code interpolate4points}: p01 is the right neighbour of p00, p10 the one below it, p11 below right.
     */
    private static int interpolate4points(int p00, int p01, int p10, int p11, int hfrac, int vfrac) {
        int a00 = (p00 >> 24) & 0xff;
        int r00 = (p00 >> 16) & 0xff;
        int g00 = (p00 >> 8)  & 0xff;
        int b00 =  p00        & 0xff;

        int a01 = (p01 >> 24) & 0xff;
        int r01 = (p01 >> 16) & 0xff;
        int g01 = (p01 >> 8)  & 0xff;
        int b01 =  p01        & 0xff;

        int a0 = interp(a00, a01, hfrac);
        int r0 = interp(r00, r01, hfrac);
        int g0 = interp(g00, g01, hfrac);
        int b0 = interp(b00, b01, hfrac);

        int a10 = (p10 >> 24) & 0xff;
        int r10 = (p10 >> 16) & 0xff;
        int g10 = (p10 >> 8)  & 0xff;
        int b10 =  p10        & 0xff;

        int a11 = (p11 >> 24) & 0xff;
        int r11 = (p11 >> 16) & 0xff;
        int g11 = (p11 >> 8)  & 0xff;
        int b11 =  p11        & 0xff;

        int a1 = interp(a10, a11, hfrac);
        int r1 = interp(r10, r11, hfrac);
        int g1 = interp(g10, g11, hfrac);
        int b1 = interp(b10, b11, hfrac);

        int aa = interp(a0, a1, vfrac);
        int rr = interp(r0, r1, vfrac);
        int gg = interp(g0, g1, vfrac);
        int bb = interp(b0, b1, vfrac);

        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private static int interpolate2pointsNoAlpha(int p0, int p1, int frac) {
        int r0 = (p0 >> 16) & 0xff;
        int g0 = (p0 >> 8)  & 0xff;
        int b0 =  p0        & 0xff;

        int r1 = (p1 >> 16) & 0xff;
        int g1 = (p1 >> 8)  & 0xff;
        int b1 =  p1        & 0xff;

        int rr = interp(r0, r1, frac);
        int gg = interp(g0, g1, frac);
        int bb = interp(b0, b1, frac);

        return 0xff000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static int interpolate4pointsNoAlpha(int p00, int p01, int p10, int p11, int hfrac, int vfrac) {
        int r00 = (p00 >> 16) & 0xff;
        int g00 = (p00 >> 8)  & 0xff;
        int b00 =  p00        & 0xff;

        int r01 = (p01 >> 16) & 0xff;
        int g01 = (p01 >> 8)  & 0xff;
        int b01 =  p01        & 0xff;

        int r0 = interp(r00, r01, hfrac);
        int g0 = interp(g00, g01, hfrac);
        int b0 = interp(b00, b01, hfrac);

        int r10 = (p10 >> 16) & 0xff;
        int g10 = (p10 >> 8)  & 0xff;
        int b10 =  p10        & 0xff;

        int r11 = (p11 >> 16) & 0xff;
        int g11 = (p11 >> 8)  & 0xff;
        int b11 =  p11        & 0xff;

        int r1 = interp(r10, r11, hfrac);
        int g1 = interp(g10, g11, hfrac);
        int b1 = interp(b10, b11, hfrac);

        int rr = interp(r0, r1, vfrac);
        int gg = interp(g0, g1, vfrac);
        int bb = interp(b0, b1, vfrac);

        return 0xff000000 | (rr << 16) | (gg << 8) | bb;
    }

    /**
     * The bilinear sample of the {@code *_INTERPOLATE_ALPHA} modes at texel (tx, ty): the four texels the C's
     * {@code getPointsToInterpolate} reads, in its order, and the four-point blend of the C. Inside the content
     * the texels stay in locals; {@code pts} is only the scratch of {@link #getPointsToInterpolateAtEdge}.
     */
    private static int sampleAlpha(int[] pts, int[] data, int offset, int stride,
            int tx, int txLast, int ty, int tyLast, int wrapMode, int hfrac, int vfrac) {
        int p00;
        int p01;
        int p10;
        int p11;
        if (tx >= 0 && tx < txLast && ty >= 0 && ty < tyLast) {
            // the whole footprint is inside the content; read in the order of getPointsToInterpolate
            int sidx = offset + ty * stride + tx;
            int sidx2 = sidx + stride;
            p01 = data[sidx + 1];
            p10 = data[sidx2];
            p11 = data[sidx2 + 1];
            p00 = data[sidx];
        } else {
            p00 = getPointsToInterpolateAtEdge(pts, data, offset, stride, tx, txLast, ty, tyLast, wrapMode);
            p01 = pts[0];
            p10 = pts[1];
            p11 = pts[2];
        }
        if (hfrac != 0 && vfrac != 0) {
            if ((p00 & p01 & p10 & p11) >>> 24 == 0xff) {
                // interp(255, 255, frac) == 255: the alpha of an opaque footprint stays 0xff, so the four-point
                // blend is interpolate4pointsNoAlpha
                return interpolate4pointsNoAlpha(p00, p01, p10, p11, hfrac, vfrac);
            }
            return interpolate4points(p00, p01, p10, p11, hfrac, vfrac);
        } else if (hfrac != 0) {
            return interpolate2points(p00, p01, hfrac);
        } else if (vfrac != 0) {
            return interpolate2points(p00, p10, vfrac);
        } else {
            return p00;
        }
    }

    /** The bilinear sample of the {@code *_INTERPOLATE_NO_ALPHA} modes; see {@link #sampleAlpha}. */
    private static int sampleNoAlpha(int[] pts, int[] data, int offset, int stride,
            int tx, int txLast, int ty, int tyLast, int wrapMode, int hfrac, int vfrac) {
        int p00;
        int p01;
        int p10;
        int p11;
        if (tx >= 0 && tx < txLast && ty >= 0 && ty < tyLast) {
            // the whole footprint is inside the content; read in the order of getPointsToInterpolate
            int sidx = offset + ty * stride + tx;
            int sidx2 = sidx + stride;
            p01 = data[sidx + 1];
            p10 = data[sidx2];
            p11 = data[sidx2 + 1];
            p00 = data[sidx];
        } else {
            p00 = getPointsToInterpolateAtEdge(pts, data, offset, stride, tx, txLast, ty, tyLast, wrapMode);
            p01 = pts[0];
            p10 = pts[1];
            p11 = pts[2];
        }
        if (hfrac != 0 && vfrac != 0) {
            return interpolate4pointsNoAlpha(p00, p01, p10, p11, hfrac, vfrac);
        } else if (hfrac != 0) {
            return interpolate2pointsNoAlpha(p00, p01, hfrac);
        } else if (vfrac != 0) {
            return interpolate2pointsNoAlpha(p00, p10, vfrac);
        } else {
            return p00;
        }
    }

    /**
     * {@code checkBoundsRepeat}: a texel coordinate {@code a} outside [min, max] is wrapped modulo max + 1 into
     * [0, max] (to 0 when max < 0); min only decides which coordinates are left as they are, so a bilinear
     * footprint may start at txMin - 1 unwrapped. Returns the new 16.16 coordinate; the modulus is the C's
     * {@code (max+1) << 16}, an int shift widened afterwards.
     */
    private static long checkBoundsRepeat(int a, long la, int min, int max) {
        if (a < min || a > max) {
            if (max >= 0) {
                return lmod(la, (max + 1) << 16);
            } else {
                return 0;
            }
        }
        return la;
    }

    /**
     * {@code checkBoundsNoRepeat}: {@code zero} is true for the bilinear footprint of a CLAMP_TO_ZERO texture:
     * a coordinate whose footprint leaves the content [0, last] is then not moved back into it but kept outside,
     * below 0 as -1 or -2 and from last on as last or last + 1, so the bilinear sample reads every texel that
     * the unclamped footprint has outside the content as 0, whatever the frac is.
     */
    private static int checkBoundsNoRepeat(int a, int min, int max, int last, boolean zero) {
        if (a < min) {
            return (zero && a < 0) ? Math.max(a, -2) : min;
        } else if (a > max) {
            return (zero && a >= last) ? Math.min(a, last + 1) : max;
        }
        return a;
    }

    /**
     * {@code wrapTexel}: a texel inside the content is read as it is; one outside it follows the wrap mode -
     * the nearest edge texel, the wrapped texel, or -1 for transparent 0.
     */
    private static int wrapTexel(int i, int last, int wrapMode) {
        if (i < 0) {
            return (wrapMode == RendererBase.WRAP_REPEAT) ? last :
                (wrapMode == RendererBase.WRAP_CLAMP_TO_ZERO) ? -1 : 0;
        }
        if (i > last) {
            return (wrapMode == RendererBase.WRAP_REPEAT) ? 0 :
                (wrapMode == RendererBase.WRAP_CLAMP_TO_ZERO) ? -1 : last;
        }
        return i;
    }

    private static int texelAt(int[] data, int offset, int stride, int x, int y) {
        return (x < 0 || y < 0) ? 0x00000000 : data[offset + y * stride + x];
    }

    /**
     * {@code getPointsToInterpolateAtEdge}: returns texel (tx, ty) of a footprint that leaves the content and
     * stores its right, lower and lower-right neighbours in pts[0..2].
     */
    private static int getPointsToInterpolateAtEdge(int[] pts, int[] data, int offset, int stride,
            int tx, int txLast, int ty, int tyLast, int wrapMode) {
        int x0 = wrapTexel(tx, txLast, wrapMode);
        int x1 = wrapTexel(tx + 1, txLast, wrapMode);
        int y0 = wrapTexel(ty, tyLast, wrapMode);
        int y1 = wrapTexel(ty + 1, tyLast, wrapMode);
        pts[0] = texelAt(data, offset, stride, x1, y0);
        pts[1] = texelAt(data, offset, stride, x0, y1);
        pts[2] = texelAt(data, offset, stride, x1, y1);
        return texelAt(data, offset, stride, x0, y0);
    }

    /**
     * {@code genTexturePaintTarget}: samples the texture into {@code paint[0 .. alphaWidth * height)}. The edge
     * rule of the bilinear footprint, the nearest-sampling clamp and the GENERIC band are the C's; see
     * {@code native-prism-sw/PiscesPaint.c:388-401}, {@code :468-473} and {@code :896-906} at {@code 42a5072596}.
     */
    static void genTexturePaintTarget(PiscesRendererState rdr, int[] paint, int height) {
        int paintStride = rdr.alphaWidth;

        int[] txtData = rdr.textureIntData;
        int txtOffset = rdr.textureIntOffset;
        int txtWidth = rdr.textureImageWidth;
        int txtHeight = rdr.textureImageHeight;
        int txtStride = rdr.textureStride;
        int txMin = rdr.textureTxMin;
        int tyMin = rdr.textureTyMin;
        int txMax = rdr.textureTxMax;
        int tyMax = rdr.textureTyMax;
        int wrapMode = rdr.textureWrapMode;
        boolean repeat = (wrapMode == RendererBase.WRAP_REPEAT);
        // never true for nearest sampling, whose NO_REPEAT reads stay inside nxMin..nxMax x nyMin..nyMax
        boolean zeroEdge = (wrapMode == RendererBase.WRAP_CLAMP_TO_ZERO) && rdr.textureInterpolate;
        // Nearest sampling without REPEAT clamps the texel under the pixel centre to the drawn
        // sub-rectangle txMin..txMax x tyMin..tyMax; a source of zero width reads texel txMax.
        int nxMin = Math.max(0, Math.min(txMin, txMax));
        int nxMax = Math.max(0, txMax);
        int nyMin = Math.max(0, Math.min(tyMin, tyMax));
        int nyMax = Math.max(0, tyMax);
        int repeatInterpolateMode;

        if (rdr.textureInterpolate) {
            // the transparent texels beyond a CLAMP_TO_ZERO edge need the alpha interpolation; on an
            // opaque texture (alpha 0xff) it gives the same pixels as the no-alpha one inside the content
            if (rdr.textureHasAlpha || wrapMode == RendererBase.WRAP_CLAMP_TO_ZERO) {
                repeatInterpolateMode = (repeat) ?
                    REPEAT_INTERPOLATE_ALPHA : NO_REPEAT_INTERPOLATE_ALPHA;
            } else {
                repeatInterpolateMode = (repeat) ?
                    REPEAT_INTERPOLATE_NO_ALPHA : NO_REPEAT_INTERPOLATE_NO_ALPHA;
            }
        } else {
            repeatInterpolateMode = (repeat) ?
                REPEAT_NO_INTERPOLATE : NO_REPEAT_NO_INTERPOLATE;
        }

        int[] pts = new int[3];

        switch (rdr.textureTransformType) {
            // IDENTITY falls through to TRANSLATE, as in the C
            case PiscesRendererState.TEXTURE_TRANSFORM_IDENTITY:
            case PiscesRendererState.TEXTURE_TRANSFORM_TRANSLATE: {
                int paintOffset = 0;
                int y = rdr.currY;

                for (int j = 0; j < height; j++, y++) {
                    int pidx = paintOffset;

                    int x = rdr.currX;

                    long ltx = (x << 16) + rdr.textureM02;
                    long lty = (y << 16) + rdr.textureM12;

                    // we can compute here since (m00 == 65536) && (m10 == 0)
                    int tx = (int) (ltx >> 16);
                    int ty = (int) (lty >> 16);
                    int hfrac = (int) (ltx & 0xffff);
                    int vfrac = (int) (lty & 0xffff);

                    if (repeat) {
                        // only a bilinear footprint may start at tyMin - 1 unwrapped
                        lty = checkBoundsRepeat(ty, lty, (rdr.textureInterpolate) ? tyMin - 1 : tyMin, tyMax);
                        ty = (int) (lty >> 16);
                    } else if (rdr.textureInterpolate) {
                        ty = checkBoundsNoRepeat(ty, tyMin - 1, tyMax, txtHeight - 1, zeroEdge);
                    } else {
                        ty = checkBoundsNoRepeat(ty, nyMin, nyMax, txtHeight - 1, zeroEdge);
                    }

                    int a = pidx;
                    int am = a + paintStride;

                    switch (repeatInterpolateMode) {
                        case NO_REPEAT_NO_INTERPOLATE: {
                            int txtRow = txtOffset + (Math.max(0, ty) * txtStride);
                            tx = (int) (ltx >> 16);
                            while (tx < nxMin && a < am) {
                                paint[a++] = txtData[txtRow + nxMin];
                                ++tx;
                            }
                            int len = Math.min(am - a, nxMax - tx + 1);
                            if (len > 0) {
                                System.arraycopy(txtData, txtRow + tx, paint, a, len);
                                a += len;
                            }
                            while (a < am) {
                                paint[a++] = txtData[txtRow + nxMax];
                            }
                            break;
                        }
                        case REPEAT_NO_INTERPOLATE:
                            while (a < am) {
                                tx = (int) (ltx >> 16);
                                ltx = checkBoundsRepeat(tx, ltx, txMin, txMax);
                                tx = (int) (ltx >> 16);
                                int sidx = Math.max(0, ty) * txtStride + Math.max(0, tx);
                                paint[pidx] = txtData[txtOffset + sidx];
                                ++a;
                                ++pidx;
                                ltx += 0x10000;
                            }
                            break;
                        case NO_REPEAT_INTERPOLATE_ALPHA:
                            while (a < am) {
                                tx = (int) (ltx >> 16);
                                tx = checkBoundsNoRepeat(tx, txMin - 1, txMax, txtWidth - 1, zeroEdge);
                                paint[pidx] = sampleAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += 0x10000;
                            }
                            break;
                        case REPEAT_INTERPOLATE_ALPHA:
                            while (a < am) {
                                tx = (int) (ltx >> 16);
                                ltx = checkBoundsRepeat(tx, ltx, txMin - 1, txMax);
                                tx = (int) (ltx >> 16);
                                paint[pidx] = sampleAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += 0x10000;
                            }
                            break;
                        case NO_REPEAT_INTERPOLATE_NO_ALPHA:
                            while (a < am) {
                                tx = (int) (ltx >> 16);
                                tx = checkBoundsNoRepeat(tx, txMin - 1, txMax, txtWidth - 1, zeroEdge);
                                paint[pidx] = sampleNoAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += 0x10000;
                            }
                            break;
                        case REPEAT_INTERPOLATE_NO_ALPHA:
                            while (a < am) {
                                tx = (int) (ltx >> 16);
                                ltx = checkBoundsRepeat(tx, ltx, txMin - 1, txMax);
                                tx = (int) (ltx >> 16);
                                paint[pidx] = sampleNoAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += 0x10000;
                            }
                            break;
                        default:
                            break;
                    }
                    paintOffset += paintStride;
                }
                break;
            }

            // scale transform
            case PiscesRendererState.TEXTURE_TRANSFORM_SCALE_TRANSLATE: {
                int paintOffset = 0;
                int y = rdr.currY;

                for (int j = 0; j < height; j++, y++) {
                    int pidx = paintOffset;

                    int x = rdr.currX;

                    long ltx = x * rdr.textureM00 + y * rdr.textureM01 + rdr.textureM02;
                    long lty = x * rdr.textureM10 + y * rdr.textureM11 + rdr.textureM12;

                    int a = pidx;
                    int am = a + paintStride;

                    switch (repeatInterpolateMode) {
                        case NO_REPEAT_NO_INTERPOLATE:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                tx = checkBoundsNoRepeat(tx, nxMin, nxMax, txtWidth - 1, zeroEdge);
                                ty = checkBoundsNoRepeat(ty, nyMin, nyMax, txtHeight - 1, zeroEdge);
                                int sidx = Math.max(0, ty) * txtStride + Math.max(0, tx);
                                paint[pidx] = txtData[txtOffset + sidx];
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case REPEAT_NO_INTERPOLATE:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                ltx = checkBoundsRepeat(tx, ltx, txMin, txMax);
                                tx = (int) (ltx >> 16);
                                lty = checkBoundsRepeat(ty, lty, tyMin, tyMax);
                                ty = (int) (lty >> 16);
                                int sidx = Math.max(0, ty) * txtStride + Math.max(0, tx);
                                paint[pidx] = txtData[txtOffset + sidx];
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case NO_REPEAT_INTERPOLATE_ALPHA:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int hfrac = (int) (ltx & 0xffff);
                                int vfrac = (int) (lty & 0xffff);
                                tx = checkBoundsNoRepeat(tx, txMin - 1, txMax, txtWidth - 1, zeroEdge);
                                ty = checkBoundsNoRepeat(ty, tyMin - 1, tyMax, txtHeight - 1, zeroEdge);
                                paint[pidx] = sampleAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case REPEAT_INTERPOLATE_ALPHA:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int hfrac = (int) (ltx & 0xffff);
                                int vfrac = (int) (lty & 0xffff);
                                ltx = checkBoundsRepeat(tx, ltx, txMin - 1, txMax);
                                tx = (int) (ltx >> 16);
                                lty = checkBoundsRepeat(ty, lty, tyMin - 1, tyMax);
                                ty = (int) (lty >> 16);
                                paint[pidx] = sampleAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case NO_REPEAT_INTERPOLATE_NO_ALPHA:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int hfrac = (int) (ltx & 0xffff);
                                int vfrac = (int) (lty & 0xffff);
                                tx = checkBoundsNoRepeat(tx, txMin - 1, txMax, txtWidth - 1, zeroEdge);
                                ty = checkBoundsNoRepeat(ty, tyMin - 1, tyMax, txtHeight - 1, zeroEdge);
                                paint[pidx] = sampleNoAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case REPEAT_INTERPOLATE_NO_ALPHA:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int hfrac = (int) (ltx & 0xffff);
                                int vfrac = (int) (lty & 0xffff);
                                ltx = checkBoundsRepeat(tx, ltx, txMin - 1, txMax);
                                tx = (int) (ltx >> 16);
                                lty = checkBoundsRepeat(ty, lty, tyMin - 1, tyMax);
                                ty = (int) (lty >> 16);
                                paint[pidx] = sampleNoAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        default:
                            break;
                    }
                    paintOffset += paintStride;
                }
                break;
            }

            // generic transform
            case PiscesRendererState.TEXTURE_TRANSFORM_GENERIC: {
                int paintOffset = 0;
                // Without REPEAT a pixel is sampled, bounded as in the other branches, only while its centre
                // lies within bu (bv) of the drawn sub-rectangle; further out it is transparent. Quarter turns
                // and mirror diagonals (m00 == m11 == 0) widen the band to half the pixel's extent in texture
                // space, at least half a texel (PiscesPaint.c:896-906 at 42a5072596).
                boolean quarter = (rdr.textureM00 == 0 && rdr.textureM11 == 0);
                long bu = (quarter) ? Math.max(0x8000, abs(rdr.textureM01) >> 1) : 0x8000;
                long bv = (quarter) ? Math.max(0x8000, abs(rdr.textureM10) >> 1) : 0x8000;
                // ltx and lty are the pixel centre for nearest sampling and half a texel less for bilinear
                long half = (rdr.textureInterpolate) ? 0x8000 : 0;
                long uLo = ((long) txMin << 16) - bu - half;
                long uHi = ((long) (txMax + 1) << 16) + bu - half;
                long vLo = ((long) tyMin << 16) - bv - half;
                long vHi = ((long) (tyMax + 1) << 16) + bv - half;

                int y = rdr.currY;

                for (int j = 0; j < height; j++, y++) {
                    int pidx = paintOffset;

                    int x = rdr.currX;

                    long ltx = x * rdr.textureM00 + y * rdr.textureM01 + rdr.textureM02;
                    long lty = x * rdr.textureM10 + y * rdr.textureM11 + rdr.textureM12;

                    int a = pidx;
                    int am = a + paintStride;

                    switch (repeatInterpolateMode) {
                        case NO_REPEAT_NO_INTERPOLATE:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int p00;
                                if (ltx >= uLo && ltx < uHi && lty >= vLo && lty < vHi) {
                                    tx = checkBoundsNoRepeat(tx, nxMin, nxMax, txtWidth - 1, zeroEdge);
                                    ty = checkBoundsNoRepeat(ty, nyMin, nyMax, txtHeight - 1, zeroEdge);
                                    int sidx = Math.max(0, ty) * txtStride + Math.max(0, tx);
                                    p00 = txtData[txtOffset + sidx];
                                } else {
                                    p00 = 0x00000000;
                                }
                                paint[pidx] = p00;
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case REPEAT_NO_INTERPOLATE:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                ltx = checkBoundsRepeat(tx, ltx, txMin, txMax);
                                tx = (int) (ltx >> 16);
                                lty = checkBoundsRepeat(ty, lty, tyMin, tyMax);
                                ty = (int) (lty >> 16);
                                int sidx = Math.max(0, ty) * txtStride + Math.max(0, tx);
                                paint[pidx] = txtData[txtOffset + sidx];
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case NO_REPEAT_INTERPOLATE_ALPHA:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int hfrac = (int) (ltx & 0xffff);
                                int vfrac = (int) (lty & 0xffff);
                                int cval;
                                if (ltx >= uLo && ltx < uHi && lty >= vLo && lty < vHi) {
                                    tx = checkBoundsNoRepeat(tx, txMin - 1, txMax, txtWidth - 1, zeroEdge);
                                    ty = checkBoundsNoRepeat(ty, tyMin - 1, tyMax, txtHeight - 1, zeroEdge);
                                    cval = sampleAlpha(pts, txtData, txtOffset, txtStride,
                                        tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                } else {
                                    cval = 0x00000000;
                                }
                                paint[pidx] = cval;
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case REPEAT_INTERPOLATE_ALPHA:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int hfrac = (int) (ltx & 0xffff);
                                int vfrac = (int) (lty & 0xffff);
                                ltx = checkBoundsRepeat(tx, ltx, txMin - 1, txMax);
                                tx = (int) (ltx >> 16);
                                lty = checkBoundsRepeat(ty, lty, tyMin - 1, tyMax);
                                ty = (int) (lty >> 16);
                                paint[pidx] = sampleAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case NO_REPEAT_INTERPOLATE_NO_ALPHA:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int hfrac = (int) (ltx & 0xffff);
                                int vfrac = (int) (lty & 0xffff);
                                int cval;
                                if (ltx >= uLo && ltx < uHi && lty >= vLo && lty < vHi) {
                                    tx = checkBoundsNoRepeat(tx, txMin - 1, txMax, txtWidth - 1, zeroEdge);
                                    ty = checkBoundsNoRepeat(ty, tyMin - 1, tyMax, txtHeight - 1, zeroEdge);
                                    cval = sampleNoAlpha(pts, txtData, txtOffset, txtStride,
                                        tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                } else {
                                    cval = 0x00000000;
                                }
                                paint[pidx] = cval;
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        case REPEAT_INTERPOLATE_NO_ALPHA:
                            while (a < am) {
                                int tx = (int) (ltx >> 16);
                                int ty = (int) (lty >> 16);
                                int hfrac = (int) (ltx & 0xffff);
                                int vfrac = (int) (lty & 0xffff);
                                ltx = checkBoundsRepeat(tx, ltx, txMin - 1, txMax);
                                tx = (int) (ltx >> 16);
                                lty = checkBoundsRepeat(ty, lty, tyMin - 1, tyMax);
                                ty = (int) (lty >> 16);
                                paint[pidx] = sampleNoAlpha(pts, txtData, txtOffset, txtStride,
                                    tx, txtWidth - 1, ty, txtHeight - 1, wrapMode, hfrac, vfrac);
                                ++a;
                                ++pidx;
                                ltx += rdr.textureM00;
                                lty += rdr.textureM10;
                            }
                            break;
                        default:
                            break;
                    }
                    paintOffset += paintStride;
                }
                break;
            }

            default:
                break;
        }
    }

    /** {@code ABS} of {@code PiscesUtil.h} on a jlong. */
    private static long abs(long x) {
        return (x) > 0 ? (x) : -(x);
    }

    /** {@code genTexturePaint}. */
    static void genTexturePaint(PiscesRendererState rdr, int height) {
        genTexturePaintTarget(rdr, rdr.paint, height);
    }

    /**
     * {@code genTexturePaintMultiply}: the texture times the paint that was set before it. A previous paint
     * mode without a case here (a texture) leaves the paint buffer as it is, stale content included, as in the C.
     */
    static void genTexturePaintMultiply(PiscesRendererState rdr, int height) {
        int xFrom = rdr.minTouched;
        int xTo = rdr.maxTouched;
        int w = (xTo - xFrom + 1);
        int[] paint = rdr.paint;
        int paintStride = rdr.alphaWidth;
        int calpha = rdr.calpha;
        int cred = rdr.cred;
        int cgreen = rdr.cgreen;
        int cblue = rdr.cblue;

        switch (rdr.prevPaintMode) {
            case PiscesRendererState.PAINT_FLAT_COLOR: {
                genTexturePaintTarget(rdr, paint, height);
                int palpha1 = calpha + 1;
                if (cred == 0xFF && cgreen == 0xFF && cblue == 0xFF) {
                    if (calpha < 0xFF) {
                        for (int i = 0; i < height; i++) {
                            int idx = i * paintStride;
                            for (int j = 0; j < w; j++) {
                                int tval = paint[idx + j];
                                int oalpha = (palpha1 * ((tval >> 24) & 0xFF)) >> 8;
                                int ored = (palpha1 * ((tval >> 16) & 0xFF)) >> 8;
                                int ogreen = (palpha1 * ((tval >> 8) & 0xFF)) >> 8;
                                int oblue = (palpha1 * (tval & 0xFF)) >> 8;
                                paint[idx + j] = (oalpha << 24) | (ored << 16) | (ogreen << 8) | oblue;
                            }
                        }
                    }
                } else {
                    for (int i = 0; i < height; i++) {
                        int idx = i * paintStride;
                        for (int j = 0; j < w; j++) {
                            int tval = paint[idx + j];
                            int oalpha = (palpha1 * ((tval >> 24) & 0xFF)) >> 8;
                            int ored = ((((cred + 1) * ((tval >> 16) & 0xFF)) >> 8) * palpha1) >> 8;
                            int ogreen = ((((cgreen + 1) * ((tval >> 8) & 0xFF)) >> 8) * palpha1) >> 8;
                            int oblue = ((((cblue + 1) * (tval & 0xFF)) >> 8) * palpha1) >> 8;
                            paint[idx + j] = (oalpha << 24) | (ored << 16) | (ogreen << 8) | oblue;
                        }
                    }
                }
                break;
            }
            case PiscesRendererState.PAINT_LINEAR_GRADIENT:
            case PiscesRendererState.PAINT_RADIAL_GRADIENT:
                if (w > 0 && height > 0 && (w < (Integer.MAX_VALUE / height / 4))) {
                    int[] imagePaint = new int[w * height];
                    if (rdr.prevPaintMode == PiscesRendererState.PAINT_LINEAR_GRADIENT) {
                        genLinearGradientPaint(rdr, height);
                    } else {
                        genRadialGradientPaint(rdr, height);
                    }
                    genTexturePaintTarget(rdr, imagePaint, height);
                    for (int i = 0; i < height; i++) {
                        int idx = i * paintStride;
                        for (int j = 0; j < w; j++) {
                            int pval = paint[idx + j];
                            int tval = imagePaint[idx + j];
                            int palpha1 = ((pval >> 24) & 0xFF) + 1;
                            int oalpha = (palpha1 * ((tval >> 24) & 0xFF)) >> 8;
                            int ored = ((((((pval >> 16) & 0xFF) + 1) * ((tval >> 16) & 0xFF)) >> 8)
                                    * palpha1) >> 8;
                            int ogreen = ((((((pval >> 8) & 0xFF) + 1) * ((tval >> 8) & 0xFF)) >> 8)
                                    * palpha1) >> 8;
                            int oblue = (((((pval & 0xFF) + 1) * (tval & 0xFF)) >> 8) * palpha1) >> 8;
                            paint[idx + j] = (oalpha << 24) | (ored << 16) | (ogreen << 8) | oblue;
                        }
                    }
                } else {
                    System.err.println("Invalid dimensions: width: " + w + ", height: " + height);
                }
                break;
            default:
                break;
        }
    }
}
