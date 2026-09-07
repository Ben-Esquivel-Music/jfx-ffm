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

#ifndef _JFXM_MEDIA_HANDLE_H_
#define _JFXM_MEDIA_HANDLE_H_

#include <stdint.h>
#include <string.h>
#include <string>
#include <jfxmedia_api.h>

class CMedia;

/*
 * The object behind the opaque jfxm_media handle (FFM-ABI-CONTRACT.md section 3). Internal to the
 * library: created by jfxm_media_create, freed by jfxm_media_dispose. The GStreamer backend fills
 * `gst`; the AVFoundation backend keeps the create-time inputs here until jfxm_player_init builds
 * the OSXMediaPlayer, whose retained pointer then lives in `osx_player`.
 */
struct JfxmMedia
{
    int32_t             backend;        // JFXM_BACKEND_GST or JFXM_BACKEND_AVF
    CMedia*             gst;            // GST: owns the pipeline; NULL for AVF
    void*               osx_player;     // AVF: retained OSXMediaPlayer*; NULL for GST

    // AVF only: inputs of jfxm_media_create, consumed by jfxm_player_init.
    std::string         content_type;
    std::string         location;
    int64_t             size_hint;
    // 1 when a JfxmStreamCallbacks table was given, which Java does for jar:/jrt: locations and
    // for nothing else. It is the only thing jfxm_avf_player_init tests to decide whether the media
    // is read through the Locator: the scheme is not parsed again on the C side.
    int32_t             has_stream;
    JfxmStreamCallbacks stream;
    void*               stream_user;

    JfxmMedia(int32_t iBackend)
        : backend(iBackend),
          gst(NULL),
          osx_player(NULL),
          size_hint(0),
          has_stream(0),
          stream_user(NULL)
    {
        memset(&stream, 0, sizeof(stream));
    }
};

#endif // _JFXM_MEDIA_HANDLE_H_
