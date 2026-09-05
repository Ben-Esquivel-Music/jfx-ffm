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
 * Implementation of jfxmedia_api.h (FFM-ABI-CONTRACT.md sections 5-11). Every function mirrors
 * the JNI export it replaces - GstPlatform.cpp, GstMedia.cpp, GstMediaPlayer.cpp,
 * jni/NativeVideoBuffer.cpp, jni/NativeAudioEqualizer.cpp, jni/NativeEqualizerBand.cpp,
 * jni/NativeAudioSpectrum.cpp and jni/com_sun_media_jfxmedia_logging_Logger.cpp - with the same
 * NULL checks and error codes; only the JNIEnv marshalling is gone.
 */

#include <jfxmedia_api.h>

#include <Common/ProductFlags.h>
#include <Common/VSMemory.h>
#include <MediaManagement/Media.h>
#include <MediaManagement/MediaManager.h>
#include <PipelineManagement/Pipeline.h>
#include <PipelineManagement/PipelineOptions.h>
#include <PipelineManagement/VideoFrame.h>
#include <PipelineManagement/AudioEqualizer.h>
#include <PipelineManagement/AudioSpectrum.h>
#include <Locator/Locator.h>
#include <Locator/LocatorStream.h>
#include <jni/Logger.h>
#include <jfxmedia_errors.h>

#include "JfxmMediaHandle.h"
#include "FfiPlayerEventDispatcher.h"
#include "FfiStreamCallbacks.h"
#include "FfiBandsHolder.h"
#include "jfxmedia_avf.h"

#include <stddef.h>
#include <string.h>
#include <new>

using namespace std;

//*************************************************************************************************
//********** ABI version guard and layout checks
//*************************************************************************************************

uint32_t jfxm_abi_version(void)
{
    return JFXM_ABI_VERSION;
}

int32_t jfxm_sizeof_player_callbacks(void)
{
    return (int32_t)sizeof(JfxmPlayerCallbacks);
}

int32_t jfxm_sizeof_stream_callbacks(void)
{
    return (int32_t)sizeof(JfxmStreamCallbacks);
}

int32_t jfxm_sizeof_frame_info(void)
{
    return (int32_t)sizeof(JfxmFrameInfo);
}

int32_t jfxm_event_player_state(int32_t pipeline_state)
{
    // Shared with CFfiPlayerEventDispatcher::SendPlayerStateEvent so the mapping cannot drift.
    return FfiMapPipelineStateToJavaEvent(pipeline_state);
}

int32_t jfxm_audio_track_channel(int32_t channel)
{
    // Reads the very constants CFfiPlayerEventDispatcher::SendAudioTrackEvent ORs into the mask.
    return FfiAudioTrackChannel(channel);
}

int32_t jfxm_log_level(int32_t level)
{
    // The LOGGER_* macros of jni/Logger.h, which are what CLogger::logMsg filters on.
    switch (level) {
    case 0:  return LOGGER_DEBUG;
    case 1:  return LOGGER_INFO;
    case 2:  return LOGGER_WARNING;
    case 3:  return LOGGER_ERROR;
    case 4:  return LOGGER_OFF;
    default: return -1;
    }
}

int32_t jfxm_offsetof_frame_info(int32_t field)
{
    switch (field) {
    case JFXM_FRAME_INFO_TIMESTAMP:      return (int32_t)offsetof(JfxmFrameInfo, timestamp);
    case JFXM_FRAME_INFO_WIDTH:          return (int32_t)offsetof(JfxmFrameInfo, width);
    case JFXM_FRAME_INFO_HEIGHT:         return (int32_t)offsetof(JfxmFrameInfo, height);
    case JFXM_FRAME_INFO_ENCODED_WIDTH:  return (int32_t)offsetof(JfxmFrameInfo, encoded_width);
    case JFXM_FRAME_INFO_ENCODED_HEIGHT: return (int32_t)offsetof(JfxmFrameInfo, encoded_height);
    case JFXM_FRAME_INFO_FORMAT:         return (int32_t)offsetof(JfxmFrameInfo, format);
    case JFXM_FRAME_INFO_HAS_ALPHA:      return (int32_t)offsetof(JfxmFrameInfo, has_alpha);
    case JFXM_FRAME_INFO_PLANE_COUNT:    return (int32_t)offsetof(JfxmFrameInfo, plane_count);
    case JFXM_FRAME_INFO_RESERVED:       return (int32_t)offsetof(JfxmFrameInfo, reserved);
    case JFXM_FRAME_INFO_STRIDES:        return (int32_t)offsetof(JfxmFrameInfo, strides);
    case JFXM_FRAME_INFO_PLANE_SIZE:     return (int32_t)offsetof(JfxmFrameInfo, plane_size);
    case JFXM_FRAME_INFO_PLANE_DATA:     return (int32_t)offsetof(JfxmFrameInfo, plane_data);
    default:                             return -1;
    }
}

//*************************************************************************************************
//********** Library initialisation and logging
//*************************************************************************************************

