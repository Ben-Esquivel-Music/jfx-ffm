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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Test-only CoreText, libobjc and malloc probes for the macOS NULL-input and release tests, over the bindings and
 * toolkit of {@link MacFontOracleShim}. An observer like it: it uses no font class of the product
 * ({@code MacFontBindings.requireSameBinding} checks). Every object it hands out is at +1, for the caller to release
 * with {@link MacFontOracleShim#release(long)}.
 */
public final class MacFontProbeShim {

    private static final MethodHandle CT_FONT_DESCRIPTOR_CREATE_WITH_NAME_AND_SIZE = MacFontOracleShim.bind(
            MacFontOracleShim.Lib.CT, "CTFontDescriptorCreateWithNameAndSize",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE));
    private static final MethodHandle CT_FONT_DESCRIPTOR_COPY_ATTRIBUTE = MacFontOracleShim.bind(
            MacFontOracleShim.Lib.CT, "CTFontDescriptorCopyAttribute",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CT_FONT_COPY_DEFAULT_CASCADE_LIST_FOR_LANGUAGES = MacFontOracleShim.bind(
            MacFontOracleShim.Lib.CT, "CTFontCopyDefaultCascadeListForLanguages",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CF_LOCALE_COPY_ISO_LANGUAGE_CODES = MacFontOracleShim.bind(
            MacFontOracleShim.Lib.CF, "CFLocaleCopyISOLanguageCodes", FunctionDescriptor.of(ADDRESS));

    private static final SymbolLookup OBJC = MacFontOracleShim.load("/usr/lib/libobjc.A.dylib");
    private static final MethodHandle OBJC_AUTORELEASE_POOL_PUSH = MacFontOracleShim.bind(
            OBJC, "objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle OBJC_AUTORELEASE_POOL_POP = MacFontOracleShim.bind(
            OBJC, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle MALLOC_ZONE_STATISTICS = MacFontOracleShim.bind(
            MacFontOracleShim.LINKER.defaultLookup(), "malloc_zone_statistics",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final long MALLOC_STATISTICS_BYTES = 32;

    private MacFontProbeShim() {
    }

    /** {@code CTFontCreateUIFontForLanguage(type, 0, NULL)}; 0 when CoreText has no font of that type. */
    public static long uiFont(int type) {
        try {
            return ((MemorySegment) MacFontOracleShim.Lib.CT_FONT_CREATE_UI_FONT_FOR_LANGUAGE.invokeExact(type, 0.0,
                    MemorySegment.NULL)).address();
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    /** {@code CTFontDescriptorCreateWithNameAndSize}. */
    public static long createFontDescriptor(String name, double size) {
        MemorySegment nameRef = MacFontOracleShim.createString(name);
        try {
            return ((MemorySegment) CT_FONT_DESCRIPTOR_CREATE_WITH_NAME_AND_SIZE.invokeExact(nameRef, size)).address();
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        } finally {
            MacFontOracleShim.release(nameRef);
        }
    }

    /** Whether the descriptor has a {@code kCTFontDisplayNameAttribute}. */
    public static boolean hasDisplayName(long descriptor) {
        try {
            MemorySegment displayName = (MemorySegment) CT_FONT_DESCRIPTOR_COPY_ATTRIBUTE.invokeExact(
                    MemorySegment.ofAddress(descriptor), MacFontOracleShim.Lib.K_CT_FONT_DISPLAY_NAME_ATTRIBUTE);
            MacFontOracleShim.release(displayName);
            return displayName.address() != 0;
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    /** The {@code kCTFontNameAttribute} of one entry of a fresh default cascade list of font; 0 past the end. */
    public static long cascadeEntryName(long font, int entry) {
        try {
            MemorySegment codes = (MemorySegment) CF_LOCALE_COPY_ISO_LANGUAGE_CODES.invokeExact();
            MemorySegment descriptors = (MemorySegment) CT_FONT_COPY_DEFAULT_CASCADE_LIST_FOR_LANGUAGES.invokeExact(
                    MemorySegment.ofAddress(font), codes);
            MacFontOracleShim.release(codes);
            if (descriptors.address() == 0) {
                return 0;
            }
            try {
                long count = (long) MacFontOracleShim.Lib.CF_ARRAY_GET_COUNT.invokeExact(descriptors);
                if (entry >= count) {
                    return 0;
                }
                MemorySegment descriptor = MacFontOracleShim.arrayValue(descriptors, entry);
                return ((MemorySegment) CT_FONT_DESCRIPTOR_COPY_ATTRIBUTE.invokeExact(descriptor,
                        MacFontOracleShim.Lib.K_CT_FONT_NAME_ATTRIBUTE)).address();
            } finally {
                MacFontOracleShim.release(descriptors);
            }
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    public static long autoreleasePoolPush() {
        try {
            return ((MemorySegment) OBJC_AUTORELEASE_POOL_PUSH.invokeExact()).address();
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    public static void autoreleasePoolPop(long pool) {
        try {
            OBJC_AUTORELEASE_POOL_POP.invokeExact(MemorySegment.ofAddress(pool));
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }

    /** {@code blocks_in_use} of {@code malloc_zone_statistics(NULL, &stats)}, all zones. */
    public static long mallocBlocksInUse() {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment statistics = scratch.allocate(MALLOC_STATISTICS_BYTES);
            MALLOC_ZONE_STATISTICS.invokeExact(MemorySegment.NULL, statistics);
            return Integer.toUnsignedLong(statistics.get(JAVA_INT, 0));
        } catch (Throwable t) {
            throw MacFontOracleShim.unexpected(t);
        }
    }
}
