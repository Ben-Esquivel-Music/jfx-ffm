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

package test.com.sun.javafx.font.coretext;

import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.coretext.CFNative;
import com.sun.javafx.font.coretext.CFNativeShim;
import com.sun.javafx.font.coretext.CGNativeShim;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static com.sun.javafx.font.MacFontOracleShim.CG_AFFINE_TRANSFORM;
import static com.sun.javafx.font.MacFontOracleShim.CG_PATH_ELEMENT;
import static com.sun.javafx.font.MacFontOracleShim.CG_RECT;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** {@code CGNative} on any macOS: registry, descriptors, layouts, contexts, fonts, struct returns and the upcall. */
@EnabledOnOs(OS.MAC)
public class CGNativeTest {

    /** From the SDK prototypes; {@code bool} is {@code JAVA_BOOLEAN}, {@code CGFloat} is {@code JAVA_DOUBLE}. */
    static final Map<String, FunctionDescriptor> SIGNATURES = signatures(
            "CGBitmapContextCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG,
                                                           JAVA_LONG, ADDRESS, JAVA_INT),
            "CGBitmapContextGetBitsPerPixel", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CGBitmapContextGetBytesPerRow", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CGBitmapContextGetData", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CGBitmapContextGetHeight", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CGBitmapContextGetWidth", FunctionDescriptor.of(JAVA_LONG, ADDRESS),
            "CGColorSpaceCreateDeviceGray", FunctionDescriptor.of(ADDRESS),
            "CGColorSpaceCreateDeviceRGB", FunctionDescriptor.of(ADDRESS),
            "CGContextFillRect", FunctionDescriptor.ofVoid(ADDRESS, CG_RECT),
            "CGContextRelease", FunctionDescriptor.ofVoid(ADDRESS),
            "CGContextSetAllowsAntialiasing", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN),
            "CGContextSetAllowsFontSmoothing", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN),
            "CGContextSetAllowsFontSubpixelPositioning", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN),
            "CGContextSetAllowsFontSubpixelQuantization", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN),
            "CGContextSetRGBFillColor", FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                                                                  JAVA_DOUBLE),
            "CGContextTranslateCTM", FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE),
            "CGDataProviderCreateWithURL", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CGFontCreateWithDataProvider", FunctionDescriptor.of(ADDRESS, ADDRESS),
            "CGPathApply", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
            "CGPathGetPathBoundingBox", FunctionDescriptor.of(CG_RECT, ADDRESS),
            "CGPathRelease", FunctionDescriptor.ofVoid(ADDRESS),
            "CGRectApplyAffineTransform", FunctionDescriptor.of(CG_RECT, CG_RECT, CG_AFFINE_TRANSFORM));

    static Map<String, FunctionDescriptor> signatures(Object... namesAndDescriptors) {
        Map<String, FunctionDescriptor> signatures = new LinkedHashMap<>();
        for (int i = 0; i < namesAndDescriptors.length; i += 2) {
            signatures.put("CoreGraphics!" + namesAndDescriptors[i], (FunctionDescriptor) namesAndDescriptors[i + 1]);
        }
        return signatures;
    }

    /** Every element type, exact double bits included: -0.0, a denormal, and values float cannot hold. */
    static final double[][] ALL_ELEMENTS = {
        {0, -0.0, 1e300}, {1, Double.MIN_VALUE, -1e-300}, {2, 15, 5, 10, 10}, {3, 5, 15, 0, 15, -5, 10}, {4},
        {0, 20, 20}, {1, 21.5, -3.25}, {4},
    };

    static final List<String> ALL_EVENTS = List.of(
            "move -0.0 1.0E300", "line 4.9E-324 -1.0E-300", "quad 15.0 5.0 10.0 10.0",
            "curve 5.0 15.0 0.0 15.0 -5.0 10.0", "close", "move 20.0 20.0", "line 21.5 -3.25", "close");

    @Test
    void everyFunctionIsBoundOnceWithItsSdkDescriptor() {
        assertEquals(new ArrayList<>(SIGNATURES.entrySet()), new ArrayList<>(CGNativeShim.boundFunctions().entrySet()));
        assertEquals(List.of("CoreGraphics!CGAffineTransformIdentity"), CGNativeShim.boundData());
    }

    @Test
    void theIdentityIsPassedByItsAddress() {
        assertEquals(MacFontOracleShim.dataSymbol("CGAffineTransformIdentity"), CGNativeShim.identity().address());
        assertArrayEquals(new double[] {1, 0, 0, 1, 0, 0}, MacFontOracleShim.identityValues());
    }

    @Test
    void layoutsHaveTheSdkSizesAndOffsets() {
        assertEquals(16, CGNativeShim.layout("CGPoint").byteSize());
        assertEquals(16, CGNativeShim.layout("CGSize").byteSize());
        assertEquals(32, CGNativeShim.layout("CGRect").byteSize());
        assertEquals(48, CGNativeShim.layout("CGAffineTransform").byteSize());
        assertEquals(16, CGNativeShim.layout("CGPathElement").byteSize());
        assertEquals(MacFontOracleShim.CG_PATH_ELEMENT, CGNativeShim.layout("CGPathElement"));
        assertEquals(MacFontOracleShim.CG_AFFINE_TRANSFORM, CGNativeShim.layout("CGAffineTransform"));
    }

    @Test
    void bitmapContextsAreCreatedReadAndDrawnInto() {
        MemorySegment gray = CGNativeShim.CGColorSpaceCreateDeviceGray();
        MemorySegment rgb = CGNativeShim.CGColorSpaceCreateDeviceRGB();
        MemorySegment context = CGNativeShim.CGBitmapContextCreate(MemorySegment.NULL, 40, 30, 8, 64, gray, 0);
        long[] oracle = MacFontOracleShim.bitmapContextInfo(context.address());
        assertArrayEquals(new long[] {40, 30, 64, 8}, Arrays.copyOf(oracle, 4));
        assertEquals(40, CGNativeShim.CGBitmapContextGetWidth(context));
        assertEquals(30, CGNativeShim.CGBitmapContextGetHeight(context));
        assertEquals(64, CGNativeShim.CGBitmapContextGetBytesPerRow(context));
        assertEquals(8, CGNativeShim.CGBitmapContextGetBitsPerPixel(context));
        for (boolean allows : new boolean[] {true, false}) {
            CGNativeShim.CGContextSetAllowsAntialiasing(context, allows);
            CGNativeShim.CGContextSetAllowsFontSmoothing(context, allows);
            CGNativeShim.CGContextSetAllowsFontSubpixelPositioning(context, allows);
            CGNativeShim.CGContextSetAllowsFontSubpixelQuantization(context, allows);
        }
        CGNativeShim.CGContextTranslateCTM(context, 2.5, -1);
        assertArrayEquals(new double[] {1, 0, 0, 1, 2.5, -1}, MacFontOracleShim.contextCtm(context.address()));
        CGNativeShim.CGContextTranslateCTM(context, -2.5, 1);
        MemorySegment pixels = CGNativeShim.CGBitmapContextGetData(context, 64 * 30);
        assertEquals(64 * 30, pixels.byteSize());
        try (Arena scratch = Arena.ofConfined()) {
            CGNativeShim.CGContextSetRGBFillColor(context, 1, 1, 1, 1);
            CGNativeShim.CGContextFillRect(context, rect(scratch, 0, 0, 4, 2));
        }
        byte[] bytes = pixels.toArray(JAVA_BYTE);
        assertEquals((byte) 0xFF, bytes[28 * 64 + 3], "bottom-left rows are filled");
        assertEquals(0, bytes[28 * 64 + 4], "pixels right of the rect stay clear");
        assertEquals(0, bytes[27 * 64], "rows above the rect stay clear");
        CGNativeShim.CGContextRelease(context);
        CFNative.CFRelease(rgb);
        CFNative.CFRelease(gray);
    }

    @Test
    void aFontFileBecomesAGraphicsFont() throws IOException {
        Path ahem = Path.of("../javafx.web/src/main/native/Tools/DumpRenderTree/fonts/AHEM____.TTF")
                .toAbsolutePath().normalize();
        MemorySegment path = CFNative.toCFString(ahem.toString());
        MemorySegment url = CFNativeShim.CFURLCreateWithFileSystemPath(CFNativeShim.kCFAllocatorDefault(), path,
                CFNative.kCFURLPOSIXPathStyle, false);
        MemorySegment provider = CGNativeShim.CGDataProviderCreateWithURL(url);
        assertEquals(Files.size(ahem), MacFontOracleShim.dataProviderLength(provider.address()));
        MemorySegment font = CGNativeShim.CGFontCreateWithDataProvider(provider);
        assertEquals("Ahem", MacFontOracleShim.cgFontPostScriptName(font.address()));
        for (MemorySegment object : List.of(font, provider, url, path)) {
            CFNative.CFRelease(object);
        }
    }

    @Test
    void rectsCrossByValueBothWays() {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment transform = scratch.allocateFrom(JAVA_DOUBLE, 2, 0.5, -0.25, 3, 10, 20);
            MemorySegment result = CGNativeShim.CGRectApplyAffineTransform(scratch, rect(scratch, 1, 2, 3, 4),
                                                                           transform);
            assertArrayEquals(new double[] {10.5, 26.5, 7, 13.5}, result.toArray(JAVA_DOUBLE));
            long path = MacFontOracleShim.createPath(new double[] {0, 1, 2}, new double[] {1, 4, 2},
                                                     new double[] {1, 4, 6}, new double[] {4});
            MemorySegment box = CGNativeShim.CGPathGetPathBoundingBox(scratch, MemorySegment.ofAddress(path));
            assertArrayEquals(new double[] {1, 2, 3, 4}, box.toArray(JAVA_DOUBLE));
            CGNativeShim.CGPathRelease(MemorySegment.ofAddress(path));
        }
    }

    @Test
    void theApplierDeliversEveryElementInOrderWithExactBits() {
        long path = MacFontOracleShim.createPath(ALL_ELEMENTS);
        Recorder recorder = new Recorder();
        CGNativeShim.CGPathApply(MemorySegment.ofAddress(path), recorder);
        assertEquals(ALL_EVENTS, recorder.events);
        assertEquals(Double.doubleToRawLongBits(-0.0), recorder.firstBits);
        assertFalse(CGNativeShim.sinkInstalled(), "the slot is cleared after the call");
        MacFontOracleShim.releasePath(path);
    }

    @Test
    void aSinkThatAppliesAnotherPathStillGetsAllOfItsOwn() {
        long outer = MacFontOracleShim.createPath(ALL_ELEMENTS);
        long inner = MacFontOracleShim.createPath(new double[] {0, 7, 8}, new double[] {4});
        Recorder nested = new Recorder();
        Recorder recorder = new Recorder() {
            @Override
            public void moveTo(double x, double y) {
                super.moveTo(x, y);
                CGNativeShim.CGPathApply(MemorySegment.ofAddress(inner), nested);
            }
        };
        CGNativeShim.CGPathApply(MemorySegment.ofAddress(outer), recorder);
        assertEquals(ALL_EVENTS, recorder.events);
        assertEquals(List.of("move 7.0 8.0", "close", "move 7.0 8.0", "close"), nested.events);
        assertFalse(CGNativeShim.sinkInstalled(), "the slot is cleared after the outer call");
        MacFontOracleShim.releasePath(inner);
        MacFontOracleShim.releasePath(outer);
    }

    /**
     * Elements CoreGraphics never makes, handed to the stub from inside a real {@code CGPathApply} so that a sink is
     * installed. A failure empties the slot, so neither the hand-made element after it nor the rest of the real
     * path reaches the sink.
     */
    @Test
    void theApplierReportsUnknownTypesClosesWithoutPointsAndFailsOnMissingPoints() {
        long path = MacFontOracleShim.createPath(new double[] {0, 1, 2}, new double[] {1, 3, 4}, new double[] {4});
        try (Arena scratch = Arena.ofConfined()) {
            List<MemorySegment> handMade = List.of(element(scratch, 5), element(scratch, 4), element(scratch, 0),
                                                   element(scratch, 1, 5, 6));
            Recorder recorder = new Recorder() {
                @Override
                public void moveTo(double x, double y) {
                    super.moveTo(x, y);
                    handMade.forEach(CGNativeShim::applyElement);
                }
            };
            CGNativeShim.CGPathApply(MemorySegment.ofAddress(path), recorder);
            assertEquals(4, recorder.events.size(), "events " + recorder.events);
            assertEquals(List.of("move 1.0 2.0", "unknown 5", "close"), recorder.events.subList(0, 3));
            assertTrue(recorder.events.get(3).startsWith("failed java.lang.IndexOutOfBoundsException"),
                       recorder.events.get(3));
        }
        assertFalse(CGNativeShim.sinkInstalled(), "the slot is cleared after the call");
        MacFontOracleShim.releasePath(path);
    }

    /** A {@code CGPathElement}; without points its {@code points} is NULL. */
    static MemorySegment element(Arena arena, int type, double... points) {
        MemorySegment element = arena.allocate(CG_PATH_ELEMENT);
        element.set(JAVA_INT, 0, type);
        element.set(ADDRESS, 8, points.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_DOUBLE, points));
        return element;
    }

    @Test
    void oneStubServesEveryCallOnEveryThread() throws Exception {
        long stub = CGNativeShim.pathApplierStub().address();
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<List<String>>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory())) {
            for (int t = 0; t < threads; t++) {
                int id = t;
                results.add(executor.submit(() -> {
                    long path = MacFontOracleShim.createPath(new double[] {0, id, -id}, new double[] {1, id, id * 2},
                                                             new double[] {4});
                    List<String> expected = List.of("move " + (double) id + " " + (double) -id,
                                                    "line " + (double) id + " " + (double) (id * 2), "close");
                    List<String> problems = new ArrayList<>();
                    start.await();
                    for (int round = 0; round < 500; round++) {
                        Recorder recorder = new Recorder();
                        CGNativeShim.CGPathApply(MemorySegment.ofAddress(path), recorder);
                        if (!recorder.events.equals(expected)) {
                            problems.add(id + ": " + recorder.events);
                        }
                    }
                    if (CGNativeShim.pathApplierStub().address() != stub) {
                        problems.add(id + ": another stub");
                    }
                    MacFontOracleShim.releasePath(path);
                    return problems;
                }));
            }
            for (Future<List<String>> result : results) {
                assertEquals(List.of(), result.get());
            }
        }
    }

    @Test
    @Tag("crash-prone")
    void aSinkThatThrowsIsToldAndTheJvmSurvives() throws Exception {
        Map<String, String> result = runChild(ThrowingSinkChild.class);
        assertEquals("java.lang.IllegalStateException: second element", result.get("failed"));
        assertEquals("move -0.0 1.0E300", result.get("delivered"));
        assertEquals("0", result.get("afterFailure"));
        assertEquals("false", result.get("sinkInstalled"));
        assertEquals("move 20.0 20.0;line 21.5 -3.25;close", result.get("nextCall"));
    }

    static MemorySegment rect(Arena arena, double x, double y, double width, double height) {
        return arena.allocateFrom(JAVA_DOUBLE, x, y, width, height);
    }

    static class Recorder implements CGNativeShim.Sink {
        final List<String> events = new CopyOnWriteArrayList<>();
        long firstBits;

        @Override
        public void moveTo(double x, double y) {
            if (events.isEmpty()) {
                firstBits = Double.doubleToRawLongBits(x);
            }
            events.add("move " + x + " " + y);
        }

        @Override
        public void lineTo(double x, double y) {
            events.add("line " + x + " " + y);
        }

        @Override
        public void quadTo(double cx, double cy, double x, double y) {
            events.add("quad " + cx + " " + cy + " " + x + " " + y);
        }

        @Override
        public void curveTo(double cx1, double cy1, double cx2, double cy2, double x, double y) {
            events.add("curve " + cx1 + " " + cy1 + " " + cx2 + " " + cy2 + " " + x + " " + y);
        }

        @Override
        public void closePath() {
            events.add("close");
        }

        @Override
        public void unknownElement(int type) {
            events.add("unknown " + type);
        }

        @Override
        public void failed(Throwable t) {
            events.add("failed " + t);
        }
    }

    /** Throws from the second element; if the applier let it escape, the JVM would end without writing a result. */
    public static final class ThrowingSinkChild {
        public static void main(String[] args) throws IOException {
            long path = MacFontOracleShim.createPath(ALL_ELEMENTS);
            List<String> delivered = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            int[] afterFailure = {0};
            Recorder throwing = new Recorder() {
                @Override
                public void moveTo(double x, double y) {
                    record("move " + x + " " + y);
                }

                @Override
                public void lineTo(double x, double y) {
                    if (failures.isEmpty()) {
                        throw new IllegalStateException("second element");
                    }
                    afterFailure[0]++;
                }

                @Override
                public void quadTo(double cx, double cy, double x, double y) {
                    record("quad");
                }

                @Override
                public void curveTo(double cx1, double cy1, double cx2, double cy2, double x, double y) {
                    record("curve");
                }

                @Override
                public void closePath() {
                    record("close");
                }

                @Override
                public void failed(Throwable t) {
                    failures.add(t.toString());
                }

                private void record(String event) {
                    if (failures.isEmpty()) {
                        delivered.add(event);
                    } else {
                        afterFailure[0]++;
                    }
                }
            };
            CGNativeShim.CGPathApply(MemorySegment.ofAddress(path), throwing);
            boolean installed = CGNativeShim.sinkInstalled();
            long next = MacFontOracleShim.createPath(new double[] {0, 20, 20}, new double[] {1, 21.5, -3.25},
                                                     new double[] {4});
            Recorder recorder = new Recorder();
            CGNativeShim.CGPathApply(MemorySegment.ofAddress(next), recorder);
            Files.writeString(Path.of(args[0]), "failed=" + String.join(";", failures) + "\ndelivered="
                    + String.join(";", delivered) + "\nafterFailure=" + afterFailure[0] + "\nsinkInstalled="
                    + installed + "\nnextCall=" + String.join(";", recorder.events) + "\n", StandardCharsets.UTF_8);
            System.exit(0);
        }
    }

    /** {@code main} in a new JVM with this JVM's options; a fatal error log goes under {@code target/}. */
    static Map<String, String> runChild(Class<?> main) throws IOException, InterruptedException {
        Path work = Path.of("").toAbsolutePath().resolve("target/mac-font-native").resolve(main.getSimpleName());
        Path errors = work.resolve("hs_err");
        Path output = work.resolve("result.txt");
        Path log = work.resolve("child.log");
        if (Files.exists(errors)) {
            try (Stream<Path> stale = Files.list(errors)) {
                for (Path file : stale.toList()) {
                    Files.delete(file);
                }
            }
        }
        Files.createDirectories(errors);
        Files.deleteIfExists(output);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!argument.startsWith("-agentlib") && !argument.startsWith("-javaagent")
                    && !argument.startsWith("-XX:ErrorFile")) {
                command.add(argument);
            }
        }
        command.add("-XX:ErrorFile=" + errors.resolve("hs_err_pid%p.log"));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName(), output.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                fail("child JVM " + main.getSimpleName() + " did not finish; log " + log);
            }
        } finally {
            process.destroyForcibly();
        }
        String[] hsErr;
        try (Stream<Path> files = Files.list(errors)) {
            hsErr = files.map(Path::toString).toArray(String[]::new);
        }
        if (process.exitValue() != 0 || hsErr.length > 0 || !Files.isRegularFile(output)) {
            fail("child JVM " + main.getSimpleName() + " exited with " + process.exitValue() + ", fatal error logs "
                    + Arrays.toString(hsErr) + "; log:\n" + Files.readString(log, StandardCharsets.UTF_8));
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            result.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return result;
    }
}
