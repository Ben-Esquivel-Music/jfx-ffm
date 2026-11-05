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

import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.PGFont;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.geom.Path2D;
import com.sun.javafx.geom.Point2D;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.Affine2D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.transform.GeneralTransform3D;
import com.sun.javafx.scene.text.GlyphList;
import com.sun.javafx.scene.text.TextSpan;
import com.sun.prism.BasicStroke;
import com.sun.prism.CompositeMode;
import com.sun.prism.Graphics;
import com.sun.prism.Image;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.ImagePattern;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.Paint;
import com.sun.prism.paint.RadialGradient;
import com.sun.prism.paint.Stop;
import com.sun.prism.ps.Shader;
import com.sun.prism.ps.ShaderFactory;
import com.sun.prism.ps.ShaderGraphics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import test.com.sun.prism.mtl.MTLGoldenChild.Session;
import test.com.sun.prism.mtl.MTLGoldenChild.Steps;

/**
 * The 2D corpus: clears, quad batches, the paint-shader matrix, composite modes, clips, uniforms and text. Arguments
 * are asymmetric on purpose (r, g, b and a differ; x, y, w and h differ), so that a swapped, narrowed or mis-offset
 * argument changes pixels.
 */
final class MTLCorpus2D {

    static final Color BACKGROUND = new Color(0.2f, 0.4f, 0.6f, 1f);
    static final Color PAINT = new Color(0.9f, 0.3f, 0.6f, 0.8f);

    /**
     * The prism shaders no profile can create, with the reason; {@code MTLShaderTableGoldenTest} still creates every
     * one of them and pins its uniform table.
     */
    static final Map<String, String> UNREACHABLE_SHADERS = unreachableShaders();

    private static final int SHADER_FRAME = 32;
    private static final int FRAME = 64;
    private static final List<String> MASKS = List.of("Solid", "Texture", "FillPgram", "DrawPgram", "FillCircle",
            "DrawCircle", "FillEllipse", "DrawEllipse", "FillRoundRect", "DrawRoundRect", "DrawSemiRoundRect");
    private static final List<String> PAINTS = List.of("Color", "ImagePattern", "LinearGradient_PAD",
            "LinearGradient_REFLECT", "LinearGradient_REPEAT", "RadialGradient_PAD", "RadialGradient_REFLECT",
            "RadialGradient_REPEAT");

    private static final Image PATTERN = image(7, 5, (x, y) -> 0xff000000 | (x * 36) << 16 | (y * 60) << 8
            | (200 - 25 * (x + y)));

    private MTLCorpus2D() {
    }

    @FunctionalInterface
    interface Draw {
        void draw(Graphics g) throws Exception;
    }

    @FunctionalInterface
    interface Pixel {
        int argbPre(int x, int y);
    }

    static void register(Steps steps) {
        clears(steps);
        quads(steps);
        paintShaders(steps);
        composites(steps);
        clips(steps);
        uniforms(steps);
        text(steps);
    }

    private static Map<String, String> unreachableShaders() {
        Map<String, String> unreachable = new TreeMap<>();
        String primitives = "the AlphaOne/AlphaTexture/AlphaTextureDifference masks are chosen only when"
                + " prism.primtexsize is non-zero, and PrismSettings makes it 0 on macOS by default";
        for (String mask : List.of("AlphaOne", "AlphaTexture", "AlphaTextureDifference")) {
            for (String paint : List.of("Color", "ImagePattern", "LinearGradient", "RadialGradient")) {
                unreachable.put(mask + "_" + paint, primitives);
                unreachable.put(mask + "_" + paint + "_AlphaTest", primitives);
            }
        }
        String lcd = "PrismFontStrike makes every strike greyscale unless prism.lcdtext is true, and it is false"
                + " on macOS by default";
        for (String pass : List.of("First", "Second")) {
            unreachable.put("Solid_Texture" + pass + "PassLCD", lcd);
            unreachable.put("Solid_Texture" + pass + "PassLCD_AlphaTest", lcd);
        }
        return unreachable;
    }

    // ---------------------------------------------------------------------------------------------
    // C: clear
    // ---------------------------------------------------------------------------------------------

