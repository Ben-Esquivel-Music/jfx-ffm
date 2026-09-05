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

package com.sun.media.jfxmediaimpl;

import com.sun.media.jfxmedia.Media;
import com.sun.media.jfxmedia.locator.ConnectionHolder;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmedia.logging.Logger;
import com.sun.media.jfxmedia.track.Track;
import com.sun.media.jfxmediaimpl.platform.Platform;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Base {@link Media} implementation class. Platforms will extend this base class.
 *
 * TODO: Nuke this class, it's not really necessary. At most we should have an impl interface
 */
public abstract class NativeMedia extends Media {
    protected final Lock markerLock = new ReentrantLock();
    protected final Lock listenerLock = new ReentrantLock();
    protected Map<String,Double> markersByName;
    protected NavigableMap<Double,String> markersByTime;
    protected WeakHashMap<MarkerStateListener,Boolean> markerListeners;

    /**
     * Actions a player peer registered for the moment {@code jfxm_media_dispose} has returned: closing
     * its own callback arena and releasing the spectrum's band memory. Guarded by this media's monitor,
     * the same one {@code dispose()} holds in every subclass.
     */
    private final List<Runnable> afterDispose = new ArrayList<>();
    private boolean disposed;

    /**
     * Constructor.
     *
     * @param locator The location of the media.
     * @throws IllegalArgumentException if <code>locator</code> is
     * <code>null</code>.
     */
    protected NativeMedia(Locator locator) {
        super(locator);
    }

    // For comparison and player creation, *must* be implemented
    public abstract Platform getPlatform();

    // --- Tracks: widen access to allow calls from NativeMediaPlayer.

    @Override
    public void addTrack(Track track) {
        super.addTrack(track);
    }

    // --- Markers ---

    @Override
    public void addMarker(String markerName, double presentationTime){
        if (markerName == null) {
            throw new IllegalArgumentException("markerName == null!");
        } else if (presentationTime < 0.0) {
            throw new IllegalArgumentException("presentationTime < 0");
        }

        markerLock.lock();
        try {
            if(markersByName == null) {
                markersByName = new HashMap<>();
                markersByTime = new TreeMap<>();
            }
            markersByName.put(markerName, presentationTime);
            markersByTime.put(presentationTime, markerName);
        } finally {
            markerLock.unlock();
        }

        fireMarkerStateEvent(true);
    }

    @Override
    public Map<String, Double> getMarkers() {
        Map<String, Double> markers = null;
        markerLock.lock();
        try {
            if(markersByName != null && !markersByName.isEmpty()) {
                markers = Collections.unmodifiableMap(markersByName);
            }
        } finally {
            markerLock.unlock();
        }
        return markers;
    }

    @Override
    public double removeMarker(String markerName) {
        if (markerName == null) {
            throw new IllegalArgumentException("markerName == null!");
        }

        double time = -1.0;
        boolean hasMarkers = false;

        markerLock.lock();
        try {
            if (markersByName.containsKey(markerName)) {
                time = markersByName.get(markerName);
                markersByName.remove(markerName);
                markersByTime.remove(time);
                hasMarkers = (markersByName.size() > 0);
            }
        } finally {
            markerLock.unlock();
        }

        fireMarkerStateEvent(hasMarkers);

        return time;
    }

    @Override
    public void removeAllMarkers() {
        markerLock.lock();
        try {
            markersByName.clear();
            markersByTime.clear();
        } finally {
            markerLock.unlock();
        }

        fireMarkerStateEvent(false);
    }

    public abstract void dispose();

    // --- FFM teardown, shared by every platform media ---
    //
    // The stubs a platform media installs, the registry ids that name their Java targets and the
    // connection holders they read through all outlive playerDispose(): the contract lets native code
    // call back until jfxm_media_dispose has returned, which happens in the subclass's dispose(). The
    // sequence that follows it is identical on every backend, and keeping one copy of it is what keeps
    // the two backends from drifting apart - a drift that already cost one leaked connection per media.

