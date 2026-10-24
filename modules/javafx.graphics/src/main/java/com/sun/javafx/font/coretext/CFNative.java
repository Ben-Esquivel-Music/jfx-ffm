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

package com.sun.javafx.font.coretext;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * CoreFoundation, and the linkage toolkit that {@link CGNative} and {@link CTNative} share. The frameworks exist
 * only in the dyld shared cache, so they are opened by install name with the {@code String} overload of
 * {@link SymbolLookup#libraryLookup}; the {@code Path} overload, {@code defaultLookup} and {@code loaderLookup}
 * cannot see them.
 * <p>
 * Every method of the three facades that shares its name with a native entry point of {@code OS},
 * {@code MacFontFinder} or {@code DFontDecoder} has a {@code MemorySegment} in its descriptor, and no entry point
 * does, so the binding witness of the macOS goldens never takes a facade method for a second binding of an entry
 * point.
 */
public final class CFNative {

    public static final long kCFNotFound = -1;
    public static final long kCFCompareEqualTo = 0;
    public static final long kCFCompareCaseInsensitive = 1;
    public static final long kCFNumberIntType = 9;
    public static final long kCFURLPOSIXPathStyle = 0;

    static final StructLayout CFRANGE = MemoryLayout.structLayout(
            JAVA_LONG.withName("location"), JAVA_LONG.withName("length"));

    static final Linker LINKER = Linker.nativeLinker();

    private static final Framework CF = framework("CoreFoundation");

    private static final MethodHandle CF_ARRAY_GET_COUNT = CF.bind("CFArrayGetCount",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CF_ARRAY_GET_VALUE_AT_INDEX = CF.bind("CFArrayGetValueAtIndex",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CF_ATTRIBUTED_STRING_CREATE = CF.bind("CFAttributedStringCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CF_DATA_GET_BYTE_PTR = CF.bind("CFDataGetBytePtr",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CF_DATA_GET_LENGTH = CF.bind("CFDataGetLength",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CF_DICTIONARY_ADD_VALUE = CF.bind("CFDictionaryAddValue",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CF_DICTIONARY_CREATE_MUTABLE = CF.bind("CFDictionaryCreateMutable",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle CF_DICTIONARY_GET_VALUE = CF.bind("CFDictionaryGetValue",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CF_LOCALE_COPY_ISO_LANGUAGE_CODES = CF.bind("CFLocaleCopyISOLanguageCodes",
            FunctionDescriptor.of(ADDRESS));
    // C bool and Boolean are one byte: JAVA_BOOLEAN, never JAVA_BYTE or JAVA_INT (R-2).
    private static final MethodHandle CF_NUMBER_GET_VALUE = CF.bind("CFNumberGetValue",
            FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle CF_RELEASE = CF.bind("CFRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CF_RETAIN = CF.bind("CFRetain",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CF_STRING_COMPARE = CF.bind("CFStringCompare",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CF_STRING_CREATE_WITH_CHARACTERS = CF.bind("CFStringCreateWithCharacters",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CF_STRING_FIND = CF.bind("CFStringFind",
            FunctionDescriptor.of(CFRANGE, ADDRESS, ADDRESS, JAVA_LONG));
    // UniChar by value is JAVA_CHAR: Apple arm64 leaves the extension of sub-int values to the caller (R-1).
    private static final MethodHandle CF_STRING_GET_CHARACTER_AT_INDEX = CF.bind("CFStringGetCharacterAtIndex",
            FunctionDescriptor.of(JAVA_CHAR, ADDRESS, JAVA_LONG));
    private static final MethodHandle CF_STRING_GET_CHARACTERS = CF.bind("CFStringGetCharacters",
            FunctionDescriptor.ofVoid(ADDRESS, CFRANGE, ADDRESS));
    private static final MethodHandle CF_STRING_GET_LENGTH = CF.bind("CFStringGetLength",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CF_URL_COPY_FILE_SYSTEM_PATH = CF.bind("CFURLCopyFileSystemPath",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CF_URL_CREATE_WITH_FILE_SYSTEM_PATH = CF.bind("CFURLCreateWithFileSystemPath",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_BOOLEAN));

    static final MemorySegment kCFAllocatorDefault = CF.dataValue("kCFAllocatorDefault");
    // Passed by address, as &kCFTypeDictionaryKeyCallBacks: the structs are never read from Java.
    static final MemorySegment kCFTypeDictionaryKeyCallBacks = CF.dataAddress("kCFTypeDictionaryKeyCallBacks");
    static final MemorySegment kCFTypeDictionaryValueCallBacks = CF.dataAddress("kCFTypeDictionaryValueCallBacks");

    private CFNative() {
    }

    /**
     * One framework's lookup and the registry of what its facade bound, written only by that facade's class
     * initialiser.
     */
    static final class Framework {
        private final String name;
        private final SymbolLookup lookup;
        private final Map<String, FunctionDescriptor> functions = new LinkedHashMap<>();
        private final List<String> data = new ArrayList<>();

        private Framework(String name, SymbolLookup lookup) {
            this.name = name;
            this.lookup = lookup;
        }

        String name() {
            return name;
        }

        @SuppressWarnings("restricted")
        MethodHandle bind(String function, FunctionDescriptor descriptor) {
            MethodHandle handle = LINKER.downcallHandle(find(function), descriptor);
            functions.put(name + "!" + function, descriptor);
            return handle;
        }

        /** The pointer stored at the symbol: an immortal CF constant. */
        @SuppressWarnings("restricted")
        MemorySegment dataValue(String symbol) {
            MemorySegment value = find(symbol).reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
            data.add(name + "!" + symbol);
            return value;
        }

        MemorySegment dataAddress(String symbol) {
            MemorySegment address = find(symbol);
            data.add(name + "!" + symbol);
            return address;
        }

        Map<String, FunctionDescriptor> functions() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        }

        List<String> data() {
            return List.copyOf(data);
        }

        private MemorySegment find(String symbol) {
            return lookup.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError(name + " does not export " + symbol));
        }
    }

    /** The framework's lookup for the life of the process; an {@link UnsatisfiedLinkError} if it cannot be opened. */
    @SuppressWarnings("restricted")
    static Framework framework(String name) {
        String installName = "/System/Library/Frameworks/" + name + ".framework/" + name;
        try {
            return new Framework(name, SymbolLookup.libraryLookup(installName, Arena.global()));
        } catch (IllegalArgumentException e) {
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("cannot load " + installName
                    + " (macOS font support): " + e.getMessage());
            error.initCause(e);
            throw error;
        }
    }

    /** Only linkage failures reach a facade's catch: CoreFoundation, CoreGraphics and CoreText cannot throw. */
    static Error unexpected(Throwable t) {
        if (t instanceof Error e) {
            return e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        return new AssertionError(t);
    }

    static Map<String, FunctionDescriptor> boundFunctions() {
        return CF.functions();
    }

    static List<String> boundData() {
        return CF.data();
    }

    @SuppressWarnings("restricted")
    private static MemorySegment bounded(MemorySegment segment, long byteSize) {
        return segment.address() == 0 ? segment : segment.reinterpret(byteSize);
    }

    /** The characters of {@code cfString}; {@code null} for NULL. Unpaired surrogates cross unchanged. */
    public static String cfToString(MemorySegment cfString) {
        if (cfString.address() == 0) {
            return null;
        }
        long length = CFStringGetLength(cfString);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment range = scratch.allocate(CFRANGE);
            range.set(JAVA_LONG, 8, length);
            MemorySegment buffer = scratch.allocate(JAVA_CHAR, length);
            CFStringGetCharacters(cfString, range, buffer);
            return new String(buffer.toArray(JAVA_CHAR));
        }
    }

    /** A new CFString, +1 to the caller, with the UTF-16 code units of {@code string}. */
    public static MemorySegment toCFString(String string) {
        char[] chars = string.toCharArray();
        try (Arena scratch = Arena.ofConfined()) {
            return CFStringCreateWithCharacters(kCFAllocatorDefault, scratch.allocateFrom(JAVA_CHAR, chars),
                                                chars.length);
        }
    }

    /** The {@code CFSTR("bold")} of the C: made on first use and never released. */
    static MemorySegment boldCFString() {
        return Bold.STRING;
    }

    private static final class Bold {
        static final MemorySegment STRING = toCFString("bold");
    }

    public static long CFArrayGetCount(MemorySegment array) {
        try {
            return (long) CF_ARRAY_GET_COUNT.invokeExact(array);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Borrowed. Some arrays hold raw values rather than CF objects: read those with {@code address()}. */
    public static MemorySegment CFArrayGetValueAtIndex(MemorySegment array, long index) {
        try {
            return (MemorySegment) CF_ARRAY_GET_VALUE_AT_INDEX.invokeExact(array, index);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static MemorySegment CFAttributedStringCreate(MemorySegment allocator, MemorySegment string,
                                                  MemorySegment attributes) {
        try {
            return (MemorySegment) CF_ATTRIBUTED_STRING_CREATE.invokeExact(allocator, string, attributes);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The bytes of {@code data}, readable for {@code length} bytes while {@code data} lives. */
    public static MemorySegment CFDataGetBytePtr(MemorySegment data, long length) {
        try {
            return bounded((MemorySegment) CF_DATA_GET_BYTE_PTR.invokeExact(data), length);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long CFDataGetLength(MemorySegment data) {
        try {
            return (long) CF_DATA_GET_LENGTH.invokeExact(data);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void CFDictionaryAddValue(MemorySegment dictionary, MemorySegment key, MemorySegment value) {
        try {
            CF_DICTIONARY_ADD_VALUE.invokeExact(dictionary, key, value);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static MemorySegment CFDictionaryCreateMutable(MemorySegment allocator, long capacity, MemorySegment keyCallBacks,
                                                   MemorySegment valueCallBacks) {
        try {
            return (MemorySegment) CF_DICTIONARY_CREATE_MUTABLE.invokeExact(allocator, capacity, keyCallBacks,
                                                                             valueCallBacks);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static MemorySegment CFDictionaryGetValue(MemorySegment dictionary, MemorySegment key) {
        try {
            return (MemorySegment) CF_DICTIONARY_GET_VALUE.invokeExact(dictionary, key);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static MemorySegment CFLocaleCopyISOLanguageCodes() {
        try {
            return (MemorySegment) CF_LOCALE_COPY_ISO_LANGUAGE_CODES.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static boolean CFNumberGetValue(MemorySegment number, long type, MemorySegment value) {
        try {
            return (boolean) CF_NUMBER_GET_VALUE.invokeExact(number, type, value);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static void CFRelease(MemorySegment object) {
        try {
            CF_RELEASE.invokeExact(object);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static MemorySegment CFRetain(MemorySegment object) {
        try {
            return (MemorySegment) CF_RETAIN.invokeExact(object);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static long CFStringCompare(MemorySegment string1, MemorySegment string2, long options) {
        try {
            return (long) CF_STRING_COMPARE.invokeExact(string1, string2, options);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static MemorySegment CFStringCreateWithCharacters(MemorySegment allocator, MemorySegment chars, long numChars) {
        try {
            return (MemorySegment) CF_STRING_CREATE_WITH_CHARACTERS.invokeExact(allocator, chars, numChars);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A {@code CFRANGE} segment from {@code allocator}. */
    static MemorySegment CFStringFind(SegmentAllocator allocator, MemorySegment string, MemorySegment find,
                                      long options) {
        try {
            return (MemorySegment) CF_STRING_FIND.invokeExact(allocator, string, find, options);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static char CFStringGetCharacterAtIndex(MemorySegment string, long index) {
        try {
            return (char) CF_STRING_GET_CHARACTER_AT_INDEX.invokeExact(string, index);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code range} is a {@code CFRANGE} segment, passed by value. */
    static void CFStringGetCharacters(MemorySegment string, MemorySegment range, MemorySegment buffer) {
        try {
            CF_STRING_GET_CHARACTERS.invokeExact(string, range, buffer);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static long CFStringGetLength(MemorySegment string) {
        try {
            return (long) CF_STRING_GET_LENGTH.invokeExact(string);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    public static MemorySegment CFURLCopyFileSystemPath(MemorySegment url, long pathStyle) {
        try {
            return (MemorySegment) CF_URL_COPY_FILE_SYSTEM_PATH.invokeExact(url, pathStyle);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static MemorySegment CFURLCreateWithFileSystemPath(MemorySegment allocator, MemorySegment filePath, long pathStyle,
                                                       boolean isDirectory) {
        try {
            return (MemorySegment) CF_URL_CREATE_WITH_FILE_SYSTEM_PATH.invokeExact(allocator, filePath, pathStyle,
                                                                                    isDirectory);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
