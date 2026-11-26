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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Overlapping children of a translucent composited parent are drawn through an intermediate surface
 * the size of their overlap, which the texture mapper takes from a process-wide pool. Each surface
 * keeps an image and its render queue alive, two registry ids, for as long as its pool entry lives.
 */
@ExtendWith(CaseAnnouncer.class)
public class SurfacePoolTest extends TestBase {

    private static final int SIZES = 71;

    /* One idle pool entry holds two ids, so a tolerance of one still fails on a single entry. */
    private static final int REGISTRY_TOLERANCE = 1;

    private static final String PAGE = PixelAssertions.pageWithControlPatch("""
            <div style='position:absolute;left:0;top:0;width:360px;height:200px;opacity:0.5'>
            <div id=red style='position:absolute;left:0;top:0;width:180px;height:200px;background:#f00;
                transform:translateZ(0)'></div>
            <div id=blue style='position:absolute;left:100px;top:0;width:180px;height:200px;background:#00f;
                transform:translateZ(0)'></div>
            </div>
            """);

    /**
     * The blue child moves 1 px to the right per frame, from left 100 to 170, so each frame's overlap,
     * 80 px wide down to 10, needs a surface of a size the pool has not seen; the overlap stays smaller
     * than the rest, so WebKit keeps it a region of its own.
     */
    @Test
    public void surfacesOfAClosedPageLeaveNothingInTheRegistry() throws Exception {
        int start = SettledRegistry.size();
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(PAGE)) {
            page.settle();
            assertTrue(page.isInCompositingMode(), "the page is not in compositing mode");
            int loaded = SettledRegistry.size();
            for (int step = 0; step < SIZES; step++) {
                page.script("blue.style.left = '" + (100 + step) + "px'");
                page.pump();
            }
            int drawn = SettledRegistry.size();
            page.close();
            int end = SettledRegistry.size();
            String series = start + " -> loaded " + loaded + " -> after " + SIZES + " surface sizes " + drawn
                    + " -> after the page " + end;
            assertTrue(drawn - loaded <= REGISTRY_TOLERANCE,
                    "the registry grew with the surface sizes while the page lived: " + series);
            assertTrue(Math.abs(end - start) <= REGISTRY_TOLERANCE,
                    "the registry did not return to its baseline after the page was destroyed: " + series);
        }
    }
}
