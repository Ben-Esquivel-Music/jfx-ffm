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

package com.sun.media.jfxmedia.locator;

import java.io.IOException;

/**
 * Package-private {@link ConnectionHolder} members, reachable by the {@code JfxmStreamCallbacks} upcall
 * targets in {@code com.sun.media.jfxmediaimpl.JfxMediaNative}.
 * <p>
 * The JNI implementation of those callbacks ({@code JavaInputStreamCallbacks.cpp}) invoked
 * {@code needBuffer}, {@code isSeekable}, {@code isRandomAccess}, {@code readBlock} and {@code property}
 * through {@code CallBooleanMethod}/{@code CallIntMethod}, which does not perform an access check. The
 * FFM upcall targets are ordinary Java code and do, so the five members are forwarded here instead of
 * being widened on {@link ConnectionHolder} itself: the holder's own API is unchanged and the extra
 * reach stays inside this package.
 * <p>
 * Every method delegates with no added behaviour; the callers keep the return and exception semantics
 * of the C ABI (contract section 9).
 */
public final class ConnectionHolderBridge {

    /**
     * {@code HLSConnectionHolder.HLS_PROP_HAS_AUDIO_EXT_STREAM}: the property a media asks the main
     * connection holder about before deciding whether a second, audio-only stream has to be opened.
     * {@code GstMedia.cpp} carried its own copy of the value; this one is the constant itself.
     */
    public static final int HLS_PROP_HAS_AUDIO_EXT_STREAM = HLSConnectionHolder.HLS_PROP_HAS_AUDIO_EXT_STREAM;

    private ConnectionHolderBridge() {
    }

    /**
     * @param holder the connection holder, never {@code null}
     * @return {@link ConnectionHolder#needBuffer()}
     */
    public static boolean needBuffer(ConnectionHolder holder) {
        return holder.needBuffer();
    }

    /**
     * @param holder the connection holder, never {@code null}
     * @return {@link ConnectionHolder#isSeekable()}
     */
    public static boolean isSeekable(ConnectionHolder holder) {
        return holder.isSeekable();
    }

    /**
     * @param holder the connection holder, never {@code null}
     * @return {@link ConnectionHolder#isRandomAccess()}
     */
    public static boolean isRandomAccess(ConnectionHolder holder) {
        return holder.isRandomAccess();
    }

    /**
     * @param holder the connection holder, never {@code null}
     * @param position the absolute position to read from
     * @param size the number of bytes to read
     * @return {@link ConnectionHolder#readBlock(long, int)}
     * @throws IOException if the underlying read fails
     */
    public static int readBlock(ConnectionHolder holder, long position, int size) throws IOException {
        return holder.readBlock(position, size);
    }

    /**
     * @param holder the connection holder, never {@code null}
     * @param prop the property id, one of {@code HLSConnectionHolder.HLS_PROP_*}
     * @param value the value, whose meaning depends on the property id
     * @return {@link ConnectionHolder#property(int, int)}
     */
    public static int property(ConnectionHolder holder, int prop, int value) {
        return holder.property(prop, value);
    }
}
