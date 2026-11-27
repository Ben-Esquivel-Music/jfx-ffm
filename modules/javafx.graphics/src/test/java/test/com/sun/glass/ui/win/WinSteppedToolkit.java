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

/**
 * A Glass toolkit thread, as {@link WinNativeToolkit} runs one, that runs caller code at four points of its life:
 * before {@code gwin_app_create}, right after it, inside {@code gwin_run_loop} before its pump starts (the loop's
 * launchable, which runs after the loop's {@code OleInitialize}), and right after the loop has returned. For a
 * scenario in {@link WinGlassChild} that records the COM apartment of the toolkit thread at those points, or that
 * puts the thread into an apartment of its own choosing before the toolkit exists. One toolkit at a time: another,
 * on a new thread or (from the step after the loop) on this one, only once the earlier toolkit window is destroyed and
 * its {@code gwin_run_loop} has returned, as {@link WinPreferenceToolkitLifecycleTest} does.
 * <p>
 * Once the loop has returned and the last step has run, the thread stays alive until {@link #releaseAndAwaitEnd}, or
 * for {@link WinNativeToolkit#KEEP_ALIVE_MILLIS} at most.
 */
final class WinSteppedToolkit {

    private final Thread thread;
    private final Runnable beforeCreate;
    private final Runnable afterCreate;
    private final Runnable inLoop;
    private final Runnable afterLoop;
    private final CountDownLatch created = new CountDownLatch(1);
    private final CountDownLatch loopReturned = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile long hwnd;
    private volatile int nativeThreadId;

    private WinSteppedToolkit(Runnable beforeCreate, Runnable afterCreate, Runnable inLoop, Runnable afterLoop) {
        this.beforeCreate = beforeCreate;
        this.afterCreate = afterCreate;
        this.inLoop = inLoop;
        this.afterLoop = afterLoop;
        this.thread = new Thread(this::body, "WinSteppedToolkit");
        this.thread.setDaemon(true);
    }

    /**
     * Starts the toolkit thread and waits until it has created its window and run {@code afterCreate}.
     *
     * @throws IllegalStateException if no toolkit window was created within 10 s
     */
    static WinSteppedToolkit start(Runnable beforeCreate, Runnable afterCreate, Runnable inLoop,
                                   Runnable afterLoop) {
        WinSteppedToolkit toolkit = new WinSteppedToolkit(beforeCreate, afterCreate, inLoop, afterLoop);
        toolkit.thread.start();
        if (!WinNativeToolkit.await(toolkit.created, 10_000) || toolkit.hwnd == 0) {
            throw new IllegalStateException("gwin_app_create made no toolkit window within 10 s");
        }
        return toolkit;
    }

    private void body() {
        nativeThreadId = WinGlassNativeShim.nativeThreadId();
        try {
            beforeCreate.run();
            hwnd = WinGlassNativeShim.toolkitCreate();
            afterCreate.run();
        } finally {
            created.countDown();
        }
        if (hwnd == 0) {
            return;
        }
        try {
            WinGlassNativeShim.toolkitRunLoop(inLoop);
            afterLoop.run();
        } finally {
            loopReturned.countDown();
        }
        WinNativeToolkit.await(released, WinNativeToolkit.KEEP_ALIVE_MILLIS);
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

    /** {@code gwin_invoke_and_wait(runnable)}: its status. */
    int invokeAndWait(Runnable runnable) {
        return WinGlassNativeShim.toolkitInvokeAndWait(runnable);
    }

    /** {@code gwin_invoke_later(runnable)}: its status. */
    int invokeLater(Runnable runnable) {
        return WinGlassNativeShim.toolkitInvokeLater(runnable);
    }

    /** Whether {@code gwin_run_loop} returned, and the step after it ran, within {@code millis}. */
    boolean awaitLoopReturn(long millis) {
        return WinNativeToolkit.await(loopReturned, millis);
    }

    /** Lets the toolkit thread end once its loop has returned, then whether it ended within {@code millis}. */
    boolean releaseAndAwaitEnd(long millis) {
        released.countDown();
        return WinNativeToolkit.join(thread, millis);
    }
}
