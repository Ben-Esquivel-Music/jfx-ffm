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

//! The WinRT half of the Windows platform preferences, ported from the C++ of commit `5a709c189d`: the same calls,
//! in the same order, with the same tolerated failures and partial states.
//!
//! The toolkit core owns one [`Preferences`] per `GlassApplication` (`glass_prefs_create` in its member
//! initialisation, `glass_prefs_destroy` in its destructor) and passes it to [`update_preferences`] and
//! [`setting_changed_types`] from its window procedure. The two sinks only post their `GWIN_PT_*` bits to the
//! toolkit window; `preferences_changed` runs on the toolkit thread only.

use core::sync::atomic::{AtomicBool, AtomicI32, Ordering};

use jfx_rust_support::{Poison, guard};
use windows::Networking::Connectivity::{
    INetworkInformationStatics, NetworkCostType, NetworkStatusChangedEventHandler,
};
use windows::UI::Color;
use windows::UI::ViewManagement::{IUISettings3, IUISettings4, IUISettings5, UIColorType};
use windows::Win32::System::WinRT::IActivationFactory;
use windows::core::{HRESULT, IInspectable, Interface};

use crate::sys::{self, AutoHideHandler, SystemMessage};
use crate::{
    GWIN_NET_COST_FIXED, GWIN_NET_COST_UNKNOWN, GWIN_NET_COST_UNRESTRICTED, GWIN_NET_COST_VARIABLE, GWIN_PT_ALL,
    GWIN_PT_NETWORK_INFORMATION, GWIN_PT_SYSTEM_PARAMS, GWIN_PT_UI_SETTINGS, GWIN_TEST_COUNTER_PREFS_DEFERRED,
    GWIN_TEST_COUNTER_PREFS_DEFERRED_RUN, GWIN_TEST_DELEGATE_AUTO_HIDE, GWIN_TEST_DELEGATE_NETWORK,
    GWIN_TEST_EVENT_RELEASE_NETWORK_FACTORY, GWIN_TEST_EVENT_RELEASE_NETWORK_STATICS,
    GWIN_TEST_EVENT_RELEASE_UI_SETTINGS, GWIN_TEST_EVENT_REMOVE_AUTO_HIDE, GWIN_TEST_EVENT_REMOVE_NETWORK,
    GWIN_TEST_EVENT_RO_UNINITIALIZE, GwinNetworkInfo, GwinUiSettings, ffi,
};

/// Whether a successful `RoInitialize` is in force: the process-wide flag of `RoActivationSupport.cpp` at
/// `5a709c189d`. Set after a successful `RoInitialize`, cleared by the teardown; while it is set a later create does
/// not initialise again. Create and teardown both run on the toolkit thread, so Relaxed orders nothing a second thread
/// reads; the C used a plain bool.
static RO_INITIALIZED: AtomicBool = AtomicBool::new(false);

/// The order of the colours in `GwinUiSettings::colors` (`glass_win_api.h`).
const COLOR_TYPES: [UIColorType; crate::GWIN_UI_COLOR_COUNT] = [
    UIColorType::Background,
    UIColorType::Foreground,
    UIColorType::AccentDark3,
    UIColorType::AccentDark2,
    UIColorType::AccentDark1,
    UIColorType::Accent,
    UIColorType::AccentLight1,
    UIColorType::AccentLight2,
    UIColorType::AccentLight3,
];

/// The prefix of the line a failed `RoInitialize` writes to stderr.
const RO_INITIALIZE_FAILED: &[u8] = b"RoInitialize failed: ";

const SPI_SETHIGHCONTRAST: u32 = 0x0043;
const SPI_SETCLIENTAREAANIMATION: u32 = 0x1043;
const IMMERSIVE_COLOR_SET: [u16; 18] = ascii_to_wide(b"ImmersiveColorSet\0");

const fn ascii_to_wide<const N: usize>(text: &[u8; N]) -> [u16; N] {
    let mut wide = [0u16; N];
    let mut i = 0;
    while i < N {
        wide[i] = text[i] as u16;
        i += 1;
    }
    wide
}

/// The WinRT objects of one toolkit and the two registrations with their tokens, behind the opaque handle of
/// `glass_win_prefs.h`. A token is `Some` exactly when its `add_*` succeeded.
pub(crate) struct Preferences {
    pub(crate) poison: Poison,
    settings: Option<IInspectable>,
    settings5: Option<IUISettings5>,
    network_factory: Option<IActivationFactory>,
    network_information: Option<INetworkInformationStatics>,
    auto_hide_token: Option<i64>,
    network_token: Option<i64>,
}

