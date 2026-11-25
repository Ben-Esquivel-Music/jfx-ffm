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
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.PGFont;
import com.sun.javafx.scene.text.GlyphList;
import com.sun.javafx.text.PrismTextLayout;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A run that DirectWrite draws with a fallback font the composite has no slot for is laid out with the missing glyph,
 * code 0, and not with the fallback font's glyph IDs in the primary's slot 0, which drew other glyphs of the primary.
 * The composite answers "no slot" for that font, where before US-111 it answered slot 0, and the DirectWrite layout
 * takes a fallback run's glyph IDs only for a slot it is given: without one, {@code DWGlyphLayout.renderShape} and
 * {@code DWTextRendererObject.textRendererGetGlyphIndices} each leave them 0; it still takes the run's advances.
 * <p>
 * The text is the CJK run of {@link DirectWriteMetricsGoldenTest#TEXT} after that string's "e" with a combining acute
 * accent: the combining mark makes the layout complex, which sends the CJK run through DirectWrite, while CJK text on
 * its own is laid out from the composite's glyph mapper. Arial, the primary, has no CJK glyph, so DirectWrite draws
 * the run with a fallback font of its choosing. The precondition is that this font is one the composite has to add. A
 * control on a first composite shows it: the run's glyphs take the slot after the composite's last, which then holds
 * the added font. Where this host's DirectWrite draws the run with a font the composite already holds, or with no
 * fallback font, the test is skipped, or failed under {@code -Djfx.parity.require=true}. A second composite is first
 * filled, through {@code addSlotFont}, with fonts that are not on this machine, until its fallback list is at its 0x7E
 * cap and the composite refuses another font. Each composite is its own, over Arial's file, so that no composite the
 * process caches is touched.
 */
@EnabledOnOs(OS.WINDOWS)
public class DirectWriteNoSlotLayoutTest {

    private static final String CJK = "\u65E5\u672C\u8A9E";

    private static final String TEXT = "e\u0301 " + CJK;

    private static final float SIZE = 12f;

    /** The slots of a composite whose fallback list is at the 0x7E cap: those slots and the primary's. */
    private static final int FULL = 0x7F;

    private static final int GLYPH_ID = 0x00FFFFFF;

    @Test
    void aRunWhoseFallbackFontGetsNoSlotIsLaidOutWithTheMissingGlyph() {
        CompositeFontResource control = newComposite();
        int slots = control.getNumSlots();
        int[] codes = glyphCodes(cjkRun(control));
        ParityGate.requireOracle(DirectWriteNoSlotLayoutTest.class,
                Arrays.stream(codes).allMatch(code -> code >>> 24 >= slots), ParityGate.required(),
                () -> "DirectWrite lays the CJK run out with the glyph codes " + Arrays.toString(codes) + " in a"
                        + " composite of " + slots + " slots, so not with a font the composite has to add");
        for (int code : codes) {
            assertEquals(slots, code >>> 24, "the slot of a glyph of the font added to a composite of " + slots
                    + " slots");
            assertNotEquals(0, code & GLYPH_ID, "the glyph ID of a glyph of the font the composite added");
        }
        FontResource added = control.getSlotResource(slots);
        assertNotNull(added, "the font at slot " + slots);
        assertEquals(slots, control.getSlotForFont(added.getFullName()), "the slot a later lookup of "
                + added.getFullName() + " finds");

        CompositeFontResource subject = newComposite();
        int refused = PrismFontFactoryTest.fill(subject).slot();
        int filled = subject.getNumSlots();
        GlyphList run = cjkRun(subject);
        for (int code : glyphCodes(run)) {
            assertEquals(0, code, "a glyph code of the CJK run in a composite with no slot left for its font");
        }
        assertTrue(run.getWidth() > 0, "the width of the CJK run in a composite with no slot left for its font");
        assertEquals(filled, subject.getNumSlots(), "the slots of the composite after the layout");
        assertEquals(FULL, filled, "the slots of a composite whose fallback list is at its cap");
        assertEquals(-1, refused, "what a composite with no slot left answers for another font");
    }

    private static CompositeFontResource newComposite() {
        String arial = FontGoldens.systemFontDirectory().resolve("arial.ttf").toString();
        FontResource primary = FontResourceShim.newUncachedFontFile(arial);
        assertNotNull(primary, "the factory opens " + arial);
        return FontResourceShim.newComposite(primary);
    }

    /** The CJK run, laid out as {@code DirectWriteMetricsGoldenTest} lays out its text. */
    private static GlyphList cjkRun(CompositeFontResource composite) {
        PGFont font = FontResourceShim.newPrismFont(composite, composite.getFullName(), SIZE);
        PrismTextLayout layout = new PrismTextLayout(0);
        layout.setContent(TEXT, font);
        for (GlyphList run : layout.getRuns()) {
            if (run.getStart() == TEXT.indexOf(CJK)) {
                assertTrue(run.isComplex(), "the CJK run is laid out by DirectWrite");
                assertEquals(CJK.length(), run.getGlyphCount(), "the glyphs of the CJK run");
                return run;
            }
        }
        return fail("no run of the layout of " + TEXT + " starts at its CJK characters");
    }

    private static int[] glyphCodes(GlyphList run) {
        int[] codes = new int[run.getGlyphCount()];
        for (int g = 0; g < codes.length; g++) {
            codes[g] = run.getGlyphCode(g);
        }
        return codes;
    }
}
