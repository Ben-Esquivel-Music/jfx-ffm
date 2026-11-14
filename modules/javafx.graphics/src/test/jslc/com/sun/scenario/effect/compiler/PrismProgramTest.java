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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrismProgramTest extends ProgramBase {

    private static final String JAVA_A = "private int probeGlueA;";

    private static final String JAVA_B = "private int probeGlueB;";

    private static final String BLOCK_A = "<<\n" + JAVA_A + "\n>>\n";

    private static final String BLOCK_B = "<<\n" + JAVA_B + "\n>>\n";

    private static final String GLUE_PROGRAM = BLOCK_A + VALID_PROGRAM;

    private static final String TWO_GLUE_BLOCKS_PROGRAM = BLOCK_A + VALID_PROGRAM + BLOCK_B;

    private static final String GLUE_AFTER_MAIN_PROGRAM = VALID_PROGRAM + BLOCK_A;

    private static final String PRISM_GLUE_BLOCK_ERROR = "A Prism program cannot have a glue block: Probe";

    private static final List<String> SHADER_FILES = List.of("Probe.frag", "Probe.hlsl", "Probe.metal");

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(ints = {JSLC.OUT_D3D, JSLC.OUT_ES2, JSLC.OUT_MTL, JSLC.OUT_JAVA, JSLC.OUT_PRISM})
    public void compilingAPrismProgramWithAGlueBlockIsAnErrorThatWritesNoFile(int outType) throws Exception {
        JSLCInfo run = prismProgramRun(outType);

        RuntimeException e = assertThrowsExactly(RuntimeException.class,
                () -> JSLC.compile(run, GLUE_PROGRAM, OUT_OF_DATE));

        assertEquals(PRISM_GLUE_BLOCK_ERROR, e.getMessage());
        assertEquals(List.of(), regularFileNamesUnder(tmp));
    }

    @Test
    public void compilingAPrismProgramWithoutAGlueBlockWritesItsShaders() throws Exception {
        JSLC.compile(prismShaderRun(), VALID_PROGRAM, OUT_OF_DATE);

        assertEquals(SHADER_FILES, regularFileNamesUnder(tmp));
    }

    @Test
    public void compilingAPrismProgramWithAGlueBlockAfterMainIsAnError() throws Exception {
        JSLCInfo run = prismShaderRun();

        RuntimeException e = assertThrowsExactly(RuntimeException.class,
                () -> JSLC.compile(run, GLUE_AFTER_MAIN_PROGRAM, OUT_OF_DATE));

        assertEquals(PRISM_GLUE_BLOCK_ERROR, e.getMessage());
        assertEquals(List.of(), regularFileNamesUnder(tmp));
    }

    @Test
    public void compilingADecoraProgramWithAGlueBlockForShadersOnlyWritesItsShaders() throws Exception {
        JSLC.compile(shaderRun(), GLUE_PROGRAM, OUT_OF_DATE);

        assertEquals(SHADER_FILES, regularFileNamesUnder(tmp));
    }

    @Test
    public void compilingADecoraProgramWithAGlueBlockForEveryOutputPutsTheBlockInBothPeers() throws Exception {
        JSLC.compile(everyOutputRun(), GLUE_PROGRAM, OUT_OF_DATE);

        assertEquals(PROBE_FILES_FOR_EVERY_OUTPUT_TYPE, regularFileNamesUnder(tmp));
        assertContainsInOrder(fileNamed("JSWProbePeer.java"), JAVA_A);
        assertContainsInOrder(fileNamed("PPSProbePeer.java"), JAVA_A);
    }

    @Test
    public void compilingADecoraProgramWithTwoGlueBlocksPutsBothInBothPeersInOrder() throws Exception {
        JSLC.compile(everyOutputRun(), TWO_GLUE_BLOCKS_PROGRAM, OUT_OF_DATE);

        assertContainsInOrder(fileNamed("JSWProbePeer.java"), JAVA_A, JAVA_B);
        assertContainsInOrder(fileNamed("PPSProbePeer.java"), JAVA_A, JAVA_B);
    }

    @Test
    public void parsingAPrismProgramWithAGlueBlockIsAnError() {
        JSLCInfo prismProgram = prismShaderRun();

        RuntimeException e = assertThrowsExactly(RuntimeException.class,
                () -> JSLC.getParserInfo(prismProgram, GLUE_PROGRAM));

        assertEquals(PRISM_GLUE_BLOCK_ERROR, e.getMessage());
    }

    @Test
    public void parsingAPrismProgramWithoutAGlueBlockGivesItsDeclarations() throws Exception {
        assertEquals(VALID_PROGRAM_DECLARATIONS,
                declarationsOf(JSLC.getParserInfo(prismShaderRun(), VALID_PROGRAM)));
    }

    @Test
    public void parsingADecoraProgramWithAGlueBlockGivesItsDeclarations() throws Exception {
        assertEquals(List.of("GlueBlock \n" + JAVA_A + "\n", "VarDecl param float x", "FuncDef void main"),
                declarationsOf(JSLC.getParserInfo(shaderRun(), GLUE_PROGRAM)));
    }

    private JSLCInfo prismShaderRun() {
        return prismProgramRun(JSLC.OUT_HW_SHADERS);
    }

    private JSLCInfo prismProgramRun(int outTypes) {
        JSLCInfo jslcinfo = probeRun(tmp, outTypes);
        jslcinfo.prismProgram = true;
        return jslcinfo;
    }

    private JSLCInfo shaderRun() {
        return probeRun(tmp, JSLC.OUT_HW_SHADERS);
    }

    private JSLCInfo everyOutputRun() {
        return probeRun(tmp, EVERY_OUTPUT_TYPE);
    }

    private String fileNamed(String name) throws IOException {
        try (Stream<Path> paths = Files.walk(tmp)) {
            Path file = paths.filter(path -> path.getFileName().toString().equals(name))
                    .findFirst()
                    .orElseThrow();
            return Files.readString(file);
        }
    }

    private static void assertContainsInOrder(String text, String... parts) {
        int from = 0;
        for (String part : parts) {
            int start = from;
            int index = text.indexOf(part, start);
            assertTrue(index >= 0, () -> "expected <" + part + "> after offset " + start + " of <" + text + ">");
            from = index + part.length();
        }
    }
}