impl Preferences {
    /// No WinRT object: the state of a toolkit whose `RoInitialize` failed.
    pub(crate) fn without_winrt() -> Preferences {
        Preferences {
            poison: Poison::new(),
            settings: None,
            settings5: None,
            network_factory: None,
            network_information: None,
            auto_hide_token: None,
            network_token: None,
        }
    }

    /// `RoInitialize(RO_INIT_SINGLETHREADED)` on the calling thread unless it is in force, then `UISettings` and
    /// `NetworkInformation` with their sinks, each half on its own: a failure of one leaves the other working.
    pub(crate) fn create() -> Preferences {
        let mut preferences = Preferences::without_winrt();
        if !RO_INITIALIZED.load(Ordering::Relaxed) {
            match sys::ro_initialize_single_threaded() {
                Ok(()) => RO_INITIALIZED.store(true, Ordering::Relaxed),
                Err(hr) => write_ro_initialize_failed(hr),
            }
        }
        if RO_INITIALIZED.load(Ordering::Relaxed) {
            preferences.activate_ui_settings();
            preferences.activate_network_information();
        }
        preferences
    }

    fn activate_ui_settings(&mut self) {
        let Some(settings) = sys::activate_ui_settings() else {
            return;
        };
        let settings = self.settings.insert(settings);
        let Ok(settings5) = settings.cast::<IUISettings5>() else {
            return;
        };
        let settings5 = self.settings5.insert(settings5);
        let handler = AutoHideHandler::new(|_, _| {
            guard((), || {
                on_sink_invoked(GWIN_TEST_DELEGATE_AUTO_HIDE, GWIN_PT_UI_SETTINGS)
            });
            Ok(())
        });
        if let Some(token) = sys::add_auto_hide_scroll_bars_changed(settings5, &handler) {
            self.auto_hide_token = Some(token);
            ffi::keep_auto_hide_delegate(&handler);
        }
    }

    fn activate_network_information(&mut self) {
        let Some(factory) = sys::network_information_factory() else {
            return;
        };
        let factory = self.network_factory.insert(factory);
        let Ok(statics) = factory.cast::<INetworkInformationStatics>() else {
            return;
        };
        let statics = self.network_information.insert(statics);
        let handler = NetworkStatusChangedEventHandler::new(|_| {
            guard((), || {
                on_sink_invoked(GWIN_TEST_DELEGATE_NETWORK, GWIN_PT_NETWORK_INFORMATION)
            });
            Ok(())
        });
        if let Some(token) = sys::add_network_status_changed(statics, &handler) {
            self.network_token = Some(token);
            ffi::keep_network_delegate(&handler);
        }
    }

    /// `gwin_prefs_query_ui_settings`: zero-filled, then `available`, then three blocks in order, where a failed
    /// query of the interface ends the function with that block and every later one invalid, and a failed getter
    /// leaves its zero value.
    pub(crate) fn collect_ui_settings(&self) -> GwinUiSettings {
        let mut out = GwinUiSettings::default();
        let Some(settings) = &self.settings else {
            return out;
        };
        out.available = 1;

        let Ok(settings3) = settings.cast::<IUISettings3>() else {
            return out;
        };
        for (slot, kind) in out.colors.iter_mut().zip(COLOR_TYPES) {
            *slot = argb(sys::color_value(&settings3, kind));
        }
        out.colors_valid = 1;
        drop(settings3);

        let Ok(settings4) = settings.cast::<IUISettings4>() else {
            return out;
        };
        out.advanced_effects_enabled = i32::from(sys::advanced_effects_enabled(&settings4) != 0);
        out.advanced_effects_valid = 1;
        drop(settings4);

        let Ok(settings5) = settings.cast::<IUISettings5>() else {
            return out;
        };
        out.auto_hide_scroll_bars = i32::from(sys::auto_hide_scroll_bars(&settings5) != 0);
        out.auto_hide_valid = 1;
        out
    }

    /// `gwin_prefs_query_network`: `available` only when the profile query succeeded, with `GWIN_NET_COST_UNKNOWN`
    /// when there is no profile; a failed cost query leaves the struct zero.
    pub(crate) fn collect_network_info(&self) -> GwinNetworkInfo {
        let mut out = GwinNetworkInfo::default();
        let Some(statics) = &self.network_information else {
            return out;
        };
        let (hr, profile) = sys::internet_connection_profile(statics);
        if hr.is_err() {
            return out;
        }
        let mut cost_type = GWIN_NET_COST_UNKNOWN;
        if let Some(profile) = profile {
            // GetConnectionCost succeeding with no object is an error here; the C dereferenced the NULL
            let Ok(cost) = profile.GetConnectionCost() else {
                return out;
            };
            let Ok(kind) = cost.NetworkCostType() else {
                return out;
            };
            cost_type = gwin_net_cost(kind);
        }
        out.available = 1;
        out.cost_type = cost_type;
        out
    }
}

