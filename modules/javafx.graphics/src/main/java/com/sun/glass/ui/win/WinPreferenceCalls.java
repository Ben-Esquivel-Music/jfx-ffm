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

package com.sun.glass.ui.win;

import com.sun.glass.ui.win.WinGlassNative.HighContrast;
import com.sun.glass.ui.win.WinGlassNative.NetworkInfo;
import com.sun.glass.ui.win.WinGlassNative.UiSettings;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import static com.sun.glass.ui.win.WinCallSupport.codeUnitsToNul;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;
import static com.sun.glass.ui.win.WinCallSupport.requireOk;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bindUser32;
import static com.sun.glass.ui.win.WinGlassNative.bound;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static com.sun.glass.ui.win.WinGlassNative.upcallStub;
import static com.sun.glass.ui.win.WinGlassNative.upcallTarget;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The {@link WinGlassNative} calls of {@link WinPreferences}: both halves of {@code PlatformSupport.cpp}'s
 * preference map and the one upcall of it that survives, the preferences-changed event.
 * <p>
 * Of the eleven upcalls {@code PlatformSupport.cpp} had, ten existed only so that C could build a
 * {@code java.util.Map} (they have since been deleted); two of the four sources that filled that map - the eight
 * {@code user32!GetSysColor} calls of {@code querySystemColors} and the two {@code user32!SystemParametersInfoW}
 * calls of {@code querySystemParameters} - were {@code WRAPPER}s and are bound here, with {@link WinPreferences}
 * turning them into preference entries. The other two sources are WinRT ({@code IUISettings},
 * {@code INetworkInformation}) and stay native.
 * <p>
 * The WinRT half is five exports of {@code GLASS_WIN_ABI_VERSION} 2: {@code gwin_prefs_query_ui_settings} and
 * {@code gwin_prefs_query_network} hand out POD instead of building a {@code java.util.Map} in C,
 * {@code gwin_sizeof_ui_settings} and {@code gwin_sizeof_network_info} pin the two layouts, and
 * {@code gwin_prefs_set_callbacks} installs the one upcall that survives, the preferences-changed
 * event. Unlike the timer's stub it has a close path ({@link #installPreferencesCallback}).
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code Utils.cpp}) refer to
 * those files at commit {@code 8492cb03b0}
 * ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-glass/win/<file>}).
 */
final class WinPreferenceCalls {

    /*
     * winuser.h: the two SystemParametersInfoW actions PlatformSupport::querySystemParameters used
     * and the HIGHCONTRAST flag it tested.
     */
    static final int SPI_GETHIGHCONTRAST = 0x0042;
    static final int SPI_GETCLIENTAREAANIMATION = 0x1042;
    static final int HCF_HIGHCONTRASTON = 0x00000001;

    /*
     * winuser.h: the eight GetSysColor indices of PlatformSupport::querySystemColors, in the order it
     * queried them. COLOR_3DFACE is winuser.h's alias for COLOR_BTNFACE. A wrong index here returns
     * a plausible colour instead of failing, which is why glass_win_api.cpp
     * static_asserts all eight.
     */
    static final int COLOR_3DFACE = 15;
    static final int COLOR_BTNTEXT = 18;
    static final int COLOR_GRAYTEXT = 17;
    static final int COLOR_HIGHLIGHT = 13;
    static final int COLOR_HIGHLIGHTTEXT = 14;
    static final int COLOR_HOTLIGHT = 26;
    static final int COLOR_WINDOW = 5;
    static final int COLOR_WINDOWTEXT = 8;

    /*
     * enum GwinPreferenceType (glass_win_api.h), which is PlatformSupport::PreferenceType
     * (PlatformSupport.h). A bitmask of the four sources of the preference map, not an index.
     * GWIN_PT_ALL is what WinApplication.getPlatformPreferences collects; the values the native side
     * sends to the callback are 2, 4, 5, 6, 8 and 12 (4 | 8: the types the two WinRT sinks kept after a
     * failed post, ORed by PlatformSupport::PostPreferencesChanged and delivered in one call by
     * PlatformSupport::RunDeferredPreferences).
     */
    static final int GWIN_PT_SYSTEM_COLORS = 1;
    static final int GWIN_PT_SYSTEM_PARAMS = 2;
    static final int GWIN_PT_UI_SETTINGS = 4;
    static final int GWIN_PT_NETWORK_INFORMATION = 8;
    static final int GWIN_PT_ALL = 15;

