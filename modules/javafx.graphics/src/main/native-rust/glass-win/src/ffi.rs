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

//! The C boundary of the crate, with the header's C types: the exports of `glass_win_api.h` implemented in Rust, the
//! functions of `glass_win_prefs.h` the crate defines for the toolkit core, the ones the toolkit core defines for the
//! crate, and every raw pointer that crosses.
//!
//! Every function the C++ or Java calls runs its body in the panic guard of `jfx-rust-support`, with the value the C
//! at commit `5a709c189d` answered on its failure path; a panic never unwinds into C++ or Java.

use core::ffi::{CStr, c_char, c_void};
use core::mem::offset_of;
use core::ptr::null_mut;
use core::sync::atomic::{AtomicPtr, Ordering};

use jfx_rust_support::{guard, guard_handle};

use crate::prefs::{self, KEPT_TYPES, Preferences};
use crate::sys::AutoHideHandler;
use crate::{GWIN_ERR_INVALID_ARG, GWIN_OK, GwinNetworkInfo, GwinPrefsCallbacks, GwinUiSettings};
use windows::Networking::Connectivity::NetworkStatusChangedEventHandler;

// Each probe asserts at compile time the layout that glass_win_api.h gives its struct, which the Java MemoryLayouts
// carry too; abi_check.cpp compares the two types with the header's field by field. The asserts are items of the
// function bodies because cbindgen, which reads every module of the crate but no function body, reports a
// module-level `const _` as a skipped item.

/// `int32_t gwin_sizeof_ui_settings(void)`: `sizeof(GwinUiSettings)`, which a Java test compares with its
/// MemoryLayout. 0 is the failure value: no struct has size 0, so Java reports a layout mismatch.
#[unsafe(no_mangle)]
pub extern "C" fn gwin_sizeof_ui_settings() -> i32 {
    const _: () = {
        assert!(
            size_of::<GwinUiSettings>() == 60,
            "GwinUiSettings: 15 x int32 = 60 bytes in glass_win_api.h"
        );
        assert!(offset_of!(GwinUiSettings, available) == 0, "GwinUiSettings.available");
        assert!(
            offset_of!(GwinUiSettings, colors_valid) == 4,
            "GwinUiSettings.colors_valid"
        );
        assert!(offset_of!(GwinUiSettings, colors) == 8, "GwinUiSettings.colors");
        assert!(
            offset_of!(GwinUiSettings, advanced_effects_valid) == 44,
            "GwinUiSettings.advanced_effects_valid"
        );
        assert!(
            offset_of!(GwinUiSettings, advanced_effects_enabled) == 48,
            "GwinUiSettings.advanced_effects_enabled"
        );
        assert!(
            offset_of!(GwinUiSettings, auto_hide_valid) == 52,
            "GwinUiSettings.auto_hide_valid"
        );
        assert!(
            offset_of!(GwinUiSettings, auto_hide_scroll_bars) == 56,
            "GwinUiSettings.auto_hide_scroll_bars"
        );
    };
    guard(0, || size_of::<GwinUiSettings>() as i32)
}

/// `int32_t gwin_sizeof_network_info(void)`: `sizeof(GwinNetworkInfo)`, as `gwin_sizeof_ui_settings`.
#[unsafe(no_mangle)]
pub extern "C" fn gwin_sizeof_network_info() -> i32 {
    const _: () = {
        assert!(
            size_of::<GwinNetworkInfo>() == 8,
            "GwinNetworkInfo: 8 bytes in glass_win_api.h"
        );
        assert!(offset_of!(GwinNetworkInfo, available) == 0, "GwinNetworkInfo.available");
        assert!(offset_of!(GwinNetworkInfo, cost_type) == 4, "GwinNetworkInfo.cost_type");
    };
    guard(0, || size_of::<GwinNetworkInfo>() as i32)
}

// ---- the preferences callback table ----

