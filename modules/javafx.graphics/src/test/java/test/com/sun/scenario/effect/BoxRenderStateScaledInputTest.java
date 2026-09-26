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

import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.Filterable;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.state.BoxRenderState;
import com.sun.scenario.effect.impl.state.LinearConvolveRenderState;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.scenario.effect.DecoraBackend.Image;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A box pass over an input whose {@code ImageData} carries a scale has to cover as many device pixels as the box size
 * says, as the same pass over an unscaled input does: {@code BoxRenderState.validatePassInput} has to scale the pass
 * size by the input's texels per filter pixel once.
 * <p>
 * {@code BoxRenderState} keeps the box size of each pass in filter pixels, which are device pixels in the render space
 * the box effects filter in: the effect's size times the filter transform's scale along the pass's axis
 * ({@code inputSizeH}, {@code inputSizeV}; {@code iSize} below). An input whose transform is not a translation, as an
 * {@code ImageInput} returns under a node, snapshot or HiDPI scale, reaches {@code validatePassInput} with texels
 * scaled by {@code s}. It maps the pass's unit filter-space sample vector back into texel space through the input
 * transform, where the vector's length {@code srcScale} is the number of texels per filter pixel, {@code 1 / s}, and
 * renormalises it to a unit texel step. The box then has to be {@code iSize * srcScale} texels long, which is
 * {@code iSize} device pixels. At commit 7b4df941b6 {@code validatePassInput} multiplied the pass size by
 * {@code srcScale} a second time, where {@code GaussianRenderState.validatePassInput} multiplies the radius once, so
 * the box covered {@code iSize / s} device pixels: half its size under a scale of 2, twice its size under a scale of
 * 0.5. The fix removes the second multiplication. A pass longer than {@code BoxRenderState.getMaxSizeForKernelSize}
 * allows is clamped to that size and sampled with a longer step ({@code srcScale = maxPassSize / iSize}), which gives
 * the right extent before and after the fix; the fix only moves where the clamp starts, from the size scaled twice to
 * the texel size.
 * <p>
 * The oracle is the device-pixel extent of a validated pass: its pass size (texels) times its tap step (texels) times
 * the device pixels per texel along the pass's axis has to be {@code iSize}. The texel box sizes alone could not be
 * compared with those of a pre-scaled input, since they differ from them by the scale. The factors come from the
 * public API and from the case:
 * <ul>
 * <li>The tap step is the increment of {@code getPassVector()}, which is the texel step divided by the physical width
 * or height of the input image, multiplied back by it. Its component across the pass's axis has to be zero, and its
 * length one texel unless the pass is clamped, which is what lets the software box peers run the pass.</li>
 * <li>The device pixels per texel are the length of a unit texel step along the pass's axis mapped through the case's
 * input transform, taken from the case, not from the state.</li>
 * <li>The pass size of a single-pass state with spread 0 is the reciprocal of its centre weight:
 * {@code BoxRenderState.validateWeights} weighs the inner taps of the box with one and the two end taps together with
 * the part of the pass size the inner taps leave, so the weights sum to the pass size, and divides them by that sum.
 * A multi-pass state convolves the box with itself, whose centre weight is no such simple function of the pass size,
 * so for three passes, as in the shadows' default {@code BlurType.THREE_PASS_BOX}, the test compares the integer
 * {@code getBoxPixelSize} and {@code getPassKernelSize} with those of the texel size {@code iSize / s}, and asserts
 * that the size scaled twice would give another box. A clamped pass size is exactly the odd maximum, which is its
 * box, so the three-pass clamp case checks the device extent as well.</li>
 * </ul>
 * Each case validates pass 0 and then pass 1 of one state on the same input, as the software path does:
 * {@code JSWBoxBlurPeer} and {@code JSWBoxShadowPeer} return the pass-0 result under the input's transform. The
 * {@code LinearConvolve} peers of the GPU path return it untransformed, which the identity control stands for.
 * <p>
 * The cases are identity and translated inputs, whose pass size is the filter size; inputs scaled by 2, by 1.5 with a
 * translation, by 0.5 and by the anisotropic (2, 1.5) under an identity filter transform; a filter transform and an
 * input both scaled by 2 or by 1.5, which is what an {@code ImageInput} on a scaled node produces: the state's
 * constructor asks for the input in render space under the filter transform, and the {@code ImageInput} returns its
 * image unresampled under that transform; a shadow; and for each pass count two inputs scaled by 0.5, one whose pass
 * is clamped only when its size is scaled twice, whose unit tap step and box pin where the clamp starts since a clamp
 * keeps the device extent right, and one whose pass is clamped both before and after the fix, a regression guard of
 * the clamp and the renormalisation that passes before the fix as well. The expected three-pass boxes cannot be moved
 * by rounding: the texel sizes are exact in {@code float} without a scale and under the power-of-two scales, and
 * under 1.5 they are 6 texels, an even number, which {@code ceil(size) | 1} makes 7 from either side.
 * <p>
 * The tolerance is the relative error of float rounding. An extent goes through at most six roundings to
 * {@code float}, each off by at most {@code 2^-24} relatively: the filter size, the inverse-transformed sample vector,
 * the pass size (or, when clamped, {@code maxPassSize / iSize}), the renormalised sample vector, its division by the
 * image width or height in {@code getPassVector}'s increment, and the centre weight. The double-precision steps
 * ({@code Math.hypot}, the divisions and the test's products) add a few {@code 2^-53}. {@link #TOLERANCE},
 * {@code 8 * 2^-24} (about 4.8e-7), bounds that with a margin; the errors the fix removes are a third of an extent or
 * more, and over 6 percent of a tap step.
 * <p>
 * At commit 7b4df941b6, whose {@code validatePassInput} scaled the pass size twice, every scaled case failed, and the
 * controls and the cases clamped before and after the fix passed. The single-pass boxes of sizes 9 and 5 covered 4.5
 * and 2.5 device pixels under a scale of 2, 6 and 3.33 under 1.5, 18 and 10 under 0.5, and 4.5 and 3.33 under
 * (2, 1.5); the shadow the same as the blur; under a filter and input scale of 2 or 1.5 they covered 9 and 5 of 18 and
 * 10, or of 13.5 and 7.5, device pixels. The box of size 40 under 0.5 covered the right 40 device pixels, but with
 * 127 taps at a step of 0.63 texels where 80 texels at a unit step are due. The three-pass boxes of size 9 were 3
 * texels where 5 are due under a scale of 2, 5 for 7 under 1.5, 37 for 19 under 0.5, 3 and 5 for 5 and 7 under
 * (2, 1.5), and 5 for 9 under a filter and input scale of 2; the box of size 20 under 0.5 was 43 texels at a step of
 * 0.93 texels where 41 texels at a unit step are due.
 */
public class BoxRenderStateScaledInputTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    private static final BaseTransform IDENTITY = BaseTransform.IDENTITY_TRANSFORM;

    /** The unit roundoff of {@code float}: one rounding to {@code float} is off by at most this, relatively. */
    private static final double FLOAT_ROUNDOFF = 0x1p-24;

    /** The relative tolerance on a device-pixel extent and on a tap step; see the class description. */
    static final double TOLERANCE = 8 * FLOAT_ROUNDOFF;

    /** What a case covers. */
    enum Kind {
        /** An identity or translated input: the pass size is the filter size. */
        CONTROL(false),
        /** A scaled input under an identity filter transform. */
        SCALED(true),
        /** A filter transform and an input with the same scale, as for an {@code ImageInput} on a scaled node. */
        FILTER_SCALED(true),
        /** A scaled input whose pass is longer than the largest box, whether scaled once or twice. */
        CLAMPED(false);

        /** Whether the pass size scaled twice gives another box than the pass size scaled once. */
        private final boolean discriminating;

        Kind(boolean discriminating) {
            this.discriminating = discriminating;
        }
    }

    /**
     * A box kernel of {@code hsize x vsize} with {@code passes} blur passes and spread 0, under
     * {@code filterTransform}, over a 64x48 input carrying {@code inputTransform}.
     */
    record Case(Kind kind, String what, float hsize, float vsize, int passes, boolean shadow,
                BaseTransform filterTransform, BaseTransform inputTransform) {

        BoxRenderState state() {
            return new BoxRenderState(hsize, vsize, passes, 0f, shadow, shadow ? Color4f.BLACK : null,
                    filterTransform);
        }

        ImageData input() {
            DecoraBackend backend = DecoraBackend.java();
            return backend.data(Image.of(WIDTH, HEIGHT, new int[WIDTH * HEIGHT]), 0, 0).transform(inputTransform);
        }

        /** The box size along the pass's axis in filter (device) pixels: the effect's size times the filter scale. */
        double filterSize(int pass) {
            return (pass == 0 ? hsize : vsize) * axisScale(filterTransform, pass);
        }

        /** The device pixels one input texel covers along the pass's axis. */
        double devicePerTexel(int pass) {
            return axisScale(inputTransform, pass);
        }

        /** The box size in texels whose device-pixel extent is the filter size. */
        double texelSize(int pass) {
            return filterSize(pass) / devicePerTexel(pass);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s h=%s v=%s passes=%d, %s", shadow ? "shadow" : "blur", hsize, vsize,
                    passes, what);
        }
    }

    /**
     * One validated pass as the peers see it: its box and kernel sizes and the lengths of its tap step along and
     * across the pass's axis, in texels.
     */
    record Pass(int boxSize, int kernelSize, double step, double stepAcross) {
    }

    static Stream<Case> singlePassCases() {
        return Stream.of(
                new Case(Kind.CONTROL, "identity input", 9, 5, 1, false, IDENTITY, IDENTITY),
                new Case(Kind.CONTROL, "input translated by (5, 7)", 9, 5, 1, false, IDENTITY,
                        BaseTransform.getTranslateInstance(5, 7)),
                new Case(Kind.SCALED, "input scaled by 2", 9, 5, 1, false, IDENTITY, scale(2, 2)),
                new Case(Kind.SCALED, "input scaled by 1.5 and translated by (22, 12)", 9, 5, 1, false, IDENTITY,
                        BaseTransform.getInstance(1.5, 0, 0, 1.5, 22, 12)),
                new Case(Kind.SCALED, "input scaled by 0.5", 9, 5, 1, false, IDENTITY, scale(0.5, 0.5)),
                new Case(Kind.SCALED, "input scaled by 0.5, clamped only when scaled twice", 40, 40, 1, false,
                        IDENTITY, scale(0.5, 0.5)),
                new Case(Kind.SCALED, "input scaled by (2, 1.5)", 9, 5, 1, false, IDENTITY, scale(2, 1.5)),
                new Case(Kind.SCALED, "input scaled by 2", 9, 5, 1, true, IDENTITY, scale(2, 2)),
                new Case(Kind.FILTER_SCALED, "filter and input scaled by 2", 9, 5, 1, false, scale(2, 2),
                        scale(2, 2)),
                new Case(Kind.FILTER_SCALED, "filter and input scaled by 1.5", 9, 5, 1, false, scale(1.5, 1.5),
                        scale(1.5, 1.5)),
                new Case(Kind.CLAMPED, "input scaled by 0.5", 100, 100, 1, false, IDENTITY, scale(0.5, 0.5)));
    }

    static Stream<Case> threePassCases() {
        return Stream.of(
                new Case(Kind.CONTROL, "input translated by (5, 7)", 9, 9, 3, false, IDENTITY,
                        BaseTransform.getTranslateInstance(5, 7)),
                new Case(Kind.SCALED, "input scaled by 2", 9, 9, 3, false, IDENTITY, scale(2, 2)),
                new Case(Kind.SCALED, "input scaled by 1.5 and translated by (22, 12)", 9, 9, 3, false, IDENTITY,
                        BaseTransform.getInstance(1.5, 0, 0, 1.5, 22, 12)),
                new Case(Kind.SCALED, "input scaled by 0.5", 9, 9, 3, false, IDENTITY, scale(0.5, 0.5)),
                new Case(Kind.SCALED, "input scaled by 0.5, clamped only when scaled twice", 20, 20, 3, false,
                        IDENTITY, scale(0.5, 0.5)),
                new Case(Kind.SCALED, "input scaled by (2, 1.5)", 9, 9, 3, false, IDENTITY, scale(2, 1.5)),
                new Case(Kind.FILTER_SCALED, "filter and input scaled by 2", 9, 9, 3, false, scale(2, 2),
                        scale(2, 2)),
                new Case(Kind.CLAMPED, "input scaled by 0.5", 30, 30, 3, false, IDENTITY, scale(0.5, 0.5)));
    }

    /**
     * A single-pass box covers {@code iSize} device pixels on both passes: pass size, from the centre weight, times
     * tap step times device pixels per texel.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("singlePassCases")
    void singlePassCoversTheFilterSize(Case c) {
        BoxRenderState state = c.state();
        ImageData input = c.input();
        List<Executable> checks = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            final int axis = pass;
            Pass p = validate(c, state, input, pass);
            assertTrue(p.kernelSize() >= 3, () -> c + " pass " + axis + ": a box of one tap, whose weight is not the"
                    + " reciprocal of the pass size");
            double passSize = 1.0 / centreWeight(state, p);
            checks.addAll(extentChecks(c, pass, p, passSize));
        }
        assertAll(checks);
    }

    /**
     * A three-pass box is the one of the texel size that covers {@code iSize} device pixels, which the size scaled
     * twice is not; a clamped pass covers {@code iSize} device pixels exactly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("threePassCases")
    void threePassBoxMatchesTheFilterSize(Case c) {
        BoxRenderState state = c.state();
        ImageData input = c.input();
        int maxPassSize = maxPassSize(c.passes());
        List<Executable> checks = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            final int axis = pass;
            Pass p = validate(c, state, input, pass);
            double texels = c.texelSize(pass);
            int expectedBox = texels > maxPassSize ? maxPassSize : boxSize(texels);
            double twiceTexels = texels / c.devicePerTexel(pass);
            int twiceBox = twiceTexels > maxPassSize ? maxPassSize : boxSize(twiceTexels);
            System.out.printf(Locale.ROOT, "%s pass %d: box %d texels (kernel %d), expected %d for %.6f texels;"
                    + " the size scaled twice, %.6f texels, gives %d%n", c, pass, p.boxSize(), p.kernelSize(),
                    expectedBox, texels, twiceTexels, twiceBox);
            checks.add(() -> assertEquals(expectedBox, p.boxSize(), () -> c + " pass " + axis
                    + ": getBoxPixelSize, texels"));
            checks.add(() -> assertEquals((expectedBox - 1) * c.passes() + 1, p.kernelSize(), () -> c + " pass "
                    + axis + ": getPassKernelSize"));
            if (c.kind().discriminating) {
                checks.add(() -> assertNotEquals(expectedBox, twiceBox, () -> c + " pass " + axis
                        + ": the case cannot tell the pass size scaled once from the size scaled twice"));
            }
            if (texels > maxPassSize) {
                checks.addAll(extentChecks(c, pass, p, p.boxSize()));
            } else {
                checks.addAll(stepChecks(c, pass, p, 1.0));
            }
        }
        assertAll(checks);
    }

    /**
     * Validates {@code pass} of {@code state} for the case's input and reads the pass back through the public API. The
     * case has to be set up as its kind says: the filter size within the largest box, which the constructor would
     * clamp, and the texel size beyond it for a clamp case only.
     */
    private static Pass validate(Case c, BoxRenderState state, ImageData input, int pass) {
        int maxPassSize = maxPassSize(c.passes());
        assertTrue(c.filterSize(pass) <= maxPassSize, () -> c + " pass " + pass + ": the constructor clamps the"
                + " filter size to " + maxPassSize);
        assertEquals(c.kind() == Kind.CLAMPED, c.texelSize(pass) > maxPassSize, () -> c + " pass " + pass
                + ": the texel size " + c.texelSize(pass) + " is not clamped as the case says, largest box "
                + maxPassSize);
        assertSame(input, state.validatePassInput(input, pass), () -> c + " pass " + pass + ": validated input");
        float[] vector = state.getPassVector();
        Filterable image = input.getUntransformedImage();
        double[] physical = {image.getPhysicalWidth(), image.getPhysicalHeight()};
        return new Pass(state.getBoxPixelSize(pass), state.getPassKernelSize(),
                Math.abs(vector[pass] * physical[pass]), Math.abs(vector[1 - pass] * physical[1 - pass]));
    }

    /**
     * The device extent of a pass of {@code passSize} texels, which has to be the filter size, and its tap step,
     * which has to be one texel unless the pass is clamped, and then the texel size spread over the largest box.
     */
    private static List<Executable> extentChecks(Case c, int pass, Pass p, double passSize) {
        double filterSize = c.filterSize(pass);
        double devicePerTexel = c.devicePerTexel(pass);
        double extent = passSize * p.step() * devicePerTexel;
        double texels = c.texelSize(pass);
        int maxPassSize = maxPassSize(c.passes());
        boolean clamped = texels > maxPassSize;
        double twiceTexels = texels / devicePerTexel;
        double twiceExtent = twiceTexels > maxPassSize ? filterSize : twiceTexels * devicePerTexel;
        System.out.printf(Locale.ROOT, "%s pass %d: %.6f texels x step %.6f texels x %s device px/texel ="
                + " %.6f device px, expected %s%s; the size scaled twice covers %.6f%n", c, pass, passSize, p.step(),
                devicePerTexel, extent, filterSize, clamped ? " (clamped to " + maxPassSize + ")" : "", twiceExtent);
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertTrue(Math.abs(extent - filterSize) <= TOLERANCE * filterSize, () -> String.format(
                Locale.ROOT, "%s pass %d: the box covers %.6f device pixels (%.6f texels x step %.6f texels x %s"
                + " device px/texel), not the filter size %s; relative error %.3g, tolerance %.3g", c, pass, extent,
                passSize, p.step(), devicePerTexel, filterSize, Math.abs(extent - filterSize) / filterSize,
                TOLERANCE)));
        checks.addAll(stepChecks(c, pass, p, clamped ? texels / maxPassSize : 1.0));
        return checks;
    }

    /** The tap step runs along the pass's axis and is {@code expectedStep} texels long. */
    private static List<Executable> stepChecks(Case c, int pass, Pass p, double expectedStep) {
        return List.of(
                () -> assertEquals(0.0, p.stepAcross(), () -> c + " pass " + pass
                        + ": tap step across the pass's axis, texels"),
                () -> assertTrue(Math.abs(p.step() - expectedStep) <= TOLERANCE * expectedStep, () -> String.format(
                        Locale.ROOT, "%s pass %d: tap step %.9f texels, expected %.9f, tolerance %.3g", c, pass,
                        p.step(), expectedStep, TOLERANCE)));
    }

    /** The centre weight of a validated single-pass state; the pass size is its reciprocal. */
    private static double centreWeight(BoxRenderState state, Pass p) {
        assertEquals(1, state.getBlurPasses(), "the centre weight is the reciprocal pass size for one pass only");
        FloatBuffer weights = state.getPassWeights();
        return weights.get(p.kernelSize() / 2);
    }

    /** The largest box {@code BoxRenderState} applies in a pass, which it clamps longer passes to. */
    private static int maxPassSize(int passes) {
        return BoxRenderState.getMaxSizeForKernelSize(LinearConvolveRenderState.MAX_KERNEL_SIZE, passes);
    }

    /** The odd box {@code BoxRenderState.getBoxPixelSize} gives a pass size in texels. */
    private static int boxSize(double texels) {
        return ((int) Math.ceil(Math.max(texels, 1.0))) | 1;
    }

    /** The length of a unit step along {@code axis} (0 for x, 1 for y) mapped through {@code tx}. */
    private static double axisScale(BaseTransform tx, int axis) {
        return axis == 0 ? Math.hypot(tx.getMxx(), tx.getMyx()) : Math.hypot(tx.getMxy(), tx.getMyy());
    }

    private static BaseTransform scale(double sx, double sy) {
        return BaseTransform.getScaleInstance(sx, sy);
    }
}
