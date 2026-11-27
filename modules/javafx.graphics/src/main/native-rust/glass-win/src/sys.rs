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

//! The raw OS and vtable calls of `prefs`, each behind a safe function.
//!
//! The `IUISettings3/4/5` and `INetworkInformationStatics` projections of the `windows` crate have no methods, and
//! the class methods that reach them query the interface again on every call; the C at commit `5a709c189d` queried
//! once and then called through the vtable, as these functions do on the caller's interface reference.

use core::ffi::c_void;
use core::ptr::{null, null_mut};

use windows::Foundation::TypedEventHandler;
use windows::Networking::Connectivity::{
    ConnectionProfile, INetworkInformationStatics, NetworkStatusChangedEventHandler,
};
use windows::UI::Color;
use windows::UI::ViewManagement::{
    IUISettings3, IUISettings4, IUISettings5, UIColorType, UISettings, UISettingsAutoHideScrollBarsChangedEventArgs,
};
use windows::Win32::Foundation::{HLOCAL, LocalFree};
use windows::Win32::System::WinRT::{
    IActivationFactory, RO_INIT_SINGLETHREADED, RoActivateInstance, RoGetActivationFactory, RoInitialize,
    RoUninitialize,
};
use windows::core::{HRESULT, IInspectable, Interface, h};

pub(crate) type AutoHideHandler = TypedEventHandler<UISettings, UISettingsAutoHideScrollBarsChangedEventArgs>;

// Neither function has a projection without a feature that brings a whole Win32 namespace; this is the declaration
// the windows crate itself writes for them (Win32_System_Diagnostics_Debug, Win32_Globalization).
windows::core::link!("kernel32.dll" "system" fn FormatMessageW(
    dwflags: u32,
    lpsource: *const c_void,
    dwmessageid: u32,
    dwlanguageid: u32,
    lpbuffer: *mut u16,
    nsize: u32,
    arguments: *const *const i8
) -> u32);
windows::core::link!("kernel32.dll" "system" fn WideCharToMultiByte(
    codepage: u32,
    dwflags: u32,
    lpwidecharstr: *const u16,
    cchwidechar: i32,
    lpmultibytestr: *mut u8,
    cbmultibyte: i32,
    lpdefaultchar: *const u8,
    lpuseddefaultchar: *mut i32
) -> i32);

const FORMAT_MESSAGE_ALLOCATE_BUFFER: u32 = 0x0000_0100;
const FORMAT_MESSAGE_IGNORE_INSERTS: u32 = 0x0000_0200;
const FORMAT_MESSAGE_FROM_SYSTEM: u32 = 0x0000_1000;
/// `MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT)`, the language `_com_error::ErrorMessage` asks for.
const LANG_NEUTRAL_SUBLANG_DEFAULT: u32 = 0x0400;
const CP_ACP: u32 = 0;

/// `RoInitialize(RO_INIT_SINGLETHREADED)` on the calling thread: `Ok` for `S_OK` and `S_FALSE`, else the `HRESULT`.
pub(crate) fn ro_initialize_single_threaded() -> Result<(), HRESULT> {
    // SAFETY: RoInitialize takes its argument by value and has no precondition on the calling thread; a thread already
    // in another apartment gets a failure code.
    unsafe { RoInitialize(RO_INIT_SINGLETHREADED) }.map_err(|error| error.code())
}

/// `RoUninitialize` on the calling thread, which the caller initialised with [`ro_initialize_single_threaded`].
pub(crate) fn ro_uninitialize() {
    // SAFETY: the caller pairs this with its successful RoInitialize on the same thread and holds no WinRT object any
    // more, as the teardown of 5a709c189d did.
    unsafe { RoUninitialize() }
}

/// `RoActivateInstance` of `Windows.UI.ViewManagement.UISettings`, kept as the `IInspectable` it answers.
pub(crate) fn activate_ui_settings() -> Option<IInspectable> {
    // SAFETY: h! is a valid static HSTRING; the result is an owned reference or an error, both handled by the crate.
    unsafe { RoActivateInstance(h!("Windows.UI.ViewManagement.UISettings")) }.ok()
}

/// `RoGetActivationFactory` of `Windows.Networking.Connectivity.NetworkInformation` for `IActivationFactory`.
pub(crate) fn network_information_factory() -> Option<IActivationFactory> {
    // SAFETY: as in activate_ui_settings; the IID passed is IActivationFactory's, the type the result is wrapped in.
    unsafe { RoGetActivationFactory::<IActivationFactory>(h!("Windows.Networking.Connectivity.NetworkInformation")) }
        .ok()
}

