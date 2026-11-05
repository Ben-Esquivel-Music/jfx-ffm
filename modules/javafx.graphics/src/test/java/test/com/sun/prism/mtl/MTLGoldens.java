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

package test.com.sun.prism.mtl;

import com.sun.javafx.font.MacMachineShim;
import com.sun.prism.mtl.MTLGoldenShim;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import test.com.sun.javafx.test.ParityGate;
import test.com.sun.prism.mtl.MTLGoldenFormat.FrameRef;
import test.com.sun.prism.mtl.MTLGoldenFormat.GoldenMap;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The machine gate, capture guard, child JVMs and capture-or-verify flow of the Metal goldens: what the JNI build of
 * commit {@value #CAPTURE_COMMIT} rendered through the Prism SPI, captured before any of {@code prism_mtl} was
 * rewritten. The file format is {@link MTLGoldenFormat}; the corpus is {@link MTLGoldenChild.Steps}.
 * <p>
 * Every gate key is computed in this JVM without touching the library under test, and none of them names the
 * library, the Java classes, the commit or the ABI: those are what the migration changes. A gate mismatch launches no
 * child at all. A capture runs every profile in two independent child JVMs, two passes each, and writes the golden
 * only if all four results are byte-identical; it refuses to run unless the tree is the C and Java of
 * {@value #CAPTURE_COMMIT} with all {@value #DECLARED_NATIVES} natives declared, so the FFM build can never
 * regenerate it.
 */
final class MTLGoldens {

    static final String CAPTURE_COMMIT = "1a6788cc4d";
    static final String GOLDEN = "mtl-golden-" + CAPTURE_COMMIT + ".txt";
    static final String PAYLOAD = "mtl-golden-" + CAPTURE_COMMIT + ".bin";
    static final String SHADER_GOLDEN = "mtl-shaders-golden-" + CAPTURE_COMMIT + ".txt";

    static final String CAPTURE_PROPERTY = "jfx.mtl.golden.capture";
    static final String REGENERATE_PROPERTY = "jfx.mtl.golden.regenerate";
    static final String PASSES_PROPERTY = "jfx.mtl.golden.passes";
    static final String RUNS_PROPERTY = "jfx.mtl.golden.runs";

    static final String DEFAULT = "default";
    static final String NO_SUPER_SHADER = "noSuperShader";
    static final String POW2 = "pow2";
    static final String LIFECYCLE = "lifecycle";
    static final String SHADERS = "shaders";
    static final String OBJC_RELEASE = "objcRelease";
    static final List<String> PIXEL_PROFILES = List.of(DEFAULT, NO_SUPER_SHADER, POW2);
    static final Map<String, List<String>> PROFILE_FLAGS = Map.of(
            DEFAULT, List.of(),
            NO_SUPER_SHADER, List.of("-Dprism.supershader=false"),
            POW2, List.of("-Dprism.forcepowerof2=true"),
            LIFECYCLE, List.of(),
            SHADERS, List.of(),
            OBJC_RELEASE, List.of());

    static final int JNI_EXPORTS = 59;
    static final int DECLARED_NATIVES = 60;

    /** Declared but never linked by any child: dead (no body, no caller) or reachable only through a Glass view. */
    static final Set<String> UNLINKED_NATIVES = Set.of("MTLRTTexture.nGetPixelDataPtr",
            "MTLContext.nSetWorldTransformToIdentity", "MTLRTTexture.nCreateRT2");

    static final long CHILD_TIMEOUT_SECONDS = 120;
    static final String WORK_DIRECTORY = "target/mtl-golden";
    static final String RESOURCE_DIRECTORY = "src/test/resources/test/com/sun/prism/mtl";
    static final String CLASSES_DIRECTORY = "target/test-classes/test/com/sun/prism/mtl";
    static final String NATIVE_SOURCES = "src/main/native-prism-mtl";
    static final String JAVA_SOURCES = "src/main/java/com/sun/prism/mtl";
    static final String AHEM = "../javafx.web/src/main/native/Tools/DumpRenderTree/fonts/AHEM____.TTF";
    static final List<String> GUARDED_PATHS = List.of("modules/javafx.graphics/src/main",
            "modules/javafx.graphics/native", "modules/javafx.base/src/main", "buildtools/jslc");

    /** Kept out of the child: validation layers, device wrappers and interposed libraries change what runs. */
    static final List<String> REMOVED_ENVIRONMENT_PREFIXES = List.of("MTL_", "METAL_", "OBJC_", "DYLD_");

    private static final long COMMAND_TIMEOUT_SECONDS = 60;
    private static final Pattern JNI_LINK = Pattern.compile("Dynamic-linking native method com\\.sun\\.prism\\.mtl\\."
            + "([A-Za-z0-9_$]+)\\.([A-Za-z0-9_$]+) ");
    private static final Pattern PLIST_ENTRY = Pattern.compile("<key>([^<]+)</key>\\s*<string>([^<]*)</string>");
    private static final Pattern IOREG_ENTRY = Pattern.compile("\"([^\"]+)\" = (.*)");
    private static final Pattern CRASH_SIGNAL = Pattern.compile("^#\\s+(SIG[A-Z]+)");

    private static Map<String, String> machine;
    private static String blocked;
    private static boolean captured;

    private MTLGoldens() {
    }

    static boolean captureRequested() {
        return Boolean.getBoolean(CAPTURE_PROPERTY);
    }

    static int passes() {
        return Integer.getInteger(PASSES_PROPERTY, 2);
    }

    static int runs() {
        return Integer.getInteger(RUNS_PROPERTY, 2);
    }

    static String frameKey(String profile, int index, String stepKey) {
        return profile + ".s" + String.format("%04d", index) + "." + stepKey;
    }

    static String captureCommand() {
        return "mvn -B -ntp -pl modules/javafx.graphics -am clean test -Dtest='MTL*Golden*Test'"
                + " -Dsurefire.failIfNoSpecifiedTests=false -D" + CAPTURE_PROPERTY + "=true";
    }

    // ---------------------------------------------------------------------------------------------
    // Verify
    // ---------------------------------------------------------------------------------------------

    /** Compares one pixel or lifecycle profile against the golden. */
    static void verifyRender(Class<?> test, String profile) throws Exception {
        MTLNatives.require();
        if (captureRequested()) {
            captureRender();
            abort("the Metal golden was captured to " + GOLDEN + " and " + PAYLOAD + "; a capture verifies nothing");
        }
        Map<String, String> golden = load(GOLDEN, test);
        ParityGate.Ledger ledger = ParityGate.ledger(test);
        requireSameMachine(golden, machine(), ledger, ParityGate.required());
        if (!Integer.toString(MTLGoldenRenderTest.TOLERANCE).equals(golden.get("tolerance.perChannel"))) {
            fail("the golden declares a per-channel tolerance of " + golden.get("tolerance.perChannel")
                    + " and the test " + MTLGoldenRenderTest.TOLERANCE + "; the comparison is exact by design");
        }
        byte[] payload = requirePayload(golden);
        ChildRun run = runChild(profile, "verify", LIFECYCLE.equals(profile) ? 1 : passes());
        Map<String, String> actual = new TreeMap<>(LIFECYCLE.equals(profile) ? lifecycleOutcome(run)
                                                                            : run.requireSuccess().keys());
        String pipeline = actual.get(profile + ".pipeline");
        ledger.requireOracle(!MTLGoldenFormat.NULL.equals(pipeline), () -> "MTLPipeline.init() returned false in"
                + " the child (nSupportsMTL), so this machine has no Metal device the golden can describe.");
        Predicate<String> scope = key -> key.startsWith(profile + ".");
        if (!LIFECYCLE.equals(profile)) {
            for (int pass = 2; pass <= run.passes().size(); pass++) {
                List<String> drift = identical(run, 1, run, pass);
                if (!drift.isEmpty()) {
                    fail("pass " + pass + " of the " + profile + " child differs from pass 1, so the rendering is not"
                            + " deterministic in one process: " + MTLGoldenFormat.report(drift));
                }
            }
            actual.putAll(run.passes().get(0).frames());
        }
        List<String> differences = MTLGoldenFormat.differences(golden, actual, scope);
        ledger.compared(MTLGoldenFormat.relativeFrames(golden, scope).size());
        if (!differences.isEmpty()) {
            String pixels = LIFECYCLE.equals(profile) ? "" : pixelReport(golden, payload, run);
            fail("the " + profile + " rendering differs from the golden the JNI build of " + CAPTURE_COMMIT
                    + " produced (exact comparison, tolerance 0): " + MTLGoldenFormat.report(differences) + pixels);
        }
    }

    /** Compares the uniform tables of every shader against their golden. */
    static void verifyShaders(Class<?> test) throws Exception {
        MTLNatives.require();
        if (captureRequested()) {
            captureShaders();
            abort("the shader-table golden was captured to " + SHADER_GOLDEN + "; a capture verifies nothing");
        }
        Map<String, String> golden = load(SHADER_GOLDEN, test);
        ParityGate.Ledger ledger = ParityGate.ledger(test);
        requireSameMachine(golden, machine(), ledger, ParityGate.required());
        Map<String, String> actual = runChild(SHADERS, "verify", 1).requireSuccess().keys();
        ledger.requireOracle(!MTLGoldenFormat.NULL.equals(actual.get(SHADERS + ".pipeline")),
                () -> "MTLPipeline.init() returned false in the child (nSupportsMTL).");
        Predicate<String> scope = key -> key.startsWith("shader.") || key.startsWith(SHADERS + ".");
        List<String> differences = MTLGoldenFormat.differences(golden, actual, scope);
        ledger.compared(MTLGoldenFormat.relativeFrames(golden, scope).size());
        if (!differences.isEmpty()) {
            fail("the shader uniform tables differ from the golden of the JNI build of " + CAPTURE_COMMIT + ": "
                    + MTLGoldenFormat.report(differences));
        }
    }

    /**
     * Skips, or fails under {@code -Djfx.parity.require=true}, unless every {@code machine.} key of the golden equals
     * this machine's and no new one has appeared; the message names each difference and where the golden was
     * captured.
     */
    static void requireSameMachine(Map<String, String> golden, Map<String, String> now, ParityGate.Ledger ledger,
                                   boolean required) {
        TreeSet<String> keys = new TreeSet<>();
        for (String key : golden.keySet()) {
            if (key.startsWith(MTLGoldenFormat.MACHINE_PREFIX)) {
                keys.add(key);
            }
        }
        for (String key : now.keySet()) {
            if (key.startsWith(MTLGoldenFormat.MACHINE_PREFIX)) {
                keys.add(key);
            }
        }
        List<String> differences = new ArrayList<>();
        for (String key : keys) {
            String want = golden.get(key);
            String got = now.get(key);
            if (want == null || !want.equals(got)) {
                differences.add(key + ": golden=" + want + " this machine=" + got);
            }
        }
        ledger.requireOracle(differences.isEmpty(), required, () -> "the Metal golden describes another machine,"
                + " macOS build, GPU driver, shader library or JDK, so it is no oracle here and no child was launched."
                + " Differences: " + differences + ". Captured on: "
                + golden.getOrDefault("capture.provenance", "(not recorded)") + ". A cross-machine oracle is a"
                + " re-capture from a JNI worktree of " + CAPTURE_COMMIT + " on that machine, never a tolerance.");
    }

    private static Map<String, String> load(String name, Class<?> test) {
        try (InputStream in = MTLGoldens.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new AssertionError("no golden " + name + " on the classpath. The test compares Metal against"
                        + " what the JNI build rendered; with no capture there is nothing to compare. Capture with \""
                        + captureCommand() + "\" in a tree with the C of " + CAPTURE_COMMIT + " ("
                        + test.getSimpleName() + ").");
            }
            return MTLGoldenFormat.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The inflated frames of the golden, checked against the size and digest its text records. */
    static byte[] requirePayload(Map<String, String> golden) {
        byte[] payload;
        try (InputStream in = MTLGoldens.class.getResourceAsStream(PAYLOAD)) {
            if (in == null) {
                throw new AssertionError("no golden payload " + PAYLOAD + " on the classpath");
            }
            payload = MTLGoldenFormat.inflate(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!Long.toString(payload.length).equals(golden.get("payload.bytes"))
                || !MTLGoldenFormat.sha256Hex(payload).equals(golden.get("payload.sha256"))) {
            fail(PAYLOAD + " is not the payload " + GOLDEN + " describes: " + payload.length + " bytes, sha256 "
                    + MTLGoldenFormat.sha256Hex(payload));
        }
        return payload;
    }

    private static String pixelReport(Map<String, String> golden, byte[] payload, ChildRun run) throws IOException {
        Pass pass = run.passes().get(0);
        Path diff = moduleDirectory().resolve(WORK_DIRECTORY).resolve("diff");
        StringBuilder report = new StringBuilder();
        int reported = 0;
        for (Map.Entry<String, String> entry : pass.frames().entrySet()) {
            FrameRef want = MTLGoldenFormat.frame(golden.getOrDefault(entry.getKey(), ""));
            FrameRef got = MTLGoldenFormat.frame(entry.getValue());
            if (want == null || got == null || want.sha256().equals(got.sha256())
                    || want.width() != got.width() || want.height() != got.height()) {
                continue;
            }
            int[] expected = MTLGoldenFormat.framePixels(payload, want);
            int[] actual = MTLGoldenFormat.framePixels(pass.payload(), got);
            String stem = entry.getKey().replace('.', '-');
            MTLGoldenFormat.writeDiff(diff, stem, want.width(), want.height(), expected, actual);
            if (reported++ < 20) {
                report.append("\n  ").append(entry.getKey()).append(": ")
                      .append(MTLGoldenFormat.describe(want.width(), want.height(), expected, actual));
            }
        }
        return reported == 0 ? "" : "\nchanged pixels (PAM images in " + diff + "):" + report;
    }

    // ---------------------------------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------------------------------

    /** Captures every render profile once per JVM, whichever test method asks first. */
    static synchronized void captureRender() throws Exception {
        if (captured) {
            return;
        }
        Path library = MTLNatives.require();
        requireJniBuild(library);
        GoldenMap out = new GoldenMap(machine());
        putCaptureInfo(out, library);
        out.put("tolerance.perChannel", "0");
        ByteArrayOutputStream payload = new ByteArrayOutputStream(1 << 22);
        Set<String> linked = new TreeSet<>();
        List<String> determinism = new ArrayList<>();
        for (String profile : PIXEL_PROFILES) {
            List<ChildRun> children = new ArrayList<>();
            for (int run = 0; run < runs(); run++) {
                children.add(runChild(profile, String.valueOf((char) ('A' + run)), passes()).requireSuccess());
            }
            ChildRun first = children.get(0);
            for (ChildRun child : children) {
                for (int pass = 1; pass <= child.passes().size(); pass++) {
                    List<String> drift = identical(first, 1, child, pass);
                    if (!drift.isEmpty()) {
                        fail("refusing to capture: run " + child.run() + " pass " + pass + " of " + profile
                                + " differs from run A pass 1. A nondeterministic step gets fixed (usually a missing"
                                + " clear) or removed with a written reason, never a tolerance: "
                                + MTLGoldenFormat.report(drift));
                    }
                }
                linked.addAll(child.jniNatives());
            }
            determinism.add(profile + "=" + children.size() + "x" + first.passes().size() + ":identical");
            requireDiscriminating(profile, first.passes().get(0));
            if (DEFAULT.equals(profile)) {
                requireShaderCoverage(first.keys().get(profile + ".coverage.shaders"));
            }
            long base = payload.size();
            out.putAll(first.keys());
            for (Map.Entry<String, String> entry : first.passes().get(0).frames().entrySet()) {
                out.put(entry.getKey(), MTLGoldenFormat.frame(entry.getValue()).rebased(-base).value());
            }
            payload.write(first.passes().get(0).payload());
            out.put("capture." + profile + ".jniNatives", join(first.jniNatives()));
        }
        List<Map<String, String>> outcomes = new ArrayList<>();
        for (int run = 0; run < runs(); run++) {
            ChildRun child = runChild(LIFECYCLE, String.valueOf((char) ('A' + run)), 1);
            outcomes.add(lifecycleOutcome(child));
            linked.addAll(child.jniNatives());
            if (run == 0) {
                out.put("capture." + LIFECYCLE + ".jniNatives", join(child.jniNatives()));
            }
        }
        for (Map<String, String> outcome : outcomes) {
            if (!bodyOf(outcome).equals(bodyOf(outcomes.get(0)))) {
                fail("refusing to capture: the lifecycle children ended differently: " + outcomes);
            }
        }
        determinism.add(LIFECYCLE + "=" + outcomes.size() + "x1:identical");
        out.putAll(outcomes.get(0));
        requireNativeCoverage(linked);
        byte[] raw = payload.toByteArray();
        out.put("payload.bytes", Long.toString(raw.length));
        out.put("payload.sha256", MTLGoldenFormat.sha256Hex(raw));
        out.put("capture.determinism", String.join(";", determinism));
        write(GOLDEN, MTLGoldenFormat.format(out, header("Metal pixel golden", "rendered"))
                .getBytes(StandardCharsets.UTF_8));
        write(PAYLOAD, MTLGoldenFormat.deflate(raw));
        captured = true;
    }

    private static void captureShaders() throws Exception {
        Path library = MTLNatives.require();
        requireJniBuild(library);
        GoldenMap out = new GoldenMap(machine());
        putCaptureInfo(out, library);
        List<Map<String, String>> results = new ArrayList<>();
        for (int run = 0; run < runs(); run++) {
            results.add(runChild(SHADERS, String.valueOf((char) ('A' + run)), 1).requireSuccess().keys());
        }
        for (Map<String, String> result : results) {
            if (!bodyOf(result).equals(bodyOf(results.get(0)))) {
                fail("refusing to capture: the shader-table children disagree");
            }
        }
        requireShaderTables(results.get(0));
        out.putAll(results.get(0));
        out.put("capture.determinism", SHADERS + "=" + results.size() + "x1:identical");
        write(SHADER_GOLDEN, MTLGoldenFormat.format(out, header("Metal shader uniform-table golden",
                "returned for every shader"))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> bodyOf(Map<String, String> values) {
        Map<String, String> body = new TreeMap<>();
        values.forEach((key, value) -> {
            if (MTLGoldenFormat.isBodyKey(key)) {
                body.put(key, value);
            }
        });
        return body;
    }

    /**
     * The tree must hold the C and Java of {@value #CAPTURE_COMMIT}, unmodified, the library must have been built
     * from it, export the {@value #JNI_EXPORTS} JNI symbols, and the Java must declare all {@value #DECLARED_NATIVES}
     * natives.
     */
    static void requireJniBuild(Path library) throws IOException {
        Path repository = repositoryDirectory();
        List<String> diff = new ArrayList<>(List.of("git", "-C", repository.toString(), "diff", "--quiet",
                CAPTURE_COMMIT, "--"));
        diff.addAll(GUARDED_PATHS);
        if (exitCode(diff) != 0) {
            fail("refusing to capture: " + GUARDED_PATHS + " differ from commit " + CAPTURE_COMMIT + " (git diff), and"
                    + " the golden records the JNI build of exactly that tree");
        }
        List<String> status = new ArrayList<>(List.of("git", "-C", repository.toString(), "status", "--porcelain",
                "--untracked-files=all", "--"));
        status.addAll(GUARDED_PATHS);
        String untracked = run(status);
        if (untracked == null || !untracked.isBlank()) {
            fail("refusing to capture: files under " + GUARDED_PATHS + " are not those of " + CAPTURE_COMMIT + ":\n"
                    + untracked);
        }
        List<String> declared = MTLGoldenShim.declaredNatives();
        if (declared.size() != DECLARED_NATIVES) {
            fail("refusing to capture: com.sun.prism.mtl declares " + declared.size() + " natives, the JNI build of "
                    + CAPTURE_COMMIT + " declares " + DECLARED_NATIVES + ". The golden is never regenerated from the"
                    + " FFM build.");
        }
        int exports = jniExports(library).size();
        if (exports != JNI_EXPORTS) {
            fail("refusing to capture: " + library + " exports " + exports + " _Java_com_sun_prism_mtl_ symbols, the"
                    + " JNI build exports " + JNI_EXPORTS);
        }
        for (Path source : nativeSources()) {
            if (Files.getLastModifiedTime(library).compareTo(Files.getLastModifiedTime(source)) <= 0) {
                fail("refusing to capture: " + library + " is not newer than " + source + ", so it was not built"
                        + " from it");
            }
        }
    }

    private static void putCaptureInfo(GoldenMap out, Path library) throws IOException {
        Path repository = repositoryDirectory();
        String commit = run(List.of("git", "-C", repository.toString(), "rev-parse", "HEAD"));
        out.put("capture.commit", commit == null ? MTLGoldenFormat.NULL : commit.trim());
        String status = run(List.of("git", "-C", repository.toString(), "status", "--porcelain",
                "--untracked-files=all"));
        List<String> dirty = new ArrayList<>();
        if (status != null) {
            for (String line : status.split("\n")) {
                if (line.length() > 3) {
                    dirty.add(line.substring(3));
                }
            }
        }
        Collections.sort(dirty);
        out.put("capture.dirtyPaths", dirty.size() + ":" + String.join(";", dirty));
        out.put("capture.provenance", System.getProperty("java.vm.name") + " "
                + System.getProperty("java.vm.version") + " on " + machine().get("machine.os.productVersion") + " ("
                + machine().get("machine.os.build") + ") " + machine().get("machine.hw.model") + " "
                + machine().get("machine.gpu.model"));
        out.put("capture.jniLibrary", Files.size(library) + ":" + MTLGoldenFormat.sha256Hex(library));
        out.put("capture.jniLibrary.exports", Integer.toString(jniExports(library).size()));
        out.put("capture.declaredNatives", Integer.toString(MTLGoldenShim.declaredNatives().size()));
        Path module = moduleDirectory();
        for (Path source : nativeSources()) {
            out.put("capture.nativeSource." + module.resolve(NATIVE_SOURCES).relativize(source) + ".sha256",
                    MTLGoldenFormat.sha256Hex(source));
        }
        for (String name : MTLGoldenShim.CLASSES) {
            Path source = module.resolve(JAVA_SOURCES).resolve(name + ".java");
            out.put("capture.javaSource." + name + ".sha256", MTLGoldenFormat.sha256Hex(source));
        }
        out.put("info.java.version", System.getProperty("java.runtime.version"));
        String compiler = run(List.of("xcrun", "metal", "--version"));
        out.put("info.metal.compiler", compiler == null ? MTLGoldenFormat.NULL
                : compiler.lines().findFirst().orElse("").trim());
        out.put("info.env.removed", String.join(",", removedEnvironment()));
    }

    private static List<String> header(String title, String what) {
        List<String> lines = new ArrayList<>();
        lines.add(title + ": what the JNI build of commit " + CAPTURE_COMMIT + " (libprism_mtl.dylib, "
                + DECLARED_NATIVES + " natives) " + what);
        lines.add("through the Prism SPI in a child JVM with no window, before any of prism_mtl was rewritten.");
        lines.add("Tolerance: 0 per channel. The comparison is exact; any difference is a transport bug.");
        lines.add("Machine-specific: macOS build, dyld shared cache (Metal, MTLCompiler, AGX driver), GPU, Metal and");
        lines.add("driver versions, the shader library, the JDK, the Ahem font and font smoothing preferences. The");
        lines.add("comparison runs only where every machine.* key matches; elsewhere it is skipped, or failed under");
        lines.add("-Djfx.parity.require=true.");
        lines.add("");
        lines.add("Capture, in a tree with the C and Java of " + CAPTURE_COMMIT + ":");
        lines.add("  " + captureCommand());
        lines.add("Regenerate: add -D" + REGENERATE_PROPERTY + "=true (reviewed as a behaviour change).");
        lines.add("");
        lines.add("Keys:");
        lines.add("  machine.*    gate, computed without the library under test");
        lines.add("  capture.*    recorded, never compared (commit, sources, JNI library, linked natives,");
        lines.add("               determinism)");
        lines.add("  info.*       recorded, never compared (JDK, compiler, environment, device, producer)");
        lines.add("  <profile>.sNNNN.<group>.<case>=<w>x<h>;offset=<bytes>;sha256=<hex> a frame of " + PAYLOAD);
        lines.add("  <profile>.pipeline.*, .coverage.shaders, .decora.accelType, lifecycle.*, shader.*: compared");
        return lines;
    }

    private static void write(String name, byte[] content) throws IOException {
        Path module = moduleDirectory();
        write(module.resolve(RESOURCE_DIRECTORY), module.resolve(CLASSES_DIRECTORY), name, content,
              Boolean.getBoolean(REGENERATE_PROPERTY));
    }

    /**
     * Writes a golden to the source resources and to the test classpath copy, so that a verify in the same build sees
     * it; refuses to overwrite an existing golden unless {@code regenerate}.
     */
    static void write(Path resources, Path classes, String name, byte[] content, boolean regenerate)
            throws IOException {
        Path source = resources.resolve(name);
        if (Files.exists(source) && !regenerate) {
            fail("a golden already exists at " + source + ". It records what the JNI build rendered; overwriting it"
                    + " changes what the parity tests prove. If that is intended, re-run with -D"
                    + REGENERATE_PROPERTY + "=true in a JNI tree of " + CAPTURE_COMMIT + " and review the result as a"
                    + " behaviour change.");
        }
        Files.createDirectories(resources);
        Files.write(source, content);
        Files.createDirectories(classes);
        Files.write(classes.resolve(name), content);
        System.out.println("captured " + content.length + " bytes to " + source);
    }

    // ---------------------------------------------------------------------------------------------
    // Machine gate
    // ---------------------------------------------------------------------------------------------

    static synchronized Map<String, String> machine() throws IOException {
        if (machine == null) {
            TreeMap<String, String> keys = new TreeMap<>();
            keys.put("machine.os.name", System.getProperty("os.name"));
            keys.put("machine.os.arch", System.getProperty("os.arch"));
            Map<String, String> version = plist(Path.of("/System/Library/CoreServices/SystemVersion.plist"));
            keys.put("machine.os.productVersion", MTLGoldenFormat.nullable(version.get("ProductVersion")));
            keys.put("machine.os.build", MTLGoldenFormat.nullable(version.get("ProductBuildVersion")));
            Map<String, String> cryptex = plist(Path.of("/System/Volumes/Preboot/Cryptexes/OS/System/Library/"
                    + "CoreServices/SystemVersion.plist"));
            keys.put("machine.os.versionExtra", MTLGoldenFormat.nullable(cryptex.get("ProductVersionExtra")));
            keys.put("machine.dyld.sharedCache.uuid", MTLGoldenFormat.nullable(MacMachineShim.sharedCacheUuid()));
            String model = run(List.of("sysctl", "-n", "hw.model"));
            keys.put("machine.hw.model", model == null ? MTLGoldenFormat.NULL : model.trim());
            putGpu(keys);
            Map<String, String> metal = plist(Path.of("/System/Library/Frameworks/Metal.framework/Versions/A/"
                    + "Resources/version.plist"));
            keys.put("machine.metal.version", MTLGoldenFormat.nullable(metal.get("CFBundleVersion")));
            byte[] metallib = MTLGoldenShim.metallib();
            keys.put("machine.metallib.bytes", metallib == null ? MTLGoldenFormat.NULL
                    : Integer.toString(metallib.length));
            keys.put("machine.metallib.sha256", metallib == null ? MTLGoldenFormat.NULL
                    : MTLGoldenFormat.sha256Hex(metallib));
            keys.put("machine.java.vm.version", System.getProperty("java.vm.version"));
            Path ahem = moduleDirectory().resolve(AHEM).normalize();
            keys.put("machine.font.ahem.sha256", Files.isRegularFile(ahem) ? MTLGoldenFormat.sha256Hex(ahem)
                    : MTLGoldenFormat.NULL);
            for (String preference : List.of("AppleFontSmoothing", "CGFontRenderingFontSmoothingDisabled")) {
                keys.put("machine.prefs." + preference,
                         MTLGoldenFormat.nullable(MacMachineShim.preference(preference, false)));
                keys.put("machine.prefs." + preference + ".currentHost",
                         MTLGoldenFormat.nullable(MacMachineShim.preference(preference, true)));
            }
            machine = Collections.unmodifiableMap(keys);
        }
        return machine;
    }

    /** The accelerator {@code MTLCreateSystemDefaultDevice} wraps, read from the I/O registry, not from Metal. */
    private static void putGpu(Map<String, String> keys) {
        String listing = run(List.of("ioreg", "-r", "-d", "1", "-c", "IOAccelerator"));
        List<Map<String, String>> accelerators = new ArrayList<>();
        if (listing != null) {
            for (String line : listing.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("+-o ")) {
                    accelerators.add(new TreeMap<>());
                    continue;
                }
                Matcher entry = IOREG_ENTRY.matcher(trimmed);
                if (entry.matches() && !accelerators.isEmpty()) {
                    accelerators.get(accelerators.size() - 1).put(entry.group(1), entry.group(2).replace("\"", ""));
                }
            }
        }
        keys.put("machine.gpu.count", Integer.toString(accelerators.size()));
        Map<String, String> gpu = accelerators.isEmpty() ? Map.of() : accelerators.get(0);
        keys.put("machine.gpu.model", MTLGoldenFormat.nullable(gpu.get("model")));
        keys.put("machine.gpu.coreCount", MTLGoldenFormat.nullable(gpu.get("gpu-core-count")));
        keys.put("machine.gpu.ioClass", MTLGoldenFormat.nullable(gpu.get("IOClass")));
        keys.put("machine.gpu.ioNameMatched", MTLGoldenFormat.nullable(gpu.get("IONameMatched")));
        String plugin = gpu.get("MetalPluginName");
        keys.put("machine.gpu.metalPlugin", MTLGoldenFormat.nullable(plugin));
        keys.put("machine.gpu.metalPluginClass", MTLGoldenFormat.nullable(gpu.get("MetalPluginClassName")));
        Map<String, String> bundle = plugin == null ? Map.of()
                : plist(Path.of("/System/Library/Extensions", plugin + ".bundle", "Contents", "Info.plist"));
        keys.put("machine.gpu.driverVersion", MTLGoldenFormat.nullable(bundle.get("CFBundleVersion")));
    }

    static Map<String, String> plist(Path file) {
        Map<String, String> entries = new TreeMap<>();
        try {
            if (Files.isRegularFile(file)) {
                Matcher matcher = PLIST_ENTRY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    entries.put(matcher.group(1), matcher.group(2));
                }
            }
        } catch (IOException | UncheckedIOException e) {
            entries.put("unreadable", e.toString());
        }
        return entries;
    }

    // ---------------------------------------------------------------------------------------------
    // Child JVMs
    // ---------------------------------------------------------------------------------------------

    /** One pass of a child: its frame keys (offsets relative to the pass) and its raw frames. */
    record Pass(Map<String, String> frames, byte[] payload) {
    }

    /** The outcome of one child JVM. */
    record ChildRun(String profile, String run, int exitCode, Path work, Map<String, String> keys, List<Pass> passes,
                    List<String> jniNatives, List<Path> errorFiles, List<String> command) {

        String describe() {
            return "the " + profile + " child (run " + run + ") exited with " + exitCode + "; fatal error logs "
                    + errorFiles + "; failure: " + readOrNull(work.resolve(MTLGoldenChild.FAILURE_FILE))
                    + "\nlast stderr lines:\n" + tail(work.resolve("stderr.txt"), 40) + "\ncommand: " + command;
        }

        ChildRun requireSuccess() {
            if (exitCode != 0 || !errorFiles.isEmpty() || keys.isEmpty()) {
                fail(describe());
            }
            return this;
        }
    }

    static ChildRun runChild(String profile, String run, int passes) throws IOException, InterruptedException {
        if (blocked != null) {
            fail("BLOCKED: an earlier child failed its no-window proof (" + blocked + "), so no further child that"
                    + " could open a window or activate the application is launched in this JVM.");
        }
        Path work = moduleDirectory().resolve(WORK_DIRECTORY).resolve(profile).resolve(run);
        deleteTree(work);
        Files.createDirectories(work.resolve("tmp"));
        Files.createDirectories(work.resolve("hs_err"));
        List<String> command = childCommand(profile, work, passes);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(moduleDirectory().toFile())
                .redirectOutput(work.resolve("stdout.txt").toFile())
                .redirectError(work.resolve("stderr.txt").toFile());
        builder.environment().keySet().removeIf(MTLGoldens::removedFromChild);
        Process process = builder.start();
        try {
            if (!process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fail("the " + profile + " child did not finish within " + CHILD_TIMEOUT_SECONDS + " s (a blocking"
                        + " downcall bound critical shows up here); stderr:\n" + tail(work.resolve("stderr.txt"), 40));
            }
        } finally {
            destroy(process);
        }
        int exit = process.exitValue();
        Path proof = work.resolve(MTLGoldenChild.PROOF_FAILURE_FILE);
        if (exit == MTLGoldenChild.EXIT_PROOF_FAILED || Files.exists(proof)) {
            blocked = profile + "/" + run + ": " + readOrNull(proof);
            fail("BLOCKED: the " + profile + " child failed its no-window proof: " + readOrNull(proof)
                    + ". Nothing else is launched.");
        }
        Map<String, String> keys = Files.isRegularFile(work.resolve(MTLGoldenChild.CHILD_FILE))
                ? MTLGoldenFormat.parse(Files.readString(work.resolve(MTLGoldenChild.CHILD_FILE))) : Map.of();
        List<Pass> passList = new ArrayList<>();
        for (int pass = 1; Files.isRegularFile(work.resolve("pass-" + pass + ".txt")); pass++) {
            passList.add(new Pass(MTLGoldenFormat.parse(Files.readString(work.resolve("pass-" + pass + ".txt"))),
                                  Files.readAllBytes(work.resolve("pass-" + pass + ".bin"))));
        }
        List<Path> errors;
        try (Stream<Path> files = Files.list(work.resolve("hs_err"))) {
            errors = files.sorted().toList();
        }
        return new ChildRun(profile, run, exit, work, keys, passList, linkedNatives(work.resolve("jni-resolve.log")),
                            errors, command);
    }

    private static List<String> childCommand(String profile, Path work, int passes) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!droppedFromChild(argument)) {
                command.add(argument);
            }
        }
        command.addAll(List.of("-Dprism.order=mtl", "-Dprism.verbose=true", "-Djavafx.verbose=true",
                "-Dglass.platform=Headless", "-Djava.awt.headless=true"));
        command.addAll(PROFILE_FLAGS.get(profile));
        command.add("-Xlog:jni+resolve=debug:file=" + work.resolve("jni-resolve.log"));
        command.add("-XX:ErrorFile=" + work.resolve("hs_err").resolve("hs_err_pid%p.log"));
        command.add("-Djava.io.tmpdir=" + work.resolve("tmp"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(MTLGoldenChild.class.getName());
        command.add(profile);
        command.add(work.toString());
        command.add(Integer.toString(passes));
        return command;
    }

    static boolean droppedFromChild(String argument) {
        return argument.startsWith("-agentlib") || argument.startsWith("-javaagent")
                || argument.startsWith("-Xrunjdwp") || argument.startsWith("-Xdebug")
                || argument.startsWith("-XX:ErrorFile") || argument.startsWith("-Dglass.platform")
                || argument.startsWith("-Dprism.") || argument.startsWith("-Djava.awt.headless")
                || argument.startsWith("-Djava.io.tmpdir") || argument.startsWith("-Djfx.mtl.");
    }

    private static boolean removedFromChild(String name) {
        return REMOVED_ENVIRONMENT_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static List<String> removedEnvironment() {
        List<String> removed = new ArrayList<>();
        for (String name : System.getenv().keySet()) {
            if (removedFromChild(name)) {
                removed.add(name);
            }
        }
        Collections.sort(removed);
        return removed;
    }

    /** {@code Class.method} of every {@code com.sun.prism.mtl} native HotSpot linked, sorted. */
    static List<String> linkedNatives(Path log) throws IOException {
        Set<String> natives = new TreeSet<>();
        if (Files.isRegularFile(log)) {
            Matcher matcher = JNI_LINK.matcher(Files.readString(log, StandardCharsets.UTF_8));
            while (matcher.find()) {
                natives.add(matcher.group(1) + "." + matcher.group(2));
            }
        }
        return List.copyOf(natives);
    }

    /**
     * The lifecycle child's outcome: the teardown steps it completed, how it exited and, if it died, the signal and
     * problematic frame of its fatal error log (decision D5: a JNI crash in L1/L3 is the expected outcome).
     */
    static Map<String, String> lifecycleOutcome(ChildRun run) throws IOException {
        GoldenMap outcome = new GoldenMap();
        Path progress = run.work().resolve(MTLCorpus3D.LIFECYCLE_PROGRESS);
        if (Files.isRegularFile(progress)) {
            outcome.putAll(MTLGoldenFormat.parse(Files.readString(progress, StandardCharsets.UTF_8)));
        }
        run.keys().forEach((key, value) -> {
            if (!outcome.containsKey(key)) {
                outcome.put(key, value);
            }
        });
        outcome.put(LIFECYCLE + ".exit", Integer.toString(run.exitCode()));
        outcome.put(LIFECYCLE + ".hsErr", run.errorFiles().isEmpty() ? "none"
                : crashSignature(run.errorFiles().get(0)));
        return outcome;
    }

    /** Differences between pass {@code passA} of {@code a} and pass {@code passB} of {@code b}, bytes included. */
    static List<String> identical(ChildRun a, int passA, ChildRun b, int passB) {
        Map<String, String> left = new TreeMap<>(bodyOf(a.keys()));
        left.putAll(a.passes().get(passA - 1).frames());
        Map<String, String> right = new TreeMap<>(bodyOf(b.keys()));
        right.putAll(b.passes().get(passB - 1).frames());
        List<String> differences = MTLGoldenFormat.differences(left, right, key -> true);
        if (!Arrays.equals(a.passes().get(passA - 1).payload(), b.passes().get(passB - 1).payload())) {
            differences.add("the raw frames differ");
        }
        return differences;
    }

    static List<Path> nativeSources() throws IOException {
        try (Stream<Path> files = Files.walk(moduleDirectory().resolve(NATIVE_SOURCES))) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    static Set<String> jniExports(Path library) {
        String listing = run(List.of("nm", "-gUj", library.toString()));
        if (listing == null) {
            fail("cannot list the symbols of " + library + " with nm");
        }
        Set<String> symbols = new TreeSet<>();
        for (String symbol : listing.split("\n")) {
            if (symbol.startsWith("_Java_com_sun_prism_mtl_")) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    private static String join(List<String> values) {
        return values.size() + ":" + String.join(",", values);
    }

    // ---------------------------------------------------------------------------------------------
    // Paths and commands
    // ---------------------------------------------------------------------------------------------

    /** Surefire runs with the module directory as its working directory; checked rather than assumed. */
    static Path moduleDirectory() {
        Path directory = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(directory.resolve("pom.xml"))
                || !Files.isDirectory(directory.resolve("src/test/resources"))) {
            fail("expected the working directory to be modules/javafx.graphics but it is " + directory);
        }
        return directory;
    }

    static Path repositoryDirectory() {
        return moduleDirectory().resolve("../..").normalize();
    }

    /** stdout of a read-only command that exits 0 within its time limit, or {@code null}. */
    static String run(List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            return null;
        }
        try {
            FutureTask<byte[]> stdout = new FutureTask<>(process.getInputStream()::readAllBytes);
            Thread.ofVirtual().start(stdout);
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            byte[] output = stdout.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return process.exitValue() == 0 ? new String(output, StandardCharsets.UTF_8) : null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            destroy(process);
        }
    }

    private static int exitCode(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                return process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) ? process.exitValue() : -1;
            } finally {
                destroy(process);
            }
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void destroy(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String readOrNull(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return "(unreadable: " + e + ")";
        }
    }

    static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException | UncheckedIOException e) {
            return "(unreadable: " + e + ")";
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Collections.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Capture-time checks
    // ---------------------------------------------------------------------------------------------

    /**
     * Designed-equal frames are equal, designed-distinct frames pairwise distinct, and no frame is uniform unless its
     * step declares it: a corpus that cannot tell a right transport from a wrong one is no golden.
     */
    static void requireDiscriminating(String profile, Pass pass) {
        MTLGoldenChild.Steps steps = MTLGoldenChild.Steps.forProfile(profile);
        Map<String, FrameRef> frames = new TreeMap<>();
        List<MTLGoldenChild.Step> list = steps.steps();
        for (int index = 0; index < list.size(); index++) {
            String key = frameKey(profile, index, list.get(index).key());
            FrameRef frame = MTLGoldenFormat.frame(pass.frames().getOrDefault(key, ""));
            if (frame == null) {
                fail("refusing to capture: no frame for " + key);
            }
            frames.put(list.get(index).key(), frame);
            if (!list.get(index).uniform()
                    && MTLGoldenFormat.uniform(MTLGoldenFormat.framePixels(pass.payload(), frame))) {
                fail("refusing to capture: " + key + " is a uniform frame, so it cannot detect a transport bug;"
                        + " change the step or declare it uniform");
            }
        }
        for (List<String> set : steps.equalSets()) {
            Set<String> hashes = new TreeSet<>();
            set.forEach(key -> hashes.add(frames.get(key).sha256()));
            if (hashes.size() != 1) {
                fail("refusing to capture: the " + profile + " steps " + set + " are designed to render equal frames"
                        + " and do not");
            }
        }
        for (List<String> set : steps.distinctSets()) {
            Set<String> hashes = new TreeSet<>();
            set.forEach(key -> hashes.add(frames.get(key).sha256()));
            if (hashes.size() != set.size()) {
                fail("refusing to capture: the " + profile + " steps " + set + " are designed to render pairwise"
                        + " distinct frames and " + (set.size() - hashes.size()) + " coincide");
            }
        }
    }

    /** The default profile must create every shader except the written-down unreachable ones. */
    static void requireShaderCoverage(String coverage) {
        Set<String> created = new TreeSet<>(List.of(coverage.substring(coverage.indexOf(':') + 1).split(",")));
        Set<String> expected = new TreeSet<>(MTLShaderTableGoldenTest.shaderNames());
        expected.removeAll(MTLCorpus2D.UNREACHABLE_SHADERS.keySet());
        if (!created.equals(expected)) {
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(created);
            Set<String> extra = new TreeSet<>(created);
            extra.removeAll(expected);
            fail("refusing to capture: the default corpus does not create exactly the reachable shaders; missing "
                    + missing.size() + " " + missing + ", unexpected " + extra);
        }
    }

    /** The union over the render children must link every declared native but the {@link #UNLINKED_NATIVES}. */
    static void requireNativeCoverage(Set<String> linked) {
        Set<String> expected = new TreeSet<>();
        for (String declared : MTLGoldenShim.declaredNatives()) {
            expected.add(declared.substring(0, declared.indexOf('(')));
        }
        expected.removeAll(UNLINKED_NATIVES);
        if (!linked.equals(expected) || expected.size() != DECLARED_NATIVES - UNLINKED_NATIVES.size()) {
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(linked);
            Set<String> extra = new TreeSet<>(linked);
            extra.removeAll(expected);
            fail("refusing to capture: the children linked " + linked.size() + " natives, expected "
                    + expected.size() + "; never linked " + missing + ", unexpected " + extra);
        }
    }

    static void requireShaderTables(Map<String, String> keys) {
        int all = MTLShaderTableGoldenTest.shaderNames().size();
        if (!"212".equals(keys.get("shader.count.prism")) || !"50".equals(keys.get("shader.count.decora"))
                || !Integer.toString(all).equals(keys.get("shader.count.created"))) {
            fail("refusing to capture: expected 212 prism and 50 Decora shaders, all created; got "
                    + keys.get("shader.count.prism") + ", " + keys.get("shader.count.decora") + ", "
                    + keys.get("shader.count.created"));
        }
    }

    /** The signal and problematic frame of a fatal error log, without the per-process addresses. */
    static String crashSignature(Path errorLog) throws IOException {
        List<String> lines = Files.readAllLines(errorLog, StandardCharsets.UTF_8);
        String signal = MTLGoldenFormat.NULL;
        String frame = MTLGoldenFormat.NULL;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = CRASH_SIGNAL.matcher(line);
            if (signal.equals(MTLGoldenFormat.NULL) && matcher.find()) {
                signal = matcher.group(1);
            }
            if (line.startsWith("# Problematic frame:") && i + 1 < lines.size()) {
                frame = lines.get(i + 1).replaceFirst("^#\\s*", "").replaceAll("\\s+", " ").trim();
            }
        }
        return signal + ";" + frame;
    }
}
