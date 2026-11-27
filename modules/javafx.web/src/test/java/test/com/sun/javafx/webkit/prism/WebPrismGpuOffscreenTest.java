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

package test.com.sun.javafx.webkit.prism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.javafx.PlatformUtil;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import test.com.sun.javafx.webkit.prism.WebPrismGpuOffscreenChild.Pipeline;

/**
 * What the web graphics code does on a real GPU pipeline, checked offscreen on this Mac's GPU, once on Metal and
 * once on ES2 (OpenGL). The module's other tests run on software Prism, where no draw is ever pending in a vertex
 * buffer, so neither the read-back nor the clear of a reused texture can overtake one, and the perspective of the
 * shared device context is seen only through recording graphics. This suite runs the same paths on GPU pipelines.
 * <p>
 * For each pipeline a child JVM runs Headless glass with that pipeline as the default, as production sets it up,
 * so the images, the shared texture cache and the graphics contexts all live on it. A GPU crash kills only the
 * child, and the software pipeline of this JVM is never touched. The child opens no window. Each pipeline is
 * gated on its own: one that this machine cannot run skips, or fails under {@code -Djfx.parity.require=true},
 * only its own tests.
 */
@Timeout(300)
public class WebPrismGpuOffscreenTest {

    private static final long CHILD_TIMEOUT_SECONDS = 120;
    private static final String WORK_DIRECTORY = "target/web-gpu-offscreen";
    private static final String REQUIRE_PROPERTY = "jfx.parity.require";
    private static final List<String> REMOVED_ENVIRONMENT_PREFIXES = List.of("MTL_", "METAL_", "OBJC_", "DYLD_");

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class OnMetal extends Scenarios {
        OnMetal() {
            super(Pipeline.METAL);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class OnEs2 extends Scenarios {
        OnEs2() {
            super(Pipeline.ES2);
        }
    }

    abstract static class Scenarios {
        private final Pipeline pipeline;
        private Properties results;

        Scenarios(Pipeline pipeline) {
            this.pipeline = pipeline;
        }

        @BeforeAll
        @Timeout(300)
        void runTheChild() throws IOException, InterruptedException {
            assumeTrue(PlatformUtil.isMac(), "the GPU suite runs on macOS only; nothing to test on this platform");
            GpuOffscreenGate.require(pipeline);
            results = WebPrismGpuOffscreenTest.runTheChild(pipeline);
        }

        @Test
        public void readContentReadsTheWholeContentOfAGpuTextureAsDrawn() {
            assertPassed(WebPrismGpuOffscreenChild.READ_CONTENT);
        }

        @Test
        public void aPrintOfAGpuImageOfScaleOneAndTwoHasAllItsPixels() {
            assertPassed(WebPrismGpuOffscreenChild.PRINT_SCALES);
        }

        @Test
        public void aPartOfAGpuImagePrintsThatPart() {
            assertPassed(WebPrismGpuOffscreenChild.PRINT_PART);
        }

        @Test
        public void aGpuImageDrawnIntoJava2DGraphicsIsCopied() {
            assertPassed(WebPrismGpuOffscreenChild.JAVA2D_COPY);
        }

        @Test
        public void aProjectedFillLandsWhereTDotPPutsIt() {
            assertPassed(WebPrismGpuOffscreenChild.PROJECTED_FILL);
        }

        @Test
        public void aProjectedImageLandsWhereTDotPPutsIt() {
            assertPassed(WebPrismGpuOffscreenChild.PROJECTED_IMAGE);
        }

        @Test
        public void atScaleTwoTheFoldStillPlacesTheFill() {
            assertPassed(WebPrismGpuOffscreenChild.SCALED_FOLD);
        }

        @Test
        public void atScaleTwoTheFoldStillPlacesTheImage() {
            assertPassed(WebPrismGpuOffscreenChild.SCALED_IMAGE_FOLD);
        }

        @Test
        public void aProjectedDrawInsideAClipLayerAndATransparencyLayerLandsWhereThePerspectivePutsIt() {
            assertPassed(WebPrismGpuOffscreenChild.LAYERED_FOLD);
        }

        @Test
        public void aNestedDecodeDoesNotLeaveItsPerspectiveForTheNextDraw() {
            assertPassed(WebPrismGpuOffscreenChild.NESTED_DECODE);
        }

        @Test
        public void aNestedDecodeAtTheStartOfABufferDrawsWithItsOwnPerspective() {
            assertPassed(WebPrismGpuOffscreenChild.NESTED_DECODE_AT_START);
        }

        @Test
        public void aDecodeAndADisposeLeaveTheIdentityInTheDevice() {
            assertPassed(WebPrismGpuOffscreenChild.IDENTITY_LEFT);
        }

        @Test
        public void aReusedTextureIsClearedOfWhatItHeld() {
            assertPassed(WebPrismGpuOffscreenChild.REUSED_CLEARED);
        }

        @Test
        public void disposingCachedTexturesFreesThem() {
            assertPassed(WebPrismGpuOffscreenChild.CACHE_DISPOSAL);
        }

        @Test
        public void aDisposedImagesTextureIsReusedClearedAfterAPoolSweep() {
            assertPassed(WebPrismGpuOffscreenChild.IMAGE_REUSE);
        }

        @Test
        public void aPixelBufferDrawnOverAPendingFillReplacesIt() {
            assertPassed(WebPrismGpuOffscreenChild.PIXEL_BUFFER);
        }

        @Test
        public void aRotatedImageShowsNothingOfItsOppositeEdges() {
            assertPassed(WebPrismGpuOffscreenChild.ROTATED_EDGE);
        }

        private void assertPassed(String scenario) {
            assertEquals(WebPrismGpuOffscreenChild.PASS, results.getProperty(WebPrismGpuOffscreenChild.SCENARIO_PREFIX
                    + scenario), "what the " + pipeline + " child recorded for " + scenario);
        }
    }

    private static Properties runTheChild(Pipeline pipeline) throws IOException, InterruptedException {
        Path work = workDirectory().resolve(pipeline.order());
        deleteTree(work);
        Files.createDirectories(work.resolve("hs_err"));
        Files.createDirectories(work.resolve("tmp"));
        List<String> command = childCommand(work, pipeline);
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(work.resolve("stdout.txt").toFile())
                .redirectError(work.resolve("stderr.txt").toFile());
        builder.environment().keySet()
                .removeIf(name -> REMOVED_ENVIRONMENT_PREFIXES.stream().anyMatch(name::startsWith));
        Process child = builder.start();
        try {
            if (!child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fail("the " + pipeline + " child did not finish within " + CHILD_TIMEOUT_SECONDS + " s, in scenario "
                        + scenarioInFlight(work) + "; last stderr lines:\n" + tail(work.resolve("stderr.txt")));
            }
        } finally {
            destroy(child);
        }
        Properties recorded = readResults(work);
        List<Path> errorFiles;
        try (Stream<Path> files = Files.list(work.resolve("hs_err"))) {
            errorFiles = files.sorted().toList();
        }
        if (child.exitValue() != 0 || !errorFiles.isEmpty() || recorded.isEmpty()) {
            fail("the " + pipeline + " child exited with " + child.exitValue() + " in scenario "
                    + scenarioInFlight(work) + "; fatal error logs " + errorFiles + "; results " + recorded
                    + "\nlast stderr lines:\n" + tail(work.resolve("stderr.txt")) + "\ncommand: " + command);
        }
        requireThePipeline(pipeline, recorded);
        return recorded;
    }

    private static void requireThePipeline(Pipeline pipeline, Properties recorded) {
        String got = recorded.getProperty(WebPrismGpuOffscreenChild.PIPELINE_KEY);
        if (pipeline.pipelineClass().equals(got)) {
            return;
        }
        String error = recorded.getProperty(WebPrismGpuOffscreenChild.PIPELINE_ERROR_KEY);
        if (error != null) {
            fail("the " + pipeline + " library is reachable but does not work: the child got the pipeline " + got
                    + ", and starting " + pipeline + " itself threw " + error);
        }
        if (!"false".equals(recorded.getProperty(WebPrismGpuOffscreenChild.PIPELINE_INIT_KEY))) {
            fail("the " + pipeline + " child got the pipeline " + got + " and recorded no reason " + pipeline
                    + " was not used: " + recorded);
        }
        String noDevice = "no " + pipeline + " device was found: the child got the pipeline " + got + " and "
                + pipeline.pipelineClass() + " could not be initialised.";
        if (Boolean.getBoolean(REQUIRE_PROPERTY)) {
            fail("-D" + REQUIRE_PROPERTY + "=true says this machine runs the " + pipeline + " checks, and "
                    + noDevice);
        }
        abort(noDevice);
    }

    private static List<String> childCommand(Path work, Pipeline pipeline) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!droppedFromChild(argument)) {
                command.add(argument);
            }
        }
        // With ",sw" a machine without the device starts on software Prism instead of failing to start.
        command.addAll(List.of("-Dprism.order=" + pipeline.order() + ",sw", "-Dglass.platform=Headless",
                "-Djava.awt.headless=true", "-Dprism.verbose=true"));
        command.add("-XX:ErrorFile=" + work.resolve("hs_err").resolve("hs_err_pid%p.log"));
        command.add("-Djava.io.tmpdir=" + work.resolve("tmp"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(WebPrismGpuOffscreenChild.class.getName());
        command.add(work.toString());
        command.add(pipeline.name());
        return command;
    }

    private static boolean droppedFromChild(String argument) {
        return argument.startsWith("-agentlib") || argument.startsWith("-javaagent")
                || argument.startsWith("-Xrunjdwp") || argument.startsWith("-Xdebug")
                || argument.startsWith("-XX:ErrorFile") || argument.startsWith("-Dglass.platform")
                || argument.startsWith("-Dprism.") || argument.startsWith("-Djava.awt.headless")
                || argument.startsWith("-Djava.io.tmpdir") || argument.startsWith("-Djfx.mtl.");
    }

    private static Path workDirectory() {
        Path module = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(module.resolve("pom.xml")) || !module.endsWith(Path.of("modules", "javafx.web"))) {
            fail("expected the working directory to be modules/javafx.web but it is " + module);
        }
        return module.resolve(WORK_DIRECTORY);
    }

    private static void deleteTree(Path work) throws IOException {
        if (!Files.exists(work)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(work)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Properties readResults(Path work) throws IOException {
        Properties recorded = new Properties();
        Path file = work.resolve(WebPrismGpuOffscreenChild.RESULTS_FILE);
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                recorded.load(in);
            }
        }
        return recorded;
    }

    private static String scenarioInFlight(Path work) {
        Path progress = work.resolve(WebPrismGpuOffscreenChild.PROGRESS_FILE);
        if (!Files.isRegularFile(progress)) {
            return "(none started)";
        }
        List<String> started = lines(progress);
        return started.isEmpty() ? "(none started)" : started.getLast();
    }

    private static String tail(Path file) {
        List<String> all = lines(file);
        return String.join("\n", all.subList(Math.max(0, all.size() - 40), all.size()));
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            return List.of("(unreadable: " + e + ")");
        }
    }

    private static void destroy(Process process) throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
    }
}
