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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thread isolation and CF reference balance of the macOS font layer, each in a child JVM
 * ({@link MacFontStressChild}) so that a crash is reported with its {@code hs_err} file instead of ending the test
 * fork. Neither depends on the machine, so both run on any macOS. The expectations passed against the JNI build of
 * commit {@value MacFontGoldens#CAPTURE_COMMIT} and must pass unchanged against its replacement.
 */
@EnabledOnOs(OS.MAC)
public class MacFontStressTest {

    /** Every probe keeps its object's retain count, except the controls, which retain their objects once per call. */
    static final Map<String, Long> RETAIN_DELTAS = Map.of(
            "control.retainOncePerCall", (long) MacFontStressChild.RETAIN_CALLS,
            "control.copyTableOncePerCall.loca", (long) MacFontStressChild.RETAIN_CALLS,
            "control.copyTableOncePerCall.glyf", (long) MacFontStressChild.RETAIN_CALLS);

    /** The only check on any macOS that the glyph bounds native releases the two tables it copies. */
    static final List<String> REQUIRED_PROBES = List.of("CTFontGetBoundingRectForGlyphUsingTables.loca",
            "CTFontGetBoundingRectForGlyphUsingTables.glyf");

    @Test
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void concurrentUseMatchesSerialUse() throws Exception {
        Map<String, String> values = MacFontGoldens.runChild(MacFontStressChild.class, MacFontStressChild.CONCURRENT,
                                                             Map.of());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(".mismatches") && !entry.getValue().equals("0")) {
                String corpus = key.substring(0, key.length() - ".mismatches".length());
                problems.add(corpus + ": " + entry.getValue() + " values differ from the serial run, first "
                        + values.get(corpus + ".first"));
            }
            if (key.endsWith(".values")) {
                assertTrue(Integer.parseInt(entry.getValue()) > 100, key + " = " + entry.getValue());
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    @Timeout(2 * MacFontGoldens.CHILD_TIMEOUT_SECONDS)
    void nativesKeepTheReferenceCountsOfTheirArguments() throws Exception {
        Map<String, String> values = MacFontGoldens.runChild(MacFontStressChild.class, MacFontStressChild.RETAIN,
                                                             Map.of());
        List<String> problems = new ArrayList<>();
        int probes = 0;
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("retain.delta.")) {
                probes++;
                long expected = RETAIN_DELTAS.getOrDefault(key.substring("retain.delta.".length()), 0L);
                if (Long.parseLong(entry.getValue()) != expected) {
                    problems.add(key + " = " + entry.getValue() + ", expected " + expected);
                }
            } else if (key.startsWith("retain.created.") && !entry.getValue().equals("1")) {
                problems.add(key + " = " + entry.getValue() + ": a Create native returns its object at +1");
            }
        }
        List<String> required = new ArrayList<>(REQUIRED_PROBES);
        required.addAll(RETAIN_DELTAS.keySet());
        for (String probe : required) {
            if (!values.containsKey("retain.delta." + probe)) {
                problems.add("retain.delta." + probe + " is missing");
            }
        }
        assertTrue(probes > 30, "probes: " + probes);
        assertEquals(List.of(), problems);
    }
}
