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

import com.sun.javafx.geom.transform.BaseTransform;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The package-private and private entry points the shared-state tests of the font layer need: a composite strike
 * over a composite of the test's own, whose making reaches no factory and no native call, and the factory's private
 * load of an embedded font, which, unlike the public {@code loadEmbeddedFont}, adds no shutdown hook that would bind
 * the process's file closer to a test factory. No {@code java.lang.foreign} and no native call of its own.
 */
public final class FontSharedStateShim {

    private FontSharedStateShim() {
    }

    /** A greyscale composite strike of {@code size} over {@code font}, with the identity transform. */
    public static CompositeStrike newCompositeStrike(CompositeFontResource font, float size) {
        BaseTransform identity = BaseTransform.IDENTITY_TRANSFORM;
        FontStrikeDesc desc = new FontStrikeDesc(size, identity, FontResource.AA_GREYSCALE);
        return new CompositeStrike(font, size, identity, FontResource.AA_GREYSCALE, desc);
    }

    /**
     * The fonts the private {@code loadEmbeddedFont1} of {@code factory} loads from {@code path} under the alias
     * {@code name}, registered, not copied, and only the first face of a collection.
     */
    public static PrismFontFile[] loadEmbeddedFont(PrismFontFactory factory, String name, String path) {
        try {
            Method load = PrismFontFactory.class.getDeclaredMethod("loadEmbeddedFont1", String.class, String.class,
                                                                   boolean.class, boolean.class, boolean.class);
            load.setAccessible(true);
            return (PrismFontFile[]) load.invoke(factory, name, path, true, false, false);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("PrismFontFactory.loadEmbeddedFont1(String, String, boolean, boolean,"
                    + " boolean) no longer exists; re-point FontSharedStateShim at its replacement", e);
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
