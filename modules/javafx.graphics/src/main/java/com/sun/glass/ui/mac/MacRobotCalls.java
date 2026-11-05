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

package com.sun.glass.ui.mac;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.mac.MacGlassNative.SystemLibrary;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_KEY_FIND_MAC;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_MOUSE_LOCATION_FLIPPED;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_POOL_POP;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_POOL_PUSH;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The natives of {@code MacRobot}. {@code GlassRobot.m} made CoreGraphics, HIServices and CoreFoundation calls,
 * which this class makes itself, with the same arguments in the same order; it calls {@code libglass} only for the
 * key table, the mouse location and the autorelease pool each native ran its body in. {@code GR:N} is line N of
 * {@code GlassRobot.m} at {@code 1a6788cc4d}.
 * <p>
 * {@code MacRobot} runs on the FX thread only. Each call but the robot's creation evaluates
 * {@code gmac_main_thread_detached} where its JNI body ran {@code GLASS_ASSERT_MAIN_JAVA_THREAD} and runs its body all
 * the same. Those bodies made no JNI call, so each native returned with the exception the assertion left pending, and
 * these calls throw it after their body. Only the body of {@link #screenCapture} made JNI calls, which decided where
 * it reported the exception and where it threw it.
 * <p>
 * None of these downcalls is critical: the pool exports allocate and drain, and the others post events, read AppKit
 * or capture the screen.
 */
final class MacRobotCalls {

    /* The values of the SDK the JNI passed. */
    static final int kCGEventLeftMouseDown = 1;
    static final int kCGEventLeftMouseUp = 2;
    static final int kCGEventRightMouseDown = 3;
    static final int kCGEventRightMouseUp = 4;
    static final int kCGEventMouseMoved = 5;
    static final int kCGEventLeftMouseDragged = 6;
    static final int kCGEventRightMouseDragged = 7;
    static final int kCGEventOtherMouseDown = 25;
    static final int kCGEventOtherMouseUp = 26;
    static final int kCGEventOtherMouseDragged = 27;
    static final int kCGHIDEventTap = 0;
    static final int kCGScrollEventUnitLine = 1;
    static final int kCGWindowListOptionOnScreenOnly = 1;
    static final int kCGNullWindowID = 0;
    static final int kCGWindowImageDefault = 0;
    static final int kCGRenderingIntentAbsoluteColorimetric = 1;
    /** {@code kCGBitmapByteOrder32Host | kCGImageAlphaPremultipliedFirst}, the host being little-endian. */
    static final int CAPTURE_BITMAP_INFO = 0x2002;

    /* The C types the JNI passed by value or read back. */
    static final ValueLayout.OfDouble CG_FLOAT = JAVA_DOUBLE;
    static final ValueLayout.OfInt CG_EVENT_TYPE = JAVA_INT;
    static final ValueLayout.OfInt CG_MOUSE_BUTTON = JAVA_INT;
    static final ValueLayout.OfInt CG_EVENT_TAP_LOCATION = JAVA_INT;
    static final ValueLayout.OfInt CG_SCROLL_EVENT_UNIT = JAVA_INT;
    /* uint16_t: Apple's arm64 ABI has the caller extend it, and JAVA_CHAR extends with zeros. */
    static final ValueLayout.OfChar CG_CHAR_CODE = JAVA_CHAR;
    static final ValueLayout.OfChar CG_KEY_CODE = JAVA_CHAR;
    /* An unsigned char that must be exactly 0 or 1. */
    static final ValueLayout.OfBoolean BOOLEAN = JAVA_BOOLEAN;
    static final ValueLayout.OfInt AX_ERROR = JAVA_INT;
    static final ValueLayout.OfInt CG_ERROR = JAVA_INT;
    static final ValueLayout.OfInt CG_WINDOW_LIST_OPTION = JAVA_INT;
    static final ValueLayout.OfInt CG_WINDOW_ID = JAVA_INT;
    static final ValueLayout.OfInt CG_WINDOW_IMAGE_OPTION = JAVA_INT;
    static final ValueLayout.OfInt CG_COLOR_RENDERING_INTENT = JAVA_INT;
    static final ValueLayout.OfInt CG_BITMAP_INFO = JAVA_INT;
    static final ValueLayout.OfLong SIZE_T = JAVA_LONG;
    static final StructLayout CG_POINT = MemoryLayout.structLayout(CG_FLOAT.withName("x"), CG_FLOAT.withName("y"));
    static final StructLayout CG_SIZE = MemoryLayout.structLayout(CG_FLOAT.withName("width"),
            CG_FLOAT.withName("height"));
    static final StructLayout CG_RECT = MemoryLayout.structLayout(CG_POINT.withName("origin"),
            CG_SIZE.withName("size"));

    private static final long X = CG_POINT.byteOffset(PathElement.groupElement("x"));
    private static final long Y = CG_POINT.byteOffset(PathElement.groupElement("y"));
    private static final long RECT_X = rectOffset("origin", "x");
    private static final long RECT_Y = rectOffset("origin", "y");
    private static final long RECT_WIDTH = rectOffset("size", "width");
    private static final long RECT_HEIGHT = rectOffset("size", "height");

    private static final int COLOR_COMPONENTS = 4;

    /* The byte alignment of malloc, which allocated what GetIntArrayElements gave the JNI. */
    private static final long MALLOC_ALIGNMENT = 16;

    private static final SystemLibrary CORE_GRAPHICS = SystemLibrary.framework("CoreGraphics");
    /* libglass imported the AXUIElement functions from ApplicationServices, which re-exports HIServices. */
    private static final SystemLibrary APPLICATION_SERVICES = SystemLibrary.framework("ApplicationServices");
    private static final SystemLibrary CORE_FOUNDATION = SystemLibrary.framework("CoreFoundation");
    private static final SystemLibrary LIB_SYSTEM = SystemLibrary.libSystem();

    private static final MethodHandle CG_EVENT_CREATE_MOUSE_EVENT = CORE_GRAPHICS.bind("CGEventCreateMouseEvent",
            FunctionDescriptor.of(ADDRESS, ADDRESS, CG_EVENT_TYPE, CG_POINT, CG_MOUSE_BUTTON));
    // Variadic after the first wheel, and the robot passed no other wheel.
    private static final MethodHandle CG_EVENT_CREATE_SCROLL_WHEEL_EVENT = CORE_GRAPHICS.bind(
            "CGEventCreateScrollWheelEvent",
            FunctionDescriptor.of(ADDRESS, ADDRESS, CG_SCROLL_EVENT_UNIT, JAVA_INT, JAVA_INT),
            Linker.Option.firstVariadicArg(4));
    private static final MethodHandle CG_EVENT_POST = CORE_GRAPHICS.bind("CGEventPost",
            FunctionDescriptor.ofVoid(CG_EVENT_TAP_LOCATION, ADDRESS));
    private static final MethodHandle CG_WARP_MOUSE_CURSOR_POSITION = CORE_GRAPHICS.bind(
            "CGWarpMouseCursorPosition", FunctionDescriptor.of(CG_ERROR, CG_POINT));
    private static final MethodHandle CG_IMAGE_GET_DATA_PROVIDER = CORE_GRAPHICS.bind("CGImageGetDataProvider",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CG_DATA_PROVIDER_COPY_DATA = CORE_GRAPHICS.bind("CGDataProviderCopyData",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CG_IMAGE_GET_COLOR_SPACE = CORE_GRAPHICS.bind("CGImageGetColorSpace",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CG_IMAGE_GET_WIDTH = CORE_GRAPHICS.bind("CGImageGetWidth",
            FunctionDescriptor.of(SIZE_T, ADDRESS));
    private static final MethodHandle CG_IMAGE_GET_HEIGHT = CORE_GRAPHICS.bind("CGImageGetHeight",
            FunctionDescriptor.of(SIZE_T, ADDRESS));
    private static final MethodHandle CG_IMAGE_RELEASE = CORE_GRAPHICS.bind("CGImageRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CG_COLOR_CREATE = CORE_GRAPHICS.bind("CGColorCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CG_COLOR_CREATE_COPY_BY_MATCHING_TO_COLOR_SPACE = CORE_GRAPHICS.bind(
            "CGColorCreateCopyByMatchingToColorSpace",
            FunctionDescriptor.of(ADDRESS, ADDRESS, CG_COLOR_RENDERING_INTENT, ADDRESS, ADDRESS));
    private static final MethodHandle CG_COLOR_GET_COMPONENTS = CORE_GRAPHICS.bind("CGColorGetComponents",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CG_COLOR_RELEASE = CORE_GRAPHICS.bind("CGColorRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CG_COLOR_SPACE_CREATE_WITH_NAME = CORE_GRAPHICS.bind(
            "CGColorSpaceCreateWithName", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CG_COLOR_SPACE_RELEASE = CORE_GRAPHICS.bind("CGColorSpaceRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CG_BITMAP_CONTEXT_CREATE = CORE_GRAPHICS.bind("CGBitmapContextCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, SIZE_T, SIZE_T, SIZE_T, SIZE_T, ADDRESS, CG_BITMAP_INFO));
    private static final MethodHandle CG_CONTEXT_DRAW_IMAGE = CORE_GRAPHICS.bind("CGContextDrawImage",
            FunctionDescriptor.ofVoid(ADDRESS, CG_RECT, ADDRESS));
    private static final MethodHandle CG_CONTEXT_FLUSH = CORE_GRAPHICS.bind("CGContextFlush",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CG_CONTEXT_RELEASE = CORE_GRAPHICS.bind("CGContextRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle AX_UI_ELEMENT_CREATE_SYSTEM_WIDE = APPLICATION_SERVICES.bind(
            "AXUIElementCreateSystemWide", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle AX_UI_ELEMENT_POST_KEYBOARD_EVENT = APPLICATION_SERVICES.bind(
            "AXUIElementPostKeyboardEvent", FunctionDescriptor.of(AX_ERROR, ADDRESS, CG_CHAR_CODE, CG_KEY_CODE,
                    BOOLEAN));
    private static final MethodHandle CF_RELEASE = CORE_FOUNDATION.bind("CFRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CF_DATA_GET_BYTE_PTR = CORE_FOUNDATION.bind("CFDataGetBytePtr",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle ROUND = LIB_SYSTEM.bind("round",
            FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE));

    /** What the JNI's {@code GlassRobot} object held: the buttons pressed through the robot and not yet released. */
    static final class RobotState {

        private int mouseButtons;
    }

    private MacRobotCalls() {
    }

    /** {@code _init} (GR:208), which had neither the detached assertion nor a pool. */
    static RobotState createRobot() {
        return new RobotState();
    }

    /** {@code _keyPress} (GR:234). */
    static void keyPress(int javaKeyCode) {
        postKeyEvent(javaKeyCode, true);
    }

    /** {@code _keyRelease} (GR:252). */
    static void keyRelease(int javaKeyCode) {
        postKeyEvent(javaKeyCode, false);
    }

    /** {@code _mouseMove} (GR:270) and {@code -[GlassRobot mouseMove:]} (GR:147). */
    static void mouseMove(RobotState robot, float x, float y) {
        boolean detached = mainThreadDetached();
        poolPush();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment location = cgPoint(arena, x, y);
            MemorySegment event = mouseMovedEvent(robot.mouseButtons, location);
            CGEventPost(kCGHIDEventTap, event);
            CGWarpMouseCursorPosition(location);
            CFRelease(event);
        } finally {
            poolPop();
        }
        throwIfDetached(detached);
    }

    /** {@code _getMouseX} (GR:289). */
    static float mouseX() {
        return (float) mouseLocation(X);
    }

    /** {@code _getMouseY} (GR:312). */
    static float mouseY() {
        return (float) mouseLocation(Y);
    }

    /** {@code _mousePress} (GR:335) and {@code -[GlassRobot mousePress:]} (GR:187). */
    static void mousePress(RobotState robot, int buttons) {
        boolean detached = mainThreadDetached();
        poolPush();
        try (Arena arena = Arena.ofConfined()) {
            robot.mouseButtons |= buttons;
            postMouseEvents(mouseLocationFlipped(arena), buttons, true);
        } finally {
            poolPop();
        }
        throwIfDetached(detached);
    }

    /** {@code _mouseRelease} (GR:354) and {@code -[GlassRobot mouseRelease:]} (GR:194). */
    static void mouseRelease(RobotState robot, int buttons) {
        boolean detached = mainThreadDetached();
        poolPush();
        try (Arena arena = Arena.ofConfined()) {
            postMouseEvents(mouseLocationFlipped(arena), buttons, false);
            robot.mouseButtons &= ~buttons;
        } finally {
            poolPop();
        }
        throwIfDetached(detached);
    }

    /** {@code _mouseWheel} (GR:373). */
    static void mouseWheel(int wheelAmt) {
        boolean detached = mainThreadDetached();
        poolPush();
        try {
            MemorySegment event = scrollWheelEvent(wheelAmt);
            CGEventPost(kCGHIDEventTap, event);
            CFRelease(event);
        } finally {
            poolPop();
        }
        throwIfDetached(detached);
    }

    /**
     * {@code _getPixelColor} (GR:393): the first pixel of a 1x1 capture at {@code (x, y)}, matched to sRGB, as ARGB.
     * The first four bytes of the capture are read as one host-order pixel whatever its format (latent bug 22),
     * {@code data} is released even when it is NULL, and the matched colour is read even when it is NULL (latent bug
     * 2).
     */
    static int pixelColor(double x, double y) {
        int color = 0;
        MemorySegment origColor = MemorySegment.NULL;
        boolean detached = mainThreadDetached();
        poolPush();
        try {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment screenImage = CGWindowListCreateImage(cgRect(arena, x, y, 1.0, 1.0),
                        kCGWindowListOptionOnScreenOnly, kCGNullWindowID, kCGWindowImageDefault);
                if (screenImage.address() != 0) {
                    MemorySegment provider = CGImageGetDataProvider(screenImage);
                    if (provider.address() != 0) {
                        MemorySegment data = CGDataProviderCopyData(provider);
                        if (data.address() != 0) {
                            MemorySegment pixels = CFDataGetBytePtr(data);
                            if (pixels.address() != 0) {
                                MemorySegment components = pixelComponents(arena, firstPixel(pixels));
                                origColor = CGColorCreate(CGImageGetColorSpace(screenImage), components);
                            }
                        }
                        CFRelease(data);
                    }
                    CGImageRelease(screenImage);
                }
            }
            if (origColor.address() != 0) {
                MemorySegment sRGBSpace = CGColorSpaceCreateWithName(colorSpaceSrgbName());
                MemorySegment correctedColor = CGColorCreateCopyByMatchingToColorSpace(sRGBSpace,
                        kCGRenderingIntentAbsoluteColorimetric, origColor, MemorySegment.NULL);
                MemorySegment components = bounded(CGColorGetComponents(correctedColor),
                        COLOR_COMPONENTS * CG_FLOAT.byteSize());
                color = (roundedByte(components, 3) & 0xFF) << 24;
                color |= (roundedByte(components, 0) & 0xFF) << 16;
                color |= (roundedByte(components, 1) & 0xFF) << 8;
                color |= (roundedByte(components, 2) & 0xFF);
                CGColorSpaceRelease(sRGBSpace);
                CGColorRelease(correctedColor);
                CGColorRelease(origColor);
            }
        } finally {
            poolPop();
        }
        throwIfDetached(detached);
        return color;
    }

    /**
     * {@code _getScreenCapture} (GR:455): the screen in {@code (x, y, width, height)} as a {@code Pixels} of
     * premultiplied ARGB ints, at the capture's own size unless {@code scaleToFit}; null where the JNI returned NULL.
     * <p>
     * The returns at GR:482, :485, :526 and :533 skip the pool's pop and the image's release, as the JNI's did
     * (latent bug 1); what {@code getScaleFactor} or {@code createPixels} threw propagates. Every other way out, a
     * throw included, pops the pool. While detached, the {@code ClassForName} of {@code Application} reported the
     * assertion's exception through its {@code GLASS_CHECK_EXCEPTION}, after the draw, and the capture went on. A
     * return before that point left the exception pending, so the native threw it, unless a failed
     * {@code NewIntArray} had replaced it with an {@link OutOfMemoryError}.
     */
    static Pixels screenCapture(int x, int y, int width, int height, boolean scaleToFit) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        if (width >= Integer.MAX_VALUE / height) {
            return null;
        }
        boolean detached = mainThreadDetached();
        poolPush();
        boolean popPool = true;
        try {
            MemorySegment screenImage;
            try (Arena arena = Arena.ofConfined()) {
                screenImage = CGWindowListCreateImage(cgRect(arena, x, y, width, height),
                        kCGWindowListOptionOnScreenOnly, kCGNullWindowID, kCGWindowImageDefault);
            }
            if (screenImage.address() == 0) {
                throwIfDetached(detached);
                return null;
            }
            int pixWidth = width;
            int pixHeight = height;
            if (!scaleToFit) {
                pixWidth = (int) CGImageGetWidth(screenImage);
                pixHeight = (int) CGImageGetHeight(screenImage);
                if (pixWidth <= 0 || pixHeight <= 0 || pixWidth >= Integer.MAX_VALUE / pixHeight) {
                    // latent bug 1: GR:482 and :485 returned inside the pool
                    popPool = false;
                    throwIfDetached(detached);
                    return null;
                }
            }
            int[] pixelArray;
            try {
                pixelArray = new int[pixWidth * pixHeight];
            } catch (OutOfMemoryError e) {
                CGImageRelease(screenImage);
                throw e;
            }
            if (!drawImage(screenImage, pixelArray, pixWidth, pixHeight)) {
                CGImageRelease(screenImage);
                throwIfDetached(detached);
                return null;
            }
            if (detached) {
                reportException(mainThreadDetachedError());
            }
            // latent bug 1: GR:526 and :533 returned inside the pool when these threw
            popPool = false;
            float scale = getScaleFactor(x, y, width, height);
            Pixels pixels = createPixels(pixWidth, pixHeight, pixelArray, scale, scale);
            popPool = true;
            CGImageRelease(screenImage);
            return pixels;
        } finally {
            if (popPool) {
                poolPop();
            }
        }
    }

    /**
     * The event {@code -[GlassRobot mouseMove:]} posted: a drag of the highest button the robot holds, else a move,
     * with the number of bits up to that button as the button (latent bug 3).
     */
    static MemorySegment mouseMovedEvent(int mouseButtons, MemorySegment location) {
        int type = kCGEventMouseMoved;
        int index = 0;
        for (int buttons = mouseButtons; buttons != 0; index++, buttons >>>= 1) {
            if ((buttons & 1) != 0) {
                type = switch (index) {
                    case 0 -> kCGEventLeftMouseDragged;
                    case 1 -> kCGEventRightMouseDragged;
                    default -> kCGEventOtherMouseDragged;
                };
            }
        }
        return CGEventCreateMouseEvent(MemorySegment.NULL, type, location, index);
    }

    /** The event {@code PostGlassMouseEvent} (GR:81) posted for the button of bit {@code index}. */
    static MemorySegment mouseButtonEvent(int index, boolean pressed, MemorySegment location) {
        int type = switch (index) {
            case 0 -> pressed ? kCGEventLeftMouseDown : kCGEventLeftMouseUp;
            case 1 -> pressed ? kCGEventRightMouseDown : kCGEventRightMouseUp;
            default -> pressed ? kCGEventOtherMouseDown : kCGEventOtherMouseUp;
        };
        return CGEventCreateMouseEvent(MemorySegment.NULL, type, location, index);
    }

    /** The event {@code _mouseWheel} posted: {@code wheelAmt} lines of the first wheel. */
    static MemorySegment scrollWheelEvent(int wheelAmt) {
        return CGEventCreateScrollWheelEvent(MemorySegment.NULL, kCGScrollEventUnitLine, 1, wheelAmt);
    }

    static MemorySegment cgPoint(SegmentAllocator allocator, double x, double y) {
        MemorySegment point = allocator.allocate(CG_POINT);
        point.set(CG_FLOAT, X, x);
        point.set(CG_FLOAT, Y, y);
        return point;
    }

    /** {@code kCGColorSpaceSRGB}, which {@code _getPixelColor} and {@code _getScreenCapture} named the space by. */
    static MemorySegment colorSpaceSrgbName() {
        try {
            return (MemorySegment) Capture.K_CG_COLOR_SPACE_SRGB.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code GLASS_POOL_ENTER} of a native body. */
    static void poolPush() {
        try {
            GMAC_POOL_PUSH.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code GLASS_POOL_EXIT} of a native body. */
    static void poolPop() {
        try {
            GMAC_POOL_POP.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void CFRelease(MemorySegment object) {
        try {
            CF_RELEASE.invokeExact(object);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code PostGlassKeyEvent} (GR:108): through HIServices, which the JNI preferred to a {@code CGEvent} because
     * the events of some keys went missing. A Java key code without a Mac key posts nothing.
     */
    private static void postKeyEvent(int javaKeyCode, boolean keyPressed) {
        boolean detached = mainThreadDetached();
        poolPush();
        try {
            int macCode = findMacKey(javaKeyCode);
            if (macCode >= 0) {
                MemorySegment elem = AXUIElementCreateSystemWide();
                AXUIElementPostKeyboardEvent(elem, (char) 0, (char) macCode, keyPressed);
                CFRelease(elem);
            }
        } finally {
            poolPop();
        }
        throwIfDetached(detached);
    }

    /** {@code PostGlassMouseEvent} (GR:81): one event per set bit of {@code buttons}, the lowest first. */
    private static void postMouseEvents(MemorySegment location, int buttons, boolean buttonPressed) {
        for (int index = 0; buttons != 0; index++, buttons >>>= 1) {
            if ((buttons & 1) != 0) {
                MemorySegment newEvent = mouseButtonEvent(index, buttonPressed, location);
                CGEventPost(kCGHIDEventTap, newEvent);
                CFRelease(newEvent);
            }
        }
    }

    private static double mouseLocation(long coordinate) {
        boolean detached = mainThreadDetached();
        poolPush();
        double value;
        try (Arena arena = Arena.ofConfined()) {
            value = mouseLocationFlipped(arena).get(CG_FLOAT, coordinate);
        } finally {
            poolPop();
        }
        throwIfDetached(detached);
        return value;
    }

    /**
     * Draws {@code screenImage} into {@code pixelArray} as the JNI drew it into the copy {@code GetIntArrayElements}
     * made of the new, zero-filled array, which {@code ReleaseIntArrayElements} copied back. False where that copy's
     * {@code malloc} failed, which made {@code GetIntArrayElements} answer NULL and raise nothing.
     */
    private static boolean drawImage(MemorySegment screenImage, int[] pixelArray, int pixWidth, int pixHeight) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment javaPixels;
            try {
                javaPixels = arena.allocate(Integer.BYTES * (long) pixelArray.length, MALLOC_ALIGNMENT);
            } catch (OutOfMemoryError e) {
                return false;
            }
            MemorySegment picColorSpace = CGColorSpaceCreateWithName(colorSpaceSrgbName());
            MemorySegment jPicContextRef = CGBitmapContextCreate(javaPixels, pixWidth, pixHeight, 8,
                    Integer.BYTES * (long) pixWidth, picColorSpace, CAPTURE_BITMAP_INFO);
            CGColorSpaceRelease(picColorSpace);
            CGContextDrawImage(jPicContextRef, cgRect(arena, 0, 0, pixWidth, pixHeight), screenImage);
            CGContextFlush(jPicContextRef);
            CGContextRelease(jPicContextRef);
            MemorySegment.copy(javaPixels, JAVA_INT, 0, pixelArray, 0, pixelArray.length);
        }
        return true;
    }

    /* The C read the bytes through a uint32_t pointer, which arm64 loads at any alignment. */
    private static int firstPixel(MemorySegment pixels) {
        return bounded(pixels, JAVA_INT_UNALIGNED.byteSize()).get(JAVA_INT_UNALIGNED, 0);
    }

    /** The components GR:420-423 made of {@code pixel}: red, green, blue and alpha, each a byte over 255. */
    private static MemorySegment pixelComponents(SegmentAllocator allocator, int pixel) {
        MemorySegment components = allocator.allocate(CG_FLOAT, COLOR_COMPONENTS);
        components.setAtIndex(CG_FLOAT, 0, ((pixel & 0x00FF0000) >>> 16) / 255.0);
        components.setAtIndex(CG_FLOAT, 1, ((pixel & 0x0000FF00) >>> 8) / 255.0);
        components.setAtIndex(CG_FLOAT, 2, (pixel & 0x000000FF) / 255.0);
        components.setAtIndex(CG_FLOAT, 3, ((pixel & 0xFF000000) >>> 24) / 255.0);
        return components;
    }

    /**
     * {@code (jint)(round(component * 255))}, which clang compiled to one {@code fcvtas} on arm64: libm's
     * {@code round} rounds half away from zero, and Java's narrowing saturates, as that instruction does. On x86_64 the
     * narrowing compiled to {@code cvttsd2si}, which answers INT32_MIN out of range and for NaN, so the masked byte
     * differs there only from 2^31 up (g1 Triage).
     */
    private static int roundedByte(MemorySegment components, int index) {
        return (int) round(components.getAtIndex(CG_FLOAT, index) * 255);
    }

    private static MemorySegment cgRect(SegmentAllocator allocator, double x, double y, double width,
                                        double height) {
        MemorySegment rect = allocator.allocate(CG_RECT);
        rect.set(CG_FLOAT, RECT_X, x);
        rect.set(CG_FLOAT, RECT_Y, y);
        rect.set(CG_FLOAT, RECT_WIDTH, width);
        rect.set(CG_FLOAT, RECT_HEIGHT, height);
        return rect;
    }

    private static long rectOffset(String member, String field) {
        return CG_RECT.byteOffset(PathElement.groupElement(member), PathElement.groupElement(field));
    }

    private static void throwIfDetached(boolean detached) {
        if (detached) {
            throw mainThreadDetachedError();
        }
    }

    private static int findMacKey(int javaKeyCode) {
        try {
            return (int) GMAC_KEY_FIND_MAC.invokeExact(javaKeyCode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code -[GlassRobot getMousePosFlipped]} (GR:178): the pointer, with y measured down from the top of the first
     * screen. Raises an Objective-C exception that nothing catches when there is no screen (latent bug 21).
     */
    private static MemorySegment mouseLocationFlipped(SegmentAllocator allocator) {
        MemorySegment where = allocator.allocate(CG_POINT);
        try {
            GMAC_MOUSE_LOCATION_FLIPPED.invokeExact(where.asSlice(X, CG_FLOAT), where.asSlice(Y, CG_FLOAT));
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return where;
    }

    private static float getScaleFactor(int x, int y, int w, int h) {
        try {
            return (float) Capture.GET_SCALE_FACTOR.invokeExact(x, y, w, h);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static Pixels createPixels(int width, int height, int[] data, float scalex, float scaley) {
        try {
            return (Pixels) Capture.CREATE_PIXELS.invokeExact(width, height, data, scalex, scaley);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGEventCreateMouseEvent(MemorySegment source, int mouseType,
                                                         MemorySegment mouseCursorPosition, int mouseButton) {
        try {
            return (MemorySegment) CG_EVENT_CREATE_MOUSE_EVENT.invokeExact(source, mouseType, mouseCursorPosition,
                    mouseButton);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGEventCreateScrollWheelEvent(MemorySegment source, int units, int wheelCount,
                                                               int wheel1) {
        try {
            return (MemorySegment) CG_EVENT_CREATE_SCROLL_WHEEL_EVENT.invokeExact(source, units, wheelCount,
                    wheel1);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void CGEventPost(int tap, MemorySegment event) {
        try {
            CG_EVENT_POST.invokeExact(tap, event);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int CGWarpMouseCursorPosition(MemorySegment newCursorPosition) {
        try {
            return (int) CG_WARP_MOUSE_CURSOR_POSITION.invokeExact(newCursorPosition);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGWindowListCreateImage(MemorySegment screenBounds, int listOption, int windowID,
                                                         int imageOption) {
        try {
            return (MemorySegment) Capture.CG_WINDOW_LIST_CREATE_IMAGE.invokeExact(screenBounds, listOption,
                    windowID, imageOption);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGImageGetDataProvider(MemorySegment image) {
        try {
            return (MemorySegment) CG_IMAGE_GET_DATA_PROVIDER.invokeExact(image);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGDataProviderCopyData(MemorySegment provider) {
        try {
            return (MemorySegment) CG_DATA_PROVIDER_COPY_DATA.invokeExact(provider);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGImageGetColorSpace(MemorySegment image) {
        try {
            return (MemorySegment) CG_IMAGE_GET_COLOR_SPACE.invokeExact(image);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static long CGImageGetWidth(MemorySegment image) {
        try {
            return (long) CG_IMAGE_GET_WIDTH.invokeExact(image);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static long CGImageGetHeight(MemorySegment image) {
        try {
            return (long) CG_IMAGE_GET_HEIGHT.invokeExact(image);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void CGImageRelease(MemorySegment image) {
        try {
            CG_IMAGE_RELEASE.invokeExact(image);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGColorCreate(MemorySegment space, MemorySegment components) {
        try {
            return (MemorySegment) CG_COLOR_CREATE.invokeExact(space, components);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGColorCreateCopyByMatchingToColorSpace(MemorySegment space, int intent,
                                                                         MemorySegment color, MemorySegment options) {
        try {
            return (MemorySegment) CG_COLOR_CREATE_COPY_BY_MATCHING_TO_COLOR_SPACE.invokeExact(space, intent, color,
                    options);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGColorGetComponents(MemorySegment color) {
        try {
            return (MemorySegment) CG_COLOR_GET_COMPONENTS.invokeExact(color);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void CGColorRelease(MemorySegment color) {
        try {
            CG_COLOR_RELEASE.invokeExact(color);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGColorSpaceCreateWithName(MemorySegment name) {
        try {
            return (MemorySegment) CG_COLOR_SPACE_CREATE_WITH_NAME.invokeExact(name);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void CGColorSpaceRelease(MemorySegment space) {
        try {
            CG_COLOR_SPACE_RELEASE.invokeExact(space);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CGBitmapContextCreate(MemorySegment data, long width, long height,
                                                       long bitsPerComponent, long bytesPerRow, MemorySegment space,
                                                       int bitmapInfo) {
        try {
            return (MemorySegment) CG_BITMAP_CONTEXT_CREATE.invokeExact(data, width, height, bitsPerComponent,
                    bytesPerRow, space, bitmapInfo);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void CGContextDrawImage(MemorySegment context, MemorySegment rect, MemorySegment image) {
        try {
            CG_CONTEXT_DRAW_IMAGE.invokeExact(context, rect, image);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void CGContextFlush(MemorySegment context) {
        try {
            CG_CONTEXT_FLUSH.invokeExact(context);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void CGContextRelease(MemorySegment context) {
        try {
            CG_CONTEXT_RELEASE.invokeExact(context);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment AXUIElementCreateSystemWide() {
        try {
            return (MemorySegment) AX_UI_ELEMENT_CREATE_SYSTEM_WIDE.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int AXUIElementPostKeyboardEvent(MemorySegment application, char keyChar, char virtualKey,
                                                    boolean keyDown) {
        try {
            return (int) AX_UI_ELEMENT_POST_KEYBOARD_EVENT.invokeExact(application, keyChar, virtualKey, keyDown);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment CFDataGetBytePtr(MemorySegment data) {
        try {
            return (MemorySegment) CF_DATA_GET_BYTE_PTR.invokeExact(data);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static double round(double x) {
        try {
            return (double) ROUND.invokeExact(x);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * What only the captures use, bound at the first: {@code CGWindowListCreateImage} is obsoleted in the macOS 15
     * SDK, and {@code Application.getScaleFactor} and {@code createPixels} are package-private, which the JNI called
     * by name (g1 Risk 6).
     */
    private static final class Capture {

        static final MethodHandle CG_WINDOW_LIST_CREATE_IMAGE = CORE_GRAPHICS.bind("CGWindowListCreateImage",
                FunctionDescriptor.of(ADDRESS, CG_RECT, CG_WINDOW_LIST_OPTION, CG_WINDOW_ID, CG_WINDOW_IMAGE_OPTION));

        static final MethodHandle K_CG_COLOR_SPACE_SRGB = CORE_GRAPHICS.bindDataValue("kCGColorSpaceSRGB");

        static final MethodHandle GET_SCALE_FACTOR = application("getScaleFactor",
                MethodType.methodType(float.class, int.class, int.class, int.class, int.class));
        static final MethodHandle CREATE_PIXELS = application("createPixels",
                MethodType.methodType(Pixels.class, int.class, int.class, int[].class, float.class, float.class));

        private static MethodHandle application(String name, MethodType type) {
            try {
                return MethodHandles.privateLookupIn(Application.class, MethodHandles.lookup())
                        .findStatic(Application.class, name, type);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
