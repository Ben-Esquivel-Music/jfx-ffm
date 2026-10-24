/*
 * Copyright (c) 2013, 2026, Oracle and/or its affiliates. All rights reserved.
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
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

import com.sun.javafx.geom.Path2D;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The entry points the macOS font peers call, once JNI natives in {@code coretext.c}. "The C" in this package is that
 * file at commit {@code 28b02ef511}
 * ({@code git show 28b02ef511:modules/javafx.graphics/src/main/native-font/coretext.c}).
 */
class OS {
    static final int kCFURLPOSIXPathStyle = 0;
    static final int kCTFontOrientationDefault = 0;
    static final int kCTFontManagerScopeProcess = 1;
    static final int kCGBitmapByteOrder32Big = 4 << 12;
    static final int kCGBitmapByteOrder32Little = 2 << 12;
    static final int kCGBitmapByteOrder32Host = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? kCGBitmapByteOrder32Little : kCGBitmapByteOrder32Big;
    static final int kCGImageAlphaPremultipliedFirst = 2;
    static final int kCGImageAlphaNone = 0;
    static final int kCTWritingDirectionRightToLeft = 1;

    static final long CFStringCreate(String string) {
        char[] buffer = string.toCharArray();
        long alloc = kCFAllocatorDefault();
        return CFStringCreateWithCharacters(alloc, buffer, buffer.length);
    }

    private static MemorySegment ptr(long address) {
        return MemorySegment.ofAddress(address);
    }

    private static MemorySegment transform(Arena arena, CGAffineTransform t) {
        return arena.allocateFrom(JAVA_DOUBLE, t.a, t.b, t.c, t.d, t.tx, t.ty);
    }

    private static MemorySegment rect(Arena arena, CGRect r) {
        return arena.allocateFrom(JAVA_DOUBLE, r.origin.x, r.origin.y, r.size.width, r.size.height);
    }

    private static void copy(MemorySegment from, CGRect to) {
        to.origin.x = from.get(JAVA_DOUBLE, 0);
        to.origin.y = from.get(JAVA_DOUBLE, 8);
        to.size.width = from.get(JAVA_DOUBLE, 16);
        to.size.height = from.get(JAVA_DOUBLE, 24);
    }

    private static CGRect newRect(MemorySegment from) {
        CGRect rect = new CGRect();
        copy(from, rect);
        return rect;
    }

    /* Custom */
    static final byte[] CGBitmapContextGetData(long c, int width, int height, int bpp) {
        return CGBitmapReader.invertedMask(ptr(c), width, height, bpp);
    }

    static final byte[] CGImageContextGetData(long c, int width, int height, int bpp) {
        return CGBitmapReader.bgra(ptr(c), width, height, bpp);
    }

    static final void CGRectApplyAffineTransform(CGRect rect, CGAffineTransform t) {
        try (Arena arena = Arena.ofConfined()) {
            copy(CGNative.CGRectApplyAffineTransform(arena, rect(arena, rect), transform(arena, t)), rect);
        }
    }

    static final Path2D CGPathApply(long path) {
        CGPathSink sink = new CGPathSink();
        CGNative.CGPathApply(ptr(path), sink);
        return sink.toPath2D();
    }

    static final CGRect CGPathGetPathBoundingBox(long path) {
        try (Arena arena = Arena.ofConfined()) {
            return newRect(CGNative.CGPathGetPathBoundingBox(arena, ptr(path)));
        }
    }

    static final long CFStringCreateWithCharacters(long alloc, char[] chars, long start, long numChars) {
        if (chars == null || start < 0 || numChars < 0 || start > Long.MAX_VALUE - numChars
                || start + numChars > chars.length) {
            return 0;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment units = arena.allocate(JAVA_CHAR, numChars);
            MemorySegment.copy(chars, (int) start, units, JAVA_CHAR, 0, (int) numChars);
            return CFNative.CFStringCreateWithCharacters(ptr(alloc), units, numChars).address();
        }
    }

    static final String CTFontCopyURLAttribute(long font) {
        MemorySegment url = CTNative.CTFontCopyAttribute(ptr(font), CTNative.kCTFontURLAttribute);
        if (url.address() == 0) {
            return null;
        }
        MemorySegment path = CFNative.CFURLCopyFileSystemPath(url, CFNative.kCFURLPOSIXPathStyle);
        CFNative.CFRelease(url);
        if (path.address() == 0) {
            return null;
        }
        String string = CFNative.cfToString(path);
        CFNative.CFRelease(path);
        return string;
    }