// No memoisation: CMediaManager::GetInstance() is itself the singleton guard (Singleton<T> creates
// the manager once and returns the same pointer afterwards), so calling through every time is
// idempotent and returns ERROR_NONE again on the success path - exactly what gstInitPlatform did.
// Caching here would additionally have made a transient failure permanent.
int32_t jfxm_platform_init(void)
{
    uint32_t       uErrorCode = ERROR_NONE;
    CMediaManager* pManager = NULL;

#if ENABLE_VISUAL_STUDIO_MEMORY_LEAKS_DETECTION && TARGET_OS_WIN32
    _CrtSetDbgFlag ( _CRTDBG_ALLOC_MEM_DF | _CRTDBG_LEAK_CHECK_DF);
#endif // ENABLE_VISUAL_STUDIO_MEMORY_LEAKS_DETECTION

    LOGGER_LOGMSG(LOGGER_DEBUG, "Initializing GSTPlatform");

    // The manager-level warning listener (CJavaMediaWarningListener) is not created: the
    // CMediaWarningDispatcher that would feed it is never instantiated, so the path is dead.
    uErrorCode = CMediaManager::GetInstance(&pManager);
    if (ERROR_NONE != uErrorCode) {
        return (int32_t)uErrorCode;
    } else if (NULL == pManager) { // Should not happen
        return ERROR_MANAGER_NULL;
    }

    return ERROR_NONE;
}

int32_t jfxm_osx_platform_init(void)
{
#ifdef __APPLE__
    return jfxm_avf_platform_init();
#else
    return 0;
#endif
}

int32_t jfxm_log_init(JfxmLogFn fn, void* user)
{
#if ENABLE_LOGGING
    return CLogger::initSink(fn, user) ? 1 : 0;
#else
    /* Nothing to install, so the call succeeded: a build with logging compiled out is healthy and
     * must not report a logger-init failure. Java_..._Logger_nativeInit returned JNI_TRUE here
     * too. A 0 return therefore means only a genuine failure (native allocation). */
    (void)fn;
    (void)user;
    return 1;
#endif // ENABLE_LOGGING
}

void jfxm_log_set_level(int32_t level)
{
#if ENABLE_LOGGING
    CLogger* pLogger = CLogger::getLogger();
    if (NULL != pLogger) {
        pLogger->setLevel((int)level);
    }
#else
    (void)level;
#endif // ENABLE_LOGGING
}

//*************************************************************************************************
//********** Media and player
//*************************************************************************************************

// GstMedia.cpp InitMedia() without the JNIEnv: Java has already resolved the Locator strings and
// the connection holders; the stream tables stand in for the jobjects.
static int32_t InitGstMedia(const char* content_type, const char* location, int64_t size_hint,
                            const JfxmStreamCallbacks* cb, void* user,
                            const JfxmStreamCallbacks* audio_cb, void* audio_user,
                            CMedia** ppMedia)
{
    CMedia*         pMedia = NULL;
    CMediaManager*  pManager = NULL;
    uint32_t        uErrCode = CMediaManager::GetInstance(&pManager);

    if (ERROR_NONE != uErrCode)
        return (int32_t)uErrCode;

    //***** pre-conditions (GetStringUTFChars returning NULL was ERROR_MEMORY_ALLOCATION)
    if (NULL == content_type)
    {
        return ERROR_MEMORY_ALLOCATION;
    }
    if (NULL == location)
    {
        return ERROR_MEMORY_ALLOCATION;
    }
    if (NULL == pManager)
    {
        return ERROR_MANAGER_NULL;
    }

    //***** Create a new native locator object (a NULL table is the NULL connection holder case)
    CFfiStreamCallbacks* callbacks = new (nothrow) CFfiStreamCallbacks(cb, user);
    if (NULL == callbacks || NULL == cb)
    {
        delete callbacks;
        return ERROR_MEMORY_ALLOCATION;
    }

    CLocatorStream* locator = new (nothrow) CLocatorStream(callbacks, content_type, location, size_hint);
    if (NULL == locator)
    {
        delete callbacks;
        return ERROR_MEMORY_ALLOCATION;
    }

    // Load any additional streams if needed. Java evaluated HLS_PROP_HAS_AUDIO_EXT_STREAM on the
    // main holder and passes the audio table only when it was set (contract section 7).
    CFfiStreamCallbacks* audioStreamCallbacks = NULL;
    if (NULL != audio_cb)
    {
        audioStreamCallbacks = new (nothrow) CFfiStreamCallbacks(audio_cb, audio_user);
        if (NULL == audioStreamCallbacks)
        {
            delete callbacks;
            delete locator;
            return ERROR_MEMORY_ALLOCATION;
        }

        locator->SetAudioCallbacks(audioStreamCallbacks);
    }

    //***** Create the media object
    uErrCode = pManager->CreatePlayer(locator, NULL, &pMedia);

    //***** return
    if (ERROR_NONE == uErrCode)
    {
        if (CMedia::IsValid(pMedia))
        {
            *ppMedia = pMedia;
        }
        else
        {
            uErrCode = ERROR_MEDIA_INVALID;
        }
    }

    // Free locator. CLocatorStream has no destructor and only stores the two adapter pointers, so
    // this does not touch them.
    if (locator != NULL)
        delete locator;

    // Clean up
    if (ERROR_NONE != uErrCode)
    {
        if (NULL != pMedia)
            delete pMedia;

        // Ownership of the adapters passes to the pipeline inside
        // CGstPipelineFactory::CreateSourceElement, which hands each one to the javasource element
        // as the user data of its "close-connection" signal; CGstPipelineFactory::SourceCloseConnection
        // is what deletes it, and javasource only emits that signal on a READY->NULL state change.
        // Nothing reached from CreatePlayer takes an element above GST_STATE_NULL - the first state
        // change is in CGst*Pipeline::Init(), which jfxm_player_init calls much later - so on every
        // failing exit the adapters are still ours and deleting them here cannot double free.
        // Skipping this would leave them holding upcall-stub addresses that
        // GSTMedia.createNativeMedia frees in its finally block, and would make the "nothing is
        // retained by C" promise of jfxmedia_api.h false. close_connection is deliberately not
        // invoked: the Java side closes the connection holders itself on this path.
        delete audioStreamCallbacks;
        delete callbacks;
    }

    return (int32_t)uErrCode;
}

