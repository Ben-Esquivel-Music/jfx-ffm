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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.javafx.scene.web.PixelAssertions.BLUE;
import static test.javafx.scene.web.PixelAssertions.CONTROL;
import static test.javafx.scene.web.PixelAssertions.RED;
import static test.javafx.scene.web.PixelAssertions.WHITE;
import static test.javafx.scene.web.PixelAssertions.assertSamples;
import static test.javafx.scene.web.PixelAssertions.pageWithControlPatch;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;

/**
 * Composited pages whose correct pixels the page with compositing off does not draw, asserted against
 * pixels computed from CSS: the reference flattens 3D transforms, and the stated limit for filters
 * differs from it by design.
 */
@ExtendWith(CaseAnnouncer.class)
public class CompositedPixelsTest extends TestBase {

    private static final int MARGIN = 10;
    private static final int PERSPECTIVE = 500;
    private static final int PERSPECTIVE_ORIGIN = 150;

    /**
     * The filter is painted in software into the container's own layer, so the container's own content
     * is filtered and its composited descendant, a layer of its own, is not.
     */
    @Test
    public void aFilterDoesNotReachACompositedDescendant() {
        assertComposited(pageWithControlPatch("""
                <div style='position:absolute;left:20px;top:20px;width:300px;height:150px;filter:grayscale(1)'>
                <div style='position:absolute;left:0;top:0;width:120px;height:150px;background:#f00'></div>
                <div style='position:absolute;left:160px;top:0;width:120px;height:150px;background:#f00;
                    transform:translateZ(0)'></div>
                </div>
                """), new Sample(80, 95, 0x363636, 3), new Sample(240, 95, RED), new Sample(160, 95, WHITE));
    }

    @Test
    public void aTranslatedLayerUnderAPerspectiveIsPlacedByItsTranslation() {
        assertComposited(pageWithControlPatch(perspectiveContainer("""
                <div style='position:absolute;left:20px;top:20px;width:100px;height:100px;background:#f00;
                    transform:translate(40px,20px) translateZ(0)'></div>
                """)), rectangleSamples(60, 40, 160, 140));
    }

    /**
     * {@code translateZ(z)} under {@code perspective(p)} scales the layer by {@code p / (p - z)} about
     * the perspective origin, the container's centre.
     */
    @Test
    public void aTranslateZLayerUnderAPerspectiveIsScaledAboutThePerspectiveOrigin() {
        int left = 30;
        int top = 40;
        int size = 100;
        int z = 50;
        double scale = (double) PERSPECTIVE / (PERSPECTIVE - z);
        Sample[] samples = rectangleSamples(projected(left, scale), projected(top, scale),
                projected(left + size, scale), projected(top + size, scale));
        assertTrue(samples[0].x() < left && samples[0].y() < top,
                "the top-left sample must lie outside the unscaled layer, so that only the scaling covers it");
        assertComposited(pageWithControlPatch(perspectiveContainer(
                "<div style='position:absolute;left:" + left + "px;top:" + top + "px;width:" + size + "px;height:"
                + size + "px;background:#f00;transform:translateZ(" + z + "px)'></div>")), samples);
    }

    /**
     * A whole turn leaves sin(2 pi), about -2.4e-16, in the layer's matrix, which a perspective turns into a
     * projective part of about 1e-19: the layer still rests flat where it started.
     */
    @Test
    public void aLayerTurnedAWholeTurnAboutYUnderAPerspectiveIsDrawnFlat() {
        assertComposited(pageWithControlPatch(perspectiveContainer(turnedBox("rotateY(360deg)"))),
                rectangleSamples(20, 20, 120, 120));
    }

    @Test
    public void aLayerTurnedAWholeTurnAboutXUnderAPerspectiveIsDrawnFlat() {
        assertComposited(pageWithControlPatch(perspectiveContainer(turnedBox("rotateX(360deg)"))),
                rectangleSamples(20, 20, 120, 120));
    }

    @Test
    public void aLayerTurnedTwoWholeTurnsUnderItsOwnPerspectiveIsDrawnFlat() {
        assertComposited(pageWithControlPatch(turnedBox("perspective(" + PERSPECTIVE + "px) rotateY(720deg)")),
                rectangleSamples(20, 20, 120, 120));
    }

    /**
     * WebKit snaps the sine and cosine of multiples of 90 degrees to exact values, so a half turn carries no
     * projective part at all. The flip mirrors the box about its own centre, which a box of one colour hides.
     */
    @Test
    public void aLayerTurnedAHalfTurnUnderAPerspectiveShowsItsMirroredBackFace() {
        assertComposited(pageWithControlPatch(perspectiveContainer(turnedBox("rotateY(180deg)"))),
                rectangleSamples(20, 20, 120, 120));
    }

