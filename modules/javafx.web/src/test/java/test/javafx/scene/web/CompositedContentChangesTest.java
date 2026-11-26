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
import static test.javafx.scene.web.PixelAssertions.BLUE;
import static test.javafx.scene.web.PixelAssertions.CONTROL;
import static test.javafx.scene.web.PixelAssertions.CYAN;
import static test.javafx.scene.web.PixelAssertions.RED;
import static test.javafx.scene.web.PixelAssertions.WHITE;
import static test.javafx.scene.web.PixelAssertions.YELLOW;
import static test.javafx.scene.web.PixelAssertions.assertMatchesReference;
import static test.javafx.scene.web.PixelAssertions.assertSamples;
import static test.javafx.scene.web.PixelAssertions.pageWithControlPatch;

import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;
import test.javafx.scene.web.PixelAssertions.Tolerance;

/**
 * Composited pages that change after their first frame: an animation, scrolling, resizing, leaving
 * compositing mode, and 3D content that the software pipeline cannot draw faithfully. Each change runs
 * the same way on a page with accelerated compositing off, the reference.
 */
@ExtendWith(CaseAnnouncer.class)
public class CompositedContentChangesTest extends TestBase {

    private static final int PUMPS_OF_A_RUNNING_ANIMATION = 5;

    /**
     * A transform animation of 1000 s, sought to its middle: 50 px of travel, plus at most 1 px for every
     * 10 s the test takes, so the samples keep 15 px from the box's edges and the oracle allows its edges.
     */
    @Test
    public void transformAnimationDrawsLikeTheReference() {
        String html = pageWithControlPatch("""
                <div id=box style='position:absolute;left:0;top:0;width:100px;height:100px;background:#f00'></div>
                <script>
                window.motion = document.getElementById('box').animate(
                    [{transform: 'translateX(0px)'}, {transform: 'translateX(100px)'}], {duration: 1000000});
                motion.currentTime = 500000;
                </script>
                """);
        Sample[] samples = {
            CONTROL, new Sample(65, 50, RED), new Sample(130, 50, RED), new Sample(35, 50, WHITE),
            new Sample(170, 50, WHITE)
        };

        try (PumpedPage referencePage = PumpedPage.withDefaultSettings(html);
                PumpedPage compositedPage = PumpedPage.withAcceleratedCompositing(html)) {
            BufferedImage reference = animate(referencePage);
            assertFalse(referencePage.isInCompositingMode(), "the reference page is in compositing mode");
            assertSamples("reference", reference, samples);

            BufferedImage composited = animate(compositedPage);
            assertTrue(compositedPage.isInCompositingMode(), "the page is not in compositing mode");
            assertSamples("composited", composited, samples);
            assertMatchesReference(reference, composited, Tolerance.flatWithEdges(255, 2));
        }
    }

    private static BufferedImage animate(PumpedPage page) {
        BufferedImage image = page.pumpUntil("motion.playState === 'running'");
        for (int pump = 0; pump < PUMPS_OF_A_RUNNING_ANIMATION; pump++) {
            image = page.pump();
        }
        assertEquals("running", page.script("motion.playState"), "animation play state");
        return image;
    }

    /**
     * Three bands of 300 px and a composited box in the middle one, scrolled by 200 px: the box moves
     * with the page, and where the first band was drawn before the scroll the second must show.
     */
    @Test
    public void scrolledPageDrawsLikeTheReference() {
        String html = """
                <html><head><style>
                html{scrollbar-width:none} body{margin:0;height:900px;background:#fff}
                div{position:absolute;left:0;width:400px;height:300px}
                </style></head><body>
                <div style='top:0;background:#ff0'></div>
                <div style='top:300px;background:#0ff'></div>
                <div style='top:600px;background:#f0f'></div>
                <div style='top:320px;left:20px;width:100px;height:100px;background:#f00;transform:translateZ(0)'></div>
                </body></html>
                """;
        Consumer<PumpedPage> scroll = page -> {
            page.script("window.scrollTo(0, 200)");
            page.pumpUntil("window.scrollY === 200");
        };
        assertChangeDrawsLikeTheReference(html, scroll, true,
                new Sample(200, 50, YELLOW), new Sample(300, 200, CYAN), new Sample(70, 170, RED),
                new Sample(70, 250, CYAN), new Sample(200, 140, CYAN));
    }

    /** From 400 x 300 to 300 x 200: the layer and the control, sized from the viewport, follow. */
    @Test
    public void resizedPageDrawsLikeTheReference() {
        String html = """
                <html><head><style>body{margin:0;background:#fff}</style></head><body>
                <div style='position:absolute;left:0;top:0;width:50vw;height:50vh;background:#f00;
                    transform:translateZ(0)'></div>
                <div style='position:absolute;right:0;bottom:0;width:25vw;height:25vh;background:#00f'></div>
                </body></html>
                """;
        assertChangeDrawsLikeTheReference(html, page -> page.resize(300, 200), true,
                new Sample(75, 50, RED), new Sample(160, 50, WHITE), new Sample(262, 175, BLUE),
                new Sample(200, 175, WHITE));
    }

