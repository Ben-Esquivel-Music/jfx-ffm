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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.javafx.scene.web.PixelAssertions.CONTROL;
import static test.javafx.scene.web.PixelAssertions.CONTROL_PATCH;
import static test.javafx.scene.web.PixelAssertions.GREEN;
import static test.javafx.scene.web.PixelAssertions.RED;
import static test.javafx.scene.web.PixelAssertions.WHITE;
import static test.javafx.scene.web.PixelAssertions.assertMatchesReference;
import static test.javafx.scene.web.PixelAssertions.assertPixel;
import static test.javafx.scene.web.PixelAssertions.assertSamples;

import com.sun.webkit.WebKitNativeShim;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;
import test.javafx.scene.web.PixelAssertions.Tolerance;

/**
 * Form controls are painted by the page's render theme, which reaches a drawing context only with the
 * page's own; the contexts of image buffers, into which WebKit paints a view-transition snapshot or the
 * input of a CSS filter, must draw them as the page does.
 */
@ExtendWith(CaseAnnouncer.class)
public class ThemedControlsInImageBuffersTest extends TestBase {

    private static final String CONTROLS = """
            <div style='position:absolute;left:0;top:0;width:200px;height:120px;background:#fff'>
            <button style='margin:10px;width:120px;height:40px'>Button</button><br>
            <input type=checkbox checked style='margin:10px;width:20px;height:20px'>
            </div>
            """;

    private static final String PATCH = "<div id=patch style='position:absolute;left:220px;top:20px;width:120px;"
            + "height:80px;background:#f00'></div>";

    /* Glyph and control edges may be anti-aliased differently in a buffer than in the back buffer. */
    private static final Tolerance CONTROL_EDGES = Tolerance.flatWithEdges(128, 1);

    /**
     * Held open with the group's own long animation, the transition shows the snapshot of the new
     * state; its callback turns only the patch green, so the snapshot must equal the page drawn with
     * the patch green and no transition.
     */
    @Test
    public void controlsInAViewTransitionSnapshotDrawAsOnThePage() {
        String page = "<html><head><style>body{margin:0;background:#fff}" + ViewTransitionContentTest.HELD_OPEN
                + "</style><script>"
                + """
                function startTransition() {
                    window.state = 'started';
                    window.transition = document.startViewTransition(function() {
                        document.getElementById('patch').style.background = '#0f0';
                    });
                    transition.ready.then(function() { state = 'ready'; }, function(e) { state = 'ready: ' + e; });
                    transition.finished.then(function() { state = 'finished'; });
                }
                """
                + "</script></head><body>" + CONTROLS + PATCH + CONTROL_PATCH + "</body></html>";
        try (PumpedPage newStatePage = PumpedPage.withDefaultSettingsAndFormControls(
                        page.replace("background:#f00'", "background:#0f0'"));
                PumpedPage transitioning = PumpedPage.withDefaultSettingsAndFormControls(page)) {
            BufferedImage newState = newStatePage.settle();
            assertSamples("the page without a transition", newState, CONTROL, new Sample(280, 60, GREEN));

            transitioning.settle();
            transitioning.script("startTransition()");
            BufferedImage during = transitioning.pumpUntil("state === 'ready'");
            assertEquals("ready", transitioning.script("state"),
                    "transition state after the frame drawn once ready");
            assertButtonFilledAsOnThePage("during the transition", during, newState);
            assertSamples("during the transition", during, CONTROL, new Sample(280, 60, GREEN));
            assertMatchesReference(newState, during, CONTROL_EDGES);
            transitioning.script("transition.skipTransition()");
            transitioning.pumpUntil("state === 'finished'");
        }
    }

    /**
     * A drop shadow that is fully transparent adds no pixel, and its offset keeps WebKit from treating
     * the filter as an identity, so the controls are painted into the filter's source buffer.
     */
    @Test
    public void controlsUnderASoftwareFilterDrawAsOnThePage() {
        String unfiltered = "<html><body style='margin:0;background:#fff'>" + CONTROLS + CONTROL_PATCH
                + "</body></html>";
        String filtered = unfiltered.replace("background:#fff'>\n<button",
                "background:#fff;filter:drop-shadow(1px 1px 0 transparent)'>\n<button");
        assertNotEquals(unfiltered, filtered, "the filter was not added to the fixture");

        try (PumpedPage referencePage = PumpedPage.withDefaultSettingsAndFormControls(unfiltered);
                PumpedPage filteredPage = PumpedPage.withDefaultSettingsAndFormControls(filtered)) {
            BufferedImage reference = referencePage.settle();
            BufferedImage drawn = filteredPage.settle();
            assertButtonFilledAsOnThePage("filtered", drawn, reference);
            assertSamples("filtered", drawn, CONTROL);
            assertMatchesReference(reference, drawn, CONTROL_EDGES);
        }
    }

    /*
     * Each control painted into an image buffer creates the widget it draws, one registry id, and asking the
     * page for its theme again would mint another. The ids one repaint creates are counted for two numbers of
     * controls, so that what the repaint costs besides the controls cancels out.
     */
    private static final int FEW_CONTROLS = 10;
    private static final int MANY_CONTROLS = 40;
    private static final double MAX_IDS_PER_CONTROL = 1.5;

