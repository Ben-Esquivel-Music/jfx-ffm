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
 * The {@code PSW_*} status codes of {@code prism_sw_api.h} at commit {@code 42a5072596} and their exceptions. The
 * functions of {@link PiscesSurfaceState} and {@link PiscesRendererState} return the code their C counterpart
 * returned under the same conditions, and {@link #check} turns it into exactly what the FFM facade of that C threw
 * for that code at that call site, with the same texts ({@code PiscesNative.java:239-251} at {@code 42a5072596}).
 * {@code PSW_ERR_STATE}, a null native handle, has no counterpart here.
 */
final class PiscesStatus {

    static final int OK = 0;
    static final int ERR_OOM = 1;
    static final int ERR_ARG = 2;

    /** The {@code PSW_ERR_OOM} text, the JNI-era {@code mem_Error_Flag} text. */
    static final String OOM_MESSAGE = "Allocation of internal renderer buffer failed.";

    /** The generic {@code PSW_ERR_ARG} text of every renderer call. */
    static final String ARG_MESSAGE = "Illegal arguments";

    /** The {@code PSW_ERR_ARG} text of {@code getRGB}. */
    static final String GET_RGB_MESSAGE = "Out of range access of buffer";

    /** The {@code PSW_ERR_ARG} text of {@code setRGB}; the JNI text differed from the getRGB one in case only. */
    static final String SET_RGB_MESSAGE = "out of range access of buffer";

    private PiscesStatus() {
    }

    /**
     * Throws what {@code status} stands for: {@link OutOfMemoryError} for {@link #ERR_OOM},
     * {@link IllegalArgumentException} with {@code argumentMessage} for {@link #ERR_ARG}; returns for {@link #OK}.
     */
    static void check(int status, String argumentMessage) {
        switch (status) {
            case OK:
                return;
            case ERR_OOM:
                throw new OutOfMemoryError(OOM_MESSAGE);
            case ERR_ARG:
                throw new IllegalArgumentException(argumentMessage);
            default:
                throw new IllegalStateException("unknown Pisces status " + status);
        }
    }
}
