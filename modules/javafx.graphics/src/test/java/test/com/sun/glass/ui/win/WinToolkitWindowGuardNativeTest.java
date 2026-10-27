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
import com.sun.glass.ui.win.WinGlassNativeShim.DecoyHunt;
import com.sun.glass.ui.win.WinGlassNativeShim.MessageLog;
import com.sun.glass.ui.win.WinGlassNativeShim.PrivateMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.glass.ui.win.WinNativeToolkit.counters;
import static test.com.sun.glass.ui.win.WinNativeToolkit.hex;

/**
 * The toolkit window's publication and teardown, seen from the posts and sends that race it. Each test runs one
 * toolkit lifetime in a {@link WinGlassChild} JVM and reads the shim-only hooks: the park gate, which stops a poster
 * between its load of the toolkit HWND and its {@code PostMessage}; the counters of posts, sends, stale targets and
 * actions; the publication; the queue peek; and {@code gwin_test_send_action_notify}.
 * <ul>
 * <li>A parked {@code gwin_invoke_later} holds teardown: {@code gwin_terminate_loop} does not return while the
 * poster is parked and returns once it is released, and no call was aimed at a window that was no longer
 * published.</li>
 * <li>Disarming the gate ({@code gwin_test_park_arm(0)}) from another thread releases the parked poster: its
 * {@code gwin_invoke_later} returns, no thread is left parked, and the toolkit then ends normally.</li>
 * <li>Once the toolkit window is cleared, every {@code gwin_invoke_later} is refused, calls {@code PostMessage}
 * zero times and frees its action - also from the runnable that called {@code gwin_terminate_loop}, after that
 * call, while the toolkit object still exists.</li>
 * <li>A {@code gwin_invoke_and_wait} on the toolkit thread whose runnable ends the toolkit returns.</li>
 * <li>A {@code gwin_invoke_and_wait} from another thread that is queued when the toolkit ends returns, and answers
 * {@code GWIN_ERR_NO_TOOLKIT} exactly when its runnable did not run.</li>
 * <li>A {@code gwin_invoke_and_wait} from another thread whose runnable ends the toolkit answers {@code GWIN_OK}
 * after that runnable.</li>
 * <li>The cross-thread invoke message with a {@code WPARAM} that is no pending call is ignored.</li>
 * </ul>
 */
@EnabledOnOs(OS.WINDOWS)
public class WinToolkitWindowGuardNativeTest {

    /** How long a scenario waits for any one step that should happen. */
    private static final long WAIT_MILLIS = 10_000;

    /** How long a scenario waits for a step that should not happen while a poster is parked. */
    private static final long HOLD_MILLIS = 2_000;

    /** A value that is neither 0 nor a pointer anything allocated: the header's {@code GWIN_TEST_HOOK_POINTER}. */
    private static final long NOT_A_PENDING_CALL = 0x0000_7FFE_1234_5678L;

    /** How many decoy windows the handle-reuse record creates at most: above the 65,536 reuses of one slot. */
    private static final long DECOY_CAP = 300_000;

    private static final int POSTERS = 4;
    private static final int POSTS_PER_POSTER = 25;

