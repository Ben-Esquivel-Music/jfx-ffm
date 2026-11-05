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

package test.com.sun.glass.ui.mac;

import com.sun.glass.ui.mac.MacGlassNativeShim;
import com.sun.glass.ui.mac.MacGlassNativeShim.TimerStandIn;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MacTimer} over {@code GmacTimerCallbacks}: the one-slot table, the two exception sinks of the {@code tick}
 * target, the registry of runnables and the detached reports of the natives.
 * <p>
 * No test calls a {@code gmac_timer_*} export but {@code gmac_timer_install_callbacks}. A recording stand-in is
 * where the library's timers would be, ticks come from {@code gmac_test_fire_timer_callback}, and {@code MacTimer},
 * whose initialization creates and starts the display link, is never initialized. A vsync tick renames its thread,
 * so vsync ticks run only on threads made for them. AppKit's {@code NSApp} is nil before the first call into the
 * library and after the last.
 */
@EnabledOnOs(OS.MAC)
public class MacTimerNativeTest {

    private static final String DETACHED = "java.lang.RuntimeException: Main Java thread is detached.";

    private static final int GMAC_UPCALL_OK = 0;
    private static final int GMAC_UPCALL_THREW = 1;
    private static final int GMAC_ERR_NO_DATA = -1;

    private static final int PERIOD = 16;

    private static final String CALLER = "MacTimerNativeTest-caller";

    /** What the JNI's {@code AttachCurrentThreadAsDaemon(env, NULL)} named the period timer's thread. */
    private static final String UNNAMED_ATTACH = "Thread-\\d+";

    private int attachedBefore;

    @BeforeAll
    static void loadGlass() {
        MacGlassNatives.require();
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists before the first call into libglass");
        MacGlassNativeShim.loadLibrary();
        assertFalse(MacGlassNativeShim.isMainThread(), "the calls must run off the main thread to be detachable");
    }

    @AfterAll
    static void noApplicationExistsAfterTheLastCall() {
        assertTrue(MacGlassNativeShim.nsAppIsNil(), "an NSApplication exists after the last call into libglass");
    }

    @BeforeEach
    void installTheTable() {
        attachedBefore = MacGlassNativeShim.setMainJavaAttached(1);
        MacGlassNativeShim.installTimerCallbacks();
    }

    @AfterEach
    void restoreTheTableAndTheAttachedFlag() {
        MacGlassNativeShim.installTimerCallbacks();
        MacGlassNativeShim.setMainJavaAttached(attachedBefore);
    }

    @Test
    public void theTableIsOneAddressOfEightBytes() {
        StructLayout table = MacGlassNativeShim.layouts().get("GmacTimerCallbacks");
        assertEquals(8, table.byteSize());
        assertEquals(8, MacGlassNativeShim.sizeOf("GmacTimerCallbacks"));
        assertEquals(List.of("tick"), table.memberLayouts().stream().map(member -> member.name().orElseThrow())
                .toList());
    }

