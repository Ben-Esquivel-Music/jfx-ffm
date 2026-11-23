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

package test.com.sun.javafx.font.coretext;

import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.MacFontProbeShim;
import com.sun.javafx.font.coretext.ObjCNative;
import com.sun.javafx.font.coretext.ObjCNativeShim;
import java.lang.foreign.FunctionDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ObjCNative} on any macOS: the two pool functions of {@code libobjc}, their descriptors, nested pools, and a
 * pop that releases what was autoreleased into its pool, which no heap measurement needs to show.
 */
@EnabledOnOs(OS.MAC)
public class MacObjCNativeTest {

    /**
     * From the runtime's prototypes, {@code void *objc_autoreleasePoolPush(void)} and
     * {@code void objc_autoreleasePoolPop(void *)}, the pointer bound as a long.
     */
    static final Map<String, FunctionDescriptor> SIGNATURES = signatures();

    private static Map<String, FunctionDescriptor> signatures() {
        Map<String, FunctionDescriptor> signatures = new LinkedHashMap<>();
        signatures.put("objc_autoreleasePoolPush", FunctionDescriptor.of(JAVA_LONG));
        signatures.put("objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(JAVA_LONG));
        return signatures;
    }

    @Test
    void bothPoolFunctionsAreBoundFromLibobjcWithTheirDescriptors() {
        ObjCNative.link();
        assertEquals("/usr/lib/libobjc.A.dylib", ObjCNativeShim.libobjc());
        assertEquals(new ArrayList<>(SIGNATURES.entrySet()),
                     new ArrayList<>(ObjCNativeShim.boundFunctions().entrySet()));
    }

    @Test
    void nestedPoolsPopInLastInFirstOutOrder() {
        long outer = ObjCNative.objc_autoreleasePoolPush();
        try {
            long inner = ObjCNative.objc_autoreleasePoolPush();
            try {
                assertNotEquals(0L, outer, "the outer pool's token");
                assertNotEquals(0L, inner, "the inner pool's token");
                assertNotEquals(outer, inner, "each pool has its own token");
            } finally {
                popPushed(inner);
            }
        } finally {
            popPushed(outer);
        }
    }

    /**
     * The test's own platform thread pops its pool on the spot, so the watched object's extra reference is released by
     * the pop and not by the thread's exit, which drains a pool that is never popped.
     */
    @Test
    void aPoppedPoolReleasesWhatWasAutoreleasedIntoIt() {
        assertFalse(Thread.currentThread().isVirtual(), "the test runs on a virtual thread, which may change carriers");
        long watched = MacFontProbeShim.mutableData();
        try {
            long before = MacFontOracleShim.retainCount(watched);
            long inside;
            long pool = ObjCNative.objc_autoreleasePoolPush();
            try {
                MacFontProbeShim.retain(watched);
                MacFontProbeShim.autorelease(watched);
                inside = MacFontOracleShim.retainCount(watched);
            } finally {
                popPushed(pool);
            }
            long after = MacFontOracleShim.retainCount(watched);
            assertEquals(before + 1, inside, "an autoreleased reference is held until its pool is popped");
            assertEquals(before, after, "popping the pool releases the reference autoreleased into it");
        } finally {
            MacFontOracleShim.release(watched);
        }
    }

    /** A token of 0 stands for no pool, and libobjc would read through it on a pop. */
    private static void popPushed(long pool) {
        if (pool != 0) {
            ObjCNative.objc_autoreleasePoolPop(pool);
        }
    }

    @Test
    void aMissingSymbolIsNamed() {
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class,
                () -> ObjCNativeShim.find("objc_noSuchFunction"));
        assertEquals("/usr/lib/libobjc.A.dylib does not export objc_noSuchFunction", error.getMessage());
    }
}
