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
import com.sun.javafx.font.MacFontFinderShim;
import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.MacFontProbeShim;
import com.sun.javafx.font.PGFont;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CoreTextPeerShim;
import com.sun.javafx.font.coretext.OSShim;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.text.GlyphLayout;
import com.sun.javafx.text.PrismTextLayout;
import com.sun.javafx.text.TextRun;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * The child JVM of {@link MacFontReleaseTest}: one scenario per CF object the macOS font layer once left unreleased.
 * The retain-count probes are those of {@link MacFontStressChild}.
 */
public final class MacFontReleaseChild {

    static final String CASCADE_LIST = "release.cascadeList";
    static final String FALLBACK_FONT = "release.fallbackFont";
    static final String FONT_FILE_OWNERSHIP = "release.fontFileOwnership";
    static final String CASCADE_FONT = "release.cascadeFont";
    static final String GLYF_BOUNDS = "release.glyfBounds";
    static final String TABLE_OUT_OF_MEMORY = "release.tableOutOfMemory";
    static final String LAYOUT_THROWS = "release.layoutThrows";

    static final int HEAP_CALLS = 300;
    static final int HEAP_ROUNDS = 3;

    private static final int GLYF_TAG = 0x676c7966;
    private static final int SBIX_TAG = 0x73626978;

    /** Glyph 4 of Ahem has a header in its short {@code loca}, so its bounds are read from {@code glyf}. */
    private static final short GLYPH_WITH_A_HEADER = 4;
    private static final short SHORT_LOCA = 0;

    /** Hebrew, Arabic and Thai: scripts the System font leaves to fallback fonts. */
    private static final List<String> FALLBACK_TEXTS =
            List.of("אבג", "مرحبا", "สวัสดี");

    private MacFontReleaseChild() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        GoldenMap out = new GoldenMap();
        switch (scenario) {
            case CASCADE_LIST -> cascadeList(out);
            case FALLBACK_FONT -> fallbackFont(out);
            case FONT_FILE_OWNERSHIP -> fontFileOwnership(out);
            case CASCADE_FONT -> cascadeFont(out);
            case GLYF_BOUNDS -> glyfBounds(out);
            case TABLE_OUT_OF_MEMORY -> tableOutOfMemory(out);
            case LAYOUT_THROWS -> layoutThrows(out);
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        Files.writeString(Path.of(args[1]), FontGoldens.format(out, List.of("MacFontReleaseChild " + scenario)),
                          StandardCharsets.UTF_8);
        System.exit(0);
    }

    private static void cascadeList(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        cascadeUrls(out, font);
        cascadeNames(out, font);
        MacFontOracleShim.release(font);
    }

    /**
     * The URL of an entry is a new object on every call, so no retain count shows one left behind; the heap does.
     * Off the AppKit thread CoreText's autoreleased objects wait for a pool, so each round drains its own. The
     * heap settles over the first rounds, hence a warm-up and the smallest of {@link #HEAP_ROUNDS}.
     */
    private static void cascadeUrls(GoldenMap out, long font) {
        String[] list = cascadeLists(font);
        double blocksPerCall = Double.MAX_VALUE;
        for (int round = 0; round < HEAP_ROUNDS; round++) {
            long before = MacFontProbeShim.mallocBlocksInUse();
            cascadeLists(font);
            long after = MacFontProbeShim.mallocBlocksInUse();
            blocksPerCall = Math.min(blocksPerCall, (after - before) / (double) HEAP_CALLS);
        }
        int urls = 0;
        for (int i = 1; i < list.length; i += 2) {
            if (list[i] != null) {
                urls++;
            }
        }
        out.put("blocks.perCall", Double.toString(blocksPerCall));
        out.put("entries", Integer.toString(list.length / 2));
        out.put("urls", Integer.toString(urls));
    }

    private static String[] cascadeLists(long font) {
        String[] list = new String[0];
        long pool = MacFontProbeShim.autoreleasePoolPush();
        for (int i = 0; i < HEAP_CALLS; i++) {
            list = MacFontFinderShim.getCascadeList(font);
        }
        MacFontProbeShim.autoreleasePoolPop(pool);
        return list;
    }

