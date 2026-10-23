/*
 * Copyright (c) 2023, 2026, Oracle and/or its affiliates. All rights reserved.
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

#include "PlatformSupport.h"
#include "RoActivationSupport.h"
#include "GlassApplication.h"
#include "glass_win_test.h"
#include <tuple>

using namespace Microsoft::WRL;
using namespace ABI::Windows::Foundation;
using namespace ABI::Windows::UI;
using namespace ABI::Windows::UI::ViewManagement;
using namespace ABI::Windows::Networking::Connectivity;

/*
 * ---- the flat C ABI half of this file (glass_win_api.h) ----
 *
 * The preferences callback table. Written by gwin_prefs_set_callbacks, from Java: installed on the
 * launcher thread before the Glass toolkit thread exists, and cleared on the toolkit thread after
 * gwin_run_loop has returned. Read only by updatePreferences, which runs only on the toolkit thread, in
 * GlassApplication::WindowProc - the two WinRT sinks post there instead of reading it. No lock, by
 * design - see gwin_prefs_set_callbacks in glass_win_api.h for why that is safe and what breaks if the
 * installation is made lazy instead.
 */
static GwinPrefsCallbacks g_prefsCallbacks = { NULL };
static void* g_prefsUser = NULL;

/*
 * updatePreferences hands its PreferenceType straight to preferences_changed as `types`, so the two
 * enums have to be the same numbers. This is the only place both are visible; glass_win_api.cpp
 * cannot see PlatformSupport::PreferenceType and PlatformSupport.h does not spell the ABI values.
 */
static_assert((int)PlatformSupport::PT_SYSTEM_COLORS == GWIN_PT_SYSTEM_COLORS
        && (int)PlatformSupport::PT_SYSTEM_PARAMS == GWIN_PT_SYSTEM_PARAMS
        && (int)PlatformSupport::PT_UI_SETTINGS == GWIN_PT_UI_SETTINGS
        && (int)PlatformSupport::PT_NETWORK_INFORMATION == GWIN_PT_NETWORK_INFORMATION
        && (int)PlatformSupport::PT_ALL == GWIN_PT_ALL,
        "GwinPreferenceType must mirror PlatformSupport::PreferenceType");

/*
 * gwin_test_prefs_capture / gwin_test_prefs_invoke (glass_win_api.h): while capture is on, the extra
 * reference to each delegate InitializeWinRT registers, as an event source holds it. Only those two
 * hooks write or read these.
 */
namespace {

typedef ITypedEventHandler<UISettings*, UISettingsAutoHideScrollBarsChangedEventArgs*> AutoHideHandler;

volatile LONG s_captureDelegates = 0;
AutoHideHandler* volatile s_keptAutoHide = NULL;
INetworkStatusChangedEventHandler* volatile s_keptNetwork = NULL;

template <typename T>
void KeepDelegate(T* volatile* slot, T* handler)
{
    if (s_captureDelegates == 0 || handler == NULL) {
        return;
    }
    handler->AddRef();
    T* old = static_cast<T*>(::InterlockedExchangePointer(reinterpret_cast<PVOID volatile*>(slot), handler));
    if (old != NULL) {
        old->Release();
    }
}

template <typename T>
void DropDelegate(T* volatile* slot)
{
    T* old = static_cast<T*>(::InterlockedExchangePointer(reinterpret_cast<PVOID volatile*>(slot), NULL));
    if (old != NULL) {
        old->Release();
    }
}

} // namespace

/*
 * gwin_app_create's instance: WinRT activation only. The JNI constructor, which also looked up
 * the Java classes the preferences map was built from, is gone.
 */
PlatformSupport::PlatformSupport()
    : autoHideToken(), networkToken(), autoHideRegistered(false), networkRegistered(false)
{
    InitializeWinRT();
}

