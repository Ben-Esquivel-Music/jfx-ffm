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

import com.sun.javafx.font.directwrite.DWAnalysisSinkObject.AnalysisSinkSession;
import com.sun.javafx.font.directwrite.DWTextRendererObject.TextRendererSession;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINKS;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINK_SET_BIDI_LEVEL;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINK_SET_LINE_BREAKPOINTS;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINK_SET_NUMBER_SUBSTITUTION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINK_SET_SCRIPT_ANALYSIS;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_LOCALE_NAME;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_NUMBER_SUBSTITUTION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_READING_DIRECTION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_TEXT_AT_POSITION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_TEXT_BEFORE_POSITION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_OFFSET;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.IID_IDWRITE_TEXT_ANALYSIS_SINK;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.IID_IDWRITE_TEXT_ANALYSIS_SOURCE;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.SINK_RANGE_FD;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.SINK_SET_BIDI_LEVEL_FD;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.SOURCE_QUERY_FD;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.SOURCE_READING_DIRECTION_FD;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.onGetParagraphReadingDirection;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.onSetScriptAnalysis;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.onSinkAddRef;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.onSinkQueryInterface;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.onSinkRelease;
import static com.sun.javafx.font.directwrite.DWComObjectSupport.COM_QUERY_INTERFACE_FD;
import static com.sun.javafx.font.directwrite.DWComObjectSupport.COM_REF_COUNT_FD;
import static com.sun.javafx.font.directwrite.DWLayouts.DESCRIPTION_CLUSTER_MAP;
import static com.sun.javafx.font.directwrite.DWLayouts.DESCRIPTION_STRING_LENGTH;
import static com.sun.javafx.font.directwrite.DWLayouts.DESCRIPTION_TEXT_POSITION;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_GLYPH_RUN_DESCRIPTION_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_GLYPH_RUN_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_MATRIX_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_SCRIPT_ANALYSIS_LAYOUT;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_BIDI_LEVEL;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_FONT_EM_SIZE;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_FONT_FACE;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_ADVANCES;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_COUNT;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_INDICES;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_GLYPH_OFFSETS;
import static com.sun.javafx.font.directwrite.DWLayouts.RUN_IS_SIDEWAYS;
import static com.sun.javafx.font.directwrite.DWNative.E_FAIL;
import static com.sun.javafx.font.directwrite.DWNative.IID_IDWRITE_FACTORY;
import static com.sun.javafx.font.directwrite.DWNative.IID_IUNKNOWN;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_ADD_REF;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_QUERY_INTERFACE;
import static com.sun.javafx.font.directwrite.DWNative.IUNKNOWN_RELEASE;
import static com.sun.javafx.font.directwrite.DWNative.com;
import static com.sun.javafx.font.directwrite.DWNative.guid;
import static com.sun.javafx.font.directwrite.DWNative.slot;
import static com.sun.javafx.font.directwrite.DWNative.unexpected;
import static com.sun.javafx.font.directwrite.DWNative.virtual;
import static com.sun.javafx.font.directwrite.DWShapingCalls.floats;
import static com.sun.javafx.font.directwrite.DWShapingCalls.shorts;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.IID_IDWRITE_PIXEL_SNAPPING;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.IID_IDWRITE_TEXT_RENDERER;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DECORATION_FD;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_GLYPH_RUN;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_GLYPH_RUN_FD;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_INLINE_OBJECT;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_STRIKETHROUGH;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_UNDERLINE;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_GET_CURRENT_TRANSFORM;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_GET_PIXELS_PER_DIP;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_INLINE_OBJECT_FD;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_IS_PIXEL_SNAPPING_DISABLED;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_QUERY_FD;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.TEXT_RENDERERS;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.onDrawGlyphRun;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Self-test hooks for the two inbound objects of {@link DWNative}, {@link DWAnalysisSinkObject} and
 * {@link DWTextRendererObject}; no production code calls them.
 * <p>
 * These call the synthesized vtables the way DirectWrite calls them - through the function
 * pointers in the tables, with the same FunctionDescriptors - so a test can check both objects
 * in isolation, before any font is involved. They prove the tables are well formed, that every
 * slot holds the method it should and that the out-parameter orders are right. What they cannot
 * prove is the Microsoft x64 register assignment, because both ends are the same linker; that
 * proof is DirectWrite itself calling these objects during AnalyzeScript and Draw, and the
 * parity of the results against the C objects.
 */