    /**
     * Registers an action to run once {@code jfxm_media_dispose} has returned, i.e. once no callback of
     * this media's player or streams can fire again. Used by the platform players for the resources
     * whose lifetime the contract ties to the media's, not to {@code playerDispose()}.
     *
     * @param action the action, never {@code null}
     */
    public final synchronized void runAfterDispose(Runnable action) {
        if (disposed) {
            action.run();
        } else {
            afterDispose.add(action);
        }
    }

    /**
     * Frees the stubs and runs the registered actions, whatever any single step does. Called by the
     * subclass's {@code dispose()} as its last act, once {@code jfxm_media_dispose} has returned.
     * <p>
     * {@code Arena.close()} on a shared arena throws {@link IllegalStateException} while a native thread
     * has not unwound from one of its stubs, and again when a second dispose races this one. Letting
     * that escape would abort the rest of the teardown and leave {@code NativeMediaPlayer.dispose()}
     * before it sets {@code isDisposed}, i.e. a player that is neither alive nor disposed, with its
     * listeners un-cleared and its registry entry leaked. The JNI implementation had no way to fail here
     * at all, so throwing is the drift: the failure is logged.
     */
    protected final synchronized void finishDispose() {
        disposed = true;

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
            StringBuilder message = new StringBuilder(getClass().getSimpleName())
                    .append(": dispose did not complete: ").append(failure);
            for (Throwable suppressed : failure.getSuppressed()) {
                message.append("; ").append(suppressed);
            }
            Logger.logMsg(Logger.ERROR, message.toString());
        }
    }

    /**
     * Unregisters this media's callback tables and closes the arena that owns their stubs. Called by
     * {@link #finishDispose()}, and by a platform media that has to undo a half-built native media. A
     * media with no callbacks of its own needs no implementation.
     */
    protected void releaseCallbacks() {
    }

    /**
     * Closes a connection holder, on a path where a failure to close must not mask the error the caller
     * is about to report - or, in {@code dispose()}, must not abort the rest of the teardown.
     *
     * @param holder the holder, or {@code null}
     */
    protected final void closeQuietly(ConnectionHolder holder) {
        if (holder == null) {
            return;
        }
        try {
            holder.closeConnection();
        } catch (RuntimeException e) {
            Logger.logMsg(Logger.WARNING, getClass().getSimpleName()
                    + ": closing the connection holder failed: " + e);
        }
    }

    Map.Entry<Double, String> getNextMarker(double time, boolean inclusive) {
        Map.Entry<Double, String> entry = null;
        markerLock.lock();
        try {
            if (markersByTime != null) {
                if (inclusive) {
                    entry = markersByTime.ceilingEntry(time);
                } else {
                    entry = markersByTime.higherEntry(time);
                }
            }
        } finally {
            markerLock.unlock();
        }
        return entry;
    }

    void addMarkerStateListener(MarkerStateListener listener) {
        if (listener != null) {
            listenerLock.lock();
            try {
                if (markerListeners == null) {
                    markerListeners = new WeakHashMap<>();
                }
                markerListeners.put(listener, Boolean.TRUE);
            } finally {
                listenerLock.unlock();
            }
        }
    }


    void removeMarkerStateListener(MarkerStateListener listener) {
        if (listener != null) {
            listenerLock.lock();
            try {
                if (markerListeners != null) {
                    markerListeners.remove(listener);
                }
            } finally {
                listenerLock.unlock();
            }
        }
    }

    void fireMarkerStateEvent(boolean hasMarkers) {
        listenerLock.lock();
        try {
            if (markerListeners != null && !markerListeners.isEmpty()) {
                for(MarkerStateListener listener : markerListeners.keySet()) {
                    if(listener != null) {
                        listener.markerStateChanged(hasMarkers);
                    }
                }
            }
        } finally {
            listenerLock.unlock();
        }
    }
}
