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

import com.sun.prism.Graphics;
import com.sun.prism.MaskTextureGraphics;
import com.sun.prism.PixelFormat;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import test.com.sun.prism.mtl.MTLGoldenChild.Session;
import test.com.sun.prism.mtl.MTLGoldenChild.Steps;

/**
 * The texture corpus: sampling and wrap modes, uploads of every pixel format from heap and direct buffers, render
 * targets with MSAA, blits and both readbacks, and the special shaders only textures reach. A step only ever samples
 * texels it uploaded: texture memory outside an upload, and RTT padding under {@code -Dprism.forcepowerof2}, is
 * undefined.
 */
final class MTLCorpusTextures {

    private static final int FRAME = 64;
    private static final Texture.WrapMode[] WRAPS = {Texture.WrapMode.CLAMP_NOT_NEEDED,
        Texture.WrapMode.CLAMP_TO_EDGE, Texture.WrapMode.REPEAT, Texture.WrapMode.CLAMP_TO_ZERO};

    private MTLCorpusTextures() {
    }

    static void register(Steps steps) {
        sampling(steps);
        uploads(steps);
        targets(steps);
        specials(steps);
    }

    /**
     * Mirrors {@code RegionImageCache}: Prism allows the super shader only once a region texture and a glyph texture
     * are registered, and every real scene registers a region texture early.
     */
    static void installRegionTexture(ResourceFactory factory) {
        RTTexture store = factory.createRTTexture(2048, 1024, Texture.WrapMode.CLAMP_TO_ZERO);
        store.contentsUseful();
        store.makePermanent();
        factory.setRegionTexture(store);
        Graphics g = store.createGraphics();
        g.clear(Color.TRANSPARENT);
        g.setPaint(MTLCorpus2D.linear(Gradient.REPEAT));
        g.fillRoundRect(1, 2, 37, 26, 9, 7);
        g.setPaint(MTLCorpus2D.PAINT);
        g.fillEllipse(20, 11, 17, 14);
    }

    /** A {@code width x height} INT_ARGB_PRE texture, fully uploaded with pattern {@code variant}. */
    static Texture argbTexture(Session s, int width, int height, int variant) {
        return argbTexture(s, width, height, variant, Texture.WrapMode.CLAMP_TO_EDGE);
    }

    static Texture argbTexture(Session s, int width, int height, int variant, Texture.WrapMode wrap) {
        Texture texture = s.factory().createTexture(PixelFormat.INT_ARGB_PRE, Texture.Usage.DEFAULT, wrap,
                width, height);
        int[] pixels = argb(width, height, variant);
        texture.update(IntBuffer.wrap(pixels), PixelFormat.INT_ARGB_PRE, 0, 0, 0, 0, width, height, width * 4,
                true);
        return texture;
    }

    static int[] argb(int width, int height, int variant) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = 255 - ((x * 7 + y * 3 + variant * 50) % 96);
                int r = (x * 31 + variant * 90) % (a + 1);
                int g = (y * 23 + x * 5) % (a + 1);
                int b = (x * y * 13 + variant * 40) % (a + 1);
                pixels[y * width + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return pixels;
    }

    // ---------------------------------------------------------------------------------------------
    // T: sampling
    // ---------------------------------------------------------------------------------------------

