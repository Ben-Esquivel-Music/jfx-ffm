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
import com.sun.javafx.font.coretext.CTGlyphShim;
import com.sun.javafx.font.coretext.OSShim;
import com.sun.javafx.geom.Path2D;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity oracle for the {@code coretext.OS} natives, against {@value #GOLDEN}, captured from the JNI build of
 * commit {@value MacFontGoldens#CAPTURE_COMMIT}. It runs in this JVM and registers nothing. The
 * {@code loca}/{@code glyf} parser, the {@code CGPathApply} applier and the pixel readers ({@link MacBitmapCorpus})
 * are Java ({@code GlyfTableBounds}, {@code CGPathSink}, {@code CGBitmapReader}), so their records are exact: raw
 * float bits, sha256 of every mask, and a sentinel in output arrays to catch partial writes.
 * <p>
 * Left out on purpose, because the C has no defined result there:
 * <ul>
 * <li>{@code CGBitmapContextGetData(ctx, w, h, 24)} and {@code CGImageContextGetData(ctx, w, h, 32)} on an 8-bit grey
 * context read up to 3 bytes past the bitmap. Production never pairs them. For the same reason no
 * AA_LCD strike is requested here: with {@code prism.lcdtext} off, {@code CTGlyph} would draw it into its cached grey
 * context. LCD masks come from the child JVM run with {@code -Dprism.lcdtext=true};</li>
 * <li>{@code CFRetain(0)}, {@code CFRelease(0)}, a null {@code CGRect} mirror, a missing table tag, and any other
 * NULL handed to CoreFoundation crash the JVM (US-067);</li>
 * <li>{@code CFStringCreateWithCharacters(alloc, null, n)} with n &gt; 0 passes NULL with a length.</li>
 * </ul>
 */
@EnabledOnOs(OS.MAC)
public class MacCoreTextGoldenTest {

    static final String GOLDEN = "mac-coretext-golden.txt";

    private static final ParityGate.Ledger LEDGER = ParityGate.ledger(MacCoreTextGoldenTest.class);

    static final double[] IDENTITY = {1, 0, 0, 1, 0, 0};
    static final double[] FLIP = {1, 0, 0, -1, 0, 0};
    static final double[] ROT30 = {Math.cos(Math.PI / 6), Math.sin(Math.PI / 6), -Math.sin(Math.PI / 6),
                                   Math.cos(Math.PI / 6), 0, 0};
    static final double[] SHEAR = {1, 0, 0.3, 1, 0, 0};

    /** The CTFont comes from the PostScript name; the file only feeds the Java glyph mapper. */
    record RawFace(String key, String postScriptName, String file, int index, String sample, int[] extra) {
    }

    static final String FONTS = MacFontGoldens.SYSTEM_FONTS;

    static final List<RawFace> RAW_FACES = List.of(
            new RawFace("helvetica", "Helvetica", FONTS + "/Helvetica.ttc", 0, "AgW .\u00E9\u00C5", new int[0]),
            new RawFace("times", "Times-Roman", FONTS + "/Times.ttc", 0, "AgW .\u00E9\u00C5", new int[0]),
            new RawFace("timesnewroman", "TimesNewRomanPSMT", FONTS + "/Supplemental/Times New Roman.ttf", 0,
                        "AgW .\u00E9\u00C5", new int[0]),
            new RawFace("menlo", "Menlo-Regular", FONTS + "/Menlo.ttc", 0, "AgW .\u00E9", new int[0]),
            new RawFace("couriernew", "CourierNewPSMT", FONTS + "/Supplemental/Courier New.ttf", 0, "AgW .\u00E9",
                        new int[0]),
            new RawFace("sfns", ".SFNS-Regular", FONTS + "/SFNS.ttf", 0, "AgW .\u00E9", new int[0]),
            new RawFace("geezapro", "GeezaPro", FONTS + "/GeezaPro.ttc", 0, "A\u0645\u0627\u0644", new int[0]),
            new RawFace("hiragino", "HiraginoSans-W3", MacFontCorpus.FACES.get(4).file(), 0, "A\u65E5\u3042",
                        new int[0]),
            new RawFace("applesdgothicneo", "AppleSDGothicNeo-Regular", FONTS + "/AppleSDGothicNeo.ttc", 0,
                        "A\uD55C", new int[0]),
            new RawFace("stheiti", "STHeitiSC-Light", FONTS + "/STHeiti Light.ttc", 1, "A\u4E2D",
                        new int[] {0x7FFF, 0x8000, 0x8001, 0x9000, 0xA5A5, 0xBFFF, 0xCC2A, 0xCC2B}),
            new RawFace("emoji", "AppleColorEmoji", FONTS + "/Apple Color Emoji.ttc", 0, "\uD83D\uDE00\uD83D\uDC4DA",
                        new int[0]));

    /** Not installed, so reached through a CGFont, as embedded fonts are. */
    static final List<String> GRAPHICS_FONTS = List.of("ahem", "fontwithfeaturesotf", "fontwithfeaturesttf",
                                                       "weightwatcher400");

    @Test
    public void coreTextMatchesTheJniGolden() throws Exception {
        MacFontGoldens.captureOrVerify(MacCoreTextGoldenTest.class, GOLDEN, header(), true,
                                       MacCoreTextGoldenTest::capture, MacCoreTextGoldenTest::assertCoverage);
    }

    @AfterAll
    static void theOracleRan() {
        LEDGER.assertOracleRan();
    }

    /** Runs on any macOS, where the gated golden would skip. */
    @Test
    public void helveticaRenders() {
        OSShim.ensureLoaded();
        long font = MacFontFinderGoldenTest.font("Helvetica", 24);
        assertNotEquals(0L, font, "CTFontCreateWithName(Helvetica)");
        assertEquals("Helvetica", MacFontOracleShim.fontPostScriptName(font), "PostScript name");
        long path = OSShim.CTFontCreatePathForGlyph(font, (short) 36, null);
        assertNotEquals(0L, path, "a path for glyph 36");
        Path2D outline = OSShim.CGPathApply(path);
        assertNotNull(outline, "CGPathApply");
        assertTrue(outline.getNumCommands() > 3, "the outline has segments");
        OSShim.CGPathRelease(path);
        long space = OSShim.CGColorSpaceCreateDeviceGray();
        long context = OSShim.CGBitmapContextCreate(0, 32, 32, 8, 32, space, OSShim.constant("kCGImageAlphaNone"));
        OSShim.CGContextSetRGBFillColor(context, 1, 1, 1, 1);
        OSShim.CGContextFillRect(context, new double[] {0, 0, 32, 32});
        OSShim.CGContextSetRGBFillColor(context, 0, 0, 0, 1);
        OSShim.CTFontDrawGlyphs(font, (short) 36, 4, 6, context);
        byte[] mask = OSShim.CGBitmapContextGetData(context, 32, 32, 8);
        assertNotNull(mask, "mask");
        int ink = 0;
        for (byte b : mask) {
            ink += b != 0 ? 1 : 0;
        }
        assertTrue(ink > 20, "the mask has ink (" + ink + " pixels)");
        OSShim.CGContextRelease(context);
        OSShim.CFRelease(font);
    }

    static List<String> header() {
        return MacFontGoldens.header("macOS CoreText golden (coretext.OS natives, in-process)", List.of(
                "  ct.data.*    data-symbol natives      ct.str.*     CFStringCreateWithCharacters / CFStringCreate",
                "  ct.url.*     CFURLCreateWithFileSystemPath           ct.dict.*    CFDictionary + retain deltas",
                "  ct.para.*    CTParagraphStyleCreate   ct.font.*    font creation (ps;size;matrix;url;display)",
                "  ct.rect.*    CGRectApplyAffineTransform: rect|matrix|result",
                "  ct.cgfont.*  CGDataProvider/CGFont/CTFontCreateWithGraphicsFont",
                "  ct.glyph.<face>.<gid>   adv0;w;h|adv0(null size)|adv2;w;h|CTFontGetBoundingRectForGlyphs",
                "  ct.tables.<face>.f<fmt> CTFontGetBoundingRectForGlyphUsingTables: true count;sha256 of gid:result",
                "  ct.path.<face>.<gid>.<matrix>  CGPathGetPathBoundingBox|CGPathApply outline|oracle elements",
                "  ct.pathapply.*          CGPathApply on oracle-built paths",
                "  ct.bitmap.*             CGBitmapContextGetData / CGImageContextGetData: length;sha256",
                "  ct.line.*               the CTLine pipeline, raw",
                "  ct.java.<face>.*        Java path: metrics, strikes (xadv;yadv;w;h;ox;oy;lcd|masks|bbox|shape)"));
    }

    static void capture(GoldenMap out) throws Exception {
        OSShim.ensureLoaded();
        putData(out);
        putStrings(out);
        putUrls(out);
        putDictionary(out);
        putParagraphStyles(out);
        putRects(out);
        putFontCreation(out);
        putGraphicsFonts(out);
        for (RawFace face : RAW_FACES) {
            long font = MacFontFinderGoldenTest.font(face.postScriptName(), 12);
            FontResource mapper = FontResourceShim.newUncachedFontFile(face.file(), face.index());
            putFace(out, face.key(), font, mapper, face.sample(), face.extra());
            OSShim.CFRelease(font);
        }
        for (String key : GRAPHICS_FONTS) {
            Path file = MacFontGoldens.pinned(key);
            long[] cg = graphicsFont(file);
            if (cg[2] == 0) {
                throw new AssertionError("no CGFont for " + file);
            }
            long font = OSShim.CTFontCreateWithGraphicsFont(cg[2], 12, null, 0);
            FontResource mapper = FontResourceShim.newUncachedFontFile(file.toString(), 0);
            putFace(out, key, font, mapper, "AgX .", new int[0]);
            OSShim.CFRelease(font);
            releaseGraphicsFont(cg);
        }
        putPathApply(out);
        MacBitmapCorpus.putBitmaps(out);
        putLines(out);
        putJavaPath(out);
    }

    private static void putData(GoldenMap out) {
        out.put("ct.data.kCFAllocatorDefault", Long.toHexString(OSShim.kCFAllocatorDefault()) + "|"
                + (OSShim.kCFAllocatorDefault() == MacFontOracleShim.dataSymbol("kCFAllocatorDefault")));
        for (String name : List.of("kCFTypeDictionaryKeyCallBacks", "kCFTypeDictionaryValueCallBacks")) {
            long value = name.endsWith("KeyCallBacks") ? OSShim.kCFTypeDictionaryKeyCallBacks()
                    : OSShim.kCFTypeDictionaryValueCallBacks();
            out.put("ct.data." + name, "address=" + (value == MacFontOracleShim.dataSymbol(name)) + "|nonzero="
                    + (value != 0));
        }
        long font = OSShim.kCTFontAttributeName();
        out.put("ct.data.kCTFontAttributeName", (font == MacFontOracleShim.dataSymbol("kCTFontAttributeName"))
                + "|" + MacFontOracleShim.cfTypeName(font) + "|"
                + MacGoldenFormat.text(MacFontOracleShim.cfString(font)));
        long paragraph = OSShim.kCTParagraphStyleAttributeName();
        out.put("ct.data.kCTParagraphStyleAttributeName",
                (paragraph == MacFontOracleShim.dataSymbol("kCTParagraphStyleAttributeName")) + "|"
                + MacFontOracleShim.cfTypeName(paragraph) + "|"
                + MacGoldenFormat.text(MacFontOracleShim.cfString(paragraph)));
    }

    private static void putStrings(GoldenMap out) {
        long alloc = OSShim.kCFAllocatorDefault();
        List<String> texts = MacFontGoldens.TEXTS;
        for (int i = 0; i < texts.size(); i++) {
            char[] chars = texts.get(i).toCharArray();
            out.put("ct.str.s" + FontGoldenFormat.pad(i, 2),
                    describeString(OSShim.CFStringCreateWithCharacters(alloc, chars, chars.length)));
        }
        out.put("ct.str.prefix", describeString(OSShim.CFStringCreateWithCharacters(alloc, "Hello".toCharArray(), 3)));
        out.put("ct.str.nullEmpty", describeString(OSShim.CFStringCreateWithCharacters(alloc, null, 0)));
        out.put("ct.str.create.latin", describeString(OSShim.CFStringCreate("Helvetica")));
        out.put("ct.str.create.cjk", describeString(OSShim.CFStringCreate("\u65E5\u672C\u8A9E")));
        out.put("ct.str.create.surrogate", describeString(OSShim.CFStringCreate("a\uD800b")));
        out.put("ct.str.create.empty", describeString(OSShim.CFStringCreate("")));
    }

    private static String describeString(long string) {
        if (string == 0) {
            return "0";
        }
        String value = MacFontOracleShim.cfTypeName(string) + "|"
                + FontGoldenFormat.unitsHex(MacFontOracleShim.cfString(string));
        OSShim.CFRelease(string);
        return value;
    }

    private static void putUrls(GoldenMap out) {
        String module = FontGoldenRuntime.moduleDirectory().toString();
        Object[][] cases = {
            {"abs", FONTS + "/Helvetica.ttc", false},
            {"absdir", FONTS, true},
            {"relative", "fonts/a.ttf", false},
            {"trailing", "/tmp/x/", false},
            {"unicode", "/tmp/caf\u00E9 \u65E5\u672C.ttf", false},
            {"spaces", "/tmp/a b/c d.ttf", false},
            {"empty", "", false},
        };
        long alloc = OSShim.kCFAllocatorDefault();
        for (Object[] c : cases) {
            long path = OSShim.CFStringCreate((String) c[1]);
            long url = OSShim.CFURLCreateWithFileSystemPath(alloc, path, OSShim.constant("kCFURLPOSIXPathStyle"),
                                                            (Boolean) c[2]);
            String value = url == 0 ? "0" : MacFontOracleShim.cfTypeName(url) + "|"
                    + MacGoldenFormat.text(MacFontOracleShim.urlString(url)) + "|"
                    + MacFontOracleShim.urlHasDirectoryPath(url) + "|"
                    + MacGoldenFormat.text(MacFontOracleShim.urlPath(url));
            out.put("ct.url." + c[0], value.replace(module, "${MODULE}"));
            if (url != 0) {
                OSShim.CFRelease(url);
            }
            OSShim.CFRelease(path);
        }
    }

    private static void putDictionary(GoldenMap out) {
        long alloc = OSShim.kCFAllocatorDefault();
        long dictionary = OSShim.CFDictionaryCreateMutable(alloc, 4, OSShim.kCFTypeDictionaryKeyCallBacks(),
                                                           OSShim.kCFTypeDictionaryValueCallBacks());
        // Too long to be a tagged pointer, and made here, so no cache or disposer thread holds it.
        long value = OSShim.CFStringCreate("ct.dict.roundTrip: a value that nothing else holds");
        long rc0 = MacFontOracleShim.retainCount(value);
        OSShim.CFDictionaryAddValue(dictionary, OSShim.kCTFontAttributeName(), value);
        long rc1 = MacFontOracleShim.retainCount(value);
        boolean same = OSShim.CFDictionaryGetValue(dictionary, OSShim.kCTFontAttributeName()) == value;
        long missing = OSShim.CFDictionaryGetValue(dictionary, OSShim.kCTParagraphStyleAttributeName());
        long count = MacFontOracleShim.dictionaryCount(dictionary);
        OSShim.CFRetain(value);
        long rc2 = MacFontOracleShim.retainCount(value);
        OSShim.CFRelease(value);
        long rc3 = MacFontOracleShim.retainCount(value);
        OSShim.CFRelease(dictionary);
        long rc4 = MacFontOracleShim.retainCount(value);
        out.put("ct.dict.roundTrip", MacFontOracleShim.cfTypeName(value) + "|same=" + same
                + "|missing=" + missing + "|count=" + count + "|deltas=" + (rc1 - rc0) + ";" + (rc2 - rc1) + ";"
                + (rc3 - rc2) + ";" + (rc4 - rc3));
        OSShim.CFRelease(value);
    }

    private static void putParagraphStyles(GoldenMap out) {
        for (int direction : new int[] {0, 1, -1, 2, 127}) {
            long style = OSShim.CTParagraphStyleCreate(direction);
            out.put("ct.para.d" + FontGoldenFormat.pad(direction, 3), style == 0 ? "0"
                    : MacFontOracleShim.cfTypeName(style) + "|" + MacFontOracleShim.paragraphWritingDirection(style));
            if (style != 0) {
                OSShim.CFRelease(style);
            }
        }
    }

    /** In place, as {@code CTGlyph.checkBounds} calls it with a strike matrix. */
    private static void putRects(GoldenMap out) {
        double[][][] cases = {
            {{1, 2, 3, 4}, {2, 0.5, -0.25, 3, 10, 20}},
            {{1, 2, 3, 4}, IDENTITY},
            {{-1.5, 2.25, 10, 7}, {-1, 0, 0, 1, 0, 0}},
            {{-1.5, 2.25, 10, 7}, ROT30},
            {{0.5, -3, 8.75, 12.5}, {ROT30[0], -ROT30[1], -ROT30[2], ROT30[3], 0, 0}},
            {{0.5, -3, 8.75, 12.5}, SHEAR},
            {{2, 2, 0, 0}, ROT30},
            {{5, 5, -3, -4}, IDENTITY},
            {{1e10, -1e-10, 3e5, 7}, {2, 0, 0, 2, 0, 0}},
            {{0, 0, 1, 1}, {0, 0, 0, 0, 0, 0}},
        };
        for (int i = 0; i < cases.length; i++) {
            double[] rect = cases[i][0].clone();
            OSShim.CGRectApplyAffineTransform(rect, cases[i][1]);
            out.put("ct.rect.c" + FontGoldenFormat.pad(i, 2), MacGoldenFormat.doubles(cases[i][0]) + "|"
                    + MacGoldenFormat.doubles(cases[i][1]) + "|" + MacGoldenFormat.doubles(rect));
        }
    }

    static String describeFont(long font) {
        if (font == 0) {
            return "0";
        }
        String value = MacGoldenFormat.text(MacFontOracleShim.fontPostScriptName(font)) + ";"
                + MacFontOracleShim.fontSize(font) + ";" + MacGoldenFormat.doubles(MacFontOracleShim.fontMatrix(font))
                + "|" + MacGoldenFormat.text(OSShim.CTFontCopyURLAttribute(font)) + "|"
                + MacGoldenFormat.text(OSShim.CTFontCopyAttributeDisplayName(font));
        OSShim.CFRelease(font);
        return value;
    }

    private static void putFontCreation(GoldenMap out) {
        String[] names = {"Helvetica", "Helvetica-Bold", "TimesNewRomanPSMT", "HiraginoSans-W3", "AppleColorEmoji",
            ".SFNS-Regular", ".SFNS-Bold", ".AppleSystemUIFontBOLD", ".PingFangUITextSC-Default", "NoSuchFont-XYZ",
            "", "System Font", "Times New Roman"};
        Object[][] combos = {
            {"s12", 12.0, null},
            {"s0", 0.0, null},
            {"s36p5rot", 36.5, ROT30},
            {"s12id", 12.0, IDENTITY},
        };
        for (int i = 0; i < names.length; i++) {
            for (Object[] combo : combos) {
                long name = OSShim.CFStringCreate(names[i]);
                long font = OSShim.CTFontCreateWithName(name, (Double) combo[1], (double[]) combo[2]);
                out.put("ct.font.name.n" + FontGoldenFormat.pad(i, 2) + "." + combo[0], describeFont(font));
                OSShim.CFRelease(name);
            }
            out.put("ct.font.name.n" + FontGoldenFormat.pad(i, 2), MacGoldenFormat.text(names[i]));
        }
        long base = MacFontFinderGoldenTest.font("Helvetica", 12);
        for (double size : new double[] {0, 20}) {
            for (double[] matrix : new double[][] {null, ROT30}) {
                out.put("ct.font.copy.s" + (int) size + "." + (matrix == null ? "null" : "rot30"),
                        describeFont(OSShim.CTFontCreateCopyWithAttributes(base, size, matrix, 0)));
            }
        }
        OSShim.CFRelease(base);
        for (double size : new double[] {0, 13, 24.5}) {
            for (double[] matrix : new double[][] {null, FLIP}) {
                for (boolean bold : new boolean[] {false, true}) {
                    out.put("ct.font.ui.s" + MacFontCorpus.sizeKey((float) size) + "." + (matrix == null ? "null"
                            : "flip") + "." + (bold ? "bold" : "regular"),
                            describeFont(OSShim.CTFontCreateUIFontForLanguage(size, matrix, bold)));
                }
            }
        }
    }

    /** {@code {pathString, url, cgFont, provider}}, each 0 when not created. */
    static long[] graphicsFont(Path file) {
        long path = OSShim.CFStringCreate(file.toString());
        long url = OSShim.CFURLCreateWithFileSystemPath(OSShim.kCFAllocatorDefault(), path,
                OSShim.constant("kCFURLPOSIXPathStyle"), false);
        long provider = url == 0 ? 0 : OSShim.CGDataProviderCreateWithURL(url);
        long cgFont = provider == 0 ? 0 : OSShim.CGFontCreateWithDataProvider(provider);
        return new long[] {path, url, cgFont, provider};
    }

    static void releaseGraphicsFont(long[] cg) {
        for (long object : new long[] {cg[2], cg[3], cg[1], cg[0]}) {
            if (object != 0) {
                OSShim.CFRelease(object);
            }
        }
    }

    private static void putGraphicsFonts(GoldenMap out) throws Exception {
        Path notAFont = MacFontGoldens.scenarioDirectory("coretext").resolve("notafont.ttf");
        Files.write(notAFont, "not a font file\n".getBytes(StandardCharsets.UTF_8));
        List<String[]> files = new ArrayList<>();
        for (String key : GRAPHICS_FONTS) {
            files.add(new String[] {key, MacFontGoldens.pinned(key).toString()});
        }
        files.add(new String[] {"timesttc", FONTS + "/Times.ttc"});
        files.add(new String[] {"missing", MacFontGoldens.scenarioDirectory("coretext").resolve("missing.ttf")
                                                         .toString()});
        files.add(new String[] {"notafont", notAFont.toString()});
        for (String[] file : files) {
            long[] cg = graphicsFont(Path.of(file[1]));
            String prefix = "ct.cgfont." + file[0];
            out.put(prefix + ".provider", cg[3] == 0 ? "0" : MacFontOracleShim.cfTypeName(cg[3]) + "|"
                    + MacFontOracleShim.dataProviderLength(cg[3]));
            String cgFont = cg[2] == 0 ? "0" : MacGoldenFormat.text(MacFontOracleShim.cgFontPostScriptName(cg[2]))
                    + "|" + MacFontOracleShim.cgFontGlyphCount(cg[2]);
            out.put(prefix + ".cgfont", cgFont);
            if (cg[2] != 0) {
                Object[][] combos = {{"s12", 12.0, null}, {"s0", 0.0, null}, {"s20rot", 20.0, ROT30}};
                for (Object[] combo : combos) {
                    out.put(prefix + "." + combo[0], describeFont(OSShim.CTFontCreateWithGraphicsFont(cg[2],
                            (Double) combo[1], (double[]) combo[2], 0)));
                }
            }
            releaseGraphicsFont(cg);
        }
    }

    private static void putFace(GoldenMap out, String key, long font, FontResource mapper, String sample,
                                int[] extra) {
        int count = (int) MacFontOracleShim.fontGlyphCount(font);
        Set<Integer> glyphs = new LinkedHashSet<>();
        glyphs.add(0);
        for (int gc : MacFontCorpus.glyphs(mapper, sample)) {
            glyphs.add(gc);
        }
        glyphs.add(count - 1);
        glyphs.add(count);
        glyphs.add(0xFFFF);
        for (int gc : extra) {
            glyphs.add(gc);
        }
        out.put("ct.face." + key, MacGoldenFormat.text(MacFontOracleShim.fontPostScriptName(font)) + "|" + count + "|"
                + glyphs);
        for (int gc : glyphs) {
            double[] size = new double[2];
            double total = OSShim.CTFontGetAdvancesForGlyphs(font, OSShim.constant("kCTFontOrientationDefault"),
                                                             (short) gc, size);
            double totalNull = OSShim.CTFontGetAdvancesForGlyphs(font, 0, (short) gc, null);
            double[] vertical = new double[2];
            double totalVertical = OSShim.CTFontGetAdvancesForGlyphs(font, 2, (short) gc, vertical);
            double[] rect = OSShim.CTFontGetBoundingRectForGlyphs(font, (short) gc);
            out.put("ct.glyph." + key + "." + MacGoldenFormat.gid(gc), MacGoldenFormat.doubles(total, size[0], size[1])
                    + "|" + totalNull + "|" + MacGoldenFormat.doubles(totalVertical, vertical[0], vertical[1]) + "|"
                    + MacGoldenFormat.doubles(rect));
        }
        putTables(out, key, font, count, extra);
        putPaths(out, key, font, glyphs);
    }

    private static final int SENTINEL = 0x5A5A5A5A;

    private static void putTables(GoldenMap out, String key, long font, int count, int[] extra) {
        Set<Integer> glyphs = new LinkedHashSet<>();
        for (int gc = 0; gc <= 400; gc++) {
            glyphs.add(gc);
        }
        for (int gc = count - 3; gc <= count + 2; gc++) {
            glyphs.add(gc);
        }
        glyphs.add(0x8000);
        glyphs.add(0xFFFF);
        for (int gc : extra) {
            glyphs.add(gc);
        }
        byte[] head = MacFontOracleShim.fontTable(font, 0x68656164);
        int locaFormat = head == null ? -9 : ByteBuffer.wrap(head).getShort(50);
        for (short format : new short[] {0, 1, 2, -1}) {
            StringBuilder lines = new StringBuilder();
            List<String> detail = new ArrayList<>();
            int found = 0;
            for (int gc : glyphs) {
                int[] bb = {SENTINEL, SENTINEL, SENTINEL, SENTINEL};
                boolean ok = OSShim.CTFontGetBoundingRectForGlyphUsingTables(font, (short) gc, format, bb);
                String result;
                if (ok) {
                    found++;
                    result = bb[0] + "," + bb[1] + "," + bb[2] + "," + bb[3];
                } else {
                    boolean untouched = Arrays.stream(bb).allMatch(v -> v == SENTINEL);
                    result = untouched ? "-" : "-!" + bb[0] + "," + bb[1] + "," + bb[2] + "," + bb[3];
                }
                lines.append(gc).append(':').append(result).append('\n');
                if (format == locaFormat && (gc < 24 || gc >= count - 3)) {
                    detail.add(gc + ":" + result);
                }
            }
            out.put("ct.tables." + key + ".f" + FontGoldenFormat.pad(format, 1), found + ";"
                    + FontGoldens.sha256Hex(lines.toString().getBytes(StandardCharsets.UTF_8)));
            if (format == locaFormat) {
                out.put("ct.tables." + key + ".detail", FontGoldens.joinList(detail));
            }
        }
        out.put("ct.tables." + key + ".loca", locaFormat + "|composites=" + composites(font, locaFormat, count));
    }

    /** Composite glyphs ({@code numberOfContours < 0}) among glyphs 0-400. */
    private static int composites(long font, int locaFormat, int count) {
        byte[] loca = MacFontOracleShim.fontTable(font, 0x6c6f6361);
        byte[] glyf = MacFontOracleShim.fontTable(font, 0x676c7966);
        if (loca == null || glyf == null) {
            return -1;
        }
        ByteBuffer l = ByteBuffer.wrap(loca);
        ByteBuffer g = ByteBuffer.wrap(glyf);
        int composites = 0;
        for (int gc = 0; gc <= Math.min(400, count - 1); gc++) {
            long o1 = locaFormat == 0 ? (l.getShort(2 * gc) & 0xFFFFL) * 2 : l.getInt(4 * gc) & 0xFFFFFFFFL;
            long o2 = locaFormat == 0 ? (l.getShort(2 * gc + 2) & 0xFFFFL) * 2 : l.getInt(4 * gc + 4) & 0xFFFFFFFFL;
            if (o2 > o1 && o1 + 2 <= glyf.length && g.getShort((int) o1) < 0) {
                composites++;
            }
        }
        return composites;
    }

    private static void putPaths(GoldenMap out, String key, long font, Set<Integer> glyphs) {
        Object[][] matrices = {{"null", null}, {"flip", FLIP}, {"rot30", ROT30}, {"shear", SHEAR}};
        for (int gc : glyphs) {
            for (Object[] matrix : matrices) {
                long path = OSShim.CTFontCreatePathForGlyph(font, (short) gc, (double[]) matrix[1]);
                String value;
                if (path == 0) {
                    value = "NULL";
                } else {
                    value = MacGoldenFormat.doubles(OSShim.CGPathGetPathBoundingBox(path)) + "|"
                            + FontGoldenFormat.outlineSummary(OSShim.CGPathApply(path)) + "|"
                            + MacFontOracleShim.applyPath(path).size();
                    if (isDumped(key, gc, (String) matrix[0])) {
                        out.put("ct.pathdump." + key + "." + MacGoldenFormat.gid(gc) + "." + matrix[0],
                                FontGoldenFormat.outlineDump(OSShim.CGPathApply(path)));
                    }
                    OSShim.CGPathRelease(path);
                }
                out.put("ct.path." + key + "." + MacGoldenFormat.gid(gc) + "." + matrix[0], value);
            }
        }
    }

    private static boolean isDumped(String key, int gc, String matrix) {
        return (key.equals("helvetica") && gc == 74 && matrix.equals("flip"))
                || (key.equals("times") && gc == 36 && matrix.equals("null"))
                || (key.equals("stheiti") && gc == 0x8000 && matrix.equals("flip"));
    }

    private static void putPathApply(GoldenMap out) {
        double[][][] paths = {
            {
                {0, 0, 0}, {1, 10, 0}, {2, 15, 5, 10, 10}, {3, 5, 15, 0, 15, -5, 10}, {4},
                {0, 20, 20}, {1, 21.5, -3.25}, {4},
            },
            {},
            {{0, 1, 2}},
            {
                {0, 0.1, -0.0}, {1, 1e300, -1e-300}, {1, 3.4028235677973366e38, 1.401298464324817e-45},
                {1, -1e39, 0.30000000000000004}, {4},
            },
        };
        String[] names = {"all", "empty", "moveonly", "conversions"};
        for (int i = 0; i < paths.length; i++) {
            long path = MacFontOracleShim.createPath(paths[i]);
            Path2D outline = OSShim.CGPathApply(path);
            out.put("ct.pathapply." + names[i], FontGoldenFormat.outlineSummary(outline) + "|"
                    + FontGoldenFormat.outlineDump(outline) + "|"
                    + MacGoldenFormat.doubles(OSShim.CGPathGetPathBoundingBox(path)) + "|"
                    + FontGoldens.joinList(MacFontOracleShim.applyPath(path)));
            MacFontOracleShim.releasePath(path);
        }
        double[][] long2000 = new double[2002][];
        long2000[0] = new double[] {0, 0, 0};
        for (int i = 1; i <= 2000; i++) {
            long2000[i] = new double[] {1, i * 0.5, (i % 7) * 1.25};
        }
        long2000[2001] = new double[] {4};
        long path = MacFontOracleShim.createPath(long2000);
        out.put("ct.pathapply.long2000", FontGoldenFormat.outlineSummary(OSShim.CGPathApply(path)) + "|"
                + MacGoldenFormat.doubles(OSShim.CGPathGetPathBoundingBox(path)));
        MacFontOracleShim.releasePath(path);
    }

    private static void putLines(GoldenMap out) {
        Object[][] cases = {
            {"latin", "Helvetica", "Hello, World! fi ffl 0123 AV To", false},
            {"arabic", "GeezaPro", "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645", true},
            {"japanese", "HiraginoSans-W3", "\u65E5\u672C\u8A9E\u306E\u30C6\u30AD\u30B9\u30C8", false},
            {"fallback", "Helvetica", "abc \u05D0\u05D1\u05D2 123 \u0645\u0631\u062D def \u65E5\u672C", false},
            {"emoji", ".SFNS-Regular", "A\uD83D\uDE00\uD83D\uDC4D\uD83C\uDFFD b", false},
            {"empty", "Helvetica", "", false},
            {"combining", "Times-Roman", "e\u0301 a\u0308\u0323 A\u030A", false},
            {"surrogate", "Helvetica", "ab\uD800c", false},
            {"thai", ".SFNS-Regular", "\u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35", false},
        };
        long alloc = OSShim.kCFAllocatorDefault();
        for (Object[] c : cases) {
            String key = "ct.line." + c[0];
            String text = (String) c[2];
            long font = MacFontFinderGoldenTest.font((String) c[1], 12);
            char[] chars = ("##" + text + "##").toCharArray();
            long textRef = OSShim.CFStringCreateWithCharacters(alloc, chars, 2, text.length());
            long attributes = OSShim.CFDictionaryCreateMutable(alloc, 4, OSShim.kCFTypeDictionaryKeyCallBacks(),
                                                               OSShim.kCFTypeDictionaryValueCallBacks());
            OSShim.CFDictionaryAddValue(attributes, OSShim.kCTFontAttributeName(), font);
            if ((Boolean) c[3]) {
                long style = OSShim.CTParagraphStyleCreate(OSShim.constant("kCTWritingDirectionRightToLeft"));
                OSShim.CFDictionaryAddValue(attributes, OSShim.kCTParagraphStyleAttributeName(), style);
                OSShim.CFRelease(style);
            }
            long attributed = OSShim.CFAttributedStringCreate(alloc, textRef, attributes);
            long[] attrs = MacFontOracleShim.attributedAttributes(attributed);
            String paragraph = attrs[1] == 0 ? "none"
                    : Integer.toString(MacFontOracleShim.paragraphWritingDirection(attrs[1]));
            String check = FontGoldenFormat.unitsHex(MacFontOracleShim.attributedText(attributed)) + "|font="
                    + (attrs[0] == font) + "|paragraph=" + paragraph + "|count=" + attrs[2];
            long line = OSShim.CTLineCreateWithAttributedString(attributed);
            OSShim.CFRelease(attributed);
            OSShim.CFRelease(attributes);
            OSShim.CFRelease(textRef);
            long runs = OSShim.CTLineGetGlyphRuns(line);
            int glyphCount = (int) OSShim.CTLineGetGlyphCount(line);
            long runCount = OSShim.CFArrayGetCount(runs);
            out.put(key, glyphCount + "|" + runCount + "|" + OSShim.CTLineGetTypographicBounds(line) + "|" + check);
            for (int i = 0; i < runCount; i++) {
                long run = OSShim.CFArrayGetValueAtIndex(runs, i);
                long runAttributes = OSShim.CTRunGetAttributes(run);
                long runFont = OSShim.CFDictionaryGetValue(runAttributes, OSShim.kCTFontAttributeName());
                int[] glyphs = new int[glyphCount + 4];
                Arrays.fill(glyphs, 0x7E7E7E7E);
                int gotGlyphs = OSShim.CTRunGetGlyphs(run, 0x03000000, 2, glyphs);
                float[] positions = new float[glyphCount * 2 + 4];
                Arrays.fill(positions, -7f);
                int gotPositions = OSShim.CTRunGetPositions(run, 1, positions);
                int[] indices = new int[glyphCount + 4];
                Arrays.fill(indices, -7);
                int gotIndices = OSShim.CTRunGetStringIndices(run, 3, indices);
                out.put(key + ".r" + FontGoldenFormat.pad(i, 2),
                        MacGoldenFormat.text(OSShim.CTFontCopyAttributeDisplayName(runFont)) + "|"
                        + MacGoldenFormat.text(MacFontOracleShim.fontPostScriptName(runFont)) + "|"
                        + MacFontOracleShim.runGlyphCount(run) + "|" + gotGlyphs + ":" + hexInts(glyphs) + "|"
                        + gotPositions + ":" + FontGoldenFormat.joinFloats(positions) + "|" + gotIndices + ":"
                        + FontGoldenFormat.joinInts(indices));
            }
            OSShim.CFRelease(line);
            OSShim.CFRelease(font);
        }
    }

    private static String hexInts(int[] values) {
        List<String> hex = new ArrayList<>();
        for (int v : values) {
            hex.add(Integer.toHexString(v));
        }
        return String.join(";", hex);
    }

    private static void putJavaPath(GoldenMap out) throws Exception {
        assertEquals(0L, CTGlyphShim.cachedContext(), "a glyph was drawn in this JVM before the corpus: CTGlyph's"
                + " cached context keeps the subpixel and LCD settings of the first strike that drew");
        for (MacFontCorpus.Face face : MacFontCorpus.FACES) {
            FontResource resource = MacFontCorpus.open(face);
            String prefix = "ct.java." + face.key();
            int[] sample = MacFontCorpus.glyphs(resource, face.sample());
            int[] metrics = sample;
            int[] masks = Arrays.copyOf(sample, Math.min(3, sample.length));
            if (face.key().equals("stheiti")) {
                metrics = concat(sample, new int[] {0x8000, 0x9000, 0xCC2B, 0, 0xFFFF});
                masks = concat(masks, new int[] {0x8000});
            } else {
                metrics = concat(sample, new int[] {0, FontResourceShim.numGlyphs(resource) - 1});
            }
            MacFontCorpus.putMetrics(out, prefix, resource, metrics);
            MacFontCorpus.putMasks(out, prefix, resource, masks, FontResource.AA_GREYSCALE);
            if (face.key().equals("helvetica") || face.key().equals("times")) {
                MacFontCorpus.putLargeMasks(out, prefix, resource, MacFontCorpus.glyphs(resource, "Wg"),
                                            FontResource.AA_GREYSCALE);
            }
        }
    }

    private static int[] concat(int[] a, int[] b) {
        int[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    static void assertCoverage(Map<String, String> values) {
        assertTrue(values.get("ct.tables.times.loca").startsWith("0|"), "Times has a short loca");
        assertTrue(values.get("ct.tables.helvetica.loca").startsWith("1|"), "Helvetica has a long loca");
        assertTrue(!values.get("ct.tables.helvetica.loca").endsWith("composites=0"),
                   "the tables grid holds composite glyphs");
        assertTrue(values.get("ct.tables.hiragino.f0").startsWith("0;"), "a CFF font has no glyf to parse");
        assertNotEquals(values.get("ct.tables.helvetica.f0"), values.get("ct.tables.helvetica.f1"),
                        "the loca format changes what the parser reads");
        assertNotEquals("NULL", values.get("ct.path.stheiti.g8000.null"), "STHeiti SC glyph 0x8000 has a path");
        assertNotEquals("NULL", values.get("ct.path.stheiti.gcc2a.flip"), "STHeiti SC's last glyph has a path");
        long moved = values.keySet().stream().filter(k -> k.startsWith("ct.bitmap.") && k.endsWith(".sp0.gray8"))
                .filter(k -> !values.get(k).equals(values.get(k.replace(".sp0.", ".sp2.")))).count();
        assertTrue(moved > 0, "subpixel offsets change some grey masks");
        assertNotEquals(values.get("ct.bitmap.helvetica.s12.g0024.sp0.rgb24"),
                        values.get("ct.bitmap.helvetica.s12.g0024.sp0.rgb8"), "LCD and grey reads differ");
        assertEquals(FontGoldens.NULL, values.get("ct.bitmap.guard.bitmap.bpp32"), "bpp 32 is refused");
        assertTrue(Integer.parseInt(values.get("ct.line.fallback").split("\\|")[1]) > 1, "the fallback line has runs");
        String emojiMetrics = values.get("ct.java.emoji." + MacGoldenFormat.gid(sampleEmoji(values)) + ".metrics");
        assertTrue(emojiMetrics.endsWith("|true"), "the emoji glyph takes the colour branch");
    }

    private static int sampleEmoji(Map<String, String> values) {
        String face = values.get("ct.face.emoji");
        String list = face.substring(face.indexOf('[') + 1, face.indexOf(']'));
        return Integer.parseInt(list.split(", ")[1]);
    }
}
