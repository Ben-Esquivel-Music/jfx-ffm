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

package test.javafx.scene.image;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.ImageInput;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import test.util.Util;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The left and top edges of scaled and sub-pixel-positioned images on the software pipeline, through public API
 * only: a headless SW toolkit, and a default {@code Node.snapshot} with a transparent fill. The scenes are those
 * of story US-013, measured on D3D and ES2 on 2026-09-26: a 16 x 10 image with a transparent one-pixel border
 * around an opaque grey interior, centred at (18, 11), drawn by an {@code ImageView} scaled about its centre or
 * translated by a quarter pixel, and by {@code Canvas.drawImage}; a red / blue / green stripe image; an alpha
 * ramp drawn as an image and as an {@code ImageInput} effect, whose texture SW clamps to zero as D3D does; and
 * single-colour images one pixel tall, one pixel wide, or both, scaled by 4.
 * <p>
 * Before the fix the first device column and row were interpolated with the weights of the texel pair (-1, 0)
 * applied to texels 0 and 1, which moved the alpha centroid up and left, and the neighbour read of a one-pixel
 * tall or wide image went past the end of its pixel array into whatever followed it on the Java heap.
 */
public class SWTextureEdgeSnapshotTest {

    private static final int W = 16;
    private static final int H = 10;
    private static final double X = 10;
    private static final double Y = 6;

    private static final int GREEN = 0xFF00FF00;
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    @BeforeAll
    public static void initFX() {
        System.setProperty("glass.platform", "Headless");
        System.setProperty("prism.order", "sw");
        CountDownLatch startupLatch = new CountDownLatch(1);
        Util.startup(startupLatch, startupLatch::countDown);
    }

    @AfterAll
    public static void teardown() {
        Util.shutdown();
    }

    static Stream<Drawing> mirrorCases() {
        return Stream.of(Drawing.imageView(2), Drawing.imageView(1.5), Drawing.imageView(3),
                Drawing.canvas(2), Drawing.canvas(1.5));
    }

    /**
     * The border image is symmetric, so each first column and row must equal the mirrored last one. The 16.16
     * fixed-point inverse scale is not exact at 1.5 and 3, so there the mirrored sample points differ by an ulp
     * and a channel may differ by 1.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("mirrorCases")
    public void scaledBorderImageIsMirrorSymmetric(Drawing drawing) {
        Frame frame = snapshot(() -> drawing.node(borderImage()));
        assertMirrorSymmetric(frame, drawing.area(), drawing.scale() == 2 ? 0 : 1, drawing.toString());
    }

    static Stream<Drawing> centroidCases() {
        return Stream.concat(mirrorCases(), Stream.of(Drawing.quarterPixelTranslation()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("centroidCases")
    public void borderImageAlphaCentroidIsTheGeometricCentre(Drawing drawing) {
        Frame frame = snapshot(() -> drawing.node(borderImage()));
        Area area = drawing.area();
        double cx = area.x() + area.w() / 2;
        double cy = area.y() + area.h() / 2;
        double[] centroid = alphaCentroid(frame);
        assertTrue(Math.abs(centroid[0] - cx) <= 0.01 && Math.abs(centroid[1] - cy) <= 0.01,
                String.format("%s: alpha centroid (%.4f, %.4f), geometric centre (%.4f, %.4f)", drawing,
                        centroid[0], centroid[1], cx, cy));
    }

    /**
     * Translate (0.25, 0.25): the first and last columns differ in coverage and sample point, so no mirror
     * applies. The middle row and column are compared with the ones D3D and ES2 draw.
     */
    @Test
    public void quarterPixelTranslatedBorderImageMatchesTheGpu() {
        Frame frame = snapshot(() -> Drawing.quarterPixelTranslation().node(borderImage()));
        int[] gpuRow = {0, 191, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 64, 0};
        int[] gpuColumn = {0, 191, 255, 255, 255, 255, 255, 255, 255, 64, 0};
        int[] row = new int[gpuRow.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = frame.at(10 + i, 11) >>> 24;
        }
        int[] column = new int[gpuColumn.length];
        for (int i = 0; i < column.length; i++) {
            column[i] = frame.at(18, 6 + i) >>> 24;
        }
        assertWithinOne(gpuRow, row, "alpha of row y=11, x=10..26");
        assertWithinOne(gpuColumn, column, "alpha of column x=18, y=6..16");
    }

