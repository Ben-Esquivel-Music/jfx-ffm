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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statistical line beside the deterministic tests of {@link WinToolkitWindowGuardNativeTest}: threads call
 * {@code gwin_invoke_later}, {@code gwin_invoke_and_wait} and {@code gwin_key_code_for_char} in a loop while the
 * toolkit window is destroyed under them, and go on calling after it is gone. Each run is a {@link WinGlassChild}
 * JVM of its own and must end with no access violation (status 0, no fatal error log), no call aimed at a window
 * that was no longer the published toolkit window ({@code STALE} 0), no caller left blocked and no runnable id left
 * registered by a call the toolkit refused. A run whose toolkit did not end, or that made no call after the end,
 * counts as failed: it hammered nothing across shutdown. At most {@value #OUTSTANDING_POSTS} posts wait at once,
 * and the toolkit is ended by a sent runnable: a posted one would wait behind the posts and starve behind the stream
 * of sends, which the toolkit thread always handles first.
 * <p>
 * {@code -Dwin.glass.hammer.runs=N} sets the number of runs (default {@value #DEFAULT_RUNS}). A run gets at most
 * {@value #SECONDS_PER_RUN} s, the whole test an hour.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinToolkitShutdownHammerTest {

    private static final int DEFAULT_RUNS = 3;
    private static final int SECONDS_PER_RUN = 30;
    private static final int THREADS = 6;
    private static final long WAIT_MILLIS = 10_000;

    /**
     * The posts that may wait in the toolkit thread's queue at once. Without a bound the posters outrun the toolkit
     * thread, and the runnable that ends the toolkit queues behind thousands of others.
     */
    private static final int OUTSTANDING_POSTS = 32;

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    @Test
    @Timeout(value = 3600)
    public void callsAcrossShutdownNeverCrashAndNeverReachAWindowThatIsNotPublished() {
        int runs = Integer.getInteger("win.glass.hammer.runs", DEFAULT_RUNS);
        List<String> failures = new ArrayList<>();
        List<WinGlassChildJvm.Run> children = new ArrayList<>();
        long calls = 0;
        for (int i = 0; i < runs; i++) {
            WinGlassChildJvm.Run run = WinGlassChildJvm.launch(WinToolkitShutdownHammerTest.class, "hammer",
                    "hammer-" + i, List.of(), SECONDS_PER_RUN);
            children.add(run);
            if (!passed(run)) {
                failures.add(run.describe());
            }
            calls += Long.parseLong(run.values().getOrDefault("hammer.calls", "0"));
            if (i == 0 || i == runs - 1) {
                System.out.println("hammer run " + i + ": " + run.values());
            }
        }
        System.out.println("hammer: " + runs + " runs, " + calls + " calls, " + failures.size() + " failed ("
                + WinGlassChildJvm.loadedGlass(children) + ")");
        assertEquals(List.of(), failures, failures.size() + " of " + runs + " hammer runs failed");
        assertTrue(calls > 0, "no call was made");
    }

    /**
     * A clean exit, {@code STALE} 0 and no caller left blocked; and, so that the run did hammer across shutdown, a
     * loop that returned and calls made after it. The runnable registry must then hold exactly the posts the
     * teardown dropped from the queue: every refused {@code gwin_invoke_later}, and every {@code gwin_invoke_and_wait}
     * whatever it answered, has taken its id out again, as the facade does.
     */
    private static boolean passed(WinGlassChildJvm.Run run) {
        Map<String, String> values = run.values();
        return run.clean() && "0".equals(values.get("counter.stale")) && "0".equals(values.get("hammer.stuck"))
                && "true".equals(values.get("loop.returned"))
                && !"0".equals(values.getOrDefault("hammer.callsAfterTeardown", "0"))
                && values.containsKey("registry.afterHammers")
                && values.get("registry.afterHammers").equals(values.get("posts.queuedAtShutdown"));
    }

    /** Runs in {@link WinGlassChild}. */
    static void hammer(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        WinGlassNativeShim.observe(true);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong calls = new AtomicLong();
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        List<Thread> hammers = new ArrayList<>();
        Semaphore outstanding = new Semaphore(OUTSTANDING_POSTS);
        for (int t = 0; t < THREADS; t++) {
            int first = t;
            hammers.add(WinNativeToolkit.startThread("hammer-" + t, () -> {
                for (int i = first; !stop.get(); i++) {
                    switch (i % 3) {
                        case 0 -> {
                            if (!outstanding.tryAcquire()) {
                                continue;
                            }
                            int status = toolkit.invokeLater(outstanding::release);
                            if (status != 0) {
                                outstanding.release();
                            }
                            statuses.add(status);
                        }
                        case 1 -> statuses.add(toolkit.invokeAndWait(() -> { }));
                        default -> WinGlassNativeShim.keyCodeForChar('a', 0);
                    }
                    calls.incrementAndGet();
                }
            }));
        }
        sleep(ThreadLocalRandom.current().nextLong(20, 250));
        out.put("terminate.send", Integer.toString(toolkit.terminateBySend()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("toolkit.threadEnded", Boolean.toString(toolkit.releaseAndAwaitEnd(WAIT_MILLIS)));
        // Posts the teardown dropped from the queue never ran, so they never gave their permits back.
        outstanding.release(OUTSTANDING_POSTS);
        long callsAtTeardown = calls.get();
        sleep(ThreadLocalRandom.current().nextLong(20, 250));
        stop.set(true);
        int stuck = 0;
        for (Thread hammer : hammers) {
            if (!WinNativeToolkit.join(hammer, WAIT_MILLIS)) {
                stuck++;
            }
        }
        out.put("hammer.stuck", Integer.toString(stuck));
        // Every caller has returned, so no gwin_invoke_and_wait holds an id. A post that ran gave its permit back and
        // the upcall took its id; a refused post gave its permit back and the refusal took its id; a post the
        // teardown dropped from the queue kept both. The pool started with OUTSTANDING_POSTS permits and the
        // teardown added as many.
        out.put("posts.queuedAtShutdown", Integer.toString(2 * OUTSTANDING_POSTS - outstanding.availablePermits()));
        out.put("registry.afterHammers", Integer.toString(WinGlassNativeShim.runnableRegistrySize()));
        out.put("hammer.calls", Long.toString(calls.get()));
        out.put("hammer.callsAfterTeardown", Long.toString(calls.get() - callsAtTeardown));
        synchronized (statuses) {
            out.put("hammer.statuses", WinToolkitWindowGuardNativeTest.histogram(statuses));
        }
        long[] counters = WinNativeToolkit.counters();
        out.put("counter.stale", Long.toString(counters[WinGlassNativeShim.COUNTER_STALE]));
        out.put("counter.post", Long.toString(counters[WinGlassNativeShim.COUNTER_POST]));
        out.put("counter.send", Long.toString(counters[WinGlassNativeShim.COUNTER_SEND]));
        out.put("counter.notify", Long.toString(counters[WinGlassNativeShim.COUNTER_NOTIFY]));
        out.put("counter.actionAlloc", Long.toString(counters[WinGlassNativeShim.COUNTER_ACTION_ALLOC]));
        out.put("counter.actionFree", Long.toString(counters[WinGlassNativeShim.COUNTER_ACTION_FREE]));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
