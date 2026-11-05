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

import com.sun.javafx.font.JniStringCodec;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_OK;
import static com.sun.glass.ui.mac.MacAppCallbacks.GMAC_UPCALL_THREW;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_OK;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_SIZEOF_TIMER_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_TIMER_INIT;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_TIMER_INSTALL_CALLBACKS;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_TIMER_PAUSE;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_TIMER_PAUSED;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_TIMER_RESUME;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_TIMER_START_PERIOD;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_TIMER_START_VSYNC;
import static com.sun.glass.ui.mac.MacGlassNative.GMAC_TIMER_STOP;
import static com.sun.glass.ui.mac.MacGlassNative.LIBRARY_NAME;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetached;
import static com.sun.glass.ui.mac.MacGlassNative.mainThreadDetachedError;
import static com.sun.glass.ui.mac.MacGlassNative.reportException;
import static com.sun.glass.ui.mac.MacGlassNative.unexpected;
import static com.sun.glass.ui.mac.MacGlassNative.upcallStub;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * The natives of {@code MacTimer} over the {@code gmac_timer_*} exports, and the target of {@code tick}, the one
 * slot of {@code GmacTimerCallbacks}. The runnables cross as ids into a registry; no native code holds a Java
 * reference.
 * <p>
 * Each call evaluates {@code gmac_main_thread_detached} where its JNI body ran {@code GLASS_ASSERT_MAIN_JAVA_THREAD},
 * runs its body all the same, and reports the error once afterwards, where the JNI's closing
 * {@code GLASS_CHECK_EXCEPTION} did; none throws it. Starts and stops come from the FX thread, pauses from a timer's
 * own thread, resumes from any thread, and ticks from the timer threads.
 */
final class MacTimerCalls {

    static final int MIN_PERIOD = 0;
    static final int MAX_PERIOD = 1000000;

    /** The name the JNI attached the display link's thread with. */
    static final String DISPLAY_LINK_THREAD_NAME = "PulseTimer-CVDisplayLink thread";

    static final StructLayout GMAC_TIMER_CALLBACKS_LAYOUT = MemoryLayout.structLayout(ADDRESS.withName("tick"));

    /** The {@code gmac_timer_*} calls, so that a test can stand where the library's timers would be. */
    interface TimerDowncalls {

        /** The {@code GlassTimer*} of a new "Glass Timer Thread" ticking {@code timerId}, or 0. */
        long startPeriod(long timerId, int period);

        /** The {@code GlassTimer*} that the display link now ticks {@code timerId} for; never 0. */
        long startVsync(long timerId);

        void stop(long timer);

        void pause(long timer);

        void resume(long timer);

        boolean paused(long timer);
    }

    static final TimerDowncalls LIBRARY = new LibraryTimers();

    /** The thread that ticks a timer decides what becomes of what its runnable throws. */
    private sealed interface RegisteredTimer permits PeriodTimer, VsyncTimer {
    }

    private record PeriodTimer(Runnable runnable) implements RegisteredTimer {
    }

    private static final class VsyncTimer implements RegisteredTimer {

        private final Runnable runnable;
        private final TimerDowncalls downcalls;

        /* 0 until the start export has returned: the display link may tick the id before. */
        private volatile long timer;

        VsyncTimer(Runnable runnable, TimerDowncalls downcalls) {
            this.runnable = runnable;
            this.downcalls = downcalls;
        }

        /** Nothing can have paused a timer whose start has not returned: {@code Timer} holds its lock until then. */
        boolean isPaused() {
            long started = timer;
            return started != 0L && downcalls.paused(started);
        }
    }

    /**
     * The timers by id. An entry leaves once the library can no longer tick it: when its start answered NULL, and
     * when a period timer of a nonzero period is stopped, which ends its thread. A tick that thread begins after the
     * stop then runs nothing, where the JNI, which released the runnable only when the thread ended, ran it once
     * more. The entries of vsync timers stay, as the JNI never released their runnables, and so do those of 0 ms
     * period timers: the library takes their stop for a vsync timer's, so their threads tick on (latent bug 19).
     */
    private static final Map<Long, RegisteredTimer> TIMERS = new ConcurrentHashMap<>();

    /** The id of each running period timer that its stop ends, by its {@code GlassTimer*}. */
    private static final Map<Long, Long> ENDED_BY_STOP = new ConcurrentHashMap<>();

    private static final AtomicLong NEXT_TIMER_ID = new AtomicLong(1);

    /**
     * The JNI's attach of a display link's thread, which named the thread and lasted until a tick left the timer
     * paused and the JNI detached the thread: what the last throw of a vsync tick left pending meanwhile.
     */
    private static final class DisplayLinkAttach {
        private Throwable pending;
    }

    /** Unset while the JNI would attach the thread at its next tick: before its first, and after a detach. */
    private static final ThreadLocal<DisplayLinkAttach> DISPLAY_LINK_ATTACH = new ThreadLocal<>();

