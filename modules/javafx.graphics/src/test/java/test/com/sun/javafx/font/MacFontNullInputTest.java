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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The inputs that handed NULL or a NULL half to CoreFoundation, CoreGraphics or CoreText in the C of commit
 * {@value MacFontGoldens#CAPTURE_COMMIT}, most of which ended the JVM there (US-067, US-111), with the outcome each
 * has now. Each runs in a child JVM ({@link MacFontNullInputChild}), so that a regression fails its test instead of
 * ending the test fork. The goldens have no row for them, the empty font name aside.
 */
@EnabledOnOs(OS.MAC)
public class MacFontNullInputTest {

    /** The control of the unified-log reading: a fill colour set on NULL through the observer's own binding. */
    private static final String CONTROL_CALL = "CGContextSetRGBFillColor";

    /** The control of the setters: one of them called on NULL through the observer's own binding. */
    private static final String CONTROL_SETTER = "CGContextSetAllowsAntialiasing";

    private static final String SETTER_PREFIX = "CGContextSetAllows";

    private static final Pattern INVALID_CONTEXT = Pattern.compile("(CG\\w+): invalid context 0x0");

    private static final long LOG_WAIT_SECONDS = 30;

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

    /**
     * Outcome: 13, the size {@code PrismFontFactory} gives the other desktops. Its -1 would be asked again and handed
     * to {@code Font} as a size, and 0 would be cached as the default size.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aSystemFontSizeWithoutAUiFontIsThirteen() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.SYSTEM_FONT_SIZE);
        assertEquals("true", values.get("coreText.uiFont"), "CoreText made no system UI font");
        assertEquals(values.get("getSystemFontSize"), values.get("sizeOf.uiFont"),
                     "the size of the system UI font is the system font size");
        assertEquals("13.0", values.get("sizeOf.null"), "a system font size without a UI font is 13");
    }

    /**
     * Outcome: no collection entries, and the two UI fonts are still listed. A null answer would make
     * {@code populateFontFileNameMap} answer false, and the font maps would stay empty for the process.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aFontListWithoutCollectionEntriesStillListsTheTwoUiFonts() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.FONT_DATA);
        assertTrue(Integer.parseInt(values.get("getFontData.triples")) > 2, "getFontData lists "
                + values.get("getFontData.triples") + " fonts, so it cannot hold the collection and the two UI fonts");
        assertEquals("0", values.get("getFontData.uiFonts.nulls"),
                     "the last two triples getFontData writes are the two UI fonts, each with name, family and file");
        assertEquals("0", values.get("matchingDescriptors.null"), "a NULL collection has no descriptors");
        assertEquals("6", values.get("fontData.null.length"), "a NULL descriptor array leaves room for the UI fonts");
        assertEquals(values.get("getFontData.uiFonts"), values.get("fontData.null"),
                     "a NULL descriptor array lists the two UI fonts alone");
        String slots = FontGoldens.joinList(MacFontNullInputChild.SLOTS_BEFORE);
        String index = Integer.toString(MacFontNullInputChild.INDEX_BEFORE);
        assertEquals(index, values.get("addUiFont.null.index"), "a NULL UI font is skipped");
        assertEquals(slots, values.get("addUiFont.null.slots"), "a NULL UI font writes nothing");
        assertEquals(index, values.get("addOwnedDescriptor.null.index"), "a NULL UI font descriptor is skipped");
        assertEquals(slots, values.get("addOwnedDescriptor.null.slots"), "a NULL UI font descriptor writes nothing");
    }

    /**
     * Outcome: font 0 for a NULL collection, descriptor array or name string, so {@code decode} throws its
     * {@code IOException("Failure creating CTFont")}, as it does for a name no font has; releasing font 0 does
     * nothing.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aDfontWithoutACollectionOrANameHasNoFont() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.DFONT);
        assertEquals("true", values.get("coreText.collection"), "CoreText made no font collection");
        assertEquals("true", values.get("coreText.descriptors"), "the decoder matched no descriptors in a collection");
        assertEquals("true", values.get("createCTFont.helvetica"), "the decoder found no Helvetica");
        assertEquals("0", values.get("matchingDescriptors.null"), "a NULL collection has no descriptors");
        assertEquals("0", values.get("fontNamed.nullArray"), "a NULL descriptor array has no font");
        assertEquals("0", values.get("fontNamed.nullName"), "a NULL name string has no font");
        assertEquals("returned", values.get("releaseCTFont.zero"), "releasing font 0 does nothing");
    }

    /**
     * Outcome: an empty list, as when CoreText gives no list, so the font gets the hard-wired fallbacks alone.
     * CoreText would answer a list for NULL codes, but this site hands no NULL on (story US-111, criterion 1).
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void noLanguageCodesGiveAnEmptyCascadeList() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.CODES);
        assertEquals("true", values.get("coreText.font"), "CoreText made no Helvetica");
        assertTrue(Integer.parseInt(values.get("getCascadeList")) > 0, "Helvetica has no cascade list");
        assertEquals("0", values.get("cascadeList.nullCodes"), "no language codes give an empty cascade list");
    }

    /**
     * Outcome: no copy, font 0, for a UI font CoreText did not make, which every caller of {@code getFontRef} takes
     * as no font. That a UI font which is copied is released, {@code MacFontStressTest} measures.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aUiFontCoreTextDidNotMakeHasNoCopy() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.UI_FONT_COPY);
        assertEquals("true", values.get("coreText.uiFont"), "CoreText made no system UI font");
        assertEquals("true", values.get("copyOfUIFont.uiFont"), "the system UI font has no copy");
        assertEquals("0", values.get("copyOfUIFont.null"), "a UI font CoreText did not make has no copy");
    }

    /**
     * Outcome: font 0 for name 0. The empty name has no first character, so it takes the path of any other name and
     * gets Helvetica, as the golden rows {@code ct.font.name.n10.*} recorded from the C.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aZeroFontNameHasNoFontAndTheEmptyNameIsHelvetica() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.FONT_NAME);
        assertEquals("true", values.get("coreText.emptyName"), "CoreFoundation made no empty string");
        assertEquals("0", values.get("name.zero"), "font name 0 has no font");
        assertEquals("Helvetica", values.get("name.empty"), "the empty font name is Helvetica");
    }

    /**
     * Outcome: advance 0 for a strike without a CTFont, the advance {@code CTGlyph} leaves its glyphs and CoreText's
     * answer for NULL. The {@code hmtx} advance of the base class would be a behaviour change.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aStrikeWithoutACtFontHasAdvanceZero() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.ADVANCE);
        assertEquals("0", values.get("cgFont"), "CoreGraphics accepted the font without a glyf table, so this input"
                + " no longer reaches a strike without a CTFont");
        assertEquals("0", values.get("strike.fontRef"), "the strike of a font CoreGraphics rejected has a CTFont");
        assertNotEquals("0.0", values.get("hmtx"),
                        "the glyph's hmtx advance is 0, so it cannot tell the two outcomes apart");
        assertEquals("0.0", values.get("advance"), "a strike without a CTFont has advance 0");
    }

    /**
     * Outcome: a bitmap context CoreGraphics did not make is 0 at once, and nothing is set on it. CoreGraphics
     * survives a call on a NULL context and reports it only in the unified log, one line per call, so the child's
     * lines are read there. After the subject the child makes two control calls on NULL: one setter, so the only
     * setter line may be that control's, and last the fill colour, which shows that its lines can be read.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aContextCoreGraphicsDidNotMakeIsZeroAndNothingIsSetOnIt() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.CONTEXT);
        assertEquals("0", values.get("coreGraphics.context"), "CoreGraphics now makes a bitmap context of width 0,"
                + " so this input no longer reaches a NULL context and the test needs another width");
        assertEquals("true", values.get("createContext.made"), "createContext made no 3 x 3 context");
        assertEquals("0", values.get("createContext"), "a context CoreGraphics did not make is 0");
        String pid = values.get("pid");
        Map<String, Integer> calls = invalidContextCalls(pid);
        Map<String, Integer> setters = new TreeMap<>(calls);
        setters.keySet().removeIf(function -> !function.startsWith(SETTER_PREFIX));
        Map<String, Integer> beyondTheControl = new TreeMap<>(setters);
        beyondTheControl.computeIfPresent(CONTROL_SETTER, (function, count) -> count == 1 ? null : count - 1);
        assertEquals(Map.of(), beyondTheControl, "nothing is set on a context CoreGraphics did not make; the setter"
                + " lines are " + setters + ", one " + CONTROL_SETTER + " line of them the control's");
        assumeTrue(calls.containsKey(CONTROL_CALL), "the unified log shows no '" + CONTROL_CALL + ": invalid context'"
                + " line of process " + pid + " within " + LOG_WAIT_SECONDS + " s, so this account cannot read it"
                + " and no call on a NULL context can be seen; lines found: " + calls);
        assumeTrue(setters.containsKey(CONTROL_SETTER), "the unified log shows the '" + CONTROL_CALL + "' line of"
                + " process " + pid + " but no '" + CONTROL_SETTER + ": invalid context' line, so the setters no"
                + " longer log a call on NULL and none made on that context can be seen; lines found: " + calls);
    }

    /**
     * Outcome: a {@code CGRect} mirror with a null {@code origin} or {@code size} throws a
     * {@code NullPointerException} in {@code OS.rect}, before any native allocation or downcall, and nothing is
     * drawn. The C passed an uninitialised half on.
     */
    @Test
    @Tag("crash-prone")
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void aRectWithoutAnOriginOrASizeThrowsBeforeAnyDowncall() throws Exception {
        Map<String, String> values = run(MacFontNullInputChild.RECT_HALF);
        assertEquals("changed", values.get("fill.control.bitmap"), "a fill of the whole bitmap does not show in it");
        String inRect = "java.lang.NullPointerException@com.sun.javafx.font.coretext.OS.rect";
        for (String half : List.of("origin", "size")) {
            assertEquals(inRect, values.get("transform." + half), "CGRectApplyAffineTransform with a null " + half);
            assertEquals(inRect, values.get("fill." + half), "CGContextFillRect with a null " + half);
            assertEquals("unchanged", values.get("fill." + half + ".bitmap"),
                         "CGContextFillRect with a null " + half + " draws nothing");
        }
    }

    private static Map<String, String> run(String scenario) throws Exception {
        return MacFontGoldens.runChild(MacFontNullInputChild.class, scenario, Map.of());
    }

    /**
     * The "invalid context" lines CoreGraphics wrote to the unified log for process {@code pid}, counted by function,
     * read until both controls' lines show or {@link #LOG_WAIT_SECONDS} pass. {@code log show} records its own
     * arguments, so the predicate always names the pid and only lines that name a function count.
     */
    private static Map<String, Integer> invalidContextCalls(String pid) throws InterruptedException {
        List<String> command = List.of("/usr/bin/log", "show", "--last", "2m", "--predicate",
                "processIdentifier == " + Long.parseLong(pid) + " AND eventMessage CONTAINS \"invalid context\"");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOG_WAIT_SECONDS);
        while (true) {
            Map<String, Integer> calls = new TreeMap<>();
            String log = FontGoldenRuntime.run(command, true);
            if (log != null) {
                Matcher matcher = INVALID_CONTEXT.matcher(log);
                while (matcher.find()) {
                    calls.merge(matcher.group(1), 1, Integer::sum);
                }
            }
            boolean controlsShown = calls.containsKey(CONTROL_CALL) && calls.containsKey(CONTROL_SETTER);
            if (controlsShown || System.nanoTime() - deadline > 0) {
                return calls;
            }
            Thread.sleep(500);
        }
    }
}
