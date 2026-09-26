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
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.state.BoxRenderState;
import com.sun.scenario.effect.impl.sw.java.JSWBoxBlurPeer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.scenario.effect.DecoraBackend.Image;
import test.com.sun.scenario.effect.DecoraBackend.Result;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.scenario.effect.DecoraCorpus.PRIMARY_SEED;
import static test.com.sun.scenario.effect.DecoraCorpus.channelDelta;
import static test.com.sun.scenario.effect.DecoraCorpus.pattern;

/**
 * A spread-0 box blur of an input whose {@code ImageData} carries a transform, as the input of a {@code BoxBlur} over
 * an {@code ImageInput} on a translated or scaled node does, has to land where that transform puts it.
 * <p>
 * {@code BoxRenderState.getPassPeer} hands a spread-0 blur pass to the software {@code BoxBlur} peer whenever
 * {@code BoxRenderState.validatePassInput} finds the pass input sw-compatible: an identity or translate-only transform,
 * or a positive axis-aligned scale, whose sample vector it renormalises to a unit step along the pass's axis unless it
 * has to clamp the box size. The peer filters the untransformed pixels, so the transform has to travel on with the
 * result: production draws a result's pixels at its bounds through its transform. {@code JSWBoxBlurPeer.filter}
 * returned its result without the input's transform, as the deleted native {@code SSEBoxBlurPeer} did, and the
 * software pipeline drew the blur away from where the transform puts it. {@code JSWBoxShadowPeer} carries the input
 * transform on since commit 26ce75d02f, which gave it the transform {@code SSEBoxShadowPeer} kept since JDK-8093087,
 * and the GPU path, the {@code LinearConvolve} peers, applies it while sampling and returns bounds in the transformed
 * space.
 * <p>
 * Every render but the early-return control's runs both passes through {@link DecoraBackend#box} on a fresh Java
 * backend, as {@code LinearConvolveCoreEffect.filterImageDatas} does, and every render asserts that only
 * {@code JSWBoxBlurPeer} ran and that the result carries the input's transform.
 * <p>
 * A translated result's pixels, placed in device space at its bounds moved by its transform, have to match a reference
 * that uses no Decora peer: the untranslated source placed at its device position (bounds origin plus translation) and
 * blurred by a direct per-pixel mean over a centered box of the kernel's size, repeated {@code passes} times along each
 * axis whose box is wider than one pixel, on a canvas grown by the peer's even-growth rule
 * {@code ((size - 1) * passes + 1) & ~1}. The sizes are odd, which {@code BoxRenderState.getBoxPixelSize} keeps as they
 * are, so that growth is exactly the support of the repeated box. The reference computes in double precision and
 * rounds to whole steps once, at the end. That rounding cannot tip over: the exact means are fractions whose
 * denominator is the product of the odd box sizes (at most {@code 9^6}), so none lies halfway between two steps or
 * closer to such a point than {@code 1 / (2 * 9^6)}, far above the error of the double sums, and the tolerance below
 * keeps a margin of half a step besides. Outside its rectangle each image is transparent, and the comparison covers the
 * union of both rectangles.
 * <p>
 * The tolerance is fixed-point rounding. A pass of the peer over a box of {@code n} pixels writes
 * {@code (sum * kscale) >> 23} with {@code kscale = 0x7fffffff / (n * 255)} for the mean {@code m = sum / n} of the
 * integer channels under the box. That is below {@code m * 256 / 255 <= m + 1} and above
 * {@code m - 1 - sum / 2^23}, where {@code sum / 2^23 < 0.001} for these boxes, so a pass lands within 1.001 steps
 * of the exact mean of its own inputs, and a box mean moves no value further than its inputs are apart. After
 * {@code P} one-dimensional passes the peer is therefore within {@code 1.001 * P} steps of the exact means, and
 * within {@code P} steps of the reference rounded to whole steps, which is what is compared: the tolerance is
 * {@code P}, 6 for {@code h=9 v=9 passes=3} and 2 for the other kernels. The largest deltas measured, the same for
 * every placement, translated or not, are 3 for {@code h=9 v=9 passes=3}, 1 for {@code h=3 v=5 passes=1}, and 2, the
 * bound, for the one-axis kernels with two passes: the peer truncates, so its errors lean one way. Before the fix the
 * translated cases failed on the transform and, in device space, by 69 to 106 for {@code h=9 v=9 passes=3} and by
 * 255 for the other kernels: the blur drawn away from where the translation puts it.
 * <p>
 * A scaled result is judged without depending on how far the blur spreads: its untransformed bounds have to be
 * centred on the input's, which the peer's even growth makes exact, and the alpha-weighted centroid of its pixels,
 * mapped to device space through its transform, has to lie within {@link #CENTROID_TOLERANCE} device pixels of the
 * input's, mapped through the input's transform. A centred box of odd size whose weights sum to one keeps the alpha
 * mass and its first moment, so an exact blur keeps the centroid, and only the peer's truncation moves it. The
 * tolerance of 0.05 device pixels is measured, not derived: the largest shift measured is 0.0082 device pixels, for
 * {@code h=9 v=9 passes=3} (0.0015 for {@code h=3 v=5 passes=1}), and 0.05 is about six times that. A measured bound
 * is safe here because the peer computes in integers and the centroid is a double sum in a fixed order, so the shift
 * is the same on every platform. It is also sensitive enough: the smallest shift any tested mutation of the peer
 * produced was 0.17 device pixels, and a one-pixel shift of the result moves the centroid by at least 0.5 device
 * pixels at the smallest scale here. Before the fix the scaled cases failed on the transform and by 9.2 to 44.8 device
 * pixels on the centroid. The blur extent of a scaled input is deliberately not asserted, because
 * {@code BoxRenderState.validatePassInput} scales the pass size by the input's sample scale twice where
 * {@code GaussianRenderState.validatePassInput} scales it once, and a correction of that must not have to change this
 * test.
 * <p>
 * The untranslated controls passed before the fix and show that the reference agrees with the peer wherever the
 * transform plays no part. The early-return control covers the one path of the peer that kept the translation before
 * the fix: a pass whose box is one pixel wide, or with no blur passes, returns its input unchanged.
 */
