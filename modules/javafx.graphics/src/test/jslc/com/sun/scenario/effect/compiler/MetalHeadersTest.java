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
import com.sun.scenario.effect.compiler.backend.hw.MetalHeaders.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;

import static com.sun.scenario.effect.compiler.backend.hw.MetalHeaders.Kind.DECORA;
import static com.sun.scenario.effect.compiler.backend.hw.MetalHeaders.Kind.PRISM;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

public class MetalHeadersTest {

    private static final String SAMPLING = """
        param sampler baseImg;
        param float2 offset;
        void main() {
            float val = sample(baseImg, pos0 - offset).a;
            color = float4(1.0 - val);
        }
        """;

    private static final String UNTEXTURED = """
        param float opacity;
        void main() {
            color = float4(opacity);
        }
        """;

    private static final String UNTEXTURED_WITH_OFFSET = """
        param float opacity;
        param float2 offset;
        void main() {
            color = float4(opacity + offset.x);
        }
        """;

    private static final String ARRAYS = """
        param float4 weights[4];
        param sampler img;
        param float3 lightColor;
        void main() {
            color = weights[0] * sample(img, pos0) * float4(lightColor.x, lightColor.y, lightColor.z, 1.0);
        }
        """;

    private static final String INT_AND_FLOAT4 = """
        param int count;
        param float4 offset;
        void main() {
            int i;
            float4 sum = float4(0.0, 0.0, 0.0, 0.0);
            unroll (2, 0) for (i = 0; i < count; i++) {
                sum += offset;
            }
            color = sum;
        }
        """;

    private static final String PRECISIONS = """
        param lowp float opacity;
        param highp float2 offset;
        param mediump float4 weights[2];
        void main() {
            color = weights[1] * opacity + float4(offset.x, offset.y, 0.0, 1.0);
        }
        """;

    private static final String PAIRS = """
        param sampler botImg;
        param sampler topImg;
        param float2 shift;
        param float2 scale;
        param float4 tint;
        param float4 glow;
        void main() {
            float4 bot = sample(botImg, pos0 * scale + shift);
            float4 top = sample(topImg, pos1 - shift);
            color = bot * tint + top * glow;
        }
        """;

    private static final String IDENTIFIER_CHARACTERS = """
        param float $zZ_aA09;
        void main() {
            color = float4($zZ_aA09);
        }
        """;

    // Helpers that MSLBackend renames at every call and definition in the Metal shader, as the build's mask and paint
    // shaders have them: rrmask's name ends with mask's, and paint samples as main does.
    private static final String HELPER_FUNCTIONS = """
        param sampler img;
        param float2 oinvarcradii;
        param float2 iinvarcradii;
        float rrmask(float2 arcreltco, float2 invarcradii) {
            float2 ecctco = arcreltco * invarcradii;
            return clamp(dot(ecctco, ecctco), 0.0, 1.0);
        }
        float mask(float2 tco, float2 flatdim) {
            float2 arcreltco = max(abs(tco) - flatdim, 0.001);
            float ocov = rrmask(arcreltco, oinvarcradii);
            float icov = rrmask(arcreltco, iinvarcradii);
            return ocov - icov;
        }
        float4 paint(float2 tco) {
            return sample(img, tco);
        }
        void main() {
            float4 paintColor = paint(pos0);
            float coverage = mask(pos0, pos1);
            color = paintColor * coverage + sample(img, pos1);
        }
        """;

    // Uniforms named like what MSLBackend renames in a Metal shader, a helper function, sampleTex and the float2,
    // float3 and float4 types, or like the vector_float2 and vector_float4 types that MetalHeaders maps back.
    private static final String HELPER_NAMED_LIKE_ITS_UNIFORM = """
        param float opacity;
        param float2 offset;
        float opacity(float x) {
            return x * 0.5;
        }
        void main() {
            color = float4(opacity(opacity) + offset.x);
        }
        """;

    private static final String SAMPLE_TEX_BESIDE_A_SAMPLER = """
        param sampler img;
        param float sampleTex;
        void main() {
            color = sample(img, pos0);
        }
        """;

    private static final String NAMES_THAT_START_WITH_A_TYPE = """
        param float4 float4Tint;
        param float2 float2Scale;
        param float3 float3Glow;
        param float vector_float2x;
        param float2 vector_float4s;
        param float opacity;
        void main() {
            color = float4(opacity);
        }
        """;

    private static final String HUE_THEN_SATURATION = """
        param float hue;
        param float saturation;
        void main() {
            color = float4(hue, saturation, 0.0, 1.0);
        }
        """;

    private static final String SATURATION_THEN_HUE = """
        param float saturation;
        param float hue;
        void main() {
            color = float4(hue, saturation, 0.0, 1.0);
        }
        """;

    private static final String NOT_JSL = "this is not JSL";

    // Programs with uniforms that the compilers of 0281b2fde8 and a8d6688172 renamed in the uniform struct, one kind of
    // rename each: they put vector_ before a name that starts with float2, float3 or float4, and the shader name before
    // a word that names a function the program defines, be it the name of a uniform, of UNUSED or of a type, or a part
    // of a name that a $ separates, and turned sampleTex into <Shader>_SampleTexture.
    private static final String A_FLOAT2_NAME = """
        param float2 float2Scale;
        param float float2x;
        param float opacity;
        void main() {
            color = float4(opacity);
        }
        """;

    private static final String A_FLOAT3_NAME = """
        param float3 float3Glow;
        param float opacity;
        void main() {
            color = float4(opacity);
        }
        """;

    // The renamed uniform comes after another, so that looking at the first member alone misses it.
    private static final String A_FLOAT4_NAME = """
        param float opacity;
        param float4 float4Tint;
        void main() {
            color = float4(opacity);
        }
        """;

    private static final String A_UNIFORM_NAMED_LIKE_A_HELPER = """
        param float opacity;
        param float2 offset;
        float opacity(float x) {
            return x * 0.5;
        }
        void main() {
            float o = opacity(opacity);
            color = float4(o, offset.x, offset.y, 1.0);
        }
        """;

    private static final String NAMES_AND_PARTS_NAMED_LIKE_FUNCTIONS = """
        param float level$gain;
        param float gain$level;
        param float main;
        float gain(float x) {
            return x * 2.0;
        }
        float UNUSED(float x) {
            return x;
        }
        void main() {
            float v = gain(UNUSED(0.5));
            color = float4(v, v, v, 1.0);
        }
        """;

    // In NAMES_AND_PARTS_NAMED_LIKE_FUNCTIONS other members start with the shader name, so here the part after the $ is
    // the only one renamed.
    private static final String A_PART_NAMED_LIKE_A_HELPER = """
        param float level$gain;
        param float opacity;
        float gain(float x) {
            return x * 2.0;
        }
        void main() {
            float v = gain(opacity);
            color = float4(v, v, v, 1.0);
        }
        """;

    // Those compilers also left at out of the struct, because the struct text before it contained "at", in float. The
    // two vector_ they put in make up for its line, so their Metal shader is exactly as long as a clean run's and
    // differs only in its bytes.
    private static final String FLOAT2_NAMES_AND_A_DROPPED_UNIFORM = """
        param float float2x;
        param float float2y;
        param float at;
        void main() {
            color = float4(0.0);
        }
        """;

    private static final String A_HELPER_NAMED_LIKE_THE_TEXTURE_TYPE = """
        param sampler img;
        param float opacity;
        float4 texture2d(float2 tco) {
            return sample(img, tco) * opacity;
        }
        void main() {
            color = texture2d(pos0);
        }
        """;

    // For the shader LookRenamed, whose struct this compiler writes with the JSL names, a struct that an earlier
    // compiler may have written: names start with LookRenamed_ and vector_float3.
    private static final String NAMES_THAT_LOOK_RENAMED = """
        param float LookRenamed_level;
        param float3 vector_float3w;
        param float opacity;
        void main() {
            color = float4(opacity);
        }
        """;

    // For the shader Near, names close to those an earlier compiler gave, but which none of them can have written:
    // vector_float without 2, 3 or 4 after it, vector_float2, vector_float3 and vector_float4 later in a name, and the
    // shader name without the _ after it.
    private static final String NAMES_NEAR_A_RENAME = """
        param float vector_floatx;
        param float my_vector_float2;
        param float my_vector_float3;
        param float my_vector_float4;
        param float Nearby;
        param float opacity;
        void main() {
            color = float4(opacity);
        }
        """;

    // Names that occur in an earlier member line of the uniform struct: in a type (at, exture) or a name (g, pacity).
    private static final String NAMES_IN_EARLIER_MEMBERS = """
        param float opacity;
        param sampler img;
        param float at;
        param float g;
        param float pacity;
        param float exture;
        void main() {
            color = sample(img, pos0) * opacity;
        }
        """;

    // Struct members in other forms than MSLBackend writes, as a hand edit of a Metal shader can give them. An entry
    // read from most of them would get other uniforms or argument buffer IDs than the Metal shader has.
    private static final List<String> MEMBERS_IN_OTHER_FORMS = List.of(
            "    float opacity",
            "    float opacity ;",
            "    float ;",
            "\tfloat opacity;",
            "   float opacity;",
            "    vector_float2 offset; float opacity;",
            "    float/**/opacity, hue;",
            "    texture2d<float> UNUSED [[id(2)]];",
            "    LOWP HIGHP float opacity;",
            "    vector_float2 2offset;",
            "    float *offset;",
            "    float &offset;",
            "    float op<a>;",
            "    vector_float4 weights[12;",
            "    vector_float4 weights[0];",
            "    vector_float4 weights[010];",
            "    vector_float4 weights[99999999999];");

