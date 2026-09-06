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

// For g_atomic_int_get/g_atomic_int_set on m_bClosed. jfxmedia links glib on all three platforms
// (glib-lite on Windows and macOS, the system GLib on Linux), so this adds no dependency; it is
// included here rather than in the header to keep FfiStreamCallbacks.h free of glib for the
// ObjC++ translation units that include it.
#include <glib.h>

#include <string.h>

CFfiStreamCallbacks::CFfiStreamCallbacks(const JfxmStreamCallbacks* pCallbacks, void* pUser)
    : m_pUser(pUser),
      m_bClosed(0),
      m_ppOwnerSlot(NULL)
{
    if (NULL != pCallbacks) {
        m_Callbacks = *pCallbacks;
    } else {
        memset(&m_Callbacks, 0, sizeof(m_Callbacks));
    }
}

CFfiStreamCallbacks::~CFfiStreamCallbacks()
{
    // Tell the creator, if it is still watching, that this object is gone. See SetOwnerSlot().
    if (NULL != m_ppOwnerSlot && this == *m_ppOwnerSlot) {
        *m_ppOwnerSlot = NULL;
    }
    m_ppOwnerSlot = NULL;
    memset(&m_Callbacks, 0, sizeof(m_Callbacks));
}

void CFfiStreamCallbacks::SetOwnerSlot(CFfiStreamCallbacks** ppSlot)
{
    m_ppOwnerSlot = ppSlot;
}

bool CFfiStreamCallbacks::NeedBuffer()
{
    if (g_atomic_int_get(&m_bClosed) || NULL == m_Callbacks.need_buffer) {
        return false;
    }
    return m_Callbacks.need_buffer(m_pUser) != 0;
}

int CFfiStreamCallbacks::ReadNextBlock()
{
    if (g_atomic_int_get(&m_bClosed)) {
        return -1;
    }
    if (NULL == m_Callbacks.read_next_block) {
        return -2;
    }
    return (int)m_Callbacks.read_next_block(m_pUser);
}

int CFfiStreamCallbacks::ReadBlock(int64_t position, int size)
{
    if (g_atomic_int_get(&m_bClosed)) {
        return -1;
    }
    if (NULL == m_Callbacks.read_block) {
        return -2;
    }
    return (int)m_Callbacks.read_block(m_pUser, position, (int32_t)size);
}

int CFfiStreamCallbacks::CopyBlock(void* destination, int size)
{
    if (g_atomic_int_get(&m_bClosed) || NULL == m_Callbacks.copy_block) {
        return 0;
    }
    return (int)m_Callbacks.copy_block(m_pUser, destination, (int32_t)size);
}

bool CFfiStreamCallbacks::IsSeekable()
{
    if (g_atomic_int_get(&m_bClosed) || NULL == m_Callbacks.is_seekable) {
        return false;
    }
    return m_Callbacks.is_seekable(m_pUser) != 0;
}

bool CFfiStreamCallbacks::IsRandomAccess()
{
    if (g_atomic_int_get(&m_bClosed) || NULL == m_Callbacks.is_random_access) {
        return false;
    }
    return m_Callbacks.is_random_access(m_pUser) != 0;
}

int64_t CFfiStreamCallbacks::Seek(int64_t position)
{
    if (g_atomic_int_get(&m_bClosed) || NULL == m_Callbacks.seek) {
        return -1;
    }
    return m_Callbacks.seek(m_pUser, position);
}

void CFfiStreamCallbacks::CloseConnection()
{
    if (g_atomic_int_get(&m_bClosed)) {
        return;
    }
    if (NULL != m_Callbacks.close_connection) {
        m_Callbacks.close_connection(m_pUser);
    }
    // The JNI adapter dropped its global reference here; every later call found no connection.
    g_atomic_int_set(&m_bClosed, 1);
}

int CFfiStreamCallbacks::Property(int prop, int value)
{
    if (g_atomic_int_get(&m_bClosed) || NULL == m_Callbacks.property) {
        return 0;
    }
    return (int)m_Callbacks.property(m_pUser, (int32_t)prop, (int32_t)value);
}
