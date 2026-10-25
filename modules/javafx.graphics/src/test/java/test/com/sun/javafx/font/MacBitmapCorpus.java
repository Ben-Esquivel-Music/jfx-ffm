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

package test.com.sun.javafx.font;

import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.coretext.OSShim;
import java.util.Arrays;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * The pixel-reader corpus of {@link MacCoreTextGoldenTest}: {@code CGBitmapContextGetData} and
 * {@code CGImageContextGetData}, now Java ({@code CGBitmapReader}) and matched exactly, on glyphs drawn the way
 * {@code CTGlyph} draws them.
 */
final class MacBitmapCorpus {

    private static final double[] ROT30 = MacCoreTextGoldenTest.ROT30;
    private static final double[] SHEAR = MacCoreTextGoldenTest.SHEAR;

    private MacBitmapCorpus() {
    }


    /** As {@code CTGlyph.createContext} makes it. */
    static long context(boolean rgb, int width, int height, boolean subPixel, long gray, long rgbSpace) {
        int flags = rgb
                ? OSShim.constant("kCGBitmapByteOrder32Host") | OSShim.constant("kCGImageAlphaPremultipliedFirst")
                : OSShim.constant("kCGImageAlphaNone");
        long context = OSShim.CGBitmapContextCreate(0, width, height, 8, rgb ? width * 4L : width,
                                                    rgb ? rgbSpace : gray, flags);
        OSShim.CGContextSetAllowsFontSmoothing(context, rgb);
        OSShim.CGContextSetAllowsAntialiasing(context, true);
        OSShim.CGContextSetAllowsFontSubpixelPositioning(context, subPixel);
        OSShim.CGContextSetAllowsFontSubpixelQuantization(context, subPixel);
        return context;
    }

    /** As {@code CTGlyph.getImage} draws. */
    static void drawGray(long context, long font, int glyph, double x, double y, int w, int h, double subPixel,
                         boolean matrix) {
        OSShim.CGContextSetRGBFillColor(context, 1, 1, 1, 1);
        OSShim.CGContextFillRect(context, new double[] {0, 0, w, h});
        double drawX = 0;
        double drawY = 0;
        if (matrix) {
            OSShim.CGContextTranslateCTM(context, -x, -y);
        } else {
            drawX = x - subPixel;
            drawY = y;
        }
        OSShim.CGContextSetRGBFillColor(context, 0, 0, 0, 1);
        OSShim.CTFontDrawGlyphs(font, (short) glyph, -drawX, -drawY, context);
        if (matrix) {
            OSShim.CGContextTranslateCTM(context, x, y);
        }
    }

