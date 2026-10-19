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
import com.sun.pisces.Transform6;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Two fixed-point edge cases of the Pisces software compositor ({@code com.sun.pisces}), driven through the public
 * {@link PiscesRenderer} and {@link JavaSurface} API (US-062). The expected pixels come from geometry in doubles
 * and from the texel index wrapped with {@link Math#floorMod}, not from the compositor's fixed-point arithmetic.
 * <p>
 * <b>A box sliver.</b> {@code fillRect} gives the first and the last column of a box the 16.16 fraction of the
 * column the box covers, and a row the box covers only in part blends those columns at that fraction times the
 * row's coverage. A box edge 1/65536 px from a pixel boundary leaves a fraction of 1 there, which the row's
 * coverage scales to 0. The colour {@code SRC} blit and the texture blits under {@code SRC} and {@code SRC_OVER}
 * decided from the scaled fraction whether the column is an edge column, so in those rows the sliver was painted
 * as an interior column at the row's coverage. The colour {@code SRC} blit counts its interior columns from the
 * unscaled fractions, so there the interior run started one column early, in the sliver, and the last column was
 * left as it was. The colour {@code SRC_OVER} blit decided from the unscaled fraction already and is the control.
 * Each route draws a box whose left edge lies 1/65536 px before a pixel boundary and one whose right edge lies
 * 1/65536 px past one, both with a fractional top and bottom row, over a translucent background. Every pixel outside
 * the box keeps the background, every pixel the box covers by c (its area, in doubles) is within 1 per 8-bit
 * channel of what c gives, and the sliver column is pinned exactly: {@code SRC_OVER} weights the source by the
 * coverage, so the sliver, whose coverage is 0 in 8 bits, keeps the background; this compositor's {@code SRC} keeps
 * the whole source and weights the destination by the uncovered part, S + (1 - c) D ({@code blendSrc8888Pre}), so
 * the sliver is S + D in every row, the value the rows the box covers in full already gave it (their 8-bit
 * fraction of the sliver is 0 too).
 * <p>
 * The geometry of the review of US-059: a texture drawn under a mirror diagonal ({@code GENERIC}) and the transposed
 * texture under its exact axis-aligned twin ({@code SCALE_TRANSLATE}), with the same 16.16 entries and the same
 * box, have to give the same frame for nearest-neighbour sampling, on all 5184 cases of that review's grid (scales
 * 0.25 to 3, 64 positions, three wrap modes, three sub-rectangles). Before US-062, 16 cases differed, all at scale
 * 0.7 and X = 60.3, where the box edge truncates to 61 * 65536 - 1: the axis-aligned twin painted the sliver
 * column's pixels in the box's fractional rows, the mirror diagonal left them transparent.
 * <p>
 * <b>REPEAT past 32767 texels.</b> {@code checkBoundsRepeat} wraps a texture coordinate modulo the sub-rectangle's
 * width or height, 65536 times that as a 16.16 coordinate. It shifted an {@code int}, which overflows from 32768
 * texels on (and is 0 from 65536 on, a division by zero), so the wrap went wrong past 32767 texels. Sub-rectangles
 * 32767 (the control), 32768, 40000, 65535, 65536 and 65537 texels wide, and one 65536 texels high, are filled with
 * {@code setTexture} and drawn with {@code drawImage}, nearest and bilinear. A 1/2048 scale puts the centre of pixel
 * 20 on the centre of the last texel, or halfway between the last texel and texel 0, and samples past both ends of
 * the texture; a translation by 5 (5.5 for bilinear) samples below texel 0. Every pixel holds the texel under its
 * sample, wrapped with {@link Math#floorMod}, or, halfway between two texels, their mean within 0.5 per channel.
 */
public class PiscesFixedPointEdgeTest {

    private static final int ARGB_PRE = RendererBase.TYPE_INT_ARGB_PRE;
    private static final int NORMAL = RendererBase.IMAGE_MODE_NORMAL;
    private static final int SRC = RendererBase.COMPOSITE_SRC;
    private static final int SRC_OVER = RendererBase.COMPOSITE_SRC_OVER;
    private static final int KEEP = RendererBase.IMAGE_FRAC_EDGE_KEEP;
    private static final int CLAMP = RendererBase.WRAP_CLAMP_TO_EDGE;
    private static final int REPEAT = RendererBase.WRAP_REPEAT;
    private static final int ZERO = RendererBase.WRAP_CLAMP_TO_ZERO;
    private static final int ONE = 1 << 16;

    /*
     * A box sliver
     */

    private static final int W = 16;
    private static final int H = 8;

    /**
     * Premultiplied black at alpha 0x21: translucent, so that the SRC blits show the coverage. The largest
     * difference from the area oracle is then 0.86 (8-bit coverage steps of 1/256, truncated); at alpha 0x20 the
     * SRC routes reach the tolerance exactly.
     */
    private static final int BACKGROUND = 0x21000000;

    /** The paint colour, not premultiplied. */
    private static final int RED = 255;
    private static final int GREEN = 128;
    private static final int BLUE = 0;
    private static final int ALPHA = 128;

    /** A row that both boxes cover in full: rows 2 and 5 are covered by 0.75 and 0.5, rows 3 and 4 in full. */
    private static final int FULL_ROW = 3;

    /** Tolerance of the area oracle, per 8-bit channel. */
    private static final double TOLERANCE = 1;

    private static final Box LEFT_SLIVER = new Box("left edge 1/65536 px before x = 5",
            5 * ONE - 1, fx(2.25), fx(9.5) - (5 * ONE - 1), fx(3.25), 4);
    private static final Box RIGHT_SLIVER = new Box("right edge 1/65536 px past x = 9",
            fx(3.25), fx(2.25), 9 * ONE + 1 - fx(3.25), fx(3.25), 9);

    /** A box in S15.16 surface coordinates; {@code sliverColumn} is the column it covers by 1/65536 px. */
    record Box(String name, int x, int y, int w, int h, int sliverColumn) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** A blit route of {@code fillRect}: the composite rule, and a colour or a texture paint. */
    enum Route {
        COLOUR_SRC(SRC, false),
        TEXTURE_SRC(SRC, true),
        TEXTURE_SRC_OVER(SRC_OVER, true),
        COLOUR_SRC_OVER(SRC_OVER, false);

        final int rule;
        final boolean texture;

        Route(int rule, boolean texture) {
            this.rule = rule;
            this.texture = texture;
        }
    }

    static Stream<Arguments> sliverCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Route route : Route.values()) {
            cases.add(Arguments.of(route, LEFT_SLIVER));
            cases.add(Arguments.of(route, RIGHT_SLIVER));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("sliverCases")
    public void aSliverColumnKeepsItsOwnCoverageAndEveryOtherColumnItsOwnPlace(Route route, Box box) {
        int[] frame = fill(route, box);
        List<String> wrong = new ArrayList<>();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int actual = frame[y * W + x];
                double c = overlap(box.x(), box.w(), x) * overlap(box.y(), box.h(), y);
                if (c == 0) {
                    if (actual != BACKGROUND) {
                        wrong.add(f("(%d,%d) lies outside the box but is %08X", x, y, actual));
                    }
                    continue;
                }
                if (x == box.sliverColumn()) {
                    int pinned = (route.rule == SRC) ? frame[FULL_ROW * W + x] : BACKGROUND;
                    if (actual != pinned) {
                        wrong.add(f("(%d,%d) sliver is %08X, not %08X", x, y, actual, pinned));
                    }
                }
                double[] expected = expected(route, x, c);
                for (int k = 0; k < 4; k++) {
                    int channel = (actual >>> (24 - 8 * k)) & 0xFF;
                    if (Math.abs(channel - expected[k]) > TOLERANCE) {
                        wrong.add(f("(%d,%d) coverage %.6f: %08X, channel %d is %d, not %.3f", x, y, c, actual, k,
                                channel, expected[k]));
                        break;
                    }
                }
            }
        }
        assertNone(wrong, route + ", " + box);
    }

    private static int[] fill(Route route, Box box) {
        int[] pixels = new int[W * H];
        Arrays.fill(pixels, BACKGROUND);
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, ARGB_PRE, W, H));
        pr.setCompositeRule(route.rule);
        if (route.texture) {
            int[] texels = new int[W * H];
            for (int i = 0; i < texels.length; i++) {
                texels[i] = sliverTexel(i % W);
            }
            pr.setTexture(ARGB_PRE, texels, W, H, W, new Transform6(), CLAMP, false, true);
        } else {
            pr.setColor(RED, GREEN, BLUE, ALPHA);
        }
        pr.fillRect(box.x(), box.y(), box.w(), box.h());
        return pixels;
    }

    /**
     * The texel of column x, premultiplied: alpha 0xC0, red and green a multiple of 0x40 that changes from column
     * to column, blue 0x40. Under the identity transform pixel (x, y) samples it.
     */
    private static int sliverTexel(int x) {
        return 0xC0000000 | (((x & 3) * 0x40) << 16) | ((((x >> 2) & 3) * 0x40) << 8) | 0x40;
    }

    /**
     * Alpha, red, green and blue a pixel covered by {@code c} should have: the premultiplied source S, then
     * S + (1 - c) D under SRC and c S + (1 - c alpha(S) / 255) D under SRC_OVER.
     */
    private static double[] expected(Route route, int x, double c) {
        double[] s = new double[4];
        if (route.texture) {
            int t = sliverTexel(x);
            for (int k = 0; k < 4; k++) {
                s[k] = (t >>> (24 - 8 * k)) & 0xFF;
            }
        } else {
            s[0] = ALPHA;
            s[1] = RED * ALPHA / 255.0;
            s[2] = GREEN * ALPHA / 255.0;
            s[3] = BLUE * ALPHA / 255.0;
        }
        double[] e = new double[4];
        for (int k = 0; k < 4; k++) {
            double d = (BACKGROUND >>> (24 - 8 * k)) & 0xFF;
            e[k] = (route.rule == SRC) ? s[k] + (1 - c) * d : c * s[k] + (1 - c * s[0] / 255) * d;
        }
        return e;
    }

    /** How much of pixel {@code pixel} the S15.16 span [from, from + length) covers, in doubles. */
    private static double overlap(int from, int length, int pixel) {
        double lo = Math.max(pixel, from / 65536.0);
        double hi = Math.min(pixel + 1, (from + (double) length) / 65536.0);
        return Math.max(0, hi - lo);
    }

    /*
     * The twins of the review of US-059
     */

    private static final int TWIN_SIZE = 160;
    private static final int TWIN_CASES = 5184;

    @Test
    public void everyMirrorDiagonalNearestDrawEqualsItsAxisAlignedTwin() {
        int[] texels = twinTexels();
        int[] transposed = new int[5 * 7];
        for (int v = 0; v < 5; v++) {
            for (int u = 0; u < 7; u++) {
                transposed[u * 5 + v] = texels[v * 7 + u];
            }
        }
        double[] scales = {0.25, 0.3, 1.0 / 3.0, 0.5, 0.7, 1.0, 1.5, 2.0, 3.0};
        double[] fractions = {0, 0.125, 0.25, 0.3, 0.5, 0.625, 0.77, 0.875};
        int[] generic = new int[TWIN_SIZE * TWIN_SIZE];
        int[] axis = new int[TWIN_SIZE * TWIN_SIZE];
        List<String> differing = new ArrayList<>();
        List<String> unpainted = new ArrayList<>();
        int cases = 0;
        for (double s : scales) {
            for (int position = 0; position < 64; position++) {
                for (int wrap : new int[] {CLAMP, REPEAT, ZERO}) {
                    for (int sub = 0; sub < 3; sub++) {
                        double x = 60 + fractions[position % 8];
                        double y = 50 + fractions[position / 8];
                        Transform6 mirror = new Transform6(0, toS(s), toS(s), 0, toS(x), toS(y));
                        Transform6 twin = new Transform6(toS(s), 0, 0, toS(s), toS(x), toS(y));
                        int x0 = (sub == 1) ? 1 : 0;
                        int y0 = (sub == 1) ? 1 : 0;
                        int x1 = (sub == 1) ? 5 : 6;
                        int y1 = (sub == 1) ? 3 : 4;
                        double[] bounds = (sub == 2) ? bounds(mirror, 0.5, 0.25, 6.25, 4.5)
                                : bounds(mirror, x0, y0, x1 + 1, y1 + 1);
                        drawTwin(generic, mirror, texels, 7, 5, wrap, bounds, x0, y0, x1, y1);
                        drawTwin(axis, twin, transposed, 5, 7, wrap, bounds, y0, x0, y1, x1);
                        cases++;
                        int n = 0;
                        int painted = 0;
                        for (int i = 0; i < axis.length; i++) {
                            n += (generic[i] != axis[i]) ? 1 : 0;
                            painted += (axis[i] != 0) ? 1 : 0;
                        }
                        String what = f("scale %.3f X %.3f Y %.3f wrap %d sub %d", s, x, y, wrap, sub);
                        if (n > 0) {
                            differing.add(what + ": " + n + " pixels differ");
                        }
                        if (painted == 0) {
                            unpainted.add(what);
                        }
                    }
                }
            }
        }
        assertEquals(TWIN_CASES, cases);
        assertEquals(List.of(), unpainted, "twin draws that painted nothing");
        assertEquals(List.of(), differing, differing.size() + " of " + cases + " nearest twin pairs differ");
    }

    /**
     * The 7 x 5 translucent texture of the review's probe: the C library's {@code rand} recurrence from seed 4242
     * gives each texel an alpha of 0x30 to 0xAF and a green and blue no larger than it; red is 0.
     */
    private static int[] twinTexels() {
        int[] texels = new int[7 * 5];
        int seed = 4242;
        for (int i = 0; i < texels.length; i++) {
            seed = seed * 1103515245 + 12345;
            int a = 0x30 + ((seed >>> 16) & 0x7F);
            seed = seed * 1103515245 + 12345;
            int g = ((seed >>> 16) & 0xFF) % (a + 1);
            seed = seed * 1103515245 + 12345;
            int b = ((seed >>> 16) & 0xFF) % (a + 1);
            texels[i] = (a << 24) | (g << 8) | b;
        }
        return texels;
    }

    /** The device bounds of the texture rectangle [u0, u1) x [v0, v1) under {@code t}, in doubles. */
    private static double[] bounds(Transform6 t, double u0, double v0, double u1, double v1) {
        double[] us = {u0, u1, u0, u1};
        double[] vs = {v0, v0, v1, v1};
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int k = 0; k < 4; k++) {
            double x = (t.m00 * us[k] + t.m01 * vs[k]) / 65536.0 + t.m02 / 65536.0;
            double y = (t.m10 * us[k] + t.m11 * vs[k]) / 65536.0 + t.m12 / 65536.0;
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.max(b[2], x);
            b[3] = Math.max(b[3], y);
        }
        return b;
    }

    private static void drawTwin(int[] pixels, Transform6 t, int[] texels, int w, int h, int wrap, double[] b,
            int txMin, int tyMin, int txMax, int tyMax) {
        Arrays.fill(pixels, 0);
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, ARGB_PRE, TWIN_SIZE, TWIN_SIZE));
        pr.setClip(0, 0, TWIN_SIZE, TWIN_SIZE);
        pr.setCompositeRule(SRC_OVER);
        pr.drawImage(ARGB_PRE, NORMAL, texels, w, h, 0, w, t, wrap, false,
                toS(b[0]), toS(b[1]), toS(b[2] - b[0]), toS(b[3] - b[1]), KEEP, KEEP, KEEP, KEEP,
                txMin, tyMin, txMax, tyMax, true);
    }

    /** The C's {@code (int) (v * 65536.0)}: truncation toward zero, as the review's probe converted. */
    private static int toS(double v) {
        return (int) (v * 65536.0);
    }

    /*
     * REPEAT past 32767 texels
     */

    /** The length of the surface along the texture's long side; the other side is 2 pixels. */
    private static final int LENGTH = 64;
    /** The pixel whose centre samples the last texel, or the seam between it and texel 0. */
    private static final int SEAM_PIXEL = 20;
    /** 1/2048 in 16.16: 2048 texels per pixel. */
    private static final int SCALE = 32;

    /** How a case maps the texture onto the surface, along its long side. */
    enum Sampling {
        NEAREST_ACROSS_THE_LAST_TEXEL(false),
        NEAREST_TRANSLATED_BY_5(false),
        BILINEAR_ON_TEXEL_CENTRES_ACROSS_THE_LAST_TEXEL(true),
        BILINEAR_HALFWAY_ACROSS_THE_SEAM(true),
        BILINEAR_HALFWAY_TRANSLATED_BY_5_5(true);

        final boolean linear;

        Sampling(boolean linear) {
            this.linear = linear;
        }

        /** The texture-to-device scale (16.16) along the long side. */
        int scale() {
            return (this == NEAREST_TRANSLATED_BY_5 || this == BILINEAR_HALFWAY_TRANSLATED_BY_5_5) ? ONE : SCALE;
        }

        /**
         * The texture-to-device translation (16.16) along the long side. Across the last texel the centre of
         * {@link #SEAM_PIXEL} maps to u = n - 1/2 (the centre of the last texel, which a bilinear sample half a
         * texel lower reads alone) or to u = n (the bilinear sample then lies halfway between the last texel and
         * texel 0); all entries and their inverse are exact in 16.16 and in float.
         */
        int translation(int n) {
            return switch (this) {
                case NEAREST_ACROSS_THE_LAST_TEXEL, BILINEAR_ON_TEXEL_CENTRES_ACROSS_THE_LAST_TEXEL ->
                        fx(SEAM_PIXEL + 0.5) - SCALE * n + SCALE / 2;
                case BILINEAR_HALFWAY_ACROSS_THE_SEAM -> fx(SEAM_PIXEL + 0.5) - SCALE * n;
                case NEAREST_TRANSLATED_BY_5 -> fx(5);
                case BILINEAR_HALFWAY_TRANSLATED_BY_5_5 -> fx(5.5);
            };
        }
    }

    /** Which side of the texture is long: a sub-rectangle n x 1 or 1 x n. */
    enum Side {
        WIDE,
        HIGH
    }

    /** How the texture reaches the paint. */
    enum Call {
        SET_TEXTURE_THEN_FILL_RECT,
        DRAW_IMAGE
    }

    static Stream<Arguments> repeatCases() {
        List<Arguments> cases = new ArrayList<>();
        for (int n : new int[] {32767, 32768, 40000, 65535, 65536, 65537}) {
            addRepeatCases(cases, n, Side.WIDE);
        }
        addRepeatCases(cases, 65536, Side.HIGH);
        return cases.stream();
    }

    private static void addRepeatCases(List<Arguments> cases, int n, Side side) {
        for (Call call : Call.values()) {
            for (Sampling sampling : Sampling.values()) {
                cases.add(Arguments.of(n, side, call, sampling));
            }
        }
    }

    @ParameterizedTest(name = "{0} texels {1}, {2}, {3}")
    @MethodSource("repeatCases")
    public void repeatWrapsEverySubRectangleItAccepts(int n, Side side, Call call, Sampling sampling) {
        boolean tall = (side == Side.HIGH);
        int[] texels = new int[n];
        for (int i = 0; i < n; i++) {
            texels[i] = repeatTexel(i);
        }
        int sw = tall ? 2 : LENGTH;
        int sh = tall ? LENGTH : 2;
        int[] pixels = new int[sw * sh];
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, ARGB_PRE, sw, sh));
        pr.setCompositeRule(SRC);
        Transform6 t = tall ? new Transform6(ONE, 0, 0, sampling.scale(), 0, sampling.translation(n))
                : new Transform6(sampling.scale(), 0, 0, ONE, sampling.translation(n), 0);
        int tw = tall ? 1 : n;
        int th = tall ? n : 1;
        if (call == Call.DRAW_IMAGE) {
            pr.drawImage(ARGB_PRE, NORMAL, texels, tw, th, 0, tw, t, REPEAT, sampling.linear, 0, 0, sw << 16,
                    sh << 16, KEEP, KEEP, KEEP, KEEP, 0, 0, tw - 1, th - 1, false);
        } else {
            pr.setTexture(ARGB_PRE, texels, tw, th, tw, t, REPEAT, sampling.linear, false);
            pr.fillRect(0, 0, sw << 16, sh << 16);
        }

        List<String> wrong = new ArrayList<>();
        int wrapped = 0;
        int seams = 0;
        for (int p = 0; p < LENGTH; p++) {
            // the pixel centre in texture space, from the texture-to-device transform; a bilinear sample lies half
            // a texel lower and is measured from the texel centres
            double u = (p + 0.5 - sampling.translation(n) / 65536.0) / (sampling.scale() / 65536.0);
            double sample = sampling.linear ? u - 0.5 : u;
            long i = (long) Math.floor(sample);
            double frac = sample - i;
            // nearest: the centre lies on a texel centre, never on a tie; bilinear: on a texel centre, or halfway
            boolean halfway = sampling.linear && frac == 0.5;
            assertTrue(sampling.linear ? (frac == 0 || halfway) : frac == 0.5, "pixel " + p + " samples u = " + u);
            int texel = (int) Math.floorMod(i, (long) n);
            wrapped += (i < 0 || i >= n) ? 1 : 0;
            double[] expected = channels(texels[texel]);
            if (halfway) {
                int next = (int) Math.floorMod(i + 1, (long) n);
                seams += (next == 0) ? 1 : 0;
                double[] right = channels(texels[next]);
                for (int k = 0; k < 4; k++) {
                    expected[k] = (expected[k] + right[k]) / 2;
                }
            }
            for (int q = 0; q < 2; q++) {
                int actual = tall ? pixels[p * sw + q] : pixels[q * sw + p];
                double[] a = channels(actual);
                for (int k = 0; k < 4; k++) {
                    if (Math.abs(a[k] - expected[k]) > (halfway ? 0.5 : 0)) {
                        wrong.add(f("pixel %d of row %d samples u = %.1f (texel %d%s) but is %08X", p, q, sample,
                                texel, halfway ? " and the next" : f(" = %08X", texels[texel]), actual));
                        break;
                    }
                }
            }
        }
        assertTrue(wrapped > 0, "the case samples outside [0, " + n + ")");
        if (sampling.linear && sampling != Sampling.BILINEAR_ON_TEXEL_CENTRES_ACROSS_THE_LAST_TEXEL) {
            assertTrue(seams > 0, "a pixel samples halfway between the last texel and texel 0");
        }
        assertNone(wrong, n + " texels " + side + ", " + call + ", " + sampling);
    }

    /** An opaque colour of its own for texel i. */
    private static int repeatTexel(int i) {
        return 0xFF000000 | ((i * 0x9E3779B1) >>> 8);
    }

    private static double[] channels(int argb) {
        double[] c = new double[4];
        for (int k = 0; k < 4; k++) {
            c[k] = (argb >>> (24 - 8 * k)) & 0xFF;
        }
        return c;
    }

    /** Fails, naming how many findings there are and the first eight, unless there are none. */
    private static void assertNone(List<String> findings, String what) {
        if (!findings.isEmpty()) {
            fail(what + ": " + findings.size() + " wrong, first " + String.join("; ",
                    findings.subList(0, Math.min(8, findings.size()))));
        }
    }

    private static int fx(double v) {
        return (int) Math.round(v * 65536.0);
    }

    private static String f(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }
}
