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

#ifndef _FFI_BANDS_HOLDER_H_
#define _FFI_BANDS_HOLDER_H_

#include <jfxmedia_api.h>

#include <PipelineManagement/AudioSpectrum.h>

/*
 * CBandsHolder over two Java-allocated float arrays (FFM-ABI-CONTRACT.md section 11). Replaces
 * CJavaBandsHolder: `magnitudes` and `phases` point at memory of `bands` floats each; UpdateBands
 * memcpy's into them from the spectrum thread (GST MainLoop / AVF audio tap). Reference counted
 * exactly like CJavaBandsHolder: the spectrum AddRef/ReleaseRef's it and deletes it when the count
 * reaches zero.
 *
 * The holder owns the pair for its whole lifetime, which is what CJavaBandsHolder's two
 * NewGlobalRefs did. Both spectrum implementations retain the holder under a lock and then write
 * through it with that lock dropped, so a spectrum thread can still be writing through this holder
 * when a newer holder has already been installed, and the memory must stay valid until the last
 * reference goes away - not until jfxm_spectrum_set_bands returns. The destructor therefore calls
 * the JfxmReleaseFn once, on whichever thread dropped that last reference, and nothing touches the
 * pair afterwards.
 */
class CFfiBandsHolder : public CBandsHolder
{
public:
    CFfiBandsHolder(int bands, float* magnitudes, float* phases,
                    JfxmReleaseFn release, void* releaseUser);
    virtual ~CFfiBandsHolder();

    void UpdateBands(int size, const float* magnitudes, const float* phases);

private:
    int           m_Bands;
    float*        m_Magnitudes;
    float*        m_Phases;
    JfxmReleaseFn m_Release;
    void*         m_pReleaseUser;
};

#endif // _FFI_BANDS_HOLDER_H_
