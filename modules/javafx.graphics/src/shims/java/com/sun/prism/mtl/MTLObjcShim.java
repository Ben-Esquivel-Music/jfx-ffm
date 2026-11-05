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

import com.sun.prism.Mesh;
import com.sun.prism.MeshView;
import com.sun.prism.PhongMaterial;
import com.sun.prism.Texture;
import com.sun.prism.ps.Shader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Test access to the Objective-C objects behind the {@code com.sun.prism.mtl} peers, for the round trips of the five
 * release natives that became {@link MTLNative#objcRelease} calls: the handle of each peer, and probes bound from
 * libobjc and CoreFoundation directly, so that nothing here goes through {@code prism_mtl}.
 * <p>
 * A sentinel is a {@code CFData} that an object retains as an associated object. The runtime releases it when it
 * destroys the object, so the sentinel's retain count tells whether the object was deallocated without reading the
 * object's memory, which may be gone by then.
 */
@SuppressWarnings("restricted")
public final class MTLObjcShim {

    static final String LIBOBJC = "/usr/lib/libobjc.A.dylib";
    static final String CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    private static final long OBJC_ASSOCIATION_RETAIN_NONATOMIC = 1;
    private static final long MAX_CLASS_NAME_BYTES = 4096;

    private MTLObjcShim() {
    }

    public static long textureHandle(Texture texture) {
        return ((MTLTexture<?>) texture).getNativeHandle();
    }

    public static long meshHandle(Mesh mesh) {
        return ((MTLMesh) mesh).getNativeHandle();
    }

    public static long materialHandle(PhongMaterial material) {
        return ((MTLPhongMaterial) material).getNativeHandle();
    }

    public static long meshViewHandle(MeshView view) {
        return readLong(MTLMeshView.class, "nativeHandle", view);
    }

    public static long shaderHandle(Shader shader) {
        return readLong(MTLShader.class, "nMetalShaderRef", shader);
    }

    /** {@code class_getName(object_getClass(object))}. */
    public static String className(long object) {
        try {
            MemorySegment cls = (MemorySegment) Objc.OBJECT_GET_CLASS.invokeExact(MemorySegment.ofAddress(object));
            MemorySegment name = (MemorySegment) Objc.CLASS_GET_NAME.invokeExact(cls);
            return name.reinterpret(MAX_CLASS_NAME_BYTES).getString(0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * Element {@code index} of the pointer-sized instance variable {@code ivarName} of a live object: its value for
     * a scalar ivar ({@code index} 0), or one entry of an array ivar such as {@code MetalMeshView.lights}.
     */
    public static long ivarPointer(long object, String ivarName, int index) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment receiver = MemorySegment.ofAddress(object);
            MemorySegment cls = (MemorySegment) Objc.OBJECT_GET_CLASS.invokeExact(receiver);
            MemorySegment ivar = (MemorySegment) Objc.CLASS_GET_INSTANCE_VARIABLE.invokeExact(cls,
                    call.allocateFrom(ivarName));
            if (ivar.address() == 0) {
                throw new IllegalArgumentException(className(object) + " has no instance variable " + ivarName);
            }
            long offset = (long) Objc.IVAR_GET_OFFSET.invokeExact(ivar);
            long element = offset + ADDRESS.byteSize() * index;
            return receiver.reinterpret(element + ADDRESS.byteSize()).get(ADDRESS, element).address();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * Makes {@code object} retain a new sentinel and returns the sentinel, which this caller owns as well: its retain
     * count is 2 while the object lives and 1 once the runtime has destroyed the object.
     */
    public static long attachSentinel(long object) {
        try {
            MemorySegment sentinel = (MemorySegment) Objc.CF_DATA_CREATE_MUTABLE.invokeExact(MemorySegment.NULL, 0L);
            Objc.OBJC_SET_ASSOCIATED_OBJECT.invokeExact(MemorySegment.ofAddress(object), sentinel, sentinel,
                    OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            return sentinel.address();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code CFGetRetainCount}, which answers {@code -retainCount} for an Objective-C object. */
    public static long retainCount(long object) {
        try {
            return (long) Objc.CF_GET_RETAIN_COUNT.invokeExact(MemorySegment.ofAddress(object));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code CFRetain}: the object, retained once more. */
    public static long retain(long object) {
        try {
            return ((MemorySegment) Objc.CF_RETAIN.invokeExact(MemorySegment.ofAddress(object))).address();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static void release(long object) {
        try {
            Objc.CF_RELEASE.invokeExact(MemorySegment.ofAddress(object));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static long readLong(Class<?> owner, String fieldName, Object instance) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getLong(instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Objc {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup OBJC = SymbolLookup.libraryLookup(LIBOBJC, Arena.global());
        static final SymbolLookup CF = SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global());

        static final MethodHandle OBJECT_GET_CLASS = bind(OBJC, "object_getClass",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CLASS_GET_NAME = bind(OBJC, "class_getName",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CLASS_GET_INSTANCE_VARIABLE = bind(OBJC, "class_getInstanceVariable",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle IVAR_GET_OFFSET = bind(OBJC, "ivar_getOffset",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        // objc_AssociationPolicy is a uintptr_t.
        static final MethodHandle OBJC_SET_ASSOCIATED_OBJECT = bind(OBJC, "objc_setAssociatedObject",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_DATA_CREATE_MUTABLE = bind(CF, "CFDataCreateMutable",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_GET_RETAIN_COUNT = bind(CF, "CFGetRetainCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_RETAIN = bind(CF, "CFRetain", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_RELEASE = bind(CF, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));

        private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(library.findOrThrow(name), descriptor);
        }
    }
}
