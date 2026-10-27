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
import com.sun.glass.ui.win.WinGlassNativeShim.ImeComposition;
import com.sun.glass.ui.win.WinGlassNativeShim.ImeEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code std::bad_alloc} during an IME composition leaves the composition through a failure branch, at every
 * allocation {@code GlassInputTextInfo} makes, and no C++ exception leaves the window procedure for
 * {@code user32}.
 * <p>
 * {@code gwin_test_ime_composition} sends {@code WM_IME_COMPOSITION} to a window of its own on this thread, whose
 * procedure hands it to the production {@code ViewContainer::HandleViewInputMethodEvent}, while a canned composition
 * stands in for the input context: result "XY" with one clause, composition "abc" with clauses {0, 1, 3},
 * attributes {1, 0, 0}, cursor 1. With {@code fault_at} n the n-th allocation throws {@code std::bad_alloc}. It
 * answers 1 when an exception came back out of the IME handling, which is what would have unwound into
 * {@code user32}, and counts the allocation attempts, the injected faults, the {@code bad_alloc} branches of
 * {@code GetClauseInfo} / {@code GetAttributeInfo} and the entries into {@code WmImeComposition}'s catch block.
 * The composition makes {@value #ALLOCATIONS} allocations: 1-6 in {@code GetContextData} (the result object, its two
 * buffers, the three composition buffers), 7-10 in {@code GetAttributeInfo}, 11-13 in {@code GetClauseInfo}.
 */
@EnabledOnOs(OS.WINDOWS)
public class WinImeCompositionFaultNativeTest {

    /** The view id the composition is reported for. */
    private static final long VIEW_ID = 77;

    /**
     * {@code GCS_COMPSTR | GCS_COMPATTR | GCS_COMPCLAUSE | GCS_CURSORPOS | GCS_RESULTSTR | GCS_RESULTCLAUSE}.
     */
    private static final long LPARAM = 0x18B8;

    /** The allocations the canned composition makes when none fails. */
    private static final int ALLOCATIONS = 13;

    /** The allocations of {@code GetClauseInfo}, the last three. */
    private static final int CLAUSE_ALLOCATIONS = 3;

    private static final String CLAUSES = "[0, 2, 3, 5]";
    private static final String ATTRIBUTE_BOUNDARIES = "[0, 2, 3, 5]";
    private static final String ATTRIBUTE_VALUES = "[2, 1, 0]";

    @BeforeAll
    static void requireNatives() {
        WinGlassNatives.require();
    }

    /** No fault: the whole composition is reported once, "XY" committed, "abc" composed. */
    @Test
    public void aCompositionWithoutAFaultIsReportedWhole() {
        ImeComposition composition = compose(0);
        assertEquals(0, composition.returned(), composition.toString());
        assertEquals(List.of(ALLOCATIONS, 0, 0, 0), composition.counts(), composition.toString());
        assertEquals(List.of(event(CLAUSES, 3, ATTRIBUTE_BOUNDARIES, ATTRIBUTE_VALUES, 3)), composition.events());
    }

    /** A fault past the last allocation injects nothing. */
    @Test
    public void aFaultPastTheLastAllocationInjectsNothing() {
        ImeComposition composition = compose(ALLOCATIONS + 1);
        assertEquals(0, composition.returned(), composition.toString());
        assertEquals(List.of(ALLOCATIONS, 0, 0, 0), composition.counts(), composition.toString());
        assertEquals(1, composition.events().size(), composition.toString());
    }

    /**
     * A fault in {@code GetContextData} leaves through {@code WmImeComposition}'s catch block: nothing is
     * reported, no allocation follows the failed one, and no exception leaves the window procedure.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    public void aFaultWhileReadingTheContextLeavesThroughTheCatchBlock(int faultAt) {
        ImeComposition composition = compose(faultAt);
        assertEquals(0, composition.returned(), "an exception left the IME handling: " + composition);
        assertEquals(List.of(faultAt, 1, 0, 1), composition.counts(), composition.toString());
        assertEquals(List.of(), composition.events(), composition.toString());
    }

    /**
     * A fault in {@code GetAttributeInfo} is its {@code bad_alloc} branch: the composition is reported without
     * attributes, and the allocations of {@code GetClauseInfo} still follow.
     */
    @ParameterizedTest
    @ValueSource(ints = {7, 8, 9, 10})
    public void aFaultInTheAttributesReportsTheCompositionWithoutThem(int faultAt) {
        ImeComposition composition = compose(faultAt);
        assertEquals(0, composition.returned(), "an exception left the IME handling: " + composition);
        assertEquals(List.of(faultAt + CLAUSE_ALLOCATIONS, 1, 1, 0), composition.counts(), composition.toString());
        assertEquals(List.of(event(CLAUSES, 3, "null", "null", 0)), composition.events());
    }

    /**
     * A fault in {@code GetClauseInfo} is its {@code bad_alloc} branch: the composition is reported without
     * clauses, except for a fault in the nested call for the result string's clauses (allocation 12), after which
     * the composition string's clauses are reported on their own.
     */
    @ParameterizedTest
    @ValueSource(ints = {11, 12, 13})
    public void aFaultInTheClausesReportsTheCompositionWithoutThem(int faultAt) {
        ImeComposition composition = compose(faultAt);
        assertEquals(0, composition.returned(), "an exception left the IME handling: " + composition);
        assertEquals(List.of(faultAt == 11 ? 11 : ALLOCATIONS, 1, 1, 0), composition.counts(),
                composition.toString());
        ImeEvent expected = faultAt == 12
                ? event("[2, 3, 5]", 2, ATTRIBUTE_BOUNDARIES, ATTRIBUTE_VALUES, 3)
                : event("null", 0, ATTRIBUTE_BOUNDARIES, ATTRIBUTE_VALUES, 3);
        assertEquals(List.of(expected), composition.events());
    }

    private static ImeComposition compose(int faultAt) {
        return WinGlassNativeShim.imeComposition(0L, VIEW_ID, LPARAM, faultAt);
    }

    private static ImeEvent event(String clauses, int clauseCount, String attributeBoundaries,
                                  String attributeValues, int attributeCount) {
        return new ImeEvent(VIEW_ID, "XYabc", 5, clauses, clauseCount, attributeBoundaries, attributeValues,
                attributeCount, 2, 1, 1);
    }
}
