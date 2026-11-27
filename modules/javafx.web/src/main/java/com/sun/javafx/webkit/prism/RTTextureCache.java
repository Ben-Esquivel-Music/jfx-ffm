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

import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.ResourceFactoryListener;
import com.sun.prism.Texture;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/*
 * The render textures of disposed images, for new images of the same physical size: WebKit makes a new
 * image for every intermediate surface, flattened tile and mask of every frame, and Prism allocates a
 * render texture on every request. A disposed image's texture is free: an image is disposed only after
 * every command buffer naming it was decoded or dropped, and every DECODERQ of an image's queue is followed
 * in the same render queue by a draw of that image, so nothing decodes that queue after the image is
 * disposed, although the image's WCBufferedContext still holds graphics on the texture. At most 32 MiB
 * stays cached, released about 2 s after its last use; until then the textures are permanent, and Prism's
 * pool cannot reclaim them. Only the render thread offers, takes and purges textures, and Prism notifies
 * factory listeners on the render thread as well.
 */
final class RTTextureCache implements ResourceFactoryListener {

    static final int MAX_TEXTURES = 32;
    static final long MAX_BYTES = 32L << 20;
    static final int MAX_SIDE = 1024;
    static final long MAX_IDLE_NANOS = TimeUnit.SECONDS.toNanos(2);
    static final long PURGE_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    static final RTTextureCache INSTANCE = new RTTextureCache(System::nanoTime, RTTextureCache::onRenderThreadAfter);

    @FunctionalInterface
    interface Scheduler {
        void schedule(long delayNanos, Runnable renderJob);
    }

    private record Entry(RTTexture texture, long offeredAt) {
        long bytes() {
            return RTTextureCache.bytes(texture);
        }
    }

    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private WeakReference<ResourceFactory> factory = null;
    private long bytes = 0;
    private int created = 0;
    private int reused = 0;
    private boolean purgePending = false;

    RTTextureCache(LongSupplier clock, Scheduler scheduler) {
        this.clock = clock;
        this.scheduler = scheduler;
    }

    /** A cleared texture of a disposed image of the same content size, or else a new one. */
    RTTexture obtain(ResourceFactory f, int width, int height) {
        RTTexture texture = take(f, width, height);
        if (texture != null) {
            reused++;
            return texture;
        }
        texture = f.createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED);
        if (texture != null) {
            created++;
        }
        return texture;
    }

    /** Keeps the texture of a disposed image, which was made by the factory, or disposes it. */
    void offer(ResourceFactory f, RTTexture texture) {
        if (f == null || f.isDisposed() || texture.isSurfaceLost()
                || texture.getContentWidth() > MAX_SIDE || texture.getContentHeight() > MAX_SIDE) {
            texture.dispose();
            return;
        }
        use(f);
        long now = clock.getAsLong();
        disposeIdle(now);
        entries.addLast(new Entry(texture, now));
        bytes += bytes(texture);
        while (entries.size() > MAX_TEXTURES || bytes > MAX_BYTES) {
            dispose(entries.removeFirst());
        }
        if (!entries.isEmpty() && !purgePending) {
            schedulePurge(now);
        }
    }

    private void schedulePurge(long now) {
        purgePending = true;
        long oldestIdle = now - entries.getFirst().offeredAt();
        scheduler.schedule(MAX_IDLE_NANOS - oldestIdle + PURGE_MARGIN_NANOS, this::purge);
    }

    private void purge() {
        purgePending = false;
        long now = clock.getAsLong();
        disposeIdle(now);
        if (!entries.isEmpty()) {
            schedulePurge(now);
        }
    }

    private RTTexture take(ResourceFactory f, int width, int height) {
        use(f);
        disposeIdle(clock.getAsLong());
        Iterator<Entry> newestFirst = entries.descendingIterator();
        while (newestFirst.hasNext()) {
            Entry entry = newestFirst.next();
            RTTexture texture = entry.texture();
            if (texture.getContentWidth() == width && texture.getContentHeight() == height) {
                newestFirst.remove();
                bytes -= entry.bytes();
                Graphics g = texture.isSurfaceLost() || f.isDisposed() ? null : texture.createGraphics();
                if (g == null) {
                    texture.dispose();
                } else {
                    RTImage.clearWholeContent(g);
                    return texture;
                }
            }
        }
        return null;
    }

    private void use(ResourceFactory f) {
        if (factory == null || factory.get() != f) {
            disposeAll();
            f.addFactoryListener(this);
            factory = new WeakReference<>(f);
        }
    }

    private void disposeIdle(long now) {
        while (!entries.isEmpty() && now - entries.getFirst().offeredAt() > MAX_IDLE_NANOS) {
            dispose(entries.removeFirst());
        }
    }

    private void dispose(Entry entry) {
        bytes -= entry.bytes();
        entry.texture().dispose();
    }

    private void disposeAll() {
        while (!entries.isEmpty()) {
            dispose(entries.removeFirst());
        }
    }

    static void onRenderThreadAfter(long delayNanos, Runnable renderJob) {
        CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS)
                .execute(() -> postToRenderThread(PrismInvoker::invokeOnRenderThread, renderJob));
    }

    static void postToRenderThread(Consumer<Runnable> poster, Runnable renderJob) {
        try {
            poster.accept(renderJob);
        } catch (RejectedExecutionException toolkitExited) {
            // The render thread has gone with the toolkit, and nothing can draw or purge any more.
        }
    }

    private static long bytes(RTTexture texture) {
        return 4L * texture.getContentWidth() * texture.getContentHeight();
    }

    @Override
    public void factoryReset() {
        disposeAll();
    }

    @Override
    public void factoryReleased() {
        disposeAll();
    }

    int created() {
        return created;
    }

    int reused() {
        return reused;
    }

    int cachedTextures() {
        return entries.size();
    }

    long cachedBytes() {
        return bytes;
    }
}