    private static void clears(Steps steps) {
        List<Color> colors = List.of(new Color(0.8f, 0.6f, 0.4f, 1f), new Color(0.7f, 0.5f, 0.3f, 0.6f),
                Color.TRANSPARENT);
        List<String> names = List.of("opaque", "translucent", "transparent");
        if (steps.in(MTLGoldens.DEFAULT)) {
            for (int c = 0; c < colors.size(); c++) {
                Color color = colors.get(c);
                for (boolean msaa : new boolean[] {false, true}) {
                    String base = "clear." + names.get(c) + (msaa ? ".msaa" : "");
                    steps.addUniform(base, FRAME, FRAME, s -> s.renderUncleared(FRAME, FRAME, msaa, g -> {
                        g.clear(BACKGROUND);
                        g.clear(color);
                    }));
                    steps.add(base + ".depth", FRAME, FRAME, s -> s.renderUncleared(FRAME, FRAME, msaa, g -> {
                        g.setDepthBuffer(true);
                        g.clear(BACKGROUND);
                        g.clear(color);
                        g.setDepthTest(true);
                        g.setPaint(PAINT);
                        g.fillRect(9, 13, 37, 29);
                    }));
                }
            }
            // With the depth test already on, the clear keeps its encoder and the fill after it is not drawn.
            steps.addUniform("clear.depthTestDuringClear", FRAME, FRAME, s -> s.renderUncleared(FRAME, FRAME, false,
                    g -> {
                        g.setDepthBuffer(true);
                        g.setDepthTest(true);
                        g.clear(BACKGROUND);
                        g.setPaint(PAINT);
                        g.fillRect(9, 13, 37, 29);
                    }));
            for (boolean msaa : new boolean[] {false, true}) {
                steps.add("clear.scissored" + (msaa ? ".msaa" : ""), FRAME, FRAME,
                        s -> s.renderUncleared(FRAME, FRAME, msaa, g -> {
                            g.clear(BACKGROUND);
                            g.setClipRect(new Rectangle(5, 9, 37, 22));
                            g.clear(colors.get(1));
                        }));
            }
        }
        if (steps.in(MTLGoldens.POW2)) {
            for (int c = 0; c < 2; c++) {
                Color color = colors.get(c);
                for (boolean msaa : new boolean[] {false, true}) {
                    steps.add("clear." + names.get(c) + (msaa ? ".msaa" : ""), 48, 40,
                            s -> s.renderUncleared(48, 40, msaa, g -> {
                                g.clear(color);
                                g.setClipRect(new Rectangle(3, 7, 29, 19));
                                g.clear(BACKGROUND);
                            }));
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Q: quad batches and transforms
    // ---------------------------------------------------------------------------------------------

    private static void quads(Steps steps) {
        boolean all = steps.in(MTLGoldens.DEFAULT);
        if (!all && !steps.in(MTLGoldens.NO_SUPER_SHADER)) {
            return;
        }
        steps.add("quad.single", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
            g.setAntialiasedShape(false);
            g.setPaint(PAINT);
            g.fillRect(7, 11, 29, 17);
        }));
        steps.add("quad.exactly4096", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> grid(g, 4096)));
        steps.add("quad.5120", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> grid(g, 5120)));
        if (all) {
            steps.add("quad.70000overlapping", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
                g.setAntialiasedShape(false);
                for (int i = 0; i < 70_000; i++) {
                    g.setPaint(new Color((i % 7) / 7f, (i % 11) / 11f, (i % 13) / 13f, 0.015f + (i % 5) * 0.004f));
                    float x = (i * 7) % 59;
                    float y = (i * 13) % 61;
                    g.fillRect(x, y, 4 + i % 3, 3 + i % 4);
                }
                g.sync();
            }));
        }
        steps.add("quad.textureSwitch", FRAME, FRAME, s -> {
            Texture first = MTLCorpusTextures.argbTexture(s, 8, 8, 0);
            Texture second = MTLCorpusTextures.argbTexture(s, 8, 8, 1);
            try {
                return s.render(FRAME, FRAME, false, g -> {
                    for (int i = 0; i < 16; i++) {
                        Texture texture = i % 2 == 0 ? first : second;
                        float x = (i % 4) * 15 + 2;
                        float y = (i / 4) * 15 + 3;
                        g.drawTexture(texture, x, y, x + 13, y + 11, 0, 0, 8, 8);
                    }
                });
            } finally {
                first.dispose();
                second.dispose();
            }
        });
        steps.add("quad.translate", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
            g.translate(5.5f, -3.25f);
            transformed(g);
        }));
        if (all) {
            steps.add("quad.scale", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
                g.scale(1.75f, 0.625f);
                transformed(g);
            }));
            steps.add("quad.rotate", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
                Affine2D rotate = new Affine2D();
                rotate.rotate(0.4, 30, 26);
                g.transform(rotate);
                transformed(g);
            }));
            steps.add("quad.shear", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
                Affine2D shear = new Affine2D();
                shear.shear(0.3, -0.2);
                g.transform(shear);
                transformed(g);
            }));
        }
        steps.add("quad.perspective", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
            GeneralTransform3D perspective = new GeneralTransform3D();
            perspective.set(new double[] {
                1.05, 0.12, 0, 2.5,
                -0.06, 0.97, 0, 1.25,
                0, 0, 1, 0,
                0.0021, 0.0013, 0, 1});
            g.setPerspectiveTransform(perspective);
            transformed(g);
            // The perspective transform is context state that outlives this Graphics; WebKit resets it the same way.
            g.setPerspectiveTransform(null);
        }));
    }

    private static void grid(Graphics g, int quads) {
        g.setAntialiasedShape(false);
        for (int i = 0; i < quads; i++) {
            int cell = i % 4096;
            int x = cell % 64;
            int y = cell / 64;
            float alpha = i < 4096 ? 1f : 0.45f;
            g.setPaint(new Color(x / 63f, y / 63f, ((x * 5 + y * 3) % 64) / 63f, alpha));
            if (i < 4096) {
                g.fillRect(x, y, 1, 1);
            } else {
                g.fillRect((x * 2) % 62, (y * 3) % 61, 2, 3);
            }
        }
    }

    private static void transformed(Graphics g) {
        g.setAntialiasedShape(false);
        g.setPaint(PAINT);
        g.fillRect(6, 8, 31, 19);
        g.setPaint(new Color(0.1f, 0.8f, 0.35f, 0.7f));
        g.fillRect(22, 30, 25, 21);
    }

    // ---------------------------------------------------------------------------------------------
    // S: the paint-shader matrix and the special shaders reached through 2D primitives
    // ---------------------------------------------------------------------------------------------

    private static void paintShaders(Steps steps) {
        if (steps.in(MTLGoldens.DEFAULT)) {
            for (String mask : MASKS) {
                for (String paint : PAINTS) {
                    for (boolean alphaTest : new boolean[] {false, true}) {
                        String key = "paint." + mask + "." + paint + (alphaTest ? ".alphaTest" : "");
                        steps.add(key, SHADER_FRAME, SHADER_FRAME,
                                s -> shaderFrame(s, alphaTest, g -> {
                                    g.setPaint(paint(paint));
                                    primitive(g, mask);
                                }));
                    }
                }
            }
        } else if (steps.in(MTLGoldens.NO_SUPER_SHADER)) {
            for (String mask : List.of("Solid", "Texture", "FillPgram", "DrawRoundRect")) {
                for (String paint : List.of("Color", "LinearGradient_REFLECT", "ImagePattern")) {
                    steps.add("paint." + mask + "." + paint, SHADER_FRAME, SHADER_FRAME,
                            s -> shaderFrame(s, false, g -> {
                                g.setPaint(paint(paint));
                                primitive(g, mask);
                            }));
                }
            }
        }
    }

    /**
     * A {@link #SHADER_FRAME}-square target; with {@code alphaTest} the depth buffer and test are on, the test turned
     * on after the clear as {@code NGNode.render} does.
     */
    static int[] shaderFrame(Session s, boolean alphaTest, Draw body) throws Exception {
        return s.renderUncleared(SHADER_FRAME, SHADER_FRAME, false, g -> {
            depthClear(g, alphaTest);
            body.draw(g);
        });
    }

    /** Clears to {@link #BACKGROUND}; with {@code depth}, with a depth buffer and the depth test on afterwards. */
    static void depthClear(Graphics g, boolean depth) {
        g.setDepthBuffer(depth);
        g.clear(BACKGROUND);
        g.setDepthTest(depth);
    }

    static Paint paint(String name) {
        return switch (name) {
            case "Color" -> PAINT;
            case "ImagePattern" -> new ImagePattern(PATTERN, 1, 2, 7, 5, false, false);
            case "LinearGradient_PAD" -> linear(Gradient.PAD);
            case "LinearGradient_REFLECT" -> linear(Gradient.REFLECT);
            case "LinearGradient_REPEAT" -> linear(Gradient.REPEAT);
            case "RadialGradient_PAD" -> radial(Gradient.PAD);
            case "RadialGradient_REFLECT" -> radial(Gradient.REFLECT);
            case "RadialGradient_REPEAT" -> radial(Gradient.REPEAT);
            default -> throw new IllegalArgumentException(name);
        };
    }

    static List<Stop> stops() {
        return List.of(new Stop(new Color(1f, 0.2f, 0.1f, 1f), 0f), new Stop(new Color(0.1f, 0.9f, 0.3f, 0.7f), 0.4f),
                new Stop(new Color(0.2f, 0.3f, 1f, 0.9f), 1f));
    }

    static LinearGradient linear(int spread) {
        return new LinearGradient(6, 4, 15, 13, null, false, spread, stops());
    }

    static RadialGradient radial(int spread) {
        return new RadialGradient(14, 13, 30, 0.4f, 7, null, false, spread, stops());
    }

    /** The primitive that makes {@code BaseShaderGraphics} pick the {@code mask} shader family. */
    static void primitive(Graphics g, String mask) {
        switch (mask) {
            case "Solid" -> {
                g.setAntialiasedShape(false);
                g.fillRect(3, 5, 23, 19);
            }
            case "Texture" -> g.fill(triangle());
            case "FillPgram" -> g.fillRect(3.5f, 5.25f, 23, 19);
            case "DrawPgram" -> {
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10));
                g.drawRect(5, 6, 20, 17);
            }
            case "FillCircle" -> g.fillEllipse(5, 4, 22, 22);
            case "DrawCircle" -> {
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10));
                g.drawEllipse(6, 5, 20, 20);
            }
            case "FillEllipse" -> g.fillEllipse(3, 7, 26, 17);
            case "DrawEllipse" -> {
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10));
                g.drawEllipse(4, 7, 24, 16);
            }
            case "FillRoundRect" -> g.fillRoundRect(3, 5, 25, 21, 12, 9);
            case "DrawRoundRect" -> {
                g.setStroke(new BasicStroke(2, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10));
                g.drawRoundRect(4, 5, 23, 21, 14, 11);
            }
            case "DrawSemiRoundRect" -> {
                g.setStroke(new BasicStroke(6, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10));
                g.drawRoundRect(6, 6, 20, 19, 5, 5);
            }
            default -> throw new IllegalArgumentException(mask);
        }
    }

    static Path2D triangle() {
        Path2D path = new Path2D();
        path.moveTo(4, 3);
        path.lineTo(28, 9);
        path.quadTo(20, 20, 9, 28);
        path.closePath();
        return path;
    }

    static Image image(int width, int height, Pixel pixel) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = pixel.argbPre(x, y);
            }
        }
        return Image.fromIntArgbPreData(pixels, width, height);
    }

    // ---------------------------------------------------------------------------------------------
    // K: composite modes
    // ---------------------------------------------------------------------------------------------

    private static void composites(Steps steps) {
        if (!steps.in(MTLGoldens.DEFAULT)) {
            return;
        }
        for (String variant : List.of("plain", "msaa", "depth")) {
            List<String> keys = new ArrayList<>();
            for (CompositeMode mode : CompositeMode.values()) {
                String key = "composite." + mode.name() + "." + variant;
                keys.add(key);
                steps.add(key, FRAME, FRAME, s -> s.renderUncleared(FRAME, FRAME, variant.equals("msaa"), g -> {
                    g.setDepthBuffer(variant.equals("depth"));
                    g.clear(new Color(0.3f, 0.5f, 0.7f, 0.6f));
                    g.setDepthTest(variant.equals("depth"));
                    g.setPaint(new Color(0.15f, 0.85f, 0.45f, 0.9f));
                    g.fillRect(4, 30, 56, 14);
                    g.setCompositeMode(mode);
                    g.setPaint(new Color(0.9f, 0.2f, 0.4f, 0.5f));
                    g.fillRect(11, 7, 37, 45);
                    g.setPaint(linear(Gradient.REFLECT));
                    g.fillEllipse(30, 3, 27, 22);
                    g.setCompositeMode(CompositeMode.SRC_OVER);
                }));
            }
            steps.designedDistinct(keys);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // L: clip rectangles on quads, clears and meshes
    // ---------------------------------------------------------------------------------------------

    private static void clips(Steps steps) {
        if (!steps.in(MTLGoldens.DEFAULT)) {
            return;
        }
        Map<String, Rectangle> clips = new TreeMap<>(Map.of(
                "negativeOrigin", new Rectangle(-7, -5, 30, 41),
                "overflow", new Rectangle(37, 21, 60, 70),
                "fullCover", new Rectangle(-3, -2, 80, 90),
                "inverted", new Rectangle(40, 30, -10, -8)));
        for (Map.Entry<String, Rectangle> clip : clips.entrySet()) {
            Rectangle rect = clip.getValue();
            steps.add("clip." + clip.getKey() + ".quads", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
                g.setClipRect(new Rectangle(rect));
                g.setPaint(PAINT);
                g.fillRect(2, 3, 57, 55);
                g.setPaint(radial(Gradient.REPEAT));
                g.fillEllipse(8, 6, 44, 50);
            }));
            MTLGoldenChild.Body clear = s -> s.render(FRAME, FRAME, false, g -> {
                g.setPaint(PAINT);
                g.fillRect(2, 3, 57, 55);
                g.setClipRect(new Rectangle(rect));
                g.clear(new Color(0.95f, 0.75f, 0.05f, 0.85f));
            });
            // A clip covering the target resets the scissor, and an empty one is no clip at all: both clear it all.
            if (rect.contains(0, 0, FRAME, FRAME) || rect.isEmpty()) {
                steps.addUniform("clip." + clip.getKey() + ".clear", FRAME, FRAME, clear);
            } else {
                steps.add("clip." + clip.getKey() + ".clear", FRAME, FRAME, clear);
            }
            steps.add("clip." + clip.getKey() + ".mesh", FRAME, FRAME, s -> MTLCorpus3D.clippedMesh(s, rect));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // U: shader constants from heap and direct buffers
    // ---------------------------------------------------------------------------------------------

    private static final float[] WEIGHTS = {0.3f, 0.2f, 0.15f, 0.1f, 0.08f, 0.07f, 0.06f, 0.04f};

    private static void uniforms(Steps steps) {
        if (!steps.in(MTLGoldens.DEFAULT)) {
            return;
        }
        steps.add("uniform.direct", FRAME, FRAME, s -> convolve(s, () -> {
            FloatBuffer weights = direct(WEIGHTS.length);
            weights.put(WEIGHTS).rewind();
            return new Constants(weights, 0);
        }));
        steps.add("uniform.heap", FRAME, FRAME, s -> convolve(s, () -> new Constants(FloatBuffer.wrap(WEIGHTS.clone()),
                0)));
        steps.add("uniform.heapOffset", FRAME, FRAME, s -> convolve(s, () -> {
            FloatBuffer weights = FloatBuffer.allocate(4 + WEIGHTS.length + 4);
            weights.put(new float[] {9f, 9f, 9f, 9f}).put(WEIGHTS).put(new float[] {7f, 7f, 7f, 7f});
            weights.position(8);
            return new Constants(weights, 4);
        }));
        steps.add("uniform.directPosition", FRAME, FRAME, s -> convolve(s, () -> {
            FloatBuffer weights = direct(4 + WEIGHTS.length + 4);
            weights.put(new float[] {9f, 9f, 9f, 9f}).put(WEIGHTS).put(new float[] {7f, 7f, 7f, 7f});
            weights.position(4);
            return new Constants(weights, 8);
        }));
        steps.designedEqual("uniform.direct", "uniform.heap", "uniform.heapOffset", "uniform.directPosition");
        steps.add("uniform.disableEnable", FRAME, FRAME, MTLCorpus2D::disableEnable);
    }

    /**
     * {@code Shader.disable} has no caller in Prism, which never disables a shader, so it is reached here: between two
     * flushed draws, disabled and enabled again, which leaves the native current shader as Prism's state expects.
     */
    private static int[] disableEnable(Session s) throws Exception {
        Texture source = MTLCorpusTextures.argbTexture(s, 24, 20, 3);
        source.setLinearFiltering(false);
        try {
            return s.render(FRAME, FRAME, false, g -> {
                Shader shader = convolveShader(s);
                ShaderGraphics sg = (ShaderGraphics) g;
                sg.setExternalShader(shader);
                shader.setConstant("count", 2);
                shader.setConstant("offset", 0.75f / 24, 0f, 0f, -1.25f / 20);
                FloatBuffer weights = direct(WEIGHTS.length);
                weights.put(WEIGHTS).rewind();
                shader.setConstants("weights", weights, 0, 2);
                sg.drawTexture(source, 3, 4, 27, 24, 0, 0, 24, 20);
                g.sync();
                shader.disable();
                shader.enable();
                sg.drawTexture(source, 31, 37, 55, 57, 0, 0, 24, 20);
                sg.setExternalShader(null);
            });
        } finally {
            source.dispose();
        }
    }

    /** Weights for {@code Shader.setConstants}: the heap path reads from {@code off}, the direct one from position. */
    private record Constants(FloatBuffer weights, int off) {
    }

    static FloatBuffer direct(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /** {@code LinearConvolve_8} as Decora creates it: sampler {@code img} on unit 0. */
    static Shader convolveShader(Session s) {
        return ((ShaderFactory) s.factory()).createShader("LinearConvolve_8", Map.of("img", 0),
                Map.of("offset", 1, "count", 0, "weights", 2), 0, false, false);
    }

    private static int[] convolve(Session s, Supplier<Constants> constants) throws Exception {
        Texture source = MTLCorpusTextures.argbTexture(s, 24, 20, 2);
        source.setLinearFiltering(false);
        try {
            return s.render(FRAME, FRAME, false, g -> {
                Shader shader = convolveShader(s);
                Constants c = constants.get();
                ShaderGraphics sg = (ShaderGraphics) g;
                sg.setExternalShader(shader);
                shader.setConstant("count", 2);
                shader.setConstant("offset", 1f / 24, 0.37f / 20, -2f / 24, 0f);
                shader.setConstants("weights", c.weights(), c.off(), 2);
                sg.drawTexture(source, 5, 7, 53, 47, 0, 0, 24, 20);
                sg.setExternalShader(null);
            });
        } finally {
            source.dispose();
        }
    }

    /**
     * Draws {@code texture}'s region {@code [sx, sy, sx + w, sy + h)} one texel per pixel at {@code (dx, dy)} through
     * {@code LinearConvolve_8} with a single unit weight: a copy that also works for A8 and RGBA32F textures, which
     * the stock texture shaders do not take.
     */
    static void copy(Session s, Graphics g, Texture texture, int dx, int dy, int sx, int sy, int w, int h) {
        Shader shader = convolveShader(s);
        ShaderGraphics sg = (ShaderGraphics) g;
        sg.setExternalShader(shader);
        shader.setConstant("count", 2);
        shader.setConstant("offset", 0f, 0f, 0f, 0f);
        FloatBuffer weights = direct(8);
        weights.put(0, 1f);
        shader.setConstants("weights", weights, 0, 2);
        sg.drawTexture(texture, dx, dy, dx + w, dy + h, sx, sy, sx + w, sy + h);
        sg.setExternalShader(null);
    }

    // ---------------------------------------------------------------------------------------------
    // G: text with Ahem (decision D4)
    // ---------------------------------------------------------------------------------------------

    private static PGFont ahem;

    private static synchronized PGFont ahem() {
        if (ahem == null) {
            PGFont[] fonts = PrismFontFactory.getFontFactory().loadEmbeddedFont(null,
                    MTLGoldens.moduleDirectory().resolve(MTLGoldens.AHEM).normalize().toString(), 13f, false, false);
            ahem = fonts[0];
        }
        return ahem;
    }

    private static void text(Steps steps) {
        if (steps.in(MTLGoldens.POW2)) {
            return;
        }
        steps.add("text.grey.color", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
            g.setPaint(PAINT);
            drawText(g, FontResource.AA_GREYSCALE, "ÉpXa q", 3.25f, 18.5f);
            drawText(g, FontResource.AA_GREYSCALE, "x É", 9f, 44.75f);
        }));
        steps.add("text.grey.gradient", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
            g.setPaint(linear(Gradient.REFLECT));
            drawText(g, FontResource.AA_GREYSCALE, "XpÉXp", 2.5f, 20f);
            g.setPaint(radial(Gradient.PAD));
            drawText(g, FontResource.AA_GREYSCALE, "pX", 21f, 47f);
        }));
        if (!steps.in(MTLGoldens.DEFAULT)) {
            return;
        }
        steps.add("text.grey.transformed", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
            g.translate(4.4f, 7.7f);
            g.scale(1.25f, 0.875f);
            g.setPaint(new Color(0.05f, 0.95f, 0.65f, 0.9f));
            drawText(g, FontResource.AA_GREYSCALE, "ÉXp", 1f, 20f);
        }));
        // prism.lcdtext is false on macOS, so an LCD strike is created greyscale and the LCD shaders never run.
        steps.add("text.lcdRequested.color", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
            g.setPaint(new Color(0.95f, 0.85f, 0.1f, 1f));
            drawText(g, FontResource.AA_LCD, "XpÉ x", 3.5f, 22.25f);
        }));
        steps.add("text.lcdRequested.msaa", FRAME, FRAME, s -> s.render(FRAME, FRAME, true, g -> {
            g.setPaint(new Color(0.15f, 0.85f, 0.95f, 1f));
            drawText(g, FontResource.AA_LCD, "pÉX", 6.75f, 41.5f);
        }));
        steps.add("text.grey.alphaTest", SHADER_FRAME, SHADER_FRAME, s -> shaderFrame(s, true, g -> {
            g.setPaint(PAINT);
            drawText(g, FontResource.AA_GREYSCALE, "pXÉ", 2f, 20f);
        }));
    }

    static void drawText(Graphics g, int aaMode, String text, float x, float y) {
        FontStrike strike = ahem().getStrike(BaseTransform.IDENTITY_TRANSFORM, aaMode);
        g.drawString(new Glyphs(strike, text), strike, x, y, null, 0, 0);
    }

    /** A single-line glyph run laid out by advance; like a {@code TextRun}, it has a position past the last glyph. */
    private static final class Glyphs implements GlyphList {
        private final int[] codes;
        private final float[] positions;
        private final float width;
        private final float height;

        Glyphs(FontStrike strike, String text) {
            codes = new int[text.length()];
            positions = new float[text.length() + 1];
            float x = 0;
            for (int i = 0; i < text.length(); i++) {
                codes[i] = strike.getFontResource().getGlyphMapper().charToGlyph(text.charAt(i));
                positions[i] = x;
                x += strike.getCharAdvance(text.charAt(i));
            }
            positions[text.length()] = x;
            width = x;
            height = strike.getSize();
        }

        @Override
        public int getGlyphCount() {
            return codes.length;
        }

        @Override
        public int getGlyphCode(int glyphIndex) {
            return codes[glyphIndex];
        }

        @Override
        public float getPosX(int glyphIndex) {
            return positions[glyphIndex];
        }

        @Override
        public float getPosY(int glyphIndex) {
            return 0;
        }

        @Override
        public float getWidth() {
            return width;
        }

        @Override
        public float getHeight() {
            return height;
        }

        @Override
        public RectBounds getLineBounds() {
            return new RectBounds(0, -height, width, height * 0.25f);
        }

        @Override
        public Point2D getLocation() {
            return new Point2D(0, 0);
        }

        @Override
        public int getCharOffset(int glyphIndex) {
            return glyphIndex;
        }

        @Override
        public boolean isComplex() {
            return false;
        }

        @Override
        public TextSpan getTextSpan() {
            return null;
        }

        @Override
        public boolean isLinebreak() {
            return false;
        }

        @Override
        public int getStart() {
            return 0;
        }

        @Override
        public int getOffsetAtX(float x, AtomicBoolean trailing) {
            return 0;
        }
    }
}
