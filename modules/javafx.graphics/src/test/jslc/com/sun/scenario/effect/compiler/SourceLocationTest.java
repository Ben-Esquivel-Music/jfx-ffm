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

import com.sun.scenario.effect.compiler.JSLC.JSLCInfo;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.sun.scenario.effect.compiler.JSLSource.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SourceLocationTest extends ProgramBase {

    private static final JSLSource MAIN = named("Main.jsl", """
            param float x;
            %s
            void main() {
                color = helper(x);
            }
            """);

    private static final String PIECE_WITH_A_STRAY_PARENTHESIS = """
            param float x;
            void main() {
                float4 val = float4(x);
                )
                color = val;
            }
            """;

    private static final String PIECE_WITH_AN_UNKNOWN_VARIABLE = """
            param float x;
            void main() {
                float u = undefinedVar;
                color = float4(x);
            }
            """;

    private static final String PIECE_WITHOUT_ITS_CLOSING_BRACE = """
            param float x;
            void main() {
                float4 val = float4(x);
                color = val;
            """;

    private static final String HELPER_WITH_A_STRAY_PARENTHESIS = """
            float4 helper(float v) {
                float4 h = float4(v);
                )
                return h;
            }
            """;

    private static final String HELPER_WITH_AN_AT_SIGN = """
            float4 helper(float v) {
                float4 h = float4(v);
                @
                return h;
            }
            """;

    private static final String HELPER_WITH_AN_UNCLOSED_COMMENT = """
            float4 helper(float v) {
                float4 h = float4(v);
                /* unclosed
                return h;
            }
            """;

    private static final String HELPER_WITH_A_STRAY_BRACE = """
            float4 helper(float v) {
                return float4(v);
            }
            }
            """;

    private static final String HELPER_WITH_A_GLUE_BLOCK_THAT_ENDS_EARLY = """
            float4 helper(float v) {
                return float4(v);
            }
            <<
            // halves >>
            float y;
            >>
            """;

    private static final String HELPER_WITH_AN_UNKNOWN_VARIABLE = """
            float4 helper(float v) {
                float u = undefinedVar;
                return float4(v);
            }
            """;

    // The first block starts after column 0, so that a block reported without its column, or the second block
    // reported instead, shows.
    private static final String HELPER_WITH_TWO_GLUE_BLOCKS = """
            float4 helper(float v) {
                return float4(v);
            }
                <<
            private int probeGlue;
            >>
            <<
            private int secondProbeGlue;
            >>
            """;

    private static final String FIRST_GLUE_BLOCK_IN_THE_HELPER =
            "Helper.jsl:4:4: in program Probe_Helper: A Prism program cannot have a glue block";

    private static final String INTEGER_CONSTANT_TOO_LARGE =
            "integer constant 2147483648 is too large (the largest is 2147483647)";

    private static final String LHS_NOT_AN_INTEGER_VARIABLE =
            "Condition LHS must be integer variable in order to unroll 'for' loop (for now)";

    @TempDir
    Path tmp;

    @Test
    public void aSyntaxErrorInAFileNamesTheFileItsLineAndTheProgramAndWritesNoFile() throws Exception {
        File piece = write("Piece.jsl", PIECE_WITH_A_STRAY_PARENTHESIS);
        JSLCInfo run = probeRun(tmp, EVERY_OUTPUT_TYPE);

        ParseCancellationException e = assertThrowsExactly(ParseCancellationException.class,
                () -> JSLC.compile(run, piece));

        assertStartsWith("Piece.jsl:4:4: in program Probe: extraneous input ')' expecting ", e.getMessage());
        assertEquals(List.of("Piece.jsl"), regularFileNamesUnder(tmp));
    }

    @Test
    public void aSemanticErrorInAFileNamesTheFileItsLineAndTheProgram() throws Exception {
        File piece = write("Piece.jsl", PIECE_WITH_AN_UNKNOWN_VARIABLE);
        JSLCInfo run = probeRun(tmp, EVERY_OUTPUT_TYPE);

        JSLSourceException e = assertThrowsExactly(JSLSourceException.class, () -> JSLC.compile(run, piece));

        assertEquals("Piece.jsl:3:14: in program Probe: Unknown variable undefinedVar", e.getMessage());
    }

    @Test
    public void aDirectoryInPlaceOfAFileIsReportedWithItsPath() throws IOException {
        Path directory = Files.createDirectory(tmp.resolve("Dir.jsl"));
        JSLCInfo run = probeRun(tmp, EVERY_OUTPUT_TYPE);

        FileNotFoundException e = assertThrowsExactly(FileNotFoundException.class,
                () -> JSLC.compile(run, directory.toFile()));

        assertTrue(e.getMessage().contains(directory.toString()), e.getMessage());
    }

    static Stream<Arguments> syntaxErrorsInTheHelper() {
        return Stream.of(
                Arguments.of(HELPER_WITH_A_STRAY_PARENTHESIS,
                        "Helper.jsl:3:4: in program Probe_Helper: extraneous input ')' expecting "),
                Arguments.of(HELPER_WITH_A_STRAY_BRACE,
                        "Helper.jsl:4:0: in program Probe_Helper: extraneous input '}' expecting "),
                Arguments.of(HELPER_WITH_A_GLUE_BLOCK_THAT_ENDS_EARLY,
                        "Helper.jsl:7:0: in program Probe_Helper: extraneous input '>>' expecting "));
    }

    @ParameterizedTest
    @MethodSource("syntaxErrorsInTheHelper")
    public void aSyntaxErrorInALaterPieceNamesThatPieceAndItsLine(String helper, String expectedStart) {
        JSLCInfo run = helperRun();

        ParseCancellationException e = assertThrowsExactly(ParseCancellationException.class,
                () -> JSLC.compile(run, withHelper(helper), OUT_OF_DATE));

        assertStartsWith(expectedStart, e.getMessage());
    }

    static Stream<Arguments> lexicalErrorsInTheHelper() {
        return Stream.of(
                Arguments.of(HELPER_WITH_AN_AT_SIGN,
                        "Helper.jsl:3:4: in program Probe_Helper: token recognition error at: '@'"),
                Arguments.of(HELPER_WITH_AN_UNCLOSED_COMMENT,
                        "Helper.jsl:3:4: in program Probe_Helper: unterminated comment"));
    }

    @ParameterizedTest
    @MethodSource("lexicalErrorsInTheHelper")
    public void aBadCharacterOrAnUnclosedCommentInALaterPieceNamesThatPieceAndItsLine(String helper,
                                                                                     String expected) {
        JSLCInfo run = helperRun();

        ParseCancellationException e = assertThrowsExactly(ParseCancellationException.class,
                () -> JSLC.compile(run, withHelper(helper), OUT_OF_DATE));

        assertEquals(expected, e.getMessage());
    }

    @Test
    public void aSyntaxErrorAtTheEndOfAFileIsReportedAtTheEndOfTheFile() throws Exception {
        File piece = write("Piece.jsl", PIECE_WITHOUT_ITS_CLOSING_BRACE);
        JSLCInfo run = probeRun(tmp, EVERY_OUTPUT_TYPE);

        ParseCancellationException e = assertThrowsExactly(ParseCancellationException.class,
                () -> JSLC.compile(run, piece));

        assertStartsWith("Piece.jsl:5:0: in program Probe: extraneous input '<EOF>' expecting ", e.getMessage());
    }

    @Test
    public void aSyntaxErrorInGeneratorTextNamesTheGeneratorPiece() {
        JSLSource lighting = named("Lighting.jsl", """
                param float x;
                %s
                void main() {
                    color = float4(x);
                }
                """).format(named("<Probe declarations>", "param float surfaceScale;\nparam flaot3 lightPosition;"));
        JSLCInfo run = probeRun(tmp, EVERY_OUTPUT_TYPE);
        run.peerName = "Probe_POINT";

        ParseCancellationException e = assertThrowsExactly(ParseCancellationException.class,
                () -> JSLC.compile(run, lighting, OUT_OF_DATE));

        assertStartsWith("<Probe declarations>:2:6: in program Probe_POINT: no viable alternative at input "
                + "'param flaot3'", e.getMessage());
    }

    @Test
    public void anUnknownVariableInALaterPieceIsReportedAtTheVariableInThatPiece() {
        JSLCInfo run = helperRun();

        JSLSourceException e = assertThrowsExactly(JSLSourceException.class,
                () -> JSLC.compile(run, withHelper(HELPER_WITH_AN_UNKNOWN_VARIABLE), OUT_OF_DATE));

        assertEquals("Helper.jsl:2:14: in program Probe_Helper: Unknown variable undefinedVar", e.getMessage());
    }

    @Test
    public void anUnknownFunctionAfterAPlaceholderIsReportedAtItsColumnInTheTemplate() {
        JSLSource kernel = named("Kernel.jsl", """
                param float4 weights[%d];
                void main() {
                    float4 sum = weights[0] * %d.0 + undefinedFunc(1.0);
                    color = sum;
                }
                """).format(4, 128);
        JSLCInfo run = probeRun(tmp, JSLC.OUT_HW_SHADERS);

        JSLSourceException e = assertThrowsExactly(JSLSourceException.class,
                () -> JSLC.compile(run, kernel, OUT_OF_DATE));

        assertEquals("Kernel.jsl:3:37: in program Probe: Unknown function undefinedFunc(float)", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {JSLC.OUT_D3D, JSLC.OUT_ES2, JSLC.OUT_MTL})
    public void anUnrollConditionOtherThanLessThanIsReportedAtTheLoop(int outType) {
        JSLSource kernel = named("Kernel.jsl", """
                param int count;
                param float4 weights[2];
                void main() {
                    int i;
                    float4 tmp = float4(0.0);
                    unroll (2, 0) for (i = 0; i <= count; i++) {
                        tmp += weights[i];
                    }
                    color = tmp;
                }
                """);
        JSLCInfo run = probeRun(tmp, outType);

        JSLSourceException e = assertThrowsExactly(JSLSourceException.class,
                () -> JSLC.compile(run, kernel, OUT_OF_DATE));

        assertEquals("Kernel.jsl:6:4: in program Probe: Condition must be '<' in order to unroll 'for' loop (for now)",
                e.getMessage());
    }

    @Test
    public void compilingAPrismProgramWithGlueBlocksInALaterPieceReportsTheFirstThereAndWritesNoFile()
            throws Exception {
        JSLCInfo run = helperRun();
        run.prismProgram = true;

        JSLSourceException e = assertThrowsExactly(JSLSourceException.class,
                () -> JSLC.compile(run, withHelper(HELPER_WITH_TWO_GLUE_BLOCKS), OUT_OF_DATE));

        assertEquals(FIRST_GLUE_BLOCK_IN_THE_HELPER, e.getMessage());
        assertEquals(List.of(), regularFileNamesUnder(tmp));
    }

    @Test
    public void parsingAPrismProgramWithGlueBlocksInALaterPieceReportsTheFirstThere() {
        JSLCInfo run = helperRun();
        run.prismProgram = true;

        JSLSourceException e = assertThrowsExactly(JSLSourceException.class,
                () -> JSLC.getParserInfo(run, withHelper(HELPER_WITH_TWO_GLUE_BLOCKS)));

        assertEquals(FIRST_GLUE_BLOCK_IN_THE_HELPER, e.getMessage());
    }

    static Stream<Arguments> errorsAtTheirConstruct() {
        return Stream.of(
                Arguments.of(JSLC.OUT_D3D, """
                        param int count;
                        void main() {
                            int i;
                            int j;
                            float4 tmp = float4(0.0);
                            unroll (2, 0) for (i = 0; i < count; i++) {
                                unroll (2, 0) for (j = 0; j < count; j++) {
                                    tmp += float4(1.0);
                                }
                            }
                            color = tmp;
                        }
                        """,
                        "Probe.jsl:7:8: in program Probe: Unrolling of nested 'for' loops not yet supported"),
                Arguments.of(JSLC.OUT_D3D,
                        programWithLoopOnLine5("int i;", "unroll (2, 0) for (i = 0; 1 < count; i++)"),
                        "Probe.jsl:5:4: in program Probe: " + LHS_NOT_AN_INTEGER_VARIABLE),
                Arguments.of(JSLC.OUT_D3D,
                        programWithLoopOnLine5("float f;", "unroll (2, 0) for (f = 0.0; f < 2.0; f++)"),
                        "Probe.jsl:5:4: in program Probe: " + LHS_NOT_AN_INTEGER_VARIABLE),
                Arguments.of(JSLC.OUT_D3D,
                        programWithLoopOnLine5("int i;", "unroll (2, 1) for (i = 0; i < 2; i++)"),
                        "Probe.jsl:5:4: in program Probe: Condition RHS must be variable in order to unroll 'for' "
                                + "loop"),
                Arguments.of(JSLC.OUT_D3D,
                        programWithLoopOnLine5("int i;", "unroll (2, 0) for (i = 0; ; i++)"),
                        "Probe.jsl:5:4: in program Probe: Invalid unrollable 'for' loop condition"),
                Arguments.of(JSLC.OUT_PRISM, """
                        param sampler a;
                        param sampler b;
                        param sampler c;
                        void main() {
                            color = sample(a, pos0) + sample(b, pos0) + sample(c, pos0);
                        }
                        """,
                        "Probe.jsl:3:14: in program Probe: Must use zero, one, or two samplers (for now)"),
                Arguments.of(JSLC.OUT_PRISM, """
                        param sampler a, b, c;
                        void main() {
                            color = sample(a, pos0) + sample(b, pos0) + sample(c, pos0);
                        }
                        """,
                        "Probe.jsl:1:20: in program Probe: Must use zero, one, or two samplers (for now)"),
                Arguments.of(JSLC.OUT_JAVA, """
                        param float x;
                        void main() {
                            color = float4(x);
                            return x;
                        }
                        """,
                        "Probe.jsl:4:4: in program Probe: Return statement not expected"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float y = x;"),
                        "Probe.jsl:4:10: in program Probe: Variable 'y' already declared"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float a = 1.0, a = 2.0;"),
                        "Probe.jsl:4:19: in program Probe: Variable 'a' already declared"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float3 f = float3();"),
                        "Probe.jsl:4:15: in program Probe: Number of constructor args must match size of float3"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    int n = 2147483648;"),
                        "Probe.jsl:4:12: in program Probe: " + INTEGER_CONSTANT_TOO_LARGE),
                Arguments.of(JSLC.OUT_D3D,
                        programWithLoopOnLine5("int i;", "unroll (2147483648, 0) for (i = 0; i < count; i++)"),
                        "Probe.jsl:5:4: in program Probe: " + INTEGER_CONSTANT_TOO_LARGE),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float[2] w;"),
                        "Probe.jsl:4:4: in program Probe: array types are not supported: float[2]"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float z = x + x * true;"),
                        "Probe.jsl:4:18: in program Probe: Expressions must have compatible result types "
                                + "(lhs=float rhs=bool op=*)"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    bool b = x < x + true;"),
                        "Probe.jsl:4:17: in program Probe: Expressions must have compatible result types "
                                + "(lhs=float rhs=bool op=+)"),
                Arguments.of(JSLC.OUT_PRISM, """
                        param float t;
                        param sampler a, b;
                        param float4 k;
                        param lsampler c, d;
                        void main() {
                            color = sample(a, pos0) + sample(b, pos0) + sample(c, pos0) + sample(d, pos0) + k * t;
                        }
                        """,
                        "Probe.jsl:4:15: in program Probe: Must use zero, one, or two samplers (for now)"),
                Arguments.of(JSLC.OUT_D3D, """
                        param float x;
                        float scaled(float[2] v) {
                            return x;
                        }
                        void main() {
                            color = float4(x);
                        }
                        """,
                        "Probe.jsl:2:13: in program Probe: array types are not supported: float[2]"),
                Arguments.of(JSLC.OUT_D3D, """
                        param float x;
                        float[2] scaled(float v) {
                            return x;
                        }
                        void main() {
                            color = float4(x);
                        }
                        """,
                        "Probe.jsl:2:0: in program Probe: array types are not supported: float[2]"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float4 w = float4[2](1.0);"),
                        "Probe.jsl:4:15: in program Probe: array types are not supported: float4[2]"),
                Arguments.of(JSLC.OUT_D3D,
                        programWithLoopOnLine5("int i;", "unroll (4, 2147483648) for (i = 0; i < count; i++)"),
                        "Probe.jsl:5:4: in program Probe: " + INTEGER_CONSTANT_TOO_LARGE),
                Arguments.of(JSLC.OUT_D3D, """
                        param float x;
                        param float4 w[1.5];
                        void main() {
                            color = float4(x);
                        }
                        """,
                        "Probe.jsl:2:13: in program Probe: Array size must be an integer"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float4 w[y];"),
                        "Probe.jsl:4:11: in program Probe: Array size must be a constant integer"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    const float k;"),
                        "Probe.jsl:4:16: in program Probe: Constant value must be initialized"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    const float k = 1;"),
                        "Probe.jsl:4:16: in program Probe: Constant type must match that of initializer"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float2 v = 1.0;"),
                        "Probe.jsl:4:11: in program Probe: Initializer result type must match that of variable"),
                Arguments.of(JSLC.OUT_D3D, programWithStatementOnLine4("    float f1 = 1.0, f2 = true;"),
                        "Probe.jsl:4:20: in program Probe: Initializer result type must match that of variable"));
    }

    @ParameterizedTest(name = "{index}: {2}")
    @MethodSource("errorsAtTheirConstruct")
    public void anErrorOfTheInputIsReportedAtItsConstruct(int outType, String program, String expected) {
        JSLCInfo run = probeRun(tmp, outType);

        JSLSourceException e = assertThrowsExactly(JSLSourceException.class,
                () -> JSLC.compile(run, named("Probe.jsl", program), OUT_OF_DATE));

        assertEquals(expected, e.getMessage());
    }

    @Test
    public void aFileWithAByteThatIsNotUtf8InACommentCompiles() throws Exception {
        Path piece = tmp.resolve("Piece.jsl");
        Files.write(piece, "param float x;\n// \u00FF\nvoid main() {\n    color = float4(x);\n}\n"
                .getBytes(StandardCharsets.ISO_8859_1));

        JSLC.compile(probeRun(tmp, EVERY_OUTPUT_TYPE), piece.toFile());

        List<String> expected = Stream.concat(PROBE_FILES_FOR_EVERY_OUTPUT_TYPE.stream(), Stream.of("Piece.jsl"))
                .sorted()
                .toList();
        assertEquals(expected, regularFileNamesUnder(tmp));
    }

    private static String programWithLoopOnLine5(String declaration, String loopHead) {
        return "param int count;\nvoid main() {\n    " + declaration + "\n    float4 tmp = float4(0.0);\n    "
                + loopHead + " {\n        tmp += float4(1.0);\n    }\n    color = tmp;\n}\n";
    }

    private static String programWithStatementOnLine4(String statement) {
        return "param float x;\nvoid main() {\n    float y = x;\n" + statement + "\n    color = float4(y);\n}\n";
    }

    private static JSLSource withHelper(String helper) {
        return MAIN.format(named("Helper.jsl", helper));
    }

    private JSLCInfo helperRun() {
        JSLCInfo run = probeRun(tmp, EVERY_OUTPUT_TYPE);
        run.peerName = "Probe_Helper";
        return run;
    }

    private File write(String name, String text) throws IOException {
        Path file = tmp.resolve(name);
        Files.writeString(file, text);
        return file.toFile();
    }
}
