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
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TranslationUnitTest {

    private static final String VALID_PROGRAM = """
            param float x;
            void main() { color = float4(x); }
            """;

    private static final List<String> VALID_PROGRAM_DECLARATIONS =
            List.of("VarDecl param float x", "FuncDef void main");

    private static final String EXPECTING_EOF_OR_A_DECLARATION =
            "expecting {<EOF>, 'const', 'param', 'lowp', 'mediump', 'highp', 'void', TYPE, GLUE_BLOCK}";

    @Test
    public void textAfterTheLastDeclarationIsASyntaxError() {
        String message = syntaxErrorIn(VALID_PROGRAM + "} ) 42 garbage + + ;\n");
        assertEquals("line 3:0 extraneous input '}' " + EXPECTING_EOF_OR_A_DECLARATION, message);
    }

    @Test
    public void aStrayBraceBetweenTwoDeclarationsIsASyntaxError() {
        String message = syntaxErrorIn("""
                param float x;
                }
                void main() { color = float4(x); }
                """);
        assertEquals("line 2:0 extraneous input '}' " + EXPECTING_EOF_OR_A_DECLARATION, message);
    }

    @Test
    public void aStrayBraceBeforeAFunctionThatCallsAnUndefinedFunctionIsReportedAtTheBrace() {
        String message = syntaxErrorIn(VALID_PROGRAM + "}\nvoid other() { undefinedCall(); }\n");
        assertEquals("line 3:0 extraneous input '}' " + EXPECTING_EOF_OR_A_DECLARATION, message);
    }

    @Test
    public void aValidProgramKeepsAllItsDeclarations() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(VALID_PROGRAM));
    }

    @Test
    public void aLineCommentWithoutANewlineCanEndTheProgram() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(VALID_PROGRAM + "// trailing comment"));
    }

    private static String syntaxErrorIn(String program) {
        ParseCancellationException e =
                assertThrows(ParseCancellationException.class, () -> JSLC.getParserInfo(program));
        return e.getMessage();
    }

    private static List<String> declarationsOf(String program) throws Exception {
        return JSLC.getParserInfo(program).program.getDecls().stream()
                .map(TranslationUnitTest::describe)
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
