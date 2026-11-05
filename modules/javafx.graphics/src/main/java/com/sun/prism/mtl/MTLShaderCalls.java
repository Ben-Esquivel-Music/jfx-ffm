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

package com.sun.prism.mtl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static com.sun.prism.mtl.MTLNative.bind;
import static com.sun.prism.mtl.MTLNative.bindCritical;
import static com.sun.prism.mtl.MTLNative.directBase;
import static com.sun.prism.mtl.MTLNative.flag;
import static com.sun.prism.mtl.MTLNative.seg;
import static com.sun.prism.mtl.MTLNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** The {@code mtl_shader_*} functions: the natives of {@code MTLShader}, whose JNI bodies were in MetalShader.m. */
final class MTLShaderCalls {

    private static final MethodHandle MTL_SHADER_CREATE = bind("mtl_shader_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle MTL_SHADER_UNIFORM_COUNT = bind("mtl_shader_uniform_count",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle MTL_SHADER_UNIFORM_TABLE = bind("mtl_shader_uniform_table",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle MTL_SHADER_ENABLE = bind("mtl_shader_enable",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle MTL_SHADER_DISABLE = bind("mtl_shader_disable",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle MTL_SHADER_SET_TEXTURE = bind("mtl_shader_set_texture",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_SHADER_SET_INT = bind("mtl_shader_set_int",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_SHADER_SET_FLOAT1 = bind("mtl_shader_set_float1",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_FLOAT));
    private static final MethodHandle MTL_SHADER_SET_FLOAT2 = bind("mtl_shader_set_float2",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle MTL_SHADER_SET_FLOAT3 = bind("mtl_shader_set_float3",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle MTL_SHADER_SET_FLOAT4 = bind("mtl_shader_set_float4",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    // A memcpy of float_count floats into the argument buffer: short, and it cannot block.
    private static final MethodHandle MTL_SHADER_SET_CONSTANTS = bindCritical("mtl_shader_set_constants",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    private MTLShaderCalls() {
    }

    /** {@code MTLShader.nCreateMetalShader}: the name as UTF-16, as {@code GetStringChars} gave it. */
    static long shaderCreate(long ctx, String fragmentFunctionName) {
        if (fragmentFunctionName == null) {
            return create(ctx, MemorySegment.NULL, 0);
        }
        try (Arena call = Arena.ofConfined()) {
            return create(ctx, call.allocateFrom(JAVA_CHAR, fragmentFunctionName.toCharArray()),
                    fragmentFunctionName.length());
        }
    }

    /**
     * {@code MTLShader.nGetUniformNameIdMap}: the map the C built with {@code HashMap.put} upcalls, built here in the
     * same order from the same entries. {@code null} for a {@code NULL} shader, whose nil receiver answered nil.
     */
    static Map<String, Integer> shaderUniformNameIdMap(long shader) {
        if (shader == 0) {
            return null;
        }
        MemorySegment receiver = seg(shader);
        try (Arena call = Arena.ofConfined()) {
            int count = (int) MTL_SHADER_UNIFORM_COUNT.invokeExact(receiver);
            int namesSize = uniformTable(receiver, MemorySegment.NULL, 0, MemorySegment.NULL, 0);
            MemorySegment ids = call.allocate(JAVA_INT, count);
            MemorySegment names = call.allocate(namesSize);
            uniformTable(receiver, ids, count, names, namesSize);
            Map<String, Integer> map = new HashMap<>();
            long offset = 0;
            for (int i = 0; i < count; i++) {
                String name = names.getString(offset);
                map.put(name, ids.getAtIndex(JAVA_INT, i));
                offset += name.getBytes(StandardCharsets.UTF_8).length + 1;
            }
            return map;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nEnable}. */
    static void shaderEnable(long shader) {
        try {
            MTL_SHADER_ENABLE.invokeExact(seg(shader));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nDisable}. */
    static void shaderDisable(long shader) {
        try {
            MTL_SHADER_DISABLE.invokeExact(seg(shader));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nSetTexture}. */
    static void shaderSetTexture(long shader, int texUnit, int uniformId, long tex, boolean linear, int wrapMode) {
        try {
            MTL_SHADER_SET_TEXTURE.invokeExact(seg(shader), texUnit, uniformId, seg(tex), flag(linear), wrapMode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nSetInt}. */
    static void shaderSetInt(long shader, int uniformId, int i0) {
        try {
            MTL_SHADER_SET_INT.invokeExact(seg(shader), uniformId, i0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nSetFloat1}. */
    static void shaderSetFloat1(long shader, int uniformId, float f0) {
        try {
            MTL_SHADER_SET_FLOAT1.invokeExact(seg(shader), uniformId, f0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nSetFloat2}. */
    static void shaderSetFloat2(long shader, int uniformId, float f0, float f1) {
        try {
            MTL_SHADER_SET_FLOAT2.invokeExact(seg(shader), uniformId, f0, f1);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nSetFloat3}. */
    static void shaderSetFloat3(long shader, int uniformId, float f0, float f1, float f2) {
        try {
            MTL_SHADER_SET_FLOAT3.invokeExact(seg(shader), uniformId, f0, f1, f2);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nSetFloat4}. */
    static void shaderSetFloat4(long shader, int uniformId, float f0, float f1, float f2, float f3) {
        try {
            MTL_SHADER_SET_FLOAT4.invokeExact(seg(shader), uniformId, f0, f1, f2, f3);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nSetConstants}: the array is read in place, where the JNI read a copy of it. */
    static void shaderSetConstants(long shader, int uniformId, float[] values, int floatCount) {
        try {
            MTL_SHADER_SET_CONSTANTS.invokeExact(seg(shader), uniformId, MemorySegment.ofArray(values), floatCount);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLShader.nSetConstantsBuf}: {@code byteOffset} bytes past the base of the direct buffer. */
    static void shaderSetConstantsBuf(long shader, int uniformId, FloatBuffer values, int byteOffset,
                                      int floatCount) {
        try {
            MTL_SHADER_SET_CONSTANTS.invokeExact(seg(shader), uniformId, directBase(values).asSlice(byteOffset),
                    floatCount);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static long create(long ctx, MemorySegment nameUtf16, int length) {
        try {
            return ((MemorySegment) MTL_SHADER_CREATE.invokeExact(seg(ctx), nameUtf16, length)).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int uniformTable(MemorySegment shader, MemorySegment ids, int idsCapacity, MemorySegment names,
                                    int namesCapacity) throws Throwable {
        return (int) MTL_SHADER_UNIFORM_TABLE.invokeExact(shader, ids, idsCapacity, names, namesCapacity);
    }
}
