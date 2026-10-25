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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A Glass toolkit with nothing above it - no {@code Application}, no Quantum - for a scenario in
 * {@link WinGlassChild}. A thread of this class creates the toolkit window ({@code gwin_app_create}) and runs
 * {@code gwin_run_loop}; runnables reach it through {@code gwin_invoke_later} and {@code gwin_invoke_and_wait}, with
 * their statuses. One per process.
 * <p>
 * Once the loop has returned the thread runs the {@code afterLoop} action, if any, and then stays alive until
 * {@link #release()}, or for {@link #KEEP_ALIVE_MILLIS} at most: a caller still blocked in a send to the toolkit
 * window must not be let go merely because its thread ended.
 */
final class WinNativeToolkit {

    /** How long the toolkit thread waits for {@link #release()} after its loop returned. */
    static final long KEEP_ALIVE_MILLIS = 60_000;

    private final Thread thread;
    private final Runnable afterLoop;
    private final CountDownLatch created = new CountDownLatch(1);
    private final CountDownLatch loopReturned = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile long hwnd;
    private volatile int nativeThreadId;
    private volatile long loopReturnSequence;

    private WinNativeToolkit(Runnable afterLoop) {
        this.afterLoop = afterLoop;
        this.thread = new Thread(this::body, "WinNativeToolkit");
        this.thread.setDaemon(true);
    }

    /** {@link #start(Runnable)} with nothing to run after the loop. */
    static WinNativeToolkit start() {
        return start(null);
    }

    /**
     * Starts the toolkit thread and waits until it has created its window.
     *
     * @param afterLoop run on the toolkit thread right after {@code gwin_run_loop} returned, or {@code null}
     * @throws IllegalStateException if no toolkit window was created within 10 s
     */
    static WinNativeToolkit start(Runnable afterLoop) {
        WinNativeToolkit toolkit = new WinNativeToolkit(afterLoop);
        toolkit.thread.start();
        if (!await(toolkit.created, 10_000) || toolkit.hwnd == 0) {
            throw new IllegalStateException("gwin_app_create made no toolkit window within 10 s");
        }
        return toolkit;
    }

    private void body() {
        nativeThreadId = WinGlassNativeShim.nativeThreadId();
        hwnd = WinGlassNativeShim.toolkitCreate();
        created.countDown();
        if (hwnd == 0) {
            return;
        }
        try {
            WinGlassNativeShim.toolkitRunLoop(null);
        } finally {
            loopReturnSequence = WinGlassNativeShim.nextSequence();
            loopReturned.countDown();
        }
        if (afterLoop != null) {
            afterLoop.run();
        }
        await(released, KEEP_ALIVE_MILLIS);
    }

    /** The HWND {@code gwin_app_create} answered. */
    long hwnd() {
        return hwnd;
    }

    /** The toolkit thread. */
    Thread thread() {
        return thread;
    }

    /** The toolkit thread's {@code GetCurrentThreadId}. */
    int nativeThreadId() {
        return nativeThreadId;
    }

    /** {@code gwin_invoke_later(runnable)}: its status. */
    int invokeLater(Runnable runnable) {
        return WinGlassNativeShim.toolkitInvokeLater(runnable);
    }

    /** {@code gwin_invoke_and_wait(runnable)}: its status. */
    int invokeAndWait(Runnable runnable) {
        return WinGlassNativeShim.toolkitInvokeAndWait(runnable);
    }

    /** Posts a runnable that calls {@code gwin_terminate_loop}; the status of that post. */
    int terminate() {
        return invokeLater(WinGlassNativeShim::toolkitTerminateLoop);
    }

    /**
     * Ends the toolkit through {@code gwin_invoke_and_wait} from the calling thread, which the toolkit thread handles
     * ahead of every posted message, then wakes the toolkit thread, because {@code GetMessage} goes on waiting for a
     * posted message after it has handled a sent one. The status of the {@code gwin_invoke_and_wait}.
     */
    int terminateBySend() {
        int status = invokeAndWait(WinGlassNativeShim::toolkitTerminateLoop);
        WinGlassNativeShim.wakeThread(nativeThreadId);
        return status;
    }

    /** Whether {@code gwin_run_loop} returned within {@code millis}. */
    boolean awaitLoopReturn(long millis) {
        return await(loopReturned, millis);
    }

    /** The {@link WinGlassNativeShim#nextSequence()} number drawn when {@code gwin_run_loop} returned, 0 before. */
    long loopReturnSequence() {
        return loopReturnSequence;
    }

    /** Lets the toolkit thread end once its loop has returned. */
    void release() {
        released.countDown();
    }

    /** {@link #release()}, then whether the toolkit thread ended within {@code millis}. */
    boolean releaseAndAwaitEnd(long millis) {
        release();
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    static boolean await(CountDownLatch latch, long millis) {
        try {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Every {@code gwin_test_counter}, indexed as {@code enum GwinTestCounter} numbers them. */
    static long[] counters() {
        long[] values = new long[WinGlassNativeShim.COUNTER_COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = WinGlassNativeShim.counter(i);
        }
        return values;
    }

    /** {@code value} as {@code 0x...} hexadecimal. */
    static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    /**
     * A thread named {@code name} running {@code body}, daemon, started. An exception that escapes {@code body}
     * fails the {@link WinGlassChild} scenario through that JVM's default uncaught-exception handler.
     */
    static Thread startThread(String name, Runnable body) {
        Thread worker = new Thread(body, name);
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    /** Whether {@code worker} ended within {@code millis}. */
    static boolean join(Thread worker, long millis) {
        try {
            worker.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !worker.isAlive();
    }
}
