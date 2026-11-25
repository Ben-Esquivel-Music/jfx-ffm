/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package test.com.sun.javafx.font;

import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CFNativeShim;
import com.sun.javafx.font.coretext.CGNativeShim;
import com.sun.javafx.font.coretext.CTNativeShim;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import test.com.sun.javafx.font.CompositeFontLocksTest.SlotFont;
import test.com.sun.javafx.test.ParityGate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 *
 */
public class PrismFontFactoryTest {

    private static final String COMPOSITE = "com/sun/javafx/font/PrismCompositeFontResource";

    /** Invented: no font and no face has this name. */
    private static final String NO_SUCH_FONT = "US-111 No Such Font";

    /** The slot count of a fallback list at which it refuses another font. */
    private static final int FALLBACK_SLOT_CAP = 0x7E;

    public PrismFontFactoryTest() {
    }

    /**
     * Test of getFontFactory method.
     */
    @Test
    public void testGetFontFactory() {
        PrismFontFactory expResult = null;
        PrismFontFactory result = PrismFontFactory.getFontFactory();
        assertNotNull(result, "Should never turn null");
        expResult = PrismFontFactory.getFontFactory();
        assertEquals(expResult, result, "Creates different instance of FontFactory");
    }

    /**
     * Reading a registry initialises its facade, so this cannot show that the factory linked them:
     * {@code MacFontBindingStructureTest} pins the {@code CTNative.link()} call from the class file.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    public void theCoreTextRegistriesAreCompleteBesideAFontFactory() {
        assertNotNull(PrismFontFactory.getFontFactory());
        assertEquals(20, CFNativeShim.boundFunctions().size(), "CoreFoundation");
        assertEquals(22, CGNativeShim.boundFunctions().size(), "CoreGraphics");
        assertEquals(33, CTNativeShim.boundFunctions().size(), "CoreText");
    }

    /**
     * A composite numbers the slots of its fallback list one above the list's own, and a font that list has no slot
     * for has none in the composite either. The mapping alone, so on any OS.
     */
    @Test
    public void aCompositeSlotIsOneAboveItsFallbackSlotAndNoSlotStaysNone() {
        assertEquals(-1, FontResourceShim.compositeSlotOf(-1), "a font the fallback list has no slot for");
        assertEquals(1, FontResourceShim.compositeSlotOf(0), "the fallback list's first slot");
        assertEquals(FALLBACK_SLOT_CAP, FontResourceShim.compositeSlotOf(FALLBACK_SLOT_CAP - 1),
                     "the fallback list's last slot");
    }

    /**
     * A composite answers through that mapping when it looks a font up and when it adds one, whatever platform's
     * layout asks it. From the class file, so on any OS.
     */
    @Test
    public void aCompositeAnswersItsFallbackListsSlotsThroughTheMapping() {
        for (String method : List.of("getSlotForFont", "addSlotFont")) {
            FontCallOrder.assertReturnsFirstCallOf(COMPOSITE, method, COMPOSITE + " slotAfterThePrimary",
                    "a composite hands out the slot above its fallback list's, and none where that list has none");
        }
    }

