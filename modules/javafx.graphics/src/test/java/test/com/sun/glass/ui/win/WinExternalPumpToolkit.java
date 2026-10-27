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
import com.sun.glass.ui.win.WinGlassNativeShim.PumpedMessage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Glass toolkit whose thread pumps with a loop of its own instead of {@code gwin_run_loop}, as the event loop of
 * the embedder does when JavaFX is embedded in SWT: {@code GetMessageW}, {@code TranslateMessage} and
 * {@code DispatchMessageW} ({@link WinGlassNativeShim#pumpOneMessage}), and nothing after the dispatch, so nothing
 * in it calls {@code PlatformSupport::RunDeferredPreferences}. For a scenario in {@link WinGlassChild}; one per
 * process.
 * <p>
 * The thread creates the toolkit window ({@code gwin_app_create}) and a window of the embedder's own, a message-only
 * {@code STATIC} window, then waits for {@link #releasePump} before it retrieves its first message, so that its
 * posted-message queue can be filled first. Released, it runs the action handed to {@link #releasePump}, if any, and
 * pumps until {@link #stopAndAwaitEnd} sets its stop flag. Then it ends the toolkit as an embedder's shutdown would:
 * {@code gwin_terminate_loop} destroys the toolkit window, and it destroys its own window. Without a release within
 * {@link #RELEASE_MILLIS} it ends the same way without pumping.
 */
final class WinExternalPumpToolkit {

    /** How long the toolkit thread waits for {@link #releasePump} at most. */
    static final long RELEASE_MILLIS = 30_000;

    /** The text of the embedder's window. */
    private static final String EMBEDDER_WINDOW_TEXT = "embedder";

    private final Thread thread;
    private final CountDownLatch created = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong toolkitTimers = new AtomicLong();
    private volatile Runnable beforePump;
    private volatile boolean stop;
    private volatile boolean pumped;
    private volatile boolean embedderDestroyed;
    private volatile long hwnd;
    private volatile long embedderHwnd;
    private volatile int nativeThreadId;

    private WinExternalPumpToolkit() {
        this.thread = new Thread(this::body, "WinExternalPumpToolkit");
        this.thread.setDaemon(true);
    }

    /**
     * Starts the toolkit thread and waits until it has created its two windows.
     *
     * @throws IllegalStateException if they were not created within 10 s
     */
    static WinExternalPumpToolkit start() {
        WinExternalPumpToolkit toolkit = new WinExternalPumpToolkit();
        toolkit.thread.start();
        if (!WinNativeToolkit.await(toolkit.created, 10_000) || toolkit.hwnd == 0 || toolkit.embedderHwnd == 0) {
            throw new IllegalStateException("no toolkit window (" + WinNativeToolkit.hex(toolkit.hwnd)
                    + ") or no embedder window (" + WinNativeToolkit.hex(toolkit.embedderHwnd) + ") within 10 s");
        }
        return toolkit;
    }

    private void body() {
        nativeThreadId = WinGlassNativeShim.nativeThreadId();
        hwnd = WinGlassNativeShim.toolkitCreate();
        embedderHwnd = WinGlassNativeShim.createMessageOnlyStatic(EMBEDDER_WINDOW_TEXT);
        created.countDown();
        try {
            if (hwnd != 0 && embedderHwnd != 0 && WinNativeToolkit.await(released, RELEASE_MILLIS) && !stop) {
                Runnable action = beforePump;
                if (action != null) {
                    action.run();
                }
                pump();
            }
        } finally {
            WinGlassNativeShim.toolkitTerminateLoop();
            if (embedderHwnd != 0) {
                embedderDestroyed = WinGlassNativeShim.destroyWindow(embedderHwnd);
            }
        }
    }

    private void pump() {
        pumped = true;
        while (!stop) {
            PumpedMessage message = WinGlassNativeShim.pumpOneMessage();
            if (message.returned() <= 0) {
                return;
            }
            messages.incrementAndGet();
            if (message.hwnd() == hwnd && message.message() == WinGlassNativeShim.WM_TIMER) {
                toolkitTimers.incrementAndGet();
            }
        }
    }

    /** The HWND {@code gwin_app_create} answered. */
    long hwnd() {
        return hwnd;
    }

    /** The embedder's message-only window, owned by the toolkit thread, which posts to it fill the queue of. */
    long embedderHwnd() {
        return embedderHwnd;
    }

    /** The toolkit thread. */
    Thread thread() {
        return thread;
    }

    /** {@link #releasePump(Runnable)} with no action. */
    void releasePump() {
        releasePump(null);
    }

    /**
     * Lets the toolkit thread go on: it runs {@code beforePump} (none for {@code null}), then retrieves its first
     * message. Once.
     */
    void releasePump(Runnable beforePump) {
        this.beforePump = beforePump;
        released.countDown();
    }

    /**
     * Sets the stop flag and wakes the toolkit thread with a thread message, for a thread that waits in
     * {@code GetMessageW} on an empty queue; when the queue is still full that post is refused, but then the thread
     * is not waiting: its next retrieval returns and it sees the flag. A thread never released ends without pumping.
     * Whether the toolkit thread ended within {@code millis}, its toolkit window and its own window destroyed.
     */
    boolean stopAndAwaitEnd(long millis) {
        stop = true;
        released.countDown();
        WinGlassNativeShim.wakeThread(nativeThreadId);
        return WinNativeToolkit.join(thread, millis);
    }

    /** Whether the toolkit thread was released and started to pump. */
    boolean pumped() {
        return pumped;
    }

    /** How many messages the pump retrieved. */
    long messages() {
        return messages.get();
    }

    /** How many of them were a {@code WM_TIMER} for the toolkit window. */
    long toolkitTimers() {
        return toolkitTimers.get();
    }

    /** Whether the toolkit thread destroyed the embedder's window at the end. */
    boolean embedderDestroyed() {
        return embedderDestroyed;
    }
}
