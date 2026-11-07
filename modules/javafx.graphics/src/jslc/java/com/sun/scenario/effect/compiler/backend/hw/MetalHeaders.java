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

package com.sun.scenario.effect.compiler.backend.hw;

import com.sun.scenario.effect.compiler.model.Precision;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The Metal headers of one generator run: {@code FragmentShaderCommon.h} and the Objective-C header through
 * which {@code MetalShader.m} looks up the uniforms of every fragment shader, so it lists every Metal shader
 * of the run, up to date or not, in the order the run added them. {@code JSLC.compile} adds the shaders;
 * {@link #write} writes the headers after the run's last compile.
 * <p>
 * A shader's entry in the Objective-C header is read from the uniform struct in its {@code .metal} file, which
 * the Metal library is built from, rather than computed from its program. So the program of a shader whose
 * output is up to date is not parsed, and the header holds exactly the entries of the run's {@code .metal}
 * files: an entry that another output root or a run that stopped before {@link #write} left in the file is
 * replaced. The one exception is an up-to-date output whose struct may hold a uniform under another name than
 * its JSL name, as an earlier {@code MSLBackend} wrote some: that shader is compiled again, and its entry is read
 * from the result.
 */
public final class MetalHeaders {

    public enum Kind {
        DECORA("DecoraShaderCommon.h"),
        PRISM("PrismShaderCommon.h");

        private final String fileName;

        Kind(String fileName) {
            this.fileName = fileName;
        }
    }

    private static final String FRAGMENT_SHADER_HEADER_FILE_NAME = "FragmentShaderCommon.h";

    private static final String FRAGMENT_SHADER_HEADER = """
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

    private final Map<String, String> entries = new LinkedHashMap<>();
    private Path dir;
    private Kind kind;

    /**
     * Sets the directory and kind of the run's headers. Every Metal shader of the run must give the same kind and a
     * path to the same directory: the one the file system resolves the path to, as for every other output of JSLC.
     * The path is not normalized, which would drop a {@code link/..} as text where the file system goes to the
     * parent of the link's target.
     */
    public void target(File headerDir, Kind headerKind) throws IOException {
        Path headerPath = headerDir.toPath().toAbsolutePath();
        if (dir == null) {
            dir = headerPath;
            kind = headerKind;
        } else if (!namesDir(headerPath) || kind != headerKind) {
            throw new IllegalStateException("This run writes the " + kind.fileName + " in " + dir
                    + ", not the " + headerKind.fileName + " in " + headerPath);
        }
    }

    // Only a path that differs as text is resolved, because every Metal shader of the run gives one, 212 in each
    // Prism JVM, and resolving a path costs file system lookups.
    private boolean namesDir(Path headerPath) throws IOException {
        return dir.equals(headerPath)
                || dir.toFile().getCanonicalFile().equals(headerPath.toFile().getCanonicalFile());
    }

    /** Adds a shader of the run that was just compiled into {@code metalShader}, which went to {@code metalFile}. */
    public void add(String shaderName, File metalFile, String metalShader) throws IOException {
        addEntry(shaderName, uniformStruct(shaderName, metalFile, metalShader));
    }

    /**
     * Adds a shader of the run from its up-to-date Metal output {@code metalFile}, unless a member of its uniform
     * struct may have a name that an earlier {@code MSLBackend} gave the uniform instead of its JSL name. Returns
     * whether it added the shader; if not, the caller compiles the shader again and adds it with
     * {@link #add(String, File, String)}.
     */
    public boolean addUnlessRenamed(String shaderName, File metalFile) throws IOException {
        UniformStruct uniformStruct = uniformStruct(shaderName, metalFile, readShader(metalFile));
        if (uniformStruct.mayBeRenamed(shaderName)) {
            return false;
        }
        addEntry(shaderName, uniformStruct);
        return true;
    }

    /**
     * Returns how every Metal shader that {@code MSLBackend} writes starts: the include line, an empty line and the
     * first line of the uniform struct.
     */
    static String metalShaderStart(String shaderName) {
        return "#include \"" + FRAGMENT_SHADER_HEADER_FILE_NAME + "\"\n\n"
                + "typedef struct " + shaderName + "_Uniforms {\n";
    }

    /** Returns the last line of the uniform struct of a Metal shader that {@code MSLBackend} writes. */
    static String uniformStructEnd(String shaderName) {
        return "} " + shaderName + "_Uniforms;\n";
    }

    private void addEntry(String shaderName, UniformStruct uniformStruct) {
        if (entries.putIfAbsent(shaderName, objCHeaderEntry(shaderName, uniformStruct)) != null) {
            throw new IllegalStateException("Shader " + shaderName + " was added twice in one run");
        }
    }

    /** Writes each header whose bytes differ from the file. */
    public void write() throws IOException {
        if (entries.isEmpty()) {
            return;
        }
        createDirectory();
        writeIfChanged(dir.resolve(FRAGMENT_SHADER_HEADER_FILE_NAME), FRAGMENT_SHADER_HEADER);
        writeIfChanged(dir.resolve(kind.fileName),
                prologue() + String.join("", entries.values()) + shaderDictFunction() + "#endif\n");
    }

    /**
     * Returns the uniform struct that {@code MSLBackend} wrote into the Metal source of the shader. A source that
     * starts in any other way, or a member line in another form than {@code MSLBackend} writes, as a hand edit can
     * give them, makes the shader unreadable instead of getting a guessed entry, because {@code MetalShader.m} sets
     * each uniform through the argument buffer ID that the entry gives it.
     */
    private static UniformStruct uniformStruct(String shaderName, File metalFile, String shader) throws IOException {
        String start = metalShaderStart(shaderName);
        if (!shader.startsWith(start)) {
            throw cannotReadUniforms(shaderName, metalFile);
        }
        int end = shader.indexOf("\n" + uniformStructEnd(shaderName), start.length() - 1);
        if (end < 0) {
            throw cannotReadUniforms(shaderName, metalFile);
        }
        String members = shader.substring(start.length(), end + 1);
        List<Uniform> uniforms = new ArrayList<>();
        for (String member : members.split("\n")) {
            Uniform uniform = uniform(member);
            if (uniform == null) {
                throw cannotReadUniforms(shaderName, metalFile);
            }
            uniforms.add(uniform);
        }
        return new UniformStruct(members, uniforms);
    }

    /** The member lines of a uniform struct and the uniforms they declare. */
    private record UniformStruct(String members, List<Uniform> uniforms) {

        /**
         * Returns whether a member may have another name than its uniform's JSL name. An earlier {@code MSLBackend}
         * put {@code vector_} before a name that starts with {@code float2}, {@code float3} or {@code float4}. It
         * also put {@code <Shader>_} before each word of the struct that names a function the program defines, be it
         * a name, a part of a name that a {@code $} separates or a type, and turned {@code sampleTex} into
         * {@code <Shader>_SampleTexture}.
         */
        boolean mayBeRenamed(String shaderName) {
            if (members.contains(shaderName + "_")) {
                return true;
            }
            for (Uniform uniform : uniforms) {
                String name = uniform.name();
                if (name.startsWith("vector_float2") || name.startsWith("vector_float3")
                        || name.startsWith("vector_float4")) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Uniform(String name, int idCount) {
    }

    /**
     * Returns the shader's argument IDs, its uniform struct with the Objective-C types and its uniform
     * dictionary function.
     */
    private static String objCHeaderEntry(String shaderName, UniformStruct uniformStruct) {
        String uniformStructName = shaderName + "_Uniforms";
        StringBuilder uniformIDs = new StringBuilder();
        int uniformIDCount = 0;
        for (Uniform uniform : uniformStruct.uniforms()) {
            uniformIDs.append("    " + shaderName + "_" + uniform.name() + "_ID = " + uniformIDCount + ",\n");
            uniformIDCount += uniform.idCount();
        }
        // The Objective-C types the header had when its entries were computed from the programs: float2 and
        // float4 packed, float3 left as the vector_float3 that MSLBackend writes. As in MSLBackend, the space after
        // the type leaves a name like vector_float2x alone.
        String uniformsForObjCFiles = uniformStruct.members().replace("texture2d<float>", "id<MTLTexture>")
                .replace(" vector_float2 ", " packed_float2 ")
                .replace(" vector_float4 ", " packed_float4 ");

        StringBuilder objCHeader = new StringBuilder();
        objCHeader.append("typedef enum " + shaderName + "_ArgumentBufferID {\n" + uniformIDs + "\n} "
                + shaderName + "ArgumentBufferID;\n\n");
        objCHeader.append("typedef struct " + uniformStructName + " {\n" + uniformsForObjCFiles + "} "
                + uniformStructName + ";\n\n");
        objCHeader.append("NSDictionary* get" + shaderName + "_Uniform_VarID_Dict() {\n");
        objCHeader.append("    id ids[] = {\n");
        for (Uniform uniform : uniformStruct.uniforms()) {
            objCHeader.append("        [NSNumber numberWithInt:" + shaderName + "_" + uniform.name() + "_ID],\n");
        }
        objCHeader.append("    };\n\n");
        objCHeader.append("    NSUInteger count = sizeof(ids) / sizeof(id);\n");
        objCHeader.append("    NSArray *idArray = [NSArray arrayWithObjects:ids count:count];\n");
        objCHeader.append("    id uniforms[] = {\n");
        for (Uniform uniform : uniformStruct.uniforms()) {
            objCHeader.append("        @\"" + uniform.name() + "\",\n");
        }
        objCHeader.append("    };\n\n");
        objCHeader.append("    NSArray *uniformArray = [NSArray arrayWithObjects:uniforms count:count];\n");
        objCHeader.append("    return [NSDictionary dictionaryWithObjects:idArray forKeys:uniformArray];\n");
        objCHeader.append("}\n\n\n");
        return objCHeader.toString();
    }

    /**
     * Returns the uniform that a member line of the struct declares, or null unless the line has the form
     * {@code MSLBackend} writes: four spaces, an optional precision, a type, a name with an optional array size,
     * and a semicolon.
     */
    private static Uniform uniform(String member) {
        if (!member.startsWith("    ") || !member.endsWith(";")) {
            return null;
        }
        int typeStart = 4;
        for (Precision precision : Precision.values()) {
            if (member.startsWith(precision.name() + " ", typeStart)) {
                typeStart += precision.name().length() + 1;
                break;
            }
        }
        int typeEnd = member.indexOf(' ', typeStart);
        int declaratorEnd = member.length() - 1;
        int arrayStart = member.indexOf('[', typeEnd + 1);
        int nameEnd = arrayStart < 0 ? declaratorEnd : arrayStart;
        // The name is the uniform's JSL identifier, which may also contain '$'.
        if (typeEnd < 0 || !isWord(member, typeStart, typeEnd, "<>") || !isWord(member, typeEnd + 1, nameEnd, "$")) {
            return null;
        }
        String name = member.substring(typeEnd + 1, nameEnd);
        if (arrayStart < 0) {
            return new Uniform(name, 1);
        }
        if (member.charAt(declaratorEnd - 1) != ']') {
            return null;
        }
        // An array takes one argument buffer ID per element. The size must read back unchanged, because MSL
        // reads a leading 0 as octal.
        String size = member.substring(arrayStart + 1, declaratorEnd - 1);
        try {
            int idCount = Integer.parseInt(size);
            return idCount > 0 && size.equals(Integer.toString(idCount)) ? new Uniform(name, idCount) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns whether {@code text[start, end)} is an ASCII identifier that may also contain the other chars. */
    private static boolean isWord(String text, int start, int end, String otherChars) {
        if (start >= end || isDigit(text.charAt(start))) {
            return false;
        }
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (!(c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || isDigit(c)
                    || otherChars.indexOf(c) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // A rerun reads every up-to-date .metal file, all 212 in each Prism JVM when nothing changed, and a fresh JVM
    // reads them about twice as fast through FileInputStream as through Files.readString.
    private static String readShader(File metalFile) throws IOException {
        try (FileInputStream in = new FileInputStream(metalFile)) {
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    private static IOException cannotReadUniforms(String shaderName, File metalFile) {
        return new IOException("Cannot read " + shaderName + "_Uniforms from the Metal shader " + metalFile);
    }

    private void createDirectory() throws IOException {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IOException("Cannot create the Metal header directory " + dir, e);
        }
    }

    private static void writeIfChanged(Path file, String content) throws IOException {
        byte[] bytes = content.getBytes(UTF_8);
        if (Arrays.equals(readIfRegularFile(file), bytes)) {
            return;
        }
        try {
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new IOException("Cannot write the Metal header " + file, e);
        }
    }

    private static byte[] readIfRegularFile(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    private String prologue() {
        String shaderType = kind.name();
        return "#ifndef " + shaderType + "_SHADER_COMMON_H\n" +
                "#define " + shaderType + "_SHADER_COMMON_H\n\n" +
                "#import <Metal/Metal.h>\n" +
                "#import <simd/simd.h>\n\n" +
                "#ifdef MSL_BACKEND_VERBOSE\n" +
                "#define MSL_LOG NSLog\n" +
                "#else\n" +
                "#define MSL_LOG(...)\n" +
                "#endif\n\n" +
                "typedef struct " + shaderType + "_VS_INPUT {\n" +
                "    packed_float2 position;\n" +
                "    packed_float4 color;\n" +
                "    packed_float2 texCoord0;\n" +
                "    packed_float2 texCoord1;\n" +
                "} " + shaderType + "_VS_INPUT;" +
                "\n\n";
    }

    private String shaderDictFunction() {
        String shaderType = kind.name();
        StringBuilder getShaderDictFunc = new StringBuilder();
        getShaderDictFunc.append("NSDictionary* get" + shaderType + "Dict(NSString* inShaderName) {\n");
        getShaderDictFunc.append("    MSL_LOG(@\"get" + shaderType + "Dict \");\n");
        for (String aShaderName : entries.keySet()) {
            getShaderDictFunc.append("    if ([inShaderName isEqualToString:@\"" + aShaderName + "\"]) {\n");
            getShaderDictFunc.append("        MSL_LOG(@\"get" + shaderType + "Dict() : calling -> get" + aShaderName
                    + "_Uniform_VarID_Dict()\");\n");
            getShaderDictFunc.append("        return get" + aShaderName + "_Uniform_VarID_Dict();\n");
            getShaderDictFunc.append("    }\n");
        }
        getShaderDictFunc.append("    return nil;\n");
        getShaderDictFunc.append("};\n\n");
        return getShaderDictFunc.toString();
    }
}
