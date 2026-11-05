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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandle;
import java.util.Collections;
import static com.sun.prism.mtl.MTLNative.bind;
import static com.sun.prism.mtl.MTLNative.flag;
import static com.sun.prism.mtl.MTLNative.seg;
import static com.sun.prism.mtl.MTLNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The {@code mtl_mesh_*}, {@code mtl_phong_material_*} and {@code mtl_mesh_view_*} functions: the 3D natives of
 * {@code MTLContext}, whose JNI bodies were in {@code MetalContext.m}. The {@code pContext} those natives took and
 * did not use is gone.
 */
final class MTLMeshCalls {

    /** Position, colour, direction, attenuation, range and spot parameters of {@code mtl_mesh_view_set_light}. */
    private static final MemoryLayout[] LIGHT = Collections.nCopies(18, JAVA_FLOAT).toArray(new MemoryLayout[0]);

    private static final MethodHandle MTL_MESH_CREATE = bind("mtl_mesh_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle MTL_MESH_BUILD_GEOMETRY_SHORT = bind("mtl_mesh_build_geometry_short",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_MESH_BUILD_GEOMETRY_INT = bind("mtl_mesh_build_geometry_int",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_PHONG_MATERIAL_CREATE = bind("mtl_phong_material_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle MTL_PHONG_MATERIAL_SET_DIFFUSE_COLOR = bind(
            "mtl_phong_material_set_diffuse_color",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle MTL_PHONG_MATERIAL_SET_SPECULAR_COLOR = bind(
            "mtl_phong_material_set_specular_color",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle MTL_PHONG_MATERIAL_SET_MAP = bind("mtl_phong_material_set_map",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle MTL_MESH_VIEW_CREATE = bind("mtl_mesh_view_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle MTL_MESH_VIEW_SET_CULLING_MODE = bind("mtl_mesh_view_set_culling_mode",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle MTL_MESH_VIEW_SET_MATERIAL = bind("mtl_mesh_view_set_material",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle MTL_MESH_VIEW_SET_WIREFRAME = bind("mtl_mesh_view_set_wireframe",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle MTL_MESH_VIEW_SET_AMBIENT_LIGHT = bind("mtl_mesh_view_set_ambient_light",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    private static final MethodHandle MTL_MESH_VIEW_SET_LIGHT = bind("mtl_mesh_view_set_light",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT).appendArgumentLayouts(LIGHT));
    private static final MethodHandle MTL_MESH_VIEW_RENDER = bind("mtl_mesh_view_render",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    private MTLMeshCalls() {
    }

    /** {@code MTLContext.nCreateMTLMesh}. */
    static long meshCreate(long ctx) {
        try {
            return ((MemorySegment) MTL_MESH_CREATE.invokeExact(seg(ctx))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code MTLContext.nBuildNativeGeometryShort}. The capacities are the array lengths, as {@code GetArrayLength}
     * gave them, and the C checks the lengths against them; at most the elements it can read are copied.
     */
    static boolean meshBuildGeometryShort(MTLScratch scratch, long mesh, float[] vertexBuffer,
                                          int vertexBufferLength, short[] indexBuffer, int indexBufferLength) {
        int vertexCount = Math.clamp(vertexBufferLength, 0, vertexBuffer.length);
        int indexCount = Math.clamp(indexBufferLength, 0, indexBuffer.length);
        SegmentAllocator copies = scratch.begin(Float.BYTES * (long) vertexCount + Short.BYTES * (long) indexCount);
        try {
            MemorySegment vb = copies.allocateFrom(JAVA_FLOAT, MemorySegment.ofArray(vertexBuffer), JAVA_FLOAT, 0,
                    vertexCount);
            MemorySegment ib = copies.allocateFrom(JAVA_SHORT, MemorySegment.ofArray(indexBuffer), JAVA_SHORT, 0,
                    indexCount);
            return (int) MTL_MESH_BUILD_GEOMETRY_SHORT.invokeExact(seg(mesh), vb, vertexBufferLength,
                    vertexBuffer.length, ib, indexBufferLength, indexBuffer.length) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            scratch.end();
        }
    }

    /** {@code MTLContext.nBuildNativeGeometryInt}, as {@link #meshBuildGeometryShort}. */
    static boolean meshBuildGeometryInt(MTLScratch scratch, long mesh, float[] vertexBuffer, int vertexBufferLength,
                                        int[] indexBuffer, int indexBufferLength) {
        int vertexCount = Math.clamp(vertexBufferLength, 0, vertexBuffer.length);
        int indexCount = Math.clamp(indexBufferLength, 0, indexBuffer.length);
        SegmentAllocator copies = scratch.begin(Float.BYTES * (long) vertexCount + Integer.BYTES * (long) indexCount);
        try {
            MemorySegment vb = copies.allocateFrom(JAVA_FLOAT, MemorySegment.ofArray(vertexBuffer), JAVA_FLOAT, 0,
                    vertexCount);
            MemorySegment ib = copies.allocateFrom(JAVA_INT, MemorySegment.ofArray(indexBuffer), JAVA_INT, 0,
                    indexCount);
            return (int) MTL_MESH_BUILD_GEOMETRY_INT.invokeExact(seg(mesh), vb, vertexBufferLength,
                    vertexBuffer.length, ib, indexBufferLength, indexBuffer.length) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            scratch.end();
        }
    }

    /** {@code MTLContext.nCreateMTLPhongMaterial}. */
    static long phongMaterialCreate(long ctx) {
        try {
            return ((MemorySegment) MTL_PHONG_MATERIAL_CREATE.invokeExact(seg(ctx))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetDiffuseColor}. */
    static void phongMaterialSetDiffuseColor(long material, float r, float g, float b, float a) {
        try {
            MTL_PHONG_MATERIAL_SET_DIFFUSE_COLOR.invokeExact(seg(material), r, g, b, a);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetSpecularColor}. */
    static void phongMaterialSetSpecularColor(long material, boolean set, float r, float g, float b, float a) {
        try {
            MTL_PHONG_MATERIAL_SET_SPECULAR_COLOR.invokeExact(seg(material), flag(set), r, g, b, a);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetMap}. */
    static void phongMaterialSetMap(long material, int mapType, long tex) {
        try {
            MTL_PHONG_MATERIAL_SET_MAP.invokeExact(seg(material), mapType, seg(tex));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nCreateMTLMeshView}. */
    static long meshViewCreate(long ctx, long mesh) {
        try {
            return ((MemorySegment) MTL_MESH_VIEW_CREATE.invokeExact(seg(ctx), seg(mesh))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetCullingMode}. */
    static void meshViewSetCullingMode(long view, int cullMode) {
        try {
            MTL_MESH_VIEW_SET_CULLING_MODE.invokeExact(seg(view), cullMode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetMaterial}. */
    static void meshViewSetMaterial(long view, long material) {
        try {
            MTL_MESH_VIEW_SET_MATERIAL.invokeExact(seg(view), seg(material));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetWireframe}. */
    static void meshViewSetWireframe(long view, boolean wireframe) {
        try {
            MTL_MESH_VIEW_SET_WIREFRAME.invokeExact(seg(view), flag(wireframe));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetAmbientLight}. */
    static void meshViewSetAmbientLight(long view, float r, float g, float b) {
        try {
            MTL_MESH_VIEW_SET_AMBIENT_LIGHT.invokeExact(seg(view), r, g, b);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetLight}. */
    static void meshViewSetLight(long view, int index, float x, float y, float z,
                                 float r, float g, float b, float w,
                                 float ca, float la, float qa, float isAttenuated, float maxRange,
                                 float dirX, float dirY, float dirZ,
                                 float innerAngle, float outerAngle, float falloff) {
        try {
            MTL_MESH_VIEW_SET_LIGHT.invokeExact(seg(view), index, x, y, z, r, g, b, w, ca, la, qa, isAttenuated,
                    maxRange, dirX, dirY, dirZ, innerAngle, outerAngle, falloff);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nRenderMeshView}. */
    static void meshViewRender(long ctx, long view) {
        try {
            MTL_MESH_VIEW_RENDER.invokeExact(seg(ctx), seg(view));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
