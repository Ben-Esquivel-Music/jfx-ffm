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
 * jfxm_media_create (GST) or jfxm_avf_player_init (AVF). The GST adapter is deleted by
 * CGstPipelineFactory::SourceCallbacksDestroyed, the GClosureNotify of the "close-connection"
 * signal: either from the disconnect at the end of SourceCloseConnection, after a real
 * READY->NULL transition, or from g_signal_handlers_destroy when g_object_unref finalizes an
 * element that never left GST_STATE_NULL (a media created and never played). The AVF adapter has
 * no such predecessor: the JNI player closed the connection and dropped the pointer without
 * deleting anything, leaking the adapter and its CLocatorStream with every jar:/jrt: player. It is
 * deleted by AVFMediaPlayer's -dispose, by the two paths of
 * -[AVFMediaPlayer initWithURL:eventHandler:locatorStream:] that return nil, and by the !player
 * branch of jfxm_avf_player_init - each of which deletes the CLocatorStream too, since
 * CLocatorStream stores the adapter pointer without owning it.
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

    // Registers, or with NULL unregisters, the caller's own pointer variable as this object's
    // owning slot: while it is set, ~CFfiStreamCallbacks NULLs that variable, so a caller holding
    // the only other copy of the pointer can tell whether somebody else has already freed the
    // object. InitGstMedia (ffi/jfxmedia_api.cpp) is the one user and the comment on its cleanup
    // block is the reason this exists; nothing here is thread safe, and it must not be used from
    // a slot that can outlive the object or from one that another thread can be writing.
    void    SetOwnerSlot(CFfiStreamCallbacks** ppSlot);

private:
    JfxmStreamCallbacks m_Callbacks;
    void*               m_pUser;
    // The closed latch, read and written with g_atomic_int_get/g_atomic_int_set - hence an int
    // rather than a bool, since glib has no atomic bool and its gint is an int. Spelled int and
    // not gint so that this header stays free of glib; only the .cpp includes it.
    //
    // It is written by CloseConnection() on whichever thread took javasource's element lock to
    // run the READY -> NULL state change, and read by the eight other methods on the javasource
    // source task or on the thread pulling that element's pad. The two are ordered in practice -
    // java_source_change_state deactivates the pads, and so joins the source task, before it
    // emits "close-connection", and CGstPipelineFactory::SourceCloseConnection disconnects the
    // other five handlers before it returns - so no read is expected to see the write at all. A
    // plain bool would still be a data race in the language, on an object whose ordering rests
    // entirely on invariants held one layer up in javasource.c, and the atomics cost a relaxed
    // load on every call to say so in the code instead of in a comment. This latch has no JNI
    // predecessor to be bug-compatible with: the JNI adapter dropped a global reference here and
    // had no flag.
    int                 m_bClosed;
    // The owning slot registered by SetOwnerSlot(), or NULL. Touched only by the creating thread,
    // before this object has been handed to anything else, and by the destructor.
    CFfiStreamCallbacks** m_ppOwnerSlot;
};

#endif // _FFI_STREAM_CALLBACKS_H_
