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

package com.sun.javafx.font;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The machine-identity probes of the macOS goldens' gate, read in-process with the oracle's own bindings, never
 * through the natives under test: framework LC_UUIDs as loaded, the dyld shared cache UUID, the user's preferences
 * and the fonts CoreText makes available. {@code /usr/bin/dyld_info} is an xcrun shim, hence the walk here.
 */
@SuppressWarnings("restricted")
public final class MacMachineShim {

    private static final int LC_UUID = 0x1b;
    private static final int MH_MAGIC_64 = 0xfeedfacf;
    private static final long K_CF_NUMBER_SINT64_TYPE = 4;
    private static final long K_CF_NUMBER_FLOAT64_TYPE = 6;

    private MacMachineShim() {
    }

    private static final class Lib {
        static final SymbolLookup CF = MacFontOracleShim.Lib.CF;
        static final SymbolLookup CT = MacFontOracleShim.Lib.CT;

        static final MethodHandle CF_PREFERENCES_COPY_VALUE = MacFontOracleShim.bind(CF, "CFPreferencesCopyValue",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CT_FONT_COLLECTION_CREATE_FROM_AVAILABLE_FONTS = MacFontOracleShim.bind(CT,
                "CTFontCollectionCreateFromAvailableFonts", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CT_FONT_COLLECTION_CREATE_MATCHING_FONT_DESCRIPTORS = MacFontOracleShim.bind(CT,
                "CTFontCollectionCreateMatchingFontDescriptors", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CT_FONT_DESCRIPTOR_COPY_ATTRIBUTE = MacFontOracleShim.bind(CT,
                "CTFontDescriptorCopyAttribute", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CF_STRING_GET_TYPE_ID = typeId("CFStringGetTypeID");
        static final MethodHandle CF_NUMBER_GET_TYPE_ID = typeId("CFNumberGetTypeID");
        static final MethodHandle CF_BOOLEAN_GET_TYPE_ID = typeId("CFBooleanGetTypeID");
        static final MethodHandle CF_ARRAY_GET_TYPE_ID = typeId("CFArrayGetTypeID");
        static final MethodHandle CF_NUMBER_IS_FLOAT_TYPE = MacFontOracleShim.bind(CF, "CFNumberIsFloatType",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
        static final MethodHandle CF_NUMBER_GET_VALUE = MacFontOracleShim.bind(CF, "CFNumberGetValue",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_LONG, ADDRESS));
        static final MethodHandle CF_NUMBER_CREATE = MacFontOracleShim.bind(CF, "CFNumberCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        static final MethodHandle CF_BOOLEAN_GET_VALUE = MacFontOracleShim.bind(CF, "CFBooleanGetValue",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
        static final MethodHandle CF_ARRAY_CREATE = MacFontOracleShim.bind(CF, "CFArrayCreate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));

        static final MemorySegment K_CF_PREFERENCES_ANY_APPLICATION =
                MacFontOracleShim.dataValue(CF, "kCFPreferencesAnyApplication");
        static final MemorySegment K_CF_PREFERENCES_CURRENT_USER =
                MacFontOracleShim.dataValue(CF, "kCFPreferencesCurrentUser");
        static final MemorySegment K_CF_PREFERENCES_ANY_HOST = MacFontOracleShim.dataValue(CF, "kCFPreferencesAnyHost");
        static final MemorySegment K_CF_PREFERENCES_CURRENT_HOST =
                MacFontOracleShim.dataValue(CF, "kCFPreferencesCurrentHost");
        static final MemorySegment K_CT_FONT_NAME_ATTRIBUTE = MacFontOracleShim.dataValue(CT, "kCTFontNameAttribute");
        static final MemorySegment K_CT_FONT_DISPLAY_NAME_ATTRIBUTE =
                MacFontOracleShim.dataValue(CT, "kCTFontDisplayNameAttribute");
        static final MemorySegment K_CT_FONT_FAMILY_NAME_ATTRIBUTE =
                MacFontOracleShim.dataValue(CT, "kCTFontFamilyNameAttribute");
        static final MemorySegment K_CT_FONT_URL_ATTRIBUTE = MacFontOracleShim.dataValue(CT, "kCTFontURLAttribute");
        static final MemorySegment K_CF_BOOLEAN_TRUE = MacFontOracleShim.dataValue(CF, "kCFBooleanTrue");
        static final MemorySegment K_CF_BOOLEAN_FALSE = MacFontOracleShim.dataValue(CF, "kCFBooleanFalse");
        static final MemorySegment K_CF_TYPE_ARRAY_CALL_BACKS = CF.findOrThrow("kCFTypeArrayCallBacks");

        private static MethodHandle typeId(String function) {
            return MacFontOracleShim.bind(CF, function, FunctionDescriptor.of(JAVA_LONG));
        }
    }

    private static final class Dyld {
        static final SymbolLookup SYSTEM = MacFontOracleShim.LINKER.defaultLookup();
        static final MethodHandle IMAGE_COUNT = MacFontOracleShim.bind(SYSTEM, "_dyld_image_count",
                FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle GET_IMAGE_NAME = MacFontOracleShim.bind(SYSTEM, "_dyld_get_image_name",
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        static final MethodHandle GET_IMAGE_HEADER = MacFontOracleShim.bind(SYSTEM, "_dyld_get_image_header",
                FunctionDescriptor.of(ADDRESS, JAVA_INT));
        static final MethodHandle GET_SHARED_CACHE_UUID = MacFontOracleShim.bind(SYSTEM,
                "_dyld_get_shared_cache_uuid", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
    }

    /**
     * The LC_UUID of the loaded image whose install name ends with {@code suffix}, or {@code null}. The frameworks
     * are loaded first, so that their images are in dyld's list.
     */
    public static String imageUuid(String suffix) {
        MacFontOracleShim.available();
        try {
            int count = (int) Dyld.IMAGE_COUNT.invokeExact();
            for (int i = 0; i < count; i++) {
                MemorySegment name = (MemorySegment) Dyld.GET_IMAGE_NAME.invokeExact(i);
                if (name.address() == 0 || !name.reinterpret(4096).getString(0).endsWith(suffix)) {
                    continue;
                }
                MemorySegment header = (MemorySegment) Dyld.GET_IMAGE_HEADER.invokeExact(i);
                return machUuid(header);
            }
            return null;
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    private static String machUuid(MemorySegment headerAddress) {
        MemorySegment header = headerAddress.reinterpret(32);
        if (header.get(JAVA_INT, 0) != MH_MAGIC_64) {
            return "not-mach-o-64";
        }
        int ncmds = header.get(JAVA_INT, 16);
        int sizeofcmds = header.get(JAVA_INT, 20);
        MemorySegment commands = headerAddress.reinterpret(32L + sizeofcmds).asSlice(32);
        long offset = 0;
        for (int c = 0; c < ncmds && offset + 8 <= sizeofcmds; c++) {
            int cmd = commands.get(JAVA_INT, offset);
            int cmdsize = commands.get(JAVA_INT, offset + 4);
            if (cmd == LC_UUID) {
                return formatUuid(commands.asSlice(offset + 8, 16).toArray(JAVA_BYTE));
            }
            if (cmdsize <= 0) {
                break;
            }
            offset += cmdsize;
        }
        return null;
    }

    private static String formatUuid(byte[] uuid) {
        String hex = HexFormat.of().withUpperCase().formatHex(uuid);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    public static String sharedCacheUuid() {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment uuid = scratch.allocate(16);
            boolean ok = (boolean) Dyld.GET_SHARED_CACHE_UUID.invokeExact(uuid);
            return ok ? formatUuid(uuid.toArray(JAVA_BYTE)) : null;
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    /** The global-domain value of {@code key} for the current user, as {@link #preferenceText} text, or null. */
    public static String preference(String key, boolean currentHost) {
        MemorySegment cfKey = MacFontOracleShim.createString(key);
        MemorySegment host = currentHost ? Lib.K_CF_PREFERENCES_CURRENT_HOST : Lib.K_CF_PREFERENCES_ANY_HOST;
        try {
            MemorySegment value = (MemorySegment) Lib.CF_PREFERENCES_COPY_VALUE.invokeExact(cfKey,
                    Lib.K_CF_PREFERENCES_ANY_APPLICATION, Lib.K_CF_PREFERENCES_CURRENT_USER, host);
            if (value.address() == 0) {
                return null;
            }
            String text = preferenceText(value);
            MacFontOracleShim.release(value);
            return text;
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        } finally {
            MacFontOracleShim.release(cfKey);
        }
    }

    /**
     * A string as its text, a number in decimal, a boolean as {@code true} or {@code false}, an array of strings as
     * {@code CFCopyDescription} writes it and any other array as {@code [element,...]}; anything else as its type.
     * {@code CFCopyDescription} of a number or boolean holds a tagged-pointer or shared-cache address, which
     * changes per process or per boot.
     */
    private static String preferenceText(MemorySegment value) throws Throwable {
        long type = (long) MacFontOracleShim.Lib.CF_GET_TYPE_ID.invokeExact(value);
        if (type == (long) Lib.CF_STRING_GET_TYPE_ID.invokeExact()) {
            return MacFontOracleShim.string(value);
        }
        if (type == (long) Lib.CF_NUMBER_GET_TYPE_ID.invokeExact()) {
            return numberText(value);
        }
        if (type == (long) Lib.CF_BOOLEAN_GET_TYPE_ID.invokeExact()) {
            return Boolean.toString((boolean) Lib.CF_BOOLEAN_GET_VALUE.invokeExact(value));
        }
        if (type != (long) Lib.CF_ARRAY_GET_TYPE_ID.invokeExact()) {
            return "<" + MacFontOracleShim.cfTypeName(value.address()) + ">";
        }
        long count = MacFontOracleShim.arrayCount(value.address());
        List<String> elements = new ArrayList<>();
        boolean strings = true;
        for (long i = 0; i < count; i++) {
            MemorySegment element = MacFontOracleShim.arrayValue(value, i);
            strings &= (long) MacFontOracleShim.Lib.CF_GET_TYPE_ID.invokeExact(element)
                    == (long) Lib.CF_STRING_GET_TYPE_ID.invokeExact();
            elements.add(preferenceText(element));
        }
        return strings ? MacFontOracleShim.describe(value) : "[" + String.join(",", elements) + "]";
    }

    private static String numberText(MemorySegment number) throws Throwable {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment slot = scratch.allocate(JAVA_LONG);
            if ((boolean) Lib.CF_NUMBER_IS_FLOAT_TYPE.invokeExact(number)) {
                boolean exact = (boolean) Lib.CF_NUMBER_GET_VALUE.invokeExact(number, K_CF_NUMBER_FLOAT64_TYPE, slot);
                return Double.toString(slot.get(JAVA_DOUBLE, 0)) + (exact ? "" : "~");
            }
            boolean exact = (boolean) Lib.CF_NUMBER_GET_VALUE.invokeExact(number, K_CF_NUMBER_SINT64_TYPE, slot);
            return Long.toString(slot.get(JAVA_LONG, 0)) + (exact ? "" : "~");
        }
    }

    /**
     * {@link #preferenceText} of a CF value made from {@code value}: a {@code String}, {@code Long}, {@code Double},
     * {@code Boolean} or a {@code List} of those.
     */
    public static String preferenceTextOf(Object value) {
        try {
            MemorySegment object = createValue(value);
            String text = preferenceText(object);
            releaseValue(object);
            return text;
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    private static MemorySegment createValue(Object value) throws Throwable {
        try (Arena scratch = Arena.ofConfined()) {
            return switch (value) {
                case String text -> MacFontOracleShim.createString(text);
                case Long number -> (MemorySegment) Lib.CF_NUMBER_CREATE.invokeExact(
                        MacFontOracleShim.Lib.K_CF_ALLOCATOR_DEFAULT, K_CF_NUMBER_SINT64_TYPE,
                        scratch.allocateFrom(JAVA_LONG, number));
                case Double number -> (MemorySegment) Lib.CF_NUMBER_CREATE.invokeExact(
                        MacFontOracleShim.Lib.K_CF_ALLOCATOR_DEFAULT, K_CF_NUMBER_FLOAT64_TYPE,
                        scratch.allocateFrom(JAVA_DOUBLE, number));
                case Boolean flag -> flag ? Lib.K_CF_BOOLEAN_TRUE : Lib.K_CF_BOOLEAN_FALSE;
                case List<?> list -> createArray(list, scratch);
                default -> throw new IllegalArgumentException("no CF value for " + value);
            };
        }
    }

    private static MemorySegment createArray(List<?> list, Arena scratch) throws Throwable {
        MemorySegment values = scratch.allocate(ADDRESS, Math.max(1, list.size()));
        for (int i = 0; i < list.size(); i++) {
            values.setAtIndex(ADDRESS, i, createValue(list.get(i)));
        }
        long count = list.size();
        MemorySegment array = (MemorySegment) Lib.CF_ARRAY_CREATE.invokeExact(
                MacFontOracleShim.Lib.K_CF_ALLOCATOR_DEFAULT, values, count, Lib.K_CF_TYPE_ARRAY_CALL_BACKS);
        for (int i = 0; i < list.size(); i++) {
            releaseValue(values.getAtIndex(ADDRESS, i));
        }
        return array;
    }

    private static void releaseValue(MemorySegment object) {
        long address = object.address();
        if (address != Lib.K_CF_BOOLEAN_TRUE.address() && address != Lib.K_CF_BOOLEAN_FALSE.address()) {
            MacFontOracleShim.release(object);
        }
    }

    /**
     * {@code postScriptName|displayName|family|path} of every font CoreText makes available, in collection order.
     * Fonts registered for the process join this list, which makes it the pristine-process guard too.
     */
    public static List<String> availableFonts() {
        try {
            MemorySegment collection =
                    (MemorySegment) Lib.CT_FONT_COLLECTION_CREATE_FROM_AVAILABLE_FONTS.invokeExact(MemorySegment.NULL);
            MemorySegment descriptors =
                    (MemorySegment) Lib.CT_FONT_COLLECTION_CREATE_MATCHING_FONT_DESCRIPTORS.invokeExact(collection);
            MacFontOracleShim.release(collection);
            List<String> fonts = new ArrayList<>();
            long count = MacFontOracleShim.arrayCount(descriptors.address());
            for (long i = 0; i < count; i++) {
                MemorySegment descriptor = MacFontOracleShim.arrayValue(descriptors, i);
                String name = descriptorString(descriptor, Lib.K_CT_FONT_NAME_ATTRIBUTE);
                String display = descriptorString(descriptor, Lib.K_CT_FONT_DISPLAY_NAME_ATTRIBUTE);
                String family = descriptorString(descriptor, Lib.K_CT_FONT_FAMILY_NAME_ATTRIBUTE);
                MemorySegment url = (MemorySegment) Lib.CT_FONT_DESCRIPTOR_COPY_ATTRIBUTE.invokeExact(descriptor,
                        Lib.K_CT_FONT_URL_ATTRIBUTE);
                String path = url.address() == 0 ? null : MacFontOracleShim.urlPath(url.address());
                MacFontOracleShim.release(url);
                fonts.add(name + "|" + display + "|" + family + "|" + path);
            }
            MacFontOracleShim.release(descriptors);
            return fonts;
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    private static String descriptorString(MemorySegment descriptor, MemorySegment attribute) throws Throwable {
        MemorySegment value = (MemorySegment) Lib.CT_FONT_DESCRIPTOR_COPY_ATTRIBUTE.invokeExact(descriptor, attribute);
        String text = MacFontOracleShim.string(value);
        MacFontOracleShim.release(value);
        return text;
    }
}