int32_t jfxm_media_create(int32_t backend,
                          const char* content_type, const char* location, int64_t size_hint,
                          const JfxmStreamCallbacks* cb, void* user,
                          const JfxmStreamCallbacks* audio_cb, void* audio_user,
                          void** out_media)
{
    if (NULL == out_media)
        return ERROR_FUNCTION_PARAM_NULL;
    *out_media = NULL;

    if (JFXM_BACKEND_GST == backend)
    {
        CMedia* pMedia = NULL;
        int32_t iRet = InitGstMedia(content_type, location, size_hint, cb, user, audio_cb, audio_user, &pMedia);
        if (ERROR_NONE != iRet)
            return iRet;

        JfxmMedia* pHandle = new (nothrow) JfxmMedia(JFXM_BACKEND_GST);
        if (NULL == pHandle)
        {
            delete pMedia;
            return ERROR_MEMORY_ALLOCATION;
        }

        pHandle->gst = pMedia;
        *out_media = pHandle;
        return ERROR_NONE;
    }

    if (JFXM_BACKEND_AVF == backend)
    {
#ifdef __APPLE__
        // osxCreatePlayer: a missing source string was "Unable to create sourceURIString", and a
        // missing content type on the jar:/jrt: path was "memory allocation failed".
        if (NULL == location)
            return ERROR_MEMORY_ALLOCATION;
        if (NULL != cb && NULL == content_type)
            return ERROR_MEMORY_ALLOCATION;

        JfxmMedia* pHandle = new (nothrow) JfxmMedia(JFXM_BACKEND_AVF);
        if (NULL == pHandle)
            return ERROR_MEMORY_ALLOCATION;

        pHandle->location = location;
        pHandle->content_type = (NULL != content_type) ? content_type : "";
        pHandle->size_hint = size_hint;
        if (NULL != cb)
        {
            pHandle->has_stream = 1;
            pHandle->stream = *cb;
            pHandle->stream_user = user;
        }
        (void)audio_cb;
        (void)audio_user;

        *out_media = pHandle;
        return ERROR_NONE;
#else
        (void)content_type;
        (void)location;
        (void)size_hint;
        (void)cb;
        (void)user;
        (void)audio_cb;
        (void)audio_user;
        return ERROR_NOT_IMPLEMENTED;
#endif
    }

    return ERROR_NOT_IMPLEMENTED;
}

void jfxm_media_dispose(void* media)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    if (NULL == pHandle)
        return;

    if (JFXM_BACKEND_GST == pHandle->backend)
    {
        // gstDispose: ~CMedia deletes the pipeline, which deletes the dispatcher.
        if (NULL != pHandle->gst)
        {
            delete pHandle->gst;
            pHandle->gst = NULL;
        }
    }
#ifdef __APPLE__
    else if (JFXM_BACKEND_AVF == pHandle->backend)
    {
        jfxm_avf_player_dispose(pHandle);
    }
#endif

    delete pHandle;
}

// The NULL checks of every GstMediaPlayer.cpp export, in their order.
static int32_t GetGstPipeline(JfxmMedia* pHandle, CPipeline** ppPipeline)
{
    CMedia* pMedia = (NULL != pHandle) ? pHandle->gst : NULL;
    if (NULL == pMedia)
        return ERROR_MEDIA_NULL;

    CPipeline* pPipeline = (CPipeline*)pMedia->GetPipeline();
    if (NULL == pPipeline)
        return ERROR_PIPELINE_NULL;

    *ppPipeline = pPipeline;
    return ERROR_NONE;
}

//*************************************************************************************************
//********** GStreamer backend: the bodies of the former GstMediaPlayer.cpp exports
//*************************************************************************************************

