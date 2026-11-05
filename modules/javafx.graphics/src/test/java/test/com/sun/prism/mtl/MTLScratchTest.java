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

package test.com.sun.prism.mtl;

import com.sun.prism.mtl.MTLNativeShim;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifetime and confinement of the off-heap copies the Metal facade makes of the arrays the JNI pinned. Pure Java: no
 * library is loaded, so this runs on every platform.
 */
public class MTLScratchTest {

    private final MTLNativeShim.Scratch scratch = new MTLNativeShim.Scratch();

    @AfterEach
    void closeScratch() {
        scratch.close();
    }

    @Test
    public void copiesThatFitShareTheRetainedBlockAndOutliveTheirCall() {
        MemorySegment first = scratch.copiesInCall(new int[] {1, 2, 3})[0];
        scratch.endCall();
        MemorySegment second = scratch.copiesInCall(new int[] {4, 5})[0];
        scratch.endCall();
        assertEquals(first.address(), second.address());
        assertTrue(first.scope().isAlive());
        assertArrayEquals(new int[] {4, 5}, second.toArray(JAVA_INT));
    }

    @Test
    public void aCopyLargerThanTheRetainedBlockLivesOnlyForItsCall() {
        int[] large = new int[(int) (MTLNativeShim.Scratch.retainedBytes() / Integer.BYTES) + 1];
        large[large.length - 1] = 7;
        MemorySegment copy = scratch.copiesInCall(large)[0];
        assertEquals(7, copy.getAtIndex(JAVA_INT, large.length - 1));
        scratch.endCall();
        assertFalse(copy.scope().isAlive());
    }

    @Test
    public void anEmptyCopyIsNeverNull() {
        int[] large = new int[(int) (MTLNativeShim.Scratch.retainedBytes() / Integer.BYTES) + 1];
        MemorySegment[] inLargeCall = scratch.copiesInCall(large, new int[0]);
        assertNotEquals(0L, inLargeCall[1].address());
        scratch.endCall();
        assertNotEquals(0L, scratch.copiesInCall(new int[0])[0].address());
        scratch.endCall();
    }

    @Test
    public void closingFreesTheRetainedBlock() {
        MemorySegment copy = scratch.copiesInCall(new int[] {1})[0];
        scratch.endCall();
        scratch.close();
        assertFalse(copy.scope().isAlive());
    }

    @Test
    public void aCallAfterCloseFailsInsteadOfAllocatingAgain() {
        scratch.copiesInCall(new int[] {1});
        scratch.endCall();
        scratch.close();
        assertThrows(IllegalStateException.class, () -> scratch.copiesInCall(new int[] {2}));
        int[] large = new int[(int) (MTLNativeShim.Scratch.retainedBytes() / Integer.BYTES) + 1];
        assertThrows(IllegalStateException.class, () -> scratch.copiesInCall(large));
        scratch.close();
    }

    @Test
    public void aCallFromAnotherThreadThanTheFirstFails() {
        scratch.copiesInCall(new int[] {1});
        scratch.endCall();
        int[] large = new int[(int) (MTLNativeShim.Scratch.retainedBytes() / Integer.BYTES) + 1];
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            for (int[] values : new int[][] {{2}, large}) {
                CompletableFuture<MemorySegment[]> call = CompletableFuture.supplyAsync(
                        () -> scratch.copiesInCall(values), other);
                ExecutionException failure = assertThrows(ExecutionException.class, call::get);
                assertInstanceOf(WrongThreadException.class, failure.getCause());
            }
        }
    }
}
