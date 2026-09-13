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

import com.sun.scenario.effect.Brightpass;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.ColorAdjust;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.PhongLighting;
import com.sun.scenario.effect.SepiaTone;
import com.sun.scenario.effect.light.PointLight;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.scenario.effect.DecoraBackend.Image;
import test.com.sun.scenario.effect.DecoraBackend.Result;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Throughput of the native {@code decora_sse} software peers against the pure-Java software peers,
 * single-threaded, on the same inputs - the measurement the decision on deleting {@code decora_sse}
 * is gated on.
 * <p>
 * Opt-in: runs only with {@code -Ddecora.bench=true} (otherwise every case is skipped), because it
 * takes minutes. Each workload is run at 1920x1080 and 256x256 on both backends; per backend at least
 * 20 warm-up iterations are followed by at least 50 timed iterations
 * ({@code -Ddecora.bench.warmup}, {@code -Ddecora.bench.iterations}), the result image is returned
 * to the backend's pool after every iteration as {@code ImageData.unref} would in production, and the
 * median and 90th percentile of the per-iteration wall time are reported in ns/op together with the
 * Java/SSE median ratio. Plain {@code System.nanoTime}, no JMH: the numbers are for a delete-or-keep
 * decision with a wide margin, not for micro-optimisation.
 * <p>
 * Workloads: the four hand-written peers (BoxBlur, BoxShadow, LinearConvolve as a centered Gaussian,
 * LinearConvolveShadow as a centered Gaussian shadow), four representative generated peers
 * (ColorAdjust, SepiaTone, Brightpass, PhongLighting_POINT), and two probes that call the
 * {@code filterHV} loops of both backends directly ({@link DecoraBackend#filterHV}) - the loop the SSE
 * peer runs for centered Gaussian passes and the Java peer has but never reaches.
 * <p>
 * Run with {@code mvn -pl modules/javafx.graphics test -DskipNative=true -Dtest=DecoraSseJavaBenchmark
 * -Ddecora.bench=true}; the table is printed to the test output and written to
 * {@code target/decora-benchmark.txt}.
 */
public class DecoraSseJavaBenchmark {

    private static final int MIN_WARMUP = 20;
    private static final int MIN_ITERATIONS = 50;

    private static final int[][] SIZES = {{1920, 1080}, {256, 256}};

    private static DecoraBackend sse;
    private static DecoraBackend java;
    private static int warmup;
    private static int iterations;
    private static final List<String> ROWS = new ArrayList<>();

    /** One workload: a name and a recipe that renders the given sources on a backend. */
    record Workload(String name, BiFunction<DecoraBackend, Sources, Result> run) {
    }

    /**
     * The source images of one backend for one size, plus two scratch images sized for the horizontal
     * and the vertical pass of the {@code filterHV} probe workloads.
     */
    record Sources(int width, int height, Image primary, Image secondary, Image scratchH, Image scratchV) {
    }

    /** Radius of the Gaussian workloads; the probe uses the same kernel width the peers would. */
    private static final int RADIUS = 5;
    private static final int TAPS = 2 * RADIUS + 1;
    private static final float[] HV_WEIGHTS = DecoraSseJavaParityTest.duplicatedGaussianWeights(RADIUS, 1f);
    private static final int[] NO_PIXELS = new int[0];

    /** Median and 90th percentile of one backend's timed iterations, in nanoseconds. */
    record Timing(long medianNanos, long p90Nanos) {
    }

    @BeforeAll
    static void backends() {
        assumeTrue(Boolean.getBoolean("decora.bench"), "benchmark is opt-in: pass -Ddecora.bench=true");
        warmup = Math.max(MIN_WARMUP, Integer.getInteger("decora.bench.warmup", MIN_WARMUP));
        iterations = Math.max(MIN_ITERATIONS, Integer.getInteger("decora.bench.iterations", MIN_ITERATIONS));
        sse = DecoraBackend.sse();
        java = DecoraBackend.java();
    }

    @AfterAll
    static void report() throws IOException {
        if (ROWS.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder(2048);
        text.append(String.format(Locale.ROOT, "%nDecora software peers, %s, %d warm-up + %d timed iterations,"
                + " single-threaded, %s%n", System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                warmup, iterations, System.getProperty("java.vm.name") + " " + System.getProperty("java.version")));
        text.append(String.format(Locale.ROOT, "%-24s %-10s %14s %14s %14s %14s %9s%n", "workload", "size",
                "SSE median", "SSE p90", "Java median", "Java p90", "Java/SSE"));
        text.append(String.format(Locale.ROOT, "%-24s %-10s %14s %14s %14s %14s %9s%n", "", "", "ns/op", "ns/op",
                "ns/op", "ns/op", "median"));
        for (String row : ROWS) {
            text.append(row).append(System.lineSeparator());
        }
        System.out.println(text);
        Path out = Path.of("target", "decora-benchmark.txt");
        if (Files.isDirectory(out.getParent())) {
            Files.writeString(out, text.toString(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest(name = "{0} {1}x{2}")
    @MethodSource("workloads")
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void throughput(String name, int width, int height, Workload workload) {
        Timing sseTiming = time(sse, workload, width, height);
        Timing javaTiming = time(java, workload, width, height);
        double ratio = (double) javaTiming.medianNanos() / sseTiming.medianNanos();
        String row = String.format(Locale.ROOT, "%-24s %-10s %,14d %,14d %,14d %,14d %8.2fx", name,
                width + "x" + height, sseTiming.medianNanos(), sseTiming.p90Nanos(), javaTiming.medianNanos(),
                javaTiming.p90Nanos(), ratio);
        synchronized (ROWS) {
            ROWS.add(row);
        }
        System.out.println(row);
    }

    private static Timing time(DecoraBackend backend, Workload workload, int width, int height) {
        Sources sources = new Sources(width, height,
                Image.of(width, height, DecoraSseJavaParityTest.pattern(width, height, 0x9E3779B9L)),
                Image.of(width, height, DecoraSseJavaParityTest.pattern(width, height, 0x7F4A7C15L)),
                new Image(width + TAPS - 1, height), new Image(width + TAPS - 1, height + TAPS - 1));
        for (int i = 0; i < warmup; i++) {
            backend.release(workload.run().apply(backend, sources), sources.primary(), sources.secondary());
        }
        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Result result = workload.run().apply(backend, sources);
            samples[i] = System.nanoTime() - start;
            backend.release(result, sources.primary(), sources.secondary());
        }
        Arrays.sort(samples);
        return new Timing(samples[samples.length / 2], samples[(int) Math.min(samples.length - 1,
                Math.ceil(samples.length * 0.9) - 1)]);
    }

    static Stream<Arguments> workloads() {
        List<Arguments> args = new ArrayList<>();
        for (Workload w : allWorkloads()) {
            for (int[] size : SIZES) {
                args.add(Arguments.of(w.name(), size[0], size[1], w));
            }
        }
        return args.stream();
    }

    static List<Workload> allWorkloads() {
        Color4f tint = new Color4f(0f, 0f, 0f, 0.75f);
        return List.of(
                new Workload("BoxBlur 9x9 x3", (b, s) -> b.box(b.data(s.primary(), 0, 0), 9, 9, 3, 0f, false, null,
                        null)),
                new Workload("BoxShadow 9x9 x3", (b, s) -> b.box(b.data(s.primary(), 0, 0), 9, 9, 3, 0f, true,
                        tint, null)),
                new Workload("LinearConvolve r=5", (b, s) -> b.gaussian(b.data(s.primary(), 0, 0), RADIUS, RADIUS,
                        0f, false, null, null)),
                new Workload("LinearConvolveShadow r=5", (b, s) -> b.gaussian(b.data(s.primary(), 0, 0), RADIUS,
                        RADIUS, 0f, true, tint, null)),
                // The filterHV loops called directly, horizontal then vertical pass, same kernel as above: what the
                // SSE peer runs for a centered Gaussian, and what the Java peer would run without its hard-coded
                // GENERAL_VECTOR. The difference to "LinearConvolve r=5" on the Java side is the cost of that line.
                new Workload("filterHV blur r=5 (probe)", (b, s) -> {
                    Image h = s.scratchH();
                    Image v = s.scratchV();
                    int dstw = s.width() + TAPS - 1;
                    int dsth = s.height() + TAPS - 1;
                    b.filterHV(h.getPixelArray(), dstw, s.height(), 1, h.getScanlineStride(),
                            s.primary().getPixelArray(), s.width(), s.height(), 1, s.primary().getScanlineStride(),
                            HV_WEIGHTS);
                    b.filterHV(v.getPixelArray(), dsth, dstw, v.getScanlineStride(), 1, h.getPixelArray(),
                            s.height(), dstw, h.getScanlineStride(), 1, HV_WEIGHTS);
                    return new Result(0, 0, 0, 0, NO_PIXELS, v);
                }),
                new Workload("filterHV shadow r=5 (probe)", (b, s) -> {
                    Image h = s.scratchH();
                    Image v = s.scratchV();
                    int dstw = s.width() + TAPS - 1;
                    int dsth = s.height() + TAPS - 1;
                    b.filterHVShadow(h.getPixelArray(), dstw, s.height(), 1, h.getScanlineStride(),
                            s.primary().getPixelArray(), s.width(), s.height(), 1, s.primary().getScanlineStride(),
                            HV_WEIGHTS, Color4f.BLACK);
                    b.filterHVShadow(v.getPixelArray(), dsth, dstw, v.getScanlineStride(), 1, h.getPixelArray(),
                            s.height(), dstw, h.getScanlineStride(), 1, HV_WEIGHTS, tint);
                    return new Result(0, 0, 0, 0, NO_PIXELS, v);
                }),
                new Workload("ColorAdjust", (b, s) -> {
                    ColorAdjust effect = new ColorAdjust();
                    effect.setHue(0.2f);
                    effect.setSaturation(0.3f);
                    effect.setBrightness(-0.1f);
                    effect.setContrast(0.4f);
                    return b.generated(effect, "ColorAdjust", null, b.data(s.primary(), 0, 0));
                }),
                new Workload("SepiaTone", (b, s) -> {
                    SepiaTone effect = new SepiaTone();
                    effect.setLevel(0.7f);
                    return b.generated(effect, "SepiaTone", null, b.data(s.primary(), 0, 0));
                }),
                new Workload("Brightpass", (b, s) -> {
                    Brightpass effect = new Brightpass();
                    effect.setThreshold(0.4f);
                    return b.generated(effect, "Brightpass", null, b.data(s.primary(), 0, 0));
                }),
                new Workload("PhongLighting_POINT", (b, s) -> {
                    PhongLighting effect = new PhongLighting(
                            new PointLight(s.width() / 2f, s.height() / 3f, 40f, Color4f.WHITE));
                    effect.setSurfaceScale(1.5f);
                    effect.setDiffuseConstant(1f);
                    effect.setSpecularConstant(0.3f);
                    effect.setSpecularExponent(20f);
                    ImageData bump = b.data(s.secondary(), 0, 0);
                    ImageData content = b.data(s.primary(), 0, 0);
                    return b.generated(effect, "PhongLighting_POINT", null, bump, content);
                }));
    }
}