    private static void sampling(Steps steps) {
        if (!steps.in(MTLGoldens.DEFAULT, MTLGoldens.NO_SUPER_SHADER)) {
            return;
        }
        for (boolean linear : new boolean[] {false, true}) {
            List<String> keys = new ArrayList<>();
            for (Texture.WrapMode wrap : WRAPS) {
                String key = "sample." + (linear ? "linear." : "nearest.") + wrap.name();
                keys.add(key);
                steps.add(key, FRAME, FRAME, s -> {
                    Texture texture = argbTexture(s, 8, 8, 3, wrap);
                    texture.setLinearFiltering(linear);
                    try {
                        return s.render(FRAME, FRAME, false, g -> g.drawTexture(texture, 4, 3, 61, 59,
                                -6.5f, -5.25f, 14.75f, 13.5f));
                    } finally {
                        texture.dispose();
                    }
                });
            }
            // CLAMP_NOT_NEEDED leaves Metal's default address mode, which is clamp-to-edge.
            steps.designedEqual(keys.get(0), keys.get(1));
            steps.designedDistinct(keys.subList(1, keys.size()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // X: uploads
    // ---------------------------------------------------------------------------------------------

    /** Where an upload lands and what it reads: destination, source origin, size and row stride in bytes. */
    private record Region(int dstx, int dsty, int srcx, int srcy, int w, int h, int scan) {
    }

    private static final Region INT_REGION = new Region(2, 1, 3, 2, 9, 7, 16 * 4);

    private static void uploads(Steps steps) {
        boolean all = steps.in(MTLGoldens.DEFAULT);
        boolean noSuper = steps.in(MTLGoldens.NO_SUPER_SHADER);
        boolean pow2 = steps.in(MTLGoldens.POW2);
        if (!all && !noSuper && !pow2) {
            return;
        }
        upload(steps, "upload.int.heap", PixelFormat.INT_ARGB_PRE, 13, 9, INT_REGION, false, all || noSuper || pow2);
        upload(steps, "upload.int.direct", PixelFormat.INT_ARGB_PRE, 13, 9, INT_REGION, true, all || noSuper);
        if (all) {
            steps.designedEqual("upload.int.heap", "upload.int.direct");
            steps.add("upload.int.heapSlice", FRAME, FRAME, s -> {
                int[] backing = intSource(INT_REGION, 2);
                IntBuffer slice = IntBuffer.wrap(backing, 21, backing.length - 21).slice();
                return drawUpload(s, PixelFormat.INT_ARGB_PRE, 13, 9, INT_REGION, slice);
            });
        }
        Region bgra = new Region(4, 3, 1, 2, 7, 5, 11 * 4);
        upload(steps, "upload.bgra.heap", PixelFormat.BYTE_BGRA_PRE, 12, 10, bgra, false, all || noSuper);
        upload(steps, "upload.bgra.direct", PixelFormat.BYTE_BGRA_PRE, 12, 10, bgra, true, all || pow2);
        Region alpha = new Region(3, 5, 2, 1, 11, 6, 13);
        upload(steps, "upload.alpha.heap", PixelFormat.BYTE_ALPHA, 17, 13, alpha, false, all || noSuper || pow2);
        upload(steps, "upload.alpha.direct", PixelFormat.BYTE_ALPHA, 17, 13, alpha, true, all);
        Region xyzw = new Region(1, 4, 2, 3, 6, 5, 9 * 16);
        upload(steps, "upload.float.heap", PixelFormat.FLOAT_XYZW, 10, 11, xyzw, false, all || pow2);
        upload(steps, "upload.float.direct", PixelFormat.FLOAT_XYZW, 10, 11, xyzw, true, all || noSuper);
        // Java converts 3- and 1-byte formats from offset 0 with a packed row stride, ignoring srcx, srcy and
        // srcscan; the C then offsets into the converted array by srcx and srcy, so those must stay 0 here.
        Region packed = new Region(5, 2, 0, 0, 8, 6, 8 * 3 + 5);
        upload(steps, "upload.rgb.heap", PixelFormat.BYTE_RGB, 15, 9, packed, false, all || noSuper);
        upload(steps, "upload.rgb.direct", PixelFormat.BYTE_RGB, 15, 9, packed, true, all);
        Region gray = new Region(2, 6, 0, 0, 9, 4, 9 + 3);
        upload(steps, "upload.gray.heap", PixelFormat.BYTE_GRAY, 12, 11, gray, false, all || noSuper);
        upload(steps, "upload.gray.direct", PixelFormat.BYTE_GRAY, 12, 11, gray, true, all);
        if (all) {
            steps.designedEqual("upload.bgra.heap", "upload.bgra.direct");
            steps.designedEqual("upload.alpha.heap", "upload.alpha.direct");
            steps.designedEqual("upload.float.heap", "upload.float.direct");
            steps.designedEqual("upload.rgb.heap", "upload.rgb.direct");
            steps.designedEqual("upload.gray.heap", "upload.gray.direct");
        }
        if (all || noSuper || pow2) {
            steps.add("upload.apple422", FRAME, FRAME, s -> mediaFrame(s, MTLTestMediaFrame.apple422(16, 8, 40),
                    false));
        }
        if (all || pow2) {
            steps.add("upload.yuv420.threePlanes", FRAME, FRAME,
                    s -> mediaFrame(s, MTLTestMediaFrame.ycbcr420(16, 12, false), false));
        }
        if (all || noSuper) {
            steps.add("upload.yuv420.fourPlanes", FRAME, FRAME,
                    s -> mediaFrame(s, MTLTestMediaFrame.ycbcr420(16, 12, true), false));
        }
        if (all) {
            steps.add("upload.yuv420.alphaTest", FRAME, FRAME,
                    s -> mediaFrame(s, MTLTestMediaFrame.ycbcr420(16, 12, true), true));
        }
        if (all || noSuper) {
            steps.add("upload.mipmap.quarter", FRAME, FRAME, s -> {
                Texture texture = s.factory().createTexture(PixelFormat.INT_ARGB_PRE, Texture.Usage.DEFAULT,
                        Texture.WrapMode.CLAMP_TO_EDGE, 64, 64, true);
                texture.update(IntBuffer.wrap(argb(64, 64, 4)), PixelFormat.INT_ARGB_PRE, 0, 0, 0, 0, 64, 64,
                        64 * 4, true);
                texture.setLinearFiltering(true);
                try {
                    return s.render(FRAME, FRAME, false, g -> {
                        g.drawTexture(texture, 3, 5, 19, 21, 0, 0, 64, 64);
                        g.drawTexture(texture, 24, 9, 60, 57, 0, 0, 64, 64);
                    });
                } finally {
                    texture.dispose();
                }
            });
        }
        if (!all) {
            return;
        }
        steps.add("upload.mipmap.oneByOne", FRAME, FRAME, s -> {
            Texture texture = s.factory().createTexture(PixelFormat.INT_ARGB_PRE, Texture.Usage.DEFAULT,
                    Texture.WrapMode.CLAMP_TO_EDGE, 1, 1, true);
            texture.update(IntBuffer.wrap(new int[] {0xd07a4c21}), PixelFormat.INT_ARGB_PRE, 0, 0, 0, 0, 1, 1, 4,
                    true);
            try {
                return s.render(FRAME, FRAME, false, g -> g.drawTexture(texture, 13, 7, 33, 27, 0, 0, 1, 1));
            } finally {
                texture.dispose();
            }
        });
        for (boolean direct : new boolean[] {false, true}) {
            steps.add("upload.large." + (direct ? "direct" : "heap"), FRAME, FRAME, s -> {
                int width = 2560;
                int height = 2048;
                Texture texture = s.factory().createTexture(PixelFormat.INT_ARGB_PRE, Texture.Usage.DEFAULT,
                        Texture.WrapMode.CLAMP_TO_EDGE, width, height);
                int[] pixels = new int[width * height];
                for (int i = 0; i < pixels.length; i++) {
                    int hash = i * 0x9E3779B1;
                    pixels[i] = 0xff000000 | (hash >>> 8);
                }
                IntBuffer buffer = direct ? directInts(pixels) : IntBuffer.wrap(pixels);
                texture.update(buffer, PixelFormat.INT_ARGB_PRE, 0, 0, 0, 0, width, height, width * 4, true);
                texture.setLinearFiltering(false);
                try {
                    return s.render(FRAME, FRAME, false, g -> {
                        g.drawTexture(texture, 0, 0, 32, 64, 1201, 977, 1233, 1041);
                        g.drawTexture(texture, 32, 0, 64, 64, 2528, 1984, 2560, 2048);
                    });
                } finally {
                    texture.dispose();
                }
            });
        }
        steps.designedEqual("upload.large.heap", "upload.large.direct");
        steps.add("upload.partial", FRAME, FRAME, s -> {
            Texture texture = argbTexture(s, 24, 20, 5);
            texture.update(IntBuffer.wrap(argb(11, 9, 6)), PixelFormat.INT_ARGB_PRE, 5, 4, 0, 0, 11, 9, 11 * 4,
                    true);
            texture.setLinearFiltering(false);
            try {
                return s.render(FRAME, FRAME, false, g -> g.drawTexture(texture, 7, 9, 31, 29, 0, 0, 24, 20));
            } finally {
                texture.dispose();
            }
        });
    }

    private static void upload(Steps steps, String key, PixelFormat format, int width, int height, Region region,
                               boolean direct, boolean included) {
        if (included) {
            steps.add(key, FRAME, FRAME, s -> drawUpload(s, format, width, height, region,
                    source(format, region, direct)));
        }
    }

    /**
     * Uploads {@code buffer} into a fresh texture and draws exactly the uploaded texels, one per pixel. Buffers carry
     * a non-zero position: the JNI passes the base address and ignores it (R2).
     */
    private static int[] drawUpload(Session s, PixelFormat format, int width, int height, Region r, Buffer buffer)
            throws Exception {
        Texture texture = s.factory().createTexture(format, Texture.Usage.DEFAULT, Texture.WrapMode.CLAMP_TO_EDGE,
                width, height);
        texture.update(buffer, format, r.dstx(), r.dsty(), r.srcx(), r.srcy(), r.w(), r.h(), r.scan(), true);
        texture.setLinearFiltering(false);
        try {
            return s.render(FRAME, FRAME, false, g -> {
                for (int copy = 0; copy < 2; copy++) {
                    int dx = 6 + copy * 29;
                    int dy = 5 + copy * 23;
                    if (format == PixelFormat.BYTE_ALPHA || format == PixelFormat.FLOAT_XYZW) {
                        MTLCorpus2D.copy(s, g, texture, dx, dy, r.dstx(), r.dsty(), r.w(), r.h());
                    } else {
                        g.drawTexture(texture, dx, dy, dx + r.w(), dy + r.h(), r.dstx(), r.dsty(),
                                r.dstx() + r.w(), r.dsty() + r.h());
                    }
                }
            });
        } finally {
            texture.dispose();
        }
    }

    private static Buffer source(PixelFormat format, Region r, boolean direct) {
        int rows = r.srcy() + r.h() + 2;
        return switch (format) {
            case INT_ARGB_PRE -> {
                int[] values = intSource(r, 0);
                IntBuffer buffer = direct ? directInts(values) : IntBuffer.wrap(values);
                yield buffer.position(5);
            }
            case FLOAT_XYZW -> {
                float[] values = new float[r.scan() / 4 * rows];
                for (int i = 0; i < values.length; i++) {
                    values[i] = ((i * 37) % 251) / 255f;
                }
                FloatBuffer buffer = direct ? MTLCorpus2D.direct(values.length).put(values) : FloatBuffer.wrap(values);
                yield buffer.position(3);
            }
            default -> {
                byte[] values = new byte[r.scan() * rows];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (byte) ((i * 53 + 17) % 256);
                }
                if (format == PixelFormat.BYTE_BGRA_PRE) {
                    for (int i = 3; i < values.length; i += 4) {
                        values[i] = (byte) 0xff;
                    }
                }
                ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(values.length).put(values)
                        : ByteBuffer.wrap(values);
                yield buffer.position(7);
            }
        };
    }

    private static int[] intSource(Region r, int variant) {
        int stride = r.scan() / 4;
        int rows = r.srcy() + r.h() + 2;
        return argb(stride, rows, variant);
    }

    static IntBuffer directInts(int[] values) {
        IntBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        buffer.put(values).rewind();
        return buffer;
    }

    private static int[] mediaFrame(Session s, MTLTestMediaFrame frame, boolean alphaTest) throws Exception {
        Texture texture = s.factory().createTexture(frame);
        texture.update(frame, true);
        texture.setLinearFiltering(false);
        try {
            return s.renderUncleared(FRAME, FRAME, false, g -> {
                MTLCorpus2D.depthClear(g, alphaTest);
                g.drawTexture(texture, 4, 6, 4 + frame.getWidth() * 3, 6 + frame.getHeight() * 3,
                        0, 0, frame.getWidth(), frame.getHeight());
            });
        } finally {
            texture.dispose();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // R: render targets, MSAA, blits and readback
    // ---------------------------------------------------------------------------------------------

    private static void targets(Steps steps) {
        if (steps.in(MTLGoldens.DEFAULT)) {
            steps.add("rtt.msaa.resolve", FRAME, FRAME, s -> s.render(FRAME, FRAME, true, MTLCorpusTextures::scene));
            steps.add("rtt.depth.lazy", FRAME, FRAME, s -> s.render(FRAME, FRAME, false, g -> {
                scene(g);
                g.setDepthBuffer(true);
                g.setDepthTest(true);
                g.setTransform3D(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, -6);
                g.setPaint(new Color(0.9f, 0.9f, 0.1f, 1f));
                g.fillRect(10, 10, 30, 30);
                g.setTransform3D(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 4);
                g.setPaint(new Color(0.1f, 0.2f, 0.9f, 1f));
                g.fillRect(24, 22, 33, 31);
            }));
            steps.add("rtt.switch.aba", FRAME, FRAME, s -> {
                RTTexture a = s.factory().createRTTexture(FRAME, FRAME, Texture.WrapMode.CLAMP_NOT_NEEDED);
                RTTexture b = s.factory().createRTTexture(FRAME, FRAME, Texture.WrapMode.CLAMP_NOT_NEEDED);
                try {
                    Graphics ga = a.createGraphics();
                    ga.clear(MTLCorpus2D.BACKGROUND);
                    ga.setPaint(MTLCorpus2D.PAINT);
                    ga.fillRect(3, 4, 30, 25);
                    Graphics gb = b.createGraphics();
                    gb.clear(Color.WHITE);
                    gb.setPaint(MTLCorpus2D.radial(Gradient.REFLECT));
                    gb.fillRect(0, 0, 64, 64);
                    Graphics again = a.createGraphics();
                    again.setPaint(new Color(0.2f, 0.95f, 0.5f, 0.75f));
                    again.fillEllipse(20, 18, 39, 33);
                    again.drawTexture(b, 40, 2, 62, 16, 5, 7, 27, 21);
                    return a.getPixels().clone();
                } finally {
                    a.dispose();
                    b.dispose();
                }
            });
            steps.add("rtt.disposeCurrent", FRAME, FRAME, s -> {
                RTTexture kept = s.factory().createRTTexture(FRAME, FRAME, Texture.WrapMode.CLAMP_NOT_NEEDED);
                try {
                    Graphics g = kept.createGraphics();
                    g.clear(MTLCorpus2D.BACKGROUND);
                    scene(g);
                    RTTexture current = s.factory().createRTTexture(32, 32, Texture.WrapMode.CLAMP_NOT_NEEDED);
                    Graphics gc = current.createGraphics();
                    gc.clear(Color.BLACK);
                    gc.setPaint(MTLCorpus2D.PAINT);
                    gc.fillRect(1, 2, 20, 21);
                    current.dispose();
                    return kept.getPixels().clone();
                } finally {
                    kept.dispose();
                }
            });
            blit(steps, "rtt.blit.whole", FRAME, FRAME, false, 0, 0, 64, 64, 0, 0, 64, 64);
            // The native ignores the coordinates and copies the whole texture (L11), so this equals the whole blit.
            blit(steps, "rtt.blit.subRect", FRAME, FRAME, false, 8, 8, 40, 40, 16, 4, 48, 36);
            steps.designedEqual("rtt.blit.whole", "rtt.blit.subRect");
            blit(steps, "rtt.blit.msaaToPlain", FRAME, FRAME, true, 0, 0, 64, 64, 0, 0, 64, 64);
            steps.add("rtt.read.getPixels", FRAME, FRAME, s -> read(s, FRAME, FRAME, false));
            steps.add("rtt.read.directIntBuffer", FRAME, FRAME, s -> read(s, FRAME, FRAME, true));
            steps.designedEqual("rtt.read.getPixels", "rtt.read.directIntBuffer");
            steps.add("rtt.read.nonSquare", 48, 32, s -> read(s, 48, 32, false));
        }
        if (steps.in(MTLGoldens.POW2)) {
            steps.add("rtt.read.getPixels", 48, 40, s -> read(s, 48, 40, false));
            // The contiguous copy reads physical rows as content rows (L7): pinned, not equal to getPixels here.
            steps.add("rtt.read.directIntBuffer", 48, 40, s -> read(s, 48, 40, true));
            steps.add("rtt.msaa.resolve", 48, 40, s -> s.render(48, 40, true, MTLCorpusTextures::scene));
            blit(steps, "rtt.blit.whole", 48, 40, false, 0, 0, 48, 40, 0, 0, 48, 40);
        }
    }

    static void scene(Graphics g) {
        g.setPaint(MTLCorpus2D.PAINT);
        g.fillEllipse(5, 7, 41, 29);
        g.setPaint(MTLCorpus2D.linear(Gradient.REPEAT));
        g.fillRoundRect(20, 18, 39, 33, 11, 7);
        g.setPaint(new Color(0.05f, 0.9f, 0.75f, 0.6f));
        g.fill(MTLCorpus2D.triangle());
    }

    private static void blit(Steps steps, String key, int width, int height, boolean msaaSource, int sx0, int sy0,
                             int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        steps.add(key, width, height, s -> {
            RTTexture source = s.factory().createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED,
                    msaaSource);
            RTTexture target = s.factory().createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED);
            try {
                Graphics gs = source.createGraphics();
                gs.clear(MTLCorpus2D.BACKGROUND);
                scene(gs);
                Graphics gt = target.createGraphics();
                gt.clear(Color.WHITE);
                gt.blit(source, target, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
                return target.getPixels().clone();
            } finally {
                source.dispose();
                target.dispose();
            }
        });
    }

    private static int[] read(Session s, int width, int height, boolean directBuffer) {
        RTTexture target = s.factory().createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED);
        try {
            Graphics g = target.createGraphics();
            g.clear(MTLCorpus2D.BACKGROUND);
            scene(g);
            if (!directBuffer) {
                return target.getPixels().clone();
            }
            IntBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            g.sync();
            target.readPixels(pixels);
            int[] values = new int[width * height];
            pixels.get(0, values);
            return values;
        } finally {
            target.dispose();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // S: the special shaders reached through textures
    // ---------------------------------------------------------------------------------------------

    private static void specials(Steps steps) {
        if (!steps.in(MTLGoldens.DEFAULT)) {
            return;
        }
        steps.add("special.textureRGB.alphaTest", 32, 32, s -> {
            Texture texture = argbTexture(s, 8, 8, 7);
            try {
                return MTLCorpus2D.shaderFrame(s, true, g -> g.drawTexture(texture, 3, 2, 29, 27, 0, 0, 8, 8));
            } finally {
                texture.dispose();
            }
        });
        for (boolean alphaTest : new boolean[] {false, true}) {
            steps.add("special.maskTextureRGB" + (alphaTest ? ".alphaTest" : ""), 32, 32, s -> {
                RTTexture image = s.factory().createRTTexture(32, 32, Texture.WrapMode.CLAMP_NOT_NEEDED);
                RTTexture mask = s.factory().createRTTexture(32, 32, Texture.WrapMode.CLAMP_NOT_NEEDED);
                try {
                    Graphics gi = image.createGraphics();
                    gi.clear(MTLCorpus2D.BACKGROUND);
                    scene(gi);
                    Graphics gm = mask.createGraphics();
                    gm.clear(Color.TRANSPARENT);
                    gm.setPaint(new Color(1f, 1f, 1f, 0.7f));
                    gm.fillEllipse(2, 5, 25, 19);
                    return MTLCorpus2D.shaderFrame(s, alphaTest, g -> ((MaskTextureGraphics) g)
                            .drawPixelsMasked(image, mask, 3, 2, 26, 27, 1, 2, 4, 1));
                } finally {
                    image.dispose();
                    mask.dispose();
                }
            });
            steps.add("special.superShader" + (alphaTest ? ".alphaTest" : ""), 32, 32,
                    s -> MTLCorpus2D.shaderFrame(s, alphaTest, g -> {
                        Texture region = s.factory().getRegionTexture();
                        g.drawTexture(region, 2, 3, 30, 29, 1, 2, 37, 28);
                    }));
        }
    }
}
