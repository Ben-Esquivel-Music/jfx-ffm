/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.glass.ui.mac;

import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.GlassRobot;
import com.sun.glass.ui.Pixels;

/**
 * MacOSX platform implementation class for Robot.
 */
final class MacRobot extends GlassRobot {

    private MacRobotCalls.RobotState robot;

    @Override
    public void create() {
        Application.checkEventThread();
        robot = MacRobotCalls.createRobot();
    }

    /**
     * Keeps the robot: the JNI released its {@code GlassRobot} object but kept the pointer, so later calls still
     * reached it (latent bug 4).
     */
    @Override
    public void destroy() {
        Application.checkEventThread();
    }

    @Override
    public void keyPress(KeyCode code) {
        Application.checkEventThread();
        MacRobotCalls.keyPress(code.getCode());
    }

    @Override
    public void keyRelease(KeyCode code) {
        Application.checkEventThread();
        MacRobotCalls.keyRelease(code.getCode());
    }

    @Override
    public void mouseMove(double x, double y) {
        Application.checkEventThread();
        if (robot == null) {
            return;
        }
        MacRobotCalls.mouseMove(robot, (float) x, (float) y);
    }

    @Override
    public void mousePress(MouseButton... buttons) {
        Application.checkEventThread();
        if (robot == null) {
            return;
        }
        MacRobotCalls.mousePress(robot, GlassRobot.convertToRobotMouseButton(buttons));
    }

    @Override
    public void mouseRelease(MouseButton... buttons) {
        Application.checkEventThread();
        if (robot == null) {
            return;
        }
        MacRobotCalls.mouseRelease(robot, GlassRobot.convertToRobotMouseButton(buttons));
    }

    @Override
    public void mouseWheel(int wheelAmt) {
        Application.checkEventThread();
        MacRobotCalls.mouseWheel(wheelAmt);
    }

    @Override
    public double getMouseX() {
        Application.checkEventThread();
        if (robot == null) {
            return 0;
        }
        return MacRobotCalls.mouseX();
    }

    @Override
    public double getMouseY() {
        Application.checkEventThread();
        if (robot == null) {
            return 0;
        }
        return MacRobotCalls.mouseY();
    }

    @Override
    public Color getPixelColor(double x, double y) {
        Application.checkEventThread();
        return GlassRobot.convertFromIntArgb(MacRobotCalls.pixelColor(x, y));
    }

    @Override
    public WritableImage getScreenCapture(WritableImage image, double x, double y, double width,
                                          double height, boolean scaleToFit) {
        Application.checkEventThread();

        int iWidth = (int) width;
        int iHeight = (int) height;
        if (iWidth <= 0) {
            throw new IllegalArgumentException("width must be > 0");
        }
        if (iHeight <= 0) {
            throw new IllegalArgumentException("height must be > 0");
        }
        if (iWidth >= (Integer.MAX_VALUE / iHeight)) {
            throw new IllegalArgumentException("invalid capture size");
        }

        Pixels pixels;
        pixels = MacRobotCalls.screenCapture((int) x, (int) y, iWidth, iHeight, scaleToFit);
        return convertFromPixels(image, pixels);
    }
}
