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
import com.sun.javafx.font.PGFont;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CTFontStrikeShim;
import com.sun.javafx.font.coretext.CTGlyphShim;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
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
    static final String CACHED_CONTEXT = "release.cachedContext";
    static final String FALLBACK_FACE = "release.fallbackFace";
    static final String CASCADE_LIST_POOL = "release.cascadeListPool";
    static final String CASCADE_LIST_VIRTUAL = "release.cascadeListVirtual";
    static final String CASCADE_LIST_INTERRUPTED = "release.cascadeListInterrupted";
    static final String CASCADE_LIST_WAIT_INTERRUPTED = "release.cascadeListWaitInterrupted";
    static final String CASCADE_LIST_WAIT_FAILS = "release.cascadeListWaitFails";

    static final int HEAP_CALLS = 300;
    static final int HEAP_ROUNDS = 3;

    static final String SECOND_CREATING = "second creating";
    static final String SECOND_BLOCKED = "second blocked";
    static final int FALLBACK_LAYOUTS = 5;

    /** How long the first thread inside a context's creation waits for the second to reach the cache. */
    private static final long CONTEXT_HOLD_SECONDS = 20;

    /** How long a caller whose wait failed is given to leave it while the task still runs. */
    private static final Duration WAIT_FAILURE_HOLD = Duration.ofSeconds(1);
    private static final long WAIT_FAILURE_SECONDS = 20;

    /** How long an interrupted caller is given to park in its wait for a task that has not answered. */
    private static final long WAIT_PARK_SECONDS = 20;

    /** Text 06 of the layout corpus: the System font leaves it to a Devanagari face its font file lacks. */
    private static final String DEVANAGARI = MacFontGoldens.TEXTS.get(6);

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
            case CACHED_CONTEXT -> cachedContext(out);
            case FALLBACK_FACE -> fallbackFace(out);
            case CASCADE_LIST_POOL -> cascadeListPool(out);
            case CASCADE_LIST_VIRTUAL -> cascadeListVirtual(out);
            case CASCADE_LIST_INTERRUPTED -> cascadeListInterrupted(out);
            case CASCADE_LIST_WAIT_INTERRUPTED -> cascadeListWaitInterrupted(out);
            case CASCADE_LIST_WAIT_FAILS -> cascadeListWaitFails(out);
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

    /**
     * Two threads ask the empty context cache for its context. The first is held inside the creation of one until
     * the second has reached the cache as well: inside a creation of its own, or blocked at the cache with
     * {@code getCachedContext} as its top frame. The strike's sub-pixel query is the hook, because only the creation
     * of a context makes it. The threads call the cache directly and draw nothing, so they never share the bitmap.
     */
    private static void cachedContext(GoldenMap out) throws Exception {
        out.put("cached.before", Long.toString(CTGlyphShim.cachedContext()));
        FontResource resource =
                FontResourceShim.newUncachedFontFile(MacFontGoldens.pinned("helvetica").toString(), 0);
        ContextRace race = new ContextRace();
        FontStrike strike = CTFontStrikeShim.create(resource, 12f, race::onSubPixelQuery);
        out.put("strike.hasFont", Boolean.toString(CoreTextPeerShim.fontRef(strike) != 0));
        CacheCall first = new CacheCall(strike.getGlyph(1));
        CacheCall second = new CacheCall(strike.getGlyph(2));
        Thread firstThread = Thread.ofPlatform().name(CACHED_CONTEXT + ".first").daemon().unstarted(first);
        Thread secondThread = Thread.ofPlatform().name(CACHED_CONTEXT + ".second").daemon().unstarted(second);
        race.second = secondThread;
        firstThread.start();
        out.put("firstInside", Boolean.toString(race.firstInside.await(CONTEXT_HOLD_SECONDS, TimeUnit.SECONDS)));
        secondThread.start();
        Duration limit = Duration.ofSeconds(2 * CONTEXT_HOLD_SECONDS);
        boolean firstEnded = firstThread.join(limit);
        boolean secondEnded = secondThread.join(limit);
        out.put("finished", Boolean.toString(firstEnded && secondEnded));
        out.put("hold", race.hold);
        out.put("creations", Integer.toString(race.queries.get()));
        out.put("first.failure", first.failure);
        out.put("second.failure", second.failure);
        out.put("context.made", Boolean.toString(first.context != 0));
        out.put("context.shared", Boolean.toString(first.context == second.context));
        out.put("context.cached", Boolean.toString(first.context == CTGlyphShim.cachedContext()));
        Reference.reachabilityFence(strike);
    }

    /**
     * Lays the Devanagari text out in the System font {@link #FALLBACK_LAYOUTS} times, each with a fresh run and
     * straight through the glyph layout, so that no run is reused. With {@code prism.debugfonts} the factory prints
     * each lookup of a fallback face in its font file, and layout each fallback font it resolves.
     */
    private static void fallbackFace(GoldenMap out) {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        PGFont font = factory.createFont("System", false, false, 12f);
        FontStrike strike = font.getStrike(BaseTransform.IDENTITY_TRANSFORM);
        GlyphLayout layout = factory.createGlyphLayout();
        char[] text = DEVANAGARI.toCharArray();
        for (int i = 0; i < FALLBACK_LAYOUTS; i++) {
            TextRun run = new TextRun(0, text.length, (byte) 0, true, 0, null, 0, false);
            layout.layout(run, font, strike, text);
            List<String> codes = new ArrayList<>();
            for (int g = 0; g < run.getGlyphCount(); g++) {
                codes.add(Integer.toHexString(run.getGlyphCode(g)));
            }
            out.put("glyphs." + i, String.join(";", codes));
        }
        out.put("layouts", Integer.toString(FALLBACK_LAYOUTS));
        Reference.reachabilityFence(strike);
    }

    /**
     * Cascade lists of Helvetica on fresh platform threads that push no autorelease pool, so that what a call leaves
     * autoreleased waits there for the thread to exit. The control is the same region without a pool: the list of
     * the font for language codes the probe copies, read by the production helper the cascade list calls.
     */
    private static void cascadeListPool(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        String[] list = MacFontFinderShim.getCascadeList(font);
        out.put("entries", Integer.toString(list.length / 2));
        heapPerCall(out, "subject", Thread.ofPlatform(), () -> MacFontFinderShim.getCascadeList(font), list);
        heapPerCall(out, "control", Thread.ofPlatform(), () -> cascadeListWithoutAPool(font), list);
        MacFontOracleShim.release(font);
    }

    /**
     * The same from fresh virtual threads. The control runs the region without a pool on the carrier, and the
     * hand-off probe measures what starting and waiting for one platform thread per call costs when it runs nothing.
     */
    private static void cascadeListVirtual(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        String[] list = MacFontFinderShim.getCascadeList(font);
        out.put("entries", Integer.toString(list.length / 2));
        heapPerCall(out, "subject", Thread.ofVirtual(), () -> MacFontFinderShim.getCascadeList(font), list);
        heapPerCall(out, "control", Thread.ofVirtual(), () -> cascadeListWithoutAPool(font), list);
        heapPerCall(out, "handOff", Thread.ofVirtual(), () -> emptyHandOff(list), list);
        MacFontOracleShim.release(font);
    }

    /** A virtual thread that interrupts itself and then asks for the cascade list of Helvetica. */
    private static void cascadeListInterrupted(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        String[] list = MacFontFinderShim.getCascadeList(font);
        out.put("entries", Integer.toString(list.length / 2));
        InterruptedCall call = new InterruptedCall(font);
        Thread thread = Thread.ofVirtual().name(CASCADE_LIST_INTERRUPTED).unstarted(call);
        thread.start();
        thread.join();
        out.put("virtual", Boolean.toString(call.virtual));
        out.put("interruptedBefore", Boolean.toString(call.interruptedBefore));
        out.put("failure", call.failure);
        out.put("sameList", Boolean.toString(Arrays.equals(list, call.list)));
        out.put("interruptedAfter", Boolean.toString(call.interruptedAfter));
        MacFontOracleShim.release(font);
    }

    /**
     * A virtual caller that interrupts itself and then waits for a task that answers only once the caller is seen
     * parked in that wait. A thread parks only with its interrupt status clear, and the wait's first {@code get()}
     * clears it by throwing, so a caller parked there has had its interrupt seen by the wait.
     */
    private static void cascadeListWaitInterrupted(GoldenMap out) throws Exception {
        CountDownLatch answer = new CountDownLatch(1);
        String[] tasksArray = {CASCADE_LIST_WAIT_INTERRUPTED};
        FutureTask<String[]> task = new FutureTask<>(() -> {
            answer.await();
            return tasksArray;
        });
        Thread worker = Thread.ofPlatform().name(CASCADE_LIST_WAIT_INTERRUPTED + ".task").daemon().start(task);
        InterruptedWait call = new InterruptedWait(task);
        Thread caller = Thread.ofVirtual().name(CASCADE_LIST_WAIT_INTERRUPTED).start(call);
        out.put("parkedInTheWait", Boolean.toString(awaitParkedInTheWait(caller)));
        out.put("doneWhenParked", Boolean.toString(task.isDone()));
        out.put("callerState", caller.getState().toString());
        answer.countDown();
        caller.join();
        worker.join();
        out.put("virtual", Boolean.toString(call.virtual));
        out.put("interruptedBefore", Boolean.toString(call.interruptedBefore));
        out.put("failure", call.failure);
        out.put("theTasksArray", Boolean.toString(call.list == tasksArray));
        out.put("list", call.list == null ? null : String.join(";", call.list));
        out.put("interruptedAfter", Boolean.toString(call.interruptedAfter));
    }

    /** Whether {@code caller} parks in its wait within {@link #WAIT_PARK_SECONDS}; false once it has ended. */
    private static boolean awaitParkedInTheWait(Thread caller) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PARK_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            if (isParkedInTheWait(caller)) {
                return true;
            }
            if (caller.getState() == Thread.State.TERMINATED) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return false;
    }

    /** WAITING with {@code FutureTask.get} on the stack above {@code MacFontFinder.resultOf}. */
    private static boolean isParkedInTheWait(Thread thread) {
        if (thread.getState() != Thread.State.WAITING) {
            return false;
        }
        boolean inGet = false;
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals("java.util.concurrent.FutureTask") && frame.getMethodName().equals("get")) {
                inGet = true;
            } else if (inGet && frame.getClassName().equals("com.sun.javafx.font.MacFontFinder")
                    && frame.getMethodName().equals("resultOf")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A virtual caller's wait whose own {@code get()} throws while the task still runs, as the wait's allocation
     * does when the heap is full. The task finishes only after the caller has had {@link #WAIT_FAILURE_HOLD} to
     * leave: a wait that ends on that error leaves while the task runs, so while a real task still reads the font
     * its caller releases next.
     */
    private static void cascadeListWaitFails(GoldenMap out) throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        FailingWait task = new FailingWait(() -> {
            finish.await();
            return new String[] {CASCADE_LIST_WAIT_FAILS};
        });
        Thread worker = Thread.ofPlatform().name(CASCADE_LIST_WAIT_FAILS + ".task").daemon().start(task);
        WaitCall call = new WaitCall(task);
        Thread caller = Thread.ofVirtual().name(CASCADE_LIST_WAIT_FAILS).start(call);
        out.put("waitFailed", Boolean.toString(task.failed.await(WAIT_FAILURE_SECONDS, TimeUnit.SECONDS)));
        out.put("leftWhileTheTaskRan", Boolean.toString(caller.join(WAIT_FAILURE_HOLD)));
        finish.countDown();
        caller.join();
        worker.join();
        out.put("virtual", Boolean.toString(call.virtual));
        out.put("doneWhenTheWaitFailed", Boolean.toString(task.doneWhenItFailed));
        out.put("doneWhenTheCallerLeft", Boolean.toString(call.doneWhenItLeft));
        out.put("list", call.list == null ? null : String.join(";", call.list));
        out.put("thrown", call.thrown == null ? null : call.thrown.toString());
        out.put("thrownIsTheWaitsError", Boolean.toString(call.thrown == task.failure));
    }

    private static String[] cascadeListWithoutAPool(long font) {
        return MacFontFinderShim.cascadeList(font, MacFontProbeShim.languageCodes());
    }

    /** A platform thread started and waited for, as a virtual caller's hand-off does, with nothing to run. */
    private static String[] emptyHandOff(String[] list) {
        FutureTask<String[]> task = new FutureTask<>(() -> list);
        Thread.ofPlatform().name("emptyHandOff").daemon().inheritInheritableThreadLocals(false).start(task);
        try {
            return task.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Heap blocks per call that {@code call} leaves in use on a fresh thread of {@code builder}: each of
     * {@link #HEAP_ROUNDS} rounds of {@link #HEAP_CALLS} calls after a round of warm-up, and the smallest. Every call
     * must answer {@code list}.
     */
    private static void heapPerCall(GoldenMap out, String probe, Thread.Builder builder, Supplier<String[]> call,
                                    String[] list) throws InterruptedException {
        HeapRounds rounds = new HeapRounds(call, list);
        Thread thread = builder.name(probe).unstarted(rounds);
        thread.start();
        thread.join();
        out.put(probe + ".virtual", Boolean.toString(rounds.virtual));
        out.put(probe + ".failure", rounds.failure);
        out.put(probe + ".sameList", Boolean.toString(rounds.sameList));
        out.put(probe + ".rounds", String.join(";", rounds.perCall));
        out.put(probe + ".perCall", Double.toString(rounds.smallest));
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

    /** Holds the first creation of a context until the second thread has reached the cache too, or time is up. */
    private static final class ContextRace {

        private final AtomicInteger queries = new AtomicInteger();
        private final CountDownLatch firstInside = new CountDownLatch(1);
        private volatile Thread second;
        private volatile String hold = "never held";

        void onSubPixelQuery() {
            if (queries.incrementAndGet() == 1) {
                firstInside.countDown();
                hold = awaitSecond();
            }
        }

        private String awaitSecond() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONTEXT_HOLD_SECONDS);
            while (System.nanoTime() - deadline < 0) {
                if (queries.get() > 1) {
                    return SECOND_CREATING;
                }
                if (isBlockedInTheCache(second)) {
                    return SECOND_BLOCKED;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            return "timed out after " + CONTEXT_HOLD_SECONDS + " s";
        }

        private static boolean isBlockedInTheCache(Thread thread) {
            if (thread.getState() != Thread.State.BLOCKED) {
                return false;
            }
            StackTraceElement[] stack = thread.getStackTrace();
            return stack.length > 0 && stack[0].getClassName().equals("com.sun.javafx.font.coretext.CTGlyph")
                    && stack[0].getMethodName().equals("getCachedContext");
        }
    }

    /** The rounds of {@link #heapPerCall} on one thread; what they found is read once the thread has ended. */
    private static final class HeapRounds implements Runnable {

        private final Supplier<String[]> call;
        private final String[] list;
        private final List<String> perCall = new ArrayList<>();
        private double smallest = Double.MAX_VALUE;
        private boolean virtual;
        private boolean sameList = true;
        private String failure;

        HeapRounds(Supplier<String[]> call, String[] list) {
            this.call = call;
            this.list = list;
        }

        @Override
        public void run() {
            virtual = Thread.currentThread().isVirtual();
            try {
                calls();
                for (int round = 0; round < HEAP_ROUNDS; round++) {
                    long before = MacFontProbeShim.mallocBlocksInUse();
                    calls();
                    long after = MacFontProbeShim.mallocBlocksInUse();
                    double blocks = (after - before) / (double) HEAP_CALLS;
                    perCall.add(Double.toString(blocks));
                    smallest = Math.min(smallest, blocks);
                }
            } catch (RuntimeException | Error e) {
                failure = e.toString();
            }
        }

        private void calls() {
            for (int i = 0; i < HEAP_CALLS; i++) {
                sameList &= Arrays.equals(list, call.get());
            }
        }
    }

    /** A virtual thread's call of the cascade list with its interrupt status set; read once the thread has ended. */
    private static final class InterruptedCall implements Runnable {

        private final long font;
        private boolean virtual;
        private boolean interruptedBefore;
        private String[] list;
        private String failure;
        private boolean interruptedAfter;

        InterruptedCall(long font) {
            this.font = font;
        }

        @Override
        public void run() {
            virtual = Thread.currentThread().isVirtual();
            Thread.currentThread().interrupt();
            interruptedBefore = Thread.currentThread().isInterrupted();
            try {
                list = MacFontFinderShim.getCascadeList(font);
            } catch (RuntimeException | Error e) {
                failure = e.toString();
            }
            interruptedAfter = Thread.currentThread().isInterrupted();
        }
    }

    /** A virtual thread's wait for a task with its interrupt status set; read once the thread has ended. */
    private static final class InterruptedWait implements Runnable {

        private final FutureTask<String[]> task;
        private boolean virtual;
        private boolean interruptedBefore;
        private String[] list;
        private String failure;
        private boolean interruptedAfter;

        InterruptedWait(FutureTask<String[]> task) {
            this.task = task;
        }

        @Override
        public void run() {
            virtual = Thread.currentThread().isVirtual();
            Thread.currentThread().interrupt();
            interruptedBefore = Thread.currentThread().isInterrupted();
            try {
                list = MacFontFinderShim.resultOf(task);
            } catch (RuntimeException | Error e) {
                failure = e.toString();
            }
            interruptedAfter = Thread.currentThread().isInterrupted();
        }
    }

    /** A task whose first {@code get()} throws while the task runs, as a wait that cannot allocate its node does. */
    private static final class FailingWait extends FutureTask<String[]> {

        private final OutOfMemoryError failure = new OutOfMemoryError("the wait's own allocation, made to fail");
        private final CountDownLatch failed = new CountDownLatch(1);
        private final AtomicInteger gets = new AtomicInteger();
        private volatile boolean doneWhenItFailed;

        FailingWait(Callable<String[]> callable) {
            super(callable);
        }

        @Override
        public String[] get() throws InterruptedException, ExecutionException {
            if (gets.incrementAndGet() > 1) {
                return super.get();
            }
            doneWhenItFailed = isDone();
            failed.countDown();
            throw failure;
        }
    }

    /** A virtual thread's wait for a {@link FailingWait}; what it answered or threw is read once it has ended. */
    private static final class WaitCall implements Runnable {

        private final FailingWait task;
        private boolean virtual;
        private String[] list;
        private Throwable thrown;
        private boolean doneWhenItLeft;

        WaitCall(FailingWait task) {
            this.task = task;
        }

        @Override
        public void run() {
            virtual = Thread.currentThread().isVirtual();
            try {
                list = MacFontFinderShim.resultOf(task);
            } catch (RuntimeException | Error e) {
                thrown = e;
            }
            doneWhenItLeft = task.isDone();
        }
    }

    /** One thread's call of the context cache; what it answered or threw is read once the thread has ended. */
    private static final class CacheCall implements Runnable {

        private final Glyph glyph;
        private long context;
        private String failure;

        CacheCall(Glyph glyph) {
            this.glyph = glyph;
        }

        @Override
        public void run() {
            try {
                context = CTGlyphShim.getCachedContext(glyph, false);
            } catch (Exception e) {
                failure = e.toString();
            }
        }
    }
}
