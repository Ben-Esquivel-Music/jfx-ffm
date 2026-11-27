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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.javafx.scene.web.PixelAssertions.BLUE;
import static test.javafx.scene.web.PixelAssertions.CONTROL;
import static test.javafx.scene.web.PixelAssertions.GREEN;
import static test.javafx.scene.web.PixelAssertions.RED;
import static test.javafx.scene.web.PixelAssertions.WHITE;
import static test.javafx.scene.web.PixelAssertions.YELLOW;
import static test.javafx.scene.web.PixelAssertions.assertMatchesReference;
import static test.javafx.scene.web.PixelAssertions.assertPixel;
import static test.javafx.scene.web.PixelAssertions.assertSamples;
import static test.javafx.scene.web.PixelAssertions.pageWithControlPatch;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;
import test.javafx.scene.web.PixelAssertions.Tolerance;

/**
 * Each static page is rendered twice, on pages of their own: with accelerated compositing off, the
 * reference, and on. Both must show the same pixels. {@code transform:translateZ(0)} gives an element a
 * compositing layer; the 2D transforms keep it so that the layer's transform stays affine.
 */
@ExtendWith(CaseAnnouncer.class)
public class CompositedContentTest extends TestBase {

    /** Edges of a resampled texture or of an anti-aliased curve land a little differently. */
    private static final Tolerance RESAMPLED_EDGES = Tolerance.flatWithEdges(255, 1);
    private static final Tolerance SCALED_EDGES = Tolerance.flatWithEdges(255, 2);