    /**
     * Dropping the only reason for a layer takes the page out of compositing mode; the box it moved away
     * from must not stay drawn, and the patch that was never invalidated must still be drawn.
     */
    @Test
    public void pageLeavingCompositingModeDrawsLikeTheReference() {
        String html = pageWithControlPatch("""
                <div id=box style='position:absolute;left:0;top:0;width:150px;height:150px;background:#f00;
                    transform:translateZ(0)'></div>
                """);
        Consumer<PumpedPage> leave = page -> {
            page.script("box.style.transform = 'none'; box.style.left = '200px'");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (page.isInCompositingMode()) {
                if (System.nanoTime() > deadline) {
                    fail("the page stayed in compositing mode");
                }
                page.pump();
            }
        };
        assertChangeDrawsLikeTheReference(html, leave, false,
                CONTROL, new Sample(75, 75, WHITE), new Sample(275, 75, RED), new Sample(75, 200, WHITE));
    }

    /**
     * The box moves by its transform, which repaints nothing, while a small patch elsewhere in the same
     * 256 px root tile is recoloured, which repaints that patch only. The composited frame is drawn over
     * the back buffer, which still holds the box where it was; only the rest of the root tile, kept from
     * its last upload, covers that place with the page.
     */
    @Test
    public void aPartialTileUpdateKeepsTheRestOfTheTile() {
        String html = pageWithControlPatch("""
                <div id=box style='position:absolute;left:20px;top:20px;width:60px;height:60px;background:#f00;
                    transform:translateZ(0)'></div>
                <div id=patch style='position:absolute;left:200px;top:150px;width:40px;height:40px;
                    background:#00f'></div>
                """);
        Consumer<PumpedPage> moveAndRecolour = page -> {
            page.script("box.style.transform = 'translate(100px, 0) translateZ(0)';"
                    + " patch.style.background = '#ff0'");
            page.pump();
        };
        assertChangeDrawsLikeTheReference(html, moveAndRecolour, true,
                CONTROL, new Sample(50, 50, WHITE), new Sample(150, 50, RED), new Sample(220, 170, YELLOW));
    }

    /**
     * A patch is recoloured and the only composited element removed in one script turn, so the
     * patch's repaint is issued while the page is still composited; after the next frame the page has
     * left compositing mode and must show both changes.
     */
    @Test
    public void changesMadeAsThePageLeavesCompositingModeAreDrawn() {
        String html = pageWithControlPatch("""
                <div id=box style='position:absolute;left:0;top:0;width:150px;height:150px;background:#f00;
                    transform:translateZ(0)'></div>
                <div id=patch style='position:absolute;left:220px;top:20px;width:60px;height:60px;
                    background:#00f'></div>
                """);
        Consumer<PumpedPage> recolourAndLeave = page -> {
            page.script("patch.style.background = '#ff0'; box.remove()");
            page.pump();
        };
        assertChangeDrawsLikeTheReference(html, recolourAndLeave, false,
                CONTROL, new Sample(75, 75, WHITE), new Sample(250, 50, YELLOW));
    }

    /** The same, with the changes made by an animation frame, inside the rendering update of a frame. */
    @Test
    public void changesMadeByAnAnimationFrameAsThePageLeavesCompositingModeAreDrawn() {
        String html = pageWithControlPatch("""
                <div id=box style='position:absolute;left:0;top:0;width:150px;height:150px;background:#f00;
                    transform:translateZ(0)'></div>
                <div id=patch style='position:absolute;left:220px;top:20px;width:60px;height:60px;
                    background:#00f'></div>
                """);
        Consumer<PumpedPage> recolourAndLeave = page -> {
            page.script("window.changed = false; requestAnimationFrame(function() {"
                    + " patch.style.background = '#ff0'; box.remove(); changed = true; })");
            page.pumpUntil("changed");
        };
        assertChangeDrawsLikeTheReference(html, recolourAndLeave, false,
                CONTROL, new Sample(75, 75, WHITE), new Sample(250, 50, YELLOW));
    }

