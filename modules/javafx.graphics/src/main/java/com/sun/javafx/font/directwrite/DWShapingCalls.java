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
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINK_SET_BIDI_LEVEL;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINK_SET_LINE_BREAKPOINTS;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINK_SET_NUMBER_SUBSTITUTION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SINK_SET_SCRIPT_ANALYSIS;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_LOCALE_NAME;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_NUMBER_SUBSTITUTION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_READING_DIRECTION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_TEXT_AT_POSITION;
import static com.sun.javafx.font.directwrite.DWAnalysisSinkObject.ANALYSIS_SOURCE_GET_TEXT_BEFORE_POSITION;
import static com.sun.javafx.font.directwrite.DWLayouts.DWRITE_SCRIPT_ANALYSIS_LAYOUT;
import static com.sun.javafx.font.directwrite.DWNative.E_FAIL;
import static com.sun.javafx.font.directwrite.DWNative.com;
import static com.sun.javafx.font.directwrite.DWNative.outPointer;
import static com.sun.javafx.font.directwrite.DWNative.slot;
import static com.sun.javafx.font.directwrite.DWNative.u32;
import static com.sun.javafx.font.directwrite.DWNative.unexpected;
import static com.sun.javafx.font.directwrite.DWNative.virtual;
import static com.sun.javafx.font.directwrite.DWNative.wide;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_GLYPH_RUN;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_INLINE_OBJECT;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_STRIKETHROUGH;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_DRAW_UNDERLINE;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_GET_CURRENT_TRANSFORM;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_GET_PIXELS_PER_DIP;
import static com.sun.javafx.font.directwrite.DWTextRendererObject.RENDERER_IS_PIXEL_SNAPPING_DISABLED;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The shaping calls of {@link DWNative}: {@code IDWriteTextAnalyzer}, {@code IDWriteTextFormat} and
 * {@code IDWriteTextLayout}, plus the factory methods that make them.
 * <p>
 * The shaping half of the port. Every call here is a COM vtable dispatch on a pointer some
 * earlier call produced, so nothing new is bound by name. Slot numbers come from a mechanical
 * count over the Windows SDK header, never from reading: the arithmetic that
 * puts IDWriteTextLayout::Draw at 58 is 3 (IUnknown) + 25 (IDWriteTextFormat's own methods,
 * dwrite.h:2062-2289) + 30 (Draw's ordinal in IDWriteTextLayout), and a reader cannot check that
 * by eye. Both ends of it are pinned behaviourally by DWLayoutTest: the format accessors at
 * slots 20-27 answer the values CreateTextFormat was given, and GetMaxWidth at 42 answers the
 * width CreateTextLayout was given - which can only be true if IDWriteTextFormat really ends at
 * 27 and IDWriteTextLayout really begins at 28.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWShapingCalls {

    /** {@code IDWriteFactory::CreateTextFormat}, ord 12 of 21 (dwrite.h:4938). */
    private static final int FACTORY_CREATE_TEXT_FORMAT = 15;

    /** {@code IDWriteFactory::CreateTextLayout}, ord 15 (dwrite.h:4985). */
    private static final int FACTORY_CREATE_TEXT_LAYOUT = 18;

    /** {@code IDWriteFactory::CreateTextAnalyzer}, ord 18 (dwrite.h:5052). */
    private static final int FACTORY_CREATE_TEXT_ANALYZER = 21;

    /* IDWriteTextAnalyzer (dwrite.h:2738), a direct subinterface of IUnknown, so base 3. */
    private static final int ANALYZER_ANALYZE_SCRIPT = 3;               // ord 0, dwrite.h:2752
    private static final int ANALYZER_ANALYZE_BIDI = 4;                 // ord 1, dwrite.h:2779
    private static final int ANALYZER_ANALYZE_NUMBER_SUBSTITUTION = 5;  // ord 2, dwrite.h:2807
    private static final int ANALYZER_ANALYZE_LINE_BREAKPOINTS = 6;     // ord 3, dwrite.h:2842
    private static final int ANALYZER_GET_GLYPHS = 7;                   // ord 4, dwrite.h:2895
    private static final int ANALYZER_GET_GLYPH_PLACEMENTS = 8;         // ord 5, dwrite.h:2947

    /* IDWriteTextFormat (dwrite.h:2053), base 3, 25 own methods: slots 3..27 inclusive. */
    private static final int TEXT_FORMAT_GET_FONT_FAMILY_NAME_LENGTH = 20;  // ord 17, dwrite.h:2241
    private static final int TEXT_FORMAT_GET_FONT_FAMILY_NAME = 21;         // ord 18, dwrite.h:2251
    private static final int TEXT_FORMAT_GET_FONT_WEIGHT = 22;              // ord 19, dwrite.h:2259
    private static final int TEXT_FORMAT_GET_FONT_STYLE = 23;               // ord 20, dwrite.h:2264
    private static final int TEXT_FORMAT_GET_FONT_STRETCH = 24;             // ord 21, dwrite.h:2269
    private static final int TEXT_FORMAT_GET_FONT_SIZE = 25;                // ord 22, dwrite.h:2274
    private static final int TEXT_FORMAT_GET_LOCALE_NAME_LENGTH = 26;       // ord 23, dwrite.h:2279
    private static final int TEXT_FORMAT_GET_LOCALE_NAME = 27;              // ord 24, dwrite.h:2289

    /* IDWriteTextLayout (dwrite.h:3775), base IDWriteTextFormat = 28. */
    private static final int TEXT_LAYOUT_GET_MAX_WIDTH = 42;                // ord 14, dwrite.h:3967
    private static final int TEXT_LAYOUT_GET_MAX_HEIGHT = 43;               // ord 15, dwrite.h:3972
    private static final int TEXT_LAYOUT_DRAW = 58;                         // ord 30, dwrite.h:4200

    /**
     * {@code HRESULT f(this, WCHAR const* family, IDWriteFontCollection*, DWRITE_FONT_WEIGHT,
     * DWRITE_FONT_STYLE, DWRITE_FONT_STRETCH, FLOAT size, WCHAR const* locale,
     * IDWriteTextFormat** out)}: {@code IDWriteFactory::CreateTextFormat}. The {@code FLOAT} is the
     * sixth argument, so it travels in XMM5 while the arguments on either side of it use RDX..R9
     * and the stack; naming it {@code JAVA_FLOAT} in the descriptor is what says so.
     */
    private static final MethodHandle HR_CREATE_TEXT_FORMAT = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, ADDRESS,
            ADDRESS));

    /**
     * {@code HRESULT f(this, WCHAR const* string, UINT32 stringLength, IDWriteTextFormat*,
     * FLOAT maxWidth, FLOAT maxHeight, IDWriteTextLayout** out)}:
     * {@code IDWriteFactory::CreateTextLayout}. Note what is <em>not</em> here: a start offset.
     * See {@link #createTextLayout}.
     */
    private static final MethodHandle HR_CREATE_TEXT_LAYOUT = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, ADDRESS));

    /**
     * {@code HRESULT f(this, IDWriteTextAnalysisSource*, UINT32 textPosition, UINT32 textLength,
     * IDWriteTextAnalysisSink*)}: all four {@code IDWriteTextAnalyzer::Analyze*} methods share it.
     */
    private static final MethodHandle HR_ANALYZE = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    /** {@code IDWriteTextAnalyzer::GetGlyphs} (dwrite.h:2895): {@code this} plus 17 parameters. */
    private static final MethodHandle HR_GET_GLYPHS = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS,
            ADDRESS,        // WCHAR const* textString
            JAVA_INT,       // UINT32 textLength
            ADDRESS,        // IDWriteFontFace*
            JAVA_INT,       // BOOL isSideways
            JAVA_INT,       // BOOL isRightToLeft
            ADDRESS,        // DWRITE_SCRIPT_ANALYSIS const*
            ADDRESS,        // WCHAR const* localeName, optional
            ADDRESS,        // IDWriteNumberSubstitution*, optional
            ADDRESS,        // DWRITE_TYPOGRAPHIC_FEATURES const**, optional
            ADDRESS,        // UINT32 const* featureRangeLengths, optional
            JAVA_INT,       // UINT32 featureRanges
            JAVA_INT,       // UINT32 maxGlyphCount
            ADDRESS,        // UINT16* clusterMap, out
            ADDRESS,        // DWRITE_SHAPING_TEXT_PROPERTIES* textProps, out
            ADDRESS,        // UINT16* glyphIndices, out
            ADDRESS,        // DWRITE_SHAPING_GLYPH_PROPERTIES* glyphProps, out
            ADDRESS));      // UINT32* actualGlyphCount, out

    /**
     * {@code IDWriteTextAnalyzer::GetGlyphPlacements} (dwrite.h:2947): {@code this} plus 18
     * parameters. {@code textProps} is {@code _Inout_updates_}, so it is copied back like a real
     * out-parameter; {@code clusterMap}, {@code glyphIndices} and {@code glyphProps} are
     * {@code const} inputs here even though {@code GetGlyphs} filled them.
     */
    private static final MethodHandle HR_GET_GLYPH_PLACEMENTS = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS,
            ADDRESS,        // WCHAR const* textString
            ADDRESS,        // UINT16 const* clusterMap
            ADDRESS,        // DWRITE_SHAPING_TEXT_PROPERTIES* textProps, in out
            JAVA_INT,       // UINT32 textLength
            ADDRESS,        // UINT16 const* glyphIndices
            ADDRESS,        // DWRITE_SHAPING_GLYPH_PROPERTIES const* glyphProps
            JAVA_INT,       // UINT32 glyphCount
            ADDRESS,        // IDWriteFontFace*
            JAVA_FLOAT,     // FLOAT fontEmSize
            JAVA_INT,       // BOOL isSideways
            JAVA_INT,       // BOOL isRightToLeft
            ADDRESS,        // DWRITE_SCRIPT_ANALYSIS const*
            ADDRESS,        // WCHAR const* localeName, optional
            ADDRESS,        // DWRITE_TYPOGRAPHIC_FEATURES const**, optional
            ADDRESS,        // UINT32 const* featureRangeLengths, optional
            JAVA_INT,       // UINT32 featureRanges
            ADDRESS,        // FLOAT* glyphAdvances, out
            ADDRESS));      // DWRITE_GLYPH_OFFSET* glyphOffsets, out

    /**
     * {@code HRESULT f(this, void* clientDrawingContext, IDWriteTextRenderer*, FLOAT originX,
     * FLOAT originY)}: {@code IDWriteTextLayout::Draw} (dwrite.h:4200). The two floats are the third
     * and fourth arguments, so they are in XMM2 and XMM3 while RCX and RDX hold {@code this} and the
     * context - the positional rule, not "floats fill XMM0 upwards".
     */
    private static final MethodHandle HR_DRAW = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_FLOAT, JAVA_FLOAT));

    /** {@code FLOAT f(this)}: GetFontSize, GetMaxWidth, GetMaxHeight - the only float returns here. */
    private static final MethodHandle F32_THIS = virtual(FunctionDescriptor.of(JAVA_FLOAT, ADDRESS));

    /** {@code HRESULT f(this, WCHAR* buffer, UINT32 nameSize)}: GetFontFamilyName, GetLocaleName. */
    private static final MethodHandle HR_BUF_I = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    private DWShapingCalls() {
    }

    /**
     * {@code IDWriteFactory::CreateTextAnalyzer} ({@code directwrite.cpp:1831-1837}): the analyzer,
     * or {@code 0} on a failing HRESULT, which {@code IDWriteFactory.CreateTextAnalyzer} turns into
     * {@code null} and {@code DWGlyphLayout} into an unshaped run.
     */
    static long createTextAnalyzer(long self) {
        return outPointer(self, FACTORY_CREATE_TEXT_ANALYZER);
    }

    /**
     * {@code IDWriteFactory::CreateTextFormat} ({@code directwrite.cpp:1839-1861}).
     * <p>
     * Both names arrive as {@code char[]} with the terminator the caller appended
     * ({@code IDWriteFactory.java:51,57}) and cross verbatim through {@link DWNative#wide}:
     * {@code allocateFrom(String, UTF_16LE)} would run a {@code CharsetEncoder} and replace an
     * unpaired surrogate with U+FFFD, and a family name is arbitrary text out of a font file. A
     * {@code null} name becomes {@code NULL}, as the C's {@code if (arg1)} guard left it -
     * DirectWrite documents both as {@code _In_z_}, so that is a fault in either path and is
     * unreachable from {@code DWGlyphLayout}, which always has a family and always passes
     * {@code "en-us"}.
     */
    static long createTextFormat(long self, char[] fontFamily, long fontCollection, int fontWeight,
                                 int fontStyle, int fontStretch, float fontSize, char[] localeName) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_CREATE_TEXT_FORMAT.invokeExact(
                    slot(obj, FACTORY_CREATE_TEXT_FORMAT), obj, wide(scratch, fontFamily),
                    com(fontCollection), fontWeight, fontStyle, fontStretch, fontSize,
                    wide(scratch, localeName), out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteFactory::CreateTextLayout} ({@code directwrite.cpp:1892-1916}).
     * <p>
     * <b>The layout is built over {@code text[start .. start+count)}, not over {@code text}.</b>
     * DirectWrite's own signature has no start offset - it takes a pointer and a length - so the C
     * added {@code start} to the pinned array before calling ({@code directwrite.cpp:1904}:
     * {@code const WCHAR* text = (const WCHAR*)(lparg1 + start)}). Dropping that addition compiles,
     * links, returns {@code S_OK} and produces glyphs; it just produces the glyphs of the wrong
     * characters, and only for runs that do not start at 0. {@code DWGlyphLayout.renderShape}
     * passes {@code run.getStart()} ({@code DWGlyphLayout.java:359}), which is non-zero for every
     * run after the first, so this is the defect these shaping bindings exist to avoid. It is proved by
     * {@code DWLayoutTest.createTextLayoutHonoursTheStartOffset}, which shapes the same three
     * characters out of two arrays that agree only at the offset, and by every {@code Draw} parity
     * row, all of which use a non-zero start.
     * <p>
     * The three guards are the C's, in the C's order ({@code directwrite.cpp:1900-1902}), and a
     * failure returns {@code 0} with {@code hr} still {@code E_FAIL}, which is what the {@code fail}
     * label did. A {@code null} array returns {@code 0} here; the C reached
     * {@code GetArrayLength(NULL)}, which aborts the JVM, and no caller can reach it.
     */
    static long createTextLayout(long self, char[] text, int start, int count, long textFormat,
                                 float maxWidth, float maxHeight) {
        if (text == null || start < 0 || count < 0) {
            return 0L;
        }
        if (count > Integer.MAX_VALUE - start || start + count > text.length) {
            return 0L;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment buffer = scratch.allocateFrom(JAVA_CHAR, text);
            MemorySegment obj = com(self);
            int hr = (int) HR_CREATE_TEXT_LAYOUT.invokeExact(
                    slot(obj, FACTORY_CREATE_TEXT_LAYOUT), obj,
                    buffer.asSlice((long) start * Character.BYTES), count, com(textFormat),
                    maxWidth, maxHeight, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteTextAnalyzer::AnalyzeScript} ({@code directwrite.cpp:2212-2220}), slot 3.
     * {@code source} and {@code sink} are two interface pointers of one object - the C passed the
     * same {@code JFXTextAnalysisSink*} twice and let the compiler adjust it to each base
     * ({@code directwrite.cpp:936} declares the bases in the order sink, source, so the sink vtable
     * is at offset 0 and the source vtable at offset 8). The Java object of
     * {@link DWAnalysisSinkObject#newAnalysisSink} keeps that shape, so
     * {@link DWAnalysisSinkObject#analysisSinkPointer} and {@link DWAnalysisSinkObject#analysisSourcePointer}
     * differ by 8.
     */
    static int analyzeScript(long self, long source, int textPosition, int textLength, long sink) {
        return analyze(self, ANALYZER_ANALYZE_SCRIPT, source, textPosition, textLength, sink);
    }

    /**
     * {@code IDWriteTextAnalyzer::AnalyzeBidi}, slot 4. Never a JNI native: JavaFX computes bidi
     * levels itself. It is bound because it is the only way to make DirectWrite call
     * {@code SetBidiLevel} - sink slot 5, and the only slot in the package taking {@code UINT8}
     * arguments - which is otherwise a vtable entry no test could reach.
     */
    static int analyzeBidi(long self, long source, int textPosition, int textLength, long sink) {
        return analyze(self, ANALYZER_ANALYZE_BIDI, source, textPosition, textLength, sink);
    }

    /** {@code IDWriteTextAnalyzer::AnalyzeNumberSubstitution}, slot 5; drives sink slot 6. */
    static int analyzeNumberSubstitution(long self, long source, int textPosition, int textLength,
                                         long sink) {
        return analyze(self, ANALYZER_ANALYZE_NUMBER_SUBSTITUTION, source, textPosition, textLength,
                sink);
    }

    /** {@code IDWriteTextAnalyzer::AnalyzeLineBreakpoints}, slot 6; drives sink slot 4. */
    static int analyzeLineBreakpoints(long self, long source, int textPosition, int textLength,
                                      long sink) {
        return analyze(self, ANALYZER_ANALYZE_LINE_BREAKPOINTS, source, textPosition, textLength,
                sink);
    }

    private static int analyze(long self, int index, long source, int textPosition, int textLength,
                               long sink) {
        MemorySegment obj = com(self);
        try {
            return (int) HR_ANALYZE.invokeExact(slot(obj, index), obj, com(source), textPosition,
                    textLength, com(sink));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteTextAnalyzer::GetGlyphs} ({@code directwrite.cpp:2222-2284}), slot 7.
     * <p>
     * The four validations and their order are the C's ({@code directwrite.cpp:2249-2252}); on any
     * of them the initial {@code hr = E_FAIL} is returned and DirectWrite is never called, which
     * {@code DWGlyphLayout} reads as "abandon this run" ({@code DWGlyphLayout.java:131-134}). The
     * text pointer is {@code text + textStart} exactly as at {@code directwrite.cpp:2253}, while
     * every other array is passed from index 0 - {@code clusterMap} and {@code textProps} are sized
     * by {@code textLength}, not by the whole string.
     * <p>
     * Arrays cross through a confined arena and the five out-parameters are copied back after the
     * call, which is what {@code Release*ArrayElements(..., 0)} did ({@code :2274-2282}). The four
     * {@code const} inputs are not copied back: DirectWrite cannot have changed them, so the C's
     * copy-back of those was a no-op. {@code localeName} is optional and {@code DWGlyphLayout}
     * always passes {@code null}, so DirectWrite picks the script default.
     * <p>
     * {@code E_NOT_SUFFICIENT_BUFFER} (0x8007007A) is expected traffic here, not a failure: the
     * caller doubles {@code maxGlyphCount} and calls again ({@code DWGlyphLayout.java:121-129}).
     */
    static int getGlyphs(long self, char[] textString, int textStart, int textLength, long fontFace,
                         boolean isSideways, boolean isRightToLeft,
                         DWRITE_SCRIPT_ANALYSIS scriptAnalysis, char[] localeName,
                         long numberSubstitution, long[] features, int[] featureRangeLengths,
                         int featureRanges, int maxGlyphCount, short[] clusterMap, short[] textProps,
                         short[] glyphIndices, short[] glyphProps, int[] actualGlyphCount) {
        if (textStart < 0 || textString == null) {
            return E_FAIL;
        }
        if (textLength <= 0 || textLength > textString.length) {
            return E_FAIL;
        }
        if (textStart > textString.length - textLength) {
            return E_FAIL;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment text = scratch.allocateFrom(JAVA_CHAR, textString);
            MemorySegment cluster = shorts(scratch, clusterMap);
            MemorySegment props = shorts(scratch, textProps);
            MemorySegment indices = shorts(scratch, glyphIndices);
            MemorySegment glyphProperties = shorts(scratch, glyphProps);
            MemorySegment count = ints(scratch, actualGlyphCount);
            MemorySegment obj = com(self);
            int hr = (int) HR_GET_GLYPHS.invokeExact(slot(obj, ANALYZER_GET_GLYPHS), obj,
                    text.asSlice((long) textStart * Character.BYTES), textLength, com(fontFace),
                    isSideways ? 1 : 0, isRightToLeft ? 1 : 0,
                    scriptAnalysis(scratch, scriptAnalysis), wide(scratch, localeName),
                    com(numberSubstitution), longs(scratch, features),
                    ints(scratch, featureRangeLengths), featureRanges, maxGlyphCount, cluster, props,
                    indices, glyphProperties, count);
            copyBack(cluster, clusterMap);
            copyBack(props, textProps);
            copyBack(indices, glyphIndices);
            copyBack(glyphProperties, glyphProps);
            copyBack(count, actualGlyphCount);
            return hr;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteTextAnalyzer::GetGlyphPlacements} ({@code directwrite.cpp:2286-2355}), slot 8.
     * The same four validations, the same {@code text + textStart}, and three arrays copied back:
     * {@code textProps} (declared {@code _Inout_updates_}), {@code glyphAdvances} and
     * {@code glyphOffsets}. {@code glyphOffsets} is {@code DWRITE_GLYPH_OFFSET*} - two floats per
     * glyph - and the caller's {@code float[]} is twice {@code glyphCount} long, which is why it
     * needs no conversion in either direction.
     */
    static int getGlyphPlacements(long self, char[] textString, short[] clusterMap, short[] textProps,
                                  int textStart, int textLength, short[] glyphIndices,
                                  short[] glyphProps, int glyphCount, long fontFace, float fontEmSize,
                                  boolean isSideways, boolean isRightToLeft,
                                  DWRITE_SCRIPT_ANALYSIS scriptAnalysis, char[] localeName,
                                  long[] features, int[] featureRangeLengths, int featureRanges,
                                  float[] glyphAdvances, float[] glyphOffsets) {
        if (textStart < 0 || textString == null) {
            return E_FAIL;
        }
        if (textLength <= 0 || textLength > textString.length) {
            return E_FAIL;
        }
        if (textStart > textString.length - textLength) {
            return E_FAIL;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment text = scratch.allocateFrom(JAVA_CHAR, textString);
            MemorySegment props = shorts(scratch, textProps);
            MemorySegment advances = floats(scratch, glyphAdvances);
            MemorySegment offsets = floats(scratch, glyphOffsets);
            MemorySegment obj = com(self);
            int hr = (int) HR_GET_GLYPH_PLACEMENTS.invokeExact(
                    slot(obj, ANALYZER_GET_GLYPH_PLACEMENTS), obj,
                    text.asSlice((long) textStart * Character.BYTES), shorts(scratch, clusterMap),
                    props, textLength, shorts(scratch, glyphIndices), shorts(scratch, glyphProps),
                    glyphCount, com(fontFace), fontEmSize, isSideways ? 1 : 0, isRightToLeft ? 1 : 0,
                    scriptAnalysis(scratch, scriptAnalysis), wide(scratch, localeName),
                    longs(scratch, features), ints(scratch, featureRangeLengths), featureRanges,
                    advances, offsets);
            copyBack(props, textProps);
            copyBack(advances, glyphAdvances);
            copyBack(offsets, glyphOffsets);
            return hr;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteTextLayout::Draw} ({@code directwrite.cpp:2357-2362}), slot 58: the call that
     * drives the renderer. {@code DWGlyphLayout} always passes {@code (0, renderer, 0, 0)}
     * ({@code DWGlyphLayout.java:366}), but the origin is a real parameter here and the tests move
     * it, because the difference between two draws at different origins is the sharpest available
     * proof that the two {@code FLOAT}s reach XMM2 and XMM3 outbound and come back out of XMM2 and
     * XMM3 in the {@code DrawGlyphRun} upcall.
     */
    static int draw(long self, long clientDrawingContext, long renderer, float originX,
                    float originY) {
        MemorySegment obj = com(self);
        try {
            return (int) HR_DRAW.invokeExact(slot(obj, TEXT_LAYOUT_DRAW), obj,
                    com(clientDrawingContext), com(renderer), originX, originY);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* The format and layout accessors below were never JNI natives and have no production caller.
     * They are here for the reason the font-face bindings bind GetGlyphCount and GetMetrics: they are the only
     * behavioural proof of the slot arithmetic around them. IDWriteTextFormat has 25 own methods, so
     * it must end at 27 and IDWriteTextLayout must begin at 28; reading back the six values
     * CreateTextFormat was given, from slots 20-27, and the maximum width CreateTextLayout was
     * given, from slot 42 of the layout, is what makes Draw at 58 more than an assertion. Each is
     * about six lines and none is reachable from main code. */

    /** {@code IDWriteTextFormat::GetFontWeight}, slot 22 (dwrite.h:2259). */
    static int getTextFormatFontWeight(long self) {
        return u32(self, TEXT_FORMAT_GET_FONT_WEIGHT);
    }

    /** {@code IDWriteTextFormat::GetFontStyle}, slot 23 (dwrite.h:2264). */
    static int getTextFormatFontStyle(long self) {
        return u32(self, TEXT_FORMAT_GET_FONT_STYLE);
    }

    /** {@code IDWriteTextFormat::GetFontStretch}, slot 24 (dwrite.h:2269). */
    static int getTextFormatFontStretch(long self) {
        return u32(self, TEXT_FORMAT_GET_FONT_STRETCH);
    }

    /**
     * {@code IDWriteTextFormat::GetFontSize}, slot 25 (dwrite.h:2274): a {@code FLOAT} return, so
     * the value comes back in XMM0 rather than EAX. Reading it through {@link DWNative#U32_THIS} would
     * answer a stale integer instead of failing, which is why the descriptor is separate.
     */
    static float getTextFormatFontSize(long self) {
        return f32(self, TEXT_FORMAT_GET_FONT_SIZE);
    }

    /** {@code IDWriteTextFormat::GetFontFamilyNameLength}, slot 20, in characters without the NUL. */
    static int getTextFormatFontFamilyNameLength(long self) {
        return u32(self, TEXT_FORMAT_GET_FONT_FAMILY_NAME_LENGTH);
    }

    /** {@code IDWriteTextFormat::GetLocaleNameLength}, slot 26, in characters without the NUL. */
    static int getTextFormatLocaleNameLength(long self) {
        return u32(self, TEXT_FORMAT_GET_LOCALE_NAME_LENGTH);
    }

    /**
     * {@code IDWriteTextFormat::GetFontFamilyName}, slot 21 (dwrite.h:2251): the family name of the
     * format, or {@code null} on a failing HRESULT. {@code nameSize} counts the terminator.
     */
    static String getTextFormatFontFamilyName(long self) {
        return textFormatString(self, TEXT_FORMAT_GET_FONT_FAMILY_NAME,
                getTextFormatFontFamilyNameLength(self));
    }

    /** {@code IDWriteTextFormat::GetLocaleName}, slot 27 (dwrite.h:2289). */
    static String getTextFormatLocaleName(long self) {
        return textFormatString(self, TEXT_FORMAT_GET_LOCALE_NAME,
                getTextFormatLocaleNameLength(self));
    }

    private static String textFormatString(long self, int index, int length) {
        if (length < 0) {
            return null;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment buffer = scratch.allocate(JAVA_CHAR, length + 1L);
            MemorySegment obj = com(self);
            int hr = (int) HR_BUF_I.invokeExact(slot(obj, index), obj, buffer, length + 1);
            if (hr < 0) {
                return null;
            }
            char[] chars = buffer.toArray(JAVA_CHAR);
            return new String(chars, 0, length);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code IDWriteTextLayout::GetMaxWidth}, slot 42 (dwrite.h:3967), a {@code FLOAT} return. */
    static float getTextLayoutMaxWidth(long self) {
        return f32(self, TEXT_LAYOUT_GET_MAX_WIDTH);
    }

    /** {@code IDWriteTextLayout::GetMaxHeight}, slot 43 (dwrite.h:3972), a {@code FLOAT} return. */
    static float getTextLayoutMaxHeight(long self) {
        return f32(self, TEXT_LAYOUT_GET_MAX_HEIGHT);
    }

    private static float f32(long self, int index) {
        MemorySegment obj = com(self);
        try {
            return (float) F32_THIS.invokeExact(slot(obj, index), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* Array marshalling. Every one of these answers MemorySegment.NULL for a null array, which is
     * what the C's "if (argN)" guards left the pointer at, and allocates a copy otherwise - the
     * behaviour of GetXxxArrayElements with a copying JVM. copyBack is Release with mode 0. */

    static MemorySegment shorts(Arena arena, short[] values) {
        return values == null ? MemorySegment.NULL : arena.allocateFrom(JAVA_SHORT, values);
    }

    private static MemorySegment ints(Arena arena, int[] values) {
        return values == null ? MemorySegment.NULL : arena.allocateFrom(JAVA_INT, values);
    }

    private static MemorySegment longs(Arena arena, long[] values) {
        return values == null ? MemorySegment.NULL : arena.allocateFrom(JAVA_LONG, values);
    }

    static MemorySegment floats(Arena arena, float[] values) {
        return values == null ? MemorySegment.NULL : arena.allocateFrom(JAVA_FLOAT, values);
    }

    private static void copyBack(MemorySegment source, short[] values) {
        if (values != null) {
            MemorySegment.copy(source, JAVA_SHORT, 0, values, 0, values.length);
        }
    }

    private static void copyBack(MemorySegment source, int[] values) {
        if (values != null) {
            MemorySegment.copy(source, JAVA_INT, 0, values, 0, values.length);
        }
    }

    private static void copyBack(MemorySegment source, float[] values) {
        if (values != null) {
            MemorySegment.copy(source, JAVA_FLOAT, 0, values, 0, values.length);
        }
    }

    /**
     * The 8-byte {@code DWRITE_SCRIPT_ANALYSIS} the two shaping calls read, or {@code NULL} for a
     * {@code null} mirror - which is what {@code getDWRITE_SCRIPT_ANALYSISFields} returned for a
     * {@code null} object ({@code directwrite.cpp:2240}) and what DirectWrite then rejected.
     * {@code shapes} carries the composite slot number here rather than a
     * {@code DWRITE_SCRIPT_SHAPES}, a reuse of the field that {@code DWGlyphLayout.java:114} makes
     * and this layer must pass through untouched.
     */
    private static MemorySegment scriptAnalysis(Arena arena, DWRITE_SCRIPT_ANALYSIS holder) {
        if (holder == null) {
            return MemorySegment.NULL;
        }
        MemorySegment analysis = arena.allocate(DWRITE_SCRIPT_ANALYSIS_LAYOUT);
        analysis.set(JAVA_SHORT, 0, holder.script);
        analysis.set(JAVA_INT, 4, holder.shapes);
        return analysis;
    }

    /**
     * The slot numbers of the shaping calls and of the two COM objects they drive, by name, so a test can
     * pin the table it derived.
     */
    static int layoutSlot(String name) {
        return switch (name) {
            case "IDWriteFactory::CreateTextFormat" -> FACTORY_CREATE_TEXT_FORMAT;
            case "IDWriteFactory::CreateTextLayout" -> FACTORY_CREATE_TEXT_LAYOUT;
            case "IDWriteFactory::CreateTextAnalyzer" -> FACTORY_CREATE_TEXT_ANALYZER;
            case "IDWriteTextAnalyzer::AnalyzeScript" -> ANALYZER_ANALYZE_SCRIPT;
            case "IDWriteTextAnalyzer::AnalyzeBidi" -> ANALYZER_ANALYZE_BIDI;
            case "IDWriteTextAnalyzer::AnalyzeNumberSubstitution" -> ANALYZER_ANALYZE_NUMBER_SUBSTITUTION;
            case "IDWriteTextAnalyzer::AnalyzeLineBreakpoints" -> ANALYZER_ANALYZE_LINE_BREAKPOINTS;
            case "IDWriteTextAnalyzer::GetGlyphs" -> ANALYZER_GET_GLYPHS;
            case "IDWriteTextAnalyzer::GetGlyphPlacements" -> ANALYZER_GET_GLYPH_PLACEMENTS;
            case "IDWriteTextFormat::GetFontFamilyNameLength" -> TEXT_FORMAT_GET_FONT_FAMILY_NAME_LENGTH;
            case "IDWriteTextFormat::GetFontFamilyName" -> TEXT_FORMAT_GET_FONT_FAMILY_NAME;
            case "IDWriteTextFormat::GetFontWeight" -> TEXT_FORMAT_GET_FONT_WEIGHT;
            case "IDWriteTextFormat::GetFontStyle" -> TEXT_FORMAT_GET_FONT_STYLE;
            case "IDWriteTextFormat::GetFontStretch" -> TEXT_FORMAT_GET_FONT_STRETCH;
            case "IDWriteTextFormat::GetFontSize" -> TEXT_FORMAT_GET_FONT_SIZE;
            case "IDWriteTextFormat::GetLocaleNameLength" -> TEXT_FORMAT_GET_LOCALE_NAME_LENGTH;
            case "IDWriteTextFormat::GetLocaleName" -> TEXT_FORMAT_GET_LOCALE_NAME;
            case "IDWriteTextLayout::GetMaxWidth" -> TEXT_LAYOUT_GET_MAX_WIDTH;
            case "IDWriteTextLayout::GetMaxHeight" -> TEXT_LAYOUT_GET_MAX_HEIGHT;
            case "IDWriteTextLayout::Draw" -> TEXT_LAYOUT_DRAW;
            case "IDWriteTextAnalysisSink::SetScriptAnalysis" -> ANALYSIS_SINK_SET_SCRIPT_ANALYSIS;
            case "IDWriteTextAnalysisSink::SetLineBreakpoints" -> ANALYSIS_SINK_SET_LINE_BREAKPOINTS;
            case "IDWriteTextAnalysisSink::SetBidiLevel" -> ANALYSIS_SINK_SET_BIDI_LEVEL;
            case "IDWriteTextAnalysisSink::SetNumberSubstitution" -> ANALYSIS_SINK_SET_NUMBER_SUBSTITUTION;
            case "IDWriteTextAnalysisSource::GetTextAtPosition" -> ANALYSIS_SOURCE_GET_TEXT_AT_POSITION;
            case "IDWriteTextAnalysisSource::GetTextBeforePosition" -> ANALYSIS_SOURCE_GET_TEXT_BEFORE_POSITION;
            case "IDWriteTextAnalysisSource::GetParagraphReadingDirection" -> ANALYSIS_SOURCE_GET_READING_DIRECTION;
            case "IDWriteTextAnalysisSource::GetLocaleName" -> ANALYSIS_SOURCE_GET_LOCALE_NAME;
            case "IDWriteTextAnalysisSource::GetNumberSubstitution" -> ANALYSIS_SOURCE_GET_NUMBER_SUBSTITUTION;
            case "IDWritePixelSnapping::IsPixelSnappingDisabled" -> RENDERER_IS_PIXEL_SNAPPING_DISABLED;
            case "IDWritePixelSnapping::GetCurrentTransform" -> RENDERER_GET_CURRENT_TRANSFORM;
            case "IDWritePixelSnapping::GetPixelsPerDip" -> RENDERER_GET_PIXELS_PER_DIP;
            case "IDWriteTextRenderer::DrawGlyphRun" -> RENDERER_DRAW_GLYPH_RUN;
            case "IDWriteTextRenderer::DrawUnderline" -> RENDERER_DRAW_UNDERLINE;
            case "IDWriteTextRenderer::DrawStrikethrough" -> RENDERER_DRAW_STRIKETHROUGH;
            case "IDWriteTextRenderer::DrawInlineObject" -> RENDERER_DRAW_INLINE_OBJECT;
            default -> throw new IllegalArgumentException("no such slot: " + name);
        };
    }
}
