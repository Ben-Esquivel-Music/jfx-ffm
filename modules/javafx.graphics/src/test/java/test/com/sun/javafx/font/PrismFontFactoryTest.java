/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package test.com.sun.javafx.font;

import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CFNativeShim;
import com.sun.javafx.font.coretext.CGNativeShim;
import com.sun.javafx.font.coretext.CTNativeShim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 *
 */
public class PrismFontFactoryTest {

    public PrismFontFactoryTest() {
    }

    /**
     * Test of getFontFactory method.
     */
    @Test
    public void testGetFontFactory() {
        PrismFontFactory expResult = null;
        PrismFontFactory result = PrismFontFactory.getFontFactory();
        assertNotNull(result, "Should never turn null");
        expResult = PrismFontFactory.getFontFactory();
        assertEquals(expResult, result, "Creates different instance of FontFactory");
    }

    /**
     * Reading a registry initialises its facade, so this cannot show that the factory linked them:
     * {@code MacFontBindingStructureTest} pins the {@code CTNative.link()} call from the class file.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    public void theCoreTextRegistriesAreCompleteBesideAFontFactory() {
        assertNotNull(PrismFontFactory.getFontFactory());
        assertEquals(20, CFNativeShim.boundFunctions().size(), "CoreFoundation");
        assertEquals(22, CGNativeShim.boundFunctions().size(), "CoreGraphics");
        assertEquals(33, CTNativeShim.boundFunctions().size(), "CoreText");
    }

}
