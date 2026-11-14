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

public class ProgramGlueBlockTest extends ProgramBase {

    private static final String BLOCK_A = """
            <<
            int a;
            >>
            """;

    private static final String BLOCK_B = """
            <<
            int b;
            >>
            """;

    private static final String PARAM = "param float x;\n";

    private static final String MAIN = "void main() { color = float4(x); }\n";

    private static final String HELPER = "float helper() { return 1.0; }\n";

    private static final String GLUE_A = "GlueBlock \nint a;\n";

    private static final String GLUE_B = "GlueBlock \nint b;\n";

    private static final String PARAM_X = "VarDecl param float x";

    private static final String MAIN_FUNCTION = "FuncDef void main";

    private static final String HELPER_FUNCTION = "FuncDef float helper";

    private static final String WHERE_END_OF_INPUT_WAS_EXPECTED = " expecting {<EOF>, ";

    @Test
    public void aParamBetweenTwoGlueBlocksIsADeclarationOfItsOwn() throws Exception {
        assertEquals(List.of(GLUE_A, PARAM_X, GLUE_B, MAIN_FUNCTION),
                declarationsOf(BLOCK_A + PARAM + BLOCK_B + MAIN));
    }

    @Test
    public void aFunctionBetweenTwoGlueBlocksIsKept() throws Exception {
        assertEquals(List.of(GLUE_A, HELPER_FUNCTION, GLUE_B, PARAM_X, MAIN_FUNCTION),
                declarationsOf(BLOCK_A + HELPER + BLOCK_B + PARAM + MAIN));
    }

    @Test
    public void adjacentGlueBlocksAreTwoBlocks() throws Exception {
        assertEquals(List.of(GLUE_A, GLUE_B, PARAM_X, MAIN_FUNCTION),
                declarationsOf(BLOCK_A + BLOCK_B + VALID_PROGRAM));
    }

    @Test
    public void adjacentGlueBlocksOnOneLineAreTwoBlocks() throws Exception {
        assertEquals(List.of("GlueBlock  int a; ", "GlueBlock  int b; ", PARAM_X, MAIN_FUNCTION),
                declarationsOf("<< int a; >><< int b; >>\n" + VALID_PROGRAM));
    }

    @Test
    public void aShiftInALineCommentAfterTheGlueBlockIsPartOfTheComment() throws Exception {
        assertEquals(List.of(GLUE_A, PARAM_X, MAIN_FUNCTION),
                declarationsOf(BLOCK_A + PARAM + "// x >> 1 would halve it\n" + MAIN));
    }

    @Test
    public void aShiftInABlockCommentAfterTheGlueBlockIsPartOfTheComment() throws Exception {
        assertEquals(List.of(GLUE_A, PARAM_X, MAIN_FUNCTION),
                declarationsOf(BLOCK_A + PARAM + "/* x >> 1 */\n" + MAIN));
    }

    @Test
    public void aShiftInALineCommentInsideMainIsPartOfTheComment() throws Exception {
        String main = """
                void main() {
                    // x >> 1 would halve it
                    color = float4(x);
                }
                """;
        assertEquals(List.of(GLUE_A, PARAM_X, MAIN_FUNCTION), declarationsOf(BLOCK_A + PARAM + main));
    }

    @Test
    public void aGlueBlockOnOneLineIsABlock() throws Exception {
        assertEquals(List.of("GlueBlock  int a; ", PARAM_X, MAIN_FUNCTION),
                declarationsOf("<< int a; >>\n" + VALID_PROGRAM));
    }

    @Test
    public void aShiftLeftInTheJavaTextDoesNotOpenANestedBlock() throws Exception {
        assertEquals(List.of("GlueBlock \nint a = 1 << 2;\n", PARAM_X, MAIN_FUNCTION),
                declarationsOf("<<\nint a = 1 << 2;\n>>\n" + VALID_PROGRAM));
    }

