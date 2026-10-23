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

#ifndef GLASS_WIN_TEST_H
#define GLASS_WIN_TEST_H

/*
 * glass_win_test.h - the production-side probes of the shim-only test hooks that glass_win_api.h lists in
 * its SHIM-ONLY TEST HOOKS block. Internal to this library: nothing declared here is exported.
 *
 * Every probe below is an inline test of one flag that only a gwin_test_* export writes. While no test has
 * armed that hook the probe does nothing else, so production behaviour does not depend on this file. The
 * slow halves live in glass_win_test.cpp.
 */

#include <windows.h>
#include <imm.h>
#include <stdint.h>

#include "glass_win_api.h"

/* gwin_test_observe: nonzero while the counters and logs record. */
extern volatile LONG g_gwinTestObserving;

/* gwin_test_park_arm: the id of the thread whose next guarded post parks; 0 when the gate is not armed. */
extern volatile LONG g_gwinTestParkThread;

/* Non-NULL only on a thread inside gwin_test_ime_composition. */
extern __declspec(thread) void* g_gwinTestImeState;

void GwinTestCountSlow(int32_t which);
void GwinTestCountCallSlow(int32_t which, HWND target);
void GwinTestParkSlow();
void GwinTestLogEventSlow(int32_t code);
void GwinTestRecordDelegateCallSlow(int32_t which);
void GwinTestImeAllocPointSlow();
void GwinTestImeHelperFailureSlow();
void GwinTestImeBackstopSlow();
LONG GwinTestImeCannedComposition(DWORD index, LPVOID buf, DWORD len);

/* Adds 1 to counter `which` (enum GwinTestCounter) while gwin_test_observe is on. */
inline void GwinTestCount(int32_t which)
{
    if (g_gwinTestObserving != 0) {
        GwinTestCountSlow(which);
    }
}

/*
 * Immediately before a PostMessage (GWIN_TEST_COUNTER_POST) or SendMessage (_SEND) aimed at the toolkit
 * window: counts the call, and counts it as stale too when `target` is not the published toolkit window.
 */
inline void GwinTestCountCall(int32_t which, HWND target)
{
    if (g_gwinTestObserving != 0) {
        GwinTestCountCallSlow(which, target);
    }
}

/*
 * The park gate of gwin_test_park_arm: between a post's HWND load and its PostMessage. Only the armed
 * thread stops here, once, on a kernel wait.
 */
inline void GwinTestParkPoint()
{
    const LONG armed = g_gwinTestParkThread;
    if (armed != 0 && (DWORD) armed == ::GetCurrentThreadId()) {
        GwinTestParkSlow();
    }
}

/* Appends `code` (enum GwinTestEvent) to gwin_test_event_log while gwin_test_observe is on. */
inline void GwinTestLogEvent(int32_t code)
{
    if (g_gwinTestObserving != 0) {
        GwinTestLogEventSlow(code);
    }
}

/* First thing in each preference-sink delegate: gwin_test_prefs_delegate_calls, while observing. */
inline void GwinTestRecordDelegateCall(int32_t which)
{
    if (g_gwinTestObserving != 0) {
        GwinTestRecordDelegateCallSlow(which);
    }
}

/*
 * ImmGetCompositionStringW, unless the calling thread is inside gwin_test_ime_composition, which stands a
 * canned composition in for the input context.
 */
inline LONG GwinImmGetCompositionString(HIMC hIMC, DWORD index, LPVOID buf, DWORD len)
{
    if (g_gwinTestImeState != NULL) {
        return GwinTestImeCannedComposition(index, buf, len);
    }
    return ::ImmGetCompositionStringW(hIMC, index, buf, len);
}

/*
 * Placed immediately before each throwing allocation of GlassInputTextInfo. Inside
 * gwin_test_ime_composition it counts the attempt and, at the attempt the test chose, throws
 * std::bad_alloc in place of the allocation.
 */
inline void GwinTestImeAllocPoint()
{
    if (g_gwinTestImeState != NULL) {
        GwinTestImeAllocPointSlow();
    }
}

/* A GlassInputTextInfo helper took its std::bad_alloc branch. */
inline void GwinTestImeHelperFailure()
{
    if (g_gwinTestImeState != NULL) {
        GwinTestImeHelperFailureSlow();
    }
}

/* ViewContainer::WmImeComposition entered its catch block. */
inline void GwinTestImeBackstop()
{
    if (g_gwinTestImeState != NULL) {
        GwinTestImeBackstopSlow();
    }
}

#endif /* GLASS_WIN_TEST_H */
