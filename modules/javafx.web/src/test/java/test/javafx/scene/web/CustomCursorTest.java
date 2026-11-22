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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.web.WebEngineShim;
import javafx.scene.web.WebView;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

public class CustomCursorTest extends TestBase {
    private static final int VIEW_SIZE = 400;
    private static final int CURSOR_SIZE = 16;
    private static final int INSIDE_HOTSPOT_X = 3;
    private static final int INSIDE_HOTSPOT_Y = 5;
    private static final int OUTSIDE_HOTSPOT = 20;
    private static final int LAST_CURSOR_PIXEL = CURSOR_SIZE - 1;
    private static final Duration CURSOR_WAIT = Duration.ofSeconds(5);
    private static final Duration MOVE_RETRY_PAUSE = Duration.ofMillis(20);

    private static final Point INSIDE_HOTSPOT_AREA = new Point(150, 150);
    private static final Point OUTSIDE_HOTSPOT_AREA = new Point(300, 150);
    private static final Point POINTER_AREA = new Point(150, 300);
    private static final Point PLAIN_AREA = new Point(300, 300);

    private static final String PAGE = """
            <!DOCTYPE html>
            <html>
            <head>
            <style>
            body { margin: 0; }
            div { position: absolute; width: 100px; height: 100px; }
            #inside { left: 100px; top: 100px; cursor: url("%1$s") %2$d %3$d, auto; }
            #outside { left: 250px; top: 100px; cursor: url("%1$s") %4$d %4$d, auto; }
            #pointer { left: 100px; top: 250px; cursor: pointer; }
            #plain { left: 250px; top: 250px; }
            #probe { position: absolute; left: 20px; top: 20px; }
            </style>
            </head>
            <body>
            <img id="probe" src="%1$s">
            <div id="inside"></div>
            <div id="outside"></div>
            <div id="pointer"></div>
            <div id="plain"></div>
            </body>
            </html>
            """;

    private record Point(int x, int y) {}

    @Test
    public void aCustomCursorWithItsHotspotInsideTheImageReachesTheWebView() throws Exception {
        loadPage();

        ImageCursor cursor = moveMouseUntilImageCursor(INSIDE_HOTSPOT_AREA);

        Image image = cursor.getImage();
        assertNotNull(image, "image of the WebView's ImageCursor");
        assertEquals(CURSOR_SIZE, image.getWidth(), "width of the cursor image");
        assertEquals(CURSOR_SIZE, image.getHeight(), "height of the cursor image");
        assertEquals(INSIDE_HOTSPOT_X, cursor.getHotspotX(), "hotspot x of the ImageCursor");
        assertEquals(INSIDE_HOTSPOT_Y, cursor.getHotspotY(), "hotspot y of the ImageCursor");
    }

    @Test
    public void aCustomCursorHotspotOutsideTheImageReachesTheWebViewClampedToTheImagesLastPixel() throws Exception {
        loadPage();

        ImageCursor cursor = moveMouseUntilImageCursor(OUTSIDE_HOTSPOT_AREA);

        // ImageCursor clamps the page's hotspot to the image; WebCore's own correction of it would arrive as 0,0.
        assertAll(
                () -> assertEquals(LAST_CURSOR_PIXEL, cursor.getHotspotX(), "hotspot x of the ImageCursor"),
                () -> assertEquals(LAST_CURSOR_PIXEL, cursor.getHotspotY(), "hotspot y of the ImageCursor"));
    }

    @Test
    public void aPredefinedCursorAndThenTheDefaultCursorReachTheWebView() throws Exception {
        loadPage();

        assertSame(Cursor.HAND, moveMouseTo(POINTER_AREA), "cursor over the element with cursor: pointer");
        assertSame(Cursor.DEFAULT, moveMouseTo(PLAIN_AREA), "cursor over the element without a cursor rule");
    }

    private void loadPage() throws IOException {
        String cursorUrl = cursorImageDataUrl();
        submit(() -> {
            getView().resize(VIEW_SIZE, VIEW_SIZE);
            // The view is in no showing scene, so no pulse hands its size to the page, and WebCore skips a custom
            // cursor that does not fit inside the visible content.
            WebEngineShim.getPage(getEngine()).setBounds(0, 0, VIEW_SIZE, VIEW_SIZE);
        });
        loadContent(PAGE.formatted(cursorUrl, INSIDE_HOTSPOT_X, INSIDE_HOTSPOT_Y, OUTSIDE_HOTSPOT));
        String probeSize = "(img => img.naturalWidth + 'x' + img.naturalHeight)(document.getElementById('probe'))";
        assertEquals(CURSOR_SIZE + "x" + CURSOR_SIZE, executeScript(probeSize),
                "natural size of an <img> with the cursor image after the page has loaded");
    }

    private static String cursorImageDataUrl() throws IOException {
        int[] pixels = new int[CURSOR_SIZE * CURSOR_SIZE];
        Arrays.fill(pixels, 0xFF0000FF);
        BufferedImage image = new BufferedImage(CURSOR_SIZE, CURSOR_SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, CURSOR_SIZE, CURSOR_SIZE, pixels, 0, CURSOR_SIZE);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(png)) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("no PNG writer");
            }
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }

    /*
     * Until WebCore's own request for the cursor image has finished, it builds the custom cursor from a null image,
     * which reaches the view as the default cursor. That request can still be running when the page load has ended,
     * so the move is repeated for a bounded time.
     */
    private ImageCursor moveMouseUntilImageCursor(Point point) throws InterruptedException {
        long deadline = System.nanoTime() + CURSOR_WAIT.toNanos();
        Cursor cursor = moveMouseTo(point);
        while (!(cursor instanceof ImageCursor) && System.nanoTime() < deadline) {
            Thread.sleep(MOVE_RETRY_PAUSE);
            cursor = moveMouseTo(point);
        }
        Cursor last = cursor;
        return assertInstanceOf(ImageCursor.class, last,
                () -> "WebView cursor " + last + " after moving the mouse to " + point + " for " + CURSOR_WAIT);
    }

    private Cursor moveMouseTo(Point point) {
        return submit(() -> {
            WebView view = getView();
            view.fireEvent(new MouseEvent(MouseEvent.MOUSE_MOVED, point.x(), point.y(), point.x(), point.y(),
                    MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null));
            return view.getCursor();
        });
    }
}
