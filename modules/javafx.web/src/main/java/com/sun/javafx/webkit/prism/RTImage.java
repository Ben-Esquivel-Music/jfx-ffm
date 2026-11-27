/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.javafx.webkit.prism;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.logging.PlatformLogger;
import com.sun.prism.CompositeMode;
import com.sun.prism.Graphics;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.Image;
import com.sun.prism.PixelFormat;
import com.sun.prism.PrinterGraphics;
import com.sun.prism.RTTexture;
import com.sun.prism.RenderTarget;
import com.sun.prism.ResourceFactory;
import com.sun.prism.ResourceFactoryListener;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Paint;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * @author Alexey.Ushakov
 */
final class RTImage extends PrismImage implements ResourceFactoryListener {
    private RTTexture txt;
    private final int width, height;
    private WeakReference<ResourceFactory> registeredWithFactory = null;
    private ByteBuffer pixelBuffer;
    private float pixelScale;

    private final static PlatformLogger log =
            PlatformLogger.getLogger(RTImage.class.getName());

    private enum DrawPath { SKIP, DIRECT, COPY }

    RTImage(int w, int h, float pixelScale) {
        if (Float.isNaN(pixelScale) || pixelScale <= 0 ||
                Math.ceil(pixelScale) >= Integer.MAX_VALUE) {

            throw new IllegalArgumentException("pixelScale out of range");
        }

        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("image size must be positive");
        }

        final int ps = (int) Math.ceil(pixelScale);

        final int scale = Math.max(ps, 4);
        final int maxSize = Integer.MAX_VALUE / scale;

        if (maxSize / w <= h) {
            throw new IllegalArgumentException("image size out of range");
        }