    private static final String FRAGMENT_HEADER = "FragmentShaderCommon.h";
    private static final String DECORA_HEADER = "DecoraShaderCommon.h";
    private static final String PRISM_HEADER = "PrismShaderCommon.h";

    private static final String WINDOWS_DROPS_LINK_DOT_DOT = "Windows drops link\\.. from a path as text";

    private static final long UP_TO_DATE = 1L;
    private static final long OUT_OF_DATE = Long.MAX_VALUE;
    private static final FileTime AGED = FileTime.fromMillis(1_000_000_000_000L);

    private static final Path IMPL = Path.of("com/sun/scenario/effect/impl");

    @TempDir
    Path tmp;

    @Test
    public void aPathWithoutJslGetsTheMetalShaderThePeersAndTheHeaders() throws Exception {
        Path outDir = tmp.resolve("out");
        Path headerDir = tmp.resolve("headers");
        JSLCInfo run = symbolTestRun(outDir, headerDir);
        compile(run, "Foo", SAMPLING, OUT_OF_DATE);
        finish(run);

        Path gensrc = Path.of("build/gensrc").resolve(IMPL);
        Path metal = outDir.resolve("decora-mtl").resolve(gensrc).resolve("hw/mtl/msl/Foo.metal");
        assertRegularFile(metal);
        assertTrue(Files.readString(metal).contains("#include \"" + FRAGMENT_HEADER + "\""), metal.toString());
        assertRegularFile(outDir.resolve("decora-d3d").resolve(gensrc).resolve("hw/d3d/hlsl/Foo.hlsl"));
        assertRegularFile(outDir.resolve("decora-es2").resolve(gensrc).resolve("es2/glsl/Foo.frag"));
        assertRegularFile(outDir.resolve("decora-jsw").resolve(gensrc).resolve("sw/java/JSWFooPeer.java"));
        assertRegularFile(outDir.resolve("decora-prism-ps").resolve(gensrc).resolve("prism/ps/PPSFooPeer.java"));
        assertHeaders(headerDir, DECORA, "Foo");
    }

    @Test
    public void withoutAHeaderDirectoryTheHeadersGoToMtlHeadersInTheOutputDirectory() throws Exception {
        Path outDir = tmp.resolve("out");
        JSLCInfo run = defaultRun(outDir);
        compile(run, "Probe", SAMPLING, OUT_OF_DATE);
        finish(run);

        assertHeaders(outDir.resolve("mtl-headers"), DECORA, "Probe");
    }

    @Test
    public void twoOutputRootsInOneJvmGetTheirOwnCompleteHeaders() throws Exception {
        Path rootA = tmp.resolve("A/gensrc");
        Path rootB = tmp.resolve("B/gensrc");
        oneShaderRun(rootA.resolve("jsl-decora"), rootA.resolve("mtl-headers"), DECORA, "ProbeA");
        oneShaderRun(rootB.resolve("jsl-decora"), rootB.resolve("mtl-headers"), DECORA, "ProbeB");

        assertHeaders(rootA.resolve("mtl-headers"), DECORA, "ProbeA");
        assertHeaders(rootB.resolve("mtl-headers"), DECORA, "ProbeB");
    }

    @Test
    public void aPrismRunAfterADecoraRunGetsAPrismHeaderWithOnlyItsShader() throws Exception {
        Path gensrc = tmp.resolve("C/gensrc");
        Path headerDir = gensrc.resolve("mtl-headers");
        oneShaderRun(gensrc.resolve("jsl-decora"), headerDir, DECORA, "ProbeD");
        oneShaderRun(gensrc.resolve("jsl-prism"), headerDir, PRISM, "ProbeP");

        assertHeaders(headerDir, PRISM, "ProbeP");
        Path prismHeader = headerDir.resolve(PRISM_HEADER);
        assertFalse(Files.readString(prismHeader).contains("DECORA"), prismHeader + " mentions DECORA");
        assertHeaders(headerDir, DECORA, "ProbeD");
    }

    @Test
    public void anEarlierJslInThePathDoesNotMoveTheHeaders() throws Exception {
        Path gensrc = tmp.resolve("jsl-work/gensrc");
        oneShaderRun(gensrc.resolve("jsl-decora"), gensrc.resolve("mtl-headers"), DECORA, "Probe");

        assertHeaders(gensrc.resolve("mtl-headers"), DECORA, "Probe");
        assertFalse(Files.exists(tmp.resolve("mtl-headers")));
    }

    @Test
    @DisabledOnOs(value = WINDOWS, disabledReason = WINDOWS_DROPS_LINK_DOT_DOT)
    public void theHeadersGoWhereTheFileSystemResolvesAHeaderDirectoryWithDotDotAfterALink() throws Exception {
        Path headerDir = deepLink().resolve("../gensrc/mtl-headers");
        oneShaderRun(tmp.resolve("gensrc/jsl-decora"), headerDir, DECORA, "Probe");

        assertHeaders(tmp.resolve("x/y/gensrc/mtl-headers"), DECORA, "Probe");
        assertFalse(Files.exists(tmp.resolve("gensrc/mtl-headers")));
    }

    @Test
    @DisabledOnOs(value = WINDOWS, disabledReason = WINDOWS_DROPS_LINK_DOT_DOT)
    public void withoutAHeaderDirectoryTheHeadersFollowAnOutputDirectoryWithDotDotAfterALink() throws Exception {
        JSLCInfo run = defaultRun(deepLink().resolve("../out"));
        compile(run, "Probe", SAMPLING, OUT_OF_DATE);
        finish(run);

        Path outDir = tmp.resolve("x/y/out");
        assertRegularFile(metalFile(outDir, "Probe"));
        assertHeaders(outDir.resolve("mtl-headers"), DECORA, "Probe");
        assertFalse(Files.exists(tmp.resolve("out")));
    }

    @Test
    public void aHeaderDirectoryThatCannotBeCreatedFailsNamingIt() throws Exception {
        Path headerDir = Files.createDirectories(tmp.resolve("gensrc")).resolve("mtl-headers");
        Files.writeString(headerDir, "a file, not a directory");
        JSLCInfo run = newRun(tmp.resolve("gensrc/jsl-decora"), headerDir, DECORA);

        IOException e = assertThrows(IOException.class, () -> {
            compile(run, "Probe", SAMPLING, OUT_OF_DATE);
            finish(run);
        });
        assertTrue(e.getMessage().contains(headerDir.toString()), e.getMessage());
    }

    @Test
    public void aHeaderThatCannotBeWrittenFailsNamingIt() throws Exception {
        Path headerDir = tmp.resolve("gensrc/mtl-headers");
        Path decoraHeader = Files.createDirectories(headerDir.resolve(DECORA_HEADER));
        JSLCInfo run = newRun(tmp.resolve("gensrc/jsl-decora"), headerDir, DECORA);

        IOException e = assertThrows(IOException.class, () -> {
            compile(run, "Probe", SAMPLING, OUT_OF_DATE);
            finish(run);
        });
        assertTrue(e.getMessage().contains(decoraHeader.toString()), e.getMessage());
    }

    @Test
    public void aMetalShaderWhoseUniformStructCannotBeReadFailsNamingIt() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        probeRun(outDir, gensrc.resolve("mtl-headers"), OUT_OF_DATE, OUT_OF_DATE);
        Path metal = metalFile(outDir, "ProbeA");
        String include = "#include \"" + FRAGMENT_HEADER + "\"\n";
        String cutInsideTheStruct = include + "\ntypedef struct ProbeA_Uniforms {\n";
        String structEnd = "} ProbeA_Uniforms;\n";
        String struct = "typedef struct ProbeA_Uniforms {\n    float opacity;\n" + structEnd;
        List<String> unreadableShaders = new ArrayList<>(List.of(
                cutInsideTheStruct,
                "    float opacity;\n" + structEnd,
                structEnd + cutInsideTheStruct + "    float opacity;\n",
                include + "\n// float opacity;\n" + struct,
                include + "\n#if 0\n" + struct + "#endif\n" + struct,
                include + struct,
                include + "\n\n" + struct,
                "// edited\n" + include + "\n" + struct,
                include + "\n" + struct.replace("ProbeA_", "ProbeB_"),
                cutInsideTheStruct + "    float opacity;" + structEnd));
        for (String member : MEMBERS_IN_OTHER_FORMS) {
            unreadableShaders.add(cutInsideTheStruct + member + "\n" + structEnd);
        }