final class DWInboundTestHooks {

    private DWInboundTestHooks() {
    }

    /**
     * Drives {@code QueryInterface}, {@code AddRef} and {@code Release} through both vtables of an
     * analysis sink.
     *
     * @return sixteen values, or {@code null} when the object is not live:
     *         {@code 0,1} QueryInterface(sink IID) on the sink pointer: HRESULT, 1 if it wrote the
     *         block; {@code 2,3} the same for the source IID; {@code 4,5} for {@code IUnknown};
     *         {@code 6,7} for an unrelated IID, where 7 is 1 if it wrote NULL;
     *         {@code 8,9} QueryInterface(source IID) on the <em>source</em> pointer: HRESULT and 1
     *         if it wrote the block - the C's multiple-inheritance behaviour, reproduced;
     *         {@code 10,11} the same for the sink IID on the source pointer;
     *         {@code 12..15} AddRef on the sink, AddRef on the source, Release on the source,
     *         Release on the sink - one counter shared by both vtables, so 1, 2, 1, 0.
     */
    static long[] analysisSinkSelfTest(long id) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        if (session == null) {
            return null;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment sink = session.block;
            MemorySegment source = sink.asSlice(ANALYSIS_SOURCE_OFFSET);
            MethodHandle queryInterface = virtual(COM_QUERY_INTERFACE_FD);
            MethodHandle counted = virtual(COM_REF_COUNT_FD);
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment sinkIid = guid(scratch, IID_IDWRITE_TEXT_ANALYSIS_SINK);
            MemorySegment sourceIid = guid(scratch, IID_IDWRITE_TEXT_ANALYSIS_SOURCE);
            MemorySegment unknownIid = guid(scratch, IID_IUNKNOWN);
            MemorySegment otherIid = guid(scratch, IID_IDWRITE_FACTORY);
            long block = sink.address();
            long[] result = new long[16];

            result[0] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(sink, IUNKNOWN_QUERY_INTERFACE), sink, sinkIid, out));
            result[1] = out.get(ADDRESS, 0).address() == block ? 1 : 0;
            result[2] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(sink, IUNKNOWN_QUERY_INTERFACE), sink, sourceIid, out));
            result[3] = out.get(ADDRESS, 0).address() == block ? 1 : 0;
            result[4] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(sink, IUNKNOWN_QUERY_INTERFACE), sink, unknownIid, out));
            result[5] = out.get(ADDRESS, 0).address() == block ? 1 : 0;
            result[6] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(sink, IUNKNOWN_QUERY_INTERFACE), sink, otherIid, out));
            result[7] = out.get(ADDRESS, 0).address() == 0 ? 1 : 0;
            result[8] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(source, IUNKNOWN_QUERY_INTERFACE), source, sourceIid, out));
            result[9] = out.get(ADDRESS, 0).address() == block ? 1 : 0;
            result[10] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                    slot(source, IUNKNOWN_QUERY_INTERFACE), source, sinkIid, out));
            result[11] = out.get(ADDRESS, 0).address() == block ? 1 : 0;

            session.refCount = 0;
            result[12] = (int) counted.invokeExact(slot(sink, IUNKNOWN_ADD_REF), sink);
            result[13] = (int) counted.invokeExact(slot(source, IUNKNOWN_ADD_REF), source);
            result[14] = (int) counted.invokeExact(slot(source, IUNKNOWN_RELEASE), source);
            result[15] = (int) counted.invokeExact(slot(sink, IUNKNOWN_RELEASE), sink);
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Drives all nine collectors and queries of an analysis sink through its two vtables, with
     * arguments a test chooses, and reports what came back.
     *
     * @return twenty-six values, or {@code null} when the object is not live:
     *         {@code 0..3} the HRESULTs of SetScriptAnalysis, SetLineBreakpoints, SetBidiLevel and
     *         SetNumberSubstitution;
     *         {@code 4,5,6} GetTextAtPosition(0): HRESULT, the offset in characters of the pointer
     *         it wrote from the start of the text copy, the length;
     *         {@code 7,8,9} GetTextAtPosition(textLength): HRESULT, 1 if it wrote NULL, the length;
     *         {@code 10,11,12} GetTextBeforePosition(0): HRESULT, 1 if NULL, the length;
     *         {@code 13,14,15} GetTextBeforePosition(2): HRESULT, 1 if it wrote the text base, the
     *         length; {@code 16,17,18} GetTextBeforePosition(textLength + 1): HRESULT, 1 if NULL,
     *         the length; {@code 19} GetParagraphReadingDirection - <em>not</em> an HRESULT;
     *         {@code 20,21,22} GetLocaleName(1): HRESULT, 1 if it wrote the locale base, the length;
     *         {@code 23,24,25} GetNumberSubstitution(1): HRESULT, 1 if it wrote NULL, the length.
     */
    static long[] analysisSinkDriveCallbacks(long id, int position, int length, short script,
                                             int shapes, int explicitLevel, int resolvedLevel) {
        AnalysisSinkSession session = ANALYSIS_SINKS.get(id);
        if (session == null) {
            return null;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment sink = session.block;
            MemorySegment source = sink.asSlice(ANALYSIS_SOURCE_OFFSET);
            MethodHandle range = virtual(SINK_RANGE_FD);
            MethodHandle bidi = virtual(SINK_SET_BIDI_LEVEL_FD);
            MethodHandle query = virtual(SOURCE_QUERY_FD);
            MethodHandle direction = virtual(SOURCE_READING_DIRECTION_FD);
            MemorySegment analysis = scratch.allocate(DWRITE_SCRIPT_ANALYSIS_LAYOUT);
            analysis.set(JAVA_SHORT, 0, script);
            analysis.set(JAVA_INT, 4, shapes);
            MemorySegment breakpoints = scratch.allocate(JAVA_BYTE, Math.max(1, length));
            MemorySegment first = scratch.allocate(ADDRESS);
            MemorySegment second = scratch.allocate(ADDRESS);
            long textBase = session.text.address();
            long localeBase = session.locale.address();
            int textLength = session.textLength;
            long[] result = new long[26];

            result[0] = Integer.toUnsignedLong((int) range.invokeExact(
                    slot(sink, ANALYSIS_SINK_SET_SCRIPT_ANALYSIS), sink, position, length, analysis));
            result[1] = Integer.toUnsignedLong((int) range.invokeExact(
                    slot(sink, ANALYSIS_SINK_SET_LINE_BREAKPOINTS), sink, position, length,
                    breakpoints));
            result[2] = Integer.toUnsignedLong((int) bidi.invokeExact(
                    slot(sink, ANALYSIS_SINK_SET_BIDI_LEVEL), sink, position, length,
                    (byte) explicitLevel, (byte) resolvedLevel));
            result[3] = Integer.toUnsignedLong((int) range.invokeExact(
                    slot(sink, ANALYSIS_SINK_SET_NUMBER_SUBSTITUTION), sink, position, length,
                    MemorySegment.NULL));

            result[4] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(source, ANALYSIS_SOURCE_GET_TEXT_AT_POSITION), source, 0, first, second));
            result[5] = (first.get(ADDRESS, 0).address() - textBase) / Character.BYTES;
            result[6] = second.get(JAVA_INT, 0);
            result[7] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(source, ANALYSIS_SOURCE_GET_TEXT_AT_POSITION), source, textLength, first,
                    second));
            result[8] = first.get(ADDRESS, 0).address() == 0 ? 1 : 0;
            result[9] = second.get(JAVA_INT, 0);

            result[10] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(source, ANALYSIS_SOURCE_GET_TEXT_BEFORE_POSITION), source, 0, first, second));
            result[11] = first.get(ADDRESS, 0).address() == 0 ? 1 : 0;
            result[12] = second.get(JAVA_INT, 0);
            result[13] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(source, ANALYSIS_SOURCE_GET_TEXT_BEFORE_POSITION), source, 2, first, second));
            result[14] = first.get(ADDRESS, 0).address() == textBase ? 1 : 0;
            result[15] = second.get(JAVA_INT, 0);
            result[16] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(source, ANALYSIS_SOURCE_GET_TEXT_BEFORE_POSITION), source, textLength + 1,
                    first, second));
            result[17] = first.get(ADDRESS, 0).address() == 0 ? 1 : 0;
            result[18] = second.get(JAVA_INT, 0);

            result[19] = Integer.toUnsignedLong((int) direction.invokeExact(
                    slot(source, ANALYSIS_SOURCE_GET_READING_DIRECTION), source));

            result[20] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(source, ANALYSIS_SOURCE_GET_LOCALE_NAME), source, 1, first, second));
            result[21] = second.get(ADDRESS, 0).address() == localeBase ? 1 : 0;
            result[22] = first.get(JAVA_INT, 0);
            result[23] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(source, ANALYSIS_SOURCE_GET_NUMBER_SUBSTITUTION), source, 1, first, second));
            result[24] = second.get(ADDRESS, 0).address() == 0 ? 1 : 0;
            result[25] = first.get(JAVA_INT, 0);
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Drives {@code QueryInterface}, {@code AddRef} and {@code Release} through the renderer vtable.
     *
     * @return twelve values, or {@code null} when the object is not live: {@code 0,1} the renderer
     *         IID (HRESULT, 1 if it wrote self), {@code 2,3} the pixel-snapping IID, {@code 4,5}
     *         {@code IUnknown}, {@code 6,7} an unrelated IID where 7 is 1 if it wrote NULL, and
     *         {@code 8..11} AddRef, AddRef, Release, Release.
     */
    static long[] textRendererSelfTest(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        if (session == null) {
            return null;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment renderer = session.block;
            MethodHandle queryInterface = virtual(COM_QUERY_INTERFACE_FD);
            MethodHandle counted = virtual(COM_REF_COUNT_FD);
            MemorySegment out = scratch.allocate(ADDRESS);
            long block = renderer.address();
            long[] result = new long[12];
            String[] iids = { IID_IDWRITE_TEXT_RENDERER, IID_IDWRITE_PIXEL_SNAPPING, IID_IUNKNOWN,
                    IID_IDWRITE_FACTORY };
            for (int i = 0; i < iids.length; i++) {
                MemorySegment iid = guid(scratch, iids[i]);
                result[i * 2] = Integer.toUnsignedLong((int) queryInterface.invokeExact(
                        slot(renderer, IUNKNOWN_QUERY_INTERFACE), renderer, iid, out));
                long written = out.get(ADDRESS, 0).address();
                result[i * 2 + 1] = i == 3 ? (written == 0 ? 1 : 0) : (written == block ? 1 : 0);
            }
            session.refCount = 0;
            result[8] = (int) counted.invokeExact(slot(renderer, IUNKNOWN_ADD_REF), renderer);
            result[9] = (int) counted.invokeExact(slot(renderer, IUNKNOWN_ADD_REF), renderer);
            result[10] = (int) counted.invokeExact(slot(renderer, IUNKNOWN_RELEASE), renderer);
            result[11] = (int) counted.invokeExact(slot(renderer, IUNKNOWN_RELEASE), renderer);
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Drives the three pixel-snapping queries through the renderer vtable and reports what they
     * wrote.
     *
     * @return nine values, or {@code null}: {@code 0,1} IsPixelSnappingDisabled (HRESULT, the BOOL),
     *         {@code 2} GetCurrentTransform HRESULT, {@code 3..8} the six floats of the matrix as
     *         raw bits, {@code 9} GetPixelsPerDip HRESULT and {@code 10} its float as raw bits.
     */
    static long[] textRendererDriveQueries(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        if (session == null) {
            return null;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment renderer = session.block;
            MethodHandle query = virtual(RENDERER_QUERY_FD);
            MemorySegment flag = scratch.allocate(JAVA_INT);
            MemorySegment matrix = scratch.allocate(DWRITE_MATRIX_LAYOUT);
            MemorySegment pixelsPerDip = scratch.allocate(JAVA_FLOAT);
            long[] result = new long[11];
            result[0] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(renderer, RENDERER_IS_PIXEL_SNAPPING_DISABLED), renderer,
                    MemorySegment.NULL, flag));
            result[1] = flag.get(JAVA_INT, 0);
            result[2] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(renderer, RENDERER_GET_CURRENT_TRANSFORM), renderer, MemorySegment.NULL,
                    matrix));
            for (int i = 0; i < 6; i++) {
                result[3 + i] = Float.floatToRawIntBits(matrix.getAtIndex(JAVA_FLOAT, i));
            }
            result[9] = Integer.toUnsignedLong((int) query.invokeExact(
                    slot(renderer, RENDERER_GET_PIXELS_PER_DIP), renderer, MemorySegment.NULL,
                    pixelsPerDip));
            result[10] = Float.floatToRawIntBits(pixelsPerDip.get(JAVA_FLOAT, 0));
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Feeds one synthetic glyph run through the renderer's {@code DrawGlyphRun} stub, so that the
     * clamping, the slot merge and the two truncations of the drains can be tested with values a
     * test chooses rather than with whatever DirectWrite happens to produce. A {@code null} array
     * becomes a {@code NULL} pointer in the struct, which is the optional case the C would have
     * dereferenced.
     *
     * @return the HRESULT the stub returned
     */
    static int textRendererFeedRun(long id, float baselineOriginX, float baselineOriginY,
                                   int measuringMode, long fontFace, float fontEmSize,
                                   short[] glyphIndices, float[] glyphAdvances, float[] glyphOffsets,
                                   short[] clusterMap, int textPosition, int stringLength,
                                   int isSideways, int bidiLevel, boolean withDescription) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        if (session == null) {
            return E_FAIL;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment renderer = session.block;
            MemorySegment run = scratch.allocate(DWRITE_GLYPH_RUN_LAYOUT);
            run.set(ADDRESS, RUN_FONT_FACE, com(fontFace));
            run.set(JAVA_FLOAT, RUN_FONT_EM_SIZE, fontEmSize);
            run.set(JAVA_INT, RUN_GLYPH_COUNT, glyphIndices == null ? 0 : glyphIndices.length);
            run.set(ADDRESS, RUN_GLYPH_INDICES, shorts(scratch, glyphIndices));
            run.set(ADDRESS, RUN_GLYPH_ADVANCES, floats(scratch, glyphAdvances));
            run.set(ADDRESS, RUN_GLYPH_OFFSETS, floats(scratch, glyphOffsets));
            run.set(JAVA_INT, RUN_IS_SIDEWAYS, isSideways);
            run.set(JAVA_INT, RUN_BIDI_LEVEL, bidiLevel);

            MemorySegment description = MemorySegment.NULL;
            if (withDescription) {
                description = scratch.allocate(DWRITE_GLYPH_RUN_DESCRIPTION_LAYOUT);
                description.set(JAVA_INT, DESCRIPTION_STRING_LENGTH, stringLength);
                description.set(JAVA_INT, DESCRIPTION_TEXT_POSITION, textPosition);
                description.set(ADDRESS, DESCRIPTION_CLUSTER_MAP, shorts(scratch, clusterMap));
            }
            MethodHandle drawGlyphRun = virtual(RENDERER_DRAW_GLYPH_RUN_FD);
            return (int) drawGlyphRun.invokeExact(slot(renderer, RENDERER_DRAW_GLYPH_RUN), renderer,
                    MemorySegment.NULL, baselineOriginX, baselineOriginY, measuringMode, run,
                    description, MemorySegment.NULL);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Drives the three slots the renderer ignores - {@code DrawUnderline}, {@code DrawStrikethrough}
     * and {@code DrawInlineObject} - so that they are known to be present, callable and harmless.
     *
     * @return their three HRESULTs, or {@code null} when the object is not live
     */
    static long[] textRendererDriveIgnored(long id) {
        TextRendererSession session = TEXT_RENDERERS.get(id);
        if (session == null) {
            return null;
        }
        try {
            MemorySegment renderer = session.block;
            MethodHandle decoration = virtual(RENDERER_DECORATION_FD);
            MethodHandle inlineObject = virtual(RENDERER_INLINE_OBJECT_FD);
            long[] result = new long[3];
            result[0] = Integer.toUnsignedLong((int) decoration.invokeExact(
                    slot(renderer, RENDERER_DRAW_UNDERLINE), renderer, MemorySegment.NULL, 1.5f,
                    -2.5f, MemorySegment.NULL, MemorySegment.NULL));
            result[1] = Integer.toUnsignedLong((int) decoration.invokeExact(
                    slot(renderer, RENDERER_DRAW_STRIKETHROUGH), renderer, MemorySegment.NULL, 3.5f,
                    -4.5f, MemorySegment.NULL, MemorySegment.NULL));
            result[2] = Integer.toUnsignedLong((int) inlineObject.invokeExact(
                    slot(renderer, RENDERER_DRAW_INLINE_OBJECT), renderer, MemorySegment.NULL, 5.5f,
                    -6.5f, MemorySegment.NULL, 1, 0, MemorySegment.NULL));
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Calls a stub through a pointer whose object has already been disposed, which is what a COM
     * client holding a stale reference would do. Nothing may be recorded and nothing may fault: the
     * id read out of the freed block misses the registry and every target answers its sentinel.
     *
     * @return {@code { QueryInterface HRESULT, AddRef, Release, SetScriptAnalysis HRESULT,
     *         GetParagraphReadingDirection, DrawGlyphRun HRESULT }}
     */
    static long[] staleCallbackProbe(long sinkBlock, long rendererBlock) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment sink = MemorySegment.ofAddress(sinkBlock);
            MemorySegment source = MemorySegment.ofAddress(sinkBlock + ANALYSIS_SOURCE_OFFSET);
            MemorySegment renderer = MemorySegment.ofAddress(rendererBlock);
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment iid = guid(scratch, IID_IDWRITE_TEXT_ANALYSIS_SINK);
            MemorySegment analysis = scratch.allocate(DWRITE_SCRIPT_ANALYSIS_LAYOUT);
            MemorySegment run = scratch.allocate(DWRITE_GLYPH_RUN_LAYOUT);
            long[] result = new long[6];
            result[0] = Integer.toUnsignedLong(onSinkQueryInterface(sink, iid, out));
            result[1] = onSinkAddRef(sink);
            result[2] = onSinkRelease(sink);
            result[3] = Integer.toUnsignedLong(onSetScriptAnalysis(sink, 0, 1, analysis));
            result[4] = onGetParagraphReadingDirection(source);
            result[5] = Integer.toUnsignedLong(onDrawGlyphRun(renderer, MemorySegment.NULL, 0f, 0f, 0,
                    run, MemorySegment.NULL, MemorySegment.NULL));
            return result;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
