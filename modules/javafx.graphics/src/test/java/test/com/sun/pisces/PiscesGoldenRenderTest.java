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
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * Pixel-exact parity harness for the Pisces software compositor ({@code com.sun.pisces}).
 * <p>
 * A fixed script drives {@link PiscesRenderer} and {@link JavaSurface} through every operation the
 * software pipeline ({@code com.sun.prism.sw}) uses, on a 64x64 {@code TYPE_INT_ARGB_PRE} surface, and
 * takes a full-frame snapshot of the surface after every step. The golden resource
 * {@value #GOLDEN_RESOURCE} is the raw big-endian concatenation of those snapshots, captured on Windows x64
 * from the Java compositor of commit {@code 410b8036c1} (PR #32), the commit that landed US-046, running the
 * script of US-061. A capture of the same script from the C of commit {@code 42a5072596} (PR #29, the commit
 * that landed US-059: nearest-neighbour texture sampling at the pixel centre, {@code renderer_setTexture} in
 * {@code native-prism-sw/PiscesRenderer.inl}), with {@code prism_sw} built from commit {@code 7d5ffd66f0}, whose
 * C is the same, reproduces it byte for byte on Windows x64: every frame, 16 to 25 included, is the C's. The
 * name is a placeholder: the file is renamed to the hash of the commit that lands US-061, as
 * {@code pisces-golden-US-059-pending.bin} became {@code pisces-golden-42a5072596.bin} (US-046).
 * <p>
 * It replaced {@code pisces-golden-42a5072596.bin}, captured from the C of commit {@code 42a5072596}. Its four
 * {@code drawImage} steps, 16 to 19, passed one device-to-texture transform, u = 16 / bw * (x - bx) plus a shear,
 * but {@code PiscesRenderer.drawImage} takes the texture-to-device transform and inverts it, so every pixel of the
 * boxes sampled far outside the 16-texel texture (u of about 52 to 89 in step 16): steps 16, 17 and 19 painted no
 * pixel, and the REPEAT step 18 painted 454, tiled from far outside the image. US-061 gives each step its own
 * texture-to-device transform ({@link ImageStep#transform()}), built the way {@code SWGraphics.drawTexture} builds
 * the one it hands {@code drawImage}: scale = box size / source size, translation = box origin, plus the shear
 * {@link #SHEAR_X} and {@link #SHEAR_Y} (0.05 and -0.03 pixel per texel), which keeps every step on the
 * {@code GENERIC} branch of the texture paint. Steps 16, 17 and 19 map their sub-rectangle onto their box (step 19
 * its 12x12 sub-rectangle at offset 34, stride 16), so the drawn quad nearly coincides with the box. Step 18 maps
 * the texture onto a tile half as wide and half as high as its box, centred in it, so its box samples u and v
 * below 0 and at or above 16 on every side, and REPEAT wraps them. That moved steps 16 to 19: they now change 488,
 * 525, 457 and 500 pixels against the step before (0, 0, 454 and 0 before) and differ from the old golden in 488,
 * 806, 1011 and 1019 pixels. Steps 20 to 25 carry those pixels, and 20 and 21 paint over some of them, which
 * leaves 1017 differing pixels in each; frames 0 to 15 did not move.
 * {@link #theDrawImageStepsPaintTheirBoxFromInsideTheTexture} pins the new geometry.
 * <p>
 * That file replaced {@code pisces-golden-820a4c35d7.bin}, captured from commit {@code 820a4c35d7} (PR #23), the
 * commit that contains the US-013 fix of {@code native-prism-sw/PiscesPaint.c}. The US-059 fix moved two
 * steps, the two nearest-neighbour {@code setTexture} paints, which now take the texel under the pixel centre
 * instead of the one under its top-left corner: step 12 (clamp to edge, inverse scale 2) in the 295 pixels of
 * the fill whose column or row reaches into the texture (x or y at most 6), and step 14 (REPEAT under a
 * slightly sheared scale) in 502 of its 812 pixels. Steps 13 and 15 to 25 carry those pixels, and 15, 18, 20
 * and 23 paint over some of them. No other step moved: the bilinear arithmetic is unchanged, and the band
 * that keeps the corners of a rotated {@code drawImage} clear changed nothing there, because steps 16, 17 and 19
 * painted no pixel in either of those goldens: their transform mapped every pixel of the box outside the texture,
 * which US-061 corrected.
 * <p>
 * That file replaced {@code pisces-golden-a544256444.bin}, captured from the <b>JNI build</b> at commit
 * {@code a544256444}, which the FFM build reproduced byte for byte. The US-013 fix moved 22 of its pixels, at
 * x = 2, y = 40 to 61: step 14 (the REPEAT, nearest-neighbour {@code setTexture} paint) fills that column
 * from pixel corners left of the tile, which sample tile texel -1. Nearest-neighbour REPEAT now wraps it to
 * the last texel of the 12-texel tile, as it already wrapped every texel further out, where it read texel 0
 * before; steps 15 to 25 carry those pixels unchanged. No other pixel and no other step moved: the
 * linear-filtering edge fix of US-013 changes none of the script's texture paints.
 * <p>
 * The Java compositor that replaced the C (US-046) has to reproduce this file byte for byte on every
 * platform, float steps included. Most of the script is integer arithmetic:
 * blits, masks, fill and clear, get and set RGB. Four groups of steps are not. Steps 6 to 11 (the linear and
 * radial gradients) and 24 (alpha rows under a gradient paint) run the float set-up of
 * {@code PiscesRenderer.inl} and the per-pixel {@code sqrt} and {@code frac += mx} of {@code PiscesPaint.c};
 * steps 12 to 19 (the four {@code setTexture} paints and the four {@code drawImage} calls) invert their texture
 * transform in {@code pisces_transform_invert} ({@code native-prism-sw/PiscesTransform.c:55} and {@code :61-62}
 * at {@code 42a5072596}: {@code fdet} and the two translation terms are float products and differences, divided
 * by {@code fdet} and truncated back to 16.16, so one ulp there moves the sampling grid); step 22 (the LCD mask
 * at gamma 1.4) reads lookup tables built with {@code pow} ({@code native-prism-sw/PiscesBlit.c:1028-1029} at
 * {@code 42a5072596}). The Java compositor runs all of them in the C's order and widths, without FMA
 * contraction, with a {@link StrictMath}-based {@code pow} and with the float-to-int conversion of the x86-64
 * build, so none of its results depends on the platform it runs on. A difference is therefore a bug in the
 * Java compositor and is fixed there, never by regenerating the golden; regenerating it is a behaviour change
 * and needs its own commit with the reason stated. The comparison runs every step and reports every differing
 * step in one failure, in step order.
 * <p>
 * The JNI glue that produced the first golden ({@code native-prism-sw/JPiscesRenderer.c} and its siblings) was
 * deleted in commit {@code 45f18c168a}, and the C compositor itself by US-046. To check this corpus against the
 * C, capture it on a commit that still has the C of {@code 42a5072596}, such as {@code 7d5ffd66f0}. The script
 * there predates US-061, so carry this one over first: the four {@code drawImage} steps of {@code Script.run},
 * {@code Script.drawImage}, and the definitions they read ({@link #SHEAR_X}, {@link #SHEAR_Y},
 * {@link #IMAGE_STEPS}, {@link Box}, {@link ImageStep}); the rest of the script is the same there. A capture
 * from the Java compositor proves only that the build agrees with itself.
 * The per-step comparisons are counted, and a run that compared none of them fails at the end of the class
 * ({@link ParityGate}); a capture run compares nothing by design and is reported as skipped.
 * <p>
 * Capture mode: with {@code -Dpisces.golden.capture=<absolute path>} the test writes the file to that
 * path instead of asserting against the resource, then aborts, so a capture run is reported as skipped
 * and can never be read as a green verification. The per-step layout exists so that a mismatch names
 * the operation that broke rather than only the pixel.
 */
public class PiscesGoldenRenderTest {

    static final String GOLDEN_RESOURCE = "pisces-golden-US-061-pending.bin";
    static final String CAPTURE_PROPERTY = "pisces.golden.capture";

    private static final ParityGate.Ledger LEDGER = ParityGate.ledger(PiscesGoldenRenderTest.class);

    static final int W = 64;
    static final int H = 64;

    /** Coverage maximum of the synthetic alpha rows; independent of Marlin's configured subpixel grid. */
    static final int MAX_ALPHA = 64;

    /** The golden runs on every machine: once the comparison started, every step must have been compared. */
    @AfterAll
    static void theOracleRan() {
        LEDGER.assertOracleRan();
    }

    @Test
    public void rendersExactlyAsTheGoldenBuildDid() throws IOException {
        Script script = new Script();
        script.run();
        String capture = System.getProperty(CAPTURE_PROPERTY);
        if (capture != null && !capture.isBlank()) {
            script.writeGolden(Path.of(capture));
            Assumptions.abort("golden captured to " + capture + "; a capture run verifies nothing");
        }
        LEDGER.oracleAvailable();
        script.assertMatchesGolden();
    }

    /**
     * The report has to name every step that differs, in step order, with where it first differs and how many
     * of its pixels do, rather than stop at the first one. The script's own frames stand in for the golden; in a
     * copy, one pixel each of steps 13 (a texture paint), 0 and 7 (a gradient) is perturbed, in that order, and
     * the comparison must list all three in step order. The golden resource is not involved.
     */
    @Test
    public void everyDifferingStepIsReportedInStepOrder() {
        Script script = new Script();
        script.run();
        List<int[]> golden = script.steps.stream().map(step -> step.snapshot().clone()).toList();
        List<Step> perturbed = new ArrayList<>(script.steps);
        perturb(perturbed, 13, 2049);
        perturb(perturbed, 0, 4095);
        perturb(perturbed, 7, 100);
        assertEquals(List.of(), compare(script.steps, golden), "unperturbed frames compare clean");

        List<Mismatch> mismatches = compare(perturbed, golden);
        assertEquals(List.of(0, 7, 13), mismatches.stream().map(Mismatch::step).toList(),
                "every differing step, in step order");
        Mismatch first = mismatches.get(0);
        assertEquals(4095, first.firstPixel());
        assertEquals(1, first.differing());
        assertEquals(golden.get(0)[4095], first.expected());
        assertEquals(perturbed.get(0).snapshot()[4095], first.actual());

        String message = describe(mismatches, perturbed.size());
        assertTrue(message.startsWith("3 of " + perturbed.size() + " steps differ from the golden"), message);
        assertTrue(message.contains("step 0 '" + perturbed.get(0).name() + "': pixel (63,63)"), message);
        assertTrue(message.contains("step 7 '" + perturbed.get(7).name() + "': pixel (36,1)"), message);
        assertTrue(message.contains("step 13 '" + perturbed.get(13).name() + "': pixel (1,32)"), message);
        assertTrue(message.indexOf("step 0 '") < message.indexOf("step 7 '")
                && message.indexOf("step 7 '") < message.indexOf("step 13 '"), "steps are listed in step order");
    }

    /** Replaces step {@code index} with a copy whose pixel {@code pixel} has one colour bit flipped. */
    private static void perturb(List<Step> steps, int index, int pixel) {
        int[] snapshot = steps.get(index).snapshot().clone();
        snapshot[pixel] ^= 0x00010000;
        steps.set(index, new Step(steps.get(index).name(), snapshot));
    }

    /**
     * The four {@code drawImage} steps (16 to 19) draw their texture into their box (US-061). Their geometry is
     * {@link #IMAGE_STEPS}, which the script draws; the oracle is geometry in doubles and a replay of each step,
     * not the compositor's fixed-point arithmetic.
     * <ul>
     * <li>Each step changes at least one pixel inside its box against the frame before.</li>
     * <li>The inverse of each transform keeps a shear of at least 1/65536, so the paint takes its {@code GENERIC}
     * branch.</li>
     * <li>Steps 16, 17 and 19 map their sub-rectangle onto their box: every corner of the drawn quad lies within
     * one pixel of its box corner on both axes. The shear moves a corner by at most 0.8 pixel, one texel spans at
     * least 1.29 pixels.</li>
     * <li>In steps 16, 17 and 19 every pixel clear of the box's fractional edges whose centre lies at least half a
     * texel inside the quad (mapped through the inverse of the step's transform) is sampled from inside the
     * texture. Replayed with the step's geometry, edges, wrap mode, filter and image mode, but with opaque texels
     * under an opaque white paint and on a transparent surface, it is painted: the paint leaves a pixel it samples
     * further than half a texel outside the sub-rectangle transparent. Half a texel inside the quad, a bilinear
     * footprint lies inside the texture as well.</li>
     * <li>The REPEAT step 18 maps the texture onto a tile smaller than its box, so its box samples u and v below 0
     * and at or above the texture size, and they wrap.</li>
     * </ul>
     */
    @Test
    public void theDrawImageStepsPaintTheirBoxFromInsideTheTexture() {
        Script script = new Script();
        script.run();
        List<String> failures = new ArrayList<>();
        for (ImageStep image : IMAGE_STEPS) {
            String label = "step " + image.step() + " '" + script.steps.get(image.step()).name() + "'";
            Box box = image.box();
            int changed = changedInBox(script.steps.get(image.step() - 1).snapshot(),
                    script.steps.get(image.step()).snapshot(), box);
            if (changed == 0) {
                failures.add(label + " changes no pixel inside its box");
            }
            System.out.printf("%s: %d pixels of the box changed%n", label, changed);
            double[] inverse = inverse(image.matrix());
            if (Math.max(Math.abs(inverse[1]), Math.abs(inverse[2])) * 65536 < 1) {
                failures.add(label + ": the inverse of its transform keeps no shear in 16.16, so the paint does"
                        + " not take its GENERIC branch");
            }
            if (image.repeat()) {
                failures.addAll(wrapFailures(image, inverse, label));
            } else {
                failures.addAll(cornerFailures(image, label));
                failures.addAll(replayFailures(image, inverse, label));
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " drawImage checks failed:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), failures));
        }
    }

    /** A REPEAT step: the pixel centres of its box sample u and v below 0 and at or above the texture size. */
    private static List<String> wrapFailures(ImageStep image, double[] inverse, String label) {
        double[] range = textureRange(inverse, image.box());
        System.out.printf("%s: its pixel centres sample u %.2f to %.2f and v %.2f to %.2f%n", label,
                range[0], range[1], range[2], range[3]);
        if (range[0] < 0 && range[1] >= image.width() && range[2] < 0 && range[3] >= image.height()) {
            return List.of();
        }
        return List.of(String.format("%s: its box samples u %.2f to %.2f and v %.2f to %.2f, which do not leave the"
                + " %dx%d texture on both sides", label, range[0], range[1], range[2], range[3], image.width(),
                image.height()));
    }

    /** Every corner of the drawn quad lies within one pixel of its box corner, on both axes. */
    private static List<String> cornerFailures(ImageStep image, String label) {
        double[] m = image.matrix();
        Box box = image.box();
        List<String> failures = new ArrayList<>();
        for (int[] corner : new int[][] {{0, 0}, {image.width(), 0}, {0, image.height()},
            {image.width(), image.height()}}) {
            double x = m[0] * corner[0] + m[1] * corner[1] + m[4];
            double y = m[2] * corner[0] + m[3] * corner[1] + m[5];
            double boxX = box.x() + (double) box.w() * corner[0] / image.width();
            double boxY = box.y() + (double) box.h() * corner[1] / image.height();
            if (Math.max(Math.abs(x - boxX), Math.abs(y - boxY)) > 1) {
                failures.add(String.format("%s: texture corner (%d,%d) maps to (%.3f,%.3f), more than one pixel from"
                        + " its box corner (%.3f,%.3f)", label, corner[0], corner[1], x, y, boxX, boxY));
            }
        }
        return failures;
    }

    /**
     * Every pixel clear of the box's fractional edges whose centre lies at least half a texel inside the quad is
     * painted when the step is replayed with opaque texels.
     */
    private static List<String> replayFailures(ImageStep image, double[] inverse, String label) {
        int[] replay = replayWithOpaqueTexels(image);
        Box box = image.box();
        int inside = 0;
        List<String> blank = new ArrayList<>();
        for (int y = Math.max(0, (int) Math.ceil(box.y())); y < H && y + 1 <= box.y() + box.h(); y++) {
            for (int x = Math.max(0, (int) Math.ceil(box.x())); x < W && x + 1 <= box.x() + box.w(); x++) {
                double u = inverse[0] * (x + 0.5) + inverse[1] * (y + 0.5) + inverse[4];
                double v = inverse[2] * (x + 0.5) + inverse[3] * (y + 0.5) + inverse[5];
                if (u < 0.5 || u > image.width() - 0.5 || v < 0.5 || v > image.height() - 0.5) {
                    continue;
                }
                inside++;
                if ((replay[y * W + x] >>> 24) == 0) {
                    blank.add(String.format("(%d,%d) at (u,v)=(%.3f,%.3f)", x, y, u, v));
                }
            }
        }
        System.out.printf("%s: %d pixels lie half a texel inside the quad and clear of the fractional box edges,"
                + " %d of them painted by the opaque replay%n", label, inside, inside - blank.size());
        if (inside == 0) {
            return List.of(label + ": no pixel clear of the fractional box edges lies half a texel inside the quad");
        }
        if (blank.isEmpty()) {
            return List.of();
        }
        return List.of(String.format("%s: %d of the %d pixels half a texel inside the quad are transparent in the"
                + " opaque replay, so the paint sampled them outside the texture; first: %s", label, blank.size(),
                inside, blank.subList(0, Math.min(4, blank.size()))));
    }

    /** The pixels touching {@code box} that differ between two frames. */
    private static int changedInBox(int[] before, int[] after, Box box) {
        int changed = 0;
        for (int y = Math.max(0, (int) Math.floor(box.y())); y < H && y < box.y() + box.h(); y++) {
            for (int x = Math.max(0, (int) Math.floor(box.x())); x < W && x < box.x() + box.w(); x++) {
                if (before[y * W + x] != after[y * W + x]) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** The inverse of the affine map {@code m} (m00, m01, m10, m11, m02, m12), in the same order. */
    private static double[] inverse(double[] m) {
        double det = m[0] * m[3] - m[1] * m[2];
        return new double[] {m[3] / det, -m[1] / det, -m[2] / det, m[0] / det,
            (m[1] * m[5] - m[4] * m[3]) / det, (m[4] * m[2] - m[0] * m[5]) / det};
    }

    /** The texture coordinates the pixel centres inside {@code box} map to: min u, max u, min v, max v. */
    private static double[] textureRange(double[] inverse, Box box) {
        double[] range = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double cx = x + 0.5;
                double cy = y + 0.5;
                if (cx < box.x() || cx >= box.x() + box.w() || cy < box.y() || cy >= box.y() + box.h()) {
                    continue;
                }
                double u = inverse[0] * cx + inverse[1] * cy + inverse[4];
                double v = inverse[2] * cx + inverse[3] * cy + inverse[5];
                range[0] = Math.min(range[0], u);
                range[1] = Math.max(range[1], u);
                range[2] = Math.min(range[2], v);
                range[3] = Math.max(range[3], v);
            }
        }
        return range;
    }

    /**
     * {@code image} drawn alone, with every texel opaque white under an opaque white paint (which
     * {@code IMAGE_MODE_MULTIPLY} multiplies by), {@code SRC_OVER} on a transparent surface.
     */
    private static int[] replayWithOpaqueTexels(ImageStep image) {
        int[] pixels = new int[W * H];
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, W, H));
        pr.setCompositeRule(RendererBase.COMPOSITE_SRC_OVER);
        pr.setColor(255, 255, 255, 255);
        int[] texels = new int[image.offset() + (image.height() - 1) * image.stride() + image.width()];
        Arrays.fill(texels, 0xFFFFFFFF);
        image.draw(pr, texels);
        return pixels;
    }

    @Test
    public void getRGBAndSetRGBRoundTrip() {
        int[] pixels = new int[W * H];
        JavaSurface surface = new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, W, H);

        int[] block = new int[3 + 6 * 4];
        for (int i = 0; i < block.length; i++) {
            block[i] = 0xFF000000 | (i * 0x010203);
        }
        surface.setRGB(block, 3, 6, 10, 20, 4, 4);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                assertEquals(block[3 + row * 6 + col], pixels[(20 + row) * W + 10 + col],
                        "pixel (" + (10 + col) + "," + (20 + row) + ")");
            }
        }

        int[] back = new int[5 + 7 * 4];
        surface.getRGB(back, 5, 7, 10, 20, 4, 4);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                assertEquals(pixels[(20 + row) * W + 10 + col], back[5 + row * 7 + col],
                        "read-back (" + col + "," + row + ")");
            }
        }
        int[] all = new int[W * H];
        surface.getRGB(all, 0, W, 0, 0, W, H);
        assertArrayEquals(pixels, all);
    }

    /**
     * A range the Java-side {@code AbstractSurface.rgbCheck} rejects. The one check only the compositor makes
     * ({@code offset + height * scanLength} elements, {@code native-prism-sw/prism_sw_api.c:460-462} at
     * {@code 42a5072596}) is pinned by {@link PiscesCorpusGoldenTest}; the JNI build could not run it, because its
     * {@code JNI_ThrowNew} treated the exception it had just raised as a failure and aborted the JVM
     * ({@code FatalError("Failed to throw an exception!")}).
     */
    @Test
    public void getRGBOutsideSurfaceThrowsTheJavaMessage() {
        JavaSurface surface = new JavaSurface(new int[W * H], RendererBase.TYPE_INT_ARGB_PRE, W, H);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> surface.getRGB(new int[W * H], 0, W, 60, 0, 8, 1));
        assertEquals("X+WIDTH is out of surface", e.getMessage());
    }

    @Test
    public void emitAndClearAlphaRowRejectsRangesBeyondTheDeltas() {
        int[] pixels = new int[W * H];
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, W, H));
        byte[] alphaMap = alphaMap();
        int[] deltas = new int[16];
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> pr.emitAndClearAlphaRow(alphaMap, deltas, 3, 0, 20, 0));
        assertEquals("rendering range exceeds length of data", e.getMessage());
    }

    @Test
    public void emitAndClearAlphaRowZeroesTheDeltasInPlace() {
        int[] pixels = new int[W * H];
        PiscesRenderer pr = new PiscesRenderer(new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, W, H));
        pr.setClip(0, 0, W, H);
        pr.setColor(10, 20, 30, 255);
        int[] deltas = new int[80];
        int width = fillRampDeltas(deltas, 4);
        pr.emitAndClearAlphaRow(alphaMap(), deltas, 7, 3, 3 + width - 1, 4, 0);
        assertArrayEquals(new int[80], deltas, "the live delta array must be zeroed by the call");
        assertTrue(pixels[7 * W + 3] != 0 || pixels[7 * W + 10] != 0, "the row was rendered");
    }

    static int toS(float v) {
        return (int) (v * 65536f);
    }

    /** Marlin's {@code setMaxAlpha} mapping for {@link #MAX_ALPHA}. */
    static byte[] alphaMap() {
        byte[] map = new byte[MAX_ALPHA + 1];
        for (int i = 0; i <= MAX_ALPHA; i++) {
            map[i] = (byte) ((i * 255 + MAX_ALPHA / 2) / MAX_ALPHA);
        }
        return map;
    }

    /**
     * Writes a coverage ramp as relative deltas at {@code deltas[off..off+16)}: eight steps up to
     * {@link #MAX_ALPHA}, a plateau, four steps back to zero. Returns the number of pixels covered.
     */
    static int fillRampDeltas(int[] deltas, int off) {
        for (int i = 0; i < 8; i++) {
            deltas[off + i] = MAX_ALPHA / 8;
        }
        for (int i = 12; i < 16; i++) {
            deltas[off + i] = -(MAX_ALPHA / 4);
        }
        return 16;
    }

    static int[] opaqueTexture() {
        int[] tex = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                tex[y * 16 + x] = 0xFF000000 | ((x * 17) << 16) | ((y * 17) << 8) | ((x ^ y) * 17);
            }
        }
        return tex;
    }

    /** Premultiplied: every colour channel is at most the alpha channel. */
    static int[] translucentTexture() {
        int[] tex = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int a = x * 17;
                int r = (a * y) / 15;
                int g = a >> 1;
                int b = (a * (15 - y)) / 15;
                tex[y * 16 + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return tex;
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

    /** The shear of every {@code drawImage} transform, in pixels per texel: x per texel of v, y per texel of u. */
    static final float SHEAR_X = 0.05f;
    static final float SHEAR_Y = -0.03f;

    /**
     * Steps 16 to 19, the four {@code drawImage} calls, in script order. The script draws them and
     * {@link #theDrawImageStepsPaintTheirBoxFromInsideTheTexture} replays and measures them, so both take their
     * geometry from here.
     */
    static final List<ImageStep> IMAGE_STEPS = imageSteps();

    private static List<ImageStep> imageSteps() {
        float bx = 20.5f;
        float by = 20.25f;
        float bw = 24.5f;
        float bh = 20.75f;
        int keep = RendererBase.IMAGE_FRAC_EDGE_KEEP;
        int pad = RendererBase.IMAGE_FRAC_EDGE_PAD;
        int trim = RendererBase.IMAGE_FRAC_EDGE_TRIM;
        Box box16 = new Box(bx, by, bw, bh);
        Box box17 = new Box(bx + 0.5f, by + 12f, bw, bh);
        Box box18 = new Box(bx - 10.25f, by + 6.5f, bw, bh);
        // steps 16, 17 and 19 map the texture onto their box; the REPEAT step 18 onto the middle of its box, half
        // as wide and half as high, so that it wraps on every side
        return List.of(
                new ImageStep(16, RendererBase.IMAGE_MODE_NORMAL, 16, 16, 0, 16, false, true,
                        box16, box16, keep, keep, keep, keep),
                new ImageStep(17, RendererBase.IMAGE_MODE_NORMAL, 16, 16, 0, 16, false, false,
                        box17, box17, pad, pad, pad, pad),
                new ImageStep(18, RendererBase.IMAGE_MODE_NORMAL, 16, 16, 0, 16, true, true,
                        box18, box18.centredHalf(), trim, trim, trim, trim),
                new ImageStep(19, RendererBase.IMAGE_MODE_MULTIPLY, 12, 12, 34, 16, false, true,
                        box16, box16, keep, pad, trim, keep));
    }

    /** An axis-aligned rectangle in surface coordinates. */
    record Box(float x, float y, float w, float h) {

        /** The rectangle half as wide and half as high, with the same centre. */
        Box centredHalf() {
            return new Box(x + w / 4, y + h / 4, w / 2, h / 2);
        }
    }

    /**
     * One {@code drawImage} step: the frame it produces, its image mode, its {@code width x height} texels at
     * {@code offset} with {@code stride} (the sub-rectangle {@code 0..width - 1 x 0..height - 1} is all of them),
     * its wrap mode and filter, the box it fills with its four edge rules, and the rectangle its texture is mapped
     * onto: the box itself, or for the REPEAT step a tile inside it.
     */
    record ImageStep(int step, int mode, int width, int height, int offset, int stride, boolean repeat,
            boolean linear, Box box, Box tile, int lEdge, int rEdge, int tEdge, int bEdge) {

        /**
         * The texture-to-device transform that {@code PiscesRenderer.drawImage} takes and inverts. It maps the
         * sub-rectangle onto {@link #tile()} the way {@code SWGraphics.drawTexture} builds the transform it hands
         * {@code drawImage} ({@code SWPaint.computeDrawTexturePaintTransform}: scale = destination size / source
         * size, translation = destination origin), plus the shear {@link PiscesGoldenRenderTest#SHEAR_X} and
         * {@link PiscesGoldenRenderTest#SHEAR_Y}, which the inverse keeps, so that the paint takes its
         * {@code GENERIC} branch.
         */
        Transform6 transform() {
            return new Transform6(toS(tile.w() / width), toS(SHEAR_X), toS(SHEAR_Y), toS(tile.h() / height),
                    toS(tile.x()), toS(tile.y()));
        }

        /** {@link #transform()} as doubles, in the order of {@link Transform6}: m00, m01, m10, m11, m02, m12. */
        double[] matrix() {
            Transform6 t = transform();
            return new double[] {t.m00 / 65536.0, t.m01 / 65536.0, t.m10 / 65536.0, t.m11 / 65536.0,
                t.m02 / 65536.0, t.m12 / 65536.0};
        }

        void draw(PiscesRenderer pr, int[] data) {
            pr.drawImage(RendererBase.TYPE_INT_ARGB_PRE, mode, data, width, height, offset, stride, transform(),
                    repeat ? RendererBase.WRAP_REPEAT : RendererBase.WRAP_CLAMP_TO_EDGE, linear,
                    toS(box.x()), toS(box.y()), toS(box.w()), toS(box.h()), lEdge, rEdge, tEdge, bEdge,
                    0, 0, width - 1, height - 1, true);
        }
    }

    private record Step(String name, int[] snapshot) {
    }

    /** One step whose snapshot differs from its golden frame: where it first differs, and by how much. */
    record Mismatch(int step, String name, int firstPixel, int expected, int actual, int differing) {

        String describe() {
            return String.format("step %d '%s': pixel (%d,%d) expected 0x%08X but was 0x%08X"
                    + " (first of %d differing pixels)", step, name,
                    firstPixel % W, firstPixel / W, expected, actual, differing);
        }
    }

    /**
     * Compares every snapshot with its golden frame and reports every step that differs, in step order,
     * so that one run names every broken operation, not only the first.
     */
    static List<Mismatch> compare(List<Step> steps, List<int[]> golden) {
        List<Mismatch> mismatches = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            int[] expected = golden.get(i);
            int[] actual = step.snapshot();
            int first = -1;
            int differing = 0;
            for (int p = 0; p < expected.length; p++) {
                if (expected[p] != actual[p]) {
                    if (first < 0) {
                        first = p;
                    }
                    differing++;
                }
            }
            if (first >= 0) {
                mismatches.add(new Mismatch(i, step.name(), first, expected[first], actual[first], differing));
            }
        }
        return mismatches;
    }

    /** The one failure message: the verdict, then every mismatch in step order. */
    static String describe(List<Mismatch> mismatches, int stepCount) {
        StringBuilder message = new StringBuilder();
        message.append(mismatches.size()).append(" of ").append(stepCount).append(" steps differ from the golden,")
                .append(" which is a bug in the Java compositor on every platform");
        for (Mismatch mismatch : mismatches) {
            message.append(System.lineSeparator()).append("  ").append(mismatch.describe());
        }
        return message.toString();
    }

    /** {@code count} big-endian frames of {@code W * H} ints, as the golden stores them. */
    static List<int[]> frames(byte[] bytes, int count) {
        IntBuffer ints = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer();
        List<int[]> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int[] frame = new int[W * H];
            ints.get(frame);
            frames.add(frame);
        }
        return frames;
    }

    /** The deterministic sequence of operations and the snapshot taken after each. */
    private static final class Script {

        private final int[] pixels = new int[W * H];
        private final JavaSurface surface = new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, W, H);
        private final PiscesRenderer pr = new PiscesRenderer(surface);
        private final List<Step> steps = new ArrayList<>();

        private void snapshot(String name) {
            steps.add(new Step(name, pixels.clone()));
        }

        void run() {
            int[] fractions = {0x0000, 0x8000, 0x10000};
            int[] rgba = {0xFFFF0000, 0x8000FF00, 0xFF0000FF};
            Transform6 gradientTx = new Transform6(toS(0.8f), toS(0.2f), toS(-0.3f), toS(1.1f), toS(2f), toS(-1f));
            int[] opaque = opaqueTexture();
            int[] translucent = translucentTexture();

            int[] background = new int[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    background[y * W + x] = 0xFF000000 | ((x * 4) << 16) | ((y * 4) << 8) | ((x ^ y) * 4);
                }
            }
            surface.setRGB(background, 0, W, 0, 0, W, H);
            pr.setClip(0, 0, W, H);
            pr.clearRect(8, 8, 48, 48);
            snapshot("setRGB background + clearRect");

            pr.setClip(10, 10, 20, 20);
            pr.setCompositeRule(RendererBase.COMPOSITE_SRC_OVER);
            pr.setColor(20, 220, 20, 255);
            pr.fillRect(0, 0, toS(W), toS(H));
            pr.resetClip();
            snapshot("setClip + fillRect + resetClip");

            pr.setColor(200, 30, 60, 180);
            pr.fillRect(toS(10.25f), toS(10.5f), toS(20.5f), toS(15.75f));
            snapshot("fillRect SRC_OVER fractional edges");

            pr.setCompositeRule(RendererBase.COMPOSITE_SRC);
            pr.setColor(0, 128, 255, 128);
            pr.fillRect(toS(4.5f), toS(30.25f), toS(25.25f), toS(10.5f));
            snapshot("fillRect SRC translucent");

            pr.setCompositeRule(RendererBase.COMPOSITE_CLEAR);
            pr.fillRect(toS(12f), toS(12f), toS(6.5f), toS(6.5f));
            snapshot("fillRect CLEAR");

            pr.setCompositeRule(RendererBase.COMPOSITE_SRC_OVER);
            pr.setColor(255, 255, 0);
            pr.fillRect(toS(40.75f), toS(2.25f), toS(20f), toS(20f));
            snapshot("fillRect SRC_OVER opaque, clipped by the surface");

            pr.setLinearGradient(toS(5f), toS(5f), toS(40f), toS(30f), fractions, rgba,
                    GradientColorMap.CYCLE_NONE, gradientTx);
            pr.fillRect(toS(2f), toS(2f), toS(30f), toS(28f));
            snapshot("linear gradient CYCLE_NONE");

            pr.setLinearGradient(toS(10f), toS(10f), toS(20f), toS(15f), fractions, rgba,
                    GradientColorMap.CYCLE_REPEAT, gradientTx);
            pr.fillRect(toS(32f), toS(2f), toS(30f), toS(28f));
            snapshot("linear gradient CYCLE_REPEAT");

            pr.setLinearGradient(toS(4f), toS(36f), 0xFF00FFFF, toS(14f), toS(46f), 0x40FF0080,
                    GradientColorMap.CYCLE_REFLECT);
            pr.fillRect(toS(2f), toS(32f), toS(30f), toS(30f));
            snapshot("linear gradient CYCLE_REFLECT (two-colour overload, identity)");

            Transform6 radialTx = new Transform6(toS(1.2f), toS(-0.1f), toS(0.15f), toS(0.9f), toS(-3f), toS(4f));
            pr.setRadialGradient(toS(48f), toS(48f), toS(44f), toS(50f), toS(12f), fractions, rgba,
                    GradientColorMap.CYCLE_NONE, radialTx);
            pr.fillRect(toS(34f), toS(34f), toS(28f), toS(28f));
            snapshot("radial gradient CYCLE_NONE");

            pr.setRadialGradient(toS(48f), toS(48f), toS(46f), toS(47f), toS(5f), fractions, rgba,
                    GradientColorMap.CYCLE_REPEAT, radialTx);
            pr.fillRect(toS(34f), toS(34f), toS(28f), toS(28f));
            snapshot("radial gradient CYCLE_REPEAT");

            pr.setRadialGradient(toS(48f), toS(48f), toS(48f), toS(48f), toS(6f), fractions, rgba,
                    GradientColorMap.CYCLE_REFLECT, null);
            pr.fillRect(toS(34.5f), toS(34.5f), toS(27f), toS(27f));
            snapshot("radial gradient CYCLE_REFLECT (null transform)");

            int edge = RendererBase.WRAP_CLAMP_TO_EDGE;
            int repeat = RendererBase.WRAP_REPEAT;
            Transform6 fitTx = new Transform6(toS(0.5f), 0, 0, toS(0.5f), toS(-1f), toS(-1f));
            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, opaque, 16, 16, 16, fitTx, edge, false, false);
            pr.fillRect(toS(2f), toS(2f), toS(32f), toS(32f));
            snapshot("setTexture repeat=false linear=false alpha=false");

            Transform6 tileTx = new Transform6(toS(1.3f), toS(0.05f), toS(-0.05f), toS(1.3f), toS(0.5f), toS(0.25f));
            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, translucent, 16, 16, 16, tileTx, repeat, true, true);
            pr.fillRect(toS(30f), toS(30f), toS(34f), toS(34f));
            snapshot("setTexture repeat=true linear=true alpha=true");

            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, opaque, 12, 12, 16, tileTx, repeat, false, false);
            pr.fillRect(toS(2.5f), toS(34f), toS(28f), toS(28f));
            snapshot("setTexture repeat=true linear=false alpha=false, stride > width");

            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, translucent, 16, 16, 16, fitTx, edge, true, true);
            pr.fillRect(toS(2f), toS(2f), toS(32f), toS(32f));
            snapshot("setTexture repeat=false linear=true alpha=true");

            pr.setColor(0, 0, 0, 255);
            drawImage(IMAGE_STEPS.get(0), translucent);
            snapshot("drawImage NORMAL, edges KEEP");

            drawImage(IMAGE_STEPS.get(1), opaque);
            snapshot("drawImage NORMAL, edges PAD");

            drawImage(IMAGE_STEPS.get(2), translucent);
            snapshot("drawImage NORMAL repeat, edges TRIM");

            pr.setColor(255, 255, 255, 128);
            drawImage(IMAGE_STEPS.get(3), opaque);
            snapshot("drawImage MULTIPLY with composite alpha, offset and stride > width, mixed edges");

            pr.setColor(10, 200, 90, 255);
            byte[] mask = glyphMask(12, 10);
            pr.fillAlphaMask(mask, 5, 45, 12, 10, 0, 12);
            pr.fillAlphaMask(mask, 20, 45, 8, 6, 13, 8);
            snapshot("fillAlphaMask (whole mask, then offset sub-mask)");

            byte[] lcdMask = glyphMask(30, 8);
            pr.setLCDGammaCorrection(1f);
            pr.setColor(0, 0, 0, 255);
            pr.fillLCDAlphaMask(lcdMask, 30, 50, 30, 8, 0, 30);
            snapshot("fillLCDAlphaMask gamma 1.0");

            pr.setLCDGammaCorrection(1.4f);
            pr.setColor(255, 40, 40, 255);
            pr.fillLCDAlphaMask(lcdMask, 30, 56, 30, 8, 0, 30);
            snapshot("fillLCDAlphaMask gamma 1.4");

            pr.setColor(30, 60, 200, 255);
            byte[] alphaMap = alphaMap();
            int[] deltas = new int[80];
            for (int row = 0; row < 4; row++) {
                int width = fillRampDeltas(deltas, 4);
                pr.emitAndClearAlphaRow(alphaMap, deltas, 2 + row, 3, 3 + width - 1, 4, row);
                assertArrayEquals(new int[80], deltas, "deltas zeroed after row " + row);
            }
            int width = fillRampDeltas(deltas, 0);
            pr.emitAndClearAlphaRow(alphaMap, deltas, 6, 40, 40 + width - 1, 4);
            assertArrayEquals(new int[80], deltas, "deltas zeroed after the six-argument overload");
            snapshot("emitAndClearAlphaRow, solid colour");

            pr.setLinearGradient(toS(40f), toS(8f), toS(56f), toS(8f), fractions, rgba,
                    GradientColorMap.CYCLE_REFLECT, null);
            for (int row = 0; row < 3; row++) {
                fillRampDeltas(deltas, 4);
                pr.emitAndClearAlphaRow(alphaMap, deltas, 8 + row, 40, 55, 4, row);
                assertArrayEquals(new int[80], deltas, "deltas zeroed after gradient row " + row);
            }
            snapshot("emitAndClearAlphaRow, gradient paint");

            int[] block = new int[3 + 6 * 4];
            for (int i = 0; i < block.length; i++) {
                block[i] = 0xFF000000 | (i * 0x0A0B0C);
            }
            surface.setRGB(block, 3, 6, 58, 58, 4, 4);
            int[] back = new int[W * H];
            surface.getRGB(back, 0, W, 0, 0, W, H);
            assertArrayEquals(pixels, back, "getRGB of the whole surface");
            snapshot("setRGB block");
        }

        /** Draws {@code image} with the texels {@code data}; the snapshot taken next is frame {@code image.step()}. */
        private void drawImage(ImageStep image, int[] data) {
            assertEquals(image.step(), steps.size(), "IMAGE_STEPS names the frame each drawImage step produces");
            image.draw(pr, data);
        }

        void writeGolden(Path path) throws IOException {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(path)))) {
                for (Step step : steps) {
                    for (int pixel : step.snapshot()) {
                        out.writeInt(pixel);
                    }
                }
            }
            System.out.println("captured " + steps.size() + " snapshots (" + steps.size() * W * H * 4
                    + " bytes) to " + path);
        }

        void assertMatchesGolden() throws IOException {
            byte[] bytes;
            try (InputStream in = PiscesGoldenRenderTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
                assertNotNull(in, "golden resource " + GOLDEN_RESOURCE + " is missing next to "
                        + PiscesGoldenRenderTest.class.getName());
                bytes = in.readAllBytes();
            }
            assertEquals(steps.size() * W * H * 4, bytes.length, "golden size: " + steps.size()
                    + " snapshots of " + W + "x" + H + " ints expected; the script and the golden disagree");
            List<Mismatch> mismatches = compare(steps, frames(bytes, steps.size()));
            LEDGER.compared(steps.size());
            if (!mismatches.isEmpty()) {
                fail(describe(mismatches, steps.size()));
            }
        }
    }
}
