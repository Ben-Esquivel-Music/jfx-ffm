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

/*
 * jfxmedia_api.h - the flat C ABI of the jfxmedia library.
 *
 * This is the only surface com.sun.media.jfxmediaimpl.JfxMediaNative binds. It knows nothing
 * about the JVM: every function takes and returns <stdint.h> scalars, opaque void* handles or
 * NUL-terminated UTF-8 strings, and every former Java callback is a slot in a table of function
 * pointers that receives the opaque void* user Java handed in. Error codes are the MediaError
 * values generated into jfxmedia_errors.h; no exception crosses the boundary in either direction.
 *
 * The design contract is modules/javafx.media/FFM-ABI-CONTRACT.md; the symbol set is identical on
 * every platform (functions that only make sense on one backend return ERROR_NOT_IMPLEMENTED
 * elsewhere).
 */

#ifndef _JFXMEDIA_API_H_
#define _JFXMEDIA_API_H_

#include <stdint.h>

#if defined(_WIN32)
#  define JFXM_EXPORT __declspec(dllexport)
#else
#  define JFXM_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------------------------------------
 * ABI version guard and layout checks (contract section 5)
 * ---------------------------------------------------------------------------------------------- */

/*
 * 2: added jfxm_audio_track_channel and jfxm_log_level. Adding a symbol bumps the version even
 * though older callers would not miss it, because the binding works the other way round: Java
 * resolves every symbol of this header eagerly, so a library built before those two exist fails
 * with "missing native symbol" instead of the clean mismatch jfxm_abi_version is here to report.
 */
#define JFXM_ABI_VERSION 2u

/* Field indices accepted by jfxm_offsetof_frame_info(); they follow JfxmFrameInfo's field order. */
enum {
    JFXM_FRAME_INFO_TIMESTAMP      = 0,
    JFXM_FRAME_INFO_WIDTH          = 1,
    JFXM_FRAME_INFO_HEIGHT         = 2,
    JFXM_FRAME_INFO_ENCODED_WIDTH  = 3,
    JFXM_FRAME_INFO_ENCODED_HEIGHT = 4,
    JFXM_FRAME_INFO_FORMAT         = 5,
    JFXM_FRAME_INFO_HAS_ALPHA      = 6,
    JFXM_FRAME_INFO_PLANE_COUNT    = 7,
    JFXM_FRAME_INFO_RESERVED       = 8,
    JFXM_FRAME_INFO_STRIDES        = 9,
    JFXM_FRAME_INFO_PLANE_SIZE     = 10,
    JFXM_FRAME_INFO_PLANE_DATA     = 11,
    JFXM_FRAME_INFO_FIELD_COUNT    = 12
};

/* Returns JFXM_ABI_VERSION of the library that was loaded. Java refuses to bind any other value. */
JFXM_EXPORT uint32_t jfxm_abi_version(void);
/* sizeof(JfxmPlayerCallbacks) / sizeof(JfxmStreamCallbacks) / sizeof(JfxmFrameInfo) as compiled. */
JFXM_EXPORT int32_t  jfxm_sizeof_player_callbacks(void);
JFXM_EXPORT int32_t  jfxm_sizeof_stream_callbacks(void);
JFXM_EXPORT int32_t  jfxm_sizeof_frame_info(void);
/* offsetof() of the JfxmFrameInfo field with the given index (enum above); -1 if out of range. */
JFXM_EXPORT int32_t  jfxm_offsetof_frame_info(int32_t field);
/*
 * Maps a CPipeline::PlayerState (0..7) to the NativeMediaPlayer.eventPlayer* constant that
 * JfxmPlayerCallbacks.state reports; returns -1 for an unknown state. It is the same mapping code
 * the dispatcher runs, exported so the Java binding test can prove the two sides still agree now
 * that the generated JNI headers are gone. Pure function, any thread.
 */
JFXM_EXPORT int32_t  jfxm_event_player_state(int32_t pipeline_state);
/*
 * The com.sun.media.jfxmedia.track.AudioTrack channel bit with the given index (0 UNKNOWN,
 * 1 FRONT_LEFT, 2 FRONT_RIGHT, 3 FRONT_CENTER, 4 REAR_LEFT, 5 REAR_RIGHT, 6 REAR_CENTER); -1 for
 * any other index. These are the constants the dispatcher ORs into the mask of the audio_track
 * slot, exported for the same reason as jfxm_event_player_state: the generated JNI header
 * com_sun_media_jfxmedia_track_AudioTrack.h no longer proves the C copy still matches the Java
 * original, so a binding test does. Pure function, any thread.
 */
