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

package test.com.sun.webkit.ffm;

import com.sun.webkit.SharedBuffer;
import com.sun.webkit.SharedBufferShim;
import com.sun.webkit.WebKitNativeShim;
import com.sun.webkit.WkjStubShim;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code wkj_shared_buffer_*} bindings, and in particular what crosses the boundary when
 * {@code offset} is not zero: the C prototypes touch only {@code buffer + offset} for
 * {@code length} bytes ({@code SharedBufferJava.cpp}), so the facade stages exactly that extent
 * and must keep {@code offset} and {@code length} intact on the wire and honour {@code offset}
 * again when it copies the result back into the caller's array.
 */
@Tag("ffm")
public class WebKitSharedBufferTest {

    private static final long PEER = 0x0BADC0DE07L;

    @BeforeAll
    static void loadLibrary() {
        assumeTrue(WkjStubShim.load(), WkjStubShim.loadFailure());
        // Initialize WebKitNative before @BeforeEach clears wkj_init from the call recorder.
        assertEquals(0, WebKitNativeShim.productionHostInitResult());
    }

    @BeforeEach
    void resetRecording() {
        WkjStubShim.reset();
    }

    @AfterEach
    void clearProgrammedReturns() {
        WkjStubShim.clearReturns();
    }

    private static SharedBuffer create() {
        WkjStubShim.setReturnLong("wkj_shared_buffer_create", PEER);
        return SharedBufferShim.createSharedBuffer();
    }

    @Test
    public void appendAtAnInteriorOffsetKeepsOffsetAndLengthOnTheWire() {
        SharedBuffer sb = create();
        byte[] src = new byte[64];
        SharedBufferShim.append(sb, src, 5, 40);

        int call = WkjStubShim.findCall("wkj_shared_buffer_append", 0);
        assertTrue(call >= 0, "wkj_shared_buffer_append was not called");
        assertEquals(4, WkjStubShim.callArgc(call));
        assertEquals(PEER, WkjStubShim.callArgBits(call, 0));
        assertEquals(5L, WkjStubShim.callArgBits(call, 2), "offset must cross unchanged");
        assertEquals(40L, WkjStubShim.callArgBits(call, 3), "length must cross unchanged");
        // The stub's recorder pairs the const uint8_t* with the int32_t that follows it, so it
        // read "offset" bytes from the base pointer at call time. Its record therefore proves
        // that the segment the facade handed over really extends "offset" bytes below the
        // payload the library reads at src + offset.
        assertEquals('a', WkjStubShim.callArgKind(call, 1));
        assertEquals(5, WkjStubShim.callArgBytes(call, 1).length);
    }

    @Test
    public void appendOfZeroBytesAtTheEndOfTheArrayStillCrosses() {
        SharedBuffer sb = create();
        byte[] src = new byte[24];
        SharedBufferShim.append(sb, src, 24, 0);

        int call = WkjStubShim.findCall("wkj_shared_buffer_append", 0);
        assertTrue(call >= 0, "wkj_shared_buffer_append was not called");
        assertEquals(24L, WkjStubShim.callArgBits(call, 2));
        assertEquals(0L, WkjStubShim.callArgBits(call, 3));
    }

    @Test
    public void getSomeDataCopiesBackAtTheOffsetItPassedAndTouchesNothingElse() {
        SharedBuffer sb = create();
        WkjStubShim.setReturnLong("wkj_shared_buffer_size", 100L);
        WkjStubShim.setReturnLong("wkj_shared_buffer_get_some_data", 7L);
        byte[] dst = new byte[32];
        Arrays.fill(dst, (byte) 0x5A);

        assertEquals(7, SharedBufferShim.getSomeData(sb, 3L, dst, 9, 16));

        int call = WkjStubShim.findCall("wkj_shared_buffer_get_some_data", 0);
        assertTrue(call >= 0, "wkj_shared_buffer_get_some_data was not called");
        assertEquals(5, WkjStubShim.callArgc(call));
        assertEquals(PEER, WkjStubShim.callArgBits(call, 0));
        assertEquals(3L, WkjStubShim.callArgBits(call, 1), "position must cross unchanged");
        assertEquals(9L, WkjStubShim.callArgBits(call, 3), "offset must cross unchanged");
        assertEquals(16L, WkjStubShim.callArgBits(call, 4), "length must cross unchanged");
        // The stub never writes into dst's segment, so the copied region arrives as the zeros
        // the facade allocated - reading them out of dst[9..16) while every other index keeps
        // its sentinel is what proves the copy-back honoured the offset in both segments.
        for (int i = 0; i < dst.length; i++) {
            byte expected = (i >= 9 && i < 16) ? 0 : (byte) 0x5A;
            assertEquals(expected, dst[i], "dst[" + i + "]");
        }
    }

    @Test
    public void getSomeDataOfZeroBytesLeavesTheArrayAlone() {
        SharedBuffer sb = create();
        WkjStubShim.setReturnLong("wkj_shared_buffer_size", 100L);
        WkjStubShim.setReturnLong("wkj_shared_buffer_get_some_data", 0L);
        byte[] dst = new byte[8];
        Arrays.fill(dst, (byte) 0x5A);

        assertEquals(0, SharedBufferShim.getSomeData(sb, 100L, dst, 8, 0));

        for (int i = 0; i < dst.length; i++) {
            assertEquals((byte) 0x5A, dst[i], "dst[" + i + "]");
        }
    }
}
