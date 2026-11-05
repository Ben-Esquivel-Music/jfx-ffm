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

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.util.HashMap;
import javafx.scene.paint.Color;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_COLLECT_PREFERENCES;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.bounded;
import static com.sun.glass.ui.mac.MacGlassNative.free;
import static com.sun.glass.ui.mac.MacGlassNative.newStringUtf;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The platform preferences map, built from the {@code GmacPref} entries the library collects, both for
 * {@code MacApplication.getPlatformPreferences} and for the {@code notify_preferences_changed} slot.
 */
final class MacPreferenceCalls {

    static final int GMAC_PREF_COLOR = 0;
    static final int GMAC_PREF_COLOR_ARRAY = 1;
    static final int GMAC_PREF_STRING = 2;
    static final int GMAC_PREF_BOOLEAN = 3;

    /** {@code GmacColor}: the arguments the JNI gave {@code Color.rgb(int, int, int, double)}. */
    static final StructLayout GMAC_COLOR_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("r"),
            JAVA_INT.withName("g"),
            JAVA_INT.withName("b"),
            JAVA_INT.withName("reserved"),
            JAVA_DOUBLE.withName("a"));

    static final StructLayout GMAC_PREF_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("key"),
            JAVA_INT.withName("kind"),
            JAVA_INT.withName("count"),
            GMAC_COLOR_LAYOUT.withName("color"),
            ADDRESS.withName("value"));

    private static final long KEY = prefOffset("key");
    private static final long KIND = prefOffset("kind");
    private static final long COUNT = prefOffset("count");
    private static final long COLOR = prefOffset("color");
    private static final long VALUE = prefOffset("value");

    private static final long RED = colorOffset("r");
    private static final long GREEN = colorOffset("g");
    private static final long BLUE = colorOffset("b");
    private static final long ALPHA = colorOffset("a");

    private MacPreferenceCalls() {
    }

    /** The modifiable map the JNI returned, or null before {@code applicationWillFinishLaunching}. */
    static HashMap<String, Object> platformPreferences(long app) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outEntries = arena.allocate(ADDRESS);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            int status;
            try {
                status = (int) GMAC_APP_COLLECT_PREFERENCES.invokeExact(MemorySegment.ofAddress(app), outEntries,
                        outCount);
            } catch (Throwable t) {
                throw unexpected(t);
            }
            if (status != GMAC_OK) {
                return null;
            }
            MemorySegment entries = outEntries.get(ADDRESS, 0);
            try {
                return toMap(entries, outCount.get(JAVA_INT, 0));
            } finally {
                free(entries);
            }
        }
    }

    /**
     * The entries in their order, which is the JNI's insertion order. An entry whose value cannot be built, a
     * colour {@code Color.rgb} rejects or a colour array holding one, is reported and left out, as the JNI's
     * {@code GLASS_CHECK_EXCEPTION_RETURN} left it out.
     */
    static HashMap<String, Object> toMap(MemorySegment entries, int count) {
        HashMap<String, Object> preferences = new HashMap<>();
        long size = GMAC_PREF_LAYOUT.byteSize();
        MemorySegment all = bounded(entries, size * count);
        for (int i = 0; i < count; i++) {
            try {
                put(preferences, all.asSlice(i * size, size));
            } catch (Throwable t) {
                reportException(t);
            }
        }
        return preferences;
    }

    private static void put(HashMap<String, Object> preferences, MemorySegment entry) {
        String key = newStringUtf(entry.get(ADDRESS, KEY));
        int count = entry.get(JAVA_INT, COUNT);
        switch (entry.get(JAVA_INT, KIND)) {
            case GMAC_PREF_COLOR -> preferences.put(key, color(entry.asSlice(COLOR, GMAC_COLOR_LAYOUT.byteSize())));
            case GMAC_PREF_COLOR_ARRAY -> preferences.put(key, colors(entry.get(ADDRESS, VALUE), count));
            case GMAC_PREF_STRING -> preferences.put(key, newStringUtf(entry.get(ADDRESS, VALUE), count));
            case GMAC_PREF_BOOLEAN -> preferences.put(key, count != 0 ? Boolean.TRUE : Boolean.FALSE);
        }
    }

    private static Color[] colors(MemorySegment array, int count) {
        long size = GMAC_COLOR_LAYOUT.byteSize();
        MemorySegment all = bounded(array, size * count);
        Color[] colors = new Color[count];
        for (int i = 0; i < count; i++) {
            colors[i] = color(all.asSlice(i * size, size));
        }
        return colors;
    }

    private static Color color(MemorySegment color) {
        return Color.rgb(color.get(JAVA_INT, RED), color.get(JAVA_INT, GREEN), color.get(JAVA_INT, BLUE),
                color.get(JAVA_DOUBLE, ALPHA));
    }

    private static long prefOffset(String name) {
        return GMAC_PREF_LAYOUT.byteOffset(PathElement.groupElement(name));
    }

    private static long colorOffset(String name) {
        return GMAC_COLOR_LAYOUT.byteOffset(PathElement.groupElement(name));
    }
}