    /**
     * A composite has slot 0 for its primary and none for a font its fallback list cannot hold. macOS, Windows and
     * Linux.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.WINDOWS, OS.LINUX})
    public void aCompositeHasNoSlotForAFontItsFallbacksCannotHold() {
        FontResource ahem = fontFile("ahem");
        CompositeFontResource composite = FontResourceShim.newComposite(ahem);
        assertEquals(0, composite.getSlotForFont(ahem.getFullName()), "the primary's own name");
        assertEquals(-1, composite.getSlotForFont(NO_SUCH_FONT), "a name no font has");
    }

    /**
     * A font a composite adds takes its next slot, which holds the font, and a later lookup finds it there. macOS,
     * Windows and Linux: the composite's fallback list, and a lookup of the added font's name, reach the platform's
     * fonts.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.WINDOWS, OS.LINUX})
    public void aFontAddedToACompositeIsInTheSlotItIsGiven() {
        CompositeFontResource composite = FontResourceShim.newComposite(fontFile("ahem"));
        FontResource added = fontFile("fontwithfeaturesttf");
        int slots = composite.getNumSlots();
        ParityGate.requireOracle(PrismFontFactoryTest.class, slots - 1 < FALLBACK_SLOT_CAP, ParityGate.required(),
                () -> "this host gives the composite " + slots + " slots, so its fallback list has no room for one");
        int slot = composite.addSlotFont(added);
        assertEquals(slots, slot, "a font added to a composite of " + slots + " slots takes slot " + slots);
        assertEquals(slots + 1, composite.getNumSlots(), "the slots of the composite once the font is added");
        assertEquals(added.getFullName(), composite.getSlotResource(slot).getFullName(),
                     "the font at the slot it was given");
        assertEquals(slot, composite.getSlotForFont(added.getFullName()), "the slot a later lookup finds");
    }

    /**
     * A composite whose fallback list is full has no slot for another font, and says so with -1, not with the
     * primary's slot 0: adding the font answers -1, a lookup of its name answers -1 too, and neither takes a slot.
     * The list is filled with fonts that are not on the machine, each at the slot it is given, until the composite
     * refuses one. macOS, Windows and Linux: the composite's fallback list, and a lookup of each added font's name,
     * reach the platform's fonts.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.WINDOWS, OS.LINUX})
    public void aCompositeWhoseFallbackListIsFullHasNoSlotForAnotherFont() {
        CompositeFontResource composite = FontResourceShim.newComposite(fontFile("ahem"));
        int slots = composite.getNumSlots();
        ParityGate.requireOracle(PrismFontFactoryTest.class, slots - 1 < FALLBACK_SLOT_CAP, ParityGate.required(),
                () -> "this host gives the composite " + slots + " slots, so its fallback list has no room for one");
        Refusal refused = fill(composite);
        assertEquals(-1, refused.slot(), "what a composite whose fallback list is full answers for another font");
        assertEquals(FALLBACK_SLOT_CAP + 1, composite.getNumSlots(),
                     "the slots of a composite whose fallback list is full: the list's 0x7E and the primary's");
        assertEquals(-1, composite.getSlotForFont(refused.font().getFullName()), "a lookup of the font it refused");
        assertEquals(refused.slotsBefore(), composite.getNumSlots(),
                     "the slots of the composite, unchanged by the font it refused and by the lookup of that font");
    }

    /**
     * findFontIndex throws what making a font file of the file throws, so that a file that could not be read is told
     * from one without the face. Through a factory of no platform, which makes no font file, so on any OS.
     */
    @Test
    public void findFontIndexThrowsWhatMakingTheFontFileThrows() {
        PrismFontFactory factory = FontResourceShim.newMapOnlyFactory();
        assertThrows(UnsupportedOperationException.class,
                     () -> factory.findFontIndex(NO_SUCH_FONT, NO_SUCH_FONT + ".ttf"));
    }

    /** findFontIndex throws for a file that does not exist and for one that is no font. macOS: CoreText's files. */
    @Test
    @EnabledOnOs(OS.MAC)
    public void findFontIndexThrowsWhatReadingTheFileThrows(@TempDir Path directory) throws Exception {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        String missing = directory.resolve("missing.ttf").toString();
        assertThrows(FileNotFoundException.class, () -> factory.findFontIndex(NO_SUCH_FONT, missing));
        String garbage = Files.writeString(directory.resolve("garbage.ttf"), "US-111 no font").toString();
        assertThrows(Exception.class, () -> factory.findFontIndex(NO_SUCH_FONT, garbage), "a file that is no font");
    }

    /**
     * findFontIndex answers -1 for a readable file none of whose faces has the name, and 0 for a file of one face,
     * whatever the name. macOS: CoreText's files.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    public void findFontIndexAnswersForAReadableFile() throws Exception {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        assertEquals(-1, factory.findFontIndex(NO_SUCH_FONT, MacFontGoldens.pinned("helvetica").toString()),
                     "Helvetica.ttc, whose faces have other names");
        assertEquals(0, factory.findFontIndex(NO_SUCH_FONT, MacFontGoldens.pinned("ahem").toString()),
                     "Ahem, a file of one face");
    }

    private static FontResource fontFile(String pinned) {
        FontResource font = FontResourceShim.newUncachedFontFile(MacFontGoldens.pinned(pinned).toString());
        assertNotNull(font, "the factory opens " + MacFontGoldens.pinned(pinned));
        return font;
    }

    /**
     * Adds fonts that are not on this machine until the composite no longer gives the next slot, and returns that
     * font, the composite's slot count before it was offered and what the composite answered for it. The factory
     * knows no font of these names, so each one added takes the slot it is given. The Windows layout test of a run
     * whose fallback font gets no slot fills its composite with it too.
     */
    static Refusal fill(CompositeFontResource composite) {
        for (int next = composite.getNumSlots(); next <= 0xFF; next++) {
            SlotFont font = new SlotFont("JFX No Slot Test " + next);
            int slots = composite.getNumSlots();
            int slot = composite.addSlotFont(font);
            if (slot != next) {
                return new Refusal(font, slots, slot);
            }
            assertSame(font, composite.getSlotResource(slot), "the font at the slot it was given");
        }
        return fail("a composite gave a font slot 0xFF");
    }

    /** The font a composite did not give the next slot, its slot count before that font and its answer for it. */
    record Refusal(SlotFont font, int slotsBefore, int slot) {
    }
}
