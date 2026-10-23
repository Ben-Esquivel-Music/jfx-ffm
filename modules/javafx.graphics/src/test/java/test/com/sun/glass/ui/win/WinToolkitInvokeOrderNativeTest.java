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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The order of the two scheduling exports against each other: while the toolkit thread is busy in a runnable,
 * thread A calls {@code gwin_invoke_later(L)} and, once that has returned, thread B calls
 * {@code gwin_invoke_and_wait(W)}. W runs before L, because a message sent to the toolkit window is handled ahead
 * of the posted ones the next time the toolkit thread retrieves a message.
 * <p>
 * The busy runnable returns only after A's call has returned and a message sent by another thread is pending in
 * the toolkit thread's queue ({@code GetQueueStatus(QS_SENDMESSAGE)}, polled inside the runnable): that is the
 * moment B's call has queued W, whatever mechanism queues it. No shim-only hook is used, so the test runs on a
 * {@code glass.dll} built without them. A cross-thread {@code gwin_invoke_and_wait} that posted W and waited would
 * set no such flag, so the poll would time out, and W would run after L.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinToolkitInvokeOrderNativeTest {

    /** How long a scenario waits for any one step. */
    private static final long WAIT_MILLIS = 10_000;

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    @Test
    @Timeout(120)
    public void anInvokeAndWaitFromAnotherThreadRunsBeforeAnEarlierInvokeLater() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinToolkitInvokeOrderNativeTest.class, "invokeOrder");
        System.out.println("invoke order: " + run.values() + " (glass.dll sha256 " + WinGlassChildJvm.glassSha256()
                + ")");
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
}