    private static final MemorySegment TICK_STUB = createTickStub();

    /** Never closed: closing it would close fd 2. */
    private static final FileOutputStream STANDARD_ERROR = new FileOutputStream(FileDescriptor.err);

    private MacTimerCalls() {
    }

    /** {@code MacTimer._getMinPeriod}. */
    static int minPeriod() {
        reportIfDetached(mainThreadDetached());
        return MIN_PERIOD;
    }

    /** {@code MacTimer._getMaxPeriod}. */
    static int maxPeriod() {
        reportIfDetached(mainThreadDetached());
        return MAX_PERIOD;
    }

    /**
     * {@code MacTimer._initIDs}, which has no detached assertion: installs the table, then creates and starts the
     * display link, which {@code gmac_screen_video_refresh_period} reads.
     */
    static void init() {
        installCallbacks();
        try {
            GMAC_TIMER_INIT.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Once, before the first start. The library copies the table, so its memory may go once the call returns. */
    static void installCallbacks() {
        int size = sizeOfTable();
        if (size != GMAC_TIMER_CALLBACKS_LAYOUT.byteSize()) {
            throw new UnsatisfiedLinkError(LIBRARY_NAME + " layout mismatch: sizeof GmacTimerCallbacks is " + size
                    + ", the layout has " + GMAC_TIMER_CALLBACKS_LAYOUT.byteSize() + " bytes");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(GMAC_TIMER_CALLBACKS_LAYOUT);
            table.set(ADDRESS, 0, TICK_STUB);
            int status = (int) GMAC_TIMER_INSTALL_CALLBACKS.invokeExact(table);
            if (status != GMAC_OK) {
                throw new IllegalStateException("gmac_timer_install_callbacks answered " + status);
            }
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static MemorySegment tickStub() {
        return TICK_STUB;
    }

    static long startPeriod(Runnable runnable, int period) {
        return startPeriod(runnable, period, LIBRARY);
    }

    /** {@code MacTimer._start(Runnable, int)}. */
    static long startPeriod(Runnable runnable, int period, TimerDowncalls downcalls) {
        boolean detached = mainThreadDetached();
        long timerId = register(new PeriodTimer(runnable));
        long timer = start(timerId, () -> downcalls.startPeriod(timerId, period));
        // The library tells a vsync timer by a period of 0, so a 0 ms period timer's stop does not end its thread.
        if (timer != 0L && period != 0) {
            ENDED_BY_STOP.put(timer, timerId);
        }
        reportIfDetached(detached);
        return timer;
    }

    static long startVsync(Runnable runnable) {
        return startVsync(runnable, LIBRARY);
    }

    /** {@code MacTimer._start(Runnable)}. */
    static long startVsync(Runnable runnable, TimerDowncalls downcalls) {
        boolean detached = mainThreadDetached();
        VsyncTimer registered = new VsyncTimer(runnable, downcalls);
        long timerId = register(registered);
        registered.timer = start(timerId, () -> downcalls.startVsync(timerId));
        reportIfDetached(detached);
        return registered.timer;
    }

    static void stop(long timer) {
        stop(timer, LIBRARY);
    }

    /**
     * {@code MacTimer._stop}. The id leaves its pointer before the call: once the call returns, the thread may free
     * the {@code GlassTimer}, and a new start may get its address.
     */
    static void stop(long timer, TimerDowncalls downcalls) {
        boolean detached = mainThreadDetached();
        Long endedTimerId = ENDED_BY_STOP.remove(timer);
        downcalls.stop(timer);
        if (endedTimerId != null) {
            TIMERS.remove(endedTimerId);
        }
        reportIfDetached(detached);
    }

    static void pause(long timer) {
        pause(timer, LIBRARY);
    }

    /** {@code MacTimer._pause}. */
    static void pause(long timer, TimerDowncalls downcalls) {
        boolean detached = mainThreadDetached();
        downcalls.pause(timer);
        reportIfDetached(detached);
    }

    static void resume(long timer) {
        resume(timer, LIBRARY);
    }

    /** {@code MacTimer._resume}. */
    static void resume(long timer, TimerDowncalls downcalls) {
        boolean detached = mainThreadDetached();
        downcalls.resume(timer);
        reportIfDetached(detached);
    }

    private static long register(RegisteredTimer timer) {
        long timerId = NEXT_TIMER_ID.getAndIncrement();
        TIMERS.put(timerId, timer);
        return timerId;
    }

    /**
     * Runs the start export with the entry in place, because the library may tick the id before the export returns,
     * and drops the entry when the export answered NULL or threw: no tick can come for it then.
     */
    private static long start(long timerId, LongSupplier startExport) {
        long timer = 0L;
        try {
            timer = startExport.getAsLong();
        } finally {
            if (timer == 0L) {
                TIMERS.remove(timerId);
            }
        }
        return timer;
    }

    /**
     * The {@code tick} slot, on a timer's thread. An id the registry no longer holds runs nothing. What the target's
     * own steps throw is reported, since nothing may escape an upcall: an {@code OutOfMemoryError}, or the
     * {@code UnsatisfiedLinkError} of a {@code gmac_timer_paused} the library lacks. The JNI's C had no such step.
     */
    private static int tick(long timerId) {
        try {
            return switch (TIMERS.get(timerId)) {
                case null -> GMAC_UPCALL_OK;
                case PeriodTimer period -> tickPeriod(period.runnable());
                case VsyncTimer vsync -> tickVsync(vsync);
            };
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /** REPORT: the JNI's {@code GLASS_CHECK_EXCEPTION} after the tick. */
    private static int tickPeriod(Runnable runnable) {
        try {
            runnable.run();
            return GMAC_UPCALL_OK;
        } catch (Throwable t) {
            reportException(t);
            return GMAC_UPCALL_THREW;
        }
    }

    /**
     * NONE: the JNI attached the thread with its name unless it was attached, so a name the runnable gave it lasted
     * until the detach. It ran the runnable with what an earlier tick threw still pending, a new throw replacing it,
     * and detached the thread after a tick that left the timer paused, which handed what was pending to the thread's
     * uncaught-exception handler.
     */
    private static int tickVsync(VsyncTimer timer) {
        Thread thread = Thread.currentThread();
        DisplayLinkAttach attach = DISPLAY_LINK_ATTACH.get();
        if (attach == null) {
            thread.setName(DISPLAY_LINK_THREAD_NAME);
            attach = new DisplayLinkAttach();
            DISPLAY_LINK_ATTACH.set(attach);
        }
        int status = GMAC_UPCALL_OK;
        try {
            timer.runnable.run();
        } catch (Throwable t) {
            attach.pending = t;
            status = GMAC_UPCALL_THREW;
        }
        if (timer.isPaused()) {
            DISPLAY_LINK_ATTACH.remove();
            if (attach.pending != null) {
                dispatchUncaughtException(thread, attach.pending);
            }
        }
        return status;
    }

    /** What HotSpot's detach did with a pending exception: {@code Thread.dispatchUncaughtException}. */
    private static void dispatchUncaughtException(Thread thread, Throwable pending) {
        try {
            thread.getUncaughtExceptionHandler().uncaughtException(thread, pending);
        } catch (Throwable t) {
            printHandlerFailure(thread, t);
        }
    }

    /**
     * The line HotSpot's {@code JavaThread::exit} writes to fd 2, with both names in modified UTF-8, when the handler
     * throws; it then clears what the handler threw.
     */
    private static void printHandlerFailure(Thread thread, Throwable failure) {
        try {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            line.writeBytes("\nException: ".getBytes(US_ASCII));
            writeModifiedUtf8(line, failure.getClass().getName());
            line.writeBytes(" thrown from the UncaughtExceptionHandler in thread \"".getBytes(US_ASCII));
            writeModifiedUtf8(line, thread.getName());
            line.writeBytes("\"\n".getBytes(US_ASCII));
            STANDARD_ERROR.write(line.toByteArray());
        } catch (Throwable ignored) {
            // HotSpot's CLEAR_PENDING_EXCEPTION
        }
    }

    private static void writeModifiedUtf8(ByteArrayOutputStream out, String s) {
        byte[] terminated = JniStringCodec.toModifiedUtf8(s);
        out.write(terminated, 0, terminated.length - 1);
    }

    private static void reportIfDetached(boolean detached) {
        if (detached) {
            reportException(mainThreadDetachedError());
        }
    }

    private static int sizeOfTable() {
        try {
            return (int) GMAC_SIZEOF_TIMER_CALLBACKS.invokeExact();
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    private static MemorySegment createTickStub() {
        try {
            MethodHandle tick = MethodHandles.lookup().findStatic(MacTimerCalls.class, "tick",
                    MethodType.methodType(int.class, long.class));
            return upcallStub(tick, FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final class LibraryTimers implements TimerDowncalls {

        @Override
        public long startPeriod(long timerId, int period) {
            try {
                return ((MemorySegment) GMAC_TIMER_START_PERIOD.invokeExact(timerId, period)).address();
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }

        @Override
        public long startVsync(long timerId) {
            try {
                return ((MemorySegment) GMAC_TIMER_START_VSYNC.invokeExact(timerId)).address();
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }

        @Override
        public void stop(long timer) {
            try {
                GMAC_TIMER_STOP.invokeExact(MemorySegment.ofAddress(timer));
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }

        @Override
        public void pause(long timer) {
            try {
                GMAC_TIMER_PAUSE.invokeExact(MemorySegment.ofAddress(timer));
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }

        @Override
        public void resume(long timer) {
            try {
                GMAC_TIMER_RESUME.invokeExact(MemorySegment.ofAddress(timer));
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }

        @Override
        public boolean paused(long timer) {
            try {
                return (int) GMAC_TIMER_PAUSED.invokeExact(MemorySegment.ofAddress(timer)) != 0;
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }
}
