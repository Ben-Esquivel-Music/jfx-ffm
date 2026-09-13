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

import com.sun.javafx.geom.Point2D;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.Rectangle;
import com.sun.scenario.effect.Blend;
import com.sun.scenario.effect.Brightpass;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.ColorAdjust;
import com.sun.scenario.effect.DisplacementMap;
import com.sun.scenario.effect.Flood;
import com.sun.scenario.effect.FloatMap;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.InvertMask;
import com.sun.scenario.effect.PerspectiveTransform;
import com.sun.scenario.effect.PhongLighting;
import com.sun.scenario.effect.SepiaTone;
import com.sun.scenario.effect.light.DistantLight;
import com.sun.scenario.effect.light.Light;
import com.sun.scenario.effect.light.PointLight;
import com.sun.scenario.effect.light.SpotLight;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.javafx.test.ParityGate;
import test.com.sun.scenario.effect.DecoraBackend.Image;
import test.com.sun.scenario.effect.DecoraBackend.Result;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pixel parity of the native {@code decora_sse} software peers against the pure-Java software peers.
 * <p>
 * Both backends are generated from the same JSL sources (the generated peers) or maintained as
 * textual twins (BoxBlur, BoxShadow, LinearConvolve, LinearConvolveShadow), so they are expected to
 * render the same pixels. Every peer that has an SSE implementation is rendered through both backends
 * in one JVM ({@link DecoraBackend}) over deterministic inputs and several parameter sets, and the
 * outputs are compared per channel.
 * <p>
 * Parity gate ({@code jni-to-ffm-migration} skill, section 1.1). The bounds were derived from the
 * source of the two backends before the first run; the two places where the first run exceeded them
 * were diagnosed and are covered by their own tests below rather than by a widened bound:
 * <ul>
 * <li>{@code exact} (bound 0) for the integer-only loops: BoxBlur and BoxShadow with {@code spread == 0}.
 * Both sides are the same integer arithmetic ({@code kscale = 0x7fffffff / (size * 255)}, shifts).</li>
 * <li>{@code tolerance 1} (one 8-bit step per channel) for every float loop. The native library is
 * compiled with {@code /fp:fast} ({@code win.cmake}) and {@code -ffast-math} ({@code linux.cmake},
 * {@code mac.cmake}), so its float sums may be reassociated or contracted, while the Java loops are
 * IEEE-strict; and for the centered Gaussian passes the SSE peer runs {@code filterHV} while the Java
 * peer always runs {@code filterVector} (a hard-coded {@code if (count >= 0)} in
 * {@code JSWLinearConvolvePeer}), two arithmetically different but mathematically equal paths. Either
 * can move a truncated channel value by one.</li>
 * <li>{@code tolerance 3} for the Gaussian shadow. Its SSE {@code filterHV} rounds half-up
 * ({@code sum = -0.5f} start, {@code shadowRGBs[(int) sum + 1]}) while the Java {@code filterVector}
 * truncates ({@code (int) (shadowColor * sum)}): up to one step per pass, and pass 1 re-blurs pass 0's
 * already-rounded bytes with weights whose sum is {@code 1 + spread} (at most 2), so the bound is
 * {@code 1 + 2 = 3} for two passes with {@code spread <= 1}. Nothing but the rounding mode differs.</li>
 * </ul>
 * A difference above the bound fails the test: it is a real divergence between the two backends and a
 * finding for the JSL generator or one of the hand-written twins. The per-case numbers (max delta,
 * fraction of differing pixels) are printed as a table after the run so the actual distance between
 * the backends is on record, not only pass/fail.
 * <p>
 * The test fails, rather than skips, when {@code decora_sse} is reachable and cannot be loaded; it
 * skips only when this build has no javafx.graphics natives at all ({@link DecoraNatives}). Every recorded
 * comparison is counted, and a class that loaded both backends and compared nothing fails at the end
 * ({@link ParityGate}).
 */
public class DecoraSseJavaParityTest {

    private static final int EXACT = 0;
    private static final int ONE_STEP = 1;
    private static final int THREE_STEPS = 3;

    private static final int[][] SIZES = {{64, 48}, {257, 129}};

    private static final Color4f SHADOW_TINT = new Color4f(1f, 0.5f, 0.25f, 0.8f);

    private static DecoraBackend sse;
    private static DecoraBackend java;
    private static final List<Row> ROWS = new ArrayList<>();
    private static final ParityGate.Ledger LEDGER = ParityGate.ledger(DecoraSseJavaParityTest.class);

    /** One backend-independent rendering recipe. */
    record Case(String effect, String params, int bound, BiFunction<DecoraBackend, Inputs, Result> run) {
    }

