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

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.Affine2D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.impl.state.GaussianRenderState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.scenario.effect.DecoraBackend.Image;
import test.com.sun.scenario.effect.DecoraBackend.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.scenario.effect.DecoraCorpus.SHADOW_TINT;
import static test.com.sun.scenario.effect.DecoraCorpus.channelDelta;

/**
 * The Java Gaussian shadow loop {@code JSWLinearConvolveShadowPeer.filterVector} interpolates the alpha of its
 * samples bilinearly, with the sample positions and edge rule of {@code JSWEffectPeer.laccumsample}, the sampler of
 * the base {@code JSWLinearConvolvePeer.filterVector} (JDK-8090445). The peer runs that loop for every pass it cannot
 * run {@code filterHV} for: an input that carries more than a translation, a clip that cuts the result's top or left
 * edge (JDK-8092042), and every directional pass.
 * <p>
 * <b>The tie.</b> Under a rotation by 45 degrees about a point that is integer in both user and device space, every
 * device pixel centre on the diagonal through that point maps to a user point on the row boundary through it. The
 * scene here is the input an {@code InnerShadow} over a 160x160 checker of opaque and transparent 20-pixel cells hands
 * its Gaussian shadow, the checker's {@code InvertMask} padded by the radius 10, rotated about the checker's centre,
 * user (80, 80), a cell corner, which lands on device (113, 113). Pass 0 blurs along user x, so all 21 taps of a pixel
 * on that diagonal sit at user y 80.000, the boundary between two rows of cells, give or take the float rounding of
 * the start point and of the step accumulation, which depends on where the destination starts and so differs between
 * a full render and a clipped one. A loop that reads the texel under each sample, {@code (int) sampy}, follows that
 * rounding to one row or the other, and the two rows are complementary across a cell edge; bilinear sampling weighs
 * them half and half whichever side the rounding falls on. The rotation's cosine and sine are {@code StrictMath}'s, so
 * the sample positions, and the tie, are the same on every platform.
 * <p>
 * <b>Clipped against full ({@link #clippedRenderMatchesTheCroppedFullRender}).</b> A clipped render has to equal the
 * full render cropped to its bounds within {@value #TWO_STEPS} steps per channel: each pass interpolates positions that
 * differ between the two renders by float rounding, which moves a sum by far less than a step but can carry its
 * truncation across an integer, one step per pass. The cuts leave out the result's top 30 rows ({@code top}), keep
 * the 60 rows below those ({@code narrowy}), leave out its left 30 columns ({@code left}), keep the 60 columns right of
 * those ({@code narrowx}), and leave out 30 pixels on every side ({@code inner});
 * {@link #everyTopCutDepthMatchesTheCroppedFullRender} sweeps the top cut over every depth from 1 to
 * {@value #MAX_CUT_DEPTH} rows. {@link #preFixNearestSamplerBreaksTheTopCut} keeps the
 * scene honest: the nearest-texel loop of commit 4f71e99535 ({@link ShadowSamplers.PreFixNearestShadowPeer}) still
 * breaks the top cut.
 * <p>
 * <b>The sampler ({@link #filterVectorInterpolatesTheAlphaBilinearly}).</b> The loop is run directly on a 4x3 source
 * with one tap of weight 1, sampling a grid that reaches past every edge, and has to give exactly the bilinear value of
 * every sample: the sum over the texels of alpha times {@code max(0, 1 - |dx|) * max(0, 1 - |dy|)}, where
 * {@code (dx, dy)} is the distance from the sample to the texel centre {@code (i + 0.5, j + 0.5)} and every texel
 * outside the source is transparent. The positions are quarters, the alphas below 256 and the weight 1, so every
 * product and sum the loop forms is exact in float: the bound is 0. The fractions differ between the axes and the
 * alphas are asymmetric, so swapped fractions show; each row of the source ends in a sentinel texel of alpha 255 in its
 * scanline padding, so a read past the right edge or before the left one shows.
 * <p>
 * <b>The blur's alpha ({@link #shadowAlphaIsTheBlurAlpha}).</b> On the same input, a black shadow and a blur of the
 * same radii have the same weights, take the same loops and sample the same positions with the same edge rule, so the
 * shadow has to be the blur's alpha channel within {@value #TWO_STEPS} steps: the shadow multiplies the interpolated
 * alpha by the tap weight, where {@code laccumsample} scales each texel's fraction by the weight times 255 and divides
 * by 255, and the two roundings can leave a sum on either side of an integer, one step per pass. No alpha in the input
 * exceeds 254, so the blur's packing, which rounds a sum above 254.97 up to 255, truncates every sum as the shadow's
 * does. Measured, 1049 to 1628 pixels of a render differ by one step.
 * <p>
 * At commit 4f71e99535, which sampled the nearest texel, the {@code top} and {@code narrowy} cuts were 54 and 50 steps
 * off and the other three exact; 80 of the 100 top-cut depths were 54 steps off; both sampler grids were off on 19 of
 * their 35 samples; and the shadows differed from the blur's alpha by 19 to 30 steps.
 */
