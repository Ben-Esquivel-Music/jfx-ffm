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

#include "Pipeline.h"
#include <Common/ProductFlags.h>
#include <Common/VSMemory.h>
#include <gst/gst.h>
#include <MediaManagement/MediaManager.h>


//*************************************************************************************************
//********** class CPipeline
//*************************************************************************************************
CPipeline::CPipeline(CPipelineOptions* pOptions)
:   m_pEventDispatcher(NULL),
    m_PlayerState(Unknown),
    m_PlayerPendingState(Unknown),
    m_pOptions(pOptions),
    m_bHasAudio(false),
    m_bHasVideo(false),
    m_bAudioInitDone(false),
    m_bVideoInitDone(false),
    m_bStaticPipeline(true),
    m_bDynamicElementsReady(false),
    m_bAudioSinkReady(false),
    m_bVideoSinkReady(false)
{
}

CPipeline::~CPipeline()
{
    if (NULL != m_pOptions)
        delete m_pOptions;

    Dispose();

    if (NULL != m_pEventDispatcher)
        delete m_pEventDispatcher;
}

bool CPipeline::SetEventDispatcher(CPlayerEventDispatcher* pEventDispatcher)
{
    // One dispatcher per pipeline, installed once. This used to overwrite whatever was already
    // there, which leaked the old dispatcher - the JNI gstInitPlayer it was ported from did the
    // same, so this is not migration drift, but it is worth more under FFM: the abandoned
    // CFfiPlayerEventDispatcher holds a by-value copy of thirteen upcall stub addresses, and an
    // object nobody can reach is an object nobody can prove is idle, which is exactly what closing
    // the shared Arena that owns those stubs requires.
    //
    // Refusing is the fix rather than deleting the old one. By the time a second install could
    // happen the first dispatcher is live: CGstAudioPlaybackPipeline::Init has attached the bus
    // watch and started the main loop, so a GStreamer bus or streaming thread can be inside one of
    // its Send*Event calls at any moment, and nothing here synchronises with those threads.
    // Deleting it would turn a leak into a use after free on a foreign thread. Freeing the
    // caller's new dispatcher instead is provably safe, because it has been handed to nobody, so
    // that is what the caller does with the false.
    //
    // This mirrors the AVF backend, where jfxm_avf_player_init has always rejected a second call
    // on the same media with ERROR_MEDIA_CREATION.
    if (NULL != m_pEventDispatcher)
        return false;

    m_pEventDispatcher = pEventDispatcher;
    return true;
}

uint32_t CPipeline::Init()
{
    return ERROR_NONE;
}

uint32_t CPipeline::PostBuildInit()
{
    return ERROR_NONE;
}

void CPipeline::Dispose()
{
}

uint32_t CPipeline::Play()
{
    return ERROR_NONE;
}

uint32_t CPipeline::Stop()
{
    return ERROR_NONE;
}

uint32_t CPipeline::Pause()
{
    return ERROR_NONE;
}

uint32_t CPipeline::Finish()
{
    return ERROR_NONE;
}

uint32_t CPipeline::Seek(double dSeekTime)
{
    return ERROR_NONE;
}

uint32_t CPipeline::GetDuration(double *pdDuration)
{
    if (NULL == pdDuration)
        return ERROR_FUNCTION_PARAM_NULL;

    *pdDuration = 0.0;

    return ERROR_NONE;
}

uint32_t CPipeline::GetStreamTime(double* pdStreamTime)
{
    if (NULL == pdStreamTime)
        return ERROR_FUNCTION_PARAM_NULL;

    *pdStreamTime = 0.0;

    return ERROR_NONE;
}

uint32_t CPipeline::SetRate(float fRate)
{
    return ERROR_NONE;
}

uint32_t CPipeline::GetRate(float* pfRate)
{
    if (NULL == pfRate)
        return ERROR_FUNCTION_PARAM_NULL;

    *pfRate = 0.0F;

    return ERROR_NONE;
}

uint32_t CPipeline::SetVolume(float fVolume)
{
    return ERROR_NONE;
}

uint32_t CPipeline::GetVolume(float* pfVolume)
{
    if (NULL == pfVolume)
        return ERROR_FUNCTION_PARAM_NULL;

    *pfVolume = 0.5F;

    return ERROR_NONE;
}

uint32_t CPipeline::SetBalance(float fBalance)
{
    return ERROR_NONE;
}

uint32_t CPipeline::GetBalance(float* pfBalance)
{
    if (NULL == pfBalance)
        return ERROR_FUNCTION_PARAM_NULL;

    *pfBalance = 0.0F;

    return ERROR_NONE;
}

uint32_t CPipeline::SetAudioSyncDelay(long lMillis)
{
    return ERROR_NONE;
}

uint32_t CPipeline::GetAudioSyncDelay(long* plMillis)
{
    if (NULL == plMillis)
        return ERROR_FUNCTION_PARAM_NULL;

    *plMillis = 0L;

    return ERROR_NONE;
}

CAudioEqualizer* CPipeline::GetAudioEqualizer()
{
    return NULL;
}

CAudioSpectrum* CPipeline::GetAudioSpectrum()
{
    return NULL;
}
