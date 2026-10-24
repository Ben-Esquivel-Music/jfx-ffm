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
import java.util.Set;
import org.junit.jupiter.api.Test;
import test.com.sun.javafx.font.MacFontBindings.Bindings;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The binding witness rules of the macOS goldens over hand-written targets; no class file is read. */
public class MacFontBindingsTest {

    private static final String OS = "com/sun/javafx/font/coretext/OS";
    private static final String FACADE = "com/sun/javafx/font/coretext/CTNative";
    private static final String LAYOUT = "com/sun/javafx/font/coretext/CTGlyphLayout";
    private static final String FACTORY = "com/sun/javafx/font/PrismFontFactory";
    private static final Set<String> NAMES = Set.of("CFRelease", "getSystemFontSize");

    private static Bindings bindings(Map<String, Set<String>> callerTargets) {
        return new Bindings(Set.of(OS + " CFRelease(J)V"), Set.of(OS), callerTargets);
    }

    @Test
    void aDelegateOwnerCallingAFacadeIsItsImplementation() {
        Bindings delegating = bindings(Map.of(
                OS, Set.of(FACADE + " CFRelease(J)V"),
                OS + "$Native", Set.of(FACADE + " CFRelease(J)V"),
                LAYOUT, Set.of(OS + " CFRelease(J)V")));
        assertEquals(List.of(), delegating.secondBindings(NAMES));
    }

    @Test
    void aPeerCallingTheFacadeIsASecondBinding() {
        Bindings repointed = bindings(Map.of(
                OS, Set.of(FACADE + " CFRelease(J)V"),
                LAYOUT, Set.of(FACADE + " CFRelease(J)V")));
        assertEquals(List.of(LAYOUT + " calls " + FACADE + " CFRelease(J)V while the shims call " + OS
                + " CFRelease(J)V: two bindings for one entry point"), repointed.secondBindings(NAMES));
    }

    @Test
    void aCallToAnotherProductionCallerIsNotABinding() {
        Bindings bindings = new Bindings(Set.of("com/sun/javafx/font/MacFontFinder getSystemFontSize()F"),
                Set.of("com/sun/javafx/font/MacFontFinder"),
                Map.of(LAYOUT, Set.of(FACTORY + " getSystemFontSize()F"), FACTORY, Set.of()));
        assertEquals(List.of(), bindings.secondBindings(NAMES));
    }

    @Test
    void jniNamesAreMangledAsTheJvmLinksThem() {
        assertEquals("com_sun_javafx_font_coretext_OS", MacFontBindings.jniMangle(OS));
        assertEquals("J_3CJ", MacFontBindings.jniMangle("J[CJ"));
        assertEquals("a_1b_2_000e9", MacFontBindings.jniMangle("a_b;\u00E9"));
    }
}
