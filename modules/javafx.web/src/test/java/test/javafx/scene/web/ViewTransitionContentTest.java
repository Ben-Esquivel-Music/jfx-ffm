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
import static test.javafx.scene.web.PixelAssertions.CONTROL;
import static test.javafx.scene.web.PixelAssertions.CONTROL_PATCH;
import static test.javafx.scene.web.PixelAssertions.GREEN;
import static test.javafx.scene.web.PixelAssertions.RED;
import static test.javafx.scene.web.PixelAssertions.assertMatchesReference;
import static test.javafx.scene.web.PixelAssertions.assertSamples;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;
import test.javafx.scene.web.PixelAssertions.Tolerance;

/**
 * A view transition whose update callback turns a flat red page green. Starting a transition puts the
 * page in compositing mode even with accelerated compositing off (the default settings).
 */
@ExtendWith(CaseAnnouncer.class)
public class ViewTransitionContentTest extends TestBase {

    /*
     * The group's own 1000 s animation keeps the transition running until the test skips it, and the
     * static pseudo-element styles show the new state opaque from the first frame on.
     */
    static final String HELD_OPEN = """
            ::view-transition-group(root){animation:hold 1000s linear}
            ::view-transition-old(root){animation:none;opacity:0}
            ::view-transition-new(root){animation:none;opacity:1}
            @keyframes hold{from{opacity:1}to{opacity:1}}
            """;

    private static final String STATE = "'ready=' + state.ready + ' updated=' + state.updated"
            + " + ' finished=' + state.finished + ' errors=' + state.errors";
    private static final String STARTED = "ready=false updated=false finished=false errors=";
    private static final String RUNNING = "ready=true updated=true finished=false errors=";
    private static final String FINISHED = "ready=true updated=true finished=true errors=";

    private static final int TRANSITIONS_IN_A_ROW = 3;

    @Test
    public void transitionShowsTheNewContentBeforeItFinishes() {
        try (PumpedPage page = PumpedPage.withDefaultSettings(transitionPage(HELD_OPEN))) {
            assertShowsTheNewContentBeforeItFinishes(page, false);
        }
    }

    @Test
    public void compositedTransitionShowsTheNewContentBeforeItFinishes() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(transitionPage(HELD_OPEN))) {
            assertShowsTheNewContentBeforeItFinishes(page, true);
        }
    }

    @Test
    public void threeTransitionsInARowEndClean() {
        try (PumpedPage page = PumpedPage.withDefaultSettings(transitionPage(""))) {
            assertTransitionsInARowEndClean(page);
        }
    }

    @Test
    public void threeCompositedTransitionsInARowEndClean() {
        try (PumpedPage page = PumpedPage.withAcceleratedCompositing(transitionPage(""))) {
            assertTransitionsInARowEndClean(page);
        }
    }

    /**
     * With accelerated compositing on, the frame drawn while the transition runs must also be drawn in
     * compositing mode; with the default settings the compositor may leave it once it finds no layer.
     */
    private void assertShowsTheNewContentBeforeItFinishes(PumpedPage page, boolean drawnInCompositingMode) {
        BufferedImage newContent;
        try (PumpedPage newContentPage = PumpedPage.withDefaultSettings(flatPage("#0f0"))) {
            newContent = newContentPage.settle();
        }

        BufferedImage before = page.settle();
        assertFalse(page.isInCompositingMode(), "compositing mode before the transition");
        assertSamples("before the transition", before, CONTROL, pageColour(RED));

        page.script("startTransition()");
        assertEquals(STARTED, page.script(STATE), "transition state when started");
        assertTrue(page.isInCompositingMode(), "starting the transition did not enter compositing mode");

        BufferedImage during = page.pumpUntil("state.ready && state.updated");
        assertEquals(RUNNING, page.script(STATE), "transition state after the frame drawn once ready resolved");
        if (drawnInCompositingMode) {
            assertTrue(page.isInCompositingMode(), "the frame of the running transition was not composited");
        }
        assertSamples("during the transition", during, CONTROL, pageColour(GREEN));
        assertMatchesReference(newContent, during, Tolerance.FLAT_FILLS);

        page.script("transition.skipTransition()");
        page.pumpUntil("state.finished");
        BufferedImage after = page.settle();
        assertEquals(FINISHED, page.script(STATE), "transition state when finished");
        assertFalse(page.isInCompositingMode(), "compositing mode after the transition");
        assertSamples("after the transition", after, CONTROL, pageColour(GREEN));
        assertMatchesReference(newContent, after, Tolerance.FLAT_FILLS);
    }

    private void assertTransitionsInARowEndClean(PumpedPage page) {
        page.settle();
        for (int transition = 1; transition <= TRANSITIONS_IN_A_ROW; transition++) {
            page.script("startTransition()");
            page.pumpUntil("state.finished");
            BufferedImage after = page.settle();
            String which = "after transition " + transition;
            assertEquals(FINISHED, page.script(STATE), which);
            assertFalse(page.isInCompositingMode(), "compositing mode " + which);
            assertSamples(which, after, CONTROL, pageColour(transition % 2 == 1 ? GREEN : RED));
        }
    }

    private static Sample pageColour(int rgb) {
        return new Sample(150, 150, rgb);
    }

    private static String transitionPage(String transitionStyle) {
        return "<html><head><style>html{background:#f00} html.after{background:#0f0} body{margin:0}"
                + transitionStyle + "</style><script>"
                + """
                function startTransition() {
                    window.state = {ready: false, updated: false, finished: false, errors: ''};
                    window.transition = document.startViewTransition(function() {
                        document.documentElement.classList.toggle('after');
                        state.updated = true;
                    });
                    transition.ready.then(function() { state.ready = true; },
                        function(e) { state.errors += ' ready: ' + e; });
                    transition.finished.then(function() { state.finished = true; },
                        function(e) { state.errors += ' finished: ' + e; });
                }
                """
                + "</script></head><body>" + CONTROL_PATCH + "</body></html>";
    }

    private static String flatPage(String colour) {
        return "<html><head><style>html{background:" + colour + "} body{margin:0}</style></head><body>"
                + CONTROL_PATCH + "</body></html>";
    }
}
