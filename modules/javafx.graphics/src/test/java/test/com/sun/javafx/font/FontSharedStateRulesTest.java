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

import java.lang.reflect.AccessFlag;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules by which the font layer shares its caches between the threads that lay out, render and print text,
 * read from the class files: the glyph boxes of a font file, the glyphs of a strike, a composite's glyph codes, slot
 * mappers and slot strikes, the glyph mappers and metrics made on first use, and the factory's embedded fonts and
 * name lists. None of these can be shown to break at run time on every host, and the class files are the same on
 * every host, so these checks pin them on any OS; they initialise no font class and make no native call.
 */
public class FontSharedStateRulesTest {

    private static final String FONT = "com/sun/javafx/font/";
    private static final String PRISM_FONT_FILE = FONT + "PrismFontFile";
    private static final String PRISM_FONT_STRIKE = FONT + "PrismFontStrike";
    private static final String COMPOSITE_GLYPH_MAPPER = FONT + "CompositeGlyphMapper";
    private static final String COMPOSITE_STRIKE = FONT + "CompositeStrike";
    private static final String PRISM_FONT_FACTORY = FONT + "PrismFontFactory";
    private static final String CONCURRENT_MAP = "java/util/concurrent/ConcurrentHashMap <init>";
    private static final String GET_GLYPH = "getGlyph(I)L" + FONT + "Glyph;";
    private static final List<String> REPLACING_CALLS = List.of("java/util/Map put", "java/util/Map computeIfAbsent",
                                                               "java/util/Map compute");
    private static final String NO_MONITOR = "the layout, render and print threads, which run it for every glyph,"
            + " never wait for one another there";

    /**
     * A font file keeps its glyph boxes in a concurrent map made with it, measures a box outside the map, since
     * measuring may take the font file's monitor, and keeps the box stored first, so that every caller of a new glyph
     * gets that one; the lookup takes no monitor.
     */
    @Test
    void aGlyphBoxIsMeasuredOutsideTheMapAndTheBoxStoredFirstIsKept() {
        FontCallOrder.assertFieldIs(PRISM_FONT_FILE, "bbCache", AccessFlag.FINAL,
                "the threads that measure glyph boxes share the one map made with the font file");
        FontCallOrder.assertFieldStoresFollow(PRISM_FONT_FILE, "bbCache", List.of(CONCURRENT_MAP),
                "the layout, render and print threads that measure glyph boxes share one map made for it");
        FontCallOrder.assertFirstCallBefore(PRISM_FONT_FILE, "getGlyphBoundingBox", "java/util/Map get",
                                            PRISM_FONT_FILE + " createGlyphBoundingBox");
        FontCallOrder.assertFirstCallBefore(PRISM_FONT_FILE, "getGlyphBoundingBox",
                                            PRISM_FONT_FILE + " createGlyphBoundingBox", "java/util/Map putIfAbsent");
        FontCallOrder.assertNeverCalls(PRISM_FONT_FILE, "getGlyphBoundingBox", REPLACING_CALLS, "a box another thread"
                + " stored first is the one every caller gets, and no platform's measurement, which may take the font"
                + " file's monitor, runs under the map's lock");
        FontCallOrder.assertTakesNoMonitor(PRISM_FONT_FILE, "getGlyphBoundingBox", NO_MONITOR);
    }

    /**
     * A strike keeps its glyphs in a concurrent map made with it, makes a glyph outside the map and keeps the glyph
     * stored first, so that every caller of a new glyph gets the same one; the lookup takes no monitor and no lock of
     * the map's on a hit.
     */
    @Test
    void aStrikeMakesEachGlyphOutsideItsMapAndKeepsTheOneStoredFirst() {
        FontCallOrder.assertFieldIs(PRISM_FONT_STRIKE, "glyphMap", AccessFlag.FINAL,
                "the threads that draw a strike's glyphs share the one map made with the strike");
        FontCallOrder.assertFieldStoresFollow(PRISM_FONT_STRIKE, "glyphMap", List.of(CONCURRENT_MAP),
                "the render, print and layout threads that share a strike share one map made for it");
        FontCallOrder.assertFirstCallBefore(PRISM_FONT_STRIKE, GET_GLYPH, "java/util/Map get",
                                            PRISM_FONT_STRIKE + " createGlyph");
        FontCallOrder.assertFirstCallBefore(PRISM_FONT_STRIKE, GET_GLYPH, PRISM_FONT_STRIKE + " createGlyph",
                                            "java/util/Map putIfAbsent");
        FontCallOrder.assertNeverCalls(PRISM_FONT_STRIKE, GET_GLYPH, REPLACING_CALLS, "no bin lock is taken on the"
                + " per-glyph hit path and the glyph stored first is the one every caller gets");
        FontCallOrder.assertTakesNoMonitor(PRISM_FONT_STRIKE, GET_GLYPH, NO_MONITOR);
    }