        for (String unreadable : unreadableShaders) {
            Files.writeString(metal, unreadable);
            JSLCInfo run = newRun(outDir, gensrc.resolve("mtl-headers"), DECORA);
            IOException e = assertThrows(IOException.class, () -> compile(run, "ProbeA", SAMPLING, UP_TO_DATE),
                    unreadable);
            assertTrue(e.getMessage().contains(metal.toString()), e.getMessage());
        }
    }

    @Test
    public void anUpToDateMetalShaderWithItsOldUniformStructCommentedOutBeforeItFailsNamingIt() throws Exception {
        String oldStruct = uniformStructOfProbe(tmp.resolve("old"), SATURATION_THEN_HUE);
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        compile(newRun(outDir, gensrc.resolve("mtl-headers"), DECORA), "Probe", HUE_THEN_SATURATION, OUT_OF_DATE);
        Path metal = metalFile(outDir, "Probe");
        String shader = Files.readString(metal);
        String include = "#include \"" + FRAGMENT_HEADER + "\"\n";
        assertTrue(shader.startsWith(include + "\ntypedef struct Probe_Uniforms {\n    float hue;\n"), shader);
        String afterTheEmptyLine = shader.substring(include.length() + 1);
        // The second shader starts with a text exactly as long as the start MSLBackend writes, so that only the check
        // of the start can reject it. The Metal compiler accepts both.
        List<String> editedShaders = List.of(
                include + "\n/*\n" + oldStruct + "*/\n" + afterTheEmptyLine,
                include.replace("#include \"", "#include\"") + "/*" + oldStruct + "*/\n" + afterTheEmptyLine);

        for (String edited : editedShaders) {
            Files.writeString(metal, edited);
            JSLCInfo rerun = newRun(outDir, gensrc.resolve("mtl-headers"), DECORA);
            IOException e = assertThrows(IOException.class,
                    () -> compile(rerun, "Probe", HUE_THEN_SATURATION, UP_TO_DATE), edited);
            assertTrue(e.getMessage().contains(metal.toString()), e.getMessage());
        }
    }

    @Test
    public void anUpToDateMetalShaderWithItsOldUniformStructCommentedOutAfterItGetsTheEntryOfItsStruct()
            throws Exception {
        String oldStruct = uniformStructOfProbe(tmp.resolve("old"), SATURATION_THEN_HUE);
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        Path decoraHeader = gensrc.resolve("mtl-headers").resolve(DECORA_HEADER);
        JSLCInfo run = newRun(outDir, gensrc.resolve("mtl-headers"), DECORA);
        compile(run, "Probe", HUE_THEN_SATURATION, OUT_OF_DATE);
        finish(run);
        String firstRunHeader = Files.readString(decoraHeader);
        Files.delete(decoraHeader);
        Path metal = metalFile(outDir, "Probe");
        String shader = Files.readString(metal);
        String structEnd = "} Probe_Uniforms;\n";
        int afterTheStruct = shader.indexOf(structEnd) + structEnd.length();
        Files.writeString(metal,
                shader.substring(0, afterTheStruct) + "/*\n" + oldStruct + "*/\n" + shader.substring(afterTheStruct));

        JSLCInfo rerun = newRun(outDir, gensrc.resolve("mtl-headers"), DECORA);
        compile(rerun, "Probe", NOT_JSL, UP_TO_DATE);
        finish(rerun);

        assertEquals(firstRunHeader, Files.readString(decoraHeader));
    }

    @Test
    public void aSecondRunWithOneShaderOutOfDateKeepsTheHeadersOfBoth() throws Exception {
        Path outDir = tmp.resolve("out");
        assertASecondRunWithOneShaderOutOfDateKeepsTheHeadersOfBoth(outDir, outDir.resolve("mtl-headers"));
    }

    @Test
    public void aSecondRunUnderJslDecoraWithOneShaderOutOfDateKeepsTheHeadersOfBoth() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        assertASecondRunWithOneShaderOutOfDateKeepsTheHeadersOfBoth(gensrc.resolve("jsl-decora"),
                gensrc.resolve("mtl-headers"));
    }

    @Test
    public void aRunWithNothingOutOfDateParsesNoProgramAndRewritesNothing() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        probeRun(gensrc.resolve("jsl-decora"), gensrc.resolve("mtl-headers"), OUT_OF_DATE, OUT_OF_DATE);
        ageEveryFile(tmp);
        Map<Path, FileState> firstRun = snapshot(tmp);

        JSLCInfo run = newRun(gensrc.resolve("jsl-decora"), gensrc.resolve("mtl-headers"), DECORA);
        compile(run, "ProbeA", NOT_JSL, UP_TO_DATE);
        compile(run, "ProbeB", NOT_JSL, UP_TO_DATE);
        finish(run);

        assertUnchanged(firstRun, snapshot(tmp));
    }

    @Test
    public void aHeaderWhoseContentIsUnchangedKeepsItsTimeStamp() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        Path headerDir = gensrc.resolve("mtl-headers");
        probeRun(outDir, headerDir, OUT_OF_DATE, OUT_OF_DATE);
        ageEveryFile(tmp);
        Map<Path, FileState> firstRun = snapshot(tmp);

        probeRun(outDir, headerDir, OUT_OF_DATE, OUT_OF_DATE);

        for (Path header : List.of(headerDir.resolve(FRAGMENT_HEADER), headerDir.resolve(DECORA_HEADER))) {
            assertEquals(AGED, Files.getLastModifiedTime(header), header.toString());
            assertEquals(firstRun.get(header).content(), Files.readString(header), header.toString());
        }
        for (String shaderName : List.of("ProbeA", "ProbeB")) {
            Path metal = metalFile(outDir, shaderName);
            assertNotEquals(AGED, Files.getLastModifiedTime(metal), metal + " was not compiled again");
        }
    }

    @Test
    public void aRunWithForceWritesEveryOutputAgainThoughNothingIsOutOfDate() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        probeRun(outDir, gensrc.resolve("mtl-headers"), OUT_OF_DATE, OUT_OF_DATE);
        ageEveryFile(tmp);
        Map<Path, FileState> firstRun = snapshot(tmp);

        JSLCInfo forced = newRun(outDir, gensrc.resolve("mtl-headers"), DECORA);
        forced.force = true;
        compile(forced, "ProbeA", SAMPLING, UP_TO_DATE);
        compile(forced, "ProbeB", UNTEXTURED, UP_TO_DATE);
        finish(forced);

        for (String shaderName : List.of("ProbeA", "ProbeB")) {
            for (Path output : outputsOf(outDir, shaderName)) {
                assertNotEquals(AGED, Files.getLastModifiedTime(output), output + " was not written again");
                assertEquals(firstRun.get(output).content(), Files.readString(output), output.toString());
            }
        }
    }

    @Test
    public void aShaderRecompiledWithOtherUniformsGetsItsNewEntryInTheHeader() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path headerDir = gensrc.resolve("mtl-headers");
        probeRun(gensrc.resolve("jsl-decora"), headerDir, OUT_OF_DATE, OUT_OF_DATE);
        String firstRunHeader = Files.readString(headerDir.resolve(DECORA_HEADER));
        ageEveryFile(tmp);

        JSLCInfo run = newRun(gensrc.resolve("jsl-decora"), headerDir, DECORA);
        compile(run, "ProbeA", UNTEXTURED_WITH_OFFSET, OUT_OF_DATE);
        compile(run, "ProbeB", UNTEXTURED, UP_TO_DATE);
        finish(run);

        String cleanHeader = headerOfACleanRun(UNTEXTURED_WITH_OFFSET, UNTEXTURED);
        assertNotEquals(firstRunHeader, cleanHeader);
        assertEquals(cleanHeader, Files.readString(headerDir.resolve(DECORA_HEADER)));
    }

    @Test
    public void aShaderWrittenByARunThatStoppedBeforeItsHeadersGetsItsEntryInTheNextRun() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        Path headerDir = gensrc.resolve("mtl-headers");
        probeRun(outDir, headerDir, OUT_OF_DATE, OUT_OF_DATE);
        String firstRunHeader = Files.readString(headerDir.resolve(DECORA_HEADER));
        JSLCInfo stopped = newRun(outDir, headerDir, DECORA);
        compile(stopped, "ProbeA", UNTEXTURED_WITH_OFFSET, OUT_OF_DATE);
        ageEveryFile(tmp);
        Map<Path, FileState> stoppedRun = snapshot(outDir);

        JSLCInfo run = newRun(outDir, headerDir, DECORA);
        compile(run, "ProbeA", UNTEXTURED_WITH_OFFSET, UP_TO_DATE);
        compile(run, "ProbeB", UNTEXTURED, UP_TO_DATE);
        finish(run);

        String cleanHeader = headerOfACleanRun(UNTEXTURED_WITH_OFFSET, UNTEXTURED);
        assertNotEquals(firstRunHeader, cleanHeader);
        assertEquals(cleanHeader, Files.readString(headerDir.resolve(DECORA_HEADER)));
        assertUnchanged(stoppedRun, snapshot(outDir));
    }

    @Test
    public void missingHeadersAreWrittenAgainWithoutRewritingTheShaders() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path headerDir = gensrc.resolve("mtl-headers");
        Path fragmentHeader = headerDir.resolve(FRAGMENT_HEADER);
        Path decoraHeader = headerDir.resolve(DECORA_HEADER);
        probeRun(gensrc.resolve("jsl-decora"), headerDir, OUT_OF_DATE, OUT_OF_DATE);
        ageEveryFile(tmp);
        byte[] fragmentHeaderBytes = Files.readAllBytes(fragmentHeader);
        byte[] decoraHeaderBytes = Files.readAllBytes(decoraHeader);
        Files.delete(fragmentHeader);
        Files.delete(decoraHeader);
        Map<Path, FileState> firstRun = snapshot(tmp);

        probeRun(gensrc.resolve("jsl-decora"), headerDir, UP_TO_DATE, UP_TO_DATE);

        assertArrayEquals(fragmentHeaderBytes, Files.readAllBytes(fragmentHeader));
        assertArrayEquals(decoraHeaderBytes, Files.readAllBytes(decoraHeader));
        Map<Path, FileState> secondRun = snapshot(tmp);
        secondRun.remove(fragmentHeader);
        secondRun.remove(decoraHeader);
        assertUnchanged(firstRun, secondRun);
    }

    @Test
    public void aHeaderThatListsOtherShadersIsWrittenAgain() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path decoraHeader = gensrc.resolve("mtl-headers").resolve(DECORA_HEADER);
        probeRun(gensrc.resolve("jsl-decora"), gensrc.resolve("mtl-headers"), OUT_OF_DATE, OUT_OF_DATE);
        byte[] firstRunHeader = Files.readAllBytes(decoraHeader);
        Path otherGensrc = tmp.resolve("other/gensrc");
        oneShaderRun(otherGensrc.resolve("jsl-decora"), otherGensrc.resolve("mtl-headers"), DECORA, "ProbeA");
        ageEveryFile(gensrc);
        Map<Path, FileState> firstRun = snapshot(gensrc);
        firstRun.remove(decoraHeader);
        Files.copy(otherGensrc.resolve("mtl-headers").resolve(DECORA_HEADER), decoraHeader,
                StandardCopyOption.REPLACE_EXISTING);

        probeRun(gensrc.resolve("jsl-decora"), gensrc.resolve("mtl-headers"), UP_TO_DATE, UP_TO_DATE);

        assertArrayEquals(firstRunHeader, Files.readAllBytes(decoraHeader));
        Map<Path, FileState> secondRun = snapshot(gensrc);
        secondRun.remove(decoraHeader);
        assertUnchanged(firstRun, secondRun);
    }

    @Test
    public void aHeaderThatListsTheSameShadersWithTheirUniformsInAnotherOrderIsWrittenAgain() throws Exception {
        Path headerDir = tmp.resolve("gensrc/mtl-headers");
        Path decoraHeader = headerDir.resolve(DECORA_HEADER);
        Path outDirA = tmp.resolve("A/jsl-decora");
        JSLCInfo runA = newRun(outDirA, headerDir, DECORA);
        compile(runA, "Probe", HUE_THEN_SATURATION, OUT_OF_DATE);
        finish(runA);
        byte[] headerOfA = Files.readAllBytes(decoraHeader);
        JSLCInfo runB = newRun(tmp.resolve("B/jsl-decora"), headerDir, DECORA);
        compile(runB, "Probe", SATURATION_THEN_HUE, OUT_OF_DATE);
        finish(runB);
        byte[] headerOfB = Files.readAllBytes(decoraHeader);
        // Of equal length, so that only their bytes tell the two headers apart.
        assertEquals(headerOfA.length, headerOfB.length);
        assertFalse(Arrays.equals(headerOfA, headerOfB), "B's run kept A's header");
        ageEveryFile(tmp);
        Map<Path, FileState> outputsOfA = snapshot(outDirA);

        JSLCInfo rerunA = newRun(outDirA, headerDir, DECORA);
        compile(rerunA, "Probe", HUE_THEN_SATURATION, UP_TO_DATE);
        finish(rerunA);

        assertArrayEquals(headerOfA, Files.readAllBytes(decoraHeader));
        assertUnchanged(outputsOfA, snapshot(outDirA));
    }

    @Test
    public void jslcInfosThatShareARunWriteOneHeaderWithTheShadersOfBoth() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        JSLCInfo first = newRun(gensrc.resolve("jsl-decora"), gensrc.resolve("mtl-headers"), DECORA);
        JSLCInfo second = newRun(gensrc.resolve("jsl-decora"), gensrc.resolve("mtl-headers"), DECORA);
        second.metalHeaders = first.metalHeaders;
        compile(first, "ProbeA", SAMPLING, OUT_OF_DATE);
        compile(second, "ProbeB", UNTEXTURED, OUT_OF_DATE);
        finish(first);

        assertHeaders(gensrc.resolve("mtl-headers"), DECORA, "ProbeA", "ProbeB");
    }

    @Test
    public void aRunRejectsAnotherHeaderDirectoryOrKind() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path headerDir = gensrc.resolve("mtl-headers");
        Path otherHeaderDir = tmp.resolve("other-headers");
        JSLCInfo run = newRun(gensrc.resolve("jsl-decora"), headerDir, DECORA);
        compile(run, "ProbeA", SAMPLING, OUT_OF_DATE);
        JSLCInfo otherDirectory = newRun(gensrc.resolve("jsl-decora"), otherHeaderDir, DECORA);
        otherDirectory.metalHeaders = run.metalHeaders;
        JSLCInfo otherKind = newRun(gensrc.resolve("jsl-prism"), headerDir, PRISM);
        otherKind.metalHeaders = run.metalHeaders;

        IllegalStateException directory = assertThrows(IllegalStateException.class,
                () -> compile(otherDirectory, "ProbeB", UNTEXTURED, OUT_OF_DATE));
        assertTrue(directory.getMessage().contains(headerDir.toString()), directory.getMessage());
        assertTrue(directory.getMessage().contains(otherHeaderDir.toString()), directory.getMessage());
        IllegalStateException kind = assertThrows(IllegalStateException.class,
                () -> compile(otherKind, "ProbeP", UNTEXTURED, OUT_OF_DATE));
        assertTrue(kind.getMessage().contains(PRISM_HEADER), kind.getMessage());
    }

    @Test
    @DisabledOnOs(value = WINDOWS, disabledReason = WINDOWS_DROPS_LINK_DOT_DOT)
    public void aRunTellsHeaderDirectoriesApartAsTheFileSystemResolvesThem() throws Exception {
        Path outDir = tmp.resolve("gensrc/jsl-decora");
        Path headerDir = deepLink().resolve("../mtl-headers");
        JSLCInfo run = newRun(outDir, headerDir, DECORA);
        compile(run, "ProbeA", SAMPLING, OUT_OF_DATE);
        JSLCInfo sameDirectory = newRun(outDir, tmp.resolve("x/y/mtl-headers"), DECORA);
        sameDirectory.metalHeaders = run.metalHeaders;
        compile(sameDirectory, "ProbeB", UNTEXTURED, OUT_OF_DATE);
        JSLCInfo normalized = newRun(outDir, headerDir.normalize(), DECORA);
        normalized.metalHeaders = run.metalHeaders;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> compile(normalized, "ProbeC", UNTEXTURED, OUT_OF_DATE));
        assertTrue(e.getMessage().contains(headerDir.toString()), e.getMessage());
        assertTrue(e.getMessage().contains(headerDir.normalize().toString()), e.getMessage());
        finish(run);
        assertHeaders(tmp.resolve("x/y/mtl-headers"), DECORA, "ProbeA", "ProbeB");
    }

    @Test
    public void aRunRejectsTheSameShaderTwice() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        JSLCInfo run = newRun(gensrc.resolve("jsl-decora"), gensrc.resolve("mtl-headers"), DECORA);
        compile(run, "Probe", SAMPLING, OUT_OF_DATE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> compile(run, "Probe", UNTEXTURED, OUT_OF_DATE));
        assertTrue(e.getMessage().contains("Shader Probe "), e.getMessage());
    }

    @Test
    public void aRunWithoutMetalOutputWritesNoHeaders() throws Exception {
        Path outDir = tmp.resolve("out");
        JSLCInfo run = defaultRun(outDir);
        run.outTypes = JSLC.OUT_D3D | JSLC.OUT_ES2 | JSLC.OUT_ALL_PEERS;
        compile(run, "Probe", SAMPLING, OUT_OF_DATE);
        finish(run);

        assertFalse(Files.exists(outDir.resolve("mtl-headers")));
    }

    @Test
    public void aDecoraRunWritesTheHeadersOfTheCompilerBeforeThisStory() throws Exception {
        Path headerDir = tmp.resolve("gensrc/mtl-headers");
        JSLCInfo run = newRun(tmp.resolve("gensrc/jsl-decora"), headerDir, DECORA);
        run.outTypes = JSLC.OUT_MTL;
        compile(run, "Textured", SAMPLING, OUT_OF_DATE);
        compile(run, "Untextured", UNTEXTURED, OUT_OF_DATE);
        compile(run, "Arrays", ARRAYS, OUT_OF_DATE);
        compile(run, "IntAndFloat4", INT_AND_FLOAT4, OUT_OF_DATE);
        compile(run, "Precisions", PRECISIONS, OUT_OF_DATE);
        compile(run, "Pairs", PAIRS, OUT_OF_DATE);
        compile(run, "IdentifierCharacters", IDENTIFIER_CHARACTERS, OUT_OF_DATE);
        finish(run);

        assertEquals(GOLDEN_FRAGMENT_HEADER, Files.readString(headerDir.resolve(FRAGMENT_HEADER)));
        assertEquals(GOLDEN_DECORA_HEADER, Files.readString(headerDir.resolve(DECORA_HEADER)));
    }

    @Test
    public void aShaderWithHelperFunctionsGetsTheMetalShaderOfTheCompilerBeforeThisStory() throws Exception {
        Path outDir = tmp.resolve("gensrc/jsl-decora");
        JSLCInfo run = newRun(outDir, tmp.resolve("gensrc/mtl-headers"), DECORA);
        run.outTypes = JSLC.OUT_MTL;
        compile(run, "Helpers", HELPER_FUNCTIONS, OUT_OF_DATE);

        assertEquals(GOLDEN_HELPERS_METAL, Files.readString(metalFile(outDir, "Helpers")));
    }

    @Test
    public void aUniformNamedLikeAHelperFunctionATypeOrSampleTexKeepsItsJslNameInTheHeader() throws Exception {
        Path headerDir = tmp.resolve("gensrc/mtl-headers");
        JSLCInfo run = newRun(tmp.resolve("gensrc/jsl-decora"), headerDir, DECORA);
        run.outTypes = JSLC.OUT_MTL;
        compile(run, "HelperNamedLikeItsUniform", HELPER_NAMED_LIKE_ITS_UNIFORM, OUT_OF_DATE);
        compile(run, "SampleTexBesideASampler", SAMPLE_TEX_BESIDE_A_SAMPLER, OUT_OF_DATE);
        compile(run, "NamesThatStartWithAType", NAMES_THAT_START_WITH_A_TYPE, OUT_OF_DATE);
        finish(run);

        String header = Files.readString(headerDir.resolve(DECORA_HEADER));
        assertUniformNames(header, "HelperNamedLikeItsUniform", "opacity", "offset", "UNUSED");
        assertUniformNames(header, "SampleTexBesideASampler", "img", "sampleTex");
        assertUniformNames(header, "NamesThatStartWithAType",
                "float4Tint", "float2Scale", "float3Glow", "vector_float2x", "vector_float4s", "opacity", "UNUSED");
    }

    @Test
    public void upToDateMetalShadersWithUniformsThatAnEarlierCompilerRenamedGetTheOutputsOfACleanRun()
            throws Exception {
        List<LegacyShader> shaders = List.of(
                new LegacyShader("Scale", A_FLOAT2_NAME, SCALE_METAL_OF_0281B2FDE8,
                        "float2Scale", "float2x", "opacity", "UNUSED"),
                new LegacyShader("Glow", A_FLOAT3_NAME, GLOW_METAL_OF_0281B2FDE8, "float3Glow", "opacity", "UNUSED"),
                new LegacyShader("Tint", A_FLOAT4_NAME, TINT_METAL_OF_0281B2FDE8, "opacity", "float4Tint", "UNUSED"),
                new LegacyShader("Helper", A_UNIFORM_NAMED_LIKE_A_HELPER, HELPER_METAL_OF_0281B2FDE8,
                        "opacity", "offset", "UNUSED"),
                new LegacyShader("Parts", NAMES_AND_PARTS_NAMED_LIKE_FUNCTIONS, PARTS_METAL_OF_0281B2FDE8,
                        "level$gain", "gain$level", "main", "UNUSED"),
                new LegacyShader("Dollar", A_PART_NAMED_LIKE_A_HELPER, DOLLAR_METAL_OF_0281B2FDE8,
                        "level$gain", "opacity", "UNUSED"),
                new LegacyShader("Sampled", SAMPLE_TEX_BESIDE_A_SAMPLER, SAMPLED_METAL_OF_0281B2FDE8,
                        "img", "sampleTex"),
                new LegacyShader("Typed", A_HELPER_NAMED_LIKE_THE_TEXTURE_TYPE, TYPED_METAL_OF_0281B2FDE8,
                        "img", "opacity"),
                new LegacyShader("Dropped", FLOAT2_NAMES_AND_A_DROPPED_UNIFORM, DROPPED_METAL_OF_0281B2FDE8,
                        "float2x", "float2y", "at", "UNUSED"));
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        Path headerDir = gensrc.resolve("mtl-headers");
        for (LegacyShader shader : shaders) {
            Path metal = metalFile(outDir, shader.name());
            Files.createDirectories(metal.getParent());
            Files.writeString(metal, shader.metalOf0281b2fde8());
        }
        ageEveryFile(tmp);

        JSLCInfo run = newRun(outDir, headerDir, DECORA);
        run.outTypes = JSLC.OUT_MTL;
        for (LegacyShader shader : shaders) {
            compile(run, shader.name(), shader.program(), UP_TO_DATE);
        }
        finish(run);

        Path cleanGensrc = tmp.resolve("clean/gensrc");
        JSLCInfo clean = newRun(cleanGensrc.resolve("jsl-decora"), cleanGensrc.resolve("mtl-headers"), DECORA);
        clean.outTypes = JSLC.OUT_MTL;
        for (LegacyShader shader : shaders) {
            compile(clean, shader.name(), shader.program(), OUT_OF_DATE);
        }
        finish(clean);
        for (LegacyShader shader : shaders) {
            String cleanShader = Files.readString(metalFile(cleanGensrc.resolve("jsl-decora"), shader.name()));
            assertNotEquals(shader.metalOf0281b2fde8(), cleanShader, shader.name());
            assertEquals(cleanShader, Files.readString(metalFile(outDir, shader.name())), shader.name());
        }
        for (String headerName : List.of(FRAGMENT_HEADER, DECORA_HEADER)) {
            assertArrayEquals(Files.readAllBytes(cleanGensrc.resolve("mtl-headers").resolve(headerName)),
                    Files.readAllBytes(headerDir.resolve(headerName)), headerName);
        }
        String header = Files.readString(headerDir.resolve(DECORA_HEADER));
        for (LegacyShader shader : shaders) {
            assertUniformNames(header, shader.name(), shader.jslNames());
        }

        ageEveryFile(gensrc);
        Map<Path, FileState> upgraded = snapshot(gensrc);
        JSLCInfo rerun = newRun(outDir, headerDir, DECORA);
        rerun.outTypes = JSLC.OUT_MTL;
        for (LegacyShader shader : shaders) {
            compile(rerun, shader.name(), NOT_JSL, UP_TO_DATE);
        }
        finish(rerun);
        assertUnchanged(upgraded, snapshot(gensrc));
    }

    @Test
    public void aRerunWithNothingChangedKeepsMetalShadersWhoseJslNamesLookRenamedAndParsesNoOtherProgram()
            throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        Path headerDir = gensrc.resolve("mtl-headers");
        Path decoraHeader = headerDir.resolve(DECORA_HEADER);
        JSLCInfo run = newRun(outDir, headerDir, DECORA);
        compile(run, "Floats", NAMES_THAT_START_WITH_A_TYPE, OUT_OF_DATE);
        compile(run, "LookRenamed", NAMES_THAT_LOOK_RENAMED, OUT_OF_DATE);
        compile(run, "Near", NAMES_NEAR_A_RENAME, OUT_OF_DATE);
        compile(run, "ProbeB", UNTEXTURED, OUT_OF_DATE);
        finish(run);
        ageEveryFile(tmp);
        String firstRunHeader = Files.readString(decoraHeader);
        Files.delete(decoraHeader);
        Map<Path, FileState> firstRun = snapshot(tmp);

        JSLCInfo rerun = newRun(outDir, headerDir, DECORA);
        compile(rerun, "Floats", NAMES_THAT_START_WITH_A_TYPE, UP_TO_DATE);
        compile(rerun, "LookRenamed", NAMES_THAT_LOOK_RENAMED, UP_TO_DATE);
        compile(rerun, "Near", NOT_JSL, UP_TO_DATE);
        compile(rerun, "ProbeB", NOT_JSL, UP_TO_DATE);
        finish(rerun);

        assertEquals(firstRunHeader, Files.readString(decoraHeader));
        assertUniformNames(firstRunHeader, "Floats",
                "float4Tint", "float2Scale", "float3Glow", "vector_float2x", "vector_float4s", "opacity", "UNUSED");
        assertUniformNames(firstRunHeader, "LookRenamed", "LookRenamed_level", "vector_float3w", "opacity", "UNUSED");
        Map<Path, FileState> secondRun = snapshot(tmp);
        secondRun.remove(decoraHeader);
        assertUnchanged(firstRun, secondRun);
    }

    @Test
    public void uniformsWhoseNamesOccurInEarlierMemberLinesAreAllInTheStructAndTheHeader() throws Exception {
        Path gensrc = tmp.resolve("gensrc");
        Path outDir = gensrc.resolve("jsl-decora");
        JSLCInfo run = newRun(outDir, gensrc.resolve("mtl-headers"), DECORA);
        run.outTypes = JSLC.OUT_MTL;
        compile(run, "Substrings", NAMES_IN_EARLIER_MEMBERS, OUT_OF_DATE);
        finish(run);

        String struct = """
            #include "FragmentShaderCommon.h"

            typedef struct Substrings_Uniforms {
                float opacity;
                texture2d<float> img;
                float at;
                float g;
                float pacity;
                float exture;
            } Substrings_Uniforms;
            """;
        String shader = Files.readString(metalFile(outDir, "Substrings"));
        assertTrue(shader.startsWith(struct), shader);
        assertUniformNames(Files.readString(gensrc.resolve("mtl-headers").resolve(DECORA_HEADER)), "Substrings",
                "opacity", "img", "at", "g", "pacity", "exture");
    }

    private void assertASecondRunWithOneShaderOutOfDateKeepsTheHeadersOfBoth(Path outDir, Path headerDir)
            throws Exception {
        probeRun(outDir, headerDir, OUT_OF_DATE, OUT_OF_DATE);
        byte[] fragmentHeader = Files.readAllBytes(headerDir.resolve(FRAGMENT_HEADER));
        byte[] decoraHeader = Files.readAllBytes(headerDir.resolve(DECORA_HEADER));
        ageEveryFile(tmp);
        Map<Path, FileState> upToDateOutputs = snapshot(outputsOf(outDir, "ProbeA"));

        probeRun(outDir, headerDir, UP_TO_DATE, OUT_OF_DATE);

        assertHeaders(headerDir, DECORA, "ProbeA", "ProbeB");
        assertArrayEquals(fragmentHeader, Files.readAllBytes(headerDir.resolve(FRAGMENT_HEADER)));
        assertArrayEquals(decoraHeader, Files.readAllBytes(headerDir.resolve(DECORA_HEADER)));
        assertUnchanged(upToDateOutputs, snapshot(outputsOf(outDir, "ProbeA")));
    }

    private static JSLCInfo newRun(Path outDir, Path headerDir, Kind kind) {
        JSLCInfo jslcinfo = defaultRun(outDir);
        jslcinfo.metalHeaderDir = headerDir.toString();
        jslcinfo.metalHeaderKind = kind;
        return jslcinfo;
    }

    private static JSLCInfo defaultRun(Path outDir) {
        JSLCInfo jslcinfo = new JSLCInfo();
        jslcinfo.outDir = outDir.toString();
        jslcinfo.trimToOutDir = true;
        jslcinfo.shaderName = "Effect";
        jslcinfo.outTypes = JSLC.OUT_ALL_PEERS | JSLC.OUT_HW_SHADERS;
        return jslcinfo;
    }

    private static JSLCInfo symbolTestRun(Path outDir, Path headerDir) {
        JSLCInfo jslcinfo = new JSLCInfo();
        jslcinfo.outDir = outDir.toString();
        jslcinfo.shaderName = "Effect";
        jslcinfo.peerName = "Foo";
        jslcinfo.outTypes = JSLC.OUT_ALL_PEERS | JSLC.OUT_HW_SHADERS;
        jslcinfo.metalHeaderDir = headerDir.toString();
        return jslcinfo;
    }

    private static void compile(JSLCInfo jslcinfo, String peerName, String program, long sourceTime)
            throws Exception {
        jslcinfo.peerName = peerName;
        JSLC.compile(jslcinfo, program, sourceTime);
    }

    private static void finish(JSLCInfo jslcinfo) throws Exception {
        jslcinfo.metalHeaders.write();
    }

    private static void oneShaderRun(Path outDir, Path headerDir, Kind kind, String shaderName) throws Exception {
        JSLCInfo run = newRun(outDir, headerDir, kind);
        compile(run, shaderName, SAMPLING, OUT_OF_DATE);
        finish(run);
    }

    private static void probeRun(Path outDir, Path headerDir, long sourceTimeA, long sourceTimeB) throws Exception {
        JSLCInfo run = newRun(outDir, headerDir, DECORA);
        compile(run, "ProbeA", SAMPLING, sourceTimeA);
        compile(run, "ProbeB", UNTEXTURED, sourceTimeB);
        finish(run);
    }

    // tmp/link, a link to tmp/x/y/z: to the file system tmp/link/.. is tmp/x/y, to Path.normalize it is tmp.
    private Path deepLink() throws IOException {
        return Files.createSymbolicLink(tmp.resolve("link"), Files.createDirectories(tmp.resolve("x/y/z")));
    }

    private String headerOfACleanRun(String programA, String programB) throws Exception {
        Path cleanGensrc = tmp.resolve("clean/gensrc");
        JSLCInfo clean = newRun(cleanGensrc.resolve("jsl-decora"), cleanGensrc.resolve("mtl-headers"), DECORA);
        compile(clean, "ProbeA", programA, OUT_OF_DATE);
        compile(clean, "ProbeB", programB, OUT_OF_DATE);
        finish(clean);
        return Files.readString(cleanGensrc.resolve("mtl-headers").resolve(DECORA_HEADER));
    }

    private static String uniformStructOfProbe(Path gensrc, String program) throws Exception {
        Path outDir = gensrc.resolve("jsl-decora");
        compile(newRun(outDir, gensrc.resolve("mtl-headers"), DECORA), "Probe", program, OUT_OF_DATE);
        String shader = Files.readString(metalFile(outDir, "Probe"));
        String structEnd = "} Probe_Uniforms;\n";
        return shader.substring(shader.indexOf("typedef struct Probe_Uniforms {\n"),
                shader.indexOf(structEnd) + structEnd.length());
    }

    private static Path metalFile(Path outDir, String shaderName) {
        return outDir.resolve(IMPL).resolve("hw/mtl/msl/" + shaderName + ".metal");
    }

    private static List<Path> outputsOf(Path outDir, String shaderName) {
        Path impl = outDir.resolve(IMPL);
        return List.of(
                metalFile(outDir, shaderName),
                impl.resolve("hw/d3d/hlsl/" + shaderName + ".hlsl"),
                impl.resolve("es2/glsl/" + shaderName + ".frag"),
                impl.resolve("sw/java/JSW" + shaderName + "Peer.java"),
                impl.resolve("prism/ps/PPS" + shaderName + "Peer.java"));
    }

    private static void assertHeaders(Path headerDir, Kind kind, String... shaderNames) throws IOException {
        Path fragmentHeader = headerDir.resolve(FRAGMENT_HEADER);
        Path objCHeader = headerDir.resolve(objCHeaderName(kind));
        assertRegularFile(fragmentHeader);
        assertRegularFile(objCHeader);
        String header = Files.readString(objCHeader);
        String where = objCHeader.toString();
        String kindName = kind.name();
        assertEquals("#ifndef " + kindName + "_SHADER_COMMON_H", header.lines().findFirst().orElse(""), where);
        assertTrue(header.contains("} " + kindName + "_VS_INPUT;"), "no " + kindName + "_VS_INPUT in " + where);
        List<String> names = List.of(shaderNames);
        assertEquals(names, matches(header, "NSDictionary\\* get(\\w+)_Uniform_VarID_Dict\\(\\) \\{\n"), where);
        String dictFunction = "NSDictionary* get" + kindName + "Dict(NSString* inShaderName) {\n";
        assertTrue(header.contains(dictFunction), "no get" + kindName + "Dict in " + where);
        String dict = header.substring(header.indexOf(dictFunction));
        String dictCase = "isEqualToString:@\"(\\w+)\"\\]\\) \\{\n[^\n]*\n"
                + " {8}return get\\1_Uniform_VarID_Dict\\(\\);\n";
        assertEquals(names, matches(dict, dictCase), where);
    }

    // Every name the header gives a uniform is its JSL name: the Java peer sets the uniform by that name, and MTLShader
    // looks it up in the map that MTLShaderCalls builds from the shader's dictionary.
    private static void assertUniformNames(String header, String shaderName, String... jslNames) {
        int entryStart = header.indexOf("typedef enum " + shaderName + "_ArgumentBufferID {\n");
        assertTrue(entryStart >= 0, "no entry for " + shaderName);
        String entry = header.substring(entryStart, header.indexOf("}\n\n\n", entryStart));
        String uniformStruct = entry.substring(entry.indexOf("typedef struct "),
                entry.indexOf("} " + shaderName + "_Uniforms;"));
        List<String> names = List.of(jslNames);
        assertEquals(names, matches(entry, "\n {4}" + shaderName + "_(\\S+)_ID = \\d+,"), shaderName + " IDs");
        assertEquals(names, matches(uniformStruct, " (\\S+);\n"), shaderName + " struct members");
        assertEquals(names, matches(entry, "numberWithInt:" + shaderName + "_(\\S+)_ID\\]"), shaderName + " ids[]");
        assertEquals(names, matches(entry, "@\"(\\S+)\","), shaderName + " dictionary keys");
    }

    private static String objCHeaderName(Kind kind) {
        return switch (kind) {
            case DECORA -> DECORA_HEADER;
            case PRISM -> PRISM_HEADER;
        };
    }

    private static List<String> matches(String text, String regex) {
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static void assertRegularFile(Path file) {
        assertTrue(Files.isRegularFile(file), "missing " + file);
    }

    private static void ageEveryFile(Path root) throws IOException {
        for (Path file : regularFiles(root)) {
            Files.setLastModifiedTime(file, AGED);
        }
    }

    private static Map<Path, FileState> snapshot(Path root) throws IOException {
        return snapshot(regularFiles(root));
    }

    private static Map<Path, FileState> snapshot(List<Path> files) throws IOException {
        Map<Path, FileState> states = new TreeMap<>();
        for (Path file : files) {
            states.put(file, new FileState(Files.getLastModifiedTime(file), Files.readString(file)));
        }
        return states;
    }

    private static List<Path> regularFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static void assertUnchanged(Map<Path, FileState> before, Map<Path, FileState> after) {
        assertEquals(before.keySet(), after.keySet());
        for (Map.Entry<Path, FileState> file : before.entrySet()) {
            FileState now = after.get(file.getKey());
            assertEquals(file.getValue().modified(), now.modified(), "time of " + file.getKey());
            assertEquals(file.getValue().content(), now.content(), "content of " + file.getKey());
        }
    }

    private record FileState(FileTime modified, String content) {
    }

    private record LegacyShader(String name, String program, String metalOf0281b2fde8, String... jslNames) {
    }

    // The headers the compiler of 0281b2fde8 wrote for SAMPLING, UNTEXTURED, ARRAYS, INT_AND_FLOAT4, PRECISIONS,
    // PAIRS and IDENTIFIER_CHARACTERS. Between them they have every kind of uniform the build's shaders declare, the
    // precisions that MSLBackend also writes into the struct, two uniforms of each type that the header spells
    // differently from the Metal shader, and a name with the first and last character of each range an identifier
    // may have. Off macOS this is the only check of their bytes.
    private static final String GOLDEN_FRAGMENT_HEADER = """
        #ifndef FRAGMENT_COMMON_H
        #define FRAGMENT_COMMON_H

        #pragma clang diagnostic ignored "-Wunused"

        #include <simd/simd.h>
        #include <metal_stdlib>

        using namespace metal;

        struct VS_OUTPUT {
            float4 position [[ position ]];
            float4 fragColor;
            float2 texCoord0;
            float2 texCoord1;
        };

        #endif
        """;

    private static final String GOLDEN_DECORA_HEADER = """
        #ifndef DECORA_SHADER_COMMON_H
        #define DECORA_SHADER_COMMON_H

        #import <Metal/Metal.h>
        #import <simd/simd.h>

        #ifdef MSL_BACKEND_VERBOSE
        #define MSL_LOG NSLog
        #else
        #define MSL_LOG(...)
        #endif

        typedef struct DECORA_VS_INPUT {
            packed_float2 position;
            packed_float4 color;
            packed_float2 texCoord0;
            packed_float2 texCoord1;
        } DECORA_VS_INPUT;

        typedef enum Textured_ArgumentBufferID {
            Textured_baseImg_ID = 0,
            Textured_offset_ID = 1,

        } TexturedArgumentBufferID;

        typedef struct Textured_Uniforms {
            id<MTLTexture> baseImg;
            packed_float2 offset;
        } Textured_Uniforms;

        NSDictionary* getTextured_Uniform_VarID_Dict() {
            id ids[] = {
                [NSNumber numberWithInt:Textured_baseImg_ID],
                [NSNumber numberWithInt:Textured_offset_ID],
            };

            NSUInteger count = sizeof(ids) / sizeof(id);
            NSArray *idArray = [NSArray arrayWithObjects:ids count:count];
            id uniforms[] = {
                @"baseImg",
                @"offset",
            };

            NSArray *uniformArray = [NSArray arrayWithObjects:uniforms count:count];
            return [NSDictionary dictionaryWithObjects:idArray forKeys:uniformArray];
        }


        typedef enum Untextured_ArgumentBufferID {
            Untextured_opacity_ID = 0,
            Untextured_UNUSED_ID = 1,

        } UntexturedArgumentBufferID;

        typedef struct Untextured_Uniforms {
            float opacity;
            id<MTLTexture> UNUSED;
        } Untextured_Uniforms;

        NSDictionary* getUntextured_Uniform_VarID_Dict() {
            id ids[] = {
                [NSNumber numberWithInt:Untextured_opacity_ID],
                [NSNumber numberWithInt:Untextured_UNUSED_ID],
            };

            NSUInteger count = sizeof(ids) / sizeof(id);
            NSArray *idArray = [NSArray arrayWithObjects:ids count:count];
            id uniforms[] = {
                @"opacity",
                @"UNUSED",
            };

            NSArray *uniformArray = [NSArray arrayWithObjects:uniforms count:count];
            return [NSDictionary dictionaryWithObjects:idArray forKeys:uniformArray];
        }


        typedef enum Arrays_ArgumentBufferID {
            Arrays_weights_ID = 0,
            Arrays_img_ID = 4,
            Arrays_lightColor_ID = 5,

        } ArraysArgumentBufferID;

        typedef struct Arrays_Uniforms {
            packed_float4 weights[4];
            id<MTLTexture> img;
            vector_float3 lightColor;
        } Arrays_Uniforms;

        NSDictionary* getArrays_Uniform_VarID_Dict() {
            id ids[] = {
                [NSNumber numberWithInt:Arrays_weights_ID],
                [NSNumber numberWithInt:Arrays_img_ID],
                [NSNumber numberWithInt:Arrays_lightColor_ID],
            };

            NSUInteger count = sizeof(ids) / sizeof(id);
            NSArray *idArray = [NSArray arrayWithObjects:ids count:count];
            id uniforms[] = {
                @"weights",
                @"img",
                @"lightColor",
            };

            NSArray *uniformArray = [NSArray arrayWithObjects:uniforms count:count];
            return [NSDictionary dictionaryWithObjects:idArray forKeys:uniformArray];
        }


        typedef enum IntAndFloat4_ArgumentBufferID {
            IntAndFloat4_count_ID = 0,
            IntAndFloat4_offset_ID = 1,
            IntAndFloat4_UNUSED_ID = 2,

        } IntAndFloat4ArgumentBufferID;

        typedef struct IntAndFloat4_Uniforms {
            int count;
            packed_float4 offset;
            id<MTLTexture> UNUSED;
        } IntAndFloat4_Uniforms;

        NSDictionary* getIntAndFloat4_Uniform_VarID_Dict() {
            id ids[] = {
                [NSNumber numberWithInt:IntAndFloat4_count_ID],
                [NSNumber numberWithInt:IntAndFloat4_offset_ID],
                [NSNumber numberWithInt:IntAndFloat4_UNUSED_ID],
            };

            NSUInteger count = sizeof(ids) / sizeof(id);
            NSArray *idArray = [NSArray arrayWithObjects:ids count:count];
            id uniforms[] = {
                @"count",
                @"offset",
                @"UNUSED",
            };

            NSArray *uniformArray = [NSArray arrayWithObjects:uniforms count:count];
            return [NSDictionary dictionaryWithObjects:idArray forKeys:uniformArray];
        }


        typedef enum Precisions_ArgumentBufferID {
            Precisions_opacity_ID = 0,
            Precisions_offset_ID = 1,
            Precisions_weights_ID = 2,
            Precisions_UNUSED_ID = 4,

        } PrecisionsArgumentBufferID;

        typedef struct Precisions_Uniforms {
            LOWP float opacity;
            HIGHP packed_float2 offset;
            MEDIUMP packed_float4 weights[2];
            id<MTLTexture> UNUSED;
        } Precisions_Uniforms;

        NSDictionary* getPrecisions_Uniform_VarID_Dict() {
            id ids[] = {
                [NSNumber numberWithInt:Precisions_opacity_ID],
                [NSNumber numberWithInt:Precisions_offset_ID],
                [NSNumber numberWithInt:Precisions_weights_ID],
                [NSNumber numberWithInt:Precisions_UNUSED_ID],
            };

            NSUInteger count = sizeof(ids) / sizeof(id);
            NSArray *idArray = [NSArray arrayWithObjects:ids count:count];
            id uniforms[] = {
                @"opacity",
                @"offset",
                @"weights",
                @"UNUSED",
            };

            NSArray *uniformArray = [NSArray arrayWithObjects:uniforms count:count];
            return [NSDictionary dictionaryWithObjects:idArray forKeys:uniformArray];
        }


        typedef enum Pairs_ArgumentBufferID {
            Pairs_botImg_ID = 0,
            Pairs_topImg_ID = 1,
            Pairs_shift_ID = 2,
            Pairs_scale_ID = 3,
            Pairs_tint_ID = 4,
            Pairs_glow_ID = 5,

        } PairsArgumentBufferID;

        typedef struct Pairs_Uniforms {
            id<MTLTexture> botImg;
            id<MTLTexture> topImg;
            packed_float2 shift;
            packed_float2 scale;
            packed_float4 tint;
            packed_float4 glow;
        } Pairs_Uniforms;

        NSDictionary* getPairs_Uniform_VarID_Dict() {
            id ids[] = {
                [NSNumber numberWithInt:Pairs_botImg_ID],
                [NSNumber numberWithInt:Pairs_topImg_ID],
                [NSNumber numberWithInt:Pairs_shift_ID],
                [NSNumber numberWithInt:Pairs_scale_ID],
                [NSNumber numberWithInt:Pairs_tint_ID],
                [NSNumber numberWithInt:Pairs_glow_ID],
            };

            NSUInteger count = sizeof(ids) / sizeof(id);
            NSArray *idArray = [NSArray arrayWithObjects:ids count:count];
            id uniforms[] = {
                @"botImg",
                @"topImg",
                @"shift",
                @"scale",
                @"tint",
                @"glow",
            };

            NSArray *uniformArray = [NSArray arrayWithObjects:uniforms count:count];
            return [NSDictionary dictionaryWithObjects:idArray forKeys:uniformArray];
        }


        typedef enum IdentifierCharacters_ArgumentBufferID {
            IdentifierCharacters_$zZ_aA09_ID = 0,
            IdentifierCharacters_UNUSED_ID = 1,

        } IdentifierCharactersArgumentBufferID;

        typedef struct IdentifierCharacters_Uniforms {
            float $zZ_aA09;
            id<MTLTexture> UNUSED;
        } IdentifierCharacters_Uniforms;

        NSDictionary* getIdentifierCharacters_Uniform_VarID_Dict() {
            id ids[] = {
                [NSNumber numberWithInt:IdentifierCharacters_$zZ_aA09_ID],
                [NSNumber numberWithInt:IdentifierCharacters_UNUSED_ID],
            };

            NSUInteger count = sizeof(ids) / sizeof(id);
            NSArray *idArray = [NSArray arrayWithObjects:ids count:count];
            id uniforms[] = {
                @"$zZ_aA09",
                @"UNUSED",
            };

            NSArray *uniformArray = [NSArray arrayWithObjects:uniforms count:count];
            return [NSDictionary dictionaryWithObjects:idArray forKeys:uniformArray];
        }


        NSDictionary* getDECORADict(NSString* inShaderName) {
            MSL_LOG(@"getDECORADict ");
            if ([inShaderName isEqualToString:@"Textured"]) {
                MSL_LOG(@"getDECORADict() : calling -> getTextured_Uniform_VarID_Dict()");
                return getTextured_Uniform_VarID_Dict();
            }
            if ([inShaderName isEqualToString:@"Untextured"]) {
                MSL_LOG(@"getDECORADict() : calling -> getUntextured_Uniform_VarID_Dict()");
                return getUntextured_Uniform_VarID_Dict();
            }
            if ([inShaderName isEqualToString:@"Arrays"]) {
                MSL_LOG(@"getDECORADict() : calling -> getArrays_Uniform_VarID_Dict()");
                return getArrays_Uniform_VarID_Dict();
            }
            if ([inShaderName isEqualToString:@"IntAndFloat4"]) {
                MSL_LOG(@"getDECORADict() : calling -> getIntAndFloat4_Uniform_VarID_Dict()");
                return getIntAndFloat4_Uniform_VarID_Dict();
            }
            if ([inShaderName isEqualToString:@"Precisions"]) {
                MSL_LOG(@"getDECORADict() : calling -> getPrecisions_Uniform_VarID_Dict()");
                return getPrecisions_Uniform_VarID_Dict();
            }
            if ([inShaderName isEqualToString:@"Pairs"]) {
                MSL_LOG(@"getDECORADict() : calling -> getPairs_Uniform_VarID_Dict()");
                return getPairs_Uniform_VarID_Dict();
            }
            if ([inShaderName isEqualToString:@"IdentifierCharacters"]) {
                MSL_LOG(@"getDECORADict() : calling -> getIdentifierCharacters_Uniform_VarID_Dict()");
                return getIdentifierCharacters_Uniform_VarID_Dict();
            }
            return nil;
        };

        #endif
        """;

    // The Metal shader the compiler of 0281b2fde8 wrote for HELPER_FUNCTIONS.
    private static final String GOLDEN_HELPERS_METAL = """
        #include "FragmentShaderCommon.h"

        typedef struct Helpers_Uniforms {
            texture2d<float> img;
            vector_float2 oinvarcradii;
            vector_float2 iinvarcradii;
        } Helpers_Uniforms;

        float4 Helpers_SampleTexture(sampler textureSampler, texture2d<float> colorTexture, float2 texCoord) {
            return colorTexture.sample(textureSampler, texCoord);
        }

        float Helpers_rrmask(sampler sampler0, device Helpers_Uniforms& uniforms,
        float2 arcreltco, float2 invarcradii) {
        float2 ecctco = arcreltco * invarcradii;
        return clamp(dot(ecctco, ecctco), 0.0, 1.0);
        }
        float Helpers_mask(sampler sampler0, device Helpers_Uniforms& uniforms,
        float2 tco, float2 flatdim) {
        float2 arcreltco = max(abs(tco) - flatdim, 0.001);
        float ocov = Helpers_rrmask(sampler0, uniforms, arcreltco, uniforms.oinvarcradii);
        float icov = Helpers_rrmask(sampler0, uniforms, arcreltco, uniforms.iinvarcradii);
        return ocov - icov;
        }
        float4 Helpers_paint(sampler sampler0, device Helpers_Uniforms& uniforms,
        float2 tco) {
        return Helpers_SampleTexture(sampler0, uniforms.img, tco);
        }

        [[fragment]] float4 Helpers(VS_OUTPUT in [[ stage_in ]],
            device Helpers_Uniforms& uniforms [[ buffer(0) ]],
            sampler sampler0 [[ sampler(0) ]]) {

        float4 outFragColor;
        float4 paintColor = Helpers_paint(sampler0, uniforms, in.texCoord0);
        float coverage = Helpers_mask(sampler0, uniforms, in.texCoord0, in.texCoord1);
        outFragColor = paintColor * coverage + Helpers_SampleTexture(sampler0, uniforms.img, in.texCoord1);
        return outFragColor;

        }
        """;

    // The Metal shaders the compiler of 0281b2fde8 wrote for A_FLOAT2_NAME, A_FLOAT3_NAME, A_FLOAT4_NAME,
    // A_UNIFORM_NAMED_LIKE_A_HELPER, NAMES_AND_PARTS_NAMED_LIKE_FUNCTIONS, A_PART_NAMED_LIKE_A_HELPER,
    // SAMPLE_TEX_BESIDE_A_SAMPLER, A_HELPER_NAMED_LIKE_THE_TEXTURE_TYPE and FLOAT2_NAMES_AND_A_DROPPED_UNIFORM, under
    // the shader names Scale, Glow, Tint, Helper, Parts, Dollar, Sampled, Typed and Dropped. The compiler of a8d6688172
    // wrote the same bytes.
    private static final String SCALE_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Scale_Uniforms {
            vector_float2 vector_float2Scale;
            float vector_float2x;
            float opacity;
            texture2d<float> UNUSED;
        } Scale_Uniforms;


        [[fragment]] float4 Scale(VS_OUTPUT in [[ stage_in ]],
            device Scale_Uniforms& uniforms [[ buffer(0) ]]) {

        float4 outFragColor;
        outFragColor = float4(uniforms.opacity, uniforms.opacity, uniforms.opacity, uniforms.opacity);
        return outFragColor;

        }
        """;

    private static final String GLOW_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Glow_Uniforms {
            vector_float3 vector_float3Glow;
            float opacity;
            texture2d<float> UNUSED;
        } Glow_Uniforms;


        [[fragment]] float4 Glow(VS_OUTPUT in [[ stage_in ]],
            device Glow_Uniforms& uniforms [[ buffer(0) ]]) {

        float4 outFragColor;
        outFragColor = float4(uniforms.opacity, uniforms.opacity, uniforms.opacity, uniforms.opacity);
        return outFragColor;

        }
        """;

    private static final String TINT_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Tint_Uniforms {
            float opacity;
            vector_float4 vector_float4Tint;
            texture2d<float> UNUSED;
        } Tint_Uniforms;


        [[fragment]] float4 Tint(VS_OUTPUT in [[ stage_in ]],
            device Tint_Uniforms& uniforms [[ buffer(0) ]]) {

        float4 outFragColor;
        outFragColor = float4(uniforms.opacity, uniforms.opacity, uniforms.opacity, uniforms.opacity);
        return outFragColor;

        }
        """;

    private static final String HELPER_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Helper_Uniforms {
            float Helper_opacity;
            vector_float2 offset;
            texture2d<float> UNUSED;
        } Helper_Uniforms;

        float Helper_opacity(device Helper_Uniforms& uniforms,
        float x) {
        return x * 0.5;
        }

        [[fragment]] float4 Helper(VS_OUTPUT in [[ stage_in ]],
            device Helper_Uniforms& uniforms [[ buffer(0) ]]) {

        float4 outFragColor;
        float o = Helper_opacity(uniforms, uniforms.Helper_opacity);
        outFragColor = float4(o, uniforms.offset.x, uniforms.offset.y, 1.0);
        return outFragColor;

        }
        """;

    private static final String PARTS_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Parts_Uniforms {
            float level$Parts_gain;
            float Parts_gain$level;
            float Parts_main;
            texture2d<float> Parts_UNUSED;
        } Parts_Uniforms;

        float Parts_gain(device Parts_Uniforms& uniforms,
        float x) {
        return x * 2.0;
        }
        float Parts_UNUSED(device Parts_Uniforms& uniforms,
        float x) {
        return x;
        }

        [[fragment]] float4 Parts(VS_OUTPUT in [[ stage_in ]],
            device Parts_Uniforms& uniforms [[ buffer(0) ]]) {

        float4 outFragColor;
        float v = Parts_gain(uniforms, Parts_UNUSED(uniforms, 0.5));
        outFragColor = float4(v, v, v, 1.0);
        return outFragColor;

        }
        """;

    private static final String DOLLAR_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Dollar_Uniforms {
            float level$Dollar_gain;
            float opacity;
            texture2d<float> UNUSED;
        } Dollar_Uniforms;

        float Dollar_gain(device Dollar_Uniforms& uniforms,
        float x) {
        return x * 2.0;
        }

        [[fragment]] float4 Dollar(VS_OUTPUT in [[ stage_in ]],
            device Dollar_Uniforms& uniforms [[ buffer(0) ]]) {

        float4 outFragColor;
        float v = Dollar_gain(uniforms, uniforms.opacity);
        outFragColor = float4(v, v, v, 1.0);
        return outFragColor;

        }
        """;

    private static final String SAMPLED_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Sampled_Uniforms {
            texture2d<float> img;
            float Sampled_SampleTexture;
        } Sampled_Uniforms;

        float4 Sampled_SampleTexture(sampler textureSampler, texture2d<float> colorTexture, float2 texCoord) {
            return colorTexture.sample(textureSampler, texCoord);
        }


        [[fragment]] float4 Sampled(VS_OUTPUT in [[ stage_in ]],
            device Sampled_Uniforms& uniforms [[ buffer(0) ]],
            sampler sampler0 [[ sampler(0) ]]) {

        float4 outFragColor;
        outFragColor = Sampled_SampleTexture(sampler0, uniforms.img, in.texCoord0);
        return outFragColor;

        }
        """;

    private static final String TYPED_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Typed_Uniforms {
            Typed_texture2d<float> img;
            float opacity;
        } Typed_Uniforms;

        float4 Typed_SampleTexture(sampler textureSampler, Typed_texture2d<float> colorTexture, float2 texCoord) {
            return colorTexture.sample(textureSampler, texCoord);
        }

        float4 Typed_texture2d(sampler sampler0, device Typed_Uniforms& uniforms,
        float2 tco) {
        return Typed_SampleTexture(sampler0, uniforms.img, tco) * uniforms.opacity;
        }

        [[fragment]] float4 Typed(VS_OUTPUT in [[ stage_in ]],
            device Typed_Uniforms& uniforms [[ buffer(0) ]],
            sampler sampler0 [[ sampler(0) ]]) {

        float4 outFragColor;
        outFragColor = Typed_texture2d(sampler0, uniforms, in.texCoord0);
        return outFragColor;

        }
        """;

    private static final String DROPPED_METAL_OF_0281B2FDE8 = """
        #include "FragmentShaderCommon.h"

        typedef struct Dropped_Uniforms {
            float vector_float2x;
            float vector_float2y;
            texture2d<float> UNUSED;
        } Dropped_Uniforms;


        [[fragment]] float4 Dropped(VS_OUTPUT in [[ stage_in ]],
            device Dropped_Uniforms& uniforms [[ buffer(0) ]]) {

        float4 outFragColor;
        outFragColor = float4(0.0, 0.0, 0.0, 0.0);
        return outFragColor;

        }
        """;
}