static int32_t GstPlayerInit(JfxmMedia* media, const JfxmPlayerCallbacks* cb, void* user)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    CFfiPlayerEventDispatcher* pEventDispatcher = new (nothrow) CFfiPlayerEventDispatcher(cb, user);
    if (NULL == pEventDispatcher)
        return ERROR_MEMORY_ALLOCATION;

    pPipeline->SetEventDispatcher(pEventDispatcher);

    return (int32_t)pPipeline->Init();
}

static void* GstPlayerGetAudioEqualizer(JfxmMedia* media)
{
    CMedia* pMedia = (NULL != media) ? media->gst : NULL;
    if (NULL == pMedia) {
        return NULL;
    }
    CPipeline* pPipeline = pMedia->GetPipeline();
    if (NULL == pPipeline) {
        return NULL;
    }
    return (void*)pPipeline->GetAudioEqualizer();
}

static void* GstPlayerGetAudioSpectrum(JfxmMedia* media)
{
    CMedia* pMedia = (NULL != media) ? media->gst : NULL;
    if (NULL == pMedia) {
        return NULL;
    }
    CPipeline* pPipeline = pMedia->GetPipeline();
    if (NULL == pPipeline) {
        return NULL;
    }
    return (void*)pPipeline->GetAudioSpectrum();
}

static int32_t GstPlayerGetAudioSyncDelay(JfxmMedia* media, int64_t* out_millis)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    long lAudioSyncDelay;
    uint32_t uErrCode = pPipeline->GetAudioSyncDelay(&lAudioSyncDelay);
    if (ERROR_NONE != uErrCode)
        return (int32_t)uErrCode;
    if (NULL != out_millis)
        *out_millis = (int64_t)lAudioSyncDelay;

    return ERROR_NONE;
}

static int32_t GstPlayerSetAudioSyncDelay(JfxmMedia* media, int64_t millis)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    // (long) truncation on LLP64 preserved from gstSetAudioSyncDelay.
    return (int32_t)pPipeline->SetAudioSyncDelay((long)millis);
}

static int32_t GstPlayerPlay(JfxmMedia* media)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    return (int32_t)pPipeline->Play();
}

static int32_t GstPlayerPause(JfxmMedia* media)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    return (int32_t)pPipeline->Pause();
}

static int32_t GstPlayerStop(JfxmMedia* media)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    return (int32_t)pPipeline->Stop();
}

static int32_t GstPlayerFinish(JfxmMedia* media)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    return (int32_t)pPipeline->Finish();
}

static int32_t GstPlayerGetRate(JfxmMedia* media, float* out_rate)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    float fRate;
    uint32_t uRetCode = pPipeline->GetRate(&fRate);
    if (ERROR_NONE != uRetCode)
        return (int32_t)uRetCode;
    if (NULL != out_rate)
        *out_rate = fRate;

    return ERROR_NONE;
}

static int32_t GstPlayerSetRate(JfxmMedia* media, float rate)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    return (int32_t)pPipeline->SetRate(rate);
}

static int32_t GstPlayerGetPresentationTime(JfxmMedia* media, double* out_seconds)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    double dPresentationTime;
    uint32_t uRetCode = pPipeline->GetStreamTime(&dPresentationTime);
    if (ERROR_NONE != uRetCode)
        return (int32_t)uRetCode;
    if (NULL != out_seconds)
        *out_seconds = dPresentationTime;

    return ERROR_NONE;
}

static int32_t GstPlayerGetVolume(JfxmMedia* media, float* out_volume)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    float fVolume;
    uint32_t uRetCode = pPipeline->GetVolume(&fVolume);
    if (ERROR_NONE != uRetCode)
        return (int32_t)uRetCode;
    if (NULL != out_volume)
        *out_volume = fVolume;

    return ERROR_NONE;
}

static int32_t GstPlayerSetVolume(JfxmMedia* media, float volume)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    return (int32_t)pPipeline->SetVolume(volume);
}

static int32_t GstPlayerGetBalance(JfxmMedia* media, float* out_balance)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    float fBalance;
    uint32_t uErrCode = pPipeline->GetBalance(&fBalance);
    if (ERROR_NONE != uErrCode)
        return (int32_t)uErrCode;
    if (NULL != out_balance)
        *out_balance = fBalance;

    return ERROR_NONE;
}

static int32_t GstPlayerSetBalance(JfxmMedia* media, float balance)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    return (int32_t)pPipeline->SetBalance(balance);
}

static int32_t GstPlayerGetDuration(JfxmMedia* media, double* out_seconds)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    double dDuration;
    uint32_t uErrCode = pPipeline->GetDuration(&dDuration);
    if (ERROR_NONE != uErrCode)
        return (int32_t)uErrCode;
    if (NULL != out_seconds)
        *out_seconds = dDuration;

    return ERROR_NONE;
}

static int32_t GstPlayerSeek(JfxmMedia* media, double seconds)
{
    CPipeline* pPipeline = NULL;
    int32_t iRet = GetGstPipeline(media, &pPipeline);
    if (ERROR_NONE != iRet)
        return iRet;

    return (int32_t)pPipeline->Seek(seconds);
}

