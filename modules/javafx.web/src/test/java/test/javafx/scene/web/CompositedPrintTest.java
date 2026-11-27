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

package test.javafx.scene.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static test.javafx.scene.web.PixelAssertions.CONTROL;
import static test.javafx.scene.web.PixelAssertions.GREEN;
import static test.javafx.scene.web.PixelAssertions.RED;
import static test.javafx.scene.web.PixelAssertions.WHITE;
import static test.javafx.scene.web.PixelAssertions.YELLOW;
import static test.javafx.scene.web.PixelAssertions.assertMatchesReference;
import static test.javafx.scene.web.PixelAssertions.assertSamples;
import static test.javafx.scene.web.PixelAssertions.pageWithControlPatch;

import com.sun.javafx.tk.RenderJob;
import com.sun.javafx.tk.Toolkit;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;
import test.javafx.scene.web.PixelAssertions.Tolerance;

/**
 * Printing a WebView node ({@code PrinterJob.printPage(webView)}) records the page into a queue of its
 * own through {@code WebPage.print}. {@code WebPageShim.mockPrint} takes the same path but keeps the
 * printed image to itself, so the tests print through {@code WebPage.print} directly, into a printer's
 * graphics ({@code PrismPrintGraphics} over a Java 2D image) as a printer job does: the page's own queue
 * draws into them on the printing thread, and the queues and render textures of the composited tiles
 * belong to the render thread.
 */
@ExtendWith(CaseAnnouncer.class)
public class CompositedPrintTest extends TestBase {

    private static final String PAGE = pageWithControlPatch("""
            <div id=box style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                transform:translateZ(0)'></div>
            """);

    private static final String ROUNDED_CLIP_PAGE = pageWithControlPatch("""
            <div style='position:absolute;left:20px;top:20px;width:200px;height:200px;overflow:hidden;
                border-radius:60px'>
            <div style='width:200px;height:200px;background:#f00;transform:translateZ(0)'></div>
            </div>
            """);

    private static final String WHOLE_TURN_PAGE = pageWithControlPatch("""
            <div style='position:absolute;left:0;top:0;width:300px;height:300px;perspective:500px'>
            <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                transform:rotateY(360deg)'></div>
            </div>
            """);

    private static final Sample[] SAMPLES = {CONTROL, new Sample(100, 100, RED), new Sample(300, 100, WHITE)};

    private static final String PRISM_INVOKER = "com.sun.javafx.webkit.prism.PrismInvoker";
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(20);

