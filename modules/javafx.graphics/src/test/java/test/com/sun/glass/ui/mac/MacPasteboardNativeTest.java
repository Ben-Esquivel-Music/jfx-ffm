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
import com.sun.glass.ui.mac.MacGlassNativeShim.BlockAnswer;
import com.sun.javafx.font.JniStringCodec;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.StructLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The natives of {@code MacPasteboard} over the {@code gmac_pasteboard_*} ABI, without a pasteboard: the record
 * layouts against the library's probes; the block parsers on the blocks the C builders answer, through
 * {@code gmac_test_pasteboard_utis_block} and {@code gmac_test_pasteboard_raw_image} in place of the exports; the
 * items {@code _putItemsFromArray} builds, through {@code gmac_test_pasteboard_describe_items}, which records the
 * setters the export would call on its {@code NSPasteboardItem}s; and the decoding and detached reports of each call,
 * with a stand-in where the export would be.
 * <p>
 * No test calls a {@code gmac_pasteboard_*} export: each talks to the pasteboard server, and reading the general
 * pasteboard may raise the paste-privacy prompt. The hooks run Foundation and {@code NSImage}, and nothing here
 * creates an {@code NSApplication}: AppKit's {@code NSApp} is nil before the first call into the library and after
 * the last.
 * <p>
 * Nothing in this JVM attached the main thread, so every call is detached unless a test sets the flag: the
 * stand-in calls run on a fresh thread whose uncaught-exception handler records what {@code Application}
 * reports.
 */
@EnabledOnOs(OS.MAC)
public class MacPasteboardNativeTest {

    private static final String DETACHED = "java.lang.RuntimeException: Main Java thread is detached.";

    private static final String UTF_STRING = "public.utf8-plain-text";
    private static final String UTF_HTML = "public.html";
    private static final String UTF_FILE_URL = "public.file-url";
    private static final String UTF_TIFF = "public.tiff";
    private static final String UTF_RAW_IMAGE = "application.x-java-rawimage";
    private static final String UTF_DRAG_IMAGE = "application.x-java-drag-image";
    private static final String UTF_DRAG_IMAGE_OFFSET = "application.x-java-drag-image-offset";
    private static final String CUSTOM_UTI = "dyn.ah62d4q34gq80c6durvy0g2pyrf106p52fz01wppytf2gns5drvu1g646h";

    private static final HexFormat HEX = HexFormat.of();

    /** What a call on a fresh thread answered, and what it reported, in order. */
    private record Run<T>(T value, List<String> reports) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {

        T get() throws Throwable;
    }

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCall() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
    }

    @Test
    public void theRepresentationIsWhereTheCompilerPutIt() {
        assertLayout("GmacPasteboardRep", 32, List.of("uti", "uti_length", "kind", "data", "data_length", "reserved"),
                List.of(0L, 8L, 12L, 16L, 24L, 28L));
    }

    @Test
    public void theItemIsWhereTheCompilerPutIt() {
        assertLayout("GmacPasteboardItem", 16, List.of("reps", "rep_count", "reserved"), List.of(0L, 8L, 12L));
    }

    @Test
    public void theUtisBlockHasOneArrayPerItemAndNullForAnItemWithoutUtis() throws Exception {
        String[][] types = {
            {UTF_STRING, UTF_HTML},
            null,
            {},
            {"dyn.é", "x😀"},
            {"a\u0000b", ""}
        };
        Run<String[][]> run = onFreshThread(true, () -> MacGlassNativeShim.pasteboardUtisOf(types));
        assertEquals(List.of(), run.reports());
        String[][] expected = {{UTF_STRING, UTF_HTML}, null, null, {"dyn.é", "x😀"}, {"a\u0000b", ""}};
        assertEquals(Arrays.deepToString(expected), Arrays.deepToString(run.value()));
        assertEquals(String[][].class, run.value().getClass());
    }

    /** {@code createUTF}: {@code lengthOfBytesUsingEncoding:} answers 0 for a string with a lone surrogate (R4). */
    @Test
    public void aUtiWithALoneSurrogateIsEmpty() throws Exception {
        String[][] types = {{"lone\uD800", "x\uDC00", UTF_FILE_URL}};
        Run<String[][]> run = onFreshThread(true, () -> MacGlassNativeShim.pasteboardUtisOf(types));
        assertEquals(Arrays.deepToString(new String[][] {{"", "", UTF_FILE_URL}}), Arrays.deepToString(run.value()));
    }

    @Test
    public void aPasteboardWithoutItemsHasNoUtis() throws Exception {
        Run<String[][]> run = onFreshThread(true, () -> MacGlassNativeShim.pasteboardUtisOf(new String[0][]));
        assertNull(run.value());
        assertEquals(List.of(), run.reports());
    }

    @Test
    public void aRawImageIsTheBlockVerbatim() throws Exception {
        byte[] png = png(2, 2, new int[] {0xFFFFFFFF, 0xFF000000, 0x00000000, 0xFFFFFFFF});
        byte[] block = MacGlassNativeShim.rawImageBlock(png);
        Run<byte[]> run = onFreshThread(true, () -> MacGlassNativeShim.pasteboardRawImageOf(png));
        assertNotNull(block);
        assertArrayEquals(block, run.value());
        assertEquals(List.of(), run.reports());
    }

    /**
     * Width and height as bytes 3 to 0 of a {@code size_t}, big-endian here (L23), then premultiplied BGRA pixels,
     * the top row first.
     */
    @Test
    public void aRawImageIsItsSizeThenItsPremultipliedPixels() {
        byte[] png = png(2, 2, new int[] {0xFFFFFFFF, 0xFF000000, 0x00000000, 0xFFFFFFFF});
        byte[] expected = HEX.parseHex("00000002" + "00000002" + "ffffffff" + "000000ff" + "00000000" + "ffffffff");
        assertEquals(HEX.formatHex(expected), HEX.formatHex(MacGlassNativeShim.rawImageBlock(png)));
    }

    @Test
    public void dataThatIsNoImageHasNoRawImage() throws Exception {
        byte[] garbage = "not an image".getBytes(StandardCharsets.US_ASCII);
        assertNull(MacGlassNativeShim.rawImageBlock(garbage));
        assertNull(onFreshThread(true, () -> MacGlassNativeShim.pasteboardRawImageOf(garbage)).value());
    }

    /** L6: the pixels may hold {@code (INT_MAX / 4) / height} columns, their block 2 fewer per row of height. */
    @Test
    public void theRawImageHasTwoDifferentSizeBounds() {
        long quarter = Integer.MAX_VALUE / 4;
        assertEquals(3, MacGlassNativeShim.rawImageBounds(1, 1));
        assertEquals(0, MacGlassNativeShim.rawImageBounds(0, 1));
        assertEquals(0, MacGlassNativeShim.rawImageBounds(1, 0));
        assertEquals(3, MacGlassNativeShim.rawImageBounds(quarter - 2, 1));
        assertEquals(1, MacGlassNativeShim.rawImageBounds(quarter - 1, 1));
        assertEquals(1, MacGlassNativeShim.rawImageBounds(quarter, 1));
        assertEquals(0, MacGlassNativeShim.rawImageBounds(quarter + 1, 1));
        assertEquals(3, MacGlassNativeShim.rawImageBounds((quarter - 2) / 2, 2));
        assertEquals(1, MacGlassNativeShim.rawImageBounds(quarter / 2, 2));
        assertEquals(0, MacGlassNativeShim.rawImageBounds(1L << 32, 1L << 32));
    }

    /** {@code createUTF} gave {@code new String(byte[], "UTF-8")} every byte, a NUL and malformed UTF-8 included. */
    @Test
    public void anItemStringIsDecodedAsUtf8() throws Exception {
        byte[] bytes = HEX.parseHex("78" + "f09f9880" + "00" + "79" + "c328");
        Run<String> run = onFreshThread(true, () -> MacGlassNativeShim.pasteboardItemString(
                new BlockAnswer(0, bytes, bytes.length)));
        assertEquals("x😀\u0000y�(", run.value());
        assertEquals(List.of(), run.reports());
    }

    /** {@code NewStringUTF}: modified UTF-8, to the first NUL (L22). */
    @Test
    public void theNameIsDecodedAsNewStringUtf() throws Exception {
        byte[] bytes = HEX.parseHex("7062" + "f09f9880" + "c3a9" + "00" + "7a");
        Run<String> run = onFreshThread(true, () -> MacGlassNativeShim.pasteboardName(
                new BlockAnswer(0, bytes, bytes.length)));
        assertEquals(JniStringCodec.fromNewStringUtf(bytes), run.value());
        assertEquals(JniStringCodec.fromNewStringUtf(HEX.parseHex("7062f09f9880c3a9")), run.value());
    }

    @Test
    public void noDataIsNullForEveryCall() throws Exception {
        BlockAnswer noData = new BlockAnswer(-1, null, 0);
        Run<List<Object>> run = onFreshThread(true, () -> Arrays.asList(MacGlassNativeShim.pasteboardName(noData),
                MacGlassNativeShim.pasteboardUtis(noData), MacGlassNativeShim.pasteboardRawImage(noData),
                MacGlassNativeShim.pasteboardItemString(noData), MacGlassNativeShim.pasteboardItemBytes(noData)));
        assertEquals(Arrays.asList(null, null, null, null, null), run.value());
        assertEquals(List.of(), run.reports());
    }

    @Test
    public void anEmptyItemIsEmpty() throws Exception {
        BlockAnswer empty = new BlockAnswer(0, new byte[0], 0);
        assertArrayEquals(new byte[0],
                onFreshThread(true, () -> MacGlassNativeShim.pasteboardItemBytes(empty)).value());
        assertEquals("", onFreshThread(true, () -> MacGlassNativeShim.pasteboardItemString(empty)).value());
    }

    @Test
    public void itemBytesAreTheBlock() throws Exception {
        byte[] bytes = HEX.parseHex("00ff7f8001");
        Run<byte[]> run = onFreshThread(true, () -> MacGlassNativeShim.pasteboardItemBytes(
                new BlockAnswer(0, bytes, bytes.length)));
        assertArrayEquals(bytes, run.value());
    }

    /**
     * {@code NewByteArray} threw for a length negative as a {@code jsize}, and that exception replaced the one the
     * detached assertion had left pending: one report either way.
     */
    @Test
    public void aNegativeLengthReportsTheNegativeArraySizeExceptionAlone() throws Exception {
        BlockAnswer negative = new BlockAnswer(-1, null, -5);
        String expected = "java.lang.NegativeArraySizeException: -5";
        Run<byte[]> attached = onFreshThread(true, () -> MacGlassNativeShim.pasteboardItemBytes(negative));
        assertNull(attached.value());
        assertEquals(List.of(expected), attached.reports());
        Run<byte[]> detached = onFreshThread(false, () -> MacGlassNativeShim.pasteboardItemBytes(negative));
        assertNull(detached.value());
        assertEquals(List.of(expected), detached.reports());
    }

    @Test
    public void aDetachedCallReportsOnceAfterItsDowncallAndAnswersAsUsual() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Run<Long> detached = onFreshThread(false, events, () -> MacGlassNativeShim.pasteboardCall(() -> {
            events.add("downcall");
            return 7L;
        }));
        assertEquals(7L, detached.value());
        assertEquals(List.of("downcall", DETACHED), events);
        events.clear();
        Run<Long> attached = onFreshThread(true, events, () -> MacGlassNativeShim.pasteboardCall(() -> {
            events.add("downcall");
            return 8L;
        }));
        assertEquals(8L, attached.value());
        assertEquals(List.of("downcall"), events);
    }

    @Test
    public void aDetachedBlockCallReportsOnceAndAnswersAsUsual() throws Exception {
        byte[] bytes = "pb".getBytes(StandardCharsets.US_ASCII);
        BlockAnswer answer = new BlockAnswer(0, bytes, bytes.length);
        assertEquals(new Run<>("pb", List.of(DETACHED)),
                onFreshThread(false, () -> MacGlassNativeShim.pasteboardName(answer)));
        assertEquals(new Run<>("pb", List.of(DETACHED)),
                onFreshThread(false, () -> MacGlassNativeShim.pasteboardItemString(answer)));
        Run<String[][]> utis = onFreshThread(false, () -> MacGlassNativeShim.pasteboardUtisOf(
                new String[][] {{UTF_STRING}}));
        assertEquals(Arrays.deepToString(new String[][] {{UTF_STRING}}), Arrays.deepToString(utis.value()));
        assertEquals(List.of(DETACHED), utis.reports());
    }

    @Test
    public void stringsAndBytesBecomeTheSettersTheirUtisTake() throws Exception {
        byte[] custom = HEX.parseHex("010203");
        byte[] large = new byte[70];
        Arrays.fill(large, (byte) 0x5a);
        Object[] items = {
            item(rep(UTF_STRING, "hello"), rep(UTF_HTML, "<b>é😀</b>")),
            item(rep(CUSTOM_UTI, custom), rep(UTF_FILE_URL, "")),
            item(rep(CUSTOM_UTI, large))
        };
        assertEquals("item\n" + stringLine(UTF_STRING, "hello") + stringLine(UTF_HTML, "<b>é😀</b>")
                + "item\n" + dataLine(CUSTOM_UTI, custom) + stringLine(UTF_FILE_URL, "")
                + "item\n" + dataLine(CUSTOM_UTI, large), describe(items));
    }

    /** L1 and L24: 8 bytes are read whatever the length, and Java pads a shorter array with zeros. */
    @Test
    public void theDragImageOffsetReadsEightBigEndianBytesPaddedWithZeros() throws Exception {
        Object[] items = {
            item(rep(UTF_DRAG_IMAGE_OFFSET, HEX.parseHex("00000005fffffffd"))),
            item(rep(UTF_DRAG_IMAGE_OFFSET, HEX.parseHex("00000007"))),
            item(rep(UTF_DRAG_IMAGE_OFFSET, new byte[0])),
            item(rep(UTF_DRAG_IMAGE_OFFSET, HEX.parseHex("000000010000000200000003")))
        };
        assertEquals("item\n" + stringLine(UTF_DRAG_IMAGE_OFFSET, "{5, -3}")
                + "item\n" + stringLine(UTF_DRAG_IMAGE_OFFSET, "{7, 0}")
                + "item\n" + stringLine(UTF_DRAG_IMAGE_OFFSET, "{0, 0}")
                + "item\n" + stringLine(UTF_DRAG_IMAGE_OFFSET, "{1, 2}"), describe(items));
    }

    /** L26: a value of another kind than its UTI takes, or none, sets nothing; an image is released all the same. */
    @Test
    public void aValueOfAnotherKindThanItsUtiTakesSetsNothing() throws Exception {
        Object[] items = {
            item(rep(UTF_STRING, HEX.parseHex("41")), rep(UTF_STRING, null), rep(UTF_STRING, 42),
                    rep(UTF_STRING, pixels())),
            item(rep(CUSTOM_UTI, "text"), rep(CUSTOM_UTI, null), rep(UTF_DRAG_IMAGE_OFFSET, "1,2"),
                    rep(UTF_DRAG_IMAGE_OFFSET, null), rep(CUSTOM_UTI, pixels())),
            item(rep(UTF_RAW_IMAGE, HEX.parseHex("41")), rep(UTF_RAW_IMAGE, "text"), rep(UTF_RAW_IMAGE, null))
        };
        assertEquals("item\nitem\nitem\n", describe(items));
    }

    /** {@code validate}: a string with a lone surrogate is not set. */
    @Test
    public void aStringWithALoneSurrogateIsNotSet() throws Exception {
        Object[] items = {item(rep(UTF_STRING, "a\uD800"), rep(UTF_HTML, "\uDC00b"), rep(UTF_STRING, "ok"))};
        assertEquals("item\n" + stringLine(UTF_STRING, "ok"), describe(items));
    }

    @Test
    public void aNullItemMakesNoItemAndABadRepresentationNothing() throws Exception {
        Object[] items = {
            null,
            item(new Object[] {UTF_STRING}, new Object[] {UTF_STRING, "a", "b"}, new Object[0]),
            null,
            item()
        };
        assertEquals("item\nitem\n", describe(items));
        assertEquals("", describe(new Object[0]));
        assertEquals("", describe(new Object[] {null}));
    }

    /** An empty UTI made the JNI's UTI nil, as {@code GetStringLength} 0 made none; Java's null does the same. */
    @Test
    public void anEmptyOrNullUtiIsNil() throws Exception {
        byte[] bytes = HEX.parseHex("ff");
        Object[] items = {item(rep("", bytes), rep(null, bytes), rep("", "text"))};
        assertEquals("item\n" + dataLine(null, bytes) + dataLine(null, bytes), describe(items));
    }

    /** The raw image UTI sets the image's TIFF as {@code public.tiff}; the drag image UTI as itself. */
    @Test
    public void anImageIsItsTiff() throws Exception {
        Object[] items = {item(rep(UTF_RAW_IMAGE, pixels())), item(rep(UTF_DRAG_IMAGE, pixels()))};
        String[] lines = describe(items).split("\n");
        assertEquals(4, lines.length, Arrays.toString(lines));
        assertEquals("item", lines[0]);
        assertTiffLine(UTF_TIFF, lines[1]);
        assertEquals("item", lines[2]);
        assertTiffLine(UTF_DRAG_IMAGE, lines[3]);
    }

    /**
     * Detached, the put reports once before the first {@code Pixels.attachData}, and each {@code attachData} after
     * it: its own detached report, or what it threw, after which its image is NULL and sets nothing.
     */
    @Test
    public void aDetachedPutReportsBeforeTheFirstAttachDataAndAfterEachAttachFailure() throws Exception {
        IllegalStateException failure = new IllegalStateException("attachData");
        Object[] items = {
            item(rep(UTF_RAW_IMAGE, MacGlassNativeShim.throwingPixels(failure))),
            item(rep(UTF_RAW_IMAGE, pixels()), rep(UTF_STRING, "s"))
        };
        Run<String> detached = onFreshThread(false, () -> MacGlassNativeShim.describeItems(items));
        assertEquals(List.of(DETACHED, failure.toString(), DETACHED), detached.reports());
        String[] lines = detached.value().split("\n");
        assertEquals(List.of("item", "item"), List.of(lines[0], lines[1]));
        assertTiffLine(UTF_TIFF, lines[2]);
        assertEquals(stringLine(UTF_STRING, "s"), lines[3] + "\n");
        Run<String> attached = onFreshThread(true, () -> MacGlassNativeShim.describeItems(items));
        assertEquals(List.of(failure.toString()), attached.reports());
        assertEquals(detached.value(), attached.value());
    }

    private static void assertLayout(String struct, long size, List<String> members, List<Long> offsets) {
        StructLayout layout = MacGlassNativeShim.layouts().get(struct);
        assertEquals(size, layout.byteSize(), struct);
        assertEquals(size, MacGlassNativeShim.sizeOf(struct), "sizeof " + struct);
        assertEquals(members, layout.memberLayouts().stream().map(m -> m.name().orElseThrow()).toList());
        for (int member = 0; member < members.size(); member++) {
            long offset = layout.byteOffset(PathElement.groupElement(members.get(member)));
            assertEquals(offsets.get(member), offset, struct + "." + members.get(member));
            assertEquals(offset, MacGlassNativeShim.offsetOf(struct, member), "offsetof " + struct + "."
                    + members.get(member));
        }
        assertEquals(-1, MacGlassNativeShim.offsetOf(struct, members.size()), struct + " past its last member");
        assertEquals(-1, MacGlassNativeShim.offsetOf(struct, -1), struct + " before its first member");
    }

    private static void assertTiffLine(String type, String line) {
        String prefix = "data" + units(type) + " ";
        assertTrue(line.startsWith(prefix), line);
        String[] countAndBytes = line.substring(prefix.length()).split(":");
        assertTrue(Integer.parseInt(countAndBytes[0]) > 8, line);
        String magic = countAndBytes[1].substring(0, 8);
        assertTrue(magic.equals("4d4d002a") || magic.equals("49492a00"), "no TIFF header in " + line);
    }

    /** What the put builds of {@code items}, attached, and asserts that nothing was reported. */
    private static String describe(Object[] items) throws Exception {
        Run<String> run = onFreshThread(true, () -> MacGlassNativeShim.describeItems(items));
        assertEquals(List.of(), run.reports());
        return run.value();
    }

    private static Object[] item(Object[]... representations) {
        return representations;
    }

    private static Object[] rep(String uti, Object value) {
        return new Object[] {uti, value};
    }

    private static Pixels pixels() {
        return MacGlassNativeShim.newPixels(1, 1, IntBuffer.wrap(new int[] {0xFF336699}));
    }

    private static String stringLine(String type, String value) {
        return "string" + units(type) + units(value) + "\n";
    }

    private static String dataLine(String type, byte[] data) {
        return "data" + units(type) + " " + data.length + ":" + HEX.formatHex(data, 0, Math.min(data.length, 64))
                + "\n";
    }

    /** The hook's spelling of a string: {@code " nil"}, or its unit count, a colon and its units in hex. */
    private static String units(String s) {
        if (s == null) {
            return " nil";
        }
        StringBuilder units = new StringBuilder(" ").append(s.length()).append(':');
        for (char c : s.toCharArray()) {
            units.append("%04x".formatted((int) c));
        }
        return units.toString();
    }

    /** A {@code width x height} RGBA PNG of {@code argb}, row by row, untagged, unfiltered. */
    private static byte[] png(int width, int height, int[] argb) {
        ByteArrayOutputStream scanlines = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            scanlines.write(0);
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                scanlines.write(pixel >>> 16);
                scanlines.write(pixel >>> 8);
                scanlines.write(pixel);
                scanlines.write(pixel >>> 24);
            }
        }
        Deflater deflater = new Deflater();
        deflater.setInput(scanlines.toByteArray());
        deflater.finish();
        byte[] buffer = new byte[1024];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        ByteBuffer header = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 6)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(HEX.parseHex("89504e470d0a1a0a"));
        chunk(png, "IHDR", header.array());
        chunk(png, "IDAT", compressed.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream png, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        png.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        png.writeBytes(typeBytes);
        png.writeBytes(data);
        png.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static <T> Run<T> onFreshThread(boolean attached, ThrowingSupplier<T> call) throws Exception {
        List<String> reports = new CopyOnWriteArrayList<>();
        Run<T> run = onFreshThread(attached, reports, call);
        return new Run<>(run.value(), List.copyOf(reports));
    }

    /**
     * Runs {@code call} on a new platform thread with the main thread's attached flag set as {@code attached}, adding
     * what it reports to {@code reports}; restores the flag after.
     */
    private static <T> Run<T> onFreshThread(boolean attached, List<String> reports, ThrowingSupplier<T> call)
            throws Exception {
        int previous = MacGlassNativeShim.setMainJavaAttached(attached ? 1 : 0);
        try {
            FutureTask<T> task = new FutureTask<>(() -> {
                try {
                    return call.get();
                } catch (Exception | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            });
            Thread thread = Thread.ofPlatform().name("MacPasteboardNativeTest-call").unstarted(task);
            thread.setUncaughtExceptionHandler((t, e) -> reports.add(e.toString()));
            thread.start();
            return new Run<>(task.get(30, TimeUnit.SECONDS), reports);
        } finally {
            MacGlassNativeShim.setMainJavaAttached(previous);
        }
    }
}