    /**
     * The alpha ramp (outer ring 64, then 160, then 255) at scale 2, as an {@code ImageView} and as an
     * {@code ImageInput} effect. D3D and ES2 clamp the image texture to its edge (64) and the Decora texture to
     * zero (48 = 0.75 x 64); so does SW, whose effect textures are {@code CLAMP_TO_ZERO} render targets.
     */
    @ParameterizedTest(name = "imageInput={0}")
    @ValueSource(booleans = {false, true})
    public void alphaRampEdgesMatchTheGpu(boolean imageInput) {
        Frame frame = snapshot(() -> {
            Node node = Drawing.imageView(2).node(rampImage());
            if (imageInput) {
                node.setEffect(new ImageInput(((ImageView) node).getImage(), X, Y));
            }
            return node;
        });
        int edge = imageInput ? 48 : 64;
        int[] gpuRow = new int[32];
        Arrays.fill(gpuRow, 255);
        int[] gpuColumn = new int[20];
        Arrays.fill(gpuColumn, 255);
        int[] ramp = {edge, 88, 136, 184, 231};
        for (int i = 0; i < ramp.length; i++) {
            gpuRow[i] = ramp[i];
            gpuRow[gpuRow.length - 1 - i] = ramp[i];
            gpuColumn[i] = ramp[i];
            gpuColumn[gpuColumn.length - 1 - i] = ramp[i];
        }
        int[] row = new int[gpuRow.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = frame.at(2 + i, 11) >>> 24;
        }
        int[] column = new int[gpuColumn.length];
        for (int i = 0; i < column.length; i++) {
            column[i] = frame.at(18, 1 + i) >>> 24;
        }
        String what = imageInput ? "ImageInput effect" : "ImageView";
        assertWithinOne(gpuRow, row, what + ": alpha of row y=11, x=2..33");
        assertWithinOne(gpuColumn, column, what + ": alpha of column x=18, y=1..20");
    }

