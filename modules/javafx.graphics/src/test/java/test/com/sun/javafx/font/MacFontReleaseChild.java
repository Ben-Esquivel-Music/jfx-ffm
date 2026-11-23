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
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * The child JVM of {@link MacFontReleaseTest}: one scenario per CF object the macOS font layer once left unreleased,
 * per check of the cascade-list thread, and per check of layout's fallback fonts. The retain-count probes are those of
 * {@link MacFontStressChild}.
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
    static final String CASCADE_LIST_WORKER = "release.cascadeListWorker";
    static final String CASCADE_LIST_LENT_FONT = "release.cascadeListLentFont";
    static final String FACE_MEMORY = "release.faceMemory";
    static final String RUN_WITHOUT_A_SLOT = "release.runWithoutASlot";
    static final String UNREADABLE_FACE_FILE = "release.unreadableFaceFile";
    static final String FONT_WITHOUT_A_FILE = "release.fontWithoutAFile";

    static final int HEAP_CALLS = 300;
    static final int HEAP_ROUNDS = 3;
    static final int TOKEN_CYCLES = 10;
    static final int HELD_CALLERS = 8;

    static final String SECOND_CREATING = "second creating";
    static final String SECOND_BLOCKED = "second blocked";
    static final int FALLBACK_LAYOUTS = 5;

    /** The name of the platform thread that makes the cascade lists of virtual callers. */
    static final String CASCADE_LIST_THREAD = "JavaFX font cascade list";

    /** What the thread that starts the cascade-list thread sets in its inheritable thread local. */
    static final String STARTER = "starter";

    private static final InheritableThreadLocal<String> INHERITED = new InheritableThreadLocal<>();

    /** How long the first thread inside a context's creation waits for the second to reach the cache. */
    private static final long CONTEXT_HOLD_SECONDS = 20;

    /** How long the scenario waits for a thread to park, or to end; it bounds only a failure. */
    private static final long WAIT_PARK_SECONDS = 20;

    /**
     * How long a task holds the cascade-list thread at most: well beyond the setup and {@link #WAIT_PARK_SECONDS},
     * so that a slow host fails only where a wait for a park says so. The scenario releases it before.
     */
    private static final long HOLD_SECONDS = 60;

    /** The face of the fonts the scenarios of layout's fallbacks make: the repository's Ahem, installed on no macOS. */
    static final String FACE = "Ahem";

    /**
     * Texts laid out in Ahem, which has neither Devanagari nor a zero-width joiner, so CoreText lays those out in
     * fonts of its own choosing: Devanagari KA, A, KA; and a joiner alone. CoreText of macOS 27.2.1 gives that joiner
     * the removed glyph 0xFFFF; another release may not.
     */
    static final List<String> MIXED_TEXTS = List.of("\u0915A\u0915", "\u200D");

    /** A strike's slot other than 0, so that an answer of 0 is not taken for it. */
    static final int STRIKE_SLOT = 1;

    /** Invented: no run's font has this name, so layout takes the run for one in another font than the strike's. */
    private static final String NOT_THE_RUN_FONT = "US-111 Not The Run Font";

    /** Invented: no face of Helvetica.ttc has this name. */
    private static final String NO_SUCH_FACE = "US-111 No Such Face";

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
            case CASCADE_LIST_WORKER -> cascadeListWorker(out);
            case CASCADE_LIST_LENT_FONT -> cascadeListLentFont(out);
            case FACE_MEMORY -> faceMemory(out);
            case RUN_WITHOUT_A_SLOT -> runWithoutASlot(out);
            case UNREADABLE_FACE_FILE -> unreadableFaceFile(out);
            case FONT_WITHOUT_A_FILE -> fontWithoutAFile(out);
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
     * Whether the factory may still find a face of a name, before and after it looked the name up in a copy of
     * Helvetica's font: under {@link #NO_SUCH_FACE}, also asked in upper case, and under "Helvetica".
     */
    private static void faceMemory(GoldenMap out) throws Exception {
        long helvetica = MacFontOracleShim.createFont("Helvetica", 12);
        out.put("missing.before", Boolean.toString(CoreTextPeerShim.mayFindFace(NO_SUCH_FACE)));
        out.put("missing.made", fontFileMadeFor(helvetica, NO_SUCH_FACE));
        out.put("missing.after", Boolean.toString(CoreTextPeerShim.mayFindFace(NO_SUCH_FACE)));
        out.put("missing.otherCase",
                Boolean.toString(CoreTextPeerShim.mayFindFace(NO_SUCH_FACE.toUpperCase(Locale.ROOT))));
        out.put("found.made", fontFileMadeFor(helvetica, "Helvetica"));
        out.put("found.after", Boolean.toString(CoreTextPeerShim.mayFindFace("Helvetica")));
        MacFontOracleShim.release(helvetica);
    }

    /** Hands a copy of {@code font} to the factory under {@code name}, which takes it; null if no font file is made. */
    private static String fontFileMadeFor(long font, String name) throws Exception {
        long ref = OSShim.CTFontCreateCopyWithAttributes(font, 0, null, 0);
        FontResource made = CoreTextPeerShim.createFontFile(name, ref);
        return made == null ? null : made.getClass().getSimpleName();
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
     * {@code getCachedContext} as its top frame. The strike's sub-pixel query is the hook: it is the one call to the
     * strike between the creation of a context and its store in the cache, and a draw, which makes it too, never
     * happens here, since the threads call the cache directly and draw nothing. So each query is one creation, and
     * the threads never share the bitmap.
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
     * Layout's slot for the run in Ahem of {@link #swappedFace} in the System font, {@link #FALLBACK_LAYOUTS} times.
     * With {@code prism.debugfonts} the factory prints each lookup of a fallback face in its font file, and layout
     * each fallback font it resolves; whether the child ran with it is written too, since without it there is nothing
     * to count.
     */
    private static void fallbackFace(GoldenMap out) throws Exception {
        FaceRun face = swappedFace(out, FALLBACK_FACE);
        answers(out, face);
        face.release();
    }

    /**
     * Ahem made from a file of the scenario's own and a line of "A" in it, the only calls on the font before its file
     * is replaced by Helvetica.ttc, which has no face named Ahem; a second link keeps Ahem's bytes for the life of
     * the child. Then the preconditions, measured apart from layout: the file CoreText reports for the font, and for
     * a fresh copy of the run's font, which is what layout's lookup reads; the run font's name; that the factory
     * reads the file and finds no face named Ahem in it; and that the System font has no slot of that name.
     */
    private static FaceRun swappedFace(GoldenMap out, String scenario) throws Exception {
        Path directory = fontDirectory(scenario);
        Path file = directory.resolve("face.ttf");
        Files.copy(MacFontGoldens.pinned("ahem"), file);
        Files.createLink(directory.resolve("ahem.ttf"), file);
        String path = file.toRealPath().toString();
        long font = fontOfFile(path);
        long line = CoreTextPeerShim.line(font, "A");
        long run = firstRun(line);
        Files.delete(file);
        Files.copy(MacFontGoldens.pinned("helvetica"), file);
        out.put("file", path);
        out.put("file.reported", OSShim.CTFontCopyURLAttribute(font));
        out.put("run.font", fontName(runFont(run)));
        out.put("file.ofACopy", fileOfACopy(runFont(run)));
        out.put("face.missing", faceMissing(path));
        return new FaceRun(font, line, run, systemFont(out));
    }

    /**
     * Ahem made from a file of the scenario's own, and layout's slot for the run in Ahem of a line of "A" in the
     * System font, once the file is deleted, so that the lookup cannot read it, and once Ahem is back in its place; a
     * second link keeps Ahem's bytes for the life of the child.
     * The preconditions: the file CoreText reports for the font, and for a fresh copy of the run's font once the file
     * is deleted, which is what the lookup reads; the run font's name; and that the System font has no slot of it.
     */
    private static void unreadableFaceFile(GoldenMap out) throws Exception {
        Path directory = fontDirectory(UNREADABLE_FACE_FILE);
        Path file = directory.resolve("face.ttf");
        Files.copy(MacFontGoldens.pinned("ahem"), file);
        Files.createLink(directory.resolve("ahem.ttf"), file);
        String path = file.toRealPath().toString();
        long font = fontOfFile(path);
        long line = CoreTextPeerShim.line(font, "A");
        long run = firstRun(line);
        out.put("file", path);
        out.put("file.reported", OSShim.CTFontCopyURLAttribute(font));
        out.put("run.font", fontName(runFont(run)));
        Files.delete(file);
        out.put("file.ofACopy", fileOfACopy(runFont(run)));
        FaceRun face = new FaceRun(font, line, run, systemFont(out));
        out.put("answer.unreadable", Integer.toString(fallbackSlot(face)));
        out.put("mayFindFace.afterUnreadable", Boolean.toString(CoreTextPeerShim.mayFindFace(FACE)));
        Files.copy(MacFontGoldens.pinned("ahem"), file);
        int readable = fallbackSlot(face);
        out.put("answer.readable", Integer.toString(readable));
        out.put("slot.font", readable < 0 ? null : face.system().getSlotResource(readable).getFullName());
        out.put("debugFonts", String.valueOf(PrismFontFactory.debugFonts));
        face.release();
    }

    /**
     * Ahem made from its bytes, so that it reports no file, and layout's slot for the run in Ahem of a line of "A" in
     * the System font, {@link #FALLBACK_LAYOUTS} times. The preconditions: no file for a fresh copy of the run's font,
     * the run font's name, and that the System font has no slot of it.
     */
    private static void fontWithoutAFile(GoldenMap out) throws Exception {
        long font = MacFontProbeShim.fontOfData(Files.readAllBytes(MacFontGoldens.pinned("ahem")), 12);
        if (font == 0) {
            throw new IllegalStateException("CoreText made no font of the bytes of Ahem");
        }
        long line = CoreTextPeerShim.line(font, "A");
        long run = firstRun(line);
        out.put("run.font", fontName(runFont(run)));
        out.put("file.ofACopy", fileOfACopy(runFont(run)));
        FaceRun face = new FaceRun(font, line, run, systemFont(out));
        answers(out, face);
        face.release();
    }

    /**
     * {@link #MIXED_TEXTS} laid out in Ahem through a plain strike, as {@code Font.loadFont} gives one, of a font file
     * made as an embedded font is, so that its CTFont has Ahem's own tables; and, as the oracle, the runs of a line of
     * each text made as layout makes its own, each with its font's display name, whether that font is the line's by
     * address, and its glyph IDs. Then {@link #ownSlots} of the run in Ahem.
     */
    private static void runWithoutASlot(GoldenMap out) throws Exception {
        FontResource ahem = FontResourceShim.newUncachedEmbeddedFontFile(MacFontGoldens.pinned("ahem").toString());
        PGFont font = FontResourceShim.newPrismFont(ahem, FACE, 12f);
        FontStrike strike = font.getStrike(BaseTransform.IDENTITY_TRANSFORM);
        long fontRef = CoreTextPeerShim.fontRef(strike);
        GlyphLayout layout = PrismFontFactory.getFontFactory().createGlyphLayout();
        List<String> codes = new ArrayList<>();
        List<Long> lines = new ArrayList<>();
        int count = 0;
        long ownRun = 0;
        for (String text : MIXED_TEXTS) {
            char[] chars = text.toCharArray();
            TextRun laidOut = new TextRun(0, chars.length, (byte) 0, true, 0, null, 0, false);
            layout.layout(laidOut, font, strike, chars);
            for (int g = 0; g < laidOut.getGlyphCount(); g++) {
                codes.add(Integer.toHexString(laidOut.getGlyphCode(g)));
            }
            long line = CoreTextPeerShim.line(fontRef, text);
            lines.add(line);
            long runs = OSShim.CTLineGetGlyphRuns(line);
            for (int i = 0; i < OSShim.CFArrayGetCount(runs); i++) {
                long run = OSShim.CFArrayGetValueAtIndex(runs, i);
                String name = fontName(runFont(run));
                out.put("run." + count + ".font", name);
                out.put("run." + count + ".lineFont", Boolean.toString(runFont(run) == fontRef));
                out.put("run." + count + ".glyphs", glyphIds(run));
                count++;
                if (ownRun == 0 && FACE.equals(name)) {
                    ownRun = run;
                }
            }
        }
        out.put("strike.font", strike.getFontResource().getFullName());
        out.put("laidOut", String.join(";", codes));
        out.put("runs", Integer.toString(count));
        out.put("own.run", Boolean.toString(ownRun != 0));
        if (ownRun != 0) {
            ownSlots(out, ownRun, ahem);
        }
        for (long line : lines) {
            OSShim.CFRelease(line);
        }
        Reference.reachabilityFence(strike);
    }

    /**
     * Layout's slot for {@code ownRun}, the run in Ahem, under a strike name that is not its font's: in a plain
     * strike, laid out with slot 0 and with {@link #STRIKE_SLOT}, and as a run of another line; in a composite that
     * holds Ahem; and in a System font, which has no slot of that name, laid out with {@link #STRIKE_SLOT}.
     */
    private static void ownSlots(GoldenMap out, long ownRun, FontResource ahem) {
        long ownFont = runFont(ownRun);
        out.put("own.plain", slotOf(ownRun, ownFont, null, 0));
        out.put("own.plainStrikeSlot", slotOf(ownRun, ownFont, null, STRIKE_SLOT));
        out.put("own.otherLine", slotOf(ownRun, 0, null, 0));
        CompositeFontResource composite = FontResourceShim.newComposite(
                FontResourceShim.newUncachedFontFile(MacFontGoldens.pinned("fontwithfeaturesttf").toString()));
        out.put("composite.added", Integer.toString(composite.addSlotFont(ahem)));
        out.put("own.composite", slotOf(ownRun, ownFont, composite, 0));
        CompositeFontResource system = FontResourceShim.newLogicalFont("System", false, false);
        out.put("logical.before", Integer.toString(system.getSlotForFont(FACE)));
        out.put("own.logical", slotOf(ownRun, ownFont, system, STRIKE_SLOT));
    }

    private static String slotOf(long run, long lineFont, CompositeFontResource fr, int slot) {
        return Integer.toString(CoreTextPeerShim.getFontSlot(run, lineFont, fr, NOT_THE_RUN_FONT, slot));
    }

    /** The scenario's own directory of font files, emptied first: a child that ended early may have left some. */
    private static Path fontDirectory(String scenario) throws IOException {
        Path directory = MacFontGoldens.scenarioDirectory(scenario).resolve("fonts");
        FontGoldenRuntime.deleteTree(directory);
        return Files.createDirectories(directory);
    }

    private static long fontOfFile(String path) {
        long font = MacFontProbeShim.fontOfFile(path, 12);
        if (font == 0) {
            throw new IllegalStateException("CoreText made no font of " + path);
        }
        return font;
    }

    /** A System font of its own, and its slot for Ahem before any layout: -1 unless a font named Ahem is installed. */
    private static CompositeFontResource systemFont(GoldenMap out) {
        CompositeFontResource system = FontResourceShim.newLogicalFont("System", false, false);
        out.put("slot.before", Integer.toString(system.getSlotForFont(FACE)));
        return system;
    }

    /** Layout's slot for the face's run in its System font, {@link #FALLBACK_LAYOUTS} times. */
    private static void answers(GoldenMap out, FaceRun face) {
        for (int i = 0; i < FALLBACK_LAYOUTS; i++) {
            out.put("answer." + i, Integer.toString(fallbackSlot(face)));
        }
        out.put("layouts", Integer.toString(FALLBACK_LAYOUTS));
        out.put("debugFonts", String.valueOf(PrismFontFactory.debugFonts));
    }

    /** As a run of another line, so that layout takes the run's font for a fallback's whatever its name. */
    private static int fallbackSlot(FaceRun face) {
        return CoreTextPeerShim.getFontSlot(face.run(), 0, face.system(), NOT_THE_RUN_FONT, 0);
    }

    /** Whether the factory reads {@code path} and finds no face named Ahem in it, or what it threw. */
    private static String faceMissing(String path) {
        try {
            return Boolean.toString(PrismFontFactory.getFontFactory().findFontIndex(FACE, path) == -1);
        } catch (Exception e) {
            return e.toString();
        }
    }

    /** The file CoreText reports for a fresh copy of {@code font}, as layout's lookup reads it. */
    private static String fileOfACopy(long font) {
        long copy = OSShim.CTFontCreateCopyWithAttributes(font, 0, null, 0);
        if (copy == 0) {
            return null;
        }
        try {
            return OSShim.CTFontCopyURLAttribute(copy);
        } finally {
            OSShim.CFRelease(copy);
        }
    }

    private static long firstRun(long line) {
        long runs = OSShim.CTLineGetGlyphRuns(line);
        if (runs == 0 || OSShim.CFArrayGetCount(runs) == 0) {
            throw new IllegalStateException("CoreText laid \"A\" out in no run");
        }
        return OSShim.CFArrayGetValueAtIndex(runs, 0);
    }

    /** The font of {@code run}, which its line holds; 0 if it has none. */
    private static long runFont(long run) {
        long attributes = OSShim.CTRunGetAttributes(run);
        return attributes == 0 ? 0 : OSShim.CFDictionaryGetValue(attributes, OSShim.kCTFontAttributeName());
    }

    private static String fontName(long font) {
        return font == 0 ? null : OSShim.CTFontCopyAttributeDisplayName(font);
    }

    /** CoreText's glyph IDs of {@code run}, without a slot. */
    private static String glyphIds(long run) {
        int[] glyphs = new int[(int) MacFontOracleShim.runGlyphCount(run)];
        OSShim.CTRunGetGlyphs(run, 0, 0, glyphs);
        return Arrays.stream(glyphs).mapToObj(Integer::toHexString).collect(Collectors.joining(";"));
    }

    /**
     * Cascade lists of Helvetica on fresh platform threads that push no autorelease pool, so that what a call leaves
     * autoreleased waits there for the thread to exit. The control is the same region without a pool: the list of
     * the font for language codes the probe copies, read by the production helper the cascade list calls. Then the
     * pool tokens of one more such thread over {@link #TOKEN_CYCLES} cycles of a region that autoreleases nothing,
     * the control and the call.
     */
    private static void cascadeListPool(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        String[] list = MacFontFinderShim.getCascadeList(font);
        out.put("entries", Integer.toString(list.length / 2));
        heapPerCall(out, "subject", Thread.ofPlatform(), () -> MacFontFinderShim.getCascadeList(font), list);
        heapPerCall(out, "control", Thread.ofPlatform(), () -> cascadeListWithoutAPool(font), list);
        TokenCycles tokens = new TokenCycles(list);
        Thread thread = Thread.ofPlatform().name("tokens").unstarted(() -> tokensOnThisThread(tokens, font));
        thread.start();
        thread.join();
        tokens.write(out);
        MacFontOracleShim.release(font);
    }

    private static void tokensOnThisThread(TokenCycles tokens, long font) {
        try {
            for (int i = 0; i < TOKEN_CYCLES; i++) {
                tokens.empty.add(tokenMove(() -> { }));
                tokens.control.add(tokenMove(() -> tokens.check(cascadeListWithoutAPool(font))));
                tokens.subject.add(tokenMove(() -> tokens.check(MacFontFinderShim.getCascadeList(font))));
            }
        } catch (RuntimeException | Error e) {
            tokens.failure = e.toString();
        }
    }

    /**
     * The same from fresh virtual threads, whose calls the cascade-list thread makes. The control runs the region
     * without a pool on the carrier, the hand-off probe hands that thread a task that does nothing, through
     * production's hand-off, and the platform probe is a call on a platform thread. The tokens are those of the
     * cascade-list thread: its cycles run there, and a virtual caller's call is a cycle of its own.
     */
    private static void cascadeListVirtual(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        String[] list = MacFontFinderShim.getCascadeList(font);
        out.put("entries", Integer.toString(list.length / 2));
        heapPerCall(out, "subject", Thread.ofVirtual(), () -> MacFontFinderShim.getCascadeList(font), list);
        heapPerCall(out, "control", Thread.ofVirtual(), () -> cascadeListWithoutAPool(font), list);
        Consumer<Runnable> handOff = MacFontFinderShim.cascadeListHandOff();
        heapPerCall(out, "handOff", Thread.ofVirtual(), () -> emptyHandOff(handOff, list), list);
        heapPerCall(out, "platform", Thread.ofPlatform(), () -> MacFontFinderShim.getCascadeList(font), list);
        TokenCycles tokens = new TokenCycles(list);
        try {
            for (int i = 0; i < TOKEN_CYCLES; i++) {
                tokens.empty.add(onTheWorker(handOff, () -> tokenMove(() -> { })));
                tokens.control.add(onTheWorker(handOff,
                        () -> tokenMove(() -> tokens.check(cascadeListWithoutAPool(font)))));
                tokens.subject.add(handedOffMove(handOff, font, tokens));
            }
        } catch (RuntimeException | Error | InterruptedException e) {
            tokens.failure = e.toString();
        }
        tokens.write(out);
        out.put("tokens.handedOff", Integer.toString(tokens.handedOff));
        MacFontOracleShim.release(font);
    }

    /**
     * How far a virtual caller's call moves the next pool token of the cascade-list thread: a task there pushes a
     * frame pool, reads the token and holds the thread until the caller is seen waiting, or has ended; once the caller
     * has its list, a second task reads the token again and pops the frame.
     */
    private static long handedOffMove(Consumer<Runnable> handOff, long font, TokenCycles tokens)
            throws InterruptedException {
        long[] frameAndToken = new long[2];
        Hold hold = new Hold(() -> {
            frameAndToken[0] = MacFontProbeShim.autoreleasePoolPush();
            frameAndToken[1] = nextPoolToken();
        });
        handOff.accept(hold);
        ListCall call = new ListCall(font);
        try {
            if (!hold.awaitInside()) {
                throw new IllegalStateException("the cascade-list thread did not run the holding task");
            }
            Thread caller = Thread.ofVirtual().name(CASCADE_LIST_VIRTUAL + ".caller").start(call);
            if (awaitParkedInTheWait(caller)) {
                tokens.handedOff++;
            }
            hold.release();
            if (!caller.join(Duration.ofSeconds(WAIT_PARK_SECONDS))) {
                throw new IllegalStateException("a virtual caller did not get its list");
            }
        } finally {
            hold.release();
        }
        if (call.failure != null) {
            throw new IllegalStateException(call.failure);
        }
        tokens.check(call.list);
        return onTheWorker(handOff, () -> {
            long after = nextPoolToken();
            MacFontProbeShim.autoreleasePoolPop(frameAndToken[0]);
            return after - frameAndToken[1];
        });
    }

    /** How far {@code region} moves this thread's next pool token; it runs inside a frame pool of the probe's own. */
    private static long tokenMove(Runnable region) {
        long frame = MacFontProbeShim.autoreleasePoolPush();
        try {
            long before = nextPoolToken();
            region.run();
            return nextPoolToken() - before;
        } finally {
            MacFontProbeShim.autoreleasePoolPop(frame);
        }
    }

    /** The token the next push of this thread answers: a pop puts the next push back where the popped pool was. */
    private static long nextPoolToken() {
        long token = MacFontProbeShim.autoreleasePoolPush();
        MacFontProbeShim.autoreleasePoolPop(token);
        return token;
    }

    /**
     * Runs {@code task} on the cascade-list thread through production's hand-off and answers what it answered, once
     * it has; a task that does not end within {@link #WAIT_PARK_SECONDS} fails.
     */
    private static long onTheWorker(Consumer<Runnable> handOff, LongSupplier task) throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        long[] answer = new long[1];
        Throwable[] failure = new Throwable[1];
        handOff.accept(() -> {
            try {
                answer[0] = task.getAsLong();
            } catch (Throwable t) {
                // Anything a task throws would end the cascade-list thread for the rest of the child.
                failure[0] = t;
            }
            ran.countDown();
        });
        if (!ran.await(WAIT_PARK_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the cascade-list thread did not run a task within " + WAIT_PARK_SECONDS
                    + " s");
        }
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
        return answer[0];
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
     * A virtual caller that interrupts itself and then asks for the cascade list while a task holds the cascade-list
     * thread, so that its task cannot be done before the caller is seen parked in its wait. A thread parks only with
     * its interrupt status clear, so a caller parked there has had its interrupt seen and cleared by the wait.
     */
    private static void cascadeListWaitInterrupted(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        String[] list = MacFontFinderShim.getCascadeList(font);
        Hold hold = new Hold();
        MacFontFinderShim.cascadeListHandOff().accept(hold);
        InterruptedCall call = new InterruptedCall(font);
        Thread caller;
        try {
            out.put("hold.inside", Boolean.toString(hold.awaitInside()));
            caller = Thread.ofVirtual().name(CASCADE_LIST_WAIT_INTERRUPTED).start(call);
            out.put("parkedInTheWait", Boolean.toString(awaitParkedInTheWait(caller)));
            out.put("interruptedWhileParked", Boolean.toString(caller.isInterrupted()));
            out.put("callerState", caller.getState().toString());
        } finally {
            hold.release();
        }
        out.put("joined", Boolean.toString(caller.join(Duration.ofSeconds(WAIT_PARK_SECONDS))));
        out.put("virtual", Boolean.toString(call.virtual));
        out.put("interruptedBefore", Boolean.toString(call.interruptedBefore));
        out.put("failure", call.failure);
        out.put("sameList", Boolean.toString(Arrays.equals(list, call.list)));
        out.put("interruptedAfter", Boolean.toString(call.interruptedAfter));
        MacFontOracleShim.release(font);
    }

    /**
     * {@link #HELD_CALLERS} virtual callers ask for the cascade list of Helvetica while a task holds the cascade-list
     * thread. A first virtual caller, with an inheritable thread local and a context class loader of its own, started
     * that thread, which the task records. Each caller must be seen waiting for it before the hold is released, and
     * it must be the only thread of its name; then it is interrupted, and must park again and still answer.
     */
    private static void cascadeListWorker(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        String[] list = MacFontFinderShim.getCascadeList(font);
        ListCall starter = new ListCall(font);
        Thread starterThread = Thread.ofVirtual().name(STARTER).unstarted(() -> {
            INHERITED.set(STARTER);
            Thread.currentThread().setContextClassLoader(new ClassLoader(null) { });
            starter.run();
        });
        starterThread.start();
        starterThread.join();
        out.put("starter.failure", starter.failure);
        out.put("starter.sameList", Boolean.toString(Arrays.equals(list, starter.list)));
        Hold hold = new Hold();
        MacFontFinderShim.cascadeListHandOff().accept(hold);
        List<ListCall> calls = new ArrayList<>();
        List<Thread> callers = new ArrayList<>();
        try {
            out.put("hold.inside", Boolean.toString(hold.awaitInside()));
            for (int i = 0; i < HELD_CALLERS; i++) {
                ListCall call = new ListCall(font);
                calls.add(call);
                callers.add(Thread.ofVirtual().name(CASCADE_LIST_WORKER + "." + i).start(call));
            }
            awaitAllParkedInTheWait(callers);
            out.put("callers.parked", Long.toString(callers.stream().filter(MacFontReleaseChild::isParkedInTheWait)
                                                           .count()));
            out.put("callers.terminatedWhileHeld",
                    Long.toString(callers.stream().filter(caller -> !caller.isAlive()).count()));
            out.put("workers.whileHeld", Long.toString(cascadeListThreads()));
        } finally {
            hold.release();
        }
        out.put("callers.joined", Boolean.toString(joinAll(callers)));
        out.put("callers.sameLists", Long.toString(calls.stream().filter(call -> Arrays.equals(list, call.list))
                                                        .count()));
        out.put("callers.failures", calls.stream().map(call -> call.failure).filter(Objects::nonNull)
                                         .reduce((first, second) -> first + " | " + second).orElse(null));
        out.put("workers.after", Long.toString(cascadeListThreads()));
        hold.write(out);
        interruptTheWorker(out, font, list);
        MacFontOracleShim.release(font);
    }

    /**
     * Interrupts the cascade-list thread, which must clear the interrupt and park again, and then make the list of a
     * virtual caller.
     */
    private static void interruptTheWorker(GoldenMap out, long font, String[] list) throws InterruptedException {
        Thread worker = MacFontFinderShim.cascadeListWorker();
        worker.interrupt();
        out.put("worker.parkedAfterInterrupt", Boolean.toString(awaitParkedWithoutInterrupt(worker)));
        ListCall call = new ListCall(font);
        Thread caller = Thread.ofVirtual().name(CASCADE_LIST_WORKER + ".afterInterrupt").start(call);
        out.put("afterInterrupt.answered", Boolean.toString(caller.join(Duration.ofSeconds(WAIT_PARK_SECONDS))));
        out.put("afterInterrupt.failure", call.failure);
        out.put("afterInterrupt.sameList", Boolean.toString(Arrays.equals(list, call.list)));
    }

    /**
     * The retain count of a font a virtual caller lends while its task waits behind a task that holds the
     * cascade-list thread, and after it ran. A virtual call first makes that thread's first list; then the pooled
     * region, run there twice in a row by tasks of the probe's own, gives the count CoreText's own references leave
     * over each of the next two lists on that thread, without the task's own reference: {@code query.delta} and
     * {@code query.again.delta}.
     */
    private static void cascadeListLentFont(GoldenMap out) throws Exception {
        long font = MacFontOracleShim.createFont("Helvetica", 12);
        String[] list = MacFontFinderShim.getCascadeList(font);
        out.put("warm.sameList", Boolean.toString(Arrays.equals(list, virtualCall(font).list)));
        Consumer<Runnable> handOff = MacFontFinderShim.cascadeListHandOff();
        String[] queried = pooledOnTheWorker(out, "query.delta", handOff, font);
        String[] queriedAgain = pooledOnTheWorker(out, "query.again.delta", handOff, font);
        out.put("query.sameList", Boolean.toString(Arrays.equals(list, queried) && Arrays.equals(list, queriedAgain)));
        Hold hold = new Hold();
        handOff.accept(hold);
        ListCall call = new ListCall(font);
        long beforeCall;
        Thread caller;
        try {
            out.put("hold.inside", Boolean.toString(hold.awaitInside()));
            beforeCall = MacFontOracleShim.retainCount(font);
            caller = Thread.ofVirtual().name(CASCADE_LIST_LENT_FONT).start(call);
            out.put("parked", Boolean.toString(awaitParkedInTheWait(caller)));
            out.put("retain.whileQueued", Long.toString(MacFontOracleShim.retainCount(font) - beforeCall));
        } finally {
            hold.release();
        }
        out.put("joined", Boolean.toString(caller.join(Duration.ofSeconds(WAIT_PARK_SECONDS))));
        out.put("retain.afterRun", Long.toString(MacFontOracleShim.retainCount(font) - beforeCall));
        out.put("failure", call.failure);
        out.put("sameList", Boolean.toString(Arrays.equals(list, call.list)));
        MacFontOracleShim.release(font);
    }

    /**
     * Runs the pooled region on the cascade-list thread, through a task of the probe's own, and writes under
     * {@code key} how far that moved the retain count of {@code font}; answers the list the region made.
     */
    private static String[] pooledOnTheWorker(GoldenMap out, String key, Consumer<Runnable> handOff, long font)
            throws InterruptedException {
        String[][] pooled = new String[1][];
        long before = MacFontOracleShim.retainCount(font);
        onTheWorker(handOff, () -> {
            pooled[0] = MacFontFinderShim.pooledCascadeList(font);
            return 0;
        });
        out.put(key, Long.toString(MacFontOracleShim.retainCount(font) - before));
        return pooled[0];
    }

    /** A virtual thread's call of the cascade list of {@code font}, waited for. */
    private static ListCall virtualCall(long font) throws InterruptedException {
        ListCall call = new ListCall(font);
        Thread caller = Thread.ofVirtual().name("virtualCall").start(call);
        if (!caller.join(Duration.ofSeconds(WAIT_PARK_SECONDS))) {
            throw new IllegalStateException("a virtual caller did not get its list within " + WAIT_PARK_SECONDS + " s");
        }
        if (call.failure != null) {
            throw new IllegalStateException(call.failure);
        }
        return call;
    }

    /** The live platform threads named {@link #CASCADE_LIST_THREAD}. */
    private static long cascadeListThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                     .filter(thread -> thread.getName().equals(CASCADE_LIST_THREAD)).count();
    }

    /** Whether all of {@code threads} end within {@link #WAIT_PARK_SECONDS} together. */
    private static boolean joinAll(List<Thread> threads) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PARK_SECONDS);
        for (Thread thread : threads) {
            long left = Math.max(1, deadline - System.nanoTime());
            if (!thread.join(Duration.ofNanos(left))) {
                return false;
            }
        }
        return true;
    }

    /** Waits until every caller is parked in its wait, or one has ended; the deadline bounds only a failure. */
    private static void awaitAllParkedInTheWait(List<Thread> callers) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PARK_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            if (callers.stream().anyMatch(caller -> !caller.isAlive())
                    || callers.stream().allMatch(MacFontReleaseChild::isParkedInTheWait)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /**
     * Whether {@code thread} is seen parked with its interrupt status clear within {@link #WAIT_PARK_SECONDS}; false
     * once it has ended. The status is read first, so a park seen after it is one the thread made once it had cleared
     * the interrupt.
     */
    private static boolean awaitParkedWithoutInterrupt(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PARK_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            if (!thread.isInterrupted() && thread.getState() == Thread.State.WAITING) {
                return true;
            }
            if (!thread.isAlive()) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return false;
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

    /** WAITING in a virtual caller's wait for its task, {@code MacFontFinder$CascadeListTask.awaitList}. */
    private static boolean isParkedInTheWait(Thread thread) {
        if (thread.getState() != Thread.State.WAITING) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals("com.sun.javafx.font.MacFontFinder$CascadeListTask")
                    && frame.getMethodName().equals("awaitList")) {
                return true;
            }
        }
        return false;
    }

    private static String[] cascadeListWithoutAPool(long font) {
        return MacFontFinderShim.cascadeList(font, MacFontProbeShim.languageCodes());
    }

    /** A task that does nothing, handed to the cascade-list thread through production's hand-off and waited for. */
    private static String[] emptyHandOff(Consumer<Runnable> handOff, String[] list) {
        CountDownLatch ran = new CountDownLatch(1);
        handOff.accept(ran::countDown);
        try {
            ran.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return list;
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

    /** A font of Ahem, a line of "A" in it, the line's first run, and the System font that lays that run out. */
    private record FaceRun(long font, long line, long run, CompositeFontResource system) {

        void release() {
            OSShim.CFRelease(line);
            MacFontOracleShim.release(font);
            Reference.reachabilityFence(system);
        }
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

    /** A call of the cascade list of {@code font}; what it answered or threw is read once its thread has ended. */
    private static final class ListCall implements Runnable {

        private final long font;
        private String[] list;
        private String failure;

        ListCall(long font) {
            this.font = font;
        }

        @Override
        public void run() {
            try {
                list = MacFontFinderShim.getCascadeList(font);
            } catch (RuntimeException | Error e) {
                failure = e.toString();
            }
        }
    }

    /**
     * A task that holds the cascade-list thread until it is released, or for {@link #HOLD_SECONDS}, after it recorded
     * that thread and ran {@code first} there.
     */
    private static final class Hold implements Runnable {

        private final Runnable first;
        private final CountDownLatch inside = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean virtual;
        private boolean daemon;
        private String name;
        private String inherited;
        private boolean systemLoader;
        private String failure;

        Hold() {
            this(() -> { });
        }

        Hold(Runnable first) {
            this.first = first;
        }

        @Override
        public void run() {
            try {
                Thread thread = Thread.currentThread();
                virtual = thread.isVirtual();
                daemon = thread.isDaemon();
                name = thread.getName();
                inherited = INHERITED.get();
                systemLoader = thread.getContextClassLoader() == ClassLoader.getSystemClassLoader();
                first.run();
                inside.countDown();
                release.await(HOLD_SECONDS, TimeUnit.SECONDS);
            } catch (Throwable t) {
                // Anything a task throws would end the cascade-list thread for the rest of the child.
                failure = t.toString();
                inside.countDown();
            }
        }

        boolean awaitInside() throws InterruptedException {
            return inside.await(WAIT_PARK_SECONDS, TimeUnit.SECONDS) && failure == null;
        }

        void release() {
            release.countDown();
        }

        /** What it recorded of the thread it ran on; read once it is inside. */
        void write(GoldenMap out) {
            out.put("hold.failure", failure);
            out.put("hold.virtual", Boolean.toString(virtual));
            out.put("hold.daemon", Boolean.toString(daemon));
            out.put("hold.name", name);
            out.put("hold.inherited", inherited);
            out.put("hold.systemLoader", Boolean.toString(systemLoader));
        }
    }

    /**
     * The pool-token moves of {@link #TOKEN_CYCLES} cycles, in bytes, each of a region that autoreleases nothing,
     * the region without a pool and the call, and whether every list answered was the font's.
     */
    private static final class TokenCycles {

        private final String[] list;
        private final List<Long> empty = new ArrayList<>();
        private final List<Long> control = new ArrayList<>();
        private final List<Long> subject = new ArrayList<>();
        private int handedOff;
        private boolean sameList = true;
        private String failure;

        TokenCycles(String[] list) {
            this.list = list;
        }

        void check(String[] answered) {
            sameList &= Arrays.equals(list, answered);
        }

        void write(GoldenMap out) {
            out.put("tokens.empty.moves", joined(empty));
            out.put("tokens.control.moves", joined(control));
            out.put("tokens.subject.moves", joined(subject));
            out.put("tokens.cycles", Integer.toString(subject.size()));
            out.put("tokens.sameList", Boolean.toString(sameList));
            out.put("tokens.failure", failure);
        }

        private static String joined(List<Long> moves) {
            return moves.stream().map(String::valueOf).collect(Collectors.joining(";"));
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
