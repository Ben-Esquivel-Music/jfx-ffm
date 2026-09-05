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

package test.com.sun.media.jfxmedia.locator;

import com.sun.media.jfxmedia.locator.ConnectionHolder;
import com.sun.media.jfxmedia.locator.Locator;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for the HLS connection holder that {@code Locator.createConnectionHolder()} returns for an
 * {@code .m3u8} URI. The class itself is package private, so the locator is the way in.
 * <p>
 * Neither test needs a native library, an HTTP server or a shim: the holder is only created and closed,
 * and the loader thread's fetch of the playlist is refused, which is exactly the state a media that never
 * reached its playlist disposes in. The one-shot test reads two private fields, which is why
 * {@code --add-opens javafx.media/com.sun.media.jfxmedia.locator=ALL-UNNAMED} sits next to the export for
 * the same package in {@code modules/javafx.media/src/test/addExports}.
 */
public class HLSConnectionHolderTest {

    /** How long the playlist loader gets to take the {@code STATE_EXIT} the first close queues for it. */
    private static final long LOADER_EXIT_TIMEOUT_MILLIS = 30_000;

    /**
     * {@code GSTMedia.dispose()} closes a media's connection holders on every path, because a pipeline
     * that never left {@code GST_STATE_NULL} never emits {@code close_connection}; where it did emit it,
     * the holder is reached twice. Every other holder closes idempotently and this one has to as well:
     * an unguarded second body releases the live playlist's semaphore again and queues a second
     * {@code STATE_EXIT} into a loader queue nothing drains any more.
     * <p>
     * That queue is what the assertion rests on, because it is the mutation that keeps its evidence: the
     * first close stops the loader, a stopped loader takes nothing more, so a second body's
     * {@code STATE_EXIT} stays in the queue to be counted. The loader thread having ended is therefore
     * the precondition, and is asserted as one - on a live loader an empty queue would only mean it had
     * been quick.
     */
    @Test
    void closingAnHlsConnectionHolderTwiceRunsItsBodyOnce() throws Exception {
        ConnectionHolder holder = holderForAPlaylistThatCannotLoad();
        Thread loader = (Thread) fieldValue(holder, "playlistLoader");
        BlockingQueue<?> loaderStates = (BlockingQueue<?>) fieldValue(loader, "stateQueue");

        holder.closeConnection();

        loader.join(LOADER_EXIT_TIMEOUT_MILLIS);
        assertFalse(loader.isAlive(), "the first close should have queued STATE_EXIT and stopped the loader");
        assertEquals(0, loaderStates.size(), "the stopped loader should have taken every state it was given");

        holder.closeConnection();

        assertEquals(0, loaderStates.size(),
                "the second close ran the body again instead of returning on the closed flag: it queued a "
                        + "second STATE_EXIT, and with the loader stopped nothing will ever take it");
    }

    /**
     * The other half of the same close path. A media holds its connection holder from the moment the
     * locator makes one, so {@code GSTMedia.dispose()} also closes holders that never became ready: an
     * {@code .m3u8} that never loads leaves {@code isReady()} unreached and the current playlist null.
     * Until that dereference was guarded this threw on the <em>first</em> close, which
     * {@code NativeMedia.closeQuietly} logged at WARNING where the JNI build logged nothing, and the
     * throw skipped the rest of the body - including the {@code STATE_EXIT} that stops the loader thread.
     */
    @Test
    void closingAnHlsConnectionHolderThatNeverBecameReadyDoesNotThrow() throws Exception {
        ConnectionHolder holder = holderForAPlaylistThatCannotLoad();

        assertDoesNotThrow(holder::closeConnection,
                "a holder whose playlist never loaded has nothing to close, not something to throw over");
    }

    /**
     * A holder for a loopback URI whose port has just been given up, so the loader thread's fetch of the
     * playlist is refused straight away and no playlist is ever adopted.
     */
    private static ConnectionHolder holderForAPlaylistThatCannotLoad() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }

        return new Locator(new URI("http", null, "127.0.0.1", port, "/nothing.m3u8", null, null))
                .createConnectionHolder();
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
