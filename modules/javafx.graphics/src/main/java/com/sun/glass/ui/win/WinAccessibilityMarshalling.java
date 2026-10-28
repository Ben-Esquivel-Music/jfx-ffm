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

package com.sun.glass.ui.win;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_OK;
import static com.sun.glass.ui.win.WinCallSupport.utf16;
import static com.sun.glass.ui.win.WinClipboardCalls.gwinAlloc;
import static com.sun.glass.ui.win.WinGlassNative.bounded;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The marshalling of the {@link WinGlassNative} UI Automation tables: the {@code GwinVariant} layout, the
 * out-parameters the 89 slots of {@link WinAccessibleCallbacks} and {@link WinTextRangeCallbacks} write, and the
 * {@code GwinVariant} arguments of {@link WinAccessibilityCalls#raiseAutomationPropertyChangedEvent}. Its
 * initializer computes one layout and its offsets and reaches no other class, so a first upcall on a UI
 * Automation thread can initialize it without waiting on anything.
 */
final class WinAccessibilityMarshalling {

    /**
     * {@code GwinVariant} ({@code glass_win_api.h}): the flat form of {@link WinVariant}, the nine
     * fields {@code GlassAccessible::copyVariant} read through cached {@code jfieldID}s plus the two
     * lengths a flat ABI needs. Natural x64 alignment, so it has three padding holes and the size the
     * C compiler gives it is 72 bytes - {@code gwin_sizeof_variant} and {@code gwin_test_variant_offsets}
     * are what pin that, never this declaration.
     * <p>
     * {@code boolVal} crosses as an {@code int32_t} 0 / 1, because the C field {@code bool_val} is an
     * {@code int32_t} and the layout matches it; the C turns it into {@code VARIANT_FALSE} / {@code VARIANT_TRUE}. A
     * {@code NULL} {@code bstr_val} / {@code p_dbl_val} is Java's {@code null} and is distinct from a
     * zero-length block: {@code copyString(NULL)} and {@code copyList(NULL)} answered {@code E_FAIL}
     * where an empty {@code String} or {@code double[]} answered {@code S_OK}.
     */
    static final StructLayout GWIN_VARIANT_LAYOUT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("vt"),
            JAVA_SHORT.withName("i_val"),
            JAVA_INT.withName("l_val"),
            JAVA_FLOAT.withName("flt_val"),
            MemoryLayout.paddingLayout(4),
            JAVA_DOUBLE.withName("dbl_val"),
            JAVA_INT.withName("bool_val"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("punk_val"),
            ADDRESS.withName("bstr_val"),
            JAVA_INT.withName("bstr_len"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("p_dbl_val"),
            JAVA_INT.withName("p_dbl_count"),
            MemoryLayout.paddingLayout(4));

    private static final long VARIANT_VT_OFFSET = variantOffset("vt");
    private static final long VARIANT_I_VAL_OFFSET = variantOffset("i_val");
    private static final long VARIANT_L_VAL_OFFSET = variantOffset("l_val");
    private static final long VARIANT_FLT_VAL_OFFSET = variantOffset("flt_val");
    private static final long VARIANT_DBL_VAL_OFFSET = variantOffset("dbl_val");
    private static final long VARIANT_BOOL_VAL_OFFSET = variantOffset("bool_val");
    private static final long VARIANT_PUNK_VAL_OFFSET = variantOffset("punk_val");
    private static final long VARIANT_BSTR_VAL_OFFSET = variantOffset("bstr_val");
    private static final long VARIANT_BSTR_LEN_OFFSET = variantOffset("bstr_len");
    private static final long VARIANT_P_DBL_VAL_OFFSET = variantOffset("p_dbl_val");
    private static final long VARIANT_P_DBL_COUNT_OFFSET = variantOffset("p_dbl_count");

    private static long variantOffset(String field) {
        return GWIN_VARIANT_LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    private WinAccessibilityMarshalling() {
    }

    /* -- the out-parameters the 89 slots write ------------------------------------------------------ */

    static int outLong(MemorySegment out, long value) {
        bounded(out, JAVA_LONG.byteSize()).set(JAVA_LONG, 0, value);
        return GWIN_OK;
    }

    static int outInt(MemorySegment out, int value) {
        bounded(out, JAVA_INT.byteSize()).set(JAVA_INT, 0, value);
        return GWIN_OK;
    }

    /** One {@code int32_t} out-parameter carrying a boolean, as 0 or 1; the C writes the VARIANT form. */
    static int outBoolean(MemorySegment out, boolean value) {
        return outInt(out, value ? 1 : 0);
    }

    static int outDouble(MemorySegment out, double value) {
        bounded(out, JAVA_DOUBLE.byteSize()).set(JAVA_DOUBLE, 0, value);
        return GWIN_OK;
    }

    /**
     * {@code get_bounding_rectangle}: four floats and the flag that says whether they were written.
     * {@code null} - which is what {@code get_BoundingRectangle} answers for a node with no bounds - is
     * {@code *out_written == 0} and the provider then returns {@code S_OK} with the {@code UiaRect}
     * untouched, exactly as the JNI did for a null {@code jfloatArray}.
     * <p>
     * An array shorter than four raises {@code ArrayIndexOutOfBoundsException} here, which is reported
     * and becomes {@code E_FAIL}; the JNI read four floats out of whatever array arrived, through
     * {@code GetPrimitiveArrayCritical} and with no length check. {@code WinAccessible} produces only
     * {@code null} or a four-element array, so no caller can tell the two apart - but a garbage read is
     * not worth reproducing.
     */
    static int outRectangle(MemorySegment out4, MemorySegment outWritten, float[] bounds) {
        int written = 0;
        if (bounds != null) {
            MemorySegment.copy(bounds, 0, bounded(out4, 4L * Float.BYTES), JAVA_FLOAT, 0, 4);
            written = 1;
        }
        return outInt(outWritten, written);
    }

    /**
     * A {@code gwin_alloc} block of {@code byteSize} bytes, bounded so that it can be written. Never
     * {@code NULL}: a heap that cannot give the block is an {@code OutOfMemoryError}, which the stub
     * catches and reports as {@link WinCallSupport#GWIN_ERR_UPCALL}.
     */
    private static MemorySegment libraryBlock(long byteSize) {
        MemorySegment block = gwinAlloc(byteSize);
        if (block.equals(MemorySegment.NULL)) {
            throw new OutOfMemoryError("gwin_alloc(" + byteSize + ") returned NULL");
        }
        return bounded(block, byteSize);
    }

    /**
     * An {@code int64_t*} block and its count, {@code gwin_alloc}-ed and owned by the library from the
     * moment this returns. A {@code null} array is {@code NULL} with count 0, which the C turns into
     * {@code E_FAIL} - what {@code copyList(NULL)} answered; an empty array is a real block with count 0,
     * which is {@code S_OK} and an empty {@code SAFEARRAY}. The difference reaches the UIA client.
     */
    static int outLongBlock(MemorySegment out, MemorySegment outCount, long[] values) {
        MemorySegment block = MemorySegment.NULL;
        int count = 0;
        if (values != null) {
            block = libraryBlock(Math.max(values.length, 1) * (long) Long.BYTES);
            MemorySegment.copy(values, 0, block, JAVA_LONG, 0, values.length);
            count = values.length;
        }
        bounded(out, ADDRESS.byteSize()).set(ADDRESS, 0, block);
        return outInt(outCount, count);
    }

    /** {@link #outLongBlock}'s rules for an {@code int32_t*} block - {@code get_runtime_id}. */
    static int outIntBlock(MemorySegment out, MemorySegment outCount, int[] values) {
        MemorySegment block = MemorySegment.NULL;
        int count = 0;
        if (values != null) {
            block = libraryBlock(Math.max(values.length, 1) * (long) Integer.BYTES);
            MemorySegment.copy(values, 0, block, JAVA_INT, 0, values.length);
            count = values.length;
        }
        bounded(out, ADDRESS.byteSize()).set(ADDRESS, 0, block);
        return outInt(outCount, count);
    }

    /** {@link #outLongBlock}'s rules for a {@code double*} block - {@code get_bounding_rectangles}. */
    static int outDoubleBlock(MemorySegment out, MemorySegment outCount, double[] values) {
        MemorySegment block = MemorySegment.NULL;
        int count = 0;
        if (values != null) {
            block = libraryBlock(Math.max(values.length, 1) * (long) Double.BYTES);
            MemorySegment.copy(values, 0, block, JAVA_DOUBLE, 0, values.length);
            count = values.length;
        }
        bounded(out, ADDRESS.byteSize()).set(ADDRESS, 0, block);
        return outInt(outCount, count);
    }

    /**
     * A {@code uint16_t*} block of UTF-16 code units and its length, {@code gwin_alloc}-ed and owned by
     * the library. Not NUL-terminated - the C wraps it in {@code SysAllocStringLen}. {@code null} is
     * {@code NULL} with length 0, which is the {@code E_FAIL} of {@code copyString(NULL)}; an empty
     * {@code String} is a real block with length 0, which is an empty {@code BSTR} and {@code S_OK}.
     * Code units move, never characters: a lone surrogate survives the crossing.
     */
    static int outString(MemorySegment out, MemorySegment outLength, String text) {
        MemorySegment block = MemorySegment.NULL;
        int length = 0;
        if (text != null) {
            char[] chars = text.toCharArray();
            block = libraryBlock(Math.max(chars.length, 1) * (long) Character.BYTES);
            MemorySegment.copy(chars, 0, block, JAVA_CHAR, 0, chars.length);
            length = chars.length;
        }
        bounded(out, ADDRESS.byteSize()).set(ADDRESS, 0, block);
        return outInt(outLength, length);
    }

    /**
     * {@code count} borrowed UTF-16 code units as a {@code String} - the {@code NewString(text, len)} of
     * {@code SetValue(LPCWSTR)} and {@code FindText}. A {@code NULL} pointer is the empty string; the
     * library only dials those two slots with a non-NULL {@code BSTR}.
     */
    static String utf16String(MemorySegment pointer, int count) {
        return new String(utf16(pointer, count));
    }

    /* -- GwinVariant -------------------------------------------------------------------------------- */

    /**
     * Writes {@code variant} into the library's {@code GwinVariant} out-parameter, with both blocks
     * {@code gwin_alloc}-ed and owned by the library from the moment this returns
     * ({@code GwinClipboardCallbacks.fos_serialize}'s rule). A {@code null} variant leaves
     * {@code vt = VT_EMPTY} and no value, which the C turns into {@code E_FAIL} with {@code VT_EMPTY} -
     * what {@code copyVariant(NULL)} did. Nothing is written until both blocks exist, so a failure
     * leaves the caller's struct as the library pre-zeroed it.
     */
    static int outVariant(MemorySegment out, WinVariant variant) {
        MemorySegment target = bounded(out, GWIN_VARIANT_LAYOUT.byteSize());
        writeVariant(target, variant, WinAccessibilityMarshalling::libraryBlock);
        return GWIN_OK;
    }

    /**
     * {@code allocator} owns the direction: {@link #libraryBlock} when the library takes the blocks over, an
     * arena when the caller keeps them for the length of a downcall.
     */
    private static void writeVariant(MemorySegment target, WinVariant variant, BlockAllocator allocator) {
        MemorySegment bstr = MemorySegment.NULL;
        int bstrLength = 0;
        MemorySegment doubles = MemorySegment.NULL;
        int doubleCount = 0;
        if (variant != null) {
            if (variant.bstrVal != null) {
                char[] chars = variant.bstrVal.toCharArray();
                bstr = allocator.allocate(Math.max(chars.length, 1) * (long) Character.BYTES);
                MemorySegment.copy(chars, 0, bstr, JAVA_CHAR, 0, chars.length);
                bstrLength = chars.length;
            }
            if (variant.pDblVal != null) {
                double[] values = variant.pDblVal;
                doubles = allocator.allocate(Math.max(values.length, 1) * (long) Double.BYTES);
                MemorySegment.copy(values, 0, doubles, JAVA_DOUBLE, 0, values.length);
                doubleCount = values.length;
            }
        }
        target.fill((byte) 0);
        if (variant != null) {
            target.set(JAVA_SHORT, VARIANT_VT_OFFSET, variant.vt);
            target.set(JAVA_SHORT, VARIANT_I_VAL_OFFSET, variant.iVal);
            target.set(JAVA_INT, VARIANT_L_VAL_OFFSET, variant.lVal);
            target.set(JAVA_FLOAT, VARIANT_FLT_VAL_OFFSET, variant.fltVal);
            target.set(JAVA_DOUBLE, VARIANT_DBL_VAL_OFFSET, variant.dblVal);
            target.set(JAVA_INT, VARIANT_BOOL_VAL_OFFSET, variant.boolVal ? 1 : 0);
            target.set(JAVA_LONG, VARIANT_PUNK_VAL_OFFSET, variant.punkVal);
        }
        target.set(ADDRESS, VARIANT_BSTR_VAL_OFFSET, bstr);
        target.set(JAVA_INT, VARIANT_BSTR_LEN_OFFSET, bstrLength);
        target.set(ADDRESS, VARIANT_P_DBL_VAL_OFFSET, doubles);
        target.set(JAVA_INT, VARIANT_P_DBL_COUNT_OFFSET, doubleCount);
    }

    /**
     * Reads a {@code GwinVariant} the library passed in. The one such parameter is
     * {@code find_attribute}'s, and the library always passes {@code NULL} there, because
     * {@code GlassTextRangeProvider::FindAttribute} never converted the {@code VARIANT} UI Automation
     * gave it ("//TODO VAL TO JVAL") and a migration carries that verbatim - so this normally answers
     * {@code null}, and decodes only if the library ever starts passing one.
     */
    static WinVariant readVariant(MemorySegment value) {
        if (value.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment source = bounded(value, GWIN_VARIANT_LAYOUT.byteSize());
        WinVariant variant = new WinVariant();
        variant.vt = source.get(JAVA_SHORT, VARIANT_VT_OFFSET);
        variant.iVal = source.get(JAVA_SHORT, VARIANT_I_VAL_OFFSET);
        variant.lVal = source.get(JAVA_INT, VARIANT_L_VAL_OFFSET);
        variant.fltVal = source.get(JAVA_FLOAT, VARIANT_FLT_VAL_OFFSET);
        variant.dblVal = source.get(JAVA_DOUBLE, VARIANT_DBL_VAL_OFFSET);
        variant.boolVal = source.get(JAVA_INT, VARIANT_BOOL_VAL_OFFSET) != 0;
        variant.punkVal = source.get(JAVA_LONG, VARIANT_PUNK_VAL_OFFSET);
        MemorySegment bstr = source.get(ADDRESS, VARIANT_BSTR_VAL_OFFSET);
        if (!bstr.equals(MemorySegment.NULL)) {
            variant.bstrVal = utf16String(bstr, source.get(JAVA_INT, VARIANT_BSTR_LEN_OFFSET));
        }
        MemorySegment doubles = source.get(ADDRESS, VARIANT_P_DBL_VAL_OFFSET);
        if (!doubles.equals(MemorySegment.NULL)) {
            int count = source.get(JAVA_INT, VARIANT_P_DBL_COUNT_OFFSET);
            variant.pDblVal = bounded(doubles, count * JAVA_DOUBLE.byteSize()).toArray(JAVA_DOUBLE);
        }
        return variant;
    }

    /**
     * One {@code const GwinVariant*} argument of {@link WinAccessibilityCalls#raiseAutomationPropertyChangedEvent}:
     * {@code NULL} for a {@code null} {@code WinVariant}, because that is the {@code jobject} the JNI
     * handed {@code copyVariant}, which answered {@code E_FAIL} and short-circuited before the OS call.
     */
    static MemorySegment variantArgument(Arena arena, WinVariant variant, BlockAllocator allocator) {
        if (variant == null) {
            return MemorySegment.NULL;
        }
        MemorySegment target = arena.allocate(GWIN_VARIANT_LAYOUT);
        writeVariant(target, variant, allocator);
        return target;
    }

    /** Where a {@code GwinVariant}'s two blocks come from; see {@link #writeVariant}. */
    interface BlockAllocator {

        /** A block of {@code byteSize} bytes, aligned for a {@code double} and writable. */
        MemorySegment allocate(long byteSize);
    }
}
