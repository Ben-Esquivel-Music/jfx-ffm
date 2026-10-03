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

package test.com.sun.prism.sw;

import com.sun.pisces.RendererBase;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.sw.SWUtilsShim;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The SW pipeline hands the C texture paint one of three Pisces wrap modes for each Prism {@link WrapMode}. A
 * {@code _SIMULATED} mode is the mode it simulates, because the C applies the mode itself at the content edge.
 */
public class SWUtilsTest {

    @Test
    public void everyPrismWrapModeMapsToThePiscesModeItStandsFor() {
        Map<WrapMode, Integer> expected = new EnumMap<>(WrapMode.class);
        expected.put(WrapMode.CLAMP_NOT_NEEDED, RendererBase.WRAP_CLAMP_TO_EDGE);
        expected.put(WrapMode.CLAMP_TO_EDGE, RendererBase.WRAP_CLAMP_TO_EDGE);
        expected.put(WrapMode.CLAMP_TO_EDGE_SIMULATED, RendererBase.WRAP_CLAMP_TO_EDGE);
        expected.put(WrapMode.CLAMP_TO_ZERO, RendererBase.WRAP_CLAMP_TO_ZERO);
        expected.put(WrapMode.CLAMP_TO_ZERO_SIMULATED, RendererBase.WRAP_CLAMP_TO_ZERO);
        expected.put(WrapMode.REPEAT, RendererBase.WRAP_REPEAT);
        expected.put(WrapMode.REPEAT_SIMULATED, RendererBase.WRAP_REPEAT);
        assertEquals(WrapMode.values().length, expected.size(), "every WrapMode has an expected Pisces mode");
        for (WrapMode mode : WrapMode.values()) {
            assertEquals(expected.get(mode), SWUtilsShim.toPiscesWrapMode(mode), mode.name());
        }
    }

    /** A mode that has a simulated version maps that version as it maps itself. */
    @Test
    public void aSimulatedModeMapsAsTheModeItSimulates() {
        int simulatedModes = 0;
        for (WrapMode mode : WrapMode.values()) {
            WrapMode simulated = mode.simulatedVersion();
            if (simulated != null) {
                simulatedModes++;
                assertEquals(SWUtilsShim.toPiscesWrapMode(mode), SWUtilsShim.toPiscesWrapMode(simulated),
                        simulated.name());
            }
        }
        assertEquals(3, simulatedModes, "CLAMP_TO_ZERO, CLAMP_TO_EDGE and REPEAT have simulated versions");
    }
}
