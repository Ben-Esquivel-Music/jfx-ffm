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

package test.com.sun.pisces;

import com.sun.pisces.GradientColorMap;
import com.sun.pisces.JavaSurface;
import com.sun.pisces.PiscesRenderer;
import com.sun.pisces.RendererBase;
import com.sun.pisces.Transform6;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Parity corpus of the Pisces software compositor ({@code com.sun.pisces}): a catalogue of operations designed
 * from the C in {@code native-prism-sw} at commit {@code 42a5072596} to reach every branch and special case the
 * public {@link PiscesRenderer}, {@link JavaSurface} and {@link Transform6} API can reach, plus seeded random
 * operation sequences, with the outcome of every step recorded.
 * <p>
 * The golden {@value #CORPUS_RESOURCE} was captured on Windows x64 from the C of commit {@code 42a5072596} (the
 * commit that landed US-059; the C did not change after it until US-046 replaced it with the Java compositor and
 * deleted it). It is the GZIP of a {@link DataOutputStream} stream: a header (magic, {@value #FORMAT_VERSION}, the
 * capture commit, the capture platform, the producer - {@value #C_PRODUCER} for the C), then per step its name, its
 * float class, how its frame is stored, its outcome ({@code ""} for a normal return, otherwise
 * {@code "<exception class>: <message>"}), the surface size, the frame - every int of the surface array, or the
 * SHA-256 of their big-endian bytes - and an optional auxiliary int array: what {@code getRGB} wrote, the delta
 * row after {@code emitAndClearAlphaRow}, or a pixel region read back from a surface whose frame is too large to
 * store. Frames are stored in full up to {@value #FULL_FRAME_LIMIT} pixels; the two larger surfaces,
 * 33000 x 2 and 2 x 33000, store the SHA-256 of each frame and read the pixels around coordinate 32768 back into
 * the aux array of every step.
 * <p>
 * The test reaches the compositor through the public API only. The C produced the golden, and the Java
 * compositor that replaced it has to reproduce every step exactly on every platform, float steps included: it
 * runs the C's float arithmetic in the C's order and widths and has no platform-dependent arithmetic. A step is a
 * float step when its own operation runs float code - gradient set-up and generation, the
 * {@code pisces_transform_invert} of a texture transform, the {@code pow} tables of the LCD gamma - or when an
 * earlier step of the same case did and no full {@code setRGB} has overwritten the surface since, because the
 * surface then still carries float-derived pixels. That class is recorded with the step, checked against the
 * script and named in a failure as a reading aid; it excuses no mismatch. Every mismatch is reported in one
 * failure, naming the step, its operation, and for a stored frame the first differing pixel and the number of
 * differing pixels.
 * <p>
 * The catalogue never takes the paths on which the C dereferences a null pointer or writes outside the caller's
 * array, because a crash is not an outcome a golden can hold: an {@code emitAndClearAlphaRow}, mask or fill after a
 * {@code drawImage} without a new paint (the texture paint is left with no texture; the one exception follows
 * below), an LCD mask with any paint but a colour or any rule but {@code SRC_OVER}, an alpha mask under
 * {@code COMPOSITE_CLEAR}, an {@code emitAndClearAlphaRow} whose delta range ends exactly at the end of the array,
 * a coverage sum outside the alpha map, and {@code WRAP_REPEAT} on a texture sub-rectangle 65536 texels wide or
 * high.
 * <p>
 * Two inputs are not steps, because they need more heap than some test JVMs have: a texture of 2 GiB, refused by
 * the size guard of {@code psw_renderer_set_texture}, and a mask row of 536870911 pixels under the multiply paint a
 * {@code drawImage} leaves behind, the one mask after a {@code drawImage} on which the C reads no texture.
 * {@link #aTextureOfTwoGibibytesFailsTheRendererBufferGuard()} and
 * {@link #aStaleMultiplyPaintOnARowTooWideToMultiplyCompositesItsPaintBufferAsItIs()} hold the C's outcomes for
 * them. The C's disposal of surfaces and renderers (the {@code com.sun.prism.impl.Disposer} records of its FFM
 * path) had no outcome to record and is not part of the catalogue; the Java compositor holds nothing to dispose.
 * <p>
 * Capture mode: {@code -D}{@value #CAPTURE_PROPERTY}{@code =<absolute path>} writes the corpus there (and a
 * readable step index next to it, {@code <path>.index.txt}), then aborts, so a capture run is reported as skipped.
 * Its header names the producer that {@code -D}{@value #PRODUCER_PROPERTY} gives, {@value #JAVA_PRODUCER} by
 * default. On a tree without the C a capture records the Java compositor, so it proves only that the build agrees
 * with itself. A capture from the C means running this class on a tree that still has the C, such as commit
 * {@code 7d5ffd66f0} with this class copied in, with
 * {@code -D}{@value #PRODUCER_PROPERTY}{@code =}{@value #C_PRODUCER}.
 * {@code -D}{@value #GOLDEN_OVERRIDE_PROPERTY}{@code =<absolute path>} compares against that file instead of the
 * resource; it exists to prove that a changed record fails. Regenerating the golden is a behaviour change, never a
 * test fix.
 */
public class PiscesCorpusGoldenTest {

    static final String CORPUS_RESOURCE = "pisces-corpus-42a5072596.bin";
    static final String CAPTURE_COMMIT = "42a5072596";
    static final String CAPTURE_PROPERTY = "pisces.corpus.capture";
    static final String GOLDEN_OVERRIDE_PROPERTY = "pisces.corpus.golden";

    /** Names the producer that a capture writes into its header; {@value #C_PRODUCER} for a capture from the C. */
    static final String PRODUCER_PROPERTY = "pisces.corpus.producer";

    /** The producer the header of {@value #CORPUS_RESOURCE} names: the C of {@value #CAPTURE_COMMIT}. */
    static final String C_PRODUCER = "native";

    /** The producer a capture writes by default: the Java compositor. */
    static final String JAVA_PRODUCER = "java";

    static final int MAGIC = 0x50434F52;
    static final int FORMAT_VERSION = 1;
    static final int END_MARK = 0x454E4421;
    static final int FLAG_FLOAT = 1;
    static final int FLAG_HASH = 2;
    static final int FULL_FRAME_LIMIT = 32768;
    static final int MAX_CORPUS_BYTES = 2 * 1024 * 1024;

    static final int RANDOM_SEQUENCES = 64;
    static final int RANDOM_OPS = 24;

    private static final ParityGate.Ledger LEDGER = ParityGate.ledger(PiscesCorpusGoldenTest.class);

    @AfterAll
    static void theOracleRan() {
        LEDGER.assertOracleRan();
    }

    @Test
    public void reproducesTheCorpusCapturedFromTheC() throws IOException {
        Script script = new Script();
        List<StepRecord> actual = script.run();

        String capture = System.getProperty(CAPTURE_PROPERTY);
        if (capture != null && !capture.isBlank()) {
            assertEquals(List.of(), script.anomalies(), "generated steps that did not end as intended");
            String producer = System.getProperty(PRODUCER_PROPERTY, JAVA_PRODUCER);
            byte[] bytes = encode(new Corpus(CAPTURE_COMMIT, platform(), producer, actual));
            assertTrue(bytes.length <= MAX_CORPUS_BYTES, "corpus is " + bytes.length + " bytes, above the "
                    + MAX_CORPUS_BYTES + "-byte budget");
            Path path = Path.of(capture);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, bytes);
            Files.writeString(Path.of(capture + ".index.txt"), index(actual), StandardCharsets.UTF_8);
            System.out.println("captured " + actual.size() + " steps (" + bytes.length + " bytes) to " + path);
            Assumptions.abort("corpus captured to " + capture + "; a capture run verifies nothing");
        }

        byte[] golden = readGolden();
        assertTrue(golden.length <= MAX_CORPUS_BYTES, "golden is " + golden.length + " bytes");
        Corpus expected = decode(golden);
        LEDGER.oracleAvailable();
        String disagreement = scriptDisagreement(actual, expected.steps());
        if (disagreement != null) {
            fail("the script and the corpus " + describeSource() + " disagree: " + disagreement
                    + "; a changed script needs a new capture from the C of " + CAPTURE_COMMIT
                    + " (see the class comment)");
        }
        List<Mismatch> mismatches = compare(actual, expected.steps());
        LEDGER.compared(actual.size());
        String report = report(mismatches, actual.size(), expected);
        System.out.println(report);
        if (!mismatches.isEmpty()) {
            fail(report);
        }
    }

    /**
     * The report contract, without the golden: a changed pixel of a stored frame, a changed SHA-256, a changed
     * outcome and a changed auxiliary array each fail and name their step, a float step as much as an integer
     * one, in step order; and the encoding round-trips.
     */
    @Test
    public void everyKindOfMismatchIsReportedWithItsStep() throws IOException {
        int[] frame = {1, 2, 3, 4, 5, 6};
        List<StepRecord> steps = List.of(
                stepRecord("case #0 integer frame", false, false, "", 3, 2, frame, null),
                stepRecord("case #1 float frame", true, false, "", 3, 2, frame, null),
                stepRecord("case #2 hashed frame", false, true, "", 3, 2, frame, null),
                stepRecord("case #3 exception", false, false,
                        "java.lang.IllegalArgumentException: X is out of surface", 3, 2, frame, new int[] {7, 8, 9}));
        Corpus corpus = decode(encode(new Corpus(CAPTURE_COMMIT, "test", C_PRODUCER, steps)));
        assertEquals(steps.size(), corpus.steps().size());
        assertEquals(null, scriptDisagreement(steps, corpus.steps()));
        assertEquals(List.of(), compare(steps, corpus.steps()), "an unchanged corpus compares clean");

        int[] changed = frame.clone();
        changed[4] ^= 0x100;
        List<StepRecord> perturbed = List.of(
                stepRecord("case #0 integer frame", false, false, "", 3, 2, changed, null),
                stepRecord("case #1 float frame", true, false, "", 3, 2, changed, null),
                stepRecord("case #2 hashed frame", false, true, "", 3, 2, changed, null),
                stepRecord("case #3 exception", false, false, "", 3, 2, frame, new int[] {7, 8, 10}));
        List<Mismatch> mismatches = compare(perturbed, corpus.steps());
        assertEquals(List.of(0, 1, 2, 3), mismatches.stream().map(Mismatch::index).toList());
        assertTrue(mismatches.get(0).detail().equals("pixel (1,1) expected 0x00000005 but was 0x00000105"
                + " (first of 1 differing pixels)"), mismatches.get(0).detail());
        assertTrue(mismatches.get(2).detail().startsWith("frame SHA-256 expected "), mismatches.get(2).detail());
        assertTrue(mismatches.get(3).detail().equals("outcome expected 'java.lang.IllegalArgumentException:"
                + " X is out of surface' but was 'normal return'; aux[2] expected 0x00000009 but was 0x0000000A"
                + " (first of 1 differing ints)"), mismatches.get(3).detail());
        assertEquals("pixel array expected 6 ints but was 5",
                compare(List.of(stepRecord("case #0 integer frame", false, false, "", 3, 2, new int[5], null)),
                        corpus.steps().subList(0, 1)).get(0).detail());

        String text = report(mismatches, perturbed.size(), corpus);
        assertTrue(text.startsWith("4 of 4 steps differ from the corpus captured from the C of " + CAPTURE_COMMIT
                + " on test (this run: " + platform() + ")"), text);
        assertTrue(text.contains("step 0 'case #0 integer frame' [integer]: pixel (1,1)"), text);
        assertTrue(text.contains("step 1 'case #1 float frame' [float]: pixel (1,1)"), text);
        assertTrue(text.indexOf("step 0 '") < text.indexOf("step 1 '")
                && text.indexOf("step 1 '") < text.indexOf("step 2 '")
                && text.indexOf("step 2 '") < text.indexOf("step 3 '"), "every step, in step order: " + text);
        Corpus captured = new Corpus(CAPTURE_COMMIT, "test", JAVA_PRODUCER, corpus.steps());
        assertTrue(report(mismatches, perturbed.size(), captured).startsWith("4 of 4 steps differ from the corpus"
                + " captured from the Java compositor of " + CAPTURE_COMMIT), "a captured corpus names its producer");
    }

    private static StepRecord stepRecord(String name, boolean floatStep, boolean hashed, String outcome, int width,
            int height, int[] frame, int[] aux) {
        return new StepRecord(name, floatStep, hashed, outcome, width, height, frame, sha256(frame), aux);
    }

    /**
     * The one input of the catalogue that a golden step cannot hold: the size guard of
     * {@code psw_renderer_set_texture} ({@code w < (INT_MAX / h) / (jint) sizeof(jint)}, {@code prism_sw_api.c:658} at
     * 42a5072596) refuses a texture of 536870911 x 1 texels with its out-of-memory status before it copies
     * anything, and the paint stays what it was. The texture array alone takes 2 GiB of heap, which not every test
     * JVM has, and a step whose own allocation failed would record a different outcome; so the outcome of the C
     * at 42a5072596 is written here instead. The test also needs room for the 2 GiB copy that the guard refuses:
     * a copy that fails for want of heap ends in the same out-of-memory status, so without that room a broken guard
     * would pass unnoticed. Without heap for the texture and the copy the test is skipped, or fails under
     * {@code -Djfx.parity.require=true}.
     * <p>
     * This method and {@link #aStaleMultiplyPaintOnARowTooWideToMultiplyCompositesItsPaintBufferAsItIs()} need a
     * maximum heap of about 4.6 GiB, the default on machines with at least about 19 GB of RAM. CI skips them; the
     * parity gate run with {@code -Djfx.parity.require=true} owns them. Where the default heap is smaller, pass
     * {@code -Djfx.headless.args=-Xmx6g}; with {@code -DHEADLESS_TEST=true} that property replaces the headless
     * arguments of that profile, so pass those arguments in full plus {@code -Xmx6g}.
     */
    @Test
    public void aTextureOfTwoGibibytesFailsTheRendererBufferGuard() {
        int width = Integer.MAX_VALUE / 4; // (INT_MAX / h) / 4 for h == 1: the narrowest texture the guard refuses
        int[] texels = allocateOrNull(width);
        boolean fits = texels != null && allocateOrNull(width) != null;
        LEDGER.requireOracle(fits, () -> "a texture of " + width + " ints (2 GiB) and 2 GiB of headroom for the copy"
                + " the guard refuses could not be allocated with a maximum heap of "
                + Runtime.getRuntime().maxMemory() + " bytes");
        int[] pixels = new int[4 * 3];
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, ARGB_PRE, 4, 3));
        pr.setColor(70, 140, 210, 255);
        OutOfMemoryError e = assertThrows(OutOfMemoryError.class, () -> pr.setTexture(ARGB_PRE, texels, width, 1,
                width, new Transform6(), CLAMP, false, false));
        assertEquals("Allocation of internal renderer buffer failed.", e.getMessage());
        pr.fillRect(0, 0, fx(4), fx(3));
        assertArrayEquals(filled(4 * 3, 0xFF468CD2), pixels, "the colour (70, 140, 210, 255) is still the paint");
        LEDGER.compared();
    }

    private static int[] allocateOrNull(int length) {
        try {
            return new int[length];
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static byte[] allocateBytesOrNull(int length) {
        try {
            return new byte[length];
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * The other input that a golden step cannot hold. With a linear or radial gradient as the previous paint,
     * {@code genTexturePaintMultiply} ({@code PiscesPaint.c:1168} at 42a5072596) multiplies only rows of
     * {@code w < INT_MAX / height / sizeof(jint)} pixels; for a wider row it prints
     * {@code Invalid dimensions: width: <w>, height: <height>} to {@link System#err}, returns normally, and
     * the blit composites the paint buffer as it is. After a {@code drawImage} in {@code IMAGE_MODE_MULTIPLY} the
     * paint stays such a multiply paint with no texture, and an alpha mask row of 536870911 pixels, the narrowest
     * that test refuses at height 1, gets there before the missing texture, which every narrower row reads. Under
     * {@code COMPOSITE_SRC} at full coverage the row then shows the buffer: zeros when the buffer has just grown to
     * the row, otherwise what the earlier operations left in it - here the previous row of a one-colour linear
     * gradient, with the four pixels the {@code drawImage} multiplied at its start. The outcomes are those of the C
     * at 42a5072596. The row needs a 2 GiB surface, a 512 MiB mask and room for a paint buffer of 2 GiB more on
     * the heap; without them the test is skipped, or fails under {@code -Djfx.parity.require=true}.
     * {@link PiscesMultiplyRowGuardTest} pins the guard and its line on every heap.
     * <p>
     * This method and {@link #aTextureOfTwoGibibytesFailsTheRendererBufferGuard()} need a maximum heap of about
     * 4.6 GiB, the default on machines with at least about 19 GB of RAM. CI skips them; the parity gate run with
     * {@code -Djfx.parity.require=true} owns them. Where the default heap is smaller, pass
     * {@code -Djfx.headless.args=-Xmx6g}; with {@code -DHEADLESS_TEST=true} that property replaces the headless
     * arguments of that profile, so pass those arguments in full plus {@code -Xmx6g}.
     */
    @Test
    public void aStaleMultiplyPaintOnARowTooWideToMultiplyCompositesItsPaintBufferAsItIs() {
        int width = Integer.MAX_VALUE / 4; // INT_MAX / 1 / sizeof(jint): the narrowest row the multiply refuses
        int[] pixels = allocateOrNull(width);
        byte[] mask = pixels == null ? null : allocateBytesOrNull(width);
        boolean fits = mask != null && allocateOrNull(width) != null;
        LEDGER.requireOracle(fits, () -> "a row of " + width + " pixels (a 2 GiB surface, a 512 MiB mask, 2 GiB of"
                + " headroom for the paint buffer) could not be allocated with a maximum heap of "
                + Runtime.getRuntime().maxMemory() + " bytes");
        Arrays.fill(mask, (byte) 0xFF);
        Arrays.fill(pixels, 0xFF102030);
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, ARGB_PRE, width, 1));
        int[] fractions = {0, 1 << 16};

        pr.setLinearGradient(0, 0, fx(8), 0, fractions, new int[] {0xFF0000FF, 0xFFFF0000},
                GradientColorMap.CYCLE_NONE, null);
        pr.drawImage(ARGB_PRE, MULTIPLY, filled(16, 0xFFFFFFFF), 4, 4, 0, 4, new Transform6(), CLAMP, false, 0, 0,
                fx(4), fx(1), KEEP, KEEP, KEEP, KEEP, 0, 0, 3, 3, true);
        pr.setCompositeRule(SRC);
        pr.fillAlphaMask(mask, 0, 0, width, 1, 0, width);
        assertUniform(pixels, 0, width, 0x00000000, "the paint buffer that grew to the row");

        pr.setLinearGradient(0, 0, fx(8), 0, fractions, new int[] {0xFF996633, 0xFF996633},
                GradientColorMap.CYCLE_NONE, null);
        pr.fillAlphaMask(mask, 0, 0, width, 1, 0, width);
        assertUniform(pixels, 0, width, 0xFF996633, "a one-colour linear gradient over the row");
        pr.setRadialGradient(fx(2), 0, fx(2), 0, fx(4), fractions, new int[] {0xFF3366CC, 0xFF3366CC},
                GradientColorMap.CYCLE_NONE, null);
        int[] texture = {
            0xFF808080, 0xFF00FF00, 0x80402010, 0xFFC0C0C0,
            0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000};
        pr.drawImage(ARGB_PRE, MULTIPLY, texture, 4, 4, 0, 4, new Transform6(), CLAMP, false, 0, 0, fx(4), fx(1),
                KEEP, KEEP, KEEP, KEEP, 0, 0, 3, 3, true);
        Arrays.fill(pixels, 0xFF102030);
        pr.fillAlphaMask(mask, 0, 0, width, 1, 0, width);
        assertEquals(List.of("FF1A3366", "FF006600", "800D0C0C", "FF274D99"),
                Arrays.stream(pixels, 0, 4).mapToObj(pixel -> f("%08X", pixel)).toList(),
                "the start of the paint buffer: the radial gradient of the drawImage multiplied with texture row 0");
        assertUniform(pixels, 4, width, 0xFF996633, "the rest of the paint buffer: the previous row");
        LEDGER.compared();
    }

    /** Fails naming the first pixel of {@code pixels[from, to)} that is not {@code expected}, and how many are not. */
    private static void assertUniform(int[] pixels, int from, int to, int expected, String what) {
        int first = -1;
        long differing = 0;
        for (int i = from; i < to; i++) {
            if (pixels[i] != expected) {
                if (first < 0) {
                    first = i;
                }
                differing++;
            }
        }
        if (first >= 0) {
            fail(f("%s: pixel %d expected 0x%08X but was 0x%08X (first of %d differing pixels in [%d, %d))", what,
                    first, expected, pixels[first], differing, from, to));
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * Report
     * ------------------------------------------------------------------------------------------- */

    static String platform() {
        return System.getProperty("os.name", "?") + " " + System.getProperty("os.arch", "?");
    }

    /** The verdict line, then every mismatch in step order; every mismatch fails, on every platform. */
    static String report(List<Mismatch> mismatches, int stepCount, Corpus expected) {
        String producer = C_PRODUCER.equals(expected.producer()) ? "the C" : "the Java compositor";
        StringBuilder text = new StringBuilder();
        text.append(mismatches.size()).append(" of ").append(stepCount).append(" steps differ from the corpus")
                .append(" captured from ").append(producer).append(" of ").append(expected.commit())
                .append(" on ").append(expected.platform()).append(" (this run: ").append(platform()).append(")");
        for (Mismatch mismatch : mismatches) {
            text.append(System.lineSeparator()).append("  ").append(mismatch.describe());
        }
        return text.toString();
    }

    /* ---------------------------------------------------------------------------------------------
     * Corpus model, encoding and comparison
     * ------------------------------------------------------------------------------------------- */

    /**
     * One step. {@code frame} is null in a decoded record whose frame is stored as a hash; {@code sha256} is the
     * hash of the frame's big-endian bytes in every record.
     */
    record StepRecord(String name, boolean floatStep, boolean hashed, String outcome, int width, int height,
            int[] frame, byte[] sha256, int[] aux) {
    }

    /** A decoded corpus: the header (capture commit, capture platform, producer) and the steps. */
    record Corpus(String commit, String platform, String producer, List<StepRecord> steps) {
    }

    /** One differing step: its index, the script's record of it, and what differs. */
    record Mismatch(int index, StepRecord step, String detail) {

        String describe() {
            return "step " + index + " '" + step.name() + "' [" + (step.floatStep() ? "float" : "integer") + "]: "
                    + detail;
        }
    }

    static byte[] encode(Corpus corpus) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(bytes)))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(corpus.commit());
            out.writeUTF(corpus.platform());
            out.writeUTF(corpus.producer());
            out.writeInt(corpus.steps().size());
            for (StepRecord step : corpus.steps()) {
                out.writeUTF(step.name());
                out.writeByte((step.floatStep() ? FLAG_FLOAT : 0) | (step.hashed() ? FLAG_HASH : 0));
                out.writeUTF(step.outcome());
                out.writeInt(step.width());
                out.writeInt(step.height());
                if (step.hashed()) {
                    out.write(step.sha256());
                } else {
                    writeInts(out, step.frame());
                }
                if (step.aux() == null) {
                    out.writeInt(-1);
                } else {
                    writeInts(out, step.aux());
                }
            }
            out.writeInt(END_MARK);
        }
        return bytes.toByteArray();
    }

    private static void writeInts(DataOutputStream out, int[] values) throws IOException {
        out.writeInt(values.length);
        for (int value : values) {
            out.writeInt(value);
        }
    }

    static Corpus decode(byte[] gzipped) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gzipped)))) {
            assertEquals(MAGIC, in.readInt(), "corpus magic");
            assertEquals(FORMAT_VERSION, in.readInt(), "corpus format version");
            String commit = in.readUTF();
            String platform = in.readUTF();
            String producer = in.readUTF();
            int count = in.readInt();
            List<StepRecord> steps = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                int flags = in.readUnsignedByte();
                String outcome = in.readUTF();
                int width = in.readInt();
                int height = in.readInt();
                boolean hashed = (flags & FLAG_HASH) != 0;
                int[] frame = null;
                byte[] sha;
                if (hashed) {
                    sha = new byte[32];
                    in.readFully(sha);
                } else {
                    frame = readInts(in);
                    sha = sha256(frame);
                }
                int auxLength = in.readInt();
                int[] aux = null;
                if (auxLength >= 0) {
                    aux = new int[auxLength];
                    for (int a = 0; a < auxLength; a++) {
                        aux[a] = in.readInt();
                    }
                }
                steps.add(new StepRecord(name, (flags & FLAG_FLOAT) != 0, hashed, outcome, width, height, frame,
                        sha, aux));
            }
            assertEquals(END_MARK, in.readInt(), "corpus end mark after " + count + " steps");
            assertEquals(-1, in.read(), "no data after the corpus end mark");
            return new Corpus(commit, platform, producer, steps);
        }
    }

    private static int[] readInts(DataInputStream in) throws IOException {
        int[] values = new int[in.readInt()];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readInt();
        }
        return values;
    }

    /** The first place the script's steps and the corpus's steps are not the same steps, or null. */
    static String scriptDisagreement(List<StepRecord> actual, List<StepRecord> expected) {
        for (int i = 0; i < Math.min(actual.size(), expected.size()); i++) {
            StepRecord a = actual.get(i);
            StepRecord e = expected.get(i);
            if (!a.name().equals(e.name()) || a.floatStep() != e.floatStep() || a.hashed() != e.hashed()) {
                return "step " + i + " is '" + a.name() + "' (" + (a.floatStep() ? "float" : "integer")
                        + (a.hashed() ? ", hashed" : "") + ") in the script and '" + e.name() + "' ("
                        + (e.floatStep() ? "float" : "integer") + (e.hashed() ? ", hashed" : "") + ") in the corpus";
            }
        }
        if (actual.size() != expected.size()) {
            return "the script has " + actual.size() + " steps and the corpus " + expected.size();
        }
        return null;
    }

    /** Every differing step, in step order, each with everything that differs in it. */
    static List<Mismatch> compare(List<StepRecord> actual, List<StepRecord> expected) {
        List<Mismatch> mismatches = new ArrayList<>();
        for (int i = 0; i < actual.size(); i++) {
            StepRecord a = actual.get(i);
            StepRecord e = expected.get(i);
            List<String> details = new ArrayList<>();
            if (!a.outcome().equals(e.outcome())) {
                details.add("outcome expected '" + outcomeText(e.outcome()) + "' but was '"
                        + outcomeText(a.outcome()) + "'");
            }
            if (a.width() != e.width() || a.height() != e.height()) {
                details.add("surface expected " + e.width() + "x" + e.height() + " but was " + a.width() + "x"
                        + a.height());
            } else if (e.hashed()) {
                if (!Arrays.equals(a.sha256(), e.sha256())) {
                    details.add("frame SHA-256 expected " + HexFormat.of().formatHex(e.sha256()) + " but was "
                            + HexFormat.of().formatHex(a.sha256()) + " (stored as a hash: no pixel data)");
                }
            } else {
                String frame = firstDifference("pixel", a.frame(), e.frame(), Math.max(1, e.width()));
                if (frame != null) {
                    details.add(frame);
                }
            }
            if (e.aux() == null || a.aux() == null) {
                if (e.aux() != a.aux()) {
                    details.add("aux expected " + (e.aux() == null ? "none" : e.aux().length + " ints") + " but was "
                            + (a.aux() == null ? "none" : a.aux().length + " ints"));
                }
            } else {
                String aux = firstDifference("aux", a.aux(), e.aux(), 0);
                if (aux != null) {
                    details.add(aux);
                }
            }
            if (!details.isEmpty()) {
                mismatches.add(new Mismatch(i, a, String.join("; ", details)));
            }
        }
        return mismatches;
    }

    private static String outcomeText(String outcome) {
        return outcome.isEmpty() ? "normal return" : outcome;
    }

    /**
     * The first differing element and the number of differing elements; a pixel is named (x,y) when
     * {@code width} is positive, an aux element by its index.
     */
    private static String firstDifference(String what, int[] actual, int[] expected, int width) {
        if (actual.length != expected.length) {
            return what + " array expected " + expected.length + " ints but was " + actual.length;
        }
        int first = -1;
        int count = 0;
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                if (first < 0) {
                    first = i;
                }
                count++;
            }
        }
        if (first < 0) {
            return null;
        }
        String where = width > 0 ? "(" + (first % width) + "," + (first / width) + ")" : "[" + first + "]";
        String noun = width > 0 ? " differing pixels)" : " differing ints)";
        return String.format(Locale.ROOT, "%s%s expected 0x%08X but was 0x%08X (first of %d%s", what,
                width > 0 ? " " + where : where, expected[first], actual[first], count, noun);
    }

    static byte[] sha256(int[] frame) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[4 * frame.length];
            for (int i = 0; i < frame.length; i++) {
                bytes[4 * i] = (byte) (frame[i] >>> 24);
                bytes[4 * i + 1] = (byte) (frame[i] >>> 16);
                bytes[4 * i + 2] = (byte) (frame[i] >>> 8);
                bytes[4 * i + 3] = (byte) frame[i];
            }
            return digest.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] readGolden() throws IOException {
        String override = System.getProperty(GOLDEN_OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Files.readAllBytes(Path.of(override));
        }
        try (InputStream in = PiscesCorpusGoldenTest.class.getResourceAsStream(CORPUS_RESOURCE)) {
            assertNotNull(in, "corpus resource " + CORPUS_RESOURCE + " is missing next to "
                    + PiscesCorpusGoldenTest.class.getName());
            return in.readAllBytes();
        }
    }

    private static String describeSource() {
        String override = System.getProperty(GOLDEN_OVERRIDE_PROPERTY);
        return (override != null && !override.isBlank()) ? override : CORPUS_RESOURCE;
    }

    private static String index(List<StepRecord> steps) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            StepRecord step = steps.get(i);
            text.append(i).append('\t').append(step.floatStep() ? "float" : "integer").append('\t')
                    .append(step.hashed() ? "hash" : "full").append('\t').append(step.name()).append('\t')
                    .append(outcomeText(step.outcome())).append('\n');
        }
        return text.toString();
    }

    /* ---------------------------------------------------------------------------------------------
     * Recording
     * ------------------------------------------------------------------------------------------- */

    /** What an action may hand back besides the surface: an array the call wrote. */
    static final class Aux {
        int[] ints;
    }

    @FunctionalInterface
    interface Action {
        void run(Aux aux);
    }

    /** Background patterns of {@link #background}. */
    static final int BG_ZERO = 0;
    static final int BG_OPAQUE = 1;
    static final int BG_TRANSLUCENT = 2;
    static final int BG_GARBAGE = 3;
    static final int BG_WHITE = 4;
    static final int BG_NONE = -1;

    /** Runs the steps of one case after another on one surface and renderer and records each. */
    static final class Recorder {
        final List<StepRecord> steps = new ArrayList<>();
        /** Generator steps whose outcome the generator did not intend; a capture must have none. */
        final List<String> anomalies = new ArrayList<>();
        private final Set<String> caseNames = new HashSet<>();
        String caseName;
        int width;
        int height;
        int[] pixels;
        JavaSurface surface;
        PiscesRenderer pr;
        boolean tainted;
        private int opNo;

        void begin(String name, int w, int h) {
            begin(name, w, h, w * h);
        }

        void begin(String name, int w, int h, int length) {
            start(name);
            width = w;
            height = h;
            pixels = new int[length];
            surface = new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, w, h);
            pr = new PiscesRenderer(surface);
        }

        /** A case without a surface: its steps construct surfaces and record empty frames. */
        void beginDetached(String name) {
            start(name);
            width = 0;
            height = 0;
            pixels = new int[0];
            surface = null;
            pr = null;
        }

        private void start(String name) {
            if (!caseNames.add(name)) {
                throw new AssertionError("duplicate case name " + name);
            }
            caseName = name;
            tainted = false;
            opNo = 0;
        }

        /** Records {@code outcome} of the step just taken as an anomaly unless {@code intended}. */
        void expect(boolean intended, String outcome) {
            if (!intended) {
                anomalies.add(steps.get(steps.size() - 1).name() + " -> " + outcome);
            }
        }

        /**
         * Runs one step. With {@code background} at {@link #BG_ZERO} or above the surface is first overwritten
         * with that pattern and the clip reset, which also clears the float taint of the case.
         *
         * @return the outcome, {@code ""} for a normal return
         */
        String step(String op, boolean floatOp, int background, Action action) {
            String name = caseName + " #" + (opNo++) + " " + op;
            if (background >= 0 && width > 0 && height > 0) {
                surface.setRGB(background(width, height, background), 0, width, 0, 0, width, height);
                pr.resetClip();
                tainted = false;
            }
            boolean floatStep = floatOp || tainted;
            Aux aux = new Aux();
            String outcome = "";
            try {
                action.run(aux);
            } catch (RuntimeException | OutOfMemoryError e) {
                outcome = e.getClass().getName() + ": " + e.getMessage();
            }
            tainted = floatStep;
            int[] frame = pixels.clone();
            boolean hashed = frame.length > FULL_FRAME_LIMIT;
            steps.add(new StepRecord(name, floatStep, hashed, outcome, width, height, frame, sha256(frame),
                    aux.ints == null ? null : aux.ints.clone()));
            return outcome;
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * Data
     * ------------------------------------------------------------------------------------------- */

    static int fx(double v) {
        return (int) (v * 65536.0);
    }

    static String f(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    static int[] filled(int length, int value) {
        int[] array = new int[length];
        Arrays.fill(array, value);
        return array;
    }

    /** A deterministic scramble of two ints, for patterns with no structure a blit could hide behind. */
    static int mix(int a, int b) {
        int h = a * 0x9E3779B1 + b * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }

    static int[] background(int w, int h, int kind) {
        int[] bg = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int value;
                switch (kind) {
                    case BG_ZERO -> value = 0;
                    case BG_OPAQUE -> value = 0xFF000000 | (((x * 255) / Math.max(1, w - 1)) << 16)
                            | (((y * 255) / Math.max(1, h - 1)) << 8) | ((x * 7 + y * 13) & 0xFF);
                    case BG_TRANSLUCENT -> {
                        int a = (x * 37 + y * 61 + 20) & 0xFF;
                        int r = (a * ((x * 255) / Math.max(1, w - 1))) / 255;
                        int g = (a * ((y * 255) / Math.max(1, h - 1))) / 255;
                        int b = (a * ((x * 29 + y * 17) & 0xFF)) / 255;
                        value = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    case BG_GARBAGE -> value = mix(x + 1000, y + 2000);
                    case BG_WHITE -> value = 0xFFFFFFFF;
                    default -> throw new AssertionError(kind);
                }
                bg[y * w + x] = value;
            }
        }
        return bg;
    }

    /** A texture as drawImage takes it: content {@code width x height} at {@code data[offset]}, row pitch stride. */
    record Texture(String name, int[] data, int width, int height, int offset, int stride, boolean hasAlpha) {
    }

    static int[] opaqueTexels(int w, int h) {
        int[] t = new int[w * h];
        for (int v = 0; v < h; v++) {
            for (int u = 0; u < w; u++) {
                t[v * w + u] = 0xFF000000 | (((u * 37 + 11) & 0xFF) << 16) | (((v * 53 + 29) & 0xFF) << 8)
                        | ((u * v * 7 + u + 3 * v + 64) & 0xFF);
            }
        }
        return t;
    }

    /** Premultiplied: no colour channel above alpha. */
    static int[] translucentTexels(int w, int h) {
        int[] t = new int[w * h];
        for (int v = 0; v < h; v++) {
            for (int u = 0; u < w; u++) {
                int a = 0x30 + ((u * 29 + v * 71) % 0xC0);
                int r = (a * ((u * 37 + 40) & 0xFF)) / 255;
                int g = (a * ((v * 53 + 90) & 0xFF)) / 255;
                int b = (a * (((u + v) * 41) & 0xFF)) / 255;
                t[v * w + u] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return t;
    }

    static int[] garbageTexels(int w, int h) {
        int[] t = new int[w * h];
        for (int v = 0; v < h; v++) {
            for (int u = 0; u < w; u++) {
                t[v * w + u] = mix(u + 77, v + 99);
            }
        }
        return t;
    }

    /** {@code content} copied into a larger array at {@code offset} with row pitch {@code stride}. */
    static int[] embed(int[] content, int w, int h, int offset, int stride) {
        int[] data = filled(offset + stride * h + 3, 0xDEADBEEF);
        for (int v = 0; v < h; v++) {
            System.arraycopy(content, v * w, data, offset + v * stride, w);
        }
        return data;
    }

    static final Texture OPAQUE_7X5 = new Texture("opaque7x5", opaqueTexels(7, 5), 7, 5, 0, 7, false);
    static final Texture TRANSLUCENT_7X5 = new Texture("translucent7x5", translucentTexels(7, 5), 7, 5, 0, 7, true);

    static Transform6 t6(double m00, double m01, double m10, double m11, double m02, double m12) {
        return new Transform6(fx(m00), fx(m01), fx(m10), fx(m11), fx(m02), fx(m12));
    }

    /** Rotation by {@code degrees} with scales {@code sx}, {@code sy}, then a translation. */
    static Transform6 rot(double degrees, double sx, double sy, double tx, double ty) {
        double r = StrictMath.toRadians(degrees);
        double c = StrictMath.cos(r);
        double s = StrictMath.sin(r);
        return t6(sx * c, -sy * s, sx * s, sy * c, tx, ty);
    }

    record NamedTransform(String name, Transform6 t) {
    }

    static NamedTransform nt(String name, Transform6 t) {
        return new NamedTransform(name, t);
    }

    /** The axis-aligned bounding box of {@code t} applied to {@code [u0,u1] x [v0,v1]}, as S15.16 x, y, w, h. */
    static int[] bbox(Transform6 t, double u0, double v0, double u1, double v1) {
        double[] us = {u0, u1, u0, u1};
        double[] vs = {v0, v0, v1, v1};
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            double x = (t.m00 * us[k] + t.m01 * vs[k]) / 65536.0 + t.m02 / 65536.0;
            double y = (t.m10 * us[k] + t.m11 * vs[k]) / 65536.0 + t.m12 / 65536.0;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new int[] {fx(minX), fx(minY), fx(maxX - minX), fx(maxY - minY)};
    }

    /** {@code w x h} coverage bytes counting 0, 1, 2, ... 255, 0, ... row by row. */
    static byte[] coverageMask(int w, int h) {
        byte[] mask = new byte[w * h];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = (byte) i;
        }
        return mask;
    }

    /** LCD mask whose pixel k has the subpixel bytes k, k + 85 and k + 170 (mod 256): every value per channel. */
    static byte[] lcdCoverageMask(int pixelsWide, int h) {
        byte[] mask = new byte[3 * pixelsWide * h];
        for (int p = 0; p < pixelsWide * h; p++) {
            mask[3 * p] = (byte) p;
            mask[3 * p + 1] = (byte) (p + 85);
            mask[3 * p + 2] = (byte) (p + 170);
        }
        return mask;
    }

    static byte[] glyphMask(int w, int h) {
        byte[] mask = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mask[y * w + x] = (byte) ((x * 255) / Math.max(1, w - 1) ^ (y * 29));
            }
        }
        return mask;
    }

    /** Marlin's {@code setMaxAlpha} map. */
    static byte[] marlinAlphaMap(int maxAlpha) {
        byte[] map = new byte[maxAlpha + 1];
        for (int i = 0; i <= maxAlpha; i++) {
            map[i] = (byte) ((i * 255 + maxAlpha / 2) / maxAlpha);
        }
        return map;
    }

    static byte[] identityAlphaMap() {
        byte[] map = new byte[256];
        for (int i = 0; i < 256; i++) {
            map[i] = (byte) i;
        }
        return map;
    }

    static final int[] FRACTIONS_2 = {0, 0x10000};
    static final int[] COLORS_2 = {0xFFFF0000, 0xFF0000FF};
    static final int[] FRACTIONS_3 = {0, 0x8000, 0x10000};
    static final int[] COLORS_3 = {0xFFFF0000, 0x8000FF00, 0xFF0000FF};
    static final int[] FRACTIONS_5 = {0x2000, 0x4000, 0x9000, 0xA000, 0xE000};
    static final int[] COLORS_5 = {0xFF00FF00, 0x80402000, 0x40004040, 0xFFFFFFFF, 0x00000000};
    static final int[] FRACTIONS_1 = {0x8000};
    static final int[] COLORS_1 = {0xC0603020};
    static final int[] FRACTIONS_DUP = {0, 0x4000, 0x4000, 0x10000};
    static final int[] COLORS_DUP = {0xFF102030, 0xFF405060, 0xFFA0B0C0, 0xFFF0E0D0};
    static final int[] FRACTIONS_CLEAR = {0, 0x10000};
    static final int[] COLORS_CLEAR = {0x00000000, 0xFFFFFFFF};

    static final int SRC_OVER = RendererBase.COMPOSITE_SRC_OVER;
    static final int SRC = RendererBase.COMPOSITE_SRC;
    static final int CLEAR = RendererBase.COMPOSITE_CLEAR;
    static final int CLAMP = RendererBase.WRAP_CLAMP_TO_EDGE;
    static final int REPEAT = RendererBase.WRAP_REPEAT;
    static final int ZERO = RendererBase.WRAP_CLAMP_TO_ZERO;
    static final int KEEP = RendererBase.IMAGE_FRAC_EDGE_KEEP;
    static final int PAD = RendererBase.IMAGE_FRAC_EDGE_PAD;
    static final int TRIM = RendererBase.IMAGE_FRAC_EDGE_TRIM;
    static final int NORMAL = RendererBase.IMAGE_MODE_NORMAL;
    static final int MULTIPLY = RendererBase.IMAGE_MODE_MULTIPLY;
    static final int ARGB_PRE = RendererBase.TYPE_INT_ARGB_PRE;

    static String ruleName(int rule) {
        return switch (rule) {
            case SRC_OVER -> "SRC_OVER";
            case SRC -> "SRC";
            case CLEAR -> "CLEAR";
            default -> "rule" + rule;
        };
    }

    static String wrapName(int wrap) {
        return switch (wrap) {
            case CLAMP -> "clamp";
            case REPEAT -> "repeat";
            case ZERO -> "zero";
            default -> "wrap" + wrap;
        };
    }

    static String edgeName(int edge) {
        return switch (edge) {
            case KEEP -> "K";
            case PAD -> "P";
            case TRIM -> "T";
            default -> "E" + edge;
        };
    }

    /** An S15.16 rectangle with a name. */
    record Box(String name, int x, int y, int w, int h) {
    }

    /* ---------------------------------------------------------------------------------------------
     * The script
     * ------------------------------------------------------------------------------------------- */

    /** The deterministic catalogue followed by the seeded random sequences. */
    static final class Script {
        private final Recorder rec = new Recorder();

        List<StepRecord> run() {
            surfaces();
            getAndSetRGB();
            clipAndClear();
            argumentChecks();
            fillRects();
            us062Slivers();
            linearGradients();
            radialGradients();
            texturePaints();
            textureFailures();
            drawImages();
            drawImageSubRectangles();
            drawImageEdges();
            drawImageMultiply();
            drawImageArgumentsAndBoxes();
            us062Twins();
            wideRepeat();
            farCoordinates();
            alphaMasks();
            lcdMasks();
            lcdGammaTables();
            alphaRows();
            new RandomSequences(rec).run();
            // the cases below come after the random sequences so that adding them moved no earlier step
            rgbArgumentOverflow();
            opaquePaintRows();
            zeroFractionTextures();
            zeroEdgeSubRectangles();
            multiplyColourTests();
            colourChanges();
            maskGuardsOnY();
            lcdZeroCoverage();
            zeroCoverageRunsUnderPaints();
            oneTranslucentTexel();
            deltaRangesToTheLastInt();
            alphaZeroTexelsWithColour();
            transparentSrcOverAlphaZero();
            return rec.steps;
        }

        List<String> anomalies() {
            return rec.anomalies;
        }

        private void surfaces() {
            rec.beginDetached("surface-construct");
            int[][] specs = {
                {ARGB_PRE, 3, 2, 6}, {0, 3, 2, 6}, {2, 3, 2, 6}, {-1, 3, 2, 6}, {3, 3, 2, 6},
                {ARGB_PRE, -1, 2, 6}, {ARGB_PRE, 3, -1, 6}, {ARGB_PRE, 65536, 65536, 1}, {ARGB_PRE, 46341, 46341, 1},
                {ARGB_PRE, 0, 0, 0}, {ARGB_PRE, 0, 5, 5}, {ARGB_PRE, 5, 0, 0}, {ARGB_PRE, 3, 2, 5},
                {ARGB_PRE, 3, 2, 9}, {ARGB_PRE, 32768, 2, 1}
            };
            for (int[] s : specs) {
                rec.step(f("new JavaSurface(int[%d], type %d, %dx%d)", s[3], s[0], s[1], s[2]), false, BG_NONE,
                        aux -> new JavaSurface(new int[s[3]], s[0], s[1], s[2]));
            }

            rec.begin("surface-5x0", 5, 0);
            PiscesRenderer pr = rec.pr;
            JavaSurface surface = rec.surface;
            rec.step("setColor + fillRect", false, BG_NONE, aux -> {
                pr.setColor(10, 20, 30, 255);
                pr.fillRect(0, 0, fx(5), fx(3));
            });
            rec.step("clearRect", false, BG_NONE, aux -> pr.clearRect(0, 0, 5, 5));
            rec.step("fillAlphaMask", false, BG_NONE, aux -> pr.fillAlphaMask(coverageMask(4, 4), 0, 0, 4, 4, 0, 4));
            rec.step("fillLCDAlphaMask", true, BG_NONE, aux -> {
                pr.setLCDGammaCorrection(1f);
                pr.fillLCDAlphaMask(lcdCoverageMask(4, 2), 0, 0, 12, 2, 0, 12);
            });
            rec.step("emitAndClearAlphaRow y 0", false, BG_NONE, aux -> {
                int[] deltas = {0, 64, 0, 0, -64, 0, 0};
                aux.ints = deltas;
                pr.emitAndClearAlphaRow(marlinAlphaMap(64), deltas, 0, 0, 3, 0, 0);
            });
            rec.step("getRGB", false, BG_NONE, aux -> {
                int[] out = filled(5, 0x5A5A5A5A);
                aux.ints = out;
                surface.getRGB(out, 0, 5, 0, 0, 5, 1);
            });
            rec.step("setRGB", false, BG_NONE, aux -> surface.setRGB(new int[5], 0, 5, 0, 0, 5, 1));
            rec.step("setTexture + fillRect", true, BG_NONE, aux -> {
                pr.setTexture(ARGB_PRE, OPAQUE_7X5.data(), 7, 5, 7, new Transform6(), CLAMP, true, false);
                pr.fillRect(0, 0, fx(5), fx(1));
            });
            rec.step("drawImage", true, BG_NONE, aux -> pr.drawImage(ARGB_PRE, NORMAL, OPAQUE_7X5.data(), 7, 5, 0, 7,
                    new Transform6(), CLAMP, false, 0, 0, fx(5), fx(1), KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4, false));

            rec.begin("surface-oversized-array", 4, 3, 4 * 3 + 5);
            PiscesRenderer pr2 = rec.pr;
            int[] tail = rec.pixels;
            rec.step("prefill tail + fillRect + clearRect", false, BG_NONE, aux -> {
                Arrays.fill(tail, 0x11223344);
                pr2.setColor(200, 100, 50, 255);
                pr2.fillRect(fx(-1), fx(-1), fx(10), fx(10));
                pr2.clearRect(1, 1, 2, 1);
            });
        }

        private void getAndSetRGB() {
            int[][] sizes = {{1, 1}, {1, 7}, {7, 1}, {13, 9}};
            for (int[] size : sizes) {
                int w = size[0];
                int h = size[1];
                rec.begin("rgb-" + w + "x" + h, w, h);
                JavaSurface surface = rec.surface;
                int[] garbage = background(w, h, BG_GARBAGE);
                rec.step("setRGB whole surface, non-premultiplied", false, BG_NONE,
                        aux -> surface.setRGB(garbage, 0, w, 0, 0, w, h));
                rec.step("getRGB whole surface", false, BG_NONE, aux -> {
                    int[] out = filled(w * h, 0x5A5A5A5A);
                    aux.ints = out;
                    surface.getRGB(out, 0, w, 0, 0, w, h);
                });
                rec.step("getRGB last pixel at offset 2", false, BG_NONE, aux -> {
                    int[] out = filled(3, 0x5A5A5A5A);
                    aux.ints = out;
                    surface.getRGB(out, 2, 1, w - 1, h - 1, 1, 1);
                });
                rec.step("setRGB first pixel", false, BG_NONE,
                        aux -> surface.setRGB(new int[] {0x12345678}, 0, 1, 0, 0, 1, 1));
                rec.step("getRGB 0x0 block", false, BG_NONE, aux -> {
                    int[] out = filled(2, 0x5A5A5A5A);
                    aux.ints = out;
                    surface.getRGB(out, 0, 0, 0, 0, 0, 0);
                });
                rec.step("getRGB whole surface, scan length w + 3", false, BG_NONE, aux -> {
                    int[] out = filled((w + 3) * h + 1, 0x5A5A5A5A);
                    aux.ints = out;
                    surface.getRGB(out, 1, w + 3, 0, 0, w, h);
                });
            }
            JavaSurface surface = rec.surface;
            rec.step("setRGB 4x4 block, offset 3, scan 6", false, BG_NONE, aux -> {
                int[] block = new int[3 + 6 * 4];
                for (int i = 0; i < block.length; i++) {
                    block[i] = 0xFF000000 | (i * 0x0A0B0C);
                }
                surface.setRGB(block, 3, 6, 4, 2, 4, 4);
            });
            rec.step("getRGB 4x4 block, offset 5, scan 7", false, BG_NONE, aux -> {
                int[] out = filled(5 + 7 * 4, 0x5A5A5A5A);
                aux.ints = out;
                surface.getRGB(out, 5, 7, 4, 2, 4, 4);
            });
            rec.step("setRGB right column", false, BG_NONE,
                    aux -> surface.setRGB(filled(9, 0x80402010), 0, 1, 12, 0, 1, 9));
            rec.step("setRGB bottom row", false, BG_NONE,
                    aux -> surface.setRGB(filled(13, 0x01020304), 0, 13, 0, 8, 13, 1));
            int[][] bad = {
                // offset, scan, x, y, w, h, array length
                {0, 13, -1, 0, 2, 2, 200}, {0, 13, 13, 0, 1, 1, 200}, {0, 13, 0, -1, 2, 2, 200},
                {0, 13, 0, 9, 1, 1, 200}, {0, 13, 0, 0, -1, 2, 200}, {0, 13, 0, 0, 2, -1, 200},
                {0, 13, 10, 0, 4, 1, 200}, {0, 13, 0, 7, 2, 3, 200}, {-1, 13, 0, 0, 2, 2, 200},
                {0, -1, 0, 0, 2, 2, 200}, {0, 3, 0, 0, 4, 2, 200}, {0, 1 << 27, 0, 0, 2, 9, 200},
                {2, 8, 1, 1, 4, 3, 2 + 8 * 2 + 4 - 1}, {2, 8, 1, 1, 4, 3, 2 + 8 * 2 + 4},
                {2, 4, 1, 1, 4, 3, 2 + 4 * 2 + 4}
            };
            for (int[] b : bad) {
                String args = f("offset %d, scan %d, x %d, y %d, %dx%d, int[%d]", b[0], b[1], b[2], b[3], b[4], b[5],
                        b[6]);
                rec.step("getRGB " + args, false, BG_NONE, aux -> {
                    int[] out = filled(b[6], 0x5A5A5A5A);
                    aux.ints = out;
                    surface.getRGB(out, b[0], b[1], b[2], b[3], b[4], b[5]);
                });
                rec.step("setRGB " + args, false, BG_NONE,
                        aux -> surface.setRGB(filled(b[6], 0x77665544), b[0], b[1], b[2], b[3], b[4], b[5]));
            }
        }

        private void clipAndClear() {
            rec.begin("clip", 16, 12);
            PiscesRenderer pr = rec.pr;
            int[][] clips = {
                {3, 2, 8, 6}, {-4, 3, 10, 5}, {2, -3, 6, 7}, {10, 4, 20, 5}, {4, 8, 5, 20}, {20, 2, 4, 4},
                {-10, -10, 5, 5}, {5, 5, 0, 4}, {5, 5, 4, 0}, {5, 5, -3, 4}, {Integer.MAX_VALUE - 2, 0, 10, 4},
                {-2, -2, Integer.MAX_VALUE, Integer.MAX_VALUE}, {0, 0, 16, 12}, {15, 11, 1, 1}
            };
            for (int[] c : clips) {
                rec.step(f("setClip(%d, %d, %d, %d) + fillRect over the surface", c[0], c[1], c[2], c[3]), false,
                        BG_TRANSLUCENT, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setColor(30, 200, 90, 255);
                            pr.setClip(c[0], c[1], c[2], c[3]);
                            pr.fillRect(fx(-1), fx(-1), fx(18), fx(14));
                        });
            }
            rec.step("setClip then resetClip + fillRect", false, BG_TRANSLUCENT, aux -> {
                pr.setClip(3, 3, 2, 2);
                pr.resetClip();
                pr.fillRect(fx(1), fx(1), fx(14), fx(10));
            });

            rec.begin("clear", 16, 12);
            PiscesRenderer cpr = rec.pr;
            int[][] rects = {
                {0, 0, 16, 12}, {3, 2, 7, 5}, {0, 4, 16, 3}, {0, 0, 1, 1}, {15, 11, 1, 1}, {-5, -5, 8, 8},
                {20, 3, 4, 4}, {5, 5, -2, 3}, {Integer.MAX_VALUE - 3, 0, 10, 2}, {2, 3, 0, 4}
            };
            rec.step("fresh renderer clearRect whole surface", false, BG_GARBAGE, aux -> cpr.clearRect(0, 0, 16, 12));
            int[][] colors = {
                {SRC_OVER, 255, 0, 0, 128}, {SRC, 255, 0, 0, 128}, {CLEAR, 255, 0, 0, 128}, {SRC_OVER, 0, 0, 0, 0},
                {SRC_OVER, 1, 2, 3, 0}, {SRC, 0, 0, 0, 255}
            };
            for (int[] c : colors) {
                for (int[] r : rects) {
                    rec.step(f("%s color(%d,%d,%d,%d) clearRect(%d, %d, %d, %d)", ruleName(c[0]), c[1], c[2], c[3],
                            c[4], r[0], r[1], r[2], r[3]), false, BG_GARBAGE, aux -> {
                                cpr.setCompositeRule(c[0]);
                                cpr.setColor(c[1], c[2], c[3], c[4]);
                                cpr.clearRect(r[0], r[1], r[2], r[3]);
                            });
                }
            }
            rec.step("SRC_OVER color(255,0,0,128) clip(2,2,8,6) clearRect whole surface", false, BG_GARBAGE,
                    aux -> {
                        cpr.setCompositeRule(SRC_OVER);
                        cpr.setColor(255, 0, 0, 128);
                        cpr.setClip(2, 2, 8, 6);
                        cpr.clearRect(0, 0, 16, 12);
                    });
            rec.step("color(10,20,30,40) then linear gradient paint, clearRect", true, BG_GARBAGE, aux -> {
                cpr.setCompositeRule(SRC_OVER);
                cpr.setColor(10, 20, 30, 40);
                cpr.setLinearGradient(0, 0, fx(16), 0, FRACTIONS_2, COLORS_2, GradientColorMap.CYCLE_NONE, null);
                cpr.clearRect(2, 2, 6, 4);
            });
            rec.step("CLEAR then back to SRC_OVER, clearRect", false, BG_GARBAGE, aux -> {
                cpr.setColor(90, 80, 70, 60);
                cpr.setCompositeRule(CLEAR);
                cpr.clearRect(0, 0, 4, 4);
                cpr.setCompositeRule(SRC_OVER);
                cpr.clearRect(4, 4, 4, 4);
            });
        }

        private void argumentChecks() {
            rec.begin("arguments", 6, 4);
            PiscesRenderer pr = rec.pr;
            int[][] colors = {{-1, 0, 0, 0}, {0, 256, 0, 0}, {0, 0, -1, 0}, {0, 0, 0, 256}, {256, 256, 256, 256}};
            for (int[] c : colors) {
                rec.step(f("setColor(%d, %d, %d, %d) after color(9,8,7,255), fillRect", c[0], c[1], c[2], c[3]),
                        false, BG_OPAQUE, aux -> {
                            pr.setColor(9, 8, 7, 255);
                            try {
                                pr.setColor(c[0], c[1], c[2], c[3]);
                            } finally {
                                pr.fillRect(0, 0, fx(3), fx(2));
                            }
                        });
            }
            rec.step("setColor(0, 0, 300) three-argument", false, BG_OPAQUE, aux -> pr.setColor(0, 0, 300));
            rec.step("setColor(40, 50, 60) three-argument + fillRect", false, BG_OPAQUE, aux -> {
                pr.setColor(40, 50, 60);
                pr.fillRect(fx(1), fx(1), fx(3), fx(2));
            });
            for (int rule : new int[] {-1, 3, 7}) {
                rec.step("setCompositeRule(" + rule + ")", false, BG_OPAQUE, aux -> pr.setCompositeRule(rule));
            }
            for (float gamma : new float[] {0f, -0f, -1f, Float.NEGATIVE_INFINITY}) {
                rec.step("setLCDGammaCorrection(" + gamma + ")", false, BG_OPAQUE,
                        aux -> pr.setLCDGammaCorrection(gamma));
            }
        }

        private void fillRects() {
            rec.begin("fillRect", 20, 14);
            PiscesRenderer pr = rec.pr;
            List<Box> boxes = List.of(
                    new Box("int", fx(2), fx(3), fx(10), fx(7)),
                    new Box("frac-all", fx(2.25), fx(3.5), fx(9.5), fx(6.75)),
                    new Box("frac-left", fx(2.5), fx(3), fx(8.5), fx(7)),
                    new Box("frac-right", fx(2), fx(3), fx(8.25), fx(7)),
                    new Box("frac-top", fx(2), fx(3.75), fx(10), fx(6.25)),
                    new Box("frac-bottom", fx(2), fx(3), fx(10), fx(6.5)),
                    new Box("column-in-pixel", fx(4.25), fx(2), fx(0.5), fx(9)),
                    new Box("row-in-pixel", fx(2), fx(5.25), fx(12), fx(0.5)),
                    new Box("dot-in-pixel", fx(4.25), fx(5.25), fx(0.5), fx(0.5)),
                    new Box("column-across", fx(4.75), fx(2), fx(0.5), fx(9)),
                    new Box("row-across", fx(2), fx(5.75), fx(12), fx(0.5)),
                    new Box("one-pixel", fx(4), fx(5), fx(1), fx(1)),
                    new Box("sliver-1/65536-wide", fx(5), fx(2), 1, fx(9)),
                    new Box("sliver-1/65536-high", fx(2), fx(6), fx(12), 1),
                    new Box("sliver-across-boundary", 0x5FFFF, fx(2), 2, fx(9)),
                    new Box("right-edge-1/65536-short", fx(3), fx(2), fx(11) - 1 - fx(3), fx(9)),
                    new Box("left-edge-1/65536-past", fx(3) + 1, fx(2), fx(8), fx(9)),
                    new Box("lfrac1-tfrac256", fx(2) + 0xFFFF, fx(3) + 0xFF00, fx(6), fx(4) + 0x80),
                    new Box("rfrac1-bfrac1", fx(2), fx(3), fx(6) + 1, fx(4) + 1),
                    new Box("rows-8", fx(1), fx(1), fx(5), fx(8)),
                    new Box("rows-9", fx(1), fx(1), fx(5), fx(9)),
                    new Box("rows-13-frac", fx(1), fx(0.5), fx(5), fx(12.75)),
                    new Box("rows-14-full-height", fx(1), 0, fx(5), fx(14)),
                    new Box("outside-left-top", fx(-2.5), fx(-1.25), fx(8), fx(6)),
                    new Box("outside-right-bottom", fx(15.5), fx(10.25), fx(10), fx(10)),
                    new Box("fully-outside", fx(21), fx(2), fx(3), fx(3)),
                    new Box("zero-width", fx(2.5), fx(2), 0, fx(4)),
                    new Box("negative-width", fx(8), fx(2), fx(-3), fx(4)),
                    new Box("whole-surface", 0, 0, fx(20), fx(14)),
                    new Box("x-near-max", Integer.MAX_VALUE - 5, fx(2), 100, fx(4)),
                    new Box("x-min-w-max", Integer.MIN_VALUE, fx(2), Integer.MAX_VALUE, fx(4)),
                    new Box("x-very-negative", -0x7FFF0000, fx(3), 0x7FFF0000 + fx(3.5), fx(4)));
            int[][] paints = {
                {SRC_OVER, 200, 30, 60, 255}, {SRC_OVER, 200, 30, 60, 128}, {SRC_OVER, 200, 30, 60, 0},
                {SRC, 20, 130, 250, 255}, {SRC, 20, 130, 250, 128}, {SRC, 20, 130, 250, 0}, {CLEAR, 99, 99, 99, 128}
            };
            for (int[] p : paints) {
                for (Box b : boxes) {
                    rec.step(f("%s color(%d,%d,%d,%d) %s", ruleName(p[0]), p[1], p[2], p[3], p[4], b.name()), false,
                            BG_TRANSLUCENT, aux -> {
                                pr.setCompositeRule(p[0]);
                                pr.setColor(p[1], p[2], p[3], p[4]);
                                pr.fillRect(b.x(), b.y(), b.w(), b.h());
                            });
                }
                rec.step(f("%s color(%d,%d,%d,%d) frac-all on non-premultiplied pixels", ruleName(p[0]), p[1], p[2],
                        p[3], p[4]), false, BG_GARBAGE, aux -> {
                            pr.setCompositeRule(p[0]);
                            pr.setColor(p[1], p[2], p[3], p[4]);
                            pr.fillRect(fx(2.25), fx(3.5), fx(9.5), fx(6.75));
                        });
                rec.step(f("%s color(%d,%d,%d,%d) clip(3,2,10,8) fractional box across it", ruleName(p[0]), p[1],
                        p[2], p[3], p[4]), false, BG_TRANSLUCENT, aux -> {
                            pr.setCompositeRule(p[0]);
                            pr.setColor(p[1], p[2], p[3], p[4]);
                            pr.setClip(3, 2, 10, 8);
                            pr.fillRect(fx(1.5), fx(1.25), fx(15.5), fx(10.5));
                        });
            }
            rec.step("a second renderer, colour never set (0,0,0,0): SRC_OVER fill, then SRC fill", false,
                    BG_TRANSLUCENT, aux -> {
                        PiscesRenderer fresh = new PiscesRenderer(rec.surface);
                        fresh.fillRect(fx(2), fx(2), fx(6), fx(6));
                        fresh.setCompositeRule(SRC);
                        fresh.fillRect(fx(10), fx(2), fx(6), fx(6));
                    });
        }

        private void us062Slivers() {
            rec.begin("us062-sliver", 72, 8);
            PiscesRenderer pr = rec.pr;
            int edge = 61 * 65536 - 1;
            List<Box> boxes = List.of(
                    new Box("x 60.3 to 3997695 (61*65536-1)", fx(60.3), fx(2), edge - fx(60.3), fx(4)),
                    new Box("x 60.3 to 3997695, fractional rows", fx(60.3), fx(1.5), edge - fx(60.3), fx(4.25)),
                    new Box("x 61*65536-1, width 3", edge, fx(2), fx(3), fx(4)),
                    new Box("x 61*65536-1, width 3, fractional rows", edge, fx(1.75), fx(3), fx(4.5)),
                    new Box("x 55 to 3997695", fx(55), fx(2), edge - fx(55), fx(4)));
            for (int[] p : new int[][] {{SRC_OVER, 255}, {SRC_OVER, 128}, {SRC, 255}, {SRC, 128}}) {
                for (Box b : boxes) {
                    rec.step(f("%s alpha %d %s", ruleName(p[0]), p[1], b.name()), false, BG_TRANSLUCENT, aux -> {
                        pr.setCompositeRule(p[0]);
                        pr.setColor(250, 120, 10, p[1]);
                        pr.fillRect(b.x(), b.y(), b.w(), b.h());
                    });
                }
            }
        }

        private static List<NamedTransform> gradientTransforms() {
            return List.of(
                    nt("null", null),
                    nt("scale(2,0.5)", t6(2, 0, 0, 0.5, 0, 0)),
                    nt("rot30", rot(30, 1, 1, 3, -2)),
                    nt("shear", t6(1, 0.4, 0, 1, 0, 0)),
                    nt("mirror-x", t6(-1, 0, 0, 1, 24, 0)),
                    nt("singular", t6(0, 0, 0, 1, 0, 0)),
                    nt("near-singular", new Transform6(fx(1), fx(1), fx(1), fx(1) + 1, 0, 0)),
                    nt("translate(10000,-8000)", t6(1, 0, 0, 1, 10000, -8000)),
                    nt("max-scale", new Transform6(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0)));
        }

        private void linearGradients() {
            rec.begin("linear", 24, 16);
            PiscesRenderer pr = rec.pr;
            int[][] geometries = {
                {fx(2), 0, fx(20), 0}, {0, fx(2), 0, fx(14)}, {fx(3), fx(2), fx(19), fx(13)},
                {fx(19), fx(13), fx(3), fx(2)}, {fx(5), fx(5), fx(6), fx(5)}, {fx(5), fx(5), fx(5) + 1, fx(5)},
                {fx(5), fx(5), fx(5), fx(5)}, {Integer.MIN_VALUE / 2, 0, Integer.MAX_VALUE / 2, 0},
                {fx(-100), fx(-50), fx(300), fx(200)}, {fx(1000), fx(5), fx(1000) + 3, fx(5)}
            };
            String[] names = {"horizontal", "vertical", "diagonal", "reversed", "1px", "1/65536px", "zero-length",
                "huge", "far", "3/65536px-far"};
            int[] cycles = {GradientColorMap.CYCLE_NONE, GradientColorMap.CYCLE_REPEAT, GradientColorMap.CYCLE_REFLECT};
            for (int g = 0; g < geometries.length; g++) {
                int[] p = geometries[g];
                for (int cycle : cycles) {
                    rec.step(f("%s cycle %d stops3", names[g], cycle), true, BG_TRANSLUCENT, aux -> {
                        pr.setCompositeRule(SRC_OVER);
                        pr.setLinearGradient(p[0], p[1], p[2], p[3], FRACTIONS_3, COLORS_3, cycle, null);
                        pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
                    });
                }
            }
            for (NamedTransform t : gradientTransforms()) {
                for (int cycle : cycles) {
                    rec.step(f("diagonal %s cycle %d", t.name(), cycle), true, BG_TRANSLUCENT, aux -> {
                        pr.setCompositeRule(SRC_OVER);
                        pr.setLinearGradient(fx(3), fx(2), fx(19), fx(13), FRACTIONS_3, COLORS_3, cycle, t.t());
                        pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
                    });
                }
            }
            int[][][] stops = {
                {FRACTIONS_2, COLORS_2}, {FRACTIONS_5, COLORS_5}, {FRACTIONS_1, COLORS_1}, {FRACTIONS_DUP, COLORS_DUP},
                {FRACTIONS_CLEAR, COLORS_CLEAR}
            };
            for (int s = 0; s < stops.length; s++) {
                int[][] stop = stops[s];
                rec.step("diagonal stops set " + s, true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setLinearGradient(fx(3), fx(2), fx(19), fx(13), stop[0], stop[1], GradientColorMap.CYCLE_NONE,
                            null);
                    pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
                });
            }
            for (int cycle : cycles) {
                rec.step("two-colour overload cycle " + cycle, true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setLinearGradient(fx(4), fx(3), 0xFF00FFFF, fx(14), fx(9), 0x40FF0080, cycle);
                    pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
                });
            }
            for (int rule : new int[] {SRC, CLEAR}) {
                rec.step("diagonal stops3 under " + ruleName(rule), true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setLinearGradient(fx(3), fx(2), fx(19), fx(13), FRACTIONS_3, COLORS_3,
                            GradientColorMap.CYCLE_REFLECT, null);
                    pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
                });
            }
        }

        private void radialGradients() {
            rec.begin("radial", 24, 16);
            PiscesRenderer pr = rec.pr;
            int[][] geometries = {
                {fx(12), fx(8), fx(12), fx(8), fx(7)}, {fx(12), fx(8), fx(14), fx(9), fx(7)},
                {fx(12), fx(8), fx(18.9), fx(8), fx(7)}, {fx(12), fx(8), fx(19), fx(8), fx(7)},
                {fx(12), fx(8), fx(26), fx(11), fx(7)}, {fx(12), fx(8), fx(12), fx(8), 0},
                {fx(12), fx(8), fx(13), fx(8), 0}, {fx(12), fx(8), fx(12), fx(8), 1},
                {fx(12), fx(8), fx(14), fx(9), fx(-7)}, {fx(12), fx(8), fx(12.5), fx(8), 1 << 30},
                {fx(-200), fx(300), fx(-200), fx(300), fx(7)}, {fx(12), fx(8), fx(12), fx(8.01), fx(0.02)}
            };
            String[] names = {"concentric", "focus-inside", "focus-0.986r", "focus-on-circle", "focus-outside",
                "r0", "r0-focus-offset", "r1/65536", "r-negative", "r-huge", "far-centre", "tiny-focus-offset"};
            int[] cycles = {GradientColorMap.CYCLE_NONE, GradientColorMap.CYCLE_REPEAT, GradientColorMap.CYCLE_REFLECT};
            for (int g = 0; g < geometries.length; g++) {
                int[] p = geometries[g];
                for (int cycle : cycles) {
                    rec.step(f("%s cycle %d stops3", names[g], cycle), true, BG_TRANSLUCENT, aux -> {
                        pr.setCompositeRule(SRC_OVER);
                        pr.setRadialGradient(p[0], p[1], p[2], p[3], p[4], FRACTIONS_3, COLORS_3, cycle, null);
                        pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
                    });
                }
            }
            for (NamedTransform t : gradientTransforms()) {
                rec.step("focus-inside " + t.name() + " reflect", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setRadialGradient(fx(12), fx(8), fx(14), fx(9), fx(7), FRACTIONS_3, COLORS_3,
                            GradientColorMap.CYCLE_REFLECT, t.t());
                    pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
                });
            }
            rec.step("focus-inside stops5 none", true, BG_TRANSLUCENT, aux -> {
                pr.setCompositeRule(SRC_OVER);
                pr.setRadialGradient(fx(12), fx(8), fx(14), fx(9), fx(7), FRACTIONS_5, COLORS_5,
                        GradientColorMap.CYCLE_NONE, rot(20, 1.3, 0.8, 1, 1));
                pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
            });
            for (int rule : new int[] {SRC, CLEAR}) {
                rec.step("focus-inside under " + ruleName(rule), true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setRadialGradient(fx(12), fx(8), fx(14), fx(9), fx(7), FRACTIONS_3, COLORS_3,
                            GradientColorMap.CYCLE_REPEAT, null);
                    pr.fillRect(fx(1.5), fx(1.25), fx(21), fx(13.5));
                });
            }
        }

        /** Texture-to-device transforms for the setTexture paints, every class renderer_setTexture tells apart. */
        private static List<NamedTransform> paintTransforms() {
            return List.of(
                    nt("identity", t6(1, 0, 0, 1, 0, 0)),
                    nt("translate(3,2)", t6(1, 0, 0, 1, 3, 2)),
                    nt("translate(2.5,1.25)", t6(1, 0, 0, 1, 2.5, 1.25)),
                    nt("translate(0.25,0.75)", t6(1, 0, 0, 1, 0.25, 0.75)),
                    nt("translate(-300.3,270.7)", t6(1, 0, 0, 1, -300.3, 270.7)),
                    nt("translate raw(16777217,-16777219)", new Transform6(fx(1), 0, 0, fx(1), 16777217, -16777219)),
                    nt("scale2+(2,1)", t6(2, 0, 0, 2, 2, 1)),
                    nt("scale0.5+(1,1)", t6(0.5, 0, 0, 0.5, 1, 1)),
                    nt("scale(1.5,0.75)+(1.25,2.5)", t6(1.5, 0, 0, 0.75, 1.25, 2.5)),
                    nt("mirror-x", t6(-2, 0, 0, 2, 16, 1)),
                    nt("mirror-y", t6(2, 0, 0, -2, 2, 12)),
                    nt("rot30x1.8", rot(30, 1.8, 1.8, 9, 1)),
                    nt("rot90", t6(0, -2, 2, 0, 14, 1)),
                    nt("rot-90", t6(0, 2, -2, 0, 2, 15)),
                    nt("rot180", t6(-2, 0, 0, -2, 16, 12)),
                    nt("mirror-diagonal", t6(0, 2, 2, 0, 2, 1)),
                    nt("shear-x", t6(2, 0.6, 0, 2, 1, 1)),
                    nt("shear-y", t6(2, 0, -0.5, 2, 2, 6)),
                    nt("near-singular", new Transform6(fx(2), fx(2) - 1, fx(2), fx(2), fx(3), fx(2))),
                    nt("singular", new Transform6(fx(1), fx(1), fx(1), fx(1), 0, 0)),
                    nt("max-scale", new Transform6(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0)),
                    nt("scale 1/65536", new Transform6(1, 0, 0, 1, 0, 0)));
        }

        private void texturePaints() {
            rec.begin("texture", 20, 16);
            PiscesRenderer pr = rec.pr;
            int[] wraps = {CLAMP, REPEAT, ZERO};
            for (Texture tex : List.of(OPAQUE_7X5, TRANSLUCENT_7X5)) {
                for (NamedTransform t : paintTransforms()) {
                    for (int wrap : wraps) {
                        for (boolean linear : new boolean[] {false, true}) {
                            rec.step(f("%s %s %s %s", tex.name(), t.name(), wrapName(wrap), linear ? "bilinear"
                                    : "nearest"), true, BG_TRANSLUCENT, aux -> paintTexture(pr, tex, t.t(), wrap,
                                    linear, tex.hasAlpha()));
                        }
                    }
                }
            }
            List<Texture> others = List.of(
                    new Texture("1x1", new int[] {0xC0806040}, 1, 1, 0, 1, true),
                    new Texture("1x5", translucentTexels(1, 5), 1, 5, 0, 1, true),
                    new Texture("5x1", opaqueTexels(5, 1), 5, 1, 0, 5, false),
                    new Texture("16x16", opaqueTexels(16, 16), 16, 16, 0, 16, false),
                    new Texture("garbage4x3", garbageTexels(4, 3), 4, 3, 0, 4, true),
                    new Texture("6x4-stride9", embed(translucentTexels(6, 4), 6, 4, 0, 9), 6, 4, 0, 9, true));
            List<NamedTransform> some = List.of(
                    nt("identity", t6(1, 0, 0, 1, 0, 0)),
                    nt("translate(2.5,1.25)", t6(1, 0, 0, 1, 2.5, 1.25)),
                    nt("scale2+(2,1)", t6(2, 0, 0, 2, 2, 1)),
                    nt("rot30x1.8", rot(30, 1.8, 1.8, 9, 1)),
                    nt("mirror-diagonal", t6(0, 2, 2, 0, 2, 1)),
                    nt("rot180", t6(-2, 0, 0, -2, 16, 12)));
            int[][] modes = {{CLAMP, 1}, {REPEAT, 1}, {ZERO, 1}, {REPEAT, 0}, {CLAMP, 0}};
            for (Texture tex : others) {
                for (NamedTransform t : some) {
                    for (int[] m : modes) {
                        rec.step(f("%s %s %s %s", tex.name(), t.name(), wrapName(m[0]), m[1] == 1 ? "bilinear"
                                : "nearest"), true, BG_TRANSLUCENT, aux -> paintTexture(pr, tex, t.t(), m[0],
                                m[1] == 1, tex.hasAlpha()));
                    }
                }
            }
            Texture garbage = others.get(4);
            for (Texture tex : List.of(OPAQUE_7X5, TRANSLUCENT_7X5, garbage)) {
                for (NamedTransform t : List.of(some.get(1), some.get(3))) {
                    for (int wrap : wraps) {
                        rec.step(f("%s hasAlpha %b %s %s bilinear", tex.name(), !tex.hasAlpha(), t.name(),
                                wrapName(wrap)), true, BG_TRANSLUCENT, aux -> paintTexture(pr, tex, t.t(), wrap, true,
                                !tex.hasAlpha()));
                    }
                }
            }
            for (int rule : new int[] {SRC, CLEAR}) {
                rec.step("translucent7x5 rot30 clamp bilinear under " + ruleName(rule), true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setTexture(ARGB_PRE, TRANSLUCENT_7X5.data(), 7, 5, 7, rot(30, 1.8, 1.8, 9, 1), CLAMP, true,
                            true);
                    pr.fillRect(fx(0.5), fx(0.25), fx(19), fx(15.5));
                });
            }
            for (boolean linear : new boolean[] {false, true}) {
                rec.step("translucent7x5 rot30 clamp " + (linear ? "bilinear" : "nearest") + " inside clip(3,2,11,9)",
                        true, BG_TRANSLUCENT, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setTexture(ARGB_PRE, TRANSLUCENT_7X5.data(), 7, 5, 7, rot(30, 1.8, 1.8, 9, 1), CLAMP,
                                    linear, true);
                            pr.setClip(3, 2, 11, 9);
                            pr.fillRect(fx(0.5), fx(0.25), fx(19), fx(15.5));
                        });
            }
        }

        private static void paintTexture(PiscesRenderer pr, Texture tex, Transform6 t, int wrap, boolean linear,
                boolean hasAlpha) {
            pr.setCompositeRule(SRC_OVER);
            pr.setTexture(ARGB_PRE, tex.data(), tex.width(), tex.height(), tex.stride(), t, wrap, linear, hasAlpha);
            pr.fillRect(fx(0.5), fx(0.25), fx(19), fx(15.5));
        }

        private void textureFailures() {
            rec.begin("texture-failure", 8, 6);
            PiscesRenderer pr = rec.pr;
            int[] data = opaqueTexels(4, 4);
            Object[][] cases = {
                {"width 0", 0, 4, 4, CLAMP}, {"height 0", 4, 0, 4, CLAMP}, {"width 0 stride 0", 0, 4, 0, CLAMP},
                {"wrap 3", 4, 4, 4, 3}, {"wrap -1", 4, 4, 4, -1}, {"width -1", -1, 4, 4, CLAMP},
                {"stride < width", 4, 4, 3, CLAMP}, {"data too short", 4, 5, 4, CLAMP}
            };
            for (Object[] c : cases) {
                int w = (Integer) c[1];
                int h = (Integer) c[2];
                int stride = (Integer) c[3];
                int wrap = (Integer) c[4];
                rec.step("color, then setTexture " + c[0] + ", then fillRect", false, BG_OPAQUE, aux -> {
                    pr.setColor(70, 140, 210, 255);
                    try {
                        pr.setTexture(ARGB_PRE, data, w, h, stride, new Transform6(), wrap, false, false);
                    } finally {
                        pr.fillRect(fx(1), fx(1), fx(4), fx(3));
                    }
                });
            }
            rec.step("texture, then setTexture width 0, then fillRect", true, BG_OPAQUE, aux -> {
                pr.setTexture(ARGB_PRE, data, 4, 4, 4, t6(1, 0, 0, 1, 1, 1), REPEAT, false, false);
                try {
                    pr.setTexture(ARGB_PRE, data, 0, 4, 4, new Transform6(), CLAMP, false, false);
                } finally {
                    pr.fillRect(0, 0, fx(8), fx(6));
                }
            });
        }

        /** Texture-to-device transforms for drawImage, each mapping the 7x5 texture into a 24x24 surface. */
        private static List<NamedTransform> imageTransforms() {
            return List.of(
                    nt("identity", t6(1, 0, 0, 1, 0, 0)),
                    nt("translate(3,4)", t6(1, 0, 0, 1, 3, 4)),
                    nt("translate(3.3,4.6)", t6(1, 0, 0, 1, 3.3, 4.6)),
                    nt("scale2.5+(2.3,1.6)", t6(2.5, 0, 0, 2.5, 2.3, 1.6)),
                    nt("scale0.6+(5.2,6.7)", t6(0.6, 0, 0, 0.6, 5.2, 6.7)),
                    nt("mirror-x", t6(-2.5, 0, 0, 2.5, 20.4, 3.3)),
                    nt("mirror-y", t6(2.5, 0, 0, -2.5, 2.6, 16.1)),
                    nt("rot180", t6(-2.5, 0, 0, -2.5, 20.3, 16.4)),
                    nt("rot90", t6(0, -2.5, 2.5, 0, 16.2, 2.7)),
                    nt("rot-90", t6(0, 2.5, -2.5, 0, 3.4, 20.1)),
                    nt("transpose", t6(0, 2.5, 2.5, 0, 2.3, 1.9)),
                    nt("anti-transpose", t6(0, -2.5, -2.5, 0, 16.6, 20.2)),
                    nt("rot30x2", rot(30, 2, 2, 8.1, 2.3)),
                    nt("rot45x2.2", rot(45, 2.2, 2.2, 11.7, 1.4)),
                    nt("rot135x1.9", rot(135, 1.9, 1.9, 18.2, 6.3)),
                    nt("rot-20(2.4,1.3)", rot(-20, 2.4, 1.3, 3.1, 7.7)),
                    nt("shear-x", t6(2, 1.2, 0, 2, 1.3, 2.2)),
                    nt("shear-y", t6(2, 0, -0.9, 2, 2.4, 9.5)),
                    nt("near-singular", new Transform6(fx(2), fx(2) - 1, fx(2), fx(2), fx(3), fx(4))),
                    nt("singular", new Transform6(fx(2), fx(2), fx(2), fx(2), fx(3), fx(4))));
        }

        private static void drawImage(PiscesRenderer pr, int mode, Texture tex, Transform6 t, int wrap,
                boolean linear, int[] box, int l, int r, int top, int bottom, int txMin, int tyMin, int txMax,
                int tyMax) {
            pr.drawImage(ARGB_PRE, mode, tex.data(), tex.width(), tex.height(), tex.offset(), tex.stride(), t, wrap,
                    linear, box[0], box[1], box[2], box[3], l, r, top, bottom, txMin, tyMin, txMax, tyMax,
                    tex.hasAlpha());
        }

        private void drawImages() {
            rec.begin("drawImage", 24, 24);
            PiscesRenderer pr = rec.pr;
            int[] wraps = {CLAMP, REPEAT, ZERO};
            for (Texture tex : List.of(OPAQUE_7X5, TRANSLUCENT_7X5)) {
                for (NamedTransform t : imageTransforms()) {
                    int[] box = bbox(t.t(), 0, 0, 7, 5);
                    for (int wrap : wraps) {
                        for (boolean linear : new boolean[] {false, true}) {
                            rec.step(f("%s %s %s %s", tex.name(), t.name(), wrapName(wrap), linear ? "bilinear"
                                    : "nearest"), true, BG_TRANSLUCENT, aux -> {
                                        pr.setCompositeRule(SRC_OVER);
                                        drawImage(pr, NORMAL, tex, t.t(), wrap, linear, box, KEEP, KEEP, KEEP, KEEP,
                                                0, 0, 6, 4);
                                    });
                        }
                    }
                }
            }
            for (int rule : new int[] {SRC, CLEAR}) {
                rec.step("translucent7x5 rot30x2 clamp bilinear under " + ruleName(rule), true, BG_TRANSLUCENT,
                        aux -> {
                            Transform6 t = rot(30, 2, 2, 8.1, 2.3);
                            pr.setCompositeRule(rule);
                            drawImage(pr, NORMAL, TRANSLUCENT_7X5, t, CLAMP, true, bbox(t, 0, 0, 7, 5), KEEP, KEEP,
                                    KEEP, KEEP, 0, 0, 6, 4);
                        });
            }
        }

        private void drawImageSubRectangles() {
            rec.begin("drawImage-sub", 24, 24);
            PiscesRenderer pr = rec.pr;
            int[][] subs = {
                {1, 1, 5, 3}, {0, 0, 3, 2}, {3, 2, 6, 4}, {6, 0, 6, 4}, {0, 4, 6, 4}, {0, 0, -1, 4}, {7, 0, 6, 4},
                {0, 0, 6, -1}, {0, 5, 6, 4}
            };
            String[] names = {"inner", "left-top", "right-bottom", "right-column", "bottom-row", "zero-width-left",
                "zero-width-right", "zero-height-top", "zero-height-bottom"};
            List<NamedTransform> all = imageTransforms();
            List<NamedTransform> some = List.of(all.get(3), all.get(8), all.get(10), all.get(12), all.get(16));
            int[][] modes = {{CLAMP, 1}, {REPEAT, 1}, {ZERO, 1}, {CLAMP, 0}, {REPEAT, 0}};
            for (int s = 0; s < subs.length; s++) {
                int[] sub = subs[s];
                for (NamedTransform t : some) {
                    int[] box = bbox(t.t(), sub[0], sub[1], sub[2] + 1, sub[3] + 1);
                    for (int[] m : modes) {
                        rec.step(f("%s %s %s %s", names[s], t.name(), wrapName(m[0]), m[1] == 1 ? "bilinear"
                                : "nearest"), true, BG_TRANSLUCENT, aux -> {
                                    pr.setCompositeRule(SRC_OVER);
                                    drawImage(pr, NORMAL, TRANSLUCENT_7X5, t.t(), m[0], m[1] == 1, box, KEEP, KEEP,
                                            KEEP, KEEP, sub[0], sub[1], sub[2], sub[3]);
                                });
                    }
                }
            }
            Texture embedded = new Texture("opaque7x5-offset5-stride11", embed(OPAQUE_7X5.data(), 7, 5, 5, 11), 7, 5,
                    5, 11, false);
            for (NamedTransform t : List.of(all.get(3), all.get(12))) {
                for (boolean linear : new boolean[] {false, true}) {
                    rec.step(embedded.name() + " " + t.name() + (linear ? " bilinear" : " nearest"), true,
                            BG_TRANSLUCENT, aux -> {
                                pr.setCompositeRule(SRC_OVER);
                                drawImage(pr, NORMAL, embedded, t.t(), REPEAT, linear, bbox(t.t(), 0, 0, 7, 5), KEEP,
                                        KEEP, KEEP, KEEP, 0, 0, 6, 4);
                            });
                }
            }
        }

        private void drawImageEdges() {
            rec.begin("drawImage-edges", 24, 24);
            PiscesRenderer pr = rec.pr;
            int[][] edges = {
                {KEEP, KEEP, KEEP, KEEP}, {PAD, PAD, PAD, PAD}, {TRIM, TRIM, TRIM, TRIM},
                {PAD, KEEP, KEEP, KEEP}, {KEEP, PAD, KEEP, KEEP}, {KEEP, KEEP, PAD, KEEP}, {KEEP, KEEP, KEEP, PAD},
                {TRIM, KEEP, KEEP, KEEP}, {KEEP, TRIM, KEEP, KEEP}, {KEEP, KEEP, TRIM, KEEP}, {KEEP, KEEP, KEEP, TRIM},
                {TRIM, PAD, KEEP, TRIM}, {3, -1, 5, 9}
            };
            List<NamedTransform> transforms = List.of(nt("scale2.5+(2.3,1.6)", t6(2.5, 0, 0, 2.5, 2.3, 1.6)),
                    nt("rot30x2", rot(30, 2, 2, 8.1, 2.3)));
            for (NamedTransform t : transforms) {
                int[] box = bbox(t.t(), 0, 0, 7, 5);
                for (int[] e : edges) {
                    rec.step(f("%s edges l%s r%s t%s b%s", t.name(), edgeName(e[0]), edgeName(e[1]), edgeName(e[2]),
                            edgeName(e[3])), true, BG_TRANSLUCENT, aux -> {
                                pr.setCompositeRule(SRC_OVER);
                                drawImage(pr, NORMAL, TRANSLUCENT_7X5, t.t(), CLAMP, true, box, e[0], e[1], e[2],
                                        e[3], 0, 0, 6, 4);
                            });
                }
                for (int[] e : new int[][] {{KEEP, KEEP, KEEP, KEEP}, {PAD, PAD, PAD, PAD}, {TRIM, TRIM, TRIM, TRIM}}) {
                    rec.step(f("%s edges %s inside clip(5,4,10,9)", t.name(), edgeName(e[0])), true, BG_TRANSLUCENT,
                            aux -> {
                                pr.setCompositeRule(SRC_OVER);
                                pr.setClip(5, 4, 10, 9);
                                drawImage(pr, NORMAL, TRANSLUCENT_7X5, t.t(), CLAMP, true, box, e[0], e[1], e[2],
                                        e[3], 0, 0, 6, 4);
                            });
                }
            }
            int[][] boxes = {
                {fx(5.5), fx(3.25), 0, fx(6)}, {fx(3), fx(5.75), fx(9), 0}, {fx(5.5), fx(3.25), 1, fx(6)},
                {fx(9), fx(9), fx(-4), fx(5)}, {0x7FFF0000, fx(2), fx(2), fx(5)},
                {Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE, Integer.MAX_VALUE},
                {fx(-3.5), fx(-2.25), fx(8), fx(9)}, {fx(20.5), fx(19.75), fx(8), fx(9)}
            };
            String[] names = {"zero-width at x 5.5", "zero-height at y 5.75", "width 1/65536 at x 5.5",
                "negative width", "x 0x7FFF0000 (x + w overflows)", "huge box", "box over the top-left corner",
                "box over the bottom-right corner"};
            Transform6 t = t6(2.5, 0, 0, 2.5, 2.3, 1.6);
            for (int b = 0; b < boxes.length; b++) {
                int[] box = boxes[b];
                for (int[] e : new int[][] {{KEEP, KEEP, KEEP, KEEP}, {TRIM, TRIM, TRIM, TRIM}}) {
                    rec.step(names[b] + " edges " + edgeName(e[0]), true, BG_TRANSLUCENT, aux -> {
                        pr.setCompositeRule(SRC_OVER);
                        drawImage(pr, NORMAL, OPAQUE_7X5, t, CLAMP, true, box, e[0], e[1], e[2], e[3], 0, 0, 6, 4);
                    });
                }
            }
        }

        private void drawImageMultiply() {
            Transform6 t = t6(2.5, 0, 0, 2.5, 2.3, 1.6);
            int[] box = bbox(t, 0, 0, 7, 5);
            rec.begin("multiply", 24, 16);
            PiscesRenderer pr = rec.pr;
            int[][] colors = {{255, 255, 255, 255}, {255, 255, 255, 128}, {200, 100, 50, 180}, {200, 100, 50, 255},
                {0, 0, 0, 0}};
            for (int[] c : colors) {
                for (Texture tex : List.of(OPAQUE_7X5, TRANSLUCENT_7X5)) {
                    rec.step(f("color(%d,%d,%d,%d) %s", c[0], c[1], c[2], c[3], tex.name()), true, BG_TRANSLUCENT,
                            aux -> {
                                pr.setCompositeRule(SRC_OVER);
                                pr.setColor(c[0], c[1], c[2], c[3]);
                                drawImage(pr, MULTIPLY, tex, t, CLAMP, true, box, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
                            });
                }
            }
            rec.step("linear gradient prev paint", true, BG_TRANSLUCENT, aux -> {
                pr.setCompositeRule(SRC_OVER);
                pr.setLinearGradient(fx(3), fx(2), fx(19), fx(13), FRACTIONS_3, COLORS_3,
                        GradientColorMap.CYCLE_REFLECT, null);
                drawImage(pr, MULTIPLY, TRANSLUCENT_7X5, t, REPEAT, true, box, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
            });
            rec.step("radial gradient prev paint", true, BG_TRANSLUCENT, aux -> {
                pr.setCompositeRule(SRC_OVER);
                pr.setRadialGradient(fx(12), fx(8), fx(14), fx(9), fx(7), FRACTIONS_3, COLORS_3,
                        GradientColorMap.CYCLE_REPEAT, null);
                drawImage(pr, MULTIPLY, OPAQUE_7X5, t, CLAMP, false, box, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
            });
            for (int mode : new int[] {0, 3, -5}) {
                rec.step("image mode " + mode + " after colour", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setColor(250, 200, 100, 200);
                    drawImage(pr, mode, TRANSLUCENT_7X5, t, CLAMP, true, box, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
                });
            }
            for (int rule : new int[] {SRC, CLEAR}) {
                rec.step("colour prev paint under " + ruleName(rule), true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setColor(250, 200, 100, 200);
                    drawImage(pr, MULTIPLY, TRANSLUCENT_7X5, t, CLAMP, true, box, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
                });
            }

            rec.begin("multiply-stale-paint", 24, 16);
            PiscesRenderer spr = rec.pr;
            rec.step("fresh renderer: setTexture, drawImage MULTIPLY (no paint buffer yet)", true, BG_TRANSLUCENT,
                    aux -> {
                        spr.setTexture(ARGB_PRE, OPAQUE_7X5.data(), 7, 5, 7, t6(1, 0, 0, 1, 1, 1), REPEAT, false,
                                false);
                        drawImage(spr, MULTIPLY, TRANSLUCENT_7X5, t, CLAMP, true, box, KEEP, KEEP, KEEP, KEEP, 0, 0,
                                6, 4);
                    });
            rec.step("setTexture + fill 20x14, then drawImage MULTIPLY over a smaller box", true, BG_TRANSLUCENT,
                    aux -> {
                        spr.setCompositeRule(SRC_OVER);
                        spr.setTexture(ARGB_PRE, TRANSLUCENT_7X5.data(), 7, 5, 7, rot(30, 1.8, 1.8, 9, 1), REPEAT,
                                true, true);
                        spr.fillRect(fx(1), fx(1), fx(20), fx(14));
                        drawImage(spr, MULTIPLY, OPAQUE_7X5, t6(1, 0, 0, 1, 4, 5), CLAMP, false,
                                new int[] {fx(4), fx(5), fx(7), fx(5)}, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
                    });
            rec.step("the same under SRC", true, BG_TRANSLUCENT, aux -> {
                spr.setCompositeRule(SRC);
                spr.setTexture(ARGB_PRE, TRANSLUCENT_7X5.data(), 7, 5, 7, rot(30, 1.8, 1.8, 9, 1), REPEAT, true,
                        true);
                spr.fillRect(fx(1), fx(1), fx(20), fx(14));
                drawImage(spr, MULTIPLY, OPAQUE_7X5, t6(1, 0, 0, 1, 4, 5), CLAMP, false,
                        new int[] {fx(4.5), fx(5.5), fx(7), fx(5)}, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
            });
            rec.step("drawImage NORMAL, then drawImage MULTIPLY (previous paint the drawImage texture)", true,
                    BG_TRANSLUCENT, aux -> {
                        spr.setCompositeRule(SRC_OVER);
                        drawImage(spr, NORMAL, TRANSLUCENT_7X5, t, REPEAT, true, box, KEEP, KEEP, KEEP, KEEP, 0, 0,
                                6, 4);
                        drawImage(spr, MULTIPLY, OPAQUE_7X5, t6(1, 0, 0, 1, 10, 3), CLAMP, true,
                                new int[] {fx(10), fx(3), fx(7), fx(5)}, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
                    });
            rec.step("larger box than any earlier paint: the buffer is reallocated", true, BG_TRANSLUCENT, aux -> {
                spr.setCompositeRule(SRC);
                drawImage(spr, MULTIPLY, OPAQUE_7X5, t6(3.5, 0, 0, 3.2, 0, 0), CLAMP, true,
                        new int[] {0, 0, fx(24), fx(16)}, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
            });
        }

        private void drawImageArgumentsAndBoxes() {
            rec.begin("drawImage-arguments", 12, 10);
            PiscesRenderer pr = rec.pr;
            int[] data = OPAQUE_7X5.data();
            int[][] cases = {
                // width, height, offset, stride, wrap, txMin, tyMin, txMax, tyMax, data length
                {0, 5, 0, 7, CLAMP, 0, 0, -1, 4, 35}, {7, 0, 0, 7, CLAMP, 0, 0, 6, -1, 35},
                {7, 5, 0, 7, CLAMP, -1, 0, 6, 4, 35}, {7, 5, 0, 7, CLAMP, 0, -1, 6, 4, 35},
                {7, 5, 0, 7, CLAMP, 0, 0, 7, 4, 35}, {7, 5, 0, 7, CLAMP, 0, 0, 6, 5, 35},
                {7, 5, 0, 7, 3, 0, 0, 6, 4, 35}, {7, 5, 0, 7, -1, 0, 0, 6, 4, 35}, {7, 5, -1, 7, CLAMP, 0, 0, 6, 4, 35},
                {7, 5, 0, 6, CLAMP, 0, 0, 6, 4, 35}, {7, 5, 1, 7, CLAMP, 0, 0, 6, 4, 35},
                {-1, 5, 0, 7, CLAMP, 0, 0, 6, 4, 35}, {7, 5, 0, 7, CLAMP, 5, 3, 4, 2, 35}
            };
            for (int[] c : cases) {
                int[] array = Arrays.copyOf(data, c[9]);
                rec.step(f("colour fill, then drawImage %dx%d offset %d stride %d %s sub(%d,%d,%d,%d), then fill",
                        c[0], c[1], c[2], c[3], wrapName(c[4]), c[5], c[6], c[7], c[8]), true, BG_OPAQUE, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setColor(10, 250, 120, 255);
                            pr.fillRect(fx(9), fx(7), fx(3), fx(3));
                            try {
                                pr.drawImage(ARGB_PRE, NORMAL, array, c[0], c[1], c[2], c[3], t6(1, 0, 0, 1, 1, 1),
                                        c[4], true, fx(1), fx(1), fx(7), fx(5), KEEP, KEEP, KEEP, KEEP, c[5], c[6],
                                        c[7], c[8], false);
                            } finally {
                                pr.setColor(250, 10, 120, 255);
                                pr.fillRect(0, fx(8), fx(3), fx(2));
                            }
                        });
            }
        }

        /**
         * The geometry of US-062: a quarter turn of the 7x5 texture at scale 0.7 and its exact axis-aligned twin with
         * the transposed texture, at X = 60.3, where the box edge truncates to 3997695 = 61 * 65536 - 1.
         */
        private void us062Twins() {
            rec.begin("us062-twins", 72, 60);
            PiscesRenderer pr = rec.pr;
            int[] texels = translucentTexels(7, 5);
            int[] transposed = new int[35];
            for (int v = 0; v < 5; v++) {
                for (int u = 0; u < 7; u++) {
                    transposed[u * 5 + v] = texels[v * 7 + u];
                }
            }
            Texture tex = new Texture("7x5", texels, 7, 5, 0, 7, true);
            Texture tr = new Texture("5x7", transposed, 5, 7, 0, 5, true);
            double s = 0.7;
            for (double y : new double[] {50.0, 50.3}) {
                double x = 60.3;
                Transform6 generic = t6(0, s, s, 0, x, y);
                Transform6 axis = t6(s, 0, 0, s, x, y);
                for (int sub = 0; sub < 3; sub++) {
                    int x0 = sub == 1 ? 1 : 0;
                    int y0 = sub == 1 ? 1 : 0;
                    int x1 = sub == 1 ? 5 : 6;
                    int y1 = sub == 1 ? 3 : 4;
                    int[] box = sub == 2 ? bbox(generic, 0.5, 0.25, 6.25, 4.5) : bbox(generic, x0, y0, x1 + 1, y1 + 1);
                    for (int wrap : new int[] {CLAMP, REPEAT, ZERO}) {
                        for (boolean linear : new boolean[] {false, true}) {
                            String what = f("Y %s sub %d %s %s", Double.toString(y), sub, wrapName(wrap),
                                    linear ? "bilinear" : "nearest");
                            rec.step("generic " + what, true, BG_ZERO, aux -> drawImage(pr, NORMAL, tex, generic, wrap,
                                    linear, box, KEEP, KEEP, KEEP, KEEP, x0, y0, x1, y1));
                            rec.step("axis-aligned twin " + what, true, BG_ZERO, aux -> drawImage(pr, NORMAL, tr,
                                    axis, wrap, linear, box, KEEP, KEEP, KEEP, KEEP, y0, x0, y1, x1));
                        }
                    }
                }
            }
        }

        /**
         * REPEAT on sub-rectangles of 32767, 32768 and 40000 texels, where {@code (max + 1) << 16} overflows a jint
         * in {@code checkBoundsRepeat}. An S15.16 translation cannot reach past texel 32767, so the samples beyond
         * the last texel come from a 1/1024 scale (SCALE_TRANSLATE), those before texel 0 from a translation
         * (TRANSLATE).
         */
        private void wideRepeat() {
            int[] widths = {32767, 32768, 40000};
            rec.begin("wide-repeat", 24, 3);
            PiscesRenderer pr = rec.pr;
            for (int w : widths) {
                int[] texels = new int[w + 1];
                for (int i = 0; i <= w; i++) {
                    texels[i] = 0xFF000000 | mix(i, 7) & 0x00FFFFFF;
                }
                List<NamedTransform> transforms = new ArrayList<>(List.of(
                        nt("translate(5,0)", t6(1, 0, 0, 1, 5, 0)),
                        nt("translate(5.5,0)", t6(1, 0, 0, 1, 5.5, 0)),
                        nt("scale 1/1024 across the last texel", t6(1 / 1024.0, 0, 0, 1, -(w - 10240) / 1024.0, 0))));
                if (w - 8 <= 32767) {
                    transforms.add(nt("translate(-(w-8),0)", t6(1, 0, 0, 1, -(w - 8.0), 0)));
                    transforms.add(nt("translate(-(w-8)-0.5,0)", t6(1, 0, 0, 1, -(w - 8.0) - 0.5, 0)));
                }
                for (NamedTransform t : transforms) {
                    for (boolean linear : new boolean[] {false, true}) {
                        rec.step(f("setTexture %dx1 repeat %s %s", w, t.name(), linear ? "bilinear" : "nearest"),
                                true, BG_OPAQUE, aux -> {
                                    pr.setCompositeRule(SRC);
                                    pr.setTexture(ARGB_PRE, texels, w, 1, w + 1, t.t(), REPEAT, linear, false);
                                    pr.fillRect(0, 0, fx(24), fx(3));
                                });
                        rec.step(f("drawImage %dx1 sub(0..%d) of %d repeat %s %s", w, w - 1, w + 1, t.name(),
                                linear ? "bilinear" : "nearest"), true, BG_OPAQUE, aux -> {
                                    pr.setCompositeRule(SRC);
                                    pr.drawImage(ARGB_PRE, NORMAL, texels, w + 1, 1, 0, w + 1, t.t(), REPEAT, linear,
                                            0, 0, fx(24), fx(3), KEEP, KEEP, KEEP, KEEP, 0, 0, w - 1, 0, false);
                                });
                    }
                }
            }
            rec.begin("tall-repeat", 3, 24);
            PiscesRenderer tpr = rec.pr;
            int h = 40000;
            int[] column = new int[h + 1];
            for (int i = 0; i <= h; i++) {
                column[i] = 0xFF000000 | mix(3, i) & 0x00FFFFFF;
            }
            List<NamedTransform> transforms = List.of(
                    nt("translate(0,5)", t6(1, 0, 0, 1, 0, 5)),
                    nt("scale 1/1024 across the last texel", t6(1, 0, 0, 1 / 1024.0, 0, -(h - 10240) / 1024.0)));
            for (NamedTransform t : transforms) {
                for (boolean linear : new boolean[] {false, true}) {
                    rec.step("setTexture 1x40000 repeat " + t.name() + (linear ? " bilinear" : " nearest"), true,
                            BG_OPAQUE, aux -> {
                                tpr.setCompositeRule(SRC);
                                tpr.setTexture(ARGB_PRE, column, 1, h, 1, t.t(), REPEAT, linear, false);
                                tpr.fillRect(0, 0, fx(3), fx(24));
                            });
                    rec.step("drawImage 1x40000 sub(0..39999) of 40001 repeat " + t.name()
                            + (linear ? " bilinear" : " nearest"), true, BG_OPAQUE, aux -> {
                                tpr.setCompositeRule(SRC);
                                tpr.drawImage(ARGB_PRE, NORMAL, column, 1, h + 1, 0, 1, t.t(), REPEAT, linear, 0, 0,
                                        fx(3), fx(24), KEEP, KEEP, KEEP, KEEP, 0, 0, 0, h - 1, false);
                            });
                }
            }
        }

        /**
         * Surfaces wider and taller than 32767 pixels: the translate-only texture paint shifts the row start
         * {@code x << 16} (and {@code y << 16}) as a jint, and {@code fillRect} clamps against {@code width << 16}.
         * Their frames are stored as hashes; each step also reads back the pixels around 32768 as its aux array.
         */
        private void farCoordinates() {
            farCoordinates("wide-33000x2", 33000, 2, true);
            farCoordinates("tall-2x33000", 2, 33000, false);
        }

        private void farCoordinates(String name, int w, int h, boolean wide) {
            rec.begin(name, w, h);
            PiscesRenderer pr = rec.pr;
            JavaSurface surface = rec.surface;
            byte[] mask = filledMask(80 * 2, (byte) 0xFF);
            int far = 32740;
            int mx = wide ? far : 0;
            int my = wide ? 0 : far;
            int mw = wide ? 80 : 2;
            int mh = wide ? 2 : 80;
            Action readBack = aux -> {
                int[] region = new int[mw * mh];
                aux.ints = region;
                surface.getRGB(region, 0, mw, mx, my, mw, mh);
            };
            rec.step("fillRect whole surface (width << 16 overflows in Java)", false, BG_OPAQUE, aux -> {
                pr.setColor(255, 0, 0, 255);
                pr.fillRect(0, 0, fx(w), fx(h));
                readBack.run(aux);
            });
            List<NamedTransform> transforms = List.of(
                    nt("translate", wide ? t6(1, 0, 0, 1, 32760.5, 0) : t6(1, 0, 0, 1, 0, 32760.5)),
                    nt("translate-int", wide ? t6(1, 0, 0, 1, 32760, 0) : t6(1, 0, 0, 1, 0, 32760)),
                    nt("scale", wide ? t6(2, 0, 0, 1, 32760.5, 0) : t6(1, 0, 0, 2, 0, 32760.5)));
            for (NamedTransform t : transforms) {
                for (int[] m : new int[][] {{CLAMP, 0}, {CLAMP, 1}, {REPEAT, 0}, {REPEAT, 1}}) {
                    rec.step(f("setTexture %s %s %s, mask at 32740 and 32800", t.name(), wrapName(m[0]),
                            m[1] == 1 ? "bilinear" : "nearest"), true, BG_OPAQUE, aux -> {
                                pr.setCompositeRule(SRC_OVER);
                                pr.setTexture(ARGB_PRE, OPAQUE_7X5.data(), 7, 5, 7, t.t(), m[0], m[1] == 1, false);
                                pr.fillAlphaMask(mask, mx, my, mw, mh, 0, mw);
                                pr.fillAlphaMask(mask, wide ? 32800 : 0, wide ? 0 : 32800, wide ? 10 : 2,
                                        wide ? 2 : 10, 0, wide ? 10 : 2);
                                readBack.run(aux);
                            });
                }
            }
            rec.step("linear gradient, mask at 32740", true, BG_OPAQUE, aux -> {
                pr.setLinearGradient(fx(32750), fx(0), fx(32790), fx(30), FRACTIONS_3, COLORS_3,
                        GradientColorMap.CYCLE_REFLECT, null);
                pr.fillAlphaMask(mask, mx, my, mw, mh, 0, mw);
                readBack.run(aux);
            });
            rec.step("emitAndClearAlphaRow from 32760, colour", false, BG_OPAQUE, aux -> {
                pr.setColor(20, 40, 250, 200);
                int[] deltas = new int[48];
                deltas[0] = 64;
                deltas[30] = -64;
                if (wide) {
                    pr.emitAndClearAlphaRow(marlinAlphaMap(64), deltas, 1, 32760, 32800, 0, 0);
                } else {
                    for (int y = 32760; y < 32800; y++) {
                        int[] row = new int[4];
                        row[0] = 64;
                        row[2] = -64;
                        pr.emitAndClearAlphaRow(marlinAlphaMap(64), row, y, 0, 2, 0, y);
                    }
                }
                readBack.run(aux);
            });
            rec.step("clearRect whole surface", false, BG_OPAQUE, aux -> {
                pr.setColor(0, 0, 0, 0);
                pr.clearRect(0, 0, w, h);
                readBack.run(aux);
            });
        }

        private static byte[] filledMask(int length, byte value) {
            byte[] mask = new byte[length];
            Arrays.fill(mask, value);
            return mask;
        }

        private void alphaMasks() {
            rec.begin("mask", 20, 20);
            PiscesRenderer pr = rec.pr;
            byte[] coverage = coverageMask(16, 16);
            int[][] colors = {{10, 200, 90, 255}, {200, 40, 120, 128}, {255, 255, 255, 1}, {30, 60, 90, 0}};
            for (int rule : new int[] {SRC_OVER, SRC}) {
                for (int[] c : colors) {
                    rec.step(f("coverage 0..255 %s color(%d,%d,%d,%d)", ruleName(rule), c[0], c[1], c[2], c[3]),
                            false, BG_TRANSLUCENT, aux -> {
                                pr.setCompositeRule(rule);
                                pr.setColor(c[0], c[1], c[2], c[3]);
                                pr.fillAlphaMask(coverage, 2, 2, 16, 16, 0, 16);
                            });
                }
                rec.step("coverage 0..255 " + ruleName(rule) + " linear gradient", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setLinearGradient(fx(2), fx(2), fx(18), fx(17), FRACTIONS_3, COLORS_3,
                            GradientColorMap.CYCLE_REFLECT, null);
                    pr.fillAlphaMask(coverage, 2, 2, 16, 16, 0, 16);
                });
                rec.step("coverage 0..255 " + ruleName(rule) + " radial gradient", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setRadialGradient(fx(10), fx(10), fx(12), fx(9), fx(8), FRACTIONS_5, COLORS_5,
                            GradientColorMap.CYCLE_REPEAT, null);
                    pr.fillAlphaMask(coverage, 2, 2, 16, 16, 0, 16);
                });
                rec.step("coverage 0..255 " + ruleName(rule) + " texture", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setTexture(ARGB_PRE, TRANSLUCENT_7X5.data(), 7, 5, 7, t6(2, 0, 0, 2, 2, 1), REPEAT, true, true);
                    pr.fillAlphaMask(coverage, 2, 2, 16, 16, 0, 16);
                });
            }
            byte[] glyph = glyphMask(12, 10);
            int[][] geometries = {
                // x, y, width, height, offset, stride
                {-3, 4, 12, 10, 0, 12}, {4, -4, 12, 10, 0, 12}, {10, 4, 12, 10, 0, 12}, {4, 12, 12, 10, 0, 12},
                {4, 4, 8, 6, 13, 12}, {4, 4, 8, 6, 0, 12}, {4, 4, 0, 6, 0, 12}, {25, 4, 12, 10, 0, 12},
                {Integer.MIN_VALUE, 4, 12, 10, 0, 12}, {-(Integer.MAX_VALUE - 12), 4, 12, 10, 0, 12},
                {-(Integer.MAX_VALUE - 12) + 1, 4, 12, 10, 0, 12}, {Integer.MAX_VALUE - 12, 4, 12, 10, 0, 12},
                {Integer.MAX_VALUE - 13, 4, 12, 10, 0, 12}, {4, -(1 << 30), 12, 10, 0, 12},
                {4, Integer.MAX_VALUE - 10, 12, 10, 0, 12}, {-3, -4, 12, 10, 0, 12},
                {-(Integer.MAX_VALUE - 12) + 1, -5, 12, 10, 0, 12}, {-(Integer.MAX_VALUE - 12) + 1, 4, 12, 8, 13, 12}
            };
            for (int[] g : geometries) {
                String where = f("x %d y %d %dx%d offset %d stride %d", g[0], g[1], g[2], g[3], g[4], g[5]);
                rec.step("glyph " + where + " colour", false, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setColor(240, 30, 70, 200);
                    pr.fillAlphaMask(glyph, g[0], g[1], g[2], g[3], g[4], g[5]);
                });
            }
            for (int[] g : new int[][] {{-3, 4, 12, 10, 0, 12}, {-3, -4, 12, 10, 0, 12}}) {
                String where = f("x %d y %d", g[0], g[1]);
                rec.step("glyph " + where + " linear gradient", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setLinearGradient(0, 0, fx(20), fx(3), FRACTIONS_3, COLORS_3, GradientColorMap.CYCLE_NONE,
                            null);
                    pr.fillAlphaMask(glyph, g[0], g[1], g[2], g[3], g[4], g[5]);
                });
                rec.step("glyph " + where + " texture", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC);
                    pr.setTexture(ARGB_PRE, OPAQUE_7X5.data(), 7, 5, 7, t6(1, 0, 0, 1, 1, 2), CLAMP, false, false);
                    pr.fillAlphaMask(glyph, g[0], g[1], g[2], g[3], g[4], g[5]);
                });
            }
            rec.step("glyph inside clip(5,5,8,8)", false, BG_TRANSLUCENT, aux -> {
                pr.setCompositeRule(SRC_OVER);
                pr.setColor(240, 30, 70, 200);
                pr.setClip(5, 5, 8, 8);
                pr.fillAlphaMask(glyph, 3, 3, 12, 10, 0, 12);
            });
            Object[][] bad = {
                {"null mask", null, 12, 10, 0, 12}, {"width -1", glyph, -1, 10, 0, 12},
                {"stride < width", glyph, 12, 10, 0, 11}, {"offset -1", glyph, 12, 10, -1, 12},
                {"mask too short", glyph, 12, 10, 1, 12}
            };
            for (Object[] b : bad) {
                byte[] m = (byte[]) b[1];
                rec.step("fillAlphaMask " + b[0], false, BG_TRANSLUCENT, aux -> pr.fillAlphaMask(m, 2, 2,
                        (Integer) b[2], (Integer) b[3], (Integer) b[4], (Integer) b[5]));
                rec.step("fillLCDAlphaMask " + b[0], false, BG_TRANSLUCENT, aux -> pr.fillLCDAlphaMask(m, 2, 2,
                        (Integer) b[2], (Integer) b[3], (Integer) b[4], (Integer) b[5]));
            }
        }

        private void lcdMasks() {
            rec.begin("lcd", 20, 20);
            PiscesRenderer pr = rec.pr;
            byte[] coverage = lcdCoverageMask(16, 16);
            int[][] colors = {{240, 120, 30, 255}, {240, 120, 30, 128}, {0, 0, 0, 255}, {255, 255, 255, 255}};
            float[] gammas = {1f, 1.4f, 1f / 1.3f};
            for (float gamma : gammas) {
                for (int[] c : colors) {
                    rec.step(f("gamma %s coverage 0..255 color(%d,%d,%d,%d)", Float.toString(gamma), c[0], c[1], c[2],
                            c[3]), true, BG_OPAQUE, aux -> {
                                pr.setCompositeRule(SRC_OVER);
                                pr.setColor(c[0], c[1], c[2], c[3]);
                                pr.setLCDGammaCorrection(gamma);
                                pr.fillLCDAlphaMask(coverage, 2, 2, 48, 16, 0, 48);
                            });
                }
            }
            for (int bg : new int[] {BG_TRANSLUCENT, BG_ZERO, BG_GARBAGE}) {
                rec.step("gamma 1/1.3 coverage 0..255 color(240,120,30,255) background " + bg, true, bg, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setColor(240, 120, 30, 255);
                    pr.setLCDGammaCorrection(1f / 1.3f);
                    pr.fillLCDAlphaMask(coverage, 2, 2, 48, 16, 0, 48);
                });
            }
            byte[] glyph = glyphMask(30, 8);
            int[][] geometries = {
                // x, y, width (bytes), height, offset, stride
                {3, 3, 7, 4, 0, 30}, {3, 3, 8, 4, 0, 30}, {-2, 3, 30, 8, 0, 30}, {15, 3, 30, 8, 0, 30},
                {3, -3, 30, 8, 0, 30}, {3, 15, 30, 8, 0, 30}, {3, 3, 12, 4, 31, 30}, {3, 3, 12, 4, 0, 30},
                {-(Integer.MAX_VALUE - 10) - 1, 3, 30, 8, 0, 30}, {-(Integer.MAX_VALUE - 10), 3, 30, 8, 0, 30},
                {Integer.MAX_VALUE - 10, 3, 30, 8, 0, 30}, {Integer.MAX_VALUE - 11, 3, 30, 8, 0, 30},
                {3, -(Integer.MAX_VALUE - 8) - 1, 30, 8, 0, 30}, {-(Integer.MAX_VALUE / 3) - 5, 3, 30, 8, 0, 30},
                {3, -(1 << 30), 30, 8, 0, 30}, {3, 3, 0, 8, 0, 30},
                {-(Integer.MAX_VALUE / 3) + 10, -2, 30, 8, 0, 30}, {-(Integer.MAX_VALUE / 3) + 10, 3, 30, 6, 31, 30}
            };
            for (int[] g : geometries) {
                rec.step(f("gamma 1/1.3 glyph x %d y %d %dx%d offset %d stride %d", g[0], g[1], g[2], g[3], g[4],
                        g[5]), true, BG_OPAQUE, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setColor(240, 120, 30, 255);
                            pr.setLCDGammaCorrection(1f / 1.3f);
                            pr.fillLCDAlphaMask(glyph, g[0], g[1], g[2], g[3], g[4], g[5]);
                        });
            }
            rec.step("gamma 1/1.3 glyph inside clip(5,5,8,8)", true, BG_OPAQUE, aux -> {
                pr.setCompositeRule(SRC_OVER);
                pr.setColor(240, 120, 30, 255);
                pr.setLCDGammaCorrection(1f / 1.3f);
                pr.setClip(5, 5, 8, 8);
                pr.fillLCDAlphaMask(glyph, 2, 3, 30, 8, 0, 30);
            });
        }

        /**
         * Every entry of both gamma tables of {@code initGammaArrays} for each gamma: row 0 composites full white
         * through coverage bytes 0..255 over black, which yields {@code gammaArray[coverage]}; row 1 composites the
         * colours (k, k + 86, min(k + 170, 255)) at full coverage, which yields {@code invGammaArray} of each.
         */
        private void lcdGammaTables() {
            rec.begin("lcd-gamma", 86, 2);
            PiscesRenderer pr = rec.pr;
            byte[] strip = new byte[3 * 86];
            for (int k = 0; k < 86; k++) {
                strip[3 * k] = (byte) k;
                strip[3 * k + 1] = (byte) (k + 86);
                strip[3 * k + 2] = (byte) Math.min(k + 170, 255);
            }
            byte[] full = {(byte) 255, (byte) 255, (byte) 255};
            List<Float> gammas = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (int contrast = 1000; contrast <= 2200; contrast += 10) {
                gammas.add(1f / (contrast / 1000f));
                labels.add("1/(" + contrast + "/1000)");
            }
            for (int k = 10; k <= 60; k++) {
                gammas.add(k / 20f);
                labels.add(k + "/20");
            }
            float[] extremes = {Float.MIN_VALUE, 1e-30f, 0.01f, 0.1f, Math.nextDown(1f), Math.nextUp(1f), 10f, 100f,
                1e30f, Float.MAX_VALUE, Float.POSITIVE_INFINITY};
            for (float g : extremes) {
                gammas.add(g);
                labels.add(Float.toString(g));
            }
            for (int i = 0; i < gammas.size(); i++) {
                float gamma = gammas.get(i);
                rec.step(f("gamma %s = %s: gamma and inverse tables", labels.get(i), Float.toString(gamma)), true,
                        BG_ZERO, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setColor(255, 255, 255, 255);
                            pr.setLCDGammaCorrection(gamma);
                            pr.fillLCDAlphaMask(strip, 0, 0, 3 * 86, 1, 0, 3 * 86);
                            for (int k = 0; k < 86; k++) {
                                pr.setColor(k, k + 86, Math.min(k + 170, 255), 255);
                                pr.fillLCDAlphaMask(full, k, 1, 3, 1, 0, 3);
                            }
                        });
            }
            rec.step("gamma NaN: white over white, the one entry (255) every table keeps", true, BG_WHITE, aux -> {
                pr.setCompositeRule(SRC_OVER);
                pr.setColor(255, 255, 255, 255);
                pr.setLCDGammaCorrection(Float.NaN);
                pr.fillLCDAlphaMask(strip, 0, 0, 3 * 86, 1, 0, 3 * 86);
                pr.fillLCDAlphaMask(full, 0, 1, 3, 1, 0, 3);
            });
            rec.step("gamma 1 again after NaN", true, BG_ZERO, aux -> {
                pr.setColor(255, 255, 255, 255);
                pr.setLCDGammaCorrection(1f);
                pr.fillLCDAlphaMask(strip, 0, 0, 3 * 86, 1, 0, 3 * 86);
            });
        }

        private void alphaRows() {
            rec.begin("rows", 66, 6);
            PiscesRenderer pr = rec.pr;
            byte[] identity = identityAlphaMap();
            int[][] colors = {{30, 60, 200, 255}, {30, 60, 200, 128}, {30, 60, 200, 0}};
            for (int rule : new int[] {SRC_OVER, SRC, CLEAR}) {
                for (int[] c : colors) {
                    rec.step(f("coverage 0..255 %s color(%d,%d,%d,%d)", ruleName(rule), c[0], c[1], c[2], c[3]),
                            false, BG_TRANSLUCENT, aux -> {
                                pr.setCompositeRule(rule);
                                pr.setColor(c[0], c[1], c[2], c[3]);
                                aux.ints = emitCoverageRows(pr, identity);
                            });
                }
                rec.step("coverage 0..255 " + ruleName(rule) + " linear gradient", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setLinearGradient(fx(1), 0, fx(64), fx(5), FRACTIONS_5, COLORS_5, GradientColorMap.CYCLE_REPEAT,
                            null);
                    aux.ints = emitCoverageRows(pr, identity);
                });
                rec.step("coverage 0..255 " + ruleName(rule) + " radial gradient", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setRadialGradient(fx(33), fx(3), fx(35), fx(2), fx(20), FRACTIONS_3, COLORS_3,
                            GradientColorMap.CYCLE_REFLECT, null);
                    aux.ints = emitCoverageRows(pr, identity);
                });
                rec.step("coverage 0..255 " + ruleName(rule) + " texture", true, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(rule);
                    pr.setTexture(ARGB_PRE, TRANSLUCENT_7X5.data(), 7, 5, 7, rot(30, 1.8, 1.8, 9, 1), REPEAT, true,
                            true);
                    aux.ints = emitCoverageRows(pr, identity);
                });
            }
            byte[] marlin = marlinAlphaMap(64);
            // never xOff + (xTo - xFrom) == deltas.length: the Java check lets it through and the C then reads and
            // zeroes deltas[length]
            int[][] rows = {
                // y, xFrom, xTo, xOff, deltas length, clip x, clip w
                {1, 0, 15, 2, 24, 0, 66}, {1, 0, 0, 0, 4, 0, 66}, {2, 65, 65, 0, 4, 0, 66}, {2, 64, 65, 1, 6, 0, 66},
                {3, 10, 5, 0, 16, 0, 66}, {7, 0, 15, 0, 24, 0, 66}, {-1, 0, 15, 0, 24, 0, 66},
                {3, 2, 17, 3, 24, 6, 40}, {3, 30, 45, 3, 24, 6, 34}, {4, -5, 10, 0, 20, 0, 66},
                {4, 0, 15, 0, 14, 0, 66}, {4, 0, 15, -1, 24, 0, 66}, {5, 50, 70, 0, 26, 0, 66},
                {5, 0, 65, 0, 70, 0, 66}
            };
            for (int[] r : rows) {
                rec.step(f("Marlin map, row y %d x %d..%d off %d of int[%d], clip x %d w %d", r[0], r[1], r[2], r[3],
                        r[4], r[5], r[6]), false, BG_TRANSLUCENT, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setColor(200, 30, 90, 230);
                            pr.setClip(r[5], 0, r[6], 6);
                            int[] deltas = new int[r[4]];
                            int span = r[2] - r[1] + 1;
                            for (int i = 0; i < span && r[3] + i >= 0 && r[3] + i < deltas.length - 1; i++) {
                                deltas[r[3] + i] = (i == 0) ? 40 : (i % 3 == 0 ? 8 : (i % 3 == 1 ? -8 : 0));
                            }
                            aux.ints = deltas;
                            pr.emitAndClearAlphaRow(marlin, deltas, r[0], r[1], r[2], r[3], 3);
                        });
            }
        }

        /**
         * Four rows of 64 pixels at x 1..64 whose coverage counts 0..255 through the identity alpha map; returns
         * the delta array after the calls, which the compositor has zeroed.
         */
        private static int[] emitCoverageRows(PiscesRenderer pr, byte[] alphaMap) {
            int[] deltas = new int[80];
            for (int row = 0; row < 4; row++) {
                deltas[4] = 64 * row;
                for (int i = 1; i < 64; i++) {
                    deltas[4 + i] = 1;
                }
                pr.emitAndClearAlphaRow(alphaMap, deltas, 1 + row, 1, 64, 4, row);
            }
            return deltas;
        }

        /**
         * get/setRGB arguments whose int sums overflow in {@code AbstractSurface.rgbCheck}, so that only the C
         * checks of {@code psw_surface_get_rgb}/{@code psw_surface_set_rgb} reject them: {@code x + width} and
         * {@code y + height} past {@code Integer.MAX_VALUE} (zero rows or zero columns, so nothing would be
         * copied), and an offset at which the end of the copied range passes {@code Integer.MAX_VALUE}.
         */
        private void rgbArgumentOverflow() {
            rec.begin("rgb-overflow", 4, 3);
            JavaSurface surface = rec.surface;
            int max = Integer.MAX_VALUE;
            int[][] cases = {
                // offset, scan, x, y, w, h, array length
                {0, max, 1, 0, max, 0, 1}, {0, 0, 0, 1, 0, max, 1}, {max, 1, 0, 0, 1, 1, 1}, {max - 2, 3, 0, 0, 2, 2, 4}
            };
            for (int[] c : cases) {
                String args = f("offset %d, scan %d, x %d, y %d, %dx%d, int[%d]", c[0], c[1], c[2], c[3], c[4], c[5],
                        c[6]);
                rec.step("getRGB " + args, false, BG_GARBAGE, aux -> {
                    int[] out = filled(c[6], 0x5A5A5A5A);
                    aux.ints = out;
                    surface.getRGB(out, c[0], c[1], c[2], c[3], c[4], c[5]);
                });
                rec.step("setRGB " + args, false, BG_GARBAGE,
                        aux -> surface.setRGB(filled(c[6], 0x77665544), c[0], c[1], c[2], c[3], c[4], c[5]));
            }
        }

        /**
         * Rows at full coverage under an opaque paint, where {@code blitPTSrcOver8888_pre} stores the paint
         * colour without blending ({@code aval == 255}), followed by coverage 200 in the same row.
         */
        private void opaquePaintRows() {
            rec.begin("rows-opaque-paint", 8, 2);
            PiscesRenderer pr = rec.pr;
            byte[] identity = identityAlphaMap();
            for (int rule : new int[] {SRC_OVER, SRC}) {
                rec.step("opaque linear gradient " + ruleName(rule) + ", coverage 255 then 200", true, BG_TRANSLUCENT,
                        aux -> {
                            pr.setCompositeRule(rule);
                            pr.setLinearGradient(0, 0, 0xFF0000FF, fx(8), 0, 0xFFFF0000, GradientColorMap.CYCLE_NONE);
                            aux.ints = emitOpaqueRows(pr, identity);
                        });
                rec.step("opaque7x5 texture " + ruleName(rule) + ", coverage 255 then 200", true, BG_TRANSLUCENT,
                        aux -> {
                            pr.setCompositeRule(rule);
                            pr.setTexture(ARGB_PRE, OPAQUE_7X5.data(), 7, 5, 7, new Transform6(), REPEAT, false,
                                    false);
                            aux.ints = emitOpaqueRows(pr, identity);
                        });
            }
        }

        /** Row 0 at coverage 255 throughout, row 1 at 255 for x 0..4 and 200 for x 5..7; returns the deltas. */
        private static int[] emitOpaqueRows(PiscesRenderer pr, byte[] alphaMap) {
            int[] deltas = new int[9];
            deltas[0] = 255;
            pr.emitAndClearAlphaRow(alphaMap, deltas, 0, 0, 7, 0, 0);
            deltas[0] = 255;
            deltas[5] = -55;
            pr.emitAndClearAlphaRow(alphaMap, deltas, 1, 0, 7, 0, 1);
            return deltas;
        }

        /**
         * Texture paints whose bilinear sample fraction is zero on one axis or on both for every pixel, in each
         * transform class: half-pixel translations (TRANSLATE), a horizontal scale of 1/2 with and without a
         * fraction and a vertical one (SCALE_TRANSLATE), quarter turns whose fraction lies on one axis or none
         * (GENERIC with m00 == m11 == 0), a vertical shear at unit scale (GENERIC although m00 == m11 == 1) and a
         * transform whose inverse has m00 == 0 and m11 != 0. Both filters, the three wrap modes, with and without
         * alpha.
         */
        private void zeroFractionTextures() {
            rec.begin("texture-zero-fraction", 8, 8);
            PiscesRenderer pr = rec.pr;
            List<NamedTransform> transforms = List.of(
                    nt("translate(0.5,0)", t6(1, 0, 0, 1, 0.5, 0)),
                    nt("translate(0,0.5)", t6(1, 0, 0, 1, 0, 0.5)),
                    nt("scale(0.5,1)", t6(0.5, 0, 0, 1, 0, 0)),
                    nt("scale(0.5,1)+(0.25,0)", t6(0.5, 0, 0, 1, 0.25, 0)),
                    nt("scale(1,0.5)", t6(1, 0, 0, 0.5, 0, 0)),
                    nt("rot90+(4,0.5)", t6(0, -1, 1, 0, 4, 0.5)),
                    nt("rot90+(4.5,0)", t6(0, -1, 1, 0, 4.5, 0)),
                    nt("rot90+(4,0)", t6(0, -1, 1, 0, 4, 0)),
                    nt("shear-y 0.5 at unit scale", t6(1, 0, 0.5, 1, 0, 0)),
                    nt("inverse m00 0 m11 -1", t6(1, 1, 1, 0, 0, 0)));
            List<Texture> textures = List.of(
                    new Texture("translucent4x4", translucentTexels(4, 4), 4, 4, 0, 4, true),
                    new Texture("opaque4x4", opaqueTexels(4, 4), 4, 4, 0, 4, false));
            for (Texture tex : textures) {
                for (NamedTransform t : transforms) {
                    for (int wrap : new int[] {CLAMP, REPEAT, ZERO}) {
                        for (boolean linear : new boolean[] {true, false}) {
                            rec.step(f("%s %s %s %s", tex.name(), t.name(), wrapName(wrap), linear ? "bilinear"
                                    : "nearest"), true, BG_TRANSLUCENT, aux -> {
                                        pr.setCompositeRule(SRC_OVER);
                                        pr.setTexture(ARGB_PRE, tex.data(), tex.width(), tex.height(), tex.stride(),
                                                t.t(), wrap, linear, tex.hasAlpha());
                                        pr.fillRect(0, 0, fx(8), fx(8));
                                    });
                        }
                    }
                }
            }
        }

        /**
         * Bilinear drawImage of sub-rectangles that start two or more texels inside the content or end two or
         * more texels before its last texel: under CLAMP_TO_ZERO {@code checkBoundsNoRepeat} clamps a footprint
         * that lies inside the content but outside the sub-rectangle to the sub-rectangle, not to transparent.
         */
        private void zeroEdgeSubRectangles() {
            rec.begin("drawImage-zero-edge", 8, 8);
            PiscesRenderer pr = rec.pr;
            Texture tex = new Texture("translucent8x8", translucentTexels(8, 8), 8, 8, 0, 8, true);
            int[][] subs = {{4, 0, 7, 7}, {0, 0, 3, 7}, {0, 4, 7, 7}, {0, 0, 7, 3}, {3, 2, 5, 5}};
            String[] names = {"right-half", "left-half", "bottom-half", "top-half", "middle"};
            List<NamedTransform> transforms = List.of(
                    nt("translate(0.5,0.5)", t6(1, 0, 0, 1, 0.5, 0.5)),
                    nt("scale1.25+(0.3,0.2)", t6(1.25, 0, 0, 1.25, 0.3, 0.2)),
                    nt("rot30+(3,-1)", rot(30, 1, 1, 3, -1)));
            int[] box = {0, 0, fx(8), fx(8)};
            for (int s = 0; s < subs.length; s++) {
                int[] sub = subs[s];
                for (NamedTransform t : transforms) {
                    for (int wrap : new int[] {ZERO, CLAMP, REPEAT}) {
                        rec.step(f("%s sub(%d,%d,%d,%d) %s %s bilinear", names[s], sub[0], sub[1], sub[2], sub[3],
                                t.name(), wrapName(wrap)), true, BG_TRANSLUCENT, aux -> {
                                    pr.setCompositeRule(SRC_OVER);
                                    drawImage(pr, NORMAL, tex, t.t(), wrap, true, box, KEEP, KEEP, KEEP, KEEP, sub[0],
                                            sub[1], sub[2], sub[3]);
                                });
                    }
                }
            }
        }

        /**
         * IMAGE_MODE_MULTIPLY after colours with red 255 and green or blue below 255, which leave the white
         * shortcut of {@code genTexturePaintMultiply} at its second and third test.
         */
        private void multiplyColourTests() {
            Transform6 t = t6(2.5, 0, 0, 2.5, 2.3, 1.6);
            int[] box = bbox(t, 0, 0, 7, 5);
            rec.begin("multiply-colour", 24, 16);
            PiscesRenderer pr = rec.pr;
            int[][] colors = {{255, 0, 0, 255}, {255, 255, 0, 255}, {255, 0, 0, 128}, {255, 255, 0, 64},
                {255, 255, 254, 255}};
            for (int[] c : colors) {
                for (Texture tex : List.of(OPAQUE_7X5, TRANSLUCENT_7X5)) {
                    rec.step(f("color(%d,%d,%d,%d) %s", c[0], c[1], c[2], c[3], tex.name()), true, BG_TRANSLUCENT,
                            aux -> {
                                pr.setCompositeRule(SRC_OVER);
                                pr.setColor(c[0], c[1], c[2], c[3]);
                                drawImage(pr, MULTIPLY, tex, t, CLAMP, true, box, KEEP, KEEP, KEEP, KEEP, 0, 0, 6, 4);
                            });
                }
            }
        }

        /** Colour changes in one channel only: each must reach the next fill. */
        private void colourChanges() {
            rec.begin("colour-change", 6, 4);
            PiscesRenderer pr = rec.pr;
            int[][] seconds = {{10, 20, 99, 255}, {10, 77, 30, 255}, {66, 20, 30, 255}, {10, 20, 30, 128},
                {10, 20, 30, 255}};
            String[] names = {"blue only", "green only", "red only", "alpha only", "no change"};
            for (int i = 0; i < seconds.length; i++) {
                int[] c = seconds[i];
                rec.step("color(10,20,30,255) left half, then " + names[i] + f(" color(%d,%d,%d,%d) right half",
                        c[0], c[1], c[2], c[3]), false, BG_TRANSLUCENT, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setColor(10, 20, 30, 255);
                            pr.fillRect(0, 0, fx(3), fx(4));
                            pr.setColor(c[0], c[1], c[2], c[3]);
                            pr.fillRect(fx(3), 0, fx(3), fx(4));
                        });
            }
        }

        /**
         * The early returns of the mask fills on the y side, at and next to each bound: {@code y <= -(INT_MAX - h)}
         * and {@code y >= INT_MAX - h} for alpha masks, {@code y < -(INT_MAX - h)} and {@code y >= INT_MAX - h}
         * for LCD masks.
         */
        private void maskGuardsOnY() {
            rec.begin("mask-y-guards", 8, 8);
            PiscesRenderer pr = rec.pr;
            byte[] alpha = filledMask(64, (byte) 0xFF);
            int max = Integer.MAX_VALUE;
            for (int y : new int[] {-(max - 8) - 1, -(max - 8), -(max - 8) + 1, max - 8, max - 9}) {
                rec.step("fillAlphaMask 8x8 x 0 y " + y, false, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setColor(240, 30, 70, 255);
                    pr.fillAlphaMask(alpha, 0, y, 8, 8, 0, 8);
                });
            }
            byte[] lcd = filledMask(6, (byte) 0xFF);
            for (int y : new int[] {-(max - 2) - 1, -(max - 2), max - 2, max - 3}) {
                rec.step("fillLCDAlphaMask 3x2 x 0 y " + y, false, BG_TRANSLUCENT, aux -> {
                    pr.setCompositeRule(SRC_OVER);
                    pr.setColor(240, 120, 30, 255);
                    pr.fillLCDAlphaMask(lcd, 0, y, 3, 2, 0, 3);
                });
            }
        }

        /**
         * Runs one step on a surface first overwritten with {@code content}, a pattern that is not one of the
         * {@code BG_*} kinds; like a background pattern, the full {@code setRGB} resets the clip and clears the
         * float taint of the case.
         */
        private void stepOver(int[] content, String op, boolean floatOp, Action action) {
            rec.surface.setRGB(content, 0, rec.width, 0, 0, rec.width, rec.height);
            rec.pr.resetClip();
            rec.tainted = false;
            rec.step(op, floatOp, BG_NONE, action);
        }

        /**
         * LCD masks of zero coverage on every subpixel over a surface whose red, green and blue channels each take
         * every value 0..255, 255 included: {@code blendLCDSrcOver8888_pre} turns each channel d into
         * {@code gammaArray[invGammaArray[d]]}, whatever the colour, at the gammas 1, 1/1.3, 0.5, 1.4 and 2.2. The
         * gamma tables of the C are process-wide, so the case ends at gamma 1 again, as lcd-gamma does.
         */
        private void lcdZeroCoverage() {
            rec.begin("lcd-zero-coverage", 16, 16);
            PiscesRenderer pr = rec.pr;
            int[] ramp = channelRamp();
            byte[] zero = new byte[3 * 16 * 16];
            int[][] colors = {{0, 0, 0, 255}, {255, 255, 255, 255}};
            for (float gamma : new float[] {1f, 1f / 1.3f, 0.5f, 1.4f, 2.2f}) {
                for (int[] c : colors) {
                    stepOver(ramp, f("gamma %s zero coverage over every channel value color(%d,%d,%d,%d)",
                            Float.toString(gamma), c[0], c[1], c[2], c[3]), true, aux -> {
                                pr.setCompositeRule(SRC_OVER);
                                pr.setColor(c[0], c[1], c[2], c[3]);
                                pr.setLCDGammaCorrection(gamma);
                                pr.fillLCDAlphaMask(zero, 0, 0, 3 * 16, 16, 0, 3 * 16);
                            });
                }
            }
            stepOver(ramp, "gamma 1 again: zero coverage over every channel value color(0,0,0,255)", true, aux -> {
                pr.setCompositeRule(SRC_OVER);
                pr.setColor(0, 0, 0, 255);
                pr.setLCDGammaCorrection(1f);
                pr.fillLCDAlphaMask(zero, 0, 0, 3 * 16, 16, 0, 3 * 16);
            });
        }

        /** 256 opaque pixels whose red, green and blue channels each take every value 0..255 once. */
        private static int[] channelRamp() {
            int[] ramp = new int[256];
            for (int i = 0; i < 256; i++) {
                ramp[i] = 0xFF000000 | (i << 16) | ((255 - i) << 8) | ((i * 7 + 3) & 0xFF);
            }
            return ramp;
        }

        /**
         * A Marlin row whose running coverage falls to zero for three pixels and rises again, under a paint and
         * SRC_OVER: {@code blitPTSrcOver8888_pre} leaves a pixel of zero running coverage untouched. A linear
         * gradient and a texture, over a transparent and over an opaque background.
         */
        private void zeroCoverageRunsUnderPaints() {
            rec.begin("rows-zero-run-paint", 8, 1);
            PiscesRenderer pr = rec.pr;
            byte[] marlin = marlinAlphaMap(64);
            int[] texels = new int[16];
            for (int i = 0; i < 16; i++) {
                texels[i] = 0xFF000000 | ((i * 16) << 8) | 0x40;
            }
            for (int bg : new int[] {0x00000000, 0xFF102030}) {
                stepOver(filled(8, bg), f("linear gradient over 0x%08X, Marlin coverage 64 64 32 0 0 0 64 64", bg),
                        true, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setLinearGradient(0, 0, 0xFF0000FF, fx(8), 0, 0xFFFF0000,
                                    GradientColorMap.CYCLE_NONE);
                            aux.ints = emitZeroRunRow(pr, marlin);
                        });
                stepOver(filled(8, bg), f("16x1 texture over 0x%08X, Marlin coverage 64 64 32 0 0 0 64 64", bg),
                        true, aux -> {
                            pr.setCompositeRule(SRC_OVER);
                            pr.setTexture(ARGB_PRE, texels, 16, 1, 16, new Transform6(), REPEAT, false, false);
                            aux.ints = emitZeroRunRow(pr, marlin);
                        });
            }
        }

        /** The deltas {64, 0, -32, -32, 0, 0, 64, 0, 0} at x 0..7 of row 0; returns them after the call. */
        private static int[] emitZeroRunRow(PiscesRenderer pr, byte[] alphaMap) {
            int[] deltas = {64, 0, -32, -32, 0, 0, 64, 0, 0};
            pr.emitAndClearAlphaRow(alphaMap, deltas, 0, 0, 7, 0, 0);
            return deltas;
        }

        /**
         * An opaque 4x4 texture with one translucent premultiplied texel, bilinear with alpha and CLAMP, under
         * translations whose sample fractions are nonzero on both axes: the translucent texel is in turn the
         * top-left, top-right, bottom-left and bottom-right point of a footprint whose other three points are
         * opaque. As a texture paint and through drawImage, under SRC and SRC_OVER.
         */
        private void oneTranslucentTexel() {
            rec.begin("texture-one-translucent-texel", 4, 4);
            PiscesRenderer pr = rec.pr;
            int[] data = filled(16, 0xFF604020);
            data[5] = 0x80402010;
            Texture tex = new Texture("opaque4x4 with 0x80402010 at (1,1)", data, 4, 4, 0, 4, true);
            int[] box = {0, 0, fx(4), fx(4)};
            List<NamedTransform> transforms = List.of(
                    nt("translate(0.5,0.5)", t6(1, 0, 0, 1, 0.5, 0.5)),
                    nt("translate(0.25,0.75)", t6(1, 0, 0, 1, 0.25, 0.75)));
            for (NamedTransform t : transforms) {
                for (int rule : new int[] {SRC, SRC_OVER}) {
                    rec.step(f("setTexture %s %s clamp bilinear under %s", tex.name(), t.name(), ruleName(rule)),
                            true, BG_TRANSLUCENT, aux -> {
                                pr.setCompositeRule(rule);
                                pr.setTexture(ARGB_PRE, tex.data(), tex.width(), tex.height(), tex.stride(), t.t(),
                                        CLAMP, true, tex.hasAlpha());
                                pr.fillRect(0, 0, fx(4), fx(4));
                            });
                    rec.step(f("drawImage %s %s clamp bilinear under %s", tex.name(), t.name(), ruleName(rule)),
                            true, BG_TRANSLUCENT, aux -> {
                                pr.setCompositeRule(rule);
                                drawImage(pr, NORMAL, tex, t.t(), CLAMP, true, box, KEEP, KEEP, KEEP, KEEP, 0, 0, 3,
                                        3);
                            });
                }
            }
        }

        /**
         * emitAndClearAlphaRow delta ranges that end at the last int of the delta array, the longest range the C
         * reads inside the array (one int longer passes the Java check, and the C then reads and zeroes one int
         * past the array): SRC_OVER colour runs at coverage 200, back to 0, and at 255 to the end of the row, an
         * offset into the delta array, SRC, and SRC_OVER under a linear gradient at coverage 200 and 255.
         */
        private void deltaRangesToTheLastInt() {
            rec.begin("rows-last-delta", 7, 1);
            PiscesRenderer pr = rec.pr;
            byte[] identity = identityAlphaMap();
            int[][] rows = {
                // rule, linear gradient (1) or colour (0), xFrom, xTo, xOff, then the deltas
                {SRC_OVER, 0, 0, 4, 0, 200, 0, 0, 0, 0}, {SRC_OVER, 0, 0, 4, 0, 200, 0, -200, 0, 0},
                {SRC_OVER, 0, 0, 6, 0, 255, 0, 0, 0, 0, 0, 0}, {SRC_OVER, 0, 0, 3, 2, 0, 0, 128, 0, 0, 0},
                {SRC, 0, 0, 4, 0, 200, 0, 0, 0, 0}, {SRC_OVER, 1, 0, 4, 0, 200, 0, 0, 0, 0},
                {SRC_OVER, 1, 0, 4, 0, 255, 0, 0, 0, 0}
            };
            for (int[] r : rows) {
                boolean gradient = r[1] == 1;
                int[] start = Arrays.copyOfRange(r, 5, r.length);
                stepOver(filled(7, 0xFF102030), f("%s %s, row x %d..%d off %d of int[%d] %s", ruleName(r[0]),
                        gradient ? "linear gradient" : "colour", r[2], r[3], r[4], start.length,
                        Arrays.toString(start)), gradient, aux -> {
                            pr.setColor(255, 0, 0, 255);
                            pr.setCompositeRule(r[0]);
                            if (gradient) {
                                pr.setLinearGradient(0, 0, fx(7), 0, new int[] {0, 0x10000},
                                        new int[] {0xFF0000FF, 0x80FF0000}, GradientColorMap.CYCLE_NONE,
                                        new Transform6());
                            }
                            int[] deltas = start.clone();
                            aux.ints = deltas;
                            pr.emitAndClearAlphaRow(identity, deltas, 0, r[2], r[3], r[4], 0);
                        });
            }
        }

        /**
         * Texture paints whose alpha-0 texels carry a colour (not premultiplied), at full coverage under SRC_OVER:
         * {@code emitLinePTSourceOver8888_pre} and {@code blitPTSrcOver8888_pre} leave the pixel under a paint
         * pixel of alpha 0 untouched, whatever its colour. A 4-pixel fillRect, a full-coverage emitAndClearAlphaRow,
         * and a 16-pixel fillRect of mostly opaque texels.
         */
        private void alphaZeroTexelsWithColour() {
            rec.begin("texture-alpha-zero-colour", 4, 1);
            PiscesRenderer pr = rec.pr;
            int[] fillTexels = {0x00000040, 0x80404040, 0xFF00FF00, 0x00402000};
            stepOver(filled(4, 0xFF102030), "4x1 texture 00000040 80404040 FF00FF00 00402000 nearest, fillRect 4 px",
                    true, aux -> {
                        pr.setCompositeRule(SRC_OVER);
                        pr.setTexture(ARGB_PRE, fillTexels, 4, 1, 4, new Transform6(), CLAMP, false, true);
                        pr.fillRect(0, 0, fx(4), fx(1));
                    });
            int[] rowTexels = {0xFF00FF00, 0x00000040, 0x00402000, 0x80404040};
            stepOver(filled(4, 0xFF102030), "4x1 texture FF00FF00 00000040 00402000 80404040 nearest, row x 0..4 at"
                    + " coverage 255", true, aux -> {
                        pr.setCompositeRule(SRC_OVER);
                        pr.setTexture(ARGB_PRE, rowTexels, 4, 1, 4, new Transform6(), CLAMP, false, true);
                        int[] deltas = {255, 0, 0, 0, -255, 0};
                        aux.ints = deltas;
                        pr.emitAndClearAlphaRow(identityAlphaMap(), deltas, 0, 0, 4, 0, 0);
                    });

            rec.begin("texture-alpha-zero-colour-16", 16, 1);
            PiscesRenderer wide = rec.pr;
            int[] texels = filled(16, 0xFF00FF00);
            texels[3] = 0x00402000;
            texels[5] = 0x80404040;
            texels[9] = 0x00000040;
            texels[12] = 0x80404040;
            stepOver(filled(16, 0xFF102030), "16x1 texture FF00FF00 with 00402000 at 3, 00000040 at 9, 80404040 at 5"
                    + " and 12, nearest, fillRect 16 px", true, aux -> {
                        wide.setCompositeRule(SRC_OVER);
                        wide.setTexture(ARGB_PRE, texels, 16, 1, 16, new Transform6(), CLAMP, false, true);
                        wide.fillRect(0, 0, fx(16), fx(1));
                    });
        }

        /**
         * SRC with a transparent source at partial coverage over destination pixels of alpha 0 that carry a colour
         * (not premultiplied): {@code blendSrc8888_pre} and {@code blendSrc8888_pre_pre} write transparent black
         * when {@code 255 * aval + dalpha * raaval} is 0. A colour of alpha 0 through fractional fillRect edges, an
         * alpha mask and an emitAndClearAlphaRow; then texels of alpha 0 with and without a colour, and a gradient
         * of alpha 0.
         */
        private void transparentSrcOverAlphaZero() {
            rec.begin("src-transparent-over-alpha-zero", 4, 1);
            PiscesRenderer pr = rec.pr;
            int[] background = filled(4, 0x00402010);
            byte[] mask = {(byte) 0, (byte) 64, (byte) 128, (byte) 255};
            stepOver(background, "SRC color(250,120,10,0) fillRect x 0.5 w 3", false, aux -> {
                pr.setCompositeRule(SRC);
                pr.setColor(250, 120, 10, 0);
                pr.fillRect(fx(0.5), 0, fx(3), fx(1));
            });
            stepOver(background, "SRC color(250,120,10,0) alpha mask 0 64 128 255", false, aux -> {
                pr.setCompositeRule(SRC);
                pr.setColor(250, 120, 10, 0);
                pr.fillAlphaMask(mask, 0, 0, 4, 1, 0, 4);
            });
            stepOver(background, "SRC color(250,120,10,0) row x 0..4 at coverage 128", false, aux -> {
                pr.setCompositeRule(SRC);
                pr.setColor(250, 120, 10, 0);
                int[] deltas = {128, 0, 0, 0, -128, 0};
                aux.ints = deltas;
                pr.emitAndClearAlphaRow(identityAlphaMap(), deltas, 0, 0, 4, 0, 0);
            });
            int[] texels = {0x00000000, 0x00402000, 0x00000000, 0x00402000};
            String texture = "SRC 4x1 texture 00000000 00402000 00000000 00402000 nearest, ";
            stepOver(background, texture + "fillRect x 0.5 w 3", true, aux -> {
                pr.setCompositeRule(SRC);
                pr.setTexture(ARGB_PRE, texels, 4, 1, 4, new Transform6(), CLAMP, false, true);
                pr.fillRect(fx(0.5), 0, fx(3), fx(1));
            });
            stepOver(background, texture + "alpha mask 0 64 128 255", true, aux -> {
                pr.setCompositeRule(SRC);
                pr.setTexture(ARGB_PRE, texels, 4, 1, 4, new Transform6(), CLAMP, false, true);
                pr.fillAlphaMask(mask, 0, 0, 4, 1, 0, 4);
            });
            stepOver(background, "SRC linear gradient 00FF8040 to 00FF8040, fillRect x 0.5 w 3", true, aux -> {
                pr.setCompositeRule(SRC);
                pr.setLinearGradient(0, 0, fx(4), 0, new int[] {0, 0x10000}, new int[] {0x00FF8040, 0x00FF8040},
                        GradientColorMap.CYCLE_NONE, null);
                pr.fillRect(fx(0.5), 0, fx(3), fx(1));
            });
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * Seeded random sequences
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@value #RANDOM_SEQUENCES} sequences of {@value #RANDOM_OPS} steps from {@link Random} with fixed seeds;
     * every parameter is drawn before the step runs. The generator tracks the renderer state its steps set up - from
     * their parameters, never from their outcomes, so a compositor that fails a step cannot change what comes next -
     * and never emits the steps the class comment lists as crash hazards: a step that needs a paint, a colour or a
     * rule the state does not have sets one first, inside the same step.
     */
    static final class RandomSequences {
        private static final int[][] SIZES = {{17, 13}, {24, 20}, {9, 31}, {32, 8}, {1, 12}, {12, 1}, {40, 24},
            {5, 5}};
        private static final float[] GAMMAS = {1f, 1.4f, 1f / 1.3f, 1f / 2.2f, 2.2f, 0.5f, 3f};

        private final Recorder rec;
        private final List<Texture> textures;
        private Random random;
        private int rule;
        private int paint;

        private static final int PAINT_COLOR = 0;
        private static final int PAINT_GRADIENT = 1;
        private static final int PAINT_TEXTURE = 2;
        private static final int PAINT_NONE = 3;

        RandomSequences(Recorder rec) {
            this.rec = rec;
            this.textures = List.of(OPAQUE_7X5, TRANSLUCENT_7X5,
                    new Texture("1x1", new int[] {0x80402010}, 1, 1, 0, 1, true),
                    new Texture("opaque16x16", opaqueTexels(16, 16), 16, 16, 0, 16, false),
                    new Texture("garbage3x9", garbageTexels(3, 9), 3, 9, 0, 3, true),
                    new Texture("translucent5x4-stride8", embed(translucentTexels(5, 4), 5, 4, 0, 8), 5, 4, 0, 8,
                            true));
        }

        void run() {
            for (int s = 0; s < RANDOM_SEQUENCES; s++) {
                long seed = 0x9E3779B97F4A7C15L * (s + 1);
                random = new Random(seed);
                int[] size = SIZES[s % SIZES.length];
                rec.begin("random-" + s + " seed 0x" + Long.toHexString(seed) + " " + size[0] + "x" + size[1],
                        size[0], size[1]);
                rule = SRC_OVER;
                paint = PAINT_COLOR;
                int bg = random.nextInt(4);
                rec.step("background " + bg, false, bg, aux -> { });
                for (int i = 1; i < RANDOM_OPS; i++) {
                    nextStep();
                }
            }
        }

        private int range(int lo, int hiInclusive) {
            return lo + random.nextInt(hiInclusive - lo + 1);
        }

        /** An S15.16 coordinate in [lo, hi] pixels with a random fraction, or an integer one. */
        private int coord(int lo, int hi) {
            int whole = range(lo, hi);
            return random.nextBoolean() ? whole << 16 : (whole << 16) + random.nextInt(65536);
        }

        private void nextStep() {
            int w = rec.width;
            int h = rec.height;
            PiscesRenderer pr = rec.pr;
            JavaSurface surface = rec.surface;
            int kind = random.nextInt(100);
            if (kind < 7) {
                int r = random.nextInt(256);
                int g = random.nextInt(256);
                int b = random.nextInt(256);
                int a = switch (random.nextInt(3)) {
                    case 0 -> 0;
                    case 1 -> 255;
                    default -> random.nextInt(256);
                };
                rec.step(f("setColor(%d,%d,%d,%d)", r, g, b, a), false, BG_NONE, aux -> pr.setColor(r, g, b, a));
                paint = PAINT_COLOR;
            } else if (kind < 12) {
                int newRule = random.nextInt(20) == 0 ? 3 : random.nextInt(3);
                String outcome = rec.step("setCompositeRule(" + newRule + ")", false, BG_NONE,
                        aux -> pr.setCompositeRule(newRule));
                if (newRule <= SRC_OVER) {
                    rule = newRule;
                }
                rec.expect(outcome.isEmpty() == (newRule <= SRC_OVER), outcome);
            } else if (kind < 17) {
                if (random.nextInt(4) == 0) {
                    rec.step("resetClip", false, BG_NONE, aux -> pr.resetClip());
                } else {
                    int x = range(-3, w + 2);
                    int y = range(-3, h + 2);
                    int cw = range(-2, w + 4);
                    int ch = range(-2, h + 4);
                    rec.step(f("setClip(%d,%d,%d,%d)", x, y, cw, ch), false, BG_NONE, aux -> pr.setClip(x, y, cw, ch));
                }
            } else if (kind < 24) {
                gradient(pr, w, h);
            } else if (kind < 31) {
                texture(pr, w, h);
            } else if (kind < 45) {
                int x = coord(-3, w + 1);
                int y = coord(-3, h + 1);
                int bw = random.nextInt(8) == 0 ? -coord(0, 3) : coord(0, w + 2);
                int bh = coord(0, h + 2);
                stepWithPaint(f("fillRect(%d,%d,%d,%d)", x, y, bw, bh), false, aux -> pr.fillRect(x, y, bw, bh));
            } else if (kind < 50) {
                int x = range(-3, w + 1);
                int y = range(-3, h + 1);
                int cw = range(-1, w + 3);
                int ch = range(-1, h + 3);
                rec.step(f("clearRect(%d,%d,%d,%d)", x, y, cw, ch), false, BG_NONE, aux -> pr.clearRect(x, y, cw, ch));
            } else if (kind < 60) {
                mask(pr, w, h);
            } else if (kind < 66) {
                lcd(pr, w, h);
            } else if (kind < 76) {
                row(pr, w, h);
            } else if (kind < 90) {
                image(pr, w, h);
            } else {
                rgb(surface, w, h);
            }
        }

        /** A step that composites with the current paint: one is set first when there is none. */
        private void stepWithPaint(String op, boolean needsMaskRule, Action action) {
            PiscesRenderer pr = rec.pr;
            boolean noPaint = paint == PAINT_NONE;
            boolean clearRule = needsMaskRule && rule == CLEAR;
            int r = random.nextInt(256);
            int g = random.nextInt(256);
            int b = random.nextInt(256);
            int a = random.nextInt(256);
            int newRule = random.nextBoolean() ? SRC : SRC_OVER;
            String prefix = (noPaint ? f("setColor(%d,%d,%d,%d) + ", r, g, b, a) : "")
                    + (clearRule ? "setCompositeRule(" + newRule + ") + " : "");
            boolean floatOp = !noPaint && paint != PAINT_COLOR;
            String outcome = rec.step(prefix + op, floatOp, BG_NONE, aux -> {
                if (noPaint) {
                    pr.setColor(r, g, b, a);
                }
                if (clearRule) {
                    pr.setCompositeRule(newRule);
                }
                action.run(aux);
            });
            if (noPaint) {
                paint = PAINT_COLOR;
            }
            if (clearRule) {
                rule = newRule;
            }
            rec.expect(outcome.isEmpty() || outcome.startsWith("java.lang.IllegalArgumentException"), outcome);
        }

        private Transform6 transform(int w, int h) {
            double tx = range(-w, 2 * w) + (random.nextBoolean() ? 0 : random.nextInt(65536) / 65536.0);
            double ty = range(-h, 2 * h) + (random.nextBoolean() ? 0 : random.nextInt(65536) / 65536.0);
            double[] scales = {-2.5, -1, -0.5, 0.25, 0.5, 1, 1.5, 2, 3};
            return switch (random.nextInt(7)) {
                case 0 -> t6(1, 0, 0, 1, tx, ty);
                case 1 -> t6(scales[random.nextInt(scales.length)], 0, 0, scales[random.nextInt(scales.length)], tx,
                        ty);
                case 2 -> rot(random.nextInt(360), 0.5 + random.nextInt(6) * 0.5, 0.5 + random.nextInt(6) * 0.5, tx,
                        ty);
                case 3 -> t6(1.5, random.nextInt(9) * 0.25 - 1, random.nextInt(9) * 0.25 - 1, 1.5, tx, ty);
                case 4 -> {
                    double s = scales[random.nextInt(scales.length)];
                    yield random.nextBoolean() ? t6(0, s, s, 0, tx, ty) : t6(0, -s, s, 0, tx, ty);
                }
                case 5 -> new Transform6(range(-3, 3) << 16 | random.nextInt(65536), range(-3, 3) << 16,
                        range(-3, 3) << 16, range(-3, 3) << 16 | random.nextInt(65536), fx(tx), fx(ty));
                default -> t6(2, 0, 0, 2, tx, ty);
            };
        }

        private void gradient(PiscesRenderer pr, int w, int h) {
            int stops = range(1, 5);
            int[] fractions = new int[stops];
            int[] colors = new int[stops];
            for (int i = 0; i < stops; i++) {
                fractions[i] = random.nextInt(0x10001);
                colors[i] = random.nextInt();
            }
            Arrays.sort(fractions);
            int cycle = random.nextInt(3);
            Transform6 t = random.nextInt(3) == 0 ? null : transform(w, h);
            String tName = t == null ? "null" : t.m00 + "," + t.m01 + "," + t.m10 + "," + t.m11 + "," + t.m02 + ","
                    + t.m12;
            String stopText = Arrays.toString(fractions) + Arrays.toString(colors);
            String outcome;
            if (random.nextBoolean()) {
                int x0 = coord(-w / 2, w + w / 2);
                int y0 = coord(-h / 2, h + h / 2);
                int x1 = random.nextInt(10) == 0 ? x0 : coord(-w / 2, w + w / 2);
                int y1 = coord(-h / 2, h + h / 2);
                outcome = rec.step(f("setLinearGradient(%d,%d,%d,%d,%s,cycle %d,tx %s)", x0, y0, x1, y1, stopText,
                        cycle, tName), true, BG_NONE,
                        aux -> pr.setLinearGradient(x0, y0, x1, y1, fractions, colors, cycle, t));
            } else {
                int cx = coord(-w / 2, w + w / 2);
                int cy = coord(-h / 2, h + h / 2);
                int fx0 = random.nextBoolean() ? cx : coord(-w / 2, w + w / 2);
                int fy0 = random.nextBoolean() ? cy : coord(-h / 2, h + h / 2);
                int radius = random.nextInt(10) == 0 ? 0 : coord(0, Math.max(w, h));
                outcome = rec.step(f("setRadialGradient(%d,%d,%d,%d,%d,%s,cycle %d,tx %s)", cx, cy, fx0, fy0, radius,
                        stopText, cycle, tName), true, BG_NONE,
                        aux -> pr.setRadialGradient(cx, cy, fx0, fy0, radius, fractions, colors, cycle, t));
            }
            paint = PAINT_GRADIENT;
            rec.expect(outcome.isEmpty(), outcome);
        }

        private void texture(PiscesRenderer pr, int w, int h) {
            Texture tex = textures.get(random.nextInt(textures.size()));
            Transform6 t = transform(w, h);
            int wrap = random.nextInt(3);
            boolean linear = random.nextBoolean();
            boolean hasAlpha = random.nextInt(8) == 0 ? !tex.hasAlpha() : tex.hasAlpha();
            String outcome = rec.step(f("setTexture(%s,tx %d,%d,%d,%d,%d,%d,%s,%s,alpha %b)", tex.name(), t.m00,
                    t.m01, t.m10, t.m11, t.m02, t.m12, wrapName(wrap), linear ? "bilinear" : "nearest", hasAlpha),
                    true, BG_NONE, aux -> pr.setTexture(ARGB_PRE, tex.data(), tex.width(), tex.height(), tex.stride(),
                    t, wrap, linear, hasAlpha));
            paint = PAINT_TEXTURE;
            rec.expect(outcome.isEmpty(), outcome);
        }

        private void mask(PiscesRenderer pr, int w, int h) {
            int mw = range(0, 9);
            int mh = range(1, 7);
            int stride = mw + range(0, 3);
            int offset = range(0, 5);
            byte[] mask = new byte[offset + stride * (mh - 1) + mw + range(0, 4)];
            for (int i = 0; i < mask.length; i++) {
                mask[i] = random.nextInt(4) == 0 ? (byte) (random.nextBoolean() ? 0 : 255) : (byte) random.nextInt();
            }
            int x = range(-6, w + 3);
            int y = range(-6, h + 3);
            boolean broken = random.nextInt(20) == 0;
            int badStride = broken ? mw - 1 : stride;
            stepWithPaint(f("fillAlphaMask(byte[%d] hash %08x,x %d,y %d,%dx%d,offset %d,stride %d)", mask.length,
                    Arrays.hashCode(mask), x, y, mw, mh, offset, badStride), true,
                    aux -> pr.fillAlphaMask(mask, x, y, mw, mh, offset, badStride));
        }

        private void lcd(PiscesRenderer pr, int w, int h) {
            int pixelsWide = range(0, 6);
            int mw = 3 * pixelsWide + range(0, 2);
            int mh = range(1, 5);
            int offset = range(0, 4);
            byte[] mask = new byte[offset + mw * mh + range(0, 3)];
            for (int i = 0; i < mask.length; i++) {
                mask[i] = random.nextInt(4) == 0 ? (byte) (random.nextBoolean() ? 0 : 255) : (byte) random.nextInt();
            }
            int x = range(-4, w + 2);
            int y = range(-4, h + 2);
            int r = random.nextInt(256);
            int g = random.nextInt(256);
            int b = random.nextInt(256);
            int a = random.nextBoolean() ? 255 : random.nextInt(256);
            float gamma = GAMMAS[random.nextInt(GAMMAS.length)];
            String prefix = (rule != SRC_OVER ? "setCompositeRule(2) + " : "") + f("setColor(%d,%d,%d,%d) + ", r, g,
                    b, a) + "setLCDGammaCorrection(" + gamma + ") + ";
            String outcome = rec.step(prefix + f("fillLCDAlphaMask(byte[%d] hash %08x,x %d,y %d,%dx%d,offset %d)",
                    mask.length, Arrays.hashCode(mask), x, y, mw, mh, offset), true, BG_NONE, aux -> {
                        pr.setCompositeRule(SRC_OVER);
                        pr.setColor(r, g, b, a);
                        pr.setLCDGammaCorrection(gamma);
                        pr.fillLCDAlphaMask(mask, x, y, mw, mh, offset, mw);
                    });
            rule = SRC_OVER;
            paint = PAINT_COLOR;
            rec.expect(outcome.isEmpty(), outcome);
        }

        private void row(PiscesRenderer pr, int w, int h) {
            boolean marlin = random.nextBoolean();
            byte[] map = marlin ? marlinAlphaMap(64) : identityAlphaMap();
            int max = marlin ? 64 : 255;
            int xFrom = range(-3, w + 1);
            int xTo = xFrom + range(-1, w + 2);
            int y = range(-2, h + 1);
            int xOff = range(0, 4);
            int span = Math.max(0, xTo - xFrom + 1);
            int[] deltas = new int[xOff + span + 1 + range(0, 3)];
            int sum = 0;
            for (int i = 0; i < span; i++) {
                int target = random.nextInt(3) == 0 ? sum : random.nextInt(max + 1);
                deltas[xOff + i] = target - sum;
                sum = target;
            }
            int rowNum = random.nextInt(4);
            int[] original = deltas.clone();
            stepWithPaint(f("emitAndClearAlphaRow(%s map,int[%d] hash %08x,y %d,x %d..%d,off %d,row %d)",
                    marlin ? "Marlin" : "identity", original.length, Arrays.hashCode(original), y, xFrom, xTo, xOff,
                    rowNum), false, aux -> {
                        aux.ints = deltas;
                        pr.emitAndClearAlphaRow(map, deltas, y, xFrom, xTo, xOff, rowNum);
                    });
        }

        private void image(PiscesRenderer pr, int w, int h) {
            Texture tex = textures.get(random.nextInt(textures.size()));
            Transform6 t = transform(w, h);
            int txMin = random.nextInt(tex.width());
            int txMax = range(txMin - 1, tex.width() - 1);
            int tyMin = random.nextInt(tex.height());
            int tyMax = range(tyMin - 1, tex.height() - 1);
            int[] box;
            if (random.nextInt(5) == 0) {
                box = new int[] {coord(-3, w), coord(-3, h), coord(0, w + 3), coord(0, h + 3)};
            } else {
                box = bbox(t, txMin, tyMin, txMax + 1, tyMax + 1);
            }
            int[] edges = {random.nextInt(3), random.nextInt(3), random.nextInt(3), random.nextInt(3)};
            int mode = switch (random.nextInt(20)) {
                case 0 -> 0;
                case 1, 2, 3, 4, 5 -> MULTIPLY;
                default -> NORMAL;
            };
            int wrap = random.nextInt(3);
            boolean linear = random.nextBoolean();
            String outcome = rec.step(f("drawImage(mode %d,%s,tx %d,%d,%d,%d,%d,%d,%s,%s,box %d,%d,%d,%d,"
                    + "edges %d%d%d%d,sub %d,%d,%d,%d)", mode, tex.name(), t.m00, t.m01, t.m10, t.m11, t.m02, t.m12,
                    wrapName(wrap), linear ? "bilinear" : "nearest", box[0], box[1], box[2], box[3], edges[0], edges[1],
                    edges[2], edges[3], txMin, tyMin, txMax, tyMax), true, BG_NONE, aux -> pr.drawImage(ARGB_PRE, mode,
                    tex.data(), tex.width(), tex.height(), tex.offset(), tex.stride(), t, wrap, linear, box[0], box[1],
                    box[2], box[3], edges[0], edges[1], edges[2], edges[3], txMin, tyMin, txMax, tyMax,
                    tex.hasAlpha()));
            paint = PAINT_NONE;
            rec.expect(outcome.isEmpty(), outcome);
        }

        private void rgb(JavaSurface surface, int w, int h) {
            int x = random.nextInt(10) == 0 ? range(-1, w) : random.nextInt(w);
            int y = random.nextInt(h);
            int bw = range(0, w - x);
            int bh = range(0, h - y);
            int offset = range(0, 3);
            int scan = bw + range(0, 2);
            int length = offset + scan * Math.max(0, bh - 1) + bw + range(0, 2) - (random.nextInt(10) == 0 ? 1 : 0);
            int[] data = new int[Math.max(0, length)];
            for (int i = 0; i < data.length; i++) {
                data[i] = random.nextInt();
            }
            if (random.nextBoolean()) {
                rec.step(f("setRGB(int[%d] hash %08x,offset %d,scan %d,x %d,y %d,%dx%d)", data.length,
                        Arrays.hashCode(data), offset, scan, x, y, bw, bh), false, BG_NONE,
                        aux -> surface.setRGB(data, offset, scan, x, y, bw, bh));
            } else {
                rec.step(f("getRGB(int[%d],offset %d,scan %d,x %d,y %d,%dx%d)", data.length, offset, scan, x, y, bw,
                        bh), false, BG_NONE, aux -> {
                            aux.ints = data;
                            surface.getRGB(data, offset, scan, x, y, bw, bh);
                        });
            }
        }
    }
}
