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
import java.nio.ByteBuffer;
import java.util.Collections;
import static com.sun.prism.mtl.MTLNative.bind;
import static com.sun.prism.mtl.MTLNative.directBase;
import static com.sun.prism.mtl.MTLNative.flag;
import static com.sun.prism.mtl.MTLNative.seg;
import static com.sun.prism.mtl.MTLNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@code mtl_context_*} functions: the natives of {@code MTLContext} and {@code MTLGraphics.nClear}, whose JNI
 * bodies were in {@code MetalContext.m}.
 */
final class MTLContextCalls {

    /** {@code PrismSourceVertex}: x, y, z, tu1, tv1, tu2, tv2. */
    static final int QUAD_VERTEX_FLOATS = 7;

    /** The {@code uchar4} colour of a quad vertex. */
    static final int QUAD_VERTEX_COLOR_BYTES = 4;

    private static final MemoryLayout[] MATRIX = Collections.nCopies(16, JAVA_DOUBLE).toArray(new MemoryLayout[0]);

    private static final MethodHandle MTL_CONTEXT_CREATE = bind("mtl_context_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle MTL_CONTEXT_RELEASE = bind("mtl_context_release",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle MTL_CONTEXT_COMMIT = bind("mtl_context_commit",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle MTL_CONTEXT_DRAW_INDEXED_QUADS = bind("mtl_context_draw_indexed_quads",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle MTL_CONTEXT_UPDATE_RENDER_TARGET = bind("mtl_context_update_render_target",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle MTL_CONTEXT_SET_CLIP_RECT = bind("mtl_context_set_clip_rect",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_CONTEXT_RESET_CLIP_RECT = bind("mtl_context_reset_clip_rect",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle MTL_CONTEXT_SET_PROJ_VIEW_MATRIX = bind("mtl_context_set_proj_view_matrix",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT).appendArgumentLayouts(MATRIX));
    private static final MethodHandle MTL_CONTEXT_SET_TRANSFORM = bind("mtl_context_set_transform",
            FunctionDescriptor.ofVoid(ADDRESS).appendArgumentLayouts(MATRIX));
    private static final MethodHandle MTL_CONTEXT_SET_WORLD_TRANSFORM = bind("mtl_context_set_world_transform",
            FunctionDescriptor.ofVoid(ADDRESS).appendArgumentLayouts(MATRIX));
    private static final MethodHandle MTL_CONTEXT_SET_CAMERA_POSITION = bind("mtl_context_set_camera_position",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
    private static final MethodHandle MTL_CONTEXT_SET_COMPOSITE_MODE = bind("mtl_context_set_composite_mode",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle MTL_CONTEXT_IS_CURRENT_RTT = bind("mtl_context_is_current_rtt",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle MTL_CONTEXT_BLIT = bind("mtl_context_blit",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS,
                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle MTL_CONTEXT_GET_COMMAND_QUEUE = bind("mtl_context_get_command_queue",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle MTL_CONTEXT_CLEAR = bind("mtl_context_clear",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT));

    private MTLContextCalls() {
    }

    /**
     * {@code MTLContext.nInitialize}. The C keeps a pointer to the bytes of {@code metallib} for the life of the
     * context, so the caller keeps the buffer reachable that long.
     */
    static long contextCreate(ByteBuffer metallib) {
        MemorySegment base = directBase(metallib);
        try {
            return ((MemorySegment) MTL_CONTEXT_CREATE.invokeExact(base, base.byteSize())).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nRelease}. */
    static void contextRelease(long ctx) {
        try {
            MTL_CONTEXT_RELEASE.invokeExact(seg(ctx));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nCommitCurrentCommandBuffer}. */
    static void contextCommit(long ctx) {
        try {
            MTL_CONTEXT_COMMIT.invokeExact(seg(ctx));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nDrawIndexedQuads}; only the vertices the C reads are copied. */
    static void contextDrawIndexedQuads(MTLScratch scratch, long ctx, float[] coords, byte[] colors,
                                        int numVertices) {
        int coordCount = numVertices * QUAD_VERTEX_FLOATS;
        int colorCount = numVertices * QUAD_VERTEX_COLOR_BYTES;
        SegmentAllocator copies = scratch.begin(Float.BYTES * (long) coordCount + colorCount);
        try {
            MemorySegment coordCopy = copies.allocateFrom(JAVA_FLOAT, MemorySegment.ofArray(coords), JAVA_FLOAT, 0,
                    coordCount);
            MemorySegment colorCopy = copies.allocateFrom(JAVA_BYTE, MemorySegment.ofArray(colors), JAVA_BYTE, 0,
                    colorCount);
            MTL_CONTEXT_DRAW_INDEXED_QUADS.invokeExact(seg(ctx), coordCopy, colorCopy, numVertices);
        } catch (Throwable t) {
            throw unexpected(t);
        } finally {
            scratch.end();
        }
    }

    /** {@code MTLContext.nUpdateRenderTarget}. */
    static int contextUpdateRenderTarget(long ctx, long rtt, boolean depthTest) {
        try {
            return (int) MTL_CONTEXT_UPDATE_RENDER_TARGET.invokeExact(seg(ctx), seg(rtt), flag(depthTest));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetClipRect}. */
    static void contextSetClipRect(long ctx, int x, int y, int width, int height) {
        try {
            MTL_CONTEXT_SET_CLIP_RECT.invokeExact(seg(ctx), x, y, width, height);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nResetClipRect}. */
    static void contextResetClipRect(long ctx) {
        try {
            MTL_CONTEXT_RESET_CLIP_RECT.invokeExact(seg(ctx));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetProjViewMatrix}, whose {@code isOrtho} the C has always used as the depth test. */
    static void contextSetProjViewMatrix(long ctx, boolean depthTest,
                                         double m00, double m01, double m02, double m03,
                                         double m10, double m11, double m12, double m13,
                                         double m20, double m21, double m22, double m23,
                                         double m30, double m31, double m32, double m33) {
        try {
            MTL_CONTEXT_SET_PROJ_VIEW_MATRIX.invokeExact(seg(ctx), flag(depthTest),
                    m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetTransform}. */
    static void contextSetTransform(long ctx,
                                    double m00, double m01, double m02, double m03,
                                    double m10, double m11, double m12, double m13,
                                    double m20, double m21, double m22, double m23,
                                    double m30, double m31, double m32, double m33) {
        try {
            MTL_CONTEXT_SET_TRANSFORM.invokeExact(seg(ctx),
                    m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetWorldTransform}. */
    static void contextSetWorldTransform(long ctx,
                                         double m00, double m01, double m02, double m03,
                                         double m10, double m11, double m12, double m13,
                                         double m20, double m21, double m22, double m23,
                                         double m30, double m31, double m32, double m33) {
        try {
            MTL_CONTEXT_SET_WORLD_TRANSFORM.invokeExact(seg(ctx),
                    m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetCameraPosition}. */
    static void contextSetCameraPosition(long ctx, double x, double y, double z) {
        try {
            MTL_CONTEXT_SET_CAMERA_POSITION.invokeExact(seg(ctx), x, y, z);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nSetCompositeMode}. */
    static void contextSetCompositeMode(long ctx, int mode) {
        try {
            MTL_CONTEXT_SET_COMPOSITE_MODE.invokeExact(seg(ctx), mode);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nIsCurrentRTT}. */
    static boolean contextIsCurrentRtt(long ctx, long rtt) {
        try {
            return (int) MTL_CONTEXT_IS_CURRENT_RTT.invokeExact(seg(ctx), seg(rtt)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLContext.nBlit}. */
    static void contextBlit(long ctx, long srcRtt, long dstRtt, int srcX0, int srcY0, int srcX1, int srcY1,
                            int dstX0, int dstY0, int dstX1, int dstY1) {
        try {
            MTL_CONTEXT_BLIT.invokeExact(seg(ctx), seg(srcRtt), seg(dstRtt),
                    srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code MTLContext.nGetCommandQueue}: the raw {@code id<MTLCommandQueue>}, which Glass reads from the device
     * details as a {@code Long}. The context owns it.
     */
    static long contextGetCommandQueue(long ctx) {
        try {
            return ((MemorySegment) MTL_CONTEXT_GET_COMMAND_QUEUE.invokeExact(seg(ctx))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code MTLGraphics.nClear}. */
    static void contextClear(long ctx, float red, float green, float blue, float alpha, boolean clearDepth) {
        try {
            MTL_CONTEXT_CLEAR.invokeExact(seg(ctx), red, green, blue, alpha, flag(clearDepth));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