    static void putBitmaps(GoldenMap out) throws Exception {
        long gray = OSShim.CGColorSpaceCreateDeviceGray();
        long rgb = OSShim.CGColorSpaceCreateDeviceRGB();
        out.put("ct.bitmap.space.gray", MacGoldenFormat.text(MacFontOracleShim.cfTypeName(gray)) + "|"
                + Arrays.toString(MacFontOracleShim.colorSpaceInfo(gray)));
        out.put("ct.bitmap.space.rgb", MacGoldenFormat.text(MacFontOracleShim.cfTypeName(rgb)) + "|"
                + Arrays.toString(MacFontOracleShim.colorSpaceInfo(rgb)));
        long gray256 = context(false, 256, 256, true, gray, rgb);
        long rgb256 = context(true, 256, 256, true, gray, rgb);
        long rgbPlain = context(true, 256, 256, false, gray, rgb);
        out.put("ct.bitmap.ctx.gray256", Arrays.toString(MacFontOracleShim.bitmapContextInfo(gray256)));
        out.put("ct.bitmap.ctx.rgb256", Arrays.toString(MacFontOracleShim.bitmapContextInfo(rgb256)));

        String[][] faces = {{"helvetica", "Helvetica"}, {"times", "Times-Roman"}, {"sfns", ".SFNS-Regular"},
            {"hiragino", "HiraginoSans-W3"}, {"geezapro", "GeezaPro"}};
        double[] sizes = {8, 12, 17.9, 24, 48};
        double[] subPixels = {0, 0.25, 0.5, 0.75};
        for (String[] face : faces) {
            int[] glyphs = sampleGlyphs(face[0]);
            for (double size : sizes) {
                long font = MacFontFinderGoldenTest.font(face[1], size);
                int w = (int) Math.ceil(size * 1.1) + 4;
                int h = (int) Math.ceil(size * 1.4) + 4;
                double x = -2;
                double y = -Math.ceil(size * 0.35) - 2;
                for (int glyph : glyphs) {
                    for (int s = 0; s < subPixels.length; s++) {
                        String prefix = "ct.bitmap." + face[0] + ".s" + MacFontCorpus.sizeKey((float) size) + "."
                                + MacGoldenFormat.gid(glyph) + ".sp" + s;
                        drawGray(gray256, font, glyph, x, y, w, h, subPixels[s], false);
                        out.put(prefix + ".gray8",
                                FontGoldenFormat.bytesSummary(OSShim.CGBitmapContextGetData(gray256, w, h, 8)));
                        drawGray(rgb256, font, glyph, x, y, w, h, subPixels[s], false);
                        out.put(prefix + ".rgb24",
                                FontGoldenFormat.bytesSummary(OSShim.CGBitmapContextGetData(rgb256, w, h, 24)));
                        out.put(prefix + ".rgb8",
                                FontGoldenFormat.bytesSummary(OSShim.CGBitmapContextGetData(rgb256, w, h, 8)));
                        if (face[0].equals("helvetica") && size == 8 && s == 0) {
                            out.put(prefix + ".gray8.hex",
                                    FontGoldenFormat.bytesHex(OSShim.CGBitmapContextGetData(gray256, w, h, 8)));
                            out.put(prefix + ".rgb24.hex",
                                    FontGoldenFormat.bytesHex(OSShim.CGBitmapContextGetData(rgb256, w, h, 24)));
                        }
                    }
                }
                OSShim.CFRelease(font);
            }
        }

        long helvetica = MacFontFinderGoldenTest.font("Helvetica", 12);
        int glyphA = sampleGlyphs("helvetica")[0];
        drawGray(rgbPlain, helvetica, glyphA, -2, -6, 18, 22, 0.25, false);
        out.put("ct.bitmap.flagsOff.rgb24", FontGoldenFormat.bytesSummary(
                OSShim.CGBitmapContextGetData(rgbPlain, 18, 22, 24)));
        out.put("ct.bitmap.flagsOff.rgb8", FontGoldenFormat.bytesSummary(
                OSShim.CGBitmapContextGetData(rgbPlain, 18, 22, 8)));

        for (Object[] transform : new Object[][] {{"rot30", ROT30}, {"shear", SHEAR}}) {
            double[] m = (double[]) transform[1];
            double[] strikeMatrix = {m[0], -m[1], -m[2], m[3], 0, 0};
            for (double size : new double[] {12, 24}) {
                long font = OSShim.CTFontCreateCopyWithAttributes(helvetica, size, strikeMatrix, 0);
                int w = (int) Math.ceil(size * 1.6) + 4;
                int h = (int) Math.ceil(size * 1.6) + 4;
                double x = -Math.ceil(size * 0.4) - 2;
                double y = -Math.ceil(size * 0.4) - 2;
                String prefix = "ct.bitmap." + transform[0] + ".s" + (int) size + "." + MacGoldenFormat.gid(glyphA);
                drawGray(gray256, font, glyphA, x, y, w, h, 0, true);
                out.put(prefix + ".gray8",
                        FontGoldenFormat.bytesSummary(OSShim.CGBitmapContextGetData(gray256, w, h, 8)));
                out.put(prefix + ".ctm", MacGoldenFormat.doubles(MacFontOracleShim.contextCtm(gray256)));
                drawGray(rgb256, font, glyphA, x, y, w, h, 0, true);
                out.put(prefix + ".rgb24",
                        FontGoldenFormat.bytesSummary(OSShim.CGBitmapContextGetData(rgb256, w, h, 24)));
                OSShim.CFRelease(font);
            }
        }

        byte[] fullGray = OSShim.CGBitmapContextGetData(gray256, 256, 256, 8);
        byte[] fullRgb = OSShim.CGBitmapContextGetData(rgb256, 256, 256, 24);
        byte[] fullImage = OSShim.CGImageContextGetData(rgb256, 256, 256, 32);
        out.put("ct.bitmap.full.gray8", FontGoldenFormat.bytesSummary(fullGray));
        out.put("ct.bitmap.full.rgb24", FontGoldenFormat.bytesSummary(fullRgb));
        out.put("ct.bitmap.full.image32", FontGoldenFormat.bytesSummary(fullImage));
        out.put("ct.bitmap.bottomRows.gray8",
                FontGoldenFormat.bytesSummary(OSShim.CGBitmapContextGetData(gray256, 200, 3, 8)));
        putBitmapGuards(out, gray256, rgb256);

        int[] emojiGlyphs = sampleGlyphs("emoji");
        for (double size : new double[] {12, 24, 48, 96}) {
            long font = MacFontFinderGoldenTest.font("AppleColorEmoji", size);
            int w = (int) Math.ceil(size * 1.3) + 4;
            int h = (int) Math.ceil(size * 1.3) + 4;
            double x = -2;
            double y = -Math.ceil(size * 0.3) - 2;
            for (int glyph : emojiGlyphs) {
                for (boolean matrix : new boolean[] {false, true}) {
                    long context = context(true, w, h, true, gray, rgb);
                    double drawX = matrix ? 0 : x;
                    double drawY = matrix ? 0 : y;
                    if (matrix) {
                        OSShim.CGContextTranslateCTM(context, -x, -y);
                    }
                    OSShim.CTFontDrawGlyphs(font, (short) glyph, -drawX, -drawY, context);
                    if (matrix) {
                        OSShim.CGContextTranslateCTM(context, x, y);
                    }
                    String key = "ct.bitmap.color.s" + (int) size + "." + MacGoldenFormat.gid(glyph)
                            + (matrix ? ".matrix" : ".plain");
                    out.put(key, FontGoldenFormat.bytesSummary(OSShim.CGImageContextGetData(context, w, h, 32)));
                    OSShim.CGContextRelease(context);
                }
            }
            OSShim.CFRelease(font);
        }

        OSShim.CFRelease(helvetica);
        OSShim.CGContextRelease(gray256);
        OSShim.CGContextRelease(rgb256);
        OSShim.CGContextRelease(rgbPlain);
    }

