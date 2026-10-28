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
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outDoubleBlock;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outInt;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outLong;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outLongBlock;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outString;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.outVariant;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.readVariant;
import static com.sun.glass.ui.win.WinAccessibilityMarshalling.utf16String;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_ERR_UPCALL;
import static com.sun.glass.ui.win.WinCallSupport.GWIN_OK;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;

/**
 * The 19 upcall targets of the {@link WinGlassNative} table {@code GwinTextRangeCallbacks}, installed by
 * {@link WinAccessibilityCalls#installAccessibilityCallbacks} with the rules {@link WinAccessibleCallbacks}
 * states. No static field and so no initializer.
 */
final class WinTextRangeCallbacks {

    private WinTextRangeCallbacks() {
    }

    static int onRangeClone(long rangeId, MemorySegment out) {
        try {
            return outLong(out, WinTextRangeProvider.dispatchClone(rangeId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeCompare(long rangeId, long otherRangeId, MemorySegment out) {
        try {
            return outBoolean(out, WinTextRangeProvider.dispatchCompare(rangeId, otherRangeId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeCompareEndpoints(long rangeId, int endpoint, long otherRangeId, int targetEndpoint,
            MemorySegment out) {
        try {
            return outInt(out, WinTextRangeProvider.dispatchCompareEndpoints(rangeId, endpoint, otherRangeId,
                    targetEndpoint));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeExpandToEnclosingUnit(long rangeId, int unit) {
        try {
            WinTextRangeProvider.dispatchExpandToEnclosingUnit(rangeId, unit);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeFindAttribute(long rangeId, int attributeId, MemorySegment value, int backward,
            MemorySegment out) {
        try {
            return outLong(out, WinTextRangeProvider.dispatchFindAttribute(rangeId, attributeId, readVariant(value),
                    backward != 0));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeFindText(long rangeId, MemorySegment text, int length, int backward, int ignoreCase,
            MemorySegment out) {
        try {
            return outLong(out, WinTextRangeProvider.dispatchFindText(rangeId, utf16String(text, length),
                    backward != 0, ignoreCase != 0));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeGetAttributeValue(long rangeId, int attributeId, MemorySegment out) {
        try {
            return outVariant(out, WinTextRangeProvider.dispatchGetAttributeValue(rangeId, attributeId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeGetBoundingRectangles(long rangeId, MemorySegment out, MemorySegment outCount) {
        try {
            return outDoubleBlock(out, outCount, WinTextRangeProvider.dispatchGetBoundingRectangles(rangeId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeGetEnclosingElement(long rangeId, MemorySegment out) {
        try {
            return outLong(out, WinTextRangeProvider.dispatchGetEnclosingElement(rangeId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeGetText(long rangeId, int maxLength, MemorySegment out, MemorySegment outLength) {
        try {
            return outString(out, outLength, WinTextRangeProvider.dispatchGetText(rangeId, maxLength));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeMove(long rangeId, int unit, int count, MemorySegment out) {
        try {
            return outInt(out, WinTextRangeProvider.dispatchMove(rangeId, unit, count));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeMoveEndpointByUnit(long rangeId, int endpoint, int unit, int count, MemorySegment out) {
        try {
            return outInt(out, WinTextRangeProvider.dispatchMoveEndpointByUnit(rangeId, endpoint, unit, count));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeMoveEndpointByRange(long rangeId, int endpoint, long otherRangeId, int targetEndpoint) {
        try {
            WinTextRangeProvider.dispatchMoveEndpointByRange(rangeId, endpoint, otherRangeId, targetEndpoint);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeSelect(long rangeId) {
        try {
            WinTextRangeProvider.dispatchSelect(rangeId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeAddToSelection(long rangeId) {
        try {
            WinTextRangeProvider.dispatchAddToSelection(rangeId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeRemoveFromSelection(long rangeId) {
        try {
            WinTextRangeProvider.dispatchRemoveFromSelection(rangeId);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeScrollIntoView(long rangeId, int alignToTop) {
        try {
            WinTextRangeProvider.dispatchScrollIntoView(rangeId, alignToTop != 0);
            return GWIN_OK;
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    static int onRangeGetChildren(long rangeId, MemorySegment out, MemorySegment outCount) {
        try {
            return outLongBlock(out, outCount, WinTextRangeProvider.dispatchGetChildren(rangeId));
        } catch (Throwable t) {
            reportUpcallFailure(t);
            return GWIN_ERR_UPCALL;
        }
    }

    /** {@code range_disposed}: {@link WinAccessibleCallbacks#onAccessibleDisposed}'s rules, for the range registry. */
    static void onRangeDisposed(long rangeId) {
        try {
            WinTextRangeProvider.dispatchDisposed(rangeId);
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }
}
