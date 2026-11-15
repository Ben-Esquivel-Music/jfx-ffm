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

#include "jfxmedia_test_hooks.h"
#include "JfxmTestHooks.h"

#if defined(JFXM_TEST_HOOKS) && JFXM_TEST_HOOKS == 1

#include <glib-object.h>

namespace {

gint s_ArmedFault = JFXM_TEST_FAULT_NONE;
gint s_Counters[JFXM_TEST_COUNTER_COUNT];

void Bump(int id)
{
    g_atomic_int_inc(&s_Counters[id]);
}

// The weak notify runs while the object is disposed for the last time and before it is freed, so its
// floating flag still tells whether anyone sank the reference that was unreffed.
void CountFinalized(GObject* object, int finalized, int finalizedFloating)
{
    Bump(finalized);
    if (g_object_is_floating(object))
        Bump(finalizedFloating);
}

void SourceFinalized(gpointer data, GObject* object)
{
    (void)data;
    CountFinalized(object, JFXM_TEST_COUNTER_SOURCE_FINALIZED, JFXM_TEST_COUNTER_SOURCE_FINALIZED_FLOATING);
}

void BinFinalized(gpointer data, GObject* object)
{
    (void)data;
    CountFinalized(object, JFXM_TEST_COUNTER_BIN_FINALIZED, JFXM_TEST_COUNTER_BIN_FINALIZED_FLOATING);
}

} // namespace

bool JfxmTestHooks::FaultArmed(int point)
{
    if (JFXM_TEST_FAULT_NONE == point ||
        !g_atomic_int_compare_and_exchange(&s_ArmedFault, point, JFXM_TEST_FAULT_NONE))
        return false;

    Bump(JFXM_TEST_COUNTER_FAULTS_FIRED);
    return true;
}

void JfxmTestHooks::WatchSource(void* gobject)
{
    Bump(JFXM_TEST_COUNTER_SOURCE_WATCHED);
    g_object_weak_ref(static_cast<GObject*>(gobject), SourceFinalized, NULL);
}

void JfxmTestHooks::WatchBin(void* gobject)
{
    Bump(JFXM_TEST_COUNTER_BIN_WATCHED);
    g_object_weak_ref(static_cast<GObject*>(gobject), BinFinalized, NULL);
}

void JfxmTestHooks::AdapterConstructed()
{
    Bump(JFXM_TEST_COUNTER_ADAPTERS_CONSTRUCTED);
}

void JfxmTestHooks::AdapterDestroyed()
{
    Bump(JFXM_TEST_COUNTER_ADAPTERS_DESTROYED);
}

void JfxmTestHooks::SpectrumStaleDropped()
{
    Bump(JFXM_TEST_COUNTER_SPECTRUM_STALE_DROPPED);
}

void JfxmTestHooks::SpectrumEventSent()
{
    Bump(JFXM_TEST_COUNTER_SPECTRUM_EVENTS_SENT);
}

void JfxmTestHooks::SpectrumBandsWritten()
{
    Bump(JFXM_TEST_COUNTER_SPECTRUM_BANDS_WRITTEN);
}

void jfxm_test_reset(void)
{
    g_atomic_int_set(&s_ArmedFault, JFXM_TEST_FAULT_NONE);
    for (int i = 0; i < JFXM_TEST_COUNTER_COUNT; i++)
        g_atomic_int_set(&s_Counters[i], 0);
}

void jfxm_test_arm_source_fault(int32_t p)
{
    bool known = JFXM_TEST_FAULT_BIN_NEW <= p && p <= JFXM_TEST_FAULT_LINK;
    g_atomic_int_set(&s_ArmedFault, known ? (gint)p : JFXM_TEST_FAULT_NONE);
}

int32_t jfxm_test_counter(int32_t id)
{
    if (id < 0 || id >= JFXM_TEST_COUNTER_COUNT)
        return -1;
    return (int32_t)g_atomic_int_get(&s_Counters[id]);
}

#endif // JFXM_TEST_HOOKS
