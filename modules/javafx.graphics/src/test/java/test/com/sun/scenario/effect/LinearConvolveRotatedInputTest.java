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
import com.sun.scenario.effect.Effect.AccelType;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.state.GaussianRenderState;
import com.sun.scenario.effect.impl.sw.RendererDelegate;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolvePeer;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolveShadowPeer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.scenario.effect.DecoraBackend.Image;
import test.com.sun.scenario.effect.DecoraBackend.Result;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static test.com.sun.scenario.effect.DecoraCorpus.ONE_STEP;
import static test.com.sun.scenario.effect.DecoraCorpus.SHADOW_TINT;
import static test.com.sun.scenario.effect.DecoraCorpus.channelDelta;

/**
 * A Gaussian pass over an input whose {@code ImageData} carries a rotation or a shear has to step through the source
 * along the inverse-transformed destination axes.
 * <p>
 * For such an input {@code JSWLinearConvolvePeer.filter} takes the 8-coordinate branch of
 * {@code EffectPeer.getTextureCoordinates}, whose corner table maps the destination corners {@code (dx1, dy1)},
 * {@code (dx2, dy1)} and {@code (dx1, dy2)} to {@code ret[0..1]}, {@code ret[4..5]} and {@code ret[6..7]}, as source
 * positions divided by the source image size. {@code ret[4..5] - ret[0..1]} therefore spans the destination width and
 * {@code ret[6..7] - ret[0..1]} its height, and the source step per destination column {@code (dxcol, dycol)} and per
 * destination row {@code (dxrow, dyrow)} that {@code filterVector} walks are those differences times the source size
 * divided by the destination width and height respectively. For an input transform {@code T} they are the inverse
 * delta transforms {@code T^-1 (1, 0)} and {@code T^-1 (0, 1)} in source texels: the images here are 1:1 with their
 * bounds, and the peer divides by and multiplies with the same physical size, so a user-space unit is a texel. At
 * commit 25c9ec02a7 the peer divided {@code dycol} by the destination height and {@code dxrow} by its width, which
 * scales both by the destination's aspect ratio whenever the transform has a non-zero {@code mxy} or {@code myx} and
 * the destination is not square.
 * <p>
 * The inputs are what an input that renders in user space returns for a node under the filter transform {@code T}
 * ({@code InvertMask}, {@code Reflection}, {@code DisplacementMap}): the untransformed source, 48x16 at {@code (5, 7)},
 * with {@code T} in its {@code ImageData}. The states are the ones the Gaussian effects build under {@code T}: a blur
 * of radii 5 and 3 ({@code GaussianBlur}-like, the {@code LinearConvolve} peer) and a tinted shadow of the same radii
 * ({@code InnerShadow}/{@code DropShadow} with {@code BlurType.GAUSSIAN}, the {@code LinearConvolveShadow} peer). Pass
 * 0 filters the transformed input; its result, and so the input of pass 1, is in device space with no transform, and
 * pass 1 takes the 4-coordinate branch, whose steps are {@code (1, 0)} and {@code (0, 1)}.
 * <p>
 * <b>Step oracle.</b> Recording subclasses of both peers capture the arguments each pass hands to
 * {@code filterVector}. For pass 0 the steps must equal {@code T^-1 (1, 0)} and {@code T^-1 (0, 1)}, computed here from
 * {@code T}'s matrix, and the start point {@code (srcx0, srcy0)} must equal {@code T^-1} of the destination's upper
 * left corner, less the source origin. The start point does not involve the swapped denominators; it guards the
 * oracle. The transforms are the quarter turns, the rotation with cosine 0.8 and sine 0.6, and a shear, all with a
 * non-zero {@code mxy} and {@code myx}, and each pass-0 destination is checked to be at least 10 percent away from
 * square, which moves either swapped step by at least 9 percent of its value. The tolerance is
 * {@value #STEP_TOLERANCE} texels. The peer computes the steps in float: each corner position, below 128 texels, picks
 * up at most three roundings of 4e-6, so the difference of two is off by less than 2.4e-5, and divided by a
 * destination dimension of at least 16 by less than 1.5e-6, plus two roundings of a step no longer than 1.1. Measured,
 * the steps are off by at most 1.3e-7 and the start points by at most 1.3e-6.
 * <p>
 * <b>Pixel oracle, quarter turns.</b> A quarter turn with an integer translation maps pixel centres to pixel centres,
 * so pre-rotating the source is a permutation of its pixels. The kernel rendered over the rotated input must then
 * match the same state rendered over the pre-rotated source with no transform, which is what an input that honours
 * the state's input transform returns ({@code NodeEffectInput}). Both renders run {@code filterVector} in both passes:
 * the pre-rotated input's pass vectors, {@code T (1, 0)} and {@code T (0, 1)}, are not the centred {@code (1, 0)} and
 * {@code (0, 1)}, so the peer takes its vector loop with the 4-coordinate branch. With the right steps, every sample
 * of both renders lies on the same pixel centre. The steps and start points are computed in float from images of
 * different sizes and origins, so the positions can differ by float rounding: the shadow takes the pixel under the
 * sample, and a sample on a pixel centre lies half a pixel from the next pixel, a distance that rounding cannot cross;
 * the blur samples bilinearly and mixes that rounding's fraction of a neighbour into a sample, which moves a sum by far
 * less than a step but can carry its truncation across an integer.
 * The bound is therefore one step per channel. Measured, the renders are bit-identical in every case but the blur
 * under the 270-degree turn, where 44 of the 1276 pixels differ by one step. The source is a hard-edged, asymmetric
 * glyph with structure along both axes, in two opaque colours and a translucent one, so that a transposed or smeared
 * step cannot hide.
 * <p>
 * At commit 25c9ec02a7 every case failed. Every step case failed on {@code dycol} and {@code dxrow} only, each off by
 * the destination's aspect ratio, with its start point and its other two steps right: under the quarter turns, into
 * 16x58, {@code |dycol|} was 0.276 and {@code |dxrow|} 3.625 instead of 1; under the rotation by atan(3/4), into
 * 59x51, they were -0.694 and 0.519 instead of -0.6 and 0.6; under the shear, into 65x33, 0.540 and 0.195 instead of
 * 0.274 and 0.384. The quarter-turn renders differed from the pre-rotated ones by 198 to 255 steps, on 706 to 798 of
 * their 1276 pixels.
 */
