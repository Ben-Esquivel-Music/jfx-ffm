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

package com.sun.javafx.font.directwrite;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static com.sun.javafx.font.directwrite.DWNative.E_FAIL;
import static com.sun.javafx.font.directwrite.DWNative.HR_3A;
import static com.sun.javafx.font.directwrite.DWNative.HR_I_OUT;
import static com.sun.javafx.font.directwrite.DWNative.HR_P_OUT;
import static com.sun.javafx.font.directwrite.DWNative.U32_THIS;
import static com.sun.javafx.font.directwrite.DWNative.com;
import static com.sun.javafx.font.directwrite.DWNative.outPointer;
import static com.sun.javafx.font.directwrite.DWNative.slot;
import static com.sun.javafx.font.directwrite.DWNative.u32;
import static com.sun.javafx.font.directwrite.DWNative.unexpected;
import static com.sun.javafx.font.directwrite.DWNative.virtual;
import static com.sun.javafx.font.directwrite.DWNative.wide;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The {@link DWNative} calls on the font objects: the {@code IDWriteFactory} methods that make fonts, and
 * {@code IDWriteFontCollection}, {@code IDWriteFontList}, {@code IDWriteFontFamily}, {@code IDWriteFont},
 * {@code IDWriteLocalizedStrings} and {@code IDWriteFontFile}.
 * <p>
 * Line numbers into {@code directwrite.cpp} (bare {@code :N} included) and the SDK headers follow
 * {@link DWNative}: commit {@code 8492cb03b0}, Windows SDK 10.0.26100.0.
 */
final class DWFontCalls {

    /* IDWriteFactory : IUnknown, dwrite.h:4704. base 3 + ordinal. */
    private static final int FACTORY_GET_SYSTEM_FONT_COLLECTION = 3;   // ord 0, dwrite.h:4717
    private static final int FACTORY_CREATE_FONT_FILE_REFERENCE = 7;   // ord 4, dwrite.h:4775
    private static final int FACTORY_CREATE_FONT_FACE = 9;             // ord 6, dwrite.h:4821

    /* IDWriteFontCollection : IUnknown, dwrite.h:1459. base 3 + ordinal. */
    private static final int COLLECTION_GET_FONT_FAMILY_COUNT = 3;     // ord 0, dwrite.h:1464
    private static final int COLLECTION_GET_FONT_FAMILY = 4;           // ord 1, dwrite.h:1474
    private static final int COLLECTION_FIND_FAMILY_NAME = 5;          // ord 2, dwrite.h:1488
    private static final int COLLECTION_GET_FONT_FROM_FONT_FACE = 6;   // ord 3, dwrite.h:1503

    /* IDWriteFontList : IUnknown, dwrite.h:1512. base 3 + ordinal; reached through a family pointer. */
    private static final int FONT_LIST_GET_FONT_COUNT = 4;             // ord 1, dwrite.h:1528
    private static final int FONT_LIST_GET_FONT = 5;                   // ord 2, dwrite.h:1538

    /* IDWriteFontFamily : IDWriteFontList, dwrite.h:1548. base 6 = 3 IUnknown + 3 IDWriteFontList. */
    private static final int FONT_FAMILY_GET_FAMILY_NAMES = 6;         // ord 0, dwrite.h:1557
    private static final int FONT_FAMILY_GET_FIRST_MATCHING_FONT = 7;  // ord 1, dwrite.h:1571

    /* IDWriteFont : IUnknown, dwrite.h:1599. base 3 + ordinal. */
    private static final int FONT_GET_FONT_FAMILY = 3;                 // ord 0, dwrite.h:1608
    private static final int FONT_GET_WEIGHT = 4;                      // ord 1, dwrite.h:1615
    private static final int FONT_GET_STRETCH = 5;                     // ord 2, dwrite.h:1620
    private static final int FONT_GET_STYLE = 6;                       // ord 3, dwrite.h:1625
    private static final int FONT_GET_FACE_NAMES = 8;                  // ord 5, dwrite.h:1639
    private static final int FONT_GET_INFORMATIONAL_STRINGS = 9;       // ord 6, dwrite.h:1653
    private static final int FONT_GET_SIMULATIONS = 10;                // ord 7, dwrite.h:1662
    private static final int FONT_CREATE_FONT_FACE = 13;               // ord 10, dwrite.h:1692