impl Drop for Preferences {
    /// The teardown of `~PlatformSupport` and `uninitializeRoActivationSupport` at `5a709c189d`, in their order and
    /// with their six test events: both registrations removed with the kept tokens, every WinRT object released, and
    /// only then `RoUninitialize`, when a successful `RoInitialize` is in force.
    fn drop(&mut self) {
        if let Some(token) = self.auto_hide_token.take() {
            if let Some(settings5) = &self.settings5 {
                sys::remove_auto_hide_scroll_bars_changed(settings5, token);
            }
            ffi::test_log_event(GWIN_TEST_EVENT_REMOVE_AUTO_HIDE);
        }
        if let Some(token) = self.network_token.take() {
            if let Some(statics) = &self.network_information {
                sys::remove_network_status_changed(statics, token);
            }
            ffi::test_log_event(GWIN_TEST_EVENT_REMOVE_NETWORK);
        }
        drop(self.settings5.take());
        if let Some(settings) = self.settings.take() {
            drop(settings);
            ffi::test_log_event(GWIN_TEST_EVENT_RELEASE_UI_SETTINGS);
        }
        if let Some(statics) = self.network_information.take() {
            drop(statics);
            ffi::test_log_event(GWIN_TEST_EVENT_RELEASE_NETWORK_STATICS);
        }
        if let Some(factory) = self.network_factory.take() {
            drop(factory);
            ffi::test_log_event(GWIN_TEST_EVENT_RELEASE_NETWORK_FACTORY);
        }
        if RO_INITIALIZED.load(Ordering::Relaxed) {
            ffi::test_log_event(GWIN_TEST_EVENT_RO_UNINITIALIZE);
            sys::ro_uninitialize();
        }
        RO_INITIALIZED.store(false, Ordering::Relaxed);
    }
}

/// The body of both sinks, on whatever thread the OS invokes them: the test build's record, then the post.
fn on_sink_invoked(which: i32, types: i32) {
    ffi::test_record_delegate_call(which);
    post_preferences_changed(&KEPT_TYPES, types);
}

/// The preference types the sinks kept because their post failed with the toolkit window published, ORed together
/// until a toolkit pump takes them: `s_deferredTypes` of `5a709c189d`.
pub(crate) struct KeptTypes(AtomicI32);

/// The one kept word of the process.
pub(crate) static KEPT_TYPES: KeptTypes = KeptTypes::new();

// The orderings are those of the C's Interlocked functions (full barriers, SeqCst here) and ReadAcquire (Acquire):
// the word carries only its own bits and publishes no other data, so none of them orders anything else; they keep the
// C's code on x86-64, where all three compile to the same instructions as at 5a709c189d.
impl KeptTypes {
    pub(crate) const fn new() -> KeptTypes {
        KeptTypes(AtomicI32::new(0))
    }

    /// `InterlockedOr`. Any thread.
    pub(crate) fn keep(&self, types: i32) {
        self.0.fetch_or(types, Ordering::SeqCst);
    }

    /// `None` after one Acquire load that found nothing kept; otherwise the word exchanged with 0 and masked with
    /// `GWIN_PT_ALL`, which may be 0. Toolkit thread.
    pub(crate) fn take(&self) -> Option<i32> {
        if self.0.load(Ordering::Acquire) == 0 {
            return None;
        }
        Some(self.0.swap(0, Ordering::SeqCst) & GWIN_PT_ALL)
    }

    /// `InterlockedExchange` with 0, the value dropped. Toolkit thread.
    pub(crate) fn discard(&self) {
        self.0.swap(0, Ordering::SeqCst);
    }
}

/// `PostPreferencesChanged`: post the types to the toolkit window through the guarded post; when that fails with the
/// window published, keep them, then post one wake (types 0), and when the wake is refused too with the window still
/// published, arm the preferences timer. Nothing is kept when no window is published.
pub(crate) fn post_preferences_changed(kept: &KeptTypes, types: i32) {
    let mut published = 0;
    if ffi::post(types, &mut published) || published == 0 {
        return;
    }
    kept.keep(types);
    ffi::test_count(GWIN_TEST_COUNTER_PREFS_DEFERRED);
    if !ffi::post(0, &mut published) && published != 0 {
        ffi::arm_timer();
    }
}