    /**
     * CoreText hands out the same name string for an entry in every list, so its retain count shows a copy left
     * behind. Immortal strings show nothing: the first counted one is watched.
     */
    private static void cascadeNames(GoldenMap out, long font) throws Exception {
        int entry = -1;
        long name = 0;
        for (int i = 0; entry < 0; i++) {
            long candidate = MacFontProbeShim.cascadeEntryName(font, i);
            if (candidate == 0) {
                break;
            }
            if (MacFontOracleShim.retainCount(candidate) < Integer.MAX_VALUE) {
                entry = i;
                name = candidate;
            } else {
                MacFontOracleShim.release(candidate);
            }
        }
        out.put("name.entry", Integer.toString(entry));
        if (entry < 0) {
            return;
        }
        long watched = name;
        int watchedEntry = entry;
        MacFontStressChild.delta(out, "name", watched, () -> MacFontFinderShim.getCascadeList(font));
        MacFontStressChild.delta(out, "control.nameCopyPerCall", watched,
                () -> MacFontProbeShim.cascadeEntryName(font, watchedEntry));
        for (int i = 0; i < 2 * MacFontStressChild.RETAIN_CALLS; i++) {
            MacFontOracleShim.release(watched);
        }
        MacFontOracleShim.release(watched);
    }

    private static void fallbackFont(GoldenMap out) throws Exception {
        fallbackFontsOfLayout(out);
        fallbackFontOfTheSeam(out);
    }

    /**
     * The fallback fonts layout adds by reference, which ones being the machine's choice. A fresh copy of a font
     * has the count of a font with one owner, so a font file's own font must have the same.
     */
    private static void fallbackFontsOfLayout(GoldenMap out) throws Exception {
        PGFont font = PrismFontFactory.getFontFactory().createFont("System", false, false, 12f);
        CompositeFontResource composite = (CompositeFontResource) font.getFontResource();
        for (String text : FALLBACK_TEXTS) {
            PrismTextLayout layout = new PrismTextLayout(0);
            layout.setContent(text, font);
            layout.getRuns();
        }
        int fonts = 0;
        for (int slot = 0; slot < composite.getNumSlots(); slot++) {
            FontResource resource = composite.getSlotResource(slot);
            long ref = CoreTextPeerShim.ctFontRef(resource);
            if (ref != 0) {
                long fresh = OSShim.CTFontCreateCopyWithAttributes(ref, 0, null, 0);
                out.put("font." + FontGoldenFormat.pad(fonts, 2), resource.getFullName() + "|"
                        + MacFontOracleShim.retainCount(ref) + "|" + MacFontOracleShim.retainCount(fresh));
                OSShim.CFRelease(fresh);
                fonts++;
            }
            Reference.reachabilityFence(resource);
        }
        out.put("fonts", Integer.toString(fonts));
    }

    /** The same hand-over as layout's, with a font this probe also holds, so that its count can be read. */
    private static void fallbackFontOfTheSeam(GoldenMap out) throws Exception {
        long helvetica = MacFontOracleShim.createFont("Helvetica", 12);
        long ref = OSShim.CTFontCreateCopyWithAttributes(helvetica, 0, null, 0);
        OSShim.CFRetain(ref);
        CompositeFontResource logical = FontResourceShim.newLogicalFont("System", false, false);
        out.put("seam.before", Long.toString(MacFontOracleShim.retainCount(ref)));
        out.put("seam.slot", Integer.toString(CoreTextPeerShim.addFallbackFont(logical, "Helvetica", ref)));
        out.put("seam.after", Long.toString(MacFontOracleShim.retainCount(ref)));
        Reference.reachabilityFence(logical);
        OSShim.CFRelease(ref);
        MacFontOracleShim.release(helvetica);
    }

    private static void fontFileOwnership(GoldenMap out) throws Exception {
        long helvetica = MacFontOracleShim.createFont("Helvetica", 12);
        fontFileFor(out, "unmatched", helvetica, "US-067 No Such Face");
        fontFileFor(out, "matched", helvetica, "Helvetica");
        MacFontOracleShim.release(helvetica);
    }

    /** Hands a copy of {@code font} to the factory under {@code name}, holding a second reference to read it by. */
    private static void fontFileFor(GoldenMap out, String key, long font, String name) throws Exception {
        long ref = OSShim.CTFontCreateCopyWithAttributes(font, 0, null, 0);
        OSShim.CFRetain(ref);
        out.put(key + ".before", Long.toString(MacFontOracleShim.retainCount(ref)));
        FontResource made = CoreTextPeerShim.createFontFile(name, ref);
        out.put(key + ".made", made == null ? null : made.getClass().getSimpleName());
        out.put(key + ".after", Long.toString(MacFontOracleShim.retainCount(ref)));
        Reference.reachabilityFence(made);
        OSShim.CFRelease(ref);
    }

