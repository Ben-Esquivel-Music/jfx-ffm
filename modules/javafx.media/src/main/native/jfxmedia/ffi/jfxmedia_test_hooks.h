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

/*
 * jfxmedia_test_hooks.h - the test-only exports of the hooks build of jfxmedia.
 *
 * Only the hooks build defines JFXM_TEST_HOOKS=1 and compiles ffi/jfxmedia_test_hooks.cpp: the
 * jfxmedia_test target of native/win.cmake and native/linux.cmake, which writes its library to
 * JFXM_TEST_BIN_DIR (target/native/test-bin in the Maven build), never to BIN_DIR. The shipped
 * jfxmedia exports no jfxm_test_* name, and the functions below are declared only when
 * JFXM_TEST_HOOKS is 1, so production code that called one would not compile. JfxMediaNative binds
 * none of them; only tests do. The seams in production code that feed them are ffi/JfxmTestHooks.h.
 *
 * Counters are bumped on GLib, GStreamer and Java threads and every access is atomic. Each counts
 * from the last jfxm_test_reset.
 */

#ifndef _JFXMEDIA_TEST_HOOKS_H_
#define _JFXMEDIA_TEST_HOOKS_H_

#include <jfxmedia_api.h>

/* The call in CGstPipelineFactory::CreateSourceElement that an armed fault fails. */
enum {
    JFXM_TEST_FAULT_NONE           = 0,
    JFXM_TEST_FAULT_BIN_NEW        = 1,   /* gst_bin_new */
    JFXM_TEST_FAULT_BUFFER_ELEMENT = 2,   /* creating the progressbuffer or hlsprogressbuffer element */
    JFXM_TEST_FAULT_LINK           = 3    /* gst_element_link(javaSource, buffer) */
};

/*
 * Counter ids accepted by jfxm_test_counter. "Watched" objects are the javasource elements and the
 * source bins CreateSourceElement makes; "finalized" counts the g_object_weak_ref notifies of those,
 * and "while floating" the notifies that found the object still floating, i.e. never sunk. The
 * spectrum counters are bumped on the bus thread, a written band set before the event that
 * delivers it.
 */
enum {
    JFXM_TEST_COUNTER_SOURCE_WATCHED            = 0,
    JFXM_TEST_COUNTER_SOURCE_FINALIZED          = 1,
    JFXM_TEST_COUNTER_SOURCE_FINALIZED_FLOATING = 2,
    JFXM_TEST_COUNTER_BIN_WATCHED               = 3,
    JFXM_TEST_COUNTER_BIN_FINALIZED             = 4,
    JFXM_TEST_COUNTER_BIN_FINALIZED_FLOATING    = 5,
    JFXM_TEST_COUNTER_ADAPTERS_CONSTRUCTED      = 6,   /* CFfiStreamCallbacks constructors run */
    JFXM_TEST_COUNTER_ADAPTERS_DESTROYED        = 7,   /* CFfiStreamCallbacks destructors run */
    JFXM_TEST_COUNTER_FAULTS_FIRED              = 8,   /* armed faults that replaced their call */
    JFXM_TEST_COUNTER_SPECTRUM_STALE_DROPPED    = 9,   /* messages computed for another band count */
    JFXM_TEST_COUNTER_SPECTRUM_EVENTS_SENT      = 10,  /* SendAudioSpectrumEvent calls */
    JFXM_TEST_COUNTER_SPECTRUM_BANDS_WRITTEN    = 11,  /* band sets copied into a holder's arrays */
    JFXM_TEST_COUNTER_COUNT                     = 12
};

#if defined(JFXM_TEST_HOOKS) && JFXM_TEST_HOOKS == 1

#ifdef __cplusplus
extern "C" {
#endif

/* Disarms the fault and sets every counter to 0. */
JFXM_EXPORT void    jfxm_test_reset(void);

/*
 * Arms fault point p (JFXM_TEST_FAULT_*). The next CreateSourceElement that reaches that point
 * consumes the arming, so one call fails per arming. 0, or a value outside 1..3, disarms.
 */
JFXM_EXPORT void    jfxm_test_arm_source_fault(int32_t p);

/* The value of counter id (JFXM_TEST_COUNTER_*), or -1 for an unknown id. */
JFXM_EXPORT int32_t jfxm_test_counter(int32_t id);

#ifdef __cplusplus
}
#endif

#endif /* JFXM_TEST_HOOKS */

#endif /* _JFXMEDIA_TEST_HOOKS_H_ */