    /** Opaque red, blue and green rings at scale 2: the edge columns and rows are pure red, as on D3D. */
    @Test
    public void stripeImageEdgeColumnsAndRowsAreTheEdgeColour() {
        Drawing drawing = Drawing.imageView(2);
        Frame frame = snapshot(() -> drawing.node(stripeImage()));
        Area area = drawing.area();
        int x0 = (int) area.x();
        int y0 = (int) area.y();
        int x1 = x0 + (int) area.w() - 1;
        int y1 = y0 + (int) area.h() - 1;
        List<String> wrong = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            expectWithinOne(frame, x0, y, RED, wrong);
            expectWithinOne(frame, x1, y, RED, wrong);
        }
        for (int x = x0; x <= x1; x++) {
            expectWithinOne(frame, x, y0, RED, wrong);
            expectWithinOne(frame, x, y1, RED, wrong);
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " edge pixels are not within 1 of 0xFFFF0000, first: "
                + wrong.subList(0, Math.min(4, wrong.size())));
    }

    static Stream<Arguments> oneTexelCases() {
        return Stream.of(Arguments.of(4, 1, 1, 4), Arguments.of(1, 4, 4, 1), Arguments.of(1, 1, 4, 4));
    }

    /**
     * An opaque green image one pixel tall, one pixel wide, or both, scaled by 4. Interpolating green can only
     * give premultiplied green, so a red or blue component, or a green that differs from alpha, came from memory
     * past the image's pixels; a fully covered pixel is the image colour exactly. What lies past the array depends
     * on the heap, so the values seen before the fix vary, but no layout makes them green.
     */
    @ParameterizedTest(name = "{0}x{1} scaled {2},{3}")
    @MethodSource("oneTexelCases")
    public void oneTexelTallOrWideImageShowsOnlyItsColour(int w, int h, int scaleX, int scaleY) {
        WritableImage image = new WritableImage(w, h);
        int[] green = new int[w * h];
        Arrays.fill(green, GREEN);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbPreInstance(), green, 0, w);
        AtomicReference<Bounds> bounds = new AtomicReference<>();
        Frame frame = snapshot(() -> {
            ImageView view = new ImageView(image);
            view.setScaleX(scaleX);
            view.setScaleY(scaleY);
            bounds.set(view.getBoundsInParent());
            return view;
        });
        Bounds b = bounds.get();
        List<String> foreign = new ArrayList<>();
        List<String> notExact = new ArrayList<>();
        int fullyCovered = 0;
        for (int y = frame.y0(); y < frame.y0() + frame.h(); y++) {
            for (int x = frame.x0(); x < frame.x0() + frame.w(); x++) {
                int p = frame.at(x, y);
                int a = p >>> 24;
                String at = String.format("(%d,%d)=0x%08X", x, y, p);
                if (((p >> 16) & 0xFF) != 0 || (p & 0xFF) != 0 || ((p >> 8) & 0xFF) != a) {
                    foreign.add(at);
                }
                if (x >= b.getMinX() && x + 1 <= b.getMaxX() && y >= b.getMinY() && y + 1 <= b.getMaxY()) {
                    fullyCovered++;
                    if (p != GREEN) {
                        notExact.add(at);
                    }
                }
            }
        }
        assertTrue(fullyCovered > 0, "the image covers no whole pixel: " + b);
        assertTrue(foreign.isEmpty() && notExact.isEmpty(), String.format("%dx%d scaled %d,%d: %d pixels are not"
                + " premultiplied green %s; %d of %d fully covered pixels are not 0xFF00FF00 %s", w, h, scaleX,
                scaleY, foreign.size(), foreign.subList(0, Math.min(4, foreign.size())), notExact.size(),
                fullyCovered, notExact.subList(0, Math.min(4, notExact.size()))));
    }

    /** How the border or stripe image is drawn, and the device rectangle it covers. */
    record Drawing(String name, double scale, double translate, boolean onCanvas) {

        static Drawing imageView(double scale) {
            return new Drawing("ImageView scale " + scale, scale, 0, false);
        }

        static Drawing quarterPixelTranslation() {
            return new Drawing("ImageView translate (0.25, 0.25)", 1, 0.25, false);
        }

        /** The scale-3 image does not fit the canvas, as in the story's probe. */
        static Drawing canvas(double scale) {
            return new Drawing("Canvas.drawImage scale " + scale, scale, 0, true);
        }

        /** The image scaled about its centre (X + W / 2, Y + H / 2), then translated. */
        Area area() {
            return new Area(X + W / 2.0 - W * scale / 2 + translate, Y + H / 2.0 - H * scale / 2 + translate,
                    W * scale, H * scale);
        }

        Node node(Image image) {
            if (onCanvas) {
                Canvas canvas = new Canvas(80, 60);
                GraphicsContext g = canvas.getGraphicsContext2D();
                g.setImageSmoothing(true);
                Area area = area();
                g.drawImage(image, area.x(), area.y(), area.w(), area.h());
                return canvas;
            }
            ImageView view = new ImageView(image);
            view.setX(X);
            view.setY(Y);
            view.setScaleX(scale);
            view.setScaleY(scale);
            view.setTranslateX(translate);
            view.setTranslateY(translate);
            return view;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    record Area(double x, double y, double w, double h) {
    }

    /** A snapshot in scene coordinates; pixels outside it are transparent. */
    record Frame(int x0, int y0, int w, int h, int[] argb) {

        int at(int x, int y) {
            if (x < x0 || y < y0 || x >= x0 + w || y >= y0 + h) {
                return 0;
            }
            return argb[(y - y0) * w + (x - x0)];
        }
    }

    /** A default snapshot with a transparent fill; its origin is the floor of the node's bounds in parent. */
    private static Frame snapshot(Supplier<Node> factory) {
        AtomicReference<Frame> frame = new AtomicReference<>();
        Util.runAndWait(() -> {
            Node node = factory.get();
            new Scene(new Group(node));
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            Bounds bounds = node.getBoundsInParent();
            WritableImage snap = node.snapshot(params, null);
            int w = (int) snap.getWidth();
            int h = (int) snap.getHeight();
            int[] argb = new int[w * h];
            snap.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbPreInstance(), argb, 0, w);
            frame.set(new Frame((int) Math.floor(bounds.getMinX()), (int) Math.floor(bounds.getMinY()), w, h, argb));
        });
        return frame.get();
    }

    private static Image borderImage() {
        return ringImage(0x00000000, 0xFF808080, 0xFF808080);
    }

    private static Image stripeImage() {
        return ringImage(RED, BLUE, GREEN);
    }

    private static Image rampImage() {
        return ringImage(0x40404040, 0xA0A0A0A0, 0xFFFFFFFF);
    }

    private static Image ringImage(int outer, int second, int interior) {
        int[] argb = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int ring = Math.min(Math.min(x, W - 1 - x), Math.min(y, H - 1 - y));
                argb[y * W + x] = ring == 0 ? outer : ring == 1 ? second : interior;
            }
        }
        WritableImage image = new WritableImage(W, H);
        image.getPixelWriter().setPixels(0, 0, W, H, PixelFormat.getIntArgbPreInstance(), argb, 0, W);
        return image;
    }

    /** Every pixel the area touches against its mirror image about the area's centre, per channel. */
    private static void assertMirrorSymmetric(Frame frame, Area area, int tolerance, String label) {
        int x0 = (int) Math.floor(area.x());
        int x1 = (int) Math.ceil(area.x() + area.w());
        int y0 = (int) Math.floor(area.y());
        int y1 = (int) Math.ceil(area.y() + area.h());
        int xMirror = (int) Math.round(2 * area.x() + area.w()) - 1;
        int yMirror = (int) Math.round(2 * area.y() + area.h()) - 1;
        List<String> asymmetric = new ArrayList<>();
        int worst = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = frame.at(x, y);
                int horizontal = frame.at(xMirror - x, y);
                int vertical = frame.at(x, yMirror - y);
                int diff = Math.max(channelDiff(p, horizontal), channelDiff(p, vertical));
                worst = Math.max(worst, diff);
                if (diff > tolerance) {
                    asymmetric.add(String.format("(%d,%d)=0x%08X mirrors (%d,%d)=0x%08X and (%d,%d)=0x%08X", x, y, p,
                            xMirror - x, y, horizontal, x, yMirror - y, vertical));
                }
            }
        }
        assertTrue(asymmetric.isEmpty(), String.format("%s: %d pixels differ from their mirror image by more than"
                + " %d (worst %d), first: %s", label, asymmetric.size(), tolerance, worst,
                asymmetric.subList(0, Math.min(3, asymmetric.size()))));
    }

    private static int channelDiff(int p, int q) {
        int worst = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            worst = Math.max(worst, Math.abs(((p >>> shift) & 0xFF) - ((q >>> shift) & 0xFF)));
        }
        return worst;
    }

    /** Alpha-weighted centre of the pixel centres, in scene coordinates. */
    private static double[] alphaCentroid(Frame frame) {
        double sum = 0;
        double sx = 0;
        double sy = 0;
        for (int y = frame.y0(); y < frame.y0() + frame.h(); y++) {
            for (int x = frame.x0(); x < frame.x0() + frame.w(); x++) {
                int a = frame.at(x, y) >>> 24;
                sum += a;
                sx += a * (x + 0.5);
                sy += a * (y + 0.5);
            }
        }
        assertTrue(sum > 0, "nothing was drawn");
        return new double[] {sx / sum, sy / sum};
    }

    private static void assertWithinOne(int[] expected, int[] actual, String label) {
        for (int i = 0; i < expected.length; i++) {
            if (Math.abs(expected[i] - actual[i]) > 1) {
                fail(label + ": expected " + Arrays.toString(expected) + " within 1 but was "
                        + Arrays.toString(actual));
            }
        }
    }

    private static void expectWithinOne(Frame frame, int x, int y, int expected, List<String> wrong) {
        int p = frame.at(x, y);
        if (channelDiff(p, expected) > 1) {
            wrong.add(String.format("(%d,%d)=0x%08X", x, y, p));
        }
    }
}
