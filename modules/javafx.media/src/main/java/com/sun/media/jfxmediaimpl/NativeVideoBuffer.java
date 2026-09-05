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

import com.sun.media.jfxmedia.control.VideoDataBuffer;
import com.sun.media.jfxmedia.control.VideoFormat;
import com.sun.media.jfxmediaimpl.JfxMediaNative.FrameInfo;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native implementation of VideoDataBuffer
 */
final class NativeVideoBuffer implements VideoDataBuffer {
    private long nativePeer;
    /**
     * The geometry of the frame, read once with {@code jfxm_frame_get_info} when the buffer is created,
     * on the thread that delivered the frame. The JNI getters read the same immutable
     * {@code CVideoFrame} fields on every call, so one struct read replaces thirteen crossings
     * (contract section 8).
     */
    private final FrameInfo frameInfo;
    private final AtomicInteger holdCount;
    private NativeVideoBuffer cachedBGRARep;

    // This causes methods to throw an NPE if the native handle is invalid
    private static final boolean DEBUG_DISPOSED_BUFFERS = false;
    private static final VideoBufferDisposer disposer = new VideoBufferDisposer();

    public static NativeVideoBuffer createVideoBuffer(long nativePeer) {
        NativeVideoBuffer buffer = null;
        try {
            buffer = new NativeVideoBuffer(nativePeer);
            MediaDisposer.addResourceDisposer(buffer, nativePeer, disposer);
        } catch (Throwable t) {
            // Unlike the JNI constructor this one crosses the boundary to read the frame's geometry, and
            // the registration that follows allocates as well - it boxes the handle, builds a phantom
            // reference and a record, inserts them into a map that may resize, and on the very first frame
            // constructs the disposer singleton and starts its thread - so either step can fail. C has
            // already handed the frame over and does not delete a frame it has sent, and neither a
            // constructor that did not complete nor a registration that did not finish leaves anything
            // holding the handle - so dispose it here rather than orphan the CVideoFrame and the GstSample
            // it pins.
            if (0 != nativePeer) {
                try {
                    // HashMap.put inserts before it resizes, so a failure inside the resize can leave the
                    // entry registered, and disposing without removing it would free the frame a second
                    // time when the buffer is collected. Removing is a no-op if it never registered.
                    MediaDisposer.removeResourceDisposer(nativePeer);
                    JfxMediaNative.frameDispose(nativePeer);
                } catch (Throwable disposeFailure) {
                    // The caller has to see why the handover failed, not why the cleanup did. The identity
                    // check matters: the JVM hands out a preallocated OutOfMemoryError when it cannot
                    // allocate one, so both throwables can be the same instance, which addSuppressed
                    // rejects.
                    if (disposeFailure != t) {
                        t.addSuppressed(disposeFailure);
                    }
                }
            }
            throw t;
        }
        return buffer;
    }

    private NativeVideoBuffer(long nativePeer) {
        holdCount = new AtomicInteger(1);
        this.nativePeer = nativePeer;
        this.frameInfo = 0 != nativePeer ? JfxMediaNative.frameGetInfo(nativePeer) : FrameInfo.NONE;
    }

