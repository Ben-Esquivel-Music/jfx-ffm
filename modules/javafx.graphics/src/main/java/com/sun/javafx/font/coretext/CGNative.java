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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * CoreGraphics. Initialising this class initialises {@link CFNative} first, before CoreGraphics is opened.
 * <p>
 * Every method that shares its name with a native entry point of {@code OS} has a {@code MemorySegment} in its
 * descriptor, and no entry point does, so the binding witness of the macOS goldens never takes one for a second
 * binding of an entry point.
 */
final class CGNative {

    static final StructLayout CGPOINT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"), JAVA_DOUBLE.withName("y"));
    static final StructLayout CGSIZE = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("width"), JAVA_DOUBLE.withName("height"));
    static final StructLayout CGRECT = MemoryLayout.structLayout(
            CGPOINT.withName("origin"), CGSIZE.withName("size"));
    static final StructLayout CGAFFINETRANSFORM = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("a"), JAVA_DOUBLE.withName("b"), JAVA_DOUBLE.withName("c"),
            JAVA_DOUBLE.withName("d"), JAVA_DOUBLE.withName("tx"), JAVA_DOUBLE.withName("ty"));
    static final StructLayout CGPATHELEMENT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4), ADDRESS.withName("points"));

    private static final CFNative.Framework CG = CFNative.framework("CoreGraphics");

    private static final MethodHandle CG_BITMAP_CONTEXT_CREATE = CG.bind("CGBitmapContextCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle CG_BITMAP_CONTEXT_GET_BITS_PER_PIXEL = CG.bind(
            "CGBitmapContextGetBitsPerPixel", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CG_BITMAP_CONTEXT_GET_BYTES_PER_ROW = CG.bind("CGBitmapContextGetBytesPerRow",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CG_BITMAP_CONTEXT_GET_DATA = CG.bind("CGBitmapContextGetData",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CG_BITMAP_CONTEXT_GET_HEIGHT = CG.bind("CGBitmapContextGetHeight",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CG_BITMAP_CONTEXT_GET_WIDTH = CG.bind("CGBitmapContextGetWidth",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CG_COLOR_SPACE_CREATE_DEVICE_GRAY = CG.bind("CGColorSpaceCreateDeviceGray",
            FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle CG_COLOR_SPACE_CREATE_DEVICE_RGB = CG.bind("CGColorSpaceCreateDeviceRGB",
            FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle CG_CONTEXT_FILL_RECT = CG.bind("CGContextFillRect",
            FunctionDescriptor.ofVoid(ADDRESS, CGRECT));
    private static final MethodHandle CG_CONTEXT_RELEASE = CG.bind("CGContextRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    // C bool is one byte: JAVA_BOOLEAN, never JAVA_BYTE or JAVA_INT (R-2).
    private static final MethodHandle CG_CONTEXT_SET_ALLOWS_ANTIALIASING = CG.bind("CGContextSetAllowsAntialiasing",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN));
    private static final MethodHandle CG_CONTEXT_SET_ALLOWS_FONT_SMOOTHING = CG.bind(
            "CGContextSetAllowsFontSmoothing", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN));
    private static final MethodHandle CG_CONTEXT_SET_ALLOWS_FONT_SUBPIXEL_POSITIONING = CG.bind(
            "CGContextSetAllowsFontSubpixelPositioning", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN));
    private static final MethodHandle CG_CONTEXT_SET_ALLOWS_FONT_SUBPIXEL_QUANTIZATION = CG.bind(
            "CGContextSetAllowsFontSubpixelQuantization", FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN));
    private static final MethodHandle CG_CONTEXT_SET_RGB_FILL_COLOR = CG.bind("CGContextSetRGBFillColor",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
    private static final MethodHandle CG_CONTEXT_TRANSLATE_CTM = CG.bind("CGContextTranslateCTM",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));
    private static final MethodHandle CG_DATA_PROVIDER_CREATE_WITH_URL = CG.bind("CGDataProviderCreateWithURL",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CG_FONT_CREATE_WITH_DATA_PROVIDER = CG.bind("CGFontCreateWithDataProvider",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CG_PATH_APPLY = CG.bind("CGPathApply",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    // A CGRect result comes back in four FP registers on arm64 and through a hidden pointer on x86_64; either way
    // the handle copies it into a segment from its leading SegmentAllocator.
    private static final MethodHandle CG_PATH_GET_PATH_BOUNDING_BOX = CG.bind("CGPathGetPathBoundingBox",
            FunctionDescriptor.of(CGRECT, ADDRESS));
    private static final MethodHandle CG_PATH_RELEASE = CG.bind("CGPathRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    // The 48-byte transform travels by value: through memory on both arm64 and x86_64.
    private static final MethodHandle CG_RECT_APPLY_AFFINE_TRANSFORM = CG.bind("CGRectApplyAffineTransform",
            FunctionDescriptor.of(CGRECT, CGRECT, CGAFFINETRANSFORM));

    // Passed by address, as &CGAffineTransformIdentity.
    static final MemorySegment CGAffineTransformIdentity = CG.dataAddress("CGAffineTransformIdentity");

    /** The receiver of {@link #CGPathApply}'s elements. Only doubles and ints cross it. */
    interface PathSink {
        void moveTo(double x, double y);

        void lineTo(double x, double y);

        void quadTo(double cx, double cy, double x, double y);

        void curveTo(double cx1, double cy1, double cx2, double cy2, double x, double y);

        void closePath();

        void unknownElement(int type);

        /** Receives what an element callback threw; the path's later elements are not delivered. Must not throw. */
        void failed(Throwable t);
    }

    // Layout runs on several threads at once, and CoreGraphics calls the applier synchronously on the calling
    // thread, so each thread's CGPathApply reaches its own sink through this slot.
    private static final ThreadLocal<PathSink> CURRENT_SINK = new ThreadLocal<>();

    private CGNative() {
    }

    // One stub for the process, made on the first CGPathApply: its target is static and CoreGraphics drops the
    // pointer when CGPathApply returns, so Arena.global() cannot leak it.
    private static final class PathApplier {
        static final MemorySegment STUB = stub();

        @SuppressWarnings("restricted")
        private static MemorySegment stub() {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(CGNative.class, "pathApplier",
                        MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
                FunctionDescriptor descriptor = FunctionDescriptor.ofVoid(ADDRESS,
                        ADDRESS.withTargetLayout(CGPATHELEMENT));
                return CFNative.LINKER.upcallStub(target, descriptor, Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("CGPathApply applier missing", e);
            }
        }
    }

    /** Binds CoreFoundation, then CoreGraphics, if they are not bound yet. {@link CTNative} calls it first. */
    static void link() {
    }

    static Map<String, FunctionDescriptor> boundFunctions() {
        return CG.functions();
    }

    static List<String> boundData() {
        return CG.data();
    }

    static MemorySegment pathApplierStub() {
        return PathApplier.STUB;
    }

    static PathSink currentSink() {
        return CURRENT_SINK.get();
    }

    @SuppressWarnings("restricted")
    private static MemorySegment bounded(MemorySegment segment, long byteSize) {
        return segment.address() == 0 ? segment : segment.reinterpret(byteSize);
    }

    // An escaping exception would end the JVM, so every failure goes to the sink, and close reads no points
    // (CoreGraphics may pass NULL for them).
    private static void pathApplier(MemorySegment info, MemorySegment element) {
        PathSink sink = CURRENT_SINK.get();
        if (sink == null) {
            return;
        }
        try {
            int type = element.get(JAVA_INT, 0);
            switch (type) {
                case 0 -> {
                    MemorySegment p = points(element, 1);
                    sink.moveTo(p.get(JAVA_DOUBLE, 0), p.get(JAVA_DOUBLE, 8));
                }
                case 1 -> {
                    MemorySegment p = points(element, 1);
                    sink.lineTo(p.get(JAVA_DOUBLE, 0), p.get(JAVA_DOUBLE, 8));
                }
                case 2 -> {
                    MemorySegment p = points(element, 2);
                    sink.quadTo(p.get(JAVA_DOUBLE, 0), p.get(JAVA_DOUBLE, 8), p.get(JAVA_DOUBLE, 16),
                                p.get(JAVA_DOUBLE, 24));
                }
                case 3 -> {
                    MemorySegment p = points(element, 3);
                    sink.curveTo(p.get(JAVA_DOUBLE, 0), p.get(JAVA_DOUBLE, 8), p.get(JAVA_DOUBLE, 16),
                                 p.get(JAVA_DOUBLE, 24), p.get(JAVA_DOUBLE, 32), p.get(JAVA_DOUBLE, 40));
                }
                case 4 -> sink.closePath();
                default -> sink.unknownElement(type);
            }
        } catch (Throwable t) {
            CURRENT_SINK.remove();
            sink.failed(t);
        }
    }

    private static MemorySegment points(MemorySegment element, int count) {
        return bounded(element.get(ADDRESS, 8), count * CGPOINT.byteSize());
    }

    /**
     * Delivers the elements of {@code path} to {@code sink} on this thread. A sink may apply another path from an
     * element callback: the outer sink gets the rest of its path once the inner call returns.
     */
    static void CGPathApply(MemorySegment path, PathSink sink) {
        PathSink outer = CURRENT_SINK.get();
        CURRENT_SINK.set(sink);
        try {
            CG_PATH_APPLY.invokeExact(path, MemorySegment.NULL, PathApplier.STUB);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        } finally {
            if (outer == null) {
                CURRENT_SINK.remove();
            } else {
                CURRENT_SINK.set(outer);
            }
        }
    }

    static MemorySegment CGBitmapContextCreate(MemorySegment data, long width, long height, long bitsPerComponent,
                                               long bytesPerRow, MemorySegment space, int bitmapInfo) {
        try {
            return (MemorySegment) CG_BITMAP_CONTEXT_CREATE.invokeExact(data, width, height, bitsPerComponent,
                                                                         bytesPerRow, space, bitmapInfo);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static long CGBitmapContextGetBitsPerPixel(MemorySegment context) {
        try {
            return (long) CG_BITMAP_CONTEXT_GET_BITS_PER_PIXEL.invokeExact(context);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static long CGBitmapContextGetBytesPerRow(MemorySegment context) {
        try {
            return (long) CG_BITMAP_CONTEXT_GET_BYTES_PER_ROW.invokeExact(context);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** The context's pixels, readable for {@code byteSize} bytes while the context lives; NULL stays NULL. */
    static MemorySegment CGBitmapContextGetData(MemorySegment context, long byteSize) {
        try {
            return bounded((MemorySegment) CG_BITMAP_CONTEXT_GET_DATA.invokeExact(context), byteSize);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static long CGBitmapContextGetHeight(MemorySegment context) {
        try {
            return (long) CG_BITMAP_CONTEXT_GET_HEIGHT.invokeExact(context);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static long CGBitmapContextGetWidth(MemorySegment context) {
        try {
            return (long) CG_BITMAP_CONTEXT_GET_WIDTH.invokeExact(context);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CGColorSpaceCreateDeviceGray() {
        try {
            return (MemorySegment) CG_COLOR_SPACE_CREATE_DEVICE_GRAY.invokeExact();
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CGColorSpaceCreateDeviceRGB() {
        try {
            return (MemorySegment) CG_COLOR_SPACE_CREATE_DEVICE_RGB.invokeExact();
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** {@code rect} is a {@link #CGRECT} segment, passed by value. */
    static void CGContextFillRect(MemorySegment context, MemorySegment rect) {
        try {
            CG_CONTEXT_FILL_RECT.invokeExact(context, rect);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CGContextRelease(MemorySegment context) {
        try {
            CG_CONTEXT_RELEASE.invokeExact(context);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CGContextSetAllowsAntialiasing(MemorySegment context, boolean allows) {
        try {
            CG_CONTEXT_SET_ALLOWS_ANTIALIASING.invokeExact(context, allows);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CGContextSetAllowsFontSmoothing(MemorySegment context, boolean allows) {
        try {
            CG_CONTEXT_SET_ALLOWS_FONT_SMOOTHING.invokeExact(context, allows);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CGContextSetAllowsFontSubpixelPositioning(MemorySegment context, boolean allows) {
        try {
            CG_CONTEXT_SET_ALLOWS_FONT_SUBPIXEL_POSITIONING.invokeExact(context, allows);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CGContextSetAllowsFontSubpixelQuantization(MemorySegment context, boolean allows) {
        try {
            CG_CONTEXT_SET_ALLOWS_FONT_SUBPIXEL_QUANTIZATION.invokeExact(context, allows);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CGContextSetRGBFillColor(MemorySegment context, double red, double green, double blue, double alpha) {
        try {
            CG_CONTEXT_SET_RGB_FILL_COLOR.invokeExact(context, red, green, blue, alpha);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CGContextTranslateCTM(MemorySegment context, double tx, double ty) {
        try {
            CG_CONTEXT_TRANSLATE_CTM.invokeExact(context, tx, ty);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CGDataProviderCreateWithURL(MemorySegment url) {
        try {
            return (MemorySegment) CG_DATA_PROVIDER_CREATE_WITH_URL.invokeExact(url);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CGFontCreateWithDataProvider(MemorySegment provider) {
        try {
            return (MemorySegment) CG_FONT_CREATE_WITH_DATA_PROVIDER.invokeExact(provider);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** A {@link #CGRECT} segment from {@code allocator}. */
    static MemorySegment CGPathGetPathBoundingBox(SegmentAllocator allocator, MemorySegment path) {
        try {
            return (MemorySegment) CG_PATH_GET_PATH_BOUNDING_BOX.invokeExact(allocator, path);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CGPathRelease(MemorySegment path) {
        try {
            CG_PATH_RELEASE.invokeExact(path);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** {@code rect} and {@code transform} are passed by value; the result is a {@link #CGRECT} from allocator. */
    static MemorySegment CGRectApplyAffineTransform(SegmentAllocator allocator, MemorySegment rect,
                                                    MemorySegment transform) {
        try {
            return (MemorySegment) CG_RECT_APPLY_AFFINE_TRANSFORM.invokeExact(allocator, rect, transform);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }
}
