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

package test.com.sun.webkit.ffm;

import com.sun.webkit.WKJLongMapShim;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The long-keyed concurrent map behind the {@code wkj_ref} registry. It replaces a
 * {@code ConcurrentHashMap<Long, Entry>} whose every lookup boxed a {@code Long} on the hottest
 * upcall path in the module, so the contract it has to honour is the subset of
 * {@code ConcurrentHashMap}'s the registry uses: lock-free reads that are safe against concurrent
 * writers, insert-or-replace {@code put}, the two-argument compare-then-remove, and a size. The map
 * is pure Java and needs no native library, but it is tagged {@code ffm} so that it runs in the
 * same surefire execution as the rest of the binding layer it belongs to.
 */
@Tag("ffm")
public class WKJLongMapTest {

    /** Enough entries to force several doublings of the 64-bin initial table. */
    private static final int MANY = 5_000;

    /** Keys the churn writer of the concurrency test cycles through, far from the stable range. */
    private static final long CHURN_BASE = 1_000_000L;
    private static final int CHURN_KEYS = 64;

    private static final int READERS = 4;
    private static final int CHURN_CYCLES = 20_000;

    private final WKJLongMapShim map = new WKJLongMapShim();

    @Test
    public void anAbsentKeyAnswersNull() {
        assertNull(map.get(0L));
        assertNull(map.get(1L));
        assertNull(map.get(-1L));
        assertNull(map.get(Long.MAX_VALUE));
        assertNull(map.get(Long.MIN_VALUE));
        assertEquals(0, map.size());
    }

    @Test
    public void putGetRemoveRoundTrip() {
        Object value = new Object();
        map.put(7L, value);

        assertSame(value, map.get(7L));
        assertEquals(1, map.size());
        assertNull(map.get(8L), "a neighbouring key must stay absent");

        assertTrue(map.remove(7L, value));
        assertNull(map.get(7L));
        assertEquals(0, map.size());
    }

    @Test
    public void putReplacesAnExistingMapping() {
        Object first = new Object();
        Object second = new Object();
        map.put(42L, first);
        map.put(42L, second);

        assertSame(second, map.get(42L));
        assertEquals(1, map.size(), "a replacement is not a second mapping");
    }

    @Test
    public void removeIsConditionalOnTheExpectedValue() {
        Object value = new Object();
        map.put(3L, value);

        assertFalse(map.remove(3L, new Object()),
                "a stale releaser must not remove an entry it does not own");
        assertSame(value, map.get(3L), "the mapping must survive the refused removal");
        assertFalse(map.remove(4L, value), "removing an absent key is a no-op");

        assertTrue(map.remove(3L, value));
        assertFalse(map.remove(3L, value), "a second removal finds nothing");
        assertEquals(0, map.size());
    }

    /**
     * Keys that differ only in their upper bits are the ones a naive {@code hash & (length - 1)}
     * would pile into one bin - or worse, treat as equal. Every shape below must be a distinct,
     * retrievable mapping, through several table doublings.
     */
    @Test
    public void keysDifferingOnlyInHighBitsStayDistinctAcrossResizes() {
        List<Long> keys = new ArrayList<>();
        for (long i = 1; i <= MANY; i++) {
            keys.add(i);
        }
        for (long i = 1; i <= 64; i++) {
            keys.add(i << 16);
            keys.add(i << 32);
            keys.add(i << 48);
            keys.add(-i);
        }
        for (Long key : keys) {
            map.put(key, "value-" + key);
        }
        assertEquals(keys.size(), map.size());
        for (Long key : keys) {
            assertEquals("value-" + key, map.get(key), "key " + key + " lost its value");
        }
        for (Long key : keys) {
            assertTrue(map.remove(key, "value-" + key));
        }
        assertEquals(0, map.size());
    }

    /**
     * The registry's actual concurrency shape: reads from WebKit's threads racing the rare writes
     * of peer creation and disposal. Stable mappings must answer their exact values at every moment
     * while another thread puts and removes other keys - including across the resizes the churn
     * provokes - and a churn key must answer either {@code null} or its current value, never
     * anything else and never an exception.
     */
    @Test
    public void getsStayCorrectWhileAnotherThreadPutsAndRemoves() throws InterruptedException {
        int stableCount = 512;
        Object[] stable = new Object[stableCount];
        for (int i = 0; i < stableCount; i++) {
            stable[i] = "stable-" + i;
            map.put(i + 1, stable[i]);
        }

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch running = new CountDownLatch(READERS);
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < READERS; r++) {
            Thread reader = new Thread(() -> {
                running.countDown();
                try {
                    while (!stop.get()) {
                        for (int i = 0; i < stableCount; i++) {
                            Object value = map.get(i + 1);
                            if (value != stable[i]) {
                                throw new AssertionError("stable key " + (i + 1)
                                        + " answered " + value);
                            }
                        }
                        for (long key = CHURN_BASE; key < CHURN_BASE + CHURN_KEYS; key++) {
                            Object value = map.get(key);
                            if (value != null && !value.equals("churn-" + key)) {
                                throw new AssertionError("churn key " + key + " answered " + value);
                            }
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "WKJLongMapTest-reader-" + r);
            reader.setDaemon(true);
            reader.start();
            readers.add(reader);
        }

        running.await();
        for (int cycle = 0; cycle < CHURN_CYCLES && failure.get() == null; cycle++) {
            long key = CHURN_BASE + (cycle % CHURN_KEYS);
            String value = "churn-" + key;
            map.put(key, value);
            assertTrue(map.remove(key, value));
        }
        stop.set(true);
        for (Thread reader : readers) {
            reader.join(30_000);
            assertFalse(reader.isAlive(), "a reader failed to stop");
        }
        if (failure.get() != null) {
            throw new AssertionError("a concurrent reader saw an impossible state", failure.get());
        }
        assertEquals(stableCount, map.size(),
                "every churn mapping was removed, so only the stable ones remain");
    }
}