    private static String noToolkit;
    private static String invalidArg;

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
        noToolkit = Integer.toString(WinGlassNativeShim.constant("GWIN_ERR_NO_TOOLKIT"));
        invalidArg = Integer.toString(WinGlassNativeShim.constant("GWIN_ERR_INVALID_ARG"));
    }

    /*
     * A parked post holds teardown.
     */

    @Test
    @Timeout(120)
    public void aParkedInvokeLaterHoldsTeardownAndPostsOnlyToThePublishedWindow() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitWindowGuardNativeTest.class, "parkedPost");
        System.out.println("parked post: " + run.values());
        assertEquals("true", run.value("published.matches"), run.describe());
        assertEquals("true", run.value("parked"), "the poster never reached the gate: " + run.describe());
        assertEquals("true", run.value("terminate.entered"),
                "the terminating runnable never started, so the hold proved nothing: " + run.describe());
        assertEquals("false", run.value("terminate.returnedWhileParked"),
                "gwin_terminate_loop returned while a poster was parked between its load and its PostMessage: "
                        + run.describe());
        assertEquals("true", run.value("terminate.returnedAfterRelease"), run.describe());
        assertEquals("0", run.value("counter.stale"),
                "a call was aimed at a window that was no longer the published toolkit window: " + run.describe());
        assertEquals("true", run.value("poster.ended"), run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
    }

    /**
     * Runs in {@link WinGlassChild}. The hold starts once the terminating runnable has started, so a toolkit thread
     * that reaches that runnable only after the release cannot make an unheld teardown look held. Also records what
     * the toolkit thread's queue holds after teardown.
     */
    static void parkedPost(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        long hwnd = toolkit.hwnd();
        long[] published = WinGlassNativeShim.publication();
        out.put("toolkit.hwnd", hex(hwnd));
        out.put("published.matches", Boolean.toString(published[0] == hwnd
                && published[1] == toolkit.nativeThreadId()));
        WinGlassNativeShim.observe(true);

        AtomicBoolean parkedRan = new AtomicBoolean();
        Thread poster = WinNativeToolkit.startThread("poster", () -> {
            out.put("poster.arm", Integer.toString(WinGlassNativeShim.parkArm(true)));
            int status = WinGlassNativeShim.toolkitInvokeLater(() -> parkedRan.set(true));
            out.put("poster.status", Integer.toString(status));
        });
        out.put("parked", Boolean.toString(WinGlassNativeShim.parkWait((int) WAIT_MILLIS)));

        CountDownLatch terminateEntered = new CountDownLatch(1);
        CountDownLatch terminateReturned = new CountDownLatch(1);
        out.put("terminate.post", Integer.toString(toolkit.invokeLater(() -> {
            terminateEntered.countDown();
            WinGlassNativeShim.toolkitTerminateLoop();
            terminateReturned.countDown();
            recordPeek(out, hwnd);
        })));
        out.put("terminate.entered", Boolean.toString(WinNativeToolkit.await(terminateEntered, WAIT_MILLIS)));
        out.put("terminate.returnedWhileParked",
                Boolean.toString(WinNativeToolkit.await(terminateReturned, HOLD_MILLIS)));
        out.put("release", Integer.toString(WinGlassNativeShim.parkRelease()));
        out.put("terminate.returnedAfterRelease",
                Boolean.toString(WinNativeToolkit.await(terminateReturned, WAIT_MILLIS)));
        out.put("poster.ended", Boolean.toString(WinNativeToolkit.join(poster, WAIT_MILLIS)));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("parked.ran", Boolean.toString(parkedRan.get()));
        recordCounters(out, counters());
        long[] after = WinGlassNativeShim.publication();
        out.put("published.after", hex(after[0]) + "," + after[1]);
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /** The private messages left on the calling thread's queue, and how many carry the destroyed toolkit HWND. */
    private static void recordPeek(Map<String, String> out, long hwnd) {
        MessageLog peek = WinGlassNativeShim.peekPrivateMessages();
        out.put("peek.total", Integer.toString(peek.total()));
        out.put("peek.toDestroyedWindow",
                Long.toString(peek.messages().stream().filter(m -> m.hwnd() == hwnd).count()));
        out.put("peek.messages", describe(peek.messages()));
    }

    @Test
    @Timeout(120)
    public void disarmingTheGateReleasesAParkedInvokeLater() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitWindowGuardNativeTest.class, "disarmedPost");
        System.out.println("disarmed post: " + run.values());
        assertEquals("0", run.value("poster.arm"), run.describe());
        assertEquals("true", run.value("parked"), "the poster never reached the gate: " + run.describe());
        assertEquals("0", run.value("disarm"), run.describe());
        assertEquals("true", run.value("poster.endedAfterDisarm"),
                "gwin_test_park_arm(0) did not release the parked poster within " + WAIT_MILLIS + " ms: "
                        + run.describe());
        assertEquals(invalidArg, run.value("release"),
                "a thread was still parked after the disarm, so gwin_test_park_release found one: " + run.describe());
        assertEquals("0", run.value("poster.status"), run.describe());
        assertEquals("true", run.value("parked.ran"), run.describe());
        assertEquals("0", run.value("terminate.post"), run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
        assertEquals("true", run.value("toolkit.ended"), run.describe());
    }

    /**
     * Runs in {@link WinGlassChild}. The gate is released with {@code gwin_test_park_release} after the bounded
     * wait for the poster, so a disarm that left the poster parked holds neither the poster nor the teardown for
     * the gate's 120 s cap.
     */
    static void disarmedPost(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        WinGlassNativeShim.observe(true);
        CountDownLatch parkedRan = new CountDownLatch(1);
        Thread poster = WinNativeToolkit.startThread("poster", () -> {
            out.put("poster.arm", Integer.toString(WinGlassNativeShim.parkArm(true)));
            out.put("poster.status", Integer.toString(WinGlassNativeShim.toolkitInvokeLater(parkedRan::countDown)));
        });
        out.put("parked", Boolean.toString(WinGlassNativeShim.parkWait((int) WAIT_MILLIS)));
        long disarmed = System.nanoTime();
        out.put("disarm", Integer.toString(WinGlassNativeShim.parkArm(false)));
        boolean ended = WinNativeToolkit.join(poster, WAIT_MILLIS);
        out.put("poster.endedAfterDisarm", Boolean.toString(ended));
        out.put("poster.millisAfterDisarm", Long.toString((System.nanoTime() - disarmed) / 1_000_000));
        out.put("release", Integer.toString(WinGlassNativeShim.parkRelease()));
        out.put("poster.ended", Boolean.toString(WinNativeToolkit.join(poster, WAIT_MILLIS)));
        out.put("parked.ran", Boolean.toString(WinNativeToolkit.await(parkedRan, WAIT_MILLIS)));
        out.put("terminate.post", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        recordCounters(out, counters());
        out.put("toolkit.ended", Boolean.toString(toolkit.releaseAndAwaitEnd(WAIT_MILLIS)));
    }

    /*
     * Every post after the clear is refused.
     */

    @Test
    @Timeout(120)
    public void everyInvokeLaterAfterTheClearIsRefusedWithoutAPostAndFreesItsAction() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitWindowGuardNativeTest.class, "postsAfterTheClear");
        assertEquals("true", run.value("inRunnable.ran"), run.describe());
        assertEquals("0x0,0", run.value("inRunnable.published"),
                "the toolkit window is still published after gwin_terminate_loop returned: " + run.describe());
        assertEquals(noToolkit, run.value("inRunnable.status"),
                "the terminating runnable's own post after gwin_terminate_loop: " + run.describe());
        assertEquals(noToolkit + "x" + POSTERS * POSTS_PER_POSTER, run.value("inRunnable.others"), run.describe());
        assertEquals("0", run.value("inRunnable.postDelta"),
                "PostMessage was called after the clear, from the terminating runnable or beside it: "
                        + run.describe());
        assertEquals(run.value("inRunnable.allocDelta"), run.value("inRunnable.freeDelta"),
                "an action refused after the clear was not freed: " + run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
        assertEquals(noToolkit + "x" + (POSTERS * POSTS_PER_POSTER + 1), run.value("afterLoop.statuses"),
                run.describe());
        assertEquals("0", run.value("afterLoop.postDelta"), run.describe());
        assertEquals(run.value("afterLoop.allocDelta"), run.value("afterLoop.freeDelta"), run.describe());
        assertEquals("0", run.value("refused.ran"), "a refused runnable ran: " + run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void postsAfterTheClear(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        WinGlassNativeShim.observe(true);
        AtomicInteger refusedRan = new AtomicInteger();
        out.put("terminate.post", Integer.toString(toolkit.invokeLater(() -> {
            WinGlassNativeShim.toolkitTerminateLoop();
            long[] before = counters();
            long[] published = WinGlassNativeShim.publication();
            out.put("inRunnable.published", hex(published[0]) + "," + published[1]);
            out.put("inRunnable.status",
                    Integer.toString(WinGlassNativeShim.toolkitInvokeLater(refusedRan::incrementAndGet)));
            // Other threads, while this runnable keeps the toolkit object alive.
            out.put("inRunnable.others", postFromThreads(refusedRan));
            long[] after = counters();
            recordDeltas(out, "inRunnable", before, after);
            out.put("inRunnable.ran", "true");
        })));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        long[] before = counters();
        List<Integer> statuses = new ArrayList<>();
        statuses.add(WinGlassNativeShim.toolkitInvokeLater(refusedRan::incrementAndGet));
        String others = postFromThreads(refusedRan);
        long[] after = counters();
        out.put("afterLoop.statuses", merge(histogram(statuses), others));
        recordDeltas(out, "afterLoop", before, after);
        out.put("refused.ran", Integer.toString(refusedRan.get()));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /** {@value #POSTERS} threads post {@value #POSTS_PER_POSTER} runnables each; their statuses as a histogram. */
    private static String postFromThreads(AtomicInteger ran) {
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        List<Thread> posters = new ArrayList<>();
        for (int i = 0; i < POSTERS; i++) {
            posters.add(WinNativeToolkit.startThread("poster-" + i, () -> {
                for (int j = 0; j < POSTS_PER_POSTER; j++) {
                    statuses.add(WinGlassNativeShim.toolkitInvokeLater(ran::incrementAndGet));
                }
            }));
        }
        for (Thread poster : posters) {
            WinNativeToolkit.join(poster, WAIT_MILLIS);
        }
        synchronized (statuses) {
            return histogram(statuses);
        }
    }

    /*
     * Synchronous calls that end the toolkit.
     */

    @Test
    @Timeout(120)
    public void anInvokeAndWaitOnTheToolkitThreadWhoseRunnableEndsTheToolkitReturns() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitWindowGuardNativeTest.class,
                "toolkitThreadInvokeAndWaitThatTerminates");
        assertEquals("true", run.value("inner.returned"),
                "gwin_invoke_and_wait on the toolkit thread did not return after its runnable ended the toolkit: "
                        + run.describe());
        assertEquals("true", run.value("inner.ran"), run.describe());
        assertEquals("0", run.value("inner.status"), run.describe());
        assertEquals("1", run.value("inner.sendDelta"), "the inline path is one SendMessage: " + run.describe());
        assertEquals("0", run.value("inner.notifyDelta"), "the inline path queues nothing: " + run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void toolkitThreadInvokeAndWaitThatTerminates(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        WinGlassNativeShim.observe(true);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean innerRan = new AtomicBoolean();
        out.put("outer.post", Integer.toString(toolkit.invokeLater(() -> {
            long[] before = counters();
            int status = WinGlassNativeShim.toolkitInvokeAndWait(() -> {
                WinGlassNativeShim.toolkitTerminateLoop();
                innerRan.set(true);
            });
            long[] after = counters();
            out.put("inner.status", Integer.toString(status));
            out.put("inner.sendDelta",
                    Long.toString(after[WinGlassNativeShim.COUNTER_SEND] - before[WinGlassNativeShim.COUNTER_SEND]));
            out.put("inner.notifyDelta", Long.toString(
                    after[WinGlassNativeShim.COUNTER_NOTIFY] - before[WinGlassNativeShim.COUNTER_NOTIFY]));
            returned.countDown();
        })));
        out.put("inner.returned", Boolean.toString(WinNativeToolkit.await(returned, WAIT_MILLIS)));
        out.put("inner.ran", Boolean.toString(innerRan.get()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    @Test
    @Timeout(120)
    public void anInvokeAndWaitQueuedWhenTheToolkitEndsReturnsAndSaysWhetherItRan() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitWindowGuardNativeTest.class,
                "sendQueuedAtTeardown");
        System.out.println("queued send at teardown: " + run.values());
        assertEquals("true", run.value("busy.sawPendingSend"),
                "B's call was never queued while the toolkit thread was busy: " + run.describe());
        assertEquals("true", run.value("b.returnedWhileToolkitThreadLives"),
                "gwin_invoke_and_wait from another thread did not return when the toolkit window was destroyed"
                        + " with its call queued: " + run.describe());
        boolean ran = Boolean.parseBoolean(run.value("w.ran"));
        assertEquals(ran ? "0" : noToolkit, run.value("b.status"),
                "the status must say whether the runnable ran (it " + (ran ? "did" : "did not") + "): "
                        + run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void sendQueuedAtTeardown(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        WinGlassNativeShim.observe(true);
        CountDownLatch busy = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        out.put("busy.post", Integer.toString(toolkit.invokeLater(() -> {
            busy.countDown();
            out.put("busy.sawPendingSend", Boolean.toString(
                    WinGlassChild.waitFor(WAIT_MILLIS, WinGlassNativeShim::sentMessagePending)));
            WinGlassNativeShim.toolkitTerminateLoop();
            out.put("busy.terminateReturned", "true");
        })));
        out.put("busy.started", Boolean.toString(WinNativeToolkit.await(busy, WAIT_MILLIS)));
        CountDownLatch bReturned = new CountDownLatch(1);
        WinNativeToolkit.startThread("B", () -> {
            out.put("b.status", Integer.toString(toolkit.invokeAndWait(() -> ran.set(true))));
            bReturned.countDown();
        });
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("b.returnedWhileToolkitThreadLives", Boolean.toString(WinNativeToolkit.await(bReturned, WAIT_MILLIS)));
        out.put("toolkit.threadEnded", Boolean.toString(toolkit.releaseAndAwaitEnd(WAIT_MILLIS)));
        out.put("b.returnedAfterToolkitThreadEnded", Boolean.toString(WinNativeToolkit.await(bReturned, WAIT_MILLIS)));
        out.put("w.ran", Boolean.toString(ran.get()));
        recordCounters(out, counters());
    }

    @Test
    @Timeout(120)
    public void anInvokeAndWaitFromAnotherThreadWhoseRunnableEndsTheToolkitAnswersOkAfterIt() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitWindowGuardNativeTest.class,
                "crossThreadRunnableThatTerminates");
        assertEquals("true", run.value("b.returned"),
                "gwin_invoke_and_wait from another thread did not return after its runnable ended the toolkit: "
                        + run.describe());
        assertEquals("0", run.value("b.status"), run.describe());
        assertEquals("true", run.value("b.runnableDoneBeforeReturn"), run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void crossThreadRunnableThatTerminates(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        WinGlassNativeShim.observe(true);
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch bReturned = new CountDownLatch(1);
        WinNativeToolkit.startThread("B", () -> {
            int status = toolkit.invokeAndWait(() -> {
                WinGlassNativeShim.toolkitTerminateLoop();
                done.set(true);
            });
            out.put("b.status", Integer.toString(status));
            out.put("b.runnableDoneBeforeReturn", Boolean.toString(done.get()));
            bReturned.countDown();
        });
        out.put("b.returned", Boolean.toString(WinNativeToolkit.await(bReturned, WAIT_MILLIS)));
        // Recorded, not required: the runnable ran inside a sent message, and GetMessage goes on waiting for a
        // posted one after it has handled a sent one, so gwin_run_loop returns only once something is posted.
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(HOLD_MILLIS)));
        recordCounters(out, counters());
    }

    /*
     * The cross-thread invoke message with a WPARAM that names no pending call.
     */

    @Test
    @Timeout(120)
    public void theCrossThreadInvokeMessageWithAnUnknownWparamIsIgnored() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinToolkitWindowGuardNativeTest.class, "unknownWparam",
                "unknownWparam", List.of(), WinGlassChildJvm.CHILD_SECONDS);
        assertTrue(run.clean(), "the toolkit window's procedure crashed on a WPARAM that is no pending call: "
                + run.describe());
        assertEquals("0", run.value("send.pointer"), run.describe());
        assertEquals("0", run.value("send.zero"), run.describe());
        assertEquals("0", run.value("send.onToolkitThread"), run.describe());
        assertEquals("true", run.value("after.ran"), "the toolkit stopped working: " + run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void unknownWparam(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        out.put("send.pointer", Integer.toString(WinGlassNativeShim.sendActionNotify(NOT_A_PENDING_CALL)));
        out.put("send.zero", Integer.toString(WinGlassNativeShim.sendActionNotify(0L)));
        CountDownLatch inline = new CountDownLatch(1);
        toolkit.invokeLater(() -> {
            out.put("send.onToolkitThread", Integer.toString(WinGlassNativeShim.sendActionNotify(NOT_A_PENDING_CALL)));
            inline.countDown();
        });
        WinNativeToolkit.await(inline, WAIT_MILLIS);
        CountDownLatch after = new CountDownLatch(1);
        out.put("after.post", Integer.toString(toolkit.invokeLater(after::countDown)));
        out.put("after.ran", Boolean.toString(WinNativeToolkit.await(after, WAIT_MILLIS)));
        out.put("terminate.post", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /*
     * The handle-reuse record: opt-in, a record rather than a check, for a build whose poster does not hold teardown.
     */

    /**
     * Parks a poster, lets teardown finish if it can, hunts for the destroyed toolkit HWND with decoy windows,
     * releases the poster and reports where its {@code WM_DO_ACTION_LATER} went. Takes tens of seconds; run with
     * {@code -Dwin.glass.decoyRun=true}. It asserts only that the child ran; the values are the record.
     */
    @Test
    @Timeout(300)
    @EnabledIfSystemProperty(named = "win.glass.decoyRun", matches = "true")
    public void handleReuseRecord() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinToolkitWindowGuardNativeTest.class, "handleReuse",
                "handleReuse", List.of(), 240);
        System.out.println("handle reuse record: " + run.describe());
        assertTrue(run.clean(), run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void handleReuse(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        long hwnd = toolkit.hwnd();
        out.put("toolkit.hwnd", hex(hwnd));
        WinGlassNativeShim.observe(true);
        AtomicBoolean parkedRan = new AtomicBoolean();
        Thread poster = WinNativeToolkit.startThread("poster", () -> {
            WinGlassNativeShim.parkArm(true);
            int status = WinGlassNativeShim.toolkitInvokeLater(() -> parkedRan.set(true));
            out.put("poster.status", Integer.toString(status));
        });
        out.put("parked", Boolean.toString(WinGlassNativeShim.parkWait((int) WAIT_MILLIS)));
        out.put("terminate.post", Integer.toString(toolkit.terminate()));
        boolean tornDown = toolkit.awaitLoopReturn(WAIT_MILLIS);
        out.put("teardown.finishedWhileParked", Boolean.toString(tornDown));
        if (tornDown) {
            out.put("decoy.start", Integer.toString(WinGlassNativeShim.decoyStart()));
            long started = System.nanoTime();
            DecoyHunt hunt = WinGlassNativeShim.decoyHunt(hwnd, DECOY_CAP);
            out.put("decoy.huntMillis", Long.toString((System.nanoTime() - started) / 1_000_000));
            out.put("decoy.hunt", hunt.toString());
        }
        out.put("release", Integer.toString(WinGlassNativeShim.parkRelease()));
        out.put("poster.ended", Boolean.toString(WinNativeToolkit.join(poster, WAIT_MILLIS)));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        if (tornDown) {
            WinGlassChild.waitFor(2_000, () -> WinGlassNativeShim.decoyMessages().total() > 0);
            MessageLog delivered = WinGlassNativeShim.decoyMessages();
            out.put("decoy.messages", describe(delivered.messages()));
            out.put("decoy.actionLaterToTheReusedHwnd", Long.toString(delivered.messages().stream()
                    .filter(m -> m.hwnd() == hwnd && m.message() == WinGlassNativeShim.WM_DO_ACTION_LATER).count()));
            out.put("decoy.stop", Integer.toString(WinGlassNativeShim.decoyStop()));
        }
        out.put("parked.ran", Boolean.toString(parkedRan.get()));
        recordCounters(out, counters());
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    /*
     * Helpers.
     */

    private static void recordCounters(Map<String, String> out, long[] values) {
        out.put("counter.post", Long.toString(values[WinGlassNativeShim.COUNTER_POST]));
        out.put("counter.notify", Long.toString(values[WinGlassNativeShim.COUNTER_NOTIFY]));
        out.put("counter.send", Long.toString(values[WinGlassNativeShim.COUNTER_SEND]));
        out.put("counter.stale", Long.toString(values[WinGlassNativeShim.COUNTER_STALE]));
        out.put("counter.actionAlloc", Long.toString(values[WinGlassNativeShim.COUNTER_ACTION_ALLOC]));
        out.put("counter.actionFree", Long.toString(values[WinGlassNativeShim.COUNTER_ACTION_FREE]));
    }

    private static void recordDeltas(Map<String, String> out, String prefix, long[] before, long[] after) {
        out.put(prefix + ".postDelta", Long.toString(delta(before, after, WinGlassNativeShim.COUNTER_POST)));
        out.put(prefix + ".staleDelta", Long.toString(delta(before, after, WinGlassNativeShim.COUNTER_STALE)));
        out.put(prefix + ".allocDelta", Long.toString(delta(before, after, WinGlassNativeShim.COUNTER_ACTION_ALLOC)));
        out.put(prefix + ".freeDelta", Long.toString(delta(before, after, WinGlassNativeShim.COUNTER_ACTION_FREE)));
    }

    private static long delta(long[] before, long[] after, int counter) {
        return after[counter] - before[counter];
    }

    /** {@code "status x count"} pairs in status order, comma-separated: {@code "-2x100"}. */
    static String histogram(List<Integer> statuses) {
        Map<Integer, Long> counts = new TreeMap<>(statuses.stream()
                .collect(Collectors.groupingBy(s -> s, Collectors.counting())));
        return counts.entrySet().stream().map(e -> e.getKey() + "x" + e.getValue())
                .collect(Collectors.joining(","));
    }

    /** Two histograms of {@link #histogram} as one. */
    private static String merge(String first, String second) {
        Map<Integer, Long> counts = new TreeMap<>();
        for (String histogram : List.of(first, second)) {
            for (String pair : histogram.split(",")) {
                if (!pair.isEmpty()) {
                    int x = pair.lastIndexOf('x');
                    counts.merge(Integer.parseInt(pair.substring(0, x)), Long.parseLong(pair.substring(x + 1)),
                            Long::sum);
                }
            }
        }
        return counts.entrySet().stream().map(e -> e.getKey() + "x" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private static String describe(List<PrivateMessage> messages) {
        return messages.stream()
                .map(m -> "{" + hex(m.hwnd()) + ", " + hex(m.message()) + ", " + hex(m.wparam()) + "}")
                .collect(Collectors.joining(" "));
    }
}