    /** A composite's mapper keeps the glyph codes it found in a concurrent map made with it. */
    @Test
    void aCompositeMapperKeepsItsGlyphCodesInAConcurrentMap() {
        FontCallOrder.assertFieldIs(COMPOSITE_GLYPH_MAPPER, "glyphMap", AccessFlag.FINAL,
                "every layout thread of a composite shares the one map made with its mapper");
        FontCallOrder.assertFieldStoresFollow(COMPOSITE_GLYPH_MAPPER, "glyphMap", List.of(CONCURRENT_MAP),
                "layout threads that map characters of one composite at once corrupt no map");
    }

    /**
     * A composite's mapper reads its slot mappers once per call, from a volatile field that it replaces only with a
     * filled copy, and its lookups take no monitor.
     */
    @Test
    void aCompositeMapperPublishesItsSlotMappersOnlyAsFilledCopies() {
        String protects = "a layout thread indexes only the slot mappers it read, and finds each slot filled";
        FontCallOrder.assertFieldIs(COMPOSITE_GLYPH_MAPPER, "slotMappers", AccessFlag.VOLATILE, protects);
        FontCallOrder.assertFieldReadOnlyIn(COMPOSITE_GLYPH_MAPPER, "slotMappers", List.of("getSlotMapper"), protects);
        FontCallOrder.assertFieldReadOnceIn(COMPOSITE_GLYPH_MAPPER, "getSlotMapper", "slotMappers", protects);
        FontCallOrder.assertPublishesOnlyFilledCopies(COMPOSITE_GLYPH_MAPPER, "getSlotMapper", "slotMappers",
                                                      protects);
        for (String method : List.of("getGlyphCode", "getAsciiGlyphCode", "getSlotMapper", "convertToGlyph")) {
            FontCallOrder.assertTakesNoMonitor(COMPOSITE_GLYPH_MAPPER, method, NO_MONITOR);
        }
    }

    /**
     * A composite's mapper publishes its ASCII glyph codes in a new array only once it has filled it, and reads them
     * once per call.
     */
    @Test
    void aCompositeMapperPublishesItsAsciiGlyphsOnceFilled() {
        String protects = "a layout thread finds either no ASCII glyph codes or all of them";
        FontCallOrder.assertFieldIs(COMPOSITE_GLYPH_MAPPER, "charToGlyph", AccessFlag.VOLATILE, protects);
        FontCallOrder.assertFieldReadOnlyIn(COMPOSITE_GLYPH_MAPPER, "charToGlyph", List.of("getAsciiGlyphCode"),
                                            protects);
        FontCallOrder.assertFieldReadOnceIn(COMPOSITE_GLYPH_MAPPER, "getAsciiGlyphCode", "charToGlyph", protects);
        FontCallOrder.assertPublishesNewArrayOnceFilled(COMPOSITE_GLYPH_MAPPER, "getAsciiGlyphCode", "charToGlyph",
                                                        protects);
    }

    /**
     * A composite strike reads its slot-0 strike and its slot strikes once per call, from volatile fields; it replaces
     * the slot strikes only with a filled copy, {@code clearDesc}, the other method that reads them, writes into no
     * array, and its lookups take no monitor.
     */
    @Test
    void aCompositeStrikePublishesItsSlotStrikesOnlyAsFilledCopies() {
        String protects = "a render, layout or print thread indexes only the slot strikes it read, and finds each"
                + " filled";
        for (String field : List.of("slot0Strike", "strikeSlots")) {
            FontCallOrder.assertFieldIs(COMPOSITE_STRIKE, field, AccessFlag.VOLATILE, protects);
            FontCallOrder.assertFieldReadOnlyIn(COMPOSITE_STRIKE, field, List.of("getStrikeSlot", "clearDesc"),
                                                protects);
            FontCallOrder.assertFieldReadOnceIn(COMPOSITE_STRIKE, "getStrikeSlot", field, protects);
            FontCallOrder.assertFieldReadOnceIn(COMPOSITE_STRIKE, "clearDesc", field, protects);
        }
        FontCallOrder.assertPublishesOnlyFilledCopies(COMPOSITE_STRIKE, "getStrikeSlot", "strikeSlots", protects);
        FontCallOrder.assertTakesNoMonitor(COMPOSITE_STRIKE, "getStrikeSlot", NO_MONITOR);
        FontCallOrder.assertTakesNoMonitor(COMPOSITE_STRIKE, GET_GLYPH, NO_MONITOR);
        FontCallOrder.assertStoresNoArrayElement(COMPOSITE_STRIKE, List.of("clearDesc"), protects);
    }

