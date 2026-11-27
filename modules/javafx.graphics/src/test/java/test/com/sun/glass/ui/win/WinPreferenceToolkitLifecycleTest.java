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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The WinRT activation of the Glass toolkit's preferences around its own life, as the C++ of
 * {@code RoActivationSupport.cpp} and {@code PlatformSupport.cpp} at commit {@code 5a709c189d}
 * ({@code GLASS_WIN_ABI_VERSION} 7) ran it:
 * <ul>
 * <li><b>A toolkit thread that is already in a single-threaded apartment</b> before {@code gwin_app_create}, as an
 *     embedder's UI thread is. {@code RoInitialize(RO_INIT_SINGLETHREADED)} then answers {@code S_FALSE}, which the C
 *     counted as success ({@code FAILED(hr)} was its only failure test): nothing is written to standard error, both
 *     sinks are registered and deliver, the queries carry the values of activated objects, teardown logs all six
 *     events, and the thread stays in its apartment, which its own {@code CoInitializeEx} holds, after the toolkit's
 *     {@code RoUninitialize} and {@code OleUninitialize}.</li>
 * <li><b>A second toolkit in one process</b>, created after the first one was torn down: the C kept one process-wide
 *     "initialised" flag, which the teardown reset, so the second toolkit initialises WinRT again on its own thread -
 *     its thread is a single-threaded apartment after {@code gwin_app_create}, its own two sinks deliver, its queries
 *     carry the values of activated objects, and its teardown logs all six events again. Both a second toolkit on a
 *     new thread and one on the thread of the first, after that thread's {@code gwin_run_loop} has returned, work.
 *     The delegates {@code gwin_test_prefs_capture} kept from the first toolkit are dropped before the second is
 *     created, so a sink that delivers is one the second toolkit registered.</li>
 * </ul>
 * "Carry the values of activated objects" is what
 * {@link WinPreferenceDispatchTraceTest#aLiveQueryCarriesTheValuesOfTheActivatedObjects} states: the flag words and
 * the ranges that hold on every machine. The colours and the network cost are not compared between two reads, which
 * a theme or network change in between would set apart with nothing wrong.
 * <p>
 * Each scenario runs in a {@link WinGlassChild} JVM; the recording table of {@link WinPreferenceDispatchTraceTest} is
 * installed before the first toolkit is created.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinPreferenceToolkitLifecycleTest {

    private static final long WAIT_MILLIS = 10_000;

    private static final int COINIT_APARTMENTTHREADED = 0x2;

    /** The first single-threaded apartment of the process, as {@code CoGetApartmentType} reports it. */
    private static final String MAIN_STA = WinPreferenceDispatchTraceTest.MAIN_STA;

    /** The six {@code gwin_test_event_log} codes of one toolkit's teardown, in their order. */
    private static final String TEARDOWN = "1,2,3,4,5,6";

    private static WinGlassChildJvm.Run alreadyInAnSta;
    private static WinGlassChildJvm.Run secondOnANewThread;
    private static WinGlassChildJvm.Run secondOnTheSameThread;

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    private static synchronized WinGlassChildJvm.Run alreadyInAnSta() {
        if (alreadyInAnSta == null) {
            alreadyInAnSta = launch("activateOnAThreadAlreadyInASingleThreadedApartment");
        }
        return alreadyInAnSta;
    }

    private static synchronized WinGlassChildJvm.Run secondOnANewThread() {
        if (secondOnANewThread == null) {
            secondOnANewThread = launch("createASecondToolkitOnANewThread");
        }
        return secondOnANewThread;
    }

    private static synchronized WinGlassChildJvm.Run secondOnTheSameThread() {
        if (secondOnTheSameThread == null) {
            secondOnTheSameThread = launch("createASecondToolkitOnTheSameThread");
        }
        return secondOnTheSameThread;
    }

    private static WinGlassChildJvm.Run launch(String scenario) {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceToolkitLifecycleTest.class, scenario);
        System.out.println(scenario + ": " + run.values() + " (" + run.loadedGlass() + ")");
        return run;
    }

    /*
     * A toolkit thread already in a single-threaded apartment.
     */

    @Test
    @Timeout(120)
    public void alreadyInAnApartmentRoInitializeSucceedsWithNothingWrittenToStandardError() {
        WinGlassChildJvm.Run run = alreadyInAnSta();
        assertEquals("0x0", run.value("coInitializeEx"), "CoInitializeEx(NULL, COINIT_APARTMENTTHREADED) on the future"
                + " toolkit thread: " + run.describe());
        WinAppCreateStandardError.assertWrittenByAppCreate(run, new byte[0],
                "what gwin_app_create wrote to standard error on a thread already in its apartment");
    }

    @Test
    @Timeout(120)
    public void alreadyInAnApartmentBothSinksAreRegisteredAndDeliverOnTheToolkitThread() {
        WinGlassChildJvm.Run run = alreadyInAnSta();
        WinPreferenceDispatchTraceTest.assertSinkDelivered(run, "sink.autoHide",
                WinPreferenceDispatchTraceTest.UI_SETTINGS);
        WinPreferenceDispatchTraceTest.assertSinkDelivered(run, "sink.network",
                WinPreferenceDispatchTraceTest.NETWORK_INFORMATION);
        assertEquals(TEARDOWN, run.value("events"), "both registrations are removed and every object released before"
                + " RoUninitialize: " + run.describe());
    }

    @Test
    @Timeout(120)
    public void alreadyInAnApartmentTheQueriesCarryTheValuesOfTheActivatedObjects() {
        assertLiveQueries(alreadyInAnSta(), "query.live");
    }

    @Test
    @Timeout(120)
    public void alreadyInAnApartmentTheThreadStaysInItAfterTheToolkitsRoUninitializeAndOleUninitialize() {
        WinGlassChildJvm.Run run = alreadyInAnSta();
        for (String point : List.of("beforeCreate", "afterCreate", "inLoop", "afterDestroy", "afterLoop")) {
            assertEquals(MAIN_STA, run.value("apartment." + point), "the toolkit thread's apartment, " + point + ": "
                    + run.describe());
        }
    }

    /*
     * A second toolkit in one process.
     */

    @Test
    @Timeout(120)
    public void aSecondToolkitOnANewThreadInitialisesWinRtAgain() {
        assertBothToolkitsActivated(secondOnANewThread());
    }

    @Test
    @Timeout(120)
    public void aSecondToolkitOnTheSameThreadInitialisesWinRtAgain() {
        WinGlassChildJvm.Run run = secondOnTheSameThread();
        assertEquals("true", run.value("second.created"), "no second toolkit window on the first one's thread: "
                + run.describe());
        assertBothToolkitsActivated(run);
    }

    /**
     * Each of the two toolkits of {@code run} is a single-threaded apartment after {@code gwin_app_create}, has its own
     * two sinks delivering, queries carrying the values of activated objects, and logs the six teardown events. Every
     * check runs and each one that fails is reported.
     */
    private static void assertBothToolkitsActivated(WinGlassChildJvm.Run run) {
        List<Executable> checks = new ArrayList<>();
        for (String toolkit : List.of("first.", "second.")) {
            checks.add(() -> assertEquals(MAIN_STA, run.value(toolkit + "apartment.afterCreate"), "the " + toolkit
                    + " toolkit's thread after gwin_app_create, which runs RoInitialize(RO_INIT_SINGLETHREADED)"));
            checks.add(() -> WinPreferenceDispatchTraceTest.assertSinkDelivered(run, toolkit + "sink.autoHide",
                    WinPreferenceDispatchTraceTest.UI_SETTINGS));
            checks.add(() -> WinPreferenceDispatchTraceTest.assertSinkDelivered(run, toolkit + "sink.network",
                    WinPreferenceDispatchTraceTest.NETWORK_INFORMATION));
            checks.add(() -> assertLiveQueries(run, toolkit + "query.live"));
            checks.add(() -> assertEquals(TEARDOWN, run.value(toolkit + "events"), "the " + toolkit
                    + " toolkit's teardown events"));
        }
        assertAll("the two toolkits of " + run.describe(), checks.stream());
    }

    private static void assertLiveQueries(WinGlassChildJvm.Run run, String prefix) {
        assertEquals("status=0,0,0 sameBytes=true", run.value(prefix + ".ui"),
                "gwin_prefs_query_ui_settings on the toolkit thread, into 0xA5, 0x00 and 0x5A bytes: "
                + run.describe());
        assertEquals("status=0,0,0 sameBytes=true", run.value(prefix + ".network"),
                "gwin_prefs_query_network on the toolkit thread, into 0xA5, 0x00 and 0x5A bytes: " + run.describe());
        WinPreferenceDispatchTraceTest.assertLiveQueriesCarryValues(run, prefix);
    }

    /*
     * The child side.
     */

    /** Runs in {@link WinGlassChild}. */
    static void activateOnAThreadAlreadyInASingleThreadedApartment(Map<String, String> out) {
        prepare();
        WinSteppedToolkit toolkit = WinSteppedToolkit.start(
                () -> {
                    out.put("coInitializeEx", "0x" + Integer.toHexString(
                            WinGlassNativeShim.coInitializeEx(COINIT_APARTMENTTHREADED)));
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
        recordLife(out, "", toolkit);
        terminate(out, "", toolkit);
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("events", events());
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /**
     * Runs in {@link WinGlassChild}: one toolkit, torn down, then a second one on a thread of its own, created once
     * the delegates kept from the first are dropped.
     */
    static void createASecondToolkitOnANewThread(Map<String, String> out) {
        prepare();
        for (String prefix : List.of("first.", "second.")) {
            dropKeptDelegates();
            WinSteppedToolkit toolkit = WinSteppedToolkit.start(
                    () -> out.put(prefix + "apartment.beforeCreate", WinPreferenceDispatchTraceTest.apartment()),
                    () -> out.put(prefix + "apartment.afterCreate", WinPreferenceDispatchTraceTest.apartment()),
                    () -> out.put(prefix + "apartment.inLoop", WinPreferenceDispatchTraceTest.apartment()),
                    () -> out.put(prefix + "apartment.afterLoop", WinPreferenceDispatchTraceTest.apartment()));
            out.put(prefix + "thread", Integer.toString(toolkit.nativeThreadId()));
            recordLife(out, prefix, toolkit);
            terminate(out, prefix, toolkit);
            out.put(prefix + "loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
            out.put(prefix + "events", events());
            WinGlassNativeShim.observe(true);
            out.put(prefix + "ended", Boolean.toString(toolkit.releaseAndAwaitEnd(WAIT_MILLIS)));
        }
    }

    /**
     * Runs in {@link WinGlassChild}: one toolkit, torn down, then a second one on the same thread, created and run by
     * the step after the first one's {@code gwin_run_loop} has returned, once the delegates kept from the first are
     * dropped.
     */
    static void createASecondToolkitOnTheSameThread(Map<String, String> out) {
        prepare();
        CountDownLatch secondCreated = new CountDownLatch(1);
        AtomicLong secondHwnd = new AtomicLong();
        WinSteppedToolkit toolkit = WinSteppedToolkit.start(
                () -> out.put("first.apartment.beforeCreate", WinPreferenceDispatchTraceTest.apartment()),
                () -> out.put("first.apartment.afterCreate", WinPreferenceDispatchTraceTest.apartment()),
                () -> out.put("first.apartment.inLoop", WinPreferenceDispatchTraceTest.apartment()),
                () -> {
                    out.put("first.apartment.afterLoop", WinPreferenceDispatchTraceTest.apartment());
                    try {
                        dropKeptDelegates();
                        secondHwnd.set(WinGlassNativeShim.toolkitCreate());
                        out.put("second.apartment.afterCreate", WinPreferenceDispatchTraceTest.apartment());
                    } finally {
                        secondCreated.countDown();
                    }
                    if (secondHwnd.get() != 0) {
                        WinGlassNativeShim.toolkitRunLoop(() ->
                                out.put("second.apartment.inLoop", WinPreferenceDispatchTraceTest.apartment()));
                    }
                    out.put("second.apartment.afterLoop", WinPreferenceDispatchTraceTest.apartment());
                });
        recordLife(out, "first.", toolkit);
        terminate(out, "first.", toolkit);
        out.put("second.created", Boolean.toString(WinNativeToolkit.await(secondCreated, WAIT_MILLIS)
                && secondHwnd.get() != 0));
        out.put("first.events", events());
        WinGlassNativeShim.observe(true);
        if (secondHwnd.get() != 0) {
            recordLife(out, "second.", toolkit);
            terminate(out, "second.", toolkit);
        }
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("second.events", events());
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /** The recording table, the delegates kept for {@code gwin_test_prefs_invoke}, and the test probes on. */
    private static void prepare() {
        WinGlassNativeShim.installAnsweringPreferencesTable(WinPreferenceDispatchTraceTest.USER);
        WinGlassNativeShim.prefsCapture(true);
        WinGlassNativeShim.observe(true);
    }

    /**
     * Drops the delegates {@code gwin_test_prefs_capture} kept from an earlier toolkit and keeps capturing, so that
     * {@code gwin_test_prefs_invoke} reaches only what the next toolkit registers, or answers that nothing is kept.
     */
    private static void dropKeptDelegates() {
        WinGlassNativeShim.prefsCapture(false);
        WinGlassNativeShim.prefsCapture(true);
    }

    /**
     * The published toolkit's live queries, on its thread, then both kept delegates invoked from another thread and
     * their deliveries, under {@code prefix}. {@code toolkit} names the thread the toolkit runs on.
     */
    private static void recordLife(Map<String, String> out, String prefix, WinSteppedToolkit toolkit) {
        out.put(prefix + "query.invoke", Integer.toString(toolkit.invokeAndWait(() ->
                WinPreferenceDispatchTraceTest.recordLiveQueries(out, prefix + "query.live"))));
        WinPreferenceDispatchTraceTest.traceSinkOnAnotherThread(out, prefix + "sink.autoHide",
                WinGlassNativeShim.DELEGATE_AUTO_HIDE, WinPreferenceDispatchTraceTest.UI_SETTINGS, toolkit.thread(),
                toolkit.nativeThreadId());
        WinPreferenceDispatchTraceTest.traceSinkOnAnotherThread(out, prefix + "sink.network",
                WinGlassNativeShim.DELEGATE_NETWORK, WinPreferenceDispatchTraceTest.NETWORK_INFORMATION,
                toolkit.thread(), toolkit.nativeThreadId());
    }

    /** Destroys the published toolkit window from a runnable on its thread, and records the apartment after it. */
    private static void terminate(Map<String, String> out, String prefix, WinSteppedToolkit toolkit) {
        out.put(prefix + "terminate.post", Integer.toString(toolkit.invokeLater(() -> {
            WinGlassNativeShim.toolkitTerminateLoop();
            out.put(prefix + "apartment.afterDestroy", WinPreferenceDispatchTraceTest.apartment());
        })));
    }

    private static String events() {
        return WinGlassNativeShim.eventLog().stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
