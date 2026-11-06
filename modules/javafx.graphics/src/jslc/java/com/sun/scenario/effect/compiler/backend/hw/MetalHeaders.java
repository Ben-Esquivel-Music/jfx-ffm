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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The Metal headers of one generator run: {@code FragmentShaderCommon.h} and the Objective-C header through
 * which {@code MetalShader.m} looks up the uniforms of every fragment shader, so it lists every Metal shader
 * of the run, up to date or not, in the order the run compiled them. {@code JSLC.compile} adds the shaders;
 * {@link #write} writes the headers after the run's last compile.
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

    static final String FRAGMENT_SHADER_HEADER_FILE_NAME = "FragmentShaderCommon.h";

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

    private final Map<String, Callable<String>> entries = new LinkedHashMap<>();
    private Path dir;
    private Kind kind;
    private boolean shaderCompiled;

    public void target(File headerDir, Kind headerKind) {
        Path headerPath = headerDir.toPath().toAbsolutePath().normalize();
        if (dir == null) {
            dir = headerPath;
            kind = headerKind;
        } else if (!dir.equals(headerPath) || kind != headerKind) {
            throw new IllegalStateException("This run writes the " + kind.fileName + " in " + dir
                    + ", not the " + headerKind.fileName + " in " + headerPath);
        }
    }

    public void addCompiled(String shaderName, String entry) {
        add(shaderName, () -> entry);
        shaderCompiled = true;
    }

    /** Adds a shader whose Metal output is up to date; its entry is computed only if the header is assembled. */
    public void addUpToDate(String shaderName, Callable<String> entry) {
        add(shaderName, entry);
    }

    private void add(String shaderName, Callable<String> entry) {
        if (entries.putIfAbsent(shaderName, entry) != null) {
            throw new IllegalStateException("Shader " + shaderName + " was compiled twice in one run");
        }
    }

    /**
     * Writes each header whose bytes differ from the file. The Objective-C header is assembled again only if a
     * shader of the run was compiled, or if the file does not start and end as this run's header does.
     */
    public void write() throws Exception {
        if (entries.isEmpty()) {
            return;
        }
        Path objCHeader = dir.resolve(kind.fileName);
        String prologue = prologue();
        String epilogue = shaderDictFunction() + "#endif\n";
        boolean current = !shaderCompiled && listsThisRun(objCHeader, prologue, epilogue);
        String content = current ? null : prologue + assembleEntries() + epilogue;
        createDirectory();
        writeIfChanged(dir.resolve(FRAGMENT_SHADER_HEADER_FILE_NAME), FRAGMENT_SHADER_HEADER);
        if (content != null) {
            writeIfChanged(objCHeader, content);
        }
    }

    private static boolean listsThisRun(Path objCHeader, String prologue, String epilogue) throws IOException {
        byte[] bytes = readIfRegularFile(objCHeader);
        if (bytes == null) {
            return false;
        }
        String text = new String(bytes, UTF_8);
        return text.startsWith(prologue) && text.endsWith(epilogue);
    }

    private String assembleEntries() throws Exception {
        StringBuilder assembled = new StringBuilder();
        for (Callable<String> entry : entries.values()) {
            assembled.append(entry.call());
        }
        return assembled.toString();
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
