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

import java.io.IOException;
import java.lang.classfile.FieldModel;
import java.lang.reflect.AccessFlag;
import java.util.List;
import org.junit.jupiter.api.Test;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Outcome;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of {@code CTGlyph} that no run-time test can provoke, read from its class file, the rule of
 * {@link MacGlyphImageVerdict}, fed the images of the macOS goldens and invented ones, and, from the class file of
 * {@code MacGlyphImageTest}, that its sub-pixel check decides through that rule; all on any OS.
 */
public class MacGlyphImageRulesTest {

    private static final String CORETEXT = "com/sun/javafx/font/coretext/";
    private static final String CT_GLYPH = CORETEXT + "CTGlyph";

    /**
     * Helvetica 24 pt 'A' at sub-pixel index 1, half a pixel over, in {@code mac-font-process-golden.txt}
     * ({@code proc.p3.subpixelNative.sub.helvetica.s24.g0024}), captured on macOS 27.0.1: drawn with sub-pixel
     * positioning allowed.
     */
    private static final String GOLDEN_WITH_POSITIONING =
            "399;a7c6d7f85516b7895dcee971a134f2c692b3d9d752fc252059a990bc34e7931b";

    /**
     * The same glyph half a pixel over with sub-pixel positioning disallowed
     * ({@code proc.p2.subpixelOff.sub.helvetica.s24.g0024}, macOS 27.0.1), which is its image at index 0.
     */
    private static final String GOLDEN_WITHOUT_POSITIONING =
            "399;1e9bfb9345ef997f076c3f0e679a7693680b460f9439357f61b91ad65f524136";

    /** Invented: an image unlike either. */
    private static final String INVENTED_OTHER_IMAGE =
            "399;0000000000000000000000000000000000000000000000000000000000000000";

    /**
     * Readers take no lock, so the box is published once, filled and clamped after its advances, by a volatile
     * store; every other store is a fresh empty box, and only {@code checkBounds} and {@code measureBounds} read
     * the field, so a reader reads it once. No run-time test can show a store made too early that no strike call
     * follows, nor a reordering a field that is not volatile allows. {@code MacGlyphImageTest} provokes the store
     * before the fill.
     */
    @Test
    void aGlyphPublishesItsBoundsOnlyOnceTheyAreMeasured() {
        List<FieldModel> bounds = FontClassFiles.classModel(CT_GLYPH, "the glyph rules read the production classes")
                .fields().stream().filter(field -> field.fieldName().equalsString("bounds")).toList();
        assertEquals(1, bounds.size(), "CTGlyph has one field named bounds");
        assertTrue(bounds.getFirst().flags().has(AccessFlag.VOLATILE), "CTGlyph.bounds must be volatile");
        FontCallOrder.assertFieldStoresFollow(CT_GLYPH, "bounds", List.of(CT_GLYPH + " measuredBox",
                CORETEXT + "CGRect <init>"), "a reader finds either no box or one that is filled and clamped");
        FontCallOrder.assertFieldReadOnlyIn(CT_GLYPH, "bounds", List.of("checkBounds", "measureBounds"),
                "each reader reads one box, not parts of two");
    }

    /**
     * The cached context keeps whatever options were set on it last, so each draw into it sets its own strike's
     * options before it draws; a fresh context gets the same options once it is made. This reads the order of the
     * calls and nothing else: in {@code getImage} the first call that gets the cached context comes before the first
     * that sets the options, and that before the first draw; {@code setStrikeOptions} asks the strike whether its
     * glyphs are sub-pixel before it sets sub-pixel positioning; {@code createContext} makes the context before it
     * sets the options. Whether the draw holds the cache's lock until its image is read is decided by
     * {@code MacGlyphImageTest.twoGlyphsDrawnAtOnceEachGetOnlyTheirOwnPixels}, and whether the glyph is drawn with
     * its own strike's options by {@code MacGlyphImageTest}, only on a machine that draws the glyph differently with
     * and without sub-pixel positioning.
     */
    @Test
    void eachGlyphDrawnIntoTheCachedContextSetsItsOwnStrikesOptions() {
        FontCallOrder.assertFirstCallBefore(CT_GLYPH, "getImage", CT_GLYPH + " getCachedContext",
                                            CT_GLYPH + " setStrikeOptions");
        FontCallOrder.assertFirstCallBefore(CT_GLYPH, "getImage", CT_GLYPH + " setStrikeOptions",
                                            CT_GLYPH + " drawImage");
        FontCallOrder.assertFirstCallBefore(CT_GLYPH, "setStrikeOptions", CORETEXT + "CTFontStrike isSubPixelGlyph",
                                            MacFontBindings.OS + " CGContextSetAllowsFontSubpixelPositioning");
        FontCallOrder.assertFirstCallBefore(CT_GLYPH, "createContext", CT_GLYPH + " bitmapContext",
                                            CT_GLYPH + " setStrikeOptions");
    }

