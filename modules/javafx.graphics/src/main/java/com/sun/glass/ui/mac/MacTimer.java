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
package com.sun.glass.ui.mac;

import com.sun.glass.ui.Timer;

/**
 * MacOSX platform implementation class for Timer.
 */
final class MacTimer extends Timer {

    // Quantum creates its timer before it reads Screen.getVideoRefreshPeriod, which is 0 until init made the link.
    static {
        minPeriod = MacTimerCalls.minPeriod();
        maxPeriod = MacTimerCalls.maxPeriod();
        MacTimerCalls.init();
    }

    private static final int minPeriod, maxPeriod;

    protected MacTimer(Runnable runnable) {
        super(runnable);
    }

    static int getMinPeriod_impl() {
        return minPeriod;
    }

    static int getMaxPeriod_impl() {
        return maxPeriod;
    }

    @Override
    protected long _start(Runnable runnable) {
        return MacTimerCalls.startVsync(runnable);
    }

    @Override
    protected long _start(Runnable runnable, int period) {
        return MacTimerCalls.startPeriod(runnable, period);
    }

    @Override
    protected void _stop(long timer) {
        MacTimerCalls.stop(timer);
    }

    @Override
    protected void _pause(long timer) {
        MacTimerCalls.pause(timer);
    }

    @Override
    protected void _resume(long timer) {
        MacTimerCalls.resume(timer);
    }
}
