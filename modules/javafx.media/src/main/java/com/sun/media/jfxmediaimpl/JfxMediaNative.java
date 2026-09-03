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

package com.sun.media.jfxmediaimpl;

import com.sun.glass.utils.NativeLibLoader;
import com.sun.javafx.PlatformUtil;
import com.sun.media.jfxmedia.MediaError;
import com.sun.media.jfxmedia.locator.ConnectionHolder;
import com.sun.media.jfxmedia.locator.ConnectionHolderBridge;
import com.sun.media.jfxmedia.logging.Logger;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The single point of contact between {@code javafx.media} and the {@code jfxm_*} C ABI exported by the
 * {@code jfxmedia} library ({@code src/main/native/jfxmedia/jfxmedia_api.h}). It owns the library load,
 * the {@link SymbolLookup}, the {@link Linker}, every downcall handle, the three struct layouts, the
 * upcall stubs of the log sink and of the per-player / per-stream callback tables, and the registry that
 * maps the opaque {@code void* user} values native code hands back to Java objects.
 * <p>
 * Every restricted {@code java.lang.foreign} operation used by this module lives in this class. The
 * callers ({@code GSTMediaPlayer}, {@code GSTMedia}, {@code GSTPlatform}, {@code OSXMediaPlayer},
 * {@code OSXMedia}, {@code OSXPlatform}, {@code NativeVideoBuffer}, {@code NativeAudioEqualizer},
 * {@code NativeEqualizerBand}, {@code NativeAudioSpectrum}, {@code Logger}) only call the typed static
 * wrappers below. Native handles cross this boundary as {@code long} values, exactly as the JNI peers
 * stored them; the conversion to {@link MemorySegment} happens once, here.
 * <p>
 * See {@code modules/javafx.media/FFM-ABI-CONTRACT.md} for the ABI this class implements. This class is
 * not public API: the {@code com.sun.media.jfxmediaimpl} package is not exported by the
 * {@code javafx.media} module. The members documented as test hooks are public only because the
 * module's binding tests live in the unnamed module and reach them through {@code --add-exports}.
 */
public final class JfxMediaNative {

    /** The {@code jfxm_*} ABI revision this class is written against ({@code JFXM_ABI_VERSION}). */
    public static final int JFXM_ABI_VERSION = 1;

    /** Backend selector of {@code jfxm_media_create}: the GStreamer pipeline. */
    public static final int JFXM_BACKEND_GST = 0;

    /** Backend selector of {@code jfxm_media_create}: AVFoundation (macOS only). */
    public static final int JFXM_BACKEND_AVF = 1;

    private static final int ERROR_NONE = MediaError.ERROR_NONE.code();