JFXM_EXPORT int32_t  jfxm_audio_track_channel(int32_t channel);
/*
 * The com.sun.media.jfxmedia.logging.Logger level with the given index (0 DEBUG, 1 INFO,
 * 2 WARNING, 3 ERROR, 4 OFF); -1 for any other index. These are the levels this library filters
 * on and reports through JfxmLogFn, so the same drift guard applies now that
 * com_sun_media_jfxmedia_logging_Logger.h is gone. Pure function, any thread.
 */
JFXM_EXPORT int32_t  jfxm_log_level(int32_t level);

/* ------------------------------------------------------------------------------------------------
 * Library initialisation and logging (contract section 6)
 * ---------------------------------------------------------------------------------------------- */

/*
 * Replaces JNI_OnLoad + Java_..._GSTPlatform_gstInitPlatform: creates the media manager, which
 * runs gst_init_check and registers the built-in plugins. Idempotent by construction, because the
 * media manager is a singleton: once it exists, a further call hands back the same manager and
 * returns ERROR_NONE again without re-running gst_init_check. A failure is NOT remembered - a
 * later call retries, exactly as repeated gstInitPlatform calls did. Any thread, though the
 * singleton itself is not internally synchronised, so Java keeps making the first call from one
 * thread. Return = MediaError code.
 */
JFXM_EXPORT int32_t jfxm_platform_init(void);

/*
 * Replaces Java_..._OSXPlatform_osxPlatformInit. macOS: applies the ATS info-dictionary
 * workaround, honours JFXMEDIA_AVF, probes objc_getClass("AVFMediaPlayer") and returns 1 if the
 * AVF backend is usable, 0 otherwise. Other platforms: returns 0 and does nothing. Any thread.
 */
JFXM_EXPORT int32_t jfxm_osx_platform_init(void);

/*
 * The single native log sink. level uses the Java Logger constants (ERROR 4, WARNING 3, INFO 2,
 * DEBUG 1, OFF Integer.MAX_VALUE). Called from any native thread (GLib main loop, GStreamer
 * streaming threads, AVFoundation queues, the Java caller thread); the message pointer is valid
 * only for the duration of the call and the target must not block or throw.
 */
typedef void (*JfxmLogFn)(void* user, int32_t level, const char* message);

/*
 * Replaces Java_..._logging_Logger_nativeInit. Installs the sink once; a later call replaces the
 * pointer (tests). Returns 1 when logging is compiled in (ENABLE_LOGGING), else 0. fn may be NULL
 * to detach the sink; the library never dereferences user.
 */
JFXM_EXPORT int32_t jfxm_log_init(JfxmLogFn fn, void* user);
/* Replaces Java_..._logging_Logger_nativeSetNativeLevel: messages below level are dropped in C. */
JFXM_EXPORT void    jfxm_log_set_level(int32_t level);

/* ------------------------------------------------------------------------------------------------
 * Stream callbacks (contract section 9): Java InputStream -> native reads
 * ---------------------------------------------------------------------------------------------- */

/*
 * One table per ConnectionHolder, copied by value by jfxm_media_create. Every slot may be NULL;
 * a NULL slot behaves like a Java target that threw: need_buffer/is_seekable/is_random_access/
 * property return 0, read_next_block/read_block return -2, seek returns -1, copy_block and
 * close_connection do nothing.
 *
 * Calling threads (GStreamer backend / AVFoundation backend):
 *   need_buffer, is_seekable, is_random_access, property(2,3,6): the Java caller thread inside
 *       jfxm_media_create (GST) or jfxm_player_init (AVF).
 *   read_next_block, read_block, copy_block, seek, property(1,4,5): the javasource task thread
 *       (push mode) or the pulling element's streaming thread (GST); the playerLoaderQueue serial
 *       dispatch queue under the player lock (AVF). These MAY block on Java I/O.
 *   close_connection: the thread driving READY->NULL, under the element lock (GST); the dispose
 *       caller under the player lock (AVF). It is the last call; C deletes its adapter right after.
 * The table and user must stay valid until close_connection has run AND jfxm_media_dispose has
 * returned. The one exception is a failed jfxm_media_create: close_connection never runs there, C
 * destroys the adapter before returning, and the table may be released as soon as it does. Never
 * use Linker.Option.critical for anything that reaches these slots.
 */
