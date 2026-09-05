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

import com.sun.media.jfxmedia.effects.AudioSpectrum;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicReference;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

final class NativeAudioSpectrum implements AudioSpectrum {
    public static final int      DEFAULT_THRESHOLD = -60;
    public static final int      DEFAULT_BANDS = 128;
    public static final double   DEFAULT_INTERVAL = 0.1;

    /**
     * Handle to the native spectrum.
     */
    private final long nativeRef;

    /**
     * The pair of band arrays the native spectrum currently fills, replaced as a unit by
     * {@link #setBandCount(int)}.
     */
    private final AtomicReference<Bands> bands = new AtomicReference<>(Bands.NONE);

    /**
     * One pair of off-heap band arrays, the number of bands they hold and the arena that owns them.
     * The native bands holder is reference counted and its writer is lock free, so a pair can still
     * be written to after a newer {@code jfxm_spectrum_set_bands} call has returned; C says when it
     * is done with a pair by running that call's release action (contract section 11).
     * <p>
     * The arena is an {@link Arena#ofAuto() automatic} one on purpose. C may run the release action on
     * a GStreamer streaming thread: {@code CGstAudioSpectrum::UpdateBands} takes its own reference for
     * the write rather than locking, so the thread that finishes a write can be the one that drops the
     * last reference, and closing a shared arena there - a thread handshake - would be exactly the kind
     * of blocking the ABI forbids. Dropping the last reference instead is free, and the memory cannot
     * be reclaimed before that moment: until C releases it, the pair is reachable both from this
     * spectrum and from the facade's registry entry for the handover.
     */
    private record Bands(Arena arena, MemorySegment magnitudes, MemorySegment phases, int count) {
        static final Bands NONE = new Bands(null, MemorySegment.NULL, MemorySegment.NULL, 0);
    }

    //**************************************************************************
    //***** Constructors
    //**************************************************************************

    /**
     * Constructor.
     * @param refMedia A reference to the native spectrum.
     */
    NativeAudioSpectrum(long refMedia) {
        if (refMedia == 0) {
            throw new IllegalArgumentException("Invalid native media reference");
        }

        this.nativeRef = refMedia;
        setBandCount(DEFAULT_BANDS);
    }

    //**************************************************************************
    //***** Public functions
    //**************************************************************************
    @Override
    public boolean getEnabled() {
        return JfxMediaNative.spectrumGetEnabled(nativeRef);
    }

    @Override
    public void setEnabled(boolean enabled) {
        JfxMediaNative.spectrumSetEnabled(nativeRef, enabled);
    }

    @Override
    public int getBandCount() {
        // just return the current size of one of the band arrays
        return bands.get().count();
    }

    @Override
    public void setBandCount(int bands) {
        if (bands > 1) {
            Arena arena = Arena.ofAuto();
            MemorySegment magnitudes = arena.allocate(JAVA_FLOAT, bands);
            MemorySegment phases = arena.allocate(JAVA_FLOAT, bands);
            for (int i = 0; i < bands; i++) {
                magnitudes.setAtIndex(JAVA_FLOAT, i, DEFAULT_THRESHOLD);//Float.NEGATIVE_INFINITY;
            }

            Bands pair = new Bands(arena, magnitudes, phases, bands);
            this.bands.set(pair);
            // The release action has to be non-null even though it does nothing to this spectrum: it is
            // what gives C a way to hand the pair back, and so what lets the facade hold the pair for
            // exactly as long as C may still write through it. A superseded pair is reachable from
            // nowhere else.
            JfxMediaNative.spectrumSetBands(nativeRef, bands, magnitudes, phases, () -> release(pair));
        } else {
            // A deliberate deviation from the JNI implementation, which cleared both arrays here. The
            // pair installed before this call is still owned by C, which may still be writing through
            // it, so a rejected argument must not retire it - the reasoning spelled out on
            // release(Bands) below.
            throw new IllegalArgumentException("Number of bands must at least be 2");
        }
    }

    @Override
    public double getInterval() {
        return JfxMediaNative.spectrumGetInterval(nativeRef);
    }

    @Override
    public void setInterval(double interval) {
        if (interval * NativeMediaPlayer.ONE_SECOND >= 1) {
            JfxMediaNative.spectrumSetInterval(nativeRef, interval);
        } else {
            throw new IllegalArgumentException("Interval can't be less that 1 nanosecond");
        }
    }

    @Override
    public int getSensitivityThreshold() {
        return JfxMediaNative.spectrumGetThreshold(nativeRef);
    }

    @Override
    public void setSensitivityThreshold(int threshold) {
        if (threshold <= 0) {
            JfxMediaNative.spectrumSetThreshold(nativeRef, threshold);
        } else {
            throw new IllegalArgumentException(String.format("Sensitivity threshold must be less than 0: %d", threshold));
        }
    }

    @Override
    public float[] getMagnitudes(float[] mag) {
        Bands current = bands.get();
        return copyOut(current.magnitudes(), current.count(), mag);
    }

    @Override
    public float[] getPhases(float[] phs) {
        Bands current = bands.get();
        return copyOut(current.phases(), current.count(), phs);
    }

    /**
     * Runs when C has dropped its last reference to {@code pair}, on whichever thread did so: a
     * GStreamer main loop or spectrum thread, or the caller of {@code setBandCount} or of the media
     * dispose. It must not block.
     * <p>
     * It deliberately leaves {@link #bands} alone, so what this spectrum reports does not change when C
     * hands a pair back. C releases the <em>current</em> pair in three situations that have nothing to
     * do with the band count changing: the media is disposed, {@code jfxm_spectrum_set_bands} failed,
     * and the spectrum handle was NULL. Retiring the pair in any of them would drop
     * {@link #getBandCount()} to zero and turn {@link #getMagnitudes(float[])} into an empty array,
     * where the JNI implementation kept ordinary Java {@code float[]}s that survived all three and went
     * on reporting the last values written - or, for a spectrum that never ran, the seeded
     * {@link #DEFAULT_THRESHOLD}. That behaviour is what callers were written against, so it is what is
     * kept here.
     * <p>
     * Nothing leaks by keeping it. The release call is only a promise that C will not read or write the
     * memory again; the memory itself is Java's, held by an {@link Arena#ofAuto() automatic} arena, and
     * is reclaimed when the pair becomes unreachable - at the latest when this spectrum does. A pair
     * that <em>was</em> superseded by {@link #setBandCount(int)} is already unreachable from here, and
     * the facade drops its registry entry for the handover as it runs this action, so that one is
     * reclaimed as soon as C is done with it.
     * <p>
     * Static on purpose: a lambda over an instance method captures {@code this}, which would pin the
     * whole spectrum - and through it its player - in the facade's {@code static} registry until C ran
     * the release function, and for the life of the JVM if C never did. Over a static method the lambda
     * captures {@code pair} alone, which is exactly what the registry entry has to keep alive.
     */
    private static void release(Bands pair) {
        // Intentionally empty; see above. The pair stays readable until this spectrum is collected.
    }

    /**
     * Copies {@code size} floats out of the off-heap band array, where the JNI implementation copied
     * them into the Java array from native code with {@code SetFloatArrayRegion}.
     */
    private static float[] copyOut(MemorySegment source, int size, float[] target) {
        if (target == null || target.length < size) {
            target = new float[size];
        }
        if (size > 0) {
            MemorySegment.copy(source, JAVA_FLOAT, 0L, target, 0, size);
        }
        return target;
    }
}
