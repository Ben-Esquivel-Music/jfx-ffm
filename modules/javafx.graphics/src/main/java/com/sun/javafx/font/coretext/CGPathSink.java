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

package com.sun.javafx.font.coretext;

import com.sun.javafx.geom.Path2D;
import java.util.Arrays;

/**
 * The elements {@code CGPathApply} delivers, accumulated as {@code coretext.c}'s {@code pathApplierFunctionFast}
 * did: the types and coordinates of the elements, as floats, in arrays grown as the C grew them and cut to
 * their exact lengths.
 * <p>
 * A failure is only recorded while CoreGraphics is still calling back, and decided by {@link #toPath2D()} once
 * {@code CGPathApply} has returned: an {@code Error} propagates from there, never from inside the upcall. Anything
 * else - an element type the C did not know, or a failed element - gives what the C's {@code fail:} path gave: its
 * stderr line and a null path.
 */
final class CGPathSink implements CGNative.PathSink {

    private static final int TYPES_STEP = 10;
    private static final int COORDS_STEP = 50;
    // Byte for byte what coretext.c's fail: path wrote to stderr, without a newline.
    private static final String FAILURE_MESSAGE = "OS_NATIVE error: pathApplierFunctionFast allocations failed";

    private byte[] types = new byte[TYPES_STEP];
    private int numTypes;
    private float[] coords = new float[COORDS_STEP];
    private int numCoords;
    private boolean failed;
    private Throwable failure;

    @Override
    public void moveTo(double x, double y) {
        add(0, x, y);
    }

    @Override
    public void lineTo(double x, double y) {
        add(1, x, y);
    }

    @Override
    public void quadTo(double cx, double cy, double x, double y) {
        add(2, cx, cy, x, y);
    }

    @Override
    public void curveTo(double cx1, double cy1, double cx2, double cy2, double x, double y) {
        add(3, cx1, cy1, cx2, cy2, x, y);
    }

    @Override
    public void closePath() {
        add(4);
    }

    @Override
    public void unknownElement(int type) {
        failed = true;
    }

    @Override
    public void failed(Throwable t) {
        if (failure == null) {
            failure = t;
        }
        failed = true;
    }

    private void add(int type, double... points) {
        if (failed) {
            return;
        }
        if (numTypes == types.length) {
            types = Arrays.copyOf(types, types.length + TYPES_STEP);
        }
        types[numTypes++] = (byte) type;
        if (numCoords + points.length > coords.length) {
            coords = Arrays.copyOf(coords, coords.length + COORDS_STEP);
        }
        for (double point : points) {
            coords[numCoords++] = (float) point;
        }
    }

    /** Winding rule 0 over arrays of exactly the element and coordinate counts, or null after a failure. */
    Path2D toPath2D() {
        if (failure instanceof Error e) {
            throw e;
        }
        if (failed) {
            System.err.print(FAILURE_MESSAGE);
            System.err.flush();
            return null;
        }
        return new Path2D(0, Arrays.copyOf(types, numTypes), numTypes, Arrays.copyOf(coords, numCoords), numCoords);
    }
}