        width = w;
        height = h;
        this.pixelScale = pixelScale;
    }

    @Override
    Image getImage() {
         return Image.fromByteBgraPreData(
                getPixelBuffer(),
                getWidth(), getHeight());
    }

    @Override
    Graphics getGraphics() {
        RTTexture texture = getTexture();
        if (texture == null) {
            return null;
        }
        Graphics g = texture.createGraphics();
        g.transform(PrismGraphicsManager.getPixelScaleTransform());
        return g;
    }

    private RTTexture getTexture() {
        if (txt != null && txt.isSurfaceLost()) {
            log.fine("RTImage::getTexture : surface lost: " + this);
        }

        ResourceFactory f = GraphicsPipeline.getDefaultResourceFactory();
        if (f == null || f.isDisposed()) {
            log.fine("RTImage::getTexture : return null because device disposed or not ready");
            return null;
        }

        if (txt == null) {
            txt = RTTextureCache.INSTANCE.obtain(f,
                    (int) Math.ceil(width * pixelScale),
                    (int) Math.ceil(height * pixelScale));
            if (txt == null) {
                log.fine("RTImage::getTexture : return null because rt texture not allocated");
                return null;
            }
            txt.contentsUseful();
            txt.makePermanent();
            if (registeredWithFactory == null || registeredWithFactory.get() != f) {
                f.addFactoryListener(this);
                registeredWithFactory = new WeakReference<>(f);
            }
        }
        return txt;
    }

    @Override
    void draw(Graphics g,
            int dstx1, int dsty1, int dstx2, int dsty2,
            int srcx1, int srcy1, int srcx2, int srcy2)
    {
        // Because this method can be called by the Printer there is a chance
        // these checks will run on a thread other than QuantumRenderer. To prevent
        // any mishaps run the checks on render thread.
        final DrawPath path = PrismInvoker.callOnRenderThread(() -> {
            if (txt == null && g.getCompositeMode() == CompositeMode.SRC_OVER) {
                return DrawPath.SKIP;
            }
            ResourceFactory factory = g.getResourceFactory();
            if (factory.isDisposed()) {
                log.fine("RTImage::draw : skip because device has been disposed");
                return DrawPath.SKIP;
            }
            if (g instanceof PrinterGraphics || (txt != null && !factory.isCompatibleTexture(txt))) {
                return DrawPath.COPY;
            }
            return DrawPath.DIRECT;
        });

        if (path == DrawPath.COPY) {
            final Image[] copy = new Image[1];
            final boolean[] hasTexture = new boolean[1];
            PrismInvoker.runOnRenderThread(() -> {
                RTTexture tex = getTexture();
                hasTexture[0] = tex != null;
                IntBuffer pixels = hasTexture[0] ? readContent(tex) : null;
                if (pixels != null) {
                    pixels.rewind();
                    copy[0] = Image.fromIntArgbPreData(pixels, tex.getContentWidth(), tex.getContentHeight());
                }
            });
            if (!hasTexture[0]) {
                drawTransparent(g, dstx1, dsty1, dstx2, dsty2);
            } else if (copy[0] != null) {
                Texture t = g.getResourceFactory().createTexture(
                        copy[0], Texture.Usage.STATIC, Texture.WrapMode.CLAMP_NOT_NEEDED);
                g.drawTexture(t,
                        dstx1, dsty1, dstx2, dsty2,
                        srcx1 * pixelScale, srcy1 * pixelScale,
                        srcx2 * pixelScale, srcy2 * pixelScale);
                t.dispose();
            }
        } else if (path == DrawPath.DIRECT) {
            if (txt == null) {
                drawTransparent(g, dstx1, dsty1, dstx2, dsty2);
            } else {
                g.drawTexture(txt,
                    dstx1, dsty1, dstx2, dsty2,
                    srcx1 * pixelScale, srcy1 * pixelScale,
                    srcx2 * pixelScale, srcy2 * pixelScale);
            }
        }
    }

    private static void drawTransparent(Graphics g, int dstx1, int dsty1, int dstx2, int dsty2) {
        Paint p = g.getPaint();
        g.setPaint(Color.TRANSPARENT);
        g.fillQuad(dstx1, dsty1, dstx2, dsty2);
        g.setPaint(p);
    }

    /*
     * Every pipeline reads the whole content of the texture, and Metal reads only into a direct buffer;
     * native-order ints are what the others read into an int array.
     */
    static IntBuffer readContent(RTTexture tex) {
        int tw = tex.getContentWidth();
        int th = tex.getContentHeight();
        if (4L * tw * th > Integer.MAX_VALUE) {
            return null;
        }
        IntBuffer pixels = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        Graphics g = tex.createGraphics();
        if (g != null) {
            // Metal's read does not flush the draws Prism still holds in its vertex buffer.
            g.sync();
        }
        tex.readPixels(pixels);
        return pixels;
    }

    static void clearWholeContent(Graphics g) {
        RenderTarget target = g.getRenderTarget();
        // Prism flushes the target's pending draws before a clear only when the clip changed; a clip set here is new.
        g.setClipRect(new Rectangle(0, 0, target.getContentWidth(), target.getContentHeight()));
        g.clear();
    }

    @Override
    void dispose() {
        PrismInvoker.invokeOnRenderThread(() -> {
            if (txt != null) {
                // getTexture makes every texture it gets useful once, a reused one too
                txt.contentsNotUseful();
                RTTextureCache.INSTANCE.offer(registeredWithFactory == null ? null : registeredWithFactory.get(), txt);
                txt = null;
            }
        });
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public ByteBuffer getPixelBuffer() {
        boolean isNew = false;
        if (pixelBuffer == null) {
            pixelBuffer = ByteBuffer.allocateDirect(width*height*4);
            if (pixelBuffer != null) {
                pixelBuffer.order(ByteOrder.nativeOrder());
                isNew = true;
            }
        }
        if (isNew || isDirty()) {
            PrismInvoker.runOnRenderThread(() -> {
                final ResourceFactory f = GraphicsPipeline.getDefaultResourceFactory();
                if (f == null || f.isDisposed()) {
                    log.fine("RTImage::getPixelBuffer : skip because device disposed or not ready");
                    return;
                }
                flushRQ();
                if (txt != null && pixelBuffer != null) {
                    PixelFormat pf = txt.getPixelFormat();
                    if (pf != PixelFormat.INT_ARGB_PRE &&
                        pf != PixelFormat.BYTE_BGRA_PRE) {

                        throw new AssertionError("Unexpected pixel format: " + pf);
                    }

                    RTTexture t = txt;
                    if (pixelScale != 1.0f) {
                        // Convert [txt] to a texture the size of the image
                        t = f.createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED);
                        Graphics g = t.createGraphics();
                        g.drawTexture(txt, 0, 0, width, height,
                                0, 0, width * pixelScale, height * pixelScale);
                    }

                    pixelBuffer.rewind();
                    int[] pixels = t.getPixels();
                    if (pixels != null) {
                        pixelBuffer.asIntBuffer().put(pixels);
                    } else {
                        t.readPixels(pixelBuffer);
                    }

                    if (t != txt) {
                        t.dispose();
                    }
                }
            });
        }
        return pixelBuffer;
    }

    // This method is called from native [ImageBufferData::update]
    // while lazy painting procedure
    @Override
    protected void drawPixelBuffer() {
        PrismInvoker.invokeOnRenderThread(new Runnable() {
            @Override
            public void run() {
                //[g] field can be null if it is the first paint
                //from synthetic ImageData or if the resource factory is disposed
                Graphics g = getGraphics();
                if (g != null && pixelBuffer != null) {
                    pixelBuffer.rewind();//critical!
                    Image img = Image.fromByteBgraPreData(
                            pixelBuffer,
                            width,
                            height);
                    Texture txt = g.getResourceFactory().createTexture(img, Texture.Usage.DEFAULT, Texture.WrapMode.CLAMP_NOT_NEEDED);
                    clearWholeContent(g);
                    g.drawTexture(txt, 0, 0, width, height);
                    txt.dispose();
                }
            }
        });
    }

    @Override public void factoryReset() {
        if (txt != null) {
            txt.dispose();
            txt = null;
        }
    }

    @Override public void factoryReleased() {
        if (txt != null) {
            txt.dispose();
            txt = null;
        }
    }

    @Override
    public float getPixelScale() {
        return pixelScale;
    }
}
