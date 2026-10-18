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

package com.sun.pisces;

/**
 * The transform arithmetic of the Pisces compositor: {@code native-prism-sw/PiscesTransform.c} of commit
 * {@code 42a5072596}, and the float-to-int conversions of the x86-64 build of that C.
 * <p>
 * A C {@code Transform6} is an {@code int[6]} here, in the {@code PswTransform6} order {@link Transform6#fill}
 * writes: m00, m01, m10, m11, m02, m12.
 * <p>
 * A C cast from {@code float} or {@code double} to a 32-bit {@code int} compiles to {@code cvttss2si} /
 * {@code cvttsd2si} with a 32-bit destination on x86-64 (MSVC and gcc), which truncates toward zero and gives
 * {@code 0x80000000} for NaN and for every value outside the {@code int} range. A Java cast saturates instead
 * (NaN to 0, large values to {@link Integer#MAX_VALUE}), so every such C cast goes through {@link #f2i} or
 * {@link #d2i}.
 */
final class PiscesTransform {

    static final int M00 = 0;
    static final int M01 = 1;
    static final int M10 = 2;
    static final int M11 = 3;
    static final int M02 = 4;
    static final int M12 = 5;

    /** Length of the {@code int[]} that stands for a C {@code Transform6}. */
    static final int INTS = 6;

    private PiscesTransform() {
    }

    /** The C {@code (jint) f} of x86-64: truncation, and {@code 0x80000000} for NaN or out of range. */
    static int f2i(float f) {
        return (f >= -0x1p31f && f < 0x1p31f) ? (int) f : Integer.MIN_VALUE;
    }

    /** The C {@code (jint) d} of x86-64: truncation, and {@code 0x80000000} for NaN or out of range. */
    static int d2i(double d) {
        return (d > -0x1p31 - 1.0 && d < 0x1p31) ? (int) d : Integer.MIN_VALUE;
    }

    /**
     * {@code t}'s six entries copied into a new {@code int[6]} ({@code pisces_transform_assign}). A null {@code t}
     * fails in {@code t.fill}, as it did in the {@code transform(Transform6 t)} of the FFM path
     * ({@code PiscesRenderer.java:102-103} at {@code 42a5072596}).
     */
    static int[] of(Transform6 t) {
        int[] entries = new int[INTS];
        t.fill(entries);
        return entries;
    }

    /**
     * {@code pisces_transform_invert}: replaces {@code transform} by its inverse, computed in float in the C's
     * order and truncated back to S15.16. A singular transform divides by a zero determinant, and the
     * resulting infinities and NaNs become {@code 0x80000000}, as in the C.
     */
    static void invert(int[] transform) {
        float fm00 = transform[M00] / 65536.0f;
        float fm01 = transform[M01] / 65536.0f;
        float fm02 = transform[M02] / 65536.0f;
        float fm10 = transform[M10] / 65536.0f;
        float fm11 = transform[M11] / 65536.0f;
        float fm12 = transform[M12] / 65536.0f;
        float fdet = fm00 * fm11 - fm01 * fm10;

        float fa00 = fm11 / fdet;
        float fa01 = -fm01 / fdet;
        float fa10 = -fm10 / fdet;
        float fa11 = fm00 / fdet;
        float fa02 = (fm01 * fm12 - fm02 * fm11) / fdet;
        float fa12 = (fm02 * fm10 - fm00 * fm12) / fdet;

        transform[M00] = f2i(fa00 * 65536.0f);
        transform[M01] = f2i(fa01 * 65536.0f);
        transform[M10] = f2i(fa10 * 65536.0f);
        transform[M11] = f2i(fa11 * 65536.0f);
        transform[M02] = f2i(fa02 * 65536.0f);
        transform[M12] = f2i(fa12 * 65536.0f);
    }
}
