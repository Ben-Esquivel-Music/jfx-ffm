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

import com.sun.javafx.font.DisposerRecord;
import com.sun.javafx.font.FontFallbackInfo;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.FontSharedStateShim;
import com.sun.javafx.font.FontStrikeDesc;
import com.sun.javafx.font.Glyph;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.PrismFontFile;
import com.sun.javafx.font.PrismFontStrike;
import com.sun.javafx.geom.Path2D;
import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.geom.Shape;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.text.GlyphLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two threads at once on the shared caches of a font file, of a strike, and of the factory's embedded fonts: one is
 * held inside the cache's work while the other uses the same cache. Each decides by what the threads answer and by
 * whether the second thread blocks on a monitor that the held one holds, never by time. In the test JVM, drawing
 * nothing and calling no CoreText: the font file is the repository's Ahem, parsed in Java. macOS only, because only
 * macOS has run them: a strike reaches {@code PrismFontFactory.getFontFactory()}, which is the platform's factory, a
 * font file initialises {@code PrismFontFactory}, and nothing can be run on Linux or Windows from the Mac these tests
 * were written on. The class-file checks of {@code FontSharedStateRulesTest} pin the same code on every OS.
 */
@EnabledOnOs(OS.MAC)
public class MacFontSharedStateTest {

    private static final String AHEM = Path.of(MacFontGoldens.REPO_FONTS, "AHEM____.TTF").toString();

    /** The family name the listing test's font answers, which no logical or platform family has. */
    private static final String LISTED_FAMILY = "JFX Listed Family";

    /**
     * Two callers of one glyph box that neither has stored get the box stored first: the second caller measures and
     * stores while the first is held in its measurement, without waiting for it, and the first then answers the box
     * the second stored. The size is the font's units per em, so the boxes are the measured integers.
     */
    @Test
    void twoCallersOfOneNewGlyphBoxGetTheBoxStoredFirst() throws Exception {
        requireAhem();
        BoxRaceFont font = new BoxRaceFont(AHEM);
        int glyph = font.getGlyphMapper().charToGlyph('A');
        assertTrue(glyph > 0 && glyph < FontResourceShim.numGlyphs(font), "Ahem has a glyph for A, glyph " + glyph);
        float size = font.getUnitsPerEm();
        RaceThread<float[]> first = new RaceThread<>("box race: first", () -> font.getGlyphBoundingBox(glyph, size,
                                                                                                        null));
        RaceThread<float[]> second = new RaceThread<>("box race: second", () -> font.getGlyphBoundingBox(glyph, size,
                                                                                                          null));
        font.armed = first;
        first.start();
        boolean waited;
        try {
            first.awaitHeldOrEnded();
            assertTrue(first.held(), "the hold needs getGlyphBoundingBox to measure a box it has not stored");
            second.start();
            waited = second.blocksOn(first);
        } finally {
            first.release();
        }
        float[] firstBox = first.end(second).answered("the first caller of a new glyph box");
        float[] secondBox = second.end(first).answered("the second caller of a new glyph box");
        assertFalse(waited, "a second caller of a new glyph box waited for the first caller's measurement, which may"
                + " take the font file's monitor");
        assertArrayEquals(new float[] {2, 4, 6, 8}, secondBox, "the box the second caller measured");
        assertArrayEquals(secondBox, firstBox, "two callers of one new glyph box got different boxes: the box stored"
                + " first was replaced");
        assertArrayEquals(secondBox, font.getGlyphBoundingBox(glyph, size, null), "the box a later caller gets");
        assertEquals(2, font.measured.get(), "a stored glyph box was measured again");
    }

