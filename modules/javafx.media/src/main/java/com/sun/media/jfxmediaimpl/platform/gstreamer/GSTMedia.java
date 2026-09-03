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

package com.sun.media.jfxmediaimpl.platform.gstreamer;

import com.sun.media.jfxmedia.Media;
import com.sun.media.jfxmedia.MediaError;
import com.sun.media.jfxmedia.locator.ConnectionHolder;
import com.sun.media.jfxmedia.locator.ConnectionHolderBridge;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.logging.Logger;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import com.sun.media.jfxmediaimpl.MediaUtils;
import com.sun.media.jfxmediaimpl.NativeMedia;
import com.sun.media.jfxmediaimpl.platform.Platform;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;

/**
 * GStreamer implementation of Media
 */
final class GSTMedia extends NativeMedia {
    /**
     * Synchronization mutex for markers.
     */
    private final Object markerMutex = new Object();

    /**
     * Handle to the native media player.
     */
    protected long refNativeMedia;

    /**
     * Owner of the upcall stubs of the stream callback tables. The native pipeline calls them from
     * its streaming threads until {@code jfxm_media_dispose} has returned, so the arena is closed
     * and the registry entries are removed only after that call (contract section 4).
     */
    private Arena streamArena;
    private JfxMediaNative.CallbackTable streamCallbacks;
    private JfxMediaNative.CallbackTable audioStreamCallbacks;

    /**
     * Actions the player peer registered for the moment {@code jfxm_media_dispose} has returned:
     * closing its own callback arena and releasing the spectrum's band memory.
     */
    private final List<Runnable> afterDispose = new ArrayList<>();
    private boolean disposed;

    GSTMedia(Locator locator) {
        super(locator);

        init();
    }

    @Override
    public Platform getPlatform() {
        return GSTPlatform.getPlatformInstance();
    }

    private void init() {
        //***** Initialize the native media components
        long[] nativeMediaHandle = new long[1];
        MediaError ret = MediaError.getFromCode(createNativeMedia(nativeMediaHandle));
        if (ret != MediaError.ERROR_NONE && ret != MediaError.ERROR_PLATFORM_UNSUPPORTED) {
            MediaUtils.nativeError(this, ret);
        }
        this.refNativeMedia = nativeMediaHandle[0];
    }

    /**
     * Resolves the {@link Locator} and creates the native media, in the order {@code GstMedia.cpp}
     * resolved it in from C (contract section 7): the string location, the connection holder, and,
     * when the holder reports an external audio stream, the audio connection holder. Every failure
     * the JNI code reported as {@code ERROR_MEMORY_ALLOCATION} - a null string, a null holder or an
     * exception thrown by the locator - is reported the same way here.
     * <p>
     * One deliberate departure (contract section 14.1, a change in the safe direction): every failing
     * return closes the connection holders it created. On the success path C closes them for us when
     * the pipeline teardown fires {@code close_connection}, but a failing {@code jfxm_media_create}
     * never received the tables, so nothing else ever would - the JNI code left the connection open
     * until the holder was collected.
     *
     * @param nativeMediaHandle receives the media handle in element 0
     * @return a {@link MediaError} code
     */
    private int createNativeMedia(long[] nativeMediaHandle) {
        Locator locator = getLocator();
        String contentType = locator.getContentType();
        long sizeHint = locator.getContentLength();
        String location = locator.getStringLocation();
        if (contentType == null || location == null) {
            return MediaError.ERROR_MEMORY_ALLOCATION.code();
        }

        ConnectionHolder holder = null;
        ConnectionHolder audioHolder = null;
        int rc = MediaError.ERROR_MEMORY_ALLOCATION.code();
        try {
            holder = locator.createConnectionHolder();
            if (holder == null) {
                return rc;
            }

            // Load any additional streams if needed. HLS_PROP_HAS_AUDIO_EXT_STREAM was asked through
            // the stream callbacks by GstMedia.cpp, on this thread and at this point.
            if (ConnectionHolderBridge.property(holder,
                    ConnectionHolderBridge.HLS_PROP_HAS_AUDIO_EXT_STREAM, 0) != 0) {
                audioHolder = locator.getAudioStreamConnectionHolder(holder);
                if (audioHolder == null) {
                    return rc;
                }
            }

            streamArena = Arena.ofShared();
            streamCallbacks = JfxMediaNative.installStreamCallbacks(streamArena, holder);
            if (audioHolder != null) {
                audioStreamCallbacks = JfxMediaNative.installStreamCallbacks(streamArena, audioHolder);
            }

            rc = JfxMediaNative.mediaCreate(JfxMediaNative.JFXM_BACKEND_GST, contentType, location,
                    sizeHint, streamCallbacks, audioStreamCallbacks, nativeMediaHandle);
            return rc;
        } catch (IOException | RuntimeException e) {
            // CLocator::CreateConnectionHolder reported the exception and returned NULL.
            Logger.logMsg(Logger.ERROR, "GSTMedia: cannot create the connection holder: " + e);
            return rc;
        } finally {
            if (rc != MediaError.ERROR_NONE.code()) {
                // The native side retained nothing of the tables, so close_connection will never
                // fire: the holders are closed here instead of being left open until they are
                // collected, which is what the JNI code did.
                releaseCallbacks();
                closeQuietly(audioHolder);
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
            Logger.logMsg(Logger.WARNING, "GSTMedia: closing the connection holder failed: " + e);
        }
    }

    long getNativeMediaRef() {
        return refNativeMedia;
    }

    /**
     * Registers an action to run once {@code jfxm_media_dispose} has returned, i.e. once no callback
     * of this media's player or streams can fire again. Used by {@link GSTMediaPlayer} for the
     * resources whose lifetime the contract ties to the media's, not to {@code playerDispose()}.
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
        releaseCallbacks();
        for (Runnable action : afterDispose) {
            action.run();
        }
        afterDispose.clear();
    }

    private void releaseCallbacks() {
        if (streamCallbacks != null) {
            streamCallbacks.unregister();
            streamCallbacks = null;
        }
        if (audioStreamCallbacks != null) {
            audioStreamCallbacks.unregister();
            audioStreamCallbacks = null;
        }
        if (streamArena != null) {
            streamArena.close();
            streamArena = null;
        }
    }
}
