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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TranslationUnitTest extends ProgramBase {

    private static final String STRAY_BRACE_BETWEEN_THE_DECLARATIONS = """
            param float x;
            }
            void main() { color = float4(x); }
            """;

    private static final String STRAY_BRACE_WHERE_END_OF_INPUT_WAS_EXPECTED =
            "extraneous input '}' expecting {<EOF>, ";

    private static final int EVERY_OUTPUT_TYPE =
            JSLC.OUT_D3D | JSLC.OUT_ES2 | JSLC.OUT_MTL | JSLC.OUT_JAVA | JSLC.OUT_PRISM;

    private static final long OUT_OF_DATE = Long.MAX_VALUE;

    @TempDir
    Path tmp;

    @Test
    public void textAfterTheLastDeclarationIsASyntaxError() {
        String message = syntaxErrorIn(VALID_PROGRAM + "} ) 42 garbage + + ;\n");
        assertStartsWith("line 3:0 " + STRAY_BRACE_WHERE_END_OF_INPUT_WAS_EXPECTED, message);
    }

    @Test
    public void aStrayBraceBetweenTwoDeclarationsIsASyntaxError() {
        String message = syntaxErrorIn(STRAY_BRACE_BETWEEN_THE_DECLARATIONS);
        assertStartsWith("line 2:0 " + STRAY_BRACE_WHERE_END_OF_INPUT_WAS_EXPECTED, message);
    }

    @Test
    public void aStrayBraceBeforeAFunctionThatCallsAnUndefinedFunctionIsReportedAtTheBrace() {
        String message = syntaxErrorIn(VALID_PROGRAM + "}\nvoid other() { undefinedCall(); }\n");
        assertStartsWith("line 3:0 " + STRAY_BRACE_WHERE_END_OF_INPUT_WAS_EXPECTED, message);
    }

    @Test
    public void aValidProgramKeepsAllItsDeclarations() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(VALID_PROGRAM));
    }

    @Test
    public void aLineCommentWithoutANewlineCanEndTheProgram() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS, declarationsOf(VALID_PROGRAM + "// trailing comment"));
    }

    @Test
    public void compilingAProgramWithAStrayBraceWritesNoFile() throws Exception {
        JSLCInfo run = everyOutputRun();

        ParseCancellationException e = assertThrows(ParseCancellationException.class,
                () -> JSLC.compile(run, STRAY_BRACE_BETWEEN_THE_DECLARATIONS, OUT_OF_DATE));

        assertStartsWith("line 2:0 " + STRAY_BRACE_WHERE_END_OF_INPUT_WAS_EXPECTED, e.getMessage());
        assertEquals(List.of(), regularFileNamesUnder(tmp));
    }

    @Test
    public void compilingTheValidProgramWritesAFileForEveryOutputType() throws Exception {
        JSLC.compile(everyOutputRun(), VALID_PROGRAM, OUT_OF_DATE);

        assertEquals(List.of("JSWProbePeer.java", "PPSProbePeer.java", "Probe.frag", "Probe.hlsl", "Probe.metal"),
                regularFileNamesUnder(tmp));
    }

    private JSLCInfo everyOutputRun() {
        JSLCInfo jslcinfo = new JSLCInfo();
        jslcinfo.outDir = tmp.resolve("out").toString();
        jslcinfo.metalHeaderDir = tmp.resolve("mtl-headers").toString();
        jslcinfo.shaderName = "Probe";
        jslcinfo.outTypes = EVERY_OUTPUT_TYPE;
        return jslcinfo;
    }

    private static List<String> regularFileNamesUnder(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}