    /**
     * A context whose options cannot be set is released before the error leaves {@code createContext}, with no
     * condition before the release: the context is never 0 there.
     */
    @Test
    void aContextWhoseOptionsFailIsReleasedUnconditionally() {
        FontCallOrder.assertHandlerReleasesUnconditionally(CT_GLYPH, "createContext", CT_GLYPH + " setStrikeOptions",
                MacFontBindings.OS + " CGContextRelease", "a context whose options cannot be set is not left without"
                + " an owner, and no condition that cannot be false guards that");
    }

    /** Recorded: the golden's glyph drawn into the cached context with its own options. */
    @Test
    void theGoldenMachinesFiguresProveAGlyphDrawnWithItsOwnOptions() {
        Verdict verdict = MacGlyphImageVerdict.ownStrikeOptions(GOLDEN_WITH_POSITIONING, GOLDEN_WITH_POSITIONING,
                                                                GOLDEN_WITHOUT_POSITIONING);
        assertEquals(Outcome.PROVED, verdict.outcome(), verdict.message());
    }

    /** Recorded: the golden's glyph drawn with the options of the strike that made the context. */
    @Test
    void theOtherStrikesOptionsRefuteIt() {
        Verdict verdict = MacGlyphImageVerdict.ownStrikeOptions(GOLDEN_WITHOUT_POSITIONING, GOLDEN_WITH_POSITIONING,
                                                                GOLDEN_WITHOUT_POSITIONING);
        assertEquals(Outcome.REFUTED, verdict.outcome(), verdict.message());
        assertTrue(verdict.message().contains("the options of the strike that made the context"), verdict.message());
    }

    /** Invented: an image unlike either draw. */
    @Test
    void anImageUnlikeEitherRefutesIt() {
        Verdict verdict = MacGlyphImageVerdict.ownStrikeOptions(INVENTED_OTHER_IMAGE, GOLDEN_WITH_POSITIONING,
                                                                GOLDEN_WITHOUT_POSITIONING);
        assertEquals(Outcome.REFUTED, verdict.outcome(), verdict.message());
        assertTrue(verdict.message().contains("matches neither"), verdict.message());
    }

    /** Invented: a machine that draws the glyph half a pixel over alike with and without positioning. */
    @Test
    void aMachineThatDrawsBothTheSameCannotShowIt() {
        Verdict verdict = MacGlyphImageVerdict.ownStrikeOptions(GOLDEN_WITHOUT_POSITIONING,
                GOLDEN_WITHOUT_POSITIONING, GOLDEN_WITHOUT_POSITIONING);
        assertEquals(Outcome.CANNOT_SHOW, verdict.outcome(), verdict.message());
    }

    /**
     * The sub-pixel check decides through its verdict, which prints its line and, under
     * {@code -Djfx.parity.require=true}, fails where the machine cannot show the defect. An assumption would skip it
     * whatever that property says, so no method of {@code MacGlyphImageTest} makes one.
     */
    @Test
    void theSubpixelCheckDecidesThroughItsVerdictAndAssumesNothing() throws IOException {
        MacFontNullInputVerdictTest.assertDecidesThroughItsVerdictAndAssumesNothing("MacGlyphImageTest",
                "aGlyphIsDrawnWithTheSubpixelOptionsOfItsOwnStrike", "MacGlyphImageVerdict", "ownStrikeOptions");
    }
}
