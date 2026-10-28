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

import com.sun.glass.ui.Screen;
import com.sun.glass.ui.win.WinCursorCalls.Gdi32;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static com.sun.glass.ui.win.WinApplicationCalls.MAX_PATH;
import static com.sun.glass.ui.win.WinCallSupport.RECT_BOTTOM_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.RECT_LAYOUT;
import static com.sun.glass.ui.win.WinCallSupport.RECT_LEFT_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.RECT_RIGHT_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.RECT_TOP_OFFSET;
import static com.sun.glass.ui.win.WinCallSupport.codeUnitsToNul;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;
import static com.sun.glass.ui.win.WinCallSupport.requireOk;
import static com.sun.glass.ui.win.WinCallSupport.wideString;
import static com.sun.glass.ui.win.WinGlassNative.bind;
import static com.sun.glass.ui.win.WinGlassNative.bindGlass;
import static com.sun.glass.ui.win.WinGlassNative.bindKernel32;
import static com.sun.glass.ui.win.WinGlassNative.bindUser32;
import static com.sun.glass.ui.win.WinGlassNative.systemLibrary;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static com.sun.glass.ui.win.WinGlassNative.upcallStub;
import static com.sun.glass.ui.win.WinGlassNative.upcallTarget;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} calls of the screen enumeration ({@code GlassScreen.cpp}) and the one-slot screen
 * callback table.
 * <p>
 * <b>The last of {@code GlassApplication.cpp} and {@code GlassScreen.cpp}</b> (ABI 5). The screen
 * enumeration was {@code WRAPPER} plus {@code PURE}: two {@code EnumDisplayMonitors} passes,
 * {@code GetMonitorInfoW}, a display DC for {@code GetDeviceCaps} and {@code shcore!GetDpiForMonitor},
 * then an anchoring computation that touches no OS at all. The first half is bound here
 * ({@link #collectMonitors()}, with {@code GlassScreen::LoadDPIFuncs} as {@link #loadDpiFuncs}); the
 * second is {@link WinScreenLayout}; and the {@code Screen} objects the JNI built in C are built in
 * Java. What stays native is what only the {@code WndProc} can see - that the monitors may have
 * changed - which is the one-slot {@code GwinScreenCallbacks} ({@link #installScreenCallbacks}).
 */
final class WinScreenCalls {

    /*
     * The Win32 values of GlassScreen.cpp's former enumeration. glass_win_api.cpp pins
     * MONITORINFOF_PRIMARY, the GetDeviceCaps indices and CCHDEVICENAME by static_assert. It pins the two
     * shcore enums too: all five values Java passes - MDT_EFFECTIVE_DPI, MDT_RAW_DPI, PROCESS_DPI_UNAWARE,
     * PROCESS_SYSTEM_DPI_AWARE and PROCESS_PER_MONITOR_DPI_AWARE - against the SDK's own definitions in
     * <ShellScalingApi.h>, included for its declarations only (nothing in glass.dll calls shcore). The
     * former GlassScreen.cpp pin only checked that file's private copies of the enums and was deleted
     * with them.
     */
    static final int MONITORINFOF_PRIMARY = 1;
    static final int BITSPIXEL = 12;
    static final int PLANES = 14;
    static final int LOGPIXELSX = 88;
    static final int LOGPIXELSY = 90;
    static final int CCHDEVICENAME = 32;
    static final int MDT_EFFECTIVE_DPI = 0;
    static final int MDT_RAW_DPI = 2;
    static final int PROCESS_PER_MONITOR_DPI_AWARE = 2;

    /** winerror.h {@code S_OK}: the only {@code HRESULT} {@code GetMonitorSettings} tested for. */
    static final int S_OK = 0;

    /**
     * {@code GwinScreenCallbacks} ({@code glass_win_api.h}): one function pointer, the
     * {@code Screen.notifySettingsChanged()} upcall of {@code GlassScreen::HandleDisplayChange}. No
     * {@code void* user}: the event has no identity. {@code gwin_sizeof_screen_callbacks} pins the size;
     * the name is the one {@code glass_win_api.cpp}'s {@code static_assert} message cites.
     */
    static final StructLayout SCREEN_CALLBACKS_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("settings_changed"));

    /**
     * {@code MONITORINFOEXW} (winuser.h): 104 bytes, no padding - {@code cbSize} at 0, the two
     * {@link WinCallSupport#RECT_LAYOUT}s at 4 and 20, {@code dwFlags} at 36, then {@code WCHAR
     * szDevice[CCHDEVICENAME]} in place at 40. {@code GetMonitorSettings} passed the struct with {@code cbSize =
     * sizeof(MONITORINFOEX)}, which is what makes {@code GetMonitorInfoW} fill {@code szDevice} at all, and then handed
     * {@code szDevice}'s address to {@code CreateDCW}. Pinned by {@code static_assert} in
     * {@code glass_win_api.cpp} (on the {@code MONITORINFO} base, which is a standard-layout type).
     */
    static final StructLayout MONITORINFOEXW_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            RECT_LAYOUT.withName("rcMonitor"),
            RECT_LAYOUT.withName("rcWork"),
            JAVA_INT.withName("dwFlags"),
            MemoryLayout.sequenceLayout(CCHDEVICENAME, JAVA_CHAR).withName("szDevice"));

    private static final long MIX_CBSIZE_OFFSET = monitorInfoOffset("cbSize");
    private static final long MIX_MONITOR_OFFSET = monitorInfoOffset("rcMonitor");
    private static final long MIX_WORK_OFFSET = monitorInfoOffset("rcWork");
    private static final long MIX_FLAGS_OFFSET = monitorInfoOffset("dwFlags");
    private static final long MIX_DEVICE_OFFSET = monitorInfoOffset("szDevice");
    private static final long SETTINGS_CHANGED_OFFSET =
            SCREEN_CALLBACKS_LAYOUT.byteOffset(PathElement.groupElement("settings_changed"));

    /*
     * The screen section of glass_win_api.h: the installer and sizeof probe of the one-slot
     * table HandleDisplayChange dials. Its test hook, gwin_test_fire_screen_callback, is bound by
     * WinGlassNativeShim, never here (as gwin_test_screen_anchor was, until it was deleted).
     */

    private static final MethodHandle GWIN_SIZEOF_SCREEN_CALLBACKS =
            bindGlass("gwin_sizeof_screen_callbacks", FunctionDescriptor.of(JAVA_INT));

    /** {@code int32_t gwin_screen_set_callbacks(const GwinScreenCallbacks* cb)} - copied by value, always OK. */
    private static final MethodHandle GWIN_SCREEN_SET_CALLBACKS = bindGlass("gwin_screen_set_callbacks",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /*
     * The user32 and kernel32 calls of GlassScreen.cpp's enumeration. HMONITORs and HDCs are
     * handle values this process never dereferences: they cross as ADDRESS and are never reinterpret()ed.
     */

    /** {@code BOOL EnumDisplayMonitors(HDC hdc, LPCRECT lprcClip, MONITORENUMPROC lpfnEnum, LPARAM dwData)}. */
    private static final MethodHandle ENUM_DISPLAY_MONITORS = bindUser32("EnumDisplayMonitors",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

    /**
     * {@code BOOL GetMonitorInfoW(HMONITOR hMonitor, LPMONITORINFO lpmi)} - the {@code W} form
     * {@code ::GetMonitorInfo} resolved to under {@code UNICODE}, which is what makes {@code szDevice}
     * UTF-16. Fills {@link #MONITORINFOEXW_LAYOUT}.
     */
    private static final MethodHandle GET_MONITOR_INFO_W = bindUser32("GetMonitorInfoW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** {@code BOOL SetProcessDPIAware(void)} - {@code LoadDPIFuncs}' fallback when {@code shcore} is not there. */
    private static final MethodHandle SET_PROCESS_DPI_AWARE = bindUser32("SetProcessDPIAware",
            FunctionDescriptor.of(JAVA_INT));

    /**
     * {@code UINT GetSystemDirectoryW(LPWSTR lpBuffer, UINT uSize)} - the directory {@code LoadDPIFuncs}
     * loaded {@code SHCore.dll} from, by full path ({@link Shcore}). {@code uSize} is in characters.
     */
    private static final MethodHandle GET_SYSTEM_DIRECTORY_W = bindKernel32("GetSystemDirectoryW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    /**
     * {@code SHCore.dll} and the three DPI functions {@code GlassScreen::LoadDPIFuncs} looked up in it,
     * resolved on the first {@link #loadDpiFuncs} - which is also where the C loaded it.
     * <p>
     * The load is the C's, by full path under {@code GetSystemDirectoryW}; only when that load fails is it
     * retried by the bare name ({@code load()} says why). The three symbols are kept <b>all or none</b>, from
     * whichever load succeeded ({@code LoadDPIFuncs} zeroed all three pointers when one was missing), and a
     * directory or a library that cannot be had is "none", not an error: it selects {@code SetProcessDPIAware}
     * and the {@code LOGPIXELSX/Y} resolution, exactly as the C's NULL pointers did. So
     * {@link WinGlassNative#boundSymbols()} gains these three only when {@link #RESOLVED}.
     * One difference from the C, unreachable on a Windows a JDK 26 supports: a system directory of
     * {@code MAX_PATH} characters or more is "none" here where the C appended to an unterminated buffer.
     */
    static final class Shcore {

        static final String NAME = "shcore";

        private static final SymbolLookup NOTHING = name -> Optional.empty();

        /** The library for the life of the process, as the C never freed it; {@link #NOTHING} when it failed. */
        static final SymbolLookup LOOKUP = load();

        /** Whether all three resolved. When false, none is bound and every handle below is {@code null}. */
        static final boolean RESOLVED = LOOKUP.find("GetProcessDpiAwareness").isPresent()
                && LOOKUP.find("SetProcessDpiAwareness").isPresent()
                && LOOKUP.find("GetDpiForMonitor").isPresent();

        /* In LoadDPIFuncs' GetProcAddress order. */

        /** {@code HRESULT GetProcessDpiAwareness(HANDLE hprocess, PROCESS_DPI_AWARENESS* value)}. */
        private static final MethodHandle GET_PROCESS_DPI_AWARENESS = RESOLVED
                ? bindShcore("GetProcessDpiAwareness", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
                : null;

        /** {@code HRESULT SetProcessDpiAwareness(PROCESS_DPI_AWARENESS value)}. */
        private static final MethodHandle SET_PROCESS_DPI_AWARENESS = RESOLVED
                ? bindShcore("SetProcessDpiAwareness", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
                : null;

        /**
         * {@code HRESULT GetDpiForMonitor(HMONITOR hmonitor, MONITOR_DPI_TYPE dpiType, UINT* dpiX, UINT* dpiY)};
         * the enum is a 32-bit {@code int}.
         */
        private static final MethodHandle GET_DPI_FOR_MONITOR = RESOLVED
                ? bindShcore("GetDpiForMonitor", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS))
                : null;

        private Shcore() {
        }

        private static SymbolLookup load() {
            String directory = systemDirectory();
            if (directory == null) {
                return NOTHING;
            }
            try {
                return systemLibrary(Path.of(directory + "\\SHCore.dll"));
            } catch (IllegalArgumentException e) {
                // InvalidPathException is one too. Not necessarily the C's NULL: see the retry below.
            }
            // The C's LoadLibraryW takes the UTF-16 path as it is; the JDK first converts it to the ANSI code
            // page (RawNativeLibraries.load0, JNU_GetStringPlatformChars), which fails for a system directory
            // outside that code page - where the C loaded SHCore and this would silently fall back to
            // SetProcessDPIAware and LOGPIXELS. So a failed full-path load is retried by bare name before it
            // counts as "none". SHCORE is a KnownDLL: the bare name resolves to the system directory's copy
            // only, through the loader's KnownDLLs section, never through the search path.
            try {
                return systemLibrary("SHCore.dll");
            } catch (IllegalArgumentException e) {
                // Both loads failed: the C's LoadLibrary returned NULL too - no DPI functions.
                return NOTHING;
            }
        }

        private static MethodHandle bindShcore(String name, FunctionDescriptor descriptor) {
            return bind(LOOKUP, NAME, name, descriptor);
        }
    }

    private WinScreenCalls() {
    }

    private static long monitorInfoOffset(String field) {
        return MONITORINFOEXW_LAYOUT.byteOffset(PathElement.groupElement(field));
    }

    /** Guards {@link #dpiFuncsTried}. */
    private static final Object DPI_LOCK = new Object();

    /** {@code triedToFindDPIFuncs} ({@code GlassScreen.cpp}): whether {@link #loadDpiFuncs} has run. */
    private static boolean dpiFuncsTried;

    /**
     * {@code GlassScreen::LoadDPIFuncs(awareRequested)}, behind the same one-shot latch: the first call
     * resolves {@code SHCore.dll} ({@link Shcore}), sets the process DPI awareness -
     * {@code SetProcessDpiAwareness(awarenessRequested)} when the three shcore functions exist, else
     * {@code SetProcessDPIAware()}, both results ignored - and then calls {@code GetProcessDpiAwareness}
     * once and discards the answer, as the release C did (only its {@code fprintf} was {@code DEBUG_DPI}).
     * Every later call does nothing, whatever it asks for.
     * <p>
     * Two callers, as in C: the toolkit start-up, with the level {@code WinApplication} computed, on the
     * toolkit thread before {@link WinApplicationCalls#appCreate()}; and {@link #collectMonitors()}, with
     * {@link #PROCESS_PER_MONITOR_DPI_AWARE}, which only has an effect when no start-up ran first. Under
     * the JDK launchers neither changes anything - their manifest already declares PerMonitorV2, so
     * {@code SetProcessDpiAwareness} answers {@code E_ACCESSDENIED} - so the order matters to a
     * manifest-less host only, and no test in this module can observe it. <b>Never call it from a static
     * initializer.</b>
     * <p>
     * This is the only latch there is. Both callers of {@code GlassScreen::LoadDPIFuncs} and its own
     * {@code triedToFindDPIFuncs} - the JNI {@code _init} and {@code CreateJavaScreens} - lost their Java entry
     * points in the change set that made the enumeration Java, and all four have since been deleted
     * from {@code glass.dll}.
     */
    static void loadDpiFuncs(int awarenessRequested) {
        synchronized (DPI_LOCK) {
            if (dpiFuncsTried) {
                return;
            }
            dpiFuncsTried = true;
            try {
                if (Shcore.RESOLVED) {
                    int ignoredResult = (int) Shcore.SET_PROCESS_DPI_AWARENESS.invokeExact(awarenessRequested);
                } else {
                    int ignoredResult = (int) SET_PROCESS_DPI_AWARE.invokeExact();
                }
                if (Shcore.RESOLVED) {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment ignoredAwareness = arena.allocate(JAVA_INT);
                        int ignoredResult = (int) Shcore.GET_PROCESS_DPI_AWARENESS.invokeExact(MemorySegment.NULL,
                                ignoredAwareness);
                    }
                }
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    /**
     * Whether the three {@code shcore} DPI functions resolved, which initializes {@link Shcore} (loading
     * {@code SHCore.dll}) but sets nothing. For the tests that fix the order of the lazy holders in
     * {@link WinGlassNative#boundSymbols()}, and for {@code GetMonitorSettings}' choice between the DPI query and
     * {@code LOGPIXELSX/Y}.
     */
    static boolean dpiFunctionsResolved() {
        return Shcore.RESOLVED;
    }

    /** {@code MONITORENUMPROC}: {@code BOOL CALLBACK (HMONITOR, HDC, LPRECT, LPARAM)}. */
    private static final FunctionDescriptor MONITOR_ENUM_PROC_FD =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);

    /**
     * {@code GlassScreen::CreateJavaScreens} up to its arrangement, with {@code GetMonitorSettings} for
     * each monitor: one {@code EnumDisplayMonitors(NULL, NULL, proc, 0)} pass that only counts, then a
     * second that collects at most that many monitors, skipping a NULL handle. A monitor attached between
     * the two passes is left out, as the C left it out. The list is in enumeration order and empty when
     * Windows reported no monitor; {@link WinScreenLayout#arrange} turns that into the {@code null} the
     * JNI returned.
     * <p>
     * Each pass has its own upcall stub, in a confined arena that is closed when both passes have returned
     * - {@code EnumDisplayMonitors} is synchronous, so nothing can call a stub afterwards. The targets never
     * let a {@code Throwable} out, which would end the JVM: the first one is kept, the pass is allowed to
     * finish, and it is thrown from here once {@code EnumDisplayMonitors} has returned.
     * <p>
     * Runs on the calling thread - the toolkit thread in the product, from inside
     * {@code Screen.initScreens} and from inside the {@code settings_changed} upcall - and calls
     * {@link #loadDpiFuncs} with {@link #PROCESS_PER_MONITOR_DPI_AWARE} before the first monitor's DPI
     * query, as {@code GetMonitorSettings} did.
     */
    static List<WinScreenLayout.MonitorSettings> collectMonitors() {
        try (MonitorEnumeration enumeration = new MonitorEnumeration()) {
            return enumeration.collect();
        }
    }

    /** The state {@code CountMonitorsCallback} and {@code CollectMonitorsCallback} kept in {@code g_MonitorInfos}. */
    private static final class MonitorEnumeration implements AutoCloseable {

        private static final MethodHandle COUNT_TARGET = target("onCount");
        private static final MethodHandle COLLECT_TARGET = target("onCollect");

        private final Arena arena = Arena.ofConfined();
        private final List<WinScreenLayout.MonitorSettings> monitors = new ArrayList<>();

        /** {@code g_MonitorInfos.maxInfos}: what the counting pass saw. */
        private int maxInfos;

        private Throwable pending;

        private static MethodHandle target(String name) {
            try {
                return MethodHandles.lookup().findVirtual(MonitorEnumeration.class, name,
                        MONITOR_ENUM_PROC_FD.toMethodType());
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        List<WinScreenLayout.MonitorSettings> collect() {
            maxInfos = 0;
            enumerate(COUNT_TARGET);
            enumerate(COLLECT_TARGET);
            return monitors;
        }

        private void enumerate(MethodHandle target) {
            MemorySegment proc = upcallStub(target.bindTo(this), MONITOR_ENUM_PROC_FD, arena);
            try {
                int ignoredResult = (int) ENUM_DISPLAY_MONITORS.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                        proc, 0L);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            Throwable failure = pending;
            if (failure != null) {
                throw unexpected(failure);
            }
        }

        /** {@code CountMonitorsCallback}. */
        private int onCount(MemorySegment hMonitor, MemorySegment hdc, MemorySegment clip, long data) {
            try {
                maxInfos++;
            } catch (Throwable t) {
                remember(t);
            }
            return 1;
        }

        /** {@code CollectMonitorsCallback}: {@code GetMonitorSettings} into the next slot, if there is one. */
        private int onCollect(MemorySegment hMonitor, MemorySegment hdc, MemorySegment clip, long data) {
            try {
                if (!hMonitor.equals(MemorySegment.NULL) && monitors.size() < maxInfos) {
                    monitors.add(monitorSettings(hMonitor));
                }
            } catch (Throwable t) {
                remember(t);
            }
            return 1;
        }

        /** The first failure of a pass is thrown when the pass has returned; later ones ride along with it. */
        private void remember(Throwable t) {
            if (pending == null) {
                pending = t;
            } else {
                pending.addSuppressed(t);
            }
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    /**
     * {@code GetMonitorSettings(hMonitor, mis)}, statement for statement, returning what it wrote:
     * {@code GetMonitorInfoW} into a zeroed {@code MONITORINFOEXW} whose result is ignored (a stale handle
     * publishes zeros, as it did); a display DC for {@code szDevice}, whose NULL is not checked either
     * ({@code GetDeviceCaps(NULL, ...)} answers 0); the colour depth as {@code BITSPIXEL * PLANES}; then,
     * with {@code shcore}, the effective DPI - {@code LOGPIXELSX/Y} if that query fails - as the UI DPI,
     * and the raw DPI into the <b>same</b> two {@code UINT}s with its result ignored, so a failed raw query
     * leaves the effective values there; without {@code shcore}, {@code LOGPIXELSX/Y} for both.
     */
    private static WinScreenLayout.MonitorSettings monitorSettings(MemorySegment hMonitor) {
        loadDpiFuncs(PROCESS_PER_MONITOR_DPI_AWARE);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mix = arena.allocate(MONITORINFOEXW_LAYOUT);
            mix.set(JAVA_INT, MIX_CBSIZE_OFFSET, (int) MONITORINFOEXW_LAYOUT.byteSize());
            int ignoredInfoResult = (int) GET_MONITOR_INFO_W.invokeExact(hMonitor, mix);

            MemorySegment hDC = (MemorySegment) Gdi32.CREATE_DC_W.invokeExact(wideString(arena, "DISPLAY"),
                    mix.asSlice(MIX_DEVICE_OFFSET), MemorySegment.NULL, MemorySegment.NULL);

            boolean primary = (mix.get(JAVA_INT, MIX_FLAGS_OFFSET) & MONITORINFOF_PRIMARY) != 0;
            int colorDepth = deviceCaps(hDC, BITSPIXEL) * deviceCaps(hDC, PLANES);
            MemorySegment resx = arena.allocate(JAVA_INT, 2);
            MemorySegment resy = resx.asSlice(JAVA_INT.byteSize());
            int uiresx;
            int uiresy;
            if (Shcore.RESOLVED) {
                int res = (int) Shcore.GET_DPI_FOR_MONITOR.invokeExact(hMonitor, MDT_EFFECTIVE_DPI, resx, resy);
                if (res != S_OK) {
                    resx.set(JAVA_INT, 0, deviceCaps(hDC, LOGPIXELSX));
                    resy.set(JAVA_INT, 0, deviceCaps(hDC, LOGPIXELSY));
                }
                uiresx = resx.get(JAVA_INT, 0);
                uiresy = resy.get(JAVA_INT, 0);
                int ignoredRawResult = (int) Shcore.GET_DPI_FOR_MONITOR.invokeExact(hMonitor, MDT_RAW_DPI, resx, resy);
            } else {
                resx.set(JAVA_INT, 0, deviceCaps(hDC, LOGPIXELSX));
                resy.set(JAVA_INT, 0, deviceCaps(hDC, LOGPIXELSY));
                uiresx = resx.get(JAVA_INT, 0);
                uiresy = resy.get(JAVA_INT, 0);
            }
            int dpiX = resx.get(JAVA_INT, 0);
            int dpiY = resy.get(JAVA_INT, 0);

            int ignoredDeleteResult = (int) Gdi32.DELETE_DC.invokeExact(hDC);

            return new WinScreenLayout.MonitorSettings(hMonitor.address(),
                    mix.get(JAVA_INT, MIX_MONITOR_OFFSET + RECT_LEFT_OFFSET),
                    mix.get(JAVA_INT, MIX_MONITOR_OFFSET + RECT_TOP_OFFSET),
                    mix.get(JAVA_INT, MIX_MONITOR_OFFSET + RECT_RIGHT_OFFSET),
                    mix.get(JAVA_INT, MIX_MONITOR_OFFSET + RECT_BOTTOM_OFFSET),
                    mix.get(JAVA_INT, MIX_WORK_OFFSET + RECT_LEFT_OFFSET),
                    mix.get(JAVA_INT, MIX_WORK_OFFSET + RECT_TOP_OFFSET),
                    mix.get(JAVA_INT, MIX_WORK_OFFSET + RECT_RIGHT_OFFSET),
                    mix.get(JAVA_INT, MIX_WORK_OFFSET + RECT_BOTTOM_OFFSET),
                    primary, colorDepth, dpiX, dpiY, uiresx, uiresy);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static int deviceCaps(MemorySegment hDC, int index) {
        try {
            return (int) Gdi32.GET_DEVICE_CAPS.invokeExact(hDC, index);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code GetSystemDirectoryW} into a {@code MAX_PATH}-character buffer: the directory, or {@code null}
     * when the call failed (0) or the buffer was too small (the required size, {@code >= MAX_PATH}).
     * The path is kept as code units ({@link WinCallSupport#codeUnitsToNul}): {@code LoadDPIFuncs} appended to that
     * {@code wchar_t} buffer with {@code wcscat_s} and never decoded it.
     */
    private static String systemDirectory() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = arena.allocate(JAVA_CHAR, MAX_PATH);
            int length = (int) GET_SYSTEM_DIRECTORY_W.invokeExact(path, MAX_PATH);
            if (length == 0 || length >= MAX_PATH) {
                return null;
            }
            return codeUnitsToNul(path);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code void (*settings_changed)(void)}. Package-private: the shim builds its recording stub from it. */
    static final FunctionDescriptor SCREEN_SETTINGS_CHANGED_FD = FunctionDescriptor.ofVoid();

    private static boolean screenCallbacksInstalled;

    /** The stub {@link #installScreenCallbacks} created, kept for {@link #rewriteScreenCallbacks}; never replaced. */
    private static MemorySegment screenCallbackStub;

    /** {@code sizeof(GwinScreenCallbacks)} as the C compiler saw it; {@link #SCREEN_CALLBACKS_LAYOUT} must agree. */
    static int sizeOfScreenCallbacks() {
        try {
            return (int) GWIN_SIZEOF_SCREEN_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Hands {@code glass.dll} the table that replaces the {@code Screen.notifySettingsChanged()} upcall of
     * {@code GlassScreen::HandleDisplayChange} - {@code WM_DISPLAYCHANGE}, {@code WM_SETTINGCHANGE} with
     * {@code SPI_SETWORKAREA} and {@code WM_DPICHANGED}. Once a table is installed that function dials the
     * slot; while none is, it delivers nothing - the JNI path it took before the install has been
     * deleted.
     * <p>
     * <b>The install is a flip, and travelled with the flip of the enumeration.</b> It comes from
     * {@code WinApplication}'s static initializer, beside the other tables - the launcher thread, before
     * {@code Application.run()} creates the toolkit window, so no display change can arrive before it - and
     * landed in the change set that made {@code staticScreen_getScreens} Java. The JNI path it replaced
     * looked {@code Screen} up through the class loader {@code _setClassLoader} handed the C, which that same
     * change set deleted, so from then until that path was deleted a display change that found no table would have
     * reached Java through a NULL class; and installed lazily, one that arrives before the install is now
     * dropped.
     * <p>
     * The table is copied by the library; the stub is in {@link Arena#global()}, because no
     * {@code remove} exists and a {@code WM_DISPLAYCHANGE} can be dispatched until the last
     * {@code GetMessage}. Idempotent.
     */
    static synchronized void installScreenCallbacks() {
        if (screenCallbacksInstalled) {
            return;
        }
        screenCallbackStub = upcallStub(
                upcallTarget(WinScreenCalls.class, "onScreenSettingsChanged", SCREEN_SETTINGS_CHANGED_FD),
                SCREEN_SETTINGS_CHANGED_FD, Arena.global());
        writeScreenTable(screenCallbackStub);
        screenCallbacksInstalled = true;
    }

    static synchronized boolean screenCallbacksInstalled() {
        return screenCallbacksInstalled;
    }

    /**
     * Puts back what {@link #installScreenCallbacks} left in the library, after a test has installed a
     * table of its own: the production table if the install has run, no table if it has not.
     * {@code gwin_screen_set_callbacks} copies by value and the last call wins. Never installs and never
     * creates a second stub.
     */
    static synchronized void rewriteScreenCallbacks() {
        if (screenCallbacksInstalled) {
            writeScreenTable(screenCallbackStub);
        } else {
            try {
                requireOk((int) GWIN_SCREEN_SET_CALLBACKS.invokeExact(MemorySegment.NULL), "gwin_screen_set_callbacks");
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    private static void writeScreenTable(MemorySegment settingsChanged) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(SCREEN_CALLBACKS_LAYOUT);
            table.set(ADDRESS, SETTINGS_CHANGED_OFFSET, settingsChanged);
            requireOk((int) GWIN_SCREEN_SET_CALLBACKS.invokeExact(table), "gwin_screen_set_callbacks");
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The {@code GwinScreenCallbacks.settings_changed} body: what {@code HandleDisplayChange}'s JNI arm did
     * until it was deleted, {@code CallStaticVoidMethod(Screen.notifySettingsChanged)} and its
     * {@code CheckAndClearException}.
     * <p>
     * <b>Thread and re-entrancy.</b> The toolkit thread, inside {@code DispatchMessage} - and for
     * {@code WM_DPICHANGED} after {@code GlassWindow} has already moved the window. The body re-enumerates
     * the monitors from inside this upcall, replaces the {@code Screen} list and rebinds every window before
     * it returns, so nothing on the path may hold a lock that code below a {@code DispatchMessage} can want.
     * A pass that finds no monitor makes {@code Screen.initScreens} throw; that, like anything else, is
     * reported here and never thrown through the stub, which would end the JVM.
     */
    static void onScreenSettingsChanged() {
        try {
            Screen.notifySettingsChanged();
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }
}