    /**
     * Two callers of one glyph that neither has stored get the glyph stored first: the second caller makes and stores
     * it while the first is held in making it, without waiting for it, and the first then answers the glyph the second
     * stored.
     */
    @Test
    void twoCallersOfOneNewGlyphGetTheGlyphStoredFirst() throws Exception {
        GlyphRaceStrike strike = new GlyphRaceStrike();
        int code = 7;
        RaceThread<Glyph> first = new RaceThread<>("glyph race: first", () -> strike.getGlyph(code));
        RaceThread<Glyph> second = new RaceThread<>("glyph race: second", () -> strike.getGlyph(code));
        strike.armed = first;
        first.start();
        boolean waited;
        try {
            first.awaitHeldOrEnded();
            assertTrue(first.held(), "the hold needs getGlyph to make a glyph it has not stored");
            second.start();
            waited = second.blocksOn(first);
        } finally {
            first.release();
        }
        Glyph firstGlyph = first.end(second).answered("the first caller of a new glyph");
        Glyph secondGlyph = second.end(first).answered("the second caller of a new glyph");
        assertFalse(waited, "a second caller of a new glyph waited for the first caller to make it");
        assertEquals(2, ((MarkedGlyph) secondGlyph).made(), "the glyph the second caller made");
        assertSame(secondGlyph, firstGlyph, "two callers of one new glyph got two glyphs: the glyph stored first was"
                + " replaced");
        assertSame(secondGlyph, strike.getGlyph(code), "the glyph a later caller gets");
        assertEquals(2, strike.made.get(), "a stored glyph was made again");
    }

    /**
     * Listing the fonts of an embedded family while another thread loads a font throws nothing: the listing holds the
     * factory's monitor, and the load, which changes the embedded fonts under it, waits for the listing. The second
     * load answers the font file the factory cached for the first, under another alias.
     */
    @Test
    void listingAFamilyWhileAFontLoadsThrowsNothing() throws Exception {
        requireAhem();
        ListingFactory factory = new ListingFactory();
        PrismFontFile[] loaded = FontSharedStateShim.loadEmbeddedFont(factory, "JFX Listed A", AHEM);
        assertTrue(loaded != null && loaded.length == 1, "the factory loads Ahem: " + Arrays.toString(loaded));
        String fullName = loaded[0].getFullName();
        assertEquals(List.of(fullName, fullName), List.of(factory.getFontFullNames(LISTED_FAMILY)),
                     "the embedded family lists the font under its alias and its full name");
        RaceThread<String[]> lister = new RaceThread<>("listing race: lister",
                                                       () -> factory.getFontFullNames(LISTED_FAMILY));
        RaceThread<PrismFontFile[]> loader = new RaceThread<>("listing race: loader",
                () -> FontSharedStateShim.loadEmbeddedFont(factory, "JFX Listed B", AHEM));
        factory.lister = lister;
        lister.start();
        boolean waited;
        try {
            lister.awaitHeldOrEnded();
            assertTrue(lister.held(), "the hold needs getFontFullNames to read the family of an embedded font");
            loader.start();
            waited = loader.blocksOn(lister, factory);
        } finally {
            lister.release();
        }
        String[] names = lister.end(loader).answered("listing a family's fonts while another thread loaded a font");
        PrismFontFile[] again = loader.end(lister).answered("loading a font while another thread listed a family");
        assertTrue(waited, "a font loaded while another thread lists a family waits for the factory's monitor");
        assertTrue(List.of(names).contains(fullName), "the listed names " + List.of(names) + " hold " + fullName);
        assertSame(loaded[0], again[0], "the second load answers the font file the factory cached for the first");
    }

    /** Fails, naming the path, where the repository's Ahem is not where the test looks for it. */
    private static void requireAhem() {
        assertTrue(Files.isReadable(Path.of(AHEM)), "the repository's Ahem is readable at " + Path.of(AHEM)
                .toAbsolutePath());
    }

    /**
     * A font file whose glyph boxes are counted made-up boxes, {@code {n, 2n, 3n, 4n}} for the n-th measurement, and
     * whose first measurement on the armed thread is held.
     */
    private static final class BoxRaceFont extends PrismFontFile {

        final AtomicInteger measured = new AtomicInteger();
        volatile RaceThread<?> armed;

        BoxRaceFont(String file) throws Exception {
            super(null, file, 0, false, false, false);
        }

        @Override
        protected PrismFontStrike<?> createStrike(float size, BaseTransform transform, int aaMode,
                                                  FontStrikeDesc desc) {
            throw new UnsupportedOperationException("the box race makes no strike");
        }