// Mute is implemented in GSTMediaPlayer.java, so the pipeline is never consulted here: a handle
// without a pipeline still answers ERROR_NOT_IMPLEMENTED, exactly as before the dispatch table.
static int32_t GstPlayerGetMute(JfxmMedia* media, int32_t* out_mute)
{
    (void)media;
    (void)out_mute;
    return ERROR_NOT_IMPLEMENTED;
}

static int32_t GstPlayerSetMute(JfxmMedia* media, int32_t mute)
{
    (void)media;
    (void)mute;
    return ERROR_NOT_IMPLEMENTED;
}

//*************************************************************************************************
//********** Backend dispatch
//*************************************************************************************************

typedef int32_t (*JfxmPlayerInitFn)(JfxmMedia* media, const JfxmPlayerCallbacks* cb, void* user);
typedef void*   (*JfxmPlayerGetHandleFn)(JfxmMedia* media);
typedef int32_t (*JfxmPlayerActionFn)(JfxmMedia* media);
typedef int32_t (*JfxmPlayerGetInt64Fn)(JfxmMedia* media, int64_t* out_value);
typedef int32_t (*JfxmPlayerSetInt64Fn)(JfxmMedia* media, int64_t value);
typedef int32_t (*JfxmPlayerGetFloatFn)(JfxmMedia* media, float* out_value);
typedef int32_t (*JfxmPlayerSetFloatFn)(JfxmMedia* media, float value);
typedef int32_t (*JfxmPlayerGetDoubleFn)(JfxmMedia* media, double* out_value);
typedef int32_t (*JfxmPlayerSetDoubleFn)(JfxmMedia* media, double value);
typedef int32_t (*JfxmPlayerGetInt32Fn)(JfxmMedia* media, int32_t* out_value);
typedef int32_t (*JfxmPlayerSetInt32Fn)(JfxmMedia* media, int32_t value);

/*
 * The backend half of every jfxm_player_* entry point, filled in once per backend instead of once
 * per call. The exported functions below resolve the handle to one of these tables and call through
 * it, so the backend test lives in exactly one place (PlayerOps) rather than in a per-function
 * "#ifdef __APPLE__ / IsAvfHandle(media)" preamble that a new entry point can forget. Every slot is
 * a constructor parameter without a default, so a backend that does not implement a newly added
 * operation fails to compile instead of leaving a NULL slot or falling through to GStreamer.
 */
struct JfxmPlayerOps
{
    const JfxmPlayerInitFn      init;
    const JfxmPlayerGetHandleFn get_audio_equalizer;
    const JfxmPlayerGetHandleFn get_audio_spectrum;
    const JfxmPlayerGetInt64Fn  get_audio_sync_delay;
    const JfxmPlayerSetInt64Fn  set_audio_sync_delay;
    const JfxmPlayerActionFn    play;
    const JfxmPlayerActionFn    pause;
    const JfxmPlayerActionFn    stop;
    const JfxmPlayerActionFn    finish;
    const JfxmPlayerGetFloatFn  get_rate;
    const JfxmPlayerSetFloatFn  set_rate;
    const JfxmPlayerGetDoubleFn get_presentation_time;
    const JfxmPlayerGetFloatFn  get_volume;
    const JfxmPlayerSetFloatFn  set_volume;
    const JfxmPlayerGetFloatFn  get_balance;
    const JfxmPlayerSetFloatFn  set_balance;
    const JfxmPlayerGetDoubleFn get_duration;
    const JfxmPlayerSetDoubleFn seek;
    const JfxmPlayerGetInt32Fn  get_mute;
    const JfxmPlayerSetInt32Fn  set_mute;

    JfxmPlayerOps(JfxmPlayerInitFn initFn,
                  JfxmPlayerGetHandleFn getAudioEqualizerFn,
                  JfxmPlayerGetHandleFn getAudioSpectrumFn,
                  JfxmPlayerGetInt64Fn getAudioSyncDelayFn,
                  JfxmPlayerSetInt64Fn setAudioSyncDelayFn,
                  JfxmPlayerActionFn playFn,
                  JfxmPlayerActionFn pauseFn,
                  JfxmPlayerActionFn stopFn,
                  JfxmPlayerActionFn finishFn,
                  JfxmPlayerGetFloatFn getRateFn,
                  JfxmPlayerSetFloatFn setRateFn,
                  JfxmPlayerGetDoubleFn getPresentationTimeFn,
                  JfxmPlayerGetFloatFn getVolumeFn,
                  JfxmPlayerSetFloatFn setVolumeFn,
                  JfxmPlayerGetFloatFn getBalanceFn,
                  JfxmPlayerSetFloatFn setBalanceFn,
                  JfxmPlayerGetDoubleFn getDurationFn,
                  JfxmPlayerSetDoubleFn seekFn,
                  JfxmPlayerGetInt32Fn getMuteFn,
                  JfxmPlayerSetInt32Fn setMuteFn)
      : init(initFn),
        get_audio_equalizer(getAudioEqualizerFn),
        get_audio_spectrum(getAudioSpectrumFn),
        get_audio_sync_delay(getAudioSyncDelayFn),
        set_audio_sync_delay(setAudioSyncDelayFn),
        play(playFn),
        pause(pauseFn),
        stop(stopFn),
        finish(finishFn),
        get_rate(getRateFn),
        set_rate(setRateFn),
        get_presentation_time(getPresentationTimeFn),
        get_volume(getVolumeFn),
        set_volume(setVolumeFn),
        get_balance(getBalanceFn),
        set_balance(setBalanceFn),
        get_duration(getDurationFn),
        seek(seekFn),
        get_mute(getMuteFn),
        set_mute(setMuteFn)
    {
    }
};

