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

import static javafx.concurrent.Worker.State.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test.util.Util;

/**
 * Composited WebView content on the real pipeline, with CSS 3D on ({@code com.sun.webkit.useCSS3D},
 * read once when the first page is created, which here is after this class sets it). The property is
 * honoured only where the pipeline supports 3D; elsewhere the class is skipped, or fails under
 * {@code -Djfx.parity.require=true}, so that a run without 3D is never read as a pass.
 * <p>
 * Each page is 400 x 300 with a blue control patch that nothing composites, and every page is proven
 * to be in compositing mode: while the view is hidden no pulse updates the page, so a rendering update,
 * and with it an animation frame, runs before a 100 ms timer only when the page is not composited.
 */
public class CSS3DCompositingTest {

    private static final String REQUIRE_PROPERTY = "jfx.parity.require";
    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;
    private static final int FLAT_FILL = 8;
    private static final int MARGIN = 10;
    private static final int PULSES = 5;

    private static final int RED = 0xff0000;
    private static final int GREEN = 0x00ff00;
    private static final int BLUE = 0x0000ff;
    private static final int WHITE = 0xffffff;
    private static final int YELLOW = 0xffff00;

    private static final String CONTROL_PATCH =
            "<div style='position:absolute;left:310px;top:230px;width:80px;height:60px;background:#00f'></div>";

    private static final String COMPOSITING_PROBE = """
            window.compositingProbe = {frame: false, timer: false, frameBeforeTimer: false};
            requestAnimationFrame(function() { compositingProbe.frame = true; });
            setTimeout(function() {
                compositingProbe.timer = true;
                compositingProbe.frameBeforeTimer = compositingProbe.frame;
            }, 100);
            """;

    private static final CountDownLatch launchLatch = new CountDownLatch(1);
    private static CSS3DCompositingTestApp app;

    private WebView webView;
    private Scene scene;

    public static class CSS3DCompositingTestApp extends Application {
        private Stage primaryStage;

        @Override
        public void init() {
            CSS3DCompositingTest.app = this;
        }

        @Override
        public void start(Stage primaryStage) {
            Platform.setImplicitExit(false);
            this.primaryStage = primaryStage;
            launchLatch.countDown();
        }
    }

    @BeforeAll
    public static void setupOnce() {
        System.setProperty("com.sun.webkit.useCSS3D", "true");
        Util.launch(launchLatch, CSS3DCompositingTestApp.class);
        assertTrue(Util.await(launchLatch), "Timeout waiting for FX runtime to start");
        boolean[] scene3D = new boolean[1];
        Util.runAndWait(() -> scene3D[0] = Platform.isSupported(ConditionalFeature.SCENE3D));
        if (!scene3D[0]) {
            String missing = "the pipeline does not support SCENE3D, so com.sun.webkit.useCSS3D is ignored";
            if (Boolean.getBoolean(REQUIRE_PROPERTY)) {
                fail("-D" + REQUIRE_PROPERTY + "=true requires CSS 3D, and " + missing);
            }
            abort(missing);
        }
    }

    @AfterAll
    public static void tearDownOnce() {
        Util.shutdown();
    }

    @BeforeEach
    public void setupTestObjects() {
        Util.runAndWait(() -> {
            webView = new WebView();
            webView.setMinSize(WIDTH, HEIGHT);
            webView.setPrefSize(WIDTH, HEIGHT);
            webView.setMaxSize(WIDTH, HEIGHT);
        });
    }

