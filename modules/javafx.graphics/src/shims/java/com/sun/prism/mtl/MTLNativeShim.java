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

import com.sun.prism.PixelFormat;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Test access to {@link MTLNative} and {@link MTLScratch}, and to the Java constants the C ABI constants must equal.
 * The private and protected ones ({@code MTLContext.MTL_COMPMODE_*}, {@code VertexBuffer.FLOATS_PER_VERT},
 * {@code BaseMesh.VERTEX_SIZE_VB}) are read reflectively, which this module may do to its own classes.
 */
public final class MTLNativeShim {

    private MTLNativeShim() {
    }

    public static void loadLibrary() {
        MTLNative.loadLibrary();
    }

    public static List<String> boundSymbols() {
        return MTLNative.boundSymbols();
    }

    /** The Java carrier types of every bound descriptor, by symbol, in binding order. */
    public static Map<String, MethodType> boundCarrierTypes() {
        Map<String, MethodType> types = new LinkedHashMap<>();
        MTLNative.boundDescriptors().forEach((name, descriptor) -> types.put(name, descriptor.toMethodType()));
        return types;
    }

    public static List<String> criticalSymbols() {
        return MTLNative.criticalSymbols();
    }

    public static List<String> missingSymbols() {
        return MTLNative.missingSymbols();
    }

    public static int expectedAbiVersion() {
        return MTLNative.ABI_VERSION;
    }

    public static int abiVersion() {
        return MTLNative.abiVersion();
    }

    public static int abiConstant(int index) {
        return MTLNative.abiConstant(index);
    }

    public static String libobjc() {
        return MTLNative.LIBOBJC;
    }

    public static long objcReleaseAddress() {
        return MTLNative.objcReleaseAddress();
    }

    public static void objcRelease(long object) {
        MTLNative.objcRelease(object);
    }

    public static MemorySegment directBase(Buffer buffer) {
        return MTLNative.directBase(buffer);
    }

    public static int quadVertexFloats() {
        return MTLContextCalls.QUAD_VERTEX_FLOATS;
    }

    public static int quadVertexColorBytes() {
        return MTLContextCalls.QUAD_VERTEX_COLOR_BYTES;
    }

    /** {@code MTLContext.NUM_QUADS}, which initializes {@code MTLContext} (it reads the shader library resource). */
    public static int numQuads() {
        return MTLContext.NUM_QUADS;
    }

    /** The size of a texel of a texture of {@code textureFormat} as the C reads it; loads no library. */
    public static int textureTexelBytes(PixelFormat textureFormat) {
        return MTLTextureCalls.SourceRows.texelBytes(textureFormat);
    }

    /** What {@code mtl_texture_update} reads for these arguments, and how much of it the facade copies. */
    public record TextureSourceRows(long start, long end, boolean packed, long byteSize) {
    }

    /** {@link TextureSourceRows} for these arguments; loads no library. */
    public static TextureSourceRows textureSourceRows(PixelFormat textureFormat, int srcx, int srcy, int width,
                                                      int height, int scanBytes) {
        MTLTextureCalls.SourceRows rows = MTLTextureCalls.SourceRows.of(textureFormat, srcx, srcy, width, height,
                scanBytes);
        return new TextureSourceRows(rows.start(), rows.end(), rows.packed(), rows.byteSize());
    }

    /**
     * The facade's copy of a heap array for {@code mtl_texture_update}, and the {@code srcx}, {@code srcy} and
     * {@code scanBytes} the C gets with the address {@code baseOffset} bytes from the copy.
     */
    public record TextureUpdateCopy(MemorySegment copy, long baseOffset, int srcx, int srcy, int scanBytes) {
    }

    /** The {@code static int} field {@code fieldName} of the javafx.graphics class {@code className}. */
    public static int staticInt(String className, String fieldName) {
        try {
            Field field = Class.forName(className, true, MTLNativeShim.class.getClassLoader())
                               .getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One {@link MTLScratch}, driven the way the facade drives it. */
    public static final class Scratch {

        private final MTLScratch scratch = new MTLScratch();

        public static long retainedBytes() {
            return MTLScratch.RETAINED_BYTES;
        }

        /** Begins a call and copies each array into it; the copies live until {@link #endCall}. */
        public MemorySegment[] copiesInCall(int[]... arrays) {
            long byteSize = 0;
            for (int[] array : arrays) {
                byteSize += Integer.BYTES * (long) array.length;
            }
            SegmentAllocator copies = scratch.begin(byteSize);
            MemorySegment[] segments = new MemorySegment[arrays.length];
            for (int i = 0; i < arrays.length; i++) {
                segments[i] = copies.allocateFrom(JAVA_INT, arrays[i]);
            }
            return segments;
        }

        /**
         * Begins a call and copies what {@code mtl_texture_update} reads of {@code array} for these arguments, as
         * the facade does; the copy lives until {@link #endCall}.
         */
        public TextureUpdateCopy textureUpdateCopyInCall(byte[] array, PixelFormat textureFormat, int srcx,
                                                         int srcy, int width, int height, int scanBytes) {
            MTLTextureCalls.SourceRows rows = MTLTextureCalls.SourceRows.of(textureFormat, srcx, srcy, width,
                    height, scanBytes);
            MTLTextureCalls.SourceRows.Copy copy = rows.copyOf(MemorySegment.ofArray(array),
                    scratch.begin(rows.byteSize()));
            return new TextureUpdateCopy(copy.copy(), copy.baseOffset(), copy.srcx(), copy.srcy(),
                    copy.scanBytes());
        }

        public void endCall() {
            scratch.end();
        }

        public void close() {
            scratch.close();
        }
    }
}
