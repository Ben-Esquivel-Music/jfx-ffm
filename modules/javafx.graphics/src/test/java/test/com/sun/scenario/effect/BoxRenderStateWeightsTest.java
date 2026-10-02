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
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.state.BoxRenderState;
import com.sun.scenario.effect.impl.state.LinearConvolveRenderState;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.scenario.effect.DecoraBackend.Image;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.scenario.effect.BoxKernels.boxLength;
import static test.com.sun.scenario.effect.BoxKernels.repeatedBox;
import static test.com.sun.scenario.effect.BoxKernels.repeatedTrimmedBox;
import static test.com.sun.scenario.effect.BoxKernels.trimmedFirstBox;
import static test.com.sun.scenario.effect.BoxKernels.trimmedTap;

/**
 * The kernel {@code BoxRenderState} hands the {@code LinearConvolve} and {@code LinearConvolveShadow} peers for a box
 * of two or more blur passes has to be the box convolved with itself once per further pass: symmetric about its centre
 * tap and divided by its own sum.
 * <p>
 * {@code BoxRenderState.validateWeights} builds the kernel of a pass in {@code ik[]}: a box of
 * {@code klen = ceil(size) | 1} ones, whose two end taps it trims to {@code 1 - (klen - size) / 2} when the size is
 * not an odd integer, convolved in place with a box of {@code klen} ones for every further pass, from the last tap
 * backwards, and finally divided by the sum of its taps. At commit 900c40e41a the first loop of that convolution,
 * {@code while (i > klen)}, summed the full window of {@code klen} taps only above {@code klen}, and the second,
 * {@code while (i > 0)}, summed taps {@code 0..i}, which is right below {@code klen} only: at {@code i == klen} it
 * added {@code klen + 1} taps. Every kernel of two or more passes was therefore asymmetric and divided by too large a
 * sum: three ones over two passes gave {@code [1 2 3 3 1] / 10} for {@code [1 2 3 2 1] / 9}, and over three passes
 * {@code [1 3 6 9 7 4 1] / 31} for {@code [1 3 6 7 6 3 1] / 27}. The GPU pipelines read these weights for every
 * multi-pass box blur and box shadow; the software pipeline reads them when it falls back to the {@code LinearConvolve}
 * peers, as a shadow with a spread does.
 * <p>
 * Each case validates pass 0 and then pass 1 of one state, built with an identity filter transform, a spread of 0 and
 * no shadow, over an input that carries only a translation, so that {@code validatePassInput} takes the box size of
 * each pass unchanged from the constructor. The horizontal and vertical sizes differ, so a pass given the other pass's
 * size shows as a kernel of the wrong length, or, for 4 and 4.5, which both round up to 5 taps, as wrong end taps.
 * The weights are read through the public API, {@code getPassWeights} and
 * {@code getPassWeightsArrayLength}, and copied before the next pass is validated, since the state refills one buffer.
 * <p>
 * Three oracles judge them:
 * <ul>
 * <li>For an odd integer size, which trims no tap, the kernel is the {@code passes}-fold convolution of a box of
 * {@code size} ones divided by {@code size^passes} ({@link BoxKernels#repeatedBox}: integer counts from a plain
 * nested-loop convolution).</li>
 * <li>For every size, the kernel has {@code getPassKernelSize()} taps, all positive, followed by zeros up to
 * {@code getPassWeightsArrayLength() * 4}, which is the peer size {@code LinearConvolveRenderState.getPeerSize} gives
 * the kernel; the taps are symmetric about the centre tap and sum to 1.</li>
 * <li>For a size that is not an odd integer, such as 4, 4.5 or 7.25, the kernel is pinned exactly
 * ({@link BoxKernels#trimmedFirstBox}).</li>
 * </ul>
 * <p>
 * <b>The kernel of a size that is not an odd integer</b> is pinned as {@code validateWeights} builds it once the tap
 * count is right: the first box with its two end taps trimmed to {@code 1 - (klen - size) / 2}, convolved with
 * {@code passes - 1} untrimmed boxes of {@code klen} ones, divided by its sum. That is not the {@code passes}-fold
 * convolution of the trimmed box, which the class description of {@code BoxRenderState} could be read to promise: for
 * a size of 4 over two passes it is {@code [0.5 1 1 1 0.5]} convolved with {@code [1 1 1 1 1]}, which is
 * {@code [0.5 1.5 2.5 3.5 4 3.5 2.5 1.5 0.5] / 20}, where the trimmed box convolved with itself would be
 * {@code [0.25 1 2 3 3.5 3 2 1 0.25] / 16}. Backlog story US-011 fixes the tap count and keeps this trimming on
 * purpose; whether the trimming should change is the open question of backlog story US-055, and such a change is a
 * behaviour change of its own, which has to change this oracle deliberately.
 * <p>
 * <b>Tolerances.</b> The weights are compared with the oracles exactly. Every value {@code validateWeights} computes
 * before the final division is exact in {@code double}, whatever the order of its sums, and so is every value of the
 * oracles: a pass size is a {@code float} of at least 1, so {@code klen - size} is exact (in {@code float} too, where
 * {@code validateWeights} subtracts) and a multiple of {@code 2^-23}, a trimmed tap {@code 1 - (klen - size) / 2} is a
 * multiple of {@code 2^-24}, and every tap and sum after that is a sum of ones and trimmed taps of at most
 * {@code klen^passes}, below {@code 2^17} (79,507 for three passes of 43, the largest), which needs at most 41 of the
 * 53 bits. The production weights and the oracles therefore divide the same {@code double} values by the same sum and
 * round the same quotient to {@code float}. For the same reason the weights are compared with their mirror images
 * exactly: equal {@code double} taps round to equal {@code float} weights. Their sum may differ from 1 by the rounding
 * of each weight to {@code float}, at most {@code 2^-24} of the weight, so at most {@code 2^-24} in all, plus the
 * {@code double} rounding of the test's own sum, at most {@code 127 * 2^-53}; {@link #SUM_TOLERANCE}, {@code 2^-23},
 * bounds that with a margin of two. Before the fix the weights summed to 1 as well, as the kernel was divided by its
 * own, too large, sum: the sum guards the normalisation and the padding, and the symmetry and the oracles find the
 * extra tap.
 * <p>
 * The odd sizes are 3 and 15 over two passes, 3 and 9 over three passes, and the largest box
 * {@code BoxRenderState.getMaxSizeForKernelSize} allows for two and for three passes, whose kernels are the longest the
 * peers take, with 25 over two passes and 5 over three as in the multi-pass box rows of the Decora golden. The other
 * sizes are 4 and 4.5, and 2.5 and 7.25, over two and three passes; 4 and 6 over three passes, as in the golden's box
 * rows with a spread; and half a pixel below the largest box for three passes with 7.3, which unlike the others is no
 * short binary fraction but fills the 24-bit significand of its {@code float}. The single-pass controls, 5 and 9, the
 * largest single-pass box with 3, and 4 and 4.5, convolve nothing.
 * <p>
 * At commit 900c40e41a every multi-pass case failed on both passes, in all three parameterized tests, and the
 * single-pass controls and the check of the oracles passed.
 */
public class BoxRenderStateWeightsTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    /** The tolerance on the sum of a kernel's weights; see the class description. */
    static final double SUM_TOLERANCE = 0x1p-23;

    /** Kernels up to this many taps are printed in full in a failure message, longer ones around the tap it names. */
    private static final int PRINTED_TAPS = 15;

    /** A box kernel of {@code hsize x vsize} with {@code passes} blur passes. */
    record Case(float hsize, float vsize, int passes) {

        float size(int pass) {
            return pass == 0 ? hsize : vsize;
        }

        BoxRenderState state() {
            return new BoxRenderState(hsize, vsize, passes, 0f, false, null, BaseTransform.IDENTITY_TRANSFORM);
        }

        ImageData input() {
            return DecoraBackend.java().data(Image.of(WIDTH, HEIGHT, new int[WIDTH * HEIGHT]), 0, 0)
                    .transform(BaseTransform.getTranslateInstance(5, 7));
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "h=%s v=%s passes=%d", hsize, vsize, passes);
        }
    }

    /**
     * One validated pass: {@code getPassKernelSize()}, {@code getPassWeightsArrayLength()}, the limit of the
     * {@code getPassWeights()} buffer and the buffer's floats up to that limit.
     */
    record Pass(Case c, int pass, int kernelSize, int arrayLength, int limit, float[] weights) {

        float size() {
            return c.size(pass);
        }

        /** {@code "h=3.0 v=15.0 passes=2, pass 0 (size 3.0)"}, for failure messages. */
        String where() {
            return String.format(Locale.ROOT, "%s, pass %d (size %s)", c, pass, size());
        }
    }

    static Stream<Case> oddSizeCases() {
        return Stream.of(
                new Case(5, 9, 1),
                new Case(maxSize(1), 3, 1),
                new Case(3, 15, 2),
                new Case(3, 9, 3),
                new Case(maxSize(2), 25, 2),
                new Case(maxSize(3), 5, 3));
    }

    static Stream<Case> nonOddSizeCases() {
        return Stream.of(
                new Case(4, 4.5f, 1),
                new Case(4, 4.5f, 2),
                new Case(4, 4.5f, 3),
                new Case(2.5f, 7.25f, 2),
                new Case(2.5f, 7.25f, 3),
                new Case(4, 6, 3),
                new Case(maxSize(3) - 0.5f, 7.3f, 3));
    }

    static Stream<Case> allCases() {
        return Stream.concat(oddSizeCases(), nonOddSizeCases());
    }

    /**
     * For an odd integer size the weights are the {@code passes}-fold convolution of a box of {@code size} ones,
     * divided by {@code size^passes}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("oddSizeCases")
    void oddSizeIsTheRepeatedBox(Case c) {
        List<Executable> checks = new ArrayList<>();
        for (Pass p : validateBothPasses(c)) {
            int size = (int) p.size();
            assertTrue(size == p.size() && (size & 1) == 1, () -> p.where() + ": not an odd integer size");
            float[] expected = normalised(repeatedBox(size, c.passes()));
            checks.add(() -> assertWeights(p, expected, String.format(Locale.ROOT,
                    "the %d-fold convolution of a box of %d ones / %d^%d", c.passes(), size, size, c.passes())));
        }
        assertAll(checks);
    }

    /**
     * For a size that is not an odd integer the weights are the first box, its end taps trimmed, convolved with
     * {@code passes - 1} untrimmed boxes and divided by its sum: the kernel this class pins (see the class
     * description).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonOddSizeCases")
    void nonOddSizeTrimsTheFirstBoxOnly(Case c) {
        List<Executable> checks = new ArrayList<>();
        for (Pass p : validateBothPasses(c)) {
            float size = p.size();
            assertFalse(size == (int) size && (((int) size) & 1) == 1, () -> p.where() + ": an odd integer size");
            int klen = boxLength(size);
            float[] expected = normalised(trimmedFirstBox(size, c.passes()));
            checks.add(() -> assertWeights(p, expected, String.format(Locale.ROOT,
                    "a box of %d taps with end taps %s, convolved with %d box(es) of %d ones, / its sum", klen,
                    trimmedTap(size), c.passes() - 1, klen)));
        }
        assertAll(checks);
    }

    /**
     * For every size the kernel has {@code getPassKernelSize()} positive taps followed by zeros up to
     * {@code getPassWeightsArrayLength() * 4}, the peer size of the kernel, and is symmetric about its centre tap and
     * sums to 1.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void everySizeIsSymmetricAndSumsToOne(Case c) {
        List<Executable> checks = new ArrayList<>();
        for (Pass p : validateBothPasses(c)) {
            System.out.printf(Locale.ROOT, "%s: %d taps, %d weights, sum - 1 = %.3g%n", p.where(), p.kernelSize(),
                    p.limit(), sum(p) - 1.0);
            checks.add(() -> assertPadded(p));
            checks.add(() -> assertSymmetric(p));
            checks.add(() -> assertSumsToOne(p));
        }
        assertAll(checks);
    }

    /**
     * The oracles reproduce worked examples written out by hand, agree with each other where nothing is trimmed, and
     * the pinned kernel is not the trimmed box convolved with itself.
     */
    @Test
    void oraclesReproduceTheWorkedExamples() {
        assertArrayEquals(new long[] {1, 2, 3, 2, 1}, repeatedBox(3, 2), "3 ones over 2 passes");
        assertArrayEquals(new long[] {1, 3, 6, 7, 6, 3, 1}, repeatedBox(3, 3), "3 ones over 3 passes");
        assertArrayEquals(new long[] {1, 1, 1, 1, 1}, repeatedBox(5, 1), "5 ones over 1 pass");
        assertArrayEquals(new double[] {0.75, 1, 1, 1, 0.75}, trimmedFirstBox(4.5f, 1), "size 4.5 over 1 pass");
        assertArrayEquals(new double[] {0.5, 1.5, 2.5, 3.5, 4, 3.5, 2.5, 1.5, 0.5}, trimmedFirstBox(4, 2),
                "size 4 over 2 passes: [0.5 1 1 1 0.5] convolved with [1 1 1 1 1]");
        assertArrayEquals(new double[] {0.125, 1.125, 2.125, 3.125, 4.125, 5.125, 6.125, 7.125, 7.25, 7.125, 6.125,
                5.125, 4.125, 3.125, 2.125, 1.125, 0.125}, trimmedFirstBox(7.25f, 2),
                "size 7.25 over 2 passes: 9 taps with end taps 0.125, convolved with 9 ones");
        double[] trimmedBoxWithItself = {0.25, 1, 2, 3, 3.5, 3, 2, 1, 0.25};
        assertArrayEquals(trimmedBoxWithItself, repeatedTrimmedBox(4, 2), "the trimmed box of size 4 with itself");
        assertFalse(Arrays.equals(trimmedBoxWithItself, trimmedFirstBox(4, 2)),
                "the pinned kernel of size 4 over 2 passes is the trimmed box convolved with itself");
        for (int passes = 1; passes <= 3; passes++) {
            for (int size : new int[] {3, 5, 9}) {
                double[] counts = Arrays.stream(repeatedBox(size, passes)).asDoubleStream().toArray();
                assertArrayEquals(counts, trimmedFirstBox(size, passes), "size " + size + " over " + passes
                        + " passes: the oracles disagree where nothing is trimmed");
            }
        }
    }

    /**
     * Validates pass 0 and then pass 1 of a fresh state for the case's input and copies what each pass hands a peer.
     * Both passes have to keep the input and be more than a no-op, which no peer would run.
     */
    private static List<Pass> validateBothPasses(Case c) {
        BoxRenderState state = c.state();
        ImageData input = c.input();
        List<Pass> passes = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            final int axis = pass;
            assertSame(input, state.validatePassInput(input, pass), () -> c + ", pass " + axis + ": validated input");
            assertFalse(state.isPassNop(), () -> c + ", pass " + axis + ": a no-op pass, which no peer runs");
            int kernelSize = state.getPassKernelSize();
            int arrayLength = state.getPassWeightsArrayLength();
            FloatBuffer buffer = state.getPassWeights();
            float[] weights = new float[buffer.limit()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = buffer.get(i);
            }
            passes.add(new Pass(c, pass, kernelSize, arrayLength, buffer.limit(), weights));
        }
        return passes;
    }

    /**
     * The first {@code getPassKernelSize()} weights of the pass are exactly {@code expected}. A kernel divided by a
     * wrong sum differs from the oracle on every tap, so the message names the tap that differs most, relatively, as
     * well as the first, and prints a long kernel around the tap that differs most.
     */
    private static void assertWeights(Pass p, float[] expected, String oracle) {
        assertEquals(expected.length, p.kernelSize(), () -> p.where() + ": getPassKernelSize, expected the "
                + expected.length + " taps of " + oracle);
        assertTrue(p.weights().length >= expected.length, () -> p.where() + ": " + p.weights().length
                + " weights in the buffer for a kernel of " + expected.length + " taps");
        float[] w = p.weights();
        for (int i = 0; i < expected.length; i++) {
            if (Float.floatToRawIntBits(w[i]) != Float.floatToRawIntBits(expected[i])) {
                final int first = i;
                int most = 0;
                for (int k = 1; k < expected.length; k++) {
                    if (relativeDifference(w[k], expected[k]) > relativeDifference(w[most], expected[most])) {
                        most = k;
                    }
                }
                final int worst = most;
                assertEquals(expected[i], w[i], () -> String.format(Locale.ROOT,
                        "%s: weights differ from %s first at tap %d of %d: %s, expected %s; most at tap %d: %s,"
                        + " expected %s (%+.2f%%);%n  weights  %s%n  expected %s%n", p.where(), oracle, first,
                        expected.length, exact(w[first]), exact(expected[first]), worst, exact(w[worst]),
                        exact(expected[worst]), 100.0 * (w[worst] - (double) expected[worst]) / expected[worst],
                        taps(w, worst, expected.length), taps(expected, worst, expected.length)));
            }
        }
    }

    private static double relativeDifference(float actual, float expected) {
        return Math.abs(actual - (double) expected) / Math.abs((double) expected);
    }

    /**
     * The pass's kernel fills {@code getPassKernelSize()} taps, all positive, and zeros fill the rest of the buffer,
     * whose limit is {@code getPassWeightsArrayLength() * 4} and the peer size of the kernel.
     */
    private static void assertPadded(Pass p) {
        int kernelSize = p.kernelSize();
        assertEquals(p.arrayLength() * 4, p.limit(), () -> p.where() + ": buffer limit against"
                + " getPassWeightsArrayLength() * 4");
        assertEquals(LinearConvolveRenderState.getPeerSize(kernelSize), p.limit(), () -> p.where()
                + ": buffer limit against the peer size of a kernel of " + kernelSize + " taps");
        for (int i = 0; i < p.limit(); i++) {
            final int tap = i;
            if (i < kernelSize) {
                assertTrue(p.weights()[i] > 0f, () -> String.format(Locale.ROOT, "%s: tap %d of the %d kernel taps"
                        + " is %s, not positive: %s", p.where(), tap, kernelSize, exact(p.weights()[tap]),
                        taps(p.weights(), tap, p.limit())));
            } else {
                assertEquals(0f, p.weights()[i], () -> String.format(Locale.ROOT, "%s: padding tap %d (kernel %d"
                        + " taps, %d weights) is not 0: %s", p.where(), tap, kernelSize, p.limit(),
                        taps(p.weights(), tap, p.limit())));
            }
        }
    }

    /** Each weight equals its mirror image about the centre tap exactly. */
    private static void assertSymmetric(Pass p) {
        int kernelSize = p.kernelSize();
        float[] w = p.weights();
        for (int i = 0; i < kernelSize / 2; i++) {
            int mirror = kernelSize - 1 - i;
            if (Float.floatToRawIntBits(w[i]) != Float.floatToRawIntBits(w[mirror])) {
                final int tap = i;
                assertEquals(w[mirror], w[i], () -> String.format(Locale.ROOT, "%s: not symmetric about the centre"
                        + " tap %d of %d: tap %d is %s, its mirror tap %d is %s;%n  weights %s%n", p.where(),
                        kernelSize / 2, kernelSize, tap, exact(w[tap]), mirror, exact(w[mirror]),
                        taps(w, tap, kernelSize)));
            }
        }
    }

    /** The weights sum to 1 within {@link #SUM_TOLERANCE}. */
    private static void assertSumsToOne(Pass p) {
        double sum = sum(p);
        assertTrue(Math.abs(sum - 1.0) <= SUM_TOLERANCE, () -> String.format(Locale.ROOT, "%s: the %d weights sum"
                + " to %.17g, off 1 by %.3g, tolerance %.3g", p.where(), p.kernelSize(), sum, sum - 1.0,
                SUM_TOLERANCE));
    }

    /** The sum of the pass's kernel taps, in {@code double}. */
    private static double sum(Pass p) {
        double sum = 0;
        for (int i = 0; i < p.kernelSize(); i++) {
            sum += p.weights()[i];
        }
        return sum;
    }

    /** The integer counts divided by their sum, rounded to {@code float} as the weights buffer holds them. */
    static float[] normalised(long[] counts) {
        return normalised(Arrays.stream(counts).asDoubleStream().toArray());
    }

    /** The kernel divided by its sum, rounded to {@code float} as the weights buffer holds it. */
    static float[] normalised(double[] kernel) {
        double sum = 0;
        for (double v : kernel) {
            sum += v;
        }
        float[] weights = new float[kernel.length];
        for (int i = 0; i < kernel.length; i++) {
            weights[i] = (float) (kernel[i] / sum);
        }
        return weights;
    }

    /** The largest pass size {@code BoxRenderState} applies for {@code passes} blur passes, which is odd. */
    private static int maxSize(int passes) {
        return BoxRenderState.getMaxSizeForKernelSize(LinearConvolveRenderState.MAX_KERNEL_SIZE, passes);
    }

    private static String exact(float v) {
        return String.format(Locale.ROOT, "%.9g (0x%08x)", v, Float.floatToRawIntBits(v));
    }

    /** Taps {@code [0, length)} in full when there are few, else the taps around {@code at}. */
    private static String taps(float[] w, int at, int length) {
        int from = length <= PRINTED_TAPS ? 0 : Math.max(0, at - 3);
        int to = length <= PRINTED_TAPS ? length : Math.min(length, at + 4);
        StringBuilder text = new StringBuilder(from > 0 ? "[... " : "[");
        for (int i = from; i < to; i++) {
            text.append(i > from ? " " : "").append(String.format(Locale.ROOT, "%.8f", w[i]));
        }
        return text.append(to < length ? " ...] (taps " + from + ".." + (to - 1) + " of " + length + ")" : "]")
                .toString();
    }
}
