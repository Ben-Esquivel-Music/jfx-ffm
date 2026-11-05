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

package com.sun.glass.ui.mac;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.function.IntUnaryOperator;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_CLASS_NAME;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_DATA_DIRECTORY;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_HIDE;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_HIDE_OTHER_APPLICATIONS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_INIT;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_IS_TRIGGER_REACTIVATION;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_OPEN_URI;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_UNHIDE_ALL_APPLICATIONS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.free;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.newStringUtf;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.utf16;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** The natives of {@code MacApplication} that are neither the main loop, the screens, the preferences nor keys. */
final class MacApplicationCalls {

    /** {@code gmac_app_init}'s answer when this macOS is older than the minimum the library was built for. */
    static final int GMAC_ERR_UNSUPPORTED_OS = -2;

    /** {@code gmac_app_open_uri}'s signature, so that a test can stand where the library would. */
    @FunctionalInterface
    interface UriOpener {

        int open(MemorySegment uri, int length, MemorySegment outCompleted) throws Throwable;
    }

    private MacApplicationCalls() {
    }

    /** {@code MacApplication}'s class initialization, which the JNI's {@code _initIDs} was. */
    static void init(boolean disableSyncRendering) {
        init(disableSyncRendering, MacApplicationCalls::appInit);
    }

    /**
     * An unsupported macOS fails the class initialization that calls this with the exception the JNI's
     * {@code ThrowNew} left pending there. {@code GMAC_OK} is the only other answer.
     */
    static void init(boolean disableSyncRendering, IntUnaryOperator appInit) {
        if (appInit.applyAsInt(disableSyncRendering ? 1 : 0) == GMAC_ERR_UNSUPPORTED_OS) {
            throw new RuntimeException("Unsupported macOS version");
        }
    }

    static boolean isTriggerReactivation() {
        try {
            return (int) GMAC_APP_IS_TRIGGER_REACTIVATION.invokeExact() != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void hide() {
        try {
            GMAC_APP_HIDE.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void hideOtherApplications() {
        try {
            GMAC_APP_HIDE_OTHER_APPLICATIONS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void unhideAllApplications() {
        try {
            GMAC_APP_UNHIDE_ALL_APPLICATIONS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** The JNI reported the detached assertion at its closing {@code GLASS_CHECK_EXCEPTION}. */
    static String dataDirectory() {
        boolean detached = mainThreadDetached();
        String directory = stringOut(GMAC_APP_DATA_DIRECTORY);
        if (detached) {
            reportException(mainThreadDetachedError());
        }
        return directory;
    }

    /** Creates {@code NSApp} when there is none. */
    static String applicationClassName() {
        return stringOut(GMAC_APP_CLASS_NAME);
    }

    static int openUri(String uri) {
        return openUri(uri, (chars, length, outCompleted) ->
                (int) GMAC_APP_OPEN_URI.invokeExact(chars, length, outCompleted));
    }

    /**
     * The JNI reported the detached assertion at its closing {@code GLASS_CHECK_EXCEPTION}, and returned with it
     * pending, so thrown, where no default browser is set.
     */
    static int openUri(String uri, UriOpener opener) {
        boolean detached = mainThreadDetached();
        int status;
        int completed;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outCompleted = arena.allocate(JAVA_INT);
            int length = uri != null ? uri.length() : 0;
            status = opener.open(utf16(arena, uri), length, outCompleted);
            completed = outCompleted.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (detached) {
            RuntimeException detachedError = mainThreadDetachedError();
            if (completed == 0) {
                throw detachedError;
            }
            reportException(detachedError);
        }
        return status;
    }

    private static int appInit(int disableSyncRendering) {
        try {
            return (int) GMAC_APP_INIT.invokeExact(disableSyncRendering);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static String stringOut(MethodHandle query) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outUtf8 = arena.allocate(ADDRESS);
            MemorySegment outLength = arena.allocate(JAVA_INT);
            int status;
            try {
                status = (int) query.invokeExact(outUtf8, outLength);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            if (status != GMAC_OK) {
                return null;
            }
            MemorySegment utf8 = outUtf8.get(ADDRESS, 0);
            try {
                return newStringUtf(utf8, outLength.get(JAVA_INT, 0));
            } finally {
                free(utf8);
            }
        }
    }
}