    /** The source images of one backend for one size; the second image feeds two-input peers. */
    record Inputs(int width, int height, Image primary, Image secondary) {
    }

    /** One comparison, for the report. */
    record Row(String effect, String params, String size, int maxDelta, long differing, long total, int bound) {
    }

    @BeforeAll
    static void backends() {
        sse = DecoraBackend.sse();
        java = DecoraBackend.java();
        // Both backends loaded: every test of this class records comparisons, so from here on a class that
        // recorded none is a bug, not a skip.
        LEDGER.oracleAvailable();
    }

    /** Prints the per-case table, then insists that at least one comparison was recorded. */
    @AfterAll
    static void report() {
        StringBuilder text = new StringBuilder(4096);
        text.append(String.format(Locale.ROOT, "%n%-34s %-44s %-8s %5s %10s %5s %s%n", "effect", "params", "size",
                "maxD", "diff%", "bound", "verdict"));
        Map<String, int[]> perEffect = new TreeMap<>();
        for (Row row : ROWS) {
            double pct = row.total() == 0 ? 0.0 : 100.0 * row.differing() / row.total();
            String verdict = row.maxDelta() == 0 ? "exact"
                    : row.maxDelta() <= row.bound() ? "within bound" : "EXCEEDS BOUND";
            text.append(String.format(Locale.ROOT, "%-34s %-44s %-8s %5d %9.3f%% %5d %s%n", row.effect(),
                    row.params(), row.size(), row.maxDelta(), pct, row.bound(), verdict));
            int[] agg = perEffect.computeIfAbsent(row.effect(), k -> new int[] {0, 0, row.bound()});
            agg[0] = Math.max(agg[0], row.maxDelta());
            agg[1] = Math.max(agg[1], (int) Math.ceil(pct * 1000));
        }
        text.append(String.format(Locale.ROOT, "%n%-34s %5s %10s %5s %s%n", "effect (summary)", "maxD",
                "worst diff%", "bound", "gate"));
        for (Map.Entry<String, int[]> e : perEffect.entrySet()) {
            int[] agg = e.getValue();
            String gate = agg[0] == 0 ? "PARITY: exact" : agg[0] <= agg[2] ? "PARITY: tolerance " + agg[2]
                    : "PARITY: EXCEEDED";
            text.append(String.format(Locale.ROOT, "%-34s %5d %9.3f%% %5d %s%n", e.getKey(), agg[0],
                    agg[1] / 1000.0, agg[2], gate));
        }
        System.out.println(text);
        LEDGER.assertOracleRan();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void sseAndJavaPeersRenderTheSamePixels(String label, Case c, int width, int height) {
        Result a = c.run().apply(sse, inputs(width, height));
        Result b = c.run().apply(java, inputs(width, height));
        String size = width + "x" + height;
        assertSameBounds(label, a, b);
        int maxDelta = maxDelta(a, b);
        long differing = differing(a, b);
        record(c.effect(), c.params(), size, maxDelta, differing, a.pixels().length, c.bound());
        if (maxDelta > c.bound()) {
            fail(label + " [" + size + "]: max per-channel delta " + maxDelta + " exceeds the bound " + c.bound()
                    + " (" + differing + " of " + a.pixels().length + " pixels differ; first difference at "
                    + firstDifference(a, b) + "; SSE peer " + sse.peerClassName(c.effect().split("/")[0])
                    + ", Java peer " + java.peerClassName(c.effect().split("/")[0]) + ")");
        }
        assertTrue(maxDelta <= c.bound());
    }

    /**
     * Finding 1: with a positive contrast, {@code ColorAdjust} can push the largest colour channel to
     * exactly {@code 0.0}, and the JSL {@code rgb_to_hsb} then computes {@code s = (cmax - cmin) / cmax}
     * as {@code +Infinity}; with a positive saturation adjustment {@code hsb.y += (1 - s) * sat} turns
     * that into {@code NaN}. The IEEE-strict Java peer carries the NaN into the output ({@code (int) NaN}
     * is 0, so two channels go black); the {@code /fp:fast} native peer may fold or drop the operations
     * and produce a different colour. Every pixel on which the two backends disagree for the extreme
     * parameter set has to be one of those division-by-zero pixels - anything else would be a second,
     * unexplained divergence.
     */
    @Test
    void colorAdjustFullContrastDivergesOnlyWhereTheShaderDividesByZero() {
        for (int[] size : SIZES) {
            int width = size[0];
            int height = size[1];
            int[] source = pattern(width, height, 0x9E3779B9L);
            BiFunction<DecoraBackend, Inputs, Result> run = colorAdjust(1f, 1f, 1f, 1f);
            Result a = run.apply(sse, inputs(width, height));
            Result b = run.apply(java, inputs(width, height));
            assertSameBounds("ColorAdjust hue=1 sat=1 bri=1 con=1", a, b);
            int explained = 0;
            List<String> unexplained = new ArrayList<>();
            for (int i = 0; i < source.length; i++) {
                if (a.pixels()[i] == b.pixels()[i]) {
                    continue;
                }
                if (fullContrastZeroesTheMaxChannel(source[i])) {
                    explained++;
                } else if (channelDelta(a.pixels()[i], b.pixels()[i]) > ONE_STEP) {
                    unexplained.add(String.format(Locale.ROOT, "(%d,%d) src=%08x sse=%08x java=%08x", i % width,
                            i / width, source[i], a.pixels()[i], b.pixels()[i]));
                }
            }
            record("ColorAdjust/full-contrast (JSL 0/0)", "hue=1 sat=1 bri=1 con=1, " + explained
                    + " pixels with cmax==0", width + "x" + height, maxDelta(a, b), differing(a, b), source.length,
                    ONE_STEP);
            assertTrue(unexplained.isEmpty(), () -> "ColorAdjust differences not explained by the 0/0 in"
                    + " rgb_to_hsb: " + unexplained);
        }
    }

    /**
     * Finding 2: when the output clip cuts into the raw blur result, {@code SSELinearConvolvePeer} sizes
     * pass 0 with {@code getPassResultBounds(inputBounds, outputClip)}, which grows the clip by the
     * rows pass 1 will need, while {@code JSWLinearConvolvePeer} intersects the raw bounds with the
     * unmodified clip. Pass 1 of the Java peer therefore sees transparent rows where pass 0 should have
     * produced data, and the pixels within {@code ceil(radius)} rows of the clip's top and bottom edges
     * come out darker than in an unclipped render. The native peer is self-consistent: clipping does
     * not change the pixels inside the clip.
     * <p>
     * The test pins the diagnosis: every pixel on which the Java peer's clipped render disagrees with
     * its own unclipped render has to lie within {@code ceil(radius)} rows of a clip edge. It passes as
     * well once the Java peer is fixed (no such pixels), and records the current distance to SSE.
     */
    @Test
    void javaLinearConvolvePeerDropsPassOnePaddingWhenClipped() {
        for (float radius : new float[] {5f, 25f}) {
            for (int[] size : SIZES) {
                int width = size[0];
                int height = size[1];
                Rectangle clip = new Rectangle(10, 8, width - 20, height - 16);
                int pad = (int) Math.ceil(radius);
                String params = String.format(Locale.ROOT, "radius=%.0f clip=%s", radius, clip);
                Result sseClipped = sse.gaussian(sse.data(inputs(width, height).primary(), 0, 0), radius, radius,
                        0f, false, null, clip);
                Result sseFull = sse.gaussian(sse.data(inputs(width, height).primary(), 0, 0), radius, radius,
                        0f, false, null, null);
                Result javaClipped = java.gaussian(java.data(inputs(width, height).primary(), 0, 0), radius,
                        radius, 0f, false, null, clip);
                Result javaFull = java.gaussian(java.data(inputs(width, height).primary(), 0, 0), radius, radius,
                        0f, false, null, null);
                assertSameBounds("clipped " + params, sseClipped, javaClipped);
                Result sseCropped = crop(sseFull, sseClipped);
                Result javaCropped = crop(javaFull, javaClipped);
                int sseSelf = maxDelta(sseClipped, sseCropped);
                assertTrue(sseSelf <= ONE_STEP, () -> "SSE clipped render differs from its unclipped render by "
                        + sseSelf + " for " + params);
                List<String> outside = new ArrayList<>();
                int[] pc = javaClipped.pixels();
                int[] pf = javaCropped.pixels();
                for (int i = 0; i < pc.length; i++) {
                    int row = i / javaClipped.width();
                    if (channelDelta(pc[i], pf[i]) > ONE_STEP && row >= pad && row < javaClipped.height() - pad) {
                        outside.add("(" + (i % javaClipped.width()) + "," + row + ")");
                    }
                }
                record("LinearConvolve/gaussian clipped (JSW pass-0 clip)", params, width + "x" + height,
                        maxDelta(sseClipped, javaClipped), differing(sseClipped, javaClipped), pc.length, ONE_STEP);
                assertTrue(outside.isEmpty(), () -> "Java clipped render differs from its unclipped render"
                        + " outside the " + pad + " rows next to the clip edges, which the pass-0 clip growth"
                        + " does not explain: " + outside + " for " + params);
            }
        }
    }

    /**
     * The Java {@code filterHV} loops (unreachable in production because of the hard-coded
     * {@code if (count >= 0)} in {@code JSWLinearConvolvePeer.filter}) against the native
     * {@code filterHV} loops the SSE peers actually run for centered passes, called directly with the
     * same weights and pixels. Exact agreement here means re-enabling the Java loops would close the
     * Gaussian blur and shadow to {@code PARITY: exact}.
     */
    @Test
    void javaFilterHvLoopsMatchSseFilterHvLoops() {
        int radius = 3;
        int count = 2 * radius + 1;
        float[] weights = duplicatedGaussianWeights(radius, 1f);
        float[] spreadWeights = duplicatedGaussianWeights(radius, 1.5f);
        for (int[] size : SIZES) {
            int width = size[0];
            int height = size[1];
            Image source = Image.of(width, height, pattern(width, height, 0x9E3779B9L));
            int[] src = source.getPixelArray();
            int scan = source.getScanlineStride();
            // Blur, horizontal orientation: dst is count - 1 columns wider than src.
            int dstw = width + count - 1;
            Image sseDst = new Image(dstw, height);
            Image javaDst = new Image(dstw, height);
            sse.filterHV(sseDst.getPixelArray(), dstw, height, 1, sseDst.getScanlineStride(), src, width, height, 1,
                    scan, weights);
            java.filterHV(javaDst.getPixelArray(), dstw, height, 1, javaDst.getScanlineStride(), src, width, height,
                    1, scan, weights);
            recordArrays("filterHV probe/blur", "horizontal radius=3", width + "x" + height, sseDst, javaDst);
            // Blur, vertical orientation: dst is count - 1 rows taller than src.
            int dsth = height + count - 1;
            sseDst = new Image(width, dsth);
            javaDst = new Image(width, dsth);
            sse.filterHV(sseDst.getPixelArray(), dsth, width, sseDst.getScanlineStride(), 1, src, height, width,
                    scan, 1, weights);
            java.filterHV(javaDst.getPixelArray(), dsth, width, javaDst.getScanlineStride(), 1, src, height, width,
                    scan, 1, weights);
            recordArrays("filterHV probe/blur", "vertical radius=3", width + "x" + height, sseDst, javaDst);
            // Shadow, horizontal, tinted, with spread-scaled weights.
            sseDst = new Image(dstw, height);
            javaDst = new Image(dstw, height);
            sse.filterHVShadow(sseDst.getPixelArray(), dstw, height, 1, sseDst.getScanlineStride(), src, width,
                    height, 1, scan, spreadWeights, SHADOW_TINT);
            java.filterHVShadow(javaDst.getPixelArray(), dstw, height, 1, javaDst.getScanlineStride(), src, width,
                    height, 1, scan, spreadWeights, SHADOW_TINT);
            recordArrays("filterHV probe/shadow", "horizontal radius=3 spread=0.5 tinted", width + "x" + height,
                    sseDst, javaDst);
        }
    }

    private static void recordArrays(String effect, String params, String size, Image a, Image b) {
        int[] pa = a.getPixelArray();
        int[] pb = b.getPixelArray();
        int maxDelta = 0;
        long differing = 0;
        for (int i = 0; i < pa.length; i++) {
            int d = channelDelta(pa[i], pb[i]);
            if (d != 0) {
                differing++;
                maxDelta = Math.max(maxDelta, d);
            }
        }
        record(effect, params, size, maxDelta, differing, pa.length, ONE_STEP);
        if (maxDelta > ONE_STEP) {
            assertArrayEquals(pa, pb, effect + " " + params + " " + size);
        }
    }

    /** Gaussian weights summing to {@code scale}, laid out twice in a row as the peers do for {@code filterHV}. */
    static float[] duplicatedGaussianWeights(int radius, float scale) {
        int count = 2 * radius + 1;
        float[] w = new float[count];
        float sum = 0f;
        for (int i = 0; i < count; i++) {
            w[i] = (float) Math.exp(-(i - radius) * (i - radius) / (2.0 * radius * radius / 9.0));
            sum += w[i];
        }
        float[] doubled = new float[count * 2];
        for (int i = 0; i < count; i++) {
            doubled[i] = w[i] * scale / sum;
            doubled[count + i] = doubled[i];
        }
        return doubled;
    }

    static Stream<Arguments> cases() {
        List<Arguments> args = new ArrayList<>();
        for (Case c : allCases()) {
            for (int[] size : SIZES) {
                args.add(Arguments.of(c.effect() + " " + c.params() + " " + size[0] + "x" + size[1], c, size[0],
                        size[1]));
            }
        }
        return args.stream();
    }

    static List<Case> allCases() {
        List<Case> cases = new ArrayList<>();
        boxCases(cases);
        gaussianCases(cases);
        generatedCases(cases);
        return cases;
    }

    private static void boxCases(List<Case> cases) {
        // (hsize, vsize, passes): no-op, odd, even, three passes, wide, one-dimensional, tall.
        float[][] sizes = {{0, 0, 1}, {3, 3, 1}, {4, 6, 1}, {5, 5, 3}, {25, 25, 2}, {51, 3, 1}, {1, 7, 3}};
        for (float[] s : sizes) {
            String params = String.format(Locale.ROOT, "h=%.0f v=%.0f passes=%.0f", s[0], s[1], s[2]);
            cases.add(new Case("BoxBlur", params, EXACT,
                    (b, in) -> b.box(b.data(in.primary(), 0, 0), s[0], s[1], (int) s[2], 0f, false, null, null)));
            cases.add(new Case("BoxShadow", params + " black", EXACT,
                    (b, in) -> b.box(b.data(in.primary(), 0, 0), s[0], s[1], (int) s[2], 0f, true, Color4f.BLACK,
                            null)));
            cases.add(new Case("BoxShadow", params + " tinted", EXACT,
                    (b, in) -> b.box(b.data(in.primary(), 0, 0), s[0], s[1], (int) s[2], 0f, true, SHADOW_TINT,
                            null)));
        }
        // Non-zero origin of the source in filter space.
        cases.add(new Case("BoxBlur", "h=5 v=5 passes=1 origin=-7,3", EXACT,
                (b, in) -> b.box(b.data(in.primary(), -7, 3), 5, 5, 1, 0f, false, null, null)));
        // spread != 0 makes BoxRenderState choose the LinearConvolveShadow peer (GENERAL_VECTOR pass).
        float[][] spreadSizes = {{5, 5, 1}, {25, 25, 2}, {4, 6, 3}};
        for (float[] s : spreadSizes) {
            for (float spread : new float[] {0.3f, 1f}) {
                String params = String.format(Locale.ROOT, "box h=%.0f v=%.0f passes=%.0f spread=%.1f", s[0], s[1],
                        s[2], spread);
                cases.add(new Case("LinearConvolveShadow/box", params + " black", ONE_STEP,
                        (b, in) -> b.box(b.data(in.primary(), 0, 0), s[0], s[1], (int) s[2], spread, true,
                                Color4f.BLACK, null)));
                cases.add(new Case("LinearConvolveShadow/box", params + " tinted", ONE_STEP,
                        (b, in) -> b.box(b.data(in.primary(), 0, 0), s[0], s[1], (int) s[2], spread, true,
                                SHADOW_TINT, null)));
            }
        }
    }

    private static void gaussianCases(List<Case> cases) {
        // Centered passes: SSE filterHV versus Java filterVector.
        for (float radius : new float[] {0f, 0.5f, 1f, 2.5f, 5f, 12.3f, 25f, 63f}) {
            String params = String.format(Locale.ROOT, "radius=%.1f centered", radius);
            cases.add(new Case("LinearConvolve/gaussian", params, ONE_STEP,
                    (b, in) -> b.gaussian(b.data(in.primary(), 0, 0), radius, radius, 0f, false, null, null)));
        }
        cases.add(new Case("LinearConvolve/gaussian", "xradius=7 yradius=2 centered", ONE_STEP,
                (b, in) -> b.gaussian(b.data(in.primary(), 0, 0), 7f, 2f, 0f, false, null, null)));
        // A clip that leaves the raw result intact is honoured identically; a cutting clip is finding 2.
        cases.add(new Case("LinearConvolve/gaussian", "radius=5.0 enclosing clip", ONE_STEP,
                (b, in) -> b.gaussian(b.data(in.primary(), 0, 0), 5f, 5f, 0f, false, null,
                        new Rectangle(-100, -100, in.width() + 200, in.height() + 200))));
        // Shadows: SSE filterHV (round half-up) versus Java filterVector (truncation), two passes.
        for (float radius : new float[] {1f, 5f, 25f}) {
            for (float spread : new float[] {0f, 0.5f}) {
                String params = String.format(Locale.ROOT, "radius=%.1f spread=%.1f", radius, spread);
                cases.add(new Case("LinearConvolveShadow/gaussian", params + " black", THREE_STEPS,
                        (b, in) -> b.gaussian(b.data(in.primary(), 0, 0), radius, radius, spread, true,
                                Color4f.BLACK, null)));
                cases.add(new Case("LinearConvolveShadow/gaussian", params + " tinted", THREE_STEPS,
                        (b, in) -> b.gaussian(b.data(in.primary(), 0, 0), radius, radius, spread, true,
                                SHADOW_TINT, null)));
            }
        }
        // Directional (motion) kernels: GENERAL_VECTOR, filterVector on both sides.
        float[][] motions = {{5f, 30f}, {10f, 90f}, {25f, 0f}, {3.5f, 200f}};
        for (float[] m : motions) {
            double angle = Math.toRadians(m[1]);
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            String params = String.format(Locale.ROOT, "motion radius=%.1f angle=%.0f", m[0], m[1]);
            cases.add(new Case("LinearConvolve/motion", params, ONE_STEP,
                    (b, in) -> b.motion(b.data(in.primary(), 0, 0), m[0], dx, dy, null)));
        }
    }

    private static void generatedCases(List<Case> cases) {
        // hue, saturation, brightness, contrast in [-1, 1]; full positive contrast is finding 1.
        float[][] adjusts = {{0, 0, 0, 0}, {1, 1, 1, 0}, {-1, -1, -1, -1}, {0.5f, -0.3f, 0.2f, -0.7f},
                {-0.25f, 0.9f, -0.6f, 0.4f}};
        for (float[] a : adjusts) {
            String params = String.format(Locale.ROOT, "hue=%.2f sat=%.2f bri=%.2f con=%.2f", a[0], a[1], a[2], a[3]);
            cases.add(new Case("ColorAdjust", params, ONE_STEP, colorAdjust(a[0], a[1], a[2], a[3])));
        }
        for (float level : new float[] {0f, 0.5f, 1f}) {
            cases.add(new Case("SepiaTone", String.format(Locale.ROOT, "level=%.1f", level), ONE_STEP, (b, in) -> {
                SepiaTone effect = new SepiaTone();
                effect.setLevel(level);
                return b.generated(effect, "SepiaTone", null, b.data(in.primary(), 0, 0));
            }));
        }
        for (float threshold : new float[] {0f, 0.3f, 0.7f, 1f}) {
            cases.add(new Case("Brightpass", String.format(Locale.ROOT, "threshold=%.1f", threshold), ONE_STEP,
                    (b, in) -> {
                        Brightpass effect = new Brightpass();
                        effect.setThreshold(threshold);
                        return b.generated(effect, "Brightpass", null, b.data(in.primary(), 0, 0));
                    }));
        }
        for (Blend.Mode mode : Blend.Mode.values()) {
            for (float opacity : new float[] {1f, 0.5f}) {
                String params = String.format(Locale.ROOT, "%s opacity=%.1f", mode.name(), opacity);
                cases.add(new Case("Blend_" + mode.name(), params, ONE_STEP, (b, in) -> {
                    Blend effect = new Blend(mode, null, null);
                    effect.setOpacity(opacity);
                    return b.generated(effect, "Blend_" + mode.name(), null, b.data(in.primary(), 0, 0),
                            b.data(in.secondary(), 0, 0));
                }));
            }
        }
        int[][] masks = {{0, 0, 0}, {5, 0, 0}, {0, 3, -2}, {5, 3, -2}};
        for (int[] m : masks) {
            String params = String.format(Locale.ROOT, "pad=%d offset=%d,%d", m[0], m[1], m[2]);
            cases.add(new Case("InvertMask", params, ONE_STEP, (b, in) -> {
                InvertMask effect = new InvertMask(m[0]);
                effect.setOffsetX(m[1]);
                effect.setOffsetY(m[2]);
                return b.generated(effect, "InvertMask", null, b.data(in.primary(), 0, 0));
            }));
        }
        cases.add(new Case("PerspectiveTransform", "identity quad", ONE_STEP,
                (b, in) -> perspective(b, in, 0, 0, in.width(), 0, in.width(), in.height(), 0, in.height())));
        cases.add(new Case("PerspectiveTransform", "skewed quad", ONE_STEP,
                (b, in) -> perspective(b, in, 10, 5, in.width() - 4, 12, in.width() - 12, in.height() - 3, 6,
                        in.height() - 8)));
        float[][] phongs = {{1.5f, 1f, 0.3f, 20f}, {5f, 2f, 1f, 1f}};
        for (float[] p : phongs) {
            String params = String.format(Locale.ROOT, "scale=%.1f kd=%.1f ks=%.1f exp=%.0f", p[0], p[1], p[2], p[3]);
            cases.add(new Case("PhongLighting_DISTANT", params, ONE_STEP,
                    (b, in) -> phong(b, in, new DistantLight(45f, 60f, Color4f.WHITE), p)));
            cases.add(new Case("PhongLighting_POINT", params, ONE_STEP, (b, in) -> phong(b, in,
                    new PointLight(in.width() / 2f, in.height() / 3f, 40f, new Color4f(1f, 0.8f, 0.6f, 1f)), p)));
            cases.add(new Case("PhongLighting_SPOT", params, ONE_STEP, (b, in) -> {
                SpotLight light = new SpotLight(in.width() / 4f, in.height() / 2f, 30f, Color4f.WHITE);
                light.setPointsAtX(in.width() / 2f);
                light.setPointsAtY(in.height() / 2f);
                light.setPointsAtZ(0f);
                light.setSpecularExponent(2f);
                return phong(b, in, light, p);
            }));
        }
        cases.add(new Case("DisplacementMap", "scale=1,1 offset=0,0 wrap=false", ONE_STEP,
                (b, in) -> displacement(b, in, 1f, 1f, 0f, 0f, false)));
        cases.add(new Case("DisplacementMap", "scale=0.5,-0.5 offset=0.1,-0.05 wrap=true", ONE_STEP,
                (b, in) -> displacement(b, in, 0.5f, -0.5f, 0.1f, -0.05f, true)));
    }

    private static BiFunction<DecoraBackend, Inputs, Result> colorAdjust(float hue, float saturation,
                                                                         float brightness, float contrast) {
        return (b, in) -> {
            ColorAdjust effect = new ColorAdjust();
            effect.setHue(hue);
            effect.setSaturation(saturation);
            effect.setBrightness(brightness);
            effect.setContrast(contrast);
            return b.generated(effect, "ColorAdjust", null, b.data(in.primary(), 0, 0));
        };
    }

    /**
     * The Java peer's arithmetic for {@code contrast = 1} ({@code c * 3 + 1 = 4}) up to {@code rgb_to_hsb}:
     * does the largest un-premultiplied, contrast-adjusted channel land on exactly {@code 0.0} while the
     * channels are not all equal, so that {@code s = (cmax - cmin) / cmax} divides by zero?
     */
    static boolean fullContrastZeroesTheMaxChannel(int argb) {
        float a = (argb >>> 24) / 255f;
        float r = ((argb >> 16) & 0xff) / 255f;
        float g = ((argb >> 8) & 0xff) / 255f;
        float bl = (argb & 0xff) / 255f;
        if (a > 0.0f) {
            r /= a;
            g /= a;
            bl /= a;
        }
        float contrast = 4f;
        r = ((r - 0.5f) * contrast) + 0.5f;
        g = ((g - 0.5f) * contrast) + 0.5f;
        bl = ((bl - 0.5f) * contrast) + 0.5f;
        float cmax = Math.max(Math.max(r, g), bl);
        float cmin = Math.min(Math.min(r, g), bl);
        return cmax == 0.0f && cmax > cmin;
    }

    private static Result perspective(DecoraBackend b, Inputs in, float ulx, float uly, float urx, float ury,
                                      float lrx, float lry, float llx, float lly) {
        // The peer reads the inverse transform the effect computes in its private setupTransforms; the
        // public transform(Point2D, Effect) runs it for the identity transform, which is what we filter with.
        PerspectiveTransform effect = new PerspectiveTransform(
                new Flood(new Object(), new RectBounds(0, 0, in.width(), in.height())));
        effect.setQuadMapping(ulx, uly, urx, ury, lrx, lry, llx, lly);
        effect.transform(new Point2D(0, 0), null);
        Rectangle clip = new Rectangle(0, 0, in.width(), in.height());
        return b.generated(effect, "PerspectiveTransform", clip, b.data(in.primary(), 0, 0));
    }

    private static Result phong(DecoraBackend b, Inputs in, Light light, float[] p) {
        PhongLighting effect = new PhongLighting(light);
        effect.setSurfaceScale(p[0]);
        effect.setDiffuseConstant(p[1]);
        effect.setSpecularConstant(p[2]);
        effect.setSpecularExponent(p[3]);
        ImageData bump = b.data(in.secondary(), 0, 0);
        ImageData content = b.data(in.primary(), 0, 0);
        return b.generated(effect, "PhongLighting_" + light.getType().name(), null, bump, content);
    }

    private static Result displacement(DecoraBackend b, Inputs in, float scaleX, float scaleY, float offsetX,
                                       float offsetY, boolean wrap) {
        FloatMap map = new FloatMap(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float u = (float) Math.sin(x * 0.7 + y * 0.3) * 0.2f;
                float v = (float) Math.cos(x * 0.2 - y * 0.9) * 0.2f;
                map.setSamples(x, y, u, v, 0f, 0f);
            }
        }
        DisplacementMap effect = new DisplacementMap(map);
        effect.setScaleX(scaleX);
        effect.setScaleY(scaleY);
        effect.setOffsetX(offsetX);
        effect.setOffsetY(offsetY);
        effect.setWrap(wrap);
        return b.generated(effect, "DisplacementMap", null, b.data(in.primary(), 0, 0));
    }

