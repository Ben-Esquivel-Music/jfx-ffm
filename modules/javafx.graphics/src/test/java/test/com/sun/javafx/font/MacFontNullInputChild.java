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

import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.DFontDecoderShim;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.Glyph;
import com.sun.javafx.font.MacFontFinderShim;
import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.MacFontProbeShim;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CTGlyphShim;
import com.sun.javafx.font.coretext.CoreTextPeerShim;
import com.sun.javafx.font.coretext.CoreTextPeerShim.NullHalf;
import com.sun.javafx.font.coretext.OSShim;
import com.sun.javafx.geom.transform.BaseTransform;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * The child JVM of {@link MacFontNullInputTest}: one scenario per input that once handed NULL to CoreFoundation,
 * CoreGraphics or CoreText. A regression ends this JVM, not the test fork.
 */
public final class MacFontNullInputChild {

    static final String FALLBACK_COPY = "null.fallbackCopy";
    static final String UI_FONT = "null.uiFont";
    static final String FAMILY_NAME = "null.familyName";
    static final String CASCADE_FONT = "null.cascadeFont";
    static final String CASCADE_EMBEDDED = "null.cascadeEmbedded";
    static final String CASCADE_NAME = "null.cascadeName";
    static final String FORMAT = "null.format";
    static final String TABLE = "null.table";
    static final String MIRROR = "null.mirror";
    static final String SYSTEM_FONT_SIZE = "null.systemFontSize";
    static final String FONT_DATA = "null.fontData";
    static final String DFONT = "null.dfont";
    static final String CODES = "null.codes";
    static final String UI_FONT_COPY = "null.uiFontCopy";
    static final String FONT_NAME = "null.fontName";
    static final String ADVANCE = "null.advance";
    static final String CONTEXT = "null.context";
    static final String RECT_HALF = "null.rectHalf";
    static final String DFONT_FONT_ZERO = "null.dfontFontZero";
    static final String CHARACTERS = "null.characters";
    static final String CASCADE_LIST_FONT = "null.cascadeListFont";

    static final int INDEX_BEFORE = 3;
    static final List<String> SLOTS_BEFORE = List.of("slot0", "slot1", "slot2", "slot3", "slot4", "slot5");

    /** {@code kCTFontUIFontNone}. */
    static final int UI_FONT_TYPE_WITHOUT_A_FONT = -1;

    /** {@code 'zzzz'}, a table no font has. */
    private static final int TABLE_TAG_NO_FONT_HAS = 0x7a7a7a7a;

    /** {@code 'head'}, a table every sfnt font has. */
    private static final int HEAD_TABLE = 0x68656164;

    /** A glyph of Ahem whose {@code hmtx} advance is not 0. */
    private static final int AHEM_GLYPH = 4;
    private static final float SIZE = 12f;

    /** The side of the bitmap the rect fills draw into. */
    private static final int BITMAP = 8;

    /** A width CoreGraphics makes no bitmap context of. */
    private static final int NO_WIDTH = 0;

    private MacFontNullInputChild() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        GoldenMap out = new GoldenMap();
        switch (scenario) {
            case FALLBACK_COPY -> fallbackCopy(out);
            case UI_FONT -> uiFont(out);
            case FAMILY_NAME -> out.put("familyName", MacFontFinderShim.familyName(0));
            case CASCADE_FONT -> out.put("entries", Integer.toString(MacFontFinderShim.getCascadeList(0).length));
            case CASCADE_EMBEDDED -> cascadeEmbedded(out);
            case CASCADE_NAME -> cascadeName(out);
            case FORMAT -> out.put("format", Integer.toString(DFontDecoderShim.formatOf(0)));
            case TABLE -> table(out);
            case MIRROR -> mirror(out);
            case SYSTEM_FONT_SIZE -> systemFontSize(out);
            case FONT_DATA -> fontData(out);
            case DFONT -> dfont(out);
            case CODES -> codes(out);
            case UI_FONT_COPY -> uiFontCopy(out);
            case FONT_NAME -> fontName(out);
            case ADVANCE -> advance(out);
            case CONTEXT -> context(out);
            case RECT_HALF -> rectHalf(out);
            case DFONT_FONT_ZERO -> dfontFontZero(out);
            case CHARACTERS -> characters(out);
            case CASCADE_LIST_FONT -> cascadeListFont(out);
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        Files.writeString(Path.of(args[1]), FontGoldens.format(out, List.of("MacFontNullInputChild " + scenario)),
                          StandardCharsets.UTF_8);
        System.exit(0);
    }