public class BoxBlurInputTransformTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    /**
     * The kernels: the one of the {@code translated/BoxBlur} golden rows, a small asymmetric one, and one per axis,
     * where one pass is a no-op that {@code BoxRenderState.getPassPeer} runs no peer for: after a horizontal-only pass
     * 0 its result is the effect's result, and a vertical-only pass 1 filters the translated source itself.
     */
    private static final List<Kernel> KERNELS = List.of(new Kernel(9, 9, 3), new Kernel(3, 5, 1),
            new Kernel(7, 1, 2), new Kernel(1, 5, 2));

    /** Translations with both signs on each axis, at the origin and at the corpus's non-zero origin. */
    private static final List<Placement> TRANSLATED = List.of(new Placement(0, 0, 5, 7), new Placement(0, 0, -3, 11),
            new Placement(0, 0, -6, -4), new Placement(-7, 3, 5, 7));

    /** Untransformed inputs, which the peer placed correctly before the fix. */
    private static final List<Placement> UNTRANSLATED = List.of(new Placement(0, 0, 0, 0), new Placement(-7, 3, 0, 0));

    /** The kernels of the scaled cases: the one of the {@code translated/BoxBlur} golden rows and a smaller one. */
    private static final List<Kernel> SCALED_KERNELS = List.of(new Kernel(9, 9, 3), new Kernel(3, 5, 1));

    /**
     * Axis-aligned scales, which {@code BoxRenderState.validatePassInput} finds sw-compatible as well: magnifying,
     * magnifying with a translation, at the corpus's non-zero origin, anisotropic, and minifying.
     */
    private static final List<Scaled> SCALED = List.of(new Scaled(0, 0, 2, 2, 0, 0), new Scaled(0, 0, 1.5, 1.5, 22, 12),
            new Scaled(-7, 3, 1.25, 1.25, 0, 0), new Scaled(0, 0, 2, 1.5, 0, 0), new Scaled(0, 0, 0.5, 0.5, 0, 0));

    /**
     * How far, in device pixels, the alpha-weighted centroid of a scaled result may lie from the input's; see the
     * class description.
     */
    static final double CENTROID_TOLERANCE = 0.05;

    /** A box kernel as {@code BoxBlur} gives it to {@code BoxRenderState}: odd sizes in pixels, spread 0. */
    record Kernel(int hsize, int vsize, int passes) {

        Kernel {
            if ((hsize & 1) == 0 || (vsize & 1) == 0 || passes < 1) {
                throw new IllegalArgumentException("odd box sizes and at least one pass: " + hsize + ", " + vsize
                        + ", " + passes);
            }
        }

        /** The one-dimensional passes the peer runs: {@code passes} along each axis whose box is wider than 1. */
        int passesRun() {
            return (hsize > 1 ? passes : 0) + (vsize > 1 ? passes : 0);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "h=%d v=%d passes=%d", hsize, vsize, passes);
        }
    }

    /** The filter-space origin of the source's bounds and the translation its {@code ImageData} carries. */
    record Placement(int x, int y, int tx, int ty) {

        ImageData input(DecoraBackend backend, Image image) {
            return backend.data(image, x, y).transform(BaseTransform.getTranslateInstance(tx, ty));
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "origin=%d,%d translate=%d,%d", x, y, tx, ty);
        }
    }

    /** The filter-space origin of the source's bounds and the scale and translation its {@code ImageData} carries. */
    record Scaled(int x, int y, double sx, double sy, double tx, double ty) {

        ImageData input(DecoraBackend backend, Image image) {
            return backend.data(image, x, y).transform(BaseTransform.getInstance(sx, 0, 0, sy, tx, ty));
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "origin=%d,%d scale=%s,%s translate=%s,%s", x, y, sx, sy, tx, ty);
        }
    }

    static Stream<Arguments> translatedCases() {
        return cases(TRANSLATED);
    }

    static Stream<Arguments> scaledCases() {
        List<Arguments> args = new ArrayList<>();
        for (Kernel kernel : SCALED_KERNELS) {
            for (Scaled scaled : SCALED) {
                args.add(Arguments.of(kernel, scaled));
            }
        }
        return args.stream();
    }

    static Stream<Arguments> untranslatedCases() {
        return cases(UNTRANSLATED);
    }

    private static Stream<Arguments> cases(List<Placement> placements) {
        List<Arguments> args = new ArrayList<>();
        for (Kernel kernel : KERNELS) {
            for (Placement placement : placements) {
                args.add(Arguments.of(kernel, placement));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("translatedCases")
    void translatedInputKeepsItsTranslation(Kernel kernel, Placement placement) {
        checkPlacement(kernel, placement);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("untranslatedCases")
    void untranslatedInputIsPlacedAtItsBounds(Kernel kernel, Placement placement) {
        checkPlacement(kernel, placement);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scaledCases")
    void scaledInputKeepsItsTransform(Kernel kernel, Scaled scaled) {
        int[] source = pattern(WIDTH, HEIGHT, PRIMARY_SEED);
        DecoraBackend backend = DecoraBackend.java();
        ImageData input = scaled.input(backend, Image.of(WIDTH, HEIGHT, source));
        Result result = backend.box(input, kernel.hsize(), kernel.vsize(), kernel.passes(), 0f, false, null, null);
        assertEquals(List.of(JSWBoxBlurPeer.class.getName()), List.copyOf(backend.ranPeers()), "peers that ran");

        Rectangle bounds = input.getUntransformedBounds();
        double[] expected = deviceCentroid(source, bounds.x, bounds.y, bounds.width, bounds.height,
                input.getTransform());
        double[] actual = deviceCentroid(result.pixels(), result.x(), result.y(), result.width(), result.height(),
                result.transform());
        double shift = Math.hypot(actual[0] - expected[0], actual[1] - expected[1]);
        System.out.printf(Locale.ROOT, "%s %s: result tx %s, bounds %d,%d %dx%d, device centroid shift %.6f"
                + " (tolerance %s)%n", kernel, scaled, DecoraGoldens.tx(result.transform()), result.x(), result.y(),
                result.width(), result.height(), shift, CENTROID_TOLERANCE);
        assertAll(
                () -> assertEquals(DecoraCorpus.matrix(input.getTransform()), DecoraCorpus.matrix(result.transform()),
                        "result transform"),
                () -> assertEquals(2 * bounds.x + bounds.width, 2 * result.x() + result.width(),
                        "twice the horizontal centre of the untransformed bounds"),
                () -> assertEquals(2 * bounds.y + bounds.height, 2 * result.y() + result.height(),
                        "twice the vertical centre of the untransformed bounds"),
                () -> assertTrue(shift <= CENTROID_TOLERANCE, () -> String.format(Locale.ROOT, "device centroid of"
                        + " the result (%.4f,%.4f) is %.4f device pixels from the input's (%.4f,%.4f), tolerance %s",
                        actual[0], actual[1], shift, expected[0], expected[1], CENTROID_TOLERANCE)));
    }

    /**
     * A pass the peer returns early from, a box one pixel wide on both axes or no blur passes, returns its input
     * itself, translation included. {@code BoxRenderState.getPassPeer} hands the peer no such pass, so
     * {@link DecoraBackend#boxBlurPass} runs it directly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("earlyReturns")
    void earlyReturnKeepsItsInput(String name, float hsize, float vsize, int passes, int pass) {
        Placement placement = new Placement(-7, 3, 5, 7);
        DecoraBackend backend = DecoraBackend.java();
        Image image = Image.of(WIDTH, HEIGHT, pattern(WIDTH, HEIGHT, PRIMARY_SEED));
        ImageData input = placement.input(backend, image);
        BoxRenderState state = new BoxRenderState(hsize, vsize, passes, 0f, false, null,
                BaseTransform.IDENTITY_TRANSFORM);
        Result result = backend.boxBlurPass(input, state, pass);
        assertEquals(List.of(JSWBoxBlurPeer.class.getName()), List.copyOf(backend.ranPeers()), "peers that ran");
        assertTrue(state.isPassNop(), "BoxRenderState.getPassPeer would have run a peer for " + name);
        assertSame(image, result.image(), "the early return hands back its input");
        Rectangle bounds = input.getUntransformedBounds();
        assertEquals(bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height,
                result.x() + "," + result.y() + "," + result.width() + "," + result.height(), "result bounds");
        assertEquals(DecoraCorpus.matrix(input.getTransform()), DecoraCorpus.matrix(result.transform()),
                "result transform");
    }

    static Stream<Arguments> earlyReturns() {
        return Stream.of(
                Arguments.of("h=1 v=1 passes=3 pass 0", 1f, 1f, 3, 0),
                Arguments.of("h=1 v=1 passes=3 pass 1", 1f, 1f, 3, 1),
                Arguments.of("h=9 v=9 passes=0 pass 0", 9f, 9f, 0, 0));
    }

    private static void checkPlacement(Kernel kernel, Placement placement) {
        int[] source = pattern(WIDTH, HEIGHT, PRIMARY_SEED);
        DecoraBackend backend = DecoraBackend.java();
        ImageData input = placement.input(backend, Image.of(WIDTH, HEIGHT, source));
        Result result = backend.box(input, kernel.hsize(), kernel.vsize(), kernel.passes(), 0f, false, null, null);
        assertEquals(List.of(JSWBoxBlurPeer.class.getName()), List.copyOf(backend.ranPeers()), "peers that ran");

        Frame reference = reference(source, WIDTH, HEIGHT, placement.x() + placement.tx(),
                placement.y() + placement.ty(), kernel);
        Frame peer = inDeviceSpace(result);
        int tolerance = kernel.passesRun();
        Comparison comparison = compare(peer, reference, tolerance);
        System.out.printf(Locale.ROOT, "%s %s: result tx %s, device max delta %d (tolerance %d)%n", kernel,
                placement, DecoraGoldens.tx(result.transform()), comparison.maxDelta(), tolerance);
        assertAll(
                () -> assertEquals(DecoraCorpus.matrix(input.getTransform()), DecoraCorpus.matrix(result.transform()),
                        "result transform"),
                () -> assertTrue(comparison.maxDelta() <= tolerance, () -> "blurred pixels misplaced in device space:"
                        + " max channel delta " + comparison.maxDelta() + " > tolerance " + tolerance + " at "
                        + comparison.where() + "; " + comparison.over() + " pixels over; peer " + peer.describe()
                        + ", reference " + reference.describe()));
    }

    /** Pixels in device space: {@code width x height} ARGB-pre ints at {@code (x, y)}, transparent outside. */
    record Frame(int x, int y, int width, int height, int[] pixels) {

        int at(int deviceX, int deviceY) {
            int col = deviceX - x;
            int row = deviceY - y;
            return col < 0 || row < 0 || col >= width || row >= height ? 0 : pixels[row * width + col];
        }

        String describe() {
            return x + "," + y + " " + width + "x" + height;
        }
    }

    /** The largest channel delta, where it is, and how many pixels exceed the tolerance of the case. */
    record Comparison(int maxDelta, String where, long over) {
    }

    /** The peer's result in device space: its bounds moved by its transform, which has to be a whole translation. */
    private static Frame inDeviceSpace(Result result) {
        BaseTransform tx = result.transform();
        double mxt = tx.getMxt();
        double myt = tx.getMyt();
        assertTrue(tx.isTranslateOrIdentity() && mxt == Math.rint(mxt) && myt == Math.rint(myt),
                () -> "result transform is not a whole translation: " + DecoraCorpus.matrix(tx));
        return new Frame(result.x() + (int) mxt, result.y() + (int) myt, result.width(), result.height(),
                result.pixels());
    }

    private static Comparison compare(Frame peer, Frame reference, int tolerance) {
        int x0 = Math.min(peer.x(), reference.x());
        int y0 = Math.min(peer.y(), reference.y());
        int x1 = Math.max(peer.x() + peer.width(), reference.x() + reference.width());
        int y1 = Math.max(peer.y() + peer.height(), reference.y() + reference.height());
        int maxDelta = 0;
        String where = "-";
        long over = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = peer.at(x, y);
                int r = reference.at(x, y);
                int delta = channelDelta(p, r);
                if (delta > tolerance) {
                    over++;
                }
                if (delta > maxDelta) {
                    maxDelta = delta;
                    where = String.format(Locale.ROOT, "(%d,%d) peer=%08x reference=%08x", x, y, p, r);
                }
            }
        }
        return new Comparison(maxDelta, where, over);
    }

    /**
     * The reference blur, with no Decora peer: {@code argb} ({@code width x height}, ARGB-pre) placed with its first
     * pixel at device {@code (deviceX, deviceY)} on a canvas grown by the peer's even-growth rule, then per channel a
     * direct mean over a centered box of {@code hsize} pixels along the rows, {@code passes} times, and the same with
     * {@code vsize} along the columns, in double precision, rounded to whole steps once, at the end.
     */
    static Frame reference(int[] argb, int width, int height, int deviceX, int deviceY, Kernel kernel) {
        int growX = growth(kernel.hsize(), kernel.passes());
        int growY = growth(kernel.vsize(), kernel.passes());
        int canvasWidth = width + growX;
        int canvasHeight = height + growY;
        double[][] planes = new double[4][canvasWidth * canvasHeight];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                int i = (y + growY / 2) * canvasWidth + x + growX / 2;
                for (int c = 0; c < 4; c++) {
                    planes[c][i] = channel(pixel, c);
                }
            }
        }
        for (int c = 0; c < 4; c++) {
            for (int pass = 0; pass < kernel.passes(); pass++) {
                planes[c] = boxMean(planes[c], canvasWidth, canvasHeight, kernel.hsize(), true);
            }
            for (int pass = 0; pass < kernel.passes(); pass++) {
                planes[c] = boxMean(planes[c], canvasWidth, canvasHeight, kernel.vsize(), false);
            }
        }
        int[] pixels = new int[canvasWidth * canvasHeight];
        for (int i = 0; i < pixels.length; i++) {
            int value = 0;
            for (int c = 0; c < 4; c++) {
                value = (value << 8) | (int) Math.round(planes[c][i]);
            }
            pixels[i] = value;
        }
        return new Frame(deviceX - growX / 2, deviceY - growY / 2, canvasWidth, canvasHeight, pixels);
    }

    /**
     * The alpha-weighted centroid of {@code width x height} compact ARGB-pre pixels whose bounds start at
     * {@code (x, y)}, each pixel weighted at its centre, mapped to device space through {@code tx}.
     */
    static double[] deviceCentroid(int[] argb, int x, int y, int width, int height, BaseTransform tx) {
        double mass = 0;
        double sumX = 0;
        double sumY = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int alpha = argb[row * width + col] >>> 24;
                mass += alpha;
                sumX += alpha * (x + col + 0.5);
                sumY += alpha * (y + row + 0.5);
            }
        }
        double cx = sumX / mass;
        double cy = sumY / mass;
        return new double[] {tx.getMxx() * cx + tx.getMxy() * cy + tx.getMxt(),
                tx.getMyx() * cx + tx.getMyy() * cy + tx.getMyt()};
    }

    /** How far the peer grows its image along an axis: {@code JSWBoxBlurPeer}'s rule, even for any size. */
    static int growth(int size, int passes) {
        return ((size - 1) * passes + 1) & ~1;
    }

    /** The mean over a centered box of {@code size} (odd) pixels along the rows or the columns, zero outside. */
    private static double[] boxMean(double[] plane, int width, int height, int size, boolean alongRows) {
        int radius = size / 2;
        double[] out = new double[plane.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0;
                for (int d = -radius; d <= radius; d++) {
                    int sx = alongRows ? x + d : x;
                    int sy = alongRows ? y : y + d;
                    if (sx >= 0 && sy >= 0 && sx < width && sy < height) {
                        sum += plane[sy * width + sx];
                    }
                }
                out[y * width + x] = sum / size;
            }
        }
        return out;
    }

    /** Channel {@code c} of an ARGB int: 0 alpha, 1 red, 2 green, 3 blue. */
    private static int channel(int argb, int c) {
        return (argb >>> (24 - 8 * c)) & 0xFF;
    }
}
