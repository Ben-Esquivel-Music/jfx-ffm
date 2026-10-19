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

import java.util.Arrays;

/**
 * The blits of the Pisces compositor: {@code native-prism-sw/PiscesBlit.c} of commit {@code 42a5072596}, one
 * method per C function, in the C's integer arithmetic. A C pointer walk ({@code a = intData + iidx; ...
 * a += imagePixelStride}) is an index into the same array here. Every routine gives each destination pixel (and
 * each delta it consumes) the value its C counterpart gives it, from the same inputs, and fails where the
 * per-pixel C loop would read out of bounds, after the pixels before that point, as long as the array index does
 * not overflow {@code int} (a C pointer cannot overflow there; an overflowing index can end a loop before it
 * reads).
 * <p>
 * US-062 departs from that C at one point. In a row that a box covers only in part, the C decided from the edge
 * fractions scaled by the row's coverage whether the first and the last column are edge columns, and a
 * 1/65536 px sliver, whose fraction that scaling truncates to 0, was painted as an interior column; the colour
 * {@code SRC} routine, which counts its interior columns from the unscaled fractions, began its interior run one
 * column early in a left sliver and left a right sliver as it was. {@link #emitLineSource8888Pre},
 * {@link #emitLinePTSource8888Pre} and {@link #emitLinePTSourceOver8888Pre} decide from the unscaled fractions
 * ({@code elLfrac}, {@code elRfrac}), as {@link #emitLineSourceOver8888Pre} already did, and blend the sliver
 * at its scaled fraction, 0; in a row where no edge fraction scales to 0 they give the C's pixels.
 * <p>
 * Every surface has a pixel stride of 1 ({@link PiscesSurfaceState}), and every renderer call
 * sets {@code imagePixelStride} to it before a routine here runs, so the C's {@code a += imagePixelStride}
 * steps are unit index steps here. The loops keep the C's bounds ({@code am = a + w}) and the order of their
 * array accesses; written as counted loops they let the JIT drop the per-pixel range checks. A few routines
 * take a faster path to the same ints, each explained where it is: the pixels after a zero coverage delta
 * ({@link #blitSrcOver8888Pre}, {@link #blitPTSrcOver8888Pre}), the full-coverage paint blend
 * ({@link #paintOverFullCoverage}), the zero-coverage LCD table ({@link #initGammaArrays}) and the rows of one
 * mask in one call (the mask blits).
 * <p>
 * The LCD gamma tables are process-global, as in the C: {@link #initGammaArrays} rebuilds them when the gamma
 * changes, and every renderer reads them.
 */
final class PiscesBlit {

    static final int MIN_ALPHA = 0;
    static final int MAX_ALPHA = 255;

    private static float currentGamma = -1;
    private static final int[] GAMMA_ARRAY = new int[256];
    private static final int[] INV_GAMMA_ARRAY = new int[256];

    /**
     * What {@link #blendLCDSrcOver8888Pre} gives a destination channel {@code d} under zero coverage:
     * {@code GAMMA_ARRAY[div255(255 * INV_GAMMA_ARRAY[d])]}, valid (and then exact) while
     * {@link #zeroCoverageValid}, that is while every inverse gamma entry lies in [0, 255], which keeps the
     * gamma index in range. The tables start zeroed, for which this one is zeroed and valid.
     */
    private static final int[] ZERO_COVERAGE_ARRAY = new int[256];
    private static boolean zeroCoverageValid = true;

    private PiscesBlit() {
    }

    private static int div255(int x) {
        return (x * 257 + 257) >> 16;
    }

    private static int a(int x) {
        return (x >> 24) & 0xFF;
    }

    private static int r(int x) {
        return (x >> 16) & 0xFF;
    }

    private static int g(int x) {
        return (x >> 8) & 0xFF;
    }

    private static int b(int x) {
        return x & 0xFF;
    }

    /** {@code emitLineSource8888_pre}. */
    static void emitLineSource8888Pre(PiscesRendererState rdr, int height, int frac) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;

        int calpha = rdr.calpha;
        int cred = rdr.cred;
        int cgreen = rdr.cgreen;
        int cblue = rdr.cblue;

        int lfrac = rdr.elLfrac;
        int rfrac = rdr.elRfrac;

        int minX = rdr.minTouched;
        int w = rdr.alphaWidth;
        w -= (lfrac != 0) ? 1 : 0;
        w -= (rfrac != 0) ? 1 : 0;

