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

import com.sun.javafx.logging.PlatformLogger;
import com.sun.javafx.util.Logging;
import java.lang.foreign.FunctionDescriptor;
import static com.sun.javafx.font.directwrite.DWNative.IID_IUNKNOWN;
import static com.sun.javafx.font.directwrite.DWNative.guidBytes;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * What the two inbound COM objects, {@link DWAnalysisSinkObject} and {@link DWTextRendererObject}, share: the
 * {@code IUnknown} upcall shapes, the {@code IUnknown} IID they answer, the failure log of a callback and the
 * uuid text of the {@code QueryInterface} log. {@link DWGeometrySinkObject}, the third COM object this package
 * implements in Java, uses only the {@code IUnknown} IID.
 */
final class DWComObjectSupport {

    /**
     * {@code HRESULT QueryInterface(this, REFIID, void** ppvObject)}, slot 0 of every COM interface.
     * The same shape as the geometry sink's; {@link FunctionDescriptor} has value equality, so
     * {@link DWNative#virtual} and the upcall linker both see one descriptor.
     */
    static final FunctionDescriptor COM_QUERY_INTERFACE_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    /** {@code ULONG AddRef(this)} and {@code ULONG Release(this)}, slots 1 and 2. */
    static final FunctionDescriptor COM_REF_COUNT_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    static final byte[] IID_IUNKNOWN_BYTES = guidBytes(IID_IUNKNOWN);

    private DWComObjectSupport() {
    }

    /** A callback failed. The C could not fail here, so this is logged and swallowed, never thrown. */
    static void logCallbackFailure(String what, Throwable failure) {
        PlatformLogger logger = Logging.getJavaFXLogger();
        if (logger.isLoggable(PlatformLogger.Level.SEVERE)) {
            logger.severe(what + " failed", failure);
        }
    }

    /** The inverse of {@link DWNative#guid}: 16 {@code guiddef.h} bytes back to uuid text, for the QI log. */
    static String uuidText(byte[] raw) {
        if (raw == null || raw.length != 16) {
            return "?";
        }
        StringBuilder text = new StringBuilder(36);
        text.append(hex(raw[3])).append(hex(raw[2])).append(hex(raw[1])).append(hex(raw[0]));
        text.append('-').append(hex(raw[5])).append(hex(raw[4]));
        text.append('-').append(hex(raw[7])).append(hex(raw[6])).append('-');
        for (int i = 8; i < 16; i++) {
            if (i == 10) {
                text.append('-');
            }
            text.append(hex(raw[i]));
        }
        return text.toString();
    }

    private static String hex(byte value) {
        int unsigned = value & 0xFF;
        return "" + Character.forDigit(unsigned >> 4, 16) + Character.forDigit(unsigned & 0xF, 16);
    }
}