    @Test
    public void aCompositedPagePrintsItsContent() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            assertSamples("printed", page.print(), SAMPLES);
        }
    }

    /** The turned layer rests flat where it started, see {@code CompositedPixelsTest}. */
    @Test
    public void aLayerTurnedAWholeTurnUnderAPerspectivePrintsItsContent() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(WHOLE_TURN_PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            assertSamples("printed", page.print(), SAMPLES);
        }
    }

    @Test
    public void aPageNotInCompositingModePrintsWhatItPaints() {
        try (PumpedPage page = PumpedPage.withDefaultSettings(PAGE)) {
            BufferedImage painted = page.settle();
            assertFalse(page.isInCompositingMode(), "the page is in compositing mode");
            BufferedImage printed = page.print();
            assertSamples("printed", printed, SAMPLES);
            assertMatchesReference(painted, printed, Tolerance.FLAT_FILLS);
        }
    }

    /**
     * A printer job prints from a thread of its own. The page changed since it was last painted, so the
     * print repaints the root tiles, and the first tile's queue is decoded before the tile is drawn;
     * that decode draws into the tile's render texture and must run on the render thread.
     */
    @Test
    public void aPrintFromAnotherThreadDecodesTileQueuesOnTheRenderThread() throws Exception {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            page.script("document.body.style.background = '#ff0'; box.style.background = '#0f0'");
            FirstHop hop = printFromAnotherThreadWhileTheRenderThreadIsHeld(page);
            assertEquals("com.sun.javafx.webkit.prism.WCRenderQueueImpl.runDecode",
                    hop.caller().getClassName() + "." + hop.caller().getMethodName(),
                    "what first asked for the render thread");
            assertSamples("printed", hop.printed(), CONTROL, new Sample(100, 100, GREEN),
                    new Sample(300, 100, YELLOW));
        }
    }

    /**
     * The tiles of a page painted since its last change have nothing to decode, and their decode
     * commands must not cost a trip to the render thread; the first thing that waits for it is then
     * the draw of a tile.
     */
    @Test
    public void aPrintOfAnUnchangedPageDecodesNothing() throws Exception {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            FirstHop hop = printFromAnotherThreadWhileTheRenderThreadIsHeld(page);
            assertEquals("com.sun.javafx.webkit.prism.RTImage.draw",
                    hop.caller().getClassName() + "." + hop.caller().getMethodName(),
                    "what first asked for the render thread");
            assertSamples("printed", hop.printed(), SAMPLES);
        }
    }

    /** The page is destroyed while a WebView node may still be printed; the print draws nothing. */
    @Test
    public void aDisposedPagePrintsNothing() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            page.settle();
            page.close();
            BufferedImage printed = page.print();
            assertEquals(0, printed.getRGB(100, 100), "the printed box of a disposed page");
            assertEquals(0, printed.getRGB(CONTROL.x(), CONTROL.y()), "the printed control patch of a disposed page");
        }
    }

    /**
     * The only composited element is removed and the page printed before any pulse: the print's layer
     * sync leaves compositing mode, and the page must then be drawn by the page paint.
     */
    @Test
    public void aPageThatLeavesCompositingModeAsItIsPrintedPrintsItsContent() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            page.pump();
            page.script("box.remove()");
            assertTrue(page.isInCompositingMode(), "the page left compositing mode before the print");
            assertSamples("printed", page.printFromThisThread(), CONTROL, new Sample(100, 100, WHITE),
                    new Sample(300, 100, WHITE));
            assertFalse(page.isInCompositingMode(), "the print did not take the page out of compositing mode");
        }
    }

    /**
     * A printer's graphics are Java 2D graphics, and so are those of the clip layer that the rounded clip
     * opens on them, into which the clipped layer's tiles are drawn.
     */
    @Test
    public void aRoundedOverflowClipOfACompositedLayerPrintsOnAPrinter() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(ROUNDED_CLIP_PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            assertSamples("printed on a printer", page.printFromThisThread(), CONTROL,
                    new Sample(120, 120, RED), new Sample(120, 30, RED), new Sample(26, 26, WHITE),
                    new Sample(214, 214, WHITE), new Sample(300, 100, WHITE));
        }
    }

    @Test
    public void aPrintCarriesNoInspectorHighlight() {
        try (PumpedPage page = PumpedPage.withDefaultSettings(PAGE)) {
            assertPrintCarriesNoInspectorHighlight(page, false);
        }
    }

    @Test
    public void aCompositedPrintCarriesNoInspectorHighlight() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            assertPrintCarriesNoInspectorHighlight(page, true);
        }
    }

    /**
     * The inspector's highlight is drawn over the page on the screen only. The box is highlighted with
     * a half-transparent green, which tints it on the screen and leaves the rest of the screen as it
     * was, and the print shows it untinted. Frames that repaint only a spot elsewhere draw the
     * highlight again; the tint must stay what the first frame showed, not build up.
     */
    private static void assertPrintCarriesNoInspectorHighlight(PumpedPage page, boolean composited) {
        page.settle();
        assertEquals(composited, page.isInCompositingMode(), "compositing mode");
        List<String> replies = page.highlightInInspector("#box");
        assertTrue(replies.stream().noneMatch(reply -> reply.contains("\"error\"")),
                "the inspector refused a request: " + replies);
        page.resize(PumpedPage.WIDTH, PumpedPage.HEIGHT);
        BufferedImage screen = page.settle();
        int highlighted = screen.getRGB(100, 100);
        assertTrue((highlighted >> 8 & 0xff) >= 0x60,
                "the highlight did not tint the box on the screen: " + Integer.toHexString(highlighted));
        assertSamples("the screen outside the highlight", screen, CONTROL, new Sample(300, 100, WHITE),
                new Sample(300, 250, WHITE));

        page.script("const spot = document.createElement('div');"
                + " spot.style.cssText = 'position:absolute;left:380px;top:10px;width:5px;height:5px;background:#000';"
                + " document.body.appendChild(spot);");
        page.pumpUntilDrawn(382, 12, 0x000000);
        page.script("document.body.lastChild.style.background = '#888'");
        assertSamples("the highlighted box after frames that repainted elsewhere",
                page.pumpUntilDrawn(382, 12, 0x888888), new Sample(100, 100, highlighted & 0xffffff));

        assertSamples("printed", page.print(), SAMPLES);
    }

    private record FirstHop(StackTraceElement caller, BufferedImage printed) {
    }

    /**
     * Holds the render thread, prints from a plain thread, and waits until that thread waits for the
     * render thread; answers the frame that asked for it, and the print once the render thread is free.
     */
    private static FirstHop printFromAnotherThreadWhileTheRenderThreadIsHeld(PumpedPage page) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Toolkit.getToolkit().addRenderJob(new RenderJob(() -> {
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        CompletableFuture<BufferedImage> printed = new CompletableFuture<>();
        Thread printer = new Thread(() -> {
            try {
                printed.complete(page.printFromThisThread());
            } catch (Throwable t) {
                printed.completeExceptionally(t);
            }
        }, "US-026 printer");
        printer.setDaemon(true);
        StackTraceElement caller = null;
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS), "the render thread was not held");
            printer.start();
            long deadline = System.nanoTime() + DEADLINE_NANOS;
            while (caller == null) {
                StackTraceElement[] stack = printer.getStackTrace();
                caller = callerOfARenderThreadHop(stack);
                if (caller == null) {
                    if (!printer.isAlive()) {
                        fail("the print ended without waiting for the held render thread");
                    }
                    if (System.nanoTime() > deadline) {
                        fail("the print never waited for the render thread: " + Arrays.toString(stack));
                    }
                    Thread.sleep(10);
                }
            }
        } finally {
            release.countDown();
        }
        return new FirstHop(caller, printed.get(20, TimeUnit.SECONDS));
    }

    private static StackTraceElement callerOfARenderThreadHop(StackTraceElement[] stack) {
        for (int frame = 0; frame + 1 < stack.length; frame++) {
            String method = stack[frame].getMethodName();
            if (PRISM_INVOKER.equals(stack[frame].getClassName())
                    && (method.equals("runOnRenderThread") || method.equals("callOnRenderThread"))) {
                return stack[frame + 1];
            }
        }
        return null;
    }
}
