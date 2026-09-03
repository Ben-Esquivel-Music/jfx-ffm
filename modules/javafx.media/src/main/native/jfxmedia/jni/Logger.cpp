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

#if ENABLE_LOGGING

CLogger::LSingleton CLogger::s_Singleton;

bool CLogger::canLog(int level)
{
    if (level < m_currentLevel)
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
    if (level < m_currentLevel) {
        return;
    }

    // The sink installed by jfxm_log_init; without one there is nowhere to log to.
    if (NULL != m_sinkFn) {
        if (NULL != msg) {
            m_sinkFn(m_sinkUser, (int32_t)level, msg);
        }
    }
}

void CLogger::logMsg(int level, const char *sourceClass, const char *sourceMethod, const char *msg)
{
    if (level < m_currentLevel) {
        return;
    }

    // The sink has a single message slot, so fold the source into the text. No caller uses this
    // overload today.
    if (NULL != m_sinkFn) {
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
        m_sinkFn(m_sinkUser, (int32_t)level, formatted.c_str());
    }
}

// Do NOT use this function. Instead use setLevel() from Java layer.
void CLogger::setLevel(int level)
{
    m_currentLevel = level;
}

bool CLogger::initSink(JfxmLogFn fn, void* user)
{
    CLogger *pLogger = NULL;
    s_Singleton.GetInstance(&pLogger);
    if (NULL == pLogger) {
        return false;
    }

    pLogger->m_sinkFn = fn;
    pLogger->m_sinkUser = user;
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