typedef struct JfxmStreamCallbacks {
    int32_t (*need_buffer)(void* user);                                /* 1 => wrap in (hls)progressbuffer */
    int32_t (*is_seekable)(void* user);
    int32_t (*is_random_access)(void* user);                           /* 1 => pull mode, read_block used */
    int32_t (*read_next_block)(void* user);                            /* >0 bytes staged; -1 EOS; -2 error */
    int32_t (*read_block)(void* user, int64_t position, int32_t size); /* size <= 65536 (GST), <= 1 MiB (AVF) */
    void    (*copy_block)(void* user, void* dst, int32_t size);        /* copy the staged bytes into dst[0..size) */
    int64_t (*seek)(void* user, int64_t position);                     /* new position or -1; HLS: seconds*1000 */
    int32_t (*property)(void* user, int32_t prop, int32_t value);      /* HLSConnectionHolder.HLS_PROP_* 1..6 */
    void    (*close_connection)(void* user);                           /* last call; C deletes its adapter after it */
} JfxmStreamCallbacks;

/* ------------------------------------------------------------------------------------------------
 * Player callbacks (contract section 10): replaces CJavaPlayerEventDispatcher
 * ---------------------------------------------------------------------------------------------- */

/*
 * One table per player, copied by value in jfxm_player_init. Every slot may be NULL (treated as
 * "delivered"). Return 1 = delivered, 0 = the Java target threw (the C keeps the same false-return
 * handling it had for a JNI exception). Strings are UTF-8 and valid only during the call.
 * Payloads are the exact arguments of the existing NativeMediaPlayer.send* methods.
 *
 * Calling threads (GStreamer backend / AVFoundation backend). None of them is the FX thread and no
 * target may block:
 *   media_error, halt, warning: GLib main loop thread (bus watch), demuxer/parser/decoder streaming
 *       threads, or the Java caller thread (pause) / KVO thread, main dispatch queue.
 *   state: main loop thread, or synchronously on the Java caller thread inside jfxm_player_pause /
 *       Java caller thread (play/pause/stop/finish), main queue (end of media), KVO thread.
 *   new_frame, frame_size: appsink streaming thread / CVDisplayLink thread.
 *   audio_track, video_track, subtitle_track: decoder/parser streaming threads (subtitle: never
 *       on GST) / KVO thread.
 *   duration_update, buffer_progress, marker: main loop thread (marker: never) / KVO thread
 *       (buffer_progress, marker: never).
 *   audio_spectrum: main loop thread / the MTAudioProcessingTap real-time audio thread with the
 *       band lock held - the Java target only enqueues.
 * The table and user must stay valid until jfxm_media_dispose has returned; after that no slot
 * fires again. The frame handed to new_frame is owned by Java from that point on and must be
 * released with jfxm_frame_dispose (NativeVideoBuffer's hold count decides when).
 */
typedef struct JfxmPlayerCallbacks {
    int32_t (*media_error)(void* user, int32_t error_code);
    int32_t (*halt)(void* user, const char* message, double time);
    int32_t (*state)(void* user, int32_t state, double present_time);   /* NativeMediaPlayer.eventPlayer* 100..107 */
    int32_t (*new_frame)(void* user, void* frame);                       /* NativeVideoBuffer wraps the frame */
    int32_t (*frame_size)(void* user, int32_t width, int32_t height);
    int32_t (*audio_track)(void* user, int32_t enabled, int64_t track_id, const char* name,
                           int32_t encoding, const char* language, int32_t channels, int32_t channel_mask,
                           float sample_rate);
    int32_t (*video_track)(void* user, int32_t enabled, int64_t track_id, const char* name,
                           int32_t encoding, int32_t width, int32_t height, float frame_rate, int32_t has_alpha);
    int32_t (*subtitle_track)(void* user, int32_t enabled, int64_t track_id, const char* name,
                              int32_t encoding, const char* language);
    int32_t (*marker)(void* user, const char* name, double time);        /* no caller today; kept for completeness */
    int32_t (*buffer_progress)(void* user, double clip_duration, int64_t start, int64_t stop, int64_t position);
    int32_t (*duration_update)(void* user, double duration);
    int32_t (*audio_spectrum)(void* user, double timestamp, double duration, int32_t query_timestamp);
    int32_t (*warning)(void* user, int32_t warning_code, const char* message);
} JfxmPlayerCallbacks;

