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

#include "Logger.h"

#include <Common/VSMemory.h>
#include <Utils/JfxCriticalSection.h>

#if ENABLE_LOGGING

CLogger::LSingleton CLogger::s_Singleton;

/*
 * jfxm_log_init and jfxm_log_set_level write the sink and the level from the Java caller thread
 * while any native thread - the GLib main loop, a GStreamer streaming thread, an AVFoundation
 * queue - is inside logMsg reading them. Without this lock logMsg tested m_sinkFn and then loaded
 * it a second time to call it, so a jfxm_log_init(NULL, NULL) landing in between dereferenced
 * NULL, and a sink swap could pair one sink's function pointer with the other's user value.
 *
 * One process-wide lock rather than a member, because Singleton<CLogger>::GetInstance is itself
 * unsynchronised: a member lock would be reached through the very pointer whose publication is
 * racy. It is created during the dynamic initialisation of this translation unit, which the
 * dynamic loader runs before any exported function of the library can be called. Until then, and
 * if the allocation fails, it stays NULL and access is simply unsynchronised - the behaviour this
 * code had before - rather than a crash.
 */
static CJfxCriticalSection *s_pLoggerLock = CJfxCriticalSection::Create();

static inline void EnterLoggerLock()
{
    if (NULL != s_pLoggerLock) {
        s_pLoggerLock->Enter();
    }
}

static inline void ExitLoggerLock()
{
    if (NULL != s_pLoggerLock) {
        s_pLoggerLock->Exit();
    }
}

bool CLogger::copySink(int level, JfxmLogFn *pFn, void **ppUser)
{
    JfxmLogFn sinkFn = NULL;
    void     *sinkUser = NULL;
    bool      bEnabled;

    EnterLoggerLock();
    bEnabled = (level >= m_currentLevel);
    if (bEnabled) {
        sinkFn = m_sinkFn;
        sinkUser = m_sinkUser;
    }
    ExitLoggerLock();

    *pFn = sinkFn;
    *ppUser = sinkUser;
    return bEnabled && NULL != sinkFn;
}

bool CLogger::canLog(int level)
{
    int currentLevel;

    EnterLoggerLock();
    currentLevel = m_currentLevel;
    ExitLoggerLock();

    if (level < currentLevel)
    {
        return false;
    }
    else
    {
        return true;
    }
}

void CLogger::logMsg(int level, const char *msg)
{
    JfxmLogFn sinkFn = NULL;
    void     *sinkUser = NULL;

    // The sink installed by jfxm_log_init; without one there is nowhere to log to. It is called
    // through the copy, outside the lock, so it may log re-entrantly and may block.
    if (!copySink(level, &sinkFn, &sinkUser)) {
        return;
    }

    if (NULL != msg) {
        sinkFn(sinkUser, (int32_t)level, msg);
    }
}

void CLogger::logMsg(int level, const char *sourceClass, const char *sourceMethod, const char *msg)
{
    JfxmLogFn sinkFn = NULL;
    void     *sinkUser = NULL;

    if (!copySink(level, &sinkFn, &sinkUser)) {
        return;
    }

    // The sink has a single message slot, so fold the source into the text. No caller uses this
    // overload today.
    string formatted;
    if (NULL != sourceClass) {
        formatted += sourceClass;
    }
    if (NULL != sourceMethod) {
        formatted += ".";
        formatted += sourceMethod;
    }
    if (NULL != msg) {
        formatted += ": ";
        formatted += msg;
    }
    sinkFn(sinkUser, (int32_t)level, formatted.c_str());
}

// Do NOT use this function. Instead use setLevel() from Java layer.
void CLogger::setLevel(int level)
{
    EnterLoggerLock();
    m_currentLevel = level;
    ExitLoggerLock();
}

bool CLogger::initSink(JfxmLogFn fn, void* user)
{
    CLogger *pLogger = NULL;
    s_Singleton.GetInstance(&pLogger);
    if (NULL == pLogger) {
        return false;
    }

    EnterLoggerLock();
    pLogger->m_sinkFn = fn;
    pLogger->m_sinkUser = user;
    ExitLoggerLock();
    return true;
}

uint32_t CLogger::CreateInstance(CLogger **ppLogger)
{
    if (ppLogger == NULL) {
        return ERROR_FUNCTION_PARAM_NULL;
    }

    *ppLogger = new CLogger();
    if (*ppLogger == NULL) {
        return ERROR_MEMORY_ALLOCATION;
    }

    return ERROR_NONE;
}

#endif // ENABLE_LOGGING
