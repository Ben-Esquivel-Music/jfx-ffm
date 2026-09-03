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

#include "FfiBandsHolder.h"

#include <Common/VSMemory.h>

#include <string.h>

CFfiBandsHolder::CFfiBandsHolder(int bands, float* magnitudes, float* phases,
                                 JfxmReleaseFn release, void* releaseUser)
    : m_Bands(bands),
      m_Magnitudes(magnitudes),
      m_Phases(phases),
      m_Release(release),
      m_pReleaseUser(releaseUser)
{
    InitRef(this);
}

CFfiBandsHolder::~CFfiBandsHolder()
{
    // Reached only when the reference count hit zero, so no spectrum thread can be inside
    // UpdateBands any more: this is the one moment at which the pair may be handed back. The
    // caller of ReleaseRef decides the thread - GST MainLoop, the AVF audio tap with the band lock
    // held, or the application thread doing set_bands / dispose - so the target must be
    // thread-safe and must not block (jfxmedia_api.h, JfxmReleaseFn).
    JfxmReleaseFn release = m_Release;
    void* releaseUser = m_pReleaseUser;

    m_Release = NULL;
    m_pReleaseUser = NULL;
    m_Magnitudes = NULL;
    m_Phases = NULL;
    m_Bands = 0;

    if (NULL != release) {
        release(releaseUser);
    }
}

void CFfiBandsHolder::UpdateBands(int size, const float* magnitudes, const float* phases)
{
    if (m_Bands != size || size < 0) {
        return;
    }

    // CJavaBandsHolder wrote both arrays or neither.
    if (NULL != m_Magnitudes && NULL != m_Phases && NULL != magnitudes && NULL != phases) {
        memcpy(m_Magnitudes, magnitudes, (size_t)size * sizeof(float));
        memcpy(m_Phases, phases, (size_t)size * sizeof(float));
    }
}
