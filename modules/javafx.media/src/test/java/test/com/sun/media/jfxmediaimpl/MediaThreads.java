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

package test.com.sun.media.jfxmediaimpl;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * What a media test can say about the threads a playback took and gave back, and - just as important -
 * what it cannot.
 * <p>
 * <b>What it can see.</b> Every player owns two JVM threads. {@code NativeMediaPlayer} starts one
 * {@link #EVENT_QUEUE} per player in its constructor and ends it from {@code dispose()}, and
 * {@code createMediaPulse} adds a daemon {@code Timer} thread from the first {@code play()} that
 * {@code destroyMediaPulse} cancels in the same {@code dispose()}. Both are ordinary Java threads, so
 * {@link ThreadMXBean} counts them, and both are created <em>per player</em> - which is per
 * {@code AudioClip.play()}, because {@code NativeMediaAudioClipPlayer.play} builds a whole player for
 * every clip playback. A player that is not disposed, or a dispose that stops reaching the event loop,
 * therefore shows up here as a count that grows with the number of playbacks and never comes back down.
 * That is the shape of leak this class exists to catch, and until it existed nothing in the fork could
 * see it at all.
 * <p>
 * <b>What it cannot see, and why it is said here rather than left implied.</b> A JVM thread count counts
 * threads the JVM knows about. GStreamer's streaming, bus and sink threads are created by GLib and never
 * attach, and so is the notification thread {@code InitNotificator} starts for every
 * {@code directsoundsink} element ({@code gstdirectsoundnotify.cpp}). None of them appears in
 * {@link #names()}. The {@code GSTDirectSoundNotify} object that thread belongs to is deliberately never
 * freed - the trade is argued in that file, and it is proportional to playbacks rather than to live
 * players - and it is about eighty bytes plus an {@code SRWLOCK}: twenty playbacks leak under two
 * kilobytes, against the megabytes a single GStreamer pipeline allocates and frees around them. No
 * measurement Java can make of native memory has the resolution to separate the two, so none is
 * attempted; asserting on process memory here would produce a test that fails for reasons unrelated to
 * the thing it names. What the twenty cycles of
 * {@code MediaPlaybackTest.everyAudioClipPlaybackGivesBackTheThreadsItTook} do cover of that path is its
 * <em>liveness</em>: each one runs an {@code InitNotificator}/{@code ReleaseNotificator} pair including
 * the untimed join in {@code ReleaseNotificator}, under a test timeout, so a join that stops returning
 * fails that test instead of wedging the build.
 */
final class MediaThreads {

    /** The name {@code NativeMediaPlayer.EventQueueThread} gives itself: one live thread per live player. */
    static final String EVENT_QUEUE = "JFXMedia Player EventQueueThread";

    /** How often {@link #awaitCountAtMost} looks again. Thread termination is asynchronous, not slow. */
    private static final long POLL_MILLIS = 25L;

    private MediaThreads() {
    }

    /**
     * @return the name of every live JVM thread, one entry per thread. Names repeat; that is the point.
     */
    static List<String> names() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        // maxDepth 0: the names are wanted, the stack traces are not, and asking for them would safepoint
        // the VM on every poll.
        List<String> live = new ArrayList<>();
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (info != null) {          // null for a thread that died between the two calls
                live.add(info.getThreadName());
            }
        }
        return live;
    }

    /**
     * @param name the thread name to count
     * @return how many live JVM threads carry exactly that name
     */
    static int count(String name) {
        return (int) names().stream().filter(name::equals).count();
    }

    /**
     * Waits for the number of live threads named {@code name} to fall to {@code limit} or below, and
     * fails naming the whole live thread population if it does not.
     * <p>
     * A wait rather than an immediate assertion because the teardown being checked is asynchronous:
     * {@code EventQueueThread.terminateLoop} sets a flag and posts a wake-up event, and
     * {@code Timer.cancel} lets the timer thread notice at its own pace, so both threads outlive the
     * {@code dispose()} that ended them by a little. A leak does not shrink with waiting, so a bound
     * that a healthy teardown clears in milliseconds still fails a real one in full.
     *
     * @param name the thread name to count
     * @param limit the number of threads of that name that may still be live
     * @param timeoutMillis how long teardown gets
     * @param because what the caller was testing, for the failure message
     */
    static void awaitCountAtMost(String name, int limit, long timeoutMillis, String because) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int live = count(name);
        while (live > limit && System.currentTimeMillis() < deadline) {
            sleep();
            live = count(name);
        }
        if (live > limit) {
            fail(because + ": " + live + " threads named \"" + name + "\" are still live after "
                    + timeoutMillis + " ms, expected at most " + limit + ". " + population());
        }
    }

    /**
     * The same for the JVM's whole thread population, which catches a leak of threads this test did not
     * think to name. Deliberately given room: a JVM under test starts and stops compiler, GC, reference
     * handler and common-pool threads on its own schedule, so a bound here is only meaningful when it is
     * far below the number of playbacks the caller ran and far above the handful the JVM moves by itself.
     *
     * @param limit the total number of live threads allowed
     * @param timeoutMillis how long teardown gets
     * @param because what the caller was testing, for the failure message
     */
    static void awaitTotalAtMost(int limit, long timeoutMillis, String because) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int live = names().size();
        while (live > limit && System.currentTimeMillis() < deadline) {
            sleep();
            live = names().size();
        }
        if (live > limit) {
            fail(because + ": " + live + " JVM threads are still live after " + timeoutMillis
                    + " ms, expected at most " + limit + ". " + population());
        }
    }

    /** Every live thread name with how many threads carry it, most numerous first. */
    private static String population() {
        Map<String, Integer> byName = new TreeMap<>();
        for (String name : names()) {
            byName.merge(name, 1, Integer::sum);
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        byName.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return "The live threads were " + sorted;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for threads to be given back", e);
        }
    }
}
