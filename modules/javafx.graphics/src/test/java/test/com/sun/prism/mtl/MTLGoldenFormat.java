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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The files of the Metal goldens and their comparison.
 * <ul>
 * <li>{@code .txt}: UTF-8, LF, {@code #} comment lines, then sorted {@code key=value} lines. A key written twice is
 * an error, {@link #NULL} is a null value, and backslash, newline and carriage return are escaped. Keys prefixed
 * {@code machine.} gate the comparison; {@code capture.} and {@code info.} keys are recorded and never compared.</li>
 * <li>{@code .bin}: a zlib (RFC 1950) stream, {@code Deflater} level 9. Inflated, it is the frames in step order,
 * each {@code width * height} big-endian int32 values, row-major from the top row, exactly as
 * {@code RTTexture.getPixels()} returns them (premultiplied {@code 0xAARRGGBB}).</li>
 * </ul>
 * A frame key's value is {@code <w>x<h>;offset=<n>;sha256=<hex>}, {@code offset} being the byte offset of the frame
 * in the inflated payload. The comparison is exact; there is no tolerance parameter anywhere in this class.
 */
final class MTLGoldenFormat {

    static final String NULL = "#null";
    static final String MACHINE_PREFIX = "machine.";
    static final String CAPTURE_PREFIX = "capture.";
    static final String INFO_PREFIX = "info.";

    private static final Pattern FRAME = Pattern.compile("(\\d+)x(\\d+);offset=(\\d+);sha256=([0-9a-f]{64})");
    private static final int MAX_REPORTED = 60;
    private static final int MAX_REPORTED_PIXELS = 8;

    private MTLGoldenFormat() {
    }

    /** A sorted map that rejects a second value for a key: a golden key written twice is a corpus bug. */
    static final class GoldenMap extends TreeMap<String, String> {

        GoldenMap() {
        }

        GoldenMap(Map<String, String> initial) {
            initial.forEach(this::put);
        }

        @Override
        public String put(String key, String value) {
            if (containsKey(key)) {
                throw new IllegalStateException("golden key written twice: " + key);
            }
            return super.put(key, value == null ? NULL : value);
        }
    }

    /** One frame as its key value describes it. */
    record FrameRef(int width, int height, long offset, String sha256) {

        long byteLength() {
            return 4L * width * height;
        }

        FrameRef rebased(long base) {
            return new FrameRef(width, height, offset - base, sha256);
        }

        String value() {
            return frameValue(width, height, offset, sha256);
        }
    }

    static String nullable(String value) {
        return value == null ? NULL : value;
    }

    static boolean isBodyKey(String key) {
        return !key.startsWith(MACHINE_PREFIX) && !key.startsWith(CAPTURE_PREFIX) && !key.startsWith(INFO_PREFIX);
    }

    static String frameValue(int width, int height, long offset, String sha256) {
        return width + "x" + height + ";offset=" + offset + ";sha256=" + sha256;
    }

    /** The frame a key value describes, or {@code null} when the value is not a frame. */
    static FrameRef frame(String value) {
        Matcher matcher = FRAME.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        return new FrameRef(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                            Long.parseLong(matcher.group(3)), matcher.group(4));
    }

    static byte[] frameBytes(int[] pixels) {
        ByteBuffer bytes = ByteBuffer.allocate(pixels.length * 4);
        bytes.asIntBuffer().put(pixels);
        return bytes.array();
    }

    static int[] framePixels(byte[] payload, FrameRef frame) {
        int[] pixels = new int[frame.width() * frame.height()];
        ByteBuffer.wrap(payload, Math.toIntExact(frame.offset()), Math.toIntExact(frame.byteLength()))
                  .asIntBuffer().get(pixels);
        return pixels;
    }

    static byte[] slice(byte[] payload, FrameRef frame) {
        int from = Math.toIntExact(frame.offset());
        byte[] bytes = new byte[Math.toIntExact(frame.byteLength())];
        System.arraycopy(payload, from, bytes, 0, bytes.length);
        return bytes;
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(Path file) throws IOException {
        return sha256Hex(Files.readAllBytes(file));
    }

    static Map<String, String> parse(String text) {
        Map<String, String> parsed = new LinkedHashMap<>();
        int line = 0;
        for (String entry : text.split("\n", -1)) {
            line++;
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int equals = entry.indexOf('=');
            if (equals < 1) {
                throw new IllegalArgumentException("golden line " + line + " is not key=value: " + entry);
            }
            String key = entry.substring(0, equals);
            if (parsed.put(key, unescape(entry.substring(equals + 1))) != null) {
                throw new IllegalArgumentException("golden line " + line + " repeats key " + key);
            }
        }
        return parsed;
    }

    static String format(Map<String, String> values, List<String> header) {
        StringBuilder text = new StringBuilder(1 << 16);
        for (String comment : header) {
            text.append(comment.isEmpty() ? "#" : "# " + comment).append('\n');
        }
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            String key = entry.getKey();
            if (key.isEmpty() || key.startsWith("#") || key.indexOf('=') >= 0 || key.indexOf('\n') >= 0
                    || key.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("not a golden key: " + key);
            }
            text.append(key).append('=').append(escape(nullable(entry.getValue()))).append('\n');
        }
        return text.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder plain = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 == value.length()) {
                plain.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> plain.append('\n');
                case 'r' -> plain.append('\r');
                case '\\' -> plain.append('\\');
                default -> plain.append('\\').append(next);
            }
        }
        return plain.toString();
    }

    static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
            byte[] chunk = new byte[1 << 16];
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static byte[] inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] chunk = new byte[1 << 16];
            while (!inflater.finished()) {
                int count = inflater.inflate(chunk);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("the golden payload is a truncated zlib stream");
                }
                out.write(chunk, 0, count);
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("the golden payload has bytes after its zlib stream");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("the golden payload is not a zlib stream", e);
        } finally {
            inflater.end();
        }
    }

    /**
     * Every difference between the body keys of {@code golden} and {@code actual} within {@code scope}. Frame values
     * are compared with their offsets made relative to the first frame of each side, so that a profile verified on
     * its own lines up with its slice of the golden.
     */
    static List<String> differences(Map<String, String> golden, Map<String, String> actual,
                                    Predicate<String> scope) {
        Map<String, String> expected = relativeFrames(golden, scope);
        Map<String, String> found = relativeFrames(actual, scope);
        TreeSet<String> keys = new TreeSet<>(expected.keySet());
        keys.addAll(found.keySet());
        List<String> differences = new ArrayList<>();
        for (String key : keys) {
            String want = expected.get(key);
            String got = found.get(key);
            if (want == null) {
                differences.add("only in this run: " + key + "=" + got);
            } else if (got == null) {
                differences.add("only in the golden: " + key + "=" + want);
            } else if (!want.equals(got)) {
                differences.add(key + ": golden=" + want + " now=" + got);
            }
        }
        return differences;
    }

    /** The body keys in {@code scope}, frame offsets rebased to the smallest frame offset among them. */
    static Map<String, String> relativeFrames(Map<String, String> values, Predicate<String> scope) {
        long base = Long.MAX_VALUE;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            FrameRef frame = isBodyKey(entry.getKey()) && scope.test(entry.getKey()) ? frame(entry.getValue()) : null;
            if (frame != null) {
                base = Math.min(base, frame.offset());
            }
        }
        Map<String, String> relative = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!isBodyKey(key) || !scope.test(key)) {
                continue;
            }
            FrameRef frame = frame(entry.getValue());
            relative.put(key, frame == null ? entry.getValue() : frame.rebased(base).value());
        }
        return relative;
    }

    static String report(List<String> differences) {
        List<String> shown = differences.subList(0, Math.min(MAX_REPORTED, differences.size()));
        return differences.size() + " difference(s)" + (differences.size() > MAX_REPORTED
                ? " (first " + MAX_REPORTED + " shown)" : "") + ":\n  " + String.join("\n  ", shown);
    }

    /**
     * How two frames of the same size differ: the changed pixel count, their bounding box, the largest channel
     * delta and the first changed coordinates.
     */
    static String describe(int width, int height, int[] golden, int[] actual) {
        int changed = 0;
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        int maxDelta = 0;
        List<String> first = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int want = golden[y * width + x];
                int got = actual[y * width + x];
                if (want == got) {
                    continue;
                }
                changed++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                for (int shift = 0; shift < 32; shift += 8) {
                    maxDelta = Math.max(maxDelta, Math.abs(((want >>> shift) & 0xff) - ((got >>> shift) & 0xff)));
                }
                if (first.size() < MAX_REPORTED_PIXELS) {
                    first.add("(" + x + "," + y + ") golden=" + String.format("%08x", want)
                            + " now=" + String.format("%08x", got));
                }
            }
        }
        if (changed == 0) {
            return "no pixel differs";
        }
        return changed + " of " + (width * height) + " pixels differ in [" + minX + "," + minY + ".." + maxX + ","
                + maxY + "], max channel delta " + maxDelta + ", first: " + String.join(" ", first);
    }

    /**
     * Writes {@code <stem>.golden.pam}, {@code .actual.pam} and {@code .diff.pam} (P7, {@code RGB_ALPHA}, the stored
     * premultiplied channels); the diff holds the per-channel absolute delta, opaque where a pixel differs.
     */
    static void writeDiff(Path directory, String stem, int width, int height, int[] golden, int[] actual)
            throws IOException {
        Files.createDirectories(directory);
        int[] delta = new int[golden.length];
        for (int i = 0; i < golden.length; i++) {
            int value = 0;
            for (int shift = 0; shift < 24; shift += 8) {
                value |= Math.abs(((golden[i] >>> shift) & 0xff) - ((actual[i] >>> shift) & 0xff)) << shift;
            }
            delta[i] = golden[i] == actual[i] ? 0 : value | 0xff000000;
        }
        Files.write(directory.resolve(stem + ".golden.pam"), pam(width, height, golden));
        Files.write(directory.resolve(stem + ".actual.pam"), pam(width, height, actual));
        Files.write(directory.resolve(stem + ".diff.pam"), pam(width, height, delta));
    }

    static byte[] pam(int width, int height, int[] argb) {
        String header = "P7\nWIDTH " + width + "\nHEIGHT " + height + "\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\n"
                + "ENDHDR\n";
        byte[] head = header.getBytes(StandardCharsets.US_ASCII);
        byte[] image = new byte[head.length + argb.length * 4];
        System.arraycopy(head, 0, image, 0, head.length);
        for (int i = 0, o = head.length; i < argb.length; i++) {
            image[o++] = (byte) (argb[i] >>> 16);
            image[o++] = (byte) (argb[i] >>> 8);
            image[o++] = (byte) argb[i];
            image[o++] = (byte) (argb[i] >>> 24);
        }
        return image;
    }

    static boolean uniform(int[] pixels) {
        for (int pixel : pixels) {
            if (pixel != pixels[0]) {
                return false;
            }
        }
        return true;
    }
}