    /** The story's probe: a white page with a 200 x 200 red box that only {@code translateZ(0)} composites. */
    @Test
    public void aTranslateZBoxIsDrawnBeforeDuringAndAfterAViewTransition() {
        show(webView);
        load("""
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                    transform:translateZ(0)'></div>
                <div id=note style='position:absolute;left:220px;top:220px;width:60px;height:60px;
                    background:#0f0'></div>
                """);
        assertProbePixels("loaded", GREEN);

        executeScript("""
                const held = document.createElement('style');
                held.textContent = '::view-transition-group(root){animation:hold 1000s linear}'
                    + '::view-transition-old(root){animation:none;opacity:0}'
                    + '::view-transition-new(root){animation:none;opacity:1}'
                    + '@keyframes hold{from{opacity:1}to{opacity:1}}';
                document.head.appendChild(held);
                window.state = 'started';
                window.transition = document.startViewTransition(function() {
                    document.getElementById('note').style.background = '#ff0';
                });
                transition.ready.then(function() { state = 'ready'; }, function(e) { state = 'ready: ' + e; });
                transition.finished.then(function() { state = 'finished'; });
                """);
        waitForScript("window.state !== 'started'", "the view transition to become ready");
        assertEquals("ready", executeScript("window.state"), "the view transition's state");
        Util.waitForIdle(scene, PULSES);
        assertProbePixels("once the view transition is ready", YELLOW);
        assertEquals("ready", executeScript("window.state"), "the view transition finished before it was sampled");

        executeScript("transition.skipTransition()");
        waitForScript("window.state === 'finished'", "the view transition to finish");
        Util.waitForIdle(scene, PULSES);
        assertProbePixels("after the view transition finished", YELLOW);
    }

    @Test
    public void aBlurredLayerSurvives() {
        show(webView);
        load("""
                <div style='position:absolute;left:40px;top:40px;width:100px;height:100px;background:#f00;
                    filter:blur(4px);transform:translateZ(0)'></div>
                """);
        assertSurvivesWith(new int[] {90, 90, RED}, new int[] {200, 90, WHITE});
    }

    @Test
    public void aMaskedLayerSurvives() {
        show(webView);
        String half = "linear-gradient(to right,#000 50%,transparent 50%)";
        load("<div style='position:absolute;left:20px;top:20px;width:200px;height:100px;background:#f00;"
                + "transform:translateZ(0);-webkit-mask-image:" + half + ";mask-image:" + half + "'></div>");
        assertSurvivesWith(new int[] {70, 70, RED}, new int[] {170, 70, WHITE});
    }

    @Test
    public void overlappingChildrenOfATranslucentParentSurvive() {
        show(webView);
        load("""
                <div style='position:absolute;left:0;top:0;width:300px;height:200px;opacity:0.5'>
                <div style='position:absolute;left:0;top:0;width:180px;height:200px;background:#f00;
                    transform:translateZ(0)'></div>
                <div style='position:absolute;left:120px;top:0;width:180px;height:200px;background:#00f;
                    transform:translateZ(0)'></div>
                </div>
                """);
        assertSurvivesWith(new int[] {60, 100, 0xff8080}, new int[] {150, 100, 0x8080ff},
                new int[] {240, 100, 0x8080ff});
    }

    @Test
    public void aFlattenedPreserve3DTreeSurvives() {
        show(webView);
        load("""
                <div style='position:absolute;left:20px;top:20px;width:160px;height:160px;perspective:500px'>
                <div style='width:160px;height:160px;transform-style:preserve-3d;transform:rotateY(30deg);
                    background:#f00'>
                <div style='width:80px;height:80px;transform-style:flat;transform:translateZ(30px);background:#0f0'>
                <div style='width:40px;height:40px;transform:rotateX(30deg);background:#00f'></div>
                </div></div></div>
                """);
        assertSurvivesWith();
    }