/// `add_AutoHideScrollBarsChanged`: the event token, or `None` when the call failed.
pub(crate) fn add_auto_hide_scroll_bars_changed(settings5: &IUISettings5, handler: &AutoHideHandler) -> Option<i64> {
    let mut token = 0i64;
    // SAFETY: settings5 is a live IUISettings5 reference, so its vtable is IUISettings5_Vtbl and as_raw is the `this`
    // it expects; the handler is a live delegate of the type the event takes, which the callee references itself if
    // it keeps it; token is a local i64 the callee writes during the call.
    let hr =
        unsafe { (settings5.vtable().AutoHideScrollBarsChanged)(settings5.as_raw(), handler.as_raw(), &raw mut token) };
    hr.is_ok().then_some(token)
}

/// `remove_AutoHideScrollBarsChanged`; its `HRESULT` is ignored, as at 5a709c189d.
pub(crate) fn remove_auto_hide_scroll_bars_changed(settings5: &IUISettings5, token: i64) {
    // SAFETY: settings5 is a live IUISettings5 reference (see add_auto_hide_scroll_bars_changed); the token is passed
    // by value.
    let _ = unsafe { (settings5.vtable().RemoveAutoHideScrollBarsChanged)(settings5.as_raw(), token) };
}

/// `add_NetworkStatusChanged`: the event token, or `None` when the call failed.
pub(crate) fn add_network_status_changed(
    statics: &INetworkInformationStatics,
    handler: &NetworkStatusChangedEventHandler,
) -> Option<i64> {
    let mut token = 0i64;
    // SAFETY: statics is a live INetworkInformationStatics reference, so its vtable is INetworkInformationStatics_Vtbl
    // and as_raw is its `this`; the handler and token are as in add_auto_hide_scroll_bars_changed.
    let hr = unsafe { (statics.vtable().NetworkStatusChanged)(statics.as_raw(), handler.as_raw(), &raw mut token) };
    hr.is_ok().then_some(token)
}

/// `remove_NetworkStatusChanged`; its `HRESULT` is ignored, as at 5a709c189d.
pub(crate) fn remove_network_status_changed(statics: &INetworkInformationStatics, token: i64) {
    // SAFETY: statics is a live INetworkInformationStatics reference; the token is passed by value.
    let _ = unsafe { (statics.vtable().RemoveNetworkStatusChanged)(statics.as_raw(), token) };
}

/// `IUISettings3::GetColorValue`. Its `HRESULT` is ignored, as at 5a709c189d: a failure leaves what the callee wrote
/// into the zeroed colour.
pub(crate) fn color_value(settings3: &IUISettings3, kind: UIColorType) -> Color {
    let mut color = Color::default();
    // SAFETY: settings3 is a live IUISettings3 reference, so its vtable is IUISettings3_Vtbl; color is a local
    // repr(C) Color (four bytes A, R, G, B) the callee writes during the call.
    let _ = unsafe { (settings3.vtable().GetColorValue)(settings3.as_raw(), kind, &raw mut color) };
    color
}

/// `IUISettings4::get_AdvancedEffectsEnabled` into a byte that starts at 0; its `HRESULT` is ignored, as at
/// 5a709c189d.
pub(crate) fn advanced_effects_enabled(settings4: &IUISettings4) -> u8 {
    let mut value = 0u8;
    // SAFETY: settings4 is a live IUISettings4 reference, so its vtable is IUISettings4_Vtbl; the callee writes one
    // `boolean` byte, and a u8 keeps every byte value it may write defined, where a Rust bool would not.
    let _ = unsafe { (settings4.vtable().AdvancedEffectsEnabled)(settings4.as_raw(), (&raw mut value).cast()) };
    value
}

/// `IUISettings5::get_AutoHideScrollBars` into a byte that starts at 0; its `HRESULT` is ignored, as at 5a709c189d.
pub(crate) fn auto_hide_scroll_bars(settings5: &IUISettings5) -> u8 {
    let mut value = 0u8;
    // SAFETY: as in advanced_effects_enabled, on a live IUISettings5 reference.
    let _ = unsafe { (settings5.vtable().AutoHideScrollBars)(settings5.as_raw(), (&raw mut value).cast()) };
    value
}

