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
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pixel-exact parity harness for the {@code prism_sw} (Pisces) compositor.
 * <p>
 * A fixed script drives {@link PiscesRenderer} and {@link JavaSurface} through every operation the
 * software pipeline ({@code com.sun.prism.sw}) uses, on a 64x64 {@code TYPE_INT_ARGB_PRE} surface, and
 * takes a full-frame snapshot of the surface after every step. The golden resource
 * {@value #GOLDEN_RESOURCE} is the raw big-endian concatenation of those snapshots, captured from the
 * <b>JNI build</b> at commit {@code a544256444} (the hash in its name) on Windows x64 - see
 * {@code scratchpad/notes/prism_sw_java.md} of the migration for the capture log. The Pisces C code is
 * integer-only, so the same golden is expected to hold on every platform.
 * <p>
 * Every later change to the Java side of Pisces - the JNI to FFM flip first of all - has to reproduce
 * this file byte for byte: the C bodies do not change in a migration, so any difference is a
 * marshalling bug in Java and is fixed there, never by regenerating the golden. Regenerating it is a
 * behaviour change and needs its own commit with the reason stated.
 * <p>
 * Capture mode: with {@code -Dpisces.golden.capture=<absolute path>} the test writes the file to that
 * path instead of asserting against the resource. The per-step layout exists so that a mismatch names
 * the operation that broke rather than only the pixel.
 */
public class PiscesGoldenRenderTest {

    static final String GOLDEN_RESOURCE = "pisces-golden-a544256444.bin";
    static final String CAPTURE_PROPERTY = "pisces.golden.capture";

    static final int W = 64;
    static final int H = 64;

    /** Coverage maximum of the synthetic alpha rows; independent of Marlin's configured subpixel grid. */
    static final int MAX_ALPHA = 64;

    @BeforeAll
    static void requireNatives() {
        PiscesNatives.require();
    }

    @Test
    public void rendersExactlyAsTheGoldenBuildDid() throws IOException {
        Script script = new Script();
        script.run();
        String capture = System.getProperty(CAPTURE_PROPERTY);
        if (capture != null && !capture.isBlank()) {
            script.writeGolden(Path.of(capture));
            return;
        }
        script.assertMatchesGolden();
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
     * A range the Java-side {@code AbstractSurface.rgbCheck} rejects. The one check only the C side
     * made ({@code offset + height * scanLength} elements) is exercised in {@code PiscesNativeTest}: the
     * JNI build could not run it, because its {@code JNI_ThrowNew} treated the exception it had just
     * raised as a failure and aborted the JVM ({@code FatalError("Failed to throw an exception!")}).
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

    private record Step(String name, int[] snapshot) {
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

            Transform6 fitTx = new Transform6(toS(0.5f), 0, 0, toS(0.5f), toS(-1f), toS(-1f));
            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, opaque, 16, 16, 16, fitTx, false, false, false);
            pr.fillRect(toS(2f), toS(2f), toS(32f), toS(32f));
            snapshot("setTexture repeat=false linear=false alpha=false");

            Transform6 tileTx = new Transform6(toS(1.3f), toS(0.05f), toS(-0.05f), toS(1.3f), toS(0.5f), toS(0.25f));
            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, translucent, 16, 16, 16, tileTx, true, true, true);
            pr.fillRect(toS(30f), toS(30f), toS(34f), toS(34f));
            snapshot("setTexture repeat=true linear=true alpha=true");

            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, opaque, 12, 12, 16, tileTx, true, false, false);
            pr.fillRect(toS(2.5f), toS(34f), toS(28f), toS(28f));
            snapshot("setTexture repeat=true linear=false alpha=false, stride > width");

            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, translucent, 16, 16, 16, fitTx, false, true, true);
            pr.fillRect(toS(2f), toS(2f), toS(32f), toS(32f));
            snapshot("setTexture repeat=false linear=true alpha=true");

            pr.setColor(0, 0, 0, 255);
            float bx = 20.5f;
            float by = 20.25f;
            float bw = 24.5f;
            float bh = 20.75f;
            Transform6 imageTx = new Transform6(toS(16f / bw), toS(0.05f), toS(-0.03f), toS(16f / bh),
                    toS(-bx * 16f / bw), toS(-by * 16f / bh));
            drawImage(RendererBase.IMAGE_MODE_NORMAL, translucent, 16, 16, 0, 16, imageTx, false, true,
                    bx, by, bw, bh, RendererBase.IMAGE_FRAC_EDGE_KEEP);
            snapshot("drawImage NORMAL, edges KEEP");

            drawImage(RendererBase.IMAGE_MODE_NORMAL, opaque, 16, 16, 0, 16, imageTx, false, false,
                    bx + 0.5f, by + 12f, bw, bh, RendererBase.IMAGE_FRAC_EDGE_PAD);
            snapshot("drawImage NORMAL, edges PAD");

            drawImage(RendererBase.IMAGE_MODE_NORMAL, translucent, 16, 16, 0, 16, imageTx, true, true,
                    bx - 10.25f, by + 6.5f, bw, bh, RendererBase.IMAGE_FRAC_EDGE_TRIM);
            snapshot("drawImage NORMAL repeat, edges TRIM");

            pr.setColor(255, 255, 255, 128);
            drawImage(RendererBase.IMAGE_MODE_MULTIPLY, opaque, 12, 12, 34, 16, imageTx, false, true,
                    bx, by, bw, bh, RendererBase.IMAGE_FRAC_EDGE_KEEP, RendererBase.IMAGE_FRAC_EDGE_PAD,
                    RendererBase.IMAGE_FRAC_EDGE_TRIM, RendererBase.IMAGE_FRAC_EDGE_KEEP, 0, 0, 11, 11);
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

        private void drawImage(int mode, int[] data, int w, int h, int offset, int stride, Transform6 tx,
                boolean repeat, boolean linear, float bx, float by, float bw, float bh, int edge) {
            drawImage(mode, data, w, h, offset, stride, tx, repeat, linear, bx, by, bw, bh,
                    edge, edge, edge, edge, 0, 0, w - 1, h - 1);
        }

        private void drawImage(int mode, int[] data, int w, int h, int offset, int stride, Transform6 tx,
                boolean repeat, boolean linear, float bx, float by, float bw, float bh,
                int lEdge, int rEdge, int tEdge, int bEdge, int txMin, int tyMin, int txMax, int tyMax) {
            pr.drawImage(RendererBase.TYPE_INT_ARGB_PRE, mode, data, w, h, offset, stride, tx, repeat, linear,
                    toS(bx), toS(by), toS(bw), toS(bh), lEdge, rEdge, tEdge, bEdge, txMin, tyMin, txMax, tyMax,
                    true);
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
            IntBuffer golden = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer();
            int[] expected = new int[W * H];
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                golden.get(expected);
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
                    fail(String.format("step %d '%s': pixel (%d,%d) expected 0x%08X but was 0x%08X"
                            + " (first of %d differing pixels)", i, step.name(), first % W, first / W,
                            expected[first], actual[first], differing));
                }
            }
        }
    }
}