/* ------------------------------------------------------------------------------------------------
 * Media and player (contract section 7)
 * ---------------------------------------------------------------------------------------------- */

enum { JFXM_BACKEND_GST = 0, JFXM_BACKEND_AVF = 1 };

/*
 * Replaces Java_..._GSTMedia_gstInitNativeMedia (+ the three CLocator JNI statics) and the media
 * half of Java_..._OSXMediaPlayer_osxCreatePlayer. Java resolves the Locator calls itself, on the
 * same thread and in the same order as the C did:
 *   location = locator.getStringLocation();
 *   holder   = locator.createConnectionHolder();               (GST always; AVF only for jar:/jrt:)
 *   audio    = holder.property(HLS_PROP_HAS_AUDIO_EXT_STREAM, 0) != 0
 *              ? locator.getAudioStreamConnectionHolder(holder) : null;   (GST only)
 * Tables are copied by value; the function pointers and user values must stay valid until
 * close_connection has run AND jfxm_media_dispose has returned. cb may be NULL (AVF file/http).
 * On any non-zero return *out_media is NULL and nothing is retained by C: the stream adapters
 * built from the tables are destroyed before this function returns, and close_connection is NOT
 * called on that path, so the caller both closes its own connection holders and is free to release
 * the tables as soon as the call returns. Called on the Java thread constructing the media; the GST
 * backend invokes need_buffer/is_seekable/is_random_access/property synchronously from inside this
 * call. backend = JFXM_BACKEND_AVF on a non-Apple platform returns ERROR_NOT_IMPLEMENTED.
 */
JFXM_EXPORT int32_t jfxm_media_create(int32_t backend,
                                      const char* content_type, const char* location, int64_t size_hint,
                                      const JfxmStreamCallbacks* cb, void* user,
                                      const JfxmStreamCallbacks* audio_cb, void* audio_user,
                                      void** out_media);

/*
 * Replaces Java_..._GSTMedia_gstDispose and the teardown half of Java_..._OSXMediaPlayer_osxDispose.
 * Tears the pipeline / AVPlayer down (joining the streaming threads), deletes the event dispatcher
 * and the stream adapters and frees the handle. After it returns no callback of any table fires
 * again; Java then unregisters and closes its arenas. NULL is a no-op. Any thread, once.
 */
JFXM_EXPORT void    jfxm_media_dispose(void* media);

/*
 * Replaces Java_..._GSTMediaPlayer_gstInitPlayer and the player half of osxCreatePlayer. Creates the
 * backend event dispatcher over a by-value copy of *cb and initialises the pipeline / AVPlayer.
 * Return = MediaError code (AVF: the six former ThrowJavaException sites map to their codes).
 * On failure the media handle stays alive and C retains nothing of the callback table; the Java
 * caller disposes it. Called on the Java thread constructing the player.
 */
JFXM_EXPORT int32_t jfxm_player_init(void* media, const JfxmPlayerCallbacks* cb, void* user);

/*
 * The 18 GSTMediaPlayer / 20 OSXMediaPlayer forwarding natives. media = jfxm_media handle.
 * Return = MediaError code (ERROR_MEDIA_NULL 257 for a NULL media, ERROR_PIPELINE_NULL 769 for a
 * media without pipeline, preserved from GstMediaPlayer.cpp); out-params are written only on
 * ERROR_NONE. Any Java thread; the calls take pipeline / ObjC locks and may block on preroll, so
 * never bind them with Linker.Option.critical. The equalizer and spectrum handles are owned by the
 * pipeline and stay valid until jfxm_media_dispose.
 */