/// `RunDeferredPreferences`: when anything is kept, take it, and when the masked types are not 0 and a live handle
/// exists, deliver them to the toolkit's handle as the `WM_PREFERENCES_CHANGED` arm does. The answer is not used.
pub(crate) fn run_deferred(kept: &KeptTypes) {
    let Some(types) = kept.take() else {
        return;
    };
    let live = ffi::live_handle();
    if types != 0 && !live.is_null() {
        ffi::test_count(GWIN_TEST_COUNTER_PREFS_DEFERRED_RUN);
        ffi::update_on(live, types);
    }
}

/// `updatePreferences`: 1 when the installed `preferences_changed` answered nonzero, else 0; 0 without a slot.
pub(crate) fn update_preferences(types: i32) -> i32 {
    i32::from(ffi::preferences_changed(types))
}

/// The types `onSettingChanged` hands to [`update_preferences`] for a `WM_SETTINGCHANGE`, 0 for no call. The `wParam`
/// is truncated to `UINT`; `SPI_SETHIGHCONTRAST` and `SPI_SETCLIENTAREAANIMATION` decide before `lParam` is looked at;
/// otherwise a non-zero `lParam` is compared with `ImmersiveColorSet` as `wcscmp` compares, `unit_at(i)` reading its
/// unit `i` only after units `0..i` matched non-NUL units.
pub(crate) fn setting_changed_types(wparam: usize, lparam: isize, unit_at: impl FnMut(usize) -> u16) -> i32 {
    match wparam as u32 {
        SPI_SETHIGHCONTRAST => return GWIN_PT_SYSTEM_PARAMS | GWIN_PT_UI_SETTINGS,
        SPI_SETCLIENTAREAANIMATION => return GWIN_PT_SYSTEM_PARAMS,
        _ => {}
    }
    if lparam != 0 && is_immersive_color_set(unit_at) {
        GWIN_PT_UI_SETTINGS
    } else {
        0
    }
}

/// `wcscmp(text, L"ImmersiveColorSet") == 0`, reading unit by unit and stopping at the first difference or at the
/// terminator.
fn is_immersive_color_set(mut unit_at: impl FnMut(usize) -> u16) -> bool {
    for (index, &expected) in IMMERSIVE_COLOR_SET.iter().enumerate() {
        if unit_at(index) != expected {
            return false;
        }
        if expected == 0 {
            return true;
        }
    }
    false
}

/// `0xAARRGGBB` from a WinRT `Color`.
pub(crate) fn argb(color: Color) -> u32 {
    (u32::from(color.A) << 24) | (u32::from(color.R) << 16) | (u32::from(color.G) << 8) | u32::from(color.B)
}

/// The explicit switch of `collectNetworkInfo`: the three named WinRT costs to this ABI's values, anything else
/// unknown.
pub(crate) fn gwin_net_cost(kind: NetworkCostType) -> i32 {
    match kind {
        NetworkCostType::Unrestricted => GWIN_NET_COST_UNRESTRICTED,
        NetworkCostType::Variable => GWIN_NET_COST_VARIABLE,
        NetworkCostType::Fixed => GWIN_NET_COST_FIXED,
        _ => GWIN_NET_COST_UNKNOWN,
    }
}

fn write_ro_initialize_failed(hr: HRESULT) {
    if let Some(line) = ro_initialize_failed_line(hr.0, sys::system_message(hr), sys::wide_to_ansi) {
        ffi::write_stderr(&line);
    }
}

/// The bytes `RoException("RoInitialize failed: ", hr).message()` holds, NUL-terminated, or `None` where it held
/// none and nothing was printed: no text at all, a failed conversion, or an allocation that failed. The text is
/// `_com_error::ErrorMessage`'s: the system message with one trailing line break trimmed, or its fallback when there
/// is none; then `to_ansi` converts it, NUL-terminated, as `WideCharToMultiByte(CP_ACP, ...)` does.
pub(crate) fn ro_initialize_failed_line(
    hr: i32,
    system: SystemMessage,
    to_ansi: impl FnOnce(&[u16]) -> Option<Vec<u8>>,
) -> Option<Vec<u8>> {
    let mut text = match system {
        SystemMessage::Text(mut text) => {
            trim_line_break(&mut text);
            text
        }
        SystemMessage::Missing => fallback_message(hr),
        SystemMessage::OutOfMemory => return None,
    };
    text.try_reserve_exact(1).ok()?;
    text.push(0);
    let ansi = to_ansi(&text)?;
    let mut line = Vec::new();
    line.try_reserve_exact(RO_INITIALIZE_FAILED.len() + ansi.len() + 1)
        .ok()?;
    line.extend_from_slice(RO_INITIALIZE_FAILED);
    line.extend_from_slice(&ansi);
    line.push(0);
    Some(line)
}

