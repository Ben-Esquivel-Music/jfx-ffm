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

package test.com.sun.javafx.webkit.prism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.webkit.prism.PrismInvokerShim;
import com.sun.javafx.webkit.prism.RTImageShim;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.ResourceFactoryListener;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import test.javafx.scene.web.TestBase;

/**
 * The cache of the render textures of disposed images, against a fake resource factory and fake
 * textures that record what is done to them, on the render thread as in production.
 */
public class RTTextureCacheTest extends TestBase {

    private static final long MIB = 1L << 20;
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long MAX_PURGE_LATENESS_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final class FakeTexture implements InvocationHandler {
        private final int width;
        private final int height;
        private final List<String> drawCalls = new ArrayList<>();
        private final List<Rectangle> clips = new ArrayList<>();
        private int disposals = 0;
        private int clears = 0;
        private boolean surfaceLost = false;
        private boolean hasGraphics = true;

        private FakeTexture(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getContentWidth" -> width;
                case "getContentHeight" -> height;
                case "isSurfaceLost" -> surfaceLost;
                case "dispose" -> {
                    disposals++;
                    yield null;
                }
                case "createGraphics" -> {
                    drawCalls.add("createGraphics");
                    yield hasGraphics ? graphics((RTTexture) proxy) : null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        /** A graphics that allows only a clip and a whole clear, as the cache makes on reuse. */
        private Graphics graphics(RTTexture target) {
            return (Graphics) Proxy.newProxyInstance(Graphics.class.getClassLoader(),
                    new Class<?>[] {Graphics.class}, (graphics, call, callArgs) -> {
                        String name = call.getName();
                        if (name.equals("getRenderTarget") && callArgs == null) {
                            return target;
                        }
                        drawCalls.add("graphics." + name);
                        if (name.equals("setClipRect") && callArgs != null && callArgs.length == 1) {
                            clips.add((Rectangle) callArgs[0]);
                        } else if (name.equals("clear") && callArgs == null) {
                            clears++;
                        } else {
                            throw new UnsupportedOperationException(name);
                        }
                        return null;
                    });
        }
    }

    private static final class FakeFactory implements InvocationHandler {
        private final List<ResourceFactoryListener> listeners = new ArrayList<>();
        private final List<FakeTexture> created = new ArrayList<>();
        private boolean disposed = false;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "isDisposed" -> disposed;
                case "addFactoryListener" -> {
                    listeners.add((ResourceFactoryListener) args[0]);
                    yield null;
                }
                case "createRTTexture" -> {
                    FakeTexture texture = new FakeTexture((Integer) args[0], (Integer) args[1]);
                    created.add(texture);
                    yield texture(texture);
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class Clock {
        private long now = 0;
    }

    /** Keeps the purges the cache schedules, and when on the clock each is due, to be run by hand. */
    private static final class Scheduler {
        private final Clock clock;
        private final List<Runnable> jobs = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();
        private final List<Long> dueAt = new ArrayList<>();

        private Scheduler(Clock clock) {
            this.clock = clock;
        }

        private void schedule(Runnable job, long delayNanos) {
            jobs.add(job);
            delays.add(delayNanos);
            dueAt.add(clock.now + delayNanos);
        }
    }

    private record Fixture(ResourceFactoryListener cache, ResourceFactory factory, FakeFactory fake, Clock clock,
            Scheduler scheduler) {
        RTTexture offer(FakeTexture texture) {
            RTTexture offered = texture(texture);
            RTImageShim.offer(cache, factory, offered);
            return offered;
        }

        /** Sets the clock to when the purge last scheduled is due, and runs it. */
        void runTheLastPurge() {
            assertFalse(scheduler.jobs.isEmpty(), "no purge was scheduled");
            clock.now = scheduler.dueAt.getLast();
            scheduler.jobs.getLast().run();
        }
    }

    private static RTTexture texture(FakeTexture texture) {
        return (RTTexture) Proxy.newProxyInstance(RTTexture.class.getClassLoader(),
                new Class<?>[] {RTTexture.class}, texture);
    }

    @Test
    public void aTextureOfTheSameSizeIsReusedCleared() {
        onRenderThread(fixture -> {
            FakeTexture texture = new FakeTexture(10, 10);
            RTTexture offered = fixture.offer(texture);
            assertSame(offered, RTImageShim.obtain(fixture.cache(), fixture.factory(), 10, 10));
            assertEquals(1, texture.clears, "clears of the reused texture");
            assertEquals(0, texture.disposals, "disposals of the reused texture");
            assertEquals(1, RTImageShim.texturesReused(fixture.cache()), "textures reused");
            assertEquals(0, RTImageShim.texturesCreated(fixture.cache()), "textures created");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
            assertEquals(0, RTImageShim.cachedBytes(fixture.cache()), "bytes cached");
        });
    }

    /*
     * A GPU pipeline batches draws and encodes a clear at once. Prism flushes the draws it still holds before
     * a clear when the graphics' clip is not the object its context last validated, and setClipRect stores a
     * copy, so setting the whole-content clip on each reuse forces that flush; the real flush is shown on Metal
     * and ES2 by WebPrismGpuOffscreenTest.
     */
    @Test
    public void eachReuseSetsTheWholeContentClipBeforeItClears() {
        onRenderThread(fixture -> {
            FakeTexture texture = new FakeTexture(10, 20);
            for (int reuse = 0; reuse < 2; reuse++) {
                fixture.offer(texture);
                RTImageShim.obtain(fixture.cache(), fixture.factory(), 10, 20);
            }
            assertEquals(List.of("createGraphics", "graphics.setClipRect", "graphics.clear",
                    "createGraphics", "graphics.setClipRect", "graphics.clear"), texture.drawCalls,
                    "the calls of the two reuses, in order");
            assertEquals(List.of(new Rectangle(0, 0, 10, 20), new Rectangle(0, 0, 10, 20)), texture.clips,
                    "the clips of the two reuses");
        });
    }

    @Test
    public void aTextureOfAnotherSizeIsNotReused() {
        onRenderThread(fixture -> {
            RTTexture offered = fixture.offer(new FakeTexture(10, 10));
            assertNotSame(offered, RTImageShim.obtain(fixture.cache(), fixture.factory(), 10, 11));
            assertNotSame(offered, RTImageShim.obtain(fixture.cache(), fixture.factory(), 11, 10));
            assertEquals(2, RTImageShim.texturesCreated(fixture.cache()), "textures created");
            assertEquals(0, RTImageShim.texturesReused(fixture.cache()), "textures reused");
            assertEquals(1, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
        });
    }

    @Test
    public void theCountCapDisposesTheOldestFirst() {
        onRenderThread(fixture -> {
            List<FakeTexture> offered = new ArrayList<>();
            for (int i = 0; i < 33; i++) {
                offered.add(new FakeTexture(10, 10));
                fixture.offer(offered.getLast());
            }
            assertEquals(32, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
            assertEquals(32 * 400, RTImageShim.cachedBytes(fixture.cache()), "bytes cached");
            assertEquals(1, offered.getFirst().disposals, "disposals of the oldest texture");
            assertTrue(offered.stream().skip(1).allMatch(texture -> texture.disposals == 0),
                    "a texture other than the oldest was disposed");
        });
    }

    @Test
    public void theByteCapDisposesTheOldestFirst() {
        onRenderThread(fixture -> {
            List<FakeTexture> offered = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                offered.add(new FakeTexture(1024, 1024));
                fixture.offer(offered.getLast());
            }
            assertEquals(8, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
            assertEquals(32 * MIB, RTImageShim.cachedBytes(fixture.cache()), "bytes cached");
            assertEquals(1, offered.getFirst().disposals, "disposals of the oldest texture");
            assertTrue(offered.stream().skip(1).allMatch(texture -> texture.disposals == 0),
                    "a texture other than the oldest was disposed");
        });
    }

    @Test
    public void aTextureLargerThan1024PixelsEitherWayIsDisposed() {
        onRenderThread(fixture -> {
            FakeTexture wide = new FakeTexture(1025, 1);
            FakeTexture tall = new FakeTexture(1, 1025);
            fixture.offer(wide);
            fixture.offer(tall);
            assertEquals(1, wide.disposals, "disposals of the wide texture");
            assertEquals(1, tall.disposals, "disposals of the tall texture");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
        });
    }

    @Test
    public void aTextureIdleForMoreThanTwoSecondsIsDisposedAtTheNextOfferOrTake() {
        onRenderThread(fixture -> {
            FakeTexture first = new FakeTexture(10, 10);
            fixture.offer(first);
            fixture.clock().now = IDLE_NANOS;
            FakeTexture second = new FakeTexture(10, 10);
            fixture.offer(second);
            assertEquals(0, first.disposals, "disposals of a texture idle for exactly two seconds");
            fixture.clock().now = IDLE_NANOS + 1;
            fixture.offer(new FakeTexture(10, 10));
            assertEquals(1, first.disposals, "disposals of the texture idle at the offer");
            fixture.clock().now = 2 * IDLE_NANOS + 1;
            RTImageShim.obtain(fixture.cache(), fixture.factory(), 20, 20);
            assertEquals(1, second.disposals, "disposals of the texture idle at the take");
            assertEquals(1, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
        });
    }

    @Test
    public void anOfferSchedulesOnePurgeAfterTheIdleTime() {
        onRenderThread(fixture -> {
            fixture.offer(new FakeTexture(10, 10));
            assertEquals(1, fixture.scheduler().jobs.size(), "purges scheduled by the first offer");
            long delay = fixture.scheduler().delays.getFirst();
            assertTrue(delay > IDLE_NANOS && delay < IDLE_NANOS + MAX_PURGE_LATENESS_NANOS,
                    "the delay of the purge: " + delay + " ns");
            fixture.clock().now = IDLE_NANOS / 2;
            fixture.offer(new FakeTexture(10, 10));
            assertEquals(1, fixture.scheduler().jobs.size(), "purges scheduled with one pending");
        });
    }

    @Test
    public void aPurgeDisposesTheIdleTexturesAndLeavesNothingPending() {
        onRenderThread(fixture -> {
            List<FakeTexture> offered = List.of(new FakeTexture(10, 10), new FakeTexture(20, 20));
            offered.forEach(fixture::offer);
            fixture.runTheLastPurge();
            assertTrue(offered.stream().allMatch(texture -> texture.disposals == 1),
                    "an idle texture was not disposed exactly once");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
            assertEquals(0, RTImageShim.cachedBytes(fixture.cache()), "bytes cached");
            assertEquals(1, fixture.scheduler().jobs.size(), "purges scheduled by the purge of an emptied cache");

            fixture.offer(new FakeTexture(10, 10));
            assertEquals(2, fixture.scheduler().jobs.size(), "purges scheduled by an offer after the purge");
        });
    }

    @Test
    public void aTextureOfferedLaterSurvivesThePurgeUntilTheNextOne() {
        onRenderThread(fixture -> {
            FakeTexture first = new FakeTexture(10, 10);
            fixture.offer(first);
            long secondOfferedAt = IDLE_NANOS / 2;
            fixture.clock().now = secondOfferedAt;
            FakeTexture second = new FakeTexture(10, 10);
            fixture.offer(second);

            fixture.runTheLastPurge();
            assertEquals(1, first.disposals, "disposals of the texture idle at the first purge");
            assertEquals(0, second.disposals, "disposals of the texture offered later, at the first purge");
            assertEquals(1, RTImageShim.cachedTextures(fixture.cache()), "textures cached after the first purge");
            assertEquals(2, fixture.scheduler().jobs.size(), "purges scheduled once the first purge ran");
            long lateness = fixture.scheduler().dueAt.getLast() - secondOfferedAt - IDLE_NANOS;
            assertTrue(lateness > 0 && lateness < MAX_PURGE_LATENESS_NANOS,
                    "the second purge past the idle time of the texture offered later: " + lateness + " ns");

            fixture.runTheLastPurge();
            assertEquals(1, second.disposals, "disposals of the texture offered later, at the second purge");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached after the second purge");
            assertEquals(2, fixture.scheduler().jobs.size(), "purges scheduled once the cache was empty");
        });
    }

    @Test
    public void aPurgeAfterTheFactoryWasReleasedDoesNothing() {
        onRenderThread(fixture -> {
            FakeTexture texture = new FakeTexture(10, 10);
            fixture.offer(texture);
            fixture.fake().disposed = true;
            fixture.fake().listeners.getFirst().factoryReleased();
            assertEquals(1, texture.disposals, "disposals of a texture of a released factory");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached after the release");

            fixture.runTheLastPurge();
            assertEquals(1, texture.disposals, "disposals of a texture of a released factory, after the purge");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached after the purge");
            assertEquals(0, RTImageShim.cachedBytes(fixture.cache()), "bytes cached after the purge");
            assertEquals(1, fixture.scheduler().jobs.size(), "purges scheduled, the purge's own included");
        });
    }

    @Test
    public void aPurgeIsDroppedWhenTheRenderThreadRefusesItsJob() {
        boolean[] ran = new boolean[1];
        RTImageShim.postToRenderThread(job -> {
            throw new RejectedExecutionException("the toolkit has exited");
        }, () -> ran[0] = true);
        assertFalse(ran[0], "the refused job ran");
    }

    @Test
    public void theProductionSchedulerRunsTheJobOnTheRenderThreadAfterTheDelay()
            throws InterruptedException, ExecutionException, TimeoutException {
        record Run(String thread, long ranAt) {}
        long delay = TimeUnit.MILLISECONDS.toNanos(50);
        CompletableFuture<Run> run = new CompletableFuture<>();
        long scheduledAt = System.nanoTime();
        RTImageShim.scheduleOnRenderThread(delay,
                () -> run.complete(new Run(Thread.currentThread().getName(), System.nanoTime())));
        Run job = run.get(10, TimeUnit.SECONDS);
        assertTrue(job.ranAt() - scheduledAt >= delay, "the job ran before its delay");
        assertTrue(job.thread().startsWith("QuantumRenderer"), "the job ran on " + job.thread());
    }

    @Test
    public void aLostTextureIsNeitherCachedNorReused() {
        onRenderThread(fixture -> {
            FakeTexture lost = new FakeTexture(10, 10);
            lost.surfaceLost = true;
            fixture.offer(lost);
            assertEquals(1, lost.disposals, "disposals of a texture offered lost");

            FakeTexture lostLater = new FakeTexture(10, 10);
            RTTexture offered = fixture.offer(lostLater);
            lostLater.surfaceLost = true;
            assertNotSame(offered, RTImageShim.obtain(fixture.cache(), fixture.factory(), 10, 10));
            assertEquals(1, lostLater.disposals, "disposals of a texture lost in the cache");
            assertEquals(0, lostLater.clears, "clears of a texture lost in the cache");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
        });
    }

    /** D3D gives no graphics for a texture whose device is lost. */
    @Test
    public void aTextureThatGivesNoGraphicsIsDisposedAndTheNextOneOfItsSizeReused() {
        onRenderThread(fixture -> {
            FakeTexture older = new FakeTexture(10, 10);
            RTTexture olderOffered = fixture.offer(older);
            FakeTexture withoutGraphics = new FakeTexture(10, 10);
            withoutGraphics.hasGraphics = false;
            fixture.offer(withoutGraphics);
            assertSame(olderOffered, RTImageShim.obtain(fixture.cache(), fixture.factory(), 10, 10));
            assertEquals(1, withoutGraphics.disposals, "disposals of the texture that gave no graphics");
            assertEquals(List.of("createGraphics"), withoutGraphics.drawCalls,
                    "the calls on the texture that gave no graphics");
            assertEquals(1, older.clears, "clears of the texture reused instead");
            assertEquals(0, older.disposals, "disposals of the texture reused instead");
            assertEquals(1, RTImageShim.texturesReused(fixture.cache()), "textures reused");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
            assertEquals(0, RTImageShim.cachedBytes(fixture.cache()), "bytes cached");
        });
    }

    @Test
    public void aTextureOfADisposedFactoryIsDisposed() {
        onRenderThread(fixture -> {
            fixture.fake().disposed = true;
            FakeTexture texture = new FakeTexture(10, 10);
            fixture.offer(texture);
            assertEquals(1, texture.disposals, "disposals of a texture of a disposed factory");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
        });
    }

    @Test
    public void aResetOfTheFactoryDisposesEveryCachedTexture() {
        assertDeviceLossDisposesEveryCachedTexture(ResourceFactoryListener::factoryReset);
    }

    @Test
    public void aReleaseOfTheFactoryDisposesEveryCachedTexture() {
        assertDeviceLossDisposesEveryCachedTexture(ResourceFactoryListener::factoryReleased);
    }

    private static void assertDeviceLossDisposesEveryCachedTexture(Consumer<ResourceFactoryListener> loss) {
        onRenderThread(fixture -> {
            List<FakeTexture> offered = List.of(new FakeTexture(10, 10), new FakeTexture(20, 20));
            offered.forEach(fixture::offer);
            assertEquals(List.of(fixture.cache()), fixture.fake().listeners, "the factory's listeners");
            loss.accept(fixture.fake().listeners.getFirst());
            assertTrue(offered.stream().allMatch(texture -> texture.disposals == 1),
                    "a cached texture was not disposed exactly once");
            assertEquals(0, RTImageShim.cachedTextures(fixture.cache()), "textures cached");
            assertEquals(0, RTImageShim.cachedBytes(fixture.cache()), "bytes cached");
        });
    }

    private static void onRenderThread(Consumer<Fixture> check) {
        Throwable[] failure = new Throwable[1];
        boolean[] ran = new boolean[1];
        PrismInvokerShim.runOnRenderThread(() -> {
            try {
                Clock clock = new Clock();
                Scheduler scheduler = new Scheduler(clock);
                FakeFactory fake = new FakeFactory();
                ResourceFactory factory = (ResourceFactory) Proxy.newProxyInstance(
                        ResourceFactory.class.getClassLoader(), new Class<?>[] {ResourceFactory.class}, fake);
                ResourceFactoryListener cache = RTImageShim.createCache(() -> clock.now, scheduler::schedule);
                check.accept(new Fixture(cache, factory, fake, clock, scheduler));
                ran[0] = true;
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        assertTrue(ran[0], "the check did not run on the render thread");
    }
}