public class LinearConvolveShadowSamplingTest {

    /** The checker's width and height, the size of its cells, and the shadow's radius, which pads the mask. */
    private static final int CHECKER = 160;
    private static final int CELL = 20;
    private static final float RADIUS = 10f;
    private static final int PAD = 10;
    /** The device point the checker's centre, user (80, 80), lands on. */
    private static final int DEVICE_CENTRE = 113;
    private static final Rectangle MASK_BOUNDS = new Rectangle(-PAD, -PAD, CHECKER + 2 * PAD, CHECKER + 2 * PAD);

    /** The bound of a clipped render against the full one: one step per pass (see the class comment). */
    private static final int TWO_STEPS = 2;
    /** How far the cuts reach into the result, and the band width of the narrow cuts. */
    private static final int CUT = 30;
    private static final int BAND = 60;
    /** The deepest top cut of the sweep, in rows. */
    private static final int MAX_CUT_DEPTH = 100;
    /** How far a cut reaches beyond the edges it leaves alone. */
    private static final int MARGIN = 100;

    /** The sampler source: 4x3 texels whose alphas are all different. */
    private static final int[][] GRID_ALPHA = {
        {16, 48, 112, 240},
        {32, 96, 224, 8},
        {64, 192, 128, 255}};
    private static final int GRID_W = 4;
    private static final int GRID_H = 3;
    /** The alpha of the texel in each source row's scanline padding, which a correct loop never reads. */
    private static final int SENTINEL = 255;

    /** A clip that cuts into the full render, as a function of the full render's bounds. */
    record Cut(String name, UnaryOperator<Rectangle> clip) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static final Cut TOP = new Cut("top", f -> new Rectangle(f.x - MARGIN, f.y + CUT, f.width + 2 * MARGIN,
            f.height + 2 * MARGIN));

    /**
     * A grid of samples at {@code (srcx0 + 0.5 + dx, srcy0 + 0.5 + dy)} for {@code 0 <= dx < dstw} and
     * {@code 0 <= dy < dsth}: one tap per destination pixel, stepping one texel per pixel.
     */
    record Grid(String name, float srcx0, float srcy0, int dstw, int dsth) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** A shadow input transform, and whether the render is clipped by {@link #TOP}. */
    record Scene(String name, BaseTransform transform, boolean topCut) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Cut> cuts() {
        return Stream.of(TOP,
                new Cut("narrowy", f -> new Rectangle(f.x - MARGIN, f.y + CUT, f.width + 2 * MARGIN, BAND)),
                new Cut("left", f -> new Rectangle(f.x + CUT, f.y - MARGIN, f.width + 2 * MARGIN,
                        f.height + 2 * MARGIN)),
                new Cut("narrowx", f -> new Rectangle(f.x + CUT, f.y - MARGIN, BAND, f.height + 2 * MARGIN)),
                new Cut("inner", f -> new Rectangle(f.x + CUT, f.y + CUT, f.width - 2 * CUT, f.height - 2 * CUT)));
    }

