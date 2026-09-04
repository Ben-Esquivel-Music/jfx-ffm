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

#ifndef _FFI_PLAYER_EVENT_DISPATCHER_H_
#define _FFI_PLAYER_EVENT_DISPATCHER_H_

#include <jfxmedia_api.h>

#include <PipelineManagement/AudioTrack.h>
#include <PipelineManagement/VideoTrack.h>
#include <PipelineManagement/SubtitleTrack.h>
#include <PipelineManagement/PlayerEventDispatcher.h>
#include <PipelineManagement/VideoFrame.h>

using namespace std;

/*
 * The one place the CPipeline::PlayerState -> NativeMediaPlayer.eventPlayer* mapping lives. The
 * dispatcher uses it for every state event and jfxm_event_player_state (jfxmedia_api.h) exports it,
 * so a Java binding test can assert the two sides still agree now that the generated JNI headers
 * are gone. Returns -1 for a state this build does not know.
 */
int32_t FfiMapPipelineStateToJavaEvent(int32_t pipelineState);

/*
 * The com.sun.media.jfxmedia.track.AudioTrack channel bit this build copies, selected by index:
 * 0 UNKNOWN, 1 FRONT_LEFT, 2 FRONT_RIGHT, 3 FRONT_CENTER, 4 REAR_LEFT, 5 REAR_RIGHT,
 * 6 REAR_CENTER; -1 for any other index. It reads the same file-static constants
 * SendAudioTrackEvent ORs into the Java channel mask, so the copies cannot drift unnoticed:
 * jfxm_audio_track_channel (jfxmedia_api.h) exports it for the Java binding test, which is the
 * only guard left now that the generated JNI header com_sun_media_jfxmedia_track_AudioTrack.h is
 * gone. An accessor rather than a move of the constants into this header, so the dispatcher stays
 * the single definition site and nothing else can start using them by including it. Pure function,
 * any thread.
 */
int32_t FfiAudioTrackChannel(int32_t index);

/*
 * CPlayerEventDispatcher over a JfxmPlayerCallbacks table (FFM-ABI-CONTRACT.md section 10).
 * Replaces CJavaPlayerEventDispatcher with the same semantics: the pipeline state is mapped to the
 * NativeMediaPlayer.eventPlayer* constants, the audio channel mask is remapped to the Java
 * AudioTrack bits, a bool result is true when the slot returned nonzero, and a NULL slot counts as
 * delivered. The table is copied by value; strings handed to the slots are UTF-8 and valid only for
 * the duration of the call. Owned and deleted by the pipeline (CPipeline::~CPipeline), or by the
 * AVF handle on macOS.
 */
class CFfiPlayerEventDispatcher : public CPlayerEventDispatcher
{
public:
    CFfiPlayerEventDispatcher(const JfxmPlayerCallbacks* pCallbacks, void* pUser);
    virtual ~CFfiPlayerEventDispatcher();

    virtual bool SendPlayerMediaErrorEvent(int errorCode);
    virtual bool SendPlayerHaltEvent(const char* message, double msgTime);
    virtual bool SendPlayerStateEvent(int newState, double presentTime);
    virtual bool SendNewFrameEvent(CVideoFrame* pVideoFrame);
    virtual bool SendFrameSizeChangedEvent(int width, int height);
    virtual bool SendAudioTrackEvent(CAudioTrack* pTrack);
    virtual bool SendVideoTrackEvent(CVideoTrack* pTrack);
    virtual bool SendMarkerEvent(string name, double time);
    virtual bool SendBufferProgressEvent(double clipDuration, int64_t start, int64_t stop, int64_t position);
    virtual bool SendDurationUpdateEvent(double time);
    virtual bool SendAudioSpectrumEvent(double time, double duration, bool queryTimestamp);
    virtual void Warning(int warningCode, const char* warningMessage);
    // Declared last to match CPlayerEventDispatcher, whose slot order is ABI for libjfxmedia_avf.
    virtual bool SendSubtitleTrackEvent(CSubtitleTrack* pTrack);

private:
    JfxmPlayerCallbacks m_Callbacks;
    void*               m_pUser;
};

#endif // _FFI_PLAYER_EVENT_DISPATCHER_H_
