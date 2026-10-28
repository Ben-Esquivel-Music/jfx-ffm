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

package com.sun.glass.ui.win;

import java.lang.foreign.MemorySegment;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outBoolean;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outDouble;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outInt;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outIntBlock;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outLong;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outLongBlock;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outRectangle;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outString;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outVariant;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.utf16String;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_ERR_UPCALL;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_OK;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;

/**
 * The 70 upcall targets of the {@link WinGlassNative} table {@code GwinAccessibleCallbacks}, installed by
 * {@link WinAccessibilityCalls#installAccessibilityCallbacks}. No static field and so no initializer: a first
 * upcall on a UI Automation thread initializes this class without running any code.
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code GlassAccessibleJni.cpp}) refer to those
 * files at commit {@code f9d06d85cc}
 * ({@code git show f9d06d85cc:modules/javafx.graphics/src/main/native-glass/win/<file>}).
 */
final class WinAccessibleCallbacks {

    private WinAccessibleCallbacks() {
    }

    /*
     * The 89 targets. Each does the marshalling its slot needs and nothing else - the registry lookup
     * and the call are WinAccessible's resp. WinTextRangeProvider's - and each catches Throwable,
     * because letting anything out of an upcall stub terminates the JVM (measured on JDK 26 and JDK 25:
     * "Unrecoverable uncaught exception encountered. The VM will now exit"). Reporting through
     * reportUpcallFailure is CheckAndClearException (the former GlassAccessibleJni.cpp:90-129): report to
     * Application.reportException on this thread, swallow, carry on - and a reported Throwable becomes
     * GWIN_ERR_UPCALL, which the provider method turns into the E_FAIL the JNI returned. A stub that
     * reports writes no out-parameter: the library pre-zeroes them, which is what CallXxxMethod left
     * behind when it returned with an exception pending. Booleans cross as int32_t 0 / 1 and are tested
     * with != 0, never == 1. Package-private so that WinGlassNativeShim can drive every arm without a
     * table.
     */