    @Test
    public void aControlInAnImageBufferUsesThePageThemeWithoutAskingAgain() {
        int few = idsCreatedByARepaintOfTheFilteredControls(FEW_CONTROLS);
        int many = idsCreatedByARepaintOfTheFilteredControls(MANY_CONTROLS);
        double perControl = (double) (many - few) / (MANY_CONTROLS - FEW_CONTROLS);
        assertTrue(perControl < MAX_IDS_PER_CONTROL, String.format("a repaint created %d registry ids for %d"
                + " controls and %d for %d, %.2f per control", few, FEW_CONTROLS, many, MANY_CONTROLS, perControl));
    }

    /** The page's theme, which its controls in image buffers share, is released with the page. */
    @Test
    public void aPageThatPaintedControlsInAnImageBufferLeavesNothingInTheRegistry() throws Exception {
        int start = SettledRegistry.size();
        idsCreatedByARepaintOfTheFilteredControls(FEW_CONTROLS);
        assertEquals(start, SettledRegistry.size(), "registry size after the page was destroyed");
    }

    private static int idsCreatedByARepaintOfTheFilteredControls(int controls) {
        String html = "<html><body style='margin:0;background:#fff'><div id=filtered style='position:absolute;"
                + "left:0;top:0;width:400px;height:200px;background:#fff;filter:drop-shadow(1px 1px 0 transparent)'>"
                + "<input type=checkbox checked style='margin:2px;width:16px;height:16px'>".repeat(controls)
                + "</div>" + CONTROL_PATCH + "</body></html>";
        try (PumpedPage page = PumpedPage.withDefaultSettingsAndFormControls(html)) {
            page.settle();
            long before = nextRegistryId();
            page.script("filtered.style.background = '#eee'; filtered.offsetWidth");
            BufferedImage repainted = page.pump();
            long after = nextRegistryId();
            assertPixel("the pump measured, which must draw the repaint", repainted, 390, 190, 0xeeeeee);
            return (int) (after - before - 1);
        }
    }

    /** Registry ids are handed out in order, so a probe's id tells how many were handed out before it. */
    private static long nextRegistryId() {
        long id = WebKitNativeShim.register(new Object());
        WebKitNativeShim.unregister(id);
        return id;
    }

    /** Inside the button, clear of its label: the theme's fill, which a missing theme leaves white. */
    private static void assertButtonFilledAsOnThePage(String rendering, BufferedImage drawn, BufferedImage page) {
        assertNotEquals(0xffffffff, page.getRGB(20, 20), "the page drew no button, so the comparison means nothing");
        for (int[] point : new int[][] {{20, 20}, {120, 20}, {20, 40}, {120, 40}}) {
            assertPixel(rendering + " button", drawn, point[0], point[1], page.getRGB(point[0], point[1]) & 0xffffff);
        }
    }

    /**
     * An SVG image is drawn by a page of its own that has no Java WebPage, so a control in it has no
     * render theme to ask; the theme of the page that draws the image is not asked either. The control
     * is left unpainted, as before themes reached image buffers, nothing is logged as a failed upcall,
     * and the next canvas still gets its backing store.
     */
    @Test
    public void aControlInAnImageWithoutAPageIsLeftUnpaintedWithoutAFailure() {
        Logger logger = Logger.getLogger("com.sun.webkit.WebKitNative");
        List<String> severe = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE) {
                    severe.add(record.getMessage() + ": " + record.getThrown());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try (PumpedPage page = PumpedPage.withDefaultSettings(IMAGE_WITH_A_BUTTON_PAGE)) {
            page.pumpUntil("state !== 'loading'");
            assertEquals("drawn", page.script("state"), "the SVG image's state");
            BufferedImage drawn = page.settle();
            assertSamples("the canvases", drawn, new Sample(60, 20, WHITE), new Sample(270, 50, RED));
        } finally {
            logger.removeHandler(handler);
        }
        assertEquals(List.of(), severe, "failed upcalls logged while the image was drawn");
    }

    private static final String IMAGE_WITH_A_BUTTON_PAGE = """
            <html><body style='margin:0;background:#fff'>
            <canvas id=withImage width=200 height=100 style='position:absolute;left:0;top:0'></canvas>
            <canvas id=next width=100 height=100 style='position:absolute;left:220px;top:0'></canvas>
            <script>
            window.state = 'loading';
            const svg = "<svg xmlns='http://www.w3.org/2000/svg' width='200' height='100'>"
                + "<foreignObject width='200' height='100'><div xmlns='http://www.w3.org/1999/xhtml'>"
                + "<button style='width:120px;height:40px'>Button</button></div></foreignObject></svg>";
            const image = new Image();
            image.onload = function() {
                withImage.getContext('2d').drawImage(image, 0, 0);
                const context = next.getContext('2d');
                context.fillStyle = '#f00';
                context.fillRect(0, 0, 100, 100);
                state = 'drawn';
            };
            image.onerror = function() { state = 'error'; };
            image.src = 'data:image/svg+xml,' + encodeURIComponent(svg);
            </script></body></html>
            """;
}
