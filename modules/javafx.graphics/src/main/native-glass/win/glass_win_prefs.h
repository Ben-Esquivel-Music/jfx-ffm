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

#ifndef GLASS_WIN_PREFS_H
#define GLASS_WIN_PREFS_H

/*
 * glass_win_prefs.h - the boundary between the C++ toolkit core and the prefs module of the glass-win crate
 * (native-rust/glass-win/src/prefs.rs), which holds the WinRT half of the platform preferences: the activation of
 * UISettings and NetworkInformation, their two event sinks, the teardown before RoUninitialize, and the delivery of
 * preference types to GwinPrefsCallbacks (glass_win_api.h). Internal to glass.dll: nothing declared here is exported.
 * C types only. The crate's ABI check (abi_check.cpp) includes this header and compares each declaration with the
 * crate's own, in both directions.
 *
 * The handle stands for the WinRT objects of one GlassApplication; NULL is legal everywhere and means "none", as
 * after a panic the crate caught in glass_prefs_create. A panic the crate catches in gwin_prefs_query_ui_settings or
 * gwin_prefs_query_network while it reads the live handle poisons that handle (a bug, never passed on to the caller):
 * the queries then answer for it what they answer without WinRT objects, and glass_prefs_destroy leaves it alone. The
 * delivery of preference types reads no handle, so neither NULL nor a poisoned handle stops it.
 */

#include <stdint.h>

/*
 * Each function below that the toolkit core defines for the crate is noexcept and calls nothing that throws: no C++
 * exception may reach Rust frames. The exception specification is no part of the C ABI.
 */
#ifndef GLASS_PREFS_NOEXCEPT
#  ifdef __cplusplus
#    define GLASS_PREFS_NOEXCEPT noexcept
#  else
#    define GLASS_PREFS_NOEXCEPT
#  endif
#endif

#ifdef __cplusplus
extern "C" {
#endif

/*
 * ---- Defined by the crate, called by the toolkit core. Each runs in the crate's panic guard and answers the value
 * named here when a panic is caught. ----
 */

/*
 * GlassApplication's member initialisation, on the thread that will run the toolkit:
 * RoInitialize(RO_INIT_SINGLETHREADED) on that thread unless an earlier handle's is still in force, then UISettings
 * and NetworkInformation with their two sinks. A handle also when RoInitialize or an activation failed (a failed
 * RoInitialize writes "RoInitialize failed: <system message>" through glass_prefs_stderr); NULL only after a panic.
 */
void* glass_prefs_create(void);

/*
 * GlassApplication's destructor, on the toolkit thread: both sink registrations removed, every WinRT object released,
 * then RoUninitialize when a successful RoInitialize is in force. NULL does nothing. A poisoned handle is left as it
 * is: no remove, no release, no RoUninitialize.
 */
void glass_prefs_destroy(void* handle);

/*
 * Hands `types` (GWIN_PT_* bits) to GwinPrefsCallbacks.preferences_changed: 1 when it answered nonzero, else 0; 0
 * when no table is installed. Reads no handle, as PlatformSupport::updatePreferences at 5a709c189d read only the
 * callback table. A panic in this call answers 0 and poisons nothing. Toolkit thread only.
 */
int32_t glass_prefs_update(int32_t types);

/*
 * The WM_SETTINGCHANGE arm: (UINT)wparam SPI_SETHIGHCONTRAST updates with 6, SPI_SETCLIENTAREAANIMATION with 2, both
 * before lparam is looked at; otherwise a non-zero lparam naming L"ImmersiveColorSet" (as wcscmp compares) updates
 * with 4. Answers what the update answered, 0 when there was none. Reads no handle, as glass_prefs_update. A panic in
 * this call answers 0 and poisons nothing. Toolkit thread only.
 */
int32_t glass_prefs_setting_changed(uintptr_t wparam, intptr_t lparam);

/*
 * Delivers the types the sinks kept because their post failed with the toolkit window published, as glass_prefs_update
 * does, when there are any and glass_prefs_live_handle answers a handle, poisoned or not; when it answers NULL (no
 * toolkit) the taken types are dropped. Toolkit thread only, with no lock held, where a retrieved message has just
 * been dispatched.
 */
void glass_prefs_run_deferred(void);

/* Drops the kept types. Toolkit thread only. */
void glass_prefs_discard_deferred(void);

/* ---- Defined by the toolkit core, called by the crate. ---- */

/*
 * GlassApplication::PostToToolkit(WM_PREFERENCES_CHANGED, (WPARAM) types, 0, &published): 1 when posted, else 0;
 * *published is 1 when a toolkit window was published, else 0. Any thread.
 */
int32_t glass_prefs_post(int32_t types, int32_t* published) GLASS_PREFS_NOEXCEPT;

/* GlassApplication::ArmPreferencesTimer(). Any thread. */
void glass_prefs_arm_timer(void) GLASS_PREFS_NOEXCEPT;

/* The handle of the toolkit's GlassApplication while it is the instance, else NULL. */
void* glass_prefs_live_handle(void) GLASS_PREFS_NOEXCEPT;

/* fputs(text, stderr): the CRT stream the C's fprintf wrote to, without reading the text as a format. */
void glass_prefs_stderr(const char* text) GLASS_PREFS_NOEXCEPT;

#ifdef GLASS_WIN_TEST_HOOKS
/* The probes of glass_win_test.h the crate places: GwinTestRecordDelegateCall, GwinTestLogEvent, GwinTestCount. */
void glass_prefs_test_record_delegate_call(int32_t which) GLASS_PREFS_NOEXCEPT;
void glass_prefs_test_log_event(int32_t code) GLASS_PREFS_NOEXCEPT;
void glass_prefs_test_count(int32_t which) GLASS_PREFS_NOEXCEPT;
#endif /* GLASS_WIN_TEST_HOOKS */

#ifdef __cplusplus
}
#endif

#endif /* GLASS_WIN_PREFS_H */
