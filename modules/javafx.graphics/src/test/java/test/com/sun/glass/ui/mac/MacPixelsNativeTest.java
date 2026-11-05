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

package test.com.sun.glass.ui.mac;

import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.ImageReadback;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.test.ParityGate;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * {@code MacPixels._attachInt} and {@code _attachByte} against the golden of their JNI bodies (audit g1 §9 D3): the
 * bounds checks, which moved to Java, and the {@code NSImage} each accepted call writes into its slot through
 * {@code gmac_image_create}, read back by {@code gmac_test_inspect_and_release_image}.
 * <p>
 * The corpus ({@link #corpus}) holds the boundaries of every check, heap arrays and direct buffers whose position and
 * limit the JNI ignored, and direct buffers at address 0, whose capacity reaches the overflow guards without
 * allocating: the JNI passed NULL on for them, so nothing reads them. It runs in a child JVM, off the main thread,
 * with the main thread detached from Java, so every call that passes the checks also reports
 * {@code "Main Java thread is detached."}, as {@code attachCommon}'s closing {@code GLASS_CHECK_EXCEPTION} did.
 * <p>
 * The golden was captured, with {@code -D}{@value #CAPTURE_PROPERTY}{@code =true}, from the JNI natives, before
 * MacPixels was moved to Java. That capture child first initializes {@code MacApplication}: its JNI
 * {@code _initIDs}, which reads {@code -[NSProcessInfo operatingSystemVersion]} and caches JNI IDs, is what makes the
 * closing {@code GLASS_CHECK_EXCEPTION} callable. Both children prove that AppKit's {@code NSApp} is nil before their
 * first call into the library and after their last.
 */
@EnabledOnOs(OS.MAC)
public class MacPixelsNativeTest {

    static final String CAPTURE_COMMIT = "1a6788cc4d";
    static final String GOLDEN = "mac-pixels-golden-" + CAPTURE_COMMIT + ".txt";
    static final String CAPTURE_PROPERTY = "jfx.mac.pixels.golden.capture";

    private static final String RESOURCE_DIRECTORY = "src/test/resources/test/com/sun/glass/ui/mac";
    private static final String CLASSES_DIRECTORY = "target/test-classes/test/com/sun/glass/ui/mac";
    private static final String NATIVE_SOURCE = "src/main/native-glass/mac/GlassPixels.m";

    /** What a slot holds until the call under test writes it. */
    private static final long SENTINEL = 0x5A5A5A5A5A5A5A5AL;

    /** Larger than the pixel data of every image of the corpus. */
    private static final int READBACK_CAPACITY = 4096;

    private static final int MAX_INT_OFFSET = Integer.MAX_VALUE / 4;

    /** The largest segment {@code MemorySegment.asByteBuffer} wraps. */
    private static final int MAX_DIRECT_BYTES = Integer.MAX_VALUE - 8;
    private static final int MAX_DIRECT_INTS = MAX_DIRECT_BYTES / 4;

    private static final HexFormat HEX = HexFormat.of();

    /** {@code kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little}, the bitmap info of every image. */
    private static final int KCG_ALPHA_PREMULTIPLIED_FIRST_32_LITTLE = 0x2002;

    private static final String DETACHED = "Main Java thread is detached.";

    /** Where the pixels come from. Capacities, offsets, positions and limits count elements of the call's type. */
    enum Source {
        /** A heap array; no buffer. */
        ARRAY,
        /** A heap array and a direct buffer of other pixels: the array is read. */
        ARRAY_AND_DIRECT,
        /** A direct buffer of native byte order; no array. */
        DIRECT,
        /** A direct buffer sliced off another one at byte 12, so that its address is not the allocation's. */
        DIRECT_SLICE,
        /** A read-only view of a direct buffer. */
        DIRECT_READ_ONLY,
        /** A direct int buffer of big-endian byte order: the raw bytes are read. */
        DIRECT_BIG_ENDIAN,
        /** A heap buffer and no array: not direct, so its capacity counts as -1. */
        HEAP_BUFFER,
        /** Neither an array nor a buffer. */
        NONE,
        /** A direct buffer at address 0. */
        NULL_ADDRESS
    }

    /**
     * One call: {@code ints} chooses {@code _attachInt} over {@code _attachByte}, {@code slot} a slot over a
     * {@code ptr} of 0. {@code position} and {@code limit} are set on direct buffers when not negative.
     */
    record PixelCase(String name, boolean ints, boolean slot, int width, int height, int offset, Source source,
                     int capacity, int position, int limit) {

        String key() {
            return "case." + (ints ? "int." : "byte.") + name;
        }

        PixelCase withSlot(boolean value) {
            return new PixelCase(name, ints, value, width, height, offset, source, capacity, position, limit);
        }

        PixelCase at(int newPosition, int newLimit) {
            return new PixelCase(name, ints, slot, width, height, offset, source, capacity, newPosition, newLimit);
        }
    }

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCallAndTheOracleRan() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
        ParityGate.ledger(MacPixelsNativeTest.class).assertOracleRan();
    }

    @Test
    public void theAttachChecksAndImagesAreThoseOfTheJniBodies() throws Exception {
        if (Boolean.getBoolean(CAPTURE_PROPERTY)) {
            capture();
            abort("the MacPixels golden was captured to " + GOLDEN + "; a capture verifies nothing");
        }
        Map<String, String> golden = readGolden();
        ParityGate.Ledger ledger = ParityGate.ledger(MacPixelsNativeTest.class);
        Map<String, String> machine = machine();
        List<String> differences = new ArrayList<>();
        machine.forEach((key, value) -> {
            if (!value.equals(golden.get(key))) {
                differences.add(key + "=" + value + " (golden: " + golden.get(key) + ")");
            }
        });
        ledger.requireOracle(differences.isEmpty(), () -> "the MacPixels golden describes another machine: "
                + differences + ". A cross-machine oracle is a re-capture from the JNI natives of " + CAPTURE_COMMIT
                + " on that machine.");
        Map<String, String> actual = MacGlassNativeTest.runChild(CorpusChild.class);
        assertEquals("false", actual.get("pixelsAttachNative"), "the corpus ran the JNI natives, not the Java");
        Map<String, String> expected = compared(golden);
        assertEquals(corpus().size() + 1, expected.size(), "the golden holds one line per case and the thread state");
        assertEquals(expected.keySet(), compared(actual).keySet(), "the cases of the corpus and of the golden");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey());
            ledger.compared();
        }
    }

    @Test
    public void theNativeFormatIsPremultipliedBgraAndNoAttachIsNativeAnyMore() {
        assertEquals(Pixels.Format.BYTE_BGRA_PRE, MacGlassNativeShim.pixelsNativeFormat());
        assertFalse(MacGlassNativeShim.pixelsAttachIsNative());
    }

    @Test
    public void imageCreateWritesNullForNullPixelsAndForSizesThatAreNotPositive() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate(16);
            MemorySegment slot = arena.allocate(ADDRESS);
            int[][] sizes = {{1, 1}, {0, 1}, {1, 0}, {-1, 1}, {1, -1}, {Integer.MIN_VALUE, Integer.MIN_VALUE}};
            for (int index = 0; index < sizes.length; index++) {
                MemorySegment source = index == 0 ? MemorySegment.NULL : pixels;
                slot.set(JAVA_LONG, 0, SENTINEL);
                MacGlassNativeShim.imageCreate(source, sizes[index][0], sizes[index][1], slot);
                assertEquals(0L, slot.get(JAVA_LONG, 0),
                        "pixels " + source + ", " + sizes[index][0] + "x" + sizes[index][1]);
            }
        }
    }

    @Test
    public void imageCreateKeepsACopyOfThePixelsItWasGiven() {
        byte[] original = bytePattern(8, 9);
        ImageReadback readback;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate(original.length).copyFrom(MemorySegment.ofArray(original));
            MemorySegment slot = arena.allocate(ADDRESS);
            MacGlassNativeShim.imageCreate(pixels, 2, 1, slot);
            pixels.fill((byte) 0);
            readback = MacGlassNativeShim.inspectAndReleaseImage(slot.get(ADDRESS, 0), READBACK_CAPACITY);
        }
        assertEquals(List.of(2, 1, 2, 1, 8, 32, 8, KCG_ALPHA_PREMULTIPLIED_FIRST_32_LITTLE, 1), readback.values());
        assertEquals(original.length, readback.result());
        assertArrayEquals(original, readback.pixels());
    }

    @Test
    public void inspectingNoImageAnswersMinusOneAndWritesNothing() {
        ImageReadback readback = MacGlassNativeShim.inspectAndReleaseImage(MemorySegment.NULL, READBACK_CAPACITY);
        assertEquals(-1, readback.result());
        assertEquals(Collections.nCopies(9, Integer.MIN_VALUE), readback.values());
        assertEquals(0, readback.pixels().length);
    }

    @Test
    public void aDetachedAttachReportsAfterItsImageAndAnAttachedOneReportsNothing() throws Exception {
        assertFalse(MacGlassNativeShim.isMainThread());
        int previous = MacGlassNativeShim.setMainJavaAttached(0);
        try {
            assertEquals(0, previous, "nothing in this JVM ran the run loop, which attaches the main thread");
            Attached detached = onFreshThread(MacPixelsNativeTest::attachOnePixel);
            assertEquals(List.of("java.lang.RuntimeException: " + DETACHED), detached.reported());
            assertEquals(List.of(1, 1, 1, 1, 8, 32, 4, KCG_ALPHA_PREMULTIPLIED_FIRST_32_LITTLE, 1),
                    detached.readback().values(), "the image is made before the report");
            MacGlassNativeShim.setMainJavaAttached(1);
            Attached attached = onFreshThread(MacPixelsNativeTest::attachOnePixel);
            assertEquals(List.of(), attached.reported());
            assertArrayEquals(detached.readback().pixels(), attached.readback().pixels());
        } finally {
            MacGlassNativeShim.setMainJavaAttached(previous);
        }
    }

    @Test
    public void attachDataWritesTheImageOfThePixelsFromTheirArrayOffset() throws Exception {
        int[] backing = intPattern(5, 7);
        IntBuffer ints = IntBuffer.wrap(backing).position(1);
        Pixels pixels = MacGlassNativeShim.newPixels(2, 2, ints);
        Attached attached = onFreshThread(() -> attachData(pixels));
        assertEquals(List.of("java.lang.RuntimeException: " + DETACHED), attached.reported());
        assertEquals(List.of(2, 2, 2, 2, 8, 32, 8, KCG_ALPHA_PREMULTIPLIED_FIRST_32_LITTLE, 1),
                attached.readback().values());
        ByteBuffer expected = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        expected.asIntBuffer().put(backing, 1, 4);
        assertArrayEquals(expected.array(), attached.readback().pixels());
    }

    @Test
    public void attachDataReadsADirectBufferFromItsBaseWhateverItsPositionAndLimit() throws Exception {
        byte[] pattern = bytePattern(12, 8);
        Pixels pixels = MacGlassNativeShim.newPixels(3, 1, ByteBuffer.allocateDirect(12).put(pattern).position(0));
        pixels.getBuffer().position(5).limit(9);
        Attached attached = onFreshThread(() -> attachData(pixels));
        assertEquals(1, attached.reported().size());
        assertArrayEquals(pattern, attached.readback().pixels());
    }

    /** What one call on a fresh thread wrote into its slot, read back, and what it reported. */
    private record Attached(ImageReadback readback, List<String> reported) {
    }

    private static ImageReadback attachOnePixel() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(ADDRESS);
            MacGlassNativeShim.attachInt(slot.address(), 1, 1, null, new int[] {0x11223344}, 0);
            return MacGlassNativeShim.inspectAndReleaseImage(slot.get(ADDRESS, 0), READBACK_CAPACITY);
        }
    }

    private static ImageReadback attachData(Pixels pixels) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(ADDRESS);
            MacGlassNativeShim.attachData(pixels, slot);
            return MacGlassNativeShim.inspectAndReleaseImage(slot.get(ADDRESS, 0), READBACK_CAPACITY);
        }
    }

    /** A thread that reports: what it reported is recorded, in order, as {@code toString()}. */
    private static Attached onFreshThread(ThrowingSupplier<ImageReadback> call) throws Exception {
        List<String> reported = new CopyOnWriteArrayList<>();
        FutureTask<ImageReadback> task = new FutureTask<>(() -> {
            try {
                return call.get();
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
        Thread thread = Thread.ofPlatform().name("MacPixelsNativeTest-call").unstarted(task);
        thread.setUncaughtExceptionHandler((t, e) -> reported.add(e.toString()));
        thread.start();
        return new Attached(task.get(30, TimeUnit.SECONDS), List.copyOf(reported));
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {

        T get() throws Throwable;
    }

    /** Runs the corpus on the Java checks and writes what it saw to the file {@code args[0]}. */
    public static final class CorpusChild {

        public static void main(String[] args) throws Exception {
            runCorpus(Path.of(args[0]), false);
        }
    }

    /**
     * Runs the corpus on the JNI natives, after {@code MacApplication}'s JNI {@code _initIDs}, and writes what it saw
     * to the file {@code args[0]}.
     */
    public static final class JniCaptureChild {

        public static void main(String[] args) throws Exception {
            runCorpus(Path.of(args[0]), true);
        }
    }

    private static void runCorpus(Path result, boolean initializeApplication) throws Exception {
        if (!MacGlassNativeShim.nsAppIsNil()) {
            Files.writeString(result, "nsAppNilBefore=false\n", StandardCharsets.UTF_8);
            return;
        }
        MacGlassNativeShim.loadLibrary();
        if (initializeApplication) {
            MacGlassNativeShim.initializeMacApplication();
        }
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        FutureTask<Map<String, String>> task = new FutureTask<>(() -> {
            Map<String, String> lines = new LinkedHashMap<>();
            lines.put("corpus.mainThreadDetached", Boolean.toString(MacGlassNativeShim.mainThreadDetached()));
            for (PixelCase pixelCase : corpus()) {
                lines.put(pixelCase.key(), run(pixelCase, reported));
            }
            return lines;
        });
        Thread thread = Thread.ofPlatform().name("MacPixelsNativeTest-corpus").unstarted(task);
        thread.setUncaughtExceptionHandler((t, e) -> reported.add(e));
        thread.start();
        Map<String, String> lines = task.get(120, TimeUnit.SECONDS);
        StringBuilder out = new StringBuilder();
        lines.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        out.append("pixelsAttachNative=").append(MacGlassNativeShim.pixelsAttachIsNative()).append('\n');
        out.append("nsAppNilBefore=true\n");
        out.append("nsAppNilAfter=").append(MacGlassNativeShim.nsAppIsNil()).append('\n');
        Files.writeString(result, out.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Calls the attach method of {@code pixelCase} and describes the slot afterwards: {@code untouched}, {@code null},
     * or {@code image} with its readback; then what the call reported.
     */
    private static String run(PixelCase pixelCase, List<Throwable> reported) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(ADDRESS);
            slot.set(JAVA_LONG, 0, SENTINEL);
            long ptr = pixelCase.slot() ? slot.address() : 0L;
            int before = reported.size();
            if (pixelCase.ints()) {
                MacGlassNativeShim.attachInt(ptr, pixelCase.width(), pixelCase.height(), intBuffer(pixelCase),
                        intArray(pixelCase), pixelCase.offset());
            } else {
                MacGlassNativeShim.attachByte(ptr, pixelCase.width(), pixelCase.height(), byteBuffer(pixelCase),
                        byteArray(pixelCase), pixelCase.offset());
            }
            StringBuilder line = new StringBuilder();
            long written = slot.get(JAVA_LONG, 0);
            if (written == SENTINEL) {
                line.append("slot=untouched");
            } else if (written == 0) {
                line.append("slot=null");
            } else {
                ImageReadback readback = MacGlassNativeShim.inspectAndReleaseImage(MemorySegment.ofAddress(written),
                        READBACK_CAPACITY);
                line.append("slot=image;readback=").append(readback.result()).append(':')
                        .append(String.join(",", readback.values().stream().map(String::valueOf).toList()))
                        .append(";bytes=").append(HEX.formatHex(readback.pixels()));
            }
            List<Throwable> reports = reported.subList(before, reported.size());
            line.append(";reports=").append(reports.size());
            for (Throwable report : reports) {
                line.append(':').append(report);
            }
            return line.toString();
        }
    }

    /** The calls of the golden, in its order. */
    static List<PixelCase> corpus() {
        List<PixelCase> cases = new ArrayList<>();
        for (boolean ints : new boolean[] {true, false}) {
            int scale = ints ? 1 : 4;
            int exact = 6 * scale;
            cases.add(small("array3x2", ints, 3, 2, 0, Source.ARRAY, exact));
            cases.add(small("array1x1", ints, 1, 1, 0, Source.ARRAY, scale));
            cases.add(small("array3x2Offset", ints, 3, 2, ints ? 5 : 3, Source.ARRAY, exact + (ints ? 5 : 3)));
            cases.add(small("array3x2OneShort", ints, 3, 2, ints ? 5 : 3, Source.ARRAY, exact + (ints ? 4 : 2)));
            cases.add(small("array5x3Larger", ints, 5, 3, 2, Source.ARRAY, 15 * scale + 9));
            cases.add(small("arrayAndDirect", ints, 3, 2, 1, Source.ARRAY_AND_DIRECT, exact + 1));
            cases.add(small("direct3x2", ints, 3, 2, 0, Source.DIRECT, exact));
            cases.add(small("direct3x2PositionLimit", ints, 3, 2, 3, Source.DIRECT, exact + 4)
                    .at(2, exact + 3));
            cases.add(small("direct3x2OneShort", ints, 3, 2, 3, Source.DIRECT, exact + 2));
            cases.add(small("directSlice", ints, 2, 2, 1, Source.DIRECT_SLICE, 4 * scale + 1));
            cases.add(small("directReadOnly", ints, 2, 1, 0, Source.DIRECT_READ_ONLY, 2 * scale));
            if (ints) {
                cases.add(small("directBigEndian", true, 2, 2, 0, Source.DIRECT_BIG_ENDIAN, 4));
            }
            cases.add(small("heapBufferNoArray", ints, 1, 1, 0, Source.HEAP_BUFFER, scale));
            cases.add(small("noSource", ints, 1, 1, 0, Source.NONE, 0));
            cases.add(small("ptrZero", ints, 1, 1, 0, Source.ARRAY, scale).withSlot(false));
            cases.add(small("ptrZeroNoSource", ints, 1, 1, 0, Source.NONE, 0).withSlot(false));
            cases.add(small("widthZero", ints, 0, 1, 0, Source.ARRAY, scale));
            cases.add(small("heightZero", ints, 1, 0, 0, Source.ARRAY, scale));
            cases.add(small("widthNegative", ints, -1, 1, 0, Source.ARRAY, scale));
            cases.add(small("heightNegative", ints, 1, -1, 0, Source.ARRAY, scale));
            cases.add(small("offsetNegative", ints, 1, 1, -1, Source.ARRAY, scale));
            cases.add(small("offsetNegativeNoSource", ints, 1, 1, -1, Source.NONE, 0));
            cases.add(small("nullAddress", ints, 2, 1, 1, Source.NULL_ADDRESS, 2 * scale + 1));
            cases.add(small("nullAddressOneShort", ints, 2, 1, 1, Source.NULL_ADDRESS, 2 * scale));
        }
        cases.addAll(intBoundaries());
        cases.addAll(byteBoundaries());
        return cases;
    }

    /**
     * The guards of {@code _attachInt}, on direct buffers at address 0. A Java buffer holds at most
     * {@value #MAX_DIRECT_INTS} ints, where the overflow guard and the length check agree, so these are the largest
     * calls each accepts and their neighbours.
     */
    private static List<PixelCase> intBoundaries() {
        int guardWidth = MAX_INT_OFFSET / 32767;
        int guardElements = guardWidth * 32767;
        int largestWidth = (MAX_DIRECT_INTS - 1) / 7;
        return List.of(
                large("overflowGuardPasses", true, guardWidth, 32767, 0, guardElements),
                large("overflowGuardPassesOneShort", true, guardWidth, 32767, 0, guardElements - 1),
                large("overflowGuardFails", true, guardWidth + 1, 32767, 0, MAX_DIRECT_INTS),
                large("largestAccepted", true, largestWidth, 7, 1, MAX_DIRECT_INTS),
                large("largestAcceptedOneShort", true, largestWidth, 7, 1, MAX_DIRECT_INTS - 1),
                large("widthPastLargest", true, largestWidth + 1, 7, 1, MAX_DIRECT_INTS),
                large("offsetLargest", true, 1, 1, MAX_DIRECT_INTS - 1, MAX_DIRECT_INTS),
                large("offsetQuarterMax", true, 1, 1, MAX_INT_OFFSET, MAX_DIRECT_INTS),
                large("offsetAboveQuarterMax", true, 1, 1, MAX_INT_OFFSET + 1, MAX_DIRECT_INTS),
                large("offsetMax", true, 1, 1, Integer.MAX_VALUE, MAX_DIRECT_INTS));
    }

    /** The guards of {@code _attachByte}, which has no quarter-range offset check, as {@link #intBoundaries}. */
    private static List<PixelCase> byteBoundaries() {
        int guardWidth = MAX_INT_OFFSET / 32767;
        int guardBytes = 4 * guardWidth * 32767;
        return List.of(
                large("overflowGuardPasses", false, guardWidth, 32767, 0, guardBytes),
                large("overflowGuardPassesOneShort", false, guardWidth, 32767, 0, guardBytes - 1),
                large("overflowGuardFails", false, guardWidth + 1, 32767, 0, MAX_DIRECT_BYTES),
                large("offsetLargest", false, 1, 1, MAX_DIRECT_BYTES - 4, MAX_DIRECT_BYTES),
                large("offsetLargestOneShort", false, 1, 1, MAX_DIRECT_BYTES - 4, MAX_DIRECT_BYTES - 1),
                large("offsetGuardFails", false, 1, 1, Integer.MAX_VALUE - 3, MAX_DIRECT_BYTES),
                large("offsetMax", false, 1, 1, Integer.MAX_VALUE, MAX_DIRECT_BYTES),
                large("offsetPastQuarterMax", false, 1, 1, MAX_INT_OFFSET + 1, MAX_DIRECT_BYTES));
    }

    private static PixelCase small(String name, boolean ints, int width, int height, int offset, Source source,
                                   int capacity) {
        return new PixelCase(name, ints, true, width, height, offset, source, capacity, -1, -1);
    }

    private static PixelCase large(String name, boolean ints, int width, int height, int offset, int capacity) {
        return new PixelCase(name, ints, true, width, height, offset, Source.NULL_ADDRESS, capacity, -1, -1);
    }

    private static int[] intArray(PixelCase pixelCase) {
        return switch (pixelCase.source()) {
            case ARRAY, ARRAY_AND_DIRECT -> intPattern(pixelCase.capacity(), 1);
            default -> null;
        };
    }

    private static byte[] byteArray(PixelCase pixelCase) {
        return switch (pixelCase.source()) {
            case ARRAY, ARRAY_AND_DIRECT -> bytePattern(pixelCase.capacity(), 1);
            default -> null;
        };
    }

    private static IntBuffer intBuffer(PixelCase pixelCase) {
        int capacity = pixelCase.capacity();
        IntBuffer buffer = switch (pixelCase.source()) {
            case ARRAY, NONE -> null;
            case ARRAY_AND_DIRECT, DIRECT -> directInts(capacity, 0).put(intPattern(capacity, 2));
            case DIRECT_SLICE -> directInts(capacity, 12).put(intPattern(capacity, 3));
            case DIRECT_READ_ONLY -> directInts(capacity, 0).put(intPattern(capacity, 4)).asReadOnlyBuffer();
            case DIRECT_BIG_ENDIAN -> ByteBuffer.allocateDirect(4 * capacity).asIntBuffer()
                    .put(intPattern(capacity, 5));
            case HEAP_BUFFER -> IntBuffer.wrap(intPattern(capacity, 6));
            case NULL_ADDRESS -> MacGlassNativeShim.nullAddressBuffer(4 * capacity).order(ByteOrder.nativeOrder())
                    .asIntBuffer();
        };
        return buffer == null ? null : positioned(pixelCase, buffer);
    }

    private static ByteBuffer byteBuffer(PixelCase pixelCase) {
        int capacity = pixelCase.capacity();
        ByteBuffer buffer = switch (pixelCase.source()) {
            case ARRAY, NONE -> null;
            case ARRAY_AND_DIRECT, DIRECT -> directBytes(capacity, 0).put(bytePattern(capacity, 2));
            case DIRECT_SLICE -> directBytes(capacity, 12).put(bytePattern(capacity, 3));
            case DIRECT_READ_ONLY -> directBytes(capacity, 0).put(bytePattern(capacity, 4)).asReadOnlyBuffer();
            case DIRECT_BIG_ENDIAN -> throw new IllegalArgumentException("a byte buffer has no element byte order");
            case HEAP_BUFFER -> ByteBuffer.wrap(bytePattern(capacity, 6));
            case NULL_ADDRESS -> MacGlassNativeShim.nullAddressBuffer(capacity);
        };
        return buffer == null ? null : positioned(pixelCase, buffer);
    }

    private static <B extends Buffer> B positioned(PixelCase pixelCase, B buffer) {
        buffer.clear();
        if (pixelCase.limit() >= 0) {
            buffer.limit(pixelCase.limit());
        }
        if (pixelCase.position() >= 0) {
            buffer.position(pixelCase.position());
        }
        return buffer;
    }

    private static IntBuffer directInts(int capacity, int byteOffset) {
        return directBytes(4 * capacity, byteOffset).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private static ByteBuffer directBytes(int capacity, int byteOffset) {
        return ByteBuffer.allocateDirect(byteOffset + capacity).position(byteOffset).slice();
    }

    private static int[] intPattern(int length, int seed) {
        int[] values = new int[length];
        for (int index = 0; index < length; index++) {
            values[index] = 0x9E3779B9 * (index + 1 + 977 * seed);
        }
        return values;
    }

    private static byte[] bytePattern(int length, int seed) {
        byte[] values = new byte[length];
        for (int index = 0; index < length; index++) {
            values[index] = (byte) (index * 151 + 7 + 31 * seed);
        }
        return values;
    }

    /** The keys a verify compares: the corpus lines and the thread state they ran in. */
    private static Map<String, String> compared(Map<String, String> lines) {
        Map<String, String> compared = new TreeMap<>();
        lines.forEach((key, value) -> {
            if (key.startsWith("case.") || key.startsWith("corpus.")) {
                compared.put(key, value);
            }
        });
        return compared;
    }

    /** The machine the golden describes; image readback is CoreGraphics' and AppKit's, so it may vary by release. */
    private static Map<String, String> machine() {
        Map<String, String> keys = new TreeMap<>();
        keys.put("machine.os.name", System.getProperty("os.name"));
        keys.put("machine.os.version", System.getProperty("os.version"));
        keys.put("machine.os.arch", System.getProperty("os.arch"));
        return keys;
    }

    private static Map<String, String> readGolden() throws IOException {
        Map<String, String> golden = new LinkedHashMap<>();
        try (InputStream in = MacPixelsNativeTest.class.getResourceAsStream(GOLDEN)) {
            if (in == null) {
                fail("no " + GOLDEN + " on the test class path: the golden records what the JNI natives of "
                        + CAPTURE_COMMIT + " did, and with no capture there is nothing to compare");
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                golden.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return golden;
    }

    /** Refuses unless MacPixels still has its JNI natives, so that the Java checks can never regenerate the golden. */
    private static void capture() throws Exception {
        if (!MacGlassNativeShim.pixelsAttachIsNative()) {
            fail("refusing to capture: MacPixels no longer declares the JNI natives _attachInt and _attachByte, so a"
                    + " capture would record the Java checks it is meant to verify");
        }
        Path module = Path.of("").toAbsolutePath();
        Path source = module.resolve(RESOURCE_DIRECTORY).resolve(GOLDEN);
        if (Files.exists(source)) {
            fail("a golden already exists at " + source + "; it records what the JNI natives did");
        }
        Map<String, String> child = MacGlassNativeTest.runChild(JniCaptureChild.class);
        if (!"true".equals(child.get("pixelsAttachNative"))) {
            fail("refusing to capture: the child ran " + child.get("pixelsAttachNative") + " JNI natives");
        }
        List<String> lines = new ArrayList<>(List.of(
                "# MacPixels attach golden: what the JNI natives _attachInt and _attachByte (GlassPixels.m: the checks",
                "# of " + CAPTURE_COMMIT + ", attachCommon calling gmac_image_create) did with each call of",
                "# MacPixelsNativeTest.corpus(), in a child JVM, off the main thread, with the main thread detached",
                "# from Java, after MacApplication's JNI _initIDs. Compared exactly.",
                "# Keys: machine.* gate the comparison; capture.* and info.* are recorded, never compared;",
                "# corpus.* and case.<int|byte>.<name> are compared. A case line: slot=untouched|null|image, the",
                "# readback of gmac_test_inspect_and_release_image (answer:9 values;bytes), and the reports."));
        machine().forEach((key, value) -> lines.add(key + "=" + value));
        lines.add("capture.commit=" + git(module, "rev-parse", "HEAD"));
        lines.add("capture.jniLibrary.sha256=" + sha256(module.resolve("target/native/bin/libglass.dylib")));
        lines.add("capture.nativeSource.GlassPixels.m.sha256=" + sha256(module.resolve(NATIVE_SOURCE)));
        lines.add("capture.pixelsAttachNative=" + child.get("pixelsAttachNative"));
        lines.add("info.java.vm=" + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        compared(child).forEach((key, value) -> lines.add(key + "=" + value));
        byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(source.getParent());
        Files.write(source, content);
        Path classes = module.resolve(CLASSES_DIRECTORY);
        Files.createDirectories(classes);
        Files.write(classes.resolve(GOLDEN), content);
    }

    private static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return process.waitFor() == 0 ? output : "(git failed: " + output + ")";
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
