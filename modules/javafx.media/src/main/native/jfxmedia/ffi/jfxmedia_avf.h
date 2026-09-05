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

#ifndef _JFXMEDIA_AVF_H_
#define _JFXMEDIA_AVF_H_

#ifdef __APPLE__

#include <stdint.h>
#include <jfxmedia_api.h>

struct JfxmMedia;

/*
 * Internal (not exported) AVFoundation half of the jfxm_* ABI, implemented in
 * platform/osx/OSXPlatform.mm and platform/osx/OSXMediaPlayer.mm and called only from
 * ffi/jfxmedia_api.cpp when a handle's backend is JFXM_BACKEND_AVF. Return codes and NULL handling
 * follow FFM-ABI-CONTRACT.md section 7: ERROR_MEDIA_NULL for a NULL handle, ERROR_PIPELINE_NULL when
 * the handle has no player (init not run or failed), otherwise the value the former JNI export
 * produced with ERROR_NONE.
 */
#if defined(__GNUC__) || defined(__clang__)
#  define JFXM_AVF_INTERNAL __attribute__((visibility("hidden")))
#else
#  define JFXM_AVF_INTERNAL
#endif

#ifdef __cplusplus
extern "C" {
#endif

JFXM_AVF_INTERNAL int32_t jfxm_avf_platform_init(void);

JFXM_AVF_INTERNAL int32_t jfxm_avf_player_init(struct JfxmMedia* media, const JfxmPlayerCallbacks* cb, void* user);
JFXM_AVF_INTERNAL void    jfxm_avf_player_dispose(struct JfxmMedia* media);

JFXM_AVF_INTERNAL void*   jfxm_avf_player_get_audio_equalizer(struct JfxmMedia* media);
JFXM_AVF_INTERNAL void*   jfxm_avf_player_get_audio_spectrum(struct JfxmMedia* media);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_get_audio_sync_delay(struct JfxmMedia* media, int64_t* out_millis);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_set_audio_sync_delay(struct JfxmMedia* media, int64_t millis);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_play(struct JfxmMedia* media);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_pause(struct JfxmMedia* media);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_stop(struct JfxmMedia* media);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_finish(struct JfxmMedia* media);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_get_rate(struct JfxmMedia* media, float* out_rate);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_set_rate(struct JfxmMedia* media, float rate);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_get_presentation_time(struct JfxmMedia* media, double* out_seconds);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_get_mute(struct JfxmMedia* media, int32_t* out_mute);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_set_mute(struct JfxmMedia* media, int32_t mute);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_get_volume(struct JfxmMedia* media, float* out_volume);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_set_volume(struct JfxmMedia* media, float volume);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_get_balance(struct JfxmMedia* media, float* out_balance);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_set_balance(struct JfxmMedia* media, float balance);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_get_duration(struct JfxmMedia* media, double* out_seconds);
JFXM_AVF_INTERNAL int32_t jfxm_avf_player_seek(struct JfxmMedia* media, double seconds);

#ifdef __cplusplus
}
#endif

#endif // __APPLE__

#endif // _JFXMEDIA_AVF_H_
