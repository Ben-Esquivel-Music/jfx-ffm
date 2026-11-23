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

package test.com.sun.javafx.font;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A call that a race test of the font layer runs on a daemon platform thread of its own, and that a fake of the test
 * holds once, where the test chooses, until the test releases it. Every wait of the test is bounded and, past its
 * bound, fails with the state and stack of the threads involved, so a regression that blocks a thread fails the test
 * instead of hanging the fork; a held thread that is never released gives up after twice that bound, so that the
 * test's wait fails first and shows the threads as they were. Pure Java: it loads no font class.
 *
 * @param <V> what the call answers
 */
final class RaceThread<V> {

    /** The bound of every wait. */
    static final Duration BOUND = Duration.ofSeconds(20);

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final CountDownLatch settled = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final FutureTask<V> task;
    private final Thread thread;
    private volatile boolean held;

    /** The call on a thread that has not started yet, so that a fake can be armed with it before it runs. */
    RaceThread(String name, Callable<V> call) {
        task = new FutureTask<>(() -> {
            try {
                return call.call();
            } finally {
                settled.countDown();
            }
        });
        thread = Thread.ofPlatform().daemon().name(name).unstarted(task);
    }

    RaceThread<V> start() {
        thread.start();
        return this;
    }

    /** Whether the calling thread is this one: a fake holds only the thread it is armed with. */
    boolean isCurrent() {
        return Thread.currentThread() == thread;
    }

    /** Whether a fake has held this thread. */
    boolean held() {
        return held;
    }

    /**
     * Called by a fake on this thread: marks it held, tells the test, and waits until the test releases it, or twice
     * the bound passes.
     */
    void hold() {
        held = true;
        settled.countDown();
        Duration limit = BOUND.multipliedBy(2);
        try {
            if (!release.await(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(thread.getName() + " was held and not released within " + limit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(thread.getName() + " was interrupted while it was held", e);
        }
    }

    /** Waits until a fake holds this thread or its call ends; fails past the bound. */
    void awaitHeldOrEnded(RaceThread<?>... others) throws InterruptedException {
        if (!settled.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
            fail(thread.getName() + " was neither held nor ended within " + BOUND + ":\n" + states(others));
        }
    }

    /** Lets this thread go on where a fake holds it; harmless where none does. */
    void release() {
        release.countDown();
    }

    /** {@link #blocksOn(RaceThread, Object)} on any monitor of {@code owner}. */
    boolean blocksOn(RaceThread<?> owner) {
        return blocksOn(owner, null);
    }

    /**
     * Whether this thread, started while {@code owner} is held, blocks on a monitor that {@code owner} holds,
     * {@code lock} where that is not null, before its call ends: a thread that needs such a monitor ends up blocked
     * on it and one that does not ends, so the pause between looks decides nothing, and a block on a monitor of any
     * other owner does not count. Fails past the bound. Platform threads only: the thread bean gives no lock of a
     * virtual thread.
     */
    boolean blocksOn(RaceThread<?> owner, Object lock) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (thread.getState() != Thread.State.TERMINATED) {
            ThreadInfo info = THREADS.getThreadInfo(thread.threadId());
            LockInfo awaited = info == null ? null : info.getLockInfo();
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == owner.thread.threadId() && awaited != null
                    && (lock == null || awaited.getIdentityHashCode() == System.identityHashCode(lock))) {
                return true;
            }
            if (System.nanoTime() - deadline > 0) {
                fail(thread.getName() + " neither blocked on a monitor that " + owner.thread.getName() + " holds nor"
                        + " ended within " + BOUND + ":\n" + states(owner));
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return false;
    }

    /** What the call answered, or what it threw, once this thread has ended; fails past the bound. */
    Ended<V> end(RaceThread<?>... others) throws InterruptedException {
        if (!thread.join(BOUND)) {
            fail(thread.getName() + " did not end within " + BOUND + ":\n" + states(others));
        }
        try {
            return new Ended<>(thread.getName(), task.get(), null);
        } catch (ExecutionException e) {
            return new Ended<>(thread.getName(), null, e.getCause());
        }
    }

    /** The state and stack of this thread and of {@code others}, from the thread bean. */
    private String states(RaceThread<?>... others) {
        StringBuilder states = new StringBuilder(state());
        for (RaceThread<?> other : others) {
            states.append(other.state());
        }
        return states.toString();
    }

    private String state() {
        ThreadInfo info = THREADS.getThreadInfo(thread.threadId(), Integer.MAX_VALUE);
        if (info == null) {
            return thread.getName() + " has ended\n";
        }
        return info + Arrays.stream(info.getStackTrace()).skip(8).map(frame -> "\tat " + frame + "\n")
                .collect(Collectors.joining());
    }

    /**
     * What the call of a thread answered, or what it threw.
     *
     * @param <V> what the call answers
     */
    record Ended<V>(String name, V answer, Throwable thrown) {

        /** What the call answered; fails, with what it threw as the cause, where it threw. */
        V answered(String what) {
            if (thrown != null) {
                fail(what + ": " + name + " threw " + thrown, thrown);
            }
            return answer;
        }
    }
}
