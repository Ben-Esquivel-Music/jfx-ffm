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

package com.sun.prism.mtl;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/**
 * The off-heap copies of the Java arrays the JNI pinned with {@code GetPrimitiveArrayCritical} for functions that
 * must not be {@code critical(true)} downcalls: the quads of {@code mtl_context_draw_indexed_quads}, the pixels of
 * {@code mtl_texture_update} and the geometry of {@code mtl_mesh_build_geometry_*}. One belongs to each
 * {@code MTLContext}, which closes it after {@code mtl_context_release}.
 * <p>
 * It is confined to the thread of its first call, the Prism render thread: a call from another thread fails with
 * {@link WrongThreadException}. The C keeps no pointer into a copy once its call returns, so one block of
 * {@link #RETAINED_BYTES} is reused by every call that fits. A larger copy gets an arena of its own, closed by
 * {@link #end}, so that the context never holds a texture-sized block between calls. Once {@link #close closed}
 * it fails every call, as a closed arena would, instead of allocating a block that nothing would free.
 */
final class MTLScratch {

    /** Twice the largest quad flush: 4096 quads of 4 vertices, 28 bytes of coordinates and 4 of colour each. */
    static final long RETAINED_BYTES = 1L << 20;

    private static final long ALIGNMENT = 16;

    private Arena retainedArena;
    private MemorySegment retained;
    private Arena callArena;
    private boolean closed;

    /**
     * An allocator for one call's copies, {@code byteSize} bytes in all, valid until {@link #end}. Its allocations
     * are never {@code NULL}, even when empty, as {@code GetPrimitiveArrayCritical} never was for an array.
     *
     * @throws IllegalStateException after {@link #close}
     */
    SegmentAllocator begin(long byteSize) {
        if (closed) {
            throw new IllegalStateException("the Metal scratch of a disposed context was used");
        }
        if (retained == null) {
            retainedArena = Arena.ofConfined();
            retained = retainedArena.allocate(RETAINED_BYTES, ALIGNMENT);
        } else if (!retained.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException("the Metal scratch of a context is confined to the Prism render thread");
        }
        if (byteSize > RETAINED_BYTES) {
            callArena = Arena.ofConfined();
            return callArena;
        }
        return SegmentAllocator.slicingAllocator(retained);
    }

    void end() {
        if (callArena != null) {
            callArena.close();
            callArena = null;
        }
    }

    void close() {
        closed = true;
        end();
        if (retainedArena != null) {
            retainedArena.close();
            retainedArena = null;
            retained = null;
        }
    }
}