static const JfxmPlayerOps GST_PLAYER_OPS(GstPlayerInit,
                                          GstPlayerGetAudioEqualizer,
                                          GstPlayerGetAudioSpectrum,
                                          GstPlayerGetAudioSyncDelay,
                                          GstPlayerSetAudioSyncDelay,
                                          GstPlayerPlay,
                                          GstPlayerPause,
                                          GstPlayerStop,
                                          GstPlayerFinish,
                                          GstPlayerGetRate,
                                          GstPlayerSetRate,
                                          GstPlayerGetPresentationTime,
                                          GstPlayerGetVolume,
                                          GstPlayerSetVolume,
                                          GstPlayerGetBalance,
                                          GstPlayerSetBalance,
                                          GstPlayerGetDuration,
                                          GstPlayerSeek,
                                          GstPlayerGetMute,
                                          GstPlayerSetMute);

#ifdef __APPLE__
static const JfxmPlayerOps AVF_PLAYER_OPS(jfxm_avf_player_init,
                                          jfxm_avf_player_get_audio_equalizer,
                                          jfxm_avf_player_get_audio_spectrum,
                                          jfxm_avf_player_get_audio_sync_delay,
                                          jfxm_avf_player_set_audio_sync_delay,
                                          jfxm_avf_player_play,
                                          jfxm_avf_player_pause,
                                          jfxm_avf_player_stop,
                                          jfxm_avf_player_finish,
                                          jfxm_avf_player_get_rate,
                                          jfxm_avf_player_set_rate,
                                          jfxm_avf_player_get_presentation_time,
                                          jfxm_avf_player_get_volume,
                                          jfxm_avf_player_set_volume,
                                          jfxm_avf_player_get_balance,
                                          jfxm_avf_player_set_balance,
                                          jfxm_avf_player_get_duration,
                                          jfxm_avf_player_seek,
                                          jfxm_avf_player_get_mute,
                                          jfxm_avf_player_set_mute);
#endif

// The one backend test. NULL means "no handle": every forwarder then answers ERROR_MEDIA_NULL (and
// the two handle getters NULL), which is what GetGstPipeline used to answer on their behalf and
// what get/set_mute already checked for themselves. A handle carrying any backend value other than
// JFXM_BACKEND_AVF - impossible from jfxm_media_create, and JFXM_BACKEND_AVF cannot be created in a
// non-Apple build - gets the GStreamer table, whose NULL CMedia yields ERROR_MEDIA_NULL as before.
static const JfxmPlayerOps* PlayerOps(JfxmMedia* pHandle)
{
    if (NULL == pHandle)
        return NULL;
#ifdef __APPLE__
    if (JFXM_BACKEND_AVF == pHandle->backend)
        return &AVF_PLAYER_OPS;
#endif
    return &GST_PLAYER_OPS;
}

//*************************************************************************************************
//********** The exported player entry points (backend agnostic)
//*************************************************************************************************

int32_t jfxm_player_init(void* media, const JfxmPlayerCallbacks* cb, void* user)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->init(pHandle, cb, user) : ERROR_MEDIA_NULL;
}

void* jfxm_player_get_audio_equalizer(void* media)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_audio_equalizer(pHandle) : NULL;
}

void* jfxm_player_get_audio_spectrum(void* media)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_audio_spectrum(pHandle) : NULL;
}

int32_t jfxm_player_get_audio_sync_delay(void* media, int64_t* out_millis)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_audio_sync_delay(pHandle, out_millis) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_set_audio_sync_delay(void* media, int64_t millis)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->set_audio_sync_delay(pHandle, millis) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_play(void* media)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->play(pHandle) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_pause(void* media)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->pause(pHandle) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_stop(void* media)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->stop(pHandle) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_finish(void* media)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->finish(pHandle) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_get_rate(void* media, float* out_rate)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_rate(pHandle, out_rate) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_set_rate(void* media, float rate)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->set_rate(pHandle, rate) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_get_presentation_time(void* media, double* out_seconds)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_presentation_time(pHandle, out_seconds) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_get_volume(void* media, float* out_volume)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_volume(pHandle, out_volume) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_set_volume(void* media, float volume)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->set_volume(pHandle, volume) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_get_balance(void* media, float* out_balance)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_balance(pHandle, out_balance) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_set_balance(void* media, float balance)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->set_balance(pHandle, balance) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_get_duration(void* media, double* out_seconds)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_duration(pHandle, out_seconds) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_seek(void* media, double seconds)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->seek(pHandle, seconds) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_get_mute(void* media, int32_t* out_mute)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->get_mute(pHandle, out_mute) : ERROR_MEDIA_NULL;
}