    /** CoreText answers no NULL copy for a real font, so the zero goes in where layout hands the copy on. */
    private static void fallbackCopy(GoldenMap out) throws Exception {
        CompositeFontResource composite = FontResourceShim.newLogicalFont("System", false, false);
        String name = "US-067 No Such Fallback";
        out.put("slot", Integer.toString(CoreTextPeerShim.addFallbackFont(composite, name, 0)));
        String withoutFactory;
        try {
            withoutFactory = Integer.toString(CoreTextPeerShim.addFallbackFontWithoutFactory(composite, name, 0));
        } catch (Exception e) {
            withoutFactory = e.getClass().getName();
        }
        out.put("slot.noFactory", withoutFactory);
    }

    private static void uiFont(GoldenMap out) {
        long font = MacFontProbeShim.uiFont(UI_FONT_TYPE_WITHOUT_A_FONT);
        if (font != 0) {
            MacFontOracleShim.release(font);
        }
        out.put("coreText.font", Long.toString(font));
        out.put("getFont", MacFontFinderShim.getFont(UI_FONT_TYPE_WITHOUT_A_FONT));
    }

    private static void cascadeEmbedded(GoldenMap out) throws Exception {
        FontResource resource = ahemWithoutGlyf();
        out.put("cgFont", Long.toString(CoreTextPeerShim.cgFontRef(resource)));
        out.put("fallbacks",
                String.join("|", PrismFontFactory.getFontFactory().getFallbacks(resource).getFontNames()));
    }

