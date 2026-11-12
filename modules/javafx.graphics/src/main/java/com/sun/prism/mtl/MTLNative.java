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

import com.sun.glass.utils.NativeLibLoader;
import com.sun.prism.PixelFormat;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The binding of {@code com.sun.prism.mtl} to the flat C ABI of {@code prism_mtl}
 * ({@code native-prism-mtl/prism_mtl_api.h}) and to {@code objc_release}. The peers call this class and nothing else.
 * The bodies and their handles are in the aggregates {@link MTLContextCalls}, {@link MTLTextureCalls},
 * {@link MTLShaderCalls} and {@link MTLMeshCalls}, which bind through {@link #bind}, so every restricted
 * {@code java.lang.foreign} call of the package is in this file.
 * <p>
 * Handles stay the {@code long}s the peers held behind the JNI; 0 is {@code NULL}, as {@code jlong_to_ptr(0)} was.
 * Every function runs on the Prism render thread.
 * <p>
 * Of the functions that read a Java array, only {@code mtl_shader_set_constants} is a
 * {@link Linker.Option#critical critical(true)} downcall: a short copy into the argument buffer that cannot block.
 * The quads, texture uploads and mesh geometry the JNI pinned with {@code GetPrimitiveArrayCritical} are copied into
 * the context's {@link MTLScratch} instead: those functions can wait for a command buffer, a ring buffer or the GPU,
 * compile a pipeline state, or copy a block of the caller's size (the BLOCKS, ENCODES and COMPILES marks of the
 * header). The scalar calls keep the thread-state transition the JNI had.
 * <p>
 * The class initializer loads the library, checks {@code mtl_abi_version} before binding anything else, and binds
 * every symbol eagerly. A failure is recorded once and thrown by {@link #loadLibrary()} and by every downcall.
 */
final class MTLNative {

    /** {@code PRISM_MTL_ABI_VERSION}; a library that answers another {@code mtl_abi_version} is refused. */
    static final int ABI_VERSION = 1;

    static final String LIBRARY_NAME = "prism_mtl";

    /** The library {@code objc_release} is resolved in: the macOS SDK headers do not declare it. */
    static final String LIBOBJC = "/usr/lib/libobjc.A.dylib";

    private static final Linker LINKER = Linker.nativeLinker();

    private static final Map<String, FunctionDescriptor> BOUND = new LinkedHashMap<>();
    private static final List<String> CRITICAL_SYMBOLS = new ArrayList<>();
    private static final List<String> MISSING_SYMBOLS = new ArrayList<>();

    private static UnsatisfiedLinkError initFailure;

    private static final SymbolLookup LOOKUP = loadPrismMtl();

    private static final MethodHandle MTL_ABI_VERSION = bind("mtl_abi_version", FunctionDescriptor.of(JAVA_INT));

    static {
        checkAbiVersion();
    }

    private static final MethodHandle MTL_ABI_CONSTANT = bind("mtl_abi_constant",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_SUPPORTS_METAL = bind("mtl_supports_metal",
            FunctionDescriptor.of(JAVA_INT));

    private static final MemorySegment OBJC_RELEASE_SYMBOL = findObjcRelease();
    private static final MethodHandle OBJC_RELEASE = link(OBJC_RELEASE_SYMBOL, "objc_release",
            FunctionDescriptor.ofVoid(ADDRESS));

    static {
        linkAggregates();
    }

    private MTLNative() {
    }

    /**
     * @throws UnsatisfiedLinkError if the library cannot be loaded, lacks a symbol, was denied native access, or
     *         answers another ABI version
     */
    static void loadLibrary() {
        if (initFailure != null) {
            throw initFailure;
        }
    }

    /** The {@code prism_mtl} symbols bound, in binding order, which is the order of the header. */
    static List<String> boundSymbols() {
        return List.copyOf(BOUND.keySet());
    }

    static Map<String, FunctionDescriptor> boundDescriptors() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(BOUND));
    }

    static List<String> criticalSymbols() {
        return List.copyOf(CRITICAL_SYMBOLS);
    }

    static List<String> missingSymbols() {
        return List.copyOf(MISSING_SYMBOLS);
    }

    /** Where {@code objc_release} was found in {@link #LIBOBJC}, 0 if it was not. */
    static long objcReleaseAddress() {
        return OBJC_RELEASE_SYMBOL.address();
    }

    static int abiVersion() {
        try {
            return (int) MTL_ABI_VERSION.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code mtl_abi_constant(index)}: an {@code enum MtlAbiConstant} entry, -1 outside the table. */
    static int abiConstant(int index) {
        try {
            return (int) MTL_ABI_CONSTANT.invokeExact(index);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLPipeline.nSupportsMTL}. */
    static boolean supportsMetal() {
        try {
            return (int) MTL_SUPPORTS_METAL.invokeExact() != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The body of the five JNI natives that compiled to {@code [cbz] b _objc_release}:
     * {@code MTLContext.nDisposeShader}, {@code nReleaseMTLMesh}, {@code nReleaseMTLPhongMaterial},
     * {@code nReleaseMTLMeshView} and {@code MTLResourceFactory.nReleaseTexture}. {@code objc_release} ignores
     * {@code NULL}, and dispatches to the {@code -release} overrides of {@code MetalMesh} and {@code MetalMeshView}.
     */
    static void objcRelease(long object) {
        try {
            OBJC_RELEASE.invokeExact(seg(object));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* The entry points whose bodies are in an aggregate, in the order of the header. */

    static long contextCreate(ByteBuffer metallib) {
        return MTLContextCalls.contextCreate(metallib);
    }

    static void contextRelease(long ctx) {
        MTLContextCalls.contextRelease(ctx);
    }

    static void contextCommit(long ctx) {
        MTLContextCalls.contextCommit(ctx);
    }

    static void contextDrawIndexedQuads(MTLScratch scratch, long ctx, float[] coords, byte[] colors,
                                        int numVertices) {
        MTLContextCalls.contextDrawIndexedQuads(scratch, ctx, coords, colors, numVertices);
    }

    static int contextUpdateRenderTarget(long ctx, long rtt, boolean depthTest) {
        return MTLContextCalls.contextUpdateRenderTarget(ctx, rtt, depthTest);
    }

    static void contextSetClipRect(long ctx, int x, int y, int width, int height) {
        MTLContextCalls.contextSetClipRect(ctx, x, y, width, height);
    }

    static void contextResetClipRect(long ctx) {
        MTLContextCalls.contextResetClipRect(ctx);
    }

    static void contextSetProjViewMatrix(long ctx, boolean depthTest,
                                         double m00, double m01, double m02, double m03,
                                         double m10, double m11, double m12, double m13,
                                         double m20, double m21, double m22, double m23,
                                         double m30, double m31, double m32, double m33) {
        MTLContextCalls.contextSetProjViewMatrix(ctx, depthTest,
                m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33);
    }

    static void contextSetTransform(long ctx,
                                    double m00, double m01, double m02, double m03,
                                    double m10, double m11, double m12, double m13,
                                    double m20, double m21, double m22, double m23,
                                    double m30, double m31, double m32, double m33) {
        MTLContextCalls.contextSetTransform(ctx,
                m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33);
    }

    static void contextSetWorldTransform(long ctx,
                                         double m00, double m01, double m02, double m03,
                                         double m10, double m11, double m12, double m13,
                                         double m20, double m21, double m22, double m23,
                                         double m30, double m31, double m32, double m33) {
        MTLContextCalls.contextSetWorldTransform(ctx,
                m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33);
    }

    static void contextSetCameraPosition(long ctx, double x, double y, double z) {
        MTLContextCalls.contextSetCameraPosition(ctx, x, y, z);
    }

    static void contextSetCompositeMode(long ctx, int mode) {
        MTLContextCalls.contextSetCompositeMode(ctx, mode);
    }

    static boolean contextIsCurrentRtt(long ctx, long rtt) {
        return MTLContextCalls.contextIsCurrentRtt(ctx, rtt);
    }

    static void contextBlit(long ctx, long srcRtt, long dstRtt, int srcX0, int srcY0, int srcX1, int srcY1,
                            int dstX0, int dstY0, int dstX1, int dstY1) {
        MTLContextCalls.contextBlit(ctx, srcRtt, dstRtt, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1);
    }

    static long contextGetCommandQueue(long ctx) {
        return MTLContextCalls.contextGetCommandQueue(ctx);
    }

    static void contextClear(long ctx, float red, float green, float blue, float alpha, boolean clearDepth) {
        MTLContextCalls.contextClear(ctx, red, green, blue, alpha, clearDepth);
    }

    static long textureCreate(long ctx, int pixelFormat, int width, int height, boolean useMipmap) {
        return MTLTextureCalls.textureCreate(ctx, pixelFormat, width, height, useMipmap);
    }

    static void textureUpdate(MTLScratch scratch, long tex, PixelFormat textureFormat, ByteBuffer buffer,
                              byte[] pixels, int dstx, int dsty, int srcx, int srcy, int width, int height,
                              int scanBytes) {
        MTLTextureCalls.textureUpdate(scratch, tex, textureFormat, buffer, pixels, dstx, dsty, srcx, srcy, width,
                height, scanBytes);
    }

    static void textureUpdateFloat(MTLScratch scratch, long tex, PixelFormat textureFormat, FloatBuffer buffer,
                                   float[] pixels, int dstx, int dsty, int srcx, int srcy, int width, int height,
                                   int scanBytes) {
        MTLTextureCalls.textureUpdateFloat(scratch, tex, textureFormat, buffer, pixels, dstx, dsty, srcx, srcy,
                width, height, scanBytes);
    }

    static void textureUpdateInt(MTLScratch scratch, long tex, PixelFormat textureFormat, IntBuffer buffer,
                                 int[] pixels, int dstx, int dsty, int srcx, int srcy, int width, int height,
                                 int scanBytes) {
        MTLTextureCalls.textureUpdateInt(scratch, tex, textureFormat, buffer, pixels, dstx, dsty, srcx, srcy,
                width, height, scanBytes);
    }

    static void textureUpdateYuv422(long tex, byte[] pixels, int dstx, int dsty, int srcx, int srcy,
                                    int width, int height, int scanBytes) {
        MTLTextureCalls.textureUpdateYuv422(tex, pixels, dstx, dsty, srcx, srcy, width, height, scanBytes);
    }

    static long rttCreate(long ctx, int physicalWidth, int physicalHeight, int contentWidth, int contentHeight,
                          boolean msaa) {
        return MTLTextureCalls.rttCreate(ctx, physicalWidth, physicalHeight, contentWidth, contentHeight, msaa);
    }

    static long rttWrap(long ctx, long mtlTexture, int physicalWidth, int physicalHeight) {
        return MTLTextureCalls.rttWrap(ctx, mtlTexture, physicalWidth, physicalHeight);
    }

    static void rttInit(long rtt, int[] pixels) {
        MTLTextureCalls.rttInit(rtt, pixels);
    }

    static void rttReadPixels(long rtt, int[] pixels) {
        MTLTextureCalls.rttReadPixels(rtt, pixels);
    }

    static void rttReadPixelsContiguous(long rtt, IntBuffer pixels) {
        MTLTextureCalls.rttReadPixelsContiguous(rtt, pixels);
    }

    static long shaderCreate(long ctx, String fragmentFunctionName) {
        return MTLShaderCalls.shaderCreate(ctx, fragmentFunctionName);
    }

    static Map<String, Integer> shaderUniformNameIdMap(long shader) {
        return MTLShaderCalls.shaderUniformNameIdMap(shader);
    }

    static void shaderEnable(long shader) {
        MTLShaderCalls.shaderEnable(shader);
    }

    static void shaderDisable(long shader) {
        MTLShaderCalls.shaderDisable(shader);
    }

    static void shaderSetTexture(long shader, int texUnit, int uniformId, long tex, boolean linear, int wrapMode) {
        MTLShaderCalls.shaderSetTexture(shader, texUnit, uniformId, tex, linear, wrapMode);
    }

    static void shaderSetInt(long shader, int uniformId, int i0) {
        MTLShaderCalls.shaderSetInt(shader, uniformId, i0);
    }

    static void shaderSetFloat1(long shader, int uniformId, float f0) {
        MTLShaderCalls.shaderSetFloat1(shader, uniformId, f0);
    }

    static void shaderSetFloat2(long shader, int uniformId, float f0, float f1) {
        MTLShaderCalls.shaderSetFloat2(shader, uniformId, f0, f1);
    }

    static void shaderSetFloat3(long shader, int uniformId, float f0, float f1, float f2) {
        MTLShaderCalls.shaderSetFloat3(shader, uniformId, f0, f1, f2);
    }

    static void shaderSetFloat4(long shader, int uniformId, float f0, float f1, float f2, float f3) {
        MTLShaderCalls.shaderSetFloat4(shader, uniformId, f0, f1, f2, f3);
    }

    static void shaderSetConstants(long shader, int uniformId, float[] values, int floatCount) {
        MTLShaderCalls.shaderSetConstants(shader, uniformId, values, floatCount);
    }

    static void shaderSetConstantsBuf(long shader, int uniformId, FloatBuffer values, int byteOffset,
                                      int floatCount) {
        MTLShaderCalls.shaderSetConstantsBuf(shader, uniformId, values, byteOffset, floatCount);
    }

    static long meshCreate(long ctx) {
        return MTLMeshCalls.meshCreate(ctx);
    }

    static boolean meshBuildGeometryShort(MTLScratch scratch, long mesh, float[] vertexBuffer,
                                          int vertexBufferLength, short[] indexBuffer, int indexBufferLength) {
        return MTLMeshCalls.meshBuildGeometryShort(scratch, mesh, vertexBuffer, vertexBufferLength, indexBuffer,
                indexBufferLength);
    }

    static boolean meshBuildGeometryInt(MTLScratch scratch, long mesh, float[] vertexBuffer, int vertexBufferLength,
                                        int[] indexBuffer, int indexBufferLength) {
        return MTLMeshCalls.meshBuildGeometryInt(scratch, mesh, vertexBuffer, vertexBufferLength, indexBuffer,
                indexBufferLength);
    }

    static long phongMaterialCreate(long ctx) {
        return MTLMeshCalls.phongMaterialCreate(ctx);
    }

    static void phongMaterialSetDiffuseColor(long material, float r, float g, float b, float a) {
        MTLMeshCalls.phongMaterialSetDiffuseColor(material, r, g, b, a);
    }

    static void phongMaterialSetSpecularColor(long material, boolean set, float r, float g, float b, float a) {
        MTLMeshCalls.phongMaterialSetSpecularColor(material, set, r, g, b, a);
    }

    static void phongMaterialSetMap(long material, int mapType, long tex) {
        MTLMeshCalls.phongMaterialSetMap(material, mapType, tex);
    }

    static long meshViewCreate(long ctx, long mesh) {
        return MTLMeshCalls.meshViewCreate(ctx, mesh);
    }

    static void meshViewSetCullingMode(long view, int cullMode) {
        MTLMeshCalls.meshViewSetCullingMode(view, cullMode);
    }

    static void meshViewSetMaterial(long view, long material) {
        MTLMeshCalls.meshViewSetMaterial(view, material);
    }

    static void meshViewSetWireframe(long view, boolean wireframe) {
        MTLMeshCalls.meshViewSetWireframe(view, wireframe);
    }

    static void meshViewSetAmbientLight(long view, float r, float g, float b) {
        MTLMeshCalls.meshViewSetAmbientLight(view, r, g, b);
    }

    static void meshViewSetLight(long view, int index, float x, float y, float z,
                                 float r, float g, float b, float w,
                                 float ca, float la, float qa, float isAttenuated, float maxRange,
                                 float dirX, float dirY, float dirZ,
                                 float innerAngle, float outerAngle, float falloff) {
        MTLMeshCalls.meshViewSetLight(view, index, x, y, z, r, g, b, w, ca, la, qa, isAttenuated, maxRange,
                dirX, dirY, dirZ, innerAngle, outerAngle, falloff);
    }

    static void meshViewRender(long ctx, long view) {
        MTLMeshCalls.meshViewRender(ctx, view);
    }

    /* Linkage, shared with the aggregates. */

    static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        return bind(name, descriptor, new Linker.Option[0]);
    }

    /** Only for a function that reads Java heap memory, is short and cannot block. */
    static MethodHandle bindCritical(String name, FunctionDescriptor descriptor) {
        CRITICAL_SYMBOLS.add(name);
        return bind(name, descriptor, Linker.Option.critical(true));
    }

    static MemorySegment seg(long address) {
        return MemorySegment.ofAddress(address);
    }

    static int flag(boolean value) {
        return value ? 1 : 0;
    }

    /**
     * What {@code GetDirectBufferAddress} gave the C: the whole buffer from its base address, whatever its position
     * and limit, so that a position left at the end never shrinks what the C sees. {@code NULL} for a buffer that is
     * not direct, which the C dereferences as it did behind the JNI.
     */
    static MemorySegment directBase(Buffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return MemorySegment.NULL;
        }
        return MemorySegment.ofBuffer(buffer.duplicate().clear());
    }

    /** Only linkage failures reach the catch around a downcall: the C cannot throw into Java. */
    static Error unexpected(Throwable t) {
        if (t instanceof Error e) {
            return e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        return new AssertionError(t);
    }

    private static MethodHandle bind(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        BOUND.put(name, descriptor);
        MemorySegment symbol = MemorySegment.NULL;
        if (LOOKUP != null) {
            Optional<MemorySegment> found = LOOKUP.find(name);
            if (found.isPresent()) {
                symbol = found.get();
            } else {
                MISSING_SYMBOLS.add(name);
                recordFailure(new UnsatisfiedLinkError("missing native symbol: " + name + " in " + LIBRARY_NAME));
            }
        }
        return link(symbol, name, descriptor, options);
    }

    /**
     * A failing handle of the descriptor's type, rather than an exception, once anything has failed: these run in
     * static initializers, and a class whose initializer threw could not report the failure a second time.
     */
    @SuppressWarnings("restricted")
    private static MethodHandle link(MemorySegment symbol, String name, FunctionDescriptor descriptor,
                                     Linker.Option... options) {
        if (initFailure == null && symbol.address() != 0) {
            try {
                return LINKER.downcallHandle(symbol, descriptor, options);
            } catch (IllegalCallerException e) {
                recordFailure(initializationFailed(e));
            }
        }
        return failingHandle(descriptor, name);
    }

    private static MethodHandle failingHandle(FunctionDescriptor descriptor, String name) {
        MethodType type = descriptor.toMethodType();
        UnsatisfiedLinkError error = initFailure != null
                ? initFailure : new UnsatisfiedLinkError("missing native symbol: " + name);
        MethodHandle thrower = MethodHandles.insertArguments(
                MethodHandles.throwException(type.returnType(), UnsatisfiedLinkError.class), 0, error);
        return MethodHandles.dropArguments(thrower, 0, type.parameterList());
    }

    private static SymbolLookup loadPrismMtl() {
        try {
            NativeLibLoader.loadLibrary(LIBRARY_NAME);
            return NativeLibLoader.loadedLibrariesLookup();
        } catch (UnsatisfiedLinkError e) {
            recordFailure(e);
        } catch (RuntimeException e) {
            // NativeLibLoader checks native access (MemorySegment::reinterpret) before it looks for a file, as
            // System.load did: without native access the loader throws IllegalCallerException.
            recordFailure(initializationFailed(e));
        }
        return null;
    }

    private static void checkAbiVersion() {
        if (initFailure != null) {
            return;
        }
        int actual = abiVersion();
        if (actual != ABI_VERSION) {
            recordFailure(new UnsatisfiedLinkError(LIBRARY_NAME + " ABI version mismatch: expected " + ABI_VERSION
                    + ", found " + actual));
        }
    }

    @SuppressWarnings("restricted")
    private static MemorySegment findObjcRelease() {
        try {
            Optional<MemorySegment> symbol = SymbolLookup.libraryLookup(LIBOBJC, Arena.global()).find("objc_release");
            if (symbol.isPresent()) {
                return symbol.get();
            }
            recordFailure(new UnsatisfiedLinkError("missing native symbol: objc_release in " + LIBOBJC));
        } catch (IllegalArgumentException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("cannot open " + LIBOBJC + ": " + e.getMessage());
            error.initCause(e);
            recordFailure(error);
        } catch (IllegalCallerException e) {
            recordFailure(initializationFailed(e));
        }
        return MemorySegment.NULL;
    }

    /** Binds the aggregates' symbols now, in the order of the header, so that a missing one fails the load. */
    private static void linkAggregates() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            for (Class<?> aggregate : List.of(MTLContextCalls.class, MTLTextureCalls.class, MTLShaderCalls.class,
                    MTLMeshCalls.class)) {
                lookup.ensureInitialized(aggregate);
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static void recordFailure(UnsatisfiedLinkError error) {
        if (initFailure == null) {
            initFailure = error;
        }
    }

    private static UnsatisfiedLinkError initializationFailed(RuntimeException e) {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(e instanceof IllegalCallerException
                ? LIBRARY_NAME + " was not granted native access: add javafx.graphics - or ALL-UNNAMED, when it"
                        + " is on the class path - to --enable-native-access"
                : LIBRARY_NAME + " could not be initialized: " + e);
        error.initCause(e);
        return error;
    }
}