        if (frac == 0x10000) { // full coverage
            int preRed = ((calpha + 1) * cred) >> 8;
            int preGreen = ((calpha + 1) * cgreen) >> 8;
            int preBlue = ((calpha + 1) * cblue) >> 8;
            int pixel = (calpha << 24) | (preRed << 16) | (preGreen << 8) | preBlue;
            for (int j = 0; j < height; j++) {
                int a = imageOffset + minX;
                if (lfrac != 0) {
                    blendSrc8888Pre(intData, a, calpha, 255 - (lfrac >> 8), cred, cgreen, cblue);
                    a++;
                }
                int am = a + w;
                for (; a < am; a++) {
                    intData[a] = pixel;
                }
                if (rfrac != 0) {
                    blendSrc8888Pre(intData, a, calpha, 255 - (rfrac >> 8), cred, cgreen, cblue);
                }
                imageOffset += imageScanlineStride;
            }
        } else {
            int compFrac = 255 - (frac >> 8);
            long llfrac = (lfrac * (long) frac);
            long lrfrac = (rfrac * (long) frac);
            lfrac = (int) (llfrac >> 16);
            rfrac = (int) (lrfrac >> 16);
            for (int j = 0; j < height; j++) {
                int a = imageOffset + minX;
                // the unscaled fractions decide the edge columns, as they decided w (US-062)
                if (rdr.elLfrac != 0) {
                    blendSrc8888Pre(intData, a, calpha, 255 - (lfrac >> 8), cred, cgreen, cblue);
                    a++;
                }
                int am = a + w;
                for (; a < am; a++) {
                    blendSrc8888Pre(intData, a, calpha, compFrac, cred, cgreen, cblue);
                }
                if (rdr.elRfrac != 0) {
                    blendSrc8888Pre(intData, a, calpha, 255 - (rfrac >> 8), cred, cgreen, cblue);
                }
                imageOffset += imageScanlineStride;
            }
        }
    }

    /** {@code emitLinePTSource8888_pre}. */
    static void emitLinePTSource8888Pre(PiscesRendererState rdr, int height, int frac) {
        int paintOffset = 0;

        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;

        int[] paint = rdr.paint;
        int cval;
        int paintStride;

        int compFrac = 255 - (frac >> 8);
        long llfrac = (rdr.elLfrac * (long) frac);
        long lrfrac = (rdr.elRfrac * (long) frac);
        int lfrac = (int) (llfrac >> 16);
        int rfrac = (int) (lrfrac >> 16);

        int minX = rdr.minTouched;
        int w = rdr.alphaWidth;
        paintStride = w;
        // the unscaled fractions decide the edge columns: a sliver whose fraction the row's coverage scales
        // to 0 is blended at 0, not painted as an interior column (US-062)
        w -= (rdr.elLfrac != 0) ? 1 : 0;
        w -= (rdr.elRfrac != 0) ? 1 : 0;

        for (int j = 0; j < height; j++) {
            int aidx = paintOffset;
            int a = imageOffset + minX;
            if (rdr.elLfrac != 0) {
                cval = paint[aidx];
                blendSrc8888PrePre(intData, a, a(cval), 255 - (lfrac >> 8), r(cval), g(cval), b(cval));
                a++;
                aidx++;
            }
            int am = a + w;
            if (frac == 0x10000) { // full coverage
                for (; a < am; a++, aidx++) {
                    intData[a] = paint[aidx];
                }
            } else {
                for (; a < am; a++, aidx++) {
                    cval = paint[aidx];
                    blendSrc8888PrePre(intData, a, a(cval), compFrac, r(cval), g(cval), b(cval));
                }
            }
            if (rdr.elRfrac != 0) {
                cval = paint[aidx];
                blendSrc8888PrePre(intData, a, a(cval), 255 - (rfrac >> 8), r(cval), g(cval), b(cval));
            }
            imageOffset += imageScanlineStride;
            paintOffset += paintStride;
        }
    }

    /** {@code emitLineSourceOver8888_pre}. */
    static void emitLineSourceOver8888Pre(PiscesRendererState rdr, int height, int frac) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;

        int calpha = rdr.calpha;
        int cred = rdr.cred;
        int cgreen = rdr.cgreen;
        int cblue = rdr.cblue;
        int alpha = (calpha * frac) >> 16;

        int lfrac = rdr.elLfrac;
        int rfrac = rdr.elRfrac;

        int minX = rdr.minTouched;
        int w = rdr.alphaWidth;
        w -= (lfrac != 0) ? 1 : 0;
        w -= (rfrac != 0) ? 1 : 0;

        if (alpha == MAX_ALPHA) {
            int solidPixel = 0xFF000000 | (cred << 16) | (cgreen << 8) | cblue;
            for (int j = 0; j < height; j++) {
                int a = imageOffset + minX;
                if (lfrac != 0) {
                    blendSrcOver8888Pre(intData, a, lfrac >> 8, cred, cgreen, cblue);
                    a++;
                }
                int am = a + w;
                for (; a < am; a++) {
                    intData[a] = solidPixel;
                }
                if (rfrac != 0) {
                    blendSrcOver8888Pre(intData, a, rfrac >> 8, cred, cgreen, cblue);
                }
                imageOffset += imageScanlineStride;
            }
        } else {
            int lalpha = (lfrac * alpha) >> 16;
            int ralpha = (rfrac * alpha) >> 16;
            for (int j = 0; j < height; j++) {
                int a = imageOffset + minX;
                if (lfrac != 0) {
                    blendSrcOver8888Pre(intData, a, lalpha, cred, cgreen, cblue);
                    a++;
                }
                int am = a + w;
                for (; a < am; a++) {
                    blendSrcOver8888Pre(intData, a, alpha, cred, cgreen, cblue);
                }
                if (rfrac != 0) {
                    blendSrcOver8888Pre(intData, a, ralpha, cred, cgreen, cblue);
                }
                imageOffset += imageScanlineStride;
            }
        }
    }

    /** {@code emitLinePTSourceOver8888_pre}. */
    static void emitLinePTSourceOver8888Pre(PiscesRendererState rdr, int height, int frac) {
        int paintOffset = 0;

        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;

        int[] paint = rdr.paint;
        int cval;
        int paintStride;

        long llfrac = (rdr.elLfrac * (long) frac);
        long lrfrac = (rdr.elRfrac * (long) frac);
        int lfrac = (int) (llfrac >> 16);
        int rfrac = (int) (lrfrac >> 16);

        int minX = rdr.minTouched;
        int w = rdr.alphaWidth;
        paintStride = w;
        // the unscaled fractions decide the edge columns: a sliver whose fraction the row's coverage scales
        // to 0 is blended at 0, not painted as an interior column (US-062)
        w -= (rdr.elLfrac != 0) ? 1 : 0;
        w -= (rdr.elRfrac != 0) ? 1 : 0;

        for (int j = 0; j < height; j++) {
            int aidx = paintOffset;
            int a = imageOffset + minX;
            if (rdr.elLfrac != 0) {
                cval = paint[aidx];
                blendSrcOver8888PrePre(intData, a, lfrac >> 8, a(cval), r(cval), g(cval), b(cval));
                a++;
                aidx++;
            }
            int am = a + w;
            if (frac == 0x10000) { // full coverage
                int n = am - a;
                if (n > 0) {
                    paintOverFullCoverage(rdr, intData, a, paint, aidx, n);
                    a = am;
                    aidx += n;
                }
            } else {
                int sfrac = frac >> 8;
                for (; a < am; a++, aidx++) {
                    cval = paint[aidx];
                    blendSrcOver8888PrePre(intData, a, sfrac, a(cval), r(cval), g(cval), b(cval));
                }
            }
            if (rdr.elRfrac != 0) {
                cval = paint[aidx];
                blendSrcOver8888PrePre(intData, a, rfrac >> 8, a(cval), r(cval), g(cval), b(cval));
            }
            imageOffset += imageScanlineStride;
            paintOffset += paintStride;
        }
    }

    /**
     * {@code blitSrc8888_pre}. Each row restarts at the first delta, as in the C, whose callers always pass a
     * height of 1.
     */
    static void blitSrc8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        int[] alpha = rdr.rowAAInt;
        int alphaStart = rdr.rowAAIntOffset;

        int calpha = rdr.calpha;
        int cred = rdr.cred;
        int cgreen = rdr.cgreen;
        int cblue = rdr.cblue;
        byte[] alphaMap = rdr.alphaMap;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        for (int j = 0; j < height; j++) {
            int iidx = imageOffset + minX;

            int avalRelative = 0;
            int a = alphaStart;
            int am = a + w;
            for (; a < am; a++, iidx++) {
                avalRelative += alpha[a];
                alpha[a] = 0;
                int acoverage = alphaMap[avalRelative] & 0xff;
                if (acoverage == MAX_ALPHA) {
                    intData[iidx] = (calpha << 24) | (cred << 16) | (cgreen << 8) | cblue;
                } else if (acoverage > 0) {
                    int aval = ((acoverage + 1) * calpha) >> 8;
                    blendSrc8888Pre(intData, iidx, aval, 255 - acoverage,
                        cred, cgreen, cblue);
                }
            }

            imageOffset += imageScanlineStride;
        }
    }

    /**
     * {@code blitSrcMask8888_pre}. Row {@code j} reads the mask row {@code maskOffset + j * maskWidth}: the rows
     * of one mask come in one call ({@code PiscesRendererState.fillAlphaMask}), where the C makes one call per row.
     */
    static void blitSrcMask8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        byte[] alpha = rdr.maskByteData;
        int alphaOffset = rdr.maskOffset;
        int alphaStride = rdr.maskWidth;

        int calpha = rdr.calpha;
        int cred = rdr.cred;
        int cgreen = rdr.cgreen;
        int cblue = rdr.cblue;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        for (int j = 0; j < height; j++) {
            int iidx = imageOffset + minX;

            int a = alphaOffset;
            int am = a + w;
            for (; a < am; a++, iidx++) {
                int acoverage = alpha[a] & 0xff;
                // run in integers otherwise it overflows
                if (acoverage == MAX_ALPHA) {
                    intData[iidx] = (calpha << 24) | (cred << 16) | (cgreen << 8) | cblue;
                } else if (acoverage > 0) {
                    int aval = ((acoverage + 1) * calpha) >> 8;
                    blendSrc8888Pre(intData, iidx, aval, 255 - acoverage,
                        cred, cgreen, cblue);
                }
            }

            imageOffset += imageScanlineStride;
            alphaOffset += alphaStride;
        }
    }

    /** {@code blitPTSrc8888_pre}. */
    static void blitPTSrc8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        int[] alpha = rdr.rowAAInt;
        int alphaStart = rdr.rowAAIntOffset;

        byte[] alphaMap = rdr.alphaMap;

        int[] paint = rdr.paint;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        for (int j = 0; j < height; j++) {
            int aidx = 0;
            int iidx = imageOffset + minX;

            int avalRelative = 0;
            int a = alphaStart;
            int am = a + w;
            for (; a < am; a++, iidx++, aidx++) {
                int cval = paint[aidx];
                int palpha = a(cval);

                avalRelative += alpha[a];
                alpha[a] = 0;
                int acoverage = alphaMap[avalRelative] & 0xff;

                if (acoverage == MAX_ALPHA) {
                    intData[iidx] = cval;
                } else if (acoverage > 0) {
                    int aval = ((acoverage + 1) * palpha) >> 8;
                    blendSrc8888PrePre(intData, iidx, aval, 255 - acoverage, r(cval), g(cval), b(cval));
                }
            }

            imageOffset += imageScanlineStride;
        }
    }

    /** {@code blitPTSrcMask8888_pre}; the C never advances the mask offset between rows. */
    static void blitPTSrcMask8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        byte[] alpha = rdr.maskByteData;
        int alphaOffset = rdr.maskOffset;

        int[] paint = rdr.paint;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        for (int j = 0; j < height; j++) {
            int aidx = 0;
            int iidx = imageOffset + minX;

            int a = alphaOffset;
            int am = a + w;
            for (; a < am; a++, iidx++, aidx++) {
                int cval = paint[aidx];
                int palpha = a(cval);

                int acoverage = alpha[a] & 0xff;

                if (acoverage == MAX_ALPHA) {
                    intData[iidx] = cval;
                } else if (acoverage > 0) {
                    int aval = ((acoverage + 1) * palpha) >> 8;
                    blendSrc8888PrePre(intData, iidx, aval, 255 - acoverage, r(cval), g(cval), b(cval));
                }
            }

            imageOffset += imageScanlineStride;
        }
    }

    /** {@code blitSrcOver8888_pre}. */
    static void blitSrcOver8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        int[] alpha = rdr.rowAAInt;
        int alphaStart = rdr.rowAAIntOffset;

        int calpha = rdr.calpha;
        int cred = rdr.cred;
        int cgreen = rdr.cgreen;
        int cblue = rdr.cblue;
        byte[] alphaMap = rdr.alphaMap;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        int solidPixel = 0xff000000 | (cred << 16) | (cgreen << 8) | cblue;

        for (int j = 0; j < height; j++) {
            int iidx = imageOffset + minX;

            int avalRelative = 0;
            int aval = 0;
            int a = alphaStart;
            int am = a + w;
            while (a < am) {
                avalRelative += alpha[a];
                alpha[a] = 0;
                if (avalRelative != 0) {
                    aval = alphaMap[avalRelative] & 0xff;
                    aval = ((aval + 1) * calpha) >> 8;
                    if (aval == MAX_ALPHA) {
                        intData[iidx] = solidPixel;
                    } else if (aval > 0) {
                        blendSrcOver8888Pre(intData, iidx, aval, cred, cgreen, cblue);
                    }
                }
                a++;
                iidx++;
                int runEnd = Math.min(am, alpha.length);
                if (a < runEnd && alpha[a] == 0) {
                    // A zero delta keeps avalRelative, so the C composites the pixels up to the next nonzero
                    // delta exactly as the one before them; their deltas are already 0. The run stops at the
                    // end of the array, so a delta index past it fails in the read of alpha[a] above, after the
                    // pixels before it, as in the per-pixel loop.
                    int run = a + 1;
                    while (run < runEnd && alpha[run] == 0) {
                        run++;
                    }
                    int end = iidx + (run - a);
                    if (avalRelative != 0) {
                        if (aval == MAX_ALPHA) {
                            for (int i = iidx; i < end; i++) {
                                intData[i] = solidPixel;
                            }
                        } else if (aval > 0) {
                            for (int i = iidx; i < end; i++) {
                                blendSrcOver8888Pre(intData, i, aval, cred, cgreen, cblue);
                            }
                        }
                    }
                    iidx = end;
                    a = run;
                }
            }

            imageOffset += imageScanlineStride;
        }
    }

    /**
     * {@code blitSrcOverMask8888_pre}. Row {@code j} reads the mask row {@code maskOffset + j * maskWidth}: the rows
     * of one mask come in one call ({@code PiscesRendererState.fillAlphaMask}), where the C makes one call per row.
     */
    static void blitSrcOverMask8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        byte[] alpha = rdr.maskByteData;
        int alphaOffset = rdr.maskOffset;
        int alphaStride = rdr.maskWidth;

        int calpha = rdr.calpha;
        int cred = rdr.cred;
        int cgreen = rdr.cgreen;
        int cblue = rdr.cblue;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        for (int j = 0; j < height; j++) {
            int iidx = imageOffset + minX;

            int a = alphaOffset;
            int am = a + w;
            for (; a < am; a++, iidx++) {
                int aval = alpha[a] & 0xff;
                if (aval != 0) {
                    // run in integers otherwise it overflows
                    aval = ((aval + 1) * calpha) >> 8;
                    if (aval == MAX_ALPHA) {
                        intData[iidx] = 0xff000000 | (cred << 16) | (cgreen << 8) | cblue;
                    } else if (aval > 0) {
                        blendSrcOver8888Pre(intData, iidx, aval, cred, cgreen, cblue);
                    }
                }
            }

            imageOffset += imageScanlineStride;
            alphaOffset += alphaStride;
        }
    }

    /**
     * {@code blitSrcOverLCDMask8888_pre}: three mask bytes per pixel, through the LCD gamma tables. Row {@code j}
     * reads the mask row {@code maskOffset + j * maskWidth}: the rows of one mask come in one call
     * ({@code PiscesRendererState.fillAlphaMask}), where the C makes one call per row.
     */
    static void blitSrcOverLCDMask8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        byte[] alpha = rdr.maskByteData;
        int alphaOffset = rdr.maskOffset;
        int alphaStride = rdr.maskWidth;

        int calpha = INV_GAMMA_ARRAY[rdr.calpha];
        int cred = INV_GAMMA_ARRAY[rdr.cred];
        int cgreen = INV_GAMMA_ARRAY[rdr.cgreen];
        int cblue = INV_GAMMA_ARRAY[rdr.cblue];
        boolean zeroCoverage = zeroCoverageValid;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        for (int j = 0; j < height; j++) {
            int pixel = imageOffset + minX;

            // the C walks a from alphaOffset to alphaOffset + 3 * w, three bytes per pixel
            for (int i = 0; i < w; i++) {
                int iidx = pixel + i;
                int a = alphaOffset + 3 * i;
                int ared = alpha[a] & 0xff;
                int agreen = alpha[a + 1] & 0xff;
                int ablue = alpha[a + 2] & 0xff;
                if (calpha < MAX_ALPHA) {
                    ared = ((ared + 1) * calpha) >> 8;
                    agreen = ((agreen + 1) * calpha) >> 8;
                    ablue = ((ablue + 1) * calpha) >> 8;
                }
                int avalIsmax = ared & agreen & ablue;
                if (avalIsmax == MAX_ALPHA) {
                    intData[iidx] = 0xff000000 | (cred << 16) | (cgreen << 8) | cblue;
                } else if ((ared | agreen | ablue) == 0 && zeroCoverage) {
                    // blendLCDSrcOver8888Pre with zero coverage, from the table
                    int ival = intData[iidx];
                    intData[iidx] = 0xFF000000 | (ZERO_COVERAGE_ARRAY[(ival >> 16) & 0xff] << 16)
                            | (ZERO_COVERAGE_ARRAY[(ival >> 8) & 0xff] << 8) | ZERO_COVERAGE_ARRAY[ival & 0xff];
                } else {
                    blendLCDSrcOver8888Pre(intData, iidx, ared, agreen, ablue,
                        cred, cgreen, cblue);
                }
            }

            imageOffset += imageScanlineStride;
            alphaOffset += alphaStride;
        }
    }

    /** {@code blitPTSrcOver8888_pre}. */
    static void blitPTSrcOver8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        int[] alpha = rdr.rowAAInt;
        int alphaStart = rdr.rowAAIntOffset;

        byte[] alphaMap = rdr.alphaMap;

        int[] paint = rdr.paint;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        for (int j = 0; j < height; j++) {
            int aidx = 0;
            int iidx = imageOffset + minX;

            int avalRelative = 0;
            int malpha = 0;
            int a = alphaStart;
            int am = a + w;
            while (a < am) {
                int cval = paint[aidx];
                int palpha = a(cval);

                avalRelative += alpha[a];
                alpha[a] = 0;
                if (avalRelative != 0) {
                    malpha = alphaMap[avalRelative] & 0xff;
                    int aval = ((malpha + 1) * palpha) >> 8;

                    if (aval == MAX_ALPHA) {
                        intData[iidx] = cval;
                    } else if (aval > 0) {
                        blendSrcOver8888PrePre(intData, iidx, malpha + 1, palpha, r(cval), g(cval), b(cval));
                    }
                }
                a++;
                iidx++;
                aidx++;
                int runEnd = Math.min(am, alpha.length);
                if (a < runEnd && alpha[a] == 0) {
                    // the pixels up to the next nonzero delta have this coverage (see blitSrcOver8888Pre)
                    int run = a + 1;
                    while (run < runEnd && alpha[run] == 0) {
                        run++;
                    }
                    int n = run - a;
                    if (avalRelative != 0) {
                        paintOverCoverage(rdr, intData, iidx, paint, aidx, n, malpha + 1);
                    }
                    a = run;
                    iidx += n;
                    aidx += n;
                }
            }

            imageOffset += imageScanlineStride;
        }
    }

    /**
     * The paint {@code paint[aidx .. aidx + n)} over {@code intData[iidx .. iidx + n)} at the coverage
     * {@code frac} (alpha map value + 1), pixel by pixel as {@link #blitPTSrcOver8888Pre} composites one: nothing
     * where {@code ((frac * palpha) >> 8)} is 0, the paint where it is 255, else
     * {@link #blendSrcOver8888PrePre}. Full coverage ({@code frac == 256}) is {@link #paintOverFullCoverage}:
     * {@code (x * 256) >> 8 == x} turns the blend into {@link #blendSrcOver8888PrePreFullFrac}.
     */
    private static void paintOverCoverage(PiscesRendererState rdr, int[] intData, int iidx, int[] paint, int aidx,
            int n, int frac) {
        if (frac == 256) {
            paintOverFullCoverage(rdr, intData, iidx, paint, aidx, n);
            return;
        }
        for (int k = 0; k < n; k++) {
            int cval = paint[aidx + k];
            int palpha = a(cval);
            int aval = (frac * palpha) >> 8;
            if (aval == MAX_ALPHA) {
                intData[iidx + k] = cval;
            } else if (aval > 0) {
                blendSrcOver8888PrePre(intData, iidx + k, frac, palpha, r(cval), g(cval), b(cval));
            }
        }
    }

    /** Below this many pixels {@link #paintOverFullCoverage} always takes the per-pixel loop. */
    private static final int SPLIT_BLEND_MIN_PIXELS = 16;

    /**
     * The full-coverage paint over the destination, as {@code emitLinePTSourceOver8888_pre} composites it at
     * {@code frac == 0x10000}: per pixel nothing for paint alpha 0, the paint for 255, else
     * {@link #blendSrcOver8888PrePreFullFrac}.
     * <p>
     * When a sample of a run of at least {@link #SPLIT_BLEND_MIN_PIXELS} pixels says that many of them need the
     * blend ({@link #mostlyTranslucent}), every pixel is computed in two passes small enough for the JIT to
     * vectorize ({@link #paintOverFullCoverageSplit}); otherwise the per-pixel loop's cheap cases win. Both give
     * every pixel the C's value, so the sample only picks the faster loop. The blend itself gives
     * the paint for alpha 255 (its {@code 255 - palpha} is 0), so the split passes only have to keep the
     * destination where the paint alpha is 0; they compute the channels as the blend does and or them together
     * in two steps, which is the same int, overflow bits included.
     */
    private static void paintOverFullCoverage(PiscesRendererState rdr, int[] intData, int iidx, int[] paint,
            int aidx, int n) {
        if (n >= SPLIT_BLEND_MIN_PIXELS && mostlyTranslucent(paint, aidx, n)) {
            paintOverFullCoverageSplit(rdr, intData, iidx, paint, aidx, n);
        } else {
            for (int k = 0; k < n; k++) {
                int cval = paint[aidx + k];
                int palpha = a(cval);
                switch (palpha) {
                    case 0:
                        break;
                    case MAX_ALPHA:
                        intData[iidx + k] = cval;
                        break;
                    default:
                        blendSrcOver8888PrePreFullFrac(intData, iidx + k, palpha, r(cval), g(cval), b(cval));
                        break;
                }
            }
        }
    }

    /**
     * Whether more than a sixth of 16 evenly spaced paint pixels of {@code paint[aidx .. aidx + n)}, {@code n >= 16},
     * have an alpha other than 0 and 255.
     */
    private static boolean mostlyTranslucent(int[] paint, int aidx, int n) {
        int step = n / SPLIT_BLEND_MIN_PIXELS;
        int translucent = 0;
        for (int k = 0; k < SPLIT_BLEND_MIN_PIXELS; k++) {
            int palpha = a(paint[aidx + k * step]);
            if (palpha != 0 && palpha != MAX_ALPHA) {
                translucent++;
            }
        }
        return translucent * 6 > SPLIT_BLEND_MIN_PIXELS;
    }

    /** {@link #paintOverFullCoverage} in two vectorizable passes. */
    private static void paintOverFullCoverageSplit(PiscesRendererState rdr, int[] intData, int iidx, int[] paint,
            int aidx, int n) {
        int[] alphaRed = rdr.blendScratch(n);
        for (int k = 0; k < n; k++) {
            int cval = paint[aidx + k];
            int ival = intData[iidx + k];
            int oneminusaval = 255 - a(cval);
            int oalpha = a(cval) + div255(oneminusaval * a(ival));
            int ored = r(cval) + div255(oneminusaval * r(ival));
            alphaRed[k] = (oalpha << 24) | (ored << 16);
        }
        for (int k = 0; k < n; k++) {
            int cval = paint[aidx + k];
            int ival = intData[iidx + k];
            int palpha = a(cval);
            int oneminusaval = 255 - palpha;
            int ogreen = g(cval) + div255(oneminusaval * g(ival));
            int oblue = b(cval) + div255(oneminusaval * b(ival));
            int blended = alphaRed[k] | (ogreen << 8) | oblue;
            int keep = (palpha - 1) >> 31; // -1 where palpha == 0: the C leaves that pixel alone
            intData[iidx + k] = (blended & ~keep) | (ival & keep);
        }
    }

    /** {@code blitPTSrcOverMask8888_pre}; the C never advances the mask offset between rows. */
    static void blitPTSrcOverMask8888Pre(PiscesRendererState rdr, int height) {
        int[] intData = rdr.data;
        int imageOffset = rdr.currImageOffset;
        int imageScanlineStride = rdr.imageScanlineStride;
        byte[] alpha = rdr.maskByteData;
        int alphaOffset = rdr.maskOffset;

        int[] paint = rdr.paint;

        int minX = rdr.minTouched;
        int maxX = rdr.maxTouched;
        int w = (maxX >= minX) ? (maxX - minX + 1) : 0;

        for (int j = 0; j < height; j++) {
            int aidx = 0;
            int iidx = imageOffset + minX;

            int a = alphaOffset;
            int am = a + w;
            for (; a < am; a++, iidx++, aidx++) {
                int malpha = alpha[a] & 0xff;
                if (malpha != 0) {
                    int cval = paint[aidx];
                    int palpha = a(cval);

                    int aval = ((malpha + 1) * palpha) >> 8;

                    if (aval == MAX_ALPHA) {
                        intData[iidx] = cval;
                    } else if (aval > 0) {
                        blendSrcOver8888PrePre(intData, iidx, malpha + 1, palpha, r(cval), g(cval), b(cval));
                    }
                }
            }

            imageOffset += imageScanlineStride;
        }
    }

    /**
     * {@code clearRect8888_any}: fills the rectangle with the internal colour. The C counts the bytes of its
     * {@code memset}/{@code memcpy} calls in {@code int} ({@code int size = sizeof(jint) * w}, {@code size * h}),
     * so those counts are int products here too; they only wrap for surfaces of 2^29 pixels and more.
     */
    static void clearRect8888Any(PiscesRendererState rdr, int x, int y, int w, int h) {
        int cval = (rdr.calpha << 24) | (rdr.cred << 16) |
                   (rdr.cgreen << 8) | rdr.cblue;
        int pixelStride = rdr.imagePixelStride;
        int[] intData = rdr.data;
        int start = rdr.imageOffset + y * rdr.imageScanlineStride + x * pixelStride;
        int size = 4 * w;

        if (cval == 0) {
            if (x == 0 && w == rdr.width) {
                // memset(intData, 0, size * h): the rows are contiguous
                Arrays.fill(intData, start, start + ((size * h) >> 2), 0);
            } else {
                for (; h > 0; --h) {
                    Arrays.fill(intData, start, start + (size >> 2), 0);
                    start += rdr.imageScanlineStride;
                }
            }
        } else {
            //set first scanline to cval
            Arrays.fill(intData, start, start + w, cval);
            int firstRow = start;
            //set to starting pixel in the second row
            start += rdr.imageScanlineStride;
            for (h--; h > 0; --h) {
                System.arraycopy(intData, firstRow, intData, start, size >> 2);
                start += rdr.imageScanlineStride;
            }
        }
    }

    // intData[i] is premultiplied, sred, sgreen, sblue are non-premultiplied
    private static void blendSrcOver8888Pre(int[] intData, int i,
                                            int aval,
                                            int sred, int sgreen, int sblue) {
        int ival = intData[i];
        //destination alpha
        int dalpha = (ival >> 24) & 0xff;
        //destination components premultiplied by dalpha
        int dred = (ival >> 16) & 0xff;
        int dgreen = (ival >> 8) & 0xff;
        int dblue = ival & 0xff;

        int oneminusaval = (255 - aval);

        int oalpha  = div255(255 * aval    + oneminusaval * dalpha);
        int ored    = div255(sred * aval   + oneminusaval * dred);
        int ogreen  = div255(sgreen * aval + oneminusaval * dgreen);
        int oblue   = div255(sblue * aval  + oneminusaval * dblue);

        intData[i] = (oalpha << 24) | (ored << 16) | (ogreen << 8) | oblue;
    }

    // intData[i] is premultiplied, sred, sgreen, sblue are premultiplied
    private static void blendSrcOver8888PrePre(int[] intData, int i, int frac,
                                               int aval,
                                               int sred, int sgreen, int sblue) {
        int ival = intData[i];
        //destination alpha
        int dalpha = (ival >> 24) & 0xff;
        //destination components premultiplied by dalpha
        int dred = (ival >> 16) & 0xff;
        int dgreen = (ival >> 8) & 0xff;
        int dblue = ival & 0xff;

        int aval2 = (aval * frac) >> 8;
        int oneminusaval = (255 - aval2);

        int oalpha  = aval2                  + div255(oneminusaval * dalpha);
        int ored    = ((sred * frac) >> 8)   + div255(oneminusaval * dred);
        int ogreen  = ((sgreen * frac) >> 8) + div255(oneminusaval * dgreen);
        int oblue   = ((sblue * frac) >> 8)  + div255(oneminusaval * dblue);

        intData[i] = (oalpha << 24) | (ored << 16) | (ogreen << 8) | oblue;
    }

    // intData[i] is premultiplied, sred, sgreen, sblue are premultiplied
    private static void blendSrcOver8888PrePreFullFrac(int[] intData, int i, int aval,
                                                       int sred, int sgreen, int sblue) {
        int ival = intData[i];
        //destination alpha
        int dalpha = (ival >> 24) & 0xff;
        //destination components premultiplied by dalpha
        int dred = (ival >> 16) & 0xff;
        int dgreen = (ival >> 8) & 0xff;
        int dblue = ival & 0xff;

        int oneminusaval = (255 - aval);

        int oalpha  = aval   + div255(oneminusaval * dalpha);
        int ored    = sred   + div255(oneminusaval * dred);
        int ogreen  = sgreen + div255(oneminusaval * dgreen);
        int oblue   = sblue  + div255(oneminusaval * dblue);

        intData[i] = (oalpha << 24) | (ored << 16) | (ogreen << 8) | oblue;
    }

    // intData[i] is premultiplied, sred, sgreen, sblue are NOT premultiplied
    // it is required that final alpha must be fully opaque (0xFF)
    private static void blendLCDSrcOver8888Pre(int[] intData, int i,
                                               int ared, int agreen, int ablue,
                                               int sred, int sgreen, int sblue) {
        int ival = intData[i];
        //destination components premultiplied by dalpha
        int dred = (ival >> 16) & 0xff;
        int dgreen = (ival >> 8) & 0xff;
        int dblue = ival & 0xff;

        dred = INV_GAMMA_ARRAY[dred];
        dgreen = INV_GAMMA_ARRAY[dgreen];
        dblue = INV_GAMMA_ARRAY[dblue];

        int ored    = div255(ared * sred     + (255 - ared) * dred);
        int ogreen  = div255(agreen * sgreen + (255 - agreen) * dgreen);
        int oblue   = div255(ablue * sblue   + (255 - ablue) * dblue);

        ored = GAMMA_ARRAY[ored];
        ogreen = GAMMA_ARRAY[ogreen];
        oblue = GAMMA_ARRAY[oblue];

        intData[i] = 0xFF000000 | (ored << 16) | (ogreen << 8) | oblue;
    }

    private static void blendSrc8888Pre(int[] intData, int i,
                                        int aval, int raaval,
                                        int sred, int sgreen, int sblue) {
        int ival = intData[i];
        int dalpha = (ival >> 24) & 0xff;
        //premultiplied color components
        int dred =   (ival >> 16) & 0xff;
        int dgreen = (ival >>  8) & 0xff;
        int dblue =  (ival & 0xff);

        int denom = 255 * aval + dalpha * raaval;
        if (denom == 0) {
            // The output is transparent black
            intData[i] = 0x00000000;
        } else {
            int oalpha  = div255(denom);
            int ored    = div255(aval * sred   + raaval * dred);
            int ogreen  = div255(aval * sgreen + raaval * dgreen);
            int oblue   = div255(aval * sblue  + raaval * dblue);

            intData[i] = (oalpha << 24) | (ored << 16) | (ogreen << 8) | oblue;
        }
    }

    // sred, sgreen, sblue are all premultiplied
    private static void blendSrc8888PrePre(int[] intData, int i,
                                           int aval, int raaval,
                                           int sred, int sgreen, int sblue) {
        int ival = intData[i];
        int dalpha = (ival >> 24) & 0xff;
        //premultiplied color components
        int dred =   (ival >> 16) & 0xff;
        int dgreen = (ival >>  8) & 0xff;
        int dblue =  (ival & 0xff);

        int denom = 255 * aval + dalpha * raaval;
        if (denom == 0) {
            // The output is transparent black
            intData[i] = 0x00000000;
        } else {
            int oalpha  = div255(denom);
            int ored    = sred   + div255(raaval * dred);
            int ogreen  = sgreen + div255(raaval * dgreen);
            int oblue   = sblue  + div255(raaval * dblue);

            intData[i] = (oalpha << 24) | (ored << 16) | (ogreen << 8) | oblue;
        }
    }

    /**
     * {@code initGammaArrays}: rebuilds the process-global tables when {@code gamma} differs from the current
     * one (a float comparison, so NaN rebuilds every time). {@code invgamma} is a float quotient, as in the C;
     * both exponents are then widened to double for {@code pow}. The zero-coverage table
     * ({@link #ZERO_COVERAGE_ARRAY}), which is not in the C, is rebuilt with them.
     */
    static void initGammaArrays(float gamma) {
        if (currentGamma != gamma) {
            float invgamma = 1.0f / gamma;
            currentGamma = gamma;
            for (int i = 0; i < 256; i++) {
                GAMMA_ARRAY[i] = PiscesTransform.d2i(255 * pow(i / 255.0, gamma));
                INV_GAMMA_ARRAY[i] = PiscesTransform.d2i(255 * pow(i / 255.0, invgamma));
            }
            initZeroCoverageArray();
        }
    }

    /** Rebuilds {@link #ZERO_COVERAGE_ARRAY} from the current tables, if every inverse gamma entry allows it. */
    private static void initZeroCoverageArray() {
        boolean valid = true;
        for (int i = 0; i < 256; i++) {
            valid &= (INV_GAMMA_ARRAY[i] >= 0 && INV_GAMMA_ARRAY[i] <= MAX_ALPHA);
        }
        if (valid) {
            for (int i = 0; i < 256; i++) {
                ZERO_COVERAGE_ARRAY[i] = GAMMA_ARRAY[div255(255 * INV_GAMMA_ARRAY[i])];
            }
        }
        zeroCoverageValid = valid;
    }

    /**
     * The C library {@code pow} for a base in [0, 1]. C99 (F.9.4.4) defines {@code pow(+1, y)} as 1 for every
     * {@code y}, NaN and infinities included, where {@link StrictMath#pow} answers NaN for an infinite or NaN
     * exponent; for every other base in [0, 1] the special cases of the two agree.
     */
    static double pow(double x, double y) {
        if (x == 1.0) {
            return 1.0;
        }
        return StrictMath.pow(x, y);
    }
}
