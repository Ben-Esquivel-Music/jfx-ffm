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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity oracle for the process-wide effects of the macOS font layer, against {@value #GOLDEN}, captured from the JNI
 * build of commit {@value MacFontGoldens#CAPTURE_COMMIT}. Each scenario runs in its own child JVM because it changes
 * state every later lookup would see: font properties read once by static initializers ({@code prism.lcdtext},
 * {@code prism.subpixeltext}), and fonts registered for the process (embedded fonts, and the {@code .dfont}
 * suitcases that reach {@code DFontDecoder} only that way).
 */
@EnabledOnOs(OS.MAC)
public class MacFontProcessGoldenTest {

    static final String GOLDEN = "mac-font-process-golden.txt";

    private static final ParityGate.Ledger LEDGER = ParityGate.ledger(MacFontProcessGoldenTest.class);

    // Longer than the children's own limits, so that a hung child is destroyed and reported by the harness.
    @Test
    @Timeout(6 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    public void processStateMatchesTheJniGolden() throws Exception {
        MacFontGoldens.captureOrVerify(MacFontProcessGoldenTest.class, GOLDEN, header(), false,
                                       MacFontProcessGoldenTest::capture, MacFontProcessGoldenTest::assertCoverage);
    }

    @AfterAll
    static void theOracleRan() {
        LEDGER.assertOracleRan();
    }

    /** Runs on any macOS, where the gated golden would skip. */
    @Test
    public void childJvmPropertiesWork() throws Exception {
        Map<String, String> ping = MacFontGoldens.runChild(MacFontProcessChild.PING, Map.of("prism.lcdtext", "true"));
        assertEquals("true", ping.get("prism.lcdtext"), "prism.lcdtext in the child");
    }

    static List<String> header() {
        return MacFontGoldens.header("macOS font process golden (child JVMs: properties, registration, .dfont)",
                List.of(
                "  proc.<scenario>.*  the child's entries; strikes as in MacFontCorpus.putStrike",
                "  paths: ${WORK} scenario directory, ${TMP} its java.io.tmpdir, ${MODULE}, ${REPO}"));
    }

    static void capture(GoldenMap out) throws Exception {
        merge(out, MacFontProcessChild.LCD, MacFontGoldens.runChild(MacFontProcessChild.LCD,
                Map.of("prism.lcdtext", "true")));
        merge(out, MacFontProcessChild.SUBPIXEL_OFF, MacFontGoldens.runChild(MacFontProcessChild.SUBPIXEL_OFF,
                Map.of("prism.subpixeltext", "off")));
        merge(out, MacFontProcessChild.SUBPIXEL_NATIVE, MacFontGoldens.runChild(MacFontProcessChild.SUBPIXEL_NATIVE,
                Map.of("prism.subpixeltext", "native")));
        FontGoldenRuntime.deleteTree(MacFontGoldens.scenarioDirectory(MacFontProcessChild.EMBEDDED));
        merge(out, MacFontProcessChild.EMBEDDED, MacFontGoldens.runChild(MacFontProcessChild.EMBEDDED, Map.of()));
        merge(out, MacFontProcessChild.DFONT, MacFontGoldens.runChild(MacFontProcessChild.DFONT, Map.of()));
    }

    private static void merge(GoldenMap out, String scenario, Map<String, String> child) {
        for (Map.Entry<String, String> entry : child.entrySet()) {
            out.put("proc." + scenario + "." + entry.getKey(), entry.getValue());
        }
    }

    static void assertCoverage(Map<String, String> values) {
        assertEquals("true", values.get("proc.p1.lcd.factory.lcdTextSupported"), "the LCD child enables LCD text");
        assertTrue(values.get("proc.p1.lcd.lcd.helvetica.aa1.s12.strike").contains("|1|"), "AA_LCD strikes exist");
        assertEquals("true", values.get("proc.p4.embedded.emb.register.ahem"), "the first registration succeeds");
        assertEquals("false", values.get("proc.p4.embedded.emb.register.ahemAgain"), "the second one fails");
        assertEquals("2", values.get("proc.p4.embedded.emb.load.ttc"), "loadAll loads both collection faces");
        assertTrue(values.get("proc.p5.dfont.dfont.getFontData.dfonts").startsWith("7|")
                   || Integer.parseInt(values.get("proc.p5.dfont.dfont.getFontData.dfonts").split("\\|")[0]) >= 6,
                   "the registered .dfont faces are enumerated");
        assertEquals("decoded", values.get("proc.p5.dfont.dfont.font.Helvetica_CY_Plain.file"),
                     "Helvetica CY Plain is decoded from its .dfont");
        assertTrue(values.containsKey("proc.p2.subpixelOff.sub.helvetica.s9.strike"), "subpixel off ran");
    }
}
