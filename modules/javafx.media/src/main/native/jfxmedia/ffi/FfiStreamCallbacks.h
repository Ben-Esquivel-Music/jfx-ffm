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

#ifndef _FFI_STREAM_CALLBACKS_H_
#define _FFI_STREAM_CALLBACKS_H_

#include <jfxmedia_api.h>
#include <Locator/LocatorStream.h>

/*
 * CStreamCallbacks over a JfxmStreamCallbacks table (FFM-ABI-CONTRACT.md section 9). Replaces
 * CJavaInputStreamCallbacks with the same return conventions: a NULL slot behaves like a Java
 * target that threw (need_buffer/is_seekable/is_random_access/property 0, read_* -2, seek -1,
 * copy_block 0, close_connection no-op), and after CloseConnection the adapter answers like the JNI
 * one did once its global reference was gone (reads -1, the rest 0/false/no-op). Created by
 * jfxm_media_create; deleted by the pipeline factory right after CloseConnection (GST) or by the
 * AVF player on dispose, exactly where CJavaInputStreamCallbacks was.
 */
class CFfiStreamCallbacks : public CStreamCallbacks
{
public:
    CFfiStreamCallbacks(const JfxmStreamCallbacks* pCallbacks, void* pUser);
    virtual ~CFfiStreamCallbacks();

    bool    NeedBuffer();
    int     ReadNextBlock();
    int     ReadBlock(int64_t position, int size);
    int     CopyBlock(void* destination, int size);
    bool    IsSeekable();
    bool    IsRandomAccess();
    int64_t Seek(int64_t position);
    void    CloseConnection();
    int     Property(int prop, int value);

private:
    JfxmStreamCallbacks m_Callbacks;
    void*               m_pUser;
    bool                m_bClosed;
};

#endif // _FFI_STREAM_CALLBACKS_H_
