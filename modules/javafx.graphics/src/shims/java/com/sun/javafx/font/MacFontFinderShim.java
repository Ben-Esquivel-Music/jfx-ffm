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

package com.sun.javafx.font;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Delegate onto the {@code MacFontFinder} entry points production calls. The two {@code private} methods are named as
 * strings and invoked by reflection, so that {@code MacFontBindings.requireSameBinding} can prove they are the
 * methods {@code MacFontFinder} itself calls.
 */
public final class MacFontFinderShim {

    private MacFontFinderShim() {
    }

    public static String getFont(int type) {
        return invokeStatic("getFont", new Class<?>[] {int.class}, type);
    }

    public static String[] getFontData() {
        return invokeStatic("getFontData", new Class<?>[0]);
    }

    public static float getSystemFontSize() {
        return MacFontFinder.getSystemFontSize();
    }

    public static String getSystemFont() {
        return MacFontFinder.getSystemFont();
    }

    /** No production caller: its only call site is commented out. */
    public static String getMonospacedFont() {
        return MacFontFinder.getMonospacedFont();
    }

    public static boolean populateFontFileNameMap(HashMap<String, String> fontToFileMap,
                                                  HashMap<String, String> fontToFamilyNameMap,
                                                  HashMap<String, ArrayList<String>> familyToFontListMap,
                                                  Locale locale) {
        return MacFontFinder.populateFontFileNameMap(fontToFileMap, fontToFamilyNameMap, familyToFontListMap,
                                                     locale);
    }

    public static String[] getCascadeList(long fontRef) {
        return MacFontFinder.getCascadeList(fontRef);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeStatic(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = MacFontFinder.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(null, arguments);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("MacFontFinder." + name + " no longer exists; re-point"
                    + " MacFontFinderShim at its replacement", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
