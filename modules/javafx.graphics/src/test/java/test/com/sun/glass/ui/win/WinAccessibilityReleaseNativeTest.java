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
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two release paths of the UI Automation exports: {@code gwin_a11y_text_range_destroy} with a {@code NULL} range
 * returns without touching it, and {@code gwin_a11y_raise_property_changed} frees the {@code BSTR} of every
 * {@code VT_BSTR} value it built, also when the second value fails to convert after the first was built.
 * <p>
 * The {@code BSTR} balance is read from the shim-only counters {@code BSTR_ALLOC} and {@code BSTR_FREE}
 * ({@code gwin_test_counter}), which count only between {@code gwin_test_observe(1)} and {@code (0)}. The
 * {@code NULL} destroy runs in a child JVM: a build that dereferences the argument takes that process down, not
 * this one.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinAccessibilityReleaseNativeTest {

    /** {@code VT_BSTR}. */
    private static final short VT_BSTR = 8;

    /** {@code VT_UNKNOWN}: with a {@code NULL} {@code punkVal} it does not convert. */
    private static final short VT_UNKNOWN = 13;

    /** {@code UIA_NamePropertyId}. */
    private static final int UIA_NAME_PROPERTY_ID = 30005;

    /** The id of a {@code GlassAccessible} no Java peer stands behind. */
    private static final long UNKNOWN_ID = 0x7FFF_0000_0002L;

    private static final int RAISES = 1000;

    private static final long E_FAIL = 0x8000_4005L;

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    @Test
    @Timeout(120)
    public void destroyingANullTextRangeReturns() {
        WinGlassChildJvm.Run run = WinGlassChildJvm.run(WinAccessibilityReleaseNativeTest.class,
                "destroyNullTextRange");
        assertEquals("2", run.value("destroyNull.returned"), run.describe());
    }

    /** Runs in {@link WinGlassChild}. */
    static void destroyNullTextRange(Map<String, String> out) {
        WinGlassNativeShim.destroyTextRange(0L);
        out.put("destroyNull.returned", "1");
        WinGlassNativeShim.destroyTextRange(0L);
        out.put("destroyNull.returned", "2");
    }

    /**
     * {@value #RAISES} raises with two {@code VT_BSTR} values build two {@code BSTR}s each, and one more raise
     * whose second value does not convert builds one: every one of them is freed. A build that clears neither
     * {@code VARIANT} answers {@code BSTR_FREE} 0.
     */
    @Test
    public void everyBstrAPropertyChangeBuildsIsFreed() {
        long accessible = WinGlassNativeShim.createAccessible(UNKNOWN_ID);
        assertNotEquals(0L, accessible);
        try {
            Object was = WinGlassNativeShim.variant(VT_BSTR, 0, "old", false, 0, null, 0);
            Object now = WinGlassNativeShim.variant(VT_BSTR, 0, "new", false, 0, null, 0);
            Object unconvertible = WinGlassNativeShim.variant(VT_UNKNOWN, 0, null, false, 0, null, 0);
            WinGlassNativeShim.observe(true);
            try {
                for (int i = 0; i < RAISES; i++) {
                    WinGlassNativeShim.raiseAutomationPropertyChangedEvent(accessible, UIA_NAME_PROPERTY_ID, was, now);
                }
                long failed = WinGlassNativeShim.raiseAutomationPropertyChangedEvent(accessible,
                        UIA_NAME_PROPERTY_ID, was, unconvertible);
                assertEquals(E_FAIL, failed & 0xFFFF_FFFFL, "a VT_UNKNOWN with no pointer does not convert");
            } finally {
                WinGlassNativeShim.observe(false);
            }
            long built = WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_BSTR_ALLOC);
            long freed = WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_BSTR_FREE);
            assertEquals(2L * RAISES + 1, built, "BSTRs built: two per raise, one for the raise whose second fails");
            assertEquals(built, freed, "BSTRs freed of " + built + " built");
        } finally {
            WinGlassNativeShim.destroyAccessible(accessible);
        }
    }

    /** The counters count only while observing, and an index the library does not know answers -1. */
    @Test
    public void theCountersCountOnlyWhileObserving() {
        long accessible = WinGlassNativeShim.createAccessible(UNKNOWN_ID);
        try {
            Object was = WinGlassNativeShim.variant(VT_BSTR, 0, "old", false, 0, null, 0);
            WinGlassNativeShim.observe(true);
            WinGlassNativeShim.observe(false);
            WinGlassNativeShim.raiseAutomationPropertyChangedEvent(accessible, UIA_NAME_PROPERTY_ID, was, was);
            assertEquals(0L, WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_BSTR_ALLOC));
            assertEquals(-1L, WinGlassNativeShim.counter(-1));
            assertEquals(-1L, WinGlassNativeShim.counter(WinGlassNativeShim.COUNTER_COUNT));
            assertTrue(WinGlassNativeShim.missingToolkitHooks().isEmpty(), "this build lacks "
                    + WinGlassNativeShim.missingToolkitHooks());
        } finally {
            WinGlassNativeShim.destroyAccessible(accessible);
        }
    }
}
