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

import java.awt.image.BufferedImage;

final class PixelAssertions {

    /** Opaque colours, 0xRRGGBB. */
    static final int RED = 0xff0000;
    static final int GREEN = 0x00ff00;
    static final int BLUE = 0x0000ff;
    static final int YELLOW = 0xffff00;
    static final int CYAN = 0x00ffff;
    static final int MAGENTA = 0xff00ff;
    static final int WHITE = 0xffffff;

    /** A flat fill drawn twice by the same software rasterizer differs by rounding only. */
    static final int FLAT_FILL = 2;

    private static final int ARGB_CHANNELS = 4;

    /**
     * How far the composited rendering may differ from the reference: {@code flat} on pixels whose
     * reference neighbourhood of {@code edgeRadius} is one colour, {@code edge} on the others.
     */
    record Tolerance(int flat, int edge, int edgeRadius) {
        static final Tolerance FLAT_FILLS = new Tolerance(FLAT_FILL, FLAT_FILL, 0);

        static Tolerance flatWithEdges(int edge, int edgeRadius) {
            return new Tolerance(FLAT_FILL, edge, edgeRadius);
        }
    }

    record Sample(int x, int y, int rgb, int tolerance) {
        Sample(int x, int y, int rgb) {
            this(x, y, rgb, FLAT_FILL);
        }
    }

    /** A flat patch that no case composites or covers, see {@link #pageWithControlPatch}. */
    static final String CONTROL_PATCH =
            "<div style='position:absolute;left:310px;top:230px;width:80px;height:60px;background:#00f'></div>";
    static final Sample CONTROL = new Sample(350, 260, BLUE);

    private PixelAssertions() {
    }

    /** A white page without margins, holding the body and the control patch sampled at {@link #CONTROL}. */
    static String pageWithControlPatch(String body) {
        return "<html><head><style>body{margin:0;background:#fff}</style></head><body>"
                + body + CONTROL_PATCH + "</body></html>";
    }

    static void assertSamples(String rendering, BufferedImage image, Sample... samples) {
        for (Sample sample : samples) {
            assertPixel(rendering, image, sample.x(), sample.y(), sample.rgb(), sample.tolerance());
        }
    }

    static void assertPixel(String rendering, BufferedImage image, int x, int y, int expectedRgb) {
        assertPixel(rendering, image, x, y, expectedRgb, FLAT_FILL);
    }

    static void assertPixel(String rendering, BufferedImage image, int x, int y, int expectedRgb, int tolerance) {
        int actual = image.getRGB(x, y);
        int expected = 0xff000000 | expectedRgb;
        if (channelDifference(expected, actual) > tolerance) {
            fail(String.format("%s pixel (%d,%d): expected %08x within %d per channel, was %08x",
                    rendering, x, y, expected, tolerance, actual));
        }
    }

    static void assertMatchesReference(BufferedImage reference, BufferedImage composited, Tolerance tolerance) {
        assertEquals(reference.getWidth(), composited.getWidth(), "image width");
        assertEquals(reference.getHeight(), composited.getHeight(), "image height");
        int offending = 0;
        int worst = 0;
        String first = null;
        for (int y = 0; y < reference.getHeight(); y++) {
            for (int x = 0; x < reference.getWidth(); x++) {
                int expected = reference.getRGB(x, y);
                int actual = composited.getRGB(x, y);
                int difference = channelDifference(expected, actual);
                int allowed = isFlat(reference, x, y, tolerance.edgeRadius()) ? tolerance.flat() : tolerance.edge();
                if (difference > allowed) {
                    offending++;
                    worst = Math.max(worst, difference);
                    if (first == null) {
                        first = String.format("(%d,%d) reference %08x composited %08x allowed %d",
                                x, y, expected, actual, allowed);
                    }
                }
            }
        }
        if (offending > 0) {
            fail(String.format("composited rendering differs from the reference at %d pixels, worst channel"
                    + " difference %d, first at %s", offending, worst, first));
        }
    }

    private static boolean isFlat(BufferedImage image, int x, int y, int radius) {
        int colour = image.getRGB(x, y);
        for (int ny = Math.max(0, y - radius); ny <= Math.min(image.getHeight() - 1, y + radius); ny++) {
            for (int nx = Math.max(0, x - radius); nx <= Math.min(image.getWidth() - 1, x + radius); nx++) {
                if (image.getRGB(nx, ny) != colour) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int channelDifference(int argb1, int argb2) {
        int worst = 0;
        for (int channel = 0; channel < ARGB_CHANNELS; channel++) {
            int shift = channel * 8;
            worst = Math.max(worst, Math.abs(((argb1 >>> shift) & 0xff) - ((argb2 >>> shift) & 0xff)));
        }
        return worst;
    }
}
