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

import com.sun.javafx.font.coretext.CoreTextPureShim;
import com.sun.javafx.geom.Path2D;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code CGPathSink}, the accumulation of {@code pathApplierFunctionFast}, on every OS: the arrays it builds,
 * and what a failure gives once {@code CGPathApply} has returned.
 */
public class CGPathSinkTest {

    /** {@code coretext.c}'s {@code fail:} line, byte for byte: no newline. */
    private static final String C_FAILURE_LINE = "OS_NATIVE error: pathApplierFunctionFast allocations failed";

    @Test
    void elementsBecomeTypesAndFloatCoordinatesWithTheirRawBits() {
        CoreTextPureShim.PathSink sink = new CoreTextPureShim.PathSink();
        sink.moveTo(-0.0, 1e300);
        sink.lineTo(Double.NaN, 0.1);
        sink.quadTo(1, 2, 3, Double.MIN_VALUE);
        sink.curveTo(5, 6, 7, 8, 9, -1e-300);
        sink.closePath();
        Path2D path = sink.toPath2D();
        assertEquals(0, path.getWindingRule());
        assertEquals(5, path.getNumCommands());
        assertArrayEquals(new byte[] {0, 1, 2, 3, 4}, path.getCommandsNoClone());
        float[] expected = {-0.0f, (float) 1e300, Float.NaN, 0.1f, 1, 2, 3, (float) Double.MIN_VALUE, 5, 6, 7, 8, 9,
            (float) -1e-300};
        assertArrayEquals(rawBits(expected), rawBits(path.getFloatCoordsNoClone()));
    }

    @Test
    void theArraysGrowPastTheirFirstSizeAndComeOutAtTheirExactLengths() {
        CoreTextPureShim.PathSink sink = new CoreTextPureShim.PathSink();
        int curves = 25;
        for (int i = 0; i < curves; i++) {
            sink.curveTo(i, i + 0.5, i + 1, i + 1.5, i + 2, i + 2.5);
        }
        sink.closePath();
        Path2D path = sink.toPath2D();
        assertEquals(curves + 1, path.getNumCommands());
        assertEquals(curves + 1, path.getCommandsNoClone().length);
        assertEquals(curves * 6, path.getFloatCoordsNoClone().length);
        assertEquals(4, path.getCommandsNoClone()[curves]);
        assertEquals(curves - 1 + 2.5f, path.getFloatCoordsNoClone()[curves * 6 - 1]);
    }

    @Test
    void anEmptyPathHasEmptyArrays() {
        Path2D path = new CoreTextPureShim.PathSink().toPath2D();
        assertEquals(0, path.getNumCommands());
        assertEquals(0, path.getCommandsNoClone().length);
        assertEquals(0, path.getFloatCoordsNoClone().length);
    }

    @Test
    void anErrorRecordedDuringTheApplyIsThrownByToPath2DAndPrintsNothing() {
        CoreTextPureShim.PathSink sink = new CoreTextPureShim.PathSink();
        sink.moveTo(1, 2);
        StackOverflowError error = new StackOverflowError("in an element callback");
        assertDoesNotThrow(() -> sink.failed(error));
        sink.lineTo(3, 4);
        String stderr = stderrOf(() -> assertSame(error, assertThrows(StackOverflowError.class, sink::toPath2D)));
        assertEquals("", stderr);
    }

    @Test
    void aRuntimeExceptionGivesNullAndTheCLine() {
        CoreTextPureShim.PathSink sink = new CoreTextPureShim.PathSink();
        sink.moveTo(1, 2);
        assertDoesNotThrow(() -> sink.failed(new IndexOutOfBoundsException("points")));
        sink.lineTo(3, 4);
        assertEquals(C_FAILURE_LINE, stderrOf(() -> assertNull(sink.toPath2D())));
    }

    @Test
    void anUnknownElementGivesNullAndTheCLine() {
        CoreTextPureShim.PathSink sink = new CoreTextPureShim.PathSink();
        sink.moveTo(1, 2);
        sink.unknownElement(5);
        sink.closePath();
        assertEquals(C_FAILURE_LINE, stderrOf(() -> assertNull(sink.toPath2D())));
    }

    @Test
    void onlyTheFirstThrowableDecides() {
        CoreTextPureShim.PathSink exceptionFirst = new CoreTextPureShim.PathSink();
        exceptionFirst.failed(new IllegalStateException());
        exceptionFirst.failed(new OutOfMemoryError());
        assertEquals(C_FAILURE_LINE, stderrOf(() -> assertNull(exceptionFirst.toPath2D())));
        CoreTextPureShim.PathSink errorFirst = new CoreTextPureShim.PathSink();
        OutOfMemoryError error = new OutOfMemoryError();
        errorFirst.failed(error);
        errorFirst.failed(new IllegalStateException());
        assertSame(error, assertThrows(OutOfMemoryError.class, errorFirst::toPath2D));
    }

    private static int[] rawBits(float[] values) {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Float.floatToRawIntBits(values[i]);
        }
        return bits;
    }

    private static String stderrOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