int32_t jfxm_player_set_mute(void* media, int32_t mute)
{
    JfxmMedia* pHandle = (JfxmMedia*)media;
    const JfxmPlayerOps* pOps = PlayerOps(pHandle);
    return (NULL != pOps) ? pOps->set_mute(pHandle, mute) : ERROR_MEDIA_NULL;
}
//*************************************************************************************************
//********** Video frames (jni/NativeVideoBuffer.cpp)
//*************************************************************************************************

int32_t jfxm_frame_get_info(void* frame, JfxmFrameInfo* out)
{
    CVideoFrame* pFrame = (CVideoFrame*)frame;
    if (NULL == pFrame || NULL == out)
        return ERROR_FUNCTION_PARAM_NULL;

    memset(out, 0, sizeof(*out));

    out->timestamp = pFrame->GetTime();
    out->width = (int32_t)pFrame->GetWidth();
    out->height = (int32_t)pFrame->GetHeight();
    out->encoded_width = (int32_t)pFrame->GetEncodedWidth();
    out->encoded_height = (int32_t)pFrame->GetEncodedHeight();
    // CVideoFrame types match the Java VideoFormat native types, so just pass it along
    out->format = (int32_t)pFrame->GetType();
    out->has_alpha = pFrame->HasAlpha() ? 1 : 0;

    unsigned int uPlaneCount = pFrame->GetPlaneCount();
    out->plane_count = (int32_t)uPlaneCount;
    for (unsigned int ii = 0; ii < uPlaneCount && ii < 4; ii++) {
        out->strides[ii] = (int32_t)pFrame->GetStrideForPlane(ii);
        out->plane_size[ii] = (int64_t)pFrame->GetSizeForPlane(ii);
        out->plane_data[ii] = pFrame->GetDataForPlane(ii);
    }

    return ERROR_NONE;
}

void* jfxm_frame_convert(void* frame, int32_t format)
{
    CVideoFrame* pFrame = (CVideoFrame*)frame;
    if (pFrame) {
        return (void*)pFrame->ConvertToFormat((CVideoFrame::FrameType)format);
    }
    return NULL;
}

void jfxm_frame_set_dirty(void* frame)
{
    CVideoFrame* pFrame = (CVideoFrame*)frame;
    if (pFrame) {
        pFrame->SetFrameDirty(true);
    }
}

void jfxm_frame_dispose(void* frame)
{
    CVideoFrame* pFrame = (CVideoFrame*)frame;
    if (pFrame) {
        delete pFrame;
    }
}

//*************************************************************************************************
//********** Equalizer (jni/NativeAudioEqualizer.cpp)
//*************************************************************************************************

int32_t jfxm_eq_get_enabled(void* eq)
{
    CAudioEqualizer* pEqualizer = (CAudioEqualizer*)eq;
    return (NULL != pEqualizer && pEqualizer->IsEnabled()) ? 1 : 0;
}

void jfxm_eq_set_enabled(void* eq, int32_t enabled)
{
    CAudioEqualizer* pEqualizer = (CAudioEqualizer*)eq;
    if (NULL != pEqualizer)
        pEqualizer->SetEnabled(enabled != 0);
}

int32_t jfxm_eq_get_num_bands(void* eq)
{
    CAudioEqualizer* pEqualizer = (CAudioEqualizer*)eq;
    return (NULL != pEqualizer) ? (int32_t)pEqualizer->GetNumBands() : 0;
}

void* jfxm_eq_add_band(void* eq, double center_frequency, double bandwidth, double gain)
{
    CAudioEqualizer* pEqualizer = (CAudioEqualizer*)eq;
    if (NULL != pEqualizer) {
        // Java constructs the NativeEqualizerBand around the returned handle.
        return (void*)pEqualizer->AddBand(center_frequency, bandwidth, gain);
    }
    return NULL;
}

int32_t jfxm_eq_remove_band(void* eq, double center_frequency)
{
    CAudioEqualizer* pEqualizer = (CAudioEqualizer*)eq;
    return (NULL != pEqualizer && pEqualizer->RemoveBand(center_frequency)) ? 1 : 0;
}

//*************************************************************************************************
//********** Equalizer band (jni/NativeEqualizerBand.cpp: the band is never NULL-checked there)
//*************************************************************************************************

double jfxm_eq_band_get_center_frequency(void* band)
{
    CEqualizerBand* pBand = (CEqualizerBand*)band;
    return pBand->GetCenterFrequency();
}

void jfxm_eq_band_set_center_frequency(void* band, double hz)
{
    CEqualizerBand* pBand = (CEqualizerBand*)band;
    pBand->SetCenterFrequency(hz);
}

double jfxm_eq_band_get_bandwidth(void* band)
{
    CEqualizerBand* pBand = (CEqualizerBand*)band;
    return pBand->GetBandwidth();
}

void jfxm_eq_band_set_bandwidth(void* band, double hz)
{
    CEqualizerBand* pBand = (CEqualizerBand*)band;
    pBand->SetBandwidth(hz);
}

