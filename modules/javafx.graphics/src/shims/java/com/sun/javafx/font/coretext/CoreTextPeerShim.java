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

import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.PrismFontFactory;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;

/** Package-private access to the macOS font peers for the font tests. No native call of its own. */
public final class CoreTextPeerShim {

    /** The half of a {@code CGRect} mirror a test leaves null. */
    public enum NullHalf {
        ORIGIN, SIZE
    }

    private CoreTextPeerShim() {
    }

    /** {@code OS.copyOfUIFont} with no matrix, so the font's own is kept; it takes {@code font} at +1. */
    public static long copyOfUIFont(long font, double size) {
        return OS.copyOfUIFont(MemorySegment.ofAddress(font), size, MemorySegment.NULL);
    }

    /** The advance CoreText gives the glyph in the font file's strike at {@code size}. */
    public static float advanceFromPlatform(FontResource resource, int glyphCode, float size) {
        return ((CTFontFile) resource).getAdvanceFromPlatform(glyphCode, size);
    }

    /** {@code OS.CGRectApplyAffineTransform} of the identity on {@code rect}, {x, y, w, h}, with {@code half} null. */
    public static void transformRectWithNull(NullHalf half, double[] rect) {
        CGAffineTransform identity = new CGAffineTransform();
        identity.a = 1;
        identity.d = 1;
        OS.CGRectApplyAffineTransform(rectWithNull(half, rect), identity);
    }

    /** {@code OS.CGContextFillRect} of {@code rect}, {x, y, w, h}, with {@code half} null. */
    public static void fillRectWithNull(long context, NullHalf half, double[] rect) {
        OS.CGContextFillRect(context, rectWithNull(half, rect));
    }

    private static CGRect rectWithNull(NullHalf half, double[] rect) {
        CGRect mirror = new CGRect();
        mirror.origin.x = rect[0];
        mirror.origin.y = rect[1];
        mirror.size.width = rect[2];
        mirror.size.height = rect[3];
        switch (half) {
            case ORIGIN -> mirror.origin = null;
            case SIZE -> mirror.size = null;
        }
        return mirror;
    }

    public static int addFallbackFont(CompositeFontResource fr, String fontName, long newRef) throws Exception {
        return CTGlyphLayout.addFallbackFont((CTFactory) PrismFontFactory.getFontFactory(), fr, fontName, newRef);
    }

    /** With no factory to ask: only a reference refused before the factory is reached returns normally. */
    public static int addFallbackFontWithoutFactory(CompositeFontResource fr, String fontName, long newRef)
            throws Exception {
        return CTGlyphLayout.addFallbackFont(null, fr, fontName, newRef);
    }

    public static FontResource createFontFile(String name, long fontRef) throws Exception {
        return ((CTFactory) PrismFontFactory.getFontFactory()).createFontFile(name, fontRef);
    }

    public static boolean mayFindFace(String name) {
        return ((CTFactory) PrismFontFactory.getFontFactory()).mayFindFace(name);
    }

    /** A CTLine of {@code text} in {@code fontRef}, left to right, made by layout's own code, at +1 for the caller. */
    public static long line(long fontRef, String text) {
        char[] chars = text.toCharArray();
        return new CTGlyphLayout().createCTLine(fontRef, chars, false, 0, chars.length);
    }

    /**
     * Layout's slot for {@code run}, a run of a line made in {@code lineFont}, laid out with slot {@code slot} of
     * {@code fr} (null for a plain strike), whose font is named {@code name}.
     */
    public static int getFontSlot(long run, long lineFont, CompositeFontResource fr, String name, int slot) {
        return new CTGlyphLayout().getFontSlot(run, lineFont, fr, name, slot);
    }

    /** The CTFont a font file made from a reference owns; 0 for any other font. */
    public static long ctFontRef(FontResource resource) throws ReflectiveOperationException {
        if (!(resource instanceof CTFontFile)) {
            return 0;
        }
        Field field = CTFontFile.class.getDeclaredField("ctFontRef");
        field.setAccessible(true);
        return field.getLong(resource);
    }

    /** The CGFont of an embedded font file; 0 for any other font. */
    public static long cgFontRef(FontResource resource) {
        return resource instanceof CTFontFile file ? file.getCGFontRef() : 0;
    }

    public static long fontRef(FontStrike strike) {
        return ((CTFontStrike) strike).getFontRef();
    }
}