// The slot and `user` of the installed GwinPrefsCallbacks: g_prefsCallbacks and g_prefsUser of 5a709c189d, which had
// no lock by design (glass_win_api.h, gwin_prefs_set_callbacks): Java installs the table before the toolkit thread
// exists and clears it on that thread after its last read. Release and Acquire keep the C's order, slot before
// `user`, at no cost on x86-64, where both are the plain moves the C compiled to.
static PREFERENCES_CHANGED: AtomicPtr<c_void> = AtomicPtr::new(null_mut());
static PREFERENCES_USER: AtomicPtr<c_void> = AtomicPtr::new(null_mut());

/// `int32_t gwin_prefs_set_callbacks(const GwinPrefsCallbacks* cb, void* user)`: a NULL `cb` clears the slot, then
/// `user`; otherwise the slot is copied out of `*cb`, then `user` is stored. `GWIN_OK`; `GWIN_ERR_INVALID_ARG` only
/// after a caught panic.
#[unsafe(no_mangle)]
pub extern "C" fn gwin_prefs_set_callbacks(cb: *const GwinPrefsCallbacks, user: *mut c_void) -> i32 {
    guard(GWIN_ERR_INVALID_ARG, || {
        if cb.is_null() {
            PREFERENCES_CHANGED.store(null_mut(), Ordering::Release);
            PREFERENCES_USER.store(null_mut(), Ordering::Release);
        } else {
            // SAFETY: cb is not NULL, and glass_win_api.h makes it a GwinPrefsCallbacks the caller owns for the
            // duration of this call; it is read once, at any alignment, and never kept.
            let table = unsafe { cb.read_unaligned() };
            let slot = table
                .preferences_changed
                .map_or(null_mut(), |callback| callback as *mut c_void);
            PREFERENCES_CHANGED.store(slot, Ordering::Release);
            PREFERENCES_USER.store(user, Ordering::Release);
        }
        GWIN_OK
    })
}

/// `preferences_changed(user, types) != 0` with the installed slot, loaded once, and `user`; false without a slot.
pub(crate) fn preferences_changed(types: i32) -> bool {
    let slot = PREFERENCES_CHANGED.load(Ordering::Acquire);
    if slot.is_null() {
        return false;
    }
    // SAFETY: every non-NULL value of PREFERENCES_CHANGED is a preferences_changed slot of exactly this type that
    // gwin_prefs_set_callbacks stored.
    let callback = unsafe { core::mem::transmute::<*mut c_void, unsafe extern "C" fn(*mut c_void, i32) -> i32>(slot) };
    let user = PREFERENCES_USER.load(Ordering::Acquire);
    // SAFETY: glass_win_api.h makes Java keep the upcall stub valid until the table is cleared on the toolkit thread
    // after gwin_run_loop has returned, and this runs on the toolkit thread before then; `user` goes back unchanged.
    unsafe { callback(user, types) != 0 }
}

// ---- the queries ----

/// `int32_t gwin_prefs_query_ui_settings(GwinUiSettings* out)`: `GWIN_ERR_INVALID_ARG` for a NULL `out`; without a
/// live toolkit every byte zero and `GWIN_OK`; otherwise every byte the live handle's answer and `GWIN_OK`.
/// `GWIN_ERR_INVALID_ARG` with nothing written after a caught panic, in this call or in an earlier query that poisoned
/// the live handle.
#[unsafe(no_mangle)]
pub extern "C" fn gwin_prefs_query_ui_settings(out: *mut GwinUiSettings) -> i32 {
    guard(GWIN_ERR_INVALID_ARG, || {
        if out.is_null() {
            return GWIN_ERR_INVALID_ARG;
        }
        let Some(settings) = query_live(GwinUiSettings::default(), Preferences::collect_ui_settings) else {
            return GWIN_ERR_INVALID_ARG;
        };
        // SAFETY: out is not NULL, and glass_win_api.h makes it a GwinUiSettings the caller owns for this call; the
        // write covers all 60 bytes (no padding) at any alignment, and the pointer is not kept.
        unsafe { out.write_unaligned(settings) };
        GWIN_OK
    })
}

