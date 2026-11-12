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

package test.com.sun.javafx.font;

import com.sun.javafx.font.JniStringCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link JniStringCodec} against HotSpot's own JNI string conversions, on every platform, through the golden
 * {@value #GOLDEN}: the answers the running JVM's {@code NewStringUTF} and {@code NewString} +
 * {@code GetStringUTFRegion} gave, through the JNI function table, for every input the JNI oracle test of commit
 * {@code 0fd9cb0ef5} ({@code JniStringOracleTest} with {@code JniStringOracleShim} and {@code JniStringOracleChild},
 * deleted by US-074 so that no test calls a JNI function) compared with the codec: the eight measured rows of
 * {@link JniStringCodecTest}, a seeded corpus of 1280 random and structured byte strings for the decoder and a
 * seeded corpus of 640 code-unit strings (with {@code U+0000}, surrogate pairs and unpaired surrogates) for the
 * encoder. The golden was captured on that commit with JDK 27+35 on Windows x64; HotSpot's conversions
 * ({@code UNICODE::utf8_write}, {@code UTF8::unicode_length}, {@code UTF8::next}) are portable C++ with no platform
 * branch, so one capture serves every platform.
 *
 * <p>The corpora below are the oracle test's generators, unchanged (same seeds, same counts), and
 * {@link #theGoldenHoldsEveryInputTheOracleCovered} proves at every run that the golden's inputs are exactly the
 * inputs they generate, so the golden can neither lose a row nor drift from what the oracle covered.
 *
 * <p>A {@code String} made by {@code NewStringUTF} from malformed bytes may carry the UTF-16 coder for Latin-1
 * content, so the comparison is on code units, never through {@code String.equals}.
 */
public class JniStringCodecGoldenTest {

    static final String GOLDEN = "jni-string-codec-golden-0fd9cb0ef5.txt";

    /** The oracle's batch size times its batch counts: 32 references per child JVM, 40 and 20 children. */
    private static final int DECODE_COUNT = 32 * 40;
    private static final int ENCODE_COUNT = 32 * 20;

    private static final HexFormat HEX = HexFormat.of();

    /** One row of the golden: the input and the JVM's output, as bytes. */
    private record Row(int line, byte[] input, byte[] output) {
    }

    private static List<Row> measured;
    private static List<Row> decoded;
    private static List<Row> encoded;

    @BeforeAll
    public static void loadTheGolden() {
        measured = new ArrayList<>();
        decoded = new ArrayList<>();
        encoded = new ArrayList<>();
        String text;
        try (InputStream in = JniStringCodecGoldenTest.class.getResourceAsStream(GOLDEN)) {
            if (in == null) {
                throw new AssertionError("no golden file " + GOLDEN + " on the classpath");
            }
            text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cells = line.split(" ", -1);
            if (cells.length != 3) {
                throw new AssertionError(GOLDEN + ":" + (i + 1) + ": expected <kind> <input> <output>: " + line);
            }
            Row row = new Row(i + 1, cell(cells[1]), cell(cells[2]));
            switch (cells[0]) {
                case "m" -> measured.add(row);
                case "d" -> decoded.add(row);
                case "e" -> encoded.add(row);
                default -> throw new AssertionError(GOLDEN + ":" + (i + 1) + ": unknown kind " + cells[0]);
            }
        }
    }

    @Test
    public void theGoldenHoldsEveryInputTheOracleCovered() {
        assertEquals(JniStringCodecTest.MEASURED_INPUTS.length, measured.size(), "measured rows");
        for (int i = 0; i < measured.size(); i++) {
            assertArrayEquals(JniStringCodecTest.MEASURED_INPUTS[i], measured.get(i).input(), "measured row " + i);
        }
        List<byte[]> decodeCorpus = decodeCorpus(DECODE_COUNT);
        assertEquals(decodeCorpus.size(), decoded.size(), "decode rows");
        for (int i = 0; i < decoded.size(); i++) {
            assertArrayEquals(decodeCorpus.get(i), decoded.get(i).input(), "decode row " + i);
        }
        List<String> encodeCorpus = encodeCorpus(ENCODE_COUNT);
        assertEquals(encodeCorpus.size(), encoded.size(), "encode rows");
        for (int i = 0; i < encoded.size(); i++) {
            assertArrayEquals(toBytes(encodeCorpus.get(i).toCharArray()), encoded.get(i).input(), "encode row " + i);
        }
    }

    @Test
    public void theCapturingJvmAgreedWithTheMeasuredRows() {
        for (int i = 0; i < measured.size(); i++) {
            assertArrayEquals(JniStringCodecTest.MEASURED_CHARS[i], toChars(measured.get(i).output()),
                    "NewStringUTF of row " + i + " " + JniStringCodecTest.hex(measured.get(i).input()));
        }
    }

    @Test
    public void decoderMatchesTheCapturedNewStringUtf() {
        List<String> mismatches = new ArrayList<>();
        List<Row> rows = new ArrayList<>(measured);
        rows.addAll(decoded);
        try (Arena arena = Arena.ofConfined()) {
            for (Row row : rows) {
                char[] jvm = toChars(row.output());
                char[] java = JniStringCodec.fromNewStringUtf(row.input()).toCharArray();
                MemorySegment cString = arena.allocateFrom(JAVA_BYTE,
                        Arrays.copyOf(row.input(), row.input().length + 1));
                char[] javaFromSegment = JniStringCodec.fromNewStringUtf(cString).toCharArray();
                if (!Arrays.equals(jvm, java) || !Arrays.equals(jvm, javaFromSegment)) {
                    mismatches.add(GOLDEN + ":" + row.line() + " " + HEX.formatHex(row.input()) + ": NewStringUTF "
                            + units(jvm) + ", JniStringCodec " + units(java) + ", from a segment "
                            + units(javaFromSegment));
                }
            }
        }
        report(mismatches, rows.size(), "decode differently");
    }

    @Test
    public void encoderMatchesTheCapturedGetStringUtfRegion() {
        List<String> mismatches = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            for (Row row : encoded) {
                String s = new String(toChars(row.input()));
                byte[] jvm = row.output();
                byte[] java = JniStringCodec.toModifiedUtf8(s);
                byte[] allocated = JniStringCodec.allocateModifiedUtf8(arena, s).toArray(JAVA_BYTE);
                int length = JniStringCodec.modifiedUtf8Length(s);
                if (!Arrays.equals(jvm, java) || !Arrays.equals(jvm, allocated)) {
                    mismatches.add(GOLDEN + ":" + row.line() + " " + units(s.toCharArray()) + ": GetStringUTFRegion "
                            + HEX.formatHex(jvm) + ", JniStringCodec " + HEX.formatHex(java) + ", allocated "
                            + HEX.formatHex(allocated));
                } else if (length != jvm.length - 1) {
                    mismatches.add(GOLDEN + ":" + row.line() + " " + units(s.toCharArray()) + ": GetStringUTFLength "
                            + (jvm.length - 1) + ", modifiedUtf8Length " + length);
                }
            }
        }
        report(mismatches, encoded.size(), "encode differently");
    }

    private static void report(List<String> mismatches, int total, String what) {
        if (!mismatches.isEmpty()) {
            fail(mismatches.size() + " of " + total + " " + what + ":\n  "
                    + String.join("\n  ", mismatches.subList(0, Math.min(mismatches.size(), 20))));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The oracle's corpora (seeded; the generators of JniStringOracleTest at 0fd9cb0ef5, unchanged)
    // ---------------------------------------------------------------------------------------------

    /**
     * Random bytes ({@code 01..FF}, so the length is the string length), structured strings built from the tokens
     * the decoder distinguishes, standard UTF-8 of random code points (four-byte sequences included) and the
     * modified UTF-8 {@code DataOutputStream.writeUTF} produces for random code units.
     */
    private static List<byte[]> decodeCorpus(int count) {
        Random random = new Random(0x4E657753747255L);
        List<byte[]> corpus = new ArrayList<>(count);
        while (corpus.size() < count) {
            switch (corpus.size() % 4) {
                case 0 -> {
                    byte[] bytes = new byte[random.nextInt(33)];
                    for (int k = 0; k < bytes.length; k++) {
                        bytes[k] = (byte) (1 + random.nextInt(255));
                    }
                    corpus.add(bytes);
                }
                case 1 -> corpus.add(structured(random));
                case 2 -> corpus.add(randomCodePoints(random).getBytes(StandardCharsets.UTF_8));
                default -> corpus.add(JniStringCodecTest.writeUtfPayload(
                        JniStringCodecTest.randomCodeUnits(random, 32)));
            }
        }
        return corpus;
    }

    private static byte[] structured(Random random) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int tokens = 1 + random.nextInt(8);
        for (int t = 0; t < tokens; t++) {
            switch (random.nextInt(16)) {
                case 0 -> out.write(0x41 + random.nextInt(26));
                case 1 -> { out.write(0xC2 + random.nextInt(30)); out.write(continuation(random)); }
                case 2 -> {
                    out.write(0xE0 + random.nextInt(16));
                    out.write(continuation(random));
                    out.write(continuation(random));
                }
                case 3 -> {
                    out.write(0xF0 + random.nextInt(5));
                    out.write(continuation(random));
                    out.write(continuation(random));
                    out.write(continuation(random));
                }
                case 4 -> {
                    out.write(0xED); out.write(0xA0 + random.nextInt(16)); out.write(continuation(random));
                    out.write(0xED); out.write(0xB0 + random.nextInt(16)); out.write(continuation(random));
                }
                case 5 -> out.write(continuation(random));
                case 6 -> out.write(0xC0 + random.nextInt(32));
                case 7 -> { out.write(0xE0 + random.nextInt(16)); out.write(continuation(random)); }
                case 8 -> { out.write(0xC0); out.write(0x80); }
                case 9 -> { out.write(0xE0); out.write(0x80); out.write(continuation(random)); }
                case 10 -> out.write(0xF8 + random.nextInt(8));
                case 11 -> { out.write(0xC0 + random.nextInt(48)); out.write(0x41 + random.nextInt(26)); }
                case 12 -> out.write(0x01 + random.nextInt(0x7F));
                case 13 -> out.write(0x01 + random.nextInt(0x7F));
                case 14 -> {
                    out.write(0xF5 + random.nextInt(3));
                    out.write(continuation(random));
                    out.write(continuation(random));
                    out.write(continuation(random));
                }
                default -> out.write(1 + random.nextInt(255));
            }
        }
        return out.toByteArray();
    }

    private static int continuation(Random random) {
        return 0x80 + random.nextInt(64);
    }

    private static String randomCodePoints(Random random) {
        int count = random.nextInt(13);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < count; i++) {
            switch (random.nextInt(5)) {
                case 0 -> s.appendCodePoint(0x01 + random.nextInt(0x7F));
                case 1 -> s.appendCodePoint(0x80 + random.nextInt(0x780));
                case 2 -> s.appendCodePoint(0x800 + random.nextInt(0xD000));
                case 3 -> s.appendCodePoint(0xE000 + random.nextInt(0x2000));
                default -> s.appendCodePoint(0x10000 + random.nextInt(0x100000));
            }
        }
        return s.toString();
    }

    /** Random code-unit strings, plus the fixed cases the encoder is documented on. */
    private static List<String> encodeCorpus(int count) {
        Random random = new Random(0x47535546436872L);
        List<String> corpus = new ArrayList<>(List.of(
                "", JniStringCodecTest.string(0), JniStringCodecTest.string(0x61, 0, 0x62), "DejaVu Sans",
                JniStringCodecTest.string(0x63, 0x61, 0x66, 0xE9), JniStringCodecTest.string(0x65E5, 0x672C, 0x8A9E),
                new String(Character.toChars(0x1F600)), JniStringCodecTest.string(0xD800),
                JniStringCodecTest.string(0xDC00, 0x41), JniStringCodecTest.string(0x41, 0xDFFF),
                JniStringCodecTest.string(0xDE00, 0xD83D), new String(Character.toChars(0x10FFFF)),
                JniStringCodecTest.string(0x0001, 0x007F, 0x0080, 0x07FF, 0x0800, 0xFFFF, 0xD7FF, 0xE000)));
        while (corpus.size() < count) {
            corpus.add(JniStringCodecTest.randomCodeUnits(random, 24));
        }
        return corpus.subList(0, count);
    }

    // ---------------------------------------------------------------------------------------------
    // Golden cells
    // ---------------------------------------------------------------------------------------------

    private static byte[] cell(String hex) {
        return "-".equals(hex) ? new byte[0] : HEX.parseHex(hex);
    }

    /** UTF-16 code units as two bytes each, big-endian: the golden's spelling of a {@code String}. */
    private static byte[] toBytes(char[] chars) {
        byte[] bytes = new byte[chars.length * 2];
        for (int i = 0; i < chars.length; i++) {
            bytes[2 * i] = (byte) (chars[i] >> 8);
            bytes[2 * i + 1] = (byte) chars[i];
        }
        return bytes;
    }

    private static char[] toChars(byte[] bytes) {
        if (bytes.length % 2 != 0) {
            throw new AssertionError("odd number of bytes for UTF-16 code units: " + HEX.formatHex(bytes));
        }
        char[] chars = new char[bytes.length / 2];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF));
        }
        return chars;
    }

    private static String units(char[] chars) {
        StringBuilder s = new StringBuilder("[");
        for (char c : chars) {
            if (s.length() > 1) {
                s.append(' ');
            }
            s.append(String.format("%04X", (int) c));
        }
        return s.append(']').toString();
    }
}
