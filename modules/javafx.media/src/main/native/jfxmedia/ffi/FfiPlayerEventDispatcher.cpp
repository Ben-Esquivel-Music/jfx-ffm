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

#include "FfiPlayerEventDispatcher.h"

#include <PipelineManagement/Pipeline.h>
#include <Common/VSMemory.h>

#include <string.h>

// com.sun.media.jfxmediaimpl.NativeMediaPlayer.eventPlayer* (the values javac -h generates into
// com_sun_media_jfxmediaimpl_NativeMediaPlayer.h); kept here so this file needs no JNI header.
static const int32_t EVENT_PLAYER_UNKNOWN  = 100;
static const int32_t EVENT_PLAYER_READY    = 101;
static const int32_t EVENT_PLAYER_PLAYING  = 102;
static const int32_t EVENT_PLAYER_PAUSED   = 103;
static const int32_t EVENT_PLAYER_STOPPED  = 104;
static const int32_t EVENT_PLAYER_STALLED  = 105;
static const int32_t EVENT_PLAYER_FINISHED = 106;
static const int32_t EVENT_PLAYER_ERROR    = 107;

// com.sun.media.jfxmedia.track.AudioTrack channel mask bits (com_sun_media_jfxmedia_track_AudioTrack.h).
static const int32_t JAVA_AUDIO_TRACK_UNKNOWN      = 0;
static const int32_t JAVA_AUDIO_TRACK_FRONT_LEFT   = 1;
static const int32_t JAVA_AUDIO_TRACK_FRONT_RIGHT  = 2;
static const int32_t JAVA_AUDIO_TRACK_FRONT_CENTER = 4;
static const int32_t JAVA_AUDIO_TRACK_REAR_LEFT    = 8;
static const int32_t JAVA_AUDIO_TRACK_REAR_RIGHT   = 16;
static const int32_t JAVA_AUDIO_TRACK_REAR_CENTER  = 32;

CFfiPlayerEventDispatcher::CFfiPlayerEventDispatcher(const JfxmPlayerCallbacks* pCallbacks, void* pUser)
    : m_pUser(pUser)
{
    if (NULL != pCallbacks) {
        m_Callbacks = *pCallbacks;
    } else {
        memset(&m_Callbacks, 0, sizeof(m_Callbacks));
    }
}

CFfiPlayerEventDispatcher::~CFfiPlayerEventDispatcher()
{
    // Nothing is retained: the table was copied by value and `user` is an opaque Java-side id.
    memset(&m_Callbacks, 0, sizeof(m_Callbacks));
}

void CFfiPlayerEventDispatcher::Warning(int warningCode, const char* warningMessage)
{
    // CJavaPlayerEventDispatcher::Warning dropped the event when the message was NULL.
    if (NULL == warningMessage) {
        return;
    }
    if (NULL != m_Callbacks.warning) {
        (void)m_Callbacks.warning(m_pUser, (int32_t)warningCode, warningMessage);
    }
}

bool CFfiPlayerEventDispatcher::SendPlayerMediaErrorEvent(int errorCode)
{
    if (NULL == m_Callbacks.media_error) {
        return true;
    }
    return m_Callbacks.media_error(m_pUser, (int32_t)errorCode) != 0;
}

bool CFfiPlayerEventDispatcher::SendPlayerHaltEvent(const char* message, double time)
{
    // NewStringUTF(NULL) returned NULL, so the JNI dispatcher reported failure for a NULL message.
    if (NULL == message) {
        return false;
    }
    if (NULL == m_Callbacks.halt) {
        return true;
    }
    return m_Callbacks.halt(m_pUser, message, time) != 0;
}

int32_t FfiMapPipelineStateToJavaEvent(int32_t pipelineState)
{
    switch (pipelineState) {
    case CPipeline::Unknown:  return EVENT_PLAYER_UNKNOWN;
    case CPipeline::Ready:    return EVENT_PLAYER_READY;
    case CPipeline::Playing:  return EVENT_PLAYER_PLAYING;
    case CPipeline::Paused:   return EVENT_PLAYER_PAUSED;
    case CPipeline::Stopped:  return EVENT_PLAYER_STOPPED;
    case CPipeline::Stalled:  return EVENT_PLAYER_STALLED;
    case CPipeline::Finished: return EVENT_PLAYER_FINISHED;
    case CPipeline::Error:    return EVENT_PLAYER_ERROR;
    default:                  return -1;
    }
}

bool CFfiPlayerEventDispatcher::SendPlayerStateEvent(int newState, double presentTime)
{
    // Same mapping code jfxm_event_player_state exports, so the two cannot drift.
    int32_t newJavaState = FfiMapPipelineStateToJavaEvent((int32_t)newState);
    if (newJavaState < 0) {
        // CJavaPlayerEventDispatcher reported failure for a state it did not know.
        return false;
    }

    if (NULL == m_Callbacks.state) {
        return true;
    }
    return m_Callbacks.state(m_pUser, newJavaState, presentTime) != 0;
}