    static Stream<Grid> grids() {
        // The fringe of an edge is the half texel outside it, where a sample still reaches the edge texel. The first
        // grid samples x at k + 0.25 (0.75 past the texel centre on the left) from -0.75, beyond the left fringe,
        // through 4.25 in the right fringe to 5.25, and y at k + 0.75 (0.25 past it) from -0.25 in the top fringe to
        // 3.75, beyond the bottom fringe. The second samples x at k + 0.75 from -1.25 through -0.25 in the left
        // fringe to 4.75, beyond the right fringe, and y at k + 0.25 from -0.75, beyond the top fringe, to 3.25 in
        // the bottom fringe.
        return Stream.of(new Grid("x at k+0.25, y at k+0.75", -1.25f, -0.75f, GRID_W + 3, GRID_H + 2),
                new Grid("x at k+0.75, y at k+0.25", -1.75f, -1.25f, GRID_W + 3, GRID_H + 2));
    }

    static Stream<Scene> scenes() {
        return Stream.of(new Scene("rotate 45", rotate45(), false),
                new Scene("rotate 45, top cut", rotate45(), true),
                new Scene("rotate atan(3/4)", new Affine2D(0.8, 0.6, -0.6, 0.8, 20, -6), false),
                new Scene("shear (-0.35, -0.25)", new Affine2D(1, -0.25, -0.35, 1, 10, 25), false));
    }

    /**
     * The clipped render equals the full render cropped to its bounds within {@value #TWO_STEPS} steps per channel.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cuts")
    void clippedRenderMatchesTheCroppedFullRender(Cut cut) {
        Result full = shadow(DecoraBackend.java(), null);
        Result clipped = shadow(DecoraBackend.java(), cut.clip().apply(bounds(full)));
        String summary = cut + ": " + compare(clipped, full);
        System.out.println(summary);
        assertTrue(maxDelta(clipped, full) <= TWO_STEPS, () -> "the clipped render differs from the cropped full"
                + " render: " + summary);
    }

    /** Every top cut from 1 to {@value #MAX_CUT_DEPTH} rows deep is within {@value #TWO_STEPS} steps per channel. */
    @Test
    void everyTopCutDepthMatchesTheCroppedFullRender() {
        Result full = shadow(DecoraBackend.java(), null);
        Rectangle f = bounds(full);
        List<String> over = new ArrayList<>();
        int worst = 0;
        int differing = 0;
        for (int depth = 1; depth <= MAX_CUT_DEPTH; depth++) {
            Rectangle clip = new Rectangle(f.x - MARGIN, f.y + depth, f.width + 2 * MARGIN, f.height + 2 * MARGIN);
            int delta = maxDelta(shadow(DecoraBackend.java(), clip), full);
            worst = Math.max(worst, delta);
            differing += delta > 0 ? 1 : 0;
            if (delta > TWO_STEPS) {
                over.add(depth + ": " + delta);
            }
        }
        String summary = String.format(Locale.ROOT, "top cuts 1..%d: max delta %d, %d depths differ, %d above %d",
                MAX_CUT_DEPTH, worst, differing, over.size(), TWO_STEPS);
        System.out.println(summary);
        assertTrue(over.isEmpty(), () -> summary + ": " + over);
    }

    /** The nearest-texel loop of commit 4f71e99535 breaks the top cut by more than {@value #TWO_STEPS} steps. */
    @Test
    void preFixNearestSamplerBreaksTheTopCut() {
        Result full = shadow(ShadowSamplers.preFixNearest(DecoraBackend.java()), null);
        Result clipped = shadow(ShadowSamplers.preFixNearest(DecoraBackend.java()), TOP.clip().apply(bounds(full)));
        String summary = "pre-fix nearest sampler, " + TOP + ": " + compare(clipped, full);
        System.out.println(summary);
        assertTrue(maxDelta(clipped, full) > TWO_STEPS, () -> "the scene no longer reaches the tie: " + summary);
    }