    /**
     * {@code GWIN_UI_COLOR_COUNT}: Background, Foreground, AccentDark3..1, Accent, AccentLight1..3,
     * in the order {@code PlatformSupport::queryUISettings} asked for them, which is the order
     * {@link WinPreferences} names the nine {@code Windows.UIColor.*} keys in.
     */
    static final int GWIN_UI_COLOR_COUNT = 9;

    /*
     * enum GwinNetworkCost (glass_win_api.h). These are NOT the WinRT NetworkCostType enumerators -
     * there Fixed is 2 and Variable is 3 - because the C picked its string with an explicit switch and
     * this ABI pins its own numbering. gwin_prefs_query_network maps between them.
     */
    static final int GWIN_NET_COST_UNKNOWN = 0;
    static final int GWIN_NET_COST_UNRESTRICTED = 1;
    static final int GWIN_NET_COST_VARIABLE = 2;
    static final int GWIN_NET_COST_FIXED = 3;

    /** {@code HIGHCONTRASTW} (winuser.h) on x64; see {@link #highContrast} for how it is passed and read. */
    static final StructLayout HIGHCONTRAST_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            JAVA_INT.withName("dwFlags"),
            ADDRESS.withName("lpszDefaultScheme"));

    /**
     * {@code GwinUiSettings} ({@code glass_win_api.h}): the nine colours are a {@code uint32_t[9]} inside the
     * struct, not a pointer, so they are a sequence layout and are read in place.
     * <p>
     * Every flag is its own field because the C returned out of {@code queryUISettings} on the first
     * {@code RoException} and so dropped every later key too: a 0 flag means "no key at all for this
     * group", never "false" (the three {@code catch (RoException const&)} arms of
     * {@code PlatformSupport::queryUISettings} each {@code return}). The three flags are monotone.
     * {@link #GWIN_SIZEOF_UI_SETTINGS} exists so that a drift in this layout fails a test rather
     * than silently misreading the struct.
     */
    static final StructLayout GWIN_UI_SETTINGS_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("available"),
            JAVA_INT.withName("colors_valid"),
            MemoryLayout.sequenceLayout(GWIN_UI_COLOR_COUNT, JAVA_INT).withName("colors"),
            JAVA_INT.withName("advanced_effects_valid"),
            JAVA_INT.withName("advanced_effects_enabled"),
            JAVA_INT.withName("auto_hide_valid"),
            JAVA_INT.withName("auto_hide_scroll_bars"));

    static final StructLayout GWIN_NETWORK_INFO_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("available"),
            JAVA_INT.withName("cost_type"));

    static final StructLayout GWIN_PREFS_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("preferences_changed"));

    private static final long HC_CBSIZE_OFFSET = highContrastOffset("cbSize");
    private static final long HC_FLAGS_OFFSET = highContrastOffset("dwFlags");
    private static final long HC_SCHEME_OFFSET = highContrastOffset("lpszDefaultScheme");
    private static final long UI_AVAILABLE_OFFSET = uiSettingsOffset("available");
    private static final long UI_COLORS_VALID_OFFSET = uiSettingsOffset("colors_valid");
    private static final long UI_COLORS_OFFSET = uiSettingsOffset("colors");
    private static final long UI_ADVANCED_VALID_OFFSET = uiSettingsOffset("advanced_effects_valid");
    private static final long UI_ADVANCED_ENABLED_OFFSET = uiSettingsOffset("advanced_effects_enabled");
    private static final long UI_AUTO_HIDE_VALID_OFFSET = uiSettingsOffset("auto_hide_valid");
    private static final long UI_AUTO_HIDE_OFFSET = uiSettingsOffset("auto_hide_scroll_bars");
    private static final long NET_AVAILABLE_OFFSET = networkInfoOffset("available");
    private static final long NET_COST_TYPE_OFFSET = networkInfoOffset("cost_type");
    private static final long PREFS_CHANGED_OFFSET =
            GWIN_PREFS_CALLBACKS_LAYOUT.byteOffset(PathElement.groupElement("preferences_changed"));

    private static final MethodHandle GWIN_SIZEOF_UI_SETTINGS = bindGlass("gwin_sizeof_ui_settings",
            FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle GWIN_SIZEOF_NETWORK_INFO = bindGlass("gwin_sizeof_network_info",
            FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle GWIN_PREFS_SET_CALLBACKS = bindGlass("gwin_prefs_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code int32_t gwin_prefs_query_ui_settings(GwinUiSettings* out)}; zero-fills {@code out}. */
    private static final MethodHandle GWIN_PREFS_QUERY_UI_SETTINGS =
            bindGlass("gwin_prefs_query_ui_settings", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** {@code int32_t gwin_prefs_query_network(GwinNetworkInfo* out)}; zero-fills {@code out}. */
    private static final MethodHandle GWIN_PREFS_QUERY_NETWORK =
            bindGlass("gwin_prefs_query_network", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle GET_SYS_COLOR = bindUser32("GetSysColor",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /**
     * {@code BOOL SystemParametersInfoW(UINT uiAction, UINT uiParam, PVOID pvParam, UINT fWinIni)} -
     * {@code PlatformSupport::querySystemParameters}. The {@code W} form is what {@code ::SystemParametersInfo}
     * resolved to in the C, which compiles with {@code UNICODE} defined, and it is what makes
     * {@code HIGHCONTRAST.lpszDefaultScheme} a UTF-16 string.
     */
    private static final MethodHandle SYSTEM_PARAMETERS_INFO_W = bindUser32("SystemParametersInfoW",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));

    private WinPreferenceCalls() {
    }

    private static long highContrastOffset(String field) {
        return HIGHCONTRAST_LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    private static long uiSettingsOffset(String field) {
        return GWIN_UI_SETTINGS_LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    private static long networkInfoOffset(String field) {
        return GWIN_NETWORK_INFO_LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    /**
     * {@code GetSysColor(nIndex)}, returning the raw {@code COLORREF}. It is {@code 0x00BBGGRR}: use
     * {@link #sysColorRed}, {@link #sysColorGreen} and {@link #sysColorBlue} rather than shifting it
     * by hand, because the wrong order yields plausible colours that no test would question.
     */
    static int sysColor(int index) {
        try {
            return (int) GET_SYS_COLOR.invokeExact(index);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** wingdi.h {@code GetRValue}: the LOW byte of a {@code COLORREF}. */
    static int sysColorRed(int colorRef) {
        return colorRef & 0xFF;
    }

    /** wingdi.h {@code GetGValue}. */
    static int sysColorGreen(int colorRef) {
        return (colorRef >>> 8) & 0xFF;
    }

    /** wingdi.h {@code GetBValue}: the HIGH byte of the three, not the low one. */
    static int sysColorBlue(int colorRef) {
        return (colorRef >>> 16) & 0xFF;
    }

    /**
     * {@code PlatformSupport::querySystemParameters}' first call, which passes the structure size
     * <em>twice</em> - once in {@code cbSize} and once as {@code uiParam} - because
     * {@code SPI_GETHIGHCONTRAST} is documented to want both and the C did exactly that.
     * <p>
     * One deliberate, strictly safer difference from the C, of the same kind as {@link WinRobotCalls#robotCapture}'s
     * zeroed buffer: the C left {@code lpszDefaultScheme} uninitialised, so a
     * {@code SystemParametersInfoW} that returned {@code TRUE} without writing it dereferenced stack
     * garbage; the arena zero-fills, so the same case yields a {@code NULL} pointer here, which is
     * read as "no scheme". The string itself is read exactly as the C read it ({@link #schemeName}); the
     * memory belongs to Windows and is neither retained nor freed.
     *
     * @return {@code null} when the call failed, which means "put neither high-contrast key"
     */
    static HighContrast highContrast() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(HIGHCONTRAST_LAYOUT);
            int structureSize = (int) HIGHCONTRAST_LAYOUT.byteSize();
            info.set(JAVA_INT, HC_CBSIZE_OFFSET, structureSize);
            if (systemParametersInfo(SPI_GETHIGHCONTRAST, structureSize, info) == 0) {
                return null;
            }
            boolean on = (info.get(JAVA_INT, HC_FLAGS_OFFSET) & HCF_HIGHCONTRASTON) != 0;
            return new HighContrast(on, on ? schemeName(info.get(ADDRESS, HC_SCHEME_OFFSET)) : null);
        }
    }

    /**
     * {@code PlatformSupport::querySystemParameters}' second call:
     * {@code SystemParametersInfo(SPI_GETCLIENTAREAANIMATION, 0, &BOOL, 0)}. The C's {@code BOOL} was
     * uninitialised and reached {@code putBoolean(const bool)}, so its semantics are "any nonzero is
     * true"; the arena zero-fills, so a {@code TRUE} return that writes nothing gives {@code false}
     * instead of garbage.
     *
     * @return {@code null} when the call failed, which means "do not put the key"
     */
    static Boolean clientAreaAnimation() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            if (systemParametersInfo(SPI_GETCLIENTAREAANIMATION, 0, value) == 0) {
                return null;
            }
            return value.get(JAVA_INT, 0) != 0;
        }
    }

    /** {@code fWinIni} is 0 because the C never passed it. */
    private static int systemParametersInfo(int action, int uiParam, MemorySegment parameter) {
        try {
            return (int) SYSTEM_PARAMETERS_INFO_W.invokeExact(action, uiParam, parameter, 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The {@code LPWSTR} Windows wrote into {@code HIGHCONTRAST.lpszDefaultScheme}, read to its NUL as
     * code units ({@link WinCallSupport#codeUnitsToNul}). The bound is {@link Long#MAX_VALUE} rather than a guessed
     * maximum because the C measured the string with {@code wcslen} and had no maximum either;
     * {@link WinGlassNative#bound} is where the restricted call lives.
     * <p>
     * Package-private rather than private so that the shim can drive it with a synthetic string: the
     * real one only exists while high contrast is on, so this is otherwise the one line of the
     * preferences code that no automated test can reach.
     */
    static String schemeName(MemorySegment scheme) {
        if (scheme.address() == 0) {
            return null;
        }
        return codeUnitsToNul(bound(scheme, Long.MAX_VALUE));
    }

    /** {@code sizeof(GwinUiSettings)} as the C compiler saw it; {@link #GWIN_UI_SETTINGS_LAYOUT} must agree. */
    static int sizeOfUiSettings() {
        try {
            return (int) GWIN_SIZEOF_UI_SETTINGS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code sizeof(GwinNetworkInfo)}; {@link #GWIN_NETWORK_INFO_LAYOUT} must agree. */
    static int sizeOfNetworkInfo() {
        try {
            return (int) GWIN_SIZEOF_NETWORK_INFO.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The {@code IUISettings} half of the preference map. Call on the Glass toolkit thread: that is
     * the apartment those WinRT objects were activated in, and the thread the JNI collected on.
     * <p>
     * This is {@code PlatformSupport::queryUISettings} with the map building removed; for the validity flags
     * see {@link #GWIN_UI_SETTINGS_LAYOUT}. The two booleans came from an {@code unsigned char} handed to
     * {@code putBoolean(const bool)}, so their semantics are "any nonzero is true" and the C already reduced
     * them to that. {@code available} is also false when there is no live toolkit for {@code UISettings} to
     * be activated in - which is the case the JNI answered with a {@code NULL} map.
     *
     * @return a record whose {@code available} is false when there is no toolkit or no
     *         {@code UISettings} - not an exception, because the C answered that case with fewer keys
     */
    static UiSettings uiSettings() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(GWIN_UI_SETTINGS_LAYOUT);
            queryUiSettings(out);
            int[] colors = new int[GWIN_UI_COLOR_COUNT];
            MemorySegment.copy(out, JAVA_INT, UI_COLORS_OFFSET, colors, 0, GWIN_UI_COLOR_COUNT);
            return new UiSettings(out.get(JAVA_INT, UI_AVAILABLE_OFFSET) != 0,
                    out.get(JAVA_INT, UI_COLORS_VALID_OFFSET) != 0,
                    colors,
                    out.get(JAVA_INT, UI_ADVANCED_VALID_OFFSET) != 0,
                    out.get(JAVA_INT, UI_ADVANCED_ENABLED_OFFSET) != 0,
                    out.get(JAVA_INT, UI_AUTO_HIDE_VALID_OFFSET) != 0,
                    out.get(JAVA_INT, UI_AUTO_HIDE_OFFSET) != 0);
        }
    }

    /**
     * The {@code INetworkInformation} half of the preference map; same threading as {@link #uiSettings}. This is
     * {@code PlatformSupport::queryNetworkInformation} with the map building removed: {@code available} is false
     * when the activation factory was unavailable or an {@code RoException} was caught before the C reached its
     * {@code putString} - in both cases there is no key - and true with {@link #GWIN_NET_COST_UNKNOWN} is the
     * <em>different</em> case where the C did write the key, with the value {@code "Unknown"}.
     */
    static NetworkInfo networkInfo() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(GWIN_NETWORK_INFO_LAYOUT);
            queryNetwork(out);
            return new NetworkInfo(out.get(JAVA_INT, NET_AVAILABLE_OFFSET) != 0,
                    out.get(JAVA_INT, NET_COST_TYPE_OFFSET));
        }
    }

    private static void queryUiSettings(MemorySegment out) {
        try {
            int status = (int) GWIN_PREFS_QUERY_UI_SETTINGS.invokeExact(out);
            requireOk(status, "gwin_prefs_query_ui_settings");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static void queryNetwork(MemorySegment out) {
        try {
            int status = (int) GWIN_PREFS_QUERY_NETWORK.invokeExact(out);
            requireOk(status, "gwin_prefs_query_network");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static final FunctionDescriptor PREFS_CHANGED_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

    private static boolean preferencesCallbackInstalled;

    /**
     * Hands {@code glass.dll} the one callback that replaced the JNI body of
     * {@code PlatformSupport::updatePreferences}: {@link WinPreferences} collects, compares and
     * notifies. The map-building upcalls are gone, so a change that finds no callback installed is not
     * delivered at all.
     * <p>
     * <b>Call this exactly once, from {@code WinApplication}'s static initializer.</b> That runs on the
     * launcher thread before {@code runLoop} starts the Glass toolkit thread, so "installed before the
     * first {@code WndProc} message" holds by construction, and the only later write is
     * {@link #clearPreferencesCallback}, after the toolkit thread's last read. Installing it any later
     * drops every notification that arrives before the install; installing it lazily, on the first
     * preference read, would drop the ones during startup.
     * <p>
     * The table is copied by the library, so the confined arena that holds it is closed here. The stub
     * inside it is allocated in {@code stubArena}, which must outlive every call: only the toolkit
     * window's procedure dials the slot, on the toolkit thread, and that thread retrieves no message
     * once {@code gwin_run_loop} has returned, so an arena may be closed there after
     * {@link #clearPreferencesCallback} ({@code GwinPrefsCallbacks}, RUNDOWN).
     *
     * @param stubArena the arena of the upcall stub: {@code WinApplication}'s application arena, or
     *                  {@link Arena#global()} in the SWT-embedded case, which never calls
     *                  {@code gwin_run_loop} and so has no point after which the stub may be freed
     */
    static synchronized void installPreferencesCallback(Arena stubArena) {
        if (preferencesCallbackInstalled) {
            return;
        }
        MemorySegment callback = upcallStub(
                upcallTarget(WinPreferenceCalls.class, "onPreferencesChanged", PREFS_CHANGED_FD),
                PREFS_CHANGED_FD, stubArena);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GWIN_PREFS_CALLBACKS_LAYOUT);
            table.set(ADDRESS, PREFS_CHANGED_OFFSET, callback);
            int status = (int) GWIN_PREFS_SET_CALLBACKS.invokeExact(table, MemorySegment.NULL);
            requireOk(status, "gwin_prefs_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
        preferencesCallbackInstalled = true;
    }

    /**
     * {@code gwin_prefs_set_callbacks(NULL, NULL)}: clears the table, after which the library delivers
     * no preference change. <b>Call this only on the toolkit thread, after {@link WinApplicationCalls#runLoop} has
     * returned there</b>: the one reader of the slot runs on that thread inside its message retrieval,
     * so the clear comes after the last read and the setter needs no lock. Then the stub's arena may be
     * closed.
     */
    static void clearPreferencesCallback() {
        try {
            int status = (int) GWIN_PREFS_SET_CALLBACKS.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
            requireOk(status, "gwin_prefs_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static synchronized boolean preferencesCallbackInstalled() {
        return preferencesCallbackInstalled;
    }

    /**
     * The {@code GwinPrefsCallbacks.preferences_changed} body: {@code PlatformSupport::updatePreferences}
     * minus the collecting, which {@link WinPreferences#update} now does in Java. {@code user} is the
     * pointer {@link #installPreferencesCallback} passed, which is {@code NULL} - the registry the C
     * needed a {@code this} for is a static field here.
     * <p>
     * <b>Thread.</b> Always the Glass toolkit thread: inside {@code GlassApplication::WindowProc}, in the
     * {@code WM_SETTINGCHANGE} / {@code WM_THEMECHANGED} / {@code WM_SYSCOLORCHANGE} /
     * {@code WM_DWMCOLORIZATIONCOLORCHANGED} arms and the arm for what the two WinRT sinks post, or in
     * {@code PlatformSupport::RunDeferredPreferences}, for the types a sink kept, right after a retrieved
     * message was dispatched: after a {@code DispatchMessage} of a toolkit pump, or in three arms of that
     * procedure - {@code WM_DO_ACTION_LATER}, the wake a sink posts after keeping its types, and the
     * {@code WM_TIMER} of the one-shot timer it arms when that wake is refused too - which a pump that is
     * not Glass's (an OS modal loop, the SWT embedder's) dispatches like any other message; never inside
     * a sink or at a sent-message point. The sinks themselves may run elsewhere: they are WRL
     * {@code Callback<>} delegates, not agile by default - {@code Callback<>} builds them with
     * {@code RuntimeClassFlags<Delegate>}, {@code Delegate} is {@code ClassicCom}, and only {@code WinRt}
     * classes aggregate the free-threaded marshaler ({@code FtmBase}) - so each event source decides whether
     * to marshal its sink into the apartment {@code RoInitialize(RO_INIT_SINGLETHREADED)} created on the
     * toolkit thread or to call it on a WinRT thread of its own. Either way a sink only posts its types to
     * the toolkit window through the guarded post of {@code gwin_invoke_later}
     * ({@code PlatformSupport::PostPreferencesChanged}), or keeps them for the toolkit thread when that
     * {@code PostMessage} fails with the window published (its posted-message queue is at its quota) and
     * posts the wake; when the wake is refused too it arms the timer
     * ({@code GlassApplication::ArmPreferencesTimer}): {@code SetTimer} on the toolkit thread, else a
     * request sent there with {@code SendNotifyMessage}, which the toolkit thread handles at its next
     * message retrieval by arming the timer and nothing else. The {@code WM_TIMER} comes once the queue
     * holds no posted message. So a sink's notification reaches this asynchronously, on the toolkit
     * thread, and a sink invoked once the toolkit window is destroyed posts and keeps nothing. No call
     * starts after {@code gwin_run_loop} has returned (see {@link #clearPreferencesCallback}).
     * <p>
     * <b>It must not throw.</b> An exception escaping an upcall stub terminates the JVM, so
     * {@code Throwable} is caught and answered with 0, which is the C's "nothing changed" and its
     * {@code DefWindowProc} arm. The reporting is guarded in turn, exactly as
     * {@code CheckAndClearException} re-checked after its own report ({@code Utils.cpp:64-67}).
     * <p>
     * Package-private, like {@link WinMenuCalls#onNotifyCommand}, so that {@code WinGlassNativeShim} can drive the
     * target and {@code WinPreferencesNativeTest} can assert the swallow; nothing but the stub calls it.
     *
     * @return 1 if the preferences changed, 0 otherwise; {@code GlassApplication::WindowProc} chooses
     *         between {@code return 0} and {@code DefWindowProc} on this, and
     *         {@code RunDeferredPreferences} does not use it
     */
    static int onPreferencesChanged(MemorySegment user, int types) {
        try {
            return WinPreferences.update(types) ? 1 : 0;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return 0;
        }
    }
}
