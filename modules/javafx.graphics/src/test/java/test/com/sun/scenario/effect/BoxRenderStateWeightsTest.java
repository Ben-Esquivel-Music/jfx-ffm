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
import static org.junit.jupiter.api.Assertions.fail;
import static test.com.sun.scenario.effect.BoxKernels.boxLength;
import static test.com.sun.scenario.effect.BoxKernels.repeatedBox;
import static test.com.sun.scenario.effect.BoxKernels.repeatedTrimmedBox;
import static test.com.sun.scenario.effect.BoxKernels.trimmedBox;
import static test.com.sun.scenario.effect.BoxKernels.trimmedFirstBox;
import static test.com.sun.scenario.effect.BoxKernels.trimmedTap;

/**
 * The kernel {@code BoxRenderState} hands the {@code LinearConvolve} and {@code LinearConvolveShadow} peers for a box
 * of two or more blur passes has to be the box convolved with itself once per further pass: symmetric about its centre
 * tap and divided by its own sum.
 * <p>
 * {@code BoxRenderState.validateWeights} builds the kernel of a pass in {@code ik[]}: a box of
 * {@code klen = ceil(size) | 1} ones, whose two end taps it trims to {@code 1 - (klen - size) / 2} when the size is
 * not an odd integer, convolved in place with that same box for every further pass, from the last tap backwards, and
 * finally divided by the sum of its taps. At commit 900c40e41a the first loop of that convolution,
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
 * <li>For a size that is not an odd integer, such as 4, 4.5 or 7.25, the kernel is the {@code passes}-fold
 * convolution of the trimmed box divided by its sum ({@link BoxKernels#repeatedTrimmedBox}: a plain nested-loop
 * convolution).</li>
 * </ul>
 * <p>
 * <b>The kernel of a size that is not an odd integer</b> trims every pass: it is the box of {@code klen} taps with its
 * two end taps trimmed to {@code 1 - (klen - size) / 2}, which sums to the size, convolved with itself
 * {@code passes - 1} times and divided by its sum. For a size of 4 over two passes that is {@code [0.5 1 1 1 0.5]}
 * convolved with itself, {@code [0.25 1 2 3 3.5 3 2 1 0.25] / 16}. Backlog story US-011, which fixed the tap count,
 * kept and pinned the kernel {@code validateWeights} built then, which trimmed the first box only and convolved it with
 * {@code passes - 1} untrimmed boxes of {@code klen} ones: {@code [0.5 1.5 2.5 3.5 4 3.5 2.5 1.5 0.5] / 20} for 4 over
 * two passes, whose later passes blur as boxes of {@code klen}, up to 2 pixels wider than the size. Backlog story
 * US-055 decided to trim every pass (its option A) and changed this oracle deliberately, from
 * {@link BoxKernels#trimmedFirstBox} to {@link BoxKernels#repeatedTrimmedBox}: the test that pinned the old kernel,
 * {@code nonOddSizeTrimsTheFirstBoxOnly}, became {@link #nonOddSizeTrimsEveryPass}. The old kernel differs from the
 * new one on every pass of two or more passes whose size is not an odd integer (its end tap is the trimmed tap
 * {@code t}, the new one's {@code t^passes}), so that test fails every multi-pass case against it. An odd size trims
 * nothing, and its kernel did not change.
 * <p>
 * <b>Tolerances.</b> The rule was fixed before the change was run, and {@link #onlyOnePassIsNotProvablyExact} pins
 * where it applies. A pass is compared bitwise, with the oracle and with its own mirror image, when a count of bits
 * ({@link #provablyExact}) proves every value {@code validateWeights} and the oracles compute before the final division
 * exact in {@code double}; any other pass is compared within one {@code float} ulp of the expected weight
 * ({@code Math.ulp}). The count: a pass size is a {@code float} of at least 1, so {@code klen - size} is exact (in
 * {@code float} too, where {@code validateWeights} subtracts: a multiple of the size's ulp below 2) and the trimmed tap
 * {@code t = 1 - (klen - size) / 2} is a multiple of {@code 2^-f}, where {@code f}, the number of fraction bits of
 * {@code t}, is at most 24, and 0 for an odd integer size ({@code t = 1}). Every tap of the {@code passes}-fold
 * convolution, every product of a tap with a box tap and every partial sum, in production and in the oracles, is a
 * sum of integer multiples of products of at most {@code passes} trimmed taps, so a multiple of
 * {@code 2^(-passes * f)}; all are non-negative and at most the kernel's sum {@code size^passes}, below {@code 2^b},
 * where {@code b} is the bit length of {@code klen^passes}. Such a value fits the 53-bit significand when
 * {@code passes * f + b <= 53}, whatever the order of the sums. Then production and the oracles divide the same
 * {@code double} values by the same sum and round the same quotient to {@code float}, and equal mirror taps round to
 * equal weights. Per case:
 * <ul>
 * <li>odd sizes: {@code f = 0}, {@code b <= 17} (79,507 for three passes of 43, the largest);</li>
 * <li>4 and 6: {@code t = 0.5}, {@code f = 1}; 4.5, 2.5 and 42.5: {@code t = 0.75}, {@code f = 2}; 7.25:
 * {@code t = 0.125}, {@code f = 3}. The largest count is 42.5 over three passes, {@code 3 * 2 + 17 = 23} bits;</li>
 * <li>7.3, whose {@code float} 7.30000019... fills its 24-bit significand, has {@code t = 0x4CCCDp-21}, an odd
 * numerator over {@code 2^21}, so {@code f = 21}. Over two passes the count is {@code 42 + 7 = 49} bits: exact, and
 * compared bitwise. Over three passes it is {@code 63 + 10 = 73} bits ({@code t^3} alone needs 55), so values round;
 * production sums each window from its last tap backwards and the oracle in the order of its nested loop, and a mirror
 * tap of production sums the same terms as its tap in the opposite order, so they may round differently. This pass,
 * pass 1 of {@code h=42.5 v=7.3 passes=3}, is the only one compared within one ulp, with the oracle and with its
 * mirror image.</li>
 * </ul>
 * The ulp bound holds there: each tap is a sum of at most 9 non-negative products, and the normalising sum one of 25
 * non-negative taps, each operation rounding by at most {@code 2^-53} relative, so the {@code double} quotients of
 * production and of the oracle are each within {@code 2^-46} of the exact weight, relatively, far less than the
 * {@code 2^-24} relative gap between neighbouring {@code float}s: they round to the same or to neighbouring
 * {@code float}s, at most {@code Math.ulp} of either apart.
 * <p>
 * The weights' sum may differ from 1 by the rounding of each weight to {@code float}, at most {@code 2^-24} of the
 * weight, so at most {@code 2^-24} in all, plus, on the one inexact pass, the {@code 2^-46} relative error of each
 * weight before that rounding, plus the {@code double} rounding of the test's own sum, at most {@code 127 * 2^-53};
 * {@link #SUM_TOLERANCE}, {@code 2^-23}, bounds that with a margin of almost two. Before the fix of the tap count the
 * weights summed to 1 as well, as the kernel was divided by its own, too large, sum: the sum guards the normalisation
 * and the padding, and the symmetry and the oracles find the extra tap.
 * <p>
 * The odd sizes are 3 and 15 over two passes, 3 and 9 over three passes, and the largest box
 * {@code BoxRenderState.getMaxSizeForKernelSize} allows for two and for three passes, whose kernels are the longest the
 * peers take, with 25 over two passes and 5 over three as in the multi-pass box rows of the Decora golden. The other
 * sizes are 4 and 4.5, and 2.5 and 7.25, over two and three passes; 4 and 6 over three passes, as in the golden's box
 * rows with a spread; half a pixel below the largest box for three passes with 7.3, which unlike the others is no
 * short binary fraction but fills the 24-bit significand of its {@code float}; and 7.3 with 2.5 over two passes, where
 * that {@code float} is still exact (added with US-055, two more tests). The single-pass controls, 5 and 9, the
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
                new Case(maxSize(3) - 0.5f, 7.3f, 3),
                new Case(7.3f, 2.5f, 2));
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
                    "the %d-fold convolution of a box of %d ones / %d^%d", c.passes(), size, size, c.passes()),
                    provablyExact(size, c.passes())));
        }
        assertAll(checks);
    }

    /**
     * For a size that is not an odd integer the weights are the trimmed box convolved with itself
     * {@code passes - 1} times and divided by its sum: every pass is trimmed (backlog story US-055, see the class
     * description).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonOddSizeCases")
    void nonOddSizeTrimsEveryPass(Case c) {
        List<Executable> checks = new ArrayList<>();
        for (Pass p : validateBothPasses(c)) {
            float size = p.size();
            assertFalse(size == (int) size && (((int) size) & 1) == 1, () -> p.where() + ": an odd integer size");
            int klen = boxLength(size);
            float[] expected = normalised(repeatedTrimmedBox(size, c.passes()));
            checks.add(() -> assertWeights(p, expected, String.format(Locale.ROOT,
                    "the %d-fold convolution of a box of %d taps with end taps %s, / its sum", c.passes(), klen,
                    trimmedTap(size)), provablyExact(size, c.passes())));
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
            checks.add(() -> assertSymmetric(p, provablyExact(p.size(), c.passes())));
            checks.add(() -> assertSumsToOne(p));
        }
        assertAll(checks);
    }

    /**
     * The oracles reproduce worked examples written out by hand, agree with each other where nothing is trimmed, and
     * the kernel of every pass trimmed is not the kernel of US-011, which trimmed the first box only.
     */
    @Test
    void oraclesReproduceTheWorkedExamples() {
        assertArrayEquals(new long[] {1, 2, 3, 2, 1}, repeatedBox(3, 2), "3 ones over 2 passes");
        assertArrayEquals(new long[] {1, 3, 6, 7, 6, 3, 1}, repeatedBox(3, 3), "3 ones over 3 passes");
        assertArrayEquals(new long[] {1, 1, 1, 1, 1}, repeatedBox(5, 1), "5 ones over 1 pass");
        assertArrayEquals(new double[] {0.75, 1, 1, 1, 0.75}, trimmedBox(4.5f), "the box of size 4.5");
        assertArrayEquals(new double[] {0.75, 1, 1, 1, 0.75}, repeatedTrimmedBox(4.5f, 1), "size 4.5 over 1 pass");
        assertArrayEquals(new double[] {0.25, 1, 2, 3, 3.5, 3, 2, 1, 0.25}, repeatedTrimmedBox(4, 2),
                "size 4 over 2 passes: [0.5 1 1 1 0.5] convolved with itself, sum 16");
        assertArrayEquals(new double[] {0.125, 0.75, 2.25, 4.75, 7.875, 10.5, 11.5, 10.5, 7.875, 4.75, 2.25, 0.75,
                0.125}, repeatedTrimmedBox(4, 3), "size 4 over 3 passes: [0.25 1 2 3 3.5 3 2 1 0.25] convolved with"
                + " [0.5 1 1 1 0.5], sum 64");
        assertArrayEquals(new double[] {0.421875, 1.6875, 3.515625, 4.375, 3.515625, 1.6875, 0.421875},
                repeatedTrimmedBox(2.5f, 3), "size 2.5 over 3 passes: [0.75 1 0.75] convolved with itself twice,"
                + " sum 15.625");
        assertArrayEquals(new double[] {0.015625, 0.25, 1.25, 2.25, 3.25, 4.25, 5.25, 6.25, 7.03125, 6.25, 5.25,
                4.25, 3.25, 2.25, 1.25, 0.25, 0.015625}, repeatedTrimmedBox(7.25f, 2),
                "size 7.25 over 2 passes: 9 taps with end taps 0.125 convolved with itself, sum 52.5625");
        double[] firstBoxTrimmed = {0.5, 1.5, 2.5, 3.5, 4, 3.5, 2.5, 1.5, 0.5};
        assertArrayEquals(firstBoxTrimmed, trimmedFirstBox(4, 2),
                "US-011's kernel of size 4 over 2 passes: [0.5 1 1 1 0.5] convolved with [1 1 1 1 1]");
        assertArrayEquals(new double[] {0.125, 1.125, 2.125, 3.125, 4.125, 5.125, 6.125, 7.125, 7.25, 7.125, 6.125,
                5.125, 4.125, 3.125, 2.125, 1.125, 0.125}, trimmedFirstBox(7.25f, 2),
                "US-011's kernel of size 7.25 over 2 passes: 9 taps with end taps 0.125, convolved with 9 ones");
        for (float size : new float[] {4, 4.5f, 2.5f, 6, 7.25f, 42.5f, 7.3f}) {
            assertArrayEquals(trimmedFirstBox(size, 1), repeatedTrimmedBox(size, 1), "size " + size
                    + " over 1 pass: the kernels of US-011 and US-055 disagree where nothing is convolved");
            for (int passes = 2; passes <= 3; passes++) {
                assertFalse(Arrays.equals(trimmedFirstBox(size, passes), repeatedTrimmedBox(size, passes)), "size "
                        + size + " over " + passes + " passes: the kernel of every pass trimmed is US-011's");
            }
        }
        for (float size : new float[] {4, 4.5f, 2.5f, 7.25f}) {
            double power = 1;
            for (int passes = 1; passes <= 3; passes++) {
                power *= size;
                double sum = 0;
                for (double tap : repeatedTrimmedBox(size, passes)) {
                    sum += tap;
                }
                assertEquals(power, sum, "size " + size + " over " + passes + " passes: the sum of the kernel,"
                        + " size^passes");
            }
        }
        for (int passes = 1; passes <= 3; passes++) {
            for (int size : new int[] {3, 5, 9}) {
                double[] counts = Arrays.stream(repeatedBox(size, passes)).asDoubleStream().toArray();
                assertArrayEquals(counts, repeatedTrimmedBox(size, passes), "size " + size + " over " + passes
                        + " passes: the oracles disagree where nothing is trimmed");
                assertArrayEquals(counts, trimmedFirstBox(size, passes), "size " + size + " over " + passes
                        + " passes: US-011's kernel is not the repeated box where nothing is trimmed");
            }
        }
    }

    /**
     * The count of bits of the class description: the trimmed taps it names, and every pass of every case is proven
     * exact in {@code double}, and so compared bitwise, except pass 1 of {@code h=42.5 v=7.3 passes=3}, which is
     * compared within one {@code float} ulp.
     */
    @Test
    void onlyOnePassIsNotProvablyExact() {
        assertEquals(1.0, trimmedTap(9), "an odd size trims nothing");
        assertEquals(0.5, trimmedTap(4), "size 4");
        assertEquals(0.75, trimmedTap(42.5f), "size 42.5");
        assertEquals(0.125, trimmedTap(7.25f), "size 7.25");
        assertEquals(0x4CCCDp-21, trimmedTap(7.3f), "size 7.3f: 1 - (9 - 7.30000019...) / 2, an odd numerator");
        assertTrue(provablyExact(7.3f, 2), "7.3 over 2 passes: 2 * 21 + 7 = 49 bits");
        assertFalse(provablyExact(7.3f, 3), "7.3 over 3 passes: 3 * 21 + 10 = 73 bits");
        assertTrue(provablyExact(maxSize(3), 3), "43 over 3 passes: 0 + 17 bits");
        assertTrue(provablyExact(maxSize(3) - 0.5f, 3), "42.5 over 3 passes: 3 * 2 + 17 = 23 bits");
        List<String> inexact = new ArrayList<>();
        Stream.concat(oddSizeCases(), nonOddSizeCases()).forEach(c -> {
            for (int pass = 0; pass < 2; pass++) {
                if (!provablyExact(c.size(pass), c.passes())) {
                    inexact.add(c + ", pass " + pass);
                }
            }
        });
        assertEquals(List.of(new Case(maxSize(3) - 0.5f, 7.3f, 3) + ", pass 1"), inexact,
                "the passes compared within one ulp");
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
     * The first {@code getPassKernelSize()} weights of the pass are {@code expected}: bitwise, or, where
     * {@code bitwise} is false because the class description cannot prove the pass exact in {@code double}, each
     * within one {@code float} ulp of the expected weight. A kernel divided by a wrong sum differs from the oracle on
     * every tap, so the message names the tap that differs most, relatively, as well as the first, and prints a long
     * kernel around the tap that differs most.
     */
    private static void assertWeights(Pass p, float[] expected, String oracle, boolean bitwise) {
        assertEquals(expected.length, p.kernelSize(), () -> p.where() + ": getPassKernelSize, expected the "
                + expected.length + " taps of " + oracle);
        assertTrue(p.weights().length >= expected.length, () -> p.where() + ": " + p.weights().length
                + " weights in the buffer for a kernel of " + expected.length + " taps");
        float[] w = p.weights();
        for (int i = 0; i < expected.length; i++) {
            if (differs(w[i], expected[i], bitwise)) {
                int most = 0;
                for (int k = 1; k < expected.length; k++) {
                    if (relativeDifference(w[k], expected[k]) > relativeDifference(w[most], expected[most])) {
                        most = k;
                    }
                }
                fail(String.format(Locale.ROOT,
                        "%s: weights differ from %s (%s) first at tap %d of %d: %s, expected %s; most at tap %d: %s,"
                        + " expected %s (%+.2f%%);%n  weights  %s%n  expected %s%n", p.where(), oracle,
                        bitwise ? "bitwise" : "within 1 ulp", i, expected.length, exact(w[i]), exact(expected[i]),
                        most, exact(w[most]), exact(expected[most]),
                        100.0 * (w[most] - (double) expected[most]) / expected[most], taps(w, most, expected.length),
                        taps(expected, most, expected.length)));
            }
        }
    }

    /**
     * Whether {@code actual} is not {@code expected}: bitwise, or, unless {@code bitwise}, further from it than
     * {@code Math.ulp(expected)}, the tolerance the class description fixes for a pass it cannot prove exact.
     */
    private static boolean differs(float actual, float expected, boolean bitwise) {
        return bitwise ? Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(expected)
                : !(Math.abs(actual - (double) expected) <= Math.ulp(expected));
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

    /**
     * Each weight equals its mirror image about the centre tap: bitwise, or, unless {@code bitwise}, within
     * {@code Math.ulp} of the mirror weight (see the class description).
     */
    private static void assertSymmetric(Pass p, boolean bitwise) {
        int kernelSize = p.kernelSize();
        float[] w = p.weights();
        for (int i = 0; i < kernelSize / 2; i++) {
            int mirror = kernelSize - 1 - i;
            if (differs(w[i], w[mirror], bitwise)) {
                fail(String.format(Locale.ROOT, "%s: not symmetric (%s) about the centre tap %d of %d: tap %d is %s,"
                        + " its mirror tap %d is %s;%n  weights %s%n", p.where(), bitwise ? "bitwise" : "within 1 ulp",
                        kernelSize / 2, kernelSize, i, exact(w[i]), mirror, exact(w[mirror]), taps(w, i, kernelSize)));
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

    /**
     * Whether the count of bits of the class description proves every value {@code validateWeights} and the oracles
     * compute for a pass of {@code size} over {@code passes} passes before the final division exact in
     * {@code double}: {@code passes * f + b <= 53}, where {@code f} is the number of fraction bits of the trimmed tap
     * and {@code b} the bit length of {@code klen^passes}.
     */
    static boolean provablyExact(float size, int passes) {
        double tap = trimmedTap(size);
        int fractionBits = 0;
        while (Math.scalb(tap, fractionBits) != Math.rint(Math.scalb(tap, fractionBits))) {
            fractionBits++;
        }
        long power = 1;
        for (int p = 0; p < passes; p++) {
            power *= boxLength(size);
        }
        int integerBits = Long.SIZE - Long.numberOfLeadingZeros(power);
        return passes * fractionBits + integerBits <= 53;
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