    /* Call this when we hand this frame off to a renderer */
    @Override
    public void holdFrame() {
        if (0 != nativePeer) {
            holdCount.incrementAndGet();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
    }

    /* Call this when the renderer is done with the frame so that it may be reused */
    @Override
    public void releaseFrame() {
        if (0 != nativePeer) {
            if (holdCount.decrementAndGet() <= 0) {
                // release our cached rep if it's there
                if (null != cachedBGRARep) {
                    cachedBGRARep.releaseFrame();
                    cachedBGRARep = null;
                }

                // last reference released, dispose and clear our native handle
                MediaDisposer.removeResourceDisposer(nativePeer);
                JfxMediaNative.frameDispose(nativePeer);
                nativePeer = 0;
            }
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
    }

    @Override
    public double getTimestamp() {
        if (0 != nativePeer) {
            return frameInfo.timestamp();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return 0.0;
    }

    @Override
    public ByteBuffer getBufferForPlane(int plane) {
        if (0 != nativePeer) {
            // NewDirectByteBuffer sets BIG_ENDIAN to be consistent with ByteBuffer, so the JNI path
            // forced native order here; planeBuffer() has already applied it. It also reproduces what
            // NewDirectByteBuffer(NULL, 0) gave a caller who asked for a plane the frame does not have:
            // an empty direct buffer, never null.
            return JfxMediaNative.planeBuffer(frameInfo, plane);
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return null;
    }

    @Override
    public int getWidth() {
        if (0 != nativePeer) {
            return frameInfo.width();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return 0;
    }

    @Override
    public int getHeight() {
        if (0 != nativePeer) {
            return frameInfo.height();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return 0;
    }

    @Override
    public int getEncodedWidth() {
        if (0 != nativePeer) {
            return frameInfo.encodedWidth();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return 0;
    }

    @Override
    public int getEncodedHeight() {
        if (0 != nativePeer) {
            return frameInfo.encodedHeight();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return 0;
    }

    @Override
    public VideoFormat getFormat() {
        if (0 != nativePeer) {
            int formatType = frameInfo.format();
            return VideoFormat.formatForType(formatType);
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return null;
    }

    @Override
    public boolean hasAlpha() {
        if (0 != nativePeer) {
            return frameInfo.hasAlpha();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return false;
    }

    @Override
    public int getPlaneCount() {
        if (0 != nativePeer) {
            return frameInfo.planeCount();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return 0;
    }

    @Override
    public int getStrideForPlane(int planeIndex) {
        if (0 != nativePeer) {
            int[] strides = getPlaneStrides();
            return strides[planeIndex];
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return 0;
    }

    @Override
    public int[] getPlaneStrides() {
        if (0 != nativePeer) {
            // nativeGetPlaneStrides returned null for a plane count outside 1..4 and a fresh array
            // of exactly planeCount entries otherwise; both are preserved. JfxmFrameInfo reports a
            // count of zero as four zero strides, so the count, not the array length, decides.
            int planeCount = frameInfo.planeCount();
            if (planeCount < 1 || planeCount > 4) {
                return null;
            }
            return frameInfo.strides().clone();
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return null;
    }

    @Override
    public VideoDataBuffer convertToFormat(VideoFormat newFormat) {
        if (0 != nativePeer) {
            // see if we have a converted frame already, if we do bump the hold count and return it instead
            if (newFormat == VideoFormat.BGRA_PRE && null != cachedBGRARep) {
                cachedBGRARep.holdFrame();
                return cachedBGRARep;
            }

            long newFrame = JfxMediaNative.frameConvert(nativePeer, newFormat.getNativeType());
            if (newFrame == nativePeer) {
                // CVideoFrame::ConvertToFormat hands the frame itself back when it is already in the
                // requested format. Wrapping it again would put two owners on one handle - a double free
                // when both release it, and, if the second wrapper failed to build, a dispose of this
                // still-live frame by the guard in createVideoBuffer. Hand this buffer back instead,
                // holding the reference the caller is expected to release, as the cached rep above does.
                holdFrame();
                return this;
            }
            if (0 != newFrame) {
                NativeVideoBuffer frame = createVideoBuffer(newFrame);
                if (newFormat == VideoFormat.BGRA_PRE) {
                    frame.holdFrame(); // we need to keep one reference around so it doesn't disappear
                    cachedBGRARep = frame;
                }
                return frame;
            } else {
                throw new UnsupportedOperationException("Conversion from "+getFormat()+" to "+newFormat+" is not supported.");
            }
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
        return null;
    }

    @Override
    public void setDirty() {
        if (0 != nativePeer) {
            JfxMediaNative.frameSetDirty(nativePeer);
        } else if (DEBUG_DISPOSED_BUFFERS) {
            throw new NullPointerException("method called on disposed NativeVideoBuffer");
        }
    }

    private static class VideoBufferDisposer implements MediaDisposer.ResourceDisposer {
        @Override
        public void disposeResource(Object resource) {
            // resource is Long containing the native handle
            if (resource instanceof Long) {
                JfxMediaNative.frameDispose(((Long)resource).longValue());
            }
        }
    }

    @Override
    public String toString() {
        if (DEBUG_DISPOSED_BUFFERS) {
            return "[NativeVideoBuffer peer="+Long.toHexString(nativePeer)+", format="+getFormat()+", size=("+getWidth()+","+getHeight()+"), timestamp="+getTimestamp()+", retain count "+holdCount.get()+"]";
        }
        return "[NativeVideoBuffer peer="+Long.toHexString(nativePeer)+", format="+getFormat()+", size=("+getWidth()+","+getHeight()+"), timestamp="+getTimestamp()+"]";
    }
}