public class LinearConvolveRotatedInputTest {

    /** The source's untransformed bounds: off the origin, three times as wide as high. */
    private static final Rectangle SOURCE_BOUNDS = new Rectangle(5, 7, 48, 16);

    /** The tolerance of the step oracle, in source texels; see the class comment. */
    private static final double STEP_TOLERANCE = 1e-5;
    /** The tolerance of the start point, in source texels: coordinates below 128 rounded twice in float. */
    private static final double START_TOLERANCE = 1e-4;
    /** How far from square a pass-0 destination has to be, as a ratio of its sides. */
    private static final double MIN_ASPECT = 1.1;

    /** {@code (x, y)} to {@code (40 - y, 3 + x)}: the source lands at {@code (17, 8)}, 16x48. */
    private static final Transform ROTATE_90 = new Transform("rotate 90", 0, 1, -1, 0, 40, 3);
    /** {@code (x, y)} to {@code (4 + y, 70 - x)}: the source lands at {@code (11, 17)}, 16x48. */
    private static final Transform ROTATE_270 = new Transform("rotate 270", 0, -1, 1, 0, 4, 70);
    /** The rotation whose cosine and sine are 0.8 and 0.6, about 36.87 degrees. */
    private static final Transform ROTATE_3_4_5 = new Transform("rotate atan(3/4)", 0.8, 0.6, -0.6, 0.8, 20, -6);
    /** {@code x' = x - 0.35 y + 10}, {@code y' = y - 0.25 x + 25}. */
    private static final Transform SHEAR = new Transform("shear (-0.35, -0.25)", 1, -0.25, -0.35, 1, 10, 25);

    private static final Kernel BLUR = new Kernel("blur radii 5 and 3", JSWLinearConvolvePeer.class.getName(),
            tx -> new GaussianRenderState(5f, 3f, 0f, false, null, tx));
    private static final Kernel SHADOW = new Kernel("shadow radii 5 and 3",
            JSWLinearConvolveShadowPeer.class.getName(),
            tx -> new GaussianRenderState(5f, 3f, 0f, true, SHADOW_TINT, tx));

    private static final String HV = "filterHV";
    private static final String VECTOR = "filterVector";

    /** Two premultiplied opaque colours and a premultiplied translucent one. */
    private static final int COLOR_A = 0xFF2060A0;
    private static final int COLOR_B = 0xFFE0B040;
    private static final int COLOR_C = 0x80402010;

