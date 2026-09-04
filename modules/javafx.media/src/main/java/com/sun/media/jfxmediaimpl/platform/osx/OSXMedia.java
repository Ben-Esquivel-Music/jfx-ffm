/*
 * Copyright (c) 2010, 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.media.jfxmediaimpl.platform.osx;

import com.sun.media.jfxmedia.MediaError;
import com.sun.media.jfxmedia.locator.ConnectionHolder;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.logging.Logger;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import com.sun.media.jfxmediaimpl.NativeMedia;
import com.sun.media.jfxmediaimpl.platform.Platform;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;

final class OSXMedia extends NativeMedia {

    /**
     * Handle to the native AVFoundation media, created by {@link OSXMediaPlayer} and torn down here:
     * {@code NativeMediaPlayer.dispose()} runs {@code playerDispose()} before {@code media.dispose()},
     * and the callback tables may only be freed once the native player is gone (contract section 7).
     */
    private long refNativeMedia;

    /**
     * Owner of the stream callback stubs, used for {@code jar:} and {@code jrt:} locations only; every
     * other scheme is read by AVFoundation itself.
     */
    private Arena streamArena;
    private JfxMediaNative.CallbackTable streamCallbacks;

    /** Actions the player registered for the moment {@code jfxm_media_dispose} has returned. */
    private final List<Runnable> afterDispose = new ArrayList<>();
    private boolean disposed;

    OSXMedia(Locator source) {
        super(source);
    }

    @Override
    public Platform getPlatform() {
        return OSXPlatform.getPlatformInstance();
    }

    /**
     * Creates the native media handle, i.e. the media half of {@code osxCreatePlayer}: the location
     * and, for {@code jar:} and {@code jrt:} locations, a connection holder to read it through. The
     * failures the ObjC code reported by throwing a {@code MediaException} are returned as the error
     * codes recorded in contract section 14.
     * <p>
     * As in {@code GSTMedia}, a failing return closes the connection holder it created: C never
     * received the table, so its {@code close_connection} will never fire (contract section 14.1).
     *
     * @return a {@link MediaError} code
     */
    synchronized int initNativeMedia() {
        Locator locator = getLocator();
        String contentType = locator.getContentType();
        long sizeHint = locator.getContentLength();
        String location = locator.getStringLocation();
        if (location == null) {
            // "OSXMediaPlayer: Unable to create sourceURIString"
            return MediaError.ERROR_MEMORY_ALLOCATION.code();
        }

        // For file/http/https AVFoundation reads the data itself; jar/jrt are read through the
        // Locator, so those are the only schemes that get a stream callback table.
        ConnectionHolder holder = null;
        int rc = MediaError.ERROR_MEMORY_ALLOCATION.code();
        try {
            String scheme = locator.getURI().getScheme();
            if ("jar".equalsIgnoreCase(scheme) || "jrt".equalsIgnoreCase(scheme)) {
                if (contentType == null) {
                    // "OSXMediaPlayer: memory allocation failed"
                    return rc;
                }
                holder = locator.createConnectionHolder();
                if (holder == null) {
                    return rc;
                }
                streamArena = Arena.ofShared();
                streamCallbacks = JfxMediaNative.installStreamCallbacks(streamArena, holder);
            }

            long[] nativeMediaHandle = new long[1];
            rc = JfxMediaNative.mediaCreate(JfxMediaNative.JFXM_BACKEND_AVF, contentType, location,
                    sizeHint, streamCallbacks, null, nativeMediaHandle);
            if (rc == MediaError.ERROR_NONE.code()) {
                refNativeMedia = nativeMediaHandle[0];
            }
            return rc;
        } catch (IOException | RuntimeException e) {
            Logger.logMsg(Logger.ERROR, "OSXMedia: cannot create the connection holder: " + e);
            return rc;
        } finally {
            if (rc != MediaError.ERROR_NONE.code()) {
                // The native side retained nothing of the table, so close_connection will never
                // fire: the holder is closed here rather than left open until it is collected.
                releaseCallbacks();
                closeQuietly(holder);
            }
        }
    }

    /**
     * Closes a connection holder the native side never took over. A failure to close must not mask the
     * error the caller is about to report.
     */
    private static void closeQuietly(ConnectionHolder holder) {
        if (holder == null) {
            return;
        }
        try {
            holder.closeConnection();
        } catch (RuntimeException e) {
            Logger.logMsg(Logger.WARNING, "OSXMedia: closing the connection holder failed: " + e);
        }
    }

    long getNativeMediaRef() {
        return refNativeMedia;
    }

    /**
     * Registers an action to run once {@code jfxm_media_dispose} has returned, i.e. once no callback
     * of this media can fire again.
     *
     * @param action the action, never {@code null}
     */
    synchronized void runAfterDispose(Runnable action) {
        if (disposed) {
            action.run();
        } else {
            afterDispose.add(action);
        }
    }

    @Override
    public synchronized void dispose() {
        if (0 != refNativeMedia) {
            JfxMediaNative.mediaDispose(refNativeMedia);
            refNativeMedia = 0L;
        }

        // Only now is it safe to free the upcall stubs and the registry entries.
        disposed = true;
        finishDispose();
    }

    /**
     * Frees the stubs and runs the registered actions, whatever any single step does.
     * {@code Arena.close()} on the shared arena throws {@link IllegalStateException} while a native
     * thread has not unwound from one of its stubs, and again when a second dispose races this one.
     * Letting that escape would abort the rest of the teardown and leave
     * {@code NativeMediaPlayer.dispose()} before it sets {@code isDisposed}, i.e. a player that is
     * neither alive nor disposed, with its listeners un-cleared and its registry entry leaked. The JNI
     * implementation had no way to fail here at all, so throwing is the drift: the failure is logged.
     */
    private void finishDispose() {
        RuntimeException failure = null;
        try {
            releaseCallbacks();
        } catch (RuntimeException e) {
            failure = e;
        }
        for (Runnable action : afterDispose) {
            try {
                action.run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        afterDispose.clear();
        if (failure != null) {
            StringBuilder message = new StringBuilder("OSXMedia: dispose did not complete: ").append(failure);
            for (Throwable suppressed : failure.getSuppressed()) {
                message.append("; ").append(suppressed);
            }
            Logger.logMsg(Logger.ERROR, message.toString());
        }
    }

    private void releaseCallbacks() {
        if (streamCallbacks != null) {
            streamCallbacks.unregister();
            streamCallbacks = null;
        }
        if (streamArena != null) {
            streamArena.close();
            streamArena = null;
        }
    }
}