JFXM_EXPORT void*   jfxm_player_get_audio_equalizer(void* media);     /* NULL if media is NULL */
JFXM_EXPORT void*   jfxm_player_get_audio_spectrum(void* media);
JFXM_EXPORT int32_t jfxm_player_get_audio_sync_delay(void* media, int64_t* out_millis);
JFXM_EXPORT int32_t jfxm_player_set_audio_sync_delay(void* media, int64_t millis);   /* AVF: stored, never applied */
JFXM_EXPORT int32_t jfxm_player_play(void* media);
JFXM_EXPORT int32_t jfxm_player_pause(void* media);
JFXM_EXPORT int32_t jfxm_player_stop(void* media);
JFXM_EXPORT int32_t jfxm_player_finish(void* media);
JFXM_EXPORT int32_t jfxm_player_get_rate(void* media, float* out_rate);
JFXM_EXPORT int32_t jfxm_player_set_rate(void* media, float rate);
JFXM_EXPORT int32_t jfxm_player_get_presentation_time(void* media, double* out_seconds);
JFXM_EXPORT int32_t jfxm_player_get_volume(void* media, float* out_volume);
JFXM_EXPORT int32_t jfxm_player_set_volume(void* media, float volume);
JFXM_EXPORT int32_t jfxm_player_get_balance(void* media, float* out_balance);
JFXM_EXPORT int32_t jfxm_player_set_balance(void* media, float balance);
/* -1.0 = unknown; Java maps it to +Infinity as today. */
JFXM_EXPORT int32_t jfxm_player_get_duration(void* media, double* out_seconds);
JFXM_EXPORT int32_t jfxm_player_seek(void* media, double seconds);
/* AVF only; the GST backend returns ERROR_NOT_IMPLEMENTED and GSTMediaPlayer never calls them (mute stays in Java). */
JFXM_EXPORT int32_t jfxm_player_get_mute(void* media, int32_t* out_mute);
JFXM_EXPORT int32_t jfxm_player_set_mute(void* media, int32_t mute);

/* ------------------------------------------------------------------------------------------------
 * Video frames (contract section 8): replaces the 13 NativeVideoBuffer natives
 * ---------------------------------------------------------------------------------------------- */

typedef struct JfxmFrameInfo {          /* field index for jfxm_offsetof_frame_info */
    double  timestamp;                  /* 0  GetTime() */
    int32_t width;                      /* 1 */
    int32_t height;                     /* 2 */
    int32_t encoded_width;              /* 3 */
    int32_t encoded_height;             /* 4 */
    int32_t format;                     /* 5  CVideoFrame::FrameType == VideoFormat.FormatTypes (ARGB 1, BGRA_PRE 2,
                                         *    YCbCr_420p 100, YCbCr_422 101) */
    int32_t has_alpha;                  /* 6 */
    int32_t plane_count;                /* 7  1..4 */
    int32_t reserved;                   /* 8  keeps the pointers 8-aligned; always 0 */
    int32_t strides[4];                 /* 9  GetStrideForPlane(i), 0 beyond plane_count */
    int64_t plane_size[4];              /* 10 GetSizeForPlane(i) */
    void*   plane_data[4];              /* 11 GetDataForPlane(i); memory owned by the frame */
} JfxmFrameInfo;                        /* sizeof == 120 on every LP64/LLP64 platform */

/*
 * Fills *out from the frame's getters (planes >= plane_count are zeroed). Called once per frame on
 * the delivering thread; the plane memory stays valid until jfxm_frame_dispose. Returns ERROR_NONE,
 * or ERROR_FUNCTION_PARAM_NULL when frame or out is NULL.
 */
JFXM_EXPORT int32_t jfxm_frame_get_info(void* frame, JfxmFrameInfo* out);
/* New frame in the requested format or NULL, exactly nativeConvertToFormat. Java owns the result. */
JFXM_EXPORT void*   jfxm_frame_convert(void* frame, int32_t format);
/* nativeSetDirty: marks the frame so the pipeline re-uploads it. */
JFXM_EXPORT void    jfxm_frame_set_dirty(void* frame);
/* nativeDisposeBuffer: deletes the frame (and its planes). NULL is a no-op. Any thread, once. */
JFXM_EXPORT void    jfxm_frame_dispose(void* frame);

/* ------------------------------------------------------------------------------------------------
 * Equalizer, bands, spectrum (contract section 11)
 * ---------------------------------------------------------------------------------------------- */