    @Test
    public void theLibraryTicksFromItsOwnCopyOfTheTable() {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        MacGlassNativeShim.startPeriodTimer(() -> events.add("run"), PERIOD, timers);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment table = arena.allocate(ADDRESS);
            table.set(ADDRESS, 0, MacGlassNativeShim.timerTickStub());
            assertEquals(0, MacGlassNativeShim.installTimerTable(table));
            table.set(ADDRESS, 0, MemorySegment.NULL);
        }
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireTimerCallback(timers.lastTimerId(), 1, false));
        assertEquals(List.of("startPeriod(16)", "run"), events);
    }

    @Test
    public void withoutATableOrItsSlotNothingTicks() {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        MacGlassNativeShim.startPeriodTimer(() -> events.add("run"), PERIOD, timers);
        long timerId = timers.lastTimerId();
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(0, MacGlassNativeShim.installTimerTable(arena.allocate(ADDRESS)));
            assertEquals(GMAC_ERR_NO_DATA, MacGlassNativeShim.fireTimerCallback(timerId, 1, false));
        }
        assertEquals(0, MacGlassNativeShim.installTimerTable(MemorySegment.NULL));
        assertEquals(GMAC_ERR_NO_DATA, MacGlassNativeShim.fireTimerCallback(timerId, 1, true));
        MacGlassNativeShim.installTimerCallbacks();
        assertEquals(Integer.MIN_VALUE, MacGlassNativeShim.fireTimerCallback(timerId, 0, false));
        assertEquals(List.of("startPeriod(16)"), events);
    }

    @Test
    public void anIdTheRegistryNeverHeldRunsNothing() {
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireTimerCallback(Long.MAX_VALUE, 2, true));
    }

    @Test
    public void aTickRunsTheRunnableOfItsIdOnTheCallingThread() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        MacGlassNativeShim.startPeriodTimer(() -> events.add("run on " + Thread.currentThread().getName()), PERIOD,
                timers);
        int status = onFreshThread(CALLER, events,
                () -> MacGlassNativeShim.fireTimerCallback(timers.lastTimerId(), 3, false));
        assertEquals(GMAC_UPCALL_OK, status);
        assertEquals(List.of("startPeriod(16)", "run on " + CALLER, "run on " + CALLER, "run on " + CALLER), events);
    }

    @Test
    public void aPeriodTickOnANewThreadRunsOnOneUnnamedDaemonThread() {
        List<Thread> threads = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(new CopyOnWriteArrayList<>());
        MacGlassNativeShim.startPeriodTimer(() -> threads.add(Thread.currentThread()), PERIOD, timers);
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireTimerCallback(timers.lastTimerId(), 3, true));
        assertEquals(3, threads.size());
        assertEquals(1, new HashSet<>(threads).size(), "one new thread runs every tick of the call");
        Thread ticking = threads.getFirst();
        assertNotSame(Thread.currentThread(), ticking);
        assertTrue(ticking.isDaemon());
        assertTrue(ticking.getName().matches(UNNAMED_ATTACH), ticking.getName());
    }

    @Test
    public void aVsyncTickNamesItsThreadAsTheJniAttachedIt() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        MacGlassNativeShim.startVsyncTimer(() -> events.add("run on " + Thread.currentThread().getName()
                + (Thread.currentThread().isDaemon() ? " (daemon)" : "")), timers);
        long timerId = timers.lastTimerId();
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireTimerCallback(timerId, 2, true));
        String nameAfter = onFreshThread(CALLER, events, () -> {
            MacGlassNativeShim.fireTimerCallback(timerId, 1, false);
            return Thread.currentThread().getName();
        });
        String linkThread = MacGlassNativeShim.displayLinkThreadName();
        String paused = "paused #" + timerId;
        assertEquals("PulseTimer-CVDisplayLink thread", linkThread);
        assertEquals(List.of("startVsync",
                "run on " + linkThread + " (daemon)", paused,
                "run on " + linkThread + " (daemon)", paused,
                "run on " + linkThread, paused), events);
        assertEquals(linkThread, nameAfter);
    }

    @Test
    public void aNameTheRunnableGaveItsThreadLastsUntilTheTickAfterAPause() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        AtomicInteger ticks = new AtomicInteger();
        String renamed = "renamed by tick 1";
        MacGlassNativeShim.startVsyncTimer(() -> {
            int tick = ticks.incrementAndGet();
            events.add("run " + tick + " on " + Thread.currentThread().getName());
            if (tick == 1) {
                Thread.currentThread().setName(renamed);
            }
            if (tick == 2) {
                throw new IllegalStateException("tick 2");
            }
        }, timers);
        long timerId = timers.lastTimerId();
        String paused = "paused #" + timerId;
        String linkThread = MacGlassNativeShim.displayLinkThreadName();
        String nameAfter = onFreshThread(CALLER, events, () -> {
            for (int tick = 1; tick <= 5; tick++) {
                timers.paused = tick == 3;
                MacGlassNativeShim.fireTimerCallback(timerId, 1, false);
            }
            return Thread.currentThread().getName();
        });
        assertEquals(List.of("startVsync",
                "run 1 on " + linkThread, paused,
                "run 2 on " + renamed, paused,
                "run 3 on " + renamed, paused, "report java.lang.IllegalStateException: tick 2 on " + renamed,
                "run 4 on " + linkThread, paused,
                "run 5 on " + linkThread, paused), events);
        assertEquals(linkThread, nameAfter);
    }

    @Test
    public void aPeriodTickReportsWhatItsRunnableThrewAtOnce() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        AtomicInteger ticks = new AtomicInteger();
        MacGlassNativeShim.startPeriodTimer(() -> {
            int tick = ticks.incrementAndGet();
            events.add("run " + tick);
            throw new IllegalStateException("tick " + tick);
        }, PERIOD, timers);
        int status = onFreshThread(CALLER, events,
                () -> MacGlassNativeShim.fireTimerCallback(timers.lastTimerId(), 2, false));
        assertEquals(GMAC_UPCALL_THREW, status);
        assertEquals(List.of("startPeriod(16)",
                "run 1", "report java.lang.IllegalStateException: tick 1 on " + CALLER,
                "run 2", "report java.lang.IllegalStateException: tick 2 on " + CALLER), events);
    }

    @Test
    public void aPeriodTickOnANewThreadReportsToThatThreadsHandler() {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        MacGlassNativeShim.startPeriodTimer(() -> {
            Thread.currentThread().setUncaughtExceptionHandler(recordingHandler(events));
            throw new IllegalStateException("on a new thread");
        }, PERIOD, timers);
        assertEquals(GMAC_UPCALL_THREW, MacGlassNativeShim.fireTimerCallback(timers.lastTimerId(), 1, true));
        assertEquals(2, events.size(), events.toString());
        assertTrue(events.get(1).matches("report java.lang.IllegalStateException: on a new thread on "
                + UNNAMED_ATTACH), events.get(1));
    }

    @Test
    public void aVsyncTickHoldsTheLastThrowUntilATickLeavesTheTimerPaused() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        AtomicInteger ticks = new AtomicInteger();
        MacGlassNativeShim.startVsyncTimer(() -> {
            int tick = ticks.incrementAndGet();
            events.add("run " + tick);
            if (tick == 1 || tick == 3 || tick == 6) {
                throw new IllegalStateException("tick " + tick);
            }
        }, timers);
        long timerId = timers.lastTimerId();
        String paused = "paused #" + timerId;
        String linkThread = MacGlassNativeShim.displayLinkThreadName();
        List<Integer> statuses = onFreshThread(CALLER, events, () -> {
            List<Integer> answered = new ArrayList<>();
            for (int tick = 1; tick <= 6; tick++) {
                timers.paused = tick >= 4;
                answered.add(MacGlassNativeShim.fireTimerCallback(timerId, 1, false));
            }
            return answered;
        });
        assertEquals(List.of(1, 0, 1, 0, 0, 1), statuses);
        assertEquals(List.of("startVsync",
                "run 1", paused,
                "run 2", paused,
                "run 3", paused,
                "run 4", paused, "report java.lang.IllegalStateException: tick 3 on " + linkThread,
                "run 5", paused,
                "run 6", paused, "report java.lang.IllegalStateException: tick 6 on " + linkThread), events);
    }

    @Test
    public void aVsyncTickBeforeItsStartReturnedNeverAsksWhetherTheTimerIsPaused() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        timers.paused = true;
        timers.duringStart = timerId -> events.add("tick during start: "
                + MacGlassNativeShim.fireTimerCallback(timerId, 1, false));
        AtomicInteger ticks = new AtomicInteger();
        String linkThread = MacGlassNativeShim.displayLinkThreadName();
        int status = onFreshThread(CALLER, events, () -> {
            MacGlassNativeShim.startVsyncTimer(() -> {
                int tick = ticks.incrementAndGet();
                events.add("run " + tick);
                if (tick == 1) {
                    throw new IllegalStateException("before the start returned");
                }
            }, timers);
            return MacGlassNativeShim.fireTimerCallback(timers.lastTimerId(), 1, false);
        });
        assertEquals(GMAC_UPCALL_OK, status);
        assertEquals(List.of("startVsync", "run 1", "tick during start: 1",
                "run 2", "paused #" + timers.lastTimerId(),
                "report java.lang.IllegalStateException: before the start returned on " + linkThread), events);
    }

    @Test
    public void whatAVsyncTickThrewOnAThreadThatEndsUnpausedIsNeverReported() {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        MacGlassNativeShim.startVsyncTimer(() -> {
            Thread.currentThread().setUncaughtExceptionHandler(recordingHandler(events));
            throw new IllegalStateException("held");
        }, timers);
        assertEquals(GMAC_UPCALL_THREW, MacGlassNativeShim.fireTimerCallback(timers.lastTimerId(), 2, true));
        assertEquals(List.of("startVsync", "paused #" + timers.lastTimerId(), "paused #" + timers.lastTimerId()),
                events);
    }

    @Test
    public void aStoppedPeriodTimerTicksNothingMore() {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        long timer = MacGlassNativeShim.startPeriodTimer(() -> events.add("run"), PERIOD, timers);
        long timerId = timers.lastTimerId();
        assertEquals(RecordingTimers.timerOf(timerId), timer);
        MacGlassNativeShim.fireTimerCallback(timerId, 1, false);
        MacGlassNativeShim.stopTimer(timer, timers);
        assertEquals(GMAC_UPCALL_OK, MacGlassNativeShim.fireTimerCallback(timerId, 1, false));
        assertEquals(List.of("startPeriod(16)", "run", "stop #" + timerId), events);
    }

    @Test
    public void aStopEndsNeitherAZeroMsPeriodTimerNorAVsyncTimer() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        long periodTimer = MacGlassNativeShim.startPeriodTimer(() -> events.add("run period"), 0, timers);
        long periodTimerId = timers.lastTimerId();
        long vsyncTimer = MacGlassNativeShim.startVsyncTimer(() -> events.add("run vsync"), timers);
        long vsyncTimerId = timers.lastTimerId();
        MacGlassNativeShim.stopTimer(periodTimer, timers);
        MacGlassNativeShim.stopTimer(vsyncTimer, timers);
        MacGlassNativeShim.fireTimerCallback(periodTimerId, 1, false);
        onFreshThread(CALLER, events, () -> MacGlassNativeShim.fireTimerCallback(vsyncTimerId, 1, false));
        assertEquals(List.of("startPeriod(0)", "startVsync", "stop #" + periodTimerId, "stop #" + vsyncTimerId,
                "run period", "run vsync", "paused #" + vsyncTimerId), events);
    }

    @Test
    public void aStartThatAnswersNullKeepsNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        timers.failStarts = true;
        assertEquals(0L, MacGlassNativeShim.startPeriodTimer(() -> events.add("run period"), PERIOD, timers));
        long periodTimerId = timers.lastTimerId();
        assertEquals(0L, MacGlassNativeShim.startVsyncTimer(() -> events.add("run vsync"), timers));
        long vsyncTimerId = timers.lastTimerId();
        MacGlassNativeShim.fireTimerCallback(periodTimerId, 1, false);
        onFreshThread(CALLER, events, () -> MacGlassNativeShim.fireTimerCallback(vsyncTimerId, 1, false));
        assertEquals(List.of("startPeriod(16)", "startVsync"), events);
    }

    @Test
    public void aPeriodTimerAtTheAddressOfAStoppedOneIsItsOwnTimer() {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        MacGlassNativeShim.startPeriodTimer(() -> events.add("run first"), PERIOD, timers);
        long firstId = timers.lastTimerId();
        long address = RecordingTimers.timerOf(firstId);
        MacGlassNativeShim.stopTimer(address, timers);
        timers.reuse = address;
        MacGlassNativeShim.startPeriodTimer(() -> events.add("run second"), PERIOD, timers);
        long secondId = timers.lastTimerId();
        MacGlassNativeShim.fireTimerCallback(firstId, 1, false);
        MacGlassNativeShim.fireTimerCallback(secondId, 1, false);
        MacGlassNativeShim.stopTimer(address, timers);
        MacGlassNativeShim.fireTimerCallback(secondId, 1, false);
        assertEquals(List.of("startPeriod(16)", "stop #" + firstId, "startPeriod(16)", "run second",
                "stop #" + firstId), events);
    }

    @Test
    public void concurrentStartsAndTicksLoseNoTimer() throws Exception {
        int threadCount = 8;
        int startsPerThread = 50;
        Map<Long, AtomicInteger> runs = new ConcurrentHashMap<>();
        Set<Long> timerIds = ConcurrentHashMap.newKeySet();
        RecordingTimers timers = new RecordingTimers(new CopyOnWriteArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        List<FutureTask<Integer>> tasks = new ArrayList<>();
        for (int thread = 0; thread < threadCount; thread++) {
            FutureTask<Integer> task = new FutureTask<>(() -> {
                go.await();
                int failures = 0;
                for (int start = 0; start < startsPerThread; start++) {
                    AtomicInteger counter = new AtomicInteger();
                    long timer = MacGlassNativeShim.startPeriodTimer(counter::incrementAndGet, PERIOD, timers);
                    long timerId = timer - RecordingTimers.timerOf(0);
                    runs.put(timerId, counter);
                    timerIds.add(timerId);
                    if (MacGlassNativeShim.fireTimerCallback(timerId, 1, false) != GMAC_UPCALL_OK) {
                        failures++;
                    }
                }
                return failures;
            });
            tasks.add(task);
            Thread.ofPlatform().name("MacTimerNativeTest-starter-" + thread).start(task);
        }
        go.countDown();
        for (FutureTask<Integer> task : tasks) {
            assertEquals(0, task.get(60, TimeUnit.SECONDS));
        }
        assertEquals(threadCount * startsPerThread, timerIds.size(), "every start got an id of its own");
        for (Map.Entry<Long, AtomicInteger> run : runs.entrySet()) {
            assertEquals(1, run.getValue().get(), "the runnable of id " + run.getKey());
        }
    }

    @Test
    public void theMinAndMaxPeriodsAreTheJniConstants() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<Integer> periods = onFreshThread(CALLER, events,
                () -> List.of(MacGlassNativeShim.timerMinPeriod(), MacGlassNativeShim.timerMaxPeriod()));
        assertEquals(List.of(0, 1000000), periods);
        assertEquals(List.of(), events);
    }

    @Test
    public void detachedMinAndMaxPeriodsReportOnceEach() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        MacGlassNativeShim.setMainJavaAttached(0);
        List<Integer> periods = onFreshThread(CALLER, events, () -> {
            int min = MacGlassNativeShim.timerMinPeriod();
            events.add("min " + min);
            int max = MacGlassNativeShim.timerMaxPeriod();
            events.add("max " + max);
            return List.of(min, max);
        });
        assertEquals(List.of(0, 1000000), periods);
        assertEquals(List.of("report " + DETACHED + " on " + CALLER, "min 0",
                "report " + DETACHED + " on " + CALLER, "max 1000000"), events);
    }

    @Test
    public void everyDetachedCallRunsItsBodyAndThenReportsOnce() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        MacGlassNativeShim.setMainJavaAttached(0);
        String report = "report " + DETACHED + " on " + CALLER;
        List<Long> ids = onFreshThread(CALLER, events, () -> {
            long period = MacGlassNativeShim.startPeriodTimer(() -> { }, PERIOD, timers);
            long periodId = timers.lastTimerId();
            long vsync = MacGlassNativeShim.startVsyncTimer(() -> { }, timers);
            long vsyncId = timers.lastTimerId();
            MacGlassNativeShim.pauseTimer(vsync, timers);
            MacGlassNativeShim.resumeTimer(vsync, timers);
            MacGlassNativeShim.stopTimer(period, timers);
            return List.of(periodId, vsyncId);
        });
        assertEquals(List.of("startPeriod(16)", report, "startVsync", report, "pause #" + ids.get(1), report,
                "resume #" + ids.get(1), report, "stop #" + ids.get(0), report), events);
    }

    @Test
    public void attachedCallsReportNothing() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingTimers timers = new RecordingTimers(events);
        List<Long> ids = onFreshThread(CALLER, events, () -> {
            long vsync = MacGlassNativeShim.startVsyncTimer(() -> { }, timers);
            long vsyncId = timers.lastTimerId();
            MacGlassNativeShim.pauseTimer(vsync, timers);
            MacGlassNativeShim.resumeTimer(vsync, timers);
            MacGlassNativeShim.stopTimer(vsync, timers);
            return List.of(vsyncId);
        });
        assertEquals(List.of("startVsync", "pause #" + ids.get(0), "resume #" + ids.get(0), "stop #" + ids.get(0)),
                events);
    }

    @Test
    public void noTickThrowsEvenWhenTheHandlerItselfThrows() throws Exception {
        Map<String, String> child = MacGlassNativeTest.runChild(ThrowingTickChild.class);
        assertEquals("1,1", child.get("statuses"));
        assertEquals("4", child.get("reports"), "two period ticks reported at once, two vsync ticks after a pause");
        String log = Files.readString(MacGlassNativeTest.childLog(ThrowingTickChild.class), StandardCharsets.UTF_8);
        String line = "\nException: java.lang.AssertionError thrown from the UncaughtExceptionHandler in thread \""
                + MacGlassNativeShim.displayLinkThreadName() + "\"\n";
        assertEquals(2, occurrences(log, line), "HotSpot's line once per vsync dispatch, none for a period report; "
                + MacGlassNativeTest.childLog(ThrowingTickChild.class));
        assertEquals(2, occurrences(log, "thrown from the UncaughtExceptionHandler"));
        assertEquals(0, occurrences(log, "Exception in thread"), "nothing reached a default handler");
    }

    @Test
    public void noTickThrowsWhenItsOwnStepsFail() throws Exception {
        Map<String, String> child = MacGlassNativeTest.runChild(FailingPausedChild.class);
        assertEquals(String.valueOf(GMAC_UPCALL_THREW), child.get("status"));
        String paused = "paused #" + child.get("timerId");
        String report = "report java.lang.UnsatisfiedLinkError: gmac_timer_paused on "
                + MacGlassNativeShim.displayLinkThreadName();
        assertEquals(List.of("startVsync", paused, report, paused, report), List.of(child.get("events").split("\\|")),
                "each tick reports the failed step, and what the runnable threw stays held");
    }

    /**
     * Ticks a period timer and a vsync timer that the stand-in reports paused, twice each, on a new thread, with
     * runnables that throw, the vsync one an {@link Error}, and that make every report throw too; writes the statuses
     * and the number of reports to the file {@code args[0]}. The main thread counts as attached, so the starts report
     * nothing. A throwable that escaped the slot would end this JVM, so
     * a written file is the proof.
     */
    public static final class ThrowingTickChild {

        public static void main(String[] args) throws Exception {
            Path result = Path.of(args[0]);
            if (!MacGlassNativeShim.nsAppIsNil()) {
                Files.writeString(result, "nsAppNilBefore=false\n", StandardCharsets.UTF_8);
                return;
            }
            MacGlassNativeShim.loadLibrary();
            MacGlassNativeShim.setMainJavaAttached(1);
            MacGlassNativeShim.installTimerCallbacks();
            AtomicInteger reports = new AtomicInteger();
            Thread.UncaughtExceptionHandler refusing = (thread, e) -> {
                reports.incrementAndGet();
                throw new AssertionError("the report of " + e + " threw");
            };
            RecordingTimers timers = new RecordingTimers(new CopyOnWriteArrayList<>());
            timers.paused = true;
            MacGlassNativeShim.startPeriodTimer(() -> {
                Thread.currentThread().setUncaughtExceptionHandler(refusing);
                throw new IllegalStateException("period tick");
            }, PERIOD, timers);
            long periodTimerId = timers.lastTimerId();
            MacGlassNativeShim.startVsyncTimer(() -> {
                Thread.currentThread().setUncaughtExceptionHandler(refusing);
                throw new Error("vsync tick");
            }, timers);
            long vsyncTimerId = timers.lastTimerId();
            int periodStatus = MacGlassNativeShim.fireTimerCallback(periodTimerId, 2, true);
            int vsyncStatus = MacGlassNativeShim.fireTimerCallback(vsyncTimerId, 2, true);
            boolean nsAppNilAfter = MacGlassNativeShim.nsAppIsNil();
            Files.writeString(result, "statuses=" + periodStatus + "," + vsyncStatus
                    + "\nreports=" + reports.get()
                    + "\nnsAppNilBefore=true"
                    + "\nnsAppNilAfter=" + nsAppNilAfter + "\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * Ticks a vsync timer twice on a new thread, with a runnable that throws and a stand-in whose {@code paused}
     * fails as the handle of a symbol the library lacks fails; writes the status, the timer's id and the events to
     * the file {@code args[0]}. A throwable that escaped the slot would end this JVM.
     */
    public static final class FailingPausedChild {

        public static void main(String[] args) throws Exception {
            Path result = Path.of(args[0]);
            if (!MacGlassNativeShim.nsAppIsNil()) {
                Files.writeString(result, "nsAppNilBefore=false\n", StandardCharsets.UTF_8);
                return;
            }
            MacGlassNativeShim.loadLibrary();
            MacGlassNativeShim.setMainJavaAttached(1);
            MacGlassNativeShim.installTimerCallbacks();
            List<String> events = new CopyOnWriteArrayList<>();
            RecordingTimers timers = new RecordingTimers(events);
            timers.pausedFailure = new UnsatisfiedLinkError("gmac_timer_paused");
            MacGlassNativeShim.startVsyncTimer(() -> {
                Thread.currentThread().setUncaughtExceptionHandler(recordingHandler(events));
                throw new IllegalStateException("vsync tick");
            }, timers);
            long timerId = timers.lastTimerId();
            int status = MacGlassNativeShim.fireTimerCallback(timerId, 2, true);
            boolean nsAppNilAfter = MacGlassNativeShim.nsAppIsNil();
            Files.writeString(result, "status=" + status
                    + "\ntimerId=" + timerId
                    + "\nevents=" + String.join("|", events)
                    + "\nnsAppNilBefore=true"
                    + "\nnsAppNilAfter=" + nsAppNilAfter + "\n", StandardCharsets.UTF_8);
        }
    }

    /**
     * Records each call, naming a timer {@code #id} after the first start that answered it. A start answers
     * {@link #timerOf} its id, or {@link #reuse} when that is set, or 0 when {@link #failStarts}; {@code paused}
     * answers {@link #paused}, or throws {@link #pausedFailure} when that is set.
     */
    private static final class RecordingTimers implements TimerStandIn {

        private static final long TIMER_BASE = 0x7E57_0000_0000L;

        volatile boolean paused;
        volatile Error pausedFailure;
        volatile boolean failStarts;
        volatile long reuse;
        volatile LongConsumer duringStart = timerId -> { };

        private final List<String> events;
        private final List<Long> timerIds = new CopyOnWriteArrayList<>();
        private final Map<Long, Long> idsByTimer = new ConcurrentHashMap<>();

        RecordingTimers(List<String> events) {
            this.events = events;
        }

        static long timerOf(long timerId) {
            return TIMER_BASE + timerId;
        }

        long lastTimerId() {
            return timerIds.getLast();
        }

        @Override
        public long startPeriod(long timerId, int period) {
            events.add("startPeriod(" + period + ")");
            return started(timerId);
        }

        @Override
        public long startVsync(long timerId) {
            events.add("startVsync");
            return started(timerId);
        }

        @Override
        public void stop(long timer) {
            events.add("stop " + describe(timer));
        }

        @Override
        public void pause(long timer) {
            events.add("pause " + describe(timer));
        }

        @Override
        public void resume(long timer) {
            events.add("resume " + describe(timer));
        }

        @Override
        public boolean paused(long timer) {
            events.add("paused " + describe(timer));
            Error failure = pausedFailure;
            if (failure != null) {
                throw failure;
            }
            return paused;
        }

        private long started(long timerId) {
            timerIds.add(timerId);
            duringStart.accept(timerId);
            if (failStarts) {
                return 0L;
            }
            long timer = reuse != 0L ? reuse : timerOf(timerId);
            idsByTimer.putIfAbsent(timer, timerId);
            return timer;
        }

        /** By the id of the first start that answered this timer. */
        private String describe(long timer) {
            return "#" + idsByTimer.get(timer);
        }
    }

    private static Thread.UncaughtExceptionHandler recordingHandler(List<String> events) {
        return (thread, e) -> events.add("report " + e + " on " + thread.getName());
    }

    /** Runs {@code call} on a new non-daemon platform thread named {@code name}, whose reports go to {@code events}. */
    private static <T> T onFreshThread(String name, List<String> events, Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Thread thread = Thread.ofPlatform().name(name).daemon(false).unstarted(task);
        thread.setUncaughtExceptionHandler(recordingHandler(events));
        thread.start();
        return task.get(30, TimeUnit.SECONDS);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }
}