    /**
     * Every case but {@code bottomRows} answers null. An 8-bit context read as 24 or 32 bits per pixel is absent:
     * the C read past the bitmap there.
     */
    private static void putBitmapGuards(GoldenMap out, long gray256, long rgb256) {
        Object[][] cases = {
            {"w0", gray256, 0, 10, 8}, {"h0", gray256, 10, 0, 8}, {"wNeg", gray256, -1, 10, 8},
            {"hNeg", gray256, 10, -1, 8}, {"wTooWide", gray256, 257, 10, 8}, {"hTooHigh", gray256, 10, 257, 8},
            {"bpp16", rgb256, 10, 10, 16}, {"bpp32", rgb256, 10, 10, 32}, {"bpp0", rgb256, 10, 10, 0},
            {"ctx0", 0L, 10, 10, 8}, {"ctx0bpp24", 0L, 10, 10, 24},
            {"intMaxWidth", gray256, Integer.MAX_VALUE, 2, 8}, {"intMaxArea", gray256, 1 << 30, 4, 8},
            {"intMaxWidth24", rgb256, Integer.MAX_VALUE / 2, 2, 24},
        };
        for (Object[] c : cases) {
            out.put("ct.bitmap.guard.bitmap." + c[0], FontGoldenFormat.bytesSummary(OSShim.CGBitmapContextGetData(
                    (Long) c[1], (Integer) c[2], (Integer) c[3], (Integer) c[4])));
        }
        Object[][] imageCases = {
            {"w0", rgb256, 0, 10, 32}, {"h0", rgb256, 10, 0, 32}, {"wNeg", rgb256, -1, 10, 32},
            {"hNeg", rgb256, 10, -1, 32}, {"wTooWide", rgb256, 257, 10, 32}, {"hTooHigh", rgb256, 10, 257, 32},
            {"bpp0", rgb256, 10, 10, 0}, {"bpp8", rgb256, 10, 10, 8}, {"bpp16", rgb256, 10, 10, 16},
            {"bpp24", rgb256, 10, 10, 24}, {"ctx0", 0L, 10, 10, 32},
            {"intMaxWidth", rgb256, Integer.MAX_VALUE / 3, 2, 32}, {"intMaxArea", rgb256, 1 << 28, 4, 32},
            {"bottomRows", rgb256, 64, 5, 32},
        };
        for (Object[] c : imageCases) {
            out.put("ct.bitmap.guard.image." + c[0], FontGoldenFormat.bytesSummary(OSShim.CGImageContextGetData(
                    (Long) c[1], (Integer) c[2], (Integer) c[3], (Integer) c[4])));
        }
        out.put("ct.bitmap.excluded", "grey context read as bpp 24 (CGBitmapContextGetData) or bpp 32"
                + " (CGImageContextGetData): undefined in the C (out-of-bounds read, audit R-12)");
    }

    private static int[] sampleGlyphs(String key) throws Exception {
        for (MacCoreTextGoldenTest.RawFace face : MacCoreTextGoldenTest.RAW_FACES) {
            if (face.key().equals(key)) {
                FontResource mapper = FontResourceShim.newUncachedFontFile(face.file(), face.index());
                String sample = switch (key) {
                    case "hiragino" -> "A\u65E5";
                    case "geezapro" -> "A\u0645";
                    case "emoji" -> "\uD83D\uDE00\uD83D\uDC4D";
                    default -> "Ag";
                };
                return MacFontCorpus.glyphs(mapper, sample);
            }
        }
        throw new IllegalArgumentException(key);
    }
}