void PlatformSupport::InitializeWinRT()
{
    tryInitializeRoActivationSupport();

    if (!isRoActivationSupported()) {
        return;
    }

    try {
        RO_CHECKED("RoActivateInstance",
                   RoActivateInstance(hstring("Windows.UI.ViewManagement.UISettings"), (IInspectable**)&settings));

        RO_CHECKED("IUISettings::QueryInterface<IUISettings5>",
                   settings->QueryInterface<IUISettings5>(&settings5));

        // The sinks only post (see PostPreferencesChanged); they capture nothing teardown frees.
        ComPtr<AutoHideHandler> autoHideHandler = Callback<AutoHideHandler>(
            [](IUISettings*, IUISettingsAutoHideScrollBarsChangedEventArgs*) {
                GwinTestRecordDelegateCall(GWIN_TEST_DELEGATE_AUTO_HIDE);
                PostPreferencesChanged(PT_UI_SETTINGS);
                return S_OK;
            });
        if (SUCCEEDED(settings5->add_AutoHideScrollBarsChanged(autoHideHandler.Get(), &autoHideToken))) {
            autoHideRegistered = true;
            KeepDelegate(&s_keptAutoHide, autoHideHandler.Get());
        }
    } catch (RoException const&) {
        // If an activation exception occurs, it probably means that we're on a Windows system
        // that doesn't support the UISettings API. This is not a problem, it simply means that
        // we don't report the UISettings properties back to the JavaFX application.
    }

    try {
        RO_CHECKED("RoGetActivationFactory",
                   RoGetActivationFactory(hstring("Windows.Networking.Connectivity.NetworkInformation"),
                                          IID_IActivationFactory,
                                          (void**)networkFactory.GetAddressOf()));

        RO_CHECKED("IActivationFactory::QueryInterface<INetworkInformationStatics>",
                   networkFactory.As(&networkInformation));

        ComPtr<INetworkStatusChangedEventHandler> networkHandler = Callback<INetworkStatusChangedEventHandler>(
            [](IInspectable*) {
                GwinTestRecordDelegateCall(GWIN_TEST_DELEGATE_NETWORK);
                PostPreferencesChanged(PT_NETWORK_INFORMATION);
                return S_OK;
            });
        if (SUCCEEDED(networkInformation->add_NetworkStatusChanged(networkHandler.Get(), &networkToken))) {
            networkRegistered = true;
            KeepDelegate(&s_keptNetwork, networkHandler.Get());
        }
    } catch (RoException const&) {
        // If an activation exception occurs, it probably means that we're on a Windows system
        // that doesn't support the NetworkInformation API.
    }
}

/*
 * On the toolkit thread, from ~GlassApplication - after the toolkit window's WM_NCDESTROY arm cleared the
 * published HWND, so a delegate the OS still invokes, now or after remove_*, finds no window and posts
 * nothing. Unregister both, then release every WinRT object here, in the body, before
 * uninitializeRoActivationSupport calls RoUninitialize and frees combase.
 */
PlatformSupport::~PlatformSupport()
{
    if (autoHideRegistered) {
        settings5->remove_AutoHideScrollBarsChanged(autoHideToken);
        autoHideRegistered = false;
        GwinTestLogEvent(GWIN_TEST_EVENT_REMOVE_AUTO_HIDE);
    }
    if (networkRegistered) {
        networkInformation->remove_NetworkStatusChanged(networkToken);
        networkRegistered = false;
        GwinTestLogEvent(GWIN_TEST_EVENT_REMOVE_NETWORK);
    }

    settings5 = nullptr;
    if (settings != nullptr) {
        settings = nullptr;
        GwinTestLogEvent(GWIN_TEST_EVENT_RELEASE_UI_SETTINGS);
    }
    if (networkInformation != nullptr) {
        networkInformation = nullptr;
        GwinTestLogEvent(GWIN_TEST_EVENT_RELEASE_NETWORK_STATICS);
    }
    if (networkFactory != nullptr) {
        networkFactory = nullptr;
        GwinTestLogEvent(GWIN_TEST_EVENT_RELEASE_NETWORK_FACTORY);
    }

    uninitializeRoActivationSupport();
}

/*
 * static
 *
 * What both WinRT sinks do, on whatever thread the OS invokes them: post the preference types to the
 * toolkit window through GlassApplication::PostToToolkit, the guarded post gwin_invoke_later uses. Once
 * the toolkit window is cleared the post is refused, and the sink still answers S_OK. No lock is held
 * across a call into Java: preferences_changed runs later, in the toolkit window's procedure.
 */
void PlatformSupport::PostPreferencesChanged(PreferenceType preferenceType)
{
    GlassApplication::PostToToolkit(WM_PREFERENCES_CHANGED, (WPARAM)preferenceType, (LPARAM)0);
}