    /**
     * {@code rotateY(60deg)} of a 200 px square under {@code perspective:500px}, both origins at
     * (200,150): an edge {@code u} from the centre lands at {@code 200 + u cos60 * 500 / (500 + u sin60)}.
     * The container's rounded clip puts the layer into a clip layer of its own.
     */
    @Test
    public void aRotatedLayerInsideARoundedClipLandsWhereThePerspectivePutsIt() {
        show(webView);
        load("""
                <div style='position:absolute;left:0;top:0;width:400px;height:300px;overflow:hidden;
                    border-radius:20px'>
                <div style='position:absolute;left:0;top:0;width:400px;height:300px;perspective:500px'>
                <div style='position:absolute;left:100px;top:50px;width:200px;height:200px;background:#f00;
                    transform:rotateY(60deg)'></div>
                </div></div>
                """);
        double left = projectedEdge(-100, 60);
        double right = projectedEdge(100, 60);
        assertSurvivesWith(new int[] {(int) Math.ceil(left) + MARGIN, 150, RED},
                new int[] {(int) Math.floor(right) - MARGIN, 150, RED},
                new int[] {(int) Math.floor(left) - MARGIN, 150, WHITE},
                new int[] {(int) Math.ceil(right) + MARGIN, 150, WHITE});
    }

    /**
     * Two planes of 200 px in one preserve-3d context under {@code perspective:500px}, turned 45 degrees
     * either way about the perspective origin (200,150); they cut each other along the centre line.
     * {@code rotateY(45deg)} comes toward the viewer on the left, {@code rotateY(-45deg)} on the right,
     * so each side shows its front plane, and the side where only one plane reaches shows that one.
     */
    @Test
    public void intersectingPlanesUnderAPerspectiveShowTheFrontPlaneOnEachSide() {
        show(webView);
        load("""
                <div style='position:absolute;left:0;top:0;width:400px;height:300px;perspective:500px'>
                <div style='position:absolute;left:100px;top:50px;width:200px;height:200px;
                    transform-style:preserve-3d'>
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                    transform:rotateY(45deg)'></div>
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#00f;
                    transform:rotateY(-45deg)'></div>
                </div></div>
                """);
        double leftOfTheLeftPlane = projectedEdge(-100, 45);
        double rightOfTheRightPlane = projectedEdge(100, -45);
        assertTrue(projectedEdge(-100, -45) - leftOfTheLeftPlane > 2 * MARGIN
                && rightOfTheRightPlane - projectedEdge(100, 45) > 2 * MARGIN,
                "the parts only one plane covers are too narrow to sample");
        assertSurvivesWith(new int[] {160, 150, RED}, new int[] {240, 150, BLUE},
                new int[] {(int) Math.ceil(leftOfTheLeftPlane) + MARGIN, 150, RED},
                new int[] {(int) Math.floor(rightOfTheRightPlane) - MARGIN, 150, BLUE},
                new int[] {(int) Math.floor(leftOfTheLeftPlane) - MARGIN, 150, WHITE},
                new int[] {(int) Math.ceil(rightOfTheRightPlane) + MARGIN, 150, WHITE});
    }

    /**
     * The same planes, both turned 30 degrees about the view axis first, so that their line of
     * intersection is slanted. A point at {@code a} across and {@code b} along a plane sits at
     * {@code x = a cos30 - b sin30}, {@code y = a sin30 + b cos30} from (200,150), at depth
     * {@code z = -a} on the {@code rotateY(45deg)} plane and {@code z = a} on the other, and
     * {@code perspective:500px} draws it at {@code 500 / (500 - z)} times that offset. Each plane is in
     * front on its own side of {@code a = 0}; the red sample beside the line lies in the box around the
     * blue plane's front part, which only the clip to that part's polygon keeps blue out of.
     */
    @Test
    public void slantedIntersectingPlanesUnderAPerspectiveShowTheFrontPlaneOnEachSide() {
        show(webView);
        load("""
                <div style='position:absolute;left:0;top:0;width:400px;height:300px;perspective:500px'>
                <div style='position:absolute;left:100px;top:50px;width:200px;height:200px;
                    transform-style:preserve-3d'>
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                    transform:rotateZ(30deg) rotateY(45deg)'></div>
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#00f;
                    transform:rotateZ(30deg) rotateY(-45deg)'></div>
                </div></div>
                """);
        assertSurvivesWith(onPlane(-10, 60, -1, RED), onPlane(-40, 0, -1, RED), onPlane(-40, -60, -1, RED),
                onPlane(40, 0, 1, BLUE), onPlane(40, 60, 1, BLUE), onPlane(10, -60, 1, BLUE),
                onPlane(-90, 0, -1, WHITE), onPlane(90, 0, 1, WHITE));
    }

