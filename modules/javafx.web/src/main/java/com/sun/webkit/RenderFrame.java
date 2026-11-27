/*
 * Copyright (c) 2011, 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.webkit;

import com.sun.webkit.graphics.WCRectangle;
import com.sun.webkit.graphics.WCRenderQueue;
import java.util.LinkedList;
import java.util.List;

// Instances of this class may not be accessed and modified concurrently
// by multiple threads
final class RenderFrame {
    private final List<WCRenderQueue> rqList =
            new LinkedList<>();
    int scrollDx, scrollDy;
    // The fill as recorded; postPaint chose from it whether to paint the page under the highlight
    boolean backgroundTransparent;
    private final WCRectangle enclosingRect = new WCRectangle();

    // Called on: Event thread only
    void addRenderQueue(WCRenderQueue rq) {
        if (rq.isEmpty()) {
            return;
        }
        rqList.add(rq);
        WCRectangle rqRect = rq.getClip();
        if (enclosingRect.isEmpty()) {
            enclosingRect.setFrame(rqRect.getX(), rqRect.getY(),
                                   rqRect.getWidth(), rqRect.getHeight());
        } else if (rqRect.isEmpty()) {
            // do nothing
        } else {
            WCRectangle.union(enclosingRect, rqRect, enclosingRect);
        }
    }

    // Called on: Event thread and Main thread
    List<WCRenderQueue> getRQList() {
        return rqList;
    }

    // Called on: Event thread only
    WCRectangle getEnclosingRect() {
        return enclosingRect;
    }

    // Called on: Event thread only
    void drop() {
        for (WCRenderQueue rq : rqList) {
            rq.dispose();
        }
        rqList.clear();
        enclosingRect.setFrame(0, 0, 0, 0);
        scrollDx = 0;
        scrollDy = 0;
    }

    @Override
    public String toString() {
        return "RenderFrame{"
                + "rqList=" + rqList + ", "
                + "enclosingRect=" + enclosingRect
                + "}";
    }
}
