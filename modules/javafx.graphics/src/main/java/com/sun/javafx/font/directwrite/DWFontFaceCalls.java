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
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_FONT_METRICS_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_GLYPH_METRICS_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_GLYPH_OFFSET_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_GLYPH_RUN_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_MATRIX_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.RECT_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_BIDI_LEVEL;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_FONT_EM_SIZE;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_FONT_FACE;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_ADVANCES;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_COUNT;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_INDICES;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_OFFSETS;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_IS_SIDEWAYS;
import static com.sun.javafx.font.directwrite.DWNative.HR_I_OUT;
import static com.sun.javafx.font.directwrite.DWNative.VOID_THIS_P;
import static com.sun.javafx.font.directwrite.DWNative.bounded;
import static com.sun.javafx.font.directwrite.DWNative.com;
import static com.sun.javafx.font.directwrite.DWNative.slot;
import static com.sun.javafx.font.directwrite.DWNative.u32;
import static com.sun.javafx.font.directwrite.DWNative.unexpected;
import static com.sun.javafx.font.directwrite.DWNative.virtual;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The {@link DWNative} calls on {@code IDWriteFontFace} (all but {@code GetGlyphRunOutline}, which drives
 * {@link DWGeometrySinkObject}), on {@code IDWriteFactory::CreateGlyphRunAnalysis} and on
 * {@code IDWriteGlyphRunAnalysis}, with the encoders of the structs those calls take.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWFontFaceCalls {

    /* IDWriteFontFace : IUnknown, dwrite.h:1047. base 3 + ordinal. */
    private static final int FONT_FACE_GET_TYPE = 3;                   // ord 0, dwrite.h:1052
    private static final int FONT_FACE_GET_INDEX = 5;                  // ord 2, dwrite.h:1074
    private static final int FONT_FACE_GET_SIMULATIONS = 6;            // ord 3, dwrite.h:1079
    private static final int FONT_FACE_IS_SYMBOL_FONT = 7;             // ord 4, dwrite.h:1084
    private static final int FONT_FACE_GET_METRICS = 8;                // ord 5, dwrite.h:1092
    private static final int FONT_FACE_GET_GLYPH_COUNT = 9;            // ord 6, dwrite.h:1099
    private static final int FONT_FACE_GET_DESIGN_GLYPH_METRICS = 10;  // ord 7, dwrite.h:1115
    private static final int FONT_FACE_GET_GLYPH_INDICES = 11;         // ord 8, dwrite.h:1136
    private static final int FONT_FACE_TRY_GET_FONT_TABLE = 12;        // ord 9, dwrite.h:1176
    private static final int FONT_FACE_RELEASE_FONT_TABLE = 13;        // ord 10, dwrite.h:1188
    static final int FONT_FACE_GET_GLYPH_RUN_OUTLINE = 14;             // ord 11, dwrite.h:1209

    /* IDWriteGlyphRunAnalysis : IUnknown, dwrite.h:4646. base 3 + ordinal. */
    private static final int ANALYSIS_GET_ALPHA_TEXTURE_BOUNDS = 3;    // ord 0, dwrite.h:4659
    private static final int ANALYSIS_CREATE_ALPHA_TEXTURE = 4;        // ord 1, dwrite.h:4677

    /* IDWriteFactory, dwrite.h:4704, continued. */
    private static final int FACTORY_CREATE_GLYPH_RUN_ANALYSIS = 23;   // ord 20, dwrite.h:5090

    /**
     * {@code HRESULT f(this, UINT16 const* glyphIndices, UINT32 glyphCount,
     * DWRITE_GLYPH_METRICS* out, BOOL isSideways)}: {@code IDWriteFontFace::GetDesignGlyphMetrics}.
     * The metrics come back through the fourth parameter, not as a return value.
     */
    private static final MethodHandle HR_DESIGN_GLYPH_METRICS = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    /**
     * {@code HRESULT f(this, UINT32 const* codePoints, UINT32 count, UINT16* out)}:
     * {@code IDWriteFontFace::GetGlyphIndices}. Distinct from {@link DWFontCalls#HR_I_OUT_BOOL}, whose scalar
     * comes first.
     */
    private static final MethodHandle HR_P_I_OUT = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    /**
     * {@code UINT16 f(this)}: {@code IDWriteFontFace::GetGlyphCount} (dwrite.h:1099). Sixteen bits
     * wide, returned in AX with the rest of RAX undefined, so the descriptor must say
     * {@code JAVA_SHORT} and the caller must zero-extend.
     */
    private static final MethodHandle U16_THIS = virtual(FunctionDescriptor.of(JAVA_SHORT, ADDRESS));

    /**
     * {@code HRESULT f(this, UINT32 tag, const void** tableData, UINT32* tableSize, void** context,
     * BOOL* exists)}: {@code IDWriteFontFace::TryGetFontTable} (dwrite.h:1176), five out-parameters
     * after the tag - the last two land on the stack at {@code [RSP+0x20]} and {@code [RSP+0x28]}.
     */
    private static final MethodHandle HR_FONT_TABLE = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code HRESULT f(this, DWRITE_GLYPH_RUN const*, FLOAT pixelsPerDip, DWRITE_MATRIX const*,
     * DWRITE_RENDERING_MODE, DWRITE_MEASURING_MODE, FLOAT baselineOriginX, FLOAT baselineOriginY,
     * IDWriteGlyphRunAnalysis** out)}: {@code IDWriteFactory::CreateGlyphRunAnalysis} (dwrite.h:5090).
     * Both structs cross as pointers; neither is passed by value.
     */
    private static final MethodHandle HR_GLYPH_RUN_ANALYSIS = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_FLOAT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_FLOAT,
            JAVA_FLOAT, ADDRESS));

    /**
     * {@code HRESULT f(this, DWRITE_TEXTURE_TYPE, RECT const*, BYTE* buffer, UINT32 bufferSize)}:
     * {@code IDWriteGlyphRunAnalysis::CreateAlphaTexture} (dwrite.h:4677).
     * {@code GetAlphaTextureBounds} (dwrite.h:4659) has the shape of {@link DWNative#HR_I_OUT} and reuses it.
     */
    private static final MethodHandle HR_ALPHA_TEXTURE = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    private DWFontFaceCalls() {
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFontFace - dwrite.h:1047
     *
     * Slots 3..14 of one interface, of which only 10 (GetDesignGlyphMetrics) and 14
     * (GetGlyphRunOutline) were ever JNI natives. The other six are bound because they are the only
     * way to prove the slot arithmetic around those two <em>behaviourally</em>: each answers a value
     * that is knowable from the font file without asking DirectWrite (the face index, the glyph count
     * in maxp, the units per em in head, the cmap mapping, a whole table byte for byte), so an
     * off-by-one anywhere in 3..14 fails a test instead of silently returning a different method of
     * the same object. They cost about fifteen lines each and no peer calls them.
     * ------------------------------------------------------------------------------------------- */

    /** {@code IDWriteFontFace::GetType}, slot 3 (dwrite.h:1052): a {@code DWRITE_FONT_FACE_TYPE}. */
    static int getFontFaceType(long self) {
        return u32(self, FONT_FACE_GET_TYPE);
    }

    /** {@code IDWriteFontFace::GetIndex}, slot 5 (dwrite.h:1074): the face index within its files. */
    static int getFontFaceIndex(long self) {
        return u32(self, FONT_FACE_GET_INDEX);
    }

    /** {@code IDWriteFontFace::GetSimulations}, slot 6 (dwrite.h:1079): {@code DWRITE_FONT_SIMULATIONS}. */
    static int getFontFaceSimulations(long self) {
        return u32(self, FONT_FACE_GET_SIMULATIONS);
    }

    /** {@code IDWriteFontFace::IsSymbolFont}, slot 7 (dwrite.h:1084): a 4-byte {@code BOOL} in EAX. */
    static boolean isSymbolFont(long self) {
        return u32(self, FONT_FACE_IS_SYMBOL_FONT) != 0;
    }

    /**
     * {@code IDWriteFontFace::GetMetrics}, slot 8 (dwrite.h:1092): the ten fields of
     * {@code DWRITE_FONT_METRICS} in declaration order, sign-extended for the three {@code INT16}
     * fields and zero-extended for the seven {@code UINT16} ones.
     * <p>
     * This method returns {@code void} and writes through an out-pointer; nothing here is returned by
     * value. That matters because Microsoft x64 returns an aggregate that is not 1, 2, 4 or 8 bytes
     * through a hidden pointer supplied by the caller, and this package never has to know whether that
     * hidden pointer precedes or follows {@code this} - no method it binds returns an aggregate at all.
     */
    static int[] getFontFaceMetrics(long self) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(DWRITE_FONT_METRICS_LAYOUT);
            MemorySegment obj = com(self);
            VOID_THIS_P.invokeExact(slot(obj, FONT_FACE_GET_METRICS), obj, out);
            int[] metrics = new int[10];
            for (int i = 0; i < metrics.length; i++) {
                short raw = out.getAtIndex(JAVA_SHORT, i);
                metrics[i] = SIGNED_FONT_METRIC[i] ? raw : Short.toUnsignedInt(raw);
            }
            return metrics;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Which fields of {@code DWRITE_FONT_METRICS} are {@code INT16}: lineGap, underline, strikethrough. */
    private static final boolean[] SIGNED_FONT_METRIC =
            { false, false, false, true, false, false, true, false, true, false };

    /**
     * {@code IDWriteFontFace::GetGlyphCount}, slot 9 (dwrite.h:1099). The value is a {@code UINT16}
     * returned in AX, so it is bound as {@code JAVA_SHORT} and widened here; a font with more than
     * 32,768 glyphs would otherwise come back negative.
     */
    static int getGlyphCount(long self) {
        MemorySegment obj = com(self);
        try {
            return Short.toUnsignedInt((short) U16_THIS.invokeExact(slot(obj, FONT_FACE_GET_GLYPH_COUNT), obj));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteFontFace::GetDesignGlyphMetrics}, slot 10 (dwrite.h:1115), for the one glyph the
     * JNI native took ({@code directwrite.cpp:1814-1828}): {@code null} on a failing HRESULT, exactly
     * as there, because {@code DWFontFile.createGlyphBoundingBox} and {@code DWGlyph.checkMetrics}
     * both branch on null.
     * <p>
     * The glyph index crosses as a raw {@code JAVA_SHORT}, never widened: {@code DWGlyph} keeps glyph
     * codes in a {@code short} and a CJK font with more than 32,768 glyphs makes it negative, which a
     * {@code JAVA_INT} store would sign-extend into a nonsense {@code UINT16}. The metrics come back
     * through a 28-byte out-parameter, not as a returned struct.
     */
    static DWRITE_GLYPH_METRICS getDesignGlyphMetrics(long self, short glyphIndex, boolean isSideways) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment indices = scratch.allocateFrom(JAVA_SHORT, glyphIndex);
            MemorySegment out = scratch.allocate(DWRITE_GLYPH_METRICS_LAYOUT);
            MemorySegment obj = com(self);
            int hr = (int) HR_DESIGN_GLYPH_METRICS.invokeExact(
                    slot(obj, FONT_FACE_GET_DESIGN_GLYPH_METRICS), obj, indices, 1, out,
                    isSideways ? 1 : 0);
            if (hr < 0) {
                return null;
            }
            DWRITE_GLYPH_METRICS metrics = new DWRITE_GLYPH_METRICS();
            metrics.leftSideBearing = out.getAtIndex(JAVA_INT, 0);
            metrics.advanceWidth = out.getAtIndex(JAVA_INT, 1);
            metrics.rightSideBearing = out.getAtIndex(JAVA_INT, 2);
            metrics.topSideBearing = out.getAtIndex(JAVA_INT, 3);
            metrics.advanceHeight = out.getAtIndex(JAVA_INT, 4);
            metrics.bottomSideBearing = out.getAtIndex(JAVA_INT, 5);
            metrics.verticalOriginY = out.getAtIndex(JAVA_INT, 6);
            return metrics;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteFontFace::GetGlyphIndices}, slot 11 (dwrite.h:1136): the nominal cmap mapping of
     * UTF-32 code points to glyph indices, or {@code null} on a failing HRESULT. Missing code points
     * come back as glyph 0, which is what the cmap says and what {@code OpenTypeGlyphMapper} answers.
     * <p>
     * Both arrays cross off-heap. {@code critical(true)} is not an option: a cmap lookup can fault the
     * font file in through the DirectWrite font cache service, and a pinned Java array across a call
     * that can block is exactly what the option forbids.
     */
    static short[] getGlyphIndices(long self, int[] codePoints) {
        if (codePoints.length == 0) {
            return new short[0];
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment in = scratch.allocateFrom(JAVA_INT, codePoints);
            MemorySegment out = scratch.allocate(JAVA_SHORT, codePoints.length);
            MemorySegment obj = com(self);
            int hr = (int) HR_P_I_OUT.invokeExact(slot(obj, FONT_FACE_GET_GLYPH_INDICES), obj, in,
                    codePoints.length, out);
            return hr >= 0 ? out.toArray(JAVA_SHORT) : null;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteFontFace::TryGetFontTable}, slot 12 (dwrite.h:1176), copied into a Java array and
     * released again through {@code ReleaseFontTable}, slot 13 (dwrite.h:1188): the raw sfnt table
     * bytes, or {@code null} when the face does not carry that table.
     * <p>
     * The pointer DirectWrite hands back addresses its own mapping of the font file and is valid only
     * until {@code ReleaseFontTable}, so it is copied at once and never retained. The {@code finally}
     * makes the release unconditional, which the documented contract requires whenever {@code exists}
     * came back TRUE.
     *
     * @param openTypeTableTag the tag as {@code DWRITE_MAKE_OPENTYPE_TAG} builds it - four bytes in
     *                         file order packed little-endian, so "cmap" is {@code 0x70616D63}
     */
    static byte[] tryGetFontTable(long self, int openTypeTableTag) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment data = scratch.allocate(ADDRESS);
            MemorySegment size = scratch.allocate(JAVA_INT);
            MemorySegment context = scratch.allocate(ADDRESS);
            MemorySegment exists = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_FONT_TABLE.invokeExact(slot(obj, FONT_FACE_TRY_GET_FONT_TABLE), obj,
                    openTypeTableTag, data, size, context, exists);
            if (hr < 0 || exists.get(JAVA_INT, 0) == 0) {
                return null;
            }
            MemorySegment table = data.get(ADDRESS, 0);
            MemorySegment tableContext = context.get(ADDRESS, 0);
            try {
                return bounded(table, Integer.toUnsignedLong(size.get(JAVA_INT, 0))).toArray(JAVA_BYTE);
            } finally {
                VOID_THIS_P.invokeExact(slot(obj, FONT_FACE_RELEASE_FONT_TABLE), obj, tableContext);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFactory::CreateGlyphRunAnalysis and IDWriteGlyphRunAnalysis - dwrite.h:5090, :4646
     * ------------------------------------------------------------------------------------------- */

    /** {@code dwrite.h}: {@code DWRITE_TEXTURE_CLEARTYPE_3x1}, the one texture type that is 3 bytes deep. */
    static final int DWRITE_TEXTURE_CLEARTYPE_3x1 = 1;

    /** {@code UINT32_MAX}, as the unsigned comparisons of {@code directwrite.cpp:2182-2185} use it. */
    private static final long UINT32_MAX = 0xFFFFFFFFL;

    /**
     * {@code IDWriteFactory::CreateGlyphRunAnalysis}, slot 23 (dwrite.h:5090)
     * ({@code directwrite.cpp:1926-1955}). Both structs cross as pointers; neither is passed by value,
     * so no aggregate-argument rule applies here beyond "the layout must be right", which the LCD mask
     * bytes prove end to end.
     * <p>
     * Two quirks of the C are kept. A {@code null} {@code DWRITE_GLYPH_RUN} or {@code DWRITE_MATRIX}
     * reaches DirectWrite as {@code NULL} rather than as a zeroed struct ({@code :1939-1940}) -
     * {@code DWGlyph.createAnalysis} really does pass a null {@code strike.matrix} - and the run always
     * describes exactly one glyph, with three one-element arrays ({@code :1933-1936}), which is what
     * the single-valued Java mirror {@code DWRITE_GLYPH_RUN} was built for.
     */
    static long createGlyphRunAnalysis(long self, DWRITE_GLYPH_RUN glyphRun, float pixelsPerDip,
                                       DWRITE_MATRIX transform, int renderingMode, int measuringMode,
                                       float baselineOriginX, float baselineOriginY) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment run = glyphRun == null ? MemorySegment.NULL : encodeGlyphRun(scratch, glyphRun);
            MemorySegment matrix = transform == null ? MemorySegment.NULL : encodeMatrix(scratch, transform);
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_GLYPH_RUN_ANALYSIS.invokeExact(
                    slot(obj, FACTORY_CREATE_GLYPH_RUN_ANALYSIS), obj, run, pixelsPerDip, matrix,
                    renderingMode, measuringMode, baselineOriginX, baselineOriginY, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteGlyphRunAnalysis::GetAlphaTextureBounds}, slot 3 (dwrite.h:4659)
     * ({@code directwrite.cpp:2200-2210}): {@code null} on a failing HRESULT, as there. The
     * {@code RECT} arrives through a 16-byte out-parameter; the caller ({@code DWGlyph.checkBounds})
     * then mutates the returned object, so it stays the plain Java holder it has always been.
     */
    static RECT getAlphaTextureBounds(long self, int textureType) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(RECT_LAYOUT);
            MemorySegment obj = com(self);
            int hr = (int) HR_I_OUT.invokeExact(slot(obj, ANALYSIS_GET_ALPHA_TEXTURE_BOUNDS), obj,
                    textureType, out);
            if (hr < 0) {
                return null;
            }
            RECT rect = new RECT();
            rect.left = out.getAtIndex(JAVA_INT, 0);
            rect.top = out.getAtIndex(JAVA_INT, 1);
            rect.right = out.getAtIndex(JAVA_INT, 2);
            rect.bottom = out.getAtIndex(JAVA_INT, 3);
            return rect;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteGlyphRunAnalysis::CreateAlphaTexture}, slot 4 (dwrite.h:4677)
     * ({@code directwrite.cpp:2171-2198}). The guard ladder of the C is reproduced in its exact order,
     * and its arithmetic is unsigned 32-bit: a null {@code RECT} gives {@code null}; an empty or
     * inverted rectangle in either axis gives {@code null}; the depth is 3 for
     * {@code CLEARTYPE_3x1} and 1 otherwise; and the two overflow guards reject a buffer whose size
     * would not fit a {@code UINT32}.
     * <p>
     * One deliberate divergence, which cannot be made bug-compatible: the C hands the result of
     * {@code new (std::nothrow) BYTE[]} to DirectWrite without a null check ({@code :2188-2189}), so an
     * allocation failure dereferences null. Here the arena raises {@code OutOfMemoryError} instead. The
     * two paths also fail differently above 2 GB, which no glyph mask can reach.
     */
    static byte[] createAlphaTexture(long self, int textureType, RECT textureBounds) {
        if (textureBounds == null) {
            return null;
        }
        if (textureBounds.right <= textureBounds.left) {
            return null;
        }
        if (textureBounds.bottom <= textureBounds.top) {
            return null;
        }
        long width = Integer.toUnsignedLong(textureBounds.right - textureBounds.left);
        long height = Integer.toUnsignedLong(textureBounds.bottom - textureBounds.top);
        long bpp = textureType == DWRITE_TEXTURE_CLEARTYPE_3x1 ? 3 : 1;
        if (height > UINT32_MAX / bpp) {
            return null;
        }
        if (height > 0 && width > UINT32_MAX / (height * bpp)) {
            return null;
        }
        long bufferSize = width * height * bpp;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment bounds = encodeRect(scratch, textureBounds);
            MemorySegment buffer = scratch.allocate(bufferSize);
            MemorySegment obj = com(self);
            int hr = (int) HR_ALPHA_TEXTURE.invokeExact(slot(obj, ANALYSIS_CREATE_ALPHA_TEXTURE), obj,
                    textureType, bounds, buffer, (int) bufferSize);
            return hr >= 0 ? buffer.toArray(JAVA_BYTE) : null;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The 48-byte {@code DWRITE_GLYPH_RUN} plus the three one-element arrays it points at. */
    static MemorySegment encodeGlyphRun(Arena arena, DWRITE_GLYPH_RUN holder) {
        MemorySegment indices = arena.allocateFrom(JAVA_SHORT, holder.glyphIndices);
        MemorySegment advances = arena.allocateFrom(JAVA_FLOAT, holder.glyphAdvances);
        MemorySegment offsets = arena.allocate(DWRITE_GLYPH_OFFSET_LAYOUT);
        offsets.setAtIndex(JAVA_FLOAT, 0, holder.advanceOffset);
        offsets.setAtIndex(JAVA_FLOAT, 1, holder.ascenderOffset);
        MemorySegment run = arena.allocate(DWRITE_GLYPH_RUN_LAYOUT);
        run.set(ADDRESS, RUN_FONT_FACE, com(holder.fontFace));
        run.set(JAVA_FLOAT, RUN_FONT_EM_SIZE, holder.fontEmSize);
        run.set(JAVA_INT, RUN_GLYPH_COUNT, 1);
        run.set(ADDRESS, RUN_GLYPH_INDICES, indices);
        run.set(ADDRESS, RUN_GLYPH_ADVANCES, advances);
        run.set(ADDRESS, RUN_GLYPH_OFFSETS, offsets);
        run.set(JAVA_INT, RUN_IS_SIDEWAYS, holder.isSideways ? 1 : 0);
        run.set(JAVA_INT, RUN_BIDI_LEVEL, holder.bidiLevel);
        return run;
    }

    private static MemorySegment encodeMatrix(Arena arena, DWRITE_MATRIX holder) {
        MemorySegment matrix = arena.allocate(DWRITE_MATRIX_LAYOUT);
        matrix.setAtIndex(JAVA_FLOAT, 0, holder.m11);
        matrix.setAtIndex(JAVA_FLOAT, 1, holder.m12);
        matrix.setAtIndex(JAVA_FLOAT, 2, holder.m21);
        matrix.setAtIndex(JAVA_FLOAT, 3, holder.m22);
        matrix.setAtIndex(JAVA_FLOAT, 4, holder.dx);
        matrix.setAtIndex(JAVA_FLOAT, 5, holder.dy);
        return matrix;
    }

    private static MemorySegment encodeRect(Arena arena, RECT holder) {
        MemorySegment rect = arena.allocate(RECT_LAYOUT);
        rect.setAtIndex(JAVA_INT, 0, holder.left);
        rect.setAtIndex(JAVA_INT, 1, holder.top);
        rect.setAtIndex(JAVA_INT, 2, holder.right);
        rect.setAtIndex(JAVA_INT, 3, holder.bottom);
        return rect;
    }
}
