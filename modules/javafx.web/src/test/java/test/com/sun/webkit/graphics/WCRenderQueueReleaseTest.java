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

package test.com.sun.webkit.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.javafx.webkit.prism.WCBufferedContextShim;
import com.sun.webkit.graphics.WCRenderQueue;
import com.sun.webkit.graphics.WCRenderQueueShim;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import test.javafx.scene.web.TestBase;

/**
 * When an image queue still holds buffers that nothing can decode any more, because its native queue
 * is gone and no command references it, it releases them, once. The queue here stands in for an
 * image queue: it has a graphics context, and it counts releases instead of handing the buffers, which
 * are not native, to the native release.
 */
public class WCRenderQueueReleaseTest extends TestBase {

    private static final class CountingQueue extends WCRenderQueue {
        private int releases;

        private CountingQueue() {
            super(WCBufferedContextShim.createBufferedContext(1, 1));
        }

        @Override
        protected void flush() {
        }

        @Override
        protected void disposeGraphics() {
        }

        @Override
        protected void releaseUndecodableBuffers() {
            releases++;
        }
    }

    private static CountingQueue withPendingBuffer() {
        CountingQueue queue = new CountingQueue();
        queue.addBuffer(ByteBuffer.allocate(16));
        return queue;
    }

    @Test
    public void aQueueNothingReferencesIsReleasedWhenItsNativeQueueGoes() {
        CountingQueue queue = withPendingBuffer();
        WCRenderQueueShim.disposeGraphics(queue);
        assertEquals(1, queue.releases, "releases when the native queue went");
    }

    @Test
    public void aReferencedQueueIsReleasedOnlyWhenItsLastReferenceGoes() {
        CountingQueue queue = withPendingBuffer();
        queue.ref();
        queue.ref();
        WCRenderQueueShim.disposeGraphics(queue);
        assertEquals(0, queue.releases, "releases while two references remain");
        queue.deref();
        assertEquals(0, queue.releases, "releases while one reference remains");
        queue.deref();
        assertEquals(1, queue.releases, "releases once the last reference went");
    }

    @Test
    public void aQueueWhoseNativeQueueRemainsIsNotReleased() {
        CountingQueue queue = withPendingBuffer();
        queue.ref();
        queue.deref();
        assertEquals(0, queue.releases, "releases while the native queue remains");
    }

    @Test
    public void anEmptyQueueIsNeverReleased() {
        CountingQueue queue = new CountingQueue();
        WCRenderQueueShim.disposeGraphics(queue);
        assertEquals(0, queue.releases, "releases of an empty queue");
    }
}