bool PlatformSupport::updatePreferences(PreferenceType preferenceType) const
{
    /*
     * Java owns the whole collect / compare / notify cycle: WinPreferences reads GetSysColor and
     * SystemParametersInfoW itself and calls gwin_prefs_query_ui_settings / gwin_prefs_query_network
     * for the WinRT half, so no java/util/Map is ever built in C (the JNI arm that built one is
     * gone). With no table installed nothing is delivered and the answer is false. The callback
     * returns 1 when the preferences changed; see GwinPrefsCallbacks in glass_win_api.h for the exact
     * mapping - notably a notify that throws still counts as changed. The slot is loaded once: the test
     * and the call use the same value even if Java has just cleared the table.
     */
    int32_t (*const preferencesChanged)(void*, int32_t) = g_prefsCallbacks.preferences_changed;
    return preferencesChanged != NULL && preferencesChanged(g_prefsUser, (int32_t)preferenceType) != 0;
}

bool PlatformSupport::onSettingChanged(WPARAM wParam, LPARAM lParam) const
{
    switch ((UINT)wParam) {
        case SPI_SETHIGHCONTRAST:
            return updatePreferences(PreferenceType(PT_SYSTEM_PARAMS | PT_UI_SETTINGS));

        case SPI_SETCLIENTAREAANIMATION:
            return updatePreferences(PT_SYSTEM_PARAMS);
    }

    if (lParam != NULL && wcscmp(LPCWSTR(lParam), L"ImmersiveColorSet") == 0) {
        return updatePreferences(PT_UI_SETTINGS);
    }

    return false;
}

/*
 * The former queryUISettings without the JNI: same three try blocks, same order, same three
 * ignored HRESULTs. Each block's "valid" flag stands for the map keys that block used to put, so a 0 flag
 * means those keys are absent - the C dropped every later key too once one block threw, and returning early here
 * reproduces that. The one deliberate deviation is that `out` and the locals feeding it start zeroed,
 * where the C read uninitialised stack when an ignored HRESULT failed.
 */
void PlatformSupport::collectUISettings(GwinUiSettings& out) const
{
    ::memset(&out, 0, sizeof(GwinUiSettings));

    if (!this->settings) {
        return;
    }

    out.available = 1;

    try {
        ComPtr<IUISettings3> settings3;
        RO_CHECKED("IUISettings::QueryInterface<IUISettings3>",
                   this->settings->QueryInterface<IUISettings3>(&settings3));

        // The order of the former queryUISettings, and the order WinPreferences names the keys in.
        static const UIColorType colorTypes[GWIN_UI_COLOR_COUNT] = {
            UIColorType::UIColorType_Background,
            UIColorType::UIColorType_Foreground,
            UIColorType::UIColorType_AccentDark3,
            UIColorType::UIColorType_AccentDark2,
            UIColorType::UIColorType_AccentDark1,
            UIColorType::UIColorType_Accent,
            UIColorType::UIColorType_AccentLight1,
            UIColorType::UIColorType_AccentLight2,
            UIColorType::UIColorType_AccentLight3
        };

        for (int i = 0; i < GWIN_UI_COLOR_COUNT; ++i) {
            Color color = {};
            // The HRESULT is ignored, as it was in queryUISettings; `color` is zeroed, so a failure
            // yields transparent black rather than the stack garbage the C would have published.
            settings3->GetColorValue(colorTypes[i], &color);
            out.colors[i] = ((uint32_t)color.A << 24) | ((uint32_t)color.R << 16)
                          | ((uint32_t)color.G << 8) | (uint32_t)color.B;
        }

        out.colors_valid = 1;
    } catch (RoException const&) {
        return;
    }

    try {
        ComPtr<IUISettings4> settings4;
        RO_CHECKED("IUISettings::QueryInterface<IUISettings4>",
                   this->settings->QueryInterface<IUISettings4>(&settings4));

        unsigned char value = 0;
        settings4->get_AdvancedEffectsEnabled(&value);   // HRESULT ignored, as in the former queryUISettings
        out.advanced_effects_enabled = value != 0;       // the former putBoolean(const bool): nonzero is true
        out.advanced_effects_valid = 1;
    } catch (RoException const&) {
        return;
    }

    try {
        ComPtr<IUISettings5> settings5;
        RO_CHECKED("IUISettings::QueryInterface<IUISettings5>",
                   this->settings->QueryInterface<IUISettings5>(&settings5));

        unsigned char value = 0;
        settings5->get_AutoHideScrollBars(&value);       // HRESULT ignored, as in the former queryUISettings
        out.auto_hide_scroll_bars = value != 0;          // the former putBoolean(const bool): nonzero is true
        out.auto_hide_valid = 1;
    } catch (RoException const&) {
        return;
    }
}