    /**
     * Every glyph mapper made on first use is published through a volatile field and read once per call: a layout
     * thread that finds a mapper another thread made finds it whole, where it could otherwise see its maps or its
     * cmap still null.
     */
    @Test
    void everyLazilyMadeGlyphMapperIsPublishedThroughAVolatileField() {
        for (String type : List.of(FONT + "LogicalFont", FONT + "FallbackResource", FONT + "PrismCompositeFontResource",
                                   PRISM_FONT_FILE)) {
            String protects = "a layout thread that finds the glyph mapper of a " + type + " another thread made finds"
                    + " it whole";
            FontCallOrder.assertFieldIs(type, "mapper", AccessFlag.VOLATILE, protects);
            FontCallOrder.assertFieldReadOnceIn(type, "getGlyphMapper", "mapper", protects);
        }
    }

    /**
     * The metrics of a strike and the style metrics of a font file, made on first use, are published through volatile
     * fields and read once per call, and the style metrics that a strike's metrics fill in after the strike shared
     * them are published through a volatile field too.
     */
    @Test
    void everyLazilyMadeMetricsIsPublishedThroughAVolatileField() {
        String protects = "a render or layout thread that finds metrics another thread made finds them filled";
        for (String type : List.of(PRISM_FONT_STRIKE, COMPOSITE_STRIKE)) {
            FontCallOrder.assertFieldIs(type, "metrics", AccessFlag.VOLATILE, protects);
            FontCallOrder.assertFieldReadOnceIn(type, "getMetrics", "metrics", protects);
        }
        FontCallOrder.assertFieldIs(PRISM_FONT_FILE, "styleMetrics", AccessFlag.VOLATILE, protects);
        FontCallOrder.assertFieldReadOnlyIn(PRISM_FONT_FILE, "styleMetrics", List.of("getStyleMetrics"), protects);
        FontCallOrder.assertFieldReadOnceIn(PRISM_FONT_FILE, "getStyleMetrics", "styleMetrics", protects);
        FontCallOrder.assertFieldIs(FONT + "PrismMetrics", "styleMetrics", AccessFlag.VOLATILE, protects);
    }

    /**
     * A font file publishes the style metrics it made on first use in a new array only once it has filled it, and the
     * metrics of a strike keep only the style metrics the font file answers, which it filled before it answered them.
     */
    @Test
    void theStyleMetricsArePublishedOnlyOnceFilled() {
        String protects = "a render or layout thread that finds style metrics another thread made finds all of them";
        FontCallOrder.assertPublishesNewArrayOnceFilled(PRISM_FONT_FILE, "getStyleMetrics", "styleMetrics", protects);
        FontCallOrder.assertFieldStoresFollow(FONT + "PrismMetrics", "styleMetrics",
                                              List.of(PRISM_FONT_FILE + " getStyleMetrics"), protects);
    }

    /**
     * The fonts a composite added are read once per call by the methods that read them without the composite's
     * monitor, so that each call counts or searches one list.
     */
    @Test
    void eachCompositeReadsTheFontsItAddedOncePerCall() {
        String protects = "a call that does not hold the composite's monitor counts or searches one list of added"
                + " fonts, not two that another thread's addition came between";
        for (String type : List.of(FONT + "LogicalFont", FONT + "FallbackResource")) {
            for (String method : List.of("getNumSlots", "getSlotForFontNoCreate")) {
                FontCallOrder.assertFieldReadOnceIn(type, method, "nativeFallbacks", protects);
            }
        }
    }

    /**
     * Every method that reads or replaces the factory's embedded fonts or its two name lists holds the factory's
     * monitor, which every writer of them holds: the shutdown hook too, through a synchronized method, never a lambda
     * of its own.
     */
    @Test
    void theEmbeddedFontsAndTheNameListsAreUsedOnlyUnderTheFactoryMonitor() {
        FontCallOrder.assertReachedOnlyUnderTheMonitor(MacFontBindingStructureTest.nest(PRISM_FONT_FACTORY),
                List.of("embeddedFonts", "allFamilyNames", "allFontNames"), List.of(),
                "listing the fonts, or disposing of them at exit, while another thread loads one cannot break the"
                + " walk of the embedded fonts, and a name list is read only once it is built");
    }
}
