/*
 * Copyright (c) 2025, 2026, Oracle and/or its affiliates. All rights reserved.
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

package test.robot.javafx.web;

import java.util.concurrent.CountDownLatch;

import javafx.concurrent.Worker;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import test.robot.testharness.RobotTestBase;
import test.util.Util;

public class TextSelectionTest extends RobotTestBase {

    private static final String html = """
        <html>
        <body><font size="2">&nbsp&nbsp&nbsp&nbsp some text</font></body>
        </html>
        """;

    private static CountDownLatch webviewLoadLatch = new CountDownLatch(1);
    private WebView webview;
    private volatile Color colorBefore;
    private volatile Color colorAfter;

    @BeforeEach
    public void beforeEach() {
        Util.runAndWait(() -> {
            webview = new WebView();
            webview.getEngine().getLoadWorker().stateProperty().addListener((ov, o, n) -> {
                if (n == Worker.State.SUCCEEDED) {
                    webviewLoadLatch.countDown();
                }
            });
            webview.getEngine().loadContent(html);
            contentPane.setCenter(webview);
        });
        Util.waitForLatch(webviewLoadLatch, 10, "Timeout waiting for web content to load");
    }

    // ========================== TEST CASE ==========================
    @Test
    @Timeout(value=20)
    public void testTextSelection() {

        Util.sleep(200);
        int localX = 22;
        int localY = 15;
        int x = (int)(scene.getWindow().getX() + scene.getX() + localX);
        int y = (int)(scene.getWindow().getY() + scene.getY() + localY);

        // Some window managers consume the first click while activating a window.
        CountDownLatch stageFocusLatch = new CountDownLatch(1);
        Util.runAndWait(() -> {
            if (stage.isFocused()) {
                stageFocusLatch.countDown();
            } else {
                stage.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
                    if (isFocused) {
                        stageFocusLatch.countDown();
                    }
                });
            }
            robot.mouseMove(scene.getWindow().getX() + scene.getX() + 5,
                    scene.getWindow().getY() + scene.getY() + 5);
            robot.mouseClick(MouseButton.PRIMARY);
        });
        Util.waitForLatch(stageFocusLatch, 5, "Timeout waiting for stage focus");
        waitForIdle();

        Util.parkCursor(robot);
        // Capture the WebView surface directly; desktop capture is not available
        // with every compositor, but the test must still verify the rendered color.
        Util.runAndWait(() -> colorBefore = webview.snapshot(null, null)
                .getPixelReader().getColor(localX, localY));

        Util.runAndWait(() -> robot.mouseMove(x, y));
        Util.doubleClick(robot);
        Util.sleep(500); // Wait for the selection highlight to be drawn

        Util.parkCursor(robot);
        Util.runAndWait(() -> colorAfter = webview.snapshot(null, null)
                .getPixelReader().getColor(localX, localY));

        Assertions.assertNotEquals(colorBefore, colorAfter,
            "Selection color did not change after double click");
    }
}
