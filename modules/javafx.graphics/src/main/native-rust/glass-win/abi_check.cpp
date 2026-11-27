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
 * Compile-time check that the glass-win crate implements glass_win_api.h. Compiled as C++, never linked: in C++ a
 * second extern "C" declaration of a function with other parameters or another return type is an error (MSVC
 * C2733/C2556); MSVC's C mode would only warn (C4028).
 *
 * Include path: the directory of glass_win_api.h and glass_win_prefs.h, and the directory of the two files cbindgen
 * generates from this crate with the configuration files next to this one:
 *   glass_win_rs_functions.h  from cbindgen-functions.toml: the crate's extern "C" functions, with the header's
 *                             type names, those of the test-hooks feature inside #if defined(GLASS_WIN_TEST_HOOKS),
 *                             and as extern declarations the functions of glass_win_prefs.h the crate calls;
 *   glass_win_rs_types.h      from cbindgen-types.toml: the crate's constants and repr(C) types, prefixed rs_.
 *
 * Compiled once per feature set that native/win.cmake links: without features for glass, and for glass_test (the
 * test-hooks feature) with GLASS_WIN_TEST_HOOKS=1, so that the headers' #ifdef GLASS_WIN_TEST_HOOKS and the
 * generated #if defined(GLASS_WIN_TEST_HOOKS) agree.
 */

#include <glass_win_api.h>

// glass_win_prefs.h declares the functions the C++ defines for the crate noexcept; cbindgen writes no exception
// specification, which is no part of the C ABI, so the redeclarations below are compared without it.
#define GLASS_PREFS_NOEXCEPT
#include <glass_win_prefs.h>

#include <glass_win_rs_functions.h>
#include <glass_win_rs_types.h>

#include <cstddef>
#include <type_traits>

static_assert(rs_GWIN_UI_COLOR_COUNT == static_cast<uintptr_t>(GWIN_UI_COLOR_COUNT),
              "glass-win crate: GWIN_UI_COLOR_COUNT differs from glass_win_api.h");

// The enumerators of glass_win_api.h the crate uses, by value; each rs_ constant is an int32_t, the C type the crate
// passes them as.
#define GLASS_WIN_ABI_CHECK_CONSTANT(name)                                                                            \
    static_assert(rs_##name == name, "glass-win crate: " #name " differs from glass_win_api.h");                      \
    static_assert(std::is_same<decltype(rs_##name), const int32_t>::value, "glass-win crate: " #name " is no int32_t")
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_OK);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_ERR_INVALID_ARG);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_PT_SYSTEM_COLORS);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_PT_SYSTEM_PARAMS);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_PT_UI_SETTINGS);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_PT_NETWORK_INFORMATION);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_PT_ALL);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_NET_COST_UNKNOWN);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_NET_COST_UNRESTRICTED);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_NET_COST_VARIABLE);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_NET_COST_FIXED);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_DELEGATE_AUTO_HIDE);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_DELEGATE_NETWORK);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_EVENT_REMOVE_AUTO_HIDE);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_EVENT_REMOVE_NETWORK);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_EVENT_RELEASE_UI_SETTINGS);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_EVENT_RELEASE_NETWORK_STATICS);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_EVENT_RELEASE_NETWORK_FACTORY);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_EVENT_RO_UNINITIALIZE);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_COUNTER_PREFS_DEFERRED);
GLASS_WIN_ABI_CHECK_CONSTANT(GWIN_TEST_COUNTER_PREFS_DEFERRED_RUN);
#undef GLASS_WIN_ABI_CHECK_CONSTANT

static_assert(sizeof(rs_GwinPrefsCallbacks) == sizeof(GwinPrefsCallbacks),
              "glass-win crate: sizeof(GwinPrefsCallbacks) differs");
static_assert(alignof(rs_GwinPrefsCallbacks) == alignof(GwinPrefsCallbacks),
              "glass-win crate: alignof(GwinPrefsCallbacks) differs");
static_assert(offsetof(rs_GwinPrefsCallbacks, preferences_changed) == offsetof(GwinPrefsCallbacks, preferences_changed),
              "glass-win crate: GwinPrefsCallbacks::preferences_changed moved");
static_assert(std::is_same<decltype(rs_GwinPrefsCallbacks::preferences_changed),
                           decltype(GwinPrefsCallbacks::preferences_changed)>::value,
              "glass-win crate: GwinPrefsCallbacks::preferences_changed changed type");

