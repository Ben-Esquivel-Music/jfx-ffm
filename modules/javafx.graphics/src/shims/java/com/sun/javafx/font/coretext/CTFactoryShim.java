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

import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.FontResource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A CoreText factory of a test's own, whose reads of a font file for a fallback face the test counts, holds, fails or
 * changes the file around, and layout's lookups of fallback faces made with it. No native call of its own.
 */
public final class CTFactoryShim {

    /** What the factory does in place of each {@code findFontIndex}; {@code read} is the factory's own. */
    @FunctionalInterface
    public interface FaceRead {
        int read(String name, String file, Callable<Integer> read) throws Exception;
    }

    /** What the factory does in place of each {@code mayFindFace}; {@code answer} is the factory's own. */
    @FunctionalInterface
    public interface FaceQuery {
        boolean mayFindFace(String name, String file, BooleanSupplier answer);
    }

    private CTFactoryShim() {
    }

    /** A factory that reads font files through {@code read} and counts the copies of run fonts handed to it. */
    public static CTFactory newFactory(FaceRead read, AtomicInteger copies) {
        return new TestFactory(read, (name, file, answer) -> answer.getAsBoolean(), copies);
    }

    /** The same, asked whether it may find a face through {@code query}. */
    public static CTFactory newFactory(FaceRead read, FaceQuery query, AtomicInteger copies) {
        return new TestFactory(read, query, copies);
    }

    /** Layout's slot in {@code fr} for a run whose font {@code runFont}, named {@code name}, reports {@code file}. */
    public static int addFallbackFont(CTFactory factory, CompositeFontResource fr, String name, String file,
                                      long runFont) throws Exception {
        return CTGlyphLayout.addFallbackFont(factory, fr, name, file, runFont);
    }

    /** The factory's font file of the face {@code name} in {@code file}, made of {@code fontRef}, +1, consumed. */
    public static FontResource createFontFile(CTFactory factory, String name, String file, long fontRef)
            throws Exception {
        return factory.createFontFile(name, file, fontRef);
    }

    public static boolean mayFindFace(CTFactory factory, String name, String file) {
        return factory.mayFindFace(name, file);
    }

    /** Whether the factory remembers a lookup of the face named {@code name} in {@code file} that found no face. */
    public static boolean remembersAMiss(CTFactory factory, String name, String file)
            throws ReflectiveOperationException {
        for (Object key : map(factory, "misses").keySet()) {
            if (component(key, "name").equals(name.toLowerCase(Locale.ROOT))
                    && Objects.equals(component(key, "file"), file)) {
                return true;
            }
        }
        return false;
    }

    /** The lookups of fallback faces the factory has in flight. */
    public static int lookupsInFlight(CTFactory factory) throws ReflectiveOperationException {
        return map(factory, "lookups").size();
    }

    private static Object component(Object key, String name) throws ReflectiveOperationException {
        Method accessor = key.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(key);
    }

    private static Map<?, ?> map(CTFactory factory, String name) throws ReflectiveOperationException {
        Field field = CTFactory.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(factory);
    }

    private static final class TestFactory extends CTFactory {

        private final FaceRead read;
        private final FaceQuery query;
        private final AtomicInteger copies;

        TestFactory(FaceRead read, FaceQuery query, AtomicInteger copies) {
            this.read = read;
            this.query = query;
            this.copies = copies;
        }

        @Override
        boolean mayFindFace(String name, String file) {
            return query.mayFindFace(name, file, () -> super.mayFindFace(name, file));
        }

        @Override
        public int findFontIndex(String targetName, String filename) throws Exception {
            return read.read(targetName, filename, () -> super.findFontIndex(targetName, filename));
        }

        @Override
        CTFontFile createFontFile(String name, String filename, long fontRef) throws Exception {
            copies.incrementAndGet();
            return super.createFontFile(name, filename, fontRef);
        }
    }
}
