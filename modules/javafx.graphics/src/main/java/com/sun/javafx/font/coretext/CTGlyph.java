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

import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.Glyph;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.Shape;

class CTGlyph implements Glyph {
    private CTFontStrike strike;
    private int glyphCode;
    /* Readers take no lock: a box is published once it is filled and clamped, after its advances */
    private volatile CGRect bounds;
    private double xAdvance;
    private double yAdvance;
    private boolean drawShapes;

    /* Always using BRGA context has the same performance as gray */
    private static boolean LCD_CONTEXT = PrismFontFactory.getFontFactory().isLCDTextSupported();
    private static boolean CACHE_CONTEXT = true;

    private static long cachedContextRef;
    private static final Object CONTEXT_LOCK = new Object();
    private static final int BITMAP_WIDTH = 256;
    private static final int BITMAP_HEIGHT = 256;
    private static final int MAX_SIZE = 320;
    private static final long GRAY_COLORSPACE = OS.CGColorSpaceCreateDeviceGray();
    private static final long RGB_COLORSPACE = OS.CGColorSpaceCreateDeviceRGB();

    CTGlyph(CTFontStrike strike, int glyphCode, boolean drawShapes) {
        this.strike = strike;
        this.glyphCode = glyphCode;
        this.drawShapes = drawShapes;
    }

    @Override public int getGlyphCode() {
        return glyphCode;
    }

    /* Note, according to javadoc these bounds should be
     * in user space but T2K uses device space.
     */
    @Override public RectBounds getBBox() {
        /* IN T2k this is the bounds of the glyph path see GeneralPath.cpp */
        CGRect rect = strike.getBBox(glyphCode);
        if (rect == null) return new RectBounds();
        return new RectBounds((float)rect.origin.x,
                              (float)rect.origin.y,
                              (float)(rect.origin.x + rect.size.width),
                              (float)(rect.origin.y + rect.size.height));
    }

    private CGRect checkBounds() {
        CGRect box = bounds;
        return box != null ? box : measureBounds();
    }

    private synchronized CGRect measureBounds() {
        if (bounds == null) {
            try {
                bounds = measuredBox();
            } catch (Throwable t) {
                /* So that a glyph whose measurement failed throws once and is not measured again at every read */
                bounds = new CGRect();
                throw t;
            }
        }
        return bounds;
    }

    private CGRect measuredBox() {
        CGRect box = new CGRect();
        if (strike.getSize() == 0) return box;

        long fontRef = strike.getFontRef();
        if (fontRef == 0) return box;
        int orientation = OS.kCTFontOrientationDefault;
        CGSize size = new CGSize();
        OS.CTFontGetAdvancesForGlyphs(fontRef, orientation, (short)glyphCode, size);
        xAdvance = size.width;
        yAdvance = -size.height;   /*Inverted coordinates system */

        /* Avoid CTFontGetBoundingRectsForGlyphs as it is too slow */
//        bounds = OS.CTFontGetBoundingRectsForGlyphs(fontRef, orientation, (short)glyphCode, null, 1);

        CTFontFile fr = strike.getFontResource();
        float[] bb = new float[4];
        fr.getGlyphBoundingBox((short)glyphCode, strike.getSize(), bb);
        box.origin.x = bb[0];
        box.origin.y = bb[1];
        box.size.width = (bb[2] - bb[0]);
        box.size.height = (bb[3] - bb[1]);
        if (strike.matrix != null) {
            /* Need to use the native matrix as it is y up */
            OS.CGRectApplyAffineTransform(box, strike.matrix);
        }

        if (box.size.width < 0 || box.size.height < 0 ||
            box.size.width > MAX_SIZE || box.size.height > MAX_SIZE) {
            /* Negative values for dimensions can indicate the font is corrupted.
             * Overly large dimensions also indicate problem with the font as
             * JavaFX uses path rasterizers for fontSize greater than 80pt.
             */
            box.origin.x = box.origin.y = box.size.width = box.size.height = 0;
        } else {

            /* The box is increased to capture all fragments from LCD rendering  */
            box.origin.x = (int)Math.floor(box.origin.x) - 1;
            box.origin.y = (int)Math.floor(box.origin.y) - 1;
            box.size.width = (int)Math.ceil(box.size.width) + 1 + 1 + 1;
            box.size.height = (int)Math.ceil(box.size.height) + 1 + 1 + 1;
        }
        return box;
    }

    @Override public Shape getShape() {
        return strike.createGlyphOutline(glyphCode);
    }

    private long createContext(boolean lcd, int width, int height) {
        long space;
        int bpr, flags;
        if (lcd) {
            space = RGB_COLORSPACE;
            bpr = width * 4;
            flags = OS.kCGBitmapByteOrder32Host | OS.kCGImageAlphaPremultipliedFirst;
        } else {
            space = GRAY_COLORSPACE;
            bpr = width;
            flags = OS.kCGImageAlphaNone;
        }
        long context = bitmapContext(space, width, height, bpr, flags);
        if (context == 0) return 0;
        try {
            setStrikeOptions(context, lcd);
        } catch (Throwable t) {
            OS.CGContextRelease(context);
            throw t;
        }
        return context;

    }

    /* CoreGraphics may not have made the colour space, and NULL is legal only for an alpha-only bitmap */
    private static long bitmapContext(long space, int width, int height, int bytesPerRow, int flags) {
        if (space == 0) return 0;
        return OS.CGBitmapContextCreate(0, width, height, 8, bytesPerRow, space, flags);
    }

