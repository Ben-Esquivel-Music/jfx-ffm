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

package com.sun.scenario.effect.compiler;

import com.sun.scenario.effect.compiler.tree.ExtDecl;
import com.sun.scenario.effect.compiler.tree.FuncDef;
import com.sun.scenario.effect.compiler.tree.VarDecl;
import java.util.List;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class ProgramBase {

    protected static final String VALID_PROGRAM = """
            param float x;
            void main() { color = float4(x); }
            """;

    protected static final List<String> VALID_PROGRAM_DECLARATIONS =
            List.of("VarDecl param float x", "FuncDef void main");

    protected static String syntaxErrorIn(String program) {
        ParseCancellationException e =
                assertThrows(ParseCancellationException.class, () -> JSLC.getParserInfo(program));
        return e.getMessage();
    }

    protected static void assertStartsWith(String expectedStart, String actual) {
        assertTrue(actual.startsWith(expectedStart),
                () -> "expected a message starting with <" + expectedStart + "> but was <" + actual + ">");
    }

    protected static List<String> declarationsOf(String program) throws Exception {
        JSLC.ParserInfo info = JSLC.getParserInfo(program);
        Token stop = info.parser.getCurrentToken();
        assertEquals(Token.EOF, stop.getType(), () -> "parse stopped before the end of input, at " + stop);
        return info.program.getDecls().stream()
                .map(ProgramBase::describe)
                .toList();
    }

    private static String describe(ExtDecl declaration) {
        return switch (declaration) {
            case VarDecl varDecl -> "VarDecl " + varDecl.getVariable().getQualifier() + " "
                    + varDecl.getVariable().getType() + " " + varDecl.getVariable().getName();
            case FuncDef funcDef -> "FuncDef " + funcDef.getFunction().getReturnType() + " "
                    + funcDef.getFunction().getName();
            default -> declaration.getClass().getSimpleName();
        };
    }
}