/*
 * The former queryNetworkInformation without the JNI. available is set exactly where the C
 * reached its putString, so available == 0 means "no key" and available == 1 with GWIN_NET_COST_UNKNOWN
 * means the key was written with the value "Unknown" - either there is no internet connection profile, or the profile
 * reported a cost the switch does not name. The GwinNetworkCost values are this ABI's, not WinRT's.
 */
void PlatformSupport::collectNetworkInfo(GwinNetworkInfo& out) const
{
    ::memset(&out, 0, sizeof(GwinNetworkInfo));

    if (!this->networkInformation) {
        return;
    }

    try {
        ComPtr<IConnectionProfile> connectionProfile;
        ComPtr<IConnectionCost> connectionCost;
        NetworkCostType networkCostType = NetworkCostType_Unknown;
        int32_t costType = GWIN_NET_COST_UNKNOWN;

        RO_CHECKED("INetworkInformation::GetInternetConnectionProfile",
                   this->networkInformation->GetInternetConnectionProfile(&connectionProfile));

        if (connectionProfile) {
            RO_CHECKED("IConnectionProfile::GetConnectionCost",
                       connectionProfile->GetConnectionCost(&connectionCost));

            RO_CHECKED("IConnectionCost::get_NetworkCostType",
                       connectionCost->get_NetworkCostType(&networkCostType));

            switch (networkCostType) {
                case NetworkCostType_Unrestricted: costType = GWIN_NET_COST_UNRESTRICTED; break;
                case NetworkCostType_Variable: costType = GWIN_NET_COST_VARIABLE; break;
                case NetworkCostType_Fixed: costType = GWIN_NET_COST_FIXED; break;
                default: break;   // the C left internetCostType NULL and put "Unknown"
            }
        }

        out.available = 1;
        out.cost_type = costType;
    } catch (RoException const&) {
    }
}

/* ---- glass_win_api.h exports. Definitions take C linkage from that header's extern "C" block. ---- */

extern "C" {

int32_t gwin_prefs_set_callbacks(const GwinPrefsCallbacks* cb, void* user)
{
    if (cb == NULL) {
        g_prefsCallbacks.preferences_changed = NULL;
        g_prefsUser = NULL;
    } else {
        g_prefsCallbacks = *cb;   // by value: this library never retains the caller's struct
        g_prefsUser = user;
    }
    return GWIN_OK;
}

int32_t gwin_prefs_query_ui_settings(GwinUiSettings* out)
{
    if (out == NULL) {
        return GWIN_ERR_INVALID_ARG;
    }

    PlatformSupport* support = GlassApplication::GetPlatformSupport();
    if (support == NULL) {
        // No toolkit window, so nothing was ever activated. Same "no keys" as collectUISettings.
        ::memset(out, 0, sizeof(GwinUiSettings));
        return GWIN_OK;
    }

    support->collectUISettings(*out);
    return GWIN_OK;
}

int32_t gwin_prefs_query_network(GwinNetworkInfo* out)
{
    if (out == NULL) {
        return GWIN_ERR_INVALID_ARG;
    }

    PlatformSupport* support = GlassApplication::GetPlatformSupport();
    if (support == NULL) {
        ::memset(out, 0, sizeof(GwinNetworkInfo));
        return GWIN_OK;
    }

    support->collectNetworkInfo(*out);
    return GWIN_OK;
}

int32_t gwin_test_prefs_capture(int32_t on)
{
    ::InterlockedExchange(&s_captureDelegates, on != 0 ? 1 : 0);
    if (on == 0) {
        DropDelegate(&s_keptAutoHide);
        DropDelegate(&s_keptNetwork);
    }
    return GWIN_OK;
}

int32_t gwin_test_prefs_invoke(int32_t which)
{
    if (which == GWIN_TEST_DELEGATE_AUTO_HIDE) {
        AutoHideHandler* handler = s_keptAutoHide;
        return handler != NULL ? (int32_t) handler->Invoke(NULL, NULL) : GWIN_ERR_INVALID_ARG;
    }
    if (which == GWIN_TEST_DELEGATE_NETWORK) {
        INetworkStatusChangedEventHandler* handler = s_keptNetwork;
        return handler != NULL ? (int32_t) handler->Invoke(NULL) : GWIN_ERR_INVALID_ARG;
    }
    return GWIN_ERR_INVALID_ARG;
}

} // extern "C"