    static Inputs inputs(int width, int height) {
        return new Inputs(width, height, Image.of(width, height, pattern(width, height, 0x9E3779B9L)),
                Image.of(width, height, pattern(width, height, 0x7F4A7C15L)));
    }

    /**
     * Deterministic ARGB-pre test content: an opaque white frame, a transparent frame inside it, a hard
     * opaque red diagonal band, and pseudo-random premultiplied noise (alpha first, colour never above
     * alpha) everywhere else. The generator is a fixed xorshift so the pattern does not depend on the JDK.
     */
    static int[] pattern(int width, int height, long seed) {
        int[] pixels = new int[width * height];
        long state = seed;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                state ^= state << 13;
                state ^= state >>> 7;
                state ^= state << 17;
                int argb;
                if (x < 3 || y < 3 || x >= width - 3 || y >= height - 3) {
                    argb = 0xFFFFFFFF;
                } else if (x < 6 || y < 6 || x >= width - 6 || y >= height - 6) {
                    argb = 0;
                } else if (Math.abs((x - y) % 23) < 3) {
                    argb = 0xFFFF0000;
                } else {
                    int a = (int) (state & 0xFF);
                    int r = (int) ((state >>> 8) & 0xFF) * a / 255;
                    int g = (int) ((state >>> 16) & 0xFF) * a / 255;
                    int bl = (int) ((state >>> 24) & 0xFF) * a / 255;
                    argb = (a << 24) | (r << 16) | (g << 8) | bl;
                }
                pixels[y * width + x] = argb;
            }
        }
        return pixels;
    }

    private static void record(String effect, String params, String size, int maxDelta, long differing, long total,
                               int bound) {
        LEDGER.compared();
        synchronized (ROWS) {
            ROWS.add(new Row(effect, params, size, maxDelta, differing, total, bound));
        }
    }

    private static void assertSameBounds(String label, Result a, Result b) {
        assertEquals(a.x() + "," + a.y() + " " + a.width() + "x" + a.height(),
                b.x() + "," + b.y() + " " + b.width() + "x" + b.height(),
                () -> label + ": SSE and Java results have different bounds");
    }

    /** The pixels of {@code full} that fall inside the bounds of {@code window}, as a result with those bounds. */
    private static Result crop(Result full, Result window) {
        int[] pixels = new int[window.width() * window.height()];
        for (int row = 0; row < window.height(); row++) {
            int srcRow = window.y() - full.y() + row;
            int srcCol = window.x() - full.x();
            System.arraycopy(full.pixels(), srcRow * full.width() + srcCol, pixels, row * window.width(),
                    window.width());
        }
        return new Result(window.x(), window.y(), window.width(), window.height(), pixels, full.image());
    }

    static int maxDelta(Result a, Result b) {
        int maxDelta = 0;
        for (int i = 0; i < a.pixels().length; i++) {
            maxDelta = Math.max(maxDelta, channelDelta(a.pixels()[i], b.pixels()[i]));
        }
        return maxDelta;
    }

    static long differing(Result a, Result b) {
        long differing = 0;
        for (int i = 0; i < a.pixels().length; i++) {
            if (a.pixels()[i] != b.pixels()[i]) {
                differing++;
            }
        }
        return differing;
    }

    static int channelDelta(int p, int q) {
        int d = Math.abs((p >>> 24) - (q >>> 24));
        d = Math.max(d, Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)));
        d = Math.max(d, Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)));
        return Math.max(d, Math.abs((p & 0xFF) - (q & 0xFF)));
    }

    private static String firstDifference(Result a, Result b) {
        for (int i = 0; i < a.pixels().length; i++) {
            if (a.pixels()[i] != b.pixels()[i]) {
                return String.format(Locale.ROOT, "(%d,%d) sse=%08x java=%08x", i % a.width(), i / a.width(),
                        a.pixels()[i], b.pixels()[i]);
            }
        }
        return "none";
    }
}
