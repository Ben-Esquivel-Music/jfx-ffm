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

package com.sun.javafx.webkit.prism;

import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.ResourceFactoryListener;
import com.sun.prism.paint.Color;
import com.sun.webkit.graphics.WCImage;
import com.sun.webkit.graphics.WCPath;
import java.nio.IntBuffer;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.ObjLongConsumer;

public final class RTImageShim {

    private RTImageShim() {
    }

    /** An image as WebKit's image buffers make them, of any pixel scale. */
    public static WCImage create(int width, int height, float pixelScale) {
        return new RTImage(width, height, pixelScale);
    }

    /** Fills a rectangle of the image's render texture, in physical pixels, on the render thread. */
    public static void fillPhysical(WCImage image, int x, int y, int width, int height, Color color) {
        PrismInvoker.runOnRenderThread(() -> {
            Graphics g = ((RTImage) image).getGraphics();
            g.setTransform(BaseTransform.IDENTITY_TRANSFORM);
            g.setPaint(color);
            g.fillRect(x, y, width, height);
        });
    }

    public static void draw(WCImage image, Graphics g, int dstx1, int dsty1, int dstx2, int dsty2,
            int srcx1, int srcy1, int srcx2, int srcy2) {
        ((RTImage) image).draw(g, dstx1, dsty1, dstx2, dsty2, srcx1, srcy1, srcx2, srcy2);
    }

    public static IntBuffer readContent(RTTexture texture) {
        return RTImage.readContent(texture);
    }

    /** Posts the draw of the image's pixel buffer into its texture to the render thread, as WebKit does. */
    public static void drawPixelBuffer(WCImage image) {
        ((RTImage) image).drawPixelBuffer();
    }

    /** The image's render texture, made if it has none; to be called on the render thread. */
    public static RTTexture renderTexture(WCImage image) {
        return (RTTexture) ((RTImage) image).getGraphics().getRenderTarget();
    }

    public static void dispose(WCImage image) {
        ((RTImage) image).dispose();
    }

    public static WCPath createPath() {
        return new WCPathImpl();
    }

    /** Render textures the images made since the process started; read on the render thread. */
    public static int texturesCreated() {
        return onRenderThread(RTTextureCache.INSTANCE::created);
    }

    /** Render textures of disposed images that new images took; read on the render thread. */
    public static int texturesReused() {
        return onRenderThread(RTTextureCache.INSTANCE::reused);
    }

    public static int cachedTextures() {
        return onRenderThread(RTTextureCache.INSTANCE::cachedTextures);
    }

    /** Disposes every texture the shared cache holds, as a reset of the factory does, on the render thread. */
    public static void emptySharedCache() {
        PrismInvoker.runOnRenderThread(RTTextureCache.INSTANCE::factoryReset);
    }

    /**
     * A cache of its own, on the clock given, to be used on the render thread only; the scheduler gets the
     * render-thread job of each purge and its delay in nanoseconds.
     */
    public static ResourceFactoryListener createCache(LongSupplier clock, ObjLongConsumer<Runnable> scheduler) {
        return new RTTextureCache(clock, (delayNanos, renderJob) -> scheduler.accept(renderJob, delayNanos));
    }

    /** Runs the job on the render thread after the delay, as the shared cache schedules its purges. */
    public static void scheduleOnRenderThread(long delayNanos, Runnable renderJob) {
        RTTextureCache.onRenderThreadAfter(delayNanos, renderJob);
    }

    public static void postToRenderThread(Consumer<Runnable> poster, Runnable renderJob) {
        RTTextureCache.postToRenderThread(poster, renderJob);
    }

    public static RTTexture obtain(ResourceFactoryListener cache, ResourceFactory factory, int width, int height) {
        return ((RTTextureCache) cache).obtain(factory, width, height);
    }

    public static void offer(ResourceFactoryListener cache, ResourceFactory factory, RTTexture texture) {
        ((RTTextureCache) cache).offer(factory, texture);
    }

    public static int texturesCreated(ResourceFactoryListener cache) {
        return ((RTTextureCache) cache).created();
    }

    public static int texturesReused(ResourceFactoryListener cache) {
        return ((RTTextureCache) cache).reused();
    }

    public static int cachedTextures(ResourceFactoryListener cache) {
        return ((RTTextureCache) cache).cachedTextures();
    }

    public static long cachedBytes(ResourceFactoryListener cache) {
        return ((RTTextureCache) cache).cachedBytes();
    }

    private static <T> T onRenderThread(Callable<T> read) {
        return PrismInvoker.callOnRenderThread(read);
    }
}
