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

#include "FfiStreamCallbacks.h"

#include <Common/VSMemory.h>

#include <string.h>

CFfiStreamCallbacks::CFfiStreamCallbacks(const JfxmStreamCallbacks* pCallbacks, void* pUser)
    : m_pUser(pUser),
      m_bClosed(false)
{
    if (NULL != pCallbacks) {
        m_Callbacks = *pCallbacks;
    } else {
        memset(&m_Callbacks, 0, sizeof(m_Callbacks));
    }
}

CFfiStreamCallbacks::~CFfiStreamCallbacks()
{
    memset(&m_Callbacks, 0, sizeof(m_Callbacks));
}

bool CFfiStreamCallbacks::NeedBuffer()
{
    if (m_bClosed || NULL == m_Callbacks.need_buffer) {
        return false;
    }
    return m_Callbacks.need_buffer(m_pUser) != 0;
}

int CFfiStreamCallbacks::ReadNextBlock()
{
    if (m_bClosed) {
        return -1;
    }
    if (NULL == m_Callbacks.read_next_block) {
        return -2;
    }
    return (int)m_Callbacks.read_next_block(m_pUser);
}

int CFfiStreamCallbacks::ReadBlock(int64_t position, int size)
{
    if (m_bClosed) {
        return -1;
    }
    if (NULL == m_Callbacks.read_block) {
        return -2;
    }
    return (int)m_Callbacks.read_block(m_pUser, position, (int32_t)size);
}

int CFfiStreamCallbacks::CopyBlock(void* destination, int size)
{
    if (m_bClosed || NULL == m_Callbacks.copy_block) {
        return 0;
    }
    return (int)m_Callbacks.copy_block(m_pUser, destination, (int32_t)size);
}

bool CFfiStreamCallbacks::IsSeekable()
{
    if (m_bClosed || NULL == m_Callbacks.is_seekable) {
        return false;
    }
    return m_Callbacks.is_seekable(m_pUser) != 0;
}

bool CFfiStreamCallbacks::IsRandomAccess()
{
    if (m_bClosed || NULL == m_Callbacks.is_random_access) {
        return false;
    }
    return m_Callbacks.is_random_access(m_pUser) != 0;
}

int64_t CFfiStreamCallbacks::Seek(int64_t position)
{
    if (m_bClosed || NULL == m_Callbacks.seek) {
        return -1;
    }
    return m_Callbacks.seek(m_pUser, position);
}

void CFfiStreamCallbacks::CloseConnection()
{
    if (m_bClosed) {
        return;
    }
    if (NULL != m_Callbacks.close_connection) {
        m_Callbacks.close_connection(m_pUser);
    }
    // The JNI adapter dropped its global reference here; every later call found no connection.
    m_bClosed = true;
}

int CFfiStreamCallbacks::Property(int prop, int value)
{
    if (m_bClosed || NULL == m_Callbacks.property) {
        return 0;
    }
    return (int)m_Callbacks.property(m_pUser, (int32_t)prop, (int32_t)value);
}
