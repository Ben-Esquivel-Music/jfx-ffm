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
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

public class JavaPeerLimitTest extends ProgramBase {

    private static final String LIMIT_PROGRAM = """
            param sampler baseImg;
            param float threshold;
            %s
            void main()
            {
                float3 luminanceVector = float3(0.2125, 0.7154, 0.0721);
                float4 val = sample(baseImg, pos0);
                float luminance = dot(luminanceVector, val.rgb);
                luminance = max(0.0, luminance - val.a * threshold);
            %s
                color = val * %s;
            }
            """;

    private static final String NESTED_CALL =
            "nested function calls are not supported by the Java software peer (-java)";

    private static final String ARRAY_ACCESS =
            "array access other than variable[variable] is not supported by the Java software peer (-java)";

    private static final String EMPTY_RETURN =
            "return without a value is not supported by the Java software peer (-java)";

    private static final String VECTOR_RESULT_OF_ABS = "the Java software peer (-java) does not support"
            + " the vector result of abs(...) here: it cannot tell which component to use";

    private static final String VECTOR_VAL = "the Java software peer (-java) does not support"
            + " vector 'val' here: it cannot tell which component to use";

    private static final List<String> SHADER_FILES = List.of("Probe.frag", "Probe.hlsl", "Probe.metal");

    @TempDir
    Path tmp;

    private record Limit(String decl, String stmt, String expr, String position, String detail) {

        JSLSource source() {
            return JSLSource.named("Limit.jsl", LIMIT_PROGRAM.formatted(decl, stmt, expr));
        }

        String message() {
            return "Limit.jsl:" + position + ": in program Probe: " + detail;
        }
    }

    private static Stream<Arguments> limits() {
        return Stream.of(
                argumentSet("a call of a program function in a call",
                        new Limit("float halve(float v) { return v * 0.5; }", "", "sign(halve(luminance))",
                                "11:23", NESTED_CALL)),
                argumentSet("a call of a built-in function in a call",
                        new Limit("", "", "sign(abs(luminance))", "11:23", NESTED_CALL)),
                argumentSet("an array access with a constant index",
                        new Limit("param float4 weights[2];", "    float w = weights[0].x;", "sign(luminance)",
                                "10:14", ARRAY_ACCESS)),
                argumentSet("an array access with a constant index in a call",
                        new Limit("param float4 weights[2];", "    float w = abs(weights[0].x);", "sign(luminance)",
                                "10:18", ARRAY_ACCESS)),
                argumentSet("a return without a value",
                        new Limit("", "    if (luminance < 0.0) return;", "sign(luminance)", "10:25", EMPTY_RETURN)),
                argumentSet("a vector-valued call in a condition",
                        new Limit("", "    if (abs(val) == val) luminance = 0.0;", "sign(luminance)", "10:8",
                                VECTOR_RESULT_OF_ABS)),
                argumentSet("a vector in a condition",
                        new Limit("", "    if (val == val) luminance = 0.0;", "sign(luminance)", "10:8",
                                VECTOR_VAL)),
                argumentSet("a vector in a field selection of a call argument",
                        new Limit("", "    luminance = abs((val.x + val).y);", "sign(luminance)", "10:29",
                                VECTOR_VAL)));
    }

    @ParameterizedTest
    @MethodSource("limits")
    public void aJavaPeerLimitIsAnErrorAtItsConstructAfterTheShadersOfAShaderOnlyCompileAreWritten(Limit limit,
            @TempDir Path shadersOnly) throws Exception {
        JSLCInfo everyOutputRun = probeRun(tmp, EVERY_OUTPUT_TYPE);

        JSLSourceException e = assertThrowsExactly(JSLSourceException.class,
                () -> JSLC.compile(everyOutputRun, limit.source(), OUT_OF_DATE));

        assertEquals(limit.message(), e.getMessage());
        assertEquals(SHADER_FILES, regularFileNamesUnder(tmp));

        JSLC.compile(probeRun(shadersOnly, JSLC.OUT_HW_SHADERS), limit.source(), OUT_OF_DATE);

        assertEquals(SHADER_FILES, regularFileNamesUnder(shadersOnly));
        assertEquals(textsOfRegularFilesUnder(shadersOnly), textsOfRegularFilesUnder(tmp));
    }

    private static Map<String, String> textsOfRegularFilesUnder(Path dir) throws IOException {
        Map<String, String> texts = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                texts.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return texts;
    }
}
