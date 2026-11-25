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
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CTGlyphShim;
import com.sun.javafx.font.coretext.OSShim;
import com.sun.javafx.geom.transform.BaseTransform;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code CTGlyph} draws and measures: a glyph image holds its own glyph's pixels while another thread draws, a
 * reader gets a glyph's bounds and advances only once they are measured in full, a glyph whose measurement throws
 * throws once and is empty afterwards, a glyph is drawn into the cached context with its own strike's options, and
 * a colour space CoreGraphics did not make gives no context. Whatever draws or measures runs in a child JVM
 * ({@link MacGlyphImageChild}), since the cached context lives as long as its JVM and {@code MacCoreTextGoldenTest}
 * needs it empty in this one. The thread scenarios hold a thread at a strike hook and decide from the other thread's
 * state, never from timing.
 * <p>
 * One check needs a machine that draws a glyph half a pixel over differently with and without sub-pixel positioning;
 * {@link MacGlyphImageVerdict} decides it and it prints its verdict with the images on one line of the test JVM's
 * standard error. Where the machine cannot show the difference it is skipped, or fails under
 * {@code -Djfx.parity.require=true}, and {@code MacGlyphImageRulesTest} still pins in the class file that each draw
 * into the cached context sets its own strike's options.
 */
@EnabledOnOs(OS.MAC)
public class MacGlyphImageTest {

    /** {@code kCGImageAlphaOnly}: the one bitmap CoreGraphics makes without a colour space. */
    private static final int ALPHA_ONLY = 7;

