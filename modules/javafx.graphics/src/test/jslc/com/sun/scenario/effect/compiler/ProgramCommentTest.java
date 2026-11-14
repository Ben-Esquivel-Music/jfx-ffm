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

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ProgramCommentTest extends ProgramBase {

    @Test
    public void aLineCommentEndedByALineFeedCanEndTheProgram() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(VALID_PROGRAM + "// c\n"));
    }

    @Test
    public void aLineCommentEndedByACarriageReturnAndLineFeedCanEndTheProgram() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(VALID_PROGRAM + "// c\r\n"));
    }

    @Test
    public void aLineCommentEndedByALoneCarriageReturnCanEndTheProgram() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(VALID_PROGRAM + "// c\r"));
    }

    @Test
    public void aLineCommentEndedByTwoCarriageReturnsAndALineFeedCanEndTheProgram() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(VALID_PROGRAM + "// c\r\r\n"));
    }

    @Test
    public void aLoneCarriageReturnEndsALineCommentInsideTheProgram() throws Exception {
        assertEquals(List.of("VarDecl param float4 k", "FuncDef void main"),
                declarationsOf("param float4 k;\r// note\rvoid main() { color = k; }"));
    }

    @Test
    public void aLineCommentEndedByACarriageReturnCanStartTheProgram() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf("// header\r" + VALID_PROGRAM));
    }

    @Test
    public void aLineCommentBetweenDeclarationsWithWindowsLineEndsKeepsBothDeclarations() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS,
                declarationsOf("param float x;\r\n// between\r\nvoid main() { color = float4(x); }\r\n"));
    }

    @Test
    public void aProgramThatIsOnlyALineCommentHasNoDeclaration() {
        assertStartsWith("line 1:7 mismatched input '<EOF>' expecting {", syntaxErrorIn("// note"));
    }

    @Test
    public void aProgramThatIsOnlyABlockCommentHasNoDeclaration() {
        assertStartsWith("line 1:10 mismatched input '<EOF>' expecting {", syntaxErrorIn("/* note */"));
    }

    @Test
    public void closedBlockCommentsAreIgnored() throws Exception {
        String program = """
                /* a */ /* b */
                param float x;
                /* multi
                 * line */
                /**/ /***/ /* a **/ /* see // and >> here */
                void main() { color = /* k */ float4(x); }
                """;
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(program));
    }

    @Test
    public void anUnclosedCommentAfterTheLastDeclarationIsReportedAtItsOpener() {
        assertEquals("line 3:0 unterminated comment", syntaxErrorIn(VALID_PROGRAM + "/* unclosed comment\n"));
    }

    @Test
    public void anUnclosedCommentBetweenDeclarationsIsReportedAtItsOpener() {
        assertEquals("line 2:0 unterminated comment",
                syntaxErrorIn("param float x;\n/* TODO\nvoid main() { color = float4(x); }\n"));
    }

    @Test
    public void anUnclosedCommentStartingTheProgramIsReportedBeforeACharacterInItThatCannotBeLexed() {
        assertEquals("line 1:0 unterminated comment", syntaxErrorIn("/* @todo\n" + VALID_PROGRAM));
    }

    @Test
    public void anUnclosedCommentInsideAFunctionBodyIsReportedAtItsOpener() {
        assertEquals("line 2:32 unterminated comment",
                syntaxErrorIn("param float x;\nvoid main() { color = float4(x) /* \"q\n"));
    }

    @Test
    public void aSlashRightAfterTheOpenerDoesNotCloseTheComment() {
        assertEquals("line 3:0 unterminated comment", syntaxErrorIn(VALID_PROGRAM + "/*/\n"));
    }

    @Test
    public void lineCommentAndGlueBlockMarkersDoNotEndAnUnclosedComment() {
        assertEquals("line 3:0 unterminated comment", syntaxErrorIn(VALID_PROGRAM + "/* see // and >> here\n"));
    }

    @Test
    public void anUnclosedCommentAfterAClosedOneIsReportedAtTheUnclosedOne() {
        assertEquals("line 4:0 unterminated comment", syntaxErrorIn("""
                /* closed */ param float x;
                /* closed */
                void main() { color = float4(x); }
                /* unclosed"""));
    }
}
