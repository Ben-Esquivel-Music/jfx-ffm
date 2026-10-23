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

#pragma once

/*
 * This flag gives us function prototypes without the __declspec(dllimport) storage class specifier.
 * RoActivationSupport defines symbols locally, and having the dllimport specifier would trigger LNK4217.
 */
#define _ROAPI_

#include <common.h>
#include <wrl.h>
#include <windows.ui.viewmanagement.h>
#include <windows.networking.connectivity.h>

#include "glass_win_api.h"

class PlatformSupport final
{
public:
    enum PreferenceType {
        PT_SYSTEM_COLORS = 1,
        PT_SYSTEM_PARAMS = 2,
        PT_UI_SETTINGS = 4,
        PT_NETWORK_INFORMATION = 8,
        PT_ALL = PT_SYSTEM_COLORS | PT_SYSTEM_PARAMS | PT_UI_SETTINGS | PT_NETWORK_INFORMATION
    };

    /**
     * The instance GlassApplication() builds for gwin_app_create: WinRT activation only, no JNI
     * state (there is no JNI constructor).
     */
    PlatformSupport();
    ~PlatformSupport();
    PlatformSupport(PlatformSupport const&) = delete;
    PlatformSupport& operator=(PlatformSupport const&) = delete;

    /**
     * Hand the specified preference types to GwinPrefsCallbacks.preferences_changed (glass_win_api.h), which
     * collects them, compares them and notifies the JavaFX application in Java. Returns true when it reports
     * a change; false when it does not, or when no callback table is installed. Toolkit thread only: it is
     * called from GlassApplication::WindowProc, also for what the two WinRT sinks post there.
     */
    bool updatePreferences(PreferenceType) const;

    /**
     * Handles the WM_SETTINGCHANGE message.
    */
    bool onSettingChanged(WPARAM, LPARAM) const;

    /**
     * Fill the flat C ABI's GwinUiSettings / GwinNetworkInfo (glass_win_api.h) from the WinRT objects
     * this instance activated. These are the POD half of the former queryUISettings /
     * queryNetworkInformation: same OS calls, same order, same tolerated failures, but no JNIEnv and no
     * java/util/Map - Java builds the map. Both zero-fill their out-parameter first. Reached from
     * gwin_prefs_query_*.
     */
    void collectUISettings(GwinUiSettings& out) const;
    void collectNetworkInfo(GwinNetworkInfo& out) const;

private:
    /*
     * Every WinRT object this instance holds, and the two registrations with their tokens. The destructor
     * unregisters and releases them all in its body, before uninitializeRoActivationSupport ends the
     * apartment - as members they would only be released after it.
     */
    Microsoft::WRL::ComPtr<ABI::Windows::UI::ViewManagement::IUISettings> settings;
    Microsoft::WRL::ComPtr<ABI::Windows::UI::ViewManagement::IUISettings5> settings5;
    Microsoft::WRL::ComPtr<IActivationFactory> networkFactory;
    Microsoft::WRL::ComPtr<ABI::Windows::Networking::Connectivity::INetworkInformationStatics> networkInformation;
    EventRegistrationToken autoHideToken;
    EventRegistrationToken networkToken;
    bool autoHideRegistered;
    bool networkRegistered;

    // RoInitialize, then UISettings and NetworkInformation with their two event sinks.
    void InitializeWinRT();

    // What the two sinks do on the OS's thread: post the types to the toolkit window (PlatformSupport.cpp).
    static void PostPreferencesChanged(PreferenceType);
};