    /** The calls each pass of a render on a {@link #recordingBackend()} made, in order; removed after each render. */
    private static final ThreadLocal<List<Call>> CALLS = ThreadLocal.withInitial(ArrayList::new);

    /**
     * An affine transform {@code (x, y) -> (mxx x + mxy y + mxt, myx x + myy y + myt)}, with its inverse computed here
     * from the matrix, independently of the transform classes the peers use.
     */
    record Transform(String name, double mxx, double myx, double mxy, double myy, double mxt, double myt) {

        BaseTransform affine() {
            return new Affine2D(mxx, myx, mxy, myy, mxt, myt);
        }

        private double det() {
            return mxx * myy - mxy * myx;
        }

        /** {@code T^-1} applied to the delta {@code (dx, dy)}. */
        double[] inverseDelta(double dx, double dy) {
            return new double[] {(myy * dx - mxy * dy) / det(), (mxx * dy - myx * dx) / det()};
        }

        /** {@code T^-1} applied to the point {@code (x, y)}. */
        double[] inverse(double x, double y) {
            return inverseDelta(x - mxt, y - myt);
        }

        /** The bounds of the transformed rectangle, rounded out to whole pixels. */
        Rectangle bounds(Rectangle r) {
            double minx = Double.POSITIVE_INFINITY;
            double miny = Double.POSITIVE_INFINITY;
            double maxx = Double.NEGATIVE_INFINITY;
            double maxy = Double.NEGATIVE_INFINITY;
            for (int[] corner : new int[][] {{r.x, r.y}, {r.x + r.width, r.y}, {r.x, r.y + r.height},
                    {r.x + r.width, r.y + r.height}}) {
                double x = mxx * corner[0] + mxy * corner[1] + mxt;
                double y = myx * corner[0] + myy * corner[1] + myt;
                minx = Math.min(minx, x);
                miny = Math.min(miny, y);
                maxx = Math.max(maxx, x);
                maxy = Math.max(maxy, y);
            }
            int x0 = (int) Math.floor(minx);
            int y0 = (int) Math.floor(miny);
            return new Rectangle(x0, y0, (int) Math.ceil(maxx) - x0, (int) Math.ceil(maxy) - y0);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A Gaussian state under a filter transform, and the peer it runs. */
    record Kernel(String name, String peer, Function<BaseTransform, GaussianRenderState> state) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * One call of a peer's loop: the pass, the loop, the destination and input bounds, whether the input carried more
     * than a translation, and for {@code filterVector} its start point and steps
     * {@code srcx0, srcy0, dxcol, dycol, dxrow, dyrow}.
     */
    record Call(String peer, int pass, String loop, Rectangle dst, Rectangle input, boolean transformedInput,
                float[] vector) {

        String step() {
            return "pass " + pass + " " + loop;
        }
    }

    /** The source pixel at texel {@code (x, y)}: a glyph like an F with a translucent dot, on transparent. */
    private static int source(int x, int y) {
        if (x >= 4 && x < 10 && y >= 2 && y < 14 || x >= 4 && x < 40 && y >= 2 && y < 6) {
            return COLOR_A;
        }
        if (x >= 10 && x < 26 && y >= 8 && y < 11) {
            return COLOR_B;
        }
        if (x >= 34 && x < 42 && y >= 9 && y < 14) {
            return COLOR_C;
        }
        return 0;
    }

    private static int[] sourcePixels() {
        int w = SOURCE_BOUNDS.width;
        int[] pixels = new int[w * SOURCE_BOUNDS.height];
        for (int y = 0; y < SOURCE_BOUNDS.height; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = source(x, y);
            }
        }
        return pixels;
    }

