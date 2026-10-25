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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity oracle for {@code CTGlyphLayout} through {@code PrismTextLayout}, against {@value #GOLDEN}, captured from
 * the JNI build of commit {@value MacFontGoldens#CAPTURE_COMMIT}. It runs in a child JVM because composite fallback
 * adds slots to the factory's fonts in the order a process first needs them. Wrong glyphs (PingFang SC, System
 * Devanagari, uncombined emoji sequences) are recorded as they are: this story fixes none of them.
 */
@EnabledOnOs(OS.MAC)
public class MacGlyphLayoutGoldenTest {

    static final String GOLDEN = "mac-layout-golden.txt";

    private static final ParityGate.Ledger LEDGER = ParityGate.ledger(MacGlyphLayoutGoldenTest.class);

    @Test
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    public void layoutMatchesTheJniGolden() throws Exception {
        MacFontGoldens.captureOrVerify(MacGlyphLayoutGoldenTest.class, GOLDEN, header(), false,
                                       MacGlyphLayoutGoldenTest::capture, MacGlyphLayoutGoldenTest::assertCoverage);
    }

    @AfterAll
    static void theOracleRan() {
        LEDGER.assertOracleRan();
    }

    /** Runs on any macOS, where the gated golden would skip. */
    @Test
    public void childJvmHarnessWorks() throws Exception {
        Map<String, String> ping = MacFontGoldens.runChild(MacFontProcessChild.PING, Map.of());
        assertEquals("com.sun.javafx.font.coretext.CTFactory", ping.get("factory"), "font factory in the child");
        assertEquals(System.getProperty("os.name"), ping.get("os.name"), "os.name in the child");
    }

    static List<String> header() {
        return MacFontGoldens.header("macOS text layout golden (CTGlyphLayout through PrismTextLayout, child JVM)",
                List.of(
                "  lay.<font>.font               full name|family|resource class",
                "  lay.<font>.s<size>[.rtl].tNN  bounds|lines|runs; .rNN per run (see FontGoldenFormat.putLayout)",
                "  lay.<font>.slots              composite slots after the layouts: count|name@file;...",
                "  lay.fallbacks.<font>          CTFactory.getFallbacks: count|name@file;...",
                "  lay.range.<start>_<n>         CFStringCreateWithCharacters(alloc, \"abc\", start, n)"));
    }

    static void capture(GoldenMap out) throws Exception {
        for (Map.Entry<String, String> entry : MacFontGoldens.runChild(MacFontProcessChild.LAYOUT, Map.of())
                                                             .entrySet()) {
            out.put(entry.getKey(), entry.getValue());
        }
    }

    private static List<String> runs(Map<String, String> values, String prefix) {
        return values.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                     .map(e -> e.getKey().substring(prefix.length()) + "=" + e.getValue()).sorted().toList();
    }

    static void assertCoverage(Map<String, String> values) {
        assertNotEquals(runs(values, "lay.helvetica.s12.t05.r"), runs(values, "lay.helvetica.s12.rtl.t05.r"),
                        "the paragraph direction changes the bidi layout");
        assertTrue(Integer.parseInt(values.get("lay.system.slots").split("\\|")[0]) > 1,
                   "System gained fallback slots");
        assertEquals("0", values.get("lay.range.-1_1"), "a negative start is refused");
        assertEquals("0", values.get("lay.range.2_2"), "a range past the array is refused");
        assertEquals("0061.0062.0063", values.get("lay.range.0_3"), "the whole array is accepted");
        assertTrue(values.keySet().stream().anyMatch(k -> k.startsWith("lay.system.s12.t06.r")),
                   "Devanagari is laid out");
    }
}
