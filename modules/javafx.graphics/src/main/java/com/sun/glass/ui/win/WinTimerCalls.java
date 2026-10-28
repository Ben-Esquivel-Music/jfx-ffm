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

package com.sun.glass.ui.win;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.sun.glass.ui.win.WinCallSupport.reportUpcallFailure;
import static com.sun.glass.ui.win.WinGlassNative.bind;
import static com.sun.glass.ui.win.WinGlassNative.systemLibrary;
import static com.sun.glass.ui.win.WinGlassNative.unexpected;
import static com.sun.glass.ui.win.WinGlassNative.upcallStub;
import static com.sun.glass.ui.win.WinGlassNative.upcallTarget;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@link WinGlassNative} calls of {@link WinTimer}: the multimedia timer over {@code winmm}.
 * <p>
 * {@link WinTimer} adds no C at all: all four of its natives were marshalling around five {@code winmm}
 * functions, which this class binds directly. None of the five is {@link Linker.Option#critical critical}:
 * {@code timeSetEvent} installs a callback, {@code timeKillEvent} can block, and none of them is ever
 * handed a Java array.
 * <p>
 * <b>Threading.</b> {@link #timerStart} and {@link #timerStop} are called from whatever thread drives
 * {@code com.sun.glass.ui.Timer} (the FX thread, for {@code QuantumToolkit}'s pulse timer),
 * {@code onTimerFired} is entered from a winmm multimedia-timer worker thread that nothing attaches, and
 * the class therefore holds state: the timer registry and the {@code timeBeginPeriod} refcount, guarded as
 * described at {@link #TIMERS} and {@link #TIMER_LOCK}. Its initializer binds nothing and reaches no other
 * class.
 * <p>
 * Line numbers into the {@code native-glass/win} C++ sources ({@code Timer.cpp}, {@code Timer.h},
 * {@code Utils.h}, {@code Utils.cpp}) refer to those files at commit {@code 8492cb03b0}
 * ({@code git show 8492cb03b0:modules/javafx.graphics/src/main/native-glass/win/<file>}).
 */
final class WinTimerCalls {

    /* mmsystem.h: the multimedia timer service that Timer.h L46-94 drove. */
    static final int TIMERR_NOERROR = 0;
    static final int TIME_ONESHOT = 0x0000;
    static final int TIME_PERIODIC = 0x0001;

    /*
     * TIME_KILL_SYNCHRONOUS (0x0100) is deliberately absent from that list, and has to stay absent.
     * Timer.h:71-72 passed TIME_PERIODIC alone, so timeKillEvent may return while a callback is still
     * running - and that, not any lock discipline of ours, is what makes timerStop safe to call from
     * the JavaFX application thread. The stopping thread holds the com.sun.glass.ui.Timer instance
     * monitor (Timer.java:129 synchronized stop()), and the tick path takes that same monitor:
     * QuantumToolkit.java:536 pulseTimer.pause() -> Timer.java:141 synchronized pause(). A
     * timeKillEvent that joined an in-flight callback would therefore deadlock the FX thread against a
     * winmm worker on every application shutdown (QuantumToolkit.java:869 and :892). Setting the flag
     * is not a follow-up detail: it requires breaking that monitor overlap first.
     */

    /**
     * {@code TIMECAPS} (mmsystem.h), filled by {@code timeGetDevCaps} ({@code Timer.h:85}).
     * {@code WinTimerNativeTest} pins the size and both offsets and then reads the values back, which is
     * what catches a swapped or shifted layout - the size and offsets on their own can only restate the
     * numbers this layout was written from. The {@code static_assert} in {@code glass_win_api.cpp}, beside
     * the {@code INPUT} ones, is the C side of the same check.
     */
    static final StructLayout TIMECAPS_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("wPeriodMin"),
            JAVA_INT.withName("wPeriodMax"));

    private static final long TIMECAPS_MIN_OFFSET =
            TIMECAPS_LAYOUT.byteOffset(PathElement.groupElement("wPeriodMin"));
    private static final long TIMECAPS_MAX_OFFSET =
            TIMECAPS_LAYOUT.byteOffset(PathElement.groupElement("wPeriodMax"));

    /**
     * {@code winmm.dll} and the five timer functions of it, bound on first use rather than in this
     * class's initializer.
     * <p>
     * The laziness is behaviour parity, not taste: {@code glass.dll} delay-loaded {@code winmm}
     * ({@code win.cmake} {@code /DELAYLOAD:winmm.dll}), so a process that used {@link WinRobot} and no
     * timer never loaded it, and a machine on which {@code winmm} could not be resolved lost timers
     * only. Binding it in {@link WinGlassNative}'s own initializer would load it on the first robot
     * call and, worse, would turn a {@code winmm} failure into a failed initializer for this whole
     * class - every {@code WinRobot} call would then die with {@code NoClassDefFoundError} for a
     * library it does not use. A holder class keeps both the load point and the blast radius where the
     * JNI had them. Nothing is lost in a real application: {@code QuantumToolkit} starts the pulse
     * timer on every run ({@code QuantumToolkit.java:391-401}).
     * <p>
     * The cost is that {@link WinGlassNative#boundSymbols()} grows when the first timer call happens;
     * {@code WinGlassNativeTest} forces this class to initialise in its {@code @BeforeAll} so that its
     * ordered assertion stays an exact equality rather than a prefix check.
     */
    static final class Winmm {

        static final String NAME = "winmm";

        /** A system DLL the JDK does not load for us; the lookup lives for the life of the process. */
        static final SymbolLookup LOOKUP = systemLibrary(NAME + ".dll");

        /** {@code MMRESULT timeGetDevCaps(LPTIMECAPS ptc, UINT cbtc)} - {@code Timer.h:85}. */
        private static final MethodHandle TIME_GET_DEV_CAPS = bindWinmm("timeGetDevCaps",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

        /** {@code MMRESULT timeBeginPeriod(UINT uPeriod)} - {@code Timer.h:53}. */
        private static final MethodHandle TIME_BEGIN_PERIOD = bindWinmm("timeBeginPeriod",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        /** {@code MMRESULT timeEndPeriod(UINT uPeriod)} - {@code Timer.h:63}. */
        private static final MethodHandle TIME_END_PERIOD = bindWinmm("timeEndPeriod",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        /**
         * {@code MMRESULT timeSetEvent(UINT uDelay, UINT uResolution, LPTIMECALLBACK lpTimeProc,
         * DWORD_PTR dwUser, UINT fuEvent)} - {@code Timer.h:71-72}. Returns the timer id, 0 on failure.
         * {@code DWORD_PTR} is pointer-sized, hence {@code JAVA_LONG} on the two Windows architectures
         * the JDK's {@link Linker} supports.
         */
        private static final MethodHandle TIME_SET_EVENT = bindWinmm("timeSetEvent",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));

        /** {@code MMRESULT timeKillEvent(UINT uTimerID)} - {@code Timer.h:60}. */
        private static final MethodHandle TIME_KILL_EVENT = bindWinmm("timeKillEvent",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        /**
         * {@code void CALLBACK LPTIMECALLBACK(UINT uTimerID, UINT uMsg, DWORD_PTR dwUser, DWORD_PTR
         * dw1, DWORD_PTR dw2)}. {@code CALLBACK} is {@code __stdcall}, a no-op on x64 and arm64.
         */
        private static final FunctionDescriptor TIME_CALLBACK_FD =
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);

        /**
         * The one {@code LPTIMECALLBACK} of the process, in {@link Arena#global()}: the target is
         * static and only {@code dwUser} differs between timers, so one stub serves every timer and
         * there is no arena to close - which matters, because {@code timeKillEvent} does not join an
         * in-flight callback (see {@link #TIME_PERIODIC}) and so there is no point at
         * which closing a per-timer arena would be safe.
         */
        private static final MemorySegment TIME_CALLBACK = upcallStub(
                upcallTarget(WinTimerCalls.class, "onTimerFired", TIME_CALLBACK_FD), TIME_CALLBACK_FD,
                Arena.global());

        private Winmm() {
        }

        private static MethodHandle bindWinmm(String name, FunctionDescriptor descriptor) {
            return bind(LOOKUP, NAME, name, descriptor);
        }
    }

    private WinTimerCalls() {
    }

    /**
     * One running timer: what {@code RunnableTimer} held ({@code Timer.cpp:53-67},
     * {@code Timer.h:106}) minus the two members that only existed because a winmm worker thread had
     * no {@code JNIEnv} - the cached {@code JNIEnv*} and the JNI global ref.
     */
    private static final class TimerEntry {

        private final Runnable runnable;

        /** 0 until {@code timeSetEvent} returns. */
        private volatile int mmTimerId;

        private TimerEntry(Runnable runnable) {
            this.runnable = runnable;
        }
    }

    /**
     * The running timers, keyed by the id {@link #timerStart} hands out and {@code timeSetEvent}
     * carries back in {@code dwUser}.
     * <p>
     * The references are strong, on purpose: a {@code com.sun.glass.ui.Timer} that becomes unreachable
     * without {@link com.sun.glass.ui.Timer#stop() stop()} keeps ticking and pins its {@code Runnable}
     * for the life of the JVM. That is exactly what the C did - an abandoned {@code RunnableTimer} was
     * a leaked C++ object plus a leaked JNI global ref ({@code Timer.cpp:42,67}), and its
     * {@code timeSetEvent} was never killed either. Making this a weak map would silently stop such a
     * timer, which is a behaviour change and not a leak fix; it does not belong in this migration.
     * {@code WinTimerNativeTest.anAbandonedTimerKeepsTickingAndPinsItsRunnable} pins the parity.
     */
    private static final Map<Long, TimerEntry> TIMERS = new ConcurrentHashMap<>();

    /**
     * Ids run from 1, so {@code dwUser} is never 0 - as it never was in the C, where it was a non-null
     * {@code this} ({@code Timer.h:72}). 0 stays reserved for "failed to start" at the
     * {@code com.sun.glass.ui.Timer} boundary ({@code Timer.java:97,117}).
     */
    private static final AtomicLong NEXT_TIMER_ID = new AtomicLong(1L);

    /**
     * Guards the four fields below, which were the plain statics {@code Timer::timersCount},
     * {@code Timer::wTimerRes} and {@code Timer::tc} ({@code Timer.h:102-104}) - mutated from any
     * thread that started or stopped a timer, with the C's own comment admitting the race
     * ({@code Timer.h:81-82}). Locking them can only remove corruption: the lock is never held across
     * {@code timeSetEvent} or {@code timeKillEvent}, and the callback path never takes it.
     */
    private static final Object TIMER_LOCK = new Object();

    /** The {@code timeBeginPeriod} refcount. */
    private static int timersCount;

    /** The resolution every timer is started with. */
    private static int timerRes;

    private static int periodMin;
    private static int periodMax;

    /**
     * {@code Timer::InitTC} ({@code Timer.h:80-94}), including its memo and its failure value: the
     * query is repeated whenever <em>both</em> fields are 0, so a machine on which
     * {@code timeGetDevCaps} fails retries on every call and reports 0/0 in the meantime. The caller
     * holds {@link #TIMER_LOCK}.
     *
     * @return false exactly when {@code timeGetDevCaps} failed
     */
    private static boolean initTimeCaps() {
        if (periodMin == 0 && periodMax == 0) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment caps = arena.allocate(TIMECAPS_LAYOUT);
                int result;
                try {
                    result = (int) Winmm.TIME_GET_DEV_CAPS.invokeExact(caps,
                            (int) TIMECAPS_LAYOUT.byteSize());
                } catch (Throwable t) {
                    throw unexpected(t);
                }
                if (result != TIMERR_NOERROR) {
                    periodMin = 0;
                    periodMax = 0;
                    return false;
                }
                periodMin = caps.get(JAVA_INT, TIMECAPS_MIN_OFFSET);
                periodMax = caps.get(JAVA_INT, TIMECAPS_MAX_OFFSET);
                timerRes = clampToDeviceRange(periodMin);
            }
        }
        return true;
    }

    /**
     * {@code min(max(tc.wPeriodMin, 1), tc.wPeriodMax)} ({@code Timer.h:90}, "We want 1 ms accuracy"),
     * on {@code UINT}s through the MSVC {@code min}/{@code max} macros - that is, with
     * <em>unsigned</em> comparisons. {@link Integer#compareUnsigned} keeps that exact, rather than
     * resting on an argument about the range {@code timeGetDevCaps} returns.
     */
    private static int clampToDeviceRange(int wPeriodMin) {
        int atLeastOneMillisecond = Integer.compareUnsigned(wPeriodMin, 1) > 0 ? wPeriodMin : 1;
        return Integer.compareUnsigned(atLeastOneMillisecond, periodMax) < 0
                ? atLeastOneMillisecond : periodMax;
    }

    /**
     * {@code Timer::GetMinPeriod} ({@code Timer.h:32-36}): the C ignores {@code InitTC}'s result and
     * returns the field, so a failed query reads back as 0 here too.
     */
    static int timerMinPeriod() {
        synchronized (TIMER_LOCK) {
            boolean ignoredResult = initTimeCaps();
            return periodMin;
        }
    }

    /** {@code Timer::GetMaxPeriod} ({@code Timer.h:38-42}), the same one query. */
    static int timerMaxPeriod() {
        synchronized (TIMER_LOCK) {
            boolean ignoredResult = initTimeCaps();
            return periodMax;
        }
    }

    /**
     * {@code RunnableTimer::Start} ({@code Timer.cpp:39-46}) over {@code Timer::Timer}
     * ({@code Timer.h:46-55}) and {@code Timer::start} ({@code Timer.h:67-75}), returning 0 in exactly
     * the two places the C returned 0 - both of them a C++ throw that {@code Timer.cpp:41-45} caught.
     * <p>
     * The two throw sites are not symmetric, and the difference is reproduced:
     * <ul>
     * <li>{@code InitTC} failing throws from the <em>base</em> constructor body
     *     ({@code Timer.h:49-51}), so {@code ~Timer()} never runs and {@code timersCount} stays
     *     incremented for the life of the process - the next start then sees 2, skips both
     *     {@code InitTC} and {@code timeBeginPeriod}, and passes {@code uResolution == 0}. Hence no
     *     decrement on that path here either. It is unreachable in practice, because 0/0 caps make
     *     {@code Timer.start} throw first ({@code Timer.java:88-90}), and is reproduced only because
     *     it is cheap to reproduce.</li>
     * <li>{@code start()} failing throws from the <em>derived</em> body ({@code Timer.cpp:55-57}), so
     *     the members are destroyed and the base {@code ~Timer()} does run with {@code id == 0},
     *     taking only its {@code --timersCount} branch - which is {@link #releaseOnePeriod()}.</li>
     * </ul>
     * The order is the C's ({@code Timer.h:46-75}, {@code Timer.cpp:53-58}). The registry entry is
     * created before {@code timeSetEvent} - the C++ object was reachable from the callback through
     * {@code dwUser} from that call on, with {@code runnable} already valid - so a tick that arrives
     * while {@code timeSetEvent} is still returning already finds it. The multimedia id is stored the
     * moment {@code timeSetEvent} returns and tested afterwards, as {@code id = ::timeSetEvent(...)}
     * was ({@code Timer.h:71-74}). The id is handed out last, as the pointer was. An entry whose
     * {@code mmTimerId} is still 0 is therefore visible only to a {@link #timerStop} that holds an id
     * this method has not returned yet, which {@code Timer.java} cannot produce: {@code start} and
     * {@code stop} share the {@code Timer}'s monitor, and {@code stop} passes the value {@code start}
     * returned.
     *
     * @param runnable the timer's {@code Runnable}, called on a winmm worker thread
     * @param period the period in milliseconds, passed straight to {@code timeSetEvent} as
     *        {@code uDelay}; the resolution is <em>not</em> the period ({@code Timer.h:71})
     * @return the id to hand back to {@link #timerStop}, or 0 if the timer did not start
     */
    static long timerStart(Runnable runnable, int period) {
        int resolution;
        synchronized (TIMER_LOCK) {
            if (++timersCount == 1) {
                if (!initTimeCaps()) {
                    return 0;
                }
                beginPeriod(timerRes);
            }
            resolution = timerRes;
        }
        long id = NEXT_TIMER_ID.getAndIncrement();
        TimerEntry entry = new TimerEntry(runnable);
        TIMERS.put(id, entry);
        int mmTimerId = setEvent(period, resolution, id);
        entry.mmTimerId = mmTimerId;
        if (mmTimerId == 0) {
            TIMERS.remove(id);
            releaseOnePeriod();
            return 0;
        }
        return id;
    }

    /**
     * {@code RunnableTimer::Stop} ({@code Timer.cpp:48-51}) over {@code ~Timer} ({@code Timer.h:57-65})
     * and in the destructors' own order, which is also the order that makes the callback safe:
     * <ol>
     * <li>remove the registry entry - the stand-in for {@code ~JGlobalRef} -&gt;
     *     {@code DeleteGlobalRef} ({@code Utils.h:374-378}), which the C also ran first because
     *     {@code runnable} is a member of the derived class ({@code Timer.cpp:67}). After it returns,
     *     every later tick reads {@code null} and drops;</li>
     * <li>{@code timeKillEvent} ({@code Timer.h:60}), <em>outside</em> {@link #TIMER_LOCK}: winmm is
     *     free to block here, and the callback path must never be able to wait on a lock this thread
     *     holds;</li>
     * <li>{@link #releaseOnePeriod()} ({@code Timer.h:62-64}).</li>
     * </ol>
     * The residual window is the C's: a tick that read its entry just before step 1 may still be
     * inside {@code run()} when this returns, because {@code TIME_KILL_SYNCHRONOUS} is not set. Nothing
     * is freed in that window - the stub is global and the {@code Runnable} is held by the callback's
     * own local - where the C called {@code CallVoidMethod} on a global ref it had already released.
     * The visible delta is the other way round: a tick the C would in practice still have delivered is
     * dropped here (a stop-then-restart, {@code Timer.java:92-94}, can lose one pulse).
     * <p>
     * An unknown or already-stopped id returns silently, where {@code Timer.cpp:50} would have deleted
     * whatever it was given a second time. {@code Timer.java:129-135} makes that unreachable through
     * the public API, so no caller can tell.
     */
    static void timerStop(long id) {
        TimerEntry entry = TIMERS.remove(id);
        if (entry == null) {
            return;
        }
        int mmTimerId = entry.mmTimerId;
        if (mmTimerId != 0) {
            killEvent(mmTimerId);
        }
        releaseOnePeriod();
    }

    /**
     * The half of {@code ~Timer} that runs on every stop and on a failed {@code timeSetEvent}
     * ({@code Timer.h:62-64}). {@code timeEndPeriod} is skipped when {@code timerRes} is 0, while
     * {@code timeBeginPeriod} is called even then ({@code Timer.h:53}) - an asymmetry reachable only
     * when {@code wPeriodMax} is 0 with a non-zero {@code wPeriodMin}, and kept verbatim.
     */
    private static void releaseOnePeriod() {
        synchronized (TIMER_LOCK) {
            if (--timersCount == 0 && timerRes != 0) {
                endPeriod(timerRes);
            }
        }
    }

    /**
     * The {@code LPTIMECALLBACK} body, {@code Timer::StaticTimeCallback} plus
     * {@code RunnableTimer::TimerCallback} ({@code Timer.h:97-100}, {@code Timer.cpp:60-64}) in one
     * method. {@code uTimerID}, {@code uMsg}, {@code dw1} and {@code dw2} are ignored exactly as the C
     * ignored them; {@code dwUser} is the registry id.
     * <p>
     * <b>This runs on a winmm multimedia-timer worker thread</b> - never the JavaFX application
     * thread - and nothing attaches that thread. This is the whole point of moving the timer: the JNI had to
     * call {@code AttachCurrentThreadAsDaemon} here ({@code Timer.cpp:69-77}, the only thread attach in
     * javafx.graphics) and cache the {@code JNIEnv} forever, while an FFM upcall stub is callable from
     * a thread the JVM has never seen. {@code com.sun.glass.ui.Timer} promises only that "the run()
     * method may be invoked on a thread other than the UI thread" ({@code Timer.java:35-37}), so no
     * test asserts anything about the identity of this thread.
     * <p>
     * It must not throw: an exception escaping an upcall stub terminates the JVM, so {@code Throwable}
     * is caught here and the report itself is wrapped as well - {@code CheckAndClearException}
     * ({@code Utils.cpp:50-71}) swallowed every failure of its own reporting for the same reason. A
     * tick whose entry has already been removed is dropped, where the C called {@code CallVoidMethod}
     * on a released global ref.
     */
    static void onTimerFired(int uTimerID, int uMsg, long dwUser, long dw1, long dw2) {
        try {
            TimerEntry entry = TIMERS.get(dwUser);
            if (entry != null) {
                entry.runnable.run();
            }
        } catch (Throwable t) {
            reportUpcallFailure(t);
        }
    }

    /** {@code ::timeBeginPeriod(wTimerRes)} ({@code Timer.h:53}); the C ignored the result. */
    private static void beginPeriod(int resolution) {
        try {
            int ignoredResult = (int) Winmm.TIME_BEGIN_PERIOD.invokeExact(resolution);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code ::timeEndPeriod(wTimerRes)} ({@code Timer.h:63}); the C ignored the result. */
    private static void endPeriod(int resolution) {
        try {
            int ignoredResult = (int) Winmm.TIME_END_PERIOD.invokeExact(resolution);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * {@code ::timeSetEvent(period, wTimerRes, StaticTimeCallback, (DWORD_PTR)this, TIME_PERIODIC)}
     * ({@code Timer.h:71-72}), with the Java id where the C passed a pointer.
     *
     * @return the winmm timer id, or 0 if the timer did not start
     */
    private static int setEvent(int period, int resolution, long id) {
        try {
            return (int) Winmm.TIME_SET_EVENT.invokeExact(period, resolution, Winmm.TIME_CALLBACK, id,
                    TIME_PERIODIC);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** {@code ::timeKillEvent(id)} ({@code Timer.h:60}); the C ignored the result. */
    private static void killEvent(int mmTimerId) {
        try {
            int ignoredResult = (int) Winmm.TIME_KILL_EVENT.invokeExact(mmTimerId);
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** How many timers this class has started and not stopped; for {@code WinTimerNativeTest}. */
    static int timerRegistrySize() {
        return TIMERS.size();
    }

    /**
     * For {@code WinTimerNativeTest}, which asserts the refcount returns to its baseline on every path
     * including a failed start.
     */
    static int timerPeriodRefCount() {
        synchronized (TIMER_LOCK) {
            return timersCount;
        }
    }

    /** For {@code WinTimerNativeTest}. */
    static int timerResolution() {
        synchronized (TIMER_LOCK) {
            return timerRes;
        }
    }
}
