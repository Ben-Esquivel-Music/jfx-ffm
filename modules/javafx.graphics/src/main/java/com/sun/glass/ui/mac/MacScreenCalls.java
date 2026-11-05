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

package com.sun.glass.ui.mac;

import com.sun.glass.ui.Screen;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SCREENS_QUERY;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SCREEN_VIDEO_REFRESH_PERIOD;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.free;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** {@code MacApplication.staticScreen_getVideoRefreshPeriod} and {@code staticScreen_getScreens}. */
final class MacScreenCalls {

    /** {@code GmacScreenInfo}: the 20 arguments of the {@code Screen} constructor, in order. */
    static final StructLayout GMAC_SCREEN_INFO_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("screen"),
            JAVA_INT.withName("depth"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("platform_x"),
            JAVA_INT.withName("platform_y"),
            JAVA_INT.withName("platform_width"),
            JAVA_INT.withName("platform_height"),
            JAVA_INT.withName("visible_x"),
            JAVA_INT.withName("visible_y"),
            JAVA_INT.withName("visible_width"),
            JAVA_INT.withName("visible_height"),
            JAVA_INT.withName("resolution_x"),
            JAVA_INT.withName("resolution_y"),
            JAVA_FLOAT.withName("platform_scale_x"),
            JAVA_FLOAT.withName("platform_scale_y"),
            JAVA_FLOAT.withName("output_scale_x"),
            JAVA_FLOAT.withName("output_scale_y"),
            MemoryLayout.paddingLayout(4));

    private MacScreenCalls() {
    }

    /** The refresh period, in ms, of MacTimer's display link; 0.0 before it exists. */
    static double videoRefreshPeriod() {
        try {
            return (double) GMAC_SCREEN_VIDEO_REFRESH_PERIOD.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** One {@code Screen} per {@code +[NSScreen screens]}, or null when the library could not allocate them. */
    static Screen[] screens() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outScreens = arena.allocate(ADDRESS);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            int status;
            try {
                status = (int) GMAC_SCREENS_QUERY.invokeExact(outScreens, outCount);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            if (status != GMAC_OK) {
                return null;
            }
            MemorySegment block = outScreens.get(ADDRESS, 0);
            try {
                return toScreens(block, outCount.get(JAVA_INT, 0));
            } finally {
                free(block);
            }
        }
    }

    /** {@code count} {@code GmacScreenInfo} at {@code block}, each passed to the constructor member by member. */
    static Screen[] toScreens(MemorySegment block, int count) {
        long size = GMAC_SCREEN_INFO_LAYOUT.byteSize();
        MemorySegment infos = bounded(block, size * count);
        Screen[] screens = new Screen[count];
        for (int i = 0; i < count; i++) {
            screens[i] = toScreen(infos.asSlice(i * size, size));
        }
        return screens;
    }

    /** One {@code GmacScreenInfo}, passed to the constructor member by member. */
    static Screen toScreen(MemorySegment info) {
        return new Screen(info.get(ADDRESS, offset("screen")).address(),
                intMember(info, "depth"),
                intMember(info, "x"),
                intMember(info, "y"),
                intMember(info, "width"),
                intMember(info, "height"),
                intMember(info, "platform_x"),
                intMember(info, "platform_y"),
                intMember(info, "platform_width"),
                intMember(info, "platform_height"),
                intMember(info, "visible_x"),
                intMember(info, "visible_y"),
                intMember(info, "visible_width"),
                intMember(info, "visible_height"),
                intMember(info, "resolution_x"),
                intMember(info, "resolution_y"),
                floatMember(info, "platform_scale_x"),
                floatMember(info, "platform_scale_y"),
                floatMember(info, "output_scale_x"),
                floatMember(info, "output_scale_y"));
    }

    private static int intMember(MemorySegment info, String name) {
        return info.get(JAVA_INT, offset(name));
    }

    private static float floatMember(MemorySegment info, String name) {
        return info.get(JAVA_FLOAT, offset(name));
    }

    private static long offset(String name) {
        return GMAC_SCREEN_INFO_LAYOUT.byteOffset(PathElement.groupElement(name));
    }
}