/* NativeAudioEqualizer (5). eq = handle from jfxm_player_get_audio_equalizer; NULL eq => 0 / no-op, as the JNI code. */
JFXM_EXPORT int32_t jfxm_eq_get_enabled(void* eq);
JFXM_EXPORT void    jfxm_eq_set_enabled(void* eq, int32_t enabled);
JFXM_EXPORT int32_t jfxm_eq_get_num_bands(void* eq);
/* Band handle or NULL; Java constructs NativeEqualizerBand. The band is owned by the equalizer. */
JFXM_EXPORT void*   jfxm_eq_add_band(void* eq, double center_frequency, double bandwidth, double gain);
JFXM_EXPORT int32_t jfxm_eq_remove_band(void* eq, double center_frequency);

/* NativeEqualizerBand (6). band = handle from jfxm_eq_add_band; must not be NULL (the JNI code dereferenced it). */
JFXM_EXPORT double  jfxm_eq_band_get_center_frequency(void* band);
JFXM_EXPORT void    jfxm_eq_band_set_center_frequency(void* band, double hz);
JFXM_EXPORT double  jfxm_eq_band_get_bandwidth(void* band);
JFXM_EXPORT void    jfxm_eq_band_set_bandwidth(void* band, double hz);
JFXM_EXPORT double  jfxm_eq_band_get_gain(void* band);
JFXM_EXPORT void    jfxm_eq_band_set_gain(void* band, double db);

/*
 * Tells Java that C has dropped its last reference to memory Java handed over - today only the
 * magnitudes/phases pair of jfxm_spectrum_set_bands - and that C will never read or write that
 * memory again once this call returns. It is the point at which Java may free or reuse it.
 *
 * Called exactly once per handover, with the `user` value supplied alongside the memory, on
 * whichever thread happens to drop the last reference: a GStreamer MainLoop / spectrum thread, the
 * application thread that called jfxm_spectrum_set_bands again or disposed the media, or - on
 * AVFoundation - the thread that tears the audio tap down. No lock of C's is held across it. The
 * Java target must nevertheless be thread-safe, must not block and must not throw: a spectrum
 * thread that is still writing when the pair is superseded runs it on its way out.
 */
typedef void (*JfxmReleaseFn)(void* user);

/*
 * NativeAudioSpectrum (7). spectrum = handle from jfxm_player_get_audio_spectrum; NULL => 0 / no-op.
 * set_bands replaces CJavaBandsHolder: magnitudes/phases point at two Java-allocated float arrays
 * of `count` elements each; C writes them in place from the spectrum thread (GST MainLoop / AVF
 * audio tap) before firing the audio_spectrum callback.
 *
 * The pair belongs to C until C says otherwise, exactly as CJavaBandsHolder held a NewGlobalRef on
 * the two float[]s: the holder that owns the pair is reference counted, so a spectrum thread
 * already inside UpdateBands keeps writing through the pair after a newer jfxm_spectrum_set_bands
 * call has returned. `release` is how C hands the memory back - it runs exactly once per
 * jfxm_spectrum_set_bands call (including when spectrum is NULL, and when the call fails), on the
 * thread that dropped the last reference, and after it returns C never touches that pair again.
 * Freeing or reusing the pair any earlier is a use-after-free. `release` may be NULL, in which
 * case nothing is called and the memory must outlive the media; `release_user` is opaque to C.
 */
JFXM_EXPORT int32_t jfxm_spectrum_get_enabled(void* spectrum);
JFXM_EXPORT void    jfxm_spectrum_set_enabled(void* spectrum, int32_t enabled);
JFXM_EXPORT void    jfxm_spectrum_set_bands(void* spectrum, int32_t count,
                                            float* magnitudes, float* phases,
                                            JfxmReleaseFn release, void* release_user);
JFXM_EXPORT double  jfxm_spectrum_get_interval(void* spectrum);
JFXM_EXPORT void    jfxm_spectrum_set_interval(void* spectrum, double seconds);
JFXM_EXPORT int32_t jfxm_spectrum_get_threshold(void* spectrum);
JFXM_EXPORT void    jfxm_spectrum_set_threshold(void* spectrum, int32_t db);

#ifdef __cplusplus
}
#endif

#endif /* _JFXMEDIA_API_H_ */