/// `int32_t gwin_prefs_query_network(GwinNetworkInfo* out)`, as `gwin_prefs_query_ui_settings`.
#[unsafe(no_mangle)]
pub extern "C" fn gwin_prefs_query_network(out: *mut GwinNetworkInfo) -> i32 {
    guard(GWIN_ERR_INVALID_ARG, || {
        if out.is_null() {
            return GWIN_ERR_INVALID_ARG;
        }
        let Some(info) = query_live(GwinNetworkInfo::default(), Preferences::collect_network_info) else {
            return GWIN_ERR_INVALID_ARG;
        };
        // SAFETY: as in gwin_prefs_query_ui_settings, for the 8 bytes of a GwinNetworkInfo.
        unsafe { out.write_unaligned(info) };
        GWIN_OK
    })
}

/// `collect` on the live handle; `none` without one; `None` when the handle is poisoned or `collect` panicked, which
/// poisons it. The queries call no Java and run on the toolkit thread, so the handle outlives the call.
fn query_live<T: Copy>(none: T, collect: impl FnOnce(&Preferences) -> T) -> Option<T> {
    let handle = live_handle();
    // SAFETY: a non-NULL live handle is the one glass_prefs_create answered for the GlassApplication that is the
    // toolkit's instance; only that instance's destructor destroys it, on the toolkit thread, which these exports run
    // on and which is inside this call.
    match unsafe { handle.cast::<Preferences>().as_ref() } {
        None => Some(none),
        Some(preferences) => guard_handle(&preferences.poison, None, || Some(collect(preferences))),
    }
}

// ---- the functions of glass_win_prefs.h the crate defines ----

/// `void* glass_prefs_create(void)`: what the constructor of `PlatformSupport` did at 5a709c189d. A handle also when
/// `RoInitialize` or an activation failed; NULL only after a caught panic.
#[unsafe(no_mangle)]
pub extern "C" fn glass_prefs_create() -> *mut c_void {
    guard(null_mut(), || Box::into_raw(Box::new(Preferences::create())).cast())
}

/// `void glass_prefs_destroy(void* handle)`: the teardown (`Drop for Preferences`). NULL does nothing; a poisoned
/// handle is left as it is.
#[unsafe(no_mangle)]
pub extern "C" fn glass_prefs_destroy(handle: *mut c_void) {
    guard((), || {
        if !usable(handle) {
            return;
        }
        // SAFETY: handle is a non-NULL value of glass_prefs_create, which made it with Box::into_raw, and its C++
        // holder calls this once, last; no reference to it is alive here.
        drop(unsafe { Box::from_raw(handle.cast::<Preferences>()) });
    })
}

/// `int32_t glass_prefs_update(void* handle, int32_t types)`: `updatePreferences`, 0 for a NULL or poisoned handle and
/// after a caught panic, which poisons nothing ([`update_on`]).
#[unsafe(no_mangle)]
pub extern "C" fn glass_prefs_update(handle: *mut c_void, types: i32) -> i32 {
    guard(0, || update_on(handle, types))
}

/// `int32_t glass_prefs_setting_changed(void* handle, uintptr_t wparam, intptr_t lparam)`: `onSettingChanged`, 0 for
/// a NULL or poisoned handle and after a caught panic, which poisons nothing, as in [`glass_prefs_update`].
#[unsafe(no_mangle)]
pub extern "C" fn glass_prefs_setting_changed(handle: *mut c_void, wparam: usize, lparam: isize) -> i32 {
    guard(0, || {
        if !usable(handle) {
            return 0;
        }
        let text = core::ptr::with_exposed_provenance::<u16>(lparam as usize);
        let types = prefs::setting_changed_types(wparam, lparam, |index| {
            // SAFETY: setting_changed_types calls this only for a non-zero lParam, which for WM_SETTINGCHANGE is a
            // NUL-terminated UTF-16 string valid during the message, and reads unit `index` only after the units
            // before it matched non-NUL units of "ImmersiveColorSet": never past the terminator, as wcscmp.
            unsafe { text.add(index).read_unaligned() }
        });
        if types == 0 {
            0
        } else {
            prefs::update_preferences(types)
        }
    })
}

/// `void glass_prefs_run_deferred(void)`: `RunDeferredPreferences`.
#[unsafe(no_mangle)]
pub extern "C" fn glass_prefs_run_deferred() {
    guard((), || prefs::run_deferred(&KEPT_TYPES))
}