    /**
     * One tap of weight 1 at each sample of the grid gives exactly the bilinear alpha of the source around it, packed
     * with the shadow colour.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("grids")
    void filterVectorInterpolatesTheAlphaBilinearly(Grid grid) {
        int scan = GRID_W + 1;
        int[] src = new int[scan * GRID_H];
        for (int y = 0; y < GRID_H; y++) {
            for (int x = 0; x < GRID_W; x++) {
                src[y * scan + x] = GRID_ALPHA[y][x] << 24;
            }
            src[y * scan + GRID_W] = SENTINEL << 24;
        }
        int[] dst = new int[grid.dstw() * grid.dsth()];
        DecoraBackend.java().filterVectorShadow(dst, grid.dstw(), grid.dsth(), grid.dstw(), src, GRID_W, GRID_H,
                scan, new float[] {1f}, 1, grid.srcx0(), grid.srcy0(), 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, SHADOW_TINT);

        float[] color = SHADOW_TINT.getPremultipliedRGBComponents();
        List<String> wrong = new ArrayList<>();
        for (int dy = 0; dy < grid.dsth(); dy++) {
            for (int dx = 0; dx < grid.dstw(); dx++) {
                double px = grid.srcx0() + 0.5 + dx;
                double py = grid.srcy0() + 0.5 + dy;
                double alpha = bilinearAlpha(px, py);
                assertEquals(Math.rint(alpha * 16), alpha * 16, () -> "the bilinear alpha at (" + px + ", " + py
                        + ") is not a multiple of 1/16, so the loop's float arithmetic would not be exact");
                float sum = (float) alpha;
                int expected = ((int) (color[0] * sum) << 16) | ((int) (color[1] * sum) << 8)
                        | ((int) (color[2] * sum)) | ((int) (color[3] * sum) << 24);
                int actual = dst[dy * grid.dstw() + dx];
                if (actual != expected) {
                    wrong.add(String.format(Locale.ROOT, "(%.2f, %.2f): %08x, expected %08x (alpha %.4f)", px, py,
                            actual, expected, alpha));
                }
            }
        }
        System.out.println(grid + ": " + wrong.size() + " of " + dst.length + " samples off the bilinear value");
        assertTrue(wrong.isEmpty(), () -> grid + ": " + wrong.size() + " of " + dst.length
                + " samples are not the bilinear value: " + wrong);
    }

    /** The sum over the source texels of alpha times the tent weights of the sample's distance to the texel centre. */
    private static double bilinearAlpha(double px, double py) {
        double alpha = 0;
        for (int j = 0; j < GRID_H; j++) {
            for (int i = 0; i < GRID_W; i++) {
                alpha += GRID_ALPHA[j][i] * tent(px - (i + 0.5)) * tent(py - (j + 0.5));
            }
        }
        return alpha;
    }

    private static double tent(double d) {
        return Math.max(0, 1 - Math.abs(d));
    }

    /**
     * A black shadow is the blur's alpha channel within {@value #TWO_STEPS} steps, on the same input with no alpha
     * above 254 and the same transform, radii and clip.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenes")
    void shadowAlphaIsTheBlurAlpha(Scene scene) {
        BaseTransform tx = scene.transform();
        Rectangle clip = null;
        if (scene.topCut()) {
            clip = TOP.clip().apply(bounds(render(DecoraBackend.java(), tx, checkerMask(254), shadowState(tx), null)));
        }
        Result shadow = render(DecoraBackend.java(), tx, checkerMask(254), shadowState(tx), clip);
        Result blur = render(DecoraBackend.java(), tx, checkerMask(254),
                new GaussianRenderState(RADIUS, RADIUS, 0f, false, null, tx), clip);
        assertEquals(bounds(blur), bounds(shadow), () -> scene + ": result bounds");
        int maxDelta = 0;
        int differing = 0;
        int worst = 0;
        for (int i = 0; i < shadow.pixels().length; i++) {
            int delta = channelDelta(shadow.pixels()[i], blur.pixels()[i] & 0xFF000000);
            differing += delta > 0 ? 1 : 0;
            if (delta > maxDelta) {
                maxDelta = delta;
                worst = i;
            }
        }
        String summary = String.format(Locale.ROOT, "%s: result %s, %d of %d pixels differ from the blur's alpha, by"
                + " up to %d", scene, format(bounds(shadow)), differing, shadow.pixels().length, maxDelta);
        System.out.println(summary);
        int at = worst;
        assertTrue(maxDelta <= TWO_STEPS, () -> summary + "; worst at (" + (shadow.x() + at % shadow.width()) + ","
                + (shadow.y() + at / shadow.width()) + "): " + String.format(Locale.ROOT, "%08x against %08x",
                shadow.pixels()[at], blur.pixels()[at]));
    }

    /**
     * Rotation by 45 degrees about the checker's centre, user (80, 80), onto device (113, 113), with the cosine and
     * sine of {@code StrictMath}.
     */
    private static BaseTransform rotate45() {
        double c = StrictMath.cos(StrictMath.toRadians(45));
        double s = StrictMath.sin(StrictMath.toRadians(45));
        Affine2D tx = new Affine2D();
        tx.translate(DEVICE_CENTRE, DEVICE_CENTRE);
        tx.concatenate(new Affine2D(c, s, -s, c, 0, 0));
        tx.translate(-CHECKER / 2.0, -CHECKER / 2.0);
        return tx;
    }

