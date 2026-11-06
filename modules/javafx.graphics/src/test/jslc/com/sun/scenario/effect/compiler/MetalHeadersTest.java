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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.sun.scenario.effect.compiler.backend.hw.MetalHeaders.Kind.DECORA;
import static com.sun.scenario.effect.compiler.backend.hw.MetalHeaders.Kind.PRISM;
import static org.junit.jupiter.api.Assertions.*;

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

    private static final String NOT_JSL = "this is not JSL";

    private static final String FRAGMENT_HEADER = "FragmentShaderCommon.h";
    private static final String DECORA_HEADER = "DecoraShaderCommon.h";
    private static final String PRISM_HEADER = "PrismShaderCommon.h";

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
    public void aRunWithNothingOutOfDateParsesAndRewritesNothing() throws Exception {
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

        Path cleanGensrc = tmp.resolve("clean/gensrc");
        JSLCInfo clean = newRun(cleanGensrc.resolve("jsl-decora"), cleanGensrc.resolve("mtl-headers"), DECORA);
        compile(clean, "ProbeA", UNTEXTURED_WITH_OFFSET, OUT_OF_DATE);
        compile(clean, "ProbeB", UNTEXTURED, OUT_OF_DATE);
        finish(clean);

        String cleanHeader = Files.readString(cleanGensrc.resolve("mtl-headers").resolve(DECORA_HEADER));
        assertNotEquals(firstRunHeader, cleanHeader);
        assertEquals(cleanHeader, Files.readString(headerDir.resolve(DECORA_HEADER)));
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
        finish(run);

        assertEquals(GOLDEN_FRAGMENT_HEADER, Files.readString(headerDir.resolve(FRAGMENT_HEADER)));
        assertEquals(GOLDEN_DECORA_HEADER, Files.readString(headerDir.resolve(DECORA_HEADER)));
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

    // The headers the compiler of 0281b2fde8 wrote for SAMPLING, UNTEXTURED and ARRAYS; off macOS this is the
    // only check of their bytes.
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
            return nil;
        };

        #endif
        """;
}
