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

package test.com.sun.media.jfxmediaimpl.platform.osx;

/**
 * The exit codes {@link AVFVideoDisposeRaceApp} halts with and {@link AVFVideoDisposeRaceTest} reads.
 * Modelled on {@code test.shutdowntest.Constants}: 0 and 1 are left alone because the JVM and the
 * launcher already own them - 0 is a clean exit nobody asked for, and 1 is "unable to launch java
 * application" - and everything above {@link #SIGNAL_EXIT_BASE} is left alone because that is where a
 * worker killed by a signal lands, which is the failure this whole test exists to see.
 */
public final class Constants {

    /** Every iteration created, played, disposed and gave its registry entry back. */
    public static final int EXIT_OK = 2;

    /** {@code video/mp4} is not playable here, i.e. {@code jfxmedia_avf} did not load. */
    public static final int EXIT_NO_PLATFORM = 3;

    /** The generated movie was not recognised as {@code video/mp4} by the locator. */
    public static final int EXIT_CONTENT_TYPE = 4;

    /** {@code MediaManager.getPlayer} returned null or threw. */
    public static final int EXIT_NO_PLAYER = 5;

    /** The player never published READY, so nothing after preroll could be read. */
    public static final int EXIT_NO_READY = 6;

    /** READY, but no video track: no {@code -createVideoOutput}, so no display link, so no race. */
    public static final int EXIT_NO_VIDEO_TRACK = 7;

    /** The player never published PLAYING. */
    public static final int EXIT_NO_PLAYING = 8;

    /** No frame arrived after PLAYING: AVFoundation never decoded the generated movie. */
    public static final int EXIT_NO_FRAMES = 9;

    /** Frames stopped arriving, or end of media was reached, before dispose could be called. */
    public static final int EXIT_NOT_FLOWING = 10;

    /** {@code dispose()} did not return: the display-link join deadlocked. */
    public static final int EXIT_DISPOSE_HUNG = 11;

    /** The player reported an error or a halt. */
    public static final int EXIT_MEDIA_ERROR = 12;

    /** A disposed player left its callback registry entry behind. */
    public static final int EXIT_REGISTRY = 13;

    /** Anything the worker did not expect; the stack trace is on stderr. */
    public static final int EXIT_UNEXPECTED = 14;

    /**
     * What {@code Process.exitValue()} adds to a signal number when the child was killed rather than
     * exited. A SIGSEGV in the display-link callback normally reaches here as 134 rather than 139,
     * because HotSpot's own handler catches the fault, writes {@code hs_err_pid*.log} and aborts.
     */
    public static final int SIGNAL_EXIT_BASE = 128;

    private Constants() {
    }
}