double jfxm_eq_band_get_gain(void* band)
{
    CEqualizerBand* pBand = (CEqualizerBand*)band;
    return pBand->GetGain();
}

void jfxm_eq_band_set_gain(void* band, double db)
{
    CEqualizerBand* pBand = (CEqualizerBand*)band;
    pBand->SetGain(db);
}

//*************************************************************************************************
//********** Spectrum (jni/NativeAudioSpectrum.cpp)
//*************************************************************************************************

int32_t jfxm_spectrum_get_enabled(void* spectrum)
{
    CAudioSpectrum* pSpectrum = (CAudioSpectrum*)spectrum;
    return (NULL != pSpectrum && pSpectrum->IsEnabled()) ? 1 : 0;
}

void jfxm_spectrum_set_enabled(void* spectrum, int32_t enabled)
{
    CAudioSpectrum* pSpectrum = (CAudioSpectrum*)spectrum;
    if (pSpectrum != NULL)
        pSpectrum->SetEnabled(enabled != 0);
}

void jfxm_spectrum_set_bands(void* spectrum, int32_t count, float* magnitudes, float* phases,
                             JfxmReleaseFn release, void* release_user)
{
    CAudioSpectrum* pSpectrum = (CAudioSpectrum*)spectrum;

    // The band count and the two buffers are as much a precondition as the handle every other
    // entry point checks, and nothing in the ABI obliges a caller to pre-screen them: a pair of
    // `count` floats each is what the header promises, so a NULL buffer or a count that cannot
    // describe one is not a pair at all. Left unchecked, a NULL buffer built a holder whose
    // UpdateBands can never copy (CFfiBandsHolder::UpdateBands writes only when both sides are
    // non-NULL) yet still owed a release, and a negative count reached
    // g_object_set(m_pSpectrum, "bands", count, NULL) on the gstspectrum element, whose property is
    // unsigned. Rejecting them is the void-returning form of the ERROR_FUNCTION_PARAM_NULL the
    // int32_t entry points return: the call becomes the no-op a NULL spectrum already is. The pair
    // is still handed straight back, because `release` runs exactly once per call on every path
    // (jfxmedia_api.h), and a rejected call retires nothing - the holder installed by an earlier
    // call keeps its pair, exactly as NativeAudioSpectrum.setBandCount requires.
    // No upper bound is imposed: any positive count is a promise by the caller that the two buffers
    // hold that many floats, which C cannot check and must not second-guess.
    if (count <= 0 || NULL == magnitudes || NULL == phases) {
        LOGGER_LOGMSG(LOGGER_WARNING, "jfxm_spectrum_set_bands: invalid band count or buffer");
        if (NULL != release) {
            release(release_user);
        }
        return;
    }

    CFfiBandsHolder* pHolder = new (nothrow) CFfiBandsHolder((int)count, magnitudes, phases,
                                                             release, release_user);
    if (pHolder == NULL) {
        // The pair never reached a holder, so hand it straight back: release runs exactly once
        // per call on every path (jfxmedia_api.h).
        if (NULL != release) {
            release(release_user);
        }
        return;
    }

    if (pSpectrum != NULL) {
        // SetBands retains the holder if it keeps it and releases the one it held before; the
        // initial reference stays this call's and is dropped below (AudioSpectrum.h). The two
        // implementations disagreed about that once - CGstAudioSpectrum consumed this reference
        // while AVFAudioSpectrumUnit added one of its own - which left every pair on the AVF path
        // pinned at one reference for ever, so its release never ran and Java was never told the
        // pair was dead.
        pSpectrum->SetBands((int)count, pHolder);
    }

    // Whatever the spectrum kept, it holds a reference of its own on; this drops the one the call
    // was born with. Where nothing retained the holder - a NULL spectrum, which nativeSetBands
    // simply leaked - the count reaches zero here and the destructor runs release exactly once,
    // which is the only way Java learns the pair is dead. Otherwise release runs from whichever
    // holder loses its last reference later, possibly on a spectrum thread.
    CBandsHolder::ReleaseRef(pHolder);
}

double jfxm_spectrum_get_interval(void* spectrum)
{
    CAudioSpectrum* pSpectrum = (CAudioSpectrum*)spectrum;
    return (NULL != pSpectrum) ? pSpectrum->GetInterval() : 0.0;
}

void jfxm_spectrum_set_interval(void* spectrum, double seconds)
{
    CAudioSpectrum* pSpectrum = (CAudioSpectrum*)spectrum;
    if (pSpectrum != NULL)
        pSpectrum->SetInterval(seconds);
}

int32_t jfxm_spectrum_get_threshold(void* spectrum)
{
    CAudioSpectrum* pSpectrum = (CAudioSpectrum*)spectrum;
    return (NULL != pSpectrum) ? (int32_t)pSpectrum->GetThreshold() : 0;
}

void jfxm_spectrum_set_threshold(void* spectrum, int32_t db)
{
    CAudioSpectrum* pSpectrum = (CAudioSpectrum*)spectrum;
    if (pSpectrum != NULL)
        pSpectrum->SetThreshold((int)db);
}
