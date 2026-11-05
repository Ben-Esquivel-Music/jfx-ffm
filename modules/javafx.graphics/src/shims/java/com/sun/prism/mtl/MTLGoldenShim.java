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

import com.sun.javafx.font.MacMachineShim;
import com.sun.prism.ResourceFactory;
import com.sun.prism.ps.Shader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * What the Metal goldens read from {@code com.sun.prism.mtl} without going through the natives under test: shaders
 * created by name, their uniform tables, the shader library resource, the declared natives, and two process probes
 * (AppKit's {@code NSApp} and the default Metal device) bound from the system frameworks directly.
 * <p>
 * The private-name reads ({@code uniformNameIdMap}, {@code shaderMap}) are the only coupling to {@code MTLShader}'s
 * internals: if they are renamed, this shim changes and the goldens do not.
 */
@SuppressWarnings("restricted")
public final class MTLGoldenShim {

    public static final List<String> CLASSES = List.of(
            "MTLContext", "MTLFBOTextureData", "MTLGraphics", "MTLMesh", "MTLMeshView", "MTLPhongMaterial",
            "MTLPipeline", "MTLRTTexture", "MTLRTTextureData", "MTLRenderTarget", "MTLResourceFactory", "MTLShader",
            "MTLSwapChain", "MTLTexture", "MTLTextureData", "MTLTextureResource", "MTLVramPool");

    static final String APPKIT = "/System/Library/Frameworks/AppKit.framework/Versions/C/AppKit";
    static final String METAL = "/System/Library/Frameworks/Metal.framework/Versions/A/Metal";
    static final String CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    private MTLGoldenShim() {
    }

    /**
     * {@code MTLShader.createShader(context, name)}, which caches by name without storing samplers. Use it only in a
     * JVM where no stock or Decora shader of the same name is created afterwards.
     */
    public static Shader createShader(ResourceFactory factory, String name) {
        return MTLShader.createShader(((MTLResourceFactory) factory).getContext(), name);
    }

    /** A sorted copy of the shader's uniform name to argument-buffer id table, as the native returned it. */
    @SuppressWarnings("unchecked")
    public static Map<String, Integer> uniformTable(Shader shader) {
        return new TreeMap<>((Map<String, Integer>) read(MTLShader.class, "uniformNameIdMap", shader));
    }

    /** The names of every shader this JVM has created and not disposed. */
    @SuppressWarnings("unchecked")
    public static Set<String> createdShaderNames() {
        return new TreeSet<>(((Map<String, ?>) read(MTLShader.class, "shaderMap", null)).keySet());
    }

    /** The resource {@code MTLContext} hands to the native, read without initialising {@code MTLContext}. */
    public static byte[] metallib() {
        try (InputStream in = MTLGoldenShim.class.getResourceAsStream("msl/jfxshaders.metallib")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every {@code native} method of the {@link #CLASSES} as {@code Class.name(descriptor)}, sorted. The classes are
     * loaded without initialisation, so no library is loaded and no shader library is read.
     */
    public static List<String> declaredNatives() {
        Set<String> natives = new TreeSet<>();
        for (String name : CLASSES) {
            Class<?> type;
            try {
                type = Class.forName(MTLGoldenShim.class.getPackageName() + "." + name, false,
                                     MTLGoldenShim.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isNative(method.getModifiers())) {
                    String descriptor = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                                                  .descriptorString();
                    natives.add(name + "." + method.getName() + descriptor);
                }
            }
        }
        return List.copyOf(natives);
    }

    /**
     * Whether AppKit's {@code NSApp} is nil, that is, no {@code NSApplication} exists in this process and nothing can
     * have activated it. An AppKit that is not loaded at all has no application either; it is not loaded here.
     */
    public static boolean nsAppIsNil() {
        if (MacMachineShim.imageUuid(APPKIT) == null) {
            return true;
        }
        MemorySegment nsApp = AppKit.LOOKUP.findOrThrow("NSApp").reinterpret(ADDRESS.byteSize());
        return nsApp.get(ADDRESS, 0).address() == 0;
    }

    /**
     * {@code CFCopyDescription} of the first device {@code MTLCopyAllDevices} returns: C and CoreFoundation only, no
     * message send. For the record; the text holds per-process addresses.
     */
    public static String defaultDeviceDescription() {
        try {
            MemorySegment devices = (MemorySegment) Metal.COPY_ALL_DEVICES.invokeExact();
            if (devices.address() == 0) {
                return null;
            }
            long count = (long) Metal.CF_ARRAY_GET_COUNT.invokeExact(devices);
            String text = null;
            if (count > 0) {
                MemorySegment device = (MemorySegment) Metal.CF_ARRAY_GET_VALUE_AT_INDEX.invokeExact(devices, 0L);
                MemorySegment description = (MemorySegment) Metal.CF_COPY_DESCRIPTION.invokeExact(device);
                text = count + ":" + string(description);
                Metal.CF_RELEASE.invokeExact(description);
            }
            Metal.CF_RELEASE.invokeExact(devices);
            return text;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static String string(MemorySegment cfString) throws Throwable {
        long length = (long) Metal.CF_STRING_GET_LENGTH.invokeExact(cfString);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment characters = scratch.allocate(JAVA_CHAR, Math.max(1, length));
            MemorySegment range = scratch.allocate(Metal.CF_RANGE);
            range.setAtIndex(JAVA_LONG, 1, length);
            Metal.CF_STRING_GET_CHARACTERS.invokeExact(cfString, range, characters);
            return new String(characters.asSlice(0, length * 2).toArray(JAVA_CHAR));
        }
    }

    private static Object read(Class<?> owner, String fieldName, Object instance) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class AppKit {
        static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup(APPKIT, Arena.global());
    }

    private static final class Metal {
        static final Linker LINKER = Linker.nativeLinker();
        static final StructLayout CF_RANGE = MemoryLayout.structLayout(
                JAVA_LONG.withName("location"), JAVA_LONG.withName("length"));
        static final SymbolLookup MTL = SymbolLookup.libraryLookup(METAL, Arena.global());
        static final SymbolLookup CF = SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global());

        static final MethodHandle COPY_ALL_DEVICES = bind(MTL, "MTLCopyAllDevices", FunctionDescriptor.of(ADDRESS));
        static final MethodHandle CF_ARRAY_GET_COUNT = bind(CF, "CFArrayGetCount",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_ARRAY_GET_VALUE_AT_INDEX = bind(CF, "CFArrayGetValueAtIndex",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle CF_COPY_DESCRIPTION = bind(CF, "CFCopyDescription",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CF_STRING_GET_LENGTH = bind(CF, "CFStringGetLength",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        static final MethodHandle CF_STRING_GET_CHARACTERS = bind(CF, "CFStringGetCharacters",
                FunctionDescriptor.ofVoid(ADDRESS, CF_RANGE, ADDRESS));
        static final MethodHandle CF_RELEASE = bind(CF, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));

        private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(library.findOrThrow(name), descriptor);
        }
    }
}
