/*
 * Copyright (c) 2008, 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.scenario.effect.compiler.parser;

import com.sun.scenario.effect.compiler.JSLParser;
import com.sun.scenario.effect.compiler.tree.JSLVisitor;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.opentest4j.AssertionFailedError;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FieldSelectTest extends ParserBase {

    @Test
    public void rgba() {
        String tree = parseTreeFor(".rgba");
        assertEquals(tree, ".rgba");
    }

    @Test
    public void rgb() {
        String tree = parseTreeFor(".rgb");
        assertEquals(tree, ".rgb");
    }

    @Test
    public void rg() {
        String tree = parseTreeFor(".rg");
        assertEquals(tree, ".rg");
    }

    @Test
    public void r() {
        String tree = parseTreeFor(".r");
        assertEquals(tree, ".r");
    }

    @Test
    public void aaaa() {
        String tree = parseTreeFor(".aaaa");
        assertEquals(tree, ".aaaa");
    }

    @Test
    public void abgr() {
        String tree = parseTreeFor(".abgr");
        assertEquals(tree, ".abgr");
    }

    @Test
    public void xyzw() {
        String tree = parseTreeFor(".xyzw");
        assertEquals(tree, ".xyzw");
    }

    @Test
    public void xyz() {
        String tree = parseTreeFor(".xyz");
        assertEquals(tree, ".xyz");
    }

    @Test
    public void xy() {
        String tree = parseTreeFor(".xy");
        assertEquals(tree, ".xy");
    }

    @Test
    public void x() {
        String tree = parseTreeFor(".x");
        assertEquals(tree, ".x");
    }

    @Test
    public void zzz() {
        String tree = parseTreeFor(".zzz");
        assertEquals(tree, ".zzz");
    }

    @Test
    public void wzyz() {
        String tree = parseTreeFor(".wzyx");
        assertEquals(tree, ".wzyx");
    }

    @Test
    public void notAFieldSelection1() {
        // lexes as the identifier "qpz", not as a swizzle
        ParseCancellationException e = assertThrows(ParseCancellationException.class, () -> parseTreeFor("qpz"));
        assertTrue(e.getMessage().startsWith("line 1:0 mismatched input 'qpz' expecting "), e.getMessage());
    }

    @Test
    public void notAFieldSelection2() {
        // lexes as ".x" followed by the identifier "qpz"
        AssertionFailedError e = assertThrows(AssertionFailedError.class, () -> parseTreeFor(".xqpz"));
        assertEquals("trailing input 'qpz' at 1:2", e.getMessage());
    }

    @Test
    public void tooManyVals() {
        // lexes as ".xyzw" followed by the identifier "x"
        AssertionFailedError e = assertThrows(AssertionFailedError.class, () -> parseTreeFor(".xyzwx"));
        assertEquals("trailing input 'x' at 1:5", e.getMessage());
    }

    @Test
    public void mixedVals() {
        // lexes as ".xy" followed by the identifier "ba"
        AssertionFailedError e = assertThrows(AssertionFailedError.class, () -> parseTreeFor(".xyba"));
        assertEquals("trailing input 'ba' at 1:3", e.getMessage());
    }

    private String parseTreeFor(String text) {
        JSLParser parser = parserOver(text);
        JSLVisitor visitor = new JSLVisitor();
        JSLParser.Field_selectionContext tree = parser.field_selection();
        assertAllInputConsumed(parser);
        return visitor.visitField_selection(tree).getString();
    }
}
