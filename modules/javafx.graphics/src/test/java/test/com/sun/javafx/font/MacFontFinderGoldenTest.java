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

import com.sun.javafx.font.MacFontFinderShim;
import com.sun.javafx.font.coretext.OSShim;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity oracle for {@code MacFontFinder}, against {@value #GOLDEN}, captured from the JNI build of commit
 * {@value MacFontGoldens#CAPTURE_COMMIT}. The order of {@code getFontData} is recorded because it fixes the order of
 * {@code familyToFontListMap}.
 * <p>
 * Left out on purpose: {@code getFont} for a type {@code CTFontCreateUIFontForLanguage} answers with NULL, and
 * {@code getCascadeList(0)}. They hand the NULL to {@code CFRelease} or {@code CFArrayGetCount} and crash the JVM,
 * as the C did (US-067).
 */
@EnabledOnOs(OS.MAC)
public class MacFontFinderGoldenTest {

    static final String GOLDEN = "mac-fontfinder-golden.txt";

    private static final ParityGate.Ledger LEDGER = ParityGate.ledger(MacFontFinderGoldenTest.class);

    static final List<String> CASCADE_FONTS = List.of(
            "Helvetica", "Helvetica-Bold", "Times-Roman", "TimesNewRomanPSMT", "Menlo-Regular", "CourierNewPSMT",
            "LucidaGrande", "AvenirNext-Regular", "HiraginoSans-W3", "STHeitiSC-Light", "AppleColorEmoji", "GeezaPro",
            "AppleSDGothicNeo-Regular", ".SFNS-Regular", ".ThonburiUI-Regular", "PingFangSC-Medium");

    @Test
    public void fontFinderMatchesTheJniGolden() throws Exception {
        MacFontGoldens.captureOrVerify(MacFontFinderGoldenTest.class, GOLDEN, header(), true,
                                       MacFontFinderGoldenTest::capture, MacFontFinderGoldenTest::assertCoverage);
    }

    @AfterAll
    static void theOracleRan() {
        LEDGER.assertOracleRan();
    }

    /** Runs on any macOS, where the gated golden would skip. */
    @Test
    public void fontFinderEnumerates() {
        OSShim.ensureLoaded();
        String[] data = MacFontFinderShim.getFontData();
        assertNotNull(data, "getFontData");
        assertEquals(0, data.length % 3, "getFontData returns triples");
        assertTrue(data.length > 30, "getFontData lists " + data.length / 3 + " fonts");
        assertNotNull(data[2], "the first font has a file");
        assertTrue(MacFontFinderShim.getSystemFontSize() > 0, "system font size");
        assertNotNull(MacFontFinderShim.getSystemFont(), "system font family");
    }

    static List<String> header() {
        return MacFontGoldens.header("macOS font enumeration golden (MacFontFinder)", List.of(
                "  mff.getFontData.*       length, null slots, sNNNN = name|family|file in returned order",
                "  mff.maps.<locale>.*     populateFontFileNameMap: counts, sha256 of sorted entries, family lists",
                "  mff.getFont.tNN         family of CTFontCreateUIFontForLanguage(type) via getFont(type)",
                "  mff.cascade.<font>      getCascadeList: count|displayName;file;..."));
    }

    static void capture(GoldenMap out) {
        OSShim.ensureLoaded();
        String[] data = MacFontFinderShim.getFontData();
        out.put("mff.getFontData.length", Integer.toString(data.length));
        int nulls = 0;
        for (String slot : data) {
            nulls += slot == null ? 1 : 0;
        }
        out.put("mff.getFontData.nulls", Integer.toString(nulls));
        for (int i = 0; i + 2 < data.length; i += 3) {
            out.put("mff.getFontData.s" + FontGoldenFormat.pad(i / 3, 4), MacGoldenFormat.text(data[i]) + "|"
                    + MacGoldenFormat.text(data[i + 1]) + "|" + MacGoldenFormat.text(data[i + 2]));
        }

        putMaps(out, "en", Locale.ENGLISH);
        putMaps(out, "tr", Locale.forLanguageTag("tr-TR"));

        for (int type = 0; type <= 26; type++) {
            out.put("mff.getFont.t" + FontGoldenFormat.pad(type, 2),
                    MacGoldenFormat.text(MacFontFinderShim.getFont(type)));
        }
        out.put("mff.getSystemFont", MacGoldenFormat.text(MacFontFinderShim.getSystemFont()));
        out.put("mff.getMonospacedFont", MacGoldenFormat.text(MacFontFinderShim.getMonospacedFont()));
        float size = MacFontFinderShim.getSystemFontSize();
        out.put("mff.getSystemFontSize", Float.toString(size) + ";"
                + Integer.toHexString(Float.floatToRawIntBits(size)));

        for (String name : CASCADE_FONTS) {
            long font = font(name, 12);
            out.put("mff.cascade." + name, cascade(font));
            OSShim.CFRelease(font);
        }
        for (boolean bold : new boolean[] {false, true}) {
            long font = OSShim.CTFontCreateUIFontForLanguage(0, null, bold);
            out.put("mff.cascade.ui." + (bold ? "emphasizedSystem" : "system"), cascade(font));
            OSShim.CFRelease(font);
        }

    }

    /** The caller releases the +1 font. */
    static long font(String postScriptName, double size) {
        long name = OSShim.CFStringCreate(postScriptName);
        long font = OSShim.CTFontCreateWithName(name, size, null);
        OSShim.CFRelease(name);
        return font;
    }

    private static String cascade(long font) {
        String[] list = MacFontFinderShim.getCascadeList(font);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i + 1 < list.length; i += 2) {
            entries.add(MacGoldenFormat.text(list[i]) + "@" + MacGoldenFormat.text(list[i + 1]));
        }
        return list.length + "|" + FontGoldens.joinList(entries);
    }

    private static void putMaps(GoldenMap out, String key, Locale locale) {
        HashMap<String, String> fontToFile = new HashMap<>();
        HashMap<String, String> fontToFamily = new HashMap<>();
        HashMap<String, ArrayList<String>> familyToFonts = new HashMap<>();
        boolean result = MacFontFinderShim.populateFontFileNameMap(fontToFile, fontToFamily, familyToFonts, locale);
        String prefix = "mff.maps." + key;
        out.put(prefix + ".result", Boolean.toString(result));
        out.put(prefix + ".counts", fontToFile.size() + ";" + fontToFamily.size() + ";" + familyToFonts.size());
        out.put(prefix + ".fontToFile.sha256", sortedHash(fontToFile));
        out.put(prefix + ".fontToFamily.sha256", sortedHash(fontToFamily));
        TreeMap<String, String> lists = new TreeMap<>();
        familyToFonts.forEach((family, fonts) -> lists.put(family, String.join("\n", fonts)));
        out.put(prefix + ".familyToFonts.sha256", sortedHash(lists));
        for (String family : List.of("helvetica", "times", "avenir next", "system font", "hiragino sans",
                                     "pingfang sc", "monaco", "apple color emoji")) {
            ArrayList<String> fonts = familyToFonts.get(family);
            List<String> texts = new ArrayList<>();
            if (fonts != null) {
                fonts.forEach(name -> texts.add(MacGoldenFormat.text(name)));
            }
            out.put(prefix + ".family." + family.replace(' ', '_'), fonts == null ? FontGoldens.NULL
                    : FontGoldens.joinList(texts));
        }
        out.put(prefix + ".file.helvetica", MacGoldenFormat.text(fontToFile.get("helvetica")));
        out.put(prefix + ".file.system_font_bold", MacGoldenFormat.text(fontToFile.get("system font bold")));
    }

    private static String sortedHash(Map<String, String> map) {
        StringBuilder text = new StringBuilder();
        new TreeMap<>(map).forEach((k, v) -> text.append(k).append('\t').append(v).append('\n'));
        return map.size() + ":" + FontGoldens.sha256Hex(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void assertCoverage(Map<String, String> values) {
        assertTrue(Integer.parseInt(values.get("mff.getFontData.length")) > 300,
                   "the enumeration lists hundreds of fonts");
        assertTrue(values.get("mff.cascade.ui.system").contains("@" + FontGoldens.NULL),
                   "the system cascade list holds entries without a file");
        assertTrue(values.get("mff.cascade.ui.system").contains("@u:") || values.get("mff.cascade.ui.system")
                   .contains(".ttc"), "the system cascade list names collection files");
        assertNotEquals(values.get("mff.getFont.t00"), values.get("mff.getFont.t01"),
                        "user and fixed-pitch UI fonts differ");
        assertNotEquals(values.get("mff.maps.en.fontToFile.sha256"), values.get("mff.maps.tr.fontToFile.sha256"),
                        "Turkish lower-casing changes the map keys");
    }
}