/// `void glass_prefs_discard_deferred(void)`: `DiscardDeferredPreferences`.
#[unsafe(no_mangle)]
pub extern "C" fn glass_prefs_discard_deferred() {
    guard((), || KEPT_TYPES.discard())
}

/// `updatePreferences` on `handle`, 0 when it is NULL or poisoned. No reference to the handle is held across the
/// upcall, which may end the toolkit and so destroy the handle (a listener calling Platform.exit()); a panic in the
/// upcall therefore poisons nothing, and the update reads no state of the handle that a panic could leave behind.
pub(crate) fn update_on(handle: *mut c_void, types: i32) -> i32 {
    if !usable(handle) {
        return 0;
    }
    prefs::update_preferences(types)
}

/// Whether `handle` is a handle of [`glass_prefs_create`] that no panic poisoned.
fn usable(handle: *mut c_void) -> bool {
    // SAFETY: a non-NULL handle is a live value of glass_prefs_create (its C++ holder passes nothing else, and only
    // its destructor destroys it); the reference ends with this function.
    match unsafe { handle.cast::<Preferences>().as_ref() } {
        None => false,
        Some(preferences) => guard_handle(&preferences.poison, false, || true),
    }
}

// ---- the functions of glass_win_prefs.h the toolkit core defines ----

unsafe extern "C" {
    fn glass_prefs_post(types: i32, published: *mut i32) -> i32;
    fn glass_prefs_arm_timer();
    fn glass_prefs_live_handle() -> *mut c_void;
    fn glass_prefs_stderr(text: *const c_char);
}

#[cfg(feature = "test-hooks")]
unsafe extern "C" {
    fn glass_prefs_test_record_delegate_call(which: i32);
    fn glass_prefs_test_log_event(code: i32);
    fn glass_prefs_test_count(which: i32);
}

/// `GlassApplication::PostToToolkit(WM_PREFERENCES_CHANGED, types, 0, &published)`: whether it posted, and in
/// `published` whether a toolkit window was published.
pub(crate) fn post(types: i32, published: &mut i32) -> bool {
    // SAFETY: glass_prefs_post is noexcept, callable from any thread, and writes *published during the call only.
    unsafe { glass_prefs_post(types, published) != 0 }
}

/// `GlassApplication::ArmPreferencesTimer()`.
pub(crate) fn arm_timer() {
    // SAFETY: glass_prefs_arm_timer is noexcept, takes nothing and is callable from any thread.
    unsafe { glass_prefs_arm_timer() }
}

/// The handle of the toolkit's instance while it is set, else NULL.
pub(crate) fn live_handle() -> *mut c_void {
    // SAFETY: glass_prefs_live_handle is noexcept and takes nothing.
    unsafe { glass_prefs_live_handle() }
}

/// `fputs(text, stderr)` in the C++ toolkit core: the CRT stream the C's `fprintf` wrote to, in whatever mode it is.
pub(crate) fn write_stderr(text: &[u8]) {
    if let Ok(text) = CStr::from_bytes_with_nul(text) {
        // SAFETY: text is NUL-terminated and alive during the call, which reads it and keeps nothing.
        unsafe { glass_prefs_stderr(text.as_ptr()) }
    }
}

/// `GwinTestRecordDelegateCall(which)` of the test build.
#[cfg(feature = "test-hooks")]
pub(crate) fn test_record_delegate_call(which: i32) {
    // SAFETY: the probe is noexcept and callable from any thread.
    unsafe { glass_prefs_test_record_delegate_call(which) }
}

/// `GwinTestLogEvent(code)` of the test build.
#[cfg(feature = "test-hooks")]
pub(crate) fn test_log_event(code: i32) {
    // SAFETY: the probe is noexcept and callable from any thread.
    unsafe { glass_prefs_test_log_event(code) }
}

/// `GwinTestCount(which)` of the test build.
#[cfg(feature = "test-hooks")]
pub(crate) fn test_count(which: i32) {
    // SAFETY: the probe is noexcept and callable from any thread.
    unsafe { glass_prefs_test_count(which) }
}

// The release build's probes are empty, as glass_win_test.h's are: no probe call and no hook code in its archive
#[cfg(not(feature = "test-hooks"))]
pub(crate) fn test_record_delegate_call(_which: i32) {}