    /**
     * Two planes of 200 px in one preserve-3d context, turned 45 degrees either way about their common
     * centre (200,150) and drawn without perspective, so each spans 200 ± 100 cos45. They cut each
     * other along the centre line: {@code rotateY(45deg)} comes toward the viewer on the left
     * ({@code z = -x sin45}), {@code rotateY(-45deg)} on the right, whatever the DOM order.
     */
    @Test
    public void intersectingPlanesShowTheFrontPlaneOnEachSideOfTheirIntersection() {
        double halfWidth = 100 * Math.cos(Math.toRadians(45));
        int left = (int) Math.floor(200 - halfWidth);
        int right = (int) Math.ceil(200 + halfWidth);
        assertComposited(pageWithControlPatch("""
                <div style='position:absolute;left:100px;top:50px;width:200px;height:200px;
                    transform-style:preserve-3d'>
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                    transform:rotateY(45deg)'></div>
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#00f;
                    transform:rotateY(-45deg)'></div>
                </div>
                """), new Sample(160, 150, RED), new Sample(160, 70, RED), new Sample(left + 1 + MARGIN, 150, RED),
                new Sample(240, 150, BLUE), new Sample(240, 230, BLUE), new Sample(right - 1 - MARGIN, 150, BLUE),
                new Sample(left - MARGIN, 150, WHITE), new Sample(right + MARGIN, 150, WHITE),
                new Sample(200, 40, WHITE));
    }

    /**
     * The same two planes, both turned 30 degrees about the view axis first, so that the line they cut
     * each other along is slanted: then the box around either plane's front part reaches across the
     * line, and only the clip to the part's own polygon keeps it on its side. A point at {@code a}
     * across and {@code b} along the planes, where {@code a} is the turned plane's projected x, lands
     * at {@code (200 + a cos30 - b sin30, 150 + a sin30 + b cos30)}; the red plane is in front where
     * {@code -70.7 < a < 0}, the blue one where {@code 0 < a < 70.7}, for {@code |b| < 100}. The
     * red sample beside the line at {@code (-10, 60)} lies in the box of the blue plane's front part,
     * which is drawn last.
     */
    @Test
    public void slantedIntersectingPlanesShowTheFrontPlaneOnEachSideOfTheirIntersection() {
        assertComposited(pageWithControlPatch("""
                <div style='position:absolute;left:100px;top:50px;width:200px;height:200px;
                    transform-style:preserve-3d'>
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                    transform:rotateZ(30deg) rotateY(45deg)'></div>
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#00f;
                    transform:rotateZ(30deg) rotateY(-45deg)'></div>
                </div>
                """), slanted(-10, 60, RED), slanted(-40, 0, RED), slanted(-40, -60, RED), slanted(-55, 80, RED),
                slanted(10, -60, BLUE), slanted(40, 0, BLUE), slanted(40, 60, BLUE), slanted(55, -80, BLUE),
                slanted(-82, 0, WHITE), slanted(82, 0, WHITE), slanted(-30, 112, WHITE), slanted(30, -112, WHITE));
    }

    private static Sample slanted(double a, double b, int rgb) {
        double radians = Math.toRadians(30);
        return new Sample((int) Math.round(200 + a * Math.cos(radians) - b * Math.sin(radians)),
                (int) Math.round(150 + a * Math.sin(radians) + b * Math.cos(radians)), rgb);
    }

    private static String perspectiveContainer(String child) {
        return "<div style='position:absolute;left:0;top:0;width:300px;height:300px;perspective:" + PERSPECTIVE
                + "px'>" + child + "</div>";
    }

    private static String turnedBox(String transform) {
        return "<div style='position:absolute;left:20px;top:20px;width:100px;height:100px;background:#f00;"
                + "transform:" + transform + "'></div>";
    }

    private static double projected(int coordinate, double scale) {
        return PERSPECTIVE_ORIGIN + (coordinate - PERSPECTIVE_ORIGIN) * scale;
    }

    /** Red just inside each corner of the rectangle, white just outside each edge's middle. */
    private static Sample[] rectangleSamples(double left, double top, double right, double bottom) {
        int innerLeft = (int) Math.ceil(left) + MARGIN;
        int innerTop = (int) Math.ceil(top) + MARGIN;
        int innerRight = (int) Math.floor(right) - MARGIN;
        int innerBottom = (int) Math.floor(bottom) - MARGIN;
        int middleX = (int) ((left + right) / 2);
        int middleY = (int) ((top + bottom) / 2);
        return new Sample[] {
            new Sample(innerLeft, innerTop, RED), new Sample(innerRight, innerTop, RED),
            new Sample(innerLeft, innerBottom, RED), new Sample(innerRight, innerBottom, RED),
            new Sample((int) Math.floor(left) - MARGIN, middleY, WHITE),
            new Sample((int) Math.ceil(right) + MARGIN, middleY, WHITE),
            new Sample(middleX, (int) Math.floor(top) - MARGIN, WHITE),
            new Sample(middleX, (int) Math.ceil(bottom) + MARGIN, WHITE)
        };
    }

    private static void assertComposited(String html, Sample... samples) {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(html)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            BufferedImage composited = page.pump();
            assertSamples("composited control patch", composited, CONTROL);
            assertSamples("composited", composited, samples);
        }
    }
}
