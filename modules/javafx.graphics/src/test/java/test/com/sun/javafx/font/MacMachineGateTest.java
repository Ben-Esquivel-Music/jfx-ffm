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

import com.sun.javafx.font.MacMachineShim;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The {@code machine.} keys of the macOS goldens must be reproducible on the machine that captured them: no
 * per-process or per-boot address may reach them.
 */
@EnabledOnOs(OS.MAC)
public class MacMachineGateTest {

    @Test
    void numbersAndBooleansAreWrittenAsValues() {
        assertAll(
                () -> assertEquals("4", MacMachineShim.preferenceTextOf(4L)),
                () -> assertEquals("-1", MacMachineShim.preferenceTextOf(-1L)),
                () -> assertEquals("0.5", MacMachineShim.preferenceTextOf(0.5)),
                () -> assertEquals("true", MacMachineShim.preferenceTextOf(true)),
                () -> assertEquals("false", MacMachineShim.preferenceTextOf(false)),
                () -> assertEquals("[4,true,en]", MacMachineShim.preferenceTextOf(List.of(4L, true, "en"))));
    }

    @Test
    void stringsAndStringArraysKeepTheirCapturedText() {
        assertAll(
                () -> assertEquals("en_US", MacMachineShim.preferenceTextOf("en_US")),
                () -> assertEquals("(\n    \"en-US\"\n)", MacMachineShim.preferenceTextOf(List.of("en-US"))));
    }

    @Test
    void noGatedPreferenceHoldsAnAddress() {
        for (Map.Entry<String, String> entry : MacFontGoldens.machine().entrySet()) {
            if (entry.getKey().startsWith("machine.prefs.")) {
                assertFalse(entry.getValue().contains("0x"), entry.getKey() + "=" + entry.getValue());
            }
        }
        for (Object value : List.of(4L, 2.5, true, List.of(1L, false))) {
            String text = MacMachineShim.preferenceTextOf(value);
            assertFalse(text.contains("0x"), text);
        }
    }

    @Test
    void theEnvironmentIsRecordedButNotGated() {
        assertFalse(MacFontGoldens.machine().containsKey("machine.env"));
    }
}