/// `_com_error::ErrorMessage`'s trim: when the text is longer than one unit and ends with `\n`, that `\n` goes, and
/// then one `\r` before it.
pub(crate) fn trim_line_break(text: &mut Vec<u16>) {
    let len = text.len();
    if len > 1 && text[len - 1] == u16::from(b'\n') {
        text.truncate(len - 1);
        if text[len - 2] == u16::from(b'\r') {
            text.truncate(len - 2);
        }
    }
}

/// `_com_error::ErrorMessage`'s text when `FormatMessage` gave none: `IDispatch error #%d` with the code for an
/// `HRESULT` in `0x80040200..=0x8004FFFF` other than the first, else `Unknown error 0x%0lX`.
pub(crate) fn fallback_message(hr: i32) -> Vec<u16> {
    const WCODE_HRESULT_FIRST: i32 = 0x8004_0200_u32 as i32;
    const WCODE_HRESULT_LAST: i32 = 0x8004_FFFF_u32 as i32;
    let wcode = if (WCODE_HRESULT_FIRST..=WCODE_HRESULT_LAST).contains(&hr) {
        hr - WCODE_HRESULT_FIRST
    } else {
        0
    };
    let text = if wcode != 0 {
        format!("IDispatch error #{wcode}")
    } else {
        format!("Unknown error 0x{:X}", hr as u32)
    };
    text.encode_utf16().collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::GWIN_PT_SYSTEM_COLORS;
    use crate::ffi::doubles::{self, Call};

    fn wide(text: &str) -> Vec<u16> {
        text.encode_utf16().collect()
    }

    /// `calls` without the probe calls when the test-hooks feature is off: there the probes are empty.
    fn expect(calls: Vec<Call>) -> Vec<Call> {
        calls
            .into_iter()
            .filter(|call| cfg!(feature = "test-hooks") || !call.is_probe())
            .collect()
    }

    fn color(a: u8, r: u8, g: u8, b: u8) -> Color {
        Color { A: a, R: r, G: g, B: b }
    }

    #[test]
    fn the_three_named_costs_map_to_this_abi_and_everything_else_is_unknown() {
        assert_eq!(gwin_net_cost(NetworkCostType::Unrestricted), GWIN_NET_COST_UNRESTRICTED);
        assert_eq!(gwin_net_cost(NetworkCostType::Variable), GWIN_NET_COST_VARIABLE);
        assert_eq!(gwin_net_cost(NetworkCostType::Fixed), GWIN_NET_COST_FIXED);
        assert_eq!(gwin_net_cost(NetworkCostType::Unknown), GWIN_NET_COST_UNKNOWN);
        for other in [4, 5, -1, i32::MAX, i32::MIN] {
            assert_eq!(gwin_net_cost(NetworkCostType(other)), GWIN_NET_COST_UNKNOWN, "{other}");
        }
        // WinRT numbers Fixed 2 and Variable 3; this ABI numbers them the other way round
        assert_eq!(gwin_net_cost(NetworkCostType(2)), 3);
        assert_eq!(gwin_net_cost(NetworkCostType(3)), 2);
    }

    #[test]
    fn colors_pack_as_aarrggbb() {
        assert_eq!(argb(color(0xFF, 0x00, 0x78, 0xD7)), 0xFF00_78D7);
        assert_eq!(argb(color(0x12, 0x34, 0x56, 0x78)), 0x1234_5678);
        assert_eq!(argb(color(0, 0, 0, 0)), 0);
        assert_eq!(argb(color(0xFF, 0xFF, 0xFF, 0xFF)), 0xFFFF_FFFF);
        assert_eq!(argb(color(0x80, 0, 0, 0)), 0x8000_0000);
    }

    /// Reads `text` (no terminator in it) followed by NUL, recording each index read.
    fn reader<'a>(text: &'a [u16], reads: &'a mut Vec<usize>) -> impl FnMut(usize) -> u16 + 'a {
        move |index| {
            reads.push(index);
            text.get(index).copied().unwrap_or(0)
        }
    }

    #[test]
    fn the_two_spi_values_decide_before_the_lparam_is_read() {
        let text = wide("ImmersiveColorSet");
        for (wparam, expected) in [
            (0x0043usize, 6),
            (0x1043, 2),
            (0x1_0000_0043, 6),
            (0xFFFF_FFFF_0000_1043, 2),
        ] {
            let mut reads = Vec::new();
            assert_eq!(
                setting_changed_types(wparam, 1, reader(&text, &mut reads)),
                expected,
                "{wparam:#x}"
            );
            assert!(reads.is_empty(), "{wparam:#x} read the lParam");
        }
        assert_eq!(GWIN_PT_SYSTEM_PARAMS | GWIN_PT_UI_SETTINGS, 6);
    }

    #[test]
    fn immersive_color_set_gives_the_ui_settings_bit_for_any_other_wparam() {
        let text = wide("ImmersiveColorSet");
        for wparam in [0usize, 0x0042, 0x2F, 0x1_0000_0000] {
            let mut reads = Vec::new();
            assert_eq!(
                setting_changed_types(wparam, 1, reader(&text, &mut reads)),
                GWIN_PT_UI_SETTINGS
            );
            assert_eq!(
                reads,
                (0..=17).collect::<Vec<_>>(),
                "the comparison reads the terminator, never past it"
            );
        }
    }

    #[test]
    fn a_null_lparam_is_no_call_and_is_never_read() {
        let mut reads = Vec::new();
        assert_eq!(setting_changed_types(0, 0, reader(&[], &mut reads)), 0);
        assert!(reads.is_empty());
    }

    #[test]
    fn the_comparison_is_wcscmp_and_stops_at_the_first_difference() {
        for (text, expected, last_read) in [
            ("immersivecolorset", 0, 0),
            ("ImmersiveColorSe", 0, 16),
            ("ImmersiveColorSetX", 0, 17),
            ("Immersive", 0, 9),
            ("", 0, 0),
            ("X", 0, 0),
            ("ImmersiveColorSet", 4, 17),
        ] {
            let units = wide(text);
            let mut reads = Vec::new();
            assert_eq!(
                setting_changed_types(0, 1, reader(&units, &mut reads)),
                expected,
                "{text:?}"
            );
            assert_eq!(reads.last().copied(), Some(last_read), "{text:?}");
            assert_eq!(reads, (0..=last_read).collect::<Vec<_>>(), "{text:?}");
        }
    }

    #[test]
    fn one_trailing_line_break_is_trimmed_as_com_error_does() {
        for (text, expected) in [
            ("abc\r\n", "abc"),
            ("abc\n", "abc"),
            ("abc \r\n", "abc "),
            ("a\r\nb\r\n", "a\r\nb"),
            ("abc\r", "abc\r"),
            ("abc\n\n", "abc\n"),
            ("abc\r\n\r\n", "abc\r\n"),
            ("\n", "\n"),
            ("x\n", "x"),
            ("\r\n", ""),
            ("\r\r\n", "\r"),
            ("", ""),
        ] {
            let mut units = wide(text);
            trim_line_break(&mut units);
            assert_eq!(units, wide(expected), "{text:?}");
        }
    }

    #[test]
    fn the_fallback_texts_are_formatted_as_swprintf_s_formats_them() {
        assert_eq!(fallback_message(0x8004_0201_u32 as i32), wide("IDispatch error #1"));
        assert_eq!(fallback_message(0x8004_FFFF_u32 as i32), wide("IDispatch error #65023"));
        assert_eq!(fallback_message(0x8004_1234_u32 as i32), wide("IDispatch error #4148"));
        assert_eq!(
            fallback_message(0x8004_0200_u32 as i32),
            wide("Unknown error 0x80040200")
        );
        assert_eq!(
            fallback_message(0x8004_01FF_u32 as i32),
            wide("Unknown error 0x800401FF")
        );
        assert_eq!(
            fallback_message(0x8005_0000_u32 as i32),
            wide("Unknown error 0x80050000")
        );
        assert_eq!(
            fallback_message(0x8001_0106_u32 as i32),
            wide("Unknown error 0x80010106")
        );
        assert_eq!(fallback_message(0x0004_0201), wide("Unknown error 0x40201"));
        assert_eq!(fallback_message(1), wide("Unknown error 0x1"));
        assert_eq!(fallback_message(0), wide("Unknown error 0x0"));
        assert_eq!(fallback_message(-1), wide("Unknown error 0xFFFFFFFF"));
    }

    /// A converter for the test: ASCII units to bytes, `None` for anything else.
    fn ascii(text: &[u16]) -> Option<Vec<u8>> {
        let (last, body) = text.split_last()?;
        assert_eq!(*last, 0, "the text handed to the conversion is NUL-terminated");
        body.iter()
            .map(|&unit| u8::try_from(unit).ok().filter(u8::is_ascii))
            .collect()
    }

    #[test]
    fn the_line_is_the_prefix_and_the_trimmed_text_with_no_line_break() {
        let line = ro_initialize_failed_line(
            0x8001_0106_u32 as i32,
            SystemMessage::Text(wide("Cannot change thread mode after it is set.\r\n")),
            ascii,
        );
        assert_eq!(
            line.as_deref(),
            Some(&b"RoInitialize failed: Cannot change thread mode after it is set.\0"[..])
        );
    }

    #[test]
    fn without_a_system_text_the_line_carries_the_fallback() {
        let line = ro_initialize_failed_line(0x8004_0203_u32 as i32, SystemMessage::Missing, ascii);
        assert_eq!(line.as_deref(), Some(&b"RoInitialize failed: IDispatch error #3\0"[..]));
        let line = ro_initialize_failed_line(0x8004_0200_u32 as i32, SystemMessage::Missing, ascii);
        assert_eq!(
            line.as_deref(),
            Some(&b"RoInitialize failed: Unknown error 0x80040200\0"[..])
        );
    }

    #[test]
    fn an_empty_text_still_prints_the_prefix_and_a_failed_conversion_prints_nothing() {
        let line = ro_initialize_failed_line(1, SystemMessage::Text(wide("\r\n")), ascii);
        assert_eq!(line.as_deref(), Some(&b"RoInitialize failed: \0"[..]));
        assert_eq!(
            ro_initialize_failed_line(1, SystemMessage::Text(wide("\u{E9}")), ascii),
            None
        );
        assert_eq!(ro_initialize_failed_line(1, SystemMessage::OutOfMemory, ascii), None);
    }

    #[test]
    fn the_system_converter_rejects_a_text_without_its_terminator() {
        assert_eq!(sys::wide_to_ansi(&wide("abc")), None);
        assert_eq!(sys::wide_to_ansi(&[u16::from(b'a'), 0, u16::from(b'b'), 0]), None);
        assert_eq!(
            sys::wide_to_ansi(&[u16::from(b'o'), u16::from(b'k'), 0]),
            Some(b"ok".to_vec())
        );
        assert_eq!(sys::wide_to_ansi(&[0]), Some(Vec::new()));
    }

    #[test]
    fn keep_ors_take_masks_and_exchanges_discard_clears() {
        let kept = KeptTypes::new();
        assert_eq!(kept.take(), None, "nothing kept: one load, no exchange");
        kept.keep(GWIN_PT_UI_SETTINGS);
        kept.keep(GWIN_PT_NETWORK_INFORMATION);
        kept.keep(GWIN_PT_UI_SETTINGS);
        assert_eq!(kept.take(), Some(12));
        assert_eq!(kept.take(), None);
        kept.keep(0x30 | GWIN_PT_SYSTEM_COLORS);
        assert_eq!(
            kept.take(),
            Some(GWIN_PT_SYSTEM_COLORS),
            "bits above GWIN_PT_ALL are exchanged away"
        );
        assert_eq!(kept.take(), None);
        kept.keep(0x100);
        assert_eq!(
            kept.take(),
            Some(0),
            "only bits above GWIN_PT_ALL: taken, nothing to deliver"
        );
        assert_eq!(kept.take(), None);
        kept.keep(GWIN_PT_NETWORK_INFORMATION);
        kept.discard();
        assert_eq!(kept.take(), None);
    }

    #[test]
    fn a_posted_sink_keeps_nothing() {
        let kept = KeptTypes::new();
        doubles::script_posts(&[(1, 1)]);
        post_preferences_changed(&kept, GWIN_PT_UI_SETTINGS);
        assert_eq!(doubles::take_calls(), vec![Call::Post(4)]);
        assert_eq!(kept.take(), None);
    }

    #[test]
    fn a_sink_refused_without_a_published_window_keeps_nothing() {
        let kept = KeptTypes::new();
        doubles::script_posts(&[(0, 0)]);
        post_preferences_changed(&kept, GWIN_PT_NETWORK_INFORMATION);
        assert_eq!(doubles::take_calls(), vec![Call::Post(8)]);
        assert_eq!(kept.take(), None);
    }

    #[test]
    fn a_failed_post_with_the_window_published_keeps_the_types_and_posts_a_wake() {
        let kept = KeptTypes::new();
        doubles::script_posts(&[(0, 1), (1, 1)]);
        post_preferences_changed(&kept, GWIN_PT_NETWORK_INFORMATION);
        assert_eq!(
            doubles::take_calls(),
            expect(vec![
                Call::Post(8),
                Call::Count(GWIN_TEST_COUNTER_PREFS_DEFERRED),
                Call::Post(0)
            ])
        );
        assert_eq!(kept.take(), Some(8));
    }

    #[test]
    fn a_refused_wake_with_the_window_published_arms_the_timer() {
        let kept = KeptTypes::new();
        doubles::script_posts(&[(0, 1), (0, 1)]);
        post_preferences_changed(&kept, GWIN_PT_UI_SETTINGS);
        assert_eq!(
            doubles::take_calls(),
            expect(vec![
                Call::Post(4),
                Call::Count(GWIN_TEST_COUNTER_PREFS_DEFERRED),
                Call::Post(0),
                Call::ArmTimer
            ])
        );
        assert_eq!(kept.take(), Some(4));
    }

    #[test]
    fn a_wake_refused_because_the_window_went_arms_no_timer() {
        let kept = KeptTypes::new();
        doubles::script_posts(&[(0, 1), (0, 0)]);
        post_preferences_changed(&kept, GWIN_PT_UI_SETTINGS);
        assert_eq!(
            doubles::take_calls(),
            expect(vec![
                Call::Post(4),
                Call::Count(GWIN_TEST_COUNTER_PREFS_DEFERRED),
                Call::Post(0)
            ])
        );
        assert_eq!(kept.take(), Some(4), "the kept types stay for the next pump");
    }

    #[test]
    fn a_sink_records_its_delegate_first_then_posts() {
        doubles::script_posts(&[(1, 1)]);
        on_sink_invoked(GWIN_TEST_DELEGATE_NETWORK, GWIN_PT_NETWORK_INFORMATION);
        assert_eq!(
            doubles::take_calls(),
            expect(vec![Call::RecordDelegate(GWIN_TEST_DELEGATE_NETWORK), Call::Post(8)])
        );
    }

    #[test]
    fn run_deferred_delivers_the_masked_types_to_a_live_handle() {
        let _table = doubles::lock_table();
        doubles::install_recording_table();
        let kept = KeptTypes::new();
        kept.keep(GWIN_PT_UI_SETTINGS | GWIN_PT_NETWORK_INFORMATION | 0x40);
        doubles::with_live_handle(Preferences::without_winrt(), || run_deferred(&kept));
        doubles::clear_table();
        assert_eq!(
            doubles::take_calls(),
            expect(vec![
                Call::LiveHandle,
                Call::Count(GWIN_TEST_COUNTER_PREFS_DEFERRED_RUN),
                Call::Changed(12)
            ])
        );
        assert_eq!(kept.take(), None);
    }

    #[test]
    fn run_deferred_without_a_live_handle_drops_the_taken_types() {
        let kept = KeptTypes::new();
        kept.keep(GWIN_PT_UI_SETTINGS);
        run_deferred(&kept);
        assert_eq!(doubles::take_calls(), vec![Call::LiveHandle]);
        assert_eq!(kept.take(), None);
        run_deferred(&kept);
        assert_eq!(
            doubles::take_calls(),
            vec![],
            "nothing kept: the live handle is not even asked for"
        );
    }

    #[test]
    fn a_handle_without_winrt_answers_empty_queries_and_tears_down_with_no_event() {
        let preferences = Preferences::without_winrt();
        assert_eq!(preferences.collect_ui_settings(), GwinUiSettings::default());
        assert_eq!(preferences.collect_network_info(), GwinNetworkInfo::default());
        drop(preferences);
        assert_eq!(
            doubles::take_calls(),
            vec![],
            "no remove, no release, no RoUninitialize"
        );
    }

    #[test]
    fn update_calls_the_installed_slot_once_and_answers_whether_it_reported_a_change() {
        let _table = doubles::lock_table();
        assert_eq!(update_preferences(GWIN_PT_UI_SETTINGS), 0, "no table: no call, false");
        assert_eq!(doubles::take_calls(), vec![]);
        doubles::install_recording_table();
        doubles::script_changed(&[0, 1, -7]);
        assert_eq!(update_preferences(5), 0);
        assert_eq!(update_preferences(6), 1);
        assert_eq!(update_preferences(2), 1, "any nonzero answer is a change");
        doubles::clear_table();
        assert_eq!(update_preferences(4), 0);
        assert_eq!(
            doubles::take_calls(),
            vec![Call::Changed(5), Call::Changed(6), Call::Changed(2)]
        );
    }
}
