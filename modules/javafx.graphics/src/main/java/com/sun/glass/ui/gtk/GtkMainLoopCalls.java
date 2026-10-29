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

package com.sun.glass.ui.gtk;

import com.sun.glass.ui.Application;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.gtk.GtkGlassNative.GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GDK;
import static com.sun.glass.ui.gtk.GtkGlassNative.LIB_GTK;
import static com.sun.glass.ui.gtk.GtkGlassNative.bind;
import static com.sun.glass.ui.gtk.GtkGlassNative.reportException;
import static com.sun.glass.ui.gtk.GtkGlassNative.unexpected;
import static com.sun.glass.ui.gtk.GtkGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The GLib sources and main loops of the Linux glass: the pulse timer ({@code GtkTimer._start} and {@code _stop},
 * {@code GlassTimer.cpp} at commit {@code 033187ad90}) and {@code GtkApplication._submitForLaterInvocation},
 * {@code enterNestedEventLoopImpl} and {@code leaveNestedEventLoopImpl} ({@code GlassApplication.cpp}).
 * <p>
 * The C passed a {@code malloc}ed {@code RunnableContext} holding a JNI global reference as the {@code gpointer}
 * of a GLib source. Here the {@code gpointer} is an id into a registry of this class, the source function is an
 * upcall stub allocated once in {@link Arena#global()}, and no native code holds a Java reference. An upcall
 * target never lets a {@code Throwable} out - that would terminate the JVM - and where the C called
 * {@code LOG_EXCEPTION} ({@code check_and_clear_exception} of {@code glass_general.cpp}) it does what that did:
 * hand the exception to {@link Application#reportException} on the current thread and swallow anything the
 * report itself throws ({@link GtkGlassNative#reportException}).
 */
final class GtkMainLoopCalls {

    /** {@code G_PRIORITY_HIGH_IDLE} of {@code gmain.h}. */
    static final int G_PRIORITY_HIGH_IDLE = 100;

    /** {@code gboolean (*GSourceFunc)(gpointer user_data)}. */
    private static final FunctionDescriptor SOURCE_FUNC = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    /**
     * {@code guint gdk_threads_add_timeout_full(gint priority, guint interval, GSourceFunc function,
     * gpointer data, GDestroyNotify notify)}.
     */
    private static final MethodHandle GDK_THREADS_ADD_TIMEOUT_FULL = bind(GDK, LIB_GDK,
            "gdk_threads_add_timeout_full",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** The {@code call_runnable_in_timer} of {@code GlassTimer.cpp}, shared by every timer. */
    private static final MemorySegment TIMER_CALLBACK = upcallStub(GtkMainLoopCalls.class, "onTimerTick", SOURCE_FUNC);

    /**
     * The {@code RunnableContext} of a started timer: {@code runnable} was a JNI global reference,
     * {@code flag} an {@code int} set by {@code _stop}. The fields are volatile where the C's were plain,
     * which changes nothing on the FX thread that starts, stops and ticks every timer in practice.
     */
    private static final class TimerContext {
        volatile Runnable runnable;
        volatile boolean flag;

        TimerContext(Runnable runnable) {
            this.runnable = runnable;
        }
    }

    /**
     * The contexts of the timers whose GLib source still exists, keyed by the id that crosses as the source's
     * {@code gpointer}. An entry leaves exactly where the C {@code free}d the context: in the first tick after
     * {@link #timerStop}. A timer that is never stopped keeps its entry and its runnable for the life of the
     * process, as it kept its global reference.
     */
    private static final Map<Long, TimerContext> TIMERS = new ConcurrentHashMap<>();

    /** Ids start at 1, so a started timer is never 0, which {@code Timer.start} reads as a failure. */
    private static final AtomicLong NEXT_TIMER_ID = new AtomicLong(1);

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkTimer__1start} ({@code GlassTimer.cpp}). The C returned 0 when
     * {@code malloc} failed; the registry cannot fail that way.
     *
     * @return the timer handle {@code Timer} keeps and passes back to {@link #timerStop}
     */
    static long timerStart(Runnable runnable, int period) {
        long id = NEXT_TIMER_ID.getAndIncrement();
        TIMERS.put(id, new TimerContext(runnable));
        try {
            int source = (int) GDK_THREADS_ADD_TIMEOUT_FULL.invokeExact(G_PRIORITY_HIGH_IDLE, period, TIMER_CALLBACK,
                    MemorySegment.ofAddress(id), MemorySegment.NULL);
        } catch (Throwable t) {
            throw unexpected(t);
        }
        return id;
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkTimer__1stop} ({@code GlassTimer.cpp}). The source is not removed here;
     * the next tick sees the flag, forgets the context and removes itself. A handle that is not a started timer
     * returns silently where the C wrote through whatever pointer it was given; {@code Timer.stop} never passes one.
     */
    static void timerStop(long timer) {
        TimerContext context = TIMERS.get(timer);
        if (context == null) {
            return;
        }
        context.flag = true;
        context.runnable = null;
    }

    /**
     * {@code call_runnable_in_timer} ({@code GlassTimer.cpp}), the {@code GSourceFunc} of every timer, on the thread
     * that iterates the default main context (the FX thread). Answering {@code FALSE} removes the source. A
     * {@code Throwable} from the runnable is reported ({@code LOG_EXCEPTION}) and the timer keeps firing. The C
     * attached the thread when {@code GetEnv} said it was detached and detached it afterwards; an upcall stub does
     * the same by itself.
     */
    static int onTimerTick(MemorySegment data) {
        try {
            long id = data.address();
            TimerContext context = TIMERS.get(id);
            if (context == null) {
                return 0;
            }
            if (context.flag) {
                TIMERS.remove(id);
                return 0;
            }
            Runnable runnable = context.runnable;
            if (runnable != null) {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    reportException(t);
                }
            }
            return 1;
        } catch (Throwable t) {
            reportException(t);
            return 1;
        }
    }

    /** {@code guint gdk_threads_add_idle_full(gint priority, GSourceFunc function, gpointer data, GDestroyNotify)}. */
    private static final MethodHandle GDK_THREADS_ADD_IDLE_FULL = bind(GDK, LIB_GDK, "gdk_threads_add_idle_full",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle GTK_MAIN = bind(GTK, LIB_GTK, "gtk_main", FunctionDescriptor.ofVoid());

    static final MethodHandle GTK_MAIN_QUIT = bind(GTK, LIB_GTK, "gtk_main_quit", FunctionDescriptor.ofVoid());

    /** The {@code call_runnable} of {@code GlassApplication.cpp}, shared by every submitted runnable. */
    private static final MemorySegment INVOKE_LATER_CALLBACK = upcallStub(GtkMainLoopCalls.class,
            "onInvokeLater", SOURCE_FUNC);

    /**
     * Stands in the registry for a {@code null} runnable, which a {@code ConcurrentHashMap} cannot hold: the C
     * took a global reference to {@code NULL} and its {@code CallVoidMethod} on it left a
     * {@code NullPointerException} that {@code LOG_EXCEPTION} reported.
     */
    private static final Runnable NULL_RUNNABLE = () -> {
        throw new NullPointerException();
    };

    /**
     * The runnables submitted and not yet run, keyed by the id that crosses as the idle source's
     * {@code gpointer}; strong, as the JNI global references were. An entry leaves when its idle runs. A runnable
     * whose idle never runs - the loop quit first - stays, as its global reference and context leaked.
     */
    private static final Map<Long, Runnable> RUNNABLES = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_RUNNABLE_ID = new AtomicLong(1);

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication__1submitForLaterInvocation} ({@code GlassApplication.cpp}),
     * from whatever thread submits (the {@code InvokeLaterDispatcher} thread, or any thread when embedded in SWT).
     * The C printed {@code malloc failed ...} and dropped the runnable when it could not allocate the context; the
     * registry cannot fail that way.
     */
    static void submitForLaterInvocation(Runnable runnable) {
        long id = NEXT_RUNNABLE_ID.getAndIncrement();
        RUNNABLES.put(id, runnable == null ? NULL_RUNNABLE : runnable);
        try {
            int source = (int) GDK_THREADS_ADD_IDLE_FULL.invokeExact(G_PRIORITY_HIGH_IDLE + 30, INVOKE_LATER_CALLBACK,
                    MemorySegment.ofAddress(id), MemorySegment.NULL);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code call_runnable} ({@code GlassApplication.cpp}), the one-shot {@code GSourceFunc} of
     * {@link #submitForLaterInvocation}, on the thread that iterates the default main context.
     */
    static int onInvokeLater(MemorySegment data) {
        try {
            Runnable runnable = RUNNABLES.remove(data.address());
            if (runnable != null) {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    reportException(t);
                }
            }
        } catch (Throwable t) {
            reportException(t);
        }
        return 0;
    }

    /**
     * {@code Java_com_sun_glass_ui_gtk_GtkApplication_enterNestedEventLoopImpl} ({@code GlassApplication.cpp}):
     * {@code gtk_main()}, which runs until the matching {@link #leaveNestedEventLoop}. Everything the loop
     * dispatches - the event handlers of {@code libglassgtk3.so}, which reach Java through the callback tables
     * ({@link GtkCallbackTables#installCallbacks}), and the other upcalls of the binding - runs inside this downcall on
     * the FX thread. The event handlers leave no exception pending and create no JNI local reference: every slot of
     * the tables is installed before the first event, and the stubs report what their Java targets throw. Should
     * anything be pending when the downcall returns, it is rethrown here unchanged, checked or not, as the JNI native
     * method threw it.
     */
    static void enterNestedEventLoop() {
        try {
            GTK_MAIN.invokeExact();
        } catch (Throwable t) {
            throw GtkGlassNative.<RuntimeException>rethrow(t);
        }
    }

    /** {@code Java_com_sun_glass_ui_gtk_GtkApplication_leaveNestedEventLoopImpl} ({@code GlassApplication.cpp}). */
    static void leaveNestedEventLoop() {
        try {
            GTK_MAIN_QUIT.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private GtkMainLoopCalls() {
    }
}
