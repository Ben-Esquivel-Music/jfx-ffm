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
import com.sun.glass.ui.win.WinGlassNativeShim.DelegateCall;
import com.sun.glass.ui.win.WinGlassNativeShim.PreferencesCall;
import com.sun.javafx.application.PlatformImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two WinRT preference sinks of {@code PlatformSupport} - the {@code AutoHideScrollBarsChanged} and
 * {@code NetworkStatusChanged} delegates - invoked the way an event source invokes them: through a reference taken
 * at registration ({@code gwin_test_prefs_capture}), on a thread of the caller's choosing
 * ({@code gwin_test_prefs_invoke}), before, during and after teardown.
 * <ul>
 * <li>A second thread invokes them in a loop across teardown, in repeated child JVMs, with no access violation.</li>
 * <li>Every {@code preferences_changed} upcall - recorded by a table installed in place of the production one -
 * runs on the toolkit thread, and none after {@code gwin_run_loop} has returned.</li>
 * <li>A delegate invoked after the toolkit window is gone posts nothing: the sink-post counters say it was
 * refused, and {@code PostMessage} was not called.</li>
 * <li>A delegate invoked on the toolkit thread, whose preference listener calls {@code Platform.exit()}, lets the
 * JVM exit. On the way out the toolkit thread clears the production table and closes
 * {@code WinApplication}'s application arena, which held its stub.</li>
 * <li>{@code PlatformSupport} releases its WinRT objects and the activation factory before
 * {@code RoUninitialize} ({@code gwin_test_event_log}).</li>
 * </ul>
 * Each runs in a {@link WinGlassChild} JVM, because the delegate of a build that still calls into a destroyed
 * {@code PlatformSupport} can take that process down.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinPreferenceSinksNativeTest {

    private static final long WAIT_MILLIS = 10_000;
    private static final int DEFAULT_RUNS = 3;
    private static final int SECONDS_PER_RUN = 30;
    private static final int INVOCATIONS_AFTER_TEARDOWN = 10;

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    /*
     * Invocations across teardown.
     */

    /** {@code -Dwin.glass.sinks.runs=N} sets the number of runs (default {@value #DEFAULT_RUNS}). */
    @Test
    @Timeout(3600)
    public void invokingTheSinksAcrossTeardownNeverCrashes() {
        int runs = Integer.getInteger("win.glass.sinks.runs", DEFAULT_RUNS);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinPreferenceSinksNativeTest.class,
                    "invokeAcrossTeardown", "invokeAcrossTeardown-" + i, List.of(), SECONDS_PER_RUN);
            if (!run.clean() || !"true".equals(run.values().get("loop.returned"))
                    || "0".equals(run.values().getOrDefault("sink.invocationsAfterTeardown", "0"))) {
                failures.add(run.describe());
            }
        }
        System.out.println("sinks across teardown: " + runs + " runs, " + failures.size() + " failed (glass.dll"
                + " sha256 " + WinGlassChildJvm.glassSha256() + ")");
        assertEquals(List.of(), failures, failures.size() + " of " + runs + " runs failed");
    }

    @Test
    @Timeout(120)
    public void preferencesChangedRunsOnlyOnTheToolkitThreadAndNeverAfterTheLoopReturned() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceSinksNativeTest.class, "invokeAcrossTeardown");
        System.out.println("sink upcalls: " + run.values());
        assertEquals("0", run.value("sink.firstStatus.autoHide"), "no AutoHideScrollBarsChanged delegate was kept: "
                + run.describe());
        assertEquals("0", run.value("sink.firstStatus.network"), "no NetworkStatusChanged delegate was kept: "
                + run.describe());
        assertNotEquals("0", run.value("prefs.calls"), "the sinks caused no preferences_changed at all: "
                + run.describe());
        assertEquals("0", run.value("prefs.offToolkitThread"),
                "preferences_changed ran on a thread other than the toolkit thread: " + run.describe());
        assertEquals("0", run.value("prefs.afterLoopReturned"),
                "preferences_changed ran after gwin_run_loop had returned: " + run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void invokeAcrossTeardown(Map<String, String> out) {
        WinGlassNativeShim.installRecordingPreferencesTable();
        WinGlassNativeShim.prefsCapture(true);
        WinGlassNativeShim.observe(true);
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        out.put("sink.firstStatus.autoHide",
                Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_AUTO_HIDE)));
        out.put("sink.firstStatus.network",
                Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_NETWORK)));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean tornDown = new AtomicBoolean();
        AtomicLong invocations = new AtomicLong();
        AtomicLong afterTeardown = new AtomicLong();
        Thread sink = WinNativeToolkit.startThread("sink", () -> {
            for (int i = 0; !stop.get(); i++) {
                WinGlassNativeShim.prefsInvoke(i % 2);
                invocations.incrementAndGet();
                if (tornDown.get()) {
                    afterTeardown.incrementAndGet();
                }
            }
        });
        sleep(ThreadLocalRandom.current().nextLong(20, 200));
        // A sent runnable: the sink's posts can fill the toolkit thread's queue to its quota, and a posted one
        // would then be refused.
        out.put("terminate.send", Integer.toString(toolkit.terminateBySend()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        tornDown.set(true);
        sleep(ThreadLocalRandom.current().nextLong(20, 200));
        stop.set(true);
        out.put("sink.ended", Boolean.toString(WinNativeToolkit.join(sink, WAIT_MILLIS)));
        out.put("sink.invocations", Long.toString(invocations.get()));
        out.put("sink.invocationsAfterTeardown", Long.toString(afterTeardown.get()));

        List<PreferencesCall> calls = WinGlassNativeShim.recordedPreferencesCalls();
        long loopReturned = toolkit.loopReturnSequence();
        out.put("prefs.calls", Integer.toString(calls.size()));
        out.put("prefs.offToolkitThread",
                Long.toString(calls.stream().filter(c -> c.thread() != toolkit.thread()).count()));
        out.put("prefs.afterLoopReturned",
                Long.toString(calls.stream().filter(c -> c.sequence() > loopReturned).count()));
        out.put("prefs.threads", calls.stream().map(c -> c.thread().getName()).distinct()
                .collect(Collectors.joining(",")));
        out.put("delegate.calls", Integer.toString(WinGlassNativeShim.prefsDelegateCalls().total()));
        out.put("counter.prefsPost", Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_POST)));
        out.put("counter.prefsRefused",
                Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_REFUSED)));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /*
     * After the toolkit window is gone.
     */

    @Test
    @Timeout(120)
    public void aDelegateInvokedAfterTheToolkitWindowIsGonePostsNothing() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceSinksNativeTest.class, "invokeAfterTeardown");
        String invocations = Integer.toString(INVOCATIONS_AFTER_TEARDOWN);
        assertEquals("true", run.value("loop.returned"), run.describe());
        assertEquals("0x" + invocations, run.value("after.statuses"), "the delegates' HRESULTs: " + run.describe());
        assertEquals(invocations, run.value("after.delegateCalls"), "the delegate bodies did not run: "
                + run.describe());
        assertEquals("0", run.value("after.post"), "PostMessage was called: " + run.describe());
        assertEquals("0", run.value("after.prefsPost"), run.describe());
        assertEquals(invocations, run.value("after.prefsRefused"),
                "a sink that ran after the window was gone was not refused: " + run.describe());
        assertEquals("0", run.value("after.prefsCalls"), "preferences_changed ran after teardown: " + run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void invokeAfterTeardown(Map<String, String> out) {
        WinGlassNativeShim.installRecordingPreferencesTable();
        WinGlassNativeShim.prefsCapture(true);
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        out.put("terminate.post", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        int callsBefore = WinGlassNativeShim.recordedPreferencesCalls().size();
        WinGlassNativeShim.observe(true);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < INVOCATIONS_AFTER_TEARDOWN; i++) {
            statuses.add(WinGlassNativeShim.prefsInvoke(i % 2));
        }
        WinGlassNativeShim.observe(false);
        out.put("after.statuses", WinToolkitWindowGuardNativeTest.histogram(statuses));
        out.put("after.delegateCalls", Integer.toString(WinGlassNativeShim.prefsDelegateCalls().total()));
        out.put("after.post", Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_POST)));
        out.put("after.prefsPost", Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_POST)));
        out.put("after.prefsRefused",
                Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_REFUSED)));
        out.put("after.prefsCalls",
                Integer.toString(WinGlassNativeShim.recordedPreferencesCalls().size() - callsBefore));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /*
     * A listener that ends the toolkit.
     */

    @Test
    @Timeout(180)
    public void aDelegateOnTheToolkitThreadWhoseListenerCallsPlatformExitLetsTheJvmExit() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinPreferenceSinksNativeTest.class, "listenerExits",
                "listenerExits", List.of("-D" + WinGlassChild.NATURAL_EXIT_PROPERTY + "=true"), 60);
        System.out.println("listener exit: " + run.values());
        assertTrue(run.clean(), run.describe());
        assertEquals("0", run.value("invoke.status"), "no AutoHideScrollBarsChanged delegate was kept: "
                + run.describe());
        assertEquals("true", run.value("listener.fired"), "the preference listener never ran: " + run.describe());
        assertEquals("true", run.value("listener.onFxThread"), run.describe());
        assertEquals("true", run.value("fx.ended"), "the FX thread did not end after Platform.exit(): "
                + run.describe());
        assertEquals("false", run.value("arena.alive"), "the toolkit thread did not close the application arena "
                + "after its pump returned: " + run.describe());
    }

    /**
     * Runs in {@link WinGlassChild}, which then returns from {@code main}: the JVM exits once the FX thread has
     * ended. One {@code Windows.UISettings.*} boolean is first made stale on the FX side and
     * {@code WinPreferences}' last collection is forgotten, so that the delegate's re-collect counts as a change,
     * restores that boolean and the listener fires.
     */
    static void listenerExits(Map<String, String> out) throws InterruptedException {
        WinGlassNativeShim.prefsCapture(true);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        if (!started.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the toolkit did not start within 60 s");
        }
        AtomicReference<Thread> fx = new AtomicReference<>();
        CountDownLatch armed = new CountDownLatch(1);
        CountDownLatch fired = new CountDownLatch(1);
        Platform.runLater(() -> {
            fx.set(Thread.currentThread());
            String key = Platform.getPreferences().entrySet().stream()
                    .filter(e -> e.getKey().startsWith("Windows.UISettings.") && e.getValue() instanceof Boolean)
                    .map(Map.Entry::getKey).sorted().findFirst().orElse(null);
            out.put("stale.key", String.valueOf(key));
            if (key != null) {
                boolean value = (Boolean) Platform.getPreferences().get(key);
                PlatformImpl.updatePreferences(Map.of(key, !value));
                Platform.getPreferences().addListener((InvalidationListener) observable -> {
                    out.put("listener.onFxThread", Boolean.toString(Platform.isFxApplicationThread()));
                    fired.countDown();
                    Platform.exit();
                });
                // update runs only on this thread: with nothing collected, the next one counts as a change,
                // whatever a real settings event collected during startup.
                WinGlassNativeShim.forgetCollectedPreferences();
                out.put("invoke.status",
                        Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_AUTO_HIDE)));
            }
            armed.countDown();
        });
        out.put("armed", Boolean.toString(armed.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)));
        out.put("listener.fired",
                Boolean.toString(fired.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)));
        Thread fxThread = fx.get();
        out.put("fx.ended", Boolean.toString(fxThread != null && WinNativeToolkit.join(fxThread, 2 * WAIT_MILLIS)));
        out.put("arena.alive", Boolean.toString(WinGlassNativeShim.applicationArenaAlive()));
    }

    /*
     * Release before RoUninitialize.
     */

    @Test
    @Timeout(120)
    public void theWinRtObjectsAndTheFactoryAreReleasedBeforeRoUninitialize() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceSinksNativeTest.class, "teardownEvents");
        System.out.println("teardown events: " + run.value("events"));
        List<Integer> events = new ArrayList<>();
        for (String code : run.value("events").split(",")) {
            if (!code.isEmpty()) {
                events.add(Integer.parseInt(code));
            }
        }
        int roUninitialize = events.indexOf(WinGlassNativeShim.EVENT_RO_UNINITIALIZE);
        assertTrue(roUninitialize >= 0, "RoUninitialize was not called: " + run.describe());
        for (int released : List.of(WinGlassNativeShim.EVENT_RELEASE_UI_SETTINGS,
                WinGlassNativeShim.EVENT_RELEASE_NETWORK_STATICS, WinGlassNativeShim.EVENT_RELEASE_NETWORK_FACTORY)) {
            int at = events.indexOf(released);
            assertTrue(at >= 0 && at < roUninitialize, "release event " + released + " is not before RoUninitialize"
                    + " in " + events + ": " + run.describe());
        }
    }

    /** Runs in {@link WinGlassChild}. */
    static void teardownEvents(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        WinGlassNativeShim.observe(true);
        out.put("terminate.post", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("events", WinGlassNativeShim.eventLog().stream().map(String::valueOf)
                .collect(Collectors.joining(",")));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /*
     * The manual run, a record rather than a check: on which thread the OS invokes each delegate.
     */

    /**
     * Runs the toolkit for {@code -Dwin.glass.manualSinkSeconds=N} seconds and prints every delegate invocation the
     * OS made meanwhile: toggle "Automatically hide scroll bars" and drop the network during that time. Asserts
     * only that the child ran.
     */
    @Test
    @Timeout(3600)
    @EnabledIfSystemProperty(named = "win.glass.manualSinkSeconds", matches = "[0-9]+")
    public void manualSinkRun() {
        long seconds = Long.getLong("win.glass.manualSinkSeconds");
        WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinPreferenceSinksNativeTest.class, "osInvocations",
                "osInvocations", List.of("-Dwin.glass.manualSinkSeconds=" + seconds), seconds + 60);
        System.out.println("manual sink run: " + run.describe());
        assertTrue(run.clean(), run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void osInvocations(Map<String, String> out) {
        WinGlassNativeShim.observe(true);
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        out.put("toolkit.threadId", Integer.toString(toolkit.nativeThreadId()));
        sleep(1000 * Long.getLong("win.glass.manualSinkSeconds", 60));
        List<DelegateCall> calls = WinGlassNativeShim.prefsDelegateCalls().calls();
        out.put("delegate.calls", calls.stream().map(DelegateCall::toString).collect(Collectors.joining(" ")));
        out.put("terminate.post", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
