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
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.Glyph;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.transform.BaseTransform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Java-path corpus of the macOS goldens: metrics, masks and outlines through {@code CTFontFile},
 * {@code CTFontStrike} and {@code CTGlyph}, on font files opened outside the factory's caches so that no earlier
 * lookup changes what is recorded.
 */
final class MacFontCorpus {

    record Face(String key, String file, int index, String postScriptName, String sample) {
    }

    static final String SAMPLE_LATIN = "Ag\u00E9W .";

    static final List<Face> FACES = List.of(
            new Face("helvetica", MacFontGoldens.SYSTEM_FONTS + "/Helvetica.ttc", 0, "Helvetica", SAMPLE_LATIN),
            new Face("times", MacFontGoldens.SYSTEM_FONTS + "/Times.ttc", 0, "Times-Roman", SAMPLE_LATIN),
            new Face("timesnewroman", MacFontGoldens.SYSTEM_FONTS + "/Supplemental/Times New Roman.ttf", 0,
                     "TimesNewRomanPSMT", SAMPLE_LATIN),
            new Face("sfns", MacFontGoldens.SYSTEM_FONTS + "/SFNS.ttf", 0, ".SFNS-Regular", SAMPLE_LATIN),
            new Face("hiragino", MacFontGoldens.SYSTEM_FONTS + "/" + MacFontGoldens.HIRAGINO_SANS + " W3.ttc", 0,
                     "HiraginoSans-W3",
                     "A\u65E5\u3042"),
            new Face("stheiti", MacFontGoldens.SYSTEM_FONTS + "/STHeiti Light.ttc", 1, "STHeitiSC-Light",
                     "A\u4E2D"),
            new Face("geezapro", MacFontGoldens.SYSTEM_FONTS + "/GeezaPro.ttc", 0, "GeezaPro", "A\u0645\u0627"),
            new Face("emoji", MacFontGoldens.SYSTEM_FONTS + "/Apple Color Emoji.ttc", 0, "AppleColorEmoji",
                     "\uD83D\uDE00\uD83D\uDC4D"));

    /** The bands of {@code CTFontStrike}'s subpixel rules, and both sides of the 80 pt shape limit. */
    static final float[] SIZES = {0f, 8f, 11.5f, 12f, 13f, 15f, 17.9f, 18f, 24f, 33.9f, 34f, 72f, 96f};

    /** Glyph boxes past the 256 pixel cached context, and past the 320 pixel limit that zeroes the bounds. */
    static final float[] LARGE_SIZES = {150f, 200f, 260f, 330f, 400f};

    record Transform(String key, BaseTransform transform) {
    }

    static final List<Transform> TRANSFORMS = List.of(
            new Transform("translate", BaseTransform.getTranslateInstance(0.3, 0.7)),
            new Transform("scale2", BaseTransform.getScaleInstance(2, 2)),
            new Transform("rotate30", BaseTransform.getRotateInstance(Math.toRadians(30), 0, 0)),
            new Transform("shear", BaseTransform.getInstance(1, 0, 0.3, 1, 0, 0)),
            new Transform("mirrorx", BaseTransform.getScaleInstance(-1, 1)));

    private MacFontCorpus() {
    }

    static FontResource open(Face face) throws Exception {
        FontResource resource = FontResourceShim.newUncachedFontFile(face.file(), face.index());
        if (!face.postScriptName().equals(resource.getPSName())) {
            fail("index " + face.index() + " of " + face.file() + " is " + resource.getPSName() + ", not "
                    + face.postScriptName());
        }
        return resource;
    }

    static int[] glyphs(FontResource resource, String text) {
        List<Integer> codes = new ArrayList<>();
        text.codePoints().forEach(cp -> codes.add(resource.getGlyphMapper().charToGlyph(cp)));
        int[] result = new int[codes.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = codes.get(i);
        }
        return result;
    }