    /**
     * A CTFont made over a CGFont holds one reference to it, so the CGFont of an embedded font shows a cascade
     * lookup that keeps its CTFont. The font file's disposer owns the CGFont, hence the fence.
     */
    private static void cascadeFont(GoldenMap out) throws Exception {
        FontResource resource =
                FontResourceShim.newUncachedEmbeddedFontFile(MacFontGoldens.pinned("ahem").toString());
        long cgFont = CoreTextPeerShim.cgFontRef(resource);
        out.put("cgFont", Long.toString(cgFont));
        if (cgFont != 0) {
            MacFontStressChild.delta(out, "cascadeFont", cgFont,
                    () -> PrismFontFactory.getFontFactory().getFallbacks(resource));
            List<Long> fonts = new ArrayList<>();
            MacFontStressChild.delta(out, "control.fontPerCall", cgFont,
                    () -> fonts.add(OSShim.CTFontCreateWithGraphicsFont(cgFont, 0, null, 0)));
            for (long font : fonts) {
                OSShim.CFRelease(font);
            }
        }
        Reference.reachabilityFence(resource);
    }

    /**
     * CoreText hands out its one cached {@code glyf} table of a font, so that table's retain count shows a copy the
     * bounds reader keeps when the array it writes to throws. Ahem is reached through a CGFont, as an embedded
     * font is, so the CTFont has the tables of the file in the repository.
     */
    private static void glyfBounds(GoldenMap out) throws Exception {
        long path = OSShim.CFStringCreate(MacFontGoldens.pinned("ahem").toString());
        long url = OSShim.CFURLCreateWithFileSystemPath(OSShim.kCFAllocatorDefault(), path,
                                                        OSShim.constant("kCFURLPOSIXPathStyle"), false);
        long provider = OSShim.CGDataProviderCreateWithURL(url);
        long cgFont = OSShim.CGFontCreateWithDataProvider(provider);
        long ahem = OSShim.CTFontCreateWithGraphicsFont(cgFont, 12, null, 0);
        if (!OSShim.CTFontGetBoundingRectForGlyphUsingTables(ahem, GLYPH_WITH_A_HEADER, SHORT_LOCA, new int[4])) {
            throw new IllegalStateException("Ahem glyph 4 has no glyf header, so no call would copy glyf");
        }
        long glyf = MacFontOracleShim.copyFontTable(ahem, GLYF_TAG);
        MacFontStressChild.delta(out, "control.copyTablePerCall", glyf,
                () -> MacFontOracleShim.copyFontTable(ahem, GLYF_TAG));
        for (int i = 0; i < 2 * MacFontStressChild.RETAIN_CALLS; i++) {
            MacFontOracleShim.release(glyf);
        }
        out.put("thrown.shortBounds", thrownByBounds(out, "shortBounds", ahem, glyf, new int[3]));
        out.put("thrown.nullBounds", thrownByBounds(out, "nullBounds", ahem, glyf, null));
        MacFontOracleShim.release(glyf);
        for (long object : new long[] {ahem, cgFont, provider, url, path}) {
            OSShim.CFRelease(object);
        }
    }

    /** The exception a bounds array that cannot take four values gives; the probe records the table's count. */
    private static String thrownByBounds(GoldenMap out, String probe, long font, long glyf, int[] bounds)
            throws Exception {
        String[] thrown = new String[1];
        MacFontStressChild.delta(out, probe, glyf, () -> {
            try {
                OSShim.CTFontGetBoundingRectForGlyphUsingTables(font, GLYPH_WITH_A_HEADER, SHORT_LOCA, bounds);
            } catch (ArrayIndexOutOfBoundsException | NullPointerException e) {
                thrown[0] = e.getClass().getName();
                return null;
            }
            throw new IllegalStateException("a bounds array that cannot take four values did not throw");
        });
        return thrown[0];
    }

