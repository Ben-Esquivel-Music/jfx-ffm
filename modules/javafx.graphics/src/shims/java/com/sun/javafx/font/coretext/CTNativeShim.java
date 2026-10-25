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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.util.List;
import java.util.Map;

/** Test access to the package-private members of {@link CTNative}. */
public final class CTNativeShim {

    public static final int kCTParagraphStyleSpecifierBaseWritingDirection =
            CTNative.kCTParagraphStyleSpecifierBaseWritingDirection;
    public static final int kCTFontTableLoca = CTNative.kCTFontTableLoca;
    public static final int kCTFontTableGlyf = CTNative.kCTFontTableGlyf;

    private CTNativeShim() {
    }

    public static Map<String, FunctionDescriptor> boundFunctions() {
        return CTNative.boundFunctions();
    }

    public static List<String> boundData() {
        return CTNative.boundData();
    }

    public static StructLayout paragraphStyleSetting() {
        return CTNative.CTPARAGRAPHSTYLESETTING;
    }

    public static MemorySegment kCTFontAttributeName() {
        return CTNative.kCTFontAttributeName;
    }

    public static MemorySegment kCTParagraphStyleAttributeName() {
        return CTNative.kCTParagraphStyleAttributeName;
    }

    public static MemorySegment CTFontCreateCopyWithAttributes(MemorySegment font, double size, MemorySegment matrix,
                                                               MemorySegment attributes) {
        return CTNative.CTFontCreateCopyWithAttributes(font, size, matrix, attributes);
    }

    public static MemorySegment CTFontCreatePathForGlyph(MemorySegment font, char glyph, MemorySegment matrix) {
        return CTNative.CTFontCreatePathForGlyph(font, glyph, matrix);
    }

    public static MemorySegment CTFontCreateWithGraphicsFont(MemorySegment cgFont, double size, MemorySegment matrix,
                                                             MemorySegment attributes) {
        return CTNative.CTFontCreateWithGraphicsFont(cgFont, size, matrix, attributes);
    }

    public static MemorySegment CTFontCreateWithName(MemorySegment name, double size, MemorySegment matrix) {
        return CTNative.CTFontCreateWithName(name, size, matrix);
    }

    public static void CTFontDrawGlyphs(MemorySegment font, MemorySegment glyphs, MemorySegment positions, long count,
                                        MemorySegment context) {
        CTNative.CTFontDrawGlyphs(font, glyphs, positions, count, context);
    }

    public static double CTFontGetAdvancesForGlyphs(MemorySegment font, int orientation, MemorySegment glyphs,
                                                    MemorySegment advances, long count) {
        return CTNative.CTFontGetAdvancesForGlyphs(font, orientation, glyphs, advances, count);
    }

    public static MemorySegment CTFontGetBoundingRectsForGlyphs(SegmentAllocator allocator, MemorySegment font,
                                                                int orientation, MemorySegment glyphs,
                                                                MemorySegment rects, long count) {
        return CTNative.CTFontGetBoundingRectsForGlyphs(allocator, font, orientation, glyphs, rects, count);
    }

    public static boolean CTFontManagerRegisterFontsForURL(MemorySegment url, int scope, MemorySegment error) {
        return CTNative.CTFontManagerRegisterFontsForURL(url, scope, error);
    }

    public static MemorySegment CTLineCreateWithAttributedString(MemorySegment string) {
        return CTNative.CTLineCreateWithAttributedString(string);
    }

    public static long CTLineGetGlyphCount(MemorySegment line) {
        return CTNative.CTLineGetGlyphCount(line);
    }

    public static MemorySegment CTLineGetGlyphRuns(MemorySegment line) {
        return CTNative.CTLineGetGlyphRuns(line);
    }

    public static double CTLineGetTypographicBounds(MemorySegment line, MemorySegment ascent, MemorySegment descent,
                                                    MemorySegment leading) {
        return CTNative.CTLineGetTypographicBounds(line, ascent, descent, leading);
    }

    public static MemorySegment CTParagraphStyleCreate(MemorySegment settings, long count) {
        return CTNative.CTParagraphStyleCreate(settings, count);
    }

    public static MemorySegment CTRunGetAttributes(MemorySegment run) {
        return CTNative.CTRunGetAttributes(run);
    }

    public static long CTRunGetGlyphCount(MemorySegment run) {
        return CTNative.CTRunGetGlyphCount(run);
    }

    public static void CTRunGetGlyphs(MemorySegment run, MemorySegment range, MemorySegment buffer) {
        CTNative.CTRunGetGlyphs(run, range, buffer);
    }

    public static MemorySegment CTRunGetGlyphsPtr(MemorySegment run, long count) {
        return CTNative.CTRunGetGlyphsPtr(run, count);
    }

    public static void CTRunGetPositions(MemorySegment run, MemorySegment range, MemorySegment buffer) {
        CTNative.CTRunGetPositions(run, range, buffer);
    }

    public static MemorySegment CTRunGetPositionsPtr(MemorySegment run, long count) {
        return CTNative.CTRunGetPositionsPtr(run, count);
    }

    public static void CTRunGetStringIndices(MemorySegment run, MemorySegment range, MemorySegment buffer) {
        CTNative.CTRunGetStringIndices(run, range, buffer);
    }

    public static MemorySegment CTRunGetStringIndicesPtr(MemorySegment run, long count) {
        return CTNative.CTRunGetStringIndicesPtr(run, count);
    }
}