    /** {@code prefix.res} = names, {@code prefix.gNNNN.metrics} = bounding box and advance at 12 pt. */
    static void putMetrics(GoldenMap out, String prefix, FontResource resource, int[] glyphs) {
        out.put(prefix + ".res", MacGoldenFormat.text(resource.getFullName()) + "|"
                + MacGoldenFormat.text(resource.getPSName()) + "|"
                + MacGoldenFormat.text(resource.getFamilyName()) + "|"
                + resource.isBold() + "|" + resource.isItalic() + "|" + resource.isEmbeddedFont() + "|"
                + FontResourceShim.numGlyphs(resource));
        for (int gc : distinct(glyphs)) {
            float[] box = resource.getGlyphBoundingBox(gc, 12f, null);
            out.put(prefix + "." + MacGoldenFormat.gid(gc) + ".metrics", FontGoldenFormat.joinFloats(box) + "|"
                    + Float.toString(resource.getAdvance(gc, 12f)) + "|" + resource.isColorGlyph(gc));
        }
    }

    /**
     * One strike of {@code resource} and, per glyph, {@code xAdvance;yAdvance;width;height;originX;originY;lcd} then
     * {@code |} and {@code length;sha256} of {@code getPixelData(subPixel)} for each subpixel index, then
     * {@code |bbox} and, when {@code shapes}, {@code |} and the glyph outline.
     */
    static void putStrike(GoldenMap out, String prefix, FontResource resource, float size, BaseTransform transform,
                          int aaMode, int[] glyphs, int subPixels, boolean shapes) {
        FontStrike strike = resource.getStrike(size, transform, aaMode);
        out.put(prefix + ".strike", strike.drawAsShapes() + "|" + strike.getAAMode() + "|"
                + Float.toString(strike.getSize()));
        for (int gc : distinct(glyphs)) {
            Glyph glyph = strike.getGlyph(gc);
            StringBuilder value = new StringBuilder();
            value.append(Float.toString(glyph.getPixelXAdvance())).append(';')
                 .append(Float.toString(glyph.getPixelYAdvance())).append(';')
                 .append(glyph.getWidth()).append(';').append(glyph.getHeight()).append(';')
                 .append(glyph.getOriginX()).append(';').append(glyph.getOriginY()).append(';')
                 .append(glyph.isLCDGlyph());
            for (int sp = 0; sp < subPixels; sp++) {
                value.append('|').append(FontGoldenFormat.bytesSummary(glyph.getPixelData(sp)));
            }
            RectBounds bounds = glyph.getBBox();
            value.append('|').append(FontGoldenFormat.joinFloats(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(),
                                                           bounds.getMaxY()));
            if (shapes) {
                value.append('|').append(FontGoldenFormat.shapeSummary(glyph.getShape()));
            }
            out.put(prefix + "." + MacGoldenFormat.gid(gc), value.toString());
        }
    }

    /**
     * The mask corpus of one face: every size band (four subpixel positions below 34 pt), the five transforms at
     * three sizes, and outlines at 12 and 96 pt.
     */
    static void putMasks(GoldenMap out, String prefix, FontResource resource, int[] glyphs, int aaMode) {
        for (float size : SIZES) {
            putStrike(out, prefix + ".s" + sizeKey(size), resource, size, BaseTransform.IDENTITY_TRANSFORM, aaMode,
                      glyphs, size < 34f ? 4 : 1, size == 12f || size == 96f);
        }
        for (Transform transform : TRANSFORMS) {
            for (float size : new float[] {12f, 24f, 96f}) {
                putStrike(out, prefix + "." + transform.key() + ".s" + sizeKey(size), resource, size,
                          transform.transform(), aaMode, glyphs, 1, size == 24f);
            }
        }
    }

    static void putLargeMasks(GoldenMap out, String prefix, FontResource resource, int[] glyphs, int aaMode) {
        for (float size : LARGE_SIZES) {
            putStrike(out, prefix + ".large.s" + sizeKey(size), resource, size, BaseTransform.IDENTITY_TRANSFORM,
                      aaMode, glyphs, 1, false);
        }
    }

    static int[] distinct(int[] glyphs) {
        return Arrays.stream(glyphs).distinct().toArray();
    }

    static String sizeKey(float size) {
        String text = Float.toString(size);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text.replace('.', 'p');
    }
}