    /**
     * The bitmap table of Apple Color Emoji is larger than the heap this child is given, so copying it into a byte
     * array throws {@code OutOfMemoryError} after CoreText has handed out its one cached table, whose retain count
     * shows a copy that is kept. Where the table would fit, nothing can be provoked and only the two sizes are
     * written.
     */
    private static void tableOutOfMemory(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("AppleColorEmoji", 12);
        long length = MacFontOracleShim.fontTableLength(font, SBIX_TAG);
        long heap = Runtime.getRuntime().maxMemory();
        out.put("table.length", Long.toString(length));
        out.put("heap.max", Long.toString(heap));
        if (length > heap) {
            long data = MacFontOracleShim.copyFontTable(font, SBIX_TAG);
            MacFontStressChild.delta(out, "control.copyTablePerCall", data,
                    () -> MacFontOracleShim.copyFontTable(font, SBIX_TAG));
            for (int i = 0; i < 2 * MacFontStressChild.RETAIN_CALLS; i++) {
                MacFontOracleShim.release(data);
            }
            String[] thrown = new String[1];
            MacFontStressChild.delta(out, "table", data, () -> {
                try {
                    DFontDecoderShim.getCTFontTable(font, SBIX_TAG);
                } catch (OutOfMemoryError e) {
                    thrown[0] = e.getClass().getName();
                    return null;
                }
                throw new IllegalStateException("a table larger than the heap was copied into it");
            });
            out.put("thrown", thrown[0]);
            MacFontOracleShim.release(data);
        }
        MacFontOracleShim.release(font);
    }

    /**
     * A CTLine holds references to its font, so the strike's font shows a line that layout keeps when the run it
     * shapes throws. The strike's disposer owns that font and the font file holds its strike only weakly, hence the
     * fences.
     */
    private static void layoutThrows(GoldenMap out) throws Exception {
        FontResource resource =
                FontResourceShim.newUncachedFontFile(MacFontGoldens.pinned("helvetica").toString(), 0);
        FontStrike strike = resource.getStrike(12f, BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE);
        long fontRef = CoreTextPeerShim.fontRef(strike);
        GlyphLayout layout = PrismFontFactory.getFontFactory().createGlyphLayout();
        PGFont font = FontResourceShim.newPrismFont(resource, "Helvetica", 12f);
        char[] text = "Hello, World".toCharArray();
        TextRun run = new TextRun(0, text.length, (byte) 0, false, 0, null, 0, false);
        MacFontStressChild.delta(out, "layout", fontRef, () -> {
            layout.layout(run, font, strike, text);
            return null;
        });
        TextRun throwingRun = new ThrowingRun(text.length);
        String[] thrown = new String[1];
        MacFontStressChild.delta(out, "shapeThrows", fontRef, () -> {
            try {
                layout.layout(throwingRun, font, strike, text);
            } catch (IllegalStateException e) {
                thrown[0] = e.getClass().getName();
                return null;
            }
            throw new IllegalStateException("the run that throws when shaped did not throw");
        });
        List<Long> lines = new ArrayList<>();
        MacFontStressChild.delta(out, "control.linePerCall", fontRef, () -> lines.add(lineOf(fontRef, text)));
        for (long line : lines) {
            OSShim.CFRelease(line);
        }
        out.put("thrown", thrown[0]);
        Reference.reachabilityFence(strike);
        Reference.reachabilityFence(resource);
    }

    /** A CTLine of {@code text} in {@code fontRef}, built as layout builds its own, at +1 for the caller. */
    private static long lineOf(long fontRef, char[] text) {
        long alloc = OSShim.kCFAllocatorDefault();
        long string = OSShim.CFStringCreateWithCharacters(alloc, text, text.length);
        long attributes = OSShim.CFDictionaryCreateMutable(alloc, 4, OSShim.kCFTypeDictionaryKeyCallBacks(),
                                                           OSShim.kCFTypeDictionaryValueCallBacks());
        OSShim.CFDictionaryAddValue(attributes, OSShim.kCTFontAttributeName(), fontRef);
        long attributed = OSShim.CFAttributedStringCreate(alloc, string, attributes);
        long line = OSShim.CTLineCreateWithAttributedString(attributed);
        OSShim.CFRelease(attributed);
        OSShim.CFRelease(attributes);
        OSShim.CFRelease(string);
        return line;
    }

    private static final class ThrowingRun extends TextRun {

        ThrowingRun(int length) {
            super(0, length, (byte) 0, false, 0, null, 0, false);
        }

        @Override
        public void shape(int count, int[] glyphs, float[] pos, int[] indices) {
            throw new IllegalStateException("this run cannot be shaped");
        }
    }
}