    /**
     * The input an {@code InnerShadow} hands its Gaussian shadow for the checker: the {@code InvertMask} of a checker
     * whose cells alternate opaque and transparent from an opaque one at the user origin, padded by the radius. The
     * mask has alpha {@code alpha} outside the checker and on its transparent cells and 0 on its opaque cells.
     */
    private static Image checkerMask(int alpha) {
        int w = MASK_BOUNDS.width;
        int h = MASK_BOUNDS.height;
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int ux = x + MASK_BOUNDS.x;
                int uy = y + MASK_BOUNDS.y;
                boolean inside = ux >= 0 && uy >= 0 && ux < CHECKER && uy < CHECKER;
                boolean opaqueCell = inside && (ux / CELL + uy / CELL) % 2 == 0;
                pixels[y * w + x] = opaqueCell ? 0 : alpha << 24;
            }
        }
        return Image.of(w, h, pixels);
    }

    /** The black Gaussian shadow of radius {@value #RADIUS} of the checker mask under {@link #rotate45()}. */
    private static Result shadow(DecoraBackend backend, Rectangle clip) {
        BaseTransform tx = rotate45();
        return render(backend, tx, checkerMask(255), shadowState(tx), clip);
    }

    /** The black Gaussian shadow state of radius {@value #RADIUS} under the filter transform {@code tx}. */
    private static GaussianRenderState shadowState(BaseTransform tx) {
        return new GaussianRenderState(RADIUS, RADIUS, 0f, true, Color4f.BLACK, tx);
    }

    /** {@code state} over the checker mask {@code mask}, whose {@code ImageData} carries {@code tx}. */
    private static Result render(DecoraBackend backend, BaseTransform tx, Image mask, GaussianRenderState state,
                                 Rectangle clip) {
        return backend.convolve(backend.data(mask, MASK_BOUNDS, tx), state, clip);
    }

    private static Rectangle bounds(Result r) {
        return new Rectangle(r.x(), r.y(), r.width(), r.height());
    }

    /** The largest channel delta between {@code clipped} and {@code full} cropped to its bounds. */
    private static int maxDelta(Result clipped, Result full) {
        return DecoraCorpus.maxDelta(clipped, DecoraCorpus.crop(full, clipped));
    }

    /** The clipped bounds, the largest delta from the cropped full render, where, and how many pixels differ by 2+. */
    private static String compare(Result clipped, Result full) {
        Result cropped = DecoraCorpus.crop(full, clipped);
        int max = 0;
        int worst = 0;
        int overOne = 0;
        for (int i = 0; i < clipped.pixels().length; i++) {
            int delta = channelDelta(clipped.pixels()[i], cropped.pixels()[i]);
            overOne += delta > 1 ? 1 : 0;
            if (delta > max) {
                max = delta;
                worst = i;
            }
        }
        return String.format(Locale.ROOT, "clipped %s of full %s, max delta %d at (%d,%d), %d of %d pixels differ by"
                + " more than 1", format(bounds(clipped)), format(bounds(full)), max,
                clipped.x() + worst % clipped.width(), clipped.y() + worst / clipped.width(), overOne,
                clipped.pixels().length);
    }

    private static String format(Rectangle r) {
        return String.format(Locale.ROOT, "(%d,%d %dx%d)", r.x, r.y, r.width, r.height);
    }
}
