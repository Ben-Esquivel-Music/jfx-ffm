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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static test.javafx.scene.web.PixelAssertions.CONTROL;
import static test.javafx.scene.web.PixelAssertions.WHITE;
import static test.javafx.scene.web.PixelAssertions.assertMatchesReference;
import static test.javafx.scene.web.PixelAssertions.assertSamples;
import static test.javafx.scene.web.PixelAssertions.pageWithControlPatch;

import com.sun.javafx.webkit.prism.RTImageShim;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;
import test.javafx.scene.web.PixelAssertions.Tolerance;

/**
 * WebKit makes a new image for the intermediate surface of every frame, and an image is disposed once
 * no command buffer names it; a new image of the same physical size then takes the render texture of a
 * disposed one, cleared, instead of a new render texture. Every pump also paints into a new image of
 * its own, which it never disposes, so each pump creates one texture besides the page's.
 */
@ExtendWith(CaseAnnouncer.class)
public class CompositedTextureReuseTest extends TestBase {

    private static final int MOVES = 30;
    private static final int SURFACES_PER_FRAME = 1;

    /*
     * Each frame's surface takes the texture of the frame before, whose image is disposed once that frame
     * has been decoded, and the first takes one the settling frames left. Measured on software Prism: no
     * texture created and 30 reused. The bounds allow a texture for each surface of the two frames that
     * can be in flight, two more, and the three reuses those may cost.
     */
    private static final int MAX_CREATED = 2 * SURFACES_PER_FRAME + 2;
    private static final int MIN_REUSED = MOVES - 3;

    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long PAUSE_MILLIS = 5;

    /*
     * Overlapping children of a translucent composited parent are drawn through an intermediate surface
     * the size of their overlap; moving the parent keeps that size.
     */
    private static final String OVERLAP_PAGE = pageWithControlPatch("""
            <div id=group style='position:absolute;left:0;top:0;width:360px;height:200px;opacity:0.5'>
            <div style='position:absolute;left:0;top:0;width:180px;height:200px;background:#f00;
                transform:translateZ(0)'></div>
            <div style='position:absolute;left:100px;top:0;width:180px;height:200px;background:#00f;
                transform:translateZ(0)'></div>
            </div>
            """);

    /*
     * Both children turn transparent with a thin black border, so the next frame's surface is drawn only
     * at the borders: a texture taken from the frame before, all red, shows wherever it was not cleared.
     */
    private static final String BORDERED_PAGE = pageWithControlPatch("""
            <div style='position:absolute;left:0;top:0;width:360px;height:200px;opacity:0.5'>
            <div id=a style='position:absolute;left:0;top:0;width:180px;height:200px;box-sizing:border-box;
                border:4px solid #f00;background:#f00;transform:translateZ(0)'></div>
            <div id=b style='position:absolute;left:100px;top:0;width:180px;height:200px;box-sizing:border-box;
                border:4px solid #f00;background:#f00;transform:translateZ(0)'></div>
            </div>
            """);

    private static final String TO_TRANSPARENT = "for (const child of [a, b]) {"
            + " child.style.background = 'transparent'; child.style.borderColor = '#000'; }";

    private record Pumped(BufferedImage frame, int pumps) {
    }

    @Test
    public void theSurfaceOfEveryFrameTakesTheTextureOfTheFrameBefore() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(OVERLAP_PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            int created = RTImageShim.texturesCreated();
            int reused = RTImageShim.texturesReused();
            int pumps = 0;
            for (int step = 1; step <= MOVES; step++) {
                int left = step;
                page.script("group.style.left = '" + left + "px'");
                pumps += pumpUntil(page, frame -> isWhite(frame, left - 1) && !isWhite(frame, left)).pumps();
            }
            int createdForThePage = RTImageShim.texturesCreated() - created - pumps;
            int reusedForThePage = RTImageShim.texturesReused() - reused;
            String figures = MOVES + " frames in " + pumps + " pumps: " + createdForThePage + " textures created, "
                    + reusedForThePage + " reused, " + RTImageShim.cachedTextures() + " cached";
            assertTrue(createdForThePage <= MAX_CREATED, "too many textures created: " + figures);
            assertTrue(reusedForThePage >= MIN_REUSED, "too few textures reused: " + figures);
        }
    }

    @Test
    public void aReusedTextureStartsTransparent() {
        try (PumpedPage referencePage = PumpedPage.withDefaultSettings(BORDERED_PAGE);
                PumpedPage compositedPage = PumpedPage.withAcceleratedCompositing(BORDERED_PAGE)) {
            referencePage.settle();
            referencePage.script(TO_TRANSPARENT);
            BufferedImage reference = pumpUntil(referencePage, CompositedTextureReuseTest::showsTransparentChildren)
                    .frame();
            assertFalse(referencePage.isInCompositingMode(), "the reference page is in compositing mode");

            compositedPage.settle();
            assertTrue(compositedPage.isInCompositingMode(), "the page is not in compositing mode");
            int reused = RTImageShim.texturesReused();
            compositedPage.script(TO_TRANSPARENT);
            BufferedImage composited = pumpUntil(compositedPage, CompositedTextureReuseTest::showsTransparentChildren)
                    .frame();
            assertTrue(RTImageShim.texturesReused() > reused, "the new frame's surface took no texture");

            Sample[] samples = {
                CONTROL, new Sample(50, 100, WHITE), new Sample(140, 100, WHITE), new Sample(230, 100, WHITE)
            };
            assertSamples("reference", reference, samples);
            assertSamples("composited", composited, samples);
            assertMatchesReference(reference, composited, Tolerance.FLAT_FILLS);
        }
    }

    /** The interiors of the children outside their overlap. */
    private static boolean showsTransparentChildren(BufferedImage frame) {
        return isWhite(frame, 50) && isWhite(frame, 230);
    }

    private static boolean isWhite(BufferedImage frame, int x) {
        return (frame.getRGB(x, 100) & 0xffffff) == WHITE;
    }

    /** WebKit draws a frame on a timer of its own, so a change shows only in a later pump. */
    private static Pumped pumpUntil(PumpedPage page, Predicate<BufferedImage> drawn) {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        BufferedImage frame = page.pump();
        int pumps = 1;
        while (!drawn.test(frame)) {
            if (System.nanoTime() > deadline) {
                fail("the change was not drawn within " + pumps + " pumps");
            }
            try {
                Thread.sleep(PAUSE_MILLIS);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            frame = page.pump();
            pumps++;
        }
        return new Pumped(frame, pumps);
    }
}
