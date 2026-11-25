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

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Delegate onto the {@code MacFontFinder} entry points production calls. The {@code private} methods are named as
 * strings and invoked by reflection, so that {@code MacFontBindings.requireSameBinding} can prove they are the
 * methods {@code MacFontFinder} itself calls. Each helper that takes a CF object at +1 releases it.
 */
public final class MacFontFinderShim {

    private MacFontFinderShim() {
    }

    public static String getFont(int type) {
        return invokeStatic("getFont", new Class<?>[] {int.class}, type);
    }

    public static String familyName(long family) {
        return invokeStatic("familyName", new Class<?>[] {MemorySegment.class}, MemorySegment.ofAddress(family));
    }

    public static String[] getFontData() {
        return invokeStatic("getFontData", new Class<?>[0]);
    }

    public static long matchingDescriptors(long collection) {
        MemorySegment descriptors = invokeStatic("matchingDescriptors", new Class<?>[] {MemorySegment.class},
                                                 MemorySegment.ofAddress(collection));
        return descriptors.address();
    }

    public static String[] fontData(long fonts) {
        return invokeStatic("fontData", new Class<?>[] {MemorySegment.class}, MemorySegment.ofAddress(fonts));
    }

    public static int addUiFont(long font, String[] result, int index) {
        return invokeStatic("addUiFont", new Class<?>[] {MemorySegment.class, String[].class, int.class},
                            MemorySegment.ofAddress(font), result, index);
    }

    public static int addOwnedDescriptor(long descriptor, String[] result, int index) {
        return invokeStatic("addOwnedDescriptor", new Class<?>[] {MemorySegment.class, String[].class, int.class},
                            MemorySegment.ofAddress(descriptor), result, index);
    }

    public static float getSystemFontSize() {
        return MacFontFinder.getSystemFontSize();
    }

    public static float sizeOf(long font) {
        return invokeStatic("sizeOf", new Class<?>[] {MemorySegment.class}, MemorySegment.ofAddress(font));
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

    /** The pooled region a platform caller runs and the cascade-list thread runs for a virtual caller. */
    public static String[] pooledCascadeList(long font) {
        return invokeStatic("pooledCascadeList", new Class<?>[] {MemorySegment.class}, MemorySegment.ofAddress(font));
    }

    /** {@code font} is borrowed. */
    public static String[] cascadeList(long font, long codes) {
        return invokeStatic("cascadeList", new Class<?>[] {MemorySegment.class, MemorySegment.class},
                            MemorySegment.ofAddress(font), MemorySegment.ofAddress(codes));
    }

    /** The platform thread that makes the cascade lists of virtual callers, started if it does not run yet. */
    public static Thread cascadeListWorker() {
        return invokeStatic("startCascadeListWorker", new Class<?>[0]);
    }

    /**
     * Production's hand-off of a task to {@link #cascadeListWorker}, which runs the tasks in their order, with no
     * font to take a reference to. It is looked up once here, so that no reflective lookup runs in the calls it makes.
     */
    public static Consumer<Runnable> cascadeListHandOff() {
        Thread worker = cascadeListWorker();
        Method handOff = queueCascadeListTask();
        return task -> invoke(handOff, worker, task, MemorySegment.NULL);
    }

    /** Production's hand-off of {@code task}, with a reference to {@code font} taken for it. */
    public static void queueCascadeListTask(Runnable task, long font) {
        invoke(queueCascadeListTask(), cascadeListWorker(), task, MemorySegment.ofAddress(font));
    }

    /**
     * A virtual caller's task for the cascade list of {@code font}, whose caller is this thread: once it has run, it
     * wakes this thread's {@link #awaitList}. Making it takes no reference to the font; running it releases one.
     */
    public static Runnable cascadeListTask(MemorySegment font) {
        try {
            Constructor<?> constructor = Class.forName(MacFontFinder.class.getName() + "$CascadeListTask")
                    .getDeclaredConstructor(MemorySegment.class);
            constructor.setAccessible(true);
            return (Runnable) constructor.newInstance(font);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MacFontFinder.CascadeListTask(MemorySegment) no longer exists; re-point"
                    + " MacFontFinderShim at its replacement", e);
        }
    }

    /** The wait of a task of {@link #cascadeListTask}: its list, or what making it threw. */
    public static String[] awaitList(Runnable task) {
        try {
            Method await = task.getClass().getDeclaredMethod("awaitList");
            await.setAccessible(true);
            return invokeOn(await, task);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("MacFontFinder.CascadeListTask.awaitList no longer exists; re-point"
                    + " MacFontFinderShim at its replacement", e);
        }
    }

    private static Method queueCascadeListTask() {
        return declaredMethod("queueCascadeListTask", Thread.class, Runnable.class, MemorySegment.class);
    }

    /** The display name and file {@code getCascadeList} records for one descriptor. */
    public static String[] cascadeEntry(long descriptor) {
        String[] names = new String[2];
        invokeStatic("addCascadeEntry", new Class<?>[] {MemorySegment.class, String[].class, int.class},
                     MemorySegment.ofAddress(descriptor), names, 0);
        return names;
    }

    private static <T> T invokeStatic(String name, Class<?>[] parameterTypes, Object... arguments) {
        return invoke(declaredMethod(name, parameterTypes), arguments);
    }

    private static Method declaredMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = MacFontFinder.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("MacFontFinder." + name + " no longer exists; re-point"
                    + " MacFontFinderShim at its replacement", e);
        }
    }

    private static <T> T invoke(Method method, Object... arguments) {
        return invokeOn(method, null, arguments);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeOn(Method method, Object target, Object... arguments) {
        try {
            return (T) method.invoke(target, arguments);
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
