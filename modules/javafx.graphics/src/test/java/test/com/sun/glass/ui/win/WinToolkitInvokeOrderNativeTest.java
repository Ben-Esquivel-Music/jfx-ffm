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
import com.sun.glass.ui.win.WinGlassNativeShim.SentMessage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static test.com.sun.glass.ui.win.WinNativeToolkit.hex;

/**
 * A {@code gwin_invoke_and_wait} from a thread other than the toolkit thread, as {@code SendMessage} behaved:
 * <ul>
 * <li>Its order against {@code gwin_invoke_later}: while the toolkit thread is busy in a runnable, thread A calls
 * {@code gwin_invoke_later(L)} and, once that has returned, thread B calls {@code gwin_invoke_and_wait(W)}. W runs
 * before L, because a message sent to the toolkit window is handled ahead of the posted ones the next time the
 * toolkit thread retrieves a message. The busy runnable returns only after A's call has returned and a message sent
 * by another thread is pending in the toolkit thread's queue ({@code GetQueueStatus(QS_SENDMESSAGE)}, polled inside
 * the runnable): that is the moment B's call has queued W, whatever mechanism queues it. A cross-thread
 * {@code gwin_invoke_and_wait} that posted W and waited would set no such flag, so the poll would time out, and W
 * would run after L.</li>
 * <li>Its wait: the caller goes on handling the messages other threads send to it while it waits. The caller owns a
 * window, and the runnable sends that window a message from the toolkit thread
 * ({@code SendMessageTimeoutW(WM_GETTEXTLENGTH)}), which only the waiting caller can answer. A wait that handled no
 * sent message would deadlock that send; the timeout turns it into a recorded failure.</li>
 * </ul>
 * No shim-only hook is used, so the tests run on a {@code glass.dll} built without them.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinToolkitInvokeOrderNativeTest {

    /** How long a scenario waits for any one step. */
    private static final long WAIT_MILLIS = 10_000;

    /** How long the toolkit thread's send to the waiting caller may take before it counts as not handled. */
    private static final int SEND_TIMEOUT_MILLIS = 5_000;

    /** {@code WM_GETTEXTLENGTH} of {@code winuser.h}. */
    private static final int WM_GETTEXTLENGTH = 0x000E;

    /** The caller's window text, whose length is what {@code WM_GETTEXTLENGTH} answers. */
    private static final String CALLER_WINDOW_TEXT = "sent to the waiting caller";

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    @Test
    @Timeout(120)
    public void anInvokeAndWaitFromAnotherThreadRunsBeforeAnEarlierInvokeLater() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitInvokeOrderNativeTest.class, "invokeOrder");
        System.out.println("invoke order: " + run.values() + " (" + run.loadedGlass() + ")");
        assertEquals("0", run.value("a.status"), run.describe());
        assertEquals("true", run.value("busy.sawAReturn"), run.describe());
        assertEquals("true", run.value("busy.sawPendingSend"),
                "no sent message was pending while B was in gwin_invoke_and_wait: " + run.describe());
        assertEquals("true", run.value("b.returned"), run.describe());
        assertEquals("0", run.value("b.status"), run.describe());
        assertEquals("busy,W,L", run.value("order"), run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void invokeOrder(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch aReturned = new CountDownLatch(1);
        out.put("busy.status", Integer.toString(toolkit.invokeLater(() -> {
            busy.countDown();
            out.put("busy.sawAReturn", Boolean.toString(WinNativeToolkit.await(aReturned, WAIT_MILLIS)));
            out.put("busy.sawPendingSend", Boolean.toString(
                    WinGlassChild.waitFor(WAIT_MILLIS, WinGlassNativeShim::sentMessagePending)));
            order.add("busy");
        })));
        out.put("busy.started", Boolean.toString(WinNativeToolkit.await(busy, WAIT_MILLIS)));

        Thread a = WinNativeToolkit.startThread("A", () -> {
            out.put("a.status", Integer.toString(toolkit.invokeLater(() -> order.add("L"))));
            aReturned.countDown();
        });
        out.put("a.returned", Boolean.toString(WinNativeToolkit.join(a, WAIT_MILLIS)));

        Thread b = WinNativeToolkit.startThread("B", () ->
                out.put("b.status", Integer.toString(toolkit.invokeAndWait(() -> order.add("W")))));
        out.put("b.returned", Boolean.toString(WinNativeToolkit.join(b, 3 * WAIT_MILLIS)));
        WinGlassChild.waitFor(WAIT_MILLIS, () -> order.size() >= 3);
        out.put("order", String.join(",", order));

        out.put("terminate.status", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        toolkit.releaseAndAwaitEnd(WAIT_MILLIS);
    }

    @Test
    @Timeout(120)
    public void aCallerWaitingInInvokeAndWaitHandlesAMessageItsRunnableSendsToTheCaller() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitInvokeOrderNativeTest.class, "sendToWaitingCaller");
        System.out.println("send to the waiting caller: " + run.values() + " (" + run.loadedGlass() + ")");
        assertNotEquals("0x0", run.value("caller.hwnd"), "the caller created no window: " + run.describe());
        assertEquals(run.value("caller.threadId"), run.value("caller.hwnd.threadId"),
                "the caller's window belongs to another thread: " + run.describe());
        assertNotEquals(run.value("toolkit.threadId"), run.value("caller.threadId"),
                "the caller is the toolkit thread, whose gwin_invoke_and_wait does not wait: " + run.describe());
        assertEquals("0", run.value("invoke.status"), run.describe());
        assertEquals("true", run.value("action.ran"), "the runnable did not run: " + run.describe());
        assertEquals("true", run.value("action.onToolkitThread"), run.describe());
        assertNotEquals("0", run.value("send.returned"), "SendMessageTimeoutW from the toolkit thread to the window of"
                + " the caller waiting in gwin_invoke_and_wait failed after " + run.value("send.millis")
                + " ms with GetLastError " + run.value("send.lastError") + " (1460 is ERROR_TIMEOUT): the waiting"
                + " caller handled no message sent to it: " + run.describe());
        assertEquals(Integer.toString(CALLER_WINDOW_TEXT.length()), run.value("send.result"),
                "WM_GETTEXTLENGTH did not answer the length of the caller's window text: " + run.describe());
        assertEquals("true", run.value("caller.returned"), run.describe());
        assertEquals("true", run.value("caller.destroyed"), run.describe());
        assertEquals("0", run.value("terminate.status"), run.describe());
        assertEquals("true", run.value("loop.returned"), run.describe());
        assertEquals("true", run.value("toolkit.ended"), run.describe());
    }

    /**
     * Runs in {@link WinGlassChild}. A caller thread creates a message-only {@code STATIC} window, calls
     * {@code gwin_invoke_and_wait} with a runnable that sends {@code WM_GETTEXTLENGTH} to that window from the
     * toolkit thread, and destroys the window once the call has returned.
     */
    static void sendToWaitingCaller(Map<String, String> out) {
        WinNativeToolkit toolkit = WinNativeToolkit.start();
        out.put("toolkit.threadId", Integer.toString(toolkit.nativeThreadId()));
        Thread caller = WinNativeToolkit.startThread("caller", () -> {
            out.put("caller.threadId", Integer.toString(WinGlassNativeShim.nativeThreadId()));
            long hwnd = WinGlassNativeShim.createMessageOnlyStatic(CALLER_WINDOW_TEXT);
            out.put("caller.hwnd", hex(hwnd));
            if (hwnd == 0) {
                return;
            }
            try {
                out.put("caller.hwnd.threadId", Integer.toString(WinGlassNativeShim.windowThreadId(hwnd)));
                AtomicBoolean ran = new AtomicBoolean();
                out.put("invoke.status", Integer.toString(toolkit.invokeAndWait(() -> {
                    out.put("action.onToolkitThread", Boolean.toString(Thread.currentThread() == toolkit.thread()));
                    long start = System.nanoTime();
                    SentMessage sent = WinGlassNativeShim.sendMessageTimeout(hwnd, WM_GETTEXTLENGTH,
                            SEND_TIMEOUT_MILLIS);
                    out.put("send.millis", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
                    out.put("send.returned", Long.toString(sent.returned()));
                    out.put("send.result", Long.toString(sent.result()));
                    out.put("send.lastError", Integer.toString(sent.lastError()));
                    ran.set(true);
                })));
                out.put("action.ran", Boolean.toString(ran.get()));
            } finally {
                out.put("caller.destroyed", Boolean.toString(WinGlassNativeShim.destroyWindow(hwnd)));
            }
        });
        out.put("caller.returned", Boolean.toString(WinNativeToolkit.join(caller, 3 * WAIT_MILLIS)));
        out.put("terminate.status", Integer.toString(toolkit.terminate()));
        out.put("loop.returned", Boolean.toString(toolkit.awaitLoopReturn(WAIT_MILLIS)));
        out.put("toolkit.ended", Boolean.toString(toolkit.releaseAndAwaitEnd(WAIT_MILLIS)));
    }
}