#[cfg(not(feature = "test-hooks"))]
pub(crate) fn test_log_event(_code: i32) {}

#[cfg(not(feature = "test-hooks"))]
pub(crate) fn test_count(_which: i32) {}

// ---- the kept delegates of the test build ----

/// `KeepDelegate`: while capture is on, the reference to the auto-hide delegate an event source keeps.
#[cfg(feature = "test-hooks")]
pub(crate) fn keep_auto_hide_delegate(handler: &AutoHideHandler) {
    hooks::keep(&hooks::KEPT_AUTO_HIDE, handler);
}

/// As [`keep_auto_hide_delegate`], for the network delegate.
#[cfg(feature = "test-hooks")]
pub(crate) fn keep_network_delegate(handler: &NetworkStatusChangedEventHandler) {
    hooks::keep(&hooks::KEPT_NETWORK, handler);
}

#[cfg(not(feature = "test-hooks"))]
pub(crate) fn keep_auto_hide_delegate(_handler: &AutoHideHandler) {}

#[cfg(not(feature = "test-hooks"))]
pub(crate) fn keep_network_delegate(_handler: &NetworkStatusChangedEventHandler) {}

#[cfg(feature = "test-hooks")]
mod hooks {
    use core::ffi::c_void;
    use core::ptr::null_mut;
    use core::sync::atomic::{AtomicBool, AtomicPtr, Ordering};

    use jfx_rust_support::guard;
    use windows::Networking::Connectivity::NetworkStatusChangedEventHandler;
    use windows::UI::ViewManagement::{UISettings, UISettingsAutoHideScrollBarsChangedEventArgs};
    use windows::core::{IUnknown, Interface};

    use crate::sys::AutoHideHandler;
    use crate::{GWIN_ERR_INVALID_ARG, GWIN_OK, GWIN_TEST_DELEGATE_AUTO_HIDE, GWIN_TEST_DELEGATE_NETWORK};

    // s_captureDelegates, s_keptAutoHide and s_keptNetwork of 5a709c189d, which the C read as volatile (acquire with
    // MSVC on x86-64) and wrote with InterlockedExchange: SeqCst for the writes and swaps, Acquire for the loads,
    // which pairs with the swap that published the delegate so an invoker sees it whole.
    static CAPTURE: AtomicBool = AtomicBool::new(false);
    pub(super) static KEPT_AUTO_HIDE: AtomicPtr<c_void> = AtomicPtr::new(null_mut());
    pub(super) static KEPT_NETWORK: AtomicPtr<c_void> = AtomicPtr::new(null_mut());

    /// `KeepDelegate`: while capture is on, one more reference to `handler` replaces the slot's, which is released.
    pub(super) fn keep<T: Interface>(slot: &AtomicPtr<c_void>, handler: &T) {
        if !CAPTURE.load(Ordering::Acquire) {
            return;
        }
        release(slot.swap(handler.clone().into_raw(), Ordering::SeqCst));
    }

    /// Releases a reference a slot owned; NULL is nothing.
    fn release(raw: *mut c_void) {
        if !raw.is_null() {
            // SAFETY: every non-NULL value of a kept slot is a reference into_raw handed to the slot, and the swap that
            // took it out of the slot handed it here, once.
            drop(unsafe { IUnknown::from_raw(raw) });
        }
    }

    /// `int32_t gwin_test_prefs_capture(int32_t on)`: capture on or off; off releases both kept delegates. `GWIN_OK`;
    /// `GWIN_ERR_INVALID_ARG` only after a caught panic.
    #[unsafe(no_mangle)]
    pub extern "C" fn gwin_test_prefs_capture(on: i32) -> i32 {
        guard(GWIN_ERR_INVALID_ARG, || {
            CAPTURE.store(on != 0, Ordering::SeqCst);
            if on == 0 {
                release(KEPT_AUTO_HIDE.swap(null_mut(), Ordering::SeqCst));
                release(KEPT_NETWORK.swap(null_mut(), Ordering::SeqCst));
            }
            GWIN_OK
        })
    }