    static int onGetPatternProvider(long accessibleId, int patternId, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchGetPatternProvider(accessibleId, patternId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetHostRawElementProvider(long accessibleId, MemorySegment outHwnd) {
        try {
            return outLong(outHwnd, WinAccessible.dispatchGetHostRawElementProvider(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetPropertyValue(long accessibleId, int propertyId, MemorySegment out) {
        try {
            return outVariant(out, WinAccessible.dispatchGetPropertyValue(accessibleId, propertyId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetBoundingRectangle(long accessibleId, MemorySegment out4, MemorySegment outWritten) {
        try {
            return outRectangle(out4, outWritten, WinAccessible.dispatchGetBoundingRectangle(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetFragmentRoot(long accessibleId, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchGetFragmentRoot(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetEmbeddedFragmentRoots(long accessibleId, MemorySegment out, MemorySegment outCount) {
        try {
            return outLongBlock(out, outCount, WinAccessible.dispatchGetEmbeddedFragmentRoots(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetRuntimeId(long accessibleId, MemorySegment out, MemorySegment outCount) {
        try {
            return outIntBlock(out, outCount, WinAccessible.dispatchGetRuntimeId(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onNavigate(long accessibleId, int direction, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchNavigate(accessibleId, direction));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onSetFocus(long accessibleId) {
        try {
            WinAccessible.dispatchSetFocus(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onElementProviderFromPoint(long accessibleId, double x, double y, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchElementProviderFromPoint(accessibleId, x, y));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetFocus(long accessibleId, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchGetFocus(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onAdviseEventAdded(long accessibleId, int eventId, long propertyIds) {
        try {
            WinAccessible.dispatchAdviseEventAdded(accessibleId, eventId, propertyIds);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onAdviseEventRemoved(long accessibleId, int eventId, long propertyIds) {
        try {
            WinAccessible.dispatchAdviseEventRemoved(accessibleId, eventId, propertyIds);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onInvoke(long accessibleId) {
        try {
            WinAccessible.dispatchInvoke(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetSelection(long accessibleId, MemorySegment out, MemorySegment outCount) {
        try {
            return outLongBlock(out, outCount, WinAccessible.dispatchGetSelection(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetCanSelectMultiple(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetCanSelectMultiple(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetIsSelectionRequired(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetIsSelectionRequired(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onSelect(long accessibleId) {
        try {
            WinAccessible.dispatchSelect(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onAddToSelection(long accessibleId) {
        try {
            WinAccessible.dispatchAddToSelection(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRemoveFromSelection(long accessibleId) {
        try {
            WinAccessible.dispatchRemoveFromSelection(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetIsSelected(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetIsSelected(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetSelectionContainer(long accessibleId, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchGetSelectionContainer(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onSetValue(long accessibleId, double value) {
        try {
            WinAccessible.dispatchSetValue(accessibleId, value);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetValue(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetValue(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetIsReadOnly(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetIsReadOnly(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetMaximum(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetMaximum(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetMinimum(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetMinimum(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetLargeChange(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetLargeChange(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetSmallChange(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetSmallChange(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onSetValueString(long accessibleId, MemorySegment text, int length) {
        try {
            WinAccessible.dispatchSetValueString(accessibleId, utf16String(text, length));
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetValueString(long accessibleId, MemorySegment out, MemorySegment outLength) {
        try {
            return outString(out, outLength, WinAccessible.dispatchGetValueString(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetVisibleRanges(long accessibleId, MemorySegment out, MemorySegment outCount) {
        try {
            return outLongBlock(out, outCount, WinAccessible.dispatchGetVisibleRanges(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeFromChild(long accessibleId, long childElement, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchRangeFromChild(accessibleId, childElement));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeFromPoint(long accessibleId, double x, double y, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchRangeFromPoint(accessibleId, x, y));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetDocumentRange(long accessibleId, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchGetDocumentRange(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetSupportedTextSelection(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetSupportedTextSelection(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetColumnCount(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetColumnCount(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetRowCount(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetRowCount(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetItem(long accessibleId, int row, int column, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchGetItem(accessibleId, row, column));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetColumn(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetColumn(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetColumnSpan(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetColumnSpan(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetContainingGrid(long accessibleId, MemorySegment out) {
        try {
            return outLong(out, WinAccessible.dispatchGetContainingGrid(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetRow(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetRow(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetRowSpan(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetRowSpan(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetColumnHeaders(long accessibleId, MemorySegment out, MemorySegment outCount) {
        try {
            return outLongBlock(out, outCount, WinAccessible.dispatchGetColumnHeaders(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetRowHeaders(long accessibleId, MemorySegment out, MemorySegment outCount) {
        try {
            return outLongBlock(out, outCount, WinAccessible.dispatchGetRowHeaders(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetRowOrColumnMajor(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetRowOrColumnMajor(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetColumnHeaderItems(long accessibleId, MemorySegment out, MemorySegment outCount) {
        try {
            return outLongBlock(out, outCount, WinAccessible.dispatchGetColumnHeaderItems(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetRowHeaderItems(long accessibleId, MemorySegment out, MemorySegment outCount) {
        try {
            return outLongBlock(out, outCount, WinAccessible.dispatchGetRowHeaderItems(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onToggle(long accessibleId) {
        try {
            WinAccessible.dispatchToggle(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetToggleState(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetToggleState(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onCollapse(long accessibleId) {
        try {
            WinAccessible.dispatchCollapse(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onExpand(long accessibleId) {
        try {
            WinAccessible.dispatchExpand(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetExpandCollapseState(long accessibleId, MemorySegment out) {
        try {
            return outInt(out, WinAccessible.dispatchGetExpandCollapseState(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetCanMove(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetCanMove(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetCanResize(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetCanResize(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetCanRotate(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetCanRotate(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onMove(long accessibleId, double x, double y) {
        try {
            WinAccessible.dispatchMove(accessibleId, x, y);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onResize(long accessibleId, double width, double height) {
        try {
            WinAccessible.dispatchResize(accessibleId, width, height);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRotate(long accessibleId, double degrees) {
        try {
            WinAccessible.dispatchRotate(accessibleId, degrees);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onScroll(long accessibleId, int horizontalAmount, int verticalAmount) {
        try {
            WinAccessible.dispatchScroll(accessibleId, horizontalAmount, verticalAmount);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onSetScrollPercent(long accessibleId, double horizontalPercent, double verticalPercent) {
        try {
            WinAccessible.dispatchSetScrollPercent(accessibleId, horizontalPercent, verticalPercent);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetHorizontallyScrollable(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetHorizontallyScrollable(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetHorizontalScrollPercent(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetHorizontalScrollPercent(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetHorizontalViewSize(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetHorizontalViewSize(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetVerticallyScrollable(long accessibleId, MemorySegment out) {
        try {
            return outBoolean(out, WinAccessible.dispatchGetVerticallyScrollable(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetVerticalScrollPercent(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetVerticalScrollPercent(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onGetVerticalViewSize(long accessibleId, MemorySegment out) {
        try {
            return outDouble(out, WinAccessible.dispatchGetVerticalViewSize(accessibleId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onScrollIntoView(long accessibleId) {
        try {
            WinAccessible.dispatchScrollIntoView(accessibleId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /**
     * {@code accessible_disposed}: the last COM reference on the {@code GlassAccessible} is gone, so the
     * registry entry may go. It stands exactly where {@code DeleteGlobalRef} stood in
     * {@code ~GlassAccessible}, which is <em>not</em> {@code WinAccessible.dispose()} - UI Automation
     * holds its own references and keeps calling provider methods on a disposed peer, which the
     * {@code isDisposed()} guards in {@code WinAccessible} answer. Can arrive on the COM/RPC thread that
     * dropped that reference, and must not throw.
     */
    static void onAccessibleDisposed(long accessibleId) {
        try {
            WinAccessible.dispatchDisposed(accessibleId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }
}