    private void setStrikeOptions(long context, boolean lcd) {
        boolean subPixel = strike.isSubPixelGlyph();
        OS.CGContextSetAllowsFontSmoothing(context, lcd);
        OS.CGContextSetAllowsAntialiasing(context, true);
        OS.CGContextSetAllowsFontSubpixelPositioning(context, subPixel);
        OS.CGContextSetAllowsFontSubpixelQuantization(context, subPixel);
    }

    private long getCachedContext(boolean lcd) {
        synchronized (CONTEXT_LOCK) {
            if (cachedContextRef == 0) {
                cachedContextRef = createContext(lcd, BITMAP_WIDTH, BITMAP_HEIGHT);
            }
            return cachedContextRef;
        }
    }

    private synchronized byte[] getColorImage(double x, double y, int w, int h) {

        if (w == 0 || h == 0) return new byte[0];

        long fontRef = strike.getFontRef();
        CGAffineTransform matrix = strike.matrix;
        long context = createContext(true, w, h);
        if (context == 0) return new byte[0];

        try {
            double drawX = 0, drawY = 0;
            if (matrix != null) {
                OS.CGContextTranslateCTM(context, -x, -y);
            } else {
                drawX = x;
                drawY = y;
            }

            OS.CTFontDrawGlyphs(fontRef, (short)glyphCode, -drawX, -drawY, context);

            if (matrix != null) {
                OS.CGContextTranslateCTM(context, x, y);
            }

            byte[] imageData = OS.CGImageContextGetData(context, w, h, 32);
            if (imageData == null) {
                bounds = new CGRect();
                imageData = new byte[0];
            }
            return imageData;
        } finally {
            OS.CGContextRelease(context);
        }
    }


    private synchronized byte[] getImage(double x, double y, int w, int h, int subPixel) {

        if (w == 0 || h == 0) return new byte[0];

        boolean lcdContext = LCD_CONTEXT || isLCDGlyph();
        if (CACHE_CONTEXT & BITMAP_WIDTH >= w & BITMAP_HEIGHT >= h) {
            /* Every glyph that fits draws into this one bitmap: no other draw may start until this image is
             * read, and the bitmap still has the options of whichever strike drew last */
            synchronized (CONTEXT_LOCK) {
                long context = getCachedContext(lcdContext);
                if (context == 0) return new byte[0];
                setStrikeOptions(context, lcdContext);
                return drawImage(context, x, y, w, h, subPixel);
            }
        }
        long context = createContext(lcdContext, w, h);
        if (context == 0) return new byte[0];
        try {
            return drawImage(context, x, y, w, h, subPixel);
        } finally {
            OS.CGContextRelease(context);
        }
    }

    private byte[] drawImage(long context, double x, double y, int w, int h, int subPixel) {
        /* Fill background with white */
        OS.CGContextSetRGBFillColor(context, 1, 1, 1, 1);
        CGRect rect = new CGRect();
        rect.size.width = w;
        rect.size.height = h;
        OS.CGContextFillRect(context, rect);

        CGAffineTransform matrix = strike.matrix;
        double drawX = 0, drawY = 0;
        if (matrix != null) {
            OS.CGContextTranslateCTM(context, -x, -y);
        } else {
            drawX = x - strike.getSubPixelPosition(subPixel);
            drawY = y;
        }

        /* Draw the text with black */
        OS.CGContextSetRGBFillColor(context, 0, 0, 0, 1);
        OS.CTFontDrawGlyphs(strike.getFontRef(), (short)glyphCode, -drawX, -drawY, context);

        if (matrix != null) {
            OS.CGContextTranslateCTM(context, x, y);
        }

        byte[] imageData;
        if (isLCDGlyph()) {
            imageData = OS.CGBitmapContextGetData(context, w, h, 24);
        } else {
            imageData = OS.CGBitmapContextGetData(context, w, h, 8);
        }
        if (imageData == null) {
            bounds = new CGRect();
            imageData = new byte[0];
        }
        return imageData;
    }

    @Override public byte[] getPixelData() {
        return getPixelData(0);
    }

    @Override public byte[] getPixelData(int subPixel) {
        CGRect box = checkBounds();
        if (isColorGlyph()) {
            return getColorImage(box.origin.x, box.origin.y,
                                 (int)box.size.width, (int)box.size.height);
        } else {
            return getImage(box.origin.x, box.origin.y,
                            (int)box.size.width, (int)box.size.height,
                            subPixel);
        }
    }

    @Override public float getAdvance() {
        checkBounds();
        //TODO should be user space (this method is not used)
        return (float)xAdvance;
    }

    @Override public float getPixelXAdvance() {
        checkBounds();
        return (float)xAdvance;
    }

    @Override public float getPixelYAdvance() {
        checkBounds();
        return (float)yAdvance;
    }

    @Override public int getWidth() {
        int w = (int)checkBounds().size.width;
        if (isColorGlyph()) {
            return (w * 4); // has alpha
        } else {
            return isLCDGlyph() ? w * 3 : w;
        }
    }

    @Override public int getHeight() {
        return (int)checkBounds().size.height;
    }

    @Override public int getOriginX() {
        return (int)checkBounds().origin.x;
    }

    @Override public int getOriginY() {
        CGRect box = checkBounds();
        int h = (int)box.size.height;
        int y = (int)box.origin.y;
        return -h - y; /*Inverted coordinates system */
    }

    public boolean isColorGlyph() {
        CTFontFile fontResource = strike.getFontResource();
        return fontResource.isColorGlyph(glyphCode);
    }

    @Override public boolean isLCDGlyph() {
        return strike.getAAMode() == FontResource.AA_LCD;
    }

}