static_assert(sizeof(rs_GwinUiSettings) == sizeof(GwinUiSettings),
              "glass-win crate: sizeof(GwinUiSettings) differs");
static_assert(alignof(rs_GwinUiSettings) == alignof(GwinUiSettings),
              "glass-win crate: alignof(GwinUiSettings) differs");
static_assert(offsetof(rs_GwinUiSettings, available) == offsetof(GwinUiSettings, available),
              "glass-win crate: GwinUiSettings::available moved");
static_assert(offsetof(rs_GwinUiSettings, colors_valid) == offsetof(GwinUiSettings, colors_valid),
              "glass-win crate: GwinUiSettings::colors_valid moved");
static_assert(offsetof(rs_GwinUiSettings, colors) == offsetof(GwinUiSettings, colors),
              "glass-win crate: GwinUiSettings::colors moved");
static_assert(offsetof(rs_GwinUiSettings, advanced_effects_valid) == offsetof(GwinUiSettings, advanced_effects_valid),
              "glass-win crate: GwinUiSettings::advanced_effects_valid moved");
static_assert(offsetof(rs_GwinUiSettings, advanced_effects_enabled)
                      == offsetof(GwinUiSettings, advanced_effects_enabled),
              "glass-win crate: GwinUiSettings::advanced_effects_enabled moved");
static_assert(offsetof(rs_GwinUiSettings, auto_hide_valid) == offsetof(GwinUiSettings, auto_hide_valid),
              "glass-win crate: GwinUiSettings::auto_hide_valid moved");
static_assert(offsetof(rs_GwinUiSettings, auto_hide_scroll_bars) == offsetof(GwinUiSettings, auto_hide_scroll_bars),
              "glass-win crate: GwinUiSettings::auto_hide_scroll_bars moved");
static_assert(std::is_same<decltype(rs_GwinUiSettings::available), decltype(GwinUiSettings::available)>::value,
              "glass-win crate: GwinUiSettings::available changed type");
static_assert(std::is_same<decltype(rs_GwinUiSettings::colors_valid), decltype(GwinUiSettings::colors_valid)>::value,
              "glass-win crate: GwinUiSettings::colors_valid changed type");
static_assert(std::is_same<decltype(rs_GwinUiSettings::colors), decltype(GwinUiSettings::colors)>::value,
              "glass-win crate: GwinUiSettings::colors changed type");
static_assert(std::is_same<decltype(rs_GwinUiSettings::advanced_effects_valid),
                           decltype(GwinUiSettings::advanced_effects_valid)>::value,
              "glass-win crate: GwinUiSettings::advanced_effects_valid changed type");
static_assert(std::is_same<decltype(rs_GwinUiSettings::advanced_effects_enabled),
                           decltype(GwinUiSettings::advanced_effects_enabled)>::value,
              "glass-win crate: GwinUiSettings::advanced_effects_enabled changed type");
static_assert(std::is_same<decltype(rs_GwinUiSettings::auto_hide_valid),
                           decltype(GwinUiSettings::auto_hide_valid)>::value,
              "glass-win crate: GwinUiSettings::auto_hide_valid changed type");
static_assert(std::is_same<decltype(rs_GwinUiSettings::auto_hide_scroll_bars),
                           decltype(GwinUiSettings::auto_hide_scroll_bars)>::value,
              "glass-win crate: GwinUiSettings::auto_hide_scroll_bars changed type");

static_assert(sizeof(rs_GwinNetworkInfo) == sizeof(GwinNetworkInfo),
              "glass-win crate: sizeof(GwinNetworkInfo) differs");
static_assert(alignof(rs_GwinNetworkInfo) == alignof(GwinNetworkInfo),
              "glass-win crate: alignof(GwinNetworkInfo) differs");
static_assert(offsetof(rs_GwinNetworkInfo, available) == offsetof(GwinNetworkInfo, available),
              "glass-win crate: GwinNetworkInfo::available moved");
static_assert(offsetof(rs_GwinNetworkInfo, cost_type) == offsetof(GwinNetworkInfo, cost_type),
              "glass-win crate: GwinNetworkInfo::cost_type moved");
static_assert(std::is_same<decltype(rs_GwinNetworkInfo::available), decltype(GwinNetworkInfo::available)>::value,
              "glass-win crate: GwinNetworkInfo::available changed type");
static_assert(std::is_same<decltype(rs_GwinNetworkInfo::cost_type), decltype(GwinNetworkInfo::cost_type)>::value,
              "glass-win crate: GwinNetworkInfo::cost_type changed type");
