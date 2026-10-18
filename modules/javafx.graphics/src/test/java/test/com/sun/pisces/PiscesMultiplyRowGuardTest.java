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

package test.com.sun.pisces;

import com.sun.pisces.PiscesPaintShim;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The size guard of the multiply texture paint over a gradient ({@code genTexturePaintMultiply}, the Java port of
 * {@code native-prism-sw/PiscesPaint.c:1168} at {@code 42a5072596}): it multiplies only rows of
 * {@code w < INT_MAX / height / sizeof(jint)} pixels. For a wider row the C printed
 * {@code Invalid dimensions: width: <w>, height: <height>} to its standard error ({@code PiscesPaint.c:1193}),
 * returned normally and left the paint buffer as it was; the Java compositor prints the same line to
 * {@link System#err}.
 * <p>
 * Through the public API that row needs a surface of 2 GiB and more heap besides
 * ({@link PiscesCorpusGoldenTest#aStaleMultiplyPaintOnARowTooWideToMultiplyCompositesItsPaintBufferAsItIs()}), so
 * this test calls the generator through {@link PiscesPaintShim} on a renderer state whose touched columns span
 * the row while its paint buffer stays small. The refused row allocates nothing, so the test runs on every heap.
 */
public class PiscesMultiplyRowGuardTest {

    /** {@code INT_MAX / 1 / sizeof(jint)}: the narrowest row of height 1 that the guard refuses. */
    private static final int REFUSED_WIDTH = Integer.MAX_VALUE / 4;

    @Test
    public void aRowTooWideToMultiplyLeavesThePaintBufferAndPrintsItsDimensionsOnce() {
        int[] paint = {0xFF996633, 0x80402010, 0x00000000, 0x12345678};
        int[] before = paint.clone();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream systemErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            PiscesPaintShim.genTexturePaintMultiplyAfterALinearGradient(paint, 0, REFUSED_WIDTH - 1, 1);
        } finally {
            System.setErr(systemErr);
        }
        assertArrayEquals(before, paint, "the paint buffer is left as it was");
        // the C wrote "\n", println writes line.separator: compare lines
        assertEquals(List.of("Invalid dimensions: width: 536870911, height: 1"),
                err.toString(StandardCharsets.UTF_8).lines().toList(), "what the refused row printed");
    }
}