    @Test
    public void translateZBoxDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:0;top:0;width:200px;height:200px;background:#f00;
                    transform:translateZ(0)'></div>
                """, Tolerance.FLAT_FILLS,
                new Sample(100, 100, RED), new Sample(300, 100, WHITE));
    }

    @Test
    public void directlyCompositedImageDrawsLikeTheReference() {
        assertDrawsLikeTheReference("<img style='position:absolute;left:20px;top:20px;transform:translateZ(0)' src='"
                + quadrantsPng(100, 100) + "'>", Tolerance.FLAT_FILLS,
                new Sample(45, 45, RED), new Sample(95, 45, GREEN),
                new Sample(45, 95, BLUE), new Sample(95, 95, YELLOW), new Sample(150, 70, WHITE));
    }

    /**
     * Text and themed form controls, in a composited layer on the left and in the page on the right. A
     * glyph or a control edge may be anti-aliased differently in a layer's tile than in the page's back
     * buffer (sub-pixel against grey-scale), so pixels next to an edge may differ by up to half the
     * range; the controls' fills, which are gradients, are compared point by point within the flat-fill
     * tolerance, clear of their labels.
     */
    @Test
    public void textAndFormControlsDrawLikeTheReference() {
        String controls = """
                <p style='position:absolute;left:10px;top:0;margin:0;font:16px sans-serif'>Text 123</p>
                <button style='position:absolute;left:10px;top:40px;width:100px;height:30px'>Button</button>
                <input type=checkbox checked style='position:absolute;left:10px;top:85px;width:20px;height:20px;
                    margin:0'>
                <input type=text value=abc style='position:absolute;left:10px;top:120px;width:100px;height:24px;
                    margin:0'>
                <select style='position:absolute;left:10px;top:160px;width:100px;height:24px;margin:0'>
                    <option>One</option></select>
                """;
        String html = pageWithControlPatch("<div style='position:absolute;left:0;top:0;width:150px;height:220px;"
                + "background:#fff;transform:translateZ(0)'>" + controls + "</div>"
                + "<div style='position:absolute;left:150px;top:0;width:150px;height:220px'>" + controls + "</div>");

        try (PumpedPage referencePage = PumpedPage.withDefaultSettingsAndFormControls(html);
                PumpedPage compositedPage = PumpedPage.withAcceleratedCompositingAndFormControls(html)) {
            BufferedImage reference = referencePage.settle();
            assertFalse(referencePage.isInCompositingMode(), "the reference page is in compositing mode");
            assertNotEquals(0xffffffff, reference.getRGB(20, 50), "the reference drew no button");

            compositedPage.settle();
            assertTrue(compositedPage.isInCompositingMode(), "the page is not in compositing mode");
            BufferedImage composited = compositedPage.pump();
            assertSamples("composited control patch", composited, CONTROL);
            for (int column : new int[] {0, 150}) {
                for (int[] fill : new int[][] {{20, 50}, {100, 60}, {100, 132}, {65, 172}}) {
                    int x = column + fill[0];
                    assertPixel("composited control fill", composited, x, fill[1],
                            reference.getRGB(x, fill[1]) & 0xffffff);
                }
            }
            assertMatchesReference(reference, composited, Tolerance.flatWithEdges(128, 1));
        }
    }

    @Test
    public void translatedLayerDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:20px;top:20px;width:100px;height:100px;background:#f00;
                    transform:translate(100px,50px) translateZ(0)'></div>
                """, Tolerance.FLAT_FILLS,
                new Sample(170, 120, RED), new Sample(70, 70, WHITE));
    }

    @Test
    public void scaledLayerDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:50px;top:50px;width:100px;height:100px;background:#f00;
                    transform:scale(1.5) translateZ(0)'></div>
                """, SCALED_EDGES,
                new Sample(100, 100, RED), new Sample(35, 35, RED), new Sample(165, 165, RED),
                new Sample(185, 100, WHITE));
    }

    @Test
    public void quarterTurnLayerDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:40px;top:60px;width:160px;height:80px;background:#f00;
                    transform:rotate(90deg) translateZ(0)'></div>
                """, RESAMPLED_EDGES,
                new Sample(120, 30, RED), new Sample(120, 170, RED), new Sample(50, 100, WHITE),
                new Sample(190, 100, WHITE));
    }

    @Test
    public void eighthTurnLayerDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:50px;top:50px;width:120px;height:120px;background:#f00;
                    transform:rotate(45deg) translateZ(0)'></div>
                """, SCALED_EDGES);
    }

    /*
     * The blur runs in software in both renderings, into buffers of different origin, so the gradient
     * may round differently by a few levels; flat pixels may not differ.
     */
    @Test
    public void blurredLayerDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:40px;top:40px;width:100px;height:100px;background:#f00;
                    filter:blur(4px);transform:translateZ(0)'></div>
                """, Tolerance.flatWithEdges(8, 1),
                new Sample(90, 90, RED), new Sample(200, 90, WHITE));
    }

    @Test
    public void dropShadowedLayerDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:40px;top:40px;width:100px;height:100px;background:#f00;
                    filter:drop-shadow(30px 30px 0 #00f);transform:translateZ(0)'></div>
                """, Tolerance.FLAT_FILLS,
                new Sample(90, 90, RED), new Sample(155, 110, BLUE), new Sample(110, 155, BLUE),
                new Sample(155, 55, WHITE));
    }

    /** grayscale(1) of pure red is its luminance, 0.2126 * 255 = 54. */
    @Test
    public void grayscaledLayerDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:40px;top:40px;width:100px;height:100px;background:#f00;
                    filter:grayscale(1);transform:translateZ(0)'></div>
                """, Tolerance.FLAT_FILLS,
                new Sample(90, 90, 0x363636, 3), new Sample(200, 90, WHITE));
    }

    /**
     * Stripes of 40 px across a 380 px layer; the mapper's 256 px tile edge falls inside the opaque stripe
     * from 240 to 280.
     */
    @Test
    public void maskWiderThanOneTileDrawsLikeTheReference() {
        String stripes = "repeating-linear-gradient(to right,#000 0 40px,transparent 40px 80px)";
        assertDrawsLikeTheReference("<div style='position:absolute;left:10px;top:20px;width:380px;height:100px;"
                + "background:#f00;transform:translateZ(0);-webkit-mask-image:" + stripes + ";mask-image:"
                + stripes + "'></div>", RESAMPLED_EDGES,
                new Sample(30, 70, RED), new Sample(70, 70, WHITE), new Sample(190, 70, RED),
                new Sample(230, 70, WHITE), new Sample(260, 70, RED), new Sample(280, 70, RED),
                new Sample(310, 70, WHITE), new Sample(350, 70, RED));
    }

    @Test
    public void smallMaskDrawsLikeTheReference() {
        String half = "linear-gradient(to right,#000 50%,transparent 50%)";
        assertDrawsLikeTheReference("<div style='position:absolute;left:20px;top:20px;width:200px;height:100px;"
                + "background:#f00;transform:translateZ(0);-webkit-mask-image:" + half + ";mask-image:" + half
                + "'></div>", RESAMPLED_EDGES,
                new Sample(70, 70, RED), new Sample(170, 70, WHITE));
    }

    @Test
    public void clipPathDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:20px;top:20px;width:200px;height:200px;background:#f00;
                    clip-path:circle(80px at 100px 100px);transform:translateZ(0)'></div>
                """, RESAMPLED_EDGES,
                new Sample(120, 120, RED), new Sample(30, 30, WHITE), new Sample(120, 210, WHITE));
    }

    /**
     * The group's opacity applies once to the two overlapping children together: where they overlap,
     * half-transparent blue over white, not blue over red.
     */
    @Test
    public void overlappingChildrenOfATranslucentParentDrawLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:0;top:0;width:300px;height:200px;opacity:0.5'>
                <div style='position:absolute;left:0;top:0;width:180px;height:200px;background:#f00;
                    transform:translateZ(0)'></div>
                <div style='position:absolute;left:120px;top:0;width:180px;height:200px;background:#00f;
                    transform:translateZ(0)'></div>
                </div>
                """, Tolerance.FLAT_FILLS,
                new Sample(60, 100, 0xff8080, 3), new Sample(150, 100, 0x8080ff, 3),
                new Sample(240, 100, 0x8080ff, 3), new Sample(150, 250, WHITE));
    }

    @Test
    public void roundedOverflowClipOfACompositedChildDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:20px;top:20px;width:200px;height:200px;overflow:hidden;
                    border-radius:60px'>
                <div style='width:200px;height:200px;background:#f00;transform:translateZ(0)'></div>
                </div>
                """, RESAMPLED_EDGES,
                new Sample(120, 120, RED), new Sample(26, 26, WHITE), new Sample(214, 214, WHITE),
                new Sample(120, 30, RED));
    }

    @Test
    public void reflectionDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:20px;top:20px;width:160px;height:100px;background:#f00;
                    transform:translateZ(0);-webkit-box-reflect:below 0'></div>
                """, Tolerance.FLAT_FILLS,
                new Sample(100, 70, RED), new Sample(100, 170, RED), new Sample(100, 240, WHITE));
    }

    /** A left-to-right mask does not depend on how the reflection flips vertically. */
    @Test
    public void maskedReflectionDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:20px;top:20px;width:160px;height:100px;background:#f00;
                    transform:translateZ(0);
                    -webkit-box-reflect:below 0 linear-gradient(to right,#000 50%,transparent 50%)'></div>
                """, RESAMPLED_EDGES,
                new Sample(100, 70, RED), new Sample(55, 170, RED), new Sample(145, 170, WHITE),
                new Sample(100, 240, WHITE));
    }

    /**
     * The non-composited reference draws no backdrop filter at all, so under the filtered element only
     * the oracle decides; the samples stay outside it.
     */
    @Test
    public void backdropFilterDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:20px;top:20px;width:200px;height:200px;background:#00f'></div>
                <div style='position:absolute;left:120px;top:120px;width:150px;height:100px;
                    backdrop-filter:invert(1);transform:translateZ(0)'></div>
                """, Tolerance.FLAT_FILLS,
                new Sample(70, 70, BLUE), new Sample(250, 250, WHITE));
    }

    /** 290 x 280, so the layer spans two tiles each way; the quadrant edges are away from the tile edges. */
    @Test
    public void layerLargerThanOneTileDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:10px;top:10px;width:290px;height:280px;
                    transform:translateZ(0)'>
                <div style='position:absolute;left:0;top:0;width:145px;height:140px;background:#f00'></div>
                <div style='position:absolute;left:145px;top:0;width:145px;height:140px;background:#0f0'></div>
                <div style='position:absolute;left:0;top:140px;width:145px;height:140px;background:#00f'></div>
                <div style='position:absolute;left:145px;top:140px;width:145px;height:140px;background:#ff0'></div>
                </div>
                """, Tolerance.FLAT_FILLS,
                new Sample(80, 70, RED), new Sample(260, 70, GREEN), new Sample(272, 70, GREEN),
                new Sample(80, 260, BLUE), new Sample(80, 272, BLUE), new Sample(260, 260, YELLOW),
                new Sample(272, 272, YELLOW));
    }

    /**
     * 300 x 280 at (10,10): the quadrants meet at (160,150), and the 256 px tile edges, at x and y 266,
     * fall inside the flat right and bottom quadrants, which are sampled on both sides of them.
     */
    @Test
    public void directlyCompositedImageLargerThanOneTileDrawsLikeTheReference() {
        assertDrawsLikeTheReference("<img style='position:absolute;left:10px;top:10px;transform:translateZ(0)' src='"
                + quadrantsPng(300, 280) + "'>", Tolerance.FLAT_FILLS,
                new Sample(80, 80, RED), new Sample(260, 80, GREEN), new Sample(272, 80, GREEN),
                new Sample(80, 260, BLUE), new Sample(80, 272, BLUE),
                new Sample(260, 210, YELLOW), new Sample(272, 210, YELLOW),
                new Sample(210, 260, YELLOW), new Sample(210, 272, YELLOW));
    }

    /**
     * A striped mask on a red layer at an odd offset, over a composited blue layer: where the mask is
     * transparent the blue must show, not white and not red, on both sides of the masked layer's tile
     * edge at x 256 + 17 = 273.
     */
    @Test
    public void maskOverCompositedContentDrawsLikeTheReference() {
        String stripes = "repeating-linear-gradient(to right,#000 0 40px,transparent 40px 80px)";
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:0;top:20px;width:400px;height:130px;background:#00f;
                    transform:translateZ(0)'></div>
                """ + "<div style='position:absolute;left:17px;top:43px;width:360px;height:90px;background:#f00;"
                + "transform:translateZ(0);-webkit-mask-image:" + stripes + ";mask-image:" + stripes + "'></div>",
                RESAMPLED_EDGES,
                new Sample(37, 88, RED), new Sample(77, 88, BLUE), new Sample(197, 88, RED),
                new Sample(237, 88, BLUE), new Sample(267, 88, RED), new Sample(287, 88, RED),
                new Sample(317, 88, BLUE), new Sample(357, 88, RED), new Sample(200, 30, BLUE),
                new Sample(200, 200, WHITE));
    }

    @Test
    public void rotatedMaskOverCompositedContentDrawsLikeTheReference() {
        String half = "linear-gradient(to right,#000 50%,transparent 50%)";
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:0;top:0;width:300px;height:220px;background:#00f;
                    transform:translateZ(0)'></div>
                """ + "<div style='position:absolute;left:73px;top:37px;width:150px;height:150px;background:#f00;"
                + "transform:rotate(45deg) translateZ(0);-webkit-mask-image:" + half + ";mask-image:" + half
                + "'></div>", SCALED_EDGES);
    }

    /**
     * A composited child larger than its rotated, clipping, composited parent: the parent's clip is a
     * rotated quad, which no rectangle can stand for. The square of 160 px turns 30 degrees about its
     * centre (180,140); a point {@code (u, v)} from the centre lands at
     * {@code (180 + u cos30 - v sin30, 140 + u sin30 + v cos30)}.
     */
    @Test
    public void childOfARotatedClipDrawsLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:100px;top:60px;width:160px;height:160px;overflow:hidden;
                    transform:rotate(30deg) translateZ(0)'>
                <div style='position:absolute;left:-70px;top:-70px;width:300px;height:300px;background:#f00;
                    transform:translateZ(0)'></div>
                </div>
                """, SCALED_EDGES,
                new Sample(180, 140, RED), rotated(65, 0, RED), rotated(0, 65, RED), rotated(95, 0, WHITE),
                rotated(0, -95, WHITE), rotated(-95, 0, WHITE));
    }

    /**
     * Overlapping children of a translucent group, drawn through an intermediate surface, inside a
     * clipping container far from the page origin. Each surface clips to its own bounds; measured
     * against the page's clip instead, the surface's draws would fall outside it and be dropped.
     */
    @Test
    public void overlappingChildrenInAClipFarFromTheOriginDrawLikeTheReference() {
        assertDrawsLikeTheReference("""
                <div style='position:absolute;left:200px;top:100px;width:100px;height:120px;overflow:hidden;
                    transform:translateZ(0)'>
                <div style='position:absolute;left:0;top:0;width:200px;height:120px;opacity:0.5'>
                <div style='position:absolute;left:0;top:0;width:60px;height:120px;background:#f00;
                    transform:translateZ(0)'></div>
                <div style='position:absolute;left:40px;top:0;width:160px;height:120px;background:#00f;
                    transform:translateZ(0)'></div>
                </div></div>
                """, Tolerance.FLAT_FILLS,
                new Sample(215, 160, 0xff8080, 3), new Sample(250, 160, 0x8080ff, 3),
                new Sample(280, 160, 0x8080ff, 3), new Sample(312, 160, WHITE), new Sample(250, 88, WHITE));
    }

    private static Sample rotated(double u, double v, int rgb) {
        double radians = Math.toRadians(30);
        return new Sample((int) Math.round(180 + u * Math.cos(radians) - v * Math.sin(radians)),
                (int) Math.round(140 + u * Math.sin(radians) + v * Math.cos(radians)), rgb);
    }

    private void assertDrawsLikeTheReference(String body, Tolerance tolerance, Sample... samples) {
        String html = pageWithControlPatch(body);

        try (PumpedPage referencePage = PumpedPage.withDefaultSettings(html);
                PumpedPage compositedPage = PumpedPage.withAcceleratedCompositing(html)) {
            referencePage.settle();
            assertFalse(referencePage.isInCompositingMode(), "the reference page is in compositing mode");
            BufferedImage reference = referencePage.pump();
            assertSamples("reference control patch", reference, CONTROL);
            assertSamples("reference", reference, samples);

            compositedPage.settle();
            assertTrue(compositedPage.isInCompositingMode(), "the page is not in compositing mode");
            BufferedImage composited = compositedPage.pump();
            assertSamples("composited control patch", composited, CONTROL);
            assertSamples("composited", composited, samples);
            assertMatchesReference(reference, composited, tolerance);
        }
    }

    /** Red, green, blue and yellow quadrants, so that a flipped or offset draw shows. */
    private static String quadrantsPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean left = x < width / 2;
                image.setRGB(x, y, y < height / 2 ? (left ? RED : GREEN) : (left ? BLUE : YELLOW));
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }
}
