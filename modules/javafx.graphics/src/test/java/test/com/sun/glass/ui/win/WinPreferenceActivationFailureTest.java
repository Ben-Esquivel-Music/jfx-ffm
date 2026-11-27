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

package test.com.sun.glass.ui.win;

import com.sun.glass.ui.win.WinGlassNativeShim;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Glass toolkit whose WinRT activation failed, as the C++ of {@code PlatformSupport.cpp} and
 * {@code RoActivationSupport.cpp} at commit {@code 5a709c189d} ({@code GLASS_WIN_ABI_VERSION} 7) ran it. The future
 * toolkit thread enters the multithreaded apartment ({@code CoInitializeEx(NULL, COINIT_MULTITHREADED)}) before
 * {@code gwin_app_create}, so the {@code RoInitialize(RO_INIT_SINGLETHREADED)} of the preferences' creation
 * ({@code tryInitializeRoActivationSupport} at that commit) fails with {@code RPC_E_CHANGED_MODE};
 * {@code gwin_run_loop}'s {@code OleInitialize} fails the same way, and the toolkit runs anyway. Pinned:
 * <ul>
 * <li>the bytes {@code gwin_app_create} writes to the process's standard error:
 *     {@code RoInitialize failed: } and the system message of the {@code HRESULT} as {@code RoException} built it
 *     ({@code _com_error::ErrorMessage} in {@code MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT)} without its trailing
 *     line break, converted to the ANSI code page), and nothing after it - no line break. The message is asked from
 *     the same machine ({@link WinGlassNativeShim#systemMessageAnsi}), so the comparison holds in any display
 *     language; on the English Windows 10 19045 this was written on it is
 *     {@code RoInitialize failed: Cannot change thread mode after it is set.} A Debug build of {@code glass.dll}
 *     traces one more line there ({@link WinAppCreateStandardError});</li>
 * <li>no WinRT object and no delegate: both queries answer a zero-filled struct and {@code GWIN_OK} on the toolkit
 *     thread, and {@code gwin_test_prefs_invoke} answers {@code GWIN_ERR_INVALID_ARG} for both delegates;</li>
 * <li>teardown logs no {@code gwin_test_event_log} event, so no {@code RoUninitialize};</li>
 * <li>the message arms still call {@code preferences_changed} exactly as in
 *     {@link WinPreferenceDispatchTraceTest}: the table does not depend on WinRT;</li>
 * <li>the toolkit thread stays in the multithreaded apartment it was put in until it leaves it itself.</li>
 * </ul>
 */
@EnabledOnOs(OS.WINDOWS)
public class WinPreferenceActivationFailureTest {

    private static final long WAIT_MILLIS = 10_000;

    /** {@code COINIT_MULTITHREADED} of {@code objbase.h}. */
    private static final int COINIT_MULTITHREADED = 0x0;

    /** {@code RPC_E_CHANGED_MODE} of {@code winerror.h}. */
    private static final int RPC_E_CHANGED_MODE = 0x8001_0106;

    /** {@code CoGetApartmentType} on a thread of the multithreaded apartment it entered itself. */
    private static final String MTA = "hr=0x0 type=1 qualifier=0";

    private static WinGlassChildJvm.Run failed;

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    /** The child of {@link #activateInTheMultithreadedApartment}, launched by the first test that needs it. */
    private static synchronized WinGlassChildJvm.Run failed() {
        if (failed == null) {
            failed = WinGlassChildJvm.run(WinPreferenceActivationFailureTest.class,
                    "activateInTheMultithreadedApartment");
            System.out.println("activation failure: " + failed.values() + " (" + failed.loadedGlass() + ")");
        }
        return failed;
    }

    @Test
    @Timeout(120)
    public void roInitializeFailingWritesItsSystemMessageToStandardErrorWithNoLineBreak() {
        WinGlassChildJvm.Run run = failed();
        assertEquals("0x0", run.value("coInitializeEx"), run.describe());
        byte[] message = WinAppCreateStandardError.concat("RoInitialize failed: ".getBytes(StandardCharsets.US_ASCII),
                WinGlassNativeShim.systemMessageAnsi(RPC_E_CHANGED_MODE));
        WinAppCreateStandardError.assertWrittenByAppCreate(run, message,
                "what gwin_app_create wrote to standard error");
    }

    @Test
    @Timeout(120)
    public void withoutWinRtBothQueriesAnswerAZeroFilledStructAndNoDelegateIsKept() {
        WinGlassChildJvm.Run run = failed();
        assertEquals("status=0 bytes=" + "00".repeat(60), run.value("query.live.ui"),
                "gwin_prefs_query_ui_settings on the toolkit thread, into 0xA5 bytes: " + run.describe());
        assertEquals("status=0 bytes=" + "00".repeat(8), run.value("query.live.network"),
                "gwin_prefs_query_network on the toolkit thread, into 0xA5 bytes: " + run.describe());
        String invalid = Integer.toString(WinPreferenceDispatchTraceTest.INVALID_ARG);
        assertEquals(invalid, run.value("hook.invoke.autoHide"), "no AutoHideScrollBarsChanged delegate exists: "
                + run.describe());
        assertEquals(invalid, run.value("hook.invoke.network"), "no NetworkStatusChanged delegate exists: "
                + run.describe());
    }

    @Test
    @Timeout(120)
    public void withoutWinRtTeardownLogsNoEventAndSoCallsNoRoUninitialize() {
        WinGlassChildJvm.Run run = failed();
        assertEquals("true", run.value("loop.returned"), run.describe());
        assertEquals("", run.value("events"), "gwin_test_event_log after teardown: " + run.describe());
    }

    @Test
    @Timeout(120)
    public void withoutWinRtEveryPreferenceMessageStillCallsPreferencesChangedAsBefore() {
        WinGlassChildJvm.Run run = failed();
        for (int answer : WinPreferenceDispatchTraceTest.ANSWERS) {
            WinPreferenceDispatchTraceTest.assertTrace(run, "trace.answer" + answer,
                    "the table answering " + answer + " in a toolkit without WinRT");
        }
    }

    @Test
    @Timeout(120)
    public void theToolkitThreadStaysInTheMultithreadedApartmentItEntered() {
        WinGlassChildJvm.Run run = failed();
        assertEquals(MTA, run.value("apartment.beforeCreate"), run.describe());
        assertEquals(MTA, run.value("apartment.afterCreate"), run.describe());
        assertEquals(MTA, run.value("apartment.inLoop"), run.describe());
        assertEquals(MTA, run.value("apartment.afterLoop"), run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void activateInTheMultithreadedApartment(Map<String, String> out) {
        WinGlassNativeShim.installAnsweringPreferencesTable(WinPreferenceDispatchTraceTest.USER);
        WinGlassNativeShim.prefsCapture(true);
        WinGlassNativeShim.observe(true);
        WinSteppedToolkit toolkit = WinSteppedToolkit.start(
                () -> {
                    out.put("coInitializeEx", hex(WinGlassNativeShim.coInitializeEx(COINIT_MULTITHREADED)));
                    out.put("apartment.beforeCreate", WinPreferenceDispatchTraceTest.apartment());
                    WinAppCreateStandardError.mark(WinAppCreateStandardError.BEFORE_CREATE);
                },
                () -> {
                    WinAppCreateStandardError.mark(WinAppCreateStandardError.AFTER_CREATE);
                    out.put("apartment.afterCreate", WinPreferenceDispatchTraceTest.apartment());
                },
                () -> out.put("apartment.inLoop", WinPreferenceDispatchTraceTest.apartment()),
                () -> {
                    out.put("apartment.afterLoop", WinPreferenceDispatchTraceTest.apartment());
                    WinGlassNativeShim.coUninitialize();
                });
        out.put("trace.invoke", Integer.toString(toolkit.invokeAndWait(() -> {
            WinPreferenceDispatchTraceTest.sweeps(out, toolkit.hwnd(), toolkit.thread(), toolkit.nativeThreadId());
            out.put("query.live.ui",
                    WinPreferenceDispatchTraceTest.raw(WinGlassNativeShim.queryUiSettingsRaw((byte) 0xA5)));
            out.put("query.live.network",
                    WinPreferenceDispatchTraceTest.raw(WinGlassNativeShim.queryNetworkRaw((byte) 0xA5)));
            out.put("hook.invoke.autoHide",
                    Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_AUTO_HIDE)));
            out.put("hook.invoke.network",
                    Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_NETWORK)));
        })));
        out.put("terminate.post", Integer.toString(toolkit.invokeLater(WinGlassNativeShim::toolkitTerminateLoop)));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("events", WinGlassNativeShim.eventLog().stream().map(String::valueOf)
                .collect(Collectors.joining(",")));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value);
    }
}