    /** Ahem with its {@code glyf} table renamed: the Java side opens it, CoreGraphics makes no font of it. */
    private static FontResource ahemWithoutGlyf() throws Exception {
        byte[] font = Files.readAllBytes(MacFontGoldens.pinned("ahem"));
        byte[] unknownTag = "zzzz".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(unknownTag, 0, font, tableRecord(font, "glyf"), unknownTag.length);
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "us067-ahem-without-glyf.ttf");
        Files.write(file, font);
        return FontResourceShim.newUncachedEmbeddedFontFile(file.toString());
    }

    /** The offset of the table directory record of {@code tag} in an sfnt file. */
    private static int tableRecord(byte[] font, String tag) {
        int tableCount = (font[4] & 0xff) << 8 | font[5] & 0xff;
        for (int i = 0; i < tableCount; i++) {
            int record = 12 + 16 * i;
            if (new String(font, record, 4, StandardCharsets.US_ASCII).equals(tag)) {
                return record;
            }
        }
        throw new IllegalStateException("the font has no " + tag + " table");
    }

    private static void table(GoldenMap out) {
        long font = DFontDecoderShim.createCTFont("Helvetica");
        out.put("coreText.table", Long.toString(MacFontOracleShim.copyFontTable(font, TABLE_TAG_NO_FONT_HAS)));
        out.put("table", DFontDecoderShim.getCTFontTable(font, TABLE_TAG_NO_FONT_HAS) == null ? null : "bytes");
        DFontDecoderShim.releaseCTFont(font);
    }

    private static void mirror(GoldenMap out) {
        double[] identity = {1, 0, 0, 1, 0, 0};
        out.put("rect.null", thrownAt(() -> OSShim.CGRectApplyAffineTransform(null, identity)));
        out.put("transform.null", thrownAt(() -> OSShim.CGRectApplyAffineTransform(new double[] {1, 2, 3, 4}, null)));
        long gray = OSShim.CGColorSpaceCreateDeviceGray();
        long context = OSShim.CGBitmapContextCreate(0, 8, 8, 8, 8, gray, OSShim.constant("kCGImageAlphaNone"));
        out.put("fill.null", thrownAt(() -> OSShim.CGContextFillRect(context, null)));
        OSShim.CGContextFillRect(context, new double[] {0, 0, 8, 8});
        out.put("fill.after", Integer.toString(OSShim.CGBitmapContextGetData(context, 8, 8, 8).length));
        OSShim.CGContextRelease(context);
        OSShim.CFRelease(gray);
    }

    /** The exception of {@code call} and the frame that threw it, which shows whether a downcall was reached. */
    private static String thrownAt(Runnable call) {
        try {
            call.run();
            return "none";
        } catch (RuntimeException e) {
            StackTraceElement top = e.getStackTrace()[0];
            return e.getClass().getName() + "@" + top.getClassName() + "." + top.getMethodName();
        }
    }

    private static void cascadeName(GoldenMap out) {
        long descriptor = MacFontProbeShim.createFontDescriptor("US067-NoSuchFontAnywhere", 12);
        out.put("coreText.displayName", Boolean.toString(MacFontProbeShim.hasDisplayName(descriptor)));
        String[] entry = MacFontFinderShim.cascadeEntry(descriptor);
        out.put("name", entry[0]);
        out.put("file", entry[1]);
        MacFontOracleShim.release(descriptor);
    }

    /** CoreText makes the system UI font here, so the NULL goes in where its size is read. */
    private static void systemFontSize(GoldenMap out) {
        out.put("sizeOf.null", Float.toString(MacFontFinderShim.sizeOf(0)));
        long font = MacFontOracleShim.uiFont(false, 0);
        out.put("coreText.uiFont", Boolean.toString(font != 0));
        if (font != 0) {
            out.put("sizeOf.uiFont", Float.toString(MacFontFinderShim.sizeOf(font)));
        }
        out.put("getSystemFontSize", Float.toString(MacFontFinderShim.getSystemFontSize()));
    }

    /**
     * CoreText answers a collection, its descriptors and both UI fonts here, so each NULL goes in where it is
     * handed on. The oracle for the UI fonts is the last two triples {@code getFontData} writes.
     */
    private static void fontData(GoldenMap out) {
        out.put("matchingDescriptors.null", Long.toString(MacFontFinderShim.matchingDescriptors(0)));
        String[] uiFontsAlone = MacFontFinderShim.fontData(0);
        out.put("fontData.null.length", Integer.toString(uiFontsAlone.length));
        out.put("fontData.null", FontGoldens.joinList(Arrays.asList(uiFontsAlone)));
        String[] all = MacFontFinderShim.getFontData();
        int written = 0;
        while (written * 3 < all.length && all[written * 3] != null) {
            written++;
        }
        out.put("getFontData.triples", Integer.toString(written));
        if (written >= 2) {
            List<String> uiFonts = Arrays.asList(all).subList((written - 2) * 3, written * 3);
            out.put("getFontData.uiFonts", FontGoldens.joinList(uiFonts));
            out.put("getFontData.uiFonts.nulls", Long.toString(uiFonts.stream().filter(s -> s == null).count()));
        }
        String[] slots = SLOTS_BEFORE.toArray(new String[0]);
        out.put("addUiFont.null.index", Integer.toString(MacFontFinderShim.addUiFont(0, slots, INDEX_BEFORE)));
        out.put("addUiFont.null.slots", FontGoldens.joinList(Arrays.asList(slots)));
        slots = SLOTS_BEFORE.toArray(new String[0]);
        out.put("addOwnedDescriptor.null.index",
                Integer.toString(MacFontFinderShim.addOwnedDescriptor(0, slots, INDEX_BEFORE)));
        out.put("addOwnedDescriptor.null.slots", FontGoldens.joinList(Arrays.asList(slots)));
    }

    /**
     * No input makes CoreText answer a NULL collection, array or name string, so each NULL goes in where it is
     * handed on; the real array comes from the decoder's own match of a collection the observer makes.
     */
    private static void dfont(GoldenMap out) {
        out.put("matchingDescriptors.null", Long.toString(DFontDecoderShim.matchingDescriptors(0)));
        out.put("fontNamed.nullArray", Long.toString(DFontDecoderShim.fontNamed(0, 0)));
        long collection = MacFontProbeShim.fontCollection();
        out.put("coreText.collection", Boolean.toString(collection != 0));
        long fonts = DFontDecoderShim.matchingDescriptors(collection);
        out.put("coreText.descriptors", Boolean.toString(fonts != 0));
        out.put("fontNamed.nullName", Long.toString(DFontDecoderShim.fontNamed(fonts, 0)));
        // With no name nothing reads the array, so a fresh object stands in for it: its retain count is exact.
        long standIn = MacFontProbeShim.retain(MacFontProbeShim.mutableData());
        long before = MacFontOracleShim.retainCount(standIn);
        DFontDecoderShim.fontNamed(standIn, 0);
        out.put("fontNamed.nullName.released", Long.toString(before - MacFontOracleShim.retainCount(standIn)));
        MacFontOracleShim.release(standIn);
        DFontDecoderShim.releaseCTFont(0);
        out.put("releaseCTFont.zero", "returned");
        long helvetica = DFontDecoderShim.createCTFont("Helvetica");
        out.put("createCTFont.helvetica", Boolean.toString(helvetica != 0));
        DFontDecoderShim.releaseCTFont(helvetica);
    }

    /** No input makes {@code CFLocaleCopyISOLanguageCodes} answer NULL, so the NULL goes in where it is handed on. */
    private static void codes(GoldenMap out) {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        out.put("coreText.font", Boolean.toString(font != 0));
        out.put("cascadeList.nullCodes", Integer.toString(MacFontFinderShim.cascadeList(font, 0).length));
        out.put("getCascadeList", Integer.toString(MacFontFinderShim.getCascadeList(font).length));
        MacFontOracleShim.release(font);
    }

    /**
     * With no font nothing reads the codes, so a fresh object stands in for them: its retain count is exact, as
     * {@code MacObjCNativeTest} relies on.
     */
    private static void cascadeListFont(GoldenMap out) {
        long codes = MacFontProbeShim.retain(MacFontProbeShim.mutableData());
        long before = MacFontOracleShim.retainCount(codes);
        out.put("cascadeList.nullFont", Integer.toString(MacFontFinderShim.cascadeList(0, codes).length));
        out.put("codes.released", Long.toString(before - MacFontOracleShim.retainCount(codes)));
        MacFontOracleShim.release(codes);
    }

    /** Font 0 goes in where only a shim can pass it; Helvetica shows what a font answers. */
    private static void dfontFontZero(GoldenMap out) {
        long helvetica = DFontDecoderShim.createCTFont("Helvetica");
        out.put("helvetica", Boolean.toString(helvetica != 0));
        if (helvetica != 0) {
            out.put("format.helvetica", Integer.toHexString(DFontDecoderShim.getCTFontFormat(helvetica)));
            out.put("tags.helvetica", tagCount(DFontDecoderShim.getCTFontTags(helvetica)));
            out.put("table.helvetica", DFontDecoderShim.getCTFontTable(helvetica, HEAD_TABLE) == null ? null : "bytes");
            DFontDecoderShim.releaseCTFont(helvetica);
        }
        out.put("format.zero", Integer.toHexString(DFontDecoderShim.getCTFontFormat(0)));
        out.put("tags.zero", tagCount(DFontDecoderShim.getCTFontTags(0)));
        out.put("table.zero", DFontDecoderShim.getCTFontTable(0, HEAD_TABLE) == null ? null : "bytes");
    }

    private static String tagCount(int[] tags) {
        return tags == null ? null : Integer.toString(tags.length);
    }

    /**
     * The three-argument {@code CFStringCreateWithCharacters} of {@code OS} with counts the characters hold, as
     * production passes them, and with counts they do not hold.
     */
    private static void characters(GoldenMap out) {
        long alloc = OSShim.kCFAllocatorDefault();
        char[] abc = "abc".toCharArray();
        out.put("whole", madeString(OSShim.CFStringCreateWithCharacters(alloc, abc, 3)));
        out.put("prefix", madeString(OSShim.CFStringCreateWithCharacters(alloc, abc, 2)));
        out.put("nullEmpty", madeString(OSShim.CFStringCreateWithCharacters(alloc, null, 0)));
        out.put("beyond", madeString(OSShim.CFStringCreateWithCharacters(alloc, abc, 4)));
        // The open CoreFoundation source doubles the count into bytes unchecked, so this one would wrap to 6 bytes.
        out.put("wrapped", madeString(OSShim.CFStringCreateWithCharacters(alloc, abc, Long.MIN_VALUE + 3)));
        out.put("negative", madeString(OSShim.CFStringCreateWithCharacters(alloc, abc, -1)));
        out.put("nullNegative", madeString(OSShim.CFStringCreateWithCharacters(alloc, null, -1)));
        out.put("nullCount", madeString(OSShim.CFStringCreateWithCharacters(alloc, null, 3)));
    }

    /** "0" for no string; otherwise its text, after which it is released. */
    private static String madeString(long string) {
        if (string == 0) {
            return "0";
        }
        try {
            return "string:" + MacGoldenFormat.text(MacFontOracleShim.cfString(string));
        } finally {
            MacFontOracleShim.release(string);
        }
    }

    /** CoreText makes a UI font of both types the peers ask for, so the NULL goes in where the font is copied. */
    private static void uiFontCopy(GoldenMap out) {
        out.put("copyOfUIFont.null", Long.toString(CoreTextPeerShim.copyOfUIFont(0, SIZE)));
        long uiFont = MacFontOracleShim.uiFont(false, 0);
        out.put("coreText.uiFont", Boolean.toString(uiFont != 0));
        if (uiFont != 0) {
            long copy = CoreTextPeerShim.copyOfUIFont(uiFont, SIZE);
            out.put("copyOfUIFont.uiFont", Boolean.toString(copy != 0));
            MacFontOracleShim.release(copy);
        }
    }

    private static void fontName(GoldenMap out) {
        out.put("name.zero", Long.toString(OSShim.CTFontCreateWithName(0, SIZE, null)));
        long empty = OSShim.CFStringCreate("");
        out.put("coreText.emptyName", Boolean.toString(empty != 0));
        long font = OSShim.CTFontCreateWithName(empty, SIZE, null);
        out.put("name.empty", MacFontOracleShim.fontPostScriptName(font));
        MacFontOracleShim.release(font);
        MacFontOracleShim.release(empty);
        long emptyOverADot = MacFontProbeShim.emptyStringOver('.');
        out.put("coreFoundation.emptyOverADot", MacFontOracleShim.cfString(emptyOverADot));
        long coreTextFont = MacFontProbeShim.fontWithName(emptyOverADot, SIZE);
        out.put("coreText.emptyOverADot", MacFontOracleShim.fontPostScriptName(coreTextFont));
        MacFontOracleShim.release(coreTextFont);
        long dotFont = OSShim.CTFontCreateWithName(emptyOverADot, SIZE, null);
        out.put("name.emptyOverADot", MacFontOracleShim.fontPostScriptName(dotFont));
        MacFontOracleShim.release(dotFont);
        MacFontOracleShim.release(emptyOverADot);
    }

    /** CoreGraphics makes no font of Ahem without {@code glyf}, so each strike of it has no CTFont. */
    private static void advance(GoldenMap out) throws Exception {
        FontResource resource = ahemWithoutGlyf();
        out.put("cgFont", Long.toString(CoreTextPeerShim.cgFontRef(resource)));
        FontStrike strike = resource.getStrike(SIZE, BaseTransform.IDENTITY_TRANSFORM);
        out.put("strike.fontRef", Long.toString(CoreTextPeerShim.fontRef(strike)));
        out.put("hmtx", Float.toString(resource.getAdvance(AHEM_GLYPH, SIZE)));
        out.put("advance", Float.toString(CoreTextPeerShim.advanceFromPlatform(resource, AHEM_GLYPH, SIZE)));
        Reference.reachabilityFence(strike);
    }

    /**
     * CoreGraphics answers no context of width 0, and logs nothing for that; it logs each call made on the NULL
     * context instead. After the subject come the two controls on NULL: one of the setters, then the fill colour.
     * The pid lets the parent read this JVM's lines of the unified log, the controls' among them.
     */
    private static void context(GoldenMap out) throws Exception {
        long gray = OSShim.CGColorSpaceCreateDeviceGray();
        long direct = OSShim.CGBitmapContextCreate(0, NO_WIDTH, 5, 8, NO_WIDTH, gray,
                                                   OSShim.constant("kCGImageAlphaNone"));
        out.put("coreGraphics.context", Long.toString(direct));
        if (direct != 0) {
            OSShim.CGContextRelease(direct);
        }
        OSShim.CFRelease(gray);
        FontResource resource =
                FontResourceShim.newUncachedFontFile(MacFontGoldens.pinned("helvetica").toString(), 0);
        FontStrike strike = resource.getStrike(SIZE, BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE);
        Glyph glyph = strike.getGlyph(0);
        long made = CTGlyphShim.createContext(glyph, false, 3, 3);
        out.put("createContext.made", Boolean.toString(made != 0));
        if (made != 0) {
            OSShim.CGContextRelease(made);
        }
        long context = CTGlyphShim.createContext(glyph, false, NO_WIDTH, 5);
        out.put("createContext", Long.toString(context));
        if (context != 0) {
            OSShim.CGContextRelease(context);
        }
        MacFontProbeShim.setAllowsAntialiasingOfNoContext();
        MacFontProbeShim.setFillColorOfNoContext();
        out.put("pid", Long.toString(ProcessHandle.current().pid()));
        Reference.reachabilityFence(strike);
    }

    /** Each half null in turn; the fill colour is white, so a fill would show in the bitmap. */
    private static void rectHalf(GoldenMap out) {
        double[] box = {0, 0, BITMAP, BITMAP};
        for (NullHalf half : NullHalf.values()) {
            String key = half.name().toLowerCase(Locale.ROOT);
            out.put("transform." + key, thrownAt(() -> CoreTextPeerShim.transformRectWithNull(half, box)));
        }
        long gray = OSShim.CGColorSpaceCreateDeviceGray();
        long context = OSShim.CGBitmapContextCreate(0, BITMAP, BITMAP, 8, BITMAP, gray,
                                                    OSShim.constant("kCGImageAlphaNone"));
        OSShim.CGContextSetRGBFillColor(context, 1, 1, 1, 1);
        byte[] before = OSShim.CGBitmapContextGetData(context, BITMAP, BITMAP, 8);
        for (NullHalf half : NullHalf.values()) {
            String key = half.name().toLowerCase(Locale.ROOT);
            out.put("fill." + key, thrownAt(() -> CoreTextPeerShim.fillRectWithNull(context, half, box)));
            out.put("fill." + key + ".bitmap", bitmapState(before, context));
        }
        OSShim.CGContextFillRect(context, box);
        out.put("fill.control.bitmap", bitmapState(before, context));
        OSShim.CGContextRelease(context);
        OSShim.CFRelease(gray);
    }

    private static String bitmapState(byte[] before, long context) {
        boolean same = Arrays.equals(before, OSShim.CGBitmapContextGetData(context, BITMAP, BITMAP, 8));
        return same ? "unchanged" : "changed";
    }
}
