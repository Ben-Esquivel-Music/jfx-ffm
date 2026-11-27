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
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.javafx.scene.web.Debugger;
import com.sun.javafx.webkit.prism.PrismInvokerShim;
import com.sun.javafx.webkit.prism.WCBufferedContextShim;
import com.sun.prism.j2d.PrismPrintGraphics;
import com.sun.webkit.WebPage;
import com.sun.webkit.graphics.WCGraphicsContext;
import com.sun.webkit.graphics.WCGraphicsManager;
import com.sun.webkit.graphics.WCRectangle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebEngineShim;
import javafx.scene.web.WebView;

/**
 * A page of its own WebView, driven the way a showing WebView is: every pump runs the FX pulse's
 * {@code updateContent} when the page is dirty and then paints the page on the render thread. The view
 * is in no showing window, so nothing else updates the page; in compositing mode the rendering update
 * (requestAnimationFrame, animations, view-transition steps) runs only inside a pump.
 * <p>
 * A page that draws form controls is in a scene of its own size: the render theme draws a control
 * through the scene graph peer of a JavaFX control it adds to the WebView, and only a scene syncs that
 * peer. As in a pulse, the update records the draws, the scene syncs the peers, and only then are the
 * draws decoded on the render thread; a snapshot of the scene does the last two.
 */
final class PumpedPage implements AutoCloseable {

    static final int WIDTH = 400;
    static final int HEIGHT = 300;

    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int MIN_PUMPS_TO_SETTLE = 3;

    /*
     * Surefire copies what the test JVM writes to its file descriptor 2 to the Maven console as it
     * comes, so the name of the case survives a native crash that kills the shared JVM.
     */
    private static final PrintStream FORK_STDERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    /*
     * In compositing mode WebPage::markForSync defers the rendering update to the next updateContent;
     * otherwise it runs it as soon as the rendering-update timer fires. That timer (at most 30 ms on
     * Java, which has no display refresh monitor) and the 100 ms DOM timer share WebCore's timer heap, so
     * without a pump the animation frame comes first exactly when the root layer is not attached.
     */
    private static final String COMPOSITING_PROBE = """
            window.compositingProbe = {frame: false, timer: false, frameBeforeTimer: false};
            requestAnimationFrame(function() { compositingProbe.frame = true; });
            setTimeout(function() {
                compositingProbe.timer = true;
                compositingProbe.frameBeforeTimer = compositingProbe.frame;
            }, 100);
            """;

    private final WebView view;
    private final WebPage page;
    private final Scene scene;
    private int width = WIDTH;
    private int height = HEIGHT;
    private boolean closed = false;

    private PumpedPage(WebView view, boolean drawsFormControls) {
        this.view = view;
        this.page = WebEngineShim.getPage(view.getEngine());
        if (drawsFormControls) {
            view.setMinSize(WIDTH, HEIGHT);
            view.setPrefSize(WIDTH, HEIGHT);
            view.setMaxSize(WIDTH, HEIGHT);
            scene = new Scene(new Group(view), WIDTH, HEIGHT);
        } else {
            scene = null;
        }
    }

    static void announce(String caseName) {
        FORK_STDERR.println("US-026 case: " + caseName);
    }

    static PumpedPage withAcceleratedCompositing(String html) {
        return open(html, true, false);
    }

    static PumpedPage withDefaultSettings(String html) {
        return open(html, false, false);
    }

    static PumpedPage withAcceleratedCompositingAndFormControls(String html) {
        return open(html, true, true);
    }

    static PumpedPage withDefaultSettingsAndFormControls(String html) {
        return open(html, false, true);
    }

