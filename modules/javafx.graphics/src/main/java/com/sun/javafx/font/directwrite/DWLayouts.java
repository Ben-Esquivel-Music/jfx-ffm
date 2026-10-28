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

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The {@code dwrite.h} and {@code d2d1.h} structs that the {@link DWNative} aggregates marshal, and the
 * offsets read out of them. Its initializer touches no other class of the package, so every initializer
 * may use it without risking a class-initialization cycle. The Direct2D and WIC structs of the mask path
 * stay beside the calls that use them, in {@link DWWicCalls} and {@link DWD2DCalls}.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWLayouts {

    /**
     * {@code DWRITE_GLYPH_METRICS} (dwrite.h:551): seven 32-bit fields, 28 bytes, 4-byte aligned.
     * {@code advanceWidth} and {@code advanceHeight} are {@code UINT32}, the other five {@code INT32};
     * the Java mirror {@code DWRITE_GLYPH_METRICS} holds all seven as {@code int}, which is what the
     * JNI did. Crosses as an out-pointer, never by value.
     */
    static final StructLayout DWRITE_GLYPH_METRICS_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("leftSideBearing"),     // offset 0   INT32
            JAVA_INT.withName("advanceWidth"),        // offset 4   UINT32
            JAVA_INT.withName("rightSideBearing"),    // offset 8   INT32
            JAVA_INT.withName("topSideBearing"),      // offset 12  INT32
            JAVA_INT.withName("advanceHeight"),       // offset 16  UINT32
            JAVA_INT.withName("bottomSideBearing"),   // offset 20  INT32
            JAVA_INT.withName("verticalOriginY"));    // offset 24  INT32, byteSize 28

    /**
     * {@code DWRITE_FONT_METRICS} (dwrite.h:476): ten 16-bit fields, 20 bytes, 2-byte aligned.
     * {@code lineGap}, {@code underlinePosition} and {@code strikethroughPosition} are {@code INT16},
     * the other seven {@code UINT16}. {@code IDWriteFontFace::GetMetrics} (dwrite.h:1092) is declared
     * {@code STDMETHOD_(void, GetMetrics)(DWRITE_FONT_METRICS*)}, so this struct is written through an
     * out-pointer and is <em>not</em> returned by value - the one method in this package whose name
     * invites that assumption.
     */
    static final StructLayout DWRITE_FONT_METRICS_LAYOUT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("designUnitsPerEm"),        // offset 0   UINT16
            JAVA_SHORT.withName("ascent"),                  // offset 2   UINT16
            JAVA_SHORT.withName("descent"),                 // offset 4   UINT16
            JAVA_SHORT.withName("lineGap"),                 // offset 6   INT16
            JAVA_SHORT.withName("capHeight"),               // offset 8   UINT16
            JAVA_SHORT.withName("xHeight"),                 // offset 10  UINT16
            JAVA_SHORT.withName("underlinePosition"),       // offset 12  INT16
            JAVA_SHORT.withName("underlineThickness"),      // offset 14  UINT16
            JAVA_SHORT.withName("strikethroughPosition"),   // offset 16  INT16
            JAVA_SHORT.withName("strikethroughThickness")); // offset 18  UINT16, byteSize 20

    /** {@code DWRITE_GLYPH_OFFSET} (dwrite.h:604): two floats, 8 bytes, 4-byte aligned. */
    static final StructLayout DWRITE_GLYPH_OFFSET_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("advanceOffset"),     // offset 0
            JAVA_FLOAT.withName("ascenderOffset"));   // offset 4, byteSize 8

    /**
     * {@code DWRITE_GLYPH_RUN} (dwrite.h:3038): 48 bytes, 8-byte aligned. No explicit padding is
     * needed - {@code fontEmSize} and {@code glyphCount} fill 8..15 exactly, and {@code isSideways}
     * and {@code bidiLevel} fill 40..47 exactly. Crosses as a {@code const*}, never by value.
     */
    static final StructLayout DWRITE_GLYPH_RUN_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("fontFace"),             // offset 0   IDWriteFontFace*
            JAVA_FLOAT.withName("fontEmSize"),        // offset 8   FLOAT
            JAVA_INT.withName("glyphCount"),          // offset 12  UINT32
            ADDRESS.withName("glyphIndices"),         // offset 16  UINT16 const*
            ADDRESS.withName("glyphAdvances"),        // offset 24  FLOAT const*
            ADDRESS.withName("glyphOffsets"),         // offset 32  DWRITE_GLYPH_OFFSET const*
            JAVA_INT.withName("isSideways"),          // offset 40  BOOL
            JAVA_INT.withName("bidiLevel"));          // offset 44  UINT32, byteSize 48

    /** {@code DWRITE_MATRIX} (dwrite.h:971): six floats, 24 bytes, 4-byte aligned. By pointer only. */
    static final StructLayout DWRITE_MATRIX_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("m11"),               // offset 0
            JAVA_FLOAT.withName("m12"),               // offset 4
            JAVA_FLOAT.withName("m21"),               // offset 8
            JAVA_FLOAT.withName("m22"),               // offset 12
            JAVA_FLOAT.withName("dx"),                // offset 16
            JAVA_FLOAT.withName("dy"));               // offset 20, byteSize 24

    /** {@code RECT} (windef.h): four {@code LONG}, 16 bytes, 4-byte aligned. By pointer only. */
    static final StructLayout RECT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("left"),                // offset 0
            JAVA_INT.withName("top"),                 // offset 4
            JAVA_INT.withName("right"),               // offset 8
            JAVA_INT.withName("bottom"));             // offset 12, byteSize 16

    /**
     * {@code D2D1_POINT_2F} (d2d1.h:283, aliasing {@code D2D_POINT_2F}, dcommon.h:183): two floats,
     * 8 bytes, 4-byte aligned. <b>This is the only aggregate in the package that crosses by value</b>
     * ({@code ID2D1SimplifiedGeometrySink::BeginFigure}, d2d1.h:2185). On Microsoft x64 an aggregate
     * of 1, 2, 4 or 8 bytes is passed as if it were an integer of that size, in the integer register
     * that argument occupies - never split across two registers, and never in an XMM register even
     * though both members are floats. Putting this layout in the {@code FunctionDescriptor} is how
     * that is expressed; flattening it into two {@code JAVA_FLOAT} parameters would put x in XMM1 and
     * y in XMM2 and read garbage without faulting.
     */
    static final StructLayout D2D1_POINT_2F_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("x"),                 // offset 0
            JAVA_FLOAT.withName("y"));                // offset 4, byteSize 8

    /**
     * {@code D2D1_BEZIER_SEGMENT} (d2d1.h:563): three points, 24 bytes, 4-byte aligned. Larger than
     * 8 bytes, so it could never be passed in a register - and it never is: {@code AddBeziers} takes
     * a pointer to an array of them.
     */
    static final StructLayout D2D1_BEZIER_SEGMENT_LAYOUT = MemoryLayout.structLayout(
            D2D1_POINT_2F_LAYOUT.withName("point1"),  // offset 0
            D2D1_POINT_2F_LAYOUT.withName("point2"),  // offset 8
            D2D1_POINT_2F_LAYOUT.withName("point3")); // offset 16, byteSize 24

    /* DWRITE_GLYPH_RUN is the one struct above that is not a run of equally sized fields. */
    static final long RUN_FONT_FACE = offsetOf(DWRITE_GLYPH_RUN_LAYOUT, "fontFace");
    static final long RUN_FONT_EM_SIZE = offsetOf(DWRITE_GLYPH_RUN_LAYOUT, "fontEmSize");
    static final long RUN_GLYPH_COUNT = offsetOf(DWRITE_GLYPH_RUN_LAYOUT, "glyphCount");
    static final long RUN_GLYPH_INDICES = offsetOf(DWRITE_GLYPH_RUN_LAYOUT, "glyphIndices");
    static final long RUN_GLYPH_ADVANCES = offsetOf(DWRITE_GLYPH_RUN_LAYOUT, "glyphAdvances");
    static final long RUN_GLYPH_OFFSETS = offsetOf(DWRITE_GLYPH_RUN_LAYOUT, "glyphOffsets");
    static final long RUN_IS_SIDEWAYS = offsetOf(DWRITE_GLYPH_RUN_LAYOUT, "isSideways");
    static final long RUN_BIDI_LEVEL = offsetOf(DWRITE_GLYPH_RUN_LAYOUT, "bidiLevel");

    /**
     * {@code DWRITE_SCRIPT_ANALYSIS} (dwrite.h:2351): {@code UINT16 script} then the 4-byte enum
     * {@code DWRITE_SCRIPT_SHAPES shapes}, so two bytes of padding and a size of 8. In and out of
     * {@code GetGlyphs}, {@code GetGlyphPlacements} and {@code SetScriptAnalysis}; the Java mirror
     * {@code DWRITE_SCRIPT_ANALYSIS} keeps {@code short} and {@code int}, which is what the JNI
     * field cache read and wrote.
     */
    static final StructLayout DWRITE_SCRIPT_ANALYSIS_LAYOUT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("script"),            // offset 0   UINT16
            MemoryLayout.paddingLayout(2),
            JAVA_INT.withName("shapes"));             // offset 4   enum, byteSize 8

    /**
     * {@code DWRITE_GLYPH_RUN_DESCRIPTION} (dwrite.h:3092): 40 bytes, 8-byte aligned, with padding
     * after {@code stringLength} and again at the end. Read in the {@code DrawGlyphRun} upcall only.
     * {@code clusterMap} is {@code _Field_size_opt_(stringLength)}, so it may be {@code NULL}.
     */
    static final StructLayout DWRITE_GLYPH_RUN_DESCRIPTION_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("localeName"),           // offset 0   WCHAR const*
            ADDRESS.withName("string"),               // offset 8   WCHAR const*
            JAVA_INT.withName("stringLength"),        // offset 16  UINT32
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("clusterMap"),           // offset 24  UINT16 const*, optional
            JAVA_INT.withName("textPosition"),        // offset 32  UINT32
            MemoryLayout.paddingLayout(4));           // byteSize 40

    static final long DESCRIPTION_STRING_LENGTH =
            offsetOf(DWRITE_GLYPH_RUN_DESCRIPTION_LAYOUT, "stringLength");
    static final long DESCRIPTION_CLUSTER_MAP =
            offsetOf(DWRITE_GLYPH_RUN_DESCRIPTION_LAYOUT, "clusterMap");
    static final long DESCRIPTION_TEXT_POSITION =
            offsetOf(DWRITE_GLYPH_RUN_DESCRIPTION_LAYOUT, "textPosition");

    private DWLayouts() {
    }

    /**
     * The byte offset of a named field, so the struct layouts above are what the code actually uses
     * rather than a set of literals repeated beside them.
     */
    static long offsetOf(StructLayout layout, String field) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    /** The byte offset of a field of a nested struct, so the nesting is expressed once. */
    static long offsetOf(StructLayout layout, String group, String field) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(group),
                MemoryLayout.PathElement.groupElement(field));
    }
}