    static Stream<Arguments> stepCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Kernel kernel : List.of(BLUR, SHADOW)) {
            for (Transform tx : List.of(ROTATE_90, ROTATE_270, ROTATE_3_4_5, SHEAR)) {
                cases.add(Arguments.of(kernel + ", " + tx, kernel, tx));
            }
        }
        return cases.stream();
    }

    static Stream<Arguments> quarterTurnCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Kernel kernel : List.of(BLUR, SHADOW)) {
            for (Transform tx : List.of(ROTATE_90, ROTATE_270)) {
                cases.add(Arguments.of(kernel + ", " + tx, kernel, tx));
            }
        }
        return cases.stream();
    }

    /**
     * Pass 0 steps through the source along {@code T^-1 (1, 0)} per destination column and {@code T^-1 (0, 1)} per
     * destination row, from {@code T^-1} of the destination's upper left corner; pass 1, over the untransformed pass-0
     * result, along {@code (1, 0)} and {@code (0, 1)}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stepCases")
    void filterVectorStepsAreTheInverseTransformedDestinationSteps(String name, Kernel kernel, Transform tx) {
        DecoraBackend backend = recordingBackend();
        ImageData input = backend.data(Image.of(SOURCE_BOUNDS.width, SOURCE_BOUNDS.height, sourcePixels()),
                SOURCE_BOUNDS, tx.affine());
        List<Call> calls = render(backend, input, kernel.state().apply(tx.affine()));
        assertEquals(List.of("pass 0 " + VECTOR, "pass 1 " + VECTOR), calls.stream().map(Call::step).toList(),
                () -> name + ": loops");
        for (Call call : calls) {
            assertEquals(kernel.peer(), call.peer(), () -> name + ": peer of pass " + call.pass());
        }

        Call pass0 = calls.get(0);
        assertTrue(pass0.transformedInput(), () -> name + ": the pass-0 input carries no rotation or shear");
        assertEquals(SOURCE_BOUNDS, pass0.input(), () -> name + ": pass-0 input bounds");
        double aspect = (double) pass0.dst().width / pass0.dst().height;
        assertTrue(Math.max(aspect, 1 / aspect) >= MIN_ASPECT, () -> String.format(Locale.ROOT,
                "%s: the pass-0 destination %s is too close to square to tell the denominators apart", name,
                format(pass0.dst())));
        double[] col = tx.inverseDelta(1, 0);
        double[] row = tx.inverseDelta(0, 1);
        double[] corner = tx.inverse(pass0.dst().x, pass0.dst().y);
        double[] start = {corner[0] - SOURCE_BOUNDS.x, corner[1] - SOURCE_BOUNDS.y};
        assertVector(name + ", pass 0 into " + format(pass0.dst()), pass0, start, col, row);

        Call pass1 = calls.get(1);
        assertFalse(pass1.transformedInput(), () -> name + ": the pass-1 input is transformed");
        assertEquals(pass0.dst(), pass1.input(), () -> name + ": pass-1 input bounds");
        double[] start1 = {pass1.dst().x - pass1.input().x, pass1.dst().y - pass1.input().y};
        assertVector(name + ", pass 1 into " + format(pass1.dst()), pass1, start1, new double[] {1, 0},
                new double[] {0, 1});
    }

    private static void assertVector(String what, Call call, double[] start, double[] col, double[] row) {
        float[] v = call.vector();
        String report = String.format(Locale.ROOT, "%s: start (%.6f, %.6f) expected (%.6f, %.6f); column step"
                + " (%.6f, %.6f) expected (%.6f, %.6f); row step (%.6f, %.6f) expected (%.6f, %.6f)", what, v[0], v[1],
                start[0], start[1], v[2], v[3], col[0], col[1], v[4], v[5], row[0], row[1]);
        System.out.println(report);
        System.out.printf(Locale.ROOT, "%s: step error %.3g, start error %.3g%n", what,
                maxError(v, 2, col[0], col[1], row[0], row[1]), maxError(v, 0, start[0], start[1]));
        assertAll(what,
                () -> assertEquals(start[0], v[0], START_TOLERANCE, () -> "srcx0; " + report),
                () -> assertEquals(start[1], v[1], START_TOLERANCE, () -> "srcy0; " + report),
                () -> assertEquals(col[0], v[2], STEP_TOLERANCE, () -> "dxcol; " + report),
                () -> assertEquals(col[1], v[3], STEP_TOLERANCE, () -> "dycol; " + report),
                () -> assertEquals(row[0], v[4], STEP_TOLERANCE, () -> "dxrow; " + report),
                () -> assertEquals(row[1], v[5], STEP_TOLERANCE, () -> "dyrow; " + report));
    }

    private static double maxError(float[] actual, int from, double... expected) {
        double max = 0;
        for (int i = 0; i < expected.length; i++) {
            max = Math.max(max, Math.abs(actual[from + i] - expected[i]));
        }
        return max;
    }

    /**
     * Under a quarter turn, the kernel over the rotated input matches the same state over the pre-rotated source with
     * no transform, within one step per channel.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("quarterTurnCases")
    void quarterTurnMatchesThePreRotatedInput(String name, Kernel kernel, Transform tx) {
        Rectangle device = tx.bounds(SOURCE_BOUNDS);
        int[] preRotated = new int[device.width * device.height];
        for (int v = 0; v < device.height; v++) {
            for (int u = 0; u < device.width; u++) {
                double[] p = tx.inverse(device.x + u + 0.5, device.y + v + 0.5);
                int x = (int) Math.floor(p[0]) - SOURCE_BOUNDS.x;
                int y = (int) Math.floor(p[1]) - SOURCE_BOUNDS.y;
                if (x < 0 || y < 0 || x >= SOURCE_BOUNDS.width || y >= SOURCE_BOUNDS.height) {
                    fail(name + ": device pixel (" + (device.x + u) + "," + (device.y + v) + ") of " + format(device)
                            + " maps outside the source");
                }
                preRotated[v * device.width + u] = source(x, y);
            }
        }

        DecoraBackend rotatedBackend = recordingBackend();
        ImageData rotatedInput = rotatedBackend.data(Image.of(SOURCE_BOUNDS.width, SOURCE_BOUNDS.height,
                sourcePixels()), SOURCE_BOUNDS, tx.affine());
        List<Call> rotatedCalls = new ArrayList<>();
        Result rotated = render(rotatedBackend, rotatedInput, kernel.state().apply(tx.affine()), rotatedCalls);
        DecoraBackend preRotatedBackend = recordingBackend();
        ImageData preRotatedInput = preRotatedBackend.data(Image.of(device.width, device.height, preRotated), device);
        List<Call> preRotatedCalls = new ArrayList<>();
        Result expected = render(preRotatedBackend, preRotatedInput, kernel.state().apply(tx.affine()),
                preRotatedCalls);

        List<String> loops = List.of("pass 0 " + VECTOR, "pass 1 " + VECTOR);
        assertEquals(loops, rotatedCalls.stream().map(Call::step).toList(), () -> name + ": loops, rotated input");
        assertEquals(loops, preRotatedCalls.stream().map(Call::step).toList(),
                () -> name + ": loops, pre-rotated input");
        assertTrue(rotatedCalls.get(0).transformedInput(), () -> name + ": the rotated input carries no rotation");
        assertFalse(preRotatedCalls.get(0).transformedInput(), () -> name + ": the pre-rotated input is transformed");

        Rectangle rotatedBounds = new Rectangle(rotated.x(), rotated.y(), rotated.width(), rotated.height());
        Rectangle expectedBounds = new Rectangle(expected.x(), expected.y(), expected.width(), expected.height());
        assertEquals(expectedBounds, rotatedBounds, () -> name + ": result bounds");
        int maxDelta = 0;
        int differ = 0;
        int worst = 0;
        for (int i = 0; i < expected.pixels().length; i++) {
            int delta = channelDelta(rotated.pixels()[i], expected.pixels()[i]);
            differ += delta > 0 ? 1 : 0;
            if (delta > maxDelta) {
                maxDelta = delta;
                worst = i;
            }
        }
        String summary = String.format(Locale.ROOT, "%s: result %s, max delta %d at (%d,%d), %d of %d pixels differ",
                name, format(expectedBounds), maxDelta, expected.x() + worst % expected.width(),
                expected.y() + worst / expected.width(), differ, expected.pixels().length);
        System.out.println(summary);
        assertTrue(maxDelta <= ONE_STEP, () -> "the rotated input renders differently from the pre-rotated one: "
                + summary);
    }

    private static List<Call> render(DecoraBackend backend, ImageData input, GaussianRenderState state) {
        List<Call> calls = new ArrayList<>();
        render(backend, input, state, calls);
        return calls;
    }

    private static Result render(DecoraBackend backend, ImageData input, GaussianRenderState state,
                                 List<Call> calls) {
        try {
            Result result = backend.convolve(input, state, null);
            calls.addAll(CALLS.get());
            return result;
        } finally {
            CALLS.remove();
        }
    }

    private static String format(Rectangle r) {
        return String.format(Locale.ROOT, "(%d,%d %dx%d)", r.x, r.y, r.width, r.height);
    }

    /** A Java backend whose {@code LinearConvolve} peers record their loop calls in {@link #CALLS}. */
    private static DecoraBackend recordingBackend() {
        return DecoraBackend.javaWith(new RendererDelegate() {
            @Override
            public AccelType getAccelType() {
                return AccelType.NONE;
            }

            @Override
            public String getPlatformPeerName(String name, int unrollCount) {
                return switch (name) {
                    case "LinearConvolve" -> RecordingLinearConvolvePeer.class.getName();
                    case "LinearConvolveShadow" -> RecordingLinearConvolveShadowPeer.class.getName();
                    default -> throw new IllegalArgumentException("unexpected peer " + name);
                };
            }
        });
    }

    private static Call call(String peer, int pass, String loop, Rectangle dst, Rectangle input,
                             BaseTransform inputTransform, float[] vector) {
        return new Call(peer, pass, loop, new Rectangle(dst), new Rectangle(input),
                !inputTransform.isTranslateOrIdentity(), vector);
    }

    /** {@code JSWLinearConvolvePeer} recording its loop calls. */
    public static final class RecordingLinearConvolvePeer extends JSWLinearConvolvePeer {

        public RecordingLinearConvolvePeer(FilterContext fctx, Renderer r, String uniqueName) {
            super(fctx, r, uniqueName);
        }

        @Override
        protected void filterVector(int[] dstPixels, int dstw, int dsth, int dstscan, int[] srcPixels, int srcw,
                                    int srch, int srcscan, float[] weights, int count, float srcx0, float srcy0,
                                    float offsetx, float offsety, float deltax, float deltay, float dxcol,
                                    float dycol, float dxrow, float dyrow) {
            CALLS.get().add(call(JSWLinearConvolvePeer.class.getName(), getPass(), VECTOR, getDestBounds(),
                    getInputBounds(0), getInputTransform(0),
                    new float[] {srcx0, srcy0, dxcol, dycol, dxrow, dyrow}));
            super.filterVector(dstPixels, dstw, dsth, dstscan, srcPixels, srcw, srch, srcscan, weights, count, srcx0,
                    srcy0, offsetx, offsety, deltax, deltay, dxcol, dycol, dxrow, dyrow);
        }

        @Override
        protected void filterHV(int[] dstPixels, int dstcols, int dstrows, int dcolinc, int drowinc, int[] srcPixels,
                                int srccols, int srcrows, int scolinc, int srowinc, float[] weights) {
            CALLS.get().add(call(JSWLinearConvolvePeer.class.getName(), getPass(), HV, getDestBounds(),
                    getInputBounds(0), getInputTransform(0), null));
            super.filterHV(dstPixels, dstcols, dstrows, dcolinc, drowinc, srcPixels, srccols, srcrows, scolinc,
                    srowinc, weights);
        }
    }

    /** {@code JSWLinearConvolveShadowPeer} recording its loop calls. */
    public static final class RecordingLinearConvolveShadowPeer extends JSWLinearConvolveShadowPeer {

        public RecordingLinearConvolveShadowPeer(FilterContext fctx, Renderer r, String uniqueName) {
            super(fctx, r, uniqueName);
        }

        @Override
        protected void filterVector(int[] dstPixels, int dstw, int dsth, int dstscan, int[] srcPixels, int srcw,
                                    int srch, int srcscan, float[] weights, int count, float srcx0, float srcy0,
                                    float offsetx, float offsety, float deltax, float deltay, float dxcol,
                                    float dycol, float dxrow, float dyrow) {
            CALLS.get().add(call(JSWLinearConvolveShadowPeer.class.getName(), getPass(), VECTOR, getDestBounds(),
                    getInputBounds(0), getInputTransform(0),
                    new float[] {srcx0, srcy0, dxcol, dycol, dxrow, dyrow}));
            super.filterVector(dstPixels, dstw, dsth, dstscan, srcPixels, srcw, srch, srcscan, weights, count, srcx0,
                    srcy0, offsetx, offsety, deltax, deltay, dxcol, dycol, dxrow, dyrow);
        }

        @Override
        protected void filterHV(int[] dstPixels, int dstcols, int dstrows, int dcolinc, int drowinc, int[] srcPixels,
                                int srccols, int srcrows, int scolinc, int srowinc, float[] weights) {
            CALLS.get().add(call(JSWLinearConvolveShadowPeer.class.getName(), getPass(), HV, getDestBounds(),
                    getInputBounds(0), getInputTransform(0), null));
            super.filterHV(dstPixels, dstcols, dstrows, dcolinc, drowinc, srcPixels, srccols, srcrows, scolinc,
                    srowinc, weights);
        }
    }
}
