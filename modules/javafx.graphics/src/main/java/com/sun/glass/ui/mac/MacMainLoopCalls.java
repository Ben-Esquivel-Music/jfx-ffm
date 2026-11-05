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

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_CREATE_DELEGATE;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_ENTER_NESTED_LOOP;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_FINISH_TERMINATING;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_INVOKE_AND_WAIT;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_INVOKE_LATER;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_LEAVE_NESTED_LOOP;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_APP_RUN_LOOP;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;

/**
 * The application delegate and its run loop, the nested event loops and the two ways of posting a {@code Runnable}
 * to the AppKit main thread. The runnables cross as ids into a registry; no native code holds a Java reference.
 */
final class MacMainLoopCalls {

    /** The JNI's global reference to a null runnable made its {@code GlassRunnable} skip the call. */
    private static final Runnable NULL_RUNNABLE = () -> {
    };

    /**
     * The runnables posted and not yet run. An entry leaves when its slot runs, or when the library dropped the
     * request. One whose slot never runs, because Java detached the main thread first, stays, as its global
     * reference leaked.
     */
    private static final Map<Long, Runnable> RUNNABLES = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_RUNNABLE_ID = new AtomicLong(1);

    /* FX thread only, as the C's nestedLoopReturnValue was. */
    private static Object nestedLoopReturnValue;

    private MacMainLoopCalls() {
    }

    /**
     * Launcher thread, after {@code MacAppCallbacks.install}: the {@code GlassApplication*} that {@link #runLoop} and
     * {@link #finishTerminating} take, 0 only when it could not be allocated. True passes 1: the C makes a regular
     * application only of a 1, as the JNI compared with {@code JNI_TRUE}.
     */
    static long createDelegate(boolean taskbarApplication, boolean hasLaunchable) {
        MemorySegment app;
        try {
            app = (MemorySegment) GMAC_APP_CREATE_DELEGATE.invokeExact(taskbarApplication ? 1 : 0,
                    hasLaunchable ? 1 : 0);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return app.address();
    }

    /** Launcher thread; under {@code -XstartOnFirstThread} returns only when the application terminated. */
    static void runLoop(long app) {
        try {
            GMAC_APP_RUN_LOOP.invokeExact(MemorySegment.ofAddress(app));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void finishTerminating(long app) {
        try {
            GMAC_APP_FINISH_TERMINATING.invokeExact(MemorySegment.ofAddress(app));
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static Object enterNestedEventLoop() {
        try {
            GMAC_APP_ENTER_NESTED_LOOP.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return takeNestedLoopReturnValue();
    }

    static void leaveNestedEventLoop(Object retValue) {
        keepNestedLoopReturnValue(retValue);
        try {
            GMAC_APP_LEAVE_NESTED_LOOP.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A null value keeps the one an earlier leave gave, as the JNI kept its global reference. */
    static void keepNestedLoopReturnValue(Object value) {
        if (value != null) {
            nestedLoopReturnValue = value;
        }
    }

    static Object takeNestedLoopReturnValue() {
        Object value = nestedLoopReturnValue;
        nestedLoopReturnValue = null;
        return value;
    }

    /** Any thread; returns at once. */
    static void invokeLater(Runnable runnable) {
        post(GMAC_APP_INVOKE_LATER, runnable);
    }

    /** Any thread but FX; returns once the runnable ran on the main thread. */
    static void invokeAndWait(Runnable runnable) {
        post(GMAC_APP_INVOKE_AND_WAIT, runnable);
    }

    /** The {@code run_runnable} slot, on the main thread; the slot reports what the runnable throws. */
    static void runRunnable(long runnableId) {
        Runnable runnable = RUNNABLES.remove(runnableId);
        if (runnable != null) {
            runnable.run();
        }
    }

    /** Files {@code runnable} under a new id, the one {@code run_runnable} is later given. */
    static long register(Runnable runnable) {
        long id = NEXT_RUNNABLE_ID.getAndIncrement();
        RUNNABLES.put(id, runnable != null ? runnable : NULL_RUNNABLE);
        return id;
    }

    static int pendingRunnables() {
        return RUNNABLES.size();
    }

    /**
     * {@code GLASS_ASSERT_MAIN_JAVA_THREAD} answered before the request, as the macro ran before the JNI posted, and
     * threw after it, as the JNI left the exception pending until it returned.
     */
    private static void post(MethodHandle invoke, Runnable runnable) {
        boolean detached = mainThreadDetached();
        long id = register(runnable);
        int status;
        try {
            status = (int) invoke.invokeExact(id);
        } catch (Throwable t) {
            RUNNABLES.remove(id);
            throw unexpected(t);
        }
        if (status != GMAC_OK) {
            RUNNABLES.remove(id);
        }
        if (detached) {
            throw mainThreadDetachedError();
        }
    }
}