    static final String CTFontCopyAttributeDisplayName(long font) {
        MemorySegment name = CTNative.CTFontCopyAttribute(ptr(font), CTNative.kCTFontDisplayNameAttribute);
        if (name.address() == 0) {
            return null;
        }
        String string = CFNative.cfToString(name);
        CFNative.CFRelease(name);
        return string;
    }

    static final void CTFontDrawGlyphs(long font, short glyphs, double x, double y, long context) {
        try (Arena arena = Arena.ofConfined()) {
            CTNative.CTFontDrawGlyphs(ptr(font), arena.allocateFrom(JAVA_CHAR, (char) glyphs),
                                      arena.allocateFrom(JAVA_DOUBLE, x, y), 1, ptr(context));
        }
    }

    static final double CTFontGetAdvancesForGlyphs(long font, int orientation, short glyphs, CGSize advances) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = MemorySegment.NULL;
            if (advances != null) {
                size = arena.allocateFrom(JAVA_DOUBLE, advances.width, advances.height);
            }
            double advance = CTNative.CTFontGetAdvancesForGlyphs(ptr(font), orientation,
                    arena.allocateFrom(JAVA_CHAR, (char) glyphs), size, 1);
            if (advances != null) {
                advances.width = size.get(JAVA_DOUBLE, 0);
                advances.height = size.get(JAVA_DOUBLE, 8);
            }
            return advance;
        }
    }

    static final CGRect CTFontGetBoundingRectForGlyphs(long font, short glyph) {
        try (Arena arena = Arena.ofConfined()) {
            return newRect(CTNative.CTFontGetBoundingRectsForGlyphs(arena, ptr(font), 0,
                    arena.allocateFrom(JAVA_CHAR, (char) glyph), MemorySegment.NULL, 1));
        }
    }

    static final boolean CTFontGetBoundingRectForGlyphUsingTables(long font, short glyphs, short format,
                                                                  int[] retArr) {
        return GlyfTableBounds.bounds(ptr(font), glyphs, format, retArr);
    }

    // The slot mask and the glyph go into one int, the glyph as unsigned 16 bits.
    static final int CTRunGetGlyphs(long run, int slotMask, int start, int[] buffer) {
        MemorySegment runRef = ptr(run);
        long count = CTNative.CTRunGetGlyphCount(runRef);
        if (count == 0) {
            return 0;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment glyphs = CTNative.CTRunGetGlyphsPtr(runRef, count);
            if (glyphs.address() == 0) {
                glyphs = arena.allocate(JAVA_CHAR, count);
                CTNative.CTRunGetGlyphs(runRef, arena.allocate(CFNative.CFRANGE), glyphs);
            }
            for (int i = 0; i < (int) count; i++) {
                buffer[start + i] = slotMask | glyphs.getAtIndex(JAVA_CHAR, i);
            }
        }
        return (int) count;
    }

    static final int CTRunGetStringIndices(long run, int start, int[] buffer) {
        MemorySegment runRef = ptr(run);
        long count = CTNative.CTRunGetGlyphCount(runRef);
        if (count == 0) {
            return 0;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment indices = CTNative.CTRunGetStringIndicesPtr(runRef, count);
            if (indices.address() == 0) {
                indices = arena.allocate(JAVA_LONG, count);
                CTNative.CTRunGetStringIndices(runRef, arena.allocate(CFNative.CFRANGE), indices);
            }
            for (int i = 0; i < (int) count; i++) {
                buffer[start + i] = (int) indices.getAtIndex(JAVA_LONG, i);
            }
        }
        return (int) count;
    }

    static final int CTRunGetPositions(long run, int start, float[] buffer) {
        MemorySegment runRef = ptr(run);
        long count = CTNative.CTRunGetGlyphCount(runRef);
        if (count == 0) {
            return 0;
        }
        int j = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment positions = CTNative.CTRunGetPositionsPtr(runRef, count);
            if (positions.address() == 0) {
                positions = arena.allocate(CGNative.CGPOINT, count);
                CTNative.CTRunGetPositions(runRef, arena.allocate(CFNative.CFRANGE), positions);
            }
            for (int i = 0; i < (int) count; i++) {
                buffer[start + j++] = (float) positions.getAtIndex(JAVA_DOUBLE, 2L * i);
                buffer[start + j++] = (float) positions.getAtIndex(JAVA_DOUBLE, 2L * i + 1);
            }
        }
        return j;
    }

    /* one to one */
    static final long kCFAllocatorDefault() {
        return CFNative.kCFAllocatorDefault.address();
    }

    static final long kCFTypeDictionaryKeyCallBacks() {
        return CFNative.kCFTypeDictionaryKeyCallBacks.address();
    }

    static final long kCFTypeDictionaryValueCallBacks() {
        return CFNative.kCFTypeDictionaryValueCallBacks.address();
    }

    static final long kCTFontAttributeName() {
        return CTNative.kCTFontAttributeName.address();
    }

    static final long kCTParagraphStyleAttributeName() {
        return CTNative.kCTParagraphStyleAttributeName.address();
    }

    static final long CFArrayGetCount(long theArray) {
        return CFNative.CFArrayGetCount(ptr(theArray));
    }

    static final long CFArrayGetValueAtIndex(long theArray, long idx) {
        return CFNative.CFArrayGetValueAtIndex(ptr(theArray), idx).address();
    }

    static final long CFAttributedStringCreate(long alloc, long str, long attributes) {
        return CFNative.CFAttributedStringCreate(ptr(alloc), ptr(str), ptr(attributes)).address();
    }

    static final void CFDictionaryAddValue(long theDict, long key, long value) {
        CFNative.CFDictionaryAddValue(ptr(theDict), ptr(key), ptr(value));
    }

    static final long CFDictionaryCreateMutable(long allocator, long capacity, long keyCallBacks, long valueCallBacks) {
        return CFNative.CFDictionaryCreateMutable(ptr(allocator), capacity, ptr(keyCallBacks), ptr(valueCallBacks))
                       .address();
    }

    static final long CFDictionaryGetValue(long theDict, long key) {
        return CFNative.CFDictionaryGetValue(ptr(theDict), ptr(key)).address();
    }

    static final void CFRetain(long cf) {
        CFNative.CFRetain(ptr(cf));
    }

    static final void CFRelease(long cf) {
        CFNative.CFRelease(ptr(cf));
    }

    static final long CFStringCreateWithCharacters(long alloc, char[] chars, long numChars) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment units = chars == null ? MemorySegment.NULL : arena.allocateFrom(JAVA_CHAR, chars);
            return CFNative.CFStringCreateWithCharacters(ptr(alloc), units, numChars).address();
        }
    }

    static final long CFURLCreateWithFileSystemPath(long allocator, long filePath, long pathStyle,
                                                    boolean isDirectory) {
        return CFNative.CFURLCreateWithFileSystemPath(ptr(allocator), ptr(filePath), pathStyle, isDirectory)
                       .address();
    }

    static final long CGBitmapContextCreate(long data, long width, long height, long bitsPerComponent, long bytesPerRow,
                                            long colorspace, int bitmapInfo) {
        return CGNative.CGBitmapContextCreate(ptr(data), width, height, bitsPerComponent, bytesPerRow,
                                              ptr(colorspace), bitmapInfo).address();
    }

    static final void CGContextFillRect(long context, CGRect rect) {
        try (Arena arena = Arena.ofConfined()) {
            CGNative.CGContextFillRect(ptr(context), rect(arena, rect));
        }
    }

    static final void CGContextRelease(long context) {
        CGNative.CGContextRelease(ptr(context));
    }

    static final void CGContextSetAllowsFontSmoothing(long context, boolean allowsFontSmoothing) {
        CGNative.CGContextSetAllowsFontSmoothing(ptr(context), allowsFontSmoothing);
    }

    static final void CGContextSetAllowsAntialiasing(long context, boolean allowsAntialiasing) {
        CGNative.CGContextSetAllowsAntialiasing(ptr(context), allowsAntialiasing);
    }

    static final void CGContextSetAllowsFontSubpixelPositioning(long context, boolean allowsFontSubpixelPositioning) {
        CGNative.CGContextSetAllowsFontSubpixelPositioning(ptr(context), allowsFontSubpixelPositioning);
    }

    static final void CGContextSetAllowsFontSubpixelQuantization(long context, boolean allowsFontSubpixelQuantization) {
        CGNative.CGContextSetAllowsFontSubpixelQuantization(ptr(context), allowsFontSubpixelQuantization);
    }

    static final void CGContextSetRGBFillColor(long context, double red, double green, double blue, double alpha) {
        CGNative.CGContextSetRGBFillColor(ptr(context), red, green, blue, alpha);
    }

    static final void CGContextTranslateCTM(long context, double tx, double ty) {
        CGNative.CGContextTranslateCTM(ptr(context), tx, ty);
    }

    static final long CGColorSpaceCreateDeviceGray() {
        return CGNative.CGColorSpaceCreateDeviceGray().address();
    }

    static final long CGColorSpaceCreateDeviceRGB() {
        return CGNative.CGColorSpaceCreateDeviceRGB().address();
    }

    static final long CGDataProviderCreateWithURL(long cfURL) {
        return CGNative.CGDataProviderCreateWithURL(ptr(cfURL)).address();
    }

    static final long CGFontCreateWithDataProvider(long dataProvider) {
        return CGNative.CGFontCreateWithDataProvider(ptr(dataProvider)).address();
    }

    static final void CGPathRelease(long path) {
        CGNative.CGPathRelease(ptr(path));
    }

    // A null matrix is NULL here, but the identity in the copy and the create below: what the C did.
    static final long CTFontCreatePathForGlyph(long font, short glyph, CGAffineTransform matrix) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = matrix == null ? MemorySegment.NULL : transform(arena, matrix);
            return CTNative.CTFontCreatePathForGlyph(ptr(font), (char) glyph, m).address();
        }
    }

    static final long CTFontCreateCopyWithAttributes(long cgFont, double size, CGAffineTransform matrix,
                                                     long attributes) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = matrix == null ? CGNative.CGAffineTransformIdentity : transform(arena, matrix);
            return CTNative.CTFontCreateCopyWithAttributes(ptr(cgFont), size, m, ptr(attributes)).address();
        }
    }

    static final long CTFontCreateWithGraphicsFont(long cgFont, double size, CGAffineTransform matrix,
                                                   long attributes) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = matrix == null ? CGNative.CGAffineTransformIdentity : transform(arena, matrix);
            return CTNative.CTFontCreateWithGraphicsFont(ptr(cgFont), size, m, ptr(attributes)).address();
        }
    }

    static final long CTFontCreateUIFontForLanguage(double size, CGAffineTransform matrix, boolean bold) {
        int type = bold ? CTNative.kCTFontUIFontEmphasizedSystem : CTNative.kCTFontUIFontSystem;
        MemorySegment font = CTNative.CTFontCreateUIFontForLanguage(type, size, MemorySegment.NULL);
        if (matrix == null) {
            return font.address();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment copy = CTNative.CTFontCreateCopyWithAttributes(font, size, transform(arena, matrix),
                                                                         MemorySegment.NULL);
            CFNative.CFRelease(font);
            return copy.address();
        }
    }

    static final long CTFontCreateWithName(long name, double size, CGAffineTransform matrix) {
        MemorySegment fontName = ptr(name);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = matrix == null ? MemorySegment.NULL : transform(arena, matrix);
            if (CFNative.CFStringGetCharacterAtIndex(fontName, 0) == '.') {
                MemorySegment found = CFNative.CFStringFind(arena, fontName, CFNative.boldCFString(),
                                                            CFNative.kCFCompareCaseInsensitive);
                boolean bold = found.get(JAVA_LONG, 0) != CFNative.kCFNotFound;
                int type = bold ? CTNative.kCTFontUIFontEmphasizedSystem : CTNative.kCTFontUIFontSystem;
                MemorySegment font = CTNative.CTFontCreateUIFontForLanguage(type, 0.0, MemorySegment.NULL);
                MemorySegment copy = CTNative.CTFontCreateCopyWithAttributes(font, size, m, MemorySegment.NULL);
                CFNative.CFRelease(font);
                return copy.address();
            }
            return CTNative.CTFontCreateWithName(fontName, size, m).address();
        }
    }

    static final boolean CTFontManagerRegisterFontsForURL(long fontURL, int scope, long error) {
        return CTNative.CTFontManagerRegisterFontsForURL(ptr(fontURL), scope, ptr(error));
    }

    static final long CTLineCreateWithAttributedString(long string) {
        return CTNative.CTLineCreateWithAttributedString(ptr(string)).address();
    }

    static final long CTLineGetGlyphRuns(long line) {
        return CTNative.CTLineGetGlyphRuns(ptr(line)).address();
    }

    static final long CTLineGetGlyphCount(long line) {
        return CTNative.CTLineGetGlyphCount(ptr(line));
    }

    static final double CTLineGetTypographicBounds(long line) {
        return CTNative.CTLineGetTypographicBounds(ptr(line), MemorySegment.NULL, MemorySegment.NULL,
                                                   MemorySegment.NULL);
    }

    static final long CTRunGetAttributes(long run) {
        return CTNative.CTRunGetAttributes(ptr(run)).address();
    }

    // CTWritingDirection is one byte.
    static final long CTParagraphStyleCreate(int dir) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment setting = arena.allocate(CTNative.CTPARAGRAPHSTYLESETTING);
            setting.set(JAVA_INT, 0, CTNative.kCTParagraphStyleSpecifierBaseWritingDirection);
            setting.set(JAVA_LONG, 8, JAVA_BYTE.byteSize());
            setting.set(ADDRESS, 16, arena.allocateFrom(JAVA_BYTE, (byte) dir));
            return CTNative.CTParagraphStyleCreate(setting, 1).address();
        }
    }
}
