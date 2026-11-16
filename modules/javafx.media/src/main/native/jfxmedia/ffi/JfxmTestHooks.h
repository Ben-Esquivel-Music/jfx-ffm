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

#ifndef _JFXM_TEST_HOOKS_SEAMS_H_
#define _JFXM_TEST_HOOKS_SEAMS_H_

/*
 * The production-side seams of the hooks that ffi/jfxmedia_test_hooks.h exports. They are not part
 * of the C ABI. In the shipped build every seam is an empty static inline and FaultArmed answers
 * false, so the call sites carry no #if and production behaviour does not depend on this file. They
 * are static so that no build of the shipped library exports one: an unoptimised build emits a plain
 * inline function it did not inline, and on Linux, where jfxmedia is built with default visibility,
 * that copy is an exported weak symbol. Only the hooks build (JFXM_TEST_HOOKS=1) defines the seams
 * as functions, in ffi/jfxmedia_test_hooks.cpp: the Windows hooks library does not export them, and
 * the Linux one does, for the same visibility reason.
 */

#include "jfxmedia_test_hooks.h"

namespace JfxmTestHooks {

#if defined(JFXM_TEST_HOOKS) && JFXM_TEST_HOOKS == 1

/*
 * True exactly once after jfxm_test_arm_source_fault armed `point` (JFXM_TEST_FAULT_*): consumes the
 * arming and counts the fault. A call site uses it in place of the call it fails, so an injected
 * failure leaves behind exactly what the real failure would.
 */
bool FaultArmed(int point);

/* Counts the GObject as watched and puts a weak reference on it that counts its finalization. */
void WatchSource(void* gobject);
void WatchBin(void* gobject);

/* One call in each CFfiStreamCallbacks constructor and destructor. */
void AdapterConstructed();
void AdapterDestroyed();

/*
 * The spectrum branch of CGstAudioPlaybackPipeline::BusCallback: a message dropped as stale, and an
 * event sent, whatever SendAudioSpectrumEvent returns. CFfiBandsHolder::UpdateBands: a band set
 * copied into the holder's arrays.
 */
void SpectrumStaleDropped();
void SpectrumEventSent();
void SpectrumBandsWritten();

#else

static inline bool FaultArmed(int) { return false; }
static inline void WatchSource(void*) {}
static inline void WatchBin(void*) {}
static inline void AdapterConstructed() {}
static inline void AdapterDestroyed() {}
static inline void SpectrumStaleDropped() {}
static inline void SpectrumEventSent() {}
static inline void SpectrumBandsWritten() {}

#endif

} // namespace JfxmTestHooks

#endif // _JFXM_TEST_HOOKS_SEAMS_H_
