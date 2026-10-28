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

package com.sun.javafx.font.directwrite;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import static com.sun.javafx.font.directwrite.DWFontFaceCalls.encodeGlyphRun;
import static com.sun.javafx.font.directwrite.DWLayouts.D2D1_POINT_2F_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.offsetOf;
import static com.sun.javafx.font.directwrite.DWNative.HR_2A;
import static com.sun.javafx.font.directwrite.DWNative.HR_3A;
import static com.sun.javafx.font.directwrite.DWNative.U32_THIS;
import static com.sun.javafx.font.directwrite.DWNative.VOID_THIS_P;
import static com.sun.javafx.font.directwrite.DWNative.com;
import static com.sun.javafx.font.directwrite.DWNative.slot;
import static com.sun.javafx.font.directwrite.DWNative.unexpected;
import static com.sun.javafx.font.directwrite.DWNative.virtual;
import static com.sun.javafx.font.directwrite.DWWicCalls.D2D1_FACTORY_OPTIONS_LAYOUT;
import static com.sun.javafx.font.directwrite.DWWicCalls.WIC_BITMAP_GET_PIXEL_FORMAT;
import static com.sun.javafx.font.directwrite.DWWicCalls.WIC_BITMAP_GET_SIZE;
import static com.sun.javafx.font.directwrite.DWWicCalls.WIC_BITMAP_LOCK;
import static com.sun.javafx.font.directwrite.DWWicCalls.WIC_FACTORY_CREATE_BITMAP;
import static com.sun.javafx.font.directwrite.DWWicCalls.WIC_LOCK_GET_DATA_POINTER;
import static com.sun.javafx.font.directwrite.DWWicCalls.WIC_LOCK_GET_SIZE;
import static com.sun.javafx.font.directwrite.DWWicCalls.WIC_LOCK_GET_STRIDE;
import static com.sun.javafx.font.directwrite.DWWicCalls.WIC_RECT_LAYOUT;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The Direct2D render-target calls of the {@link DWNative} greyscale mask path:
 * {@code ID2D1Factory::CreateWicBitmapRenderTarget} and {@code ID2D1RenderTarget}. {@link DWWicCalls} has
 * the COM apartment, the factories and the WIC bitmap this path draws into.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWD2DCalls {

    /* ID2D1Factory : IUnknown, d2d1.h:3341. base 3 + ordinal. */
    private static final int D2D_FACTORY_CREATE_WIC_TARGET = 13;    // ord 10, d2d1.h:3423

    /*
     * ID2D1RenderTarget : ID2D1Resource, d2d1.h:2392. ID2D1Resource (d2d1.h:1082) declares exactly one
     * method, GetFactory (:1088), so the base is 4 - not 3. Every slot below is 4 + ordinal, and the
     * three accessors at the far end exist only to pin that arithmetic where it matters most.
     */
    private static final int RT_CREATE_SOLID_COLOR_BRUSH = 8;       // ord  4, d2d1.h:2439
    private static final int RT_DRAW_GLYPH_RUN = 29;                // ord 25, d2d1.h:2643
    private static final int RT_SET_TRANSFORM = 30;                 // ord 26, d2d1.h:2650
    private static final int RT_SET_TEXT_ANTIALIAS_MODE = 34;       // ord 30, d2d1.h:2665
    private static final int RT_CLEAR = 47;                         // ord 43, d2d1.h:2754
    private static final int RT_BEGIN_DRAW = 48;                    // ord 44, d2d1.h:2762
    private static final int RT_END_DRAW = 49;                      // ord 45, d2d1.h:2769
    private static final int RT_GET_PIXEL_FORMAT = 50;              // ord 46, d2d1.h:2774
    private static final int RT_GET_SIZE = 53;                      // ord 49, d2d1.h:2799
    private static final int RT_GET_PIXEL_SIZE = 54;                // ord 50, d2d1.h:2805
    private static final int RT_GET_MAXIMUM_BITMAP_SIZE = 55;       // ord 51, d2d1.h:2812

    /** {@code D2D1_COLOR_F} (d2d1.h:288 -> d2dbasetypes.h:24, D3DCOLORVALUE): four floats, 16 bytes. By pointer. */
    static final StructLayout D2D1_COLOR_F_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("r"),                 // offset 0
            JAVA_FLOAT.withName("g"),                 // offset 4
            JAVA_FLOAT.withName("b"),                 // offset 8
            JAVA_FLOAT.withName("a"));                // offset 12, byteSize 16

    /**
     * {@code D2D1_MATRIX_3X2_F} (d2d1.h:289 -> dcommon.h:283, {@code D2D_MATRIX_3X2_F}): six floats, 24 bytes. The
     * SDK declares it as a union of three views of the same 24 bytes; the first is
     * {@code m11, m12, m21, m22, dx, dy}, which is the order the Java mirror uses. By pointer.
     */
    static final StructLayout D2D1_MATRIX_3X2_F_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("m11"),               // offset 0
            JAVA_FLOAT.withName("m12"),               // offset 4
            JAVA_FLOAT.withName("m21"),               // offset 8
            JAVA_FLOAT.withName("m22"),               // offset 12
            JAVA_FLOAT.withName("dx"),                // offset 16
            JAVA_FLOAT.withName("dy"));               // offset 20, byteSize 24

    /** {@code D2D1_PIXEL_FORMAT} (dcommon.h:163): a DXGI_FORMAT and a D2D1_ALPHA_MODE, 8 bytes. */
    static final StructLayout D2D1_PIXEL_FORMAT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("format"),              // offset 0
            JAVA_INT.withName("alphaMode"));          // offset 4, byteSize 8

    /**
     * {@code D2D1_RENDER_TARGET_PROPERTIES} (d2d1.h:886): 28 bytes with the nested
     * {@code D2D1_PIXEL_FORMAT} flattened at 4 and 8. Everything is 4-byte aligned, so there is no
     * padding anywhere and the struct does not round up to 32.
     */
    static final StructLayout D2D1_RENDER_TARGET_PROPERTIES_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),                              // offset 0
            D2D1_PIXEL_FORMAT_LAYOUT.withName("pixelFormat"),       // offset 4  (format 4, alphaMode 8)
            JAVA_FLOAT.withName("dpiX"),                            // offset 12
            JAVA_FLOAT.withName("dpiY"),                            // offset 16
            JAVA_INT.withName("usage"),                             // offset 20
            JAVA_INT.withName("minLevel"));                         // offset 24, byteSize 28

    /**
     * {@code D2D1_SIZE_F} (dcommon.h:260, {@code D2D_SIZE_F}): two floats, 8 bytes. The shape of the
     * buffer {@code ID2D1RenderTarget::GetSize} (d2d1.h:2799) fills - see {@link #STRUCT_RETURN_THIS}
     * for why an aggregate a COM method declares as returned by value never travels in a register.
     */
    static final StructLayout D2D1_SIZE_F_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("width"),             // offset 0
            JAVA_FLOAT.withName("height"));           // offset 4, byteSize 8

    /** {@code D2D1_SIZE_U} (dcommon.h:272): two {@code UINT32}, 8 bytes; the {@code GetPixelSize} buffer. */
    static final StructLayout D2D1_SIZE_U_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("width"),               // offset 0
            JAVA_INT.withName("height"));             // offset 4, byteSize 8

    private static final long RTP_TYPE = offsetOf(D2D1_RENDER_TARGET_PROPERTIES_LAYOUT, "type");
    private static final long RTP_FORMAT =
            offsetOf(D2D1_RENDER_TARGET_PROPERTIES_LAYOUT, "pixelFormat", "format");
    private static final long RTP_ALPHA_MODE =
            offsetOf(D2D1_RENDER_TARGET_PROPERTIES_LAYOUT, "pixelFormat", "alphaMode");
    private static final long RTP_DPI_X = offsetOf(D2D1_RENDER_TARGET_PROPERTIES_LAYOUT, "dpiX");
    private static final long RTP_DPI_Y = offsetOf(D2D1_RENDER_TARGET_PROPERTIES_LAYOUT, "dpiY");
    private static final long RTP_USAGE = offsetOf(D2D1_RENDER_TARGET_PROPERTIES_LAYOUT, "usage");
    private static final long RTP_MIN_LEVEL = offsetOf(D2D1_RENDER_TARGET_PROPERTIES_LAYOUT, "minLevel");

    /* The vtable shapes only the render target uses; the ones it shares are in DWNative. */

    /** {@code void f(this)}: {@code ID2D1RenderTarget::BeginDraw} (d2d1.h:2762). */
    private static final MethodHandle VOID_THIS = virtual(FunctionDescriptor.ofVoid(ADDRESS));

    /** {@code void f(this, int)}: {@code SetTextAntialiasMode} (d2d1.h:2665). */
    private static final MethodHandle VOID_THIS_I = virtual(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    /**
     * {@code void f(this, D2D1_POINT_2F baselineOrigin, CONST DWRITE_GLYPH_RUN*, ID2D1Brush*,
     * DWRITE_MEASURING_MODE)}: {@code ID2D1RenderTarget::DrawGlyphRun} (d2d1.h:2643). The point is the
     * only aggregate this path passes <b>by value</b>; see {@link DWLayouts#D2D1_POINT_2F_LAYOUT} for why the
     * layout must appear in the descriptor rather than two {@code JAVA_FLOAT} parameters.
     */
    private static final MethodHandle VOID_DRAW_GLYPH_RUN = virtual(FunctionDescriptor.ofVoid(
            ADDRESS, D2D1_POINT_2F_LAYOUT, ADDRESS, ADDRESS, JAVA_INT));

    /**
     * {@code D2D1_SIZE_F f(this)} / {@code D2D1_SIZE_U f(this)} / {@code D2D1_PIXEL_FORMAT f(this)}:
     * the three aggregates {@code ID2D1RenderTarget} declares as returned <b>by value</b>
     * (d2d1.h:2774, :2799, :2805). Not one of the sixteen migrated natives needs them; they are bound
     * because they are the only behavioural pin available for the far end of a 53-method vtable, and
     * because they are the one place in this port where the struct-return convention is exercised at
     * all.
     * <p>
     * <b>They are not returned in a register, even at 8 bytes.</b> Modelling them as
     * {@code FunctionDescriptor.of(D2D1_SIZE_F_LAYOUT, ADDRESS)} - which the Microsoft x64 rule for
     * <em>free</em> functions would justify, since an aggregate of 1, 2, 4 or 8 bytes is a register
     * aggregate and FFM classifies it that way
     * ({@code x64/windows/TypeClass.isRegisterAggregate}) - faults inside {@code d2d1.dll}. The
     * crash names the convention exactly: the callee computes the right answer for the right object
     * (RCX held {@code 0x0000000100000057}, i.e. {@code DXGI_FORMAT_B8G8R8A8_UNORM} with
     * {@code D2D1_ALPHA_MODE_PREMULTIPLIED}, for the render target that was asked) and then stores
     * those 8 bytes through a pointer it took from RDX, which nothing had written.
     * <p>
     * So a COM method that returns an aggregate takes a caller-allocated buffer as a hidden argument
     * <em>after</em> {@code this}: {@code this} in RCX, the buffer in RDX, and the same pointer handed
     * back in RAX. That is the shape every non-MSVC binding of Direct2D uses, and it is why
     * {@code ID2D1RenderTarget::GetSize} is famously uncallable from MinGW. The one descriptor below
     * expresses it, and {@link #renderTargetStructReturnEchoesItsBuffer} checks the RAX half against
     * the object rather than against this comment.
     */
    private static final MethodHandle STRUCT_RETURN_THIS =
            virtual(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    private DWD2DCalls() {
    }

    /* ---------------------------------------------------------------------------------------------
     * ID2D1Factory / ID2D1RenderTarget - directwrite.cpp:2427-2502
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code ID2D1Factory::CreateWicBitmapRenderTarget}, slot 13 (d2d1.h:3423)
     * ({@code directwrite.cpp:2427-2437}): the 28-byte properties struct crosses by pointer, and a
     * {@code null} holder reaches Direct2D as {@code NULL} exactly as the C {@code if (arg2)} guard
     * left it.
     */
    static long createWicBitmapRenderTarget(long self, long target,
                                            D2D1_RENDER_TARGET_PROPERTIES properties) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment props = properties == null ? MemorySegment.NULL
                    : encodeRenderTargetProperties(scratch, properties);
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_3A.invokeExact(slot(obj, D2D_FACTORY_CREATE_WIC_TARGET), obj,
                    com(target), props, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code ID2D1RenderTarget::BeginDraw}, slot 48 (d2d1.h:2762) ({@code directwrite.cpp:2440-2444}). */
    static void beginDraw(long self) {
        MemorySegment obj = com(self);
        try {
            VOID_THIS.invokeExact(slot(obj, RT_BEGIN_DRAW), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code ID2D1RenderTarget::EndDraw}, slot 49 (d2d1.h:2769) ({@code directwrite.cpp:2446-2450}).
     * The C calls it through the C++ default arguments, so both {@code D2D1_TAG*} out-parameters are
     * {@code NULL}. {@code DWGlyph.java:203} compares the result with {@code S_OK}, not with
     * {@code SUCCEEDED}, and that comparison stays in {@code DWGlyph}.
     */
    static int endDraw(long self) {
        MemorySegment obj = com(self);
        try {
            return (int) HR_2A.invokeExact(slot(obj, RT_END_DRAW), obj, MemorySegment.NULL,
                    MemorySegment.NULL);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code ID2D1RenderTarget::Clear}, slot 47 (d2d1.h:2754) ({@code directwrite.cpp:2452-2458}).
     * A {@code null} colour still issues the call, with {@code NULL} - which is legal Direct2D and is
     * exactly what the C {@code if (arg1)} guard produced.
     */
    static void clear(long self, D2D1_COLOR_F clearColor) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment color = clearColor == null ? MemorySegment.NULL
                    : encodeColor(scratch, clearColor);
            MemorySegment obj = com(self);
            VOID_THIS_P.invokeExact(slot(obj, RT_CLEAR), obj, color);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code ID2D1RenderTarget::SetTextAntialiasMode}, slot 34 (d2d1.h:2665)
     * ({@code directwrite.cpp:2460-2464}).
     */
    static void setTextAntialiasMode(long self, int textAntialiasMode) {
        MemorySegment obj = com(self);
        try {
            VOID_THIS_I.invokeExact(slot(obj, RT_SET_TEXT_ANTIALIAS_MODE), obj, textAntialiasMode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code ID2D1RenderTarget::SetTransform}, slot 30 (d2d1.h:2650)
     * ({@code directwrite.cpp:2466-2472}). As with {@link #clear}, a {@code null} holder still issues
     * the call with {@code NULL}; no caller does it, and Direct2D does not document it as legal, but
     * reproducing the C means reproducing that too.
     */
    static void setTransform(long self, D2D1_MATRIX_3X2_F transform) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment matrix = transform == null ? MemorySegment.NULL
                    : encodeMatrix3x2(scratch, transform);
            MemorySegment obj = com(self);
            VOID_THIS_P.invokeExact(slot(obj, RT_SET_TRANSFORM), obj, matrix);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code ID2D1RenderTarget::DrawGlyphRun}, slot 29 (d2d1.h:2643)
     * ({@code directwrite.cpp:2474-2490}) - the one call in the package that passes an aggregate
     * <b>by value</b>. The 8-byte {@code D2D1_POINT_2F} travels in the integer register that argument
     * occupies (RDX here, since {@code this} takes RCX), never in an XMM register and never split.
     * <p>
     * The glyph run is rebuilt per call with {@code glyphCount} forced to 1 and three one-element
     * arrays, which the C frees the instant the call returns ({@code :2487-2489}) - proof that
     * Direct2D copies whatever it keeps, and what licenses the confined arena.
     * <p>
     * One deliberate divergence: when the Java {@code D2D1_POINT_2F} is {@code null} the C passed the
     * uninitialised stack struct, i.e. indeterminate bytes. Java passes zeroes. No caller passes
     * {@code null} ({@code DWGlyph.java:194}, {@code ID2D1RenderTarget.java:53}).
     */
    static void drawGlyphRun(long self, D2D1_POINT_2F baselineOrigin, DWRITE_GLYPH_RUN glyphRun,
                             long foregroundBrush, int measuringMode) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment origin = scratch.allocate(D2D1_POINT_2F_LAYOUT);
            if (baselineOrigin != null) {
                origin.setAtIndex(JAVA_FLOAT, 0, baselineOrigin.x);
                origin.setAtIndex(JAVA_FLOAT, 1, baselineOrigin.y);
            }
            MemorySegment run = glyphRun == null ? MemorySegment.NULL
                    : encodeGlyphRun(scratch, glyphRun);
            MemorySegment obj = com(self);
            VOID_DRAW_GLYPH_RUN.invokeExact(slot(obj, RT_DRAW_GLYPH_RUN), obj, origin, run,
                    com(foregroundBrush), measuringMode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code ID2D1RenderTarget::CreateSolidColorBrush}, slot 8 (d2d1.h:2439)
     * ({@code directwrite.cpp:2492-2502}).
     * <p>
     * <b>Three arguments, not two.</b> The pure virtual is
     * {@code CreateSolidColorBrush(CONST D2D1_COLOR_F *color, CONST D2D1_BRUSH_PROPERTIES
     * *brushProperties, ID2D1SolidColorBrush **solidColorBrush)}; the by-value call the C makes binds
     * the inline overload at d2d1.h:2922-2929, whose body supplies {@code NULL} for the middle
     * argument - the same trick as the two {@code EndDraw} tags. A two-pointer descriptor would put
     * the out-parameter in R8, where Direct2D reads {@code brushProperties}, and leave R9 - where it
     * writes the new brush - holding whatever the previous call left there.
     */
    static long createSolidColorBrush(long self, D2D1_COLOR_F color) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment rgba = color == null ? MemorySegment.NULL : encodeColor(scratch, color);
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_3A.invokeExact(slot(obj, RT_CREATE_SOLID_COLOR_BRUSH), obj, rgba,
                    MemorySegment.NULL, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment encodeColor(Arena arena, D2D1_COLOR_F holder) {
        MemorySegment color = arena.allocate(D2D1_COLOR_F_LAYOUT);
        color.setAtIndex(JAVA_FLOAT, 0, holder.r);
        color.setAtIndex(JAVA_FLOAT, 1, holder.g);
        color.setAtIndex(JAVA_FLOAT, 2, holder.b);
        color.setAtIndex(JAVA_FLOAT, 3, holder.a);
        return color;
    }

    /** The 24-byte {@code D2D1_MATRIX_3X2_F}: m11, m12, m21, m22, dx, dy. */
    private static MemorySegment encodeMatrix3x2(Arena arena, D2D1_MATRIX_3X2_F holder) {
        MemorySegment matrix = arena.allocate(D2D1_MATRIX_3X2_F_LAYOUT);
        matrix.setAtIndex(JAVA_FLOAT, 0, holder._11);
        matrix.setAtIndex(JAVA_FLOAT, 1, holder._12);
        matrix.setAtIndex(JAVA_FLOAT, 2, holder._21);
        matrix.setAtIndex(JAVA_FLOAT, 3, holder._22);
        matrix.setAtIndex(JAVA_FLOAT, 4, holder._31);
        matrix.setAtIndex(JAVA_FLOAT, 5, holder._32);
        return matrix;
    }

    /** The 28-byte {@code D2D1_RENDER_TARGET_PROPERTIES}, with its nested pixel format flattened. */
    private static MemorySegment encodeRenderTargetProperties(Arena arena,
                                                              D2D1_RENDER_TARGET_PROPERTIES holder) {
        MemorySegment props = arena.allocate(D2D1_RENDER_TARGET_PROPERTIES_LAYOUT);
        props.set(JAVA_INT, RTP_TYPE, holder.type);
        props.set(JAVA_INT, RTP_FORMAT, holder.pixelFormat.format);
        props.set(JAVA_INT, RTP_ALPHA_MODE, holder.pixelFormat.alphaMode);
        props.set(JAVA_FLOAT, RTP_DPI_X, holder.dpiX);
        props.set(JAVA_FLOAT, RTP_DPI_Y, holder.dpiY);
        props.set(JAVA_INT, RTP_USAGE, holder.usage);
        props.set(JAVA_INT, RTP_MIN_LEVEL, holder.minLevel);
        return props;
    }

    /* ---------------------------------------------------------------------------------------------
     * Calls that were never JNI natives: the behavioural pins for the slot arithmetic
     *
     * ID2D1RenderTarget has 53 methods of its own and the mask path reaches six of them, the furthest at
     * slot 49. A slot table can only be proved by asking objects questions whose answers are known
     * independently, so the seven accessors below are bound too - each answers something the test
     * already knows (the bitmap is 256 x 256, its pixel format is the GUID that created it) and each
     * sits next to a slot that matters. They have no caller in main code.
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code ID2D1RenderTarget::GetSize}, slot 53 (d2d1.h:2799), as {@code {width, height}} in DIPs.
     * The {@code D2D1_SIZE_F} arrives through the hidden buffer described on
     * {@link #STRUCT_RETURN_THIS}, not in a register.
     */
    static float[] renderTargetSize(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(D2D1_SIZE_F_LAYOUT);
            MemorySegment obj = com(self);
            MemorySegment ignored = (MemorySegment) STRUCT_RETURN_THIS.invokeExact(
                    slot(obj, RT_GET_SIZE), obj, out);
            return new float[] { out.getAtIndex(JAVA_FLOAT, 0), out.getAtIndex(JAVA_FLOAT, 1) };
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code ID2D1RenderTarget::GetPixelSize}, slot 54 (d2d1.h:2805): two {@code UINT32} in the buffer. */
    static int[] renderTargetPixelSize(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(D2D1_SIZE_U_LAYOUT);
            MemorySegment obj = com(self);
            MemorySegment ignored = (MemorySegment) STRUCT_RETURN_THIS.invokeExact(
                    slot(obj, RT_GET_PIXEL_SIZE), obj, out);
            return new int[] { out.getAtIndex(JAVA_INT, 0), out.getAtIndex(JAVA_INT, 1) };
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code ID2D1RenderTarget::GetPixelFormat}, slot 50 (d2d1.h:2774): {@code {DXGI_FORMAT, alphaMode}}. */
    static int[] renderTargetPixelFormat(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(D2D1_PIXEL_FORMAT_LAYOUT);
            MemorySegment obj = com(self);
            MemorySegment ignored = (MemorySegment) STRUCT_RETURN_THIS.invokeExact(
                    slot(obj, RT_GET_PIXEL_FORMAT), obj, out);
            return new int[] { out.getAtIndex(JAVA_INT, 0), out.getAtIndex(JAVA_INT, 1) };
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The second half of the struct-return convention, measured rather than assumed: the callee hands
     * the caller-allocated buffer back in RAX. {@code true} when the pointer this facade passed as the
     * hidden argument is the pointer that came back.
     */
    static boolean renderTargetStructReturnEchoesItsBuffer(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(D2D1_SIZE_U_LAYOUT);
            MemorySegment obj = com(self);
            MemorySegment echoed = (MemorySegment) STRUCT_RETURN_THIS.invokeExact(
                    slot(obj, RT_GET_PIXEL_SIZE), obj, out);
            return echoed.address() == out.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code ID2D1RenderTarget::GetMaximumBitmapSize}, slot 55 (d2d1.h:2812): a plain {@code UINT32}. */
    static int renderTargetMaximumBitmapSize(long self) {
        MemorySegment obj = com(self);
        try {
            return (int) U32_THIS.invokeExact(slot(obj, RT_GET_MAXIMUM_BITMAP_SIZE), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The byte size of a named Direct2D/WIC layout, so a test can pin it without seeing a {@code MemoryLayout}. */
    static long layoutByteSize(String name) {
        return layout(name).byteSize();
    }

    static long layoutOffset(String name, String field) {
        return layout(name).byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    private static StructLayout layout(String name) {
        return switch (name) {
            case "WICRect" -> WIC_RECT_LAYOUT;
            case "D2D1_COLOR_F" -> D2D1_COLOR_F_LAYOUT;
            case "D2D1_MATRIX_3X2_F" -> D2D1_MATRIX_3X2_F_LAYOUT;
            case "D2D1_PIXEL_FORMAT" -> D2D1_PIXEL_FORMAT_LAYOUT;
            case "D2D1_RENDER_TARGET_PROPERTIES" -> D2D1_RENDER_TARGET_PROPERTIES_LAYOUT;
            case "D2D1_FACTORY_OPTIONS" -> D2D1_FACTORY_OPTIONS_LAYOUT;
            case "D2D1_SIZE_F" -> D2D1_SIZE_F_LAYOUT;
            case "D2D1_SIZE_U" -> D2D1_SIZE_U_LAYOUT;
            default -> throw new IllegalArgumentException("no such layout: " + name);
        };
    }

    /** The vtable slot a named Direct2D/WIC method occupies, so the table is testable as data. */
    static int renderSlot(String name) {
        return switch (name) {
            case "IWICImagingFactory::CreateBitmap" -> WIC_FACTORY_CREATE_BITMAP;
            case "IWICBitmapSource::GetSize" -> WIC_BITMAP_GET_SIZE;
            case "IWICBitmapSource::GetPixelFormat" -> WIC_BITMAP_GET_PIXEL_FORMAT;
            case "IWICBitmap::Lock" -> WIC_BITMAP_LOCK;
            case "IWICBitmapLock::GetSize" -> WIC_LOCK_GET_SIZE;
            case "IWICBitmapLock::GetStride" -> WIC_LOCK_GET_STRIDE;
            case "IWICBitmapLock::GetDataPointer" -> WIC_LOCK_GET_DATA_POINTER;
            case "ID2D1Factory::CreateWicBitmapRenderTarget" -> D2D_FACTORY_CREATE_WIC_TARGET;
            case "ID2D1RenderTarget::CreateSolidColorBrush" -> RT_CREATE_SOLID_COLOR_BRUSH;
            case "ID2D1RenderTarget::DrawGlyphRun" -> RT_DRAW_GLYPH_RUN;
            case "ID2D1RenderTarget::SetTransform" -> RT_SET_TRANSFORM;
            case "ID2D1RenderTarget::SetTextAntialiasMode" -> RT_SET_TEXT_ANTIALIAS_MODE;
            case "ID2D1RenderTarget::Clear" -> RT_CLEAR;
            case "ID2D1RenderTarget::BeginDraw" -> RT_BEGIN_DRAW;
            case "ID2D1RenderTarget::EndDraw" -> RT_END_DRAW;
            case "ID2D1RenderTarget::GetPixelFormat" -> RT_GET_PIXEL_FORMAT;
            case "ID2D1RenderTarget::GetSize" -> RT_GET_SIZE;
            case "ID2D1RenderTarget::GetPixelSize" -> RT_GET_PIXEL_SIZE;
            case "ID2D1RenderTarget::GetMaximumBitmapSize" -> RT_GET_MAXIMUM_BITMAP_SIZE;
            default -> throw new IllegalArgumentException("no such slot: " + name);
        };
    }
}