        @Override
        protected int[] createGlyphBoundingBox(int gc) {
            int n = measured.incrementAndGet();
            RaceThread<?> thread = armed;
            if (thread != null && thread.isCurrent() && !thread.held()) {
                thread.hold();
            }
            return new int[] {n, 2 * n, 3 * n, 4 * n};
        }
    }

    /** A strike of no font file whose glyphs are counted, and whose first glyph on the armed thread is held. */
    private static final class GlyphRaceStrike extends PrismFontStrike<PrismFontFile> {

        final AtomicInteger made = new AtomicInteger();
        volatile RaceThread<?> armed;

        GlyphRaceStrike() {
            super(null, 12f, BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE,
                  new FontStrikeDesc(12f, BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_GREYSCALE));
        }

        @Override
        protected DisposerRecord createDisposer(FontStrikeDesc desc) {
            return null;
        }

        @Override
        protected Glyph createGlyph(int glyphCode) {
            int n = made.incrementAndGet();
            RaceThread<?> thread = armed;
            if (thread != null && thread.isCurrent() && !thread.held()) {
                thread.hold();
            }
            return new MarkedGlyph(glyphCode, n);
        }

        @Override
        protected Path2D createGlyphOutline(int glyphCode) {
            return null;
        }
    }

    /** A glyph that knows only its code and which making of the strike's it was. */
    private record MarkedGlyph(int code, int made) implements Glyph {

        @Override
        public int getGlyphCode() {
            return code;
        }

        @Override
        public RectBounds getBBox() {
            throw unused();
        }

        @Override
        public float getAdvance() {
            throw unused();
        }

        @Override
        public Shape getShape() {
            throw unused();
        }

        @Override
        public byte[] getPixelData() {
            throw unused();
        }

        @Override
        public byte[] getPixelData(int subPixel) {
            throw unused();
        }

        @Override
        public float getPixelXAdvance() {
            throw unused();
        }

        @Override
        public float getPixelYAdvance() {
            throw unused();
        }

        @Override
        public boolean isLCDGlyph() {
            throw unused();
        }

        @Override
        public int getWidth() {
            throw unused();
        }

        @Override
        public int getHeight() {
            throw unused();
        }

        @Override
        public int getOriginX() {
            throw unused();
        }

        @Override
        public int getOriginY() {
            throw unused();
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("the glyph race reads only a glyph's code");
        }
    }

    /**
     * A factory of no platform whose font files are {@link ListedFont}s: it lays out nothing, looks up no fallbacks
     * and registers nothing with the OS.
     */
    private static final class ListingFactory extends PrismFontFactory {

        volatile RaceThread<?> lister;

        @Override
        protected PrismFontFile createFontFile(String name, String filename, int fIndex, boolean register,
                                               boolean embedded, boolean copy) throws Exception {
            return new ListedFont(this, name, filename, fIndex, register, embedded, copy);
        }

        @Override
        public GlyphLayout createGlyphLayout() {
            throw new UnsupportedOperationException("the listing race lays out nothing");
        }

        @Override
        public FontFallbackInfo getFallbacks(FontResource primaryResource) {
            throw new UnsupportedOperationException("the listing race looks up no fallbacks");
        }
    }

    /**
     * A font file of the listing test's family, whose family the factory's lister thread reads, the first time, only
     * after it was held.
     */
    private static final class ListedFont extends PrismFontFile {

        private final ListingFactory factory;

        ListedFont(ListingFactory factory, String name, String filename, int fIndex, boolean register,
                   boolean embedded, boolean copy) throws Exception {
            super(name, filename, fIndex, register, embedded, copy);
            this.factory = factory;
        }

        @Override
        public String getFamilyName() {
            RaceThread<?> thread = factory == null ? null : factory.lister;
            if (thread != null && thread.isCurrent() && !thread.held()) {
                thread.hold();
            }
            return LISTED_FAMILY;
        }

        @Override
        protected PrismFontStrike<?> createStrike(float size, BaseTransform transform, int aaMode,
                                                  FontStrikeDesc desc) {
            throw new UnsupportedOperationException("the listing race makes no strike");
        }

        @Override
        protected int[] createGlyphBoundingBox(int gc) {
            throw new UnsupportedOperationException("the listing race measures no glyph");
        }
    }
}
