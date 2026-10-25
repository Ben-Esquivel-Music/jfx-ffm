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

import com.sun.javafx.font.DFontDecoderShim;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.Glyph;
import com.sun.javafx.font.MacFontFinderShim;
import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.PGFont;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.OSShim;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.transform.BaseTransform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import test.com.sun.javafx.font.FontGoldenHarness.Corpus;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * The child JVM of {@link MacFontStressTest}: concurrent use of the macOS font layer compared with serial use, and
 * the retain counts of the CF objects handed to the natives. Nothing it writes is machine-specific.
 */
public final class MacFontStressChild {

    static final String CONCURRENT = "stress.concurrent";
    static final String RETAIN = "stress.retain";

    static final int LAYOUT_THREADS = 3;
    static final int ROUNDS = 4;
    static final int RETAIN_CALLS = 20;

    private static final double[] FLIP = {1, 0, 0, -1, 0, 0};
    private static final double[] ROT30 = {0.8660254037844387, 0.5, -0.5, 0.8660254037844387, 3.25, -1.5};
    private static final Map<String, Integer> TABLE_TAGS = Map.of("loca", 0x6c6f6361, "glyf", 0x676c7966);

    private MacFontStressChild() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        GoldenMap out = new GoldenMap();
        switch (scenario) {
            case CONCURRENT -> concurrent(out);
            case RETAIN -> retain(out);
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        Files.writeString(Path.of(args[1]), FontGoldens.format(out, List.of("MacFontStressChild " + scenario)),
                          StandardCharsets.UTF_8);
        System.exit(0);
    }

