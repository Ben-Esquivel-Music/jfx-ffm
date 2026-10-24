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

import com.sun.javafx.geom.Path2D;

/**
 * Pure delegate onto the {@code OS} entry points the macOS font peers call, one line per method. The macOS goldens
 * were captured through it from the JNI build of commit {@code 28b02ef511}; {@code MacFontBindings.requireSameBinding}
 * proves at every run that it calls what production calls.
 * <p>
 * The package-private mirrors cross as {@code double[]} in field order. {@code null} stays {@code null}: a null
 * matrix and an identity matrix are different inputs to the natives.
 */
public final class OSShim {

    private OSShim() {
    }

    /**
     * Initialises {@code OS}. In the JNI build the goldens were captured from, that loaded {@code javafx_font};
     * now it loads nothing, and each facade links its framework when first used.
     */
    public static void ensureLoaded() {
        try {
            Class.forName(OS.class.getName(), true, OS.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int constant(String name) {
        return switch (name) {
            case "kCFURLPOSIXPathStyle" -> OS.kCFURLPOSIXPathStyle;
            case "kCTFontOrientationDefault" -> OS.kCTFontOrientationDefault;
            case "kCTFontManagerScopeProcess" -> OS.kCTFontManagerScopeProcess;
            case "kCGBitmapByteOrder32Host" -> OS.kCGBitmapByteOrder32Host;
            case "kCGImageAlphaPremultipliedFirst" -> OS.kCGImageAlphaPremultipliedFirst;
            case "kCGImageAlphaNone" -> OS.kCGImageAlphaNone;
            case "kCTWritingDirectionRightToLeft" -> OS.kCTWritingDirectionRightToLeft;
            default -> throw new IllegalArgumentException("no OS constant " + name);
        };
    }

    public static long CFStringCreate(String string) {
        return OS.CFStringCreate(string);
    }

    public static byte[] CGBitmapContextGetData(long c, int width, int height, int bpp) {
        return OS.CGBitmapContextGetData(c, width, height, bpp);
    }

    public static byte[] CGImageContextGetData(long c, int width, int height, int bpp) {
        return OS.CGImageContextGetData(c, width, height, bpp);
    }

    /** In/out, like the mirror: {@code rect} receives the result. */
    public static void CGRectApplyAffineTransform(double[] rect, double[] t) {
        CGRect mirror = toRect(rect);
        OS.CGRectApplyAffineTransform(mirror, toTransform(t));
        copyRect(mirror, rect);
    }

    public static Path2D CGPathApply(long path) {
        return OS.CGPathApply(path);
    }

    public static double[] CGPathGetPathBoundingBox(long path) {
        return fromRect(OS.CGPathGetPathBoundingBox(path));
    }

    public static long CFStringCreateWithCharacters(long alloc, char[] chars, long start, long numChars) {
        return OS.CFStringCreateWithCharacters(alloc, chars, start, numChars);
    }

    public static String CTFontCopyURLAttribute(long font) {
        return OS.CTFontCopyURLAttribute(font);
    }

    public static String CTFontCopyAttributeDisplayName(long font) {
        return OS.CTFontCopyAttributeDisplayName(font);
    }

    public static void CTFontDrawGlyphs(long font, short glyphs, double x, double y, long context) {
        OS.CTFontDrawGlyphs(font, glyphs, x, y, context);
    }

    /** A null {@code advances} passes a null mirror, so {@code OS} passes a NULL out-parameter. */
    public static double CTFontGetAdvancesForGlyphs(long font, int orientation, short glyphs, double[] advances) {
        CGSize size = advances == null ? null : new CGSize();
        double result = OS.CTFontGetAdvancesForGlyphs(font, orientation, glyphs, size);
        if (advances != null) {
            advances[0] = size.width;
            advances[1] = size.height;
        }
        return result;
    }

    public static double[] CTFontGetBoundingRectForGlyphs(long font, short glyph) {
        return fromRect(OS.CTFontGetBoundingRectForGlyphs(font, glyph));
    }

    public static boolean CTFontGetBoundingRectForGlyphUsingTables(long font, short glyphs, short format,
                                                                  int[] retArr) {
        return OS.CTFontGetBoundingRectForGlyphUsingTables(font, glyphs, format, retArr);
    }

    public static int CTRunGetGlyphs(long run, int slotMask, int start, int[] buffer) {
        return OS.CTRunGetGlyphs(run, slotMask, start, buffer);
    }

    public static int CTRunGetStringIndices(long run, int start, int[] buffer) {
        return OS.CTRunGetStringIndices(run, start, buffer);
    }

    public static int CTRunGetPositions(long run, int start, float[] buffer) {
        return OS.CTRunGetPositions(run, start, buffer);
    }

    public static long kCFAllocatorDefault() {
        return OS.kCFAllocatorDefault();
    }

    public static long kCFTypeDictionaryKeyCallBacks() {
        return OS.kCFTypeDictionaryKeyCallBacks();
    }

    public static long kCFTypeDictionaryValueCallBacks() {
        return OS.kCFTypeDictionaryValueCallBacks();
    }

    public static long kCTFontAttributeName() {
        return OS.kCTFontAttributeName();
    }

    public static long kCTParagraphStyleAttributeName() {
        return OS.kCTParagraphStyleAttributeName();
    }

    public static long CFArrayGetCount(long theArray) {
        return OS.CFArrayGetCount(theArray);
    }

    public static long CFArrayGetValueAtIndex(long theArray, long idx) {
        return OS.CFArrayGetValueAtIndex(theArray, idx);
    }

    public static long CFAttributedStringCreate(long alloc, long str, long attributes) {
        return OS.CFAttributedStringCreate(alloc, str, attributes);
    }

    public static void CFDictionaryAddValue(long theDict, long key, long value) {
        OS.CFDictionaryAddValue(theDict, key, value);
    }

    public static long CFDictionaryCreateMutable(long allocator, long capacity, long keyCallBacks,
                                                 long valueCallBacks) {
        return OS.CFDictionaryCreateMutable(allocator, capacity, keyCallBacks, valueCallBacks);
    }

    public static long CFDictionaryGetValue(long theDict, long key) {
        return OS.CFDictionaryGetValue(theDict, key);
    }

    public static void CFRetain(long cf) {
        OS.CFRetain(cf);
    }

    public static void CFRelease(long cf) {
        OS.CFRelease(cf);
    }

    public static long CFStringCreateWithCharacters(long alloc, char[] chars, long numChars) {
        return OS.CFStringCreateWithCharacters(alloc, chars, numChars);
    }

    public static long CFURLCreateWithFileSystemPath(long allocator, long filePath, long pathStyle,
                                                     boolean isDirectory) {
        return OS.CFURLCreateWithFileSystemPath(allocator, filePath, pathStyle, isDirectory);
    }

    public static long CGBitmapContextCreate(long data, long width, long height, long bitsPerComponent,
                                             long bytesPerRow, long colorspace, int bitmapInfo) {
        return OS.CGBitmapContextCreate(data, width, height, bitsPerComponent, bytesPerRow, colorspace,
                                        bitmapInfo);
    }

    public static void CGContextFillRect(long context, double[] rect) {
        OS.CGContextFillRect(context, toRect(rect));
    }

    public static void CGContextRelease(long context) {
        OS.CGContextRelease(context);
    }

    public static void CGContextSetAllowsFontSmoothing(long context, boolean allowsFontSmoothing) {
        OS.CGContextSetAllowsFontSmoothing(context, allowsFontSmoothing);
    }

    public static void CGContextSetAllowsAntialiasing(long context, boolean allowsAntialiasing) {
        OS.CGContextSetAllowsAntialiasing(context, allowsAntialiasing);
    }

    public static void CGContextSetAllowsFontSubpixelPositioning(long context, boolean allows) {
        OS.CGContextSetAllowsFontSubpixelPositioning(context, allows);
    }

    public static void CGContextSetAllowsFontSubpixelQuantization(long context, boolean allows) {
        OS.CGContextSetAllowsFontSubpixelQuantization(context, allows);
    }

    public static void CGContextSetRGBFillColor(long context, double red, double green, double blue, double alpha) {
        OS.CGContextSetRGBFillColor(context, red, green, blue, alpha);
    }

    public static void CGContextTranslateCTM(long context, double tx, double ty) {
        OS.CGContextTranslateCTM(context, tx, ty);
    }

    public static long CGColorSpaceCreateDeviceGray() {
        return OS.CGColorSpaceCreateDeviceGray();
    }

    public static long CGColorSpaceCreateDeviceRGB() {
        return OS.CGColorSpaceCreateDeviceRGB();
    }

    public static long CGDataProviderCreateWithURL(long cfURL) {
        return OS.CGDataProviderCreateWithURL(cfURL);
    }

    public static long CGFontCreateWithDataProvider(long dataProvider) {
        return OS.CGFontCreateWithDataProvider(dataProvider);
    }

    public static void CGPathRelease(long path) {
        OS.CGPathRelease(path);
    }

    public static long CTFontCreatePathForGlyph(long font, short glyph, double[] matrix) {
        return OS.CTFontCreatePathForGlyph(font, glyph, toTransform(matrix));
    }

    public static long CTFontCreateCopyWithAttributes(long cgFont, double size, double[] matrix, long attributes) {
        return OS.CTFontCreateCopyWithAttributes(cgFont, size, toTransform(matrix), attributes);
    }

    public static long CTFontCreateWithGraphicsFont(long cgFont, double size, double[] matrix, long attributes) {
        return OS.CTFontCreateWithGraphicsFont(cgFont, size, toTransform(matrix), attributes);
    }

    public static long CTFontCreateUIFontForLanguage(double size, double[] matrix, boolean bold) {
        return OS.CTFontCreateUIFontForLanguage(size, toTransform(matrix), bold);
    }

    public static long CTFontCreateWithName(long name, double size, double[] matrix) {
        return OS.CTFontCreateWithName(name, size, toTransform(matrix));
    }

    public static boolean CTFontManagerRegisterFontsForURL(long fontURL, int scope, long error) {
        return OS.CTFontManagerRegisterFontsForURL(fontURL, scope, error);
    }

    public static long CTLineCreateWithAttributedString(long string) {
        return OS.CTLineCreateWithAttributedString(string);
    }

    public static long CTLineGetGlyphRuns(long line) {
        return OS.CTLineGetGlyphRuns(line);
    }

    public static long CTLineGetGlyphCount(long line) {
        return OS.CTLineGetGlyphCount(line);
    }

    public static double CTLineGetTypographicBounds(long line) {
        return OS.CTLineGetTypographicBounds(line);
    }

    public static long CTRunGetAttributes(long run) {
        return OS.CTRunGetAttributes(run);
    }

    public static long CTParagraphStyleCreate(int dir) {
        return OS.CTParagraphStyleCreate(dir);
    }

    private static CGAffineTransform toTransform(double[] t) {
        if (t == null) {
            return null;
        }
        CGAffineTransform m = new CGAffineTransform();
        m.a = t[0];
        m.b = t[1];
        m.c = t[2];
        m.d = t[3];
        m.tx = t[4];
        m.ty = t[5];
        return m;
    }

    private static CGRect toRect(double[] r) {
        if (r == null) {
            return null;
        }
        CGRect rect = new CGRect();
        rect.origin.x = r[0];
        rect.origin.y = r[1];
        rect.size.width = r[2];
        rect.size.height = r[3];
        return rect;
    }

    private static void copyRect(CGRect rect, double[] r) {
        if (rect != null && r != null) {
            r[0] = rect.origin.x;
            r[1] = rect.origin.y;
            r[2] = rect.size.width;
            r[3] = rect.size.height;
        }
    }

    private static double[] fromRect(CGRect rect) {
        if (rect == null) {
            return null;
        }
        return new double[] {rect.origin.x, rect.origin.y, rect.size.width, rect.size.height};
    }
}
