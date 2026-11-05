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

package test.com.sun.prism.mtl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.test.ParityGate;

/**
 * AC 7 of US-001b: Metal renders, pixel for pixel, what the JNI build of {@code prism_mtl} rendered. Each method runs
 * one child JVM that draws the corpus twice through the Prism SPI with no window, and compares both passes, exactly,
 * with the golden captured from the JNI build. The swapchain (present, {@code blitToScreen}, {@code nCreateRT2}) needs
 * a Glass view and stays with the owner-run robot suites.
 */
@EnabledOnOs(OS.MAC)
@Timeout(600)
public class MTLGoldenRenderTest {

    /** Per channel. Declared before anything is compared, in the golden header as well; nothing takes a tolerance. */
    static final int TOLERANCE = 0;

    @AfterAll
    static void oracleRan() {
        ParityGate.ledger(MTLGoldenRenderTest.class).assertOracleRan();
    }

    @Test
    void theDefaultSettingsRenderTheJniGolden() throws Exception {
        MTLGoldens.verifyRender(MTLGoldenRenderTest.class, MTLGoldens.DEFAULT);
    }

    @Test
    void withoutTheSuperShaderTheRenderingMatchesTheJniGolden() throws Exception {
        MTLGoldens.verifyRender(MTLGoldenRenderTest.class, MTLGoldens.NO_SUPER_SHADER);
    }

    @Test
    void withPowerOfTwoTexturesTheRenderingMatchesTheJniGolden() throws Exception {
        MTLGoldens.verifyRender(MTLGoldenRenderTest.class, MTLGoldens.POW2);
    }

    @Test
    void teardownEndsAsInTheJniGolden() throws Exception {
        MTLGoldens.verifyRender(MTLGoldenRenderTest.class, MTLGoldens.LIFECYCLE);
    }
}