    /*
     * Upcall descriptors. Every void* user arrives as a zero-length MemorySegment whose address is the
     * registry id; every const char* arrives as a zero-length segment that is read through cString().
     * Booleans are int32_t (contract section 2).
     */
    private static final FunctionDescriptor MEDIA_ERROR_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor HALT_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_DOUBLE);
    private static final FunctionDescriptor STATE_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE);
    private static final FunctionDescriptor NEW_FRAME_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor FRAME_SIZE_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor AUDIO_TRACK_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS,
            JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_FLOAT);
    private static final FunctionDescriptor VIDEO_TRACK_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS,
            JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_INT);
    private static final FunctionDescriptor SUBTITLE_TRACK_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS,
            JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor MARKER_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_DOUBLE);
    private static final FunctionDescriptor BUFFER_PROGRESS_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS,
            JAVA_DOUBLE, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final FunctionDescriptor DURATION_UPDATE_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE);
    private static final FunctionDescriptor AUDIO_SPECTRUM_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS,
            JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT);
    private static final FunctionDescriptor WARNING_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);

    private static final FunctionDescriptor INT_OF_USER_FD = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor READ_BLOCK_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT);
    private static final FunctionDescriptor COPY_BLOCK_FD = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor SEEK_FD = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor PROPERTY_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor CLOSE_CONNECTION_FD = FunctionDescriptor.ofVoid(ADDRESS);

    private static final FunctionDescriptor LOG_FD = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor RELEASE_FD = FunctionDescriptor.ofVoid(ADDRESS);

    /** One slot of a callback table: its C field name, its prototype and its Java target. */
    private record Slot(String name, FunctionDescriptor descriptor, MethodHandle target) {
    }

    /** {@code JfxmPlayerCallbacks}, in declaration order (contract section 10). */
    private static final Slot[] PLAYER_SLOTS = {
        slot("media_error", MEDIA_ERROR_FD, "onMediaError"),
        slot("halt", HALT_FD, "onHalt"),
        slot("state", STATE_FD, "onState"),
        slot("new_frame", NEW_FRAME_FD, "onNewFrame"),
        slot("frame_size", FRAME_SIZE_FD, "onFrameSize"),
        slot("audio_track", AUDIO_TRACK_FD, "onAudioTrack"),
        slot("video_track", VIDEO_TRACK_FD, "onVideoTrack"),
        slot("subtitle_track", SUBTITLE_TRACK_FD, "onSubtitleTrack"),
        slot("marker", MARKER_FD, "onMarker"),
        slot("buffer_progress", BUFFER_PROGRESS_FD, "onBufferProgress"),
        slot("duration_update", DURATION_UPDATE_FD, "onDurationUpdate"),
        slot("audio_spectrum", AUDIO_SPECTRUM_FD, "onAudioSpectrum"),
        slot("warning", WARNING_FD, "onWarning"),
    };

    /** {@code JfxmStreamCallbacks}, in declaration order (contract section 9). */
    private static final Slot[] STREAM_SLOTS = {
        slot("need_buffer", INT_OF_USER_FD, "onNeedBuffer"),
        slot("is_seekable", INT_OF_USER_FD, "onIsSeekable"),
        slot("is_random_access", INT_OF_USER_FD, "onIsRandomAccess"),
        slot("read_next_block", INT_OF_USER_FD, "onReadNextBlock"),
        slot("read_block", READ_BLOCK_FD, "onReadBlock"),
        slot("copy_block", COPY_BLOCK_FD, "onCopyBlock"),
        slot("seek", SEEK_FD, "onSeek"),
        slot("property", PROPERTY_FD, "onProperty"),
        slot("close_connection", CLOSE_CONNECTION_FD, "onCloseConnection"),
    };

    /** {@code JfxmPlayerCallbacks}: 13 function pointers. */
    public static final StructLayout PLAYER_CALLBACKS = tableLayout(PLAYER_SLOTS);

    /** {@code JfxmStreamCallbacks}: 9 function pointers. */
    public static final StructLayout STREAM_CALLBACKS = tableLayout(STREAM_SLOTS);

    /**
     * {@code JfxmFrameInfo} (contract section 8). {@link #FRAME_INFO_FIELDS} lists the field names in the
     * index order {@code jfxm_offsetof_frame_info} uses.
     */
    public static final StructLayout FRAME_INFO = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("timestamp"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("encoded_width"),
            JAVA_INT.withName("encoded_height"),
            JAVA_INT.withName("format"),
            JAVA_INT.withName("has_alpha"),
            JAVA_INT.withName("plane_count"),
            JAVA_INT.withName("reserved"),
            MemoryLayout.sequenceLayout(4, JAVA_INT).withName("strides"),
            MemoryLayout.sequenceLayout(4, JAVA_LONG).withName("plane_size"),
            MemoryLayout.sequenceLayout(4, ADDRESS).withName("plane_data"));

    /** The {@link #FRAME_INFO} field names, indexed as {@code jfxm_offsetof_frame_info} expects. */
    public static final List<String> FRAME_INFO_FIELDS = List.of("timestamp", "width", "height",
            "encoded_width", "encoded_height", "format", "has_alpha", "plane_count", "reserved", "strides",
            "plane_size", "plane_data");

    private static final long OFFSET_TIMESTAMP = frameInfoOffset("timestamp");
    private static final long OFFSET_WIDTH = frameInfoOffset("width");
    private static final long OFFSET_HEIGHT = frameInfoOffset("height");
    private static final long OFFSET_ENCODED_WIDTH = frameInfoOffset("encoded_width");
    private static final long OFFSET_ENCODED_HEIGHT = frameInfoOffset("encoded_height");
    private static final long OFFSET_FORMAT = frameInfoOffset("format");
    private static final long OFFSET_HAS_ALPHA = frameInfoOffset("has_alpha");
    private static final long OFFSET_PLANE_COUNT = frameInfoOffset("plane_count");
    private static final long OFFSET_STRIDES = frameInfoOffset("strides");
    private static final long OFFSET_PLANE_SIZE = frameInfoOffset("plane_size");
    private static final long OFFSET_PLANE_DATA = frameInfoOffset("plane_data");
    private static final int MAX_PLANES = 4;

    private static final Linker LINKER = Linker.nativeLinker();

    /** Names of every symbol this class binds, in binding order; read by the binding tests. */
    private static final List<String> BOUND_SYMBOLS = Collections.synchronizedList(new ArrayList<>());

    /** Names of the bound symbols the library does not export; read by the binding tests. */
    private static final List<String> MISSING_SYMBOLS = Collections.synchronizedList(new ArrayList<>());

    /**
     * The {@code void* user} registry (contract section 3): one entry per player and one per
     * {@link ConnectionHolder}. Ids are Java assigned, start at one and are never reused; zero is never
     * a valid id. Native code never holds a Java reference.
     */
    private static final Map<Long, Object> REGISTRY = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    /** The one log sink stub of the process, in the global arena (contract section 4). */
    private static final MemorySegment LOG_SINK;

    /**
     * The one {@code JfxmReleaseFn} stub of the process, also in the global arena: every
     * {@code jfxm_spectrum_set_bands} handover shares it because only the {@code user} value differs,
     * which keeps a stub from ever having to outlive the arena that owns it.
     */
    private static final MemorySegment RELEASE_SINK;

    /** Optional observer of log sink upcalls; a test hook, {@code null} in production. */
    private static volatile LogObserver logObserver;

    private static boolean librariesLoaded;

    /**
     * The failure that loading the library, resolving a symbol or checking the ABI version ended in, or
     * {@code null}. It is raised by {@link #loadLibraries()} and by every downcall bound after it
     * happened, so the tenth caller sees the same message as the first rather than a bare
     * {@code NoClassDefFoundError}.
     */
    private static UnsatisfiedLinkError initFailure;

    private static final SymbolLookup LOOKUP;

    static {
        SymbolLookup lookup = null;
        try {
            loadNativeLibraries();
            lookup = SymbolLookup.loaderLookup();
        } catch (UnsatisfiedLinkError e) {
            initFailure = e;
        }
        LOOKUP = lookup;
        LOG_SINK = upcallStub(upcallTarget("onLog", LOG_FD), LOG_FD, Arena.global());
        RELEASE_SINK = upcallStub(upcallTarget("onRelease", RELEASE_FD), RELEASE_FD, Arena.global());
    }

    /* Binding order matters: the ABI guard runs before any other symbol is bound. */
    private static final MethodHandle JFXM_ABI_VERSION_MH = bind("jfxm_abi_version",
            FunctionDescriptor.of(JAVA_INT));

    static {
        checkAbiVersion();
    }

    private static final MethodHandle JFXM_SIZEOF_PLAYER_CALLBACKS = bind("jfxm_sizeof_player_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle JFXM_SIZEOF_STREAM_CALLBACKS = bind("jfxm_sizeof_stream_callbacks",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle JFXM_SIZEOF_FRAME_INFO = bind("jfxm_sizeof_frame_info",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle JFXM_OFFSETOF_FRAME_INFO = bind("jfxm_offsetof_frame_info",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle JFXM_EVENT_PLAYER_STATE = bind("jfxm_event_player_state",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    private static final MethodHandle JFXM_PLATFORM_INIT = bind("jfxm_platform_init",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle JFXM_OSX_PLATFORM_INIT = bind("jfxm_osx_platform_init",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle JFXM_LOG_INIT = bind("jfxm_log_init",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_LOG_SET_LEVEL = bind("jfxm_log_set_level",
            FunctionDescriptor.ofVoid(JAVA_INT));

    private static final MethodHandle JFXM_MEDIA_CREATE = bind("jfxm_media_create",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS,
                    ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_MEDIA_DISPOSE = bind("jfxm_media_dispose",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle JFXM_PLAYER_INIT = bind("jfxm_player_init",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_GET_AUDIO_EQUALIZER = bind("jfxm_player_get_audio_equalizer",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_GET_AUDIO_SPECTRUM = bind("jfxm_player_get_audio_spectrum",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_GET_AUDIO_SYNC_DELAY = bind("jfxm_player_get_audio_sync_delay",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_SET_AUDIO_SYNC_DELAY = bind("jfxm_player_set_audio_sync_delay",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle JFXM_PLAYER_PLAY = bind("jfxm_player_play",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_PAUSE = bind("jfxm_player_pause",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_STOP = bind("jfxm_player_stop",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_FINISH = bind("jfxm_player_finish",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_GET_RATE = bind("jfxm_player_get_rate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_SET_RATE = bind("jfxm_player_set_rate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));
    private static final MethodHandle JFXM_PLAYER_GET_PRESENTATION_TIME = bind("jfxm_player_get_presentation_time",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_GET_VOLUME = bind("jfxm_player_get_volume",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_SET_VOLUME = bind("jfxm_player_set_volume",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));
    private static final MethodHandle JFXM_PLAYER_GET_BALANCE = bind("jfxm_player_get_balance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_SET_BALANCE = bind("jfxm_player_set_balance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT));
    private static final MethodHandle JFXM_PLAYER_GET_DURATION = bind("jfxm_player_get_duration",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_SEEK = bind("jfxm_player_seek",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE));
    private static final MethodHandle JFXM_PLAYER_GET_MUTE = bind("jfxm_player_get_mute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_PLAYER_SET_MUTE = bind("jfxm_player_set_mute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private static final MethodHandle JFXM_FRAME_GET_INFO = bind("jfxm_frame_get_info",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_FRAME_CONVERT = bind("jfxm_frame_convert",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle JFXM_FRAME_SET_DIRTY = bind("jfxm_frame_set_dirty",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle JFXM_FRAME_DISPOSE = bind("jfxm_frame_dispose",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle JFXM_EQ_GET_ENABLED = bind("jfxm_eq_get_enabled",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle JFXM_EQ_SET_ENABLED = bind("jfxm_eq_set_enabled",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle JFXM_EQ_GET_NUM_BANDS = bind("jfxm_eq_get_num_bands",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle JFXM_EQ_ADD_BAND = bind("jfxm_eq_add_band",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
    private static final MethodHandle JFXM_EQ_REMOVE_BAND = bind("jfxm_eq_remove_band",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE));

    private static final MethodHandle JFXM_EQ_BAND_GET_CENTER_FREQUENCY = bind("jfxm_eq_band_get_center_frequency",
            FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
    private static final MethodHandle JFXM_EQ_BAND_SET_CENTER_FREQUENCY = bind("jfxm_eq_band_set_center_frequency",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE));
    private static final MethodHandle JFXM_EQ_BAND_GET_BANDWIDTH = bind("jfxm_eq_band_get_bandwidth",
            FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
    private static final MethodHandle JFXM_EQ_BAND_SET_BANDWIDTH = bind("jfxm_eq_band_set_bandwidth",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE));
    private static final MethodHandle JFXM_EQ_BAND_GET_GAIN = bind("jfxm_eq_band_get_gain",
            FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
    private static final MethodHandle JFXM_EQ_BAND_SET_GAIN = bind("jfxm_eq_band_set_gain",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE));

    private static final MethodHandle JFXM_SPECTRUM_GET_ENABLED = bind("jfxm_spectrum_get_enabled",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle JFXM_SPECTRUM_SET_ENABLED = bind("jfxm_spectrum_set_enabled",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle JFXM_SPECTRUM_SET_BANDS = bind("jfxm_spectrum_set_bands",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle JFXM_SPECTRUM_GET_INTERVAL = bind("jfxm_spectrum_get_interval",
            FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS));
    private static final MethodHandle JFXM_SPECTRUM_SET_INTERVAL = bind("jfxm_spectrum_set_interval",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE));
    private static final MethodHandle JFXM_SPECTRUM_GET_THRESHOLD = bind("jfxm_spectrum_get_threshold",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle JFXM_SPECTRUM_SET_THRESHOLD = bind("jfxm_spectrum_set_threshold",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private JfxMediaNative() {
    }

    // ---------------------------------------------------------------------------------------------
    // Library loading, binding, ABI guard
    // ---------------------------------------------------------------------------------------------

    /**
     * Loads {@code glib-lite} (Windows, macOS), {@code gstreamer-lite} (non-Linux) and {@code jfxmedia}
     * with its platform dependency list, in that order, through {@link NativeLibLoader}, exactly as
     * {@code NativeMediaManager} used to. Idempotent. Both {@code NativeMediaManager} and this class's
     * own initializer call it, so a binding test can touch the facade without constructing the manager.
     *
     * @throws UnsatisfiedLinkError if a library cannot be loaded, a {@code jfxm_*} symbol the facade
     *         binds is missing, or the library's {@code jfxm_abi_version()} is not
     *         {@link #JFXM_ABI_VERSION}
     */
    public static void loadLibraries() {
        loadNativeLibraries();
        if (initFailure != null) {
            throw initFailure;
        }
    }

    private static synchronized void loadNativeLibraries() {
        if (librariesLoaded) {
            return;
        }
        ArrayList<String> dependencies = new ArrayList<>();
        if (PlatformUtil.isWindows() || PlatformUtil.isMac()) {
            NativeLibLoader.loadLibrary("glib-lite");
        }

        if (!PlatformUtil.isLinux()) {
            NativeLibLoader.loadLibrary("gstreamer-lite");
        } else {
            dependencies.add("gstreamer-lite");
        }
        if (PlatformUtil.isLinux()) {
            dependencies.add("fxplugins");
            dependencies.add("avplugin");
            dependencies.add("avplugin-54");
            dependencies.add("avplugin-56");
            dependencies.add("avplugin-57");
            dependencies.add("avplugin-ffmpeg-56");
            dependencies.add("avplugin-ffmpeg-57");
            dependencies.add("avplugin-ffmpeg-58");
            dependencies.add("avplugin-ffmpeg-59");
            dependencies.add("avplugin-ffmpeg-60");
            dependencies.add("avplugin-ffmpeg-61");
            dependencies.add("avplugin-ffmpeg-62");
        }
        if (PlatformUtil.isMac()) {
            dependencies.add("fxplugins");
            dependencies.add("glib-lite");
            dependencies.add("jfxmedia_avf");
        }
        if (PlatformUtil.isWindows()) {
            dependencies.add("fxplugins");
            dependencies.add("glib-lite");
        }
        NativeLibLoader.loadLibrary("jfxmedia", dependencies);
        librariesLoaded = true;
    }

    /**
     * Binds an exported {@code jfxm_*} symbol. When the library could not be loaded, a symbol is missing
     * or the ABI version does not match, the returned handle has the requested type and throws the
     * recorded {@link UnsatisfiedLinkError} when invoked: the failure is reported once, by name, from
     * {@link #loadLibraries()} and from the first call, instead of turning every later touch of this
     * class into a {@code NoClassDefFoundError}.
     */
    @SuppressWarnings("restricted")
    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        BOUND_SYMBOLS.add(name);
        if (LOOKUP != null) {
            Optional<MemorySegment> symbol = LOOKUP.find(name);
            if (symbol.isEmpty()) {
                MISSING_SYMBOLS.add(name);
                if (initFailure == null) {
                    initFailure = new UnsatisfiedLinkError("missing native symbol: " + name);
                }
            } else if (initFailure == null) {
                return LINKER.downcallHandle(symbol.get(), descriptor);
            }
        }
        return failingHandle(descriptor, name);
    }

    private static MethodHandle failingHandle(FunctionDescriptor descriptor, String name) {
        MethodType type = descriptor.toMethodType();
        UnsatisfiedLinkError error = initFailure != null
                ? initFailure : new UnsatisfiedLinkError("missing native symbol: " + name);
        MethodHandle thrower = MethodHandles.insertArguments(
                MethodHandles.throwException(type.returnType(), UnsatisfiedLinkError.class), 0, error);
        return MethodHandles.dropArguments(thrower, 0, type.parameterList());
    }

    private static void checkAbiVersion() {
        if (initFailure != null) {
            return;
        }
        int actual;
        try {
            actual = (int) JFXM_ABI_VERSION_MH.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
        if (actual != JFXM_ABI_VERSION) {
            initFailure = new UnsatisfiedLinkError("jfxmedia ABI version mismatch: expected "
                    + JFXM_ABI_VERSION + ", found " + actual);
        }
    }

    private static Error unexpected(Throwable t) {
        if (t instanceof Error e) {
            return e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        // Only linkage failures can land here: C cannot throw.
        return new AssertionError(t);
    }

    private static Slot slot(String name, FunctionDescriptor descriptor, String target) {
        return new Slot(name, descriptor, upcallTarget(target, descriptor));
    }

    private static MethodHandle upcallTarget(String name, FunctionDescriptor descriptor) {
        try {
            return MethodHandles.lookup().findStatic(JfxMediaNative.class, name, descriptor.toMethodType());
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static StructLayout tableLayout(Slot[] slots) {
        MemoryLayout[] fields = new MemoryLayout[slots.length];
        for (int i = 0; i < slots.length; i++) {
            fields[i] = ADDRESS.withName(slots[i].name());
        }
        return MemoryLayout.structLayout(fields);
    }

    private static long frameInfoOffset(String field) {
        return FRAME_INFO.byteOffset(PathElement.groupElement(field));
    }

    @SuppressWarnings("restricted")
    private static MemorySegment upcallStub(MethodHandle target, FunctionDescriptor descriptor, Arena arena) {
        return LINKER.upcallStub(target, descriptor, arena);
    }

    /**
     * Reads a NUL-terminated UTF-8 string handed to an upcall. The pointer is valid only for the
     * duration of the call (contract section 2), so the copy is taken before the target returns.
     */
    @SuppressWarnings("restricted")
    private static String cString(MemorySegment s) {
        if (s.address() == 0L) {
            return null;
        }
        return s.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static MemorySegment handle(long address) {
        return MemorySegment.ofAddress(address);
    }

    // ---------------------------------------------------------------------------------------------
    // Test hooks
    // ---------------------------------------------------------------------------------------------

    /**
     * Test hook: the names of every {@code jfxm_*} symbol this class binds, in binding order.
     *
     * @return an unmodifiable snapshot
     */
    public static List<String> boundSymbols() {
        synchronized (BOUND_SYMBOLS) {
            return List.copyOf(BOUND_SYMBOLS);
        }
    }

    /**
     * Test hook: the bound symbols the loaded library does not export.
     *
     * @return an unmodifiable snapshot, empty when every symbol resolved
     */
    public static List<String> missingSymbols() {
        synchronized (MISSING_SYMBOLS) {
            return List.copyOf(MISSING_SYMBOLS);
        }
    }

    /**
     * Test hook: whether the loaded library exports {@code name}.
     *
     * @param name a symbol name
     * @return true if the symbol resolves through the loader lookup
     */
    public static boolean resolvesSymbol(String name) {
        return LOOKUP != null && LOOKUP.find(name).isPresent();
    }

    /**
     * Test hook: the number of live registry entries (players and connection holders).
     *
     * @return the registry size
     */
    public static int registrySize() {
        return REGISTRY.size();
    }

    /** Receives every message the native log sink delivers; a test hook. */
    public interface LogObserver {
        /**
         * Called on the native thread that logged, before the message reaches {@link Logger}.
         *
         * @param level a {@link Logger} level constant
         * @param message the message, never {@code null}
         */
        void onMessage(int level, String message);
    }

    /**
     * Test hook: installs (or, with {@code null}, removes) an observer of the native log sink.
     *
     * @param observer the observer, may be {@code null}
     */
    public static void setLogObserver(LogObserver observer) {
        logObserver = observer;
    }

    /**
     * Test hook: calls one slot of a callback table through its function pointer, the way C calls it -
     * the upcall stub itself, not the Java method behind it. It is the only way to prove from a test
     * that a target swallows whatever it throws, and that a callback arriving after the media was
     * disposed finds nothing (contract sections 4 and 9).
     * <p>
     * It lives here because linking a downcall handle is a restricted operation: {@code javafx.media}
     * holds the module's native access, the tests run in the unnamed module and do not, so the call
     * belongs on this side of the boundary rather than behind a wider {@code --enable-native-access}.
     *
     * @param callbacks a table from {@link #installPlayerCallbacks} or {@link #installStreamCallbacks}
     * @param slot the C field name of the slot, for instance {@code "read_next_block"}
     * @param args the arguments that follow {@code void* user}, which is taken from the table
     * @return the slot's return value, or {@code null} when the slot returns {@code void}
     * @throws IllegalArgumentException if the table has no slot of that name
     */
    @SuppressWarnings("restricted")
    public static Object invokeSlot(CallbackTable callbacks, String slot, Object... args) {
        Slot target = null;
        for (Slot candidate : callbacks.slots) {
            if (candidate.name().equals(slot)) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("no such callback slot: " + slot);
        }
        MemorySegment stub = callbacks.table()
                .get(ADDRESS, callbacks.layout.byteOffset(PathElement.groupElement(slot)));
        List<Object> arguments = new ArrayList<>(args.length + 1);
        arguments.add(callbacks.user());
        Collections.addAll(arguments, args);
        try {
            return LINKER.downcallHandle(stub, target.descriptor()).invokeWithArguments(arguments);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Registry
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers a Java object and returns the id native code names it by.
     *
     * @param o the object, never {@code null}
     * @return the registry id, never zero
     */
    public static long register(Object o) {
        long id = NEXT_ID.getAndIncrement();
        REGISTRY.put(id, o);
        return id;
    }

    /**
     * Removes a registry entry. A late upcall carrying the id is then ignored.
     *
     * @param id the registry id
     */
    public static void unregister(long id) {
        REGISTRY.remove(id);
    }

    /**
     * Returns the object registered under {@code id}.
     *
     * @param id the registry id
     * @return the object, or {@code null} if the id is unknown
     */
    public static Object lookup(long id) {
        return REGISTRY.get(id);
    }

    private static NativeMediaPlayer player(MemorySegment user) {
        return lookup(user.address()) instanceof NativeMediaPlayer player ? player : null;
    }

    private static ConnectionHolder holder(MemorySegment user) {
        return lookup(user.address()) instanceof ConnectionHolder holder ? holder : null;
    }

    // ---------------------------------------------------------------------------------------------
    // Callback tables
    // ---------------------------------------------------------------------------------------------

    /**
     * A callback table filled with upcall stubs plus the registry id of its Java target. The stubs live
     * in the arena the table was allocated from; the caller closes that arena after
     * {@code jfxm_media_dispose} has returned and calls {@link #unregister()} (contract section 4).
     */
    public static final class CallbackTable {
        private final StructLayout layout;
        private final Slot[] slots;
        private final MemorySegment table;
        private final long id;

        private CallbackTable(StructLayout layout, Slot[] slots, MemorySegment table, long id) {
            this.layout = layout;
            this.slots = slots;
            this.table = table;
            this.id = id;
        }

        /**
         * Returns the table segment to pass as {@code const Jfxm*Callbacks*}.
         *
         * @return the table
         */
        public MemorySegment table() {
            return table;
        }

        /**
         * Returns the {@code void* user} value to pass beside the table.
         *
         * @return the registry id as an address
         */
        public MemorySegment user() {
            return MemorySegment.ofAddress(id);
        }

        /**
         * Returns the registry id of the Java target.
         *
         * @return the id
         */
        public long id() {
            return id;
        }

        /** Removes the Java target from the registry. Safe to call more than once. */
        public void unregister() {
            JfxMediaNative.unregister(id);
        }
    }

    /**
     * Builds a {@code JfxmPlayerCallbacks} table whose 13 stubs deliver to {@code player}'s
     * {@code send*} methods, and registers the player.
     *
     * @param arena the shared arena owning the stubs; closed by the caller after the media is disposed
     * @param player the target
     * @return the table and registry id
     */
    public static CallbackTable installPlayerCallbacks(Arena arena, NativeMediaPlayer player) {
        return installCallbacks(arena, PLAYER_CALLBACKS, PLAYER_SLOTS, player);
    }

    /**
     * Builds a {@code JfxmStreamCallbacks} table whose 9 stubs deliver to {@code holder}, and registers
     * the holder.
     *
     * @param arena the shared arena owning the stubs; closed by the caller after the media is disposed
     * @param holder the target
     * @return the table and registry id
     */
    public static CallbackTable installStreamCallbacks(Arena arena, ConnectionHolder holder) {
        return installCallbacks(arena, STREAM_CALLBACKS, STREAM_SLOTS, holder);
    }

    private static CallbackTable installCallbacks(Arena arena, StructLayout layout, Slot[] slots,
                                                  Object target) {
        MemorySegment table = arena.allocate(layout);
        for (Slot slot : slots) {
            MemorySegment stub = upcallStub(slot.target(), slot.descriptor(), arena);
            table.set(ADDRESS, layout.byteOffset(PathElement.groupElement(slot.name())), stub);
        }
        return new CallbackTable(layout, slots, table, register(target));
    }

    private static MemorySegment tableOf(CallbackTable callbacks) {
        return callbacks == null ? MemorySegment.NULL : callbacks.table();
    }

    private static MemorySegment userOf(CallbackTable callbacks) {
        return callbacks == null ? MemorySegment.NULL : callbacks.user();
    }

    // ---------------------------------------------------------------------------------------------
    // Player callback targets (contract section 10). Return 1 = delivered, 0 = the target threw.
    // An unknown id (a late callback after dispose) is ignored and reported as delivered.
    // ---------------------------------------------------------------------------------------------

    private static void logUpcallFailure(String slot, Throwable t) {
        try {
            Logger.logMsg(Logger.ERROR, "JfxMediaNative", slot, "upcall failed: " + t);
        } catch (Throwable ignored) {
            // Logging must never let anything escape into native code.
        }
    }

    private static int onMediaError(MemorySegment user, int errorCode) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendPlayerMediaErrorEvent(errorCode);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("media_error", t);
            return 0;
        }
    }

    private static int onHalt(MemorySegment user, MemorySegment message, double time) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendPlayerHaltEvent(cString(message), time);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("halt", t);
            return 0;
        }
    }

    private static int onState(MemorySegment user, int state, double presentTime) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendPlayerStateEvent(state, presentTime);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("state", t);
            return 0;
        }
    }

    private static int onNewFrame(MemorySegment user, MemorySegment frame) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendNewFrameEvent(frame.address());
            } else if (frame.address() != 0L) {
                // Nobody will ever hold or release this frame; Java owns it once delivered.
                frameDispose(frame.address());
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("new_frame", t);
            return 0;
        }
    }

    private static int onFrameSize(MemorySegment user, int width, int height) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendFrameSizeChangedEvent(width, height);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("frame_size", t);
            return 0;
        }
    }

    private static int onAudioTrack(MemorySegment user, int enabled, long trackId, MemorySegment name,
                                    int encoding, MemorySegment language, int channels, int channelMask,
                                    float sampleRate) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendAudioTrack(enabled != 0, trackId, cString(name), encoding, cString(language),
                        channels, channelMask, sampleRate);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("audio_track", t);
            return 0;
        }
    }

    private static int onVideoTrack(MemorySegment user, int enabled, long trackId, MemorySegment name,
                                    int encoding, int width, int height, float frameRate, int hasAlpha) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendVideoTrack(enabled != 0, trackId, cString(name), encoding, width, height,
                        frameRate, hasAlpha != 0);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("video_track", t);
            return 0;
        }
    }

    private static int onSubtitleTrack(MemorySegment user, int enabled, long trackId, MemorySegment name,
                                       int encoding, MemorySegment language) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendSubtitleTrack(enabled != 0, trackId, cString(name), encoding, cString(language));
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("subtitle_track", t);
            return 0;
        }
    }

    private static int onMarker(MemorySegment user, MemorySegment name, double time) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendMarkerEvent(cString(name), time);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("marker", t);
            return 0;
        }
    }

    private static int onBufferProgress(MemorySegment user, double clipDuration, long start, long stop,
                                        long position) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendBufferProgressEvent(clipDuration, start, stop, position);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("buffer_progress", t);
            return 0;
        }
    }

    private static int onDurationUpdate(MemorySegment user, double duration) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendDurationUpdateEvent(duration);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("duration_update", t);
            return 0;
        }
    }

    private static int onAudioSpectrum(MemorySegment user, double timestamp, double duration,
                                       int queryTimestamp) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendAudioSpectrumEvent(timestamp, duration, queryTimestamp != 0);
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("audio_spectrum", t);
            return 0;
        }
    }

    private static int onWarning(MemorySegment user, int warningCode, MemorySegment message) {
        try {
            NativeMediaPlayer player = player(user);
            if (player != null) {
                player.sendWarning(warningCode, cString(message));
            }
            return 1;
        } catch (Throwable t) {
            logUpcallFailure("warning", t);
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stream callback targets (contract section 9). Return conventions are those of
    // JavaInputStreamCallbacks.cpp: an exception yields -2 from read_*, -1 from seek, 0 from the
    // int/bool slots; an unknown id behaves like an exception.
    // ---------------------------------------------------------------------------------------------

    private static int onNeedBuffer(MemorySegment user) {
        try {
            ConnectionHolder holder = holder(user);
            return holder != null && ConnectionHolderBridge.needBuffer(holder) ? 1 : 0;
        } catch (Throwable t) {
            logUpcallFailure("need_buffer", t);
            return 0;
        }
    }

    private static int onIsSeekable(MemorySegment user) {
        try {
            ConnectionHolder holder = holder(user);
            return holder != null && ConnectionHolderBridge.isSeekable(holder) ? 1 : 0;
        } catch (Throwable t) {
            logUpcallFailure("is_seekable", t);
            return 0;
        }
    }

    private static int onIsRandomAccess(MemorySegment user) {
        try {
            ConnectionHolder holder = holder(user);
            return holder != null && ConnectionHolderBridge.isRandomAccess(holder) ? 1 : 0;
        } catch (Throwable t) {
            logUpcallFailure("is_random_access", t);
            return 0;
        }
    }

    private static int onReadNextBlock(MemorySegment user) {
        try {
            ConnectionHolder holder = holder(user);
            return holder != null ? holder.readNextBlock() : -2;
        } catch (Throwable t) {
            logUpcallFailure("read_next_block", t);
            return -2;
        }
    }

    private static int onReadBlock(MemorySegment user, long position, int size) {
        try {
            ConnectionHolder holder = holder(user);
            return holder != null ? ConnectionHolderBridge.readBlock(holder, position, size) : -2;
        } catch (Throwable t) {
            logUpcallFailure("read_block", t);
            return -2;
        }
    }

    /**
     * Copies the staged bytes into {@code dst}. The holder's <em>current</em> buffer is read on every
     * call, as the JNI {@code GetObjectField} did: {@code FileConnectionHolder.readBlock} replaces it and
     * {@code MemoryConnectionHolder} slices it. The copy starts at the buffer's base address regardless
     * of its position, as {@code GetDirectBufferAddress} + {@code memcpy} did.
     */
    @SuppressWarnings("restricted")
    private static void onCopyBlock(MemorySegment user, MemorySegment dst, int size) {
        try {
            ConnectionHolder holder = holder(user);
            if (holder == null || size <= 0 || dst.address() == 0L) {
                return;
            }
            ByteBuffer buffer = holder.getBuffer();
            if (buffer == null) {
                return;
            }
            MemorySegment source = MemorySegment.ofBuffer(buffer.duplicate().clear());
            MemorySegment.copy(source, 0L, dst.reinterpret(size), 0L, size);
        } catch (Throwable t) {
            logUpcallFailure("copy_block", t);
        }
    }

    private static long onSeek(MemorySegment user, long position) {
        try {
            ConnectionHolder holder = holder(user);
            return holder != null ? holder.seek(position) : -1L;
        } catch (Throwable t) {
            logUpcallFailure("seek", t);
            return -1L;
        }
    }

    private static int onProperty(MemorySegment user, int prop, int value) {
        try {
            ConnectionHolder holder = holder(user);
            return holder != null ? ConnectionHolderBridge.property(holder, prop, value) : 0;
        } catch (Throwable t) {
            logUpcallFailure("property", t);
            return 0;
        }
    }

    private static void onCloseConnection(MemorySegment user) {
        try {
            ConnectionHolder holder = holder(user);
            if (holder != null) {
                holder.closeConnection();
            }
        } catch (Throwable t) {
            logUpcallFailure("close_connection", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Log sink target (contract section 6)
    // ---------------------------------------------------------------------------------------------

    private static void onLog(MemorySegment user, int level, MemorySegment message) {
        try {
            String text = cString(message);
            if (text == null) {
                text = "";
            }
            LogObserver observer = logObserver;
            if (observer != null) {
                observer.onMessage(level, text);
            }
            Logger.logMsg(level, text);
        } catch (Throwable t) {
            // The sink is the last resort; there is nothing left to report to.
        }
    }

    /**
     * One {@code jfxm_spectrum_set_bands} handover: the action to run when C hands the pair back, and the
     * two segments C owns until it does. The registry entry for the handover is what keeps the segments -
     * and therefore the memory they point at, however its arena was created - reachable for exactly as
     * long as C may still write through them. Holding them here rather than relying on whatever the
     * caller's action happens to capture keeps that invariant inside the facade: a caller that passes a
     * non-capturing {@code Runnable} is safe.
     */
    private record BandHandover(Runnable onReleased, MemorySegment magnitudes, MemorySegment phases) {
    }

    /**
     * {@code JfxmReleaseFn}: C has dropped its last reference to memory Java handed over and will never
     * read or write it again. The registry entry both names the handover and keeps the memory reachable
     * until this call, so nothing can be collected while C still owns it.
     */
    private static void onRelease(MemorySegment user) {
        try {
            Object handover = lookup(user.address());
            unregister(user.address());
            switch (handover) {
                case BandHandover bands -> bands.onReleased().run();
                case Runnable runnable -> runnable.run();
                case null, default -> { }
            }
        } catch (Throwable t) {
            logUpcallFailure("release", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ABI guard and layout checks (contract section 5)
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns {@code jfxm_abi_version()}.
     *
     * @return the ABI revision of the loaded library
     */
    public static int abiVersion() {
        try {
            return (int) JFXM_ABI_VERSION_MH.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Returns {@code jfxm_sizeof_player_callbacks()}.
     *
     * @return the C {@code sizeof(JfxmPlayerCallbacks)}
     */
    public static int sizeofPlayerCallbacks() {
        try {
            return (int) JFXM_SIZEOF_PLAYER_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Returns {@code jfxm_sizeof_stream_callbacks()}.
     *
     * @return the C {@code sizeof(JfxmStreamCallbacks)}
     */
    public static int sizeofStreamCallbacks() {
        try {
            return (int) JFXM_SIZEOF_STREAM_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Returns {@code jfxm_sizeof_frame_info()}.
     *
     * @return the C {@code sizeof(JfxmFrameInfo)}
     */
    public static int sizeofFrameInfo() {
        try {
            return (int) JFXM_SIZEOF_FRAME_INFO.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Returns {@code jfxm_offsetof_frame_info(field)}.
     *
     * @param field a field index into {@link #FRAME_INFO_FIELDS}
     * @return the C {@code offsetof}, or -1 if the index is out of range
     */
    public static int offsetofFrameInfo(int field) {
        try {
            return (int) JFXM_OFFSETOF_FRAME_INFO.invokeExact(field);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Returns {@code jfxm_event_player_state(pipelineState)}: the {@code NativeMediaPlayer.eventPlayer*}
     * constant the {@code state} callback reports for a {@code CPipeline::PlayerState}. The C dispatcher
     * runs the same mapping code, and hardcodes those constants now that the generated JNI headers are
     * going away, so this is the drift guard between the two sides.
     *
     * @param pipelineState a {@code CPipeline::PlayerState} value, 0 to 7
     * @return the matching {@code eventPlayer*} constant, or -1 for an unknown state
     */
    public static int eventPlayerState(int pipelineState) {
        try {
            return (int) JFXM_EVENT_PLAYER_STATE.invokeExact(pipelineState);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Platform and logging (contract section 6)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code jfxm_platform_init()}: creates the media manager singleton, which runs {@code gst_init_check}
     * and registers the built-in plugins. Idempotent by construction: once the manager exists a further
     * call hands back the same one and returns {@code ERROR_NONE} again without re-running
     * {@code gst_init_check}. A failure is <em>not</em> remembered - a later call retries, exactly as
     * repeated {@code gstInitPlatform} calls did.
     *
     * @return a {@link MediaError} code
     */
    public static int platformInit() {
        try {
            return (int) JFXM_PLATFORM_INIT.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_osx_platform_init()}.
     *
     * @return true if the AVFoundation backend is usable (always false off macOS)
     */
    public static boolean osxPlatformInit() {
        try {
            return (int) JFXM_OSX_PLATFORM_INIT.invokeExact() != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_log_init}: installs the process-wide log sink that forwards to {@link Logger}.
     *
     * @return true when logging is compiled into the library
     */
    public static boolean logInit() {
        try {
            return (int) JFXM_LOG_INIT.invokeExact(LOG_SINK, MemorySegment.NULL) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_log_set_level}.
     *
     * @param level a {@link Logger} level constant
     */
    public static void logSetLevel(int level) {
        try {
            JFXM_LOG_SET_LEVEL.invokeExact(level);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Media and player (contract section 7)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code jfxm_media_create}. Strings are passed as UTF-8; {@code null} becomes {@code NULL}.
     *
     * @param backend {@link #JFXM_BACKEND_GST} or {@link #JFXM_BACKEND_AVF}
     * @param contentType the MIME type
     * @param location the locator's string location
     * @param sizeHint the content length, or -1
     * @param callbacks the stream table, may be {@code null} (AVF file/http sources)
     * @param audioCallbacks the HLS audio stream table, may be {@code null}
     * @param outMedia receives the media handle in element 0 on success, zero otherwise
     * @return a {@link MediaError} code
     */
    public static int mediaCreate(int backend, String contentType, String location, long sizeHint,
                                  CallbackTable callbacks, CallbackTable audioCallbacks, long[] outMedia) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment type = contentType == null ? MemorySegment.NULL : arena.allocateFrom(contentType);
            MemorySegment loc = location == null ? MemorySegment.NULL : arena.allocateFrom(location);
            MemorySegment out = arena.allocate(ADDRESS);
            int rc = (int) JFXM_MEDIA_CREATE.invokeExact(backend, type, loc, sizeHint,
                    tableOf(callbacks), userOf(callbacks), tableOf(audioCallbacks), userOf(audioCallbacks), out);
            outMedia[0] = rc == ERROR_NONE ? out.get(ADDRESS, 0L).address() : 0L;
            return rc;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_media_dispose}: after it returns no callback of any table fires again.
     *
     * @param media the media handle
     */
    public static void mediaDispose(long media) {
        try {
            JFXM_MEDIA_DISPOSE.invokeExact(handle(media));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_init}: creates the backend event dispatcher over a copy of the table.
     *
     * @param media the media handle
     * @param callbacks the player table
     * @return a {@link MediaError} code
     */
    public static int playerInit(long media, CallbackTable callbacks) {
        try {
            return (int) JFXM_PLAYER_INIT.invokeExact(handle(media), callbacks.table(), callbacks.user());
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_audio_equalizer}.
     *
     * @param media the media handle
     * @return the equalizer handle, or zero
     */
    public static long playerGetAudioEqualizer(long media) {
        try {
            return ((MemorySegment) JFXM_PLAYER_GET_AUDIO_EQUALIZER.invokeExact(handle(media))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_audio_spectrum}.
     *
     * @param media the media handle
     * @return the spectrum handle, or zero
     */
    public static long playerGetAudioSpectrum(long media) {
        try {
            return ((MemorySegment) JFXM_PLAYER_GET_AUDIO_SPECTRUM.invokeExact(handle(media))).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_audio_sync_delay}. {@code out[0]} is written only on {@code ERROR_NONE}.
     *
     * @param media the media handle
     * @param out receives the delay in milliseconds
     * @return a {@link MediaError} code
     */
    public static int playerGetAudioSyncDelay(long media, long[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_LONG);
            int rc = (int) JFXM_PLAYER_GET_AUDIO_SYNC_DELAY.invokeExact(handle(media), value);
            if (rc == ERROR_NONE) {
                out[0] = value.get(JAVA_LONG, 0L);
            }
            return rc;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_set_audio_sync_delay}.
     *
     * @param media the media handle
     * @param millis the delay
     * @return a {@link MediaError} code
     */
    public static int playerSetAudioSyncDelay(long media, long millis) {
        try {
            return (int) JFXM_PLAYER_SET_AUDIO_SYNC_DELAY.invokeExact(handle(media), millis);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_play}.
     *
     * @param media the media handle
     * @return a {@link MediaError} code
     */
    public static int playerPlay(long media) {
        try {
            return (int) JFXM_PLAYER_PLAY.invokeExact(handle(media));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_pause}.
     *
     * @param media the media handle
     * @return a {@link MediaError} code
     */
    public static int playerPause(long media) {
        try {
            return (int) JFXM_PLAYER_PAUSE.invokeExact(handle(media));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_stop}.
     *
     * @param media the media handle
     * @return a {@link MediaError} code
     */
    public static int playerStop(long media) {
        try {
            return (int) JFXM_PLAYER_STOP.invokeExact(handle(media));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_finish}.
     *
     * @param media the media handle
     * @return a {@link MediaError} code
     */
    public static int playerFinish(long media) {
        try {
            return (int) JFXM_PLAYER_FINISH.invokeExact(handle(media));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_rate}. {@code out[0]} is written only on {@code ERROR_NONE}.
     *
     * @param media the media handle
     * @param out receives the rate
     * @return a {@link MediaError} code
     */
    public static int playerGetRate(long media, float[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_FLOAT);
            int rc = (int) JFXM_PLAYER_GET_RATE.invokeExact(handle(media), value);
            if (rc == ERROR_NONE) {
                out[0] = value.get(JAVA_FLOAT, 0L);
            }
            return rc;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_set_rate}.
     *
     * @param media the media handle
     * @param rate the rate
     * @return a {@link MediaError} code
     */
    public static int playerSetRate(long media, float rate) {
        try {
            return (int) JFXM_PLAYER_SET_RATE.invokeExact(handle(media), rate);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_presentation_time}. {@code out[0]} is written only on {@code ERROR_NONE}.
     *
     * @param media the media handle
     * @param out receives the time in seconds
     * @return a {@link MediaError} code
     */
    public static int playerGetPresentationTime(long media, double[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_DOUBLE);
            int rc = (int) JFXM_PLAYER_GET_PRESENTATION_TIME.invokeExact(handle(media), value);
            if (rc == ERROR_NONE) {
                out[0] = value.get(JAVA_DOUBLE, 0L);
            }
            return rc;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_volume}. {@code out[0]} is written only on {@code ERROR_NONE}.
     *
     * @param media the media handle
     * @param out receives the volume
     * @return a {@link MediaError} code
     */
    public static int playerGetVolume(long media, float[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_FLOAT);
            int rc = (int) JFXM_PLAYER_GET_VOLUME.invokeExact(handle(media), value);
            if (rc == ERROR_NONE) {
                out[0] = value.get(JAVA_FLOAT, 0L);
            }
            return rc;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_set_volume}.
     *
     * @param media the media handle
     * @param volume the volume
     * @return a {@link MediaError} code
     */
    public static int playerSetVolume(long media, float volume) {
        try {
            return (int) JFXM_PLAYER_SET_VOLUME.invokeExact(handle(media), volume);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_balance}. {@code out[0]} is written only on {@code ERROR_NONE}.
     *
     * @param media the media handle
     * @param out receives the balance
     * @return a {@link MediaError} code
     */
    public static int playerGetBalance(long media, float[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_FLOAT);
            int rc = (int) JFXM_PLAYER_GET_BALANCE.invokeExact(handle(media), value);
            if (rc == ERROR_NONE) {
                out[0] = value.get(JAVA_FLOAT, 0L);
            }
            return rc;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_set_balance}.
     *
     * @param media the media handle
     * @param balance the balance
     * @return a {@link MediaError} code
     */
    public static int playerSetBalance(long media, float balance) {
        try {
            return (int) JFXM_PLAYER_SET_BALANCE.invokeExact(handle(media), balance);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_duration}. {@code out[0]} is written only on {@code ERROR_NONE}; -1.0 means
     * unknown and the callers map it to positive infinity as before.
     *
     * @param media the media handle
     * @param out receives the duration in seconds
     * @return a {@link MediaError} code
     */
    public static int playerGetDuration(long media, double[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_DOUBLE);
            int rc = (int) JFXM_PLAYER_GET_DURATION.invokeExact(handle(media), value);
            if (rc == ERROR_NONE) {
                out[0] = value.get(JAVA_DOUBLE, 0L);
            }
            return rc;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_seek}.
     *
     * @param media the media handle
     * @param seconds the stream time
     * @return a {@link MediaError} code
     */
    public static int playerSeek(long media, double seconds) {
        try {
            return (int) JFXM_PLAYER_SEEK.invokeExact(handle(media), seconds);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_get_mute} (AVF only). {@code out[0]} is written only on {@code ERROR_NONE}.
     *
     * @param media the media handle
     * @param out receives the mute state
     * @return a {@link MediaError} code
     */
    public static int playerGetMute(long media, boolean[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            int rc = (int) JFXM_PLAYER_GET_MUTE.invokeExact(handle(media), value);
            if (rc == ERROR_NONE) {
                out[0] = value.get(JAVA_INT, 0L) != 0;
            }
            return rc;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_player_set_mute} (AVF only).
     *
     * @param media the media handle
     * @param mute the mute state
     * @return a {@link MediaError} code
     */
    public static int playerSetMute(long media, boolean mute) {
        try {
            return (int) JFXM_PLAYER_SET_MUTE.invokeExact(handle(media), mute ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Video frames (contract section 8)
    // ---------------------------------------------------------------------------------------------

    /**
     * The contents of a {@code JfxmFrameInfo}, read once per frame. {@code planeCount} is what the frame
     * reports, unclamped, exactly as {@code nativeGetPlaneCount} returned it; the three arrays hold the
     * first four planes at most, because that is all {@code JfxmFrameInfo} carries. {@code planeData}
     * holds raw addresses owned by the frame.
     *
     * @param timestamp the presentation time
     * @param width the frame width
     * @param height the frame height
     * @param encodedWidth the encoded width
     * @param encodedHeight the encoded height
     * @param format a {@code VideoFormat.FormatTypes} value
     * @param hasAlpha whether the frame has an alpha channel
     * @param planeCount the number of planes the frame reports
     * @param strides the stride of each of the first four planes
     * @param planeSizes the byte size of each of the first four planes
     * @param planeData the address of each of the first four planes
     */
    public record FrameInfo(double timestamp, int width, int height, int encodedWidth, int encodedHeight,
                            int format, boolean hasAlpha, int planeCount, int[] strides, long[] planeSizes,
                            long[] planeData) {

        /** The value every getter of a frame without native info reports: zeros, as the JNI getters did. */
        public static final FrameInfo NONE = new FrameInfo(0.0, 0, 0, 0, 0, 0, false, 0,
                new int[0], new long[0], new long[0]);
    }

    /**
     * {@code jfxm_frame_get_info}.
     *
     * @param frame the frame handle
     * @return the frame's info, or {@link FrameInfo#NONE} if the call failed
     */
    public static FrameInfo frameGetInfo(long frame) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(FRAME_INFO);
            int rc = (int) JFXM_FRAME_GET_INFO.invokeExact(handle(frame), info);
            if (rc != ERROR_NONE) {
                return FrameInfo.NONE;
            }
            int planeCount = info.get(JAVA_INT, OFFSET_PLANE_COUNT);
            int planes = Math.max(0, Math.min(MAX_PLANES, planeCount));
            int[] strides = new int[planes];
            long[] sizes = new long[planes];
            long[] data = new long[planes];
            for (int i = 0; i < planes; i++) {
                strides[i] = info.get(JAVA_INT, OFFSET_STRIDES + i * JAVA_INT.byteSize());
                sizes[i] = info.get(JAVA_LONG, OFFSET_PLANE_SIZE + i * JAVA_LONG.byteSize());
                data[i] = info.get(ADDRESS, OFFSET_PLANE_DATA + i * ADDRESS.byteSize()).address();
            }
            return new FrameInfo(info.get(JAVA_DOUBLE, OFFSET_TIMESTAMP),
                    info.get(JAVA_INT, OFFSET_WIDTH), info.get(JAVA_INT, OFFSET_HEIGHT),
                    info.get(JAVA_INT, OFFSET_ENCODED_WIDTH), info.get(JAVA_INT, OFFSET_ENCODED_HEIGHT),
                    info.get(JAVA_INT, OFFSET_FORMAT), info.get(JAVA_INT, OFFSET_HAS_ALPHA) != 0,
                    planeCount, strides, sizes, data);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Returns a direct, native-order view of one plane of a frame, as {@code NewDirectByteBuffer} did:
     * the buffer has no lifetime of its own; the frame's hold count still governs the memory.
     * <p>
     * Never {@code null}, and never {@code null} in the JNI implementation either. {@code
     * CVideoFrame::GetDataForPlane} bounds-checked the index against {@code MAX_PLANE_COUNT}, not against
     * the frame's own plane count, and handed anything out of range to {@code NewDirectByteBuffer} as
     * {@code (NULL, 0)} - which is a direct buffer of capacity zero, not a null reference. A plane a frame
     * does not have therefore reads as empty rather than as an error, and a caller that walks past
     * {@link FrameInfo#planeCount()} gets an empty buffer instead of a {@code NullPointerException} it
     * would never have seen under JNI.
     *
     * @param info the frame's info
     * @param plane the plane index
     * @return the plane's buffer, or an empty direct buffer if the frame has no such plane
     */
    @SuppressWarnings("restricted")
    public static ByteBuffer planeBuffer(FrameInfo info, int plane) {
        boolean present = plane >= 0 && plane < MAX_PLANES && plane < info.planeData().length
                && info.planeData()[plane] != 0L;
        long address = present ? info.planeData()[plane] : 0L;
        long size = present ? info.planeSizes()[plane] : 0L;
        return MemorySegment.ofAddress(address)
                .reinterpret(size)
                .asByteBuffer()
                .order(ByteOrder.nativeOrder());
    }

    /**
     * {@code jfxm_frame_convert}.
     *
     * @param frame the frame handle
     * @param format the target {@code VideoFormat.FormatTypes} value
     * @return a new frame handle owned by the caller, or zero
     */
    public static long frameConvert(long frame, int format) {
        try {
            return ((MemorySegment) JFXM_FRAME_CONVERT.invokeExact(handle(frame), format)).address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_frame_set_dirty}.
     *
     * @param frame the frame handle
     */
    public static void frameSetDirty(long frame) {
        try {
            JFXM_FRAME_SET_DIRTY.invokeExact(handle(frame));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_frame_dispose}: deletes the frame.
     *
     * @param frame the frame handle
     */
    public static void frameDispose(long frame) {
        try {
            JFXM_FRAME_DISPOSE.invokeExact(handle(frame));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Equalizer, bands, spectrum (contract section 11)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code jfxm_eq_get_enabled}.
     *
     * @param eq the equalizer handle
     * @return the enabled state (false for a zero handle)
     */
    public static boolean eqGetEnabled(long eq) {
        try {
            return (int) JFXM_EQ_GET_ENABLED.invokeExact(handle(eq)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_set_enabled}.
     *
     * @param eq the equalizer handle
     * @param enabled the enabled state
     */
    public static void eqSetEnabled(long eq, boolean enabled) {
        try {
            JFXM_EQ_SET_ENABLED.invokeExact(handle(eq), enabled ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_get_num_bands}.
     *
     * @param eq the equalizer handle
     * @return the band count
     */
    public static int eqGetNumBands(long eq) {
        try {
            return (int) JFXM_EQ_GET_NUM_BANDS.invokeExact(handle(eq));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_add_band}.
     *
     * @param eq the equalizer handle
     * @param centerFrequency the band's center frequency
     * @param bandwidth the band's bandwidth
     * @param gain the band's gain
     * @return the band handle, or zero
     */
    public static long eqAddBand(long eq, double centerFrequency, double bandwidth, double gain) {
        try {
            return ((MemorySegment) JFXM_EQ_ADD_BAND.invokeExact(handle(eq), centerFrequency, bandwidth, gain))
                    .address();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_remove_band}.
     *
     * @param eq the equalizer handle
     * @param centerFrequency the band's center frequency
     * @return true if a band was removed
     */
    public static boolean eqRemoveBand(long eq, double centerFrequency) {
        try {
            return (int) JFXM_EQ_REMOVE_BAND.invokeExact(handle(eq), centerFrequency) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_band_get_center_frequency}.
     *
     * @param band the band handle
     * @return the center frequency
     */
    public static double bandGetCenterFrequency(long band) {
        try {
            return (double) JFXM_EQ_BAND_GET_CENTER_FREQUENCY.invokeExact(handle(band));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_band_set_center_frequency}.
     *
     * @param band the band handle
     * @param hz the center frequency
     */
    public static void bandSetCenterFrequency(long band, double hz) {
        try {
            JFXM_EQ_BAND_SET_CENTER_FREQUENCY.invokeExact(handle(band), hz);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_band_get_bandwidth}.
     *
     * @param band the band handle
     * @return the bandwidth
     */
    public static double bandGetBandwidth(long band) {
        try {
            return (double) JFXM_EQ_BAND_GET_BANDWIDTH.invokeExact(handle(band));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_band_set_bandwidth}.
     *
     * @param band the band handle
     * @param hz the bandwidth
     */
    public static void bandSetBandwidth(long band, double hz) {
        try {
            JFXM_EQ_BAND_SET_BANDWIDTH.invokeExact(handle(band), hz);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_band_get_gain}.
     *
     * @param band the band handle
     * @return the gain
     */
    public static double bandGetGain(long band) {
        try {
            return (double) JFXM_EQ_BAND_GET_GAIN.invokeExact(handle(band));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_eq_band_set_gain}.
     *
     * @param band the band handle
     * @param db the gain
     */
    public static void bandSetGain(long band, double db) {
        try {
            JFXM_EQ_BAND_SET_GAIN.invokeExact(handle(band), db);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_spectrum_get_enabled}.
     *
     * @param spectrum the spectrum handle
     * @return the enabled state
     */
    public static boolean spectrumGetEnabled(long spectrum) {
        try {
            return (int) JFXM_SPECTRUM_GET_ENABLED.invokeExact(handle(spectrum)) != 0;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_spectrum_set_enabled}.
     *
     * @param spectrum the spectrum handle
     * @param enabled the enabled state
     */
    public static void spectrumSetEnabled(long spectrum, boolean enabled) {
        try {
            JFXM_SPECTRUM_SET_ENABLED.invokeExact(handle(spectrum), enabled ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_spectrum_set_bands}: hands C two Java-owned {@code float} arrays of {@code count}
     * elements each. The pair belongs to C from this call until it runs {@code onReleased}, which it does
     * exactly once, on whichever thread drops the last reference to the bands holder - the caller of the
     * next {@code set_bands}, the media dispose, or a spectrum thread that was still writing. Freeing the
     * memory any earlier is a use-after-free, so the action, not the caller, closes the arena.
     * <p>
     * While C owns the pair the facade holds both segments in its registry, beside {@code onReleased}, so
     * the caller does not have to keep them reachable itself: an action that captures nothing is as safe
     * as one that captures the arrays.
     *
     * @param spectrum the spectrum handle
     * @param count the band count
     * @param magnitudes the magnitude array, {@code count} floats
     * @param phases the phase array, {@code count} floats
     * @param onReleased run when C hands the pair back; may be {@code null}, and then C is given no way
     *        to hand the pair back at all and the memory has to outlive the media
     */
    public static void spectrumSetBands(long spectrum, int count, MemorySegment magnitudes,
                                        MemorySegment phases, Runnable onReleased) {
        long id = onReleased == null ? 0L
                : register(new BandHandover(onReleased, magnitudes, phases));
        MemorySegment release = onReleased == null ? MemorySegment.NULL : RELEASE_SINK;
        try {
            JFXM_SPECTRUM_SET_BANDS.invokeExact(handle(spectrum), count, magnitudes, phases,
                    release, MemorySegment.ofAddress(id));
        } catch (Throwable t) {
            unregister(id);
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_spectrum_get_interval}.
     *
     * @param spectrum the spectrum handle
     * @return the interval in seconds
     */
    public static double spectrumGetInterval(long spectrum) {
        try {
            return (double) JFXM_SPECTRUM_GET_INTERVAL.invokeExact(handle(spectrum));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_spectrum_set_interval}.
     *
     * @param spectrum the spectrum handle
     * @param seconds the interval
     */
    public static void spectrumSetInterval(long spectrum, double seconds) {
        try {
            JFXM_SPECTRUM_SET_INTERVAL.invokeExact(handle(spectrum), seconds);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_spectrum_get_threshold}.
     *
     * @param spectrum the spectrum handle
     * @return the sensitivity threshold in dB
     */
    public static int spectrumGetThreshold(long spectrum) {
        try {
            return (int) JFXM_SPECTRUM_GET_THRESHOLD.invokeExact(handle(spectrum));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code jfxm_spectrum_set_threshold}.
     *
     * @param spectrum the spectrum handle
     * @param db the sensitivity threshold
     */
    public static void spectrumSetThreshold(long spectrum, int db) {
        try {
            JFXM_SPECTRUM_SET_THRESHOLD.invokeExact(handle(spectrum), db);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

}