    /**
     * A transform animation that only the compositor runs: nothing else invalidates the page, so the
     * page draws a new frame only because the running animation asks for one. The box leaves its start
     * position within a second of the three it takes to travel 300 px, and the frame that first shows
     * the start position uncovered shows the box further along the same row. The animation keeps its
     * end state, so pumps stalled past its end show the box at 300 px; the test then fails saying the
     * animation finished first, not that the box never moved.
     */
    @Test
    public void aRunningCompositedAnimationKeepsDrawingFrames() {
        String html = pageWithControlPatch("""
                <div id=box style='position:absolute;left:0;top:100px;width:50px;height:50px;background:#f00'></div>
                <script>
                window.motion = document.getElementById('box').animate(
                    [{transform: 'translateX(0px)'}, {transform: 'translateX(300px)'}],
                    {duration: 3000, fill: 'forwards'});
                </script>
                """);
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(html)) {
            page.pumpUntil("motion.playState === 'running'");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            BufferedImage frame = page.pump();
            while ((frame.getRGB(25, 125) & 0xffffff) != WHITE) {
                if (System.nanoTime() > deadline) {
                    Object state = page.script("motion.playState");
                    fail("finished".equals(state)
                            ? "the pumps stalled until the 3 s animation had finished"
                            : "after 2 s of a 3 s animation the box still covers its start position: "
                                    + Integer.toHexString(frame.getRGB(25, 125)) + ", play state " + state);
                }
                frame = page.pump();
            }
            Object state = page.script("motion.playState");
            if ("finished".equals(state)) {
                fail("the pumps stalled until the 3 s animation had finished");
            }
            assertEquals("running", state, "animation play state");
            assertTrue(redInRow(frame, 125, 50, 349),
                    "the frame that uncovered the start position shows no box further along its row");
        }
    }

    private static boolean redInRow(BufferedImage image, int y, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            if ((image.getRGB(x, y) & 0xffffff) == RED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders the page with compositing off and on, applies the change to both, and compares what they
     * draw afterwards. The composited page is in compositing mode before the change, and after it as
     * {@code compositedAfterChange} says.
     */
    private void assertChangeDrawsLikeTheReference(String html, Consumer<PumpedPage> change,
            boolean compositedAfterChange, Sample... samples) {
        try (PumpedPage referencePage = PumpedPage.withDefaultSettings(html);
                PumpedPage compositedPage = PumpedPage.withAcceleratedCompositing(html)) {
            referencePage.settle();
            change.accept(referencePage);
            referencePage.settle();
            assertFalse(referencePage.isInCompositingMode(), "the reference page is in compositing mode");
            BufferedImage reference = referencePage.pump();
            assertSamples("reference", reference, samples);

            compositedPage.settle();
            assertTrue(compositedPage.isInCompositingMode(),
                    "the page is not in compositing mode before the change");
            change.accept(compositedPage);
            compositedPage.settle();
            assertEquals(compositedAfterChange, compositedPage.isInCompositingMode(),
                    "compositing mode after the change");
            BufferedImage composited = compositedPage.pump();
            assertSamples("composited", composited, samples);
            assertMatchesReference(reference, composited, Tolerance.FLAT_FILLS);
        }
    }

    @Test
    public void flattenedPreserve3DSurvives() {
        assertSurvives("""
                <div style='position:absolute;left:20px;top:20px;width:160px;height:160px;perspective:500px'>
                <div style='width:160px;height:160px;transform-style:preserve-3d;transform:rotateY(30deg);
                    background:#f00'>
                <div style='width:80px;height:80px;transform-style:flat;transform:translateZ(30px);background:#0f0'>
                <div style='width:40px;height:40px;transform:rotateX(30deg);background:#00f'></div>
                </div></div></div>
                """);
    }

    @Test
    public void intersectingPreserve3DPlanesSurvive() {
        assertSurvives("""
                <div style='position:absolute;left:20px;top:20px;width:160px;height:160px;perspective:600px'>
                <div style='position:relative;width:160px;height:160px;transform-style:preserve-3d'>
                <div style='position:absolute;width:160px;height:160px;background:rgba(255,0,0,0.7);
                    transform:rotateY(45deg)'></div>
                <div style='position:absolute;width:160px;height:160px;background:rgba(0,255,0,0.7);
                    transform:rotateY(-45deg)'></div>
                </div></div>
                """);
    }

    /**
     * The software pipeline ignores the perspective, which carries the whole placement of a projected
     * layer, so the layer must not be drawn unplaced at the origin of its target, left of and above the
     * 3D container.
     */
    @Test
    public void perspectiveRotationSurvives() {
        assertSurvives("""
                <div style='position:absolute;left:20px;top:20px;width:160px;height:160px;perspective:400px'>
                <div style='width:160px;height:160px;background:#f00;transform:rotateY(40deg)'></div>
                </div>
                """, new Sample(10, 10, WHITE));
    }

    /**
     * The software pipeline cannot draw these as a 3D pipeline would, so only survival is asserted:
     * scripts still run, pumps still return, and the patch outside the 3D content is drawn, with the
     * given samples outside it too.
     */
    private void assertSurvives(String body, Sample... outside) {
        String html = pageWithControlPatch(body);
        try (PumpedPage referencePage = PumpedPage.withDefaultSettings(html);
                PumpedPage compositedPage = PumpedPage.withAcceleratedCompositing(html)) {
            BufferedImage reference = referencePage.settle();
            assertSamples("reference control patch", reference, CONTROL);
            assertSamples("reference", reference, outside);

            compositedPage.settle();
            assertTrue(compositedPage.isInCompositingMode(), "the page is not in compositing mode");
            assertEquals(2, ((Number) compositedPage.script("1 + 1")).intValue(), "script result");
            BufferedImage composited = compositedPage.pump();
            assertSamples("composited control patch", composited, CONTROL);
            assertSamples("composited", composited, outside);
        }
    }
}
