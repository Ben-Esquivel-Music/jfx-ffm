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

import com.sun.javafx.font.Glyph;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reads {@link CTGlyph}'s process-wide state and calls its private methods. No native call of its own. */
public final class CTGlyphShim {

    private CTGlyphShim() {
    }

    /** The bitmap context the first small glyph drawn in this JVM created, or 0 before any was drawn. */
    public static long cachedContext() throws ReflectiveOperationException {
        Field field = CTGlyph.class.getDeclaredField("cachedContextRef");
        field.setAccessible(true);
        return field.getLong(null);
    }

    /** The glyph's private {@code createContext}; a context it answers is the caller's to release. */
    public static long createContext(Glyph glyph, boolean lcd, int width, int height)
            throws ReflectiveOperationException {
        return invoke(glyph, "createContext", new Class<?>[] {boolean.class, int.class, int.class},
                      lcd, width, height);
    }

    /** The glyph's private {@code getCachedContext}; the context it answers belongs to the cache. */
    public static long getCachedContext(Glyph glyph, boolean lcd) throws ReflectiveOperationException {
        return invoke(glyph, "getCachedContext", new Class<?>[] {boolean.class}, lcd);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Glyph glyph, String name, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        Method method = CTGlyph.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return (T) method.invoke(glyph, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
