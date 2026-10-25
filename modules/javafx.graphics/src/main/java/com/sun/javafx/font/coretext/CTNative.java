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
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * CoreText. The initialiser starts with {@link CGNative#link()}, which initialises {@link CFNative} and then
 * {@link CGNative}; only then is CoreText opened. So {@link #link()} binds CoreFoundation, CoreGraphics and CoreText,
 * in that order, or fails with an {@link UnsatisfiedLinkError} naming what is missing, and every thread takes the
 * three initialisation locks in that one order.
 * <p>
 * Every method that shares its name with a native entry point of {@code OS}, {@code MacFontFinder} or
 * {@code DFontDecoder} has a {@code MemorySegment} in its descriptor, and no entry point does, so the binding
 * witness of the macOS goldens never takes one for a second binding of an entry point.
 */
public final class CTNative {

    public static final int kCTFontUIFontSystem = 2;
    public static final int kCTFontUIFontEmphasizedSystem = 3;
    public static final int kCTFontTableOptionNoOptions = 0;
    public static final int kCTFontFormatOpenTypePostScript = 1;
    public static final int kCTFontFormatOpenTypeTrueType = 2;
    public static final int kCTFontFormatTrueType = 3;

    static final int kCTParagraphStyleSpecifierBaseWritingDirection = 13;
    static final int kCTFontTableLoca = 0x6c6f6361;
    static final int kCTFontTableGlyf = 0x676c7966;

    static final StructLayout CTPARAGRAPHSTYLESETTING = MemoryLayout.structLayout(
            JAVA_INT.withName("spec"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("valueSize"),
            ADDRESS.withName("value"));

    static {
        CGNative.link();
    }

    private static final CFNative.Framework CT = CFNative.framework("CoreText");

    private static final MethodHandle CT_FONT_COLLECTION_CREATE_FROM_AVAILABLE_FONTS = CT.bind(
            "CTFontCollectionCreateFromAvailableFonts", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_COLLECTION_CREATE_MATCHING_FONT_DESCRIPTORS = CT.bind(
            "CTFontCollectionCreateMatchingFontDescriptors", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_COPY_ATTRIBUTE = CT.bind("CTFontCopyAttribute",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_COPY_AVAILABLE_TABLES = CT.bind("CTFontCopyAvailableTables",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle CT_FONT_COPY_DEFAULT_CASCADE_LIST_FOR_LANGUAGES = CT.bind(
            "CTFontCopyDefaultCascadeListForLanguages", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_COPY_FAMILY_NAME = CT.bind("CTFontCopyFamilyName",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_COPY_FONT_DESCRIPTOR = CT.bind("CTFontCopyFontDescriptor",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_COPY_TABLE = CT.bind("CTFontCopyTable",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle CT_FONT_CREATE_COPY_WITH_ATTRIBUTES = CT.bind("CTFontCreateCopyWithAttributes",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS, ADDRESS));
    // CGGlyph by value is JAVA_CHAR: Apple arm64 leaves the extension of sub-int values to the caller, so a
    // JAVA_SHORT glyph above 0x7FFF reaches CoreText sign-extended (R-1).
    private static final MethodHandle CT_FONT_CREATE_PATH_FOR_GLYPH = CT.bind("CTFontCreatePathForGlyph",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_CHAR, ADDRESS));
    private static final MethodHandle CT_FONT_CREATE_UI_FONT_FOR_LANGUAGE = CT.bind("CTFontCreateUIFontForLanguage",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_DOUBLE, ADDRESS));
    private static final MethodHandle CT_FONT_CREATE_WITH_FONT_DESCRIPTOR = CT.bind("CTFontCreateWithFontDescriptor",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS));
    private static final MethodHandle CT_FONT_CREATE_WITH_GRAPHICS_FONT = CT.bind("CTFontCreateWithGraphicsFont",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_CREATE_WITH_NAME = CT.bind("CTFontCreateWithName",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS));
    private static final MethodHandle CT_FONT_DESCRIPTOR_COPY_ATTRIBUTE = CT.bind("CTFontDescriptorCopyAttribute",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_DRAW_GLYPHS = CT.bind("CTFontDrawGlyphs",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle CT_FONT_GET_ADVANCES_FOR_GLYPHS = CT.bind("CTFontGetAdvancesForGlyphs",
            FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CT_FONT_GET_BOUNDING_RECTS_FOR_GLYPHS = CT.bind(
            "CTFontGetBoundingRectsForGlyphs",
            FunctionDescriptor.of(CGNative.CGRECT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CT_FONT_GET_SIZE = CT.bind("CTFontGetSize",
            FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
    // C bool is one byte: JAVA_BOOLEAN, never JAVA_BYTE or JAVA_INT (R-2).
    private static final MethodHandle CT_FONT_MANAGER_REGISTER_FONTS_FOR_URL = CT.bind(
            "CTFontManagerRegisterFontsForURL", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CT_LINE_CREATE_WITH_ATTRIBUTED_STRING = CT.bind(
            "CTLineCreateWithAttributedString", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_LINE_GET_GLYPH_COUNT = CT.bind("CTLineGetGlyphCount",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CT_LINE_GET_GLYPH_RUNS = CT.bind("CTLineGetGlyphRuns",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_LINE_GET_TYPOGRAPHIC_BOUNDS = CT.bind("CTLineGetTypographicBounds",
            FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CT_PARAGRAPH_STYLE_CREATE = CT.bind("CTParagraphStyleCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CT_RUN_GET_ATTRIBUTES = CT.bind("CTRunGetAttributes",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_RUN_GET_GLYPH_COUNT = CT.bind("CTRunGetGlyphCount",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CT_RUN_GET_GLYPHS = CT.bind("CTRunGetGlyphs",
            FunctionDescriptor.ofVoid(ADDRESS, CFNative.CFRANGE, ADDRESS));
    private static final MethodHandle CT_RUN_GET_GLYPHS_PTR = CT.bind("CTRunGetGlyphsPtr",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_RUN_GET_POSITIONS = CT.bind("CTRunGetPositions",
            FunctionDescriptor.ofVoid(ADDRESS, CFNative.CFRANGE, ADDRESS));
    private static final MethodHandle CT_RUN_GET_POSITIONS_PTR = CT.bind("CTRunGetPositionsPtr",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CT_RUN_GET_STRING_INDICES = CT.bind("CTRunGetStringIndices",
            FunctionDescriptor.ofVoid(ADDRESS, CFNative.CFRANGE, ADDRESS));
    private static final MethodHandle CT_RUN_GET_STRING_INDICES_PTR = CT.bind("CTRunGetStringIndicesPtr",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    public static final MemorySegment kCTFontURLAttribute = CT.dataValue("kCTFontURLAttribute");
    public static final MemorySegment kCTFontDisplayNameAttribute = CT.dataValue("kCTFontDisplayNameAttribute");
    public static final MemorySegment kCTFontFamilyNameAttribute = CT.dataValue("kCTFontFamilyNameAttribute");
    public static final MemorySegment kCTFontNameAttribute = CT.dataValue("kCTFontNameAttribute");
    public static final MemorySegment kCTFontFormatAttribute = CT.dataValue("kCTFontFormatAttribute");
    static final MemorySegment kCTFontAttributeName = CT.dataValue("kCTFontAttributeName");
    static final MemorySegment kCTParagraphStyleAttributeName = CT.dataValue("kCTParagraphStyleAttributeName");

    private CTNative() {
    }

    /**
     * Binds CoreFoundation, CoreGraphics and CoreText, in that order, if they are not bound yet: calling it
     * initialises this class, whose initialiser initialises the other two first.
     */
    public static void link() {
    }

    static Map<String, FunctionDescriptor> boundFunctions() {
        return CT.functions();
    }

    static List<String> boundData() {
        return CT.data();
    }

    @SuppressWarnings("restricted")
    private static MemorySegment bounded(MemorySegment segment, long byteSize) {
        return segment.address() == 0 ? segment : segment.reinterpret(byteSize);
    }

    public static MemorySegment CTFontCollectionCreateFromAvailableFonts(MemorySegment options) {
        try {
            return (MemorySegment) CT_FONT_COLLECTION_CREATE_FROM_AVAILABLE_FONTS.invokeExact(options);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontCollectionCreateMatchingFontDescriptors(MemorySegment collection) {
        try {
            return (MemorySegment) CT_FONT_COLLECTION_CREATE_MATCHING_FONT_DESCRIPTORS.invokeExact(collection);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontCopyAttribute(MemorySegment font, MemorySegment attribute) {
        try {
            return (MemorySegment) CT_FONT_COPY_ATTRIBUTE.invokeExact(font, attribute);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** The array's elements are table tags stored as pointer values, not CF objects. */
    public static MemorySegment CTFontCopyAvailableTables(MemorySegment font, int options) {
        try {
            return (MemorySegment) CT_FONT_COPY_AVAILABLE_TABLES.invokeExact(font, options);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontCopyDefaultCascadeListForLanguages(MemorySegment font, MemorySegment languages) {
        try {
            return (MemorySegment) CT_FONT_COPY_DEFAULT_CASCADE_LIST_FOR_LANGUAGES.invokeExact(font, languages);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontCopyFamilyName(MemorySegment font) {
        try {
            return (MemorySegment) CT_FONT_COPY_FAMILY_NAME.invokeExact(font);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontCopyFontDescriptor(MemorySegment font) {
        try {
            return (MemorySegment) CT_FONT_COPY_FONT_DESCRIPTOR.invokeExact(font);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontCopyTable(MemorySegment font, int tag, int options) {
        try {
            return (MemorySegment) CT_FONT_COPY_TABLE.invokeExact(font, tag, options);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CTFontCreateCopyWithAttributes(MemorySegment font, double size, MemorySegment matrix,
                                                        MemorySegment attributes) {
        try {
            return (MemorySegment) CT_FONT_CREATE_COPY_WITH_ATTRIBUTES.invokeExact(font, size, matrix, attributes);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CTFontCreatePathForGlyph(MemorySegment font, char glyph, MemorySegment matrix) {
        try {
            return (MemorySegment) CT_FONT_CREATE_PATH_FOR_GLYPH.invokeExact(font, glyph, matrix);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontCreateUIFontForLanguage(int uiType, double size, MemorySegment language) {
        try {
            return (MemorySegment) CT_FONT_CREATE_UI_FONT_FOR_LANGUAGE.invokeExact(uiType, size, language);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontCreateWithFontDescriptor(MemorySegment descriptor, double size,
                                                               MemorySegment matrix) {
        try {
            return (MemorySegment) CT_FONT_CREATE_WITH_FONT_DESCRIPTOR.invokeExact(descriptor, size, matrix);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CTFontCreateWithGraphicsFont(MemorySegment cgFont, double size, MemorySegment matrix,
                                                      MemorySegment attributes) {
        try {
            return (MemorySegment) CT_FONT_CREATE_WITH_GRAPHICS_FONT.invokeExact(cgFont, size, matrix, attributes);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CTFontCreateWithName(MemorySegment name, double size, MemorySegment matrix) {
        try {
            return (MemorySegment) CT_FONT_CREATE_WITH_NAME.invokeExact(name, size, matrix);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static MemorySegment CTFontDescriptorCopyAttribute(MemorySegment descriptor, MemorySegment attribute) {
        try {
            return (MemorySegment) CT_FONT_DESCRIPTOR_COPY_ATTRIBUTE.invokeExact(descriptor, attribute);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CTFontDrawGlyphs(MemorySegment font, MemorySegment glyphs, MemorySegment positions, long count,
                                 MemorySegment context) {
        try {
            CT_FONT_DRAW_GLYPHS.invokeExact(font, glyphs, positions, count, context);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static double CTFontGetAdvancesForGlyphs(MemorySegment font, int orientation, MemorySegment glyphs,
                                             MemorySegment advances, long count) {
        try {
            return (double) CT_FONT_GET_ADVANCES_FOR_GLYPHS.invokeExact(font, orientation, glyphs, advances, count);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** A {@code CGRECT} segment from {@code allocator}. */
    static MemorySegment CTFontGetBoundingRectsForGlyphs(SegmentAllocator allocator, MemorySegment font,
                                                         int orientation, MemorySegment glyphs, MemorySegment rects,
                                                         long count) {
        try {
            return (MemorySegment) CT_FONT_GET_BOUNDING_RECTS_FOR_GLYPHS.invokeExact(allocator, font, orientation,
                                                                                     glyphs, rects, count);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    public static double CTFontGetSize(MemorySegment font) {
        try {
            return (double) CT_FONT_GET_SIZE.invokeExact(font);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static boolean CTFontManagerRegisterFontsForURL(MemorySegment url, int scope, MemorySegment error) {
        try {
            return (boolean) CT_FONT_MANAGER_REGISTER_FONTS_FOR_URL.invokeExact(url, scope, error);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CTLineCreateWithAttributedString(MemorySegment string) {
        try {
            return (MemorySegment) CT_LINE_CREATE_WITH_ATTRIBUTED_STRING.invokeExact(string);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static long CTLineGetGlyphCount(MemorySegment line) {
        try {
            return (long) CT_LINE_GET_GLYPH_COUNT.invokeExact(line);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** Borrowed from the line. */
    static MemorySegment CTLineGetGlyphRuns(MemorySegment line) {
        try {
            return (MemorySegment) CT_LINE_GET_GLYPH_RUNS.invokeExact(line);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static double CTLineGetTypographicBounds(MemorySegment line, MemorySegment ascent, MemorySegment descent,
                                             MemorySegment leading) {
        try {
            return (double) CT_LINE_GET_TYPOGRAPHIC_BOUNDS.invokeExact(line, ascent, descent, leading);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static MemorySegment CTParagraphStyleCreate(MemorySegment settings, long count) {
        try {
            return (MemorySegment) CT_PARAGRAPH_STYLE_CREATE.invokeExact(settings, count);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** Borrowed from the run. */
    static MemorySegment CTRunGetAttributes(MemorySegment run) {
        try {
            return (MemorySegment) CT_RUN_GET_ATTRIBUTES.invokeExact(run);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static long CTRunGetGlyphCount(MemorySegment run) {
        try {
            return (long) CT_RUN_GET_GLYPH_COUNT.invokeExact(run);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** {@code range} is a {@code CFRANGE} segment, passed by value. */
    static void CTRunGetGlyphs(MemorySegment run, MemorySegment range, MemorySegment buffer) {
        try {
            CT_RUN_GET_GLYPHS.invokeExact(run, range, buffer);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** {@code count} glyphs readable while the run's line lives, or NULL when the run keeps none. */
    static MemorySegment CTRunGetGlyphsPtr(MemorySegment run, long count) {
        try {
            return bounded((MemorySegment) CT_RUN_GET_GLYPHS_PTR.invokeExact(run), count * JAVA_CHAR.byteSize());
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CTRunGetPositions(MemorySegment run, MemorySegment range, MemorySegment buffer) {
        try {
            CT_RUN_GET_POSITIONS.invokeExact(run, range, buffer);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** {@code count} positions readable while the run's line lives, or NULL when the run keeps none. */
    static MemorySegment CTRunGetPositionsPtr(MemorySegment run, long count) {
        try {
            return bounded((MemorySegment) CT_RUN_GET_POSITIONS_PTR.invokeExact(run),
                           count * CGNative.CGPOINT.byteSize());
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    static void CTRunGetStringIndices(MemorySegment run, MemorySegment range, MemorySegment buffer) {
        try {
            CT_RUN_GET_STRING_INDICES.invokeExact(run, range, buffer);
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }

    /** {@code count} indices readable while the run's line lives, or NULL when the run keeps none. */
    static MemorySegment CTRunGetStringIndicesPtr(MemorySegment run, long count) {
        try {
            return bounded((MemorySegment) CT_RUN_GET_STRING_INDICES_PTR.invokeExact(run),
                           count * JAVA_LONG.byteSize());
        } catch (Throwable t) {
            throw CFNative.unexpected(t);
        }
    }
}
