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

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The inputs that handed NULL to CoreFoundation, CoreGraphics or CoreText in the C of commit
 * {@value MacFontGoldens#CAPTURE_COMMIT} and ended the JVM there (US-067), with the outcome each has now. Each runs
 * in a child JVM ({@link MacFontNullInputChild}), so that a regression fails its test instead of ending the test
 * fork. The goldens have no row for any of them, because the C crashed.
 */
@EnabledOnOs(OS.MAC)
public class MacFontNullInputTest {

    /** Outcome: no fallback slot, so the run's glyphs go to slot 0, as for any fallback that cannot be added. */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aRunFontCoreTextDidNotCopyTakesNoFallbackSlot() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.FALLBACK_COPY);
        assertEquals("-1", values.get("slot"), "a run font CoreText did not copy takes no fallback slot");
        assertEquals("-1", values.get("slot.noFactory"),
                     "a zero copy must be refused before the factory is asked for a font file");
    }

    /** Outcome: no name; {@code PrismFontFactory} then falls back to Lucida Grande for the system font. */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aUiFontTypeWithoutAFontHasNoName() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.UI_FONT);
        assertEquals("0", values.get("coreText.font"), "CoreText now makes a font for UI font type "
                + MacFontNullInputChild.UI_FONT_TYPE_WITHOUT_A_FONT + ", so this input no longer reaches a NULL font"
                + " and the test needs another type");
        assertEquals(FontGoldens.NULL, values.get("getFont"), "a UI font type without a font has no name");
    }

    /** Outcome: no name. No installed font lacks a family name, so the NULL goes in where the name is read. */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFontWithoutAFamilyNameHasNoName() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.FAMILY_NAME);
        assertEquals(FontGoldens.NULL, values.get("familyName"), "a font without a family name has no name");
    }

    /** Outcome: an empty list. */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aZeroFontHasAnEmptyCascadeList() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.CASCADE_FONT);
        assertEquals("0", values.get("entries"), "a zero font has an empty cascade list");
    }

    /** Outcome: no cascade entry is added, so the font gets the hard-wired fallbacks alone, as any font gets them. */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void anEmbeddedFontCoreGraphicsRejectsKeepsTheHardWiredFallbacks() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.CASCADE_EMBEDDED);
        assertEquals("0", values.get("cgFont"), "CoreGraphics accepted the font without a glyf table, so this input"
                + " no longer reaches a zero font in the cascade lookup");
        assertEquals("Apple Symbols|Apple Color Emoji|Arial Unicode MS|Heiti SC Light", values.get("fallbacks"),
                     "a font CoreGraphics rejected has the hard-wired fallbacks and no other");
    }

    /**
     * Outcome: a null name, which the cascade lookup skips. No cascade list has such an entry, so a descriptor
     * without a display name goes in where the list's entries are read.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aCascadeEntryWithoutADisplayNameHasANullName() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.CASCADE_NAME);
        assertEquals("false", values.get("coreText.displayName"), "CoreText now gives a descriptor of an unknown"
                + " font a display name, so this input no longer reaches a NULL name");
        assertEquals(FontGoldens.NULL, values.get("name"), "an entry without a display name has a null name");
        assertEquals(FontGoldens.NULL, values.get("file"), "an entry without a URL has a null file");
    }

    /**
     * Outcome: format 0, as for any format that is not sfnt, so the decoder refuses the font. No installed font
     * lacks the attribute, so the NULL goes in where the attribute is read.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFontWithoutAFormatAttributeHasFormatZero() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.FORMAT);
        assertEquals("0", values.get("format"), "a font without a format attribute has format 0");
    }

    /** Outcome: no table; the decoder then refuses the font as it does when CoreText lists no tables. */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aTableTheFontLacksIsNull() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.TABLE);
        assertEquals("0", values.get("coreText.table"), "CoreText now makes a table for a tag Helvetica lacks, so"
                + " this input no longer reaches a NULL table and the test needs another tag");
        assertEquals(FontGoldens.NULL, values.get("table"), "a table the font lacks is null");
    }

    /**
     * Outcome: a {@code NullPointerException} thrown where {@code OS} reads the mirror, which is before any
     * downcall; the context is usable afterwards. The C crashed here.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aNullMirrorThrowsBeforeAnyDowncall() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.MIRROR);
        String inRect = "java.lang.NullPointerException@com.sun.javafx.font.coretext.OS.rect";
        String inTransform = "java.lang.NullPointerException@com.sun.javafx.font.coretext.OS.transform";
        assertEquals(inRect, values.get("rect.null"), "CGRectApplyAffineTransform with a null rect");
        assertEquals(inTransform, values.get("transform.null"), "CGRectApplyAffineTransform with a null transform");
        assertEquals(inRect, values.get("fill.null"), "CGContextFillRect with a null rect");
        assertEquals("64", values.get("fill.after"), "the context still fills and reads after the null rect");
    }

    private static Map<String, String> run(String scenario) throws Exception {
        return MacFontGoldens.runChild(MacFontNullInputChild.class, scenario, Map.of());
    }
}