    /// `int32_t gwin_test_prefs_invoke(int32_t which)`: the kept delegate's `Invoke(NULL, NULL)` on the calling
    /// thread, its `HRESULT` as `int32_t`; `GWIN_ERR_INVALID_ARG` for an unknown `which` or an empty slot.
    #[unsafe(no_mangle)]
    pub extern "C" fn gwin_test_prefs_invoke(which: i32) -> i32 {
        guard(GWIN_ERR_INVALID_ARG, || match which {
            GWIN_TEST_DELEGATE_AUTO_HIDE => {
                let raw = KEPT_AUTO_HIDE.load(Ordering::Acquire);
                // SAFETY: a non-NULL value is a reference to an AutoHideHandler the slot owns (keep), which
                // glass_win_api.h keeps alive during this call: gwin_test_prefs_capture must not run concurrently; the
                // borrow takes no reference of its own and ends with this arm.
                match unsafe { AutoHideHandler::from_raw_borrowed(&raw) } {
                    Some(handler) => hresult(handler.Invoke(
                        None::<&UISettings>,
                        None::<&UISettingsAutoHideScrollBarsChangedEventArgs>,
                    )),
                    None => GWIN_ERR_INVALID_ARG,
                }
            }
            GWIN_TEST_DELEGATE_NETWORK => {
                let raw = KEPT_NETWORK.load(Ordering::Acquire);
                // SAFETY: as for the auto-hide slot, a reference to a NetworkStatusChangedEventHandler.
                match unsafe { NetworkStatusChangedEventHandler::from_raw_borrowed(&raw) } {
                    Some(handler) => hresult(handler.Invoke(None::<&windows::core::IInspectable>)),
                    None => GWIN_ERR_INVALID_ARG,
                }
            }
            _ => GWIN_ERR_INVALID_ARG,
        })
    }

    /// The `HRESULT` behind the crate's `Result` of a delegate's `Invoke`.
    fn hresult(result: windows::core::Result<()>) -> i32 {
        match result {
            Ok(()) => 0,
            Err(error) => error.code().0,
        }
    }
}

/// The toolkit core's side of `glass_win_prefs.h` for the crate's unit tests: recording doubles of the functions the
/// C++ defines, on the calling test's thread.
#[cfg(test)]
pub(crate) mod doubles {
    use core::ffi::{CStr, c_char, c_void};
    use core::ptr::null_mut;
    use std::cell::{Cell, RefCell};
    use std::collections::VecDeque;
    use std::sync::{Mutex, MutexGuard};

    use crate::GwinPrefsCallbacks;
    use crate::prefs::Preferences;

    #[derive(Clone, Debug, PartialEq, Eq)]
    pub(crate) enum Call {
        Post(i32),
        ArmTimer,
        LiveHandle,
        Stderr(Vec<u8>),
        Changed(i32),
        RecordDelegate(i32),
        LogEvent(i32),
        Count(i32),
    }

    impl Call {
        /// A call of a probe of the test build.
        pub(crate) fn is_probe(&self) -> bool {
            matches!(self, Call::RecordDelegate(_) | Call::LogEvent(_) | Call::Count(_))
        }
    }

    thread_local! {
        static CALLS: RefCell<Vec<Call>> = const { RefCell::new(Vec::new()) };
        static POSTS: RefCell<VecDeque<(i32, i32)>> = const { RefCell::new(VecDeque::new()) };
        static CHANGED: RefCell<VecDeque<i32>> = const { RefCell::new(VecDeque::new()) };
        static LIVE: Cell<*mut c_void> = const { Cell::new(null_mut()) };
    }

    /// The callback table is process-wide: a test that installs one or makes an update holds this.
    static TABLE: Mutex<()> = Mutex::new(());

    /// The user pointer of the recording table: never dereferenced.
    const USER: *mut c_void = 0x7FFE_1234_5678_usize as *mut c_void;

    fn record(call: Call) {
        CALLS.with(|calls| calls.borrow_mut().push(call));
    }

    pub(crate) fn take_calls() -> Vec<Call> {
        CALLS.with(|calls| calls.take())
    }

    /// The answers of the next posts, `(posted, published)` each.
    pub(crate) fn script_posts(answers: &[(i32, i32)]) {
        POSTS.with(|posts| posts.borrow_mut().extend(answers.iter().copied()));
    }