    /**
     * One render thread draws masks, as the Prism render thread does; it alone uses {@code CTGlyph}'s shared cached
     * context. Each layout thread lays text out and reads outlines, bounds, advances and colour glyphs at its own
     * size, on its own font files, so only the bindings and CoreText are shared. Every corpus runs once serially,
     * then {@link #ROUNDS} times on all threads at once, and each round must equal the serial result.
     */
    private static void concurrent(GoldenMap out) throws Exception {
        List<Corpus> corpora = new ArrayList<>();
        List<String> names = new ArrayList<>();
        corpora.add(MacFontStressChild::render);
        names.add("render");
        for (int t = 0; t < LAYOUT_THREADS; t++) {
            float size = 12f + t;
            corpora.add(values -> layout(values, size));
            names.add("layout" + t);
        }
        List<Map<String, String>> serial = new ArrayList<>();
        for (Corpus corpus : corpora) {
            GoldenMap values = new GoldenMap();
            corpus.capture(values);
            serial.add(values);
        }
        CyclicBarrier start = new CyclicBarrier(corpora.size());
        List<Future<List<String>>> results = new ArrayList<>();
        try (ExecutorService threads = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory())) {
            for (int i = 0; i < corpora.size(); i++) {
                Corpus corpus = corpora.get(i);
                Map<String, String> expected = serial.get(i);
                results.add(threads.submit(() -> {
                    start.await();
                    List<String> mismatches = new ArrayList<>();
                    for (int round = 0; round < ROUNDS; round++) {
                        GoldenMap values = new GoldenMap();
                        corpus.capture(values);
                        mismatches.addAll(differences(expected, values));
                    }
                    return mismatches;
                }));
            }
            for (int i = 0; i < corpora.size(); i++) {
                List<String> mismatches = results.get(i).get();
                out.put("concurrent." + names.get(i) + ".values", Integer.toString(serial.get(i).size()));
                out.put("concurrent." + names.get(i) + ".mismatches", Integer.toString(mismatches.size()));
                out.put("concurrent." + names.get(i) + ".first", mismatches.isEmpty() ? "none" : mismatches.get(0));
            }
        }
    }

    private static List<String> differences(Map<String, String> expected, Map<String, String> actual) {
        List<String> keys = new ArrayList<>();
        expected.forEach((key, value) -> {
            if (!value.equals(actual.get(key))) {
                keys.add(key);
            }
        });
        actual.keySet().stream().filter(key -> !expected.containsKey(key)).forEach(keys::add);
        return keys;
    }

    private static void render(GoldenMap out) throws Exception {
        for (MacFontCorpus.Face face : MacFontCorpus.FACES) {
            if (face.key().equals("emoji")) {
                continue;
            }
            FontResource resource = openIfPresent(face);
            if (resource == null) {
                continue;
            }
            int[] glyphs = MacFontCorpus.glyphs(resource, face.sample());
            String prefix = "render." + face.key();
            MacFontCorpus.putStrike(out, prefix + ".s12", resource, 12f, BaseTransform.IDENTITY_TRANSFORM,
                                    FontResource.AA_GREYSCALE, glyphs, 4, false);
            MacFontCorpus.putStrike(out, prefix + ".s24", resource, 24f, BaseTransform.IDENTITY_TRANSFORM,
                                    FontResource.AA_GREYSCALE, glyphs, 1, false);
            for (MacFontCorpus.Transform transform : MacFontCorpus.TRANSFORMS) {
                MacFontCorpus.putStrike(out, prefix + "." + transform.key(), resource, 24f, transform.transform(),
                                        FontResource.AA_GREYSCALE, glyphs, 1, false);
            }
        }
    }

    private static void layout(GoldenMap out, float size) throws Exception {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        for (String name : new String[] {"System", "Helvetica", "Times", "Hiragino Sans", "Geeza Pro"}) {
            PGFont font = factory.createFont(name, size);
            for (int i = 0; i < MacFontGoldens.TEXTS.size(); i++) {
                String key = "layout." + name.replace(' ', '_') + ".t" + FontGoldenFormat.pad(i, 2);
                FontGoldenFormat.putLayout(out, key, font, MacFontGoldens.TEXTS.get(i), i == 5);
            }
        }
        for (MacFontCorpus.Face face : MacFontCorpus.FACES) {
            FontResource resource = openIfPresent(face);
            if (resource == null) {
                continue;
            }
            int[] glyphs = MacFontCorpus.glyphs(resource, face.sample());
            String prefix = "outline." + face.key();
            for (int gc : MacFontCorpus.distinct(glyphs)) {
                String glyph = prefix + "." + MacGoldenFormat.gid(gc);
                out.put(glyph + ".box", FontGoldenFormat.joinFloats(resource.getGlyphBoundingBox(gc, size, null))
                        + "|" + Float.toString(resource.getAdvance(gc, size)));
            }
            putOutlines(out, prefix + ".s" + MacFontCorpus.sizeKey(size), resource,
                        resource.getStrike(size, BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE), glyphs);
            BaseTransform rotate = BaseTransform.getRotateInstance(Math.toRadians(30), 0, 0);
            putOutlines(out, prefix + ".rotate30", resource,
                        resource.getStrike(size * 2, rotate, FontResource.AA_GREYSCALE), glyphs);
        }
        long path = MacFontOracleShim.createPath(new double[] {0, 0, 0}, new double[] {1, 10, 0},
                new double[] {2, 15, 5, 10, 10}, new double[] {3, 5, 15, 0, 15, -5, 10}, new double[] {4});
        out.put("raw.pathApply", FontGoldenFormat.outlineSummary(OSShim.CGPathApply(path)) + "|"
                + MacGoldenFormat.doubles(OSShim.CGPathGetPathBoundingBox(path)));
        MacFontOracleShim.releasePath(path);
        double[] rect = {1, 2, 3 + size, 4};
        OSShim.CGRectApplyAffineTransform(rect, new double[] {2, 0.5, -0.25, 3, 10, 20});
        out.put("raw.rectApply", MacGoldenFormat.doubles(rect));
    }

    /** The face's font file, or null on a macOS that does not ship it. */
    private static FontResource openIfPresent(MacFontCorpus.Face face) throws Exception {
        return Files.isRegularFile(Path.of(face.file()))
                ? FontResourceShim.newUncachedFontFile(face.file(), face.index()) : null;
    }

    /** Outline, bounds and advance per glyph; pixels only for colour glyphs, which draw into their own context. */
    private static void putOutlines(GoldenMap out, String prefix, FontResource resource, FontStrike strike,
                                    int[] glyphs) {
        for (int gc : MacFontCorpus.distinct(glyphs)) {
            Glyph glyph = strike.getGlyph(gc);
            RectBounds bounds = glyph.getBBox();
            String value = FontGoldenFormat.shapeSummary(glyph.getShape()) + "|"
                    + FontGoldenFormat.joinFloats(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(),
                                                  bounds.getMaxY()) + "|" + Float.toString(glyph.getPixelXAdvance());
            if (resource.isColorGlyph(gc)) {
                value += "|" + FontGoldenFormat.bytesSummary(glyph.getPixelData());
            }
            out.put(prefix + "." + MacGoldenFormat.gid(gc), value);
        }
    }

    /**
     * The change of each CF object's retain count between call {@link #RETAIN_CALLS} and call twice that of a native
     * handed the object, so that caches filled by the first calls do not count; and the retain count of what each
     * Create native returns.
     */
    private static void retain(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 13.25);
        short glyph = 36;
        delta(out, "CTFontCreatePathForGlyph.font", font,
                () -> OSShim.CGPathRelease(OSShim.CTFontCreatePathForGlyph(font, glyph, FLIP)));
        delta(out, "CTFontGetAdvancesForGlyphs.font", font,
                () -> OSShim.CTFontGetAdvancesForGlyphs(font, 0, glyph, new double[2]));
        delta(out, "CTFontGetBoundingRectForGlyphUsingTables.font", font,
                () -> OSShim.CTFontGetBoundingRectForGlyphUsingTables(font, glyph, (short) 1, new int[4]));
        delta(out, "CTFontGetBoundingRectForGlyphs.font", font,
                () -> OSShim.CTFontGetBoundingRectForGlyphs(font, glyph));
        delta(out, "CTFontCopyAttributeDisplayName.font", font, () -> OSShim.CTFontCopyAttributeDisplayName(font));
        delta(out, "CTFontCopyURLAttribute.font", font, () -> OSShim.CTFontCopyURLAttribute(font));
        delta(out, "CTFontCreateCopyWithAttributes.font", font,
                () -> OSShim.CFRelease(OSShim.CTFontCreateCopyWithAttributes(font, 20, ROT30, 0)));
        delta(out, "MacFontFinder.getCascadeList.font", font, () -> MacFontFinderShim.getCascadeList(font));

        long gray = OSShim.CGColorSpaceCreateDeviceGray();
        long context = OSShim.CGBitmapContextCreate(0, 64, 64, 8, 64, gray, OSShim.constant("kCGImageAlphaNone"));
        created(out, "CGBitmapContextCreate", context);
        delta(out, "CTFontDrawGlyphs.font", font, () -> OSShim.CTFontDrawGlyphs(font, glyph, 4, 4, context));
        delta(out, "CTFontDrawGlyphs.context", context, () -> OSShim.CTFontDrawGlyphs(font, glyph, 4, 4, context));
        delta(out, "CGBitmapContextGetData.context", context, () -> OSShim.CGBitmapContextGetData(context, 64, 64, 8));
        delta(out, "CGContextFillRect.context", context,
                () -> OSShim.CGContextFillRect(context, new double[] {0, 0, 8, 8}));
        long rgb = OSShim.CGColorSpaceCreateDeviceRGB();
        int bgra = OSShim.constant("kCGBitmapByteOrder32Host") | OSShim.constant("kCGImageAlphaPremultipliedFirst");
        long rgbContext = OSShim.CGBitmapContextCreate(0, 32, 32, 8, 128, rgb, bgra);
        delta(out, "CGImageContextGetData.context", rgbContext,
                () -> OSShim.CGImageContextGetData(rgbContext, 32, 32, 32));

        long path = OSShim.CTFontCreatePathForGlyph(font, glyph, null);
        created(out, "CTFontCreatePathForGlyph", path);
        delta(out, "CGPathApply.path", path, () -> OSShim.CGPathApply(path));
        delta(out, "CGPathGetPathBoundingBox.path", path, () -> OSShim.CGPathGetPathBoundingBox(path));

        long alloc = OSShim.kCFAllocatorDefault();
        long text = OSShim.CFStringCreate("A string too long to be a tagged pointer, held by this probe alone");
        created(out, "CFStringCreate", text);
        long attributes = OSShim.CFDictionaryCreateMutable(alloc, 4, OSShim.kCFTypeDictionaryKeyCallBacks(),
                                                           OSShim.kCFTypeDictionaryValueCallBacks());
        created(out, "CFDictionaryCreateMutable", attributes);
        OSShim.CFDictionaryAddValue(attributes, OSShim.kCTFontAttributeName(), font);
        delta(out, "CFAttributedStringCreate.string", text,
                () -> OSShim.CFRelease(OSShim.CFAttributedStringCreate(alloc, text, attributes)));
        delta(out, "CFAttributedStringCreate.attributes", attributes,
                () -> OSShim.CFRelease(OSShim.CFAttributedStringCreate(alloc, text, attributes)));
        long attributed = OSShim.CFAttributedStringCreate(alloc, text, attributes);
        created(out, "CFAttributedStringCreate", attributed);
        delta(out, "CTLineCreateWithAttributedString.string", attributed,
                () -> OSShim.CFRelease(OSShim.CTLineCreateWithAttributedString(attributed)));
        long line = OSShim.CTLineCreateWithAttributedString(attributed);
        created(out, "CTLineCreateWithAttributedString", line);
        delta(out, "CTLineGetGlyphRuns.line", line, () -> OSShim.CTLineGetGlyphRuns(line));
        delta(out, "CTLineGetTypographicBounds.line", line, () -> (long) OSShim.CTLineGetTypographicBounds(line));
        long run = OSShim.CFArrayGetValueAtIndex(OSShim.CTLineGetGlyphRuns(line), 0);
        int count = (int) OSShim.CTLineGetGlyphCount(line);
        delta(out, "CTRunGetGlyphs.run", run, () -> OSShim.CTRunGetGlyphs(run, 0, 0, new int[count]));
        delta(out, "CTRunGetPositions.run", run, () -> OSShim.CTRunGetPositions(run, 0, new float[count * 2]));
        delta(out, "CTRunGetStringIndices.run", run, () -> OSShim.CTRunGetStringIndices(run, 0, new int[count]));
        delta(out, "CTRunGetAttributes.run", run, () -> OSShim.CTRunGetAttributes(run));
        long style = OSShim.CTParagraphStyleCreate(0);
        created(out, "CTParagraphStyleCreate", style);

        long fontPath = OSShim.CFStringCreate(MacFontGoldens.pinned("ahem").toString());
        long url = OSShim.CFURLCreateWithFileSystemPath(alloc, fontPath, OSShim.constant("kCFURLPOSIXPathStyle"),
                                                        false);
        created(out, "CFURLCreateWithFileSystemPath", url);
        delta(out, "CFURLCreateWithFileSystemPath.path", fontPath, () -> OSShim.CFRelease(
                OSShim.CFURLCreateWithFileSystemPath(alloc, fontPath, OSShim.constant("kCFURLPOSIXPathStyle"), false)));
        delta(out, "CGDataProviderCreateWithURL.url", url,
                () -> OSShim.CFRelease(OSShim.CGDataProviderCreateWithURL(url)));
        long provider = OSShim.CGDataProviderCreateWithURL(url);
        delta(out, "CGFontCreateWithDataProvider.provider", provider,
                () -> OSShim.CFRelease(OSShim.CGFontCreateWithDataProvider(provider)));
        long cgFont = OSShim.CGFontCreateWithDataProvider(provider);
        delta(out, "CTFontCreateWithGraphicsFont.cgFont", cgFont,
                () -> OSShim.CFRelease(OSShim.CTFontCreateWithGraphicsFont(cgFont, 12, ROT30, 0)));
        long ahem = OSShim.CTFontCreateWithGraphicsFont(cgFont, 12, null, 0);
        tables(out, ahem);
        OSShim.CFRelease(ahem);

        // CoreText makes a new UI font per call over one cached CGFont: only the change of the CGFont's count is ours.
        long uiFont = MacFontOracleShim.uiFont(false, 12);
        long uiCgFont = MacFontOracleShim.graphicsFont(uiFont);
        delta(out, "CTFontCreateUIFontForLanguage.cgFont", uiCgFont,
                () -> OSShim.CFRelease(OSShim.CTFontCreateUIFontForLanguage(12, ROT30, false)));
        delta(out, "CTFontCreateUIFontForLanguage.noMatrix.cgFont", uiCgFont,
                () -> OSShim.CFRelease(OSShim.CTFontCreateUIFontForLanguage(12, null, false)));
        long dotName = OSShim.CFStringCreate(".SFNS-Regular");
        delta(out, "CTFontCreateWithName.dotName.cgFont", uiCgFont,
                () -> OSShim.CFRelease(OSShim.CTFontCreateWithName(dotName, 12, ROT30)));
        delta(out, "CTFontCreateWithName.dotName", dotName,
                () -> OSShim.CFRelease(OSShim.CTFontCreateWithName(dotName, 12, ROT30)));
        long name = OSShim.CFStringCreate("Helvetica-Bold");
        delta(out, "CTFontCreateWithName.name", name,
                () -> OSShim.CFRelease(OSShim.CTFontCreateWithName(name, 12, ROT30)));
        delta(out, "control.retainOncePerCall", font, () -> OSShim.CFRetain(font));
        for (int i = 0; i < 2 * RETAIN_CALLS; i++) {
            OSShim.CFRelease(font);
        }

        long decoded = DFontDecoderShim.createCTFont("Helvetica");
        delta(out, "DFontDecoder.getCTFontTable.font", decoded,
                () -> DFontDecoderShim.getCTFontTable(decoded, 0x68656164).length);
        delta(out, "DFontDecoder.getCTFontTags.font", decoded, () -> DFontDecoderShim.getCTFontTags(decoded).length);
        delta(out, "DFontDecoder.getCTFontFormat.font", decoded, () -> DFontDecoderShim.getCTFontFormat(decoded));
        DFontDecoderShim.releaseCTFont(decoded);

        for (long object : new long[] {name, dotName, uiCgFont, uiFont, cgFont, provider, url, fontPath, style, line,
                                       attributed, attributes, text, path, font}) {
            OSShim.CFRelease(object);
        }
        OSShim.CGContextRelease(rgbContext);
        OSShim.CGContextRelease(context);
        OSShim.CFRelease(rgb);
        OSShim.CFRelease(gray);
    }

    /**
     * CoreText caches each table of a font and {@code CTFontCopyTable} returns it at +1, so a table copy the native
     * does not release shows in the cached table's retain count. Glyph 4 of Ahem has a header in its short
     * {@code loca}, so every call copies both tables. Each control copies its table once per call, which proves that
     * the probe watches the object the native copies.
     */
    private static void tables(GoldenMap out, long ahem) throws Exception {
        short glyph = 4;
        short shortLoca = 0;
        if (!OSShim.CTFontGetBoundingRectForGlyphUsingTables(ahem, glyph, shortLoca, new int[4])) {
            throw new IllegalStateException("Ahem glyph 4 has no glyf header, so no call would copy glyf");
        }
        for (Map.Entry<String, Integer> table : TABLE_TAGS.entrySet()) {
            int tag = table.getValue();
            long data = MacFontOracleShim.copyFontTable(ahem, tag);
            delta(out, "control.copyTableOncePerCall." + table.getKey(), data,
                    () -> MacFontOracleShim.copyFontTable(ahem, tag));
            for (int i = 0; i < 2 * RETAIN_CALLS; i++) {
                MacFontOracleShim.release(data);
            }
            delta(out, "CTFontGetBoundingRectForGlyphUsingTables." + table.getKey(), data,
                    () -> OSShim.CTFontGetBoundingRectForGlyphUsingTables(ahem, glyph, shortLoca, new int[4]));
            MacFontOracleShim.release(data);
        }
    }

    private interface Call {
        Object call() throws Exception;
    }

    private static void delta(GoldenMap out, String probe, long object, Call call) throws Exception {
        for (int i = 0; i < RETAIN_CALLS; i++) {
            call.call();
        }
        long before = MacFontOracleShim.retainCount(object);
        for (int i = 0; i < RETAIN_CALLS; i++) {
            call.call();
        }
        out.put("retain.delta." + probe, Long.toString(MacFontOracleShim.retainCount(object) - before));
    }

    private static void delta(GoldenMap out, String probe, long object, Runnable call) throws Exception {
        delta(out, probe, object, () -> {
            call.run();
            return null;
        });
    }

    private static void created(GoldenMap out, String function, long object) {
        out.put("retain.created." + function, Long.toString(MacFontOracleShim.retainCount(object)));
    }
}