/// `INetworkInformationStatics::GetInternetConnectionProfile`: its `HRESULT`, and the profile it wrote, owned whatever
/// the `HRESULT` (a `ComPtr` out-parameter releases what it was given in either case). A success with no profile is
/// visible as `None`.
pub(crate) fn internet_connection_profile(
    statics: &INetworkInformationStatics,
) -> (HRESULT, Option<ConnectionProfile>) {
    let mut raw = null_mut();
    // SAFETY: statics is a live INetworkInformationStatics reference; raw is a local the callee writes an owned
    // IConnectionProfile reference (or NULL) into.
    let hr = unsafe { (statics.vtable().GetInternetConnectionProfile)(statics.as_raw(), &raw mut raw) };
    let profile = if raw.is_null() {
        None
    } else {
        // SAFETY: a non-NULL raw is an owned IConnectionProfile reference, the default interface of
        // ConnectionProfile, which takes it over and releases it once.
        Some(unsafe { ConnectionProfile::from_raw(raw) })
    };
    (hr, profile)
}

/// What `FormatMessageW` answered for an `HRESULT` in [`system_message`].
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum SystemMessage {
    /// The text, up to its first NUL (`wcslen`), not yet trimmed.
    Text(Vec<u16>),
    /// `FormatMessageW` allocated no buffer.
    Missing,
    /// The copy of the text could not be allocated.
    OutOfMemory,
}

/// The system text of `hr` as `_com_error::ErrorMessage` asks for it: `FormatMessageW` with `ALLOCATE_BUFFER`,
/// `FROM_SYSTEM` and `IGNORE_INSERTS`, no source, `MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT)`. The buffer is copied
/// and freed with `LocalFree` before the text is used.
pub(crate) fn system_message(hr: HRESULT) -> SystemMessage {
    let mut buffer: *mut u16 = null_mut();
    let flags = FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS;
    // SAFETY: with FORMAT_MESSAGE_ALLOCATE_BUFFER, lpbuffer is the address of a pointer that receives a LocalAlloc'ed,
    // NUL-terminated buffer, which is what &raw mut buffer is; no source and no arguments are read.
    // The answer is not used: _com_error::ErrorMessage tests the buffer pointer, not the count.
    let _ = unsafe {
        FormatMessageW(
            flags,
            null(),
            hr.0 as u32,
            LANG_NEUTRAL_SUBLANG_DEFAULT,
            (&raw mut buffer).cast(),
            0,
            null(),
        )
    };
    if buffer.is_null() {
        return SystemMessage::Missing;
    }
    let mut len = 0usize;
    // SAFETY: the buffer FormatMessageW allocated is NUL-terminated, and the loop reads no unit past the first NUL.
    while unsafe { buffer.add(len).read() } != 0 {
        len += 1;
    }
    let mut text = Vec::new();
    let copied = text.try_reserve_exact(len).is_ok();
    if copied {
        // SAFETY: the first len units of the buffer are initialised (they precede its NUL), and nothing writes the
        // buffer while the slice lives.
        text.extend_from_slice(unsafe { core::slice::from_raw_parts(buffer, len) });
    }
    // SAFETY: buffer is the LocalAlloc'ed buffer FormatMessageW handed over; it is freed once, after the copy.
    let _ = unsafe { LocalFree(Some(HLOCAL(buffer.cast()))) };
    if copied {
        SystemMessage::Text(text)
    } else {
        SystemMessage::OutOfMemory
    }
}

/// `WideCharToMultiByte(CP_ACP, 0, text, -1, ...)` twice, as `RoException` converts: the length with the NUL, then
/// the text. `text` must end with its only NUL. `None` when either call fails or the buffer cannot be allocated;
/// otherwise the bytes before the first NUL written.
pub(crate) fn wide_to_ansi(text: &[u16]) -> Option<Vec<u8>> {
    if text.last() != Some(&0) || text[..text.len() - 1].contains(&0) {
        return None;
    }
    // SAFETY: text is NUL-terminated (checked above), so -1 makes the call read up to and including that NUL; no
    // output buffer is passed for this length query.
    let len = unsafe { WideCharToMultiByte(CP_ACP, 0, text.as_ptr(), -1, null_mut(), 0, null(), null_mut()) };
    let size = usize::try_from(len).ok().filter(|&size| size > 0)?;
    let mut bytes = Vec::new();
    bytes.try_reserve_exact(size).ok()?;
    bytes.resize(size, 0);
    // SAFETY: as above for text; bytes holds len initialised bytes, which the call may overwrite and never exceeds.
    let written = unsafe {
        WideCharToMultiByte(
            CP_ACP,
            0,
            text.as_ptr(),
            -1,
            bytes.as_mut_ptr(),
            len,
            null(),
            null_mut(),
        )
    };
    let written = usize::try_from(written).ok().filter(|&written| written > 0)?;
    bytes.truncate(written);
    if let Some(nul) = bytes.iter().position(|&byte| byte == 0) {
        bytes.truncate(nul);
    }
    Some(bytes)
}
