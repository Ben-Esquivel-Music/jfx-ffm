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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.javafx.scene.web.PixelAssertions.CONTROL;
import static test.javafx.scene.web.PixelAssertions.CYAN;
import static test.javafx.scene.web.PixelAssertions.GREEN;
import static test.javafx.scene.web.PixelAssertions.MAGENTA;
import static test.javafx.scene.web.PixelAssertions.WHITE;
import static test.javafx.scene.web.PixelAssertions.YELLOW;
import static test.javafx.scene.web.PixelAssertions.assertSamples;
import static test.javafx.scene.web.PixelAssertions.pageWithControlPatch;

import com.sun.webkit.WebKitNativeShim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;

/**
 * A page drops the frames it recorded and nobody painted in two ways: a page with more than ten
 * unpainted frames drops them all and repaints the whole page, and a WebView that is not visible drops
 * them on every pulse. What a dropped frame carried, the decode commands of the images it drew
 * included, never reaches the screen.
 */
@ExtendWith(CaseAnnouncer.class)
public class DroppedFramesTest extends TestBase {

    /*
     * A frame is also dropped when a later opaque queue covers it, so each update changes a cell of
     * its own and the frames add up.
     */
    private static final int MAX_UPDATES_BEFORE_A_DROP = 20;
    /* About one id leaks per dropped change, far past the tolerance WebKitRegistryLeakTest uses. */
    private static final int GRADIENT_CHANGES = 20;
    private static final int REGISTRY_TOLERANCE = 2;

    private static final String CHANGE = "layer.style.background = '#0f0'; patch.style.background = '#ff0';";

    private static final String PAGE = pageWithControlPatch("""
            <div id=layer style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                transform:translateZ(0)'></div>
            <div id=patch style='position:absolute;left:220px;top:0;width:150px;height:100px;background:#00f'></div>
            <div id=nudge style='position:absolute;left:220px;top:150px;width:20px;height:20px;background:#000'></div>
            <script>
            for (let i = 0; i < 20; i++) {
                const cell = document.createElement('div');
                cell.id = 'cell' + i;
                cell.style.cssText = 'position:absolute;top:210px;width:8px;height:10px;background:#000;left:'
                    + (10 * i) + 'px';
                document.body.appendChild(cell);
            }
            </script>
            """);

    @Test
    public void afterTooManyUnpaintedFramesThePageShowsItsCurrentContent() {
        try (PumpedPage page = PumpedPage.withDefaultSettings(PAGE)) {
            assertCurrentAfterTooManyUnpaintedFrames(page, false);
        }
    }

    @Test
    public void afterTooManyUnpaintedFramesACompositedPageShowsItsCurrentContent() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            assertCurrentAfterTooManyUnpaintedFrames(page, true);
        }
    }

    private void assertCurrentAfterTooManyUnpaintedFrames(PumpedPage page, boolean composited) {
        page.settle();
        assertEquals(composited, page.isInCompositingMode(), "compositing mode before the change");

        int updates = page.updatesUntilFramesOverflow(update -> (update == 1 ? CHANGE : "")
                + "cell" + (update - 1) + ".style.background = '#888'", MAX_UPDATES_BEFORE_A_DROP);
        assertTrue(updates > 0, MAX_UPDATES_BEFORE_A_DROP + " updates without a paint did not make the page drop"
                + " its frames");

        page.settle();
        assertEquals(composited, page.isInCompositingMode(), "compositing mode after the drop");
        assertSamples("after " + updates + " updates without a paint", page.pump(),
                CONTROL, new Sample(100, 100, GREEN), new Sample(295, 50, YELLOW), new Sample(300, 200, WHITE));
    }

    /**
     * The frame that uploaded the changed layer and root tiles is dropped. Without a repaint of their
     * own, the tiles are drawn again by the next composited frame, here caused by a change elsewhere,
     * which repaints only part of the root tile at (0,0): the rest of that tile, the part of the patch in
     * it and the page around, must keep what the dropped upload put there.
     */
    @Test
    public void tilesUploadedInADroppedFrameAreCurrentInTheNextCompositedFrame() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");

            assertTrue(page.recordAndDropFrames("layer.style.background = '#f0f'; patch.style.background = '#0ff'"),
                    "the change recorded no frame, or its frames were not dropped");

            page.script("nudge.style.background = '#888'");
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page left compositing mode");
            assertSamples("after the dropped frame", page.pump(),
                    CONTROL, new Sample(100, 100, MAGENTA), new Sample(295, 50, CYAN), new Sample(230, 160, 0x888888),
                    new Sample(240, 50, CYAN), new Sample(210, 140, WHITE));
        }
    }

    /**
     * Each update paints the layer's gradient into its tile, whose commands reference the gradient, and
     * draws the tile through a command that decodes them; dropping the frame leaves them undecoded in
     * the tile's queue. When the page is destroyed with the tile, nothing can decode them any more, and
     * only disposing them gives back the ids of what they reference. The same page lifecycle without
     * dropped frames shows that the registry otherwise returns to where it started.
     */
    @Test
    public void undecodedTileBuffersOfADestroyedPageReleaseWhatTheyReference() throws Exception {
        String page = pageWithControlPatch("""
                <div id=layer style='position:absolute;left:0;top:0;width:200px;height:200px;
                    background:linear-gradient(#f00,#00f);transform:translateZ(0)'></div>
                """);
        for (boolean dropFrames : new boolean[] {false, true}) {
            int start = SettledRegistry.size();
            try (PumpedPage pumped = PumpedPage.withAcceleratedCompositing(page)) {
                pumped.settle();
                assertTrue(pumped.isInCompositingMode(), "the page is not in compositing mode");
                for (int change = 0; change < GRADIENT_CHANGES; change++) {
                    String recolour = "layer.style.background = 'linear-gradient(#f00,#"
                            + String.format("%02x", change) + "00ff)'";
                    if (dropFrames) {
                        assertTrue(pumped.recordAndDropFrames(recolour),
                                "change " + change + " recorded no frame, or its frames were not dropped");
                    } else {
                        pumped.script(recolour);
                        pumped.settle();
                    }
                }
                int held = WebKitNativeShim.registrySize();
                pumped.close();
                int end = SettledRegistry.size();
                String series = (dropFrames ? "with" : "without") + " dropped frames: " + start + " -> held "
                        + held + " -> after the page " + end;
                assertTrue(Math.abs(end - start) <= REGISTRY_TOLERANCE,
                        "the registry did not return to its baseline after the page was destroyed, " + series);
            }
        }
    }
}
