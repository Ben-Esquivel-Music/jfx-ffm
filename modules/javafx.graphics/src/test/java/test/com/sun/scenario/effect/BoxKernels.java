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
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.state.BoxRenderState;
import com.sun.scenario.effect.impl.state.LinearConvolveRenderState;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Box-blur kernels computed on the test side: the oracles that {@link BoxRenderStateWeightsTest} and the Decora golden
 * judge the weights of {@code BoxRenderState} with, the kernel {@code BoxRenderState.validateWeights} built before
 * its tap count was fixed, which the Decora golden recorded, and a {@code BoxRenderState} that hands the peers one of
 * these kernels instead of its own ({@link KernelBoxRenderState}).
 * <p>
 * A kernel here is the list of tap weights of one pass before {@code validateWeights} divides them by their sum. For
 * a pass size {@code size} it is built from a box of {@code klen = ceil(size) | 1} taps, whose two end taps are
 * trimmed to {@code 1 - (klen - size) / 2} when the size is not an odd integer, so that the box sums to the size. The
 * kernel is that trimmed box convolved with itself once for every further blur pass: every pass is trimmed, as
 * backlog story US-055 decided (option A). Before US-055 only the first box was trimmed and every further pass
 * convolved with an untrimmed box of {@code klen} ones, the kernel US-011 kept and pinned; it stays here as
 * {@link #FIRST_BOX_TRIMMED}, which the negative controls render with to show that a return to it is caught.
 */
final class BoxKernels {

    /** The kernel of one pass of {@code size} over {@code passes} blur passes, before its division by its sum. */
    @FunctionalInterface
    interface Kernel {
        double[] taps(float size, int passes);
    }

    /** The kernel a pass has to have, computed independently of {@code BoxRenderState}: {@link #oracleKernel}. */
    static final Kernel ORACLE = BoxKernels::oracleKernel;

    /**
     * The kernel {@code validateWeights} built from the fix of its tap count (backlog story US-011) until every pass
     * was trimmed (US-055): only the first box trimmed, {@link #trimmedFirstBox}. No production code builds it any
     * more; the negative controls render with it.
     */
    static final Kernel FIRST_BOX_TRIMMED = BoxKernels::trimmedFirstBox;

    /**
     * The kernel {@code validateWeights} built at commit 900c40e41a, which the golden recorded: {@link #preFixKernel}.
     */
    static final Kernel PRE_FIX = BoxKernels::preFixKernel;

    private BoxKernels() {
    }

    /**
     * The kernel a pass has to have: for an odd integer size the {@code passes}-fold convolution of a box of
     * {@code size} ones ({@link #repeatedBox}), for any other size the {@code passes}-fold convolution of the trimmed
     * box ({@link #repeatedTrimmedBox}).
     */
    static double[] oracleKernel(float size, int passes) {
        int odd = (int) size;
        if (odd == size && (odd & 1) == 1) {
            return Arrays.stream(repeatedBox(odd, passes)).asDoubleStream().toArray();
        }
        return repeatedTrimmedBox(size, passes);
    }

    /**
     * The {@code passes}-fold convolution of a box of {@code size} ones, as integer counts: the box convolved with a
     * box of {@code size} ones {@code passes - 1} times.
     */
    static long[] repeatedBox(int size, int passes) {
        long[] ones = new long[size];
        Arrays.fill(ones, 1);
        long[] kernel = ones;
        for (int p = 1; p < passes; p++) {
            kernel = convolve(kernel, ones);
        }
        return kernel;
    }

    /**
     * The box of one pass of {@code size}: {@code klen = ceil(size) | 1} taps, whose two end taps are
     * {@code 1 - (klen - size) / 2} and the others 1, so that it sums to the size.
     */
    static double[] trimmedBox(float size) {
        int klen = boxLength(size);
        double[] box = new double[klen];
        Arrays.fill(box, 1.0);
        box[0] = trimmedTap(size);
        box[klen - 1] = trimmedTap(size);
        return box;
    }

    /**
     * The kernel of US-011, before US-055 trimmed every pass: the trimmed box ({@link #trimmedBox}) convolved with an
     * untrimmed box of {@code klen} ones {@code passes - 1} times. Only the first box is trimmed.
     */
    static double[] trimmedFirstBox(float size, int passes) {
        double[] ones = new double[boxLength(size)];
        Arrays.fill(ones, 1.0);
        double[] kernel = trimmedBox(size);
        for (int p = 1; p < passes; p++) {
            kernel = convolve(kernel, ones);
        }
        return kernel;
    }

    /**
     * The trimmed box ({@link #trimmedBox}) convolved with itself {@code passes - 1} times: every pass trimmed, the
     * kernel of a size that is not an odd integer since US-055.
     */
    static double[] repeatedTrimmedBox(float size, int passes) {
        double[] box = trimmedBox(size);
        double[] kernel = box;
        for (int p = 1; p < passes; p++) {
            kernel = convolve(kernel, box);
        }
        return kernel;
    }

    /** The full convolution of {@code a} and {@code b}, by a plain nested loop. */
    static long[] convolve(long[] a, long[] b) {
        long[] c = new long[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                c[i + j] += a[i] * b[j];
            }
        }
        return c;
    }

    /** The full convolution of {@code a} and {@code b}, by a plain nested loop. */
    static double[] convolve(double[] a, double[] b) {
        double[] c = new double[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                c[i + j] += a[i] * b[j];
            }
        }
        return c;
    }

    /** The odd box length a pass size rounds up to: {@code ceil(size) | 1}, for a size of at least 1. */
    static int boxLength(float size) {
        return ((int) Math.ceil(Math.max(size, 1f))) | 1;
    }

    /** The weight of each end tap of the box: 1 less half of what the odd box length exceeds the size by. */
    static double trimmedTap(float size) {
        return 1.0 - (boxLength(size) - (double) Math.max(size, 1f)) / 2.0;
    }

    /**
     * The kernel {@code BoxRenderState.validateWeights} built at commit 900c40e41a, before its tap count was fixed:
     * its statements from {@code klen} to the end of the convolution, copied verbatim without their comments. Its
     * first loop, {@code while (i > klen)}, leaves {@code i == klen} to the second loop, which sums {@code klen + 1}
     * taps there. The Decora golden recorded this kernel. Only {@link DecoraCorpus#BOX_KERNEL_TAP_COUNT} renders with
     * it, and the controls of {@link DecoraJavaGoldenTest} pin it.
     */
    static double[] preFixKernel(float pSize, int blurPasses) {
        int klen = ((int) Math.ceil(pSize)) | 1;
        int totalklen = klen;
        for (int p = 1; p < blurPasses; p++) {
            totalklen += klen - 1;
        }
        double ik[] = new double[totalklen];
        for (int i = 0; i < klen; i++) {
            ik[i] = 1.0;
        }
        double excess = klen - pSize;
        if (excess > 0.0) {
            ik[0] = ik[klen-1] = 1.0 - excess * 0.5;
        }
        int filledklen = klen;
        for (int p = 1; p < blurPasses; p++) {
            filledklen += klen - 1;
            int i = filledklen - 1;
            while (i > klen) {
                double sum = ik[i];
                for (int k = 1; k < klen; k++) {
                    sum += ik[i-k];
                }
                ik[i--] = sum;
            }
            while (i > 0) {
                double sum = ik[i];
                for (int k = 0; k < i; k++) {
                    sum += ik[k];
                }
                ik[i--] = sum;
            }
        }
        return ik;
    }

    /**
     * The weights {@code validateWeights} hands the peers for the kernel {@code ik}: the kernel divided by its sum,
     * which a spread moves towards 1, each rounded to {@code float}, padded with zeros to the peer size of the kernel.
     * Its statements are those of {@code validateWeights}, whose normalisation the fix of the tap count left as it was.
     */
    static float[] passWeights(double[] ik, float passSpread) {
        double sum = 0.0;
        for (int i = 0; i < ik.length; i++) {
            sum += ik[i];
        }
        sum += (1.0 - sum) * passSpread;
        float[] weights = new float[LinearConvolveRenderState.getPeerSize(ik.length)];
        for (int i = 0; i < ik.length; i++) {
            weights[i] = (float) (ik[i] / sum);
        }
        return weights;
    }

    /** Box states for {@link DecoraBackend#withBoxStates} that hand the peers the weights of {@code kernel}. */
    static DecoraBackend.BoxStates states(Kernel kernel) {
        return (hsize, vsize, passes, spread, shadow, shadowColor) ->
                new KernelBoxRenderState(hsize, vsize, passes, spread, shadow, shadowColor, kernel);
    }

    /**
     * The production {@code BoxRenderState}, except for the weights it hands the peers, which come from a test-side
     * kernel through {@link #passWeights}. Everything else a peer asks for (the pass size, kernel size, sample vector,
     * bounds and shadow colour) is the production state's.
     * <p>
     * The state finds the size of each pass the way the production state does for the recipes it serves: under the
     * identity filter transform and over inputs that carry at most a translation, a pass size is the box size along
     * the pass's axis, unless the constructor clamps it, which the sizes allowed here never need; the spread applies
     * to pass 1 unless the vertical box is at most one pixel. It refuses any other input, and checks that its kernel
     * has the length {@code getPassKernelSize} gives, so it cannot silently hand the peers the kernel of another size.
     */
    static final class KernelBoxRenderState extends BoxRenderState {

        private final float hsize;
        private final float vsize;
        private final int passes;
        private final float spread;
        private final Kernel kernel;
        private int pass = -1;

        KernelBoxRenderState(float hsize, float vsize, int passes, float spread, boolean shadow, Color4f shadowColor,
                             Kernel kernel) {
            super(hsize, vsize, passes, spread, shadow, shadowColor, BaseTransform.IDENTITY_TRANSFORM);
            int largest = getMaxSizeForKernelSize(MAX_KERNEL_SIZE, passes);
            if (passes < 1 || hsize > largest || vsize > largest) {
                throw new IllegalArgumentException("box " + hsize + "x" + vsize + " over " + passes + " passes: only"
                        + " sizes up to " + largest + " and at least one pass");
            }
            this.hsize = hsize;
            this.vsize = vsize;
            this.passes = passes;
            this.spread = spread;
            this.kernel = kernel;
        }

        @Override
        public ImageData validatePassInput(ImageData src, int pass) {
            if (!src.getTransform().isTranslateOrIdentity()) {
                throw new IllegalStateException("pass " + pass + ": input transform " + src.getTransform()
                        + "; only inputs that carry at most a translation");
            }
            this.pass = pass;
            return super.validatePassInput(src, pass);
        }

        @Override
        public int getPassWeightsArrayLength() {
            return weights().length / 4;
        }

        @Override
        public FloatBuffer getPassWeights() {
            return FloatBuffer.wrap(weights());
        }

        private float[] weights() {
            if (pass < 0) {
                throw new IllegalStateException("no pass validated");
            }
            float size = Math.max(pass == 0 ? hsize : vsize, 1f);
            float passSpread = pass == (vsize > 1 ? 1 : 0) ? spread : 0f;
            double[] taps = kernel.taps(size, passes);
            if (taps.length != getPassKernelSize()) {
                throw new IllegalStateException("pass " + pass + " of size " + size + ": kernel of " + taps.length
                        + " taps, getPassKernelSize " + getPassKernelSize());
            }
            return passWeights(taps, passSpread);
        }
    }
}
