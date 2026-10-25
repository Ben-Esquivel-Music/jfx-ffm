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

package com.sun.javafx.font;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * An independent observer of CoreFoundation, CoreText and CoreGraphics for the macOS font tests, with its own
 * bindings and no font class of the product ({@code MacFontBindings.requireSameBinding} checks): it reads the CF
 * objects the natives return as bare {@code long}s, builds test inputs, and carries the C ABI probes of
 * {@code MacFontAbiTest}. Objects it creates it releases; objects it is handed it only reads.
 * <p>
 * The frameworks live only in the dyld shared cache, so they are opened by install name with the {@code String}
 * overload of {@link SymbolLookup#libraryLookup}; the {@code Path} overload fails.
 */
@SuppressWarnings("restricted")
public final class MacFontOracleShim {

    public static final String CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    public static final String CORE_TEXT = "/System/Library/Frameworks/CoreText.framework/CoreText";
    public static final String CORE_GRAPHICS = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";

    public static final StructLayout CG_POINT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"), JAVA_DOUBLE.withName("y"));

    public static final StructLayout CG_SIZE = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("width"), JAVA_DOUBLE.withName("height"));

    public static final StructLayout CG_RECT = MemoryLayout.structLayout(
            CG_POINT.withName("origin"), CG_SIZE.withName("size"));

    public static final StructLayout CG_AFFINE_TRANSFORM = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("a"), JAVA_DOUBLE.withName("b"), JAVA_DOUBLE.withName("c"),
            JAVA_DOUBLE.withName("d"), JAVA_DOUBLE.withName("tx"), JAVA_DOUBLE.withName("ty"));

    public static final StructLayout CF_RANGE = MemoryLayout.structLayout(
            JAVA_LONG.withName("location"), JAVA_LONG.withName("length"));

    public static final StructLayout CG_PATH_ELEMENT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4), ADDRESS.withName("points"));

    public static final StructLayout CT_PARAGRAPH_STYLE_SETTING = MemoryLayout.structLayout(
            JAVA_INT.withName("spec"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("valueSize"),
            ADDRESS.withName("value"));

    /** {@code kCTParagraphStyleSpecifierBaseWritingDirection}. */
    public static final int BASE_WRITING_DIRECTION = 13;

    static final Linker LINKER = Linker.nativeLinker();

    private static final ThreadLocal<List<String>> PATH_SINK = new ThreadLocal<>();

    private MacFontOracleShim() {
    }

    static SymbolLookup load(String installName) {
        return SymbolLookup.libraryLookup(installName, Arena.global());
    }

    static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(library.findOrThrow(name), descriptor);
    }

    static MemorySegment dataValue(SymbolLookup library, String name) {
        return library.findOrThrow(name).reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    }

    static final class Lib {
        static final SymbolLookup CF = load(CORE_FOUNDATION);
        static final SymbolLookup CT = load(CORE_TEXT);
        static final SymbolLookup CG = load(CORE_GRAPHICS);

        static final MethodHandle CF_RELEASE = bind(CF, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle CF_GET_RETAIN_COUNT = bind(CF, "CFGetRetainCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_GET_TYPE_ID = bind(CF, "CFGetTypeID", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_COPY_TYPE_ID_DESCRIPTION = bind(CF, "CFCopyTypeIDDescription",
                FunctionDescriptor.of(ADDRESS, JAVA_LONG));
        static final MethodHandle CF_COPY_DESCRIPTION = bind(CF, "CFCopyDescription",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_STRING_GET_LENGTH = bind(CF, "CFStringGetLength",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_STRING_GET_CHARACTERS = bind(CF, "CFStringGetCharacters",
                FunctionDescriptor.ofVoid(ADDRESS, CF_RANGE, ADDRESS));
        static final MethodHandle CF_STRING_CREATE_WITH_CHARACTERS = bind(CF, "CFStringCreateWithCharacters",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_STRING_FIND = bind(CF, "CFStringFind",
                FunctionDescriptor.of(CF_RANGE, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_ARRAY_GET_COUNT = bind(CF, "CFArrayGetCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_ARRAY_GET_VALUE_AT_INDEX = bind(CF, "CFArrayGetValueAtIndex",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_DICTIONARY_GET_COUNT = bind(CF, "CFDictionaryGetCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_DICTIONARY_GET_VALUE = bind(CF, "CFDictionaryGetValue",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CF_URL_GET_STRING = bind(CF, "CFURLGetString",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_URL_COPY_FILE_SYSTEM_PATH = bind(CF, "CFURLCopyFileSystemPath",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_URL_HAS_DIRECTORY_PATH = bind(CF, "CFURLHasDirectoryPath",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
        static final MethodHandle CF_URL_CREATE_WITH_FILE_SYSTEM_PATH = bind(CF, "CFURLCreateWithFileSystemPath",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_BOOLEAN));
        static final MethodHandle CF_ATTRIBUTED_STRING_GET_STRING = bind(CF, "CFAttributedStringGetString",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_ATTRIBUTED_STRING_GET_LENGTH = bind(CF, "CFAttributedStringGetLength",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_ATTRIBUTED_STRING_GET_ATTRIBUTES = bind(CF, "CFAttributedStringGetAttributes",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        static final MethodHandle CF_DATA_GET_LENGTH = bind(CF, "CFDataGetLength",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_DATA_GET_BYTE_PTR = bind(CF, "CFDataGetBytePtr",
                FunctionDescriptor.of(ADDRESS, ADDRESS));

        static final MethodHandle CT_FONT_COPY_POSTSCRIPT_NAME = bind(CT, "CTFontCopyPostScriptName",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CT_FONT_GET_SIZE = bind(CT, "CTFontGetSize",
                FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
        static final MethodHandle CT_FONT_GET_MATRIX = bind(CT, "CTFontGetMatrix",
                FunctionDescriptor.of(CG_AFFINE_TRANSFORM, ADDRESS));
        static final MethodHandle CT_FONT_GET_GLYPH_COUNT = bind(CT, "CTFontGetGlyphCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CT_FONT_COPY_TABLE = bind(CT, "CTFontCopyTable",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        static final MethodHandle CT_FONT_CREATE_WITH_NAME = bind(CT, "CTFontCreateWithName",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS));
        static final MethodHandle CT_FONT_CREATE_UI_FONT_FOR_LANGUAGE = bind(CT, "CTFontCreateUIFontForLanguage",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_DOUBLE, ADDRESS));
        static final MethodHandle CT_FONT_COPY_GRAPHICS_FONT = bind(CT, "CTFontCopyGraphicsFont",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        /** {@code CGGlyph} by value as {@code JAVA_CHAR}: Apple arm64 leaves sub-int extension to the caller. */
        static final MethodHandle CT_FONT_CREATE_PATH_FOR_GLYPH = bind(CT, "CTFontCreatePathForGlyph",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_CHAR, ADDRESS));
        static final MethodHandle CT_PARAGRAPH_STYLE_CREATE = bind(CT, "CTParagraphStyleCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CT_PARAGRAPH_STYLE_GET_VALUE_FOR_SPECIFIER = bind(CT,
                "CTParagraphStyleGetValueForSpecifier",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
        static final MethodHandle CT_RUN_GET_GLYPH_COUNT = bind(CT, "CTRunGetGlyphCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));

        static final MethodHandle CG_RECT_APPLY_AFFINE_TRANSFORM = bind(CG, "CGRectApplyAffineTransform",
                FunctionDescriptor.of(CG_RECT, CG_RECT, CG_AFFINE_TRANSFORM));
        static final MethodHandle CG_RECT_CONTAINS_POINT = bind(CG, "CGRectContainsPoint",
                FunctionDescriptor.of(JAVA_BOOLEAN, CG_RECT, CG_POINT));
        static final MethodHandle CG_PATH_CREATE_MUTABLE = bind(CG, "CGPathCreateMutable",
                FunctionDescriptor.of(ADDRESS));
        static final MethodHandle CG_PATH_MOVE_TO_POINT = bind(CG, "CGPathMoveToPoint",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));
        static final MethodHandle CG_PATH_ADD_LINE_TO_POINT = bind(CG, "CGPathAddLineToPoint",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));
        static final MethodHandle CG_PATH_ADD_QUAD_CURVE_TO_POINT = bind(CG, "CGPathAddQuadCurveToPoint",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        static final MethodHandle CG_PATH_ADD_CURVE_TO_POINT = bind(CG, "CGPathAddCurveToPoint",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                                          JAVA_DOUBLE, JAVA_DOUBLE));
        static final MethodHandle CG_PATH_CLOSE_SUBPATH = bind(CG, "CGPathCloseSubpath",
                FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle CG_PATH_RELEASE = bind(CG, "CGPathRelease", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle CG_PATH_APPLY = bind(CG, "CGPathApply",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CG_BITMAP_CONTEXT_GET_WIDTH = bind(CG, "CGBitmapContextGetWidth",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CG_BITMAP_CONTEXT_GET_HEIGHT = bind(CG, "CGBitmapContextGetHeight",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CG_BITMAP_CONTEXT_GET_BYTES_PER_ROW = bind(CG, "CGBitmapContextGetBytesPerRow",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CG_BITMAP_CONTEXT_GET_BITS_PER_PIXEL = bind(CG, "CGBitmapContextGetBitsPerPixel",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CG_BITMAP_CONTEXT_GET_BITS_PER_COMPONENT = bind(CG,
                "CGBitmapContextGetBitsPerComponent", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CG_BITMAP_CONTEXT_GET_BITMAP_INFO = bind(CG, "CGBitmapContextGetBitmapInfo",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle CG_BITMAP_CONTEXT_GET_COLOR_SPACE = bind(CG, "CGBitmapContextGetColorSpace",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CG_CONTEXT_GET_CTM = bind(CG, "CGContextGetCTM",
                FunctionDescriptor.of(CG_AFFINE_TRANSFORM, ADDRESS));
        static final MethodHandle CG_COLOR_SPACE_GET_MODEL = bind(CG, "CGColorSpaceGetModel",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle CG_COLOR_SPACE_GET_NUMBER_OF_COMPONENTS = bind(CG,
                "CGColorSpaceGetNumberOfComponents", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CG_FONT_COPY_POSTSCRIPT_NAME = bind(CG, "CGFontCopyPostScriptName",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CG_FONT_GET_NUMBER_OF_GLYPHS = bind(CG, "CGFontGetNumberOfGlyphs",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CG_DATA_PROVIDER_COPY_DATA = bind(CG, "CGDataProviderCopyData",
                FunctionDescriptor.of(ADDRESS, ADDRESS));

        static final MemorySegment K_CF_ALLOCATOR_DEFAULT = dataValue(CF, "kCFAllocatorDefault");
        static final MemorySegment K_CF_TYPE_DICTIONARY_KEY_CALL_BACKS =
                CF.findOrThrow("kCFTypeDictionaryKeyCallBacks");
        static final MemorySegment K_CF_TYPE_DICTIONARY_VALUE_CALL_BACKS =
                CF.findOrThrow("kCFTypeDictionaryValueCallBacks");
        static final MemorySegment K_CT_FONT_ATTRIBUTE_NAME = dataValue(CT, "kCTFontAttributeName");
        static final MemorySegment K_CT_PARAGRAPH_STYLE_ATTRIBUTE_NAME =
                dataValue(CT, "kCTParagraphStyleAttributeName");
        static final MemorySegment K_CT_FONT_URL_ATTRIBUTE = dataValue(CT, "kCTFontURLAttribute");
        static final MemorySegment K_CT_FONT_DISPLAY_NAME_ATTRIBUTE = dataValue(CT, "kCTFontDisplayNameAttribute");
        static final MemorySegment K_CT_FONT_FAMILY_NAME_ATTRIBUTE = dataValue(CT, "kCTFontFamilyNameAttribute");
        static final MemorySegment K_CT_FONT_NAME_ATTRIBUTE = dataValue(CT, "kCTFontNameAttribute");
        static final MemorySegment K_CT_FONT_FORMAT_ATTRIBUTE = dataValue(CT, "kCTFontFormatAttribute");
        static final MemorySegment CG_AFFINE_TRANSFORM_IDENTITY = CG.findOrThrow("CGAffineTransformIdentity");
        static final MethodHandle CG_PATH_GET_PATH_BOUNDING_BOX = bind(CG, "CGPathGetPathBoundingBox",
                FunctionDescriptor.of(CG_RECT, ADDRESS));
        static final MethodHandle CT_FONT_GET_BOUNDING_RECTS_FOR_GLYPHS = bind(CT, "CTFontGetBoundingRectsForGlyphs",
                FunctionDescriptor.of(CG_RECT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_DATA_CREATE = bind(CF, "CFDataCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_NUMBER_CREATE = bind(CF, "CFNumberCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));

        static final MemorySegment PATH_APPLIER = pathApplier();
    }

    private static MemorySegment pathApplier() {
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(MacFontOracleShim.class, "applyElement",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
            return LINKER.upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static RuntimeException unexpected(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    private static MemorySegment at(long address) {
        return MemorySegment.ofAddress(address);
    }

    /** Opens the three frameworks: false off macOS; on macOS a failing lookup or binding propagates. */
    public static boolean available() {
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            return false;
        }
        try {
            Class.forName(Lib.class.getName(), true, MacFontOracleShim.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    static MemorySegment createString(String text) {
        try (Arena scratch = Arena.ofConfined()) {
            char[] chars = text.toCharArray();
            MemorySegment units = scratch.allocateFrom(JAVA_CHAR, chars);
            return (MemorySegment) Lib.CF_STRING_CREATE_WITH_CHARACTERS.invokeExact(Lib.K_CF_ALLOCATOR_DEFAULT, units,
                                                                                (long) chars.length);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static String string(MemorySegment cfString) throws Throwable {
        if (cfString.address() == 0) {
            return null;
        }
        long length = (long) Lib.CF_STRING_GET_LENGTH.invokeExact(cfString);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment range = scratch.allocate(CF_RANGE);
            range.set(JAVA_LONG, 0, 0L);
            range.set(JAVA_LONG, 8, length);
            MemorySegment units = scratch.allocate(JAVA_CHAR, Math.max(1, length));
            Lib.CF_STRING_GET_CHARACTERS.invokeExact(cfString, range, units);
            return new String(units.asSlice(0, length * 2).toArray(JAVA_CHAR));
        }
    }

    static String describe(MemorySegment object) throws Throwable {
        MemorySegment description = (MemorySegment) Lib.CF_COPY_DESCRIPTION.invokeExact(object);
        String text = string(description);
        release(description);
        return text;
    }

    static void release(MemorySegment object) {
        if (object.address() == 0) {
            return;
        }
        try {
            Lib.CF_RELEASE.invokeExact(object);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static String cfString(long cfString) {
        try {
            return string(at(cfString));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static String cfTypeName(long object) {
        if (object == 0) {
            return null;
        }
        try {
            long typeId = (long) Lib.CF_GET_TYPE_ID.invokeExact(at(object));
            MemorySegment name = (MemorySegment) Lib.CF_COPY_TYPE_ID_DESCRIPTION.invokeExact(typeId);
            String text = string(name);
            release(name);
            return text;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long retainCount(long object) {
        try {
            return (long) Lib.CF_GET_RETAIN_COUNT.invokeExact(at(object));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static void release(long object) {
        release(at(object));
    }

    static MemorySegment arrayValue(MemorySegment array, long index) throws Throwable {
        return (MemorySegment) Lib.CF_ARRAY_GET_VALUE_AT_INDEX.invokeExact(array, index);
    }

    public static long arrayCount(long array) {
        try {
            return (long) Lib.CF_ARRAY_GET_COUNT.invokeExact(at(array));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long dictionaryCount(long dictionary) {
        try {
            return (long) Lib.CF_DICTIONARY_GET_COUNT.invokeExact(at(dictionary));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static String urlString(long url) {
        if (url == 0) {
            return null;
        }
        try {
            return string((MemorySegment) Lib.CF_URL_GET_STRING.invokeExact(at(url)));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static String urlPath(long url) {
        if (url == 0) {
            return null;
        }
        try {
            MemorySegment path = (MemorySegment) Lib.CF_URL_COPY_FILE_SYSTEM_PATH.invokeExact(at(url), 0L);
            String text = string(path);
            release(path);
            return text;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static boolean urlHasDirectoryPath(long url) {
        try {
            return (boolean) Lib.CF_URL_HAS_DIRECTORY_PATH.invokeExact(at(url));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static String attributedText(long attributedString) {
        try {
            return string((MemorySegment) Lib.CF_ATTRIBUTED_STRING_GET_STRING.invokeExact(at(attributedString)));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code {font, paragraphStyle, attributeCount}} at index 0. An empty string has no attributes there, and asking
     * for them reads past the string and crashes the JVM.
     */
    public static long[] attributedAttributes(long attributedString) {
        try {
            long length = (long) Lib.CF_ATTRIBUTED_STRING_GET_LENGTH.invokeExact(at(attributedString));
            if (length == 0) {
                return new long[] {0, 0, -1};
            }
            MemorySegment attributes = (MemorySegment) Lib.CF_ATTRIBUTED_STRING_GET_ATTRIBUTES.invokeExact(
                    at(attributedString), 0L, MemorySegment.NULL);
            if (attributes.address() == 0) {
                return new long[] {0, 0, -1};
            }
            MemorySegment font = (MemorySegment) Lib.CF_DICTIONARY_GET_VALUE.invokeExact(attributes,
                    Lib.K_CT_FONT_ATTRIBUTE_NAME);
            MemorySegment paragraph = (MemorySegment) Lib.CF_DICTIONARY_GET_VALUE.invokeExact(attributes,
                    Lib.K_CT_PARAGRAPH_STYLE_ATTRIBUTE_NAME);
            long count = (long) Lib.CF_DICTIONARY_GET_COUNT.invokeExact(attributes);
            return new long[] {font.address(), paragraph.address(), count};
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The data symbols the font layer reads: {@code name} = value, or address for the two callback structs. */
    public static long dataSymbol(String name) {
        return switch (name) {
            case "kCFAllocatorDefault" -> Lib.K_CF_ALLOCATOR_DEFAULT.address();
            case "kCFTypeDictionaryKeyCallBacks" -> Lib.K_CF_TYPE_DICTIONARY_KEY_CALL_BACKS.address();
            case "kCFTypeDictionaryValueCallBacks" -> Lib.K_CF_TYPE_DICTIONARY_VALUE_CALL_BACKS.address();
            case "kCTFontAttributeName" -> Lib.K_CT_FONT_ATTRIBUTE_NAME.address();
            case "kCTParagraphStyleAttributeName" -> Lib.K_CT_PARAGRAPH_STYLE_ATTRIBUTE_NAME.address();
            case "kCTFontURLAttribute" -> Lib.K_CT_FONT_URL_ATTRIBUTE.address();
            case "kCTFontDisplayNameAttribute" -> Lib.K_CT_FONT_DISPLAY_NAME_ATTRIBUTE.address();
            case "kCTFontFamilyNameAttribute" -> Lib.K_CT_FONT_FAMILY_NAME_ATTRIBUTE.address();
            case "kCTFontNameAttribute" -> Lib.K_CT_FONT_NAME_ATTRIBUTE.address();
            case "kCTFontFormatAttribute" -> Lib.K_CT_FONT_FORMAT_ATTRIBUTE.address();
            case "CGAffineTransformIdentity" -> Lib.CG_AFFINE_TRANSFORM_IDENTITY.address();
            default -> throw new IllegalArgumentException("no data symbol " + name);
        };
    }

    public static double[] pathBoundingBox(long path) {
        try (Arena scratch = Arena.ofConfined()) {
            return ((MemorySegment) Lib.CG_PATH_GET_PATH_BOUNDING_BOX.invokeExact((SegmentAllocator) scratch,
                    at(path))).toArray(JAVA_DOUBLE);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static double[] glyphBoundingRect(long font, char glyph) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment glyphs = scratch.allocateFrom(JAVA_CHAR, glyph);
            return ((MemorySegment) Lib.CT_FONT_GET_BOUNDING_RECTS_FOR_GLYPHS.invokeExact((SegmentAllocator) scratch,
                    at(font), 0, glyphs, MemorySegment.NULL, 1L)).toArray(JAVA_DOUBLE);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The six doubles stored at {@code CGAffineTransformIdentity}. */
    public static double[] identityValues() {
        return Lib.CG_AFFINE_TRANSFORM_IDENTITY.reinterpret(CG_AFFINE_TRANSFORM.byteSize()).toArray(JAVA_DOUBLE);
    }

    /** A +1 CFData holding a copy of {@code bytes}. */
    public static long createData(byte[] bytes) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment copy = scratch.allocateFrom(JAVA_BYTE, bytes);
            long length = bytes.length;
            return ((MemorySegment) Lib.CF_DATA_CREATE.invokeExact(Lib.K_CF_ALLOCATOR_DEFAULT, copy, length))
                    .address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A +1 CFNumber of type {@code kCFNumberSInt32Type}. */
    public static long createNumber(int value) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment slot = scratch.allocateFrom(JAVA_INT, value);
            long sint32 = 3;
            return ((MemorySegment) Lib.CF_NUMBER_CREATE.invokeExact(Lib.K_CF_ALLOCATOR_DEFAULT, sint32, slot))
                    .address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long keyCallBacksVersion() {
        return Lib.K_CF_TYPE_DICTIONARY_KEY_CALL_BACKS.reinterpret(8).get(JAVA_LONG, 0);
    }

    public static String fontPostScriptName(long font) {
        return copiedString(Lib.CT_FONT_COPY_POSTSCRIPT_NAME, font);
    }

    public static String cgFontPostScriptName(long cgFont) {
        return copiedString(Lib.CG_FONT_COPY_POSTSCRIPT_NAME, cgFont);
    }

    private static String copiedString(MethodHandle copy, long object) {
        if (object == 0) {
            return null;
        }
        try {
            MemorySegment value = (MemorySegment) copy.invokeExact(at(object));
            String text = string(value);
            release(value);
            return text;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static double fontSize(long font) {
        try {
            return (double) Lib.CT_FONT_GET_SIZE.invokeExact(at(font));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static double[] fontMatrix(long font) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment m = (MemorySegment) Lib.CT_FONT_GET_MATRIX.invokeExact((SegmentAllocator) scratch, at(font));
            return m.toArray(JAVA_DOUBLE);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long fontGlyphCount(long font) {
        try {
            return (long) Lib.CT_FONT_GET_GLYPH_COUNT.invokeExact(at(font));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long runGlyphCount(long run) {
        try {
            return (long) Lib.CT_RUN_GET_GLYPH_COUNT.invokeExact(at(run));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long cgFontGlyphCount(long cgFont) {
        try {
            return (long) Lib.CG_FONT_GET_NUMBER_OF_GLYPHS.invokeExact(at(cgFont));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code CTFontCopyTable(font, tag, 0)} as bytes; {@code null} when the font has no such table. */
    public static byte[] fontTable(long font, int tag) {
        try {
            MemorySegment data = (MemorySegment) Lib.CT_FONT_COPY_TABLE.invokeExact(at(font), tag, 0);
            if (data.address() == 0) {
                return null;
            }
            byte[] bytes = dataBytes(data);
            release(data);
            return bytes;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The length of {@code CTFontCopyTable(font, tag, 0)} without copying it out; -1 when there is none. */
    public static long fontTableLength(long font, int tag) {
        try {
            MemorySegment data = (MemorySegment) Lib.CT_FONT_COPY_TABLE.invokeExact(at(font), tag, 0);
            if (data.address() == 0) {
                return -1;
            }
            long length = (long) Lib.CF_DATA_GET_LENGTH.invokeExact(data);
            release(data);
            return length;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The CFData {@code CTFontCopyTable(font, tag, 0)} returns, at +1 for the caller to release; 0 when the font has
     * no such table. CoreText hands out its cached table, so its retain count shows a copy that was not released.
     */
    public static long copyFontTable(long font, int tag) {
        try {
            return ((MemorySegment) Lib.CT_FONT_COPY_TABLE.invokeExact(at(font), tag, 0)).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static byte[] dataBytes(MemorySegment data) throws Throwable {
        long length = (long) Lib.CF_DATA_GET_LENGTH.invokeExact(data);
        MemorySegment bytes = (MemorySegment) Lib.CF_DATA_GET_BYTE_PTR.invokeExact(data);
        return length == 0 ? new byte[0] : bytes.reinterpret(length).toArray(JAVA_BYTE);
    }

    /** The length of {@code CGDataProviderCopyData(provider)}, or -1 when it returns NULL. */
    public static long dataProviderLength(long provider) {
        try {
            MemorySegment data = (MemorySegment) Lib.CG_DATA_PROVIDER_COPY_DATA.invokeExact(at(provider));
            if (data.address() == 0) {
                return -1;
            }
            long length = (long) Lib.CF_DATA_GET_LENGTH.invokeExact(data);
            release(data);
            return length;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The base writing direction stored in a {@code CTParagraphStyle}, read with a 1-byte buffer
     * ({@code CTWritingDirection} is {@code int8_t}); {@link Integer#MIN_VALUE} when CoreText reports none.
     */
    public static int paragraphWritingDirection(long style) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment value = scratch.allocate(JAVA_BYTE);
            value.set(JAVA_BYTE, 0, (byte) 0x55);
            boolean ok = (boolean) Lib.CT_PARAGRAPH_STYLE_GET_VALUE_FOR_SPECIFIER.invokeExact(at(style),
                    BASE_WRITING_DIRECTION, 1L, value);
            return ok ? value.get(JAVA_BYTE, 0) : Integer.MIN_VALUE;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code {width, height, bytesPerRow, bitsPerPixel, bitsPerComponent, bitmapInfo, colorSpaceModel}}. */
    public static long[] bitmapContextInfo(long context) {
        try {
            MemorySegment c = at(context);
            MemorySegment space = (MemorySegment) Lib.CG_BITMAP_CONTEXT_GET_COLOR_SPACE.invokeExact(c);
            int model = space.address() == 0 ? -2 : (int) Lib.CG_COLOR_SPACE_GET_MODEL.invokeExact(space);
            return new long[] {
                (long) Lib.CG_BITMAP_CONTEXT_GET_WIDTH.invokeExact(c),
                (long) Lib.CG_BITMAP_CONTEXT_GET_HEIGHT.invokeExact(c),
                (long) Lib.CG_BITMAP_CONTEXT_GET_BYTES_PER_ROW.invokeExact(c),
                (long) Lib.CG_BITMAP_CONTEXT_GET_BITS_PER_PIXEL.invokeExact(c),
                (long) Lib.CG_BITMAP_CONTEXT_GET_BITS_PER_COMPONENT.invokeExact(c),
                (int) Lib.CG_BITMAP_CONTEXT_GET_BITMAP_INFO.invokeExact(c),
                model
            };
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static double[] contextCtm(long context) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment m = (MemorySegment) Lib.CG_CONTEXT_GET_CTM.invokeExact((SegmentAllocator) scratch,
                                                                                at(context));
            return m.toArray(JAVA_DOUBLE);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code {model, numberOfComponents}} of a {@code CGColorSpaceRef}. */
    public static long[] colorSpaceInfo(long space) {
        try {
            MemorySegment s = at(space);
            return new long[] {
                (int) Lib.CG_COLOR_SPACE_GET_MODEL.invokeExact(s),
                (long) Lib.CG_COLOR_SPACE_GET_NUMBER_OF_COMPONENTS.invokeExact(s)
            };
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code CTFontCreateWithName(name, size, NULL)}; the caller releases it with {@link #release(long)}. */
    /** A +1 {@code kCTFontUIFontSystem} or {@code kCTFontUIFontEmphasizedSystem} font, for any language. */
    public static long uiFont(boolean emphasized, double size) {
        try {
            int type = emphasized ? 3 : 2;
            return ((MemorySegment) Lib.CT_FONT_CREATE_UI_FONT_FOR_LANGUAGE.invokeExact(type, size,
                    MemorySegment.NULL)).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The +1 {@code CGFont} under {@code font}. Every live {@code CTFont} over it holds a reference to it. */
    public static long graphicsFont(long font) {
        try {
            return ((MemorySegment) Lib.CT_FONT_COPY_GRAPHICS_FONT.invokeExact(at(font), MemorySegment.NULL))
                    .address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long createFont(String postScriptName, double size) {
        MemorySegment name = createString(postScriptName);
        try {
            MemorySegment font = (MemorySegment) Lib.CT_FONT_CREATE_WITH_NAME.invokeExact(name, size,
                                                                                         MemorySegment.NULL);
            return font.address();
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            release(name);
        }
    }

    /**
     * A {@code CGPath} built element by element: {@code {0, x, y}} move, {@code {1, x, y}} line,
     * {@code {2, cx, cy, x, y}} quad, {@code {3, c1x, c1y, c2x, c2y, x, y}} cubic, {@code {4}} close. The caller
     * releases it with {@link #releasePath(long)}.
     */
    public static long createPath(double[]... elements) {
        try {
            MemorySegment path = (MemorySegment) Lib.CG_PATH_CREATE_MUTABLE.invokeExact();
            MemorySegment none = MemorySegment.NULL;
            for (double[] e : elements) {
                switch ((int) e[0]) {
                    case 0 -> Lib.CG_PATH_MOVE_TO_POINT.invokeExact(path, none, e[1], e[2]);
                    case 1 -> Lib.CG_PATH_ADD_LINE_TO_POINT.invokeExact(path, none, e[1], e[2]);
                    case 2 -> Lib.CG_PATH_ADD_QUAD_CURVE_TO_POINT.invokeExact(path, none, e[1], e[2], e[3], e[4]);
                    case 3 -> {
                        Lib.CG_PATH_ADD_CURVE_TO_POINT.invokeExact(path, none, e[1], e[2], e[3], e[4], e[5], e[6]);
                    }
                    case 4 -> Lib.CG_PATH_CLOSE_SUBPATH.invokeExact(path);
                    default -> throw new IllegalArgumentException("element type " + e[0]);
                }
            }
            return path.address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static void releasePath(long path) {
        try {
            Lib.CG_PATH_RELEASE.invokeExact(at(path));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The elements {@code CGPathApply} reports for {@code path}, through the oracle's own applier and the
     * {@link #CG_PATH_ELEMENT} layout: {@code type} then {@code :x,y} per point, coordinates as
     * {@link Double#toString}.
     */
    public static List<String> applyPath(long path) {
        List<String> elements = new ArrayList<>();
        PATH_SINK.set(elements);
        try {
            Lib.CG_PATH_APPLY.invokeExact(at(path), MemorySegment.NULL, Lib.PATH_APPLIER);
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            PATH_SINK.remove();
        }
        return elements;
    }

    private static void applyElement(MemorySegment info, MemorySegment element) {
        List<String> sink = PATH_SINK.get();
        try {
            MemorySegment e = element.reinterpret(CG_PATH_ELEMENT.byteSize());
            int type = e.get(JAVA_INT, CG_PATH_ELEMENT.byteOffset(PathElement.groupElement("type")));
            int points = switch (type) {
                case 0, 1 -> 1;
                case 2 -> 2;
                case 3 -> 3;
                default -> 0;
            };
            StringBuilder text = new StringBuilder(Integer.toString(type));
            if (points > 0) {
                MemorySegment p = e.get(ADDRESS, CG_PATH_ELEMENT.byteOffset(PathElement.groupElement("points")))
                                   .reinterpret(points * CG_POINT.byteSize());
                for (int i = 0; i < points; i++) {
                    text.append(':').append(p.getAtIndex(JAVA_DOUBLE, 2L * i)).append(',')
                        .append(p.getAtIndex(JAVA_DOUBLE, 2L * i + 1));
                }
            }
            sink.add(text.toString());
        } catch (Throwable t) {
            if (sink != null) {
                sink.add("failed: " + t);
            }
        }
    }

    public static double[] rectApplyAffineTransform(double[] rect, double[] transform) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment r = scratch.allocateFrom(JAVA_DOUBLE, rect);
            MemorySegment t = scratch.allocateFrom(JAVA_DOUBLE, transform);
            MemorySegment result = (MemorySegment) Lib.CG_RECT_APPLY_AFFINE_TRANSFORM.invokeExact(
                    (SegmentAllocator) scratch, r, t);
            return result.toArray(JAVA_DOUBLE);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static boolean rectContainsPoint(double[] rect, double[] point) {
        try (Arena scratch = Arena.ofConfined()) {
            return (boolean) Lib.CG_RECT_CONTAINS_POINT.invokeExact(scratch.allocateFrom(JAVA_DOUBLE, rect),
                                                                   scratch.allocateFrom(JAVA_DOUBLE, point));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long[] stringFind(String text, String find, long options) {
        MemorySegment s = createString(text);
        MemorySegment f = createString(find);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment range = (MemorySegment) Lib.CF_STRING_FIND.invokeExact((SegmentAllocator) scratch, s, f,
                                                                                options);
            return range.toArray(JAVA_LONG);
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            release(s);
            release(f);
        }
    }

    public static String stringCharacters(String text, long location, long length) {
        MemorySegment s = createString(text);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment range = scratch.allocate(CF_RANGE);
            range.set(JAVA_LONG, CF_RANGE.byteOffset(PathElement.groupElement("location")), location);
            range.set(JAVA_LONG, CF_RANGE.byteOffset(PathElement.groupElement("length")), length);
            MemorySegment units = scratch.allocate(JAVA_CHAR, Math.max(1, length));
            Lib.CF_STRING_GET_CHARACTERS.invokeExact(s, range, units);
            return new String(units.asSlice(0, length * 2).toArray(JAVA_CHAR));
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            release(s);
        }
    }

    public static int paragraphStyleRoundTrip(byte direction) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment value = scratch.allocate(JAVA_BYTE);
            value.set(JAVA_BYTE, 0, direction);
            MemorySegment setting = scratch.allocate(CT_PARAGRAPH_STYLE_SETTING);
            setting.set(JAVA_INT, CT_PARAGRAPH_STYLE_SETTING.byteOffset(PathElement.groupElement("spec")),
                        BASE_WRITING_DIRECTION);
            setting.set(JAVA_LONG, CT_PARAGRAPH_STYLE_SETTING.byteOffset(PathElement.groupElement("valueSize")), 1L);
            setting.set(ADDRESS, CT_PARAGRAPH_STYLE_SETTING.byteOffset(PathElement.groupElement("value")), value);
            MemorySegment style = (MemorySegment) Lib.CT_PARAGRAPH_STYLE_CREATE.invokeExact(setting, 1L);
            int read = paragraphWritingDirection(style.address());
            release(style);
            return read;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static boolean urlIsDirectory(String path, boolean isDirectory) {
        MemorySegment s = createString(path);
        try {
            MemorySegment url = (MemorySegment) Lib.CF_URL_CREATE_WITH_FILE_SYSTEM_PATH.invokeExact(
                    Lib.K_CF_ALLOCATOR_DEFAULT, s, 0L, isDirectory);
            boolean directory = (boolean) Lib.CF_URL_HAS_DIRECTORY_PATH.invokeExact(url);
            release(url);
            return directory;
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            release(s);
        }
    }

    public static boolean glyphPathExists(long font, char glyph) {
        try {
            MemorySegment path = (MemorySegment) Lib.CT_FONT_CREATE_PATH_FOR_GLYPH.invokeExact(at(font), glyph,
                                                                                              MemorySegment.NULL);
            if (path.address() == 0) {
                return false;
            }
            releasePath(path.address());
            return true;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
