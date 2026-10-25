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

import com.sun.glass.ui.Application;
import com.sun.glass.ui.win.WinGlassNativeShim;
import com.sun.glass.ui.win.WinGlassNativeShim.DelegateCall;
import com.sun.glass.ui.win.WinGlassNativeShim.PostedMessage;
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
import java.util.function.BiConsumer;
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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
 * <li>A delegate whose post fails because the toolkit thread's posted-message queue is at its quota keeps its
 * type: once the toolkit thread runs again, {@code preferences_changed} receives it, on the toolkit thread, once -
 * whether the toolkit thread was blocked in a runnable of {@code gwin_invoke_later} or of a
 * {@code gwin_invoke_and_wait} from another thread. After teardown the same delegate is refused and keeps nothing.</li>
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
    private static final int SECONDS_PER_RUN = 60;
    private static final int INVOCATIONS_AFTER_TEARDOWN = 10;

    /** How long the toolkit thread stays blocked at most while its queue is filled. */
    private static final long BLOCK_MILLIS = 30_000;

    /**
     * How many posts filling the toolkit thread's queue are made at most: twenty times the default quota. A fill that
     * reaches it without a refusal aborts the test, because the quota of the machine, {@value #POST_MESSAGE_LIMIT},
     * is above it.
     */
    private static final int FILL_CAP = 200_000;

    /** The registry value that sets the quota of a thread's posted-message queue (10,000 when it is absent). */
    private static final String POST_MESSAGE_LIMIT =
            "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Windows\\USERPostMessageLimit";

    /** {@code WM_NULL} of {@code winuser.h}: a message the toolkit window's procedure passes to DefWindowProc. */
    private static final int WM_NULL = 0x0000;

    /** {@code ERROR_NOT_ENOUGH_QUOTA} of {@code winerror.h}: what PostMessage fails with on a full queue. */
    private static final int ERROR_NOT_ENOUGH_QUOTA = 1816;

    /** {@code GWIN_PT_NETWORK_INFORMATION} of {@code glass_win_api.h}: the type the network sink posts. */
    private static final int NETWORK_INFORMATION = 8;

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
        List<WinGlassChildJvm.Run> children = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinPreferenceSinksNativeTest.class,
                    "invokeAcrossTeardown", "invokeAcrossTeardown-" + i, List.of(), SECONDS_PER_RUN);
            children.add(run);
            if (!run.clean() || !"true".equals(run.values().get("loop.returned"))
                    || "0".equals(run.values().getOrDefault("sink.invocationsAfterTeardown", "0"))) {
                failures.add(run.describe());
            }
        }
        System.out.println("sinks across teardown: " + runs + " runs, " + failures.size() + " failed ("
                + WinGlassChildJvm.loadedGlass(children) + ")");
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
        // start() does not wait for gwin_run_loop, and the send below is handled ahead of the posted preference
        // messages, which gwin_terminate_loop's DestroyWindow discards: wait until one has been handled.
        out.put("prefs.awaited", Boolean.toString(
                WinGlassChild.waitFor(WAIT_MILLIS, () -> !WinGlassNativeShim.recordedPreferencesCalls().isEmpty())));
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
     * A post the full queue refuses.
     */

    @Test
    @Timeout(120)
    public void aNetworkChangeWhosePostAFullQueueRefusedIsDeliveredOnceTheToolkitThreadRunsAgain() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceSinksNativeTest.class, "invokeIntoFullQueue");
        System.out.println("sink into a full queue: " + run.values() + " (" + run.loadedGlass() + ")");
        assertKeptTypeDelivered(run);
    }

    @Test
    @Timeout(120)
    public void aNetworkChangeWhosePostAFullQueueRefusedDuringAnInvokeAndWaitIsDeliveredByTheRunLoop() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinPreferenceSinksNativeTest.class,
                "invokeIntoFullQueueDuringInvokeAndWait");
        System.out.println("sink into a full queue during an invoke-and-wait: " + run.values() + " ("
                + run.loadedGlass() + ")");
        assertKeptTypeDelivered(run);
        assertEquals("true", run.value("waiter.ended"),
                "the gwin_invoke_and_wait that blocked the toolkit thread did not return: " + run.describe());
        assertEquals("0", run.value("waiter.status"), run.describe());
    }

    /**
     * What a run of {@link #intoFullQueue} must show: the fill reached the quota; the network sink's post and its
     * wake were refused and its type was kept; {@code preferences_changed} received that type once, on the toolkit
     * thread, after the toolkit thread ran again; after teardown the same sink is refused and keeps nothing. A fill
     * that reached {@value #FILL_CAP} posts without a refusal aborts the test.
     */
    private static void assertKeptTypeDelivered(WinGlassChildJvm.Run run) {
        assertEquals("true", run.value("busy.started"), run.describe());
        assumeFalse("none".equals(run.value("fill.lastError")), "the fill posted " + run.value("fill.posted")
                + " messages to the toolkit thread's posted-message queue, its cap, and none was refused: the quota"
                + " of this machine (" + POST_MESSAGE_LIMIT + ") is above the cap, so the queue cannot be filled");
        assertEquals(Integer.toString(ERROR_NOT_ENOUGH_QUOTA), run.value("fill.lastError"),
                "the toolkit thread's posted-message queue was not filled to its quota: " + run.describe());
        assertEquals("0", run.value("sink.status"), "no NetworkStatusChanged delegate was kept: " + run.describe());
        assertEquals("false", run.value("probe.posted"), "the queue was no longer full after the sink: "
                + run.describe());
        assertEquals("0", run.value("blocked.networkCalls"),
                "preferences_changed ran while the toolkit thread was blocked: " + run.describe());
        assertEquals("true", run.value("delivered"), "the network change whose post the full queue refused never"
                + " reached preferences_changed after the toolkit thread ran again: " + run.describe());
        assertEquals("1", run.value("delivered.networkCalls"), run.describe());
        assertEquals(Integer.toString(NETWORK_INFORMATION), run.value("delivered.types"), run.describe());
        assertEquals("0", run.value("delivered.offToolkitThread"),
                "preferences_changed ran on a thread other than the toolkit thread: " + run.describe());
        assertEquals("2", run.value("blocked.prefsPost"), "the refused post and its wake: " + run.describe());
        assertEquals("0", run.value("blocked.prefsRefused"), run.describe());
        assertEquals("1", run.value("blocked.prefsDeferred"), run.describe());
        assertEquals("0", run.value("blocked.prefsDeferredRun"), run.describe());
        assertEquals("1", run.value("delivered.prefsDeferredRun"), run.describe());
        assertEquals("0", run.value("terminate.send"), run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
        assertEquals("0", run.value("after.status"), run.describe());
        assertEquals("1", run.value("after.prefsRefusedDelta"),
                "the sink that ran after the window was gone was not refused: " + run.describe());
        assertEquals("0", run.value("after.prefsPostDelta"), run.describe());
        assertEquals("0", run.value("after.prefsDeferredDelta"),
                "the sink that ran after the window was gone kept its type: " + run.describe());
        assertEquals("0", run.value("after.prefsCalls"), "preferences_changed ran after teardown: " + run.describe());
    }

    /**
     * Runs in {@link WinGlassChild}. A runnable posted with {@code gwin_invoke_later} blocks the toolkit thread
     * while this thread fills the toolkit thread's posted-message queue with {@code WM_NULL} until
     * {@code PostMessageW} fails, then invokes the network delegate, whose post and wake the full queue refuses
     * too, and unblocks the toolkit thread. The type the sink kept is delivered by the {@code WM_DO_ACTION_LATER}
     * arm of {@code GlassApplication::WindowProc}, right after the blocking runnable has returned.
     */
    static void invokeIntoFullQueue(Map<String, String> out) {
        intoFullQueue(out, (toolkit, blocker) -> out.put("busy.post", Integer.toString(toolkit.invokeLater(blocker))));
    }

    /**
     * Runs in {@link WinGlassChild}. {@link #invokeIntoFullQueue}, but a thread of its own, {@code waiter}, passes
     * the blocking runnable to {@code gwin_invoke_and_wait}: the toolkit thread runs it in the
     * {@code WM_DO_ACTION_NOTIFY} arm, inside the {@code GetMessage} of {@code gwin_run_loop}, and that arm delivers
     * no kept type. The type the sink kept is delivered by {@code gwin_run_loop} itself, after its
     * {@code DispatchMessage} of the first {@code WM_NULL} of the fill. Nothing else is posted to the toolkit
     * thread before the delivery has been recorded: a runnable or a wake dispatched first would deliver the type in
     * its own arm instead.
     */
    static void invokeIntoFullQueueDuringInvokeAndWait(Map<String, String> out) {
        AtomicReference<Thread> waiter = new AtomicReference<>();
        intoFullQueue(out, (toolkit, blocker) -> waiter.set(WinNativeToolkit.startThread("waiter",
                () -> out.put("waiter.status", Integer.toString(toolkit.invokeAndWait(blocker))))));
        out.put("waiter.ended", Boolean.toString(WinNativeToolkit.join(waiter.get(), WAIT_MILLIS)));
    }

    /**
     * The body of {@link #invokeIntoFullQueue} and {@link #invokeIntoFullQueueDuringInvokeAndWait}: {@code block}
     * hands the toolkit thread the runnable that blocks it. The block ends in a {@code finally}, and by itself after
     * {@value #BLOCK_MILLIS} ms. The delivery is recorded before anything else is posted to the toolkit thread, which
     * then ends by a send, because a post could still find the queue full.
     */
    private static void intoFullQueue(Map<String, String> out, BiConsumer<WinNativeToolkit, Runnable> block) {
        WinGlassNativeShim.installRecordingPreferencesTable();
        WinGlassNativeShim.prefsCapture(true);
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        long hwnd = toolkit.hwnd();
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        block.accept(toolkit, () -> {
            busy.countDown();
            out.put("busy.unblocked", Boolean.toString(WinNativeToolkit.await(unblock, BLOCK_MILLIS)));
        });
        long invoked = WinGlassNativeShim.nextSequence();
        try {
            out.put("busy.started", Boolean.toString(WinNativeToolkit.await(busy, WAIT_MILLIS)));
            WinGlassNativeShim.observe(true);
            fill(out, hwnd);
            out.put("sink.status",
                    Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_NETWORK)));
            PostedMessage probe = WinGlassNativeShim.postMessage(hwnd, WM_NULL);
            out.put("probe.posted", Boolean.toString(probe.posted()));
            out.put("probe.lastError", Integer.toString(probe.lastError()));
            out.put("blocked.networkCalls", Integer.toString(networkCallsSince(invoked).size()));
            recordSinkCounters(out, "blocked");
        } finally {
            unblock.countDown();
        }
        out.put("delivered", Boolean.toString(
                WinGlassChild.waitFor(WAIT_MILLIS, () -> !networkCallsSince(invoked).isEmpty())));
        List<PreferencesCall> delivered = networkCallsSince(invoked);
        out.put("delivered.networkCalls", Integer.toString(delivered.size()));
        out.put("delivered.types", delivered.stream().map(c -> Integer.toString(c.types()))
                .collect(Collectors.joining(",")));
        out.put("delivered.offToolkitThread",
                Long.toString(delivered.stream().filter(c -> c.thread() != toolkit.thread()).count()));
        recordSinkCounters(out, "delivered");

        // A sent runnable: a post could still find the queue full.
        out.put("terminate.send", Integer.toString(toolkit.terminateBySend()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        long refused = WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_REFUSED);
        long posted = WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_POST);
        long deferred = WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_DEFERRED);
        long afterTeardown = WinGlassNativeShim.nextSequence();
        out.put("after.status",
                Integer.toString(WinGlassNativeShim.prefsInvoke(WinGlassNativeShim.DELEGATE_NETWORK)));
        out.put("after.prefsRefusedDelta", Long.toString(
                WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_REFUSED) - refused));
        out.put("after.prefsPostDelta", Long.toString(
                WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_POST) - posted));
        out.put("after.prefsDeferredDelta", Long.toString(
                WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_DEFERRED) - deferred));
        out.put("after.prefsCalls", Long.toString(WinGlassNativeShim.recordedPreferencesCalls().stream()
                .filter(c -> c.sequence() > afterTeardown).count()));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /** Posts {@code WM_NULL} to {@code hwnd} until a post fails, {@value #FILL_CAP} times at most. */
    private static void fill(Map<String, String> out, long hwnd) {
        int posted = 0;
        PostedMessage last = null;
        while (posted < FILL_CAP) {
            last = WinGlassNativeShim.postMessage(hwnd, WM_NULL);
            if (!last.posted()) {
                break;
            }
            posted++;
        }
        out.put("fill.posted", Integer.toString(posted));
        out.put("fill.lastError", last == null || last.posted() ? "none" : Integer.toString(last.lastError()));
    }

    /** The recorded {@code preferences_changed} calls with the network bit, drawn after {@code sequence}. */
    private static List<PreferencesCall> networkCallsSince(long sequence) {
        return WinGlassNativeShim.recordedPreferencesCalls().stream()
                .filter(c -> c.sequence() > sequence && (c.types() & NETWORK_INFORMATION) != 0)
                .toList();
    }

    private static void recordSinkCounters(Map<String, String> out, String prefix) {
        out.put(prefix + ".prefsPost",
                Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_POST)));
        out.put(prefix + ".prefsRefused",
                Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_REFUSED)));
        out.put(prefix + ".prefsDeferred",
                Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_DEFERRED)));
        out.put(prefix + ".prefsDeferredRun",
                Long.toString(WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_PREFS_DEFERRED_RUN)));
    }

    /*
     * A listener that ends the toolkit.
     */

    @Test
    @Timeout(180)
    public void aDelegateOnTheToolkitThreadWhoseListenerCallsPlatformExitLetsTheJvmExit() {
        // These options follow the copied input arguments, so glass.platform=Win wins over the
        // -Dglass.platform=Headless that -DHEADLESS_TEST=true puts on the argLine.
        WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinPreferenceSinksNativeTest.class, "listenerExits",
                "listenerExits", List.of("-D" + WinGlassChild.NATURAL_EXIT_PROPERTY + "=true", "-Dglass.platform=Win"),
                60);
        System.out.println("listener exit: " + run.values());
        assertTrue(run.clean(), run.describe());
        assertEquals("com.sun.glass.ui.win.WinApplication", run.value("glass.application"),
                "the child did not run WinApplication: " + run.describe());
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
     * restores that boolean and the listener fires. With no such boolean, as under another Glass application, it
     * calls {@code Platform.exit()} at once, so that the child still ends and the test names what it recorded.
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
            out.put("glass.application", Application.GetApplication().getClass().getName());
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
            } else {
                Platform.exit();
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