    /// The answers of the next calls of the recording table's slot; 0 once they are used up.
    pub(crate) fn script_changed(answers: &[i32]) {
        CHANGED.with(|changed| changed.borrow_mut().extend(answers.iter().copied()));
    }

    pub(crate) fn lock_table() -> MutexGuard<'static, ()> {
        TABLE.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    extern "C" fn recording_changed(user: *mut c_void, types: i32) -> i32 {
        record(if user == USER {
            Call::Changed(types)
        } else {
            Call::Changed(i32::MIN)
        });
        CHANGED.with(|changed| changed.borrow_mut().pop_front().unwrap_or(0))
    }

    pub(crate) fn install_recording_table() {
        let table = GwinPrefsCallbacks {
            preferences_changed: Some(recording_changed),
        };
        assert_eq!(super::gwin_prefs_set_callbacks(&table, USER), crate::GWIN_OK);
    }

    pub(crate) fn clear_table() {
        assert_eq!(
            super::gwin_prefs_set_callbacks(core::ptr::null(), null_mut()),
            crate::GWIN_OK
        );
    }

    /// Runs `body` while `preferences` is this thread's live handle, then destroys it.
    pub(crate) fn with_live_handle(preferences: Preferences, body: impl FnOnce()) {
        let handle = Box::into_raw(Box::new(preferences)).cast::<c_void>();
        LIVE.with(|live| live.set(handle));
        body();
        LIVE.with(|live| live.set(null_mut()));
        super::glass_prefs_destroy(handle);
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn glass_prefs_post(types: i32, published: *mut i32) -> i32 {
        record(Call::Post(types));
        let (posted, is_published) = POSTS.with(|posts| posts.borrow_mut().pop_front().unwrap_or((0, 0)));
        // SAFETY: the crate passes a live i32 it owns (ffi::post).
        unsafe { published.write(is_published) };
        posted
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn glass_prefs_arm_timer() {
        record(Call::ArmTimer);
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn glass_prefs_live_handle() -> *mut c_void {
        record(Call::LiveHandle);
        LIVE.with(Cell::get)
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn glass_prefs_stderr(text: *const c_char) {
        // SAFETY: the crate passes a NUL-terminated string alive during the call (ffi::write_stderr).
        record(Call::Stderr(unsafe { CStr::from_ptr(text) }.to_bytes().to_vec()));
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn glass_prefs_test_record_delegate_call(which: i32) {
        record(Call::RecordDelegate(which));
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn glass_prefs_test_log_event(code: i32) {
        record(Call::LogEvent(code));
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn glass_prefs_test_count(which: i32) {
        record(Call::Count(which));
    }
}

#[cfg(test)]
mod tests {
    use super::doubles::{self, Call};
    use super::*;
    use crate::{GWIN_PT_ALL, GWIN_PT_UI_SETTINGS};

    #[test]
    fn gwin_sizeof_ui_settings_returns_the_header_size() {
        assert_eq!(gwin_sizeof_ui_settings(), 60);
    }

    #[test]
    fn gwin_sizeof_network_info_returns_the_header_size() {
        assert_eq!(gwin_sizeof_network_info(), 8);
    }

    #[test]
    fn a_null_out_is_invalid_and_without_a_toolkit_every_byte_is_zero() {
        assert_eq!(gwin_prefs_query_ui_settings(null_mut()), GWIN_ERR_INVALID_ARG);
        assert_eq!(gwin_prefs_query_network(null_mut()), GWIN_ERR_INVALID_ARG);
        assert_eq!(
            doubles::take_calls(),
            vec![],
            "a NULL out is rejected before the live handle is asked for"
        );
        let mut ui = [0xA5u8; 61];
        // An odd address: the struct is written at any alignment
        assert_eq!(gwin_prefs_query_ui_settings(ui[1..].as_mut_ptr().cast()), GWIN_OK);
        assert_eq!(ui[0], 0xA5);
        assert!(ui[1..].iter().all(|&byte| byte == 0));
        let mut network = [0xA5u8; 8];
        assert_eq!(gwin_prefs_query_network(network.as_mut_ptr().cast()), GWIN_OK);
        assert_eq!(network, [0; 8]);
        assert_eq!(doubles::take_calls(), vec![Call::LiveHandle, Call::LiveHandle]);
    }

    #[test]
    fn a_handle_without_winrt_answers_zero_with_available_unset() {
        doubles::with_live_handle(Preferences::without_winrt(), || {
            let mut ui = GwinUiSettings {
                available: 7,
                ..GwinUiSettings::default()
            };
            assert_eq!(gwin_prefs_query_ui_settings(&mut ui), GWIN_OK);
            assert_eq!(ui, GwinUiSettings::default());
        });
        assert_eq!(doubles::take_calls(), vec![Call::LiveHandle]);
    }

    #[test]
    fn the_seam_answers_0_for_a_null_handle_and_destroy_ignores_it() {
        let _table = doubles::lock_table();
        doubles::install_recording_table();
        assert_eq!(glass_prefs_update(null_mut(), GWIN_PT_UI_SETTINGS), 0);
        assert_eq!(glass_prefs_setting_changed(null_mut(), 0x0043, 0), 0);
        glass_prefs_destroy(null_mut());
        doubles::clear_table();
        assert_eq!(doubles::take_calls(), vec![]);
    }

    #[test]
    fn set_callbacks_copies_the_slot_by_value_and_a_null_table_or_slot_clears_it() {
        let _table = doubles::lock_table();
        let handle = Box::into_raw(Box::new(Preferences::without_winrt())).cast::<c_void>();
        doubles::install_recording_table();
        doubles::script_changed(&[1]);
        assert_eq!(glass_prefs_update(handle, GWIN_PT_ALL), 1);
        let empty = GwinPrefsCallbacks {
            preferences_changed: None,
        };
        assert_eq!(gwin_prefs_set_callbacks(&empty, 0x10 as *mut c_void), GWIN_OK);
        assert_eq!(glass_prefs_update(handle, GWIN_PT_UI_SETTINGS), 0);
        doubles::install_recording_table();
        doubles::clear_table();
        assert_eq!(glass_prefs_update(handle, GWIN_PT_UI_SETTINGS), 0);
        glass_prefs_destroy(handle);
        assert_eq!(doubles::take_calls(), vec![Call::Changed(GWIN_PT_ALL)]);
    }

    #[test]
    fn setting_changed_reads_the_text_and_updates_with_its_types() {
        let _table = doubles::lock_table();
        let handle = Box::into_raw(Box::new(Preferences::without_winrt())).cast::<c_void>();
        doubles::install_recording_table();
        doubles::script_changed(&[1, 0, 1]);
        let text: Vec<u16> = "ImmersiveColorSet\0".encode_utf16().collect();
        let other: Vec<u16> = "Policy\0".encode_utf16().collect();
        assert_eq!(glass_prefs_setting_changed(handle, 0, text.as_ptr() as isize), 1);
        assert_eq!(glass_prefs_setting_changed(handle, 0x1043, 0), 0);
        assert_eq!(glass_prefs_setting_changed(handle, 0, other.as_ptr() as isize), 0);
        assert_eq!(
            glass_prefs_setting_changed(handle, 0x1_0000_0043, other.as_ptr() as isize),
            1
        );
        doubles::clear_table();
        glass_prefs_destroy(handle);
        assert_eq!(
            doubles::take_calls(),
            vec![Call::Changed(4), Call::Changed(2), Call::Changed(6)]
        );
    }

    #[test]
    fn the_kept_word_is_discarded_by_the_seam() {
        KEPT_TYPES.keep(GWIN_PT_UI_SETTINGS);
        glass_prefs_discard_deferred();
        glass_prefs_run_deferred();
        assert_eq!(
            doubles::take_calls(),
            vec![],
            "nothing kept after a discard: no live handle asked for"
        );
    }

    #[test]
    fn write_stderr_hands_over_the_bytes_without_the_terminator() {
        write_stderr(b"RoInitialize failed: x\0");
        write_stderr(b"no terminator");
        write_stderr(b"inner\0nul\0");
        assert_eq!(
            doubles::take_calls(),
            vec![Call::Stderr(b"RoInitialize failed: x".to_vec())]
        );
    }
}