    /** The point {@code (a, b)} of the plane whose depth is {@code depthSign * a}, as {x, y, rgb}. */
    private static int[] onPlane(double a, double b, int depthSign, int rgb) {
        double radians = Math.toRadians(30);
        double scale = 500 / (500 - depthSign * a);
        return new int[] {
            (int) Math.round(200 + (a * Math.cos(radians) - b * Math.sin(radians)) * scale),
            (int) Math.round(150 + (a * Math.sin(radians) + b * Math.cos(radians)) * scale),
            rgb
        };
    }

    /** A layer turned almost edge-on reaches far in depth; drawn into a flattening surface it must not be cut. */
    @Test
    public void aLayerTurnedAlmostEdgeOnInAFlatteningSurfaceIsVisible() {
        show(webView);
        load(flatteningSurface("""
                <div style='position:absolute;left:0;top:50px;width:400px;height:200px;background:#f00;
                    transform:rotateY(80deg)'></div>
                """));
        double left = projectedEdge(-200, 80);
        double right = projectedEdge(200, 80);
        assertTrue(right - left > 2 * MARGIN + 2, "the projected layer is too narrow to sample");
        assertSurvivesWith(new int[] {(int) Math.round((left + right) / 2), 150, RED});
    }

    /** {@code translateZ(300px)} under {@code perspective:500px} scales a 60 px box 2.5 times about (200,150). */
    @Test
    public void aLayerCloseToTheViewerInAFlatteningSurfaceIsVisible() {
        show(webView);
        load(flatteningSurface("""
                <div style='position:absolute;left:170px;top:120px;width:60px;height:60px;background:#f00;
                    transform:translateZ(300px)'></div>
                """));
        assertSurvivesWith(new int[] {200 - 75 + MARGIN, 150, RED}, new int[] {200 + 75 - MARGIN, 150, RED},
                new int[] {200, 150 - 75 + MARGIN, RED}, new int[] {200 - 75 - MARGIN, 150, WHITE});
    }

    /** A perspective left set in Prism by the page would distort the JavaFX node drawn after it. */
    @Test
    public void aRectangleBesideAPageWithA3DLayerIsUnaffected() {
        Rectangle rectangle = new Rectangle(100, 100, Color.LIME);
        Util.runAndWait(() -> rectangle.setTranslateY(100));
        show(new HBox(webView, rectangle));
        load("""
                <div style='position:absolute;left:0;top:0;width:400px;height:300px;perspective:500px'>
                <div style='position:absolute;left:100px;top:50px;width:200px;height:200px;background:#f00;
                    transform:rotateY(60deg)'></div>
                </div>
                """);
        assertSurvivesWith();
        WritableImage[] snapshot = new WritableImage[1];
        Util.runAndWait(() -> snapshot[0] = scene.snapshot(null));
        double scale = snapshot[0].getWidth() / scene.getWidth();
        assertPixel("scene", snapshot[0], scale, WIDTH + 50, 150, GREEN);
        assertPixel("scene", snapshot[0], scale, WIDTH + MARGIN, 100 + MARGIN, GREEN);
        assertPixel("scene", snapshot[0], scale, WIDTH + 100 - MARGIN, 200 - MARGIN, GREEN);
        assertPixel("scene", snapshot[0], scale, WIDTH + 50, 100 - MARGIN, WHITE);
        assertPixel("scene", snapshot[0], scale, WIDTH + 50, 200 + MARGIN, WHITE);
    }

    private static double projectedEdge(double offset, double degrees) {
        double radians = Math.toRadians(degrees);
        return 200 + offset * Math.cos(radians) * 500 / (500 + offset * Math.sin(radians));
    }

