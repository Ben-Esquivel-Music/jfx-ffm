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

package com.sun.javafx.font.coretext;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;

/** Test access to {@link CGNative}, which is package-private. */
public final class CGNativeShim {

    private CGNativeShim() {
    }

    /** {@link CGNative.PathSink} for tests outside the package. */
    public interface Sink {
        void moveTo(double x, double y);

        void lineTo(double x, double y);

        void quadTo(double cx, double cy, double x, double y);

        void curveTo(double cx1, double cy1, double cx2, double cy2, double x, double y);

        void closePath();

        void unknownElement(int type);

        void failed(Throwable t);
    }

    public static Map<String, FunctionDescriptor> boundFunctions() {
        return CGNative.boundFunctions();
    }

    public static List<String> boundData() {
        return CGNative.boundData();
    }

    public static StructLayout layout(String name) {
        return switch (name) {
            case "CGPoint" -> CGNative.CGPOINT;
            case "CGSize" -> CGNative.CGSIZE;
            case "CGRect" -> CGNative.CGRECT;
            case "CGAffineTransform" -> CGNative.CGAFFINETRANSFORM;
            case "CGPathElement" -> CGNative.CGPATHELEMENT;
            default -> throw new IllegalArgumentException(name);
        };
    }

    public static MemorySegment identity() {
        return CGNative.CGAffineTransformIdentity;
    }

    public static MemorySegment pathApplierStub() {
        return CGNative.pathApplierStub();
    }

    public static boolean sinkInstalled() {
        return CGNative.currentSink() != null;
    }

    // A downcall straight into the applier stub, to hand it elements CoreGraphics never makes.
    private static final class Applier {
        static final MethodHandle INVOKE = invoker();

        @SuppressWarnings("restricted")
        private static MethodHandle invoker() {
            return Linker.nativeLinker().downcallHandle(CGNative.pathApplierStub(),
                                                        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        }
    }

    /** Calls the applier stub as CoreGraphics calls it, so {@code element} goes to this thread's current sink. */
    public static void applyElement(MemorySegment element) {
        try {
            Applier.INVOKE.invokeExact(MemorySegment.NULL, element);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    public static void CGPathApply(MemorySegment path, Sink sink) {
        CGNative.CGPathApply(path, new CGNative.PathSink() {
            @Override
            public void moveTo(double x, double y) {
                sink.moveTo(x, y);
            }

            @Override
            public void lineTo(double x, double y) {
                sink.lineTo(x, y);
            }

            @Override
            public void quadTo(double cx, double cy, double x, double y) {
                sink.quadTo(cx, cy, x, y);
            }

            @Override
            public void curveTo(double cx1, double cy1, double cx2, double cy2, double x, double y) {
                sink.curveTo(cx1, cy1, cx2, cy2, x, y);
            }

            @Override
            public void closePath() {
                sink.closePath();
            }

            @Override
            public void unknownElement(int type) {
                sink.unknownElement(type);
            }

            @Override
            public void failed(Throwable t) {
                sink.failed(t);
            }
        });
    }

    public static MemorySegment CGBitmapContextCreate(MemorySegment data, long width, long height,
                                                      long bitsPerComponent, long bytesPerRow, MemorySegment space,
                                                      int bitmapInfo) {
        return CGNative.CGBitmapContextCreate(data, width, height, bitsPerComponent, bytesPerRow, space, bitmapInfo);
    }

    public static long CGBitmapContextGetBitsPerPixel(MemorySegment context) {
        return CGNative.CGBitmapContextGetBitsPerPixel(context);
    }

    public static long CGBitmapContextGetBytesPerRow(MemorySegment context) {
        return CGNative.CGBitmapContextGetBytesPerRow(context);
    }

    public static MemorySegment CGBitmapContextGetData(MemorySegment context, long byteSize) {
        return CGNative.CGBitmapContextGetData(context, byteSize);
    }

    public static long CGBitmapContextGetHeight(MemorySegment context) {
        return CGNative.CGBitmapContextGetHeight(context);
    }

    public static long CGBitmapContextGetWidth(MemorySegment context) {
        return CGNative.CGBitmapContextGetWidth(context);
    }

    public static MemorySegment CGColorSpaceCreateDeviceGray() {
        return CGNative.CGColorSpaceCreateDeviceGray();
    }

    public static MemorySegment CGColorSpaceCreateDeviceRGB() {
        return CGNative.CGColorSpaceCreateDeviceRGB();
    }

    public static void CGContextFillRect(MemorySegment context, MemorySegment rect) {
        CGNative.CGContextFillRect(context, rect);
    }

    public static void CGContextRelease(MemorySegment context) {
        CGNative.CGContextRelease(context);
    }

    public static void CGContextSetAllowsAntialiasing(MemorySegment context, boolean allows) {
        CGNative.CGContextSetAllowsAntialiasing(context, allows);
    }

    public static void CGContextSetAllowsFontSmoothing(MemorySegment context, boolean allows) {
        CGNative.CGContextSetAllowsFontSmoothing(context, allows);
    }

    public static void CGContextSetAllowsFontSubpixelPositioning(MemorySegment context, boolean allows) {
        CGNative.CGContextSetAllowsFontSubpixelPositioning(context, allows);
    }

    public static void CGContextSetAllowsFontSubpixelQuantization(MemorySegment context, boolean allows) {
        CGNative.CGContextSetAllowsFontSubpixelQuantization(context, allows);
    }

    public static void CGContextSetRGBFillColor(MemorySegment context, double red, double green, double blue,
                                                double alpha) {
        CGNative.CGContextSetRGBFillColor(context, red, green, blue, alpha);
    }

    public static void CGContextTranslateCTM(MemorySegment context, double tx, double ty) {
        CGNative.CGContextTranslateCTM(context, tx, ty);
    }

    public static MemorySegment CGDataProviderCreateWithURL(MemorySegment url) {
        return CGNative.CGDataProviderCreateWithURL(url);
    }

    public static MemorySegment CGFontCreateWithDataProvider(MemorySegment provider) {
        return CGNative.CGFontCreateWithDataProvider(provider);
    }

    public static MemorySegment CGPathGetPathBoundingBox(SegmentAllocator allocator, MemorySegment path) {
        return CGNative.CGPathGetPathBoundingBox(allocator, path);
    }

    public static void CGPathRelease(MemorySegment path) {
        CGNative.CGPathRelease(path);
    }

    public static MemorySegment CGRectApplyAffineTransform(SegmentAllocator allocator, MemorySegment rect,
                                                           MemorySegment transform) {
        return CGNative.CGRectApplyAffineTransform(allocator, rect, transform);
    }
}