bool CFfiPlayerEventDispatcher::SendNewFrameEvent(CVideoFrame* pVideoFrame)
{
    if (NULL == m_Callbacks.new_frame) {
        return true;
    }
    // The Java target creates the NativeVideoBuffer wrapper and owns the frame from here on.
    return m_Callbacks.new_frame(m_pUser, (void*)pVideoFrame) != 0;
}

bool CFfiPlayerEventDispatcher::SendFrameSizeChangedEvent(int width, int height)
{
    if (NULL == m_Callbacks.frame_size) {
        return true;
    }
    return m_Callbacks.frame_size(m_pUser, (int32_t)width, (int32_t)height) != 0;
}

bool CFfiPlayerEventDispatcher::SendAudioTrackEvent(CAudioTrack* pTrack)
{
    if (NULL == m_Callbacks.audio_track) {
        return true;
    }

    string name = pTrack->GetName();
    string language = pTrack->GetLanguage();

    // Translate channel mask bits from native values to Java values.
    int nativeChannelMask = pTrack->GetChannelMask();
    int32_t javaChannelMask = 0;
    if (nativeChannelMask & CAudioTrack::UNKNOWN)
        javaChannelMask |= JAVA_AUDIO_TRACK_UNKNOWN;
    if (nativeChannelMask & CAudioTrack::FRONT_LEFT)
        javaChannelMask |= JAVA_AUDIO_TRACK_FRONT_LEFT;
    if (nativeChannelMask & CAudioTrack::FRONT_RIGHT)
        javaChannelMask |= JAVA_AUDIO_TRACK_FRONT_RIGHT;
    if (nativeChannelMask & CAudioTrack::FRONT_CENTER)
        javaChannelMask |= JAVA_AUDIO_TRACK_FRONT_CENTER;
    if (nativeChannelMask & CAudioTrack::REAR_LEFT)
        javaChannelMask |= JAVA_AUDIO_TRACK_REAR_LEFT;
    if (nativeChannelMask & CAudioTrack::REAR_RIGHT)
        javaChannelMask |= JAVA_AUDIO_TRACK_REAR_RIGHT;
    if (nativeChannelMask & CAudioTrack::REAR_CENTER)
        javaChannelMask |= JAVA_AUDIO_TRACK_REAR_CENTER;

    return m_Callbacks.audio_track(m_pUser,
                                   pTrack->isEnabled() ? 1 : 0,
                                   (int64_t)pTrack->GetTrackID(),
                                   name.c_str(),
                                   (int32_t)pTrack->GetEncoding(),
                                   language.c_str(),
                                   (int32_t)pTrack->GetNumChannels(),
                                   javaChannelMask,
                                   pTrack->GetSampleRate()) != 0;
}

bool CFfiPlayerEventDispatcher::SendVideoTrackEvent(CVideoTrack* pTrack)
{
    if (NULL == m_Callbacks.video_track) {
        return true;
    }

    string name = pTrack->GetName();

    return m_Callbacks.video_track(m_pUser,
                                   pTrack->isEnabled() ? 1 : 0,
                                   (int64_t)pTrack->GetTrackID(),
                                   name.c_str(),
                                   (int32_t)pTrack->GetEncoding(),
                                   (int32_t)pTrack->GetWidth(),
                                   (int32_t)pTrack->GetHeight(),
                                   pTrack->GetFrameRate(),
                                   pTrack->HasAlphaChannel() ? 1 : 0) != 0;
}

bool CFfiPlayerEventDispatcher::SendSubtitleTrackEvent(CSubtitleTrack* pTrack)
{
    if (NULL == m_Callbacks.subtitle_track) {
        return true;
    }

    string name = pTrack->GetName();
    string language = pTrack->GetLanguage();

    return m_Callbacks.subtitle_track(m_pUser,
                                      pTrack->isEnabled() ? 1 : 0,
                                      (int64_t)pTrack->GetTrackID(),
                                      name.c_str(),
                                      (int32_t)pTrack->GetEncoding(),
                                      language.c_str()) != 0;
}

bool CFfiPlayerEventDispatcher::SendMarkerEvent(string name, double time)
{
    if (NULL == m_Callbacks.marker) {
        return true;
    }
    return m_Callbacks.marker(m_pUser, name.c_str(), time) != 0;
}

bool CFfiPlayerEventDispatcher::SendBufferProgressEvent(double clipDuration, int64_t start, int64_t stop,
                                                        int64_t position)
{
    if (NULL == m_Callbacks.buffer_progress) {
        return true;
    }
    return m_Callbacks.buffer_progress(m_pUser, clipDuration, start, stop, position) != 0;
}

bool CFfiPlayerEventDispatcher::SendDurationUpdateEvent(double time)
{
    if (NULL == m_Callbacks.duration_update) {
        return true;
    }
    return m_Callbacks.duration_update(m_pUser, time) != 0;
}

bool CFfiPlayerEventDispatcher::SendAudioSpectrumEvent(double time, double duration, bool queryTimestamp)
{
    if (NULL == m_Callbacks.audio_spectrum) {
        return true;
    }
    return m_Callbacks.audio_spectrum(m_pUser, time, duration, queryTimestamp ? 1 : 0) != 0;
}