    private static String flatteningSurface(String layer) {
        return "<div style='position:absolute;left:0;top:0;width:400px;height:300px;opacity:0.99;"
                + "transform:translateZ(0)'><div style='position:absolute;left:0;top:0;width:400px;height:300px;"
                + "perspective:500px'>" + layer + "</div></div>";
    }

    /** The probe's box and page, and the patch that is not composited, which the transition recolours. */
    private void assertProbePixels(String when, int note) {
        assertDrawnInCompositingMode(when, new int[] {100, 100, RED}, new int[] {300, 100, WHITE},
                new int[] {250, 250, note});
    }

    private void assertSurvivesWith(int[]... samples) {
        assertDrawnInCompositingMode("drawn", samples);
    }

    /**
     * The page is in compositing mode, its scripts still run, pulses still come, and the control patch
     * and the given {x, y, rgb} samples are drawn.
     */
    private void assertDrawnInCompositingMode(String when, int[]... samples) {
        assertTrue(isInCompositingMode(), "the page is not in compositing mode, " + when);
        assertEquals(2, ((Number) executeScript("1 + 1")).intValue(), "a script after the page was drawn");
        Util.waitForIdle(scene, PULSES);
        WritableImage[] snapshot = new WritableImage[1];
        Util.runAndWait(() -> snapshot[0] = webView.snapshot(null, null));
        double scale = snapshot[0].getWidth() / WIDTH;
        assertPixel("control patch, " + when + ",", snapshot[0], scale, 350, 260, BLUE);
        for (int[] sample : samples) {
            assertPixel("page, " + when + ",", snapshot[0], scale, sample[0], sample[1], sample[2]);
        }
    }

    private static void assertPixel(String what, WritableImage image, double scale, int x, int y, int rgb) {
        int argb = image.getPixelReader().getArgb((int) Math.floor(x * scale), (int) Math.floor(y * scale));
        int expected = 0xff000000 | rgb;
        for (int shift = 0; shift < 32; shift += 8) {
            if (Math.abs(((argb >>> shift) & 0xff) - ((expected >>> shift) & 0xff)) > FLAT_FILL) {
                fail(String.format("%s pixel (%d,%d): expected %08x within %d per channel, was %08x",
                        what, x, y, expected, FLAT_FILL, argb));
            }
        }
    }

    private boolean isInCompositingMode() {
        Util.runAndWait(() -> webView.setVisible(false));
        try {
            executeScript(COMPOSITING_PROBE);
            waitForScript("compositingProbe.timer", "the compositing probe's timer");
            return !Boolean.TRUE.equals(executeScript("compositingProbe.frameBeforeTimer"));
        } finally {
            Util.runAndWait(() -> webView.setVisible(true));
        }
    }

    private void show(Parent root) {
        Util.runAndWait(() -> {
            scene = new Scene(root, root == webView ? WIDTH : WIDTH + 100, HEIGHT, Color.WHITE);
            app.primaryStage.setScene(scene);
            app.primaryStage.show();
        });
    }

    private void load(String body) {
        CountDownLatch loaded = new CountDownLatch(1);
        Util.runAndWait(() -> {
            WebEngine engine = webView.getEngine();
            engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == SUCCEEDED) {
                    loaded.countDown();
                }
            });
            engine.loadContent("<html><head><style>body{margin:0;background:#fff}</style></head><body>" + body
                    + CONTROL_PATCH + "</body></html>", "text/html");
        });
        assertTrue(Util.await(loaded), "Timeout waiting for the page to load");
        Util.waitForIdle(scene, PULSES);
    }

    private Object executeScript(String script) {
        Object[] result = new Object[1];
        Util.runAndWait(() -> result[0] = webView.getEngine().executeScript(script));
        return result[0];
    }

    private void waitForScript(String condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Util.TIMEOUT);
        while (!Boolean.TRUE.equals(executeScript(condition))) {
            assertTrue(System.nanoTime() < deadline, "Timeout waiting for " + description);
            Util.sleep(20);
        }
    }
}