    /**
     * Every glyph that fits draws into the one cached bitmap, so no other draw may start until an image is read. The
     * first thread draws 'W' and is held between its draw and its read until the second thread, drawing 'o', has
     * blocked in {@code CTGlyph.getImage} or has finished. The hold is the strike's anti-aliasing query that
     * {@code drawImage} makes at the read, after {@code CTFontDrawGlyphs}; the threads draw at sub-pixel index 0, for
     * which {@code CTFontStrike.getSubPixelPosition} answers without asking for the anti-aliasing mode, so the hold
     * is reached only at the read. Without the lock the second fills and draws 'o' over part of the bitmap and
     * finishes, and the first reads 'W' with 'o' drawn over it. Each thread's image is compared with its glyph drawn
     * alone a second time, into the cached context after the other glyph was drawn into it, as each thread's draw
     * finds it: whether CoreGraphics draws into a context it has drawn into before exactly as into a new one is the
     * host's affair, not the lock's.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void twoGlyphsDrawnAtOnceEachGetOnlyTheirOwnPixels() throws Exception {
        Map<String, String> values = run(MacGlyphImageChild.CACHED_DRAW);
        assertEquals("0", values.get("cached.before"), "a context was cached before the glyphs were drawn");
        String first = values.get("serial.first");
        String second = values.get("serial.second");
        assertTrue(!first.equals(FontGoldens.NULL) && !first.startsWith("0;"),
                   "'W' drawn alone has no image: " + first);
        assertTrue(!second.equals(FontGoldens.NULL) && !second.startsWith("0;"),
                   "'o' drawn alone has no image: " + second);
        assertNotEquals(first, second, "'W' and 'o' drawn alone give the same image, so no image can show a mix");
        assertFits(values.get("box.first"));
        assertFits(values.get("box.second"));
        assertEquals("true", values.get("cached.serial"), "the glyphs drawn alone made no cached context");
        assertEquals("true", values.get("firstAtRead"), "the first thread never reached the read of its image: the"
                + " hold needs drawImage to ask isLCDGlyph between the draw and the read");
        assertEquals(MacGlyphImageChild.SECOND_BLOCKED, values.get("hold"), "the second thread drew into the cached"
                + " bitmap while the first was between drawing its glyph and reading it: " + values.get("hold"));
        assertEquals("true", values.get("finished"), "a thread did not return from its draw");
        assertEquals(FontGoldens.NULL, values.get("first.failure"), "the first thread's draw");
        assertEquals(FontGoldens.NULL, values.get("second.failure"), "the second thread's draw");
        assertEquals(values.get("again.first"), values.get("first.image"),
                     "the image of 'W' drawn beside another thread, against 'W' drawn alone the second time");
        assertEquals(values.get("again.second"), values.get("second.image"),
                     "the image of 'o' drawn beside another thread, against 'o' drawn alone the second time");
    }

    /**
     * A reader finds a glyph's bounds only once they are measured, filled and clamped, after its advances. The
     * first thread reads the advance of 'W' and is held while it measures, after the advances and before the box,
     * where the measurement asks the strike for its font file, until the second thread, reading all six values, has
     * blocked in {@code CTGlyph} or has finished. Without the fix the second finds a box that is not filled.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aGlyphReadWhileItsBoundsAreMeasuredGetsThemInFull() throws Exception {
        Map<String, String> values = run(MacGlyphImageChild.BOUNDS_RACE);
        String[] reference = values.get("reference").split(";");
        assertTrue(Float.parseFloat(reference[0]) > 0, "'W' of the reference strike has no advance");
        assertTrue(Integer.parseInt(reference[2]) > 0, "'W' of the reference strike has no width");
        assertEquals("true", values.get("firstMeasuring"), "the first thread never measured the glyph: the hold needs"
                + " measuredBox to ask the strike for its font file while it measures");
        assertEquals(MacGlyphImageChild.SECOND_BLOCKED, values.get("hold"), "a reader found the glyph's bounds while"
                + " they were measured: " + values.get("hold") + ", it read " + values.get("second.values"));
        assertEquals("true", values.get("finished"), "a thread did not return from its read");
        assertEquals(FontGoldens.NULL, values.get("first.failure"), "the first thread's read");
        assertEquals(FontGoldens.NULL, values.get("second.failure"), "the second thread's read");
        assertEquals(values.get("reference"), values.get("second.values"),
                     "the advances and bounds the second thread read, against those of 'W' read alone");
        assertEquals(reference[0], values.get("first.advance"), "the advance the first thread read");
    }

    /**
     * A glyph whose measurement throws throws once: its first reader gets the exception, and every reader after it
     * gets an empty box instead of measuring the glyph again. The strike's font-file query throws, which the
     * measurement makes after the advances and before the box.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aGlyphWhoseMeasurementFailsThrowsOnceAndIsEmptyAfterwards() throws Exception {
        Map<String, String> values = run(MacGlyphImageChild.MEASUREMENT_FAILS);
        assertEquals(MacGlyphImageChild.MEASUREMENT_FAILURE, values.get("first.failure"),
                     "what the first reader of a glyph whose measurement throws gets");
        assertEquals("0x0;0;0", values.get("after"), "the box of the glyph read after its measurement threw:"
                + " a glyph measured again at every read would answer its full box");
    }

    /**
     * The cached context is made by a glyph of a strike without sub-pixel positions, as a scaled or rotated strike's
     * are; then 'A' of a strike with them is drawn into it half a pixel over. It must equal 'A' drawn the same way
     * into a context of its own strike's options, not into one of the other strike's. Each of the three is its
     * context's second draw: the other strike's 'A' is drawn first, at sub-pixel index 0, into each context the child
     * makes, as it is into the cached context. The child runs with sub-pixel text on and LCD text off, whatever this
     * JVM was given. {@code CTFontDrawGlyphs} sets a context's size and text matrix where the CTFont specifies them;
     * both strikes here are identity strikes of one font.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aGlyphIsDrawnWithTheSubpixelOptionsOfItsOwnStrike(TestInfo test) throws Exception {
        Map<String, String> values = MacFontGoldens.runChild(MacGlyphImageChild.class,
                MacGlyphImageChild.OWN_SETTINGS, Map.of("prism.subpixeltext", "on", "prism.lcdtext", "false"));
        assertEquals("true", values.get("subPixelText"), "the child ran without sub-pixel text");
        assertEquals("true", values.get("contexts.made"), "CoreGraphics made no context to draw the glyph into");
        assertEquals("0", values.get("cached.before"), "a context was cached before the other strike drew");
        assertEquals("true", values.get("cached.made"), "the other strike's glyph made no cached context");
        Verdict verdict = MacGlyphImageVerdict.ownStrikeOptions(values.get("subject"), values.get("own"),
                                                                values.get("other"));
        MacFontReleaseVerdict.decide(MacGlyphImageTest.class, test.getTestMethod().orElseThrow().getName(), verdict);
    }

    /**
     * {@code CTGlyph} makes its one cached context in the colour space of the first glyph that draws, LCD where LCD
     * text is on, and every glyph of the process draws into it. That is right only while no strike is LCD where LCD
     * text is off. Nothing is drawn.
     */
    @Test
    void aStrikeIsLcdOnlyWhereLcdTextIsOn() throws Exception {
        FontResource resource = FontResourceShim.newUncachedFontFile(MacFontGoldens.pinned("helvetica").toString(), 0);
        FontStrike strike = resource.getStrike(12f, BaseTransform.IDENTITY_TRANSFORM, FontResource.AA_LCD);
        boolean lcdText = PrismFontFactory.getFontFactory().isLCDTextSupported();
        assertEquals(lcdText ? FontResource.AA_LCD : FontResource.AA_GREYSCALE, strike.getAAMode(),
                     "CTGlyph makes its one cached context in the colour space LCD text selects, so a strike may be"
                     + " LCD only where LCD text is on");
        assertEquals(strike.getAAMode() == FontResource.AA_LCD, strike.getGlyph('A').isLCDGlyph(),
                     "a glyph is LCD exactly when its strike is");
    }

    /**
     * Outcome: no context, and CoreGraphics is not asked for one. The control shows that CoreGraphics makes an
     * alpha-only bitmap without a colour space, the one legal case, so with the same flags an unguarded call would
     * make one. Nothing is drawn.
     */
    @Test
    void aColourSpaceCoreGraphicsDidNotMakeGivesNoContext() throws Exception {
        OSShim.ensureLoaded();
        long control = OSShim.CGBitmapContextCreate(0, 3, 3, 8, 3, 0, ALPHA_ONLY);
        assertNotEquals(0L, control, "CoreGraphics makes an alpha-only context without a colour space, the one legal"
                + " NULL");
        OSShim.CGContextRelease(control);
        long subject = CTGlyphShim.bitmapContext(0, 3, 3, 3, ALPHA_ONLY);
        if (subject != 0) {
            OSShim.CGContextRelease(subject);
        }
        assertEquals(0L, subject, "a colour space CoreGraphics did not make gives no context, and CoreGraphics is not"
                + " asked for one");
    }

    private static void assertFits(String box) {
        String[] size = box.split("x");
        assertTrue(Integer.parseInt(size[0]) <= 256 && Integer.parseInt(size[1]) <= 256,
                   "the glyph of box " + box + " does not fit the cached context");
    }

    private static Map<String, String> run(String scenario) throws Exception {
        return MacFontGoldens.runChild(MacGlyphImageChild.class, scenario, Map.of());
    }
}