    private static PumpedPage open(String html, boolean acceleratedCompositing, boolean drawsFormControls) {
        CompletableFuture<Worker.State> loaded = new CompletableFuture<>();
        PumpedPage opened = onFxThread(() -> {
            PumpedPage created = new PumpedPage(new WebView(), drawsFormControls);
            if (acceleratedCompositing) {
                created.page.overridePreference("WebKitAcceleratedCompositingEnabled", "1");
            }
            WebEngine engine = created.view.getEngine();
            engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED || newState == Worker.State.FAILED) {
                    loaded.complete(newState);
                }
            });
            engine.loadContent(html);
            return created;
        });
        try {
            assertEquals(Worker.State.SUCCEEDED, loaded.get(20, TimeUnit.SECONDS), "page load");
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("page did not load", e);
        }
        opened.resize(WIDTH, HEIGHT);
        return opened;
    }

    /** Gives the page its size, as a WebView's layout does, which invalidates the whole page, and pumps. */
    BufferedImage resize(int newWidth, int newHeight) {
        width = newWidth;
        height = newHeight;
        return onFxThread(() -> {
            page.setBounds(0, 0, width, height);
            return pumpOnFxThread();
        });
    }

    BufferedImage pump() {
        return onFxThread(this::pumpOnFxThread);
    }

    private BufferedImage pumpOnFxThread() {
        if (page.isDirty()) {
            page.updateContent(new WCRectangle(0, 0, width, height));
        }
        if (scene != null) {
            scene.snapshot(null);
        }
        WCGraphicsContext gc = WCBufferedContextShim.createBufferedContext(width, height);
        PrismInvokerShim.runOnRenderThread(() -> page.paint(gc, 0, 0, width, height));
        return gc.getImage().toBufferedImage();
    }

    /** Pumps until two pumps in a row paint the same pixels. */
    BufferedImage settle() {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        BufferedImage previous = pump();
        int pumps = 1;
        while (true) {
            BufferedImage current = pump();
            pumps++;
            if (pumps >= MIN_PUMPS_TO_SETTLE && samePixels(previous, current)) {
                return current;
            }
            if (System.nanoTime() > deadline) {
                fail("the page kept changing for " + pumps + " pumps");
            }
            previous = current;
        }
    }

    /** Pumps until a frame shows the colour at the point, and returns that frame; fails at the deadline. */
    BufferedImage pumpUntilDrawn(int x, int y, int rgb) {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        BufferedImage frame = pump();
        while ((frame.getRGB(x, y) & 0xffffff) != rgb) {
            if (System.nanoTime() > deadline) {
                fail(String.format("(%d,%d) never showed %06x, last %08x", x, y, rgb, frame.getRGB(x, y)));
            }
            frame = pump();
        }
        return frame;
    }

    /** Pumps until the script answers true, and returns what the pump after that painted. */
    BufferedImage pumpUntil(String condition) {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        int pumps = 0;
        while (!Boolean.TRUE.equals(script(condition))) {
            if (System.nanoTime() > deadline) {
                fail("after " + pumps + " pumps, still not true: " + condition);
            }
            pump();
            pumps++;
        }
        return pump();
    }

    /*
     * The view is not visible, so every stage pulse drops the page's unpainted frames. Each of the two
     * methods below runs in one FX task, between two pulses, so that what it observes is its own doing.
     */

    /**
     * Runs the change, then updates the content without painting until the page holds unpainted
     * frames, and drops them as a pulse does. Answers whether frames were recorded and then gone.
     */
    boolean recordAndDropFrames(String change) {
        return onFxThread(() -> {
            view.getEngine().executeScript(change);
            for (int update = 0; update < MIN_PUMPS_TO_SETTLE && !page.isRepaintPending(); update++) {
                page.updateContent(new WCRectangle(0, 0, width, height));
            }
            boolean recorded = page.isRepaintPending();
            page.dropRenderFrames();
            return recorded && !page.isRepaintPending();
        });
    }

    /**
     * Runs a change and an update without painting, again and again, until the page drops its frames
     * because it holds too many. Answers the number of updates that took, or 0 when it did not happen
     * within {@code maxUpdates}.
     */
    int updatesUntilFramesOverflow(IntFunction<String> change, int maxUpdates) {
        return onFxThread(() -> {
            boolean recorded = false;
            for (int update = 1; update <= maxUpdates; update++) {
                view.getEngine().executeScript(change.apply(update));
                page.updateContent(new WCRectangle(0, 0, width, height));
                if (recorded && !page.isRepaintPending()) {
                    return update;
                }
                recorded |= page.isRepaintPending();
            }
            return 0;
        });
    }

    /**
     * Highlights the element the selector names, as the Web Inspector does over the remote debugging
     * protocol, and answers the protocol's replies.
     */
    List<String> highlightInInspector(String selector) {
        return onFxThread(() -> {
            List<String> replies = new ArrayList<>();
            Debugger debugger = WebEngineShim.getDebugger(view.getEngine());
            debugger.setMessageCallback(message -> {
                replies.add(message);
                return null;
            });
            debugger.setEnabled(true);
            debugger.sendMessage("{\"method\":\"DOM.getDocument\",\"id\":1}");
            int document = replyNodeId(replies, "DOM.getDocument");
            debugger.sendMessage("{\"method\":\"DOM.querySelector\",\"id\":2,\"params\":{\"nodeId\":" + document
                    + ",\"selector\":\"" + selector + "\"}}");
            int element = replyNodeId(replies, "DOM.querySelector");
            debugger.sendMessage("{\"method\":\"DOM.highlightNode\",\"id\":3,\"params\":{\"nodeId\":" + element
                    + ",\"highlightConfig\":{\"showInfo\":false,"
                    + "\"contentColor\":{\"r\":0,\"g\":255,\"b\":0,\"a\":0.5}}}}");
            return List.copyOf(replies);
        });
    }

    /** Hides the inspector's highlight over the same protocol, and answers the protocol's replies. */
    List<String> hideHighlightInInspector() {
        return onFxThread(() -> {
            List<String> replies = new ArrayList<>();
            Debugger debugger = WebEngineShim.getDebugger(view.getEngine());
            debugger.setMessageCallback(message -> {
                replies.add(message);
                return null;
            });
            debugger.sendMessage("{\"method\":\"DOM.hideHighlight\",\"id\":4}");
            return List.copyOf(replies);
        });
    }

    /** Sets the WebView's page fill, which WebKit draws as the base background of the page, and pumps. */
    BufferedImage setPageFill(Color fill) {
        return onFxThread(() -> {
            view.setPageFill(fill);
            return pumpOnFxThread();
        });
    }

    /**
     * Runs the change, gives the page its size again, which invalidates the whole page, and records that
     * update without painting it; then sets the page fill and paints only the recorded update. One FX task,
     * so that no pulse drops the update.
     */
    BufferedImage recordAnUpdateThenSetPageFillAndPaint(String change, Color fill) {
        return onFxThread(() -> {
            view.getEngine().executeScript(change);
            page.setBounds(0, 0, width, height);
            page.updateContent(new WCRectangle(0, 0, width, height));
            view.setPageFill(fill);
            WCGraphicsContext gc = WCBufferedContextShim.createBufferedContext(width, height);
            PrismInvokerShim.runOnRenderThread(() -> page.paint(gc, 0, 0, width, height));
            return gc.getImage().toBufferedImage();
        });
    }

    private static int replyNodeId(List<String> replies, String request) {
        Matcher nodeId = Pattern.compile("\"nodeId\":(\\d+)").matcher(replies.isEmpty() ? "" : replies.getLast());
        if (!nodeId.find()) {
            throw new AssertionError(request + " answered no node: " + replies);
        }
        return Integer.parseInt(nodeId.group(1));
    }

    /** Updates the content and prints it the way a printer job prints a WebView node. */
    BufferedImage print() {
        return onFxThread(() -> {
            page.updateContent(new WCRectangle(0, 0, width, height));
            return printFromThisThread();
        });
    }

    /**
     * Prints from the calling thread into a printer's graphics, as a printer job prints a WebView node
     * from a thread of its own; the page is not updated first.
     */
    BufferedImage printFromThisThread() {
        BufferedImage printed = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = printed.createGraphics();
        WCGraphicsContext gc = WCGraphicsManager.getGraphicsManager()
                .createGraphicsContext(new PrismPrintGraphics(g2d, width, height));
        try {
            page.print(gc, 0, 0, width, height);
            gc.flush();
        } finally {
            gc.dispose();
            g2d.dispose();
        }
        return printed;
    }

    /** Navigates away and disposes the engine, which destroys the page; once only. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        CompletableFuture<Worker.State> blank = new CompletableFuture<>();
        onFxThread(() -> {
            view.getEngine().getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED || newState == Worker.State.FAILED) {
                    blank.complete(newState);
                }
            });
            view.getEngine().load("about:blank");
            return null;
        });
        try {
            blank.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("about:blank did not load", e);
        }
        onFxThread(() -> {
            WebEngineShim.dispose(view.getEngine());
            return null;
        });
    }

    Object script(String script) {
        return onFxThread(() -> view.getEngine().executeScript(script));
    }

    /**
     * Whether WebPage has its root GraphicsLayer attached. Changes nothing the page paints; the probe's
     * animation frame, if deferred, runs in the next pump.
     */
    boolean isInCompositingMode() {
        script(COMPOSITING_PROBE);
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        while (!Boolean.TRUE.equals(script("compositingProbe.timer"))) {
            if (System.nanoTime() > deadline) {
                fail("the compositing probe's timer never fired");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        return !Boolean.TRUE.equals(script("compositingProbe.frameBeforeTimer"));
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        return a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight()
                && Arrays.equals(a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()),
                        b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()));
    }

    private static <T> T onFxThread(Callable<T> job) {
        FutureTask<T> task = new FutureTask<>(job);
        Platform.runLater(task);
        try {
            return task.get(DEADLINE_NANOS, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw new AssertionError(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError(e);
        }
    }
}
