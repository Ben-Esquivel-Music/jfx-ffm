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

//! The Rust part of glass.dll, behind the unchanged C ABI of `glass_win_api.h` (`GLASS_WIN_ABI_VERSION` 7).
//!
//! `prefs` is the WinRT half of the platform preferences: the activation of `UISettings` and `NetworkInformation`,
//! their two event sinks, the teardown before `RoUninitialize`, the two queries and the delivery of preference types
//! to `GwinPrefsCallbacks`. The C++ toolkit core reaches it through the functions of `glass_win_prefs.h`, and it
//! reaches the toolkit core through the functions that header declares for the C++ to define. `ffi` is that boundary
//! and the exports, `sys` the raw OS and vtable calls; the rest is safe Rust.
//!
//! Windows only: on Linux and macOS the crate is empty, a workspace member that no library links.

#![cfg(windows)]

mod ffi;
mod prefs;
mod sys;

/// `GWIN_UI_COLOR_COUNT` of `glass_win_api.h`: the number of entries of `GwinUiSettings::colors`.
pub const GWIN_UI_COLOR_COUNT: usize = 9;

/// `enum GwinStatus` of `glass_win_api.h`: the two values the preferences exports answer.
pub const GWIN_OK: i32 = 0;
pub const GWIN_ERR_INVALID_ARG: i32 = -1;

/// `enum GwinPreferenceType` of `glass_win_api.h`: the bits `GwinPrefsCallbacks::preferences_changed` receives.
pub const GWIN_PT_SYSTEM_COLORS: i32 = 1;
pub const GWIN_PT_SYSTEM_PARAMS: i32 = 2;
pub const GWIN_PT_UI_SETTINGS: i32 = 4;
pub const GWIN_PT_NETWORK_INFORMATION: i32 = 8;
pub const GWIN_PT_ALL: i32 = 15;

/// `enum GwinNetworkCost` of `glass_win_api.h`: this ABI's values, not those of WinRT's `NetworkCostType`.
pub const GWIN_NET_COST_UNKNOWN: i32 = 0;
pub const GWIN_NET_COST_UNRESTRICTED: i32 = 1;
pub const GWIN_NET_COST_VARIABLE: i32 = 2;
pub const GWIN_NET_COST_FIXED: i32 = 3;

/// `enum GwinTestDelegate` of `glass_win_api.h`: which of the two sinks a test hook or a probe names.
pub const GWIN_TEST_DELEGATE_AUTO_HIDE: i32 = 0;
pub const GWIN_TEST_DELEGATE_NETWORK: i32 = 1;

/// `enum GwinTestEvent` of `glass_win_api.h`: the teardown steps the test build logs, in their order.
pub const GWIN_TEST_EVENT_REMOVE_AUTO_HIDE: i32 = 1;
pub const GWIN_TEST_EVENT_REMOVE_NETWORK: i32 = 2;
pub const GWIN_TEST_EVENT_RELEASE_UI_SETTINGS: i32 = 3;
pub const GWIN_TEST_EVENT_RELEASE_NETWORK_STATICS: i32 = 4;
pub const GWIN_TEST_EVENT_RELEASE_NETWORK_FACTORY: i32 = 5;
pub const GWIN_TEST_EVENT_RO_UNINITIALIZE: i32 = 6;

/// The two counters of `enum GwinTestCounter` (`glass_win_api.h`) that the preferences code counts.
pub const GWIN_TEST_COUNTER_PREFS_DEFERRED: i32 = 10;
pub const GWIN_TEST_COUNTER_PREFS_DEFERRED_RUN: i32 = 11;

/// `GwinUiSettings` of `glass_win_api.h`; `gwin_sizeof_ui_settings` asserts its layout.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct GwinUiSettings {
    pub available: i32,
    pub colors_valid: i32,
    pub colors: [u32; GWIN_UI_COLOR_COUNT],
    pub advanced_effects_valid: i32,
    pub advanced_effects_enabled: i32,
    pub auto_hide_valid: i32,
    pub auto_hide_scroll_bars: i32,
}

/// `GwinNetworkInfo` of `glass_win_api.h`; `gwin_sizeof_network_info` asserts its layout.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct GwinNetworkInfo {
    pub available: i32,
    pub cost_type: i32,
}

/// `GwinPrefsCallbacks` of `glass_win_api.h`: `gwin_prefs_set_callbacks` copies the one slot out of the caller's
/// table.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct GwinPrefsCallbacks {
    pub preferences_changed: Option<unsafe extern "C" fn(user: *mut core::ffi::c_void, types: i32) -> i32>,
}