    /* IDWriteLocalizedStrings : IUnknown, dwrite.h:1371. base 3 + ordinal. */
    private static final int STRINGS_FIND_LOCALE_NAME = 4;             // ord 1, dwrite.h:1388
    private static final int STRINGS_GET_STRING_LENGTH = 7;            // ord 4, dwrite.h:1431
    private static final int STRINGS_GET_STRING = 8;                   // ord 5, dwrite.h:1446

    /* IDWriteFontFile : IUnknown, dwrite.h:822. base 3 + ordinal. */
    private static final int FONT_FILE_ANALYZE = 5;                    // ord 2, dwrite.h:868

    /** {@code HRESULT f(this, void** out, BOOL)}: GetSystemFontCollection - the BOOL is 32-bit. */
    private static final MethodHandle HR_OUT_BOOL =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    /** {@code HRESULT f(this, void*, void*, void*, void*)}: {@code IDWriteFontFile::Analyze} only. */
    private static final MethodHandle HR_4A =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code HRESULT f(this, UINT32, void** out, BOOL* exists)}: GetInformationalStrings. */
    private static final MethodHandle HR_I_OUT_BOOL =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** {@code HRESULT f(this, UINT32 index, WCHAR* buffer, UINT32 size)}: GetString. */
    private static final MethodHandle HR_I_BUF_I =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    /** {@code HRESULT f(this, weight, stretch, style, void** out)}: GetFirstMatchingFont. */
    private static final MethodHandle HR_III_OUT =
            virtual(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));

    /**
     * {@code HRESULT f(this, DWRITE_FONT_FACE_TYPE, UINT32 numberOfFiles, IDWriteFontFile* const*,
     * UINT32 faceIndex, DWRITE_FONT_SIMULATIONS, IDWriteFontFace** out)}: IDWriteFactory::CreateFontFace.
     */
    private static final MethodHandle HR_FONT_FACE = virtual(FunctionDescriptor.of(
            JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    private DWFontCalls() {
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFactory - dwrite.h:4704
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code IDWriteFactory::GetSystemFontCollection}, slot 3 (dwrite.h:4717). {@code checkForUpdates}
     * crosses as a 32-bit {@code BOOL}, where the JNI argument was an 8-bit {@code jboolean}.
     */
    static long getSystemFontCollection(long self, boolean checkForUpdates) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_OUT_BOOL.invokeExact(slot(obj, FACTORY_GET_SYSTEM_FONT_COLLECTION), obj, out,
                    checkForUpdates ? 1 : 0);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteFactory::CreateFontFileReference}, slot 7 (dwrite.h:4775). {@code lastWriteTime} is
     * {@code NULL}, as at {@code directwrite.cpp:1870}.
     */
    static long createFontFileReference(long self, char[] filePath) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment path = wide(scratch, filePath);
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_3A.invokeExact(slot(obj, FACTORY_CREATE_FONT_FILE_REFERENCE), obj, path,
                    MemorySegment.NULL, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteFactory::CreateFontFace}, slot 9 (dwrite.h:4821), with the one-element
     * {@code IDWriteFontFile* const*} the C built on the stack ({@code directwrite.cpp:1880-1881}).
     */
    static long createFontFace(long self, int fontFaceType, long fontFile, int faceIndex,
                               int fontFaceSimulationFlags) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment files = scratch.allocate(ADDRESS);
            files.set(ADDRESS, 0, com(fontFile));
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_FONT_FACE.invokeExact(slot(obj, FACTORY_CREATE_FONT_FACE), obj, fontFaceType,
                    1, files, faceIndex, fontFaceSimulationFlags, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFontCollection - dwrite.h:1459
     * ------------------------------------------------------------------------------------------- */

    /** {@code IDWriteFontCollection::GetFontFamilyCount}, slot 3 (dwrite.h:1464); no error channel. */
    static int getFontFamilyCount(long self) {
        MemorySegment obj = com(self);
        try {
            return (int) U32_THIS.invokeExact(slot(obj, COLLECTION_GET_FONT_FAMILY_COUNT), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code IDWriteFontCollection::GetFontFamily}, slot 4 (dwrite.h:1474). */
    static long getFontFamily(long self, int index) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_I_OUT.invokeExact(slot(obj, COLLECTION_GET_FONT_FAMILY), obj, index, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteFontCollection::FindFamilyName}, slot 5 (dwrite.h:1488): the family index, or
     * {@code -1} when the call failed or {@code exists} came back FALSE ({@code directwrite.cpp:2159}).
     * {@code DWFontFile} branches on that {@code -1}, so a {@code 0} here would silently pick family 0.
     */
    static int findFamilyName(long self, char[] familyName) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment name = wide(scratch, familyName);
            MemorySegment index = scratch.allocate(JAVA_INT);
            MemorySegment exists = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_3A.invokeExact(slot(obj, COLLECTION_FIND_FAMILY_NAME), obj, name, index, exists);
            return hr >= 0 && exists.get(JAVA_INT, 0) != 0 ? index.get(JAVA_INT, 0) : -1;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code IDWriteFontCollection::GetFontFromFontFace}, slot 6 (dwrite.h:1503). */
    static long getFontFromFontFace(long self, long fontFace) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_P_OUT.invokeExact(slot(obj, COLLECTION_GET_FONT_FROM_FONT_FACE), obj,
                    com(fontFace), out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFontList - dwrite.h:1512, reached through an IDWriteFontFamily pointer
     * ------------------------------------------------------------------------------------------- */

    /** {@code IDWriteFontList::GetFontCount}, slot 4 (dwrite.h:1528); no error channel. */
    static int getFontCount(long self) {
        MemorySegment obj = com(self);
        try {
            return (int) U32_THIS.invokeExact(slot(obj, FONT_LIST_GET_FONT_COUNT), obj);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code IDWriteFontList::GetFont}, slot 5 (dwrite.h:1538). */
    static long getFont(long self, int index) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_I_OUT.invokeExact(slot(obj, FONT_LIST_GET_FONT), obj, index, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFontFamily - dwrite.h:1548 (base IDWriteFontList, so its own methods start at slot 6)
     * ------------------------------------------------------------------------------------------- */

    /** {@code IDWriteFontFamily::GetFamilyNames}, slot 6 (dwrite.h:1557). */
    static long getFamilyNames(long self) {
        return outPointer(self, FONT_FAMILY_GET_FAMILY_NAMES);
    }

    /** {@code IDWriteFontFamily::GetFirstMatchingFont}, slot 7 (dwrite.h:1571). */
    static long getFirstMatchingFont(long self, int weight, int stretch, int style) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment obj = com(self);
            int hr = (int) HR_III_OUT.invokeExact(slot(obj, FONT_FAMILY_GET_FIRST_MATCHING_FONT), obj,
                    weight, stretch, style, out);
            return hr >= 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFont - dwrite.h:1599
     * ------------------------------------------------------------------------------------------- */

    /** {@code IDWriteFont::CreateFontFace}, slot 13 (dwrite.h:1692). */
    static long createFontFace(long self) {
        return outPointer(self, FONT_CREATE_FONT_FACE);
    }

    /** {@code IDWriteFont::GetFaceNames}, slot 8 (dwrite.h:1639). */
    static long getFaceNames(long self) {
        return outPointer(self, FONT_GET_FACE_NAMES);
    }

    /** {@code IDWriteFont::GetFontFamily}, slot 3 (dwrite.h:1608). */
    static long getFontFamily(long self) {
        return outPointer(self, FONT_GET_FONT_FAMILY);
    }

    /** {@code IDWriteFont::GetStretch}, slot 5 (dwrite.h:1620); a {@code DWRITE_FONT_STRETCH}, no HRESULT. */
    static int getStretch(long self) {
        return u32(self, FONT_GET_STRETCH);
    }

    /** {@code IDWriteFont::GetStyle}, slot 6 (dwrite.h:1625); a {@code DWRITE_FONT_STYLE}, no HRESULT. */
    static int getStyle(long self) {
        return u32(self, FONT_GET_STYLE);
    }

    /** {@code IDWriteFont::GetWeight}, slot 4 (dwrite.h:1615); a {@code DWRITE_FONT_WEIGHT}, no HRESULT. */
    static int getWeight(long self) {
        return u32(self, FONT_GET_WEIGHT);
    }

    /**
     * {@code IDWriteFont::GetInformationalStrings}, slot 9 (dwrite.h:1653): the strings, or {@code 0}
     * when the font does not carry that string. {@code directwrite.cpp:2053} returns NULL whenever
     * {@code exists} is FALSE and releases nothing, because DirectWrite documents the out-pointer as
     * NULL in that case; the callers treat null as normal.
     */
    static long getInformationalStrings(long self, int informationalStringID) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            MemorySegment exists = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_I_OUT_BOOL.invokeExact(slot(obj, FONT_GET_INFORMATIONAL_STRINGS), obj,
                    informationalStringID, out, exists);
            return hr >= 0 && exists.get(JAVA_INT, 0) != 0 ? out.get(ADDRESS, 0).address() : 0L;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code IDWriteFont::GetSimulations}, slot 10 (dwrite.h:1662); flags, no HRESULT. */
    static int getSimulations(long self) {
        return u32(self, FONT_GET_SIMULATIONS);
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteLocalizedStrings - dwrite.h:1371
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code IDWriteLocalizedStrings::GetString}, slot 8 (dwrite.h:1446): exactly {@code size}
     * characters, the terminating NUL included, or {@code null} when the call failed
     * ({@code directwrite.cpp:2078-2092}). The peer asks for {@code length + 1} and trims.
     */
    static char[] getString(long self, int index, int size) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment buffer = scratch.allocate(JAVA_CHAR, size);
            MemorySegment obj = com(self);
            int hr = (int) HR_I_BUF_I.invokeExact(slot(obj, STRINGS_GET_STRING), obj, index, buffer, size);
            return hr >= 0 ? buffer.toArray(JAVA_CHAR) : null;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteLocalizedStrings::GetStringLength}, slot 7 (dwrite.h:1431): the length in
     * characters without the terminator, or {@code 0} when the call failed.
     */
    static int getStringLength(long self, int index) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment length = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_I_OUT.invokeExact(slot(obj, STRINGS_GET_STRING_LENGTH), obj, index, length);
            return hr >= 0 ? length.get(JAVA_INT, 0) : 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code IDWriteLocalizedStrings::FindLocaleName}, slot 4 (dwrite.h:1388): the index, or {@code -1}
     * when the call failed or the locale is absent ({@code directwrite.cpp:2113}).
     */
    static int findLocaleName(long self, char[] locale) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment name = wide(scratch, locale);
            MemorySegment index = scratch.allocate(JAVA_INT);
            MemorySegment exists = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_3A.invokeExact(slot(obj, STRINGS_FIND_LOCALE_NAME), obj, name, index, exists);
            return hr >= 0 && exists.get(JAVA_INT, 0) != 0 ? index.get(JAVA_INT, 0) : -1;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /* ---------------------------------------------------------------------------------------------
     * IDWriteFontFile - dwrite.h:822
     * ------------------------------------------------------------------------------------------- */

    /**
     * {@code IDWriteFontFile::Analyze}, slot 5 (dwrite.h:868): the HRESULT straight through, with the
     * four out-parameters copied into the caller's arrays. Four quirks of
     * {@code directwrite.cpp:1958-2001} are reproduced: the {@code self == 0 -> E_FAIL} guard, the
     * "array non-null and length exactly 1" condition on each copy, the copies happening even when the
     * call failed (the C copied uninitialised stack there, this copies the zero-filled scratch, and
     * both callers gate on {@code hr == S_OK} first) and the {@code BOOL} to {@code jboolean}
     * truncation to the low byte.
     */
    static int analyze(long self, boolean[] isSupportedFontType, int[] fontFileType, int[] fontFaceType,
                       int[] numberOfFaces) {
        if (self == 0) {
            return E_FAIL;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment supported = scratch.allocate(JAVA_INT);
            MemorySegment fileType = scratch.allocate(JAVA_INT);
            MemorySegment faceType = scratch.allocate(JAVA_INT);
            MemorySegment faces = scratch.allocate(JAVA_INT);
            MemorySegment obj = com(self);
            int hr = (int) HR_4A.invokeExact(slot(obj, FONT_FILE_ANALYZE), obj, supported, fileType,
                    faceType, faces);
            if (isSupportedFontType != null && isSupportedFontType.length == 1) {
                isSupportedFontType[0] = (supported.get(JAVA_INT, 0) & 0xFF) != 0;
            }
            if (fontFileType != null && fontFileType.length == 1) {
                fontFileType[0] = fileType.get(JAVA_INT, 0);
            }
            if (fontFaceType != null && fontFaceType.length == 1) {
                fontFaceType[0] = faceType.get(JAVA_INT, 0);
            }
            if (numberOfFaces != null && numberOfFaces.length == 1) {
                numberOfFaces[0] = faces.get(JAVA_INT, 0);
            }
            return hr;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }
}