    @Test
    public void aGlueBlockWithWindowsLineEndsKeepsThemInItsText() throws Exception {
        assertEquals(List.of("GlueBlock \r\nint a;\r\n", PARAM_X, MAIN_FUNCTION),
                declarationsOf(withWindowsLineEnds(BLOCK_A + VALID_PROGRAM)));
    }

    @Test
    public void aFunctionBetweenTwoGlueBlocksWithWindowsLineEndsIsKept() throws Exception {
        assertEquals(List.of("GlueBlock \r\nint a;\r\n", HELPER_FUNCTION, "GlueBlock \r\nint b;\r\n", PARAM_X,
                        MAIN_FUNCTION),
                declarationsOf(withWindowsLineEnds(BLOCK_A + HELPER + BLOCK_B + PARAM + MAIN)));
    }

    @Test
    public void anUnclosedCommentInTheJavaTextIsPartOfTheBlock() throws Exception {
        assertEquals(List.of("GlueBlock \n/* not closed\n", PARAM_X, MAIN_FUNCTION),
                declarationsOf("<<\n/* not closed\n>>\n" + VALID_PROGRAM));
    }

    @Test
    public void aNestedGenericTypeEndsTheBlockAtItsClosingBrackets() {
        String message = syntaxErrorIn("<<\nprivate java.util.List<java.util.List<String>> l;\n>>\n" + VALID_PROGRAM);
        assertStartsWith("line 2:47 extraneous input 'l'" + WHERE_END_OF_INPUT_WAS_EXPECTED, message);
    }

    @Test
    public void aShiftInTheJavaTextEndsTheBlock() {
        String message = syntaxErrorIn("<<\nprivate int h(int v) { return v >> 1; }\n>>\n" + VALID_PROGRAM);
        assertStartsWith("line 2:35 extraneous input '1'" + WHERE_END_OF_INPUT_WAS_EXPECTED, message);
    }

    @Test
    public void aShiftInAJavaCommentEndsTheBlock() {
        String message = syntaxErrorIn("<<\n// shifts a >> 1\nint a;\n>>\n" + VALID_PROGRAM);
        assertStartsWith("line 2:15 extraneous input '1'" + WHERE_END_OF_INPUT_WAS_EXPECTED, message);
    }

    @Test
    public void theCloserAfterAnEarlyEndIsAStrayCloser() {
        String message = syntaxErrorIn("<<\n// halves >>\nfloat y;\n>>\n" + VALID_PROGRAM);
        assertStartsWith("line 4:0 extraneous input '>>'" + WHERE_END_OF_INPUT_WAS_EXPECTED, message);
    }

    @Test
    public void aCloserAndAnOpenerInAJavaStringSplitTheBlockInTwo() throws Exception {
        assertEquals(List.of("GlueBlock \nString s = \"", "GlueBlock \";\n", PARAM_X, MAIN_FUNCTION),
                declarationsOf("<<\nString s = \">><<\";\n>>\n" + VALID_PROGRAM));
    }

    @Test
    public void aCommentOpenerAfterAnEarlyEndHidesTheCodeUpToTheNextCommentEnd() throws Exception {
        String program = """
                <<
                // x >> /* shift
                >>
                param float y;
                /* end */
                """;
        assertEquals(List.of("GlueBlock \n// x ", PARAM_X, MAIN_FUNCTION), declarationsOf(program + VALID_PROGRAM));
    }

    @Test
    public void anUnclosedGlueBlockStartingTheProgramIsReportedAtItsOpener() {
        String message = syntaxErrorIn("<<\nint a;\n" + VALID_PROGRAM);
        assertStartsWith("line 1:0 extraneous input '<<' expecting {", message);
    }

    @Test
    public void anUnclosedGlueBlockAfterTheLastDeclarationIsReportedAtItsOpener() {
        String message = syntaxErrorIn(VALID_PROGRAM + "<<\nint a;\n");
        assertStartsWith("line 3:0 extraneous input '<<'" + WHERE_END_OF_INPUT_WAS_EXPECTED, message);
    }

    private static String withWindowsLineEnds(String program) {
        return program.replace("\n", "\r\n");
    }
}
